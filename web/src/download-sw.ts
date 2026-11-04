// Download service worker, registered for /__download/ only.
//
// It streams large attachments to disk: the page registers a job with the
// file's address, key and name, then points a hidden frame at
// /__download/<id>, and the answer is a stream this worker fills by fetching
// and decrypting one chunk at a time as the browser's download manager asks.
//
// It lives apart from the app's own worker (sw.ts) on purpose. A service worker
// with a fetch handler is shown every request its pages make, uploads
// included, and Chrome keeps each of those request bodies alive until that
// worker's garbage collector runs: sending a 6 GB file from a page under one
// took gigabytes of memory. This worker's scope holds no pages, so nothing the
// app sends ever passes through it.
//
// It also plays videos while they download: a frame of the page loads
// /__download/play/<id>, a page this worker writes holding nothing but a video,
// and answers that video's range requests by decrypting just the chunks they
// cover (lib/videoStream.ts has the page's end).
//
// This file is loaded as a classic script, so the build must not leave an
// import in it: whatever it uses from src/ has to be used by nothing else.
import { FileCorrupt, Permanent, decryptStream, type CipherBody, type PlainStream } from "./crypto/streamDecrypt";
import { ChunkCache, parseRange, rangeSource, type RangeSource } from "./crypto/streamRange";

interface SWEvent extends Event {
  waitUntil(p: Promise<unknown>): void;
}
interface SWFetchEvent extends SWEvent {
  request: Request;
  respondWith(r: Promise<Response> | Response): void;
}
interface SWMessageEvent extends SWEvent {
  data: unknown;
  ports: readonly MessagePort[];
}
interface SWScope {
  addEventListener(type: "install" | "activate", fn: (e: SWEvent) => void): void;
  addEventListener(type: "fetch", fn: (e: SWFetchEvent) => void): void;
  addEventListener(type: "message", fn: (e: SWMessageEvent) => void): void;
  skipWaiting(): Promise<void>;
  clients: {
    claim(): Promise<void>;
    matchAll(opts: { type: "window"; includeUncontrolled: boolean }): Promise<{ postMessage(msg: unknown): void }[]>;
  };
  location: Location;
}

const sw = self as unknown as SWScope;

sw.addEventListener("install", (e) => {
  e.waitUntil(sw.skipWaiting());
});

sw.addEventListener("activate", (e) => {
  e.waitUntil(sw.clients.claim());
});

const DOWNLOAD_PATH = "/__download/";
/** A job the browser has not come for within this long is dropped, key and all. */
const JOB_TTL = 60_000;
const JOB_ID = /^[0-9a-f]{32}$/;

interface DownloadJob {
  url: string;
  token: string | null;
  key: Uint8Array;
  name: string;
  size: number;
  port: MessagePort;
  at: number;
}

const jobs = new Map<string, DownloadJob>();
const streams = new Map<string, PlainStream>();

function readJob(msg: Record<string, unknown>, port: MessagePort): DownloadJob | null {
  const { url, token, key, name, size } = msg;
  if (typeof url !== "string" || !(key instanceof Uint8Array) || typeof name !== "string") return null;
  if (typeof size !== "number" || !Number.isSafeInteger(size) || size < 0) return null;
  if (token !== null && typeof token !== "string") return null;
  let target: URL;
  try {
    target = new URL(url);
  } catch {
    return null;
  }
  // the token only ever goes to this app's own attachments
  if (target.origin !== sw.location.origin || !target.pathname.startsWith("/media/attachments/")) return null;
  return { url: target.href, token, key, name, size, port, at: Date.now() };
}

sw.addEventListener("message", (e) => {
  if (!e.data || typeof e.data !== "object") return;
  const msg = e.data as Record<string, unknown>;
  const id = typeof msg["id"] === "string" && JOB_ID.test(msg["id"]) ? msg["id"] : null;
  const port = e.ports[0];
  if (msg["type"] === "download" && id && port) {
    const now = Date.now();
    for (const [k, job] of jobs) {
      if (now - job.at > JOB_TTL) jobs.delete(k);
    }
    const job = readJob(msg, port);
    if (!job) {
      port.postMessage({ type: "error", message: "That download could not be started." });
      return;
    }
    jobs.set(id, job);
    if (msg["toFile"] === true) {
      // A fetch from the uncontrolled app page would bypass this worker.
      // Transfer the stream itself so its chosen file can consume it.
      const body = streamDownload(id).body!;
      try {
        port.postMessage({ type: "file", body }, [body]);
      } catch {
        void body.cancel();
        port.postMessage({ type: "file-unavailable" });
      }
      return;
    }
    port.postMessage({ type: "ready" });
    return;
  }
  if (msg["type"] === "download-forget" && id) {
    jobs.delete(id);
    void streams.get(id)?.cancel();
  }
  if (msg["type"] === "stream" && id) {
    const ok = sayStream(id, msg);
    port?.postMessage({ type: ok ? "stream-ready" : "stream-refused" });
    return;
  }
  if (msg["type"] === "stream-forget" && id) {
    streamJobs.delete(id);
    chunkCache.forget(`${id}:`);
  }
  // "keepalive" needs no answer: receiving it is what keeps this worker running
});

/** Fetches the ciphertext from `offset` onwards, sorting failures worth retrying from those that are not. */
async function openCipher(job: DownloadJob, offset: number): Promise<CipherBody> {
  const headers: Record<string, string> = {};
  if (job.token) headers["Authorization"] = `Bearer ${job.token}`;
  if (offset > 0) headers["Range"] = `bytes=${offset}-`;
  const res = await fetch(job.url, { headers, cache: "no-store" });
  if (res.body && (res.status === 200 || res.status === 206)) {
    let start = 0;
    if (res.status === 206) {
      const m = /^bytes (\d+)-/.exec(res.headers.get("Content-Range") ?? "");
      if (!m) {
        void res.body.cancel().catch(() => undefined);
        throw new Permanent("The server sent part of the file without saying which part.");
      }
      start = Number(m[1]);
    }
    return { reader: res.body.getReader(), start };
  }
  void res.body?.cancel().catch(() => undefined);
  if (res.status >= 500 || res.status === 408 || res.status === 429) throw new Error(`download: the server answered ${res.status}`);
  if (res.status === 404) throw new Permanent("That file is no longer on the server.");
  if (res.status === 401 || res.status === 403) throw new Permanent("You no longer have access to that file.");
  throw new Permanent(`The server refused the download (${res.status}).`);
}

function failure(err: unknown): string {
  if (err instanceof FileCorrupt) return "This file could not be decrypted. It may be damaged.";
  if (err instanceof Permanent) return err.message;
  return "The connection kept dropping, so the download stopped.";
}

/** A header the browser will accept for any file name, with the real name alongside for those that read it. */
function disposition(name: string): string {
  const clean = name.replace(/[\u0000-\u001f\u007f"\\/]/g, "_").trim() || "download";
  const ascii = clean.replace(/[^\x20-\x7e]/g, "_");
  const encoded = encodeURIComponent(clean).replace(/['()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encoded}`;
}

function streamDownload(id: string): Response {
  const job = jobs.get(id);
  jobs.delete(id);
  // nothing waiting under that name: answer with nothing rather than the app
  if (!job) return new Response(null, { status: 204 });
  const { port, size } = job;
  const tell = (msg: Record<string, unknown>): void => {
    try {
      port.postMessage(msg);
    } catch {
      /* the page has gone; the download carries on without it */
    }
  };
  const source = decryptStream({ key: job.key, size, open: (offset) => openCipher(job, offset) });
  streams.set(id, source);
  let produced = 0;
  let told = 0;
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        for (;;) {
          const chunk = await source.next();
          if (!chunk) {
            // Stopping short has to fail. Closing the stream instead would
            // leave a cut-off file on disk looking like a whole one.
            if (produced !== size) throw new Error("download: stopped before the end of the file");
            streams.delete(id);
            controller.close();
            tell({ type: "done" });
            return;
          }
          if (chunk.length === 0) continue;
          controller.enqueue(chunk);
          produced += chunk.length;
          const now = Date.now();
          if (now - told >= 250) {
            told = now;
            tell({ type: "progress", done: produced });
          }
          return;
        }
      } catch (err) {
        streams.delete(id);
        void source.cancel();
        controller.error(err);
        tell({ type: "error", message: failure(err) });
      }
    },
    cancel() {
      streams.delete(id);
      void source.cancel();
      tell({ type: "cancelled" });
    },
  });
  tell({ type: "started" });
  return new Response(body, {
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Disposition": disposition(job.name),
      "Content-Length": String(size),
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

// --- playing a video while it downloads ----------------------------------

const PLAY_PATH = "/__download/play/";
const STREAM_PATH = "/__download/stream/";
/** A stream its page has not said again for this long is dropped, key and all. The page says it every 15 s. */
const STREAM_TTL = 120_000;
/** How long a request for a stream this worker has lost waits for its page to say it again. */
const RECOVER_MS = 3_000;
/** What a video may be served as; anything else is served as bytes, never as something a browser would run. */
const MEDIA_TYPE = /^(?:video|audio)\/[0-9a-z.+-]{1,100}$/i;

interface StreamJob {
  url: string;
  token: string | null;
  type: string;
  source: RangeSource;
  at: number;
}

const streamJobs = new Map<string, StreamJob>();
/** Every stream's decrypted chunks together, 4 MB each. */
const chunkCache = new ChunkCache(8);

function sweepStreams(now: number): void {
  for (const [id, job] of streamJobs) {
    if (now - job.at <= STREAM_TTL) continue;
    streamJobs.delete(id);
    chunkCache.forget(`${id}:`);
  }
}

/** Takes a stream from its page, or the same one said again with a fresh token. Whether it could be taken. */
function sayStream(id: string, msg: Record<string, unknown>): boolean {
  const { url, token, key, size, mime } = msg;
  if (typeof url !== "string" || typeof size !== "number" || !Number.isSafeInteger(size) || size < 0) return false;
  if (token !== null && typeof token !== "string") return false;
  if (key !== null && !(key instanceof Uint8Array && key.length === 32)) return false;
  let target: URL;
  try {
    target = new URL(url);
  } catch {
    return false;
  }
  // the token only ever goes to this app's own attachments
  if (target.origin !== sw.location.origin || !target.pathname.startsWith("/media/attachments/")) return false;
  const now = Date.now();
  sweepStreams(now);
  const have = streamJobs.get(id);
  if (have && have.url === target.href && have.source.size === size) {
    have.token = token;
    have.at = now;
    return true;
  }
  const job: StreamJob = { url: target.href, token, type: typeof mime === "string" && MEDIA_TYPE.test(mime) ? mime : "application/octet-stream", at: now, source: null as unknown as RangeSource };
  job.source = rangeSource({ size, key, cache: chunkCache, name: id, fetch: (start, end) => fetchStretch(job, start, end), onFail: () => void tellPages({ type: "stream-failed", id }) });
  streamJobs.set(id, job);
  return true;
}

/** Reads a body that ignored the Range asked for, keeping bytes [start, end] and letting the rest go. */
async function stretchOf(body: ReadableStream<Uint8Array>, start: number, end: number): Promise<Uint8Array<ArrayBuffer>> {
  const out = new Uint8Array(end - start + 1);
  const reader = body.getReader();
  let at = 0;
  let filled = 0;
  try {
    while (filled < out.length) {
      const { done, value } = await reader.read();
      if (done) break;
      const from = Math.max(start, at);
      const to = Math.min(end + 1, at + value.length);
      if (to > from) {
        out.set(value.subarray(from - at, to - at), from - start);
        filled += to - from;
      }
      at += value.length;
    }
  } finally {
    void reader.cancel().catch(() => undefined);
  }
  return filled === out.length ? out : out.subarray(0, filled);
}

/** Bytes [start, end] of a stream's file on the server. */
async function fetchStretch(job: StreamJob, start: number, end: number): Promise<Uint8Array<ArrayBuffer>> {
  const headers: Record<string, string> = { Range: `bytes=${start}-${end}` };
  if (job.token) headers["Authorization"] = `Bearer ${job.token}`;
  const res = await fetch(job.url, { headers, cache: "no-store" });
  if (res.status === 206) {
    const m = /^bytes (\d+)-(\d+)\//.exec(res.headers.get("Content-Range") ?? "");
    if (!m || Number(m[1]) !== start || Number(m[2]) !== end) {
      void res.body?.cancel().catch(() => undefined);
      throw new Error("stream: the server sent another part of the file");
    }
    return new Uint8Array(await res.arrayBuffer());
  }
  if (res.status === 200 && res.body) return stretchOf(res.body, start, end);
  void res.body?.cancel().catch(() => undefined);
  if (res.status >= 500 || res.status === 408 || res.status === 429) throw new Error(`stream: the server answered ${res.status}`);
  throw new Permanent(`stream: the server refused (${res.status})`);
}

function streamHeaders(job: StreamJob, length: number): Record<string, string> {
  return {
    "Content-Type": job.type,
    "Content-Length": String(length),
    "Accept-Ranges": "bytes",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    // opened on its own, it is a file to look at, never a page that runs anything
    "Content-Security-Policy": "sandbox; default-src 'none'",
  };
}

/**
 * Answers a video's request for part of its file, read a chunk at a time as
 * the player takes it. One that asks for everything from a point on gets all
 * of it, at the player's pace: a shorter answer reads as the end of the file
 * to some players (WebKit's on Linux stops there). The page's regular word
 * keeps the worker running meanwhile.
 */
function streamResponse(job: StreamJob, rangeHeader: string | null): Response {
  const size = job.source.size;
  if (size === 0) return new Response(null, { status: 200, headers: streamHeaders(job, 0) });
  const range = parseRange(rangeHeader, size);
  if (range === "unsatisfiable") return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${size}`, "Cache-Control": "no-store" } });
  if (!range) return new Response(job.source.read(0, size - 1, true), { status: 200, headers: streamHeaders(job, size) });
  const { start, end } = range;
  return new Response(job.source.read(start, end, range.open), {
    status: 206,
    headers: { ...streamHeaders(job, end - start + 1), "Content-Range": `bytes ${start}-${end}/${size}` },
  });
}

/** Tells every page of the app something about a stream; the one that holds it listens (lib/videoStream.ts). */
async function tellPages(msg: Record<string, unknown>): Promise<void> {
  const pages = await sw.clients.matchAll({ type: "window", includeUncontrolled: true });
  for (const page of pages) page.postMessage(msg);
}

/** Asks the app's pages for a stream this worker no longer has (the browser stopped it since), and waits a moment for it. */
async function recoverStream(id: string): Promise<StreamJob | undefined> {
  await tellPages({ type: "stream-wanted", id });
  for (let waited = 0; waited < RECOVER_MS; waited += 100) {
    await new Promise((r) => setTimeout(r, 100));
    const job = streamJobs.get(id);
    if (job) return job;
  }
  return undefined;
}

async function serveStream(id: string, request: Request): Promise<Response> {
  if (!JOB_ID.test(id)) return new Response(null, { status: 404 });
  sweepStreams(Date.now());
  const job = streamJobs.get(id) ?? (await recoverStream(id));
  if (!job) {
    void tellPages({ type: "stream-failed", id });
    return new Response(null, { status: 404, headers: { "Cache-Control": "no-store" } });
  }
  job.at = Date.now();
  return streamResponse(job, request.headers.get("Range"));
}

/** The page the video plays in: nothing but the video, which the app's player works from outside the frame. */
function playerPage(id: string, url: URL): Response {
  const preload = url.searchParams.get("preload") === "metadata" ? "metadata" : "none";
  const html =
    `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}video{display:block;width:100%;height:100%;object-fit:contain}</style>` +
    `</head><body><video src="${STREAM_PATH}${id}" playsinline preload="${preload}"></video></body></html>`;
  return new Response(html, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Content-Security-Policy": "default-src 'none'; media-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'self'",
    },
  });
}

sw.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== sw.location.origin || !url.pathname.startsWith(DOWNLOAD_PATH)) return;
  if (url.pathname.startsWith(PLAY_PATH)) {
    const id = url.pathname.slice(PLAY_PATH.length);
    e.respondWith(JOB_ID.test(id) ? playerPage(id, url) : new Response(null, { status: 404 }));
    return;
  }
  if (url.pathname.startsWith(STREAM_PATH)) {
    e.respondWith(serveStream(url.pathname.slice(STREAM_PATH.length), e.request));
    return;
  }
  e.respondWith(streamDownload(url.pathname.slice(DOWNLOAD_PATH.length)));
});
