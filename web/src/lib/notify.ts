import { MSG_FLAG_SUPPRESS_NOTIFICATIONS, type Message, type NoteInviteRow, type Relationship } from "@/api/types";
import { avatarUrl } from "@/api/users";
import { dispatchHooks, type NexaAlert } from "@/gateway/dispatch";
import { noticeTitle } from "@/lib/moderation";
import { channelLabel } from "@/lib/channelGlyph";
import { channelDisplayName } from "@/lib/names";
import { parse, plainText } from "@/markdown";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useDesktop } from "@/stores/desktop";
import { useMessages, type DecryptedMessage } from "@/stores/messages";
import { useServers } from "@/stores/servers";
import { useSettings, type Settings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useUsers, displayName } from "@/stores/users";
import { desktopBridge } from "./desktopBridge";
import { doNotDisturb } from "./dnd";
import { installAudioUnlock, playSound, preloadSounds } from "./sounds";
import { installSoundSettings } from "./soundSync";
import { mentionsMe } from "./mentionsMe";
import { hookText } from "./webhookMessage";
import { pollWords } from "./poll";
import { mediaSrc } from "./mediaLinks";
import { roundAvatar } from "./roundIcon";
import { heldBy } from "./automod/rules";
import { currentBlockRules, isQuiet, loadedAuthorOf } from "./blocked";
import { bugAlertHooks, bugNumber, bugStatusLabel, type BugAlert } from "./bugs";
import { alertsForReport, staffAlerts } from "./staffAlerts";
import { reasonTone } from "./reportReasons";
import type { SafetyReport } from "@/api/reports";
import type { People } from "@/api/moderation";

import { t } from "@/i18n";
let navigateTo: ((path: string) => void) | null = null;

/** The router's navigate function, supplied by the shell. */
export function setNotificationNavigator(fn: ((path: string) => void) | null): void {
  navigateTo = fn;
}

/**
 * Whether new messages show a notification. The desktop app shows them
 * unless they were turned off there; a browser only once the person has
 * allowed them, which the settings page asks for.
 */
export function notificationsEnabled(settings: Settings): boolean {
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return false;
  return desktopBridge() ? settings.notifications.app !== false : settings.notifications.desktop;
}

/**
 * Shows a notification the way this browser allows. Chrome on Android and
 * iOS home-screen apps refuse the Notification constructor and only take one
 * through the service worker, which then hands the click back (see sw.ts).
 * The desktop app shows them natively through the constructor, and a click
 * brings its window back even from the tray. Resolves false when neither way
 * works.
 */
export async function showNotification(title: string, opts: NotificationOptions, path?: string): Promise<boolean> {
  const bridge = desktopBridge();
  if (!bridge) {
    try {
      const reg = await navigator.serviceWorker?.getRegistration("/");
      if (reg?.active) {
        await reg.showNotification(title, { ...opts, data: { path } });
        return true;
      }
    } catch {
      /* no usable worker: try the constructor */
    }
  }
  try {
    const n = new Notification(title, opts);
    if (opts.tag) {
      const tag = opts.tag;
      shownByTag.set(tag, n);
      n.onclose = () => {
        if (shownByTag.get(tag) === n) shownByTag.delete(tag);
      };
    }
    n.onclick = () => {
      // window.focus() cannot bring the app's window back from the tray or a minimised taskbar button
      if (bridge?.focusWindow) bridge.focusWindow();
      else window.focus();
      if (path) navigateTo?.(path);
      n.close();
    };
    // the desktop app also asks for attention the way its system does, until the window is looked at
    bridge?.requestAttention?.();
    return true;
  } catch {
    return false;
  }
}

/** Notifications made through the constructor, by tag, so an incoming call's can be taken down once it is answered. */
const shownByTag = new Map<string, Notification>();

/** Closes whatever notification carries this tag, however it was shown. */
export async function closeNotifications(tag: string): Promise<void> {
  shownByTag.get(tag)?.close();
  shownByTag.delete(tag);
  try {
    const reg = await navigator.serviceWorker?.getRegistration("/");
    for (const n of (await reg?.getNotifications({ tag })) ?? []) n.close();
  } catch {
    /* nothing shown that way */
  }
}

/**
 * Whether the app is in front of the person: its window (or tab) focused and
 * showing. The browser is asked rather than a stored flag, because a window
 * can regain focus without firing the event that flag listens for.
 */
export function appOnScreen(): boolean {
  // the desktop app says where its window is: in a call its page counts as visible while hidden, and its focus reading was seen to stay true
  const w = useDesktop.getState().window;
  if (w) return w.focused && w.visible && !w.minimized;
  return typeof document !== "undefined" && document.visibilityState === "visible" && document.hasFocus();
}

/**
 * What a new message does: nothing in the conversation being looked at, a
 * sound anywhere else, and a notification whenever the app is not in front of
 * the person (unfocused, minimised, in the tray, a background tab). While it
 * is in front of them the unread badge and the sound are enough.
 */
export function messageAlert(onScreen: boolean, viewingChannel: boolean): { sound: boolean; popup: boolean } {
  if (onScreen && viewingChannel) return { sound: false, popup: false };
  return { sound: true, popup: !onScreen };
}

function previewOf(m: Message, decrypted?: DecryptedMessage): string {
  // The decrypted copy is handed in because the store may not have it: a list
  // jumped back into history refuses messages past its end, and reading it
  // back gave every one of them a notification saying only "New message".
  const stored = decrypted ?? useMessages.getState().channels[m.channel_id]?.byId[m.id];
  const p = stored?.payload;
  if (!p) return stored?.decryptError ? t("chat.messageItem.encryptedMessage") : t("notify.newMessage");
  if (p.fwd?.comment || p.t) return plainText(parse([p.fwd?.comment, p.t].filter(Boolean).join("\n"))).slice(0, 140);
  // a poll says it in its question and answers
  if (p.poll) return pollWords(p.poll).slice(0, 140);
  // a webhook's message often says it all in its embeds
  const words = stored?.hook ? hookText(stored.hook) : "";
  if (words) return plainText(parse(words)).slice(0, 140);
  if (p.att?.length) return p.att.length === 1 ? t("notify.sentFile", { name: p.att[0]!.name }) : t("notify.sentFiles", { count: p.att.length });
  if (p.sticker) return t("notify.sentASticker");
  return t("notify.newMessage");
}

/** What a server's owner chose for members who have not chosen for themselves. */
export function serverDefaultLevel(serverId: string): "all" | "mentions" {
  return useServers.getState().byId[serverId]?.server.default_notifications === 1 ? "mentions" : "all";
}

function onMessage(m: Message, mine: boolean, decrypted?: DecryptedMessage): void {
  if (mine || m.type.startsWith("system_")) return;
  // sent to be read, not heard: it still counts as unread, and nothing else
  if (m.flags & MSG_FLAG_SUPPRESS_NOTIFICATIONS) return;
  // nothing from someone blocked (or answering them, while those are hidden) makes a sound or a notification
  if (isQuiet(decrypted ?? m, currentBlockRules(), loadedAuthorOf(m.channel_id))) return;
  const me = useAuth.getState().me;
  if (!me) return;
  // nor does anything AutoMod holds back
  if (decrypted && heldBy(decrypted, me.id)) return;
  const settings = useSettings.getState().settings;
  const { perChannel, muted } = settings.notifications;
  const chan = useChannels.getState().byId[m.channel_id];
  const serverId = chan?.server_id ?? null;
  // a subchat follows the conversation it belongs to, as its sidebar row does
  const parentId = chan?.type === "subchat" ? chan.parent_id : null;
  const level = perChannel[m.channel_id] ?? (parentId ? perChannel[parentId] : undefined) ?? (serverId ? perChannel[serverId] : undefined) ?? (serverId ? serverDefaultLevel(serverId) : "all");
  if (muted.includes(m.channel_id) || (parentId && muted.includes(parentId)) || (serverId && muted.includes(serverId)) || level === "none") return;
  const mentioned = mentionsMe(m, me.id);
  if (level === "mentions" && !mentioned) return;
  const ui = useUI.getState();
  const alert = messageAlert(appOnScreen(), ui.activeChannelId === m.channel_id);
  if (!alert.sound && !alert.popup) return;
  if (doNotDisturb()) return;
  if (alert.sound && settings.notifications.sounds) playSound(mentioned ? "mention" : "message");
  if (!alert.popup || !notificationsEnabled(settings)) return;
  const users = useUsers.getState().byId;
  const ch = useChannels.getState().byId[m.channel_id];
  const path = ch?.server_id ? `/channels/${ch.server_id}/${m.channel_id}` : `/channels/@me/${m.channel_id}`;
  // A notification is the one place a message is shown outside the app: on a
  // locked phone's screen, in a desktop's notification history. Whoever wants
  // less there than the whole of it can say so.
  const preview = settings.notifications.preview ?? "full";
  if (preview === "none") {
    void showNotification("Nexa", { body: t("notify.newMessage"), tag: `ec-${m.channel_id}`, icon: "/icons/icon-192.png", silent: true }, path);
    return;
  }
  // a webhook's message goes by the name and picture it was sent with; an app's, by its bot's
  const hook = decrypted?.hook && m.webhook_id ? decrypted.hook : undefined;
  const author = hook?.name ?? displayName(users[m.author_id]);
  const title = ch && ch.type === "group_dm" ? `${author} (${channelDisplayName(ch, users, me.id)})` : ch && ch.server_id ? `${author} (${channelLabel(ch)})` : author;
  const body = preview === "full" ? previewOf(m, decrypted) : t("notify.newMessage");
  const picture = hook ? ((hook.avatar_proxy_url && mediaSrc(hook.avatar_proxy_url)) || avatarUrl(hook.avatar_id ?? null, 96)) : avatarUrl(users[m.author_id]?.avatar_id ?? null, 96);
  // round, as the app draws it, whatever the system does with a square one
  void roundAvatar({ id: hook ? (m.webhook_id ?? m.author_id) : m.author_id, name: author, url: picture }, "/icons/icon-192.png").then((icon) =>
    showNotification(title, { body, tag: `ec-${m.channel_id}`, icon, silent: true }, path),
  );
}

/** A new friend request plays its own sound, and notifies while the app is not being looked at. */
function onFriendRequest(r: Relationship): void {
  const me = useAuth.getState().me;
  if (!me || doNotDisturb()) return;
  const settings = useSettings.getState().settings;
  if (settings.notifications.sounds) playSound("friend");
  if (appOnScreen()) return;
  if (!notificationsEnabled(settings)) return;
  // with nothing to be shown, not even who it is from
  if (settings.notifications.preview === "none") {
    void showNotification("Nexa", { body: t("notify.friendRequest"), tag: `ec-friend-${r.user.id}`, icon: "/icons/icon-192.png", silent: true }, "/channels/@me");
    return;
  }
  const name = displayName(r.user);
  void roundAvatar({ id: r.user.id, name, url: avatarUrl(r.user.avatar_id ?? null, 96) }, "/icons/icon-192.png").then((icon) =>
    showNotification(t("notify.friendRequest"), { body: t("notify.sentYouAFriendRequest", { name }), tag: `ec-friend-${r.user.id}`, icon, silent: true }, "/channels/@me"),
  );
}

/**
 * Something new from Nexa: a notice about this account, a staff reply in one
 * of its cases, an announcement — or, for staff, a member writing in a case.
 * It sounds like a message and notifies like one, and stays quiet the same
 * ways: sounds off, Do Not Disturb, or already looking at it.
 */
function onNexa(a: NexaAlert): void {
  if (a.kind === "newReport") return onNewReport(a.report, a.people);
  const me = useAuth.getState().me;
  if (!me || doNotDisturb()) return;
  if (a.kind === "appeal" && !staffAlerts(useSettings.getState().settings).cases) return;
  const path =
    a.kind === "appeal" ? `/channels/@admin/cases/${a.caseId}` : a.kind === "announcement" || a.kind === "report" ? "/channels/@me/nexa" : a.kind === "reply" ? `/channels/@me/nexa/cases/${a.case.id}` : "/channels/@me/nexa";
  const onScreen = appOnScreen();
  const viewing = typeof location !== "undefined" && (location.pathname === path || (a.kind === "appeal" && location.pathname.endsWith(`/cases/${a.caseId}`)));
  if (onScreen && viewing) return;
  const settings = useSettings.getState().settings;
  if (settings.notifications.sounds) void playSound(a.kind === "notice" ? "mention" : "message");
  if (onScreen || !notificationsEnabled(settings)) return;
  const title = a.kind === "appeal" ? t("nexa.notify.appealTitle") : "Nexa";
  const body =
    a.kind === "notice"
      ? noticeTitle(a.case.action, a.case.details)
      : a.kind === "reply"
        ? t("nexa.notify.reply", { number: a.case.number })
        : a.kind === "announcement"
          ? a.announcement.title || plainText(parse(a.announcement.body)).slice(0, 140)
          : a.kind === "report"
            ? t("reports.outcome.notify")
            : t("nexa.notify.appeal");
  const tag = a.kind === "appeal" ? `ec-appeal-${a.caseId}` : a.kind === "announcement" ? `ec-nexa-a-${a.announcement.id}` : a.kind === "report" ? `ec-report-${a.report.id}` : `ec-nexa-${a.case.id}`;
  void showNotification(title, { body, tag, icon: "/icons/icon-192.png", silent: true }, path);
}

/**
 * Something new about a bug report: for the reporter, the team replied or
 * changed its status; for staff, a report arrived or its reporter wrote. It
 * sounds and notifies like a message from Nexa, and stays quiet the same ways.
 */
function onBugAlert(a: BugAlert): void {
  const me = useAuth.getState().me;
  if (!me || doNotDisturb()) return;
  if (a.kind === "staff" && !staffAlerts(useSettings.getState().settings).bugs) return;
  const path = a.kind === "staff" ? `/channels/@admin/bugs/${a.report.id}` : `/channels/@me/nexa/bugs/${a.report.id}`;
  if (appOnScreen() && typeof location !== "undefined" && location.pathname === path) return;
  const settings = useSettings.getState().settings;
  if (settings.notifications.sounds) void playSound("message");
  if (appOnScreen() || !notificationsEnabled(settings)) return;
  const number = a.report.number;
  const title = a.kind === "staff" ? (a.isNew ? t("bugs.notify.newTitle") : t("bugs.notify.replyTitle")) : "Nexa";
  const body = a.kind === "staff" ? a.report.subject : a.kind === "reply" ? t("bugs.feed.replied", { number: bugNumber(number) }) : t("bugs.feed.statusChanged", { number: bugNumber(number), status: bugStatusLabel(a.report.status) });
  void showNotification(title, { body, tag: `ec-bug-${a.report.id}`, icon: "/icons/icon-192.png", silent: true }, path);
}

/**
 * For staff: someone just sent a safety report. Every one, or only the urgent
 * ones (someone may be in danger), as Settings, Notifications says; an urgent
 * one sounds like a mention. Quiet while the report queue is on screen, where
 * it arrives in the list.
 */
function onNewReport(r: SafetyReport, people: People): void {
  const me = useAuth.getState().me;
  if (!me || r.reporter_id === me.id || doNotDisturb()) return;
  const settings = useSettings.getState().settings;
  if (!alertsForReport(settings, r.reason)) return;
  const path = `/channels/@admin/reports/${r.id}`;
  const onScreen = appOnScreen();
  if (onScreen && typeof location !== "undefined" && location.pathname.startsWith("/channels/@admin/reports")) return;
  const urgent = reasonTone(r.reason) === "urgent";
  if (settings.notifications.sounds) void playSound(urgent ? "mention" : "message");
  if (onScreen || !notificationsEnabled(settings)) return;
  const subject = r.subject_id !== "0" ? people[r.subject_id] : undefined;
  const name = subject ? subject.display_name || subject.username : "";
  const kind = t(`reports.kind.${r.target_kind}`);
  const about = name ? t("reports.notify.about", { kind, name }) : kind;
  const body = r.description ? `${about}\n${r.description.slice(0, 160)}` : about;
  void showNotification(t("reports.notify.newTitle", { reason: t(`reports.reason.${r.reason}`) }), { body, tag: `ec-staff-report-${r.id}`, icon: "/icons/icon-192.png", silent: true, requireInteraction: urgent }, path);
}

/** Someone asking to share a note sounds like a friend request, and notifies while the app is not being looked at. */
function onNoteInvite(inv: NoteInviteRow): void {
  const me = useAuth.getState().me;
  if (!me || doNotDisturb()) return;
  const settings = useSettings.getState().settings;
  if (settings.notifications.sounds) playSound("friend");
  if (appOnScreen() || !notificationsEnabled(settings)) return;
  const path = "/channels/@me/notes";
  if (settings.notifications.preview === "none") {
    void showNotification("Nexa", { body: t("notify.noteInvite"), tag: `ec-note-${inv.id}`, icon: "/icons/icon-192.png", silent: true }, path);
    return;
  }
  const name = displayName(inv.from);
  void roundAvatar({ id: inv.from.id, name, url: avatarUrl(inv.from.avatar_id ?? null, 96) }, "/icons/icon-192.png").then((icon) =>
    showNotification(t("notify.noteInvite"), { body: t("notify.sharedANote", { name }), tag: `ec-note-${inv.id}`, icon, silent: true }, path),
  );
}

let installed = false;
export function installNotifications(): void {
  if (installed) return;
  installed = true;
  dispatchHooks.onMessage.push(onMessage);
  dispatchHooks.onFriendRequest.push(onFriendRequest);
  dispatchHooks.onNoteInvite.push(onNoteInvite);
  dispatchHooks.onNexa.push(onNexa);
  bugAlertHooks.push(onBugAlert);
  // the pack, volume and sounds switched off follow the account's settings
  installSoundSettings();
  // a phone can only play sounds once a tap has let the page make noise
  installAudioUnlock();
  // the recorded sounds come down once the app has settled, so the first message is not late
  setTimeout(() => preloadSounds(), 4000);
  // a notification shown by the service worker is clicked there, which sends the chat to open back here
  navigator.serviceWorker?.addEventListener("message", (e: MessageEvent) => {
    const d = e.data as { type?: unknown; path?: unknown } | null;
    if (d?.type === "notification-open" && typeof d.path === "string" && d.path.startsWith("/")) navigateTo?.(d.path);
  });
}
