import { Link2, Link2Off, Lock, Plus, Trash2, X } from "lucide-react";
import { Suspense, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { servers as serversApi } from "@/api/servers";
import type { Channel, Overwrite } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { Field, TextInput } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import { memberName } from "@/lib/names";
import { P, has, isPrivateChannel, parsePerm, permString, canManageRole, canActOn, sameOverwrites } from "@/lib/perms";
import { useChannels } from "@/stores/channels";
import { useKeys } from "@/stores/keys";
import { canI, rolesByPosition, useServers, channelPerms, memberHighest } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import st from "@/components/settings/Settings.module.css";
import { OverwriteGrid } from "./PermissionGrid";
import { ChannelIconPicker, PICTURE } from "./ChannelIconPicker";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { SaveBar, SettingsLayer, type NavGroup } from "./SettingsLayer";
import { Spinner } from "@/components/common/Spinner";
import { lazyPage } from "@/lib/lazyChunk";
import s from "./ServerSettings.module.css";

import { t as tr_ } from "@/i18n";
import { formatShortDuration } from "@/lib/time";
import { unlessUnsaved } from "@/stores/unsaved";
import { isolate } from "./bidi";
import { slug, typedChannelName } from "@/components/modals/CreateChannelModal";
import { FormattedText } from "@/components/common/FormattedText";
import { AUTO_ARCHIVE, autoArchiveLabel, isForum } from "@/lib/threads";
import { linkDestination } from "@/lib/linkChannel";
const SLOWMODE = [0, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600];
const IntegrationsPage = lazyPage(() => import("./pages/IntegrationsPage"));

function slowLabel(v: number): string {
  return v === 0 ? tr_("server.channelSettings.slowmodeOff") : formatShortDuration(v);
}

export default function ChannelSettingsModal({ onClose, channelId, page: initial }: { onClose: () => void; channelId: string; page?: string | undefined }) {
  const channel = useChannels((c) => c.byId[channelId]);
  const [page, setPage] = useState(initial ?? "overview");
  const nav = useNavigate();
  useEffect(() => {
    if (!channel) onClose();
  }, [channel, onClose]);
  if (!channel || !channel.server_id) return null;
  const isCat = channel.type === "category";
  const isThreadCh = channel.type === "thread" || channel.type === "private_thread";
  // a text channel's webhooks can be managed from its own settings too
  const hooks = (channel.type === "text" || channel.type === "announcement") && canI(useServers.getState().byId[channel.server_id], P.MANAGE_WEBHOOKS, channel.id);
  const groups: NavGroup[] = [
    {
      head: isolate((isCat || isThreadCh || channel.type === "link" ? "" : "#") + channel.name),
      items: [
        { id: "overview", label: tr_("server.channelSettings.overview") },
        // a thread answers to its channel's permissions
        ...(isThreadCh ? [] : [{ id: "permissions", label: tr_("server.channelSettings.permissions") }]),
        ...(hooks ? [{ id: "integrations", label: tr_("integrations.title") }] : []),
      ],
    },
    { head: "", items: [{ id: "delete", label: isCat ? tr_("server.channelSettings.deleteCategory") : isThreadCh ? tr_("threads.bar.delete") : tr_("server.channelSettings.deleteChannel"), danger: true }] },
  ];
  return (
    <SettingsLayer label={tr_("server.channelSettings.channelSettings")} groups={groups} page={page} setPage={setPage} onClose={onClose} startOnList={!initial}>
      {page === "permissions" ? <PermissionsPage channel={channel} serverId={channel.server_id} /> : page === "integrations" && hooks ? (
        <Suspense fallback={<div style={{ display: "flex", justifyContent: "center", padding: 40 }}><Spinner label={tr_("chat.pins.loading")} /></div>}>
          <IntegrationsPage serverId={channel.server_id} channelId={channel.id} />
        </Suspense>
      ) : page === "delete" ? <DeletePage channel={channel} onDone={() => { onClose(); nav(`/channels/${channel.server_id}`); }} /> : <OverviewPage channel={channel} />}
    </SettingsLayer>
  );
}

/** What this reader typed, and only that: everything else is read from the channel. */
interface ChannelEdits {
  name?: string;
  topic?: string;
  slow?: number;
  nsfw?: boolean;
  limit?: number;
  glyph?: string;
  announce?: boolean;
  defaultArchive?: number;
  archive?: number;
  locked?: boolean;
  tags?: TagEdit[];
  layout?: "list" | "gallery";
  sort?: "activity" | "created";
  requireTag?: boolean;
  hideDownloads?: boolean;
  applied?: string[];
  url?: string;
}

/** A forum tag as it is being edited: one not saved yet has no id. */
interface TagEdit {
  id?: string;
  name: string;
  emoji: string;
  moderated: boolean;
}

const MAX_TAGS = 20;

function sameTags(a: TagEdit[], b: TagEdit[]): boolean {
  return a.length === b.length && a.every((x, i) => x.id === b[i]!.id && x.name === b[i]!.name && x.emoji === b[i]!.emoji && x.moderated === b[i]!.moderated);
}

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

function OverviewPage({ channel }: { channel: Channel }) {
  const upsert = useChannels((c) => c.upsert);
  const toast = useUI((u) => u.toast);
  const server = useServers((x) => (channel.server_id ? x.byId[channel.server_id] : undefined));
  // Seeding the fields once would leave the form holding values from before
  // somebody else's change, and saving would send those stale values back over
  // their edit. What was not typed here follows the channel instead.
  const [edits, setEdits] = useState<ChannelEdits>({});
  const [busy, setBusy] = useState(false);
  const thread = channel.type === "thread" || channel.type === "private_thread";
  // a media channel is a forum of pictures and videos, always shown as a gallery
  const forum = isForum(channel);
  const media = channel.type === "media";
  const link = channel.type === "link";
  const textLike = channel.type === "text" || channel.type === "announcement";
  // names are written as text channels' are: lower case, dashes for spaces
  const slugged = textLike || forum;
  const parent = useChannels((c) => (thread && channel.parent_id ? c.byId[channel.parent_id] : undefined));
  const postTags = isForum(parent) ? (parent?.forum?.tags ?? []) : [];
  const savedTags: TagEdit[] = useMemo(() => (channel.forum?.tags ?? []).map((tg) => ({ id: tg.id, name: tg.name, emoji: tg.emoji, moderated: tg.moderated })), [channel.forum?.tags]);
  const name = edits.name ?? channel.name;
  const topic = edits.topic ?? channel.topic;
  const slow = edits.slow ?? channel.slowmode_seconds;
  const nsfw = edits.nsfw ?? channel.nsfw;
  const limit = edits.limit ?? channel.user_limit;
  // an uploaded picture is edited as PICTURE: picking anything else and saving puts it away
  const savedGlyph = channel.icon_id ? PICTURE : (channel.glyph ?? "");
  const glyph = edits.glyph ?? savedGlyph;
  const announce = edits.announce ?? channel.type === "announcement";
  const defaultArchive = edits.defaultArchive ?? channel.default_auto_archive ?? 4320;
  const archive = edits.archive ?? channel.thread?.auto_archive ?? 4320;
  const locked = edits.locked ?? !!channel.thread?.locked;
  const tags = edits.tags ?? savedTags;
  const layout = edits.layout ?? channel.forum?.layout ?? "list";
  const sort = edits.sort ?? channel.forum?.sort ?? "activity";
  const requireTag = edits.requireTag ?? !!channel.forum?.require_tag;
  const hideDownloads = edits.hideDownloads ?? !!channel.forum?.hide_downloads;
  const url = edits.url ?? channel.url ?? "";
  const applied = edits.applied ?? channel.thread?.applied_tags ?? [];
  const set = (e: ChannelEdits) => setEdits((cur) => ({ ...cur, ...e }));
  const reset = () => setEdits({});
  const mayManageThreads = canI(server, P.MANAGE_THREADS, channel.id);
  const forumDirty = forum && (!sameTags(tags, savedTags) || layout !== (channel.forum?.layout ?? "list") || sort !== (channel.forum?.sort ?? "activity") || requireTag !== !!channel.forum?.require_tag || (media && hideDownloads !== !!channel.forum?.hide_downloads));
  const dirty =
    name !== channel.name ||
    topic !== channel.topic ||
    slow !== channel.slowmode_seconds ||
    nsfw !== channel.nsfw ||
    limit !== channel.user_limit ||
    glyph !== savedGlyph ||
    (textLike && announce !== (channel.type === "announcement")) ||
    ((textLike || forum) && defaultArchive !== (channel.default_auto_archive ?? 4320)) ||
    (thread && (archive !== (channel.thread?.auto_archive ?? 4320) || locked !== !!channel.thread?.locked || !sameList(applied, channel.thread?.applied_tags ?? []))) ||
    (link && url !== (channel.url ?? "")) ||
    forumDirty;
  const save = async () => {
    const dest = link && url !== (channel.url ?? "") ? linkDestination(url) : null;
    if (link && url !== (channel.url ?? "") && !dest) {
      toast(tr_("channelLink.badAddress"), "error");
      return;
    }
    setBusy(true);
    try {
      const body: Parameters<typeof serversApi.patchChannel>[1] = {};
      if (dest) body.url = dest.href;
      if (name !== channel.name) body.name = slugged ? slug(name) : name.trim();
      if (topic !== channel.topic) body.topic = topic;
      if (slow !== channel.slowmode_seconds) body.slowmode_seconds = slow;
      if (nsfw !== channel.nsfw) body.nsfw = nsfw;
      if (limit !== channel.user_limit) body.user_limit = limit;
      if (glyph !== savedGlyph && glyph !== PICTURE) body.glyph = glyph;
      if (textLike && announce !== (channel.type === "announcement")) body.type = announce ? "announcement" : "text";
      if ((textLike || forum) && defaultArchive !== (channel.default_auto_archive ?? 4320)) body.default_auto_archive = defaultArchive;
      if (thread) {
        if (archive !== (channel.thread?.auto_archive ?? 4320)) body.auto_archive = archive;
        if (locked !== !!channel.thread?.locked) body.locked = locked;
        if (!sameList(applied, channel.thread?.applied_tags ?? [])) body.applied_tags = applied;
      }
      if (forumDirty) {
        body.forum = { tags: tags.filter((tg) => tg.name.trim()).map((tg) => ({ ...(tg.id ? { id: tg.id } : {}), name: tg.name.trim(), emoji: tg.emoji, moderated: tg.moderated })), layout: media ? "gallery" : layout, sort, require_tag: requireTag, ...(media ? { hide_downloads: hideDownloads } : {}) };
      }
      const ch = await serversApi.patchChannel(channel.id, body);
      upsert(ch);
      // the server tidies names and topics (text channel names become lowercase-with-dashes), so show what it kept
      setEdits({});
      toast(tr_("server.channelSettings.channelSaved"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("server.channelSettings.couldNotSaveTheChannel"), "error");
    } finally {
      setBusy(false);
    }
  };
  const nameLabel = channel.type === "category" ? tr_("server.channelSettings.categoryName") : thread ? tr_("threads.settings.name") : tr_("server.channelSettings.channelName");
  return (
    <>
      <h2 className={st.title}>{tr_("server.channelSettings.overview")}</h2>
      <div className={st.form}>
        <Field label={nameLabel} required>
          <div className={s.iconInput}>
            {/* what the channel list will show, as it is being picked below */}
            <span className={s.nameGlyph}><ChannelGlyph channel={{ type: textLike ? (announce ? "announcement" : "text") : channel.type, server_id: channel.server_id, glyph: glyph === PICTURE ? "" : glyph, icon_id: glyph === PICTURE ? channel.icon_id : null }} size={16} /></span>
            <TextInput value={name} onChange={(e) => set({ name: slugged ? typedChannelName(e.target.value) : e.target.value })} maxLength={100} data-testid="channel-settings-name" />
          </div>
        </Field>
        {link ? (
          <Field label={tr_("channelLink.address")} required hint={tr_("channelLink.addressHint")}>
            <TextInput type="url" inputMode="url" value={url} onChange={(e) => set({ url: e.target.value })} placeholder="https://" maxLength={1024} autoCapitalize="none" spellCheck={false} dir="ltr" data-testid="channel-settings-url" />
          </Field>
        ) : null}
        {!thread ? (
          <Field as="div" label={channel.type === "category" ? tr_("server.channelIcon.categoryTitle") : tr_("server.channelIcon.title")} hint={channel.type === "category" ? tr_("server.channelIcon.categoryHint") : tr_("server.channelIcon.hint")}>
            <ChannelIconPicker channel={channel} value={glyph} onChange={(v) => set({ glyph: v })} onUploaded={(ch) => { upsert(ch); setEdits(({ glyph: _, ...rest }) => rest); }} label={channel.type === "category" ? tr_("server.channelIcon.categoryTitle") : tr_("server.channelIcon.title")} />
          </Field>
        ) : null}
        {textLike || forum ? (
          <Field as="div" label={forum ? tr_("forum.settings.guidelines") : tr_("server.channelSettings.channelTopic")} hint={forum ? tr_("forum.settings.guidelinesHint") : undefined}>
            <FormattedText value={topic} onChange={(v) => set({ topic: v })} max={1024} placeholder={forum ? tr_("forum.settings.guidelinesPlaceholder") : tr_("server.channelSettings.letEveryoneKnowHowToUse")} title={forum ? tr_("forum.settings.guidelines") : tr_("server.channelSettings.channelTopic")} serverId={channel.server_id ?? undefined} testId="channel-topic" />
          </Field>
        ) : null}
        {textLike ? (
          <Toggle checked={announce} onChange={(v) => set({ announce: v })} label={tr_("follows.settings.announcement")} description={tr_("follows.settings.announcementDesc")} />
        ) : null}
        {forum ? <ForumTagsEditor tags={tags} onChange={(v) => set({ tags: v })} /> : null}
        {forum ? (
          <Toggle checked={requireTag} onChange={(v) => set({ requireTag: v })} label={tr_("forum.settings.requireTag")} description={tr_("forum.settings.requireTagDesc")} />
        ) : null}
        {media ? (
          <Toggle checked={hideDownloads} onChange={(v) => set({ hideDownloads: v })} label={tr_("media.settings.hideDownloads")} description={tr_("media.settings.hideDownloadsDesc")} />
        ) : null}
        {forum ? (
          <div className={s.twoCol}>
            {!media ? (
              <Field label={tr_("forum.settings.layout")}>
                <Select value={layout} onChange={(e) => set({ layout: e.target.value as "list" | "gallery" })}>
                  <option value="list">{tr_("forum.layout.list")}</option>
                  <option value="gallery">{tr_("forum.layout.gallery")}</option>
                </Select>
              </Field>
            ) : null}
            <Field label={tr_("forum.settings.sort")}>
              <Select value={sort} onChange={(e) => set({ sort: e.target.value as "activity" | "created" })}>
                <option value="activity">{tr_("forum.sort.activity")}</option>
                <option value="created">{tr_("forum.sort.created")}</option>
              </Select>
            </Field>
          </div>
        ) : null}
        {thread && postTags.length ? (
          <Field as="div" label={tr_("forum.post.tags")}>
            <div className={s.tagPick}>
              {postTags.map((tg) => {
                const on = applied.includes(tg.id);
                const allowed = !tg.moderated || mayManageThreads;
                return (
                  <button key={tg.id} type="button" className={s.tagChip} aria-pressed={on} disabled={!allowed} onClick={() => set({ applied: on ? applied.filter((x) => x !== tg.id) : [...applied, tg.id].slice(0, 5) })}>
                    {tg.emoji ? <span aria-hidden="true">{tg.emoji}</span> : null}
                    <span dir="auto">{tg.name}</span>
                  </button>
                );
              })}
            </div>
          </Field>
        ) : null}
        {textLike || forum || thread ? (
          <Field label={forum ? tr_("forum.settings.slowmode") : tr_("server.channelSettings.slowmode")} hint={forum ? tr_("forum.settings.slowmodeHint") : tr_("server.channelSettings.membersWillBeRestrictedToSending")}>
            <Select value={slow} onChange={(e) => set({ slow: Number(e.target.value) })}>
              {SLOWMODE.map((v) => <option key={v} value={v}>{slowLabel(v)}</option>)}
            </Select>
          </Field>
        ) : null}
        {textLike || forum ? (
          <Field label={tr_("threads.settings.defaultArchive")} hint={tr_("threads.settings.defaultArchiveHint")}>
            <Select value={defaultArchive} onChange={(e) => set({ defaultArchive: Number(e.target.value) })}>
              {AUTO_ARCHIVE.map((m) => <option key={m} value={m}>{autoArchiveLabel(m)}</option>)}
            </Select>
          </Field>
        ) : null}
        {thread ? (
          <Field label={tr_("threads.create.closeAfter")} hint={tr_("threads.create.closeHint")}>
            <Select value={archive} onChange={(e) => set({ archive: Number(e.target.value) })}>
              {AUTO_ARCHIVE.map((m) => <option key={m} value={m}>{autoArchiveLabel(m)}</option>)}
            </Select>
          </Field>
        ) : null}
        {thread && mayManageThreads ? (
          <Toggle checked={locked} onChange={(v) => set({ locked: v })} label={tr_("threads.settings.locked")} description={tr_("threads.settings.lockedDesc")} />
        ) : null}
        {textLike || forum ? <Toggle checked={nsfw} onChange={(v) => set({ nsfw: v })} label={tr_("server.channelSettings.ageRestrictedChannel")} description={nsfw ? tr_("nsfw.channelHint") : undefined} /> : null}
        {channel.type === "voice" ? (
          <Field label={tr_("server.channelSettings.userLimitValue", { limit: limit === 0 ? tr_("server.channelSettings.noLimit") : tr_("server.channelSettings.usersCount", { count: limit }) })} hint={tr_("server.channelSettings.limitHowManyPeopleCanJoin")}>
            <input type="range" className={st.slider} min={0} max={99} value={limit} onChange={(e) => set({ limit: Number(e.target.value) })} />
          </Field>
        ) : null}
      </div>
      <SaveBar dirty={dirty} busy={busy} onReset={reset} onSave={() => void save()} />
    </>
  );
}

/**
 * A forum's tags: what posts can be filed under, each with an emoji if it
 * likes, and whether only people who manage threads may put it on a post.
 */
function ForumTagsEditor({ tags, onChange }: { tags: TagEdit[]; onChange: (v: TagEdit[]) => void }) {
  const update = (i: number, patch: Partial<TagEdit>) => onChange(tags.map((tg, j) => (j === i ? { ...tg, ...patch } : tg)));
  return (
    <Field as="div" label={tr_("forum.settings.tags")} hint={tr_("forum.settings.tagsHint", { count: MAX_TAGS })}>
      <div className={s.tagList}>
        {tags.map((tg, i) => (
          <div key={tg.id ?? `new${i}`} className={s.tagRow}>
            <TextInput className={s.tagEmoji} value={tg.emoji} onChange={(e) => update(i, { emoji: [...e.target.value].slice(-2).join("").trim() })} placeholder="🙂" aria-label={tr_("forum.settings.tagEmoji")} maxLength={8} />
            <TextInput value={tg.name} onChange={(e) => update(i, { name: e.target.value.slice(0, 20) })} placeholder={tr_("forum.settings.tagName")} aria-label={tr_("forum.settings.tagName")} maxLength={20} />
            <label className={s.tagMod} title={tr_("forum.settings.moderatedDesc")}>
              <input type="checkbox" checked={tg.moderated} onChange={(e) => update(i, { moderated: e.target.checked })} />
              <span>{tr_("forum.settings.moderated")}</span>
            </label>
            <button type="button" className={s.tagRemove} aria-label={tr_("forum.settings.removeTag", { name: tg.name || "…" })} onClick={() => onChange(tags.filter((_, j) => j !== i))}><Trash2 size={16} /></button>
          </div>
        ))}
        {tags.length < MAX_TAGS ? (
          <Button size="sm" variant="secondary" onClick={() => onChange([...tags, { name: "", emoji: "", moderated: false }])}>
            <Plus size={16} aria-hidden="true" /> {tr_("forum.settings.addTag")}
          </Button>
        ) : null}
      </div>
    </Field>
  );
}

type Target = { id: string; type: "role" | "member"; name: string; color?: string | undefined };

function PermissionsPage({ channel, serverId }: { channel: Channel; serverId: string }) {
  const server = useServers((x) => x.byId[serverId]);
  const users = useUsers((u) => u.byId);
  const upsert = useChannels((c) => c.upsert);
  const toast = useUI((u) => u.toast);
  const overwrites = useMemo(() => channel.overwrites ?? [], [channel.overwrites]);
  const [selected, setSelected] = useState<string>(serverId);
  const [adding, setAdding] = useState<"role" | "member" | null>(null);
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  // a channel's permissions are Manage Roles there, as the server holds them
  const editable = canI(server, P.MANAGE_ROLES, channel.id);
  const parent = useChannels((c) => (channel.parent_id ? c.byId[channel.parent_id] : undefined));
  const synced = !!parent && sameOverwrites(channel.overwrites, parent.overwrites);
  // what the editor holds here, which is all they may set or clear; an
  // administrator holds everything
  const mine = server ? channelPerms(server, channel.id, server.me.user_id) : 0n;
  const grantable = (mine & P.ADMINISTRATOR) !== 0n ? undefined : mine;
  // a selection whose overwrite is gone (removed elsewhere, or never added) falls back to @everyone, like the list does
  const current: Overwrite | undefined = overwrites.find((o) => o.target_id === selected) ?? overwrites.find((o) => o.target_id === serverId);
  const [allow, setAllow] = useState<bigint>(current ? parsePerm(current.allow) : 0n);
  const [deny, setDeny] = useState<bigint>(current ? parsePerm(current.deny) : 0n);
  useEffect(() => {
    const list = channel.overwrites ?? [];
    const cur = list.find((o) => o.target_id === selected) ?? list.find((o) => o.target_id === serverId);
    setAllow(cur ? parsePerm(cur.allow) : 0n);
    setDeny(cur ? parsePerm(cur.deny) : 0n);
  }, [selected, serverId, channel.overwrites]);

  if (!server) return null;
  const targets: Target[] = [];
  const roleOf = (id: string) => server.roles[id];
  targets.push({ id: serverId, type: "role", name: "@everyone" });
  for (const ow of overwrites) {
    if (ow.target_id === serverId) continue;
    if (ow.target_type === "role") {
      const r = roleOf(ow.target_id);
      if (r) targets.push({ id: r.id, type: "role", name: r.name, color: r.color ? `#${r.color.toString(16).padStart(6, "0")}` : undefined });
    } else {
      const m = server.members[ow.target_id];
      targets.push({ id: ow.target_id, type: "member", name: memberName(m?.nickname, users[ow.target_id]) });
    }
  }
  const selectedTarget = targets.find((t) => t.id === selected) ?? targets[0]!;
  const dirty = allow !== (current ? parsePerm(current.allow) : 0n) || deny !== (current ? parsePerm(current.deny) : 0n);
  const isPrivate = isPrivateChannel(serverId, overwrites);
  const wantPrivate = selectedTarget.id === serverId ? has(deny, P.VIEW_CHANNEL) : isPrivate;

  const refresh = async () => upsert(await serversApi.channels(serverId));

  const save = async (a = allow, d = deny, targetId = selectedTarget.id, type = selectedTarget.type): Promise<boolean> => {
    setBusy(true);
    try {
      const res = await serversApi.putOverwrite(channel.id, targetId, { type, allow: permString(a & ~d), deny: permString(d) });
      await refresh();
      if (res.key_actions?.needs_own_key) {
        // the channel just became private: give it its own key so only its viewers ever hold one
        await useKeys.getState().ensureConvKey(channel.id);
        await refresh();
      }
      toast(tr_("server.channelSettings.permissionsSaved"), "success");
      return true;
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("server.channelSettings.couldNotSavePermissions"), "error");
      return false;
    } finally {
      setBusy(false);
    }
  };
  // Sync Now: the channel takes its category's overwrites
  const syncNow = async () => {
    setBusy(true);
    try {
      const res = await serversApi.syncPermissions(channel.id);
      await refresh();
      if (res.key_actions?.needs_own_key) {
        await useKeys.getState().ensureConvKey(channel.id);
        await refresh();
      }
      setSelected(serverId);
      toast(tr_("server.channelSettings.permissionsSynced"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("server.channelSettings.couldNotSavePermissions"), "error");
    } finally {
      setBusy(false);
    }
  };
  const remove = async (targetId: string) => {
    setBusy(true);
    try {
      await serversApi.deleteOverwrite(channel.id, targetId);
      await refresh();
      if (selected === targetId) setSelected(serverId);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("server.channelSettings.couldNotRemoveTheOverwrite"), "error");
    } finally {
      setBusy(false);
    }
  };
  // Adding or removing an entry re-reads every overwrite, which resets the
  // grid on screen: with unsaved changes in it, both ask first (see below).
  const add = async (targetId: string, type: "role" | "member") => {
    setAdding(null);
    setQuery("");
    // a private channel's new target starts with View Channel allowed, so adding someone actually lets them in
    const a = isPrivate ? P.VIEW_CHANNEL : 0n;
    if (await save(a, 0n, targetId, type)) setSelected(targetId);
  };

  // Offered only what the server would let this editor set: roles below
  // their highest, and members they outrank. The rest failed on the first
  // save with "role hierarchy".
  const meId = server.me.user_id;
  const { highest: myTop, isOwner: actorIsOwner } = memberHighest(server, meId);
  const candidateRoles = rolesByPosition(server).filter((r) => r.id !== serverId && !overwrites.some((o) => o.target_id === r.id) && canManageRole(myTop, actorIsOwner, r.position));
  const q = query.trim().toLowerCase();
  const candidateMembers = Object.values(server.members)
    .filter((m) => !overwrites.some((o) => o.target_id === m.user_id))
    .filter((m) => m.user_id !== meId || actorIsOwner)
    .filter((m) => canActOn(myTop, memberHighest(server, m.user_id).highest, actorIsOwner, server.server.owner_id === m.user_id))
    .map((m) => ({ id: m.user_id, name: memberName(m.nickname, users[m.user_id]), username: users[m.user_id]?.username ?? "" }))
    .filter((m) => !q || m.name.toLowerCase().includes(q) || m.username.includes(q))
    .sort((a, b) => a.name.localeCompare(b.name))
    .slice(0, 12);

  return (
    <>
      <h2 className={st.title}>{tr_("server.channelSettings.permissions")}</h2>
      <p className={st.hint}>{channel.type === "category" ? tr_("server.channelSettings.permsCategory") : tr_("server.channelSettings.permsChannel")} {isPrivate ? tr_("server.channelSettings.permsPrivate") : ""}</p>
      {parent && channel.type !== "category" ? (
        <div className={s.syncBar} data-synced={synced} data-testid="perm-sync">
          {synced ? <Link2 size={16} aria-hidden="true" /> : <Link2Off size={16} aria-hidden="true" />}
          <span className={s.syncText}>{synced ? tr_("server.channelSettings.syncedWith", { category: parent.name }) : tr_("server.channelSettings.notSyncedWith", { category: parent.name })}</span>
          {!synced && editable ? <button className={s.smallBtn} disabled={busy} onClick={unlessUnsaved(() => void syncNow())}>{tr_("server.channelSettings.syncNow")}</button> : null}
        </div>
      ) : null}
      <div className={s.permLayout}>
        <div className={s.permList}>
          <div className={s.permListHead}>
            <span>{tr_("server.channelSettings.rolesMembers")}</span>
            {editable ? (
              <span className={s.permAdd}>
                <button className={s.smallBtn} onClick={() => setAdding(adding === "role" ? null : "role")} title={tr_("server.channelSettings.addRole")}><Plus size={14} /> {tr_("server.channelSettings.role")}</button>
                <button className={s.smallBtn} onClick={() => setAdding(adding === "member" ? null : "member")} title={tr_("server.channelSettings.addMember")}><Plus size={14} /> {tr_("server.channelSettings.member")}</button>
              </span>
            ) : null}
          </div>
          {adding === "role" ? (
            <div className={s.picker}>
              {candidateRoles.length === 0 ? <div className={s.pickerEmpty}>{tr_("server.channelSettings.everyRoleAlreadyHasAnOverwrite")}</div> : null}
              {candidateRoles.map((r) => (
                <button key={r.id} className={s.pickerRow} onClick={unlessUnsaved(() => void add(r.id, "role"))}>
                  <span className={s.dot} style={{ background: r.color ? `#${r.color.toString(16).padStart(6, "0")}` : "var(--text-muted)" }} />
                  <span>{r.name}</span>
                </button>
              ))}
            </div>
          ) : null}
          {adding === "member" ? (
            <div className={s.picker}>
              <TextInput value={query} onChange={(e) => setQuery(e.target.value)} placeholder={tr_("server.channelSettings.searchMembers")} aria-label={tr_("server.channelSettings.searchMembers")} autoFocus />
              {candidateMembers.length === 0 ? <div className={s.pickerEmpty}>{q ? tr_("friends.nobodyHereMatches", { query: query.trim() }) : tr_("server.channelSettings.nobodyLeftToAdd")}</div> : null}
              {candidateMembers.map((m) => (
                <button key={m.id} className={s.pickerRow} onClick={unlessUnsaved(() => void add(m.id, "member"))}>
                  <Avatar id={m.id} avatarId={users[m.id]?.avatar_id ?? null} name={m.name} size={20} />
                  <span>{m.name}</span>
                  <span className={s.muted}><bdi>@{m.username}</bdi></span>
                </button>
              ))}
            </div>
          ) : null}
          {targets.map((t) => (
            <div key={t.id} className={s.permTarget} data-selected={t.id === selectedTarget.id}>
              <button className={s.permTargetBtn} onClick={t.id === selectedTarget.id ? undefined : unlessUnsaved(() => setSelected(t.id))} data-target={t.id}>
                {t.type === "role" ? <span className={s.dot} style={{ background: t.color ?? "var(--text-muted)" }} /> : <Avatar id={t.id} avatarId={users[t.id]?.avatar_id ?? null} name={t.name} size={20} />}
                <span className={s.permTargetName} dir="auto">{t.name}</span>
              </button>
              {editable && t.id !== serverId ? (
                <button className={s.permRemove} aria-label={tr_("server.channelSettings.removeOverwrite")} onClick={unlessUnsaved(() => void remove(t.id))} disabled={busy}><X size={14} /></button>
              ) : null}
            </div>
          ))}
        </div>
        <div className={s.permEditor}>
          {selectedTarget.id === serverId ? (
            <div className={s.privateRow}>
              <Toggle
                checked={wantPrivate}
                disabled={!editable}
                onChange={(on) => {
                  if (on) setDeny(deny | P.VIEW_CHANNEL), setAllow(allow & ~P.VIEW_CHANNEL);
                  else setDeny(deny & ~P.VIEW_CHANNEL);
                }}
                label={tr_("modals.createChannel.privateChannel")}
                description={tr_("server.channelSettings.onlySelectedMembersAndRolesCan")}
              />
              {wantPrivate ? <span className={s.lockNote}><Lock size={14} /> {tr_("server.channelSettings.addRolesOrMembersOnThe")}</span> : null}
            </div>
          ) : null}
          <OverwriteGrid allow={allow} deny={deny} editable={editable} grantable={grantable} channelType={channel.type} onChange={(a, d) => { setAllow(a); setDeny(d); }} />
          <SaveBar dirty={dirty} busy={busy} onReset={() => { setAllow(current ? parsePerm(current.allow) : 0n); setDeny(current ? parsePerm(current.deny) : 0n); }} onSave={() => void save()} />
        </div>
      </div>
    </>
  );
}

function DeletePage({ channel, onDone }: { channel: Channel; onDone: () => void }) {
  const remove = useChannels((c) => c.remove);
  const toast = useUI((u) => u.toast);
  const [busy, setBusy] = useState(false);
  const isCat = channel.type === "category";
  const go = async () => {
    setBusy(true);
    try {
      await serversApi.deleteChannel(channel.id);
      remove(channel.id);
      onDone();
    } catch (err) {
      toast(err instanceof ApiError ? err.message : tr_("chat.messageMenu.couldNotDelete"), "error");
      setBusy(false);
    }
  };
  return (
    <>
      <h2 className={st.title}>{isCat ? tr_("server.channelSettings.deleteCategory") : tr_("server.channelSettings.deleteChannel")}</h2>
      <p className={st.hint}>{isCat ? tr_("server.channelSettings.deleteCategoryHint", { name: channel.name }) : tr_("server.channelSettings.deleteChannelHint", { name: channel.name })}</p>
      <div style={{ marginTop: 24 }}>
        <Button variant="danger" loading={busy} onClick={() => void go()} data-testid="delete-channel"><Trash2 size={16} /> {isCat ? tr_("server.channelSettings.deleteCategory") : tr_("server.channelSettings.deleteChannel")}</Button>
      </div>
    </>
  );
}
