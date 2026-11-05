// Bundles the main process and the preload script into dist-app/ with esbuild.
// Everything the app needs at runtime is inside these two files, so the
// installer carries no node_modules.
import { build } from "esbuild";
import { copyFile as cp } from "node:fs/promises";

const production = process.env.NODE_ENV !== "development";
const common = {
  bundle: true,
  platform: "node",
  target: "node24",
  format: "cjs",
  sourcemap: production ? false : "inline",
  minify: production,
  legalComments: "none",
  // koffi's per-platform packages hold native modules; the Windows one ships in resources/koffi instead
  external: ["electron", "@koromix/*"],
  logLevel: "warning",
  // empty for a release; tools/desktop_dev_build.py sets it for a test site's clients
  define: { __NEXA_BUILD_URL__: JSON.stringify(process.env.NEXA_BUILD_URL ?? "") },
};
await Promise.all([
  build({ ...common, entryPoints: ["src/main/index.ts"], outfile: "dist-app/main.js" }),
  // Windows' process and window calls, on a thread of their own (main/games/windows.ts)
  build({ ...common, entryPoints: ["src/main/games/windowsWorker.ts"], outfile: "dist-app/gamesWorker.js" }),
  // a sandboxed preload may only require electron itself, so it is bundled whole
  build({ ...common, entryPoints: ["src/preload/index.ts"], outfile: "dist-app/preload.js" }),
  // The screen-share picker: its own window, its own preload, and a page that
  // the site's renderer cannot script. Its script runs in a browser, not in
  // Node, so it is bundled for one.
  build({ ...common, entryPoints: ["src/picker/preload.ts"], outfile: "dist-app/pickerPreload.js" }),
  build({ ...common, platform: "browser", target: "chrome130", format: "iife", external: [], entryPoints: ["src/picker/ui.ts"], outfile: "dist-app/picker-ui.js" }),
]);
await cp("src/picker/picker.html", "dist-app/picker.html");
console.log("built dist-app/main.js, dist-app/preload.js and the screen-share picker");
