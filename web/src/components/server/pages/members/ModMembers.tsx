import { PersonName } from "@/components/common/PersonName";
import { ArrowDownUp, ChevronDown, Scissors } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError } from "@/api/client";
import { servers as serversApi, type MemberQuery } from "@/api/servers";
import type { MemberRow, Role } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { menuAnchor } from "@/components/common/ContextMenu";
import { LoadError } from "@/components/common/LoadError";
import { Modal } from "@/components/common/Modal";
import { SearchBox } from "@/components/common/SearchBox";
import { Select } from "@/components/common/Select";
import { Spinner } from "@/components/common/Spinner";
import { Field, TextInput } from "@/components/common/TextInput";
import { useIsMobile } from "@/hooks/useIsMobile";
import { t } from "@/i18n";
import { formatSpan } from "@/lib/moderation";
import { memberName } from "@/lib/names";
import { P } from "@/lib/perms";
import { formatDateDivider } from "@/lib/time";
import { useAuth } from "@/stores/auth";
import { canI, rolesByPosition, useServers } from "@/stores/servers";
import { useUI, type ContextMenuItem } from "@/stores/ui";
import { button, defineMenu, type MenuPlacement } from "@/lib/contextMenus/registry";
import { displayName, useUsers } from "@/stores/users";
import st from "@/components/settings/Settings.module.css";
import { outranks } from "../../memberMenu";
import { joinMethodLabel, ModView, roleDot, SignalIcon, signalLabel } from "./ModView";
import s from "./Members.module.css";

const PAGE = 50;
const DAY = 86_400_000;
const SIGNALS = ["timed_out", "quarantined", "new_account", "unusual_dms"] as const;
type Sort = NonNullable<MemberQuery["sort"]>;

/** How far back, as the Joined and Account Age filters offer it. */
const SPANS = [1, 7, 30, 90];
const SORTS: Sort[] = ["joined_desc", "joined_asc", "created_desc", "created_asc"];

function sortLabel(sort: Sort): string {
  switch (sort) {
    case "joined_asc":
      return t("members.sort.joinedOld");
    case "created_desc":
      return t("members.sort.createdNew");
    case "created_asc":
      return t("members.sort.createdOld");
    default:
      return t("members.sort.joinedNew");
  }
}

function spanLabel(days: number): string {
  return days === 1 ? t("members.filter.day") : days === 7 ? t("members.filter.week") : days === 30 ? t("members.filter.month") : t("members.filter.threeMonths");
}

/** Mod View's sort menu. */
const memberSortMenu = defineMenu<{ sort: Sort; setSort: (s: Sort) => void }>("memberSort", [
  [button("memberSortOrder", ({ sort, setSort }) => SORTS.map((k) => ({ id: k, label: sortLabel(k), checked: sort === k, onSelect: () => setSort(k) })))],
]);

/** Mod View's roles filter: any of the roles ticked. */
const memberRoleFilterMenu = defineMenu<{ roles: Role[]; picked: string[]; toggle: (id: string) => void }>("memberRoleFilter", [
  [button("memberRoleFilter", ({ roles, picked, toggle }) => roles.map((r) => ({ id: r.id, label: r.name, checked: picked.includes(r.id), toggle: true, onSelect: () => toggle(r.id) })))],
]);

/** Mod View's filters by how recently someone joined, or made their account. */
const spanFilterItems = (prefix: string, value: number, set: (d: number) => void): ContextMenuItem[] => [
  { id: "any", label: t("members.filter.any"), checked: !value, onSelect: () => set(0) },
  ...SPANS.map((d) => ({ id: `${prefix}${d}`, label: spanLabel(d), checked: value === d, onSelect: () => set(d) })),
];
const memberJoinedFilterMenu = defineMenu<{ value: number; set: (d: number) => void }>("memberJoinedFilter", [[button("memberJoinedFilter", ({ value, set }) => spanFilterItems("j", value, set))]]);
const memberAgeFilterMenu = defineMenu<{ value: number; set: (d: number) => void }>("memberAgeFilter", [[button("memberAgeFilter", ({ value, set }) => spanFilterItems("c", value, set))]]);

/** Mod View's filter by how someone joined, or through which invite. */
const memberJoinFilterMenu = defineMenu<{ method: string; code: string; codes: string[]; pick: (method: string, code: string) => void }>("memberJoinFilter", [
  [
    button("memberJoinMethodFilter", ({ method, code, pick }) => [
      { id: "any", label: t("members.filter.any"), checked: !method && !code, onSelect: () => pick("", "") },
      ...["invite", "vanity", "discovery", "tag"].map((m) => ({ id: m, label: joinMethodLabel(m), checked: method === m && !code, onSelect: () => pick(m, "") })),
    ]),
    button("memberInviteFilter", ({ code, codes, pick }) => codes.length > 0 && [{ id: "codes", label: t("members.filter.byInvite"), heading: true }, ...codes.slice(0, 20).map((c) => ({ id: `code${c}`, label: c, checked: code === c, onSelect: () => pick("invite", c) }))]),
  ],
]);

function RoleChips({ serverId, ids }: { serverId: string; ids: string[] }) {
  const roles = useServers((x) => x.byId[serverId]?.roles);
  const shown = ids.map((id) => roles?.[id]).filter(Boolean).sort((a, b) => b!.position - a!.position);
  return (
    <span className={s.roles}>
      {shown.slice(0, 3).map((r) => (
        <span key={r!.id} className={s.role}>
          <span className={s.dot} style={{ background: roleDot(r!) }} />
          <span dir="auto">{r!.name}</span>
        </span>
      ))}
      {shown.length > 3 ? <span className={s.more}>+{shown.length - 3}</span> : null}
    </span>
  );
}

function Signals({ list }: { list: string[] }) {
  if (!list.length) return null;
  return (
    <span className={s.signals}>
      {list.map((sig) => (
        <span key={sig} className={s.signal} data-kind={sig} title={signalLabel(sig)} role="img" aria-label={signalLabel(sig)}>
          <SignalIcon kind={sig} />
        </span>
      ))}
    </span>
  );
}

/**
 * The Members page as moderators see it: everyone in the server, searched
 * and filtered on the server (by role, when they came, how old their account
 * is, how they joined, and what might want a look), with what can be done to
 * several at once, and Mod View for one.
 */
export function ModMembers({ serverId }: { serverId: string }) {
  const server = useServers((x) => x.byId[serverId]);
  const users = useUsers((u) => u.byId);
  const meId = useAuth((a) => a.me?.id);
  const mobile = useIsMobile();
  const toast = useUI((u) => u.toast);
  const seesJoins = canI(server, P.MANAGE_SERVER);
  const [q, setQ] = useState("");
  const [roles, setRoles] = useState<string[]>([]);
  const [joined, setJoined] = useState(0);
  const [created, setCreated] = useState(0);
  const [signals, setSignals] = useState<string[]>([]);
  const [method, setMethod] = useState("");
  const [code, setCode] = useState("");
  const [sort, setSort] = useState<Sort>("joined_desc");
  const [rows, setRows] = useState<MemberRow[] | null>(null);
  const [total, setTotal] = useState(0);
  const [codes, setCodes] = useState<string[]>([]);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [picked, setPicked] = useState<Set<string>>(() => new Set());
  const [viewing, setViewing] = useState<string | null>(null);
  const [pruning, setPruning] = useState(false);
  const gen = useRef(0);

  const query = useMemo((): MemberQuery => {
    const now = Date.now();
    return {
      ...(q.trim() ? { q: q.trim() } : {}),
      ...(roles.length ? { roles } : {}),
      ...(joined ? { joined_after: now - joined * DAY } : {}),
      ...(created ? { created_after: now - created * DAY } : {}),
      ...(signals.length ? { signals } : {}),
      ...(method ? { join_method: method } : {}),
      ...(code ? { join_code: code } : {}),
      sort,
    };
  }, [q, roles, joined, created, signals, method, code, sort]);

  const load = useCallback(
    async (offset: number) => {
      const mine = ++gen.current;
      setLoading(true);
      setFailed(false);
      try {
        const res = await serversApi.searchMembers(serverId, { ...query, offset, limit: PAGE });
        if (mine !== gen.current) return;
        useUsers.getState().upsert(res.users);
        setRows((cur) => (offset && cur ? [...cur, ...res.members] : res.members));
        setTotal(res.total);
        if (res.join_codes) setCodes(res.join_codes);
      } catch {
        if (mine === gen.current) setFailed(true);
      } finally {
        if (mine === gen.current) setLoading(false);
      }
    },
    [serverId, query],
  );
  useEffect(() => {
    const timer = setTimeout(() => void load(0), q ? 250 : 0);
    return () => clearTimeout(timer);
  }, [load, q]);

  const filtering = !!(q.trim() || roles.length || joined || created || signals.length || method || code);
  const clearFilters = () => {
    setQ("");
    setRoles([]);
    setJoined(0);
    setCreated(0);
    setSignals([]);
    setMethod("");
    setCode("");
  };
  /** Where a filter's menu opens: under its button, from its start edge. */
  const under = (el: HTMLElement, label: string): MenuPlacement => {
    const anchor = menuAnchor(el, "start");
    return { x: anchor.left, y: anchor.bottom + 4, anchor, label };
  };
  const allRoles = rolesByPosition(server).filter((r) => r.id !== serverId);
  const toggle = (id: string) =>
    setPicked((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const pickable = (id: string) => id !== meId && id !== server?.server.owner_id && outranks(serverId, id);
  const pickedIds = [...picked];

  const bulkKick = () =>
    useUI.getState().openModal("confirm", {
      title: t("members.bulk.kickTitle", { count: pickedIds.length }),
      body: t("members.bulk.kickBody"),
      confirmLabel: t("members.bulk.kick", { count: pickedIds.length }),
      danger: true,
      onConfirm: async () => {
        let ok = 0;
        for (const id of pickedIds) {
          try {
            await serversApi.kick(serverId, id);
            ok++;
          } catch {
            // the rest still go
          }
        }
        toast(t("members.bulk.kicked", { count: ok }), ok === pickedIds.length ? "success" : "error");
        setPicked(new Set());
        void load(0);
      },
    });
  const bulkBan = () =>
    useUI.getState().openModal("confirm", {
      title: t("members.bulk.banTitle", { count: pickedIds.length }),
      body: t("members.bulk.banBody"),
      confirmLabel: t("members.bulk.ban", { count: pickedIds.length }),
      danger: true,
      onConfirm: async () => {
        try {
          const res = await serversApi.bulkBan(serverId, pickedIds, { delete_message_days: 1 });
          toast(t("members.bulk.banned", { count: res.banned.length }), res.failed.length ? "error" : "success");
          setPicked(new Set());
          void load(0);
        } catch (err) {
          toast(err instanceof ApiError ? err.message : t("members.mod.failed"), "error");
        }
      },
    });

  if (!server) return null;
  const nameOf = (m: MemberRow) => memberName(m.nickname, users[m.user_id]);
  const methodCell = (m: MemberRow) =>
    m.join ? (
      <span className={s.method}>
        {joinMethodLabel(m.join.join_method)}
        {m.join.join_code ? <span className={s.methodSub} dir="ltr">{m.join.join_code}</span> : m.join.invited_by ? <PersonName userId={m.join.invited_by} className={s.methodSub}>{displayName(users[m.join.invited_by])}</PersonName> : null}
      </span>
    ) : null;

  return (
    <>
      <h2 className={st.title}>{t("server.members.membersCount", { count: server.memberCount })}</h2>
      <SearchBox className={s.search} value={q} onChange={setQ} placeholder={t("members.search")} aria-label={t("members.search")} data-testid="members-search" />
      {/* the filters in one row; a phone scrolls it sideways rather than stacking four rows of it */}
      <div className={s.filters} role="group" aria-label={t("members.filter.label")}>
        <button type="button" className={s.filterBtn} aria-haspopup="menu" onClick={(e) => memberSortMenu.open({ sort, setSort }, under(e.currentTarget, t("members.sort.label")))} data-testid="members-sort">
          <ArrowDownUp size={14} aria-hidden="true" /> {sortLabel(sort)} <ChevronDown size={14} aria-hidden="true" />
        </button>
        <button type="button" className={s.filterBtn} data-on={roles.length ? "" : undefined} aria-haspopup="menu" onClick={(e) => memberRoleFilterMenu.open({ roles: allRoles, picked: roles, toggle: (id) => setRoles((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id])) }, under(e.currentTarget, t("server.members.roles")))}>
          {roles.length ? t("members.filter.rolesN", { count: roles.length }) : t("server.members.roles")} <ChevronDown size={14} aria-hidden="true" />
        </button>
        <button type="button" className={s.filterBtn} data-on={joined ? "" : undefined} aria-haspopup="menu" onClick={(e) => memberJoinedFilterMenu.open({ value: joined, set: setJoined }, under(e.currentTarget, t("members.filter.joined")))}>
          {joined ? t("members.filter.joinedIn", { span: spanLabel(joined) }) : t("members.filter.joined")} <ChevronDown size={14} aria-hidden="true" />
        </button>
        <button type="button" className={s.filterBtn} data-on={created ? "" : undefined} aria-haspopup="menu" onClick={(e) => memberAgeFilterMenu.open({ value: created, set: setCreated }, under(e.currentTarget, t("members.filter.age")))}>
          {created ? t("members.filter.ageIn", { span: spanLabel(created) }) : t("members.filter.age")} <ChevronDown size={14} aria-hidden="true" />
        </button>
        {seesJoins ? (
          <button
            type="button"
            className={s.filterBtn}
            data-on={method || code ? "" : undefined}
            aria-haspopup="menu"
            onClick={(e) =>
              memberJoinFilterMenu.open(
                {
                  method,
                  code,
                  codes,
                  pick: (m, c) => {
                    setMethod(m);
                    setCode(c);
                  },
                },
                under(e.currentTarget, t("members.col.joinMethod")),
              )
            }
          >
            {code ? t("members.filter.invite", { code }) : method ? joinMethodLabel(method) : t("members.col.joinMethod")} <ChevronDown size={14} aria-hidden="true" />
          </button>
        ) : null}
        <span className={s.filterRule} aria-hidden="true" />
        {SIGNALS.map((sig) => (
          <button key={sig} type="button" className={s.filterBtn} aria-pressed={signals.includes(sig)} data-on={signals.includes(sig) ? "" : undefined} onClick={() => setSignals((cur) => (cur.includes(sig) ? cur.filter((x) => x !== sig) : [...cur, sig]))}>
            <SignalIcon kind={sig} /> {signalLabel(sig)}
          </button>
        ))}
      </div>
      <div className={s.countRow}>
        <span className={s.count} role="status">{rows ? (filtering ? t("members.found", { count: total }) : t("members.total", { count: total })) : ""}</span>
        {filtering ? <button type="button" className={s.clear} onClick={clearFilters}>{t("members.filter.clear")}</button> : null}
        {canI(server, P.KICK_MEMBERS) ? (
          <Button size="sm" variant="secondary" onClick={() => setPruning(true)}>
            <Scissors size={14} aria-hidden="true" /> {t("members.prune.button")}
          </Button>
        ) : null}
      </div>
      {failed ? <LoadError message={t("members.loadFailed")} onRetry={() => void load(0)} /> : null}
      {rows === null && !failed ? <Spinner label={t("chat.pins.loading")} /> : null}
      {rows && rows.length === 0 ? <p className={st.hint}>{filtering ? t("members.none") : t("members.empty")}</p> : null}
      {rows && rows.length > 0 ? (
        mobile ? (
          <div className={s.cards}>
            {rows.map((m) => (
              <div
                key={m.user_id}
                className={s.card}
                data-picked={picked.has(m.user_id) || undefined}
                data-member={m.user_id}
                // a long press picks, as it does in the phone app
                onContextMenu={(e) => {
                  if (!pickable(m.user_id)) return;
                  e.preventDefault();
                  toggle(m.user_id);
                }}
              >
                {pickable(m.user_id) ? <input type="checkbox" className={s.tick} checked={picked.has(m.user_id)} onChange={() => toggle(m.user_id)} aria-label={t("members.pick", { name: nameOf(m) })} /> : <span style={{ width: 18 }} />}
                <button type="button" className={s.cardMain} onClick={() => setViewing(m.user_id)}>
                  <span className={s.who}>
                    <Avatar id={m.user_id} avatarId={m.avatar_id ?? users[m.user_id]?.avatar_id ?? null} name={nameOf(m)} size={36} />
                    <span className={s.whoText}>
                      <span className={s.name} dir="auto">{nameOf(m)}</span>
                      <span className={s.handle}>@{users[m.user_id]?.username ?? ""}</span>
                    </span>
                    <Signals list={m.signals} />
                  </span>
                  <span className={s.cardFacts}>
                    <span>{t("members.card.member", { date: formatDateDivider(m.joined_at) })}</span>
                    <span>{t("members.card.account", { age: formatSpan(Date.now() - m.account_created_at) })}</span>
                    {m.join ? <span>{joinMethodLabel(m.join.join_method)}</span> : null}
                  </span>
                  <RoleChips serverId={serverId} ids={m.role_ids} />
                </button>
              </div>
            ))}
          </div>
        ) : (
          <table className={s.table}>
            <thead>
              <tr>
                <th aria-label={t("members.pickColumn")} />
                <th>{t("members.col.name")}</th>
                <th>{t("members.col.memberSince")}</th>
                <th>{t("members.col.joinedNexa")}</th>
                {seesJoins ? <th>{t("members.col.joinMethod")}</th> : null}
                <th>{t("server.members.roles")}</th>
                <th>{t("members.col.signals")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.user_id} data-picked={picked.has(m.user_id) || undefined} data-member={m.user_id} onClick={() => setViewing(m.user_id)}>
                  <td onClick={(e) => e.stopPropagation()}>
                    {pickable(m.user_id) ? <input type="checkbox" className={s.tick} checked={picked.has(m.user_id)} onChange={() => toggle(m.user_id)} aria-label={t("members.pick", { name: nameOf(m) })} /> : null}
                  </td>
                  <td>
                    <span className={s.who}>
                      <Avatar id={m.user_id} avatarId={m.avatar_id ?? users[m.user_id]?.avatar_id ?? null} name={nameOf(m)} size={32} />
                      <span className={s.whoText}>
                        <button type="button" className={s.name} dir="auto" onClick={(e) => { e.stopPropagation(); setViewing(m.user_id); }}><PersonName userId={m.user_id} serverId={serverId}>{nameOf(m)}</PersonName></button>
                        <span className={s.handle}>@{users[m.user_id]?.username ?? ""}</span>
                      </span>
                    </span>
                  </td>
                  <td className={s.date}>{formatDateDivider(m.joined_at)}</td>
                  <td className={s.date}>
                    {formatDateDivider(m.account_created_at)}
                    <span className={s.dateSub}>{formatSpan(Date.now() - m.account_created_at)}</span>
                  </td>
                  {seesJoins ? <td>{methodCell(m)}</td> : null}
                  <td><RoleChips serverId={serverId} ids={m.role_ids} /></td>
                  <td><Signals list={m.signals} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      ) : null}
      {rows && rows.length < total ? (
        <div className={s.top} style={{ justifyContent: "center", marginTop: 12 }}>
          <Button size="sm" variant="secondary" loading={loading} onClick={() => void load(rows.length)}>{t("members.more", { count: total - rows.length })}</Button>
        </div>
      ) : null}
      {picked.size ? <div className={s.bulkSpace} aria-hidden="true" /> : null}
      {picked.size ? (
        <div className={s.bulk} role="region" aria-label={t("members.bulk.label")}>
          <span className={s.bulkText}>{t("members.bulk.picked", { count: picked.size })}</span>
          <span className={s.bulkBtns}>
            <Button size="sm" variant="ghost" onClick={() => setPicked(new Set())}>{t("members.bulk.clear")}</Button>
            {canI(server, P.KICK_MEMBERS) ? <Button size="sm" variant="secondary" onClick={bulkKick}>{t("members.bulk.kick", { count: picked.size })}</Button> : null}
            {canI(server, P.BAN_MEMBERS) ? <Button size="sm" variant="danger" onClick={bulkBan}>{t("members.bulk.ban", { count: picked.size })}</Button> : null}
          </span>
        </div>
      ) : null}
      {viewing ? <ModView serverId={serverId} userId={viewing} onClose={() => setViewing(null)} onChanged={() => void load(0)} /> : null}
      {pruning ? <PruneDialog serverId={serverId} onClose={() => setPruning(false)} onDone={() => void load(0)} /> : null}
    </>
  );
}

/** Taking out the people nobody has seen for a while, who hold no role but the ones that keep them. */
function PruneDialog({ serverId, onClose, onDone }: { serverId: string; onClose: () => void; onDone: () => void }) {
  const server = useServers((x) => x.byId[serverId]);
  const [days, setDays] = useState(30);
  const [keep, setKeep] = useState<string[]>([]);
  const [count, setCount] = useState<number | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const toast = useUI((u) => u.toast);
  useEffect(() => {
    let alive = true;
    setCount(null);
    void serversApi
      .prunePreview(serverId, days, keep)
      .then((r) => alive && setCount(r.count))
      .catch(() => alive && setCount(0));
    return () => {
      alive = false;
    };
  }, [serverId, days, keep]);
  const prune = async () => {
    setBusy(true);
    try {
      const res = await serversApi.prune(serverId, days, keep, reason.trim() || undefined);
      toast(t("members.prune.done", { count: res.pruned }), "success");
      onDone();
      onClose();
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("members.mod.failed"), "error");
    } finally {
      setBusy(false);
    }
  };
  const roles = rolesByPosition(server).filter((r) => r.id !== serverId);
  return (
    <Modal
      open
      onClose={onClose}
      title={t("members.prune.title")}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button variant="danger" loading={busy} disabled={!count} onClick={() => void prune()}>{t("members.prune.confirm", { count: count ?? 0 })}</Button>
        </>
      }
    >
      <div className={st.form}>
        <p className={st.hint} style={{ marginTop: 0 }}>{t("members.prune.what")}</p>
        <Field label={t("members.prune.days")}>
          <Select value={days} onChange={(e) => setDays(Number(e.target.value))}>
            {[1, 7, 30, 90, 180, 365].map((d) => <option key={d} value={d}>{formatSpan(d * DAY)}</option>)}
          </Select>
        </Field>
        {roles.length ? (
          <Field as="div" label={t("members.prune.keep")} hint={t("members.prune.keepHint")}>
            <div className={s.filters}>
              {roles.map((r) => (
                <button key={r.id} type="button" className={s.filterBtn} aria-pressed={keep.includes(r.id)} data-on={keep.includes(r.id) ? "" : undefined} onClick={() => setKeep((cur) => (cur.includes(r.id) ? cur.filter((x) => x !== r.id) : [...cur, r.id]))}>
                  {r.name}
                </button>
              ))}
            </div>
          </Field>
        ) : null}
        <Field label={t("server.memberAction.reasonOptional")}>
          <TextInput value={reason} onChange={(e) => setReason(e.target.value)} maxLength={512} />
        </Field>
        <p className={st.hint} role="status">{count === null ? t("members.prune.counting") : t("members.prune.count", { count })}</p>
      </div>
    </Modal>
  );
}
