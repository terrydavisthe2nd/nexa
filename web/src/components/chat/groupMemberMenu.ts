import { personText, personNode } from "@/lib/personText";
import { dmOpenError } from "@/lib/errors";
import { channels as channelsApi } from "@/api/channels";
import { closeDM, managesGroup } from "@/components/sidebar/dmMenu";
import { copyText } from "@/lib/clipboard";
import { channelDisplayName, friendNicknames } from "@/lib/names";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useRelationships } from "@/stores/relationships";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { about as menuAbout, button, defineMenu } from "@/lib/contextMenus/registry";
import { userAppsItem } from "./apps/appMenus";
import { usePick, type PickList } from "@/stores/pick";
import { displayName, useUsers } from "@/stores/users";
import { relationshipButtons } from "@/lib/friendship";

import { t } from "@/i18n";
import { profileLinkItem } from "@/lib/profileLinks";
import { copyBotIdItem } from "@/lib/copyId";
export interface GroupMemberMenuCtx {
  nav: (path: string) => void;
  openModal: (kind: string, props?: Record<string, unknown>) => void;
}

/** Opens (or starts) the one-to-one conversation with someone. */
async function messageUser(userId: string, nav: (path: string) => void): Promise<void> {
  try {
    const ch = await channelsApi.createDM([userId]);
    useChannels.getState().upsert(ch);
    nav(`/channels/@me/${ch.id}`);
  } catch (err) {
    useUI.getState().toast(dmOpenError(err, t("chat.groupMemberMenu.couldNotOpenTheConversation")), "error");
  }
}

export interface GroupMemberMenuRowCtx extends GroupMemberMenuCtx {
  channelId: string;
  userId: string;
  /** The list to pick more people from, where the menu offers picking. */
  select?: PickList;
}

/** What the menu needs to know about the group and the person, or null when it has nothing to offer. */
function about(channelId: string, userId: string) {
  const ch = useChannels.getState().byId[channelId];
  const me = useAuth.getState().me;
  // a one-to-one conversation has the same person menu, without the group's choices
  if (!ch || (ch.type !== "group_dm" && ch.type !== "dm") || !me) return null;
  const users = useUsers.getState().byId;
  const nicknames = friendNicknames(useRelationships.getState().byUser);
  const self = userId === me.id;
  return {
    ch,
    me,
    self,
    user: users[userId],
    name: nicknames[userId] || displayName(users[userId], self ? me.display_name : t("chat.groupMemberMenu.unknownUser")),
    group: channelDisplayName(ch, users, me.id, nicknames),
    manage: managesGroup(ch),
    isOwner: ch.owner_id === userId,
    owner: ch.owner_id ? nicknames[ch.owner_id] || displayName(users[ch.owner_id], t("chat.groupMemberMenu.itsCurrentOwner")) : t("chat.groupMemberMenu.itsCurrentOwner"),
  };
}

/**
 * Context menu for someone in a group's member list, or shown in a group or
 * one-to-one conversation (personMenu): profile, mention and message for
 * everyone, and for whoever manages the group (its owner or an Nexa admin)
 * handing ownership on and removing people. The owner outranks a Nexa
 * admin, so is never offered for removal.
 */
export const groupMemberMenu = defineMenu<GroupMemberMenuRowCtx>("groupMember", [
  [
    button("viewGroupMemberProfile", ({ channelId, userId, openModal }) => !!about(channelId, userId) && { id: "profile", label: t("profile.profile.profile"), onSelect: () => openModal("profile", { userId }) }),
    button("mentionGroupMember", ({ channelId, userId }) => {
      const a = about(channelId, userId);
      return a && { id: "mention", label: t("settings.notifications.mention"), disabled: !a.user?.username, onSelect: () => window.dispatchEvent(new CustomEvent("ec:insert-text", { detail: `@${a.user?.username} ` })) };
    }),
    button("messageGroupMember", ({ channelId, userId, nav }) => {
      const a = about(channelId, userId);
      return !!a && !a.self && { id: "message", label: t("profile.profileCard.message"), onSelect: () => void messageUser(userId, nav) };
    }),
  ],
  // what apps can do with someone, used in this group
  [button("groupMemberApps", ({ channelId, userId }) => !!about(channelId, userId) && userAppsItem(channelId, userId))],
  // being friends, and blocking: the same choices a profile offers
  relationshipButtons<GroupMemberMenuRowCtx>({ friend: "groupMemberFriendship", block: "blockGroupMember" }, ({ channelId, userId }) => (about(channelId, userId) ? userId : null)),
  [
    button("transferGroupOwnership", ({ channelId, userId, openModal }) => {
      const a = about(channelId, userId);
      if (!a || !a.manage || a.isOwner) return null;
      const { self, group, owner, name, ch, me } = a;
      return {
        id: "owner",
        label: self ? t("chat.groupMemberMenu.takeOwnership") : t("chat.groupMemberMenu.transferOwnership"),
        onSelect: () =>
          openModal("confirm", {
            title: self ? t("chat.groupMemberMenu.takeOwnershipOf", { group }) : t("chat.groupMemberMenu.transferOwnershipOf", { group }),
            body: self
              ? personText("chat.groupMemberMenu.youBecomeTheOwnerInPlaceOf", null, null, { owner: personNode(ch.owner_id, owner) })
              : ch.owner_id === me.id
                ? personText("chat.groupMemberMenu.theyBecomeTheOwnerAndYouAMember", userId, name)
                : personText("chat.groupMemberMenu.theyBecomeTheOwnerInPlaceOf", userId, name, { owner: personNode(ch.owner_id, owner) }),
            confirmLabel: self ? t("chat.groupMemberMenu.takeOwnership") : t("chat.groupMemberMenu.transferOwnership"),
            onConfirm: () => channelsApi.patch(channelId, { owner_id: userId }),
          }),
      };
    }),
    button("removeGroupMember", ({ channelId, userId, openModal }) => {
      const a = about(channelId, userId);
      if (!a || !a.manage || a.self || a.isOwner) return null;
      return {
        id: "remove",
        label: t("chat.groupMemberMenu.removeFromGroup"),
        danger: true,
        onSelect: () =>
          openModal("confirm", {
            title: personText("chat.groupMemberMenu.removeNamed", userId, a.name),
            body: personText("chat.groupMemberMenu.theyAreRemovedFromTheGroup", userId, a.name, { group: a.group }),
            confirmLabel: t("composer.composer.remove"),
            danger: true,
            onConfirm: () => channelsApi.removeRecipient(channelId, userId),
          }),
      };
    }),
    button("leaveGroupFromMembers", ({ channelId, userId, nav, openModal }) => {
      const a = about(channelId, userId);
      if (!a?.self || a.ch.type !== "group_dm") return null;
      return {
        id: "leave",
        label: t("chat.groupMemberMenu.leaveGroup"),
        danger: true,
        onSelect: () =>
          openModal("confirm", {
            title: t("sidebar.dmMenu.leaveNamed", { name: a.group }),
            body: a.isOwner ? t("chat.groupMemberMenu.youStopReceivingMessagesFromThis") : t("sidebar.dmMenu.youStopReceivingMessagesFromThis"),
            confirmLabel: t("chat.groupMemberMenu.leaveGroup"),
            danger: true,
            checkboxLabel: t("chat.leaveSilently"),
            onConfirm: (silent: boolean) => closeDM(channelId, nav, silent),
          }),
      };
    }),
  ],
  [
    button("copyGroupMemberProfileLink", ({ channelId, userId }) => !!about(channelId, userId) && profileLinkItem(userId)),
    button("copyGroupMemberId", ({ channelId, userId }) => !!about(channelId, userId) && useSettings.getState().settings.developerMode && { id: "copy", label: t("chat.groupMemberMenu.copyUserId"), onSelect: () => void copyText(userId, t("clipboard.idCopied")) }),
    button("copyGroupMemberBotId", ({ channelId, userId }) => !!about(channelId, userId) && useSettings.getState().settings.developerMode && copyBotIdItem(userId)),
  ],
  [button("selectGroupMembers", ({ channelId, userId, select }) => !!select && !!about(channelId, userId) && { id: "select", label: t("pick.select"), onSelect: () => usePick.getState().start(select, userId) })],
]);
menuAbout(groupMemberMenu, ["user", "channel"], ({ channelId, userId }) => ({ channelId, userId }));

