import { create } from "zustand";

/**
 * View Server as Role: a preview, on this device only, of a server as the
 * chosen roles see it. Only this account's own permissions are worked out
 * from them; the server still sends what the account may really see.
 */
export interface ViewAs {
  serverId: string;
  roleIds: string[];
}

export const useViewAs = create<{ current: ViewAs | null }>(() => ({ current: null }));

/** The roles this account is previewing the server as, if any. */
export function viewingAs(serverId: string): string[] | null {
  const v = useViewAs.getState().current;
  return v && v.serverId === serverId ? v.roleIds : null;
}
