import { LockKeyhole, ScrollText, Search } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { moderation as modApi, type ModLogEntry, type People } from "@/api/moderation";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { PersonChip } from "@/components/nexa/NexaBits";
import { actionIcon, actionLabel, fullDate, LOG_GROUPS, logDetail, severity } from "@/lib/moderation";
import { formatTimestamp } from "@/lib/time";
import { useModEvents } from "@/stores/moderation";
import { t } from "@/i18n";
import m from "./Moderation.module.css";
import { LoadError } from "@/components/common/LoadError";

type Group = "all" | keyof typeof LOG_GROUPS;
const PAGE = 50;

/**
 * Every action staff took, newest first, as it was taken: the names are the
 * ones recorded at the time, so a later rename or deletion does not rewrite
 * history. Nothing here can be edited or removed, by anyone.
 */
export function LogsTab() {
  const nav = useNavigate();
  const [group, setGroup] = useState<Group>("all");
  const [q, setQ] = useState("");
  const [entries, setEntries] = useState<ModLogEntry[] | null>(null);
  const [people, setPeople] = useState<People>({});
  const [more, setMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  // only the newest request may fill the table: a slower answer for the filter picked before would land in this one
  const request = useRef(0);

  const load = useCallback(
    async (before?: string) => {
      const id = ++request.current;
      setLoading(true);
      setFailed(false);
      try {
        const r = await modApi.log({ action: group === "all" ? undefined : LOG_GROUPS[group], q, before, limit: PAGE });
        if (id !== request.current) return;
        setEntries((cur) => (before ? [...(cur ?? []), ...r.entries.filter((x) => !cur?.some((y) => y.id === x.id))] : r.entries));
        setPeople((p) => ({ ...p, ...r.people }));
        setMore(r.entries.length === PAGE);
      } catch {
        if (id === request.current) setFailed(true);
      } finally {
        if (id === request.current) setLoading(false);
      }
    },
    [group, q],
  );

  useEffect(() => {
    request.current++;
    setMore(false);
    const h = setTimeout(() => {
      setEntries(null);
      void load();
    }, q ? 250 : 0);
    return () => clearTimeout(h);
  }, [load, q]);

  useModEvents((e) => {
    if (e.type !== "log") return;
    const matches = (group === "all" || LOG_GROUPS[group]!.includes(e.entry.action)) && !q;
    if (!matches) return;
    setPeople((p) => ({ ...p, ...e.people }));
    setEntries((cur) => (cur && !cur.some((x) => x.id === e.entry.id) ? [e.entry, ...cur] : cur));
  });

  const groups: { id: Group; label: string }[] = [
    { id: "all", label: t("moderation.logs.all") },
    { id: "punishments", label: t("moderation.logs.punishments") },
    { id: "lifts", label: t("moderation.logs.lifts") },
    { id: "announcements", label: t("moderation.logs.announcements") },
    { id: "access", label: t("moderation.logs.access") },
    { id: "badges", label: t("moderation.logs.badges") },
    { id: "games", label: t("moderation.tabs.games") },
  ];

  const target = (e: ModLogEntry) =>
    e.target_id ? (
      <span className={m.logPerson}>
        <PersonChip id={e.target_id} person={people[e.target_id]} size={18} />
        {people[e.target_id]?.username !== e.target_username && e.target_username ? <span className={m.logDetail}>{t("moderation.logs.thenNamed", { name: e.target_username })}</span> : null}
      </span>
    ) : (
      <span className={m.logDetail}>{t("moderation.logs.everyone")}</span>
    );
  const moderator = (e: ModLogEntry) => (
    <span className={m.logPerson}>
      <PersonChip id={e.moderator_id} person={people[e.moderator_id] ?? { id: e.moderator_id, username: e.moderator_username, display_name: e.moderator_username, avatar_id: null, flags: 0 }} size={18} />
    </span>
  );
  const caseLink = (e: ModLogEntry) =>
    e.case_id && e.target_id ? (
      <button type="button" className={m.linkBtn} onClick={() => nav(`/channels/@admin/users/${e.target_id}/cases/${e.case_id}`)}>
        #{e.case_number}
      </button>
    ) : (
      <span className={m.logDetail}>{t("moderation.form.none")}</span>
    );
  // the reason as written, with what was done under it
  const why = (e: ModLogEntry) => {
    const detail = logDetail(e);
    if (!e.reason && !detail) return <span className={m.logDetail}>{t("moderation.form.none")}</span>;
    return (
      <>
        {e.reason ? <div className={m.logReasonText}>{e.reason}</div> : null}
        {detail ? <div className={m.logDetail}>{detail}</div> : null}
      </>
    );
  };
  const action = (e: ModLogEntry) => {
    const Icon = actionIcon(e.action);
    return (
      <span className={m.logAction} data-tone={severity(e.action)}>
        <Icon size={16} aria-hidden="true" />
        {actionLabel(e.action)}
      </span>
    );
  };

  return (
    <div className={m.scroll} data-testid="logs-tab">
      <div className={m.toolbar}>
        <label className={m.search}>
          <Search size={17} aria-hidden="true" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("moderation.logs.search")} aria-label={t("moderation.logs.search")} />
        </label>
        <div className={m.chips} role="radiogroup" aria-label={t("moderation.logs.filter")}>
          {groups.map((g) => (
            <button key={g.id} type="button" role="radio" aria-checked={group === g.id} className={m.chip} data-selected={group === g.id} onClick={() => setGroup(g.id)}>
              {g.label}
            </button>
          ))}
        </div>
        <span className={m.immutable} title={t("moderation.logs.immutableTip")}>
          <LockKeyhole size={15} aria-hidden="true" />
          {t("moderation.logs.immutable")}
        </span>
      </div>
      {failed && !entries?.length ? (
        <LoadError message={t("moderation.loadFailed")} onRetry={() => void load()} busy={loading} center />
      ) : entries === null ? (
        <div className={m.loadingRow}>
          <Spinner label={t("chat.pins.loading")} />
        </div>
      ) : entries.length === 0 ? (
        <div className={m.empty}>
          <ScrollText size={40} aria-hidden="true" />
          <span className={m.emptyTitle}>{q ? t("moderation.noMatches") : t("moderation.logs.empty")}</span>
        </div>
      ) : (
        <>
          <div className={m.logWrap}>
            <table className={m.logTable} data-testid="log-table">
              <colgroup>
                <col className={m.colWhen} />
                <col className={m.colAction} />
                <col className={m.colPerson} />
                <col className={m.colPerson} />
                <col />
                <col className={m.colCase} />
              </colgroup>
              <thead>
                <tr>
                  <th>{t("moderation.logs.when")}</th>
                  <th>{t("moderation.logs.action")}</th>
                  <th>{t("moderation.logs.target")}</th>
                  <th>{t("moderation.logs.moderator")}</th>
                  <th>{t("moderation.logs.reason")}</th>
                  <th>{t("moderation.logs.case")}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id} data-action={e.action}>
                    <td className={m.logWhen} title={fullDate(e.created_at)}>
                      {formatTimestamp(e.created_at)}
                    </td>
                    <td>{action(e)}</td>
                    <td>{target(e)}</td>
                    <td>{moderator(e)}</td>
                    <td className={m.logReason}>{why(e)}</td>
                    <td>{caseLink(e)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className={m.logCards}>
            {entries.map((e) => (
              <div key={e.id} className={m.logCard}>
                <div className={m.logCardTop}>
                  {action(e)}
                  <span className={m.logWhen}>{formatTimestamp(e.created_at)}</span>
                </div>
                <dl className={m.logCardPeople}>
                  <dt>{t("moderation.logs.target")}</dt>
                  <dd>{target(e)}</dd>
                  <dt>{t("moderation.logs.moderator")}</dt>
                  <dd>{moderator(e)}</dd>
                </dl>
                <div className={m.logReason}>{why(e)}</div>
                {e.case_id ? <div>{caseLink(e)}</div> : null}
              </div>
            ))}
          </div>
          {more ? (
            <div className={m.loadingRow}>
              <Button size="sm" variant="ghost" loading={loading} onClick={() => void load(entries[entries.length - 1]!.id)}>
                {t("moderation.loadMore")}
              </Button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
