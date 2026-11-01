import { AudioKeeper, isHomeScreenApp } from "./audioKeeper";
import { DEFAULT_SOUNDS, SOUND_NAMES, sourceOf, type SoundFile, type SoundSettings, type SoundSource } from "./soundPacks";

import { t as tr_ } from "@/i18n";
export type SoundName = "message" | "mention" | "friend" | "ring" | "outgoing" | "join" | "leave" | "mute" | "unmute" | "deafen" | "undeafen" | "shareStart" | "shareStop";

/** The account's sound packs, volume and the sounds switched off (lib/soundPacks.ts), as set by configureSounds. */
let config: SoundSettings = DEFAULT_SOUNDS;
/** How a custom pack's sealed recordings are fetched and opened; set with the settings, so this module needs no network of its own. */
let openFile: ((file: SoundFile) => Promise<ArrayBuffer>) | null = null;

/**
 * Takes the account's sound settings: which pack plays, how loud, and which
 * sounds are off. Called whenever they change, on every device the account
 * is signed in on.
 */
export function configureSounds(next: SoundSettings, open?: (file: SoundFile) => Promise<ArrayBuffer>): void {
  config = next;
  if (open) openFile = open;
  // a ring already going carries on as it is; the next sound plays from the new pack
  if (preloadWanted && keeper) decodeActive();
}

/** How loud sounds play, from the volume setting. */
function level(): number {
  return Math.max(0, Math.min(1, config.volume / 100));
}

/** A sound that cannot start within this long is dropped rather than played late. */
const STALE_MS = 1500;
/** How long a home-screen app keeps audio running after the last sound, for the next one close behind. */
const IDLE_MS = 2000;
/** A context the device refuses to start is swapped for a new one at most this often; taps in between try the same one again. */
const REPLACE_MS = 30_000;

let keeper: AudioKeeper | null = null;
/** When every sound played so far will have finished, in ms since the epoch. */
let quietFrom = 0;
/** Sounds waiting for audio to start. */
let starting = 0;
/** Why the last sound that could not play did not, for the settings page. */
let trouble: { state: string; problem: string | null } | null = null;
let replacedAt = -Infinity;

function contextClass(): typeof AudioContext | undefined {
  if (typeof window === "undefined") return undefined;
  return window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
}

/** The context sounds play through, made on first use (see audioKeeper for when it runs). */
function audio(): AudioKeeper | null {
  if (keeper && keeper.ac.state !== "closed") return keeper;
  const Ctor = contextClass();
  if (!Ctor) return null;
  const k = new AudioKeeper(new Ctor());
  keeper = k;
  k.ac.addEventListener("statechange", () => {
    if (keeper !== k) return;
    const state = k.ac.state as string;
    if (state === "running") {
      disarmUnlock();
      if (k.released) return;
      startLoops();
      restLater();
    } else if (k.released || state === "closed") disarmUnlock();
    // iOS suspends the context again after a phone call or Siri, and only a tap can wake it
    else armUnlock();
  });
  watchVisibility();
  if (preloadWanted) decodeActive();
  return k;
}

const UNLOCK_EVENTS = ["pointerdown", "touchend", "click", "keydown"] as const;
let unlockWanted = false;
let unlockArmed = false;

/**
 * Starts the context from inside a tap or key press. Sounds mostly play from
 * gateway events, and iOS keeps Web Audio silent until a gesture has started
 * it, which resume() outside one cannot do. A one-sample silent buffer is what
 * makes WebKit count the gesture. A click counts on its own, as VoiceOver
 * activates things without any touches.
 */
function unlock(): void {
  const k = audio();
  if (!k) {
    disarmUnlock();
    return;
  }
  const ac = k.ac;
  void k.want().then((ok) => {
    // a context the device would not start is let go, and the next tap makes a new one
    if (!ok && k.refused && keeper === k && ac.state === "suspended" && Date.now() - replacedAt >= REPLACE_MS) {
      replacedAt = Date.now();
      keeper = null;
      void ac.close().catch(() => undefined);
    }
  });
  try {
    const src = ac.createBufferSource();
    src.buffer = ac.createBuffer(1, 1, 22050);
    src.connect(ac.destination);
    src.start(0);
  } catch {
    /* nothing to unlock with; the next gesture tries again */
  }
  if (ac.state === "running") disarmUnlock();
}

function armUnlock(): void {
  if (!unlockWanted || unlockArmed || typeof window === "undefined") return;
  unlockArmed = true;
  for (const t of UNLOCK_EVENTS) window.addEventListener(t, unlock, { capture: true, passive: true });
}

function disarmUnlock(): void {
  if (!unlockArmed) return;
  unlockArmed = false;
  for (const t of UNLOCK_EVENTS) window.removeEventListener(t, unlock, { capture: true });
}

/** Lets the next tap or key press start audio, and again whenever the system suspends it. */
export function installAudioUnlock(): void {
  unlockWanted = true;
  if (keeper?.ac.state !== "running") armUnlock();
}

let watching = false;

/** A home-screen app lets its audio go when it goes to the background; a ring still going carries on when it is back. */
function watchVisibility(): void {
  if (watching || typeof document === "undefined") return;
  watching = true;
  document.addEventListener("visibilitychange", () => {
    if (!keeper || !isHomeScreenApp()) return;
    if (document.visibilityState === "hidden") keeper.release();
    else if (loops.size > 0) void keeper.want();
  });
  window.addEventListener("pagehide", () => {
    if (isHomeScreenApp()) keeper?.release();
  });
}

function hiddenHomeScreenApp(): boolean {
  return isHomeScreenApp() && typeof document !== "undefined" && document.visibilityState === "hidden";
}

/** In a home-screen app, lets audio go once the last sound has finished and nothing rings. */
function restLater(): void {
  keeper?.releaseLater(() => loops.size === 0 && starting === 0 && Date.now() >= quietFrom, Math.max(0, quietFrom - Date.now()) + IDLE_MS);
}

/** Schedules one tone; returns when it will have finished, in seconds from now. */
function tone(ac: AudioContext, freq: number, start: number, dur: number, type: OscillatorType = "sine", gain = 0.18): number {
  const o = ac.createOscillator();
  const g = ac.createGain();
  o.type = type;
  o.frequency.setValueAtTime(freq, ac.currentTime + start);
  g.gain.setValueAtTime(0, ac.currentTime + start);
  g.gain.linearRampToValueAtTime(Math.max(0.0002, gain * level()), ac.currentTime + start + 0.01);
  g.gain.exponentialRampToValueAtTime(0.0001, ac.currentTime + start + dur);
  o.connect(g).connect(ac.destination);
  o.start(ac.currentTime + start);
  o.stop(ac.currentTime + start + dur + 0.02);
  return start + dur + 0.02;
}

/**
 * The recorded sounds, served with the app (public/sounds). The rest are
 * synthesised, and so is any recording that cannot be had, so a sound never
 * goes missing.
 */
const FILES: Partial<Record<SoundName, string>> = {
  message: "/sounds/message.m4a",
  mention: "/sounds/mention.m4a",
  friend: "/sounds/friend-request.m4a",
  ring: "/sounds/incoming-call.m4a",
  outgoing: "/sounds/outgoing-call.m4a",
  leave: "/sounds/leave-call.m4a",
  mute: "/sounds/mute.m4a",
  unmute: "/sounds/unmute.m4a",
  deafen: "/sounds/deafen.m4a",
  undeafen: "/sounds/undeafen.m4a",
  shareStart: "/sounds/share-start.m4a",
  shareStop: "/sounds/share-stop.m4a",
};

/** How loud the recordings play; they are mastered near full scale. */
const FILE_GAIN = 0.7;

/**
 * Recordings by what they are: "nexa:<sound>" for the ones served with the
 * app, "file:<blob>" for a custom pack's. Bytes are fetched once and decoded
 * once a context is there to decode them.
 */
const fetched = new Map<string, Promise<ArrayBuffer | null>>();
const buffers = new Map<string, AudioBuffer>();
const loading = new Map<string, Promise<AudioBuffer | null>>();
let preloadWanted = false;

/** Where a recording comes from, and its key in the maps above; null for a sound that is synthesised. */
function recordingOf(name: SoundName, src: SoundSource): { key: string; get: () => Promise<ArrayBuffer> } | null {
  if (src.kind === "file") {
    const file = src.file;
    return {
      key: `file:${file.blob}`,
      get: () => (openFile ? openFile(file) : Promise.reject(new Error("custom sounds are not set up yet"))),
    };
  }
  const url = FILES[name];
  if (!url) return null;
  return {
    key: `nexa:${name}`,
    get: () => fetch(url).then((r) => (r.ok ? r.arrayBuffer() : Promise.reject(new Error(String(r.status))))),
  };
}

function fetchBytes(rec: { key: string; get: () => Promise<ArrayBuffer> }): Promise<ArrayBuffer | null> {
  let p = fetched.get(rec.key);
  if (!p) {
    p = rec.get().catch(() => {
      // not remembered: a later sound tries again
      fetched.delete(rec.key);
      return null;
    });
    fetched.set(rec.key, p);
  }
  return p;
}

/** A recording, decoded; null when it cannot be had or there is no context to decode it with yet. */
function decodeRecording(rec: { key: string; get: () => Promise<ArrayBuffer> }): Promise<AudioBuffer | null> {
  const have = buffers.get(rec.key);
  if (have) return Promise.resolve(have);
  let p = loading.get(rec.key);
  if (!p) {
    p = fetchBytes(rec)
      .then((data) => {
        const ac = keeper?.ac;
        if (!data || !ac) return Promise.reject(new Error("nothing to decode"));
        // decoding takes the bytes over, and one that fails leaves them for the next try
        return ac.decodeAudioData(data.slice(0));
      })
      .then((buf) => {
        buffers.set(rec.key, buf);
        fetched.delete(rec.key);
        return buf;
      })
      .catch(() => {
        loading.delete(rec.key);
        return null;
      });
    loading.set(rec.key, p);
  }
  return p;
}

/**
 * What a sound plays as in a pack: a decoded recording, or null to synthesise
 * it. A custom recording that cannot be had plays as Nexa's own would, so a
 * sound never goes missing.
 */
async function loadSound(name: SoundName, packId?: string): Promise<AudioBuffer | null> {
  const src = sourceOf(config, name, packId);
  const rec = recordingOf(name, src);
  if (!rec) return null;
  const buf = await decodeRecording(rec);
  if (buf || src.kind === "nexa") return buf;
  const own = recordingOf(name, { kind: "nexa" });
  return own ? decodeRecording(own) : null;
}

/** The recordings of the pack in use. */
function decodeActive(): void {
  for (const name of SOUND_NAMES) {
    const rec = recordingOf(name, sourceOf(config, name));
    if (rec) void decodeRecording(rec);
  }
}

/**
 * Fetches and decodes the recordings ahead of time, so the first message plays
 * at once. A home-screen app decodes them once a tap has made its context: it
 * makes none before then.
 */
export function preloadSounds(): void {
  preloadWanted = true;
  for (const name of SOUND_NAMES) {
    const rec = recordingOf(name, sourceOf(config, name));
    if (rec) void fetchBytes(rec);
  }
  if (keeper) decodeActive();
  else if (!isHomeScreenApp()) void audio()?.want();
}

function playBuffer(ac: AudioContext, buf: AudioBuffer, loop = false): AudioBufferSourceNode {
  const src = ac.createBufferSource();
  src.buffer = buf;
  src.loop = loop;
  const g = ac.createGain();
  g.gain.value = FILE_GAIN * level();
  src.connect(g).connect(ac.destination);
  src.start();
  return src;
}

/** Plays a synthesised sound; returns its length in seconds. */
function synth(ac: AudioContext, name: SoundName): number {
  switch (name) {
    case "message":
      tone(ac, 880, 0, 0.09);
      return tone(ac, 1174.7, 0.09, 0.12);
    case "mention":
      tone(ac, 784, 0, 0.08);
      tone(ac, 987.8, 0.09, 0.08);
      return tone(ac, 1318.5, 0.18, 0.16);
    case "friend":
      // a bright rising three, warmer than a mention's
      tone(ac, 587.3, 0, 0.1, "triangle", 0.2);
      tone(ac, 880, 0.1, 0.1, "triangle", 0.2);
      return tone(ac, 1174.7, 0.2, 0.22, "triangle", 0.2);
    case "join":
      tone(ac, 523.3, 0, 0.08, "triangle");
      return tone(ac, 784, 0.08, 0.12, "triangle");
    case "leave":
      tone(ac, 784, 0, 0.08, "triangle");
      return tone(ac, 523.3, 0.08, 0.12, "triangle");
    case "mute":
      tone(ac, 440, 0, 0.07, "square", 0.08);
      return tone(ac, 330, 0.07, 0.1, "square", 0.08);
    case "unmute":
      tone(ac, 330, 0, 0.07, "square", 0.08);
      return tone(ac, 440, 0.07, 0.1, "square", 0.08);
    case "deafen":
      tone(ac, 392, 0, 0.07, "square", 0.08);
      return tone(ac, 261.6, 0.07, 0.12, "square", 0.08);
    case "undeafen":
      tone(ac, 261.6, 0, 0.07, "square", 0.08);
      return tone(ac, 392, 0.07, 0.12, "square", 0.08);
    case "shareStart":
      tone(ac, 659.3, 0, 0.08, "triangle");
      tone(ac, 880, 0.08, 0.08, "triangle");
      return tone(ac, 1318.5, 0.16, 0.14, "triangle");
    case "shareStop":
      tone(ac, 1318.5, 0, 0.08, "triangle");
      tone(ac, 880, 0.08, 0.08, "triangle");
      return tone(ac, 659.3, 0.16, 0.14, "triangle");
    case "ring":
    case "outgoing":
      tone(ac, 659.3, 0, 0.25, "sine", 0.15);
      return tone(ac, 880, 0.3, 0.25, "sine", 0.15);
  }
}

/** Plays a sound, unless it is switched off. Resolves with whether it could start. */
export function playSound(name: SoundName): Promise<boolean> {
  if (config.off.includes(name)) return Promise.resolve(false);
  return play(name).catch(() => false);
}

/**
 * Plays a sound to hear it, from the pack named or the one in use, even if it
 * is switched off: what the settings' play buttons do. Resolves with whether
 * it could start, which those buttons report.
 */
export function previewSound(name: SoundName, packId?: string): Promise<boolean> {
  return play(name, packId).catch(() => false);
}

async function play(name: SoundName, packId?: string): Promise<boolean> {
  // a home-screen app in the background stays quiet rather than start audio there
  if (hiddenHomeScreenApp()) return false;
  const k = audio();
  if (!k) return false;
  const asked = Date.now();
  starting++;
  try {
    const [running, buf] = await Promise.all([k.started(STALE_MS), loadSound(name, packId)]);
    if (!running || keeper !== k || k.released || Date.now() - asked > STALE_MS) {
      trouble = { state: k.ac.state, problem: k.problem };
      // the next tap may start what this could not
      if (keeper === k && !k.released && k.ac.state !== "running") armUnlock();
      return false;
    }
    trouble = null;
    let seconds: number;
    if (buf) {
      playBuffer(k.ac, buf);
      seconds = buf.duration;
    } else seconds = synth(k.ac, name);
    quietFrom = Math.max(quietFrom, Date.now() + seconds * 1000);
    return true;
  } finally {
    starting--;
    restLater();
  }
}

/** Why the last sound could not play, for the settings page to say. */
export function soundTrouble(): string {
  if (!contextClass()) return tr_("sounds.thisBrowserCanTPlaySounds");
  if (trouble?.state === "running") return tr_("sounds.theSoundTookTooLong");
  const detail = trouble ? ` (${trouble.problem ?? tr_("sounds.audioStayed", { state: trouble.state })})` : "";
  if (isHomeScreenApp()) return tr_("sounds.homeScreenSoundProblem", { detail, host: location.host });
  return tr_("sounds.soundProblem", { detail });
}

interface Loop {
  name: SoundName;
  /** The context it plays in, once begun. */
  ac: AudioContext | null;
  source: AudioBufferSourceNode | null;
  timer: ReturnType<typeof setInterval> | null;
}

/** Rings and outgoing tones going on. */
const loops = new Set<Loop>();

/** Begins every loop not yet playing in the context now running, a new one after a refused context included. */
function startLoops(): void {
  const k = keeper;
  if (!k || k.released || k.ac.state !== "running") return;
  for (const l of loops) if (l.ac !== k.ac) beginLoop(l, k.ac);
}

function beginLoop(l: Loop, ac: AudioContext): void {
  l.ac = ac;
  void loadSound(l.name).then((buf) => {
    if (!loops.has(l) || l.ac !== ac || ac.state === "closed") return;
    if (buf) {
      l.source = playBuffer(ac, buf, true);
      return;
    }
    synth(ac, l.name);
    l.timer = setInterval(() => {
      // tones asked for while it is stopped would all come out at once when it starts
      if (ac.state === "running") synth(ac, l.name);
    }, 1500);
  });
}

/** Plays a sound over and over until the returned function is called. */
function loop(name: SoundName): () => void {
  const k = audio();
  if (!k) return () => {};
  const l: Loop = { name, ac: null, source: null, timer: null };
  loops.add(l);
  // it waits for audio as long as it goes on: the tap that answers a ring may be what starts it
  if (!hiddenHomeScreenApp()) void k.want();
  startLoops();
  return () => {
    if (!loops.delete(l)) return;
    if (l.timer) clearInterval(l.timer);
    try {
      l.source?.stop();
    } catch {
      /* never started */
    }
    restLater();
  };
}

let stopIncoming: (() => void) | null = null;
let stopOutgoing: (() => void) | null = null;

/** Someone is calling: rings until stopRinging, unless the ring is switched off. */
export function startRinging(): void {
  stopRinging();
  if (config.off.includes("ring")) return;
  stopIncoming = loop("ring");
}

export function stopRinging(): void {
  stopIncoming?.();
  stopIncoming = null;
}

/** Calling someone who has not picked up yet: the outgoing tone, until stopCalling. */
export function startCalling(): void {
  stopCalling();
  if (config.off.includes("outgoing")) return;
  stopOutgoing = loop("outgoing");
}

export function stopCalling(): void {
  stopOutgoing?.();
  stopOutgoing = null;
}
