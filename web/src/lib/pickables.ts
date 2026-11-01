import type { Channel, PublicUser, Role } from "@/api/types";
import { servers as serversApi } from "@/api/servers";
import { rankPeople, recentSpeakers } from "@/components/composer/rankPeople";
import { P } from "@/lib/perms";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useMessages } from "@/stores/messages";
import { canI, rolesByPosition, useServers } from "@/stores/servers";
import { useUsers } from "@/stores/users";

/**
 * What someone can pick when an app asks for a person, a role or a channel:
 * in a select menu on its message, an option of its command, or a field of
 * its form. The people, roles and channels of where it was used, as the
 * message box's @ and # suggestions find them.
 */

/** A channel's kind, as bot libraries number them. */
export function protocolChannelType(ch: Pick<Channel, "type">): number {
  switch (ch.type) {
    case "dm":
      return 1;
    case "voice":
      return 2;
    case "group_dm":
      return 3;
    case "category":
      return 4;
    case "announcement":
      return 5;
    case "thread":
      return 11;
    case "private_thread":
      return 12;
    case "forum":
      return 15;
    case "media":
      return 16;
    default:
      return 0;
  }
}

/** The people of where something was used that match what was typed, those who spoke last first. */
export function peopleFor(channelId: string, query: string, limit = 25): PublicUser[] {
  const ch = useChannels.getState().byId[channelId];
  const users = useUsers.getState().byId;
  const st = ch?.server_id ? useServers.getState().byId[ch.server_id] : undefined;
  const pool = new Map<string, PublicUser>();
  if (st) {
    for (const id of Object.keys(st.members)) if (users[id]) pool.set(id, users[id]!);
  } else if (ch) {
    for (const id of ch.recipient_ids) if (users[id]) pool.set(id, users[id]!);
    const me = useAuth.getState().me;
    if (me && users[me.id]) pool.set(me.id, users[me.id]!);
  }
  const here = useMessages.getState().channels[channelId];
  const authors = here ? here.ids.slice(-100).reverse().map((id) => here.byId[id]?.author_id ?? "") : [];
  const nick = (id: string) => st?.members[id]?.nickname ?? "";
  return rankPeople(query.trim(), [...pool.values()], nick, recentSpeakers(authors)).slice(0, limit);
}

const asked = new Set<string>();

/**
 * Asks the server for a big server's members by name, once per name, for
 * the pickers above: its members come a page at a time, and whoever is not
 * loaded yet would otherwise never be offered. Resolves once they are in.
 */
export async function findMembers(channelId: string, query: string): Promise<void> {
  const ch = useChannels.getState().byId[channelId];
  const st = ch?.server_id ? useServers.getState().byId[ch.server_id] : undefined;
  const q = query.trim();
  if (!st || st.membersComplete || q.length < 2) return;
  const key = `${st.server.id}:${q.toLowerCase()}`;
  if (asked.has(key)) return;
  asked.add(key);
  try {
    const res = await serversApi.members(st.server.id, { q, limit: 25 });
    useUsers.getState().upsert(res.users);
    useServers.getState().upsertMembers(st.server.id, res.members);
  } catch {
    asked.delete(key);
  }
}

/** The roles of where something was used that match what was typed, highest first; @everyone last. */
export function rolesFor(channelId: string, query: string, limit = 25): Role[] {
  const ch = useChannels.getState().byId[channelId];
  const st = ch?.server_id ? useServers.getState().byId[ch.server_id] : undefined;
  if (!st) return [];
  const q = query.trim().toLowerCase();
  return rolesByPosition(st)
    .filter((r) => !q || r.name.toLowerCase().includes(q))
    .slice(0, limit);
}

/** The channels of where something was used that this account can see, of the kinds asked for. */
export function channelsFor(channelId: string, query: string, types?: readonly number[], limit = 25): Channel[] {
  const all = useChannels.getState().byId;
  const ch = all[channelId];
  if (!ch) return [];
  // outside a server the only channel there is, is this one
  if (!ch.server_id) return types?.length && !types.includes(protocolChannelType(ch)) ? [] : [ch];
  const st = useServers.getState().byId[ch.server_id];
  const q = query.trim().toLowerCase();
  return Object.values(all)
    .filter((c) => c.server_id === ch.server_id && (!types?.length || types.includes(protocolChannelType(c))) && (!q || c.name.toLowerCase().includes(q)) && canI(st, P.VIEW_CHANNEL, c.id))
    .sort((a, b) => a.position - b.position || a.name.localeCompare(b.name))
    .slice(0, limit);
}
