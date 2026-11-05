/**
 * How moderation is put into words, in one place, so a notice, the pop-up,
 * the ban screen and the staff's panel all describe the same action the same
 * way — in the reader's language.
 */
import { Award, Ban, Eraser, ImageOff, KeyRound, Lock, LockOpen, Megaphone, MessageSquareOff, ServerCog, ShieldMinus, ShieldPlus, Sticker, UserX, type LucideIcon } from "lucide-react";
import type { CaseDetails, CaseMessage, MemberCase, ModAction, ModLogEntry, PackField, People, Person, ResetField, ServerField } from "@/api/moderation";
import type { BugStatus } from "@/api/bugs";
import { bugStatusLabel } from "@/lib/bugs";
import { formatStamp } from "@/lib/time";
import { formatList, t } from "@/i18n";
import { rankName } from "@/lib/staffRank";

/** The flag the server sets on the Nexa system account. */
export const FLAG_SYSTEM = 16;
export const FLAG_ADMIN = 1;
export const FLAG_DISABLED = 2;
export const FLAG_DELETED = 4;

export function isSystemAccount(flags: number | undefined): boolean {
  return ((flags ?? 0) & FLAG_SYSTEM) !== 0;
}

/** How serious an action is, which decides its colour everywhere. */
export type Severity = "info" | "warn" | "danger";

export function severity(action: string): Severity {
  switch (action) {
    case "ban":
    case "delete":
    case "account_disable":
    case "account_delete_profile":
      return "danger";
    case "mute":
    case "field_reset":
    case "avatar_remove":
    case "server_reset":
    case "expression_takedown":
    case "admin_revoke":
      return "warn";
    default:
      return "info";
  }
}

export function actionIcon(action: string): LucideIcon {
  switch (action) {
    case "avatar_remove":
      return ImageOff;
    case "field_reset":
      return Eraser;
    case "server_reset":
      return ServerCog;
    case "expression_takedown":
      return Sticker;
    case "mute":
      return MessageSquareOff;
    case "ban":
      return Ban;
    case "delete":
    case "account_delete_profile":
      return UserX;
    case "unmute":
    case "unban":
    case "expression_relist":
    case "discovery_restore":
      return LockOpen;
    case "announcement":
    case "announcement_delete":
      return Megaphone;
    case "admin_grant":
    case "rank_change":
      return ShieldPlus;
    case "admin_revoke":
      return ShieldMinus;
    case "badge_change":
    case "badge_create":
    case "badge_edit":
    case "badge_delete":
      return Award;
    case "account_disable":
      return Lock;
    default:
      return KeyRound;
  }
}

/** What staff did, as the panel lists it: "Muted", "Removed profile picture". */
export function actionLabel(action: string): string {
  switch (action) {
    case "avatar_remove":
      return t("moderation.action.avatarRemove");
    case "field_reset":
      return t("moderation.action.fieldReset");
    case "server_reset":
      return t("moderation.action.serverReset");
    case "expression_takedown":
      return t("moderation.action.expressionTakedown");
    case "expression_relist":
      return t("moderation.action.expressionRelist");
    case "discovery_restore":
      return t("moderation.action.discoveryRestore");
    case "mute":
      return t("moderation.action.mute");
    case "ban":
      return t("moderation.action.ban");
    case "delete":
      return t("moderation.action.delete");
    case "unmute":
      return t("moderation.action.unmute");
    case "unban":
      return t("moderation.action.unban");
    case "announcement":
      return t("moderation.action.announcement");
    case "announcement_delete":
      return t("moderation.action.announcementDelete");
    case "admin_grant":
      return t("moderation.action.adminGrant");
    case "admin_revoke":
      return t("moderation.action.adminRevoke");
    case "rank_change":
      return t("staff.log.rankChange");
    case "badge_change":
      return t("badges.log.change");
    case "badge_create":
      return t("badges.log.create");
    case "badge_edit":
      return t("badges.log.edit");
    case "badge_delete":
      return t("badges.log.delete");
    case "message_remove":
      return t("reports.messageRemoved");
    case "report_review":
      return t("reports.review");
    case "account_disable":
      return t("moderation.action.accountDisable");
    case "account_enable":
      return t("moderation.action.accountEnable");
    case "account_delete_profile":
      return t("moderation.action.accountDeleteProfile");
    default:
      return action;
  }
}

/** A pack's parts, as a takedown names them. */
export function packFieldLabel(f: PackField): string {
  switch (f) {
    case "name":
      return t("moderation.field.packName");
    case "description":
      return t("moderation.field.packDescription");
    default:
      return t("moderation.field.packPublic");
  }
}

export function fieldLabel(f: ResetField | ServerField): string {
  switch (f) {
    case "username":
      return t("moderation.field.username");
    case "display_name":
      return t("moderation.field.displayName");
    case "about_me":
      return t("moderation.field.aboutMe");
    case "pronouns":
      return t("moderation.field.pronouns");
    case "nicknames":
      return t("moderation.field.nicknames");
    case "server_profiles":
      return t("moderation.field.serverProfiles");
    case "typing_style":
      return t("moderation.field.typingStyle");
    case "name_style":
      return t("moderation.field.nameStyle");
    case "widgets":
      return t("moderation.field.widgets");
    case "server_tag":
      return t("moderation.field.serverTagWorn");
    case "tag":
      return t("moderation.field.serverTag");
    case "icon":
      return t("moderation.field.serverIcon");
    case "banner":
      return t("moderation.field.serverBanner");
    case "name":
      return t("moderation.field.serverName");
    case "description":
      return t("moderation.field.serverDescription");
    case "discovery":
      return t("moderation.field.serverDiscovery");
    case "status":
      return t("moderation.field.status");
    default:
      // every field has its words above; one a newer server sends is shown as it came
      return f as string;
  }
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WEEK = 7 * DAY;

/** "1 hour", "3 days", "2 weeks": the largest unit it is a whole number of. */
export function formatSpan(ms: number): string {
  for (const [size, key] of [
    [WEEK, "nexa.span.weeks"],
    [DAY, "nexa.span.days"],
    [HOUR, "nexa.span.hours"],
    [MINUTE, "nexa.span.minutes"],
  ] as const) {
    if (ms >= size && ms % size === 0) return t(key, { count: ms / size });
  }
  if (ms >= DAY) return t("nexa.span.days", { count: Math.round(ms / DAY) });
  if (ms >= HOUR) return t("nexa.span.hours", { count: Math.round(ms / HOUR) });
  return t("nexa.span.minutes", { count: Math.max(1, Math.round(ms / MINUTE)) });
}

export const SPAN = { MINUTE, HOUR, DAY, WEEK };

/** "in 3 days" (or "3 days ago"), in the reader's language. */
export function relativeTo(ms: number): string {
  return formatStamp(new Date(ms), "R");
}

/** "Thursday, September 24, 2026 at 4:10 PM". */
export function fullDate(ms: number): string {
  return formatStamp(new Date(ms), "F");
}

/** The headline of a notice, as the member reads it: "Your account was muted". */
export function noticeTitle(action: ModAction, d: CaseDetails): string {
  switch (action) {
    case "message_remove":
      return t("reports.messageRemoved");
    case "avatar_remove":
      if (d.avatar && d.banner) return t("nexa.notice.titleAvatarAndBanner");
      return d.banner ? t("nexa.notice.titleBanner") : t("nexa.notice.titleAvatar");
    case "field_reset":
      return t("nexa.notice.titleFieldReset");
    case "server_reset":
      // hiding a server from Discover alone takes nothing down, so it is said as what it is
      return onlyDiscover(d) ? t("nexa.notice.titleServerDiscover") : t("nexa.notice.titleServerReset");
    case "expression_takedown":
      if (d.deleted) return d.pack_kind === "emoji" ? t("nexa.notice.titleEmojiPackRemoved") : t("nexa.notice.titleStickerPackRemoved");
      if (d.emojis?.length) return t("nexa.notice.titleServerEmoji");
      if (d.stickers?.length && !d.fields?.length) return d.pack_kind === "emoji" ? t("nexa.notice.titlePackEmoji") : t("nexa.notice.titlePackStickers");
      return t("nexa.notice.titlePack");
    case "mute":
      return t("nexa.notice.titleMute");
    case "ban":
      return t("nexa.notice.titleBan");
    default:
      return t("nexa.notice.titleDelete");
  }
}

/** Whether a server_reset did nothing but hide the server from Discover. */
function onlyDiscover(d: CaseDetails): boolean {
  return d.fields?.length === 1 && d.fields[0] === "discovery";
}

/** What an action means for the member, in a sentence under the headline. */
export function noticeExplainer(c: Pick<MemberCase, "action" | "details" | "expires_at">): string {
  switch (c.action) {
    case "message_remove":
      return t("reports.messageRemovedBody");
    case "avatar_remove":
      return t("nexa.notice.explainAvatar");
    case "field_reset":
      return c.details.fields?.includes("username") ? t("nexa.notice.explainUsername") : t("nexa.notice.explainFields");
    case "server_reset": {
      // a server hidden from Discover stays hidden until staff show it again, whatever the owner does
      const name = c.details.server_name ?? "";
      if (onlyDiscover(c.details)) return t("nexa.notice.explainServerDiscover", { name });
      return c.details.fields?.includes("discovery") ? t("nexa.notice.explainServerWithDiscover", { name }) : t("nexa.notice.explainServer", { name });
    }
    case "expression_takedown": {
      const d = c.details;
      if (d.deleted) return t("nexa.notice.explainPackRemoved", { name: d.pack_name ?? "" });
      if (d.emojis?.length) return t("nexa.notice.explainServerEmoji", { name: d.server_name ?? "" });
      if (d.fields?.length) return t("nexa.notice.explainPackParts", { name: d.pack_name ?? "" });
      return d.pack_kind === "emoji" ? t("nexa.notice.explainPackEmoji", { name: d.pack_name ?? "" }) : t("nexa.notice.explainPackItems", { name: d.pack_name ?? "" });
    }
    case "mute":
      return t("nexa.notice.explainMute");
    case "ban":
      return c.expires_at === null ? t("nexa.notice.explainBanPermanent") : t("nexa.notice.explainBan");
    default:
      return t("nexa.notice.explainDelete");
  }
}

/** Where a case stands, as a short status for a chip. */
export function caseStanding(c: Pick<MemberCase, "action" | "in_effect" | "ended" | "lifted_at" | "expires_at">, now = Date.now()): { label: string; tone: Severity | "done" } | null {
  if (c.action !== "mute" && c.action !== "ban") return null;
  if (c.lifted_at) return { label: t("nexa.standing.lifted"), tone: "done" };
  if (c.in_effect && (c.expires_at === null || c.expires_at > now)) {
    if (c.expires_at === null) return { label: t("nexa.standing.permanent"), tone: "danger" };
    return { label: t("nexa.standing.endsIn", { when: relativeTo(c.expires_at) }), tone: c.action === "ban" ? "danger" : "warn" };
  }
  return { label: t("nexa.standing.ended"), tone: "done" };
}

/**
 * A line of a thread as one line of plain words, for a list's preview: what
 * the formatting says rather than its marks, and what a line of only files
 * carries. Nothing is drawn from it, so nothing in it can run.
 */
export function previewText(body: string, files = 0): string {
  const words = body
    // a code block is its code, on one line
    .replace(/```[^\n]*\n?([\s\S]*?)```/g, " $1 ")
    // headings, quotes, list marks and subtext at the start of a line
    .replace(/^[ \t]*(?:#{1,6}|>+|-#|[-*+]|\d+[.)])[ \t]+/gm, "")
    // [words](address) is its words; <address> is the address
    .replace(/\[([^\]\n]*)\]\([^)\s]*\)/g, "$1")
    .replace(/<(https?:[^>\s]+)>/g, "$1")
    // a spoiler says it is one, without saying what it hides
    .replace(/\|\|[\s\S]*?\|\|/g, `[${t("moderation.case.spoiler")}]`)
    // emphasis, strikes and code marks; underscores only at a word's edges, so snake_case stays
    .replace(/(\*\*\*|\*\*|\*|~~|`)(?=\S)([\s\S]*?\S)\1/g, "$2")
    .replace(/(^|[^\p{L}\p{N}_])(___|__|_)(?=\S)([\s\S]*?\S)\2(?![\p{L}\p{N}_])/gu, "$1$3")
    .replace(/\s+/g, " ")
    .trim();
  if (words) return words;
  return files > 0 ? t("moderation.case.sentFiles", { count: files }) : "";
}

/** A person's name as the moderation views show it, with a stand-in when they are gone. */
export function personName(p: Person | undefined): string {
  if (!p) return t("names.unknownUser");
  if (p.deleted) return t("nexa.deletedUser");
  return p.display_name || p.username;
}

/**
 * What a system line in a case thread says happened: a lift, a close, a
 * reopen, a mute or ban running out, or a new case taking over. In a bug
 * report's thread, the one system line is a change of status.
 */
export function systemLineText(m: Pick<CaseMessage, "author_id" | "body"> & { event: string }, people: People, action: ModAction | undefined, perspective: "member" | "staff"): string {
  const who = personName(people[m.author_id]);
  const what = action === "ban" ? t("nexa.thread.theBan") : t("nexa.thread.theMute");
  switch (m.event) {
    case "status":
      return t("bugs.thread.statusChanged", { who, status: bugStatusLabel(m.body as BugStatus) });
    case "lifted":
      return m.body ? t("nexa.thread.liftedWithReason", { who, what, reason: m.body }) : t("nexa.thread.lifted", { who, what });
    case "closed":
      return t("nexa.thread.closed", { who });
    case "reopened":
      // a member reopens a case by writing in it; staff do it on purpose
      return people[m.author_id] && perspective === "staff" ? t("nexa.thread.reopened", { who }) : t("nexa.thread.reopenedByReply");
    case "expired":
      return t("nexa.thread.expired", { what });
    case "replaced":
      return t("nexa.thread.replaced", { number: m.body });
    default:
      return m.body;
  }
}

/** One sentence for a log entry's details, when it has any worth showing. */
export function logDetail(e: ModLogEntry): string {
  const d = e.details as CaseDetails & { title?: string; messages_kept?: boolean };
  switch (e.action) {
    case "mute":
    case "ban":
      if (d.permanent) return t("moderation.log.permanent");
      return d.duration_ms ? t("moderation.log.forSpan", { span: formatSpan(d.duration_ms) }) : "";
    case "field_reset":
      return (d.fields ?? []).map((f) => fieldLabel(f as ResetField)).join(", ");
    case "server_reset":
      return d.server_name ? t("moderation.log.serverFields", { name: d.server_name, fields: (d.fields ?? []).map((f) => fieldLabel(f as ServerField)).join(", ") }) : "";
    case "avatar_remove":
      return [d.avatar ? t("moderation.field.avatar") : "", d.banner ? t("moderation.field.banner") : ""].filter(Boolean).join(", ");
    case "expression_takedown":
      return expressionSummary(d);
    case "expression_relist": {
      const name = (e.details as { pack_name?: string }).pack_name;
      return name ? `“${name}”` : "";
    }
    case "discovery_restore":
      return d.server_name ? `“${d.server_name}”` : "";
    case "delete":
      return d.counts ? t("moderation.log.deleteCounts", { messages: d.counts.messages, servers: d.counts.owned_servers }) : "";
    case "announcement":
    case "announcement_delete":
      return d.title ? `“${d.title}”` : "";
    case "account_delete_profile":
      return t("moderation.log.messagesKept");
    case "badge_change": {
      const d = e.details as { added?: string[]; removed?: string[] };
      const named = (list: string[] | undefined) => formatList((list ?? []).map((b) => (b === "contributor" ? t("badges.contributor") : b)));
      return [d.added?.length ? t("badges.log.gave", { names: named(d.added) }) : "", d.removed?.length ? t("badges.log.took", { names: named(d.removed) }) : ""].filter(Boolean).join("; ");
    }
    case "badge_create":
    case "badge_delete":
      return (e.details as { name?: string }).name ? `“${(e.details as { name: string }).name}”` : "";
    case "badge_edit": {
      const d = e.details as { name?: string; from?: string };
      return d.from && d.from !== d.name ? t("staff.log.fromTo", { from: d.from, to: d.name ?? "" }) : d.name ? `“${d.name}”` : "";
    }
    case "rank_change": {
      const r = e.details as { from?: string; to?: string };
      const name = (x: string | undefined) => (x === "developer" || x === "admin" || x === "mod" || x === "helper" ? rankName(x) : t("staff.rankModal.none"));
      return t("staff.log.fromTo", { from: name(r.from), to: name(r.to) });
    }
    default:
      return "";
  }
}

/** What an expression_takedown took, in a line: "2 stickers from Cats", "Cats, deleted", "3 emoji in Night Owls". */
export function expressionSummary(d: CaseDetails): string {
  if (d.deleted) return t("moderation.log.packDeleted", { name: d.pack_name ?? "" });
  const parts: string[] = [];
  if (d.stickers?.length) parts.push(t("moderation.log.stickersFrom", { count: d.stickers.length, name: d.pack_name ?? "" }));
  if (d.emojis?.length) parts.push(t("moderation.log.emojiIn", { count: d.emojis.length, name: d.server_name ?? "" }));
  const fields = (d.fields ?? []).filter((f): f is PackField => f === "name" || f === "description" || f === "public");
  if (fields.length) parts.push(t("moderation.log.packFields", { fields: fields.map((f) => packFieldLabel(f)).join(", "), name: d.pack_name ?? "" }));
  return parts.join("; ");
}

/** Log filters as the panel groups them. */
export const LOG_GROUPS: Record<string, string[]> = {
  punishments: ["message_remove", "avatar_remove", "field_reset", "server_reset", "expression_takedown", "mute", "ban", "delete", "account_delete_profile"],
  lifts: ["unmute", "unban", "expression_relist", "discovery_restore"],
  announcements: ["announcement", "announcement_delete"],
  access: ["rank_change", "admin_grant", "admin_revoke", "account_disable", "account_enable"],
  badges: ["badge_change", "badge_create", "badge_edit", "badge_delete"],
};

/** Why a muted account cannot do something, in a sentence for a toast. */
export function mutedSentence(mute: { expires_at: number | null } | null): string {
  if (!mute || mute.expires_at === null) return t("nexa.muted.toastPermanent");
  return t("nexa.muted.toast", { when: relativeTo(mute.expires_at) });
}

/** Why a banned account cannot do something, in a sentence for a toast. */
export function bannedSentence(ban: { expires_at: number | null } | null): string {
  if (!ban || ban.expires_at === null) return t("nexa.ban.toastPermanent");
  return t("nexa.ban.toast", { when: relativeTo(ban.expires_at) });
}
