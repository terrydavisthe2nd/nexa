import { create } from "zustand";
import type { ReactNode } from "react";
import type { GatewayStatus } from "@/gateway/client";

export interface ModalState {
  /** Stack entry id; closeModal(id) removes exactly this modal. */
  id?: number;
  kind: string;
  props?: Record<string, unknown>;
}

export interface ContextMenuItem {
  id: string;
  label: string;
  danger?: boolean;
  disabled?: boolean;
  icon?: string;
  /** A small picture in the icon's place: whose item it is, such as an app's for its commands. */
  picture?: ReactNode;
  onSelect?: () => void;
  separatorAfter?: boolean;
  /** Marks the chosen one of a set of choices, such as the microphone in use. */
  checked?: boolean;
  /** With `checked`: the item switches one thing on and off, a tick box rather than one choice of a set. */
  toggle?: boolean;
  /** The keys that do the same, shown quietly at the item's end: "Ctrl+X". */
  keys?: string;
  /** Drawn in bold, as a spelling offered for a misspelled word is. */
  strong?: boolean;
  /**
   * Something stands in the way of the item doing what it says (an app too
   * old to paste from this menu): a warning sign at its end, with these words
   * for its tooltip and a screen reader. Choosing it explains instead.
   */
  warn?: string;
  /** A small title over the items that follow it; not an item itself. */
  heading?: boolean;
  /** A line of information, such as when a message was sent: read, never chosen. */
  note?: boolean;
  /** Makes the item a slider that takes effect as it moves, and leaves the menu open. */
  slider?: { value: number; min: number; max: number; step?: number; unit?: string; onChange: (value: number) => void };
  /**
   * Makes the item open a menu of its own, one level deep: beside this one
   * with a mouse or a keyboard, and in its place (with a way back) on a touch
   * screen. The item itself is then never chosen, so it has no onSelect.
   */
  submenu?: ContextMenuItem[];
}

export interface ContextMenuState {
  x: number;
  y: number;
  items: ContextMenuItem[];
  /**
   * A row of emoji to react with in one tap, over the items: shown only when
   * the menu is a phone's sheet, where it saves opening the picker.
   */
  reactions?: { emoji: string[]; mine: string[]; pick: (emoji: string) => void; more: () => void };
  /** What the menu is called, for anyone who cannot see what it opened from. */
  label?: string;
  /** Rich visible menu title; label remains the searchable/accessibility text. */
  labelNode?: ReactNode;
  /** Opens upwards, its bottom edge at y: for a button along the bottom of the screen. */
  above?: boolean;
  /**
   * The button the menu belongs to. The menu opens under it (over it when
   * there is no room below), lined up with the button's start or end edge in
   * the reading direction, once its real width is known. x and y are then only
   * where it starts out.
   */
  anchor?: { left: number; right: number; top: number; bottom: number; align: "start" | "end" };
  /**
   * Leaves focus where the menu was opened until a key is pressed, as a menu
   * of the system's own does, so a text box keeps its selection showing while
   * its menu is open. The first key moves focus into the menu. For a menu
   * opened with the mouse; one opened from the keyboard takes focus at once.
   */
  keepFocus?: boolean;
}

export interface Toast {
  id: number;
  text: ReactNode;
  kind: "info" | "error" | "success" | "warning";
}

interface UIState {
  connection: GatewayStatus;
  /** Why the connection is down, when the server said something worth passing on; null otherwise. */
  connectionNote: string | null;
  leftOpen: boolean;
  rightOpen: boolean;
  membersOpen: boolean;
  /** The topmost open modal (kept for readers that only care whether one is open). */
  modal: ModalState | null;
  /** Every open modal, bottom to top; a modal opened from inside another stacks above it. */
  modals: ModalState[];
  contextMenu: ContextMenuState | null;
  quickSwitcher: boolean;
  activeChannelId: string | null;
  replyTo: { channelId: string; messageId: string; ping: boolean } | null;
  editing: { channelId: string; messageId: string } | null;
  /**
   * Edits in progress, by channel: the message and what has been typed so far.
   * Going to another conversation parks the edit here, and coming back opens it
   * again with the same text; saving or cancelling ends it.
   */
  editDrafts: Record<string, { messageId: string; text: string | null }>;
  jumpTo: { channelId: string; messageId: string } | null;
  toasts: Toast[];
  /** While a toast is pointed at or focused, none of them go away. */
  toastsHeld: boolean;
  focused: boolean;
  setConnection: (s: GatewayStatus, note?: string | null) => void;
  setLeftOpen: (v: boolean) => void;
  setRightOpen: (v: boolean) => void;
  toggleMembers: () => void;
  openModal: (kind: string, props?: Record<string, unknown>) => void;
  /** Closes the modal with that id, or the topmost one. */
  closeModal: (id?: number) => void;
  /** Adds to an open modal's props, for what it has to find again if it is drawn anew (the language changed under it). */
  setModalProps: (id: number, props: Record<string, unknown>) => void;
  closeAllModals: () => void;
  openContextMenu: (m: ContextMenuState) => void;
  closeContextMenu: () => void;
  setQuickSwitcher: (v: boolean) => void;
  setActiveChannel: (id: string | null) => void;
  setReplyTo: (r: UIState["replyTo"]) => void;
  setEditing: (e: UIState["editing"]) => void;
  setEditDraft: (channelId: string, messageId: string, text: string) => void;
  /** Ends the edit of that one message: its kept text goes, and the open edit closes only if it is this one. */
  endEdit: (channelId: string, messageId: string) => void;
  setJumpTo: (j: UIState["jumpTo"]) => void;
  /** `duration` in milliseconds, for a plugin's toast (from 1 to 15 seconds); the app's own toasts go by their kind. */
  toast: (text: ReactNode, kind?: Toast["kind"], duration?: number) => void;
  dismissToast: (id: number) => void;
  holdToasts: (held: boolean) => void;
  setFocused: (v: boolean) => void;
}

let toastSeq = 0;
/** Modals that replace themselves rather than stacking. */
const ONE_AT_A_TIME = new Set(["game", "mentions", "profile", "serverProfile", "memberAction", "verifyKey", "keyChange"]);

let modalSeq = 0;

export const useUI = create<UIState>()((set, get) => ({
  connection: "idle",
  connectionNote: null,
  leftOpen: false,
  rightOpen: false,
  membersOpen: true,
  modal: null,
  modals: [],
  contextMenu: null,
  quickSwitcher: false,
  activeChannelId: null,
  replyTo: null,
  editing: null,
  editDrafts: {},
  jumpTo: null,
  toasts: [],
  toastsHeld: false,
  focused: true,
  // a note says why the connection went; it stays through the reconnect it explains and goes once one succeeds
  setConnection: (connection, note) => set((s) => ({ connection, connectionNote: connection === "open" || connection === "idle" ? null : note === undefined ? s.connectionNote : note })),
  setLeftOpen: (leftOpen) => set(leftOpen ? { leftOpen: true, rightOpen: false } : { leftOpen: false }),
  setRightOpen: (rightOpen) => set(rightOpen ? { rightOpen: true, leftOpen: false } : { rightOpen: false }),
  toggleMembers: () => set((s) => ({ membersOpen: !s.membersOpen })),
  openModal: (kind, props) =>
    set((s) => {
      modalSeq += 1;
      const entry: ModalState = props ? { id: modalSeq, kind, props } : { id: modalSeq, kind };
      // A profile leads to another profile — mutual friends, a group's member
      // list — and stacking them leaves a pile to close one at a time. One of
      // these is open at once: the one being looked at.
      const kept = ONE_AT_A_TIME.has(kind) ? s.modals.filter((m) => m.kind !== kind) : s.modals;
      const modals = [...kept, entry];
      return { modals, modal: entry };
    }),
  closeModal: (id) =>
    set((s) => {
      if (s.modals.length === 0) return s;
      const modals = id === undefined ? s.modals.slice(0, -1) : s.modals.filter((m) => m.id !== id);
      return { modals, modal: modals[modals.length - 1] ?? null };
    }),
  setModalProps: (id, props) =>
    set((s) => {
      if (!s.modals.some((m) => m.id === id)) return s;
      const modals = s.modals.map((m) => (m.id === id ? { ...m, props: { ...m.props, ...props } } : m));
      return { modals, modal: modals[modals.length - 1] ?? null };
    }),
  closeAllModals: () => set({ modals: [], modal: null }),
  openContextMenu: (contextMenu) => set({ contextMenu }),
  closeContextMenu: () => set({ contextMenu: null }),
  setQuickSwitcher: (quickSwitcher) => set({ quickSwitcher }),
  // Re-activating the open channel keeps a reply or edit in progress; a reply
  // that belongs to another channel is dropped, and an edit waits in
  // editDrafts until its channel is opened again.
  setActiveChannel: (id) =>
    set((s) => {
      if (s.activeChannelId === id) return s;
      // an edit being left behind is kept, even one nothing was typed into yet (null: the message's own text)
      let editDrafts = s.editDrafts;
      if (s.editing && s.editing.channelId !== id && editDrafts[s.editing.channelId]?.messageId !== s.editing.messageId) {
        editDrafts = { ...editDrafts, [s.editing.channelId]: { messageId: s.editing.messageId, text: null } };
      }
      const parked = id ? editDrafts[id] : undefined;
      const editing = s.editing?.channelId === id ? s.editing : id && parked ? { channelId: id, messageId: parked.messageId } : null;
      return { activeChannelId: id, replyTo: s.replyTo?.channelId === id ? s.replyTo : null, editing, editDrafts };
    }),
  setReplyTo: (replyTo) => set({ replyTo }),
  // ending an edit (or starting another in the same channel) forgets the text kept for the old one
  setEditing: (editing) =>
    set((s) => {
      const channelId = editing?.channelId ?? s.editing?.channelId;
      if (!channelId || s.editDrafts[channelId]?.messageId === editing?.messageId) return { editing };
      const editDrafts = { ...s.editDrafts };
      delete editDrafts[channelId];
      return { editing, editDrafts };
    }),
  setEditDraft: (channelId, messageId, text) => set((s) => ({ editDrafts: { ...s.editDrafts, [channelId]: { messageId, text } } })),
  // a save that finishes after its channel was left must not close another channel's edit, or leave its own text to come back
  endEdit: (channelId, messageId) =>
    set((s) => {
      const editing = s.editing?.channelId === channelId && s.editing.messageId === messageId ? null : s.editing;
      if (s.editDrafts[channelId]?.messageId !== messageId) return editing === s.editing ? s : { editing };
      const editDrafts = { ...s.editDrafts };
      delete editDrafts[channelId];
      return { editing, editDrafts };
    }),
  setJumpTo: (jumpTo) => set({ jumpTo }),
  toast: (text, kind = "info", duration) => {
    toastSeq += 1;
    const id = toastSeq;
    set((s) => ({ toasts: [...s.toasts, { id, text, kind }] }));
    // An error is the one worth reading twice, so it stays longer; and none
    // goes while someone is reading it with the pointer on it or focus in it.
    const expire = (): void => {
      if (get().toastsHeld) {
        setTimeout(expire, 1000);
        return;
      }
      set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
    };
    setTimeout(expire, typeof duration === "number" && Number.isFinite(duration) ? Math.min(15_000, Math.max(1000, duration)) : kind === "error" ? 8000 : 5000);
  },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id), toastsHeld: s.toasts.length > 1 && s.toastsHeld })),
  holdToasts: (toastsHeld) => set({ toastsHeld }),
  setFocused: (focused) => set({ focused }),
}));
