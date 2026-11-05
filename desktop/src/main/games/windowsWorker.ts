/**
 * The thread Windows' process and window calls run on, so that one that never
 * returns stalls game detection and nothing else (windows.ts). It is loaded from
 * text, so it runs from inside the app's archive like the rest of the app.
 */
import { parentPort, workerData } from "node:worker_threads";

interface Request {
  id: number;
  method: "snapshot" | "windows" | "foreground" | "gpuBusy" | "product";
  arg?: string;
}

const port = parentPort;
if (port) {
  // koffi looks beside the app for its native module, which a worker has to be told
  (process as unknown as { resourcesPath?: string }).resourcesPath = (workerData as { resourcesPath?: string }).resourcesPath;
  // required here, after that: koffi loads its native module the moment it is required
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { loadWindowsApi } = require("./windowsApi") as typeof import("./windowsApi");
  const api = loadWindowsApi((msg, extra) => port.postMessage({ log: msg, extra }));
  port.on("message", ({ id, method, arg }: Request) => {
    try {
      const value = !api ? null : method === "product" ? api.product(arg ?? "") : api[method]();
      port.postMessage({ id, value });
    } catch (err) {
      port.postMessage({ id, value: null, error: String(err) });
    }
  });
}
