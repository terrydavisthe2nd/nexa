import { PersonName } from "@/components/common/PersonName";
import { ChevronDown, ChevronRight, Hash, Trash2, TriangleAlert, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { apps as appsApi, type CommandPermission, type IntegrationCommands, type ServerApp } from "@/api/apps";
import { AppIcon } from "@/components/apps/AppIcon";
import { ReadingGlass } from "@/components/apps/ReadingGlass";
import { SelectBox } from "@/components/apps/SelectBox";
import { entityChoices } from "@/components/apps/entityChoices";
import { HookTags } from "@/components/chat/webhook/HookHeader";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { LoadError } from "@/components/common/LoadError";
import { Spinner } from "@/components/common/Spinner";
import { dispatchHooks } from "@/gateway/dispatch";
import type { GatewayEvent } from "@/gateway/events";
import { t, Tr } from "@/i18n";
import { commandName } from "@/lib/apps";
import { describeError } from "@/lib/errors";
import { findMembers } from "@/lib/pickables";
import { formatShortDate } from "@/lib/time";
import { useChannels } from "@/stores/channels";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { displayName, useUsers } from "@/stores/users";
import st from "@/components/settings/Settings.module.css";
import s from "./Integrations.module.css";

/**
 * Server Settings, Integrations, Apps: the apps added to this server, for
 * whoever manages it. Each says who added it and when, what it can read, and
 * opens to set who may use its commands where, or to take it out.
 */
export function AppsSection({ serverId }: { serverId: string }) {
  const [list, setList] = useState<ServerApp[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const users = useUsers((u) => u.byId);
  const openModal = useUI((u) => u.openModal);
  const toast = useUI((u) => u.toast);
  const load = useCallback(() => setAttempt((n) => n + 1), []);
  useEffect(() => {
    let alive = true;
    setFailed(false);
    appsApi
      .inServer(serverId)
      .then((l) => alive && setList(l))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [serverId, attempt]);
  // added or taken out elsewhere: the list follows
  useEffect(() => {
    const onEvent = (e: GatewayEvent) => {
      if (e.t === "integrations_update" && e.d.server_id === serverId) load();
    };
    dispatchHooks.onUnhandled.push(onEvent);
    return () => {
      const i = dispatchHooks.onUnhandled.indexOf(onEvent);
      if (i >= 0) dispatchHooks.onUnhandled.splice(i, 1);
    };
  }, [serverId, load]);
  const remove = (app: ServerApp) =>
    openModal("confirm", {
      title: t("apps.server.removeNamed", { name: app.application.name }),
      body: t("apps.server.removeBody", { name: app.application.name }),
      confirmLabel: t("apps.server.remove"),
      danger: true,
      onConfirm: async () => {
        try {
          await appsApi.removeFromServer(serverId, app.application.id);
          setList((cur) => cur?.filter((x) => x.application.id !== app.application.id) ?? cur);
          toast(t("apps.server.removed", { name: app.application.name }), "success");
        } catch (err) {
          toast(describeError(err, t("apps.server.removeFailed")), "error");
        }
      },
    });
  return (
    <>
      <h3 className={st.h5} style={{ marginTop: 0 }}>{t("apps.server.title")}</h3>
      <p className={st.hint} style={{ marginTop: -6, marginBottom: 10 }}>{t("apps.server.intro")}</p>
      {list === null && !failed ? <Spinner label={t("apps.portal.loading")} /> : null}
      {failed ? <LoadError message={t("apps.server.couldNotLoad")} onRetry={load} /> : null}
      {list && list.length === 0 ? <div className={s.empty}><span>{t("apps.server.none")}</span></div> : null}
      <div className={s.list} style={{ paddingBottom: 16 }}>
        {list?.map((app) => {
          const expanded = open === app.application.id;
          const by = users[app.installed_by];
          return (
            <div key={app.application.id} className={s.card} data-open={expanded} data-testid="server-app">
              <button type="button" className={s.head} onClick={() => setOpen(expanded ? null : app.application.id)} aria-expanded={expanded}>
                <AppIcon app={app.application} size={40} />
                <span className={s.headText}>
                  <span className={s.name} style={{ display: "flex", alignItems: "center", gap: 6 }}>
                    <span dir="auto" style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis" }}>{app.application.name}</span>
                    <HookTags app />
                    {app.application.intents.message_content ? (
                      <span className={s.readsAll}>
                        <TriangleAlert size={11} aria-hidden="true" />
                        {t("apps.portal.readsEverything")}
                      </span>
                    ) : null}
                  </span>
                  <span className={s.meta}>{by ? <Tr id="apps.server.addedBy" values={{ name: <PersonName user={by}>{displayName(by)}</PersonName>, date: formatShortDate(app.installed_at) }} /> : t("apps.server.addedOn", { date: formatShortDate(app.installed_at) })}</span>
                </span>
                {expanded ? <ChevronDown size={18} aria-hidden="true" /> : <ChevronRight size={18} aria-hidden="true" />}
              </button>
              {expanded ? (
                <div className={s.appBody}>
                  {app.application.description ? <p className={s.appDesc} dir="auto">{app.application.description}</p> : null}
                  {app.bot_member ? <ReadingGlass name={app.application.name} everything={app.application.intents.message_content} compact /> : null}
                  <CommandPermissions serverId={serverId} app={app} />
                  <div className={s.actions}>
                    <Button size="sm" variant="danger" onClick={() => remove(app)} data-testid="server-app-remove">
                      <Trash2 size={15} aria-hidden="true" /> {t("apps.server.remove")}
                    </Button>
                  </div>
                </div>
              ) : null}
            </div>
          );
        })}
      </div>
    </>
  );
}

/** A channel of the server, which the pickers find the server's people, roles and channels through. */
function someChannel(serverId: string): string {
  return Object.values(useChannels.getState().byId).find((c) => c.server_id === serverId && c.type === "text")?.id ?? "";
}

/**
 * Who may use an app's commands in a server: for all of them, or for one,
 * roles and people allowed or not, and channels allowed or not. Without
 * entries, a command follows what the app set; entries for one command come
 * before those for all of them.
 */
function CommandPermissions({ serverId, app }: { serverId: string; app: ServerApp }) {
  const [data, setData] = useState<IntegrationCommands | null>(null);
  const [failed, setFailed] = useState(false);
  const [target, setTarget] = useState(app.application.id);
  const [edits, setEdits] = useState<Record<string, CommandPermission[]>>({});
  const [busy, setBusy] = useState(false);
  const [, rerender] = useState(0);
  const toast = useUI((u) => u.toast);
  const server = useServers((x) => x.byId[serverId]);
  const channels = useChannels((c) => c.byId);
  const users = useUsers((u) => u.byId);
  const via = useMemo(() => someChannel(serverId), [serverId]);
  useEffect(() => {
    let alive = true;
    appsApi
      .serverCommands(serverId, app.application.id)
      .then((d) => alive && setData(d))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [serverId, app.application.id]);
  if (failed) return <p className={st.hint}>{t("apps.server.commandsFailed")}</p>;
  if (!data) return <Spinner label={t("apps.portal.loading")} />;
  if (!data.commands.length) return <p className={st.hint}>{t("apps.server.noCommands", { app: app.application.name })}</p>;
  const entries = edits[target] ?? data.permissions[target] ?? [];
  const changed = edits[target] !== undefined;
  const set = (list: CommandPermission[]) => setEdits((cur) => ({ ...cur, [target]: list }));
  const add = (id: string, type: 1 | 2 | 3) => {
    if (entries.some((e) => e.id === id)) return;
    set([...entries, { id, type, permission: true }]);
  };
  const save = async () => {
    setBusy(true);
    try {
      const res = await appsApi.setCommandPermissions(serverId, app.application.id, target, entries);
      setData({ ...data, permissions: { ...data.permissions, [target]: res.permissions } });
      setEdits((cur) => {
        const next = { ...cur };
        delete next[target];
        return next;
      });
      toast(t("apps.server.permissionsSaved"), "success");
    } catch (err) {
      toast(describeError(err, t("apps.server.permissionsFailed")), "error");
    } finally {
      setBusy(false);
    }
  };
  const label = (e: CommandPermission) => {
    if (e.type === 3) return e.id === data.all_channels_id ? t("apps.server.allChannels") : `#${channels[e.id]?.name ?? e.id}`;
    if (e.type === 1) return e.id === serverId ? "@everyone" : `@${server?.roles[e.id]?.name ?? e.id}`;
    return displayName(users[e.id]);
  };
  const picture = (e: CommandPermission) => {
    if (e.type === 3) return <Hash size={18} aria-hidden="true" className={s.permIcon} />;
    if (e.type === 2) return <Avatar id={e.id} avatarId={users[e.id]?.avatar_id ?? null} name={label(e)} size={24} />;
    const color = server?.roles[e.id]?.color;
    return <span className={s.permDot} style={color ? { background: `#${color.toString(16).padStart(6, "0")}` } : undefined} aria-hidden="true" />;
  };
  const people = entries.filter((e) => e.type !== 3);
  const where = entries.filter((e) => e.type === 3);
  const row = (e: CommandPermission) => (
    <div key={`${e.type}:${e.id}`} className={s.permRow}>
      {picture(e)}
      <span className={s.permName} dir="auto">{label(e)}</span>
      <span className={s.permSwitch} role="radiogroup" data-glide="" aria-label={label(e)}>
        <button type="button" role="radio" aria-checked={e.permission} data-allow="" onClick={() => set(entries.map((x) => (x === e ? { ...x, permission: true } : x)))}>{t("apps.server.allowed")}</button>
        <button type="button" role="radio" aria-checked={!e.permission} data-deny="" onClick={() => set(entries.map((x) => (x === e ? { ...x, permission: false } : x)))}>{t("apps.server.denied")}</button>
      </span>
      <button type="button" className={s.permRemove} aria-label={t("apps.server.removeEntry", { name: label(e) })} onClick={() => set(entries.filter((x) => x !== e))}>
        <X size={15} />
      </button>
    </div>
  );
  return (
    <div className={s.perms}>
      <h4 className={s.permsHead}>{t("apps.server.whoCanUse")}</h4>
      <p className={st.hint} style={{ margin: 0 }}>{t("apps.server.whoCanUseHint")}</p>
      <div className={s.permTargets} role="tablist" aria-label={t("apps.server.whoCanUse")}>
        <button type="button" role="tab" aria-selected={target === app.application.id} className={s.permTarget} onClick={() => setTarget(app.application.id)}>{t("apps.server.allCommands")}</button>
        {data.commands.map((c) => (
          <button key={c.id} type="button" role="tab" aria-selected={target === c.id} className={s.permTarget} onClick={() => setTarget(c.id)}>
            {c.type === 1 ? `/${commandName(c)}` : commandName(c)}
          </button>
        ))}
      </div>
      <div className={s.permGroup}>
        <span className={s.permGroupHead}>{t("apps.server.rolesAndMembers")}</span>
        {people.length ? people.map(row) : <span className={s.permNone}>{t("apps.server.noEntries")}</span>}
        <div className={s.permAdd}>
          <SelectBox
            placeholder={t("apps.server.addRoleOrMember")}
            picked={[]}
            labelOf={(v) => v}
            choicesFor={(q) => entityChoices("mentionable", via, q)}
            searchable
            onSearch={(q) => void findMembers(via, q).then(() => rerender((n) => n + 1))}
            onCommit={([v]) => v && add(v, users[v] ? 2 : 1)}
          />
        </div>
      </div>
      <div className={s.permGroup}>
        <span className={s.permGroupHead}>{t("apps.server.channels")}</span>
        {where.length ? where.map(row) : <span className={s.permNone}>{t("apps.server.noEntries")}</span>}
        <div className={s.permAdd}>
          <SelectBox
            placeholder={t("apps.server.addChannel")}
            picked={[]}
            labelOf={(v) => v}
            choicesFor={(q) => [{ value: data.all_channels_id, label: t("apps.server.allChannels") }, ...entityChoices("channel", via, q)]}
            searchable
            onCommit={([v]) => v && add(v, 3)}
          />
        </div>
      </div>
      {changed ? (
        <div className={s.actions}>
          <Button size="sm" onClick={() => void save()} loading={busy} data-testid="save-command-permissions">{t("apps.server.savePermissions")}</Button>
          <Button size="sm" variant="ghost" onClick={() => setEdits((cur) => { const next = { ...cur }; delete next[target]; return next; })}>{t("common.imageCrop.reset")}</Button>
        </div>
      ) : null}
    </div>
  );
}
