/** The app's normal API contract, implemented entirely inside this frame. Unknown actions fail closed. */
import { api, ApiError, type RequestOptions } from "@/api/client";
import type { Channel, MeUser, Message, NoteRow, NoteUpdateRow, NoteVersionRow, Role, ServerView } from "@/api/types";
import { b64, randomBytes } from "@/crypto/bytes";
import { newRootKey } from "@/crypto/conv";
import { generateIdentity } from "@/crypto/keys";
import { t } from "@/i18n";
import { acceptTerms } from "@/lib/legal";
import { ring } from "@/lib/keyring";
import { ALL } from "@/lib/perms";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useKeys } from "@/stores/keys";
import { usePresence } from "@/stores/presence";
import { useMessages } from "@/stores/messages";
import { useServers } from "@/stores/servers";
import { DEFAULT_SETTINGS, useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { RELEASES } from "@/lib/whatsNew";

export function accountRequired(): never {
  window.dispatchEvent(new Event("nexa-demo-account"));
  throw new ApiError(403, "demo_account_required", t("home.demo.accountTitle"));
}

export function installDemoRuntime() {
  const identity = generateIdentity();
  const now = Date.now();
  let counter = 0n;
  const id = () => (((BigInt(Date.now()) - 1420070400000n) << 22n) + counter++).toString();
  const me: MeUser = {
    id: id(), username: "you", display_name: t("callScreen.you"), avatar_id: null, banner_id: null,
    about_me: "", pronouns: "", accent_color: null, accent_color_2: null, status_text: "", status_emoji: "",
    status_expires_at: null, public_key: b64.enc(identity.publicKey), flags: 0, created_at: now,
    presence_pref: "online", dm_privacy: "friends_servers", has_recovery: false, plan: "",
  };
  const servers = new Map<string, ServerView>();
  const channels = new Map<string, Channel>();
  const history = new Map<string, Message[]>();
  const notes = new Map<string, NoteRow>();
  const updates = new Map<string, NoteUpdateRow[]>();
  const versions = new Map<string, NoteVersionRow[]>();
  let settingsBlob: string | null = null;
  let settingsVersion = 0;
  let widgets: unknown[] = [];
  const channel = (server: string, type: Channel["type"], name: string, parent: string | null = null): Channel => {
    const ch: Channel = { id: id(), server_id: server, type, name, topic: "", position: channels.size,
      parent_id: parent, nsfw: false, slowmode_seconds: 0, user_limit: 0, owner_id: me.id,
      icon_id: null, last_message_id: null, created_at: Date.now(), recipient_ids: [], key_conv_id: server, overwrites: [] };
    if (type === "forum" || type === "media") ch.forum = { tags: [], default_reaction: "", sort: "activity", layout: type === "media" ? "gallery" : "list", require_tag: false, pinned_post: null };
    channels.set(ch.id, ch);
    return ch;
  };
  const syncServer = (view: ServerView) => {
    view.channels = [...channels.values()].filter((ch) => ch.server_id === view.id && !["thread", "private_thread"].includes(ch.type));
    useServers.getState().setFromView(view);
    useChannels.getState().upsert([...channels.values()].filter((ch) => ch.server_id === view.id));
  };
  const limits = { file_max: 1024 ** 3, month_upload_max: 5 * 1024 ** 3, share_quality: false, share_max_bps: 6000000, name_effects: false, boosts: 0 };
  async function request(method: string, rawPath: string, body?: unknown, opts?: RequestOptions): Promise<unknown> {
    const path = rawPath.split("?")[0]!;
    const parts = path.split("/").filter(Boolean);
    const data = (body ?? {}) as Record<string, unknown>;
    const reading = method === "GET";
    if (path === "/users/@me" && (reading || method === "PATCH")) {
      // Only ordinary profile edits; account/security actions never succeed in the demo.
      const allowed = ["display_name", "about_me", "pronouns", "accent_color", "accent_color_2", "status_text", "status_emoji", "status_expires_at", "presence_pref", "dm_privacy", "group_link_only", "typing_style", "name_style"];
      if (!reading) for (const key of Object.keys(data)) if (allowed.includes(key)) Object.assign(me, { [key]: data[key] }); else return accountRequired();
      useAuth.getState().setMe({ ...me }); useUsers.getState().upsert({ ...me });
      return { ...me };
    }
    if (path === "/users/@me/settings") {
      if (method === "PUT") { settingsBlob = String(data["blob"]); settingsVersion++; }
      return reading ? (settingsBlob ? { blob: settingsBlob, version: settingsVersion } : null) : { version: settingsVersion };
    }
    // a fresh visitor account has earned no badges
    if (path === "/users/@me/badges") return { badges: [] };
    if (path === "/users/@me/widgets") { if (!reading) widgets = data["widgets"] as unknown[]; return { widgets }; }
    if (path === `/users/${me.id}/profile`) return { user: { ...me }, mutual_friends: [], mutual_servers: [...servers.keys()], widgets };
    if (path === `/users/${me.id}`) return { ...me };
    if (path === `/users/${me.id}/public-key`) return { user_id: me.id, public_key: me.public_key };
    if (reading && ["/users/@me/channels", "/users/@me/relationships", "/auth/sessions", "/auth/passkeys", "/users/@me/sounds", "/users/@me/blocked-images", "/users/@me/authorized-apps", "/applications", "/sticker-packs", "/oauth2/authorized", "/stickers/packs", "/stickers/recent", "/users/@me/blocked-pictures"].includes(path)) return [];
    if (reading && path === "/auth/mfa") return { enabled: false, backup_codes_left: 0 };
    if (reading && path === "/connections/discord") return { available: true, linked: null };
    if (reading && path === "/gif/status") return { available: false };
    if (reading && path === "/instance") return { max_upload_bytes: limits.file_max, registration: "open", plans: { enabled: true, free: limits, supporter: { ...limits, file_max: 2 * 1024 ** 3, share_quality: true }, pro: { ...limits, file_max: 4 * 1024 ** 3, share_quality: true }, boost_file_max: 2 * 1024 ** 3 } };
    if (reading && path === "/billing") return { enabled: true, plan: "", limits, month_used: 0, month_frees_at: 0, boosts: [], period: "", status: "", period_end: 0, cancel_at_end: false, manageable: false, prices: { supporter: { month: { amount: 500, currency: "usd" }, year: { amount: 4800, currency: "usd" } }, pro: { month: { amount: 1000, currency: "usd" }, year: { amount: 9600, currency: "usd" } } }, gift_prices: {}, gifts: [], gift_links: [] };
    if (reading && path === "/desktop/latest") return null;
    if (reading && path === "/android/latest") return null;
    if (reading && path === "/captcha/config") return { enabled: false };
    if (path === "/servers" && method === "POST") {
      const sid = id();
      const defaults = data["defaults"] as Record<string, string>;
      const textCat = channel(sid, "category", defaults["text_category"]!);
      const general = channel(sid, "text", defaults["text_channel"]!, textCat.id);
      const voiceCat = channel(sid, "category", defaults["voice_category"]!);
      channel(sid, "voice", defaults["voice_channel"]!, voiceCat.id);
      const member = { server_id: sid, user_id: me.id, nickname: "", avatar_id: null, joined_at: Date.now(), timeout_until: null, flags: 0, role_ids: [] };
      const role: Role = { id: sid, server_id: sid, name: "@everyone", color: 0, hoist: false, mentionable: false, position: 0, permissions: ALL.toString(), icon_id: null, created_at: Date.now() };
      const view: ServerView = { id: sid, name: String(data["name"]), icon_id: null, banner_id: null, owner_id: me.id, description: "", system_channel_id: general.id, default_notifications: 0, flags: 0, created_at: Date.now(), channels: [], roles: [role], members: [member], member_count: 1, me: member, my_permissions: ALL.toString() };
      servers.set(sid, view); ring.set(sid, 1, newRootKey()); syncServer(view);
      return view;
    }
    const server = parts[0] === "servers" ? servers.get(parts[1]!) : undefined;
    if (server) {
      if (parts.length === 2) {
        if (method === "DELETE") { servers.delete(server.id); useServers.getState().remove(server.id); for (const ch of server.channels) { channels.delete(ch.id); useChannels.getState().remove(ch.id); } return; }
        if (method === "PATCH") { Object.assign(server, data); syncServer(server); return server; }
        if (reading) return { server, users: [{ ...me }] };
      }
      if (parts[2] === "channels") {
        if (method === "POST") { const ch = channel(server.id, data["type"] as Channel["type"], String(data["name"]), data["parent_id"] as string ?? null); syncServer(server); return ch; }
        if (method === "PATCH" && Array.isArray(body)) { for (const item of body) Object.assign(channels.get(item.id) ?? {}, item); syncServer(server); }
        return server.channels;
      }
      if (parts[2] === "roles") {
        if (method === "POST") { const role: Role = { ...server.roles[0]!, id: id(), name: String(data["name"] ?? t("server.roles.newRole")), position: server.roles.length, permissions: "0", ...data }; server.roles.push(role); syncServer(server); return role; }
        const role = server.roles.find((r) => r.id === parts[3]);
        if (method === "PATCH" && role) { Object.assign(role, data); syncServer(server); return role; }
        if (method === "DELETE" && role) { server.roles = server.roles.filter((r) => r.id !== role.id); syncServer(server); return; }
        if (reading) return server.roles;
      }
      if (reading && parts[2] === "members") return { members: server.members, users: [{ ...me }], total: 1, has_more: false };
      if (reading && parts[2] === "threads") return { threads: [...channels.values()].filter((ch) => ch.server_id === server.id && ch.thread), users: [{ ...me }], has_more: false };
      if (reading && ["emojis", "stickers", "bans", "invites", "webhooks", "integrations", "automod"].includes(parts[2]!)) return [];
      if (reading && parts[2] === "audit-log") return { entries: [], users: [{ ...me }], has_more: false };
    }
    const ch = parts[0] === "channels" ? channels.get(parts[1]!) : undefined;
    if (ch) {
      if (parts.length === 2) {
        if (method === "PATCH") { Object.assign(ch, data); useChannels.getState().upsert({ ...ch }); return ch; }
        if (method === "DELETE") { channels.delete(ch.id); useChannels.getState().remove(ch.id); return; }
        if (reading) return ch;
      }
      if (parts[2] === "typing") return;
      if (parts[2] === "messages") {
        const list = history.get(ch.id) ?? []; history.set(ch.id, list);
        const message = list.find((m) => m.id === parts[3]);
        if (parts[4] === "ack") return { channel_id: ch.id, last_message_id: parts[3], mention_count: 0 };
        if (parts[4] === "reactions" && message) {
          const emoji = decodeURIComponent(parts[5]!);
          if (reading) return [me.id];
          const existing = message.reactions.find((r) => r.emoji === emoji);
          const add = method === "PUT";
          if (existing) { existing.count = add ? 1 : 0; existing.me = add; } else if (add) message.reactions.push({ emoji, count: 1, me: true });
          message.reactions = message.reactions.filter((r) => r.count > 0);
          useMessages.getState().setReaction(ch.id, message.id, emoji, me.id, add, true); return;
        }
        if (reading) return parts[3] ? message : [...list].reverse();
        if (method === "DELETE" && message) { history.set(ch.id, list.filter((m) => m.id !== message.id)); useMessages.getState().remove(ch.id, message.id); return; }
        if (method === "PATCH" && message) { Object.assign(message, data, { edited_at: Date.now() }); return message; }
        if (method === "POST" && parts.length === 3) {
          const msg: Message = { id: id(), channel_id: ch.id, author_id: me.id, type: data["reply_to_id"] ? "reply" : "default", ciphertext: null, key_conv_id: ch.key_conv_id, key_epoch: 1, reply_to_id: null, mentions: [], mention_roles: [], mention_everyone: false, attachments: [], reactions: [], flags: 0, edited_at: null, created_at: Date.now(), ...data };
          list.push(msg); ch.last_message_id = msg.id; useChannels.getState().setLastMessage(ch.id, msg.id); return msg;
        }
      }
      if (parts[2] === "pins") {
        if (reading) return (history.get(ch.id) ?? []).filter((m) => m.flags & 1);
        const msg = history.get(ch.id)?.find((m) => m.id === parts[3]); if (msg) { msg.flags = method === "PUT" ? msg.flags | 1 : msg.flags & ~1; useMessages.getState().setFlags(ch.id, msg.id, msg.flags); } return;
      }
      if (reading && parts[2] === "commands") return [];
      if (reading && ["threads", "posts"].includes(parts[2]!)) return { threads: [...channels.values()].filter((c) => c.parent_id === ch.id && c.thread), users: [{ ...me }], has_more: false };
    }
    if (parts[0] === "notes") {
      if (parts.length === 1) {
        if (reading) return { notes: [...notes.values()], invites: [] };
        if (method === "POST") { const note: NoteRow = { id: id(), owner_id: me.id, head: String(data["head"]), body: String(data["body"]), key_sealed: String(data["key_sealed"]), key_epoch: 1, version: 1, edited_by: me.id, created_at: Date.now(), updated_at: Date.now(), role: 3, members: 1, crdt_seq: 0, crdt_upto: 0 }; notes.set(note.id, note); return note; }
      }
      const note = notes.get(parts[1]!);
      if (note) {
        if (parts.length === 2) {
          if (reading) return note;
          if (method === "DELETE") { notes.delete(note.id); return; }
          if (method === "PUT") { const previous = { ...note, id: id(), note_id: note.id, saved_at: Date.now() }; versions.set(note.id, [previous, ...versions.get(note.id) ?? []]); note.head = String(data["head"]); note.body = String(data["body"]); note.crdt_upto = Number(data["upto"] ?? 0); note.version++; note.updated_at = Date.now(); return { version: note.version, updated_at: note.updated_at }; }
        }
        if (parts[2] === "presence") return;
        if (parts[2] === "sharing" && reading) return { members: [{ user: { ...me }, role: 3, created_at: note.created_at }], invites: [], links: [] };
        if (parts[2] === "versions" && reading) return parts[3] ? versions.get(note.id)?.find((v) => v.id === parts[3]) : { versions: versions.get(note.id) ?? [], keys: [] };
        if (parts[2] === "updates") {
          const list = updates.get(note.id) ?? []; updates.set(note.id, list);
          if (reading) return { updates: list.filter((u) => u.seq > Number(opts?.query?.["after"] ?? 0)), crdt_seq: note.crdt_seq ?? 0, crdt_upto: note.crdt_upto ?? 0, more: false };
          if (method === "POST") { const seq = (note.crdt_seq ?? 0) + 1; note.crdt_seq = seq; list.push({ seq, update: String(data["update"]), user_id: me.id, created_at: Date.now() }); return { seq, at: Date.now() }; }
        }
      }
    }
    // No permissive fallback: friends, discovery, invites, uploads, billing, security and integrations need an account.
    return accountRequired();
  }
  api.setToken(null);
  api.request = async <T>(method: string, path: string, body?: unknown, opts?: RequestOptions): Promise<T> => structuredClone(await request(method, path, body, opts)) as T;
  api.upload = async () => accountRequired();
  api.fetchImpl = window.fetch;
  api.XHRImpl = null;
  useAuth.setState({ status: "ready", me, token: null, identity, settingsKey: randomBytes(32) });
  useKeys.setState({ userId: me.id, identity });
  useUsers.getState().upsert(me);
  usePresence.getState().set({ user_id: me.id, status: "online", status_text: "", status_emoji: "", status_expires_at: null, activities: [] });
  useSettings.setState({ settings: { ...DEFAULT_SETTINGS, theme: "dark", whatsNewSeen: RELEASES[0]!.id, supportPromptSeen: RELEASES[0]!.id, searchIndex: false, spellcheck: false }, loaded: true, key: randomBytes(32) });
  useUI.setState({ connection: "open" });
  acceptTerms(me.id);
  return { me, request };
}
