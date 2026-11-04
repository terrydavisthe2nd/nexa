import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { usePortalRoot } from "@/hooks/usePortalRoot";
import { attachSheetDrag, fadeOverlay, sheetsHere } from "@/lib/sheetDrag";
import { leaveCopy } from "@/lib/leaveCopy";
import s from "./Modal.module.css";

import { t } from "@/i18n";
/** Only the most recently opened dialog reacts to Escape; portals mount in order, so the last in the DOM is on top. */
export function isTopLayer(el: HTMLElement | null): boolean {
  if (!el) return false;
  const layers = document.querySelectorAll("[data-modal-layer]");
  return layers[layers.length - 1] === el;
}

/** Copies of dialogs that have closed, still fading away (leave). */
const leaving = new Set<HTMLElement>();

/**
 * As a dialog goes, a copy of it fades away where it stood (lib/leaveCopy),
 * mirroring how it came: a card shrinks a little as it fades, a phone's sheet
 * drops. A sheet that was swiped away has already left.
 */
function leave(host: HTMLElement): void {
  const overlay = host.firstElementChild as HTMLElement | null;
  const card = overlay?.querySelector<HTMLElement>("[data-modal-layer]");
  if (!overlay || !card || card.style.transform || overlay.style.getPropertyValue("--sheet-dim")) return;
  const sheet = sheetsHere() && !overlay.hasAttribute("data-centered");
  const ms = sheet ? 200 : 140;
  // beside the dialog's own container, which goes as soon as this returns
  const copy: HTMLElement | null = leaveCopy(overlay, { ms, beside: host, onGone: () => copy && leaving.delete(copy), part: { el: card, frames: [{ transform: "none" }, { transform: sheet ? "translateY(48px)" : "translateY(4px) scale(0.97)" }] } });
  if (copy) leaving.add(copy);
}

/**
 * A dialog that opens while another's copy is still fading takes over the dim
 * at once rather than fading in a second one over it (which made the page
 * behind darken and lighten again). The copy stays above it, so nothing is
 * dimmed twice, and hurries out at twice the pace, so the two are seen
 * together for only a moment: the one dialog crossfades into the next.
 */
function arrive(host: HTMLElement): void {
  if (leaving.size === 0) return;
  (host.firstElementChild as HTMLElement | null)?.setAttribute("data-calm", "");
  for (const ghost of leaving) {
    if (!ghost.isConnected || ghost.parentNode !== host.parentNode) continue;
    ghost.style.background = "transparent";
    ghost.style.backdropFilter = "none";
    ghost.style.setProperty("-webkit-backdrop-filter", "none");
    host.parentNode?.appendChild(ghost);
    for (const run of ghost.getAnimations({ subtree: true })) run.updatePlaybackRate(2);
  }
}

interface Props {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
  closeOnOverlay?: boolean;
  className?: string;
  bare?: boolean;
  /**
   * What the dialog is called, for anyone who cannot see it. A titled dialog
   * takes its name from its own heading; a `bare` one has no heading to take it
   * from, so it has to be told, or it is announced as "dialog" and nothing else.
   */
  ariaLabel?: string;
  /** Whether the title bar has an ✕; off for a dialog that has to be answered. */
  closeButton?: boolean;
  /**
   * What Enter in one of the dialog's text boxes does — its main action, as a
   * form's Enter does. A phone keyboard's Go key is Enter too; without this it
   * did nothing, and the keyboard had to be put away to reach the button.
   * Called whatever the button's state, so it checks what the button checks.
   */
  onSubmit?: () => void;
  /**
   * Stays in the middle of the screen on a phone too, rather than rising from
   * the bottom as a sheet: for the few dialogs that are news rather than a
   * task, such as an update being ready.
   */
  centered?: boolean;
  /**
   * Whether a phone's sheet follows the finger: pulled up to the full height
   * when it has more to show, swiped down to close (lib/sheetDrag.ts). Off
   * for a sheet that is all writing surface, where a drag is meant for the text.
   */
  swipe?: boolean;
}

/** Single-line boxes, where Enter has nothing else to do. */
const SUBMITS_ON_ENTER = new Set(["text", "password", "email", "search", "url", "tel", "number"]);

/** What Tab can reach, for the trap below. */
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
/** Somewhere to type: where a dialog that asks for something starts. Not a radio, a switch or a file input. */
const TEXT_FIELD = "input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=file]):not([type=range]):not([disabled]), textarea:not([disabled])";

/**
 * Of these, the ones laid out — or all of them where nothing is, which is a
 * page without layout (jsdom, a print preview) as often as a hidden section;
 * focusing one that cannot take it is caught where this is used.
 */
function shown<T extends Element>(all: T[]): T[] {
  const laidOut = all.filter((el) => el.getClientRects().length > 0);
  return laidOut.length > 0 ? laidOut : all;
}

export function Modal({ open, onClose, title, children, footer, width = 440, closeOnOverlay = true, className, bare = false, ariaLabel, closeButton = true, onSubmit, centered = false, swipe: swipeAsked }: Props) {
  // a dialog that has to be answered is not swiped away either
  const swipe = swipeAsked ?? closeButton;
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const portalRoot = usePortalRoot();
  // This dialog's own container, kept for its whole life and moved between the
  // body and the fullscreen element rather than swapped. Handing createPortal a
  // different container makes React unmount and rebuild everything inside it,
  // so a name half typed into the dialog vanished when the stream behind it
  // left full screen.
  const [host] = useState<HTMLDivElement | null>(() => {
    if (typeof document === "undefined") return null;
    const el = document.createElement("div");
    el.style.display = "contents";
    return el;
  });
  // As it closes, a copy fades away where it stood (leave). Its own effect,
  // ahead of the one below, so it runs while the dialog is still in place, and
  // only when the dialog goes: the one below also runs when the dialog moves
  // into or out of a full-screen element.
  useLayoutEffect(() => {
    if (!host || !open) return;
    return () => leave(host);
  }, [host, open]);
  useLayoutEffect(() => {
    if (!host || !open) return;
    portalRoot.append(host);
    arrive(host);
    return () => host.remove();
  }, [host, portalRoot, open]);
  // Held in a ref so a caller passing a fresh function on every render does
  // not re-run the effect below: that put the caret back on the dialog after
  // every keystroke, so a field could only ever take one character.
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  // What had focus as the dialog was first drawn, for when something inside
  // has already taken focus by the time the effect below runs: handing focus
  // back to that, gone with the dialog, left it nowhere.
  const [opener] = useState<HTMLElement | null>(() => (typeof document === "undefined" ? null : (document.activeElement as HTMLElement | null)));
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (!isTopLayer(ref.current)) return;
      if (e.key === "Escape") {
        e.stopPropagation();
        closeRef.current();
        return;
      }
      // aria-modal says the rest of the page is out of reach, and clicks are
      // blocked by the overlay, but Tab walked straight through it into the
      // channel list behind, where Enter navigated away with the dialog still up
      if (e.key !== "Tab") return;
      const card = ref.current;
      if (!card) return;
      const all = [...card.querySelectorAll<HTMLElement>(FOCUSABLE)];
      // Laid-out stops only, so a hidden tab's contents are not in the cycle —
      // but if nothing at all reports a box, the environment is not doing
      // layout (jsdom, a print preview) rather than the dialog being empty,
      // and letting focus escape would be the worse answer.
      const shown = all.filter((el) => el.getClientRects().length > 0);
      const stops = shown.length > 0 ? shown : all;
      if (stops.length === 0) return;
      const first = stops[0]!;
      const last = stops[stops.length - 1]!;
      const active = document.activeElement as HTMLElement | null;
      if (!card.contains(active)) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus();
      } else if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    const card = ref.current;
    const active = document.activeElement as HTMLElement | null;
    // Focus somewhere else came back there from a menu this dialog was chosen
    // in (a menu hands focus back to what it opened from); focus already
    // inside belongs to whatever opened the dialog.
    const prev = active && card && !card.contains(active) ? active : opener;
    // Start in the field to type into, so a dialog that asks for something is
    // ready for it — unless something inside has already taken focus. Not on
    // a touch screen, where focusing a field opens the keyboard over the
    // dialog before anyone has read it. The first field used to be any input
    // at all: a radio button ahead of the name to type, or the hidden file
    // input of an icon picker, which cannot take focus and so left it on the
    // page behind the dialog. (React's autoFocus is no help here: it fires
    // before this dialog's container is in the document.)
    const touch = typeof window !== "undefined" && !!window.matchMedia?.("(pointer: coarse)").matches;
    const chosen = !touch && card && active && active !== card && card.contains(active);
    if (!chosen && card) {
      const field = touch ? null : shown([...card.querySelectorAll<HTMLElement>(TEXT_FIELD)])[0];
      // A dialog in the homepage preview must not scroll its embedding page.
      field?.focus({ preventScroll: true });
      if (!field || document.activeElement !== field) card.focus({ preventScroll: true });
    }
    return () => {
      document.removeEventListener("keydown", onKey);
      if (prev && prev.isConnected) prev.focus?.({ preventScroll: true });
    };
    // the opener is read once, as the dialog is first drawn
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  // On a phone the dialog is a sheet that follows the finger.
  useEffect(() => {
    const card = ref.current;
    if (!open || centered || !swipe || !card || !sheetsHere()) return;
    return attachSheetDrag({
      sheet: card,
      onClose: () => closeRef.current(),
      fade: fadeOverlay(card.parentElement),
      // only a sheet with more in it than it shows has anywhere to grow to
      expandable: () => {
        const body = card.querySelector<HTMLElement>(`:scope > .${s.body}, :scope > .${s.bareBody}`);
        return !!body && body.scrollHeight > body.clientHeight + 1;
      },
    });
  }, [open, centered, swipe, host]);
  if (!open || !host) return null;
  const onKeyDown = onSubmit
    ? (e: React.KeyboardEvent<HTMLDivElement>) => {
        // Not while an input method is composing (Enter picks the word), not
        // from a field that has already dealt with it, not Shift+Enter.
        if (e.key !== "Enter" || e.shiftKey || e.defaultPrevented || e.nativeEvent.isComposing || e.keyCode === 229) return;
        const el = e.target as HTMLElement;
        if (!(el instanceof HTMLInputElement) || !SUBMITS_ON_ENTER.has(el.type) || el.getAttribute("aria-expanded") === "true") return;
        e.preventDefault();
        onSubmit();
      }
    : undefined;
  return createPortal(
    <div className={s.overlay} data-modal-overlay="" data-centered={centered || undefined} onMouseDown={(e) => { if (closeOnOverlay && e.target === e.currentTarget) onClose(); }}>
      <div
        ref={ref}
        className={[s.card, bare ? s.bare : "", className ?? ""].join(" ")}
        style={{ width }}
        role="dialog"
        aria-modal="true"
        {...(!bare && title !== undefined ? { "aria-labelledby": titleId } : { "aria-label": ariaLabel ?? (typeof title === "string" ? title : undefined) })}
        tabIndex={-1}
        data-modal-layer=""
        data-swipe={swipe && !centered ? "" : undefined}
        onKeyDown={onKeyDown}
      >
        {/* the handle of a sheet with no title bar to show one (a profile, a picture of its own) */}
        {bare && swipe && !centered ? <span className={s.grip} aria-hidden="true" /> : null}
        {!bare && title !== undefined ? (
          <div className={s.header}>
            <h2 className={s.title} id={titleId}>{title}</h2>
            {closeButton ? (
              <button className={s.close} onClick={onClose} aria-label={t("chat.attachments.close")}>
                <X size={20} />
              </button>
            ) : null}
          </div>
        ) : null}
        <div className={bare ? s.bareBody : s.body}>{children}</div>
        {footer ? <div className={s.footer}>{footer}</div> : null}
      </div>
    </div>,
    host,
  );
}
