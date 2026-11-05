/**
 * Game detection as the app runs it: a look at what is running every few
 * seconds, which games those are, and the activities that makes.
 *
 * What is running stays on this computer. Only the name of a game found, and
 * the store id that names it exactly, ever goes further, and only as an
 * activity, which the page sends when the person shares their activity at all.
 * The page itself is told a program's list only while they choose a game.
 */
import { createHash } from "node:crypto";
import { EventEmitter } from "node:events";
import type { CustomGame, DetectedGame, GameShare, GameSuggestion, GamesState, HiddenGame, RpcActivity, RunningApp } from "../../shared/bridge";
import type { RpcClaim } from "../rpc/service";
import { judge, type NameIndex } from "./candidates";
import type { KnownGame } from "./known";
import { readLibrary } from "./library";
import { baseName, cleanName, emptyLibrary, entryOf, fileOf, folderName, identify, isHelper, knownIndex, norm, sameFamily, worthLooking, type Library, type Match, type Parents, type Proc } from "./match";
import { isWebApp, readMarkers } from "./markers";
import { LinuxLister, MacLister, type ProcessLister } from "./processes";
import { WindowsLister } from "./windows";

/** A custom game as it is kept: `match` is what entryOf says the program runs, so it holds a path. */
export interface StoredGame extends CustomGame {
  match: string;
}

export interface GameServiceOptions {
  enabled: boolean;
  custom: StoredGame[];
  hidden: HiddenGame[];
  /** This app's own processes, which are never a game. */
  ownPids(): Set<number>;
  /** Programs reporting through Rich Presence right now. */
  claims(): RpcClaim[];
  /** Programs someone already said are not games. */
  dismissed?: string[];
  /** A program's icon, when the system will give one. */
  iconOf?(path: string): Promise<string | undefined>;
  save(patch: { custom?: StoredGame[]; hidden?: HiddenGame[]; dismissed?: string[] }): void;
  /** The downloaded lists: known programs, and IGDB's names (lists.ts). */
  lists?: { known: { games: KnownGame[] }; index: NameIndex | null; load(): Promise<void>; refresh(): Promise<boolean> };
  isWebApp?(path: string): Promise<boolean>;
  lister?: ProcessLister;
  readLibrary?(): Promise<Library>;
  readMarkers?(path: string): Promise<Match | null>;
  platform?: NodeJS.Platform;
  now?(): number;
  log?(msg: string, extra?: Record<string, unknown>): void;
}

interface Playing {
  match: Match;
  pids: Set<number>;
  startedAt: number;
  /** Looks in a row that did not find it; it is let go after a second one. */
  missed: number;
}

const SCAN_MS = 15_000;
/** A game gone is looked for once more this soon, so a restart or a launcher handing over shows nothing, and an exit is quick. */
const RECHECK_MS = 4_000;
const LIBRARY_MS = 5 * 60_000;
/** Which looks also check how programs behave: asking the system about windows and the graphics card costs more than listing processes. */
const BEHAVIOUR_EVERY = 2;
/** Looks in a row a program must behave like a game, so a moment of it asks nothing. */
const LOOKS_TO_ASK = 2;
const LISTS_MS = 6 * 3600_000;
const MAX_DISMISSED = 500;
const NAME_MAX = 128;

export function platformLister(platform: NodeJS.Platform, log: GameServiceOptions["log"]): ProcessLister {
  if (platform === "win32") return new WindowsLister((m, e) => log?.(m, e));
  if (platform === "darwin") return new MacLister();
  return new LinuxLister();
}

/** Background programs of a Linux desktop session, which have no window of their own to pick. */
const LINUX_BACKGROUND = /^(systemd|dbus|pipewire|wireplumber|pulseaudio|gvfs|gnome-|gsd-|kwin|kded|plasmashell|ksmserver|xdg-|at-spi|ibus|fcitx|evolution-|tracker-|ssh-agent|gpg-agent|xorg|xwayland|mutter|sh|bash|zsh|fish|dash|tmux|screen|sudo|sshd|nexa|electron|chrome_crashpad|crashpad)/i;

/** Whether a program is something a person would see as an app they have open. */
export function pickable(p: Proc, titled: boolean, platform: NodeJS.Platform): boolean {
  const n = norm(p.path);
  const file = baseName(n);
  if (!n.includes("/")) return titled;
  if (platform === "win32") return titled && !/^[a-z]:\/windows\/(system32|systemapps|syswow64|explorer\.exe)/.test(n) && !/\/windows\/explorer\.exe$/.test(n);
  if (platform === "darwin") return /\.app\/contents\/macos\/[^/]+$/.test(n) && !/^\/system\//.test(n) && !/\.app\/contents\/(frameworks|library|plugins|xpcservices)\//.test(n) && !/helper/.test(file);
  if (titled) return true;
  // without a window to go by: not in a terminal, not part of the desktop itself, not a game's helper
  return !p.tty && !isHelper(fileOf(p)) && !LINUX_BACKGROUND.test(file) && !/^\/(usr\/)?(sbin|libexec|lib\/systemd)\//.test(n) && !/^\/usr\/lib(exec|32|64)?\/.*(daemon|helper|portal|agent)/.test(n);
}

function appLabel(p: Proc, title: string | undefined): string {
  const bundle = /([^/]+)\.app\/contents\/macos\//i.exec(p.path.replace(/\\/g, "/"))?.[1];
  if (bundle) return cleanName(bundle);
  if (title) {
    const t = cleanName(title);
    if (t) return t;
  }
  // with no window to read, a game's own folder names it better than its file: "Cool Game", not "cg_x64"
  const file = fileOf(p);
  if (worthLooking(entryOf(p))) {
    const folder = folderName(entryOf(p));
    if (folder) return folder;
  }
  return cleanName(file, true) || file;
}

export declare interface GameService {
  on(event: "state", listener: (state: GamesState) => void): this;
  on(event: "activities", listener: () => void): this;
}

export class GameService extends EventEmitter {
  private readonly platform: NodeJS.Platform;
  private readonly lister: ProcessLister;
  private readonly clock: () => number;
  private enabled: boolean;
  private custom: StoredGame[];
  private hidden: HiddenGame[];
  private library: Library = emptyLibrary();
  private libraryAt = 0;
  private libraryLoading: Promise<void> | null = null;
  private readonly playing = new Map<string, Playing>();
  private parents: Parents = new Map();
  /** What each program's surroundings said, once per program. */
  private readonly markers = new Map<string, Match | null>();
  /** The program behind each row of the last list given to the page. */
  private picks = new Map<string, { match: string; exe: string }>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private listsTimer: ReturnType<typeof setInterval> | null = null;
  private scanning = false;
  private stopped = true;
  private looks = 0;
  private dismissed: Set<string>;
  /** Programs behaving like games, by what they run, and how many looks in a row. */
  private readonly watched = new Map<string, { name: string; exe: string; count: number }>();
  private suggested = new Map<string, GameSuggestion & { match: string }>();

  constructor(private readonly opts: GameServiceOptions) {
    super();
    this.platform = opts.platform ?? process.platform;
    this.lister = opts.lister ?? platformLister(this.platform, opts.log);
    this.clock = opts.now ?? Date.now;
    this.enabled = opts.enabled;
    this.custom = opts.custom;
    this.hidden = opts.hidden;
    this.dismissed = new Set(opts.dismissed ?? []);
  }

  start(): void {
    this.stopped = false;
    if (this.enabled) this.schedule(0);
    void this.startLists();
  }

  private async startLists(): Promise<void> {
    const lists = this.opts.lists;
    if (!lists) return;
    await lists.load().catch(() => undefined);
    this.useKnown();
    const refresh = () =>
      lists
        .refresh()
        .then((changed) => {
          if (changed) this.useKnown();
        })
        .catch((err) => this.opts.log?.("game lists", { err: String(err) }));
    void refresh();
    if (this.stopped || this.listsTimer) return;
    this.listsTimer = setInterval(() => void refresh(), LISTS_MS);
    this.listsTimer.unref?.();
  }

  private useKnown(): void {
    if (this.opts.lists) this.library.known = knownIndex(this.opts.lists.known.games);
  }

  stop(): void {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    if (this.listsTimer) clearInterval(this.listsTimer);
    this.listsTimer = null;
  }

  private schedule(ms: number): void {
    if (this.stopped || !this.enabled) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.scan(), ms);
    this.timer.unref?.();
  }

  /** Reads the stores again; a game installed since is worth one look straight away. */
  private loadLibrary(): Promise<void> {
    this.libraryLoading ??= (this.opts.readLibrary ?? (() => readLibrary(this.platform, process.env, process.env.HOME ?? process.env.USERPROFILE ?? "")))()
      .then((library) => {
        this.library = library;
        this.useKnown();
        this.libraryAt = this.clock();
      })
      .catch((err) => this.opts.log?.("game library", { err: String(err) }))
      .finally(() => {
        this.libraryLoading = null;
      });
    return this.libraryLoading;
  }

  private customMatches() {
    return this.custom.map((c) => ({ id: c.id, name: c.name, match: c.match, ...(c.gameId ? { gameId: c.gameId } : {}) }));
  }

  private async recognise(p: Proc): Promise<Match | null> {
    const found = identify(p, this.library, this.customMatches());
    if (found) return found;
    const exe = entryOf(p);
    if (/^[a-z0-9-]+:/i.test(exe) && !/^[a-z]:[\\/]/i.test(exe)) return null;
    if (!worthLooking(p.path) || !worthLooking(exe)) return null;
    // a Wine program's Windows path is no use here; what its own folder holds is read through the path it runs at, or not at all
    const local = /^[a-z]:[\\/]/i.test(exe) && this.platform !== "win32" ? exe.replace(/^z:/i, "").replace(/\\/g, "/") : exe;
    const key = norm(local);
    if (!this.markers.has(key)) {
      if (this.markers.size > 4000) this.markers.clear();
      try {
        this.markers.set(key, await (this.opts.readMarkers ?? readMarkers)(local));
      } catch {
        this.markers.set(key, null);
      }
    }
    return this.markers.get(key) ?? null;
  }

  /** One look at what is running. */
  async scan(): Promise<void> {
    if (this.scanning || !this.enabled) return;
    this.scanning = true;
    let recheck = false;
    try {
      if (!this.libraryAt || this.clock() - this.libraryAt > LIBRARY_MS) await this.loadLibrary();
      const procs = await this.lister.list();
      if (!this.enabled) return;
      const own = this.opts.ownPids();
      const parents: Parents = new Map();
      for (const p of procs) parents.set(p.pid, p.ppid);
      this.parents = parents;
      const found = new Map<string, { match: Match; pids: Set<number>; startedAt?: number }>();
      let unlisted = false;
      const unknown: Proc[] = [];
      for (const p of procs) {
        if (own.has(p.pid)) continue;
        const match = await this.recognise(p);
        if (!match) {
          unknown.push(p);
          continue;
        }
        unlisted ||= match.source === "steam" && !match.store;
        const at = found.get(match.key);
        if (at) {
          at.pids.add(p.pid);
          if (p.startedAt && (!at.startedAt || p.startedAt < at.startedAt)) at.startedAt = p.startedAt;
        } else {
          found.set(match.key, { match, pids: new Set([p.pid]), ...(p.startedAt ? { startedAt: p.startedAt } : {}) });
        }
      }
      // a Steam folder its manifests do not list yet: just installed
      if (unlisted && this.clock() - this.libraryAt > 30_000) void this.loadLibrary();

      let changed = false;
      for (const [key, seen] of found) {
        const was = this.playing.get(key);
        if (was) {
          was.missed = 0;
          was.pids = seen.pids;
          if (was.match.name !== seen.match.name) changed = true;
          was.match = seen.match;
        } else {
          this.playing.set(key, { match: seen.match, pids: seen.pids, startedAt: seen.startedAt ?? this.clock(), missed: 0 });
          changed = true;
        }
      }
      for (const [key, was] of this.playing) {
        if (found.has(key)) continue;
        was.missed++;
        if (was.missed >= 2) {
          this.playing.delete(key);
          changed = true;
        } else {
          recheck = true;
        }
      }
      this.looks++;
      if (this.looks % BEHAVIOUR_EVERY === 1 && (await this.watchBehaviour(unknown, procs))) changed = true;
      if (changed) this.changed();
    } catch (err) {
      this.opts.log?.("game scan failed", { err: String(err) });
    } finally {
      this.scanning = false;
      this.schedule(recheck ? RECHECK_MS : SCAN_MS);
    }
  }

  /**
   * Programs nothing recognised, checked for behaving like a game with a game's
   * name. True when what to ask about changed. All of this stays here: the
   * person is asked, and only their yes sends anything.
   */
  private async watchBehaviour(unknown: Proc[], all: Proc[]): Promise<boolean> {
    const before = [...this.suggested.keys()].join();
    const alive = new Set(all.map((p) => norm(entryOf(p))));
    for (const key of this.suggested.keys()) {
      const s = this.suggested.get(key);
      if (s && !alive.has(s.match)) this.suggested.delete(key);
    }
    const index = this.opts.lists?.index ?? null;
    if (!index || !this.lister.foreground) return before !== [...this.suggested.keys()].join();
    const open = unknown.filter((p) => worthLooking(p.path) && !isHelper(fileOf(p)));
    const [front, busy] = await Promise.all([this.lister.foreground().catch(() => null), this.lister.gpuBusy?.(open.map((p) => p.pid)).catch(() => new Set<number>()) ?? Promise.resolve(new Set<number>())]);
    const seen = new Set<string>();
    for (const p of open) {
      const isFront = front?.pid === p.pid;
      const evidence = { fullscreen: isFront && !!front?.fullscreen, gpu: busy.has(p.pid), osGame: this.library.osGames.has(norm(p.path)) };
      if (!evidence.fullscreen && !evidence.gpu) continue;
      const match = norm(entryOf(p));
      if (this.dismissed.has(match) || this.custom.some((c) => c.match === match) || seen.has(match)) continue;
      const found = judge(p, { ...(isFront && front?.title ? { title: front.title } : {}), product: (await this.lister.product?.(p.path).catch(() => null)) ?? null }, evidence, index);
      if (!found) continue;
      seen.add(match);
      const at = this.watched.get(match);
      const count = (at?.count ?? 0) + 1;
      this.watched.set(match, { name: found.name, exe: fileOf(p), count });
      if (count >= LOOKS_TO_ASK && ![...this.suggested.values()].some((s) => s.match === match) && !(await (this.opts.isWebApp ?? isWebApp)(p.path).catch(() => false))) {
        const id = createHash("sha256").update(match).digest("hex").slice(0, 16);
        this.suggested.set(id, { id, name: found.name, exe: fileOf(p), match });
      }
    }
    for (const key of this.watched.keys()) if (!seen.has(key)) this.watched.delete(key);
    return before !== [...this.suggested.keys()].join();
  }

  /** A system call never came back: quitting normally would wait for it for ever. */
  get stuck(): boolean {
    return !!this.lister.stuck;
  }

  /** Answers a suggestion: yes makes it a game like one added by hand, no never asks about that program again. */
  answer(id: string, isGame: boolean): void {
    const s = this.suggested.get(id);
    if (!s) return;
    this.suggested.delete(id);
    this.watched.delete(s.match);
    if (isGame) {
      this.custom = [...this.custom, { id, name: s.name, exe: s.exe, match: s.match }];
      this.saved();
      return;
    }
    this.dismissed.add(s.match);
    this.opts.save({ dismissed: [...this.dismissed].slice(-MAX_DISMISSED) });
    this.emit("state", this.state());
  }

  /** What sharing a game the person added would send, for the page to send it: a file name, never a path. */
  shareInfo(id: string): GameShare | null {
    const g = this.custom.find((c) => c.id === id);
    if (!g) return null;
    return { process: g.exe, os: this.platform, name: g.name, ...(g.gameId ? { game_id: g.gameId } : {}) };
  }

  markShared(id: string): void {
    this.custom = this.custom.map((c) => (c.id === id ? { ...c, shared: true } : c));
    this.opts.save({ custom: this.custom });
    this.emit("state", this.state());
  }

  /** Says everything again: Rich Presence changed, so what a game gives way to may have too. */
  rpcChanged(): void {
    if (this.playing.size > 0) this.emit("state", this.state());
  }

  private changed(): void {
    this.emit("state", this.state());
    this.emit("activities");
  }

  private reported(g: Playing): boolean {
    const name = g.match.name.toLowerCase();
    return this.opts.claims().some((c) => {
      if (c.pid !== undefined) for (const pid of g.pids) if (sameFamily(pid, c.pid, this.parents)) return true;
      return c.game && c.name?.toLowerCase() === name;
    });
  }

  private activityOf(g: Playing): RpcActivity {
    return {
      application_id: "",
      type: 0,
      name: g.match.name,
      detected: true,
      ...(g.match.store ? { store: g.match.store } : {}),
      ...(g.match.storeId ? { store_id: g.match.storeId } : {}),
      ...(g.match.gameId ? { game_id: g.match.gameId } : {}),
      timestamps: { start: g.startedAt },
    };
  }

  /** What to show after what programs report themselves: games still running, shown, and saying nothing about themselves. */
  activities(): RpcActivity[] {
    if (!this.enabled) return [];
    const hidden = new Set(this.hidden.map((h) => h.key));
    return [...this.playing.entries()]
      .filter(([key, g]) => !hidden.has(key) && !this.reported(g))
      .sort((a, b) => b[1].startedAt - a[1].startedAt)
      .map(([, g]) => this.activityOf(g));
  }

  state(): GamesState {
    const hidden = new Set(this.hidden.map((h) => h.key));
    const running: DetectedGame[] = [...this.playing.entries()]
      .sort((a, b) => b[1].startedAt - a[1].startedAt)
      .map(([key, g]) => ({ key, name: g.match.name, source: g.match.source, startedAt: g.startedAt, hidden: hidden.has(key), reporting: this.reported(g), activity: this.activityOf(g) }));
    return {
      enabled: this.enabled,
      running,
      custom: this.custom.map(({ id, name, exe, gameId, shared }) => ({ id, name, exe, ...(gameId ? { gameId } : {}), ...(shared ? { shared } : {}) })),
      hidden: this.hidden,
      suggestions: this.enabled ? [...this.suggested.values()].map(({ id, name, exe }) => ({ id, name, exe })) : [],
    };
  }

  setEnabled(on: boolean): void {
    if (on === this.enabled) return;
    this.enabled = on;
    if (on) {
      this.schedule(0);
    } else {
      if (this.timer) clearTimeout(this.timer);
      this.timer = null;
      this.playing.clear();
      this.suggested.clear();
      this.watched.clear();
    }
    this.changed();
  }

  setHidden(key: string, name: string, hidden: boolean): void {
    this.hidden = this.hidden.filter((h) => h.key !== key);
    if (hidden) this.hidden = [...this.hidden, { key, name: Array.from(name).slice(0, NAME_MAX).join("") }];
    this.opts.save({ hidden: this.hidden });
    this.changed();
  }

  /** The programs with a window right now, once, for the person choosing a game. */
  async runningApps(): Promise<RunningApp[]> {
    const [procs, titles] = await Promise.all([this.lister.list(), this.lister.windows().catch(() => new Map<number, string>())]);
    if (!this.libraryAt) await this.loadLibrary();
    const own = this.opts.ownPids();
    const rows = new Map<string, RunningApp & { path: string; titled: boolean }>();
    const picks = new Map<string, { match: string; exe: string }>();
    for (const p of procs) {
      if (own.has(p.pid)) continue;
      const title = titles.get(p.pid);
      if (!pickable(p, !!title, this.platform)) continue;
      const entry = norm(entryOf(p));
      const id = createHash("sha256").update(entry).digest("hex").slice(0, 16);
      if (rows.has(id) && (!title || rows.get(id)!.titled)) continue;
      const known = await this.recognise(p);
      const exe = fileOf(p);
      rows.set(id, {
        id,
        name: appLabel(p, title),
        exe,
        added: this.custom.some((c) => c.match === entry),
        ...(known && known.source !== "custom" ? { detectedAs: known.name } : {}),
        path: p.path,
        titled: !!title,
      });
      picks.set(id, { match: entry, exe });
      if (rows.size >= 300) break;
    }
    this.picks = picks;
    const list = [...rows.values()].sort((a, b) => Number(b.titled) - Number(a.titled) || a.name.localeCompare(b.name));
    return Promise.all(
      list.map(async ({ path, titled: _titled, ...row }) => {
        const icon = await this.opts.iconOf?.(path).catch(() => undefined);
        return icon ? { ...row, icon } : row;
      }),
    );
  }

  /** Adds the program from the last list as a game. */
  add(appId: string, name: string, gameId: string | null): void {
    const pick = this.picks.get(appId);
    const clean = cleanName(name);
    if (!pick || !clean) return;
    const already = this.custom.find((c) => c.match === pick.match);
    const id = already?.id ?? createHash("sha256").update(pick.match).digest("hex").slice(0, 16);
    const game: StoredGame = { id, name: clean, exe: pick.exe, match: pick.match, ...(validGameId(gameId) ? { gameId: gameId! } : {}) };
    this.custom = already ? this.custom.map((c) => (c.id === id ? game : c)) : [...this.custom, game];
    this.saved();
  }

  update(id: string, name: string, gameId: string | null): void {
    const clean = cleanName(name);
    if (!clean) return;
    this.custom = this.custom.map((c) => {
      if (c.id !== id) return c;
      const { gameId: _old, ...rest } = c;
      return { ...rest, name: clean, ...(validGameId(gameId) ? { gameId: gameId! } : {}) };
    });
    this.saved();
  }

  remove(id: string): void {
    this.custom = this.custom.filter((c) => c.id !== id);
    this.hidden = this.hidden.filter((h) => h.key !== `custom:${id}`);
    this.opts.save({ hidden: this.hidden });
    this.saved();
  }

  /** A game added, changed or removed shows at once, without waiting for the next look. */
  private saved(): void {
    this.opts.save({ custom: this.custom });
    for (const [key, g] of this.playing) if (g.match.source === "custom" && !this.custom.some((c) => `custom:${c.id}` === key)) this.playing.delete(key);
    this.emit("state", this.state());
    this.emit("activities");
    this.schedule(0);
  }
}

function validGameId(id: string | null): boolean {
  return !!id && /^\d{1,10}$/.test(id);
}
