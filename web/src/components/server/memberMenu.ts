import { P, canActOn } from "@/lib/perms";
import { useAuth } from "@/stores/auth";
import { canI, memberHighest, useServers } from "@/stores/servers";
import { useSettings } from "@/stores/settings";
import { copyAndSay } from "@/lib/copyText";
import { copyBotIdItem } from "@/lib/copyId";
import { useUsers } from "@/stores/users";
import { relationshipButtons } from "@/lib/friendship";
import { profileLinkItem } from "@/lib/profileLinks";
import { about, button, defineMenu, type MenuButton } from "@/lib/contextMenus/registry";
import { userAppsItem } from "@/components/chat/apps/appMenus";
import { useChannels } from "@/stores/channels";
import { useUI } from "@/stores/ui";
import { usePick, type PickList } from "@/stores/pick";

import { t } from "@/i18n";
/** Whether the current user outranks a member (or is that member). */
export function outranks(serverId: string, userId: string): boolean {
  const st = useServers.getState().byId[serverId];
  const me = useAuth.getState().me;
  if (!st || !me) return false;
  if (userId === me.id) return true;
  const actor = memberHighest(st, me.id);
  const target = memberHighest(st, userId);
  return canActOn(actor.highest, target.highest, actor.isOwner, target.isOwner);
}

export interface MemberMenuCtx extends MemberTarget {
  /** The list to pick more people from, where the menu offers picking. */
  select?: PickList;
}

/** The server and the signed-in account, when both are there to build the menu for. */
function where(serverId: string) {
  const st = useServers.getState().byId[serverId];
  const me = useAuth.getState().me;
  return st && me ? { st, me } : null;
}

/** The member a set of buttons is about, found in the context of the menu they are in. */
export interface MemberTarget {
  serverId: string;
  userId: string;
  openModal: (kind: string, props?: Record<string, unknown>) => void;
}

/** Whether the member can be moderated: someone else, whom the signed-in account outranks. */
const moderatable = (serverId: string, userId: string) => useAuth.getState().me?.id !== userId && outranks(serverId, userId);

/** Profile, Copy Profile Link and Mention: who the member is. `ids` names them in the menu they are in. */
export function memberProfileButtons<C>(ids: { profile: string; link: string; mention: string }, of: (ctx: C) => MemberTarget | null): [MenuButton<C>, MenuButton<C>, MenuButton<C>] {
  const target = (c: C) => {
    const m = of(c);
    return m && where(m.serverId) ? m : null;
  };
  return [
    button(ids.profile, (c) => {
      const m = target(c);
      return m && { id: "profile", label: t("profile.profile.profile"), onSelect: () => m.openModal("profile", { userId: m.userId }) };
    }),
    button(ids.link, (c) => {
      const m = target(c);
      return m && profileLinkItem(m.userId);
    }),
    button(ids.mention, (c) => {
      const m = target(c);
      if (!m) return null;
      const username = useUsers.getState().byId[m.userId]?.username;
      return { id: "mention", label: t("settings.notifications.mention"), disabled: !username, onSelect: () => window.dispatchEvent(new CustomEvent("ec:insert-text", { detail: `@${username} ` })) };
    }),
  ];
}

/** Their server profile (one's own to edit; someone else's nickname takes Manage Nicknames) and their roles. */
export function memberManageButtons<C>(ids: { nickname: string; roles: string }, of: (ctx: C) => MemberTarget | null): [MenuButton<C>, MenuButton<C>] {
  return [
    button(ids.nickname, (c) => {
      const m = of(c);
      const w = m && where(m.serverId);
      if (!m || !w) return null;
      const { serverId, userId, openModal } = m;
      const self = userId === w.me.id;
      return (self || (canI(w.st, P.MANAGE_NICKNAMES) && outranks(serverId, userId))) && {
        id: "nick",
        label: self ? t("sidebar.serverMenu.editServerProfile") : t("server.memberMenu.changeTheirNickname"),
        onSelect: () => (self ? openModal("settings", { page: "profiles", serverId }) : openModal("serverProfile", { serverId, userId })),
      };
    }),
    button(ids.roles, (c) => {
      const m = of(c);
      const w = m && where(m.serverId);
      return !!m && !!w && canI(w.st, P.MANAGE_ROLES) && outranks(m.serverId, m.userId) && { id: "roles", label: t("server.members.roles"), onSelect: () => m.openModal("memberAction", { serverId: m.serverId, userId: m.userId, action: "roles" }) };
    }),
  ];
}

/** Time Out, Kick and Ban, each where this account may and outranks the member; nothing for itself. */
export function memberModerationButtons<C>(ids: { timeout: string; kick: string; ban: string }, of: (ctx: C) => MemberTarget | null): MenuButton<C>[] {
  const target = (c: C) => {
    const m = of(c);
    const w = m && where(m.serverId);
    return m && w && moderatable(m.serverId, m.userId) ? { ...m, st: w.st } : null;
  };
  return [
    button(ids.timeout, (c) => {
      const m = target(c);
      if (!m || !canI(m.st, P.MODERATE_MEMBERS)) return null;
      const until = m.st.members[m.userId]?.timeout_until;
      const timedOut = !!until && until > Date.now();
      return { id: "timeout", label: timedOut ? t("server.memberAction.removeTimeout") : t("server.members.timeout"), danger: true, onSelect: () => m.openModal("memberAction", { serverId: m.serverId, userId: m.userId, action: "timeout" }) };
    }),
    button(ids.kick, (c) => {
      const m = target(c);
      return !!m && canI(m.st, P.KICK_MEMBERS) && { id: "kick", label: t("server.members.kick"), danger: true, onSelect: () => m.openModal("memberAction", { serverId: m.serverId, userId: m.userId, action: "kick" }) };
    }),
    button(ids.ban, (c) => {
      const m = target(c);
      return !!m && canI(m.st, P.BAN_MEMBERS) && { id: "ban", label: t("server.members.ban"), danger: true, onSelect: () => m.openModal("memberAction", { serverId: m.serverId, userId: m.userId, action: "ban" }) };
    }),
  ];
}

const [viewMemberProfile, copyMemberProfileLink, mentionMember] = memberProfileButtons<MemberMenuCtx>({ profile: "viewMemberProfile", link: "copyMemberProfileLink", mention: "mentionMember" }, (c) => c);
const [editMemberNickname, editMemberRoles] = memberManageButtons<MemberMenuCtx>({ nickname: "editMemberNickname", roles: "editMemberRoles" }, (c) => c);

/**
 * Context menu for a member row, in the order people know from other chat
 * apps: who they are, apps, friendship and blocking, moderation, roles, then
 * the copy items.
 */
export const memberMenu = defineMenu<MemberMenuCtx>("member", [
  [viewMemberProfile, mentionMember],
  // what apps can do with someone, used in the conversation open in this server
  [button("memberApps", ({ serverId, userId }) => {
    const here = useUI.getState().activeChannelId;
    return !!where(serverId) && useChannels.getState().byId[here ?? ""]?.server_id === serverId ? userAppsItem(here, userId) : null;
  })],
  // being friends, and blocking: the same choices a profile offers
  relationshipButtons<MemberMenuCtx>({ friend: "memberFriendship", block: "blockMember" }, ({ serverId, userId }) => (where(serverId) ? userId : null)),
  [editMemberNickname, ...memberModerationButtons<MemberMenuCtx>({ timeout: "timeoutMember", kick: "kickMember", ban: "banMember" }, (c) => c)],
  [editMemberRoles],
  [
    copyMemberProfileLink,
    button("copyMemberId", ({ serverId, userId }) => !!where(serverId) && useSettings.getState().settings.developerMode && { id: "copy", label: t("chat.groupMemberMenu.copyUserId"), onSelect: () => copyAndSay(userId) }),
    button("copyMemberBotId", ({ serverId, userId }) => !!where(serverId) && useSettings.getState().settings.developerMode && copyBotIdItem(userId)),
  ],
  [button("selectMembers", ({ serverId, userId, select }) => !!select && !!where(serverId) && { id: "select", label: t("pick.select"), onSelect: () => usePick.getState().start(select, userId) })],
]);
about(memberMenu, ["user", "server"], ({ serverId, userId }) => ({ serverId, userId }));

