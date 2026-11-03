import { AppWindow, AudioLines, Ban, Bot, Compass, Flag, ShieldAlert, Bell, ChevronLeft, ChevronRight, Code, DatabaseZap, Gamepad2, Gavel, Heart, IdCard, Info, Keyboard, KeyRound, Languages, Link, Lock, MessagesSquare, Mic, Monitor, MonitorSmartphone, Palette, PersonStanding, Puzzle, Scale, ScrollText, Settings2, Shield, ShieldCheck, SlidersHorizontal, Smile, Sticker, Tag, Tags, Trash2, UserRound, Users, Webhook, Wrench, X, type LucideIcon } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Button } from "@/components/common/Button";
import { isTopLayer } from "@/components/common/Modal";
import { useFocusTrap } from "@/components/common/useFocusTrap";
import s from "@/components/settings/Settings.module.css";
import { useIsMobile } from "@/hooks/useIsMobile";
import { unlessUnsaved, useHoldUnsaved, useUnsaved } from "@/stores/unsaved";

import { t } from "@/i18n";
import { usePortalRoot } from "@/hooks/usePortalRoot";
/** A picture for each page the settings have, by its id, the same for a user's, a server's and a channel's. */
const NAV_ICONS: Record<string, LucideIcon> = {
  account: UserRound,
  profiles: IdCard,
  privacy: ShieldCheck,
  activity: Gamepad2,
  keybinds: Keyboard,
  tag: Tag,
  blocked: Ban,
  reports: Flag,
  security: KeyRound,
  devices: MonitorSmartphone,
  subscription: Heart,
  appearance: Palette,
  accessibility: PersonStanding,
  language: Languages,
  chat: MessagesSquare,
  data: DatabaseZap,
  advanced: Wrench,
  developers: Bot,
  authorizedApps: AppWindow,
  customCss: Code,
  plugins: Puzzle,
  voice: Mic,
  notifications: Bell,
  sounds: AudioLines,
  stickers: Sticker,
  desktop: Monitor,
  licenses: Scale,
  about: Info,
  admin: Shield,
  overview: Settings2,
  roles: Tags,
  emoji: Smile,
  integrations: Webhook,
  encryption: Lock,
  members: Users,
  invites: Link,
  bans: Gavel,
  audit: ScrollText,
  automod: ShieldCheck,
  discovery: Compass,
  safety: ShieldAlert,
  permissions: SlidersHorizontal,
  delete: Trash2,
};

/** Pages that need more than the reading width a settings page usually has. */
const WIDE_PAGES = new Set(["profiles"]);

/** The picture a settings page is shown with, wherever it is listed (Settings' search, say). */
export function navIconOf(id: string): LucideIcon | undefined {
  return NAV_ICONS[id];
}

export interface NavGroup {
  /** The group's heading; without one the group is set apart by a divider instead. */
  head: string;
  items: { id: string; label: string; danger?: boolean }[];
}

/**
 * The full-screen settings frame used by user, server and channel settings.
 *
 * On a wide screen the nav stands beside the page. A phone has room for one of
 * them at a time, so there the nav is a list of its own: a page opens from it
 * and has a way back to it. The sideways-scrolling strip of page names it used
 * to be ran under the close button and showed three of fifteen pages at once.
 */
export function SettingsLayer({
  label,
  groups,
  page,
  setPage,
  onClose,
  children,
  footer,
  startOnList = false,
  navTop,
  navBody,
}: {
  label: string;
  groups: NavGroup[];
  page: string;
  setPage: (id: string) => void;
  onClose: () => void;
  children: ReactNode;
  /** Entries under the pages that are not pages themselves, such as Log Out. */
  footer?: ReactNode;
  /** On a phone, open on the list instead of on `page`: settings opened as a whole rather than at one page. */
  startOnList?: boolean;
  /** Above the nav: a search through the settings. */
  navTop?: ReactNode;
  /** In place of the nav's groups while there is something to show instead: what the search found. */
  navBody?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);
  // into the full-screen element when a call is full screen, where the body is not seen
  const portalRoot = usePortalRoot();
  const mobile = useIsMobile();
  const [atList, setAtList] = useState(() => startOnList && mobile);
  // A window narrowed to phone width keeps showing the page it had, and
  // whatever is unsaved on it, rather than swapping it for the list.
  useEffect(() => {
    if (!mobile) setAtList(false);
  }, [mobile]);
  const onList = mobile && atList;
  // a page chosen from outside the nav (what Settings' search found) is opened, on a phone too
  const firstPage = useRef(true);
  useEffect(() => {
    if (firstPage.current) {
      firstPage.current = false;
      return;
    }
    setAtList(false);
  }, [page]);
  // into the layer when it opens: at the page it opened on, or its way back to the list
  useFocusTrap(ref, true, () => (mobile && !atList ? backRef.current : ref.current?.querySelector<HTMLElement>('[aria-current="page"]')));
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && isTopLayer(ref.current)) {
        e.stopPropagation();
        unlessUnsaved(onClose)();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  // A reload or a closed tab takes unsaved edits with it just as surely as
  // the close button, and nothing used to ask: the browser's own "Leave
  // site?" now does, while a save bar is showing.
  useEffect(() => {
    const onUnload = (e: BeforeUnloadEvent) => {
      if (useUnsaved.getState().holds === 0) return;
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", onUnload);
    return () => window.removeEventListener("beforeunload", onUnload);
  }, []);
  // Each page starts at its top. The column scrolls, not the page, so it kept
  // the last page's offset: Licenses scrolled halfway opened My Account halfway.
  useLayoutEffect(() => {
    scroller.current?.scrollTo?.(0, 0);
  }, [page, onList]);
  // On a phone, focus follows the swap: to the way back when a page opens, and
  // to the page's own entry when the list comes back.
  const swapped = useRef(false);
  useEffect(() => {
    if (!swapped.current) {
      swapped.current = true;
      return;
    }
    if (!mobile) return;
    if (onList) ref.current?.querySelector<HTMLElement>(`[data-nav="${page}"]`)?.focus();
    else backRef.current?.focus({ preventScroll: true });
    // only the swap itself moves focus
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onList]);

  const open = (id: string) => {
    if (id !== page) setPage(id);
    setAtList(false);
  };

  // On a phone a page is swiped away to the list it came from, the way a
  // phone goes back: a drag towards the reading direction's start that
  // carries the page with it, and back it goes past a third of the way.
  const swipe = useRef<{ x: number; y: number; t: number; on: boolean } | null>(null);
  const flow = () => (document.documentElement.dir === "rtl" ? -1 : 1);
  const slide = (x: number | null, ms = 0) => {
    const el = scroller.current;
    if (!el) return;
    el.style.transition = ms ? `transform ${ms}ms cubic-bezier(0.2, 0.8, 0.2, 1), opacity ${ms}ms ease` : "none";
    el.style.transform = x === null ? "" : `translateX(${x * flow()}px)`;
    el.style.opacity = x === null ? "" : String(Math.max(0.4, 1 - x / 600));
  };
  const swipeBack = {
    onTouchStart: (e: React.TouchEvent) => {
      const t0 = e.touches[0];
      const target = e.target instanceof Element ? e.target : null;
      // a slider, a field, or anything that scrolls sideways keeps its own drag
      if (!t0 || e.touches.length !== 1 || target?.closest("input, textarea, select, [contenteditable='true'], [data-no-swipe]")) {
        swipe.current = null;
        return;
      }
      swipe.current = { x: t0.clientX, y: t0.clientY, t: e.timeStamp, on: false };
    },
    onTouchMove: (e: React.TouchEvent) => {
      const sw = swipe.current;
      const t1 = e.touches[0];
      if (!sw || !t1) return;
      const dx = (t1.clientX - sw.x) * flow();
      const dy = t1.clientY - sw.y;
      if (!sw.on) {
        if (Math.abs(dx) < 14 && Math.abs(dy) < 14) return;
        if (dx < 0 || Math.abs(dx) < Math.abs(dy) * 1.4) {
          swipe.current = null;
          return;
        }
        sw.on = true;
      }
      slide(Math.max(0, dx));
    },
    onTouchEnd: (e: React.TouchEvent) => {
      const sw = swipe.current;
      swipe.current = null;
      if (!sw?.on) return;
      const t1 = e.changedTouches[0];
      const dx = t1 ? (t1.clientX - sw.x) * flow() : 0;
      const fast = dx / Math.max(1, e.timeStamp - sw.t) > 0.5;
      const width = scroller.current?.clientWidth ?? 360;
      if (dx > width / 3 || (fast && dx > 40)) {
        slide(width, 160);
        window.setTimeout(() => {
          slide(null);
          unlessUnsaved(() => setAtList(true))();
        }, 150);
      } else slide(null, 200);
    },
  };
  const close = (
    <button className={s.close} aria-label={t("server.settingsLayer.closeSettings")} onClick={unlessUnsaved(onClose)}>
      <X size={mobile ? 22 : 20} />
    </button>
  );
  return createPortal(
    <div ref={ref} className={s.layer} role="dialog" aria-modal="true" aria-label={label} data-modal-layer="" data-view={mobile ? (onList ? "list" : "page") : undefined} tabIndex={-1}>
      {mobile ? (
        <div className={s.topBar}>
          {onList ? (
            <h2 className={s.topTitle}>{label}</h2>
          ) : (
            <button ref={backRef} className={s.back} onClick={unlessUnsaved(() => setAtList(true))} aria-label={t("server.settingsLayer.backTo", { name: label })} data-testid="settings-back">
              <ChevronLeft className={s.flip} size={22} aria-hidden="true" />
              <span className={s.backLabel}>{label}</span>
            </button>
          )}
          {close}
        </div>
      ) : null}
      {!mobile || onList ? (
        <div className={s.navWrap}>
          {/* Selection lived only in `data-selected`, which is a CSS hook: ten identically-stated buttons, with no way to hear which page is open. */}
          <nav className={s.nav} aria-label={label}>
            {navTop}
            {navBody ?? groups.map((g, i) => (
              <div key={g.head + i} className={s.navSection}>
                {g.head ? <div className={s.navHead}>{g.head}</div> : <div className={s.navSep} />}
                <div className={s.navGroup}>
                  {g.items.map((it) => {
                    // the list on a phone stands in for the page rather than beside it, so nothing in it is "here"
                    const here = !mobile && page === it.id;
                    return (
                      <button
                        key={it.id}
                        className={[s.navItem, it.danger ? s.danger : ""].join(" ")}
                        data-selected={here}
                        aria-current={here ? "page" : undefined}
                        data-nav={it.id}
                        onClick={mobile ? unlessUnsaved(() => open(it.id)) : here ? undefined : unlessUnsaved(() => setPage(it.id))}
                      >
                        {NAV_ICONS[it.id] ? <NavIcon icon={NAV_ICONS[it.id]!} /> : null}
                        <span className={s.navLabel}>{it.label}</span>
                        {mobile ? <ChevronRight className={[s.navChevron, s.flip].join(" ")} size={18} aria-hidden="true" /> : null}
                      </button>
                    );
                  })}
                </div>
              </div>
            ))}
            {navBody ? null : footer}
          </nav>
        </div>
      ) : null}
      {onList ? null : (
        <div ref={scroller} className={s.contentWrap} {...(mobile ? swipeBack : {})}>
          {/* the page as wide as it needs: the profile designer shows a whole profile beside its tools */}
          <div className={s.content} data-wide={WIDE_PAGES.has(page) || undefined}>{children}</div>
          {mobile ? null : (
            <div className={s.closeCol}>
              {close}
              <span className={s.esc}>ESC</span>
            </div>
          )}
        </div>
      )}
    </div>,
    portalRoot,
  );
}

/** A settings page's picture in the nav, in a small lit tile. */
export function NavIcon({ icon: Icon }: { icon: LucideIcon }) {
  return (
    <span className={s.navIcon} aria-hidden="true">
      <Icon size={16} />
    </span>
  );
}

/** The sticky "You have unsaved changes" bar. */
export function SaveBar({ dirty, busy, onReset, onSave, label = t("server.settingsLayer.saveChanges") }: { dirty: boolean; busy: boolean; onReset: () => void; onSave: () => void; label?: string }) {
  useHoldUnsaved(dirty);
  // shaken each time something tries to leave with these edits unsaved
  const nudge = useUnsaved((u) => u.nudge);
  const [calm] = useState(nudge);
  if (!dirty) return null;
  return (
    <div key={nudge} className={[s.saveBar, nudge !== calm ? s.shake : ""].join(" ")} data-testid="save-bar" role="status">
      <span className={s.saveText}>{t("server.settingsLayer.carefulYouHaveUnsavedChanges")}</span>
      <span className={s.saveBtns}>
        <Button variant="ghost" onClick={onReset} disabled={busy}>{t("common.imageCrop.reset")}</Button>
        <Button variant="positive" onClick={onSave} loading={busy} data-testid="save">{label}</Button>
      </span>
    </div>
  );
}
