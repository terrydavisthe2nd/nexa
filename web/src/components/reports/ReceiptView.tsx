import { MessageSquareQuote } from "lucide-react";
import type { ReportReceipt } from "@/api/reports";
import { fullDate, relativeTo } from "@/lib/moderation";
import { NexaAvatar } from "@/components/nexa/NexaBits";
import { ReasonGlyph } from "./ReasonGlyph";
import { t } from "@/i18n";
import s from "./Reports.module.css";

/** Where a report stands, in the reporter's words rather than the staff's. */
export function ReceiptStatus({ status }: { status: string }) {
  return (
    <span className={s.badge} data-status={status}>
      {t(`reports.yours.status.${status}`)}
    </span>
  );
}

/** "Profile: maya", "Message", "Something Else: what they wrote". */
export function receiptSubject(r: ReportReceipt): string {
  const kind = t(`reports.kind.${r.target_kind}`);
  return r.target_label ? t("reports.yours.about", { kind, name: r.target_label }) : kind;
}

/** What staff told the reporter, or the standard words for the outcome. */
export function outcomeText(r: ReportReceipt): string {
  return r.outcome || t(`reports.outcome.${r.status === "dismissed" ? "dismissed" : "actioned"}`);
}

/**
 * One report as the person who sent it sees it: what and why, what they said,
 * where it stands, and what staff told them once it was closed. Never staff
 * notes, never who reviewed it.
 */
export function ReceiptView({ receipt: r, compact = false }: { receipt: ReportReceipt; compact?: boolean }) {
  return (
    <div className={s.receipt} data-status={r.status} data-testid="report-receipt">
      <div className={s.receiptHead}>
        <ReasonGlyph reason={r.reason} />
        <span className={s.receiptTitle}>
          <span className={s.reasonTitle}>{t(`reports.reason.${r.reason}`)}</span>
          <span className={s.muted} dir="auto">
            {receiptSubject(r)}
          </span>
        </span>
        <ReceiptStatus status={r.status} />
      </div>
      {!compact && (
        <p className={s.receiptSaid} dir="auto">
          {r.description}
        </p>
      )}
      {r.outcome_at ? (
        <div className={s.toldBox}>
          <NexaAvatar size={20} />
          <span className={s.toldText}>
            <span className={s.toldLabel}>
              <MessageSquareQuote size={14} aria-hidden="true" /> {t("reports.yours.fromStaff")}
            </span>
            <span dir="auto">{outcomeText(r)}</span>
          </span>
        </div>
      ) : null}
      <time className={s.muted} dateTime={new Date(r.created_at).toISOString()} title={fullDate(r.created_at)}>
        {t("reports.yours.sentAt", { when: relativeTo(r.created_at) })}
      </time>
    </div>
  );
}
