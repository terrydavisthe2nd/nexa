import { unfurl as unfurlApi } from "@/api/unfurl";
import type { UnfurlResult } from "@/api/types";
import { isNoteLink, jumpTarget } from "@/lib/appLinks";

export interface LinkPreviews {
  /** Starts fetching a preview for the link unless it is known or on its way. */
  start: (url: string) => void;
  /** The previews for these links, waiting up to `waitMs` for any still on their way. */
  embedsFor: (urls: string[], waitMs: number) => Promise<UnfurlResult[]>;
}

/**
 * The link previews one composer has fetched. Only the part of a link before
 * `#` is sent to the server: invite links carry the server's key in the
 * fragment, and fragments must never leave the device. The fragment is put
 * back on the preview so an invite card still joins with the key.
 */
export function createLinkPreviews(fetchPreview: (url: string) => Promise<UnfurlResult | null> = (url) => unfurlApi.fetch(url)): LinkPreviews {
  const done = new Map<string, UnfurlResult | null>();
  const inflight = new Map<string, Promise<void>>();

  const start = (url: string) => {
    if (done.has(url) || inflight.has(url)) return;
    // a note's share link is drawn by the app itself, from what its key opens (NoteCards)
    // and a link to a channel or message is a pill in the text, with no card under it
    if (isNoteLink(url) || jumpTarget(url)) {
      done.set(url, null);
      return;
    }
    let request = url;
    let hash = "";
    try {
      const u = new URL(url);
      hash = u.hash;
      u.hash = "";
      request = u.href;
    } catch {
      return;
    }
    const p = fetchPreview(request)
      .then(
        (r) => void done.set(url, r && hash && !r.url.includes("#") ? { ...r, url: r.url + hash } : r),
        // a failed fetch is forgotten so the next edit can try again
        () => undefined,
      )
      .finally(() => inflight.delete(url));
    inflight.set(url, p);
  };

  const embedsFor = async (urls: string[], waitMs: number) => {
    const waiting: Promise<void>[] = [];
    for (const url of urls) {
      start(url);
      const p = inflight.get(url);
      if (p) waiting.push(p);
    }
    if (waiting.length) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      await Promise.race([Promise.allSettled(waiting), new Promise((r) => (timer = setTimeout(r, waitMs)))]);
      clearTimeout(timer);
    }
    return urls.map((u) => done.get(u)).filter((r): r is UnfurlResult => !!r);
  };

  return { start, embedsFor };
}
