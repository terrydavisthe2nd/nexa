import type { Message } from "@/api/types";

/** What a mention of this account was, for its tag: you, which role, or everyone. */
export type MentionOf = { kind: "me" } | { kind: "role"; roleId: string } | { kind: "everyone" };

/**
 * Why a message is in this account's mentions. Naming you wins over a role,
 * and a role over everyone, since that is the reason it was meant for you.
 */
export function mentionOf(msg: Pick<Message, "mentions" | "mention_roles" | "mention_everyone">, meId: string, myRoles: readonly string[]): MentionOf {
  if (msg.mentions.includes(meId)) return { kind: "me" };
  const role = msg.mention_roles.find((r) => myRoles.includes(r));
  if (role) return { kind: "role", roleId: role };
  if (msg.mention_everyone) return { kind: "everyone" };
  // a role taken away since: still a role mention, now of nobody here
  if (msg.mention_roles[0]) return { kind: "role", roleId: msg.mention_roles[0] };
  return { kind: "everyone" };
}

/** The local calendar day a time falls on, to group a list under day lines. */
export function dayOf(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

/** Joins a page onto what is already there, keeping order and dropping repeats. */
export function appendPage<T extends { id: string }>(have: T[], page: T[]): T[] {
  const seen = new Set(have.map((m) => m.id));
  return [...have, ...page.filter((m) => !seen.has(m.id))];
}
