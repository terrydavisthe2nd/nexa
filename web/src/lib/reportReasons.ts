import { Baby, CircleHelp, CircleOff, Fingerprint, Gavel, HeartCrack, LifeBuoy, Mails, MessageSquareWarning, ShieldAlert, Swords, UserX, VenetianMask, type LucideIcon } from "lucide-react";

/**
 * How each report reason looks wherever reports are shown: its icon, and how
 * urgent it is. Urgent reasons (someone may be in danger) carry the danger
 * colour and a note about emergency services; serious ones the warning colour;
 * the rest stay quiet. Android keeps the same table in data/Reports.kt.
 */
export type ReasonTone = "urgent" | "serious" | "routine";

const REASONS: Record<string, { icon: LucideIcon; tone: ReasonTone }> = {
  harassment: { icon: MessageSquareWarning, tone: "serious" },
  hate: { icon: HeartCrack, tone: "serious" },
  violence: { icon: Swords, tone: "urgent" },
  self_harm: { icon: LifeBuoy, tone: "urgent" },
  sexual: { icon: CircleOff, tone: "serious" },
  child_safety: { icon: ShieldAlert, tone: "urgent" },
  underage: { icon: Baby, tone: "serious" },
  spam: { icon: Mails, tone: "routine" },
  scam: { icon: VenetianMask, tone: "serious" },
  privacy: { icon: Fingerprint, tone: "serious" },
  ban_evasion: { icon: UserX, tone: "routine" },
  illegal: { icon: Gavel, tone: "serious" },
  other: { icon: CircleHelp, tone: "routine" },
};

export function reasonIcon(reason: string): LucideIcon {
  return REASONS[reason]?.icon ?? CircleHelp;
}

export function reasonTone(reason: string): ReasonTone {
  return REASONS[reason]?.tone ?? "routine";
}
