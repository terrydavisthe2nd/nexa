import { reportItem } from "@/lib/reporting";
import type { Sticker, StickerPack } from "@/api/types";
import { button, defineMenu } from "@/lib/contextMenus/registry";
import { canRemovePack, confirmRemovePack } from "@/lib/packRemoval";
import { t } from "@/i18n";

/** A favourite GIF: send it (unless it no longer loads), or let it go. */
export const favoriteGifMenu = defineMenu<{ dead: boolean; send: () => void; unfavorite: () => void }>("favoriteGif", [
  [
    button("sendFavoriteGif", ({ dead, send }) => !dead && { id: "send", label: t("pickers.gifPicker.sendGif"), onSelect: send }),
    button("unfavoriteGif", ({ unfavorite }) => ({ id: "unfavorite", label: t("pickers.gifPicker.removeFromFavorites"), danger: true, onSelect: unfavorite })),
  ],
]);

/** A GIF in the search results: send it, or star it. */
export const gifResultMenu = defineMenu<{ favorite: boolean; send: () => void; toggleFavorite: () => void }>("gifResult", [
  [
    button("sendGif", ({ send }) => ({ id: "send", label: t("pickers.gifPicker.sendGif"), onSelect: send })),
    button("favoriteGif", ({ favorite, toggleFavorite }) => ({ id: "favorite", label: favorite ? t("pickers.gifPicker.removeFromFavorites") : t("pickers.gifPicker.addToFavorites"), onSelect: toggleFavorite })),
  ],
]);

/** A sticker in the picker: star it, see the pack it came from, or report it. */
export const pickerStickerMenu = defineMenu<{ sticker: Sticker; favorite: boolean; toggleFavorite: () => void; viewPack: () => void }>("pickerSticker", [
  [
    button("favoriteSticker", ({ favorite, toggleFavorite }) => ({ id: "favorite", label: favorite ? t("pickers.gifPicker.removeFromFavorites") : t("pickers.gifPicker.addToFavorites"), onSelect: toggleFavorite })),
    button("viewPackOfSticker", ({ viewPack }) => ({ id: "viewPack", label: t("pickers.stickerPicker.viewPack"), onSelect: viewPack })),
  ],
  [button("reportPickerSticker", ({ sticker }) => reportItem({ kind: "sticker", id: sticker.id }))],
]);

/** A sticker pack's header in the picker: edit your own, or remove it. */
export const stickerPackMenu = defineMenu<{ pack: StickerPack; meId: string | undefined; edit: () => void; view: () => void }>("stickerPack", [
  [
    button("viewStickerPack", ({ view }) => ({ id: "view", label: t("pickers.stickerPicker.viewPack"), onSelect: view })),
    button("reportStickerPack", ({ pack }) => reportItem({ kind: "pack", id: pack.id })),
    button("editStickerPack", ({ pack, meId, edit }) => pack.owner_user_id === meId && { id: "edit", label: t("pickers.stickerPicker.editPack"), onSelect: edit }),
    button("removeStickerPack", ({ pack, meId }) => canRemovePack(pack, meId) && { id: "remove", label: t("pickers.stickerPicker.removePack"), danger: true, onSelect: () => confirmRemovePack(pack) }),
  ],
]);
