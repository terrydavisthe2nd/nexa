import { ArrowBigUp, BadgeCheck, Eye, Forward, Heart, MessageCircle, Play, Repeat2, Smile, UsersRound, type LucideIcon } from "lucide-react";
import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { PostMedia, PostPoll, SocialPost, UnfurlResult } from "@/api/types";
import { t } from "@/i18n";
import { useMediaLinks } from "@/lib/mediaLinks";
import { formatCompact, formatFull, formatPercent, formatShortDate } from "@/lib/time";
import { Lightbox, type LightboxItem } from "./Lightbox";
import { postText } from "./postText";
import { VideoPlayer } from "./VideoPlayer";
import { isYouTubeID, YouTubeVideo } from "./YouTubePlayer";
import s from "./PostEmbed.module.css";

/**
 * A social post, drawn as a post: who wrote it, what it says, its pictures
 * and video, the post it quotes, and how many people answered.
 *
 * The server read all of it from the post's own site (internal/unfurl/post.go)
 * and the sender's app sealed it into the message, so nothing is fetched from
 * the site when the card is shown: every picture and video loads through this
 * server's proxy (lib/mediaLinks), and a file that is not a proxy address is
 * not loaded at all. No markup from the site reaches the page either; the
 * card is built here from plain fields.
 */

/** The video a YouTube post's card plays, and where its link asks it to start. */
interface YouTubeRef {
  id: string;
  start?: number | undefined;
}

/** The size a lone picture or a tile grid takes at most. */
const MEDIA_W = 416;
const MEDIA_H = 320;

function fit(w: number | undefined, h: number | undefined, maxW = MEDIA_W, maxH = MEDIA_H): { width: number; height: number } | null {
  if (!w || !h) return null;
  const r = Math.min(1, maxW / w, maxH / h);
  return { width: Math.max(1, Math.round(w * r)), height: Math.max(1, Math.round(h * r)) };
}

/** A post's words, folded after a few lines until asked for the rest. */
function Fold({ children, lines, signature }: { children: ReactNode; lines: number; signature: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [folds, setFolds] = useState(false);
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
  }, [signature, open]);
  return (
    <>
      <div ref={ref} className={s.text} data-open={open} style={{ WebkitLineClamp: open ? "unset" : lines }} dir="auto">
        {children}
      </div>
      {folds || open ? (
        <button type="button" className={s.more} aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          {open ? t("chat.embeds.showLess") : t("chat.embeds.showMore")}
        </button>
      ) : null}
    </>
  );
}

/** The author's picture, or the site's icon on a tile where the site gave none (Reddit) or it would not load. */
function Face({ avatar, icon, name, small }: { avatar: string | null | undefined; icon: string | null | undefined; name: string; small: boolean }) {
  const [broken, setBroken] = useState(false);
  if (avatar && !broken) {
    return <img className={s.avatar} data-small={small} src={avatar} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} onError={() => setBroken(true)} />;
  }
  return (
    <span className={[s.avatar, s.avatarBlank].join(" ")} data-small={small} aria-hidden="true">
      {icon ? <img src={icon} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} onError={(ev) => (ev.currentTarget.style.display = "none")} /> : Array.from(name.replace(/^[@/]+/, ""))[0]?.toUpperCase()}
    </span>
  );
}

/** The service's own icon, gone altogether if it will not load, so the chip it sits on can tell it has none. */
function SiteIcon({ src }: { src: string | null | undefined }) {
  const [broken, setBroken] = useState(false);
  if (!src || broken) return null;
  return <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} onError={() => setBroken(true)} />;
}

/** A link out of the app in a new tab, or the same words as plain text where there is nowhere to go. */
function outside(href: string | undefined, className: string | undefined, children: ReactNode, label?: string) {
  if (!href) return <span className={className}>{children}</span>;
  return (
    <a className={className} href={href} target="_blank" rel="noopener noreferrer" aria-label={label}>
      {children}
    </a>
  );
}

/** A short video that loops without sound, the way a GIF does. A tap stops and starts it. */
function Loop({ src, poster, alt }: { src: string; poster: string | undefined; alt: string }) {
  return (
    <video
      className={s.loop}
      src={src}
      poster={poster}
      aria-label={alt}
      autoPlay
      loop
      muted
      playsInline
      preload="metadata"
      onClick={(ev) => {
        const el = ev.currentTarget;
        if (el.paused) void el.play().catch(() => {});
        else el.pause();
      }}
    />
  );
}

/**
 * A post's pictures and videos. Pictures and looping clips share a grid, as
 * the sites lay them out: one alone at its own shape, two side by side, three
 * with the first tall, four in a square, and a count over the fourth when
 * there are more. A video that plays here gets the app's player; one that
 * cannot (a stream, a site that keeps its files) shows its picture, which
 * opens the post.
 */
function Media({ post, link, compact, video }: { post: SocialPost; link: string | undefined; compact: boolean; video?: YouTubeRef | undefined }) {
  const src = useMediaLinks();
  const [covered, setCovered] = useState(!!post.sensitive);
  const [viewing, setViewing] = useState<number | null>(null);
  const media = post.media ?? [];
  const site = post.site ?? "";
  // a YouTube video plays here, in YouTube's player fetched through this server
  if (video && media[0]?.kind === "video") {
    const m = media[0];
    return (
      <div className={s.media} data-covered={covered} data-compact={compact}>
        <YouTubeVideo id={video.id} title={post.title || m.alt || site} poster={src(m.thumb)} start={video.start} duration={m.duration} live={post.live} w={m.w} h={m.h} />
        {covered ? (
          <button type="button" className={s.cover} onClick={() => setCovered(false)}>
            <span>{t("chat.post.sensitive")}</span>
            <span className={s.coverShow}>{t("chat.post.show")}</span>
          </button>
        ) : null}
      </div>
    );
  }

  const players: PostMedia[] = [];
  const tiles: PostMedia[] = [];
  for (const m of media) {
    if (m.kind === "video" && m.url) players.push(m);
    else tiles.push(m);
  }
  // what the viewer can page through: the pictures, in order
  const photos = tiles.filter((m) => m.kind === "photo" && m.url);
  const items: LightboxItem[] = [];
  const indexOf = new Map<PostMedia, number>();
  for (const m of photos) {
    const url = src(m.url);
    if (!url) continue;
    indexOf.set(m, items.length);
    items.push({ src: url, name: m.alt || t("chat.post.pictureOf", { index: items.length + 1, count: photos.length }) });
  }
  if (!media.length) return null;

  const shown = tiles.slice(0, 4);
  const extra = tiles.length - shown.length;
  const alone = shown.length === 1 && !compact ? fit(shown[0]!.w, shown[0]!.h) : null;

  const tile = (m: PostMedia, i: number) => {
    const last = i === shown.length - 1 && extra > 0;
    const alt = m.alt || t("chat.post.pictureOf", { index: i + 1, count: tiles.length });
    if (m.kind === "photo") {
      const url = src(m.url);
      if (url === null) return null;
      const at = indexOf.get(m);
      const open = () => at !== undefined && setViewing(at);
      return (
        <div key={i} className={s.tile} aria-busy={url === undefined}>
          {url ? (
            <img
              src={url}
              alt={alt}
              loading="lazy"
              referrerPolicy="no-referrer"
              data-image-src={url}
              role="button"
              tabIndex={0}
              aria-haspopup="dialog"
              onClick={open}
              onKeyDown={(ev) => {
                if (ev.key !== "Enter" && ev.key !== " ") return;
                ev.preventDefault();
                open();
              }}
              onError={(ev) => (ev.currentTarget.style.visibility = "hidden")}
            />
          ) : null}
          {last ? <span className={s.extra} aria-hidden="true">+{extra}</span> : null}
        </div>
      );
    }
    const poster = src(m.thumb);
    if (m.kind === "gif" && m.url) {
      const url = src(m.url);
      if (url === null) return null;
      return (
        <div key={i} className={s.tile} aria-busy={url === undefined}>
          {url ? <Loop src={url} poster={poster ?? undefined} alt={alt} /> : null}
          <span className={s.badge}>{t("chat.embeds.gif")}</span>
          {last ? <span className={s.extra} aria-hidden="true">+{extra}</span> : null}
        </div>
      );
    }
    // a video the app cannot play: its picture, which opens the post where it can be watched
    if (poster === null) return null;
    return (
      <a key={i} className={s.tile} href={link} target="_blank" rel="noopener noreferrer" aria-label={t("chat.post.watchOn", { site })} aria-busy={poster === undefined}>
        {poster ? <img src={poster} alt="" loading="lazy" referrerPolicy="no-referrer" onError={(ev) => (ev.currentTarget.style.visibility = "hidden")} /> : null}
        <span className={s.play} aria-hidden="true"><Play size={22} fill="currentColor" /></span>
        {last ? <span className={s.extra} aria-hidden="true">+{extra}</span> : null}
      </a>
    );
  };

  return (
    <div className={s.media} data-covered={covered} data-compact={compact}>
      {shown.length ? (
        <div
          className={s.grid}
          data-count={shown.length}
          style={alone ? { width: alone.width, aspectRatio: `${alone.width} / ${alone.height}` } : undefined}
          data-sized={!!alone}
        >
          {shown.map(tile)}
        </div>
      ) : null}
      {players.map((m, i) => {
        const url = src(m.url);
        const poster = src(m.thumb);
        if (!url) return url === undefined ? <div key={i} className={s.waiting} aria-busy="true" /> : null;
        // a quoted post's video stays small: the player is as wide as its controls need
        return (
          <div key={i} className={s.player}>
            <VideoPlayer src={url} name="video.mp4" width={m.w} height={m.h} poster={poster ?? undefined} audio={src(m.audio) ?? undefined} />
          </div>
        );
      })}
      {covered ? (
        <button type="button" className={s.cover} onClick={() => setCovered(false)}>
          <span>{t("chat.post.sensitive")}</span>
          <span className={s.coverShow}>{t("chat.post.show")}</span>
        </button>
      ) : null}
      {viewing !== null && items.length ? <Lightbox items={items} start={viewing} onClose={() => setViewing(null)} /> : null}
    </div>
  );
}

function Poll({ poll }: { poll: PostPoll }) {
  const top = Math.max(0, ...poll.choices.map((c) => c.percent));
  const closed = !!poll.ends && poll.ends < Date.now();
  return (
    <div className={s.poll}>
      {poll.choices.map((c, i) => (
        <div key={i} className={s.choice} data-top={top > 0 && c.percent === top}>
          <span className={s.choiceBar} style={{ inlineSize: `${Math.min(100, Math.max(0, c.percent))}%` }} aria-hidden="true" />
          <span className={s.choiceLabel} dir="auto">{c.label}</span>
          <span className={s.choiceShare}>{formatPercent(c.percent)}</span>
        </div>
      ))}
      <div className={s.pollFoot}>
        {poll.votes ? <span>{t("chat.post.votes", { count: poll.votes, n: formatCompact(poll.votes) })}</span> : null}
        {closed ? <span>{t("chat.post.pollClosed")}</span> : poll.ends ? <span>{t("chat.post.pollEnds", { date: formatShortDate(poll.ends) })}</span> : null}
      </div>
    </div>
  );
}

/** What each site calls the third number, and the picture for it. */
function likes(platform: string): [LucideIcon, string] {
  if (platform === "reddit") return [ArrowBigUp, "chat.post.points"];
  if (platform === "telegram") return [Smile, "chat.post.reactions"];
  return [Heart, "chat.post.likes"];
}

function Counts({ post }: { post: SocialPost }) {
  const [LikeIcon, likeKey] = likes(post.platform);
  // TikTok counts how often a video was sent on, not reposted
  const shares = post.platform === "tiktok";
  const all: [LucideIcon, number | undefined, string][] = [
    [MessageCircle, post.replies, "chat.post.replies"],
    [shares ? Forward : Repeat2, post.reposts, shares ? "chat.post.shares" : "chat.post.reposts"],
    [LikeIcon, post.likes, likeKey],
    [Eye, post.views, "chat.post.views"],
  ];
  const shown = all.filter(([, n]) => !!n && n > 0);
  if (!shown.length) return null;
  return (
    <div className={s.counts}>
      {shown.map(([Icon, n, key]) => {
        const label = t(key, { count: n!, n: formatCompact(n!) });
        return (
          <span key={key} className={s.count} title={label}>
            <Icon size={15} aria-hidden="true" />
            <span aria-hidden="true">{formatCompact(n!)}</span>
            <span className="visually-hidden">{label}</span>
          </span>
        );
      })}
    </div>
  );
}

function PostView({ post, icon, link, quoted, video }: { post: SocialPost; icon: string | undefined; link: string | undefined; quoted: boolean; video?: YouTubeRef | undefined }) {
  const src = useMediaLinks();
  const reddit = post.platform === "reddit";
  // Reddit is read by where a post is, then who wrote it; everywhere else by who.
  const first = reddit && post.community ? post.community : post.name || (post.handle ? `@${post.handle}` : post.community) || post.site || "";
  const firstLink = reddit && post.community ? post.community_url : post.author_url;
  const second = reddit ? (post.community ? post.name : undefined) : post.name && post.handle ? `@${post.handle}` : undefined;
  const text = post.text ?? "";
  const words = useMemo(() => postText(text, post.links, post.platform), [text, post.links, post.platform]);
  const when = post.created && !Number.isNaN(new Date(post.created).getTime()) ? post.created : 0;
  const date = when ? (
    <time dateTime={new Date(when).toISOString()} title={formatFull(when)}>
      {formatShortDate(when)}
    </time>
  ) : null;
  const site = post.site ?? "";
  return (
    <>
      <header className={s.head}>
        <Face avatar={src(post.avatar)} icon={src(icon)} name={first} small={quoted} />
        <div className={s.who}>
          <span className={s.nameRow}>
            {outside(firstLink, s.name, <bdi>{first}</bdi>)}
            {post.verified ? (
              <span className={s.verified} title={t("chat.post.verified")}>
                <BadgeCheck size={quoted ? 14 : 16} aria-hidden="true" />
                <span className="visually-hidden">{t("chat.post.verified")}</span>
              </span>
            ) : null}
          </span>
          {second || post.subscribers ? (
            <span className={s.handleRow}>
              {second ? outside(reddit ? post.author_url : undefined, s.handle, <bdi>{second}</bdi>) : null}
              {post.subscribers ? <span className={s.handle}>{t("chat.post.subscribers", { count: post.subscribers, n: formatCompact(post.subscribers) })}</span> : null}
            </span>
          ) : null}
        </div>
        {quoted ? (
          date && outside(link, s.quoteWhen, date)
        ) : (
          outside(
            link,
            s.site,
            <>
              <SiteIcon src={src(icon)} />
              <span>{site}</span>
            </>,
            site ? t("chat.post.openOn", { site }) : undefined,
          )
        )}
      </header>
      {post.reply_to ? <div className={s.context}>{t("chat.post.replyingTo", { name: `@${post.reply_to}` })}</div> : null}
      {!reddit && post.community ? (
        <div className={s.context}>
          {outside(post.community_url, s.contextLink, t("chat.post.forwardedFrom", { name: post.community }))}
        </div>
      ) : null}
      {post.title ? <div className={s.title} dir="auto">{post.title}</div> : null}
      {/* a video's description is long and mostly links: the video comes first, its words after */}
      {video ? <Media post={post} link={link} compact={quoted} video={video} /> : null}
      {text ? (
        <Fold lines={quoted ? 3 : video ? 2 : 6} signature={text}>
          {words}
        </Fold>
      ) : null}
      {video ? null : <Media post={post} link={link} compact={quoted} />}
      {post.poll ? <Poll poll={post.poll} /> : null}
      {post.note ? (
        <div className={s.note}>
          <span className={s.noteHead}>
            <UsersRound size={14} aria-hidden="true" />
            {t("chat.post.note")}
          </span>
          <span dir="auto">{post.note}</span>
        </div>
      ) : null}
      {!quoted && post.quote ? (
        <div className={s.quote} data-platform={post.quote.platform}>
          <PostView post={post.quote} icon={icon} link={post.quote.url} quoted />
        </div>
      ) : null}
      {quoted ? null : (
        <footer className={s.foot}>
          <Counts post={post} />
          {date ? outside(link, s.when, date) : null}
        </footer>
      )}
    </>
  );
}

export function PostEmbed({ e }: { e: UnfurlResult }) {
  const post = e.post;
  if (!post) return null;
  const video = post.platform === "youtube" && isYouTubeID(e.video_id) ? { id: e.video_id, start: e.video_start } : undefined;
  return (
    <article className={s.post} data-platform={post.platform} data-testid="post-embed">
      <PostView post={post} icon={e.icon} link={post.url ?? e.url} quoted={false} video={video} />
    </article>
  );
}
