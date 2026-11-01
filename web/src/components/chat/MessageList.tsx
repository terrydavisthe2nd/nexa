import { PersonName } from "@/components/common/PersonName";
import { withOwn } from "@/lib/useMemberLook";
import { nameClass, nameStyle } from "@/lib/roleLook";
import { ArrowDown } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Channel, GroupAddNotice } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Spinner } from "@/components/common/Spinner";
import { channelDisplayName, friendNicknames } from "@/lib/names";
import { compareIds } from "@/lib/ids";
import { setReadingPresent } from "@/lib/readPosition";
import { interactedWithin, onInteraction } from "@/lib/interaction";
import { formatDateDivider, formatTime, sameDay } from "@/lib/time";
import { useAuth } from "@/stores/auth";
import { WINDOW, WINDOW_CAP, useMessages, type DecryptedMessage, type PendingMessage } from "@/stores/messages";
import { useReadStates } from "@/stores/readStates";
import { useSelection } from "@/stores/selection";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { installSwipeReply } from "@/lib/swipeReply";
import { useUsers } from "@/stores/users";
import { useReplyTargets, type ReplyTarget } from "@/stores/replyTargets";
import { useChannels } from "@/stores/channels";
import { MessageItem } from "./MessageItem";
import { GroupAlone } from "./GroupAlone";
import { useReadingApps } from "./apps/readingApps";
import { ThreadIntro } from "@/components/threads/ThreadIntro";
import { GroupAddNoticeRow } from "./GroupAddNoticeRow";
import { noticeSpot } from "@/lib/groupAdd";
import { useGroupAddNotices } from "@/stores/groupAddNotices";
import { BlockedRun } from "./BlockedRun";
import { isQuiet, messageFate, useBlockRules, type AuthorOf } from "@/lib/blocked";
import s from "./Chat.module.css";

import { formatList, t, Tr } from "@/i18n";
import { cssValue } from "@/lib/cssEscape";
import { RenderCtxProvider, useRenderCtx } from "@/lib/markdownCtx";
import { sameHookSender } from "@/lib/webhookMessage";
import { pollWords } from "@/lib/poll";
import { parse, plainText } from "@/markdown";
import { useRelationships } from "@/stores/relationships";
import { useServers } from "@/stores/servers";
import { displayName } from "@/stores/users";
import { ActiveRows, ActiveRowsProvider, rowIdOf } from "./activeRow";
import { onReadyApplied, readyApplied } from "@/gateway/dispatch";
/** A run of messages from blocked people, folded into one row (open shows them under it). */
type BlockedRow = { kind: "blocked"; key: string; ids: string[]; open: boolean };
type Row =
  | { kind: "date"; key: string; ts: number }
  | { kind: "unread"; key: string }
  | { kind: "msg"; key: string; msg: DecryptedMessage; groupStart: boolean }
  | { kind: "pending"; key: string; p: PendingMessage; groupStart: boolean }
  /** Nexa's note, to this account alone, that a friend added it to the group: not a message. */
  | { kind: "notice"; key: string; notice: GroupAddNotice }
  | BlockedRow;

const GROUP_MS = 7 * 60 * 1000;

/**
 * Chrome and the browsers built on it, where a page landing during touch
 * or wheel scrolling moves this list under it (see `touching` in MessageList). Browsers
 * on iPhones and iPads are WebKit whatever they call themselves, and say
 * CriOS rather than Chrome; touch scrolling there is left as it was.
 */
function movesUnderTouch(): boolean {
  return typeof navigator !== "undefined" && ("userAgentData" in navigator || /\bChrome\/\d/.test(navigator.userAgent));
}

/**
 * Whether this session's read positions are in. A conversation opened straight
 * from the address bar can load its messages before ready arrives, while
 * nothing is known yet about how far it was read: the "new" line was placed
 * against nothing (so never shown) and the newest message acked at once,
 * which undid Mark Unread and every unread message waiting there.
 */
function useReadStatesKnown(): boolean {
  const [known, setKnown] = useState(readyApplied);
  useEffect(() => {
    if (known) return;
    const off = onReadyApplied(() => setKnown(readyApplied()));
    setKnown(readyApplied());
    return off;
  }, [known]);
  return known;
}

/** How soon after a click or a key leaving a conversation counts as having read it. */
const LEAVE_MS = 3000;

/**
 * How long the list has to be still before a long one is cut back to the
 * messages around the reader (stores/messages WINDOW): never under a finger
 * or a fling, where a scroll position written from here can be thrown away.
 */
const REST_MS = 300;

/**
 * How long a wheel has to be quiet before the gesture counts as over. A
 * trackpad's momentum arrives as a wheel event every frame, so a gap this long
 * means it has stopped; waiting longer (it was a second and a half, the touch
 * fallback) left a page of history held off screen until the reader gave up.
 */
const WHEEL_REST_MS = 200;

/** Pages of older messages that may be fetched ahead while a gesture holds the ones before them off screen. */
const HELD_AHEAD = 100;

/** A row of the list and how far its top sat below the top of the box. */
type Anchor = { id: string; top: number };

/**
 * Notes where the reader is, as the first row still showing and where it sits.
 * For a change made right after it is taken (picking messages rewraps every
 * row), so it is put back from the same place before anything has scrolled.
 */
function readAnchor(el: HTMLElement): Anchor | null {
  const box = el.getBoundingClientRect();
  for (const row of el.querySelectorAll<HTMLElement>("[data-id]")) {
    const r = row.getBoundingClientRect();
    if (r.bottom > box.top) return { id: row.dataset.id!, top: r.top - box.top };
  }
  return null;
}

/** Puts a noted row back where it sat. False when that row has gone from the list. */
function restoreAnchor(el: HTMLElement, a: Anchor): boolean {
  const row = el.querySelector<HTMLElement>(`[data-id="${cssValue(a.id)}"]`);
  if (!row) return false;
  const now = row.getBoundingClientRect().top - el.getBoundingClientRect().top;
  if (now !== a.top) el.scrollTop += now - a.top;
  return true;
}

/*
 * The list scrolls from its bottom: `.scroll` is a column-reverse flex box
 * around `.inner`, so a scroll position is a distance from the newest
 * message (0 at the bottom, negative above it). History loaded above the
 * reader then moves nothing on screen and needs no correcting. It used to:
 * a page of older messages pushed everything down and the list wrote the
 * scroll position back, and on iOS a fling in progress threw that write
 * away, so a fast swipe up landed at the top of the page that had just
 * loaded, far above where it had been going.
 */

/** How far the reader is above the newest message. */
export function fromBottom(el: HTMLElement): number {
  return Math.max(0, -el.scrollTop);
}

/** How far the reader is below the oldest message loaded. */
export function fromTop(el: HTMLElement): number {
  return Math.max(0, el.scrollHeight - el.clientHeight + el.scrollTop);
}

/** To the newest message: counted from the bottom, anything past 0 is 0. */
function toBottom(el: HTMLElement): void {
  el.scrollTop = el.scrollHeight;
}

/**
 * The row at the foot of the view and how far its bottom sits above the end
 * of the list. Scrolling does not change that distance, and nothing above
 * the row does either: older history landing, a message there losing its
 * name as it joins the one before it, a picture loading. Only something
 * below the row growing or shrinking does, and counted from the bottom that
 * is what would move the reader: a message arriving, a newer page, a picture
 * loading further down.
 */
type Reading = { id: string; below: number };

/** The last row still showing at the foot of the box, found by halves: a long conversation has thousands. */
function readReading(el: HTMLElement): Reading | null {
  const inner = el.firstElementChild;
  if (!inner) return null;
  const rows = el.querySelectorAll<HTMLElement>("[data-id]");
  if (rows.length === 0) return null;
  const bottom = el.getBoundingClientRect().bottom;
  let lo = 0;
  let hi = rows.length - 1;
  let at = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (rows[mid]!.getBoundingClientRect().top < bottom) {
      at = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  const row = rows[at]!;
  return { id: row.dataset.id!, below: inner.getBoundingClientRect().bottom - row.getBoundingClientRect().bottom };
}

/** Takes back whatever grew or shrank below the row being read. False when that row has gone. */
function keepReading(el: HTMLElement, a: Reading): boolean {
  const inner = el.firstElementChild;
  const row = el.querySelector<HTMLElement>(`[data-id="${cssValue(a.id)}"]`);
  if (!inner || !row) return false;
  const moved = inner.getBoundingClientRect().bottom - row.getBoundingClientRect().bottom - a.below;
  if (Math.abs(moved) >= 0.5) el.scrollTop -= moved;
  return true;
}

/** Who wrote a kept original (stores/replyTargets), when there is one. */
function keptAuthor(kept: Record<string, ReplyTarget> | null, id: string): string | undefined {
  const m = kept?.[id];
  return typeof m === "object" ? m.author_id : undefined;
}

function shouldGroup(prev: DecryptedMessage | undefined, cur: DecryptedMessage): boolean {
  if (!prev) return false;
  if (prev.author_id !== cur.author_id) return false;
  // every webhook's messages are filed under one account; one sender is one
  // webhook posting under one name and picture
  if (prev.webhook_id !== cur.webhook_id || !sameHookSender(prev.hook, cur.hook)) return false;
  if (cur.type !== "default" && cur.type !== "reply") return false;
  if (prev.type.startsWith("system_")) return false;
  if (cur.reply_to_id) return false;
  // a message that started a thread shows its own avatar, which the line to the thread's card hangs from
  if (cur.thread) return false;
  if (cur.created_at - prev.created_at > GROUP_MS) return false;
  return sameDay(prev.created_at, cur.created_at);
}

function pendingToMessage(p: PendingMessage): DecryptedMessage {
  return {
    id: p.tmpId,
    channel_id: p.channelId,
    author_id: p.authorId,
    type: p.replyToId ? "reply" : "default",
    ciphertext: null,
    key_conv_id: null,
    key_epoch: null,
    reply_to_id: p.replyToId ?? null,
    mentions: [],
    mention_roles: [],
    mention_everyone: false,
    attachments: [],
    reactions: [],
    flags: 0,
    edited_at: null,
    created_at: p.createdAt,
    payload: p.payload,
  };
}

export function MessageList({ channel }: { channel: Channel }) {
  const me = useAuth((a) => a.me);
  const users = useUsers((u) => u.byId);
  const byIdChannels = useChannels((c) => c.byId);
  const state = useMessages((m) => m.channels[channel.id]);
  const addNotice = useGroupAddNotices((n) => (channel.type === "group_dm" ? n.byChannel[channel.id] : undefined));
  const loadOlder = useMessages((m) => m.loadOlder);
  const loadNewer = useMessages((m) => m.loadNewer);
  const loadInitial = useMessages((m) => m.loadInitial);
  const returnToPresent = useMessages((m) => m.returnToPresent);
  const readState = useReadStates((r) => r.byChannel[channel.id]);
  const readKnown = useReadStatesKnown();
  const ack = useReadStates((r) => r.ack);
  const focused = useUI((u) => u.focused);
  const jumpTo = useUI((u) => u.jumpTo);
  const setJumpTo = useUI((u) => u.setJumpTo);
  const compact = useSettings((st) => st.settings.messageDisplay === "compact");
  const selecting = useSelection((x) => x.channelId === channel.id);
  const scrollRef = useRef<HTMLDivElement>(null);
  /** The row at the top of the view, and how far down it sat, as picking began or ended. */
  const pickAnchor = useRef<{ id: string; top: number } | null>(null);
  const atBottom = useRef(true);
  // While this list is up the conversation keeps what it loads; when it goes,
  // the conversation is cut back to its newest page (stores/messages).
  useEffect(() => useMessages.getState().open(channel.id), [channel.id]);
  /** A finger on the list, on every browser (the `touching` hold below is Chrome's alone). */
  const fingerDown = useRef(false);
  /** When the list last scrolled, to tell a list at rest from one still moving. */
  const lastScroll = useRef(0);
  const trimTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const trimDue = useRef(0);
  // on a touch screen, a message swiped against the reading direction is replied to
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    return installSwipeReply(
      el,
      (row) => {
        const id = row.dataset["id"];
        if (id) useUI.getState().setReplyTo({ channelId: channel.id, messageId: id, ping: true });
      },
      (row) => row.dataset["pending"] !== "true" && row.dataset["failed"] !== "true" && row.dataset["pickable"] === undefined,
    );
  }, [channel.id]);
  /**
   * Where the reader is, while they are anywhere but the bottom: noted as
   * they scroll and after every change, and put back after every change.
   */
  const reading = useRef<Reading | null>(null);
  /** A page of older messages is on its way. Set here, not read from the last render, which lags a frame behind. */
  const olderBusy = useRef(false);
  /**
   * A finger or wheel on the list, or the list still moving from one (the fling after
   * it), and the rows it was showing when the gesture began. Chrome, while
   * a touch or wheel scroll or its fling is under way, holds the scroll position as a
   * distance from the top of the list rather than this list's own from the
   * bottom. A page of history landing above threw the reader to the top of
   * it, close enough to ask for the next page, which did the same, until the
   * start of the conversation; and the correction for a message arriving
   * below, written while the finger was down, made the drag lurch hundreds of
   * pixels. So nothing changes the list's shape under a moving touch: rows
   * that arrive wait, and so does keeping the reader's place, and both land
   * once the list is still (scrollend). A fling up through history stops at
   * the top of what is loaded and the next page lands there.
   */
  const touching = useRef(false);
  const releaseAnchor = useRef<Anchor | null>(null);
  /**
   * The row on screen when a long list was cut back. Cutting rows from below a
   * reader near the top shrinks the scroll range past where they are, and the
   * browser clamps the position before holdReading can take the cut off
   * again, which then overshot by the clamp. Put back by where it sits on
   * screen, the row stays put whatever the browser did.
   */
  const trimAnchor = useRef<Anchor | null>(null);
  const [held, setHeld] = useState<{ first: string; last: string } | null>(null);
  const highlightTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [newBelow, setNewBelow] = useState(false);
  // The newest message there was when news started arriving below the reader:
  // the pill counts what came after it, and says since when.
  const newFrom = useRef<string | null>(null);
  const clearNewBelow = useCallback(() => {
    newFrom.current = null;
    setNewBelow(false);
  }, []);
  // Scrolling only moves refs, so whether the jump button shows has to be state of its own.
  const [showJump, setShowJump] = useState(false);
  const [reachedBottom, setReachedBottom] = useState(0);
  // one render context for every row, rather than every row subscribing to
  // every user, channel and server itself
  const renderCtx = useRenderCtx(channel.server_id);
  // which row is pointed at or focused: only that one draws its toolbar
  const [activeRows] = useState(() => new ActiveRows());
  // what a screen reader is told about a message arriving (see announce below)
  const [said, setSaid] = useState<{ n: number; text: string } | null>(null);
  // Newer pages load below the reader; the loader at the top is for older ones.
  const [newerBusy, setNewerBusy] = useState(false);
  const [highlight, setHighlight] = useState<string | null>(null);
  // ChatView mounts a fresh list for each channel, so these start over per channel.
  const unreadFrom = useRef<string | null | undefined>(undefined);
  const lastAcked = useRef<string | null>(null);
  const triedAround = useRef<string | null>(null);
  // messages from people this account blocked, and which folded runs of them are open
  const blockRules = useBlockRules();
  const [openRuns, setOpenRuns] = useState<ReadonlySet<string>>(() => new Set());
  const toggleRun = useCallback((key: string) => {
    setOpenRuns((cur) => {
      const next = new Set(cur);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const allIds = state?.ids ?? [];
  // under a moving touch, only the rows that were there when the finger went down (see `touching`)
  const ids = useMemo(() => {
    if (!held) return allIds;
    const from = allIds.indexOf(held.first);
    const to = allIds.lastIndexOf(held.last);
    // the list was replaced under the finger (a jump): nothing to hold on to;
    // and nothing new is the same list, so a touch alone builds no rows again
    if (from < 0 || to < from || (from === 0 && to === allIds.length - 1)) return allIds;
    return allIds.slice(from, to + 1);
  }, [allIds, held]);
  // a page of history is in and waits for the list to stop: the loader at the top says more is coming
  const heldAbove = ids.length > 0 && ids[0] !== allIds[0];
  // how many of those wait, so a fling can fetch a little further ahead but not without end
  const hiddenAbove = heldAbove ? allIds.indexOf(ids[0]!) : 0;
  // so does a list at its longest, waiting to be cut back before it loads more
  const capped = allIds.length >= WINDOW_CAP;
  // what the wheel listener below reads between renders: rows waiting above, or a list waiting to be cut back
  const waitingAbove = useRef(false);
  waitingAbove.current = hiddenAbove > 0 || capped;
  const byId = state?.byId ?? {};
  // whose a reply's original was, for hiding replies to someone blocked, also
  // from the originals the server sent along (stores/replyTargets)
  const kept = useReplyTargets((r) => (blockRules.hideReplies ? r.byId : null));
  const pending = state?.pending ?? [];
  const hasOlder = state?.hasOlder ?? true;
  const hasNewer = state?.hasNewer ?? false;
  const loading = state?.loading ?? false;
  const loaded = state?.loaded ?? false;
  const failed = state?.failed ?? 0;

  if (unreadFrom.current === undefined && loaded && readKnown) unreadFrom.current = readState?.lastReadId ?? null;
  // Marked unread (here or on another device): the read position moves back,
  // and the "new" line moves up with it at once rather than on the next visit.
  const lastRead = readState?.lastReadId ?? null;
  const seenLastRead = useRef(lastRead);
  if (seenLastRead.current !== lastRead) {
    const before = seenLastRead.current;
    seenLastRead.current = lastRead;
    if (unreadFrom.current !== undefined && before !== null && (lastRead === null || compareIds(lastRead, before) < 0)) {
      unreadFrom.current = lastRead ?? "0";
      // Unread on purpose stays unread while it is looked at, clicked around in
      // and left: only a message newer than what is here now reads it again.
      lastAcked.current = state?.ids[state.ids.length - 1] ?? null;
    }
  }

  const rows = useMemo<Row[]>(() => {
    const out: Row[] = [];
    let prev: DecryptedMessage | undefined;
    let unreadPlaced = false;
    const from = unreadFrom.current;
    // Messages from people this account blocked fold into one row per run, or
    // leave no trace at all (lib/blocked.ts). Dates and grouping are worked out
    // from what is left, so a hidden message leaves no gap, no lone date line
    // and no break in the run of messages around it.
    const authorOf: AuthorOf = (mid) => byId[mid]?.author_id ?? keptAuthor(kept, mid);
    let run: BlockedRow | null = null;
    // Nexa's note about who added this account goes where noticeSpot says,
    // and whatever follows it starts a run of its own, with a name
    const meId = me?.id;
    const spot = addNotice && meId && loaded ? noticeSpot(ids, byId, addNotice, meId, { hasOlder, hasNewer }) : null;
    let noticePlaced = false;
    let afterNotice = false;
    const placeNotice = () => {
      if (!addNotice || noticePlaced) return;
      out.push({ kind: "notice", key: "group-add-notice", notice: addNotice });
      noticePlaced = true;
      afterNotice = true;
    };
    if (spot === "start") placeNotice();
    for (const id of ids) {
      const m = byId[id];
      if (!m) continue;
      const fate = messageFate(m, blockRules, authorOf);
      if (fate === "hide") continue;
      const newDay = !prev || !sameDay(prev.created_at, m.created_at);
      if (newDay) out.push({ kind: "date", key: `d${m.id}`, ts: m.created_at });
      // nothing from someone blocked is news, so the "new" line waits for what is
      if (!unreadPlaced && from !== undefined && from !== null && compareIds(m.id, from) > 0 && m.author_id !== me?.id && !isQuiet(m, blockRules, authorOf)) {
        out.push({ kind: "unread", key: "unread" });
        unreadPlaced = true;
      }
      if (fate === "collapse") {
        // a run ends at the next shown message, and at a new day so the date line stays true
        if (!run || newDay) {
          const key = `b${m.id}`;
          run = { kind: "blocked", key, ids: [], open: openRuns.has(key) };
          out.push(run);
        }
        run.ids.push(m.id);
        if (run.open) out.push({ kind: "msg", key: m.id, msg: m, groupStart: !shouldGroup(prev, m) });
        prev = m;
        continue;
      }
      run = null;
      out.push({ kind: "msg", key: m.id, msg: m, groupStart: afterNotice || !shouldGroup(prev, m) });
      afterNotice = false;
      prev = m;
      if (spot && typeof spot === "object" && spot.after === m.id) placeNotice();
    }
    // at the end, or there after all when the line it follows is not shown
    if (spot === "end" || (spot && !noticePlaced && !hasNewer)) placeNotice();
    // Unsent messages group with what is above them exactly as sent ones do,
    // or each would flash its own avatar and name until the server answered.
    for (const p of pending) {
      const asMessage = pendingToMessage(p);
      out.push({ kind: "pending", key: p.tmpId, p, groupStart: afterNotice || !shouldGroup(prev, asMessage) });
      afterNotice = false;
      prev = asMessage;
    }
    return out;
    // loaded, readKnown and lastRead are listed so the rows are built again when the unread point is captured or moves
  }, [ids, byId, kept, pending, me?.id, loaded, readKnown, lastRead, blockRules, openRuns, addNotice, hasOlder, hasNewer]);
  /** Whether a message stays quiet here: from someone blocked, or hidden for answering them. */
  const quiet = useCallback((m: DecryptedMessage | undefined) => !!m && isQuiet(m, blockRules, (mid) => byId[mid]?.author_id ?? keptAuthor(kept, mid)), [blockRules, byId, kept]);

  const newest = ids[ids.length - 1];
  const prevNewest = useRef<string | undefined>(undefined);
  const prevPending = useRef(0);
  const prevHasNewer = useRef(false);

  /** Lets the reading position go: the bottom, or a jump, is where the list puts itself. */
  const dropReading = useCallback(() => {
    reading.current = null;
  }, []);

  /** Keeps the row being read where it is on screen, and notes it again for the next change. */
  const holdReading = useCallback((el: HTMLElement) => {
    const a = reading.current;
    // The row can be deleted, or swept away by a jump that reloaded the list
    // around somewhere else; then the row in its place is what to hold.
    if (a) keepReading(el, a);
    reading.current = readReading(el);
  }, []);

  /**
   * Says a message that just arrived, the way a sighted reader sees it land:
   * who and what, in a polite live region of its own. Rows added any other way
   * — a page of history above, a jump — are not news and say nothing, which a
   * live region over the whole list could not tell apart.
   */
  const announce = useCallback(
    (m: DecryptedMessage) => {
      if (typeof document === "undefined" || document.visibilityState !== "visible") return;
      const st = channel.server_id ? useServers.getState().byId[channel.server_id] : undefined;
      const name = (m.webhook_id ? m.hook?.name : undefined) || st?.members[m.author_id]?.nickname || useRelationships.getState().byUser[m.author_id]?.nickname || displayName(users[m.author_id]);
      const body = m.payload?.t ? plainText(parse(m.payload.t), renderCtx).slice(0, 300) : m.payload?.poll ? pollWords(m.payload.poll).replace(/\n/g, ", ").slice(0, 300) : "";
      const text = body
        ? t("chat.messageList.announce", { name, text: body })
        : m.payload?.sticker
          ? t("chat.messageList.announceSticker", { name })
          : m.payload?.att?.length
            ? t("chat.messageList.announceFile", { name })
            : "";
      if (text) setSaid((cur) => ({ n: (cur?.n ?? 0) + 1, text }));
    },
    [channel.server_id, users, renderCtx],
  );

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    // Something new at the end that was not paged in: a message arriving live.
    const arrived = !!newest && !!prevNewest.current && compareIds(newest, prevNewest.current) > 0 && !prevHasNewer.current;
    if (arrived && byId[newest!] && byId[newest!]!.author_id !== me?.id && !quiet(byId[newest!])) announce(byId[newest!]!);
    // Your own message always gets shown, wherever you were reading. When the
    // list stops short of the present it would land after old history, so the
    // newest page is loaded again first.
    if (pending.length > prevPending.current) {
      atBottom.current = true;
      setShowJump(false);
      // Going to the bottom outranks wherever the reader was.
      dropReading();
      if (hasNewer) returnToPresent(channel.id);
    }
    prevPending.current = pending.length;
    if (touching.current) {
      // the touch goes on from wherever it is; the place is held again once the list stops
      reading.current = readReading(el) ?? reading.current;
    } else if (atBottom.current) {
      toBottom(el);
      clearNewBelow();
    } else {
      // Older messages land above the reader and move nothing. What lands
      // below (a message arriving, a newer page) would push them up the
      // screen; holding the row being read takes exactly that off again, and
      // nothing more, so a render that changed nothing costs the reader nothing.
      holdReading(el);
      if (newest && prevNewest.current && compareIds(newest, prevNewest.current) > 0 && !prevHasNewer.current && byId[newest]?.author_id !== me?.id && !quiet(byId[newest])) {
        // A list that stopped short of the present only grows downwards by
        // loading pages the reader scrolled to, which are not news.
        if (!newFrom.current) newFrom.current = prevNewest.current;
        setNewBelow(true);
      }
    }
    const trimmed = trimAnchor.current;
    trimAnchor.current = null;
    if (trimmed && !touching.current && !atBottom.current && restoreAnchor(el, trimmed)) reading.current = readReading(el);
    prevNewest.current = newest;
    prevHasNewer.current = hasNewer;
  }, [rows, newest, byId, me?.id, pending.length, hasNewer, returnToPresent, channel.id, dropReading, holdReading, announce, quiet]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      // a picture landing under a finger moves it, rather than the list moving under it
      if (touching.current) {
        reading.current = readReading(el) ?? reading.current;
        return;
      }
      if (atBottom.current) {
        toBottom(el);
        return;
      }
      // A picture or an embed has no height of its own until it loads, and
      // nothing in a render says when it does. One below the reader would
      // push them up the screen, so the row being read is held here too.
      holdReading(el);
    });
    // Watch the list itself as well as its contents: the composer growing a
    // line shrinks this box without changing what is in it, and the newest
    // message would otherwise slide out of sight behind the box.
    if (el.firstElementChild) ro.observe(el.firstElementChild);
    ro.observe(el);
    return () => ro.disconnect();
  }, [channel.id, holdReading]);

  // Chromium can rebase its reversed scroll range as a held page is drawn.
  // Once the wheel is still it is safe to restore the actual visible row.
  useLayoutEffect(() => {
    const el = scrollRef.current;
    const anchor = releaseAnchor.current;
    releaseAnchor.current = null;
    if (el && anchor) {
      restoreAnchor(el, anchor);
      reading.current = readReading(el);
    }
    // what was held is on screen now: the reader may already be near its top
    if (el && !held) requestAnimationFrame(() => onScrollNow.current());
  }, [held]);

  // Picking moves every row over for its check, which rewraps long messages
  // and would slide what is being read out of view. The row at the top is
  // noted before the change lands (a store subscription runs before React
  // renders) and put back where it was after.
  useEffect(
    () =>
      useSelection.subscribe((next, prev) => {
        if ((next.channelId === channel.id) === (prev.channelId === channel.id)) return;
        const el = scrollRef.current;
        if (!el || atBottom.current) return;
        pickAnchor.current = readAnchor(el);
      }),
    [channel.id],
  );
  useLayoutEffect(() => {
    const a = pickAnchor.current;
    const el = scrollRef.current;
    pickAnchor.current = null;
    if (a && el) restoreAnchor(el, a);
  }, [selecting]);

  /**
   * Arrow keys walk the messages from the keyboard: from the list itself ↑
   * starts at the newest, ↑ and ↓ move a message at a time, Home and End go to
   * either end. A focused message shows its toolbar, and the menu key opens
   * its menu. Keys typed in an edit box stay the edit box's.
   */
  const onListKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowUp" && e.key !== "ArrowDown" && e.key !== "Home" && e.key !== "End") return;
    const target = e.target as HTMLElement;
    if (target.closest("textarea, input, select, [contenteditable='true']")) return;
    const list = scrollRef.current;
    if (!list) return;
    const rows = [...list.querySelectorAll<HTMLElement>("[data-row]")];
    if (rows.length === 0) return;
    const at = target.closest<HTMLElement>("[data-row]");
    let i = at ? rows.indexOf(at) : -1;
    if (e.key === "Home") i = 0;
    else if (e.key === "End") i = rows.length - 1;
    else if (i < 0) i = rows.length - 1;
    else i = e.key === "ArrowUp" ? Math.max(0, i - 1) : Math.min(rows.length - 1, i + 1);
    e.preventDefault();
    rows[i]!.focus({ preventScroll: true });
    rows[i]!.scrollIntoView({ block: "nearest" });
  };

  /**
   * Cuts a long list back to the messages around the reader once it is still
   * (stores/messages trimAround), then looks again for a page to load, which
   * a list at its cap was waiting for. The row being read stays where it is:
   * what goes from below it is taken off the scroll position as the rows
   * change (holdReading), and what goes from above it moves nothing.
   */
  const scheduleTrim = useCallback((after = REST_MS) => {
    // a check already due sooner stays: scrolling must not keep putting off the one a release asked for
    const due = performance.now() + after;
    if (trimTimer.current) {
      if (trimDue.current <= due) return;
      clearTimeout(trimTimer.current);
    }
    const check = () => {
      trimTimer.current = null;
      const el = scrollRef.current;
      const st = useMessages.getState().channels[channel.id];
      if (!el || !st || st.ids.length <= WINDOW) return;
      // Chromium's gesture guard (`touching`) already waits out flings, so a short rest will do there.
      const restFor = movesUnderTouch() ? WHEEL_REST_MS : REST_MS;
      if (fingerDown.current || touching.current || st.loading || performance.now() - lastScroll.current < restFor) {
        trimDue.current = performance.now() + REST_MS;
        trimTimer.current = setTimeout(check, REST_MS);
        return;
      }
      const anchor = atBottom.current ? null : (reading.current?.id ?? readReading(el)?.id ?? null);
      trimAnchor.current = atBottom.current ? null : readAnchor(el);
      useMessages.getState().trimAround(channel.id, anchor);
      requestAnimationFrame(() => onScrollNow.current());
    };
    trimDue.current = due;
    trimTimer.current = setTimeout(check, after);
  }, [channel.id]);
  useEffect(
    () => () => {
      if (trimTimer.current) clearTimeout(trimTimer.current);
    },
    [],
  );
  // A gesture just let go is the list at rest: a list at its cap is cut back
  // now, where polling for a quiet moment kept landing between flicks.
  useEffect(() => {
    if (!held && (useMessages.getState().channels[channel.id]?.ids.length ?? 0) > WINDOW) scheduleTrim(0);
  }, [held, channel.id, scheduleTrim]);
  // messages arriving while the reader sits still (a busy channel at the bottom) grow it too
  useEffect(() => {
    if (allIds.length > WINDOW) scheduleTrim();
  }, [allIds.length, scheduleTrim]);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const down = () => {
      fingerDown.current = true;
    };
    const up = (e: TouchEvent) => {
      if ((e.touches?.length ?? 0) === 0) fingerDown.current = false;
    };
    el.addEventListener("touchstart", down, { passive: true });
    el.addEventListener("touchend", up, { passive: true });
    el.addEventListener("touchcancel", up, { passive: true });
    return () => {
      el.removeEventListener("touchstart", down);
      el.removeEventListener("touchend", up);
      el.removeEventListener("touchcancel", up);
    };
  }, [channel.id]);

  const onScroll = useCallback((e?: unknown) => {
    const el = scrollRef.current;
    if (!el) return;
    // only a real scroll is movement; the list also runs this itself to look for a page
    if (e) lastScroll.current = performance.now();
    if (allIds.length > WINDOW) scheduleTrim();
    const dist = fromBottom(el);
    // The end of a list that stops short of the present is not the bottom:
    // nothing should stick to it or be marked read from it.
    const wasAtBottom = atBottom.current;
    atBottom.current = dist < 80 && !hasNewer;
    // arriving at the bottom is reading what is there: the ack below runs
    // again for it, instead of waiting for the next message or a refocus
    if (atBottom.current && !wasAtBottom) setReachedBottom((n) => n + 1);
    setShowJump(!atBottom.current);
    if (atBottom.current) {
      clearNewBelow();
      // Nothing to hold: the bottom is where this list puts itself anyway.
      reading.current = null;
    } else {
      // the row in front of the reader now, which a fling keeps changing
      reading.current = readReading(el) ?? reading.current;
    }
    // Asked for well before the top, and again while a gesture keeps the last
    // page off screen, so a fast scroll up finds the next pages already here.
    const ahead = Math.max(800, el.clientHeight * 2);
    if (fromTop(el) < ahead && hasOlder && !olderBusy.current && !loading && loaded && ids.length > 0 && hiddenAbove < HELD_AHEAD) {
      // A second scroll event in the same frame still sees `loading` false —
      // the store sets it synchronously, React has not re-rendered — so the
      // guard is a ref of this list's own, set before the call rather than
      // read back out of the last render.
      olderBusy.current = true;
      void loadOlder(channel.id)
        .catch(() => {})
        .finally(() => {
          olderBusy.current = false;
          // a wheel at the top of the list makes no scroll event to ask for the next page
          requestAnimationFrame(() => onScrollNow.current());
        });
    } else if (dist < 400 && hasNewer && !loading && loaded) {
      // Pages added below the reader are taken back off by the row being read.
      setNewerBusy(true);
      void loadNewer(channel.id)
        .catch(() => {})
        .finally(() => setNewerBusy(false));
    }
  }, [channel.id, hasOlder, hasNewer, loading, loaded, ids.length, allIds.length, hiddenAbove, loadOlder, loadNewer, scheduleTrim]);
  const onScrollNow = useRef(onScroll);
  onScrollNow.current = onScroll;

  // A touch or wheel gesture holds the rows that are there (see `touching`); they go once
  // the last finger is up and the list has stopped: at once if it never
  // moved, else at its scrollend, or when no scroll has come for a while where
  // that never fires. A finger that catches a fling keeps what is held.
  // Passive listeners: none of this may slow the scroll down.
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !movesUnderTouch()) return;
    let fingers = false;
    let moving = false;
    let wheel = false;
    let rest: ReturnType<typeof setTimeout> | null = null;
    const quietFor = "onscrollend" in window ? 1500 : 250;
    const wait = () => {
      if (rest) clearTimeout(rest);
      rest = null;
    };
    const release = () => {
      wait();
      if (wheel) releaseAnchor.current = readAnchor(el);
      touching.current = false;
      wheel = false;
      setHeld(null);
    };
    const settle = () => {
      wait();
      rest = setTimeout(release, wheel && !fingers ? WHEEL_REST_MS : quietFor);
    };
    const hold = () => {
      if (touching.current) return;
      touching.current = true;
      const now = useMessages.getState().channels[channel.id]?.ids ?? [];
      setHeld(now.length ? { first: now[0]!, last: now[now.length - 1]! } : null);
    };
    const down = () => {
      fingers = true;
      wait();
      hold();
    };
    // Trackpad momentum uses wheel events, with no touchstart. Chromium
    // preserves the top-relative position during that gesture too, so pages
    // must wait just as they do under a finger. Arm the fallback even if the
    // wheel hits the loaded edge and produces no scroll event.
    const wheeled = (e: WheelEvent) => {
      if (e.ctrlKey || e.deltaY === 0) return;
      // Pinned against the top of what is loaded, the list cannot move, so
      // nothing is under way that drawing what is held (or cutting a full
      // list back) could throw: let go, the rows are put back on the one in
      // view, and the wheel carries on up through them.
      if (wheel && touching.current && !fingers && e.deltaY < 0 && waitingAbove.current && fromTop(el) < 2) {
        release();
        return;
      }
      wheel = true;
      hold();
      if (!fingers) settle();
    };
    const up = (e: TouchEvent) => {
      if ((e.touches?.length ?? 0) > 0 || !fingers) return;
      fingers = false;
      if (moving) settle();
      else release();
    };
    const scrolled = () => {
      moving = true;
      if (rest) settle();
    };
    const ended = () => {
      moving = false;
      if (!touching.current || fingers) return;
      // A wheel scrollend can precede the compositor finishing its gesture.
      // Let it rest before changing the scroll range; another wheel resets it.
      if (wheel) {
        wait();
        rest = setTimeout(release, WHEEL_REST_MS);
      } else release();
    };
    el.addEventListener("touchstart", down, { passive: true });
    el.addEventListener("touchend", up, { passive: true });
    el.addEventListener("touchcancel", up, { passive: true });
    el.addEventListener("wheel", wheeled, { passive: true });
    el.addEventListener("scroll", scrolled, { passive: true });
    el.addEventListener("scrollend", ended);
    return () => {
      el.removeEventListener("touchstart", down);
      el.removeEventListener("touchend", up);
      el.removeEventListener("touchcancel", up);
      el.removeEventListener("wheel", wheeled);
      el.removeEventListener("scroll", scrolled);
      el.removeEventListener("scrollend", ended);
      wait();
      touching.current = false;
      setHeld(null);
    };
  }, [channel.id]);

  // What the checks below need from the latest render, for the ones that run outside of one.
  const live = useRef({ loaded, readKnown, hasNewer, newest, readState });
  live.current = { loaded, readKnown, hasNewer, newest, readState };
  /** Marks the newest message read when the list is showing it: the present, scrolled to the bottom. */
  const ackIfPresent = useCallback(() => {
    const v = live.current;
    // Wait for the first page: the unread point is taken from the read state
    // on that render, and acking earlier would move it to the newest message.
    // A list that stops short of the present has not shown the newest message.
    if (!v.loaded || !v.readKnown || v.hasNewer || !v.newest || !atBottom.current || typeof document === "undefined" || document.visibilityState !== "visible") return;
    if (lastAcked.current === v.newest) return;
    if (v.readState?.lastReadId && compareIds(v.readState.lastReadId, v.newest) >= 0 && v.readState.mentions === 0) return;
    lastAcked.current = v.newest;
    void ack(channel.id, v.newest);
  }, [ack, channel.id]);

  useEffect(() => {
    if (focused) ackIfPresent();
  }, [loaded, readKnown, hasNewer, newest, focused, readState, reachedBottom, ackIfPresent]);

  // A click, a key or a wheel turned in the window means the person is looking
  // at it, whatever its focus says (lib/interaction). And leaving the
  // conversation from its newest message, by a click or a key in the app, is
  // having read it: the list is gone before the desktop app's word that its
  // window is in front arrives, so it used to stay unread.
  useEffect(() => {
    const off = onInteraction(ackIfPresent);
    return () => {
      off();
      if (interactedWithin(LEAVE_MS)) ackIfPresent();
    };
  }, [ackIfPresent]);

  // A list too short to scroll never sends the scroll that asks for older
  // messages — after most of a page was deleted, or on a tall screen — so it
  // asks once it has settled.
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !loaded || !hasOlder || loading) return;
    // (a list with no size yet has not been laid out, and says nothing)
    if (el.clientHeight > 0 && el.scrollHeight > 0 && el.scrollHeight <= el.clientHeight + 400) onScroll();
  }, [ids.length, loaded, hasOlder, loading, onScroll]);

  useEffect(() => {
    if (!jumpTo || jumpTo.channelId !== channel.id) return;
    // A message from someone blocked: a folded run opens to show it, and one
    // left out entirely is not there to go to, which is said rather than
    // searched for and then called missing.
    const wanted = byId[jumpTo.messageId];
    const fate = wanted ? messageFate(wanted, blockRules, (mid) => byId[mid]?.author_id ?? keptAuthor(kept, mid)) : "show";
    if (fate === "hide") {
      setJumpTo(null);
      useUI.getState().toast(t("people.blocked.jumpHidden"), "info");
      return;
    }
    if (fate === "collapse") {
      const run = rows.find((r): r is BlockedRow => r.kind === "blocked" && r.ids.includes(jumpTo.messageId));
      if (run && !run.open) {
        setOpenRuns((cur) => new Set(cur).add(run.key));
        return;
      }
    }
    // the id comes from the address bar, so it is escaped before it is a selector
    const el = scrollRef.current?.querySelector<HTMLElement>(`[data-id="${cssValue(jumpTo.messageId)}"]`);
    if (el) {
      triedAround.current = null;
      atBottom.current = false;
      setShowJump(true);
      // Going somewhere on purpose beats holding where you were: the row that
      // was being held is very likely not even in the list any more.
      dropReading();
      el.scrollIntoView({ block: "center" });
      setHighlight(jumpTo.messageId);
      // a second jump within the two seconds would otherwise have its highlight
      // cleared by the first one's timer
      if (highlightTimer.current) clearTimeout(highlightTimer.current);
      highlightTimer.current = setTimeout(() => {
        highlightTimer.current = null;
        setHighlight(null);
      }, 2000);
      setJumpTo(null);
    } else if (!loading) {
      // The page around a deleted message holds its neighbours but never the
      // message itself, so after one try the jump is given up.
      if (triedAround.current === jumpTo.messageId) {
        triedAround.current = null;
        setJumpTo(null);
        useUI.getState().toast(t("chat.messageList.thatMessageCouldNotBeFound"), "error");
        return;
      }
      triedAround.current = jumpTo.messageId;
      void useMessages.getState().loadAround(channel.id, jumpTo.messageId).catch(() => {});
    }
  }, [jumpTo, channel.id, rows, loading, setJumpTo, dropReading, byId, kept, blockRules]);

  // "3 new messages since 4:32 PM": what came in below the reader since the news began
  const newSince = (): string => {
    const from = newFrom.current;
    const fresh = from ? ids.filter((id) => compareIds(id, from) > 0 && byId[id]?.author_id !== me?.id && !quiet(byId[id])) : [];
    const first = fresh.length ? byId[fresh[0]!] : undefined;
    return first ? t("chat.messageList.newMessagesSince", { count: fresh.length, time: formatTime(first.created_at) }) : t("chat.messageList.newMessages");
  };
  const jumpToPresent = () => {
    const el = scrollRef.current;
    if (!el) return;
    atBottom.current = true;
    setShowJump(false);
    clearNewBelow();
    dropReading();
    // The newest messages are not loaded after a jump far back, so they are fetched again.
    if (hasNewer) returnToPresent(channel.id);
    else toBottom(el);
  };

  // apps that read every message here: the start of the channel names them with the encryption line
  const readingIds = useReadingApps(channel.server_id ?? null, channel.id);
  const readers = readingIds.length ? formatList(readingIds.map((id) => displayName(users[id]))) : "";
  const rels = useRelationships((st) => st.byUser);
  const intro = useMemo(() => {
    const name = channelDisplayName(channel, users, me?.id, friendNicknames(rels));
    if (channel.type === "dm") {
      const other = channel.recipient_ids.find((id) => id !== me?.id) ?? "";
      const u = users[other];
      const look = withOwn(undefined, u?.name_style);
      return (
        <div className={s.empty}>
          <div className={s.emptyAvatar}><Avatar id={other} avatarId={u?.avatar_id ?? null} name={name} size={80} /></div>
          <h2 className={[s.emptyName, nameClass(look)].join(" ")} style={nameStyle(look)}>{name}</h2>
          <div className={s.emptyUser}>{u?.username ?? ""}</div>
          <p className={s.emptyText}><Tr id="chat.messageList.dmStart" values={{ name: <b><PersonName userId={other} user={u}>{name}</PersonName></b> }} /></p>
        </div>
      );
    }
    if (channel.type === "thread" || channel.type === "private_thread") return <ThreadIntro thread={channel} />;
    if (channel.type === "subchat") {
      const parent = channel.parent_id ? byIdChannels[channel.parent_id] : undefined;
      const where = parent ? channelDisplayName(parent, users, me?.id) : t("chat.messageList.thisConversation");
      return (
        <div className={s.empty}>
          <h2 className={s.emptyName}>{name}</h2>
          <p className={s.emptyText}><Tr id="chat.messageList.subchatStart" values={{ where: <b>{where}</b> }} /></p>
        </div>
      );
    }
    // a group of one (made with nobody picked) says how to fill it
    const alone = channel.type === "group_dm" && channel.recipient_ids.length <= 1;
    return (
      <div className={s.empty}>
        {channel.type === "group_dm" && channel.icon_id ? <div className={s.emptyAvatar}><Avatar id={channel.id} avatarId={channel.icon_id} name={name} size={80} kind="icon" /></div> : null}
        <h2 className={s.emptyName}>{channel.server_id ? t("chat.messageList.welcomeToChannel", { name }) : name}</h2>
        {alone ? <GroupAlone channelId={channel.id} /> : <p className={s.emptyText}>
          {channel.server_id
            ? <Tr id="chat.messageList.channelStart" values={{ name: <b>#{name}</b> }} />
            : <Tr id="chat.messageList.groupStart" values={{ name: <b>{name}</b> }} />}
          {channel.server_id && channel.topic ? ` ${plainText(parse(channel.topic)).replace(/\s+/g, " ").trim()}` : ""} {readers ? t("apps.reading.endToEndExcept", { apps: readers }) : t("chat.messageList.endToEnd")}
        </p>}
      </div>
    );
  }, [channel, users, me?.id, byIdChannels, readers, rels]);

  useEffect(() => {
    if (loaded || loading) return;
    if (failed === 0) {
      void loadInitial(channel.id);
      return;
    }
    // After a failure, try again once the connection is back or after a pause
    // that doubles each time, up to half a minute.
    const retry = () => void loadInitial(channel.id);
    const timer = setTimeout(retry, Math.min(30_000, 1000 * 2 ** (failed - 1)));
    window.addEventListener("online", retry);
    return () => {
      clearTimeout(timer);
      window.removeEventListener("online", retry);
    };
  }, [channel.id, loaded, loading, failed, loadInitial]);

  useEffect(() => {
    const onScrollKey = (e: Event) => {
      const el = scrollRef.current;
      if (!el) return;
      const dir = (e as CustomEvent<number>).detail;
      el.scrollBy({ top: dir * el.clientHeight * 0.9, behavior: "smooth" });
    };
    window.addEventListener("ec:scroll", onScrollKey);
    return () => window.removeEventListener("ec:scroll", onScrollKey);
  }, []);

  useEffect(
    () => () => {
      if (highlightTimer.current) clearTimeout(highlightTimer.current);
    },
    [],
  );

  // No dependencies: every change to atBottom also flips showJump, so this runs
  // whenever the answer can have changed. The dispatcher reads it to decide
  // whether an arriving message was really in front of the reader.
  useEffect(() => {
    setReadingPresent(atBottom.current && loaded && !hasNewer ? channel.id : null);
  });
  useEffect(() => () => setReadingPresent(null), []);

  return (
    <div className={[s.listWrap, compact ? s.compact : ""].join(" ")} data-selecting={selecting || undefined}>
      {newBelow ? (
        <button type="button" className={s.newPill} onClick={jumpToPresent} data-testid="new-messages">
          <span className={s.newPillText}>{newSince()}</span>
          <span className={s.newPillJump}>{t("chat.messageList.jumpToPresent")}</span>
        </button>
      ) : null}
      <div className="visually-hidden" role="status">{said ? <span key={said.n}>{said.text}</span> : null}</div>
      <div
        className={s.scroll}
        ref={scrollRef}
        onScroll={onScroll}
        data-scroller=""
        role="region"
        tabIndex={0}
        aria-label={t("modals.quickSwitcher.messages")}
        onKeyDown={onListKey}
        // a finger has no hover: only a mouse or a pen points at a row
        onPointerOver={(e) => e.pointerType !== "touch" && activeRows.hover(rowIdOf(e.target))}
        onPointerLeave={() => activeRows.hover(null)}
        onFocus={(e) => activeRows.focus(rowIdOf(e.target))}
        onBlur={(e) => {
          if (!(e.relatedTarget instanceof Node && e.currentTarget.contains(e.relatedTarget))) activeRows.focus(null);
        }}
      >
        <div className={s.inner}>
          {!hasOlder ? (
            intro
          ) : !loaded && !loading && failed > 0 ? (
            <div className={s.loadError}>
              <span>{t("chat.messageList.messagesCouldNotBeLoaded")}</span>
              <button className={s.loadRetry} onClick={() => void loadInitial(channel.id)}>{t("chat.messageList.tryAgain")}</button>
            </div>
          ) : (
            // one height, spinning or not: a page asked for moves nothing before it lands
            <div className={s.loader} data-busy={(loading && !newerBusy) || !loaded || heldAbove || capped || undefined}>
              {(loading && !newerBusy) || !loaded || heldAbove || capped ? <Spinner size={24} /> : null}
            </div>
          )}
          <RenderCtxProvider value={renderCtx}>
            <ActiveRowsProvider value={activeRows}>
            {rows.map((row, i) => {
              if (row.kind === "date") return <div key={row.key} className={s.dateDivider}><span>{formatDateDivider(row.ts)}</span></div>;
              if (row.kind === "unread") return <div key={row.key} className={s.unreadDivider}><span>{t("chat.messageList.new")}</span></div>;
              if (row.kind === "blocked") return <BlockedRun key={row.key} runKey={row.key} count={row.ids.length} open={row.open} compact={compact} onToggle={toggleRun} />;
              if (row.kind === "notice") return <GroupAddNoticeRow key={row.key} notice={row.notice} />;
              if (row.kind === "pending") return <MessageItem key={row.key} msg={pendingToMessage(row.p)} groupStart={row.groupStart} pending={row.p.status} upload={row.p.upload} />;
              return <MessageItem key={row.key} msg={row.msg} groupStart={row.groupStart} highlight={highlight === row.msg.id} first={i < 2} />;
            })}
            </ActiveRowsProvider>
          </RenderCtxProvider>
          {hasNewer && loaded ? <div className={s.loader}><Spinner size={24} /></div> : null}
        </div>
      </div>
      {showJump && ids.length > 0 && !newBelow ? (
        <button className={s.jumpBtn} aria-label={t("chat.messageList.jumpToPresent")} onClick={jumpToPresent}><ArrowDown size={18} /></button>
      ) : null}
    </div>
  );
}
