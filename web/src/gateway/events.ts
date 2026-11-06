import type { ReportReceipt, SafetyReport } from "@/api/reports";
import type { DiscordState } from "@/api/connections";
import type {
  AuditEntry,
  AutomodEvent,
  Channel,
  Emoji,
  GroupAddNotice,
  Invite,
  KeyGrant,
  KeyNeed,
  Member,
  Message,
  NoteCrdtEvent,
  NoteInviteRow,
  NotePresenceEvent,
  NoteUpdateEvent,
  Presence,
  PublicUser,
  ReadState,
  ReadyPayload,
  Relationship,
  Role,
  Server,
  ServerView,
  Snowflake,
  Sticker,
  StickerPack,
  VoiceState,
} from "@/api/types";
import type { Announcement, CaseCounts, CaseMessage, MemberCase, ModLogEntry, ModUnread, NexaState, People, StaffCase } from "@/api/moderation";
import type { BugCounts, BugMessage, MemberBug, StaffBug } from "@/api/bugs";

/** What a deleted message mentioned, which is what unread badges counted it by. */
export interface MentionMeta {
  id: Snowflake;
  author_id: Snowflake;
  mentions: Snowflake[];
  mention_roles: Snowflake[];
  mention_everyone: boolean;
}

export type GatewayEvent =
  | { t: "ready"; d: ReadyPayload }
  | { t: "message_create"; d: Message }
  | { t: "message_update"; d: Message & { was?: Pick<Message, "mentions" | "mention_roles" | "mention_everyone"> } }
  | { t: "message_delete"; d: { id: Snowflake; channel_id: Snowflake; last_message_id?: Snowflake | null; ephemeral?: boolean } & Partial<MentionMeta> }
  | { t: "message_delete_bulk"; d: { ids: Snowflake[]; channel_id: Snowflake; mentions?: MentionMeta[]; last_message_id?: Snowflake | null } }
  | { t: "reaction_add" | "reaction_remove"; d: { channel_id: Snowflake; message_id: Snowflake; user_id: Snowflake; emoji: string } }
  /** Every reaction taken off a message at once (an app or a moderator through the bot API). */
  | { t: "reaction_remove_all"; d: { channel_id: Snowflake; message_id: Snowflake } }
  /** Every reaction of one emoji taken off a message. */
  | { t: "reaction_remove_emoji"; d: { channel_id: Snowflake; message_id: Snowflake; emoji: string } }
  /** An app's commands changed (made, changed, deleted, or who may use them): lists of them are read again. */
  | { t: "application_commands_update"; d: { application_id: Snowflake; guild_id?: Snowflake | null } }
  /** A server's apps changed: one was added or taken away. */
  | { t: "integrations_update"; d: { server_id: Snowflake } }
  | { t: "typing"; d: { channel_id: Snowflake; user_id: Snowflake; ts: number; stop?: boolean } }
  | { t: "channel_create" | "channel_update"; d: Channel }
  | { t: "channel_delete"; d: { id: Snowflake; server_id?: Snowflake | null } }
  | { t: "channel_positions"; d: { server_id: Snowflake; channels: { id: Snowflake; position: number; parent_id: Snowflake | null }[] } }
  | { t: "server_create"; d: ServerView & { users?: PublicUser[] } }
  | { t: "server_update"; d: Server }
  | { t: "server_delete"; d: { id: Snowflake } }
  | { t: "member_add"; d: { server_id: Snowflake; member: Member; user: PublicUser } }
  | { t: "member_update"; d: Member }
  | { t: "member_remove"; d: { server_id: Snowflake; user_id: Snowflake; reason: "leave" | "kick" | "ban" } }
  | { t: "role_create" | "role_update"; d: Role }
  | { t: "role_delete"; d: { id: Snowflake; server_id: Snowflake } }
  | { t: "presence"; d: Presence }
  | { t: "user_update"; d: PublicUser }
  /** This account's subscription or boosts changed (api/billing.go); read them again. */
  | { t: "billing_update"; d: Record<string, never> }
  | { t: "relationship_add"; d: Relationship }
  | { t: "relationship_remove"; d: { user_id: Snowflake } }
  | { t: "read_state"; d: ReadState }
  | { t: "voice_state"; d: VoiceState }
  | { t: "voice_signal"; d: { from: Snowflake; channel_id: Snowflake; payload: unknown } }
  | { t: "voice_error"; d: { code: string; message: string } }
  /** After a reconnect: whether the server kept this device's call for it, or it starts over. */
  | { t: "voice_resumed"; d: { channel_id: Snowflake; kept: boolean } }
  | { t: "call_ring"; d: { channel_id: Snowflake; from: Snowflake } }
  | { t: "call_end"; d: { channel_id: Snowflake; user_id?: Snowflake; reason: "declined" | "ended" | "answered" } }
  | { t: "key_grant"; d: KeyGrant }
  | { t: "keys_needed"; d: KeyNeed }
  | { t: "key_rotation_needed"; d: { conv_id: Snowflake } }
  | { t: "emoji_update"; d: { server_id: Snowflake; emojis: Emoji[] } }
  | { t: "sticker_update"; d: { pack_id: Snowflake; deleted: boolean; pack?: StickerPack } }
  | { t: "sticker_recent"; d: { sticker: Sticker } }
  | { t: "sticker_recent_clear"; d: Record<string, never> }
  | { t: "pin_update"; d: { channel_id: Snowflake; message_id: Snowflake; pinned: boolean } }
  | { t: "ban_add"; d: { server_id: Snowflake; user_id: Snowflake; reason: string } }
  | { t: "ban_remove"; d: { server_id: Snowflake; user_id: Snowflake } }
  | { t: "invite_create"; d: Invite }
  | { t: "invite_delete"; d: { server_id: Snowflake; code: string } }
  /** A server's webhooks changed (made, edited, moved or deleted), for whoever manages them. */
  | { t: "webhooks_update"; d: { server_id: Snowflake; channel_id: Snowflake } }
  /** Someone voted on, or took back a vote on, a webhook message's poll. */
  | { t: "poll_vote_add" | "poll_vote_remove"; d: { channel_id: Snowflake; message_id: Snowflake; user_id: Snowflake; answer_id: number; total_voters?: number } }
  | { t: "settings_update"; d: { version: number; session_id: Snowflake } }
  /** Nexa's note that a friend added you to a group, and its dismissal on another of your devices. */
  | { t: "group_add_notice"; d: GroupAddNotice }
  | { t: "group_add_notice_delete"; d: { channel_id: Snowflake } }
  /** A note changed: what it says, who it is shared with, or its key. */
  | { t: "note_update"; d: NoteUpdateEvent }
  /** A note is gone for this account: deleted, or this account is out of it. */
  | { t: "note_delete"; d: { id: Snowflake } }
  /** Someone asked this account into a note, and that asking being answered or taken back. */
  | { t: "note_invite"; d: NoteInviteRow }
  | { t: "note_invite_delete"; d: { id: Snowflake } }
  | { t: "note_presence"; d: NotePresenceEvent }
  /** Where someone else's caret is in a note, sealed with the note's key. */
  | { t: "note_cursor"; d: { id: Snowflake; user_id: Snowflake; data: string } }
  /** A change to a note, sealed, as it is made. */
  | { t: "note_crdt"; d: NoteCrdtEvent }
  | { t: "session_expired"; d: unknown }
  /** A guest's time in the call is over: removed, revoked, expired, left, or the call is gone. */
  | { t: "guest_ended"; d: { reason: string } }
  /** Staff acted on this account: the notice to show, and where the account stands now. */
  | { t: "nexa_notice"; d: { case: MemberCase; people: People; state: NexaState } }
  /** This account's standing changed (read, dismissed, a mute or ban ended). */
  | { t: "nexa_state"; d: NexaState }
  /** One of this account's cases moved: new thread lines, lifted, closed, reopened. */
  | { t: "nexa_case"; d: { case: MemberCase; messages: CaseMessage[]; people: People; state: NexaState } }
  | { t: "nexa_announcement"; d: { announcement: Announcement; people: People } }
  | { t: "nexa_announcement_delete"; d: { id: Snowflake } }
  /** Staff: a case changed, with any new lines of its thread. */
  | { t: "mod_case"; d: { case: StaffCase; messages: CaseMessage[]; people: People; counts: CaseCounts } }
  /** Staff: a new line in the punishment log. */
  | { t: "mod_log"; d: { entry: ModLogEntry; people: People } }
  /** One of this account's bug reports moved: sent from another device, answered, or given a new status. */
  | { t: "nexa_bug"; d: { report: MemberBug; messages: BugMessage[]; people: People; state: NexaState } }
  /** Staff: a bug report arrived or changed, with any new lines of its thread. */
  | { t: "mod_bug"; d: { report: StaffBug; messages: BugMessage[]; people: People; counts: BugCounts } }
  /** Staff: the case threads and bug reports waiting on the staff that this person has not read. */
  | { t: "mod_unread"; d: ModUnread }
  | { t: "mod_games"; d: { waiting: number } }
  | { t: "mod_report"; d: { reports: SafetyReport[]; counts: Record<string, number>; people: People; new?: boolean } }
  | { t: "report_update"; d: ReportReceipt }
  /** This account's linked Discord account changed: linked, unlinked, out of the activity server or back in it. */
  | { t: "discord_link"; d: DiscordState }
  /** A thread started, changed (renamed, closed, opened, locked, retagged) or was shown to someone just put in it. */
  | { t: "thread_create" | "thread_update"; d: Channel & { member_count?: number } }
  | { t: "thread_delete"; d: { id: Snowflake; parent_id: Snowflake | null; server_id: Snowflake | null } }
  /** People came into or left a thread. */
  | { t: "thread_members_update"; d: { thread_id: Snowflake; server_id: Snowflake | null; added: Snowflake[]; removed: Snowflake[]; member_count: number } }
  /** A server's AutoMod rules changed: apps fetch them again. */
  | { t: "automod_rules_update"; d: { server_id: Snowflake; automod_at?: number } }
  /** AutoMod acted, for those who may read its log. */
  | { t: "automod_event"; d: AutomodEvent }
  /** A new line in a server's audit log, for those who may read it. */
  | { t: "audit_log_entry"; d: AuditEntry }
  /** What a channel follows changed, or a follow's key was sealed again. */
  | { t: "follows_update"; d: { channel_id: Snowflake } }
  /** Not from the server: the gateway was closed because the account is banned. */
  | { t: "account_banned"; d?: undefined }
  | { t: "heartbeat_ack"; d?: undefined };

export type GatewayEventType = GatewayEvent["t"];

export function isGatewayFrame(x: unknown): x is { t: string; s?: number; d?: unknown } {
  return typeof x === "object" && x !== null && typeof (x as { t?: unknown }).t === "string";
}
