/**
 * What the voice session asks of a call transport. There are two: the
 * peer-to-peer mesh (development and servers without Realtime keys) and
 * Cloudflare Realtime SFU (every call on a server that has them).
 */
import type { VoiceState } from "@/api/types";

export type MediaKind = "audio" | "video" | "screen";

export interface SignalPayload {
  description?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit | null;
  /** Which of my stream ids carries which kind, so the receiver can label tracks. */
  streams?: Partial<Record<MediaKind, string>>;
  /** Through the SFU: the share of your voice's packets this listener is losing, so you send copies, */
  voice_loss?: number;
  /** and the share of your audio it is concealing, and how long it waits to play it (ms), so copies that make things worse go back; */
  voice_conceal?: number;
  voice_delay?: number;
  /** all over this stretch of time before the report (ms). */
  voice_span?: number;
}

export interface TransportEvents {
  onSignal: (to: string, payload: SignalPayload) => void;
  onPeerState: (userId: string, state: RTCPeerConnectionState) => void;
  onRemoteStream: (userId: string, kind: MediaKind, stream: MediaStream | null) => void;
  onSpeaking: (userId: string, speaking: boolean) => void;
  /**
   * This device's own link to the call: coming up the first time, up, or
   * down and on its way back. The per-person states above cannot say that:
   * through the SFU they all follow the one connection.
   */
  onConnection?: (state: "connecting" | "connected" | "reconnecting") => void;
  /** Something the person should hear about, like video pausing near the free allowance. */
  onError?: (code: string, message: string) => void;
  /**
   * Someone's camera or screen picture is over the size cap (videoCap.ts), so
   * none of it is decoded here, or it is back within it.
   */
  onTooLarge?: (userId: string, kind: "video" | "screen", tooLarge: boolean) => void;
}

export interface VoiceTransport {
  /** The voice to send: a copy of the microphone that voiceSend.ts silences between phrases, never taken away for them. */
  setLocalAudio(track: MediaStreamTrack | null): void;
  /** Whether this device's voice goes out in two channels (channels.ts). */
  setVoiceStereo(on: boolean): void;
  /** Whether every voice and shared sound plays here in one channel. */
  setHearMono(on: boolean): void;
  setLocalVideo(track: MediaStreamTrack | null): void;
  /** `fps` is the share's chosen rate: a share at 60 is steered to keep moving (shareAdapt.ts). */
  /** `smooth`: the share keeps its frames and is made smaller when the link is short, rather than keeping its size (shareQuality.ts SharePlan). */
  setLocalScreen(stream: MediaStream | null, bitrate?: number, fps?: number, smooth?: boolean): void;
  addPeer(userId: string): void;
  removePeer(userId: string): void;
  peerIds(): string[];
  handleSignal(from: string, payload: SignalPayload): void;
  /** Everyone in the call as the server last described them, this device included. */
  setRoster(states: VoiceState[]): void;
  /** The server acknowledged this device's voice state. */
  joined(state: VoiceState): void;
  /** The gateway reconnected, so the server forgot this call and anything it had set up for it. */
  reconnected(): void;
  /** A moderator moved this device's call to another channel. */
  moveTo(channelId: string): void;
  /** The conversation's keys may have arrived: read them now rather than on the next check. */
  keysArrived?(): void;
  /** The network looks back: whatever is down tries again now. */
  nudge?(): void;
  /** The SFU session of this device's call, which a reconnect can resume; null peer to peer. */
  currentSession?(): string | null;
  /** Fresh relay credentials, for every connection and ICE restart from now on. */
  setIceServers?(servers: RTCIceServer[]): void;
  setWatching(userId: string, on: boolean): void;
  /**
   * Whether the call's pictures are on screen anywhere (videoShown.ts). While
   * they are not, cameras and shares need not come in; voices always do.
   */
  setVideoShown?(on: boolean): void;
  setVolume(userId: string, pct: number): void;
  setLocalMute(userId: string, on: boolean): void;
  /** The level of someone's screen share sound, apart from their voice. */
  setStreamVolume(userId: string, pct: number): void;
  setStreamMuted(userId: string, on: boolean): void;
  setOutputMuted(on: boolean): void;
  /** Keeps someone a moderator muted or deafened silent here, whatever their app sends. */
  setModMuted(userId: string, on: boolean): void;
  /** Someone talking as Priority Speaker, who turns everyone else down while heard. */
  setPriority(userId: string, on: boolean): void;
  setMasterVolume(pct: number): void;
  setOutputDevice(deviceId: string | null): Promise<void>;
  stats(): Promise<{ rtt: number; loss: number }>;
  close(): void;
}
