/**
 * The Nexa plugin API, version 2: what `start(api)` is handed by a plugin that
 * says `apiVersion: 2`. Version 1 (api.ts) is still what every other plugin
 * gets. The reference for plugin authors is docs/plugins.md.
 *
 * Plugins run in the page with the person's full access. Permissions say what
 * a plugin means to do and stop it doing anything else through this API, but
 * they are not a sandbox.
 */

import type { PermissionName } from "./permissions";

export const PLUGIN_API_V2 = 2;

export type Id = string | bigint;

/** Thrown by a method a plugin calls without the permission it needs. */
export class PermissionError extends Error {
  readonly permission: string;
  constructor(permission: string, what: string) {
    super(`${what} needs the ${permission} permission`);
    this.name = "PermissionError";
    this.permission = permission;
  }
}

// --- users ----------------------------------------------------------------------

export type RelationshipKind = "none" | "friend" | "blocked" | "incomingRequest" | "outgoingRequest";

export interface ServerTag {
  serverId: string;
  text: string;
  /** As the server sends it: "icon:<name>" or "image:<id>". */
  badge: string;
  color: number | null;
}

export interface TypingStyle {
  /** Unicode emoji, a server's emoji as messages write it (<:name:id>), or a picture's URL. */
  emoji: string[];
  motion: string;
  words: string | null;
  preset: string | null;
}

export interface UserStatus {
  text: string;
  emoji: string;
  expiresAt: Date | null;
}

export interface User {
  id: string;
  username: string;
  displayName: string;
  pronouns: string;
  avatarUrl: string | null;
  bannerUrl: string | null;
  accentColor: number | null;
  accentColor2: number | null;
  aboutMe: string;
  status: UserStatus;
  serverTag: ServerTag | null;
  typingStyle: TypingStyle | null;
  publicKey: string;
  groupLinkOnly: boolean;
  flags: number;
  createdAt: Date;
  /** Null without users.relationships.read. */
  relationship: RelationshipKind | null;
  addFriend(): Promise<void>;
  removeFriend(): Promise<void>;
  block(): Promise<void>;
  unblock(): Promise<void>;
}

export interface SelfUser extends User {
  presencePref: string;
  dmPrivacy: string;
  hasRecovery: boolean;
  mfaEnabled: boolean;
  backupCodesLeft: number;
}

export interface EditableProfile {
  displayName: string;
  pronouns: string;
  aboutMe: string;
  accentColor: number;
  accentColor2: number;
  status: UserStatus;
  typingStyle: TypingStyle | null;
}

// --- servers --------------------------------------------------------------------

export interface ServerTagSettings {
  text: string;
  badge: string;
  color: number | null;
  limited: boolean;
  roleIds: string[];
  join: boolean;
}

export interface ServerDiscovery {
  enabled: boolean;
  category: string | null;
  tags: string[];
  language: string | null;
  since: Date | null;
  hidden: boolean;
}

export interface ServerVerification {
  accountAgeMinutes: number;
  memberAgeMinutes: number;
}

export interface Role {
  id: string;
  serverId: string;
  name: string;
  color: number;
  color2: number | null;
  color3: number | null;
  hoist: boolean;
  mentionable: boolean;
  position: number;
  permissions: bigint;
  iconUrl: string | null;
  /** As the server sends it: an emoji, "custom:<id>", or "". */
  iconEmoji: string;
  createdAt: Date;
}

export interface ServerMember {
  serverId: string;
  userId: string;
  nickname: string;
  avatarUrl: string | null;
  bannerUrl: string | null;
  bio: string;
  pronouns: string;
  joinedAt: Date;
  timeoutUntil: Date | null;
  flags: number;
  roleIds: string[];
}

export interface Server {
  id: string;
  name: string;
  description: string;
  iconUrl: string | null;
  bannerUrl: string | null;
  ownerId: string;
  systemChannelId: string | null;
  defaultNotifications: number;
  flags: number;
  createdAt: Date;
  tag: ServerTagSettings | null;
  invitesPausedUntil: Date | null;
  discovery: ServerDiscovery;
  verification: ServerVerification;
  automodAt: Date | null;
  memberCount: number;
  roles: Role[];
  me: ServerMember;
  myPermissions: bigint;
}

export interface ServerCreateOptions {
  name: string;
  description?: string;
}

export interface EditableServer {
  name: string;
  description: string;
  systemChannelId: string | null;
  defaultNotifications: number;
}

export interface RoleCreateOptions {
  name: string;
  color?: number;
  color2?: number;
  color3?: number;
  hoist?: boolean;
  mentionable?: boolean;
  permissions?: bigint;
  iconEmoji?: string;
}

export interface EditableRole {
  name: string;
  color: number;
  color2: number | null;
  color3: number | null;
  hoist: boolean;
  mentionable: boolean;
  position: number;
  permissions: bigint;
  iconEmoji: string;
}

export interface EditableMember {
  nickname: string;
  roleIds: string[];
  timeoutUntil: Date | null;
}

// --- channels -------------------------------------------------------------------

export type ChannelType = "text" | "voice" | "category" | "dm" | "group_dm" | "subchat" | "announcement" | "forum" | "thread" | "private_thread";

export interface ChannelOverwrite {
  channelId: string;
  targetId: string;
  targetType: "role" | "member";
  allow: bigint;
  deny: bigint;
}

export interface ForumTag {
  id: string;
  name: string;
  emoji: string;
  moderated: boolean;
}

export interface ForumSettings {
  tags: ForumTag[];
  defaultReaction: string;
  sort: string;
  layout: string;
  requireTag: boolean;
  pinnedPost: string | null;
}

export interface ThreadInfo {
  archived: boolean;
  archivedAt: Date | null;
  locked: boolean;
  autoArchive: number;
  starterId: string | null;
  messageCount: number;
  appliedTags: string[];
  joined: boolean;
}

export interface Channel {
  id: string;
  serverId: string | null;
  type: ChannelType;
  name: string;
  topic: string;
  position: number;
  parentId: string | null;
  nsfw: boolean;
  slowmodeSeconds: number;
  userLimit: number;
  ownerId: string | null;
  iconUrl: string | null;
  lastMessageId: string | null;
  createdAt: Date;
  recipientIds: string[];
  overwrites: ChannelOverwrite[];
  forum: ForumSettings | null;
  thread: ThreadInfo | null;
}

export type NewOverwrite = Omit<ChannelOverwrite, "channelId">;

export interface ChannelCreateOptions {
  serverId: string;
  type: "category" | "text" | "announcement" | "forum" | "voice";
  name: string;
  topic?: string;
  parentId?: string;
  nsfw?: boolean;
  overwrites?: NewOverwrite[];
}

export interface EditableChannel {
  name: string;
  topic: string;
  position: number;
  parentId: string | null;
  nsfw: boolean;
  slowmodeSeconds: number;
  userLimit: number;
  overwrites: NewOverwrite[];
  thread: Partial<{ archived: boolean; locked: boolean; autoArchive: number; appliedTags: string[] }>;
}

export interface ThreadCreateOptions {
  parentId: string;
  name: string;
  starterId?: string;
  content?: string;
  autoArchive?: number;
  appliedTags?: string[];
}

// --- messages -------------------------------------------------------------------

export interface Reaction {
  /** A unicode emoji, or a custom one as the server keeps it. */
  emoji: string;
  count: number;
  me: boolean;
}

export interface Attachment {
  name: string;
  /** Its MIME type, such as "image/png". */
  type: string;
  size: number;
  /** A picture's or video's size in pixels, when the sender's app said. */
  width: number | null;
  height: number | null;
  spoiler: boolean;
  /** A voice message or other recording: its length in seconds, when known. */
  duration: number | null;
  voice: boolean;
}

export interface Message {
  id: string;
  channelId: string;
  /** The server the channel belongs to, or null in direct messages and groups. */
  serverId: string | null;
  authorId: string;
  /** "default", "reply" or one of the "system_*" kinds. */
  type: string;
  /** Null when this device could not decrypt it. */
  content: string | null;
  replyToId: string | null;
  referencedMessage: Message | null;
  mentions: string[];
  mentionRoles: string[];
  mentionEveryone: boolean;
  reactions: Reaction[];
  attachments: Attachment[];
  sticker: { id: string; name: string } | null;
  /** Forwarded from another message: when that one was sent, and the forwarder's comment. */
  forwarded: { at: Date; comment: string | null } | null;
  /** Sent by a webhook or an app, not by a person's own app. */
  fromApp: boolean;
  pinned: boolean;
  flags: number;
  nonce: string | null;
  editedAt: Date | null;
  createdAt: Date;
}

export interface Emoji {
  id: string;
  serverId: string;
  name: string;
  animated: boolean;
  url: string;
  /** How a message writes it: <:name:id>, or <a:name:id> when it moves. */
  markdown: string;
}

export interface Presence {
  userId: string;
  status: "online" | "idle" | "dnd" | "offline";
  /** What they are doing, as the desktop app heard it from programs on their computer. */
  activities: { name: string; type: number; details: string | null; state: string | null }[];
}

export interface Location {
  /** The address inside the app, such as "/channels/@me". */
  path: string;
  serverId: string | null;
  channelId: string | null;
}

export interface PromptOptions {
  title: string;
  label?: string;
  placeholder?: string;
  initial?: string;
  maxLength?: number;
  confirmText?: string;
}

export interface SendOptions {
  content: string;
  replyToId?: string;
}

// --- events ---------------------------------------------------------------------

export interface PluginEventsV2 {
  messageCreated: Message;
  messageEdited: Message;
  messageDeleted: { id: string; channelId: string; message: Message | null };
  memberJoined: ServerMember;
  memberUpdated: ServerMember;
  memberLeft: { serverId: string; userId: string; member: ServerMember | null };
  serverJoined: Server;
  serverLeft: { id: string; server: Server | null };
  channelCreated: Channel;
  channelUpdated: Channel;
  channelDeleted: { id: string; serverId: string | null; channel: Channel | null };
  reactionAdded: { channelId: string; messageId: string; userId: string; emoji: string };
  reactionRemoved: { channelId: string; messageId: string; userId: string; emoji: string };
  userUpdated: User;
  relationshipChanged: { user: User | null; relationship: RelationshipKind; old: RelationshipKind };
  localeChanged: string;
  typingStarted: { channelId: string; userId: string };
  typingStopped: { channelId: string; userId: string };
  presenceUpdated: { userId: string; status: string };
  locationChanged: Location;
  messagePinned: { channelId: string; messageId: string };
  messageUnpinned: { channelId: string; messageId: string };
}

export type PluginEventNameV2 = keyof PluginEventsV2;

// --- commands and keybinds ------------------------------------------------------

/** Who ran a command or pressed a keybind (you), and where; each null without the permission to see it. */
export interface CommandContext {
  user: SelfUser | null;
  channel: Channel | null;
}

/** The same, for a keybind: the channel is the one on screen when it was pressed, or null. */
export type KeybindContext = CommandContext;

// --- the rest -------------------------------------------------------------------

export type SettingValue = string | number | boolean;

export interface ContextMenuContext {
  menu: string;
  message: Message | null;
  user: User | null;
  channel: Channel | null;
  server: Server | null;
}

export interface ContextMenuButton {
  id: string;
  label: string;
  onClick: (ctx: ContextMenuContext) => void;
  icon?: string;
  danger?: boolean;
  separatorAfter?: boolean;
  visible?: (ctx: ContextMenuContext) => boolean;
  before?: string;
  after?: string;
}

export interface ContextMenuChanges {
  label: string;
  icon: string | null;
  danger: boolean;
  separatorAfter: boolean;
  visible: (ctx: ContextMenuContext) => boolean;
  onClick: (ctx: ContextMenuContext, original: () => void) => void;
}

export interface ContextMenuItem {
  id: string;
  menu: string;
  label: string;
  icon: string | null;
  danger: boolean;
  separatorAfter: boolean;
  builtIn: boolean;
}

/** The permissions a v2 plugin can ask for. */
export type { PermissionName };
