/**
 * Links to this app, told apart from links to anywhere else.
 *
 * A link to one of Nexa's own pages (an invite, a channel, a message, a
 * sticker pack, the download page) opens inside the app instead of a new tab:
 * in the desktop app a new tab is the system browser, and on the web it is a
 * second copy of the app. Nexa's public address counts as well as the
 * page's own, so a link someone copied from https://chat.example.org works on
 * a page the same server serves under another name, and plain http counts
 * because it only redirects to https.
 */

/** Where a link to this app leads. `hash` keeps an invite's or a note's key, which never leaves the device. */
export type AppLink =
  | { kind: "invite"; code: string; hash: string }
  | { kind: "note"; token: string; hash: string }
  | { kind: "profile"; username: string }
  | { kind: "app"; clientId: string; path: string }
  | { kind: "route"; path: string };

/** An invite link found in a message: its code, and the key in its fragment when it has one. */
export interface InviteLinkRef {
  code: string;
  hash: string;
  url: string;
}

let publicHost: string | null = null;
let publicOrigin: string | null = null;

/** Remembers Nexa's public address, as GET /instance reports it. */
export function setPublicUrl(url: string | null | undefined): void {
  try {
    const u = url ? new URL(url) : null;
    publicHost = u ? u.host.toLowerCase() : null;
    publicOrigin = u ? u.origin : null;
  } catch {
    publicHost = null;
    publicOrigin = null;
  }
}

/**
 * The address a link made to be shared starts with: Nexa's public one, so a
 * link copied at another of its addresses, or in the desktop app, still reads
 * as Nexa's own. The page's own address until the server has said.
 */
export function shareOrigin(): string {
  return publicOrigin ?? (typeof window === "undefined" ? "" : window.location.origin);
}

/** Paths the server answers itself (files, media, the API), which the router knows nothing of. */
const SERVER_PATH = /^\/(?:api|media|assets|twemoji|icons|home|sounds|__download|\.well-known)(?:\/|$)/;
/** A file: the service workers, the manifest, anything with an extension. Nothing of the app's has one. */
const FILE_PATH = /\.[a-z0-9]{2,12}$/i;
/**
 * A guest call link starts a guest session of its own, which cannot share a
 * page with a signed-in one, so it keeps opening where it did.
 */
const OWN_SESSION = /^\/call\//;
const INVITE_PATH = /^\/invite\/([A-Za-z0-9_-]{1,64})\/?$/;
/** A note's share link (crypto/notes.ts): its token, and its key after the #. */
const NOTE_PATH = /^\/note\/([A-Za-z0-9_-]{16,128})\/?$/;
const MESSAGE_PATH = /^\/channels\/(@me|\d+)\/(\d+)\/(\d+)\/?$/;
/** A sticker or emoji pack's page (pages/StickerPackPage), which the app opens as one of its routes. */
const PACK_PATH = /^\/stickers\/pack\/(\d{1,20})\/?$/;
/** A profile link (lib/profileLinks): an @ and a username, which may have dots in it like a file's name. */
const PROFILE_PATH = /^\/@([A-Za-z0-9_.]{2,32})\/?$/;
/** The page that adds an app (pages/AuthorizePage), which an app's install link opens. */
const AUTHORIZE_PATH = /^\/oauth2\/authorize\/?$/;

interface Here {
  href: string;
  host: string;
}

/**
 * What a link leads to when it is one of the app's own pages, or null for
 * everything the browser should handle as it would anyway: other sites, the
 * server's files, a jump to a heading on this same page.
 */
export function appLink(href: string, here: Here = window.location): AppLink | null {
  let u: URL;
  let cur: URL;
  try {
    cur = new URL(here.href);
    u = new URL(href, cur);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") return null;
  const host = u.host.toLowerCase();
  if (host !== here.host.toLowerCase() && host !== publicHost) return null;
  const path = u.pathname;
  // before the file test: "@maya.dev" is a username, not a file
  const profile = PROFILE_PATH.exec(path);
  if (profile) return { kind: "profile", username: profile[1]!.toLowerCase() };
  if (SERVER_PATH.test(path) || FILE_PATH.test(path) || OWN_SESSION.test(path)) return null;
  // a footnote or a table of contents on the page being read
  if (u.hash && path === cur.pathname && u.search === cur.search) return null;
  const invite = INVITE_PATH.exec(path);
  if (invite) return { kind: "invite", code: invite[1]!, hash: u.hash };
  const note = NOTE_PATH.exec(path);
  if (note) return { kind: "note", token: note[1]!, hash: u.hash };
  // an app's install link (the Developer Portal's): the app it adds
  const clientId = AUTHORIZE_PATH.test(path) ? u.searchParams.get("client_id") : null;
  if (clientId && /^\d{1,20}$/.test(clientId)) return { kind: "app", clientId, path: path + u.search };
  return { kind: "route", path: path + u.search + u.hash };
}

/** Whether a link is a note's share link to this app, which nothing is asked about but the app itself. */
export function isNoteLink(href: string, here?: Here): boolean {
  const at = here ?? (typeof window === "undefined" ? null : window.location);
  return !!at && appLink(href, at)?.kind === "note";
}

const JUMP_PATH = /^\/channels\/(@me|\d{1,20})\/(\d{1,20})(?:\/(\d{1,20}))?\/?$/;

/**
 * The channel, and message if any, a link to a conversation in this app
 * points to, drawn as a small pill instead of its address (markdown/render).
 * Only a bare path counts: anything after it (a query, a fragment) is shown
 * as written.
 */
export function jumpTarget(href: string, here?: Here): { channelId: string; messageId: string | null } | null {
  const at = here ?? (typeof window === "undefined" ? null : window.location);
  if (!at) return null;
  const link = appLink(href, at);
  if (link?.kind !== "route") return null;
  const m = JUMP_PATH.exec(link.path);
  return m ? { channelId: m[2]!, messageId: m[3] ?? null } : null;
}

/** The channel and message a message link points to. */
export function messageTarget(path: string): { channelId: string; messageId: string } | null {
  const m = MESSAGE_PATH.exec(path.split(/[?#]/)[0]!);
  return m ? { channelId: m[2]!, messageId: m[3]! } : null;
}

/** How many invite cards one message shows at most, how many note cards, profile cards and pack cards. */
export const MAX_INVITE_CARDS = 3;
export const MAX_NOTE_CARDS = 3;
export const MAX_PROFILE_CARDS = 3;
export const MAX_PACK_CARDS = 3;
export const MAX_APP_CARDS = 3;

/** A note's share link found in a message: its token, the key in its fragment, and the link as written. */
export interface NoteLinkRef {
  token: string;
  hash: string;
  url: string;
}

/** Visits every link a message's parsed markdown has that asks for a preview: not one in code, not one written as <url>. */
function eachLink(nodes: unknown, fn: (url: string) => boolean): void {
  let stop = false;
  const visit = (value: unknown, depth: number) => {
    if (stop || depth > 64 || !value || typeof value !== "object") return;
    if (Array.isArray(value)) {
      for (const v of value) visit(v, depth + 1);
      return;
    }
    const node = value as Record<string, unknown>;
    if (node["type"] === "code" || node["type"] === "codeblock") return;
    if (node["type"] === "link" && typeof node["url"] === "string" && node["noEmbed"] !== true && !fn(node["url"])) {
      stop = true;
      return;
    }
    for (const key of Object.keys(node)) {
      const v = node[key];
      if (v && typeof v === "object") visit(v, depth + 1);
    }
  };
  visit(nodes, 0);
}

/** The note share links a message has, in order and each once. */
export function noteLinksIn(nodes: unknown, here: Here = window.location): NoteLinkRef[] {
  const out: NoteLinkRef[] = [];
  const seen = new Set<string>();
  eachLink(nodes, (url) => {
    const link = appLink(url, here);
    if (link?.kind === "note" && !seen.has(link.token)) {
      seen.add(link.token);
      out.push({ token: link.token, hash: link.hash, url });
    }
    return out.length < MAX_NOTE_CARDS;
  });
  return out;
}

/**
 * The invites to Nexa that a message links to, in order and each
 * once. `nodes` is the message's parsed markdown: a link in code is not a
 * link, and one written as <url> asked for no preview.
 */
export function inviteLinksIn(nodes: unknown, here: Here = window.location): InviteLinkRef[] {
  const out: InviteLinkRef[] = [];
  const seen = new Set<string>();
  eachLink(nodes, (url) => {
    const link = appLink(url, here);
    if (link?.kind === "invite" && !seen.has(link.code)) {
      seen.add(link.code);
      out.push({ code: link.code, hash: link.hash, url });
    }
    return out.length < MAX_INVITE_CARDS;
  });
  return out;
}

/** A profile link found in a message: whose, and the link as written. */
export interface ProfileLinkRef {
  username: string;
  url: string;
}

/** The profiles a message links to, in order and each once. */
export function profileLinksIn(nodes: unknown, here: Here = window.location): ProfileLinkRef[] {
  const out: ProfileLinkRef[] = [];
  const seen = new Set<string>();
  eachLink(nodes, (url) => {
    const link = appLink(url, here);
    if (link?.kind === "profile" && !seen.has(link.username)) {
      seen.add(link.username);
      out.push({ username: link.username, url });
    }
    return out.length < MAX_PROFILE_CARDS;
  });
  return out;
}

/** An app's install link found in a message: the app (its client id, as the link names it) and the link as written. */
export interface AppInstallLinkRef {
  clientId: string;
  path: string;
  url: string;
}

/** The apps a message links to the install page of, in order and each once. */
export function appInstallLinksIn(nodes: unknown, here: Here = window.location): AppInstallLinkRef[] {
  const out: AppInstallLinkRef[] = [];
  const seen = new Set<string>();
  eachLink(nodes, (url) => {
    const link = appLink(url, here);
    if (link?.kind === "app" && !seen.has(link.clientId)) {
      seen.add(link.clientId);
      out.push({ clientId: link.clientId, path: link.path, url });
    }
    return out.length < MAX_APP_CARDS;
  });
  return out;
}

/** A sticker or emoji pack link found in a message: which pack, and the link as written. */
export interface PackLinkRef {
  id: string;
  url: string;
}

/** The sticker and emoji packs a message links to, in order and each once. */
export function packLinksIn(nodes: unknown, here: Here = window.location): PackLinkRef[] {
  const out: PackLinkRef[] = [];
  const seen = new Set<string>();
  eachLink(nodes, (url) => {
    const link = appLink(url, here);
    const m = link?.kind === "route" ? PACK_PATH.exec(link.path.split(/[?#]/)[0]!) : null;
    if (m && !seen.has(m[1]!)) {
      seen.add(m[1]!);
      out.push({ id: m[1]!, url });
    }
    return out.length < MAX_PACK_CARDS;
  });
  return out;
}

