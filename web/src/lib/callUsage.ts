/**
 * How the staff's Calls page and an account's Call Usage panel write what
 * calls used. Sizes are in thousands, not 1024s, the way Cloudflare bills and
 * the monthly allowance is set, so a share of it reads true.
 */
import { t, translator } from "@/i18n";

// gigabytes at most, as the allowance is set: "2,292 GB of 1,000 GB" compares at a glance
const sizeUnits: [number, string][] = [
  [1e9, "gigabyte"],
  [1e6, "megabyte"],
  [1e3, "kilobyte"],
];

const formats = new Map<string, Intl.NumberFormat>();

function unitFormat(unit: string, digits: number): Intl.NumberFormat {
  const locale = translator().locale;
  const key = `${locale}:${unit}:${digits}`;
  let f = formats.get(key);
  if (!f) {
    const options: Intl.NumberFormatOptions = { style: "unit", unit, unitDisplay: "short", maximumFractionDigits: digits };
    try {
      f = new Intl.NumberFormat(locale, options);
    } catch {
      f = new Intl.NumberFormat(undefined, options);
    }
    formats.set(key, f);
  }
  return f;
}

/** A size such as "2.4 GB" or "740 MB", in the reader's language. */
export function formatDataSize(bytes: number): string {
  const n = Math.max(0, bytes);
  for (const [size, unit] of sizeUnits) {
    if (n >= size) {
      const v = n / size;
      return unitFormat(unit, v < 10 ? 1 : 0).format(v);
    }
  }
  return t("time.bytes", { n: Math.round(n) });
}

/** Time in calls such as "2 hr, 5 min" or "40 min", in the reader's language. */
export function formatCallTime(seconds: number): string {
  const total = Math.max(0, Math.round(seconds / 60));
  const h = Math.floor(total / 60);
  const m = total % 60;
  const locale = translator().locale;
  const DurationFormat = (Intl as unknown as { DurationFormat?: new (l: string, o: object) => { format(d: object): string } }).DurationFormat;
  if (DurationFormat) {
    try {
      return new DurationFormat(locale, { style: "short" }).format(h ? { hours: h, minutes: m } : { minutes: m });
    } catch {
      /* the units on their own below */
    }
  }
  const mins = unitFormat("minute", 0).format(m);
  return h ? `${unitFormat("hour", 0).format(h)} ${mins}` : mins;
}

/** How much of the month's allowance a size is, from 0 to 100 (more when over). */
export function shareOfAllowance(bytes: number, limit: number): number {
  return limit > 0 ? (Math.max(0, bytes) / limit) * 100 : 0;
}

/** The width a share of the allowance is drawn at: never wider than the bar, and never so thin it vanishes. */
export function barWidth(percent: number): string {
  if (percent <= 0) return "0%";
  return `${Math.min(100, Math.max(0.6, percent))}%`;
}
