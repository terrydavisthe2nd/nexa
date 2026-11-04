import { ApiError, api, errorFrom } from "@/api/client";
import { uploads as uploadsApi } from "@/api/uploads";
import { b64 } from "@/crypto/bytes";
import { FRAME_LEN, HEADER_LEN, chunkCount, encryptChunks, encryptedSize, frameOffset, newBaseIv, newFileKey } from "@/crypto/fileChunks";
import { MESSAGE_LIMITS, type AttachmentMeta, type AttachmentPreview } from "@/crypto/message";
import { holdReload } from "./busy";
import { seedMedia } from "./mediaCache";
import { lookAtPicture, type PictureCopy, type PictureLook } from "./pictureCopy";
import { fileMaxIn, setFreeFileMax } from "./plans";
import { offerPlan } from "@/stores/upsell";
import { scrubFile } from "./scrubMetadata";
import type { PieceReply, PieceRequest } from "./upload.worker";

import { t } from "@/i18n";

/**
 * What this server takes: the largest file, the most to send in one request,
 * and the size of each piece of a file bigger than that. A file over one
 * piece goes up in pieces: each is encrypted whole in memory before it goes,
 * with one following piece prepared during the request. A dropped connection
 * costs only the piece in flight, which on a phone matters far more than the
 * round trip a piece adds.
 */
const limits = { single: 32 * 1024 * 1024, chunk: 32 * 1024 * 1024 };

/** Remembers the limits the server reported, so the composer can say no early. */
export function setUploadLimits(reported: { max?: number | undefined; single?: number | undefined; chunk?: number | undefined }): void {
  // the server's one limit is the free plan's (api/billing.go)
  if (reported.max && reported.max > 0) setFreeFileMax(reported.max);
  if (reported.single && reported.single > 0) limits.single = reported.single;
  if (reported.chunk && reported.chunk > 0) limits.chunk = reported.chunk;
}

/** The largest file this account may send, to a channel when one is named: its plan's (lib/plans.ts), or a boosted server's. */
export function uploadLimit(channelId?: string): number {
  return fileMaxIn(channelId);
}

/** Whether a file of `size` bytes is within the limit once encrypted, which is what the server measures. */
export function fitsUploadLimit(size: number, channelId?: string): boolean {
  return encryptedSize(size) <= uploadLimit(channelId);
}

/** Whether a file of `size` bytes goes up in one request, rather than in pieces. */
export function goesInOneRequest(size: number): boolean {
  return encryptedSize(size) <= limits.single;
}

export interface Staged {
  id: string;
  file: File;
  name: string;
  spoiler: boolean;
  preview: string | null;
  progress: number;
  error: string | null;
  /** A voice message recorded in the box: how long it runs and the shape of its sound. */
  voice?: { duration: number; wave: string };
}

let seq = 0;
/**
 * Readies a picked file for sending. A photo or a video loses the hidden
 * details it carries first (scrubMetadata.ts), so what is previewed, measured,
 * encrypted and sent is the cleaned file.
 */
export async function stage(file: File): Promise<Staged> {
  const clean = await scrubFile(file);
  seq += 1;
  const preview = clean.type.startsWith("image/") || clean.type.startsWith("video/") ? URL.createObjectURL(clean) : null;
  return { id: `f${seq}`, file: clean, name: fitName(file.name), spoiler: false, preview, progress: 0, error: null };
}

/**
 * A file's name short enough for every app to open the message
 * (MESSAGE_LIMITS.fileName): a longer one keeps its start and its extension.
 */
export function fitName(name: string): string {
  const chars = Array.from(name);
  const max = MESSAGE_LIMITS.fileName;
  if (chars.length <= max) return name;
  const dot = name.lastIndexOf(".");
  const ext = dot > 0 && name.length - dot <= 16 ? Array.from(name.slice(dot)) : [];
  return chars.slice(0, max - ext.length).join("") + ext.join("");
}

/**
 * One stretch of a file to encrypt and send: chunks [first, first + count),
 * less the first `skip` bytes the server already has.
 */
export interface PieceJob {
  method: "POST" | "PATCH";
  path: string;
  headers?: Record<string, string>;
  file: Blob;
  key: Uint8Array;
  baseIv: Uint8Array;
  first: number;
  count: number;
  skip: number;
  /** Prepared while the previous request was in flight. Transferred to its sender once. */
  body?: Uint8Array<ArrayBuffer>;
}

/** Sends a stretch, resolving with the server's answer; `signal` stops it part way. */
export type PieceSender = (job: PieceJob, onProgress: (loaded: number) => void, signal?: AbortSignal) => Promise<unknown>;

/** What an upload stopped on purpose rejects with. */
function cancelled(): ApiError {
  return new ApiError(0, "aborted", t("errors.thatWasCancelledBeforeItFinished"));
}

/** Encrypts the stretch a job describes: exactly the bytes its request carries. */
async function bodyOf(job: PieceJob): Promise<Uint8Array<ArrayBuffer>> {
  if (job.body) return job.body;
  const whole = await encryptChunks(job.file, job.key, job.baseIv, job.first, job.count);
  return whole.subarray(job.skip);
}

/** Sends from the page itself, for a browser that cannot start the worker. */
export const sendInPage: PieceSender = async (job, onProgress, signal) => {
  if (signal?.aborted) throw cancelled();
  const body = await bodyOf(job);
  if (signal?.aborted) throw cancelled();
  return api.upload(job.path, body, { method: job.method, headers: job.headers ?? {}, onProgress, ...(signal ? { signal } : {}) });
};

let workers = typeof Worker !== "undefined";

/**
 * How long the page waits on a worker that has gone quiet. Longer than the
 * worker's own stall timeout, so a request that dies is reported by the worker
 * rather than guessed at here; this is only for a worker that stops answering.
 */
const WORKER_STALL_MS = 90_000;

/**
 * Encrypts a stretch here, then hands the bytes to a worker of its own that
 * sends them and is ended once they have landed, so no finished request's
 * body outlives it (see upload.worker.ts).
 */
export const sendInWorker: PieceSender = async (job, onProgress, signal) => {
  if (!workers) return sendInPage(job, onProgress, signal);
  if (signal?.aborted) throw cancelled();
  const body = await bodyOf(job);
  if (signal?.aborted) throw cancelled();
  const headers: Record<string, string> = { "Content-Type": "application/octet-stream", ...job.headers };
  const token = api.getToken();
  if (token) headers["Authorization"] = `Bearer ${token}`;
  return new Promise((resolve, reject) => {
    let worker: Worker;
    try {
      worker = new Worker(new URL("./upload.worker.ts", import.meta.url), { type: "module" });
    } catch {
      workers = false;
      api.upload(job.path, body, { method: job.method, headers: job.headers ?? {}, onProgress, ...(signal ? { signal } : {}) }).then(resolve, reject);
      return;
    }
    let heard = false;
    let stall: ReturnType<typeof setTimeout> | undefined;
    // stopping the upload ends the worker, and the request with it
    const onAbort = (): void => {
      settled();
      worker.terminate();
      reject(cancelled());
    };
    signal?.addEventListener("abort", onAbort, { once: true });
    const settled = (): void => {
      clearTimeout(stall);
      signal?.removeEventListener("abort", onAbort);
    };
    // A worker can be taken away outright — a backgrounded tab on a phone short
    // of memory — and then neither a message nor an error ever comes. The
    // upload would show a frozen bar for good, and the reload hold it took
    // would keep a newly deployed version from ever applying to this tab.
    const watch = (): void => {
      clearTimeout(stall);
      stall = setTimeout(() => {
        worker.terminate();
        reject(new ApiError(0, "worker", t("attachments.theUploadStoppedUnexpectedly")));
      }, WORKER_STALL_MS);
    };
    worker.onmessage = (e: MessageEvent<PieceReply>) => {
      heard = true;
      const reply = e.data;
      if (reply.type === "progress") {
        watch();
        onProgress(reply.loaded);
        return;
      }
      settled();
      worker.terminate();
      if (reply.type === "failed") {
        reject(new ApiError(0, "network", t("attachments.theUploadCouldNotReachThe")));
        return;
      }
      if (reply.status >= 200 && reply.status < 300) {
        try {
          resolve(reply.text ? JSON.parse(reply.text) : undefined);
        } catch {
          reject(new ApiError(reply.status, "bad_response", t("attachments.theServerSAnswerCouldNot")));
        }
        return;
      }
      if (reply.status === 401) api.onUnauthorized?.();
      reject(errorFrom(reply.status, reply.text, reply.retryAfter));
    };
    worker.onerror = (e) => {
      settled();
      worker.terminate();
      if (heard) {
        reject(new ApiError(0, "worker", t("attachments.theUploadStoppedUnexpectedly")));
        return;
      }
      // It never got going (an older browser, or its script would not load).
      // The body went to it, so make the stretch again and send it from here.
      e.preventDefault();
      workers = false;
      const { body: _body, ...unprepared } = job;
      sendInPage(unprepared, onProgress, signal).then(resolve, reject);
    };
    const request: PieceRequest = { url: new URL(api.url(job.path), location.href).href, method: job.method, headers, body: body.buffer, offset: body.byteOffset, length: body.byteLength };
    watch();
    worker.postMessage(request, [body.buffer]);
  });
};

/** A failure a second try might get past: the connection or the server, not the request itself. */
function worthAnotherTry(err: unknown): boolean {
  if (!(err instanceof ApiError)) return false;
  if (err.code === "aborted") return false;
  return err.status === 0 || err.status >= 500;
}

/** Where a file is going: a conversation, by its id, or a note, by where its files are sent. */
export type UploadPlace = string | { path: string };

function placePath(to: UploadPlace): string {
  return typeof to === "string" ? `/channels/${to}/attachments` : to.path;
}

/** A note's files go up as a message's do, to the note. */
export function noteUploads(noteId: string): UploadPlace {
  return { path: `/notes/${noteId}/attachments` };
}

/**
 * Encrypts and sends a picture's small copy (lib/pictureCopy.ts): a file of
 * its own, under a key of its own, in one request.
 */
async function sendCopy(to: UploadPlace, copy: PictureCopy, send: PieceSender, signal?: AbortSignal): Promise<{ meta: AttachmentPreview; blob: Blob }> {
  const key = newFileKey();
  const baseIv = newBaseIv();
  const job: PieceJob = { method: "POST", path: placePath(to), file: copy.blob, key, baseIv, first: 0, count: chunkCount(copy.blob.size), skip: 0 };
  const res = (await send(job, () => undefined, signal)) as { blob_id: string; size: number };
  return { meta: { blob_id: res.blob_id, key: b64.enc(key), size: copy.blob.size, type: copy.type, w: copy.w, h: copy.h }, blob: copy.blob };
}

/**
 * Encrypts and uploads a staged file, returning the metadata that rides inside the message.
 *
 * A small file goes up as one request, tried a second time if the connection
 * or the server lets it down. Anything larger goes up a stretch of chunks at a
 * time, preparing one following stretch and resuming where it stopped.
 * Each request goes through a worker that is ended afterwards, so memory
 * holds the active request and one prepared piece at most, however big the file is.
 *
 * Meanwhile a picture is measured and, when it is big, a small copy of it is
 * made and sent beside it for the chat to show.
 */
export async function uploadStaged(to: UploadPlace, st: Staged, onProgress: (p: number) => void, send: PieceSender = sendInWorker, signal?: AbortSignal): Promise<{ meta: AttachmentMeta; blob_id: string; size: number }> {
  const channelId = typeof to === "string" ? to : undefined;
  if (!fitsUploadLimit(st.file.size, channelId)) throw new Error(t("attachments.overServerLimit", { limit: formatSize(uploadLimit(channelId)) }));
  const total = encryptedSize(st.file.size);
  const key = newFileKey();
  const baseIv = newBaseIv();
  let res: { blob_id: string; size: number };
  let seen: PictureLook;
  let copy: Awaited<ReturnType<typeof sendCopy>> | null;
  // a new version of the app waits for the upload rather than reloading over it
  const release = holdReload("upload");
  const look = lookAtPicture(st.file);
  // a copy that does not make it up only leaves the picture to be shown at its full size
  const copied = look.then((found) => (found.copy ? sendCopy(to, found.copy, send, signal) : null)).catch(() => null);
  try {
    // One request up to the server's single-request limit, pieces only above it:
    // the owner chose fewer requests over resuming mid-file for files that fit in one.
    if (total <= limits.single) {
      const job: PieceJob = { method: "POST", path: placePath(to), file: st.file, key, baseIv, first: 0, count: chunkCount(st.file.size), skip: 0 };
      const once = async () => (await send(job, (loaded) => onProgress(loaded / total), signal)) as { blob_id: string; size: number };
      try {
        res = await once();
      } catch (err) {
        // Once more, after a moment. A copy that did land the first time only
        // leaves a file nothing refers to, which the server sweeps away.
        if (signal?.aborted || !worthAnotherTry(err)) throw err;
        await connectionBack(1500, signal);
        if (signal?.aborted) throw cancelled();
        onProgress(0);
        res = await once();
      }
    } else {
      res = await uploadInPieces(st.file, key, baseIv, (sent) => onProgress(sent / total), send, signal, channelId);
    }
    [seen, copy] = await Promise.all([look, copied]);
  } catch (err) {
    // this month's uploads used up: a plan with more is offered beside the failed upload
    if (err instanceof ApiError && err.code === "upload_month_full") offerPlan({ kind: "storage" });
    throw err;
  } finally {
    release();
  }
  if (signal?.aborted) throw cancelled();
  const meta: AttachmentMeta = { blob_id: res.blob_id, name: st.name || fitName(st.file.name), type: st.file.type || "application/octet-stream", size: st.file.size, key: b64.enc(key) };
  if (seen.w && seen.h) {
    meta.w = seen.w;
    meta.h = seen.h;
  }
  if (copy) meta.preview = copy.meta;
  if (st.spoiler) meta.spoiler = true;
  if (st.voice) {
    meta.voice = true;
    meta.duration = st.voice.duration;
    meta.wave = st.voice.wave;
  }
  // what was just sent is on this device already, so showing it here takes no download
  if (/^(image|video|audio)\//.test(meta.type)) seedMedia(res.blob_id, st.file, meta.type);
  if (copy) seedMedia(copy.meta.blob_id, copy.blob, copy.meta.type);
  return { meta, blob_id: res.blob_id, size: res.size };
}

function formatSize(bytes: number): string {
  const gb = bytes / (1024 * 1024 * 1024);
  if (gb >= 1) return `${Math.round(gb * 10) / 10} GB`;
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

/** How many times one piece is retried, while the server can be reached, before the upload gives up. */
const CHUNK_TRIES = 4;

/** How long an upload waits for a lost connection to come back without getting any further. */
const STALL_MS = 5 * 60_000;

/** Waits `ms`, or less if the device was offline and comes back online, or the upload is cancelled. */
function connectionBack(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const offline = typeof window !== "undefined" && navigator.onLine === false;
    const done = (): void => {
      clearTimeout(timer);
      if (offline) window.removeEventListener("online", done);
      signal?.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    if (offline) window.addEventListener("online", done);
    signal?.addEventListener("abort", done);
  });
}

/**
 * How much of an upload the server holds. When it cannot be asked (the phone
 * is between networks, the server is restarting) this keeps asking, further
 * apart each time, until `deadline`; null means it never answered.
 * `waited` says the connection was down for a while.
 */
async function landedSoFar(id: string, deadline: number, signal?: AbortSignal): Promise<{ received: number; waited: boolean } | null> {
  for (let attempt = 0; ; attempt++) {
    // a cancelled upload stops asking at once; it used to wait out its five
    // minutes, holding back an app update and the server-side cancel with it
    if (signal?.aborted) return null;
    try {
      const state = await uploadsApi.status(id);
      return { received: state.received, waited: attempt > 0 };
    } catch (err) {
      // gone, or not ours any more: no amount of waiting brings it back
      if (err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 408 && err.status !== 429) return null;
      if (Date.now() >= deadline) return null;
      await connectionBack(Math.min(30_000, 1000 * 2 ** attempt, Math.max(0, deadline - Date.now())), signal);
    }
  }
}

/**
 * Turns the landed pieces into a file. Finishing twice gives back the same
 * file, so a reply lost to a dropped connection or a server hiccup is asked for
 * again rather than costing the whole upload; a refusal is final.
 */
async function finishUpload(id: string): Promise<{ blob_id: string; size: number }> {
  for (let tries = 1; ; tries++) {
    try {
      return await uploadsApi.finish(id);
    } catch (err) {
      const refused = err instanceof ApiError && err.status > 0 && err.status < 500;
      if (refused || tries >= CHUNK_TRIES) throw err;
      await new Promise((r) => setTimeout(r, 400 * tries));
    }
  }
}

/**
 * Sends a file too big for one request, a piece at a time. Each piece is a
 * whole number of chunks, so it can be encrypted on its own when its turn
 * comes, and again identically if it has to be sent twice.
 *
 * The server says how much has landed, so a dropped connection costs at most
 * that one piece rather than the whole transfer. Anything already spooled is
 * thrown away if the upload is abandoned.
 */
export async function uploadInPieces(file: Blob, key: Uint8Array, baseIv: Uint8Array, onProgress: (sent: number) => void, send: PieceSender = sendInWorker, signal?: AbortSignal, channelId?: string): Promise<{ blob_id: string; size: number }> {
  const total = encryptedSize(file.size);
  const chunks = chunkCount(file.size);
  // the channel lets a boosted server take a bigger file
  const session = await uploadsApi.create(total, channelId);
  const limit = Math.min(session.chunk_size || limits.chunk, limits.chunk);
  const perPiece = Math.max(1, Math.floor((limit - HEADER_LEN) / FRAME_LEN));
  let sent = 0;
  /** When the upload last got further; it gives up after STALL_MS without. */
  let movedAt = Date.now();
  // Only one piece ahead: encryption and disk reads run during the current
  // request instead of leaving the connection idle at every piece boundary.
  const prepare = (first: number): Promise<Uint8Array<ArrayBuffer>> => {
    if (signal?.aborted) return Promise.reject(cancelled());
    const body = encryptChunks(file, key, baseIv, first, Math.min(perPiece, chunks - first));
    // It can fail while the previous request is still running. The loop will
    // observe the failure when it takes this piece, without an unhandled rejection.
    void body.catch(() => undefined);
    return body;
  };
  let prepared: Promise<Uint8Array<ArrayBuffer>> | undefined;
  // The next piece goes out as soon as the current one's bytes have left,
  // while its answer is still on the way: through the proxy and a home
  // connection that round trip left the line idle at every piece boundary,
  // so a big upload stopped and started. The server holds a piece that comes
  // early until the one before it has landed.
  /** The piece sent early, whether its bytes have all left, and what to do when they have. */
  type Ahead = { first: number; reply: Promise<unknown>; left: boolean; onLeft?: () => void };
  let ahead: Ahead | undefined;
  let shown = 0;
  const show = (n: number): void => {
    if (n > shown) {
      shown = n;
      onProgress(n);
    }
  };
  const launchNext = (next: number): void => {
    if (ahead || next >= chunks || !prepared || signal?.aborted) return;
    const count = Math.min(perPiece, chunks - next);
    const start = frameOffset(next);
    const body = prepared;
    prepared = next + count < chunks ? prepare(next + count) : undefined;
    const end = Math.min(total, frameOffset(next + count));
    const entry: Ahead = { first: next, reply: Promise.resolve(), left: false };
    entry.reply = body.then((b) =>
      send({ method: "PATCH", path: `/uploads/${session.id}`, headers: { "Upload-Offset": String(start) }, file, key, baseIv, first: next, count, skip: 0, body: b }, (loaded) => {
        show(start + loaded);
        if (start + loaded >= end && !entry.left) {
          entry.left = true;
          entry.onLeft?.();
        }
      }, signal),
    );
    // taken, or its failure handled, when its turn comes
    void entry.reply.catch(() => undefined);
    ahead = entry;
  };
  try {
    prepared = prepare(0);
    for (let first = 0; first < chunks; first += perPiece) {
      const count = Math.min(perPiece, chunks - first);
      const start = first === 0 ? 0 : frameOffset(first);
      const end = Math.min(total, frameOffset(first + count));
      if (sent !== start) throw new Error(t("attachments.theUploadLostItsPlace"));
      const early = ahead && ahead.first === first ? ahead : undefined;
      let pending = early?.reply;
      ahead = undefined;
      // now the current piece: once its bytes have left, the next one goes
      if (early) {
        if (early.left) launchNext(first + count);
        else early.onLeft = () => launchNext(first + count);
      }
      let body: Uint8Array<ArrayBuffer> | undefined;
      if (!pending) {
        body = await prepared;
        if (signal?.aborted) throw cancelled();
        prepared = first + count < chunks ? prepare(first + count) : undefined;
      }
      let tries = 0;
      while (sent < end) {
        try {
          if (signal?.aborted) throw cancelled();
          let reply: { received?: number } | undefined;
          if (pending) {
            const waiting = pending;
            pending = undefined;
            reply = (await waiting) as { received?: number } | undefined;
          } else {
            const job: PieceJob = { method: "PATCH", path: `/uploads/${session.id}`, headers: { "Upload-Offset": String(sent) }, file, key, baseIv, first, count, skip: sent - start, ...(body ? { body } : {}) };
            // A worker transfers the buffer, so a retry must regenerate it.
            body = undefined;
            const from = sent;
            reply = (await send(job, (loaded) => {
              show(from + loaded);
              if (from + loaded >= end) launchNext(first + count);
            }, signal)) as { received?: number } | undefined;
          }
          const received = reply?.received ?? -1;
          if (received <= sent || received > end) throw new Error(t("attachments.theServerLostTrackOfThis"));
          sent = received;
          movedAt = Date.now();
        } catch (err) {
          // stopped on purpose: nothing to pick up again
          if (signal?.aborted) throw err;
          // ask where it got to, waiting for the connection if it dropped, then carry on from there
          const landed = await landedSoFar(session.id, movedAt + STALL_MS, signal);
          if (!landed || landed.received < start || landed.received > end) throw err;
          if (landed.received > sent) {
            tries = 0;
            movedAt = Date.now();
          } else if (!landed.waited) {
            // the server was there all along and the piece still did not land
            tries += 1;
          }
          if (tries >= CHUNK_TRIES || Date.now() - movedAt > STALL_MS) throw err;
          sent = landed.received;
          if (sent < end) await new Promise((r) => setTimeout(r, 400 * tries));
        }
        show(sent);
      }
    }
    if (sent !== total) throw new Error(t("attachments.theUploadCameOutADifferentSize"));
    return await finishUpload(session.id);
  } catch (err) {
    await uploadsApi.cancel(session.id).catch(() => undefined);
    throw err;
  }
}
