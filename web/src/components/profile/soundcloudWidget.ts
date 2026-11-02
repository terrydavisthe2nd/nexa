/**
 * SoundCloud's own player, kept out of sight and driven from Nexa's music
 * card: its widget takes play, pause, seek and volume as messages and tells
 * how far it has got (https://developers.soundcloud.com/docs/api/html5-widget).
 * The widget is served through this server (internal/api/music_players.go),
 * sandboxed, so SoundCloud never learns who is listening. One plays at a
 * time, as previews do; previews.ts decides when.
 */

export interface WidgetEvents {
  loading(): void;
  playing(): void;
  paused(): void;
  progress(at: number, seconds: number): void;
  ended(): void;
  failed(): void;
  /** The browser would not let it start without a tap inside SoundCloud's player (Safari). */
  blocked(): void;
}

let frame: HTMLIFrameElement | null = null;
let events: WidgetEvents | null = null;
let duration = 0;
let ready = false;
let pending: unknown[] = [];
let readyTimer: ReturnType<typeof setTimeout> | null = null;
let startTimer: ReturnType<typeof setTimeout> | null = null;
let started = false;

function send(method: string, value?: unknown) {
  const msg = value === undefined ? { method } : { method, value };
  if (!ready) {
    pending.push(msg);
    return;
  }
  frame?.contentWindow?.postMessage(JSON.stringify(msg), "*");
}

function heard(ev: MessageEvent) {
  if (!frame || ev.source !== frame.contentWindow || typeof ev.data !== "string") return;
  let data: { method?: unknown; value?: unknown };
  try {
    data = JSON.parse(ev.data) as typeof data;
  } catch {
    return;
  }
  const on = events;
  if (!on) return;
  const value = (data.value ?? {}) as { currentPosition?: unknown; relativePosition?: unknown };
  switch (data.method) {
    case "ready": {
      if (ready) return;
      ready = true;
      if (readyTimer) clearTimeout(readyTimer);
      for (const name of ["play", "pause", "finish", "playProgress", "error"]) frame.contentWindow?.postMessage(JSON.stringify({ method: "addEventListener", value: name }), "*");
      frame.contentWindow?.postMessage(JSON.stringify({ method: "getDuration" }), "*");
      for (const msg of pending) frame.contentWindow?.postMessage(JSON.stringify(msg), "*");
      pending = [];
      // Safari lets a frame play only after a tap inside it: a play that never starts is that
      startTimer = setTimeout(() => {
        if (!started && events === on) on.blocked();
      }, 4000);
      return;
    }
    case "getDuration":
      if (typeof data.value === "number" && data.value > 0) duration = data.value / 1000;
      return;
    case "play":
      on.playing();
      return;
    case "pause":
      on.paused();
      return;
    case "finish":
      on.ended();
      return;
    case "error":
      on.failed();
      return;
    case "playProgress": {
      const cur = typeof value.currentPosition === "number" ? value.currentPosition / 1000 : 0;
      // only time moving counts as started: Safari says "play" for a play it then refuses
      if (cur > 0.2) started = true;
      const rel = typeof value.relativePosition === "number" ? value.relativePosition : 0;
      if (!duration && rel > 0) duration = cur / rel;
      on.progress(Math.min(1, Math.max(0, rel)), duration);
      return;
    }
  }
}

/** Starts a SoundCloud widget (its address through this server) at a volume from 0 to 1. */
export function widgetStart(src: string, volume: number, on: WidgetEvents): void {
  widgetStop();
  events = on;
  duration = 0;
  ready = false;
  started = false;
  pending = [{ method: "setVolume", value: Math.round(volume * 100) }, { method: "play" }];
  window.addEventListener("message", heard);
  const el = document.createElement("iframe");
  el.src = src;
  el.title = "SoundCloud";
  el.setAttribute("sandbox", "allow-scripts allow-presentation");
  el.setAttribute("allow", "autoplay; encrypted-media");
  el.setAttribute("referrerpolicy", "no-referrer");
  el.setAttribute("aria-hidden", "true");
  el.tabIndex = -1;
  el.dataset["testid"] = "soundcloud-widget";
  // out of sight but laid out, since a browser may hold back a frame it never draws
  Object.assign(el.style, { position: "fixed", left: "-10000px", top: "0", width: "320px", height: "166px", border: "0", opacity: "0", pointerEvents: "none" });
  document.body.appendChild(el);
  frame = el;
  on.loading();
  // a widget that never says it is ready (blocked, or SoundCloud down) is a failure, not a spinner forever
  readyTimer = setTimeout(() => {
    if (!ready && events === on) {
      on.failed();
    }
  }, 15000);
}

export function widgetPlay(): void {
  send("play");
}

export function widgetPause(): void {
  send("pause");
}

export function widgetSeek(at: number): void {
  if (duration > 0) send("seekTo", Math.round(at * duration * 1000));
}

export function widgetVolume(volume: number): void {
  send("setVolume", Math.round(volume * 100));
}

export function widgetActive(): boolean {
  return frame !== null;
}

export function widgetStop(): void {
  if (readyTimer) clearTimeout(readyTimer);
  if (startTimer) clearTimeout(startTimer);
  startTimer = null;
  readyTimer = null;
  window.removeEventListener("message", heard);
  frame?.remove();
  frame = null;
  events = null;
  ready = false;
  pending = [];
  duration = 0;
}
