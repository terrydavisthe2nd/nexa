import { isDemo } from "@/lib/demoMode";
/**
 * Calls, as the rest of the app sees them: joining and leaving, incoming calls
 * ringing, the calling tone and what came of a call, and the voice states
 * everyone's devices report. The call itself — media, the transports, frame
 * encryption, playback — is engine.ts, loaded the first time this page joins
 * a call, so a page that never calls never downloads it.
 */
import { users as usersApi } from "@/api/users";
import { voice as voiceApi } from "@/api/voice";
import { GUEST_FLAG, type PublicUser, type VoiceState } from "@/api/types";
import { dispatchHooks } from "@/gateway/dispatch";
import type { GatewayEvent } from "@/gateway/events";
import { gateway } from "@/gateway/session";
import { holdCallLock, releaseCallLock } from "@/lib/backgroundLock";
import { doNotDisturb } from "@/lib/dnd";
import { mutedSentence } from "@/lib/moderation";
import { playSound, startCalling, startRinging, stopCalling, stopRinging } from "@/lib/sounds";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { activeMute, useNexa } from "@/stores/nexa";
import { usePresence } from "@/stores/presence";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { displayName, useUsers } from "@/stores/users";
import { participantsOf, useVoice } from "@/stores/voice";
import { useVoiceLocal } from "@/stores/voiceLocal";
import { closeRingNotice, missedNotice, ringNotice } from "./callNotice";
import { allDeclined, callDeclined, callEnded, callLine, callStarted, callTimedOut, callsAfterReady, installCallRecord } from "./callRecord";
import { audioContext, resumeAudio } from "./media";
import type { SharePlan } from "./shareQuality";
import { canReceiveH264 } from "./videoCap";
import { allowsVoiceActivity, mayBePriority, mayStream, pushToTalkOn } from "./callPerms";
import { useServers } from "@/stores/servers";

import { t as tr_ } from "@/i18n";

type Engine = typeof import("./engine");

/** The call engine once loaded; null until this page first joins a call. */
let engine: Engine | null = null;
let engineLoading: Promise<Engine> | null = null;

function loadEngine(): Promise<Engine> {
  if (engine) return Promise.resolve(engine);
  engineLoading ??= import("./engine").then(
    (m) => (engine = m),
    (err: unknown) => {
      // offline at the first call: the next try loads it again
      engineLoading = null;
      throw err;
    },
  );
  return engineLoading;
}

let iceCache: { servers: RTCIceServer[]; sfu: boolean; guests: boolean; at: number } | null = null;
/** Bumped by every join and leave, so a join still waiting on the network can tell it was overtaken. */
let joinSeq = 0;
/** This connection's gateway session, to tell this device's call from the same account's other devices. */
let mySessionId: string | null = null;
let installed = false;
const ringTimers = new Map<string, ReturnType<typeof setTimeout>>();
/** Stops the outgoing tone once the people called have been rung for as long as they are. */
let callingTimer: ReturnType<typeof setTimeout> | null = null;
/** How long a call rings, here and on the other side. */
const RING_MS = 60_000;
/** Whether anyone else has been in this device's call since it joined. */
let company = false;
/** Hangs up a conversation's call everyone else left, unless someone comes back. */
let aloneTimer: ReturnType<typeof setTimeout> | null = null;
const ALONE_MS = 60_000;

/** Relay credentials expire, so the list is fetched again after this long. */
const ICE_CACHE_MS = 30 * 60 * 1000;
/** Refusals of a voice_signal: they race people leaving and reconnects, and say nothing about our own place in the call. */
const SIGNAL_ERRORS = new Set(["not_in_voice", "peer_gone", "too_large"]);
/** The server connects calls the other way than this page thought (turned on or off since it asked). */
const MODE_ERRORS = new Set(["sfu_required", "sfu_off"]);
/** When this page last joined again after such a refusal; a second one soon after is shown instead. */
let modeRetryAt = 0;

export function me(): string | undefined {
  return useAuth.getState().me?.id;
}

/** Whether this page is a guest's, in a call through a guest link, rather than an account's. */
export function asGuest(): boolean {
  return ((useAuth.getState().me?.flags ?? 0) & GUEST_FLAG) !== 0;
}

/** The accounts in a call other than this one; a guest waiting in it is nobody picking up. */
function othersIn(channelId: string): number {
  const myId = me();
  return participantsOf(useVoice.getState().byChannel, channelId).filter((p) => p.user_id !== myId && !p.guest && ((p.user?.flags ?? 0) & GUEST_FLAG) === 0).length;
}

function isConversation(channelId: string): boolean {
  const type = useChannels.getState().byId[channelId]?.type;
  return type === "dm" || type === "group_dm";
}

/**
 * Calling a DM or group nobody else is in yet: the outgoing tone plays, and
 * the call says who it is calling, until someone picks up. A minute with no
 * answer gives the call up, as a phone does, and says so.
 */
function startCallingTone(channelId: string): void {
  startCalling();
  useVoice.getState().setCalling(channelId);
  if (callingTimer) clearTimeout(callingTimer);
  callingTimer = setTimeout(() => {
    callingTimer = null;
    stopCallingTone();
    callTimedOut(channelId);
    if (inVoice() === channelId && othersIn(channelId) === 0) {
      useUI.getState().toast(callOutcome(channelId, "noAnswer"), "info");
      void leaveVoice();
    }
  }, RING_MS);
}

function stopCallingTone(): void {
  if (callingTimer) clearTimeout(callingTimer);
  callingTimer = null;
  stopCalling();
  useVoice.getState().setCalling(null);
}

/** What became of a call nobody picked up, in words: a DM names the person called. */
function callOutcome(channelId: string, what: "noAnswer" | "declined"): string {
  const ch = useChannels.getState().byId[channelId];
  const other = ch?.type === "dm" ? ch.recipient_ids.find((id) => id !== me()) : undefined;
  const name = other ? displayName(useUsers.getState().byId[other]) : "";
  if (what === "declined") return tr_("voice.call.declined", { name });
  return name ? tr_("voice.call.noAnswer", { name }) : tr_("voice.call.noAnswerGroup");
}

/**
 * A conversation's call that people talked in and everyone else then left
 * waits a minute for them to come back (a dropped phone, a crashed app), then
 * hangs up by itself instead of sitting open and silent. A server's voice
 * channel is a place to wait in and never does.
 */
function checkAlone(channelId: string): void {
  if (!isConversation(channelId)) return;
  if (othersIn(channelId) > 0) {
    company = true;
    stopAloneTimer();
    return;
  }
  if (!company || aloneTimer) return;
  useVoice.getState().setLeftAlone(channelId);
  aloneTimer = setTimeout(() => {
    aloneTimer = null;
    if (inVoice() !== channelId || othersIn(channelId) > 0) return;
    useUI.getState().toast(tr_("voice.call.callEnded"), "info");
    void leaveVoice();
  }, ALONE_MS);
}

function stopAloneTimer(): void {
  if (aloneTimer) clearTimeout(aloneTimer);
  aloneTimer = null;
  if (useVoice.getState().leftAlone) useVoice.getState().setLeftAlone(null);
}

const BLANK_USER: Omit<PublicUser, "id"> = { username: "", display_name: "", avatar_id: null, banner_id: null, about_me: "", pronouns: "", accent_color: null, accent_color_2: null, status_text: "", status_emoji: "", status_expires_at: null, public_key: "", flags: 0, created_at: 0 };

/** Accounts in a call this page is fetching the profile of. */
const fetchingCallUsers = new Set<string>();

/**
 * Keeps whoever is in a call known by name. A guest is known no other way,
 * and a guest's own page knows nobody else, so those take what the call says.
 * An account's page fetches the real profile of an account it does not know
 * yet, rather than keep a stand-in that would stop the profile ever loading.
 */
function rememberCallUser(vs: VoiceState): void {
  const u = vs.user;
  if (!u || !vs.channel_id) return;
  const users = useUsers.getState();
  const known = users.byId[u.id];
  if (!asGuest() && (u.flags & GUEST_FLAG) === 0) {
    if (known || fetchingCallUsers.has(u.id)) return;
    fetchingCallUsers.add(u.id);
    void usersApi
      .get(u.id)
      .then((full) => useUsers.getState().upsert(full))
      .catch(() => {})
      .finally(() => fetchingCallUsers.delete(u.id));
    return;
  }
  if (known && known.display_name === u.display_name && known.avatar_id === u.avatar_id && known.flags === u.flags) return;
  users.upsert({ ...BLANK_USER, ...known, id: u.id, display_name: u.display_name, avatar_id: u.avatar_id, flags: u.flags });
}

/**
 * `resume` marks a rejoin after a reconnect, so the server does not treat it
 * as a new call; with `sfuSession` it asks for the very call this device
 * still holds, which the server keeps a while for it.
 */
export function sendState(channelId: string | null, resume = false, sfuSession?: string): void {
  const local = useVoiceLocal.getState();
  const st = useVoice.getState();
  gateway()?.send("voice_state", {
    channel_id: channelId,
    self_mute: local.muted,
    self_deaf: local.deafened,
    video: !!st.localVideo,
    streaming: !!st.localScreen,
    ...(channelId && engine?.priorityHeld() ? { priority: true } : {}),
    // how this device connects, so the server can turn away a page set up for the other kind
    ...(channelId ? { sfu: !!engine?.throughSfu() } : {}),
    // what it can watch besides VP8: a phone shares its screen in H.264 only when everyone can
    ...(channelId && canReceiveH264() ? { codecs: ["h264"] } : {}),
    ...(resume ? { resume: true } : {}),
    ...(resume && sfuSession ? { sfu_session: sfuSession } : {}),
  });
}

/**
 * The ICE servers, and whether calls go through Cloudflare's SFU on this
 * server. Fetched again once the relay credentials in hand are half an hour
 * old; a call asks again now and then for its reconnects (engine.ts).
 */
export async function iceConfig(): Promise<{ servers: RTCIceServer[]; sfu: boolean } | null> {
  if (iceCache && Date.now() - iceCache.at < ICE_CACHE_MS) return iceCache;
  try {
    const res = await voiceApi.ice();
    iceCache = { servers: res.ice_servers as RTCIceServer[], sfu: !!res.sfu, guests: res.guests !== false, at: Date.now() };
    return iceCache;
  } catch {
    // Not kept, and not guessed: a server that sends calls through the SFU
    // must not have one go peer to peer because this request failed.
    return null;
  }
}

/** A touch screen with no hover has no key to hold: push to talk there is a button in the call. */
export function noKeyToHold(): boolean {
  return typeof matchMedia === "function" && matchMedia("(hover: none) and (pointer: coarse)").matches;
}

/** Whether the call shows a button to hold for push to talk, on a device with no key for it. */
export function pushToTalkButton(): boolean {
  return pushToTalkOn() && noKeyToHold();
}

/** Join a voice channel (or DM call). Leaves the current one first. */
export async function joinVoice(channelId: string): Promise<void> {
  if (isDemo) { window.dispatchEvent(new Event("nexa-demo-account")); return; }
  const myId = me();
  if (!myId) return;
  // A muted account is in no call; the server refuses the join as well, but
  // saying so here, in the reader's language, beats a refusal after a ring.
  const mute = activeMute(useNexa.getState().state);
  if (mute) {
    useUI.getState().toast(mutedSentence(mute), "error");
    return;
  }
  // Made here, inside the tap, because iOS only lets audio start in a gesture
  // and nothing else in a call without a microphone would get it running.
  audioContext();
  const store = useVoice.getState();
  if (inVoice() === channelId) return;
  // Moving says so first: if the new channel refuses us, the server must not
  // keep us sitting in the old one.
  if (inVoice()) await leaveVoice(true);
  const seq = ++joinSeq;
  store.setError(null);
  store.setJoining(channelId);
  clearRing(channelId, false);
  const refuse = (message: string) => {
    store.setJoining(null);
    store.setError(message);
    useUI.getState().toast(message, "error");
  };
  const [loaded, ice] = await Promise.all([loadEngine().catch(() => null), iceConfig()]);
  if (seq !== joinSeq) return; // disconnected, or joined elsewhere, while fetching
  if (!loaded || !ice) return refuse(tr_("session.couldNotReachTheServerTo"));
  company = false;
  stopAloneTimer();
  const announced = await loaded.join(channelId, ice, () => seq === joinSeq, refuse);
  if (!announced || seq !== joinSeq) return;
  holdCallLock();
  // a channel without Use Voice Activity is push to talk only, whatever the settings say
  const voiceSettings = useSettings.getState().settings.voice;
  if (voiceSettings.mode !== "ptt" && !allowsVoiceActivity(channelId)) {
    useUI.getState().toast(noKeyToHold() ? tr_("voice.callPerms.pttRequiredButton") : tr_("voice.callPerms.pttRequired", { key: voiceSettings.pttKey }), "info");
  }
  if (isConversation(channelId) && othersIn(channelId) === 0) {
    startCallingTone(channelId);
    callStarted(channelId);
  } else checkAlone(channelId);
}

/** Leave voice; tells the server unless the connection is already gone. */
export async function leaveVoice(notify = true): Promise<void> {
  joinSeq++;
  releaseCallLock();
  const was = engine?.current() ?? null;
  engine?.leave(notify);
  if (was) stopCallingTone();
  stopAloneTimer();
  useVoice.getState().resetSession();
}

/** Swap the microphone after a device or processing setting change. */
export function refreshMic(): Promise<void> {
  return engine?.refreshMic() ?? Promise.resolve();
}

/** Asks for a microphone that was refused or busy again, from the call's "Try again". */
export function retryMic(): Promise<void> {
  return engine?.retryMic() ?? Promise.resolve();
}

/** Turns the camera round on a phone. */
export function flipCamera(): Promise<void> {
  return engine?.flipCamera() ?? Promise.resolve();
}

/** Called straight from a click: iOS only opens the camera in a gesture. */
export function toggleCamera(): Promise<void> {
  if (!useVoice.getState().localVideo && !streamAllowed()) return Promise.resolve();
  return engine?.toggleCamera() ?? Promise.resolve();
}

/** Whether the call this device is in lets it start a camera or a share; says why not when it does not. */
function streamAllowed(): boolean {
  const cur = engine?.current();
  if (!cur || mayStream(cur.channelId)) return true;
  useUI.getState().toast(tr_("voice.callPerms.noVideo"), "error");
  return false;
}

/**
 * Starts a share at the given size and frame rate (the last ones chosen when
 * not given), unless one is already running. Call it straight from a click:
 * the browser only opens its screen picker during a user gesture.
 */
export function startScreen(quality?: { plan: SharePlan; audio?: boolean }): Promise<void> {
  if (!engine || engine.sharing() || !streamAllowed()) return Promise.resolve();
  return engine.toggleScreen(quality);
}

/** Stops a share, or starts one the way startScreen does. */
export function toggleScreen(quality?: { plan: SharePlan; audio?: boolean }): Promise<void> {
  if (!engine?.sharing() && !streamAllowed()) return Promise.resolve();
  return engine?.toggleScreen(quality) ?? Promise.resolve();
}

/** Push to talk's button in the call, pressed or let go. */
export function holdToTalk(on: boolean): void {
  engine?.setPttHeld(on);
}

/** The "tap to turn the sound on" of a call whose audio the system stopped. */
export function resumeCallAudio(): void {
  resumeAudio();
}

export function setUserVolume(userId: string, pct: number): void {
  useVoice.getState().setVolume(userId, pct);
  engine?.setVolume(userId, pct);
}

/** The volume of someone's screen share sound, leaving their voice as it is. */
export function setStreamVolume(userId: string, pct: number): void {
  useVoice.getState().setStreamVolume(userId, pct);
  engine?.setStreamVolume(userId, pct);
}

export function setStreamLocalMute(userId: string, on: boolean): void {
  useVoice.getState().setStreamMute(userId, on);
  engine?.setStreamMuted(userId, on);
}

export function setUserLocalMute(userId: string, on: boolean): void {
  useVoice.getState().setLocalMute(userId, on);
  engine?.setLocalMute(userId, on);
}

export function inVoice(): string | null {
  return engine?.current()?.channelId ?? null;
}

/** Whether this server takes guests in calls; null before the call's settings arrived. */
export function guestLinksOn(): boolean | null {
  return iceCache ? iceCache.guests : null;
}

/** Whether this call goes through the SFU rather than peer to peer. */
export function callThroughSfu(): boolean {
  return !!engine?.throughSfu();
}

/** Live link quality for the indicator. */
export function voiceStats(): Promise<{ rtt: number; loss: number }> | null {
  return engine?.stats() ?? null;
}

// --- gateway events --------------------------------------------------------

/**
 * Calls ignored on this device: they neither ring nor show here until they
 * are over, and the caller is not told — to them it is still ringing.
 */
const ignoredCalls = new Set<string>();

/**
 * Stops ringing for a channel, and the timeout that would have stopped it.
 * `missed` says the call ended without anyone here answering it, which is
 * worth telling someone who was away; otherwise the notification just goes.
 */
function clearRing(channelId: string, missed: boolean): void {
  const was = useVoice.getState().ringing[channelId];
  useVoice.getState().setRinging(channelId, null);
  const t = ringTimers.get(channelId);
  if (t) clearTimeout(t);
  ringTimers.delete(channelId);
  if (!was) return;
  if (missed) missedNotice(channelId, was.from);
  else closeRingNotice(channelId);
}

/**
 * Says what a moderator just did to this account's own call: moved it to
 * another channel, muted or deafened it, or lifted either. `was` is the state
 * before, `cur` the channel the call was in. A channel that lets nobody speak
 * mutes too, but that is the channel's doing and is said nowhere here.
 */
function moderatorNotice(was: VoiceState | null, now: VoiceState, cur: string): void {
  const toast = useUI.getState().toast;
  if (now.channel_id && now.channel_id !== cur) {
    const name = useChannels.getState().byId[now.channel_id]?.name;
    toast(name ? tr_("voice.moderation.youWereMovedTo", { channel: name }) : tr_("voice.moderation.youWereMoved"));
    return;
  }
  if (!was || was.channel_id !== now.channel_id) return;
  const modMute = (v: VoiceState) => v.server_mute && !v.suppress;
  if (modMute(now) !== modMute(was)) toast(modMute(now) ? tr_("voice.moderation.youWereMuted") : tr_("voice.moderation.youWereUnmuted"));
  if (now.server_deaf !== was.server_deaf) toast(now.server_deaf ? tr_("voice.moderation.youWereDeafened") : tr_("voice.moderation.youWereUndeafened"));
}

export function handleVoiceEvent(e: GatewayEvent): void {
  const myId = me();
  const store = useVoice.getState();
  switch (e.t) {
    case "voice_state": {
      const vs = e.d;
      const before = vs.channel_id ? null : channelOfUser(vs.user_id);
      rememberCallUser(vs);
      store.applyState(vs);
      const cur = inVoice();
      if (vs.user_id === myId) {
        // The server keeps one call per account and tells all its devices.
        // A state from another device's session is that device's call.
        const elsewhere = !!mySessionId && !!vs.session_id && vs.session_id !== mySessionId;
        if (vs.channel_id) {
          if (elsewhere) {
            clearRing(vs.channel_id, false);
            if (cur) {
              void leaveVoice(false);
              useUI.getState().toast(tr_("session.yourCallMovedToAnotherDevice"));
            } else if (store.mine) store.setMine(null);
            return;
          }
          // a join this device has since given up on; its leave is on the way
          if (!cur || !engine?.onMine(vs)) return;
          moderatorNotice(store.mine, vs, cur);
          checkAlone(vs.channel_id);
          return;
        }
        if (!cur || (vs.old_channel_id && vs.old_channel_id !== cur)) return;
        // another device of this account picked the call up: the server says so first
        if (vs.reason === "moved") {
          void leaveVoice(false);
          useUI.getState().toast(tr_("session.yourCallMovedToAnotherDevice"));
        } else if (vs.reason === "disconnected") {
          void leaveVoice(false);
          useUI.getState().toast(tr_("voice.moderation.youWereDisconnected"));
        } else if (!elsewhere) {
          // disconnected server-side (kick, channel deleted, moderator)
          void leaveVoice(false);
        }
        return;
      }
      if (!cur) return;
      const what = engine?.onOther(vs, before) ?? null;
      if (what === "joined") {
        // someone picked up
        stopCallingTone();
        playSound("join");
      } else if (what === "left") playSound("leave");
      checkAlone(cur);
      return;
    }
    case "voice_signal":
      engine?.onSignal(e);
      return;
    case "voice_resumed":
      engine?.onResumed(e);
      return;
    case "voice_error": {
      if (SIGNAL_ERRORS.has(e.d.code)) {
        console.warn("voice: signal refused:", e.d.code);
        return;
      }
      const cur = inVoice();
      if (MODE_ERRORS.has(e.d.code) && cur && Date.now() - modeRetryAt > 30_000) {
        // ask the server again how calls connect, and join that way
        modeRetryAt = Date.now();
        iceCache = null;
        void leaveVoice(false).then(() => joinVoice(cur));
        return;
      }
      store.setJoining(null);
      {
        const message = e.d.code === "account_muted" ? mutedSentence(activeMute(useNexa.getState().state)) : e.d.message;
        store.setError(message);
        useUI.getState().toast(message, "error");
      }
      // `mine` is null for the whole window between announcing the join and the
      // server's own voice_state coming back, so this fires on a call the
      // server does think we are in. Telling it costs one redundant frame; not
      // telling it leaves a tile nobody can get rid of and a channel that
      // refuses to let us back in.
      if (cur && !useVoice.getState().mine) void leaveVoice(true);
      return;
    }
    case "call_ring": {
      // a caller who reconnects rings again; an ignored call stays quiet
      if (e.d.from === myId || inVoice() === e.d.channel_id || ignoredCalls.has(e.d.channel_id)) return;
      // the ringing store starts the repeating ring (see installVoice)
      store.setRinging(e.d.channel_id, e.d.from);
      ringNotice(e.d.channel_id, e.d.from);
      const old = ringTimers.get(e.d.channel_id);
      if (old) clearTimeout(old);
      ringTimers.set(e.d.channel_id, setTimeout(() => clearRing(e.d.channel_id, true), RING_MS));
      return;
    }
    case "call_end": {
      const { channel_id: ch, reason, user_id: who } = e.d;
      if (reason === "ended") {
        // the call is over: a ring still going here was never answered
        clearRing(ch, true);
        ignoredCalls.delete(ch);
        callEnded(ch);
        return;
      }
      // answered or declined on another of this account's devices
      if (who === myId) clearRing(ch, false);
      if (reason !== "declined" || !who || who === myId) return;
      callDeclined(ch, who);
      if (inVoice() !== ch || store.calling !== ch) return;
      const type = useChannels.getState().byId[ch]?.type;
      // the one person called said no, or everyone in a group did: the call is given up, and says why
      if (type === "dm" || (type === "group_dm" && allDeclined(ch))) {
        stopCallingTone();
        useUI.getState().toast(type === "dm" ? callOutcome(ch, "declined") : tr_("voice.call.everyoneDeclined"), "info");
        void leaveVoice();
      }
      return;
    }
    default:
      return;
  }
}

function channelOfUser(userId: string): string | null {
  for (const [ch, users] of Object.entries(useVoice.getState().byChannel)) if (users[userId]) return ch;
  return null;
}

export function onReadyVoice(list: VoiceState[], sessionId?: string): void {
  mySessionId = sessionId ?? null;
  for (const vs of list) rememberCallUser(vs);
  // a reconnect may follow a restart that changed how calls connect
  iceCache = null;
  useVoice.getState().setAll(list);
  // a call that ended while the gateway was away would ring on for its full minute
  for (const ch of Object.keys(useVoice.getState().ringing)) if (!list.some((vs) => vs.channel_id === ch)) clearRing(ch, true);
  callsAfterReady((ch) => inVoice() === ch);
  // after a reconnect the server has forgotten us: the call rejoins the room it was in
  engine?.onReady();
}

/** Silences an incoming call here without answering or declining it. */
export function ignoreCall(channelId: string): void {
  ignoredCalls.add(channelId);
  clearRing(channelId, false);
}

/** Declines an incoming DM call. */
export async function declineCall(channelId: string): Promise<void> {
  clearRing(channelId, false);
  try {
    await voiceApi.decline(channelId);
  } catch {
    /* the ring will time out */
  }
}

/** Registers the mute/deafen, push-to-talk and unload hooks once. */
export function installVoice(): void {
  if (installed || typeof window === "undefined") return;
  installed = true;
  installCallRecord();
  // the line a call this device started gets, to write how it went onto
  dispatchHooks.onMessage.push((m) => callLine(m));
  useVoiceLocal.subscribe((st, prev) => {
    if (st.muted !== prev.muted) playSound(st.muted ? "mute" : "unmute");
    engine?.setOutputMuted(engine.outputSilenced());
    engine?.applyTransmit();
    // Only a call the server has been told about: during the microphone
    // prompt the join is not sent yet, and a mute there sent it early — ringing
    // the other side of a call this device could not yet leave.
    const cur = engine?.current();
    if (cur?.announced) sendState(cur.channelId);
  });
  useSettings.subscribe((st, prev) => {
    const a = st.settings.voice;
    const b = prev.settings.voice;
    if (!engine?.current()) return;
    if (a.inputDeviceId !== b.inputDeviceId || a.noiseMode !== b.noiseMode || a.echoCancellation !== b.echoCancellation || a.autoGain !== b.autoGain) void engine.refreshMic();
    if (a.outputDeviceId !== b.outputDeviceId) engine.setOutputDevice(a.outputDeviceId);
    if (a.cameraDeviceId !== b.cameraDeviceId || a.cameraDeviceLabel !== b.cameraDeviceLabel) void engine.switchCamera();
    if (a.outputVolume !== b.outputVolume) engine.setMasterVolume(a.outputVolume);
    if (a.inputVolume !== b.inputVolume) engine.applyInputGain();
    if (a.mode !== b.mode) engine.applyTransmit();
  });
  useVoice.subscribe((st, prev) => {
    // One ring for however many calls are coming in, until the last is
    // answered, declined or gone. Do Not Disturb shows the call but keeps it quiet.
    if (st.ringing !== prev.ringing) {
      const now = Object.keys(st.ringing).length > 0;
      const was = Object.keys(prev.ringing).length > 0;
      if (now && !was && !doNotDisturb()) startRinging();
      else if (!now && was) stopRinging();
    }
    if (ignoredCalls.size && st.byChannel !== prev.byChannel) {
      for (const id of ignoredCalls) if (participantsOf(st.byChannel, id).length === 0) ignoredCalls.delete(id);
    }
    // A share's sound plays only while it is watched.
    if (engine?.current() && st.watching !== prev.watching) {
      for (const id of new Set([...Object.keys(st.watching), ...Object.keys(prev.watching)])) engine.setWatching(id, !!st.watching[id]);
    }
  });
  // A role or overwrite changing mid-call: a camera or share no longer
  // allowed stops, and push to talk comes in or goes as Use Voice Activity does
  useServers.subscribe(() => {
    const cur = engine?.current();
    if (!engine || !cur) return;
    if (!mayStream(cur.channelId)) {
      if (useVoice.getState().localVideo) void engine.toggleCamera();
      if (engine.sharing()) void engine.toggleScreen();
    }
    engine.applyTransmit();
  });
  // turning on Do Not Disturb quiets a call that is already ringing
  usePresence.subscribe(() => {
    if (doNotDisturb()) stopRinging();
  });
  const isPtt = (e: KeyboardEvent) => {
    const v = useSettings.getState().settings.voice;
    return pushToTalkOn() && (e.key === v.pttKey || e.code === v.pttKey);
  };
  window.addEventListener("keydown", (e) => {
    if (!engine?.current() || !isPtt(e) || e.repeat) return;
    engine.setPttHeld(true);
  });
  window.addEventListener("keyup", (e) => {
    if (!engine?.current() || !isPtt(e)) return;
    engine.setPttHeld(false);
  });
  const isPriority = (e: KeyboardEvent) => {
    const k = useSettings.getState().settings.voice.priorityKey;
    return !!k && (e.key === k || e.code === k);
  };
  // Priority Speaker: only where this person may be one, and the server checks again
  const holdPriority = (on: boolean) => {
    const cur = engine?.current();
    if (!cur || (on && !mayBePriority(cur.channelId))) return;
    if (engine?.setPriorityHeld(on) && cur.announced) sendState(cur.channelId);
  };
  window.addEventListener("keydown", (e) => {
    if (isPriority(e) && !e.repeat) holdPriority(true);
  });
  window.addEventListener("keyup", (e) => {
    if (isPriority(e)) holdPriority(false);
  });
  window.addEventListener("blur", () => {
    engine?.setPttHeld(false);
    holdPriority(false);
  });
  document.addEventListener("visibilitychange", () => engine?.applyTransmit());
  // iOS keeps a suspended AudioContext silent until a gesture resumes it.
  const wake = () => {
    if (engine?.current()) resumeAudio();
  };
  for (const type of ["touchend", "click", "keydown"]) window.addEventListener(type, wake, { capture: true, passive: true });
  window.addEventListener("beforeunload", () => {
    const cur = engine?.current();
    if (cur?.announced) sendState(null);
  });
  // iOS Safari never fires beforeunload, and Chrome on Android skips it for
  // a tab swiped away; pagehide is what both do send. Without it the call
  // lingered for everyone else until the server's grace ran out.
  window.addEventListener("pagehide", (e) => {
    const cur = engine?.current();
    if (!e.persisted && cur?.announced) sendState(null);
  });
  // a call whose connection dropped with the network tries again the moment the network is back
  window.addEventListener("online", () => engine?.nudge());
  // The device in use going away is not a settings change, and the only other
  // devicechange listener in the app is on a settings page no call mounts.
  // Only a call listens: listening starts the browser's audio and camera
  // services (near 90 MB in the desktop app), which stay up once started.
  const onDevices = () => engine?.devicesChanged();
  let listening = false;
  const listen = ({ mine, joining }: { mine: unknown; joining: unknown }) => {
    const want = !!(mine || joining);
    if (want === listening) return;
    listening = want;
    if (want) navigator.mediaDevices?.addEventListener?.("devicechange", onDevices);
    else navigator.mediaDevices?.removeEventListener?.("devicechange", onDevices);
  };
  listen(useVoice.getState());
  useVoice.subscribe(listen);
  // a channel that disappears takes its call with it
  useChannels.subscribe((st, prev) => {
    const ch = inVoice();
    if (!ch || !prev.byId[ch] || st.byId[ch]) return;
    // A reconnect empties the list and fills it again in the same turn, so
    // only a channel still missing afterwards has really gone. The server
    // does not end our call when a DM is closed, so it is told.
    queueMicrotask(() => {
      if (inVoice() === ch && !useChannels.getState().byId[ch]) void leaveVoice(true);
    });
  });
}
