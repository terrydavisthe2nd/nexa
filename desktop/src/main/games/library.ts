/**
 * What the computer's game stores say is installed: Steam's manifests, Epic's
 * and Heroic's records. These are the player's own files and name a game
 * exactly, with the id its store knows it by. They are read here and go nowhere.
 */
import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { baseName, cleanName, emptyLibrary, norm, slug, type Library, type LibraryGame } from "./match";

type Vdf = { [key: string]: string | Vdf };

/** Valve's KeyValues text: quoted keys and values, braces, and // comments. */
export function parseVdf(text: string): Vdf {
  const root: Vdf = {};
  const stack: Vdf[] = [root];
  let key: string | null = null;
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
    } else if (c === '"') {
      let s = "";
      i++;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === "\\" && i + 1 < text.length) {
          i++;
          s += text[i] === "n" ? "\n" : text[i] === "t" ? "\t" : text[i];
        } else {
          s += text[i];
        }
        i++;
      }
      i++;
      if (key === null) {
        key = s;
      } else {
        stack[stack.length - 1]![key] = s;
        key = null;
      }
    } else if (c === "{") {
      const inner: Vdf = {};
      if (key !== null) stack[stack.length - 1]![key] = inner;
      stack.push(inner);
      key = null;
      i++;
    } else if (c === "}") {
      if (stack.length > 1) stack.pop();
      key = null;
      i++;
    } else {
      i++;
    }
  }
  return root;
}

type BinaryVdf = { [key: string]: string | number | BinaryVdf };

/** Valve's binary KeyValues, which Steam's shortcuts.vdf is written in: a type byte, a name, then the value. */
export function parseBinaryVdf(buf: Buffer): BinaryVdf {
  let at = 0;
  const text = (): string => {
    const end = buf.indexOf(0, at);
    if (end < 0) throw new Error("unterminated string");
    const s = buf.toString("utf8", at, end);
    at = end + 1;
    return s;
  };
  const map = (depth: number): BinaryVdf => {
    if (depth > 16) throw new Error("too deep");
    const out: BinaryVdf = {};
    while (at < buf.length) {
      const type = buf[at++];
      if (type === 0x08) return out;
      const key = text();
      if (type === 0x00) out[key] = map(depth + 1);
      else if (type === 0x01) out[key] = text();
      else if (type === 0x02) {
        out[key] = buf.readInt32LE(at);
        at += 4;
      } else throw new Error(`unknown type ${String(type)}`);
    }
    return out;
  };
  return map(0);
}

/** A shortcut's name and program, whichever case Steam wrote the keys in. */
export function steamShortcuts(vdf: BinaryVdf): { name: string; exe: string }[] {
  const list = vdf.shortcuts;
  if (!list || typeof list !== "object") return [];
  const out: { name: string; exe: string }[] = [];
  for (const entry of Object.values(list)) {
    if (!entry || typeof entry !== "object") continue;
    const fields = Object.fromEntries(Object.entries(entry).map(([k, v]) => [k.toLowerCase(), v]));
    const name = fields.appname;
    const exe = fields.exe;
    if (typeof name === "string" && name && typeof exe === "string" && exe) out.push({ name, exe: exe.replace(/^"|"$/g, "") });
  }
  return out;
}

const child = (v: string | Vdf | undefined): Vdf | undefined => (v && typeof v === "object" ? v : undefined);

/** Every library folder a Steam install lists, its own among them. */
export function libraryFolders(vdf: Vdf): string[] {
  const list = child(vdf.libraryfolders) ?? child(vdf.LibraryFolders);
  if (!list) return [];
  const out: string[] = [];
  for (const [k, v] of Object.entries(list)) {
    if (!/^\d+$/.test(k)) continue;
    // older Steam wrote the path as the value itself
    const p = typeof v === "string" ? v : typeof v.path === "string" ? v.path : null;
    if (p) out.push(p);
  }
  return out;
}

async function read(file: string): Promise<string | null> {
  try {
    const s = await fs.stat(file);
    if (!s.isFile() || s.size > 8 << 20) return null;
    return await fs.readFile(file, "utf8");
  } catch {
    return null;
  }
}

async function list(dir: string): Promise<string[]> {
  try {
    return await fs.readdir(dir);
  } catch {
    return [];
  }
}

function windowsRegistry(key: string, value: string): Promise<string | null> {
  return new Promise((resolve) => {
    execFile("reg", ["query", key, "/v", value], { timeout: 4000, windowsHide: true }, (err, stdout) => {
      if (err) return resolve(null);
      resolve(new RegExp(`${value}\\s+REG_\\w+\\s+(.+)`, "i").exec(String(stdout))?.[1]?.trim() ?? null);
    });
  });
}

async function steamInstalls(platform: NodeJS.Platform, env: NodeJS.ProcessEnv, home: string): Promise<string[]> {
  if (platform === "win32") {
    const fromRegistry = await windowsRegistry("HKCU\\Software\\Valve\\Steam", "SteamPath");
    return [fromRegistry, path.join(env["ProgramFiles(x86)"] ?? "C:\\Program Files (x86)", "Steam")].filter((p): p is string => !!p);
  }
  if (platform === "darwin") return [path.join(home, "Library/Application Support/Steam")];
  return [
    path.join(env.XDG_DATA_HOME ?? path.join(home, ".local/share"), "Steam"),
    path.join(home, ".steam/steam"),
    path.join(home, ".steam/root"),
    path.join(home, ".var/app/com.valvesoftware.Steam/.local/share/Steam"),
    path.join(home, "snap/steam/common/.local/share/Steam"),
  ];
}

/** The games someone added to Steam by hand, each person on the computer in their own folder. */
async function readShortcuts(into: Library, install: string): Promise<void> {
  const users = path.join(install, "userdata");
  for (const user of await list(users)) {
    try {
      const file = path.join(users, user, "config", "shortcuts.vdf");
      const s = await fs.stat(file);
      if (s.size > 8 << 20) continue;
      for (const { name, exe } of steamShortcuts(parseBinaryVdf(await fs.readFile(file)))) {
        into.programs.set(norm(exe), { key: `shortcut:${slug(name)}`, name: cleanName(name), source: "steam", root: "" });
      }
    } catch {
      /* someone with no shortcuts, or a file not understood */
    }
  }
}

async function readSteam(into: Library, platform: NodeJS.Platform, env: NodeJS.ProcessEnv, home: string): Promise<void> {
  const libraries = new Set<string>();
  for (const install of await steamInstalls(platform, env, home)) {
    const text = await read(path.join(install, "steamapps", "libraryfolders.vdf"));
    if (text === null) continue;
    libraries.add(install);
    for (const p of libraryFolders(parseVdf(text))) libraries.add(p);
    await readShortcuts(into, install);
  }
  for (const library of libraries) {
    const apps = path.join(library, "steamapps");
    for (const file of await list(apps)) {
      if (!/^appmanifest_\d+\.acf$/.test(file)) continue;
      const text = await read(path.join(apps, file));
      const state = text === null ? undefined : child(parseVdf(text).AppState);
      const id = state?.appid;
      const name = state?.name;
      const dir = state?.installdir;
      if (typeof id !== "string" || typeof name !== "string" || typeof dir !== "string" || !name) continue;
      const app = { id, name };
      into.steam.set(dir.toLowerCase(), app);
      into.steamById.set(id, app);
    }
  }
}

async function readEpic(into: LibraryGame[], platform: NodeJS.Platform, env: NodeJS.ProcessEnv, home: string): Promise<void> {
  const dirs = platform === "win32" ? [path.join(env.ProgramData ?? "C:\\ProgramData", "Epic/EpicGamesLauncher/Data/Manifests")] : platform === "darwin" ? [path.join(home, "Library/Application Support/Epic/EpicGamesLauncher/Data/Manifests")] : [];
  for (const dir of dirs) {
    for (const file of await list(dir)) {
      if (!file.endsWith(".item")) continue;
      const text = await read(path.join(dir, file));
      if (text === null) continue;
      try {
        const m = JSON.parse(text) as { DisplayName?: unknown; InstallLocation?: unknown; AppName?: unknown; AppCategories?: unknown };
        if (typeof m.DisplayName !== "string" || typeof m.InstallLocation !== "string" || typeof m.AppName !== "string") continue;
        // the store also installs its engine and plugins this way
        if (Array.isArray(m.AppCategories) && !m.AppCategories.includes("games")) continue;
        into.push({ key: `epic:${m.AppName.toLowerCase()}`, name: cleanName(m.DisplayName), source: "epic", root: norm(m.InstallLocation).replace(/\/$/, "") });
      } catch {
        /* a damaged record is one game not recognised this way */
      }
    }
  }
}

/** Heroic's and Legendary's record of what they installed from Epic, which is how Linux plays those games. */
async function readLegendary(into: LibraryGame[], platform: NodeJS.Platform, env: NodeJS.ProcessEnv, home: string): Promise<void> {
  const config = platform === "win32" ? env.APPDATA ?? path.join(home, "AppData/Roaming") : platform === "darwin" ? path.join(home, "Library/Application Support") : env.XDG_CONFIG_HOME ?? path.join(home, ".config");
  const files = [
    path.join(config, "legendary/installed.json"),
    path.join(config, "heroic/legendaryConfig/legendary/installed.json"),
    path.join(home, ".var/app/com.heroicgameslauncher.hgl/config/heroic/legendaryConfig/legendary/installed.json"),
  ];
  for (const file of files) {
    const text = await read(file);
    if (text === null) continue;
    try {
      const all = JSON.parse(text) as Record<string, { title?: unknown; install_path?: unknown; is_dlc?: unknown }>;
      for (const [id, g] of Object.entries(all)) {
        if (g.is_dlc === true || typeof g.title !== "string" || typeof g.install_path !== "string") continue;
        into.push({ key: `epic:${id.toLowerCase()}`, name: cleanName(g.title), source: "epic", root: norm(g.install_path).replace(/\/$/, "") });
      }
    } catch {
      /* as above */
    }
  }
}

interface UninstallEntry {
  name?: string;
  location?: string;
  uninstall?: string;
  publisher?: string;
}

/** `reg query <Uninstall key> /s`: a line naming each key, then its values, indented. */
export function parseUninstall(text: string): UninstallEntry[] {
  const out: UninstallEntry[] = [];
  let at: UninstallEntry | null = null;
  for (const line of text.split(/\r?\n/)) {
    if (/^HKEY_/i.test(line)) {
      at = {};
      out.push(at);
      continue;
    }
    const m = /^\s+(\S+)\s+REG_\w+\s+(.*)$/.exec(line);
    if (!m || !at) continue;
    const value = m[2]?.trim() ?? "";
    if (m[1] === "DisplayName") at.name = value;
    else if (m[1] === "InstallLocation") at.location = value;
    else if (m[1] === "UninstallString") at.uninstall = value;
    else if (m[1] === "Publisher") at.publisher = value;
  }
  return out;
}

/** The launchers themselves, and what they install alongside games. */
const NOT_INSTALLED_GAME = /^(battle\.net|ea app|ea desktop|origin|ubisoft connect|ubisoft game launcher|uplay|riot client|riot vanguard|rockstar games launcher|rockstar games social club|social club|amazon games|gog galaxy)\b|redistributable|runtime|directx|vcredist|\bredist\b/i;

/** A game a launcher installed, told apart from the rest of what is on the computer by who installed it. */
export function installedGame(e: UninstallEntry): LibraryGame | null {
  if (!e.name || !e.location || NOT_INSTALLED_GAME.test(e.name)) return null;
  const by = `${e.uninstall ?? ""} ${e.publisher ?? ""}`.toLowerCase();
  if (!/battle\.net|blizzard|uplay:\/\/|ubisoft|eainstaller|ea desktop|electronic arts|origin games|riot client|riot games|rockstar games|amazon games|gog\.com/.test(by)) return null;
  const root = norm(e.location).replace(/\/$/, "");
  if (root.split("/").length < 2) return null;
  return { key: `dir:${baseName(root)}`, name: cleanName(e.name), source: "store", root };
}

const UNINSTALL_KEYS = ["HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"];

function regQuery(args: string[]): Promise<string> {
  return new Promise((resolve) => {
    execFile("reg", ["query", ...args], { timeout: 8000, windowsHide: true, maxBuffer: 32 << 20 }, (_err, stdout) => resolve(String(stdout ?? "")));
  });
}

/** Battle.net, EA, Ubisoft, Riot, Rockstar and Amazon keep no list we can read, but all of them register what they install with Windows. */
async function readWindowsInstalled(into: Library): Promise<void> {
  for (const key of UNINSTALL_KEYS) {
    for (const e of parseUninstall(await regQuery([key, "/s"]))) {
      const game = installedGame(e);
      if (game) into.roots.push(game);
    }
  }
  // what Windows' own game features have treated as a game; it says this about too much to be an answer on its own
  const config = await regQuery(["HKCU\\System\\GameConfigStore\\Children", "/s", "/v", "MatchedExeFullPath"]);
  for (const m of config.matchAll(/MatchedExeFullPath\s+REG_\w+\s+(.+)/g)) if (m[1]) into.osGames.add(norm(m[1].trim()));
}

export interface DesktopEntry {
  name?: string;
  exec?: string;
  categories: string[];
  hidden: boolean;
}

/** The main section of a Linux desktop entry. */
export function parseDesktopEntry(text: string): DesktopEntry {
  const entry: DesktopEntry = { categories: [], hidden: false };
  let inMain = false;
  for (const line of text.split(/\r?\n/)) {
    if (line.startsWith("[")) {
      inMain = line.trim() === "[Desktop Entry]";
      continue;
    }
    if (!inMain) continue;
    const eq = line.indexOf("=");
    if (eq < 0) continue;
    const k = line.slice(0, eq).trim();
    const v = line.slice(eq + 1).trim();
    if (k === "Name") entry.name = v;
    else if (k === "Exec") entry.exec = v;
    else if (k === "Categories") entry.categories = v.split(";").filter(Boolean);
    else if ((k === "Hidden" || k === "NoDisplay") && v === "true") entry.hidden = true;
  }
  return entry;
}

/** Launchers and wrappers: their own entries say Game too, but what they run is something else. */
const WRAPPER = /^(steam|lutris|heroic|legendary|bottles(-cli)?|gamescope|gamemoderun|mangohud|prime-run|wine|wine64|proton|sh|bash|env|python\d?|java|flatpak|snap|xdg-open|itch|retroarch-launcher)$/i;

/** The program a desktop entry starts: what a running process will be called. */
export function desktopProgram(exec: string): string | null {
  const parts = (exec.match(/"[^"]*"|\S+/g) ?? []).map((x) => x.replace(/^"|"$/g, "")).filter((x) => !x.startsWith("%"));
  if (baseName(parts[0] ?? "") === "flatpak") {
    const command = parts.find((x) => x.startsWith("--command="))?.slice("--command=".length);
    return command ? baseName(command).toLowerCase() : null;
  }
  let i = 0;
  while (i < parts.length && (baseName(parts[i] ?? "") === "env" || /^[A-Za-z_]+=/.test(parts[i] ?? ""))) i++;
  const file = baseName(parts[i] ?? "").toLowerCase();
  return file && !WRAPPER.test(file) ? file : null;
}

/** What a Linux desktop calls a game: its packages and launchers say so in their entries. */
async function readDesktopEntries(into: Library, env: NodeJS.ProcessEnv, home: string): Promise<void> {
  const dirs = [
    path.join(env.XDG_DATA_HOME ?? path.join(home, ".local/share"), "applications"),
    "/usr/share/applications",
    "/usr/local/share/applications",
    "/var/lib/flatpak/exports/share/applications",
    path.join(home, ".local/share/flatpak/exports/share/applications"),
    "/var/lib/snapd/desktop/applications",
  ];
  for (const dir of dirs) {
    for (const file of await list(dir)) {
      if (!file.endsWith(".desktop")) continue;
      const text = await read(path.join(dir, file));
      if (text === null) continue;
      const e = parseDesktopEntry(text);
      if (e.hidden || !e.name || !e.exec || !e.categories.includes("Game")) continue;
      const program = desktopProgram(e.exec);
      if (program) into.files.set(program, { key: `system:${slug(e.name)}`, name: cleanName(e.name), source: "system", root: "" });
    }
  }
}

/** Lutris keeps a file per game named after it, and Heroic a list of what was added by hand. */
async function readLinuxLaunchers(into: Library, env: NodeJS.ProcessEnv, home: string): Promise<void> {
  const config = env.XDG_CONFIG_HOME ?? path.join(home, ".config");
  const data = env.XDG_DATA_HOME ?? path.join(home, ".local/share");
  for (const dir of [path.join(config, "lutris/games"), path.join(data, "lutris/games")]) {
    for (const file of await list(dir)) {
      if (!/\.ya?ml$/.test(file)) continue;
      const text = await read(path.join(dir, file));
      const exe = text && /^\s+exe:\s*(.+)$/m.exec(text)?.[1]?.trim().replace(/^['"]|['"]$/g, "");
      if (!exe) continue;
      const name = cleanName(file.replace(/\.ya?ml$/, "").replace(/-\d+$/, "").replace(/-/g, " "), true);
      if (!name) continue;
      into.programs.set(norm(exe), { key: `lutris:${slug(name)}`, name, source: "store", root: "" });
      if (!/^(game|launcher|start|run|main|app)(\.exe)?$/i.test(baseName(exe))) into.files.set(baseName(exe).toLowerCase(), { key: `lutris:${slug(name)}`, name, source: "store", root: "" });
    }
  }
  const sideloaded = await read(path.join(config, "heroic/sideload_apps/library.json"));
  if (sideloaded) {
    try {
      const { games } = JSON.parse(sideloaded) as { games?: { title?: unknown; install?: { executable?: unknown } }[] };
      for (const g of games ?? []) {
        if (typeof g.title === "string" && typeof g.install?.executable === "string") into.programs.set(norm(g.install.executable), { key: `heroic:${slug(g.title)}`, name: cleanName(g.title), source: "store", root: "" });
      }
    } catch {
      /* as above */
    }
  }
}

/** What the stores say right now. A store that is not here, or a file that cannot be read, adds nothing. */
export async function readLibrary(platform: NodeJS.Platform, env: NodeJS.ProcessEnv, home: string): Promise<Library> {
  const library = emptyLibrary();
  await Promise.all([
    readSteam(library, platform, env, home),
    readEpic(library.roots, platform, env, home),
    readLegendary(library.roots, platform, env, home),
    ...(platform === "win32" ? [readWindowsInstalled(library)] : []),
    ...(platform === "linux" ? [readDesktopEntries(library, env, home), readLinuxLaunchers(library, env, home)] : []),
  ]);
  // a game inside another's folder is the nearer one
  library.roots.sort((a, b) => b.root.length - a.root.length);
  return library;
}
