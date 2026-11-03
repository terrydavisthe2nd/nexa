import { useSettings } from "@/stores/settings";
import { api } from "./client";
import type { PurgeCounts } from "./moderation";
import type { Badge, KdfParamsJson, MeUser, PublicUser, SettingsBlob, Snowflake } from "./types";
import type { TypingStyle } from "@/lib/typingStyle";
import type { NameStyle } from "@/lib/nameStyle";
import type { FoundBook, FoundMusic, FoundScreen, MusicWidgetType } from "@/lib/widgets";

export type MePatch = Partial<{
  username: string;
  display_name: string;
  about_me: string | null;
  pronouns: string | null;
  accent_color: number | null;
  accent_color_2: number | null;
  status_text: string | null;
  status_emoji: string | null;
  status_expires_at: number | null;
  presence_pref: MeUser["presence_pref"];
  dm_privacy: MeUser["dm_privacy"];
  group_link_only: boolean;
  /** null for the plain "is typing…" (lib/typingStyle). */
  typing_style: TypingStyle | null;
  /** null for the plain name (lib/nameStyle). */
  name_style: NameStyle | null;
}>;

type Progress = (loaded: number, total: number) => void;

function putImage<T>(path: string, file: Blob, onProgress?: Progress): Promise<T> {
  const opts: { method: string; headers: Record<string, string>; onProgress?: Progress } = {
    method: "PUT",
    headers: { "Content-Type": file.type || "application/octet-stream" },
  };
  if (onProgress) opts.onProgress = onProgress;
  return api.upload<T>(path, file, opts);
}

/** The part framed in the picture's own pixels, as ?x=&y=&w=&h=; empty keeps the middle. */
function cropQuery(crop?: { x: number; y: number; w: number; h: number } | null): string {
  if (!crop) return "";
  return `?x=${Math.round(crop.x)}&y=${Math.round(crop.y)}&w=${Math.round(crop.w)}&h=${Math.round(crop.h)}`;
}

export const users = {
  me(): Promise<MeUser> {
    return api.request<MeUser>("GET", "/users/@me");
  },
  patchMe(patch: MePatch): Promise<MeUser> {
    return api.request<MeUser>("PATCH", "/users/@me", patch);
  },
  deleteMe(auth_key: string): Promise<void> {
    return api.request<void>("POST", "/users/@me/delete", { auth_key });
  },
  /** What deleting the account would take with it. */
  deletePreview(): Promise<PurgeCounts> {
    return api.request<PurgeCounts>("GET", "/users/@me/delete-preview");
  },
  get(id: Snowflake): Promise<PublicUser> {
    return api.request<PublicUser>("GET", `/users/${id}`);
  },
  /**
   * The whole profile: with the mutual friends and servers, and its widgets
   * (read with readWidgets). Opened in a server, the widgets are the ones
   * chosen for it, where there are (server_widgets says so).
   */
  profile(id: Snowflake, serverId?: Snowflake | null): Promise<{ user: PublicUser; mutual_friends: Snowflake[]; mutual_servers: Snowflake[]; widgets?: unknown; server_widgets?: boolean; badges?: Badge[] }> {
    return api.request("GET", `/users/${id}/profile`, undefined, serverId ? { query: { server_id: serverId } } : undefined);
  },
  /** Replaces the account's widgets (lib/widgets), as the server cleaned them. */
  setWidgets(widgets: unknown[]): Promise<{ widgets: unknown }> {
    return api.request("PUT", "/users/@me/widgets", { widgets });
  },
  /** A picture for a typing style, sent whole with the part framed; the server cuts it square and small (moving ones keep moving). */
  uploadTypingImage(file: Blob, crop: { x: number; y: number; w: number; h: number } | null): Promise<{ id: Snowflake; animated: boolean }> {
    const q = new URLSearchParams(crop ? { x: String(Math.round(crop.x)), y: String(Math.round(crop.y)), w: String(Math.round(crop.w)), h: String(Math.round(crop.h)) } : {});
    return api.upload(`/users/@me/typing/image${q.size ? `?${q.toString()}` : ""}`, file, { method: "POST", headers: { "Content-Type": file.type || "application/octet-stream" } });
  },
  /** A picture for a widget, sent whole with the part framed; the server cuts it in this shape. */
  uploadWidgetImage(file: Blob, crop: { x: number; y: number; w: number; h: number } | null, shape: "wide" | "square" | "cover"): Promise<{ id: Snowflake; shape: string }> {
    const q = new URLSearchParams({ shape, ...(crop ? { x: String(crop.x), y: String(crop.y), w: String(crop.w), h: String(crop.h) } : {}) });
    return api.upload(`/users/@me/widgets/image?${q.toString()}`, file, { method: "POST", headers: { "Content-Type": file.type || "application/octet-stream" } });
  },
  /** A music link read into a music widget, each piece matched to Deezer; `missed` names what was not found there. */
  musicFromLink(kind: MusicWidgetType, url: string): Promise<{ kind: string; title: string; items: FoundMusic[]; missed: string[] }> {
    return api.request("POST", "/music/from-link", { kind, url });
  },
  /** Shows and films on TMDB by name, found by this server; `ready` is false where the search is off. */
  searchScreen(q: string): Promise<{ titles: FoundScreen[]; ready: boolean }> {
    return api.request("GET", `/screen/search?q=${encodeURIComponent(q)}`);
  },
  /** Books on Open Library by title or author, found by this server; `ready` is false where book search is off. */
  searchBooks(q: string): Promise<{ books: FoundBook[]; ready: boolean }> {
    return api.request("GET", `/books/search?q=${encodeURIComponent(q)}`);
  },
  /** Games on IGDB by name, found by this server; `ready` is false where game search is not set up. */
  searchGames(q: string): Promise<{ games: { id: string; name: string; cover?: string; year?: number }[]; ready: boolean }> {
    return api.request("GET", `/games/search?q=${encodeURIComponent(q)}`);
  },
  /**
   * Artists, albums or songs on Deezer, found by this server from a name, a
   * song or an album, best first; `ready` is false where music search is off.
   */
  searchMusic(kind: MusicWidgetType, q: string, signal?: AbortSignal): Promise<{ results: FoundMusic[]; ready: boolean }> {
    return api.request("GET", `/music/search?kind=${kind}&q=${encodeURIComponent(q)}`, undefined, signal ? { signal } : undefined);
  },
  /**
   * The profile a profile link shows (lib/profileLinks), which anyone may ask
   * for, signed in or not: what every account sees of them, without their
   * status or encryption key. Never put it in the users store, where it would
   * stand in for the full record.
   */
  shared(username: string): Promise<PublicUser & { widgets?: unknown; badges?: Badge[] }> {
    return api.request("GET", `/profiles/${encodeURIComponent(username)}`);
  },
  /** Every badge this account holds, in its order, the hidden ones marked. */
  myBadges(): Promise<{ badges: Badge[] }> {
    return api.request("GET", "/users/@me/badges");
  },
  /** Keeps the order of this account's badges and which it hides (never its staff rank). */
  setBadges(order: string[], hidden: string[]): Promise<{ badges: Badge[] }> {
    return api.request("PUT", "/users/@me/badges", { order, hidden });
  },
  search(q: string): Promise<PublicUser[]> {
    return api.request<PublicUser[]>("GET", "/users/search", undefined, { query: { q } });
  },
  async getSettings(): Promise<SettingsBlob | null> {
    const res = await api.request<SettingsBlob | undefined>("GET", "/users/@me/settings");
    return res && typeof res.blob === "string" ? res : null;
  },
  putSettings(blob: string, version: number, opts?: { keepalive?: boolean }): Promise<{ version: number }> {
    return api.request("PUT", "/users/@me/settings", { blob, version }, opts);
  },
  myKeys(): Promise<{ public_key: string; enc_private_key: string; kdf_salt: string; kdf_params: KdfParamsJson }> {
    return api.request("GET", "/users/@me/keys");
  },
  publicKey(id: Snowflake): Promise<{ user_id: Snowflake; public_key: string }> {
    return api.request("GET", `/users/${id}/public-key`);
  },
  uploadAvatar(file: Blob, crop?: { x: number; y: number; w: number; h: number } | null, onProgress?: Progress): Promise<{ avatar_id: Snowflake; user: MeUser }> {
    return putImage(`/users/@me/avatar${cropQuery(crop)}`, file, onProgress);
  },
  deleteAvatar(): Promise<void> {
    return api.request<void>("DELETE", "/users/@me/avatar");
  },
  uploadBanner(file: Blob, crop?: { x: number; y: number; w: number; h: number } | null, onProgress?: Progress): Promise<{ banner_id: Snowflake; user: MeUser }> {
    return putImage(`/users/@me/banner${cropQuery(crop)}`, file, onProgress);
  },
  deleteBanner(): Promise<void> {
    return api.request<void>("DELETE", "/users/@me/banner");
  },
};

/**
 * The size to ask the server for so a picture drawn `css` pixels across stays
 * sharp on this screen: doubled or tripled for dense screens, then rounded up
 * to a power of two, which is what the server keeps copies at. Without it a
 * 32-pixel avatar would come as uploaded, and a 4096-pixel one costs the
 * browser 64 MB to show.
 */
export function mediaSize(css: number): number {
  const density = typeof window !== "undefined" ? Math.min(3, Math.max(1, window.devicePixelRatio || 1)) : 1;
  const want = Math.ceil(css * density);
  let size = 16;
  while (size < want && size < 1024) size *= 2;
  return size;
}

function mediaUrl(kind: string, id: Snowflake | null, css?: number, version = ""): string | null {
  if (!id) return null;
  const url = `${api.baseUrl}/media/${kind}/${id}`;
  const key = ({ avatar: "animateAvatars", icon: "animateIcons", banner: "animateBanners", emoji: "animateEmoji", sticker: "animateStickers" } as const)[kind as "avatar"];
  const still = key && useSettings.getState().settings[key] === false;
  const params = new URLSearchParams();
  if (css) params.set("size", String(mediaSize(css)));
  if (still) params.set("still", "1");
  if (css && version) params.set("v", "3");
  return params.size ? `${url}?${params}` : url;
}

/**
 * Sized avatars, banners and icons used to be a still picture whenever the
 * upload was a GIF over 512 pixels, which froze animated banners; the server
 * now shrinks every frame. Sized copies are served as immutable, so browsers
 * that kept the still one would keep showing it at the old address: this
 * gives those three kinds a new one. Version 3 also drops copies made before
 * the server applied photo orientation when resizing. The server ignores it.
 */
const MOVING = "&v=3";

/** `css` is how many pixels across it is drawn; leave it out for the original. */
export function avatarUrl(id: Snowflake | null, css?: number): string | null {
  return mediaUrl("avatar", id, css, MOVING);
}
export function bannerUrl(id: Snowflake | null, css?: number): string | null {
  return mediaUrl("banner", id, css, MOVING);
}
export function iconUrl(id: Snowflake | null, css?: number): string | null {
  return mediaUrl("icon", id, css, MOVING);
}
export function emojiUrl(id: Snowflake, _animated: boolean, css?: number): string {
  return mediaUrl("emoji", id, css) ?? "";
}
export function stickerUrl(id: Snowflake, css?: number): string {
  return mediaUrl("sticker", id, css) ?? "";
}
