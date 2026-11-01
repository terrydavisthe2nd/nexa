// @vitest-environment jsdom
import { cleanup, fireEvent, render } from "@testing-library/react";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Channel } from "@/api/types";
import { useMessages, type DecryptedMessage } from "@/stores/messages";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { MessageList } from "./MessageList";

// The list only ever asks a row where it is and what its id is, so a stand-in
// keeps these tests about scrolling rather than about markdown and avatars. It
// says whether the list counts it as the row being looked at.
vi.mock("./MessageItem", async () => {
  const { useRowActive } = await import("./activeRow");
  return {
    MessageItem: ({ msg }: { msg: DecryptedMessage }) => <div data-id={msg.id} data-row="" tabIndex={-1} data-testid="row" data-active={useRowActive(msg.id)} />,
  };
});

const CHANNEL = "700";
const ME = "1";
const THEM = "2";

const channel: Channel = {
  id: CHANNEL,
  server_id: null,
  type: "dm",
  name: "",
  topic: "",
  position: 0,
  parent_id: null,
  nsfw: false,
  slowmode_seconds: 0,
  user_limit: 0,
  owner_id: null,
  icon_id: null,
  last_message_id: null,
  created_at: 0,
  recipient_ids: [ME, THEM],
  key_conv_id: CHANNEL,
};

function msg(id: string, author = THEM): DecryptedMessage {
  return {
    id,
    channel_id: CHANNEL,
    author_id: author,
    type: "default",
    ciphertext: null,
    key_conv_id: null,
    key_epoch: null,
    reply_to_id: null,
    mentions: [],
    mention_roles: [],
    mention_everyone: false,
    attachments: [],
    reactions: [],
    flags: 0,
    edited_at: null,
    created_at: Number(id),
    payload: { t: `m${id}` },
  };
}

// --- a fake layout ---------------------------------------------------------
// jsdom measures nothing: every box is zero by zero and scrollTop never moves.
// Anchoring is entirely about measurements, so one is supplied here — a box of
// a fixed height, rows of a fixed height each, and the list scrolled from its
// bottom as `.scroll`'s column-reverse box scrolls it: scrollTop is 0 with the
// newest message at the bottom of the box and goes negative above it, and a
// list shorter than its box sits at the bottom of it.

const BOX = 400;
const ROW = 40;
/** The spacer or spinner the list keeps above its first row. */
const HEAD = 24;

/** Rows taller than the usual, by id: a picture that has just finished loading. */
const heights = new Map<string, number>();

let scroller: HTMLElement;
let scrollTop = 0;
const realRect = HTMLElement.prototype.getBoundingClientRect;

const rows = (): HTMLElement[] => [...scroller.querySelectorAll<HTMLElement>("[data-id]")];
const heightOf = (row: HTMLElement): number => heights.get(row.dataset.id!) ?? ROW;
const content = (): number => rows().reduce((h, r) => h + heightOf(r), HEAD);
const maxScroll = (): number => Math.max(0, content() - BOX);
/** Where the top of the list is, with the reader `-scrollTop` above its bottom. */
const contentTop = (): number => BOX - scrollTop - content();

/** How far the reader is below the top of what is loaded: how a drag up through history is felt. */
const fromTheTop = (): number => maxScroll() + scrollTop;

function rect(top: number, height: number): DOMRect {
  return { top, bottom: top + height, height, left: 0, right: 0, width: 0, x: 0, y: top, toJSON: () => ({}) } as DOMRect;
}

function measure(): void {
  Object.defineProperty(scroller, "clientHeight", { configurable: true, get: () => BOX });
  Object.defineProperty(scroller, "scrollHeight", { configurable: true, get: () => Math.max(BOX, content()) });
  Object.defineProperty(scroller, "scrollTop", {
    configurable: true,
    // a list that got shorter cannot stay scrolled past its new top: the browser clamps it, as here
    get: () => (scrollTop = Math.max(-maxScroll(), scrollTop)),
    set: (v: number) => {
      scrollTop = Math.max(-maxScroll(), Math.min(Math.round(v), 0));
    },
  });
  HTMLElement.prototype.getBoundingClientRect = function (this: HTMLElement): DOMRect {
    if (this === scroller) return rect(0, BOX);
    if (this === scroller.firstElementChild) return rect(contentTop(), content());
    let y = contentTop() + HEAD;
    for (const row of rows()) {
      if (row === this) return rect(y, heightOf(row));
      y += heightOf(row);
    }
    return rect(0, 0);
  };
}

/** Where a row's top sits below the top of the box, the way a reader sees it. */
function topOf(id: string): number {
  const row = scroller.querySelector<HTMLElement>(`[data-id="${id}"]`);
  if (!row) throw new Error(`no row ${id}`);
  return row.getBoundingClientRect().top;
}

/** The first row the reader can still see. */
function firstVisible(): string {
  for (const row of rows()) if (row.getBoundingClientRect().bottom > 0) return row.dataset.id!;
  throw new Error("nothing visible");
}

/** A drag or a fling to `top` pixels below the top of what is loaded: the browser moves the list, then says so. */
function scrollTo(top: number): void {
  act(() => {
    scroller.scrollTop = top - maxScroll();
    fireEvent.scroll(scroller);
  });
}

/** The browser says the scroll is over: the finger is up and any fling has run out. */
function scrollEnded(): void {
  act(() => {
    fireEvent(scroller, new Event("scrollend"));
  });
}

/** Chrome or a browser built on it, the engine that moves the list under a touch scroll. */
function asChrome(): void {
  Object.defineProperty(navigator, "userAgentData", { configurable: true, value: { mobile: true, brands: [] } });
}

// --- a controllable page of older messages ---------------------------------

const realLoadOlder = useMessages.getState().loadOlder;
let inFlight: { land: (ids: string[]) => void; asked: number };

function stubLoadOlder(): void {
  let settle: (() => void) | null = null;
  inFlight = {
    asked: 0,
    land: (ids) => {
      act(() => {
        useMessages.getState().insert(CHANNEL, ids.map((id) => msg(id)));
        useMessages.getState().setLoading(CHANNEL, false);
      });
      const done = settle;
      settle = null;
      act(() => done?.());
    },
  };
  useMessages.setState({
    loadOlder: (channelId: string) => {
      inFlight.asked++;
      // The real store marks the channel loading before it awaits anything;
      // the list leans on that, so the stand-in does it too.
      useMessages.getState().setLoading(channelId, true);
      return new Promise<void>((res) => {
        settle = res;
      });
    },
  });
}

// --- a ResizeObserver, which jsdom has not ---------------------------------

const observers: ResizeObserverCallback[] = [];

class FakeResizeObserver {
  constructor(private cb: ResizeObserverCallback) {
    observers.push(cb);
  }
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {
    const i = observers.indexOf(this.cb);
    if (i >= 0) observers.splice(i, 1);
  }
}

/** Something in the list changed height on its own: a picture finished loading. */
function relayout(): void {
  act(() => {
    for (const cb of [...observers]) cb([], {} as ResizeObserver);
  });
}

// --- the list under test ---------------------------------------------------

/** Seeds `count` messages ending at id `1000 + count` and renders the list. */
function seed(count: number): void {
  const ids = Array.from({ length: count }, (_, i) => String(1001 + i));
  useMessages.setState({
    channels: {
      [CHANNEL]: {
        ids,
        byId: Object.fromEntries(ids.map((id) => [id, msg(id)])),
        hasOlder: true,
        hasNewer: false,
        loading: false,
        loaded: true,
        failed: 0,
        pending: [],
      },
    },
  });
}

function show(): void {
  const view = render(<MessageList channel={channel} />);
  scroller = view.container.querySelector<HTMLElement>("[data-scroller]")!;
  measure();
  // The first layout run sticks to the bottom, which it can only do once it
  // can measure; before `measure()` every height was zero.
  relayout();
}

beforeEach(() => {
  scrollTop = 0;
  heights.clear();
  observers.length = 0;
  vi.stubGlobal("ResizeObserver", FakeResizeObserver);
  stubLoadOlder();
  useAuth.setState({ me: { id: ME, username: "me", display_name: "Me" } as never });
  // A focused list at the bottom marks itself read, which would reach the network.
  useUI.setState({ focused: false, jumpTo: null });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  delete (navigator as { userAgentData?: unknown }).userAgentData;
  HTMLElement.prototype.getBoundingClientRect = realRect;
  useMessages.setState({ loadOlder: realLoadOlder, channels: {} });
});

describe("MessageList keeping the reading position", () => {
  it("does not lose the reading position to an unrelated render while an older page is loading", () => {
    seed(60);
    show();
    // Up near the top of what is loaded, which asks for the page before it.
    scrollTo(200);
    expect(inFlight.asked).toBe(1);
    const keeping = firstVisible();
    const was = topOf(keeping);

    // A message finishes decrypting and lands in the store. This is the render
    // that used to spend the anchor: the list did not get taller, so the height
    // it had noted looked unchanged and it threw the anchor away anyway.
    act(() => useMessages.getState().update(CHANNEL, { ...msg("1030"), payload: { t: "decrypted at last" } }));

    inFlight.land(["951", "952", "953", "954", "955", "956", "957", "958", "959", "960"]);

    expect(firstVisible()).toBe(keeping);
    expect(topOf(keeping)).toBe(was);
    // The complaint itself: the ten messages that arrived above the viewport
    // must not carry the reader up to the top of the chunk that just loaded.
    // Counted from the bottom the list never moved at all, and the reader is
    // the new page's height further from the top of what is loaded.
    expect(fromTheTop()).toBe(200 + 10 * ROW);
  });

  it("holds through a stream of unrelated renders, as a DM full of decryptions produces", () => {
    seed(60);
    show();
    // past where the list asks for older messages (two screens from the top)
    scrollTo(1200);
    const keeping = firstVisible();
    const was = topOf(keeping);
    expect(inFlight.asked).toBe(0);

    scrollTo(120);
    expect(inFlight.asked).toBe(1);
    const asked = firstVisible();
    const askedAt = topOf(asked);

    for (const id of ["1010", "1022", "1041", "1057"]) {
      act(() => useMessages.getState().update(CHANNEL, { ...msg(id), payload: { t: `${id} decrypted` } }));
    }
    act(() => useMessages.getState().setReaction(CHANNEL, "1044", "👍", THEM, true, false));

    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));

    expect(firstVisible()).toBe(asked);
    expect(topOf(asked)).toBe(askedAt);
    // The fling really did move on between the two, so this is not the same
    // row being held all along.
    expect(keeping).not.toBe(asked);
    expect(was).toBeLessThanOrEqual(0);
  });

  it("leaves the reader alone when the list grows below them", () => {
    seed(60);
    show();
    scrollTo(0);
    const keeping = firstVisible();
    const was = topOf(keeping);
    const before = fromTheTop();

    // A picture well below the viewport finally loads. Counted from the bottom,
    // that would push everything on screen up by the height of the picture.
    heights.set("1050", ROW + 300);
    relayout();
    act(() => useMessages.getState().update(CHANNEL, { ...msg("1050"), payload: { t: "picture" } }));

    expect(fromTheTop()).toBe(before);
    expect(topOf(keeping)).toBe(was);
  });

  it("takes off what pictures in the page that just landed add above the reader", () => {
    seed(60);
    show();
    scrollTo(0);
    const keeping = firstVisible();
    const was = topOf(keeping);

    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    expect(topOf(keeping)).toBe(was);

    // The page is on screen before its pictures know how tall they are; nothing
    // renders when they find out, and `.scroll` turns `overflow-anchor` off.
    heights.set("960", ROW + 200);
    heights.set("975", ROW + 120);
    relayout();

    expect(topOf(keeping)).toBe(was);
  });

  it("takes a fresh row to hold when the reader touches the bottom and comes back up", () => {
    seed(60);
    show();
    scrollTo(200);
    expect(inFlight.asked).toBe(1);

    // All the way down and straight back up, all before the page arrives. At
    // the bottom there is nothing worth holding, so coming back up has to take
    // a row again rather than let the page land uncompensated.
    scrollTo(maxScroll());
    scrollTo(160);
    const keeping = firstVisible();
    const was = topOf(keeping);

    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));

    expect(topOf(keeping)).toBe(was);
    expect(fromTheTop()).toBe(160 + 50 * ROW);
  });

  it("writes no scroll position while a page of history lands above a fling", () => {
    seed(60);
    show();
    scrollTo(150);
    expect(inFlight.asked).toBe(1);
    const keeping = firstVisible();
    const was = topOf(keeping);
    // iOS carries a fling on in the compositor and throws away a scroll
    // position written from the page in the middle of one: the reader was
    // left at the top of the page that had just loaded. Nothing may be written.
    let writes = 0;
    const own = Object.getOwnPropertyDescriptor(scroller, "scrollTop")!;
    Object.defineProperty(scroller, "scrollTop", {
      configurable: true,
      get: own.get!,
      set: (v: number) => {
        writes++;
        own.set!(v);
      },
    });
    // The message that was oldest joins the one before it as the page lands,
    // and loses the name line on top of it: it shrinks from its top.
    heights.set(rows()[0]!.dataset.id!, ROW - 16);
    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    heights.set("955", ROW + 160);
    relayout();
    expect(writes).toBe(0);
    expect(topOf(keeping)).toBe(was);
  });

  it("holds the list's shape under a moving touch in Chrome, and lands what came once the list stops", () => {
    // Chrome, while a touch scroll or its fling is under way, keeps the scroll
    // position from the top: a page landing above threw the reader to the top
    // of it, and a position written for a message below made the drag lurch.
    asChrome();
    seed(60);
    show();
    scrollTo(150);
    scrollEnded();
    expect(inFlight.asked).toBe(1);
    const keeping = firstVisible();
    const was = topOf(keeping);
    let writes = 0;
    const own = Object.getOwnPropertyDescriptor(scroller, "scrollTop")!;
    Object.defineProperty(scroller, "scrollTop", {
      configurable: true,
      get: own.get!,
      set: (v: number) => {
        writes++;
        own.set!(v);
      },
    });
    fireEvent.touchStart(scroller);
    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    act(() => useMessages.getState().insert(CHANNEL, [msg("1061")]));
    heights.set(keeping, ROW + 30);
    relayout();
    // nothing new is drawn, and nothing is written, while the finger is down
    expect(rows()).toHaveLength(60);
    expect(writes).toBe(0);
    // more history is not asked for while a page waits for the finger
    scrollTo(10);
    expect(inFlight.asked).toBe(1);
    expect(topOf(keeping)).not.toBe(was);
    // the finger lifts and the list flings on to the top of what is loaded:
    // still nothing lands, and the loader says more is coming
    act(() => {
      fireEvent.touchEnd(scroller);
    });
    scrollTo(0);
    expect(rows()).toHaveLength(60);
    expect(inFlight.asked).toBe(1);
    expect(scroller.querySelector("[data-busy]")).not.toBeNull();
    const stopped = firstVisible();
    const stoppedAt = topOf(stopped);
    scrollEnded();
    expect(rows()).toHaveLength(111);
    // the page lands above, and the message below is taken back off: the reader
    // stays exactly where the fling left them, not at the top of the new page
    expect(topOf(stopped)).toBe(stoppedAt);
    expect(firstVisible()).not.toBe("951");
  });

  it("holds history during wheel momentum and releases it after scrollend settles", async () => {
    asChrome();
    seed(60);
    show();
    scrollTo(150);
    scrollEnded();
    fireEvent.wheel(scroller, { deltaY: -240 });
    scrollTo(40);
    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    expect(rows()).toHaveLength(60);
    const keeping = firstVisible();
    const was = topOf(keeping);
    // Model Chromium rebasing the reversed range to the newly loaded top
    // when the held page is finally drawn, after its compositor gesture ends.
    const measuredRect = HTMLElement.prototype.getBoundingClientRect;
    let rebased = false;
    HTMLElement.prototype.getBoundingClientRect = function () {
      if (!rebased && rows().length === 110) {
        rebased = true;
        scrollTop = -maxScroll();
      }
      return measuredRect.call(this);
    };
    scrollEnded();
    expect(rows()).toHaveLength(60);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 350)); });
    expect(rows()).toHaveLength(110);
    expect(rebased).toBe(true);
    expect(topOf(keeping)).toBe(was);
    heights.set("960", ROW + 300);
    relayout();
    expect(topOf(keeping)).toBe(was);
  });

  it("releases wheel-held history after quiet when scrollend is missing", () => {
    vi.useFakeTimers();
    try {
      asChrome();
      seed(60);
      show();
      scrollTo(150);
      fireEvent.wheel(scroller, { deltaY: -240 });
      inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
      expect(rows()).toHaveLength(60);
      // a wheel quiet for a fifth of a second has stopped, momentum included
      act(() => vi.advanceTimersByTime(250));
      expect(rows()).toHaveLength(110);
    } finally {
      vi.useRealTimers();
    }
  });

  it("fetches ahead while a wheel holds a page, without waiting for another scroll", async () => {
    vi.useFakeTimers();
    try {
      asChrome();
      seed(60);
      show();
      scrollTo(150);
      fireEvent.wheel(scroller, { deltaY: -240 });
      expect(inFlight.asked).toBe(1);
      inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(20);
      });
      // the page is held off screen, and the next one is already asked for
      expect(rows()).toHaveLength(60);
      expect(inFlight.asked).toBe(2);
      fireEvent.wheel(scroller, { deltaY: -240 });
      inFlight.land(Array.from({ length: 50 }, (_, i) => String(901 + i)));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(20);
      });
      // two pages ahead is far enough
      expect(inFlight.asked).toBe(2);
      act(() => vi.advanceTimersByTime(250));
      expect(rows()).toHaveLength(160);
    } finally {
      vi.useRealTimers();
    }
  });

  it("lets a held page in when the wheel is pinned against the top, keeping the row in view", () => {
    asChrome();
    seed(60);
    show();
    scrollTo(150);
    fireEvent.wheel(scroller, { deltaY: -240 });
    scrollTo(0);
    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    expect(rows()).toHaveLength(60);
    const keeping = firstVisible();
    const was = topOf(keeping);
    // still wheeling up, against an edge the list cannot pass
    act(() => {
      fireEvent.wheel(scroller, { deltaY: -240 });
    });
    expect(rows()).toHaveLength(110);
    expect(topOf(keeping)).toBe(was);
  });

  it("keeps a reader near the top in place when a long list is cut back below them", () => {
    vi.useFakeTimers();
    try {
      seed(400);
      show();
      // Near the top of a full list. Cutting it back takes 150 rows from
      // below, which shrinks the scroll range past where the reader is.
      scrollTo(100);
      // the page that asked for lands empty: the start of the conversation
      inFlight.land([]);
      const keeping = firstVisible();
      const was = topOf(keeping);
      act(() => vi.advanceTimersByTime(1000));
      expect(rows()).toHaveLength(250);
      expect(topOf(keeping)).toBe(was);
    } finally {
      vi.useRealTimers();
    }
  });

  it("lands a page that came under a touch that never moved the list as the finger lifts", () => {
    asChrome();
    seed(60);
    show();
    scrollTo(150);
    scrollEnded();
    fireEvent.touchStart(scroller);
    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    expect(rows()).toHaveLength(60);
    act(() => {
      fireEvent.touchEnd(scroller);
    });
    expect(rows()).toHaveLength(110);
  });

  it("lands a held page after a quiet spell where the browser never says the scroll ended", () => {
    vi.useFakeTimers();
    try {
      asChrome();
      seed(60);
      show();
      scrollTo(150);
      fireEvent.touchStart(scroller);
      inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
      scrollTo(10);
      act(() => {
        fireEvent.touchEnd(scroller);
      });
      expect(rows()).toHaveLength(60);
      act(() => {
        vi.advanceTimersByTime(1600);
      });
      expect(rows()).toHaveLength(110);
    } finally {
      vi.useRealTimers();
    }
  });

  it("leaves touch scrolling outside Chrome as it was", () => {
    // Safari on an iPhone or iPad: nothing waits for the finger
    seed(60);
    show();
    scrollTo(150);
    fireEvent.touchStart(scroller);
    inFlight.land(Array.from({ length: 50 }, (_, i) => String(951 + i)));
    expect(rows()).toHaveLength(110);
  });

  it("asks for one page however hard the list is flung", () => {
    seed(60);
    show();
    act(() => {
      for (const top of [900, 600, 300, 100, 40, 0]) {
        scroller.scrollTop = top - maxScroll();
        fireEvent.scroll(scroller);
      }
    });
    expect(inFlight.asked).toBe(1);
  });
});

describe("MessageList behaviours the anchoring must not cost", () => {
  it("sticks to the bottom when the reader is at the bottom", () => {
    seed(20);
    show();
    expect(scroller.scrollTop).toBe(0);
    const wasMax = maxScroll();

    act(() => useMessages.getState().insert(CHANNEL, [msg("1021")]));
    expect(scroller.scrollTop).toBe(0);
    expect(maxScroll()).toBe(wasMax + ROW);
  });

  it("shows your own message wherever you were reading", () => {
    seed(60);
    show();
    scrollTo(600);
    expect(fromTheTop()).toBe(600);

    act(() =>
      useMessages.getState().addPending({
        tmpId: "t1",
        nonce: "n1",
        channelId: CHANNEL,
        authorId: ME,
        payload: { t: "mine" },
        createdAt: 1,
        status: "sending",
      }),
    );

    expect(scroller.scrollTop).toBe(0);
  });

  it("offers the new-messages pill instead of moving a reader who has scrolled up", () => {
    seed(60);
    show();
    scrollTo(600);
    const keeping = firstVisible();
    const was = topOf(keeping);

    act(() => useMessages.getState().insert(CHANNEL, [msg("1061")]));

    expect(fromTheTop()).toBe(600);
    expect(topOf(keeping)).toBe(was);
    // it counts what came in, and says since when
    expect(document.body.textContent).toMatch(/1 new message since \d{1,2}:\d{2}/);
  });
});

describe("MessageList for the keyboard and screen readers", () => {
  const active = () => [...scroller.querySelectorAll<HTMLElement>('[data-active="true"]')].map((r) => r.dataset.id);
  const row = (id: string) => scroller.querySelector<HTMLElement>(`[data-id="${id}"]`)!;

  it("counts only the row pointed at, or holding focus, as looked at", () => {
    seed(5);
    show();
    expect(active()).toEqual([]);
    fireEvent.pointerOver(row("1003"));
    expect(active()).toEqual(["1003"]);
    fireEvent.pointerOver(row("1004"));
    expect(active()).toEqual(["1004"]);
    fireEvent.pointerLeave(scroller);
    expect(active()).toEqual([]);
    act(() => row("1002").focus());
    expect(active()).toEqual(["1002"]);
    act(() => row("1002").blur());
    expect(active()).toEqual([]);
  });

  it("tells a screen reader about a message arriving, and not about a page of history", () => {
    useUsers.setState({ byId: { [THEM]: { id: THEM, username: "them", display_name: "Them" } as never } });
    seed(60);
    show();
    const status = () => document.querySelector('[role="status"]')!.textContent;
    expect(status()).toBe("");

    act(() => useMessages.getState().insert(CHANNEL, [msg("1061")]));
    expect(status()).toBe("Them: m1061");

    // older messages paged in above are not news
    scrollTo(0);
    inFlight.land(["951", "952"]);
    expect(status()).toBe("Them: m1061");

    // nor is your own message
    act(() => useMessages.getState().insert(CHANNEL, [msg("1062", ME)]));
    expect(status()).toBe("Them: m1061");
  });
});
