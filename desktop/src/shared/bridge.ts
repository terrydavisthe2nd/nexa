/**
 * What the desktop app and the page say to each other. The page's copy of
 * these types is web/src/lib/desktopBridge.ts; keep the two in step.
 */

export interface RpcActivity {
  application_id: string;
  name?: string;
  type: 0 | 2 | 3 | 5;
  status_display_type?: 0 | 1 | 2;
  details?: string;
  details_url?: string;
  state?: string;
  state_url?: string;
  timestamps?: { start?: number; end?: number };
  assets?: { large_image?: string; large_text?: string; large_url?: string; small_image?: string; small_text?: string; small_url?: string };
  party?: { id?: string; size?: [number, number] };
  buttons?: { label: string; url: string }[];
  instance?: boolean;
  /** A game recognised running (main/games), which said nothing about itself; the server looks it up on IGDB. */
  detected?: boolean;
  store?: string;
  store_id?: string;
  game_id?: string;
}

export interface RpcApp {
  clientId: string;
  name: string | null;
  activity: RpcActivity | null;
  hidden: boolean;
  connectedAt: number;
}

export interface RpcState {
  enabled: boolean;
  relay: boolean;
  listening: { ipc: string | null; ws: number | null };
  discord: "connected" | "absent" | "first";
  apps: RpcApp[];
}

/** How a game was recognised. */
export type GameSource = "steam" | "epic" | "gog" | "xbox" | "itch" | "store" | "system" | "known" | "engine" | "custom";

/** A game running now. */
export interface DetectedGame {
  key: string;
  name: string;
  source: GameSource;
  startedAt: number;
  /** Kept off the person's profile. */
  hidden: boolean;
  /** A program reports this game through Rich Presence, and that is what shows. */
  reporting: boolean;
  activity: RpcActivity;
}

/** A game the person added from their running apps. */
export interface CustomGame {
  id: string;
  name: string;
  /** The program's file name, to tell it by. */
  exe: string;
  /** IGDB's id, when they chose the game there. */
  gameId?: string;
  /** They shared it with staff for the list everyone gets. */
  shared?: boolean;
}

/** A program that behaves like a game and has a game's name, waiting for the person to say. Nothing about it has left the computer. */
export interface GameSuggestion {
  id: string;
  name: string;
  exe: string;
}

/** What sharing a game sends: never a path. */
export interface GameShare {
  process: string;
  os: string;
  name: string;
  game_id?: string;
}

export interface HiddenGame {
  key: string;
  name: string;
}

export interface GamesState {
  enabled: boolean;
  running: DetectedGame[];
  custom: CustomGame[];
  hidden: HiddenGame[];
  suggestions: GameSuggestion[];
}

/** A program with a window, for choosing a game by hand. It carries no path: the app keeps those. */
export interface RunningApp {
  id: string;
  name: string;
  exe: string;
  /** data: URL of its icon, when there is one. */
  icon?: string;
  /** What it is already recognised as, when it is. */
  detectedAs?: string;
  added: boolean;
}

export interface UpdateState {
  status: "idle" | "checking" | "downloading" | "ready" | "manual" | "error";
  version?: string;
  progress?: number;
  downloadUrl?: string;
  error?: string;
  lastCheck?: number;
}

export interface DesktopSettings {
  launchAtLogin: boolean;
  startMinimized: boolean;
  closeToTray: boolean;
  hardwareAcceleration: boolean;
}

export interface DisplaySource {
  id: string;
  name: string;
  kind: "screen" | "window";
  thumbnail: string;
  icon?: string;
}

export interface DisplaySourceRequest {
  requestId: string;
  sources: DisplaySource[];
  audio: boolean;
  /** Set instead of sources when this computer will not let the app record the screen. */
  permission?: { status: string; stale: boolean };
}

/** Where the app's window is, as its own window system says: a page's own idea of focus is not reliable here. */
export interface WindowState {
  focused: boolean;
  visible: boolean;
  minimized: boolean;
}

export interface Identity {
  id: string;
  username: string;
  global_name: string;
  avatar: string | null;
}

/** IPC channel names between the preload script and the main process. */
/**
 * The words the operating system shows for this app: the tray menu, the macOS
 * menu bar, the right-click menu on text, the page that stands in when the
 * site cannot be reached. The page holds the only catalogue, so it sends these
 * across whenever its language changes rather than the app keeping a second
 * set of translations that could drift.
 */
export interface NativeStrings {
  open: string;
  mute: string;
  deafen: string;
  checkForUpdates: string;
  quit: string;
  view: string;
  reload: string;
  addToDictionary: string;
  copyLink: string;
  openLinkInBrowser: string;
  offlineTitle: string;
  offlineBody: string;
  offlineRetry: string;
  about: string;
  /** The Mac app's offer to move itself into Applications, replacing an older copy (succession.ts). */
  moveTitle: string;
  moveBody: string;
  moveReplaceBody: string;
  moveButton: string;
  notNow: string;
  moveFailed: string;
}

export const IPC = {
  openOriginal: "app:open-original",
  setStrings: "app:set-strings",
  rpcGetState: "rpc:get-state",
  rpcSetEnabled: "rpc:set-enabled",
  rpcSetRelay: "rpc:set-relay",
  rpcSetHidden: "rpc:set-hidden",
  rpcRestartDiscord: "rpc:restart-discord",
  rpcState: "rpc:state",
  activities: "rpc:activities",
  activitiesGet: "rpc:get-activities",
  gamesGetState: "games:get-state",
  gamesState: "games:state",
  gamesSetEnabled: "games:set-enabled",
  gamesSetHidden: "games:set-hidden",
  gamesRunningApps: "games:running-apps",
  gamesAdd: "games:add",
  gamesUpdate: "games:update",
  gamesRemove: "games:remove",
  gamesAnswer: "games:answer",
  gamesShareInfo: "games:share-info",
  gamesMarkShared: "games:mark-shared",
  setIdentity: "app:set-identity",
  updateGetState: "update:get-state",
  updateCheck: "update:check",
  updateRestart: "update:restart",
  updateState: "update:state",
  settingsGet: "settings:get",
  settingsSet: "settings:set",
  setBadge: "app:set-badge",
  setCallActive: "app:set-call-active",
  displaySources: "display:sources",
  displayChoose: "display:choose",
  trayAction: "tray:action",
  focusWindow: "app:focus-window",
  requestAttention: "app:request-attention",
  windowGetState: "app:get-window-state",
  shareAudioFailed: "display:share-audio-failed",
  screenAccess: "display:screen-access",
  openScreenSettings: "display:open-screen-settings",
  resetScreenAccess: "display:reset-screen-access",
  restartApp: "app:restart",
  windowState: "app:window-state",
  paste: "app:paste",
  setShortcuts: "app:set-shortcuts",
  shortcut: "app:shortcut",
} as const;
