import { reportItem } from "@/lib/reporting";
import { personText } from "@/lib/personText";
import { ApiError } from "@/api/client";
import { channels as channelsApi } from "@/api/channels";
import type { Channel } from "@/api/types";
import { copyBotIdItem, copyIdItem, developerMode } from "@/lib/copyId";
import { about, button, defineMenu } from "@/lib/contextMenus/registry";
import { channelDisplayName, friendNicknames } from "@/lib/names";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useMessages } from "@/stores/messages";
import { isUnread, useReadStates } from "@/stores/readStates";
import { useRelationships } from "@/stores/relationships";
import { useSettings } from "@/stores/settings";
import { useUI, type ContextMenuItem } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { usePick } from "@/stores/pick";
import { joinVoice } from "@/voice/session";
import { openInTabButton } from "./openMenu";
import { dmArrangeButtons, forgetConversation } from "./dmArrange";

import { t } from "@/i18n";
import { profileLinkItem } from "@/lib/profileLinks";
function nameOf(ch: Channel): string {
  return channelDisplayName(ch, useUsers.getState().byId, useAuth.getState().me?.id, friendNicknames(useRelationships.getState().byUser));
}

function forget(channelId: string): void {
  useChannels.getState().remove(channelId);
  useMessages.getState().clearChannel(channelId);
}

/**
 * Whether the page at `pathname` shows this conversation or one of its
 * subchats, so closing the conversation has to take the user somewhere else.
 */
export function isViewingDM(pathname: string, channelId: string, byId: Record<string, Channel | undefined>): boolean {
  const open = pathname.match(/^\/channels\/@me\/(\d+)/)?.[1];
  if (!open) return false;
  return open === channelId || byId[open]?.parent_id === channelId;
}

/** Sends the user to Friends only when they are reading the conversation that went away. */
function leave(channelId: string, nav?: (p: string) => void): void {
  if (nav && isViewingDM(window.location.pathname, channelId, useChannels.getState().byId)) nav("/channels/@me");
}

/** Closes a conversation for you; a new message brings it back, to the main list. */
export async function closeDM(channelId: string, nav?: (p: string) => void, silent = false): Promise<void> {
  try {
    await channelsApi.close(channelId, silent);
    leave(channelId, nav);
    forget(channelId);
    forgetConversation(channelId);
  } catch (err) {
    useUI.getState().toast(err instanceof ApiError ? err.message : t("sidebar.dmMenu.couldNotCloseThatConversation"), "error");
  }
}

/** Deletes a conversation for everyone in it (or a group you own). */
export async function purgeDM(channelId: string, nav?: (p: string) => void): Promise<void> {
  try {
    await channelsApi.purge(channelId);
    leave(channelId, nav);
    forget(channelId);
    forgetConversation(channelId);
  } catch (err) {
    useUI.getState().toast(err instanceof ApiError ? err.message : t("sidebar.dmMenu.couldNotDeleteThatConversation"), "error");
  }
}

/** Whether you may do what a group's owner does: you own it. */
export function managesGroup(ch: Channel | undefined): boolean {
  const me = useAuth.getState().me;
  if (!ch || ch.type !== "group_dm" || !me) return false;
  return ch.owner_id === me.id;
}

export interface DMMenuCtx {
  nav: (path: string) => void;
  openModal: (kind: string, props?: Record<string, unknown>) => void;
  /** Move up and Move down, for a pinned conversation: the list offers them where it can move them. */
  moves?: ContextMenuItem[];
  /** Offer picking more conversations, as the DM list does: the way into picking on a touch screen, where there is no Ctrl to hold. */
  select?: boolean;
}

export interface DMMenuRowCtx extends DMMenuCtx {
  channelId: string;
}

const dmOf = (id: string) => useChannels.getState().byId[id];
/** The other person in a one-to-one conversation; undefined in a group. */
const otherIn = (ch: Channel) => (ch.type === "group_dm" ? undefined : ch.recipient_ids.find((id) => id !== useAuth.getState().me?.id));

/** Context menu for a DM or group row in the sidebar, the rail and wherever else one is listed. */
export const dmMenu = defineMenu<DMMenuRowCtx>("dm", [
  [button("reportConversationContent", ({ channelId }) => reportItem({ kind: "channel", id: channelId }))],
  [openInTabButton<DMMenuRowCtx>("openDmInTab", ({ channelId }) => (dmOf(channelId) ? { serverId: null, channelId } : null))],
  [button("markDmRead", ({ channelId }) => {
    const ch = dmOf(channelId);
    if (!ch) return null;
    const reads = useReadStates.getState();
    return {
      id: "read",
      label: t("sidebar.dmMenu.markAsRead"),
      disabled: !ch.last_message_id || !isUnread(reads.byChannel[channelId], ch.last_message_id),
      onSelect: () => ch.last_message_id && void reads.ack(channelId, ch.last_message_id),
    };
  }),
  button("selectConversations", ({ channelId, select }) => !!select && !!dmOf(channelId) && { id: "select", label: t("pick.select"), onSelect: () => usePick.getState().start("dms", channelId) })],
  // pin it, group it, move it: arranging the list comes straight after reading
  dmArrangeButtons<DMMenuRowCtx>({ pin: "pinConversation", add: "addConversationToGroup", remove: "removeConversationFromGroup", move: "moveConversation" }, (c) => (dmOf(c.channelId) ? { id: c.channelId, moves: c.moves } : null)),
  [
    button("editGroup", ({ channelId, openModal }) => dmOf(channelId)?.type === "group_dm" && { id: "edit", label: t("modals.editGroup.editGroup"), onSelect: () => openModal("editGroup", { channelId }) }),
    button("inviteToGroup", ({ channelId, openModal }) => dmOf(channelId)?.type === "group_dm" && { id: "invite", label: t("chat.chat.invitePeople"), onSelect: () => openModal("groupInvite", { channelId }) }),
    button("addFriendsToGroup", ({ channelId, openModal }) => dmOf(channelId)?.type === "group_dm" && { id: "add", label: t("sidebar.dmMenu.addFriends"), onSelect: () => openModal("createDM", { groupId: channelId }) }),
    button("viewDmProfile", ({ channelId, openModal }) => {
      const ch = dmOf(channelId);
      return !!ch && ch.type !== "group_dm" && { id: "profile", label: t("profile.profile.profile"), onSelect: () => { const other = otherIn(ch); if (other) openModal("profile", { userId: other }); } };
    }),
    button("copyDmProfileLink", ({ channelId }) => {
      const ch = dmOf(channelId);
      const other = ch && otherIn(ch);
      return other ? profileLinkItem(other) : null;
    }),
    button("startDmCall", ({ channelId, nav }) => {
      const ch = dmOf(channelId);
      return !!ch && ch.type !== "group_dm" && {
        id: "call",
        label: t("sidebar.dmMenu.startCall"),
        onSelect: () => {
          nav(`/channels/@me/${channelId}`);
          void joinVoice(channelId);
        },
      };
    }),
    button("muteConversation", ({ channelId }) => {
      if (!dmOf(channelId)) return null;
      const settings = useSettings.getState().settings;
      const muted = settings.notifications.muted.includes(channelId);
      return {
        id: "mute",
        label: muted ? t("sidebar.dmMenu.unmuteConversation") : t("sidebar.dmMenu.muteConversation"),
        onSelect: () => {
          const list = settings.notifications.muted;
          useSettings.getState().patch({ notifications: { ...settings.notifications, muted: muted ? list.filter((x) => x !== channelId) : [...list, channelId] } });
        },
      };
    }),
  ],
  [
    button("closeConversation", ({ channelId, nav, openModal }) => {
      const ch = dmOf(channelId);
      if (!ch) return null;
      const group = ch.type === "group_dm";
      return {
        id: "close",
        label: group ? t("chat.groupMemberMenu.leaveGroup") : t("sidebar.dMSidebar.closeDm"),
        danger: group,
        onSelect: () =>
          group
            ? openModal("confirm", {
                title: t("sidebar.dmMenu.leaveNamed", { name: nameOf(ch) }),
                body: t("sidebar.dmMenu.youStopReceivingMessagesFromThis"),
                confirmLabel: t("chat.groupMemberMenu.leaveGroup"),
                danger: true,
                checkboxLabel: t("chat.leaveSilently"),
                onConfirm: (silent: boolean) => closeDM(channelId, nav, silent),
              })
            : void closeDM(channelId, nav),
      };
    }),
    button("deleteConversation", ({ channelId, nav, openModal }) => {
      const ch = dmOf(channelId);
      if (!ch) return null;
      const group = ch.type === "group_dm";
      if (group && !managesGroup(ch)) return null;
      const name = nameOf(ch);
      return {
        id: "purge",
        label: group ? t("sidebar.dmMenu.deleteGroup") : t("sidebar.dmMenu.deleteConversation"),
        danger: true,
        onSelect: () =>
          openModal("confirm", {
            title: group ? t("sidebar.dmMenu.deleteNamed", { name }) : personText("sidebar.dmMenu.deleteYourConversationWith", ch.recipient_ids.find((id) => id !== useAuth.getState().me?.id), name),
            body: group ? t("sidebar.dmMenu.theGroupAndEveryMessageIn") : t("sidebar.dmMenu.everyMessageIsRemovedForBoth"),
            confirmLabel: group ? t("sidebar.dmMenu.deleteGroup") : t("sidebar.dmMenu.deleteForEveryone"),
            danger: true,
            onConfirm: () => purgeDM(channelId, nav),
          }),
      };
    }),
  ],
  [
    // in a one-to-one conversation, what was right-clicked is the person
    button("copyDmUserId", ({ channelId }) => {
      const ch = dmOf(channelId);
      const other = ch && developerMode() && otherIn(ch);
      return other ? copyIdItem("copy-user", t("chat.groupMemberMenu.copyUserId"), other) : null;
    }),
    button("copyDmUserBotId", ({ channelId }) => {
      const ch = dmOf(channelId);
      const other = ch && developerMode() && otherIn(ch);
      return other ? copyBotIdItem(other) : null;
    }),
    button("copyDmChannelId", ({ channelId }) => !!dmOf(channelId) && developerMode() && copyIdItem("copy", t("sidebar.dmMenu.copyChannelId"), channelId)),
  ],
]);
// a one-to-one conversation is also about the person in it
about(dmMenu, ["channel", "user"], ({ channelId }) => {
  const ch = dmOf(channelId);
  return { channelId, userId: ch ? (otherIn(ch) ?? null) : null };
});

