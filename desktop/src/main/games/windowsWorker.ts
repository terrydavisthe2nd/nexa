/**
 * The thread Windows' process and window calls run on, so that one that never
 * returns stalls game detection and nothing else (windows.ts). It is loaded from
 * text, so it runs from inside the app's archive like the rest of the app.
 */
import path from "node:path";
import { parentPort, workerData } from "node:worker_threads";

interface Request {
  id: number;
  method: "snapshot" | "windows" | "gameWindows" | "foreground" | "gpuBusy" | "product";
  arg?: string;
}

const port = parentPort;
if (port) {
  // koffi looks beside the app for its native module. Electron gives a worker the path itself, read only, and
  // assigning to it throws, which took all of detection down in a packaged app; plain Node has to be told.
  const own = process as unknown as { resourcesPath?: string };
  if (!own.resourcesPath) own.resourcesPath = (workerData as { resourcesPath?: string }).resourcesPath;
  // Started from text, this "file" is called "[worker eval]", and koffi cannot make a require from that. Node reads
  // these names as globals in a worker started this way, so it gets the bundle's real place on disk.
  const named = globalThis as unknown as { __filename: string; __dirname: string };
  named.__filename = (workerData as { file: string }).file;
  named.__dirname = path.dirname(named.__filename);
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
