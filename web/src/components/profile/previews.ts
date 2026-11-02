/**
 * Song previews: thirty seconds of a song on someone's Favourite Songs,
 * played through this server (handleMusicPreview in internal/api/widgets.go)
 * so the listener never contacts Deezer. One plays at a time anywhere in the
 * app, at a volume of their own that every list's slider sets (starting from
 * the one voice notes and videos play at), and the list that started one
 * stops it as it goes away.
 */
import { create } from "zustand";
import { api } from "@/api/client";
import { mediaPrefs } from "@/components/chat/mediaPrefs";
import { storage } from "@/lib/storage";
import { t } from "@/i18n";
import { musicArtUrl, type WidgetMusic } from "@/lib/widgets";
import { useUI } from "@/stores/ui";
import { widgetActive, widgetPause, widgetPlay, widgetSeek, widgetStart, widgetStop, widgetVolume } from "./soundcloudWidget";

export interface PreviewState {
  /** The song playing, or about to. */
  id: string | null;
  /** Which list started it. */
  owner: string | null;
  state: "loading" | "playing" | "paused";
  /** How far through it is, from 0 to 1. */
  progress: number;
  /** How long it is, in seconds, once known (0 before). */
  duration: number;
  /** A widget's key the browser would not start out of sight: its card shows the service's own player. */
  blocked: string | null;
  /** How loud previews play, from 0 to 1, and whether they are muted: this device's, kept. */
  volume: number;
  muted: boolean;
}

const VOLUME_KEY = "ec:songVolume";

/** The volume kept on this device, or the one videos play at until one is set. */
function keptVolume(): { volume: number; muted: boolean } {
  try {
    const raw = JSON.parse(storage.get(VOLUME_KEY) ?? "null") as { volume?: unknown; muted?: unknown } | null;
    if (raw && typeof raw.volume === "number" && Number.isFinite(raw.volume)) return { volume: Math.min(1, Math.max(0, raw.volume)), muted: raw.muted === true };
  } catch {
    /* kept wrong: start from the videos' */
  }
  // a muted video says nothing about songs: pressing play on one means wanting to hear it
  const prefs = mediaPrefs();
  return { volume: prefs.volume > 0 ? prefs.volume : 1, muted: false };
}

const IDLE = { id: null, owner: null, state: "paused", progress: 0, duration: 0 } as const;

export const usePreview = create<PreviewState>(() => ({ ...IDLE, blocked: null, ...keptVolume() }));

let player: HTMLAudioElement | null = null;
/** The preview the audio element is playing, so its late events never speak for a widget's. */
let audioKey: string | null = null;

const idle = () => usePreview.setState({ ...IDLE });

function audio(): HTMLAudioElement {
  if (player) return player;
  const el = new Audio();
  el.preload = "auto";
  const ours = () => audioKey !== null && usePreview.getState().id === audioKey;
  el.addEventListener("waiting", () => ours() && usePreview.setState({ state: "loading" }));
  el.addEventListener("playing", () => ours() && usePreview.setState({ state: "playing" }));
  // the pause of the song before is told after the next has been asked to play: that one is not paused
  el.addEventListener("pause", () => ours() && el.paused && !el.ended && usePreview.setState({ state: "paused" }));
  el.addEventListener("timeupdate", () => {
    if (ours() && el.duration > 0 && Number.isFinite(el.duration)) usePreview.setState({ progress: Math.min(1, el.currentTime / el.duration), duration: el.duration });
  });
  el.addEventListener("ended", () => ours() && idle());
  el.addEventListener("error", () => {
    if (!ours() || !el.error) return;
    // a source with nothing to play is a song Deezer has no preview of
    useUI.getState().toast(el.error.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED ? t("widgets.music.noPreview") : t("widgets.music.previewFailed"), "error");
    idle();
  });
  player = el;
  return el;
}

function play(el: HTMLAudioElement) {
  void el.play().catch((err: unknown) => {
    // a play() cut short by a pause, or by the next song, rejects too; the error event speaks for the rest
    if (err instanceof DOMException && err.name === "AbortError") return;
    if (err instanceof DOMException && err.name === "NotAllowedError") idle();
  });
}

/** A preview to play: what it is called while it plays, and where its sound is. */
export interface PreviewTrack {
  /** Tells it apart from every other preview: a Deezer id, or a music card's track. */
  key: string;
  src: string;
  name: string;
  by?: string | undefined;
  /** A cover for a phone's lock screen. */
  art?: string | undefined;
  /** Played by SoundCloud's own widget, out of sight, rather than as a file: src is then its address. */
  widget?: boolean | undefined;
}

/** Plays a song's preview, or pauses it (and plays it on) when it is the one playing. */
export function togglePreview(song: WidgetMusic, owner: string): void {
  if (!song.id) return;
  playPreview(
    {
      key: song.id,
      src: `${api.baseUrl}/media/music/preview/${song.id}`,
      name: song.name,
      by: song.by,
      art: song.art ? musicArtUrl("cover", song.art, "l") : undefined,
    },
    owner,
  );
}

/** Plays any preview, or pauses it (and plays it on) when it is the one playing: a widget's song or a music card's. */
export function playPreview(track: PreviewTrack, owner: string): void {
  const current = usePreview.getState();
  if (current.id === track.key) {
    if (widgetActive()) {
      if (current.state === "playing") widgetPause();
      else widgetPlay();
      return;
    }
    const el = audio();
    if (el.paused) play(el);
    else el.pause();
    return;
  }
  const { volume, muted } = current;
  usePreview.setState({ id: track.key, owner, state: "loading", progress: 0, duration: 0 });
  if (track.widget) {
    silenceAudio();
    const ours = () => usePreview.getState().id === track.key;
    widgetStart(track.src, muted ? 0 : volume, {
      loading: () => ours() && usePreview.setState({ state: "loading" }),
      playing: () => ours() && usePreview.setState({ state: "playing" }),
      paused: () => ours() && usePreview.setState({ state: "paused" }),
      progress: (progress, duration) => ours() && usePreview.setState(duration > 0 ? { progress, duration } : { progress }),
      ended: () => {
        if (!ours()) return;
        widgetStop();
        idle();
      },
      blocked: () => {
        if (!ours()) return;
        widgetStop();
        idle();
        usePreview.setState({ blocked: track.key });
      },
      failed: () => {
        if (!ours()) return;
        widgetStop();
        useUI.getState().toast(t("chat.embeds.music.playerFailed"), "error");
        idle();
      },
    });
  } else {
    widgetStop();
    const el = audio();
    el.volume = volume;
    el.muted = muted;
    el.src = track.src;
    audioKey = track.key;
    play(el);
  }
  // what a phone's lock screen and a headset's buttons show
  if (typeof navigator !== "undefined" && "mediaSession" in navigator && typeof MediaMetadata !== "undefined") {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.name,
      artist: track.by ?? "",
      artwork: track.art ? [{ src: track.art, sizes: "400x400", type: "image/jpeg" }] : [],
    });
  }
}

/** Moves the playing preview to a point from 0 to 1, when it is the one with this key. */
export function seekPreview(key: string, at: number): void {
  if (usePreview.getState().id === key && widgetActive()) {
    const f = Math.min(1, Math.max(0, at));
    widgetSeek(f);
    usePreview.setState({ progress: f });
    return;
  }
  if (!player || usePreview.getState().id !== key || !(player.duration > 0) || !Number.isFinite(player.duration)) return;
  const f = Math.min(1, Math.max(0, at));
  player.currentTime = f * player.duration;
  usePreview.setState({ progress: f });
}

/** Stops the preview playing, when the list asking is the one that started it. */
export function stopPreview(owner: string): void {
  if (usePreview.getState().owner !== owner) return;
  idle();
  widgetStop();
  silenceAudio();
}

/** Empties the audio element, so a widget taking over leaves no file playing. */
function silenceAudio(): void {
  audioKey = null;
  if (!player) return;
  player.pause();
  player.removeAttribute("src");
  player.load();
}

/** Stops whatever preview is playing: a service's own player opening takes over. */
export function stopAnyPreview(): void {
  const { owner } = usePreview.getState();
  if (owner) stopPreview(owner);
}

/** Sets how loud previews play, on the one playing now too, and keeps it on this device. */
export function setPreviewVolume(volume: number, muted: boolean): void {
  const v = Math.min(1, Math.max(0, volume));
  usePreview.setState({ volume: v, muted });
  if (player) {
    player.volume = v;
    player.muted = muted;
  }
  if (widgetActive()) widgetVolume(muted ? 0 : v);
  storage.set(VOLUME_KEY, JSON.stringify({ volume: v, muted }));
}

let volumeWorks: boolean | null = null;

/**
 * Whether this browser lets a page set how loud it plays. An iPhone's never
 * does (its volume is the phone's own buttons); muting still works there.
 */
export function volumeAdjustable(): boolean {
  if (volumeWorks !== null) return volumeWorks;
  try {
    const probe = new Audio();
    probe.volume = 0.5;
    volumeWorks = Math.abs(probe.volume - 0.5) < 0.01;
  } catch {
    volumeWorks = false;
  }
  return volumeWorks;
}
