import { useEffect } from "react";
import { create } from "zustand";
import { ApiError } from "@/api/client";
import type { Badge, PublicUser } from "@/api/types";
import { users as usersApi } from "@/api/users";
import { t } from "@/i18n";
import { shareOrigin } from "@/lib/appLinks";
import { copyText } from "@/lib/clipboard";
import { FLAG_DELETED, FLAG_SYSTEM } from "@/lib/moderation";
import { useAuth } from "@/stores/auth";
import { useUI, type ContextMenuItem } from "@/stores/ui";
import { useUsers } from "@/stores/users";

/**
 * Profile links: https://nexa.fish/@username.
 *
 * Anyone may open one, signed in or not. Signed out, it is the person's own
 * profile card on a page of its own (pages/ProfilePage); signed in, it is
 * their profile in the app. In a chat it is drawn as their card
 * (components/chat/ProfileCards), and anywhere else it unfurls from the tags
 * the server writes into the page (internal/api/profile_links.go).
 *
 * What a link shows is asked of the server without an account: what every
 * account sees of the person, less their status and their encryption key. So
 * it never goes into the users store, where it would stand in for the full
 * record the app keeps of someone it knows.
 */

export function profileUrl(username: string): string {
  return `${shareOrigin()}/@${username}`;
}

export function copyProfileLink(username: string): void {
  void copyText(profileUrl(username), t("clipboard.profileLinkCopied"));
}

/** The account flags whose profiles no link shows: Nexa's own, a webhook's (32), a deleted one. */
const NO_LINK = FLAG_SYSTEM | 32 | FLAG_DELETED;

/** Copy Profile Link, for a menu about someone the app knows; none for an account with no profile to share. */
export function profileLinkItem(userId: string): ContextMenuItem | null {
  const me = useAuth.getState().me;
  const user = me?.id === userId ? me : useUsers.getState().byId[userId];
  if (!user?.username || (user.flags ?? 0) & NO_LINK) return null;
  const username = user.username;
  return { id: "copy-profile-link", label: t("profileLink.copy"), onSelect: () => copyProfileLink(username) };
}

/** A profile as its link shows it: the person, and the widgets on their profile (lib/widgets). */
export type SharedUser = PublicUser & { widgets?: unknown; badges?: Badge[] };

export type SharedProfileEntry =
  | { status: "loading" }
  | { status: "ready"; user: SharedUser; at: number }
  /** no such account, or one whose link shows nothing (banned, deleted) */
  | { status: "missing"; at: number }
  | { status: "failed"; at: number };

/** A name or a picture changes now and then: a card drawn again after this long asks again, showing what it had meanwhile. */
const FRESH_MS = 5 * 60_000;
/** A failed request (offline, busy) is tried again after this long. */
const RETRY_MS = 30_000;

export const useSharedProfiles = create<{ byName: Record<string, SharedProfileEntry> }>()(() => ({ byName: {} }));

const inflight = new Map<string, Promise<void>>();

function put(name: string, entry: SharedProfileEntry): void {
  useSharedProfiles.setState((s) => ({ byName: { ...s.byName, [name]: entry } }));
}

function due(e: SharedProfileEntry | undefined, now: number): boolean {
  if (!e) return true;
  if (e.status === "loading") return false;
  return now - e.at > (e.status === "failed" ? RETRY_MS : FRESH_MS);
}

interface LoadOptions {
  /** Ask again even if the answer is fresh: after trying again by hand. */
  force?: boolean;
  fetch?: (username: string) => Promise<SharedUser>;
  now?: () => number;
}

/** Asks the server whose profile a link leads to, unless this device already knows or is already asking. */
export function loadSharedProfile(username: string, opts: LoadOptions = {}): Promise<void> {
  const name = username.toLowerCase();
  const now = opts.now ?? Date.now;
  const running = inflight.get(name);
  if (running) return running;
  const cur = useSharedProfiles.getState().byName[name];
  if (!opts.force && !due(cur, now())) return Promise.resolve();
  // an answer already on screen stays there while it is refreshed
  if (cur?.status !== "ready") put(name, { status: "loading" });
  const fetchProfile = opts.fetch ?? ((n: string) => usersApi.shared(n));
  const p = fetchProfile(name)
    .then(
      (user) => put(name, { status: "ready", user, at: now() }),
      (err: unknown) => {
        if (err instanceof ApiError && err.status === 404) put(name, { status: "missing", at: now() });
        else if (cur?.status === "ready") put(name, { ...cur, at: now() });
        else put(name, { status: "failed", at: now() });
      },
    )
    .finally(() => inflight.delete(name));
  inflight.set(name, p);
  return p;
}

/** Whose profile a link leads to, asking the server when that is not known yet or is out of date. */
export function useSharedProfile(username: string): SharedProfileEntry | undefined {
  const name = username.toLowerCase();
  const entry = useSharedProfiles((s) => s.byName[name]);
  useEffect(() => {
    void loadSharedProfile(name);
  }, [name]);
  return entry;
}

/**
 * Opens the profile a link leads to over whatever is on screen, for someone
 * signed in: the app's own profile dialog, which loads the full profile
 * itself. A link to nobody says so instead.
 */
export async function openProfileLink(username: string): Promise<void> {
  const name = username.toLowerCase();
  await loadSharedProfile(name);
  const entry = useSharedProfiles.getState().byName[name];
  const ui = useUI.getState();
  if (entry?.status === "ready") ui.openModal("profile", { userId: entry.user.id, full: true });
  else if (entry?.status === "missing") ui.toast(t("profileLink.missingToast"), "error");
  else ui.toast(t("profileLink.couldNotLoadToast"), "error");
}
