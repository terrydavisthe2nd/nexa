import { threads as threadsApi } from "@/api/threads";
import type { Channel } from "@/api/types";
import { t } from "@/i18n";
import { P } from "@/lib/perms";
import { useChannels } from "@/stores/channels";
import { useKeys } from "@/stores/keys";
import { canI, useServers } from "@/stores/servers";
import { useThreads } from "@/stores/threads";

/** How long a thread may go quiet before it closes itself, in minutes, as the server allows. */
export const AUTO_ARCHIVE = [60, 1440, 4320, 10080] as const;

export function autoArchiveLabel(minutes: number): string {
  switch (minutes) {
    case 60:
      return t("threads.archive.hour");
    case 1440:
      return t("threads.archive.day");
    case 4320:
      return t("threads.archive.threeDays");
    default:
      return t("threads.archive.week");
  }
}

/** Whether a channel only holds posts: a forum, or a media channel (a forum of pictures and videos). */
export function isForum(ch: Pick<Channel, "type"> | undefined | null): boolean {
  return ch?.type === "forum" || ch?.type === "media";
}

/** Whether threads start in a channel of this kind: text, announcement, forum and media channels. */
export function holdsThreads(ch: Pick<Channel, "type"> | undefined): boolean {
  return ch?.type === "text" || ch?.type === "announcement" || isForum(ch);
}

/** Whether this account may start a thread in a channel (a private one when asked). */
export function canStartThread(ch: Channel | undefined, priv = false): boolean {
  if (!ch?.server_id || !holdsThreads(ch) || isForum(ch)) return false;
  const st = useServers.getState().byId[ch.server_id];
  if (priv) return ch.type === "text" && canI(st, P.CREATE_PRIVATE_THREADS, ch.id);
  return canI(st, P.CREATE_PUBLIC_THREADS, ch.id);
}

/** Whether this account may manage a thread: close, lock, rename, delete and take people out of it. */
export function canManageThread(thread: Channel | undefined): boolean {
  if (!thread?.server_id) return false;
  return canI(useServers.getState().byId[thread.server_id], P.MANAGE_THREADS, thread.id);
}

/**
 * Starts a thread, from a message or on its own, and comes back with it once
 * this app holds it: a private one gets its own key here first.
 */
export async function startThread(parent: Channel, body: { name: string; messageId?: string; private?: boolean; autoArchive?: number }): Promise<Channel> {
  const res = await threadsApi.create(parent.id, {
    name: body.name,
    ...(body.messageId ? { message_id: body.messageId } : {}),
    ...(body.private ? { private: true } : {}),
    ...(body.autoArchive ? { auto_archive: body.autoArchive } : {}),
  });
  const thread = res.thread;
  useChannels.getState().upsert(thread);
  useThreads.getState().setJoined(thread.id, true);
  if (res.key_actions?.needs_own_key) await useKeys.getState().ensureConvKey(res.key_actions.conv_id);
  return thread;
}

/** A name for a thread from what its message says: the start of its first line. */
export function nameFromText(text: string): string {
  const line = text
    .replace(/<[@#&:a][^>]*>/g, "")
    .split("\n")
    .map((l) => l.trim())
    .find(Boolean);
  if (!line) return "";
  return line.length > 60 ? `${line.slice(0, 57).trimEnd()}…` : line;
}
