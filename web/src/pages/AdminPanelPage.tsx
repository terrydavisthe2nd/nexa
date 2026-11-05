import { reportReturnPath } from "@/lib/reportNavigation";
import { Button } from "@/components/common/Button";
import { Suspense, useEffect } from "react";
import { Navigate, Outlet, useLocation, useNavigate } from "react-router-dom";
import { moderation as modApi } from "@/api/moderation";
import { Spinner } from "@/components/common/Spinner";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { placeOf, placesFor } from "@/components/moderation/StaffSidebar";
import { useMyRank } from "@/lib/staffRank";
import { useModeration } from "@/stores/moderation";
import { t } from "@/i18n";
import m from "@/components/moderation/Moderation.module.css";

// the tabs, routed in routes.tsx and loaded with the panel
export { AnnouncementsTab } from "@/components/moderation/AnnouncementsTab";
export { BadgesTab } from "@/components/moderation/BadgesTab";
export { BugsTab } from "@/components/moderation/BugsTab";
export { BotsTab } from "@/components/moderation/BotsTab";
export { CallsTab } from "@/components/moderation/CallsTab";
export { CasesTab } from "@/components/moderation/CaseWorkspace";
export { ExpressionsTab } from "@/components/moderation/ExpressionsTab";
export { LogsTab } from "@/components/moderation/LogsTab";
export { PackPage } from "@/components/moderation/PackPage";
export { PersonPage } from "@/components/moderation/PersonPage";
export { ServerPage } from "@/components/moderation/ServerPage";
export { ServersTab } from "@/components/moderation/ServersTab";
export { UserGrid } from "@/components/moderation/UserGrid";

export { ReportsTab } from "@/components/reports/ReportsTab";

/**
 * The Admin & Moderation panel: every account at a glance, the cases waiting
 * on the staff, the record of everything done, and announcements to everyone.
 * Every rank of staff gets here, a mod to the people, cases and log alone
 * (lib/staffRank.ts); the server refuses the rest anyway.
 */
export default function AdminPanelPage() {
  const rank = useMyRank();
  const isStaff = rank !== null;
  const loc = useLocation();
  const nav = useNavigate();
  const here = placeOf(loc.pathname);

  useEffect(() => {
    if (!isStaff) return;
    modApi
      .summary()
      .then((r) => {
        const st = useModeration.getState();
        st.setCounts(r.counts);
        st.setBugCounts(r.bugs);
        st.setReportCounts(r.reports);
        st.setUnread(r.unread);
        st.setOwner(r.owner_id);
        st.mergePeople(r.people);
      })
      .catch(() => undefined);
  }, [isStaff]);

  useEffect(() => {
    document.title = `${t("moderation.title")} · Nexa`;
    return () => {
      document.title = "Nexa";
    };
  }, []);

  if (!isStaff) return <Navigate to="/channels/@me" replace />;
  // the places are the sidebar's (StaffSidebar.tsx); one this rank lacks goes to the reports
  const places = placesFor(rank);
  const place = places.find((p) => p.id === here);
  if (!place) return <Navigate to="/channels/@admin/reports" replace />;
  const Icon = place.icon;
  const back = reportReturnPath(loc.state);

  return (
    <div className={m.page} data-testid="admin-panel">
      <header className={m.header}>
        <PageHeaderButtons />
        <div className={m.title}>
          <Icon size={20} aria-hidden="true" />
          <h1 className={m.titleText}>{place.label()}</h1>
        </div>
        {back ? (
          <Button variant="secondary" size="sm" className={m.headerBack} onClick={() => nav(back)} data-testid="return-to-report">
            {t("reports.returnToReport")}
          </Button>
        ) : null}
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
