import type { Presence } from "@/api/types";
import { isGatewayFrame, type GatewayEvent } from "./events";

export type GatewayStatus = "idle" | "connecting" | "open" | "closed";

export interface GatewayOptions {
  url: () => string;
  token: () => string | null;
  onEvent: (e: GatewayEvent) => void;
  onStatus?: (s: GatewayStatus, info?: { code?: number; reason?: string; attempt: number }) => void;
  WebSocketImpl?: typeof WebSocket;
  /** Heartbeat interval; Cloudflare drops idle sockets around 100 s. */
  heartbeatMs?: number;
  setTimeoutImpl?: typeof setTimeout;
  clearTimeoutImpl?: typeof clearTimeout;
  random?: () => number;
  /** Told how long a connection took to be ready and how long each heartbeat took to come back (lib/perfReports). */
  onTiming?: (what: "gateway_connect" | "gateway_rtt", ms: number) => void;
}

const MAX_QUEUE = 64;
/**
 * The longest wait between reconnects. It stays under the server's grace for a
 * call that outlives its socket (internal/api/voice_glue.go), so a blip never
 * ends a call whose sound is still going through.
 */
const MAX_BACKOFF_MS = 12_000;
/**
 * How long a heartbeat may go unanswered. A phone that changes network or
 * wakes from sleep can keep a socket "open" for minutes after its connection
 * is gone, and nothing but silence ever says so.
 */
const ACK_TIMEOUT_MS = 8_000;
/**
 * How long a handshake may take. Only the heartbeat had a deadline, and it
 * starts in `onopen`: a phone suspended while the socket was still connecting
 * can come back with it stuck in CONNECTING and no close event ever, which
 * neither `connect()` nor `wake()` would touch again.
 */
const CONNECT_TIMEOUT_MS = 15_000;
/**
 * How long a heartbeat may go unanswered before ready has arrived. Ready is
 * one frame, and the answer to a heartbeat queues behind it: a large account
 * on a slow line can take longer than ACK_TIMEOUT_MS to download it, and
 * dropping the socket then only starts the same download again, for ever.
 */
const READY_ACK_TIMEOUT_MS = 90_000;

/** Close codes the server gives a reason with (internal/gateway/conn.go). */
export const CLOSE_RATE_LIMITED = 4008;
export const CLOSE_TOO_MANY = 4009;
/**
 * A socket turned away for being one too many (for the account, the address or
 * the whole server) gets the same answer if it asks again at once. It waits
 * this long instead, doubling each time up to the cap.
 */
const CROWDED_BACKOFF_MS = 30_000;
const MAX_CROWDED_BACKOFF_MS = 5 * 60_000;
/** A socket closed for sending too much waits for the limiter to fill up again. */
const RATE_LIMITED_BACKOFF_MS = 5_000;

export class GatewayClient {
  private readonly opts: GatewayOptions;
  private ws: WebSocket | null = null;
  private _status: GatewayStatus = "idle";
  private attempt = 0;
  /** Refusals in a row for being one connection too many, reset by a ready. */
  private crowded = 0;
  private closedByUser = false;
  private heartbeat: ReturnType<typeof setTimeout> | null = null;
  /** When the socket opened and the last heartbeat went, for onTiming. */
  private openedAt = 0;
  private pingedAt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private ackTimer: ReturnType<typeof setTimeout> | null = null;
  private connectTimer: ReturnType<typeof setTimeout> | null = null;
  /** When the handshake under way started, so a frozen tab can tell how long it has really been. */
  private connectingSince = 0;
  /**
   * How many frames the socket has delivered. A heartbeat counts as answered
   * once this moves; comparing times failed when the answer came back within
   * the same millisecond, and a healthy socket was thrown away.
   */
  private framesSeen = 0;
  private queue: string[] = [];
  private ready = false;

  constructor(opts: GatewayOptions) {
    this.opts = opts;
  }

  get status(): GatewayStatus {
    return this._status;
  }

  private setStatus(s: GatewayStatus, info?: { code?: number; reason?: string }): void {
    this._status = s;
    this.opts.onStatus?.(s, { ...info, attempt: this.attempt });
  }

  // The browser's timers are methods of the global object and refuse a
  // detached receiver with "Illegal invocation", so they are called through a
  // wrapper rather than handed out bare.
  private timers() {
    const { setTimeoutImpl, clearTimeoutImpl } = this.opts;
    return {
      set: (fn: () => void, ms: number): ReturnType<typeof setTimeout> =>
        setTimeoutImpl ? setTimeoutImpl(fn, ms) : globalThis.setTimeout(fn, ms),
      clear: (handle: ReturnType<typeof setTimeout>): void => {
        if (clearTimeoutImpl) clearTimeoutImpl(handle);
        else globalThis.clearTimeout(handle);
      },
    };
  }

  connect(): void {
    if (this._status === "connecting" || this._status === "open") return;
    this.closedByUser = false;
    const token = this.opts.token();
    if (!token) {
      this.setStatus("closed", { reason: "no token" });
      return;
    }
    const Impl = this.opts.WebSocketImpl ?? WebSocket;
    this.ready = false;
    this.setStatus("connecting");
    let ws: WebSocket;
    try {
      ws = new Impl(this.opts.url());
    } catch {
      this.scheduleReconnect(1006, "constructor failed");
      return;
    }
    this.ws = ws;
    this.connectingSince = Date.now();
    this.connectTimer = this.timers().set(() => {
      this.connectTimer = null;
      this.abandonHandshake(ws);
    }, CONNECT_TIMEOUT_MS);
    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.clearConnectTimer();
      ws.send(JSON.stringify({ op: "identify", d: { token } }));
      this.openedAt = performance.now();
      this.setStatus("open");
      this.flush();
      this.startHeartbeat();
    };
    ws.onmessage = (ev: MessageEvent) => {
      if (this.ws !== ws) return;
      this.framesSeen++;
      let parsed: unknown;
      try {
        parsed = JSON.parse(typeof ev.data === "string" ? ev.data : "") as unknown;
      } catch {
        return;
      }
      if (!isGatewayFrame(parsed)) return;
      if (parsed.t === "heartbeat_ack" && this.pingedAt) {
        this.opts.onTiming?.("gateway_rtt", performance.now() - this.pingedAt);
        this.pingedAt = 0;
      }
      if (parsed.t === "ready") {
        if (this.openedAt) this.opts.onTiming?.("gateway_connect", performance.now() - this.openedAt);
        this.openedAt = 0;
        this.ready = true;
        this.attempt = 0;
        this.crowded = 0;
        // The long wait allowed for ready is over; the next heartbeat is held
        // to the usual one.
        if (this.ackTimer !== null) {
          this.timers().clear(this.ackTimer);
          this.ackTimer = null;
        }
      }
      this.opts.onEvent(parsed as GatewayEvent);
    };
    ws.onclose = (ev: CloseEvent) => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.clearConnectTimer();
      this.stopHeartbeat();
      this.ready = false;
      if (ev.code === 4001) {
        this.setStatus("closed", { code: ev.code, reason: ev.reason });
        this.opts.onEvent({ t: "session_expired", d: {} });
        return;
      }
      // Banned: the session still works for the ban screen and an appeal, so
      // it is kept, but there is nothing to reconnect to until the ban ends.
      if (ev.code === 4003) {
        this.closedByUser = true;
        this.setStatus("closed", { code: ev.code, reason: ev.reason });
        this.opts.onEvent({ t: "account_banned" });
        return;
      }
      if (this.closedByUser) {
        this.setStatus("closed", { code: ev.code, reason: ev.reason });
        return;
      }
      this.scheduleReconnect(ev.code, ev.reason);
    };
    ws.onerror = () => {
      // onclose follows; nothing to do here
    };
  }

  private clearConnectTimer(): void {
    if (this.connectTimer === null) return;
    this.timers().clear(this.connectTimer);
    this.connectTimer = null;
  }

  /** Gives up on a handshake that never finished and starts a fresh one. */
  private abandonHandshake(ws: WebSocket): void {
    if (this.ws !== ws || this._status !== "connecting") return;
    this.ws = null;
    this.clearConnectTimer();
    this.stopHeartbeat();
    this.ready = false;
    try {
      ws.close(4000, "connect timeout");
    } catch {
      /* already closing */
    }
    this.scheduleReconnect(1006, "connect timeout");
  }

  private scheduleReconnect(code?: number, reason?: string): void {
    this.clearConnectTimer();
    this.setStatus("closed", { code, reason });
    const rnd = this.opts.random ?? Math.random;
    let delay = Math.min(MAX_BACKOFF_MS, 1000 * Math.pow(1.7, this.attempt)) + rnd() * 500;
    if (code === CLOSE_TOO_MANY) {
      delay = Math.min(MAX_CROWDED_BACKOFF_MS, CROWDED_BACKOFF_MS * 2 ** this.crowded) + rnd() * 5000;
      this.crowded += 1;
    } else if (code === CLOSE_RATE_LIMITED) {
      delay = Math.max(delay, RATE_LIMITED_BACKOFF_MS);
    }
    this.attempt += 1;
    const { set, clear } = this.timers();
    if (this.reconnectTimer !== null) clear(this.reconnectTimer);
    this.reconnectTimer = set(() => {
      this.reconnectTimer = null;
      if (!this.closedByUser) this.connect();
    }, delay);
  }

  /**
   * For when the device is back: online again, or the app reopened. A socket
   * waiting out its backoff tries again straight away rather than after up to
   * half a minute, and an open one is asked to prove it is still alive.
   */
  wake(): void {
    if (this.closedByUser) return;
    if (this._status === "open") {
      this.ping();
      return;
    }
    // A handshake that never finished is the third state, and neither branch
    // here nor connect() would ever touch it. The clock is read rather than
    // trusted to the timer, because a frozen tab's timers do not run either.
    if (this._status === "connecting") {
      const ws = this.ws;
      if (!ws || Date.now() - this.connectingSince < CONNECT_TIMEOUT_MS) return;
      this.attempt = 0;
      this.abandonHandshake(ws);
      return;
    }
    if (this._status !== "closed" || this.reconnectTimer === null) return;
    this.timers().clear(this.reconnectTimer);
    this.reconnectTimer = null;
    this.attempt = 0;
    this.connect();
  }

  /** Sends a heartbeat and gives up on the socket if nothing comes back in time. */
  private ping(): void {
    const ws = this.ws;
    if (!ws || this._status !== "open") return;
    ws.send(JSON.stringify({ op: "heartbeat" }));
    // only a heartbeat answered with nothing else ahead of it times the network
    this.pingedAt = this.ready ? performance.now() : 0;
    if (this.ackTimer !== null) return;
    const seen = this.framesSeen;
    const limit = this.ready ? ACK_TIMEOUT_MS : READY_ACK_TIMEOUT_MS;
    this.ackTimer = this.timers().set(() => {
      this.ackTimer = null;
      if (this.ws !== ws || this.framesSeen > seen) return;
      // The connection is gone without saying so. Stop listening to it; its
      // close event, if one ever comes, is ignored like any stale socket's.
      this.ws = null;
      this.stopHeartbeat();
      this.ready = false;
      try {
        ws.close(4000, "heartbeat timeout");
      } catch {
        /* already closing */
      }
      this.scheduleReconnect(1006, "heartbeat timeout");
    }, limit);
  }

  /**
   * Drops the socket and connects again at once, for a fresh ready: unlocking
   * the app needs everything it put away, and ready is what brings it. A call
   * rides this the way it rides any change of network.
   */
  restart(): void {
    const ws = this.ws;
    const { clear } = this.timers();
    if (this.reconnectTimer !== null) {
      clear(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearConnectTimer();
    this.stopHeartbeat();
    this.ws = null;
    this.ready = false;
    this.attempt = 0;
    this.closedByUser = false;
    if (ws) {
      try {
        ws.close(4000, "restart");
      } catch {
        /* already closing */
      }
    }
    this._status = "idle";
    this.connect();
  }

  close(): void {
    this.closedByUser = true;
    const { clear } = this.timers();
    if (this.reconnectTimer !== null) {
      clear(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearConnectTimer();
    this.stopHeartbeat();
    const ws = this.ws;
    this.ws = null;
    this.ready = false;
    // presence, activity and voice frames belong to the session that queued
    // them; a later connect() flushes whatever is here in its onopen
    this.queue = [];
    if (ws) ws.close(1000, "client closed");
    this.setStatus("closed", { code: 1000, reason: "client closed" });
  }

  send(op: string, d?: unknown): void {
    const frame = JSON.stringify(d === undefined ? { op } : { op, d });
    if (this.ws && this._status === "open" && this.ws.readyState === 1) {
      this.ws.send(frame);
      return;
    }
    // where a caret is, like typing, is only worth saying now
    if (op === "typing" || op === "note_cursor") return;
    this.queue.push(frame);
    if (this.queue.length > MAX_QUEUE) this.queue.shift();
  }

  sendTyping(channelId: string): void {
    this.send("typing", { channel_id: channelId });
  }

  /** Ends our typing indicator for everyone else immediately. */
  sendTypingStop(channelId: string): void {
    this.send("typing", { channel_id: channelId, stop: true });
  }

  sendPresence(p: {
    status?: Presence["status"];
    status_text?: string;
    status_emoji?: string;
    status_expires_at?: number | null;
    clear_status?: boolean;
  }): void {
    this.send("presence", p);
  }

  /** Where this device's caret is in a note, sealed with the note's key (lib/notes/cursors.ts). */
  sendNoteCursor(noteId: string, data: string): void {
    this.send("note_cursor", { id: noteId, data });
  }

  /** Whether the person has left this device alone for a while (see lib/away.ts). */
  sendAway(away: boolean): void {
    this.send("away", { away });
  }

  private flush(): void {
    if (!this.ws || this._status !== "open") return;
    const pending = this.queue;
    this.queue = [];
    for (const f of pending) this.ws.send(f);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    const { set } = this.timers();
    const interval = this.opts.heartbeatMs ?? 25_000;
    const tick = (): void => {
      if (this.ws && this._status === "open") {
        this.heartbeat = set(tick, interval);
        this.ping();
      }
    };
    this.heartbeat = set(tick, interval);
  }

  private stopHeartbeat(): void {
    if (this.heartbeat !== null) {
      this.timers().clear(this.heartbeat);
      this.heartbeat = null;
    }
    if (this.ackTimer !== null) {
      this.timers().clear(this.ackTimer);
      this.ackTimer = null;
    }
  }
}
