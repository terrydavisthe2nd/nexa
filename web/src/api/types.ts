/**
 * Wire types for the chatd REST API and gateway.
 *
 * The server's conventions, once, so nothing downstream has to guess: every id
 * is a decimal string, every timestamp is Unix milliseconds, and every binary
 * field (`public_key`, `enc_private_key`, `kdf_salt`, `wrapped_key`, `blob`) is
 * standard base64.
 */

import type { NexaState } from "./moderation";
import type { Envelope } from "@/crypto/message";

/** A 64-bit id, rendered as a decimal string so it survives JSON. */
export type Snowflake = string;

/**
 * One badge on a profile (internal/store/badges.go), in its holder's order.
 * "staff" and "plan" carry nothing more: they are drawn from the account.
 */
export interface Badge {
  /** "staff", "plan", "og", "years", "bug_hunter", "app_developer", "gift_giver", "contributor" or "event:<id>". */
  id: string;
  /** Gift Giver's tier (1 bronze, 2 silver, 3 gold), or the years on Nexa. */
  level?: number;
  /** An event badge's name, Lucide icon and colour. */
  name?: string;
  icon?: string;
  color?: number;
  /** Only in the person's own list: they hid it from their profile. */
  hidden?: boolean;
}

/** A badge staff made for an event, with how many hold it. */
export interface BadgeDef {
  id: Snowflake;
  name: string;
  icon: string;
  color: number;
  created_by: Snowflake;
  created_at: number;
  holders: number;
}

export interface PublicUser {
  id: Snowflake;
  username: string;
  display_name: string;
  avatar_id: Snowflake | null;
  banner_id: Snowflake | null;
  about_me: string;
  pronouns: string;
  accent_color: number | null;
  /** Second colour: when set, the profile banner is a gradient between the two. */
  accent_color_2: number | null;
  status_text: string;
  status_emoji: string;
  status_expires_at: number | null;
  /** base64 X25519 public key. */
  public_key: string;
  flags: number;
  /**
   * Joins group chats only from an invite link ("Let friends add me to group
   * chats" is off): friends see them as Link Only and cannot add them directly.
   */
  group_link_only?: boolean;
  created_at: number;
  /** How "is typing…" looks for them (lib/typingStyle), or null for the plain one. Read with readTypingStyle. */
  typing_style?: unknown;
  /** How their display name is drawn (lib/nameStyle), or null for the plain name. Read with readNameStyle. */
  name_style?: unknown;
  /** The server tag they wear beside their name, or null. */
  server_tag?: WornTag | null;
  /** Their subscription, shown as a badge: "" (none), "supporter" or "pro". Absent from servers before plans. */
  plan?: Plan;
}

/** A subscription: "" is the free plan. */
export type Plan = "" | "supporter" | "pro";

/** What one plan allows (api/billing.go planLimits). */
export interface PlanLimits {
  file_max: number;
  /** How much may be uploaded in any 30 days; each upload counts until it is that old. */
  month_upload_max: number;
  /** The Quality share mode (the screen's own size). */
  share_quality: boolean;
  share_max_bps: number;
  /** The name effects only plans have. */
  name_effects: boolean;
  boosts: number;
}

/** Every plan's limits, from the instance. */
export interface PlansInfo {
  /** Subscriptions can be bought here. */
  enabled: boolean;
  free: PlanLimits;
  supporter: PlanLimits;
  pro: PlanLimits;
  /** The largest file anyone may send in a boosted server. */
  boost_file_max: number;
}

/** One of an account's boosts, with the server it is on. */
export interface Boost {
  id: Snowflake;
  server_id: Snowflake;
  server_name: string;
  server_icon_id: Snowflake | null;
  created_at: number;
}

/** A price as Stripe has it: the smallest unit of the currency. */
export interface PlanPrice {
  amount: number;
  currency: string;
}

/** An account's side of its subscription (GET /billing). */
export interface Billing {
  enabled: boolean;
  plan: Plan;
  limits: PlanLimits;
  /** What was uploaded in the last 30 days. */
  month_used: number;
  /** When the first of that room comes back (Unix ms); 0 when nothing counts. */
  month_frees_at: number;
  boosts: Boost[];
  period: "" | "month" | "year";
  /** Stripe's word for it: active, past_due, canceled... */
  status: string;
  /** When it renews, or ends when cancel_at_end (Unix ms); 0 for none. */
  period_end: number;
  cancel_at_end: boolean;
  /** There is a Stripe customer to open the portal for. */
  manageable: boolean;
  prices: Partial<Record<"supporter" | "pro", Partial<Record<"month" | "year", PlanPrice>>>>;
  /** What a month or a year of a plan costs as a gift, paid once. */
  gift_prices: Partial<Record<"supporter" | "pro", Partial<Record<"month" | "year", PlanPrice>>>>;
  /** Plans given to this account that haven't run out, soonest first. */
  gifts: PlanGift[] | null;
  /** Gift links this account bought, newest first (absent on a server from before them). */
  gift_links?: GiftLink[] | null;
}

/** A plan bought as a link, which whoever opens it first claims (store.GiftLink). */
export interface GiftLink {
  code: string;
  plan: Plan;
  days: number;
  created_at: number;
  claimed: boolean;
  claimed_by: Snowflake;
  claimed_name: string;
  claimed_at: number;
}

/** A gift link as someone who opened it sees it (api/billing.go giftLinkView). */
export interface GiftLinkView {
  code: string;
  from_id: Snowflake;
  from_name: string;
  plan: Plan;
  days: number;
  claimed: boolean;
  claimed_by_me: boolean;
  /** This account bought it. */
  mine: boolean;
}

/** A plan someone gave this account, for a month or a year (store.Gift). */
export interface PlanGift {
  from_id: Snowflake;
  from_name: string;
  plan: Plan;
  starts_at: number;
  ends_at: number;
}

/** A server's tag as someone wears it: its letters, its badge and its colour. */
export interface WornTag {
  server_id: Snowflake;
  text: string;
  /** "icon:<name>" for a badge every server has, "image:<id>" for the server's own picture. */
  badge: string;
  /** 0xRRGGBB, or null for the accent colour. */
  color: number | null;
}

/** The extra fields the server only ever hands to the account's owner. */
export interface MeUser extends PublicUser {
  presence_pref: "online" | "idle" | "dnd" | "invisible";
  /** Who may start a direct message with the account; "friends_servers" (friends and people in a server with it) is the default. */
  dm_privacy: "everyone" | "friends_servers" | "friends" | "nobody";
  has_recovery: boolean;
  /** An authenticator app is required to sign in. */
  mfa_enabled?: boolean;
  backup_codes_left?: number;
  /** The account's moderation standing: a mute or ban in force, notices to show. */
  nexa?: NexaState;
}

export type User = PublicUser;

export interface Session {
  id: Snowflake;
  user_agent: string;
  ip: string;
  created_at: number;
  last_seen_at: number;
  current: boolean;
}

export type ChannelType = "text" | "voice" | "category" | "dm" | "group_dm" | "subchat" | "announcement" | "forum" | "media" | "link" | "thread" | "private_thread";

export interface Channel {
  id: Snowflake;
  server_id: Snowflake | null;
  type: ChannelType;
  name: string;
  topic: string;
  position: number;
  parent_id: Snowflake | null;
  nsfw: boolean;
  slowmode_seconds: number;
  user_limit: number;
  owner_id: Snowflake | null;
  icon_id: Snowflake | null;
  last_message_id: Snowflake | null;
  created_at: number;
  recipient_ids: Snowflake[];
  /** The conversation whose keys encrypt this channel: itself for DMs and private channels, the server otherwise. */
  key_conv_id: Snowflake;
  /** Permission overwrites; only present on server channels. */
  overwrites?: Overwrite[];
  /** A subchat that asks for its password before it opens. */
  locked?: boolean;
  /** A server channel's own icon in place of its # or speaker (see lib/channelGlyph); absent for none. */
  glyph?: string;
  /** How a thread stands (types thread and private_thread). */
  thread?: ThreadMeta;
  /** A forum or media channel's own settings. */
  forum?: ForumSettings;
  /** Where a link channel leads: a whole http or https address. */
  url?: string;
  /** On a channel threads start in: how many quiet minutes close a new thread (absent: three days). */
  default_auto_archive?: number;
  /** On a thread, as listed or announced: how many people are in it. */
  member_count?: number;
}

export interface ThreadMeta {
  archived: boolean;
  archived_at: number | null;
  locked: boolean;
  /** Minutes without messages after which it closes itself. */
  auto_archive: number;
  /** The message it was started from, if any. */
  starter_id: Snowflake | null;
  message_count: number;
  /** A forum post's tags. */
  applied_tags: Snowflake[];
}

export interface ForumTag {
  id: Snowflake;
  name: string;
  /** A single emoji, or "" for none. */
  emoji: string;
  /** Only people who manage threads may put it on a post. */
  moderated: boolean;
}

export interface ForumSettings {
  tags: ForumTag[];
  default_reaction: string;
  sort: "activity" | "created";
  layout: "list" | "gallery";
  require_tag: boolean;
  pinned_post: Snowflake | null;
  /** A media channel whose pictures and videos offer no download buttons. */
  hide_downloads?: boolean;
}

/** A thread as a message it started from shows it. */
export interface ThreadSummary {
  id: Snowflake;
  name: string;
  message_count: number;
  last_message_id: Snowflake | null;
  archived: boolean;
  locked: boolean;
  /** The newest thing said in a public thread, sealed as it was sent (absent for a private thread, and from older servers). */
  last_message?: Message;
}

/** A thread in a channel's list of them, or a forum's posts. */
export interface ThreadListing extends Channel {
  member_count: number;
  joined: boolean;
  member_ids: Snowflake[];
  /** A forum post's first message: what the post says. */
  first_message?: Message;
}

/** Permission bits travel as decimal strings; `lib/perms` turns them into BigInt. */
export type PermString = string;

export interface Overwrite {
  channel_id: Snowflake;
  target_id: Snowflake;
  target_type: "role" | "member";
  allow: PermString;
  deny: PermString;
}

export interface Server {
  nsfw?: boolean;
  id: Snowflake;
  name: string;
  icon_id: Snowflake | null;
  banner_id: Snowflake | null;
  owner_id: Snowflake;
  description: string;
  system_channel_id: Snowflake | null;
  default_notifications: number;
  flags: number;
  created_at: number;
  /** The tag its members can wear beside their name (lib/serverTags), or null. */
  tag?: ServerTag | null;
  /** When the server takes new members again; null while it takes them. */
  invites_paused_until?: number | null;
  /** How it is listed on Discovery. */
  discoverable?: boolean;
  discovery_category?: string;
  discovery_tags?: string[];
  discovery_language?: string;
  discovery_since?: number | null;
  /** Taken off Discovery by Nexa's staff. */
  discovery_hidden?: boolean;
  /** How old an account, and how long a membership, has to be to talk here. */
  verify_account_minutes?: number;
  verify_member_minutes?: number;
  /** When the AutoMod rules members' apps apply last changed; 0 when there are none. */
  automod_at?: number;
  /** How many Pro accounts boost it; one or more lets every member send bigger files. */
  boosts?: number;
}

/** A server's tag, as its members and admins see it. */
export interface ServerTag {
  text: string;
  /** "icon:<name>" for a badge every server has, "image:<id>" for the server's own picture. */
  badge: string;
  /** 0xRRGGBB, or null for the accent colour. */
  color: number | null;
  /** Kept to members with one of `roles` (and whoever runs the server). */
  limited: boolean;
  roles: Snowflake[];
  /** Whoever opens the tag can join the server from it. */
  join: boolean;
}

/** What opening someone's tag shows: the server it is from, and what the viewer can do there. */
export interface ServerTagPreview {
  server: { id: Snowflake; name: string; icon_id: Snowflake | null; banner_id: Snowflake | null; description: string; member_count: number; online_count: number };
  tag: { text: string; badge: string; color: number | null };
  member: boolean;
  /** For a member: whether they may wear it. */
  can_wear?: boolean;
  /** For someone outside it: the invite to join through, when the server lets people join from its tag. */
  invite?: string;
}

export interface Member {
  server_id: Snowflake;
  user_id: Snowflake;
  /** The member's profile in this server, shown there in place of their own. */
  nickname: string;
  avatar_id: Snowflake | null;
  banner_id?: Snowflake | null;
  bio?: string;
  pronouns?: string;
  /** The profile's theme in this server; absent uses the account's. */
  accent_color?: number | null;
  accent_color_2?: number | null;
  joined_at: number;
  timeout_until: number | null;
  /** MEMBER_QUARANTINED, and more to come. */
  flags: number;
  role_ids: Snowflake[];
}

/** A member AutoMod holds for what their name or profile says. */
export const MEMBER_QUARANTINED = 1;

/** How someone joined a server, for those who see its invites. */
export interface MemberJoin {
  join_method: "invite" | "discovery" | "tag" | "owner" | "";
  join_code: string;
  invited_by: Snowflake | null;
}

/** A member as the Members page lists them. */
export interface MemberRow extends Member {
  account_created_at: number;
  signals: MemberSignal[];
  join?: MemberJoin;
}

export type MemberSignal = "timed_out" | "quarantined" | "new_account" | "unusual_dms";

/** What Mod View shows of one member. */
export interface MemberDetail extends MemberRow {
  message_count: number;
  last_seen: number | null;
}

export interface Role {
  id: Snowflake;
  server_id: Snowflake;
  name: string;
  color: number;
  hoist: boolean;
  mentionable: boolean;
  position: number;
  permissions: PermString;
  /** A picture of its own, or null; and an emoji ("custom:<id>" for one of the server's), or "". */
  icon_id: Snowflake | null;
  icon_emoji?: string;
  /** With a second colour the name is a gradient; with a third, a shimmer across all three. */
  color2?: number | null;
  color3?: number | null;
  created_at: number;
}

/** A server as the ready payload, create and join responses carry it. */
export interface ServerView extends Server {
  channels: Channel[];
  /** Threads still going that this member can see, and the ones they are in. */
  threads?: Channel[];
  joined_threads?: Snowflake[];
  roles: Role[];
  members: Member[];
  member_count: number;
  me: Member;
  my_permissions: PermString;
}

export interface Invite {
  code: string;
  /** Null for a group DM invite, where channel_id carries the group. */
  server_id: Snowflake | null;
  channel_id: Snowflake | null;
  inviter_id: Snowflake;
  max_uses: number;
  uses: number;
  expires_at: number | null;
  created_at: number;
}

export interface InvitePreview {
  nsfw?: boolean;
  age_gate?: boolean;
  code: string;
  /** Present for a server invite. */
  server?: { id: Snowflake; name: string; icon_id: Snowflake | null; banner_id: Snowflake | null; description: string; member_count: number; online_count: number };
  /** Present for a group DM invite. */
  group?: { id: Snowflake; name: string; icon_id: Snowflake | null; member_count: number };
  channel?: { id: Snowflake; name: string; type: Channel["type"] };
  inviter?: PublicUser;
  /** The server's vanity link, which nobody in particular gave out (no inviter then). */
  vanity?: boolean;
  /** Omitted while the invite preview is age-gated. */
  expires_at?: number | null;
  /** The server takes nobody new right now. */
  joins_paused?: boolean;
}

export interface Ban {
  server_id: Snowflake;
  user_id: Snowflake;
  reason: string;
  banned_by: Snowflake;
  created_at: number;
}

export interface AuditEntry {
  id: Snowflake;
  server_id: Snowflake;
  actor_id: Snowflake;
  action: string;
  target_type: string;
  target_id: Snowflake | null;
  changes: unknown;
  reason: string;
  created_at: number;
}

export interface Emoji {
  id: Snowflake;
  server_id: Snowflake;
  name: string;
  blob_id: Snowflake;
  animated: boolean;
  creator_id: Snowflake;
  created_at: number;
}

export interface Sticker {
  id: Snowflake;
  pack_id: Snowflake;
  name: string;
  tags: string;
  blob_id: Snowflake;
  format: "png" | "apng" | "gif" | "webp" | "tgs" | "webm";
  position: number;
  creator_id: Snowflake;
  created_at: number;
  width?: number;
  height?: number;
  animated: boolean;
}

export interface DiscoverPacksPage {
  packs: StickerPack[];
  has_more: boolean;
  next_offset?: number;
}

export interface StickerPack {
  id: Snowflake;
  /** "sticker" for the big ones you post, "emoji" for the small ones you type. */
  kind: "sticker" | "emoji";
  owner_user_id: Snowflake | null;
  server_id: Snowflake | null;
  name: string;
  description: string;
  cover_sticker_id: Snowflake | null;
  public: boolean;
  created_at: number;
  updated_at: number;
  stickers: Sticker[];
  installed: boolean;
  /** How many the whole pack holds, where `stickers` is only a preview (Discovery). */
  sticker_count?: number;
  /** How many people installed it, where someone is deciding whether to. */
  install_count?: number;
  /** Who made it, where a pack is looked at on its own. */
  owner?: PublicUser;
}

/** Where a sticker seen in a message comes from: only ever a public pack. */
export interface StickerInfo {
  sticker: Sticker;
  pack: StickerPack;
  /** Whoever made the pack's stickers. */
  users: PublicUser[];
}

export interface GifResult {
  id: string;
  url: string;
  preview: string;
  w: number;
  h: number;
  title: string;
}

export interface GifPage {
  results: GifResult[];
  next: string;
}

export interface VoiceState {
  /** Provider sessions are retired when moderation or channel permissions change. */
  sfu_generation?: number;
  user_id: Snowflake;
  /** Null when the user left; old_channel_id then says where from. */
  channel_id: Snowflake | null;
  old_channel_id?: Snowflake;
  session_id: Snowflake;
  self_mute: boolean;
  self_deaf: boolean;
  video: boolean;
  streaming: boolean;
  /** A moderator's mute, or a channel that lets nobody speak (then `suppress` is set too). */
  server_mute: boolean;
  server_deaf: boolean;
  /** The channel denies Speak: server_mute is set for that, not for anything a moderator did. */
  suppress?: boolean;
  /** They lack Use Stereo Voice here, so this app decodes them in one channel whatever they send (voice/channels.ts). */
  mono_only?: boolean;
  /** Talking as Priority Speaker now: everyone else is turned down while they are heard (absent from older servers). */
  priority_speaker?: boolean;
  joined_at: number;
  /** When the call in this channel began, the same for everyone in it (absent from older servers). */
  call_since?: number;
  /**
   * What this participant publishes through the SFU: each kind (audio, video,
   * screen, screen_audio) with a tag that changes when that track is published again.
   */
  media?: Partial<Record<"audio" | "video" | "screen" | "screen_audio", string>>;
  /**
   * The video codecs besides VP8 this participant's device can watch in a
   * call: "h264" for a phone's screen share, which is sent in H.264 only when
   * everyone in the call can.
   */
  codecs?: string[];
  /** Enough of the participant to show them in the call; all a guest ever learns about anyone. */
  user?: CallUser;
  /** Set for someone in through a guest link, with the key epoch their link carried. */
  guest?: { epoch: number };
  /** On a leave: "moved" when another device of the same account picked the call up, "disconnected" when a moderator put them out. */
  reason?: "moved" | "disconnected";
}

/** The flag on a user who is a guest in a call rather than an account. */
export const GUEST_FLAG = 8;
/** The flag on an app's bot: an account a program runs (lib/apps). */
export const BOT_FLAG = 64;
/**
 * The flag on a bot whose app reads every message it can see (the Message
 * Content intent): the server opens messages for it with keys the members'
 * apps hand it, so it is marked wherever it can read.
 */
export const BOT_READS_ALL_FLAG = 128;

export interface CallUser {
  id: Snowflake;
  display_name: string;
  avatar_id: Snowflake | null;
  flags: number;
}

/** A link that lets people without an account into one call. */
export interface GuestLink {
  code: string;
  channel_id: Snowflake;
  creator_id: Snowflake;
  /** The key epoch whose call key the link carries. */
  epoch: number;
  created_at: number;
  expires_at: number;
  /** How many guests are in through it now. */
  guests: number;
}

/** What a guest is told about their own stay. */
export interface GuestInfo {
  id: Snowflake;
  display_name: string;
  channel_id: Snowflake;
  epoch: number;
  expires_at: number;
  /** Whether calls go through the SFU, and so need the link's key. */
  sfu: boolean;
}

export interface IceServer {
  urls: string | string[];
  username?: string;
  credential?: string;
}

export interface AdminUser {
  id: Snowflake;
  username: string;
  display_name: string;
  flags: number;
  created_at: number;
  admin: boolean;
  disabled: boolean;
}

export interface AdminStats {
  users: number;
  servers: number;
  messages: number;
  blobs: number;
  blob_bytes: number;
  online: number;
  version: string;
  /** This month's estimated call traffic against Nexa's allowance; absent when calls are not metered. */
  realtime?: { month: string; used_bytes: number; limit_bytes: number; video_paused: boolean; calls_paused: boolean } | null;
  /** What keeping attachments in the R2 bucket costs this month; null while every file stays on the server's disk. */
  storage?: StorageMeter | null;
}

/** The bucket's cost meter (internal/api/storage_budget.go). */
export interface StorageMeter {
  /** What the bucket holds and bills for. */
  stored_bytes: number;
  /** Attachments still on the server, and uploads on their way: what the bucket will hold. */
  waiting_bytes: number;
  /** This month's requests to the bucket, by the class R2 bills them in. */
  class_a: number;
  class_b: number;
  /** This month's estimate in US cents: keeping everything for the month, plus the requests so far. */
  cost_cents: number;
  limit_cents: number | null;
  /** New attachments are refused: the next one would pass the limit. */
  held: boolean;
  /** When Cloudflare's own measurements run to (the server counts on from there); null while it counts alone. */
  measured_at: number | null;
  /** Cloudflare's measurements are read here (an analytics token is set), once they can be. */
  measuring: boolean;
  /** Whether downloads get links to the bucket: they stop for the month once reads cost more than cap_cents (internal/api/storage_links.go). */
  links?: { on: boolean; read_cents: number; cap_cents: number };
}

export interface AdminSettings {
  registration: "open" | "invite" | "closed";
  registration_key_set: boolean;
  /** At most two new accounts a day from one IP address; admins can turn it off. */
  signup_ip_limit: boolean;
  config_registration: string;
  overrides: Record<string, string>;
  max_upload_bytes: number;
  max_emoji_bytes: number;
  max_sticker_bytes: number;
  max_avatar_bytes: number;
  gif_search: boolean;
  turn: boolean;
  /** Attachments are kept in an R2 bucket, so the monthly limit applies. */
  storage_remote: boolean;
  /** The most keeping attachments may cost a month, in US cents; null for no limit. */
  storage_budget_cents: number | null;
}

/**
 * A compiled law-enforcement response: everything Nexa already holds
 * about one account, as a document to send back to an officer.
 *
 * `document` is English legal prose written by the server and is deliberately
 * not translated — it may end up attached to a filing, where wording that
 * changes with the operator's interface language would be worth less.
 */
export interface LegalReport {
  user_id: Snowflake;
  username: string;
  /** When the snapshot was taken, in Unix milliseconds. */
  generated_at: number;
  /** What the download should be called. */
  filename: string;
  document: string;
}

export interface InstanceInfo {
  name: string;
  version: string;
  registration: "open" | "invite" | "closed";
  needs_key: boolean;
  max_upload_bytes: number;
  gif_search: boolean;
  public_url: string;
  turn_configured: boolean;
  /** Set when the actions bots abuse need a captcha; names hCaptcha for older apps while that fallback is set up. */
  captcha?: { provider: "turnstile" | "hcaptcha"; site_key: string } | null;
  /** Turnstile's site key, which this app uses. */
  turnstile?: { site_key: string } | null;
  /** The most one upload request may carry; a larger file goes up in pieces. */
  upload_single_bytes?: number;
  /** The size of each of those pieces. */
  upload_chunk_bytes?: number;
  max_sticker_bytes?: number;
  /** Every plan's limits; absent from servers before plans. */
  plans?: PlansInfo;
}

export interface ServerKeyInfo {
  conv_id: Snowflake;
  name: string;
  epochs: KeyEpoch[];
  flags: KeyFlags | null;
}

export interface Attachment {
  id: Snowflake;
  blob_id: Snowflake;
  size: number;
}

export interface ReactionSummary {
  emoji: string;
  count: number;
  me: boolean;
}

export type MessageType =
  | "default"
  | "reply"
  | "system_join"
  | "system_pin"
  | "system_call"
  | "system_member_add"
  | "system_member_remove"
  | "system_owner_change"
  | "system_name_change"
  | "system_icon_change"
  | "system_poll_result";

export interface Message {
  id: Snowflake;
  channel_id: Snowflake;
  author_id: Snowflake;
  /** Widened with `string` so an unknown future type does not break parsing. */
  type: MessageType | string;
  ciphertext: Envelope | null;
  key_conv_id: Snowflake | null;
  key_epoch: number | null;
  reply_to_id: Snowflake | null;
  mentions: Snowflake[];
  mention_roles: Snowflake[];
  mention_everyone: boolean;
  attachments: Attachment[];
  reactions: ReactionSummary[];
  flags: number;
  /** The sender's idempotency key, echoed back so a client can match its optimistic copy. */
  nonce?: string;
  edited_at: number | null;
  created_at: number;
  /**
   * Set on a message a webhook posted: which webhook, and what it said (not
   * encrypted; see lib/webhookMessage). An app's messages carry `webhook`
   * alone, with the app named in it, and their bot as the author.
   */
  webhook_id?: Snowflake;
  webhook?: unknown;
  /** How a message's poll stands (a webhook's, or a person's), for whoever loaded it. */
  poll_results?: PollTally;
  /** On the line that closes a person's poll: how it went, in numbers (the words are sealed in the poll's message). */
  poll_result?: PollOutcome;
  /**
   * What a reply (or a pin's or a poll's notice) points at, sent along by the
   * server: the original as it is now, or null when it has been deleted.
   * Absent when the server did not say (stores/replyTargets fetches it then).
   */
  referenced_message?: Message | null;
  /** The thread started from this message, if any. */
  thread?: ThreadSummary;
  /** On a copy of a post published from an announcement channel: where it came from. */
  crosspost?: CrosspostInfo;
  /** A post of an announcement channel that has been published. */
  published?: boolean;
  /** On AutoMod's note in an alert channel: what it caught and did. */
  automod?: AutomodNote;
}

export interface CrosspostInfo {
  follow_id: Snowflake;
  server_id: Snowflake;
  server_name: string;
  server_icon_id: Snowflake | null;
  channel_id: Snowflake;
  channel_name: string;
  message_id: Snowflake;
  /** The original has been deleted. */
  deleted?: boolean;
}

export interface AutomodNote {
  event_id: Snowflake;
  rule_id: Snowflake | null;
  rule_name: string;
  rule_kind: string;
  channel_id: Snowflake | null;
  actions: string[];
  /** What was caught, sealed with the server's key. */
  content?: Envelope;
  matched?: string;
  mentions?: number;
  raid?: boolean;
  messages?: number;
  seconds?: number;
  title?: boolean;
  /** The message it was about, when the rule let it through: to jump to. */
  message_id?: Snowflake;
}

export const MSG_FLAG_PINNED = 1;
export const MSG_FLAG_SUPPRESS_EMBEDS = 4;
/** No sound or notification; it still counts as unread. */
export const MSG_FLAG_SUPPRESS_NOTIFICATIONS = 1 << 12;
/** A post of an announcement channel that has been published to the channels following it. */
export const MSG_FLAG_PUBLISHED = 1 << 20;
/** A webhook message laid out entirely by its components. */
export const MSG_FLAG_COMPONENTS_V2 = 1 << 15;
/** An app's answer only the person who used it sees; it lives until they dismiss it or reload. */
export const MSG_FLAG_EPHEMERAL = 1 << 6;
/** An app's answer still being thought about: the app deferred it and will fill it in. */
export const MSG_FLAG_LOADING = 1 << 7;

/** A poll's votes, as the server counts them for the reader. */
export interface PollTally {
  answer_counts: { id: number; count: number; me_voted: boolean }[];
  total_voters: number;
  expires_at: number | null;
  is_finalized: boolean;
}

/**
 * How a person's poll went, as the line that closes it carries it: numbers
 * only, since what the poll asked is sealed in its own message, which the
 * line answers. The winner is 0 when nobody voted or the top answers tied.
 */
export interface PollOutcome {
  answer_counts: { id: number; count: number }[];
  total_votes: number;
  total_voters: number;
  victor_answer_id: number;
  victor_answer_votes: number;
}

/**
 * What a person's poll tells the server as it is sent: only what it needs to
 * take votes. The question and answers go in the ciphertext.
 */
export interface PollRequest {
  answer_count: number;
  /** In hours, from 1 to 336. */
  duration: number;
  allow_multiselect: boolean;
}

/** An incoming webhook, as the people who manage it see it. */
export interface Webhook {
  id: Snowflake;
  type: number;
  server_id: Snowflake;
  channel_id: Snowflake;
  name: string;
  /** The blob id of its picture, served as an avatar. */
  avatar: Snowflake | null;
  token?: string;
  url?: string;
  user?: { id: Snowflake; username: string; display_name: string; avatar_id: Snowflake | null };
  created_at: number;
}

export interface Relationship {
  user: PublicUser;
  type: "friend" | "outgoing" | "incoming" | "blocked";
  nickname: string;
  /** On an incoming request, the note it came with: a sealed box (base64) only this account can open. */
  note?: string;
  created_at: number;
}

export interface ReadState {
  channel_id: Snowflake;
  last_read_message_id: Snowflake | null;
  last_ack_at: number;
  mention_count: number;
  /** Other people's unread messages in a DM, group DM or subchat, up to 100; absent or 0 elsewhere. */
  unread_count?: number;
  /** Set by Mark Unread, the one change that moves the read marker back. */
  manual?: boolean;
}

export interface KeyGrant {
  conv_id: Snowflake;
  epoch: number;
  user_id: Snowflake;
  /** base64 of the conversation key sealed to `user_id`. */
  wrapped_key: string;
  wrapped_by: Snowflake;
  created_at: number;
  /** base64 of the epoch's fingerprint of its key; missing on older epochs. */
  commitment?: string;
}

export interface KeyNeed {
  conv_id: Snowflake;
  epoch: number;
  user_ids: Snowflake[];
  /** the key is waiting to be rotated after someone lost access */
  rotate?: boolean;
}

export interface KeyEpoch {
  conv_id: Snowflake;
  epoch: number;
  created_by: Snowflake;
  created_at: number;
  reason: string;
}

export interface KeyFlags {
  conv_id: Snowflake;
  needs_rotation: boolean;
  needs_own_key: boolean;
}

export interface Presence {
  user_id: Snowflake;
  status: "online" | "idle" | "dnd" | "offline" | "invisible";
  status_text: string;
  status_emoji: string;
  status_expires_at: number | null;
  /** What they are doing right now, heard from programs on their computer by the desktop app. */
  activities?: Activity[];
}

/** 0 playing, 2 listening, 3 watching, 5 competing (Discord's Rich Presence types). */
export type ActivityType = 0 | 1 | 2 | 3 | 5;

/**
 * A Rich Presence activity as chatd sends it: cleaned to Discord's limits,
 * with its images already on this server's media proxy.
 */
export interface Activity {
  application_id?: string;
  name: string;
  type: ActivityType;
  /** Which text the one-line summary uses: 0 the name, 1 the state, 2 the details. */
  status_display_type?: 0 | 1 | 2;
  details?: string;
  details_url?: string;
  state?: string;
  state_url?: string;
  /** Milliseconds. A start alone counts up; a start and an end draw a progress bar. */
  timestamps?: { start?: number; end?: number };
  assets?: { large_image?: string; large_text?: string; large_url?: string; small_image?: string; small_text?: string; small_url?: string };
  party?: { id?: string; size?: [number, number] };
  buttons?: { label: string; url: string }[];
  instance?: boolean;
  icon_url?: string;
  /** A game the desktop app recognised running, rather than one reporting itself. */
  detected?: boolean;
  /** IGDB's id for it, when the server found one. */
  game_id?: string;
  /** "discord" for what the person's linked Discord account says, rather than the desktop app. */
  source?: "discord";
  created_at: number;
}

export interface SettingsBlob {
  /** base64 of the client-encrypted settings document. */
  blob: string;
  version: number;
  updated_at?: number;
}

/**
 * Nexa's note to someone a friend put in a group directly: who added them and
 * when. Only that person is ever sent it, until they dismiss it.
 */
export interface GroupAddNotice {
  channel_id: Snowflake;
  added_by: Snowflake;
  added_at: number;
}

export interface ReadyPayload {
  session_id: Snowflake;
  user: MeUser;
  users: PublicUser[];
  channels: Channel[];
  relationships: Relationship[];
  read_states: ReadState[];
  presences: Presence[];
  key_grants: KeyGrant[];
  settings: SettingsBlob | null;
  servers: ServerView[];
  emojis: Emoji[];
  sticker_packs: StickerPack[];
  voice_states: VoiceState[];
  /** This account's own notes about being added to a group by a friend, not yet dismissed. */
  group_add_notices?: GroupAddNotice[];
  /** Only in a guest's ready: which call they are in. */
  guest?: GuestInfo;
  /** The account's moderation standing. */
  nexa?: NexaState;
  /** The server's clock as the ready went out (Unix ms), absent from older servers. */
  now?: number;
}

/** What a member of a note may do: read it, edit it, or own it. A higher role includes the ones below. */
export type NoteRole = 1 | 2 | 3;
export const NOTE_VIEWER = 1;
export const NOTE_EDITOR = 2;
export const NOTE_OWNER = 3;

/**
 * A note as the server keeps it for one of its members. `head` and `body` are
 * sealed with the note's key, and `key_sealed` is that key sealed to this
 * account (crypto/notes.ts); the server can read none of it.
 */
export interface NoteRow {
  id: Snowflake;
  owner_id: Snowflake;
  head: string;
  /** Left out of lists; fetched when the note is opened. */
  body?: string;
  /** Counts saves. A save names the version it was made from. */
  version: number;
  edited_by: Snowflake | null;
  created_at: number;
  updated_at: number;
  role: NoteRole;
  key_sealed: string;
  /** Counts the note's keys: `key_sealed` is the key of this epoch. It gets a new one when someone is taken out. */
  key_epoch: number;
  /** How many people the note is shared with, its owner included. */
  members: number;
  /**
   * Someone who held the note's key is no longer in it (taken out, left, an
   * invitation or a link removed). The owner's app gives the note a new key
   * when it sees this.
   */
  needs_rekey?: boolean;
  /** The number the newest change to the note was given. */
  crdt_seq?: number;
  /** The newest change the saved note (`body`) already holds: the ones after it are fetched. */
  crdt_upto?: number;
}

/** One change to a note, sealed on the device that made it (lib/notes/ydoc.ts). */
export interface NoteUpdateRow {
  seq: number;
  update: string;
  user_id: Snowflake | null;
  created_at: number;
}

/** Someone changed a note: the change itself, sealed, passed on as it is made. */
export interface NoteCrdtEvent {
  id: Snowflake;
  seq: number;
  /** Left out when it is large; whoever has the note open fetches it. */
  update?: string;
  user_id: Snowflake;
  /** Which open copy of the note made it. */
  cid: string;
  at: number;
}

/** An earlier state of a note, sealed with the key the note had then. */
export interface NoteVersionRow {
  id: Snowflake;
  note_id: Snowflake;
  version: number;
  head: string;
  /** Left out of the list of versions. */
  body?: string;
  key_epoch: number;
  edited_by: Snowflake | null;
  /** When this state was saved. */
  saved_at: number;
}

/** One of a note's earlier keys, sealed with the key that replaced it. */
export interface NoteKeyRow {
  epoch: number;
  wrapped: string;
}

/** An invitation to a note, as the person invited gets it. */
export interface NoteInviteRow {
  id: Snowflake;
  note_id: Snowflake;
  role: NoteRole;
  key_sealed: string;
  head: string;
  created_at: number;
  from: PublicUser;
}

export interface NoteLinkRow {
  id: Snowflake;
  note_id: Snowflake;
  role: NoteRole;
  key_wrapped: string;
  /** The link itself, sealed with the note's key, for its owner to copy again. */
  link_sealed: string;
  created_by: Snowflake | null;
  uses: number;
  created_at: number;
}

/** Who a note is shared with. Invitations and links are only sent to its owner. */
export interface NoteSharing {
  members: { user: PublicUser; role: NoteRole; created_at: number }[];
  invites: { id: Snowflake; role: NoteRole; created_at: number; user: PublicUser }[];
  links: NoteLinkRow[];
}

/** What a share link leads to, for whoever holds it. */
export interface NoteLinkView {
  note_id: Snowflake;
  role: NoteRole;
  key_wrapped: string;
  head: string;
  updated_at: number;
  members: number;
  /** This account's role in the note already, or 0. */
  member_role: number;
  owner?: PublicUser;
}

/** What the server says when a note changes (internal/api/note_handlers.go). */
export interface NoteUpdateEvent {
  id: Snowflake;
  version?: number;
  head?: string;
  /** The sealed note itself, sent along when it is short enough. */
  body?: string;
  updated_at?: number;
  edited_by?: Snowflake;
  /** Who it is shared with changed, or how. */
  members?: boolean;
  /** Someone was taken out, or a link was removed: the owner's app gives the note a new key. */
  removed?: boolean;
  /** The note has a new key: every member's copy changed. */
  rekeyed?: boolean;
  /** The newest change the saved note holds. */
  upto?: number;
  /** Saved whole by an app that keeps no changes: the note is what this body says. */
  reset?: boolean;
}

/** Someone else in a note says they have it open, or have closed it; `hello` asks those there to say so too. */
export interface NotePresenceEvent {
  id: Snowflake;
  user_id: Snowflake;
  here: boolean;
  hello: boolean;
}

/** One picture or video of a social post. */
export interface PostMedia {
  /** "gif" is a silent video that loops. */
  kind: "photo" | "video" | "gif";
  /** The file. A video with none cannot be played here; its picture opens the post. */
  url?: string;
  thumb?: string;
  /** A video's sound, where the site keeps it in a file of its own; played together with it. */
  audio?: string;
  w?: number;
  h?: number;
  /** In seconds. */
  duration?: number;
  alt?: string;
}

/** The address behind some words of a post's text. Found by looking for the words in order, each after the last. */
export interface PostLink {
  text: string;
  url: string;
}

export interface PostPoll {
  choices: { label: string; votes?: number; percent: number }[];
  votes?: number;
  /** When voting closes or closed, in Unix milliseconds. */
  ends?: number;
}

/**
 * A social post, read by the server from the post's own site (internal/unfurl/post.go)
 * and drawn here as a post: who wrote it, what it says, its pictures and video.
 */
export interface SocialPost {
  /** What its card keys its look on: "twitter", "bluesky", "tiktok", "instagram", "reddit", "telegram", "threads", "mastodon", "youtube". */
  platform: string;
  /** The service's name as people say it. */
  site?: string;
  /** The post on its own site. */
  url?: string;
  name?: string;
  /** Without the "@". */
  handle?: string;
  author_url?: string;
  avatar?: string;
  verified?: boolean;
  /** Where it was posted: "r/pics", the channel a Telegram message was forwarded from. */
  community?: string;
  community_url?: string;
  /** A post's own headline (Reddit), or a content warning (Mastodon). */
  title?: string;
  text?: string;
  links?: PostLink[];
  /** The handle it answers. */
  reply_to?: string;
  /** Unix milliseconds. */
  created?: number;
  media?: PostMedia[];
  poll?: PostPoll;
  quote?: SocialPost;
  replies?: number;
  reposts?: number;
  /** A Reddit post's score, and the sum of a Telegram message's reactions. */
  likes?: number;
  views?: number;
  /** Its media is covered until asked for. */
  sensitive?: boolean;
  /** A note readers added under it. */
  note?: string;
  /** How many people follow the YouTube channel a video is on. */
  subscribers?: number;
  /** "now" for a stream that is on, "upcoming" for one yet to start. */
  live?: "now" | "upcoming";
}

export interface UnfurlResult {
  url: string;
  type: "link" | "image" | "gif" | "video" | "audio" | "youtube" | "invite" | "profile";
  title?: string;
  description?: string;
  site_name?: string;
  image?: string;
  image_w?: number;
  image_h?: number;
  video_id?: string;
  /** Where in a YouTube video its link asks it to start, in seconds. */
  video_start?: number;
  mime?: string;
  color?: string;
  author?: string;
  author_url?: string;
  /** When the page says it was published, in Unix milliseconds. */
  published_at?: number;
  /** The site's own small icon. */
  icon?: string;
  /** A file the browser plays by itself: no frame, nobody else's code. */
  media?: string;
  media_kind?: "video" | "audio";
  media_mime?: string;
  media_w?: number;
  media_h?: number;
  /** A video player's frame, built by the server from a fixed list of sites. */
  player?: string;
  player_site?: string;
  /** Set when the link invites you somewhere on Nexa. */
  invite_code?: string;
  member_count?: number;
  online_count?: number;
  /** An invite's server banner, or a linked profile's. */
  banner?: string;
  /** Whose profile a profile link is (type "profile"), drawn by ProfileCards. */
  username?: string;
  /** How the page asks for its picture: across the card, or small beside the words. Unset leaves it to the picture's size. */
  image_layout?: "large" | "thumbnail";
  /** Set when the link is a social post. The fields above still describe it, for an app that does not draw posts. */
  post?: SocialPost;
  /** Set when the link is music on Spotify, Apple Music, Deezer or SoundCloud (internal/unfurl/music.go). */
  music?: MusicCard;
}

/** A track in a music card's list. */
export interface MusicTrack {
  title: string;
  artist?: string;
  /** In seconds. */
  duration?: number;
  /** Thirty seconds the app plays itself: a proxy address, or Deezer's through /media/music/preview. */
  preview?: string;
  explicit?: boolean;
}

/** A song, album, playlist, artist or podcast episode on a music service. */
export interface MusicCard {
  platform: "spotify" | "apple" | "deezer" | "soundcloud" | string;
  kind: "track" | "album" | "playlist" | "artist" | "episode" | "show" | string;
  id: string;
  title: string;
  artist?: string;
  album?: string;
  /** The cover, a proxy address. */
  art?: string;
  duration?: number;
  /** How many tracks an album or playlist holds. */
  count?: number;
  year?: number;
  explicit?: boolean;
  preview?: string;
  /** Where a preview comes from when it is not the link's own service (a Spotify track's, found on Deezer or Apple Music). */
  preview_from?: "deezer" | "apple";
  tracks?: MusicTrack[];
  /** Set when it plays in the service's own player, through this server. */
  player?: "spotify" | "soundcloud" | string;
}

export interface KdfParamsJson {
  m: number;
  t: number;
  p: number;
  v: 1;
}

export interface RegisterRequest {
  username: string;
  display_name: string;
  auth_key: string;
  kdf_salt: string;
  kdf_params: KdfParamsJson;
  public_key: string;
  enc_private_key: string;
  enc_private_key_recovery?: string;
  recovery_auth_key?: string;
  invite_code?: string;
  registration_key?: string;
}

export interface AuthResponse {
  user: MeUser;
  token: string;
}

export interface LoginResponse extends AuthResponse {
  enc_private_key: string;
  public_key: string;
  kdf_salt: string;
  kdf_params: KdfParamsJson;
}

/** The password was right, but the account also wants a code. */
export interface MfaChallenge {
  mfa_required: true;
  ticket: string;
  backup_codes_left: number;
}

export type LoginResult = LoginResponse | MfaChallenge;

/** True when the server asked for a second factor instead of signing us in. */
export function isMfaChallenge(r: LoginResult): r is MfaChallenge {
  return (r as MfaChallenge).mfa_required === true;
}

export interface MfaStatus {
  enabled: boolean;
  backup_codes_left: number;
  enabled_at?: number;
}

export interface TotpSetup {
  secret: string;
  formatted: string;
  uri: string;
  ticket: string;
}

export interface SendMessageRequest {
  ciphertext: Envelope;
  key_conv_id: Snowflake;
  key_epoch: number;
  reply_to_id?: Snowflake;
  mentions: Snowflake[];
  mention_roles?: Snowflake[];
  mention_everyone?: boolean;
  /** `preview` marks a picture's small copy (lib/pictureCopy.ts), which does not count as a file of its own. */
  attachments?: { blob_id: Snowflake; size: number; preview?: boolean }[];
  /** Client-generated idempotency key for the send. */
  nonce: string;
  flags?: number;
  /** Opens a poll with the message. */
  poll?: PollRequest;
  /** The message sealed to each app's bot it is addressed to, base64 by bot id (lib/botCopies). */
  bot_copies?: Record<Snowflake, string>;
  /** The message this one forwards, whose files it may carry over as they are. */
  forward?: { channel_id: Snowflake; message_id: Snowflake };
}

/** One AutoMod rule (see api/automod and lib/automod). */
export interface AutomodRule {
  id: Snowflake;
  server_id: Snowflake;
  name: string;
  kind: AutomodKind;
  enabled: boolean;
  config: AutomodConfig;
  actions: AutomodAction[];
  exempt_roles: Snowflake[];
  exempt_channels: Snowflake[];
  creator_id: Snowflake;
  created_at: number;
  updated_at: number;
}

export type AutomodKind = "flagged_words" | "words" | "spam" | "links" | "invites" | "personal" | "caps" | "emoji_spam" | "attachments" | "mentions" | "flood" | "profile";

export interface AutomodConfig {
  lists?: string[];
  words?: string[];
  regex?: string[];
  allow?: string[];
  evasion?: boolean;
  mode?: "all" | "list";
  domains?: string[];
  scams?: boolean;
  repeats?: boolean;
  shady_links?: boolean;
  zalgo?: boolean;
  walls?: boolean;
  emails?: boolean;
  phones?: boolean;
  ips?: boolean;
  cards?: boolean;
  percent?: number;
  min_length?: number;
  max?: number;
  raid?: boolean;
  messages?: number;
  seconds?: number;
  extensions?: string[];
  allow_own?: boolean;
  names?: boolean;
  bio?: boolean;
}

export interface AutomodAction {
  type: "block" | "alert" | "timeout" | "quarantine";
  message?: string;
  channel_id?: Snowflake;
  seconds?: number;
  after?: number;
}

export interface AutomodEvent {
  id: Snowflake;
  server_id: Snowflake;
  rule_id: Snowflake | null;
  rule_name: string;
  rule_kind: string;
  user_id: Snowflake;
  channel_id: Snowflake | null;
  actions: string[];
  content: Envelope | null;
  reporter_id: Snowflake | null;
  created_at: number;
}

/** The ready-made lists and spam signs every app applies alike. */
export interface AutomodPresets {
  lists: Record<string, string[]>;
  spam_phrases: string[];
  shorteners: string[];
  impersonated: Record<string, string[]>;
  risky_extensions: string[];
}

/** A server as Discovery lists it. */
export interface DiscoveryCard {
  nsfw?: boolean;
  nsfw_content?: boolean;
  id: Snowflake;
  name: string;
  icon_id: Snowflake | null;
  banner_id: Snowflake | null;
  description: string;
  category: string;
  tags: string[];
  language: string;
  member_count: number;
  online_count: number;
  created_at: number;
  listed_at: number | null;
  tag: ServerTag | null;
  member: boolean;
  joins_paused: boolean;
}

/** A followed announcement channel, as the channel it posts into sees it. */
export interface Follow {
  id: Snowflake;
  source_id: Snowflake;
  target_id: Snowflake;
  server_id: Snowflake;
  creator_id: Snowflake | null;
  public_key: string;
  /** Its private key sealed with the target channel's key. */
  secret: string;
  created_at: number;
  source_server_name: string;
  source_server_icon_id: Snowflake | null;
  source_channel_name: string;
  source_server_id: Snowflake | null;
}
