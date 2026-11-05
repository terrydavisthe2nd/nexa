import { ArrowLeft, Check, Copy, ExternalLink, Gavel, Inbox, RefreshCw, Search, SlidersHorizontal, ThumbsDown, UserRound } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { MessageItem } from "@/components/chat/MessageItem";
import { sanitizePayload } from "@/crypto/message";
import { sanitizeHook } from "@/lib/webhookMessage";
import type { DecryptedMessage } from "@/lib/decrypt";
import { INLINE_MAX } from "@/lib/mediaCache";
import { ReportSnapshot, ReportTargetCard } from "./ReportSnapshot";
import { ReasonGlyph } from "./ReasonGlyph";
import { bugFileURL } from "@/lib/bugMedia";
import { ThreadFiles } from "@/components/nexa/ThreadFiles";
import { PersonChip } from "@/components/nexa/NexaBits";
import { REPORT_KINDS, REPORT_REASONS, reports, type ReportDetail, type SafetyReport } from "@/api/reports";
import type { BugFile } from "@/api/bugs";
import type { People } from "@/api/moderation";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { TextArea } from "@/components/common/TextInput";
import { describeError } from "@/lib/errors";
import { fullDate, personName, relativeTo } from "@/lib/moderation";
import { reasonTone, worstReason } from "@/lib/reportReasons";
import { useAuth } from "@/stores/auth";
import { useModeration, useModEvents } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import { ActionPanel, DismissPanel } from "./ReportDecision";
import s from "./Reports.module.css";

const BASE = "/channels/@admin/reports";
/** The queue's views, as the chips across its top offer them. */
const VIEWS = ["active", "mine", "closed", "all"] as const;
type View = (typeof VIEWS)[number];

export const closing = (status: string) => status === "actioned" || status === "dismissed";

/**
 * The staff's report queue. Each reported thing is one row however many
 * people reported it, showing what was reported rather than only what the
 * reporter said, with how pressing its reason is along its edge. The open
 * report sits beside it (over it on a phone) and is decided where it is read:
 * Take Action and Dismiss sit right under the reported content, and deciding
 * moves straight on to the next report.
 */
export function ReportsTab() {
  const { reportId } = useParams();
  const nav = useNavigate();
  const [filters, setFilters] = useSearchParams();
  const legacy = filters.get("status");
  const view: View = filters.get("view") === "mine" || filters.get("assigned") === "me" || filters.get("mine") === "1" ? "mine" : legacy === "closed" ? "closed" : legacy === "all" ? "all" : "active";
  const kind = filters.get("kind") ?? "";
  const reason = filters.get("reason") ?? "";
  const unclaimed = filters.get("assigned") === "none";
  const sort = filters.get("sort") === "oldest" ? "oldest" : "newest";
  const query = filters.get("q") ?? "";
  const setFilter = (key: string, value: string) =>
    setFilters(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.delete("mine");
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );
  const setView = (v: View) =>
    setFilters(
      (previous) => {
        const next = new URLSearchParams(previous);
        for (const k of ["mine", "view", "status"]) next.delete(k);
        if (next.get("assigned") === "me") next.delete("assigned");
        if (v === "mine") next.set("view", "mine");
        if (v === "closed" || v === "all") next.set("status", v);
        return next;
      },
      { replace: true },
    );
  const suffix = filters.size ? `?${filters}` : "";
  const narrowed = !!(kind || reason || unclaimed || sort === "oldest");
  const filtered = narrowed || !!query;
  const [showFilters, setShowFilters] = useState(narrowed);

  const me = useAuth((a) => a.me?.id);
  const counts = useModeration((m) => m.reportCounts) ?? {};
  const [list, setList] = useState<SafetyReport[]>([]);
  const [people, setPeople] = useState<People>({});
  const [more, setMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const generation = useRef(0);
  const [refresh, setRefresh] = useState(0);
  // reports that came in since the queue was opened, which rise in as they land
  const [fresh, setFresh] = useState<Set<string>>(new Set());

  const load = useCallback(
    async (cursor?: string) => {
      const ticket = ++generation.current;
      setLoading(true);
      setError("");
      try {
        const r = await reports.list({
          status: view === "closed" ? "closed" : view === "all" ? "" : "active",
          assigned: view === "mine" ? "me" : unclaimed ? "none" : "",
          group: "item",
          kind,
          reason,
          sort,
          q: query,
          ...(cursor ? (sort === "oldest" ? { after: cursor } : { before: cursor }) : {}),
        });
        if (ticket !== generation.current) return;
        setList((old) => (cursor ? [...old, ...r.reports.filter((x) => !old.some((y) => sameItem(x, y)))] : r.reports));
        setPeople((p) => ({ ...p, ...r.people }));
        useModeration.getState().setReportCounts(r.counts);
        setMore(r.has_more);
      } catch (err) {
        if (ticket === generation.current) setError(describeError(err, t("reports.failed")));
      } finally {
        if (ticket === generation.current) setLoading(false);
      }
    },
    [view, kind, reason, unclaimed, sort, query],
  );
  useEffect(() => {
    setList([]);
    setMore(false);
    const timer = setTimeout(() => void load(), query ? 250 : 0);
    return () => {
      clearTimeout(timer);
      generation.current++;
    };
  }, [load, refresh]);

  // Reports arrive and move as they happen. One about an item already listed
  // takes that item's row (the newest report speaks for it); a new item that
  // fits the view joins the top of a newest-first queue; one that no longer
  // fits (decided, or claimed by someone else under Mine) leaves.
  useModEvents((e) => {
    if (e.type !== "report") return;
    setPeople((p) => ({ ...p, ...e.people }));
    setList((cur) => {
      let next = cur;
      for (const r of e.reports) {
        const at = next.findIndex((x) => x.id === r.id || sameItem(x, r));
        const belongs = fits(r, { view, kind, reason, unclaimed, me });
        if (at >= 0) {
          if (belongs) next = next.map((x, i) => (i === at ? (r.id === x.id || r.id > x.id ? r : { ...x, duplicates: r.duplicates, reasons: r.reasons }) : x));
          else if (next[at]!.id === r.id && r.id !== reportId) next = next.filter((_, i) => i !== at);
        } else if (belongs && sort === "newest" && !query) {
          next = [r, ...next];
          setFresh((f) => new Set(f).add(r.id));
        }
      }
      return next;
    });
  });

  const waiting = (counts.open ?? 0) + (counts.in_review ?? 0);
  const viewCount = (v: View) => (v === "active" ? waiting : v === "closed" ? (counts.actioned ?? 0) + (counts.dismissed ?? 0) : v === "all" ? Object.values(counts).reduce((a, b) => a + b, 0) : null);

  // after a decision the next report in the queue opens, so a run of them goes quickly
  const openNext = (decided: string) => {
    const at = list.findIndex((x) => x.id === decided);
    const rest = list.filter((x) => x.id !== decided && !closing(x.status));
    const next = rest[Math.max(0, at)] ?? rest[0];
    if (view === "active" || view === "mine") setList((cur) => cur.filter((x) => x.id !== decided));
    nav(next ? `${BASE}/${next.id}${suffix}` : `${BASE}${suffix}`, { replace: true });
  };

  return (
    <div className={s.workspace} data-open={!!reportId} data-testid="reports-workspace">
      <aside className={s.queue} aria-label={t("reports.title")}>
        <div className={s.queueHead}>
          <div className={s.views} role="radiogroup" aria-label={t("reports.status")} data-glide="">
            {VIEWS.map((v) => {
              const n = viewCount(v);
              return (
                <button key={v} type="button" role="radio" aria-checked={view === v} className={s.viewChip} onClick={() => setView(v)} data-testid={`report-view-${v}`}>
                  {t(`reports.view.${v}`)}
                  {n !== null ? <span className={s.viewCount}>{n}</span> : null}
                </button>
              );
            })}
          </div>
          <div className={s.searchRow}>
            <label className={s.search}>
              <Search size={16} aria-hidden="true" />
              <input value={query} onChange={(e) => setFilter("q", e.target.value)} placeholder={t("reports.searchHint")} aria-label={t("reports.search")} maxLength={200} />
            </label>
            <button
              type="button"
              className={s.filterToggle}
              aria-expanded={showFilters}
              aria-controls="report-filters"
              aria-label={t("reports.filters")}
              title={t("reports.filters")}
              data-active={narrowed || undefined}
              onClick={() => setShowFilters((x) => !x)}
            >
              <SlidersHorizontal size={17} aria-hidden="true" />
            </button>
            <button type="button" className={s.filterToggle} onClick={() => setRefresh((n) => n + 1)} disabled={loading} aria-label={t("reports.refresh")} title={t("reports.refresh")}>
              <RefreshCw size={16} aria-hidden="true" />
            </button>
          </div>
          <div className={s.filterGrid} id="report-filters" hidden={!showFilters}>
            <Select aria-label={t("reports.reason")} value={reason} onChange={(e) => setFilter("reason", e.target.value)}>
              <option value="">{t("reports.allReasons")}</option>
              {REPORT_REASONS.map((r) => (
                <option key={r} value={r}>
                  {t(`reports.reason.${r}`)}
                </option>
              ))}
            </Select>
            <Select aria-label={t("reports.target")} value={kind} onChange={(e) => setFilter("kind", e.target.value)}>
              <option value="">{t("reports.allKinds")}</option>
              {REPORT_KINDS.map((k) => (
                <option key={k} value={k}>
                  {t(`reports.kind.${k}`)}
                </option>
              ))}
            </Select>
            <Select aria-label={t("reports.assignment")} value={unclaimed ? "none" : ""} onChange={(e) => setFilter("assigned", e.target.value)} disabled={view === "mine"}>
              <option value="">{t("reports.anyone")}</option>
              <option value="none">{t("reports.unassigned")}</option>
            </Select>
            <Select aria-label={t("reports.sort")} value={sort} onChange={(e) => setFilter("sort", e.target.value === "oldest" ? "oldest" : "")}>
              <option value="newest">{t("reports.newestFirst")}</option>
              <option value="oldest">{t("reports.oldestFirst")}</option>
            </Select>
          </div>
        </div>
        {error && (
          <p role="alert" className={s.error}>
            {error}
          </p>
        )}
        <div className={s.list} aria-busy={loading} data-testid="report-queue">
          {!loading && !list.length && !error && (
            <div className={s.emptyQueue}>
              <Inbox size={28} aria-hidden="true" />
              <p>{!filtered && view === "active" ? t("reports.allClear") : t("reports.empty")}</p>
              {filtered && (
                <Button variant="secondary" size="sm" onClick={() => setFilters(view === "mine" ? { view: "mine" } : view === "active" ? {} : { status: view }, { replace: true })}>
                  {t("reports.clearFilters")}
                </Button>
              )}
            </div>
          )}
          {list.map((v) => (
            <QueueRow key={v.id} report={v} people={people} current={v.id === reportId} fresh={fresh.has(v.id)} onOpen={() => nav(`${BASE}/${v.id}${suffix}`)} />
          ))}
          {loading && (
            <p role="status" className={s.listStatus}>
              {t("reports.loading")}
            </p>
          )}
          {more && !loading && (
            <Button variant="secondary" block onClick={() => void load(list.at(-1)?.id)}>
              {t("reports.more")}
            </Button>
          )}
        </div>
      </aside>
      <main className={s.detail}>
        {reportId ? (
          <ReportWorkspace key={reportId} id={reportId} onBack={() => nav(`${BASE}${suffix}`)} onOpen={(rid) => nav(`${BASE}/${rid}${suffix}`)} onDecided={openNext} />
        ) : (
          <div className={s.emptyDetail}>
            <Gavel size={32} aria-hidden="true" />
            <p>{t("reports.select")}</p>
          </div>
        )}
      </main>
    </div>
  );
}

/** Two reports about the same reported thing ("something else" names none). */
function sameItem(a: SafetyReport, b: SafetyReport): boolean {
  return a.target_id !== "0" && a.target_kind === b.target_kind && a.target_id === b.target_id;
}

/** Whether a report that just arrived or changed belongs in the queue as it is filtered. */
function fits(r: SafetyReport, f: { view: View; kind: string; reason: string; unclaimed: boolean; me: string | undefined }): boolean {
  if ((f.view === "active" || f.view === "mine") && closing(r.status)) return false;
  if (f.view === "closed" && !closing(r.status)) return false;
  if (f.view === "mine" && r.assigned_to !== f.me) return false;
  if (f.kind && r.target_kind !== f.kind) return false;
  if (f.reason && r.reason !== f.reason) return false;
  if (f.unclaimed && r.assigned_to !== "0") return false;
  return true;
}

function snapshotName(v: SafetyReport): string {
  const snap = v.snapshot;
  for (const key of ["display_name", "name", "username"]) if (typeof snap[key] === "string" && snap[key]) return snap[key] as string;
  return "";
}

/** What was reported, in a line: a message's words, or the thing's name. */
function reportedLine(v: SafetyReport): string {
  const content = v.snapshot.message_content as Record<string, unknown> | undefined;
  if (content) {
    const text = typeof content.t === "string" ? content.t : "";
    if (text) return text;
    if (Array.isArray(content.att) && content.att.length) return t("reports.queue.files", { count: content.att.length });
    if (content.sticker) return t("reports.part.sticker");
  }
  return v.excerpt || "";
}

export function StatusPill({ status }: { status: string }) {
  return (
    <span className={s.badge} data-status={status}>
      {t(`reports.status.${status}`)}
    </span>
  );
}

/** One reported thing in the queue: why, about whom, what it said, and where it stands. */
function QueueRow({ report: v, people, current, fresh, onOpen }: { report: SafetyReport; people: People; current: boolean; fresh: boolean; onOpen: () => void }) {
  const subject = v.subject_id !== "0" ? people[v.subject_id] : undefined;
  const assignee = v.assigned_to !== "0" ? people[v.assigned_to] : undefined;
  const person = v.target_kind === "user" || v.target_kind === "message";
  const name = person ? (subject ? personName(subject) : "") || snapshotName(v) : snapshotName(v) || (subject ? personName(subject) : "");
  const line = reportedLine(v);
  const count = v.duplicates ?? 1;
  // a row standing for several reports speaks with the most pressing reason among them
  const reason = worstReason(v.reason, v.reasons);
  return (
    <button type="button" className={s.row} aria-current={current} onClick={onOpen} data-testid="report-row" data-status={v.status} data-tone={reasonTone(reason)} data-fresh={fresh || undefined}>
      <span className={s.rowTop}>
        <ReasonGlyph reason={reason} size="sm" />
        <strong className={s.rowReason}>{t(`reports.reason.${reason}`)}</strong>
        <time className={s.rowTime} dateTime={new Date(v.created_at).toISOString()} title={fullDate(v.created_at)}>
          {relativeTo(v.created_at)}
        </time>
      </span>
      <span className={s.rowSubject}>
        {subject && person ? <Avatar id={v.subject_id} avatarId={subject.avatar_id} name={name} size={20} /> : null}
        {name ? (
          <span className={s.rowName} dir="auto">
            {name}
          </span>
        ) : null}
        <span className={s.rowKind}>{t(`reports.kind.${v.target_kind}`)}</span>
        {count > 1 ? <span className={s.dupes}>{t("reports.duplicates", { count })}</span> : null}
      </span>
      {line ? (
        <span className={s.rowContent} dir="auto">
          {line}
        </span>
      ) : null}
      {v.description && v.description !== line ? (
        <span className={s.rowText} dir="auto">
          {v.description}
        </span>
      ) : null}
      {v.status !== "open" || assignee ? (
        <span className={s.rowFoot}>
          {v.status !== "open" ? <StatusPill status={v.status} /> : null}
          {assignee ? (
            <span className={s.rowAssignee}>
              <Avatar id={v.assigned_to} avatarId={assignee.avatar_id} name={personName(assignee)} size={16} />
              {personName(assignee)}
            </span>
          ) : null}
        </span>
      ) : null}
    </button>
  );
}

type Deciding = "none" | "act" | "dismiss";

function ReportWorkspace({ id, onBack, onOpen, onDecided }: { id: string; onBack: () => void; onOpen: (id: string) => void; onDecided: (id: string) => void }) {
  const nav = useNavigate();
  const location = useLocation();
  const related = (path: string) => nav(path, { state: { reportReturnTo: location.pathname + location.search } });
  const me = useAuth((st) => st.me?.id);
  const [detail, setDetail] = useState<ReportDetail | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [deciding, setDeciding] = useState<Deciding>("none");
  const [note, setNote] = useState("");
  const lock = useRef(false);

  const load = useCallback(async () => {
    try {
      const r = await reports.detail(id);
      setDetail(r);
      setError("");
      return r;
    } catch (err) {
      setError(describeError(err, t("reports.failed")));
      return null;
    }
  }, [id]);
  useEffect(() => {
    void load();
  }, [load]);
  // another member of staff decided or claimed it: show what stands now
  useModEvents((e) => {
    if (e.type === "report" && !lock.current && e.reports.some((r) => r.id === id && r.version !== detail?.report.version)) void load();
  });

  const v = detail?.report;
  const run = async (work: () => Promise<void>) => {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (err) {
      setError(describeError(err, t("reports.failed")));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  };
  // claiming puts an open report in review; returning it to the queue opens it again
  const assign = (to: string) =>
    run(async () => {
      if (!v) return;
      const status = to !== "0" && v.status === "open" ? "in_review" : to === "0" && v.status === "in_review" ? "open" : v.status;
      setDetail(await reports.review(id, { version: v.version, status, note: "", assigned_to: to }));
    });
  const addNote = () =>
    run(async () => {
      if (!v || !note.trim()) return;
      setDetail(await reports.review(id, { version: v.version, status: v.status, note: note.trim(), assigned_to: v.assigned_to }));
      setNote("");
    });
  const reopen = () =>
    run(async () => {
      if (!v) return;
      setDetail(await reports.review(id, { version: v.version, status: "open", note: "", assigned_to: v.assigned_to }));
    });

  if (!v || !detail) {
    return (
      <div className={s.detailInner}>
        <button type="button" className={s.back} onClick={onBack}>
          <ArrowLeft size={18} aria-hidden="true" />
          {t("reports.back")}
        </button>
        {error ? (
          <div role="alert" className={s.error}>
            {error}
            <Button variant="link" onClick={() => void load()}>
              {t("reports.retry")}
            </Button>
          </div>
        ) : (
          <p role="status" className={s.notice}>
            {t("reports.loading")}
          </p>
        )}
      </div>
    );
  }

  const person = (uid: string) => detail.people[uid];
  const isMessage = !!v.snapshot.message_content;
  const subject = detail.subject;
  const stats = detail.reporter_stats;
  const sent = stats ? Object.values(stats).reduce((a, b) => a + b, 0) : 0;
  const sameItemReports = (detail.related ?? []).filter((x) => sameItem(x, v));
  const aboutAccount = (detail.related ?? []).filter((x) => !sameItem(x, v));
  const openSame = sameItemReports.filter((x) => !closing(x.status)).length;
  const closed = closing(v.status);
  const lastDecision = [...detail.events].reverse().find((e) => closing(e.status));
  const subjectName = subject ? subject.display_name || subject.username : personName(person(v.subject_id));
  const packId = v.target_kind === "pack" ? v.target_id : v.target_kind === "sticker" && v.snapshot.pack_id ? String(v.snapshot.pack_id) : "";

  return (
    <article className={s.detailInner} data-testid="report-detail" data-status={v.status}>
      <button type="button" className={s.back} onClick={onBack}>
        <ArrowLeft size={18} aria-hidden="true" />
        {t("reports.back")}
      </button>
      <header className={s.reportHeader} data-tone={reasonTone(v.reason)}>
        <ReasonGlyph reason={v.reason} />
        <div className={s.headerText}>
          <h2>{t(`reports.reason.${v.reason}`)}</h2>
          <p className={s.headerMeta}>
            <span>{t("reports.kindReport", { kind: t(`reports.kind.${v.target_kind}`) })}</span>
            <time dateTime={new Date(v.created_at).toISOString()} title={fullDate(v.created_at)}>
              {relativeTo(v.created_at)}
            </time>
            {openSame + (closed ? 0 : 1) > 1 ? <span className={s.dupes}>{t("reports.duplicates", { count: openSame + 1 })}</span> : null}
          </p>
        </div>
        <StatusPill status={v.status} />
      </header>

      <div className={s.claimRow}>
        {v.assigned_to !== "0" ? (
          <span className={s.claimWho}>
            <span className={s.muted}>{t("reports.assignment")}</span>
            <PersonChip id={v.assigned_to} person={person(v.assigned_to)} size={18} />
          </span>
        ) : (
          <span className={s.muted}>{t("reports.unassigned")}</span>
        )}
        {!closed ? (
          v.assigned_to !== me ? (
            <Button variant="ghost" size="sm" disabled={busy || !me} onClick={() => void assign(me ?? "0")} data-testid="report-claim">
              {t("reports.claim")}
            </Button>
          ) : (
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => void assign("0")}>
              {t("reports.unassign")}
            </Button>
          )
        ) : null}
        <span className={s.spacer} />
        <button
          type="button"
          className={s.copyId}
          title={t("reports.copyId")}
          onClick={() => void navigator.clipboard?.writeText(v.id).then(() => useUI.getState().toast(t("apps.portal.copiedShort"), "success"))}
        >
          <Copy size={13} aria-hidden="true" />
          {t("reports.reportId")}
        </button>
      </div>

      {error && (
        <div role="alert" className={s.error}>
          {error}
          <Button variant="link" onClick={() => void load()} disabled={busy}>
            {t("reports.retry")}
          </Button>
        </div>
      )}

      <section className={s.content} aria-labelledby="report-content">
        <h3 id="report-content" className="visually-hidden">
          {t("reports.reportedContent")}
        </h3>
        {isMessage ? (
          <div className={s.evidenceRow}>
            <MessageItem
              msg={reportedChatMessage(v)}
              groupStart
              preview
              previewAuthor={{ name: personName(person(v.subject_id)) || v.subject_id, avatarId: person(v.subject_id)?.avatar_id ?? null }}
              evidence={
                <div className={s.retainedFiles}>
                  {!!v.snapshot.message_sticker && <ReportSnapshot snapshot={v.snapshot.message_sticker as Record<string, unknown>} kind="sticker" saved />}
                  <EvidenceFiles files={detail.files} onError={setError} />
                </div>
              }
            />
          </div>
        ) : (
          <div className={s.targetBox}>
            {v.target_kind !== "other" && <ReportTargetCard kind={v.target_kind} snapshot={v.snapshot} saved />}
            {v.part && v.part !== "whole" ? <p className={s.partLine}>{t("reports.reportedPart", { part: v.part === "custom" ? v.custom_part || t("reports.part.custom") : t(`reports.part.${v.part}`) })}</p> : null}
            {v.excerpt ? <pre className={s.preview}>{v.excerpt}</pre> : null}
            <EvidenceFiles files={detail.files} onError={setError} />
          </div>
        )}
        <p className={s.unverified}>{t("reports.unverified")}</p>
      </section>

      {!closed ? (
        <section className={s.decideBox} aria-label={t("reports.decision.title")} data-testid="report-decision">
          {deciding === "none" ? (
            <div className={s.decideBar}>
              <Button variant="danger" onClick={() => setDeciding("act")} disabled={busy} data-testid="report-act">
                <Gavel size={16} aria-hidden="true" /> {t("reports.act.takeAction")}
              </Button>
              <Button variant="secondary" onClick={() => setDeciding("dismiss")} disabled={busy} data-testid="report-dismiss">
                <ThumbsDown size={16} aria-hidden="true" /> {t("reports.act.dismiss")}
              </Button>
              <span className={s.spacer} />
              {v.subject_id !== "0" ? (
                <Button variant="ghost" size="sm" onClick={() => related(`/channels/@admin/users/${v.subject_id}`)}>
                  <UserRound size={15} aria-hidden="true" /> {t("reports.openAccount")}
                </Button>
              ) : null}
            </div>
          ) : deciding === "act" ? (
            <ActionPanel detail={detail} others={openSame} onCancel={() => setDeciding("none")} onDone={() => onDecided(v.id)} onChanged={() => void load()} />
          ) : (
            <DismissPanel detail={detail} others={openSame} onCancel={() => setDeciding("none")} onDone={() => onDecided(v.id)} />
          )}
        </section>
      ) : (
        <section className={s.outcomeBox} data-status={v.status}>
          <Check size={18} aria-hidden="true" />
          <div className={s.outcomeText}>
            <strong>{t(`reports.status.${v.status}`)}</strong>
            {lastDecision ? (
              <span className={s.muted}>
                <PersonChip id={lastDecision.actor_id} person={person(lastDecision.actor_id)} size={16} /> {relativeTo(lastDecision.created_at)}
              </span>
            ) : null}
            {lastDecision?.note ? <span className={s.outcomeNote}>{lastDecision.note}</span> : null}
          </div>
          <Button variant="secondary" size="sm" onClick={() => void reopen()} disabled={busy}>
            {t("reports.decision.reopen")}
          </Button>
        </section>
      )}

      <div className={s.detailGrid}>
        <div className={s.mainCol}>
          <section className={s.said} aria-labelledby="report-said">
            <h3 id="report-said">{t("reports.whatTheySaid", { count: sameItemReports.length + 1 })}</h3>
            <ul className={s.voices} role="list">
              {[v, ...sameItemReports].map((x) => (
                <li key={x.id} className={s.voice} data-current={x.id === v.id || undefined}>
                  <div className={s.voiceHead}>
                    <PersonChip id={x.reporter_id} person={person(x.reporter_id)} size={18} />
                    {x.reason !== v.reason ? <span className={s.voiceReason}>{t(`reports.reason.${x.reason}`)}</span> : null}
                    <time className={s.rowTime} title={fullDate(x.created_at)}>
                      {relativeTo(x.created_at)}
                    </time>
                    {x.id !== v.id ? (
                      <button type="button" className={s.voiceOpen} onClick={() => onOpen(x.id)} aria-label={t("reports.openThis")} title={t("reports.openThis")}>
                        <ExternalLink size={13} aria-hidden="true" />
                      </button>
                    ) : null}
                  </div>
                  {x.description ? (
                    <p className={s.voiceText} dir="auto">
                      {x.description}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>

          {aboutAccount.length ? (
            <section className={s.relatedBox} aria-labelledby="report-related">
              <h3 id="report-related">{t("reports.aboutAccount", { count: aboutAccount.length })}</h3>
              <ul className={s.relatedList} role="list">
                {aboutAccount.map((x) => (
                  <li key={x.id}>
                    <button type="button" className={s.relatedRow} onClick={() => onOpen(x.id)}>
                      <ReasonGlyph reason={x.reason} size="sm" />
                      <span className={s.relatedText}>
                        <span className={s.relatedTitle}>{t(`reports.reason.${x.reason}`)}</span>
                        <span className={s.muted}>{t(`reports.kind.${x.target_kind}`)}</span>
                      </span>
                      <StatusPill status={x.status} />
                      <time className={s.rowTime} title={fullDate(x.created_at)}>
                        {relativeTo(x.created_at)}
                      </time>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <details className={s.historyBox}>
            <summary>
              <h3>{t("reports.history")}</h3>
            </summary>
            <ol className={s.timeline}>
              <TimelineItem at={v.created_at} who={<PersonChip id={v.reporter_id} person={person(v.reporter_id)} size={16} />}>
                {t("reports.timeline.sent")}
              </TimelineItem>
              {detail.events.map((e) => (
                <TimelineItem key={e.id} at={e.created_at} who={<PersonChip id={e.actor_id} person={person(e.actor_id)} size={16} />} status={e.status}>
                  {t("reports.timeline.status", { status: t(`reports.status.${e.status}`) })}
                  {e.note && <span className={s.timelineNote}>{e.note}</span>}
                </TimelineItem>
              ))}
              {v.outcome_at ? (
                <TimelineItem at={v.outcome_at} who={null}>
                  {t("reports.timeline.told")}
                  <span className={s.timelineNote}>{v.outcome || t(`reports.outcome.${v.status === "dismissed" ? "dismissed" : "actioned"}`)}</span>
                </TimelineItem>
              ) : null}
            </ol>
            <div className={s.noteRow}>
              <TextArea value={note} rows={2} maxLength={2000} disabled={busy} onChange={(e) => setNote(e.target.value)} placeholder={t("reports.noteHint")} aria-label={t("reports.staffNote")} data-testid="report-note" />
              <Button variant="secondary" size="sm" disabled={busy || !note.trim()} onClick={() => void addNote()}>
                {t("reports.addNote")}
              </Button>
            </div>
            <details className={s.technical}>
              <summary>{t("reports.sharedDetails")}</summary>
              <p className={s.notice}>{t("reports.snapshotHint")}</p>
              <ReportSnapshot snapshot={Object.fromEntries(Object.entries(v.snapshot).filter(([key]) => key !== "message_content" && key !== "message_sticker"))} kind={v.target_kind} saved />
            </details>
          </details>
        </div>

        <aside className={s.sideCol} aria-label={t("reports.people")}>
          {v.subject_id !== "0" ? (
            <section className={s.panel}>
              <h3>{t("reports.subject")}</h3>
              <PersonChip id={v.subject_id} person={person(v.subject_id)} size={32} withUsername />
              {subject ? (
                <ul className={s.standing} role="list">
                  <li data-tone={subject.ban ? "danger" : subject.mute ? "warning" : undefined}>{subject.ban ? t("reports.standing.banned") : subject.mute ? t("reports.standing.muted") : t("reports.standing.clear")}</li>
                  <li>{t("reports.standing.cases", { count: subject.case_count })}</li>
                  <li>{t("reports.standing.reports", { count: aboutAccount.length + sameItemReports.length + 1 })}</li>
                  {subject.alt_count > 0 && <li data-tone="warning">{t("reports.standing.alts", { count: subject.alt_count })}</li>}
                </ul>
              ) : null}
              <div className={s.panelActions}>
                <Button variant="secondary" size="sm" onClick={() => related(`/channels/@admin/users/${v.subject_id}`)} aria-label={t("reports.openAccountOf", { name: subjectName })}>
                  {t("reports.openAccount")}
                </Button>
                {v.server_id !== "0" ? (
                  <Button variant="ghost" size="sm" onClick={() => related(`/channels/@admin/servers/${v.server_id}`)}>
                    {t("reports.openServer")}
                  </Button>
                ) : null}
                {packId ? (
                  <Button variant="ghost" size="sm" onClick={() => related(`/channels/@admin/expressions/packs/${packId}`)}>
                    {t("reports.openPack")}
                  </Button>
                ) : null}
              </div>
            </section>
          ) : null}
          <section className={s.panel}>
            <h3>{t("reports.reporter")}</h3>
            <PersonChip id={v.reporter_id} person={person(v.reporter_id)} size={28} withUsername />
            {stats && (
              <dl className={s.stats}>
                <div>
                  <dt>{t("reports.stats.sent")}</dt>
                  <dd>{sent}</dd>
                </div>
                <div>
                  <dt>{t("reports.stats.actioned")}</dt>
                  <dd>{stats.actioned ?? 0}</dd>
                </div>
                <div>
                  <dt>{t("reports.stats.dismissed")}</dt>
                  <dd>{stats.dismissed ?? 0}</dd>
                </div>
              </dl>
            )}
          </section>
        </aside>
      </div>
    </article>
  );
}

function TimelineItem({ at, who, status, children }: { at: number; who: ReactNode; status?: string; children: ReactNode }) {
  return (
    <li className={s.timelineItem} data-status={status}>
      <span className={s.timelineDot} aria-hidden="true" />
      <div className={s.timelineBody}>
        <div className={s.timelineHead}>
          {who}
          <span>{children}</span>
        </div>
        <time className={s.muted} title={fullDate(at)}>
          {fullDate(at)}
        </time>
      </div>
    </li>
  );
}

const INLINE_TYPES = /^(image\/(png|jpeg|gif|webp|avif)$|video\/)/i;

/** Files kept with the report: pictures and videos drawn, the rest to open. */
function EvidenceFiles({ files, onError }: { files: BugFile[]; onError: (e: string) => void }) {
  const inline = useMemo(() => files.filter((f) => f.size <= INLINE_MAX && INLINE_TYPES.test(f.mime)), [files]);
  const rest = files.filter((f) => !inline.includes(f));
  if (!files.length) return null;
  return (
    <>
      {inline.length > 0 && <ThreadFiles files={inline} kind="reports" />}
      {rest.map((f) => (
        <Button
          key={f.id}
          variant="secondary"
          onClick={async () => {
            try {
              const url = await bugFileURL(f.id, undefined, "reports");
              const a = document.createElement("a");
              a.href = url;
              a.download = f.name || f.id;
              a.click();
            } catch (err) {
              onError(describeError(err, t("reports.failed")));
            }
          }}
        >
          {t("reports.openFile")}: {f.name}
        </Button>
      ))}
    </>
  );
}

function reportedChatMessage(report: SafetyReport): DecryptedMessage {
  const content = report.snapshot.message_content as Record<string, unknown>;
  const { att: _att, sticker: _sticker, ...body } = content;
  return {
    id: report.target_id,
    channel_id: String(report.snapshot.channel_id),
    author_id: report.subject_id,
    type: String(content.type ?? "default"),
    created_at: Number(report.snapshot.created_at ?? report.created_at),
    edited_at: report.snapshot.edited_at ? Number(report.snapshot.edited_at) : null,
    ciphertext: null,
    key_conv_id: null,
    key_epoch: null,
    reply_to_id: null,
    mentions: [],
    mention_roles: [],
    mention_everyone: false,
    attachments: [],
    reactions: [],
    flags: 0,
    payload: sanitizePayload(body),
    ...(content.call ? { call: content.call as DecryptedMessage["call"] } : {}),
    ...(content.hook ? { hook: sanitizeHook(content.hook) ?? undefined } : {}),
  };
}
