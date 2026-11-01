import { useEffect } from "react";
import { useNavigate, type NavigateFunction } from "react-router-dom";
import { appLink, messageTarget, type AppLink } from "@/lib/appLinks";
import { openProfileLink } from "@/lib/profileLinks";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";

/** Whether the app itself is on screen, with its dialogs, rather than one of the pages outside it. */
function inApp(): boolean {
  return window.location.pathname.startsWith("/channels") && useAuth.getState().status === "ready";
}

/** Opens a link to one of the app's own pages inside it; `anchor` is the link that was clicked, if one was. */
export function followAppLink(link: AppLink, nav: NavigateFunction, anchor?: Element): void {
  const ui = useUI.getState();
  if (link.kind === "invite") {
    // Inside the app an invite opens over the conversation it was in; from
    // one of the pages outside it, the invite page is where to go.
    if (inApp()) ui.openModal("joinInvite", { code: link.code, hash: link.hash });
    else nav(`/invite/${link.code}${link.hash}`);
    return;
  }
  if (link.kind === "note") {
    // the same for a note's share link: a dialog in the app, its own page outside it
    if (inApp()) ui.openModal("noteLink", { token: link.token, hash: link.hash });
    else nav(`/note/${link.token}${link.hash}`);
    return;
  }
  if (link.kind === "profile") {
    // and a profile link: the profile over the app, or the profile's own page outside it
    if (inApp()) void openProfileLink(link.username);
    else nav(`/@${link.username}`);
    return;
  }
  // a link in a dialog (a profile, a topic) leads away from it
  if (anchor?.closest("[data-modal-layer]")) ui.closeAllModals();
  if (link.kind === "app") {
    // adding an app brings the person back to where they were
    nav(link.path, { state: inApp() ? { from: window.location.pathname + window.location.search } : undefined });
    return;
  }
  const here = window.location.pathname + window.location.search + window.location.hash;
  if (link.path === here) {
    // the page is already open: a message link still jumps to its message
    const target = messageTarget(link.path);
    if (target) ui.setJumpTo(target);
    return;
  }
  nav(link.path);
}

/**
 * Keeps links to this app inside it. One listener for the whole document, so
 * a link works the same wherever it was drawn (a message, a profile, a topic,
 * a link card): a plain click, or Enter on a focused link, goes through the
 * router instead of opening a tab, which in the desktop app would be the
 * system browser. A click with Ctrl, Cmd, Shift or Alt, a middle click, a
 * download, and a link marked `data-new-tab` still open a new tab as usual,
 * and anything that handled the click itself (a router Link, a footnote) is
 * left alone.
 */
export function AppLinks() {
  const nav = useNavigate();
  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const anchor = e.target instanceof Element ? e.target.closest("a[href]") : null;
      if (!anchor || anchor.hasAttribute("download") || anchor.hasAttribute("data-new-tab")) return;
      const href = anchor.getAttribute("href") ?? "";
      // a jump within the page, and anything being typed into, are the browser's
      if (!href || href.startsWith("#") || anchor.closest("[contenteditable='true'], [contenteditable='']")) return;
      const link = appLink(href);
      if (!link) return;
      e.preventDefault();
      followAppLink(link, nav, anchor);
    };
    document.addEventListener("click", onClick);
    return () => document.removeEventListener("click", onClick);
  }, [nav]);
  return null;
}
