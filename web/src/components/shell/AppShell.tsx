import { Suspense, useEffect, useRef, useState, type ReactNode, type TouchEvent } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { Rail } from "@/components/rail/Rail";
import { DMSidebar } from "@/components/sidebar/DMSidebar";
import { ServerSidebar } from "@/components/sidebar/ServerSidebar";
import { StaffSidebar } from "@/components/moderation/StaffSidebar";
import { ModalHost } from "@/components/modals/ModalHost";
import { NexaNotices } from "@/components/nexa/NexaNotices";
import { TermsGate } from "@/components/legal/TermsGate";
import { WhatsNew } from "@/components/shell/WhatsNewDialog";
import { lazyPage } from "@/lib/lazyChunk";
import { DownloadBanner } from "@/components/shell/DownloadBanner";
import { MaintenanceBanner } from "./MaintenanceBanner";
import { ViewAsBanner } from "./ViewAsBanner";
import { RpcBanner } from "@/components/shell/RpcBanner";
import { isTypingField } from "@/lib/keyboardInset";
import { rowSwipeActive } from "@/lib/swipeReply";
import { DiscordBanner } from "@/components/shell/DiscordBanner";
import { UpdatePrompt } from "@/components/shell/UpdateDialog";
import { DesktopEffects } from "@/components/desktop/DesktopEffects";
import { EmojiDetails } from "@/components/chat/EmojiDetails";
import { Shortcuts } from "@/components/shell/Shortcuts";
import { TAB_PANEL_ID, Tabs } from "@/components/shell/Tabs";
import { UserPanel } from "@/components/shell/UserPanel";
import { VoiceStatus } from "@/components/voice/VoiceStatus";
import { useIsMobile } from "@/hooks/useIsMobile";
import { activeTab, tabAddress, useTabs } from "@/stores/tabs";
import { useUI } from "@/stores/ui";
import { installVoice } from "@/voice/session";
import { installAway } from "@/lib/away";
import { installDiscord } from "@/stores/discord";
import { installSearchIndex } from "@/search/indexer";
import { installBackLayers } from "./backLayers";
import s from "./AppShell.module.css";
import { t } from "@/i18n";
import { isDemo } from "@/lib/demoMode";

/** The plan offers and the once-per-release Support Nexa prompt, loaded after the app opens (components/support/Upsell.tsx). */
const Plans = lazyPage(() =>
  import("@/components/support/Upsell").then((m) => ({
    default: () => (
      <>
        <m.SupportPrompt />
        <m.UpsellHost />
      </>
    ),
  })),
);

/** Controls a horizontal drag already means something to. */
const NATIVE_DRAG = "input, textarea, select, [contenteditable=''], [contenteditable='true'], [role=slider], [data-native-press]";

/** Puts the on-screen keyboard away: whatever text field has it lets go. */
function dismissKeyboard(): void {
  const el = document.activeElement;
  if (isTypingField(el)) el.blur();
}

/** Three-pane desktop layout; on phones the rail+sidebar live in a left drawer. */
export function AppShell({ children }: { children: ReactNode }) {
  const mobile = useIsMobile();
  const leftOpen = useUI((u) => u.leftOpen);
  const rightOpen = useUI((u) => u.rightOpen);
  const setLeftOpen = useUI((u) => u.setLeftOpen);
  const setRightOpen = useUI((u) => u.setRightOpen);
  const connection = useUI((u) => u.connection);
  const connectionNote = useUI((u) => u.connectionNote);
  const loc = useLocation();
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const openModal = useUI((u) => u.openModal);
  const touch = useRef<{ x: number; y: number; edge: boolean } | null>(null);
  // The browser knows the moment the network goes; the socket only finds out
  // when a heartbeat goes unanswered, and for up to half a minute the app
  // looked perfectly healthy while nothing could arrive.
  const [offline, setOffline] = useState(() => typeof navigator !== "undefined" && navigator.onLine === false);
  useEffect(() => {
    const on = () => setOffline(false);
    const off = () => setOffline(true);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => {
      window.removeEventListener("online", on);
      window.removeEventListener("offline", off);
    };
  }, []);
  /** Which settings page the query has already opened, so one run does it once. */
  const openedSettings = useRef<string | null>(null);
  const serverId = loc.pathname.match(/^\/channels\/(\d+)/)?.[1];
  // the panel has its own sidebar in place of the conversations, as a server has its channels
  const inPanel = loc.pathname.startsWith("/channels/@admin");
  const sidebar = serverId ? <ServerSidebar serverId={serverId} /> : inPanel ? <StaffSidebar /> : <DMSidebar />;
  const tabId = useTabs((p) => activeTab(p).id);
  const setTabChannel = useTabs((p) => p.setTabChannel);
  // the strip is only drawn once there is more than one tab (see Tabs)
  const tabbed = useTabs((p) => p.tabs.length > 1);

  // The address bar and the open tab are kept as one thing, and only one of
  // them is ever in charge at a time.
  //
  // Normally it is the address: a click in the sidebar, a link in a message,
  // the back button and a notification all navigate exactly as they always
  // did, and the open tab follows. The one exception is another tab opening,
  // because it was picked or because the open one was closed, where the tab is
  // in charge and the address follows it. That navigation comes straight back
  // here with the same tab open, and the first branch turns it into a no-op.
  const lastTab = useRef<string | null>(null);
  useEffect(() => {
    const moved = lastTab.current !== null && lastTab.current !== tabId;
    lastTab.current = tabId;
    if (moved) {
      nav(tabAddress(activeTab(useTabs.getState())));
      return;
    }
    const at = loc.pathname.match(/^\/channels\/(@me|\d+)(?:\/(\d+))?/);
    setTabChannel(tabId, at?.[1] === "@me" ? null : (at?.[1] ?? null), at?.[2] ?? null);
  }, [loc.pathname, tabId, setTabChannel, nav]);

  useEffect(() => {
    const page = params.get("settings");
    if (!page) {
      openedSettings.current = null;
      return;
    }
    // Opening was kept to once only by the effect mutating the shared params
    // object out from under its own next run. The guard says it plainly, and
    // the copy below leaves the router's object alone.
    if (openedSettings.current === page) return;
    openedSettings.current = page;
    openModal("settings", { page });
    const next = new URLSearchParams(params);
    next.delete("settings");
    setParams(next, { replace: true });
  }, [params, setParams, openModal]);

  useEffect(() => {
    if (isDemo) return;
    installVoice();
    installAway();
    installDiscord();
    installSearchIndex();
  }, []);

  // back closes the dialog, the settings or the drawer on top (see backLayers)
  useEffect(() => installBackLayers(), []);

  // Going somewhere closes the drawers. Picking a server or Direct Messages in
  // the rail only changes which list the drawer shows, so the drawer stays open
  // for the user to choose a channel or conversation in it.
  const keepDrawer = (loc.state as { keepDrawer?: boolean } | null)?.keepDrawer === true;
  useEffect(() => {
    if (!keepDrawer) setLeftOpen(false);
    setRightOpen(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loc.pathname, setLeftOpen, setRightOpen]);

  // a panel opened any other way (its button, a link) puts the keyboard away too
  useEffect(() => {
    if (mobile && (leftOpen || rightOpen)) dismissKeyboard();
  }, [mobile, leftOpen, rightOpen]);

  useEffect(() => {
    if (!mobile) return;
    // put back what was there rather than clearing outright: the drawer is not
    // the only thing that can want the page behind it to stop scrolling
    const before = document.body.style.overflow;
    document.body.style.overflow = leftOpen ? "hidden" : "";
    return () => {
      document.body.style.overflow = before;
    };
  }, [leftOpen, mobile]);

  // --- swiping the drawers -------------------------------------------------
  // The drawer follows the finger and snaps when it is let go, so it feels
  // like a drawer rather than a shortcut that either fires or does not.
  // `right` is a swipe that started with the member list open; it can only push that list away.
  const drag = useRef<{ x: number; y: number; t: number; axis: "" | "x" | "y"; from: boolean; right: boolean } | null>(null);
  const [dragX, setDragX] = useState<number | null>(null);

  const drawerWidth = () => Math.min(420, Math.max(240, window.innerWidth - 56));
  // Which way "towards opening" is: in a right-to-left language the drawer
  // lives on the right edge and opens with a swipe to the left. Everything
  // below measures along that direction.
  const flow = () => (document.documentElement.dir === "rtl" ? -1 : 1);

  /** A swipe inside something that scrolls sideways belongs to that thing. */
  const scrollsSideways = (target: EventTarget | null): boolean => {
    let el = target instanceof Element ? target : null;
    while (el && el !== document.body) {
      if (el.hasAttribute("data-no-swipe")) return true;
      if (el.scrollWidth > el.clientWidth + 4) {
        const overflow = getComputedStyle(el).overflowX;
        if (overflow === "auto" || overflow === "scroll") return true;
      }
      el = el.parentElement;
    }
    return false;
  };

  const onTouchStart = (e: TouchEvent) => {
    const t = e.touches[0];
    if (!mobile || !t) return;
    if (document.querySelector("[data-modal-layer]")) return;
    // a drag on a slider or in a text field moves the slider or the caret, not the drawer
    if (e.target instanceof Element && e.target.closest(NATIVE_DRAG)) return;
    if (scrollsSideways(e.target)) return;
    drag.current = { x: t.clientX, y: t.clientY, t: Date.now(), axis: "", from: leftOpen, right: rightOpen && !leftOpen };
  };

  const onTouchMove = (e: TouchEvent) => {
    const d = drag.current;
    const t = e.touches[0];
    if (!d || !t) return;
    const dx = (t.clientX - d.x) * flow();
    const dy = t.clientY - d.y;
    // a message being swiped to reply has this touch, not the drawers
    if (rowSwipeActive()) {
      drag.current = null;
      return;
    }
    if (d.axis === "") {
      if (Math.abs(dx) < 12 && Math.abs(dy) < 12) return;
      d.axis = Math.abs(dx) > Math.abs(dy) * 1.3 ? "x" : "y";
      if (d.axis === "y") drag.current = null;
      // a panel being dragged over the chat puts the keyboard away with it
      else dismissKeyboard();
      return;
    }
    if (d.right) return;
    const w = drawerWidth();
    const at = d.from ? w + dx : dx;
    setDragX(Math.max(0, Math.min(w, at)));
  };

  const onTouchEnd = (e: TouchEvent) => {
    const d = drag.current;
    const t = e.changedTouches[0];
    drag.current = null;
    if (rowSwipeActive()) {
      setDragX(null);
      return;
    }
    if (!d || !t || d.axis !== "x") {
      setDragX(null);
      return;
    }
    const dx = (t.clientX - d.x) * flow();
    const w = drawerWidth();
    const speed = dx / Math.max(1, Date.now() - d.t);
    if (d.right) {
      setDragX(null);
      if (dx > 60 || speed > 0.4) setRightOpen(false);
      return;
    }
    // a slow drag settles toward the side it was dragged to once it has gone 40% of the way
    const open = Math.abs(speed) > 0.4 ? dx > 0 : d.from ? w + dx > w * 0.6 : dx > w * 0.4;
    setDragX(null);
    if (open) setLeftOpen(true);
    else if (d.from) setLeftOpen(false);
    else if (dx < -60) setRightOpen(true); // a left swipe with nothing open looks right
  };

  const dragging = dragX !== null;
  const drawerStyle = dragging ? { transform: `translateX(${(dragX - drawerWidth()) * flow()}px)`, transition: "none" } : undefined;
  const backdropStyle = dragging ? { opacity: dragX / drawerWidth(), pointerEvents: "none" as const, transition: "none" } : undefined;
  // You: the call you are in and your own card, under the server rail and the
  // list beside it together. Inside the list alone the name got a few letters.
  const dock = (
    <div className={s.dock}>
      <VoiceStatus />
      <UserPanel />
    </div>
  );

  return (
    <div className={s.app} data-keyboard-fit="" onTouchStart={onTouchStart} onTouchMove={onTouchMove} onTouchEnd={onTouchEnd} onTouchCancel={onTouchEnd}>
      {mobile ? (
        <>
          <div className={s.backdrop} data-open={leftOpen || dragging} style={backdropStyle} onClick={() => setLeftOpen(false)} />
          <div className={s.drawer} data-open={leftOpen} style={drawerStyle}>
            <div className={s.rail}><Rail /></div>
            <div className={s.sidebar}>{sidebar}</div>
            {dock}
          </div>
        </>
      ) : (
        <>
          <div className={s.rail}><Rail /></div>
          <div className={s.sidebar}>{sidebar}</div>
          {dock}
        </>
      )}
      <div className={s.main}>
        {offline ? (
          <div className={s.connection} data-state="closed" role="status" data-testid="offline-banner">{t("shell.appShell.offline")}</div>
        ) : connection !== "open" && connection !== "idle" ? (
          <div className={s.connection} data-state={connection} role="status">{connectionNote ?? (connection === "connecting" ? t("shell.requireAuth.connecting") : t("shell.appShell.disconnectedReconnecting"))}</div>
        ) : null}
        {!isDemo && <MaintenanceBanner />}
        <ViewAsBanner />
        {!isDemo && <UpdatePrompt />}
        <RpcBanner />
        <DiscordBanner />
        {!isDemo && <DownloadBanner />}
        <Tabs />
        {/* the page takes what the banner leaves */}
        <div className={s.page} {...(tabbed ? { role: "tabpanel", id: TAB_PANEL_ID, "aria-labelledby": `tab-${tabId}` } : {})}>
          {children}
        </div>
      </div>
      <ModalHost />
      <NexaNotices />
      <TermsGate />
      <WhatsNew />
      <Suspense fallback={null}>
        <Plans />
      </Suspense>
      <Shortcuts />
      <DesktopEffects />
      <EmojiDetails />
    </div>
  );
}
