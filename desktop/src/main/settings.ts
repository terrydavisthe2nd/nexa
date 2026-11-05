/**
 * The app's own settings, kept in a JSON file in its user data folder. Written
 * to a temporary file and renamed, so a crash mid-write never leaves half.
 */
import fs from "node:fs";
import path from "node:path";
import type { Moved } from "./rpc/discord";
import type { HiddenGame, NativeStrings } from "../shared/bridge";
import type { StoredGame } from "./games/service";

export interface Stored {
  launchAtLogin: boolean;
  startMinimized: boolean;
  closeToTray: boolean;
  hardwareAcceleration: boolean;
  rpcEnabled: boolean;
  rpcRelay: boolean;
  hiddenApps: string[];
  /** Recognising games from what is running (main/games). */
  gameDetection: boolean;
  /** Games added from the running apps, on this computer only: they name its paths. */
  customGames: StoredGame[];
  /** Games kept off the person's profile. */
  hiddenGames: HiddenGame[];
  /** Programs someone said are not games when asked. */
  dismissedGames: string[];
  bounds: { x?: number; y?: number; width: number; height: number; maximized?: boolean } | null;
  /** Discord's socket, while this app has it moved (undone on a clean quit). */
  movedSocket: Moved | null;
  /** An update this app has already announced, so it does not nag. */
  lastUpdateSeen: string | null;
  /** The page's words for the tray, the menu bar and the offline page, so the next launch is already in its language. */
  strings: NativeStrings | null;
  /** A copy outside Applications whose offer to move there was turned down (macOS), so it is not made again. */
  declinedMove: string | null;
}

export const DEFAULTS: Stored = {
  launchAtLogin: true,
  startMinimized: false,
  closeToTray: true,
  hardwareAcceleration: true,
  rpcEnabled: true,
  rpcRelay: true,
  hiddenApps: [],
  gameDetection: true,
  customGames: [],
  hiddenGames: [],
  dismissedGames: [],
  bounds: null,
  movedSocket: null,
  lastUpdateSeen: null,
  strings: null,
  declinedMove: null,
};

export class SettingsStore {
  private data: Stored;
  private writeTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly file: string) {
    this.data = { ...DEFAULTS };
    try {
      const raw = JSON.parse(fs.readFileSync(file, "utf8")) as Partial<Stored>;
      for (const key of Object.keys(DEFAULTS) as (keyof Stored)[]) {
        if (raw[key] !== undefined && typeof raw[key] === typeof DEFAULTS[key]) (this.data as unknown as Record<string, unknown>)[key] = raw[key];
      }
      if (raw.bounds && typeof raw.bounds === "object") this.data.bounds = raw.bounds;
      if (raw.movedSocket && typeof raw.movedSocket === "object") this.data.movedSocket = raw.movedSocket;
      if (raw.strings && typeof raw.strings === "object") this.data.strings = raw.strings;
      if (typeof raw.declinedMove === "string") this.data.declinedMove = raw.declinedMove;
      if (!Array.isArray(this.data.hiddenApps)) this.data.hiddenApps = [];
      if (!Array.isArray(this.data.customGames)) this.data.customGames = [];
      this.data.customGames = this.data.customGames.filter((g) => typeof g?.id === "string" && typeof g.name === "string" && typeof g.match === "string" && typeof g.exe === "string");
      if (!Array.isArray(this.data.hiddenGames)) this.data.hiddenGames = [];
      this.data.hiddenGames = this.data.hiddenGames.filter((g) => typeof g?.key === "string" && typeof g.name === "string");
      if (!Array.isArray(this.data.dismissedGames)) this.data.dismissedGames = [];
    } catch {
      /* first run, or a damaged file: defaults */
    }
  }

  get<K extends keyof Stored>(key: K): Stored[K] {
    return this.data[key];
  }

  set(patch: Partial<Stored>, { now = false } = {}): void {
    this.data = { ...this.data, ...patch };
    if (now) {
      this.flush();
      return;
    }
    if (this.writeTimer) clearTimeout(this.writeTimer);
    this.writeTimer = setTimeout(() => this.flush(), 400);
  }

  flush(): void {
    if (this.writeTimer) clearTimeout(this.writeTimer);
    this.writeTimer = null;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = `${this.file}.${process.pid}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), { mode: 0o600 });
      fs.renameSync(tmp, this.file);
    } catch {
      /* a full or read-only disk: settings stay in memory for this run */
    }
  }
}
