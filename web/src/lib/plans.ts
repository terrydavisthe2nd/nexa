import type { Plan, PlanLimits, PlansInfo } from "@/api/types";
import type { ShareAllowance } from "@/voice/shareQuality";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useServers } from "@/stores/servers";

const MiB = 1024 * 1024;
const GiB = 1024 * MiB;

/**
 * What each plan allows (api/billing.go), until the server says. A plan only
 * raises what costs money to keep; everything else in Nexa is the same on all three.
 */
let plans: PlansInfo = {
  enabled: false,
  free: { file_max: 1 * GiB, month_upload_max: 5 * GiB, share_quality: false, share_max_bps: 6_000_000, name_effects: false, boosts: 0 },
  supporter: { file_max: 2 * GiB, month_upload_max: 100 * GiB, share_quality: true, share_max_bps: 12_000_000, name_effects: true, boosts: 0 },
  pro: { file_max: 4 * GiB, month_upload_max: 500 * GiB, share_quality: true, share_max_bps: 20_000_000, name_effects: true, boosts: 2 },
  boost_file_max: 2 * GiB,
};

/** Takes the table the instance answered with. */
export function setPlans(info: PlansInfo | undefined): void {
  if (info?.free && info.supporter && info.pro) plans = info;
}

/** The largest file on the free plan, which is the one limit a server from before plans reports. */
export function setFreeFileMax(n: number): void {
  plans = { ...plans, free: { ...plans.free, file_max: n } };
}

export function plansInfo(): PlansInfo {
  return plans;
}

/** The plans people can buy, cheapest first. */
export const PAID_PLANS = ["supporter", "pro"] as const;

/** The plan the account has now ("" is free). */
export function myPlan(): Plan {
  return useAuth.getState().me?.plan ?? "";
}

export function limitsOf(plan: Plan | undefined): PlanLimits {
  return plan === "supporter" ? plans.supporter : plan === "pro" ? plans.pro : plans.free;
}

export function myLimits(): PlanLimits {
  return limitsOf(myPlan());
}

/** Whether a server has a boost on it, which lets everyone there send bigger files. */
export function serverBoosted(serverId: string | null | undefined): boolean {
  return !!serverId && (useServers.getState().byId[serverId]?.server.boosts ?? 0) > 0;
}

/** The largest file this account may send to a channel: its plan's, or a boosted server's when that is more. */
export function fileMaxIn(channelId?: string): number {
  const own = myLimits().file_max;
  const serverId = channelId ? useChannels.getState().byId[channelId]?.server_id : null;
  return serverBoosted(serverId) ? Math.max(own, plans.boost_file_max) : own;
}

/** The cheapest plan that sends a file this big, or null when none does. */
export function planForFile(size: number): Plan | null {
  for (const p of PAID_PLANS) if (plans[p].file_max >= size) return p;
  return null;
}

/** The name effects only plans have (api/billing.go paidNameEffects). */
export const PAID_EFFECTS: ReadonlySet<string> = new Set(["holo", "neon", "flame"]);

/** What this account's plan allows a screen share (voice/shareQuality.ts sharePlan). */
export function shareAllowance(): ShareAllowance {
  const l = myLimits();
  return { quality: l.share_quality, maxBps: l.share_max_bps };
}

/** Pay what you want, in cents: from a dollar to the most one payment may be (api/billing.go pwywMin, pwywMax). */
export const PWYW_MIN = 100;
export const PWYW_MAX = 99_999_999;

/**
 * What a pay-what-you-want amount buys, as api/billing.go pwywSplit works it
 * out: a month (30 days) of Pro for every month price of Pro, then a month of
 * Supporter for every month price of Supporter in what is left. Prices are the
 * one-time month prices in cents.
 */
export function payWhatYouWant(amount: number, supporterMonth = 500, proMonth = 1000): { pro: number; supporter: number } {
  if (supporterMonth <= 0 || proMonth <= 0 || amount < PWYW_MIN) return { pro: 0, supporter: 0 };
  const pro = Math.floor(amount / proMonth);
  return { pro, supporter: Math.floor((amount - pro * proMonth) / supporterMonth) };
}

/** Cents from what someone typed as dollars ("12", "12.5", "12,50"), or null when it isn't an amount. */
export function centsOf(text: string): number | null {
  const m = /^\s*\$?\s*(\d{1,9})(?:[.,](\d{0,2}))?\s*$/.exec(text);
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? "").padEnd(2, "0"));
}
