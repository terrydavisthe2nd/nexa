/**
 * Games no list or store knows, found by how they behave: a program filling the
 * screen or drawing hard with the graphics card, whose name is a game's name on
 * IGDB. Nothing about the program leaves the computer to check that: the server
 * hands out a hashed index of every game's name (internal/games/nameindex.go)
 * and the check happens here.
 *
 * What comes out is only ever a suggestion. The person says whether it is a
 * game; until then the name stays here too.
 */
import { createHash } from "node:crypto";
import { baseName, cleanName, entryOf, fileOf, folderName, type Proc } from "./match";

export type Tier = "rated" | "other";

/** A game's name as names are compared: internal/games FoldName, which testdata/fold_vectors.json holds the two to. */
export function foldName(s: string): string {
  let out = "";
  let space = false;
  for (const ch of s.toLowerCase()) {
    if (/[\p{L}\p{N}]/u.test(ch)) {
      if (space && out) out += " ";
      space = false;
      out += ch;
    } else if (/\s/u.test(ch) || ch === ":" || ch === "-" || ch === "_") {
      space = true;
    }
  }
  return out;
}

const HASH_BYTES = 5;

export function nameHash(name: string): Buffer {
  return createHash("sha256").update(foldName(name), "utf8").digest().subarray(0, HASH_BYTES);
}

export class NameIndex {
  private constructor(
    private readonly data: Buffer,
    private readonly rated: number,
    private readonly other: number,
  ) {}

  /** Reads the server's file; null when it is not one, so a damaged copy is never searched. */
  static parse(data: Buffer): NameIndex | null {
    if (data.length < 13 || data.toString("latin1", 0, 4) !== "NXGI" || data[4] !== 1) return null;
    const rated = data.readUInt32BE(5);
    const other = data.readUInt32BE(9);
    if (data.length !== 13 + (rated + other) * HASH_BYTES) return null;
    return new NameIndex(data, rated, other);
  }

  private search(start: number, count: number, key: Buffer): boolean {
    let low = 0;
    let high = count - 1;
    while (low <= high) {
      const mid = (low + high) >> 1;
      const at = 13 + (start + mid) * HASH_BYTES;
      const cmp = this.data.compare(key, 0, HASH_BYTES, at, at + HASH_BYTES);
      if (cmp === 0) return true;
      if (cmp > 0) high = mid - 1;
      else low = mid + 1;
    }
    return false;
  }

  /** Which part of the index holds a game of this name, if any. */
  has(name: string): Tier | null {
    if (!foldName(name)) return null;
    const key = nameHash(name);
    if (this.search(0, this.rated, key)) return "rated";
    return this.search(this.rated, this.other, key) ? "other" : null;
  }
}

/** What windows and files add to a game's name: renderers, frame counters, versions, editions. */
const NOISE: RegExp[] = [
  /\s*\(c\)\s*\d{4}\b.*$/i,
  /\s*[([][^)\]]*[)\]]\s*/g,
  /\s*[-|:]?\s*\b\d+(\.\d+)?\s*fps\b.*$/i,
  /\s*[-|:]?\s*\b(directx|dx|d3d)\s?\d+.*$/i,
  /\s*[-|:]?\s*\b(vulkan|opengl|metal)\b.*$/i,
  /\s*[-|:]?\s*\bv?\d+(\.\d+){1,}.*$/i,
  /\s*[-|:]?\s*\b(build|alpha|beta|early access|demo|playtest)\b.*$/i,
];
const EDITION = /\s*[-|:]?\s*(game of the year|goty|definitive|enhanced|special|ultimate|complete|anniversary|deluxe|gold|premium|legendary|royal|remastered|remaster|director'?s cut)(\s+edition)?$/i;
const SEPARATORS = /\s+[-–—|·•]\s+|\s{2,}/;

/** Every way a window's title or a file's name could say a game's name, the fullest first. */
export function nameVariants(raw: string): string[] {
  const out = new Map<string, string>();
  const add = (value: string) => {
    const v = value.replace(/\s+/g, " ").trim();
    const key = foldName(v);
    if (key.length >= 3 && !/^\d+( fps)?$/.test(key) && !out.has(key)) out.set(key, v);
  };
  const whole = cleanName(raw);
  add(whole);
  let plain = whole;
  for (const noise of NOISE) plain = plain.replace(noise, " ").trim();
  add(plain);
  add(plain.replace(EDITION, ""));
  for (const part of plain.split(SEPARATORS)) {
    add(part);
    add(part.replace(EDITION, ""));
  }
  // "Terraria: Shut Up and Dig Gaiden!" is a window saying a different thing each time; the part before it stays put
  const colon = plain.indexOf(":");
  if (colon > 2) add(plain.slice(0, colon));
  return [...out.values()].sort((a, b) => foldName(b).length - foldName(a).length);
}

/** Programs that fill the screen or use the graphics card all day and are no game. */
const NEVER_A_GAME = /^(chrome|chromium|firefox|msedge|brave|opera|vivaldi|safari|arc|zen|tor|thorium|vlc|mpv|mpc-hc(64)?|mpc-be(64)?|potplayer(mini)?(64)?|kodi|plex|plexamp|spotify|netflix|quicktime ?player|iina|wmplayer|stremio|jellyfin.*|winword|excel|powerpnt|outlook|soffice.*|libreoffice.*|onenote|acrobat|acrord32|notepad(\+\+)?|photoshop|illustrator|adobe .*|afterfx|blender|krita|gimp.*|resolve|obs(64)?|figma|unity|unityhub|unrealeditor.*|godot.*|code|cursor|devenv|idea(64)?|pycharm(64)?|webstorm(64)?|rider(64)?|clion(64)?|windowsterminal|wezterm.*|alacritty|kitty|konsole|gnome-terminal.*|xterm|mstsc|parsec.*|moonlight|teamviewer.*|anydesk|vmware.*|virtualbox.*|virtualboxvm|qemu.*|discord.*|slack|teams|ms-teams|zoom|telegram.*|signal|skype|nexa|explorer|finder|dwm|applicationframehost|searchhost|shellexperiencehost|startmenuexperiencehost|lockapp|nvcontainer|nvidia.*|radeonsoftware|steam|steamwebhelper|epicgameslauncher|galaxyclient|battle\.net|eadesktop|origin|upc|riotclientservices|wallpaper(32|64)|livelywpf|rainmeter|screensaver.*)(\.exe|\.scr)?$/i;

/** Words that are also games somebody made, and are far more likely to be the program they say. */
const PLAIN_WORDS = new Set("app apps application browser calculator calendar camera chat client clock code console demo desktop documents downloads drive editor explorer files game games home launcher mail maps media messages music news notes office paint photos player preview program reader recorder settings start steam store studio terminal test tools update video viewer weather web window windows word work".split(" "));

export interface Evidence {
  fullscreen: boolean;
  gpu: boolean;
  /** Windows' own game features have treated this program as a game. */
  osGame: boolean;
}

export interface Candidate {
  /** The name to offer, as the window or the file wrote it. */
  name: string;
  tier: Tier;
  score: number;
}

/**
 * What it takes to ask. Behaviour alone never asks, and neither does a name
 * alone: a rated game's name on a program filling the screen is 4, and it takes
 * one more thing (a name of more than one word, or two places saying the same
 * name) to reach SUGGEST_AT. An obscure game's name, where almost anything could
 * be somebody's game, needs both kinds of behaviour.
 */
export const SUGGEST_AT = 5;
const WEIGHT = { fullscreen: 2, gpu: 2, osGame: 2, rated: 2, severalWords: 1, agreement: 1, onlyFromFile: -1 };

/** The name, if any, that makes this program worth asking about. */
export function judge(p: Proc, sources: { title?: string; product?: string | null }, evidence: Evidence, index: NameIndex): Candidate | null {
  if (!evidence.fullscreen && !evidence.gpu) return null;
  const file = fileOf(p);
  if (NEVER_A_GAME.test(file)) return null;
  const entry = entryOf(p);
  const places: { text: string; fromFile: boolean }[] = [];
  if (sources.title) places.push({ text: sources.title, fromFile: false });
  if (sources.product) places.push({ text: sources.product, fromFile: false });
  const bundle = /([^/\\]+)\.app[/\\]contents[/\\]macos[/\\]/i.exec(entry)?.[1];
  if (bundle) places.push({ text: bundle, fromFile: false });
  const folder = folderName(entry);
  if (folder) places.push({ text: folder, fromFile: true });
  const stem = cleanName(baseName(file), true);
  if (stem) places.push({ text: stem, fromFile: true });

  // every place that says each name, so a name two of them agree on counts for more
  const found = new Map<string, { name: string; tier: Tier; places: Set<number>; fromFile: boolean }>();
  places.forEach((place, i) => {
    for (const variant of nameVariants(place.text)) {
      const key = foldName(variant);
      if (PLAIN_WORDS.has(key)) continue;
      const tier = index.has(variant);
      if (!tier) continue;
      const at = found.get(key);
      if (at) {
        at.places.add(i);
        at.fromFile &&= place.fromFile;
      } else {
        found.set(key, { name: variant, tier, places: new Set([i]), fromFile: place.fromFile });
      }
    }
  });
  const behaviour = (evidence.fullscreen ? WEIGHT.fullscreen : 0) + (evidence.gpu ? WEIGHT.gpu : 0) + (evidence.osGame ? WEIGHT.osGame : 0);
  let best: Candidate | null = null;
  for (const [key, f] of found) {
    const score =
      behaviour +
      (f.tier === "rated" ? WEIGHT.rated : 0) +
      (key.includes(" ") ? WEIGHT.severalWords : 0) +
      (f.places.size > 1 ? WEIGHT.agreement : 0) +
      (f.fromFile ? WEIGHT.onlyFromFile : 0);
    if (!best || score > best.score || (score === best.score && foldName(f.name).length > foldName(best.name).length)) best = { name: f.name, tier: f.tier, score };
  }
  return best && best.score >= SUGGEST_AT ? best : null;
}
