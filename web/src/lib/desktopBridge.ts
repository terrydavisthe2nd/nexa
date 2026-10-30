import { isDemo } from "./demoMode";
/**
 * The desktop app's side of the page: what its preload script puts on
 * `window.evilionDesktop`. In a browser there is none, and everything that
 * depends on it stays out of the way.
 *
 * desktop/src-tauri/bridge.js implements this; keep the two in step.
 */

/** The words the desktop app shows outside the page. desktop/src/shared/bridge.ts holds the other copy. */
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
  /** The Mac app's offer to move itself into Applications, replacing an older copy (app 1.1.0 and later). */
  moveTitle?: string;
  moveBody?: string;
  moveReplaceBody?: string;
  moveButton?: string;
  notNow?: string;
  moveFailed?: string;
}

/** A Rich Presence activity as a program sent it to the desktop app, cleaned there. */
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
}

/** One program connected to the desktop app's Rich Presence server. */
export interface RpcApp {
  clientId: string;
  /** The program's own name for itself, once Discord's directory has been asked. */
  name: string | null;
  activity: RpcActivity | null;
  /** Hidden by the user: kept off their profile. */
  hidden: boolean;
  connectedAt: number;
}

export interface RpcState {
  /** The Rich Presence server is on. */
  enabled: boolean;
  /** Programs also reach Discord through us when it is running. */
  relay: boolean;
  /** Where programs find us: a pipe or socket path, and the WebSocket port. */
  listening: { ipc: string | null; ws: number | null };
  /**
   * connected: Discord runs and gets everything too; absent: Discord is not
   * running, so activity shows only here; first: Discord started first and
   * holds the pipe programs try first (Windows), so programs talk only to it.
   */
  discord: "connected" | "absent" | "first";
  apps: RpcApp[];
}

export interface UpdateState {
  status: "idle" | "checking" | "downloading" | "ready" | "manual" | "error";
  /** The version found, when there is one. */
  version?: string;
  /** 0–1 while downloading. */
  progress?: number;
  /** A package format that cannot update itself (.deb): where to download the new one. */
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
  /** data: URL of a small thumbnail. */
  thumbnail: string;
  /** data: URL of the window's app icon, when there is one. */
  icon?: string;
}

export interface DisplaySourceRequest {
  requestId: string;
  sources: DisplaySource[];
  /** Native application sound, selected independently from the picture. */
  sounds?: { id: string; name: string }[];
  /** The system can capture sound along with the picture (Windows). */
  audio: boolean;
  /** Set instead of sources when this computer will not let the app record the screen. */
  permission?: { status: string; stale: boolean };
}

/** Where the app's window is, as the window system says (app 1.0.3 and later). */
export interface WindowState {
  focused: boolean;
  visible: boolean;
  minimized: boolean;
}

export interface EvilionDesktop {
  /** Native capture handed to the webview over a same-host media peer. */
  captureScreen?(options: { width: number; height: number; fps: number; audio: boolean }): Promise<MediaStream>;
  /** Also cancels a pending native picker when no stream is given. */
  stopScreen?(stream?: MediaStream): void;
  /** Open decrypted media in the default web browser (app 1.2.10 and later). */
  openOriginal?(bytes: Uint8Array, type: string, name: string): Promise<void>;
  version: string;
  platform: "win32" | "darwin" | "linux";
  /** Whether a screen share here can carry the computer's sound (app 1.0.1 and later). */
  shareAudio?: boolean;
  /**
   * What that sound leaves out (app 1.0.2 and later): "own" is everything but
   * the app, so the call cannot echo; "all" includes the app, on systems that
   * cannot leave it out; "none" is no sound.
   */
  shareAudioMode?: "own" | "all" | "none" | string;
  /** Sharing a window carries just that program's sound (app 1.0.3 and later, on Windows 10 2004+ and macOS 14.2+). */
  windowAudio?: boolean;
  rpc: {
    getState(): Promise<RpcState>;
    setEnabled(on: boolean): Promise<void>;
    setRelay(on: boolean): Promise<void>;
    setHidden(clientId: string, hidden: boolean): Promise<void>;
    /** Windows only: quits and reopens Discord so that we hold the first pipe. */
    restartDiscord(): Promise<boolean>;
    onState(cb: (state: RpcState) => void): () => void;
  };
  /** The activities to show, newest first, hidden programs left out. */
  onActivities(cb: (list: RpcActivity[]) => void): () => void;
  /** Who is signed in, so programs that ask get this user rather than nobody. */
  setIdentity(user: { id: string; username: string; global_name: string; avatar: string | null } | null): void;
  updates: {
    getState(): Promise<UpdateState>;
    check(): Promise<void>;
    restart(): Promise<void>;
    onState(cb: (state: UpdateState) => void): () => void;
  };
  settings: {
    get(): Promise<DesktopSettings>;
    set(patch: Partial<DesktopSettings>): Promise<DesktopSettings>;
  };
  /**
   * The words the app shows outside this page: the tray menu, the macOS menu
   * bar, the right-click menu on text, the page that stands in when the site
   * cannot be reached. Sent from here because the catalogue lives here (app
   * 1.0.4 and later).
   */
  setStrings?(strings: NativeStrings): void;
  setBadge(count: number): void;
  setCallActive(active: boolean): void;
  onDisplaySources(cb: (req: DisplaySourceRequest) => void): () => void;
  /** Answers a display source request; a null source cancels the share. */
  chooseDisplaySource(requestId: string, sourceId: string | null, withAudio: boolean, soundId?: string | null): void;
  /** Mute or Deafen chosen from the tray icon. */
  onTrayAction(cb: (action: "mute" | "deafen") => void): () => void;
  /** Brings the window back from the tray or the taskbar and focuses it (app 1.0.1 and later). */
  focusWindow?(): void;
  /** Flashes the taskbar button or bounces the Dock icon until the window is looked at (app 1.0.1 and later). */
  requestAttention?(): void;
  /** Whether the window is focused, shown and not minimised; the page's own document.hasFocus() can be wrong in the app (1.0.3 and later). */
  getWindowState?(): Promise<WindowState>;
  /**
   * A share failed to start after its sound was chosen. True when the app has
   * fallen back to safer sound and asking again shares the same source
   * straight away, without the picker (app 1.0.3 and later).
   */
  shareAudioFailed?(): Promise<boolean>;
  /** Whether this computer will really let the app record the screen (macOS keeps a decision that can go stale). */
  screenAccess?(): Promise<{ status: string; usable: boolean; stale: boolean }>;
  openScreenSettings?(): Promise<void>;
  /**
   * Has macOS forget its screen recording decision for the app, then asks
   * again and opens Screen Recording in System Settings; true when macOS took
   * the reset (the app after 1.2.2).
   */
  resetScreenAccess?(): Promise<boolean>;
  restartApp?(): Promise<void>;
  onWindowState?(cb: (state: WindowState) => void): () => void;
  /**
   * Pastes into whatever the page has focused, as the app's own Paste does,
   * for Nexa's right-click menu on text (app 1.0.6 and later). The page may
   * not read the clipboard itself (the app's permissions.ts), so this is how
   * that menu pastes. Resolves false when the app refused: it pastes only
   * straight after a right click and a click, or the keys that do the same.
   */
  paste?(): Promise<boolean>;
  /**
   * Holds these keybinds system-wide, so they answer with another window in
   * front (app 1.2.2 and later), in place of the ones held before; an empty
   * list lets go of them all. Says which ones the system gave: a key another
   * program holds cannot be taken.
   */
  setShortcuts?(list: { action: string; accelerator: string }[]): Promise<{ action: string; ok: boolean }[]>;
  /** A keybind held system-wide was pressed (app 1.2.2 and later). */
  onShortcut?(cb: (action: string) => void): () => void;
}

export function desktopBridge(): EvilionDesktop | null {
  if (isDemo || typeof window === "undefined") return null;
  return (window as Window & { evilionDesktop?: EvilionDesktop }).evilionDesktop ?? null;
}

/**
 * The desktop app from before it could paste for the page (1.0.5 and older),
 * whose page may not read the clipboard either. It has the same right-click
 * menu as everywhere else (lib/textMenu), but its Paste cannot paste: it
 * carries a warning and offers the update (components/desktop/
 * PasteUpdateDialog). The keyboard pastes there as always.
 */
export function pasteNeedsUpdate(): boolean {
  const bridge = desktopBridge();
  return !!bridge && typeof bridge.paste !== "function";
}
