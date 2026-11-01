import { api } from "./client";
import type { Message, Snowflake } from "./types";

/** Who a mention named: you, a role you hold, or everyone. */
export type MentionKind = "me" | "roles" | "everyone";

export interface MentionQuery {
  /** Every kind when empty or left out. */
  kinds?: MentionKind[];
  /** A server's id, "@me" for direct messages and groups, or everywhere when left out. */
  place?: Snowflake | "@me";
  sort?: "newest" | "oldest";
  /** The last message of the page before, newest first (before) or oldest first (after). */
  cursor?: Snowflake;
  limit?: number;
}

/** The query string GET /users/@me/mentions takes for a query. */
export function mentionsPath(q: MentionQuery): string {
  const p = new URLSearchParams();
  if (q.kinds && q.kinds.length > 0 && q.kinds.length < 3) p.set("kinds", q.kinds.join(","));
  if (q.place) p.set("server_id", q.place);
  if (q.sort === "oldest") p.set("sort", "oldest");
  if (q.cursor) p.set(q.sort === "oldest" ? "after" : "before", q.cursor);
  if (q.limit) p.set("limit", String(q.limit));
  const s = p.toString();
  return `/users/@me/mentions${s ? `?${s}` : ""}`;
}

export const mentions = {
  /** A page of the messages that mentioned this account, still sealed. */
  list(q: MentionQuery): Promise<Message[]> {
    return api.request<Message[]>("GET", mentionsPath(q));
  },
};
