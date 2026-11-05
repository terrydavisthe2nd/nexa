/**
 * Data export: your profile, relationships, channels and every message you
 * hold keys for, decrypted on this device and zipped without any library.
 * The zip is "store" (uncompressed): simple, fast, and the JSON is small.
 */
import { channels as channelsApi } from "@/api/channels";
import { ApiError } from "@/api/client";
import { messages as messagesApi } from "@/api/messages";
import { relationships as relationshipsApi } from "@/api/relationships";
import { users as usersApi } from "@/api/users";
import type { Channel, Message } from "@/api/types";
import { decryptForStore } from "@/lib/decrypt";
import { REQUEST_GAP_MS, backoffFor } from "@/search/pacing";
import { ReadableError } from "@/lib/readableError";
import { useChannels } from "@/stores/channels";
import { useServers } from "@/stores/servers";
import { useSettings } from "@/stores/settings";
import { t } from "@/i18n";

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < data.length; i++) crc = CRC_TABLE[(crc ^ data[i]!) & 0xff]! ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function dosDateTime(d: Date): { time: number; date: number } {
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  return { time, date };
}

/**
 * The most a plain zip can describe: 65,535 entries and 4 GiB of offsets.
 * Past either, the fields wrap and the archive is quietly corrupt; ZIP64
 * would lift both, but an export that size would not fit in a tab's memory
 * anyway, so it is refused with a sentence rather than written broken.
 */
const ZIP_MAX_ENTRIES = 0xffff;
const ZIP_MAX_BYTES = 0xffffffff;

/** Builds a store-only zip from (name → bytes) entries. */
export function buildZip(entries: { name: string; data: Uint8Array; mtime?: Date }[]): Uint8Array {
  if (entries.length > ZIP_MAX_ENTRIES) throw new ReadableError(t("export.tooLarge"));
  const enc = new TextEncoder();
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;
  for (const e of entries) {
    const name = enc.encode(e.name);
    const crc = crc32(e.data);
    const { time, date } = dosDateTime(e.mtime ?? new Date());
    const local = new Uint8Array(30 + name.length + e.data.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true); // UTF-8 names
    lv.setUint16(8, 0, true);
    lv.setUint16(10, time, true);
    lv.setUint16(12, date, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, e.data.length, true);
    lv.setUint32(22, e.data.length, true);
    lv.setUint16(26, name.length, true);
    lv.setUint16(28, 0, true);
    local.set(name, 30);
    local.set(e.data, 30 + name.length);
    locals.push(local);
    const central = new Uint8Array(46 + name.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, time, true);
    cv.setUint16(14, date, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, e.data.length, true);
    cv.setUint32(24, e.data.length, true);
    cv.setUint16(28, name.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, 0, true);
    cv.setUint32(42, offset, true);
    central.set(name, 46);
    centrals.push(central);
    offset += local.length;
    if (offset > ZIP_MAX_BYTES) throw new ReadableError(t("export.tooLarge"));
  }
  const centralSize = centrals.reduce((n, c) => n + c.length, 0);
  if (offset + centralSize > ZIP_MAX_BYTES) throw new ReadableError(t("export.tooLarge"));
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(4, 0, true);
  ev.setUint16(6, 0, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);
  ev.setUint16(20, 0, true);
  const out = new Uint8Array(offset + centralSize + 22);
  let p = 0;
  for (const l of locals) {
    out.set(l, p);
    p += l.length;
  }
  for (const c of centrals) {
    out.set(c, p);
    p += c.length;
  }
  out.set(end, p);
  return out;
}

function json(v: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(v, null, 2));
}

function safeName(s: string): string {
  return s.replace(/[^A-Za-z0-9._-]+/g, "_").slice(0, 60) || "channel";
}

/** When the walk last asked for a page, and how it waits; tests replace the clock. */
export interface Pace {
  last: number;
  now: () => number;
  sleep: (ms: number) => Promise<void>;
}

export function realPace(): Pace {
  return { last: 0, now: () => Date.now(), sleep: (ms) => new Promise((r) => setTimeout(r, ms)) };
}

/** How many times one page is asked for before the export gives up. */
const PAGE_TRIES = 6;

/**
 * One page of history, paced like the search walk. The history route sits
 * behind the per-account limiter, and an account with a few hundred pages
 * used to run into it part-way: every page after that was a refusal, and the
 * export came back with most of its channels missing.
 */
async function historyPage(channelId: string, before: string | undefined, pace: Pace): Promise<Message[]> {
  for (let attempt = 0; ; attempt++) {
    const wait = REQUEST_GAP_MS - (pace.now() - pace.last);
    if (wait > 0) await pace.sleep(wait);
    pace.last = pace.now();
    try {
      return await messagesApi.list(channelId, { limit: 100, ...(before ? { before } : {}) });
    } catch (err) {
      // slow down, or the connection or the server: worth waiting for; the rest is final
      const again = err instanceof ApiError && (err.status === 429 || err.status === 0 || err.status >= 500);
      if (!again || attempt + 1 >= PAGE_TRIES) throw err;
      await pace.sleep(backoffFor(err, attempt));
    }
  }
}

/**
 * Walks a channel's whole history (newest → oldest) and decrypts what we can.
 * Each page comes back oldest first, so the pages are kept apart and only
 * their order is reversed: reversing the flat list would also turn every
 * page of 100 around.
 */
export async function allMessages(channelId: string, onProgress: (n: number) => void, pace: Pace = realPace()): Promise<unknown[]> {
  const pages: unknown[][] = [];
  let count = 0;
  let before: string | undefined;
  for (let page = 0; page < 10000; page++) {
    const batch = await historyPage(channelId, before, pace);
    if (batch.length === 0) break;
    const rows: unknown[] = [];
    for (const m of batch) {
      const d = await decryptForStore(m);
      rows.push({
        id: m.id,
        author_id: m.author_id,
        type: m.type,
        created_at: new Date(m.created_at).toISOString(),
        edited_at: m.edited_at ? new Date(m.edited_at).toISOString() : null,
        reply_to_id: m.reply_to_id,
        text: [d.payload?.fwd?.comment, d.payload?.t].filter(Boolean).join("\n") || null,
        attachments: d.payload?.att?.map((a) => ({ name: a.name, type: a.type, size: a.size })) ?? [],
        sticker: d.payload?.sticker ?? null,
        embeds: d.payload?.embeds ?? [],
        reactions: m.reactions,
        decrypted: !d.decryptError,
        error: d.decryptError ?? null,
      });
    }
    pages.push(rows);
    count += rows.length;
    onProgress(count);
    before = batch[0]!.id;
    if (batch.length < 100) break;
  }
  return pages.reverse().flat();
}

const README = `Nexa data export

Everything here was decrypted on your device. Messages you never held a key for are marked decrypted: false.
Attachments are listed by name; download them from the app while you still have access.
`;

/** Gathers everything and returns the zip bytes. */
export async function exportMyData(onProgress: (text: string) => void): Promise<Uint8Array> {
  const entries: { name: string; data: Uint8Array }[] = [];
  onProgress(t("export.readingProfile"));
  const me = await usersApi.me();
  entries.push({ name: "profile.json", data: json(me) });
  // The synchronized lock policy contains a passcode verifier and its KDF
  // parameters. A plaintext export must not become an offline guessing kit.
  const { appLock: _lockPolicy, ...settings } = useSettings.getState().settings;
  entries.push({ name: "settings.json", data: json(settings) });
  onProgress(t("export.readingFriends"));
  entries.push({ name: "relationships.json", data: json(await relationshipsApi.list()) });
  entries.push({ name: "shared-games.json", data: json((await usersApi.mySharedGames()).games) });
  const dms: Channel[] = await channelsApi.listDMs();
  const serverChannels = Object.values(useChannels.getState().byId).filter((c) => c.server_id && c.type === "text");
  entries.push({ name: "servers.json", data: json(Object.values(useServers.getState().byId).map((s) => ({ ...s.server, roles: Object.values(s.roles), me: s.me }))) });
  entries.push({ name: "channels.json", data: json([...dms, ...serverChannels]) });
  const all = [...dms, ...serverChannels];
  const pace = realPace();
  const skipped: string[] = [];
  for (let i = 0; i < all.length; i++) {
    const ch = all[i]!;
    const label = ch.server_id ? `${useServers.getState().byId[ch.server_id]?.server.name ?? ch.server_id}/${ch.name}` : ch.name || ch.recipient_ids.join("-");
    const step = { done: i + 1, total: all.length, name: label };
    onProgress(t("export.readingChannel", step));
    try {
      const msgs = await allMessages(ch.id, (n) => onProgress(t("export.readingChannelCount", { ...step, messages: t("export.messagesCount", { count: n }) })), pace);
      entries.push({ name: `messages/${ch.server_id ? safeName(useServers.getState().byId[ch.server_id]?.server.name ?? ch.server_id) + "/" : "dm/"}${safeName(ch.name || ch.id)}-${ch.id}.json`, data: json({ channel: ch, messages: msgs }) });
    } catch (err) {
      // A conversation this account can no longer read (left, or gone) is
      // said so in the README and the rest goes on. Anything else would leave
      // a hole nobody sees until it is too late: the export fails, and says so.
      if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
        skipped.push(`${label} (${ch.id})`);
        continue;
      }
      throw err;
    }
  }
  // The notes stay in English, as the data does; the first line is in the reader's language.
  const notes = [t("export.readmeFirstLine"), "", README];
  if (skipped.length) notes.push("These conversations could not be read any more, so they are not here:", ...skipped.map((s) => `- ${s}`), "");
  entries.push({ name: "README.txt", data: new TextEncoder().encode(notes.join("\n")) });
  return buildZip(entries);
}

/** Hands the bytes to the browser as a download. */
export function downloadBytes(name: string, bytes: Uint8Array, type = "application/zip"): void {
  const blob = new Blob([bytes as BlobPart], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // iOS may still be asking where to save it ten seconds on
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
