import { Play, X } from "lucide-react";
import { useLayoutEffect, useMemo, useRef, useState, type MouseEvent, type ReactNode } from "react";
import type { UnfurlResult } from "@/api/types";
import { trimUrl } from "@/markdown/parse";
import { FavoriteButton } from "./FavoriteButton";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { fitMedia } from "./Attachments";
import { Lightbox } from "./Lightbox";
import { AudioPlayer } from "./AudioPlayer";
import { PostEmbed } from "./PostEmbed";
import { VideoPlayer } from "./VideoPlayer";
import { isYouTubeID, YouTubeVideo } from "./YouTubePlayer";
import c from "./Chat.module.css";
import s from "./Embeds.module.css";
import { imageNameFromUrl } from "@/lib/imageActions";
import { useMediaSrc } from "@/lib/mediaLinks";
import { formatShortDate } from "@/lib/time";

import { t } from "@/i18n";
/** A readable name for a linked file: the last part of its address. */
function fileNameOf(url: string, fallback: string): string {
  try {
    const last = new URL(url).pathname.split("/").filter(Boolean).pop();
    return last ? decodeURIComponent(last) : fallback;
  } catch {
    return fallback;
  }
}

function fitCard(w: number | undefined, h: number | undefined, maxW = 400, maxH = 300): { width: number; height: number } | undefined {
  if (!w || !h) return undefined;
  const r = Math.min(1, maxW / w, maxH / h);
  return { width: Math.round(w * r), height: Math.round(h * r) };
}

/**
 * A linked picture or GIF, framed and opened exactly as an uploaded one is.
 *
 * Only through the proxy (lib/mediaLinks): the frame keeps its shape while a
 * stale link is signed again, and a preview naming any other address is not
 * shown at all.
 */
function ImageEmbed({ e }: { e: UnfurlResult }) {
  const src = useMediaSrc(e.image ?? e.url);
  const [open, setOpen] = useState(false);
  // without a recorded size, the picture's own tells the frame what shape to be
  const [natural, setNatural] = useState<{ w: number; h: number } | null>(null);
  const dims = fitMedia(e.image_w ?? natural?.w, e.image_h ?? natural?.h);
  const name = e.type === "gif" ? t("chat.embeds.gif") : fileNameOf(e.url, t("server.emoji.image"));
  // what "Download" saves it as; a GIF from the picker arrives through the proxy, whose address says nothing
  const fileName = e.type === "gif" ? "animation.gif" : fileNameOf(e.url, "image");
  if (src === null) return null;
  if (src === undefined) {
    return <div className={[c.imgWrap, s.media].join(" ")} style={{ width: dims.width, aspectRatio: `${dims.width} / ${dims.height}` }} aria-busy="true" />;
  }
  return (
    <>
      <div className={[c.imgWrap, s.media].join(" ")} style={{ width: dims.width, aspectRatio: `${dims.width} / ${dims.height}` }} onClick={() => setOpen(true)}>
        <img
          src={src}
          alt={name}
          width={dims.width}
          height={dims.height}
          loading="lazy"
          referrerPolicy="no-referrer"
          data-image-src={src}
          data-image-name={fileName}
          // the way into the viewer, for a keyboard as for a pointer
          role="button"
          tabIndex={0}
          aria-haspopup="dialog"
          onKeyDown={(ev) => {
            if (ev.key !== "Enter" && ev.key !== " ") return;
            ev.preventDefault();
            setOpen(true);
          }}
          onLoad={(ev) => {
            if (!e.image_w || !e.image_h) setNatural({ w: ev.currentTarget.naturalWidth, h: ev.currentTarget.naturalHeight });
          }}
        />
        {e.type === "gif" ? <span className={s.gifBadge}>GIF</span> : null}
        <FavoriteButton url={src} {...(e.image_w ? { w: e.image_w } : {})} {...(e.image_h ? { h: e.image_h } : {})} />
      </div>
      {open ? <Lightbox items={[{ src, name: fileName }]} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

/**
 * The sites whose players a card may open in a frame. The server builds the
 * address (internal/unfurl/players.go); this list is the second lock, so a
 * preview that ever came from somewhere else still cannot run a page here.
 */
const PLAYER_HOSTS = new Set(["player.vimeo.com", "player.twitch.tv", "clips.twitch.tv", "www.dailymotion.com", "streamable.com", "www.loom.com"]);

/** A YouTube video's id, from the card or from a player address the server built before players went through it. */
function youtubeID(e: UnfurlResult): string | null {
  if (isYouTubeID(e.video_id)) return e.video_id;
  try {
    const u = new URL(e.player ?? "");
    const m = /^\/embed\/([A-Za-z0-9_-]{11})$/.exec(u.pathname);
    return u.host === "www.youtube-nocookie.com" && m ? m[1]! : null;
  } catch {
    return null;
  }
}

/** A player in another site's frame, for the sites both ends agree on. YouTube goes through this server instead. */
function playerSrc(e: UnfurlResult): string | null {
  if (!e.player) return null;
  try {
    const u = new URL(e.player);
    return u.protocol === "https:" && PLAYER_HOSTS.has(u.host) ? u.toString() : null;
  } catch {
    return null;
  }
}

/** "3 Feb 2026", for the line under a card's title. */
function publishedOn(ms: number | undefined): string | null {
  if (!ms) return null;
  if (Number.isNaN(new Date(ms).getTime())) return null;
  // the app's language, not the browser's
  return formatShortDate(ms);
}

/** An address in a page's words, as far as the words around it let it go. */
const RE_ADDRESS = /https?:\/\/[^\s<>"]+/gi;

/** A page's words with the addresses in them made into links, each opening in a new tab. */
export function linkify(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let at = 0;
  for (const m of text.matchAll(RE_ADDRESS)) {
    const start = m.index ?? 0;
    // the full stop or bracket after an address belongs to the sentence
    const url = trimUrl(m[0]);
    if (start < at || !/^https?:\/\/\S/i.test(url)) continue;
    if (start > at) out.push(text.slice(at, start));
    out.push(
      <a key={start} className="md-link" href={url} target="_blank" rel="noopener noreferrer">
        {url}
      </a>,
    );
    at = start + url.length;
  }
  if (at < text.length) out.push(text.slice(at));
  return out;
}

/**
 * A page's description as it wrote it: its line breaks kept and its addresses
 * made into links. A long one shows its first lines until asked for the rest.
 */
function Description({ text }: { text: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [folds, setFolds] = useState(false);
  const parts = useMemo(() => linkify(text), [text]);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || open) return;
    const check = () => setFolds(el.scrollHeight > el.clientHeight + 1);
    check();
    // a narrower window folds lines that fitted before
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(check);
    ro.observe(el);
    return () => ro.disconnect();
  }, [text, open]);
  return (
    <>
      <div ref={ref} className={s.desc} data-open={open} dir="auto">
        {parts}
      </div>
      {folds || open ? (
        <button type="button" className={s.more} aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          {open ? t("chat.embeds.showLess") : t("chat.embeds.showMore")}
        </button>
      ) : null}
    </>
  );
}

/**
 * A card's picture, which opens in the viewer like any other. A page that
 * asks for its picture large (twitter:card) has it across the card, one that
 * asks for a thumbnail has it small beside the words, and one that says
 * neither has it large when it is big enough to be worth it.
 */
function CardPicture({ e, src, large }: { e: UnfurlResult; src: string; large: boolean }) {
  const [open, setOpen] = useState(false);
  const name = imageNameFromUrl(src);
  const show = () => setOpen(true);
  // a width and a shape rather than a height, so a narrow screen shrinks it whole instead of squeezing it
  const fit = large ? fitCard(e.image_w, e.image_h, 360, 240) : undefined;
  return (
    <>
      <img
        className={large ? s.large : s.thumb}
        src={src}
        // the picture is most of what a large preview says, so it is named
        alt={large ? e.title || e.site_name || t("chat.embeds.linkPreviewImage") : ""}
        loading="lazy"
        referrerPolicy="no-referrer"
        style={fit ? { width: fit.width, aspectRatio: `${fit.width} / ${fit.height}` } : undefined}
        data-image-src={src}
        data-image-name={name}
        role="button"
        tabIndex={0}
        aria-haspopup="dialog"
        aria-label={large ? undefined : t("chat.embeds.linkPreviewImage")}
        onClick={show}
        onKeyDown={(ev) => {
          if (ev.key !== "Enter" && ev.key !== " ") return;
          ev.preventDefault();
          show();
        }}
      />
      {open ? <Lightbox items={[{ src, name: name || "image" }]} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

/**
 * Whether an author is only the site's own account: "@youtube" beside
 * "YouTube". Previews made before the server stopped taking the site's
 * account for the author still carry one.
 */
export function siteAccount(author: string | undefined, site: string | undefined): boolean {
  if (!author?.startsWith("@") || !site) return false;
  const handle = author.slice(1).toLowerCase().replace(/[^a-z0-9]/g, "");
  const name = site.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (!handle || !name) return false;
  // a short handle is only the site's when it is the site's whole name
  return handle === name || (handle.length >= 3 && name.length >= 3 && (name.includes(handle) || handle.includes(name)));
}

/** The line above the title: the site's icon, its name, the author, the date. */
function CardHead({ e }: { e: UnfurlResult }) {
  const icon = useMediaSrc(e.icon);
  const when = publishedOn(e.published_at);
  const byline = siteAccount(e.author, e.site_name) ? undefined : e.author;
  const author = e.author_url ? (
    <a href={e.author_url} target="_blank" rel="noopener noreferrer">{byline || e.author_url.replace(/^https?:\/\//, "")}</a>
  ) : byline ? (
    <span>{byline}</span>
  ) : null;
  if (!e.site_name && !author && !when && !icon) return null;
  return (
    <div className={s.site}>
      {icon ? <img className={s.favicon} src={icon} alt="" loading="lazy" referrerPolicy="no-referrer" onError={(ev) => (ev.currentTarget.style.display = "none")} /> : null}
      {e.site_name ? <span>{e.site_name}</span> : null}
      {author ? <><span className={s.dot} aria-hidden="true">·</span>{author}</> : null}
      {when ? <><span className={s.dot} aria-hidden="true">·</span><time dateTime={new Date(e.published_at!).toISOString()}>{when}</time></> : null}
    </div>
  );
}

/**
 * One link's card. Everything on it is drawn from fields the server filled in
 * — no markup from the page itself ever reaches the document. The single
 * exception is a video, which needs its site's own player; that frame only
 * opens for the handful of sites both ends agree on, and only when asked.
 */
function LinkEmbed({ e }: { e: UnfurlResult }) {
  const [play, setPlay] = useState(false);
  const image = useMediaSrc(e.image);
  const media = useMediaSrc(e.media);
  const yt = youtubeID(e);
  const src = yt ? null : playerSrc(e);
  const big = !!e.image && (e.image_layout === "large" || (e.image_layout !== "thumbnail" && (e.image_w ?? 0) >= 400));
  const title = e.title ? <a className={s.title} href={e.url} target="_blank" rel="noopener noreferrer" dir="auto">{e.title}</a> : null;
  if (yt) {
    return (
      <div className={s.card} style={e.color ? { borderInlineStartColor: e.color } : undefined}>
        <CardHead e={e} />
        {title}
        {e.description ? <Description text={e.description} /> : null}
        <YouTubeVideo id={yt} title={e.title ?? t("perms.video")} poster={image} start={e.video_start} w={e.image_w} h={e.image_h} />
      </div>
    );
  }
  if (src) {
    return (
      <div className={s.card} style={e.color ? { borderInlineStartColor: e.color } : undefined}>
        <CardHead e={e} />
        {title}
        {e.description ? <Description text={e.description} /> : null}
        <div className={s.video}>
          {play ? (
            <iframe
              src={src}
              title={e.title ?? t("perms.video")}
              allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
              sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
              allowFullScreen
              referrerPolicy="strict-origin-when-cross-origin"
            />
          ) : (
            <button className={s.poster} onClick={() => setPlay(true)} aria-label={t("chat.embeds.playVideo")} style={image ? { backgroundImage: `url(${image})` } : undefined}>
              <span className={s.playBtn}><Play size={26} fill="currentColor" /></span>
            </button>
          )}
        </div>
      </div>
    );
  }
  return (
    <div className={s.card} style={e.color ? { borderInlineStartColor: e.color } : undefined}>
      <div className={s.cardRow}>
        <div className={s.cardText}>
          <CardHead e={e} />
          {title}
          {e.description ? <Description text={e.description} /> : null}
        </div>
        {image && !big && !e.media ? <CardPicture e={e} src={image} large={false} /> : null}
      </div>
      {/* a file this browser plays on its own; the page it came from runs nothing */}
      {media && e.media_kind === "video" ? (
        <VideoPlayer src={media} name={fileNameOf(e.url, t("perms.video"))} width={e.media_w} height={e.media_h} />
      ) : media && e.media_kind === "audio" ? (
        // the app's own player, as for an uploaded voice note, not the browser's grey bar
        <div className={s.audio}><AudioPlayer src={media} name={fileNameOf(e.url, t("chat.embeds.audio"))} /></div>
      ) : image && big ? (
        <CardPicture e={e} src={image} large />
      ) : null}
    </div>
  );
}

/** One preview, drawn the way its kind is, or null when there is nothing to draw. */
function embedFor(e: UnfurlResult, imagePreviews: boolean): ReactNode {
  if (!e || typeof e !== "object" || typeof e.url !== "string") return null;
  // a social post is drawn as a post; without one of those it is a link like any other
  if (e.post && typeof e.post === "object") return <PostEmbed e={e} />;
  if (e.type === "image" || e.type === "gif") return imagePreviews ? <ImageEmbed e={e} /> : null;
  // played through this server's proxy, so the host of the file never sees who is watching
  if (e.type === "video") return imagePreviews ? <VideoEmbed e={e} /> : null;
  if (e.type === "audio") return <AudioEmbed e={e} />;
  if (e.type === "youtube") return <LinkEmbed e={e} />;
  // drawn by InviteCards and ProfileCards, which also cover links that came without a card
  if (e.type === "invite" || e.type === "profile") return null;
  if (e.title || e.description || e.player || e.media) return <LinkEmbed e={e} />;
  return null;
}

/**
 * A message's link previews. `onRemove`, for the author and whoever may
 * delete the message, puts a small x on each one that takes them all away.
 */
export function Embeds({ list, onRemove }: { list: UnfurlResult[]; onRemove?: ((ev: MouseEvent) => void) | undefined }) {
  const imagePreviews = useSettings((st) => st.settings.imagePreviews);
  if (list.every((e) => e?.type === "invite" || e?.type === "profile")) return null;
  return (
    <div className={s.embeds}>
      {list.slice(0, 5).map((e, i) => {
        const body = embedFor(e, imagePreviews);
        if (!body) return null;
        return (
          <div key={i} className={s.item}>
            {body}
            {onRemove ? (
              <button type="button" className={s.remove} onClick={onRemove} aria-label={t("chat.embeds.removeEmbeds")} title={t("chat.embeds.removeEmbeds")} data-testid="remove-embeds">
                <X size={14} strokeWidth={2.5} />
              </button>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

/** A linked video file, played through the proxy like everything else a preview loads. */
function VideoEmbed({ e }: { e: UnfurlResult }) {
  const src = useMediaSrc(e.image ?? e.url);
  return src ? <VideoPlayer src={src} name={fileNameOf(e.url, t("perms.video"))} width={e.image_w} height={e.image_h} /> : null;
}

/** A linked audio file uses the same player as an upload, always through the proxy. */
function AudioEmbed({ e }: { e: UnfurlResult }) {
  const src = useMediaSrc(e.media);
  return src ? <div className={s.audioFile}><AudioPlayer src={src} name={fileNameOf(e.url, t("chat.embeds.audio"))} /></div> : null;
}
