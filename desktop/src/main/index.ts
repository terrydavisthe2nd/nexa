/**
 * Nexa for the desktop: one window on the live site, a tray icon, the
 * Rich Presence server, and updates.
 */
import { app, BrowserWindow, globalShortcut, ipcMain, nativeImage, net, powerMonitor, shell, type IpcMainEvent, type IpcMainInvokeEvent } from "electron";
import fs from "node:fs";
import path from "node:path";
import { IPC, type DesktopSettings, type Identity, type RpcActivity, type WindowState } from "../shared/bridge";
import { BASE, ORIGIN, TEST_BUILD, isAppUrl, testHook } from "./config";
import { chooseDisplaySource, handleDisplayMedia, lockPermissions, openScreenSettings, resetScreenAccess, restartApp, screenAccess, shareAudioFailed } from "./permissions";
import { RpcService } from "./rpc/service";
import { GameLists } from "./games/lists";
import { GameService, platformLister } from "./games/service";
import { Originals } from "./originals";
import { PasteGate } from "./pasteGate";
import { applyShortcuts, cleanRequests } from "./shortcuts";
import { SettingsStore } from "./settings";
import { offerMoveToApplications, retireOlderCopies } from "./succession";
import { createTray, fillTray, type TrayActions } from "./tray";
import { Updater } from "./update";
import { nativeStrings, setNativeStrings, stringsDiffer } from "./strings";
import { applicationMenu, createMainWindow, resourcePath } from "./window";

// tests run the app with a profile of their own
const testProfile = testHook("EVILION_USER_DATA");
if (testProfile) app.setPath("userData", testProfile);
const originals = new Originals((url) => shell.openExternal(url));
const settings = new SettingsStore(path.join(app.getPath("userData"), "settings.json"));
if (!settings.get("hardwareAcceleration")) app.disableHardwareAcceleration();
if (process.platform === "linux") app.commandLine.appendSwitch("enable-features", "WebRTCPipeWireCapturer");
if (process.platform === "win32") app.setAppUserModelId(TEST_BUILD ? "xyz.evilion.chat.dev" : "xyz.evilion.chat");

// a test build keeps its log in a file, since what goes wrong may only happen when opened the usual way, with no terminal
const testLog = TEST_BUILD ? path.join(app.getPath("userData"), "test-build.log") : null;
if (testLog) {
  try {
    fs.mkdirSync(path.dirname(testLog), { recursive: true });
    fs.writeFileSync(testLog, `${app.getVersion()} started ${new Date().toISOString()}\n`);
  } catch {
    /* it only goes without a log */
  }
}
const log = (msg: string, extra?: Record<string, unknown>) => {
  if (!app.isPackaged || process.env.EVILION_DEBUG) console.log(`[evilion] ${msg}`, extra ?? "");
  if (testLog) {
    try {
      fs.appendFileSync(testLog, `${new Date().toISOString().slice(11, 19)} ${msg} ${extra ? JSON.stringify(extra) : ""}\n`);
    } catch {
      /* as above */
    }
  }
};

let win: BrowserWindow | null = null;
let quitting = false;
let tray: Electron.Tray | null = null;
let trayActions: TrayActions | null = null;
let identity: Identity | null = null;
let rpc: RpcService | null = null;
let games: GameService | null = null;
let updater: Updater | null = null;
/** Whether the page is in a call, which keeps it at full speed while hidden (applyThrottling). */
let inCall = false;
const pasteGate = new PasteGate();

// an older copy still running would hold the lock the two share (macOS; succession.ts)
if (app.isPackaged) retireOlderCopies(log);
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => showWindow());
  void app.whenReady().then(start);
}

function showWindow(): void {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

/** Sends to the page while there is one: a window closing to quit has none. */
function toPage(channel: string, payload: unknown): void {
  if (win && !win.isDestroyed() && !win.webContents.isDestroyed()) win.webContents.send(channel, payload);
}

/** Only the site's own page, in this window, may use the bridge. */
function fromApp(e: IpcMainEvent | IpcMainInvokeEvent): boolean {
  return !!win && e.sender === win.webContents && isAppUrl(e.senderFrame?.url);
}

async function start(): Promise<void> {
  // the tray, the menu bar and the offline page all exist before a page has
  // loaded, so they start from what the last run was told
  setNativeStrings(settings.get("strings"));
  aboutPanel();
  lockPermissions();
  // opened at login: Windows passes the argument given below, macOS says so itself
  const launchedHidden = process.argv.includes("--hidden") || (process.platform === "darwin" && app.getLoginItemSettings().wasOpenedAtLogin === true);
  win = createMainWindow(settings, {
    preload: path.join(__dirname, "preload.js"),
    hidden: launchedHidden && settings.get("startMinimized"),
    ...(process.platform === "linux" ? { icon: resourcePath("icon.png") } : {}),
  });
  // real input the page cannot make up: the right-click menu's Paste waits for it (pasteGate.ts)
  win.webContents.on("input-event", (_e, input) => pasteGate.mouse(input.type, (input as Electron.MouseInputEvent).button));
  win.webContents.on("before-input-event", (_e, input) => pasteGate.key(input.type, input.key, input.shift));
  // A page that goes away (a reload, the offline page) lets go of the keybinds
  // it held: the next one asks again for its own, and one that cannot answer
  // them must not keep the keys from every other program.
  // The same goes for its call: a new page says so again when it joins one.
  win.webContents.on("did-start-navigation", (nav) => {
    if (!nav.isMainFrame || nav.isSameDocument) return;
    globalShortcut.unregisterAll();
    inCall = false;
  });
  // set once the new page is there: set as it starts loading, it reached only the page going away
  win.webContents.on("did-navigate", applyThrottling);
  handleDisplayMedia(() => win, log);
  applicationMenu(() => win, () => void updater?.check());
  // a copy opened from the disk image or Downloads offers to move into Applications, once
  // the page has had a moment to send its words over
  win.webContents.once("did-finish-load", () => setTimeout(() => void offerMoveToApplications(settings, win, log), 3000));

  // A page asking to keep unsaved edits (beforeunload) silently cancels a quit
  // in Electron: Quit did nothing, and Update left the app running while its
  // installer started, which then had to kill it. Quitting means quitting.
  win.webContents.on("will-prevent-unload", (e) => {
    if (quitting) e.preventDefault();
  });
  win.on("close", (e) => {
    if (quitting || !settings.get("closeToTray")) return;
    e.preventDefault();
    win?.hide();
  });
  // Windows signing out or shutting down closes the window itself; the tray must not hold that up
  win.on("session-end", () => {
    quitting = true;
  });
  win.on("closed", () => {
    win = null;
  });
  // the page decides whether to notify from this rather than document.hasFocus()
  for (const event of ["focus", "blur", "show", "hide", "minimize", "restore"] as const) win.on(event as "focus", () => toPage(IPC.windowState, windowState()));
  // a call that ended while the window was out of sight lets it sleep once it is back
  win.on("show", applyThrottling);
  win.on("restore", applyThrottling);

  trayActions = {
    show: showWindow,
    mute: () => toPage(IPC.trayAction, "mute"),
    deafen: () => toPage(IPC.trayAction, "deafen"),
    checkUpdates: () => void updater?.check(),
    quit: () => {
      quitting = true;
      app.quit();
    },
  };
  tray = createTray(trayActions);
  applyLoginItem(settings.get("launchAtLogin"));

  rpc = new RpcService({
    enabled: settings.get("rpcEnabled"),
    relay: settings.get("rpcRelay"),
    hidden: settings.get("hiddenApps"),
    leftoverMove: settings.get("movedSocket"),
    rememberMove: (m) => settings.set({ movedSocket: m }, { now: true }),
    identity: () => identity,
    log,
  });
  rpc.on("state", (state) => toPage(IPC.rpcState, state));
  rpc.on("activities", () => {
    games?.rpcChanged();
    toPage(IPC.activities, activities());
  });
  void rpc.start().catch((err) => log("rpc start failed", { err: String(err) }));

  games = new GameService({
    // the switch that shows what someone is playing covers detected games too
    enabled: settings.get("gameDetection") && settings.get("rpcEnabled"),
    custom: settings.get("customGames"),
    hidden: settings.get("hiddenGames"),
    ownPids,
    claims: () => rpc?.claims() ?? [],
    iconOf: async (file) => {
      if (!path.isAbsolute(file)) return undefined;
      const icon = await app.getFileIcon(file, { size: "normal" });
      return icon.isEmpty() ? undefined : icon.toDataURL();
    },
    save: ({ custom, hidden, dismissed }) => settings.set({ ...(custom ? { customGames: custom } : {}), ...(hidden ? { hiddenGames: hidden } : {}), ...(dismissed ? { dismissedGames: dismissed } : {}) }),
    dismissed: settings.get("dismissedGames"),
    lists: new GameLists({
      base: BASE.toString(),
      dir: path.join(app.getPath("userData"), "games"),
      get: async (url, etag) => {
        const res = await net.fetch(url, { cache: "no-store", ...(etag ? { headers: { "If-None-Match": etag } } : {}) });
        return { status: res.status, etag: res.headers.get("etag"), bytes: res.status === 200 ? Buffer.from(await res.arrayBuffer()) : Buffer.alloc(0) };
      },
      log,
    }),
    ...testBehaviour(),
    log,
  });
  games.on("state", (state) => toPage(IPC.gamesState, state));
  games.on("activities", () => toPage(IPC.activities, activities()));
  games.start();

  updater = new Updater(new URL("/desktop/", BASE).toString(), new URL("/download", BASE).toString(), quitForUpdate, log);
  updater.on("state", (s) => toPage(IPC.updateState, s));
  updater.start();

  // a laptop waking up may have missed Discord starting or quitting
  powerMonitor.on("resume", () => void rpc?.refreshDiscord().then((s) => toPage(IPC.rpcState, s)));

  registerIpc();
  log("started", { origin: ORIGIN });
}

/** What the person is doing, as the page sends it on: programs reporting themselves first, then games recognised running. */
function activities(): RpcActivity[] {
  return [...(rpc?.activities() ?? []), ...(games?.activities() ?? [])];
}

/** Tests say what is in front and drawing, which a screen with no window manager or graphics card cannot. */
function testBehaviour(): { lister?: ReturnType<typeof platformLister> } {
  const file = testHook("EVILION_TEST_GAME_BEHAVIOUR");
  if (!file) return {};
  const real = platformLister(process.platform, log);
  const said = (): { foreground?: { pid: number; title?: string; fullscreen: boolean } | null; busy?: number[]; windows?: number[] } => {
    try {
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
      return {};
    }
  };
  return { lister: { list: () => real.list(), windows: () => real.windows(), foreground: async () => said().foreground ?? null, gpuBusy: async () => new Set(said().busy ?? []), gameWindows: async () => { const w = said().windows; return w ? new Set(w) : null; } } };
}

/** This app's own processes, which must never count as a game someone is playing. */
function ownPids(): Set<number> {
  const pids = new Set<number>([process.pid]);
  try {
    for (const m of app.getAppMetrics()) pids.add(m.pid);
  } catch {
    /* only the main process then */
  }
  return pids;
}

/** The panel behind "About Nexa" on macOS, which is otherwise the bare name. */
function aboutPanel(): void {
  app.setAboutPanelOptions({
    applicationName: "Nexa",
    applicationVersion: app.getVersion(),
    version: "",
    copyright: "Copyright © 2026 Evilion",
    credits: nativeStrings().about,
    iconPath: resourcePath("icon.png"),
  });
}

function registerIpc(): void {
  const handle = <A extends unknown[], R>(channel: string, fn: (...args: A) => R) =>
    ipcMain.handle(channel, (e, ...args) => {
      if (!fromApp(e)) throw new Error("not allowed");
      return fn(...(args as A));
    });
  const on = <A extends unknown[]>(channel: string, fn: (...args: A) => void) =>
    ipcMain.on(channel, (e, ...args) => {
      if (fromApp(e)) fn(...(args as A));
    });

  handle(IPC.rpcGetState, () => rpc!.refreshDiscord());
  handle(IPC.rpcSetEnabled, async (enabled: boolean) => {
    settings.set({ rpcEnabled: !!enabled });
    await rpc!.setEnabled(!!enabled);
    games?.setEnabled(!!enabled && settings.get("gameDetection"));
  });
  handle(IPC.rpcSetRelay, (relay: boolean) => {
    settings.set({ rpcRelay: !!relay });
    rpc!.setRelay(!!relay);
  });
  handle(IPC.rpcSetHidden, (clientId: string, hidden: boolean) => {
    if (!/^\d{1,20}$/.test(String(clientId))) return;
    rpc!.setHidden(String(clientId), !!hidden);
    settings.set({ hiddenApps: rpc!.hiddenApps() });
  });
  handle(IPC.rpcRestartDiscord, () => rpc!.restartDiscord());
  handle(IPC.activitiesGet, () => activities());

  handle(IPC.gamesGetState, () => games!.state());
  handle(IPC.gamesSetEnabled, (on: boolean) => {
    settings.set({ gameDetection: !!on });
    games!.setEnabled(!!on && settings.get("rpcEnabled"));
    return games!.state();
  });
  handle(IPC.gamesSetHidden, (key: string, name: string, hidden: boolean) => {
    games!.setHidden(String(key).slice(0, 256), String(name), !!hidden);
    return games!.state();
  });
  handle(IPC.gamesRunningApps, () => games!.runningApps());
  handle(IPC.gamesAdd, (appId: string, name: string, gameId: string | null) => {
    games!.add(String(appId), String(name), gameId);
    return games!.state();
  });
  handle(IPC.gamesUpdate, (id: string, name: string, gameId: string | null) => {
    games!.update(String(id), String(name), gameId);
    return games!.state();
  });
  handle(IPC.gamesRemove, (id: string) => {
    games!.remove(String(id));
    return games!.state();
  });
  handle(IPC.gamesAnswer, (id: string, isGame: boolean) => {
    games!.answer(String(id), !!isGame);
    return games!.state();
  });
  handle(IPC.gamesShareInfo, (id: string) => games!.shareInfo(String(id)));
  handle(IPC.gamesMarkShared, (id: string) => {
    games!.markShared(String(id));
    return games!.state();
  });
  on(IPC.setIdentity, (user: Identity | null) => {
    // This is the one piece of the signed-in person that crosses outward: it
    // goes verbatim into the READY payload every local RPC program receives.
    // Nothing capped it, and a truthy non-object came through as the literal
    // id "undefined", which passed the type check.
    const text = (v: unknown, max: number): string => (typeof v === "string" ? v.slice(0, max) : "");
    identity =
      user && typeof user === "object" && typeof user.id === "string" && user.id.length > 0 && user.id.length <= 32
        ? { id: user.id, username: text(user.username, 64), global_name: text(user.global_name, 64), avatar: typeof user.avatar === "string" ? user.avatar.slice(0, 256) : null }
        : null;
  });
  on(IPC.setStrings, (strings: unknown) => {
    const before = nativeStrings();
    const after = setNativeStrings(strings);
    if (!stringsDiffer(before, after)) return;
    settings.set({ strings: after });
    if (tray && trayActions) fillTray(tray, trayActions);
    applicationMenu(() => win, () => void updater?.check());
    aboutPanel();
  });

  handle(IPC.updateGetState, () => updater!.state);
  handle(IPC.updateCheck, () => updater!.check());
  handle(IPC.updateRestart, () => updater!.restart());

  handle(IPC.settingsGet, (): DesktopSettings => desktopSettings());
  handle(IPC.settingsSet, (patch: Partial<DesktopSettings>): DesktopSettings => {
    const next: Partial<DesktopSettings> = {};
    for (const key of ["launchAtLogin", "startMinimized", "closeToTray", "hardwareAcceleration"] as const) {
      if (typeof patch?.[key] === "boolean") next[key] = patch[key];
    }
    settings.set(next, { now: true });
    if (next.launchAtLogin !== undefined || next.startMinimized !== undefined) applyLoginItem(settings.get("launchAtLogin"));
    return desktopSettings();
  });

  on(IPC.setBadge, (count: number) => setBadge(Math.max(0, Math.floor(Number(count) || 0))));
  on(IPC.setCallActive, (active: boolean) => {
    inCall = !!active;
    applyThrottling();
  });
  // a notification clicked while the window sits in the tray or minimised brings it back
  on(IPC.focusWindow, () => showWindow());
  on(IPC.requestAttention, () => requestAttention());
  handle(IPC.windowGetState, () => windowState());
  handle(IPC.shareAudioFailed, () => shareAudioFailed(log));
  handle(IPC.openOriginal, (bytes: unknown, type: unknown, name: unknown) => originals.open(bytes, type, name));
  handle(IPC.screenAccess, () => screenAccess());
  handle(IPC.openScreenSettings, () => openScreenSettings());
  handle(IPC.resetScreenAccess, () => resetScreenAccess());
  handle(IPC.restartApp, () => restartApp());
  // Paste from the page's right-click menu on text, into what the page has
  // focused, as the app's own menu does it: only straight after a right click
  // and a click, so the page never gets the clipboard on its own say-so.
  handle(IPC.paste, (): boolean => {
    if (!win || win.isDestroyed() || !pasteGate.allow()) return false;
    win.webContents.paste();
    return true;
  });
  // The keybinds held system-wide. The page sends the whole set each time
  // they change, and an empty one when nobody is signed in.
  handle(IPC.setShortcuts, (list: unknown) => applyShortcuts(globalShortcut, cleanRequests(list), (action) => toPage(IPC.shortcut, action)));
  // Dismissal only. Which screen is shared is answered by the picker window,
  // which this page cannot reach — the extra arguments the page used to send
  // are ignored rather than accepted and dropped, so that a change here cannot
  // quietly hand the decision back to it.
  on(IPC.displayChoose, (requestId: string) => chooseDisplaySource(String(requestId)));
}

/**
 * Draws the eye to a notification the way the system does, while the window
 * is not being looked at: the taskbar button flashes on Windows (and Linux
 * where the window manager supports it) until the window is focused, and the
 * Dock icon bounces once on macOS.
 */
function requestAttention(): void {
  if (!win || win.isDestroyed() || win.isFocused()) return;
  if (process.platform === "darwin") {
    app.dock?.bounce("informational");
    return;
  }
  win.flashFrame(true);
  const w = win;
  w.once("focus", () => {
    if (!w.isDestroyed()) w.flashFrame(false);
  });
}

/**
 * A call runs at full speed in a hidden window: its voice gate, share sizing
 * and stall watch run on timers that a hidden page has slowed to once a
 * second. Out of a call the page sleeps while hidden.
 *
 * Throttling turned back on while the window is out of sight leaves the page
 * awake (Electron hides a page only as its window hides), so then it waits
 * until the window is shown again.
 */
function applyThrottling(): void {
  if (!win || win.isDestroyed()) return;
  if (!inCall && (!win.isVisible() || win.isMinimized())) return;
  win.webContents.setBackgroundThrottling(!inCall);
}

function windowState(): WindowState {
  if (!win || win.isDestroyed()) return { focused: false, visible: false, minimized: false };
  return { focused: win.isFocused(), visible: win.isVisible(), minimized: win.isMinimized() };
}

function desktopSettings(): DesktopSettings {
  return {
    launchAtLogin: settings.get("launchAtLogin"),
    startMinimized: settings.get("startMinimized"),
    closeToTray: settings.get("closeToTray"),
    hardwareAcceleration: settings.get("hardwareAcceleration"),
  };
}

function applyLoginItem(on: boolean): void {
  // a development build, or one under test, must not put itself in the login items
  if (!app.isPackaged || testHook("EVILION_NO_LOGIN_ITEM")) return;
  if (process.platform === "linux") {
    linuxAutostart(on);
    return;
  }
  app.setLoginItemSettings({ openAtLogin: on, args: ["--hidden"] });
}

/** Linux desktops start what is in ~/.config/autostart when someone signs in. */
function linuxAutostart(on: boolean): void {
  const dir = path.join(process.env.XDG_CONFIG_HOME || path.join(app.getPath("home"), ".config"), "autostart");
  const file = path.join(dir, "nexa.desktop");
  try {
    if (!on) {
      fs.rmSync(file, { force: true });
      return;
    }
    // an AppImage is started through its own file, not the mount it unpacks to
    const exec = (process.env.APPIMAGE || process.execPath).replace(/(["\\`$])/g, "\\$1");
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(file, `[Desktop Entry]\nType=Application\nName=Nexa\nExec="${exec}" --hidden\nX-GNOME-Autostart-enabled=true\nTerminal=false\n`);
  } catch (err) {
    log("autostart", { err: String(err) });
  }
}

let badgeImage: Electron.NativeImage | null = null;
function setBadge(count: number): void {
  if (process.platform === "win32") {
    badgeImage ??= nativeImage.createFromPath(resourcePath("badge.png"));
    win?.setOverlayIcon(count > 0 ? badgeImage : null, count > 0 ? `${count} unread` : "");
    return;
  }
  app.setBadgeCount(count);
}

/**
 * Stops Rich Presence once, so Discord's socket goes back to its own name
 * before the process ends. It never waits: a quit held up to wait for it never
 * finished when the system was the one ending the app (a shutdown, a logout,
 * a kill), and the app stayed running.
 */
function stopRpc(): void {
  games?.stop();
  const stopping = rpc;
  rpc = null;
  try {
    stopping?.stopForExit();
  } catch (err) {
    log("rpc stop failed", { err: String(err) });
  }
}

function quitForUpdate(install: () => void): void {
  log("quitting to update");
  quitting = true;
  stopRpc();
  settings.flush();
  install();
}

app.on("activate", () => showWindow());
app.on("before-quit", () => {
  quitting = true;
});
app.on("will-quit", () => {
  originals.dispose();
  globalShortcut.unregisterAll();
  const stuck = games?.stuck;
  stopRpc();
  settings.flush();
  // everything is saved and let go; a thread stuck in a system call would keep the process from ever ending
  if (stuck) process.kill(process.pid);
});
app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
