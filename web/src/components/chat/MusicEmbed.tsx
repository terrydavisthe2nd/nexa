import { LoaderCircle, Pause, Play } from "lucide-react";
import { useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { api } from "@/api/client";
import type { MusicCard, MusicTrack, UnfurlResult } from "@/api/types";
import { t } from "@/i18n";
import { useMediaSrc } from "@/lib/mediaLinks";
import { formatDuration } from "@/lib/time";
import { useUI } from "@/stores/ui";
import { playPreview, seekPreview, stopAnyPreview, stopPreview, usePreview } from "@/components/profile/previews";
import { SongVolume } from "@/components/profile/SongVolume";
import s from "./MusicEmbed.module.css";

/**
 * A link to music (internal/unfurl/music.go), drawn as music: the cover, what
 * it is, who made it, and a few of its tracks. Deezer and Apple Music let
 * anyone hear thirty seconds of a song, so those play here in the app's own
 * player, one at a time with the songs on profiles. Spotify and SoundCloud
 * ask to be heard in their own player, so pressing play opens that,
 * through this server (internal/api/music_players.go), where neither learns
 * who is listening.
 */

const SITES: Record<string, { name: string; color: string }> = {
  spotify: { name: "Spotify", color: "#1ed760" },
  apple: { name: "Apple Music", color: "#fa2d48" },
  deezer: { name: "Deezer", color: "#a238ff" },
  soundcloud: { name: "SoundCloud", color: "#ff5500" },
};

const KINDS = new Set(["track", "album", "playlist", "artist", "episode", "show"]);

/** Whether a preview's music card is one this app can draw. */
export function isMusicCard(m: unknown): m is MusicCard {
  if (!m || typeof m !== "object") return false;
  const c = m as MusicCard;
  return typeof c.platform === "string" && c.platform in SITES && typeof c.title === "string" && !!c.title && typeof c.kind === "string" && KINDS.has(c.kind);
}

/** Where a preview's sound may come from: a proxy address, or Deezer's previews through this server. */
function previewSrc(src: string | undefined, signed: string | null | undefined): string | null {
  if (!src) return null;
  if (/^\/media\/music\/preview\/\d{1,15}$/.test(src)) return api.baseUrl + src;
  return signed ?? null;
}

/** "14 songs", for an album or playlist. */
function countLine(m: MusicCard): string | null {
  if (m.count && m.count > 0) return t("chat.embeds.music.songs", { count: m.count });
  return null;
}

function Explicit() {
  return (
    <span className={s.explicit} title={t("chat.embeds.music.explicit")} aria-label={t("chat.embeds.music.explicit")}>
      E
    </span>
  );
}

/** The service's own player, opened through this server. */
function PlayerFrame({ m }: { m: MusicCard }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const kind = m.kind;
  useEffect(() => {
    let alive = true;
    api
      .request<{ url: string }>("GET", `/music/player/${encodeURIComponent(m.player ?? "")}/${encodeURIComponent(kind)}/${encodeURIComponent(m.id)}`)
      .then((r) => alive && (r?.url.startsWith("/sp/") || r?.url.startsWith("/sc/")) && setSrc(api.baseUrl + r.url))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [m.player, kind, m.id]);
  const tall = kind !== "track" && kind !== "episode";
  const frame = useRef<HTMLIFrameElement>(null);
  // Spotify's player says when it is ready and takes a play command, so the
  // press that opened it also starts it (SoundCloud's starts by itself)
  useEffect(() => {
    if (m.player !== "spotify") return;
    const heard = (ev: MessageEvent) => {
      const win = frame.current?.contentWindow;
      if (!win || ev.source !== win) return;
      const data = ev.data as { type?: unknown } | null;
      if (data && typeof data === "object" && data.type === "ready") win.postMessage({ command: "play" }, "*");
    };
    window.addEventListener("message", heard);
    return () => window.removeEventListener("message", heard);
  }, [m.player]);
  return (
    <div className={s.frame} data-site={m.player} data-tall={tall || undefined}>
      {failed ? (
        <p className={s.note}>{t("chat.embeds.music.playerFailed")}</p>
      ) : src ? (
        <iframe
          ref={frame}
          src={src}
          title={m.title}
          allow="autoplay; encrypted-media"
          sandbox="allow-scripts allow-presentation"
          referrerPolicy="no-referrer"
          data-testid="music-player"
        />
      ) : (
        <div className={s.note} aria-busy="true" />
      )}
    </div>
  );
}

function TrackRow({ track, index, owner, art, by, onOpen }: { track: MusicTrack; index: number; owner: string; art: string | undefined; by: boolean; onOpen: (() => void) | null }) {
  const signed = useMediaSrc(track.preview?.startsWith("/media/music/") ? null : track.preview);
  const src = previewSrc(track.preview, signed);
  const key = `${owner}:${index}`;
  const on = usePreview((p) => p.id === key);
  const state = usePreview((p) => (p.id === key ? p.state : null));
  const progress = usePreview((p) => (p.id === key ? p.progress : 0));
  const body = (
    <>
      <span className={s.no} aria-hidden="true">
        <span className={s.num}>{(index + 1).toLocaleString()}</span>
        {src || onOpen ? <span className={s.cue}>{on && state === "loading" ? <LoaderCircle size={14} className={s.spin} /> : on && state === "playing" ? <Pause size={13} fill="currentColor" /> : <Play size={13} fill="currentColor" />}</span> : null}
        {on && state === "playing" ? (
          <span className={s.eq}>
            <i />
            <i />
            <i />
          </span>
        ) : null}
      </span>
      <span className={s.trackText}>
        <span className={s.trackName} dir="auto">
          {track.title}
        </span>
        {track.explicit ? <Explicit /> : null}
      </span>
      {by ? (
        <span className={s.trackBy} dir="auto">
          {track.artist}
        </span>
      ) : null}
      {track.duration ? <span className={s.trackLen}>{formatDuration(track.duration)}</span> : null}
      {on ? <span className={s.played} style={{ transform: `scaleX(${progress})` }} aria-hidden="true" /> : null}
    </>
  );
  const press = src ? () => playPreview({ key, src, name: track.title, by: track.artist, art }, owner) : onOpen;
  return (
    <li className={s.trackRow} data-state={on ? (state ?? undefined) : undefined}>
      {press ? (
        <button type="button" className={s.track} onClick={press} aria-label={t(on && state !== "paused" ? "widgets.music.pause" : "widgets.music.play", { name: track.title })} data-testid="music-track">
          {body}
        </button>
      ) : (
        <span className={s.track}>{body}</span>
      )}
    </li>
  );
}

/**
 * Under the cover while a preview can play: how far through it is (a bar to
 * press or drag once it plays), its times, and how loud previews play.
 */
function PreviewStrip({ k, length }: { k: string; length: number }) {
  const on = usePreview((p) => p.id === k);
  const progress = usePreview((p) => (p.id === k ? p.progress : 0));
  const duration = usePreview((p) => (p.id === k && p.duration > 0 ? p.duration : 0));
  const total = duration || length;
  const fill = `${(progress * 100).toFixed(2)}%`;
  return (
    <div className={s.strip} data-on={on || undefined}>
      <span className={s.time}>{formatDuration(Math.floor(progress * total))}</span>
      <input
        className={s.seek}
        type="range"
        min={0}
        max={1}
        step={0.001}
        value={progress}
        disabled={!on}
        style={{ "--fill": fill } as CSSProperties}
        onChange={(ev) => seekPreview(k, Number(ev.target.value))}
        aria-label={t("chat.embeds.music.seek")}
        aria-valuetext={`${formatDuration(Math.floor(progress * total))} / ${formatDuration(total)}`}
        data-testid="music-seek"
      />
      <span className={s.time}>{total > 0 ? formatDuration(total) : ""}</span>
      <SongVolume className={s.vol} />
    </div>
  );
}

/** A music link's card. */
export function MusicEmbed({ e, m }: { e: UnfurlResult; m: MusicCard }) {
  const owner = useId();
  const site = SITES[m.platform] ?? { name: m.platform, color: "var(--brand)" };
  const art = useMediaSrc(m.art);
  const icon = useMediaSrc(e.icon);
  const signed = useMediaSrc(m.preview?.startsWith("/media/music/") ? null : m.preview);
  const preview = previewSrc(m.preview, signed);
  const [open, setOpen] = useState(false);
  const key = `${owner}:main`;
  const on = usePreview((p) => p.id === key);
  const state = usePreview((p) => (p.id === key ? p.state : null));
  const progress = usePreview((p) => (p.id === key ? p.progress : 0));
  // a preview stops with the message that started it
  useEffect(() => () => stopPreview(owner), [owner]);

  // a SoundCloud track plays in this card through SoundCloud's widget, out of
  // sight; a Spotify track with a preview found elsewhere plays that; the rest
  // open the service's own player
  // a browser that will not start SoundCloud's player out of sight (Safari) gets it in the card, for a tap of its own
  const blocked = usePreview((p) => p.blocked === `${owner}:main`);
  const widget = m.player === "soundcloud" && m.kind === "track" && !blocked;
  const player = (m.player === "spotify" && !preview) || (m.player === "soundcloud" && !widget);
  useEffect(() => {
    if (blocked) setOpen(true);
  }, [blocked]);
  const widgetSrc = useRef<string | null>(null);
  const [fetching, setFetching] = useState(false);
  const playWidget = () => {
    const go = (src: string) => playPreview({ key, src, name: m.title, by: m.artist, art: art ?? undefined, widget: true }, owner);
    if (widgetSrc.current) return go(widgetSrc.current);
    if (fetching) return;
    setFetching(true);
    api
      .request<{ url: string }>("GET", `/music/player/soundcloud/track/${encodeURIComponent(m.id)}`)
      .then((r) => {
        if (!r?.url.startsWith("/sc/")) throw new Error("bad player address");
        widgetSrc.current = api.baseUrl + r.url;
        go(widgetSrc.current);
      })
      .catch(() => useUI.getState().toast(t("chat.embeds.music.playerFailed"), "error"))
      .finally(() => setFetching(false));
  };
  const tracks = (m.tracks ?? []).filter((x) => x && typeof x.title === "string").slice(0, 5);
  // who made each track is said only when they are not all by whoever made the album
  const by = tracks.some((x) => !!x.artist && x.artist !== m.artist && x.artist !== m.title);
  const press = player
    ? () => {
        stopAnyPreview();
        setOpen(true);
      }
    : widget
      ? playWidget
      : preview
        ? () => playPreview({ key, src: preview, name: m.title, by: m.artist, art: art ?? undefined }, owner)
        : null;
  const busy = fetching || (on && state === "loading");
  const playing = on && state === "playing";

  const album = (m.kind === "track" || m.kind === "episode") && m.album && m.album !== m.title ? m.album : null;
  const meta = [m.year ? String(m.year) : null, countLine(m), m.duration && (m.kind === "track" || m.kind === "episode") ? formatDuration(m.duration) : null].filter(
    (x): x is string => !!x,
  );

  return (
    <div className={s.card} style={{ "--site": site.color } as CSSProperties} data-testid="music-embed" data-platform={m.platform}>
      {/* the record's own colours, blurred into the card behind everything */}
      {art && !(open && player) ? <img className={s.backdrop} src={art} alt="" aria-hidden="true" referrerPolicy="no-referrer" draggable={false} onError={(ev) => (ev.currentTarget.style.display = "none")} /> : null}
      <div className={s.head}>
        {icon ? <img className={s.favicon} src={icon} alt="" loading="lazy" referrerPolicy="no-referrer" onError={(ev) => (ev.currentTarget.style.display = "none")} /> : null}
        <span className={s.siteName}>{site.name}</span>
        <span className={s.kind}>{t(`chat.embeds.music.kind.${m.kind}`)}</span>
        {preview && m.preview_from ? <span className={s.from}>{t(`chat.embeds.music.previewFrom.${m.preview_from}`)}</span> : null}
      </div>
      {open && player ? (
        <PlayerFrame m={m} />
      ) : (
        <>
          <div className={s.main}>
            <div className={s.cover} data-round={m.kind === "artist" || undefined}>
              {art ? <img src={art} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} onError={(ev) => (ev.currentTarget.style.visibility = "hidden")} /> : null}
              {press ? (
                <button
                  type="button"
                  className={s.disc}
                  onClick={press}
                  data-state={on ? (state ?? undefined) : undefined}
                  aria-label={t(playing ? "widgets.music.pause" : "widgets.music.play", { name: m.title })}
                  data-testid="music-play"
                >
                  {on ? (
                    <svg className={s.ring} viewBox="0 0 44 44" aria-hidden="true">
                      <circle cx="22" cy="22" r="20" pathLength="100" style={{ strokeDashoffset: 100 - progress * 100 }} />
                    </svg>
                  ) : null}
                  {busy ? <LoaderCircle size={20} className={s.spin} /> : playing ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" className={s.playGlyph} />}
                </button>
              ) : null}
            </div>
            <div className={s.text}>
              <a className={s.title} href={e.url} target="_blank" rel="noopener noreferrer" dir="auto">
                {m.title}
              </a>
              {m.artist && m.kind !== "artist" ? (
                <span className={s.artist} dir="auto">
                  {m.artist}
                  {m.explicit && m.kind === "track" ? <Explicit /> : null}
                </span>
              ) : null}
              {album ? (
                <span className={s.album} dir="auto">
                  {album}
                </span>
              ) : null}
              {meta.length ? <span className={s.meta}>{meta.join(" · ")}</span> : null}
            </div>
          </div>
          {widget ? <PreviewStrip k={key} length={m.duration ?? 0} /> : preview && !player ? <PreviewStrip k={key} length={30} /> : null}
          {tracks.length ? (
            <ol className={s.tracks} aria-label={m.title} data-by={by || undefined}>
              {tracks.map((tr, i) => (
                <TrackRow key={i} track={tr} index={i} owner={owner} art={art ?? undefined} by={by} onOpen={player ? () => press?.() : null} />
              ))}
            </ol>
          ) : null}
        </>
      )}
    </div>
  );
}
