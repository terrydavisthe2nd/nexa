import { api } from "./client";
import type { AdminSettings, AdminStats, AdminUser, InstanceInfo, LegalReport, Snowflake } from "./types";

/** One crash a browser reported. */
export interface ClientErrorReport {
  id: string;
  user_id: string | null;
  kind: "error" | "render" | "promise";
  message: string;
  stack: string;
  component: string;
  path: string;
  user_agent: string;
  version: string;
  created_at: number;
}

export const admin = {
  instance(): Promise<InstanceInfo> {
    return api.request<InstanceInfo>("GET", "/instance");
  },
  stats(): Promise<AdminStats> {
    return api.request<AdminStats>("GET", "/admin/stats");
  },
  users(q = "", before?: Snowflake): Promise<AdminUser[]> {
    return api.request<AdminUser[]>("GET", "/admin/users", undefined, { query: { q, before, limit: 50 } });
  },
  patchUser(id: Snowflake, body: { disabled?: boolean; admin?: boolean }): Promise<AdminUser> {
    return api.request<AdminUser>("PATCH", `/admin/users/${id}`, body);
  },
  /** Deletes someone's account: their profile goes, their messages stay under Deleted User. */
  settings(): Promise<AdminSettings> {
    return api.request<AdminSettings>("GET", "/admin/settings");
  },
  errors(limit = 100): Promise<ClientErrorReport[]> {
    return api.request<ClientErrorReport[]>("GET", "/admin/errors", undefined, { query: { limit } });
  },
  clearErrors(): Promise<void> {
    return api.request<void>("DELETE", "/admin/errors");
  },
  patchSettings(body: { registration?: string; registration_key?: string; signup_ip_limit?: boolean }): Promise<AdminSettings> {
    return api.request<AdminSettings>("PATCH", "/admin/settings", body);
  },
  /**
   * Compiles what Nexa holds about one account, for an officer who has
   * served valid legal process. `user` is a username or an account id. This
   * reads existing rows and writes nothing.
   */
  legalReport(user: string): Promise<LegalReport> {
    return api.request<LegalReport>("GET", "/admin/legal-report", undefined, { query: { user } });
  },
};
