/**
 * The v2 plugin API (api2.ts), built for one running plugin. runtime.ts loads
 * and pins the code, starts and stops plugins of both versions and keeps what
 * they registered; this file is what a v2 plugin is handed.
 *
 * Every method checks its permission as it is called, against the grants as
 * they are at that moment, and does what the app's own screens do, so keys,
 * encryption, captchas, AutoMod and the server's rate limits and permissions
 * apply exactly as they do to the person.
 */
import { channels as channelsApi } from "@/api/channels";
import { ApiError } from "@/api/client";
import { messages as messagesApi } from "@/api/messages";
import { servers as serversApi, type ChannelPatch, type RoleBody } from "@/api/servers";
import { threads as threadsApi } from "@/api/threads";
import type { Channel as WireChannel, Member, MeUser, Message as WireMessage, PublicUser, Relationship, ServerView } from "@/api/types";
import { users as usersApi, type MePatch } from "@/api/users";
import { api as client } from "@/api/client";
import { editMessage, sendMessage } from "@/components/composer/send";
import { isBuiltInCommand, takePluginCommand, unregisterPluginCommand } from "@/components/composer/commands";
import { bindText } from "@/lib/keybinds";
import { pluginBind, registerPluginKeybind } from "@/lib/pluginKeybinds";
import { useSettings } from "@/stores/settings";
import { dispatchHooks } from "@/gateway/dispatch";
import type { AppEvent } from "@/gateway/appEvents";
import { t, translator } from "@/i18n";
import { guarded } from "@/lib/captcha";
import { addButton, buttonInfo, dropAdded, dropChanges, editButton, listButtons, removeButton, type Subject } from "@/lib/contextMenus/registry";
import { decryptForStore, type DecryptedMessage } from "@/lib/decrypt";
import { doNotDisturb } from "@/lib/dnd";
import { startForumPost } from "@/lib/forumPost";
import { showNotification } from "@/lib/notify";
import { P, parsePerm, permString } from "@/lib/perms";
import { startThread } from "@/lib/threads";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useMessages } from "@/stores/messages";
import { useRelationships } from "@/stores/relationships";
import { useServers } from "@/stores/servers";
import { useThreads } from "@/stores/threads";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { usePresence } from "@/stores/presence";
import { useReadStates } from "@/stores/readStates";
import { useExpressions } from "@/stores/expressions";
import { emojiUrl } from "@/api/users";
import { copyText } from "@/lib/clipboard";
import { idTime } from "@/lib/ids";
import { promptFields } from "@/components/modals/PromptModal";
import type { PluginMeta } from "../types";
import type { PluginSetting, SettingValue } from "./api";
import {
  PermissionError,
  PLUGIN_API_V2,
  type Channel,
  type ChannelCreateOptions,
  type CommandContext,
  type KeybindContext,
  type ContextMenuButton,
  type ContextMenuChanges,
  type ContextMenuContext,
  type ContextMenuItem,
  type EditableChannel,
  type EditableMember,
  type EditableProfile,
  type EditableRole,
  type EditableServer,
  type Emoji,
  type Id,
  type Location,
  type Message,
  type Presence,
  type PromptOptions,
  type NewOverwrite,
  type PluginEventNameV2,
  type PluginEventsV2,
  type RelationshipKind,
  type Role,
  type RoleCreateOptions,
  type SelfUser,
  type SendOptions,
  type Server,
  type ServerCreateOptions,
  type ServerMember,
  type ThreadCreateOptions,
  type User,
} from "./api2";
import { relationshipName, toChannel, toMember, toMessage, toRole, toSelfUser, toServer, toServerFrom, toUser, type Can } from "./objects";
import type { PermissionName } from "./permissions";

/** What runtime.ts lends a v2 plugin's API: its own state there, and the means to change it. */
export interface V2Host {
  id: string;
  meta: PluginMeta;
  schema: PluginSetting[];
  /** The grants as they are now. */
  can: Can;
  values: () => Record<string, SettingValue>;
  /** Saves a setting's value with the account, as the Plugins page does. */
  saveSetting: (key: string, value: SettingValue | undefined) => void;
  /** Calls `fn` with the values and the ones before whenever they change; returns a stop. */
  onSettings: (fn: (values: Record<string, SettingValue>, old: Record<string, SettingValue>) => void) => () => void;
  addSheet: (css: string, persist: boolean) => () => void;
  clearSheets: () => void;
  storage: {
    get: (key: string) => unknown;
    set: (key: string, value: unknown) => void;
    remove: (key: string) => void;
    keys: () => string[];
    clear: () => void;
  };
  stores: Record<string, unknown>;
  /** Goes to a page of the app, as a link inside it would. */
  navigate: (path: string) => void;
}

/** One running v2 plugin, as the shared event hooks see it. */
interface Instance {
  host: V2Host;
  listeners: Map<string, Set<(payload: never) => unknown>>;
}

const instances = new Set<Instance>();

const str = (id: Id): string => String(id);
const isDM = (c: Pick<WireChannel, "server_id"> | undefined | null): boolean => !!c && !c.server_id;

function need(can: Can, p: PermissionName, what: string): void {
  if (!can(p)) throw new PermissionError(p, what);
}

/** What reading or writing in a channel takes: the server's permission, or the direct messages' one. */
function messagesPerm(c: WireChannel | undefined | null, write: boolean): PermissionName {
  if (isDM(c)) return write ? "directmessages.write" : "directmessages.read";
  return write ? "servers.messages.write" : "servers.messages.read";
}
function channelReadPerm(c: WireChannel | undefined | null): PermissionName {
  return isDM(c) ? "directmessages.read" : "servers.channels.read";
}

const notFound = (err: unknown): boolean => err instanceof ApiError && (err.status === 404 || err.status === 403);

async function orNull<T>(work: Promise<T>): Promise<T | null> {
  try {
    return await work;
  } catch (err) {
    if (notFound(err)) return null;
    throw err;
  }
}

/** Calls a plugin's callback; what it throws is logged and goes no further. */
function callSafely(name: string, fn: () => unknown): unknown {
  try {
    return fn();
  } catch (err) {
    console.warn(`plugin ${name}: a callback failed`, err);
    return undefined;
  }
}

// --- events -----------------------------------------------------------------------

type Delivery = { event: PluginEventNameV2 | string; perm: PermissionName | null; payload: (can: Can) => unknown };

function deliver(d: Delivery): void {
  for (const inst of instances) {
    const set = inst.listeners.get(d.event);
    if (!set?.size || !inst.host.can("events.read")) continue;
    if (d.perm && !inst.host.can(d.perm)) continue;
    const payload = d.payload(inst.host.can);
    for (const fn of [...set]) callSafely(inst.host.meta.name, () => (fn as (p: unknown) => unknown)(payload));
  }
}

const channelOf = (id: string) => useChannels.getState().byId[id];

function messageDelivery(event: "messageCreated" | "messageEdited", m: DecryptedMessage): Delivery {
  return { event, perm: messagesPerm(channelOf(m.channel_id), false), payload: () => toMessage(m) };
}

function selfOr(userId: string): PermissionName {
  return userId === useAuth.getState().me?.id ? "users.self.read" : "users.read";
}

function fromAppEvent(e: AppEvent): Delivery | null {
  switch (e.type) {
    case "reaction":
      return { event: e.added ? "reactionAdded" : "reactionRemoved", perm: messagesPerm(channelOf(e.channelId), false), payload: () => Object.freeze({ channelId: e.channelId, messageId: e.messageId, userId: e.userId, emoji: e.emoji }) };
    case "typing":
      return { event: e.started ? "typingStarted" : "typingStopped", perm: messagesPerm(channelOf(e.channelId), false), payload: () => Object.freeze({ channelId: e.channelId, userId: e.userId }) };
    case "memberJoined":
    case "memberUpdated":
      return { event: e.type, perm: "servers.members.read", payload: () => toMember(e.member) };
    case "memberLeft":
      return { event: "memberLeft", perm: "servers.members.read", payload: () => Object.freeze({ serverId: e.serverId, userId: e.userId, member: e.member ? toMember(e.member) : null }) };
    case "serverJoined":
      return { event: "serverJoined", perm: "servers.read", payload: () => toServer(e.server) };
    case "serverLeft":
      return { event: "serverLeft", perm: "servers.read", payload: () => Object.freeze({ id: e.id, server: toServer(e.server) }) };
    case "channelCreated":
    case "channelUpdated":
      return { event: e.type, perm: channelReadPerm(e.channel), payload: () => toChannel(e.channel) };
    case "channelDeleted":
      return { event: "channelDeleted", perm: e.serverId ? "servers.channels.read" : "directmessages.read", payload: () => Object.freeze({ id: e.id, serverId: e.serverId, channel: e.channel ? toChannel(e.channel) : null }) };
    case "userUpdated": {
      const self = e.user.id === useAuth.getState().me?.id;
      return { event: "userUpdated", perm: selfOr(e.user.id), payload: (can) => (self && useAuth.getState().me ? toSelfUser(useAuth.getState().me as MeUser, can) : toUser(e.user, can)) };
    }
    case "presenceUpdated":
      return { event: "presenceUpdated", perm: selfOr(e.presence.user_id), payload: () => Object.freeze({ userId: e.presence.user_id, status: e.presence.status }) };
    case "relationshipChanged":
      return { event: "relationshipChanged", perm: "users.relationships.read", payload: (can) => Object.freeze({ user: e.user ? toUser(e.user, can) : null, relationship: relationshipName(e.relationship), old: relationshipName(e.old) }) };
    case "localeChanged":
      return { event: "localeChanged", perm: null, payload: () => e.locale };
  }
}

// --- where the person is -----------------------------------------------------------

let path = typeof location !== "undefined" ? location.pathname : "/";

/** Where an address inside the app points: /channels/<server or @me>/<channel>. */
export function locationOf(p: string): Location {
  const parts = p.split("/").filter(Boolean);
  const inChannels = parts[0] === "channels";
  const place = inChannels ? (parts[1] ?? null) : null;
  const serverId = place && /^\d+$/.test(place) ? place : null;
  const channel = inChannels && place === "@me" ? (parts[2] ?? null) : inChannels && serverId ? (parts[2] ?? null) : null;
  return Object.freeze({ path: p, serverId, channelId: channel && /^\d+$/.test(channel) ? channel : null });
}

/** The app moved to another page (LookEffect tells it); plugins with navigation hear it. */
export function noteLocation(next: string): void {
  if (next === path) return;
  path = next;
  if (instances.size) deliver({ event: "locationChanged", perm: "navigation", payload: () => locationOf(next) });
}

// one set of hooks for every v2 plugin; nothing is turned into the API's shapes unless a plugin listens
dispatchHooks.onMessage.push((_m, _mine, decrypted) => {
  if (instances.size) deliver(messageDelivery("messageCreated", decrypted));
});
dispatchHooks.onMessageUpdate.push((decrypted) => {
  if (instances.size) deliver(messageDelivery("messageEdited", decrypted));
});
dispatchHooks.onMessageDelete.push((channelId, ids, was) => {
  if (!instances.size) return;
  const perm = messagesPerm(channelOf(channelId), false);
  ids.forEach((id, i) => deliver({ event: "messageDeleted", perm, payload: () => Object.freeze({ id, channelId, message: was[i] ? toMessage(was[i]!) : null }) }));
});
dispatchHooks.onMessageChange.push((c) => {
  if (!instances.size || c.kind !== "pin") return;
  deliver({ event: c.pinned ? "messagePinned" : "messageUnpinned", perm: messagesPerm(channelOf(c.channelId), false), payload: () => Object.freeze({ channelId: c.channelId, messageId: c.id }) });
});
dispatchHooks.onEvent.push((e) => {
  if (!instances.size) return;
  const d = fromAppEvent(e);
  if (d) deliver(d);
});

// --- the API -------------------------------------------------------------------------

export function buildApiV2(host: V2Host): { api: Record<string, unknown>; stop: () => void } {
  const { can, meta } = host;
  const name = meta.name;
  const inst: Instance = { host, listeners: new Map() };
  instances.add(inst);
  const settingStops = new Set<() => void>();

  const meId = () => useAuth.getState().me?.id;
  const userObj = (u: PublicUser): User => toUser(u, can);

  // --- users ---
  const users = Object.freeze({
    get(id: Id): User | null {
      const uid = str(id);
      need(can, selfOr(uid), "users.get");
      const me = useAuth.getState().me;
      if (me && me.id === uid) return toSelfUser(me, can);
      const u = useUsers.getState().byId[uid];
      return u ? userObj(u) : null;
    },
    async fetch(id: Id): Promise<User | null> {
      const uid = str(id);
      need(can, selfOr(uid), "users.fetch");
      const u = await orNull(usersApi.get(uid));
      if (!u) return null;
      useUsers.getState().upsert(u);
      return userObj(u);
    },
    get me(): SelfUser {
      need(can, "users.self.read", "users.me");
      const me = useAuth.getState().me;
      if (!me) throw new Error("nobody is signed in");
      return toSelfUser(me, can);
    },
    async update(changes: Partial<EditableProfile>): Promise<SelfUser> {
      need(can, "users.self.write", "users.update");
      const patch: MePatch = {};
      if (changes.displayName !== undefined) patch.display_name = String(changes.displayName);
      if (changes.pronouns !== undefined) patch.pronouns = String(changes.pronouns);
      if (changes.aboutMe !== undefined) patch.about_me = String(changes.aboutMe);
      if (changes.accentColor !== undefined) patch.accent_color = changes.accentColor;
      if (changes.accentColor2 !== undefined) patch.accent_color_2 = changes.accentColor2;
      if (changes.status !== undefined) {
        patch.status_text = changes.status.text ?? "";
        patch.status_emoji = changes.status.emoji ?? "";
        patch.status_expires_at = changes.status.expiresAt ? changes.status.expiresAt.getTime() : null;
      }
      if (changes.typingStyle !== undefined) {
        const s = changes.typingStyle;
        const base = `${client.baseUrl}/media/typing/`;
        patch.typing_style = s
          ? { emoji: s.emoji.map((e) => (e.startsWith(base) ? `img:${e.slice(base.length)}` : e)), motion: s.motion as never, ...(s.words ? { words: s.words } : s.preset ? { preset: s.preset } : {}) }
          : null;
      }
      const me = await usersApi.patchMe(patch);
      const was = useAuth.getState().me;
      useAuth.getState().setMe(was ? { ...was, ...me } : me);
      useUsers.getState().upsert(me);
      return toSelfUser(useAuth.getState().me as MeUser, can);
    },
    presence(id: Id): Presence {
      const uid = str(id);
      need(can, selfOr(uid), "users.presence");
      const p = usePresence.getState().byUser[uid];
      const status = p && p.status !== "invisible" ? p.status : "offline";
      return Object.freeze({
        userId: uid,
        status,
        activities: Object.freeze((p?.activities ?? []).map((a) => Object.freeze({ name: String(a.name ?? ""), type: Number(a.type ?? 0), details: a.details ?? null, state: a.state ?? null }))) as Presence["activities"],
      });
    },
    async listRelationships(filter?: Exclude<RelationshipKind, "none">): Promise<User[]> {
      need(can, "users.relationships.read", "users.listRelationships");
      const all = Object.values(useRelationships.getState().byUser) as Relationship[];
      return all.filter((r) => !filter || relationshipName(r.type) === filter).map((r) => userObj(useUsers.getState().byId[r.user.id] ?? r.user));
    },
  });

  // --- servers, roles, members ---
  const serverState = (id: string) => useServers.getState().byId[id];
  const serverObj = (id: string): Server => {
    const st = serverState(id);
    if (!st) throw new Error(`not in a server ${id}`);
    return toServer(st);
  };

  const roles = Object.freeze({
    async create(serverId: Id, options: RoleCreateOptions): Promise<Role> {
      need(can, "servers.write", "servers.roles.create");
      const body: RoleBody = { name: String(options.name) };
      if (options.color !== undefined) body.color = options.color;
      if (options.color2 !== undefined) body.color2 = options.color2;
      if (options.color3 !== undefined) body.color3 = options.color3;
      if (options.hoist !== undefined) body.hoist = options.hoist;
      if (options.mentionable !== undefined) body.mentionable = options.mentionable;
      if (options.permissions !== undefined) body.permissions = permString(BigInt(options.permissions));
      if (options.iconEmoji !== undefined) body.icon_emoji = options.iconEmoji;
      const role = await serversApi.createRole(str(serverId), body);
      useServers.getState().upsertRole(role);
      return toRole(role);
    },
    async update(serverId: Id, roleId: Id, changes: Partial<EditableRole>): Promise<Role> {
      need(can, "servers.write", "servers.roles.update");
      const sid = str(serverId);
      const rid = str(roleId);
      const body: RoleBody = {};
      if (changes.name !== undefined) body.name = changes.name;
      if (changes.color !== undefined) body.color = changes.color;
      if (changes.color2 !== undefined) body.color2 = changes.color2;
      if (changes.color3 !== undefined) body.color3 = changes.color3;
      if (changes.hoist !== undefined) body.hoist = changes.hoist;
      if (changes.mentionable !== undefined) body.mentionable = changes.mentionable;
      if (changes.permissions !== undefined) body.permissions = permString(BigInt(changes.permissions));
      if (changes.iconEmoji !== undefined) body.icon_emoji = changes.iconEmoji;
      let role = Object.keys(body).length ? await serversApi.patchRole(sid, rid, body) : serverState(sid)?.roles[rid];
      if (changes.position !== undefined) {
        const list = await serversApi.setRolePositions(sid, [{ id: rid, position: changes.position }]);
        useServers.getState().upsertRoles(sid, list);
        role = list.find((r) => r.id === rid) ?? role;
      }
      if (!role) throw new Error(`there is no role ${rid}`);
      useServers.getState().upsertRole(role);
      return toRole(role);
    },
    async delete(serverId: Id, roleId: Id): Promise<void> {
      need(can, "servers.write", "servers.roles.delete");
      await serversApi.deleteRole(str(serverId), str(roleId));
      useServers.getState().removeRole(str(serverId), str(roleId));
    },
  });

  const memberStore = (sid: string, uid: string) => serverState(sid)?.members[uid];
  const members = Object.freeze({
    get(serverId: Id, userId: Id): ServerMember | null {
      need(can, "servers.members.read", "servers.members.get");
      const m = memberStore(str(serverId), str(userId));
      return m ? toMember(m) : null;
    },
    async fetch(serverId: Id, userId: Id): Promise<ServerMember | null> {
      need(can, "servers.members.read", "servers.members.fetch");
      const m = await orNull(serversApi.member(str(serverId), str(userId)));
      return m ? toMember(m) : null;
    },
    async list(serverId: Id): Promise<ServerMember[]> {
      need(can, "servers.members.read", "servers.members.list");
      const sid = str(serverId);
      const out: Member[] = [];
      let after: string | undefined;
      for (;;) {
        const page = await serversApi.members(sid, { limit: 1000, ...(after ? { after } : {}) });
        useUsers.getState().upsert(page.users);
        out.push(...page.members);
        if (page.members.length < 1000) break;
        after = page.members[page.members.length - 1]!.user_id;
      }
      return out.map(toMember);
    },
    async update(serverId: Id, userId: Id, changes: Partial<EditableMember>): Promise<ServerMember> {
      need(can, "servers.members.write", "servers.members.update");
      const sid = str(serverId);
      const uid = str(userId);
      const patch: { nick?: string | null; timeout_until?: number | null; roles?: string[] } = {};
      if (changes.nickname !== undefined) patch.nick = changes.nickname || null;
      if (changes.timeoutUntil !== undefined) patch.timeout_until = changes.timeoutUntil ? changes.timeoutUntil.getTime() : null;
      if (changes.roleIds !== undefined) patch.roles = changes.roleIds.map(str);
      const m = await serversApi.patchMember(sid, uid === meId() ? "@me" : uid, patch);
      useServers.getState().upsertMember(m);
      return toMember(m);
    },
    async kick(serverId: Id, userId: Id, options?: { reason?: string }): Promise<void> {
      need(can, "servers.members.write", "servers.members.kick");
      await serversApi.kick(str(serverId), str(userId), options?.reason);
      useServers.getState().removeMember(str(serverId), str(userId));
    },
    async ban(serverId: Id, userId: Id, options?: { reason?: string }): Promise<void> {
      need(can, "servers.members.write", "servers.members.ban");
      await serversApi.ban(str(serverId), str(userId), options?.reason ? { reason: options.reason } : {});
      useServers.getState().removeMember(str(serverId), str(userId));
    },
    async unban(serverId: Id, userId: Id): Promise<void> {
      need(can, "servers.members.write", "servers.members.unban");
      await serversApi.unban(str(serverId), str(userId));
    },
    async mute(serverId: Id, userId: Id, until: Date): Promise<void> {
      need(can, "servers.members.write", "servers.members.mute");
      await members.update(serverId, userId, { timeoutUntil: until });
    },
    async unmute(serverId: Id, userId: Id): Promise<void> {
      need(can, "servers.members.write", "servers.members.unmute");
      await members.update(serverId, userId, { timeoutUntil: null });
    },
  });

  // --- messages ---
  async function channelFor(id: string): Promise<WireChannel | null> {
    return channelOf(id) ?? (await orNull(channelsApi.get(id)));
  }
  const decrypted = async (m: WireMessage) => decryptForStore(m);

  const messages = Object.freeze({
    get(channelId: Id, id: Id): Message | null {
      const cid = str(channelId);
      need(can, messagesPerm(channelOf(cid), false), "messages.get");
      const m = useMessages.getState().channels[cid]?.byId[str(id)];
      return m ? toMessage(m) : null;
    },
    async fetch(channelId: Id, id: Id): Promise<Message | null> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), false), "messages.fetch");
      const m = await orNull(messagesApi.get(cid, str(id)));
      return m ? toMessage(await decrypted(m)) : null;
    },
    async list(channelId: Id, options: { limit?: number; before?: string; after?: string } = {}): Promise<Message[]> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), false), "messages.list");
      const page = await messagesApi.list(cid, { limit: Math.max(1, Math.min(100, options.limit ?? 50)), ...(options.before ? { before: options.before } : {}), ...(options.after ? { after: options.after } : {}) });
      const out = await Promise.all(page.map(decrypted));
      return out.sort((a, b) => (BigInt(a.id) < BigInt(b.id) ? -1 : 1)).map((m) => toMessage(m));
    },
    async send(channelId: Id, options: SendOptions): Promise<Message> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), true), "messages.send");
      const content = String(options?.content ?? "");
      if (!content.trim()) throw new Error("a message needs some content");
      let sent: DecryptedMessage | null = null;
      await sendMessage(cid, content, { ...(options.replyToId ? { replyTo: options.replyToId } : {}), onSent: (m) => (sent = m) });
      if (!sent) throw new Error("the message was not sent");
      return toMessage(sent);
    },
    async edit(channelId: Id, id: Id, content: string): Promise<Message> {
      const cid = str(channelId);
      const mid = str(id);
      need(can, messagesPerm(await channelFor(cid), true), "messages.edit");
      const loaded = useMessages.getState().channels[cid]?.byId[mid];
      if (!loaded) throw new Error("only a message this device has loaded can be edited");
      if (loaded.author_id !== meId()) throw new Error("only your own messages can be edited");
      await editMessage(cid, mid, String(content));
      return toMessage(useMessages.getState().channels[cid]?.byId[mid] ?? loaded);
    },
    async delete(channelId: Id, id: Id): Promise<void> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), true), "messages.delete");
      await messagesApi.remove(cid, str(id));
      useMessages.getState().remove(cid, str(id));
    },
    async react(channelId: Id, id: Id, emoji: string): Promise<void> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), true), "messages.react");
      await messagesApi.react(cid, str(id), String(emoji));
    },
    async unreact(channelId: Id, id: Id, emoji: string): Promise<void> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), true), "messages.unreact");
      await messagesApi.unreact(cid, str(id), String(emoji));
    },
    async reactors(channelId: Id, id: Id, emoji: string): Promise<string[]> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), false), "messages.reactors");
      return [...(await messagesApi.reactors(cid, str(id), String(emoji), 100))];
    },
    async listPinned(channelId: Id): Promise<Message[]> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), false), "messages.listPinned");
      const list = await Promise.all((await channelsApi.pins(cid)).map(decrypted));
      return list.map((m) => toMessage(m));
    },
    async pin(channelId: Id, id: Id): Promise<void> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), true), "messages.pin");
      await channelsApi.pin(cid, str(id));
    },
    async unpin(channelId: Id, id: Id): Promise<void> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), true), "messages.unpin");
      await channelsApi.unpin(cid, str(id));
    },
    async markRead(channelId: Id): Promise<void> {
      const cid = str(channelId);
      need(can, messagesPerm(await channelFor(cid), false), "messages.markRead");
      const last = channelOf(cid)?.last_message_id;
      if (last) await useReadStates.getState().ack(cid, last);
    },
  });

  // --- channels ---
  async function setOverwrites(channelId: string, list: NewOverwrite[], before: WireChannel["overwrites"] = []): Promise<void> {
    const keep = new Set(list.map((o) => str(o.targetId)));
    for (const o of before ?? []) if (!keep.has(o.target_id)) await serversApi.deleteOverwrite(channelId, o.target_id);
    for (const o of list) await serversApi.putOverwrite(channelId, str(o.targetId), { type: o.targetType, allow: permString(BigInt(o.allow)), deny: permString(BigInt(o.deny)) });
  }

  const channels = Object.freeze({
    get(id: Id): Channel | null {
      const c = channelOf(str(id));
      need(can, channelReadPerm(c), "channels.get");
      return c ? toChannel(c) : null;
    },
    async fetch(id: Id): Promise<Channel | null> {
      const c = await orNull(channelsApi.get(str(id)));
      need(can, channelReadPerm(c ?? channelOf(str(id))), "channels.fetch");
      return c ? toChannel(c) : null;
    },
    list(serverId: Id): Channel[] {
      need(can, "servers.channels.read", "channels.list");
      const sid = str(serverId);
      return Object.values(useChannels.getState().byId)
        .filter((c) => c.server_id === sid)
        .map(toChannel);
    },
    async openDM(userId: Id): Promise<Channel> {
      need(can, "directmessages.write", "channels.openDM");
      const ch = await channelsApi.createDM([str(userId)]);
      useChannels.getState().upsert(ch);
      return toChannel(ch);
    },
    listDMs(): Channel[] {
      need(can, "directmessages.read", "channels.listDMs");
      return Object.values(useChannels.getState().byId)
        .filter((c) => c.type === "dm" || c.type === "group_dm")
        .map(toChannel);
    },
    async create(options: ChannelCreateOptions): Promise<Channel> {
      need(can, "servers.channels.write", "channels.create");
      const sid = str(options.serverId);
      let ch = await serversApi.createChannel(sid, { type: options.type, name: String(options.name), ...(options.parentId ? { parent_id: str(options.parentId) } : {}) });
      if (options.topic !== undefined || options.nsfw !== undefined) ch = await serversApi.patchChannel(ch.id, { ...(options.topic !== undefined ? { topic: options.topic } : {}), ...(options.nsfw !== undefined ? { nsfw: options.nsfw } : {}) });
      if (options.overwrites?.length) {
        await setOverwrites(ch.id, options.overwrites);
        ch = await channelsApi.get(ch.id);
      }
      useChannels.getState().upsert(ch);
      return toChannel(ch);
    },
    async update(id: Id, changes: Partial<EditableChannel>): Promise<Channel> {
      need(can, "servers.channels.write", "channels.update");
      const cid = str(id);
      const before = channelOf(cid) ?? (await channelsApi.get(cid));
      const patch: ChannelPatch = {};
      if (changes.name !== undefined) patch.name = changes.name;
      if (changes.topic !== undefined) patch.topic = changes.topic;
      if (changes.nsfw !== undefined) patch.nsfw = changes.nsfw;
      if (changes.slowmodeSeconds !== undefined) patch.slowmode_seconds = changes.slowmodeSeconds;
      if (changes.userLimit !== undefined) patch.user_limit = changes.userLimit;
      if (changes.thread) {
        if (changes.thread.archived !== undefined) patch.archived = changes.thread.archived;
        if (changes.thread.locked !== undefined) patch.locked = changes.thread.locked;
        if (changes.thread.autoArchive !== undefined) patch.auto_archive = changes.thread.autoArchive;
        if (changes.thread.appliedTags !== undefined) patch.applied_tags = changes.thread.appliedTags.map(str);
      }
      let ch = Object.keys(patch).length ? await serversApi.patchChannel(cid, patch) : before;
      if ((changes.position !== undefined || changes.parentId !== undefined) && before.server_id) {
        const list = await serversApi.setChannelPositions(before.server_id, [{ id: cid, position: changes.position ?? before.position, ...(changes.parentId !== undefined ? { parent_id: changes.parentId ? str(changes.parentId) : null } : {}) }]);
        useChannels.getState().upsert(list);
        ch = list.find((c) => c.id === cid) ?? ch;
      }
      if (changes.overwrites) {
        await setOverwrites(cid, changes.overwrites, before.overwrites);
        ch = await channelsApi.get(cid);
      }
      useChannels.getState().upsert(ch);
      return toChannel(ch);
    },
    async delete(id: Id): Promise<void> {
      need(can, "servers.channels.write", "channels.delete");
      await serversApi.deleteChannel(str(id));
      useChannels.getState().remove(str(id));
    },
    async createThread(options: ThreadCreateOptions): Promise<Channel> {
      need(can, "servers.channels.write", "channels.createThread");
      const parent = await channelFor(str(options.parentId));
      if (!parent) throw new Error(`there is no channel ${options.parentId}`);
      if (parent.type === "forum") {
        if (!options.content?.trim()) throw new Error("a forum post needs content: its first message");
        const post = await startForumPost(parent, { name: String(options.name), body: options.content, ...(options.appliedTags ? { appliedTags: options.appliedTags.map(str) } : {}) });
        if (!post) throw new Error("AutoMod stopped the post");
        return toChannel(post);
      }
      const thread = await startThread(parent, { name: String(options.name), ...(options.starterId ? { messageId: str(options.starterId) } : {}), ...(options.autoArchive ? { autoArchive: options.autoArchive } : {}) });
      if (options.content?.trim()) await sendMessage(thread.id, options.content);
      return toChannel(useChannels.getState().byId[thread.id] ?? thread);
    },
    async joinThread(id: Id): Promise<void> {
      need(can, "servers.channels.write", "channels.joinThread");
      await threadsApi.join(str(id));
      useThreads.getState().setJoined(str(id), true);
    },
    async leaveThread(id: Id): Promise<void> {
      need(can, "servers.channels.write", "channels.leaveThread");
      await threadsApi.leave(str(id));
      useThreads.getState().setJoined(str(id), false);
    },
    messages,
  });

  const toEmoji = (e: { id: string; server_id: string; name: string; animated: boolean }): Emoji =>
    Object.freeze({ id: e.id, serverId: e.server_id, name: e.name, animated: !!e.animated, url: emojiUrl(e.id, !!e.animated), markdown: `<${e.animated ? "a" : ""}:${e.name}:${e.id}>` });
  const emojis = Object.freeze({
    list(serverId?: Id): Emoji[] {
      need(can, "servers.read", "servers.emojis.list");
      const all = useExpressions.getState().emojis;
      const lists = serverId === undefined ? Object.values(all) : [all[str(serverId)] ?? []];
      return lists.flat().map(toEmoji);
    },
    find(name: string): Emoji | null {
      need(can, "servers.read", "servers.emojis.find");
      const want = String(name).replace(/^:|:$/g, "").toLowerCase();
      const hit = Object.values(useExpressions.getState().emojis).flat().find((e) => e.name.toLowerCase() === want);
      return hit ? toEmoji(hit) : null;
    },
  });

  const servers = Object.freeze({
    get(id: Id): Server | null {
      need(can, "servers.read", "servers.get");
      const st = serverState(str(id));
      return st ? toServer(st) : null;
    },
    async fetch(id: Id): Promise<Server | null> {
      need(can, "servers.read", "servers.fetch");
      const got = await orNull(serversApi.get(str(id)));
      if (!got) return null;
      const v: ServerView = got.server;
      return toServerFrom(v, { roles: v.roles, me: v.me, memberCount: v.member_count, myPermissions: parsePerm(v.my_permissions) });
    },
    list(): Server[] {
      need(can, "servers.read", "servers.list");
      return Object.values(useServers.getState().byId).map(toServer);
    },
    async create(options: ServerCreateOptions): Promise<Server> {
      need(can, "servers.write", "servers.create");
      const nameText = String(options?.name ?? "").trim();
      if (!nameText) throw new Error("a server needs a name");
      const view = await guarded("create_server", (captcha) => serversApi.create(nameText, captcha));
      useChannels.getState().upsert(view.channels);
      useServers.getState().setFromView(view);
      if (options.description) useServers.getState().updateServer(await serversApi.patch(view.id, { description: options.description }));
      return serverObj(view.id);
    },
    async update(id: Id, changes: Partial<EditableServer>): Promise<Server> {
      need(can, "servers.write", "servers.update");
      const sid = str(id);
      const patch: Record<string, unknown> = {};
      if (changes.name !== undefined) patch["name"] = changes.name;
      if (changes.description !== undefined) patch["description"] = changes.description;
      if (changes.systemChannelId !== undefined) patch["system_channel_id"] = changes.systemChannelId === null ? null : str(changes.systemChannelId);
      if (changes.defaultNotifications !== undefined) patch["default_notifications"] = changes.defaultNotifications;
      useServers.getState().updateServer(await serversApi.patch(sid, patch));
      return serverObj(sid);
    },
    async leave(id: Id): Promise<void> {
      need(can, "servers.write", "servers.leave");
      const sid = str(id);
      if (serverState(sid)?.server.owner_id === meId()) throw new Error("the owner cannot leave their own server");
      await serversApi.leave(sid);
      useServers.getState().remove(sid);
    },
    members,
    channels,
    roles,
    emojis,
  });

  // --- events ---
  const events = Object.freeze({
    on<E extends PluginEventNameV2>(event: E | string, cb: (payload: E extends PluginEventNameV2 ? PluginEventsV2[E] : unknown) => unknown): () => void {
      need(can, "events.read", "events.on");
      if (typeof cb !== "function") throw new Error("events.on needs a function");
      let set = inst.listeners.get(event);
      if (!set) inst.listeners.set(event, (set = new Set()));
      set.add(cb as (p: never) => unknown);
      return () => void set!.delete(cb as (p: never) => unknown);
    },
    once<E extends PluginEventNameV2>(event: E | string, cb: (payload: E extends PluginEventNameV2 ? PluginEventsV2[E] : unknown) => unknown): () => void {
      const off = events.on(event as string, ((p: unknown) => {
        off();
        return (cb as (x: unknown) => unknown)(p);
      }) as never);
      return off;
    },
    run(event: string, data?: unknown): void {
      need(can, "events.write", "events.run");
      // named after the plugin, so a plugin never fires one of the app's own events
      deliver({ event: `${name}:${String(event)}`, perm: null, payload: () => data });
    },
  });

  // --- styles, storage, settings ---
  const styles = Object.freeze({
    add(stylesheet: string, persist = false): () => void {
      need(can, "styles.write", "styles.add");
      return host.addSheet(String(stylesheet), !!persist);
    },
    clear(): void {
      need(can, "styles.write", "styles.clear");
      host.clearSheets();
    },
  });

  const storage = Object.freeze({
    get<T = unknown>(key: string): T | undefined {
      need(can, "storage.read", "storage.get");
      return host.storage.get(String(key)) as T | undefined;
    },
    set(key: string, value: unknown): void {
      need(can, "storage.write", "storage.set");
      host.storage.set(String(key), value);
    },
    remove(key: string): void {
      need(can, "storage.write", "storage.remove");
      host.storage.remove(String(key));
    },
    keys(): string[] {
      need(can, "storage.read", "storage.keys");
      return host.storage.keys();
    },
    clear(): void {
      need(can, "storage.write", "storage.clear");
      host.storage.clear();
    },
  });

  const declared = (key: string) => host.schema.find((s) => s.key === key);
  const settings = Object.freeze({
    get(key: string): SettingValue | undefined {
      return declared(String(key)) ? host.values()[String(key)] : undefined;
    },
    getAll(): Record<string, SettingValue> {
      return { ...host.values() };
    },
    set(key: string, value: SettingValue): void {
      const s = declared(String(key));
      if (!s) throw new Error(`there is no setting "${key}"`);
      const bad = (why: string) => new Error(`the setting "${key}" ${why}`);
      if (s.type === "toggle" && typeof value !== "boolean") throw bad("takes true or false");
      if (s.type === "text") {
        if (typeof value !== "string") throw bad("takes text");
        if (value.length > (s.maxLength ?? 2000)) throw bad(`takes at most ${s.maxLength ?? 2000} characters`);
      }
      if (s.type === "number") {
        if (typeof value !== "number" || !Number.isFinite(value)) throw bad("takes a number");
        if ((s.min !== undefined && value < s.min) || (s.max !== undefined && value > s.max)) throw bad(`takes a number from ${s.min ?? "any"} to ${s.max ?? "any"}`);
      }
      if (s.type === "select" && !s.options.some((o) => o.value === value)) throw bad(`takes one of ${s.options.map((o) => o.value).join(", ")}`);
      if (s.type === "color" && (typeof value !== "string" || !/^#[0-9a-f]{6}$/i.test(value))) throw bad('takes a colour such as "#8000ff"');
      host.saveSetting(s.key, value);
    },
    reset(key?: string): void {
      if (key === undefined) for (const s of host.schema) host.saveSetting(s.key, undefined);
      else if (declared(String(key))) host.saveSetting(String(key), undefined);
      else throw new Error(`there is no setting "${key}"`);
    },
    onChange(key: string, cb: (value: SettingValue, old: SettingValue) => void): () => void {
      if (typeof cb !== "function") throw new Error("settings.onChange needs a function");
      const k = String(key);
      const stop = host.onSettings((values, old) => {
        if (values[k] !== old[k]) callSafely(name, () => cb(values[k]!, old[k]!));
      });
      settingStops.add(stop);
      return () => {
        stop();
        settingStops.delete(stop);
      };
    },
  });

  // --- commands and keybinds ---
  const taken = new Set<() => void>();
  const keep = (undo: () => void) => {
    taken.add(undo);
    return () => {
      undo();
      taken.delete(undo);
    };
  };
  /** Who and where, for a command or a keybind: what this plugin may see of them. */
  const runContext = (channelId: string | null) => {
    const me = useAuth.getState().me;
    const ch = channelId ? channelOf(channelId) : undefined;
    return Object.freeze({
      user: me && can("users.self.read") ? toSelfUser(me, can) : null,
      channel: ch && can(channelReadPerm(ch)) ? toChannel(ch) : null,
    });
  };
  /** Runs a plugin's callback, which may be async; what it throws or rejects with goes no further than the log. */
  const runQuietly = (fn: () => unknown) => {
    const out = callSafely(name, fn);
    if (out && typeof (out as Promise<unknown>).then === "function") (out as Promise<unknown>).then(undefined, (err: unknown) => console.warn(`plugin ${name}: a callback failed`, err));
  };

  const commands = Object.freeze({
    register(commandName: string, description: string, cb: (args: string, ctx: CommandContext) => unknown): () => void {
      need(can, "commands.write", "commands.register");
      const n = String(commandName ?? "");
      if (/[A-Z]/.test(n)) throw new Error(`the command name "${n}" has to be lowercase`);
      if (/\s/.test(n)) throw new Error(`the command name "${n}" cannot have spaces`);
      if (!/^[a-z0-9_-]{1,32}$/.test(n)) throw new Error(`the command name "${n}" can have only letters, digits, - and _, up to 32`);
      if (isBuiltInCommand(n)) throw new Error(`/${n} is one of the app's own commands`);
      if (typeof cb !== "function") throw new Error("commands.register needs a function");
      // nothing is sent in its place: the plugin does what it does
      const run = async (args: string, channelId: string): Promise<string | null> => {
        runQuietly(() => cb(args, runContext(channelId)));
        return null;
      };
      takePluginCommand({ name: n, desc: t("look.plugins.commandFrom", { description: String(description ?? "").slice(0, 100), name }), run });
      return keep(() => unregisterPluginCommand(n, run));
    },
  });

  const keybindStops = new Set<() => void>();
  const keybinds = Object.freeze({
    register(id: string, options: { name: string; description: string }, cb: (ctx: KeybindContext) => unknown): () => void {
      need(can, "keybinds.write", "keybinds.register");
      const kid = String(id ?? "");
      if (!/^[A-Za-z0-9_.:-]{1,64}$/.test(kid)) throw new Error(`a keybind id has letters, digits, ".", ":", "-" and "_", up to 64: "${kid}"`);
      if (typeof cb !== "function") throw new Error("keybinds.register needs a function");
      const info = { name: String(options?.name ?? kid).slice(0, 80), description: String(options?.description ?? "").slice(0, 200) };
      const undo = registerPluginKeybind(host.id, name, kid, info, () => runQuietly(() => cb(runContext(useUI.getState().activeChannelId ?? null))));
      return keep(undo);
    },
    get(id: string): string | null {
      need(can, "keybinds.read", "keybinds.get");
      return bindText(pluginBind(host.id, String(id))) || null;
    },
    onChange(id: string, cb: (keys: string | null) => void): () => void {
      need(can, "keybinds.read", "keybinds.onChange");
      if (typeof cb !== "function") throw new Error("keybinds.onChange needs a function");
      const kid = String(id);
      let was = pluginBind(host.id, kid);
      const stop = useSettings.subscribe(() => {
        const now = pluginBind(host.id, kid);
        if (now === was) return;
        was = now;
        callSafely(name, () => cb(bindText(now) || null));
      });
      keybindStops.add(stop);
      return () => {
        stop();
        keybindStops.delete(stop);
      };
    },
  });

  // --- ui ---
  /** What a menu is about, as this plugin may see it. */
  const menuContext = (s: Subject): ContextMenuContext => {
    const ch = s.channelId ? channelOf(s.channelId) : undefined;
    const serverId = s.serverId ?? ch?.server_id ?? null;
    const msg = s.message ?? null;
    const msgCh = msg ? channelOf(msg.channel_id) : ch;
    const user = s.userId ? (useAuth.getState().me?.id === s.userId ? useAuth.getState().me : useUsers.getState().byId[s.userId]) : undefined;
    const st = serverId ? serverState(serverId) : undefined;
    return Object.freeze({
      menu: s.menu,
      message: msg && can(messagesPerm(msgCh, false)) ? toMessage(msg) : null,
      user: user && can(selfOr(user.id)) ? userObj(user) : null,
      channel: ch && can(channelReadPerm(ch)) ? toChannel(ch) : null,
      server: st && can("servers.read") ? toServer(st) : null,
    });
  };

  const contextMenu = Object.freeze({
    add(menu: string, b: ContextMenuButton): () => void {
      need(can, "ui.write", "ui.contextMenu.add");
      if (!b || typeof b.id !== "string" || !b.id || typeof b.label !== "string" || typeof b.onClick !== "function") throw new Error("a button needs an id, a label and an onClick");
      addButton(host.id, String(menu), {
        id: b.id,
        label: b.label.slice(0, 100),
        ...(b.icon ? { icon: String(b.icon) } : {}),
        ...(b.danger ? { danger: true } : {}),
        ...(b.separatorAfter ? { separatorAfter: true } : {}),
        ...(b.before ? { before: String(b.before) } : {}),
        ...(b.after ? { after: String(b.after) } : {}),
        ...(typeof b.visible === "function" ? { visible: (s: Subject) => b.visible!(menuContext(s)) } : {}),
        onClick: (s) => b.onClick(menuContext(s)),
      });
      return () => dropAdded(host.id, b.id);
    },
    remove(id: string): void {
      need(can, "ui.write", "ui.contextMenu.remove");
      removeButton(host.id, String(id));
    },
    edit(id: string, changes: Partial<ContextMenuChanges>): void {
      need(can, "ui.write", "ui.contextMenu.edit");
      const c = changes ?? {};
      editButton(host.id, String(id), {
        ...(c.label !== undefined ? { label: String(c.label).slice(0, 100) } : {}),
        ...(c.icon !== undefined ? { icon: c.icon === null ? null : String(c.icon) } : {}),
        ...(c.danger !== undefined ? { danger: !!c.danger } : {}),
        ...(c.separatorAfter !== undefined ? { separatorAfter: !!c.separatorAfter } : {}),
        ...(typeof c.visible === "function" ? { visible: (s: Subject) => c.visible!(menuContext(s)) } : {}),
        ...(typeof c.onClick === "function" ? { onClick: (s: Subject, original: () => void) => c.onClick!(menuContext(s), original) } : {}),
      });
    },
    get(id: string): ContextMenuItem | null {
      need(can, "ui.write", "ui.contextMenu.get");
      const b = buttonInfo(String(id));
      return b ? Object.freeze({ ...b }) : null;
    },
    list(menu: string): ContextMenuItem[] {
      need(can, "ui.write", "ui.contextMenu.list");
      return listButtons(String(menu)).map((b) => Object.freeze({ ...b }));
    },
  });

  const ui = Object.freeze({
    toast(message: string, options?: { type?: "info" | "success" | "warning" | "error"; duration?: number }): void {
      need(can, "ui.write", "ui.toast");
      const type = options?.type;
      const kind = type === "success" || type === "error" || type === "warning" ? type : "info";
      // a plugin's toast lasts what it asks, from 1 to 15 seconds (the store holds it to that), 4 when it does not say
      const duration = typeof options?.duration === "number" && Number.isFinite(options.duration) ? options.duration : 4000;
      useUI.getState().toast(t("look.plugins.toastFrom", { name, text: String(message).slice(0, 300) }), kind, duration);
    },
    notify(title: string, body?: string): void {
      need(can, "ui.write", "ui.notify");
      if (typeof Notification === "undefined" || doNotDisturb()) return;
      const show = () => void showNotification(t("look.plugins.toastFrom", { name, text: String(title).slice(0, 100) }), { ...(body ? { body: String(body).slice(0, 300) } : {}), icon: "/icons/icon-192.png" });
      if (Notification.permission === "granted") show();
      else if (Notification.permission === "default") void Notification.requestPermission().then((p) => p === "granted" && show());
    },
    confirm(options: { title: string; message: string; confirmText?: string; cancelText?: string }): Promise<boolean> {
      need(can, "ui.write", "ui.confirm");
      return new Promise<boolean>((resolve) => {
        useUI.getState().openModal("confirm", {
          title: t("look.plugins.toastFrom", { name, text: String(options?.title ?? "").slice(0, 100) }),
          body: String(options?.message ?? "").slice(0, 1000),
          ...(options?.confirmText ? { confirmLabel: String(options.confirmText).slice(0, 40) } : {}),
          ...(options?.cancelText ? { cancelLabel: String(options.cancelText).slice(0, 40) } : {}),
          onConfirm: () => resolve(true),
          onDismiss: () => resolve(false),
        });
      });
    },
    async prompt(options: PromptOptions): Promise<string | null> {
      need(can, "ui.write", "ui.prompt");
      const o = options ?? ({} as PromptOptions);
      const maxLength = Math.max(1, Math.min(2000, Number(o.maxLength) || 2000));
      const values = await promptFields({
        title: t("look.plugins.toastFrom", { name, text: String(o.title ?? "").slice(0, 100) }),
        fields: [{ key: "value", label: String(o.label ?? "").slice(0, 100), ...(o.placeholder ? { placeholder: String(o.placeholder).slice(0, 100) } : {}), ...(o.initial ? { initial: String(o.initial).slice(0, maxLength) } : {}), maxLength }],
        ...(o.confirmText ? { confirmLabel: String(o.confirmText).slice(0, 40) } : {}),
        allowEmpty: true,
      });
      return values ? (values["value"] ?? "") : null;
    },
    openProfile(userId: Id): void {
      need(can, "ui.write", "ui.openProfile");
      need(can, selfOr(str(userId)), "ui.openProfile");
      useUI.getState().openModal("profile", { userId: str(userId) });
    },
    async copy(text: string): Promise<boolean> {
      need(can, "ui.write", "ui.copy");
      return copyText(String(text), t("look.plugins.toastFrom", { name, text: t("clipboard.copied") }));
    },
    contextMenu,
  });

  const composer = Object.freeze({
    insert(text: string): void {
      need(can, "composer.write", "composer.insert");
      const detail = String(text).slice(0, 4000);
      // a tick later: a command calling this runs before the box it was typed in has emptied and redrawn
      setTimeout(() => window.dispatchEvent(new CustomEvent("ec:insert-text", { detail })), 0);
    },
  });

  const navigation = Object.freeze({
    get current(): Location {
      need(can, "navigation", "navigation.current");
      return locationOf(location.pathname);
    },
    go(to: string): void {
      need(can, "navigation", "navigation.go");
      const p = String(to);
      if (!p.startsWith("/") || p.startsWith("//")) throw new Error(`navigation.go takes a path inside the app, such as "/channels/@me", not "${p}"`);
      host.navigate(p);
    },
    openChannel(channelId: Id, messageId?: Id): void {
      need(can, "navigation", "navigation.openChannel");
      const c = channelOf(str(channelId));
      if (!c) throw new Error(`this device does not know a channel ${channelId}`);
      host.navigate(`/channels/${c.server_id ?? "@me"}/${c.id}${messageId !== undefined ? `/${str(messageId)}` : ""}`);
    },
  });

  const utils = Object.freeze({
    mention: Object.freeze({
      user: (id: Id) => `<@${str(id)}>`,
      role: (id: Id) => `<@&${str(id)}>`,
      channel: (id: Id) => `<#${str(id)}>`,
    }),
    timestamp(when: Date | number, style: "t" | "T" | "d" | "D" | "f" | "F" | "R" = "f"): string {
      const ms = when instanceof Date ? when.getTime() : Number(when);
      if (!Number.isFinite(ms)) throw new Error("utils.timestamp needs a Date or milliseconds");
      return `<t:${Math.floor(ms / 1000)}:${/^[tTdDfFR]$/.test(style) ? style : "f"}>`;
    },
    escapeMarkdown(text: string): string {
      return String(text).replace(/([\\*_~`|>#[\]()<{}-])/g, "\\$1");
    },
    idDate(id: Id): Date {
      return new Date(idTime(str(id)));
    },
    hasPermission(bits: bigint, permission: bigint): boolean {
      return (BigInt(bits) & BigInt(permission)) === BigInt(permission);
    },
  });

  const api = Object.freeze({
    apiVersion: PLUGIN_API_V2,
    plugin: Object.freeze({ id: host.id, name, description: meta.description || null, version: meta.version || null, author: meta.author || null }),
    app: Object.freeze({
      name: "Nexa",
      build: typeof __BUILD_ID__ === "string" ? __BUILD_ID__ : "",
      get locale() {
        return translator().locale;
      },
    }),
    users,
    servers,
    channels,
    messages,
    styles,
    events,
    commands,
    keybinds,
    storage,
    settings,
    ui,
    composer,
    navigation,
    utils,
    constants: Object.freeze({ Permissions: Object.freeze({ ...P }) }),
    get stores() {
      need(can, "stores", "stores");
      return host.stores;
    },
  });

  return {
    api,
    stop() {
      instances.delete(inst);
      inst.listeners.clear();
      for (const s of settingStops) s();
      settingStops.clear();
      for (const undo of taken) undo();
      taken.clear();
      for (const s of keybindStops) s();
      keybindStops.clear();
      dropChanges(host.id);
    },
  };
}

/** For tests: every v2 plugin is stopped, so nothing is left listening between them. */
export function forgetInstances(): void {
  instances.clear();
}

