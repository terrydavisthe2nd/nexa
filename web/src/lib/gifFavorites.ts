import { MAX_FAVORITE_GIFS, useSettings } from "@/stores/settings";
import type { AttachmentMeta } from "@/crypto/message";
import { attachmentObjectURL } from "./mediaCache";
import { mediaKey } from "./mediaLinks";

export { MAX_FAVORITE_GIFS };

export interface FavoriteGif {
  url: string;
  w?: number;
  h?: number;
  /**
   * An uploaded file instead of a link. `url` is then `attach:<blob_id>` and
   * carries nothing by itself; everything needed to fetch and decrypt sits
   * here. mediaKey() passes such keys through untouched, so the toggle,
   * lookup and removal below work for both shapes with no special cases.
   */
  att?: AttachmentMeta;
}

// Compared by what a link points at: each signing gives a GIF a new address
// (lib/mediaLinks), and comparing addresses said a favourite was not one.
export function isFavoriteGif(url: string): boolean {
  const key = mediaKey(url);
  return useSettings.getState().settings.favoriteGifs.some((f) => mediaKey(f.url) === key);
}

/** Makes a GIF a favourite or stops it being one. Returns whether it is one now. */
export function toggleFavoriteGif(gif: FavoriteGif): boolean {
  const { settings, patch } = useSettings.getState();
  const favs = settings.favoriteGifs;
  const key = mediaKey(gif.url);
  if (favs.some((f) => mediaKey(f.url) === key)) {
    patch({ favoriteGifs: favs.filter((f) => mediaKey(f.url) !== key) });
    return false;
  }
  const saved: FavoriteGif = { url: gif.url, ...(gif.w ? { w: gif.w } : {}), ...(gif.h ? { h: gif.h } : {}), ...(gif.att ? { att: gif.att } : {}) };
  patch({ favoriteGifs: [saved, ...favs].slice(0, MAX_FAVORITE_GIFS) });
  return true;
}

export function removeFavoriteGifs(urls: ReadonlySet<string>): void {
  const { settings, patch } = useSettings.getState();
  patch({ favoriteGifs: settings.favoriteGifs.filter((f) => !urls.has(f.url)) });
}

/**
 * An uploaded favourite as a file ready to preview or send: fetched and
 * decrypted through the same cache the chat itself reads. Link favourites
 * never come through here; they are already an address.
 */
export async function favoriteFile(fav: FavoriteGif): Promise<{ blob: Blob; name: string; type: string }> {
  if (!fav.att) throw new Error("not an uploaded favourite");
  const url = await attachmentObjectURL(fav.att);
  const res = await fetch(url);
  if (!res.ok) throw new Error("favourite is gone");
  const blob = await res.blob();
  return { blob, name: fav.att.name || "animation.gif", type: fav.att.type || blob.type || "image/gif" };
}
