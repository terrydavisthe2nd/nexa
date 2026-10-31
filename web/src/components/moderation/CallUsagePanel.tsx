import { Phone, Waypoints } from "lucide-react";
import { useEffect, useState } from "react";
import { moderation as modApi, type CallPeriod, type CallTotals, type ModUserCalls } from "@/api/moderation";
import { Spinner } from "@/components/common/Spinner";
import { formatCallTime, formatDataSize } from "@/lib/callUsage";
import { t } from "@/i18n";
import p from "./Sheet.module.css";
import c from "./CallsTab.module.css";

const COLUMNS: { id: CallPeriod; label: () => string }[] = [
  { id: "today", label: () => t("moderation.calls.today") },
  { id: "week", label: () => t("moderation.calls.week") },
  { id: "month", label: () => t("moderation.calls.month") },
];

const ROWS: { label: () => string; value: (x: CallTotals) => string }[] = [
  { label: () => t("moderation.calls.receivedHead"), value: (x) => formatDataSize(x.received_bytes + x.relay_bytes) },
  { label: () => t("moderation.calls.sentHead"), value: (x) => formatDataSize(x.sent_bytes) },
  { label: () => t("moderation.calls.relay"), value: (x) => formatDataSize(x.relay_bytes) },
  { label: () => t("moderation.calls.timeHead"), value: (x) => formatCallTime(x.seconds) },
];

/**
 * One account's calls for the staff: what reached its devices, what its own
 * camera, microphone and screen sent others, its TURN relay and its time in
 * calls, today, over seven days and this month. Amounts only, never content.
 */
export function CallUsagePanel({ userId }: { userId: string }) {
  const [data, setData] = useState<ModUserCalls | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let live = true;
    setData(null);
    setFailed(false);
    modApi
      .userCalls(userId)
      .then((r) => {
        if (live) setData(r);
      })
      .catch(() => {
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [userId]);

  let note = "";
  if (data) {
    if (data.relay_cut_off) note = t("moderation.calls.relayCutOffToday");
    else if (!data.measurements) note = t("moderation.calls.estimatedHint");
    else note = data.month.measured ? t("moderation.calls.measured") : t("moderation.calls.partlyMeasured");
  }
  return (
    <section className={p.panel} aria-labelledby="sheet-calls" data-testid="sheet-calls">
      <header className={p.panelHead}>
        <h3 id="sheet-calls" className={p.panelTitle}>{t("moderation.calls.panelTitle")}</h3>
        <p className={p.panelHint}>{t("moderation.calls.panelHint")}</p>
      </header>
      {failed ? (
        <p className={`${c.hint} ${c.panelFoot}`}>{t("moderation.loadFailed")}</p>
      ) : !data ? (
        <div className={c.panelFoot}>
          <Spinner size={20} label={t("chat.pins.loading")} />
        </div>
      ) : (
        <>
          <table className={c.usageTable}>
            <thead>
              <tr>
                <td />
                {COLUMNS.map((col) => (
                  <th key={col.id} scope="col">
                    {col.label()}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {ROWS.map((row) => (
                <tr key={row.label()}>
                  <th scope="row">{row.label()}</th>
                  {COLUMNS.map((col) => (
                    <td key={col.id}>{row.value(data[col.id])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <div className={c.panelFoot}>
            {data.in_call ? (
              <span className={c.badge} data-tone="live">
                <Phone size={12} aria-hidden="true" /> {t("moderation.calls.inCall")}
              </span>
            ) : null}
            {data.relay_cut_off ? (
              <span className={c.badge} data-tone="warn">
                <Waypoints size={12} aria-hidden="true" /> {t("moderation.calls.relayCutOff")}
              </span>
            ) : null}
            <span className={c.hint}>{note}</span>
          </div>
        </>
      )}
    </section>
  );
}
