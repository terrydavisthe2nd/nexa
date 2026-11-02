import { NoDownloads, useNoDownloads } from "@/lib/mediaDownloads";
import { Download, FileIcon, Play } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { DecryptError } from "@/crypto/keys";
import type { AttachmentMeta } from "@/crypto/message";
import { saveAttachment } from "@/lib/download";
import { canStream, openVideoStream, type VideoStream } from "@/lib/videoStream";
import { setFullSource } from "@/lib/imageActions";
import { INLINE_MAX, PRIORITY, acquire, attachmentObjectURL, cachedURL, prioritize, release } from "@/lib/mediaCache";
import { copyOf } from "@/lib/pictureCopy";
import { watchPlace, type ScreenPlace } from "@/hooks/useNearScreen";
import { formatBytes } from "@/lib/time";
import { useUI } from "@/stores/ui";
import { Spinner } from "@/components/common/Spinner";
import { AudioPlayer } from "./AudioPlayer";
import { VoiceMessage } from "./VoiceMessage";
import { FavoriteButton } from "./FavoriteButton";
import { Lightbox, type LightboxItem } from "./Lightbox";
import { VideoPlayer } from "./VideoPlayer";
import { fitMedia, fitVideo } from "./fitMedia";
import s from "./Chat.module.css";

import { t as tr_ } from "@/i18n";
export { fitMedia };

/**
 * Media up to this size loads by itself as it nears the screen. Anything
 * bigger waits to be asked for, since every copy of the app showing the
 * message would otherwise download and decrypt all of it.
 */
const AUTO_LOAD_MAX = 25 * 1024 * 1024;
/**
 * How far off screen a loaded one may drift before it lets go of its
 * decrypted copy. Kept while mounted, every picture scrolled past stayed in
 * memory for as long as the conversation was open, and a long photo channel
 * on a phone ended in the tab being killed. Let go, it can be evicted, and
 * coming back is instant while it has not been.
 */
const FAR = "2500px 0px";

type LoadError = "load" | "decrypt";

/**
 * Decrypts an attachment while it is near the screen (`auto`) or once asked
 * to (`load`), and lets go of it again when it is far away — unless it is
 * playing, which it goes on doing out of view. What is on screen is fetched
 * before what is only near it (lib/mediaCache.ts).
 */
function useDecrypted(att: AttachmentMeta, ref: RefObject<HTMLElement | null>, auto: boolean): { url: string | null; error: LoadError | null; wanted: boolean; load: () => void; retry: () => void } {
  const [url, setUrl] = useState<string | null>(cachedURL(att.blob_id) ?? null);
  const [error, setError] = useState<LoadError | null>(null);
  const [wanted, setWanted] = useState(false);
  const started = useRef(false);
  /** Where it is: on screen, near it, or far. Asked for by hand counts as on screen. */
  const place = useRef<ScreenPlace>("far");
  /**
   * Withdraws this one's wait for the download under way. The download stops
   * once nothing else is waiting on it: a conversation left behind must not
   * keep the line busy ahead of the one opened.
   */
  const stop = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      stop.current?.abort();
      started.current = false;
    },
    [],
  );
  // Another file in the same place (a picture's copy giving way to the
  // picture): start again, and let whatever was on its way for the old one go.
  const shownId = useRef(att.blob_id);
  useEffect(() => {
    if (shownId.current === att.blob_id) return;
    shownId.current = att.blob_id;
    stop.current?.abort();
    started.current = false;
    setUrl(cachedURL(att.blob_id) ?? null);
    setError(null);
  }, [att.blob_id]);
  useEffect(() => {
    if (!wanted || url || error || started.current) return;
    started.current = true;
    const ctrl = new AbortController();
    stop.current = ctrl;
    // Not dropped when the effect runs again: a render that only rebuilt the
    // attachment's object must not orphan the download it already started.
    attachmentObjectURL(att, ctrl.signal, place.current === "visible" ? PRIORITY.visible : PRIORITY.near)
      .then((u) => {
        if (ctrl.signal.aborted) return;
        started.current = false;
        setUrl(u);
      })
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        // a dropped connection is worth another try, which clearing the error starts
        started.current = false;
        setError(err instanceof DecryptError ? "decrypt" : "load");
      });
  }, [wanted, url, error, att]);
  // Hold the URL while this is on screen, so the cache does not revoke it
  // under a download link or a video that is still reading from it.
  useEffect(() => {
    if (!url) return;
    if (!acquire(att.blob_id, url)) {
      started.current = false;
      setUrl(null);
      return;
    }
    return () => release(att.blob_id, url);
  }, [url, att.blob_id]);
  // Near and far are measured against the message list that scrolls, not the
  // window: seen from the window, everything the list has scrolled out of its
  // box is clipped away, however near it is.
  // near the screen: wanted, when it loads by itself; on it, wanted sooner
  const id = att.blob_id;
  useEffect(() => {
    const el = ref.current;
    if (!auto || !el) return;
    return watchPlace(el, el.closest("[data-scroller]"), (where) => {
      place.current = where;
      if (where === "far") return;
      setWanted(true);
      if (where === "visible") prioritize(id, PRIORITY.visible);
    });
  }, [ref, auto, id]);
  // far from it: let go, unless it is playing
  useEffect(() => {
    const el = ref.current;
    if (!url || !el || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) return;
      const media = el.querySelector<HTMLMediaElement>("video, audio");
      if (media && !media.paused) return;
      setUrl(null);
      setWanted(false);
    }, { root: el.closest("[data-scroller]"), rootMargin: FAR });
    io.observe(el);
    return () => io.disconnect();
  }, [ref, url]);
  const load = useCallback(() => {
    place.current = "visible";
    setWanted(true);
  }, []);
  const retry = useCallback(() => setError(null), []);
  return { url, error, wanted, load, retry };
}

/** Why an attachment is not showing, with a way to try again when that could help. */
/** What failed, said per kind: a word slotted into one sentence reads wrong in most languages. */
const FAILED = {
  image: ["chat.attachments.imageUndecryptable", "chat.attachments.imageUnloadable"],
  video: ["chat.attachments.videoUndecryptable", "chat.attachments.videoUnloadable"],
  audio: ["chat.attachments.audioUndecryptable", "chat.attachments.audioUnloadable"],
} as const;

export function LoadFailed({ error, what, onRetry }: { error: LoadError; what: keyof typeof FAILED; onRetry: () => void }) {
  return (
    <span className={s.loadFailed}>
      {tr_(FAILED[what][error === "decrypt" ? 0 : 1])}
      {error === "load" ? (
        <button
          className={s.retry}
          onClick={(e) => {
            e.stopPropagation();
            onRetry();
          }}
        >
          {tr_("chat.messageItem.retry")}
        </button>
      ) : null}
    </span>
  );
}

/** A picture's place in a message's grid of pictures: how many of its six columns and rows it spans, and its shape. */
export interface Tile {
  col: number;
  row: number;
  /** Left out for a tile as tall as the rows beside it. */
  ratio?: string;
}

/**
 * Where each of a message's pictures goes when it has more than one, on a
 * grid of six columns filled a row at a time: two side by side, three as one
 * large beside two small, four two by two, and more three to a row with the
 * one or two left over across the top. Every tile's shape is known before its
 * picture arrives, so the message never jumps as they come in.
 */
export function mosaicLayout(n: number): Tile[] {
  const square = { col: 2, row: 1, ratio: "1 / 1" };
  const half = { col: 3, row: 1, ratio: "3 / 2" };
  if (n <= 1) return [];
  if (n === 2) return [{ col: 3, row: 1, ratio: "1 / 1" }, { col: 3, row: 1, ratio: "1 / 1" }];
  if (n === 3) return [{ col: 4, row: 2 }, square, square];
  if (n === 4) return [half, half, half, half];
  const over = n % 3;
  const top = over === 1 ? [{ col: 6, row: 1, ratio: "2 / 1" }] : over === 2 ? [half, half] : [];
  return [...top, ...Array.from({ length: n - over }, () => square)];
}

function ImageAttachment({ att, onOpen, tile, fill }: { att: AttachmentMeta; onOpen: () => void; tile?: Tile; fill?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  // A big picture shows the small copy its sender made (lib/pictureCopy.ts),
  // which comes down in a moment whatever the picture's own size. A copy that
  // turns out unreadable gives way to the picture itself.
  const [copyFailed, setCopyFailed] = useState(false);
  const copy = useMemo(() => (copyFailed ? null : copyOf(att)), [att, copyFailed]);
  const shown = copy ?? att;
  const auto = !!copy || att.size <= AUTO_LOAD_MAX;
  const { url, error, wanted, load, retry } = useDecrypted(shown, ref, auto);
  useEffect(() => {
    if (copy && error === "decrypt") setCopyFailed(true);
  }, [copy, error]);
  const [revealed, setRevealed] = useState(!att.spoiler);
  const dims = fitMedia(att.w, att.h);
  const open = () => {
    if (url) onOpen();
  };
  // in a grid the tile gives the shape, and the picture is cut to fill it
  // a note's picture is as wide as the note gives it, in its own shape
  const box = tile
    ? { gridColumn: `span ${tile.col}`, gridRow: `span ${tile.row}`, ...(tile.ratio ? { aspectRatio: tile.ratio } : {}) }
    : fill
      ? { width: "100%", aspectRatio: att.w && att.h ? `${att.w} / ${att.h}` : "4 / 3" }
      : { width: dims.width, aspectRatio: `${dims.width} / ${dims.height}` };
  return (
    <div ref={ref} className={s.imgWrap} data-tile={tile ? "" : undefined} data-spoiler={!revealed} style={box} onClick={() => (revealed ? open() : setRevealed(true))}>
      {att.type === "image/gif" && revealed ? <FavoriteButton url={`attach:${att.blob_id}`} w={att.w} h={att.h} att={att} className={s.favStar} /> : null}
      {url ? (
        // the picture is the way into the viewer, for a keyboard as for a pointer
        <img
          // drawn off the main thread, so a screenful of pictures arriving together does not stall scrolling
          decoding="async"
          // copying or saving it takes the picture itself, not its copy
          ref={(el) => {
            if (el) setFullSource(el, copy ? () => attachmentObjectURL(att, undefined, PRIORITY.open) : null);
          }}
          src={url}
          alt={att.alt || att.name}
          width={dims.width}
          height={dims.height}
          data-image-src={url}
          data-image-name={att.name}
          role={revealed ? "button" : undefined}
          tabIndex={revealed ? 0 : -1}
          aria-haspopup={revealed ? "dialog" : undefined}
          onKeyDown={(e) => {
            if (e.key !== "Enter" && e.key !== " ") return;
            e.preventDefault();
            open();
          }}
        />
      ) : (
        <div className={s.imgPlaceholder}>
          {error ? (
            <LoadFailed error={error} what="image" onRetry={retry} />
          ) : wanted || auto ? (
            // a webhook's file is fetched as it is, with nothing to decrypt
            att.plain ? tr_("chat.pins.loading") : tr_("chat.attachments.decryptingShort")
          ) : (
            <button
              className={s.videoLoad}
              onClick={(e) => {
                // under a spoiler, the first click only uncovers it
                if (!revealed) return;
                e.stopPropagation();
                load();
              }}
            >
              {tr_("chat.attachments.loadImage", { size: formatBytes(att.size) })}
            </button>
          )}
        </div>
      )}
      {!revealed ? (
        <button
          type="button"
          className={s.spoilerTag}
          aria-label={tr_("render.spoilerHidden")}
          onClick={(e) => {
            e.stopPropagation();
            setRevealed(true);
          }}
        >
          <span>{tr_("composer.composer.spoiler")}</span>
        </button>
      ) : null}
    </div>
  );
}

/** The box a video waits in, the same shape as the player that takes its place, so nothing jumps. */
function videoBox(att: AttachmentMeta, fill?: boolean): React.CSSProperties {
  if (fill) return { width: "100%", aspectRatio: att.w && att.h ? `${att.w} / ${att.h}` : "16 / 9" };
  const dims = fitVideo(att.w ?? 640, att.h ?? 360);
  return { width: dims.width, aspectRatio: `${dims.width} / ${dims.height}` };
}

/**
 * A video, played while it downloads where the download worker can stream it
 * (lib/videoStream.ts), and downloaded whole first where it cannot.
 */
function VideoAttachment({ att, fill }: { att: AttachmentMeta; fill?: boolean }) {
  const [whole, setWhole] = useState(() => !canStream());
  const fallBack = useCallback(() => setWhole(true), []);
  if (!whole) return <StreamedVideo att={att} fill={fill} onFallBack={fallBack} />;
  // too big to hold in memory, which playing it whole means
  if (att.size > INLINE_MAX) return <FileAttachment att={att} />;
  return <WholeVideo att={att} fill={fill} />;
}

/**
 * A video that plays while it downloads, a chunk at a time, however big it
 * is. It is set up as it nears the screen and let go again once it is far off
 * and not playing. A small one shows its first frame and length straight
 * away, as it did when it loaded by itself; a big one loads nothing until it
 * is played.
 */
function StreamedVideo({ att, fill, onFallBack }: { att: AttachmentMeta; fill?: boolean; onFallBack: () => void }) {
  const noDownload = useNoDownloads();
  const ref = useRef<HTMLDivElement>(null);
  const [near, setNear] = useState(false);
  const [stream, setStream] = useState<VideoStream | null>(null);
  const toast = useUI((u) => u.toast);
  // a render that only rebuilt the attachment's object must not start the stream again
  const latest = useRef({ att, onFallBack });
  latest.current = { att, onFallBack };
  const id = att.blob_id;
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    return watchPlace(el, el.closest("[data-scroller]"), (where) => {
      if (where !== "far") setNear(true);
    });
  }, []);
  // far from the screen and not playing: let go of it, frame and all
  useEffect(() => {
    const el = ref.current;
    if (!stream || !el || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) return;
      const media = el.querySelector("iframe")?.contentDocument?.querySelector("video");
      if (media && !media.paused) return;
      setNear(false);
    }, { root: el.closest("[data-scroller]"), rootMargin: FAR });
    io.observe(el);
    return () => io.disconnect();
  }, [stream]);
  useEffect(() => {
    if (!near) return;
    let gone = false;
    let opened: VideoStream | null = null;
    const { att: a } = latest.current;
    void openVideoStream(a, a.size <= AUTO_LOAD_MAX ? "metadata" : "none").then((s) => {
      if (gone) {
        s?.close();
        return;
      }
      if (!s) {
        latest.current.onFallBack();
        return;
      }
      opened = s;
      setStream(s);
    });
    return () => {
      gone = true;
      opened?.close();
      setStream(null);
    };
  }, [near, id]);
  const save = () =>
    void saveAttachment(latest.current.att).catch((err: unknown) => toast(err instanceof Error && err.message ? err.message : tr_("chat.attachments.couldNotDownloadThatFile"), "error"));
  // The stream failing (the file not coming, or not decrypting) is worth the
  // whole download; a browser that cannot play what it was given is not, and
  // the player says so with a way to save the file.
  const failed = useCallback(async () => {
    // the worker's word on the stream can land a moment after the player's error
    await new Promise((r) => setTimeout(r, 400));
    if (!stream?.failed()) return false;
    latest.current.onFallBack();
    return true;
  }, [stream]);
  return (
    <div ref={ref} className={s.mediaSlot}>
      {stream ? (
        <VideoPlayer frame={stream.frame} name={att.name} width={att.w} height={att.h} spoiler={att.spoiler ?? false} fill={fill} onDownload={noDownload ? undefined : save} onFail={failed} sizeHint={formatBytes(att.size)} />
      ) : (
        <div className={s.videoPending} style={videoBox(att, fill)} />
      )}
    </div>
  );
}

/** A video downloaded and decrypted whole before it plays: where nothing can stream it. */
function WholeVideo({ att, fill }: { att: AttachmentMeta; fill?: boolean }) {
  // the whole file has to come down and be decrypted before it can play at all
  const ref = useRef<HTMLDivElement>(null);
  const auto = att.size <= AUTO_LOAD_MAX;
  // Waiting for the row to near the screen, as pictures do. Starting on mount
  // downloaded and decrypted every clip in the loaded history at once, each of
  // them held in memory from that moment on.
  const { url, error, wanted, load, retry } = useDecrypted(att, ref, auto);
  return (
    <div ref={ref} className={s.mediaSlot}>
      {url ? (
        <VideoPlayer src={url} name={att.name} width={att.w} height={att.h} spoiler={att.spoiler ?? false} fill={fill} />
      ) : (
        <div className={s.videoPending} style={videoBox(att, fill)}>
          {error ? (
            <LoadFailed error={error} what="video" onRetry={retry} />
          ) : wanted || auto ? (
            <span className={s.videoLoading}><Spinner size={20} /> {tr_(att.plain ? "chat.attachments.loadingNamed" : "chat.attachments.decryptingNamed", { name: att.name, size: formatBytes(att.size) })}</span>
          ) : (
            <button className={s.videoLoad} onClick={load}>
              <Play size={20} /> {tr_("chat.attachments.loadVideo", { size: formatBytes(att.size) })}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/** A voice message recorded in the app: always fetched by itself (they are small), drawn whole from the start. */
function VoiceAttachment({ att }: { att: AttachmentMeta }) {
  const ref = useRef<HTMLDivElement>(null);
  const { url, error, wanted, load, retry } = useDecrypted(att, ref, true);
  return (
    <div ref={ref} className={s.audio}>
      {error && !url ? (
        <span className={s.fileSize}><LoadFailed error={error} what="audio" onRetry={retry} /></span>
      ) : (
        <VoiceMessage src={url} duration={att.duration} wave={att.wave} loading={wanted || !url} onLoad={load} />
      )}
    </div>
  );
}

function AudioAttachment({ att }: { att: AttachmentMeta }) {
  const ref = useRef<HTMLDivElement>(null);
  const auto = att.size <= AUTO_LOAD_MAX;
  const { url, error, wanted, load, retry } = useDecrypted(att, ref, auto);
  return (
    <div ref={ref} className={s.audio}>
      {url ? (
        <AudioPlayer src={url} name={att.name} size={att.size} duration={att.duration} waveKey={att.blob_id} />
      ) : error ? (
        <span className={s.fileSize}><LoadFailed error={error} what="audio" onRetry={retry} /></span>
      ) : wanted || auto ? (
        <span className={s.fileSize}>{tr_(att.plain ? "chat.attachments.loadingFile" : "chat.attachments.decrypting", { name: att.name })}</span>
      ) : (
        <button className={s.videoLoad} onClick={load}>
          <Play size={16} /> {tr_("chat.attachments.loadNamed", { name: att.name, size: formatBytes(att.size) })}
        </button>
      )}
    </div>
  );
}

function FileAttachment({ att }: { att: AttachmentMeta }) {
  const toast = useUI((u) => u.toast);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(0);
  const download = async () => {
    if (busy) return;
    setBusy(true);
    setDone(0);
    try {
      await saveAttachment(att, (n) => setDone(n));
    } catch (err) {
      toast(err instanceof Error && err.message ? err.message : tr_("chat.attachments.couldNotDownloadThatFile"), "error");
    } finally {
      setBusy(false);
    }
  };
  const percent = att.size > 0 ? Math.min(100, Math.floor((done / att.size) * 100)) : 0;
  return (
    <div className={s.file}>
      <FileIcon size={36} className={s.fileIcon} aria-hidden="true" />
      <div className={s.fileText}>
        {/* the name downloads too; the button beside it is the one a keyboard reaches */}
        <button type="button" className={s.fileName} onClick={() => void download()} tabIndex={-1} dir="auto" title={att.name}>{att.name}</button>
        <span className={s.fileSize}>{busy ? tr_("chat.attachments.downloadingPercent", { percent, size: formatBytes(att.size) }) : formatBytes(att.size)}</span>
      </div>
      <button className={s.dl} aria-label={tr_("chat.attachments.downloadNamed", { name: att.name })} title={tr_("chat.attachments.download")} onClick={() => void download()} disabled={busy}>{busy ? <Spinner size={20} /> : <Download size={20} />}</button>
    </div>
  );
}

/**
 * A message's files, or one of a note's (`fill`: as wide as the note gives
 * it, a picture in its own shape, rather than fitted into a message's frame).
 */
export function Attachments({ list, from, fill, noDownload = false }: { list: AttachmentMeta[]; from?: string; fill?: boolean; noDownload?: boolean }) {
  // The message's pictures are one set in the viewer, a step apart. A spoiler
  // is left out unless it is the one opened: stepping onto it would give it away.
  const [viewing, setViewing] = useState<string | null>(null);
  const pictures = useMemo(() => list.filter((a) => a.size <= INLINE_MAX && (a.type || "").startsWith("image/")), [list]);
  const gallery: LightboxItem[] = useMemo(
    () => pictures.filter((a) => !a.spoiler || a.blob_id === viewing).map((a) => ({ name: a.name, type: a.type, att: a, from })),
    [pictures, viewing, from],
  );
  // two pictures or more share one grid, in the order they were sent, ahead of the message's other files
  const tiles = pictures.length > 1 ? mosaicLayout(pictures.length) : null;
  return (
    <NoDownloads.Provider value={noDownload}>
    <div className={s.att}>
      {tiles ? (
        <div className={s.mosaic} data-count={pictures.length} data-testid="picture-grid">
          {pictures.map((att, i) => (
            <ImageAttachment key={att.blob_id} att={att} tile={tiles[i]} onOpen={() => setViewing(att.blob_id)} />
          ))}
        </div>
      ) : null}
      {list.map((att) => {
        const t = att.type || "";
        // showing or playing a file means holding all of it, so past a point it is only offered as a download;
        // a video that streams is never held whole, however big
        const inline = att.size <= INLINE_MAX;
        if (inline && t.startsWith("image/")) return tiles ? null : <ImageAttachment key={att.blob_id} att={att} onOpen={() => setViewing(att.blob_id)} fill={fill} />;
        if ((inline || canStream()) && t.startsWith("video/")) return <VideoAttachment key={att.blob_id} att={att} fill={fill} />;
        if (inline && t.startsWith("audio/")) return att.voice ? <VoiceAttachment key={att.blob_id} att={att} /> : <AudioAttachment key={att.blob_id} att={att} />;
        return <FileAttachment key={att.blob_id} att={att} />;
      })}
      {viewing ? <Lightbox items={gallery} start={Math.max(0, gallery.findIndex((g) => g.att?.blob_id === viewing))} onClose={() => setViewing(null)} /> : null}
    </div>
    </NoDownloads.Provider>
  );
}
