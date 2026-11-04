/**
 * Sends one request's body, then is thrown away.
 *
 * A browser keeps a request's body until its garbage collector gets round to
 * the finished request, and nothing about a finished upload makes it hurry:
 * sent from the page, a large file's pieces pile up until memory runs out.
 * The body is handed over to a worker instead, not copied, and the worker is
 * ended as soon as its request is answered, which frees all of it at once.
 */

export interface PieceRequest {
  url: string;
  /** PUT is a part of a relayed upload (api/upload_relay.go). */
  method: "POST" | "PATCH" | "PUT";
  headers: Record<string, string>;
  /** Transferred, not copied: the page no longer holds these bytes once sent here. */
  body: ArrayBuffer;
  offset: number;
  length: number;
}

export type PieceReply =
  | { type: "progress"; loaded: number }
  | { type: "done"; status: number; text: string; retryAfter: string | null }
  | { type: "failed" };

interface WorkerScope {
  onmessage: ((e: MessageEvent<PieceRequest>) => void) | null;
  postMessage(msg: PieceReply): void;
}

const scope = self as unknown as WorkerScope;

/**
 * How long a request may go without sending a byte or hearing back. A
 * connection that dies quietly (a phone changing networks) never errors on
 * its own, and the upload would sit there instead of picking up again.
 */
const STALL_MS = 60_000;

scope.onmessage = (e) => {
  const job = e.data;
  const xhr = new XMLHttpRequest();
  let stall: ReturnType<typeof setTimeout> | undefined;
  const watch = (): void => {
    clearTimeout(stall);
    stall = setTimeout(() => xhr.abort(), STALL_MS);
  };
  xhr.open(job.method, job.url);
  for (const [k, v] of Object.entries(job.headers)) xhr.setRequestHeader(k, v);
  xhr.upload.onprogress = (ev) => {
    watch();
    scope.postMessage({ type: "progress", loaded: ev.loaded });
  };
  // all sent: the answer gets the same time to arrive
  xhr.upload.onload = watch;
  xhr.onload = () => scope.postMessage({ type: "done", status: xhr.status, text: typeof xhr.responseText === "string" ? xhr.responseText : "", retryAfter: xhr.getResponseHeader("Retry-After") });
  xhr.onerror = () => scope.postMessage({ type: "failed" });
  xhr.onabort = () => scope.postMessage({ type: "failed" });
  xhr.onloadend = () => clearTimeout(stall);
  watch();
  // As a Blob, not the bytes themselves. Chromium (and so the desktop app)
  // took most of a second over every 28 MB sent as an ArrayBuffer, long after
  // the last byte had reached the server; wrapped in a Blob the same bytes go
  // in a few milliseconds, and no browser is slower for it.
  xhr.send(new Blob([new Uint8Array(job.body, job.offset, job.length)]));
};
