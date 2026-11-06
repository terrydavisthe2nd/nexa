import { create } from "zustand";
import { auth as authApi } from "@/api/auth";
import { api, ApiError } from "@/api/client";
import { isMfaChallenge, type KdfParamsJson, type LoginResponse, type MeUser } from "@/api/types";
import { users as usersApi } from "@/api/users";
import { b64, randomBytes } from "@/crypto/bytes";
import { DEFAULT_KDF, deriveAuthKey, deriveEncKey, deriveMaster, deriveSettingsKey, newRecoveryKey, recoveryKeyToBytes, recoveryToKeys, type KdfParams } from "@/crypto/kdf";
import { generateIdentity, unwrapPrivateKey, wrapPrivateKey, type Identity } from "@/crypto/keys";
import { keystore } from "@/crypto/keystore";
import { guarded } from "@/lib/captcha";
import { adoptPolicy, clearLock, enableLock, hasLock, loadLock, policyFor, saveLock, unlockIdentity, type LockConfig } from "@/lib/appLock";
import { acceptServerKdf } from "@/lib/serverKdf";
import { useSettings, type AppLockPolicy } from "@/stores/settings";
import { useNexa } from "@/stores/nexa";
import { ReadableError } from "@/lib/readableError";
import { storage } from "@/lib/storage";
import { wipeSearchIndex } from "@/search/keys";
import { forgetAccount, forgetToken, nextAccount, placeOf, rememberAccount } from "@/lib/accounts";

import { t } from "@/i18n";
/** "offline": a token is stored, but the server or this device's key store could not be reached. */
export type AuthStatus = "idle" | "loading" | "offline" | "anonymous" | "locked" | "pinlocked" | "ready";

/** What a password got us: either we are in, or the account wants a code. */
export type LoginOutcome = { mfa: false } | { mfa: true; backupCodesLeft: number };

/** What a sign-in got, before anything is kept on this device: the session and the identity key. */
export interface SignedIn {
  me: MeUser;
  token: string;
  identity: Identity;
}

/** A password's answer: signed in, or the account wants a code, which `finish` sends. */
export type PasswordStep = { mfa: false; out: SignedIn } | { mfa: true; backupCodesLeft: number; finish: (proof: { code?: string; backupCode?: string }) => Promise<SignedIn> };

/** Kept out of the store so a half-finished sign-in never lands in a snapshot. */
let pendingMfa: ((proof: { code?: string; backupCode?: string }) => Promise<SignedIn>) | null = null;
/**
 * The account's passcode policy, held from the moment we decide to ask for it.
 * Showing the lock screen tears the session down, which clears the settings it
 * came from, so it has to be kept here rather than read back later.
 */
let pendingPolicy: AppLockPolicy | null = null;

/** Where this device keeps its session (lib/move.ts carries it to a new address). */
export const TOKEN_KEY = "ec.token";

/**
 * Whether this browser holds a session, without opening it: the public pages
 * offer "Open Nexa" instead of "Log in" to someone who is signed in, and the
 * app itself checks the session when it opens.
 */
export function hasSavedSession(): boolean {
  return !!storage.get(TOKEN_KEY);
}

interface AuthState {
  status: AuthStatus;
  me: MeUser | null;
  token: string | null;
  identity: Identity | null;
  settingsKey: Uint8Array | null;
  error: string | null;
  /** Bumped whenever this device's lock settings change, so the lock timers re-read them. */
  lockVersion: number;
  bootstrap: () => Promise<void>;
  register: (username: string, displayName: string, password: string, extra?: { invite_code?: string; registration_key?: string }) => Promise<{ recoveryKey: string }>;
  login: (username: string, password: string) => Promise<LoginOutcome>;
  /** Signs in with what a passkey or a phone (a QR code) handed over: the session and the identity key. */
  loginWith: (me: MeUser, token: string, identity: Identity) => Promise<void>;
  /** Finishes a sign-in that stopped for a second factor. */
  completeMfa: (proof: { code?: string; backupCode?: string }) => Promise<void>;
  cancelMfa: () => void;
  unlock: (password: string) => Promise<void>;
  /** Rebuilds the keys from this device's passcode. */
  unlockWithPasscode: (passcode: string) => Promise<void>;
  /** Forgets the keys held in memory and shows the passcode screen. */
  lockApp: () => void;
  /** Asks for the account passcode on a device that has not stored it yet. */
  requirePasscode: () => void;
  /** Wraps the identity with a passcode and drops the plaintext copy. */
  setPasscode: (passcode: string, opts?: Partial<Pick<LockConfig, "autoLockMs" | "lockOnBlur" | "blurDelayMs">>) => Promise<void>;
  /** Removes the passcode and goes back to keeping the keys on this device. */
  removePasscode: () => Promise<void>;
  /** Tells the lock timers that this device's lock settings were changed. */
  bumpLock: () => void;
  recover: (username: string, recoveryKey: string, newPassword: string) => Promise<{ recoveryKey: string }>;
  changePassword: (current: string, next: string) => Promise<void>;
  newRecoveryKey: (password: string) => Promise<string>;
  logout: () => Promise<void>;
  setMe: (me: MeUser) => void;
}

/** Pushes settings to the server now rather than on the usual debounce, trying twice; false if they never got there. */
async function saveSettingsNow(): Promise<boolean> {
  if (await useSettings.getState().flush()) return true;
  await new Promise((r) => setTimeout(r, 500));
  return useSettings.getState().flush();
}

const saveFailed = () => t("auth.couldNotSaveThatToYour");

/**
 * Run on sign-out before the state is cleared. The gateway session registers
 * here rather than being imported, since it already depends on this store.
 */
export const logoutHooks: (() => Promise<void>)[] = [];

async function deriveAll(password: string, salt: Uint8Array, params: KdfParams) {
  const master = await deriveMaster(password, salt, params);
  return { authKey: deriveAuthKey(master), encKey: deriveEncKey(master) };
}

/**
 * The argon2id parameters to stretch a password with, as the server gave them.
 *
 * They have to come from the server — an account may have been registered with
 * others, and only the server knows which — but they cannot be taken on trust.
 * The password is stretched with whatever arrives here and the result is then
 * *handed to the server* as proof of knowledge, so a server that answers with
 * something free to compute gets the plaintext password back out of that proof
 * in seconds, and with it the wrapped identity key it already stores.
 *
 * Nothing is derived below the floor. It is the server's own rule for what a
 * client may register with, so no account it ever accepted can be refused here:
 * every account on Nexa is at DEFAULT_KDF, well inside it.
 */
function toParams(p: KdfParamsJson): KdfParams {
  return acceptServerKdf(p);
}

async function finishLogin(set: (s: Partial<AuthState>) => void, me: MeUser, token: string, identity: Identity): Promise<void> {
  storage.set(TOKEN_KEY, token);
  api.setToken(token);
  // a ban is known before the app is drawn, so it never flashes up behind the ban screen
  useNexa.getState().setState(me.nexa);
  // With a passcode set, the only copy of the key on this device is the wrapped
  // one, so the plaintext must never go back into the key store.
  if (!hasLock(me.id)) await keystore.saveIdentity(me.id, identity);
  rememberAccount(me, token);
  set({ status: "ready", me, token, identity, settingsKey: deriveSettingsKey(identity.privateKey), error: null });
}

/**
 * Signs in with a password without touching the account open here: the login
 * page goes on with the result, Add Another Account keeps it beside this one.
 */
export async function passwordSignIn(username: string, password: string): Promise<PasswordStep> {
  const kdf = await authApi.kdf(username);
  const { authKey, encKey } = await deriveAll(password, b64.dec(kdf.salt), toParams(kdf.params));
  const res = await guarded("login", (captcha) => authApi.login(username, b64.enc(authKey), captcha));
  const open = async (r: LoginResponse): Promise<SignedIn> => ({
    me: r.user,
    token: r.token,
    identity: { publicKey: b64.dec(r.public_key), privateKey: await unwrapPrivateKey(b64.dec(r.enc_private_key), encKey) },
  });
  if (isMfaChallenge(res)) {
    const ticket = res.ticket;
    return {
      mfa: true,
      backupCodesLeft: res.backup_codes_left,
      finish: async ({ code, backupCode }) => open(await authApi.loginMfa(ticket, backupCode ? { backup_code: backupCode } : { code: (code ?? "").replace(/\s/g, "") })),
    };
  }
  return { mfa: false, out: await open(res) };
}

/**
 * Makes a new account without touching the one open here: the sign-up page goes
 * on with the result, Add Another Account keeps it beside this one. Every key is
 * made here and only sealed copies leave the device.
 */
export async function createAccount(username: string, displayName: string, password: string, extra?: { invite_code?: string; registration_key?: string }): Promise<{ out: SignedIn; recoveryKey: string }> {
  const salt = randomBytes(16);
  const params = DEFAULT_KDF;
  const { authKey, encKey } = await deriveAll(password, salt, params);
  const identity = generateIdentity();
  const recovery = newRecoveryKey();
  const rk = recoveryToKeys(recovery.bytes);
  const body = {
    username,
    display_name: displayName,
    auth_key: b64.enc(authKey),
    kdf_salt: b64.enc(salt),
    kdf_params: params,
    public_key: b64.enc(identity.publicKey),
    enc_private_key: b64.enc(await wrapPrivateKey(identity.privateKey, encKey)),
    enc_private_key_recovery: b64.enc(await wrapPrivateKey(identity.privateKey, rk.encKey)),
    recovery_auth_key: b64.enc(rk.authKey),
    ...(extra ?? {}),
  };
  const res = await guarded("register", (captcha) => authApi.register(body, captcha));
  return { out: { me: res.user, token: res.token, identity }, recoveryKey: recovery.display };
}

export const useAuth = create<AuthState>()((set, get) => ({
  status: "idle",
  me: null,
  token: storage.get(TOKEN_KEY),
  identity: null,
  settingsKey: null,
  error: null,
  lockVersion: 0,

  bootstrap: async () => {
    const token = storage.get(TOKEN_KEY);
    if (!token) {
      set({ status: "anonymous", token: null });
      return;
    }
    api.setToken(token);
    // A retry from the offline screen stays on it until there is an answer, so
    // the screen does not flash a spinner on every attempt.
    set(get().status === "offline" ? { token } : { status: "loading", token });
    try {
      const me = await usersApi.me();
      useNexa.getState().setState(me.nexa);
      rememberAccount(me, token);
      if (hasLock(me.id)) {
        set({ status: "pinlocked", me, identity: null, settingsKey: null });
        return;
      }
      const identity = await keystore.loadIdentity(me.id);
      if (!identity) {
        set({ status: "locked", me });
        return;
      }
      set({ status: "ready", me, identity, settingsKey: deriveSettingsKey(identity.privateKey), error: null });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        forgetToken(token);
        storage.remove(TOKEN_KEY);
        api.setToken(null);
        set({ status: "anonymous", token: null, me: null });
        return;
      }
      // Only the server turning the request down means logged out. A dropped
      // connection, a server restarting behind Cloudflare or a key store hiccup
      // keeps the session and waits on a screen that retries.
      if (err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 408 && err.status !== 429) {
        set({ status: "anonymous", error: err.message });
        return;
      }
      set({ status: "offline", error: err instanceof ApiError || err instanceof TypeError ? t("offline.couldNotReachTheServer") : t("auth.couldNotOpenTheKeysSaved") });
    }
  },

  register: async (username, displayName, password, extra) => {
    const { out, recoveryKey } = await createAccount(username, displayName, password, extra);
    await finishLogin(set, out.me, out.token, out.identity);
    return { recoveryKey };
  },

  login: async (username, password) => {
    const step = await passwordSignIn(username, password);
    if (step.mfa) {
      pendingMfa = step.finish;
      return { mfa: true, backupCodesLeft: step.backupCodesLeft };
    }
    pendingMfa = null;
    await finishLogin(set, step.out.me, step.out.token, step.out.identity);
    return { mfa: false };
  },

  loginWith: async (me, token, identity) => {
    pendingMfa = null;
    await finishLogin(set, me, token, identity);
  },

  completeMfa: async (proof) => {
    const finish = pendingMfa;
    if (!finish) throw new ReadableError(t("auth.startAgainFromYourPassword"));
    const out = await finish(proof);
    pendingMfa = null;
    await finishLogin(set, out.me, out.token, out.identity);
  },

  cancelMfa: () => {
    pendingMfa = null;
  },

  unlock: async (password) => {
    const me = get().me;
    const token = get().token;
    if (!me || !token) throw new Error("not logged in");
    const bundle = await usersApi.myKeys();
    const { encKey } = await deriveAll(password, b64.dec(bundle.kdf_salt), toParams(bundle.kdf_params));
    const privateKey = await unwrapPrivateKey(b64.dec(bundle.enc_private_key), encKey);
    await finishLogin(set, me, token, { publicKey: b64.dec(bundle.public_key), privateKey });
  },

  unlockWithPasscode: async (passcode) => {
    const { me, token, identity: current } = get();
    if (!me || !token) throw new Error("not logged in");
    if (!hasLock(me.id)) {
      // This device has not seen the account's passcode yet: check it against
      // the policy, then wrap this device's copy of the key with it.
      const policy = pendingPolicy ?? useSettings.getState().settings.appLock;
      if (!policy || !current) throw new ReadableError(t("appLock.thisDeviceHasNoPasscode"));
      await adoptPolicy(me.id, current, passcode, policy);
      pendingPolicy = null;
      await keystore.forgetIdentity(me.id);
      await keystore.forgetRoots(me.id);
      set({ status: "ready", error: null });
      return;
    }
    const identity = await unlockIdentity(me.id, passcode);
    set({ status: "ready", me, identity, settingsKey: deriveSettingsKey(identity.privateKey), error: null });
  },

  /** Asks for the account passcode on a device that has not stored it yet. */
  requirePasscode: () => {
    const { me, status } = get();
    if (!me || status !== "ready" || hasLock(me.id)) return;
    const policy = useSettings.getState().settings.appLock;
    if (!policy) return;
    pendingPolicy = policy;
    set({ status: "pinlocked" });
  },

  lockApp: () => {
    const { me, status } = get();
    if (!me || status !== "ready" || !hasLock(me.id)) return;
    set({ status: "pinlocked", identity: null, settingsKey: null });
  },

  setPasscode: async (passcode, opts) => {
    const { me, identity } = get();
    if (!me || !identity) throw new ReadableError(t("auth.unlockYourKeysFirst"));
    const previousLock = loadLock(me.id);
    const previousPolicy = useSettings.getState().settings.appLock;
    const cfg = await enableLock(me.id, identity, passcode, opts);
    // The wrapped copy is now the only one on this device; drop the readable
    // one, and the conversation keys, which would read messages without it.
    await keystore.forgetIdentity(me.id);
    await keystore.forgetRoots(me.id);
    get().bumpLock();
    // The policy travels with the account, so every other device asks too. It
    // is saved right away: a reload before the debounce would come back to
    // settings that say there is no passcode, and drop the one just made.
    useSettings.getState().patch({ appLock: await policyFor(cfg, passcode) });
    if (await saveSettingsNow()) return;
    // Unsaved, the next ready would find no passcode and quietly undo this
    // one, so put things back and say so instead.
    if (previousLock) {
      saveLock(me.id, previousLock);
    } else {
      clearLock(me.id);
      await keystore.saveIdentity(me.id, identity);
    }
    useSettings.getState().patch({ appLock: previousPolicy });
    get().bumpLock();
    throw new ReadableError(saveFailed());
  },

  removePasscode: async () => {
    const { me, identity } = get();
    if (!me) return;
    const previousLock = loadLock(me.id);
    const previousPolicy = useSettings.getState().settings.appLock;
    clearLock(me.id);
    get().bumpLock();
    if (identity) await keystore.saveIdentity(me.id, identity);
    useSettings.getState().patch({ appLock: null });
    if (await saveSettingsNow()) return;
    // Unsaved, the next ready would ask for the removed passcode again.
    if (previousLock) {
      saveLock(me.id, previousLock);
      await keystore.forgetIdentity(me.id);
    }
    useSettings.getState().patch({ appLock: previousPolicy });
    get().bumpLock();
    throw new ReadableError(saveFailed());
  },

  bumpLock: () => set((s) => ({ lockVersion: s.lockVersion + 1 })),

  recover: async (username, recoveryKey, newPassword) => {
    const rkBytes = recoveryKeyToBytes(recoveryKey);
    const rk = recoveryToKeys(rkBytes);
    const begin = await guarded("recover", (captcha) => authApi.recoverBegin(username, b64.enc(rk.authKey), captcha));
    const privateKey = await unwrapPrivateKey(b64.dec(begin.enc_private_key_recovery), rk.encKey);
    const salt = randomBytes(16);
    const { authKey, encKey } = await deriveAll(newPassword, salt, DEFAULT_KDF);
    const fresh = newRecoveryKey();
    const frk = recoveryToKeys(fresh.bytes);
    const res = await authApi.recoverFinish({
      username,
      recovery_auth_key: b64.enc(rk.authKey),
      new_auth_key: b64.enc(authKey),
      new_kdf_salt: b64.enc(salt),
      new_kdf_params: DEFAULT_KDF,
      new_enc_private_key: b64.enc(await wrapPrivateKey(privateKey, encKey)),
      new_recovery_auth_key: b64.enc(frk.authKey),
      new_enc_private_key_recovery: b64.enc(await wrapPrivateKey(privateKey, frk.encKey)),
      ticket: begin.ticket,
    });
    await finishLogin(set, res.user, res.token, { publicKey: b64.dec(begin.public_key), privateKey });
    return { recoveryKey: fresh.display };
  },

  changePassword: async (current, next) => {
    const { identity } = get();
    if (!identity) throw new Error("locked");
    const bundle = await usersApi.myKeys();
    const cur = await deriveAll(current, b64.dec(bundle.kdf_salt), toParams(bundle.kdf_params));
    const salt = randomBytes(16);
    const nxt = await deriveAll(next, salt, DEFAULT_KDF);
    await authApi.changePassword({
      auth_key: b64.enc(cur.authKey),
      new_auth_key: b64.enc(nxt.authKey),
      new_kdf_salt: b64.enc(salt),
      new_kdf_params: DEFAULT_KDF,
      new_enc_private_key: b64.enc(await wrapPrivateKey(identity.privateKey, nxt.encKey)),
    });
  },

  newRecoveryKey: async (password) => {
    const { identity } = get();
    if (!identity) throw new Error("locked");
    const bundle = await usersApi.myKeys();
    const cur = await deriveAll(password, b64.dec(bundle.kdf_salt), toParams(bundle.kdf_params));
    const fresh = newRecoveryKey();
    const frk = recoveryToKeys(fresh.bytes);
    await authApi.replaceRecovery({
      auth_key: b64.enc(cur.authKey),
      recovery_auth_key: b64.enc(frk.authKey),
      enc_private_key_recovery: b64.enc(await wrapPrivateKey(identity.privateKey, frk.encKey)),
    });
    set((s) => (s.me ? { me: { ...s.me, has_recovery: true } } : s));
    return fresh.display;
  },

  logout: async () => {
    const me = get().me;
    // First, while the token still works, so a call is left properly.
    for (const hook of logoutHooks) {
      try {
        await hook();
      } catch {
        /* signing out goes ahead regardless */
      }
    }
    try {
      await authApi.logout();
    } catch {
      /* the token may already be invalid */
    }
    storage.remove(TOKEN_KEY);
    api.setToken(null);
    pendingMfa = null;
    pendingPolicy = null;
    try {
      if (me) {
        clearLock(me.id);
        // the search index's key goes at once; its database follows once
        // nothing has it open (search/keys.ts)
        void wipeSearchIndex(me.id);
        await keystore.clear(me.id);
      }
    } finally {
      // Signed out whatever the key store says: a dead database handle (iOS
      // after a long time in the background) made clear() throw, and the app
      // sat "ready" with no token and no connection, going nowhere.
      set({ status: "anonymous", me: null, token: null, identity: null, settingsKey: null, error: null });
    }
    // another account signed in on this browser carries on in its place
    if (me) forgetAccount(me.id);
    const next = nextAccount();
    if (next) {
      storage.set(TOKEN_KEY, next.token);
      location.assign(placeOf(next.id));
    }
  },

  setMe: (me) => {
    set({ me });
    // the switcher shows the name and picture as they are now
    const token = get().token;
    if (token) rememberAccount(me, token);
  },
}));

/**
 * A token the server no longer accepts ends the session here.
 *
 * Nothing used to act on a 401: the only route out was the gateway's 4001
 * close, which needs a live socket the server chooses to close, so a tab that
 * is offline or mid-backoff kept showing its cached conversation and toasting
 * on every write. Only a signed-in account is signed out — a guest page runs
 * its own token beside an idle store — and a burst of 401s signs out once.
 */
let signingOut = false;
api.onUnauthorized = () => {
  if (signingOut || useAuth.getState().status !== "ready") return;
  signingOut = true;
  void useAuth
    .getState()
    .logout()
    .catch(() => undefined)
    .finally(() => {
      signingOut = false;
    });
};
