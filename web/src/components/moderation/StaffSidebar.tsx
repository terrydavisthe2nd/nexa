import { Award, BellRing, Bot, Bug, Flag, FolderOpen, Gamepad2, Megaphone, Phone, ScrollText, Server, Shield, Sticker, Users, type LucideIcon } from "lucide-react";
import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { moderation as modApi } from "@/api/moderation";
import { atLeast, useMyRank, type StaffRank } from "@/lib/staffRank";
import { useModeration } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { badgeText } from "@/lib/unread";
import { t } from "@/i18n";
import s from "@/components/sidebar/Sidebar.module.css";
import m from "./StaffSidebar.module.css";

/** One place in the panel, by its path below /channels/@admin. */
export interface StaffPlace {
  id: string;
  label: () => string;
  icon: LucideIcon;
  /** The least rank that has it. */
  rank: StaffRank;
}

/**
 * Every place in the panel, in the groups the sidebar shows them in: what
 * waits on the staff, where to look people and things up, the record, and
 * what is about running Nexa. Android keeps the same list (ui/moderation/Panel.kt).
 */
export const STAFF_GROUPS: { id: string; label: () => string; places: StaffPlace[] }[] = [
  {
    id: "attention",
    label: () => t("moderation.nav.attention"),
    places: [
      { id: "reports", label: () => t("reports.title"), icon: Flag, rank: "helper" },
      { id: "cases", label: () => t("moderation.tabs.cases"), icon: FolderOpen, rank: "helper" },
      { id: "bugs", label: () => t("moderation.tabs.bugs"), icon: Bug, rank: "helper" },
    ],
  },
  {
    id: "lookUp",
    label: () => t("moderation.nav.lookUp"),
    places: [
      { id: "users", label: () => t("moderation.tabs.users"), icon: Users, rank: "helper" },
      { id: "servers", label: () => t("moderation.tabs.servers"), icon: Server, rank: "mod" },
      { id: "expressions", label: () => t("moderation.tabs.expressions"), icon: Sticker, rank: "mod" },
      { id: "bots", label: () => t("moderation.tabs.bots"), icon: Bot, rank: "admin" },
    ],
  },
  {
    id: "records",
    label: () => t("moderation.nav.records"),
    places: [{ id: "logs", label: () => t("moderation.tabs.logs"), icon: ScrollText, rank: "helper" }],
  },
  {
    id: "running",
    label: () => t("moderation.nav.running"),
    places: [
      { id: "announcements", label: () => t("moderation.tabs.announcements"), icon: Megaphone, rank: "admin" },
      { id: "badges", label: () => t("moderation.tabs.badges"), icon: Award, rank: "admin" },
      { id: "games", label: () => t("moderation.tabs.games"), icon: Gamepad2, rank: "admin" },
      { id: "calls", label: () => t("moderation.tabs.calls"), icon: Phone, rank: "admin" },
    ],
  },
];

/** The places this rank has, in order. */
export function placesFor(rank: StaffRank | null): StaffPlace[] {
  return STAFF_GROUPS.flatMap((g) => g.places).filter((p) => atLeast(rank, p.rank));
}

/** Which place a path below the panel is in (Discovery now lives in Servers). */
export function placeOf(pathname: string): string {
  const seg = pathname.replace(/^\/channels\/@admin\/?/, "").split("/")[0] ?? "";
  if (seg === "discovery") return "servers";
  return seg || "reports";
}

/**
 * The panel's own sidebar, in place of the conversations while it is open,
 * as a server's channels take their place: every part of it by name, with
 * what waits on the staff counted where it waits.
 */
export function StaffSidebar() {
  const rank = useMyRank();
  const nav = useNavigate();
  const loc = useLocation();
  const here = placeOf(loc.pathname);
  const unread = useModeration((x) => x.unread);
  const reportCounts = useModeration((x) => x.reportCounts);
  const openModal = useUI((u) => u.openModal);
  const gamesWaiting = useModeration((x) => x.gamesWaiting);
  const mayReviewGames = atLeast(rank, "admin");
  useEffect(() => {
    if (!mayReviewGames) return;
    void modApi.gamesWaiting().then((r) => useModeration.getState().setGamesWaiting(r.waiting)).catch(() => undefined);
  }, [mayReviewGames]);
  const waitingReports = (reportCounts?.open ?? 0) + (reportCounts?.in_review ?? 0);
  const counts: Record<string, { n: number; label: string } | undefined> = {
    reports: waitingReports ? { n: waitingReports, label: t("reports.waitingCount", { count: waitingReports }) } : undefined,
    cases: unread.cases.length ? { n: unread.cases.length, label: t("moderation.needsReplyCount", { count: unread.cases.length }) } : undefined,
    bugs: unread.bugs.length ? { n: unread.bugs.length, label: t("bugs.staff.waitingCount", { count: unread.bugs.length }) } : undefined,
    games: gamesWaiting ? { n: gamesWaiting, label: t("moderation.games.waitingCount", { count: gamesWaiting }) } : undefined,
  };
  // picking a place closes the phone drawer, even the page already open
  const go = (id: string) => {
    nav(`/channels/@admin/${id}`);
    useUI.getState().setLeftOpen(false);
  };
  return (
    <nav className={s.sidebar} aria-label={t("moderation.title")} data-testid="staff-sidebar">
      <div className={m.head}>
        <Shield size={18} aria-hidden="true" className={m.headIcon} />
        <span className={m.headTitle}>{t("moderation.navLabel")}</span>
        <button
          type="button"
          className={m.headBtn}
          onClick={() => openModal("settings", { page: "notifications", anchor: "staffAlerts" })}
          aria-label={t("moderation.alerts.open")}
          title={t("moderation.alerts.open")}
          data-testid="staff-alerts"
        >
          <BellRing size={16} aria-hidden="true" />
        </button>
      </div>
      <div className={s.scroll}>
        {STAFF_GROUPS.map((g) => {
          const places = g.places.filter((p) => atLeast(rank, p.rank));
          if (!places.length) return null;
          return (
            <div key={g.id} role="group" aria-labelledby={`staff-group-${g.id}`}>
              <div className={s.header} id={`staff-group-${g.id}`}>
                <span className={s.headerLabel}>{g.label()}</span>
              </div>
              {places.map((p) => {
                const c = counts[p.id];
                const Icon = p.icon;
                return (
                  <button
                    key={p.id}
                    type="button"
                    className={s.nav}
                    data-selected={here === p.id}
                    aria-current={here === p.id ? "page" : undefined}
                    onClick={() => go(p.id)}
                    data-testid={`staff-nav-${p.id}`}
                  >
                    <span className={s.navIcon}>
                      <Icon size={20} aria-hidden="true" />
                    </span>
                    <span className={s.navLabel}>{p.label()}</span>
                    {c ? (
                      <>
                        <span className="visually-hidden">{`, ${c.label}`}</span>
                        <span className={s.mention} aria-hidden="true">
                          {badgeText(c.n)}
                        </span>
                      </>
                    ) : null}
                  </button>
                );
              })}
            </div>
          );
        })}
      </div>
    </nav>
  );
}
