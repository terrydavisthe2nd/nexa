/**
 * Send performance reports: how long things take on this device, for the
 * server's profiler (internal/perf) to find what makes Nexa slow for people.
 *
 * Everything is summed here into histograms with the profiler's own bucket
 * edges, and sent every few minutes and when the page is hidden. A report says
 * which kind of device it came from (its tier from cores and memory, and its
 * connection), and the API routes it timed with every id taken out; it never
 * carries message content, names, ids or anything typed. The server keeps none
 * of it per account. With the synced `perfReports` setting off nothing is
 * measured, and anything gathered is dropped. Android does the same in
 * fish.nexa.data.PerfReports.
 */
import { useEffect, useRef } from "react";
import { api } from "@/api/client";
import { isDemo } from "@/lib/demoMode";
import { useAuth } from "@/stores/auth";
import { useSettings } from "@/stores/settings";

/** The profiler's bucket edges in milliseconds; must equal `bounds` in internal/perf/perf.go. */
export const BOUNDS = [0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 30000, 60000];

/** What the server keeps; anything else it drops. */
export type Stage = "startup" | "open_conversation" | "decrypt" | "send" | "gateway_connect" | "gateway_rtt" | "long_task" | "interaction";

const SEND_EVERY_MS = 5 * 60_000;
/** A report stays under what the server reads, and under keepalive's 64 KB. */
const MAX_ROUTES = 120;
const MAX_MS = 10 * 60_000;

class Hist {
  h: number[] = [];
  n = 0;
  errs = 0;
  sum = 0;
  max = 0;
  add(ms: number, failed: boolean): void {
    ms = Math.min(Math.max(ms, 0), MAX_MS);
    let i = 0;
    while (i < BOUNDS.length && ms > BOUNDS[i]!) i++;
    while (this.h.length <= i) this.h.push(0);
    this.h[i]!++;
    this.n++;
    this.sum += ms;
    if (ms > this.max) this.max = ms;
    if (failed) this.errs++;
  }
  wire(): { e: number; sum: number; max: number; h: number[] } {
    return { e: this.errs, sum: Math.round(this.sum * 100) / 100, max: Math.round(this.max * 100) / 100, h: this.h };
  }
}

let stages = new Map<Stage, Hist>();
let routes = new Map<string, Hist>();
let started = false;
/** When the first ready of this page arrived, in performance.now() time. */
let firstReady: number | null = null;

/** Whether this device measures at all: signed in, outside the demo, and the setting on. */
export function perfOn(): boolean {
  return !isDemo && !!useAuth.getState().me && useSettings.getState().settings.perfReports !== false;
}

/** Records how long a stage took. */
export function stage(name: Stage, ms: number, failed = false): void {
  if (!started || !perfOn() || !Number.isFinite(ms)) return;
  let h = stages.get(name);
  if (!h) stages.set(name, (h = new Hist()));
  h.add(ms, failed);
}

/**
 * The route a request went to, with every id taken out: numbers become {id},
 * and the query string goes. What else a path holds (an invite code, say) the
 * server matches to its route and forgets.
 */
export function routeOf(path: string): string {
  const bare = path.split(/[?#]/)[0]!;
  return "/api/v1" + bare.replace(/\/\d+(?=\/|$)/g, "/{id}");
}

/** Records an API request's whole round trip, as api.request saw it. */
function request(method: string, path: string, ms: number, failed: boolean): void {
  if (!started || path === "/perf" || path.startsWith("/media/") || !perfOn()) return;
  const key = method + " " + routeOf(path);
  let h = routes.get(key);
  if (!h) {
    if (routes.size >= MAX_ROUTES) return;
    routes.set(key, (h = new Hist()));
  }
  h.add(ms, failed);
}

/** Marks a gateway ready: the first of the page also ends startup, when the page opened signed in. */
export function sawReady(): void {
  if (firstReady !== null) return;
  firstReady = performance.now();
  if (signedInAtStart) stage("startup", firstReady);
}

/** Whether ready has come at least once, so a conversation opened before it is counted as startup instead. */
export function pastStartup(): boolean {
  return firstReady !== null;
}

/**
 * Times opening a conversation: from the render that first shows it until the
 * browser has painted its messages. Openings before the first ready belong to
 * startup, and ones in a hidden page are not waits anyone saw.
 */
export function useOpenTiming(id: string, loaded: boolean): void {
  const open = useRef<{ id: string; at: number; done: boolean } | null>(null);
  if (open.current?.id !== id) open.current = { id, at: performance.now(), done: !pastStartup() || document.visibilityState !== "visible" };
  useEffect(() => {
    const o = open.current;
    if (!o || o.done || !loaded) return;
    o.done = true;
    requestAnimationFrame(() => {
      setTimeout(() => stage("open_conversation", performance.now() - o.at), 0);
    });
  }, [id, loaded]);
}

/** Whether the page opened with an account already signed in, so startup is not the time someone took to sign in. */
let signedInAtStart = false;

function tier(): string {
  const nav = navigator as Navigator & { deviceMemory?: number };
  const cores = nav.hardwareConcurrency || 0;
  const mem = nav.deviceMemory;
  if ((cores && cores <= 2) || (mem !== undefined && mem <= 2)) return "low";
  if (cores >= 8 && (mem === undefined || mem >= 8)) return "high";
  return cores ? "mid" : "unknown";
}

function net(): string {
  const c = (navigator as Navigator & { connection?: { type?: string; effectiveType?: string } }).connection;
  if (!c) return "unknown";
  if (c.type === "wifi" || c.type === "cellular" || c.type === "ethernet") return c.type;
  return c.effectiveType ?? "unknown";
}

/** Sends what was gathered and starts afresh; a report that fails is dropped, never kept to pile up. */
function send(keepalive: boolean): void {
  if (!perfOn()) {
    stages = new Map();
    routes = new Map();
    return;
  }
  if (!stages.size && !routes.size) return;
  const body = {
    tier: tier(),
    net: net(),
    stages: [...stages].map(([name, h]) => ({ name, ...h.wire() })),
    api: [...routes].map(([key, h]) => {
      const [method, path] = key.split(" ");
      return { method, path, ...h.wire() };
    }),
  };
  stages = new Map();
  routes = new Map();
  void api.request("POST", "/perf", body, { keepalive }).catch(() => {});
}

/** Starts measuring for this page; called once, early. */
export function startPerfReports(): void {
  if (started || typeof window === "undefined" || isDemo) return;
  started = true;
  signedInAtStart = !!useAuth.getState().token;
  // a lock screen or the sign-in page before the first ready makes startup the
  // time someone took to type, which says nothing about speed
  useAuth.subscribe((a) => {
    if (firstReady === null && (a.status === "locked" || a.status === "pinlocked" || a.status === "anonymous")) signedInAtStart = false;
  });
  api.onTiming = request;
  // the page's main thread held up for 50 ms or more, and taps and keys the
  // page was slow to answer (Chromium); other browsers support neither
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) stage("long_task", e.duration);
    }).observe({ type: "longtask", buffered: false });
  } catch {
    /* not supported */
  }
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries() as (PerformanceEventTiming & { interactionId?: number })[]) if (e.interactionId) stage("interaction", e.duration);
    }).observe({ type: "event", durationThreshold: 104, buffered: false } as PerformanceObserverInit);
  } catch {
    /* not supported */
  }
  setInterval(() => send(false), SEND_EVERY_MS);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") send(true);
  });
}
