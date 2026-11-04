// Motion answers what a person did to one thing (a tab, a reaction, a
// category): never a fade or dim over a whole view, which reads as the page
// blinking. Every helper stands still under Reduce Motion (the system's or
// Nexa's own).

export function motionOff(): boolean {
  if (typeof document === "undefined") return true;
  if (document.documentElement.dataset["reducedMotion"] === "true") return true;
  return typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * When each conversation's message list was opened (server time), so only
 * what arrives while it is open rises into place (MessageItem's fresh), not
 * what came in just before it was opened.
 */
export const listOpenedAt = new Map<string, number>();
