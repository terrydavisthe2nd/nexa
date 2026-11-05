import type { ReportReceipt } from "./reports";
/**
 * Moderation, both sides of it.
 *
 * `nexa` is a member's own: their Nexa conversation (notices about actions
 * taken on their account, lifted mutes and bans, announcements) and their case
 * threads, where they can appeal. `moderation` is the staff's panel. None of it
 * is end-to-end encrypted — a case thread is a conversation with Nexa
 * itself — and the app says so wherever one is written.
 */
import { api, type UploadOptions } from "./client";
import type { BugCounts, BugMessage, MemberBug } from "./bugs";
import type { Activity, BadgeDef, Emoji, PublicUser, ServerTag, Snowflake, StickerPack } from "./types";

export type ModAction = "message_remove" | "avatar_remove" | "field_reset" | "mute" | "ban" | "delete" | "server_reset" | "expression_takedown";
export type ResetField = "username" | "display_name" | "about_me" | "pronouns" | "status" | "nicknames" | "server_profiles" | "typing_style" | "name_style" | "widgets" | "server_tag";
/** What a server_reset can take back of what a server shows everyone. */
export type ServerField = "icon" | "banner" | "name" | "description" | "tag" | "discovery";
/** What an expression_takedown can take back of a pack: its name, its description, its public listing. */
export type PackField = "name" | "description" | "public";

/** A program people shared, waiting on staff: how many, never who. */
export interface WaitingGame {
  process: string;
  name: string;
  game_id?: string;
  other_names?: string[];
  os: string[];
  count: number;
  first_at: number;
}

export interface DecidedGame {
  process: string;
  name: string;
  game_id?: string;
  status: "listed" | "rejected";
  decided_at: number;
}

export interface StaffGames {
  waiting: WaitingGame[];
  decided: DecidedGame[];
  games: Record<string, { id: string; name: string; cover?: string; year?: number }>;
}

/** As much of an account as the moderation views draw: a name, an avatar, a way to the profile. */
export interface Person {
  id: Snowflake;
  username: string;
  display_name: string;
  avatar_id: Snowflake | null;
  flags: number;
  deleted?: boolean;
}

/** People named by a response, keyed by id; views refer to them by id. */
export type People = Record<string, Person>;

export interface PurgeCounts {
  messages: number;
  attachments: number;
  attachment_bytes: number;
  owned_servers: number;
  owned_groups: number;
  servers: number;
  groups: number;
  packs: number;
  reactions: number;
  /** One-to-one conversations, which go whole when someone deletes their own account. */
  direct_messages?: number;
  /** The notes the account owns. */
  notes?: number;
}

/** Exactly what a case did; which fields are there depends on the action. */
export interface CaseDetails {
  avatar?: boolean;
  banner?: boolean;
  /** A field_reset's profile fields, a server_reset's parts of the server, or an expression_takedown's parts of a pack. */
  fields?: (ResetField | ServerField | PackField)[];
  old?: Partial<Record<ResetField | ServerField | PackField, string>>;
  new?: Partial<Record<ResetField | ServerField | PackField, string>>;
  /** A server_reset's server, and what it was called when it happened; an expression_takedown's, for a server's emoji or pack. */
  server_id?: Snowflake;
  server_name?: string;
  /** An expression_takedown's pack, as it was called then. */
  pack_id?: Snowflake;
  pack_name?: string;
  pack_kind?: "sticker" | "emoji";
  /** The stickers or emoji it took down, by name (their pictures may be blocked now). */
  stickers?: TakenDown[];
  emojis?: TakenDown[];
  /** It took the whole pack. */
  deleted?: boolean;
  /** Its pictures were blocked: served to nobody, and refused if uploaded again. */
  blocked?: boolean;
  duration_ms?: number;
  permanent?: boolean;
  counts?: PurgeCounts;
  complete?: boolean;
}

/** A sticker or emoji an expression_takedown took down. */
export interface TakenDown {
  id: Snowflake;
  name: string;
  blob_id: Snowflake;
}

/** A case as the member it is about sees it. */
export interface MemberCase {
  id: Snowflake;
  number: number;
  action: ModAction;
  reason: string;
  details: CaseDetails;
  moderator_id: Snowflake;
  created_at: number;
  expires_at: number | null;
  lifted_at: number | null;
  lifted_by: Snowflake | null;
  lift_reason: string;
  status: "open" | "closed";
  /** Its mute or ban still holds. */
  in_effect: boolean;
  /** Its mute or ban ran out, or a later case replaced it. */
  ended: boolean;
  /** Staff replies the member has not read. */
  unread: number;
  acknowledged: boolean;
  last_activity_at: number;
}

export type Awaiting = "staff" | "member" | "none";

/** A case as the staff see it. */
export interface StaffCase extends MemberCase {
  user_id: Snowflake;
  target_username: string;
  target_display_name: string;
  awaiting: Awaiting;
  closed_at: number | null;
  closed_by: Snowflake | null;
  notice_ack_at: number | null;
  last_message: CaseMessage | null;
}

export type CaseEvent = "" | "lifted" | "closed" | "reopened" | "expired" | "replaced";

/** A picture or recording sent in a case's thread; its address is /media/cases/{id}. */
export interface CaseFile {
  id: Snowflake;
  name: string;
  mime: string;
  size: number;
  width: number | null;
  height: number | null;
}

/** One line of a case's thread. */
export interface CaseMessage {
  id: Snowflake;
  case_id: Snowflake;
  author_id: Snowflake;
  kind: "member" | "staff" | "system";
  event: CaseEvent;
  body: string;
  created_at: number;
  /** Absent when the line has none. */
  files?: CaseFile[];
}

/** A mute or ban in force. */
export interface Restriction {
  kind: "mute" | "ban";
  case_id: Snowflake;
  case_number: number;
  reason: string;
  moderator_id: Snowflake;
  expires_at: number | null;
  created_at: number;
}

export interface CaseCounts {
  needs_reply: number;
  open: number;
  in_effect: number;
  closed: number;
  all: number;
}

/** The case threads and bug reports waiting on the staff that this member of staff has not read. */
export interface ModUnread {
  cases: Snowflake[];
  bugs: Snowflake[];
}

/** An account's standing, as it arrives with the account and on every change. */
export interface NexaState {
  /** The Nexa system account's id. */
  account_id: Snowflake;
  mute: Restriction | null;
  ban: Restriction | null;
  /** Notices not yet dismissed, oldest first. */
  pending: MemberCase[];
  unread: number;
  /** When Nexa last had something for this account (0: never). */
  latest: number;
  people: People;
  /** The owner of Nexa, for every account ("0" when there is none yet; absent from older servers). */
  owner_id?: Snowflake;
  /** Staff only: what is waiting on them (bug report counts and what they have not read are absent from older servers). */
  staff?: { counts: CaseCounts; owner_id: Snowflake; bugs?: BugCounts; unread?: ModUnread; reports?: Record<string, number> };
}

export interface Announcement {
  id: Snowflake;
  title: string;
  body: string;
  author_id: Snowflake;
  created_at: number;
}

export type FeedItem =
  | { kind: "notice" | "lift"; at: number; case: MemberCase }
  | { kind: "announcement"; at: number; announcement: Announcement }
  /** A bug report the member sent. */
  | { kind: "bug"; at: number; bug: MemberBug }
  /** A staff reply in one of the member's bug reports, or a change of its status. */
  | { kind: "bug_reply" | "bug_status"; at: number; bug: MemberBug; message: BugMessage }
  /** A safety report the member sent, and what staff told them on closing it. */
  | { kind: "report" | "report_outcome"; at: number; report: ReportReceipt };

export type BlockReason = "" | "self" | "system" | "deleted" | "owner" | "staff";

/** An account that shares an address with another (an alt account, or someone on the same network). */
export interface ModAlt {
  user: ModUser;
  /** How many addresses the two have in common. */
  shared_ips: number;
  /** When either was last at a shared address. */
  last_shared_at: number;
}

/** An account in the moderation grid. */
export interface ModUser extends PublicUser {
  /** Every flag, including disabled and deleted. */
  admin_flags: number;
  case_count: number;
  open_cases: number;
  awaiting_cases: number;
  last_seen_at: number | null;
  /** How many other accounts have used one of this account's addresses. Absent from older servers. */
  alt_count?: number;
  mute: Restriction | null;
  ban: Restriction | null;
  /** Why the viewer cannot act on this account ("" when they can). */
  blocked: BlockReason;
  /**
   * How the account looks to everyone as it was loaded: online, idle, do not
   * disturb or offline (someone invisible looks offline here too), and what
   * they are doing. Their custom status is the profile's own status fields,
   * which are kept while they are away. Absent from older servers.
   */
  presence?: { status: "online" | "idle" | "dnd" | "offline"; activities?: Activity[] };
  /** The profile's widgets (lib/widgets), on one account's page only. */
  widgets?: unknown;
}

/** A server someone owns, or has a profile of their own in: a nickname, a picture, a banner, a bio, pronouns or widgets. */
export interface ModMembership {
  server_id: Snowflake;
  name: string;
  icon_id: Snowflake | null;
  owner: boolean;
  nickname: string;
  avatar_id: Snowflake | null;
  /** The rest of their profile there (absent from older servers). */
  banner_id?: Snowflake | null;
  bio?: string;
  pronouns?: string;
  widgets?: string;
  member_count: number;
  joined_at: number;
}

/** Whether a membership carries words of its own that a server_profiles reset clears. */
export function hasServerProfile(m: ModMembership): boolean {
  return !!(m.bio || m.pronouns || (m.widgets && m.widgets !== "[]"));
}

/** A server as the panel shows it: what it shows everyone, who owns it, how many are in it. */
export interface ModServer {
  nsfw?: boolean;
  id: Snowflake;
  name: string;
  icon_id: Snowflake | null;
  banner_id: Snowflake | null;
  owner_id: Snowflake;
  description: string;
  created_at: number;
  member_count: number;
  channel_count: number;
  /** Its server tag, if it has one (absent from older servers). */
  tag?: ServerTag | null;
  /** Its owner lists it on Discover. */
  discoverable?: boolean;
  /** Staff hid it from Discover, and it stays hidden until they show it again. */
  discovery_hidden?: boolean;
  /** Why the viewer cannot act on it: its owner is out of their reach ("" when they can). */
  blocked: BlockReason;
}

export type ServerSort = "newest" | "oldest" | "members" | "name";

/** The month's Realtime egress against the allowance (SFU and TURN share it). */
export interface MeterStatus {
  month: string;
  used_bytes: number;
  limit_bytes: number;
  estimate_bytes: number;
  /** Whether used_bytes rests on Cloudflare's measurement, which covers up to measured_until. */
  measured: boolean;
  measured_sfu_bytes: number;
  measured_turn_bytes: number;
  measured_until: number;
  measured_at: number;
  video_paused: boolean;
  calls_paused: boolean;
}

/**
 * What one account's calls used over a span of days: media sent to its own
 * devices, media its own tracks sent others, TURN relay to it (measured only)
 * and time in calls. Measured is whether Cloudflare measured every day of it;
 * otherwise the days without a measurement are Nexa's estimate.
 */
export interface CallTotals {
  received_bytes: number;
  sent_bytes: number;
  relay_bytes: number;
  seconds: number;
  measured: boolean;
  last_day: string;
}

export type CallPeriod = "today" | "week" | "month";
export type CallSort = "usage" | "sent" | "time";

/** One account on the staff's Calls page. */
export interface ModCallRow extends CallTotals {
  user_id: Snowflake;
  /** Null for someone without an account (a guest), or one deleted since. */
  user: PublicUser | null;
  in_call: boolean;
  /** Past its TURN relay for the day, so it gets none until the next UTC day. */
  relay_cut_off: boolean;
}

export interface ModCalls {
  period: CallPeriod;
  first_day: string;
  last_day: string;
  sort: CallSort;
  accounts: ModCallRow[];
  has_more: boolean;
  /** What Cloudflare measured that no account could be named for. */
  unattributed: CallTotals;
  /** Whether Cloudflare's measurements are set up, rather than Nexa's estimate alone. */
  measurements: boolean;
  turn_daily_limit_bytes: number;
  meter?: MeterStatus;
}

export interface ModUserCalls {
  today: CallTotals;
  week: CallTotals;
  month: CallTotals;
  in_call: boolean;
  relay_cut_off: boolean;
  measurements: boolean;
}

/** An app as the staff's Bots tab lists it. Its id is its bot's. */
export interface ModApp {
  id: Snowflake;
  owner_id: Snowflake;
  name: string;
  description: string;
  icon_id: Snowflake | null;
  /** It has the Message Content intent: it reads every message it can see. */
  reads_all: boolean;
  /** Its bot's account is turned off, so the app does nothing. */
  disabled: boolean;
  public: boolean;
  servers: number;
  accounts: number;
  created_at: number;
}

export type AppSort = "newest" | "oldest" | "servers" | "name";

/** A sticker or emoji pack as the panel shows it: its first stickers in lists, all of them on its page. */
export interface ModPack extends StickerPack {
  sticker_count: number;
  installs: number;
  /** The staff made it private, and it stays private until they let it back. */
  discover_blocked: boolean;
  /** Who answers for it: its owner, or its server's owner. */
  owner_id: Snowflake;
  /** Why the viewer cannot act on it ("" when they can). */
  blocked: BlockReason;
}

/** A server a pack or an emoji belongs to. */
export interface ModServerRef {
  id: Snowflake;
  name: string;
  icon_id: Snowflake | null;
  owner_id: Snowflake;
}

export type PackScope = "all" | "discover" | "personal" | "server" | "hidden";
export type PackSort = "newest" | "oldest" | "installs" | "name";

/** One sticker or emoji among the newest uploads on Nexa. */
export interface ModUpload {
  kind: "sticker" | "emoji";
  id: Snowflake;
  name: string;
  blob_id: Snowflake;
  animated: boolean;
  format?: string;
  pack_id?: Snowflake;
  pack_name?: string;
  server_id?: Snowflake;
  owner_id?: Snowflake;
  creator_id: Snowflake;
  created_at: number;
}

export interface ModLogEntry {
  id: Snowflake;
  action: string;
  moderator_id: Snowflake;
  moderator_username: string;
  target_id: Snowflake | null;
  target_username: string;
  case_id: Snowflake | null;
  case_number: number | null;
  reason: string;
  details: Record<string, unknown>;
  created_at: number;
}

export type GridShow = "all" | "online" | "muted" | "banned" | "staff" | "cases" | "disabled";
export type GridSort = "newest" | "oldest" | "name";
export type CaseView = "needs_reply" | "open" | "in_effect" | "closed" | "all";

export interface ActionRequest {
  action: ModAction;
  reason: string;
  images?: { avatar: boolean; banner: boolean };
  fields?: ResetField[];
  duration_ms?: number;
  permanent?: boolean;
  confirm?: string;
}

interface Thread<C> {
  case: C;
  messages: CaseMessage[];
  people: People;
}

/** One file for a case's thread, the bytes as the body, as bug report files go up. */
function uploadCaseFile(path: string, file: Blob, name: string, opts?: Pick<UploadOptions, "onProgress" | "signal">): Promise<CaseFile> {
  const q = name ? `?name=${encodeURIComponent(name.slice(0, 120))}` : "";
  return api.upload<CaseFile>(`${path}${q}`, file, { ...opts, headers: { "Content-Type": file.type || "application/octet-stream" } });
}

/** Where a case file is fetched from, with the bearer token; `size` asks for a picture no larger than that. */
export function caseFileUrl(id: Snowflake, size?: number): string {
  return api.url(`/media/cases/${id}`, size ? { size } : undefined);
}

export const nexa = {
  state(): Promise<{ state: NexaState; account: PublicUser | null }> {
    return api.request("GET", "/nexa");
  },
  feed(before?: number): Promise<{ items: FeedItem[]; has_more: boolean; people: People }> {
    return api.request("GET", "/nexa/feed", undefined, { query: { before } });
  },
  seen(at?: number): Promise<void> {
    return api.request("POST", "/nexa/seen", at ? { at } : {});
  },
  thread(caseId: Snowflake): Promise<Thread<MemberCase>> {
    return api.request("GET", `/nexa/cases/${caseId}`);
  },
  reply(caseId: Snowflake, body: string, files: CaseFile[] = []): Promise<{ message: CaseMessage; messages: CaseMessage[]; case: MemberCase }> {
    return api.request("POST", `/nexa/cases/${caseId}/messages`, { body, files: files.map((f) => ({ id: f.id, name: f.name })) });
  },
  /** Puts one file up for a line not sent yet. */
  upload(file: Blob, name: string, opts?: Pick<UploadOptions, "onProgress" | "signal">): Promise<CaseFile> {
    return uploadCaseFile("/nexa/cases/attachments", file, name, opts);
  },
  read(caseId: Snowflake, messageId: Snowflake): Promise<void> {
    return api.request("POST", `/nexa/cases/${caseId}/read`, { message_id: messageId });
  },
  ack(caseId: Snowflake): Promise<void> {
    return api.request("POST", `/nexa/cases/${caseId}/ack`);
  },
};

export const moderation = {
  summary(): Promise<{ counts: CaseCounts; bugs?: BugCounts | null; reports?: Record<string, number>; unread?: ModUnread; owner_id: Snowflake; nexa_id: Snowflake; staff_ids: Snowflake[]; people: People }> {
    return api.request("GET", "/moderation/summary");
  },
  /** Mark as Read: every case thread and bug report, read up to now. */
  readAll(): Promise<ModUnread> {
    return api.request("POST", "/moderation/read");
  },
  /** Reads one case thread up to a line, or all of it. */
  readCase(caseId: Snowflake, messageId?: Snowflake): Promise<ModUnread> {
    return api.request("POST", `/moderation/cases/${caseId}/read`, messageId ? { message_id: messageId } : {});
  },
  users(p: { q?: string; show?: GridShow; sort?: GridSort; cursor?: string; limit?: number }): Promise<{ users: ModUser[]; next_cursor: string }> {
    return api.request("GET", "/moderation/users", undefined, { query: { q: p.q || undefined, show: p.show, sort: p.sort, cursor: p.cursor || undefined, limit: p.limit } });
  },
  user(id: Snowflake): Promise<{ user: ModUser; cases: StaffCase[]; people: People; servers?: ModMembership[]; packs?: ModPack[] }> {
    return api.request("GET", `/moderation/users/${id}`);
  },
  servers(p: { discovery?: string; q?: string; sort?: ServerSort; offset?: number; limit?: number }): Promise<{ servers: ModServer[]; people: People; total: number; has_more: boolean }> {
    return api.request("GET", "/moderation/servers", undefined, { query: { discovery: p.discovery, q: p.q?.trim() || undefined, sort: p.sort, offset: p.offset || undefined, limit: p.limit } });
  },
  apps(p: { q?: string; sort?: AppSort; offset?: number; limit?: number }): Promise<{ apps: ModApp[]; people: People; total: number; has_more: boolean }> {
    return api.request("GET", "/moderation/apps", undefined, { query: { q: p.q?.trim() || undefined, sort: p.sort, offset: p.offset || undefined, limit: p.limit } });
  },
  server(id: Snowflake): Promise<{ server: ModServer; cases: StaffCase[]; people: People; emojis?: Emoji[]; packs?: ModPack[] }> {
    return api.request("GET", `/moderation/servers/${id}`);
  },
  packs(p: { q?: string; scope?: PackScope; sort?: PackSort; offset?: number; limit?: number }): Promise<{ packs: ModPack[]; people: People; servers: Record<string, ModServerRef>; has_more: boolean }> {
    return api.request("GET", "/moderation/packs", undefined, { query: { q: p.q?.trim() || undefined, scope: p.scope, sort: p.sort, offset: p.offset || undefined, limit: p.limit } });
  },
  pack(id: Snowflake): Promise<{ pack: ModPack; cases: StaffCase[]; people: People; servers: Record<string, ModServerRef> }> {
    return api.request("GET", `/moderation/packs/${id}`);
  },
  /** Takes down stickers in a pack, its name, description or public listing, or the whole pack. */
  actOnPack(id: Snowflake, body: { stickers?: Snowflake[]; fields?: PackField[]; delete?: boolean; block?: boolean; reason: string }): Promise<{ case: StaffCase; people: People }> {
    return api.request("POST", `/moderation/packs/${id}/actions`, body);
  },
  /** Lets a pack the staff made private be made public again by its owner. */
  relistPack(id: Snowflake, reason: string): Promise<void> {
    return api.request("POST", `/moderation/packs/${id}/relist`, { reason });
  },
  actOnEmojis(serverId: Snowflake, body: { emojis: Snowflake[]; block: boolean; reason: string }): Promise<{ case: StaffCase; people: People }> {
    return api.request("POST", `/moderation/servers/${serverId}/emojis/actions`, body);
  },
  uploads(p: { before?: number; before_id?: Snowflake; limit?: number }): Promise<{ uploads: ModUpload[]; people: People; servers: Record<string, ModServerRef>; has_more: boolean }> {
    return api.request("GET", "/moderation/uploads", undefined, { query: { before: p.before, before_id: p.before_id, limit: p.limit } });
  },
  actOnServer(id: Snowflake, body: { fields: ServerField[]; reason: string }): Promise<{ case: StaffCase; people: People }> {
    return api.request("POST", `/moderation/servers/${id}/actions`, body);
  },
  /** Lets a server the staff hid from Discover show up there again. */
  showOnDiscover(id: Snowflake): Promise<void> {
    return api.request("DELETE", `/moderation/servers/${id}/discovery-hold`);
  },
  /** Puts one file up for a staff line not sent yet. */
  upload(file: Blob, name: string, opts?: Pick<UploadOptions, "onProgress" | "signal">): Promise<CaseFile> {
    return uploadCaseFile("/moderation/cases/attachments", file, name, opts);
  },
  /** The accounts seen at the same addresses as this one, the most shared first. */
  alts(id: Snowflake): Promise<{ alts: ModAlt[] }> {
    return api.request("GET", `/moderation/users/${id}/alts`);
  },
  /** Accounts by what their calls used over a period, largest first. */
  calls(p: { period: CallPeriod; sort: CallSort; offset?: number }): Promise<ModCalls> {
    return api.request("GET", "/moderation/calls", undefined, { query: { period: p.period, sort: p.sort, offset: p.offset } });
  },
  /** One account's calls today, over seven days and this month. */
  userCalls(id: Snowflake): Promise<ModUserCalls> {
    return api.request("GET", `/moderation/users/${id}/calls`);
  },
  purgePreview(id: Snowflake): Promise<PurgeCounts> {
    return api.request("GET", `/moderation/users/${id}/purge-preview`);
  },
  act(id: Snowflake, body: ActionRequest): Promise<{ case: StaffCase; people: People }> {
    return api.request("POST", `/moderation/users/${id}/actions`, body);
  },
  cases(p: { view: CaseView; before_at?: number; before_id?: Snowflake; limit?: number }): Promise<{ cases: StaffCase[]; counts: CaseCounts; people: People }> {
    return api.request("GET", "/moderation/cases", undefined, { query: { view: p.view, before_at: p.before_at, before_id: p.before_id, limit: p.limit } });
  },
  thread(caseId: Snowflake): Promise<Thread<StaffCase>> {
    return api.request("GET", `/moderation/cases/${caseId}`);
  },
  reply(caseId: Snowflake, body: string, files: CaseFile[] = []): Promise<{ message: CaseMessage; messages: CaseMessage[]; case: StaffCase }> {
    return api.request("POST", `/moderation/cases/${caseId}/messages`, { body, files: files.map((f) => ({ id: f.id, name: f.name })) });
  },
  lift(caseId: Snowflake, reason: string): Promise<{ case: StaffCase }> {
    return api.request("POST", `/moderation/cases/${caseId}/lift`, { reason });
  },
  setClosed(caseId: Snowflake, closed: boolean): Promise<{ case: StaffCase }> {
    return api.request("POST", `/moderation/cases/${caseId}/${closed ? "close" : "reopen"}`);
  },
  log(p: { action?: string[]; q?: string; target?: Snowflake; before?: Snowflake; limit?: number }): Promise<{ entries: ModLogEntry[]; people: People }> {
    return api.request("GET", "/moderation/log", undefined, {
      query: { action: p.action?.length ? p.action.join(",") : undefined, q: p.q || undefined, target: p.target, before: p.before, limit: p.limit },
    });
  },
  announcements(before?: number): Promise<{ announcements: Announcement[]; people: People; recipients: number }> {
    return api.request("GET", "/moderation/announcements", undefined, { query: { before } });
  },
  announce(title: string, body: string): Promise<{ announcement: Announcement; people: People }> {
    return api.request("POST", "/moderation/announcements", { title, body });
  },
  retract(id: Snowflake): Promise<void> {
    return api.request("DELETE", `/moderation/announcements/${id}`);
  },
  /** Games people shared and what staff decided, with the games IGDB named. */
  games(): Promise<StaffGames> {
    return api.request("GET", "/moderation/games");
  },
  gamesWaiting(): Promise<{ waiting: number }> {
    return api.request("GET", "/moderation/games/count");
  },
  listGame(b: { process: string; name: string; game_id?: string }): Promise<void> {
    return api.request("POST", "/moderation/games/list", b);
  },
  rejectGame(b: { process: string; name: string }): Promise<void> {
    return api.request("POST", "/moderation/games/reject", b);
  },
  forgetGame(process: string): Promise<void> {
    return api.request("POST", "/moderation/games/forget", { process });
  },
  /** The event badges staff made, and how many hold Contributor. */
  badges(): Promise<{ badges: BadgeDef[]; contributors: number }> {
    return api.request("GET", "/moderation/badges");
  },
  createBadge(b: { name: string; icon: string; color: number }): Promise<BadgeDef> {
    return api.request("POST", "/moderation/badges", b);
  },
  editBadge(id: Snowflake, b: { name: string; icon: string; color: number }): Promise<BadgeDef> {
    return api.request("PATCH", `/moderation/badges/${id}`, b);
  },
  deleteBadge(id: Snowflake): Promise<void> {
    return api.request("DELETE", `/moderation/badges/${id}`);
  },
  /** The badges staff gave someone: "contributor" and "event:<id>". */
  userBadges(id: Snowflake): Promise<{ granted: string[] }> {
    return api.request("GET", `/moderation/users/${id}/badges`);
  },
  setUserBadges(id: Snowflake, badges: string[]): Promise<{ granted: string[] }> {
    return api.request("PUT", `/moderation/users/${id}/badges`, { badges });
  },
  /** Gives or takes a staff rank ("" for none; the owner alone), or disables or restores sign-in, with an optional reason for the log. */
  access(id: Snowflake, body: { rank?: "developer" | "admin" | "mod" | "helper" | ""; disabled?: boolean; reason?: string }): Promise<{ flags: number }> {
    return api.request("PATCH", `/admin/users/${id}`, body);
  },
};
