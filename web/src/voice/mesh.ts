/**
 * Full-mesh WebRTC transport: one RTCPeerConnection per remote participant,
 * perfect negotiation (the peer with the lower user id is polite), signalling
 * relayed through the gateway. Media is DTLS-SRTP peer to peer; the server
 * never sees it. Playback goes through WebAudio so per-user volume can exceed
 * 100 % and the output device can be chosen.
 *
 * Video goes out in VP8 and comes in as VP8 or H.264 (a phone's screen
 * share), and where the browser allows it every received video frame is held
 * to the size cap (frameSize.ts) before it is decoded, as frames through the
 * SFU are once opened.
 */
import type { VoiceState } from "@/api/types";
import { FrameCrypto } from "./e2ee";
import { tunedLocal, tunedRemote } from "./opus";

/**
 * Asking to play stereo stays on this side (tunedLocal), and what goes to the
 * other side is the description as it was made: an older app would take
 * `stereo=1` from it and send its mono voice in two channels at a voice's bitrate.
 */
const NO_MIDS: ReadonlySet<string> = new Set();
import { Playback } from "./playback";
import { MAX_SHARE_BPS, MIN_SHARE_BPS } from "./shareQuality";
import type { MediaKind, SignalPayload, TransportEvents, VoiceTransport } from "./transport";
import { RecentLoss, type LossTotals } from "./recentLoss";
import { acceptCallVideo, capSender, clampBitrate, preferVp8, recapSender, trackSize } from "./videoCap";
import { ShareAdapter, retuneShare } from "./shareAdapt";

export type { MediaKind, SignalPayload, TransportEvents } from "./transport";

interface Peer {
  userId: string;
  pc: RTCPeerConnection;
  polite: boolean;
  makingOffer: boolean;
  ignoreOffer: boolean;
  remoteStreams: Partial<Record<MediaKind, string>>;
  pendingCandidates: RTCIceCandidateInit[];
  audioSender: RTCRtpSender | null;
  videoSender: RTCRtpSender | null;
  screenSenders: RTCRtpSender[];
  /** Each peer's link is its own, so each steers the share's size for itself (shareAdapt.ts). */
  shareAdapter: ShareAdapter | null;
  restartTimer: ReturnType<typeof setTimeout> | null;
  /** ICE restarts tried since the connection last worked. */
  restarts: number;
  /** Fires when a renegotiation offer never got an answer. */
  offerTimer: ReturnType<typeof setTimeout> | null;
  /** A negotiation we had to skip because the exchange was mid-flight. */
  renegotiate: boolean;
  /** Fires if the connection never comes up, so the peer can be rebuilt. */
  watchdog: ReturnType<typeof setTimeout> | null;
  attempts: number;
  /** Every description this peer touches runs through here, one at a time. */
  ops: Promise<void>;
}

function lower(a: string, b: string): boolean {
  if (a.length !== b.length) return a.length < b.length;
  return a < b;
}

/** How many early signals to hold for a peer we have not been told about yet. */
const EARLY_SIGNAL_LIMIT = 32;
/** How long to give a peer to come up before rebuilding it from scratch. */
const CONNECT_DEADLINE_MS = 12_000;
/** How many rebuilds before leaving the tile showing trouble. */
const MAX_REBUILDS = 3;
/** How long a connection that dropped gets to recover before it is rebuilt. */
const DROP_DEADLINE_MS = 20_000;
/** How often, and how many times, to restart ICE on a dropped connection. */
const ICE_RESTART_MS = 4_000;
const MAX_ICE_RESTARTS = 3;
/** How long a renegotiation offer may go unanswered before it is taken back and sent again. */
const OFFER_DEADLINE_MS = 10_000;
/** The camera is capped here. */
const CAMERA_BPS = 2_500_000;

/** A received video track held to the size cap, by its name in the worker. */
interface Inspected {
  userId: string;
  stream: MediaStream;
  over: boolean;
}

export class MeshTransport implements VoiceTransport {
  /** Loss is measured between readings, not over the whole call. */
  private recentLoss = new RecentLoss();
  private peers = new Map<string, Peer>();
  private audioTrack: MediaStreamTrack | null = null;
  private voiceStereo = false;
  private hearMono = false;
  private monoOnly = new Set<string>();
  private videoTrack: MediaStreamTrack | null = null;
  private screen: MediaStream | null = null;
  private audioStream = new MediaStream();
  private videoStream = new MediaStream();
  private screenStream = new MediaStream();
  /** Signals that beat the roster: an offer can arrive before we know the sender. */
  private early = new Map<string, SignalPayload[]>();
  private playback: Playback;
  private screenBitrate = 6_000_000;
  private screenFps = 30;
  private screenSmooth = false;
  private closed = false;
  /** The peers this device has been connected to, so losing them reads as reconnecting rather than a first connect. */
  private upBefore = new Set<string>();
  private lastConnection: "connecting" | "connected" | "reconnecting" | null = null;
  /** The worker that holds received video to the size cap, made with the first video track; null where the browser cannot. */
  private inspector: FrameCrypto | null = null;
  private inspected = new Map<string, Inspected>();
  /** Receivers already checked: a track that comes back in a later exchange keeps its check. */
  private checkedReceivers = new WeakSet<RTCRtpReceiver>();
  private receiverSeq = 0;

  constructor(
    private meId: string,
    private ice: RTCIceServer[],
    private events: TransportEvents,
  ) {
    this.playback = new Playback(events.onSpeaking);
  }

  // --- local media -------------------------------------------------------

  /** An encoder takes its channels from the description, so a change goes through another exchange with each peer. */
  setVoiceStereo(on: boolean): void {
    if (on === this.voiceStereo) return;
    this.voiceStereo = on;
    for (const p of this.peers.values()) this.enqueue(p, () => this.negotiate(p));
  }

  setLocalAudio(track: MediaStreamTrack | null): void {
    this.audioTrack = track;
    // The stream keeps its id, for the reason setLocalVideo spells out below:
    // the sender is reused and keeps the msid it was added with, so a fresh
    // stream here only told peers an id no incoming track carries.
    for (const t of this.audioStream.getTracks()) this.audioStream.removeTrack(t);
    if (track) this.audioStream.addTrack(track);
    for (const p of this.peers.values()) this.syncSenders(p);
  }

  setLocalVideo(track: MediaStreamTrack | null): void {
    this.videoTrack = track;
    // The stream keeps its id across camera toggles: the sender is reused, so
    // its msid never changes and the other side must still recognise it.
    for (const t of this.videoStream.getTracks()) this.videoStream.removeTrack(t);
    if (track) this.videoStream.addTrack(track);
    for (const p of this.peers.values()) this.syncSenders(p);
  }

  setLocalScreen(stream: MediaStream | null, bitrate = 6_000_000, fps = 30, smooth = fps >= 50): void {
    this.screen = stream;
    this.screenBitrate = clampBitrate(bitrate, MIN_SHARE_BPS, MAX_SHARE_BPS, 6_000_000);
    this.screenFps = fps;
    this.screenSmooth = smooth;
    this.screenStream = stream ?? new MediaStream();
    for (const p of this.peers.values()) this.syncSenders(p);
  }

  // --- peers -------------------------------------------------------------

  /**
   * Starts a connection to one peer. Who offers first does not matter: both
   * sides offer as soon as they have media and the politeness rule settles
   * the collision.
   */
  addPeer(userId: string, attempts = 0): void {
    if (this.closed || this.peers.has(userId) || userId === this.meId) return;
    const pc = new RTCPeerConnection({ iceServers: this.ice, bundlePolicy: "max-bundle" });
    const peer: Peer = { userId, pc, polite: lower(this.meId, userId), makingOffer: false, ignoreOffer: false, remoteStreams: {}, pendingCandidates: [], audioSender: null, videoSender: null, screenSenders: [], shareAdapter: null, restartTimer: null, restarts: 0, offerTimer: null, renegotiate: false, watchdog: null, attempts, ops: Promise.resolve() };
    this.peers.set(userId, peer);
    pc.onicecandidate = (e) => this.events.onSignal(userId, { candidate: e.candidate ? e.candidate.toJSON() : null });
    pc.onnegotiationneeded = () => this.enqueue(peer, () => this.negotiate(peer));
    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      if (state === "connected") {
        this.upBefore.add(userId);
        if (peer.watchdog) clearTimeout(peer.watchdog);
        if (peer.restartTimer) clearTimeout(peer.restartTimer);
        peer.watchdog = null;
        peer.restartTimer = null;
        peer.restarts = 0;
        peer.attempts = 0;
      } else if (state === "disconnected" || state === "failed") {
        // A phone moving between Wi-Fi and cellular sits in "disconnected" for
        // a long time before the browser calls it failed, so recovery starts
        // there. If restarting ICE does not bring it back, the peer is rebuilt
        // rather than left saying reconnecting.
        if (!peer.watchdog) peer.watchdog = setTimeout(() => this.rebuild(userId, peer.attempts), DROP_DEADLINE_MS);
        if (!peer.restartTimer) this.scheduleRestart(peer, state === "failed" ? 1500 : ICE_RESTART_MS);
      }
      this.events.onPeerState(userId, state);
      this.reportConnection();
    };
    pc.ontrack = (e) => this.onTrack(peer, e);
    this.syncSenders(peer);
    this.events.onPeerState(userId, pc.connectionState);
    this.reportConnection();
    peer.watchdog = setTimeout(() => this.rebuild(userId, attempts), CONNECT_DEADLINE_MS);
    this.drainEarly(userId);
  }

  /**
   * A peer that has not connected by the deadline is thrown away and made
   * again. Signalling can stall in ways neither side notices: a lost offer, a
   * collision resolved the wrong way, a socket that dropped mid-exchange.
   */
  private rebuild(userId: string, attempts: number): void {
    const peer = this.peers.get(userId);
    if (!peer || this.closed) return;
    peer.watchdog = null;
    if (peer.pc.connectionState === "connected") return;
    if (attempts + 1 >= MAX_REBUILDS) {
      console.warn("voice: giving up on", userId, "after", attempts + 1, "attempts");
      return;
    }
    console.warn("voice: rebuilding the connection to", userId);
    const wasUp = this.upBefore.has(userId);
    this.removePeer(userId);
    if (wasUp) this.upBefore.add(userId);
    this.addPeer(userId, attempts + 1);
  }

  /** Restarts ICE on a dropped connection a few times, from the impolite side so both ends don't offer at once. */
  private scheduleRestart(peer: Peer, delay: number): void {
    if (peer.polite) return;
    peer.restartTimer = setTimeout(() => {
      peer.restartTimer = null;
      if (this.closed || this.peers.get(peer.userId) !== peer) return;
      const state = peer.pc.connectionState;
      if ((state !== "disconnected" && state !== "failed") || peer.restarts >= MAX_ICE_RESTARTS) return;
      peer.restarts++;
      peer.pc.restartIce();
      this.scheduleRestart(peer, ICE_RESTART_MS);
    }, delay);
  }

  /** Replays, in order, anything this peer sent before we knew about them. */
  private drainEarly(userId: string): void {
    const waiting = this.early.get(userId);
    if (!waiting?.length) return;
    this.early.delete(userId);
    for (const payload of waiting) this.handleSignal(userId, payload);
  }

  removePeer(userId: string): void {
    const p = this.peers.get(userId);
    if (!p) return;
    this.peers.delete(userId);
    this.playback.detach(userId);
    if (p.restartTimer) clearTimeout(p.restartTimer);
    if (p.offerTimer) clearTimeout(p.offerTimer);
    p.pc.onicecandidate = null;
    p.pc.onnegotiationneeded = null;
    p.pc.ontrack = null;
    p.pc.onconnectionstatechange = null;
    if (p.watchdog) clearTimeout(p.watchdog);
    p.pc.close();
    this.early.delete(userId);
    this.upBefore.delete(userId);
    for (const [id, x] of this.inspected) if (x.userId === userId) this.inspected.delete(id);
    for (const kind of ["audio", "video", "screen"] as const) this.events.onRemoteStream(userId, kind, null);
    for (const kind of ["video", "screen"] as const) this.events.onTooLarge?.(userId, kind, false);
    this.reportConnection();
  }

  /**
   * This device's link to the call as a whole: up while any peer is (or with
   * nobody to reach), and otherwise still coming up. Peer to peer, one link
   * that dropped says nothing about whose network it was — their tile says it
   * is reconnecting — so this device only calls itself reconnecting when every
   * link it had went down together, or the browser says it is offline.
   */
  private reportConnection(): void {
    if (this.closed) return;
    const peers = [...this.peers.values()];
    const hadUp = peers.filter((p) => this.upBefore.has(p.userId)).length;
    const offline = typeof navigator !== "undefined" && navigator.onLine === false;
    let state: "connecting" | "connected" | "reconnecting" = "connecting";
    if (peers.length === 0 || peers.some((p) => p.pc.connectionState === "connected")) state = "connected";
    else if (hadUp >= 2 || (hadUp >= 1 && offline)) state = "reconnecting";
    else if (hadUp === 1) state = "connected";
    if (state === this.lastConnection) return;
    this.lastConnection = state;
    this.events.onConnection?.(state);
  }

  /** Fresh relay credentials, for every connection and ICE restart from now on. */
  setIceServers(servers: RTCIceServer[]): void {
    this.ice = servers;
    for (const p of this.peers.values()) {
      try {
        p.pc.setConfiguration({ ...p.pc.getConfiguration(), iceServers: servers });
      } catch {
        /* the next rebuild takes them */
      }
    }
  }

  peerIds(): string[] {
    return [...this.peers.keys()];
  }

  /** The mesh learns about peers one by one through addPeer. */
  /** Who the server says lacks Use Stereo Voice: their sound is decoded in one channel, whatever they send. */
  setRoster(states: VoiceState[]): void {
    const next = new Set(states.filter((s) => s.mono_only).map((s) => s.user_id));
    const changed = [...this.peers.values()].filter((p) => next.has(p.userId) !== this.monoOnly.has(p.userId));
    this.monoOnly = next;
    if (!this.hearMono) for (const p of changed) this.enqueue(p, () => this.negotiate(p));
  }

  setHearMono(on: boolean): void {
    if (on === this.hearMono) return;
    this.hearMono = on;
    for (const p of this.peers.values()) this.enqueue(p, () => this.negotiate(p));
  }

  private tuneLocal(userId: string, desc: RTCSessionDescriptionInit): RTCSessionDescriptionInit {
    const mono = this.hearMono || this.monoOnly.has(userId);
    return tunedLocal(desc, NO_MIDS, mono ? NO_MIDS : "all", mono ? "all" : NO_MIDS);
  }

  /** In the call: with nobody else there yet, there is nothing to connect to and the call is up. */
  joined(_state: VoiceState): void {
    this.reportConnection();
  }

  reconnected(): void {}

  moveTo(_channelId: string): void {}

  close(): void {
    this.closed = true;
    this.early.clear();
    for (const id of [...this.peers.keys()]) this.removePeer(id);
    this.playback.close();
    this.inspector?.close();
    this.inspector = null;
  }

  // --- signalling --------------------------------------------------------

  /**
   * Runs one description-touching step at a time per peer. Two of them
   * interleaved leave the connection in a state neither expects, which is how
   * a call ends up stuck with both sides waiting for the other.
   */
  private enqueue(peer: Peer, step: () => Promise<void>): void {
    peer.ops = peer.ops.then(step).catch((err: unknown) => console.warn("voice: signalling", peer.userId, err));
  }

  handleSignal(from: string, payload: SignalPayload): void {
    const peer = this.peers.get(from);
    if (!peer) {
      // Whoever joins second offers immediately, which can outrun the roster
      // update that introduces them. Dropping that offer deadlocks the call:
      // we would offer in turn and the other side, being impolite, would
      // ignore it. So hold onto it until the peer exists.
      if (this.closed || from === this.meId) return;
      const queued = this.early.get(from) ?? [];
      queued.push(payload);
      this.early.set(from, queued.slice(-EARLY_SIGNAL_LIMIT));
      return;
    }
    this.enqueue(peer, () => this.applySignal(peer, payload));
  }

  private async applySignal(peer: Peer, payload: SignalPayload): Promise<void> {
    const from = peer.userId;
    if (payload.streams) peer.remoteStreams = { ...peer.remoteStreams, ...payload.streams };
    const pc = peer.pc;
    try {
      if (payload.description) {
        const desc = payload.description;
        const offerCollision = desc.type === "offer" && (peer.makingOffer || pc.signalingState !== "stable");
        peer.ignoreOffer = !peer.polite && offerCollision;
        if (peer.ignoreOffer) return;
        await pc.setRemoteDescription(tunedRemote(desc, undefined, this.voiceStereo));
        for (const c of peer.pendingCandidates.splice(0)) await pc.addIceCandidate(c).catch(() => {});
        if (desc.type === "offer") {
          const answer = await pc.createAnswer();
          await pc.setLocalDescription(this.tuneLocal(from, answer));
          this.events.onSignal(from, { description: answer, streams: this.streamIds() });
        }
        if (peer.renegotiate && pc.signalingState === "stable") this.enqueue(peer, () => this.negotiate(peer));
      } else if (payload.candidate !== undefined) {
        const c = payload.candidate;
        if (!pc.remoteDescription) {
          if (c) peer.pendingCandidates.push(c);
          return;
        }
        try {
          await pc.addIceCandidate(c ?? undefined);
        } catch (err) {
          if (!peer.ignoreOffer) throw err;
        }
      }
    } catch (err) {
      console.warn("voice: signal error from", from, err);
    }
  }

  private streamIds(): Partial<Record<MediaKind, string>> {
    return { audio: this.audioStream.id, video: this.videoStream.id, screen: this.screenStream.id };
  }

  private async negotiate(peer: Peer): Promise<void> {
    const pc = peer.pc;
    // Mid-exchange the other side's description is already on its way; a
    // second offer now would talk over it, so it waits for the lull.
    if (this.closed) return;
    if (pc.signalingState !== "stable") {
      peer.renegotiate = true;
      return;
    }
    peer.renegotiate = false;
    try {
      peer.makingOffer = true;
      const renegotiating = !!pc.remoteDescription;
      const offer = await pc.createOffer();
      await pc.setLocalDescription(this.tuneLocal(peer.userId, offer));
      this.events.onSignal(peer.userId, { description: offer, streams: this.streamIds() });
      if (renegotiating) this.armOfferTimer(peer);
    } catch (err) {
      console.warn("voice: negotiation failed", err);
    } finally {
      peer.makingOffer = false;
    }
  }

  /**
   * An offer that is never answered (refused by the server, lost on the way)
   * would leave the peer in have-local-offer for good, and every later change
   * would wait behind it. So it is taken back and made again. The first
   * exchange is left to the connect watchdog instead.
   */
  private armOfferTimer(peer: Peer): void {
    if (peer.offerTimer) clearTimeout(peer.offerTimer);
    peer.offerTimer = setTimeout(() => {
      peer.offerTimer = null;
      if (this.closed || this.peers.get(peer.userId) !== peer) return;
      this.enqueue(peer, async () => {
        if (peer.pc.signalingState !== "have-local-offer") return;
        console.warn("voice: offer to", peer.userId, "went unanswered, sending it again");
        await peer.pc.setLocalDescription({ type: "rollback" });
        await this.negotiate(peer);
      });
    }, OFFER_DEADLINE_MS);
  }

  // --- senders -----------------------------------------------------------

  private syncSenders(peer: Peer): void {
    const pc = peer.pc;
    // audio: one sender, replaced in place so renegotiation is rare; a
    // silence between phrases is the track's own (voiceSend.ts), never a gap
    if (this.audioTrack) {
      if (peer.audioSender) void peer.audioSender.replaceTrack(this.audioTrack);
      else peer.audioSender = pc.addTrack(this.audioTrack, this.audioStream);
    } else if (peer.audioSender) {
      void peer.audioSender.replaceTrack(null);
    }
    // Video keeps its sender and only pauses it: a removed track leaves its
    // m-line behind for good, so every camera toggle would grow the offer
    // until the server refused it as too large.
    if (this.videoTrack) {
      if (peer.videoSender) {
        this.setSending(pc, peer.videoSender, true);
        void peer.videoSender.replaceTrack(this.videoTrack);
      } else {
        peer.videoSender = pc.addTrack(this.videoTrack, this.videoStream);
        this.sendVp8(pc, peer.videoSender);
        capSender(peer.videoSender, CAMERA_BPS);
      }
    } else if (peer.videoSender) {
      if (peer.videoSender.track) void peer.videoSender.replaceTrack(null);
      this.setSending(pc, peer.videoSender, false);
    }
    const screenTracks = this.screen ? this.screen.getTracks() : [];
    const wanted = new Set(screenTracks.map((t) => t.id));
    for (const s of [...peer.screenSenders]) {
      if (!s.track || !wanted.has(s.track.id)) {
        // a stopped transceiver's m-line can be reused by the next share
        const tr = pc.getTransceivers().find((x) => x.sender === s);
        if (tr && typeof tr.stop === "function") tr.stop();
        else pc.removeTrack(s);
        peer.screenSenders = peer.screenSenders.filter((x) => x !== s);
      }
    }
    for (const t of screenTracks) {
      if (!peer.screenSenders.some((s) => s.track?.id === t.id)) {
        const sender = pc.addTrack(t, this.screenStream);
        if (t.kind === "video") {
          this.sendVp8(pc, sender);
          capSender(sender, this.screenBitrate, trackSize(t), "high");
          peer.shareAdapter = new ShareAdapter(this.screenFps, this.screenSmooth);
        }
        peer.screenSenders.push(sender);
      }
    }
  }

  /** Turns sending on or off for a sender's transceiver, keeping whatever it receives. */
  private setSending(pc: RTCPeerConnection, sender: RTCRtpSender, on: boolean): void {
    const tr = pc.getTransceivers().find((x) => x.sender === sender);
    if (!tr || tr.direction === "stopped") return;
    const receiving = tr.direction === "sendrecv" || tr.direction === "recvonly";
    const next: RTCRtpTransceiverDirection = on ? (receiving ? "sendrecv" : "sendonly") : receiving ? "recvonly" : "inactive";
    if (tr.direction !== next) tr.direction = next;
  }

  /** Video goes out, and comes back on the same transceiver, in VP8. */
  private sendVp8(pc: RTCPeerConnection, sender: RTCRtpSender): void {
    const tr = pc.getTransceivers().find((x) => x.sender === sender);
    if (tr) preferVp8(tr);
  }

  // --- receiving ---------------------------------------------------------

  private onTrack(peer: Peer, e: RTCTrackEvent): void {
    if (e.track.kind === "video") this.inspect(peer, e);
    const stream = e.streams[0];
    if (!stream) return;
    let kind: MediaKind = e.track.kind === "audio" ? "audio" : "video";
    for (const [k, id] of Object.entries(peer.remoteStreams)) if (id === stream.id) kind = k as MediaKind;
    if (kind === "screen") {
      // screen audio and video share one stream; expose it once
      this.events.onRemoteStream(peer.userId, "screen", stream);
      if (e.track.kind === "audio") this.playback.attachScreenAudio(peer.userId, stream);
    } else if (kind === "audio") {
      this.playback.attachVoice(peer.userId, stream);
      this.events.onRemoteStream(peer.userId, "audio", stream);
    } else {
      this.events.onRemoteStream(peer.userId, "video", stream);
    }
    e.track.onended = () => this.events.onRemoteStream(peer.userId, kind, null);
    stream.onremovetrack = () => {
      if (kind === "screen" && this.playback.screenAudioStream(peer.userId) === stream && stream.getAudioTracks().length === 0) this.playback.detachScreenAudio(peer.userId);
      if (stream.getTracks().length === 0) this.events.onRemoteStream(peer.userId, kind, null);
    };
  }

  /**
   * Holds a received video track to the size cap before it is decoded. This
   * runs while the offer or answer carrying it is applied, before any of its
   * frames can arrive, and asks for VP8 and H.264 alone first so the answer
   * this device gives takes nothing the check cannot read.
   */
  private inspect(peer: Peer, e: RTCTrackEvent): void {
    acceptCallVideo(e.transceiver);
    if (this.checkedReceivers.has(e.receiver)) return;
    try {
      this.inspector ??= FrameCrypto.scriptTransform() ? this.newInspector() : null;
      const id = `${peer.userId}#${++this.receiverSeq}`;
      if (!this.inspector?.inspectReceiver(e.receiver, id)) return;
      this.checkedReceivers.add(e.receiver);
      const stream = e.streams[0] ?? new MediaStream([e.track]);
      this.inspected.set(id, { userId: peer.userId, stream, over: false });
      e.track.addEventListener("ended", () => this.inspected.delete(id));
    } catch (err) {
      console.warn("voice: cannot check the size of a received picture", err);
    }
  }

  private newInspector(): FrameCrypto {
    const c = new FrameCrypto();
    c.onSize = (id, over) => {
      const x = this.inspected.get(id);
      if (!x || x.over === over || this.closed) return;
      x.over = over;
      const peer = this.peers.get(x.userId);
      const kind = peer && peer.remoteStreams.screen === x.stream.id ? "screen" : "video";
      this.events.onTooLarge?.(x.userId, kind, over);
    };
    return c;
  }

  // --- output control ----------------------------------------------------

  setWatching(userId: string, on: boolean): void {
    this.playback.setWatching(userId, on);
  }

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

  /** Master output level for every peer, 0–200 %. */
  setMasterVolume(pct: number): void {
    this.playback.setMasterVolume(pct);
  }

  setOutputDevice(deviceId: string | null): Promise<void> {
    return this.playback.setOutputDevice(deviceId);
  }

  /** Aggregate stats: round-trip time and packet loss over all peers, for a quality indicator. */
  async stats(): Promise<{ rtt: number; loss: number }> {
    let rtt = 0;
    let n = 0;
    const streams = new Map<string, LossTotals>();
    for (const [peerId, p] of this.peers) {
      // what is sent stays within the size cap even when the picture going in grows,
      // and a share in motion is sized to what this peer's link carries
      if (p.videoSender?.track?.kind === "video") void recapSender(p.videoSender);
      for (const sender of p.screenSenders) if (sender.track?.kind === "video") void retuneShare(sender, p.shareAdapter ?? (p.shareAdapter = new ShareAdapter(this.screenFps, this.screenSmooth)));
      const report = await p.pc.getStats().catch(() => null);
      if (!report) continue;
      report.forEach((r) => {
        if (r.type === "candidate-pair" && (r as RTCIceCandidatePairStats).state === "succeeded" && (r as RTCIceCandidatePairStats).currentRoundTripTime !== undefined) {
          rtt += (r as RTCIceCandidatePairStats).currentRoundTripTime! * 1000;
          n++;
        }
        if (r.type === "inbound-rtp") {
          const x = r as RTCInboundRtpStreamStats;
          streams.set(`${peerId}:${r.id}`, { lost: x.packetsLost ?? 0, received: x.packetsReceived ?? 0 });
        }
      });
    }
    return { rtt: n ? rtt / n : 0, loss: this.recentLoss.measure(streams) };
  }
}
