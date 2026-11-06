import { automod as automodApi } from "@/api/automod";
import type { AutomodPresets, AutomodRule, Channel } from "@/api/types";
import { channelKey } from "@/crypto/conv";
import { decryptMessage, encryptMessage, type Envelope, type MessagePayload } from "@/crypto/message";
import type { DecryptedMessage } from "@/lib/decrypt";
import { channelKeyFor, ring } from "@/lib/keyring";
import { P, canActOn, has } from "@/lib/perms";
import { useChannels } from "@/stores/channels";
import { channelPerms, memberHighest, serverPerms, useServers, type ServerState } from "@/stores/servers";
import { isBareGifPath } from "@/components/chat/mediaLinks";
import { checkAttachments, checkCaps, checkEmoji, checkInvites, checkLinks, checkPersonal, linksIn, scam, shadyLink, wall, zalgo } from "./checks";
import { Matcher } from "./match";

/**
 * AutoMod in this app. Messages are end-to-end encrypted, so the server
 * cannot read them: the rules that read what a message says are applied by
 * members' apps. The sender's app stops a message before it goes (and tells
 * its sender why), everyone's app keeps hidden what got past anyway, and a
 * moderator's app clears it away. The server applies the rest itself
 * (mentions, flooding, names and profiles, thread and post titles).
 */

/** The kinds members' apps apply. */
const CLIENT_KINDS = new Set<string>(["flagged_words", "words", "spam", "links", "invites", "personal", "caps", "emoji_spam", "attachments"]);

interface Loaded {
  rules: AutomodRule[];
  /** The server's automod_at the rules were fetched for. */
  stamp: number;
}

const loaded = new Map<string, Loaded>();
const loading = new Map<string, Promise<void>>();
let presets: AutomodPresets | null = null;
let presetsLoading: Promise<void> | null = null;
/** Bumped whenever the rules change, so what was worked out from the old ones is worked out again. */
let version = 0;
const listeners = new Set<() => void>();

/** Called whenever the rules this app holds change. */
export function onAutomodChange(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** Which version of the rules is held, for views that remember what the rules said. */
export function automodVersion(): number {
  return version;
}

function changed(): void {
  version++;
  verdicts.clear();
  for (const fn of listeners) fn();
}

/** The server's mark of when its rules last changed: 0 when it has none. */
function stampOf(serverId: string): number {
  return useServers.getState().byId[serverId]?.server.automod_at ?? 0;
}

/** The rules of a server changed (or it was left): fetched again if it has any. */
export function forgetAutomodRules(serverId: string): void {
  loaded.delete(serverId);
  if (stampOf(serverId) > 0) void loadAutomod(serverId);
  changed();
}

/** Fetches the rules of the servers that have any, one after another, after connecting. */
export function warmAutomod(serverIds: string[]): void {
  void (async () => {
    for (const id of serverIds) {
      const l = loaded.get(id);
      if (l && l.stamp === stampOf(id)) continue;
      await loadAutomod(id);
    }
  })();
}

/** Signing out: nothing about the account's servers stays. */
export function clearAutomod(): void {
  loaded.clear();
  loading.clear();
  recent.clear();
  arrived.clear();
  changed();
}

/** Fetches a server's rules (and the ready-made lists, when a rule uses them). */
export function loadAutomod(serverId: string): Promise<void> {
  const busy = loading.get(serverId);
  if (busy) return busy;
  const stamp = stampOf(serverId);
  const job = automodApi
    .forMembers(serverId)
    .then(async (rules) => {
      const kept = rules.filter((r) => r.enabled && CLIENT_KINDS.has(r.kind));
      if (kept.some((r) => r.kind === "flagged_words" || r.kind === "spam")) await loadPresets();
      loaded.set(serverId, { rules: kept, stamp });
      changed();
    })
    .catch(() => undefined)
    .finally(() => loading.delete(serverId));
  loading.set(serverId, job);
  return job;
}

function loadPresets(): Promise<void> {
  if (presets) return Promise.resolve();
  presetsLoading ??= automodApi
    .presets()
    .then((p) => {
      presets = p;
    })
    .catch(() => undefined)
    .finally(() => {
      presetsLoading = null;
    });
  return presetsLoading;
}

/** The ready-made lists, fetched once and kept. */
export async function automodPresets(): Promise<AutomodPresets | null> {
  await loadPresets();
  return presets;
}

/** Draft matchers, for trying a rule out as it is written, by its words. */
const drafts = new Map<string, Matcher>();

/**
 * What a rule as it stands in the editor (saved or not) would catch in a
 * message, with no one exempt: for its Try It box. Repeats are not tried.
 */
export function tryRule(rule: Pick<AutomodRule, "kind" | "config">, text: string, files: readonly string[] = []): string | null {
  const c = rule.config;
  if (rule.kind === "words" || rule.kind === "flagged_words" || rule.kind === "profile") {
    const key = JSON.stringify([c.words ?? [], c.lists ?? [], c.regex ?? [], c.allow ?? [], c.evasion !== false, !!presets]);
    let m = drafts.get(key);
    if (!m) {
      const words = [...(c.words ?? [])];
      for (const name of c.lists ?? []) words.push(...(presets?.lists[name] ?? []));
      m = new Matcher(words, c.regex ?? [], c.allow ?? [], c.evasion !== false);
      drafts.set(key, m);
      if (drafts.size > 20) drafts.delete(drafts.keys().next().value as string);
    }
    return m.match(text);
  }
  if (rule.kind === "spam") {
    const s = { ...c, repeats: false };
    return evaluate({ ...(rule as AutomodRule), config: s }, "", "", { text, files }, Date.now(), "view");
  }
  return evaluate(rule as AutomodRule, "", "", { text, files }, Date.now(), "view");
}

/** A server's rules as this app holds them; null until they are loaded (which this starts). */
export function rulesNow(serverId: string): AutomodRule[] | null {
  const stamp = stampOf(serverId);
  if (stamp === 0) return [];
  const l = loaded.get(serverId);
  if (!l || l.stamp !== stamp) void loadAutomod(serverId);
  return l?.rules ?? null;
}

/** Whether a server's rules are here to be applied (a server without any counts as here). */
function rulesHere(serverId: string): boolean {
  return stampOf(serverId) === 0 || loaded.has(serverId);
}

/**
 * Waits (briefly) for a server's rules, so the first message sent after
 * opening the app is checked like every other.
 */
export async function automodReady(serverId: string, waitMs = 2500): Promise<void> {
  const l = loaded.get(serverId);
  const stamp = stampOf(serverId);
  if (stamp === 0 || (l && l.stamp === stamp)) return;
  const job = loadAutomod(serverId);
  await Promise.race([job, new Promise((r) => setTimeout(r, waitMs))]);
}

const matchers = new Map<string, Matcher>();

function matcherOf(rule: AutomodRule): Matcher {
  const key = `${rule.id}:${rule.updated_at}:${presets ? 1 : 0}`;
  let m = matchers.get(key);
  if (!m) {
    const words = [...(rule.config.words ?? [])];
    for (const name of rule.config.lists ?? []) words.push(...(presets?.lists[name] ?? []));
    m = new Matcher(words, rule.config.regex ?? [], rule.config.allow ?? [], rule.config.evasion !== false);
    matchers.set(key, m);
    if (matchers.size > 200) matchers.delete(matchers.keys().next().value as string);
  }
  return m;
}

/** What one rule caught, and what it does about it. */
export interface Catch {
  rule: AutomodRule;
  /** The word, link or sign it found. */
  matched: string;
  /** The rule stops the message. */
  blocks: boolean;
  /** What a block says to whoever it stopped (empty: the usual words). */
  message: string;
}

/** What a message is made of, as AutoMod reads it. */
export interface Readable {
  text: string;
  files: readonly string[];
}

/** The words of a message (with a poll's question and answers) and its files' names. */
export function readable(p: MessagePayload | undefined): Readable {
  if (!p) return { text: "", files: [] };
  // a picker GIF's text is its signed path, which nobody reads and whose
  // digits and letters only look like phone numbers and words
  const parts = [p.fwd?.comment ?? "", isBareGifPath(p.t ?? "") ? "" : (p.t ?? "")];
  if (p.poll) {
    parts.push(p.poll.question ?? "");
    for (const a of p.poll.answers ?? []) parts.push(a.text ?? "");
  }
  return { text: parts.filter(Boolean).join("\n"), files: (p.att ?? []).map((a) => a.name) };
}

/** Whether a member is known well enough here to say which rules hold them. */
function known(st: ServerState, userId: string): boolean {
  return st.server.owner_id === userId || !!st.members[userId];
}

/** Anyone who may manage the server is never held by AutoMod. */
function exemptPerson(st: ServerState, userId: string): boolean {
  const p = serverPerms(st, userId);
  return has(p, P.MANAGE_SERVER) || has(p, P.ADMINISTRATOR);
}

function exemptHere(st: ServerState, rule: AutomodRule, userId: string, channel: Channel): boolean {
  // a thread is left alone where its channel is
  if (rule.exempt_channels.includes(channel.id) || (!!channel.parent_id && rule.exempt_channels.includes(channel.parent_id))) return true;
  const roles = st.members[userId]?.role_ids ?? [];
  return roles.some((r) => rule.exempt_roles.includes(r));
}

type Mode = "send" | "arrive" | "view";

/** The messages people sent lately, by server and person, for spotting the same one over and over. */
const recent = new Map<string, { text: string; at: number }[]>();

function repeated(serverId: string, userId: string, text: string, now: number): boolean {
  const norm = text.trim().toLowerCase().replace(/\s+/g, " ");
  if (norm.length < 4) return false;
  const key = `${serverId}:${userId}`;
  const list = (recent.get(key) ?? []).filter((m) => now - m.at < 60_000);
  const same = list.filter((m) => m.text === norm).length;
  list.push({ text: norm, at: now });
  recent.set(key, list.slice(-20));
  if (recent.size > 2000) recent.delete(recent.keys().next().value as string);
  // the third time in a minute
  return same >= 2;
}

function evaluate(rule: AutomodRule, serverId: string, authorId: string, msg: Readable, now: number, mode: Mode): string | null {
  const c = rule.config;
  const text = msg.text;
  switch (rule.kind) {
    case "words":
      return matcherOf(rule).match(text);
    case "flagged_words":
      return presets || (c.words?.length ?? 0) > 0 ? matcherOf(rule).match(text) : null;
    case "links":
      return checkLinks(text, c);
    case "invites":
      return checkInvites(text);
    case "personal":
      return checkPersonal(text, c);
    case "caps":
      return checkCaps(text, c);
    case "emoji_spam":
      return checkEmoji(text, c);
    case "attachments":
      return checkAttachments(msg.files, c);
    case "spam": {
      // repeats are counted as messages go and come, never over history
      if (c.repeats && mode !== "view" && repeated(serverId, authorId, text, now)) return "repeat";
      if (c.zalgo) {
        const z = zalgo(text);
        if (z) return z;
      }
      if (c.walls) {
        const w = wall(text);
        if (w) return w;
      }
      if (!presets) return null;
      if (c.scams) {
        const s = scam(text, presets);
        if (s) return s;
      }
      if (c.shady_links) return shadyLink(linksIn(text), presets);
      return null;
    }
  }
  return null;
}

/**
 * What a server's rules catch in a message by authorId in a channel. A rule
 * never reaches back past when it was last changed (writtenAt). An empty list
 * when nothing does, or when the rules or the author are not known yet.
 */
export function check(channel: Channel, authorId: string, msg: Readable, opts: { mode: Mode; writtenAt?: number }): Catch[] {
  const serverId = channel.server_id;
  if (!serverId) return [];
  const rules = rulesNow(serverId);
  const st = useServers.getState().byId[serverId];
  if (!rules || rules.length === 0 || !st || !known(st, authorId) || exemptPerson(st, authorId)) return [];
  const now = Date.now();
  const out: Catch[] = [];
  for (const rule of rules) {
    if (opts.writtenAt !== undefined && opts.writtenAt < rule.updated_at) continue;
    if (exemptHere(st, rule, authorId, channel)) continue;
    const hit = evaluate(rule, serverId, authorId, msg, now, opts.mode);
    if (hit === null) continue;
    const block = rule.actions.find((a) => a.type === "block");
    out.push({ rule, matched: hit, blocks: !!block, message: block?.message ?? "" });
  }
  return out;
}

/** What blocked a message, worked out once per message and version of the rules. */
const verdicts = new Map<string, Catch | null>();
/** What a moderator's app caught as messages arrived (repeats are only seen then). */
const arrived = new Map<string, Catch>();

/** The channel a message is in, when it is a server's. */
function serverChannel(channelId: string): Channel | null {
  const ch = useChannels.getState().byId[channelId];
  return ch?.server_id ? ch : null;
}

/** Messages AutoMod reads: what members wrote, not notes, webhooks, apps or calls. */
function readsMessage(m: DecryptedMessage): boolean {
  return !m.type.startsWith("system_") && !m.webhook_id && !m.hook && !!m.payload;
}

/**
 * The rule that keeps a message someone else sent hidden, or null: shown as
 * held by AutoMod rather than as it was written.
 */
export function heldBy(m: DecryptedMessage, meId: string | undefined): Catch | null {
  if (m.author_id === meId || !readsMessage(m)) return null;
  const early = arrived.get(m.id);
  if (early) return early;
  const key = `${version}:${m.id}:${m.edited_at ?? 0}`;
  const had = verdicts.get(key);
  if (had !== undefined) return had;
  const ch = serverChannel(m.channel_id);
  if (!ch) return null;
  const st = useServers.getState().byId[ch.server_id!];
  // without the rules or the author's roles, not yet: asked again once they are here
  if (!st || !rulesHere(ch.server_id!) || !known(st, m.author_id)) return null;
  const found = check(ch, m.author_id, readable(m.payload), { mode: "view", writtenAt: m.created_at }).find((c) => c.blocks) ?? null;
  verdicts.set(key, found);
  if (verdicts.size > 3000) verdicts.delete(verdicts.keys().next().value as string);
  return found;
}

/** The server's key AutoMod seals what it caught with, which every member's app holds. */
async function seal(serverId: string, offenderId: string, text: string): Promise<Envelope | undefined> {
  const latest = ring.latest(serverId);
  if (!latest) return undefined;
  const key = channelKey(latest.root, serverId);
  return encryptMessage(key, { conv: serverId, epoch: latest.epoch, channelId: serverId, authorId: offenderId }, { t: text.slice(0, 4000) });
}

/** Opens what AutoMod caught, for a moderator reading an alert or the log; null when it cannot be read here. */
export async function openCaught(serverId: string, offenderId: string, env: Envelope): Promise<string | null> {
  const key = channelKeyFor(env.conv, env.epoch, serverId);
  if (!key) return null;
  try {
    const payload = await decryptMessage(key, env, { conv: env.conv, epoch: env.epoch, channelId: serverId, authorId: offenderId });
    return payload.t ?? "";
  } catch {
    return null;
  }
}

/**
 * Tells the server what a rule caught: this account's own message (stopped
 * before it went, or sent, messageId, when the rule lets it through), or
 * someone else's that got past, which a moderator's app clears away. What
 * was caught goes sealed with the server's key. The actions taken come back.
 */
export async function report(channel: Channel, c: Catch, offenderId: string, text: string, messageId?: string): Promise<string[]> {
  const serverId = channel.server_id;
  if (!serverId) return [];
  try {
    const content = await seal(serverId, offenderId, text);
    const res = await automodApi.report(serverId, { rule_id: c.rule.id, channel_id: channel.id, ...(messageId ? { message_id: messageId } : {}), ...(content ? { content } : {}) });
    return res?.actions ?? [];
  } catch {
    return [];
  }
}

/** Messages a moderator's app has already reported. */
const reported = new Set<string>();

/**
 * A message that just arrived. Repeats are counted, and in a moderator's app
 * what a rule catches is reported: cleared away when the rule stops such
 * messages, written down and alerted when it only watches for them. After a
 * moment, so the sender's own app (and other moderators') can go first.
 */
export function onArrive(m: DecryptedMessage, meId: string | undefined): void {
  if (!meId || m.author_id === meId || !readsMessage(m)) return;
  // only what arrives as it is sent, never history coming back
  if (Math.abs(Date.now() - m.created_at) > 60_000) return;
  const ch = serverChannel(m.channel_id);
  if (!ch) return;
  const st = useServers.getState().byId[ch.server_id!];
  if (!st || stampOf(ch.server_id!) === 0 || !loaded.has(ch.server_id!)) return;
  const text = readable(m.payload);
  const caught = check(ch, m.author_id, text, { mode: "arrive", writtenAt: m.created_at });
  const blocking = caught.find((c) => c.blocks);
  if (blocking) {
    arrived.set(m.id, blocking);
    if (arrived.size > 500) arrived.delete(arrived.keys().next().value as string);
    changed();
  }
  if (caught.length === 0 || !has(channelPerms(st, ch.id, meId), P.MANAGE_MESSAGES)) return;
  const mine = memberHighest(st, meId);
  const theirs = memberHighest(st, m.author_id);
  if (!canActOn(mine.highest, theirs.highest, mine.isOwner, theirs.isOwner)) return;
  for (const c of caught) {
    const key = `${c.rule.id}:${m.id}`;
    if (reported.has(key)) continue;
    reported.add(key);
    if (reported.size > 2000) reported.delete(reported.values().next().value as string);
    // a rule that blocks clears it away soon; one that lets it through waits for the sender's own report
    const wait = c.blocks ? 400 + Math.random() * 1600 : 5000 + Math.random() * 5000;
    setTimeout(() => {
      if (!useChannels.getState().byId[ch.id]) return;
      void report(ch, c, m.author_id, text.text, m.id);
    }, wait);
  }
}

/** What stopping a message tells its sender, and what came of it. */
export interface Stopped {
  catches: Catch[];
  /** The first rule that blocked it. */
  first: Catch;
}

/**
 * Checks a message this account is about to send. Returns what stops it, if
 * anything; reports every rule that caught it (what stopped it now, and what
 * lets it through once it has been sent, through afterSend).
 */
export function screenOutgoing(channel: Channel, meId: string, payload: MessagePayload, opts: { onlyIfLoaded?: boolean } = {}): { stopped: Stopped | null; afterSend: (messageId: string) => void } | null {
  const serverId = channel.server_id;
  if (opts.onlyIfLoaded && serverId && !rulesHere(serverId)) return null;
  const msg = readable(payload);
  const catches = check(channel, meId, msg, { mode: "send" });
  const blocking = catches.filter((c) => c.blocks);
  if (blocking.length) {
    // every rule that caught it acts, as the server would have had it read the message
    for (const c of catches) void report(channel, c, meId, msg.text);
    return { stopped: { catches, first: blocking[0]! }, afterSend: () => undefined };
  }
  return {
    stopped: null,
    afterSend: (messageId) => {
      for (const c of catches) void report(channel, c, meId, msg.text, messageId);
    },
  };
}
