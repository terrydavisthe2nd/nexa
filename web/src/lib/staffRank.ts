/**
 * Nexa's staff ranks, as the server keeps them (internal/api/staff.go): each
 * holds everything the ones below it hold.
 *
 * - Helper: support and triage. Claims, notes and dismisses reports, handles
 *   bug reports, and reads people, cases and the log. Acts on nothing.
 * - Mod: a moderator. Takes reported content down (messages, profile parts,
 *   stickers, emoji, packs), mutes and bans for 30 days at most, replies in
 *   cases and handles the cases they opened. Browses servers and packs.
 * - Admin: the rest of the panel: any length, anyone's cases, servers and
 *   Discover, alt accounts, call usage, apps, announcements, badges, bulk
 *   actions, disabling sign-in and deleting accounts.
 * - Developer: the instance's settings, stats and crash reports. No rights in
 *   other people's servers or group chats.
 * - Owner: the oldest Developer, the only one who gives and takes ranks.
 *
 * A rank is a public flag bit, so it is read from anyone's flags. What each
 * rank may do is `can(rank, capability)`, from STAFF_CAN.
 */
import { CodeXml, Crown, Hammer, LifeBuoy, ShieldCheck, type LucideIcon } from "lucide-react";
import { useAuth } from "@/stores/auth";
import { useUsers } from "@/stores/users";
import { isOwner, useOwnerId } from "@/lib/owner";
import { t } from "@/i18n";

export type StaffRank = "owner" | "developer" | "admin" | "mod" | "helper";
/** The ranks the owner can give, from the most to the least. */
export type GrantableRank = Exclude<StaffRank, "owner">;

export const FLAG_DEVELOPER = 1;
export const FLAG_STAFF_ADMIN = 256;
export const FLAG_STAFF_MOD = 512;
export const FLAG_STAFF_HELPER = 1024;
export const STAFF_FLAGS = FLAG_DEVELOPER | FLAG_STAFF_ADMIN | FLAG_STAFF_MOD | FLAG_STAFF_HELPER;

const ORDER: StaffRank[] = ["helper", "mod", "admin", "developer", "owner"];

/** The longest mute or ban a mod can give. */
export const MOD_MAX_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * The least rank each thing staff do takes, kept equal to the server's routes
 * (internal/api/staff.go) and Android's StaffRanks.can.
 */
export const STAFF_CAN = {
  /** The report queue: claim, note, dismiss. */
  triageReports: "helper",
  /** People, cases and the log, to read. */
  readPanel: "helper",
  /** Bug reports: read, reply, change status. */
  handleBugs: "helper",
  /** Take Action on reports, and take down messages, profile parts, stickers, emoji and packs. */
  takeDownContent: "mod",
  /** Mute and ban, within MOD_MAX_MS below admin. */
  restrict: "mod",
  /** Reply in cases, and lift, close and reopen the ones you opened. */
  handleOwnCases: "mod",
  /** The Servers and Stickers & Emoji pages. */
  browseServers: "mod",
  /** Restrictions of any length, permanent ones included. */
  restrictAnyLength: "admin",
  /** Lift, close and reopen anyone's cases. */
  handleAnyCase: "admin",
  /** Server takedowns, Discover holds, relisting packs. */
  takeDownServers: "admin",
  /** Alt accounts and call usage. */
  seeAltsAndCalls: "admin",
  /** Apps & Bots, Announcements, Badges, Call Usage pages. */
  runNexa: "admin",
  /** Acting on many accounts at once. */
  bulkModerate: "admin",
  /** Disabling sign-in and deleting accounts of people who are not staff. */
  closeAccounts: "admin",
  /** The instance's settings, stats and crash reports. */
  instance: "developer",
  /** Giving and taking ranks, and acting on staff. */
  ranks: "owner",
} as const satisfies Record<string, StaffRank>;
export type StaffCapability = keyof typeof STAFF_CAN;

/** Whether a rank (or none) may do something. */
export function can(rank: StaffRank | null, what: StaffCapability): boolean {
  return atLeast(rank, STAFF_CAN[what]);
}

/** Someone's rank from their flags, null for none. The owner's account always reads as the owner. */
export function rankOf(userId: string | null | undefined, flags: number | undefined, ownerId?: string): StaffRank | null {
  const f = flags ?? 0;
  if (userId && (ownerId ? userId === ownerId : isOwner(userId))) return "owner";
  if (f & FLAG_DEVELOPER) return "developer";
  if (f & FLAG_STAFF_ADMIN) return "admin";
  if (f & FLAG_STAFF_MOD) return "mod";
  if (f & FLAG_STAFF_HELPER) return "helper";
  return null;
}

/** Someone's rank from what this device knows of their account, for lists that are not reactive to it. */
export function staffRankOf(userId: string): StaffRank | null {
  const me = useAuth.getState().me;
  return rankOf(userId, me?.id === userId ? me.flags : useUsers.getState().byId[userId]?.flags);
}

/** Whether a rank is need or above it. */
export function atLeast(rank: StaffRank | null, need: StaffRank): boolean {
  return rank !== null && ORDER.indexOf(rank) >= ORDER.indexOf(need);
}

/** The signed-in account's rank. */
export function useMyRank(): StaffRank | null {
  const owner = useOwnerId();
  return useAuth((a) => rankOf(a.me?.id, a.me?.flags, owner));
}

export const RANK_ICON: Record<StaffRank, LucideIcon> = { owner: Crown, developer: CodeXml, admin: Hammer, mod: ShieldCheck, helper: LifeBuoy };

export function rankName(rank: StaffRank): string {
  switch (rank) {
    case "owner":
      return t("profile.profileCard.owner");
    case "developer":
      return t("staff.rank.developer");
    case "admin":
      return t("profile.profileCard.admin");
    case "helper":
      return t("staff.rank.helper");
    default:
      return t("staff.rank.mod");
  }
}

/** What the badge says when pointed at: "Owner of Nexa". */
export function rankTip(rank: StaffRank): string {
  switch (rank) {
    case "owner":
      return t("profile.profileCard.ownerTip");
    case "developer":
      return t("staff.rank.developerTip");
    case "admin":
      return t("profile.profileCard.adminTip");
    case "helper":
      return t("staff.rank.helperTip");
    default:
      return t("staff.rank.modTip");
  }
}

/** What each rank the owner gives can do, in a line. */
export function rankPowers(rank: GrantableRank): string {
  switch (rank) {
    case "developer":
      return t("staff.rank.developerPowers");
    case "admin":
      return t("staff.rank.adminPowers");
    case "helper":
      return t("staff.rank.helperPowers");
    default:
      return t("staff.rank.modPowers");
  }
}
