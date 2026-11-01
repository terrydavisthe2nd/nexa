import { createContext, useContext } from "react";
import { useChannels } from "@/stores/channels";

/**
 * A media channel can hide the download buttons on its pictures and videos
 * (its `forum.hide_downloads`). It is a courtesy the apps keep, not a lock:
 * whoever can see a picture can still keep it. Drawn attachments learn it
 * from this context; menus ask `downloadsHidden` by the message's channel.
 */
export const NoDownloads = createContext(false);

/** Whether the pictures and videos drawn here leave out their download, save, copy and open buttons. */
export function useNoDownloads(): boolean {
  return useContext(NoDownloads);
}

/** Whether a channel's pictures and videos offer no downloads: a post in a media channel that hides them. */
export function downloadsHidden(channelId: string | null | undefined): boolean {
  if (!channelId) return false;
  const byId = useChannels.getState().byId;
  const ch = byId[channelId];
  const parent = ch?.parent_id && (ch.type === "thread" || ch.type === "private_thread") ? byId[ch.parent_id] : ch;
  return parent?.type === "media" && !!parent.forum?.hide_downloads;
}

/** The same, kept current as the channel's settings change. */
export function useDownloadsHidden(channelId: string | null | undefined): boolean {
  return useChannels(() => downloadsHidden(channelId));
}
