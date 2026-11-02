import { b64, equal, randomBytes, utf8 } from "./bytes";
import { channelKey, newRootKey } from "./conv";
import { DecryptError } from "./keys";
import {
  aadBytes,
  decryptMessage,
  encryptMessage,
  isEnvelope,
  type Aad,
  type AttachmentMeta,
  type Envelope,
  type MessagePayload,
  brokenLimit,
  typedLength,
  sanitizeAttachment,
  sanitizeEmbed,
} from "./message";

const root = newRootKey();
const key = channelKey(root, "chan-1");
const aad: Aad = { conv: "conv-1", epoch: 2, channelId: "chan-1", authorId: "user-1" };

it("rejects attachment IDs that could become paths or URLs", () => {
  const attachment = { blob_id: "123", name: "file.png", type: "image/png", size: 10, key: "key" };
  expect(sanitizeAttachment(attachment)).toEqual(attachment);
  for (const blob_id of ["../private/case-1", "../../files/secret", "/tmp/file", "123/../../api/v1/users/@me", "%2e%2e%2ffile", "1?x=2", "1#x", "1\\file", "", "1".repeat(21), "1\n"]) {
    expect(sanitizeAttachment({ ...attachment, blob_id }), blob_id).toBeNull();
  }
});

/** Encrypt arbitrary bytes under the real key and AAD, bypassing the JSON layer. */
async function forgeEnvelope(plaintext: Uint8Array, a: Aad = aad): Promise<Envelope> {
  const iv = randomBytes(12);
  const k = await crypto.subtle.importKey("raw", new Uint8Array(key), "AES-GCM", false, ["encrypt"]);
  const params: AesGcmParams = { name: "AES-GCM", iv: new Uint8Array(iv), additionalData: new Uint8Array(aadBytes(a)) };
  const ct = await crypto.subtle.encrypt(params, k, new Uint8Array(plaintext));
  return { v: 1, conv: a.conv, epoch: a.epoch, iv: b64.enc(iv), ct: b64.enc(new Uint8Array(ct)) };
}

describe("aadBytes", () => {
  it("binds conversation, epoch, channel and author", () => {
    expect(utf8.dec(aadBytes(aad))).toBe("conv-1|2|chan-1|user-1");
  });

  it("changes when any field changes", () => {
    const base = aadBytes(aad);
    expect(equal(aadBytes({ ...aad, conv: "conv-2" }), base)).toBe(false);
    expect(equal(aadBytes({ ...aad, epoch: 3 }), base)).toBe(false);
    expect(equal(aadBytes({ ...aad, channelId: "chan-2" }), base)).toBe(false);
    expect(equal(aadBytes({ ...aad, authorId: "user-2" }), base)).toBe(false);
  });
});

describe("encryptMessage / decryptMessage", () => {
  it("round trips a text payload", async () => {
    const payload: MessagePayload = { t: "hello world" };
    const env = await encryptMessage(key, aad, payload);
    expect(await decryptMessage(key, env, aad)).toEqual(payload);
  });

  it("round trips unicode, emoji and control characters", async () => {
    const payload: MessagePayload = {
      t: "日本語 — café 🚀 привет\nline\ttab \"quoted\" \\slash",
    };
    const env = await encryptMessage(key, aad, payload);
    const out = await decryptMessage(key, env, aad);
    expect(out.t).toBe(payload.t);
  });

  it("round trips attachments, stickers, embeds and flags", async () => {
    const att: AttachmentMeta[] = [
      { blob_id: "1", name: "cat.png", type: "image/png", size: 12345, w: 800, h: 600, key: b64.enc(randomBytes(32)) },
      { blob_id: "2", name: "clip.mp4", type: "video/mp4", size: 99, key: b64.enc(randomBytes(32)), spoiler: true, duration: 4.5 },
    ];
    const payload: MessagePayload = { t: "look", att, sticker: { id: "s1" }, embeds: [{ url: "https://example.com" }], flags: 3 };
    const env = await encryptMessage(key, aad, payload);
    expect(await decryptMessage(key, env, aad)).toEqual(payload);
  });

  it("round trips an empty payload", async () => {
    const env = await encryptMessage(key, aad, {});
    expect(await decryptMessage(key, env, aad)).toEqual({});
  });

  it("stamps the envelope from the aad", async () => {
    const env = await encryptMessage(key, aad, { t: "x" });
    expect(env.v).toBe(1);
    expect(env.conv).toBe(aad.conv);
    expect(env.epoch).toBe(aad.epoch);
    expect(isEnvelope(env)).toBe(true);
  });

  it("emits a fresh 12-byte iv and a different ciphertext each time", async () => {
    const payload: MessagePayload = { t: "same" };
    const a = await encryptMessage(key, aad, payload);
    const b = await encryptMessage(key, aad, payload);
    expect(b64.dec(a.iv).length).toBe(12);
    expect(b64.dec(b.iv).length).toBe(12);
    expect(a.iv).not.toBe(b.iv);
    expect(a.ct).not.toBe(b.ct);
  });

  it("rejects the wrong channel key", async () => {
    const env = await encryptMessage(key, aad, { t: "secret" });
    await expect(decryptMessage(channelKey(root, "chan-2"), env, aad)).rejects.toBeInstanceOf(DecryptError);
    await expect(decryptMessage(channelKey(newRootKey(), "chan-1"), env, aad)).rejects.toBeInstanceOf(DecryptError);
  });

  it("rejects an aad mismatch on any field", async () => {
    const env = await encryptMessage(key, aad, { t: "secret" });
    const mismatches: Aad[] = [
      { ...aad, channelId: "chan-2" },
      { ...aad, authorId: "user-2" },
      { ...aad, conv: "conv-2" },
      { ...aad, epoch: 3 },
    ];
    for (const m of mismatches) {
      await expect(decryptMessage(key, env, m)).rejects.toBeInstanceOf(DecryptError);
    }
  });

  it("rejects a tampered ciphertext", async () => {
    const env = await encryptMessage(key, aad, { t: "secret" });
    const ct = b64.dec(env.ct);
    ct[0] = (ct[0] ?? 0) ^ 0x01;
    await expect(decryptMessage(key, { ...env, ct: b64.enc(ct) }, aad)).rejects.toBeInstanceOf(DecryptError);

    const iv = b64.dec(env.iv);
    iv[0] = (iv[0] ?? 0) ^ 0x01;
    await expect(decryptMessage(key, { ...env, iv: b64.enc(iv) }, aad)).rejects.toBeInstanceOf(DecryptError);
  });

  it("rejects malformed base64", async () => {
    const env = await encryptMessage(key, aad, { t: "secret" });
    await expect(decryptMessage(key, { ...env, ct: "not base64!" }, aad)).rejects.toBeInstanceOf(DecryptError);
    await expect(decryptMessage(key, { ...env, iv: "%%%%" }, aad)).rejects.toBeInstanceOf(DecryptError);
  });

  it("rejects an iv of the wrong length", async () => {
    const env = await encryptMessage(key, aad, { t: "secret" });
    await expect(decryptMessage(key, { ...env, iv: b64.enc(randomBytes(16)) }, aad)).rejects.toBeInstanceOf(DecryptError);
  });

  it("rejects an unsupported envelope version", async () => {
    const env = await encryptMessage(key, aad, { t: "secret" });
    const bumped = { ...env, v: 2 } as unknown as Envelope;
    await expect(decryptMessage(key, bumped, aad)).rejects.toBeInstanceOf(DecryptError);
  });

  it("rejects an authentic ciphertext that is not JSON", async () => {
    const env = await forgeEnvelope(utf8.enc("this is not json"));
    await expect(decryptMessage(key, env, aad)).rejects.toBeInstanceOf(DecryptError);
  });

  it("rejects an authentic ciphertext holding JSON that is not an object", async () => {
    for (const json of ["42", '"a string"', "null", "[1,2,3]", "true"]) {
      const env = await forgeEnvelope(utf8.enc(json));
      await expect(decryptMessage(key, env, aad)).rejects.toBeInstanceOf(DecryptError);
    }
  });

  it("rejects a payload whose content all has the wrong types", async () => {
    for (const json of ['{"t":1}', '{"att":"x"}', '{"embeds":[null]}', '{"sticker":{"id":5}}', '{"att":[{"blob_id":"b"}],"t":{}}']) {
      const env = await forgeEnvelope(utf8.enc(json));
      await expect(decryptMessage(key, env, aad)).rejects.toBeInstanceOf(DecryptError);
    }
  });

  it("drops fields that are not the message's and keeps the rest, but refuses a file or a card that is not one", async () => {
    const good = { blob_id: "1", name: "a.png", type: "image/png", size: 10, key: "k", w: 5 };
    const forged = {
      t: "hi",
      att: [good, { ...good, size: -1 }, { ...good, name: 7 }, null, { ...good, blob_id: "2", w: "wide", spoiler: "yes" }],
      sticker: { id: "s", w: "x", name: {} },
      embeds: [null, 3, { url: "https://e.x", title: { bad: true }, image_w: "9", type: "link" }],
      flags: "3",
      extra: 1,
    };
    const env = await forgeEnvelope(utf8.enc(JSON.stringify(forged)));
    // a broken file or card is something no app sends: the whole message is refused
    await expect(decryptMessage(key, env, aad)).rejects.toThrow("over_limits");
    // what is merely mistyped or not a message's at all is let go
    const odd = await forgeEnvelope(utf8.enc(JSON.stringify({ t: "hi", att: [good, { ...good, blob_id: "2", w: "wide", spoiler: "yes" }], sticker: { id: "s", w: "x", name: {} }, embeds: [{ url: "https://e.x", title: { bad: true }, image_w: "9", type: "link" }], flags: "3", extra: 1 })));
    expect(await decryptMessage(key, odd, aad)).toEqual({
      t: "hi",
      att: [good, { blob_id: "2", name: "a.png", type: "image/png", size: 10, key: "k" }],
      sticker: { id: "s" },
      embeds: [{ url: "https://e.x", type: "link" }],
    });
  });

  it("keeps a picture's small copy only when it is one", async () => {
    const copy = { blob_id: "77", key: "ck", size: 9000, type: "image/webp", w: 1280, h: 960 };
    const pic = { blob_id: "1", name: "a.jpg", type: "image/jpeg", size: 4_000_000, key: "k" };
    const forged = {
      att: [
        { ...pic, preview: copy },
        // fetched by its address, so the address is a number and nothing else
        { ...pic, blob_id: "2", preview: { ...copy, blob_id: "../../api/v1/users/@me" } },
        // shown by its type, so the type is a picture's
        { ...pic, blob_id: "3", preview: { ...copy, type: "text/html" } },
        { ...pic, blob_id: "4", preview: { ...copy, size: 50 * 1024 * 1024 } },
        { ...pic, blob_id: "5", preview: { ...copy, w: 0 } },
        // only a picture has a copy
        { ...pic, blob_id: "6", type: "application/zip", preview: copy },
      ],
    };
    const env = await forgeEnvelope(utf8.enc(JSON.stringify(forged)));
    const out = await decryptMessage(key, env, aad);
    expect(out.att?.map((a) => a.preview ?? null)).toEqual([copy, null, null, null, null, null]);
  });

  it("keeps everything a link's card draws, with only web addresses and known words in it", async () => {
    const card = {
      url: "https://e.x/post",
      type: "link",
      title: "T",
      author: "Ada",
      author_url: "https://e.x/ada",
      icon: "/media/proxy/1-s/aWNvbg",
      published_at: 1_790_000_000_000,
      media: "/media/proxy/1-s/bWVkaWE",
      media_kind: "audio",
      media_mime: "audio/mpeg",
      media_w: 640,
      media_h: 360,
      player: "https://player.vimeo.com/video/123456",
      player_site: "Vimeo",
      banner: "/media/banner/9",
      image_layout: "large",
    };
    const forged = { t: "x", embeds: [card, { url: "https://e.y", author_url: "javascript:alert(1)", icon: "//elsewhere.test/i.png", media_kind: "flash", image_layout: "huge", player: "data:text/html,hi", published_at: "soon" }] };
    const env = await forgeEnvelope(utf8.enc(JSON.stringify(forged)));
    expect((await decryptMessage(key, env, aad)).embeds).toEqual([card, { url: "https://e.y" }]);
  });
});

describe("a social post in a link's card", () => {
  const open = async (post: unknown) => {
    const env = await forgeEnvelope(utf8.enc(JSON.stringify({ t: "x", embeds: [{ url: "https://x.com/a/status/1", type: "link", post }] })));
    return ((await decryptMessage(key, env, aad)).embeds?.[0] as { post?: Record<string, unknown> }).post;
  };

  it("keeps everything a post's card draws", async () => {
    const post = {
      platform: "twitter",
      site: "X",
      url: "https://x.com/a/status/1",
      name: "A",
      handle: "a",
      author_url: "https://x.com/a",
      avatar: "/media/proxy/1-s/YXZhdGFy",
      verified: true,
      community: "r/pics",
      community_url: "https://www.reddit.com/r/pics/",
      title: "T",
      text: "words",
      links: [{ text: "words", url: "https://e.x/w" }],
      reply_to: "b",
      created: 1_790_000_000_000,
      media: [
        { kind: "photo", url: "/media/proxy/1-s/cA", w: 100, h: 50, alt: "a picture" },
        { kind: "video", url: "/media/proxy/1-s/dg", thumb: "/media/proxy/1-s/dA", audio: "/media/proxy/1-s/YQ", w: 720, h: 1280, duration: 63.5 },
        { kind: "video", thumb: "/media/proxy/1-s/cG9zdGVy" },
      ],
      poll: { choices: [{ label: "Yes", percent: 75, votes: 30 }, { label: "No", percent: 25 }], votes: 40, ends: 1_790_000_000_000 },
      quote: { platform: "twitter", name: "Q", text: "quoted", media: [{ kind: "gif", url: "/media/proxy/1-s/Zw" }] },
      replies: 1,
      reposts: 2,
      likes: 3,
      views: 4,
      sensitive: true,
      note: "context",
    };
    expect(await open(post)).toEqual(post);
  });

  it("keeps a YouTube video's channel, whether it is live, and where its link starts it", async () => {
    const post = {
      platform: "youtube",
      site: "YouTube",
      url: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      name: "Rick Astley",
      handle: "RickAstleyYT",
      avatar: "/media/proxy/1-s/YXZhdGFy",
      verified: true,
      title: "Never Gonna Give You Up",
      media: [{ kind: "video", thumb: "/media/proxy/1-s/cG9zdGVy", w: 1280, h: 720, duration: 213 }],
      likes: 19_000_000,
      views: 1_800_000_000,
      subscribers: 4_550_000,
      live: "now",
    };
    expect(await open(post)).toEqual(post);
    expect(await open({ ...post, live: "maybe" })).not.toHaveProperty("live");
    expect(await open({ ...post, subscribers: -5 })).not.toHaveProperty("subscribers");
    const card = async (start: unknown) => {
      const env = await forgeEnvelope(utf8.enc(JSON.stringify({ t: "x", embeds: [{ url: "https://youtu.be/dQw4w9WgXcQ", type: "youtube", video_id: "dQw4w9WgXcQ", video_start: start }] })));
      return (await decryptMessage(key, env, aad)).embeds?.[0] as Record<string, unknown>;
    };
    expect((await card(65))["video_start"]).toBe(65);
    for (const bad of [0, -1, 86_401, "65"]) expect(await card(bad)).not.toHaveProperty("video_start");
  });

  it("lets nothing through that is not a web address, a known word, or of a sane size", async () => {
    const got = await open({
      platform: "twitter",
      name: "A",
      url: "javascript:alert(1)",
      author_url: "data:text/html,hi",
      avatar: "//elsewhere.test/a.png",
      community_url: "https://ok.test/\\evil",
      text: "é".repeat(5000),
      verified: "yes",
      sensitive: 1,
      created: "soon",
      likes: -5,
      views: Infinity,
      onload: "alert(1)",
      html: "<script>",
      links: [{ text: "a", url: "javascript:x" }, { text: "", url: "https://e.x" }, { text: "ok", url: "https://e.x/ok" }, "nonsense", { text: "x".repeat(600), url: "https://e.x" }],
      media: [
        { kind: "flash", url: "/media/proxy/1-s/a" },
        { kind: "photo", url: "file:///etc/passwd" },
        { kind: "photo" },
        { kind: "video" },
        { kind: "photo", url: "/media/proxy/1-s/b", w: -1, h: "tall", duration: 1e9, alt: 7, extra: "x" },
        null,
      ],
      poll: { choices: [{ label: "only one", percent: 100 }] },
      quote: { platform: "twitter", name: "Q", text: "quoted", quote: { platform: "twitter", name: "deeper", text: "x" } },
    });
    expect(got).toEqual({
      platform: "twitter",
      name: "A",
      text: "é".repeat(4000),
      links: [{ text: "ok", url: "https://e.x/ok" }],
      media: [{ kind: "photo", url: "/media/proxy/1-s/b" }],
      quote: { platform: "twitter", name: "Q", text: "quoted" },
    });
  });

  it("holds a post to ten pictures, twenty links and ten answers", async () => {
    const got = await open({
      platform: "bluesky",
      name: "A",
      media: Array.from({ length: 30 }, (_, i) => ({ kind: "photo", url: `/media/proxy/1-s/${i}` })),
      links: Array.from({ length: 50 }, (_, i) => ({ text: `l${i}`, url: "https://e.x/" })),
      poll: { choices: Array.from({ length: 25 }, (_, i) => ({ label: `c${i}`, percent: 500 })) },
    });
    expect((got?.["media"] as unknown[]).length).toBe(10);
    expect((got?.["links"] as unknown[]).length).toBe(20);
    const choices = (got?.["poll"] as { choices: { percent: number }[] }).choices;
    expect(choices.length).toBe(10);
    expect(choices[0]?.percent).toBe(100);
  });

  it("drops what is not a post at all, and leaves the card its other fields", async () => {
    for (const bad of [null, "post", [], {}, { platform: "Twitter" }, { platform: "x y" }, { name: "no platform" }]) {
      expect(await open(bad)).toBeUndefined();
    }
  });
});

describe("isEnvelope", () => {
  const good: Envelope = { v: 1, conv: "c", epoch: 0, iv: "AAAA", ct: "BBBB" };

  it("accepts a well-formed envelope", () => {
    expect(isEnvelope(good)).toBe(true);
    expect(isEnvelope({ ...good, extra: "ignored" })).toBe(true);
  });

  it("rejects non-objects", () => {
    for (const x of [null, undefined, 0, 1, "", "envelope", true, [], () => undefined]) {
      expect(isEnvelope(x)).toBe(false);
    }
  });

  it("rejects missing or mistyped fields", () => {
    const bad: unknown[] = [
      {},
      { ...good, v: 2 },
      { ...good, v: "1" },
      { ...good, conv: 1 },
      { ...good, epoch: "0" },
      { ...good, epoch: 1.5 },
      { ...good, epoch: Number.NaN },
      { ...good, iv: null },
      { ...good, ct: undefined },
      { v: 1, conv: "c", epoch: 0, iv: "AAAA" },
    ];
    for (const x of bad) expect(isEnvelope(x)).toBe(false);
  });

  it("narrows the type for the compiler", () => {
    const x: unknown = good;
    if (!isEnvelope(x)) throw new Error("expected an envelope");
    expect(x.conv).toBe("c");
  });
});

describe("polls in the payload", () => {
  const poll = {
    question: "Lunch?",
    answers: [{ text: "Pizza", emoji: { name: "🍕" } }, { text: "Soup" }, { text: "Salad", emoji: { id: "123456789", name: "leaf", animated: true } }],
    hours: 24,
    multiselect: true,
  };

  it("round trips a poll, sealed with the rest of the message", async () => {
    const env = await encryptMessage(key, aad, { poll });
    expect(env.ct).not.toContain("Lunch");
    expect(await decryptMessage(key, env, aad)).toEqual({ poll });
  });

  it("refuses a poll past the lengths the dialog allows, and fills in what it leaves unsaid", async () => {
    const long = { question: "q".repeat(400), answers: [{ text: "a" }, { text: "b" }] };
    await expect(decryptMessage(key, await forgeEnvelope(utf8.enc(JSON.stringify({ poll: long }))), aad)).rejects.toThrow("over_limits");
    const loose = { question: "q".repeat(300), answers: [{ text: "a".repeat(55), emoji: { name: "🍕", id: "not-an-id" } }, { text: "b", emoji: { name: 5 } }], hours: 9999, multiselect: "yes" };
    const out = await decryptMessage(key, await forgeEnvelope(utf8.enc(JSON.stringify({ poll: loose }))), aad);
    expect(out.poll).toEqual({ question: "q".repeat(300), answers: [{ text: "a".repeat(55), emoji: { name: "🍕" } }, { text: "b" }], hours: 24, multiselect: false });
  });

  it("counts an emoji as one character, as the dialog does", async () => {
    const env = await forgeEnvelope(utf8.enc(JSON.stringify({ poll: { ...poll, answers: [{ text: "x".repeat(54) + "🍕" }, { text: "b" }] } })));
    expect((await decryptMessage(key, env, aad)).poll?.answers[0]?.text).toBe("x".repeat(54) + "🍕");
  });

  it("refuses a message whose poll no app would send", async () => {
    for (const bad of [
      { ...poll, question: "" },
      { ...poll, question: 7 },
      { ...poll, answers: [{ text: "only one" }] },
      { ...poll, answers: Array.from({ length: 11 }, (_, i) => ({ text: `a${i}` })) },
      // a broken answer would shift the ids of those after it, so the poll goes
      { ...poll, answers: [{ text: "a" }, null, { text: "c" }] },
      { ...poll, answers: [{ text: "a" }, { text: 2 }] },
      "poll",
    ]) {
      const env = await forgeEnvelope(utf8.enc(JSON.stringify({ t: "hi", poll: bad })));
      await expect(decryptMessage(key, env, aad)).rejects.toThrow("over_limits");
    }
  });

  it("rejects a payload whose only content is a poll it cannot use", async () => {
    const env = await forgeEnvelope(utf8.enc(JSON.stringify({ poll: { question: "q", answers: [] } })));
    await expect(decryptMessage(key, env, aad)).rejects.toBeInstanceOf(DecryptError);
  });
});

describe("a forwarded message in the payload", () => {
  it("keeps when the original was sent, and drops a mark that is not one", async () => {
    const att = [{ blob_id: "9", name: "a.png", type: "image/png", size: 10, key: b64.enc(randomBytes(32)) }];
    const env = await encryptMessage(key, aad, { t: "hi", att, fwd: { at: 1761400000000, comment: "Here is the context" } });
    expect(await decryptMessage(key, env, aad)).toEqual({ t: "hi", att, fwd: { at: 1761400000000, comment: "Here is the context" } });
    const odd = await encryptMessage(key, aad, { t: "hi", fwd: { at: "yesterday" } } as never);
    expect((await decryptMessage(key, odd, aad)).fwd).toBeUndefined();
  });
});

describe("what a message may hold", () => {
  const file = (name = "a.png") => ({ blob_id: "9", name, type: "image/png", size: 10, key: b64.enc(randomBytes(32)) });
  const opens = async (payload: object) => {
    const env = await encryptMessage(key, aad, payload as never);
    return decryptMessage(key, env, aad).then(
      () => true,
      (e: Error) => (e.message === "over_limits" ? false : Promise.reject(e)),
    );
  };

  it("opens a message up to the composer's length, counting mentions and custom emoji as typed", async () => {
    expect(await opens({ t: "x".repeat(4000) })).toBe(true);
    expect(await opens({ t: "x".repeat(4001) })).toBe(false);
    // 1000 mentions are far longer sealed than typed, and still fit
    expect(await opens({ t: "<@123456789012345678>".repeat(1000) + "x".repeat(1000) })).toBe(true);
    expect(typedLength("hi <:wave:123456789012345678> <@&42>")).toBe(8);
  });

  it("refuses more files than a message takes, a file that is not one, and a name past any app's", async () => {
    expect(await opens({ att: Array.from({ length: 10 }, () => file()) })).toBe(true);
    expect(await opens({ att: Array.from({ length: 11 }, () => file()) })).toBe(false);
    expect(await opens({ att: [file(), { blob_id: 5 }] })).toBe(false);
    expect(await opens({ att: [file("n".repeat(256))] })).toBe(false);
  });

  it("refuses more link previews than the composer makes, and previews past what the server writes", async () => {
    const card = (extra = {}) => ({ url: "https://example.com/", title: "t", ...extra });
    expect(await opens({ embeds: Array.from({ length: 5 }, () => card()) })).toBe(true);
    expect(await opens({ embeds: Array.from({ length: 6 }, () => card()) })).toBe(false);
    expect(await opens({ embeds: [card({ description: "d".repeat(2049) })] })).toBe(false);
    expect(brokenLimit({ t: 42 })).toBe("text");
    expect(brokenLimit({ sticker: { id: "1", name: "s".repeat(31) } })).toBe("sticker");
    expect(brokenLimit({ poll: { question: "q".repeat(301), answers: [{ text: "a" }, { text: "b" }] } })).toBe("poll");
  });
});

describe("a music card in a link's card", () => {
  it("keeps what the card draws and nothing else", () => {
    const e = sanitizeEmbed({
      url: "https://www.deezer.com/album/302127",
      type: "link",
      music: {
        platform: "deezer",
        kind: "album",
        id: "302127",
        title: "Discovery",
        artist: "Daft Punk",
        art: "/media/proxy/1-x/aGk",
        count: 14,
        year: 2001,
        evil: "<script>",
        player: "spotify",
        tracks: [
          { title: "One More Time", artist: "Daft Punk", duration: 320, preview: "/media/music/preview/3135553" },
          { title: "Bad", preview: "javascript:alert(1)" },
          { nope: true },
          ...Array.from({ length: 10 }, (_, i) => ({ title: `t${i}` })),
        ],
      },
    });
    const m = e["music"] as Record<string, unknown>;
    expect(m["title"]).toBe("Discovery");
    expect(m["evil"]).toBeUndefined();
    // a player is only the platform's own
    expect(m["player"]).toBeUndefined();
    const tracks = m["tracks"] as Record<string, unknown>[];
    expect(tracks.length).toBe(4);
    expect(tracks[0]!["preview"]).toBe("/media/music/preview/3135553");
    expect(tracks[1]!["preview"]).toBeUndefined();
  });

  it("drops a card that is not music", () => {
    expect(sanitizeEmbed({ url: "https://x.example", music: { platform: "napster", kind: "track", id: "1", title: "x" } })["music"]).toBeUndefined();
    expect(sanitizeEmbed({ url: "https://x.example", music: { platform: "spotify", kind: "track", id: "../../x", title: "x" } })["music"]).toBeUndefined();
  });
});
