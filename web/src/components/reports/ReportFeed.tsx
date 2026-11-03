import type { FeedItem } from "@/api/moderation";
import { Button } from "@/components/common/Button";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import { ReceiptView } from "./ReceiptView";
import s from "./Reports.module.css";

export type ReportItem = Extract<FeedItem, { kind: "report" | "report_outcome" }>;

export function isReportItem(it: FeedItem): it is ReportItem {
  return it.kind === "report" || it.kind === "report_outcome";
}

/**
 * A safety report in the reporter's Nexa conversation: the report they sent,
 * then, once staff close it, what was decided and what staff told them.
 */
export function ReportFeedEntry({ item }: { item: ReportItem }) {
  const r = item.report;
  return (
    <div className={s.feedReport} data-kind={item.kind}>
      <p className={s.feedLead}>{item.kind === "report" ? t("reports.feed.sent") : r.status === "dismissed" ? t("reports.feed.reviewedNoAction") : t("reports.feed.reviewedAction")}</p>
      <ReceiptView receipt={item.kind === "report" ? { ...r, outcome_at: 0 } : r} compact={item.kind === "report_outcome"} />
      {item.kind === "report_outcome" && (
        <div>
          <Button size="sm" variant="secondary" onClick={() => useUI.getState().openModal("settings", { page: "reports" })}>
            {t("reports.yours.view")}
          </Button>
        </div>
      )}
    </div>
  );
}
