import { PersonName } from "@/components/common/PersonName";
import { ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { dispatchHooks } from "@/gateway/dispatch";
import type { GatewayEvent } from "@/gateway/events";
import { servers as serversApi } from "@/api/servers";
import type { AuditEntry } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { SelectBox } from "@/components/apps/SelectBox";
import { LoadError } from "@/components/common/LoadError";
import { Spinner } from "@/components/common/Spinner";
import { formatSpan } from "@/lib/moderation";
import { P, has, parsePerm, permGroups } from "@/lib/perms";
import { formatFull, formatShortDuration } from "@/lib/time";
import { useChannels } from "@/stores/channels";
import { useExpressions } from "@/stores/expressions";
import { useServers } from "@/stores/servers";
import { displayName, useUsers } from "@/stores/users";
import st from "@/components/settings/Settings.module.css";
import s from "../ServerSettings.module.css";

import { t } from "@/i18n";
import { isolate } from "../bidi";
import { LANGUAGES } from "@/i18n/core";
/** What each audited action says, looked up per render so it follows the language. */
function verbs(): Record<string, string> {
  return {
    server_update: t("server.auditLog.verb.server_update"),
    server_transfer: t("server.auditLog.verb.server_transfer"),
    channel_create: t("server.auditLog.verb.channel_create"),
    channel_update: t("server.auditLog.verb.channel_update"),
    channel_delete: t("server.auditLog.verb.channel_delete"),
    channel_positions: t("server.auditLog.verb.channel_positions"),
    overwrite_update: t("server.auditLog.verb.overwrite_update"),
    overwrite_delete: t("server.auditLog.verb.overwrite_delete"),
    overwrite_sync: t("server.auditLog.verb.overwrite_sync"),
    role_create: t("server.auditLog.verb.role_create"),
    role_update: t("server.auditLog.verb.role_update"),
    role_delete: t("server.auditLog.verb.role_delete"),
    role_positions: t("server.auditLog.verb.role_positions"),
    member_update: t("server.auditLog.verb.member_update"),
    member_role_add: t("server.auditLog.verb.member_role_add"),
    member_role_remove: t("server.auditLog.verb.member_role_remove"),
    member_kick: t("server.auditLog.verb.member_kick"),
    member_ban: t("server.auditLog.verb.member_ban"),
    member_unban: t("server.auditLog.verb.member_unban"),
    member_voice: t("server.auditLog.verb.member_voice"),
    emoji_create: t("server.auditLog.verb.emoji_create"),
    emoji_update: t("server.auditLog.verb.emoji_update"),
    emoji_delete: t("server.auditLog.verb.emoji_delete"),
    sticker_pack_create: t("server.auditLog.verb.sticker_pack_create"),
    sticker_pack_update: t("server.auditLog.verb.sticker_pack_update"),
    sticker_pack_delete: t("server.auditLog.verb.sticker_pack_delete"),
    invite_create: t("server.auditLog.verb.invite_create"),
    invite_delete: t("server.auditLog.verb.invite_delete"),
    webhook_create: t("server.auditLog.verb.webhook_create"),
    webhook_update: t("server.auditLog.verb.webhook_update"),
    webhook_delete: t("server.auditLog.verb.webhook_delete"),
    server_tag_update: t("server.auditLog.verb.server_tag_update"),
    server_tag_delete: t("server.auditLog.verb.server_tag_delete"),
    invite_delete_all: t("server.auditLog.verb.invite_delete_all"),
    invites_pause: t("server.auditLog.verb.invites_pause"),
    invites_resume: t("server.auditLog.verb.invites_resume"),
    member_prune: t("server.auditLog.verb.member_prune"),
    member_timeout: t("server.auditLog.verb.member_timeout"),
    member_timeout_remove: t("server.auditLog.verb.member_timeout_remove"),
    member_quarantine_lift: t("server.auditLog.verb.member_quarantine_lift"),
    message_delete: t("server.auditLog.verb.message_delete"),
    message_bulk_delete: t("server.auditLog.verb.message_bulk_delete"),
    message_pin: t("server.auditLog.verb.message_pin"),
    message_unpin: t("server.auditLog.verb.message_unpin"),
    message_publish: t("server.auditLog.verb.message_publish"),
    thread_create: t("server.auditLog.verb.thread_create"),
    thread_update: t("server.auditLog.verb.thread_update"),
    thread_delete: t("server.auditLog.verb.thread_delete"),
    follow_create: t("server.auditLog.verb.follow_create"),
    follow_delete: t("server.auditLog.verb.follow_delete"),
    automod_rule_create: t("server.auditLog.verb.automod_rule_create"),
    automod_rule_update: t("server.auditLog.verb.automod_rule_update"),
    automod_rule_delete: t("server.auditLog.verb.automod_rule_delete"),
    automod_action: t("server.auditLog.verb.automod_action"),
  };
}

/** AutoMod's own id, which stands for it as the one who did what it did. */
const AUTOMOD = "0";

/** A channel by the name its row shows: text and announcement channels with their "#", the rest without. */
function shownChannel(ch: { type: string; name: string }): string {
  return ch.type === "text" || ch.type === "announcement" ? `#${ch.name}` : ch.name;
}

/** What each kind of channel is called where one is made. */
function channelKind(type: string | undefined): string {
  switch (type) {
    case "voice":
      return t("modals.createChannel.voice");
    case "category":
      return t("server.auditLog.category");
    case "announcement":
      return t("modals.createChannel.announcement");
    case "forum":
      return t("modals.createChannel.forum");
    case "media":
      return t("modals.createChannel.media");
    case "link":
      return t("modals.createChannel.link");
    case "thread":
      return t("threads.create.public");
    case "private_thread":
      return t("threads.create.private");
    default:
      return t("modals.createChannel.text");
  }
}

const hex = (n: number) => `#${n.toString(16).padStart(6, "0")}`;

export default function AuditLogPage({ serverId }: { serverId: string }) {
  const users = useUsers((u) => u.byId);
  const upsertUsers = useUsers((u) => u.upsert);
  const channels = useChannels((c) => c.byId);
  const server = useServers((x) => x.byId[serverId]);
  const packs = useExpressions((x) => x.packs);
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  // A first page that failed used to read "Nothing has happened yet", which for
  // a moderation record is the most misleading sentence in the app. A later
  // page that failed keeps what is already on screen and says the rest is
  // missing.
  const [failed, setFailed] = useState(false);
  // Who and what to show. The log only ever arrives a page at a time, so this
  // narrows what has loaded; "Load more" carries on further back, filtered.
  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());

  const load = async (before?: string) => {
    setBusy(true);
    setFailed(false);
    try {
      const res = await serversApi.auditLog(serverId, { limit: 50, ...(before ? { before } : {}), ...(actor ? { actor_id: actor } : {}), ...(action ? { actions: [action] } : {}) });
      upsertUsers(res.users);
      setEntries((cur) => [...(before ? (cur ?? []) : []), ...res.entries]);
      setDone(res.entries.length < 50);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  // the filters are asked of the server, so older entries are found as well
  useEffect(() => {
    setEntries(null);
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, actor, action]);
  // what is done while the log is open comes in at the top
  useEffect(() => {
    const hook = (ev: GatewayEvent) => {
      if (ev.t !== "audit_log_entry" || ev.d.server_id !== serverId) return;
      const e = ev.d;
      if ((actor && e.actor_id !== actor) || (action && e.action !== action)) return;
      setEntries((cur) => (cur && !cur.some((x) => x.id === e.id) ? [e, ...cur] : cur));
    };
    dispatchHooks.onUnhandled.push(hook);
    return () => {
      dispatchHooks.onUnhandled.splice(dispatchHooks.onUnhandled.indexOf(hook), 1);
    };
  }, [serverId, actor, action]);
  const actorName = (id: string) => (id === AUTOMOD ? t("automod.name") : displayName(users[id]));

  const targetName = (e: AuditEntry): string => {
    if (!e.target_id) return "";
    if (e.target_type === "user") return displayName(users[e.target_id]);
    if (e.target_type === "channel") {
      const ch = channels[e.target_id];
      const changes = e.changes as { name?: string } | null;
      return isolate(ch ? shownChannel(ch) : changes?.name ? `#${changes.name}` : t("server.auditLog.aDeletedChannel"));
    }
    if (e.target_type === "role") {
      const changes = e.changes as { name?: string } | null;
      return server?.roles[e.target_id]?.name ?? changes?.name ?? t("server.auditLog.aDeletedRole");
    }
    if (e.target_type === "emoji") {
      const changes = e.changes as { name?: string } | null;
      return changes?.name ? isolate(`:${changes.name}:`) : "";
    }
    if (e.target_type === "webhook") {
      // a rename records the new name, other changes the name it has; creating and deleting record the one it had
      const changes = e.changes as { name?: string; current_name?: string } | null;
      const name = changes?.name || changes?.current_name;
      return name ? isolate(name) : "";
    }
    if (e.target_type === "sticker_pack") {
      // pack updates record no changes, so the pack's current name is the only one there is
      const changes = e.changes as { name?: string } | null;
      return changes?.name ?? packs[e.target_id]?.name ?? "";
    }
    return "";
  };
  // What an id in the changes stands for, where this client knows: a row
  // reading "role: 96767107336765440" told a moderator nothing about which
  // role was handed out.
  const roleName = (id: string): string | undefined => (id === serverId ? isolate("@everyone") : server?.roles[id]?.name);
  const channelName = (id: string): string | undefined => {
    const ch = channels[id];
    return ch ? isolate(shownChannel(ch)) : undefined;
  };
  const userName = (id: string): string | undefined => (users[id] ? displayName(users[id]) : undefined);
  const permNames = (v: unknown): string => {
    const bits = parsePerm(v as string);
    if (bits === 0n) return none();
    return permGroups()
      .flatMap((g) => g.items)
      .filter((it) => has(bits, P[it.name]))
      .map((it) => it.label)
      .join(", ");
  };
  const none = () => t("settings.profile.none");
  const onOff = (v: unknown) => (v ? t("server.auditLog.on") : t("server.channelSettings.slowmodeOff"));
  // What each recorded field is called on the screens where it is set, and its
  // value in the words those screens use: "max_age: 604800" was the invite's
  // lifetime in seconds, and "hoist: true" a checkbox a moderator knows by
  // another name.
  const field = (k: string, v: unknown, e: AuditEntry, obj: Record<string, unknown>): [string, string] | null => {
    const str = typeof v === "string" ? v : undefined;
    const num = typeof v === "number" ? v : undefined;
    switch (k) {
      case "name":
        return [t("modals.subchat.name"), str || none()];
      case "description":
        return [t("server.serverOverview.serverDescription"), str || none()];
      case "topic":
        return [t("server.channelSettings.channelTopic"), str || none()];
      case "type":
        return [t("modals.createChannel.channelType"), channelKind(str)];
      case "system_channel_id":
        return [t("server.serverOverview.systemMessagesChannel"), (str && channelName(str)) || t("server.serverOverview.noSystemMessages")];
      case "default_notifications":
        return [t("server.serverOverview.defaultNotificationSettings"), num === 1 ? t("server.serverOverview.onlyMentions") : t("server.serverOverview.allMessages")];
      case "nsfw":
        return [t("server.channelSettings.ageRestrictedChannel"), onOff(v)];
      case "slowmode_seconds":
        return [t("server.channelSettings.slowmode"), num ? formatShortDuration(num) : t("server.channelSettings.slowmodeOff")];
      case "user_limit":
        return [t("server.auditLog.userLimit"), num ? String(num) : t("server.channelSettings.noLimit")];
      case "color":
        return [t("server.roles.roleColour"), num ? `#${num.toString(16).padStart(6, "0")}` : t("server.roles.default")];
      case "tag":
        return [t("serverTag.page.tagLabel"), str || none()];
      case "tag_badge":
        return [t("serverTag.page.badge"), str?.startsWith("image:") ? t("serverTag.page.yourPicture") : (str ?? "").replace(/^icon:/, "")];
      case "tag_color":
        return [t("serverTag.page.color"), typeof num === "number" ? `#${num.toString(16).padStart(6, "0")}` : t("serverTag.page.accent")];
      case "tag_limited":
        return [t("serverTag.page.who"), v ? t("serverTag.page.someRoles") : t("serverTag.page.everyone")];
      case "tag_join":
        return [t("serverTag.page.join"), onOff(v)];
      case "hoist":
        return [t("server.roles.displayRoleMembersSeparatelyFromOnline"), onOff(v)];
      case "mentionable":
        return [t("server.roles.allowAnyoneToMentionThisRole"), onOff(v)];
      case "permissions":
        return [t("server.channelSettings.permissions"), permNames(v)];
      case "allow":
        return [t("server.permissionGrid.allow"), permNames(v)];
      case "deny":
        return [t("server.permissionGrid.deny"), permNames(v)];
      case "target_id":
      case "target": {
        // an overwrite names who it is for; a member's own entry already does
        if (!str) return null;
        const member = obj["target_type"] === "member";
        return [member ? t("server.channelSettings.member") : t("server.channelSettings.role"), (member ? userName(str) : (roleName(str) ?? userName(str))) ?? str];
      }
      case "role":
        return [t("server.channelSettings.role"), (str && roleName(str)) || t("server.auditLog.aDeletedRole")];
      case "roles":
        return [t("server.members.roles"), Array.isArray(v) && v.length ? v.map((id) => roleName(String(id)) ?? t("server.auditLog.aDeletedRole")).join(", ") : none()];
      case "nick":
        return [t("modals.serverProfile.nickname"), str || none()];
      case "timeout_until":
        return [t("server.members.timedOut"), num && num > 0 ? formatFull(num) : none()];
      case "mute":
        return [t("voice.voice.mute"), onOff(v)];
      case "deaf":
        return [t("shell.user.deafen"), onOff(v)];
      case "channel_id":
        // the channel an entry is about is its own target; a voice move names where to
        if (v === e.target_id) return null;
        return [t("server.invites.channel"), (str && channelName(str)) || t("server.auditLog.disconnected")];
      case "parent_id":
        // a thread's parent is the channel it was started in
        if (e.action.startsWith("thread_")) return [t("server.invites.channel"), (str && channelName(str)) || t("server.auditLog.aDeletedChannel")];
        return [t("server.auditLog.category"), (str && channelName(str)) || none()];
      case "code":
        return [t("server.invites.code"), str ?? ""];
      case "max_age":
        return [t("modals.groupInvite.expireAfter"), num ? formatSpan(num * 1000) : t("server.invites.never")];
      case "max_uses":
        return [t("modals.groupInvite.maxNumberOfUses"), num ? String(num) : t("modals.invite.noLimit")];
      case "delete_message_days":
        return [t("server.memberAction.deleteMessageHistory"), num ? formatSpan(num * 86_400_000) : none()];
      case "icon":
        return [e.target_type === "role" ? t("roles.icon.label") : t("server.serverOverview.serverIcon"), v ? t("server.auditLog.changed") : t("server.auditLog.removed")];
      case "icon_emoji":
        return [t("roles.icon.label"), str ? (str.startsWith("custom:") ? t("server.auditLog.changed") : str) : t("server.auditLog.removed")];
      case "color2":
        return [t("roles.style.second"), num ? hex(num) : none()];
      case "color3":
        return [t("roles.style.third"), num ? hex(num) : none()];
      case "glyph":
        return [t("server.channelIcon.title"), str ? (str.startsWith("custom:") ? t("server.auditLog.changed") : str) : t("server.channelIcon.default")];
      case "forum":
        return [t("server.auditLog.forumSettings"), t("server.auditLog.changed")];
      case "url":
        return [t("channelLink.address"), str || none()];
      case "applied_tags":
        return [t("forum.settings.tags"), Array.isArray(v) ? t("server.auditLog.itemsCount", { count: v.length }) : none()];
      case "enabled":
        return [t("server.auditLog.status"), onOff(v)];
      case "discoverable":
        return [t("discovery.settings.list"), onOff(v)];
      case "discovery_category":
        return [t("discovery.settings.category"), str ? t(`discovery.cat.${str}`) : t("discovery.settings.noCategory")];
      case "discovery_tags":
        return [t("discovery.settings.tags"), Array.isArray(v) && v.length ? v.join(", ") : none()];
      case "discovery_language":
        return [t("discovery.settings.language"), (str && LANGUAGES.find((l) => l.tag === str)?.endonym) || str || t("discovery.anyLanguage")];
      case "verify_account_minutes":
        return [t("safety.accountAge"), num ? formatSpan(num * 60_000) : t("safety.noWait")];
      case "verify_member_minutes":
        return [t("safety.memberAge"), num ? formatSpan(num * 60_000) : t("safety.noWait")];
      case "bio":
        return [t("settings.profile.aboutMe"), str ? t("server.auditLog.changed") : t("server.auditLog.removed")];
      case "pronouns":
        return [t("settings.profile.pronouns"), str || none()];
      case "server_avatar":
        return [t("serverProfile.picture"), t("server.auditLog.removed")];
      case "server_banner":
        return [t("serverProfile.banner"), t("server.auditLog.removed")];
      case "quarantined":
        return [t("automod.action.held"), onOff(v)];
      case "banner":
        return [t("server.serverOverview.serverBanner"), v ? t("server.auditLog.changed") : t("server.auditLog.removed")];
      case "avatar":
        return [t("integrations.image"), v ? t("server.auditLog.changed") : t("server.auditLog.removed")];
      case "rule":
        return [t("automod.log.rule"), str ?? ""];
      case "kind":
        return [t("automod.log.kind"), str ? t(`automod.kind.${str}`) : ""];
      case "actions":
        return [t("server.auditLog.whatItDid"), Array.isArray(v) ? v.map((a) => t(`automod.action.${a === "block" ? "blocked" : a === "timeout" ? "timedOut" : a === "quarantine" ? "held" : "alerted"}`)).join(", ") : ""];
      case "matched":
        return [t("automod.log.matched"), str ?? ""];
      case "mentions":
        return [t("automod.log.mentions"), String(num ?? 0)];
      case "count":
        // a published post went to this many channels that follow this one
        if (e.action === "message_publish") return [t("server.auditLog.sentTo"), t("server.auditLog.followingChannels", { count: num ?? 0 })];
        return [t("server.auditLog.count"), String(num ?? 0)];
      case "days":
        return [t("members.prune.days"), num ? formatSpan(num * 86_400_000) : ""];
      case "invites_paused_until":
        return [t("server.invites.pausedHead"), num ? formatFull(num) : none()];
      case "archived":
        return [t("threads.chip.closed"), onOff(v)];
      case "locked":
        return [t("threads.settings.locked"), onOff(v)];
      case "auto_archive":
      case "default_auto_archive":
        return [t("threads.create.closeAfter"), num ? formatSpan(num * 60_000) : none()];
      case "source_server":
        return [t("follows.log.server"), str ?? ""];
      case "source_channel":
        return [t("follows.log.channel"), str ?? ""];
      case "author_id":
        return [t("server.auditLog.author"), (str && userName(str)) || str || ""];
      case "message_id":
      case "follow_id":
      case "bulk":
      case "raid":
      case "seconds":
      case "messages":
      case "title":
      case "target_type":
      case "current_name":
        return null;
      default:
        return [k, Array.isArray(v) ? t("server.auditLog.itemsCount", { count: v.length }) : str ?? JSON.stringify(v)];
    }
  };
  const changesText = (e: AuditEntry): string => {
    if (!e.changes || typeof e.changes !== "object") return "";
    // the reorders record the whole new order, which says nothing read as text
    if (Array.isArray(e.changes)) return t("server.auditLog.itemsCount", { count: e.changes.length });
    const obj = e.changes as Record<string, unknown>;
    const parts: string[] = [];
    for (const [k, v] of Object.entries(obj)) {
      const shown = field(k, v, e, obj);
      if (shown) parts.push(`${shown[0]}: ${shown[1]}`);
    }
    return parts.join(" · ");
  };
  const changesNodes = (e: AuditEntry, limit: number) => {
    if (!e.changes || typeof e.changes !== "object" || Array.isArray(e.changes)) return changesText(e).slice(0, limit);
    const obj = e.changes as Record<string, unknown>;
    let left = limit;
    let first = true;
    return Object.entries(obj).map(([key, value]) => {
      const shown = field(key, value, e, obj);
      if (!shown || left <= 0) return null;
      const prefix = `${first ? "" : " · "}${shown[0]}: `;
      first = false;
      const head = prefix.slice(0, left);
      left -= head.length;
      const text = shown[1].slice(0, left);
      left -= text.length;
      const id = typeof value === "string" && (
        key === "author_id" || ((key === "target_id" || key === "target") && (obj["target_type"] === "member" || (!roleName(value) && !!userName(value))))
      ) ? value : null;
      return <span key={key}>{head}{id ? <PersonName userId={id} serverId={serverId}>{text}</PersonName> : text}</span>;
    });
  };
  // A long bulk change is cut, with a way to read the rest: the cut used to
  // drop its tail with no sign there was one.
  const LONG = 300;
  const changesView = (e: AuditEntry) => {
    const text = changesText(e);
    if (!text) return null;
    const open = expanded.has(e.id);
    const long = text.length > LONG;
    return (
      <div className={s.auditChanges}>
        {changesNodes(e, long && !open ? LONG - 1 : text.length)}{long && !open ? "…" : null}
        {long ? (
          <button type="button" className={s.auditMore} aria-expanded={open} onClick={() => setExpanded((cur) => { const next = new Set(cur); if (open) next.delete(e.id); else next.add(e.id); return next; })}>
            {open ? t("server.auditLog.showLess") : t("server.auditLog.showAll")}
          </button>
        ) : null}
      </div>
    );
  };
  // who has done things here, as far as this log has been read, and AutoMod
  const [actors, setActors] = useState<string[]>([]);
  useEffect(() => {
    if (!entries) return;
    setActors((cur) => [...new Set([...cur, ...entries.map((e) => e.actor_id)])]);
  }, [entries]);
  const allVerbs = verbs();
  const actions = Object.keys(allVerbs).sort((a, b) => allVerbs[a]!.localeCompare(allVerbs[b]!));
  const shown = entries ?? [];
  const filtering = !!actor || !!action;

  return (
    <>
      <h2 className={st.title}>{t("server.auditLog.auditLog")}</h2>
      <p className={st.hint}>{t("server.auditLog.everyModerationAndConfigurationChangeIn")}</p>
      <div style={{ height: 16 }} />
      {(entries && entries.length > 0) || filtering ? (
        <div className={s.auditFilters}>
          <SelectBox
            placeholder={t("server.auditLog.filterByMember")}
            ariaLabel={`${t("server.auditLog.filterByMember")}: ${actor ? actorName(actor) : t("server.auditLog.anyone")}`}
            testId="audit-actor"
            picked={[actor]}
            labelOf={(id) => id ? actorName(id) : t("server.auditLog.anyone")}
            renderLabelOf={(id) => !id ? t("server.auditLog.anyone") : id === AUTOMOD ? actorName(id) : <PersonName userId={id} serverId={serverId}>{actorName(id)}</PersonName>}
            choicesFor={(query) => [
              { value: "", label: t("server.auditLog.anyone") },
              { value: AUTOMOD, label: t("automod.name") },
              ...actors.filter((id) => id !== AUTOMOD).map((id) => ({ value: id, label: actorName(id), labelNode: <PersonName userId={id} serverId={serverId}>{actorName(id)}</PersonName> })),
            ].filter((choice) => !query || choice.label.toLocaleLowerCase().includes(query.toLocaleLowerCase()))}
            searchable
            onCommit={(values) => setActor(values[0] ?? "")}
          />
          <Select value={action} onChange={(e) => setAction(e.target.value)} aria-label={t("server.auditLog.filterByAction")} data-testid="audit-action">
            <option value="">{t("server.auditLog.anyAction")}</option>
            {actions.map((a) => <option key={a} value={a}>{allVerbs[a]}</option>)}
          </Select>
        </div>
      ) : null}
      {entries === null && !failed ? <Spinner label={t("server.auditLog.loadingTheAuditLog")} /> : null}
      {entries && entries.length === 0 && !failed ? <p className={st.hint}>{t("server.auditLog.nothingHasHappenedYet")}</p> : null}
      {entries && entries.length > 0 && shown.length === 0 ? <p className={st.hint}>{t("server.auditLog.nothingMatches")}</p> : null}
      {shown.map((e) => (
        <div key={e.id} className={s.auditRow} data-action={e.action}>
          {e.actor_id === AUTOMOD ? (
            <span className={s.auditAutomod} aria-hidden="true"><ShieldCheck size={18} /></span>
          ) : (
            <Avatar id={e.actor_id} avatarId={users[e.actor_id]?.avatar_id ?? null} name={displayName(users[e.actor_id])} size={32} />
          )}
          <div className={s.auditText}>
            <b>{e.actor_id === AUTOMOD ? actorName(e.actor_id) : <PersonName userId={e.actor_id} serverId={serverId}>{actorName(e.actor_id)}</PersonName>}</b> {allVerbs[e.action] ?? e.action} {targetName(e) ? <b>{e.target_type === "user" ? <PersonName userId={e.target_id} serverId={serverId}>{targetName(e)}</PersonName> : targetName(e)}</b> : null}
            <div className={s.auditMeta}>{formatFull(e.created_at)}{e.reason ? ` · ${t("server.auditLog.reason", { reason: e.reason })}` : ""}</div>
            {changesView(e)}
          </div>
        </div>
      ))}
      {failed ? (
        <LoadError
          message={entries && entries.length > 0 ? t("server.auditLog.theRestOfTheLogCould") : t("server.auditLog.theAuditLogCouldNotBe")}
          busy={busy}
          onRetry={() => void load(entries && entries.length > 0 ? entries[entries.length - 1]?.id : undefined)}
        />
      ) : null}
      {entries && entries.length > 0 && !done && !failed ? (
        <div className={s.loadMore}>
          <Button variant="secondary" size="sm" loading={busy} onClick={() => void load(entries[entries.length - 1]?.id)}>{t("server.auditLog.loadMore")}</Button>
        </div>
      ) : null}
    </>
  );
}
