/**
 * Puts `window.evilionDesktop` on Nexa's pages, and on nothing else.
 * Only named calls cross over; the page never gets ipcRenderer itself.
 * web/src/lib/desktopBridge.ts describes this object to the page.
 */
import { contextBridge, ipcRenderer, type IpcRendererEvent } from "electron";
import { IPC, type DesktopSettings, type DisplaySourceRequest, type GameShare, type GamesState, type Identity, type NativeStrings, type RunningApp, type RpcActivity, type RpcState, type UpdateState, type WindowState } from "../shared/bridge";

const arg = (name: string): string => process.argv.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3) ?? "";
const origin = arg("evilion-origin");
const version = arg("evilion-version");
const shareAudioMode = arg("evilion-share-audio");
const shareAudio = shareAudioMode === "own" || shareAudioMode === "all";
const windowAudio = arg("evilion-window-audio") === "1";

function listen<T>(channel: string) {
  return (cb: (payload: T) => void): (() => void) => {
    const handler = (_e: IpcRendererEvent, payload: T) => cb(payload);
    ipcRenderer.on(channel, handler);
    return () => ipcRenderer.removeListener(channel, handler);
  };
}

if (origin && location.origin === origin) {
  contextBridge.exposeInMainWorld("evilionDesktop", {
    openOriginal: (bytes: Uint8Array, type: string, name: string): Promise<void> => ipcRenderer.invoke(IPC.openOriginal, bytes, type, name),
    version,
    platform: process.platform,
    shareAudio,
    shareAudioMode,
    windowAudio,
    rpc: {
      getState: (): Promise<RpcState> => ipcRenderer.invoke(IPC.rpcGetState),
      setEnabled: (on: boolean): Promise<void> => ipcRenderer.invoke(IPC.rpcSetEnabled, !!on),
      setRelay: (on: boolean): Promise<void> => ipcRenderer.invoke(IPC.rpcSetRelay, !!on),
      setHidden: (clientId: string, hidden: boolean): Promise<void> => ipcRenderer.invoke(IPC.rpcSetHidden, String(clientId), !!hidden),
      restartDiscord: (): Promise<boolean> => ipcRenderer.invoke(IPC.rpcRestartDiscord),
      onState: listen<RpcState>(IPC.rpcState),
    },
    onActivities: listen<RpcActivity[]>(IPC.activities),
    getActivities: (): Promise<RpcActivity[]> => ipcRenderer.invoke(IPC.activitiesGet),
    games: {
      getState: (): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesGetState),
      onState: listen<GamesState>(IPC.gamesState),
      setEnabled: (on: boolean): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesSetEnabled, !!on),
      setHidden: (key: string, name: string, hidden: boolean): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesSetHidden, String(key), String(name), !!hidden),
      runningApps: (): Promise<RunningApp[]> => ipcRenderer.invoke(IPC.gamesRunningApps),
      add: (appId: string, name: string, gameId: string | null): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesAdd, String(appId), String(name), gameId === null ? null : String(gameId)),
      update: (id: string, name: string, gameId: string | null): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesUpdate, String(id), String(name), gameId === null ? null : String(gameId)),
      remove: (id: string): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesRemove, String(id)),
      answer: (id: string, isGame: boolean): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesAnswer, String(id), !!isGame),
      shareInfo: (id: string): Promise<GameShare | null> => ipcRenderer.invoke(IPC.gamesShareInfo, String(id)),
      markShared: (id: string): Promise<GamesState> => ipcRenderer.invoke(IPC.gamesMarkShared, String(id)),
    },
    setIdentity: (user: Identity | null): void => ipcRenderer.send(IPC.setIdentity, user ? { id: String(user.id), username: String(user.username), global_name: String(user.global_name), avatar: user.avatar ? String(user.avatar) : null } : null),
    updates: {
      getState: (): Promise<UpdateState> => ipcRenderer.invoke(IPC.updateGetState),
      check: (): Promise<void> => ipcRenderer.invoke(IPC.updateCheck),
      restart: (): Promise<void> => ipcRenderer.invoke(IPC.updateRestart),
      onState: listen<UpdateState>(IPC.updateState),
    },
    settings: {
      get: (): Promise<DesktopSettings> => ipcRenderer.invoke(IPC.settingsGet),
      set: (patch: Partial<DesktopSettings>): Promise<DesktopSettings> => ipcRenderer.invoke(IPC.settingsSet, patch),
    },
    setStrings: (strings: NativeStrings): void => ipcRenderer.send(IPC.setStrings, strings),
    setBadge: (count: number): void => ipcRenderer.send(IPC.setBadge, Number(count) || 0),
    setCallActive: (active: boolean): void => ipcRenderer.send(IPC.setCallActive, !!active),
    onDisplaySources: listen<DisplaySourceRequest>(IPC.displaySources),
    chooseDisplaySource: (requestId: string, sourceId: string | null, withAudio: boolean): void => ipcRenderer.send(IPC.displayChoose, String(requestId), sourceId === null ? null : String(sourceId), !!withAudio),
    onTrayAction: listen<"mute" | "deafen">(IPC.trayAction),
    focusWindow: (): void => ipcRenderer.send(IPC.focusWindow),
    requestAttention: (): void => ipcRenderer.send(IPC.requestAttention),
    getWindowState: (): Promise<WindowState> => ipcRenderer.invoke(IPC.windowGetState),
    shareAudioFailed: (): Promise<boolean> => ipcRenderer.invoke(IPC.shareAudioFailed),
    screenAccess: (): Promise<{ status: string; usable: boolean; stale: boolean }> => ipcRenderer.invoke(IPC.screenAccess),
    openScreenSettings: (): Promise<void> => ipcRenderer.invoke(IPC.openScreenSettings),
    resetScreenAccess: (): Promise<boolean> => ipcRenderer.invoke(IPC.resetScreenAccess),
    restartApp: (): Promise<void> => ipcRenderer.invoke(IPC.restartApp),
    onWindowState: listen<WindowState>(IPC.windowState),
    // the page's own right-click menu pastes through the app, which alone may read the clipboard (main/pasteGate.ts)
    paste: (): Promise<boolean> => ipcRenderer.invoke(IPC.paste),
    // keybinds that answer with another window in front (main/shortcuts.ts)
    setShortcuts: (list: { action: string; accelerator: string }[]): Promise<{ action: string; ok: boolean }[]> => ipcRenderer.invoke(IPC.setShortcuts, Array.isArray(list) ? list.map((x) => ({ action: String(x?.action), accelerator: String(x?.accelerator) })) : []),
    onShortcut: listen<string>(IPC.shortcut),
  });
}
