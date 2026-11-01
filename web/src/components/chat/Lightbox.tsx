import { useNoDownloads } from "@/lib/mediaDownloads";
import { ChevronLeft, ChevronRight, Download, ExternalLink, MessageSquare, X, ZoomIn, ZoomOut } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { AttachmentMeta } from "@/crypto/message";
import { PRIORITY, acquire, attachmentObjectURL, cachedURL, release } from "@/lib/mediaCache";
import { Spinner } from "@/components/common/Spinner";
import { useFocusTrap } from "@/components/common/useFocusTrap";
import { ZoomPan, type ZoomPanHandle } from "@/components/common/ZoomPan";
import { desktopBridge } from "@/lib/desktopBridge";
import { saveImage } from "@/lib/imageActions";
import { useUI } from "@/stores/ui";
import { imageMenu } from "./messageMenu";
import s from "./Lightbox.module.css";
import { t } from "@/i18n";

/** One picture the viewer can show: one already on screen, or an attachment it decrypts itself. */
export interface LightboxItem {
  name: string;
  type?: string | undefined;
  src?: string | undefined;
  att?: AttachmentMeta | undefined;
  /** Who sent it, under its name. */
  from?: string | undefined;
  /** Opens the message it came from (from search's gallery, where the message is not on screen). */
  jump?: (() => void) | undefined;
}

/** How far one press of an arrow moves a zoomed picture, as a share of the view. */
const PAN_STEP = 0.1;

/**
 * The address of the picture on show, decrypted if need be and held while it
 * is, and meanwhile `copy`: the small copy the chat showed of it, if it has
 * one here already, to look at until the picture itself arrives.
 */
function useItemSrc(item: LightboxItem): { src: string | null; failed: boolean; copy: string | null } {
  const att = item.att;
  const [url, setUrl] = useState<string | null>(() => item.src ?? (att ? (cachedURL(att.blob_id) ?? null) : null));
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    setFailed(false);
    if (item.src) return setUrl(item.src);
    if (!att) return setUrl(null);
    const have = cachedURL(att.blob_id);
    setUrl(have ?? null);
    if (have) return;
    let alive = true;
    // the one thing the person is waiting on goes ahead of everything else
    attachmentObjectURL(att, undefined, PRIORITY.open)
      .then((u) => alive && setUrl(u))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [item.src, att]);
  useEffect(() => {
    if (!url || !att || item.src) return;
    if (!acquire(att.blob_id, url)) return;
    return () => release(att.blob_id, url);
  }, [url, att, item.src]);
  const copyId = item.src ? undefined : att?.preview?.blob_id;
  const [copy, setCopy] = useState<string | null>(null);
  useEffect(() => {
    const u = copyId ? cachedURL(copyId) : undefined;
    if (!copyId || !u || !acquire(copyId, u)) return setCopy(null);
    setCopy(u);
    return () => release(copyId, u);
  }, [copyId]);
  return { src: url, failed, copy };
}

/**
 * The picture viewer: the picture as large as fits, zoomed with the wheel,
 * a pinch, a double click or + and −, and moved by dragging or with the
 * arrows once zoomed. Its controls sit in a bar of their own along the top,
 * with the picture's name; a message's other pictures are a step away with
 * the side arrows, ← and →, or a swipe.
 */
export function Lightbox({ items, start = 0, onClose }: { items: LightboxItem[]; start?: number; onClose: () => void }) {
  // a media channel that hides downloads offers no save, copy or open here either
  const noDownload = useNoDownloads();
  const count = items.length;
  const [index, setIndex] = useState(() => Math.min(Math.max(0, start), Math.max(0, count - 1)));
  const item = items[index] ?? items[0]!;
  const { src, failed, copy } = useItemSrc(item);
  const [scale, setScale] = useState(1);
  const zoom = useRef<ZoomPanHandle>(null);
  const root = useRef<HTMLDivElement>(null);
  const closeBtn = useRef<HTMLButtonElement>(null);
  const swipe = useRef<{ x: number; y: number; t: number; down: boolean } | null>(null);
  /** The fingers on the picture: a second one makes it a pinch, never a swipe. */
  const touches = useRef(new Set<number>());
  const stageRef = useRef<HTMLDivElement>(null);
  /** The picture follows a finger pulling it down, and the dark behind it lifts, until it is let go. */
  const follow = (dy: number | null, ms = 0) => {
    const stage = stageRef.current;
    const box = root.current;
    if (!stage || !box) return;
    stage.style.transition = ms ? `transform ${ms}ms cubic-bezier(0.2, 0.8, 0.2, 1)` : "none";
    box.style.transition = ms ? `background-color ${ms}ms ease` : "none";
    if (dy === null) {
      stage.style.transform = "";
      box.style.removeProperty("--lb-dim");
      return;
    }
    stage.style.transform = `translateY(${dy}px) scale(${Math.max(0.82, 1 - Math.abs(dy) / 2400)})`;
    box.style.setProperty("--lb-dim", String(Math.max(0.2, 1 - Math.abs(dy) / 500)));
  };
  // a dialog in every sense: focus goes in, stays in, and comes back to the picture after
  useFocusTrap(root, true, () => closeBtn.current);
  const rtl = typeof document !== "undefined" && document.documentElement.dir === "rtl";
  const step = (d: number) => {
    const next = index + d;
    if (next < 0 || next >= count) return;
    zoom.current?.reset();
    setIndex(next);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const k = e.key;
    if (k === "Escape") {
      // the viewer takes this Escape; the panels beside the chat do not close with it
      e.preventDefault();
      e.stopPropagation();
      if (scale > 1) zoom.current?.reset();
      else onClose();
      return;
    }
    if (k === "+" || k === "=") return void zoom.current?.zoomBy(1.4);
    if (k === "-") return void zoom.current?.zoomBy(1 / 1.4);
    if (k === "0") return void zoom.current?.reset();
    const arrows: Record<string, [number, number]> = { ArrowLeft: [PAN_STEP, 0], ArrowRight: [-PAN_STEP, 0], ArrowUp: [0, PAN_STEP], ArrowDown: [0, -PAN_STEP] };
    const move = arrows[k];
    if (!move) return;
    e.preventDefault();
    // zoomed in, the arrows move the picture; otherwise ← and → go through the pictures
    if (zoom.current?.panBy(move[0], move[1])) return;
    if (k === "ArrowLeft") step(rtl ? 1 : -1);
    else if (k === "ArrowRight") step(rtl ? -1 : 1);
  };

  const svg = item.type?.toLowerCase().startsWith("image/svg");
  // a clip plays where the picture would be, with its own controls instead of the zoom
  const video = !!item.type?.toLowerCase().startsWith("video/");
  const stop = (e: React.MouseEvent) => e.stopPropagation();
  return createPortal(
    // a layer, so the back gesture on a phone puts the picture away rather than leaving the page under it
    <div ref={root} className={s.lightbox} tabIndex={-1} onClick={onClose} onKeyDown={onKeyDown} role="dialog" aria-modal="true" aria-label={item.name} data-zoomed={scale > 1 || undefined} data-modal-layer="" data-testid="lightbox">
      <div className={s.top} onClick={stop}>
        <div className={s.info}>
          <span className={s.name} dir="auto" title={item.name}>{item.name}</span>
          <span className={s.sub}>
            {item.from ? <span dir="auto">{item.from}</span> : null}
            {count > 1 ? <span>{t("chat.lightbox.position", { n: index + 1, count })}</span> : null}
          </span>
        </div>
        <div className={s.tools}>
          {item.jump ? (
            <button type="button" className={s.tool} aria-label={t("chat.lightbox.goToMessage")} title={t("chat.lightbox.goToMessage")} onClick={item.jump} data-testid="lightbox-jump">
              <MessageSquare size={20} />
            </button>
          ) : null}
          {video ? null : (
          <span className={s.zoomTools}>
            <button type="button" className={s.tool} aria-label={t("chat.attachments.zoomOut")} title={t("chat.attachments.zoomOut")} onClick={() => zoom.current?.zoomBy(1 / 1.4)}><ZoomOut size={20} /></button>
            <button type="button" className={[s.tool, s.pct].join(" ")} aria-label={t("chat.lightbox.resetZoom")} title={t("chat.lightbox.resetZoom")} onClick={() => zoom.current?.reset()} disabled={scale === 1} data-testid="lightbox-zoom">{Math.round(scale * 100)}%</button>
            <button type="button" className={s.tool} aria-label={t("chat.attachments.zoomIn")} title={t("chat.attachments.zoomIn")} onClick={() => zoom.current?.zoomBy(1.4)}><ZoomIn size={20} /></button>
          </span>
          )}
          {/* an SVG opened on its own is a page in this app's origin that can reach out to other servers */}
          {src && !svg && !noDownload ? <a className={s.tool} href={src} target="_blank" rel="noopener noreferrer" onClick={(event) => {
            const desktop = desktopBridge();
            if (!desktop?.openOriginal) return;
            event.preventDefault();
            void (async () => {
              const response = await fetch(src);
              if (!response.ok) throw new Error("original unavailable");
              const file = await response.blob();
              await desktop.openOriginal!(new Uint8Array(await file.arrayBuffer()), file.type, item.name);
            })().catch(() => useUI.getState().toast(t("chat.attachments.imageUnloadable"), "error"));
          }} aria-label={t("chat.attachments.openOriginal")} title={t("chat.attachments.openOriginal")}><ExternalLink size={20} /></a> : null}
          {src && !noDownload ? <button type="button" className={s.tool} onClick={() => void saveImage(src, item.name).catch(() => useUI.getState().toast(t("chat.messageMenu.couldNotSaveThePicture"), "error"))} aria-label={t("chat.attachments.download")} title={t("chat.attachments.download")}><Download size={20} /></button> : null}
          <button ref={closeBtn} type="button" className={[s.tool, s.close].join(" ")} aria-label={t("chat.attachments.close")} title={t("chat.attachments.close")} onClick={onClose}><X size={22} /></button>
        </div>
      </div>
      <div
        ref={stageRef}
        className={s.stageWrap}
        // At its own size, a picture on a touch screen goes to the next one with a
        // sideways swipe, and is put away with one up or down, as a phone's
        // photos are. Not over a clip, where a drag is the scrubber's.
        onPointerDown={(e) => {
          if (e.pointerType === "touch") touches.current.add(e.pointerId);
          if (touches.current.size > 1) {
            // a second finger: the picture is being pinched, so it goes back where it was
            if (swipe.current?.down) follow(null, 160);
            swipe.current = null;
            return;
          }
          swipe.current = e.pointerType === "touch" && scale <= 1 && !video ? { x: e.clientX, y: e.clientY, t: e.timeStamp, down: false } : null;
        }}
        onPointerMove={(e) => {
          const from = swipe.current;
          if (!from) return;
          const dx = e.clientX - from.x;
          const dy = e.clientY - from.y;
          if (!from.down && Math.abs(dy) > 12 && Math.abs(dy) > Math.abs(dx) * 1.3) from.down = true;
          if (from.down) follow(dy);
        }}
        onPointerUp={(e) => {
          touches.current.delete(e.pointerId);
          const from = swipe.current;
          swipe.current = null;
          if (!from) return;
          const dx = e.clientX - from.x;
          const dy = e.clientY - from.y;
          if (from.down) {
            const speed = Math.abs(dy) / Math.max(1, e.timeStamp - from.t);
            if (Math.abs(dy) > 110 || speed > 0.7) {
              follow(dy + Math.sign(dy) * 240, 160);
              window.setTimeout(onClose, 150);
            } else follow(null, 220);
            return;
          }
          if (count < 2 || Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
          step((dx < 0) !== rtl ? 1 : -1);
        }}
        onPointerCancel={(e) => {
          touches.current.delete(e.pointerId);
          if (swipe.current?.down) follow(null, 220);
          swipe.current = null;
        }}
      >
        {video ? (
          <div key={index} className={s.stage} onClick={(e) => e.target === e.currentTarget && onClose()}>
            {src ? <video className={s.video} src={src} controls autoPlay playsInline onClick={stop} /> : <span className={s.pending} onClick={stop}>{failed ? t("chat.attachments.videoUnloadable") : <Spinner size={28} />}</span>}
          </div>
        ) : (
        <ZoomPan key={index} className={s.stage} handle={zoom} onZoom={setScale} onIdleClick={onClose}>
          {src || copy ? (
            // the copy first, then the picture itself in the same place once it is here
            <img
              src={src ?? copy!}
              alt={item.name}
              draggable={false}
              onContextMenu={(e) => {
                e.preventDefault();
                e.stopPropagation();
                const att = item.att;
                // copied and saved whole, even while the copy is what shows
                const full = !src && att ? () => attachmentObjectURL(att, undefined, PRIORITY.open) : undefined;
                if (noDownload) return;
                imageMenu.open({ src: src ?? copy!, name: item.name, ...(full ? { full } : {}) }, { x: e.clientX, y: e.clientY });
              }}
            />
          ) : (
            <span className={s.pending} onClick={stop}>{failed ? t("chat.attachments.imageUnloadable") : <Spinner size={28} />}</span>
          )}
        </ZoomPan>
        )}
      </div>
      {!src && copy && !failed && !video ? <span className={s.fullLoading} aria-hidden="true"><Spinner size={18} /></span> : null}
      {count > 1 && index > 0 ? (
        <button type="button" className={[s.side, s.prev].join(" ")} aria-label={t("chat.lightbox.previous")} title={t("chat.lightbox.previous")} onClick={(e) => { stop(e); step(-1); }}>
          <ChevronLeft size={28} />
        </button>
      ) : null}
      {count > 1 && index < count - 1 ? (
        <button type="button" className={[s.side, s.next].join(" ")} aria-label={t("chat.lightbox.next")} title={t("chat.lightbox.next")} onClick={(e) => { stop(e); step(1); }}>
          <ChevronRight size={28} />
        </button>
      ) : null}
    </div>,
    document.body,
  );
}
