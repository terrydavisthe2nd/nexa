import { api } from "./client";
import type { BugFile } from "./bugs";
import type { Person } from "./moderation";

export const REPORT_KINDS = [
  "user",
  "server",
  "channel",
  "message",
  "emoji",
  "sticker",
  "pack",
  "note",
  "application",
  "role",
  "other",
] as const;
export type ReportKind = (typeof REPORT_KINDS)[number];
/** In the order the forms list them (the server's own order). */
export const REPORT_REASONS = [
  "harassment",
  "hate",
  "violence",
  "self_harm",
  "sexual",
  "child_safety",
  "underage",
  "spam",
  "scam",
  "privacy",
  "ban_evasion",
  "illegal",
  "other",
] as const;
export type ReportReason = (typeof REPORT_REASONS)[number];
export const REPORT_STATUSES = [
  "open",
  "in_review",
  "actioned",
  "dismissed",
] as const;
export type ReportStatus = (typeof REPORT_STATUSES)[number];
export interface ReportTarget {
  kind: ReportKind;
  id: string;
  subject_id: string;
  server_id: string;
  parts: string[];
  reasons?: string[];
  snapshot: Record<string, unknown>;
  /** This person's own report about the item that still waits on staff. */
  already_reported?: ReportReceipt;
}
/** What a reporter sees of their own report: never notes or who reviewed it. */
export interface ReportReceipt {
  id: string;
  target_kind: ReportKind;
  target_id: string;
  /** The item's name when it was reported ("" for a message). */
  target_label?: string;
  reason: string;
  description: string;
  status: ReportStatus;
  /** What staff told the reporter on closing it ("" for the outcome alone). */
  outcome?: string;
  /** When they were told (0 while nobody has). */
  outcome_at?: number;
  created_at: number;
  updated_at: number;
}
export interface SafetyReport {
  id: string;
  reporter_id: string;
  target_kind: ReportKind;
  target_id: string;
  subject_id: string;
  server_id: string;
  part: string;
  custom_part: string;
  reason: string;
  description: string;
  excerpt: string;
  snapshot: Record<string, unknown>;
  status: ReportStatus;
  assigned_to: string;
  version: number;
  created_at: number;
  updated_at: number;
  /** Open or in-review reports about the same item, this one included. */
  duplicates?: number;
  /** The distinct reasons of those reports, when there is more than one. */
  reasons?: string[];
  /** What the reporter was told on closing it, and when (0: not told). */
  outcome?: string;
  outcome_at?: number;
}
export interface ReportEvent {
  id: string;
  actor_id: string;
  status: ReportStatus;
  assigned_to: string;
  note: string;
  created_at: number;
}
/** Where the responsible account stands, beside a report. */
export interface ReportSubject {
  id: string;
  username: string;
  display_name: string;
  avatar_id: string | null;
  case_count: number;
  open_cases: number;
  alt_count: number;
  mute: { expires_at: number | null; case_number?: number } | null;
  ban: { expires_at: number | null; case_number?: number } | null;
  /** Why this member of staff cannot act on the account ("" when they can). */
  blocked: string;
}
export interface ReportDetail {
  report: SafetyReport;
  events: ReportEvent[];
  files: BugFile[];
  people: Record<string, Person>;
  /** Other reports about the same item or the same account, newest first. */
  related?: SafetyReport[];
  /** How the reporter's reports have gone, by status. */
  reporter_stats?: Record<ReportStatus, number>;
  subject?: ReportSubject | null;
}
export interface ReportList {
  reports: SafetyReport[];
  counts: Record<ReportStatus, number>;
  has_more: boolean;
  people?: Record<string, Person>;
}
export interface ReportedMessage {
  content: Record<string, unknown>;
  attachments: {
    blob_id: string;
    name: string;
    type: string;
    size: number;
    key: string;
    preview?: boolean;
  }[];
  edited_at: number | null;
}
export interface ReportSubmission {
  request_id: string;
  message?: ReportedMessage;
  target_kind: ReportKind;
  target_id: string;
  server_id?: string;
  part: string;
  custom_part: string;
  reason: string;
  description: string;
  excerpt: string;
  files: { id: string; name: string }[];
}
export const reports = {
  target: (
    kind: ReportKind,
    id: string,
    serverId?: string,
    signal?: AbortSignal,
  ) =>
    api.request<ReportTarget>("GET", "/reports/target", undefined, {
      query: { kind, id, server_id: serverId },
      ...(signal ? { signal } : {}),
    }),
  send: (body: ReportSubmission) =>
    api.request<{ report: ReportReceipt }>("POST", "/reports", body),
  mine: (before?: string) =>
    api.request<{ reports: ReportReceipt[]; has_more: boolean }>(
      "GET",
      "/reports",
      undefined,
      { query: { before } },
    ),
  upload: (file: File, signal?: AbortSignal) =>
    api.upload<BugFile>(
      `/reports/attachments?name=${encodeURIComponent(file.name)}`,
      file,
      signal ? { signal } : undefined,
    ),
  list: (query: Record<string, string | undefined>) =>
    api.request<ReportList>("GET", "/moderation/reports", undefined, { query }),
  detail: (id: string) =>
    api.request<ReportDetail>("GET", `/moderation/reports/${id}`),
  review: (
    id: string,
    body: {
      version: number;
      status: ReportStatus;
      note: string;
      assigned_to: string;
      /** Close the other open reports about the same item the same way. */
      resolve_related?: boolean;
      /** Told to the reporter in their Nexa conversation when it closes. */
      reporter_message?: string;
    },
  ) => api.request<ReportDetail>("PATCH", `/moderation/reports/${id}`, body),
  removeMessage: (id: string, version: number, reason: string, reporterMessage = "") =>
    api.request("POST", `/moderation/reports/${id}/remove-message`, {
      version,
      reason,
      reporter_message: reporterMessage,
    }),
  fileUrl: (id: string) => api.url(`/media/reports/${id}`),
};
