import { api } from "./client";
import type { Snowflake } from "./types";

export interface UploadSession {
  id: Snowflake;
  size: number;
  received: number;
  chunk_size: number;
  /** Present when the file goes in parts the server streams on to storage: every part but the last is part_size bytes. */
  relay?: { part_size: number; parts: number };
}

/**
 * Transfers too large for one request. The file goes up in pieces, each one
 * placed at the offset the server says it is expecting, so a dropped
 * connection costs one piece rather than the whole upload.
 */
export const uploads = {
  /** `channelId` is where the file goes, when it is a channel: a boosted server takes bigger files. */
  /** `relay` asks to send the file in parts streamed on to storage (PUT /uploads/{id}/parts/{n}). */
  create(size: number, channelId?: Snowflake, relay?: boolean): Promise<UploadSession> {
    return api.request<UploadSession>("POST", "/uploads", { size, ...(channelId ? { channel_id: channelId } : {}), ...(relay ? { relay: true } : {}) });
  },
  status(id: Snowflake): Promise<UploadSession> {
    return api.request<UploadSession>("GET", `/uploads/${id}`);
  },
  finish(id: Snowflake): Promise<{ blob_id: Snowflake; size: number }> {
    return api.request("POST", `/uploads/${id}/finish`);
  },
  cancel(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/uploads/${id}`);
  },
};
