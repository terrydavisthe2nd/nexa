import { MAX_FAVORITE_STICKERS, useSettings } from "@/stores/settings";

/** Stars a sticker, or takes the star away; the newest comes first. Synced like the rest of the settings. */
export function toggleFavoriteSticker(id: string): void {
  const { settings, patch } = useSettings.getState();
  const list = settings.favoriteStickers;
  patch({ favoriteStickers: list.includes(id) ? list.filter((x) => x !== id) : [id, ...list].slice(0, MAX_FAVORITE_STICKERS) });
}
