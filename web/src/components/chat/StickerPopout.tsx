import { openReport } from "@/lib/reporting";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { expressions } from "@/api/expressions";
import type { StickerInfo } from "@/api/types";
import { stickerUrl } from "@/api/users";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { StickerView } from "@/components/stickers/StickerView";
import type { MessagePayload } from "@/crypto/message";
import { portalRoot } from "@/hooks/usePortalRoot";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import s from "./Sticker.module.css";

import { t } from "@/i18n";
type MessageSticker = NonNullable<MessagePayload["sticker"]>;

const GAP = 8;
/** How long what a sticker's pack looks like is trusted before asking again. */
const INFO_TTL = 60_000;
const infoCache = new Map<string, { at: number; info: Promise<StickerInfo | null> }>();

/** The public pack a sticker comes from, asked for once a minute at most. */
function stickerInfo(id: string): Promise<StickerInfo | null> {
  const hit = infoCache.get(id);
  if (hit && Date.now() - hit.at < INFO_TTL) return hit.info;
  const info = expressions.stickerInfo(id).catch(() => {
    // a failed lookup is not remembered
    infoCache.delete(id);
    return null;
  });
  infoCache.set(id, { at: Date.now(), info });
  return info;
}

/** Beside the sticker: above it when there is room, else below, always on screen. */
function place(anchor: DOMRect, box: DOMRect): { left: number; top: number } {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  let top = anchor.top - box.height - GAP;
  if (top < 4) top = anchor.bottom + GAP;
  const left = anchor.left + anchor.width / 2 - box.width / 2;
  return {
    left: Math.max(4, Math.min(left, vw - box.width - 4)),
    top: Math.max(4, Math.min(top, vh - box.height - 4)),
  };
}

/**
 * What a click on a sticker in a message shows: its name and picture, and
 * when its pack is public, a way to see the whole pack. A private pack's
 * sticker stays just the name and picture the message already has.
 */
export function StickerPopout({ sticker, anchor, onClose }: { sticker: MessageSticker; anchor: HTMLElement; onClose: () => void }) {
  useMediaAnimation();
  const card = useRef<HTMLDivElement>(null);
  const [at, setAt] = useState<{ left: number; top: number } | null>(null);
  const [info, setInfo] = useState<StickerInfo | null | undefined>(undefined);
  const openModal = useUI((u) => u.openModal);
  const name = sticker.name || t("attachmentViews.sticker");

  useEffect(() => {
    let alive = true;
    void stickerInfo(sticker.id).then((found) => {
      if (!alive) return;
      if (found) useUsers.getState().upsert(found.users);
      setInfo(found);
    });
    return () => {
      alive = false;
    };
  }, [sticker.id]);

  // Focus goes into the card once it is placed, and back to the sticker when
  // it closes: the card lives at the end of the page, where Tab never reached it.
  const focused = useRef(false);
  const inside = useRef(false);
  useEffect(() => {
    if (!at || focused.current) return;
    focused.current = true;
    card.current?.focus({ preventScroll: true });
  }, [at]);
  useEffect(
    () => () => {
      if (inside.current && anchor.isConnected) anchor.focus({ preventScroll: true });
    },
    [anchor],
  );

  const reposition = useCallback(() => {
    const box = card.current?.getBoundingClientRect();
    const a = anchor.getBoundingClientRect();
    if (!box) return;
    // a sticker scrolled out of sight takes its popout with it
    if (!anchor.isConnected || a.bottom < 0 || a.top > window.innerHeight) {
      onClose();
      return;
    }
    setAt(place(a, box));
  }, [anchor, onClose]);
  useLayoutEffect(reposition, [reposition, info]);

  useEffect(() => {
    const down = (e: PointerEvent) => {
      const t = e.target as Node;
      // the sticker itself toggles the popout, so a press on it is left to its click
      if (card.current?.contains(t) || anchor.contains(t)) return;
      onClose();
    };
    const key = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.stopPropagation();
      onClose();
    };
    document.addEventListener("pointerdown", down, true);
    document.addEventListener("keydown", key, true);
    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);
    return () => {
      document.removeEventListener("pointerdown", down, true);
      document.removeEventListener("keydown", key, true);
      window.removeEventListener("resize", reposition);
      window.removeEventListener("scroll", reposition, true);
    };
  }, [anchor, onClose, reposition]);

  return createPortal(
    <div ref={card} className={s.popout} role="dialog" aria-label={name} tabIndex={-1} onFocus={() => (inside.current = true)} onBlur={(e) => (inside.current = !!card.current?.contains(e.relatedTarget as Node))} data-testid="sticker-popout" style={at ? { left: at.left, top: at.top } : { left: -9999, top: -9999 }}>
      <div className={s.head}>
        {sticker.blob ? <StickerView format={sticker.format ?? info?.sticker.format} src={stickerUrl(sticker.blob, 64)} alt="" className={s.preview} /> : null}
        <div className={s.text}>
          <div className={s.name}>{name}</div>
          {info ? <div className={s.sub}>{t("chat.stickerPopout.fromPack", { name: info.pack.name })}</div> : null}
        </div>
      </div>
      <Button variant="ghost" size="sm" block onClick={() => { onClose(); openReport({ kind: "sticker", id: sticker.id }); }}>{t("reports.report")}</Button>
      {info === undefined ? (
        <div className={s.pending}><Spinner size={16} /></div>
      ) : info ? (
        <Button
          size="sm"
          block
          data-testid="view-sticker-pack"
          onClick={() => {
            onClose();
            openModal("stickerPack", { packId: info.pack.id, pack: info.pack, stickerId: sticker.id });
          }}
        >
          {t("chat.stickerPopout.viewPack")}
        </Button>
      ) : null}
    </div>,
    portalRoot(),
  );
}
