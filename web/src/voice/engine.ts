/**
 * The call itself: local media, the transport (peer to peer or through the
 * SFU), frame encryption and playback. It is loaded the first time this page
 * joins a call, so everybody else — the sign-in page, a visitor, anyone who
 * never calls — does not download or parse any of it. `session.ts` is the
 * part the rest of the app talks to.
 */
import { GUEST_FLAG, type VoiceState } from "@/api/types";
import type { GatewayEvent } from "@/gateway/events";
import { gateway } from "@/gateway/session";
import { holdReload } from "@/lib/busy";
import { desktopBridge } from "@/lib/desktopBridge";
import { describeError } from "@/lib/errors";
import { ring } from "@/lib/keyring";
import { playSound } from "@/lib/sounds";
import { convForChannel } from "@/components/composer/send";
import { useKeys } from "@/stores/keys";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { participantsOf, useVoice } from "@/stores/voice";
import { useVoiceLocal } from "@/stores/voiceLocal";
import { FrameCrypto } from "./e2ee";
import { hasGuestKeys, openRelayedKeys, sealKeysForGuest } from "./guestKeys";
import { LevelMeter, audioContext, getCamera, getMic, getScreen, stopScreen, holdAudio, lastInputChoice, sensitivityToThreshold, sentStream, type CameraFacing } from "./media";
import { MeshTransport, type SignalPayload } from "./mesh";
import { asGuest, iceConfig, me, sendState } from "./session";
import { pushToTalkOn } from "./callPerms";
import { SfuTransport } from "./sfu";
import { type SharePlan, sharePlan } from "./shareQuality";
import { shareAllowance } from "@/lib/plans";
import { makeDenoiser, prepareDenoiser, releaseDenoiser } from "./denoise";
import { AUDIBLE_LEVEL, SpeakingGate } from "./speaking";
import type { TransportEvents, VoiceTransport } from "./transport";
import { recheckVideoShown, watchVideoShown } from "./videoShown";
import { VoiceSend } from "./voiceSend";

import { t as tr_ } from "@/i18n";

interface Session {
  channelId: string;
  transport: VoiceTransport;
  /** The raw capture; what peers get is the gain-staged copy. */
  mic: MediaStream | null;
  micRaw: MediaStream | null;
  /** What the call carries of the microphone, silenced between phrases rather than taken away. */
  send: VoiceSend;
  inputGain: GainNode | null;
  inputSource: MediaStreamAudioSourceNode | null;
  /** RNNoise between the microphone and the input slider, while Enhanced noise suppression runs here (denoise.ts). */
  denoiser: AudioNode | null;
  /** Whether this call's noise is taken out here by RNNoise, rather than by the browser or not at all. */
  rnnoise: boolean;
  /** Said once a call that Enhanced cannot run here and Standard is used instead. */
  noiseNoticed: boolean;
  /** Counts input graphs being built, so one overtaken by another is let go. */
  inputGen: number;
  meter: LevelMeter | null;
  meterTimer: ReturnType<typeof setInterval> | null;
  camera: MediaStream | null;
  screen: MediaStream | null;
  /** A camera or screen request is waiting on the browser; a second tap must not open another. */
  cameraPending: boolean;
  screenPending: boolean;
  /** Asking for the microphone again after it was refused; one ask at a time. */
  micRetrying: boolean;
  noiseFloor: number;
  pttHeld: boolean;
  /** The Priority Speaker key is held: the mic is open, as with push to talk, and everyone else is turned down. */
  priorityHeld: boolean;
  /** Whether voice activity currently hears speech, and until when it keeps listening after. */
  vadOpen: boolean;
  vadHoldUntil: number;
  /** Keeps this device's own speaking ring steady between words. */
  speaking: SpeakingGate;
  /** The server has been told about this call, so leaving has to tell it too. */
  announced: boolean;
  /** Fetches the relay credentials again now and then, so a call that runs for hours can still reconnect. */
  iceTimer: ReturnType<typeof setInterval> | null;
  /** What was undone when the call ends: key, audio and permission watchers. */
  cleanups: (() => void)[];
}

let session: Session | null = null;
/** Keeps a new version of the app from reloading the page while in a call. */
let releaseCall: (() => void) | null = null;
/** Keeps the call's audio running (a home-screen app lets it go otherwise). */
let releaseAudio: (() => void) | null = null;
/** Measures the call's connection for everything that shows it (see useVoice's quality). */
let qualityTimer: ReturnType<typeof setInterval> | null = null;
const QUALITY_EVERY_MS = 3000;
/** How long voice activity keeps the mic open after speech drops, so word endings and short pauses get through. */
const VAD_HOLD_MS = 400;
/**
 * How often voice activity listens. Whatever is said before it hears speech
 * start never goes out, so a slow check cut the start off every phrase: at
 * 80 ms it clipped up to a syllable and made each one late by as much.
 */
const VAD_EVERY_MS = 20;
/** The level meter on screen only needs every few of those readings. */
const LEVEL_EVERY = 4;
/**
 * How often a call asks for its relay credentials again. A fetch only goes out
 * once the ones in hand are old enough (see session.ts), and the transport
 * uses the fresh ones for its next reconnect.
 */
const ICE_REFRESH_MS = 10 * 60 * 1000;
/** A stopped audio engine this long into a call is waiting for a tap, not starting up. */
const AUDIO_BLOCKED_AFTER_MS = 1500;

function startQuality(s: Session): void {
  stopQuality();
  const measure = () => {
    if (session !== s) return;
    // a connection that is down has nothing current to report: its last round trip would say all is well
    if (useVoice.getState().connection !== "connected") {
      useVoice.getState().setQuality(null);
      return;
    }
    void s.transport
      .stats()
      .then((q) => {
        // no round trip yet means nothing is connected to measure: unknown, not a perfect 0 ms
        if (session === s) useVoice.getState().setQuality(q.rtt > 0 && useVoice.getState().connection === "connected" ? q : null);
      })
      .catch(() => {});
  };
  measure();
  qualityTimer = setInterval(measure, QUALITY_EVERY_MS);
}

function stopQuality(): void {
  if (qualityTimer) clearInterval(qualityTimer);
  qualityTimer = null;
}

/** The keys handed to each guest in the call, by what they were made from, so each change goes out once. */
const relayedTo = new Map<string, string>();
/**
 * How often the call checks for guests who need newer keys than their link
 * brought. A key change shows up here within this long, and until the guest
 * has the new key they cannot open what this device seals with it; the check
 * itself is only a comparison of epoch lists.
 */
const GUEST_RELAY_MS = 1000;
let relayTimer: ReturnType<typeof setInterval> | null = null;

/**
 * Hands the guests in this call the call keys newer than the ones their links
 * carried, sealed so only a holder of the link can open them. Everyone in the
 * call does it; a guest keeps the first copy and ignores the rest.
 */
function relayGuestKeys(s: Session): void {
  if (session !== s || asGuest() || !(s.transport instanceof SfuTransport)) return;
  const myId = me();
  const conv = convForChannel(s.channelId);
  const epochs = ring.epochs(conv);
  for (const p of participantsOf(useVoice.getState().byChannel, s.channelId)) {
    if (!p.guest || p.user_id === myId) continue;
    // a guest who joins again (a reload) has lost what it was given
    const made = `${p.joined_at}:${p.guest.epoch}:${epochs.join(",")}`;
    if (relayedTo.get(p.user_id) === made) continue;
    relayedTo.set(p.user_id, made);
    const to = p.user_id;
    void sealKeysForGuest(s.channelId, to, p.guest.epoch, epochs, (epoch) => ring.get(conv, epoch))
      .then((payload) => {
        if (payload && session === s) gateway()?.send("voice_signal", { to, payload: { guest_keys: payload } });
      })
      .catch(() => relayedTo.delete(to));
  }
}

function startRelay(s: Session): void {
  stopRelay();
  if (asGuest()) return;
  relayTimer = setInterval(() => relayGuestKeys(s), GUEST_RELAY_MS);
}

function stopRelay(): void {
  if (relayTimer) clearInterval(relayTimer);
  relayTimer = null;
  relayedTo.clear();
}

/**
 * True when the local mic may be on at all: not muted, and in push to talk
 * the key, or on a touch screen the button in the call, held down.
 */
function shouldTransmit(): boolean {
  const local = useVoiceLocal.getState();
  const mine = useVoice.getState().mine;
  // a moderator's deafen takes the voice away too, as it does the sound
  if (local.muted || local.deafened || mine?.server_mute || mine?.server_deaf) return false;
  if (session?.priorityHeld) return true;
  if (pushToTalkOn()) return !!session?.pttHeld;
  return true;
}

/** True when voice activity lets the mic through: it heard speech, or push to talk is doing the job. */
function gateOpen(): boolean {
  const s = session;
  if (!s) return true;
  if (s.pttHeld || s.priorityHeld) return true;
  if (pushToTalkOn()) return true;
  // The gate is worked by a timer, which a hidden page has throttled or
  // frozen; it stays open there rather than cut the speaker off mid-sentence.
  // The desktop app keeps a call at full speed while it is hidden.
  if (typeof document !== "undefined" && document.visibilityState === "hidden" && !desktopBridge()) return true;
  return s.vadOpen;
}

/** Whether nothing of the call may be heard here: deafened by this person, or by a moderator. */
export function outputSilenced(): boolean {
  return useVoiceLocal.getState().deafened || !!useVoice.getState().mine?.server_deaf;
}

export function applyTransmit(): void {
  if (!session?.mic) return;
  const on = shouldTransmit();
  for (const t of session.mic.getAudioTracks()) t.enabled = on;
  // The call's copy of the mic is silenced rather than taken away (voiceSend.ts), and the meter,
  // which listens to the mic itself, keeps hearing it and can open the copy again.
  session.send.setOpen(on && gateOpen());
}

/** Push to talk: the key or the button went down or came up. */
export function setPttHeld(on: boolean): void {
  if (!session || session.pttHeld === on) return;
  session.pttHeld = on;
  useVoice.getState().setPttHeld(on);
  applyTransmit();
}

/** The Priority Speaker key went down or came up; the server hears of it with the voice state. */
export function setPriorityHeld(on: boolean): boolean {
  if (!session || session.priorityHeld === on) return false;
  session.priorityHeld = on;
  applyTransmit();
  return true;
}

/** Whether this device is talking as Priority Speaker now. */
export function priorityHeld(): boolean {
  return !!session?.priorityHeld;
}

/** Scales the microphone before it reaches the peers, for the input slider. */
export function applyInputGain(): void {
  const s = session;
  if (!s?.micRaw) return;
  const volume = useSettings.getState().settings.voice.inputVolume;
  if (s.inputGain) {
    // moving back to 100% takes the extra stage out of the way again, unless RNNoise needs the graph anyway
    if (volume === 100 && !s.denoiser) void rebuildInput(s);
    else s.inputGain.gain.value = volume / 100;
    return;
  }
  if (volume !== 100) void rebuildInput(s);
}

/** Rebuilds the path from the microphone to the peers after a volume change. */
async function rebuildInput(s: Session): Promise<void> {
  const raw = s.micRaw;
  if (!raw) return;
  const gen = ++s.inputGen;
  const denoiser = s.rnnoise ? await makeDenoiser(audioContext()) : null;
  // overtaken while RNNoise was being made: a newer graph, a new microphone, or the call ended
  if (gen !== s.inputGen || session !== s || s.micRaw !== raw) {
    releaseDenoiser(denoiser);
    return;
  }
  // the meter listens to the sent stream, which is about to be replaced
  stopMeter(s);
  disconnectInput(s);
  if (s.mic && s.mic !== s.micRaw) s.mic.getTracks().forEach((t) => t.stop());
  s.mic = buildInput(s, raw, denoiser);
  s.transport.setLocalAudio(s.send.track(s.mic.getAudioTracks()[0] ?? null));
  startMeter(s);
  applyTransmit();
}

/**
 * How this call takes noise out of the microphone: by RNNoise here, by the
 * browser, or not at all. Enhanced that cannot run here (no AudioWorklet or
 * WebAssembly, or an audio engine that would not run at 48 kHz) falls back to
 * the browser's own, and says so once.
 */
async function noisePlan(s: Session): Promise<{ rnnoise: boolean; browserNoise: boolean }> {
  const mode = useSettings.getState().settings.voice.noiseMode;
  if (mode === "off") return { rnnoise: false, browserNoise: false };
  if (mode === "standard") return { rnnoise: false, browserNoise: true };
  if (await prepareDenoiser(audioContext())) return { rnnoise: true, browserNoise: false };
  if (!s.noiseNoticed && session === s) {
    s.noiseNoticed = true;
    useUI.getState().toast(tr_("voice.noise.fallback"), "info");
  }
  return { rnnoise: false, browserNoise: true };
}

/**
 * Joins a call once session.ts has checked it may and knows how calls
 * connect. `wanted` says whether this join is still the one asked for: a
 * leave, or a join elsewhere, may overtake it at any wait. Resolves true once
 * the server has been told.
 */
export async function join(channelId: string, ice: { servers: RTCIceServer[]; sfu: boolean }, wanted: () => boolean, refuse: (message: string) => void): Promise<boolean> {
  const myId = me();
  if (!myId) return false;
  if (ice.sfu) {
    if (!FrameCrypto.supported()) {
      refuse(tr_("session.thisBrowserCannotMakeEncryptedCalls"));
      return false;
    }
    // the call's frames are sealed with the conversation's key, which has to be on this device first;
    // a guest brought the call's key in its link instead
    if (!hasGuestKeys(channelId)) {
      const key = await useKeys.getState().ensureConvKey(convForChannel(channelId)).catch(() => null);
      if (!wanted()) return false;
      if (!key) {
        refuse(asGuest() ? tr_("session.thisLinkIsMissingTheCall") : tr_("guestLink.thisConversationSKeysHaveNot"));
        return false;
      }
    }
  }
  // the session the transport's events belong to; set just below, before anything can fire
  let s: Session;
  const events: TransportEvents = {
    onSignal: (to, payload) => {
      const gw = gateway();
      // Losing the socket mid-call would otherwise strand the peer silently.
      if (!gw) {
        console.warn("voice: no gateway to signal through");
        return;
      }
      gw.send("voice_signal", { to, payload });
    },
    onPeerState: (uid, state) => useVoice.getState().setPeer(uid, state),
    onRemoteStream: (uid, kind, stream) => useVoice.getState().setRemote(uid, kind, stream),
    onTooLarge: (uid, kind, on) => {
      if (session === s) useVoice.getState().setTooLarge(uid, kind, on);
    },
    onSpeaking: (uid, on) => useVoice.getState().setSpeaking(uid, on),
    onConnection: (state) => {
      if (session !== s) return;
      useVoice.getState().setConnection(state);
      // the dot goes grey at once rather than at the next measurement
      if (state !== "connected") useVoice.getState().setQuality(null);
    },
    onError: (code, message) => {
      useUI.getState().toast(message, "error");
      // near the free allowance video pauses: what was being sent is turned off, so the buttons say so,
      // and shares being watched stop waiting for a picture that is not coming
      if (code === "video_paused" && session === s) {
        if (s.camera) void toggleCamera();
        if (s.screen) void toggleScreen();
        for (const uid of Object.keys(useVoice.getState().watching)) useVoice.getState().setWatching(uid, false);
      }
    },
  };
  const transport: VoiceTransport = ice.sfu ? new SfuTransport(myId, channelId, ice.servers, events) : new MeshTransport(myId, ice.servers, events);
  s = { channelId, transport, mic: null, micRaw: null, send: new VoiceSend(), inputGain: null, inputSource: null, denoiser: null, rnnoise: false, noiseNoticed: false, inputGen: 0, meter: null, meterTimer: null, camera: null, screen: null, cameraPending: false, screenPending: false, micRetrying: false, noiseFloor: 0.01, pttHeld: false, priorityHeld: false, vadOpen: false, vadHoldUntil: 0, speaking: new SpeakingGate(), announced: false, iceTimer: null, cleanups: [] };
  session = s;
  useVoice.getState().setConnection("connecting");
  releaseCall ??= holdReload("call");
  releaseAudio ??= holdAudio();
  let mic: MediaStream | null = null;
  let denoiser: AudioNode | null = null;
  try {
    const plan = await noisePlan(s);
    s.rnnoise = plan.rnnoise;
    mic = await getMic(useSettings.getState().settings.voice, { browserNoise: plan.browserNoise });
    if (plan.rnnoise) denoiser = await makeDenoiser(audioContext());
    if (plan.rnnoise && !denoiser) {
      s.rnnoise = false;
      await mic.getAudioTracks()[0]?.applyConstraints({ noiseSuppression: true });
    }
    useVoice.getState().setMicBlocked(false);
  } catch (err) {
    // The toast is gone in five seconds and the call then looked entirely
    // normal, so the fact is held in the store for as long as it is true.
    if (session === s) {
      useUI.getState().toast(describeError(err, tr_("session.microphoneAccessWasDeniedYouCan")), "error");
      useVoice.getState().setMicBlocked(true);
    }
  }
  if (session !== s || !wanted()) {
    // left while asking for the mic: leaving already closed the transport, but
    // this capture came too late for it to stop
    mic?.getTracks().forEach((t) => t.stop());
    releaseDenoiser(denoiser);
    return false;
  }
  if (mic) {
    s.micRaw = mic;
    s.mic = buildInput(s, mic, denoiser);
    watchMic(s);
    if (lastInputChoice() === "missing") {
      // Better to say so than to carry a microphone nobody is talking into.
      useUI.getState().toast(tr_("session.theMicrophoneYouPickedIsNot"), "error");
    }
    transport.setLocalAudio(s.send.track(s.mic.getAudioTracks()[0] ?? null));
    startMeter(s);
  }
  const voiceStore = useVoice.getState();
  for (const uid of Object.keys(voiceStore.localMutes)) transport.setLocalMute(uid, true);
  for (const [uid, pct] of Object.entries(voiceStore.volumes)) transport.setVolume(uid, pct);
  for (const [uid, pct] of Object.entries(voiceStore.streamVolumes)) transport.setStreamVolume(uid, pct);
  for (const [uid, on] of Object.entries(voiceStore.streamMutes)) if (on) transport.setStreamMuted(uid, true);
  for (const uid of Object.keys(voiceStore.watching)) transport.setWatching(uid, true);
  transport.setOutputMuted(outputSilenced());
  transport.setMasterVolume(useSettings.getState().settings.voice.outputVolume);
  // Always set, "default" included: the audio output lives as long as the
  // page, so a speaker chosen in an earlier call stayed chosen after
  // switching back to Default outside one.
  const out = useSettings.getState().settings.voice.outputDeviceId;
  void transport.setOutputDevice(out ?? null);
  applyTransmit();
  s.announced = true;
  sendState(channelId);
  startQuality(s);
  startRelay(s);
  watchWhileInCall(s);
  playSound("join");
  return true;
}

/**
 * What a call watches for as long as it lasts: a key the conversation just
 * got (so frames sealed under it open at once rather than at the next check),
 * the audio engine stopping (an iPhone after a phone call), the microphone
 * permission being given after all, and fresh relay credentials.
 */
function watchWhileInCall(s: Session): void {
  // A new key reaches the key store through a grant on the gateway; the
  // transport used to find it on its own four-second check, and every frame
  // sealed under it until then was dropped here.
  s.cleanups.push(useKeys.subscribe(() => session === s && s.transport.keysArrived?.()));
  // cameras and shares come in only while the call's pictures are on screen somewhere
  s.cleanups.push(watchVideoShown(() => s.channelId, (on) => session === s && s.transport.setVideoShown?.(on)));
  const ctx = audioContext();
  let blockedTimer: ReturnType<typeof setTimeout> | null = null;
  const onState = () => {
    if (blockedTimer) clearTimeout(blockedTimer);
    blockedTimer = null;
    if (ctx.state === "running") {
      useVoice.getState().setAudioBlocked(false);
      return;
    }
    // a context that is only starting up is not waiting for anybody
    blockedTimer = setTimeout(() => {
      if (session === s && ctx.state !== "running") useVoice.getState().setAudioBlocked(true);
    }, AUDIO_BLOCKED_AFTER_MS);
  };
  ctx.addEventListener("statechange", onState);
  onState();
  s.cleanups.push(() => {
    ctx.removeEventListener("statechange", onState);
    if (blockedTimer) clearTimeout(blockedTimer);
  });
  // granting the microphone in the site settings mid-call brings it in, where the browser says so
  void navigator.permissions
    ?.query({ name: "microphone" as PermissionName })
    .then((st) => {
      const onChange = () => {
        if (session === s && st.state === "granted" && useVoice.getState().micBlocked) void retryMic(true);
      };
      st.addEventListener("change", onChange);
      s.cleanups.push(() => st.removeEventListener("change", onChange));
    })
    .catch(() => {});
  s.iceTimer = setInterval(() => {
    void iceConfig().then((ice) => {
      if (ice && session === s) s.transport.setIceServers?.(ice.servers);
    });
  }, ICE_REFRESH_MS);
}

/** Ends this device's call. Tells the server unless `notify` is off or it was never told. */
export function leave(notify: boolean): void {
  const s = session;
  session = null;
  releaseCall?.();
  releaseCall = null;
  releaseAudio?.();
  releaseAudio = null;
  stopQuality();
  stopRelay();
  if (!s) return;
  for (const undo of s.cleanups) undo();
  if (s.iceTimer) clearInterval(s.iceTimer);
  stopMeter(s);
  disconnectInput(s);
  s.transport.close();
  s.send.stop();
  s.mic?.getTracks().forEach((t) => t.stop());
  s.micRaw?.getTracks().forEach((t) => t.stop());
  s.camera?.getTracks().forEach((t) => t.stop());
  stopScreen(s.screen ?? undefined);
  if (notify && s.announced) sendState(null);
  playSound("leave");
}

/** The channel of this device's call, and whether the server was told about it. */
export function current(): { channelId: string; announced: boolean } | null {
  return session ? { channelId: session.channelId, announced: session.announced } : null;
}

/** Whether this call goes through the SFU rather than peer to peer. */
export function throughSfu(): boolean {
  return session?.transport instanceof SfuTransport;
}

/** Live link quality for the indicator. */
export function stats(): Promise<{ rtt: number; loss: number }> | null {
  return session ? session.transport.stats() : null;
}

/**
 * Routes the capture through RNNoise, when it is given one, and a gain node
 * so the input slider does something. At 100% with no RNNoise the
 * microphone's own track is sent instead: a graph that produces silence on
 * some devices is not worth inserting for no change in level.
 */
function buildInput(s: Session, raw: MediaStream, denoiser: AudioNode | null): MediaStream {
  const { stream, gain, source } = sentStream(audioContext(), raw, useSettings.getState().settings.voice.inputVolume, denoiser);
  // a graph that could not be built sends the microphone as it is
  if (denoiser && !source) releaseDenoiser(denoiser);
  s.inputGain = gain;
  s.inputSource = source;
  s.denoiser = source ? denoiser : null;
  return stream;
}

/**
 * Watches the microphone itself rather than the copy the peers get.
 *
 * Pulling a headset out ends the device's track. The level meter then reads
 * silence, voice activity closes the gate, and nobody hears you again for the
 * rest of the call while the button still says "Mute". The gain-staged copy is
 * a destination node's track and never ends, so it cannot say so.
 */
function watchMic(s: Session): void {
  for (const t of s.micRaw?.getAudioTracks() ?? []) {
    t.onended = () => {
      if (session === s) void refreshMic();
    };
  }
}

/** Lets go of the slider's audio graph, which would otherwise stay wired to the live microphone. */
function disconnectInput(s: Session): void {
  try {
    s.inputSource?.disconnect();
    s.inputGain?.disconnect();
  } catch {
    /* already gone */
  }
  releaseDenoiser(s.denoiser);
  s.inputSource = null;
  s.inputGain = null;
  s.denoiser = null;
}

function startMeter(s: Session): void {
  if (!s.mic) return;
  const ctx = audioContext();
  s.meter = new LevelMeter(ctx, s.mic);
  const myId = me() ?? "";
  let tick = 0;
  s.meterTimer = setInterval(() => {
    if (!s.meter) return;
    const level = s.meter.level();
    if (tick++ % LEVEL_EVERY === 0) useVoice.getState().setLevel(level);
    const v = useSettings.getState().settings.voice;
    let threshold: number;
    if (v.autoSensitivity) {
      s.noiseFloor = level < s.noiseFloor ? level : s.noiseFloor * 0.995 + level * 0.005;
      threshold = Math.max(0.02, s.noiseFloor * 2.5);
    } else threshold = sensitivityToThreshold(v.sensitivity);
    const now = Date.now();
    if (level > threshold) s.vadHoldUntil = now + VAD_HOLD_MS;
    // A stopped audio engine reads silence, which must not shut the mic.
    const open = ctx.state !== "running" || now < s.vadHoldUntil;
    if (open !== s.vadOpen && session === s) {
      s.vadOpen = open;
      applyTransmit();
    }
    // The ring says this device's sound is going out: the microphone let
    // through (unmuted, and voice activity or push to talk open) with
    // something in it to hear. Everyone else's ring for it goes by the same
    // level of what arrives (playback.ts). It holds through the gaps between
    // words, and a microphone shut or muted puts it out at once.
    const sending = shouldTransmit() && gateOpen();
    const loud = s.speaking.update(sending && level > AUDIBLE_LEVEL, now);
    useVoice.getState().setSpeaking(myId, sending && loud);
  }, VAD_EVERY_MS);
}

function stopMeter(s: Session): void {
  if (s.meterTimer) clearInterval(s.meterTimer);
  s.meter?.close();
  s.meter = null;
  s.meterTimer = null;
  s.vadOpen = false;
  s.vadHoldUntil = 0;
  s.speaking.reset();
}

/**
 * Opens the microphone again and puts it in the call in place of whatever was
 * there. `failure` is what to say when that does not work; null says nothing.
 */
async function swapMic(s: Session, failure: string | null): Promise<void> {
  try {
    const plan = await noisePlan(s);
    const next = await getMic(useSettings.getState().settings.voice, { browserNoise: plan.browserNoise });
    const denoiser = plan.rnnoise ? await makeDenoiser(audioContext()) : null;
    if (plan.rnnoise && !denoiser) await next.getAudioTracks()[0]?.applyConstraints({ noiseSuppression: true });
    if (session !== s) {
      next.getTracks().forEach((t) => t.stop());
      releaseDenoiser(denoiser);
      return;
    }
    // a volume change still building its graph is overtaken by this one
    s.inputGen++;
    s.rnnoise = plan.rnnoise && !!denoiser;
    stopMeter(s);
    disconnectInput(s);
    s.mic?.getTracks().forEach((t) => t.stop());
    s.micRaw?.getTracks().forEach((t) => {
      t.onended = null;
      t.stop();
    });
    s.micRaw = next;
    s.mic = buildInput(s, next, denoiser);
    watchMic(s);
    s.transport.setLocalAudio(s.send.track(s.mic.getAudioTracks()[0] ?? null));
    startMeter(s);
    applyTransmit();
    // a microphone that works again is no longer "blocked": the button and
    // the note said so while every word was going out
    useVoice.getState().setMicBlocked(false);
  } catch (err) {
    if (failure !== null && session === s) useUI.getState().toast(describeError(err, failure), "error");
    // and one that is gone is: the button read "Mute" while nothing was sent
    if (session === s && !s.micRaw?.getAudioTracks().some((t) => t.readyState === "live")) useVoice.getState().setMicBlocked(true);
  }
}

/** Swap the microphone after a device or processing setting change. */
export async function refreshMic(): Promise<void> {
  const s = session;
  if (s) await swapMic(s, tr_("session.couldNotSwitchTheMicrophone"));
}

/**
 * Asks for the microphone again after it was refused or busy: from the call's
 * "Try again", its mute button, a microphone plugged in, or the permission
 * given in the browser. `quiet` says nothing when it still does not work.
 */
export async function retryMic(quiet = false): Promise<void> {
  const s = session;
  if (!s || s.micRetrying) return;
  s.micRetrying = true;
  try {
    await swapMic(s, quiet ? null : tr_("session.microphoneAccessWasDeniedYouCan"));
  } finally {
    s.micRetrying = false;
  }
}

/** A device came or went: a microphone that ended, or one that was never there, is looked for again. */
export function devicesChanged(): void {
  const s = session;
  if (!s) return;
  if (s.micRaw?.getAudioTracks().some((t) => t.readyState === "ended")) void refreshMic();
  else if (!s.micRaw && useVoice.getState().micBlocked) void retryMic(true);
}

/** Which way the camera points; a phone can turn it round mid-call. */
let cameraFacing: CameraFacing = "user";

/** The camera chosen in settings. */
function chosenCamera(): { id: string | null; label: string | null } {
  const v = useSettings.getState().settings.voice;
  return { id: v.cameraDeviceId ?? null, label: v.cameraDeviceLabel ?? null };
}

/**
 * Opens the camera chosen in settings in place of the one that is on, so the
 * other side sees the picture change without renegotiating.
 */
export async function switchCamera(): Promise<void> {
  const s = session;
  if (!s?.camera || s.cameraPending) return;
  const old = s.camera;
  s.cameraPending = true;
  // The replacement is opened before the working camera is let go. Stopping
  // first froze this tile and everyone else's on the last frame for as long as
  // the browser took, and left the camera off altogether when the new one was
  // busy. flipCamera keeps the stop-first order, for the phones its own
  // comment describes.
  const stream = await getCamera(cameraFacing, chosenCamera()).catch(() => null);
  s.cameraPending = false;
  if (session !== s) {
    stream?.getTracks().forEach((t) => t.stop());
    return;
  }
  if (!stream) {
    useUI.getState().toast(tr_("session.couldNotSwitchTheCamera"), "error");
    return;
  }
  for (const t of old.getTracks()) {
    t.onended = null;
    t.stop();
  }
  s.camera = stream;
  const track = stream.getVideoTracks()[0] ?? null;
  if (track) track.onended = () => void toggleCamera();
  s.transport.setLocalVideo(track);
  useVoice.getState().setLocalVideo(stream);
}

/**
 * Turns the camera round. The new stream replaces the old one in place, so
 * the other side sees the picture change without renegotiating.
 */
export async function flipCamera(): Promise<void> {
  const s = session;
  if (!s?.camera || s.cameraPending) return;
  const next: CameraFacing = cameraFacing === "user" ? "environment" : "user";
  // Many phones cannot open a second camera while the first is live, so the
  // old one is let go before asking for the other. Its ended handler would
  // otherwise turn the camera off halfway through.
  const old = s.camera;
  for (const t of old.getTracks()) {
    t.onended = null;
    t.stop();
  }
  s.cameraPending = true;
  let stream: MediaStream | null = null;
  let facing = next;
  try {
    stream = await getCamera(next);
  } catch {
    facing = cameraFacing;
    stream = await getCamera(cameraFacing).catch(() => null);
    if (stream && session === s) useUI.getState().toast(tr_("session.thisDeviceHasNoOtherCamera"), "error");
  }
  s.cameraPending = false;
  if (session !== s) {
    stream?.getTracks().forEach((t) => t.stop());
    return;
  }
  if (!stream) {
    s.camera = null;
    s.transport.setLocalVideo(null);
    useVoice.getState().setLocalVideo(null);
    sendState(s.channelId);
    useUI.getState().toast(tr_("session.couldNotSwitchTheCamera"), "error");
    return;
  }
  cameraFacing = facing;
  s.camera = stream;
  const track = stream.getVideoTracks()[0] ?? null;
  if (track) track.onended = () => void toggleCamera();
  s.transport.setLocalVideo(track);
  useVoice.getState().setLocalVideo(stream);
}

export async function toggleCamera(): Promise<void> {
  const s = session;
  if (!s || s.cameraPending) return;
  if (s.camera) {
    s.camera.getTracks().forEach((t) => t.stop());
    s.camera = null;
    s.transport.setLocalVideo(null);
    useVoice.getState().setLocalVideo(null);
  } else {
    s.cameraPending = true;
    let stream: MediaStream;
    try {
      stream = await getCamera(cameraFacing, chosenCamera());
    } catch {
      s.cameraPending = false;
      if (session === s) useUI.getState().toast(tr_("session.cameraAccessWasDenied"), "error");
      return;
    }
    s.cameraPending = false;
    if (session !== s) {
      // hung up while the camera was opening
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    s.camera = stream;
    const track = stream.getVideoTracks()[0] ?? null;
    if (track) track.onended = () => void toggleCamera();
    s.transport.setLocalVideo(track);
    useVoice.getState().setLocalVideo(stream);
  }
  sendState(s.channelId);
}

/** Stops a share, or starts one at the given size and frame rate (the last ones chosen when not given). */
export async function toggleScreen(quality?: { plan: SharePlan; audio?: boolean }): Promise<void> {
  const s = session;
  if (!s || s.screenPending) return;
  if (s.screen) {
    stopScreen(s.screen);
    s.screen = null;
    s.transport.setLocalScreen(null);
    useVoice.getState().setLocalScreen(null);
  } else {
    const v = useSettings.getState().settings.voice;
    const plan = quality?.plan ?? sharePlan(v, shareAllowance());
    const audio = quality?.audio ?? v.shareAudio ?? true;
    s.screenPending = true;
    let stream: MediaStream | null = null;
    try {
      stream = await getScreen(plan, audio);
    } catch (err) {
      // Cancelling the picker rejects with NotAllowedError. A share whose sound could not start
      // rejects as a whole: the desktop app then falls back to safer sound and shares the same
      // source again, without asking, and the share goes ahead.
      const soundFailed = audio && err instanceof DOMException && (err.name === "NotReadableError" || err.name === "AbortError");
      const again = soundFailed ? await desktopBridge()?.shareAudioFailed?.().catch(() => false) : false;
      if (again && session === s) stream = await getScreen(plan, audio).catch(() => null);
      if (!stream && session === s) {
        // A share that will not start is usually the computer refusing to let
        // the app record at all; blaming the sound sends people the wrong way.
        const access = await desktopBridge()?.screenAccess?.().catch(() => null);
        if (access && !access.usable) useUI.getState().openModal("screenPermission", { stale: access.stale });
        else if (soundFailed) useUI.getState().toast(tr_("session.yourScreenShareCouldNotStart"), "error");
      }
    }
    s.screenPending = false;
    if (!stream) return;
    if (session !== s) {
      // hung up while the picker was open
      stopScreen(stream);
      return;
    }
    s.screen = stream;
    const video = stream.getVideoTracks()[0];
    if (video) video.onended = () => void toggleScreen();
    s.transport.setLocalScreen(stream, plan.bitrate, plan.fps, plan.smooth);
    useVoice.getState().setLocalScreen(stream);
  }
  sendState(s.channelId);
}

/** Whether a share is running from this device. */
export function sharing(): boolean {
  return !!session?.screen;
}

export function setVolume(userId: string, pct: number): void {
  session?.transport.setVolume(userId, pct);
}

export function setStreamVolume(userId: string, pct: number): void {
  session?.transport.setStreamVolume(userId, pct);
}

export function setStreamMuted(userId: string, on: boolean): void {
  session?.transport.setStreamMuted(userId, on);
}

export function setLocalMute(userId: string, on: boolean): void {
  session?.transport.setLocalMute(userId, on);
}

export function setOutputMuted(on: boolean): void {
  session?.transport.setOutputMuted(on);
}

export function setMasterVolume(pct: number): void {
  session?.transport.setMasterVolume(pct);
}

export function setOutputDevice(deviceId: string | null): void {
  void session?.transport.setOutputDevice(deviceId);
}

export function setWatching(userId: string, on: boolean): void {
  session?.transport.setWatching(userId, on);
}

/** The network looks back: whatever is down tries again now. */
export function nudge(): void {
  session?.transport.nudge?.();
}

// --- gateway events --------------------------------------------------------

function syncPeers(): void {
  const s = session;
  const myId = me();
  if (!s || !myId) return;
  const everyone = participantsOf(useVoice.getState().byChannel, s.channelId);
  const wanted = new Set(everyone.map((p) => p.user_id).filter((id) => id !== myId));
  for (const id of s.transport.peerIds()) if (!wanted.has(id)) s.transport.removePeer(id), useVoice.getState().removePeer(id);
  syncModMutes(s, everyone);
}

/** Whoever a moderator muted or deafened stays silent here (a channel that denies Speak is the same). */
function syncModMutes(s: Session, everyone: VoiceState[]): void {
  const myId = me();
  for (const p of everyone) {
    if (p.user_id === myId) continue;
    s.transport.setModMuted(p.user_id, p.server_mute || p.server_deaf);
    s.transport.setPriority(p.user_id, !!p.priority_speaker);
  }
}

/**
 * The server acknowledged this device in its call, maybe in another channel
 * (a moderator moved it). Returns false when the state is an echo of a call
 * already left, which the caller ignores.
 */
export function onMine(vs: VoiceState): boolean {
  const s = session;
  if (!s || !vs.channel_id) return false;
  const store = useVoice.getState();
  // A state for another channel before this call was ever confirmed is an
  // echo of the call just left (its join, a mute, a published track),
  // arriving after the switch — not a moderator's move. Taken for one, it
  // pulled the call back to the old channel, and the old channel's leave that
  // followed ended it.
  if (s.channelId !== vs.channel_id && store.mine?.channel_id !== s.channelId) return false;
  store.setMine(vs);
  store.setJoining(null);
  if (s.channelId !== vs.channel_id) {
    // moved by a moderator: rebuild peers for the new room
    for (const id of s.transport.peerIds()) s.transport.removePeer(id), store.removePeer(id);
    s.channelId = vs.channel_id;
    s.transport.moveTo(vs.channel_id);
    // whether the call's pictures are on screen is now a question about the new channel
    recheckVideoShown();
    // An encrypted call needs the new room's key here. Until it lands the
    // transport has none, and every frame it sends is dropped, so it is told
    // the moment the fetch resolves rather than on its own check.
    void useKeys
      .getState()
      .ensureConvKey(convForChannel(vs.channel_id))
      .then(() => {
        if (session === s) s.transport.keysArrived?.();
      })
      .catch(() => {});
  }
  const everyone = participantsOf(useVoice.getState().byChannel, vs.channel_id);
  for (const p of everyone) {
    if (p.user_id !== me()) s.transport.addPeer(p.user_id);
  }
  s.transport.setRoster(everyone);
  s.transport.joined(vs);
  syncModMutes(s, everyone);
  s.transport.setOutputMuted(outputSilenced());
  applyTransmit();
  relayGuestKeys(s);
  return true;
}

/**
 * Someone else's state changed. Returns "joined" when they came into this
 * call and "left" when they went, for the sounds and the ringing.
 */
export function onOther(vs: VoiceState, before: string | null): "joined" | "left" | null {
  const s = session;
  if (!s) return null;
  let what: "joined" | "left" | null = null;
  if (vs.channel_id === s.channelId) {
    if (!s.transport.peerIds().includes(vs.user_id)) {
      s.transport.addPeer(vs.user_id);
      what = "joined";
    }
  } else if (before === s.channelId || vs.old_channel_id === s.channelId) {
    s.transport.removePeer(vs.user_id);
    useVoice.getState().removePeer(vs.user_id);
    what = "left";
  }
  syncPeers();
  // cameras, shares and what people publish change without anyone joining or leaving
  s.transport.setRoster(participantsOf(useVoice.getState().byChannel, s.channelId));
  relayGuestKeys(s);
  return what;
}

export function onSignal(e: Extract<GatewayEvent, { t: "voice_signal" }>): void {
  const s = session;
  const myId = me();
  const payload = e.d.payload as { guest_keys?: unknown } | null;
  if (payload && typeof payload === "object" && "guest_keys" in payload) {
    // newer call keys for a guest, from someone in the call; another guest has none to give
    const from = s && e.d.channel_id === s.channelId ? useVoice.getState().byChannel[e.d.channel_id]?.[e.d.from] : undefined;
    const fromGuest = !!from?.guest || ((from?.user?.flags ?? 0) & GUEST_FLAG) !== 0;
    if (from && !fromGuest && asGuest() && myId) void openRelayedKeys(e.d.channel_id, myId, payload.guest_keys);
    return;
  }
  if (s && e.d.channel_id === s.channelId) s.transport.handleSignal(e.d.from, e.d.payload as SignalPayload);
}

/** The server let a call go while the gateway was away: it starts over as after any reconnect. */
export function onResumed(e: Extract<GatewayEvent, { t: "voice_resumed" }>): void {
  const s = session;
  if (!s || e.d.kept || s.channelId !== e.d.channel_id) return;
  const store = useVoice.getState();
  for (const id of s.transport.peerIds()) s.transport.removePeer(id), store.removePeer(id);
  store.setMine(null);
  s.transport.reconnected();
}

/** The gateway reconnected: rejoin the room this device was in. */
export function onReady(): void {
  const s = session;
  // a join still asking for the mic announces itself when it gets there
  if (!s?.announced) return;
  // Media through the SFU never went through the gateway: the call carries on while it reconnects,
  // and the server kept it for this device. Ask for it back as it is; voice_resumed says whether it was.
  const held = s.transport.currentSession?.() ?? null;
  if (held) {
    syncPeers();
    const everyone = participantsOf(useVoice.getState().byChannel, s.channelId);
    for (const p of everyone) if (p.user_id !== me()) s.transport.addPeer(p.user_id);
    s.transport.setRoster(everyone);
    sendState(s.channelId, true, held);
    // the network is back, so a connection that dropped with it tries again now
    s.transport.nudge?.();
    return;
  }
  for (const id of s.transport.peerIds()) s.transport.removePeer(id), useVoice.getState().removePeer(id);
  useVoice.getState().setMine(null);
  s.transport.reconnected();
  sendState(s.channelId, true);
}
