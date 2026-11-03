import { create } from "zustand";
import { users } from "@/api/users";
import type { Badge } from "@/api/types";

/**
 * This account's own badges, every one in its order with the hidden ones
 * marked (GET /users/@me/badges). The profile designer edits them and its
 * preview draws them; a profile of anyone else comes with its own list.
 */
interface MyBadgesStore {
  list: Badge[] | null;
  error: boolean;
  load: () => Promise<void>;
  /** Saves an order and hidden set, showing it at once and going back if the server refuses. */
  save: (list: Badge[]) => Promise<void>;
  reset: () => void;
}

export const useMyBadges = create<MyBadgesStore>()((set, get) => ({
  list: null,
  error: false,
  load: async () => {
    set({ error: false });
    try {
      set({ list: (await users.myBadges()).badges });
    } catch {
      set({ error: true });
    }
  },
  save: async (list) => {
    const before = get().list;
    set({ list });
    try {
      const r = await users.setBadges(
        list.map((b) => b.id),
        list.filter((b) => b.hidden).map((b) => b.id),
      );
      set({ list: r.badges });
    } catch (err) {
      set({ list: before });
      throw err;
    }
  },
  reset: () => set({ list: null, error: false }),
}));
