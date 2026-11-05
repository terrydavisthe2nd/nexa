/**
 * What is running on Windows, through koffi (its native module ships beside the
 * app, resources/koffi), as main/appAudio.ts asks about a window's program. The
 * snapshot is cheap; the path, start time and command line take opening the
 * process, so each is asked once per process.
 *
 * Every call here blocks until the system answers, so this runs only on a worker
 * thread (windowsWorker.ts): a call that never returns must never freeze the app.
 * If koffi cannot be loaded nothing is listed, and games show only through Rich
 * Presence, as they did before.
 */
import { wantsArgs, type Proc } from "./match";
import { GAME_WINDOW, GPU_BUSY, splitCommandLine, type Foreground } from "./processes";

const TH32CS_SNAPPROCESS = 0x2;
const PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
const INVALID_HANDLE = -1n;
const ProcessCommandLineInformation = 60;
const GW_OWNER = 4;
const MONITOR_DEFAULTTONEAREST = 2;
const PDH_FMT_DOUBLE = 0x200;
/** PROCESSENTRY32W on 64-bit Windows, the only Windows the app ships for. */
const ENTRY_SIZE = 568;
const ENTRY_PID = 8;
const ENTRY_PPID = 32;
const ENTRY_FILE = 44;
/** Windows counts time from 1601, in hundreds of nanoseconds. */
const FILETIME_UNIX = 11644473600000n;

export interface WindowsApi {
  snapshot(): Proc[];
  windows(): Map<number, string>;
  gameWindows(): Set<number>;
  foreground(): Foreground | null;
  gpuBusy(): Set<number>;
  product(file: string): string | null;
}

interface Known {
  file: string;
  ppid: number;
  path: string;
  args?: string[];
  startedAt?: number;
}

export function loadWindowsApi(log: (msg: string, extra?: Record<string, unknown>) => void): WindowsApi | null {
  try {
    // required here, not imported: koffi loads its native module the moment it is required
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const koffi = require("koffi") as typeof import("koffi");
    const kernel32 = koffi.load("kernel32.dll");
    const user32 = koffi.load("user32.dll");
    const ntdll = koffi.load("ntdll.dll");
    const CreateToolhelp32Snapshot = kernel32.func("intptr_t __stdcall CreateToolhelp32Snapshot(uint32_t dwFlags, uint32_t th32ProcessID)");
    const Process32FirstW = kernel32.func("int __stdcall Process32FirstW(intptr_t hSnapshot, _Inout_ uint8_t *lppe)");
    const Process32NextW = kernel32.func("int __stdcall Process32NextW(intptr_t hSnapshot, _Inout_ uint8_t *lppe)");
    const OpenProcess = kernel32.func("intptr_t __stdcall OpenProcess(uint32_t dwDesiredAccess, int bInheritHandle, uint32_t dwProcessId)");
    const CloseHandle = kernel32.func("int __stdcall CloseHandle(intptr_t hObject)");
    const QueryFullProcessImageNameW = kernel32.func("int __stdcall QueryFullProcessImageNameW(intptr_t hProcess, uint32_t dwFlags, _Out_ uint8_t *lpExeName, _Inout_ uint32_t *lpdwSize)");
    const GetProcessTimes = kernel32.func("int __stdcall GetProcessTimes(intptr_t hProcess, _Out_ uint8_t *lpCreationTime, _Out_ uint8_t *lpExitTime, _Out_ uint8_t *lpKernelTime, _Out_ uint8_t *lpUserTime)");
    const NtQueryInformationProcess = ntdll.func("int32_t __stdcall NtQueryInformationProcess(intptr_t ProcessHandle, uint32_t ProcessInformationClass, _Out_ uint8_t *ProcessInformation, uint32_t ProcessInformationLength, _Out_ uint32_t *ReturnLength)");
    const EnumWindowsProc = koffi.proto("int __stdcall EnumWindowsProc(intptr_t hwnd, intptr_t lparam)");
    const EnumWindows = user32.func("int __stdcall EnumWindows(EnumWindowsProc *lpEnumFunc, intptr_t lParam)");
    void EnumWindowsProc;
    const IsWindowVisible = user32.func("int __stdcall IsWindowVisible(intptr_t hWnd)");
    const GetWindow = user32.func("intptr_t __stdcall GetWindow(intptr_t hWnd, uint32_t uCmd)");
    const GetWindowTextW = user32.func("int __stdcall GetWindowTextW(intptr_t hWnd, _Out_ uint8_t *lpString, int nMaxCount)");
    const GetWindowThreadProcessId = user32.func("uint32_t __stdcall GetWindowThreadProcessId(intptr_t hWnd, _Out_ uint32_t *lpdwProcessId)");

    const IsIconic = user32.func("int __stdcall IsIconic(intptr_t hWnd)");
    const GetForegroundWindow = user32.func("intptr_t __stdcall GetForegroundWindow()");
    const GetWindowRect = user32.func("int __stdcall GetWindowRect(intptr_t hWnd, _Out_ uint8_t *lpRect)");
    const MonitorFromWindow = user32.func("intptr_t __stdcall MonitorFromWindow(intptr_t hwnd, uint32_t dwFlags)");
    const GetMonitorInfoW = user32.func("int __stdcall GetMonitorInfoW(intptr_t hMonitor, _Inout_ uint8_t *lpmi)");
    const GetClassNameW = user32.func("int __stdcall GetClassNameW(intptr_t hWnd, _Out_ uint8_t *lpClassName, int nMaxCount)");
    const version = koffi.load("version.dll");
    const GetFileVersionInfoSizeW = version.func("uint32_t __stdcall GetFileVersionInfoSizeW(str16 lptstrFilename, _Out_ uint32_t *lpdwHandle)");
    const GetFileVersionInfoW = version.func("int __stdcall GetFileVersionInfoW(str16 lptstrFilename, uint32_t dwHandle, uint32_t dwLen, _Out_ uint8_t *lpData)");
    const VerQueryValueW = version.func("int __stdcall VerQueryValueW(uint8_t *pBlock, str16 lpSubBlock, _Out_ void **lplpBuffer, _Out_ uint32_t *puLen)");
    let pdh: { query: unknown; counter: unknown; collect: (q: unknown) => number; array: (c: unknown, fmt: number, size: number[], count: number[], buf: Buffer | null) => number; primed: boolean } | null | undefined;

    const known = new Map<number, Known>();

    const inspect = (pid: number, file: string, ppid: number): Known => {
      const out: Known = { file, ppid, path: file };
      const handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
      if (!handle) return out;
      try {
        const chars = 1024;
        const buf = Buffer.alloc(chars * 2);
        const size = [chars];
        if (QueryFullProcessImageNameW(handle, 0, buf, size)) out.path = buf.toString("utf16le", 0, Math.min(chars, Number(size[0] ?? 0)) * 2);
        const created = Buffer.alloc(8);
        const other = Buffer.alloc(8);
        if (GetProcessTimes(handle, created, other, other, other)) {
          const ms = created.readBigUInt64LE(0) / 10000n - FILETIME_UNIX;
          if (ms > 0n) out.startedAt = Number(ms);
        }
        if (wantsArgs(file)) {
          const info = Buffer.alloc(32 * 1024);
          const length = [0];
          if (NtQueryInformationProcess(handle, ProcessCommandLineInformation, info, info.length, length) === 0) {
            // a UNICODE_STRING (16 bytes here) whose text follows it in the same buffer
            const line = info.toString("utf16le", 16, Math.min(info.length, 16 + info.readUInt16LE(0)));
            out.args = splitCommandLine(line).slice(1);
          }
        }
      } finally {
        CloseHandle(handle);
      }
      return out;
    };

    return {
      snapshot() {
        const snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (!snap || BigInt(snap) === INVALID_HANDLE) return [];
        const out: Proc[] = [];
        const alive = new Set<number>();
        try {
          const entry = Buffer.alloc(ENTRY_SIZE);
          entry.writeUInt32LE(ENTRY_SIZE, 0);
          let more = Process32FirstW(snap, entry);
          while (more) {
            const pid = entry.readUInt32LE(ENTRY_PID);
            const ppid = entry.readUInt32LE(ENTRY_PPID);
            let end = ENTRY_FILE;
            while (end + 1 < ENTRY_SIZE && entry.readUInt16LE(end) !== 0) end += 2;
            const file = entry.toString("utf16le", ENTRY_FILE, end);
            if (pid > 4) {
              alive.add(pid);
              let k = known.get(pid);
              // a pid can come back as another program
              if (!k || k.file !== file || k.ppid !== ppid) {
                k = inspect(pid, file, ppid);
                known.set(pid, k);
              }
              out.push({ pid, ppid, path: k.path, ...(k.args ? { args: k.args } : {}), ...(k.startedAt ? { startedAt: k.startedAt } : {}) });
            }
            entry.writeUInt32LE(ENTRY_SIZE, 0);
            more = Process32NextW(snap, entry);
          }
        } finally {
          CloseHandle(snap);
        }
        for (const pid of known.keys()) if (!alive.has(pid)) known.delete(pid);
        return out;
      },
      foreground() {
        const hwnd = GetForegroundWindow();
        if (!hwnd) return null;
        const owner = [0];
        GetWindowThreadProcessId(hwnd, owner);
        const pid = Number(owner[0] ?? 0);
        if (!pid) return null;
        const cls = Buffer.alloc(128);
        const classChars = GetClassNameW(hwnd, cls, 64);
        // the desktop behind everything also fills the screen
        if (/^(Progman|WorkerW|Shell_TrayWnd)$/.test(cls.toString("utf16le", 0, classChars * 2))) return null;
        const title = Buffer.alloc(512);
        const chars = GetWindowTextW(hwnd, title, 256);
        const rect = Buffer.alloc(16);
        const info = Buffer.alloc(40);
        info.writeUInt32LE(40, 0);
        let fullscreen = false;
        if (GetWindowRect(hwnd, rect) && GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), info)) {
          // the monitor's rectangle sits after the structure's size; a borderless game covers it exactly, an exclusive one too
          fullscreen = rect.readInt32LE(0) <= info.readInt32LE(4) && rect.readInt32LE(4) <= info.readInt32LE(8) && rect.readInt32LE(8) >= info.readInt32LE(12) && rect.readInt32LE(12) >= info.readInt32LE(16);
        }
        return { pid, fullscreen, ...(chars > 0 ? { title: title.toString("utf16le", 0, chars * 2) } : {}) };
      },
      gpuBusy() {
        const busy = new Set<number>();
        if (pdh === undefined) {
          pdh = null;
          try {
            const lib = koffi.load("pdh.dll");
            const open = lib.func("uint32_t __stdcall PdhOpenQueryW(str16 src, uintptr_t user, _Out_ void **query)");
            const add = lib.func("uint32_t __stdcall PdhAddEnglishCounterW(void *query, str16 path, uintptr_t user, _Out_ void **counter)");
            const q: unknown[] = [null];
            const c: unknown[] = [null];
            if (open(null, 0, q) === 0 && add(q[0], "\\GPU Engine(*engtype_3D)\\Utilization Percentage", 0, c) === 0) {
              const collect = lib.func("uint32_t __stdcall PdhCollectQueryData(void *query)");
              const array = lib.func("uint32_t __stdcall PdhGetFormattedCounterArrayW(void *counter, uint32_t fmt, _Inout_ uint32_t *size, _Out_ uint32_t *count, _Out_ uint8_t *items)");
              pdh = { query: q[0], counter: c[0], collect, array, primed: false };
            }
          } catch (err) {
            log("game detection: no GPU counters", { err: String(err) });
          }
        }
        if (!pdh || pdh.collect(pdh.query) !== 0) return busy;
        // a rate needs two samples: the first look only starts the clock
        if (!pdh.primed) {
          pdh.primed = true;
          return busy;
        }
        const size = [0];
        const count = [0];
        // PDH_MORE_DATA tells the size to make room for
        pdh.array(pdh.counter, PDH_FMT_DOUBLE, size, count, null);
        const need = Number(size[0] ?? 0);
        if (need === 0 || need > 16 << 20) return busy;
        const items = Buffer.alloc(need);
        if (pdh.array(pdh.counter, PDH_FMT_DOUBLE, size, count, items) !== 0) return busy;
        const base = koffi.address(items);
        const usage = new Map<number, number>();
        for (let i = 0; i < Number(count[0] ?? 0); i++) {
          // PDH_FMT_COUNTERVALUE_ITEM_W: a pointer to the instance's name inside this buffer, then a status and the value
          const at = i * 24;
          const name = Number(items.readBigUInt64LE(at) - BigInt(base));
          if (name < 0 || name >= need) continue;
          let end = name;
          while (end + 1 < need && items.readUInt16LE(end) !== 0) end += 2;
          const pid = Number(/^pid_(\d+)_/.exec(items.toString("utf16le", name, end))?.[1]);
          if (pid) usage.set(pid, (usage.get(pid) ?? 0) + items.readDoubleLE(at + 16));
        }
        for (const [pid, percent] of usage) if (percent / 100 >= GPU_BUSY) busy.add(pid);
        return busy;
      },
      product(file) {
        const handle = [0];
        const size = GetFileVersionInfoSizeW(file, handle);
        if (!size || size > 1 << 20) return null;
        const block = Buffer.alloc(size);
        if (!GetFileVersionInfoW(file, 0, size, block)) return null;
        const ptr: unknown[] = [null];
        const len = [0];
        if (!VerQueryValueW(block, "\\VarFileInfo\\Translation", ptr, len) || Number(len[0] ?? 0) < 4 || !ptr[0]) return null;
        const [lang, page] = koffi.decode(ptr[0], "uint16_t", 2) as number[];
        const prefix = `\\StringFileInfo\\${(lang ?? 0).toString(16).padStart(4, "0")}${(page ?? 0).toString(16).padStart(4, "0")}\\`;
        for (const field of ["ProductName", "FileDescription"]) {
          if (VerQueryValueW(block, prefix + field, ptr, len) && ptr[0] && Number(len[0] ?? 0) > 1) {
            const text = koffi.decode(ptr[0], "char16_t", Number(len[0]) - 1) as string | number[];
            const value = typeof text === "string" ? text : String.fromCharCode(...text);
            if (value.trim()) return value.trim();
          }
        }
        return null;
      },
      gameWindows() {
        const out = new Set<number>();
        const rect = Buffer.alloc(16);
        const owner = [0];
        const visit = (hwnd: unknown) => {
          if (IsWindowVisible(hwnd) && !GetWindow(hwnd, GW_OWNER)) {
            // a minimised window is moved off screen at a tiny size, and is still a game's window
            const sized = GetWindowRect(hwnd, rect) && rect.readInt32LE(8) - rect.readInt32LE(0) >= GAME_WINDOW.width && rect.readInt32LE(12) - rect.readInt32LE(4) >= GAME_WINDOW.height;
            if (sized || IsIconic(hwnd)) {
              GetWindowThreadProcessId(hwnd, owner);
              const pid = Number(owner[0] ?? 0);
              if (pid) out.add(pid);
            }
          }
          return 1;
        };
        EnumWindows(visit, 0);
        return out;
      },
      windows() {
        const out = new Map<number, string>();
        const title = Buffer.alloc(512);
        const owner = [0];
        const visit = (hwnd: unknown) => {
          // the windows a person sees in the taskbar: shown, titled, owned by nothing
          if (IsWindowVisible(hwnd) && !GetWindow(hwnd, GW_OWNER)) {
            const chars = GetWindowTextW(hwnd, title, 256);
            if (chars > 0) {
              GetWindowThreadProcessId(hwnd, owner);
              const pid = Number(owner[0] ?? 0);
              if (pid && !out.has(pid)) out.set(pid, title.toString("utf16le", 0, chars * 2));
            }
          }
          return 1;
        };
        EnumWindows(visit, 0);
        return out;
      },
    };
  } catch (err) {
    log("game detection: koffi unavailable", { err: String(err) });
    return null;
  }
}
