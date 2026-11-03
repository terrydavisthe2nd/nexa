import { lazy, Suspense, type CSSProperties } from "react";
import type { Badge, PublicUser } from "@/api/types";
import { PlanBadge, StaffBadge } from "@/components/common/OwnerBadge";
import { Tooltip } from "@/components/common/Tooltip";
import { badgeLook, fallbackBadges, hexColour } from "@/lib/badges";
import { rankOf } from "@/lib/staffRank";
import s from "./Badges.module.css";

const EventIcon = lazy(() => import("./EventIcon"));

type Who = Pick<PublicUser, "id" | "flags" | "plan">;

/**
 * One badge. Staff rank and plan stay the tags they always were; every other
 * badge is its icon alone on a small tile in its own tone (an event badge in
 * the colour staff chose), its name and meaning in the tooltip. [named] puts
 * the name beside the icon, for lists (the designer, the staff panel).
 */
export function BadgePill({ badge, user, still, named }: { badge: Badge; user: Who; still?: boolean; named?: boolean }) {
  if (badge.id === "staff") {
    const rank = rankOf(user.id, user.flags);
    return rank ? <StaffBadge rank={rank} /> : null;
  }
  if (badge.id === "plan") return <PlanBadge plan={user.plan} />;
  const look = badgeLook(badge);
  if (!look) return null;
  const style = look.color !== undefined ? ({ "--tone": hexColour(look.color) } as CSSProperties) : undefined;
  const tile = (
    <span className={s.badge} data-tone={look.tone} style={style} tabIndex={still ? undefined : 0} role="img" aria-label={`${look.label}. ${look.tip}`} data-testid={`badge-${badge.id}`}>
      {look.Icon ? (
        <look.Icon size={15} strokeWidth={2.25} aria-hidden="true" />
      ) : (
        <Suspense fallback={<span className={s.iconSlot} aria-hidden="true" />}>
          <EventIcon name={look.iconName} size={15} />
        </Suspense>
      )}
    </span>
  );
  if (named) {
    return (
      <span className={s.named}>
        {tile}
        <span className={s.name} dir="auto">{look.label}</span>
      </span>
    );
  }
  return still ? tile : <Tooltip text={`${look.label}: ${look.tip}`} side="top">{tile}</Tooltip>;
}

/**
 * A profile's badges in its holder's order. "tags" is the staff rank and
 * plan, drawn on the username line beside the pronouns; "icons" is every
 * other badge, on its own line under it. Until the list arrives, the tags
 * come from the account itself.
 */
export function BadgeRow({ user, badges, part, className }: { user: Who; badges: Badge[] | undefined; part: "tags" | "icons"; className?: string }) {
  const isTag = (b: Badge) => b.id === "staff" || b.id === "plan";
  const list = (badges ?? fallbackBadges(user)).filter((b) => !b.hidden && isTag(b) === (part === "tags"));
  if (!list.length) return null;
  const items = list.map((b) => <BadgePill key={b.id} badge={b} user={user} />);
  // tags sit among the username line's own items, which already wrap and space them
  if (part === "tags") return <>{items}</>;
  return (
    <div className={[s.row, className].filter(Boolean).join(" ")} data-testid="profile-badges">
      {items}
    </div>
  );
}
