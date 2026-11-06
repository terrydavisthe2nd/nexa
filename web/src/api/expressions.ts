import { api, ApiError, errorFromResponse } from "./client";
import type { DiscoverPacksPage, Emoji, GifPage, Snowflake, Sticker, StickerInfo, StickerPack } from "./types";
import { t } from "@/i18n";

type Progress = (loaded: number, total: number) => void;

function upload<T>(path: string, file: Blob, onProgress?: Progress): Promise<T> {
  const opts: { method: string; headers: Record<string, string>; onProgress?: Progress } = { method: "POST", headers: { "Content-Type": file.type || "application/octet-stream" } };
  if (onProgress) opts.onProgress = onProgress;
  return api.upload<T>(path, file, opts);
}

export const expressions = {
  emojis(serverId: Snowflake): Promise<Emoji[]> {
    return api.request<Emoji[]>("GET", `/servers/${serverId}/emojis`);
  },
  uploadEmoji(serverId: Snowflake, name: string, file: Blob, onProgress?: Progress): Promise<Emoji> {
    return upload<Emoji>(`/servers/${serverId}/emojis?name=${encodeURIComponent(name)}`, file, onProgress);
  },
  renameEmoji(serverId: Snowflake, emojiId: Snowflake, name: string): Promise<Emoji> {
    return api.request<Emoji>("PATCH", `/servers/${serverId}/emojis/${emojiId}`, { name });
  },
  deleteEmoji(serverId: Snowflake, emojiId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/servers/${serverId}/emojis/${emojiId}`);
  },
  packs(): Promise<StickerPack[]> {
    return api.request<StickerPack[]>("GET", "/stickers/packs");
  },
  publicPacks(q = "", limit = 50): Promise<StickerPack[]> {
    return api.request<StickerPack[]>("GET", "/stickers/packs/public", undefined, { query: { q, limit } });
  },
  /** A page of public packs of one kind for Discovery, each with a preview of its stickers. */
  discoverPacks(query: { kind: "sticker" | "emoji"; q?: string; sort?: "popular" | "newest" | "updated"; offset?: number; limit?: number }): Promise<DiscoverPacksPage> {
    return api.request<DiscoverPacksPage>("GET", "/stickers/discover", undefined, { query });
  },
  pack(id: Snowflake): Promise<StickerPack> {
    return api.request<StickerPack>("GET", `/stickers/packs/${id}`);
  },
  createPack(body: { name: string; description?: string; server_id?: Snowflake; kind?: "sticker" | "emoji" }): Promise<StickerPack> {
    return api.request<StickerPack>("POST", "/stickers/packs", body);
  },
  patchPack(id: Snowflake, body: { name?: string; description?: string; public?: boolean; cover_sticker_id?: Snowflake | null }): Promise<StickerPack> {
    return api.request<StickerPack>("PATCH", `/stickers/packs/${id}`, body);
  },
  deletePack(id: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/stickers/packs/${id}`);
  },
  uploadSticker(packId: Snowflake, name: string, tags: string, file: Blob, onProgress?: Progress): Promise<Sticker> {
    return upload<Sticker>(`/stickers/packs/${packId}/stickers?name=${encodeURIComponent(name)}&tags=${encodeURIComponent(tags)}`, file, onProgress);
  },
  patchSticker(packId: Snowflake, stickerId: Snowflake, body: { name?: string; tags?: string }): Promise<Sticker> {
    return api.request<Sticker>("PATCH", `/stickers/packs/${packId}/stickers/${stickerId}`, body);
  },
  deleteSticker(packId: Snowflake, stickerId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/stickers/packs/${packId}/stickers/${stickerId}`);
  },
  setStickerPositions(packId: Snowflake, list: { id: Snowflake; position: number }[]): Promise<StickerPack> {
    return api.request<StickerPack>("PATCH", `/stickers/packs/${packId}/stickers`, list);
  },
  /** The public pack a sticker comes from, or null when its pack is not public (or it is gone). */
  async stickerInfo(stickerId: Snowflake): Promise<StickerInfo | null> {
    try {
      return await api.request<StickerInfo>("GET", `/stickers/${stickerId}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) return null;
      throw err;
    }
  },
  install(packId: Snowflake): Promise<StickerPack> {
    return api.request<StickerPack>("PUT", `/stickers/packs/${packId}/install`);
  },
  /** The account's last-used stickers, newest first, for the picker's recent row. */
  recentStickers(): Promise<Sticker[]> {
    return api.request<Sticker[]>("GET", "/stickers/recent");
  },
  /** Records that a sticker was just sent. Never rejects the send if it fails. */
  touchRecentSticker(id: Snowflake): Promise<void> {
    return api.request<void>("POST", "/stickers/recent", { sticker_id: id });
  },
  /** Empties the recent row, on every device of the account. */
  clearRecentStickers(): Promise<void> {
    return api.request<void>("DELETE", "/stickers/recent");
  },
  /**
   * Imports a Telegram set with progress. The server answers newline-delimited
   * JSON (a line per sticker, the pack last), so this reads the body as it
   * arrives rather than waiting for the whole import like api.request does.
   */
  importTelegramStream(
    url: string,
    onProgress?: (done: number, total: number, phase: string) => void,
    signal?: AbortSignal,
  ): Promise<{ pack: StickerPack; imported: number; skipped: number; failed: number; total: number }> {
    const headers: Record<string, string> = { Accept: "application/x-ndjson", "Content-Type": "application/json" };
    const token = api.getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
    // Imports run for tens of seconds; the usual request deadline does not apply.
    const ctrl = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      ctrl.abort();
    }, 150_000);
    if (signal) {
      if (signal.aborted) ctrl.abort();
      else signal.addEventListener("abort", () => ctrl.abort(), { once: true });
    }
    const badResponse = (): ApiError => new ApiError(0, "bad_response", t("attachments.theServerSAnswerCouldNot"));
    return (async () => {
      let res: Response;
      try {
        res = await api.fetchImpl(api.url("/stickers/packs/import/telegram"), {
          method: "POST",
          headers,
          body: JSON.stringify({ url }),
          signal: ctrl.signal,
        });
      } catch (err) {
        if (timedOut) throw new ApiError(0, "timeout", t("offline.couldNotReachTheServer"));
        if (err instanceof ApiError) throw err;
        if (err instanceof DOMException && err.name === "AbortError") throw new ApiError(0, "aborted", t("errors.thatWasCancelledBeforeItFinished"));
        throw new ApiError(0, "network", t("offline.couldNotReachTheServer"));
      } finally {
        clearTimeout(timer);
      }
      if (!res.ok) {
        const err = await errorFromResponse(res);
        if (res.status === 401 && token && err.code === "unauthorized") api.onUnauthorized?.();
        throw err;
      }
      if (!res.body) throw badResponse();
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = "";
      let result: { pack: StickerPack; imported: number; skipped: number; failed: number; total: number } | undefined;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let idx: number;
        while ((idx = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, idx).trim();
          buf = buf.slice(idx + 1);
          if (!line) continue;
          let msg: Record<string, unknown>;
          try {
            msg = JSON.parse(line) as Record<string, unknown>;
          } catch {
            throw badResponse();
          }
          if (msg["done"] === true) {
            result = {
              pack: msg["pack"] as StickerPack,
              imported: Number(msg["imported"] ?? 0),
              skipped: Number(msg["skipped"] ?? 0),
              failed: Number(msg["failed"] ?? 0),
              total: Number(msg["total"] ?? 0),
            };
          } else if (msg["error"] && typeof msg["error"] === "object") {
            const e = msg["error"] as { code?: unknown; message?: unknown };
            throw new ApiError(res.status, typeof e.code === "string" ? e.code : "import_failed", typeof e.message === "string" ? e.message : t("settings.stickers.importFailed"));
          } else if (typeof msg["done"] === "number" && typeof msg["total"] === "number") {
            onProgress?.(msg["done"] as number, msg["total"] as number, typeof msg["phase"] === "string" ? (msg["phase"] as string) : "");
          }
        }
      }
      if (!result) throw badResponse();
      return result;
    })();
  },
  uninstall(packId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/stickers/packs/${packId}/install`);
  },
  gifStatus(): Promise<{ enabled: boolean }> {
    return api.request("GET", "/gif/status");
  },
  gifSearch(q: string, pos?: string): Promise<GifPage> {
    return api.request<GifPage>("GET", "/gif/search", undefined, { query: { q, pos } });
  },
  gifTrending(pos?: string): Promise<GifPage> {
    return api.request<GifPage>("GET", "/gif/trending", undefined, { query: { pos } });
  },
};
