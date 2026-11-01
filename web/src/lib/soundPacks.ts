/**
 * Sound packs: which sound plays for a new message, a mention, a call and the
 * rest.
 *
 * Nexa's own sounds come with the app. Anyone can also make packs of their
 * own from recordings they pick: each file is turned into a short mono
 * recording here, sealed like an attachment and kept on the server
 * (api/sounds.ts), while the packs themselves (names, which file plays for
 * what, and the keys that open the files) live in the account's encrypted
 * settings, so they follow the account to every device. A sound a custom pack
 * leaves out plays as Nexa's own.
 *
 * Everything here is read as another device may have written it.
 */

import type { SoundName } from "./sounds";

export const SOUND_NAMES: readonly SoundName[] = ["message", "mention", "friend", "ring", "outgoing", "join", "leave", "mute", "unmute", "deafen", "undeafen", "shareStart", "shareStop"];

/** Nexa's own sounds: the pack that comes with the app. */
export const NEXA_PACK = "nexa";

/** One recording of a custom pack. */
export interface SoundFile {
  /** The sealed file on the server. */
  blob: string;
  /** Its key, base64. */
  key: string;
  /** What the file was called when it was picked, to show. */
  name: string;
  /** How long it plays, in milliseconds. */
  ms: number;
  /** The sealed file's size, to check the download against. */
  size: number;
}

export interface SoundPack {
  id: string;
  name: string;
  files: Partial<Record<SoundName, SoundFile>>;
}

export interface SoundSettings {
  /** Nexa's own pack, or a custom pack's id. */
  pack: string;
  /** 0 to 100. */
  volume: number;
  /** Sounds switched off one by one. */
  off: SoundName[];
  packs: SoundPack[];
}

export const DEFAULT_SOUNDS: SoundSettings = { pack: NEXA_PACK, volume: 100, off: [], packs: [] };

export const MAX_PACKS = 8;
export const PACK_NAME_MAX = 40;
/** How long a sound may play: a ring or a dialling tone loops, so it may be longer than the rest. */
export const MAX_SOUND_MS = 6000;
export const MAX_LOOP_MS = 20_000;
/** The biggest file that is even tried; what is kept is far smaller. */
export const MAX_SOURCE_BYTES = 30 * 1024 * 1024;

export const isLoop = (name: SoundName): boolean => name === "ring" || name === "outgoing";
const isSoundName = (x: unknown): x is SoundName => typeof x === "string" && (SOUND_NAMES as readonly string[]).includes(x);

type Obj = Record<string, unknown>;
const isObj = (x: unknown): x is Obj => typeof x === "object" && x !== null && !Array.isArray(x);

function readFile(x: unknown): SoundFile | null {
  if (!isObj(x)) return null;
  const { blob, key, name, ms, size } = x;
  if (typeof blob !== "string" || !/^\d{1,20}$/.test(blob)) return null;
  if (typeof key !== "string" || key.length < 16 || key.length > 128) return null;
  return {
    blob,
    key,
    name: typeof name === "string" ? name.slice(0, 120) : "",
    ms: typeof ms === "number" && Number.isFinite(ms) ? Math.max(0, Math.min(MAX_LOOP_MS, Math.round(ms))) : 0,
    size: typeof size === "number" && Number.isFinite(size) && size > 0 ? Math.round(size) : 0,
  };
}

function readPack(x: unknown): SoundPack | null {
  if (!isObj(x)) return null;
  const { id, name, files } = x;
  if (typeof id !== "string" || !/^[A-Za-z0-9_-]{4,40}$/.test(id) || id === NEXA_PACK) return null;
  const out: SoundPack = {
    id,
    name: typeof name === "string" && name.trim() ? name.replace(/\s+/g, " ").trim().slice(0, PACK_NAME_MAX) : "",
    files: {},
  };
  if (isObj(files)) {
    for (const n of SOUND_NAMES) {
      const f = readFile(files[n]);
      if (f) out.files[n] = f;
    }
  }
  return out;
}

/** Sound settings as they can be trusted, whatever another version of the app saved. */
export function sanitizeSounds(x: unknown): SoundSettings {
  if (!isObj(x)) return { ...DEFAULT_SOUNDS };
  const packs: SoundPack[] = [];
  const seen = new Set<string>();
  if (Array.isArray(x["packs"])) {
    for (const raw of x["packs"]) {
      const p = readPack(raw);
      if (!p || seen.has(p.id)) continue;
      seen.add(p.id);
      packs.push(p);
      if (packs.length >= MAX_PACKS) break;
    }
  }
  const pack = typeof x["pack"] === "string" && (x["pack"] === NEXA_PACK || seen.has(x["pack"])) ? x["pack"] : DEFAULT_SOUNDS.pack;
  const volume = typeof x["volume"] === "number" && Number.isFinite(x["volume"]) ? Math.max(0, Math.min(100, Math.round(x["volume"]))) : DEFAULT_SOUNDS.volume;
  const off = Array.isArray(x["off"]) ? [...new Set(x["off"].filter(isSoundName))] : [];
  return { pack, volume, off, packs };
}

/** What plays for a sound in a pack: one of its own files, or Nexa's own sound. */
export type SoundSource = { kind: "file"; file: SoundFile } | { kind: "nexa" };

export function sourceOf(settings: SoundSettings, name: SoundName, packId: string = settings.pack): SoundSource {
  const file = settings.packs.find((p) => p.id === packId)?.files[name];
  return file ? { kind: "file", file } : { kind: "nexa" };
}

/** Every sealed file the packs name, each once. */
export function filesInUse(settings: SoundSettings): Set<string> {
  const out = new Set<string>();
  for (const p of settings.packs) for (const f of Object.values(p.files)) if (f) out.add(f.blob);
  return out;
}

/** A new pack's id: random, so two devices making one at once never pick the same. */
export function newPackId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(9));
  return "p" + Array.from(bytes, (b) => b.toString(36).padStart(2, "0")).join("").slice(0, 15);
}

// --- turning a picked file into a sound ----------------------------------

/** Why a file could not be made into a sound. */
export class SoundFileError extends Error {
  constructor(readonly reason: "too_big" | "unreadable" | "silent") {
    super(reason);
  }
}

/** The rate sounds are kept at: plenty for a chime, and a ringtone stays small. */
const RATE = 32_000;
/** Quieter than this counts as silence at either end, which is cut away. */
const SILENCE = 0.004;
const FADE_S = 0.12;

function audioContextClass(): typeof OfflineAudioContext | undefined {
  if (typeof window === "undefined") return undefined;
  return window.OfflineAudioContext ?? (window as unknown as { webkitOfflineAudioContext?: typeof OfflineAudioContext }).webkitOfflineAudioContext;
}

/** Where the sound in a buffer starts and ends, with silence at either end left out. */
function audibleSpan(buf: AudioBuffer): { start: number; end: number; peak: number } {
  const n = buf.length;
  let first = n;
  let last = -1;
  let peak = 0;
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const d = buf.getChannelData(c);
    for (let i = 0; i < n; i++) {
      const v = Math.abs(d[i]!);
      if (v > peak) peak = v;
      if (v > SILENCE) {
        if (i < first) first = i;
        if (i > last) last = i;
      }
    }
  }
  if (last < 0) return { start: 0, end: 0, peak };
  // a little air either side, so a soft attack or a tail is not clipped
  const pad = Math.round(buf.sampleRate * 0.01);
  return { start: Math.max(0, first - pad) / buf.sampleRate, end: Math.min(n, last + pad * 4) / buf.sampleRate, peak };
}

/** 16-bit mono PCM in a WAV file, which every browser can play back. */
export function encodeWav(samples: Float32Array, rate: number): Blob {
  const data = new DataView(new ArrayBuffer(44 + samples.length * 2));
  const text = (at: number, s: string) => {
    for (let i = 0; i < s.length; i++) data.setUint8(at + i, s.charCodeAt(i));
  };
  text(0, "RIFF");
  data.setUint32(4, 36 + samples.length * 2, true);
  text(8, "WAVE");
  text(12, "fmt ");
  data.setUint32(16, 16, true);
  data.setUint16(20, 1, true);
  data.setUint16(22, 1, true);
  data.setUint32(24, rate, true);
  data.setUint32(28, rate * 2, true);
  data.setUint16(32, 2, true);
  data.setUint16(34, 16, true);
  text(36, "data");
  data.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const v = Math.max(-1, Math.min(1, samples[i]!));
    data.setInt16(44 + i * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true);
  }
  return new Blob([data.buffer], { type: "audio/wav" });
}

/**
 * Makes a picked file into a sound: decoded with whatever the browser can
 * read, silence cut from either end, no longer than the sound may play
 * (faded out where it is cut), mixed down to one channel, brought up to a
 * sensible level and kept as a small WAV.
 */
export async function prepareSound(file: Blob, name: SoundName): Promise<{ wav: Blob; ms: number }> {
  if (file.size > MAX_SOURCE_BYTES) throw new SoundFileError("too_big");
  const Ctx = audioContextClass();
  if (!Ctx) throw new SoundFileError("unreadable");
  let decoded: AudioBuffer;
  try {
    decoded = await new Ctx(1, 1, RATE).decodeAudioData(await file.arrayBuffer());
  } catch {
    throw new SoundFileError("unreadable");
  }
  const span = audibleSpan(decoded);
  if (span.end <= span.start || span.peak < SILENCE * 2) throw new SoundFileError("silent");
  const limit = (isLoop(name) ? MAX_LOOP_MS : MAX_SOUND_MS) / 1000;
  const length = Math.min(limit, span.end - span.start);
  const frames = Math.max(1, Math.ceil(length * RATE));
  const out = new Ctx(1, frames, RATE);
  const src = out.createBufferSource();
  src.buffer = decoded;
  const gain = out.createGain();
  // brought to just under full scale, so a quiet recording is not lost under the rest
  const level = Math.min(8, 0.89 / span.peak);
  gain.gain.setValueAtTime(level, 0);
  if (length < span.end - span.start) {
    // cut short: faded out rather than stopped dead
    gain.gain.setValueAtTime(level, Math.max(0, length - FADE_S));
    gain.gain.linearRampToValueAtTime(0, length);
  }
  src.connect(gain).connect(out.destination);
  src.start(0, span.start, length);
  const rendered = await out.startRendering();
  return { wav: encodeWav(rendered.getChannelData(0), RATE), ms: Math.round(length * 1000) };
}
