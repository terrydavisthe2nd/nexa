import { create } from "zustand";
import type { Member, Role, Server, ServerView } from "@/api/types";
import { ALL, base, compute, has, highestPosition, P, threadPerms, toPermMember, toPermRoles, type PermRole } from "@/lib/perms";

/** The flag bit marking an account that runs Nexa. */
import { useChannels } from "./channels";
import { useThreads } from "./threads";
import { viewingAs } from "./viewAs";

export interface ServerState {
  server: Server;
  roles: Record<string, Role>;
  members: Record<string, Member>;
  memberCount: number;
  me: Member;
  /** Server-level permissions of the current user as the server computed them. */
  myPermissions: bigint;
  /** The server's members are complete (small servers ship them in ready). */
  membersComplete: boolean;
}

interface ServersState {
  byId: Record<string, ServerState>;
  /** Join order, so the rail has a stable default ordering. */
  order: string[];
  setFromView: (view: ServerView) => void;
  updateServer: (server: Server) => void;
  remove: (id: string) => void;
  upsertRole: (role: Role) => void;
  upsertRoles: (serverId: string, roles: Role[]) => void;
  removeRole: (serverId: string, roleId: string) => void;
  /** `joined` is true only for member_add: an update about a member not loaded here is not a join. */
  upsertMember: (member: Member, joined?: boolean) => void;
  upsertMembers: (serverId: string, members: Member[], complete?: boolean) => void;
  /**
   * `left` is true for member_remove, which counts the member out even when it
   * was never loaded (large servers load members lazily). A local removal leaves
   * the count to that event, so the two together count the member out once.
   */
  removeMember: (serverId: string, userId: string, left?: boolean) => void;
  setMemberCount: (serverId: string, count: number) => void;
  clear: () => void;
  /** Works out this account's permissions again, as View Server as Role starts or stops. */
  refreshMine: (serverId: string) => void;
}

function stripView(view: ServerView): Server {
  const { channels: _c, roles: _r, members: _m, member_count: _mc, me: _me, my_permissions: _mp, threads: _t, joined_threads: _j, ...server } = view;
  void _t;
  void _j;
  void _c;
  void _r;
  void _m;
  void _mc;
  void _me;
  void _mp;
  return server;
}

export const useServers = create<ServersState>()((set) => ({
  byId: {},
  order: [],
  setFromView: (view) =>
    set((s) => {
      const roles: Record<string, Role> = {};
      for (const r of view.roles) roles[r.id] = r;
      const members: Record<string, Member> = {};
      for (const m of view.members) members[m.user_id] = m;
      members[view.me.user_id] = view.me;
      const st: ServerState = {
        server: stripView(view),
        roles,
        members,
        memberCount: view.member_count,
        me: view.me,
        myPermissions: BigInt(view.my_permissions),
        membersComplete: view.members.length >= view.member_count,
      };
      const order = s.order.includes(view.id) ? s.order : [...s.order, view.id];
      return { byId: { ...s.byId, [view.id]: st }, order };
    }),
  updateServer: (server) =>
    set((s) => {
      const cur = s.byId[server.id];
      if (!cur) return s;
      // a new owner_id changes who holds every permission
      const next = { ...cur, server };
      return { byId: { ...s.byId, [server.id]: { ...next, myPermissions: recomputeMine(next) } } };
    }),
  remove: (id) =>
    set((s) => {
      if (!(id in s.byId)) return s;
      const byId = { ...s.byId };
      delete byId[id];
      return { byId, order: s.order.filter((x) => x !== id) };
    }),
  upsertRole: (role) =>
    set((s) => {
      const cur = s.byId[role.server_id];
      if (!cur) return s;
      const roles = { ...cur.roles, [role.id]: role };
      return { byId: { ...s.byId, [role.server_id]: { ...cur, roles, myPermissions: recomputeMine({ ...cur, roles }) } } };
    }),
  upsertRoles: (serverId, list) =>
    set((s) => {
      const cur = s.byId[serverId];
      if (!cur) return s;
      const roles: Record<string, Role> = {};
      for (const r of list) roles[r.id] = r;
      return { byId: { ...s.byId, [serverId]: { ...cur, roles, myPermissions: recomputeMine({ ...cur, roles }) } } };
    }),
  removeRole: (serverId, roleId) =>
    set((s) => {
      const cur = s.byId[serverId];
      if (!cur || !(roleId in cur.roles)) return s;
      const roles = { ...cur.roles };
      delete roles[roleId];
      const members: Record<string, Member> = {};
      for (const [uid, m] of Object.entries(cur.members)) members[uid] = m.role_ids.includes(roleId) ? { ...m, role_ids: m.role_ids.filter((r) => r !== roleId) } : m;
      const me = members[cur.me.user_id] ?? cur.me;
      const next = { ...cur, roles, members, me };
      return { byId: { ...s.byId, [serverId]: { ...next, myPermissions: recomputeMine(next) } } };
    }),
  upsertMember: (member, joined = false) =>
    set((s) => {
      const cur = s.byId[member.server_id];
      if (!cur) return s;
      const known = member.user_id in cur.members;
      const members = { ...cur.members, [member.user_id]: member };
      const me = member.user_id === cur.me.user_id ? member : cur.me;
      const next = { ...cur, members, me, memberCount: joined && !known ? cur.memberCount + 1 : cur.memberCount };
      return { byId: { ...s.byId, [member.server_id]: { ...next, myPermissions: recomputeMine(next) } } };
    }),
  upsertMembers: (serverId, list, complete) =>
    set((s) => {
      const cur = s.byId[serverId];
      if (!cur) return s;
      const members = { ...cur.members };
      for (const m of list) members[m.user_id] = m;
      // This device's own row comes down in a bulk load like any other, and a
      // member_update missed across a reconnect arrives only here. Every other
      // member mutation recomputes these; leaving them stale showed the new
      // role's colour beside buttons the old permissions still hid.
      const me = members[cur.me.user_id] ?? cur.me;
      const next = { ...cur, members, me, membersComplete: complete ?? cur.membersComplete };
      return { byId: { ...s.byId, [serverId]: { ...next, myPermissions: recomputeMine(next) } } };
    }),
  removeMember: (serverId, userId, left = false) =>
    set((s) => {
      const cur = s.byId[serverId];
      if (!cur) return s;
      const members = { ...cur.members };
      delete members[userId];
      return { byId: { ...s.byId, [serverId]: { ...cur, members, memberCount: Math.max(0, cur.memberCount - (left ? 1 : 0)) } } };
    }),
  setMemberCount: (serverId, count) =>
    set((s) => {
      const cur = s.byId[serverId];
      if (!cur) return s;
      return { byId: { ...s.byId, [serverId]: { ...cur, memberCount: count } } };
    }),
  clear: () => set({ byId: {}, order: [] }),
  refreshMine: (serverId) =>
    set((s) => {
      const cur = s.byId[serverId];
      if (!cur) return s;
      return { byId: { ...s.byId, [serverId]: { ...cur, myPermissions: recomputeMine(cur) } } };
    }),
}));

function recomputeMine(st: ServerState): bigint {
  return serverPerms(st, st.me.user_id);
}

/** Server-level permissions of a user from local state. */
export function serverPerms(st: ServerState, userId: string): bigint {
  const preview = userId === st.me.user_id ? viewingAs(st.server.id) : null;
  if (preview) return base(st.server.id, toPermRoles(Object.values(st.roles)), { userId, roleIds: preview, timedOut: false }, false);
  // only the server's owner holds everything: Nexa's staff are ordinary members here
  if (st.server.owner_id === userId) return ALL;
  // Base, as the server works out my_permissions: compute() with no channel
  // treats a missing View Channel as "can see nothing" and returned 0, so
  // staff whose view came from channel overwrites lost every server-wide
  // action after any role update.
  const m = st.members[userId];
  return base(st.server.id, toPermRoles(Object.values(st.roles)), toPermMember(m, userId), false);
}

/** A user's permissions in a channel of the server, from local state. A thread answers to its channel (threadPerms). */
export function channelPerms(st: ServerState, channelId: string, userId: string): bigint {
  const byId = useChannels.getState().byId;
  const ch = byId[channelId];
  const preview = userId === st.me.user_id ? viewingAs(st.server.id) : null;
  const isOwner = !preview && st.server.owner_id === userId;
  const roles = toPermRoles(Object.values(st.roles));
  const member = preview ? { userId, roleIds: preview, timedOut: false } : toPermMember(st.members[userId], userId);
  if ((ch?.type === "thread" || ch?.type === "private_thread") && ch.parent_id) {
    const parent = compute(st.server.id, roles, member, isOwner, byId[ch.parent_id]?.overwrites);
    // only this account's own membership is known here; others' private threads read as closed
    const inIt = userId === st.me.user_id && !!useThreads.getState().joined[channelId];
    return threadPerms(parent, ch.type === "private_thread", inIt);
  }
  return compute(st.server.id, roles, member, isOwner, ch?.overwrites);
}

export function memberHighest(st: ServerState, userId: string): { highest: number; isOwner: boolean } {
  const preview = userId === st.me.user_id ? viewingAs(st.server.id) : null;
  if (preview) return { highest: highestPosition(toPermRoles(Object.values(st.roles)), { userId, roleIds: preview, timedOut: false }), isOwner: false };
  const isOwner = st.server.owner_id === userId;
  return { highest: highestPosition(toPermRoles(Object.values(st.roles)), toPermMember(st.members[userId], userId)), isOwner };
}

/** Roles sorted top (highest position) first. */
export function rolesByPosition(st: ServerState | undefined): Role[] {
  if (!st) return [];
  return Object.values(st.roles).sort((a, b) => b.position - a.position || (a.id < b.id ? -1 : 1));
}

/** The colour of the member's highest coloured role, as a CSS colour, or undefined. */
export function memberColor(st: ServerState | undefined, userId: string): string | undefined {
  if (!st) return undefined;
  const m = st.members[userId];
  if (!m) return undefined;
  let best: Role | undefined;
  for (const id of m.role_ids) {
    const r = st.roles[id];
    if (r && r.color !== 0 && (!best || r.position > best.position)) best = r;
  }
  return best ? `#${best.color.toString(16).padStart(6, "0")}` : undefined;
}

export function permRolesOf(st: ServerState): PermRole[] {
  return toPermRoles(Object.values(st.roles));
}

export function canI(st: ServerState | undefined, need: bigint, channelId?: string): boolean {
  if (!st) return false;
  if (channelId) return has(channelPerms(st, channelId, st.me.user_id), need);
  // `myPermissions` was worked out once, with the timeout of that moment folded
  // into it, and a timeout that simply lapses gives the server no reason to
  // send anything. Channel checks recompute and quietly came back to life while
  // every server-wide one stayed off until a reload.
  if (viewingAs(st.server.id)) return has(serverPerms(st, st.me.user_id), need);
  const until = st.me.timeout_until;
  const lapsed = !!until && until <= Date.now();
  return has(lapsed ? serverPerms(st, st.me.user_id) : st.myPermissions, need);
}

export { P };
