import { Check, ChevronLeft, ChevronRight, SmilePlus, TriangleAlert } from "lucide-react";
import { Twemoji } from "./Twemoji";
import { lazy, Suspense, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { usePortalRoot } from "@/hooks/usePortalRoot";
import { attachSheetDrag, fadeElement, sheetsHere } from "@/lib/sheetDrag";
import { t } from "@/i18n";
import { useUI, type ContextMenuItem, type ContextMenuState } from "@/stores/ui";
import s from "./ContextMenu.module.css";

/** A plugin's button can name an icon; the icons load only when a menu shows one (MenuIcon). */
const MenuIcon = lazy(() => import("./MenuIcon"));

function SliderItem({ item }: { item: ContextMenuItem & { slider: NonNullable<ContextMenuItem["slider"]> } }) {
  const { min, max, step = 1, unit = "", onChange } = item.slider;
  const [value, setValue] = useState(item.slider.value);
  return (
    <div className={s.slider} role="group" aria-label={item.label}>
      <div className={s.sliderHead}>
        <span>{item.label}</span>
        <span className={s.sliderValue}>{value}{unit}</span>
      </div>
      <input
        type="range"
        className={s.range}
        min={min}
        max={max}
        step={step}
        value={value}
        aria-label={item.label}
        disabled={item.disabled}
        data-menu-stop=""
        tabIndex={-1}
        onChange={(e) => {
          const next = Number(e.target.value);
          setValue(next);
          onChange(next);
        }}
      />
    </div>
  );
}

/**
 * The items, cut into groups at the headings.
 *
 * A heading is not an item — it is a title over the ones that follow — and the
 * only way to say that in ARIA is a `group` with a name. Everything before the
 * first heading is one unnamed group, which is what a menu with no headings is.
 */
function groups(items: ContextMenuItem[]): { label: string | null; items: ContextMenuItem[] }[] {
  const out: { label: string | null; items: ContextMenuItem[] }[] = [];
  for (const item of items) {
    if (item.heading) {
      out.push({ label: item.label, items: [] });
      continue;
    }
    if (out.length === 0) out.push({ label: null, items: [] });
    out[out.length - 1]!.items.push(item);
  }
  return out;
}

/** Where a menu opened from a button goes: by that button, lined up with its start or end edge. */
export function menuAnchor(el: Element, align: "start" | "end" = "end"): NonNullable<ContextMenuState["anchor"]> {
  const r = el.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, align };
}

/** How long the pointer rests on an item before its submenu opens. */
const OPEN_DELAY = 120;
/**
 * How long an open submenu waits once the pointer is on another item. The way
 * from an item to its submenu runs diagonally across the items below it, and
 * closing at the first of those made a submenu impossible to reach.
 */
const CLOSE_DELAY = 300;

/**
 * Whether a submenu takes the menu's place rather than opening beside it. A
 * finger has no hover to open one with and a phone no room for two menus side
 * by side, so there it is drilled into, with a way back at the top.
 */
function drills(pointer: string | null): boolean {
  if (pointer === "touch") return true;
  if (pointer) return false;
  return typeof window !== "undefined" && !!window.matchMedia?.("(hover: none), (pointer: coarse)").matches;
}

type Sub = {
  item: ContextMenuItem;
  /** Beside the menu, or in its place. */
  mode: "flyout" | "drill";
  /** Whether focus goes into it: opened by a key or a click, not by resting the pointer on its item. */
  focus: boolean;
  /** Where its item was, to line the submenu up with it. */
  anchor: { top: number; bottom: number };
};

/** Renders the ui store's context menu, which a registered menu opens (lib/contextMenus/registry). */
export function ContextMenuHost() {
  const menu = useUI((st) => st.contextMenu);
  const close = useUI((st) => st.closeContextMenu);
  const ref = useRef<HTMLDivElement>(null);
  const subRef = useRef<HTMLDivElement>(null);
  const backdrop = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  const [sub, setSub] = useState<Sub | null>(null);
  const [subPos, setSubPos] = useState<{ x: number; y: number } | null>(null);
  // On a phone the menu is a sheet across the bottom, swiped down to put away.
  const [sheet] = useState(sheetsHere);
  const subId = useId();
  const portalRoot = usePortalRoot();
  // What the menu was opened from. A menu portals to the body, so without this
  // choosing an item left focus at the very end of the document.
  const opener = useRef<HTMLElement | null>(null);
  // Letters typed in a row: "de" goes to Delete rather than to Deafen and then
  // to Edit. Cleared after a pause, as every other menu in every OS does.
  const typed = useRef<{ text: string; at: number }>({ text: "", at: 0 });
  /** The kind of pointer that last pressed inside the menu: a finger drills into submenus. */
  const pointer = useRef<string | null>(null);
  /** A submenu waiting to open or to close, as the pointer moves over the items. */
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const drilled = sub?.mode === "drill" ? sub.item : null;

  const clearTimer = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  }, []);

  // A new menu starts at its top level.
  useLayoutEffect(() => {
    clearTimer();
    setSub(null);
    pointer.current = null;
  }, [menu, clearTimer]);
  useEffect(() => clearTimer, [clearTimer]);

  useLayoutEffect(() => {
    if (!menu || !ref.current) {
      setPos(null);
      return;
    }
    const r = ref.current.getBoundingClientRect();
    const rtl = document.documentElement.dir === "rtl";
    const a = menu.anchor;
    if (a) {
      // Lined up with the button it came from, measured rather than guessed:
      // the menu is as wide as its longest label in the language in use.
      const rightAligned = (a.align === "end") !== rtl;
      const x = rightAligned ? a.right - r.width : a.left;
      const below = a.bottom + 4;
      const over = a.top - r.height - 4;
      const fitsBelow = below + r.height <= window.innerHeight - 8;
      const fitsOver = over >= 8;
      // `above` says which side is wanted; the other one is taken when that one has no room
      const y = menu.above ? (fitsOver || !fitsBelow ? over : below) : fitsBelow || !fitsOver ? below : over;
      setPos({ x: Math.max(8, Math.min(x, window.innerWidth - r.width - 8)), y: Math.max(8, Math.min(y, window.innerHeight - r.height - 8)) });
      return;
    }
    // Towards the reading direction's end: in a right-to-left language a menu
    // opens to the left of where it was asked for, as the system's own do.
    const from = rtl ? menu.x - r.width : menu.x;
    const x = Math.min(from, window.innerWidth - r.width - 8);
    const y = Math.min(menu.above ? menu.y - r.height : menu.y, window.innerHeight - r.height - 8);
    setPos({ x: Math.max(8, x), y: Math.max(8, y) });
    // drilling in changes the menu's size, which is measured again
  }, [menu, drilled]);

  // A submenu beside the menu: level with its item, towards the reading
  // direction's end, or the other way when there is no room that way.
  useLayoutEffect(() => {
    if (!sub || sub.mode !== "flyout" || !subRef.current || !ref.current) {
      setSubPos(null);
      return;
    }
    const box = subRef.current.getBoundingClientRect();
    const parent = ref.current.getBoundingClientRect();
    const rtl = document.documentElement.dir === "rtl";
    // overlapping the menu's edge a little, so the pointer crosses no gap on its way
    const after = parent.right - 4;
    const before = parent.left - box.width + 4;
    const fitsAfter = after + box.width <= window.innerWidth - 8;
    const fitsBefore = before >= 8;
    const x = rtl ? (fitsBefore || !fitsAfter ? before : after) : fitsAfter || !fitsBefore ? after : before;
    // its first item level with the one it came from: the menu's padding and border above it
    const y = Math.min(sub.anchor.top - 7, window.innerHeight - box.height - 8);
    setSubPos({ x: Math.max(8, Math.min(x, window.innerWidth - box.width - 8)), y: Math.max(8, y) });
  }, [sub]);

  /** Everything the arrows can land on in one menu, in the order it is drawn. */
  const stops = useCallback((el: HTMLElement | null): HTMLElement[] => {
    if (!el) return [];
    return [...el.querySelectorAll<HTMLElement>("[data-menu-stop]")].filter((x) => !(x as HTMLButtonElement).disabled);
  }, []);

  const moveTo = useCallback(
    (el: HTMLElement | null, index: number) => {
      const list = stops(el);
      if (list.length === 0) return;
      const at = ((index % list.length) + list.length) % list.length;
      for (const x of list) x.tabIndex = -1;
      const target = list[at]!;
      target.tabIndex = 0;
      target.focus({ preventScroll: true });
    },
    [stops],
  );

  // Focus goes into the menu as it opens and comes back to the opener as it
  // closes, which is the difference between a menu you can use with a keyboard
  // and a menu a keyboard can only ever open.
  useEffect(() => {
    if (!menu) return;
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // Not this frame: the menu is drawn `visibility: hidden` until the layout
    // effect above has measured it and nudged it on screen, and focus() on a
    // hidden element does nothing at all. One frame later it is visible.
    // (not for a menu that leaves focus where it was until a key is pressed: keepFocus)
    const frame = menu.keepFocus ? 0 : requestAnimationFrame(() => stops(ref.current)[0]?.focus({ preventScroll: true }));
    return () => {
      if (frame) cancelAnimationFrame(frame);
      const back = opener.current;
      opener.current = null;
      if (back && document.contains(back)) back.focus({ preventScroll: true });
    };
  }, [menu, stops]);

  // Into a submenu opened by a key or a click: its first item, or in a menu
  // drilled into, the first one after the way back.
  useEffect(() => {
    if (!sub?.focus) return;
    if (sub.mode === "flyout" && !subPos) return;
    const frame = requestAnimationFrame(() => {
      if (sub.mode === "flyout") moveTo(subRef.current, 0);
      else moveTo(ref.current, stops(ref.current).length > 1 ? 1 : 0);
    });
    return () => cancelAnimationFrame(frame);
  }, [sub, subPos, moveTo, stops]);

  const openSub = useCallback((item: ContextMenuItem, el: HTMLElement, focus: boolean) => {
    clearTimer();
    const r = el.getBoundingClientRect();
    // a sheet across a phone has no room beside it, whatever pointer is used
    setSub({ item, mode: sheet || drills(pointer.current) ? "drill" : "flyout", focus, anchor: { top: r.top, bottom: r.bottom } });
  }, [clearTimer, sheet]);

  /** Back to the menu the submenu came from, focus on its item when a key or a tap asked. */
  const closeSub = useCallback(
    (refocus: boolean) => {
      clearTimer();
      const id = sub?.item.id;
      setSub(null);
      if (!refocus || !id) return;
      requestAnimationFrame(() => {
        const item = [...(ref.current?.querySelectorAll<HTMLElement>("[data-submenu]") ?? [])].find((el) => el.dataset["submenu"] === id);
        if (!item) return;
        for (const x of stops(ref.current)) x.tabIndex = -1;
        item.tabIndex = 0;
        item.focus({ preventScroll: true });
      });
    },
    [sub, clearTimer, stops],
  );

  useEffect(() => {
    if (!menu) return;
    const inside = (target: EventTarget | null): boolean => target instanceof Node && (!!ref.current?.contains(target) || !!subRef.current?.contains(target));
    const onDown = (e: MouseEvent) => {
      // the backdrop closes on its own click, so that the tap ends on it rather than on what it covered
      if (e.target === backdrop.current) return;
      if (!inside(e.target)) close();
    };
    // A phone's address bar and keyboard change the height as they come and
    // go; only a real change of width (turning the phone) moves things enough
    // to close the menu.
    const width = window.innerWidth;
    const onResize = () => {
      if (Math.abs(window.innerWidth - width) > 1) close();
    };
    // Only a scroll the person makes closes the menu. The message list moves
    // by itself as messages arrive while scrolled up (keeping the reading
    // position), and that used to close a menu someone was still reading.
    let handScrolled = 0;
    const onHand = (e: Event) => {
      if (!inside(e.target)) handScrolled = performance.now();
    };
    const onScroll = (e: Event) => {
      // Behind a sheet nothing can be scrolled by hand. What moves there is
      // the app settling as the keyboard goes away, which it does whenever a
      // menu is opened while typing (lib/keyboardInset.ts says when).
      if (sheet || inside(e.target) || document.documentElement.hasAttribute("data-keyboard-moving")) return;
      if (performance.now() - handScrolled > 1000) return;
      close();
    };
    // Escape belongs to the menu while it is open, and to nothing else. The
    // dialog underneath keeps its own listener on `document`, and
    // `stopPropagation` does not suppress a sibling listener on the same node —
    // that needs `stopImmediatePropagation`, taken in the capture phase before
    // anyone else has seen the key. Right-clicking inside settings and pressing
    // Escape used to dismiss the menu *and* the whole settings dialog behind
    // it, unsaved edits and all. In a submenu it goes back one level first.
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopImmediatePropagation();
        if (sub) closeSub(true);
        else close();
        return;
      }
      // A menu that left focus where it was opened (keepFocus) takes the
      // keyboard at the first key, as a menu of the system's own does: the
      // arrows and letters move into it and nothing reaches the text box
      // underneath. Tab and shortcuts with a modifier go on and close it.
      if (!menu.keepFocus || inside(document.activeElement) || e.isComposing) return;
      if (e.key === "Shift" || e.key === "Control" || e.key === "Alt" || e.key === "Meta" || e.key === "CapsLock") return;
      if (e.key === "Tab" || e.ctrlKey || e.metaKey || e.altKey) {
        close();
        return;
      }
      e.preventDefault();
      e.stopImmediatePropagation();
      const list = stops(ref.current);
      if (e.key === "ArrowUp" || e.key === "End") {
        moveTo(ref.current, list.length - 1);
        return;
      }
      const at = e.key.length === 1 && e.key !== " " ? list.findIndex((x) => (x.textContent ?? "").trim().toLowerCase().startsWith(e.key.toLowerCase())) : -1;
      moveTo(ref.current, Math.max(at, 0));
    };
    document.addEventListener("keydown", onKey, true);
    document.addEventListener("mousedown", onDown);
    window.addEventListener("resize", onResize);
    document.addEventListener("scroll", onScroll, true);
    document.addEventListener("wheel", onHand, { capture: true, passive: true });
    document.addEventListener("touchmove", onHand, { capture: true, passive: true });
    return () => {
      document.removeEventListener("wheel", onHand, true);
      document.removeEventListener("touchmove", onHand, true);
      document.removeEventListener("keydown", onKey, true);
      document.removeEventListener("mousedown", onDown);
      window.removeEventListener("resize", onResize);
      document.removeEventListener("scroll", onScroll, true);
    };
  }, [menu, close, sub, closeSub, stops, moveTo, sheet]);

  useEffect(() => {
    const el = ref.current;
    if (!menu || !el || !sheet) return;
    return attachSheetDrag({
      sheet: el,
      onClose: close,
      fade: fadeElement(backdrop.current),
      expandable: () => el.scrollHeight > el.clientHeight + 1,
    });
  }, [menu, sheet, close]);

  if (!menu) return null;

  /**
   * A click in a menu that leaves focus where it was opened (keepFocus) does
   * not take focus either, so a text box's selection stays in view until the
   * item acts on it. A slider still takes the press it needs to be dragged.
   */
  const keepFocusDown = menu.keepFocus
    ? (e: React.MouseEvent) => {
        if (!(e.target instanceof Element && e.target.closest("input"))) e.preventDefault();
      }
    : undefined;

  /** The keys of one menu: the one opened, or the submenu beside it. */
  const onKeyDown = (inSub: boolean) => (e: React.KeyboardEvent<HTMLDivElement>) => {
    const el = inSub ? subRef.current : ref.current;
    const list = stops(el);
    const at = list.indexOf(document.activeElement as HTMLElement);
    const onRange = document.activeElement instanceof HTMLInputElement && document.activeElement.type === "range";
    // a submenu opens towards the reading direction's end and closes back the other way
    const rtl = document.documentElement.dir === "rtl";
    const inward = rtl ? "ArrowLeft" : "ArrowRight";
    const outward = rtl ? "ArrowRight" : "ArrowLeft";
    if (e.key === "Tab") {
      // A menu is not part of the tab order: Tab dismisses it and puts focus
      // back where it came from, so the next Tab carries on from there.
      e.preventDefault();
      close();
      return;
    }
    if (inSub || drilled) {
      if (e.key === outward) {
        e.preventDefault();
        closeSub(true);
        return;
      }
      if (e.key === inward) {
        e.preventDefault();
        return;
      }
    } else if (e.key === inward || e.key === "Enter" || e.key === " ") {
      const id = (document.activeElement as HTMLElement | null)?.dataset["submenu"];
      const item = id ? menu.items.find((x) => x.id === id) : undefined;
      if (item && document.activeElement instanceof HTMLElement) {
        e.preventDefault();
        openSub(item, document.activeElement, true);
        return;
      }
    }
    if (e.key === "ArrowDown" || (e.key === "ArrowRight" && !onRange)) {
      e.preventDefault();
      moveTo(el, at < 0 ? 0 : at + 1);
      return;
    }
    if (e.key === "ArrowUp" || (e.key === "ArrowLeft" && !onRange)) {
      e.preventDefault();
      moveTo(el, at < 0 ? list.length - 1 : at - 1);
      return;
    }
    if (e.key === "Home") {
      e.preventDefault();
      moveTo(el, 0);
      return;
    }
    if (e.key === "End") {
      e.preventDefault();
      moveTo(el, list.length - 1);
      return;
    }
    if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey && !onRange) {
      const now = Date.now();
      typed.current = { text: (now - typed.current.at < 800 ? typed.current.text : "") + e.key.toLowerCase(), at: now };
      const want = typed.current.text;
      const from = at < 0 ? 0 : want.length === 1 ? at + 1 : at;
      for (let i = 0; i < list.length; i++) {
        const cand = list[(from + i) % list.length]!;
        if ((cand.textContent ?? "").trim().toLowerCase().startsWith(want)) {
          e.preventDefault();
          moveTo(el, (from + i) % list.length);
          return;
        }
      }
    }
  };

  const flyout = sub?.mode === "flyout" ? sub.item : null;

  /** One menu's items; `top` for the menu opened, whose items may open submenus. */
  const list = (items: ContextMenuItem[], top: boolean) =>
    groups(items).map((g, gi) => (
      <div key={gi} role={g.label ? "group" : "none"} {...(g.label ? { "aria-label": g.label } : {})}>
        {g.label ? <div className={s.heading} aria-hidden="true">{g.label}</div> : null}
        {g.items.map((item) => (
          <div key={item.id} role="none">
            {item.slider ? (
              <SliderItem item={{ ...item, slider: item.slider }} />
            ) : item.note ? (
              // read, not chosen, so no stop for the arrows; whoever opens a menu
              // with a note in it says the same thing in the menu's own name
              <div className={s.note} aria-hidden="true">{item.label}</div>
            ) : item.submenu && top ? (
              <button
                role="menuitem"
                aria-haspopup="menu"
                aria-expanded={flyout?.id === item.id}
                {...(flyout?.id === item.id ? { "aria-controls": subId } : {})}
                className={s.item}
                disabled={item.disabled || item.submenu.length === 0}
                data-menu-stop=""
                data-submenu={item.id}
                tabIndex={-1}
                // opened with a key it takes focus; with a click only where the menu took focus itself
                onClick={(e) => openSub(item, e.currentTarget, !menu.keepFocus || e.detail === 0)}
                onPointerEnter={(e) => {
                  if (e.pointerType === "touch" || sheet || drills(pointer.current)) return;
                  if (flyout?.id === item.id) {
                    clearTimer();
                    return;
                  }
                  clearTimer();
                  const el = e.currentTarget;
                  timer.current = setTimeout(() => openSub(item, el, false), OPEN_DELAY);
                }}
              >
                <span className={s.itemLabel}>{item.label}</span>
                <ChevronRight size={16} className={s.chevron} aria-hidden="true" />
              </button>
            ) : (
              <button
                role={item.checked === undefined ? "menuitem" : item.toggle ? "menuitemcheckbox" : "menuitemradio"}
                aria-checked={item.checked}
                className={[s.item, item.danger ? s.danger : "", item.strong ? s.strong : ""].join(" ")}
                disabled={item.disabled}
                data-menu-stop=""
                tabIndex={-1}
                onPointerEnter={(e) => {
                  // on to another item of the menu: an open submenu waits a moment for the pointer, then goes
                  if (!top || !flyout || e.pointerType === "touch") return;
                  clearTimer();
                  timer.current = setTimeout(() => closeSub(false), CLOSE_DELAY);
                }}
                onClick={() => {
                  close();
                  item.onSelect?.();
                }}
              >
                {item.picture ? (
                  <span className={s.itemPicture} aria-hidden="true">{item.picture}</span>
                ) : item.icon ? (
                  <Suspense fallback={<span className={s.itemIcon} />}>
                    <MenuIcon name={item.icon} className={s.itemIcon} />
                  </Suspense>
                ) : null}
                <span className={s.itemLabel}>{item.label}</span>
                {item.warn ? (
                  <span className={s.warn} title={item.warn} data-testid="menu-item-warn">
                    <TriangleAlert size={15} aria-hidden="true" />
                    <span className="visually-hidden">{item.warn}</span>
                  </span>
                ) : item.keys ? (
                  <span className={s.keys} aria-hidden="true">{item.keys}</span>
                ) : null}
                {item.checked === undefined ? null : item.toggle ? (
                  <span className={s.tick} data-on={item.checked} aria-hidden="true">{item.checked ? <Check size={12} strokeWidth={3.5} /> : null}</span>
                ) : (
                  <span className={s.radio} data-on={item.checked} aria-hidden="true" />
                )}
              </button>
            )}
            {item.separatorAfter ? <div className={s.sep} role="separator" /> : null}
          </div>
        ))}
      </div>
    ));

  const notePointer = (e: React.PointerEvent) => {
    pointer.current = e.pointerType || null;
  };

  return createPortal(
    <>
      {/* On a touch screen, tapping away closes the menu without also tapping whatever is behind it. */}
      <div
        ref={backdrop}
        className={s.backdrop}
        data-context-menu
        onClick={close}
        onContextMenu={(e) => {
          e.preventDefault();
          close();
        }}
      />
      <div
        ref={ref}
        className={s.menu}
        data-context-menu
        data-modal-layer=""
        style={{ left: pos?.x ?? menu.x, top: pos?.y ?? menu.y, visibility: pos ? "visible" : "hidden" }}
        role="menu"
        aria-label={drilled ? drilled.label : menu.label}
        onKeyDown={onKeyDown(false)}
        onPointerDownCapture={notePointer}
        onMouseDown={keepFocusDown}
        data-sheet={sheet || undefined}
      >
        {sheet ? <span className={s.grip} aria-hidden="true" /> : null}
        {/* on a phone the menu says what it is for, far from the finger that opened it */}
        {sheet && menu.label && !drilled && !menu.items.some((it) => it.note && it.label === menu.label) ? <div className={s.sheetTitle} aria-hidden="true" dir="auto">{menu.labelNode ?? menu.label}</div> : null}
        {menu.reactions && !drilled ? (
          <div className={s.quick} role="none">
            {(sheet ? menu.reactions.emoji : menu.reactions.emoji.slice(0, 4)).map((e) => (
              <button key={e} role="menuitem" data-menu-stop="" tabIndex={-1} className={s.quickBtn} data-on={menu.reactions!.mine.includes(e) || undefined} aria-label={t("chat.reactions.reactWith", { emoji: e })} onClick={() => { const r = menu.reactions!; close(); r.pick(e); }}>
                <Twemoji text={e} />
              </button>
            ))}
            {/* beside a mouse, Add Reaction is the item right under the row */}
            {sheet ? <button role="menuitem" data-menu-stop="" tabIndex={-1} className={s.quickBtn} aria-label={t("chat.reactions.addReaction")} onClick={() => { const r = menu.reactions!; close(); r.more(); }}>
              <SmilePlus size={22} aria-hidden="true" />
            </button> : null}
          </div>
        ) : null}
        {drilled ? (
          <>
            <div role="none">
              {/* the way back up, where a finger expects it: at the top */}
              <button role="menuitem" className={[s.item, s.back].join(" ")} data-menu-stop="" tabIndex={-1} onClick={() => closeSub(true)} data-testid="menu-back">
                <ChevronLeft size={16} className={s.chevron} aria-hidden="true" />
                <span className={s.itemLabel}>{t("contextMenu.back")}</span>
              </button>
            </div>
            {list([{ id: `${drilled.id}:heading`, label: drilled.label, heading: true }, ...(drilled.submenu ?? [])], false)}
          </>
        ) : (
          list(menu.items, true)
        )}
      </div>
      {flyout ? (
        <div
          ref={subRef}
          id={subId}
          className={s.menu}
          data-context-menu
          data-modal-layer=""
          data-submenu-of={flyout.id}
          style={{ left: subPos?.x ?? 0, top: subPos?.y ?? 0, visibility: subPos ? "visible" : "hidden" }}
          role="menu"
          aria-label={flyout.label}
          onKeyDown={onKeyDown(true)}
          onPointerDownCapture={notePointer}
          onMouseDown={keepFocusDown}
          onPointerEnter={clearTimer}
        >
          {list(flyout.submenu ?? [], false)}
        </div>
      ) : null}
    </>,
    portalRoot,
  );
}
