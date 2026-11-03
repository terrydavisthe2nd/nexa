import {
  ArrowLeft,
  Copy,
  ExternalLink,
  Gavel,
  Inbox,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
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
import {
  REPORT_KINDS,
  REPORT_REASONS,
  REPORT_STATUSES,
  reports,
  type ReportDetail,
  type ReportStatus,
  type SafetyReport,
} from "@/api/reports";
import type { BugFile } from "@/api/bugs";
import { moderation, type ModUser, type People } from "@/api/moderation";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { Field, TextArea } from "@/components/common/TextInput";
import { ModerateModal } from "@/components/moderation/ModerateModal";
import { describeError } from "@/lib/errors";
import { fullDate, personName, relativeTo } from "@/lib/moderation";
import { atLeast, useMyRank } from "@/lib/staffRank";
import { useAuth } from "@/stores/auth";
import { useModeration, useModEvents } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import s from "./Reports.module.css";

const BASE = "/channels/@admin/reports";
/** The queue's views, as the chips across its top offer them. */
const VIEWS = ["active", "open", "in_review", "closed", "all"] as const;
type View = (typeof VIEWS)[number];

const closing = (status: string) =>
  status === "actioned" || status === "dismissed";

/**
 * The staff's report queue: everything people reported, the most pressing
 * shown as such, filtered in the address so a view can be shared, and moving
 * the moment anyone reports or decides. The open report sits beside it (over
 * it on a phone) with everything needed to decide: what was said, what was
 * reported, who is behind it and their record, the other reports about it,
 * and the decision with what the reporter will be told.
 */
export function ReportsTab() {
  const { reportId } = useParams();
  const nav = useNavigate();
  const [filters, setFilters] = useSearchParams();
  const view = (VIEWS as readonly string[]).includes(
    filters.get("status") ?? "",
  )
    ? (filters.get("status") as View)
    : "active";
  const kind = filters.get("kind") ?? "";
  const reason = filters.get("reason") ?? "";
  const assigned =
    filters.get("assigned") ?? (filters.get("mine") === "1" ? "me" : "");
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
  const suffix = filters.size ? `?${filters}` : "";
  const filtered = !!(kind || reason || assigned || query || sort === "oldest");
  // the filters other than search, which fold away until wanted
  const narrowed = !!(kind || reason || assigned || sort === "oldest");
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

  const load = useCallback(
    async (cursor?: string) => {
      const ticket = ++generation.current;
      setLoading(true);
      setError("");
      try {
        const r = await reports.list({
          status: view === "all" ? "" : view,
          kind,
          reason,
          assigned,
          sort,
          q: query,
          ...(cursor
            ? sort === "oldest"
              ? { after: cursor }
              : { before: cursor }
            : {}),
        });
        if (ticket !== generation.current) return;
        setList((old) =>
          cursor
            ? [
                ...old,
                ...r.reports.filter((x) => !old.some((y) => y.id === x.id)),
              ]
            : r.reports,
        );
        setPeople((p) => ({ ...p, ...r.people }));
        useModeration.getState().setReportCounts(r.counts);
        setMore(r.has_more);
      } catch (err) {
        if (ticket === generation.current)
          setError(describeError(err, t("reports.failed")));
      } finally {
        if (ticket === generation.current) setLoading(false);
      }
    },
    [view, kind, reason, assigned, sort, query],
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

  // reports arrive and move as they happen: a changed one is replaced where it
  // is, a new one that fits the view joins the top of a newest-first queue
  useModEvents((e) => {
    if (e.type !== "report") return;
    setPeople((p) => ({ ...p, ...e.people }));
    setList((cur) => {
      let next = cur;
      for (const r of e.reports) {
        const at = next.findIndex((x) => x.id === r.id);
        if (at >= 0) next = next.map((x) => (x.id === r.id ? r : x));
        else if (
          sort === "newest" &&
          !query &&
          fits(r, { view, kind, reason, assigned, me })
        )
          next = [r, ...next];
      }
      return next;
    });
  });

  const waiting = (counts.open ?? 0) + (counts.in_review ?? 0);
  const viewCount = (v: View) =>
    v === "active"
      ? waiting
      : v === "closed"
        ? (counts.actioned ?? 0) + (counts.dismissed ?? 0)
        : v === "all"
          ? Object.values(counts).reduce((a, b) => a + b, 0)
          : (counts[v] ?? 0);

  return (
    <div
      className={s.workspace}
      data-open={!!reportId}
      data-testid="reports-workspace"
    >
      <aside className={s.queue} aria-label={t("reports.title")}>
        <div className={s.queueHead}>
          <div className={s.queueTitle}>
            <h2>{t("reports.title")}</h2>
            <span className={s.queueWaiting}>
              {t("reports.waitingCount", { count: waiting })}
            </span>
            <button
              type="button"
              className={s.iconButton}
              onClick={() => setRefresh((n) => n + 1)}
              disabled={loading}
              aria-label={t("reports.refresh")}
              title={t("reports.refresh")}
            >
              <RefreshCw size={17} aria-hidden="true" />
            </button>
          </div>
          <div
            className={s.views}
            role="radiogroup"
            aria-label={t("reports.status")}
          >
            {VIEWS.map((v) => (
              <button
                key={v}
                type="button"
                role="radio"
                aria-checked={view === v}
                className={s.viewChip}
                onClick={() => setFilter("status", v === "active" ? "" : v)}
                data-testid={`report-view-${v}`}
              >
                {t(`reports.view.${v}`)}
                <span className={s.viewCount}>{viewCount(v)}</span>
              </button>
            ))}
          </div>
          <div className={s.searchRow}>
            <label className={s.search}>
              <Search size={16} aria-hidden="true" />
              <input
                value={query}
                onChange={(e) => setFilter("q", e.target.value)}
                placeholder={t("reports.searchHint")}
                aria-label={t("reports.search")}
                maxLength={200}
              />
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
          </div>
          <div className={s.filterGrid} id="report-filters" hidden={!showFilters}>
            <Select
              aria-label={t("reports.reason")}
              value={reason}
              onChange={(e) => setFilter("reason", e.target.value)}
            >
              <option value="">{t("reports.allReasons")}</option>
              {REPORT_REASONS.map((r) => (
                <option key={r} value={r}>
                  {t(`reports.reason.${r}`)}
                </option>
              ))}
            </Select>
            <Select
              aria-label={t("reports.target")}
              value={kind}
              onChange={(e) => setFilter("kind", e.target.value)}
            >
              <option value="">{t("reports.allKinds")}</option>
              {REPORT_KINDS.map((k) => (
                <option key={k} value={k}>
                  {t(`reports.kind.${k}`)}
                </option>
              ))}
            </Select>
            <Select
              aria-label={t("reports.assignment")}
              value={assigned}
              onChange={(e) => setFilter("assigned", e.target.value)}
            >
              <option value="">{t("reports.anyone")}</option>
              <option value="me">{t("reports.assignedToMe")}</option>
              <option value="none">{t("reports.unassigned")}</option>
            </Select>
            <Select
              aria-label={t("reports.sort")}
              value={sort}
              onChange={(e) =>
                setFilter("sort", e.target.value === "oldest" ? "oldest" : "")
              }
            >
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
              <p>
                {filtered
                  ? t("reports.empty")
                  : view === "active"
                    ? t("reports.allClear")
                    : t("reports.empty")}
              </p>
              {filtered && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() =>
                    setFilters(view === "active" ? {} : { status: view }, {
                      replace: true,
                    })
                  }
                >
                  {t("reports.clearFilters")}
                </Button>
              )}
            </div>
          )}
          {list.map((v) => (
            <QueueRow
              key={v.id}
              report={v}
              people={people}
              current={v.id === reportId}
              onOpen={() => nav(`${BASE}/${v.id}${suffix}`)}
            />
          ))}
          {loading && (
            <p role="status" className={s.listStatus}>
              {t("reports.loading")}
            </p>
          )}
          {more && !loading && (
            <Button
              variant="secondary"
              block
              onClick={() => void load(list.at(-1)?.id)}
            >
              {t("reports.more")}
            </Button>
          )}
        </div>
      </aside>
      <main className={s.detail}>
        {reportId ? (
          <ReportWorkspace
            key={reportId}
            id={reportId}
            onBack={() => nav(`${BASE}${suffix}`)}
            onOpen={(rid) => nav(`${BASE}/${rid}${suffix}`)}
          />
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

/** Whether a report that just arrived belongs in the queue as it is filtered. */
function fits(
  r: SafetyReport,
  f: {
    view: View;
    kind: string;
    reason: string;
    assigned: string;
    me: string | undefined;
  },
): boolean {
  if (f.view === "active" && closing(r.status)) return false;
  if (f.view === "closed" && !closing(r.status)) return false;
  if ((f.view === "open" || f.view === "in_review") && r.status !== f.view)
    return false;
  if (f.kind && r.target_kind !== f.kind) return false;
  if (f.reason && r.reason !== f.reason) return false;
  if (f.assigned === "me" && r.assigned_to !== f.me) return false;
  if (f.assigned === "none" && r.assigned_to !== "0") return false;
  return true;
}

function snapshotName(v: SafetyReport): string {
  const snap = v.snapshot;
  for (const key of ["display_name", "name", "username"])
    if (typeof snap[key] === "string" && snap[key]) return snap[key] as string;
  return "";
}

function StatusPill({ status }: { status: string }) {
  return (
    <span className={s.badge} data-status={status}>
      {t(`reports.status.${status}`)}
    </span>
  );
}

/** One report in the queue: why, about whom or what, what they said, and where it stands. */
function QueueRow({
  report: v,
  people,
  current,
  onOpen,
}: {
  report: SafetyReport;
  people: People;
  current: boolean;
  onOpen: () => void;
}) {
  const subject = v.subject_id !== "0" ? people[v.subject_id] : undefined;
  const assignee = v.assigned_to !== "0" ? people[v.assigned_to] : undefined;
  const name =
    v.target_kind === "user" || v.target_kind === "message"
      ? (subject ? personName(subject) : "") || snapshotName(v)
      : snapshotName(v) || (subject ? personName(subject) : "");
  return (
    <button
      type="button"
      className={s.row}
      aria-current={current}
      onClick={onOpen}
      data-testid="report-row"
      data-status={v.status}
    >
      <ReasonGlyph reason={v.reason} />
      <span className={s.rowBody}>
        <span className={s.rowHeading}>
          <strong>{t(`reports.reason.${v.reason}`)}</strong>
          <time
            className={s.rowTime}
            dateTime={new Date(v.created_at).toISOString()}
            title={fullDate(v.created_at)}
          >
            {relativeTo(v.created_at)}
          </time>
        </span>
        <span className={s.rowSubject}>
          {subject &&
          (v.target_kind === "user" || v.target_kind === "message") ? (
            <Avatar
              id={v.subject_id}
              avatarId={subject.avatar_id}
              name={name}
              size={18}
            />
          ) : null}
          {name ? (
            <span className={s.rowName} dir="auto">
              {name}
            </span>
          ) : null}
          <span className={s.rowKind}>
            {t(`reports.kind.${v.target_kind}`)}
          </span>
        </span>
        <span className={s.rowText} dir="auto">
          {v.description}
        </span>
        <span className={s.rowFoot}>
          <StatusPill status={v.status} />
          {(v.duplicates ?? 1) > 1 && (
            <span className={s.dupes}>
              {t("reports.duplicates", { count: v.duplicates ?? 1 })}
            </span>
          )}
          <span className={s.rowAssignee}>
            {assignee ? (
              <Avatar
                id={v.assigned_to}
                avatarId={assignee.avatar_id}
                name={personName(assignee)}
                size={16}
              />
            ) : null}
            {assignee ? personName(assignee) : t("reports.unassigned")}
          </span>
        </span>
      </span>
    </button>
  );
}

function ReportWorkspace({
  id,
  onBack,
  onOpen,
}: {
  id: string;
  onBack: () => void;
  onOpen: (id: string) => void;
}) {
  const nav = useNavigate();
  const location = useLocation();
  const related = (path: string) =>
    nav(path, {
      state: { reportReturnTo: location.pathname + location.search },
    });
  const rank = useMyRank();
  const me = useAuth((st) => st.me?.id);
  const [detail, setDetail] = useState<ReportDetail | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [decision, setDecision] = useState<ReportStatus>("open");
  const [note, setNote] = useState("");
  const [told, setTold] = useState("");
  const [alsoRelated, setAlsoRelated] = useState(true);
  const [removing, setRemoving] = useState(false);
  const [removeReason, setRemoveReason] = useState("");
  const [moderating, setModerating] = useState<ModUser | null>(null);
  const [saved, setSaved] = useState("");
  const lock = useRef(false);

  const load = useCallback(async () => {
    try {
      const r = await reports.detail(id);
      setDetail(r);
      setDecision(r.report.status);
      setError("");
    } catch (err) {
      setError(describeError(err, t("reports.failed")));
    }
  }, [id]);
  useEffect(() => {
    void load();
  }, [load]);
  // another member of staff decided or claimed it: show what stands now
  useModEvents((e) => {
    if (
      e.type === "report" &&
      !lock.current &&
      e.reports.some((r) => r.id === id && r.version !== detail?.report.version)
    )
      void load();
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
      const status: ReportStatus =
        to !== "0" && v.status === "open"
          ? "in_review"
          : to === "0" && v.status === "in_review"
            ? "open"
            : v.status;
      const r = await reports.review(id, {
        version: v.version,
        status,
        note: "",
        assigned_to: to,
      });
      setDetail(r);
      setDecision(r.report.status);
    });
  const others = Math.max(0, (v?.duplicates ?? 1) - 1);
  const save = () =>
    run(async () => {
      if (!v) return;
      const r = await reports.review(id, {
        version: v.version,
        status: decision,
        note: note.trim(),
        assigned_to:
          v.assigned_to === "0" && decision !== "open"
            ? (me ?? "0")
            : v.assigned_to,
        ...(closing(decision)
          ? {
              reporter_message: told.trim(),
              resolve_related: others > 0 && alsoRelated,
            }
          : {}),
      });
      setDetail(r);
      setDecision(r.report.status);
      setNote("");
      setTold("");
      setSaved(t("reports.decision.saved"));
    });
  const removeMessage = () =>
    useUI.getState().openModal("confirm", {
      title: t("reports.decision.removeMessage"),
      body: t("reports.decision.removeConfirm"),
      confirmLabel: t("reports.decision.removeMessage"),
      danger: true,
      onConfirm: () =>
        run(async () => {
          if (!v) return;
          await reports.removeMessage(
            v.id,
            v.version,
            removeReason.trim(),
            told.trim(),
          );
          setRemoving(false);
          setRemoveReason("");
          setTold("");
          await load();
          setSaved(t("reports.decision.removed"));
        }),
    });
  const moderate = () =>
    run(async () => {
      if (!v) return;
      const r = await moderation.user(v.subject_id);
      setModerating(r.user);
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
  const changing = decision !== v.status;
  const needsNote = closing(decision) && changing && note.trim().length < 3;
  const canRemove =
    atLeast(rank, "admin") && v.target_kind === "message" && !closing(v.status);

  return (
    <article
      className={s.detailInner}
      data-testid="report-detail"
      data-status={v.status}
    >
      <button type="button" className={s.back} onClick={onBack}>
        <ArrowLeft size={18} aria-hidden="true" />
        {t("reports.back")}
      </button>
      <header className={s.reportHeader}>
        <ReasonGlyph reason={v.reason} />
        <div className={s.headerText}>
          <h2>{t(`reports.reason.${v.reason}`)}</h2>
          <p className={s.headerMeta}>
            <span>
              {t("reports.kindReport", {
                kind: t(`reports.kind.${v.target_kind}`),
              })}
            </span>
            <time
              dateTime={new Date(v.created_at).toISOString()}
              title={fullDate(v.created_at)}
            >
              {relativeTo(v.created_at)}
            </time>
            {(v.duplicates ?? 1) > 1 && (
              <span className={s.dupes}>
                {t("reports.duplicates", { count: v.duplicates ?? 1 })}
              </span>
            )}
            <button
              type="button"
              className={s.copyId}
              title={t("reports.copyId")}
              onClick={() =>
                void navigator.clipboard
                  ?.writeText(v.id)
                  .then(() =>
                    useUI.getState().toast(t("apps.portal.copiedShort"), "success"),
                  )
              }
            >
              <Copy size={13} aria-hidden="true" />
              {t("reports.reportId")}
            </button>
          </p>
        </div>
        <StatusPill status={v.status} />
      </header>

      <div className={s.assignment}>
        <span className={s.assignLabel}>{t("reports.assignment")}</span>
        {v.assigned_to !== "0" ? (
          <PersonChip
            id={v.assigned_to}
            person={person(v.assigned_to)}
            size={20}
          />
        ) : (
          <span className={s.muted}>{t("reports.unassigned")}</span>
        )}
        <span className={s.spacer} />
        {v.assigned_to !== me ? (
          <Button
            variant="secondary"
            size="sm"
            disabled={busy || !me}
            onClick={() => void assign(me ?? "0")}
            data-testid="report-claim"
          >
            {t("reports.claim")}
          </Button>
        ) : (
          <Button
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => void assign("0")}
          >
            {t("reports.unassign")}
          </Button>
        )}
      </div>

      {error && (
        <div role="alert" className={s.error}>
          {error}
          <Button variant="link" onClick={() => void load()} disabled={busy}>
            {t("reports.retry")}
          </Button>
        </div>
      )}

      <div className={s.detailGrid}>
        <div className={s.mainCol}>
          <section className={s.said} aria-labelledby="report-said">
            <h3 id="report-said">{t("reports.said")}</h3>
            <blockquote className={s.quote} dir="auto">
              {v.description}
            </blockquote>
            <p className={s.byline}>
              <span>{t("reports.reporter")}</span>
              <PersonChip
                id={v.reporter_id}
                person={person(v.reporter_id)}
                size={18}
                withUsername
              />
            </p>
          </section>

          <section className={s.content} aria-labelledby="report-content">
            <h3 id="report-content">{t("reports.reportedContent")}</h3>
            <p className={s.notice}>{t("reports.unverified")}</p>
            {isMessage ? (
              <div className={s.evidenceRow}>
                <MessageItem
                  msg={reportedChatMessage(v)}
                  groupStart
                  preview
                  previewAuthor={{
                    name: personName(person(v.subject_id)) || v.subject_id,
                    avatarId: person(v.subject_id)?.avatar_id ?? null,
                  }}
                  evidence={
                    <div className={s.retainedFiles}>
                      {!!v.snapshot.message_sticker && (
                        <ReportSnapshot
                          snapshot={
                            v.snapshot.message_sticker as Record<
                              string,
                              unknown
                            >
                          }
                          kind="sticker"
                          saved
                        />
                      )}
                      <EvidenceFiles files={detail.files} onError={setError} />
                    </div>
                  }
                />
              </div>
            ) : (
              <>
                {v.target_kind !== "other" && (
                  <ReportTargetCard
                    kind={v.target_kind}
                    snapshot={v.snapshot}
                    saved
                  />
                )}
                {v.excerpt ? (
                  <pre className={s.preview}>{v.excerpt}</pre>
                ) : null}
                <EvidenceFiles files={detail.files} onError={setError} />
              </>
            )}
            <details className={s.technical}>
              <summary>{t("reports.sharedDetails")}</summary>
              <p className={s.notice}>{t("reports.snapshotHint")}</p>
              <ReportSnapshot
                snapshot={Object.fromEntries(
                  Object.entries(v.snapshot).filter(
                    ([key]) =>
                      key !== "message_content" && key !== "message_sticker",
                  ),
                )}
                kind={v.target_kind}
                saved
              />
              <p className={s.muted}>
                {t("reports.reportId")}: <span dir="ltr">{v.id}</span>
              </p>
            </details>
          </section>

          <section
            className={s.decision}
            aria-labelledby="report-decision"
            data-testid="report-decision"
          >
            <h3 id="report-decision">{t("reports.decision.title")}</h3>
            {canRemove && (
              <div className={s.removeBox} data-open={removing || undefined}>
                {!removing ? (
                  <Button
                    variant="danger"
                    onClick={() => setRemoving(true)}
                    disabled={busy}
                  >
                    <Trash2 size={16} aria-hidden="true" />{" "}
                    {t("reports.decision.removeMessage")}
                  </Button>
                ) : (
                  <>
                    <p className={s.notice}>
                      {t("reports.decision.removeHint")}
                    </p>
                    <Field
                      label={t("moderation.form.reason")}
                      hint={t("moderation.form.reasonVisible")}
                    >
                      <TextArea
                        value={removeReason}
                        onChange={(e) => setRemoveReason(e.target.value)}
                        maxLength={1000}
                        rows={2}
                        disabled={busy}
                      />
                    </Field>
                    <ToldField
                      value={told}
                      onChange={setTold}
                      status="actioned"
                      disabled={busy}
                    />
                    <div className={s.inlineActions}>
                      <Button
                        variant="ghost"
                        onClick={() => setRemoving(false)}
                        disabled={busy}
                      >
                        {t("reports.decision.cancel")}
                      </Button>
                      <Button
                        variant="danger"
                        disabled={busy || removeReason.trim().length < 3}
                        onClick={removeMessage}
                      >
                        {t("reports.decision.removeMessage")}
                      </Button>
                    </div>
                  </>
                )}
              </div>
            )}
            {!removing && (
              <>
                <div
                  className={s.outcomes}
                  role="radiogroup"
                  aria-label={t("reports.status")}
                >
                  {REPORT_STATUSES.map((st) => (
                    <button
                      key={st}
                      type="button"
                      role="radio"
                      aria-checked={decision === st}
                      className={s.outcome}
                      data-status={st}
                      onClick={() => setDecision(st)}
                      disabled={busy}
                      data-testid={`report-decide-${st}`}
                    >
                      {t(`reports.status.${st}`)}
                    </button>
                  ))}
                </div>
                {closing(decision) && changing && (
                  <>
                    <ToldField
                      value={told}
                      onChange={setTold}
                      status={decision}
                      disabled={busy}
                    />
                    {others > 0 && (
                      <label className={s.check}>
                        <input
                          type="checkbox"
                          checked={alsoRelated}
                          onChange={(e) => setAlsoRelated(e.target.checked)}
                          disabled={busy}
                        />
                        <span>
                          {t("reports.decision.alsoRelated", { count: others })}
                        </span>
                      </label>
                    )}
                  </>
                )}
                <Field
                  label={t("reports.staffNote")}
                  hint={t("reports.noteHint")}
                  required={closing(decision) && changing}
                >
                  <TextArea
                    value={note}
                    rows={3}
                    maxLength={2000}
                    disabled={busy}
                    onChange={(e) => (setNote(e.target.value), setSaved(""))}
                    data-testid="report-note"
                  />
                </Field>
                <div className={s.inlineActions}>
                  {saved && (
                    <p role="status" className={s.savedNote}>
                      {saved}
                    </p>
                  )}
                  <Button
                    loading={busy}
                    disabled={needsNote || (!changing && !note.trim())}
                    onClick={() => void save()}
                    data-testid="report-save"
                  >
                    {closing(v.status) && !closing(decision)
                      ? t("reports.decision.reopen")
                      : t("reports.decision.save")}
                  </Button>
                </div>
              </>
            )}
          </section>

          <section className={s.historyBox} aria-labelledby="report-history">
            <h3 id="report-history">{t("reports.history")}</h3>
            <ol className={s.timeline}>
              <TimelineItem
                at={v.created_at}
                who={
                  <PersonChip
                    id={v.reporter_id}
                    person={person(v.reporter_id)}
                    size={16}
                  />
                }
              >
                {t("reports.timeline.sent")}
              </TimelineItem>
              {detail.events.map((e) => (
                <TimelineItem
                  key={e.id}
                  at={e.created_at}
                  who={
                    <PersonChip
                      id={e.actor_id}
                      person={person(e.actor_id)}
                      size={16}
                    />
                  }
                  status={e.status}
                >
                  {t("reports.timeline.status", {
                    status: t(`reports.status.${e.status}`),
                  })}
                  {e.note && <span className={s.timelineNote}>{e.note}</span>}
                </TimelineItem>
              ))}
              {v.outcome_at ? (
                <TimelineItem at={v.outcome_at} who={null}>
                  {t("reports.timeline.told")}
                  <span className={s.timelineNote}>
                    {v.outcome ||
                      t(
                        `reports.outcome.${v.status === "dismissed" ? "dismissed" : "actioned"}`,
                      )}
                  </span>
                </TimelineItem>
              ) : null}
            </ol>
          </section>
        </div>
        <div className={s.sideCol}>
          <aside className={s.people} aria-label={t("reports.people")}>
            {v.subject_id !== "0" && (
              <section className={s.panel}>
                <h3>{t("reports.subject")}</h3>
                <PersonChip
                  id={v.subject_id}
                  person={person(v.subject_id)}
                  size={32}
                  withUsername
                />
                {subject ? (
                  <ul className={s.standing} role="list">
                    <li data-tone={subject.ban ? "danger" : undefined}>
                      {subject.ban
                        ? t("reports.standing.banned")
                        : subject.mute
                          ? t("reports.standing.muted")
                          : t("reports.standing.clear")}
                    </li>
                    <li>
                      {t("reports.standing.cases", {
                        count: subject.case_count,
                      })}
                    </li>
                    <li>
                      {t("reports.standing.reports", {
                        count:
                          (detail.related ?? []).filter(
                            (x) => x.subject_id === v.subject_id,
                          ).length + 1,
                      })}
                    </li>
                    {subject.alt_count > 0 && (
                      <li data-tone="warning">
                        {t("reports.standing.alts", {
                          count: subject.alt_count,
                        })}
                      </li>
                    )}
                  </ul>
                ) : null}
                <div className={s.panelActions}>
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() =>
                      related(`/channels/@admin/users/${v.subject_id}`)
                    }
                  >
                    {t("reports.openAccount")}
                  </Button>
                  {subject && !subject.blocked && (
                    <Button
                      size="sm"
                      onClick={() => void moderate()}
                      disabled={busy}
                    >
                      {t("reports.moderateAccount")}
                    </Button>
                  )}
                </div>
              </section>
            )}
            <section className={s.panel}>
              <h3>{t("reports.reporter")}</h3>
              <PersonChip
                id={v.reporter_id}
                person={person(v.reporter_id)}
                size={32}
                withUsername
              />
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
            {(atLeast(rank, "admin") && v.server_id !== "0") ||
            (atLeast(rank, "admin") &&
              (v.target_kind === "pack" || v.target_kind === "sticker")) ? (
              <section className={s.panel}>
                <h3>{t("reports.goTo")}</h3>
                <div className={s.panelActions}>
                  {v.server_id !== "0" && (
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() =>
                        related(`/channels/@admin/servers/${v.server_id}`)
                      }
                    >
                      <ExternalLink size={14} aria-hidden="true" />{" "}
                      {t("reports.openServer")}
                    </Button>
                  )}
                  {(v.target_kind === "pack" ||
                    v.target_kind === "sticker") && (
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() =>
                        related(
                          `/channels/@admin/expressions/packs/${v.target_kind === "pack" ? v.target_id : String(v.snapshot.pack_id)}`,
                        )
                      }
                    >
                      <ExternalLink size={14} aria-hidden="true" />{" "}
                      {t("reports.openPack")}
                    </Button>
                  )}
                </div>
              </section>
            ) : null}
          </aside>

          <section className={s.relatedBox} aria-labelledby="report-related">
            <h3 id="report-related">{t("reports.related")}</h3>
            {detail.related?.length ? (
              <ul className={s.relatedList} role="list">
                {detail.related.map((x) => (
                  <li key={x.id}>
                    <button
                      type="button"
                      className={s.relatedRow}
                      onClick={() => onOpen(x.id)}
                    >
                      <ReasonGlyph reason={x.reason} size="sm" />
                      <span className={s.relatedText}>
                        <span className={s.relatedTitle}>
                          {t(`reports.reason.${x.reason}`)}
                        </span>
                        <span className={s.muted}>
                          {x.target_kind === v.target_kind &&
                          x.target_id === v.target_id &&
                          v.target_id !== "0"
                            ? t("reports.sameItem")
                            : t("reports.sameAccount", {
                                kind: t(`reports.kind.${x.target_kind}`),
                              })}
                        </span>
                      </span>
                      <StatusPill status={x.status} />
                      <time
                        className={s.rowTime}
                        title={fullDate(x.created_at)}
                      >
                        {relativeTo(x.created_at)}
                      </time>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className={s.muted}>{t("reports.noRelated")}</p>
            )}
          </section>
        </div>
      </div>

      {moderating && (
        <ModerateModal
          user={moderating}
          onClose={() => setModerating(null)}
          onDone={(c) => {
            setDecision("actioned");
            setNote((old) =>
              [old, t("reports.caseRecorded", { number: c.number })]
                .filter(Boolean)
                .join("\n"),
            );
          }}
        />
      )}
    </article>
  );
}

/** What the reporter will read in their Nexa conversation, and the standard words if left empty. */
function ToldField({
  value,
  onChange,
  status,
  disabled,
}: {
  value: string;
  onChange: (v: string) => void;
  status: string;
  disabled: boolean;
}) {
  return (
    <Field
      label={t("reports.decision.told")}
      hint={t("reports.decision.toldHint")}
    >
      <TextArea
        value={value}
        rows={2}
        maxLength={1000}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        placeholder={t(
          `reports.outcome.${status === "dismissed" ? "dismissed" : "actioned"}`,
        )}
        data-testid="report-told"
      />
    </Field>
  );
}

function TimelineItem({
  at,
  who,
  status,
  children,
}: {
  at: number;
  who: ReactNode;
  status?: string;
  children: ReactNode;
}) {
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
function EvidenceFiles({
  files,
  onError,
}: {
  files: BugFile[];
  onError: (e: string) => void;
}) {
  const inline = useMemo(
    () =>
      files.filter((f) => f.size <= INLINE_MAX && INLINE_TYPES.test(f.mime)),
    [files],
  );
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
    edited_at: report.snapshot.edited_at
      ? Number(report.snapshot.edited_at)
      : null,
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
