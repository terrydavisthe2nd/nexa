import { create } from "zustand";
import { ApiError } from "@/api/client";
import { keys as keysApi } from "@/api/keys";
import { users as usersApi } from "@/api/users";
import type { KeyGrant } from "@/api/types";
import { b64, equal } from "@/crypto/bytes";
import { keyCommitment, newRootKey } from "@/crypto/conv";
import { keystore } from "@/crypto/keystore";
import { unwrapGrant, wrapGrant, type Identity } from "@/crypto/keys";
import { hasLock } from "@/lib/appLock";
import { checkRecipients } from "@/lib/convMembers";
import { clearFollows } from "@/lib/follows";
import { closePins, openPins } from "@/lib/keyPins";
import { clearKeyring, missingKeyListeners, ring } from "@/lib/keyring";
import { judgeKey, useKeyAlerts } from "./keyAlerts";
import { useMessages } from "./messages";
import { redecryptReplyTargets } from "./replyTargets";
import { useUsers } from "./users";

interface KeysState {
  userId: string | null;
  identity: Identity | null;
  waiting: Record<string, true>;
  init: (userId: string, identity: Identity) => Promise<void>;
  ingestGrants: (grants: KeyGrant[]) => Promise<string[]>;
  ensureConvKey: (conv: string, current?: boolean) => Promise<{ epoch: number; root: Uint8Array } | null>;
  grantTo: (conv: string, epoch: number, userIds: string[]) => Promise<void>;
  reconcile: () => Promise<void>;
  rotate: (conv: string, reason?: string) => Promise<void>;
  markWaiting: (conv: string) => void;
  reset: () => void;
}

const pendingEnsure = new Map<string, Promise<{ epoch: number; root: Uint8Array } | null>>();

/** Whether an opened key is the one its epoch was made with. Epochs from before fingerprints have none to check. */
function matchesEpoch(g: KeyGrant, root: Uint8Array): boolean {
  if (!g.commitment) return true;
  try {
    return equal(keyCommitment(root, g.conv_id), b64.dec(g.commitment));
  } catch {
    return false;
  }
}

/**
 * Asking the holders of a conversation's keys again, while this device waits
 * for them: soon after it finds it lacks them, then now and then, a few times.
 * The ask made for it as it came in can be missed (nobody holding them was
 * online, or their app had not heard of the newcomer yet), and nothing else
 * asked again until a holder next connected.
 */
const asking = new Map<string, { timer: ReturnType<typeof setTimeout>; tries: number }>();

function askAgain(conv: string): void {
  if (asking.has(conv)) return;
  const next = (tries: number) => {
    if (tries >= keyTimings.askAgainMs.length) {
      asking.delete(conv);
      return;
    }
    const timer = setTimeout(() => {
      // got them meanwhile, or signed out: nothing to ask for
      if (!useKeys.getState().waiting[conv] || !useKeys.getState().userId) {
        asking.delete(conv);
        return;
      }
      void keysApi.request(conv).catch(() => undefined);
      next(tries + 1);
    }, keyTimings.askAgainMs[tries]);
    asking.set(conv, { timer, tries });
  };
  next(0);
}

function stopAsking(conv?: string): void {
  for (const [c, a] of asking) {
    if (conv && c !== conv) continue;
    clearTimeout(a.timer);
    asking.delete(c);
  }
}

/**
 * Called when the keys are forgotten (a lock, a sign-out). The search index
 * holds decrypted messages; it registers here and closes with them, or the
 * next person on this tab could read a conversation they have no grant for by
 * typing a word into search.
 */
export const keysGoneHooks: (() => void)[] = [];

/**
 * The key to seal to, as base64 — the shape the pin file keeps, so the two can
 * be compared without decoding either.
 */
async function publicKeyOf(userId: string): Promise<string> {
  const cached = useUsers.getState().byId[userId]?.public_key;
  if (cached) return cached;
  const res = await usersApi.publicKey(userId);
  return res.public_key;
}

/**
 * The identity key to seal something to for a person: the one this device
 * holds for them (or the server's answer), judged against the pin. Null when
 * it is not the key pinned for them; judgeKey has put the change in front of
 * a human, and nothing is sealed to it until they accept it. Every conversation
 * grant goes through here, and so does anything else sealed to one person
 * (a friend request's note), so neither can skip the check.
 */
export async function sealingKeyOf(userId: string): Promise<Uint8Array | null> {
  const pub = await publicKeyOf(userId);
  if (judgeKey(userId, pub) === "changed") return null;
  return b64.dec(pub);
}

/**
 * Caches a root on this device, except behind a passcode: a root at rest reads
 * its messages without one, so a locked device takes its roots from the grants
 * that come with every ready instead.
 */
async function persistRoot(userId: string, conv: string, epoch: number, root: Uint8Array): Promise<void> {
  if (hasLock(userId)) return;
  await keystore.saveRoot(userId, conv, epoch, root);
}

/** Grants opened at a time: enough to overlap their decryptions, few enough to keep the tab drawing. */
const UNWRAP_BATCH = 32;

/**
 * How long to wait before trying again someone this device did not know as a
 * member when it was asked to hand them a key. The ask can outrun the event
 * that says they joined: it is handled beside the event queue, and someone
 * who has just come into a server is not in this device's list of its members
 * for a moment yet. Refusing them for good left them without the keys until
 * a holder next connected.
 */
export const keyTimings = { outsiderRetryMs: [1500, 5000, 15000], askAgainMs: [2000, 20000, 60000, 180000, 600000] };

/**
 * Narrows a server-supplied recipient list to the accounts this device is
 * willing to hand a conversation key to.
 *
 * Nothing about who gets a key was ever the client's decision before: the
 * `user_ids` in a `keys_needed` event, in `/keys/needs` and in the answer to
 * creating an epoch went straight into the wrapping. Here they are held against
 * the membership this device already knows and draws, and anything outside it
 * is set aside: looked at again a few times, in case this device has not heard
 * of them joining yet, and only then refused and written down where it can be
 * seen.
 */
function recipientsFor(conv: string, epoch: number, userIds: string[], attempt: number): { allowed: string[]; later: string[] } {
  const seen = checkRecipients(conv, userIds);
  // Never this account. A membership list that has not caught up with a join
  // would otherwise leave a device holding a root with no grant of its own,
  // which is the one way this check could lock somebody out of their own
  // conversation.
  const me = useKeys.getState().userId;
  const outsiders = me ? seen.outsiders.filter((id) => id !== me) : seen.outsiders;
  const allowed = me && userIds.includes(me) && !seen.allowed.includes(me) ? [...seen.allowed, me] : seen.allowed;
  if (outsiders.length > 0 && attempt >= keyTimings.outsiderRetryMs.length) {
    console.warn(`keys: refusing to wrap ${conv} epoch ${epoch} for ${outsiders.join(", ")} — not in this conversation`);
    useKeyAlerts.getState().report({ kind: "outsider_refused", conv, epoch, count: outsiders.length, ...(outsiders.length === 1 ? { userId: outsiders[0]! } : {}) });
  }
  // Fail open, and say so. A large server's members arrive a page at a time, so
  // an id missing from the list in hand may simply not have been fetched yet;
  // refusing there would leave real members unable to read messages they are
  // entitled to, which is the worse of the two failures.
  if (!seen.checked && userIds.length > 0) useKeyAlerts.getState().report({ kind: "unchecked", conv, epoch, count: userIds.length });
  return { allowed, later: attempt < keyTimings.outsiderRetryMs.length ? outsiders : [] };
}

/** Wraps a root for each user and uploads the grants; users without a public key are skipped. */
async function putGrantsFor(conv: string, epoch: number, root: Uint8Array, wanted: string[], attempt = 0): Promise<void> {
  // The one place every grant this client ever makes passes through, so the
  // recipient check cannot be forgotten by a path added later: createEpoch,
  // /keys/needs and the keys_needed event all end up here.
  const { allowed: userIds, later } = recipientsFor(conv, epoch, wanted, attempt);
  if (later.length > 0) {
    const wait = keyTimings.outsiderRetryMs[attempt]!;
    setTimeout(() => {
      // the same root, if this session still holds it
      const still = ring.get(conv, epoch);
      if (still) void putGrantsFor(conv, epoch, still, later, attempt + 1).catch(() => undefined);
    }, wait);
  }
  const uploads: { conv_id: string; epoch: number; user_id: string; wrapped_key: string }[] = [];
  const wrap = async (uid: string): Promise<boolean> => {
    try {
      const pub = await sealingKeyOf(uid);
      // A key that is not the one this device pinned for that person does not
      // get a copy of the conversation until a human has looked at it. The
      // question is raised by judgeKey; this path just declines quietly, and
      // the grant is made on the next reconcile once it is answered.
      if (!pub) {
        useKeyAlerts.getState().report({ kind: "grant_withheld", conv, epoch, userId: uid });
        return true;
      }
      uploads.push({ conv_id: conv, epoch, user_id: uid, wrapped_key: b64.enc(await wrapGrant(pub, root)) });
      return true;
    } catch (err) {
      // An account that is genuinely gone is skipped for good; anything else —
      // a 500, a timeout, an offline fetch — says nothing about that person,
      // and dropping them silently costs them every message of this epoch.
      return err instanceof ApiError && err.status === 404;
    }
  };
  const again: string[] = [];
  // a few keys looked up at a time: a large server's members are not all known here
  for (let i = 0; i < userIds.length; i += 16) {
    const batch = userIds.slice(i, i + 16);
    const ok = await Promise.all(batch.map(wrap));
    for (const [j, fine] of ok.entries()) if (!fine) again.push(batch[j]!);
  }
  if (again.length > 0) {
    await new Promise((r) => setTimeout(r, 500));
    // The rotation itself still goes ahead if this fails: it is what takes the
    // key away from whoever just left, and holding it back for one unreachable
    // lookup would be worse. The server keeps track of who is still owed a
    // grant, and reconcile() hands it over on the next connection.
    const healed = await Promise.all(again.map(wrap));
    const missed = again.filter((_, j) => !healed[j]);
    if (missed.length > 0) console.warn(`keys: ${missed.length} member(s) could not be granted ${conv} epoch ${epoch}; the server will ask again`);
  }
  for (let i = 0; i < uploads.length; i += 200) {
    await keysApi.putGrants(uploads.slice(i, i + 200));
  }
}

/**
 * Creates the next epoch and hands it out. The root only goes into use once
 * its grants are uploaded: an epoch nobody holds a grant for is never passed
 * on, so anything written under it would stay unreadable to everyone else.
 */
async function createAndGrant(conv: string, userId: string, reason?: string): Promise<{ epoch: number; root: Uint8Array }> {
  const root = newRootKey();
  const created = await keysApi.createEpoch(conv, reason, b64.enc(keyCommitment(root, conv)));
  const targets = created.user_ids.includes(userId) ? created.user_ids : [...created.user_ids, userId];
  await putGrantsFor(conv, created.epoch, root, targets);
  ring.set(conv, created.epoch, root);
  await persistRoot(userId, conv, created.epoch, root);
  return { epoch: created.epoch, root };
}

export const useKeys = create<KeysState>()((set, get) => ({
  userId: null,
  identity: null,
  waiting: {},
  init: async (userId, identity) => {
    set({ userId, identity, waiting: {} });
    // Before anything else, and before the gateway connects: the first ready
    // upserts every user the account can see, and each of those keys has to be
    // judged against what this device remembers rather than pinned late.
    openPins(userId);
    if (hasLock(userId)) {
      // Roots cached before the passcode was set would read messages without it.
      await keystore.forgetRoots(userId);
      return;
    }
    const roots = await keystore.loadRoots(userId);
    for (const r of roots) ring.set(r.conv, r.epoch, r.root);
  },
  ingestGrants: async (grants) => {
    const { identity, userId } = get();
    if (!identity || !userId) return [];
    const changed: string[] = [];
    const opened: { conv: string; epoch: number; root: Uint8Array }[] = [];
    const fresh = grants.filter((g) => !ring.get(g.conv_id, g.epoch));
    // A first sign-in on a device brings every grant the account holds, and
    // each used to be opened and written to disk on its own, one after the
    // other, while ready and every event behind it waited.
    for (let i = 0; i < fresh.length; i += UNWRAP_BATCH) {
      const batch = fresh.slice(i, i + UNWRAP_BATCH);
      // a grant this device cannot open is left; another holder may send a good one
      const roots = await Promise.all(batch.map((g) => unwrapGrant(identity.privateKey, identity.publicKey, b64.dec(g.wrapped_key)).catch(() => null)));
      // Locked or signed out meanwhile: these keys belong to a session that has
      // ended, and putting them back would undo the forgetting it just did.
      if (get().identity !== identity) return [];
      for (const [j, root] of roots.entries()) {
        const g = batch[j]!;
        if (!root || root.length !== 32 || !matchesEpoch(g, root)) {
          // A row already present used to prevent every honest holder from
          // replacing an unopenable grant, however often we asked again. The
          // server counts these: a key several people cannot use gets replaced.
          await keysApi.request(g.conv_id, g).catch(() => undefined);
          continue;
        }
        if (ring.get(g.conv_id, g.epoch)) continue;
        // In memory the key works at once, whether or not the disk takes it: a
        // dead database handle (iOS after a long time in the background) must
        // not leave messages saying "waiting for key" with the key right here.
        ring.set(g.conv_id, g.epoch, root);
        changed.push(g.conv_id);
        opened.push({ conv: g.conv_id, epoch: g.epoch, root });
      }
    }
    if (opened.length > 0 && !hasLock(userId)) {
      try {
        await keystore.saveRoots(userId, opened);
      } catch {
        /* kept for this session; the next grant or sign-in stores them again */
      }
    }
    if (changed.length > 0) {
      const set_ = new Set(changed);
      set((s) => {
        const waiting = { ...s.waiting };
        for (const c of changed) delete waiting[c];
        return { waiting };
      });
      for (const c of changed) stopAsking(c);
      await useMessages.getState().redecrypt((m) => m.decryptError === "no_key" && !!m.key_conv_id && set_.has(m.key_conv_id));
      await redecryptReplyTargets((m) => m.decryptError === "no_key" && !!m.key_conv_id && set_.has(m.key_conv_id));
    }
    return changed;
  },
  ensureConvKey: async (conv, current = false) => {
    // A sleeping device can hold an old root indefinitely. Before sealing new
    // text, ask which epoch is current rather than using the newest we hold.
    if (current) {
      let info = await keysApi.epochs(conv);
      if (info.flags.needs_rotation) {
        await get().rotate(conv, "membership change");
        info = await keysApi.epochs(conv);
        if (info.flags.needs_rotation) { get().markWaiting(conv); return null; }
      }
      const epoch = info.epochs.at(-1)?.epoch;
      if (epoch !== undefined) {
        let root = ring.get(conv, epoch);
        if (!root) {
          await get().ingestGrants((await keysApi.grants()).filter((g) => g.conv_id === conv));
          root = ring.get(conv, epoch);
        }
        if (root) return { epoch, root };
        get().markWaiting(conv);
        return null;
      }
    }
    const latest = ring.latest(conv);
    if (latest) return latest;
    const inflight = pendingEnsure.get(conv);
    if (inflight) return inflight;
    // Signed out or locked, there is nothing to make a key with — and a job
    // that ended before it was stored below would be stored finished, and
    // answer null to everyone after the unlock.
    if (!get().userId) return null;
    const job = (async () => {
      const { userId } = get();
      // inside the try, so the finally below always takes the job back out of
      // the map: a null cached here answered every later caller for good
      try {
        if (!userId) return null;
        return await createAndGrant(conv, userId);
      } catch (err) {
        if (err instanceof ApiError && (err.status === 409 || err.code === "epoch_race")) {
          const mine = await keysApi.grants();
          await get().ingestGrants(mine.filter((g) => g.conv_id === conv));
          const now = ring.latest(conv);
          if (now) return now;
          get().markWaiting(conv);
          return null;
        }
        throw err;
      } finally {
        pendingEnsure.delete(conv);
      }
    })();
    pendingEnsure.set(conv, job);
    return job;
  },
  grantTo: async (conv, epoch, userIds) => {
    const root = ring.get(conv, epoch);
    if (!root || userIds.length === 0) return;
    await putGrantsFor(conv, epoch, root, userIds);
  },
  reconcile: async () => {
    // Every conversation stands on its own: one that throws used to end the
    // walk, so everybody who joined the ones after it while this device was
    // away kept seeing "encrypted message" until another holder came online.
    let needs: Awaited<ReturnType<typeof keysApi.needs>>;
    try {
      needs = await keysApi.needs();
    } catch (err) {
      console.warn("keys: could not ask what needs granting", err);
      return;
    }
    let failed = 0;
    for (const n of needs) {
      if (!ring.get(n.conv_id, n.epoch)) continue;
      try {
        await get().grantTo(n.conv_id, n.epoch, n.user_ids);
        // someone lost access while no holder was online; the server lets only
        // the first device that asks create the new key
        if (n.rotate) await get().rotate(n.conv_id, "membership change");
      } catch (err) {
        failed += 1;
        console.warn("keys: could not reconcile a conversation", n.conv_id, err);
      }
    }
    if (failed > 0) console.warn(`keys: ${failed} conversation(s) still need granting`);
  },
  rotate: async (conv, reason) => {
    const { userId } = get();
    if (!userId) return;
    try {
      await createAndGrant(conv, userId, reason);
    } catch (err) {
      if (err instanceof ApiError && (err.status === 409 || err.code === "epoch_race")) return;
      throw err;
    }
  },
  markWaiting: (conv) => {
    if (!get().userId) return;
    if (!get().waiting[conv]) set((s) => ({ waiting: { ...s.waiting, [conv]: true } }));
    askAgain(conv);
  },
  reset: () => {
    clearKeyring();
    clearFollows();
    for (const h of keysGoneHooks) h();
    pendingEnsure.clear();
    stopAsking();
    // The pins themselves stay on the device — that is the point of them — but
    // this session's unanswered questions and its log go with the session.
    closePins();
    useKeyAlerts.getState().reset();
    set({ userId: null, identity: null, waiting: {} });
  },
}));

// a message this device could not read for want of its key: wait for it, and ask for it
missingKeyListeners.add((conv) => {
  const st = useKeys.getState();
  if (st.userId && !st.waiting[conv]) queueMicrotask(() => useKeys.getState().markWaiting(conv));
});
