import { reportItem } from "@/lib/reporting";
import { ApiError } from "@/api/client";
import { servers as serversApi } from "@/api/servers";
import { billing as billingApi } from "@/api/billing";
import { myPlan, plansInfo } from "@/lib/plans";
import { formatBytes } from "@/lib/time";
import { P } from "@/lib/perms";
import { useAuth } from "@/stores/auth";
import { channelTree, selectServerChannels, useChannels } from "@/stores/channels";
import type { Channel } from "@/api/types";
import { useReadStates } from "@/stores/readStates";
import { canI, useServers } from "@/stores/servers";
import { useSettings } from "@/stores/settings";
import { useUI, type ContextMenuItem } from "@/stores/ui";
import { copyAndSay } from "@/lib/copyText";
import { openInTabButton } from "./openMenu";
import { leaveServer, markServerRead, toggleMuted } from "./serverActions";
import { about, button, defineMenu } from "@/lib/contextMenus/registry";
import { serverArrangeButtons, type RailServerCtx } from "@/components/rail/railActions";
import { usePick } from "@/stores/pick";
import { canWearTag } from "@/lib/serverTags";
import { wearServerTag } from "@/components/settings/ServerTagChooser";

import { t } from "@/i18n";
export interface MenuCtx {
  nav: (path: string) => void;
  openModal: (kind: string, props?: Record<string, unknown>) => void;
}

const ANY_MANAGE = P.MANAGE_SERVER | P.MANAGE_ROLES | P.MANAGE_CHANNELS | P.VIEW_AUDIT_LOG | P.BAN_MEMBERS | P.KICK_MEMBERS;

function anyOf(p: bigint, bits: bigint): boolean {
  return (p & bits) !== 0n;
}

function copy(text: string): void {
  copyAndSay(text);
}

export interface ServerMenuCtx extends MenuCtx, RailServerCtx {}

const serverOf = (id: string) => useServers.getState().byId[id];

async function boostServer(serverId: string, name: string): Promise<void> {
  const ui = useUI.getState();
  try {
    await billingApi.boost(serverId);
    ui.toast(t("support.boosts.boosted", { server: name, size: formatBytes(plansInfo().boost_file_max) }), "success");
  } catch (err) {
    ui.toast(err instanceof ApiError ? err.message : t("support.error"), "error");
  }
}

const leaveButton = button<ServerMenuCtx>("leaveServer", ({ serverId, nav, openModal }) => {
  const st = serverOf(serverId);
  if (!st || st.server.owner_id === useAuth.getState().me?.id) return null;
  return {
    id: "leave",
    label: t("sidebar.serverMenu.leaveServer"),
    danger: true,
    onSelect: () =>
      openModal("confirm", {
        title: t("sidebar.serverMenu.leaveNamed", { name: st.server.name }),
        body: t("sidebar.serverMenu.areYouSureYouWantToLeave", { name: st.server.name }),
        confirmLabel: t("sidebar.serverMenu.leaveServer"),
        danger: true,
        onConfirm: () => leaveServer(serverId, nav),
      }),
  };
});

/** A server's menu, from its header or the rail; the rail adds arranging it and picking more. */
export const serverMenu = defineMenu<ServerMenuCtx>("server", [
  [button("reportServerContent", ({ serverId }) => reportItem({ kind: "server", id: serverId }))],
  [button("markServerRead", ({ serverId }) => !!serverOf(serverId) && { id: "read", label: t("sidebar.dmMenu.markAsRead"), onSelect: () => markServerRead(serverId) })],
  // arranging it comes straight after Mark as read, which stays first, and then picking more
  serverArrangeButtons,
  [button("selectServers", ({ serverId, rail }) => !!rail && !!serverOf(serverId) && { id: "select", label: t("pick.select"), onSelect: () => usePick.getState().start("servers", serverId) })],
  [
    // offered only where there is a channel to invite into: without one the
    // dialog opened on an empty list and a blank link
    button("inviteToServer", ({ serverId, openModal }) => {
      const st = serverOf(serverId);
      const invitable = !!st && selectServerChannels(useChannels.getState().byId, serverId).some((c) => c.type === "text" && canI(st, P.CREATE_INSTANT_INVITE, c.id));
      return invitable && { id: "invite", label: t("chat.chat.invitePeople"), onSelect: () => openModal("invite", { serverId }) };
    }),
    button("serverSettings", ({ serverId, openModal }) => {
      const st = serverOf(serverId);
      return !!st && anyOf(st.myPermissions, ANY_MANAGE) && { id: "settings", label: t("server.serverSettings.serverSettings"), onSelect: () => openModal("serverSettings", { serverId }) };
    }),
    button("createChannel", ({ serverId, openModal }) => canI(serverOf(serverId), P.MANAGE_CHANNELS) && { id: "channel", label: t("modals.createChannel.createChannel"), onSelect: () => openModal("createChannel", { serverId }) }),
    button("createCategory", ({ serverId, openModal }) => canI(serverOf(serverId), P.MANAGE_CHANNELS) && { id: "category", label: t("modals.createCategory.createCategory"), onSelect: () => openModal("createCategory", { serverId }) }),
    // Pro's boosts, one at a time; the Support page lists them and takes them back
    button("boostServer", ({ serverId }) => {
      const st = serverOf(serverId);
      return !!st && myPlan() === "pro" && { id: "boost", label: t("support.boosts.boostServer"), onSelect: () => void boostServer(serverId, st.server.name) };
    }),
  ],
  [
    button("muteServer", ({ serverId }) => {
      if (!serverOf(serverId)) return null;
      const muted = useSettings.getState().settings.notifications.muted.includes(serverId);
      return { id: "mute", label: muted ? t("sidebar.serverMenu.unmuteServer") : t("sidebar.serverMenu.muteServer"), onSelect: () => toggleMuted(serverId) };
    }),
    button("serverNotifications", ({ serverId, openModal }) => !!serverOf(serverId) && { id: "notif", label: t("modals.serverNotifications.notificationSettings"), onSelect: () => openModal("serverNotifications", { serverId }) }),
  ],
  [
    button("editServerProfile", ({ serverId, openModal }) => !!serverOf(serverId) && { id: "profile", label: t("sidebar.serverMenu.editServerProfile"), onSelect: () => openModal("settings", { page: "profiles", serverId }) }),
    // its tag, beside your name everywhere: on with one press, and off again
    button("wearServerTag", ({ serverId }) => {
      const st = serverOf(serverId);
      const tag = st?.server.tag;
      if (!st || !tag) return null;
      if (useAuth.getState().me?.server_tag?.server_id === serverId) return { id: "tagOff", label: t("serverTag.menu.takeOff"), onSelect: () => void wearServerTag(null) };
      return canWearTag(st) && { id: "tagOn", label: t("serverTag.menu.wear", { tag: tag.text }), onSelect: () => void wearServerTag(serverId, tag.text) };
    }),
    leaveButton,
    button("copyServerId", ({ serverId }) => !!serverOf(serverId) && useSettings.getState().settings.developerMode && { id: "copy", label: t("sidebar.serverMenu.copyServerId"), onSelect: () => copy(serverId) }),
  ],
]);
about(serverMenu, ["server"], ({ serverId }) => ({ serverId }));


/** The list a channel is ordered within: its category's channels, the loose ones, or the categories themselves. */
function siblingsOf(ch: Channel, byId: Record<string, Channel>): Channel[] {
  const tree = channelTree(byId, ch.server_id ?? "");
  if (ch.type === "category") return tree.categories.map((c) => c.category);
  const inCategory = ch.parent_id ? tree.categories.find((c) => c.category.id === ch.parent_id) : undefined;
  return inCategory ? inCategory.children : tree.loose;
}

/**
 * Moves a channel (or a category) one place up or down among its siblings,
 * as dropping it there would. Dragging was the only way before, and a touch
 * screen and a keyboard cannot drag.
 */
async function moveChannel(channelId: string, delta: -1 | 1): Promise<void> {
  const byId = useChannels.getState().byId;
  const ch = byId[channelId];
  if (!ch?.server_id) return;
  const serverId = ch.server_id;
  const list = [...siblingsOf(ch, byId)];
  const at = list.findIndex((c) => c.id === channelId);
  const to = at + delta;
  if (at < 0 || to < 0 || to >= list.length) return;
  list.splice(at, 1);
  list.splice(to, 0, ch);
  const positions = list.map((c, i) => ({ id: c.id, position: i }));
  useChannels.getState().applyPositions(positions.map((p) => ({ ...p, parent_id: byId[p.id]?.parent_id ?? null })));
  try {
    useChannels.getState().upsert(await serversApi.setChannelPositions(serverId, positions));
  } catch (err) {
    useUI.getState().toast(err instanceof ApiError ? err.message : t("sidebar.serverSidebar.couldNotReorderChannels"), "error");
    // put back what the server has
    await serversApi.channels(serverId).then((all) => useChannels.getState().upsert(all), () => undefined);
  }
}

/** Move up and Move down, where there is somewhere to move to. */
function moveItems(ch: Channel): ContextMenuItem[] {
  const list = siblingsOf(ch, useChannels.getState().byId);
  const at = list.findIndex((c) => c.id === ch.id);
  const items: ContextMenuItem[] = [];
  if (at > 0) items.push({ id: "move-up", label: t("reorder.moveUp"), onSelect: () => void moveChannel(ch.id, -1) });
  if (at >= 0 && at < list.length - 1) items.push({ id: "move-down", label: t("reorder.moveDown"), onSelect: () => void moveChannel(ch.id, 1) });
  return items;
}

/** A server channel, or null for one that is gone or not in a server. */
function serverChannel(id: string): (Channel & { server_id: string }) | null {
  const ch = useChannels.getState().byId[id];
  return ch?.server_id ? (ch as Channel & { server_id: string }) : null;
}

const canManage = (ch: Channel & { server_id: string }, channelId?: string) => canI(useServers.getState().byId[ch.server_id], P.MANAGE_CHANNELS, channelId);

/** Deleting a channel or a category, after asking. */
function deleteItem(ch: Channel, label: string, body: string): ContextMenuItem {
  return {
    id: "delete",
    label,
    danger: true,
    onSelect: () =>
      useUI.getState().openModal("confirm", {
        title: label,
        body,
        confirmLabel: label,
        danger: true,
        onConfirm: async () => {
          await serversApi.deleteChannel(ch.id);
          useChannels.getState().remove(ch.id);
        },
      }),
  };
}

export interface ChannelMenuCtx extends MenuCtx {
  channelId: string;
}

/** A server channel's menu in the channel list. */
export const channelMenu = defineMenu<ChannelMenuCtx>("channel", [
  [button("reportChannelContent", ({ channelId }) => reportItem({ kind: "channel", id: channelId }))],
  // a voice channel has no chat to open beside another one
  [openInTabButton<ChannelMenuCtx>("openChannelInTab", ({ channelId }) => {
    const ch = serverChannel(channelId);
    return ch && ch.type !== "voice" && ch.type !== "link" ? { serverId: ch.server_id, channelId } : null;
  })],
  [button("markChannelRead", ({ channelId }) => {
    const ch = serverChannel(channelId);
    return ch?.type === "text" && {
      id: "read",
      label: t("sidebar.dmMenu.markAsRead"),
      disabled: !ch.last_message_id,
      onSelect: () => ch.last_message_id && void useReadStates.getState().ack(ch.id, ch.last_message_id),
    };
  })],
  [
    button("inviteToChannel", ({ channelId, openModal }) => {
      const ch = serverChannel(channelId);
      return !!ch && ch.type !== "link" && canI(useServers.getState().byId[ch.server_id], P.CREATE_INSTANT_INVITE, channelId) && { id: "invite", label: t("chat.chat.invitePeople"), onSelect: () => openModal("invite", { serverId: ch.server_id, channelId }) };
    }),
    // a voice channel's call takes guests without an account, through a link
    button("inviteGuestsToCall", ({ channelId, openModal }) => {
      const ch = serverChannel(channelId);
      return ch?.type === "voice" && canI(useServers.getState().byId[ch.server_id], P.CREATE_INSTANT_INVITE | P.CONNECT, channelId) && { id: "guests", label: t("chat.chat.inviteGuestsToCall"), onSelect: () => openModal("guestLink", { channelId }) };
    }),
    button("copyChannelLink", ({ channelId }) => {
      const ch = serverChannel(channelId);
      return ch && ch.type !== "link" && { id: "link", label: t("sidebar.serverMenu.copyLink"), onSelect: () => copy(`${window.location.origin}/channels/${ch.server_id}/${ch.id}`) };
    }),
    // a link channel's own link is where it leads
    button("copyLinkChannelAddress", ({ channelId }) => {
      const ch = serverChannel(channelId);
      return ch?.type === "link" && !!ch.url && { id: "address", label: t("channelLink.copyAddress"), onSelect: () => copy(ch.url!) };
    }),
  ],
  [button("muteChannel", ({ channelId }) => {
    const ch = serverChannel(channelId);
    if (ch?.type !== "text") return null;
    const muted = useSettings.getState().settings.notifications.muted.includes(channelId);
    return { id: "mute", label: muted ? t("sidebar.serverMenu.unmuteChannel") : t("sidebar.serverMenu.muteChannel"), onSelect: () => toggleMuted(channelId) };
  })],
  [button("moveChannel", ({ channelId }) => {
    const ch = serverChannel(channelId);
    return !!ch && canManage(ch, channelId) && canManage(ch) && moveItems(ch);
  })],
  [
    button("editChannel", ({ channelId, openModal }) => {
      const ch = serverChannel(channelId);
      return !!ch && canManage(ch, channelId) && { id: "edit", label: t("sidebar.serverSidebar.editChannel"), onSelect: () => openModal("channelSettings", { channelId }) };
    }),
    button("deleteChannel", ({ channelId }) => {
      const ch = serverChannel(channelId);
      return !!ch && canManage(ch, channelId) && deleteItem(ch, t("sidebar.serverMenu.deleteChannel"), t("sidebar.serverMenu.areYouSureYouWantToDeleteChannel", { name: ch.name }));
    }),
    button("copyChannelId", ({ channelId }) => !!serverChannel(channelId) && useSettings.getState().settings.developerMode && { id: "copy", label: t("sidebar.dmMenu.copyChannelId"), onSelect: () => copy(channelId) }),
  ],
]);
about(channelMenu, ["channel", "server"], ({ channelId }) => ({ channelId }));


export interface CategoryMenuCtx extends MenuCtx {
  categoryId: string;
  collapsed: boolean;
  toggle: () => void;
}

/** A category's menu in the channel list. */
export const categoryMenu = defineMenu<CategoryMenuCtx>("category", [
  [button("reportCategoryContent", ({ categoryId }) => reportItem({ kind: "channel", id: categoryId }))],
  [button("collapseCategory", ({ categoryId, collapsed, toggle }) => !!serverChannel(categoryId) && { id: "collapse", label: collapsed ? t("sidebar.serverMenu.expandCategory") : t("sidebar.serverMenu.collapseCategory"), onSelect: toggle })],
  [button("moveCategory", ({ categoryId }) => {
    const ch = serverChannel(categoryId);
    return !!ch && canManage(ch) && moveItems(ch);
  })],
  [
    button("createChannelInCategory", ({ categoryId, openModal }) => {
      const ch = serverChannel(categoryId);
      return !!ch && canManage(ch) && { id: "create", label: t("modals.createChannel.createChannel"), onSelect: () => openModal("createChannel", { serverId: ch.server_id, parentId: categoryId }) };
    }),
    button("editCategory", ({ categoryId, openModal }) => {
      const ch = serverChannel(categoryId);
      return !!ch && canManage(ch) && { id: "edit", label: t("sidebar.serverMenu.editCategory"), onSelect: () => openModal("channelSettings", { channelId: categoryId }) };
    }),
    button("deleteCategory", ({ categoryId }) => {
      const ch = serverChannel(categoryId);
      return !!ch && canManage(ch) && deleteItem(ch, t("sidebar.serverMenu.deleteCategory"), t("sidebar.serverMenu.deleteTheCategoryNamed", { name: ch.name }));
    }),
    button("copyCategoryId", ({ categoryId }) => !!serverChannel(categoryId) && useSettings.getState().settings.developerMode && { id: "copy", label: t("sidebar.serverMenu.copyCategoryId"), onSelect: () => copy(categoryId) }),
  ],
]);
about(categoryMenu, ["channel", "server"], ({ categoryId }) => ({ channelId: categoryId }));

