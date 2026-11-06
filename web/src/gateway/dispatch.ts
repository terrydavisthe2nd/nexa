import type { ReportReceipt, SafetyReport } from "@/api/reports";
import { useMyReports } from "@/stores/myReports";
import { useBilling } from "@/stores/billing";
import type { GatewayEvent, MentionMeta } from "./events";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { keysGoneHooks, useKeys } from "@/stores/keys";
import { useMessages } from "@/stores/messages";
import { isThread, useThreads } from "@/stores/threads";
import { noteThreadMessage, useThreadLast } from "@/stores/threadLast";
import { clearAutomod, forgetAutomodRules, heldBy, onArrive, warmAutomod } from "@/lib/automod/rules";
import { forgetFollows } from "@/lib/follows";
import { usePresence } from "@/stores/presence";
import { isUnread, useReadStates } from "@/stores/readStates";
import { useRelationships } from "@/stores/relationships";
import { useServers } from "@/stores/servers";
import { useExpressions } from "@/stores/expressions";
import { useGroupAddNotices } from "@/stores/groupAddNotices";
import { useNotes } from "@/stores/notes";
import { useSettings } from "@/stores/settings";
import { useTyping } from "@/stores/typing";
import { useUI } from "@/stores/ui";
import { applyLockPolicy } from "./session";
import { fetchMissingUsers, useUsers } from "@/stores/users";
import { decryptForStore, type DecryptedMessage } from "@/lib/decrypt";
import { appOnScreen } from "@/lib/notify";
import { readingPresent } from "@/lib/readPosition";
import { setServerNow } from "@/lib/serverClock";
import { channels as channelsApi } from "@/api/channels";
import { ApiError } from "@/api/client";
import { expressions as expressionsApi } from "@/api/expressions";
import { ring } from "@/lib/keyring";
import type { Member, Message, NoteInviteRow, PublicUser, Relationship, ServerView } from "@/api/types";
import { MSG_FLAG_EPHEMERAL } from "@/api/types";
import { useAppCommands } from "@/stores/appCommands";
import { nexa as nexaApi, type Announcement, type MemberCase, type NexaState, type People } from "@/api/moderation";
import { useNexa } from "@/stores/nexa";
import { useDiscord } from "@/stores/discord";
import { emitModEvent, useModeration } from "@/stores/moderation";
import { bugAlertHooks } from "@/lib/bugs";
import { mentionsMe } from "@/lib/mentionsMe";
import { currentBlockRules, isQuiet, loadedAuthorOf } from "@/lib/blocked";
import { loadChunk } from "@/lib/lazyChunk";
import { appEventHooks, each, emitAppEvent, quietly, reportMissed, watched, type AppEvent } from "./appEvents";

type VoiceModule = typeof import("@/voice/session");
let voiceModule: Promise<VoiceModule> | null = null;

// what AutoMod held for this account goes with it
keysGoneHooks.push(clearAutomod);

/**
 * The call engine, fetched the first time an event needs it rather than with
 * the gateway: it is most of a hundred kilobytes, and the gateway comes with
 * pages (an invite, for one) that never show a call.
 */
function voice(): Promise<VoiceModule> {
  voiceModule ??= loadChunk(() => import("@/voice/session")).catch((err: unknown) => {
    voiceModule = null;
    throw err;
  });
  return voiceModule;
}

/** Something from Nexa worth a sound or a notification. */
export type NexaAlert =
  | { kind: "notice" | "reply"; case: MemberCase }
  | { kind: "announcement"; announcement: Announcement }
  | { kind: "appeal"; caseId: string; userId: string }
  | { kind: "report"; report: ReportReceipt }
  /** For staff: someone just sent a safety report. */
  | { kind: "newReport"; report: SafetyReport; people: People };

/** A message edited or deleted, or conversations gone, for what keeps its own copy (the search index). */
export type MessageChange =
  | { kind: "update"; msg: DecryptedMessage }
  | { kind: "delete"; channelId: string; ids: string[] }
  | { kind: "pin"; channelId: string; id: string; pinned: boolean }
  | { kind: "channelsGone"; channelIds: string[] };

/** Hooks other layers register: notifications, voice, servers (later phases). */
export const dispatchHooks: {
  onMessage: ((m: Message, mine: boolean, decrypted: DecryptedMessage) => void)[];
  onUnhandled: ((e: GatewayEvent) => void)[];
  onReady: (() => void)[];
  /** Someone new asked to be friends (not a request already known before). */
  onFriendRequest: ((r: Relationship) => void)[];
  /** Nexa has something new: a notice, a staff reply, an announcement, or for staff a member's appeal. */
  onNexa: ((a: NexaAlert) => void)[];
  /** Someone asked this account into a note. */
  onNoteInvite: ((inv: NoteInviteRow) => void)[];
  /** A message was edited; it arrives decrypted. */
  onMessageUpdate: ((decrypted: DecryptedMessage) => void)[];
  /** Messages were deleted; `was` holds each one as it was loaded here, or undefined. */
  onMessageDelete: ((channelId: string, ids: string[], was: (DecryptedMessage | undefined)[]) => void)[];
  /** A message was edited or deleted, or conversations went away. */
  onMessageChange: ((c: MessageChange) => void)[];
  /** Everything else that happens, once the stores have it (appEvents.ts). */
  onEvent: ((e: AppEvent) => void)[];
} = { onMessage: [], onUnhandled: [], onReady: [], onFriendRequest: [], onNexa: [], onNoteInvite: [], onMessageUpdate: [], onMessageDelete: [], onMessageChange: [], onEvent: appEventHooks };

/** The account's standing, and for staff what is waiting on them. */
function applyNexaState(st: NexaState | undefined): void {
  if (!st) return;
  useNexa.getState().setState(st);
  if (st.staff) {
    useModeration.getState().setCounts(st.staff.counts);
    useModeration.getState().setOwner(st.staff.owner_id);
    useModeration.getState().setBugCounts(st.staff.bugs);
    useModeration.getState().setUnread(st.staff.unread);
    useModeration.getState().setReportCounts(st.staff.reports);
  }
}

/**
 * Which session the stores belong to.
 *
 * An event already past its first await — a decrypt, a grant ingest, a
 * settings load — resumes after sign-out and writes the account that has just
 * gone into the stores the next one will use. Bumping this makes that work
 * stop where it stands, the way the messages store's own generations do.
 */
let sessionGen = 0;

/** Called when a session ends, so nothing still in flight writes into the next one. */
export function endDispatchSession(): void {
  sessionGen += 1;
}

/** The session events are being applied for, so one queued before it ended can be dropped. */
export function dispatchGeneration(): number {
  return sessionGen;
}

/**
 * The session whose ready has been applied, and who wants to know when one is.
 * Kept here, where ready is applied, rather than by whoever cares: a hook a
 * lazily loaded page added when it loaded missed the ready that came before
 * it, and a conversation opened after that never knew its read positions were
 * in, so it never marked anything read.
 */
let readyGen = -1;
const readyListeners = new Set<() => void>();

/** Whether this session's ready, and every read position with it, has been applied. */
export function readyApplied(): boolean {
  return readyGen === sessionGen;
}

/** Calls fn whenever a ready has been applied; returns how to stop. */
export function onReadyApplied(fn: () => void): () => void {
  readyListeners.add(fn);
  return () => {
    readyListeners.delete(fn);
  };
}

// Channels are not cleared here: ready replaces them in one step, because a
// call whose channel vanished from the store even for a moment is ended.
export function resetAll(keepMessagesIn: string[] = []): void {
  // emptied, nobody has left anything or stopped typing
  quietly(() => clearStores(keepMessagesIn));
}

function clearStores(keepMessagesIn: string[]): void {
  useMessages.getState().clearAll(keepMessagesIn);
  // Ready does not bring back everyone the kept conversations show (people in
  // a large server, people who left), and their messages are never looked up
  // again, so their authors would turn into "Unknown User".
  useUsers.setState({ byId: usersShownIn(keepMessagesIn) });
  usePresence.getState().clear();
  useReadStates.getState().clear();
  useRelationships.getState().clear();
  useTyping.getState().clear();
  useServers.getState().clear();
  useExpressions.getState().clear();
  useGroupAddNotices.getState().clear();
}

function usersShownIn(channelIds: string[]): Record<string, PublicUser> {
  const known = useUsers.getState().byId;
  const kept: Record<string, PublicUser> = {};
  const keep = (id: string): void => {
    const u = known[id];
    if (u) kept[id] = u;
  };
  for (const channelId of channelIds) {
    const ch = useMessages.getState().channels[channelId];
    for (const m of Object.values(ch?.byId ?? {})) {
      keep(m.author_id);
      for (const id of m.mentions) keep(id);
    }
  }
  return kept;
}

function ingestServerView(view: ServerView): void {
  useChannels.getState().upsert(view.channels);
  useThreads.getState().setServer(view.id, view.threads ?? [], view.joined_threads ?? []);
  useServers.getState().setFromView(view);
}

const channelFetches = new Set<string>();
/** Key rotations under way, by conversation. */
const rotations = new Map<string, Promise<void>>();

/** Sign-out: the rotations queued for the account that has gone are not the next one's to wait behind. */
export function forgetRotations(): void {
  rotations.clear();
}

/** A message can arrive for a conversation this client closed or never saw. */
async function ensureChannel(id: string): Promise<void> {
  if (useChannels.getState().byId[id] || channelFetches.has(id)) return;
  channelFetches.add(id);
  const gen = sessionGen;
  try {
    const ch = await channelsApi.get(id);
    if (gen === sessionGen) useChannels.getState().upsert(ch);
  } catch (err) {
    // Gone, or not this account's to see: a message that raced the channel's
    // deletion would otherwise leave a list behind for a channel nothing shows.
    if (gen === sessionGen && err instanceof ApiError && (err.status === 403 || err.status === 404)) useMessages.getState().clearChannel(id);
  } finally {
    channelFetches.delete(id);
  }
}

/** Fetches someone this device has not loaded; unknown users render as placeholders meanwhile. */
function ensureUser(id: string): void {
  fetchMissingUsers([id]);
}

/**
 * Whether a message was one this account's mention count was bumped for: by
 * the copy loaded here, or else by what its event says it mentioned — the
 * badge counted messages this device never loaded too.
 */
/**
 * Whether a message going away was one kept quiet when it came (lib/blocked):
 * never counted, so there is nothing to take off. The loaded copy says best;
 * failing that, whoever the event says wrote it.
 */
function quietMessage(channelId: string, messageId: string, author: string | undefined): boolean {
  const rules = currentBlockRules();
  if (rules.blocked.size === 0) return false;
  const loaded = useMessages.getState().channels[channelId]?.byId[messageId];
  if (loaded) return isQuiet(loaded, rules, loadedAuthorOf(channelId));
  return !!author && rules.blocked.has(author);
}

/** A member as the store has them once a change is in, or as the event told it when the store keeps no members for that server. */
function memberNow(serverId: string, told: Member): Member {
  return useServers.getState().byId[serverId]?.members[told.user_id] ?? told;
}

function mentionedMe(channelId: string, messageId: string, meId: string | undefined, told?: Partial<MentionMeta>): boolean {
  if (!meId) return false;
  const loaded = useMessages.getState().channels[channelId]?.byId[messageId];
  if (loaded) return loaded.author_id !== meId && mentionsMe(loaded, meId);
  if (!told?.mentions || !told.author_id) return false;
  return told.author_id !== meId && mentionsMe({ channel_id: channelId, mentions: told.mentions, mention_roles: told.mention_roles ?? [], mention_everyone: !!told.mention_everyone }, meId);
}

export async function applyEvent(e: GatewayEvent): Promise<void> {
  const meId = useAuth.getState().me?.id;
  const gen = sessionGen;
  const gone = (): boolean => gen !== sessionGen;
  switch (e.t) {
    case "ready": {
      const d = e.d;
      setServerNow(d.now);
      // A reconnect re-sends ready. Everything else is rebuilt from the
      // payload, but the conversation on screen keeps its messages, so the
      // reader stays where they were instead of being thrown to the newest
      // message.
      const active = useUI.getState().activeChannelId;
      const shown = active ? [active] : [];
      const servers = d.servers ?? [];
      // A reconnect: what changed while the connection was down is told once
      // the stores are filled again. A first ready has nothing to compare with.
      const reconnect = useAuth.getState().me?.id === d.user.id && (Object.keys(useServers.getState().byId).length > 0 || Object.keys(useRelationships.getState().byUser).length > 0);
      const before = watched();
      // filled from ready, nothing has been joined or changed: the store watchers stay quiet
      quietly(() => {
        resetAll(shown);
        // Merged, not replaced: a field the gateway leaves out must not read as
        // "none" here, or the next profile save sends that back and wipes it.
        const was = useAuth.getState().me;
        useAuth.getState().setMe(was && was.id === d.user.id ? { ...was, ...d.user } : d.user);
        useUsers.getState().upsert([...d.users, d.user]);
        // a reconnect may have missed what Nexa said meanwhile: open views load it again
        useNexa.setState({ feed: null, threads: {} });
        applyNexaState(d.nexa);
        const channels = [...d.channels, ...servers.flatMap((v) => v.channels), ...servers.flatMap((v) => v.threads ?? [])];
        useChannels.setState({ byId: Object.fromEntries(channels.map((c) => [c.id, c])) });
        useThreads.getState().clear();
        for (const v of servers) useThreads.getState().setServer(v.id, v.threads ?? [], v.joined_threads ?? []);
        useRelationships.getState().setMany(d.relationships);
        useReadStates.getState().setMany(d.read_states);
        useGroupAddNotices.getState().setAll(d.group_add_notices ?? []);
        usePresence.getState().setMany(d.presences);
        for (const view of servers) useServers.getState().setFromView(view);
      });
      if (reconnect) reportMissed(before);
      useExpressions.getState().setAllEmojis(d.emojis ?? []);
      useExpressions.getState().setPacks(d.sticker_packs ?? []);
      // Recents ride REST rather than ready: one small request after the
      // reconnect storm, and every device lands on the same saved row.
      void expressionsApi
        .recentStickers()
        .then((list) => {
          if (!gone()) useExpressions.getState().setRecents(list);
        })
        .catch(() => undefined);
      // Not waited for: the engine's first load must not hold up the settings,
      // the keys and the conversations below. Call events after this one wait
      // on the same load, so they still come after it.
      void voice()
        .then((v) => {
          if (!gone()) v.onReadyVoice(d.voice_states ?? [], d.session_id);
        })
        .catch((err: unknown) => console.warn("voice: could not load the call engine", err));
      const settings = useSettings.getState();
      await settings.load(d.settings?.blob ?? null, d.settings?.version ?? 0);
      if (gone()) return;
      // The account's passcode policy only arrives with the settings, so this
      // is the first moment a fresh device can know it needs to ask for one.
      applyLockPolicy();
      await useKeys.getState().ingestGrants(d.key_grants);
      if (gone()) return;
      readyGen = sessionGen;
      // the rules members' apps apply, from the servers that have any
      warmAutomod(servers.filter((v) => (v.automod_at ?? 0) > 0).map((v) => v.id));
      for (const fn of [...readyListeners]) fn();
      each(dispatchHooks.onReady, (h) => h());
      void useKeys.getState().reconcile();
      void useDiscord.getState().load();
      // The notes ride REST, like the stickers above. Asked for again on every
      // ready rather than cleared first: a note open on the page keeps what it
      // has while the list catches up with whatever the reconnect missed.
      void useNotes.getState().load(true);
      for (const id of shown) void useMessages.getState().syncGap(id);
      return;
    }
    case "message_create": {
      const m = e.d;
      const decrypted = await decryptForStore(m);
      if (gone()) return;
      const messages = useMessages.getState();
      // An app's answer only this person sees: it shows where it was asked
      // for, and is no part of the conversation (nothing unread, nothing newest).
      if (m.flags & MSG_FLAG_EPHEMERAL) {
        messages.insert(m.channel_id, [decrypted]);
        ensureUser(m.author_id);
        return;
      }
      if (m.nonce && meId === m.author_id) messages.settle(m.channel_id, [decrypted], { nonce: m.nonce });
      else messages.insert(m.channel_id, [decrypted]);
      // AutoMod reads what arrives (a moderator's app clears away what a rule stops)
      onArrive(decrypted, meId ?? undefined);
      const held = !!heldBy(decrypted, meId ?? undefined);
      // what was newest before this one, to tell whether the conversation had been read
      const lastBefore = useChannels.getState().byId[m.channel_id]?.last_message_id ?? null;
      useChannels.getState().setLastMessage(m.channel_id, m.id);
      // a thread counts what is said in it, for the message it hangs from, and shows the newest under it
      const thread = useChannels.getState().byId[m.channel_id];
      if (thread?.thread && !m.type.startsWith("system_") && lastBefore !== m.id) {
        useChannels.getState().upsert({ ...thread, thread: { ...thread.thread, message_count: thread.thread.message_count + 1, archived: false } });
        if (thread.type === "thread") noteThreadMessage(m.channel_id, decrypted);
      }
      useTyping.getState().remove(m.channel_id, m.author_id);
      if (decrypted.decryptError === "no_key" && m.key_conv_id) useKeys.getState().markWaiting(m.key_conv_id);
      ensureUser(m.author_id);
      void ensureChannel(m.channel_id);
      const mine = meId === m.author_id;
      // The line closing a person's poll speaks for whoever asked it, but the
      // server wrote it, on a timer: it marks nothing before it read.
      const acted = mine && m.type !== "system_poll_result";
      const ui = useUI.getState();
      // Seen means seen: the channel open, the app in front of the person, and
      // the list showing the present at the bottom of it. Without the last two
      // this marked read a message five hundred messages below where the
      // reader was — and `ui.focused` has two writers, one of which the
      // desktop app is documented to lie to.
      const visible = ui.activeChannelId === m.channel_id && appOnScreen() && readingPresent(m.channel_id);
      if (acted || visible) {
        void useReadStates.getState().ack(m.channel_id, m.id);
      } else if (held || isQuiet(decrypted, currentBlockRules(), loadedAuthorOf(m.channel_id))) {
        // Someone blocked (or a reply to them, when those are hidden): no badge,
        // no mention, and a conversation that was read up to here stays read.
        if (!isUnread(useReadStates.getState().byChannel[m.channel_id], lastBefore)) void useReadStates.getState().ack(m.channel_id, m.id);
      } else {
        if (meId && mentionsMe(m, meId)) useReadStates.getState().bumpMentions(m.channel_id);
        // conversations count every message, so their badges say how many are waiting
        const type = useChannels.getState().byId[m.channel_id]?.type;
        // none of your own, as the server counts them
        if (!mine && (!type || type === "dm" || type === "group_dm" || type === "subchat")) useReadStates.getState().bumpUnread(m.channel_id);
      }
      each(dispatchHooks.onMessage, (h) => h(m, mine, decrypted));
      return;
    }
    case "message_update": {
      const decrypted = await decryptForStore(e.d);
      if (gone()) return;
      // An edit can add a mention the badge should count or take one back
      // that it did; the event says what the message mentioned before. Someone
      // blocked was never counted either way.
      if (meId && e.d.was && e.d.author_id !== meId && !currentBlockRules().blocked.has(e.d.author_id)) {
        const before = mentionsMe({ ...e.d.was, channel_id: e.d.channel_id }, meId);
        const after = mentionsMe(e.d, meId);
        if (before && !after) useReadStates.getState().dropMention(e.d.channel_id, e.d.id);
        else if (!before && after) useReadStates.getState().addMention(e.d.channel_id, e.d.id);
      }
      useMessages.getState().update(e.d.channel_id, decrypted);
      each(dispatchHooks.onMessageUpdate, (h) => h(decrypted));
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "update", msg: decrypted }));
      return;
    }
    case "message_delete": {
      if (e.d.ephemeral) {
        useMessages.getState().remove(e.d.channel_id, e.d.id);
        return;
      }
      // Read before it goes: only the message itself says whether it was
      // counted as a mention, and a mention taken back as unread but not as a
      // mention leaves a red pill on a channel with nothing left to read.
      const counted = mentionedMe(e.d.channel_id, e.d.id, meId, e.d);
      // nor is a message from someone blocked counted, so its going takes nothing off
      const uncounted = quietMessage(e.d.channel_id, e.d.id, e.d.author_id);
      const was = useMessages.getState().channels[e.d.channel_id]?.byId[e.d.id];
      useMessages.getState().remove(e.d.channel_id, e.d.id);
      // a thread's newest message gone: its card stops quoting it
      useThreadLast.getState().forget(e.d.channel_id, e.d.id);
      // the newest message gone, the channel's last is an older one: read up to that, it is read
      if (e.d.last_message_id !== undefined) useChannels.getState().resetLastMessage(e.d.channel_id, e.d.last_message_id);
      if (!uncounted) useReadStates.getState().dropUnread(e.d.channel_id, e.d.id);
      if (counted && !uncounted) useReadStates.getState().dropMention(e.d.channel_id, e.d.id);
      each(dispatchHooks.onMessageDelete, (h) => h(e.d.channel_id, [e.d.id], [was]));
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "delete", channelId: e.d.channel_id, ids: [e.d.id] }));
      return;
    }
    case "message_delete_bulk": {
      const told = new Map((e.d.mentions ?? []).map((m) => [m.id, m]));
      const uncounted = new Set(e.d.ids.filter((id) => quietMessage(e.d.channel_id, id, told.get(id)?.author_id)));
      const counted = e.d.ids.filter((id) => mentionedMe(e.d.channel_id, id, meId, told.get(id)) && !uncounted.has(id));
      const loaded = useMessages.getState().channels[e.d.channel_id]?.byId;
      const was = e.d.ids.map((id) => loaded?.[id]);
      useMessages.getState().removeMany(e.d.channel_id, e.d.ids);
      if (e.d.last_message_id !== undefined) useChannels.getState().resetLastMessage(e.d.channel_id, e.d.last_message_id);
      for (const id of e.d.ids) if (!uncounted.has(id)) useReadStates.getState().dropUnread(e.d.channel_id, id);
      for (const id of counted) useReadStates.getState().dropMention(e.d.channel_id, id);
      each(dispatchHooks.onMessageDelete, (h) => h(e.d.channel_id, e.d.ids, was));
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "delete", channelId: e.d.channel_id, ids: e.d.ids }));
      return;
    }
    case "reaction_remove_all":
    case "reaction_remove_emoji":
      useMessages.getState().clearReactions(e.d.channel_id, e.d.message_id, e.t === "reaction_remove_emoji" ? e.d.emoji : undefined);
      return;
    case "application_commands_update":
    case "integrations_update":
      // the commands kept for each channel are read again when next needed
      useAppCommands.getState().forget();
      // and a page listing a server's apps reads them again
      each(dispatchHooks.onUnhandled, (h) => h(e));
      return;
    case "reaction_add":
    case "reaction_remove":
      useMessages.getState().setReaction(e.d.channel_id, e.d.message_id, e.d.emoji, e.d.user_id, e.t === "reaction_add", e.d.user_id === meId);
      emitAppEvent({ type: "reaction", channelId: e.d.channel_id, messageId: e.d.message_id, userId: e.d.user_id, emoji: e.d.emoji, added: e.t === "reaction_add" });
      return;
    case "typing":
      if (e.d.user_id === meId) return;
      // someone this account blocked is never shown typing
      if (!e.d.stop && currentBlockRules().blocked.has(e.d.user_id)) return;
      if (e.d.stop) useTyping.getState().remove(e.d.channel_id, e.d.user_id);
      // timed from when it arrived: the server's clock and this device's rarely agree
      else useTyping.getState().add(e.d.channel_id, e.d.user_id);
      return;
    case "channel_create":
    case "channel_update":
      useChannels.getState().upsert(e.d);
      fetchMissingUsers(e.d.recipient_ids);
      emitAppEvent({ type: e.t === "channel_create" ? "channelCreated" : "channelUpdated", channel: useChannels.getState().byId[e.d.id] ?? e.d });
      return;
    case "channel_delete": {
      // a channel's threads go with it
      const gone = [e.d.id, ...Object.values(useChannels.getState().byId).filter((c) => isThread(c) && c.parent_id === e.d.id).map((c) => c.id)];
      const had = gone.map((id) => useChannels.getState().byId[id] ?? null);
      for (const id of gone) {
        useChannels.getState().remove(id);
        useMessages.getState().clearChannel(id);
        useThreads.getState().forget(id);
      }
      useGroupAddNotices.getState().remove(e.d.id);
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "channelsGone", channelIds: gone }));
      gone.forEach((id, i) => emitAppEvent({ type: "channelDeleted", id, serverId: had[i]?.server_id ?? (i === 0 ? (e.d.server_id ?? null) : null), channel: had[i] ?? null }));
      return;
    }
    case "thread_create":
    case "thread_update": {
      const { member_count, ...thread } = e.d;
      useChannels.getState().upsert(thread);
      // started from a message: the message says so under it at once
      const starter = thread.thread?.starter_id;
      if (e.t === "thread_create" && starter && thread.parent_id) {
        useMessages.getState().setThread(thread.parent_id, starter, { id: thread.id, name: thread.name, message_count: thread.thread?.message_count ?? 0, last_message_id: thread.last_message_id ?? null, archived: !!thread.thread?.archived, locked: !!thread.thread?.locked });
      }
      if (typeof member_count === "number") useThreads.getState().membersChanged(thread.id, [], [], member_count, meId);
      each(dispatchHooks.onUnhandled, (h) => h(e));
      emitAppEvent({ type: e.t === "thread_create" ? "channelCreated" : "channelUpdated", channel: useChannels.getState().byId[thread.id] ?? thread });
      return;
    }
    case "thread_delete": {
      const had = useChannels.getState().byId[e.d.id] ?? null;
      useChannels.getState().remove(e.d.id);
      useMessages.getState().clearChannel(e.d.id);
      useThreads.getState().forget(e.d.id);
      each(dispatchHooks.onUnhandled, (h) => h(e));
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "channelsGone", channelIds: [e.d.id] }));
      emitAppEvent({ type: "channelDeleted", id: e.d.id, serverId: e.d.server_id ?? had?.server_id ?? null, channel: had });
      return;
    }
    case "thread_members_update":
      useThreads.getState().membersChanged(e.d.thread_id, e.d.added, e.d.removed, e.d.member_count, meId);
      each(dispatchHooks.onUnhandled, (h) => h(e));
      return;
    case "automod_rules_update": {
      const st = useServers.getState().byId[e.d.server_id];
      if (st && e.d.automod_at !== undefined) useServers.getState().updateServer({ ...st.server, automod_at: e.d.automod_at });
      forgetAutomodRules(e.d.server_id);
      return;
    }
    case "automod_event":
    case "audit_log_entry":
    case "follows_update":
      if (e.t === "follows_update") {
        const channelId = e.d.channel_id;
        forgetFollows(channelId);
        // a follow's key sealed again (or a new follow) opens copies that could not be read
        void useMessages.getState().redecrypt((m) => m.channel_id === channelId && m.type === "crosspost" && !!m.decryptError);
      }
      each(dispatchHooks.onUnhandled, (h) => h(e));
      return;
    case "channel_positions":
      useChannels.getState().applyPositions(e.d.channels);
      return;
    case "server_create":
      if (e.d.users) useUsers.getState().upsert(e.d.users);
      ingestServerView(e.d);
      each(dispatchHooks.onUnhandled, (h) => h(e));
      return;
    case "server_update":
      useServers.getState().updateServer(e.d);
      return;
    case "server_delete": {
      const chans = useChannels.getState();
      const gone: string[] = [];
      for (const ch of Object.values(chans.byId)) {
        if (ch.server_id === e.d.id) {
          chans.remove(ch.id);
          useMessages.getState().clearChannel(ch.id);
          gone.push(ch.id);
        }
      }
      useServers.getState().remove(e.d.id);
      useExpressions.getState().removeServer(e.d.id);
      each(dispatchHooks.onUnhandled, (h) => h(e));
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "channelsGone", channelIds: gone }));
      return;
    }
    case "emoji_update":
      useExpressions.getState().setEmojis(e.d.server_id, e.d.emojis);
      return;
    case "sticker_update":
      if (e.d.deleted || !e.d.pack) useExpressions.getState().removePack(e.d.pack_id);
      else useExpressions.getState().upsertPack(e.d.pack);
      return;
    case "sticker_recent":
      useExpressions.getState().noteRecent(e.d.sticker);
      return;
    case "sticker_recent_clear":
      useExpressions.getState().setRecents([]);
      return;
    case "member_add":
      useUsers.getState().upsert(e.d.user);
      useServers.getState().upsertMember(e.d.member, true);
      emitAppEvent({ type: "memberJoined", serverId: e.d.server_id, member: memberNow(e.d.server_id, e.d.member) });
      return;
    case "member_update":
      useServers.getState().upsertMember(e.d);
      emitAppEvent({ type: "memberUpdated", serverId: e.d.server_id, member: memberNow(e.d.server_id, e.d) });
      return;
    case "member_remove": {
      const had = useServers.getState().byId[e.d.server_id]?.members[e.d.user_id] ?? null;
      useServers.getState().removeMember(e.d.server_id, e.d.user_id, true);
      emitAppEvent({ type: "memberLeft", serverId: e.d.server_id, userId: e.d.user_id, member: had });
      return;
    }
    case "role_create":
    case "role_update":
      useServers.getState().upsertRole(e.d);
      return;
    case "role_delete":
      useServers.getState().removeRole(e.d.server_id, e.d.id);
      return;
    case "presence":
      usePresence.getState().set(e.d);
      emitAppEvent({ type: "presenceUpdated", presence: e.d });
      return;
    case "user_update":
      useUsers.getState().upsert(e.d);
      if (e.d.id === meId) {
        const me = useAuth.getState().me;
        if (me) useAuth.getState().setMe({ ...me, ...e.d, flags: me.flags });
      }
      emitAppEvent({ type: "userUpdated", user: useUsers.getState().byId[e.d.id] ?? e.d });
      return;
    case "billing_update":
      // only a page that has read it reads it again
      if (useBilling.getState().billing) void useBilling.getState().load().catch(() => undefined);
      return;
    case "relationship_add": {
      const before = useRelationships.getState().byUser[e.d.user.id]?.type;
      useRelationships.getState().set(e.d);
      useUsers.getState().upsert(e.d.user);
      if (e.d.type === "incoming" && before !== "incoming") each(dispatchHooks.onFriendRequest, (h) => h(e.d));
      return;
    }
    case "relationship_remove":
      useRelationships.getState().remove(e.d.user_id);
      return;
    case "read_state":
      useReadStates.getState().set(e.d);
      return;
    case "group_add_notice":
      useGroupAddNotices.getState().add(e.d);
      return;
    case "discord_link":
      useDiscord.getState().set(e.d);
      return;
    case "note_update":
      useNotes.getState().updated(e.d);
      return;
    case "note_delete":
      useNotes.getState().drop(e.d.id);
      return;
    case "note_invite": {
      const known = useNotes.getState().invites.some((i) => i.id === e.d.id);
      useNotes.getState().invited(e.d);
      if (!known) each(dispatchHooks.onNoteInvite, (h) => h(e.d));
      return;
    }
    case "note_invite_delete":
      useNotes.getState().dropInvite(e.d.id);
      return;
    case "note_presence":
      useNotes.getState().present(e.d);
      return;
    case "note_cursor":
      useNotes.getState().cursor(e.d);
      return;
    case "note_crdt":
      useNotes.getState().crdt(e.d);
      return;
    case "group_add_notice_delete":
      useGroupAddNotices.getState().remove(e.d.channel_id);
      return;
    case "key_grant":
      await useKeys.getState().ingestGrants([e.d]);
      return;
    // Key upkeep can mean hundreds of requests. It runs beside the event queue
    // rather than in it, so messages, typing and calls do not wait behind it.
    //
    // `user_ids` is the server's answer to a question only the server was ever
    // asked, and one fabricated event naming an attacker's account used to be
    // enough to make this device seal the conversation root for it. The list is
    // held against the membership this client knows in `putGrantsFor`, which
    // every grant — this event, /keys/needs and a new epoch — goes through.
    case "keys_needed":
      if (ring.get(e.d.conv_id, e.d.epoch)) void useKeys.getState().grantTo(e.d.conv_id, e.d.epoch, e.d.user_ids).catch(() => undefined);
      return;
    case "key_rotation_needed": {
      const conv = e.d.conv_id;
      if (!ring.latest(conv)) return;
      // Each change still gets its own rotation, one after another: a key made
      // before a second removal may already have gone to the person removed.
      const job = (rotations.get(conv) ?? Promise.resolve())
        .then(() => useKeys.getState().rotate(conv, "membership change"))
        .catch(() => undefined)
        .finally(() => {
          if (rotations.get(conv) === job) rotations.delete(conv);
        });
      rotations.set(conv, job);
      return;
    }
    case "pin_update": {
      const ch = useMessages.getState().channels[e.d.channel_id];
      const m = ch?.byId[e.d.message_id];
      if (m) useMessages.getState().setFlags(e.d.channel_id, e.d.message_id, e.d.pinned ? m.flags | 1 : m.flags & ~1);
      each(dispatchHooks.onMessageChange, (h) => h({ kind: "pin", channelId: e.d.channel_id, id: e.d.message_id, pinned: e.d.pinned }));
      return;
    }
    case "settings_update":
      // our own saves come back here too, and there is nothing new to fetch in those
      if (e.d.version > useSettings.getState().version) void useSettings.getState().reload().catch(() => undefined);
      return;
    case "session_expired":
      void useAuth.getState().logout();
      return;
    case "heartbeat_ack":
    case "guest_ended":
      return;
    case "nexa_notice":
      useNexa.getState().onNotice(e.d.case, e.d.people, e.d.state);
      applyNexaState(e.d.state);
      each(dispatchHooks.onNexa, (h) => h({ kind: "notice", case: e.d.case }));
      return;
    case "nexa_state":
      applyNexaState(e.d);
      return;
    case "nexa_case":
      useNexa.getState().onCase(e.d.case, e.d.messages, e.d.people, e.d.state);
      applyNexaState(e.d.state);
      if (e.d.messages.some((m) => m.kind === "staff")) each(dispatchHooks.onNexa, (h) => h({ kind: "reply", case: e.d.case }));
      return;
    case "nexa_announcement":
      useNexa.getState().onAnnouncement(e.d.announcement, e.d.people);
      emitModEvent({ type: "announcement", announcement: e.d.announcement, people: e.d.people });
      each(dispatchHooks.onNexa, (h) => h({ kind: "announcement", announcement: e.d.announcement }));
      return;
    case "nexa_announcement_delete":
      useNexa.getState().onAnnouncementGone(e.d.id);
      emitModEvent({ type: "announcement_gone", id: e.d.id });
      return;
    case "mod_case":
      useModeration.getState().setCounts(e.d.counts);
      emitModEvent({ type: "case", case: e.d.case, messages: e.d.messages, people: e.d.people });
      // a member writing is what the staff are waiting for
      if (e.d.messages.some((m) => m.kind === "member")) each(dispatchHooks.onNexa, (h) => h({ kind: "appeal", caseId: e.d.case.id, userId: e.d.case.user_id }));
      return;
    case "mod_log":
      emitModEvent({ type: "log", entry: e.d.entry, people: e.d.people });
      return;
    case "nexa_bug": {
      useNexa.getState().onBug(e.d.report, e.d.messages, e.d.people, e.d.state);
      applyNexaState(e.d.state);
      // the team said something: a reply, or a new status
      const said = e.d.messages.find((m) => m.kind === "staff") ?? e.d.messages.find((m) => m.kind === "system");
      if (said) for (const h of bugAlertHooks) h({ kind: said.kind === "staff" ? "reply" : "status", report: e.d.report });
      return;
    }
    case "mod_bug":
      useModeration.getState().setBugCounts(e.d.counts);
      emitModEvent({ type: "bug", report: e.d.report, messages: e.d.messages, people: e.d.people });
      // a new report, or the reporter writing, is what the staff are waiting for
      if (!e.d.report.last_message) for (const h of bugAlertHooks) h({ kind: "staff", report: e.d.report, isNew: true });
      else if (e.d.messages.some((m) => m.kind === "member")) for (const h of bugAlertHooks) h({ kind: "staff", report: e.d.report, isNew: false });
      return;
    case "mod_unread":
      useModeration.getState().setUnread(e.d);
      return;
    case "mod_games":
      useModeration.getState().setGamesWaiting(e.d.waiting);
      emitModEvent({ type: "games" });
      return;
    case "mod_report":
      useModeration.getState().setReportCounts(e.d.counts);
      emitModEvent({ type: "report", reports: e.d.reports, people: e.d.people });
      // a report just sent is news to the staff, as a member writing in a case is
      if (e.d.new && e.d.reports[0]) each(dispatchHooks.onNexa, (h) => h({ kind: "newReport", report: e.d.reports[0]!, people: e.d.people }));
      return;
    case "report_update": {
      const before = useMyReports.getState().list?.find((x) => x.id === e.d.id);
      useMyReports.getState().onUpdate(e.d);
      useNexa.getState().onReport(e.d);
      // staff closed it: the outcome is news, told like anything else from Nexa
      if (e.d.outcome_at && before?.outcome_at !== e.d.outcome_at) each(dispatchHooks.onNexa, (h) => h({ kind: "report", report: e.d }));
      return;
    }
    case "account_banned":
      // the socket was shut on a ban; what the ban screen needs comes over REST
      void nexaApi
        .state()
        .then((r) => {
          if (!gone()) applyNexaState(r.state);
        })
        .catch(() => undefined);
      return;
    case "voice_state":
    case "voice_signal":
    case "voice_error":
    case "voice_resumed":
    case "call_ring":
    case "call_end": {
      const v = await voice();
      if (!gone()) v.handleVoiceEvent(e);
      return;
    }
    case "ban_add":
    case "ban_remove":
    case "invite_create":
    case "invite_delete":
    case "webhooks_update":
      each(dispatchHooks.onUnhandled, (h) => h(e));
      return;
    case "poll_vote_add":
    case "poll_vote_remove":
      useMessages.getState().setPollVote(e.d.channel_id, e.d.message_id, e.d.answer_id, e.t === "poll_vote_add", e.d.user_id === meId, e.d.total_voters);
      return;
    default: {
      const never: never = e;
      void never;
    }
  }
}

/**
 * An event that arrived while the app was locked with a call going on. The
 * keys are gone, so nothing that needs them is applied — the ready after
 * unlocking brings all of that back — but the call goes on: its own events are
 * handled as usual, and a reconnect meanwhile still resumes it. A call ringing
 * in is left: nobody can answer it behind the passcode.
 */
export async function applyLockedEvent(e: GatewayEvent): Promise<void> {
  const gen = sessionGen;
  switch (e.t) {
    case "ready": {
      setServerNow(e.d.now);
      const v = await voice();
      if (gen === sessionGen) v.onReadyVoice(e.d.voice_states ?? [], e.d.session_id);
      return;
    }
    case "voice_state":
    case "voice_signal":
    case "voice_error":
    case "voice_resumed":
    case "call_end": {
      const v = await voice();
      if (gen === sessionGen) v.handleVoiceEvent(e);
      return;
    }
    case "session_expired":
    case "account_banned":
      return applyEvent(e);
    default:
      return;
  }
}
