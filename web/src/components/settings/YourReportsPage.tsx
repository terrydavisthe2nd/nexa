import { Flag } from "lucide-react";
import { useEffect } from "react";
import { Button } from "@/components/common/Button";
import { ReceiptView } from "@/components/reports/ReceiptView";
import { openReport } from "@/lib/reporting";
import { useMyReports } from "@/stores/myReports";
import { t } from "@/i18n";
import s from "./Settings.module.css";
import r from "@/components/reports/Reports.module.css";

/**
 * Your Reports: every report this account sent, where each stands, and what
 * staff said when they closed it (also told in the Nexa conversation).
 */
export default function YourReportsPage() {
  const list = useMyReports((m) => m.list);
  const more = useMyReports((m) => m.more);
  const loading = useMyReports((m) => m.loading);
  const failed = useMyReports((m) => m.error);
  useEffect(() => {
    void useMyReports.getState().load();
  }, []);
  return (
    <>
      <h2 className={s.title}>{t("reports.yours.title")}</h2>
      <p className={s.hint}>{t("reports.yours.intro")}</p>
      <div className={r.yoursActions}>
        <Button onClick={() => openReport()} data-testid="report-something">
          <Flag size={16} aria-hidden="true" /> {t("reports.yours.reportSomething")}
        </Button>
      </div>
      {failed && !list?.length ? (
        <div className={r.error} role="alert">
          {t("reports.failed")}
          <Button variant="link" onClick={() => void useMyReports.getState().load()}>
            {t("reports.retry")}
          </Button>
        </div>
      ) : list === null ? (
        <p role="status" className={r.notice}>
          {t("reports.loading")}
        </p>
      ) : list.length === 0 ? (
        <p className={r.yoursEmpty}>{t("reports.yours.empty")}</p>
      ) : (
        <ul className={r.yoursList} role="list" data-testid="your-reports">
          {list.map((x) => (
            <li key={x.id}>
              <ReceiptView receipt={x} />
            </li>
          ))}
        </ul>
      )}
      {more && (
        <Button variant="secondary" loading={loading} onClick={() => void useMyReports.getState().load(true)}>
          {t("reports.more")}
        </Button>
      )}
    </>
  );
}
