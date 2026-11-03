/**
 * A game's page (internal/games/details.go): what IGDB says about a game, its
 * screenshots, artwork and trailers, similar games and where it is sold,
 * asked of this server and kept here for the session. Opened from a game on
 * a profile's widgets, or from an activity whose game IGDB knows by name.
 */
import { api } from "@/api/client";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";
import { gameCoverUrl } from "@/lib/widgets";

export interface GameRef {
  id: string;
  name: string;
  cover?: string;
  year?: number;
}

export interface GamePicture {
  id: string;
  w?: number;
  h?: number;
}

export interface GameVideo {
  id: string;
  name?: string;
  /** Its 16:9 picture for the strip, through this server's media proxy. */
  thumb?: string;
  /** Its larger picture, a 4:3 frame with the 16:9 picture across its middle. */
  poster?: string;
}

export type GameLinkKind = "official" | "steam" | "epic" | "gog" | "itch" | "playstation" | "xbox" | "nintendo" | "apple" | "googleplay" | "gamejolt" | "wikipedia";

export interface GameDetails extends GameRef {
  summary?: string;
  storyline?: string;
  /** Unix milliseconds. */
  released?: number;
  genres: string[];
  themes: string[];
  modes: string[];
  platforms: string[];
  developers: string[];
  publishers: string[];
  /** IGDB's score out of 100, and how many it is from. */
  rating?: number;
  rating_count?: number;
  age_ratings: { board: string; rating: string }[];
  screenshots: GamePicture[];
  artworks: GamePicture[];
  videos: GameVideo[];
  similar: GameRef[];
  links: { kind: GameLinkKind; url: string }[];
}

/** What each kind of link is called: the stores' own names, which are not translated. */
export const STORE_NAMES: Record<Exclude<GameLinkKind, "official">, string> = {
  steam: "Steam",
  epic: "Epic Games Store",
  gog: "GOG",
  itch: "itch.io",
  playstation: "PlayStation Store",
  xbox: "Xbox",
  nintendo: "Nintendo eShop",
  apple: "App Store",
  googleplay: "Google Play",
  gamejolt: "Game Jolt",
  wikipedia: "Wikipedia",
};

/** A screenshot or artwork: "t" for the strip, "b" for the stage and the viewer. */
export function gamePictureUrl(id: string, size: "t" | "b"): string {
  return `${api.baseUrl}/media/game/${id}?size=${size}`;
}

export { gameCoverUrl };

const pages = new Map<string, Promise<GameDetails>>();

/** A game's page, asked once a session (the server keeps it a day). */
export function loadGame(id: string): Promise<GameDetails> {
  let p = pages.get(id);
  if (!p) {
    p = api.request<GameDetails>("GET", `/games/${encodeURIComponent(id)}`);
    p.catch(() => pages.delete(id));
    pages.set(id, p);
  }
  return p;
}

const matches = new Map<string, Promise<string | null>>();

/** IGDB's id for the game an activity names, if one has exactly that name; asked once a session per name. */
export function matchGame(name: string): Promise<string | null> {
  const key = name.trim().toLowerCase();
  let p = matches.get(key);
  if (!p) {
    p = api.request<{ id: string | null }>("GET", `/games/match?name=${encodeURIComponent(name.trim())}`).then((r) => r?.id ?? null);
    p.catch(() => matches.delete(key));
    matches.set(key, p);
  }
  return p;
}

/** Whether game pages can be opened here: they are asked of the server as someone signed in. */
export function canOpenGames(): boolean {
  return useAuth.getState().status === "ready";
}

/** Opens a game's page over whatever is open. */
export function openGame(id: string): void {
  useUI.getState().openModal("game", { gameId: id });
}
