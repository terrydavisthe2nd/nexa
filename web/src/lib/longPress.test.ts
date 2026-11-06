// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HOLD_MS, installLongPress } from "./longPress";

function touch(target: Element, type: string, opts: { fingers?: number; cancelable?: boolean } = {}): void {
  const e = new Event(type, { bubbles: true, cancelable: opts.cancelable ?? true });
  const list = Array.from({ length: opts.fingers ?? 0 }, () => ({ clientX: 10, clientY: 10 }));
  Object.defineProperty(e, "touches", { value: list });
  target.dispatchEvent(e);
}

/** Whether a click the browser made at `target` reached the app. */
function clickArrives(target: Element): boolean {
  const heard = vi.fn();
  document.body.addEventListener("click", heard);
  target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
  document.body.removeEventListener("click", heard);
  return heard.mock.calls.length > 0;
}

describe("long press", () => {
  let row: HTMLElement;
  let item: HTMLElement;
  let uninstall: () => void;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    document.body.innerHTML = `<div id="row"></div><button id="item"></button>`;
    row = document.getElementById("row")!;
    item = document.getElementById("item")!;
    uninstall = installLongPress(document, () => true);
  });

  afterEach(() => {
    uninstall();
    vi.useRealTimers();
  });

  const hold = (): void => {
    touch(row, "touchstart", { fingers: 1 });
    vi.advanceTimersByTime(HOLD_MS);
  };

  // what an iPhone did: the lift pressed the menu item that had slid in under the finger
  it("keeps the lift from pressing what opened under the finger", () => {
    hold();
    touch(row, "touchend");
    expect(clickArrives(item)).toBe(false);
  });

  // a finger that drifted started a scroll, and then the touchend cannot be cancelled
  it("does so when the touchend cannot be cancelled, or the touch was cancelled", () => {
    hold();
    touch(row, "touchend", { cancelable: false });
    expect(clickArrives(item)).toBe(false);

    hold();
    touch(row, "touchcancel", { cancelable: false });
    expect(clickArrives(item)).toBe(false);
  });

  it("lets the next touch press the menu straight away", () => {
    hold();
    touch(row, "touchend");
    touch(item, "touchstart", { fingers: 1 });
    touch(item, "touchend");
    expect(clickArrives(item)).toBe(true);
  });

  it("leaves an ordinary tap alone", () => {
    touch(row, "touchstart", { fingers: 1 });
    touch(row, "touchend");
    expect(clickArrives(row)).toBe(true);
  });
});
