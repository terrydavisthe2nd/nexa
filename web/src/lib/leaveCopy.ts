// Something that closes (a dialog, Settings, the picture viewer) fades away
// instead of vanishing: as React removes it, a copy is left where it stood and
// animated out. The real thing is already gone, so nothing in the copy can be
// pressed, typed into, focused or read: it is inert and hidden from assistive
// technology, and it no longer counts as an open dialog to the code that asks
// ([data-modal-layer], role=dialog). Nothing inside it starts over or eases
// ([data-leaving] in base.css), so it leaves exactly as it looked.
import { motionOff } from "@/lib/motion";

/** Leaving, where `part` (the card of a dialog) moves as the whole fades. */
export interface Leave {
  ms: number;
  /** How the whole copy goes; it fades out when not given. */
  frames?: Keyframe[];
  /** A part that moves on its own way out, and how. */
  part?: { el: HTMLElement | null | undefined; frames: Keyframe[] };
  /**
   * Where the copy goes when not beside the original: next to a wrapper that
   * is itself about to be removed (a dialog's own container), or the copy
   * would go with it.
   */
  beside?: Element;
  /** Told when the copy is gone. */
  onGone?: () => void;
}

// Gone from the copy: what would make it answer to code, labels or forms as the real thing does.
const DROP = ["id", "name", "for", "data-modal-layer", "data-modal-overlay", "aria-modal", "aria-labelledby", "aria-describedby", "aria-controls", "aria-activedescendant"];
const EASE_IN = "cubic-bezier(0.4, 0, 1, 1)";

/**
 * Leaves a fading copy of `el` in its place and returns it (or null when it
 * just goes). It just goes under Reduce Motion, in a hidden window, and when a
 * copy could not look the same: a drawing, a video or another page inside
 * would come up blank for its moment.
 */
export function leaveCopy(el: HTMLElement, how: Leave): HTMLElement | null {
  if (!el.isConnected || motionOff() || document.hidden || typeof el.animate !== "function") return null;
  if (el.querySelector("canvas, video, iframe, object, embed")) return null;
  const copy = el.cloneNode(true) as HTMLElement;
  // what typing, choosing and scrolling changed is not in the markup a copy is made from
  const from = [el, ...el.querySelectorAll<HTMLElement>("*")];
  const to = [copy, ...copy.querySelectorAll<HTMLElement>("*")];
  for (const c of to) {
    for (const name of DROP) c.removeAttribute(name);
    if (c.getAttribute("role") === "dialog") c.removeAttribute("role");
  }
  copy.setAttribute("data-leaving", "");
  copy.setAttribute("aria-hidden", "true");
  copy.setAttribute("inert", "");
  const anchor = how.beside ?? el;
  if (!anchor.parentNode) return null;
  anchor.parentNode.insertBefore(copy, anchor);
  const scrolled: [HTMLElement, number, number][] = [];
  let part: HTMLElement | null = null;
  from.forEach((a, i) => {
    const b = to[i];
    if (!b) return;
    if (a === how.part?.el) part = b;
    if (a instanceof HTMLInputElement && b instanceof HTMLInputElement) {
      if (a.type === "checkbox" || a.type === "radio") b.checked = a.checked;
      else if (a.type !== "file") b.value = a.value;
    } else if (a instanceof HTMLTextAreaElement && b instanceof HTMLTextAreaElement) b.value = a.value;
    else if (a instanceof HTMLSelectElement && b instanceof HTMLSelectElement) b.value = a.value;
    if (a.scrollTop || a.scrollLeft) scrolled.push([b, a.scrollTop, a.scrollLeft]);
  });
  for (const [b, top, left] of scrolled) {
    b.scrollTop = top;
    b.scrollLeft = left;
  }
  const timing: KeyframeAnimationOptions = { duration: how.ms, easing: EASE_IN, fill: "forwards" };
  const run = copy.animate(how.frames ?? [{ opacity: 1 }, { opacity: 0 }], timing);
  if (part && how.part) (part as HTMLElement).animate(how.part.frames, timing);
  let gone = false;
  const go = () => {
    if (gone) return;
    gone = true;
    copy.remove();
    how.onGone?.();
  };
  // Gone when its fade ends, however long the page took to get there; a window
  // hidden part way holds its animations, so a timer sees to it in the end.
  run.finished.then(go, go);
  window.setTimeout(go, how.ms + 2000);
  return copy;
}
