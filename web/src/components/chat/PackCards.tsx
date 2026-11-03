import { PackageX, RotateCw } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { expressions as expressionsApi } from "@/api/expressions";
import type { StickerPack } from "@/api/types";
import { Button } from "@/components/common/Button";
import v from "@/components/invite/Invite.module.css";
import { packCount, packCover } from "@/components/server/PackEditor";
import { StickerView } from "@/components/stickers/StickerView";
import { LazySticker } from "@/components/stickers/StickerTile";
import { t } from "@/i18n";
import { packLinksIn } from "@/lib/appLinks";
import { describeError } from "@/lib/errors";
import { loadSharedPack, setSharedPack, useSharedPack } from "@/lib/packLinks";
import { useAuth } from "@/stores/auth";
import { packImageUrl, useExpressions } from "@/stores/expressions";
import { useUI } from "@/stores/ui";
import s from "./PackCards.module.css";

/** How many of a pack's stickers or emoji a card shows. */
const SHOWN = 8;

/**
 * The sticker and emoji packs a message links to, each as a card the way an
 * invite is drawn: what kind of pack it is, its cover, name and size, the
 * first few of what is in it, and the one thing to do, add it or open it.
 */
export function PackCards({ nodes }: { nodes: unknown }) {
  const links = useMemo(() => packLinksIn(nodes), [nodes]);
  if (links.length === 0) return null;
  return (
    <div className={s.cards}>
      {links.map((l) => <PackLinkCard key={l.id} id={l.id} />)}
    </div>
  );
}

/**
 * A pack's card by its id, loading it as a pack link does. `quiet` draws
 * nothing for a pack that has gone or turned private (a profile's showcase,
 * where the owner chose it and a stranger has nothing to fix).
 */
export function PackLinkCard({ id, quiet = false }: { id: string; quiet?: boolean }) {
  const entry = useSharedPack(id);
  if (quiet && (entry?.status === "missing" || entry?.status === "failed")) return null;
  if (entry?.status === "missing" || entry?.status === "failed") {
    const missing = entry.status === "missing";
    return (
      <div className={v.view} data-variant="card" data-gone="true" data-testid="pack-card" data-pack-state={entry.status}>
        <div className={v.gone}>
          <span className={v.goneIcon} aria-hidden="true"><PackageX size={22} /></span>
          <div className={v.goneText}>
            <p className={v.goneTitle}>{t("stickerPack.packUnavailable")}</p>
            <p className={v.goneBody}>{missing ? t("stickerPack.thisPackIsPrivate") : t("stickerPack.couldNotLoadThePack")}</p>
          </div>
          {missing ? null : (
            <Button size="sm" variant="secondary" onClick={() => void loadSharedPack(id, { force: true })}><RotateCw size={14} aria-hidden="true" /> {t("invite.tryAgain")}</Button>
          )}
        </div>
      </div>
    );
  }
  if (entry?.status !== "ready") {
    return (
      <div className={[v.view, s.card].join(" ")} data-variant="card" data-loading="true" aria-busy="true" data-testid="pack-card" data-pack-state="loading">
        <span className="visually-hidden">{t("stickerPack.loading")}</span>
        <div className={s.head}>
          <span className={s.cover} />
          <div className={v.main}>
            <span className={v.bar} data-w="name" />
            <span className={v.bar} data-w="counts" />
          </div>
        </div>
      </div>
    );
  }
  return <PackCard pack={entry.pack} />;
}

function PackCard({ pack }: { pack: StickerPack }) {
  const meId = useAuth((a) => a.me?.id);
  const upsertPack = useExpressions((x) => x.upsertPack);
  const toast = useUI((u) => u.toast);
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const cover = packCover(pack);
  const open = () => navigate(`/stickers/pack/${pack.id}`);
  const install = async () => {
    setBusy(true);
    try {
      const res = await expressionsApi.install(pack.id);
      upsertPack(res);
      setSharedPack(res);
    } catch (err) {
      toast(describeError(err, t("server.packEditor.couldNotChangeThePack")), "error");
    } finally {
      setBusy(false);
    }
  };
  // a pack already here, or one of this account's own, is opened rather than added
  const added = pack.installed || pack.owner_user_id === meId;
  const shown = pack.stickers.slice(0, SHOWN);
  const more = pack.stickers.length - shown.length;
  return (
    <div className={[v.view, s.card].join(" ")} data-variant="card" data-testid="pack-card" data-pack-state="ready" data-kind={pack.kind}>
      <div className={s.head}>
        <button type="button" className={s.cover} onClick={open} aria-label={pack.name}>
          {cover ? <StickerView format={cover.format} src={packImageUrl(pack, cover)} alt="" /> : <span className={s.initial}>{pack.name.slice(0, 1).toUpperCase()}</span>}
        </button>
        <div className={v.main}>
          <p className={v.lead}>{pack.kind === "emoji" ? t("stickerPack.emojiPack") : t("stickerPack.stickerPack")}</p>
          <p className={v.name}><bdi>{pack.name}</bdi></p>
          <div className={v.counts}><span className={v.count}>{packCount(pack)}</span></div>
        </div>
        <div className={v.cardActions}>
          {added ? (
            <Button size="sm" variant="secondary" onClick={open}>{t("chat.embeds.open")}</Button>
          ) : (
            <Button size="sm" loading={busy} onClick={() => void install()}>{t("stickerPack.installPack")}</Button>
          )}
        </div>
      </div>
      {pack.description ? <p className={[v.desc, s.desc].join(" ")} dir="auto">{pack.description}</p> : null}
      {shown.length > 0 ? (
        <div className={s.strip} aria-hidden="true">
          {shown.map((st) => (
            <span key={st.id} className={s.item} title={`:${st.name}:`}>
              <LazySticker sticker={st} pack={pack} alt="" className={s.itemImg} />
            </span>
          ))}
          {more > 0 ? <span className={s.more}>+{more}</span> : null}
        </div>
      ) : null}
    </div>
  );
}
