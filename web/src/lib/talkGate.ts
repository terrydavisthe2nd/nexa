import { MEMBER_QUARANTINED, type User } from "@/api/types";
import { has, P } from "@/lib/perms";
import { serverPerms, type ServerState } from "@/stores/servers";

/**
 * Why someone cannot talk in a server right now, beyond a channel's
 * permissions: a timeout, AutoMod holding them for their name or profile, or
 * the wait the server asks of newcomers. Null when nothing holds them.
 */
export type TalkGate = { kind: "timeout"; until: number } | { kind: "quarantine" } | { kind: "verify"; until: number } | null;

export function talkGate(st: ServerState | undefined, me: Pick<User, "id" | "created_at"> | null | undefined, now = Date.now()): TalkGate {
  if (!st || !me || st.server.owner_id === me.id) return null;
  const m = st.members[me.id] ?? (st.me.user_id === me.id ? st.me : undefined);
  if (!m) return null;
  const p = serverPerms(st, me.id);
  if (has(p, P.ADMINISTRATOR)) return null;
  if (m.timeout_until && m.timeout_until > now) return { kind: "timeout", until: m.timeout_until };
  if ((m.flags & MEMBER_QUARANTINED) !== 0) return { kind: "quarantine" };
  // the wait for newcomers, as the server works it out: anyone with a role, or who may manage the server, is past it
  const account = st.server.verify_account_minutes ?? 0;
  const membership = st.server.verify_member_minutes ?? 0;
  if ((account === 0 && membership === 0) || m.role_ids.length > 0 || has(p, P.MANAGE_SERVER)) return null;
  const until = Math.max(account > 0 ? me.created_at + account * 60_000 : 0, membership > 0 ? m.joined_at + membership * 60_000 : 0);
  return until > now ? { kind: "verify", until } : null;
}
