import { inviteConsentQuery } from "@/lib/nsfw";
import { api, captchaOptions } from "./client";
import { t } from "@/i18n";
import type { AuditEntry, Ban, Channel, ForumSettings, Invite, InvitePreview, Member, MemberDetail, MemberRow, MeUser, PermString, PublicUser, Role, Server, ServerKeyInfo, ServerTagPreview, ServerView, Snowflake } from "./types";

/**
 * What accepting an invite gives back. A server invite answers with the server
 * view; a group invite answers with the group's channel instead, and the two
 * were told apart by casting one to the other through `unknown`.
 */
/** A server's own chosen invite code: never expiring or running out, found in any case. */
export interface VanityLink {
  code: string | null;
  /** How many people joined through it. */
  uses: number;
  channel_id: Snowflake | null;
}

export type JoinedInvite = { server: ServerView; users: PublicUser[] } | { channel: Channel; users?: PublicUser[] };

type Progress = (loaded: number, total: number) => void;

export interface ServerPatch {
  nsfw?: boolean;
  name?: string;
  description?: string;
  system_channel_id?: Snowflake | null;
  default_notifications?: number;
  invites_paused_until?: number | null;
  discoverable?: boolean;
  discovery_category?: string;
  discovery_tags?: string[];
  discovery_language?: string;
  verify_account_minutes?: number;
  verify_member_minutes?: number;
}

export interface ChannelPatch {
  name?: string;
  topic?: string;
  nsfw?: boolean;
  slowmode_seconds?: number;
  user_limit?: number;
  /** The channel's own icon (see lib/channelGlyph); "" puts the default back. */
  glyph?: string;
  /** Text and announcement channels turn into each other. */
  type?: "text" | "announcement";
  default_auto_archive?: number;
  forum?: Partial<Omit<ForumSettings, "tags">> & { tags: { id?: Snowflake; name: string; emoji: string; moderated: boolean }[] };
  /** Link channels: where it leads. */
  url?: string;
  /** Threads: */
  archived?: boolean;
  locked?: boolean;
  auto_archive?: number;
  applied_tags?: Snowflake[];
}

export interface ChannelPositionPatch {
  id: Snowflake;
  position: number;
  parent_id?: Snowflake | null;
}

export interface RoleBody {
  name?: string;
  color?: number;
  color2?: number | null;
  color3?: number | null;
  /** A single emoji, "custom:<id>" for one of the server's own, or "" for none. */
  icon_emoji?: string;
  hoist?: boolean;
  mentionable?: boolean;
  permissions?: PermString;
}

export interface MemberPatch {
  nick?: string | null;
  timeout_until?: number | null;
  roles?: Snowflake[];
  bio?: string;
  pronouns?: string;
  /** The profile's theme in this server (null uses the account's). */
  accent_color?: number | null;
  accent_color_2?: number | null;
  /** Widgets for this server alone (lib/widgets); null goes back to the account's. */
  widgets?: unknown[] | null;
  /** false lets a member out of AutoMod's quarantine. */
  quarantined?: false;
}

export interface MemberQuery {
  q?: string;
  roles?: Snowflake[];
  joined_after?: number;
  joined_before?: number;
  created_after?: number;
  created_before?: number;
  join_method?: string;
  join_code?: string;
  signals?: string[];
  sort?: "joined_desc" | "joined_asc" | "created_desc" | "created_asc";
  offset?: number;
  limit?: number;
}

export interface AuditQuery {
  before?: Snowflake;
  limit?: number;
  actions?: string[];
  actor_id?: Snowflake;
  target_id?: Snowflake;
}

function upload<T>(path: string, file: Blob, onProgress?: Progress): Promise<T> {
  const opts: { method: string; headers: Record<string, string>; onProgress?: Progress } = { method: "PUT", headers: { "Content-Type": file.type || "application/octet-stream" } };
  if (onProgress) opts.onProgress = onProgress;
  return api.upload<T>(path, file, opts);
}

type Crop = { x: number; y: number; w: number; h: number } | null;

/** The part framed in the picture's own pixels, as ?x=&y=&w=&h=; empty keeps the middle. */
function cropQuery(crop?: Crop): string {
  if (!crop) return "";
  return `?x=${Math.round(crop.x)}&y=${Math.round(crop.y)}&w=${Math.round(crop.w)}&h=${Math.round(crop.h)}`;
}

export interface DiscordTemplatePreviewChannel {
  id: number;
  type: "category" | "text" | "voice" | "announcement" | "forum" | "media";
  name: string;
  topic?: string;
  parent_index: number;
  private?: boolean;
}

export interface DiscordTemplatePreviewRole {
  name: string;
  color: number;
}

export interface DiscordTemplatePreview {
  code: string;
  name: string;
  description: string;
  usage_count: number;
  server_name: string;
  server_description: string;
  channels: DiscordTemplatePreviewChannel[];
  roles: DiscordTemplatePreviewRole[];
}

export const servers = {
  /**
   * The first categories and channels are records people can rename, not
   * interface text, so they are created in the language the maker is reading
   * rather than always in English.
   */
  create(name: string, captcha?: string | null): Promise<ServerView> {
    const defaults = {
      text_category: t("servers.defaults.textChannels"),
      voice_category: t("servers.defaults.voiceChannels"),
      text_channel: t("servers.defaults.general"),
      voice_channel: t("servers.defaults.generalVoice"),
    };
    return api.request<ServerView>("POST", "/servers", { name, defaults }, captchaOptions(captcha));
  },
  previewDiscordTemplate(code: string): Promise<DiscordTemplatePreview> {
    return api.request<DiscordTemplatePreview>("GET", `/servers/templates/discord/${encodeURIComponent(code)}`);
  },
  importDiscordTemplate(url: string, name?: string, captcha?: string | null): Promise<ServerView> {
    return api.request<ServerView>("POST", "/servers/import/discord", { url, ...(name ? { name } : {}) }, captchaOptions(captcha));
  },
  get(id: Snowflake): Promise<{ server: ServerView; users: PublicUser[] }> {
    return api.request("GET", `/servers/${id}`);
  },
  patch(id: Snowflake, body: ServerPatch): Promise<Server> {
    return api.request<Server>("PATCH", `/servers/${id}`, body);
  },
  delete(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}`);
  },
  uploadIcon(id: Snowflake, file: Blob, crop?: Crop, onProgress?: Progress): Promise<Server> {
    return upload<Server>(`/servers/${id}/icon${cropQuery(crop)}`, file, onProgress);
  },
  deleteIcon(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}/icon`);
  },
  uploadBanner(id: Snowflake, file: Blob, crop?: Crop, onProgress?: Progress): Promise<Server> {
    return upload<Server>(`/servers/${id}/banner${cropQuery(crop)}`, file, onProgress);
  },
  deleteBanner(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}/banner`);
  },
  leave(id: Snowflake): Promise<void> {
    return api.request<void>("POST", `/servers/${id}/leave`);
  },
  /** Sets the server's tag (lib/serverTags); the badge is "icon:<name>" or an "image:<id>" from uploadTagBadge. */
  setTag(id: Snowflake, body: { text: string; badge: string; color: number | null; limited: boolean; roles: Snowflake[]; join: boolean }): Promise<Server> {
    return api.request<Server>("PUT", `/servers/${id}/tag`, body);
  },
  deleteTag(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}/tag`);
  },
  /**
   * A picture for the tag's badge, sent whole with the part that was framed:
   * the server cuts it, and a moving picture stays moving.
   */
  uploadTagBadge(id: Snowflake, file: Blob, crop: { x: number; y: number; w: number; h: number } | null, onProgress?: Progress): Promise<{ badge: string; id: Snowflake; animated: boolean }> {
    const q = crop ? `?x=${crop.x}&y=${crop.y}&w=${crop.w}&h=${crop.h}` : "";
    const opts: { method: string; headers: Record<string, string>; onProgress?: Progress } = { method: "POST", headers: { "Content-Type": file.type || "application/octet-stream" } };
    if (onProgress) opts.onProgress = onProgress;
    return api.upload(`/servers/${id}/tag/badge${q}`, file, opts);
  },
  /** What someone's tag opens: the server it is from. */
  tagPreview(id: Snowflake): Promise<ServerTagPreview> {
    return api.request<ServerTagPreview>("GET", `/server-tags/${id}`);
  },
  /** Puts this server's tag on the account; null takes the account's tag off. */
  wearTag(id: Snowflake | null): Promise<MeUser> {
    return id ? api.request<MeUser>("PUT", "/users/@me/server-tag", { server_id: id }) : api.request<MeUser>("DELETE", "/users/@me/server-tag");
  },
  transfer(id: Snowflake, userId: Snowflake): Promise<Server> {
    return api.request<Server>("POST", `/servers/${id}/transfer`, { user_id: userId });
  },
  channels(id: Snowflake): Promise<Channel[]> {
    return api.request<Channel[]>("GET", `/servers/${id}/channels`);
  },
  createChannel(id: Snowflake, body: { type: "text" | "voice" | "category" | "announcement" | "forum" | "media" | "link"; name: string; parent_id?: Snowflake | null; url?: string }): Promise<Channel & { key_actions?: { needs_own_key: boolean; conv_id: Snowflake } }> {
    // key_actions: made in a private category, the channel needs its own key now
    return api.request("POST", `/servers/${id}/channels`, body);
  },
  setChannelPositions(id: Snowflake, list: ChannelPositionPatch[]): Promise<Channel[]> {
    return api.request<Channel[]>("PATCH", `/servers/${id}/channels`, list);
  },
  patchChannel(channelId: Snowflake, body: ChannelPatch): Promise<Channel> {
    return api.request<Channel>("PATCH", `/channels/${channelId}`, body);
  },
  deleteChannel(channelId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${channelId}`);
  },
  putOverwrite(channelId: Snowflake, targetId: Snowflake, body: { type: "role" | "member"; allow: PermString; deny: PermString }): Promise<{ overwrite: unknown; key_actions?: { needs_own_key: boolean; conv_id: Snowflake } }> {
    return api.request("PUT", `/channels/${channelId}/permissions/${targetId}`, body);
  },
  /** Gives a channel its category's overwrites (Sync Now). */
  syncPermissions(channelId: Snowflake): Promise<{ key_actions?: { needs_own_key: boolean; conv_id: Snowflake } }> {
    return api.request("POST", `/channels/${channelId}/permissions/sync`);
  },
  deleteOverwrite(channelId: Snowflake, targetId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${channelId}/permissions/${targetId}`);
  },
  roles(id: Snowflake): Promise<Role[]> {
    return api.request<Role[]>("GET", `/servers/${id}/roles`);
  },
  createRole(id: Snowflake, body: RoleBody): Promise<Role> {
    return api.request<Role>("POST", `/servers/${id}/roles`, body);
  },
  patchRole(id: Snowflake, roleId: Snowflake, body: RoleBody): Promise<Role> {
    return api.request<Role>("PATCH", `/servers/${id}/roles/${roleId}`, body);
  },
  deleteRole(id: Snowflake, roleId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}/roles/${roleId}`);
  },
  setRolePositions(id: Snowflake, list: { id: Snowflake; position: number }[]): Promise<Role[]> {
    return api.request<Role[]>("PATCH", `/servers/${id}/roles`, list);
  },
  members(id: Snowflake, query: { after?: Snowflake; limit?: number; q?: string } = {}): Promise<{ members: Member[]; users: PublicUser[] }> {
    const q: Record<string, string> = {};
    if (query.after) q["after"] = query.after;
    if (query.limit) q["limit"] = String(query.limit);
    if (query.q) q["q"] = query.q;
    return api.request("GET", `/servers/${id}/members`, undefined, { query: q });
  },
  member(id: Snowflake, userId: Snowflake): Promise<Member> {
    return api.request<Member>("GET", `/servers/${id}/members/${userId}`);
  },
  patchMember(id: Snowflake, userId: Snowflake | "@me", body: MemberPatch): Promise<Member> {
    return api.request<Member>("PATCH", `/servers/${id}/members/${userId}`, body);
  },
  kick(id: Snowflake, userId: Snowflake, reason?: string): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}/members/${userId}`, undefined, reason ? { query: { reason } } : {});
  },
  addRole(id: Snowflake, userId: Snowflake, roleId: Snowflake): Promise<Member> {
    return api.request<Member>("PUT", `/servers/${id}/members/${userId}/roles/${roleId}`);
  },
  removeRole(id: Snowflake, userId: Snowflake, roleId: Snowflake): Promise<Member> {
    return api.request<Member>("DELETE", `/servers/${id}/members/${userId}/roles/${roleId}`);
  },
  bans(id: Snowflake): Promise<{ bans: Ban[]; users: PublicUser[] }> {
    return api.request("GET", `/servers/${id}/bans`);
  },
  ban(id: Snowflake, userId: Snowflake, body: { reason?: string; delete_message_days?: number } = {}): Promise<void> {
    return api.request<void>("PUT", `/servers/${id}/bans/${userId}`, body);
  },
  unban(id: Snowflake, userId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${id}/bans/${userId}`);
  },
  invites(id: Snowflake): Promise<Invite[]> {
    return api.request<Invite[]>("GET", `/servers/${id}/invites`);
  },
  createInvite(channelId: Snowflake, body: { max_age?: number; max_uses?: number } = {}): Promise<Invite> {
    return api.request<Invite>("POST", `/channels/${channelId}/invites`, body);
  },
  invitePreview(code: string): Promise<InvitePreview> {
    return api.request<InvitePreview>("GET", `/invites/${encodeURIComponent(code)}`, undefined, { query: inviteConsentQuery(code) });
  },
  /** Accepts a server or group invite; the payload says which came back. */
  join(code: string, captcha?: string | null): Promise<JoinedInvite> {
    return api.request("POST", `/invites/${encodeURIComponent(code)}`, undefined, { ...captchaOptions(captcha), query: inviteConsentQuery(code) });
  },
  deleteInvite(code: string): Promise<void> {
    return api.request<void>("DELETE", `/invites/${encodeURIComponent(code)}`);
  },
  auditLog(id: Snowflake, query: AuditQuery = {}): Promise<{ entries: AuditEntry[]; users: PublicUser[] }> {
    const q: Record<string, string> = {};
    if (query.before) q["before"] = query.before;
    if (query.limit) q["limit"] = String(query.limit);
    if (query.actions?.length) q["action"] = query.actions.join(",");
    if (query.actor_id) q["actor_id"] = query.actor_id;
    if (query.target_id) q["target_id"] = query.target_id;
    return api.request("GET", `/servers/${id}/audit-log`, undefined, { query: q });
  },
  /** The server's vanity link (Manage Server). */
  vanity(id: Snowflake): Promise<VanityLink> {
    return api.request("GET", `/servers/${id}/vanity-url`);
  },
  /** Sets the vanity link's code, or takes the link away with null. */
  setVanity(id: Snowflake, code: string | null): Promise<VanityLink> {
    return api.request("PATCH", `/servers/${id}/vanity-url`, { code });
  },
  /** Revokes every invite the server has but its vanity link. */
  revokeAllInvites(id: Snowflake): Promise<{ revoked: number }> {
    return api.request("DELETE", `/servers/${id}/invites`);
  },
  /** The Members page: members filtered and sorted on the server. */
  searchMembers(id: Snowflake, query: MemberQuery = {}): Promise<{ members: MemberRow[]; total: number; users: PublicUser[]; join_codes?: string[] }> {
    const q: Record<string, string> = {};
    for (const [k, v] of Object.entries(query)) {
      if (v === undefined || v === "" || (Array.isArray(v) && v.length === 0)) continue;
      q[k] = Array.isArray(v) ? v.join(",") : String(v);
    }
    return api.request("GET", `/servers/${id}/member-search`, undefined, { query: q });
  },
  /** Mod View's facts about one member. */
  modView(id: Snowflake, userId: Snowflake): Promise<{ member: MemberDetail; audit?: AuditEntry[]; automod?: import("./types").AutomodEvent[]; users: PublicUser[] }> {
    return api.request("GET", `/servers/${id}/members/${userId}/mod`);
  },
  prunePreview(id: Snowflake, days: number, roles: Snowflake[] = []): Promise<{ count: number }> {
    const q: Record<string, string> = { days: String(days) };
    if (roles.length) q["roles"] = roles.join(",");
    return api.request("GET", `/servers/${id}/prune`, undefined, { query: q });
  },
  prune(id: Snowflake, days: number, roles: Snowflake[] = [], reason?: string): Promise<{ pruned: number }> {
    return api.request("POST", `/servers/${id}/prune`, { days, roles, ...(reason ? { reason } : {}) });
  },
  bulkBan(id: Snowflake, userIds: Snowflake[], body: { reason?: string; delete_message_days?: number } = {}): Promise<{ banned: Snowflake[]; failed: Snowflake[] }> {
    return api.request("POST", `/servers/${id}/bulk-ban`, { user_ids: userIds, ...body });
  },
  /** The caller's own picture or banner in one server ("@me"); a moderator may only remove someone else's. */
  uploadMemberImage(id: Snowflake, which: "avatar" | "banner", file: Blob, crop?: Crop, onProgress?: Progress): Promise<Member> {
    return upload<Member>(`/servers/${id}/members/@me/${which}${cropQuery(crop)}`, file, onProgress);
  },
  deleteMemberImage(id: Snowflake, userId: Snowflake | "@me", which: "avatar" | "banner"): Promise<Member> {
    return api.request<Member>("DELETE", `/servers/${id}/members/${userId}/${which}`);
  },
  uploadRoleIcon(id: Snowflake, roleId: Snowflake, file: Blob, crop?: Crop, onProgress?: Progress): Promise<Role> {
    return upload<Role>(`/servers/${id}/roles/${roleId}/icon${cropQuery(crop)}`, file, onProgress);
  },
  deleteRoleIcon(id: Snowflake, roleId: Snowflake): Promise<Role> {
    return api.request<Role>("DELETE", `/servers/${id}/roles/${roleId}/icon`);
  },
  keys(id: Snowflake): Promise<ServerKeyInfo[]> {
    return api.request<ServerKeyInfo[]>("GET", `/servers/${id}/keys`);
  },
};
