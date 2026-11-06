/**
 * Long-press opens context menus on touch screens.
 *
 * Every menu in the app listens for `contextmenu`. A mouse sends one on right
 * click, but a finger mostly does not: iOS never turns a long press into one,
 * and Android only sometimes does. So a touch held still for a moment sends
 * one itself, at the finger, to whatever is under it, and everything that has
 * a right-click menu gets the same menu on a long press.
 */

/** How long a finger has to stay down. */
export const HOLD_MS = 450;
/** How far it may wander and still be holding still. */
const SLOP_PX = 10;
/** Where a long press already means something: selecting text, a native control. */
const NATIVE = "input, textarea, select, [contenteditable=''], [contenteditable='true'], [data-native-press]";
/** How long after the lift a click can still be the lift's own. A new touch ends this sooner. */
const LIFT_MS = 700;
/** What a browser makes of a lifted finger. */
const LIFT_MOUSE = ["mousedown", "mouseup", "click"] as const;

/**
 * `fromBrowser` tells the browser's own contextmenu events from the one sent
 * here; it is only ever replaced by tests, which cannot forge `isTrusted`.
 */
export function installLongPress(doc: Document = document, fromBrowser: (e: Event) => boolean = (e) => e.isTrusted): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let start: { x: number; y: number; target: Element } | null = null;
  /** A finger is down. */
  let pressing = false;
  /** This press has opened a menu, so lifting the finger must not tap as well. */
  let opened = false;
  /** When the lift of such a press stops owning the clicks that come, on the events' clock. */
  let liftOwnsUntil = -Infinity;

  const cancel = (): void => {
    if (timer) clearTimeout(timer);
    timer = null;
    start = null;
  };

  const onStart = (e: TouchEvent): void => {
    cancel();
    pressing = true;
    opened = false;
    // whatever this touch clicks is its own
    liftOwnsUntil = -Infinity;
    const t = e.touches[0];
    const target = e.target instanceof Element ? e.target : null;
    if (e.touches.length !== 1 || !t || !target || target.closest(NATIVE)) return;
    start = { x: t.clientX, y: t.clientY, target };
    timer = setTimeout(() => {
      const s = start;
      timer = null;
      start = null;
      if (!s || !s.target.isConnected) return;
      opened = true;
      const menu = new MouseEvent("contextmenu", { bubbles: true, cancelable: true, composed: true, clientX: s.x, clientY: s.y, button: 2 });
      s.target.dispatchEvent(menu);
      if (menu.defaultPrevented) {
        try {
          navigator.vibrate?.(8);
        } catch {
          /* no haptics here */
        }
      }
    }, HOLD_MS);
  };

  const onMove = (e: TouchEvent): void => {
    const t = e.touches[0];
    if (!start || !t) return;
    if (Math.hypot(t.clientX - start.x, t.clientY - start.y) > SLOP_PX) cancel();
  };

  const onEnd = (e: TouchEvent): void => {
    cancel();
    if (e.touches.length > 0) return;
    pressing = false;
    // the menu is open now; the lift must not also press what was under the finger
    if (opened) {
      if (e.cancelable) e.preventDefault();
      // Not enough alone: a finger that drifted has started a scroll, and then
      // this cannot be cancelled. iPhones have pressed the item that slid in
      // under the finger, or closed the sheet by pressing the backdrop.
      liftOwnsUntil = e.timeStamp + LIFT_MS;
    }
    opened = false;
  };

  const onLiftMouse = (e: Event): void => {
    if (!fromBrowser(e) || e.timeStamp >= liftOwnsUntil) return;
    e.preventDefault();
    e.stopImmediatePropagation();
  };

  const onNativeMenu = (e: Event): void => {
    if (!fromBrowser(e) || !pressing) return; // a right click, or the one sent above
    if (opened) {
      // this press already opened its menu
      e.preventDefault();
      e.stopImmediatePropagation();
      return;
    }
    // the browser got there first; let its one through instead
    cancel();
    opened = true;
  };

  const capture = { capture: true } as const;
  const passive = { capture: true, passive: true } as const;
  doc.addEventListener("touchstart", onStart, passive);
  doc.addEventListener("touchmove", onMove, passive);
  doc.addEventListener("touchend", onEnd, { capture: true, passive: false });
  doc.addEventListener("touchcancel", onEnd, passive);
  doc.addEventListener("scroll", cancel, passive);
  doc.addEventListener("contextmenu", onNativeMenu, capture);
  for (const type of LIFT_MOUSE) doc.addEventListener(type, onLiftMouse, capture);
  return () => {
    cancel();
    for (const type of LIFT_MOUSE) doc.removeEventListener(type, onLiftMouse, capture);
    doc.removeEventListener("touchstart", onStart, passive);
    doc.removeEventListener("touchmove", onMove, passive);
    doc.removeEventListener("touchend", onEnd, { capture: true });
    doc.removeEventListener("touchcancel", onEnd, passive);
    doc.removeEventListener("scroll", cancel, passive);
    doc.removeEventListener("contextmenu", onNativeMenu, capture);
  };
}
