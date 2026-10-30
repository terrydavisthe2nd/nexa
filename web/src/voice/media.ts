/**
 * Local media: microphone, camera and screen capture with the user's voice
 * settings applied, plus a small RMS level meter used for voice activity and
 * the settings page.
 */
import { AudioKeeper, isHomeScreenApp } from "@/lib/audioKeeper";
import type { VoiceSettings } from "@/stores/settings";
import { DENOISE_RATE } from "./denoise";
import { type SharePlan, shareVideoConstraints } from "./shareQuality";
import { VIDEO_CAP } from "./videoCap";
import { desktopBridge } from "@/lib/desktopBridge";

import { t } from "@/i18n";
/** Why the microphone that opened is the one that opened. */
export type InputChoice = "default" | "chosen" | "matched-by-name" | "missing";

let lastChoice: InputChoice = "default";

/** How the last microphone was picked, for the settings page to explain. */
export function lastInputChoice(): InputChoice {
  return lastChoice;
}

/**
 * What the microphone is opened with. The browser's own noise suppression is
 * on for the Standard mode only (or when RNNoise cannot run): Enhanced does
 * it in the page instead, and two of them in a row only blur a voice.
 */
function micConstraints(v: VoiceSettings, browserNoise: boolean): MediaTrackConstraints {
  return {
    echoCancellation: v.echoCancellation,
    noiseSuppression: browserNoise,
    autoGainControl: v.autoGain,
    channelCount: 1,
  };
}

/**
 * Opens the microphone the person chose.
 *
 * The device is asked for exactly, not as a preference: a preference is
 * silently ignored when the id no longer matches anything, and the call then
 * carries a different microphone than the one on screen. When the id has gone
 * stale the device is looked up again by name, which also covers the same
 * microphone on another computer, where ids never match.
 */
export async function getMic(v: VoiceSettings, opts: { browserNoise?: boolean } = {}): Promise<MediaStream> {
  const audio = micConstraints(v, opts.browserNoise ?? v.noiseMode === "standard");
  if (!v.inputDeviceId && !v.inputDeviceLabel) {
    lastChoice = "default";
    return navigator.mediaDevices.getUserMedia({ audio, video: false });
  }
  if (v.inputDeviceId) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { ...audio, deviceId: { exact: v.inputDeviceId } }, video: false });
      lastChoice = "chosen";
      return stream;
    } catch (err) {
      if (err instanceof DOMException && err.name !== "OverconstrainedError" && err.name !== "NotFoundError") throw err;
    }
  }
  if (v.inputDeviceLabel) {
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      const match = all.find((d) => d.kind === "audioinput" && d.label === v.inputDeviceLabel && d.deviceId && d.deviceId !== "default");
      if (match) {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: { ...audio, deviceId: { exact: match.deviceId } }, video: false });
        lastChoice = "matched-by-name";
        return stream;
      }
    } catch {
      /* fall through to the default below */
    }
  }
  lastChoice = "missing";
  return navigator.mediaDevices.getUserMedia({ audio, video: false });
}

export type CameraFacing = "user" | "environment";

/**
 * Opens a camera: the one chosen in settings while it can still be found (by
 * its id, then by its name, as the microphone is), otherwise the one facing
 * the way asked.
 */
export async function getCamera(facing: CameraFacing = "user", choice?: { id: string | null; label: string | null }): Promise<MediaStream> {
  // no camera comes near the cap, but what is asked for stays inside it all the same
  const base: MediaTrackConstraints = { width: { ideal: 1280, max: VIDEO_CAP.side }, height: { ideal: 720, max: VIDEO_CAP.side }, frameRate: { ideal: 30, max: VIDEO_CAP.fps } };
  const open = (deviceId: string) => navigator.mediaDevices.getUserMedia({ video: { ...base, deviceId: { exact: deviceId } }, audio: false });
  if (choice?.id) {
    try {
      return await open(choice.id);
    } catch (err) {
      if (err instanceof DOMException && err.name !== "OverconstrainedError" && err.name !== "NotFoundError") throw err;
    }
  }
  if (choice?.label) {
    const all = await navigator.mediaDevices.enumerateDevices().catch(() => [] as MediaDeviceInfo[]);
    const match = all.find((d) => d.kind === "videoinput" && d.label === choice.label && d.deviceId);
    if (match) {
      try {
        return await open(match.deviceId);
      } catch {
        /* fall back to the one facing the way asked */
      }
    }
  }
  return navigator.mediaDevices.getUserMedia({ video: { ...base, facingMode: { ideal: facing } }, audio: false });
}

/** Whether this device has more than one camera to switch between. */
export async function hasSeveralCameras(): Promise<boolean> {
  try {
    const list = await navigator.mediaDevices.enumerateDevices();
    return list.filter((d) => d.kind === "videoinput").length > 1;
  } catch {
    return false;
  }
}

/** Whether this browser can share a screen at all; phone browsers cannot. */
export const canShareScreen = !!desktopBridge()?.captureScreen || (typeof navigator !== "undefined" && typeof navigator.mediaDevices?.getDisplayMedia === "function");

let shareAudioAsked = false;

/** Whether the share being picked right now asked for sound; the desktop app's picker follows it. */
export function shareAudioRequested(): boolean {
  return shareAudioAsked;
}

/** Track.stop() alone cannot stop an OS recorder owned by the desktop shell. */
export function stopScreen(stream?: MediaStream): void {
  desktopBridge()?.stopScreen?.(stream);
  stream?.getTracks().forEach((track) => track.stop());
}

export async function getScreen(plan: SharePlan, withAudio = true): Promise<MediaStream> {
  const video = shareVideoConstraints(plan.res, plan.fps);
  // System audio where the browser allows it (Chrome asks per window or tab).
  // restrictOwnAudio leaves this app's own sound out of it, the call included:
  // without it everyone in the call hears themselves come back through the
  // share. Chrome honours it for system audio on Windows, and the desktop app
  // swaps its loopback for one that skips its own playback.
  const audio = withAudio ? { echoCancellation: false, noiseSuppression: false, autoGainControl: false, sampleRate: 48000, channelCount: 2, restrictOwnAudio: true, suppressLocalAudioPlayback: false } : false;
  // Window audio must stay scoped to that program, never silently broaden to
  // every program's sound. Engines without this hint ignore it.
  const opts = { video, audio, systemAudio: withAudio ? "include" : "exclude", windowAudio: withAudio ? "window" : "exclude", surfaceSwitching: "include", selfBrowserSurface: "exclude" } as DisplayMediaStreamOptions & { systemAudio: string; windowAudio: string; surfaceSwitching: string; selfBrowserSurface: string };
  shareAudioAsked = withAudio;
  const native = desktopBridge();
  // A denied native share stays denied. Never reopen browser/system capture
  // with different sound scope behind the person's selection.
  const stream = native?.captureScreen
    ? await native.captureScreen({ width: (video.width as ConstrainULongRange).max!, height: (video.height as ConstrainULongRange).max!, fps: plan.fps, audio: withAudio })
    : await navigator.mediaDevices.getDisplayMedia(opts);
  // How the encoder treats the picture. A share that keeps sharp ("detail",
  // or "text" at 15 fps, for slides) keeps its full size and gives up frames
  // when the link is short. A smooth one is for motion, and gets no hint at
  // all: "motion" made Chromium scale the capture down when the share started
  // and never back up (1080p reached people as 480x270,
  // tools/share_quality.py). Without it the share keeps its size, and
  // shareAdapt.ts steps it down and up by what the link can carry, so the
  // frames keep coming.
  const track = stream.getVideoTracks()[0];
  if (track && "contentHint" in track) track.contentHint = plan.smooth ? "" : plan.fps >= 30 ? "detail" : "text";
  return stream;
}

/** The devices of a kind the person can pick, leaving out Chromium's own "default" and "communications" aliases. */
export function realDevices(list: MediaDeviceInfo[]): MediaDeviceInfo[] {
  return list.filter((d) => d.deviceId && d.deviceId !== "default" && d.deviceId !== "communications");
}

/**
 * The name of the device the system uses when none is picked. Chromium lists
 * it as "Default - <name>"; Firefox and Safari put it first. Null while the
 * browser keeps device names hidden (nothing allowed yet).
 */
export function defaultDeviceName(list: MediaDeviceInfo[]): string | null {
  const alias = list.find((d) => d.deviceId === "default");
  if (alias) return alias.label.replace(/^default\s*-\s*/i, "").trim() || null;
  return realDevices(list)[0]?.label.trim() || null;
}

/** "Default (MacBook Pro Microphone)", or just "Default" when the name is not known. */
export function defaultDeviceLabel(list: MediaDeviceInfo[]): string {
  const name = defaultDeviceName(list);
  return name ? t("voice.media.defaultNamed", { name }) : t("voice.media.default");
}

export interface Devices {
  inputs: MediaDeviceInfo[];
  outputs: MediaDeviceInfo[];
  cameras: MediaDeviceInfo[];
}

export async function listDevices(): Promise<Devices> {
  const all = await navigator.mediaDevices.enumerateDevices();
  return {
    inputs: all.filter((d) => d.kind === "audioinput"),
    outputs: all.filter((d) => d.kind === "audiooutput"),
    cameras: all.filter((d) => d.kind === "videoinput"),
  };
}

/** RMS level meter over an audio stream; level() is 0..1. */
export class LevelMeter {
  private analyser: AnalyserNode;
  private source: MediaStreamAudioSourceNode;
  private buf: Float32Array<ArrayBuffer>;

  constructor(ctx: AudioContext, stream: MediaStream) {
    this.source = ctx.createMediaStreamSource(stream);
    this.analyser = ctx.createAnalyser();
    this.analyser.fftSize = 1024;
    this.analyser.smoothingTimeConstant = 0.4;
    this.source.connect(this.analyser);
    this.buf = new Float32Array(this.analyser.fftSize) as Float32Array<ArrayBuffer>;
  }

  level(): number {
    this.analyser.getFloatTimeDomainData(this.buf);
    let sum = 0;
    for (let i = 0; i < this.buf.length; i++) sum += this.buf[i]! * this.buf[i]!;
    return Math.min(1, Math.sqrt(sum / this.buf.length) * 4);
  }

  close(): void {
    try {
      this.source.disconnect();
      this.analyser.disconnect();
    } catch {
      /* already closed */
    }
  }
}

/** Sensitivity in dBFS (-100..0) to a 0..1 threshold comparable with LevelMeter.level(). */
export function sensitivityToThreshold(db: number): number {
  return Math.min(1, Math.max(0.005, Math.pow(10, db / 20) * 4));
}

/**
 * The shared audio engine, at 48 kHz where the browser will: what calls carry,
 * so their audio is not resampled on the way in and out, and the rate RNNoise
 * works at (denoise.ts).
 */
function newContext(): AudioContext {
  try {
    return new AudioContext({ sampleRate: DENOISE_RATE, latencyHint: "interactive" });
  } catch {
    return new AudioContext();
  }
}

let sharedKeeper: AudioKeeper | null = null;
/** Calls and microphone tests using the shared context. */
let audioHolds = 0;

/** How long a home-screen app keeps the context with nothing holding it: a call can take a while to begin after the tap that starts it. */
const UNHELD_MS = 10_000;
/** How long it stays after the last call or test ends, for the next one close behind. */
const IDLE_MS = 2000;

/**
 * One AudioContext for meters and playback. The rate is left to the device:
 * asking for 48 kHz makes Chrome resample some USB microphones, and a few of
 * them come out silent when it does. In a home-screen app on iOS it is let go
 * whenever no call or test holds it (see audioKeeper).
 */
export function audioContext(): AudioContext {
  if (!sharedKeeper) {
    const k = new AudioKeeper(newContext());
    sharedKeeper = k;
    if (typeof document !== "undefined") {
      const away = () => {
        if (isHomeScreenApp() && audioHolds === 0) k.release();
      };
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") away();
      });
      window.addEventListener("pagehide", away);
    }
  }
  void sharedKeeper.want();
  if (audioHolds === 0) sharedKeeper.releaseLater(() => audioHolds === 0, UNHELD_MS);
  return sharedKeeper.ac;
}

/** Keeps the shared context running for a call or a microphone test until the returned function is called. Calling it again does nothing. */
export function holdAudio(): () => void {
  audioHolds += 1;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    audioHolds -= 1;
    const k = sharedKeeper;
    if (audioHolds > 0 || !k) return;
    if (isHomeScreenApp() && typeof document !== "undefined" && document.visibilityState === "hidden") k.release();
    else k.releaseLater(() => audioHolds === 0, IDLE_MS);
  };
}

/**
 * Wakes the shared AudioContext during a tap or key press. iOS only lets a
 * suspended context start inside a gesture, and without a live microphone
 * (say its permission was refused) nothing else would ever get it running.
 */
export function resumeAudio(): void {
  if (sharedKeeper && sharedKeeper.ac.state !== "running") void sharedKeeper.want();
}

/**
 * The stream a call would send: the microphone through RNNoise when it is
 * given one (denoise.ts) and the input slider. At 100% with no denoiser there
 * is nothing to apply, so the microphone's own track goes out untouched
 * rather than through a graph that could go wrong.
 */
export function sentStream(ctx: AudioContext, mic: MediaStream, inputVolume: number, denoiser: AudioNode | null = null): { stream: MediaStream; gain: GainNode | null; source: MediaStreamAudioSourceNode | null } {
  if (inputVolume === 100 && !denoiser) return { stream: mic, gain: null, source: null };
  try {
    const src = ctx.createMediaStreamSource(mic);
    const gain = ctx.createGain();
    gain.gain.value = Math.max(0, inputVolume) / 100;
    const dest = ctx.createMediaStreamDestination();
    (denoiser ? src.connect(denoiser) : src).connect(gain).connect(dest);
    if (dest.stream.getAudioTracks().length === 0) {
      src.disconnect();
      return { stream: mic, gain: null, source: null };
    }
    return { stream: dest.stream, gain, source: src };
  } catch {
    return { stream: mic, gain: null, source: null };
  }
}

function choiceWords(c: InputChoice): string {
  switch (c) {
    case "chosen":
      return t("media.theOneYouPicked");
    case "matched-by-name":
      return t("media.foundAgainByName");
    case "missing":
      return t("media.notAvailableThisIsTheSystem");
    default:
      return t("media.systemDefault");
  }
}

/** What the microphone and the call are actually doing, in words. */
export function describeInput(ctx: AudioContext, mic: MediaStream, sent: MediaStream, inputVolume: number, rnnoise = false): string {
  const track = mic.getAudioTracks()[0];
  const st = track?.getSettings() ?? {};
  const out = sent.getAudioTracks()[0];
  const lines = [
    `device: ${track?.label || "unnamed"}`,
    `track: ${track?.readyState ?? "none"}, muted=${String(track?.muted ?? "?")}, enabled=${String(track?.enabled ?? "?")}`,
    `format: ${st.sampleRate ?? "?"} Hz, ${st.channelCount ?? "?"} channel(s)`,
    `processing: echo=${String(st.echoCancellation ?? "?")} noise=${String(st.noiseSuppression ?? "?")} gain=${String(st.autoGainControl ?? "?")} rnnoise=${String(rnnoise)}`,
    `audio engine: ${ctx.state}, ${Math.round(ctx.sampleRate)} Hz`,
    `input volume: ${inputVolume}% (${sent === mic ? t("media.sentStraightThrough") : t("media.throughTheSlider")})`,
    `chosen device: ${choiceWords(lastChoice)}`,
    `sent track: ${out?.readyState ?? "none"}`,
  ];
  return lines.join("\n");
}
