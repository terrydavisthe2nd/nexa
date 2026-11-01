/**
 * The app's objects as the v2 plugin API hands them over: ids as strings,
 * times as Dates, permission bits as bigints, pictures as their URLs, and
 * frozen, since every plugin is handed the same one. What a plugin may not
 * see is left out by the caller; the few members that depend on a permission
 * here (a user's relationship, and what can be done to them) ask `can`.
 */
import type { Channel as WireChannel, Member, MeUser, PublicUser, Relationship, Role as WireRole, Server as WireServer, ServerTag as WireServerTag, WornTag } from "@/api/types";
import { avatarUrl, bannerUrl, iconUrl } from "@/api/users";
import { api } from "@/api/client";
import type { DecryptedMessage } from "@/lib/decrypt";
import { relationships as relApi } from "@/api/relationships";
import { acceptFriendRequest, blockUser, dropFriendRequest, sendFriendRequest, unblockUser } from "@/lib/friendship";
import { useSettings } from "@/stores/settings";
import { parsePerm } from "@/lib/perms";
import { readTypingStyle } from "@/lib/typingStyle";
import { useAuth } from "@/stores/auth";
import { useRelationships } from "@/stores/relationships";
import { useReplyTargets } from "@/stores/replyTargets";
import { useMessages } from "@/stores/messages";
import type { ServerState } from "@/stores/servers";
import { useThreads } from "@/stores/threads";
import { useChannels } from "@/stores/channels";
import type { Attachment, Channel, Message, RelationshipKind, Role, Server, ServerMember, ServerTag, SelfUser, TypingStyle, User } from "./api2";
import { PermissionError } from "./api2";
import type { PermissionName } from "./permissions";

/** Asks whether the plugin holds a permission now. */
export type Can = (p: PermissionName) => boolean;

const date = (ms: number | null | undefined): Date | null => (typeof ms === "number" && ms > 0 ? new Date(ms) : null);
const freeze = <T extends object>(x: T): T => Object.freeze(x);

export function relationshipName(type: Relationship["type"] | "none" | undefined): RelationshipKind {
  return type === "incoming" ? "incomingRequest" : type === "outgoing" ? "outgoingRequest" : type === "friend" || type === "blocked" ? type : "none";
}

function serverTag(t: WornTag | null | undefined): ServerTag | null {
  return t ? freeze({ serverId: t.server_id, text: t.text, badge: t.badge, color: t.color }) : null;
}

function typingStyle(raw: unknown): TypingStyle | null {
  const s = readTypingStyle(raw);
  if (!s) return null;
  // a picture of the person's own is its address, as every other picture is
  const emoji = s.emoji.map((e) => (e.startsWith("img:") ? `${api.baseUrl}/media/typing/${e.slice(4)}` : e));
  return freeze({ emoji: Object.freeze(emoji) as string[], motion: s.motion, words: s.words ?? null, preset: s.words ? null : (s.preset ?? null) });
}

/** What a user's relationship buttons do, each checked as it is pressed. */
function relationshipActions(u: PublicUser, can: Can) {
  const check = (what: string) => {
    if (!can("users.relationships.write")) throw new PermissionError("users.relationships.write", what);
    if (u.id === useAuth.getState().me?.id) throw new Error(`${what} is about other people, not yourself`);
  };
  const kind = () => useRelationships.getState().byUser[u.id]?.type;
  return {
    async addFriend() {
      check("addFriend");
      if (kind() === "incoming") await acceptFriendRequest(u);
      else if (!kind()) await sendFriendRequest(u);
    },
    async removeFriend() {
      check("removeFriend");
      const k = kind();
      if (k === "friend") {
        // what the app's Remove Friend does once it has asked; a plugin asks first if it wants to
        await relApi.remove(u.id);
        useRelationships.getState().remove(u.id);
      }
      else if (k === "incoming" || k === "outgoing") await dropFriendRequest(u.id);
    },
    async block() {
      check("block");
      // with the account's own choices about a blocked person's messages, as the block dialog starts from
      await blockUser(u, useSettings.getState().settings.blocking);
    },
    async unblock() {
      check("unblock");
      await unblockUser(u.id);
    },
  };
}

export function toUser(u: PublicUser, can: Can): User {
  return freeze({
    id: u.id,
    username: u.username,
    displayName: u.display_name || u.username,
    pronouns: u.pronouns ?? "",
    avatarUrl: avatarUrl(u.avatar_id),
    bannerUrl: bannerUrl(u.banner_id),
    accentColor: u.accent_color ?? null,
    accentColor2: u.accent_color_2 ?? null,
    aboutMe: u.about_me ?? "",
    status: freeze({ text: u.status_text ?? "", emoji: u.status_emoji ?? "", expiresAt: date(u.status_expires_at) }),
    serverTag: serverTag(u.server_tag),
    typingStyle: typingStyle(u.typing_style),
    publicKey: u.public_key ?? "",
    groupLinkOnly: !!u.group_link_only,
    flags: u.flags ?? 0,
    createdAt: new Date(u.created_at ?? 0),
    relationship: can("users.relationships.read") ? relationshipName(useRelationships.getState().byUser[u.id]?.type) : null,
    ...relationshipActions(u, can),
  });
}

export function toSelfUser(me: MeUser, can: Can): SelfUser {
  return freeze({
    ...toUser(me, can),
    presencePref: me.presence_pref,
    dmPrivacy: me.dm_privacy,
    hasRecovery: !!me.has_recovery,
    mfaEnabled: !!me.mfa_enabled,
    backupCodesLeft: me.backup_codes_left ?? 0,
  });
}

export function toRole(r: WireRole): Role {
  return freeze({
    id: r.id,
    serverId: r.server_id,
    name: r.name,
    color: r.color ?? 0,
    color2: r.color2 ?? null,
    color3: r.color3 ?? null,
    hoist: !!r.hoist,
    mentionable: !!r.mentionable,
    position: r.position,
    permissions: parsePerm(r.permissions),
    iconUrl: iconUrl(r.icon_id),
    iconEmoji: r.icon_emoji ?? "",
    createdAt: new Date(r.created_at ?? 0),
  });
}

export function toMember(m: Member): ServerMember {
  return freeze({
    serverId: m.server_id,
    userId: m.user_id,
    nickname: m.nickname ?? "",
    avatarUrl: avatarUrl(m.avatar_id),
    bannerUrl: bannerUrl(m.banner_id ?? null),
    bio: m.bio ?? "",
    pronouns: m.pronouns ?? "",
    joinedAt: new Date(m.joined_at ?? 0),
    timeoutUntil: m.timeout_until && m.timeout_until > Date.now() ? new Date(m.timeout_until) : null,
    flags: m.flags ?? 0,
    roleIds: Object.freeze([...(m.role_ids ?? [])]) as string[],
  });
}

function tagSettings(t: WireServerTag | null | undefined): Server["tag"] {
  return t ? freeze({ text: t.text, badge: t.badge, color: t.color, limited: !!t.limited, roleIds: Object.freeze([...(t.roles ?? [])]) as string[], join: !!t.join }) : null;
}

export function toServerFrom(s: WireServer, extra: { roles: WireRole[]; me: Member; memberCount: number; myPermissions: bigint }): Server {
  return freeze({
    id: s.id,
    name: s.name,
    description: s.description ?? "",
    iconUrl: iconUrl(s.icon_id),
    bannerUrl: bannerUrl(s.banner_id),
    ownerId: s.owner_id,
    systemChannelId: s.system_channel_id ?? null,
    defaultNotifications: s.default_notifications ?? 0,
    flags: s.flags ?? 0,
    createdAt: new Date(s.created_at ?? 0),
    tag: tagSettings(s.tag),
    invitesPausedUntil: s.invites_paused_until && s.invites_paused_until > Date.now() ? new Date(s.invites_paused_until) : null,
    discovery: freeze({
      enabled: !!s.discoverable,
      category: s.discovery_category || null,
      tags: Object.freeze([...(s.discovery_tags ?? [])]) as string[],
      language: s.discovery_language || null,
      since: date(s.discovery_since),
      hidden: !!s.discovery_hidden,
    }),
    verification: freeze({ accountAgeMinutes: s.verify_account_minutes ?? 0, memberAgeMinutes: s.verify_member_minutes ?? 0 }),
    automodAt: date(s.automod_at),
    memberCount: extra.memberCount,
    roles: Object.freeze([...extra.roles].sort((a, b) => b.position - a.position).map(toRole)) as Role[],
    me: toMember(extra.me),
    myPermissions: extra.myPermissions,
  });
}

export function toServer(st: ServerState): Server {
  return toServerFrom(st.server, { roles: Object.values(st.roles), me: st.me, memberCount: st.memberCount, myPermissions: st.myPermissions });
}

export function toChannel(c: WireChannel): Channel {
  const th = c.thread;
  const f = c.forum;
  return freeze({
    id: c.id,
    serverId: c.server_id ?? null,
    type: c.type,
    name: c.name ?? "",
    topic: c.topic ?? "",
    position: c.position ?? 0,
    parentId: c.parent_id ?? null,
    nsfw: !!c.nsfw,
    slowmodeSeconds: c.slowmode_seconds ?? 0,
    userLimit: c.user_limit ?? 0,
    ownerId: c.owner_id ?? null,
    iconUrl: iconUrl(c.icon_id),
    lastMessageId: c.last_message_id ?? null,
    createdAt: new Date(c.created_at ?? 0),
    recipientIds: Object.freeze([...(c.recipient_ids ?? [])]) as string[],
    overwrites: Object.freeze((c.overwrites ?? []).map((o) => freeze({ channelId: o.channel_id, targetId: o.target_id, targetType: o.target_type, allow: parsePerm(o.allow), deny: parsePerm(o.deny) }))) as Channel["overwrites"],
    forum: f
      ? freeze({
          tags: Object.freeze(f.tags.map((t) => freeze({ id: t.id, name: t.name, emoji: t.emoji, moderated: t.moderated }))) as NonNullable<Channel["forum"]>["tags"],
          defaultReaction: f.default_reaction ?? "",
          sort: f.sort,
          layout: f.layout,
          requireTag: !!f.require_tag,
          pinnedPost: f.pinned_post ?? null,
        })
      : null,
    thread: th
      ? freeze({
          archived: !!th.archived,
          archivedAt: date(th.archived_at),
          locked: !!th.locked,
          autoArchive: th.auto_archive,
          starterId: th.starter_id ?? null,
          messageCount: th.message_count ?? 0,
          appliedTags: Object.freeze([...(th.applied_tags ?? [])]) as string[],
          joined: !!useThreads.getState().joined[c.id],
        })
      : null,
  });
}

/** What a reply points at, one level deep: the message as this device has it, or null. */
function referenced(m: DecryptedMessage): DecryptedMessage | null {
  if (!m.reply_to_id) return null;
  const loaded = useMessages.getState().channels[m.channel_id]?.byId[m.reply_to_id];
  if (loaded) return loaded;
  const target = useReplyTargets.getState().byId[m.reply_to_id];
  return target && typeof target === "object" ? target : null;
}

function toAttachment(a: NonNullable<NonNullable<DecryptedMessage["payload"]>["att"]>[number]): Attachment {
  return freeze({
    name: a.name,
    type: a.type,
    size: a.size,
    width: typeof a.w === "number" ? a.w : null,
    height: typeof a.h === "number" ? a.h : null,
    spoiler: !!a.spoiler,
    duration: typeof a.duration === "number" ? a.duration : null,
    voice: a.voice === true,
  });
}

export function toMessage(m: DecryptedMessage, depth = 0): Message {
  const ref = depth === 0 ? referenced(m) : null;
  const p = m.payload;
  return freeze({
    id: m.id,
    channelId: m.channel_id,
    serverId: useChannels.getState().byId[m.channel_id]?.server_id ?? null,
    authorId: m.author_id,
    type: m.type,
    content: m.payload ? (m.payload.t ?? "") : null,
    replyToId: m.reply_to_id ?? null,
    referencedMessage: ref ? toMessage(ref, 1) : null,
    mentions: Object.freeze([...(m.mentions ?? [])]) as string[],
    mentionRoles: Object.freeze([...(m.mention_roles ?? [])]) as string[],
    mentionEveryone: !!m.mention_everyone,
    reactions: Object.freeze((m.reactions ?? []).map((r) => freeze({ emoji: r.emoji, count: r.count, me: !!r.me }))) as Message["reactions"],
    attachments: Object.freeze((p?.att ?? []).map(toAttachment)) as Attachment[],
    sticker: p?.sticker ? freeze({ id: p.sticker.id, name: p.sticker.name ?? "" }) : null,
    forwarded: p?.fwd ? freeze({ at: new Date(p.fwd.at), comment: p.fwd.comment ?? null }) : null,
    fromApp: !!m.hook,
    pinned: ((m.flags ?? 0) & 1) !== 0,
    flags: m.flags ?? 0,
    nonce: m.nonce ?? null,
    editedAt: date(m.edited_at),
    createdAt: new Date(m.created_at ?? 0),
  });
}
