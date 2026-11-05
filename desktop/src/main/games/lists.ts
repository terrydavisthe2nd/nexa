/**
 * The two lists the app downloads to recognise games: well-known programs
 * (known.ts) and the hashed names of IGDB's games (candidates.ts). Both are kept
 * on disk with what the server last called them, so an unchanged list costs a
 * single short answer and the next start needs no connection.
 */
import fs from "node:fs/promises";
import path from "node:path";
import { NameIndex } from "./candidates";
import { KnownList } from "./known";

export interface Answer {
  status: number;
  etag: string | null;
  bytes: Buffer;
}

export interface ListsOptions {
  base: string;
  dir: string;
  get(url: string, etag: string | null): Promise<Answer>;
  log?(msg: string, extra?: Record<string, unknown>): void;
}

/** A hundred times what the index takes today: anything bigger is not it. */
const MAX_INDEX_BYTES = 256 << 20;

export class GameLists {
  readonly known: KnownList;
  index: NameIndex | null = null;
  private indexTag: string | null = null;

  constructor(private readonly opts: ListsOptions) {
    this.known = new KnownList({
      url: new URL("/api/v1/games/known", opts.base).toString(),
      cacheFile: path.join(opts.dir, "known-games.json"),
      fetch: async (url, etag) => {
        const res = await opts.get(url, etag);
        return { status: res.status, etag: res.etag, body: res.status === 200 ? JSON.parse(res.bytes.toString("utf8")) : null };
      },
      ...(opts.log ? { log: opts.log } : {}),
    });
  }

  private get indexFile(): string {
    return path.join(this.opts.dir, "game-names.bin");
  }

  /** What the last run kept. */
  async load(): Promise<void> {
    await this.known.load();
    try {
      const index = NameIndex.parse(await fs.readFile(this.indexFile));
      if (index) {
        this.index = index;
        this.indexTag = (await fs.readFile(`${this.indexFile}.etag`, "utf8")).trim() || null;
      }
    } catch {
      /* none yet: nothing is suggested until there is one */
    }
  }

  /** Asks the server about both; true when the list of programs changed. */
  async refresh(): Promise<boolean> {
    const changed = await this.known.refresh();
    try {
      const res = await this.opts.get(new URL("/api/v1/games/name-index", this.opts.base).toString(), this.indexTag);
      if (res.status === 200 && res.bytes.length <= MAX_INDEX_BYTES) {
        const index = NameIndex.parse(res.bytes);
        if (index) {
          this.index = index;
          this.indexTag = res.etag;
          await fs.mkdir(this.opts.dir, { recursive: true });
          const tmp = `${this.indexFile}.${process.pid}.tmp`;
          await fs.writeFile(tmp, res.bytes, { mode: 0o600 });
          await fs.rename(tmp, this.indexFile);
          await fs.writeFile(`${this.indexFile}.etag`, res.etag ?? "", { mode: 0o600 });
        }
      }
    } catch (err) {
      this.opts.log?.("game name index", { err: String(err) });
    }
    return changed;
  }
}
