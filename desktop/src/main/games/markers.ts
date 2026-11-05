/**
 * Games no store on this computer knows: DRM-free downloads, itch.io, a copy
 * moved to another drive. What sits beside the program says what it is, first a
 * store's own note about the game and otherwise the engine it was made with.
 * Each program is looked at once, and only folders that could hold a game.
 */
import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { gunzip } from "node:zlib";
import { promisify } from "node:util";
import { baseName, cleanName, folderName, norm, type Match } from "./match";

const unzip = promisify(gunzip);

/** Tools made with the same engines as the games they make. */
const NOT_A_GAME = /(unity hub|unityhub|\/unity\/|\/hub\/editor\/|ue_\d|unrealeditor|unrealengine|\/godot|gamemaker(studio)?|renpy-\d)/;

async function names(dir: string): Promise<string[]> {
  try {
    return await fs.readdir(dir);
  } catch {
    return [];
  }
}

async function exists(p: string): Promise<boolean> {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

/** A GOG game's note about itself, in its own folder wherever it was installed from. */
async function gogNote(dir: string, inside: string[]): Promise<Match | null> {
  const file = inside.find((f) => /^goggame-\d+\.info$/i.test(f));
  if (!file) return null;
  try {
    const info = JSON.parse(await fs.readFile(path.join(dir, file), "utf8")) as { gameId?: unknown; rootGameId?: unknown; name?: unknown };
    const id = String(info.rootGameId ?? info.gameId ?? "");
    if (!/^\d+$/.test(id) || typeof info.name !== "string" || !info.name) return null;
    return { key: `gog:${id}`, name: cleanName(info.name), source: "gog", store: "gog", storeId: id };
  } catch {
    return null;
  }
}

/** What the itch.io app wrote when it installed a game. */
async function itchNote(dir: string): Promise<Match | null> {
  try {
    const raw = await fs.readFile(path.join(dir, ".itch", "receipt.json.gz"));
    const receipt = JSON.parse(String(await unzip(raw))) as { game?: { id?: unknown; title?: unknown } };
    const title = receipt.game?.title;
    if (typeof title !== "string" || !title) return null;
    return { key: `itch:${String(receipt.game?.id ?? norm(title))}`, name: cleanName(title), source: "itch" };
  } catch {
    return null;
  }
}

/** Game Pass games keep their display name beside the program, unless it is a resource to be looked up. */
async function xboxNote(dir: string, inside: string[]): Promise<Match | null> {
  const file = inside.find((f) => f.toLowerCase() === "microsoftgame.config");
  if (!file) return null;
  try {
    const name = /DefaultDisplayName="([^"]+)"/.exec(await fs.readFile(path.join(dir, file), "utf8"))?.[1];
    if (!name || name.startsWith("ms-resource:")) return null;
    return { key: `dir:${norm(name)}`, name: cleanName(name), source: "xbox" };
  } catch {
    return null;
  }
}

/** Whether a program sits among what an engine puts beside a finished game. */
async function engineGame(exe: string, dir: string, inside: string[]): Promise<boolean> {
  const file = baseName(exe);
  const stem = file.replace(/\.(exe|x86_64|x86|bin)$/i, "");
  const has = (name: string) => inside.some((f) => f.toLowerCase() === name.toLowerCase());
  // Unity, Godot, GameMaker, Ren'Py and LÖVE each leave their files beside the program
  if (has("UnityPlayer.dll") || has("UnityPlayer.so") || has(`${stem}_Data`)) return true;
  if (has(`${stem}.pck`) || has("data.win") || has("game.unx") || has("game.ios")) return true;
  if (has("renpy") && has("game")) return true;
  // Unreal's packaged games, at <game>/Binaries/Win64/<game>-Win64-Shipping.exe
  if (/-(win64|linux|wingdk)-shipping(\.exe)?$/i.test(file)) return true;
  // a Mac game's bundle
  if (/\.app\/contents\/macos\//i.test(exe)) {
    const contents = path.dirname(dir);
    return (await exists(path.join(contents, "Frameworks", "UnityPlayer.dylib"))) || (await exists(path.join(contents, "Resources", "Data", "globalgamemanagers")));
  }
  return false;
}

/** A Mac program's category, from an XML Info.plist: "public.app-category.action-games", say. */
export function plistValue(xml: string, key: string): string | null {
  return new RegExp(`<key>${key}</key>\\s*<string>([^<]*)</string>`).exec(xml)?.[1] ?? null;
}

function plutil(file: string, key: string): Promise<string | null> {
  return new Promise((resolve) => {
    execFile("/usr/bin/plutil", ["-extract", key, "raw", "-o", "-", file], { timeout: 3000 }, (err, stdout) => resolve(err ? null : String(stdout).trim() || null));
  });
}

/** Every Mac game says what it is: the category is how the App Store and Launchpad's Games folder sort it. */
async function macGame(exe: string): Promise<Match | null> {
  const contents = /^(.*\.app[/\\]Contents)[/\\]MacOS[/\\]/i.exec(exe)?.[1];
  if (!contents) return null;
  const file = path.join(contents, "Info.plist");
  let read: (key: string) => Promise<string | null>;
  try {
    const raw = await fs.readFile(file);
    // a binary plist takes the system's tool; most are XML and do not
    read = raw.subarray(0, 6).toString() === "bplist" ? (key) => plutil(file, key) : async (key) => plistValue(raw.toString("utf8"), key);
  } catch {
    return null;
  }
  const category = await read("LSApplicationCategoryType");
  if (!category || !/games$/i.test(category)) return null;
  const name = cleanName((await read("CFBundleDisplayName")) ?? (await read("CFBundleName")) ?? path.basename(path.dirname(contents), ".app"));
  return name ? { key: `dir:${name.toLowerCase()}`, name, source: "system" } : null;
}

/** What the program's surroundings say it is, or null when they say nothing. */
export async function readMarkers(exe: string): Promise<Match | null> {
  const slashed = norm(exe);
  if (NOT_A_GAME.test(slashed)) return null;
  const mac = await macGame(exe);
  if (mac) return mac;
  const home = norm(process.env.HOME ?? process.env.USERPROFILE ?? "");
  let dir = path.dirname(exe);
  const own = await names(dir);
  // a store's note may sit a few folders above a program in bin/x64
  let at = dir;
  let inside = own;
  for (let up = 0; up < 4; up++) {
    const found = (await gogNote(at, inside)) ?? (await itchNote(at)) ?? (await xboxNote(at, inside));
    if (found) return found;
    const parent = path.dirname(at);
    if (parent === at || norm(parent) === home || /^[a-z]:\/?$/.test(norm(parent))) break;
    at = parent;
    inside = await names(at);
  }
  dir = path.dirname(exe);
  if (!(await engineGame(exe, dir, own))) return null;
  const bundle = /([^/\\]+)\.app[/\\]contents[/\\]macos[/\\]/i.exec(exe)?.[1];
  const name = bundle ? cleanName(bundle) : folderName(exe) ?? cleanName(baseName(exe), true);
  if (!name) return null;
  return { key: `dir:${name.toLowerCase()}`, name, source: "engine" };
}

/** A program built on a browser (Electron, CEF): those fill the screen and use the graphics card as any web page does. */
export async function isWebApp(exe: string): Promise<boolean> {
  const dir = path.dirname(exe);
  for (const inside of ["resources/app.asar", "resources/app", "libcef.dll", "libcef.so", "chrome_100_percent.pak", "../Frameworks/Electron Framework.framework"]) {
    if (await exists(path.join(dir, inside))) return true;
  }
  return false;
}
