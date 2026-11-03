import { Bot, Bug, CalendarHeart, Gift, Star, Wrench, type LucideIcon } from "lucide-react";
import type { Badge, PublicUser } from "@/api/types";
import { t } from "@/i18n";
import { rankName, rankOf } from "@/lib/staffRank";

/**
 * Profile badges (internal/store/badges.go; Android data/Badges.kt). The
 * server works out which an account holds and puts them in its order; this
 * names and draws them. Staff rank and plan keep their own badges
 * (components/common/OwnerBadge.tsx).
 */

/** The badges staff give by hand, beside the event badges they make. */
export const CONTRIBUTOR = "contributor";
export const EVENT_PREFIX = "event:";

/** Whether a badge is an event badge staff made. */
export const isEventBadge = (id: string) => id.startsWith(EVENT_PREFIX);

/** The icons the Badges tab offers for an event badge (Lucide names, which Android draws too). */
export const EVENT_ICONS = [
  "award", "trophy", "medal", "crown", "star", "sparkles", "heart", "flame",
  "zap", "rocket", "ghost", "skull", "candy", "snowflake", "tree-pine", "gift",
  "party-popper", "cake", "music", "gamepad-2", "palette", "camera", "paintbrush", "pen-tool",
  "flag", "shield", "gem", "sun", "moon", "cloud", "leaf", "flower-2",
  "bug", "code", "terminal", "megaphone", "mic", "headphones", "book-open", "graduation-cap",
] as const;

/** The colours the Badges tab offers first; any other can be picked. */
export const EVENT_COLOURS = [0xf0c04a, 0xff7a45, 0xff5c93, 0xb48cff, 0x6aa2ff, 0x4cc9b0, 0x5fd17a, 0xb9c3cf];

/** How a badge looks: its tone (a key of Badges.module.css), its icon, words and tooltip. */
export interface BadgeLook {
  tone: "og" | "years" | "bug" | "dev" | "bronze" | "silver" | "gold" | "contributor" | "event";
  Icon?: LucideIcon;
  /** An event badge's Lucide icon by name, loaded when shown. */
  iconName?: string;
  color?: number;
  label: string;
  tip: string;
}

const GIFT_TONES = ["bronze", "silver", "gold"] as const;

/** The look of an earned or given badge; null for staff and plan, which draw themselves, and for ids this app does not know. */
export function badgeLook(b: Badge): BadgeLook | null {
  switch (b.id) {
    case "og":
      return { tone: "og", Icon: Star, label: t("badges.og"), tip: t("badges.ogTip") };
    case "years": {
      const count = Math.max(1, b.level ?? 1);
      return { tone: "years", Icon: CalendarHeart, label: t("badges.years", { count }), tip: t("badges.yearsTip", { count }) };
    }
    case "bug_hunter":
      return { tone: "bug", Icon: Bug, label: t("badges.bugHunter"), tip: t("badges.bugHunterTip") };
    case "app_developer":
      return { tone: "dev", Icon: Bot, label: t("badges.appDeveloper"), tip: t("badges.appDeveloperTip") };
    case "gift_giver": {
      const level = Math.min(3, Math.max(1, b.level ?? 1));
      const tone = GIFT_TONES[level - 1]!;
      return { tone, Icon: Gift, label: t("badges.giftGiver"), tip: t(`badges.giftGiverTip.${tone}`) };
    }
    case CONTRIBUTOR:
      return { tone: "contributor", Icon: Wrench, label: t("badges.contributor"), tip: t("badges.contributorTip") };
    default:
      if (isEventBadge(b.id) && b.name) return { tone: "event", iconName: b.icon, color: b.color, label: b.name, tip: t("badges.eventTip") };
      return null;
  }
}

/** A badge's name in a list (the designer, the staff panel), staff and plan included. */
export function badgeName(b: Badge, user: Pick<PublicUser, "id" | "flags" | "plan">): string {
  if (b.id === "staff") {
    const rank = rankOf(user.id, user.flags);
    return rank ? rankName(rank) : t("badges.staff");
  }
  if (b.id === "plan") return user.plan === "pro" ? t("support.plan.pro") : t("support.plan.supporter");
  return badgeLook(b)?.label ?? b.id;
}

/** What a profile shows before its badges arrive, or where there is no list: the staff rank and the plan. */
export function fallbackBadges(user: Pick<PublicUser, "id" | "flags" | "plan">): Badge[] {
  const out: Badge[] = [];
  if (rankOf(user.id, user.flags)) out.push({ id: "staff" });
  if (user.plan === "supporter" || user.plan === "pro") out.push({ id: "plan" });
  return out;
}

/** A colour number as CSS. */
export const hexColour = (c: number) => `#${(c & 0xffffff).toString(16).padStart(6, "0")}`;
