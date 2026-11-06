import * as crypto from "./index";

describe("crypto module surface", () => {
  it("re-exports every public name", () => {
    const expected = [
      // bytes
      "b64",
      "utf8",
      "concat",
      "randomBytes",
      "equal",
      "u32be",
      "readU32be",
      // kdf
      "DEFAULT_KDF",
      "KDF_FLOOR",
      "KDF_CEILING",
      "WeakKdfError",
      "kdfParamsAcceptable",
      "deriveMaster",
      "deriveAuthKey",
      "deriveEncKey",
      "deriveSettingsKey",
      "newRecoveryKey",
      "recoveryKeyToBytes",
      "recoveryToKeys",
      // fingerprint
      "fingerprint",
      "fingerprintOfB64",
      // keys
      "generateIdentity",
      "publicKeyOf",
      "wrapPrivateKey",
      "unwrapPrivateKey",
      "seal",
      "open",
      "wrapGrant",
      "unwrapGrant",
      "DecryptError",
      // conv
      "newRootKey",
      "channelKey",
      "voiceFrameKey",
      "keyCommitment",
      "KeyRing",
      // message
      "aadBytes",
      "encryptMessage",
      "decryptMessage",
      "isEnvelope",
      "webLink",
      "sanitizeEmbed",
      "sanitizePayload",
      "sanitizeAttachment",
      "POLL_QUESTION_MAX",
      "POLL_ANSWER_MAX",
      "POLL_ANSWERS_MIN",
      "POLL_ANSWERS_MAX",
      "POLL_HOURS_MAX",
      "MESSAGE_LIMITS",
      "typedLength",
      "brokenLimit",
      // file
      "CHUNK",
      "encryptFile",
      "encryptChunks",
      "encryptedSize",
      "chunkCount",
      "newFileKey",
      "newBaseIv",
      "decryptFile",
      // keystore
      "keystore",
    ];
    for (const name of expected) {
      expect(Object.hasOwn(crypto, name)).toBe(true);
    }
    expect(Object.keys(crypto).sort()).toEqual(expected.sort());
  });

  it("drives an end-to-end conversation through the re-exported names", async () => {
    const alice = crypto.generateIdentity();
    const bob = crypto.generateIdentity();

    // Alice creates a conversation and grants Bob the root key.
    const root = crypto.newRootKey();
    const grant = await crypto.wrapGrant(bob.publicKey, root);
    const bobRoot = await crypto.unwrapGrant(bob.privateKey, bob.publicKey, grant);
    expect(crypto.equal(bobRoot, root)).toBe(true);

    const ring = new crypto.KeyRing();
    ring.set("conv-1", 0, bobRoot);
    const latest = ring.latest("conv-1");
    expect(latest?.epoch).toBe(0);

    // Alice sends a message on a channel; Bob decrypts it.
    const aad: crypto.Aad = { conv: "conv-1", epoch: 0, channelId: "chan-1", authorId: "alice" };
    const env = await crypto.encryptMessage(crypto.channelKey(root, "chan-1"), aad, { t: "hi bob" });
    expect(crypto.isEnvelope(env)).toBe(true);

    const bobKey = crypto.channelKey(ring.get("conv-1", 0) ?? new Uint8Array(0), "chan-1");
    expect((await crypto.decryptMessage(bobKey, env, aad)).t).toBe("hi bob");

    // Eve, holding the wrong root, gets nothing.
    const eveKey = crypto.channelKey(crypto.newRootKey(), "chan-1");
    await expect(crypto.decryptMessage(eveKey, env, aad)).rejects.toBeInstanceOf(crypto.DecryptError);
    expect(alice.publicKey.length).toBe(32);
  });

  it("wraps and unwraps an identity with a password-derived key", async () => {
    const id = crypto.generateIdentity();
    const master = await crypto.deriveMaster("hunter2", crypto.randomBytes(16), crypto.KDF_FLOOR);
    const encKey = crypto.deriveEncKey(master);
    const wrapped = await crypto.wrapPrivateKey(id.privateKey, encKey);
    const priv = await crypto.unwrapPrivateKey(wrapped, encKey);
    expect(crypto.equal(crypto.publicKeyOf(priv), id.publicKey)).toBe(true);
    expect(crypto.equal(crypto.deriveAuthKey(master), encKey)).toBe(false);
  });
});
