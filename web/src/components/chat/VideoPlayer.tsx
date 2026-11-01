import { Download, Maximize2, Minimize2, MoreVertical, Pause, PictureInPicture2, Play, Volume2, VolumeX } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Tooltip } from "@/components/common/Tooltip";
import { menuAnchor } from "@/components/common/ContextMenu";
import { enterElementFullscreen, enterVideoFullscreen, exitElementFullscreen, fullscreenElement, lockLandscape, onFullscreenChange, streamFullscreenWay } from "@/lib/fullscreen";
import { button, defineMenu } from "@/lib/contextMenus/registry";
import { fitVideo } from "./fitMedia";
import { mediaPrefs, saveMediaPrefs } from "./mediaPrefs";
import { followVideo } from "./soundtrack";
import s from "./VideoPlayer.module.css";

import { t as tr_ } from "@/i18n";
function clock(t: number): string {
  if (!Number.isFinite(t) || t < 0) t = 0;
  const total = Math.floor(t);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const sec = total % 60;
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return `${h > 0 ? `${h}:` : ""}${mm}:${String(sec).padStart(2, "0")}`;
}

const SPEEDS = [0.5, 1, 1.5, 2];
/** How long the controls stay up after a tap, while the video is playing. */
const CONTROLS_LINGER = 2800;

/** On a player too narrow for every control, speed, picture in picture and download wait in a menu. */
const videoMoreMenu = defineMenu<{ speed: number; setSpeed: (v: number) => void; pip?: () => void; download: () => void }>("videoMore", [
  [button("playbackSpeed", ({ speed, setSpeed }) => ({ id: "speed", label: tr_("chat.audioPlayer.playbackSpeed"), slider: { value: speed, min: 0.5, max: 2, step: 0.5, unit: "×", onChange: setSpeed } }))],
  [
    button("pictureInPicture", ({ pip }) => !!pip && { id: "pip", label: tr_("chat.videoPlayer.pictureInPicture"), onSelect: pip }),
    button("downloadVideo", ({ download }) => ({ id: "download", label: tr_("chat.attachments.download"), onSelect: download })),
  ],
]);

/**
 * Whether anything can go full screen here. iPhone Safari has no full screen
 * for elements, only its own player for a video element.
 */
function canFullscreen(): boolean {
  return typeof document !== "undefined" && streamFullscreenWay() !== "page";
}

/** Whether a video can float in picture in picture here, the standard way or Safari's. */
function canPip(): boolean {
  if (typeof document === "undefined") return false;
  return !!(document as Document & { pictureInPictureEnabled?: boolean }).pictureInPictureEnabled || (typeof HTMLVideoElement !== "undefined" && "webkitSetPresentationMode" in HTMLVideoElement.prototype);
}

/**
 * Opens the system player (iPhone). iOS refuses it for a video that has not
 * loaded, and loads nothing before playing, so such a video is started inside
 * the tap, which also lets the player open as soon as the video can show.
 */
function openSystemPlayer(el: HTMLVideoElement, onFail: (err: unknown) => void): void {
  if (typeof (el as HTMLVideoElement & { webkitEnterFullscreen?: unknown }).webkitEnterFullscreen !== "function") return;
  if (enterVideoFullscreen(el)) return;
  // paused again before it could open: the viewer changed their mind
  const open = () => {
    if (!el.paused) enterVideoFullscreen(el);
  };
  if (el.readyState >= HTMLMediaElement.HAVE_METADATA) {
    void el.play().then(open, onFail);
    return;
  }
  el.addEventListener("loadedmetadata", open, { once: true });
  void el.play().catch((err: unknown) => {
    el.removeEventListener("loadedmetadata", open);
    onFail(err);
  });
}

/**
 * A play() cut short by pause() rejects too, and so does one the browser
 * holds back until a tap; neither says anything about whether the file plays.
 * Told apart by name: a video in a frame rejects with its frame's DOMException.
 */
function playFailed(err: unknown): boolean {
  const name = (err as { name?: unknown } | null)?.name;
  return name !== "AbortError" && name !== "NotAllowedError";
}

/** Touch screens have no hover, so the controls need a tap to appear. */
function coarsePointer(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(hover: none)").matches;
}

interface Props {
  /** The video's address; left out for a `frame`. */
  src?: string | undefined;
  /**
   * A page holding the video instead, which the player works from outside:
   * an encrypted video playing while it downloads (lib/videoStream.ts).
   */
  frame?: string | undefined;
  name: string;
  /** Natural size, when the sender recorded it. */
  width?: number | undefined;
  height?: number | undefined;
  poster?: string | undefined;
  /** Hidden until the viewer chooses to reveal it. */
  spoiler?: boolean;
  /** The video's sound, when it is a file of its own (a post from Reddit); played in step with the picture. */
  audio?: string | undefined;
  /** As wide as where it is put, a note's line, rather than a message's frame. */
  fill?: boolean | undefined;
  /** How the download control saves it, when what plays is not the file itself (a stream). */
  onDownload?: (() => void) | undefined;
  /** Shown where the time goes until the video's length is known: a big one loads nothing before it is played. */
  sizeHint?: string | undefined;
  /**
   * Told when the video cannot play. True (or a promise of it) means the
   * caller has another way to play it and has taken over; false, that the
   * player should say it cannot.
   */
  onFail?: (() => boolean | Promise<boolean>) | undefined;
}

/** The app's own video player: themed controls, keyboard, PiP and a download. */
export function VideoPlayer({ src, frame, name, width, height, poster, spoiler = false, audio, fill = false, onDownload, onFail, sizeHint }: Props) {
  const video = useRef<HTMLVideoElement | null>(null);
  /** The video element in use, once there is one: the player's own, or the one in its frame. */
  const [media, setMedia] = useState<HTMLVideoElement | null>(null);
  const holder = useRef<HTMLIFrameElement>(null);
  const ownVideo = useCallback((el: HTMLVideoElement | null) => {
    video.current = el;
    setMedia(el);
  }, []);
  const sound = useRef<HTMLAudioElement>(null);
  useEffect(() => {
    if (!audio || !media || !sound.current) return;
    return followVideo(media, sound.current);
  }, [audio, media]);
  const wrap = useRef<HTMLDivElement>(null);
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState(0);
  // as the last clip was left, on this device
  const [prefs] = useState(mediaPrefs);
  const [volume, setVolume] = useState(prefs.volume);
  const [muted, setMutedState] = useState(prefs.muted);
  const [speed, setSpeedState] = useState(prefs.speed);
  const setMuted = useCallback((next: boolean | ((v: boolean) => boolean)) => {
    setMutedState((v) => {
      const m = typeof next === "function" ? next(v) : next;
      saveMediaPrefs({ muted: m });
      return m;
    });
  }, []);
  const setSpeed = useCallback((v: number) => {
    setSpeedState(v);
    saveMediaPrefs({ speed: v });
  }, []);
  const [full, setFull] = useState(false);
  const [failed, setFailed] = useState(false);
  const fail = useCallback(() => {
    if (!onFail) {
      setFailed(true);
      return;
    }
    void Promise.resolve(onFail()).then((handled) => {
      if (!handled) setFailed(true);
    });
  }, [onFail]);
  /** The video's own size, for a frame sent without one: a frame has none of its own to fall back on. */
  const [natural, setNatural] = useState<[number, number] | null>(null);
  const [revealed, setRevealed] = useState(!spoiler);
  const [fullOk] = useState(canFullscreen);
  const [pipOk] = useState(canPip);
  // On a touch screen the controls would otherwise sit over the picture the
  // whole time, so they appear on a tap and fade out again while it plays.
  const [ui, setUi] = useState(true);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const armHide = useCallback((playing: boolean) => {
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = null;
    if (playing) hideTimer.current = setTimeout(() => setUi(false), CONTROLS_LINGER);
  }, []);

  const reveal = useCallback(() => {
    setUi(true);
    armHide(!!video.current && !video.current.paused);
  }, [armHide]);

  useEffect(() => () => {
    if (hideTimer.current) clearTimeout(hideTimer.current);
  }, []);

  const toggle = useCallback(() => {
    const el = video.current;
    if (!el) return;
    if (el.paused) void el.play().catch((err: unknown) => playFailed(err) && fail());
    else el.pause();
  }, [fail]);

  /** A first tap on a touch screen brings the controls up rather than pausing. */
  const onSurface = useCallback(() => {
    if (!revealed) {
      setRevealed(true);
      return;
    }
    if (coarsePointer() && !ui) {
      reveal();
      return;
    }
    toggle();
  }, [revealed, ui, reveal, toggle]);

  /** The frame's page is in: its video is the one to work. */
  const onFrame = useCallback(() => {
    const el = holder.current?.contentDocument?.querySelector("video") ?? null;
    video.current = el;
    setMedia(el);
    // what loaded is not the worker's page (it was stopped meanwhile)
    if (!el) fail();
  }, [fail]);

  useEffect(() => {
    const el = media;
    if (!el) return;
    const onTime = () => {
      setTime(el.currentTime);
      const b = el.buffered;
      setBuffered(b.length ? b.end(b.length - 1) : 0);
    };
    const onMeta = () => {
      setDuration(el.duration);
      if (el.videoWidth && el.videoHeight) setNatural([el.videoWidth, el.videoHeight]);
    };
    const onPlay = () => {
      setPlaying(true);
      armHide(true);
    };
    const onPause = () => {
      setPlaying(false);
      setUi(true);
      armHide(false);
    };
    const onError = () => fail();
    el.addEventListener("timeupdate", onTime);
    el.addEventListener("progress", onTime);
    el.addEventListener("loadedmetadata", onMeta);
    el.addEventListener("durationchange", onMeta);
    el.addEventListener("play", onPlay);
    el.addEventListener("pause", onPause);
    el.addEventListener("error", onError);
    // a video in a frame may have read its length, or failed, before the frame said it had loaded
    if (el.readyState >= HTMLMediaElement.HAVE_METADATA) onMeta();
    onTime();
    if (el.error) onError();
    return () => {
      el.removeEventListener("timeupdate", onTime);
      el.removeEventListener("progress", onTime);
      el.removeEventListener("loadedmetadata", onMeta);
      el.removeEventListener("durationchange", onMeta);
      el.removeEventListener("play", onPlay);
      el.removeEventListener("pause", onPause);
      el.removeEventListener("error", onError);
    };
  }, [media, armHide, fail]);

  useEffect(() => onFullscreenChange(() => setFull(!!wrap.current && fullscreenElement() === wrap.current)), []);

  const seek = (t: number) => {
    const el = video.current;
    if (!el || !Number.isFinite(duration)) return;
    el.currentTime = Math.max(0, Math.min(duration || 0, t));
    setTime(el.currentTime);
  };

  /**
   * The player's own keys, on the player and its seek track only. On a
   * button Space is that button's — it used to play the clip instead of
   * muting it — and the arrows on the volume slider move the volume.
   */
  const onKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const el = video.current;
    const target = e.target as HTMLElement;
    if (!el || (target !== e.currentTarget && target.getAttribute("role") !== "slider")) return;
    reveal();
    switch (e.key) {
      case " ":
      case "k":
        e.preventDefault();
        // a spoiler is uncovered first; its sound does not start behind the blur
        if (!revealed) setRevealed(true);
        else toggle();
        break;
      case "ArrowRight":
        e.preventDefault();
        seek(el.currentTime + 5);
        break;
      case "ArrowLeft":
        e.preventDefault();
        seek(el.currentTime - 5);
        break;
      case "m":
        e.preventDefault();
        setMuted((v) => !v);
        break;
      case "f":
        e.preventDefault();
        toggleFull();
        break;
      default:
        break;
    }
  };

  const toggleFull = () => {
    const el = video.current;
    if (fullscreenElement()) {
      void exitElementFullscreen().catch(() => {});
      return;
    }
    const failed = (err: unknown) => playFailed(err) && fail();
    if (streamFullscreenWay() === "element" && wrap.current) {
      // a phone turns sideways for a wide video, where the browser allows it (not iOS)
      void enterElementFullscreen(wrap.current)
        .then(() => (el && el.videoWidth > el.videoHeight ? lockLandscape() : false))
        .catch(() => el && openSystemPlayer(el, failed));
      return;
    }
    if (el) openSystemPlayer(el, failed);
  };
  const pip = () => {
    const el = video.current as (HTMLVideoElement & { webkitSetPresentationMode?: (mode: string) => void }) | null;
    if (!el) return;
    // a video in a frame is in picture in picture in its frame's document
    const doc = el.ownerDocument;
    if (doc.pictureInPictureElement) {
      void doc.exitPictureInPicture().catch(() => {});
      return;
    }
    // inside the tap: Safari opens nothing from a promise that resolves later
    if (typeof el.requestPictureInPicture === "function") void el.requestPictureInPicture().catch(() => el.webkitSetPresentationMode?.("picture-in-picture"));
    else el.webkitSetPresentationMode?.("picture-in-picture");
  };
  const download = () => {
    if (onDownload) {
      onDownload();
      return;
    }
    if (!src) return;
    const a = document.createElement("a");
    a.href = src;
    a.download = name;
    a.click();
  };
  /** On a player too narrow for every control, speed, picture in picture and download wait in a menu. */
  const more = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    const anchor = menuAnchor(e.currentTarget);
    // over the bar it sits on, lined up with the button's end, rather than a guessed 200px to the left
    videoMoreMenu.open({ speed, setSpeed, ...(pipOk ? { pip } : {}), download }, { x: anchor.left, y: anchor.top, anchor, above: true });
    reveal();
  };

  useEffect(() => {
    const el = media;
    if (el) {
      el.volume = volume;
      el.muted = muted;
      el.playbackRate = speed;
    }
  }, [media, volume, muted, speed]);

  const pct = duration > 0 ? (time / duration) * 100 : 0;
  const bufPct = duration > 0 ? (buffered / duration) * 100 : 0;
  // full screen sizes the player itself, so the box is only set inline
  const shape: [number, number] | null = width && height ? [width, height] : frame ? (natural ?? [16, 9]) : null;
  const box = shape && !full ? fitVideo(shape[0], shape[1]) : null;
  const size = box ? { width: box.width, aspectRatio: `${box.width} / ${box.height}` } : undefined;
  /** Seeks to where a pointer is along the track; a drag keeps seeking while it moves. */
  const scrub = (e: React.PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    seek(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * duration);
  };
  return (
    <div
      ref={wrap}
      className={s.player}
      style={fill ? (size ? { aspectRatio: size.aspectRatio } : undefined) : size}
      data-full={full}
      data-fill={fill || undefined}
      data-sized={!!size}
      data-spoiler={!revealed}
      data-ui={ui}
      tabIndex={0}
      onKeyDown={onKey}
      onPointerMove={(e) => e.pointerType === "mouse" && reveal()}
      onPointerLeave={(e) => {
        if (e.pointerType === "mouse" && video.current && !video.current.paused) setUi(false);
      }}
      data-testid="video-player"
    >
      {frame ? (
        <>
          <iframe ref={holder} className={s.video} src={frame} title={name} tabIndex={-1} onLoad={onFrame} data-testid="video-frame" />
          {/* the frame takes no clicks of its own: they land here, as they would on the video */}
          <div className={s.surface} onClick={onSurface} onDoubleClick={toggleFull} />
        </>
      ) : (
        <video ref={ownVideo} className={s.video} src={src} poster={poster} playsInline preload="metadata" onClick={onSurface} onDoubleClick={toggleFull} />
      )}
      {audio ? <audio ref={sound} src={audio} preload="metadata" hidden /> : null}
      {!revealed ? (
        <button type="button" className={s.spoiler} aria-label={tr_("render.spoilerHidden")} onClick={() => setRevealed(true)}><span>{tr_("composer.composer.spoiler")}</span></button>
      ) : null}
      {failed ? (
        <div className={s.failed}>
          <span>{tr_("chat.videoPlayer.thisBrowserCannotPlayThisVideo")}</span>
          {onDownload ? (
            <button type="button" className={s.failedDownload} onClick={onDownload}>{tr_("chat.videoPlayer.downloadIt")}</button>
          ) : (
            <a className={s.failedDownload} href={src} download={name}>{tr_("chat.videoPlayer.downloadIt")}</a>
          )}
        </div>
      ) : null}
      {!playing && revealed && !failed ? (
        <button className={s.bigPlay} aria-label={tr_("chat.videoPlayer.play")} onClick={toggle}><Play size={28} fill="currentColor" /></button>
      ) : null}
      {/* data-no-swipe: dragging the seek bar or volume must not pull a drawer open on a phone */}
      <div className={s.bar} data-no-swipe onClick={(e) => e.stopPropagation()} onPointerDown={reveal}>
        <div
          className={s.track}
          role="slider"
          aria-label={tr_("chat.audioPlayer.seek")}
          aria-valuemin={0}
          aria-valuemax={Math.round(duration) || 0}
          aria-valuenow={Math.round(time)}
          tabIndex={0}
          onPointerDown={(e) => {
            e.currentTarget.setPointerCapture(e.pointerId);
            scrub(e);
          }}
          onPointerMove={(e) => {
            if (!e.currentTarget.hasPointerCapture(e.pointerId)) return;
            reveal();
            scrub(e);
          }}
        >
          <span className={s.buffered} style={{ width: `${bufPct}%` }} />
          <span className={s.progress} style={{ width: `${pct}%` }} />
          <span className={s.knob} style={{ left: `${pct}%` }} />
        </div>
        <div className={s.controls}>
          <button className={s.ctl} aria-label={playing ? tr_("chat.media.pause") : tr_("chat.videoPlayer.play")} onClick={toggle}>{playing ? <Pause size={18} /> : <Play size={18} />}</button>
          {sizeHint && !(duration > 0) ? (
            <span className={s.time}>{sizeHint}</span>
          ) : (
            <span className={s.time}>{clock(time)}<span className={s.total}> / {clock(duration)}</span></span>
          )}
          <span className={s.spacer} />
          <div className={s.volumeWrap}>
            <button className={s.ctl} aria-label={muted ? tr_("chat.media.unmute") : tr_("chat.media.mute")} aria-pressed={muted} onClick={() => setMuted((v) => !v)}>{muted || volume === 0 ? <VolumeX size={18} /> : <Volume2 size={18} />}</button>
            <input className={s.volume} type="range" min={0} max={1} step={0.01} value={muted ? 0 : volume} aria-label={tr_("chat.audioPlayer.volume")} onChange={(e) => { const v = Number(e.target.value); setVolume(v); setMuted(false); saveMediaPrefs({ volume: v }); }} />
          </div>
          <button className={[s.speed, s.wideOnly].join(" ")} aria-label={tr_("chat.audioPlayer.playbackSpeed")} onClick={() => setSpeed(SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length]!)}>{speed}×</button>
          {pipOk ? (
            <Tooltip text={tr_("chat.videoPlayer.pictureInPicture")} side="top">
              <button className={[s.ctl, s.wideOnly].join(" ")} aria-label={tr_("chat.videoPlayer.pictureInPicture")} onClick={pip}><PictureInPicture2 size={18} /></button>
            </Tooltip>
          ) : null}
          <Tooltip text={tr_("chat.attachments.download")} side="top">
            {onDownload ? (
              <button type="button" className={[s.ctl, s.wideOnly].join(" ")} aria-label={tr_("chat.videoPlayer.downloadVideo")} onClick={(e) => { e.stopPropagation(); onDownload(); }}><Download size={18} /></button>
            ) : (
              <a className={[s.ctl, s.wideOnly].join(" ")} href={src} download={name} aria-label={tr_("chat.videoPlayer.downloadVideo")} onClick={(e) => e.stopPropagation()}><Download size={18} /></a>
            )}
          </Tooltip>
          <button className={[s.ctl, s.narrowOnly].join(" ")} aria-label={tr_("chat.chat.more")} onClick={more} data-testid="video-more"><MoreVertical size={18} /></button>
          {fullOk ? (
            <Tooltip text={full ? tr_("chat.videoPlayer.exitFullScreen") : tr_("chat.videoPlayer.fullScreen")} side="top">
              <button className={s.ctl} aria-label={full ? tr_("chat.videoPlayer.exitFullScreen") : tr_("chat.videoPlayer.fullScreen")} onClick={toggleFull} data-testid="video-fullscreen">{full ? <Minimize2 size={18} /> : <Maximize2 size={18} />}</button>
            </Tooltip>
          ) : null}
        </div>
      </div>
    </div>
  );
}
