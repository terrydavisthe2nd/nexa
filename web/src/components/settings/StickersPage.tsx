import { useMemo, useState } from "react";
import { Compass, Plus } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { expressions as expressionsApi } from "@/api/expressions";
import type { StickerPack } from "@/api/types";

import { Button } from "@/components/common/Button";
import { CreatePackModal, type ImportCounts } from "@/components/modals/CreatePackModal";
import { PackEditor, type PackGroup } from "@/components/server/PackEditor";
import { canRemovePack } from "@/lib/packRemoval";
import { useAuth } from "@/stores/auth";
import { useExpressions } from "@/stores/expressions";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { unlessUnsaved } from "@/stores/unsaved";
import st from "./Settings.module.css";
import s from "@/components/server/PackEditor.module.css";

import { t as tr_ } from "@/i18n";

/** User settings, Emoji & Stickers: every pack this account has, and a page for each of its own. */
export default function StickersPage({ initialPackId }: { initialPackId?: string | undefined }) {
  const meId = useAuth((a) => a.me?.id);
  const packs = useExpressions((x) => x.packs);
  const servers = useServers((x) => x.byId);
  const upsertPack = useExpressions((x) => x.upsertPack);
  const toast = useUI((u) => u.toast);
  const closeModal = useUI((u) => u.closeModal);
  const navigate = useNavigate();
  const [selected, setSelected] = useState<string | null>(initialPackId ?? null);
  const [creating, setCreating] = useState<null | "sticker" | "emoji">(null);
  const opened = selected ? packs[selected] : undefined;
  const [tab, setTab] = useState<"sticker" | "emoji">(opened?.kind === "emoji" ? "emoji" : "sticker");
  const kind = tab;
  const groups = useMemo((): PackGroup[] => {
    const list = Object.values(packs).filter((p) => (p.kind ?? "sticker") === kind).sort((a, b) => b.updated_at - a.updated_at);
    const own = list.filter((p) => !p.server_id && p.owner_user_id === meId);
    const installed = list.filter((p) => !own.includes(p) && (canRemovePack(p, meId) || (!p.server_id && p.installed)));
    const fromServers = list.filter((p) => !own.includes(p) && !installed.includes(p) && !!p.server_id);
    return [
      { id: "own", title: tr_("settings.stickers.yourPacks"), packs: own },
      { id: "installed", title: tr_("expressions.installed"), packs: installed },
      { id: "servers", title: tr_("settings.stickers.fromYourServers"), packs: fromServers, note: (p) => servers[p.server_id ?? ""]?.server.name ?? null },
    ];
  }, [packs, meId, kind, servers]);

  const create = async (name: string) => {
    try {
      const pack = await expressionsApi.createPack({ name, kind });
      upsertPack(pack);
      setSelected(pack.id);
      setCreating(null);
      return pack;
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("server.serverStickers.couldNotCreateThePack"), "error");
      return null;
    }
  };
  const imported = (pack: StickerPack, counts: ImportCounts) => {
    upsertPack(pack);
    setSelected(pack.id);
    setCreating(null);
    let msg = tr_("settings.stickers.importedToast", { imported: counts.imported });
    if (counts.skipped > 0) msg += " " + tr_("settings.stickers.importedSkipped", { skipped: counts.skipped });
    if (counts.failed > 0) msg += " " + tr_("settings.stickers.importedFailed", { failed: counts.failed });
    toast(msg, "success");
  };
  const findMore = () => {
    closeModal();
    navigate(`/channels/@discover?tab=${kind === "emoji" ? "emoji" : "stickers"}`);
  };

  // Left and Right move along the tabs, as a tab strip does everywhere else.
  const onTabKey = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const strip = e.currentTarget.parentElement;
    const buttons = strip ? [...strip.querySelectorAll<HTMLElement>('[role="tab"]')] : [];
    // in a right-to-left page, Right goes back
    const dir = document.documentElement.dir === "rtl" ? -step : step;
    const next = buttons[Math.min(buttons.length - 1, Math.max(0, buttons.indexOf(e.currentTarget) + dir))];
    next?.click();
    next?.focus();
  };

  const openPack = selected ? packs[selected] : undefined;
  const onPackPage = !!openPack && openPack.owner_user_id === meId;

  return (
    <>
      {onPackPage ? null : (
        <>
          <h2 data-setting="packs" className={st.title}>{tr_("settings.groups.emojiStickers")}</h2>
          <p className={st.hint}>{tr_("settings.stickers.buildYourOwnEmojiAndSticker")}</p>
          <div className={s.kinds} role="tablist" aria-label={tr_("settings.groups.emojiStickers")}>
            {([
              ["sticker", tr_("pickers.expressionPicker.stickers"), "packs-stickers"],
              ["emoji", tr_("modals.customStatus.emoji"), "packs-emoji"],
            ] as const).map(([id, label, nav]) => (
              <button
                key={id}
                className={s.kind}
                role="tab"
                aria-selected={tab === id}
                tabIndex={tab === id ? 0 : -1}
                data-on={tab === id}
                data-nav={nav}
                onKeyDown={onTabKey}
                onClick={tab === id ? undefined : () => { setTab(id); setSelected(null); }}
              >
                {label}
              </button>
            ))}
          </div>
        </>
      )}
      <PackEditor
        groups={groups}
        selectedId={selected}
        onSelect={setSelected}
        canManage={(p) => p.owner_user_id === meId}
        canRemove={(p) => canRemovePack(p, meId)}
        backLabel={tr_("settings.groups.emojiStickers")}
        actions={
          <>
            <Button size="sm" onClick={unlessUnsaved(() => setCreating(kind))} data-testid="create-pack"><Plus size={14} aria-hidden="true" /> {kind === "emoji" ? tr_("settings.stickers.createEmojiPack") : tr_("settings.stickers.createStickerPack")}</Button>
            <Button size="sm" variant="secondary" onClick={findMore} data-testid="find-more-packs"><Compass size={14} aria-hidden="true" /> {tr_("pickers.stickerPicker.findMorePacks")}</Button>
          </>
        }
        emptyText={kind === "emoji" ? tr_("settings.stickers.noEmojiPacksYet") : tr_("settings.stickers.noStickerPacksYet")}
      />
      {creating ? <CreatePackModal kind={creating} onClose={() => setCreating(null)} onCreate={create} onImported={imported} /> : null}
    </>
  );
}
