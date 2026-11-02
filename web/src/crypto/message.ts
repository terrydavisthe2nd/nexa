/**
 * Message envelopes.
 *
 * The server only ever sees an `Envelope`: a conversation id, an epoch, and an
 * opaque AES-256-GCM ciphertext. The channel and author are bound into the
 * additional authenticated data, so a ciphertext cannot be replayed into
 * another channel or attributed to another author.
 */

import { IV_LEN, gcmDecrypt, gcmEncrypt } from "./aes";
import { b64, randomBytes, utf8 } from "./bytes";
import { DecryptError } from "./keys";

export interface Envelope {
  v: 1;
  conv: string;
  epoch: number;
  /** base64 of the 12-byte GCM nonce. */
  iv: string;
  /** base64 of the ciphertext and tag. */
  ct: string;
}

export interface Aad {
  conv: string;
  epoch: number;
  channelId: string;
  authorId: string;
}

/**
 * A picture's small copy, made by its sender for the chat to show in its
 * place (lib/pictureCopy.ts): a file of its own, encrypted under its own key.
 */
export interface AttachmentPreview {
  blob_id: string;
  /** base64 of the copy's own AES key. */
  key: string;
  size: number;
  type: string;
  w: number;
  h: number;
}

export interface AttachmentMeta {
  blob_id: string;
  name: string;
  type: string;
  size: number;
  w?: number;
  h?: number;
  /** base64 of the per-file AES key. */
  key: string;
  spoiler?: boolean;
  duration?: number;
  /** A big picture's small copy, shown in the chat until the picture is opened. */
  preview?: AttachmentPreview;
  /**
   * A webhook's file: stored as it was sent, not encrypted, so it downloads
   * as it is and `key` is empty.
   */
  plain?: boolean;
  /** Alt text a webhook gave the file. */
  alt?: string;
  /** A voice message recorded in the app: drawn as one, with the shape of its sound. */
  voice?: boolean;
  /** base64 of the recording's loudness, a byte per bar (lib/voiceMessage.ts). */
  wave?: string;
}

/** An answer's emoji: a character (its name), or one of the app's custom emoji. */
export interface PollEmoji {
  name: string;
  id?: string;
  animated?: boolean;
}

/**
 * A person's poll. Everything it says is sealed here with the rest of the
 * message; the server is told only how many answers there are, how long it
 * runs and whether more than one may be picked, which is what it needs to
 * take votes. An answer's id is its place in the list, from 1.
 */
export interface PollPayload {
  question: string;
  answers: { text: string; emoji?: PollEmoji }[];
  /** How long it runs, in hours. The server's tally has when it really closes. */
  hours: number;
  multiselect: boolean;
}

export const POLL_QUESTION_MAX = 300;
export const POLL_ANSWER_MAX = 55;
export const POLL_ANSWERS_MIN = 2;
export const POLL_ANSWERS_MAX = 10;
export const POLL_HOURS_MAX = 14 * 24;

export interface MessagePayload {
  t?: string;
  att?: AttachmentMeta[];
  /** A sticker reference: enough to render it without owning the pack. */
  sticker?: { id: string; blob?: string; name?: string; w?: number; h?: number; animated?: boolean; format?: string };
  embeds?: unknown[];
  flags?: number;
  poll?: PollPayload;
  /** Forwarded from another message (lib/forward.ts): when that one was sent (Unix ms). Its files are the original's own. */
  fwd?: { at: number; comment?: string };
}

export function aadBytes(a: Aad): Uint8Array {
  return utf8.enc(`${a.conv}|${a.epoch}|${a.channelId}|${a.authorId}`);
}

export async function encryptMessage(
  chanKey: Uint8Array,
  aad: Aad,
  payload: MessagePayload,
): Promise<Envelope> {
  const iv = randomBytes(IV_LEN);
  const ct = await gcmEncrypt(chanKey, iv, utf8.enc(JSON.stringify(payload)), aadBytes(aad));
  return { v: 1, conv: aad.conv, epoch: aad.epoch, iv: b64.enc(iv), ct: b64.enc(ct) };
}

export async function decryptMessage(
  chanKey: Uint8Array,
  env: Envelope,
  aad: Aad,
): Promise<MessagePayload> {
  if (env.v !== 1) throw new DecryptError("unsupported envelope version");

  let iv: Uint8Array;
  let ct: Uint8Array;
  try {
    iv = b64.dec(env.iv);
    ct = b64.dec(env.ct);
  } catch {
    throw new DecryptError("malformed envelope");
  }
  if (iv.length !== IV_LEN) throw new DecryptError("malformed envelope");

  let plaintext: Uint8Array;
  try {
    plaintext = await gcmDecrypt(chanKey, iv, ct, aadBytes(aad));
  } catch {
    throw new DecryptError("message failed to decrypt");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(utf8.dec(plaintext));
  } catch {
    throw new DecryptError("malformed message payload");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new DecryptError("malformed message payload");
  }
  return sanitizePayload(parsed as Obj);
}

type Obj = Record<string, unknown>;

function isObj(x: unknown): x is Obj {
  return typeof x === "object" && x !== null && !Array.isArray(x);
}

function isNum(x: unknown): x is number {
  return typeof x === "number" && Number.isFinite(x);
}

/** What a picture's copy may be: a still picture every browser shows, and small. */
const PREVIEW_TYPES = new Set(["image/webp", "image/jpeg", "image/png"]);
const PREVIEW_MAX = 2 * 1024 * 1024;

/**
 * A copy is fetched by its address and shown by its type, so both are held
 * to what a copy can be: a number, and a picture. Anything else leaves the
 * picture to show at its full size, as it did before copies.
 */
function sanitizePreview(x: unknown): AttachmentPreview | undefined {
  if (!isObj(x)) return undefined;
  const { blob_id, key, size, type, w, h } = x;
  if (typeof blob_id !== "string" || !/^\d{1,20}$/.test(blob_id) || typeof key !== "string" || typeof type !== "string" || !PREVIEW_TYPES.has(type)) return undefined;
  if (typeof size !== "number" || !Number.isSafeInteger(size) || size <= 0 || size > PREVIEW_MAX) return undefined;
  if (!isNum(w) || !isNum(h) || w <= 0 || h <= 0) return undefined;
  return { blob_id, key, size, type, w, h };
}

/** A voice message's shape: at most 96 bytes of base64. */
const WAVE = /^[A-Za-z0-9+/]{1,128}={0,2}$/;

/** A file as something another person wrote into a message or a note: held to what a file can be. */
export function sanitizeAttachment(x: unknown): AttachmentMeta | null {
  if (!isObj(x)) return null;
  const { blob_id, name, type, key, size, w, h, duration, spoiler } = x;
  if (typeof blob_id !== "string" || !/^[0-9]{1,20}$/.test(blob_id) || typeof name !== "string" || typeof type !== "string" || typeof key !== "string") return null;
  if (typeof size !== "number" || !Number.isSafeInteger(size) || size < 0) return null;
  const a: AttachmentMeta = { blob_id, name, type, size, key };
  if (isNum(w)) a.w = w;
  if (isNum(h)) a.h = h;
  if (isNum(duration)) a.duration = duration;
  if (typeof spoiler === "boolean") a.spoiler = spoiler;
  const preview = type.startsWith("image/") ? sanitizePreview(x["preview"]) : undefined;
  if (preview) a.preview = preview;
  if (type.startsWith("audio/")) {
    if (x["voice"] === true) a.voice = true;
    const wave = x["wave"];
    if (typeof wave === "string" && WAVE.test(wave)) a.wave = wave;
  }
  // a message sealed by a person holds only encrypted files, whatever it says
  return a;
}

// Everything a link's card draws. The card's icon, author, date, playable file
// and video player were kept out of this list when they were added, so no
// message ever showed them: whatever is not here is thrown away on arrival.
const EMBED_STRINGS = ["url", "type", "title", "description", "site_name", "image", "video_id", "mime", "color", "invite_code", "author", "author_url", "icon", "media", "media_mime", "player", "player_site", "banner"];
const EMBED_NUMBERS = ["image_w", "image_h", "member_count", "online_count", "published_at", "media_w", "media_h", "video_start"];
/** Fields that only mean something as one of a few words. */
const EMBED_CHOICES: Record<string, readonly string[]> = { media_kind: ["video", "audio"], image_layout: ["large", "thumbnail"] };

/**
 * Whether a link in an embed may be followed or loaded: an http(s) address,
 * or a path on this server. Anything else — javascript:, a data: page, a
 * path a browser would resolve to another host — the sender wrote to do
 * something other than show a link.
 */
export function webLink(v: string): boolean {
  if (/[\\\s\u0000-\u001f\u007f]/.test(v)) return false;
  if (v.startsWith("/")) return !v.startsWith("//");
  return /^https?:\/\//i.test(v);
}

/**
 * The fields that are addresses. Pictures and files still only load through
 * this server (lib/mediaLinks), and a player only opens for the sites the card
 * allows (Embeds); this keeps anything but a web address out of them at all.
 */
const EMBED_LINKS = new Set(["url", "image", "author_url", "icon", "media", "player", "banner"]);

export function sanitizeEmbed(e: Obj): Obj {
  const out: Obj = {};
  for (const k of EMBED_STRINGS) {
    const v = e[k];
    if (typeof v === "string" && (!EMBED_LINKS.has(k) || webLink(v))) out[k] = v;
  }
  for (const k of EMBED_NUMBERS) if (isNum(e[k])) out[k] = e[k];
  // a YouTube video starts at most a day in, where its link says
  if (isNum(out["video_start"]) && !(out["video_start"] > 0 && out["video_start"] <= 86400)) delete out["video_start"];
  for (const [k, allowed] of Object.entries(EMBED_CHOICES)) {
    const v = e[k];
    if (typeof v === "string" && allowed.includes(v)) out[k] = v;
  }
  const post = sanitizePost(e["post"], false);
  if (post) out["post"] = post;
  const music = sanitizeMusic(e["music"]);
  if (music) out["music"] = music;
  return out;
}

// A music card (internal/unfurl/music.go), held to the same rule: listed
// fields only, its sounds and cover web addresses, everything with a length.
const MUSIC_PLATFORMS = ["spotify", "apple", "deezer", "soundcloud"];
const MUSIC_KINDS = ["track", "album", "playlist", "artist", "episode", "show"];
const MUSIC_PLAYERS = ["spotify", "soundcloud"];
const MAX_MUSIC_TRACKS = 5;

function sanitizeMusicTrack(x: unknown): Obj | undefined {
  if (!isObj(x) || typeof x["title"] !== "string" || !x["title"]) return undefined;
  const out: Obj = { title: cap(x["title"], 256) };
  if (typeof x["artist"] === "string" && x["artist"]) out["artist"] = cap(x["artist"], 256);
  const d = x["duration"];
  if (isNum(d) && d > 0 && d <= 360000) out["duration"] = Math.floor(d);
  const preview = address(x["preview"]);
  if (preview) out["preview"] = preview;
  if (x["explicit"] === true) out["explicit"] = true;
  return out;
}

function sanitizeMusic(x: unknown): Obj | undefined {
  if (!isObj(x)) return undefined;
  const { platform, kind, id, title } = x;
  if (typeof platform !== "string" || !MUSIC_PLATFORMS.includes(platform) || typeof kind !== "string" || !MUSIC_KINDS.includes(kind)) return undefined;
  if (typeof id !== "string" || !/^[A-Za-z0-9.-]{1,64}$/.test(id) || typeof title !== "string" || !title) return undefined;
  const out: Obj = { platform, kind, id, title: cap(title, 256) };
  for (const k of ["artist", "album"]) {
    const v = x[k];
    if (typeof v === "string" && v) out[k] = cap(v, 256);
  }
  const art = address(x["art"]);
  if (art) out["art"] = art;
  const preview = address(x["preview"]);
  if (preview) out["preview"] = preview;
  if (preview && (x["preview_from"] === "deezer" || x["preview_from"] === "apple")) out["preview_from"] = x["preview_from"];
  for (const [k, max] of [["duration", 360000], ["count", 100000], ["year", 3000]] as const) {
    const v = x[k];
    if (isNum(v) && v > 0 && v <= max) out[k] = Math.floor(v);
  }
  if (x["explicit"] === true) out["explicit"] = true;
  if (typeof x["player"] === "string" && MUSIC_PLAYERS.includes(x["player"]) && x["player"] === platform) out["player"] = x["player"];
  if (Array.isArray(x["tracks"])) {
    const tracks = x["tracks"].slice(0, MAX_MUSIC_TRACKS).map(sanitizeMusicTrack).filter((t): t is Obj => !!t);
    if (tracks.length) out["tracks"] = tracks;
  }
  return out;
}

/** At most n characters (code points, so an emoji is never cut in half). */
function cap(v: string, n: number): string {
  const chars = Array.from(v);
  return chars.length > n ? chars.slice(0, n).join("") : v;
}

// A social post's card (internal/unfurl/post.go), held to the same rule as
// the rest of an embed: only what is listed here is kept, the fields that are
// addresses hold web addresses and nothing else, and everything has a length.
// The sender's app made the card, so a sender can write anything into it.
const POST_TEXTS: Record<string, number> = { platform: 20, site: 40, name: 256, handle: 256, community: 256, title: 300, text: 4000, reply_to: 256, note: 2048 };
const POST_ADDRESSES = ["url", "author_url", "avatar", "community_url"];
const POST_COUNTS = ["created", "replies", "reposts", "likes", "views", "subscribers"];
const POST_MEDIA_KINDS = ["photo", "video", "gif"];
const MAX_ADDRESS = 8192;
const MAX_POST_MEDIA = 10;
const MAX_POST_LINKS = 20;

function address(v: unknown): string | undefined {
  return typeof v === "string" && v.length <= MAX_ADDRESS && webLink(v) ? v : undefined;
}

function sanitizePostMedia(x: unknown): Obj | undefined {
  if (!isObj(x) || typeof x["kind"] !== "string" || !POST_MEDIA_KINDS.includes(x["kind"])) return undefined;
  const out: Obj = { kind: x["kind"] };
  for (const k of ["url", "thumb", "audio"]) {
    const v = address(x[k]);
    if (v) out[k] = v;
  }
  // a picture is its file; a video may be only its poster
  if (!out["url"] && (out["kind"] === "photo" || !out["thumb"])) return undefined;
  for (const k of ["w", "h", "duration"]) {
    const v = x[k];
    if (isNum(v) && v > 0 && v <= 100000) out[k] = v;
  }
  if (typeof x["alt"] === "string" && x["alt"]) out["alt"] = cap(x["alt"], 1000);
  return out;
}

function sanitizePost(x: unknown, quoted: boolean): Obj | undefined {
  if (!isObj(x) || typeof x["platform"] !== "string" || !/^[a-z]{2,20}$/.test(x["platform"])) return undefined;
  const out: Obj = {};
  for (const [k, max] of Object.entries(POST_TEXTS)) {
    const v = x[k];
    if (typeof v === "string" && v) out[k] = cap(v, max);
  }
  for (const k of POST_ADDRESSES) {
    const v = address(x[k]);
    if (v) out[k] = v;
  }
  for (const k of POST_COUNTS) {
    const v = x[k];
    if (isNum(v) && v > 0) out[k] = v;
  }
  for (const k of ["verified", "sensitive"]) if (x[k] === true) out[k] = true;
  if (x["live"] === "now" || x["live"] === "upcoming") out["live"] = x["live"];
  if (Array.isArray(x["media"])) {
    const media = x["media"].slice(0, MAX_POST_MEDIA).map(sanitizePostMedia).filter((m): m is Obj => !!m);
    if (media.length) out["media"] = media;
  }
  if (Array.isArray(x["links"])) {
    const links: Obj[] = [];
    for (const l of x["links"].slice(0, MAX_POST_LINKS)) {
      if (!isObj(l) || typeof l["text"] !== "string" || !l["text"] || l["text"].length > 512) continue;
      const url = address(l["url"]);
      if (url) links.push({ text: l["text"], url });
    }
    if (links.length) out["links"] = links;
  }
  const poll = x["poll"];
  if (isObj(poll) && Array.isArray(poll["choices"])) {
    const choices: Obj[] = [];
    for (const c of poll["choices"].slice(0, 10)) {
      if (!isObj(c) || typeof c["label"] !== "string" || !isNum(c["percent"])) continue;
      const choice: Obj = { label: cap(c["label"], 200), percent: Math.min(100, Math.max(0, c["percent"])) };
      if (isNum(c["votes"]) && c["votes"] >= 0) choice["votes"] = c["votes"];
      choices.push(choice);
    }
    if (choices.length >= 2) {
      const p: Obj = { choices };
      if (isNum(poll["votes"]) && poll["votes"] >= 0) p["votes"] = poll["votes"];
      if (isNum(poll["ends"]) && poll["ends"] > 0) p["ends"] = poll["ends"];
      out["poll"] = p;
    }
  }
  // a quote quotes nothing further
  if (!quoted) {
    const quote = sanitizePost(x["quote"], true);
    if (quote) out["quote"] = quote;
  }
  return out;
}

function sanitizePollEmoji(x: unknown): PollEmoji | undefined {
  if (!isObj(x) || typeof x["name"] !== "string" || !x["name"]) return undefined;
  const e: PollEmoji = { name: cap(x["name"], 64) };
  if (typeof x["id"] === "string" && /^\d{1,20}$/.test(x["id"])) e.id = x["id"];
  if (x["animated"] === true) e.animated = true;
  return e;
}

/**
 * A poll as the UI can trust it: a question, 2 to 10 answers, each held to
 * the lengths the dialog allows. An answer's id is its place in the list, so
 * one that cannot be read spoils the poll rather than being skipped: the
 * answers after it would take the votes meant for the ones before.
 */
function sanitizePoll(x: unknown): PollPayload | undefined {
  if (!isObj(x) || typeof x["question"] !== "string" || !x["question"].trim() || !Array.isArray(x["answers"])) return undefined;
  const list = x["answers"];
  if (list.length < POLL_ANSWERS_MIN || list.length > POLL_ANSWERS_MAX) return undefined;
  const answers: PollPayload["answers"] = [];
  for (const a of list) {
    if (!isObj(a) || typeof a["text"] !== "string") return undefined;
    const answer: PollPayload["answers"][number] = { text: cap(a["text"], POLL_ANSWER_MAX) };
    const emoji = sanitizePollEmoji(a["emoji"]);
    if (emoji) answer.emoji = emoji;
    answers.push(answer);
  }
  const h = x["hours"];
  const hours = typeof h === "number" && Number.isInteger(h) && h >= 1 && h <= POLL_HOURS_MAX ? h : 24;
  return { question: cap(x["question"], POLL_QUESTION_MAX), answers, hours, multiselect: x["multiselect"] === true };
}

/**
 * Anyone holding the key can encrypt whatever they like, and the UI trusts
 * these fields' types while rendering, so one wrong type would crash every
 * client that shows the message. Only well-typed fields are kept, and a
 * payload that had content but none of it usable counts as undecryptable.
 */
/**
 * What a person's message may hold, as the apps send it (the composer's
 * MAX_MESSAGE_LENGTH, ten files, five link previews, what the server lets a
 * sticker or a preview be). The server only ever sees a message sealed, so it
 * cannot hold anyone to these: every app holds every message it opens to them,
 * and one that breaks them, which only an app made to could send, is not shown
 * at all (Android data/MessageLimits keeps the same numbers). Mentions and
 * custom emoji count as two characters each, never more than they took to type.
 */
export const MESSAGE_LIMITS = { text: 4000, files: 10, fileName: 255, fileType: 255, fileKey: 64, embeds: 5, embedTitle: 256, embedText: 2048, address: 8192, stickerName: 30 } as const;

const TOKENS = /<(?:a?:[A-Za-z0-9_]{1,32}:|@&?|#)\d{1,20}>/g;

/** A message's text as long as it was to type: each mention and custom emoji as two characters. */
export function typedLength(t: string): number {
  return t.replace(TOKENS, "··").length;
}

const runes = (v: string) => Array.from(v).length;

/** The first limit a payload breaks, or null when it keeps to them all. */
export function brokenLimit(p: Obj): string | null {
  const L = MESSAGE_LIMITS;
  if (isObj(p["fwd"]) && "comment" in p["fwd"] && (typeof p["fwd"]["comment"] !== "string" || typedLength(p["fwd"]["comment"]) > L.text)) return "text";
  if ("t" in p && (typeof p["t"] !== "string" || typedLength(p["t"]) > L.text)) return "text";
  if ("att" in p) {
    const att = p["att"];
    if (!Array.isArray(att) || att.length > L.files) return "files";
    for (const a of att) {
      if (!isObj(a) || sanitizeAttachment(a) === null) return "file";
      if (runes(String(a["name"])) > L.fileName || String(a["type"]).length > L.fileType || String(a["key"]).length > L.fileKey) return "file";
    }
  }
  if ("embeds" in p) {
    const embeds = p["embeds"];
    if (!Array.isArray(embeds) || embeds.length > L.embeds) return "embeds";
    for (const e of embeds) {
      if (!isObj(e) || typeof e["url"] !== "string" || !webLink(e["url"])) return "embed";
      for (const k of ["title", "site_name", "author"]) if (typeof e[k] === "string" && runes(e[k] as string) > L.embedTitle) return "embed";
      if (typeof e["description"] === "string" && runes(e["description"]) > L.embedText) return "embed";
      for (const k of EMBED_LINKS) if (typeof e[k] === "string" && (e[k] as string).length > L.address) return "embed";
    }
  }
  const st = p["sticker"];
  if ("sticker" in p && (!isObj(st) || (typeof st["name"] === "string" && runes(st["name"]) > L.stickerName))) return "sticker";
  const poll = p["poll"];
  if ("poll" in p) {
    if (!isObj(poll) || typeof poll["question"] !== "string" || runes(poll["question"]) > POLL_QUESTION_MAX || !Array.isArray(poll["answers"])) return "poll";
    if (poll["answers"].length < POLL_ANSWERS_MIN || poll["answers"].length > POLL_ANSWERS_MAX) return "poll";
    for (const a of poll["answers"]) if (!isObj(a) || typeof a["text"] !== "string" || runes(a["text"]) > POLL_ANSWER_MAX) return "poll";
    if (!sanitizePoll(poll)) return "poll";
  }
  return null;
}

export function sanitizePayload(p: Obj): MessagePayload {
  // a message past what any app sends was made to be, and is not shown
  if (brokenLimit(p)) throw new DecryptError("over_limits");
  const out: MessagePayload = {};
  if (typeof p["t"] === "string") out.t = p["t"];
  if (Array.isArray(p["att"])) {
    const att = p["att"].map(sanitizeAttachment).filter((a): a is AttachmentMeta => a !== null);
    if (att.length) out.att = att;
  }
  const st = p["sticker"];
  if (isObj(st) && typeof st["id"] === "string") {
    const sticker: NonNullable<MessagePayload["sticker"]> = { id: st["id"] };
    if (typeof st["blob"] === "string") sticker.blob = st["blob"];
    if (typeof st["name"] === "string") sticker.name = st["name"];
    if (isNum(st["w"])) sticker.w = st["w"];
    if (isNum(st["h"])) sticker.h = st["h"];
    if (typeof st["animated"] === "boolean") sticker.animated = st["animated"];
    if (typeof st["format"] === "string") sticker.format = st["format"];
    out.sticker = sticker;
  }
  if (Array.isArray(p["embeds"])) {
    const embeds = p["embeds"].filter((e): e is Obj => isObj(e) && typeof e["url"] === "string").map(sanitizeEmbed).filter((e) => typeof e["url"] === "string");
    if (embeds.length) out.embeds = embeds;
  }
  if (isNum(p["flags"])) out.flags = p["flags"];
  const poll = sanitizePoll(p["poll"]);
  if (poll) out.poll = poll;
  const fwd = p["fwd"];
  if (isObj(fwd) && isNum(fwd["at"])) out.fwd = { at: fwd["at"], ...(typeof fwd["comment"] === "string" ? { comment: fwd["comment"] } : {}) };
  const hadContent = p["t"] !== undefined || p["att"] !== undefined || p["sticker"] !== undefined || p["embeds"] !== undefined || p["poll"] !== undefined;
  if (hadContent && out.t === undefined && !out.att && !out.sticker && !out.embeds && !out.poll) {
    throw new DecryptError("malformed message payload");
  }
  return out;
}

export function isEnvelope(x: unknown): x is Envelope {
  if (typeof x !== "object" || x === null) return false;
  const e = x as Record<string, unknown>;
  return (
    e["v"] === 1 &&
    typeof e["conv"] === "string" &&
    typeof e["epoch"] === "number" &&
    Number.isInteger(e["epoch"]) &&
    typeof e["iv"] === "string" &&
    typeof e["ct"] === "string"
  );
}
