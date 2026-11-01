import { Eye } from "lucide-react";
import { Button } from "@/components/common/Button";
import { useT } from "@/i18n";
import { stopViewAs } from "@/lib/viewAsRole";
import { useServers } from "@/stores/servers";
import { useViewAs } from "@/stores/viewAs";
import s from "./ViewAsBanner.module.css";

/** Says, while View Server as Role is on, whose eyes the server is seen through, with the way out. */
export function ViewAsBanner() {
  const t = useT();
  const current = useViewAs((v) => v.current);
  const server = useServers((x) => (current ? x.byId[current.serverId] : undefined));
  if (!current || !server) return null;
  const role = current.roleIds[0] ? server.roles[current.roleIds[0]] : server.roles[server.server.id];
  return (
    <div className={s.banner} role="region" aria-label={t("server.viewAs.title")} data-testid="view-as-banner">
      <Eye size={18} aria-hidden="true" />
      <div className={s.words}>
        <strong>{t("server.viewAs.viewing", { role: role?.name ?? "", server: server.server.name })}</strong>
        <span>{t("server.viewAs.onlyYou")}</span>
      </div>
      <Button size="sm" variant="secondary" onClick={stopViewAs} data-testid="view-as-exit">{t("server.viewAs.exit")}</Button>
    </div>
  );
}
