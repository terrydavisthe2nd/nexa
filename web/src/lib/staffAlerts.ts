import type { Settings, StaffAlertSettings } from "@/stores/settings";
import { reasonTone } from "./reportReasons";

/**
 * What staff are told about, with the defaults filled in: every new report,
 * members writing in cases and bug reports, until someone turns them down in
 * Settings, Notifications. Android reads the same keys (notify/StaffAlerts.kt).
 */
export function staffAlerts(settings: Settings): Required<StaffAlertSettings> {
  const s = settings.notifications.staff ?? {};
  return {
    reports: s.reports === "urgent" || s.reports === "off" ? s.reports : "all",
    cases: s.cases !== false,
    bugs: s.bugs !== false,
  };
}

/** Whether a report just sent for this reason is one to tell this person about. */
export function alertsForReport(settings: Settings, reason: string): boolean {
  const level = staffAlerts(settings).reports;
  return level === "all" || (level === "urgent" && reasonTone(reason) === "urgent");
}
