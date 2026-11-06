/**
 * Add Another Account's last step (components/modals/AddAccountModal.tsx), kept
 * apart from lib/accounts.ts so it loads with the dialog rather than the app.
 */
import { api } from "@/api/client";
import { keystore } from "@/crypto/keystore";
import { rememberAccount, switchAccount, useAccounts } from "@/lib/accounts";
import { hasLock } from "@/lib/appLock";
import { ReadableError } from "@/lib/readableError";
import { useAuth, type SignedIn } from "@/stores/auth";
import { t } from "@/i18n";

/**
 * Keeps an account just signed in to (Add Another Account) beside the one open
 * here, then switches to it. Its key goes where a sign-in would put it.
 */
export async function addSignedIn(out: SignedIn): Promise<void> {
  const logOut = (token: string) => fetch(api.url("/auth/logout"), { method: "POST", headers: { Authorization: `Bearer ${token}` } }).catch(() => undefined);
  if (out.me.id === useAuth.getState().me?.id) {
    // the account already open: the second session is not wanted
    void logOut(out.token);
    throw new ReadableError(t("accounts.alreadyOpen"));
  }
  const before = useAccounts.getState().list.find((a) => a.id === out.me.id);
  if (!hasLock(out.me.id)) await keystore.saveIdentity(out.me.id, out.identity);
  rememberAccount(out.me, out.token);
  // signed in to again: the session it had here goes
  if (before && before.token !== out.token) void logOut(before.token);
  await switchAccount(out.me.id);
}

/**
 * Keeps an account just created beside the one open here, without switching yet:
 * its recovery key still has to be saved, and a reload meanwhile must not lose it.
 */
export async function keepCreated(out: SignedIn): Promise<void> {
  await keystore.saveIdentity(out.me.id, out.identity);
  rememberAccount(out.me, out.token);
}
