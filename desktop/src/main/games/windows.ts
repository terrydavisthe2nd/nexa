/**
 * Asks the worker thread what is running on Windows (windowsWorker.ts), giving
 * up on any answer that does not come. A call that hung once is killed with its
 * thread; one that hangs again is not asked again this run, so it cannot keep
 * taking the rest of detection down with it.
 */
import fs from "node:fs";
import path from "node:path";
import { Worker } from "node:worker_threads";
import type { Proc } from "./match";
import type { Foreground, ProcessLister } from "./processes";

type Log = (msg: string, extra?: Record<string, unknown>) => void;
type Method = "snapshot" | "windows" | "foreground" | "gpuBusy" | "product";

const ANSWER_MS = 5000;
const STRIKES = 2;

interface Waiting {
  resolve(value: unknown): void;
  fallback: unknown;
  method: Method;
  timer: ReturnType<typeof setTimeout>;
}

export class WindowsLister implements ProcessLister {
  private worker: Worker | null = null;
  private seq = 0;
  private readonly waiting = new Map<number, Waiting>();
  private readonly strikes = new Map<Method, number>();
  /** A thread is stuck inside a system call: nothing can end it, and it would hold up the app quitting. */
  stuck = false;

  constructor(
    private readonly log: Log,
    private readonly file = path.join(__dirname, "gamesWorker.js"),
    private readonly answerMs = ANSWER_MS,
  ) {}

  private start(): Worker | null {
    if (this.worker) return this.worker;
    try {
      // from text: a worker cannot be started from a file inside the app's archive
      const worker = new Worker(fs.readFileSync(this.file, "utf8"), { eval: true, workerData: { resourcesPath: (process as unknown as { resourcesPath?: string }).resourcesPath } });
      worker.on("message", (m: { id?: number; value?: unknown; error?: string; log?: string; extra?: Record<string, unknown> }) => {
        if (m.log) this.log(m.log, m.extra);
        if (m.error) this.log("game detection: a call failed", { error: m.error });
        const w = m.id === undefined ? undefined : this.waiting.get(m.id);
        if (!w || m.id === undefined) return;
        this.waiting.delete(m.id);
        clearTimeout(w.timer);
        this.strikes.delete(w.method);
        w.resolve(m.value ?? w.fallback);
      });
      const gone = () => {
        if (this.worker === worker) this.worker = null;
      };
      worker.on("error", (err) => {
        this.log("game detection: worker failed", { err: String(err) });
        gone();
      });
      worker.on("exit", gone);
      worker.unref();
      this.worker = worker;
      return worker;
    } catch (err) {
      this.log("game detection: no worker", { err: String(err) });
      return null;
    }
  }

  private call<T>(method: Method, fallback: T, arg?: string): Promise<T> {
    if ((this.strikes.get(method) ?? 0) >= STRIKES) return Promise.resolve(fallback);
    const worker = this.start();
    if (!worker) return Promise.resolve(fallback);
    const id = ++this.seq;
    return new Promise<T>((resolve) => {
      const timer = setTimeout(() => {
        this.log("game detection: a call did not answer", { method });
        this.strikes.set(method, (this.strikes.get(method) ?? 0) + 1);
        // everything else waiting sat behind the same stuck thread
        for (const [key, w] of this.waiting) {
          clearTimeout(w.timer);
          w.resolve(w.fallback);
          this.waiting.delete(key);
        }
        if (this.worker === worker) this.worker = null;
        this.stuck = true;
        void worker.terminate();
      }, this.answerMs);
      this.waiting.set(id, { resolve: resolve as (v: unknown) => void, fallback, method, timer });
      worker.postMessage({ id, method, ...(arg !== undefined ? { arg } : {}) });
    });
  }

  list(): Promise<Proc[]> {
    return this.call<Proc[]>("snapshot", []);
  }

  windows(): Promise<Map<number, string>> {
    return this.call("windows", new Map<number, string>());
  }

  foreground(): Promise<Foreground | null> {
    return this.call<Foreground | null>("foreground", null);
  }

  gpuBusy(): Promise<Set<number>> {
    return this.call("gpuBusy", new Set<number>());
  }

  product(file: string): Promise<string | null> {
    return this.call<string | null>("product", null, file);
  }

  stop(): void {
    void this.worker?.terminate();
    this.worker = null;
  }
}
