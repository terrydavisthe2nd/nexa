/**
 * The typed HTTP client.
 *
 * Everything goes through `api.request`, which knows three things the rest of
 * the app should not have to: where the API lives, how the bearer token is
 * attached, and what the server's error envelope looks like. `fetchImpl` and
 * `XHRImpl` are injectable so tests never touch the network.
 */

import { t } from "@/i18n";

/** The base path every API route hangs off. */
const API_PREFIX = "/api/v1";

/**
 * How long a request waits for an answer. A proxy that accepts the connection
 * and never replies — a half-open socket after a network change — otherwise
 * leaves the promise pending forever, and with it every button that set `busy`
 * in a `try`/`finally`. The gateway has its heartbeat; HTTP had nothing.
 */
const REQUEST_TIMEOUT_MS = 30_000;

/**
 * How long an upload may go without sending a byte or hearing back: the same
 * minute the upload worker allows (see lib/upload.worker.ts). Uploads have no
 * overall deadline, since a large file on a slow line takes as long as it takes.
 */
export const UPLOAD_STALL_MS = 60_000;

/** An error the server reported, or a transport failure dressed up as one. */
export class ApiError extends Error {
  status: number;
  code: string;
  retryAfter?: number;
  /** What the server said beside the error itself: the version a note is at, the files it would not take. */
  details?: Record<string, unknown>;

  constructor(status: number, code: string, message: string, retryAfter?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

/**
 * The headers a captcha token goes with. Tokens are Turnstile's, and saying so
 * keeps the server from checking them with hCaptcha, which it keeps only for
 * apps from before the move back.
 */
export const captchaHeaders = (captcha: string): Record<string, string> => ({ "X-Captcha-Token": captcha, "X-Captcha-Provider": "turnstile" });

/** Options carrying a captcha token for a guarded request, when there is one. */
export function captchaOptions(captcha: string | null | undefined): RequestOptions | undefined {
  return captcha ? { headers: captchaHeaders(captcha) } : undefined;
}

export interface RequestOptions {
  /** Send this body as-is instead of JSON-encoding `body`. */
  raw?: BodyInit;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** Appended as a query string; `undefined` values are dropped. */
  query?: Record<string, string | number | undefined>;
  /** Let the request outlive the page (a save sent as it unloads). Browsers take at most 64 KB this way. */
  keepalive?: boolean;
}

export interface UploadOptions {
  headers?: Record<string, string>;
  onProgress?: (loaded: number, total: number) => void;
  signal?: AbortSignal;
  /** Defaults to POST; avatars, banners and icons are PUT. */
  method?: string;
}

/** The server's error envelope: `{ "error": { "code": ..., "message": ... } }`. */
interface ErrorEnvelope {
  error: { code: string; message: string };
}

function isErrorEnvelope(x: unknown): x is ErrorEnvelope {
  if (typeof x !== "object" || x === null) return false;
  const e = (x as { error?: unknown }).error;
  if (typeof e !== "object" || e === null) return false;
  const { code, message } = e as { code?: unknown; message?: unknown };
  return typeof code === "string" && typeof message === "string";
}

/** Retry-After is sent in seconds; anything else is ignored. */
function parseRetryAfter(raw: string | null): number | undefined {
  if (!raw) return undefined;
  const n = Number(raw.trim());
  return Number.isFinite(n) && n >= 0 ? n : undefined;
}

/**
 * What an error with no envelope behind it says.
 *
 * Thirteen call sites show `err.message` when they have nothing better, so this
 * string is read by people, not only by logs. "Request failed with status 500."
 * is a status line, not a sentence, and it is untranslated in an app that ships
 * eleven catalogues; these say the same thing in words someone wrote.
 */
export function genericMessage(status: number): string {
  if (status === 429) return t("errors.youAreDoingThatTooQuickly");
  if (status >= 500) return t("errors.theServerRanIntoAProblem");
  if (status >= 400) return t("errors.theServerTurnedThatDownAnd");
  return t("modals.confirm.somethingWentWrong");
}

/** Builds an ApiError from a response body, falling back to `http_<status>`. */
export function errorFrom(status: number, body: string, retryAfterHeader: string | null): ApiError {
  const retryAfter = parseRetryAfter(retryAfterHeader);
  let parsed: unknown;
  try {
    parsed = JSON.parse(body) as unknown;
  } catch {
    parsed = undefined;
  }
  if (isErrorEnvelope(parsed)) {
    const err = new ApiError(status, parsed.error.code, parsed.error.message, retryAfter);
    const { error: _error, ...rest } = parsed as unknown as Record<string, unknown>;
    if (Object.keys(rest).length > 0) err.details = rest;
    return err;
  }
  return new ApiError(status, `http_${status}`, genericMessage(status), retryAfter);
}

/** Builds an ApiError from a fetch Response that was not ok. */
export async function errorFromResponse(res: Response): Promise<ApiError> {
  let text = "";
  try {
    text = await res.text();
  } catch {
    text = "";
  }
  return errorFrom(res.status, text, res.headers.get("Retry-After"));
}

function hasHeader(headers: Record<string, string>, name: string): boolean {
  const want = name.toLowerCase();
  return Object.keys(headers).some((k) => k.toLowerCase() === want);
}

/** A caller's signal and a deadline as one signal, with a way to take both down again. */
function deadline(signal: AbortSignal | undefined, ms: number) {
  const ctrl = new AbortController();
  let expired = false;
  const timer = setTimeout(() => {
    expired = true;
    ctrl.abort();
  }, ms);
  const relay = (): void => ctrl.abort();
  if (signal) {
    if (signal.aborted) ctrl.abort();
    else signal.addEventListener("abort", relay, { once: true });
  }
  return {
    signal: ctrl.signal,
    expired: () => expired,
    done: (): void => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", relay);
    },
  };
}

let token: string | null = null;

const defaultFetch: typeof fetch =
  typeof globalThis.fetch === "function"
    ? globalThis.fetch.bind(globalThis)
    : (() => {
        throw new ApiError(0, "no_fetch", "This environment has no fetch().");
      }) as unknown as typeof fetch;

export const api = {
  /** Origin the API is served from; empty means same origin. */
  baseUrl: "",

  /** Called once for every 401 that came back from an authenticated request. */
  onUnauthorized: null as (() => void) | null,

  /**
   * Solves a captcha for the action the server named, or answers null when
   * captchas are off (lib/captcha sets it). A request sent without a token
   * that the server turns down for want of one is sent again once with one:
   * a new username, a first message to a stranger and the rest are guarded
   * wherever in the app they are asked for.
   */
  captchaSolver: null as ((action: string) => Promise<string | null>) | null,

  fetchImpl: defaultFetch,

  XHRImpl: (typeof XMLHttpRequest !== "undefined" ? XMLHttpRequest : null) as
    | typeof XMLHttpRequest
    | null,

  setToken(t: string | null): void {
    token = t;
  },

  getToken(): string | null {
    return token;
  },

  /** Absolute URL for a path; `/media/...` is used verbatim, the rest is versioned. */
  url(path: string, query?: Record<string, string | number | undefined>): string {
    const base = this.baseUrl + (path.startsWith("/media/") ? path : API_PREFIX + path);
    if (!query) return base;
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined) params.set(k, String(v));
    }
    const qs = params.toString();
    return qs ? `${base}?${qs}` : base;
  },

  async request<T>(method: string, path: string, body?: unknown, opts?: RequestOptions): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json", ...opts?.headers };
    const auth = token;
    if (auth) headers["Authorization"] = `Bearer ${auth}`;

    let payload: BodyInit | undefined;
    if (opts?.raw !== undefined) {
      payload = opts.raw;
    } else if (body !== undefined) {
      payload = JSON.stringify(body);
      if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/json";
    }

    const init: RequestInit = { method, headers };
    if (payload !== undefined) init.body = payload;
    if (opts?.keepalive) init.keepalive = true;
    const limit = deadline(opts?.signal, REQUEST_TIMEOUT_MS);
    init.signal = limit.signal;

    let res: Response;
    let raw: string;
    try {
      res = await this.fetchImpl(this.url(path, opts?.query), init);
      raw = res.status === 204 || res.status === 205 ? "" : await res.text();
    } catch (err) {
      // fetch rejects with a bare TypeError for anything below HTTP, and the
      // callers that branch on ApiError fall through to showing the browser's
      // own untranslated "Failed to fetch".
      if (limit.expired()) throw new ApiError(0, "timeout", t("offline.couldNotReachTheServer"));
      if (err instanceof ApiError) throw err;
      if (err instanceof DOMException && err.name === "AbortError") throw err;
      throw new ApiError(0, "network", t("offline.couldNotReachTheServer"));
    } finally {
      limit.done();
    }

    if (!res.ok) {
      const err = errorFrom(res.status, raw, res.headers.get("Retry-After"));
      // Only the token being refused. Changing a password, adding a second
      // factor and deleting an account all answer 401 for a password typed
      // wrong, and signing out over a typo would be worse than the bug.
      if (res.status === 401 && auth && err.code === "unauthorized") this.onUnauthorized?.();
      const action = err.code === "captcha_required" && !hasHeader(headers, "x-captcha-token") ? res.headers.get("X-Captcha-Action") : null;
      if (action && this.captchaSolver) {
        const solved = await this.captchaSolver(action);
        if (solved) return this.request<T>(method, path, body, { ...opts, headers: { ...opts?.headers, ...captchaHeaders(solved) } });
      }
      throw err;
    }
    if (!raw) return undefined as T;
    try {
      return JSON.parse(raw) as T;
    } catch {
      // a 200 that is not ours — a captive portal's sign-in page, a proxy's
      // error page — is said as that, in the reader's language, not as a bare
      // SyntaxError that thirteen call sites would show as it stands
      throw new ApiError(res.status, "bad_response", t("attachments.theServerSAnswerCouldNot"));
    }
  },

  /**
   * Uploads bytes with progress. fetch() cannot report upload progress, so this
   * is the one place that still uses XMLHttpRequest.
   */
  upload<T>(path: string, body: Blob | ArrayBuffer | Uint8Array, opts?: UploadOptions): Promise<T> {
    const XHR = this.XHRImpl;
    const onUnauthorized = (): void => this.onUnauthorized?.();
    if (!XHR) {
      return Promise.reject(
        new ApiError(0, "no_xhr", "Uploads need a browser environment with XMLHttpRequest."),
      );
    }
    const url = this.url(path);
    const auth = token;
    const headers = opts?.headers ?? {};
    const signal = opts?.signal;
    // An XMLHttpRequest aborted before send() fires no event at all, so a
    // signal that was already down when this was called left the promise
    // pending for good — and with it the upload's hold on reloading the app.
    if (signal?.aborted) return Promise.reject(new ApiError(0, "aborted", t("errors.thatWasCancelledBeforeItFinished")));

    return new Promise<T>((resolve, reject) => {
      const xhr = new XHR();
      let settled = false;
      // A signal shared across uploads would otherwise keep one listener, and
      // through it one finished XMLHttpRequest, per upload it ever saw.
      let offAbort: (() => void) | null = null;
      let stall: ReturnType<typeof setTimeout> | undefined;
      let stalled = false;
      const finish = (fn: () => void): void => {
        if (settled) return;
        settled = true;
        clearTimeout(stall);
        offAbort?.();
        offAbort = null;
        fn();
      };
      // A connection that dies quietly (a phone between networks) never errors
      // on its own; the upload worker gives up after a minute of silence, and
      // an upload sent from the page — an avatar, an emoji, a file where no
      // worker can start — did not give up at all.
      const watch = (): void => {
        clearTimeout(stall);
        stall = setTimeout(() => {
          stalled = true;
          xhr.abort();
        }, UPLOAD_STALL_MS);
      };

      xhr.open(opts?.method ?? "POST", url);
      for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v);
      if (!hasHeader(headers, "content-type")) {
        xhr.setRequestHeader("Content-Type", "application/octet-stream");
      }
      if (auth) xhr.setRequestHeader("Authorization", `Bearer ${auth}`);

      const onProgress = opts?.onProgress;
      xhr.upload.onprogress = (ev: ProgressEvent): void => {
        watch();
        onProgress?.(ev.loaded, ev.total);
      };
      // all sent: the answer gets the same time to arrive
      xhr.upload.onload = watch;

      xhr.onload = (): void =>
        finish(() => {
          const text = typeof xhr.responseText === "string" ? xhr.responseText : "";
          if (xhr.status >= 200 && xhr.status < 300) {
            if (!text) {
              resolve(undefined as T);
              return;
            }
            // A parse that throws here throws inside an event handler, where
            // nothing rejects the promise and `settled` stops anything later
            // from settling it: a captive portal answering 200 with HTML would
            // leave the upload spinning for the life of the page.
            try {
              resolve(JSON.parse(text) as T);
            } catch {
              reject(new ApiError(xhr.status, "bad_response", t("attachments.theServerSAnswerCouldNot")));
            }
            return;
          }
          const err = errorFrom(xhr.status, text, xhr.getResponseHeader("Retry-After"));
          if (xhr.status === 401 && auth && err.code === "unauthorized") onUnauthorized();
          reject(err);
        });

      xhr.onerror = (): void =>
        finish(() => reject(new ApiError(0, "network", t("offline.couldNotReachTheServer"))));

      xhr.onabort = (): void =>
        finish(() =>
          reject(stalled ? new ApiError(0, "timeout", t("offline.couldNotReachTheServer")) : new ApiError(0, "aborted", t("errors.thatWasCancelledBeforeItFinished"))),
        );

      if (signal) {
        const onAbort = (): void => xhr.abort();
        signal.addEventListener("abort", onAbort, { once: true });
        offAbort = () => signal.removeEventListener("abort", onAbort);
      }

      watch();
      // bytes go as a Blob: Chromium is many times slower sending them as they are (see upload.worker.ts)
      xhr.send(body instanceof Blob ? body : new Blob([body as BlobPart]));
    });
  },
};
