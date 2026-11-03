/**
 * Platform legal info for the consent gate, the Settings → About page, and
 * the public /terms /privacy /about routes: which version is in force, who to
 * contact, and who has agreed on this device.
 *
 * The documents themselves are docs/legal/*.txt, shown word for word (see
 * legalDocs.ts); the summaries around them are the legal.* strings.
 *
 * Legal text stays in English on purpose (like the law-enforcement report):
 * a translated Terms is a different Terms.
 */

/** The effective date of the Terms and Privacy Policy in docs/legal (a test holds the two to it). */
export const LEGAL_EFFECTIVE = "2026-11-02";

/**
 * What an account agrees to: the effective date, and which revision of that
 * day's wording. Changing it asks every account again, so it moves with any
 * change to what the documents say.
 */
export const LEGAL_VERSION = `${LEGAL_EFFECTIVE}.1`;

export const CONTACT_EMAIL = "evilionx3@proton.me";

/** In-app owner account for contact / reports. */
export const OWNER_USERNAME = "nora";

/** Public source for every Nexa client and the server; also read by Android. */
export const GITHUB_URL = "https://github.com/terrydavisthe2nd/nexa";

/** The guides for plugin authors and custom CSS, read on GitHub (docs/plugins.md, docs/custom-css.md). */
export const PLUGIN_GUIDE_URL = `${GITHUB_URL}/blob/main/docs/plugins.md`;
export const CSS_GUIDE_URL = `${GITHUB_URL}/blob/main/docs/custom-css.md`;

export function termsKey(userId: string): string {
  return `nexa.terms-accepted.${userId}`;
}

export function termsAccepted(userId: string): boolean {
  try {
    return localStorage.getItem(termsKey(userId)) === LEGAL_VERSION;
  } catch {
    return false;
  }
}

/** The version this account agreed to before, if any: someone agreeing again is shown what changed. */
export function previouslyAccepted(userId: string): string | null {
  try {
    const v = localStorage.getItem(termsKey(userId));
    return v && v !== LEGAL_VERSION ? v : null;
  } catch {
    return null;
  }
}

const termsListeners = new Set<() => void>();

/** Calls back whenever an account agrees, for what waits behind the consent gate (the What's New pop-up). */
export function onTermsAccepted(cb: () => void): () => void {
  termsListeners.add(cb);
  return () => {
    termsListeners.delete(cb);
  };
}

export function acceptTerms(userId: string): void {
  try {
    localStorage.setItem(termsKey(userId), LEGAL_VERSION);
  } catch {
    /* private window: the gate shows again next visit, which is the safe side */
  }
  for (const cb of [...termsListeners]) cb();
}
