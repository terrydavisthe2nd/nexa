import { create } from "zustand";
import { reports, type ReportReceipt } from "@/api/reports";

/**
 * The reports this account sent, as their receipts: the status and what was
 * reported, never staff notes. Settings, Your Reports lists them, and
 * `report_update` moves one the moment staff change its status.
 */
interface MyReportsStore {
  list: ReportReceipt[] | null;
  more: boolean;
  loading: boolean;
  error: boolean;
  load: (older?: boolean) => Promise<void>;
  /** A receipt the server sent: a new report from this account, or a new status. */
  onUpdate: (r: ReportReceipt) => void;
  reset: () => void;
}

export const useMyReports = create<MyReportsStore>()((set, get) => ({
  list: null,
  more: false,
  loading: false,
  error: false,
  load: async (older) => {
    if (get().loading) return;
    set({ loading: true, error: false });
    try {
      const before = older ? get().list?.at(-1)?.id : undefined;
      const r = await reports.mine(before);
      set((s) => ({ list: before ? [...(s.list ?? []), ...r.reports.filter((x) => !s.list?.some((y) => y.id === x.id))] : r.reports, more: r.has_more }));
    } catch {
      set({ error: true });
    } finally {
      set({ loading: false });
    }
  },
  onUpdate: (r) => {
    const list = get().list;
    const old = list?.find((x) => x.id === r.id);
    if (!list) return;
    set({ list: old ? list.map((x) => (x.id === r.id ? r : x)) : [r, ...list] });
  },
  reset: () => set({ list: null, more: false, loading: false, error: false }),
}));
