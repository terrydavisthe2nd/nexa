/**
 * What is running on this computer, read each way the system allows. A program
 * whose path cannot be read (another user's, a protected one) is left out, or
 * on Windows known by its file name only.
 */
import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import { baseName, wantsArgs, type Proc } from "./match";

export interface ProcessLister {
  /** Everything running, as cheaply as can be: this runs every few seconds. */
  list(): Promise<Proc[]>;
  /** Which processes have a window, and what it says, for choosing a game by hand. */
  windows(): Promise<Map<number, string>>;
  /** The window in front, and whether it fills its screen. Null when the system will not say. */
  foreground?(): Promise<Foreground | null>;
  /** Which of these processes are drawing hard with the graphics card right now. */
  gpuBusy?(pids: number[]): Promise<Set<number>>;
  /** What a program calls itself, where its file says (Windows' version resource). */
  product?(path: string): Promise<string | null>;
  /**
   * Which processes own a normal window, minimised or on another desktop included, so a game
   * sitting in the tray does not count as being played. Null when the system cannot say.
   * A platform where this cannot be trusted has no such method, and nothing is held back there.
   */
  gameWindows?(): Promise<Set<number> | null>;
  /** How long an answer from gameWindows stays good, where asking costs a process of its own. */
  gameWindowsMs?: number;
  /** A system call never came back, so the process can only end by being ended. */
  stuck?: boolean;
}

export interface Foreground {
  pid: number;
  title?: string;
  fullscreen: boolean;
}

/** How much of one engine a process must use, between two looks, to count as a game drawing frames. */
export const GPU_BUSY = 0.15;

/** /proc/<pid>/fdinfo/<fd> for a graphics card: which client it is, and nanoseconds spent on each engine. */
export function parseDrmFdinfo(text: string): { client: string; engineNs: number } | null {
  const client = /^drm-client-id:\s*(\d+)/m.exec(text)?.[1];
  if (!client) return null;
  let engineNs = 0;
  for (const m of text.matchAll(/^drm-engine-(?:render|gfx|graphics):\s*(\d+)\s*ns/gm)) engineNs += Number(m[1]);
  return { client, engineNs };
}

/** What the window check prints: a JSON list of process ids. Null when it printed anything else. */
export function parseWindowOwners(text: string | null): Set<number> | null {
  try {
    const list = JSON.parse(text?.trim() || "null") as unknown;
    return Array.isArray(list) ? new Set(list.filter((p): p is number => typeof p === "number")) : null;
  } catch {
    return null;
  }
}

/** Smaller than this is an icon or a title strip, which Roblox keeps several of: a game's window is not. */
export const GAME_WINDOW = { width: 200, height: 150 };

/** The properties xprop prints for a window, down to what game detection needs. */
export function parseXprop(text: string): { pid?: number; title?: string; fullscreen: boolean } {
  const pid = Number(/_NET_WM_PID\(CARDINAL\) = (\d+)/.exec(text)?.[1]);
  const title = /_NET_WM_NAME\([^)]*\) = "(.*)"/.exec(text)?.[1]?.replace(/\\(.)/g, "$1");
  return { ...(pid ? { pid } : {}), ...(title ? { title } : {}), fullscreen: /_NET_WM_STATE_FULLSCREEN/.test(text) };
}

// --- Linux ------------------------------------------------------------------

interface Stat {
  ppid: number;
  tty: boolean;
  /** Clock ticks since boot. */
  start: number;
}

/** /proc/<pid>/stat: the command name may hold spaces and brackets, so fields count from the last ")". */
export function parseStat(text: string): Stat | null {
  const end = text.lastIndexOf(")");
  if (end < 0) return null;
  const f = text.slice(end + 2).split(" ");
  const ppid = Number(f[1]);
  const start = Number(f[19]);
  if (!Number.isFinite(ppid) || !Number.isFinite(start)) return null;
  return { ppid, tty: Number(f[4]) !== 0, start };
}

/** /proc/<pid>/cmdline: the program's own name, then each argument, split by NUL. */
export function parseCmdline(text: string): string[] {
  const parts = text.split("\0");
  if (parts[parts.length - 1] === "") parts.pop();
  return parts;
}

interface LinuxEntry {
  start: number;
  path: string | null;
  args?: string[];
}

export class LinuxLister implements ProcessLister {
  private readonly seen = new Map<number, LinuxEntry>();
  private bootMs = 0;

  private async boot(): Promise<number> {
    if (this.bootMs) return this.bootMs;
    try {
      const btime = /^btime (\d+)$/m.exec(await fs.readFile("/proc/stat", "utf8"))?.[1];
      this.bootMs = Number(btime) * 1000;
    } catch {
      /* then times are when a game was first seen */
    }
    return this.bootMs;
  }

  async list(): Promise<Proc[]> {
    const boot = await this.boot();
    const pids = (await fs.readdir("/proc")).filter((n) => /^\d+$/.test(n)).map(Number);
    const alive = new Set(pids);
    for (const pid of this.seen.keys()) if (!alive.has(pid)) this.seen.delete(pid);
    const out = await Promise.all(pids.map((pid) => this.one(pid, boot)));
    return out.filter((p): p is Proc => p !== null);
  }

  private async one(pid: number, boot: number): Promise<Proc | null> {
    let stat: Stat | null;
    try {
      stat = parseStat(await fs.readFile(`/proc/${pid}/stat`, "utf8"));
    } catch {
      return null;
    }
    if (!stat) return null;
    let entry = this.seen.get(pid);
    // a pid can come back as another program; the time it started tells them apart
    if (!entry || entry.start !== stat.start) {
      entry = { start: stat.start, path: null };
      try {
        entry.path = (await fs.readlink(`/proc/${pid}/exe`)).replace(/ \(deleted\)$/, "");
        const cmdline = parseCmdline(await fs.readFile(`/proc/${pid}/cmdline`, "utf8"));
        // Wine programs show their Windows path as their own name, so look at that too
        if (wantsArgs(baseName(entry.path)) || /^[a-z]:[\\/]/i.test(cmdline[0] ?? "")) entry.args = /^[a-z]:[\\/]/i.test(cmdline[0] ?? "") ? cmdline : cmdline.slice(1);
      } catch {
        /* not ours to read, or a kernel thread */
      }
      this.seen.set(pid, entry);
    }
    if (!entry.path) return null;
    return { pid, ppid: stat.ppid, path: entry.path, ...(entry.args ? { args: entry.args } : {}), ...(boot ? { startedAt: boot + (stat.start * 1000) / 100 } : {}), tty: stat.tty };
  }

  async foreground(): Promise<Foreground | null> {
    const root = await run("xprop", ["-root", "_NET_ACTIVE_WINDOW"]);
    const id = /window id # (0x[0-9a-f]+)/i.exec(root ?? "")?.[1];
    if (!id || id === "0x0") return null;
    const info = parseXprop((await run("xprop", ["-id", id, "_NET_WM_PID", "_NET_WM_NAME", "_NET_WM_STATE"])) ?? "");
    return info.pid ? { pid: info.pid, fullscreen: info.fullscreen, ...(info.title ? { title: info.title } : {}) } : null;
  }

  private readonly drmSeen = new Map<number, { ns: number; at: number }>();

  /** The kernel counts what each process's graphics clients used, on the cards whose drivers report it. */
  async gpuBusy(pids: number[]): Promise<Set<number>> {
    const busy = new Set<number>();
    const now = Date.now();
    await Promise.all(
      pids.map(async (pid) => {
        let fds: string[];
        try {
          fds = await fs.readdir(`/proc/${pid}/fdinfo`);
        } catch {
          return;
        }
        const clients = new Map<string, number>();
        await Promise.all(
          fds.slice(0, 2048).map(async (fd) => {
            try {
              const info = parseDrmFdinfo(await fs.readFile(`/proc/${pid}/fdinfo/${fd}`, "utf8"));
              if (info) clients.set(info.client, info.engineNs);
            } catch {
              /* closed since */
            }
          }),
        );
        if (clients.size === 0) {
          this.drmSeen.delete(pid);
          return;
        }
        const ns = [...clients.values()].reduce((a, b) => a + b, 0);
        const was = this.drmSeen.get(pid);
        this.drmSeen.set(pid, { ns, at: now });
        if (was && now > was.at && (ns - was.ns) / ((now - was.at) * 1e6) >= GPU_BUSY) busy.add(pid);
      }),
    );
    for (const pid of this.drmSeen.keys()) if (!pids.includes(pid)) this.drmSeen.delete(pid);
    return busy;
  }

  /** X11 and XWayland say which process owns a window; nothing has to be installed for the rest to work. */
  async windows(): Promise<Map<number, string>> {
    const out = new Map<number, string>();
    const root = await run("xprop", ["-root", "_NET_CLIENT_LIST"]);
    const ids = (root?.match(/0x[0-9a-f]+/gi) ?? []).slice(0, 80);
    await Promise.all(
      ids.map(async (id) => {
        const text = await run("xprop", ["-id", id, "_NET_WM_PID", "_NET_WM_NAME"]);
        const pid = Number(/_NET_WM_PID\(CARDINAL\) = (\d+)/.exec(text ?? "")?.[1]);
        const title = /_NET_WM_NAME\([^)]*\) = "(.*)"/.exec(text ?? "")?.[1];
        if (pid && title) out.set(pid, title.replace(/\\(.)/g, "$1"));
      }),
    );
    return out;
  }
}

function run(file: string, args: string[]): Promise<string | null> {
  return new Promise((resolve) => {
    execFile(file, args, { timeout: 2000, maxBuffer: 16 << 20, windowsHide: true }, (err, stdout) => resolve(err ? null : String(stdout)));
  });
}

// --- macOS ------------------------------------------------------------------

/** ps's elapsed time: [[dd-]hh:]mm:ss. */
export function parseElapsed(text: string): number | null {
  const m = /^(?:(?:(\d+)-)?(\d+):)?(\d+):(\d+)$/.exec(text);
  if (!m) return null;
  return (Number(m[1] ?? 0) * 86400 + Number(m[2] ?? 0) * 3600 + Number(m[3]) * 60 + Number(m[4])) * 1000;
}

/** `ps -axww -o pid=,ppid=,etime=,comm=`. The path is last, because it may hold spaces. */
export function parsePs(text: string, now: number): Proc[] {
  const out: Proc[] = [];
  for (const line of text.split("\n")) {
    const m = /^\s*(\d+)\s+(\d+)\s+(\S+)\s+(.+?)\s*$/.exec(line);
    if (!m) continue;
    const elapsed = parseElapsed(m[3] ?? "");
    out.push({ pid: Number(m[1]), ppid: Number(m[2]), path: m[4] ?? "", ...(elapsed !== null ? { startedAt: now - elapsed } : {}) });
  }
  return out;
}

export class MacLister implements ProcessLister {
  /** Asking takes starting osascript, so it waits a little between looks. */
  readonly gameWindowsMs = 10_000;

  constructor(private readonly log?: (msg: string, extra?: Record<string, unknown>) => void) {}

  gameWindows(): Promise<Set<number> | null> {
    return new Promise((resolve) => {
      execFile("/usr/bin/osascript", ["-l", "JavaScript", "-e", WINDOWS_SCRIPT], { timeout: 2000, maxBuffer: 16 << 20 }, (err, stdout, stderr) => {
        const owners = err ? null : parseWindowOwners(String(stdout));
        // the game stays shown when this fails, so the reason has to be said somewhere
        if (!owners) this.log?.("window check failed", { err: err ? String(err) : "", stdout: String(stdout).slice(0, 200), stderr: String(stderr).slice(0, 400) });
        resolve(owners);
      });
    });
  }

  async list(): Promise<Proc[]> {
    const text = await run("/bin/ps", ["-axww", "-o", "pid=,ppid=,etime=,comm="]);
    if (text === null) return [];
    const procs = parsePs(text, Date.now());
    // the second look costs a process of its own, so it is only for what needs it
    const need = procs.filter((p) => wantsArgs(baseName(p.path)));
    if (need.length > 0) {
      const args = await run("/bin/ps", ["-ww", "-o", "pid=,args=", "-p", need.map((p) => p.pid).join(",")]);
      for (const line of args?.split("\n") ?? []) {
        const m = /^\s*(\d+)\s+(.+)$/.exec(line);
        const p = m ? need.find((x) => x.pid === Number(m[1])) : undefined;
        // ps cannot say where the program's own name ends when it holds spaces; arguments are searched, not parsed
        if (p && m?.[2]) p.args = splitCommandLine(m[2]).slice(1);
      }
    }
    return procs;
  }

  /** A Mac program's window is its bundle: the chooser takes those, and names them from it. */
  async windows(): Promise<Map<number, string>> {
    return new Map();
  }

  /** The app in front and whether its window fills the screen. Reading a window's bounds needs no permission; its title would. */
  async foreground(): Promise<Foreground | null> {
    const text = await run("/usr/bin/osascript", ["-l", "JavaScript", "-e", FRONT_SCRIPT]);
    try {
      const v = JSON.parse(text?.trim() || "null") as { pid?: unknown; fullscreen?: unknown } | null;
      return v && typeof v.pid === "number" ? { pid: v.pid, fullscreen: v.fullscreen === true } : null;
    } catch {
      return null;
    }
  }
}

/** Every process with a normal window on the app layer, on screen or not. Reading a window's bounds needs no permission. */
const WINDOWS_SCRIPT = `ObjC.import("CoreGraphics");function run(){const l=ObjC.deepUnwrap(ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionAll,0)))||[];return JSON.stringify([...new Set(l.filter((w)=>w.kCGWindowLayer===0&&w.kCGWindowBounds.Width>=${GAME_WINDOW.width}&&w.kCGWindowBounds.Height>=${GAME_WINDOW.height}).map((w)=>w.kCGWindowOwnerPID))])}`;

const FRONT_SCRIPT = `ObjC.import("AppKit");ObjC.import("CoreGraphics");function run(){const pid=$.NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier;const l=ObjC.deepUnwrap(ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionOnScreenOnly|$.kCGWindowListExcludeDesktopElements,0)))||[];const w=l.find((x)=>x.kCGWindowOwnerPID===pid&&x.kCGWindowLayer===0);if(!w)return JSON.stringify({pid,fullscreen:false});const s=$.NSScreen.mainScreen.frame;return JSON.stringify({pid,fullscreen:w.kCGWindowBounds.Width>=s.size.width&&w.kCGWindowBounds.Height>=s.size.height})}`;

/** A command line in the shape programs are handed it: quoted parts stay whole. */
export function splitCommandLine(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quoted = false;
  let any = false;
  for (const c of line) {
    if (c === '"') {
      quoted = !quoted;
      any = true;
    } else if (!quoted && (c === " " || c === "\t")) {
      if (any || cur) out.push(cur);
      cur = "";
      any = false;
    } else {
      cur += c;
    }
  }
  if (any || cur) out.push(cur);
  return out;
}
