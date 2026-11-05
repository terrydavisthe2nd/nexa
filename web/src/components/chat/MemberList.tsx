import { Crown } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type MouseEvent } from "react";
import { servers as serversApi } from "@/api/servers";
import type { Member, PublicUser, Role } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { RankMark } from "@/components/common/OwnerBadge";
import { isOwner } from "@/lib/owner";
import { memberName } from "@/lib/names";
import { memberMenu } from "@/components/server/memberMenu";
import { memberBulk } from "@/components/server/memberBulk";
import { PickBar, PickMark } from "@/components/common/Pick";
import { pickClick, usePick } from "@/stores/pick";
import { useAuth } from "@/stores/auth";
import { useSettings } from "@/stores/settings";
import { useTypingIn } from "@/stores/typing";
import { activitiesOf, customStatus, statusOf, usePresence } from "@/stores/presence";
import { StatusLine } from "@/components/activity/StatusLine";
import { useChannels } from "@/stores/channels";
import { isThread, useThreads } from "@/stores/threads";
import { threads as threadsApi } from "@/api/threads";
import { permRolesOf, rolesByPosition, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { openProfilePopout } from "@/stores/profilePopout";
import { P, compute, has, toPermMember } from "@/lib/perms";
import { nameOrder } from "./nameOrder";
import { memberLook, nameClass, nameStyle, type RoleLook } from "@/lib/roleLook";
import { withOwn } from "@/lib/useMemberLook";
import { RoleIcon } from "@/components/common/RoleIcon";
import s from "./MemberList.module.css";
import { UserTag } from "@/components/servertag/UserTag";
import { BotTag } from "@/components/apps/BotTag";

import { t } from "@/i18n";



type Row =
  | { kind: "head"; key: string; label: string; count: number }
  | { kind: "member"; key: string; member: Member; user: PublicUser | undefined; look: RoleLook | undefined; offline: boolean };

const ROW_H = 44;
const HEAD_H = 40;
const VIRTUAL_FROM = 200;

/** Members grouped by hoisted roles, then online, then offline; windowed past 200 rows. */
const NO_PICKS: string[] = [];

export function MemberList({ serverId, channelId, drawer, open, entering, onEntered }: { serverId: string; channelId: string; drawer?: boolean; open?: boolean; entering?: boolean; onEntered?: () => void }) {
  const st = useServers((x) => x.byId[serverId]);
  const upsertMembers = useServers((x) => x.upsertMembers);
  const users = useUsers((u) => u.byId);
  const upsertUsers = useUsers((u) => u.upsert);
  const presence = usePresence((p) => p.byUser);
  const meId = useAuth((a) => a.me?.id);
  // whoever types in the channel open beside the list shows it on their dot
  const typingOn = useSettings((x) => x.settings.typingAvatars !== false);
  const typers = useTypingIn(typingOn ? channelId : null, meId);
  const openModal = useUI((u) => u.openModal);
  const ref = useRef<HTMLElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [height, setHeight] = useState(600);
  const [pagingFailed, setPagingFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const complete = st?.membersComplete ?? true;

  // large servers ship without members in ready; page them in once
  useEffect(() => {
    if (complete) return;
    let alive = true;
    (async () => {
      let after: string | undefined;
      for (let i = 0; i < 20 && alive; i++) {
        const res = await serversApi.members(serverId, { limit: 1000, ...(after ? { after } : {}) });
        if (!alive) return;
        upsertUsers(res.users);
        upsertMembers(serverId, res.members, res.members.length < 1000);
        if (res.members.length < 1000) break;
        after = res.members[res.members.length - 1]?.user_id;
      }
      // nothing left to page: the loop either finished the list or ran its 20 rounds
      if (alive) setPagingFailed(false);
    })().catch(() => {
      // A page that fails leaves the list short with no spinner, no error and
      // nothing to change the effect's deps, so it never tried again.
      if (alive) setPagingFailed(true);
    });
    return () => {
      alive = false;
    };
  }, [complete, serverId, upsertMembers, upsertUsers, attempt]);

  // try again when the connection comes back
  useEffect(() => {
    if (!pagingFailed) return;
    const again = () => setAttempt((n) => n + 1);
    window.addEventListener("online", again);
    const timer = setTimeout(again, 15_000);
    return () => {
      window.removeEventListener("online", again);
      clearTimeout(timer);
    };
  }, [pagingFailed]);

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => setHeight(el.clientHeight));
    ro.observe(el);
    setHeight(el.clientHeight);
    return () => ro.disconnect();
  }, []);

  // A thread lists the people in it; it has no overwrites of its own, so who
  // may see it at all is its channel's business.
  const threadParent = useChannels((c) => (isThread(c.byId[channelId]) ? (c.byId[channelId]?.parent_id ?? null) : undefined));
  const inThread = threadParent !== undefined;
  const threadCount = useThreads((x) => x.memberCounts[channelId]);
  const [threadIds, setThreadIds] = useState<Set<string> | null>(null);
  useEffect(() => {
    setThreadIds(null);
    if (!inThread) return;
    let alive = true;
    threadsApi
      .members(channelId)
      .then((res) => {
        if (!alive) return;
        upsertUsers(res.users);
        setThreadIds(new Set(res.members.map((m) => m.user_id)));
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
    // a change in how many are in it is someone joining or leaving: asked again
  }, [inThread, channelId, threadCount, upsertUsers]);

  // Who can see the channel changes with members, roles and overwrites, not
  // presence, so it is worked out apart from the presence-driven rows below,
  // with the server's roles converted once rather than once per member.
  const overwrites = useChannels((c) => c.byId[threadParent ?? channelId]?.overwrites);
  const visible = useMemo<Member[]>(() => {
    if (!st) return [];
    if (inThread && !threadIds) return [];
    const roles = permRolesOf(st);
    const now = Date.now();
    return Object.values(st.members).filter((m) => {
      if (threadIds && !threadIds.has(m.user_id)) return false;
      const isOwner = st.server.owner_id === m.user_id;
      return has(compute(serverId, roles, toPermMember(m, m.user_id, now), isOwner, overwrites), P.VIEW_CHANNEL);
    });
  }, [st, serverId, overwrites, inThread, threadIds]);

  // The order by name depends on names, not on who is online, so it is sorted
  // when the members or their names change. Presence changes all the time, and
  // sorting a large server's whole list again for every one of them was most
  // of what a busy member list cost; now a change only regroups the list.
  const sorted = useMemo<Member[]>(() => {
    const named = visible.map((m) => ({ m, name: memberName(m.nickname, users[m.user_id]) }));
    const order = nameOrder();
    named.sort((a, b) => order.compare(a.name, b.name));
    return named.map((x) => x.m);
  }, [visible, users]);

  const rows = useMemo<Row[]>(() => {
    if (!st) return [];
    const roles = rolesByPosition(st);
    const hoisted = roles.filter((r) => r.hoist && r.id !== serverId);
    const groups = new Map<string, { label: string; members: Member[] }>();
    for (const r of hoisted) groups.set(r.id, { label: r.name, members: [] });
    const online: Member[] = [];
    const offline: Member[] = [];
    for (const m of sorted) {
      const status = statusOf(presence[m.user_id]);
      if (status === "offline" || status === "invisible") {
        offline.push(m);
        continue;
      }
      let top: Role | undefined;
      for (const id of m.role_ids) {
        const r = st.roles[id];
        if (r?.hoist && (!top || r.position > top.position)) top = r;
      }
      if (top && groups.has(top.id)) groups.get(top.id)!.members.push(m);
      else online.push(m);
    }
    const out: Row[] = [];
    // each list keeps the name order it was filled in
    const push = (key: string, label: string, list: Member[], off: boolean) => {
      if (list.length === 0) return;
      out.push({ kind: "head", key: "h:" + key, label, count: list.length });
      for (const m of list) out.push({ kind: "member", key: m.user_id, member: m, user: users[m.user_id], look: withOwn(memberLook(st, m.user_id), users[m.user_id]?.name_style), offline: off });
    };
    for (const [id, g] of groups) push(id, g.label, g.members, false);
    push("online", t("chat.memberList.online"), online, false);
    push("offline", t("chat.memberList.offline"), offline, true);
    return out;
  }, [st, sorted, users, presence, serverId]);

  // picking several members at once (stores/pick.ts)
  const pickList = `members:${serverId}` as const;
  const picked = usePick((p) => (p.list === pickList ? p.ids : NO_PICKS));
  const pickMode = usePick((p) => p.list === pickList && p.mode);
  const isPicking = pickMode || picked.length > 0;
  const pickOrder = useMemo(() => rows.flatMap((r) => (r.kind === "head" ? [] : [r.member.user_id])), [rows]);
  if (!st) return null;
  const virtual = rows.length > VIRTUAL_FROM;
  let start = 0;
  let end = rows.length;
  let padTop = 0;
  let padBottom = 0;
  if (virtual) {
    const heights = rows.map((r) => (r.kind === "head" ? HEAD_H : ROW_H));
    let y = 0;
    while (start < rows.length && y + heights[start]! < scrollTop - 400) y += heights[start++]!;
    padTop = y;
    end = start;
    let y2 = y;
    while (end < rows.length && y2 < scrollTop + height + 400) y2 += heights[end++]!;
    for (let i = end; i < rows.length; i++) padBottom += heights[i]!;
  }

  const onContext = (e: MouseEvent, m: Member) => {
    e.preventDefault();
    const pick = usePick.getState();
    // a right click on one of several picked acts on all of them
    if (pick.list === pickList && pick.ids.includes(m.user_id) && pick.ids.length > 1) {
      memberBulk(serverId, pick.ids).menu({ x: e.clientX, y: e.clientY, label: t("pick.count", { count: pick.ids.length }) });
      return;
    }
    if (pick.list === pickList && !pick.mode && pick.ids.length) pick.clear();
    memberMenu.open({ serverId, userId: m.user_id, openModal, select: pickList }, { x: e.clientX, y: e.clientY });
  };
  const bulk = isPicking ? memberBulk(serverId, picked) : null;

  return (
    <aside className={s.list} ref={ref} onScroll={(e) => virtual && setScrollTop(e.currentTarget.scrollTop)} data-testid="member-list" data-open={drawer ? !!open : undefined} data-entering={entering || undefined} onAnimationEnd={(e) => { if (e.target === e.currentTarget && entering) onEntered?.(); }} aria-hidden={drawer && !open} {...(drawer && !open ? ({ inert: "" } as Record<string, string>) : {})}>
      {padTop ? <div style={{ height: padTop }} /> : null}
      {rows.slice(start, end).map((row) => {
        if (row.kind === "head") {
          return (
            <div key={row.key} className={s.head}>
              {t("chat.memberList.roleGroupCount", { label: row.label, count: row.count })}
            </div>
          );
        }
        const name = memberName(row.member.nickname, row.user, row.member.user_id === meId ? t("callScreen.you") : t("names.unknownUser"));
        const p = presence[row.member.user_id];
        return (
          <button
            key={row.key}
            className={s.row}
            data-offline={row.offline}
            onClick={(e) => {
              // Ctrl or ⌘ picks a member, Shift a run of them, a tap one while picking
              if (pickClick(e, pickList, row.member.user_id, pickOrder)) return;
              openProfilePopout(row.member.user_id, e.currentTarget, st.server.id);
            }}
            onContextMenu={(e) => onContext(e, row.member)}
            data-user={row.member.user_id}
            data-picked={picked.includes(row.member.user_id) || undefined}
            aria-pressed={isPicking ? picked.includes(row.member.user_id) : undefined}
          >
            {isPicking ? <PickMark on={picked.includes(row.member.user_id)} label={t("pick.pickNamed", { name })} /> : null}
            <Avatar id={row.member.user_id} avatarId={row.member.avatar_id ?? row.user?.avatar_id ?? null} name={name} size={32} status={statusOf(p)} typing={typingOn ? typers.includes(row.member.user_id) : undefined} />
            <span className={s.text}>
              <span className={s.name} style={row.look?.color ? { color: row.look.color } : undefined}>
                <span className={[s.nameText, nameClass(row.look)].join(" ")} style={nameStyle(row.look)}>{name}</span>
                {row.look?.icon ? <RoleIcon icon={row.look.icon} size={16} /> : null}
                {!isOwner(row.member.user_id) && st.server.owner_id === row.member.user_id ? <Crown size={14} className={s.crown} aria-label={t("chat.memberList.serverOwner")} /> : null}
                <RankMark userId={row.member.user_id} />
                <BotTag userId={row.member.user_id} />
                <UserTag userId={row.member.user_id} size="sm" />
              </span>
              <StatusLine
                className={s.sub}
                testId="member-activity"
                status={customStatus(p)}
                activity={activitiesOf(p)[0]}
                more={Math.max(0, activitiesOf(p).length - 1)}
              />
            </span>
          </button>
        );
      })}
      {padBottom ? <div style={{ height: padBottom }} /> : null}
      {bulk ? (
        <div className={s.pickBar}>
          <PickBar list={pickList} count={picked.length} label={t("pick.membersBar")} actions={bulk.bar} more={bulk.menu} />
        </div>
      ) : null}
    </aside>
  );
}
