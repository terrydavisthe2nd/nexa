/**
 * One program connected to the Rich Presence server.
 *
 * When Discord runs (and relaying is on) the session opens its own connection
 * to Discord with the program's handshake and passes every frame through both
 * ways, so the program gets Discord's genuine replies and events and its
 * presence shows on Discord. When Discord is not running, or quits part way,
 * the session answers the program itself. Either way it reads SET_ACTIVITY on
 * the way past, which is how the activity reaches Nexa.
 */
import type { RpcActivity } from "../../shared/bridge";
import { normalizeActivity } from "./activity";
import { OP, encodeFrame, type Frame } from "./frame";
import { Upstream } from "./upstream";

/** A program's end of the connection, whatever carries it. */
export interface AppConnection {
  readonly id: string;
  readonly transport: "ipc" | "ws";
  /** Sends a message with the FRAME opcode. */
  send(json: unknown): void;
  /** Passes on a whole IPC frame from Discord (the WebSocket transport sends its JSON). */
  sendFrame(frame: Frame): void;
  close(code: number, message: string): void;
}

export interface SessionDeps {
  /** Where Discord listens, best first; empty when it is not running or relaying is off. */
  discordEndpoints(): Promise<string[]>;
  /** The user named in a READY this server gives itself. */
  readyUser(): Record<string, unknown>;
  /** The program's activity changed (null: cleared). */
  onActivity(sessionId: string, clientId: string, activity: RpcActivity | null, pid?: number): void;
  /** The session reached or lost Discord. */
  onRelayChange(): void;
  log(msg: string, extra?: Record<string, unknown>): void;
}

const LOCAL_CONFIG = { cdn_host: "cdn.discordapp.com", api_endpoint: "//discord.com/api", environment: "production" };
/** Discord takes five activity updates in twenty seconds from a program; so do we. */
const UPDATES = 5;
const WINDOW_MS = 20_000;

type Message = { cmd?: unknown; nonce?: unknown; evt?: unknown; args?: Record<string, unknown> };

export class RpcSession {
  private upstream: Upstream | null = null;
  private ended = false;
  private updateTimes: number[] = [];
  private pending: { activity: RpcActivity | null } | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;
  private pid: number | undefined;

  constructor(
    readonly conn: AppConnection,
    readonly clientId: string,
    private readonly deps: SessionDeps,
  ) {}

  get relayed(): boolean {
    return this.upstream !== null;
  }

  /** Answers the handshake: through Discord when it is there, from here otherwise. */
  async start(): Promise<void> {
    for (const target of await this.deps.discordEndpoints()) {
      const opened = await Upstream.open(target, this.clientId);
      if (this.ended) {
        if (opened.kind === "ready") opened.upstream.close();
        return;
      }
      if (opened.kind === "refused") {
        // Discord's answer is the answer the program would have had from it
        this.deps.log("discord refused handshake", { clientId: this.clientId, code: opened.code });
        this.conn.close(opened.code, opened.message);
        return;
      }
      if (opened.kind === "ready") {
        this.attach(opened.upstream);
        this.conn.sendFrame(opened.ready);
        return;
      }
    }
    this.conn.send({ cmd: "DISPATCH", evt: "READY", nonce: null, data: { v: 1, config: LOCAL_CONFIG, user: this.deps.readyUser() } });
  }

  private attach(up: Upstream): void {
    this.upstream = up;
    up.onFrame((f) => {
      if (!this.ended) this.conn.sendFrame(f);
    });
    up.onClose(() => {
      if (this.upstream !== up) return;
      // Discord went away: the program keeps its connection and is answered from here from now on
      this.upstream = null;
      if (!this.ended) this.deps.onRelayChange();
    });
    this.deps.onRelayChange();
  }

  /** Stops passing this program's frames to Discord; it carries on answered from here. */
  detachFromDiscord(): void {
    const up = this.upstream;
    this.upstream = null;
    up?.close();
  }

  /** A frame from the program, after the handshake. */
  handle(frame: Frame): void {
    if (this.ended) return;
    if (frame.op === OP.CLOSE) {
      this.upstream?.sendRaw(frame.raw);
      this.end();
      this.conn.close(1000, "Closed");
      return;
    }
    if (frame.op === OP.PING || frame.op === OP.PONG) {
      if (this.upstream) this.upstream.sendRaw(frame.raw);
      else if (frame.op === OP.PING) this.conn.sendFrame({ op: OP.PONG, json: frame.json, raw: encodeFrame(OP.PONG, frame.json) });
      return;
    }
    if (frame.op !== OP.FRAME) return;
    const msg = (frame.json ?? {}) as Message;
    if (msg.cmd === "SET_ACTIVITY") this.readActivity(msg.args);
    if (this.upstream) {
      this.upstream.sendRaw(frame.raw);
      return;
    }
    this.answer(msg);
  }

  /** A command answered here, while Discord is not in the loop. */
  private answer(msg: Message): void {
    const nonce = msg.nonce ?? null;
    switch (msg.cmd) {
      case "SET_ACTIVITY": {
        const activity = normalizeActivity(this.clientId, msg.args?.activity);
        this.conn.send({ cmd: "SET_ACTIVITY", evt: null, nonce, data: activity ? { ...activity, name: activity.name ?? "", created_at: Date.now() } : null });
        return;
      }
      case "SUBSCRIBE":
      case "UNSUBSCRIBE":
        this.conn.send({ cmd: msg.cmd, evt: null, nonce, data: { evt: typeof msg.evt === "string" ? msg.evt : null } });
        return;
      case "SEND_ACTIVITY_JOIN_INVITE":
      case "CLOSE_ACTIVITY_REQUEST":
        this.conn.send({ cmd: msg.cmd, evt: null, nonce, data: null });
        return;
      default:
        this.conn.send({
          cmd: typeof msg.cmd === "string" ? msg.cmd : "UNKNOWN",
          evt: "ERROR",
          nonce,
          data: { code: 4002, message: "Nexa handles Rich Presence; this command needs Discord to be running." },
        });
    }
  }

  private readActivity(args: Record<string, unknown> | undefined): void {
    const activity = args && "activity" in args ? normalizeActivity(this.clientId, args.activity) : null;
    // the libraries say which process is playing, which ties this to the game running (main/games)
    const pid = typeof args?.pid === "number" && Number.isInteger(args.pid) && args.pid > 1 ? args.pid : undefined;
    if (pid !== undefined) this.pid = pid;
    const now = Date.now();
    this.updateTimes = this.updateTimes.filter((t) => now - t < WINDOW_MS);
    if (this.updateTimes.length < UPDATES && !this.pendingTimer) {
      this.updateTimes.push(now);
      this.deps.onActivity(this.conn.id, this.clientId, activity, this.pid);
      return;
    }
    // over the limit: the latest waits for the window to open, earlier ones are dropped
    this.pending = { activity };
    if (this.pendingTimer) return;
    const wait = Math.max(0, (this.updateTimes[0] ?? now) + WINDOW_MS - now);
    this.pendingTimer = setTimeout(() => {
      this.pendingTimer = null;
      const p = this.pending;
      this.pending = null;
      if (!p || this.ended) return;
      this.updateTimes.push(Date.now());
      this.deps.onActivity(this.conn.id, this.clientId, p.activity, this.pid);
    }, wait);
  }

  /** The program is gone: its activity ends with it, here and on Discord. */
  end(): void {
    if (this.ended) return;
    this.ended = true;
    if (this.pendingTimer) clearTimeout(this.pendingTimer);
    this.pendingTimer = null;
    const up = this.upstream;
    this.upstream = null;
    up?.close();
    this.deps.onActivity(this.conn.id, this.clientId, null);
  }
}
