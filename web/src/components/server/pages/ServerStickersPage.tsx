import { useMemo, useState } from "react";
import { ApiError } from "@/api/client";
import { expressions as expressionsApi } from "@/api/expressions";
import { P } from "@/lib/perms";
import { useExpressions } from "@/stores/expressions";
import { canI, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { Plus } from "lucide-react";
import { Button } from "@/components/common/Button";
import st from "@/components/settings/Settings.module.css";
import { PackEditor } from "../PackEditor";
import { promptText } from "@/components/modals/PromptModal";

import { t } from "@/i18n";
/** Server settings → Stickers: packs owned by this server, managed with Manage Expressions. */
export default function ServerStickersPage({ serverId }: { serverId: string }) {
  const server = useServers((x) => x.byId[serverId]);
  const packs = useExpressions((x) => x.packs);
  const upsertPack = useExpressions((x) => x.upsertPack);
  const toast = useUI((u) => u.toast);
  const [selected, setSelected] = useState<string | null>(null);
  const manage = canI(server, P.MANAGE_EMOJIS);
  const list = useMemo(() => Object.values(packs).filter((p) => p.server_id === serverId).sort((a, b) => b.updated_at - a.updated_at), [packs, serverId]);

  const create = async () => {
    const name = await promptText(t("server.serverStickers.createServerPack"), { label: t("server.packEditor.packName"), initial: t("server.serverStickers.defaultPackName", { name: server?.server.name ?? t("settings.settingsScreen.server") }), maxLength: 60 }, t("settings.stickers.create"));
    if (!name?.trim()) return;
    try {
      const pack = await expressionsApi.createPack({ name: name.trim(), server_id: serverId });
      upsertPack(pack);
      setSelected(pack.id);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.serverStickers.couldNotCreateThePack"), "error");
    }
  };

  return (
    <>
      {/* a pack's page says what it is itself */}
      {selected && manage && list.some((p) => p.id === selected) ? null : (
        <>
          <h2 className={st.title}>{t("pickers.expressionPicker.stickers")}</h2>
          <p className={st.hint}>{t("server.serverStickers.stickerPacksThatBelongToThis")}</p>
        </>
      )}
      <PackEditor
        groups={[{ id: "server", packs: list }]}
        selectedId={selected}
        onSelect={setSelected}
        canManage={() => manage}
        backLabel={t("pickers.expressionPicker.stickers")}
        actions={manage ? <Button size="sm" onClick={() => void create()} data-testid="create-pack"><Plus size={14} aria-hidden="true" /> {t("server.serverStickers.createServerPack")}</Button> : undefined}
        emptyText={manage ? t("server.serverStickers.thisServerHasNoStickerPacks") : t("server.serverStickers.thisServerHasNoStickerPacks2")}
      />
    </>
  );
}
