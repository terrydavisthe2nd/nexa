import { useMyReports } from "@/stores/myReports";
import { useMyBadges } from "@/stores/myBadges";
import { CLOSE_TOO_MANY, GatewayClient, type GatewayStatus } from "./client";
import type { GatewayEvent } from "./events";
import { applyEvent, applyLockedEvent, dispatchGeneration, endDispatchSession, forgetRotations, resetAll } from "./dispatch";
import { ApiError } from "@/api/client";
import { logoutHooks, useAuth } from "@/stores/auth";
import { useKeys } from "@/stores/keys";
import { useMessages } from "@/stores/messages";
import { useThreadLast } from "@/stores/threadLast";
import { useReplyTargets } from "@/stores/replyTargets";
import { useNotes } from "@/stores/notes";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useVoice } from "@/stores/voice";
import { useNexa } from "@/stores/nexa";
import { useDiscord } from "@/stores/discord";
import { useModeration } from "@/stores/moderation";
import { useUploads } from "@/stores/uploads";
import { useSelection } from "@/stores/selection";
import { useSlowmode } from "@/stores/slowmode";
import { forgetSends } from "@/components/composer/send";
import { forgetFetchedPolls } from "@/components/chat/usePollOf";
import { forgetUnsentAcks } from "@/stores/readStates";
import { admin as adminApi } from "@/api/admin";
import { setUploadLimits } from "@/lib/attachments";
import { setPlans } from "@/lib/plans";
import { clearLock, hasLock } from "@/lib/appLock";
import { reportCrash } from "@/lib/crashReport";
import { clearMediaCache } from "@/lib/mediaCache";
import { keystore } from "@/crypto/keystore";
import { workerClearTimeout, workerSetTimeout } from "@/lib/workerTimers";
import { useTabs } from "@/stores/tabs";
import { t } from "@/i18n";

let client: GatewayClient | null = null;
let queue: Promise<void> = Promise.resolve();
/** Set for the whole of startSession, including its awaits, so two overlapping calls build one client. */
let starting = false;
/** Moves on when a session ends, so one still starting knows to stop. */
let sessionGeneration = 0;
/**
 * Set while the app is locked with a call going on. People lock while they
 * listen, so the socket stays up and the call with it; but nothing that needs
 * the keys is applied, only the call's own events. Unlocking connects again,
 * and the ready that brings back everything the lock put away.
 */
let lockedInCall = false;

export function gatewayUrl(): string {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/gateway`;
}

/**
 * What the connection banner says when the server gave a reason worth passing
 * on; undefined leaves the note as it is, null takes it away.
 */
export function connectionNote(s: GatewayStatus, info?: { code?: number; reason?: string }): string | null | undefined {
  if (s !== "closed") return undefined;
  if (info?.code !== CLOSE_TOO_MANY) return null;
  // the server's reason names which limit it was (internal/gateway/hub.go)
  const reason = info.reason ?? "";
  if (reason.includes("address")) return t("gateway.tooManyFromThisNetwork");
  if (reason.includes("limit")) return t("gateway.serverIsFull");
  return t("gateway.tooManyForThisAccount");
}

/**
 * A handler that throws still lets the queue go on, since one bad event must
 * not stop every later one; but it is said somewhere. A message whose handler
 * failed is a message that never appears, and without this nothing in the
 * console or the crash reports would explain it.
 */
function handlerFailed(e: GatewayEvent, err: unknown): void {
  console.warn("gateway: the handler for an event failed", e.t, err);
  // a request that failed on the way is the network's doing, not a bug here
  if (err instanceof ApiError && (err.status === 0 || err.status >= 500)) return;
  reportCrash({ kind: "promise", message: `gateway ${e.t}: ${err instanceof Error ? `${err.name}: ${err.message}` : String(err)}`, stack: err instanceof Error ? (err.stack ?? "") : "" });
}

/** Applies events strictly in order: a slow decrypt never lets a later frame overtake it. */
export function enqueueEvent(e: GatewayEvent): Promise<void> {
  const gen = dispatchGeneration();
  // decided as the event arrives: one that came while the app was locked is
  // handled as the lock allowed, even if the queue reaches it after unlocking
  const locked = lockedInCall;
  queue = queue
    .then(() => {
      if (gen !== dispatchGeneration()) return undefined;
      return locked ? applyLockedEvent(e) : applyEvent(e);
    })
    .catch((err: unknown) => handlerFailed(e, err));
  return queue;
}

/** Connects the gateway for the logged-in user; safe to call repeatedly. */
export async function startSession(): Promise<void> {
  const { me, identity, settingsKey } = useAuth.getState();
  if (!me || !identity) return;
  // The key store is read before the client is built, and two callers that
  // overlap on that await would both find `client` null: the second socket is
  // never referenced again but keeps applying every event a second time.
  if (starting) return;
  starting = true;
  const gen = ++sessionGeneration;
  try {
    useSettings.getState().setKey(settingsKey);
    void loadUploadLimits();
    try {
      await useKeys.getState().init(me.id, identity);
    } catch (err) {
      // The cached keys are only a head start: ready brings every grant again,
      // so a key store that fails (WebKit drops it after a long background stay)
      // must not leave the app without a connection.
      console.warn("keystore: could not load cached keys", err);
      useKeys.setState({ userId: me.id, identity, waiting: {} });
    }
    // Locked or signed out while the keys were loading: this session is not
    // wanted any more, and connecting now would bring it back to life.
    if (gen !== sessionGeneration) {
      useKeys.getState().reset();
      return;
    }
    if (client) {
      // Unlocked with the call's socket still up: everything else comes back
      // with a fresh ready.
      if (lockedInCall) {
        lockedInCall = false;
        client.restart();
      }
      return;
    }
    client = new GatewayClient({
      url: gatewayUrl,
      token: () => useAuth.getState().token,
      onEvent: (e) => void enqueueEvent(e),
      onStatus: (s, info) => useUI.getState().setConnection(s, connectionNote(s, info)),
      // heartbeats keep their pace in a hidden window, where the page's own timers slow to once a minute
      setTimeoutImpl: workerSetTimeout,
      clearTimeoutImpl: workerClearTimeout,
    });
    client.connect();
    window.addEventListener("online", wakeGateway);
    window.addEventListener("pageshow", wakeGateway);
    document.addEventListener("visibilitychange", wakeGatewayIfShown);
  } finally {
    starting = false;
  }
}

/**
 * Connects the gateway for a guest in a call: no account, keys or settings,
 * only the events of that one call, handed to `onEvent` as they come.
 */
export function startGuestSession(token: string, onEvent: (e: GatewayEvent) => void): void {
  stopGuestSession();
  client = new GatewayClient({
    url: gatewayUrl,
    token: () => token,
    onEvent,
    onStatus: (s, info) => useUI.getState().setConnection(s, connectionNote(s, info)),
    setTimeoutImpl: workerSetTimeout,
    clearTimeoutImpl: workerClearTimeout,
  });
  client.connect();
  window.addEventListener("online", wakeGateway);
  window.addEventListener("pageshow", wakeGateway);
  document.addEventListener("visibilitychange", wakeGatewayIfShown);
}

export function stopGuestSession(): void {
  window.removeEventListener("online", wakeGateway);
  window.removeEventListener("pageshow", wakeGateway);
  document.removeEventListener("visibilitychange", wakeGatewayIfShown);
  client?.close();
  client = null;
}

/** Coming back online or to the app is when a connection is most likely to have died. */
function wakeGateway(): void {
  client?.wake();
}

function wakeGatewayIfShown(): void {
  if (document.visibilityState === "visible") client?.wake();
}

/**
 * Forgets everything this session could read: the keys, the settings (sent
 * first, if any are still waiting) and every decrypted message and file.
 */
function forgetReadable(): void {
  // Events already partway through applying stop here rather than finishing
  // into stores that have just been cleared.
  endDispatchSession();
  useKeys.getState().reset();
  // Changes still waiting to be saved go now, sealed with the key they were
  // made under: the reset below forgets them, so a theme picked a moment
  // before the app locked itself was simply gone when it unlocked.
  void useSettings.getState().flush();
  useSettings.getState().reset();
  // Without the keys nothing decrypted should stay readable either; unlocking
  // loads the conversations again.
  useMessages.getState().clearAll();
  useReplyTargets.getState().clear();
  // what each thread said last, opened for the cards under the messages they hang from
  useThreadLast.getState().clear();
  // notes are opened with keys of their own, which go with the rest
  useNotes.getState().clear();
  clearMediaCache();
}

export function stopSession(): void {
  sessionGeneration++;
  lockedInCall = false;
  window.removeEventListener("online", wakeGateway);
  window.removeEventListener("pageshow", wakeGateway);
  document.removeEventListener("visibilitychange", wakeGatewayIfShown);
  client?.close();
  client = null;
  forgetReadable();
}

/** Whether a call is under way on this device, joined or still joining. */
function inCall(): boolean {
  const v = useVoice.getState();
  return v.mine !== null || v.joining !== null;
}

/**
 * The app has locked. Everything readable goes, as it always has; a call
 * going on keeps its socket, so locking does not hang up on anyone.
 */
export function lockSession(): void {
  if (!client || !inCall()) {
    stopSession();
    return;
  }
  // a startSession still loading keys stops where it is
  sessionGeneration++;
  lockedInCall = true;
  forgetReadable();
}

/**
 * Ends the session on sign-out: hangs up any call, so the microphone and the
 * peer connections do not outlive the account, and forgets everything the
 * account had loaded so the next person on this tab starts from nothing.
 */
export async function endSession(): Promise<void> {
  try {
    const voice = await import("@/voice/session");
    await voice.leaveVoice(true);
  } catch {
    /* nothing to hang up */
  }
  stopSession();
  resetAll();
  forgetRotations();
  useVoice.getState().clear();
  useUI.setState({ activeChannelId: null, replyTo: null, editing: null, editDrafts: {}, jumpTo: null, contextMenu: null, quickSwitcher: false, modals: [], modal: null });
  // a deletion notice stays, to be read on the sign-in page
  useNexa.getState().reset();
  useModeration.getState().reset();
  useMyReports.getState().reset();
  useMyBadges.getState().reset();
  useDiscord.getState().clear();
  // files picked or on their way belong to the account that picked them, and
  // so do messages still being sent or left failed: the next person on this
  // tab must neither read them nor, with Retry, send them as their own
  useUploads.getState().clear();
  forgetSends();
  useMessages.setState({ channels: {} });
  useReplyTargets.getState().clear();
  // polls read for their closing lines were this account's to read
  forgetFetchedPolls();
  forgetUnsentAcks();
  // what was being picked for deleting, and the slow mode waits, were this account's
  useSelection.getState().clear();
  useSlowmode.getState().reset();
  // tabs name another account's channels
  useTabs.getState().reset();
}

logoutHooks.push(endSession);

// The lock's promise is that it forgets what is held in memory. That used to
// happen in a route guard's effect, so a page outside it — an invite — kept
// the keys and the socket behind the passcode screen; it happens here now,
// wherever the app is when it locks.
useAuth.subscribe((a, prev) => {
  if (a.status === "pinlocked" && prev.status !== "pinlocked") lockSession();
});

export function gateway(): GatewayClient | null {
  return client;
}

/**
 * Keeps this device in step with the account's passcode: asks for it when the
 * account has one and this device does not, and forgets it when it is removed
 * somewhere else.
 */
export function applyLockPolicy(): void {
  const { me, identity } = useAuth.getState();
  const { settings, loaded } = useSettings.getState();
  // Before the account's settings arrive they are all defaults, and acting on
  // those would read "no passcode" and throw this device's copy away.
  if (!me || !loaded) return;
  const policy = settings.appLock;
  const local = hasLock(me.id);
  if (policy && !local) {
    useAuth.getState().requirePasscode();
    return;
  }
  if (!policy && local && identity) {
    clearLock(me.id);
    void keystore.saveIdentity(me.id, identity);
  }
}

/** Asks the server what it accepts, so the composer can refuse early. */
async function loadUploadLimits(): Promise<void> {
  try {
    const info = await adminApi.instance();
    setUploadLimits({ max: info.max_upload_bytes, single: info.upload_single_bytes, chunk: info.upload_chunk_bytes });
    setPlans(info.plans);
  } catch {
    /* the built-in defaults stand */
  }
}
