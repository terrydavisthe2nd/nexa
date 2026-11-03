/**
 * What the Countdown and Local Time widgets say (lib/widgets.ts), worked out
 * on the viewer's device against its clock, corrected by the server's
 * (lib/serverClock.ts), so a wrong device clock does not move a countdown.
 * Android's data/TimeWidgets.kt follows the same rules.
 */
import { t, translator } from "@/i18n";
import type { CountdownWidget } from "@/lib/widgets";

const DAY = 86_400_000;

/** A whole day's start in the viewer's own calendar. */
function dayStart(date: string): number {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  return new Date(y, m - 1, d).getTime();
}

function midnight(ms: number): number {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

export type CountdownState =
  /** Still to come: `left` says how long, in words. */
  | { kind: "coming"; left: string }
  /** The day itself, or the moment has come within the last day. */
  | { kind: "today" }
  /** Over for more than a day: the widget is not shown. */
  | { kind: "gone" };

/** Where a countdown stands at `now`. */
export function countdownState(w: CountdownWidget, now: number, locale = translator().locale): CountdownState {
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  if (w.date) {
    const start = dayStart(w.date);
    // whole days in the viewer's calendar, so tomorrow is tomorrow whatever the hour
    const days = Math.round((start - midnight(now)) / DAY);
    if (days > 0) return { kind: "coming", left: rtf.format(days, "day") };
    if (days === 0) return { kind: "today" };
    // the day after, it is still shown as having been; after that it goes
    return days >= -1 ? { kind: "today" } : { kind: "gone" };
  }
  const at = w.at ?? 0;
  const ms = at - now;
  if (ms <= 0) return ms > -DAY ? { kind: "today" } : { kind: "gone" };
  const mins = Math.ceil(ms / 60_000);
  if (mins < 60) return { kind: "coming", left: rtf.format(mins, "minute") };
  const hours = Math.round(ms / 3_600_000);
  if (hours < 24) return { kind: "coming", left: rtf.format(hours, "hour") };
  return { kind: "coming", left: rtf.format(Math.round((midnight(at) - midnight(now)) / DAY), "day") };
}

/** When a countdown is, as a date (and a time for a moment) in the viewer's words. */
export function countdownWhen(w: CountdownWidget, locale = translator().locale): string {
  if (w.date) return new Date(dayStart(w.date)).toLocaleDateString(locale, { weekday: "long", year: "numeric", month: "long", day: "numeric" });
  return new Date(w.at ?? 0).toLocaleString(locale, { weekday: "long", year: "numeric", month: "long", day: "numeric", hour: "numeric", minute: "2-digit" });
}

/** A zone's offset from UTC at `now`, in minutes (Anchorage in winter: -540). */
export function zoneOffset(zone: string, now: number): number {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: zone, hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric" }).formatToParts(new Date(now));
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0);
  const asUtc = Date.UTC(n("year"), n("month") - 1, n("day"), n("hour") % 24, n("minute"));
  return Math.round((asUtc - Math.floor(now / 60_000) * 60_000) / 60_000);
}

/** The time in a zone at `now`, as the viewer writes times ("3:14 AM", "03:14"). */
export function zoneTime(zone: string, now: number, locale = translator().locale): string {
  return new Date(now).toLocaleTimeString(locale, { timeZone: zone, hour: "numeric", minute: "2-digit" });
}

/** A zone's place, from its name: "America/Port_of_Spain" is Port of Spain. */
export function zonePlace(zone: string): string {
  return (zone.split("/").pop() ?? zone).replace(/_/g, " ");
}

/** How far a zone is from the viewer's: "8 hours ahead", "30 minutes behind", or "Same time as you". */
export function zoneGap(zone: string, now: number): string {
  const diff = zoneOffset(zone, now) + new Date(now).getTimezoneOffset();
  if (diff === 0) return t("widgets.localtime.same");
  const abs = Math.abs(diff);
  const h = Math.floor(abs / 60);
  const m = abs % 60;
  const amount = h && m ? t("widgets.localtime.hoursMinutes", { hours: h, minutes: m }) : h ? t("widgets.localtime.hours", { count: h }) : t("widgets.localtime.minutes", { count: m });
  return diff > 0 ? t("widgets.localtime.ahead", { amount }) : t("widgets.localtime.behind", { amount });
}

/** Every time zone this browser knows, for the picker, with the device's own first. */
export function allZones(): string[] {
  const own = Intl.DateTimeFormat().resolvedOptions().timeZone;
  let list: string[] = [];
  try {
    list = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
  } catch {
    list = [];
  }
  if (own && !list.includes(own)) list = [own, ...list];
  return list.length ? list : ["UTC"];
}
