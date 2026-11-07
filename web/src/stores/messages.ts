import { create } from "zustand";
import { fetchMissingUsers } from "./users";
import { messages as messagesApi } from "@/api/messages";
import type { Message, PollTally, ReactionSummary } from "@/api/types";
import type { MessagePayload } from "@/crypto/message";
import { decryptForStore, type DecryptedMessage, type DecryptError } from "@/lib/decrypt";
import { stage as perfStage } from "@/lib/perfReports";
import { compareIds } from "@/lib/ids";
import { ingestReferences, useReplyTargets } from "./replyTargets";

/** Authors of loaded history may no longer be members or friends; fetch the ones the users store lacks. */
function resolveAuthors(msgs: { author_id: string; mentions?: string[] }[]): void {
  fetchMissingUsers(msgs.flatMap((m) => [m.author_id, ...(m.mentions ?? [])]));
}

export type { DecryptedMessage };

/**
 * Told about every page of history the chat fetches and decrypts, so the
 * search index can take what the reader has already paid for, and notice
 * what was edited or deleted while nobody was here to hear about it.
 */
export const historyHooks: ((channelId: string, page: DecryptedMessage[]) => void)[] = [];

function sawPage(channelId: string, page: DecryptedMessage[]): void {
  if (page.length === 0) return;
  for (const h of historyHooks) h(channelId, page);
}

/** A message's files on their way up, shown in its place in the list until it is sent. */
export interface PendingUpload {
  files: { id: string; name: string; size: number; type: string; preview: string | null }[];
  /** Bytes of the files up so far, of `total`. */
  loaded: number;
  total: number;
  /** Bytes a second, smoothed. */
  speed: number;
  /** Every file is up; the message itself is on its way. */
  done: boolean;
  /** Why the files could not go up. */
  error: string | null;
}

export interface PendingMessage {
  tmpId: string;
  nonce: string;
  channelId: string;
  authorId: string;
  payload: MessagePayload;
  createdAt: number;
  status: "sending" | "failed";
  replyToId?: string;
  /** The reply's ping choice and the message flags, so a retry sends what was chosen. */
  ping?: boolean;
  flags?: number;
  /** Files still going up with it. */
  upload?: PendingUpload;
}

export interface ChannelMessages {
  ids: string[];
  byId: Record<string, DecryptedMessage>;
  hasOlder: boolean;
  /** The list stops short of the newest message, after a jump far back into history. */
  hasNewer: boolean;
  loading: boolean;
  loaded: boolean;
  /** First-page loads that failed in a row, so the list can back off instead of asking again at once. */
  failed: number;
  pending: PendingMessage[];
}

const PAGE = 50;
// Pages of already-loaded history read again after a reconnect. Past this the
// older end of a very long reading session is left as it was.
const REFRESH_PAGES = 10;

/**
 * The most messages a conversation keeps loaded while it is open, around
 * where the reader is. Scrolling through history adds a page at a time, and
 * once the list is longer than this the end farthest from the reader is let
 * go (it loads again on the way back). Kept whole, scrolling far up a long
 * conversation full of pictures held thousands of rows in the page, and a
 * phone slowed until it could not be used.
 */
export const WINDOW = 250;
/**
 * How long a list may grow before it is cut back to WINDOW. The list does the
 * cutting when it is still (MessageList), never under a moving finger, so
 * past this no more pages are loaded until it has.
 */
export const WINDOW_CAP = WINDOW + 3 * PAGE;

/**
 * Conversations with a list on screen, by how many. Live messages for any
 * other conversation keep only its newest few, however long it was open
 * before: a busy channel looked at once used to collect every message sent
 * in it for as long as the app stayed connected.
 */
const openLists = new Map<string, number>();

/** Whether a conversation has a list showing it. */
export function isOpenList(channelId: string): boolean {
  return (openLists.get(channelId) ?? 0) > 0;
}

const emptyChannel = (): ChannelMessages => ({ ids: [], byId: {}, hasOlder: true, hasNewer: false, loading: false, loaded: false, failed: 0, pending: [] });

// A load started before its channel was cleared or sent back to the present
// must not write its page into the fresh list, or the list would show two
// unrelated stretches of history as if they were one.
const generations = new Map<string, number>();
const generation = (channelId: string): number => generations.get(channelId) ?? 0;
const nextGeneration = (channelId: string): void => {
  generations.set(channelId, generation(channelId) + 1);
};

interface MessagesState {
  channels: Record<string, ChannelMessages>;
  ensure: (channelId: string) => ChannelMessages;
  insert: (channelId: string, msgs: DecryptedMessage[]) => void;
  /**
   * Puts a message in and takes its unsent copy out together. Two steps would
   * leave one frame showing both, or neither, which reads as a flicker.
   */
  settle: (channelId: string, msgs: DecryptedMessage[], drop: { tmpId?: string; nonce?: string }) => void;
  update: (channelId: string, msg: DecryptedMessage) => void;
  remove: (channelId: string, id: string) => void;
  removeMany: (channelId: string, ids: string[]) => void;
  setReaction: (channelId: string, messageId: string, emoji: string, userId: string, add: boolean, isMe: boolean) => void;
  /** Takes a message's reactions away: every one, or every one of one emoji. */
  clearReactions: (channelId: string, messageId: string, emoji?: string) => void;
  setDecryption: (channelId: string, id: string, payload: MessagePayload | undefined, error: DecryptError | undefined) => void;
  /** A vote on a poll added or taken back, by anyone; `isMe` when it was this account's. */
  /** Counts one vote coming or going; `voters` is how many people have voted now, where the server says. */
  setPollVote: (channelId: string, messageId: string, answerId: number, add: boolean, isMe: boolean, voters?: number) => void;
  /** A poll's standing as the server just counted it for this account. */
  setPollResults: (channelId: string, messageId: string, results: PollTally) => void;
  setFlags: (channelId: string, id: string, flags: number) => void;
  /** A thread was started from a message: it says so under it. */
  setThread: (channelId: string, id: string, thread: NonNullable<DecryptedMessage["thread"]>) => void;
  setLoading: (channelId: string, loading: boolean) => void;
  setHasOlder: (channelId: string, hasOlder: boolean) => void;
  addPending: (p: PendingMessage) => void;
  removePending: (channelId: string, tmpId: string) => void;
  failPending: (channelId: string, tmpId: string) => void;
  updatePending: (channelId: string, tmpId: string, patch: Partial<PendingMessage>) => void;
  resolvePendingByNonce: (channelId: string, nonce: string) => void;
  clearChannel: (channelId: string) => void;
  /** Forgets every conversation's messages but those of the channels in `keep`. */
  clearAll: (keep?: Iterable<string>) => void;
  loadInitial: (channelId: string) => Promise<void>;
  /** Fills in whatever arrived while the socket was down, keeping what is on screen. */
  syncGap: (channelId: string) => Promise<void>;
  loadOlder: (channelId: string) => Promise<void>;
  loadAround: (channelId: string, messageId: string) => Promise<void>;
  loadNewer: (channelId: string) => Promise<void>;
  /** Drops a list that stops short of the newest message so the newest page loads again. Unsent messages stay. */
  returnToPresent: (channelId: string) => void;
  /**
   * A list showing this conversation came on screen; the function it returns
   * is for when it goes. When the last one goes the conversation is cut back
   * to its newest page, or forgotten if it stopped short of the present.
   */
  open: (channelId: string) => () => void;
  /**
   * Cuts a list longer than WINDOW back to WINDOW messages around `anchor`
   * (the message being read; the newest when absent), marking either end
   * that lost messages as having more to load. Does nothing while a page is
   * loading, which would land next to messages that are no longer there.
   */
  trimAround: (channelId: string, anchor: string | null) => void;
  /**
   * Forgets the first-page loads that failed, so the list asks again at once
   * instead of after its pause: the server refused them for a reason that has
   * just gone away (a subchat's password was typed).
   */
  forgetFailures: (channelId: string) => void;
  redecrypt: (predicate: (m: DecryptedMessage) => boolean) => Promise<void>;
}

function withChannel(s: MessagesState, channelId: string, fn: (c: ChannelMessages) => ChannelMessages): Partial<MessagesState> {
  const cur = s.channels[channelId] ?? emptyChannel();
  const next = fn(cur);
  // Unchanged means unchanged. Writing the entry back anyway built a new map on
  // every no-op, waking everything that watches the whole of it, and put a
  // fresh empty record back for channels that had just been deleted — which
  // then read as channels the store has.
  if (next === cur) return { channels: s.channels };
  return { channels: { ...s.channels, [channelId]: next } };
}

/** Adds messages to a list, keeping ids ascending and unique. */
function put(c: ChannelMessages, msgs: DecryptedMessage[]): ChannelMessages {
  if (msgs.length === 0) return c;
  const byId = { ...c.byId };
  const fresh: string[] = [];
  for (const m of msgs) {
    if (!(m.id in byId)) fresh.push(m.id);
    byId[m.id] = m;
  }
  return { ...c, byId, ids: mergeIds(c.ids, fresh) };
}

/**
 * Adds messages that have just arrived. A list that stops short of the present
 * cannot take messages past its end, because the ones in between are missing;
 * they come with the newer pages instead.
 */
/**
 * How many live messages a conversation nobody has opened keeps. Every
 * message anywhere arrives over the gateway; kept in full, a device left
 * connected for days held every message of every busy channel, decrypted,
 * and walked all of them again on each key grant. The newest few are enough
 * for what reads them before the conversation is opened (a mention taken
 * back, a notification); opening it loads its first page as usual.
 */
const UNOPENED_KEEP = 50;

function keepNewest(c: ChannelMessages, n: number): ChannelMessages {
  if (c.ids.length <= n) return c;
  const drop = c.ids.slice(0, c.ids.length - n);
  const byId = { ...c.byId };
  for (const id of drop) delete byId[id];
  // what went is older history again, to be loaded when it is scrolled to
  return { ...c, ids: c.ids.slice(-n), byId, hasOlder: true };
}

/** Keeps ids[lo, hi) of a list, marking either end that lost messages as having more to load. */
function keepRange(c: ChannelMessages, lo: number, hi: number): ChannelMessages {
  if (lo <= 0 && hi >= c.ids.length) return c;
  const ids = c.ids.slice(lo, hi);
  const byId: Record<string, DecryptedMessage> = {};
  for (const id of ids) {
    const m = c.byId[id];
    if (m) byId[id] = m;
  }
  return { ...c, ids, byId, hasOlder: c.hasOlder || lo > 0, hasNewer: c.hasNewer || hi < c.ids.length };
}

/** A list as live messages leave it: whole while it is on screen, its newest few otherwise. */
function bounded(channelId: string, c: ChannelMessages): ChannelMessages {
  return isOpenList(channelId) ? c : keepNewest(c, UNOPENED_KEEP);
}

/** Takes the originals the server sent along with these messages' replies (stores/replyTargets). */
function sawReferences(msgs: readonly DecryptedMessage[]): void {
  if (msgs.some((m) => m.reply_to_id && m.referenced_message !== undefined)) void ingestReferences(msgs);
}

function arrived(c: ChannelMessages, msgs: DecryptedMessage[]): ChannelMessages {
  const newest = c.ids[c.ids.length - 1];
  if (!c.hasNewer || !newest) return put(c, msgs);
  return put(c, msgs.filter((m) => compareIds(m.id, newest) <= 0));
}

/** The server's copy of a loaded message, reusing the decryption, and the object itself when nothing changed. */
async function refreshed(prev: DecryptedMessage, m: Message): Promise<DecryptedMessage> {
  // a webhook's message can change without an edit: its link previews come in after it
  if (prev.edited_at !== m.edited_at || JSON.stringify(prev.ciphertext) !== JSON.stringify(m.ciphertext) || JSON.stringify(prev.webhook) !== JSON.stringify(m.webhook)) return decryptForStore(m);
  if (prev.flags === m.flags && JSON.stringify(prev.reactions) === JSON.stringify(m.reactions) && JSON.stringify(prev.attachments) === JSON.stringify(m.attachments) && JSON.stringify(prev.poll_results) === JSON.stringify(m.poll_results)) return prev;
  const next: DecryptedMessage = { ...m };
  if (prev.payload !== undefined) next.payload = prev.payload;
  if (prev.decryptError !== undefined) next.decryptError = prev.decryptError;
  if (prev.hook !== undefined) next.hook = prev.hook;
  return next;
}

function mergeIds(existing: string[], added: string[]): string[] {
  if (added.length === 0) return existing;
  const last = existing[existing.length - 1];
  if (added.length === 1 && added[0] !== undefined && (last === undefined || compareIds(added[0], last) > 0)) {
    return [...existing, added[0]];
  }
  const set = new Set(existing);
  const out = existing.slice();
  for (const id of added) {
    if (!set.has(id)) {
      set.add(id);
      out.push(id);
    }
  }
  out.sort(compareIds);
  return out;
}

/** Decrypts a page of history, timing it for the performance report. */
async function decryptPage(page: Message[]): Promise<DecryptedMessage[]> {
  const began = performance.now();
  const out = await Promise.all(page.map(decryptForStore));
  if (page.length) perfStage("decrypt", performance.now() - began);
  return out;
}

export const useMessages = create<MessagesState>()((set, get) => ({
  channels: {},
  ensure: (channelId) => get().channels[channelId] ?? emptyChannel(),
  insert: (channelId, msgs) => {
    if (msgs.length === 0) return;
    resolveAuthors(msgs);
    sawReferences(msgs);
    set((s) => withChannel(s, channelId, (c) => bounded(channelId, arrived(c, msgs))));
  },
  settle: (channelId, msgs, drop) => {
    resolveAuthors(msgs);
    sawReferences(msgs);
    set((s) =>
      withChannel(s, channelId, (c) => {
        const kept = c.pending.filter((p) => (drop.tmpId ? p.tmpId !== drop.tmpId : true) && (drop.nonce ? p.nonce !== drop.nonce : true));
        const next = bounded(channelId, arrived(c, msgs));
        return kept.length === c.pending.length ? next : { ...next, pending: kept };
      }),
    );
  },

  update: (channelId, msg) => {
    // a quoted original follows its edits, loaded here or not
    useReplyTargets.getState().refresh(msg);
    sawReferences([msg]);
    set((s) =>
      withChannel(s, channelId, (c) => {
        const prev = c.byId[msg.id];
        if (!prev) return c;
        // The edited copy carries the editor's own "me" flags on its reactions,
        // so the reactions already here stay; their own events keep them current.
        // So does the thread it started: an edited copy (a person's, an app's,
        // an app's answer) comes without it, and thread events keep it current.
        // A poll's counts come from the copy, which was counted for nobody, so
        // which answers are this account's comes from the one already here.
        const next: DecryptedMessage = { ...msg, reactions: prev.reactions, thread: msg.thread ?? prev.thread };
        if (msg.poll_results && prev.poll_results) {
          const mine = new Set(prev.poll_results.answer_counts.filter((a) => a.me_voted).map((a) => a.id));
          next.poll_results = { ...msg.poll_results, answer_counts: msg.poll_results.answer_counts.map((a) => ({ ...a, me_voted: mine.has(a.id) })) };
        }
        return { ...c, byId: { ...c.byId, [msg.id]: next } };
      }),
    );
  },
  remove: (channelId, id) => get().removeMany(channelId, [id]),
  removeMany: (channelId, ids) => {
    // replies to what went say it was deleted, wherever they are
    useReplyTargets.getState().markDeleted(ids);
    set((s) =>
      withChannel(s, channelId, (c) => {
        const drop = new Set(ids);
        if (!c.ids.some((id) => drop.has(id))) return c;
        const byId = { ...c.byId };
        for (const id of ids) delete byId[id];
        return { ...c, byId, ids: c.ids.filter((id) => !drop.has(id)) };
      }),
    );
  },
  clearReactions: (channelId, messageId, emoji) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const m = c.byId[messageId];
        if (!m) return c;
        const reactions = emoji === undefined ? [] : m.reactions.filter((r) => r.emoji !== emoji);
        return { ...c, byId: { ...c.byId, [messageId]: { ...m, reactions } } };
      }),
    ),
  setReaction: (channelId, messageId, emoji, _userId, add, isMe) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const m = c.byId[messageId];
        if (!m) return c;
        const reactions: ReactionSummary[] = m.reactions.map((r) => ({ ...r }));
        const idx = reactions.findIndex((r) => r.emoji === emoji);
        // your own add arrives twice: once optimistically, once as the echo,
        // so the "me" flag decides whether it has already been counted
        if (add) {
          if (idx >= 0) {
            const r = reactions[idx]!;
            if (isMe && r.me) return c;
            r.count += 1;
            if (isMe) r.me = true;
          } else {
            reactions.push({ emoji, count: 1, me: isMe });
          }
        } else if (idx >= 0) {
          const r = reactions[idx]!;
          if (isMe && !r.me) return c;
          r.count -= 1;
          if (isMe) r.me = false;
          if (r.count <= 0) reactions.splice(idx, 1);
        }
        return { ...c, byId: { ...c.byId, [messageId]: { ...m, reactions } } };
      }),
    ),
  setDecryption: (channelId, id, payload, error) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const m = c.byId[id];
        if (!m) return c;
        const next: DecryptedMessage = { ...m };
        if (payload !== undefined) next.payload = payload;
        else delete next.payload;
        if (error !== undefined) next.decryptError = error;
        else delete next.decryptError;
        return { ...c, byId: { ...c.byId, [id]: next } };
      }),
    ),
  setPollVote: (channelId, messageId, answerId, add, isMe, voters) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const m = c.byId[messageId];
        const tally = m?.poll_results;
        if (!m || !tally) return c;
        const counts = tally.answer_counts.map((a) => ({ ...a }));
        const a = counts.find((x) => x.id === answerId);
        if (!a) return c;
        // the number of voters is as the server counted it, so it can be taken every time
        const total_voters = voters ?? tally.total_voters;
        // your own vote arrives twice, once from the answer to it and once
        // as the echo, so its "me" flag says whether it is already counted
        if (isMe && a.me_voted === add) return total_voters === tally.total_voters ? c : { ...c, byId: { ...c.byId, [messageId]: { ...m, poll_results: { ...tally, total_voters } } } };
        a.count = Math.max(0, a.count + (add ? 1 : -1));
        if (isMe) a.me_voted = add;
        return { ...c, byId: { ...c.byId, [messageId]: { ...m, poll_results: { ...tally, answer_counts: counts, total_voters } } } };
      }),
    ),
  setPollResults: (channelId, messageId, results) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const m = c.byId[messageId];
        if (!m) return c;
        return { ...c, byId: { ...c.byId, [messageId]: { ...m, poll_results: results } } };
      }),
    ),
  setFlags: (channelId, id, flags) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const m = c.byId[id];
        if (!m) return c;
        return { ...c, byId: { ...c.byId, [id]: { ...m, flags } } };
      }),
    ),
  setThread: (channelId, id, thread) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        const prev = c.byId[id];
        if (!prev || prev.thread?.id === thread.id) return c;
        return { ...c, byId: { ...c.byId, [id]: { ...prev, thread } } };
      }),
    ),
  setLoading: (channelId, loading) => set((s) => withChannel(s, channelId, (c) => ({ ...c, loading }))),
  setHasOlder: (channelId, hasOlder) => set((s) => withChannel(s, channelId, (c) => ({ ...c, hasOlder }))),
  addPending: (p) => set((s) => withChannel(s, p.channelId, (c) => ({ ...c, pending: [...c.pending, p] }))),
  removePending: (channelId, tmpId) =>
    set((s) => withChannel(s, channelId, (c) => ({ ...c, pending: c.pending.filter((p) => p.tmpId !== tmpId) }))),
  failPending: (channelId, tmpId) =>
    set((s) =>
      withChannel(s, channelId, (c) => ({ ...c, pending: c.pending.map((p) => (p.tmpId === tmpId ? { ...p, status: "failed" } : p)) })),
    ),
  updatePending: (channelId, tmpId, patch) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        if (!c.pending.some((p) => p.tmpId === tmpId)) return c;
        return { ...c, pending: c.pending.map((p) => (p.tmpId === tmpId ? { ...p, ...patch } : p)) };
      }),
    ),
  resolvePendingByNonce: (channelId, nonce) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        if (!c.pending.some((p) => p.nonce === nonce)) return c;
        return { ...c, pending: c.pending.filter((p) => p.nonce !== nonce) };
      }),
    ),
  clearChannel: (channelId) => {
    nextGeneration(channelId);
    set((s) => {
      const channels = { ...s.channels };
      delete channels[channelId];
      return { channels };
    });
  },
  clearAll: (keep) =>
    set((s) => {
      const spared = new Set(keep ?? []);
      for (const id of Object.keys(s.channels)) if (!spared.has(id)) nextGeneration(id);
      // The channels on screen can be spared, so a reconnect does not throw
      // away the reader's place in them. Messages still being sent stay
      // everywhere, with their uploads, however long the reconnect took.
      const channels: Record<string, ChannelMessages> = {};
      for (const [id, c] of Object.entries(s.channels)) {
        if (spared.has(id)) channels[id] = c;
        else if (c.pending.length) channels[id] = { ...emptyChannel(), pending: c.pending };
      }
      return { channels };
    }),
  returnToPresent: (channelId) => {
    nextGeneration(channelId);
    set((s) => withChannel(s, channelId, (c) => ({ ...emptyChannel(), pending: c.pending })));
  },
  open: (channelId) => {
    openLists.set(channelId, (openLists.get(channelId) ?? 0) + 1);
    let closed = false;
    return () => {
      if (closed) return;
      closed = true;
      const left = (openLists.get(channelId) ?? 1) - 1;
      if (left > 0) {
        openLists.set(channelId, left);
        return;
      }
      openLists.delete(channelId);
      const c = get().channels[channelId];
      if (!c) return;
      if (c.hasNewer || c.loading) {
        // A stretch of the past, or a page still on its way: next time the
        // conversation opens at its newest messages, as it would after a
        // reconnect. Whatever is being sent stays, upload and all.
        nextGeneration(channelId);
        set((s) => {
          const channels = { ...s.channels };
          const cur = channels[channelId];
          if (cur?.pending.length) channels[channelId] = { ...emptyChannel(), pending: cur.pending };
          else delete channels[channelId];
          return { channels };
        });
        return;
      }
      // the newest page stays, to show at once if it is opened again
      set((s) => withChannel(s, channelId, (cur) => keepNewest(cur, PAGE)));
    };
  },
  trimAround: (channelId, anchor) =>
    set((s) =>
      withChannel(s, channelId, (c) => {
        if (c.loading || c.ids.length <= WINDOW) return c;
        const n = c.ids.length;
        const found = anchor ? c.ids.indexOf(anchor) : -1;
        const at = found >= 0 ? found : n - 1;
        // the reader in the middle of what stays, or as near it as the ends allow
        const lo = Math.min(Math.max(0, at - Math.floor(WINDOW / 2)), n - WINDOW);
        return keepRange(c, lo, lo + WINDOW);
      }),
    ),
  forgetFailures: (channelId) => set((s) => (s.channels[channelId]?.failed ? withChannel(s, channelId, (c) => ({ ...c, failed: 0 })) : {})),
  loadInitial: async (channelId) => {
    const cur = get().channels[channelId];
    if (cur?.loaded || cur?.loading) return;
    const gen = generation(channelId);
    get().setLoading(channelId, true);
    try {
      const page = await messagesApi.list(channelId, { limit: PAGE });
      const decrypted = await decryptPage(page);
      if (generation(channelId) !== gen) return;
      resolveAuthors(decrypted);
      sawReferences(decrypted);
      set((s) => withChannel(s, channelId, (c) => ({ ...put(c, decrypted), loaded: true, loading: false, failed: 0, hasOlder: page.length >= PAGE, hasNewer: false })));
      sawPage(channelId, decrypted);
    } catch {
      // Counted rather than thrown: the list shows the failure and tries again
      // after a growing pause, instead of asking again the moment loading ends.
      if (generation(channelId) === gen) set((s) => withChannel(s, channelId, (c) => ({ ...c, loading: false, failed: c.failed + 1 })));
    }
  },
  syncGap: async (channelId) => {
    const cur = get().channels[channelId];
    const newest = cur?.ids[cur.ids.length - 1];
    const oldest = cur?.ids[0];
    if (!cur?.loaded || !newest || !oldest) {
      await get().loadInitial(channelId);
      return;
    }
    const gen = generation(channelId);
    try {
      // A list that stops short of the present has no gap at its end to fill;
      // scrolling down brings the newer pages.
      if (!cur.hasNewer) {
        let after = newest;
        let filled = false;
        // Five pages is more than any short disconnect produces; past that it is
        // cheaper to start again from the newest page than to walk the gap.
        for (let i = 0; i < 5 && !filled; i++) {
          const page = await messagesApi.list(channelId, { after, limit: PAGE });
          const decrypted = await decryptPage(page);
          if (generation(channelId) !== gen) return;
          get().insert(channelId, decrypted);
          sawPage(channelId, decrypted);
          filled = page.length < PAGE;
          if (!filled) after = page[page.length - 1]!.id;
        }
        if (!filled) {
          // back to the newest page, keeping what is still being sent: its
          // upload goes on, and the only copy of a failed message's text is here
          get().returnToPresent(channelId);
          await get().loadInitial(channelId);
          return;
        }
      }
      // Messages already on screen may have been edited, deleted or reacted to
      // while the socket was down, and those events are lost. Read the kept
      // stretch again, newest first, and bring it in line with the server.
      let before = (BigInt(newest) + 1n).toString();
      for (let i = 0; i < REFRESH_PAGES; i++) {
        const page = await messagesApi.list(channelId, { before, limit: PAGE });
        if (generation(channelId) !== gen) return;
        const known = get().channels[channelId]?.byId ?? {};
        const fresh = await Promise.all(page.filter((m) => known[m.id]).map((m) => refreshed(known[m.id]!, m)));
        if (generation(channelId) !== gen) return;
        // what the kept replies answer may have changed while away, or gone
        void ingestReferences(page);
        const upto = before;
        // A short page reaches the start of the channel, so everything below it is covered too.
        const floor = page.length >= PAGE ? page[0]!.id : null;
        const returned = new Set(page.map((m) => m.id));
        set((s) =>
          withChannel(s, channelId, (c) => {
            const gone = c.ids.filter((id) => compareIds(id, upto) < 0 && (floor === null || compareIds(id, floor) >= 0) && !returned.has(id));
            // A copy changed by a live event since the page was read is newer than the page.
            const changed = fresh.filter((m) => m !== known[m.id] && c.byId[m.id] === known[m.id]);
            if (gone.length === 0 && changed.length === 0) return c;
            const byId = { ...c.byId };
            for (const m of changed) byId[m.id] = m;
            for (const id of gone) delete byId[id];
            const drop = new Set(gone);
            return { ...c, byId, ids: gone.length > 0 ? c.ids.filter((id) => !drop.has(id)) : c.ids };
          }),
        );
        if (floor === null || compareIds(floor, oldest) <= 0) return;
        before = floor;
      }
    } catch {
      // A request that fails part-way down the walk leaves a hole between what
      // was on screen and what arrived after it, with nothing to say so. The
      // newest page again is the same fallback a gap too wide to walk takes.
      if (generation(channelId) !== gen) return;
      get().returnToPresent(channelId);
      await get().loadInitial(channelId);
    }
  },
  loadOlder: async (channelId) => {
    const cur = get().channels[channelId];
    // a list at its cap waits to be cut back (trimAround) before it grows again
    if (!cur || cur.loading || !cur.hasOlder || cur.ids.length >= WINDOW_CAP) return;
    const oldest = cur.ids[0];
    if (!oldest) return get().loadInitial(channelId);
    const gen = generation(channelId);
    get().setLoading(channelId, true);
    try {
      const page = await messagesApi.list(channelId, { before: oldest, limit: PAGE });
      const decrypted = await decryptPage(page);
      if (generation(channelId) !== gen) return;
      get().setHasOlder(channelId, page.length >= PAGE);
      get().insert(channelId, decrypted);
      sawPage(channelId, decrypted);
    } finally {
      if (generation(channelId) === gen) get().setLoading(channelId, false);
    }
  },
  loadNewer: async (channelId) => {
    const cur = get().channels[channelId];
    const newest = cur?.ids[cur.ids.length - 1];
    if (!cur || cur.loading || !cur.hasNewer || !newest || cur.ids.length >= WINDOW_CAP) return;
    const gen = generation(channelId);
    get().setLoading(channelId, true);
    try {
      const page = await messagesApi.list(channelId, { after: newest, limit: PAGE });
      const decrypted = await decryptPage(page);
      if (generation(channelId) !== gen) return;
      resolveAuthors(decrypted);
      sawReferences(decrypted);
      set((s) => withChannel(s, channelId, (c) => ({ ...put(c, decrypted), hasNewer: page.length >= PAGE })));
      sawPage(channelId, decrypted);
    } finally {
      if (generation(channelId) === gen) get().setLoading(channelId, false);
    }
  },
  loadAround: async (channelId, messageId) => {
    const gen = generation(channelId);
    get().setLoading(channelId, true);
    try {
      const page = await messagesApi.list(channelId, { around: messageId, limit: PAGE });
      const decrypted = await decryptPage(page);
      if (generation(channelId) !== gen) return;
      resolveAuthors(decrypted);
      sawReferences(decrypted);
      sawPage(channelId, decrypted);
      // The server sends up to half a page from before the target and the rest
      // from the target on; a short side means that end of the channel is in the page.
      const from = page.filter((m) => compareIds(m.id, messageId) >= 0).length;
      const reachesPresent = from < PAGE - PAGE / 2;
      const reachesStart = page.length - from < PAGE / 2;
      set((s) =>
        withChannel(s, channelId, (c) => {
          const first = decrypted[0];
          const last = decrypted[decrypted.length - 1];
          const oldest = c.ids[0];
          const newest = c.ids[c.ids.length - 1];
          if (!first || !last) return { ...c, loaded: true };
          const overlaps = !!oldest && !!newest && compareIds(first.id, newest) <= 0 && compareIds(last.id, oldest) >= 0;
          if (overlaps || (reachesPresent && !c.hasNewer)) {
            return {
              ...put(c, decrypted),
              loaded: true,
              hasOlder: !oldest || compareIds(first.id, oldest) <= 0 ? !reachesStart : c.hasOlder,
              hasNewer: c.hasNewer && !reachesPresent,
            };
          }
          // The page is not next to what is loaded. Joining the two would hide
          // everything in between, so the page replaces the list and the rest
          // loads as the reader scrolls either way.
          return { ...put({ ...c, ids: [], byId: {} }, decrypted), loaded: true, hasOlder: !reachesStart, hasNewer: !reachesPresent };
        }),
      );
    } finally {
      if (generation(channelId) === gen) get().setLoading(channelId, false);
    }
  },
  redecrypt: async (predicate) => {
    const jobs: Promise<{ channelId: string; id: string; payload?: MessagePayload; error?: DecryptError }>[] = [];
    for (const [channelId, c] of Object.entries(get().channels)) {
      for (const id of c.ids) {
        const m = c.byId[id];
        if (!m || !predicate(m)) continue;
        jobs.push(decryptForStore(m).then((d) => ({ channelId, id, ...(d.payload !== undefined ? { payload: d.payload } : {}), ...(d.decryptError !== undefined ? { error: d.decryptError } : {}) })));
      }
    }
    if (jobs.length === 0) return;
    const done = await Promise.all(jobs);
    // One write for all of them. A grant arriving for a conversation with a
    // couple of thousand loaded messages used to be a couple of thousand store
    // writes, each waking every subscriber, and the tab stopped for seconds.
    set((s) => {
      const rebuilt = new Map<string, Record<string, DecryptedMessage>>();
      for (const r of done) {
        const c = s.channels[r.channelId];
        if (!c) continue;
        let byId = rebuilt.get(r.channelId);
        if (!byId) {
          byId = { ...c.byId };
          rebuilt.set(r.channelId, byId);
        }
        const m = byId[r.id];
        if (!m) continue;
        const next: DecryptedMessage = { ...m };
        if (r.payload !== undefined) next.payload = r.payload;
        else delete next.payload;
        if (r.error !== undefined) next.decryptError = r.error;
        else delete next.decryptError;
        byId[r.id] = next;
      }
      if (rebuilt.size === 0) return s;
      const channels = { ...s.channels };
      for (const [channelId, byId] of rebuilt) channels[channelId] = { ...channels[channelId]!, byId };
      return { channels };
    });
  },
}));

/**
 * A message this device holds: in its conversation's loaded stretch, or kept
 * as the original some reply quotes (stores/replyTargets).
 */
export function heldMessage(channelId: string, id: string): DecryptedMessage | undefined {
  const loaded = useMessages.getState().channels[channelId]?.byId[id];
  if (loaded) return loaded;
  const kept = useReplyTargets.getState().byId[id];
  return typeof kept === "object" && kept.channel_id === channelId ? kept : undefined;
}

export function convOf(msg: Message): string | null {
  return msg.key_conv_id;
}
