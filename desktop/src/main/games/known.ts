/**
 * Big games that a store's files on the computer will not name: the ones with
 * launchers of their own, and the ones people run from wherever they keep them.
 * Everything from Steam, Epic, GOG and the like is found from those stores' own
 * files instead (library.ts), so this list stays short.
 *
 * The list comes from the server (GET /api/v1/games/known): this file's copy,
 * internal/games/known_games.json, written by hand, plus what staff accepted of
 * the games people shared. So it changes with a deploy, and installed apps get
 * it without a release. The copy bundled here is for a first run offline.
 *
 * A file name is compared whole and in lower case, so one a thousand other
 * programs might share ("game.exe", "launcher.exe") must not be here. `arg`
 * must also appear on the command line, for games run by something else (java).
 */
import fs from "node:fs/promises";
import path from "node:path";
import bundled from "../../../../internal/games/known_games.json";

export interface KnownGame {
  name: string;
  exes: string[];
  arg?: string[];
  /** IGDB's id, when staff chose one. */
  game_id?: string;
}

export const BUNDLED_GAMES = bundled as KnownGame[];

const MAX_ENTRIES = 20_000;
const MAX_TEXT = 256;

const short = (v: unknown): v is string => typeof v === "string" && v.length > 0 && v.length <= MAX_TEXT;

/** Keeps what has the right shape: this list came over the network. */
export function cleanKnownGames(input: unknown): KnownGame[] | null {
  const list = (input as { games?: unknown } | null)?.games;
  if (!Array.isArray(list) || list.length > MAX_ENTRIES) return null;
  const out: KnownGame[] = [];
  for (const raw of list) {
    const g = raw as Partial<KnownGame> | null;
    if (!g || !short(g.name) || !Array.isArray(g.exes)) continue;
    const exes = g.exes.filter(short).slice(0, 32).map((x) => x.toLowerCase());
    if (exes.length === 0) continue;
    const arg = Array.isArray(g.arg) ? g.arg.filter(short).slice(0, 16) : [];
    out.push({ name: g.name, exes, ...(arg.length ? { arg } : {}), ...(short(g.game_id) && /^\d{1,10}$/.test(g.game_id) ? { game_id: g.game_id } : {}) });
  }
  return out;
}

export interface KnownSource {
  /** Asks the server, sending what it was last told so an unchanged list costs nothing. */
  fetch(url: string, etag: string | null): Promise<{ status: number; etag: string | null; body: unknown }>;
  url: string;
  cacheFile: string;
  log?(msg: string, extra?: Record<string, unknown>): void;
}

/** The list as last heard from the server, kept on disk for the next start. */
export class KnownList {
  private etag: string | null = null;
  games: KnownGame[] = BUNDLED_GAMES;

  constructor(private readonly src: KnownSource) {}

  /** What the last run kept. */
  async load(): Promise<void> {
    try {
      const kept = JSON.parse(await fs.readFile(this.src.cacheFile, "utf8")) as { etag?: unknown; games?: unknown };
      const games = cleanKnownGames(kept);
      if (games?.length) {
        this.games = games;
        this.etag = typeof kept.etag === "string" ? kept.etag : null;
      }
    } catch {
      /* the first run, or a damaged copy: the bundled list stands */
    }
  }

  /** Asks the server; true when the list changed. A failure keeps what is here. */
  async refresh(): Promise<boolean> {
    try {
      const res = await this.src.fetch(this.src.url, this.etag);
      if (res.status !== 200) return false;
      const games = cleanKnownGames(res.body);
      if (!games?.length) return false;
      this.games = games;
      this.etag = res.etag;
      await fs.mkdir(path.dirname(this.src.cacheFile), { recursive: true });
      const tmp = `${this.src.cacheFile}.${process.pid}.tmp`;
      await fs.writeFile(tmp, JSON.stringify({ etag: this.etag, games }), { mode: 0o600 });
      await fs.rename(tmp, this.src.cacheFile);
      return true;
    } catch (err) {
      this.src.log?.("known games", { err: String(err) });
      return false;
    }
  }
}
