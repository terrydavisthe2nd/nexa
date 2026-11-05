/**
 * The Rich Presence server as the app runs it: the IPC and WebSocket
 * transports, a session per connected program, and the activities they report.
 */
import { EventEmitter } from "node:events";
import fs from "node:fs";
import type { Identity, RpcActivity, RpcApp, RpcState } from "../../shared/bridge";
import { DiscordLocator, restartDiscordWindows, type Moved } from "./discord";
import type { Frame } from "./frame";
import { IpcServer } from "./ipcServer";
import { RpcSession, type AppConnection } from "./session";
import { WsServer } from "./wsServer";

export interface RpcServiceOptions {
  enabled: boolean;
  relay: boolean;
  hidden: string[];
  /** A Discord socket a previous run moved and could not put back. */
  leftoverMove: Moved | null;
  rememberMove(m: Moved | null): void;
  identity(): Identity | null;
  platform?: NodeJS.Platform;
  env?: NodeJS.ProcessEnv;
  /** The WebSocket transport, off in tests that run beside a real Discord. */
  websocket?: boolean;
  log?(msg: string, extra?: Record<string, unknown>): void;
}

interface Entry {
  session: RpcSession;
  clientId: string;
  activity: RpcActivity | null;
  connectedAt: number;
  updatedAt: number;
  pid?: number;
}

/** A program reporting something, for telling whether a detected game is already covered (main/games). */
export interface RpcClaim {
  pid?: number;
  name?: string;
  /** It reports a game, rather than a song or a video. */
  game: boolean;
}

export declare interface RpcService {
  on(event: "state", listener: (state: RpcState) => void): this;
  on(event: "activities", listener: (list: RpcActivity[]) => void): this;
}

export class RpcService extends EventEmitter {
  private readonly platform: NodeJS.Platform;
  private readonly env: NodeJS.ProcessEnv;
  private readonly locator: DiscordLocator;
  private ipc: IpcServer | null = null;
  private ws: WsServer | null = null;
  private readonly entries = new Map<string, Entry>();
  private readonly hidden: Set<string>;
  private enabled: boolean;
  private relay: boolean;
  private discordFirst = false;
  private emitTimer: ReturnType<typeof setTimeout> | null = null;
  private starting: Promise<void> | null = null;

  constructor(private readonly opts: RpcServiceOptions) {
    super();
    this.platform = opts.platform ?? process.platform;
    this.env = opts.env ?? process.env;
    this.enabled = opts.enabled;
    this.relay = opts.relay;
    this.hidden = new Set(opts.hidden);
    this.locator = new DiscordLocator(this.platform, this.env, opts.rememberMove);
  }

  private log(msg: string, extra?: Record<string, unknown>): void {
    this.opts.log?.(msg, extra);
  }

  async start(): Promise<void> {
    if (!this.enabled) return;
    if (this.starting) return this.starting;
    this.starting = this.open().finally(() => {
      this.starting = null;
    });
    return this.starting;
  }

  private async open(): Promise<void> {
    if (this.ipc) return;
    const claim = await this.locator.claimFirst(this.opts.leftoverMove);
    this.discordFirst = claim === "first";
    const handlers = { onSession: (conn: AppConnection, clientId: string) => this.addSession(conn, clientId), log: (m: string, e?: Record<string, unknown>) => this.log(m, e) };
    const ipc = new IpcServer(handlers, this.platform, this.env);
    try {
      await ipc.listen();
      this.ipc = ipc;
    } catch (err) {
      this.log("rpc ipc listen failed", { err: String(err) });
      this.locator.restore();
    }
    if (this.opts.websocket !== false) {
      const ws = new WsServer(handlers);
      if ((await ws.listen()) !== null) this.ws = ws;
    }
    this.log("rpc listening", { ipc: this.ipc?.path, ws: this.ws?.port, discordFirst: this.discordFirst, moved: this.locator.moved?.to });
    await this.refreshDiscord();
    this.changed();
  }

  async stop(): Promise<void> {
    await this.starting;
    for (const e of [...this.entries.values()]) e.session.end();
    this.entries.clear();
    await Promise.all([this.ipc?.close(), this.ws?.close()]);
    this.ipc = null;
    this.ws = null;
    // Discord gets its own name back only once ours is gone
    this.locator.restore();
    this.changed();
  }

  /**
   * What has to happen before the process ends, all at once: programs are
   * let go, our socket is removed and Discord's gets its own name back. The
   * servers finish closing with the process. Nothing here waits, because an
   * exit that has to wait (a shutdown, a logout, a kill) may never come back.
   */
  stopForExit(): void {
    for (const e of [...this.entries.values()]) e.session.end();
    this.entries.clear();
    const own = this.ipc?.path ?? null;
    void this.ipc?.close();
    void this.ws?.close();
    this.ipc = null;
    this.ws = null;
    // a socket file stays until its server has closed; Discord's can only take the name once ours is gone
    if (own && this.platform !== "win32") {
      try {
        fs.rmSync(own, { force: true });
      } catch {
        /* already gone */
      }
    }
    this.locator.restore();
  }

  private addSession(conn: AppConnection, clientId: string): { handle(frame: Frame): void; end(): void } {
    const session = new RpcSession(conn, clientId, {
      discordEndpoints: async () => (this.relay ? this.locator.endpoints(this.ipc?.path ?? null) : []),
      readyUser: () => this.readyUser(),
      onActivity: (id, _clientId, activity, pid) => this.setActivity(id, activity, pid),
      onRelayChange: () => this.changed(),
      log: (m, e) => this.log(m, e),
    });
    const now = Date.now();
    this.entries.set(conn.id, { session, clientId, activity: null, connectedAt: now, updatedAt: now });
    void session.start().catch((err) => this.log("rpc session start failed", { err: String(err) }));
    this.changed();
    return {
      handle: (frame) => session.handle(frame),
      end: () => {
        // the program is gone, and so is what it was showing
        session.end();
        this.entries.delete(conn.id);
        this.changed();
      },
    };
  }

  private setActivity(sessionId: string, activity: RpcActivity | null, pid?: number): void {
    const e = this.entries.get(sessionId);
    if (!e) return;
    e.activity = activity;
    e.updatedAt = Date.now();
    if (pid !== undefined) e.pid = pid;
    this.changed();
  }

  private readyUser(): Record<string, unknown> {
    const me = this.opts.identity();
    return {
      id: me?.id ?? "0",
      username: me?.username ?? "evilion",
      discriminator: "0",
      global_name: me?.global_name ?? "Nexa",
      avatar: null,
      avatar_decoration_data: null,
      bot: false,
      flags: 0,
      premium_type: 0,
    };
  }

  /** The activities to show, newest first, hidden programs left out. */
  activities(): RpcActivity[] {
    if (!this.enabled) return [];
    return [...this.entries.values()]
      .filter((e) => e.activity && !this.hidden.has(e.clientId))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((e) => e.activity!);
  }

  /**
   * Every program reporting something, hidden ones too: hiding a game's Rich
   * Presence must not let the same game show again as a detected one.
   */
  claims(): RpcClaim[] {
    if (!this.enabled) return [];
    return [...this.entries.values()]
      .filter((e) => e.activity)
      .map((e) => ({ ...(e.pid !== undefined ? { pid: e.pid } : {}), ...(e.activity?.name ? { name: e.activity.name } : {}), game: e.activity?.type === 0 || e.activity?.type === 5 }));
  }

  state(): RpcState {
    const apps: RpcApp[] = [...this.entries.values()]
      .sort((a, b) => b.connectedAt - a.connectedAt)
      .map((e) => ({ clientId: e.clientId, name: e.activity?.name ?? null, activity: e.activity, hidden: this.hidden.has(e.clientId), connectedAt: e.connectedAt }));
    const relayed = [...this.entries.values()].some((e) => e.session.relayed);
    return {
      enabled: this.enabled,
      relay: this.relay,
      listening: { ipc: this.ipc?.path ?? null, ws: this.ws?.port ?? null },
      discord: this.discordFirst ? "first" : relayed || this.discordSeen ? "connected" : "absent",
      apps,
    };
  }

  /** Whether Discord answered the last look for it. */
  private discordSeen = false;

  /** Looks again for Discord, for the settings page (a probe, never a handshake). */
  async refreshDiscord(): Promise<RpcState> {
    this.discordSeen = this.enabled && !!this.ipc && (await this.locator.endpoints(this.ipc.path)).length > 0;
    return this.state();
  }

  async setEnabled(on: boolean): Promise<void> {
    if (on === this.enabled) return;
    this.enabled = on;
    if (on) await this.start();
    else await this.stop();
    this.changed();
  }

  setRelay(on: boolean): void {
    if (on === this.relay) return;
    this.relay = on;
    // turning it off takes these programs off Discord at once; turning it on reaches programs as they reconnect
    if (!on) for (const e of this.entries.values()) e.session.detachFromDiscord();
    this.changed();
  }

  setHidden(clientId: string, hidden: boolean): void {
    if (hidden) this.hidden.add(clientId);
    else this.hidden.delete(clientId);
    this.changed();
  }

  hiddenApps(): string[] {
    return [...this.hidden];
  }

  /** Windows: restarts Discord behind our server so programs reach both. */
  async restartDiscord(): Promise<boolean> {
    if (this.platform !== "win32") return false;
    const ok = await restartDiscordWindows(async () => {
      await this.stop();
      await this.start();
    });
    this.changed();
    return ok;
  }

  private changed(): void {
    if (this.emitTimer) return;
    this.emitTimer = setTimeout(() => {
      this.emitTimer = null;
      this.emit("state", this.state());
      this.emit("activities", this.activities());
    }, 50);
  }
}
