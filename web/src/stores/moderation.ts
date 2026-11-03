import { useEffect, useRef } from "react";
import { create } from "zustand";
import { moderation as modApi, type Announcement, type CaseCounts, type CaseMessage, type ModLogEntry, type ModUnread, type People, type StaffCase } from "@/api/moderation";
import { staffBugs } from "@/api/bugs";
import type { SafetyReport } from "@/api/reports";
import type { BugCounts, BugMessage, StaffBug } from "@/api/bugs";

/**
 * The staff's side: how much is waiting on them (the rail and sidebar show
 * it) and the people the panel has loaded. The panel's views keep their own
 * lists and hear about changes through `useModEvents`, so an open case, the
 * Active Cases list and the log all move the moment anything happens.
 */

export type ModEvent =
  | { type: "case"; case: StaffCase; messages: CaseMessage[]; people: People }
  | { type: "log"; entry: ModLogEntry; people: People }
  | { type: "announcement"; announcement: Announcement; people: People }
  | { type: "announcement_gone"; id: string }
  | { type: "bug"; report: StaffBug; messages: BugMessage[]; people: People }
  | { type: "report"; reports: SafetyReport[]; people: People };

interface ModerationStore {
  counts: CaseCounts | null;
  /** How many bug reports each view of the Bug Reports tab holds. */
  bugCounts: BugCounts | null;
  /** How many safety reports hold each status: the Reports tab's badge. */
  reportCounts: Record<string, number> | null;
  setReportCounts: (c: Record<string, number> | null | undefined) => void;
  /**
   * The case threads and bug reports waiting on the staff that this person
   * has not read: what the badges on the rail, the sidebar and the panel's
   * tabs count, and what Mark as Read clears.
   */
  unread: ModUnread;
  ownerId: string | null;
  people: People;
  setCounts: (c: CaseCounts | undefined) => void;
  setBugCounts: (c: BugCounts | null | undefined) => void;
  setUnread: (u: ModUnread | null | undefined) => void;
  /** Mark as Read: everything, on every device of this account. */
  readAll: () => Promise<void>;
  /** Reads one case thread or bug report up to a line (all of it without one). */
  readThread: (kind: "case" | "bug", id: string, messageId?: string) => void;
  setOwner: (id: string | undefined) => void;
  mergePeople: (p: People | undefined) => void;
  reset: () => void;
}

const NONE: ModUnread = { cases: [], bugs: [] };

/** How many threads wait unread: the number every badge for the panel shows. */
export function unreadCount(u: ModUnread): number {
  return u.cases.length + u.bugs.length;
}

export const useModeration = create<ModerationStore>()((set, get) => ({
  counts: null,
  bugCounts: null,
  reportCounts: null,
  setReportCounts: (reportCounts) => reportCounts && set({ reportCounts }),
  unread: NONE,
  ownerId: null,
  people: {},
  setCounts: (counts) => counts && set({ counts }),
  setBugCounts: (bugCounts) => bugCounts && set({ bugCounts }),
  setUnread: (u) => u && set({ unread: { cases: u.cases ?? [], bugs: u.bugs ?? [] } }),
  readAll: async () => {
    const before = get().unread;
    // gone at once; the server's own word follows as mod_unread
    set({ unread: NONE });
    try {
      set({ unread: await modApi.readAll() });
    } catch (err) {
      if (get().unread === NONE) set({ unread: before });
      throw err;
    }
  },
  readThread: (kind, id, messageId) => {
    const u = get().unread;
    const list = kind === "case" ? u.cases : u.bugs;
    if (!list.includes(id)) return;
    set({ unread: kind === "case" ? { ...u, cases: u.cases.filter((x) => x !== id) } : { ...u, bugs: u.bugs.filter((x) => x !== id) } });
    const ask = kind === "case" ? modApi.readCase(id, messageId) : staffBugs.read(id, messageId);
    void ask.then((next) => set({ unread: next })).catch(() => undefined);
  },
  setOwner: (id) => id && set({ ownerId: id }),
  mergePeople: (p) => p && set((s) => ({ people: { ...s.people, ...p } })),
  reset: () => set({ counts: null, bugCounts: null, reportCounts: null, unread: NONE, ownerId: null, people: {} }),
}));

const listeners = new Set<(e: ModEvent) => void>();

/** Hands a moderation event to every open view that listens. */
export function emitModEvent(e: ModEvent): void {
  if (e.type !== "announcement_gone") useModeration.getState().mergePeople(e.people);
  for (const l of [...listeners]) l(e);
}

/** Calls `handler` with every moderation event while the component is mounted. */
export function useModEvents(handler: (e: ModEvent) => void): void {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => {
    const l = (e: ModEvent) => ref.current(e);
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  }, []);
}
