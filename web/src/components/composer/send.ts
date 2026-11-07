import { ApiError } from "@/api/client";
import { stage as perfStage } from "@/lib/perfReports";
import { messages as messagesApi } from "@/api/messages";
import type { SendMessageRequest } from "@/api/types";
import { brokenLimit, encryptMessage, type AttachmentMeta, type MessagePayload, type PollPayload } from "@/crypto/message";
import { addressedBots, sealCopies } from "@/lib/botCopies";
import { goesInOneRequest, sendInWorker, uploadStaged, type Staged } from "@/lib/attachments";
import { encryptedSize } from "@/crypto/fileChunks";
import { holdReload } from "@/lib/busy";
import { decryptForStore, type DecryptedMessage } from "@/lib/decrypt";
import { nonce as newNonce, tmpId } from "@/lib/ids";
import { channelKeyFor } from "@/lib/keyring";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useKeys } from "@/stores/keys";
import { heldMessage, useMessages, type PendingMessage, type PendingUpload } from "@/stores/messages";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import { useSlowmode } from "@/stores/slowmode";
import { canI, P, useServers } from "@/stores/servers";
import { emojiToken, packEmoji, useExpressions } from "@/stores/expressions";
import { emojiByShortcode, emojiLoaded } from "@/components/pickers/emojiIndex";
import { extractUrls } from "@/markdown";
import { pollRequest } from "@/lib/poll";
import { automodReady, screenOutgoing, type Stopped } from "@/lib/automod/rules";
import { useAutomodNotices } from "@/stores/automodNotices";
import { tooLong, tooLongMessage } from "./limit";
import { extractMentions, type HumanNames, type MentionScope } from "./mentions";

import { t } from "@/i18n";
export interface SendOptions {
  replyTo?: string;
  ping?: boolean;
  attachments?: { meta: AttachmentMeta; blob_id: string; size: number }[];
  /** Link previews, or a promise of them: the message shows at once and waits for them before it goes. */
  embeds?: unknown[] | Promise<unknown[]>;
  sticker?: MessagePayload["sticker"];
  flags?: number;
  /** A failed message this send replaces, dropped only once its own copy is in the store. */
  replaces?: string;
  /** A poll: sealed in the message, with only its shape told to the server (lib/poll.ts). */
  poll?: PollPayload;
  /** AutoMod has read it already (screenForSend): what to tell it once the message is sent. */
  screened?: (messageId: string) => void;
  /** Told the message as the server took it, once it is sent (the plugin API's messages.send). */
  onSent?: (message: DecryptedMessage) => void;
  /** Passes on another message (lib/forward.ts): its files go as they are, and nobody is pinged. */
  forward?: { channelId: string; messageId: string; at: number; comment?: string };
}

/** The conversation whose keys encrypt a channel: the server tells us (itself for DMs and private channels, the server root otherwise). */
export function convForChannel(channelId: string): string {
  const ch = useChannels.getState().byId[channelId];
  if (ch?.key_conv_id && ch.key_conv_id !== "0") return ch.key_conv_id;
  if (ch?.server_id && ch.type !== "dm" && ch.type !== "group_dm") return ch.server_id;
  return channelId;
}

/**
 * The sends still going out in each channel. A message waits for the ones
 * before it, so they arrive in the order they were written even when an
 * earlier one is still waiting on its link preview. A message with files
 * joins once its files are up: nothing waits for an upload.
 */
const queues = new Map<string, Promise<void>>();

/**
 * Which signed-in account queued sends belong to. A message still waiting in
 * a queue at sign-out must not go out afterwards — the requests would carry
 * whoever signed in next, and post the last person's words as theirs.
 */
let account = 0;

function enqueue(channelId: string, job: () => Promise<void>): Promise<void> {
  // a new version of the app waits until the queue is through
  const release = holdReload("send");
  const mine = account;
  const run = (queues.get(channelId) ?? Promise.resolve()).then(() => (mine === account ? job() : undefined));
  const tail = run.catch(() => undefined);
  queues.set(channelId, tail);
  void tail.then(() => {
    release();
    if (queues.get(channelId) === tail) queues.delete(channelId);
  });
  return run;
}

/** A message as written: its text with the mentions in it, and what goes with it. */
interface Written {
  payload: MessagePayload;
  mentions: string[];
  roles: string[];
  everyone: boolean;
  /** What was typed, to put back in the box should AutoMod stop it. */
  raw: string;
}

/** The conversation a channel belongs to: a subchat's people are those of the chat it is in. */
function conversation(channelId: string) {
  const byId = useChannels.getState().byId;
  const ch = byId[channelId];
  return ch?.type === "subchat" && ch.parent_id ? (byId[ch.parent_id] ?? ch) : ch;
}

/**
 * Who and what a message in this channel can name: its people, the roles one
 * may mention there (the mentionable ones, or all of them with Mention
 * @everyone), the server's text, announcement and forum channels one can see, and the custom emoji,
 * this server's own first.
 */
export function mentionScope(channelId: string): MentionScope {
  const ch = conversation(channelId);
  const users = useUsers.getState().byId;
  const me = useAuth.getState().me;
  const nameOf = (id: string) => (id === me?.id ? me.display_name : users[id]?.display_name) ?? "";
  const serverId = ch?.server_id ?? null;
  const st = serverId ? useServers.getState().byId[serverId] : undefined;
  const scope: MentionScope = {};
  if (st) {
    scope.members = Object.values(st.members).map((m) => ({ id: m.user_id, names: [nameOf(m.user_id), m.nickname] }));
    const everyRole = canI(st, P.MENTION_EVERYONE, channelId);
    scope.roles = Object.values(st.roles)
      .filter((r) => r.id !== st.server.id && (r.mentionable || everyRole))
      .map((r) => ({ id: r.id, name: r.name }));
    scope.channels = Object.values(useChannels.getState().byId)
      .filter((c) => c.server_id === serverId && ["text", "announcement", "forum", "media"].includes(c.type) && canI(st, P.VIEW_CHANNEL, c.id))
      .map((c) => ({ id: c.id, name: c.name }));
  } else if (ch) {
    const ids = new Set([...ch.recipient_ids, ...(me ? [me.id] : [])]);
    scope.members = [...ids].map((id) => ({ id, names: [nameOf(id)] }));
  }
  const ex = useExpressions.getState();
  const emoji: NonNullable<MentionScope["emoji"]> = [];
  if (serverId) for (const e of ex.emojis[serverId] ?? []) emoji.push({ name: e.name, token: emojiToken(e) });
  // another server's emoji (and packs') need Use External Emoji in a server channel
  const external = !st || canI(st, P.USE_EXTERNAL_EMOJIS, channelId);
  if (external) {
    for (const [sid, list] of Object.entries(ex.emojis)) if (sid !== serverId) for (const e of list) emoji.push({ name: e.name, token: emojiToken(e), fallback: true });
    for (const { items } of packEmoji(ex.packs)) for (const e of items) emoji.push({ name: e.name, token: emojiToken(e), fallback: true });
  }
  scope.emoji = emoji;
  // until the emoji set is here nobody can say a name is free, so nothing borrowed stands in for it
  scope.isShortcode = (name) => !emojiLoaded() || !!emojiByShortcode(name);
  return scope;
}

/** The names mentions in an edit go back to: only those that turn into the same mention again when it is saved. */
export function editNames(channelId: string): HumanNames {
  const scope = mentionScope(channelId);
  const once = (list: { id: string; name: string }[] | undefined) => {
    const count = new Map<string, number>();
    for (const x of list ?? []) count.set(x.name.toLowerCase(), (count.get(x.name.toLowerCase()) ?? 0) + 1);
    return Object.fromEntries((list ?? []).filter((x) => count.get(x.name.toLowerCase()) === 1).map((x) => [x.id, x.name]));
  };
  // a custom emoji reads as its :name: when saving that name brings back the very same emoji
  return { roles: once(scope.roles), channels: once(scope.channels), emoji: (token, name) => extractMentions(`:${name}:`, {}, scope).text === token };
}

function written(channelId: string, rawText: string, opts: SendOptions): Written {
  const { text, mentions, roles, everyone } = extractMentions(rawText, useUsers.getState().byId, mentionScope(channelId));
  const payload: MessagePayload = {};
  if (text) payload.t = text;
  if (opts.attachments?.length) payload.att = opts.attachments.map((a) => a.meta);
  if (Array.isArray(opts.embeds) && opts.embeds.length) payload.embeds = opts.embeds;
  if (opts.sticker) payload.sticker = opts.sticker;
  if (opts.poll) payload.poll = opts.poll;
  // someone else's words, passed on: they ping nobody
  if (opts.forward) {
    payload.fwd = { at: opts.forward.at, ...(opts.forward.comment ? { comment: opts.forward.comment } : {}) };
    return { payload, mentions: [], roles: [], everyone: false, raw: rawText };
  }
  return { payload, mentions, roles, everyone, raw: rawText };
}

/** A message AutoMod stopped before it went. */
export class AutomodStop extends Error {
  constructor(readonly stopped: Stopped) {
    super("automod_blocked");
    this.name = "AutomodStop";
  }
}

/** Puts up what AutoMod said about a message in its conversation's box, with what was typed to go back in it. */
function noticeStopped(channelId: string, err: AutomodStop | ApiError, raw: string | undefined): void {
  if (err instanceof AutomodStop) {
    const c = err.stopped.first;
    useAutomodNotices.getState().show(channelId, { message: c.message, rule: c.rule.name, kind: c.rule.kind, matched: c.matched, ...(raw ? { text: raw } : {}), at: Date.now() });
    return;
  }
  const d = err.details ?? {};
  const custom = d["custom"] === true;
  useAutomodNotices.getState().show(channelId, { message: custom ? err.message : "", rule: typeof d["rule"] === "string" ? d["rule"] : "", kind: typeof d["kind"] === "string" ? d["kind"] : "", ...(raw ? { text: raw } : {}), at: Date.now() });
}

/**
 * What AutoMod makes of a message before it goes, when the server's rules are
 * here to say: "stopped" (and the box shows why), or what to tell AutoMod
 * once it is sent. Null when the rules are still on their way: the send
 * itself waits for them.
 */
export function screenForSend(channelId: string, rawText: string, files: readonly string[], opts: SendOptions = {}): { stopped: true } | { stopped: false; afterSend: (messageId: string) => void } | null {
  const ch = useChannels.getState().byId[channelId];
  const me = useAuth.getState().me;
  if (!ch?.server_id || !me) return { stopped: false, afterSend: () => undefined };
  const st = useServers.getState().byId[ch.server_id];
  if (!st) return null;
  const w = written(channelId, rawText, opts);
  // the names of files about to go up count as they will once they are attached
  const payload: MessagePayload = files.length ? { ...w.payload, att: files.map((name) => ({ name }) as AttachmentMeta) } : w.payload;
  const verdict = screenOutgoing(ch, me.id, payload, { onlyIfLoaded: true });
  if (verdict === null) return null;
  if (verdict.stopped) {
    noticeStopped(channelId, new AutomodStop(verdict.stopped), undefined);
    return { stopped: true };
  }
  useAutomodNotices.getState().dismiss(channelId);
  return { stopped: false, afterSend: verdict.afterSend };
}

/** What a pending copy keeps of the options, so a retry sends what was chosen. */
function chosen(opts: SendOptions): Pick<PendingMessage, "replyToId" | "ping" | "flags"> {
  return { ...(opts.replyTo ? { replyToId: opts.replyTo } : {}), ...(opts.ping !== undefined ? { ping: opts.ping } : {}), ...(opts.flags ? { flags: opts.flags } : {}) };
}

function pendingOf(channelId: string, tmp: string): PendingMessage | undefined {
  return useMessages.getState().channels[channelId]?.pending.find((p) => p.tmpId === tmp);
}

/** Encrypts and sends a message, with an optimistic pending copy in the store. */
export function sendMessage(channelId: string, rawText: string, opts: SendOptions = {}): Promise<void> {
  const me = useAuth.getState().me;
  if (!me) return Promise.resolve();
  const w = written(channelId, rawText, opts);
  if (!w.payload.t && !w.payload.att && !w.payload.sticker && !w.payload.poll) return Promise.resolve();
  // nothing goes out that the apps would refuse to show (crypto/message.ts MESSAGE_LIMITS), whoever asks to send it
  if (brokenLimit(w.payload as Record<string, unknown>)) return Promise.reject(new Error("too_long"));
  const nonce = newNonce();
  const tmp = tmpId();
  const messages = useMessages.getState();
  messages.addPending({ tmpId: tmp, nonce, channelId, authorId: me.id, payload: w.payload, createdAt: Date.now(), status: "sending", ...chosen(opts) });
  // Only now, past the two returns above that leave nothing behind: the failed
  // copy is the only place a retried message still exists.
  if (opts.replaces) messages.removePending(channelId, opts.replaces);
  // pressing send until the server has it, earlier messages queued ahead included
  const began = performance.now();
  const timed: SendOptions = {
    ...opts,
    onSent: (d) => {
      perfStage("send", performance.now() - began);
      opts.onSent?.(d);
    },
  };
  return enqueue(channelId, () => deliver(channelId, me.id, w, tmp, nonce, timed));
}

/**
 * Sends a poll, shown at once like any message and settled or failed the same
 * way. Its question and answers go sealed in the message; the server hears
 * only how many answers it has, how long it runs and whether several may be
 * picked.
 */
export function sendPoll(channelId: string, poll: PollPayload): Promise<void> {
  return sendMessage(channelId, "", { poll });
}

/**
 * Messages whose files are going up: the files, kept for a retry and for
 * their previews, and the way to stop them.
 */
const uploading = new Map<string, { files: Staged[]; stop: AbortController | null; sent: Map<string, Awaited<ReturnType<typeof uploadStaged>>> }>();

/** Lets go of a message's files once nothing shows them any more. */
function forget(tmp: string): void {
  const job = uploading.get(tmp);
  if (!job) return;
  uploading.delete(tmp);
  for (const f of job.files) if (f.preview) URL.revokeObjectURL(f.preview);
}

/**
 * Sends a message with files. It shows in the list at once, dimmed, with its
 * upload's progress; the files go up in the background, whatever happens
 * meanwhile — other messages sent, other conversations opened — and the
 * message follows once they are through. The message now owns the files and
 * their previews.
 */
export function sendWithFiles(channelId: string, rawText: string, files: Staged[], opts: SendOptions = {}): Promise<void> {
  const me = useAuth.getState().me;
  if (!me) return Promise.resolve();
  const w = written(channelId, rawText, opts);
  const nonce = newNonce();
  const tmp = tmpId();
  const upload: PendingUpload = {
    files: files.map((f) => ({ id: f.id, name: f.name, size: f.file.size, type: f.file.type, preview: f.preview })),
    loaded: 0,
    total: files.reduce((n, f) => n + f.file.size, 0),
    speed: 0,
    done: false,
    error: null,
  };
  useMessages.getState().addPending({ tmpId: tmp, nonce, channelId, authorId: me.id, payload: w.payload, createdAt: Date.now(), status: "sending", ...chosen(opts), upload });
  uploading.set(tmp, { files, stop: null, sent: new Map() });
  return uploadThenSend(channelId, me.id, tmp, nonce, w, opts);
}

/** How fast the bytes are going, smoothed so that the number can be read. */
function speedometer(): (bytes: number, now: number) => number {
  let at = 0;
  let last = 0;
  let speed = 0;
  return (bytes, now) => {
    if (!at) {
      at = now;
      last = bytes;
      return speed;
    }
    const seconds = (now - at) / 1000;
    if (seconds < 0.4) return speed;
    const instant = Math.max(0, (bytes - last) / seconds);
    speed = speed ? speed * 0.6 + instant * 0.4 : instant;
    at = now;
    last = bytes;
    return speed;
  };
}

/**
 * How many of a message's files go up at once. One at a time, every file
 * waited out the round trip and the server's write of the one before it,
 * with the connection idle meanwhile: ten screenshots took several times
 * as long as their bytes did.
 */
const FILES_AT_ONCE = 3;

/**
 * Files bigger than this go up on their own, after the rest. Each one going
 * up holds its encrypted request in memory, and three big ones at once would
 * be more than a phone can spare.
 */
const TOGETHER_MAX = 16 * 1024 * 1024;

/**
 * Runs `upload` over the files that still have to go up, a few at once. After
 * one fails no more are started; the ones already going are let finish, so a
 * retry has less to send, and then the first failure is thrown.
 */
async function uploadAll(files: Staged[], upload: (f: Staged) => Promise<void>): Promise<void> {
  const together = files.filter((f) => f.file.size <= TOGETHER_MAX && goesInOneRequest(f.file.size));
  const alone = files.filter((f) => !together.includes(f));
  let failed: { err: unknown } | null = null;
  const lanes = async (list: Staged[], width: number) => {
    let next = 0;
    const lane = async () => {
      while (!failed && next < list.length) {
        const f = list[next++]!;
        try {
          await upload(f);
        } catch (err) {
          failed ??= { err };
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(width, list.length) }, lane));
  };
  await lanes(together, FILES_AT_ONCE);
  await lanes(alone, 1);
  if (failed) throw (failed as { err: unknown }).err;
}

async function uploadThenSend(channelId: string, meId: string, tmp: string, nonce: string, w: Written, opts: SendOptions): Promise<void> {
  const job = uploading.get(tmp);
  if (!job) return;
  const stop = new AbortController();
  job.stop = stop;
  const store = () => useMessages.getState();
  const total = job.files.reduce((n, f) => n + f.file.size, 0);
  const speed = speedometer();
  let shownAt = 0;
  // at most a few times a second: progress comes far more often than anyone reads it
  const show = (loaded: number) => {
    const now = performance.now();
    const bytesPerSecond = speed(loaded, now);
    if (now - shownAt < 200) return;
    shownAt = now;
    const cur = pendingOf(channelId, tmp);
    if (cur?.upload) store().updatePending(channelId, tmp, { upload: { ...cur.upload, loaded: Math.min(loaded, total), speed: bytesPerSecond } });
  };
  // how far each file has got; one that went up before a failure counts in full
  const loaded = new Map<string, number>();
  const progress = () => {
    let n = 0;
    for (const f of job.files) n += job.sent.has(f.id) ? f.file.size : (loaded.get(f.id) ?? 0);
    show(n);
  };
  const release = holdReload("upload");
  let ups: Awaited<ReturnType<typeof uploadStaged>>[];
  try {
    // a file that went up before another failed is not sent again on the retry
    const todo = job.files.filter((f) => !job.sent.has(f.id));
    await uploadAll(todo, async (f) => {
      const up = await uploadStaged(
        channelId,
        f,
        (p) => {
          loaded.set(f.id, p * f.file.size);
          progress();
        },
        sendInWorker,
        stop.signal,
      );
      job.sent.set(f.id, up);
    });
    // in the order they were picked, whichever finished first
    ups = job.files.map((f) => job.sent.get(f.id)!);
  } catch (err) {
    // stopped on purpose: the message went with it
    if (stop.signal.aborted) return;
    const msg = err instanceof Error ? err.message : t("composer.composer.uploadFailed");
    const cur = pendingOf(channelId, tmp);
    if (cur?.upload) store().updatePending(channelId, tmp, { status: "failed", upload: { ...cur.upload, speed: 0, error: msg } });
    useUI.getState().toast(msg, "error");
    return;
  } finally {
    job.stop = null;
    release();
  }
  const cur = pendingOf(channelId, tmp);
  // taken out of the list while its files went up
  if (!cur?.upload) return forget(tmp);
  const payload: MessagePayload = { ...w.payload, att: ups.map((u) => u.meta) };
  // the card stays, full, until the message itself is in
  store().updatePending(channelId, tmp, { payload, upload: { ...cur.upload, loaded: total, speed: 0, done: true } });
  await enqueue(channelId, () => deliver(channelId, meId, { ...w, payload }, tmp, nonce, { ...opts, attachments: ups }));
  forget(tmp);
}

/** Sends one message whose pending copy is already showing, and settles or fails that copy. */
async function deliver(channelId: string, meId: string, w: Written, tmp: string, nonce: string, opts: SendOptions): Promise<void> {
  const { mentions, roles, everyone } = w;
  let payload = w.payload;
  try {
    if (opts.embeds && !Array.isArray(opts.embeds)) {
      // a preview that never comes is no reason not to send
      const got = await opts.embeds.catch(() => []);
      if (got.length) payload = { ...w.payload, embeds: got };
    }
    // AutoMod reads it here when its rules were not in yet as it was sent
    let afterSend = opts.screened;
    const ch = useChannels.getState().byId[channelId];
    if (!afterSend && ch?.server_id) {
      await automodReady(ch.server_id);
      const verdict = screenOutgoing(ch, meId, payload);
      if (verdict?.stopped) throw new AutomodStop(verdict.stopped);
      afterSend = verdict?.afterSend;
    }
    const conv = convForChannel(channelId);
    const key = await useKeys.getState().ensureConvKey(conv, true);
    if (!key) throw new Error("waiting_for_key");
    const chanKey = channelKeyFor(conv, key.epoch, channelId);
    if (!chanKey) throw new Error("waiting_for_key");
    const ciphertext = await encryptMessage(chanKey, { conv, epoch: key.epoch, channelId, authorId: meId }, payload);
    const body: SendMessageRequest = { ciphertext, key_conv_id: conv, key_epoch: key.epoch, mentions: opts.ping === false ? mentions.filter((id) => !opts.replyTo || id !== replyAuthor(channelId, opts.replyTo)) : mentions, nonce };
    if (opts.replyTo) {
      body.reply_to_id = opts.replyTo;
      const author = replyAuthor(channelId, opts.replyTo);
      if (opts.ping !== false && author && author !== meId && !body.mentions.includes(author)) body.mentions = [...body.mentions, author];
    }
    if (everyone) body.mention_everyone = true;
    if (roles.length) body.mention_roles = roles;
    // a picture's small copy goes with it, marked as one so it does not count as a file of its own
    if (opts.attachments?.length) body.attachments = opts.attachments.flatMap((a) => [{ blob_id: a.blob_id, size: a.size }, ...(a.meta.preview ? [{ blob_id: a.meta.preview.blob_id, size: encryptedSize(a.meta.preview.size), preview: true }] : [])]);
    if (opts.flags) body.flags = opts.flags;
    if (opts.forward) body.forward = { channel_id: opts.forward.channelId, message_id: opts.forward.messageId };
    if (payload.poll) body.poll = pollRequest(payload.poll);
    // the apps it is addressed to read their own copy, sealed to their bots
    const bots = addressedBots(channelId, body.mentions, body.reply_to_id ? replyAuthor(channelId, body.reply_to_id) : undefined, meId);
    if (bots.length) body.bot_copies = await sealCopies(bots, payload);
    const sent = await messagesApi.send(channelId, body);
    afterSend?.(sent.id);
    const decrypted = await decryptForStore(sent);
    if (!decrypted.payload) decrypted.payload = payload;
    useMessages.getState().settle(channelId, [decrypted], { tmpId: tmp, nonce });
    useChannels.getState().setLastMessage(channelId, sent.id);
    useSlowmode.getState().markSent(channelId);
    opts.onSent?.(decrypted);
  } catch (err) {
    // stopped by AutoMod: it never went, so nothing waits in the list to be sent again
    if (err instanceof AutomodStop || (err instanceof ApiError && err.code === "automod_blocked")) {
      useMessages.getState().removePending(channelId, tmp);
      noticeStopped(channelId, err, w.raw);
      return;
    }
    useMessages.getState().failPending(channelId, tmp);
    const ui = useUI.getState();
    if (err instanceof ApiError && err.code === "verification_wait") {
      const secs = Number(err.retryAfter ?? 0) || 60;
      ui.toast(t("composer.send.verificationWait", { time: formatWait(secs) }), "error");
      throw err;
    }
    if (err instanceof ApiError && err.code === "slowmode") {
      const secs = Number(err.retryAfter ?? 0) || 5;
      useSlowmode.getState().setUntil(channelId, Date.now() + secs * 1000);
      ui.toast(t("composer.send.slowModeTryAgain", { seconds: secs }), "error");
    } else ui.toast(sendErrorText(err) ?? (err instanceof ApiError ? err.message : t("composer.send.yourMessageCouldNotBeSent")), "error");
    throw err;
  }
}

/**
 * The sentence for a send or an edit that failed, where there is a better one
 * than the error's own: sending and editing throw bare codes.
 */
export function sendErrorText(err: unknown): string | null {
  if (err instanceof AutomodStop) return err.stopped.first.message || t("automod.notice.stoppedEdit");
  if (err instanceof ApiError) {
    if (err.code === "automod_blocked") return err.details?.["custom"] === true ? err.message : t("automod.notice.stoppedEdit");
    if (err.code === "rate_limited") return t("composer.send.youAreSendingMessagesTooQuickly");
    // the server's own ceiling, which only a message far past the limit reaches
    if (err.code === "message_too_long" || err.code === "too_large") return tooLongMessage();
    // Create Polls taken away while the dialog was open
    if (err.code === "cannot_create_polls") return t("poll.create.notAllowed");
    return null;
  }
  if (!(err instanceof Error)) return null;
  if (err.message === "waiting_for_key") return t("composer.send.waitingForTheEncryptionKeyFor");
  if (err.message === "too_long") return tooLongMessage();
  return null;
}

/** A wait, in the largest unit that fits. */
export function formatWait(seconds: number): string {
  if (seconds < 90) return t("composer.send.waitSeconds", { count: Math.max(1, Math.ceil(seconds)) });
  if (seconds < 90 * 60) return t("composer.send.waitMinutes", { count: Math.ceil(seconds / 60) });
  if (seconds < 36 * 3600) return t("composer.send.waitHours", { count: Math.ceil(seconds / 3600) });
  return t("composer.send.waitDays", { count: Math.ceil(seconds / 86400) });
}

function replyAuthor(channelId: string, messageId: string): string | undefined {
  const m = heldMessage(channelId, messageId);
  // a webhook's message has nobody behind it to ping
  return m?.webhook_id ? undefined : m?.author_id;
}

/** Re-encrypts and saves an edit. */
export async function editMessage(channelId: string, messageId: string, rawText: string): Promise<void> {
  const me = useAuth.getState().me;
  const existing = useMessages.getState().channels[channelId]?.byId[messageId];
  // a message this device cannot read cannot be edited from it: the edit
  // would replace everything with the new text, files and all
  if (!me || !existing || !existing.payload) return;
  // the same limit as sending, whoever calls this
  if (tooLong(rawText)) throw new Error("too_long");
  const { text, mentions, roles, everyone } = extractMentions(rawText, useUsers.getState().byId, mentionScope(channelId));
  // A reply that pinged its author keeps pinging them: the mention came from
  // the reply, not from the text, and the server replaces the list on edit.
  const replied = existing.reply_to_id ? replyAuthor(channelId, existing.reply_to_id) : undefined;
  if (replied && existing.mentions?.includes(replied) && !mentions.includes(replied)) mentions.push(replied);
  const payload: MessagePayload = { ...(existing.payload ?? {}) };
  if (text) payload.t = text;
  else delete payload.t;
  // A preview belongs to a link in the text. Previews of redirected links carry
  // the final address, so they are only dropped when the links themselves change.
  const before = extractUrls(existing.payload?.t ?? "");
  const after = extractUrls(rawText);
  if (payload.embeds?.length && (before.length !== after.length || before.some((u, i) => u !== after[i]))) {
    const keep = new Set(after);
    const kept = payload.embeds.filter((e) => !!e && typeof e === "object" && keep.has((e as { url?: unknown }).url as string));
    if (kept.length) payload.embeds = kept;
    else delete payload.embeds;
  }
  if (!payload.t && !payload.att?.length && !payload.sticker) return;
  // an edit is read by AutoMod as a new message is
  const ch = useChannels.getState().byId[channelId];
  let afterEdit: ((id: string) => void) | undefined;
  if (ch?.server_id) {
    await automodReady(ch.server_id);
    const verdict = screenOutgoing(ch, me.id, payload);
    if (verdict?.stopped) throw new AutomodStop(verdict.stopped);
    afterEdit = verdict?.afterSend;
  }
  const conv = convForChannel(channelId);
  const latest = await useKeys.getState().ensureConvKey(conv, true);
  if (!latest) throw new Error("waiting_for_key");
  const useEpoch = latest.epoch;
  const chanKey = channelKeyFor(conv, useEpoch, channelId);
  if (!chanKey) throw new Error("waiting_for_key");
  const ciphertext = await encryptMessage(chanKey, { conv, epoch: useEpoch, channelId, authorId: me.id }, payload);
  const edit: Partial<SendMessageRequest> = { ciphertext, key_conv_id: conv, key_epoch: useEpoch, mentions, mention_roles: roles, mention_everyone: everyone };
  // the apps it is addressed to get the edit too; an app no longer addressed loses its copy
  const bots = addressedBots(channelId, mentions, replied, me.id);
  edit.bot_copies = bots.length ? await sealCopies(bots, payload) : {};
  const updated = await messagesApi.edit(channelId, messageId, edit);
  afterEdit?.(messageId);
  const decrypted = await decryptForStore(updated);
  useMessages.getState().update(channelId, decrypted);
}

/** Sends a failed message again from the copy still sitting in the list. */
export async function retryPending(channelId: string, tmpId: string): Promise<void> {
  const store = useMessages.getState();
  const p = store.channels[channelId]?.pending.find((x) => x.tmpId === tmpId);
  const me = useAuth.getState().me;
  if (!p || !me) return;
  const opts: SendOptions = {};
  if (p.replyToId) opts.replyTo = p.replyToId;
  if (p.ping !== undefined) opts.ping = p.ping;
  if (p.flags) opts.flags = p.flags;
  if (p.payload.embeds?.length) opts.embeds = p.payload.embeds;
  if (p.payload.sticker) opts.sticker = p.payload.sticker;
  if (p.payload.poll) opts.poll = p.payload.poll;
  if (p.upload && !p.upload.done && uploading.has(tmpId)) {
    // its files never made it up: they go up again, in the same place in the list
    store.updatePending(channelId, tmpId, { status: "sending", upload: { ...p.upload, loaded: 0, speed: 0, error: null } });
    await uploadThenSend(channelId, me.id, tmpId, p.nonce, written(channelId, p.payload.t ?? "", opts), opts).catch(() => undefined);
    return;
  }
  opts.replaces = tmpId;
  if (p.payload.att?.length) {
    // the files were already uploaded; reuse the blobs rather than asking again
    opts.attachments = p.payload.att.map((meta) => ({ meta, blob_id: meta.blob_id, size: meta.size }));
  }
  const sending = sendMessage(channelId, p.payload.t ?? "", opts);
  // the failed copy is gone now, and its previews with it
  forget(tmpId);
  try {
    await sending;
  } catch {
    /* sendMessage already reported it and left a fresh failed copy */
  }
}

/** Throws away a message that could not be sent, or stops one whose files are still going up. */
export function discardPending(channelId: string, tmpId: string): void {
  uploading.get(tmpId)?.stop?.abort();
  forget(tmpId);
  useMessages.getState().removePending(channelId, tmpId);
}

/** Stops every upload and lets go of their files (sign-out). */
/** Sign-out: nothing queued goes out, and every upload stops. */
export function forgetSends(): void {
  account++;
  queues.clear();
  stopAllUploads();
}

export function stopAllUploads(): void {
  for (const [tmp, job] of [...uploading]) {
    job.stop?.abort();
    forget(tmp);
  }
}
