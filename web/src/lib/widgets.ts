/**
 * Profile widgets (internal/api/widgets.go): sections of a profile that show
 * what someone is into. Games they love, are playing, want to play and have
 * played, found on IGDB or added by name; the artists, albums and songs they
 * love, found on Deezer or added by name; and cards they make themselves.
 * The server checks and keeps them; this is their shape on this side, read
 * defensively, and the addresses of their pictures.
 */
import { api } from "@/api/client";
import { mediaSize } from "@/api/users";

export interface WidgetGame {
  /** IGDB's id for the game, when it came from the games search. */
  id?: string;
  name: string;
  /** IGDB's image id for its cover. */
  cover?: string;
  /** A picture of the person's own, for a game added by name. */
  image?: string;
  /** Only for Playing Now: how they play it. */
  tags?: string[];
}

/** An artist, an album or a song on a music widget. */
export interface WidgetMusic {
  /** Deezer's id, when it came from the music search. */
  id?: string;
  name: string;
  /** An album's or a song's artist. */
  by?: string;
  /** Deezer's id for its picture. */
  art?: string;
  /** A picture of the person's own, for one added by name. */
  image?: string;
  /** A song's length in seconds. */
  duration?: number;
}

/** An artist, album or song the music search found (internal/music), with what tells it apart. */
export interface FoundMusic {
  id: string;
  name: string;
  by?: string;
  art?: string;
  duration?: number;
  /** The album a song is on. */
  album?: string;
  /** What an album is when it is not one. */
  record?: "single" | "ep" | "compile";
  /** How many follow an artist on Deezer. */
  fans?: number;
  /** The song or album an artist was found through. */
  via?: { kind: "song" | "album"; name: string };
}

export interface WidgetStat {
  label: string;
  value: string;
  /** From 0 to 100: the stat is drawn as a progress bar (its value is then the percentage in words). */
  bar?: number;
}

export interface WidgetLink {
  url: string;
  label?: string;
}

export type GameWidgetType = "favorite" | "playing" | "wishlist" | "played";
export type MusicWidgetType = "artists" | "albums" | "songs";
export type WidgetType = GameWidgetType | MusicWidgetType | "custom" | "countdown" | "localtime";

export interface GameWidget {
  id: string;
  type: GameWidgetType;
  games: WidgetGame[];
  /** Only for Favorite Game: why. */
  note?: string;
}

export interface MusicWidget {
  id: string;
  type: MusicWidgetType;
  music: WidgetMusic[];
}

export interface CardWidget {
  id: string;
  type: "custom";
  title: string;
  image?: string;
  shape?: "wide" | "square";
  text?: string;
  stats?: WidgetStat[];
  /**
   * Up to three buttons. Always sent, even empty: a card without it is taken
   * for one from an app before cards had several links, and keeps what it had.
   * The server also keeps the first as `link`, for those apps.
   */
  links?: WidgetLink[];
  /** 0xRRGGBB tinting the card, and a second for a gradient. */
  color?: number;
  color2?: number;
}

/**
 * A countdown to a whole day (`date`, YYYY-MM-DD, counted in each viewer's
 * own calendar) or to a moment (`at`, Unix milliseconds).
 */
export interface CountdownWidget {
  id: string;
  type: "countdown";
  title: string;
  emoji?: string;
  date?: string;
  at?: number;
}

/** The time where someone is: an IANA time zone, shown only because they added it. */
export interface LocalTimeWidget {
  id: string;
  type: "localtime";
  zone: string;
}

export type Widget = GameWidget | MusicWidget | CardWidget | CountdownWidget | LocalTimeWidget;

export const GAME_TYPES: GameWidgetType[] = ["favorite", "playing", "wishlist", "played"];
export const MUSIC_TYPES: MusicWidgetType[] = ["artists", "albums", "songs"];
export const WIDGET_TYPES: WidgetType[] = [...GAME_TYPES, ...MUSIC_TYPES, "custom", "countdown", "localtime"];
export const MAX_WIDGETS = 12;
export const MAX_CARD_LINKS = 3;
export const MAX_CARDS = 4;
/** The most games each kind of games widget holds. */
export const MOST_GAMES: Record<GameWidgetType, number> = { favorite: 1, playing: 5, wishlist: 20, played: 20 };
/** The most each kind of music widget holds. */
export const MOST_MUSIC: Record<MusicWidgetType, number> = { artists: 12, albums: 12, songs: 10 };
export const LIMITS = { note: 200, tags: 3, tag: 20, game: 100, music: 100, title: 40, text: 1000, stats: 6, label: 24, value: 40, linkLabel: 32 };

export function isGameWidget(w: Widget): w is GameWidget {
  return (GAME_TYPES as string[]).includes(w.type);
}

export function isMusicWidget(w: Widget): w is MusicWidget {
  return (MUSIC_TYPES as string[]).includes(w.type);
}

/** Whether this browser knows a time zone by that name. */
export function validZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat("en", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/** A new, empty widget of a kind, for its editor to fill. */
export function blankWidget(type: WidgetType, id: string): Widget {
  if (type === "custom") return { id, type, title: "", links: [] };
  if (type === "countdown") return { id, type, title: "", date: "" };
  if (type === "localtime") return { id, type, zone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC" };
  if (isMusicType(type)) return { id, type, music: [] };
  return { id, type, games: [] };
}

export function isMusicType(type: WidgetType): type is MusicWidgetType {
  return (MUSIC_TYPES as string[]).includes(type);
}

const str = (v: unknown, max: number): string => (typeof v === "string" ? v.slice(0, max) : "");

function readGame(x: unknown): WidgetGame | null {
  if (!x || typeof x !== "object") return null;
  const g = x as Record<string, unknown>;
  const name = str(g["name"], LIMITS.game).trim();
  if (!name) return null;
  const out: WidgetGame = { name };
  if (typeof g["id"] === "string" && /^\d{1,10}$/.test(g["id"])) out.id = g["id"];
  if (typeof g["cover"] === "string" && /^[a-z0-9]{6,32}$/.test(g["cover"])) out.cover = g["cover"];
  if (typeof g["image"] === "string" && /^\d{1,20}$/.test(g["image"])) out.image = g["image"];
  if (Array.isArray(g["tags"])) out.tags = g["tags"].filter((t): t is string => typeof t === "string" && t.trim().length > 0).map((t) => t.slice(0, LIMITS.tag)).slice(0, LIMITS.tags);
  return out;
}

function readMusic(x: unknown, type: MusicWidgetType): WidgetMusic | null {
  if (!x || typeof x !== "object") return null;
  const m = x as Record<string, unknown>;
  const name = str(m["name"], LIMITS.music).trim();
  if (!name) return null;
  const out: WidgetMusic = { name };
  if (typeof m["id"] === "string" && /^\d{1,20}$/.test(m["id"])) out.id = m["id"];
  const by = type === "artists" ? "" : str(m["by"], LIMITS.music).trim();
  if (by) out.by = by;
  if (typeof m["art"] === "string" && /^[0-9a-f]{32}$/.test(m["art"])) out.art = m["art"];
  if (typeof m["image"] === "string" && /^\d{1,20}$/.test(m["image"])) out.image = m["image"];
  const secs = m["duration"];
  if (type === "songs" && typeof secs === "number" && secs > 0 && secs <= 86400) out.duration = Math.round(secs);
  return out;
}

/** Widgets as the server sent them, read defensively: anything malformed is left out. */
export function readWidgets(x: unknown): Widget[] {
  if (!Array.isArray(x)) return [];
  const out: Widget[] = [];
  for (const raw of x) {
    if (!raw || typeof raw !== "object") continue;
    const w = raw as Record<string, unknown>;
    const id = typeof w["id"] === "string" ? w["id"] : `w${out.length}`;
    const type = w["type"];
    if (type === "countdown") {
      const title = str(w["title"], LIMITS.title).trim();
      const date = typeof w["date"] === "string" && /^\d{4}-\d{2}-\d{2}$/.test(w["date"]) ? w["date"] : undefined;
      const at = typeof w["at"] === "number" && Number.isFinite(w["at"]) && w["at"] > 0 ? w["at"] : undefined;
      if (!title || (!date && !at)) continue;
      const emoji = str(w["emoji"], 32).trim();
      out.push({ id, type, title, ...(emoji ? { emoji } : {}), ...(date ? { date } : { at: at! }) });
      continue;
    }
    if (type === "localtime") {
      const zone = str(w["zone"], 64).trim();
      if (!zone || !validZone(zone)) continue;
      out.push({ id, type, zone });
      continue;
    }
    if (type === "custom") {
      const title = str(w["title"], LIMITS.title).trim();
      if (!title) continue;
      const card: CardWidget = { id, type, title };
      if (typeof w["image"] === "string" && /^\d{1,20}$/.test(w["image"])) {
        card.image = w["image"];
        card.shape = w["shape"] === "square" ? "square" : "wide";
      }
      const text = str(w["text"], LIMITS.text);
      if (text) card.text = text;
      if (Array.isArray(w["stats"])) {
        card.stats = w["stats"]
          .filter((s): s is Record<string, unknown> => !!s && typeof s === "object")
          .map((s): WidgetStat => {
            const bar = s["bar"];
            const st: WidgetStat = { label: str(s["label"], LIMITS.label), value: str(s["value"], LIMITS.value) };
            if (typeof bar === "number" && bar >= 0 && bar <= 100) st.bar = Math.round(bar);
            return st;
          })
          .filter((s) => s.label && s.value)
          .slice(0, LIMITS.stats);
      }
      const rawLinks = Array.isArray(w["links"]) ? (w["links"] as unknown[]) : w["link"] ? [w["link"]] : [];
      const links = rawLinks
        .map((l): WidgetLink | null => {
          if (!l || typeof l !== "object") return null;
          const o = l as Record<string, unknown>;
          if (typeof o["url"] !== "string" || !/^https?:\/\//i.test(o["url"])) return null;
          return { url: o["url"], ...(typeof o["label"] === "string" && o["label"] ? { label: o["label"].slice(0, LIMITS.linkLabel) } : {}) };
        })
        .filter((l): l is WidgetLink => !!l)
        .slice(0, MAX_CARD_LINKS);
      // always there, even empty: the server tells an app from before by a card sent without it
      card.links = links;
      const colour = (v: unknown) => (typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= 0xffffff ? v : undefined);
      const c1 = colour(w["color"]);
      if (c1 !== undefined) {
        card.color = c1;
        const c2 = colour(w["color2"]);
        if (c2 !== undefined) card.color2 = c2;
      }
      out.push(card);
      continue;
    }
    if (type === "artists" || type === "albums" || type === "songs") {
      const music = Array.isArray(w["music"]) ? w["music"].map((m) => readMusic(m, type)).filter((m): m is WidgetMusic => !!m).slice(0, MOST_MUSIC[type]) : [];
      if (music.length) out.push({ id, type, music });
      continue;
    }
    if (type !== "favorite" && type !== "playing" && type !== "wishlist" && type !== "played") continue;
    const games = Array.isArray(w["games"]) ? w["games"].map(readGame).filter((g): g is WidgetGame => !!g).slice(0, MOST_GAMES[type]) : [];
    if (games.length === 0) continue;
    const game: GameWidget = { id, type, games };
    if (type === "favorite" && typeof w["note"] === "string" && w["note"]) game.note = w["note"].slice(0, LIMITS.note);
    out.push(game);
  }
  return out.slice(0, MAX_WIDGETS);
}

/** A game's cover from IGDB, kept by this server: "s" for small, "l" for large. */
export function gameCoverUrl(cover: string, size: "s" | "l"): string {
  return `${api.baseUrl}/media/game/${cover}?size=${size}`;
}

/** A widget's own picture at a size. */
export function widgetImageUrl(id: string, css?: number): string {
  const base = `${api.baseUrl}/media/widget/${id}`;
  return css ? `${base}?size=${mediaSize(css)}` : base;
}

/** A game's picture: its cover, or the one its owner put up, or none. */
export function gamePicture(g: WidgetGame, css: number): string | null {
  if (g.cover) return gameCoverUrl(g.cover, css > 80 ? "l" : "s");
  if (g.image) return widgetImageUrl(g.image, css);
  return null;
}

/** A picture kept from Deezer: an artist's photo or a cover, "s" for rows and "l" for shelves. */
export function musicArtUrl(kind: "artist" | "cover", art: string, size: "s" | "l"): string {
  return `${api.baseUrl}/media/music/${kind}/${art}?size=${size}`;
}

/** The picture of an artist, album or song drawn `css` pixels across: Deezer's, the one its owner put up, or none. */
export function musicPicture(type: MusicWidgetType, m: WidgetMusic, css: number): string | null {
  if (m.art) return musicArtUrl(type === "artists" ? "artist" : "cover", m.art, mediaSize(css) > 128 ? "l" : "s");
  if (m.image) return widgetImageUrl(m.image, css);
  return null;
}

/** Where an artist, album or song found on Deezer is there, to listen to it. */
export function musicLink(type: MusicWidgetType, m: WidgetMusic): string | null {
  if (!m.id) return null;
  return `https://www.deezer.com/${type === "artists" ? "artist" : type === "albums" ? "album" : "track"}/${m.id}`;
}

/** A song's length the way a sleeve prints it: 3:07, or 1:02:07. */
export function songLength(secs: number): string {
  const s = Math.max(0, Math.round(secs));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/** A short id for a new widget, as the server takes them. */
export function newWidgetId(): string {
  return `w${Math.random().toString(36).slice(2, 10)}`;
}

/** The host a link goes to, for a link with no words of its own. */
export function linkHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
}

/** Widgets as short lines for the staff: what each is called, and its games or a card's words. */
export function widgetsText(widgets: Widget[], title: (w: Widget) => string): { title: string; text: string }[] {
  return widgets.map((w) => {
    if (isGameWidget(w)) return { title: title(w), text: [w.games.map((g) => g.name).join(", "), w.note].filter(Boolean).join(" · ") };
    if (isMusicWidget(w)) return { title: title(w), text: w.music.map((m) => (m.by ? `${m.name} (${m.by})` : m.name)).join(", ") };
    if (w.type === "countdown") return { title: title(w), text: [w.emoji, w.title, w.date ?? (w.at ? new Date(w.at).toISOString() : "")].filter(Boolean).join(" ") };
    if (w.type === "localtime") return { title: title(w), text: w.zone };
    return { title: title(w), text: [w.title, w.text, ...(w.stats ?? []).map((st) => `${st.label}: ${st.value}`), ...(w.links ?? []).map((l) => l.url)].filter(Boolean).join(" · ") };
  });
}
