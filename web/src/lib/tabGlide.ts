// The chosen tab's pill slides from the tab that was chosen to the new one,
// in every tab strip of the app (and in the segmented switches that ask for it
// with data-glide on their radiogroup): one observer watches aria-selected and
// aria-checked, so no strip needs code of its own.
//
// While it moves, a stand-in drawn with the tab's own look travels behind the
// words and the new tab's background stands aside ([data-gliding] in
// base.css). When it lands, the tab takes its look back with every transition
// held, so nothing fades in a second time: handing it back with transitions
// running made the pill flash once as it landed.
//
// A strip opts out with data-no-glide (the window's tab bar, whose tabs join
// the page below them).
import { motionOff } from "@/lib/motion";

type Look = { backgroundColor: string; backgroundImage: string; boxShadow: string; borderRadius: string };
type Glider = { el: HTMLElement; run: Animation | null };
type Box = { x: number; y: number; w: number; h: number };

const EASE = "cubic-bezier(0.22, 1, 0.36, 1)";
const gliders = new WeakMap<HTMLElement, Glider>();
/** The chosen item and its look, read as a press or key begins, before the choice moves. */
const before = new WeakMap<HTMLElement, { item: Element; look: Look }>();

function groupOf(el: Element): HTMLElement | null {
  const role = el.getAttribute("role");
  if (role === "tab") {
    const list = el.closest<HTMLElement>('[role="tablist"]');
    return list && !list.hasAttribute("data-no-glide") ? list : null;
  }
  if (role === "radio") {
    const group = el.closest<HTMLElement>('[role="radiogroup"]');
    return group?.hasAttribute("data-glide") ? group : null;
  }
  return null;
}

function isChosen(el: Element): boolean {
  return el.getAttribute(el.getAttribute("role") === "tab" ? "aria-selected" : "aria-checked") === "true";
}

function chosenIn(group: HTMLElement): Element | undefined {
  return [...group.querySelectorAll('[role="tab"][aria-selected="true"], [role="radio"][aria-checked="true"]')].find((x) => groupOf(x) === group);
}

function lookOf(el: Element): Look {
  const cs = getComputedStyle(el);
  return { backgroundColor: cs.backgroundColor, backgroundImage: cs.backgroundImage, boxShadow: cs.boxShadow, borderRadius: cs.borderRadius };
}

/** A look with nothing to slide: the strip marks its choice some other way (an underline). */
function bare(l: Look): boolean {
  return l.backgroundImage === "none" && l.boxShadow === "none" && /^(transparent|rgba\(0, 0, 0, 0\))$/.test(l.backgroundColor);
}

/** Lets the group's items have their transitions again once their styles are settled, so nothing animates. */
function release(group: HTMLElement): void {
  void group.offsetWidth; // style everything now, while still held
  group.removeAttribute("data-glide-hold");
}

function glide(group: HTMLElement, from: Element, to: Element): void {
  if (motionOff() || typeof group.animate !== "function") return;
  // a different kind of item (Friends' Add Friend, a button of its own) keeps its own way of showing it is chosen
  if (from.className !== to.className) return;
  if (getComputedStyle(group).display === "contents") return;
  const g0 = gliders.get(group);
  const running = g0?.run?.playState === "running";

  // the new item's settled look, read with its transitions held
  group.setAttribute("data-glide-hold", "");
  const endLook = lookOf(to);
  if (bare(endLook)) {
    release(group);
    return;
  }
  // where the pill is coming from, and how it looked there
  const kept = before.get(group);
  before.delete(group);
  const startLook = running ? lookOf(g0!.el) : kept?.item === from ? kept.look : endLook;
  const startRect = running ? g0!.el.getBoundingClientRect() : from.getBoundingClientRect();
  g0?.run?.cancel();

  let g = g0;
  if (!g || g.el.parentElement !== group) {
    const el = document.createElement("span");
    el.setAttribute("aria-hidden", "true");
    el.setAttribute("data-tab-glider", "");
    Object.assign(el.style, { position: "absolute", left: "0", top: "0", zIndex: "-1", pointerEvents: "none", visibility: "hidden", boxSizing: "border-box" });
    group.appendChild(el);
    g = { el, run: null };
    gliders.set(group, g);
  }
  const glider = g;
  // Still, the glider sits at the top left corner of whatever box holds it, so
  // its own position is the origin, however that box is placed, scrolled or
  // transformed; the strip's own layout is never touched (making it a
  // positioned box once moved Friends' Add Friend over the tabs).
  const o = glider.el.getBoundingClientRect();
  const at = (r: DOMRect): Box => ({ x: r.left - o.left, y: r.top - o.top, w: r.width, h: r.height });
  const a = at(startRect);
  const b = at(to.getBoundingClientRect());
  const far = Math.hypot(a.x - b.x, a.y - b.y);
  if (a.w === 0 || b.w === 0 || far < 1) {
    group.removeAttribute("data-gliding");
    glider.el.style.visibility = "hidden";
    release(group);
    return;
  }

  // the stand-in takes over: the strip is isolated so the glider, under the
  // words at z-index -1, stays above the strip's own background
  Object.assign(glider.el.style, { backgroundImage: endLook.backgroundImage, borderRadius: endLook.borderRadius, visibility: "visible" });
  group.style.isolation = "isolate";
  group.setAttribute("data-gliding", "");
  release(group);

  const frame = (p: Box, l: Look): Keyframe => ({ transform: `translate(${p.x}px, ${p.y}px)`, width: `${p.w}px`, height: `${p.h}px`, backgroundColor: l.backgroundColor, boxShadow: l.boxShadow });
  // a longer way takes a little longer, within a quarter to a third of a second
  const duration = Math.round(Math.min(320, Math.max(220, 190 + far * 0.25)));
  const run = glider.el.animate([frame(a, startLook), frame(b, endLook)], { duration, easing: EASE, fill: "forwards" });
  glider.run = run;
  run.onfinish = () => {
    if (glider.run !== run) return;
    glider.run = null;
    // the tab takes its look back with transitions held, in the same frame the stand-in goes
    group.setAttribute("data-glide-hold", "");
    group.removeAttribute("data-gliding");
    group.style.isolation = "";
    glider.el.style.visibility = "hidden";
    run.cancel();
    release(group);
  };
}

export function installTabGlide(root: Node = document.body): () => void {
  if (typeof MutationObserver !== "function" || typeof document === "undefined") return () => {};
  // The chosen item's look is read just before a press or a key can move the
  // choice, so the pill can change colour on its way (Allow to Deny).
  const remember = (e: Event) => {
    const t = e.target instanceof Element ? e.target : null;
    const group = t?.closest<HTMLElement>('[role="tablist"]:not([data-no-glide]), [role="radiogroup"][data-glide]');
    if (!group || group.hasAttribute("data-gliding")) return;
    const item = chosenIn(group);
    if (item) before.set(group, { item, look: lookOf(item) });
  };
  document.addEventListener("pointerdown", remember, true);
  document.addEventListener("keydown", remember, true);
  const mo = new MutationObserver((records) => {
    const moves = new Map<HTMLElement, { from?: Element; to?: Element }>();
    for (const r of records) {
      const item = r.target as Element;
      const group = groupOf(item);
      if (!group) continue;
      const now = isChosen(item);
      if (now === (r.oldValue === "true")) continue;
      const m = moves.get(group) ?? {};
      if (now) m.to = item;
      else m.from = item;
      moves.set(group, m);
    }
    for (const [group, { from, to }] of moves) if (from?.isConnected && to?.isConnected) glide(group, from, to);
  });
  mo.observe(root, { subtree: true, attributes: true, attributeFilter: ["aria-selected", "aria-checked"], attributeOldValue: true });
  return () => {
    mo.disconnect();
    document.removeEventListener("pointerdown", remember, true);
    document.removeEventListener("keydown", remember, true);
  };
}
