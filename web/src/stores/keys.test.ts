import { vi } from "vitest";
import { api } from "@/api/client";
import type { RequestOptions } from "@/api/client";
import type { KeyGrant, Message } from "@/api/types";
import { b64 } from "@/crypto/bytes";
import { channelKey, keyCommitment, newRootKey } from "@/crypto/conv";
import { generateIdentity, unwrapGrant, wrapGrant } from "@/crypto/keys";
import { keystore } from "@/crypto/keystore";
import { encryptMessage } from "@/crypto/message";
import { decryptForStore } from "@/lib/decrypt";
import { ring } from "@/lib/keyring";
import { applyEvent } from "@/gateway/dispatch";
import { useAuth } from "./auth";
import { useChannels } from "./channels";
import { closePins } from "@/lib/keyPins";
import { useKeyAlerts } from "./keyAlerts";
import { keyTimings, useKeys } from "./keys";
import { useMessages } from "./messages";
import { useUsers } from "./users";

const alice = generateIdentity();
const bob = generateIdentity();
/** The key a hostile server would like this client to seal a conversation to. */
const mallory = generateIdentity();

function user(id: string, pub: Uint8Array) {
  return {
    id,
    username: id,
    display_name: id,
    avatar_id: null,
    banner_id: null,
    about_me: "",
    pronouns: "",
    accent_color: null, accent_color_2: null,
    status_text: "",
    status_emoji: "",
    status_expires_at: null,
    public_key: b64.enc(pub),
    flags: 0,
    created_at: 0,
  };
}

/** A DM channel as the client holds it, which is where its membership comes from. */
function dm(id: string, recipients: string[]) {
  return {
    id,
    server_id: null,
    type: "dm" as const,
    name: "",
    topic: "",
    position: 0,
    parent_id: null,
    nsfw: false,
    slowmode_seconds: 0,
    user_limit: 0,
    owner_id: null,
    icon_id: null,
    last_message_id: null,
    created_at: 0,
    recipient_ids: recipients,
    key_conv_id: id,
  };
}

/**
 * The keys_needed handler starts the grant beside the event queue rather than
 * in it, so the event returning says nothing about the upload. One macrotask
 * turn is past every microtask the grant path takes.
 */
describe("key manager", () => {
  const putGrants: { conv_id: string; epoch: number; user_id: string; wrapped_key: string }[] = [];

  /**
   * Waits for the grant path to go quiet, rather than for a fixed number of
   * ticks.
   *
   * This was one `setTimeout(0)` — a single macrotask — and the path it waits
   * on does real WebCrypto, which takes more than one. On a loaded machine the
   * assertion ran first and the grant landed during the *next* test, so one
   * test saw no grants and the one after it saw a duplicate. Both passed when
   * run on their own, which is the least useful way for a test to be wrong.
   */
  // Settled is several quiet ticks in a row: one was enough on an idle
  // machine, but under the whole suite sealing a grant can take longer than a
  // tick, and the test read the grants before the first one was put.
  const settled = async (): Promise<void> => {
    let seen = -1;
    for (let i = 0, quiet = 0; i < 200 && quiet < 6; i++) {
      quiet = seen === putGrants.length ? quiet + 1 : 0;
      seen = putGrants.length;
      await new Promise((r) => setTimeout(r, 5));
    }
  };

  beforeEach(async () => {
    // a newcomer this device has not heard of is looked at again, quickly here
    keyTimings.outsiderRetryMs = [20, 20, 20];
    keyTimings.askAgainMs = [10_000];
    // Anything still in flight from the last test belongs to the last test.
    await settled();
    putGrants.length = 0;
    useKeyAlerts.getState().reset();
    closePins();
    await keystore.clear("A");
    await keystore.clear("B");
    useKeys.getState().reset();
    useMessages.getState().clearAll();
    useChannels.getState().clear();
    useUsers.getState().clear();
    useUsers.getState().upsert([user("A", alice.publicKey), user("B", bob.publicKey)]);
    api.request = <T>(method: string, path: string, body?: unknown, _opts?: RequestOptions): Promise<T> => {
      if (method === "POST" && path === "/keys/epochs") return Promise.resolve({ conv_id: "conv1", epoch: 0, user_ids: ["A", "B"] } as T);
      if (method === "POST" && path === "/keys/grants") {
        putGrants.push(...(body as typeof putGrants));
        return Promise.resolve({ inserted: (body as unknown[]).length } as T);
      }
      if (method === "GET" && path === "/keys/needs") return Promise.resolve([] as T);
      if (method === "POST" && path === "/keys/request") return Promise.resolve(undefined as T);
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    };
  });

  it("asks for an exact unopenable grant to be replaced", async () => {
    await useKeys.getState().init("A", alice);
    const request = vi.spyOn(api, "request").mockResolvedValue(undefined);
    const grant = { conv_id: "conv1", epoch: 0, user_id: "A", wrapped_key: b64.enc(new Uint8Array([1, 2, 3])), wrapped_by: "B", created_at: 0 };
    await useKeys.getState().ingestGrants([grant]);
    expect(request).toHaveBeenCalledWith("POST", "/keys/request", { conv_id: "conv1", bad_epoch: 0, bad_wrapped_key: grant.wrapped_key });
    expect(ring.get("conv1", 0)).toBeUndefined();
  });

  it("takes a key only if it matches its epoch's fingerprint", async () => {
    await useKeys.getState().init("A", alice);
    const request = vi.spyOn(api, "request").mockResolvedValue(undefined);
    const root = newRootKey();
    const grant = async (conv: string, commitment?: string): Promise<KeyGrant> => ({ conv_id: conv, epoch: 0, user_id: "A", wrapped_key: b64.enc(await wrapGrant(alice.publicKey, root)), wrapped_by: "B", created_at: 0, ...(commitment ? { commitment } : {}) });
    // sealed properly, so it opens: only the fingerprint shows it is not the epoch's key
    const other = await grant("conv1", b64.enc(keyCommitment(newRootKey(), "conv1")));
    await useKeys.getState().ingestGrants([other]);
    expect(ring.get("conv1", 0)).toBeUndefined();
    expect(request).toHaveBeenCalledWith("POST", "/keys/request", { conv_id: "conv1", bad_epoch: 0, bad_wrapped_key: other.wrapped_key });
    await useKeys.getState().ingestGrants([await grant("conv2", b64.enc(keyCommitment(root, "conv2")))]);
    expect(ring.get("conv2", 0)).toEqual(root);
    // from before fingerprints
    await useKeys.getState().ingestGrants([await grant("conv3")]);
    expect(ring.get("conv3", 0)).toEqual(root);
  });

  it("gives the server the fingerprint of the key it hands out", async () => {
    await useKeys.getState().init("A", alice);
    const base = api.request;
    let commitment = "";
    api.request = <T>(method: string, path: string, body?: unknown, opts?: RequestOptions): Promise<T> => {
      if (method === "POST" && path === "/keys/epochs") commitment = (body as { commitment: string }).commitment;
      return base<T>(method, path, body, opts);
    };
    await useKeys.getState().ensureConvKey("conv1");
    const sealed = putGrants.find((g) => g.user_id === "A")!;
    const root = await unwrapGrant(alice.privateKey, alice.publicKey, b64.dec(sealed.wrapped_key));
    expect(b64.dec(commitment)).toEqual(keyCommitment(root, "conv1"));
  });

  it("never selects an old cached root for a new send after rotation", async () => {
    await useKeys.getState().init("A", alice);
    ring.set("conv1", 0, newRootKey());
    api.request = vi.fn(async (_method, path) => {
      if (path === "/keys/epochs") return { epochs: [{ epoch: 0 }, { epoch: 1 }], flags: { needs_rotation: false } };
      if (path === "/keys/grants") return [];
      throw new Error(`unexpected ${path}`);
    }) as typeof api.request;
    expect(await useKeys.getState().ensureConvKey("conv1", true)).toBeNull();
    const current = newRootKey();
    ring.set("conv1", 1, current);
    expect(await useKeys.getState().ensureConvKey("conv1", true)).toEqual({ epoch: 1, root: current });
  });

  it("creates an epoch, grants everyone, and the recipient can unwrap it", async () => {
    await useKeys.getState().init("A", alice);
    const got = await useKeys.getState().ensureConvKey("conv1");
    expect(got?.epoch).toBe(0);
    expect(putGrants.map((g) => g.user_id).sort()).toEqual(["A", "B"]);
    const forBob = putGrants.find((g) => g.user_id === "B")!;
    const rootA = ring.get("conv1", 0)!;

    // Bob's session ingests the grant and derives the same channel key.
    useKeys.getState().reset();
    await useKeys.getState().init("B", bob);
    const grant: KeyGrant = { conv_id: "conv1", epoch: 0, user_id: "B", wrapped_key: forBob.wrapped_key, wrapped_by: "A", created_at: 1 };
    const changed = await useKeys.getState().ingestGrants([grant]);
    expect(changed).toEqual(["conv1"]);
    expect(b64.enc(ring.get("conv1", 0)!)).toBe(b64.enc(rootA));
    expect(b64.enc(channelKey(ring.get("conv1", 0)!, "chan9"))).toBe(b64.enc(channelKey(rootA, "chan9")));
  });

  it("opens a pile of grants together and writes them to disk in one go", async () => {
    await useKeys.getState().init("A", alice);
    // ten conversations' first epochs, each granted to bob
    const grants: KeyGrant[] = [];
    for (let i = 0; i < 10; i++) {
      const root = newRootKey();
      grants.push({ conv_id: `pile${i}`, epoch: 0, user_id: "B", wrapped_key: b64.enc(await wrapGrant(bob.publicKey, root)), wrapped_by: "A", created_at: 1 });
    }
    useKeys.getState().reset();
    await useKeys.getState().init("B", bob);
    const one = vi.spyOn(keystore, "saveRoot");
    const many = vi.spyOn(keystore, "saveRoots");
    const changed = await useKeys.getState().ingestGrants(grants);
    expect(changed.sort()).toEqual(grants.map((g) => g.conv_id).sort());
    expect(one).not.toHaveBeenCalled();
    expect(many).toHaveBeenCalledTimes(1);
    expect((await keystore.loadRoots("B")).filter((r) => r.conv.startsWith("pile"))).toHaveLength(10);
    one.mockRestore();
    many.mockRestore();
  });

  it("does not put keys back into a session that locked while they were being opened", async () => {
    await useKeys.getState().init("B", bob);
    const grant: KeyGrant = { conv_id: "late", epoch: 0, user_id: "B", wrapped_key: b64.enc(await wrapGrant(bob.publicKey, newRootKey())), wrapped_by: "A", created_at: 1 };
    const ingesting = useKeys.getState().ingestGrants([grant]);
    // the lock lands while the grant is being opened
    useKeys.getState().reset();
    expect(await ingesting).toEqual([]);
    expect(ring.get("late", 0)).toBeUndefined();
    expect((await keystore.loadRoots("B")).some((r) => r.conv === "late")).toBe(false);
  });

  it("carries out a rotation that was left pending while nobody was online", async () => {
    await useKeys.getState().init("A", alice);
    await useKeys.getState().ensureConvKey("conv1");
    const base = api.request;
    const reasons: string[] = [];
    api.request = <T>(method: string, path: string, body?: unknown, opts?: RequestOptions): Promise<T> => {
      if (method === "GET" && path === "/keys/needs") return Promise.resolve([{ conv_id: "conv1", epoch: 0, user_ids: [], rotate: true }] as T);
      if (method === "POST" && path === "/keys/epochs") {
        reasons.push((body as { reason: string }).reason);
        return Promise.resolve({ conv_id: "conv1", epoch: 1, user_ids: ["A", "B"] } as T);
      }
      return base<T>(method, path, body, opts);
    };
    putGrants.length = 0;
    await useKeys.getState().reconcile();
    expect(reasons).toEqual(["membership change"]);
    expect(ring.latest("conv1")?.epoch).toBe(1);
    expect(putGrants.filter((g) => g.epoch === 1).map((g) => g.user_id).sort()).toEqual(["A", "B"]);
  });

  it("re-decrypts waiting messages when a grant arrives", async () => {
    // Alice encrypts a message with a root Bob does not have yet.
    await useKeys.getState().init("A", alice);
    await useKeys.getState().ensureConvKey("conv1");
    const root = ring.get("conv1", 0)!;
    const env = await encryptMessage(channelKey(root, "chan9"), { conv: "conv1", epoch: 0, channelId: "chan9", authorId: "A" }, { t: "secret" });
    const forBob = putGrants.find((g) => g.user_id === "B")!;

    useKeys.getState().reset();
    await useKeys.getState().init("B", bob);
    useAuth.setState({ me: { ...user("B", bob.publicKey), presence_pref: "online", dm_privacy: "everyone", has_recovery: false } });
    const m: Message = {
      id: "5",
      channel_id: "chan9",
      author_id: "A",
      type: "default",
      ciphertext: env,
      key_conv_id: "conv1",
      key_epoch: 0,
      reply_to_id: null,
      mentions: [],
      mention_roles: [],
      mention_everyone: false,
      attachments: [],
      reactions: [],
      flags: 0,
      edited_at: null,
      created_at: 1,
    };
    const first = await decryptForStore(m);
    expect(first.decryptError).toBe("no_key");
    await applyEvent({ t: "message_create", d: m });
    expect(useMessages.getState().channels["chan9"]?.byId["5"]?.decryptError).toBe("no_key");
    expect(useKeys.getState().waiting["conv1"]).toBe(true);
    await applyEvent({ t: "key_grant", d: { conv_id: "conv1", epoch: 0, user_id: "B", wrapped_key: forBob.wrapped_key, wrapped_by: "A", created_at: 1 } });
    const after = useMessages.getState().channels["chan9"]?.byId["5"];
    expect(after?.decryptError).toBeUndefined();
    expect(after?.payload).toEqual({ t: "secret" });
    expect(useKeys.getState().waiting["conv1"]).toBeUndefined();
  });

  it("does not use a new epoch whose grants never reached the server", async () => {
    let epoch = 0;
    let failGrants = true;
    api.request = <T>(method: string, path: string, body?: unknown, _opts?: RequestOptions): Promise<T> => {
      if (method === "POST" && path === "/keys/epochs") return Promise.resolve({ conv_id: "conv1", epoch: epoch++, user_ids: ["A", "B"] } as T);
      if (method === "POST" && path === "/keys/grants") {
        if (failGrants) return Promise.reject(new Error("offline"));
        putGrants.push(...(body as typeof putGrants));
        return Promise.resolve({ inserted: (body as unknown[]).length } as T);
      }
      return Promise.reject(new Error(`unexpected ${method} ${path}`));
    };
    await useKeys.getState().init("A", alice);
    await expect(useKeys.getState().ensureConvKey("conv1")).rejects.toThrow();
    expect(ring.latest("conv1")).toBeUndefined();
    expect(await keystore.loadRoots("A")).toEqual([]);

    // The retry starts a fresh epoch and hands it out before using it.
    failGrants = false;
    const got = await useKeys.getState().ensureConvKey("conv1");
    expect(got?.epoch).toBe(1);
    expect(putGrants.filter((g) => g.epoch === 1).map((g) => g.user_id).sort()).toEqual(["A", "B"]);
  });

  it("refuses to wrap the conversation key for an account that is not in it", async () => {
    // The conversation is a DM between A and B, which is what this client draws.
    useChannels.setState({ byId: { conv1: dm("conv1", ["A", "B"]) } });
    useUsers.getState().upsert(user("Z", mallory.publicKey));
    await useKeys.getState().init("A", alice);
    await useKeys.getState().ensureConvKey("conv1");
    putGrants.length = 0;

    // A fabricated keys_needed naming an account outside the conversation.
    await applyEvent({ t: "keys_needed", d: { conv_id: "conv1", epoch: 0, user_ids: ["B", "Z"] } });
    await settled();
    expect(putGrants.map((g) => g.user_id)).toEqual(["B"]);
    // looked at again in case its join had not arrived yet, never sealed for, then refused and said so
    await vi.waitFor(() => expect(useKeyAlerts.getState().events.some((e) => e.kind === "outsider_refused")).toBe(true));
    expect(putGrants.map((g) => g.user_id)).toEqual(["B"]);
  });

  it("hands the key to someone whose join arrives after the ask for it", async () => {
    // A group of A and B; C is asked for before this device has heard C was added.
    useChannels.setState({ byId: { conv1: dm("conv1", ["A", "B"]) } });
    useUsers.getState().upsert(user("C", mallory.publicKey));
    await useKeys.getState().init("A", alice);
    await useKeys.getState().ensureConvKey("conv1");
    putGrants.length = 0;
    await applyEvent({ t: "keys_needed", d: { conv_id: "conv1", epoch: 0, user_ids: ["C"] } });
    await settled();
    expect(putGrants).toEqual([]);
    // the join lands, and the next look hands C the key
    useChannels.setState({ byId: { conv1: dm("conv1", ["A", "B", "C"]) } });
    await vi.waitFor(() => expect(putGrants.map((g) => g.user_id)).toEqual(["C"]));
    expect(useKeyAlerts.getState().events.some((e) => e.kind === "outsider_refused")).toBe(false);
  });

  it("still grants when the membership cannot be checked, and says it could not", async () => {
    // Nothing in the channels or servers store answers to "conv1": a client
    // that refused here would strand real members, so it grants and records it.
    useChannels.setState({ byId: {} });
    await useKeys.getState().init("A", alice);
    await useKeys.getState().ensureConvKey("conv1");
    expect(putGrants.map((g) => g.user_id).sort()).toEqual(["A", "B"]);
    expect(useKeyAlerts.getState().events.some((e) => e.kind === "unchecked")).toBe(true);
  });

  it("withholds the key when a member's identity key changed, and hands it over once accepted", async () => {
    useChannels.setState({ byId: { conv1: dm("conv1", ["A", "B"]) } });
    await useKeys.getState().init("A", alice);
    // B's real key is pinned by the first sight of their user object.
    useUsers.getState().upsert(user("B", bob.publicKey));
    await useKeys.getState().ensureConvKey("conv1");
    expect(putGrants.map((g) => g.user_id).sort()).toEqual(["A", "B"]);

    // The server now serves someone else's key under B's id.
    putGrants.length = 0;
    useUsers.getState().upsert(user("B", mallory.publicKey));
    await applyEvent({ t: "keys_needed", d: { conv_id: "conv1", epoch: 0, user_ids: ["B"] } });
    await settled();
    expect(putGrants).toEqual([]);
    expect(useKeyAlerts.getState().pending["B"]?.offered).toBe(b64.enc(mallory.publicKey));

    // Only a deliberate acceptance lets the key through, and then it is sealed
    // to the new key rather than the old one.
    useKeyAlerts.getState().acceptKeyChange("B");
    await useKeys.getState().grantTo("conv1", 0, ["B"]);
    expect(putGrants.map((g) => g.user_id)).toEqual(["B"]);
    const wrapped = b64.dec(putGrants[0]!.wrapped_key);
    expect(b64.enc(await unwrapGrant(mallory.privateKey, mallory.publicKey, wrapped))).toBe(b64.enc(ring.get("conv1", 0)!));
  });

  it("keeps roots off the disk behind a passcode", async () => {
    await keystore.saveRoot("A", "old", 0, newRootKey());
    const lock = JSON.stringify({ v: 1, salt: "AA", wrapped: "AA", pub: "AA", params: {} });
    vi.stubGlobal("localStorage", { getItem: (k: string) => (k === "ec.lock.A" ? lock : null), setItem: () => undefined, removeItem: () => undefined });
    try {
      await useKeys.getState().init("A", alice);
      expect(await keystore.loadRoots("A")).toEqual([]);
      expect(ring.get("old", 0)).toBeUndefined();
      await useKeys.getState().ensureConvKey("conv1");
      expect(ring.get("conv1", 0)).toBeDefined();
      expect(await keystore.loadRoots("A")).toEqual([]);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
