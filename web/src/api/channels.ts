import { ApiError, api, errorFromResponse } from "./client";
import type { Channel, Invite, Message, Snowflake } from "./types";

import { t } from "@/i18n";
type Progress = (loaded: number, total: number) => void;

/** How much of a download is gathered before it is handed to the browser's blob store. */
const FOLD_BYTES = 16 * 1024 * 1024;

export function attachmentUrl(blobId: Snowflake): string {
  return `${api.baseUrl}/media/attachments/${blobId}`;
}

export const channels = {
  createDM(recipientIds: Snowflake[], name?: string): Promise<Channel> {
    const body: { recipient_ids: Snowflake[]; name?: string } = { recipient_ids: recipientIds };
    if (name !== undefined) body.name = name;
    return api.request<Channel>("POST", "/users/@me/channels", body);
  },
  listDMs(): Promise<Channel[]> {
    return api.request<Channel[]>("GET", "/users/@me/channels");
  },
  get(id: Snowflake): Promise<Channel> {
    return api.request<Channel>("GET", `/channels/${id}`);
  },
  patch(id: Snowflake, body: { name?: string; owner_id?: Snowflake; password_key?: string; password_salt?: string; password_params?: unknown; password_remove?: boolean }): Promise<Channel> {
    return api.request<Channel>("PATCH", `/channels/${id}`, body);
  },
  uploadIcon(id: Snowflake, file: Blob, crop?: { x: number; y: number; w: number; h: number } | null, onProgress?: Progress): Promise<Channel> {
    const q = crop ? `?x=${Math.round(crop.x)}&y=${Math.round(crop.y)}&w=${Math.round(crop.w)}&h=${Math.round(crop.h)}` : "";
    const opts: { method: string; headers: Record<string, string>; onProgress?: Progress } = {
      method: "PUT",
      headers: { "Content-Type": file.type || "application/octet-stream" },
    };
    if (onProgress) opts.onProgress = onProgress;
    return api.upload<Channel>(`/channels/${id}/icon${q}`, file, opts);
  },
  deleteIcon(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${id}/icon`);
  },
  addRecipient(id: Snowflake, uid: Snowflake): Promise<Channel> {
    return api.request<Channel>("POST", `/channels/${id}/recipients/${uid}`);
  },
  removeRecipient(id: Snowflake, uid: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${id}/recipients/${uid}`);
  },
  /** Dismisses Nexa's note about who added you to this group, on every device. */
  dismissAddNotice(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${id}/add-notice`);
  },
  /** Closes a DM for you; a new message brings it back. */
  /** The side conversations of a DM or group. */
  subchats(id: Snowflake): Promise<Channel[]> {
    return api.request<Channel[]>("GET", `/channels/${id}/subchats`);
  },
  createSubchat(id: Snowflake, name: string, lock?: Record<string, unknown>): Promise<Channel> {
    return api.request<Channel>("POST", `/channels/${id}/subchats`, { name, ...(lock ?? {}) });
  },
  close(id: Snowflake, silent = false): Promise<void> {
    return api.request<void>("DELETE", `/channels/${id}`, undefined, { query: silent ? { silent: "true" } : {} });
  },
  /** Deletes the conversation for everyone, or deletes a group you own. */
  purge(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${id}`, undefined, { query: { purge: "true" } });
  },
  /** Leaves a group DM. */
  leave(id: Snowflake, silent = false): Promise<void> {
    return channels.close(id, silent);
  },
  groupInvites(id: Snowflake): Promise<Invite[]> {
    return api.request<Invite[]>("GET", `/channels/${id}/invites`);
  },
  createGroupInvite(id: Snowflake, body: { max_age?: number; max_uses?: number } = {}): Promise<Invite> {
    return api.request<Invite>("POST", `/channels/${id}/invites`, body);
  },
  typing(id: Snowflake): Promise<void> {
    return api.request<void>("POST", `/channels/${id}/typing`);
  },
  uploadAttachment(
    channelId: Snowflake,
    data: Blob | Uint8Array,
    onProgress?: Progress,
  ): Promise<{ blob_id: Snowflake; size: number; channel_id: Snowflake }> {
    const opts: { onProgress?: Progress } = {};
    if (onProgress) opts.onProgress = onProgress;
    return api.upload(`/channels/${channelId}/attachments`, data, opts);
  },
  /**
   * The ciphertext, as a Blob the browser may keep out of the page's own
   * memory. With `expected`, a file of any other size is refused as soon as
   * that shows, rather than after all of it has come down: the size comes from
   * the message, and a sender could otherwise pass a huge file off as a small
   * picture that everyone's client loads by itself. `onProgress` hears how
   * many bytes have come in so far, which means reading the body piece by piece.
   */
  async downloadAttachment(blobId: Snowflake, signal?: AbortSignal, expected?: number, onProgress?: (got: number) => void): Promise<Blob> {
    const headers: Record<string, string> = {};
    const token = api.getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const init: RequestInit = { method: "GET", headers };
    if (signal) init.signal = signal;
    const res = await api.fetchImpl(attachmentUrl(blobId), init);
    if (!res.ok) throw await errorFromResponse(res);
    if (expected === undefined || !res.body) return res.blob();
    const wrongSize = (): ApiError => new ApiError(0, "size_mismatch", t("channels.thatFileIsNotTheSize"));
    const declared = res.headers.get("Content-Length");
    if (declared !== null && !res.headers.has("Content-Encoding")) {
      if (Number(declared) !== expected) {
        void res.body.cancel().catch(() => undefined);
        throw wrongSize();
      }
      // the browser holds a response to its stated length, so its own fast read is safe
      if (!onProgress) return res.blob();
    }
    const reader = res.body.getReader();
    let whole = new Blob([]);
    let batch: BlobPart[] = [];
    let batchLen = 0;
    let got = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      got += value.length;
      if (got > expected) {
        void reader.cancel().catch(() => undefined);
        throw wrongSize();
      }
      onProgress?.(got);
      batch.push(value as Uint8Array<ArrayBuffer>);
      batchLen += value.length;
      if (batchLen >= FOLD_BYTES) {
        whole = new Blob([whole, ...batch]);
        batch = [];
        batchLen = 0;
      }
    }
    if (got !== expected) throw wrongSize();
    return new Blob([whole, ...batch]);
  },
  pins(id: Snowflake): Promise<Message[]> {
    return api.request<Message[]>("GET", `/channels/${id}/pins`);
  },
  pin(id: Snowflake, mid: Snowflake): Promise<void> {
    return api.request<void>("PUT", `/channels/${id}/pins/${mid}`);
  },
  unpin(id: Snowflake, mid: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/channels/${id}/pins/${mid}`);
  },
};
