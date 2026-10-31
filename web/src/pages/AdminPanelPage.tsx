import { reportReturnPath } from "@/lib/reportNavigation";
import { Button } from "@/components/common/Button";
import { Flag, Compass, Bot, Bug, FolderOpen, LayoutGrid, Megaphone, Phone, ScrollText, Server, Shield, Sticker } from "lucide-react";
import { Suspense, useEffect, type KeyboardEvent } from "react";
import { Navigate, Outlet, useLocation, useNavigate } from "react-router-dom";
import { moderation as modApi } from "@/api/moderation";
import { Spinner } from "@/components/common/Spinner";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { atLeast, useMyRank } from "@/lib/staffRank";
import { useModeration } from "@/stores/moderation";
import { t } from "@/i18n";
import m from "@/components/moderation/Moderation.module.css";

// the tabs, routed in routes.tsx and loaded with the panel
export { AnnouncementsTab } from "@/components/moderation/AnnouncementsTab";
export { BugsTab } from "@/components/moderation/BugsTab";
export { BotsTab } from "@/components/moderation/BotsTab";
export { CallsTab } from "@/components/moderation/CallsTab";
export { CasesTab } from "@/components/moderation/CaseWorkspace";
export { ExpressionsTab } from "@/components/moderation/ExpressionsTab";
export { LogsTab } from "@/components/moderation/LogsTab";
export { PackPage } from "@/components/moderation/PackPage";
export { PersonPage } from "@/components/moderation/PersonPage";
export { ServerPage } from "@/components/moderation/ServerPage";
export { ServersTab, DiscoveryModerationTab } from "@/components/moderation/ServersTab";
export { UserGrid } from "@/components/moderation/UserGrid";

export { ReportsTab } from "@/components/reports/ReportsTab";

type Tab = "reports" | "discovery" | "users" | "bots" | "servers" | "expressions" | "calls" | "cases" | "bugs" | "logs" | "announcements";

const BASE = "/channels/@admin";

/** The tabs a mod has; admins and above have every one. */
const MOD_TABS: Tab[] = ["users", "reports", "cases", "logs"];

function tabOf(path: string): Tab {
  const seg = path.slice(BASE.length + 1).split("/")[0];
  return seg === "reports" || seg === "discovery" || seg === "bots" || seg === "servers" || seg === "expressions" || seg === "calls" || seg === "cases" || seg === "bugs" || seg === "logs" || seg === "announcements" ? seg : "users";
}

/**
 * The Admin & Moderation panel: every account at a glance, the cases waiting
 * on the staff, the record of everything done, and announcements to everyone.
 * Every rank of staff gets here, a mod to the people, cases and log alone
 * (lib/staffRank.ts); the server refuses the rest anyway.
 */
export default function AdminPanelPage() {
  const rank = useMyRank();
  const isAdmin = rank !== null;
  const unread = useModeration((s) => s.unread);
  const loc = useLocation();
  const nav = useNavigate();
  const tab = tabOf(loc.pathname);

  useEffect(() => {
    if (!isAdmin) return;
    modApi
      .summary()
      .then((r) => {
        const st = useModeration.getState();
        st.setCounts(r.counts);
        st.setBugCounts(r.bugs);
        st.setUnread(r.unread);
        st.setOwner(r.owner_id);
        st.mergePeople(r.people);
      })
      .catch(() => undefined);
  }, [isAdmin]);

  // on a phone the tabs are a strip that scrolls: the open one is always in view
  useEffect(() => {
    document.querySelector<HTMLElement>("[data-testid=admin-panel] [role=tab][aria-selected=true]")?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [tab]);

  useEffect(() => {
    document.title = `${t("moderation.title")} · Nexa`;
    return () => {
      document.title = "Nexa";
    };
  }, []);

  if (!isAdmin) return <Navigate to="/channels/@me" replace />;

  const all: { id: Tab; label: string; icon: JSX.Element; badge?: number; badgeLabel?: string }[] = [
    { id: "reports", label: t("reports.title"), icon: <Flag size={17} /> },
    { id: "users", label: t("moderation.tabs.users"), icon: <LayoutGrid size={17} /> },
    { id: "bots", label: t("moderation.tabs.bots"), icon: <Bot size={17} /> },
    { id: "servers", label: t("moderation.tabs.servers"), icon: <Server size={17} /> },
    { id: "discovery", label: t("moderation.tabs.discovery"), icon: <Compass size={17} /> },
    { id: "expressions", label: t("moderation.tabs.expressions"), icon: <Sticker size={17} /> },
    // what calls use, and who uses it, against the month's allowance
    { id: "calls", label: t("moderation.tabs.calls"), icon: <Phone size={17} /> },
    // what waits on the staff and is new to this person; reading it, or Mark as Read, takes it off
    { id: "cases", label: t("moderation.tabs.cases"), icon: <FolderOpen size={17} />, badge: unread.cases.length || undefined },
    { id: "bugs", label: t("moderation.tabs.bugs"), icon: <Bug size={17} />, badge: unread.bugs.length || undefined, badgeLabel: unread.bugs.length ? t("bugs.staff.waitingCount", { count: unread.bugs.length }) : undefined },
    { id: "logs", label: t("moderation.tabs.logs"), icon: <ScrollText size={17} /> },
    { id: "announcements", label: t("moderation.tabs.announcements"), icon: <Megaphone size={17} /> },
  ];
  const tabs = atLeast(rank, "admin") ? all : all.filter((x) => MOD_TABS.includes(x.id));
  if (!tabs.some((x) => x.id === tab)) return <Navigate to={BASE} replace />;

  // Left and Right across the strip, as a tablist moves
  const onKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const at = tabs.findIndex((x) => x.id === tab);
    const next = tabs[(at + step + tabs.length) % tabs.length]!;
    nav(`${BASE}/${next.id}`);
    const strip = e.currentTarget.parentElement;
    strip?.querySelectorAll<HTMLElement>('[role="tab"]')[tabs.indexOf(next)]?.focus();
  };

  return (
    <div className={m.page} data-testid="admin-panel">
      {reportReturnPath(loc.state) && <div style={{padding:"8px 16px",borderBottom:"1px solid var(--border-subtle)"}}><Button variant="secondary" size="sm" onClick={() => nav(reportReturnPath(loc.state)!)}>{t("reports.returnToReport")}</Button></div>}
      <header className={m.header}>
        <PageHeaderButtons />
        <div className={m.title}>
          <Shield size={20} aria-hidden="true" />
          {t("moderation.title")}
        </div>
        <nav className={m.tabs} role="tablist" aria-label={t("moderation.title")}>
          {tabs.map((x) => (
            <button
              key={x.id}
              className={m.tab}
              role="tab"
              aria-selected={tab === x.id}
              tabIndex={tab === x.id ? 0 : -1}
              data-selected={tab === x.id}
              onClick={() => nav(`${BASE}/${x.id}`)}
              onKeyDown={onKey}
              data-testid={`tab-${x.id}`}
              title={tab === x.id ? undefined : x.label}
            >
              {x.icon}
              <span className={m.tabLabel}>{x.label}</span>
              {x.badge ? (
                <span className={m.badge} aria-label={x.badgeLabel ?? t("moderation.needsReplyCount", { count: x.badge })}>
                  {x.badge > 99 ? "99+" : x.badge}
                </span>
              ) : null}
            </button>
          ))}
        </nav>
      </header>
      <div className={m.body}>
        <Suspense
          fallback={
            <div className={m.loadingRow}>
              <Spinner label={t("chat.pins.loading")} />
            </div>
          }
        >
          <Outlet />
        </Suspense>
      </div>
    </div>
  );
}
