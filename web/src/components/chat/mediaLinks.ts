import type { UnfurlResult } from "@/api/types";

const LINK = /^https?:\/\/\S+$/i;
const PROXY_PATH = /^\/media\/proxy\/\S+$/;

/**
 * Whether a message's text is only the signed path a picker GIF is sent as.
 * It means nothing to a reader, so with its preview left off (Suppress Embeds,
 * Image Previews) a note takes its place.
 */
export function isBareGifPath(text: string): boolean {
  return PROXY_PATH.test(text.trim());
}

export function isMediaEmbed(e: UnfurlResult | null | undefined): boolean {
  return !!e && (e.type === "image" || e.type === "gif");
}

/**
 * Whether a message's text is nothing but links to the pictures and GIFs
 * shown under it. Audio and video keep their original links. For pictures,
 * media stands alone. A word counts as such a link when it is the address a
 * preview was made for, which is how a GIF from the picker arrives (through
 * this server's proxy, as a path), or any web link at all, since a preview
 * records where a pasted link ended up after redirects; the counts then have
 * to line up.
 */
export function isOnlyMediaLinks(text: string, embeds: readonly UnfurlResult[]): boolean {
  const words = text.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0 || embeds.length === 0 || !embeds.every(isMediaEmbed)) return false;
  const own = new Set(embeds.flatMap((e) => [e.url, e.image]).filter((u): u is string => !!u));
  if (!words.every((w) => own.has(w) || LINK.test(w))) return false;
  return embeds.length >= words.length;
}
