import { openReport } from "@/lib/reporting";
import { PersonName } from "@/components/common/PersonName";
import { Check, Copy, Flag, Star } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "@/api/client";
import { expressions } from "@/api/expressions";
import type { Sticker, StickerPack } from "@/api/types";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { Spinner } from "@/components/common/Spinner";
import { StickerView } from "@/components/stickers/StickerView";
import { LazySticker } from "@/components/stickers/StickerTile";
import { packCount, packCover, packUrl } from "@/components/server/PackEditor";
import { copyText } from "@/lib/copyText";
import { useDominantColor } from "@/lib/dominantColor";
import { avatarColor } from "@/lib/avatarColor";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { canRemovePack, confirmRemovePack } from "@/lib/packRemoval";
import { toggleFavoriteSticker } from "@/lib/stickerFavorites";
import { useAuth } from "@/stores/auth";
import { packImageUrl, useExpressions } from "@/stores/expressions";
import { setSharedPack } from "@/lib/packLinks";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { displayName } from "@/stores/users";
import s from "./StickerPackModal.module.css";

import { t, Tr } from "@/i18n";

/**
 * A whole pack: the one chosen drawn big and moving, every sticker in it by
 * name, who made it and the one thing to do with it. Whatever opened it passes
 * what it already knows, and the rest arrives from the server.
 */
export function StickerPackModal({ onClose, packId, pack: given, stickerId }: { onClose: () => void; packId: string; pack?: StickerPack | undefined; stickerId?: string | undefined }) {
  useMediaAnimation();
  const meId = useAuth((a) => a.me?.id);
  const stored = useExpressions((x) => x.packs[packId]);
  const favorites = useSettings((x) => x.settings.favoriteStickers);
  const openModal = useUI((u) => u.openModal);
  const toast = useUI((u) => u.toast);
  const [loaded, setLoaded] = useState<StickerPack | null>(null);
  const [failed, setFailed] = useState(false);
  const [picked, setPicked] = useState<string | null>(stickerId ?? null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    let alive = true;
    expressions
      .pack(packId)
      .then((p) => alive && setLoaded(p))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [packId]);

  // what the server just said, then this account's copy, then the preview it was opened from
  const base = loaded ?? stored ?? given ?? null;
  const pack = base ? { ...base, installed: stored?.installed ?? base.installed } : null;
  const current: Sticker | undefined = pack ? (pack.stickers.find((x) => x.id === picked) ?? packCover(pack)) : undefined;
  const tintFrom = pack && current ? packImageUrl(pack, current, 96) + (current.animated || current.format === "webm" || current.format === "tgs" ? "&still=1" : "") : null;
  const tint = useDominantColor(tintFrom);
  const color = tint ?? avatarColor(packId);

  if (!pack) {
    return (
      <Modal open onClose={onClose} title={failed ? t("stickerPack.packUnavailable") : t("stickerPack.loading")} width={480}>
        {failed ? <p className={s.note}>{t("stickerPack.thisPackIsPrivate")}</p> : <div className={s.loading}><Spinner /></div>}
      </Modal>
    );
  }

  const mine = !!meId && pack.owner_user_id === meId;
  const emoji = pack.kind === "emoji";
  const removable = canRemovePack(pack, meId);
  // a favourite only comes back from a pack the picker has
  const usable = !emoji && !!stored;
  const favorite = !!current && favorites.includes(current.id);

  const install = async () => {
    setBusy(true);
    try {
      const res = await expressions.install(pack.id);
      useExpressions.getState().upsertPack(res);
      setSharedPack(res);
      setLoaded(res);
      toast(t(emoji ? "modals.stickerPack.isInstalledEmoji" : "modals.stickerPack.isInstalled", { name: pack.name }), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("modals.stickerPack.couldNotInstallThePack"), "error");
    } finally {
      setBusy(false);
    }
  };
  const copyLink = () => {
    void copyText(packUrl(pack.id)).then((ok) => {
      if (!ok) return toast(t("common.copyFailed"), "error");
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const action = mine ? (
    <Button variant="secondary" onClick={() => { onClose(); openModal("settings", { page: "stickers", packId: pack.id }); }}>{t("stickerPack.editPack")}</Button>
  ) : pack.installed && removable ? (
    <Button variant="secondary" onClick={() => confirmRemovePack(pack, onClose)} data-testid="sticker-pack-remove">{t("server.packEditor.removePack")}</Button>
  ) : pack.installed ? (
    <Button variant="secondary" disabled data-testid="sticker-pack-installed"><Check size={16} aria-hidden="true" /> {t("settings.stickers.installed")}</Button>
  ) : pack.public ? (
    <Button loading={busy} onClick={() => void install()} data-testid="install-sticker-pack">{t("stickerPack.installPack")}</Button>
  ) : null;

  return (
    <Modal
      open
      onClose={onClose}
      title={pack.name}
      width={760}
      className={s.modal}
      footer={
        <>
          <span className={s.footStart}><Button variant="ghost" onClick={() => openReport({ kind: "pack", id: pack.id })}>{t("reports.report")}</Button></span>
          {pack.public ? <Button variant="ghost" onClick={copyLink} aria-label={t("server.packEditor.copyLink")} title={t("server.packEditor.copyLink")}>{copied ? <Check size={16} aria-hidden="true" /> : <Copy size={16} aria-hidden="true" />} <span className={s.copyWords}>{copied ? t("modals.groupInvite.copied") : t("server.packEditor.copyLink")}</span></Button> : null}
          {action}
        </>
      }
    >
      <div className={s.layout} data-testid="sticker-pack" style={{ "--tint": color } as React.CSSProperties}>
        <aside className={s.stage}>
          {current ? <StickerView key={current.id} format={current.format} src={packImageUrl(pack, current)} alt={current.name} className={emoji ? s.bigEmoji : s.big} eager /> : null}
          {current ? (
            <div className={s.stageText}>
              <div className={s.stickerName} data-testid="sticker-pack-name" dir="auto">{emoji ? `:${current.name}:` : current.name}</div>
              {current.tags ? <div className={s.tags} dir="auto">{current.tags}</div> : null}
            </div>
          ) : null}
          {current ? (
            <div className={s.stageActions}>
              {usable ? (
                <button type="button" className={s.stageBtn} data-on={favorite || undefined} onClick={() => toggleFavoriteSticker(current.id)} aria-pressed={favorite} data-testid="sticker-pack-favorite">
                  <Star size={15} fill={favorite ? "currentColor" : "none"} aria-hidden="true" />
                  {favorite ? t("pickers.gifPicker.removeFromFavorites") : t("pickers.gifPicker.addToFavorites")}
                </button>
              ) : null}
              <button type="button" className={s.stageBtn} onClick={() => openReport({ kind: "sticker", id: current.id })}>
                <Flag size={15} aria-hidden="true" /> {t("pickers.stickerPicker.reportSticker")}
              </button>
            </div>
          ) : null}
        </aside>
        <div className={s.main}>
          <header className={s.head}>
            <div className={s.facts}>
              <span>{packCount(pack)}</span>
              {pack.install_count ? <span>{t("stickerPack.installs", { count: pack.install_count })}</span> : null}
              {pack.owner ? <span><Tr id="stickerPack.madeBy" values={{ name: <button type="button" className={s.creatorLink} onClick={() => openModal("profile", { userId: pack.owner!.id })}><PersonName user={pack.owner}>{displayName(pack.owner)}</PersonName></button> }} /></span> : null}
            </div>
            {pack.description ? <p className={s.desc} dir="auto">{pack.description}</p> : null}
          </header>
          <div className={s.grid} data-kind={pack.kind} data-scroll-root="" data-testid="sticker-pack-grid">
            {pack.stickers.map((x) => (
              <button key={x.id} type="button" className={s.tile} data-on={x.id === current?.id} onClick={() => setPicked(x.id)} title={x.name} aria-label={x.name} aria-pressed={x.id === current?.id}>
                <LazySticker sticker={x} pack={pack} alt="" className={s.tileArt} />
                <span className={s.tileName} dir="auto">{x.name}</span>
              </button>
            ))}
            {!loaded && !failed ? <div className={s.loadingMore}><Spinner size={20} /></div> : null}
          </div>
        </div>
      </div>
    </Modal>
  );
}
