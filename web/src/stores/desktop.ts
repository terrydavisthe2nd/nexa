import { create } from "zustand";
import type { GamesState, RpcState, UpdateState, WindowState } from "@/lib/desktopBridge";
import type { KeybindAction } from "@/lib/keybinds";

/** What the desktop app last said about Rich Presence and updates (nothing in a browser). */
interface DesktopState {
  rpc: RpcState | null;
  /** Game detection (null in a browser, or an app too old to have it). */
  games: GamesState | null;
  update: UpdateState | null;
  /** Where the app's window is (null in a browser, or an app too old to say). */
  window: WindowState | null;
  /**
   * Which keybinds the system let the app hold while another window is in
   * front (lib/keybinds), and which another program already holds. Null in a
   * browser, in an app too old to hold them, or while they are switched off.
   */
  shortcuts: Partial<Record<KeybindAction, boolean>> | null;
  setRpc: (rpc: RpcState) => void;
  setGames: (games: GamesState) => void;
  setUpdate: (update: UpdateState) => void;
  setWindow: (window: WindowState) => void;
  setShortcuts: (shortcuts: Partial<Record<KeybindAction, boolean>> | null) => void;
}

export const useDesktop = create<DesktopState>()((set) => ({
  rpc: null,
  games: null,
  update: null,
  window: null,
  shortcuts: null,
  setRpc: (rpc) => set({ rpc }),
  setGames: (games) => set({ games }),
  setUpdate: (update) => set({ update }),
  setWindow: (window) => set({ window }),
  setShortcuts: (shortcuts) => set({ shortcuts }),
}));
