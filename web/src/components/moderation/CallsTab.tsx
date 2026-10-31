import { PersonName } from "@/components/common/PersonName";
import { Clock, Download, Phone, PhoneOff, RefreshCw, Upload, Waypoints } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { moderation as modApi, type CallPeriod, type CallSort, type MeterStatus, type ModCallRow, type ModCalls } from "@/api/moderation";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { Spinner } from "@/components/common/Spinner";
import { barWidth, formatCallTime, formatDataSize, shareOfAllowance } from "@/lib/callUsage";
import { formatPercent, formatRelative } from "@/lib/time";
import { t } from "@/i18n";
import m from "./Moderation.module.css";
import c from "./CallsTab.module.css";

const PERIODS: { id: CallPeriod; label: () => string }[] = [
  { id: "today", label: () => t("moderation.calls.today") },
  { id: "week", label: () => t("moderation.calls.week") },
  { id: "month", label: () => t("moderation.calls.month") },
];

/**
 * What calls use, and who uses it: the month against the allowance, then
 * every account by what reached its devices, what its own camera, microphone
 * and screen sent others, or its time in calls. Every bar is drawn on the
 * month's allowance, so one account using a large share of it stands out.
 */
export function CallsTab() {
  const nav = useNavigate();
  const [period, setPeriod] = useState<CallPeriod>("today");
  const [sort, setSort] = useState<CallSort>("usage");
  const [page, setPage] = useState<ModCalls | null>(null);
  const [rows, setRows] = useState<ModCallRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const request = useRef(0);
  const sentinel = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const load = useCallback(
    async (offset: number) => {
      const id = ++request.current;
      setLoading(true);
      setFailed(false);
      try {
        const r = await modApi.calls({ period, sort, offset });
        if (id !== request.current) return;
        setPage(r);
        setRows((cur) => (offset ? [...(cur ?? []), ...r.accounts.filter((x) => !cur?.some((y) => y.user_id === x.user_id))] : r.accounts));
        if (!offset) scroller.current?.scrollTo({ top: 0 });
      } catch {
        if (id === request.current) setFailed(true);
      } finally {
        if (id === request.current) setLoading(false);
      }
    },
    [period, sort],
  );

  useEffect(() => {
    setRows(null);
    void load(0);
  }, [load]);

  // the next page loads as the end of the list comes into view
  const more = !!page?.has_more;
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !more || failed) return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !loading) void load(rows?.length ?? 0);
    }, { rootMargin: "400px" });
    io.observe(el);
    return () => io.disconnect();
  }, [more, loading, load, failed, rows?.length]);

  const limit = page?.meter?.limit_bytes ?? 0;
  const unattributed = page?.unattributed;
  const unattributedBytes = unattributed ? unattributed.received_bytes + unattributed.relay_bytes : 0;
  const head = sort === "sent" ? t("moderation.calls.sentHead") : sort === "time" ? t("moderation.calls.timeHead") : t("moderation.calls.receivedHead");

  return (
    <div className={m.scroll} ref={scroller} data-testid="calls-tab">
      {page?.meter ? <Allowance meter={page.meter} measurements={page.measurements} refreshing={loading} onRefresh={() => void load(0)} /> : null}

      <div className={m.toolbar}>
        <div className={m.chips} role="radiogroup" aria-label={t("moderation.calls.periodLabel")}>
          {PERIODS.map((p) => (
            <button key={p.id} type="button" role="radio" className={m.chip} aria-checked={period === p.id} data-selected={period === p.id} onClick={() => setPeriod(p.id)} data-testid={`calls-period-${p.id}`}>
              {p.label()}
            </button>
          ))}
        </div>
        <Select className={m.sort} value={sort} onChange={(e) => setSort(e.target.value as CallSort)} aria-label={t("moderation.sort.label")} data-testid="calls-sort">
          <option value="usage">{t("moderation.calls.sortUsage")}</option>
          <option value="sent">{t("moderation.calls.sortSent")}</option>
          <option value="time">{t("moderation.calls.sortTime")}</option>
        </Select>
      </div>

      {rows === null && !failed ? (
        <div className={c.list} aria-busy="true">
          {Array.from({ length: 5 }, (_, i) => (
            <div key={i} className={c.skeleton} />
          ))}
        </div>
      ) : null}
      {failed && !rows?.length ? (
        <div className={m.empty}>
          <p>{t("moderation.loadFailed")}</p>
          <Button size="sm" variant="secondary" onClick={() => void load(0)}>
            {t("moderation.retry")}
          </Button>
        </div>
      ) : null}
      {rows && rows.length === 0 ? (
        <div className={m.empty}>
          <PhoneOff size={40} aria-hidden="true" />
          <span className={m.emptyTitle}>{t("moderation.calls.none")}</span>
        </div>
      ) : null}
      {rows && rows.length > 0 ? (
        <section className={c.list} aria-label={t("moderation.tabs.calls")}>
          <div className={c.listHead} aria-hidden="true">
            <span>{t("moderation.calls.account")}</span>
            <span>{head}</span>
          </div>
          {rows.map((r) => (
            <CallRow key={r.user_id} row={r} sort={sort} limit={limit} measurements={!!page?.measurements} turnLimit={page?.turn_daily_limit_bytes ?? 0} onOpen={() => nav(`/channels/@admin/users/${r.user_id}`)} />
          ))}
        </section>
      ) : null}
      {unattributed && unattributedBytes + unattributed.sent_bytes > 0 && !more ? (
        <section className={c.unattributed} data-testid="calls-unattributed">
          <div className={c.unattributedText}>
            <span className={c.unattributedTitle}>{t("moderation.calls.unattributedTitle")}</span>
            <span className={c.hint}>{t("moderation.calls.unattributedHint")}</span>
          </div>
          <span className={c.figure}>{formatDataSize(sort === "sent" ? unattributed.sent_bytes : unattributedBytes)}</span>
        </section>
      ) : null}
      <div ref={sentinel} className={m.sentinel} />
      {loading && rows?.length ? (
        <div className={m.loadingRow}>
          <Spinner size={24} label={t("chat.pins.loading")} />
        </div>
      ) : null}
    </div>
  );
}

/** The month against the allowance: what Cloudflare measured, and the estimate for the minutes since. */
function Allowance({ meter, measurements, refreshing, onRefresh }: { meter: MeterStatus; measurements: boolean; refreshing: boolean; onRefresh: () => void }) {
  const limit = meter.limit_bytes;
  const used = meter.used_bytes;
  const measured = meter.measured ? meter.measured_sfu_bytes + meter.measured_turn_bytes : 0;
  const measuredShare = Math.min(100, shareOfAllowance(measured, limit));
  const usedShare = Math.min(100, shareOfAllowance(used, limit));
  const tone = meter.calls_paused ? "stop" : meter.video_paused ? "warn" : undefined;
  return (
    <section className={c.allowance} data-tone={tone} data-testid="calls-allowance">
      <header className={c.allowanceHead}>
        <h3 className={c.allowanceTitle}>{t("settings.admin.callsThisMonth")}</h3>
        <span className={c.allowanceFigure} data-testid="calls-allowance-used">
          {t("settings.admin.callUsage", { used: formatDataSize(used), limit: formatDataSize(limit) })}
        </span>
        <Button size="sm" variant="ghost" className={c.refresh} onClick={onRefresh} disabled={refreshing} aria-label={t("moderation.calls.refresh")} title={t("moderation.calls.refresh")}>
          <RefreshCw size={15} aria-hidden="true" />
        </Button>
      </header>
      <div
        className={c.meter}
        role="meter"
        aria-label={t("settings.admin.callsThisMonth")}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(usedShare)}
        aria-valuetext={t("moderation.calls.shareOfMonth", { percent: formatPercent(shareOfAllowance(used, limit)) })}
      >
        {measured > 0 ? <span className={c.meterMeasured} style={{ width: barWidth(measuredShare) }} /> : null}
        {usedShare > measuredShare ? <span className={c.meterEstimated} style={{ insetInlineStart: `${measuredShare}%`, width: `${Math.max(0.6, usedShare - measuredShare)}%` }} /> : null}
        <span className={c.tick} style={{ insetInlineStart: "90%" }} />
        <span className={c.tick} style={{ insetInlineStart: "99%" }} />
      </div>
      <p className={c.hint}>{t("moderation.calls.thresholds")}</p>
      {meter.calls_paused ? <p className={c.warning}>{t("settings.admin.callsPaused")}</p> : meter.video_paused ? <p className={c.warning}>{t("settings.admin.callsVideoPaused")}</p> : null}
      <div className={c.allowanceFoot}>
        <span className={c.source} data-testid="calls-source">
          {meter.measured ? t("moderation.calls.measuredAgo", { when: formatRelative(meter.measured_at) }) : measurements ? t("moderation.calls.measuringSoon") : t("moderation.calls.estimatedHint")}
        </span>
        {meter.measured ? (
          <dl className={c.split}>
            <div>
              <dt>{t("moderation.calls.callMedia")}</dt>
              <dd>{formatDataSize(meter.measured_sfu_bytes)}</dd>
            </div>
            <div>
              <dt>{t("moderation.calls.relay")}</dt>
              <dd>{formatDataSize(meter.measured_turn_bytes)}</dd>
            </div>
          </dl>
        ) : null}
      </div>
    </section>
  );
}

function CallRow({ row, sort, limit, measurements, turnLimit, onOpen }: { row: ModCallRow; sort: CallSort; limit: number; measurements: boolean; turnLimit: number; onOpen: () => void }) {
  const received = row.received_bytes + row.relay_bytes;
  const figure = sort === "sent" ? formatDataSize(row.sent_bytes) : sort === "time" ? formatCallTime(row.seconds) : formatDataSize(received);
  const share = shareOfAllowance(sort === "sent" ? row.sent_bytes : received, limit);
  const name = row.user ? row.user.display_name || row.user.username : t("moderation.calls.guest");
  const estimated = measurements && !row.measured;
  return (
    <button type="button" className={c.row} onClick={onOpen} disabled={!row.user} data-testid="call-row" data-user={row.user_id}>
      <span className={c.avatar}>
        <Avatar id={row.user_id} avatarId={row.user?.avatar_id ?? null} name={name} size={36} />
      </span>
      <span className={c.who}>
        <span className={c.nameLine}>
          <span className={c.name}>
            <PersonName userId={row.user?.id ?? null} user={row.user}>{name}</PersonName>
          </span>
          {row.in_call ? (
            <span className={c.badge} data-tone="live">
              <Phone size={12} aria-hidden="true" /> {t("moderation.calls.inCall")}
            </span>
          ) : null}
          {row.relay_cut_off ? (
            <span className={c.badge} data-tone="warn" title={t("moderation.calls.relayCutOffHint", { limit: formatDataSize(turnLimit) })}>
              <Waypoints size={12} aria-hidden="true" /> {t("moderation.calls.relayCutOff")}
            </span>
          ) : null}
          {estimated ? <span className={c.badge}>{t("moderation.calls.estimatedTag")}</span> : null}
        </span>
        <span className={c.facts}>
          {sort !== "usage" ? (
            <span>
              <Download size={13} aria-hidden="true" /> {t("moderation.calls.receivedFact", { size: formatDataSize(received) })}
            </span>
          ) : null}
          {sort !== "sent" ? (
            <span>
              <Upload size={13} aria-hidden="true" /> {t("moderation.calls.sentFact", { size: formatDataSize(row.sent_bytes) })}
            </span>
          ) : null}
          {row.relay_bytes > 0 ? (
            <span>
              <Waypoints size={13} aria-hidden="true" /> {t("moderation.calls.relayFact", { size: formatDataSize(row.relay_bytes) })}
            </span>
          ) : null}
          {sort !== "time" ? (
            <span>
              <Clock size={13} aria-hidden="true" /> {t("moderation.calls.timeFact", { time: formatCallTime(row.seconds) })}
            </span>
          ) : null}
        </span>
      </span>
      <span className={c.figure}>{figure}</span>
      {/* its own row across the whole width, so every account's bar is the same length of allowance */}
      <span className={c.bar} title={t("moderation.calls.shareOfMonth", { percent: formatPercent(share) })}>
        <span className={c.barFill} data-estimated={!row.measured || undefined} data-large={share >= 10 || undefined} style={{ width: barWidth(share) }} />
      </span>
    </button>
  );
}
