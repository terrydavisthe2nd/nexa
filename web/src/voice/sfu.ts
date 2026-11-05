/**
 * Calls through Cloudflare Realtime SFU: one RTCPeerConnection to Cloudflare
 * for the whole call. This device publishes its microphone, camera and screen
 * there and pulls what it needs of everyone else's, through chatd, which
 * decides who may pull what. Every frame is sealed with the conversation's key
 * on the way out and opened on the way in, so Cloudflare forwards media it
 * cannot read, and nobody in the call learns anybody else's address.
 */
import { ApiError } from "@/api/client";
import { voice as voiceApi, type SfuDescription } from "@/api/voice";
import type { VoiceState } from "@/api/types";
import { FrameCrypto, voiceKeysFor, voiceKeysVersion, type VoiceKeys } from "./e2ee";
import { CopyPlanner, LOSS_ONE_COPY } from "./copyPlanner";
import { tunedLocal, tunedRemote, VOICE_STEREO_BITRATE } from "./opus";
import { ExtmapHistory } from "./extmaps";
import { Playback } from "./playback";
import { MAX_SHARE_BPS, MIN_SHARE_BPS } from "./shareQuality";
import type { SignalPayload, TransportEvents, VoiceTransport } from "./transport";
import { acceptCallVideo, capSender, clampBitrate, preferH264, preferVp8, recapSender, sharesInH264, trackSize } from "./videoCap";
import { ShareAdapter, retuneShare } from "./shareAdapt";
import { STALL_CHECK_MS, type StallWatch, watchStall } from "./stallWatch";

import { t as tr_ } from "@/i18n";
import { RecentLoss, type LossTotals } from "./recentLoss";
type PubKind = "audio" | "video" | "screen" | "screen_audio";

/** A track pulled from someone else, arriving on one of this connection's mids. */
interface Pulled {
  userId: string;
  kind: PubKind;
  /** The tag of the published track it is, and the tag that was asked for (the server may have had a newer one). */
  tag: string;
  asked: string;
  mid: string;
  /** The received track, once it has arrived. */
  track: MediaStreamTrack | null;
  /** This receiver's name in the frame crypto worker, new for every receiver. */
  cryptoId?: string;
  /** When the track arrived, whether any of its frames reached the worker yet, and whether one opened. */
  arrivedAt?: number;
  seen?: boolean;
  opened?: boolean;
  /** Its picture is over the size cap, so the worker keeps it from the decoder. */
  tooLarge?: boolean;
  /** When its packets are next counted, and how long the wait after that grows to while none come (a camera that is off). */
  checkAt?: number;
  checkEvery?: number;
}

interface Want {
  userId: string;
  kind: PubKind;
  tag: string;
}

/** A pulled voice's counters, as getStats has them, and when. */
interface VoiceCounts {
  at: number;
  received: number;
  lost: number;
  concealed: number;
  samples: number;
  jbDelay: number;
  jbEmitted: number;
}

/** What was last reported for someone, so only changes are reported. */
interface Shown {
  audio: MediaStream | null;
  video: MediaStream | null;
  screen: MediaStream | null;
  /** Whether the camera and the share shown are over the size cap. */
  videoTooLarge?: boolean;
  screenTooLarge?: boolean;
}

/** The camera is capped here, and the server charges pulls of it at this rate. */
const CAMERA_BPS = 1_500_000;
/**
 * How long a dropped connection gets before ICE is restarted, and how often
 * restarts are tried while it stays down. A blip comes back by itself well
 * within the first; a changed network (Wi-Fi to mobile) needs the restart.
 * The connection itself is kept while the SFU keeps its tracks (see
 * TRACKS_GONE_MS): a network that returns by then picks it straight back up.
 */
const RECOVER_AFTER_MS = 3_000;
const RESTART_EVERY_MS = 8_000;
/** Restarts that were answered yet never reconnected, before the connection is made again from nothing. */
const MAX_ICE_RESTARTS = 4;
/**
 * Cloudflare lets go of tracks after 30 s without their media. A connection
 * down this long (its media stopped a few seconds before it noticed) comes
 * back to tracks that are gone, silent both ways, so it is made again instead.
 */
const TRACKS_GONE_MS = 20_000;
/** How often the key ring is checked for a rotated key, beside being told when one arrives. */
const KEY_CHECK_MS = 4_000;
/**
 * How long a newly arrived key waits before frames are sealed under it. Every
 * other device in the call learns it through its own grant, a moment earlier
 * or later, and drops whatever it cannot open yet: switching at once cut a
 * second or more out of whoever was talking on every membership change. The
 * key it replaces is kept, and new frames under either open everywhere.
 */
const SEND_SWITCH_MS = 3_000;
/** A publisher whose track has been refused this many times in a row is waited for at the ordinary pace, not the quick one. */
const STUCK_PULLS = 8;
/** How long a request to chatd may take before the step gives up on it. */
const REQUEST_TIMEOUT_MS = 15_000;
/** Retries wait this long, doubling with each failure in a row up to the most. */
const RETRY_BASE_MS = 1_000;
/**
 * A pull the SFU could not serve yet waits this long first. Cloudflare refuses
 * a track whose publisher is still connecting (not_found_track_error), which
 * is what someone who joined a moment ago looks like.
 */
const PULL_RETRY_BASE_MS = 400;
/** In a call a long wait is a long silence: retries never wait more than this. */
const RETRY_MAX_MS = 8_000;
/** And a refused pull is someone waiting to be heard at all, so it waits far less. */
const PULL_RETRY_MAX_MS = 1_600;
/** A failure this long after the last one starts the wait from the beginning again. */
const FAILURES_FORGOTTEN_MS = 60_000;
/**
 * A pulled track that has received this many packets without one frame
 * reaching the decryption worker is playing sealed frames as they are, which
 * Chromium can end up doing when packets beat the receiver's transform. Such
 * a track is let go and pulled again.
 */
const BYPASS_PACKETS = 25;
/** How long a new track gets before its packets are counted against it. */
const BYPASS_GRACE_MS = 1_500;
/** Pulling the same track again stops after this many tries; it stays silent. */
const MAX_REPULLS = 3;
/** A track with too few packets to judge is counted again after this long, doubling up to the most. */
const BYPASS_RECHECK_MS = 1_000;
const BYPASS_RECHECK_MAX_MS = { audio: 8_000, video: 20_000 };
/** How often each pulled voice's losses are measured, and the most often a listener tells the speaker. */
const LOSS_CHECK_MS = 2_000;
const LOSS_REPORT_MS = 4_000;
/**
 * Losses are told when they last: over the checks kept (the last six seconds,
 * some 300 packets), so the odd loss on a good link does not turn copies on.
 * Heavy losses are told from one check (two seconds, some 100 packets).
 */
const LOSS_CHECKS_KEPT = 3;
const LOSS_SPAN_MIN_PACKETS = 150;
const LOSS_SPAN_MIN_MISSED = 6;
const LOSS_HEAVY = 0.08;
const LOSS_HEAVY_MIN_PACKETS = 40;
const LOSS_HEAVY_MIN_MISSED = 4;
/** This device's own voice is measured once it sent this many packets since the last check. */
const UPLINK_MIN_PACKETS = 40;
/** A pulled voice's first seconds are not counted: packets lost while a track starts say nothing about the link. */
const LOSS_GRACE_MS = 4_000;
/** After the server pauses video, this device leaves it alone this long before asking again. */
const VIDEO_PAUSE_MS = 10 * 60_000;
/**
 * How long the call's pictures stay pulled after they leave the screen: a
 * look at another channel, or a switch to another window and back, finds them
 * still there, rather than waiting on a pull and a keyframe.
 */
const VIDEO_HIDDEN_GRACE_MS = 15_000;



/** Thrown by a step whose connection was replaced or closed while it waited. */
class Stale extends Error {}

/** A pull the server could not answer; nothing changed here, so asking again later is enough. */
class SyncLater extends Error {}

const media = (kind: PubKind): "audio" | "video" => (kind === "audio" || kind === "screen_audio" ? "audio" : "video");
const slot = (userId: string, kind: PubKind) => `${userId}:${kind}`;
/**
 * A signal that aborts after the request timeout. AbortSignal.timeout is
 * missing from Safari before 16, where the call's encryption still works, so
 * every step there threw and the call sat on "Connecting…" for good.
 */
const timeout = (): AbortSignal => {
  if (typeof AbortSignal.timeout === "function") return AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  const c = new AbortController();
  setTimeout(() => c.abort(new DOMException("The request timed out.", "TimeoutError")), REQUEST_TIMEOUT_MS);
  return c.signal;
};

function description(d: RTCSessionDescription | null): SfuDescription {
  return { type: d!.type as "offer" | "answer", sdp: d!.sdp };
}

/** The stream already carrying exactly these tracks, or a new one for them; null for none. */
function streamOf(current: MediaStream | null, tracks: MediaStreamTrack[]): MediaStream | null {
  if (tracks.length === 0) return null;
  const have = current?.getTracks() ?? [];
  if (current && have.length === tracks.length && tracks.every((t) => have.includes(t))) return current;
  return new MediaStream(tracks);
}

export class SfuTransport implements VoiceTransport {
  /** Loss is measured between readings, not over the whole call. */
  private recentLoss = new RecentLoss();
  private pc: RTCPeerConnection;
  /** The header extension ids this connection gave each mid. */
  private extmaps = new ExtmapHistory();
  private readonly crypto = new FrameCrypto();
  private readonly playback: Playback;
  private sessionId: string | null = null;
  /** Bumped whenever the connection is thrown away; queued steps of an older one do not run. */
  private generation = 0;
  /** Every step that touches the connection's descriptions runs here, one at a time. */
  private ops: Promise<void> = Promise.resolve();
  private closed = false;
  private connecting = false;

  private audioTrack: MediaStreamTrack | null = null;
  private voiceStereo = false;
  private videoTrack: MediaStreamTrack | null = null;
  private screen: MediaStream | null = null;
  private screenBitrate = 6_000_000;
  /** Steers the share's size by what the link carries (shareAdapt.ts), made anew for each share. */
  private shareAdapter = new ShareAdapter(30);
  private senders: Partial<Record<PubKind, RTCRtpTransceiver>> = {};
  private published = new Set<PubKind>();
  /** Whether this device shares in hardware H.264 (videoCap.ts sharesInH264), and whether the share's transceiver went out in it. */
  private canH264 = false;
  private screenH264 = false;
  private republishing = false;
  /** Whether cameras and watched shares are pulled: their pictures are on screen, or left it less than VIDEO_HIDDEN_GRACE_MS ago. */
  private videoShown = true;
  private hiddenTimer: ReturnType<typeof setTimeout> | null = null;

  private roster = new Map<string, VoiceState>();
  private policyGeneration: number | undefined;
  private peers = new Set<string>();
  private watching = new Set<string>();
  /** One pull per person and kind. */
  private pulled = new Map<string, Pulled>();
  private byMid = new Map<string, Pulled>();
  private shown = new Map<string, Shown>();
  private syncQueued = false;

  private keyVersion = "";
  /** The keys last read, the index frames are sealed under now, and the switch to a newer one waiting its turn. */
  private keys: VoiceKeys | null = null;
  private sendIndex: number | null = null;
  private sendSwitch: ReturnType<typeof setTimeout> | null = null;
  private keyTimer: ReturnType<typeof setInterval>;
  /** Whether this connection was ever up, so a drop reads as reconnecting rather than a first connect. */
  private everUp = false;
  /** Pulls refused in a row per person and kind, with the tag they were refused for. */
  private refusedPulls = new Map<string, { tag: string; count: number }>();
  private bypassTimer: ReturnType<typeof setInterval>;
  private receiverSeq = 0;
  /** How often each person's track of each kind has been pulled again for skipping decryption. */
  private repulls = new Map<string, number>();
  /** Refused pulls in a row, kept apart from `failures`: a publisher still connecting is expected, not a fault. */
  private pullRetries = 0;
  private recoverTimer: ReturnType<typeof setTimeout> | null = null;
  private iceRestarts = 0;
  /** When this connection, having been up, went down; null while it is up (or never was). */
  private downSince: number | null = null;
  private wasUp = false;
  private retry: { timer: ReturnType<typeof setTimeout>; rebuild: boolean } | null = null;
  private failures = 0;
  private lastFailure = 0;
  private videoPausedUntil = 0;
  private lossTimer: ReturnType<typeof setInterval>;
  private stallTimer: ReturnType<typeof setInterval>;
  /** Each watched share's StallWatch, by its receiver's crypto id. */
  private stalls = new Map<string, StallWatch>();
  private stallBusy = false;
  /** Per pulled voice (by mid), the counters at the last few checks, oldest first. */
  private lossSeen = new Map<string, VoiceCounts[]>();
  /** When this device last told each speaker about losses. */
  private lossTold = new Map<string, number>();
  /** This device's voice at the last check: packets sent, and lost as the SFU reports. */
  private voiceSent: { ssrc: number; sent: number; lost: number } | null = null;
  /** How many copies this device's voice frames carry, from what listeners report. */
  private copies = new CopyPlanner((depth) => this.crypto.setRedundancy(depth));

  constructor(
    private readonly meId: string,
    private channelId: string,
    private ice: RTCIceServer[],
    private readonly events: TransportEvents,
  ) {
    this.playback = new Playback(events.onSpeaking);
    this.pc = this.newConnection();
    this.crypto.onSeen = (id, opened) => this.onFramesSeen(id, opened);
    this.crypto.onSize = (id, over) => this.onFrameSize(id, over);
    this.refreshKeys();
    this.keyTimer = setInterval(() => this.refreshKeys(), KEY_CHECK_MS);
    this.bypassTimer = setInterval(() => void this.checkBypass(), 1_000);
    this.lossTimer = setInterval(() => {
      void this.checkLoss();
      // what is sent stays within the size cap even when the picture going in grows,
      // and a share in motion is sized to what the link carries
      if (this.senders.video) void recapSender(this.senders.video.sender);
      if (this.senders.screen) void retuneShare(this.senders.screen.sender, this.shareAdapter);
    }, LOSS_CHECK_MS);
    this.stallTimer = setInterval(() => void this.watchStalls(), STALL_CHECK_MS);
    void sharesInH264().then((yes) => {
      this.canH264 = yes;
      this.checkShareCodec();
    });
  }

  // --- the connection ----------------------------------------------------

  private newConnection(): RTCPeerConnection {
    const config: RTCConfiguration & { encodedInsertableStreams?: boolean } = { iceServers: this.ice, bundlePolicy: "max-bundle" };
    if (FrameCrypto.legacy()) config.encodedInsertableStreams = true;
    const pc = new RTCPeerConnection(config);
    pc.ontrack = (e) => this.onTrack(e);
    pc.onconnectionstatechange = () => this.onConnectionState(pc);
    return pc;
  }

  /** Applies this device's own description, noting the extension ids it gives each mid. */
  private async setLocal(pc: RTCPeerConnection, desc: RTCSessionDescriptionInit): Promise<void> {
    await pc.setLocalDescription(desc);
    if (pc === this.pc) this.extmaps.note(pc.localDescription?.sdp);
  }

  /** Applies the SFU's description, without extension ids that would mean something else on a mid than before (extmaps.ts). */
  private async setRemote(pc: RTCPeerConnection, desc: RTCSessionDescriptionInit): Promise<void> {
    await pc.setRemoteDescription(this.extmaps.fit(desc));
    if (pc === this.pc) this.extmaps.note(pc.remoteDescription?.sdp);
  }

  /** Runs a step after the ones before it. Steps of a connection since thrown away are skipped, and what they throw is ignored. */
  private enqueue(step: () => Promise<void>): Promise<void> {
    const generation = this.generation;
    const run = this.ops.then(async () => {
      if (this.closed || generation !== this.generation) return;
      try {
        await step();
      } catch (err) {
        if (this.closed || generation !== this.generation || err instanceof Stale) return;
        this.fail(err);
      }
    });
    this.ops = run;
    return run;
  }

  /** Stops a step that waited while its connection was replaced or closed. */
  private live(pc: RTCPeerConnection): void {
    if (this.closed || pc !== this.pc) throw new Stale();
  }

  private fail(err: unknown): void {
    if (err instanceof ApiError && err.code === "video_paused") {
      this.pauseVideo(err.message);
      return;
    }
    console.warn("voice: sfu step failed", err);
    // a refused pull changed nothing here; anything else may have left the
    // connection half way through an exchange, so it is made again
    this.retryLater(!(err instanceof SyncLater));
  }

  /**
   * Tries again after a wait that grows while failures keep coming. A rebuild
   * replaces a waiting sync. `expected` marks a pull the SFU simply could not
   * serve yet, which keeps its own count: sharing one made every join in a
   * busy channel stretch the next person's first sound, and genuine reconnects
   * with it.
   */
  private retryLater(rebuild: boolean, baseMs = RETRY_BASE_MS, expected = false): void {
    if (this.closed) return;
    if (this.retry) {
      if (!rebuild || this.retry.rebuild) return;
      clearTimeout(this.retry.timer);
    } else if (expected) {
      this.pullRetries++;
    } else {
      const now = Date.now();
      if (now - this.lastFailure > FAILURES_FORGOTTEN_MS) this.failures = 0;
      this.lastFailure = now;
      this.failures++;
    }
    const tries = expected ? this.pullRetries : this.failures;
    // A publisher refused again and again is not one still connecting: asked
    // for every 1.6 s it cost every listener a request for the rest of the call.
    const stuck = expected && [...this.refusedPulls.values()].some((r) => r.count >= STUCK_PULLS);
    const delay = Math.min(expected && !stuck ? PULL_RETRY_MAX_MS : RETRY_MAX_MS, baseMs * 2 ** Math.max(0, tries - 1));
    const timer = setTimeout(() => {
      this.retry = null;
      if (rebuild) this.rebuild();
      else this.scheduleSync();
    }, delay);
    this.retry = { timer, rebuild };
  }

  private refreshKeys(): void {
    if ((voiceKeysVersion(this.channelId) ?? `none:${this.channelId}`) === this.keyVersion) return;
    // without a key for the channel nothing is sent at all, never in the clear or under another channel's key
    const k = voiceKeysFor(this.channelId) ?? { keys: [], send: 0, version: `none:${this.channelId}` };
    if (k.version === this.keyVersion) return;
    this.keyVersion = k.version;
    this.keys = k;
    const before = this.sendIndex;
    // a newer key is opened with at once, and sealed with a moment later (see SEND_SWITCH_MS)
    if (before !== null && before !== k.send && k.keys.some((x) => x.index === before)) {
      this.crypto.setKeys({ ...k, send: before });
      if (!this.sendSwitch) {
        this.sendSwitch = setTimeout(() => {
          this.sendSwitch = null;
          if (this.closed || !this.keys) return;
          this.sendIndex = this.keys.send;
          this.crypto.setKeys(this.keys);
        }, SEND_SWITCH_MS);
      }
      return;
    }
    if (this.sendSwitch) clearTimeout(this.sendSwitch);
    this.sendSwitch = null;
    this.sendIndex = k.keys.length ? k.send : null;
    this.crypto.setKeys(k);
  }

  /** The server acknowledged this device in the call: open the session if there is none. */
  joined(state?: VoiceState): void {
    if (state) {
      this.roster.set(this.meId, state);
      this.policyGeneration = state.sfu_generation ?? 0;
    }
    this.connect();
  }

  /** A reconnect ended the server's copy of this call and closed its session; the next acknowledgement opens a new one. */
  reconnected(): void {
    this.reset();
  }

  /**
   * A key for the channel may have arrived. `moveTo` runs while the new room's
   * key is still being fetched, and without this the four-second check is what
   * decides how long nothing this device sends can be opened.
   */
  keysArrived(): void {
    this.refreshKeys();
  }

  /** Fresh relay credentials: the connection's next ICE restart uses them, and so does any connection made after. */
  setIceServers(servers: RTCIceServer[]): void {
    this.ice = servers;
    try {
      this.pc.setConfiguration({ ...this.pc.getConfiguration(), iceServers: servers });
    } catch {
      /* the next connection made takes them */
    }
  }

  /** Moved by a moderator: the old channel's session is gone and the key belongs to the new channel. */
  moveTo(channelId: string): void {
    if (channelId === this.channelId) return;
    this.channelId = channelId;
    // another room's keys: nothing from the old one is kept to seal with
    if (this.sendSwitch) clearTimeout(this.sendSwitch);
    this.sendSwitch = null;
    this.sendIndex = null;
    this.refreshKeys();
    this.roster.clear();
    this.reset();
  }

  private connect(): void {
    if (this.closed || this.sessionId || this.connecting) return;
    this.connecting = true;
    const pc = this.pc;
    void this.enqueue(async () => {
      let session: string;
      try {
        ({ session_id: session } = await voiceApi.sfuSession(timeout()));
      } finally {
        if (pc === this.pc) this.connecting = false;
      }
      this.live(pc);
      this.sessionId = session;
      // A rebuild still waiting from an earlier failure would tear down the
      // session just made, a second or two in: an audible drop for nothing.
      if (this.retry?.rebuild) {
        clearTimeout(this.retry.timer);
        this.retry = null;
      }
      await this.publishNow(this.wantedKinds());
      this.scheduleSync();
    });
  }

  /** Throws the connection away and makes everything again: a new session, the same media, the same pulls. */
  private rebuild(): void {
    this.reset();
    this.connect();
  }

  /** A fresh connection with no session, nothing published and nothing pulled. */
  private reset(): void {
    if (this.closed) return;
    this.generation++;
    this.ops = Promise.resolve();
    if (this.recoverTimer) clearTimeout(this.recoverTimer);
    this.recoverTimer = null;
    if (this.retry) clearTimeout(this.retry.timer);
    this.retry = null;
    this.iceRestarts = 0;
    this.downSince = null;
    this.wasUp = false;
    const old = this.pc;
    old.ontrack = null;
    old.onconnectionstatechange = null;
    old.close();
    this.pc = this.newConnection();
    this.extmaps = new ExtmapHistory();
    this.sessionId = null;
    this.connecting = false;
    this.senders = {};
    this.published.clear();
    this.pulled.clear();
    this.byMid.clear();
    // All of this belongs to the connection that has just gone. A new one
    // hands out mids from 0 again, so keeping any of it means the fresh
    // receiver on a recycled mid is judged by the dead one's history: three
    // repulls already spent, losses it never had.
    this.repulls.clear();
    this.lossSeen.clear();
    this.lossTold.clear();
    this.pullRetries = 0;
    this.refusedPulls.clear();
    this.present();
    for (const id of this.peers) this.events.onPeerState(id, this.pc.connectionState);
    this.events.onConnection?.(this.everUp ? "reconnecting" : "connecting");
  }

  private onConnectionState(pc: RTCPeerConnection): void {
    if (pc !== this.pc || this.closed) return;
    const state = pc.connectionState;
    for (const id of this.peers) this.events.onPeerState(id, state);
    if (state === "connected") this.everUp = true;
    this.events.onConnection?.(state === "connected" ? "connected" : this.everUp ? "reconnecting" : "connecting");
    if (state === "connected") {
      if (this.recoverTimer) clearTimeout(this.recoverTimer);
      this.recoverTimer = null;
      this.iceRestarts = 0;
      this.failures = 0;
      const downFor = this.downSince === null ? 0 : Date.now() - this.downSince;
      this.downSince = null;
      this.wasUp = true;
      if (downFor >= TRACKS_GONE_MS) this.retryLater(true, 0);
    } else if (state === "disconnected" || state === "failed") {
      if (this.wasUp && this.downSince === null) this.downSince = Date.now();
      if (!this.recoverTimer) this.recoverTimer = setTimeout(() => this.recover(pc), state === "failed" ? 0 : RECOVER_AFTER_MS);
    }
  }

  /** The network looks back (the page came online, the gateway reconnected): a connection still down tries again now. */
  nudge(): void {
    const pc = this.pc;
    if (this.closed) return;
    if (this.retry?.rebuild) {
      clearTimeout(this.retry.timer);
      this.retry = null;
      this.failures = 0;
      this.rebuild();
      return;
    }
    if (pc.connectionState !== "disconnected" && pc.connectionState !== "failed") return;
    if (this.recoverTimer) clearTimeout(this.recoverTimer);
    this.recover(pc);
  }

  /** The SFU session this device's call holds, for resuming it on a new gateway connection. */
  currentSession(): string | null {
    return this.sessionId;
  }

  /**
   * A connection that stayed down: restart ICE, keeping the session, its
   * tracks and everyone's pulls, while the connection stays open to come back
   * by itself. Only restarts that were answered and still did not reconnect,
   * or a session the SFU no longer has, make it again from nothing.
   */
  private recover(pc: RTCPeerConnection): void {
    this.recoverTimer = null;
    if (pc !== this.pc || this.closed) return;
    const state = pc.connectionState;
    if (state !== "disconnected" && state !== "failed") return;
    const anchor = (["audio", "video", "screen", "screen_audio"] as const).find((k) => this.published.has(k) && this.senders[k]?.mid);
    if (this.iceRestarts >= MAX_ICE_RESTARTS || !this.sessionId || !anchor) {
      this.retryLater(true, 0);
      return;
    }
    this.recoverTimer = setTimeout(() => this.recover(pc), RESTART_EVERY_MS);
    const session = this.sessionId;
    const mid = this.senders[anchor]!.mid!;
    void this.enqueue(async () => {
      try {
        pc.restartIce();
        const offer = await pc.createOffer({ iceRestart: true });
        this.live(pc);
        await this.setLocal(pc, this.tuneLocal(offer));
        this.live(pc);
        const res = await voiceApi.sfuRestart({ session_id: session, offer: description(pc.localDescription), mid, kind: anchor }, timeout());
        this.live(pc);
        if (!res.answer) throw new Error("no answer to the ICE restart");
        await this.setRemote(pc, this.tuneRemote(res.answer));
        this.iceRestarts++;
      } catch (err) {
        if (err instanceof Stale) throw err;
        if (pc.signalingState === "have-local-offer") await pc.setLocalDescription({ type: "rollback" }).catch(() => {});
        // a session the SFU let go of cannot come back; anything else (the network still down) waits for the next try
        if (err instanceof ApiError && (err.code === "sfu_session_gone" || err.code === "not_in_voice")) throw err;
        console.warn("voice: ICE restart did not go through", err);
      }
    });
  }

  // --- publishing --------------------------------------------------------

  private trackFor(kind: PubKind): MediaStreamTrack | null {
    switch (kind) {
      case "audio":
        return this.audioTrack;
      case "video":
        return this.videoTrack;
      case "screen":
        return this.screen?.getVideoTracks()[0] ?? null;
      case "screen_audio":
        return this.screen?.getAudioTracks()[0] ?? null;
    }
  }

  private videoPaused(): boolean {
    return Date.now() < this.videoPausedUntil;
  }

  /** What has something to send just now; video is left out while the server has it paused. */
  private mayPublish(kind: PubKind): boolean {
    const me = this.roster.get(this.meId);
    return kind !== "audio" && kind !== "screen_audio" || !(me?.server_mute || me?.server_deaf || me?.suppress);
  }

  private wantedKinds(): PubKind[] {
    const kinds: PubKind[] = [];
    if (this.audioTrack && this.mayPublish("audio")) kinds.push("audio");
    if (!this.videoPaused()) {
      if (this.videoTrack) kinds.push("video");
      if (this.screen?.getVideoTracks()[0]) {
        kinds.push("screen");
        if (this.screen.getAudioTracks()[0]) kinds.push("screen_audio");
      }
    }
    return kinds;
  }

  private publish(kinds: PubKind[]): void {
    if (!this.sessionId) return; // connecting publishes whatever is there by then
    void this.enqueue(async () => {
      await this.publishNow(kinds);
      this.scheduleSync();
    });
  }

  /** Adds the missing transceivers and publishes them in one offer. */
  private async publishNow(kinds: PubKind[]): Promise<void> {
    const session = this.sessionId;
    const pc = this.pc;
    const fresh = kinds.filter((k) => !this.published.has(k) && this.mayPublish(k));
    if (!session || fresh.length === 0) return;
    const added: PubKind[] = [];
    for (const kind of fresh) {
      if (this.senders[kind]) continue;
      const tr = pc.addTransceiver(this.trackFor(kind) ?? media(kind), { direction: "sendonly" });
      if (kind === "screen") {
        const h264 = this.wantsH264();
        this.screenH264 = h264 && preferH264(tr);
        // a browser that turned the list down stays on VP8 for this call, rather than publishing again and again
        if (h264 && !this.screenH264) this.canH264 = false;
        if (!this.screenH264) preferVp8(tr);
      } else if (media(kind) === "video") preferVp8(tr);
      // the microphone's frames carry copies of the ones before when listeners lose packets
      this.crypto.protectSender(tr.sender, media(kind), kind === "audio");
      if (kind === "video") capSender(tr.sender, CAMERA_BPS);
      if (kind === "screen") capSender(tr.sender, this.screenBitrate, undefined, "high");
      this.senders[kind] = tr;
      added.push(kind);
    }
    let failed = 0;
    try {
      const offer = await pc.createOffer();
      this.live(pc);
      await this.setLocal(pc, this.tuneLocal(offer));
      this.live(pc);
      const res = await voiceApi.sfuPublish(
        {
          session_id: session,
          offer: description(pc.localDescription),
          tracks: fresh.map((kind) => ({ mid: this.senders[kind]!.mid!, kind, ...(kind === "screen" ? { bitrate: this.screenBitrate } : kind === "audio" && this.voiceStereo ? { bitrate: VOICE_STEREO_BITRATE } : {}) })),
        },
        timeout(),
      );
      this.live(pc);
      if (!res.answer) throw new Error("no answer to the offer");
      await this.setRemote(pc, this.tuneRemote(res.answer));
      this.live(pc);
      failed = res.failed;
    } catch (err) {
      if (pc !== this.pc || this.closed) throw new Stale();
      // take the offer back, and the transceivers it added, so the next exchange starts clean
      if (pc.signalingState === "have-local-offer") await pc.setLocalDescription({ type: "rollback" }).catch(() => {});
      this.live(pc);
      for (const kind of added) {
        this.senders[kind]?.stop();
        delete this.senders[kind];
      }
      throw err;
    }
    for (const kind of fresh) this.published.add(kind);
    // the SFU turned a track down: the session is made again rather than left without it
    if (failed > 0) throw new Error(`the call service refused ${failed} track(s)`);
  }

  /**
   * Whether the screen goes out in H.264: this device's hardware encoder can
   * make it, and everyone else in the call said they can watch it (a browser
   * without H.264, or an older app, cannot, and gets VP8).
   */
  private wantsH264(): boolean {
    if (!this.canH264) return false;
    for (const [userId, st] of this.roster) if (userId !== this.meId && !st.codecs?.includes("h264")) return false;
    return true;
  }

  /** Publishes the share again when the codec it went out in is no longer the one the call should get. */
  private checkShareCodec(): void {
    if (this.closed || !this.senders.screen || !this.screen?.getVideoTracks()[0]) return;
    if (this.wantsH264() !== this.screenH264) this.republishScreen();
  }

  /**
   * The share published again, in the codec the call can watch now. One at a
   * time: it reads the call as it is when its turn comes, and looks again
   * once it is done, so states arriving together cost one change, not one each.
   */
  private republishScreen(): void {
    if (!this.sessionId || this.republishing) return;
    this.republishing = true;
    void this.enqueue(async () => {
      await this.unpublishNow("screen");
      if (this.screen?.getVideoTracks()[0]) await this.publishNow(["screen"]);
      this.scheduleSync();
    }).finally(() => {
      this.republishing = false;
      this.checkShareCodec();
    });
  }

  /**
   * The mids of every screen share's sound on this connection, this device's
   * own and those it pulls: their Opus is tuned for music in stereo rather
   * than for a voice (opus.ts), in each exchange, as the voice tuning is.
   */
  private streamMids(): Set<string> {
    const mids = new Set<string>();
    const own = this.senders.screen_audio?.mid;
    if (own) mids.add(own);
    for (const p of this.pulled.values()) if (p.kind === "screen_audio") mids.add(p.mid);
    return mids;
  }

  /**
   * Every voice this device pulls plays in stereo whenever its sender sends
   * it, and this device's own says so while it does.
   */
  private listenMids(): Set<string> {
    const mids = new Set<string>();
    for (const p of this.pulled.values()) if (p.kind === "audio") mids.add(p.mid);
    const own = this.senders.audio?.mid;
    if (own && this.voiceStereo) mids.add(own);
    return mids;
  }

  private tuneLocal(desc: RTCSessionDescriptionInit): RTCSessionDescriptionInit {
    return tunedLocal(desc, this.streamMids(), this.listenMids());
  }

  private tuneRemote<D extends { type?: RTCSdpType | string; sdp?: string | null }>(desc: D): D {
    return tunedRemote(desc, this.streamMids(), this.voiceStereo);
  }

  /** Takes a published track back from the SFU; publishing it again gives it a new name everyone pulls afresh. */
  private async unpublishNow(kind: PubKind): Promise<void> {
    const session = this.sessionId;
    const tr = this.senders[kind];
    if (!session || !tr) return;
    const pc = this.pc;
    delete this.senders[kind];
    this.published.delete(kind);
    const mid = tr.mid;
    tr.stop();
    if (!mid) return;
    const offer = await pc.createOffer();
    this.live(pc);
    await this.setLocal(pc, this.tuneLocal(offer));
    this.live(pc);
    const res = await voiceApi.sfuClose({ session_id: session, mids: [mid], offer: description(pc.localDescription) }, timeout());
    this.live(pc);
    if (!res.answer) throw new Error("no answer to closing a track");
    await this.setRemote(pc, this.tuneRemote(res.answer));
    this.live(pc);
  }

  /**
   * An encoder takes its channels from the description, so a voice that changes
   * is published again, as a share changing codec is: everyone pulls it afresh.
   */
  setVoiceStereo(on: boolean): void {
    if (on === this.voiceStereo) return;
    this.voiceStereo = on;
    if (!this.sessionId || !this.published.has("audio")) return;
    void this.enqueue(async () => {
      await this.unpublishNow("audio");
      if (this.trackFor("audio") && this.mayPublish("audio")) await this.publishNow(["audio"]);
      this.scheduleSync();
    });
  }

  setLocalAudio(track: MediaStreamTrack | null): void {
    this.audioTrack = track;
    const tr = this.senders.audio;
    if (tr) void tr.sender.replaceTrack(this.trackFor("audio")).catch(() => {});
    else if (track) this.publish(["audio"]);
  }

  setLocalVideo(track: MediaStreamTrack | null): void {
    this.videoTrack = track;
    const tr = this.senders.video;
    if (!track && tr) { void this.enqueue(async () => { await this.unpublishNow("video"); if (this.videoTrack) await this.publishNow(["video"]); }); return; }
    if (tr) void tr.sender.replaceTrack(track).catch(() => {});
    else if (track) this.publish(["video"]);
  }

  setLocalScreen(stream: MediaStream | null, bitrate = 6_000_000, fps = 30, smooth = fps >= 50): void {
    this.screen = stream;
    // the server charges pulls of the share at this rate and holds it to the same bounds
    this.screenBitrate = clampBitrate(bitrate, MIN_SHARE_BPS, MAX_SHARE_BPS, 6_000_000);
    if (stream) this.shareAdapter = new ShareAdapter(fps, smooth);
    if (!stream) {
      void this.enqueue(async () => { await this.unpublishNow("screen_audio"); await this.unpublishNow("screen"); await this.publishNow(this.wantedKinds()); });
      return;
    }
    // a share starting on a transceiver of the other codec is published again in the one the call can watch
    if (this.senders.screen && stream?.getVideoTracks()[0] && this.wantsH264() !== this.screenH264) {
      if (this.senders.screen_audio) void this.senders.screen_audio.sender.replaceTrack(this.trackFor("screen_audio")).catch(() => {});
      else if (this.trackFor("screen_audio")) this.publish(["screen_audio"]);
      this.republishScreen();
      return;
    }
    const missing: PubKind[] = [];
    for (const kind of ["screen", "screen_audio"] as const) {
      const tr = this.senders[kind];
      const track = this.trackFor(kind);
      if (tr) {
        void tr.sender.replaceTrack(track).catch(() => {});
        // a new share starts at its own full size, whatever the last one had been stepped down to
        if (kind === "screen" && track) capSender(tr.sender, this.screenBitrate, trackSize(track) ?? { width: 1, height: 1 }, "high");
      } else if (track) missing.push(kind);
    }
    if (missing.length) this.publish(missing);
  }

  /** The server paused video near the free allowance: stop asking for it for a while, and send what went with it. */
  private pauseVideo(message: string): void {
    this.videoPausedUntil = Date.now() + VIDEO_PAUSE_MS;
    this.events.onError?.("video_paused", message);
    this.publish(this.wantedKinds());
    this.scheduleSync();
  }

  // --- pulling -----------------------------------------------------------

  addPeer(userId: string): void {
    if (userId === this.meId || this.peers.has(userId)) return;
    this.peers.add(userId);
    this.events.onPeerState(userId, this.pc.connectionState);
    this.scheduleSync();
  }

  removePeer(userId: string): void {
    if (!this.peers.delete(userId)) return;
    this.lossTold.delete(userId);
    // silent at once; the next exchange tells the SFU to stop sending their tracks
    this.present();
    this.scheduleSync();
  }

  peerIds(): string[] {
    return [...this.peers];
  }

  /** A listener says how much of this device's voice it loses, conceals and waits for; copies go out while that calls for them. */
  handleSignal(from: string, payload: SignalPayload): void {
    const num = (v: unknown, max: number) => (typeof v === "number" && Number.isFinite(v) ? Math.max(0, Math.min(max, v)) : null);
    const loss = num(payload?.voice_loss, 1);
    if (loss === null) return;
    const span = num(payload.voice_span, 60_000);
    this.copies.report(from, { loss, conceal: num(payload.voice_conceal, 1) ?? loss, delay: num(payload.voice_delay, 60_000) ?? 0, ...(span === null ? {} : { span }) }, Date.now());
  }

  /** Measures what each pulled voice loses, and tells its speaker when it is enough to matter. */
  private async checkLoss(): Promise<void> {
    if (this.closed || !this.sessionId) return;
    const pc = this.pc;
    const report = await pc.getStats().catch(() => null);
    if (!report || pc !== this.pc || this.closed) return;
    const now = Date.now();
    let mine: { ssrc: number; sent: number } | null = null;
    const lostBySsrc = new Map<number, number>();
    for (const r of report.values() as IterableIterator<RTCStats & { kind?: string; mid?: string; ssrc?: number; packetsSent?: number; packetsLost?: number }>) {
      if (r.type === "candidate-pair" && (r as RTCIceCandidatePairStats).nominated && (r as RTCIceCandidatePairStats).state === "succeeded") {
        const rtt = (r as RTCIceCandidatePairStats).currentRoundTripTime;
        if (typeof rtt === "number") this.copies.roundTrip(rtt * 1000, now);
      }
      if (r.type === "outbound-rtp" && r.kind === "audio" && r.mid !== undefined && r.mid === this.senders.audio?.mid && r.ssrc !== undefined) mine = { ssrc: r.ssrc, sent: r.packetsSent ?? 0 };
      if (r.type === "remote-inbound-rtp" && r.kind === "audio" && r.ssrc !== undefined && typeof r.packetsLost === "number") lostBySsrc.set(r.ssrc, r.packetsLost);
    }
    // what the SFU lost of this device's voice since the last check, from the receiver reports it sends every second
    const lost = mine ? lostBySsrc.get(mine.ssrc) : undefined;
    if (mine && lost !== undefined) {
      const was = this.voiceSent;
      this.voiceSent = { ssrc: mine.ssrc, sent: mine.sent, lost };
      if (was && was.ssrc === mine.ssrc && mine.sent - was.sent >= UPLINK_MIN_PACKETS) this.copies.uplink(Math.max(0, Math.min(1, (lost - was.lost) / (mine.sent - was.sent))), now);
    }
    for (const r of report.values() as IterableIterator<RTCStats>) {
      if (r.type !== "inbound-rtp" || (r as RTCInboundRtpStreamStats).kind !== "audio") continue;
      const s = r as RTCInboundRtpStreamStats & { mid?: string; concealedSamples?: number; totalSamplesReceived?: number; jitterBufferDelay?: number; jitterBufferEmittedCount?: number };
      const p = s.mid ? this.byMid.get(s.mid) : undefined;
      if (!p || p.kind !== "audio" || !s.mid) continue;
      const counts: VoiceCounts = {
        at: now,
        received: s.packetsReceived ?? 0,
        lost: Math.max(0, s.packetsLost ?? 0),
        concealed: s.concealedSamples ?? 0,
        samples: s.totalSamplesReceived ?? 0,
        jbDelay: s.jitterBufferDelay ?? 0,
        jbEmitted: s.jitterBufferEmittedCount ?? 0,
      };
      const seen = this.lossSeen.get(s.mid) ?? [];
      const last = seen[seen.length - 1];
      // counters that went back are a new stream: what came before says nothing about it
      const kept = last && counts.received >= last.received ? seen : [];
      this.lossSeen.set(s.mid, [...kept, counts].slice(-LOSS_CHECKS_KEPT));
      const told = this.lossTold.get(p.userId) ?? 0;
      if (kept.length === 0 || p.arrivedAt === undefined || now - p.arrivedAt < LOSS_GRACE_MS || now - told < LOSS_REPORT_MS) continue;
      const over = (from: VoiceCounts) => {
        const received = counts.received - from.received;
        // packets that never came; ones that came too late to play are jitter, and a copy would come later still
        const missed = Math.max(0, counts.lost - from.lost);
        return { from, total: received + missed, missed, loss: received + missed > 0 ? missed / (received + missed) : 0 };
      };
      const recent = over(kept[kept.length - 1]!);
      // since this speaker was last told, so reports do not overlap and each says what the stretch after the one before was like
      const span = over(kept.find((c) => c.at >= told) ?? kept[0]!);
      const tell =
        recent.total >= LOSS_HEAVY_MIN_PACKETS && recent.missed >= LOSS_HEAVY_MIN_MISSED && recent.loss >= LOSS_HEAVY
          ? recent
          : span.total >= LOSS_SPAN_MIN_PACKETS && span.missed >= LOSS_SPAN_MIN_MISSED && span.loss >= LOSS_ONE_COPY
            ? span
            : null;
      if (!tell) continue;
      this.lossTold.set(p.userId, now);
      const samples = counts.samples - tell.from.samples;
      const emitted = counts.jbEmitted - tell.from.jbEmitted;
      const conceal = samples > 0 ? Math.max(0, Math.min(1, (counts.concealed - tell.from.concealed) / samples)) : tell.loss;
      const wait = emitted > 0 ? ((counts.jbDelay - tell.from.jbDelay) / emitted) * 1000 : 0;
      const share = (v: number) => Math.round(v * 1000) / 1000;
      this.events.onSignal(p.userId, { voice_loss: share(tell.loss), voice_conceal: share(conceal), voice_delay: Math.round(wait + (this.copies.rtt() ?? 0)), voice_span: now - tell.from.at });
    }
    this.copies.update(now);
  }

  setRoster(states: VoiceState[]): void {
    this.roster = new Map(states.filter((s) => s.channel_id === this.channelId).map((s) => [s.user_id, s]));
    const policy = this.roster.get(this.meId)?.sfu_generation ?? 0;
    const changed = this.policyGeneration !== undefined && policy !== this.policyGeneration;
    this.policyGeneration = policy;
    if (changed && this.sessionId) this.rebuild();
    // someone who cannot watch H.264 came in, or the last of them left: the share changes codec
    this.checkShareCodec();
    // a camera turned on or off shows at once: its track stays pulled either way
    this.present();
    this.scheduleSync();
  }

  setVideoShown(on: boolean): void {
    if (on) {
      if (this.hiddenTimer) clearTimeout(this.hiddenTimer);
      this.hiddenTimer = null;
      if (this.videoShown) return;
      this.videoShown = true;
      this.scheduleSync();
      return;
    }
    if (!this.videoShown || this.hiddenTimer) return;
    this.hiddenTimer = setTimeout(() => {
      this.hiddenTimer = null;
      this.videoShown = false;
      this.scheduleSync();
    }, VIDEO_HIDDEN_GRACE_MS);
  }

  setWatching(userId: string, on: boolean): void {
    if (on) this.watching.add(userId);
    else this.watching.delete(userId);
    this.playback.setWatching(userId, on);
    this.scheduleSync();
  }

  /**
   * What should be pulled now: everyone's voice and camera (a camera that is
   * off sends nothing and costs nothing, and turning it on shows without
   * another exchange), and the shares being watched.
   */
  private wanted(): Map<string, Want> {
    const out = new Map<string, Want>();
    // pictures come in while the server allows video and someone can see them
    const pictures = !this.videoPaused() && this.videoShown;
    for (const [userId, st] of this.roster) {
      if (userId === this.meId || !this.peers.has(userId)) continue;
      const add = (kind: PubKind) => {
        const tag = st.media?.[kind];
        if (tag) out.set(slot(userId, kind), { userId, kind, tag });
      };
      if (!this.roster.get(this.meId)?.server_deaf && !st.server_mute && !st.server_deaf && !st.suppress) add("audio");
      if (pictures) add("video");
      if (st.streaming && this.watching.has(userId)) {
        if (pictures) add("screen");
        if (!this.roster.get(this.meId)?.server_deaf && !st.server_mute && !st.server_deaf && !st.suppress) add("screen_audio");
      }
    }
    return out;
  }

  private scheduleSync(): void {
    if (this.syncQueued || this.closed) return;
    this.syncQueued = true;
    queueMicrotask(() => {
      this.syncQueued = false;
      void this.enqueue(() => this.syncNow());
    });
  }

  private async syncNow(): Promise<void> {
    if (!this.sessionId) return;
    const want = this.wanted();
    const drop: Pulled[] = [];
    for (const [key, p] of this.pulled) {
      const w = want.get(key);
      // a track published again has a new tag, and is pulled again
      if (!w || (w.tag !== p.tag && w.tag !== p.asked)) drop.push(p);
    }
    const add = [...want.entries()].filter(([key]) => !this.pulled.has(key) || drop.includes(this.pulled.get(key)!)).map(([, w]) => w);
    if (drop.length) await this.unsubscribe(drop);
    if (add.length) await this.subscribe(add);
  }

  private async subscribe(list: Want[]): Promise<void> {
    const session = this.sessionId!;
    const pc = this.pc;
    let res: Awaited<ReturnType<typeof voiceApi.sfuSubscribe>>;
    try {
      res = await voiceApi.sfuSubscribe({ session_id: session, tracks: list.map((w) => ({ user_id: w.userId, kind: w.kind })) }, timeout());
    } catch (err) {
      this.live(pc);
      if (err instanceof ApiError && (err.code === "sfu_session_gone" || err.code === "not_in_voice")) throw err;
      throw new SyncLater(err instanceof Error ? err.message : String(err));
    }
    this.live(pc);
    const asked = new Map(list.map((w) => [slot(w.userId, w.kind), w.tag]));
    let paused = false;
    let refused = false;
    for (const t of res.tracks) {
      const key = slot(t.user_id, t.kind as PubKind);
      if (t.error === "video_paused") paused = true;
      else if (t.error || !t.mid) {
        refused = true;
        // counted per track, and from nothing again when its publisher publishes it anew
        const tag = asked.get(key) ?? "";
        const prev = this.refusedPulls.get(key);
        this.refusedPulls.set(key, { tag, count: prev && prev.tag === tag ? prev.count + 1 : 1 });
      }
      if (!t.mid || t.error) continue;
      this.refusedPulls.delete(key);
      const kind = t.kind as PubKind;
      const p: Pulled = { userId: t.user_id, kind, tag: t.tag ?? "", asked: asked.get(slot(t.user_id, kind)) ?? "", mid: t.mid, track: null };
      this.pulled.set(slot(p.userId, kind), p);
      this.byMid.set(p.mid, p);
    }
    if (res.offer) {
      await this.setRemote(pc, this.tuneRemote(res.offer));
      this.live(pc);
      const answer = await pc.createAnswer();
      this.live(pc);
      await this.setLocal(pc, this.tuneLocal(answer));
      this.live(pc);
      await voiceApi.sfuRenegotiate({ session_id: session, description: description(pc.localDescription) }, timeout());
      this.live(pc);
    }
    if (paused && !this.videoPaused()) this.pauseVideo(tr_("sfu.videoIsPausedUntilNextMonth"));
    // a track not there yet (its publisher still connecting) is asked for again shortly; what is no longer wanted by then is not
    if (refused) this.retryLater(false, PULL_RETRY_BASE_MS, true);
    else this.pullRetries = 0;
  }

  private async unsubscribe(list: Pulled[]): Promise<void> {
    const session = this.sessionId!;
    const pc = this.pc;
    for (const p of list) {
      if (this.pulled.get(slot(p.userId, p.kind)) === p) this.pulled.delete(slot(p.userId, p.kind));
      this.byMid.delete(p.mid);
      // the mid is handed back and can be used again, so nothing keyed on it may outlive this track
      this.lossSeen.delete(p.mid);
      pc.getTransceivers().find((t) => t.mid === p.mid)?.stop();
    }
    this.present();
    const offer = await pc.createOffer();
    this.live(pc);
    await this.setLocal(pc, this.tuneLocal(offer));
    this.live(pc);
    const res = await voiceApi.sfuClose({ session_id: session, mids: list.map((p) => p.mid), offer: description(pc.localDescription) }, timeout());
    this.live(pc);
    if (!res.answer) throw new Error("no answer to closing tracks");
    await this.setRemote(pc, this.tuneRemote(res.answer));
    this.live(pc);
  }

  private onTrack(e: RTCTrackEvent): void {
    const mid = e.transceiver.mid;
    const p = mid ? this.byMid.get(mid) : undefined;
    const cryptoId = `${mid ?? "-"}#${++this.receiverSeq}`;
    const kind = p ? media(p.kind) : e.track.kind === "video" ? "video" : "audio";
    // The answer to this offer takes VP8 or H.264 (a phone's screen share)
    // and nothing else: the worker reads both to keep oversized pictures from
    // the decoder, so a publisher cannot get another codec past it.
    if (kind === "video") acceptCallVideo(e.transceiver);
    try {
      // Every receiver gets its transform at once, even one this call does not know: Chromium lets
      // frames skip a receiver that has none for good, and the mid may be handed out again later.
      this.crypto.protectReceiver(e.receiver, kind, cryptoId);
    } catch (err) {
      // a track that cannot be opened is not played: what arrives is sealed
      console.warn("voice: cannot decrypt a pulled track", err);
      return;
    }
    if (!p) return;
    p.track = e.track;
    p.cryptoId = cryptoId;
    p.arrivedAt = Date.now();
    p.seen = false;
    p.opened = false;
    p.tooLarge = false;
    p.checkAt = 0;
    p.checkEvery = undefined;
    this.present();
  }

  /** The worker saw a pulled track's first frame, or its first opened one: its sound can play. */
  private onFramesSeen(id: string, opened: boolean): void {
    for (const p of this.pulled.values()) {
      if (p.cryptoId !== id) continue;
      p.seen = true;
      if (opened && !p.opened) {
        p.opened = true;
        this.repulls.delete(slot(p.userId, p.kind));
        if (p.kind === "audio") this.playback.setHeld(p.userId, "voice", false);
        if (p.kind === "screen_audio") this.playback.setHeld(p.userId, "screen", false);
      }
      return;
    }
  }

  /** The worker found a pulled picture over the size cap, or back within it: its tile says so. */
  private onFrameSize(id: string, over: boolean): void {
    for (const p of this.pulled.values()) {
      if (p.cryptoId !== id) continue;
      if (!!p.tooLarge === over) return;
      p.tooLarge = over;
      this.present();
      return;
    }
  }

  /** Whether a pulled track's sound is still waiting to be known as opened. */
  private held(userId: string, kind: PubKind): boolean {
    const p = this.pulled.get(slot(userId, kind));
    return !!p && !p.opened;
  }

  /**
   * Finds pulled tracks whose packets arrive but never reach the decryption
   * worker, and pulls them again. Their sound was held silent all along.
   */
  /** Asks for a keyframe for a watched share whose picture is stuck behind a lost frame (stallWatch.ts). */
  private async watchStalls(): Promise<void> {
    if (this.closed || this.stallBusy) return;
    this.stallBusy = true;
    try {
      await this.checkStalls();
    } finally {
      this.stallBusy = false;
    }
  }

  private async checkStalls(): Promise<void> {
    const pc = this.pc;
    // only a share that has played: one still waiting on its keys, or over the size cap, is held on purpose
    const screens = [...this.pulled.values()].filter((p) => p.kind === "screen" && p.track && p.cryptoId && p.opened && !p.tooLarge);
    const now = Date.now();
    const next = new Map<string, StallWatch>();
    for (const p of screens) {
      const receiver = pc.getTransceivers().find((t) => t.mid === p.mid)?.receiver;
      const report = receiver ? await receiver.getStats().catch(() => null) : null;
      if (!report || pc !== this.pc || this.closed) return;
      let packets = 0;
      let frames = 0;
      report.forEach((r) => {
        const s = r as RTCInboundRtpStreamStats;
        if (s.type === "inbound-rtp" && s.kind === "video") {
          packets = s.packetsReceived ?? 0;
          frames = s.framesDecoded ?? 0;
        }
      });
      const id = p.cryptoId!;
      const { watch, ask } = watchStall(this.stalls.get(id) ?? null, packets, frames, now);
      // a picture stuck behind a lost frame: the sender is asked for a keyframe now, not in three seconds
      if (ask) this.crypto.requestKeyFrame(id);
      next.set(id, watch);
    }
    this.stalls = next;
  }

  private async checkBypass(): Promise<void> {
    if (this.closed || !this.sessionId) return;
    const now = Date.now();
    const suspects = [...this.pulled.values()].filter(
      (p) => p.track && p.cryptoId && !p.seen && p.arrivedAt !== undefined && now - p.arrivedAt > BYPASS_GRACE_MS && (this.repulls.get(slot(p.userId, p.kind)) ?? 0) < MAX_REPULLS,
    );
    // stats are only gathered for a track that is due: one that never gets packets waits longer each time
    if (!suspects.some((p) => (p.checkAt ?? 0) <= now)) return;
    const pc = this.pc;
    const report = await pc.getStats().catch(() => null);
    if (!report || pc !== this.pc || this.closed) return;
    const packets = new Map<string, number>();
    report.forEach((r) => {
      const s = r as RTCInboundRtpStreamStats & { mid?: string };
      if (s.type === "inbound-rtp" && s.mid) packets.set(s.mid, (packets.get(s.mid) ?? 0) + (s.packetsReceived ?? 0));
    });
    const skipping = suspects.filter((p) => !p.seen && this.pulled.get(slot(p.userId, p.kind)) === p && (packets.get(p.mid) ?? 0) >= BYPASS_PACKETS);
    for (const p of suspects) {
      if (skipping.includes(p)) continue;
      p.checkEvery = Math.min(BYPASS_RECHECK_MAX_MS[media(p.kind)], (p.checkEvery ?? BYPASS_RECHECK_MS / 2) * 2);
      p.checkAt = now + p.checkEvery;
    }
    if (skipping.length === 0) return;
    const again: Pulled[] = [];
    for (const p of skipping) {
      const key = slot(p.userId, p.kind);
      const tries = this.repulls.get(key) ?? 0;
      if (tries >= MAX_REPULLS) continue;
      this.repulls.set(key, tries + 1);
      console.warn(`voice: a pulled ${p.kind} track played without decryption (${packets.get(p.mid)} packets); pulling it again`);
      again.push(p);
    }
    if (again.length === 0) return;
    void this.enqueue(async () => {
      const still = again.filter((p) => this.pulled.get(slot(p.userId, p.kind)) === p);
      if (still.length) await this.unsubscribe(still);
      this.scheduleSync();
    });
  }

  /** Reports what can be heard and seen of each person, where that changed. */
  private present(): void {
    if (this.closed) return;
    const users = new Set(this.shown.keys());
    for (const p of this.pulled.values()) users.add(p.userId);
    for (const userId of users) {
      const st = this.peers.has(userId) ? this.roster.get(userId) : undefined;
      const track = (kind: PubKind) => {
        const t = st ? this.pulled.get(slot(userId, kind))?.track : null;
        return t && t.readyState === "live" ? t : null;
      };
      const prev = this.shown.get(userId) ?? { audio: null, video: null, screen: null };
      const audio = track("audio");
      const video = st?.video ? track("video") : null;
      // a share shows once its picture is there; its sound comes with it
      const screen = st?.streaming ? track("screen") : null;
      const screenAudio = screen ? track("screen_audio") : null;
      const next: Shown = {
        audio: streamOf(prev.audio, audio ? [audio] : []),
        video: streamOf(prev.video, video ? [video] : []),
        screen: streamOf(prev.screen, screen ? (screenAudio ? [screen, screenAudio] : [screen]) : []),
        videoTooLarge: !!video && !!this.pulled.get(slot(userId, "video"))?.tooLarge,
        screenTooLarge: !!screen && !!this.pulled.get(slot(userId, "screen"))?.tooLarge,
      };
      if (next.audio !== prev.audio) {
        if (next.audio) this.playback.attachVoice(userId, next.audio, this.held(userId, "audio"));
        else this.playback.detachVoice(userId);
        this.events.onRemoteStream(userId, "audio", next.audio);
      }
      if (next.video !== prev.video) this.events.onRemoteStream(userId, "video", next.video);
      if (next.screen !== prev.screen) {
        if (next.screen?.getAudioTracks().length) this.playback.attachScreenAudio(userId, next.screen, this.held(userId, "screen_audio"));
        else this.playback.detachScreenAudio(userId);
        this.events.onRemoteStream(userId, "screen", next.screen);
      }
      // told again with a new stream too, which starts out not too large
      if (next.videoTooLarge !== !!prev.videoTooLarge || (next.videoTooLarge && next.video !== prev.video)) this.events.onTooLarge?.(userId, "video", !!next.videoTooLarge);
      if (next.screenTooLarge !== !!prev.screenTooLarge || (next.screenTooLarge && next.screen !== prev.screen)) this.events.onTooLarge?.(userId, "screen", !!next.screenTooLarge);
      if (next.audio || next.video || next.screen) this.shown.set(userId, next);
      else this.shown.delete(userId);
    }
  }

  // --- output ------------------------------------------------------------

  setVolume(userId: string, pct: number): void {
    this.playback.setVolume(userId, pct);
  }

  setStreamVolume(userId: string, pct: number): void {
    this.playback.setStreamVolume(userId, pct);
  }

  setStreamMuted(userId: string, on: boolean): void {
    this.playback.setStreamMuted(userId, on);
  }

  setLocalMute(userId: string, on: boolean): void {
    this.playback.setLocalMute(userId, on);
  }

  setOutputMuted(on: boolean): void {
    this.playback.setOutputMuted(on);
  }

  setModMuted(userId: string, on: boolean): void {
    this.playback.setModMuted(userId, on);
  }

  setPriority(userId: string, on: boolean): void {
    this.playback.setPriority(userId, on);
  }

  setMasterVolume(pct: number): void {
    this.playback.setMasterVolume(pct);
  }

  setOutputDevice(deviceId: string | null): Promise<void> {
    return this.playback.setOutputDevice(deviceId);
  }

  async stats(): Promise<{ rtt: number; loss: number }> {
    const report = await this.pc.getStats().catch(() => null);
    let rtt = 0;
    let selected: string | undefined;
    const pairs = new Map<string, RTCIceCandidatePairStats>();
    const streams = new Map<string, LossTotals>();
    report?.forEach((r) => {
      // the pair in use is the one the transport names; after an ICE restart
      // an old nominated pair can still be in the report
      if (r.type === "transport") selected = (r as RTCTransportStats).selectedCandidatePairId;
      if (r.type === "candidate-pair") pairs.set(r.id, r as RTCIceCandidatePairStats);
      if (r.type === "inbound-rtp") {
        const x = r as RTCInboundRtpStreamStats;
        streams.set(r.id, { lost: x.packetsLost ?? 0, received: x.packetsReceived ?? 0 });
      }
    });
    const pair = (selected ? pairs.get(selected) : undefined) ?? [...pairs.values()].find((p) => p.nominated && p.currentRoundTripTime !== undefined);
    // a pair that is down still reports the round trip it last had, which read as a good connection
    if (pair?.currentRoundTripTime !== undefined && this.pc.connectionState === "connected") rtt = pair.currentRoundTripTime * 1000;
    return { rtt, loss: this.recentLoss.measure(streams) };
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.generation++;
    clearInterval(this.keyTimer);
    clearInterval(this.bypassTimer);
    clearInterval(this.lossTimer);
    clearInterval(this.stallTimer);
    if (this.hiddenTimer) clearTimeout(this.hiddenTimer);
    if (this.sendSwitch) clearTimeout(this.sendSwitch);
    if (this.recoverTimer) clearTimeout(this.recoverTimer);
    if (this.retry) clearTimeout(this.retry.timer);
    this.retry = null;
    this.pc.ontrack = null;
    this.pc.onconnectionstatechange = null;
    this.pc.close();
    this.pulled.clear();
    this.byMid.clear();
    this.shown.clear();
    this.playback.close();
    this.crypto.close();
  }
}
