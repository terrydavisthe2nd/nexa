import { downloadsHidden } from "@/lib/mediaDownloads";
import { reportItem, messageEvidence } from "@/lib/reporting";
import { canForward } from "@/lib/forward";
import { rankedKeys, unicodeKey } from "@/lib/emojiUsage";
import { channels as channelsApi } from "@/api/channels";
import { BULK_DELETE_MAX, messages as messagesApi } from "@/api/messages";
import { MSG_FLAG_EPHEMERAL, MSG_FLAG_PINNED, MSG_FLAG_SUPPRESS_EMBEDS } from "@/api/types";
import { humanizeMentions, type HumanNames } from "@/components/composer/mentions";
import { copyText } from "@/lib/clipboard";
import { compareIds } from "@/lib/ids";
import { copyImage, saveImage, type ImageTarget } from "@/lib/imageActions";
import { formatExact } from "@/lib/time";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useMessages, type DecryptedMessage } from "@/stores/messages";
import { useReadStates } from "@/stores/readStates";
import { useSelection } from "@/stores/selection";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { about, button, defineMenu, type MenuButton } from "@/lib/contextMenus/registry";
import { messageAppsItem } from "./apps/appMenus";
import { canI, P, useServers } from "@/stores/servers";
import { useUsers } from "@/stores/users";
import { mentionsMe } from "@/lib/mentionsMe";
import { canStartThread } from "@/lib/threads";
import { isThread } from "@/stores/threads";
import { canPublish, publish } from "./publish";

import { t } from "@/i18n";
export interface MenuHooks {
  openReactionPicker: () => void;
}

export function canDelete(msg: DecryptedMessage): boolean {
  const me = useAuth.getState().me;
  if (!me) return false;
  if (msg.author_id === me.id) return true;
  const ch = useChannels.getState().byId[msg.channel_id];
  if (!ch) return false;
  // Manage Messages lets moderators and the server owner remove anyone's message
  if (ch.server_id) return canI(useServers.getState().byId[ch.server_id], P.MANAGE_MESSAGES, ch.id);
  return ch.type === "group_dm" && ch.owner_id === me.id;
}

/** Whether this person may take a message's previews away: its author, or whoever may delete it. */
export function canRemoveEmbeds(msg: DecryptedMessage): boolean {
  return !msg.type.startsWith("system_") && canDelete(msg);
}

/** Takes a message's previews away, or brings them back. */
export function setEmbedsHidden(msg: DecryptedMessage, hidden: boolean): void {
  const flags = hidden ? msg.flags | MSG_FLAG_SUPPRESS_EMBEDS : msg.flags & ~MSG_FLAG_SUPPRESS_EMBEDS;
  void messagesApi
    .edit(msg.channel_id, msg.id, { flags: flags & MSG_FLAG_SUPPRESS_EMBEDS })
    .then((m) => useMessages.getState().setFlags(msg.channel_id, msg.id, m.flags))
    .catch(() => useUI.getState().toast(t("chat.messageMenu.couldNotUpdateEmbeds"), "error"));
}

/** Asks before removing a message's previews; Shift skips the question, as it does for Delete. */
export function confirmRemoveEmbeds(msg: DecryptedMessage, skip = false): void {
  if (skip) return setEmbedsHidden(msg, true);
  useUI.getState().openModal("confirm", {
    title: t("chat.embeds.removeTitle"),
    body: t("chat.embeds.removeBody"),
    confirmLabel: t("chat.embeds.removeConfirm"),
    danger: true,
    onConfirm: () => setEmbedsHidden(msg, true),
  });
}

/** Anyone in a DM may pin; a server channel needs Pin Messages, as the server requires. */
export function canPin(msg: DecryptedMessage): boolean {
  const ch = useChannels.getState().byId[msg.channel_id];
  if (!ch?.server_id) return true;
  return canI(useServers.getState().byId[ch.server_id], P.PIN_MESSAGES, ch.id);
}

/** Whether a thread can be started from a message: something someone said, in a channel threads start in, that has none yet. */
export function canThread(msg: DecryptedMessage): boolean {
  if (msg.thread || (msg.type !== "default" && msg.type !== "reply" && msg.type !== "crosspost")) return false;
  const ch = useChannels.getState().byId[msg.channel_id];
  return !!ch && !isThread(ch) && canStartThread(ch);
}

/**
 * Whether a new reaction may be added: anyone in a DM, and Add Reactions in a
 * server channel, as the server requires. Taking one's own reaction back is
 * always allowed.
 */
export function canReact(msg: DecryptedMessage): boolean {
  const ch = useChannels.getState().byId[msg.channel_id];
  if (!ch?.server_id) return true;
  return canI(useServers.getState().byId[ch.server_id], P.ADD_REACTIONS, ch.id);
}

/** When a message was sent, and edited, to the second: a phone has no tooltip to tell it. */
export function messageTimes(msg: DecryptedMessage): string[] {
  const out: string[] = [];
  if (Number.isFinite(msg.created_at)) out.push(t("chat.messageMenu.sentAt", { time: formatExact(msg.created_at) }));
  if (msg.edited_at && Number.isFinite(msg.edited_at)) out.push(t("chat.messageMenu.editedAt", { time: formatExact(msg.edited_at) }));
  return out;
}

export function canEdit(msg: DecryptedMessage): boolean {
  const me = useAuth.getState().me;
  // a poll's question and answers are what people voted on, so they stay
  return !!me && msg.author_id === me.id && (msg.type === "default" || msg.type === "reply") && !!msg.payload && !msg.payload.poll;
}

/** Copy Image and Save Image, for a picture a menu was opened on; `ids` names the two buttons in the menu they are in. */
export function imageButtons<C>(ids: [copy: string, save: string], image: (ctx: C) => ImageTarget | null | undefined): MenuButton<C>[] {
  // a browser's own refusal says nothing useful; the helpers' own errors do
  const failed = (err: unknown, fallback: string) => useUI.getState().toast(err instanceof Error && !(err instanceof DOMException) && err.message ? err.message : fallback, "error");
  return [
    // a picture shown from its small copy is copied and saved whole
    button(ids[0], (ctx) => {
      const img = image(ctx);
      return img && { id: "copy-image", label: t("chat.messageMenu.copyImage"), onSelect: () => void copyImage(img.full ? img.full() : img.src).then(() => useUI.getState().toast(t("chat.messageMenu.imageCopied"), "success"), (err: unknown) => failed(err, t("chat.messageMenu.couldNotCopyThePicture"))) };
    }),
    button(ids[1], (ctx) => {
      const img = image(ctx);
      return img && { id: "save-image", label: t("chat.messageMenu.saveImage"), onSelect: () => void saveImage(img.full ?? img.src, img.name).catch((err: unknown) => failed(err, t("chat.messageMenu.couldNotSaveThePicture"))) };
    }),
  ];
}

/** The menu for a picture on its own, as the lightbox shows it. */
export const imageMenu = defineMenu<ImageTarget>("image", [imageButtons(["copyImage", "saveImage"], (img) => img)]);

/** The emoji a phone's sheet offers to react with: your most used, then the usual few (the Android app's quickReactionKeys). */
export function quickReactions(): string[] {
  const starter = ["👍", "❤️", "😂", "😮", "😢", "🔥"];
  const used = rankedKeys(useSettings.getState().settings.emojiUsage, Date.now()).filter((k) => !k.startsWith("c:"));
  const out: string[] = [];
  for (const k of [...used, ...starter]) {
    // a counted key has no variation selector; react with the starter's own spelling when it is one of them
    const e = starter.find((x) => unicodeKey(x) === k) ?? k;
    if (!out.some((x) => unicodeKey(x) === unicodeKey(e))) out.push(e);
    if (out.length === 6) break;
  }
  return out;
}

export interface MessageMenuCtx {
  msg: DecryptedMessage;
  openReactionPicker: () => void;
  /** The picture the menu was opened on, whose own actions then come first. */
  image?: ImageTarget | null;
}

/** The menu for a message; opened on one of its pictures, the picture's own actions come first. */
export const messageMenu = defineMenu<MessageMenuCtx>("message", [
  // a media channel that hides downloads offers neither for its pictures
  imageButtons(["copyMessageImage", "saveMessageImage"], (c) => (downloadsHidden(c.msg.channel_id) ? null : c.image)),
  // where reacting is not allowed the item is not offered, rather than failing after the pick
  [button("addReaction", ({ msg, openReactionPicker }) => canReact(msg) && { id: "react", label: t("chat.messageItem.addReaction"), onSelect: openReactionPicker })],
  [
    button("editMessage", ({ msg }) => canEdit(msg) && { id: "edit", label: t("chat.messageItem.editMessage"), onSelect: () => useUI.getState().setEditing({ channelId: msg.channel_id, messageId: msg.id }) }),
    button("replyToMessage", ({ msg }) => ({ id: "reply", label: t("chat.messageItem.reply"), onSelect: () => useUI.getState().setReplyTo({ channelId: msg.channel_id, messageId: msg.id, ping: true }) })),
    button("forwardMessage", ({ msg }) => canForward(msg) && { id: "forward", label: t("forward.menu"), onSelect: () => useUI.getState().openModal("forward", { msg }) }),
    button("createThreadFromMessage", ({ msg }) => canThread(msg) && { id: "thread", label: t("threads.create.title"), onSelect: () => useUI.getState().openModal("createThread", { channelId: msg.channel_id, messageId: msg.id }) }),
    button("publishMessage", ({ msg }) => canPublish(msg) && { id: "publish", label: t("follows.publish"), onSelect: () => void publish(msg) }),
  ],
  [
    button("copyMessageText", ({ msg }) => ({
      id: "copy",
      label: t("chat.messageMenu.copyText"),
      disabled: !(msg.payload?.t || msg.payload?.fwd?.comment),
      onSelect: () => void copyText(humanizeMentions([msg.payload?.fwd?.comment, msg.payload?.t].filter(Boolean).join("\n"), useUsers.getState().byId, knownNames()), t("clipboard.textCopied")),
    })),
    button("pinMessage", ({ msg }) => {
      if (!canPin(msg)) return null;
      const pinned = (msg.flags & MSG_FLAG_PINNED) !== 0;
      return {
        id: "pin",
        label: pinned ? t("chat.messageItem.unpinMessage") : t("chat.messageItem.pinMessage"),
        onSelect: () => {
          const p = pinned ? channelsApi.unpin(msg.channel_id, msg.id) : channelsApi.pin(msg.channel_id, msg.id);
          p.catch((err: unknown) => useUI.getState().toast(err instanceof Error ? err.message : t("chat.messageMenu.couldNotUpdateThePin"), "error"));
        },
      };
    }),
    button("markUnread", ({ msg }) => ({ id: "unread", label: t("chat.messageItem.markUnread"), onSelect: () => markUnread(msg) })),
    button("toggleEmbeds", ({ msg }) => {
      if (!(msg.payload?.embeds?.length || msg.hook?.link_embeds.length) || !canRemoveEmbeds(msg)) return null;
      const suppressed = (msg.flags & MSG_FLAG_SUPPRESS_EMBEDS) !== 0;
      return { id: "embeds", label: suppressed ? t("chat.messageMenu.showEmbeds") : t("chat.messageMenu.removeEmbeds"), onSelect: () => setEmbedsHidden(msg, !suppressed) };
    }),
    button("copyMessageLink", ({ msg }) => ({ id: "link", label: t("chat.messageItem.copyMessageLink"), onSelect: () => copyMessageLink(msg) })),
  ],
  // what apps can do with a message, their message commands
  [button("reportMessageContent", ({ msg }) => !msg.local && reportItem({ kind: "message", id: msg.id, evidence: messageEvidence(msg) }))],
  [button("messageApps", ({ msg }) => messageAppsItem(msg))],
  [button("copyMessageId", ({ msg }) => useSettings.getState().settings.developerMode && { id: "id", label: t("chat.messageItem.copyMessageId"), onSelect: () => void copyText(msg.id, t("clipboard.idCopied")) })],
  [
    button("selectMessages", ({ msg }) => canDelete(msg) && { id: "select", label: t("chat.messageMenu.selectMessages"), onSelect: () => startSelecting(msg) }),
    button("deleteMessage", ({ msg }) => canDelete(msg) && { id: "delete", label: t("chat.messageMenu.deleteMessage"), danger: true, onSelect: () => confirmDelete(msg) }),
  ],
  // the exact moment, which a phone has no tooltip for, closes the menu
  [button("messageTimes", ({ msg }) => messageTimes(msg).map((line, i) => ({ id: `time${i}`, label: line, note: true })))],
]);
about(messageMenu, ["message", "user", "channel", "server"], ({ msg }) => ({ message: msg, userId: msg.author_id, channelId: msg.channel_id }));


/** The menu of a system line that can be taken away (someone joining). */
export const systemMessageMenu = defineMenu<DecryptedMessage>("systemMessage", [
  [button("deleteSystemMessage", (msg) => msg.type === "system_join" && canDelete(msg) && { id: "delete", label: t("chat.messageMenu.deleteMessage"), danger: true, onSelect: () => confirmDelete(msg) })],
]);
about(systemMessageMenu, ["message", "user", "channel", "server"], (msg) => ({ message: msg, userId: msg.author_id, channelId: msg.channel_id }));


/** Asks before deleting a message, as the menu's Delete does; `deleted` runs once it is gone. */
export function confirmDelete(msg: DecryptedMessage, deleted?: () => void): void {
  const ui = useUI.getState();
  ui.openModal("confirm", {
    title: t("chat.messageMenu.deleteMessage"),
    body: t("chat.messageMenu.areYouSureYouWantTo"),
    confirmLabel: t("chat.messageItem.delete"),
    danger: true,
    onConfirm: () =>
      messagesApi
        .remove(msg.channel_id, msg.id)
        .then(() => deleted?.())
        .catch((err: unknown) => ui.toast(err instanceof Error ? err.message : t("chat.messageMenu.couldNotDelete"), "error")),
  });
}

/** Every role and channel name this device knows, so copied text reads the way the message does. */
function knownNames(): HumanNames {
  const roles: Record<string, string> = {};
  for (const st of Object.values(useServers.getState().byId)) for (const r of Object.values(st.roles)) roles[r.id] = r.name;
  const channels: Record<string, string> = {};
  for (const c of Object.values(useChannels.getState().byId)) if (c.server_id && c.name) channels[c.id] = c.name;
  return { roles, channels, emoji: () => true };
}

async function fetchForcedAck(channelId: string, prev: string): Promise<void> {
  // The regular ack never moves backwards; "mark unread" needs the manual flag.
  const { api } = await import("@/api/client");
  await api.request("POST", `/channels/${channelId}/messages/${prev === "0" ? "0" : prev}/ack`, { manual: true }).catch(() => undefined);
}

export function sortIds(a: string, b: string): number {
  return compareIds(a, b);
}

/** Marks the channel unread from this message down. */
export function markUnread(msg: DecryptedMessage): void {
  const ch = useMessages.getState().channels[msg.channel_id];
  const ids = ch?.ids ?? [];
  const idx = ids.indexOf(msg.id);
  const prev = idx > 0 ? ids[idx - 1]! : "0";
  // What is being made unread may hold mentions; zeroing the count left the
  // channel unread with no badge to say the reader is wanted in it.
  const meId = useAuth.getState().me?.id;
  let mentions = 0;
  if (meId && idx >= 0) {
    for (const id of ids.slice(idx)) {
      const m = ch?.byId[id];
      if (m && m.author_id !== meId && mentionsMe(m, meId)) mentions += 1;
    }
  }
  useReadStates.getState().set({ channel_id: msg.channel_id, last_read_message_id: prev === "0" ? null : prev, last_ack_at: Date.now(), mention_count: mentions, manual: true });
  void (async () => {
    // The two acks say opposite things, and nothing used to sequence them: the
    // forced one is what Mark Unread means, so it lands last. At the top of a
    // loaded page there is no earlier message to read up to, and the plain ack
    // there would have marked this very message read.
    if (prev !== "0") await messagesApi.ack(msg.channel_id, prev).catch(() => undefined);
    await fetchForcedAck(msg.channel_id, prev);
  })();
}

/** Copies a permalink that opens the message in its channel. */
export function copyMessageLink(msg: DecryptedMessage): void {
  const chan = useChannels.getState().byId[msg.channel_id];
  const base = chan?.server_id ? `/channels/${chan.server_id}` : "/channels/@me";
  void copyText(`${location.origin}${base}/${msg.channel_id}/${msg.id}`, t("chat.messageMenu.linkCopied"));
}

/** Deletes without the confirmation dialog (the Shift shortcut). */
export async function deleteMessageNow(msg: DecryptedMessage): Promise<void> {
  try {
    await messagesApi.remove(msg.channel_id, msg.id);
    useMessages.getState().remove(msg.channel_id, msg.id);
  } catch (err) {
    useUI.getState().toast(err instanceof Error ? err.message : t("chat.messageMenu.couldNotDeleteTheMessage"), "error");
  }
}

/** Whether a message can be ticked while picking messages to delete. */
export function canPick(msg: DecryptedMessage): boolean {
  // an app's answer only you see is no part of the conversation, so nothing to delete from it
  return !msg.type.startsWith("system_") && (msg.flags & MSG_FLAG_EPHEMERAL) === 0 && canDelete(msg);
}

/** Starts picking messages to delete in the message's channel, with that message ticked. */
export function startSelecting(msg: DecryptedMessage): void {
  const ui = useUI.getState();
  if (ui.editing?.channelId === msg.channel_id) ui.setEditing(null);
  useSelection.getState().start(msg.channel_id, canPick(msg) ? msg.id : undefined);
}

/**
 * Deletes the picked messages, a batch per request. Each batch that goes is
 * taken off the screen and out of the selection straight away, so a failure
 * part way leaves only what is still there picked, ready to try again.
 */
export async function deleteMessagesNow(channelId: string, ids: string[]): Promise<void> {
  for (let i = 0; i < ids.length; i += BULK_DELETE_MAX) {
    const batch = ids.slice(i, i + BULK_DELETE_MAX);
    await messagesApi.removeMany(channelId, batch);
    // ids the server skipped were already gone, so the whole batch leaves the screen
    useMessages.getState().removeMany(channelId, batch);
    useSelection.getState().drop(batch);
  }
}

/** Pins or unpins, whichever the message is not. */
export async function togglePin(msg: DecryptedMessage): Promise<void> {
  const pinned = (msg.flags & MSG_FLAG_PINNED) !== 0;
  try {
    if (pinned) await channelsApi.unpin(msg.channel_id, msg.id);
    else await channelsApi.pin(msg.channel_id, msg.id);
  } catch (err) {
    useUI.getState().toast(err instanceof Error ? err.message : t("chat.messageMenu.couldNotUpdateThePin"), "error");
  }
}
