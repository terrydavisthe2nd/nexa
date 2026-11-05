/**
 * Which game, if any, a running process is. All of it is pure: what is running
 * and what the computer's stores say go in, and a game comes out. Nothing here
 * ever leaves the computer except the name of a game it found.
 *
 * Best evidence first: a game the person added themselves, then a store's own
 * record of what is installed where, then the folders stores are known to
 * install into, then the short list of well-known file names. Whatever is left
 * can still be recognised by what its folder holds (markers.ts).
 */
import type { GameSource } from "../../shared/bridge";
import { BUNDLED_GAMES, type KnownGame } from "./known";

export interface Proc {
  pid: number;
  ppid: number;
  /** The executable's full path, or just its file name when the system would not say more. */
  path: string;
  /** The command line after the program's own name; only for programs where it matters. */
  args?: string[];
  /** When it started, in Unix milliseconds. */
  startedAt?: number;
  /** Runs in a terminal (Linux), so it is no window to pick. */
  tty?: boolean;
}

export interface Match {
  key: string;
  name: string;
  source: GameSource;
  store?: string;
  storeId?: string;
  gameId?: string;
}

export interface SteamApp {
  id: string;
  name: string;
}

export interface LibraryGame {
  key: string;
  name: string;
  source: GameSource;
  /** The install folder, as norm() writes it. */
  root: string;
}

export interface Library {
  /** Steam's installed games by the folder under steamapps/common, in lower case. */
  steam: Map<string, SteamApp>;
  /** Steam's installed games by app id. */
  steamById: Map<string, SteamApp>;
  /** Everything else that knows where it installed a game, longest folder first. */
  roots: LibraryGame[];
  /** Exact programs someone or something named as a game (Steam's non-Steam shortcuts, Heroic), by norm(). */
  programs: Map<string, LibraryGame>;
  /** Program file names the system lists as games (a Linux desktop entry, Lutris), in lower case. */
  files: Map<string, LibraryGame>;
  /** Programs Windows itself treats as games, by norm(): evidence, not an answer (candidates.ts). */
  osGames: Set<string>;
  /** The list of well-known file names (known.ts). */
  known: Map<string, KnownGame[]>;
}

export const emptyLibrary = (): Library => ({ steam: new Map(), steamById: new Map(), roots: [], programs: new Map(), files: new Map(), osGames: new Set(), known: BUNDLED_INDEX });

export interface CustomMatch {
  id: string;
  name: string;
  /** What entryOf says the program runs, as norm() writes it. */
  match: string;
  gameId?: string;
}

/** A path in one form: forward slashes, single ones, lower case. */
export function norm(p: string): string {
  return p.replace(/\\/g, "/").replace(/\/+/g, "/").toLowerCase();
}

export function baseName(p: string): string {
  const parts = p.replace(/\\/g, "/").split("/");
  return parts[parts.length - 1] ?? p;
}

const WINE = /^(wine|wine64|wine-preloader|wine64-preloader|wine32on64-preloader)$/i;
const JAVA = /^javaw?(\.exe)?$/i;
/** Programs that run something else, where that something is what the person started. */
const RUNNER = /^(python[\d.]*|pythonw|mono|mono-sgen|love|lovec|node|ruby|dotnet)(\.exe)?$/i;
const WINDOWS_PATH = /^[a-z]:[\\/]/i;

/** Programs whose command line has to be read to tell what they are running. */
export function wantsArgs(fileName: string): boolean {
  return WINE.test(fileName) || JAVA.test(fileName) || RUNNER.test(fileName) || /^reaper$/.test(fileName);
}

/** The Windows program a Wine process runs, if this is one. */
function wineProgram(p: Proc): string | null {
  for (const a of (p.args ?? []).slice(0, 2)) if (WINDOWS_PATH.test(a)) return a;
  return null;
}

/**
 * What a process is running, for telling one program from another: the program
 * itself, or for Wine, Java and interpreters what they were told to run. A
 * Java game and Nexa's own updater are both just "java" otherwise.
 */
export function entryOf(p: Proc): string {
  const file = baseName(p.path);
  const wine = wineProgram(p);
  if (wine) return wine;
  const args = p.args ?? [];
  if (JAVA.test(file)) {
    const jar = args.indexOf("-jar");
    if (jar >= 0 && args[jar + 1]) return `java:${args[jar + 1]}`;
    for (let i = 0; i < args.length; i++) {
      const a = args[i] ?? "";
      if (a === "-cp" || a === "-classpath" || a === "--class-path" || a === "--module-path" || a === "-p" || a === "--add-opens" || a === "--add-exports" || a === "--add-modules") {
        i++;
        continue;
      }
      if (!a.startsWith("-")) return `java:${a}`;
    }
    return p.path;
  }
  if (RUNNER.test(file)) {
    const script = args.find((a) => !a.startsWith("-"));
    if (script) return `${file}:${script}`;
  }
  return p.path;
}

/** Parts of a game that are not playing it: crash reporters, installers, anti-cheat, launchers waiting for a click. */
const HELPER = /(crash(handler|report|pad|sender|dump|_reporter|uploader)|crs-|bugreport|bugsplat|errorreport|report(er|client)|redist|dxsetup|directx|dotnetfx|setup|unins\d*|uninstall|install(er|script)?\b|\bupdater?\b|patcher|launcher|launchpad|easyanticheat|^eac[_.-]|beservice|battleye|anticheat|anti-cheat|cefsharp|cefprocess|^cef|webhelper|webview|overlay|ue4prereq|prereq|helper|^service|register|activation|configtool|^config\b|settings|modmanager|dedicated.?server|\bserver\.exe$|screenshot)/i;

/** Whether a program's file name says it only helps a game along. */
export const isHelper = (file: string): boolean => HELPER.test(file);

/** A program's file name as a person would know it: what an interpreter runs, without the interpreter. */
export function fileOf(p: Proc): string {
  const entry = entryOf(p);
  return baseName(WINDOWS_PATH.test(entry) ? entry : entry.replace(/^[a-z0-9.-]+:/i, ""));
}

/** Steam installs tools the same way it installs games; these folders are never a game being played. */
const STEAM_TOOLS = /^(proton\b.*|proton-.*|steamlinuxruntime.*|steam linux runtime.*|steamworks shared|steamworks sdk.*|steam controller configs|steamvr|wallpaper_engine|wallpaper engine|soundpad|lossless scaling|obs studio|aseprite|blender|krita|rpg ?maker.*|gamemaker.*|godot.*engine|godot|unity|3dmark.*|fpsvr|ovr advanced settings|ovr toolkit|virtual desktop.*|vtube studio|voicemeeter.*|source sdk.*|.*\bsdk\b.*|.*\bdedicated server\b.*|.*\bdevkit\b.*)$/i;

/** Other stores' folder layouts: a game is the folder right after these. */
const STORE_FOLDERS: { source: GameSource; pattern: RegExp; not?: RegExp }[] = [
  { source: "xbox", pattern: /\/xboxgames\/([^/]+)\/content\//i, not: /^gaming ?services$/i },
  { source: "store", pattern: /\/riot games\/([^/]+)\//i, not: /^(riot client|install)$/i },
  { source: "store", pattern: /\/rockstar games\/([^/]+)\//i, not: /^(launcher|social club)$/i },
  { source: "store", pattern: /\/ea games\/([^/]+)\//i },
  { source: "store", pattern: /\/origin games\/([^/]+)\//i },
  { source: "store", pattern: /\/ubisoft game launcher\/games\/([^/]+)\//i },
  { source: "store", pattern: /\/amazon games\/library\/([^/]+)\//i },
  { source: "gog", pattern: /\/gog galaxy\/games\/([^/]+)\//i },
  { source: "epic", pattern: /\/epic games\/([^/]+)\//i, not: /^(launcher|directxredist|epicgameslauncher|ue_.*)$/i },
];

/** Folders that say nothing about what is inside them. */
const PLAIN_FOLDER = /^(bin|binaries|win32|win64|x64|x86|x86_64|amd64|game|games|data|build|release|retail|live|shipping|app|windows|linux|macos|contents|content|client|runtime|engine|common|program files.*|\d+)$/i;

/** A name fit to show: no trademark signs, and a folder's underscores and version numbers gone. */
export function cleanName(raw: string, fromFile = false): string {
  let s = raw.replace(/[™®©]/g, "").replace(/\s+/g, " ").trim();
  if (fromFile) {
    s = s
      .replace(/\.(exe|app|bin|x86_64|x86|sh)$/i, "")
      .replace(/[-_. ](win|windows|win64|win32|pc|linux|mac|macos|x64|x86|x86_64|amd64|shipping)\b.*$/i, "")
      .replace(/[-_ ]v?\d+(\.\d+)+.*$/i, "")
      .replace(/[_]+/g, " ")
      .replace(/-+/g, " ")
      // HollowKnight, as a file is often called
      .replace(/([a-z])([A-Z])/g, "$1 $2")
      .trim();
    if (s && s === s.toLowerCase()) s = s.replace(/(^|\s)(\p{L})/gu, (_m, a: string, b: string) => a + b.toUpperCase());
  }
  return Array.from(s).slice(0, 128).join("");
}

/** The nearest folder above a file that names something, for a game known only by where it sits. */
export function folderName(path: string): string | null {
  const parts = path.replace(/\\/g, "/").split("/").slice(0, -1);
  for (let i = parts.length - 1; i >= 0 && i >= parts.length - 5; i--) {
    const part = parts[i] ?? "";
    if (!part || /^[a-z]:$/i.test(part) || PLAIN_FOLDER.test(part)) continue;
    if (part.endsWith(".app")) return cleanName(part, true);
    return cleanName(part, true);
  }
  return null;
}

export function knownIndex(list: KnownGame[]): Map<string, KnownGame[]> {
  const index = new Map<string, KnownGame[]>();
  for (const g of list) {
    for (const exe of g.exes) {
      const at = index.get(exe);
      if (at) at.push(g);
      else index.set(exe, [g]);
    }
  }
  return index;
}

export const slug = (name: string) => name.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "");

const BUNDLED_INDEX = knownIndex(BUNDLED_GAMES);

function knownGame(known: Library["known"], file: string, args: string[] | undefined): KnownGame | null {
  const options = known.get(file.toLowerCase());
  if (!options) return null;
  const line = (args ?? []).join(" ");
  return options.find((g) => !g.arg || g.arg.some((a) => line.includes(a))) ?? null;
}

const steamMatch = (app: SteamApp): Match => ({ key: `steam:${app.id}`, name: cleanName(app.name), source: "steam", store: "steam", storeId: app.id });

/**
 * The game Steam says it is running. On Linux Steam starts every game under
 * its reaper with the app id on the command line, which holds however the game
 * then runs (a Proton prefix's paths say little).
 */
function steamLaunch(p: Proc, library: Library): Match | null {
  if (baseName(p.path) !== "reaper") return null;
  const id = /AppId=(\d+)/.exec((p.args ?? []).join(" "))?.[1];
  const app = id ? library.steamById.get(id) : undefined;
  return app && !STEAM_TOOLS.test(app.name) ? steamMatch(app) : null;
}

/** Which game this process is, or null. */
export function identify(p: Proc, library: Library, custom: CustomMatch[]): Match | null {
  const entry = entryOf(p);
  const seen = norm(entry);
  for (const c of custom) {
    if (c.match === seen) return { key: `custom:${c.id}`, name: c.name, source: "custom", ...(c.gameId ? { gameId: c.gameId } : {}) };
  }
  const local = seen.replace(/^z:/, "");
  const program = library.programs.get(local);
  if (program) return { key: program.key, name: program.name, source: program.source };
  const launched = steamLaunch(p, library);
  if (launched) return launched;

  const file = baseName(entry.replace(/^[a-z-]+:/i, (m) => (WINDOWS_PATH.test(entry) ? m : "")));
  const known = knownGame(library.known, baseName(p.path), p.args) ?? knownGame(library.known, file, p.args);
  if (HELPER.test(file) && !known) return null;
  // Wine's own programs live in the prefix's Windows folder
  if (/^[a-z]:\/windows\//.test(seen)) return null;

  const steam = /\/steamapps\/common\/([^/]+)\//i.exec(entry.replace(/\\/g, "/"));
  if (steam?.[1]) {
    if (STEAM_TOOLS.test(steam[1])) return null;
    const app = library.steam.get(steam[1].toLowerCase());
    if (app) return STEAM_TOOLS.test(app.name) ? null : steamMatch(app);
    return { key: `dir:${steam[1].toLowerCase()}`, name: cleanName(steam[1]), source: "steam" };
  }
  const inFolder = `${seen.slice(0, seen.lastIndexOf("/") + 1)}`;
  for (const g of library.roots) {
    if (inFolder.startsWith(`${g.root}/`)) return { key: g.key, name: g.name, source: g.source };
  }
  const slashed = entry.replace(/\\/g, "/");
  for (const store of STORE_FOLDERS) {
    const folder = store.pattern.exec(slashed)?.[1];
    if (folder && !store.not?.test(folder)) return { key: `dir:${folder.toLowerCase()}`, name: cleanName(folder), source: store.source };
  }
  const listed = library.files.get(file.toLowerCase()) ?? library.files.get(baseName(p.path).toLowerCase());
  if (listed) return { key: listed.key, name: listed.name, source: listed.source };
  if (known) return { key: `known:${slug(known.name)}`, name: known.name, source: "known", ...(known.game_id ? { gameId: known.game_id } : {}) };
  return null;
}

/** Whether the folder a program sits in is worth reading for signs of a game. */
export function worthLooking(path: string): boolean {
  const n = norm(path);
  if (!n.includes("/")) return false;
  return !/^([a-z]:\/windows\/|[a-z]:\/program files( \(x86\))?\/(common files|windows|microsoft|nvidia|amd|intel|google|mozilla|windowsapps)|\/usr\/|\/bin\/|\/sbin\/|\/lib|\/opt\/google|\/snap\/|\/system\/|\/library\/|\/applications\/(safari|utilities|xcode))/.test(n);
}

/** The people who started each process, for finding a family: pid to parent pid. */
export type Parents = Map<number, number>;

/** Whether one process is the other, or an ancestor of it. */
export function sameFamily(a: number, b: number, parents: Parents): boolean {
  const reaches = (from: number, to: number) => {
    let at: number | undefined = from;
    for (let i = 0; i < 32 && at !== undefined && at > 1; i++) {
      if (at === to) return true;
      at = parents.get(at);
    }
    return false;
  };
  return reaches(a, b) || reaches(b, a);
}
