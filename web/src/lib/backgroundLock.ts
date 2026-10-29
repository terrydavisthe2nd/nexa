/**
 * A Web Lock held for as long as this page is in a call. Chromium's page
 * freezing and WebKit's process suppression both leave a page that holds a
 * lock alone, which keeps a hidden call's timers running where the desktop
 * shell cannot switch background throttling off (Linux WebKitGTK, browsers,
 * and macOS before 14). It is released when the call ends, so an idle page is
 * still put to sleep.
 */
let release: (() => void) | null = null;

export function holdCallLock(): void {
  if (release) return;
  if (typeof navigator === "undefined" || !navigator.locks) return;
  let finish = () => {};
  const held = new Promise<void>((resolve) => {
    finish = resolve;
  });
  let active = true;
  release = () => {
    if (!active) return;
    active = false;
    release = null;
    finish();
  };
  navigator.locks.request("nexa-call", { mode: "shared" }, () => held).catch(() => {
    if (active) release = null;
  });
}

export function releaseCallLock(): void {
  release?.();
}
