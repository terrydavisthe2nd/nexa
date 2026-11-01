import { PersonName } from "@/components/common/PersonName";
import { Copy, Eye, Plus, SmilePlus, Trash2 } from "lucide-react";
import { duplicateRole, startViewAs } from "@/lib/viewAsRole";
import { ColourInput } from "@/components/common/ColourInput";
import { useEffect, useId, useMemo, useRef, useState, type DragEvent } from "react";
import { ImageCropModal } from "@/components/common/ImageCropModal";
import { ExpressionPicker } from "@/components/pickers/ExpressionPicker";
import { RoleIcon } from "@/components/common/RoleIcon";
import { nameClass, nameStyle, type RoleIconInfo, type RoleLook } from "@/lib/roleLook";
import { ApiError } from "@/api/client";
import { servers as serversApi } from "@/api/servers";
import type { Role } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Field, TextInput } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import { useReorder } from "@/hooks/useReorder";
import { describeError } from "@/lib/errors";
import { memberName } from "@/lib/names";
import { canManageRole, parsePerm, permString } from "@/lib/perms";
import { useAuth } from "@/stores/auth";
import { memberHighest, rolesByPosition, useServers } from "@/stores/servers";
import { useUI, type ContextMenuItem } from "@/stores/ui";
import { button, defineMenu } from "@/lib/contextMenus/registry";

interface RoleMenuCtx { moves: ContextMenuItem[]; viewAs: () => void; duplicate: (() => void) | null }
/** A role's menu in the role list: viewing the server as it, copying it, and moving it up or down as dragging does. */
const roleOrderMenu = defineMenu<RoleMenuCtx>("roleOrder", [
  [
    button("viewAsRole", ({ viewAs }) => ({ id: "viewAsRole", label: t("server.viewAs.action"), onSelect: viewAs })),
    button("duplicateRole", ({ duplicate }) => duplicate && { id: "duplicateRole", label: t("server.roles.duplicateRole"), onSelect: duplicate }),
  ],
  [button("moveRole", ({ moves }) => moves)],
]);
import { useUsers } from "@/stores/users";
import st from "@/components/settings/Settings.module.css";
import { RoleGrid } from "../PermissionGrid";
import { SaveBar } from "../SettingsLayer";
import { rolePositionsAfterDrop } from "./rolePositions";
import s from "../ServerSettings.module.css";

import { t } from "@/i18n";
import { NoMatches } from "../NoMatches";
import { unlessUnsaved } from "@/stores/unsaved";
const SWATCHES = [0x1abc9c, 0x2ecc71, 0x3498db, 0x9b59b6, 0xe91e63, 0xf1c40f, 0xe67e22, 0xe74c3c, 0x95a5a6, 0x607d8b, 0x11806a, 0x1f8b4c, 0x206694, 0x71368a, 0xad1457, 0xc27c0e, 0xa84300, 0x992d22, 0x979c9f, 0x546e7a];

function hex(c: number): string {
  return `#${c.toString(16).padStart(6, "0")}`;
}

export default function RolesPage({ serverId }: { serverId: string }) {
  const server = useServers((x) => x.byId[serverId]);
  const upsertRole = useServers((x) => x.upsertRole);
  const upsertRoles = useServers((x) => x.upsertRoles);
  const removeRole = useServers((x) => x.removeRole);
  const meId = useAuth((a) => a.me?.id);
  const toast = useUI((u) => u.toast);
  const roles = useMemo(() => rolesByPosition(server), [server]);
  const [selected, setSelected] = useState<string>(roles[0]?.id ?? serverId);
  const [tab, setTab] = useState<"display" | "permissions" | "members">("display");
  const [drag, setDrag] = useState<string | null>(null);
  const [drop, setDrop] = useState<{ id: string; after: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const role = server?.roles[selected] ?? server?.roles[serverId];
  const mine = server && meId ? memberHighest(server, meId) : { highest: 0, isOwner: false };
  const editable = !!role && (role.id === serverId || canManageRole(mine.highest, mine.isOwner, role.position));

  useEffect(() => {
    if (server && !server.roles[selected]) setSelected(serverId);
  }, [server, selected, serverId]);

  // @everyone is always at the bottom and never moves, so it is not in the
  // list the reordering works on.
  const movable = roles.filter((r) => r.id !== serverId);

  const apply = async (positions: { id: string; position: number }[] | null): Promise<boolean> => {
    if (!positions) return false;
    try {
      upsertRoles(serverId, await serversApi.setRolePositions(serverId, positions));
      return true;
    } catch (err) {
      toast(describeError(err, t("server.roles.couldNotReorderRoles")), "error");
      return false;
    }
  };

  // The same move, without a pointer: the list is drawn top-first, so "up" is
  // one place earlier in it.
  const reorder = useReorder({
    ids: movable.map((r) => r.id),
    name: (id) => server?.roles[id]?.name ?? "",
    canMove: (id) => id !== serverId && canManageRole(mine.highest, mine.isOwner, server?.roles[id]?.position ?? 0),
    move: (id, delta) => {
      const at = movable.findIndex((r) => r.id === id);
      const target = movable[at + delta];
      if (!target) return false;
      return apply(rolePositionsAfterDrop(movable, id, target.id, delta > 0, mine.highest, mine.isOwner));
    },
  });

  // Left and Right across the three tabs, with the selected one the only tab
  // stop: what a tablist does everywhere else.
  const panelId = "role-tab-panel";
  const onTabKey = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const strip = e.currentTarget.parentElement;
    const buttons = strip ? [...strip.querySelectorAll<HTMLElement>('[role="tab"]')] : [];
    const at = buttons.indexOf(e.currentTarget);
    buttons[Math.min(buttons.length - 1, Math.max(0, at + step))]?.click();
  };

  if (!server || !role) return null;
  // @everyone has no member list to manage, however it got selected
  const view = tab === "members" && role.id === serverId ? "display" : tab;
  const memberCount = (id: string) => (id === serverId ? server.memberCount : Object.values(server.members).filter((m) => m.role_ids.includes(id)).length);

  const create = async () => {
    setBusy(true);
    try {
      const r = await serversApi.createRole(serverId, { name: t("server.roles.newRole") });
      upsertRoles(serverId, await serversApi.roles(serverId));
      setSelected(r.id);
      setTab("display");
    } catch (err) {
      toast(describeError(err, t("server.roles.couldNotCreateTheRole")), "error");
    } finally {
      setBusy(false);
    }
  };

  const duplicate = async (r: Role) => {
    setBusy(true);
    try {
      const made = await duplicateRole(r);
      setSelected(made.id);
      setTab("display");
      toast(t("server.roles.roleDuplicated"), "success");
    } catch (err) {
      toast(describeError(err, t("server.roles.couldNotDuplicateTheRole")), "error");
    } finally {
      setBusy(false);
    }
  };
  const viewAs = (r: Role) => {
    startViewAs(serverId, r.id);
    useUI.getState().closeModal();
  };

  const onDrop = async (e: DragEvent) => {
    e.preventDefault();
    const d = drag;
    const at = drop;
    setDrag(null);
    setDrop(null);
    if (!d || !at) return;
    await apply(rolePositionsAfterDrop(movable, d, at.id, at.after, mine.highest, mine.isOwner));
  };

  return (
    <>
      <h2 className={st.title}>{t("server.members.roles")}</h2>
      <p className={st.hint}>{t("server.roles.useRolesToGroupYourMembers")}</p>
      <div className={s.rolesLayout}>
        <div className={s.roleList}>
          <Button size="sm" variant="secondary" onClick={unlessUnsaved(() => void create())} loading={busy} style={{ marginBottom: 8 }} data-testid="create-role"><Plus size={14} /> {t("server.roles.createRole")}</Button>
          {movable.length > 1 ? <p className={st.hint} style={{ margin: "0 0 8px" }}>{reorder.hint}</p> : null}
          {reorder.live}
          {roles.map((r) => {
            const canDrag = r.id !== serverId && canManageRole(mine.highest, mine.isOwner, r.position);
            return (
              <button
                key={r.id}
                className={s.roleRow}
                data-selected={r.id === role.id}
                data-role={r.id}
                data-dragging={drag === r.id}
                data-drop={drop?.id === r.id ? (drop.after ? "after" : "before") : undefined}
                draggable={canDrag}
                onDragStart={(e) => { if (!canDrag) return e.preventDefault(); setDrag(r.id); e.dataTransfer.effectAllowed = "move"; }}
                onDragOver={(e) => { if (!drag || drag === r.id || !canDrag) return; e.preventDefault(); const b = e.currentTarget.getBoundingClientRect(); setDrop({ id: r.id, after: e.clientY > b.top + b.height / 2 }); }}
                onDrop={(e) => void onDrop(e)}
                onDragEnd={() => { setDrag(null); setDrop(null); }}
                onKeyDown={reorder.onKeyDown(r.id)}
                onContextMenu={(e) => {
                  if (roleOrderMenu.open({ moves: reorder.menuItems(r.id), viewAs: unlessUnsaved(() => viewAs(r)), duplicate: r.id === serverId ? null : unlessUnsaved(() => void duplicate(r)) }, { x: e.clientX, y: e.clientY, label: r.name })) e.preventDefault();
                }}
                data-reorder-id={r.id}
                aria-current={r.id === role.id}
                onClick={r.id === selected ? undefined : unlessUnsaved(() => { setSelected(r.id); if (r.id === serverId && tab === "members") setTab("display"); })}
              >
                <span className={s.dot} style={{ background: r.color ? hex(r.color) : "var(--text-muted)" }} />
                <span className={s.roleName} dir="auto">{r.name}</span>
                <span className={s.roleCount}>{memberCount(r.id)}</span>
              </button>
            );
          })}
        </div>
        <div>
          <div className={s.roleActions}>
            <Button size="sm" variant="secondary" onClick={unlessUnsaved(() => viewAs(role))} data-testid="view-as-role"><Eye size={14} /> {t("server.viewAs.action")}</Button>
            {role.id !== serverId ? <Button size="sm" variant="secondary" onClick={unlessUnsaved(() => void duplicate(role))} disabled={busy} data-testid="duplicate-role"><Copy size={14} /> {t("server.roles.duplicateRole")}</Button> : null}
          </div>
          <div className={s.tabs} role="tablist" aria-label={role.name}>
            <button className={s.tab} role="tab" aria-selected={view === "display"} aria-controls={panelId} tabIndex={view === "display" ? 0 : -1} onKeyDown={onTabKey} data-on={view === "display"} onClick={unlessUnsaved(() => setTab("display"))}>{t("server.roles.display")}</button>
            <button className={s.tab} role="tab" aria-selected={view === "permissions"} aria-controls={panelId} tabIndex={view === "permissions" ? 0 : -1} onKeyDown={onTabKey} data-on={view === "permissions"} onClick={unlessUnsaved(() => setTab("permissions"))}>{t("server.channelSettings.permissions")}</button>
            {role.id !== serverId ? <button className={s.tab} role="tab" aria-selected={tab === "members"} aria-controls={panelId} tabIndex={tab === "members" ? 0 : -1} onKeyDown={onTabKey} data-on={tab === "members"} onClick={unlessUnsaved(() => setTab("members"))}>{t("server.roles.manageMembers")}</button> : null}
          </div>
          <div id={panelId} role="tabpanel" tabIndex={-1}>
          {view === "display" ? <DisplayTab key={role.id} role={role} editable={editable} everyone={role.id === serverId} onSaved={upsertRole} onDeleted={() => { removeRole(serverId, role.id); setSelected(serverId); }} /> : null}
          {view === "permissions" ? <PermissionsTab key={role.id} role={role} editable={editable} actorPerms={server.myPermissions} onSaved={upsertRole} /> : null}
          {view === "members" ? <MembersTab key={role.id} role={role} serverId={serverId} editable={editable} /> : null}
          </div>
        </div>
      </div>
    </>
  );
}

function DisplayTab({ role, editable, everyone, onSaved, onDeleted }: { role: Role; editable: boolean; everyone: boolean; onSaved: (r: Role) => void; onDeleted: () => void }) {
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [name, setName] = useState(role.name);
  const [color, setColor] = useState(role.color);
  const [hoist, setHoist] = useState(role.hoist);
  const [mentionable, setMentionable] = useState(role.mentionable);
  const [color2, setColor2] = useState<number | null>(role.color2 ?? null);
  const [color3, setColor3] = useState<number | null>(role.color3 ?? null);
  const [iconEmoji, setIconEmoji] = useState(role.icon_emoji ?? "");
  const [iconBusy, setIconBusy] = useState(false);
  const [crop, setCrop] = useState<File | null>(null);
  const [picker, setPicker] = useState<DOMRect | null>(null);
  const iconRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  // One group per role, so switching roles does not leave the browser thinking
  // two sets of swatches are the same set.
  const swatchName = `role-colour-${useId()}`;
  const style: "solid" | "gradient" | "shimmer" = color2 == null ? "solid" : color3 == null ? "gradient" : "shimmer";
  const setStyle = (next: "solid" | "gradient" | "shimmer") => {
    if (next === "solid") {
      setColor2(null);
      setColor3(null);
      return;
    }
    if (!color) setColor(0x8000ff);
    setColor2((c) => c ?? 0x00b0f4);
    setColor3(next === "shimmer" ? (color3 ?? 0xffd166) : null);
  };
  const dirty = name !== role.name || color !== role.color || hoist !== role.hoist || mentionable !== role.mentionable || color2 !== (role.color2 ?? null) || color3 !== (role.color3 ?? null) || iconEmoji !== (role.icon_emoji ?? "");
  // an emoji picked but not yet saved shows in place of the picture it will replace
  const emojiIcon: RoleIconInfo | undefined = !iconEmoji ? undefined : iconEmoji.startsWith("custom:") ? { kind: "custom", id: iconEmoji.slice(7), name } : { kind: "emoji", text: iconEmoji, name };
  const pickedIcon: RoleIconInfo | undefined = emojiIcon ?? (role.icon_id ? { kind: "image", id: role.icon_id, name } : undefined);
  const look = { color: color ? hex(color) : undefined, ...(color && color2 != null ? { colors: [hex(color), hex(color2), ...(color3 != null ? [hex(color3)] : [])], shimmer: color3 != null } : {}) } as RoleLook;
  const uploadIcon = async (blob: Blob, crop?: { x: number; y: number; w: number; h: number } | null) => {
    setIconBusy(true);
    try {
      const saved = await serversApi.uploadRoleIcon(role.server_id, role.id, blob, crop);
      onSaved(saved);
      setIconEmoji("");
      toast(t("roles.icon.saved"), "success");
    } catch (err) {
      toast(describeError(err, t("roles.icon.failed")), "error");
    } finally {
      setIconBusy(false);
    }
  };
  const removeIcon = async () => {
    setIconBusy(true);
    try {
      const saved = role.icon_id ? await serversApi.deleteRoleIcon(role.server_id, role.id) : await serversApi.patchRole(role.server_id, role.id, { icon_emoji: "" });
      onSaved(saved);
      setIconEmoji("");
    } catch (err) {
      toast(describeError(err, t("roles.icon.failed")), "error");
    } finally {
      setIconBusy(false);
    }
  };
  const save = async () => {
    setBusy(true);
    try {
      const body: Parameters<typeof serversApi.patchRole>[2] = {};
      if (name !== role.name) body.name = name;
      if (color !== role.color) body.color = color;
      if (hoist !== role.hoist) body.hoist = hoist;
      if (mentionable !== role.mentionable) body.mentionable = mentionable;
      if (color2 !== (role.color2 ?? null)) body.color2 = color2;
      if (color3 !== (role.color3 ?? null)) body.color3 = color3;
      if (iconEmoji !== (role.icon_emoji ?? "")) body.icon_emoji = iconEmoji.trim();
      const saved = await serversApi.patchRole(role.server_id, role.id, body);
      onSaved(saved);
      setColor2(saved.color2 ?? null);
      setColor3(saved.color3 ?? null);
      setIconEmoji(saved.icon_emoji ?? "");
      // the server tidies whitespace in names, so show what it kept or the save bar never goes away
      setName(saved.name);
      setColor(saved.color);
      setHoist(saved.hoist);
      setMentionable(saved.mentionable);
      toast(t("server.roles.roleSaved"), "success");
    } catch (err) {
      toast(describeError(err, t("server.roles.couldNotSaveTheRole")), "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className={st.form}>
      {!everyone ? (
        <Field label={t("server.roles.roleName")} required>
          <TextInput value={name} onChange={(e) => setName(e.target.value)} maxLength={100} disabled={!editable} data-testid="role-name" />
        </Field>
      ) : (
        <p className={st.hint}>{t("server.roles.everyoneIsTheBaseRoleEvery")}</p>
      )}
      <Field as="div" label={t("server.roles.roleColour")} hint={t("server.roles.membersUseTheColourOfTheir")}>
        <div className={s.swatches} role="radiogroup" aria-label={t("server.roles.roleColour")}>
          <span className={s.preview}>
            <span className={s.dot} style={{ background: look.colors ? `linear-gradient(90deg, ${look.colors.join(", ")})` : color ? hex(color) : "var(--text-muted)" }} />
            <span className={nameClass(look)} style={nameStyle(look)}>{name || "@everyone"}</span>
            {!everyone && pickedIcon ? <RoleIcon icon={pickedIcon} size={18} /> : null}
          </span>
          {/* Without a shared `name` the browser treats twenty-one swatches as
              twenty-one unrelated radios: no arrow keys between them, and one
              tab stop each. Their only name was `title={hex(c)}`, which a
              screen reader reads as "#1abc9c, radio button". */}
          <label className={s.swatch} data-on={color === 0} style={{ background: "var(--bg-active)" }} title={t("server.roles.default")}><input type="radio" name={swatchName} checked={color === 0} disabled={!editable} onChange={() => setColor(0)} aria-label={t("server.roles.default")} /></label>
          {SWATCHES.map((c, i) => (
            <label key={c} className={s.swatch} data-on={color === c} style={{ background: hex(c) }} title={hex(c)}><input type="radio" name={swatchName} checked={color === c} disabled={!editable} onChange={() => setColor(c)} aria-label={t("server.roles.colourIndexOfCount", { index: i + 1, count: SWATCHES.length })} /></label>
          ))}
          <label className={s.swatch} style={{ background: color && !SWATCHES.includes(color) ? hex(color) : "var(--bg-surface)", border: "2px dashed var(--border-strong)" }} title={t("server.roles.customColour")}><ColourInput value={hex(color || 0x99aab5)} disabled={!editable} label={t("server.roles.customColour")} onChange={(v) => setColor(parseInt(v.slice(1, 7), 16))} /></label>
        </div>
      </Field>
      {!everyone ? (
        <Field as="div" label={t("roles.style.label")} hint={t("roles.style.hint")}>
          <div className={s.styleRow} role="radiogroup" aria-label={t("roles.style.label")}>
            {(["solid", "gradient", "shimmer"] as const).map((x) => (
              <label key={x} className={s.styleOpt} data-on={style === x}>
                <input type="radio" name={`${swatchName}-style`} checked={style === x} disabled={!editable} onChange={() => setStyle(x)} />
                {t(`roles.style.${x}`)}
              </label>
            ))}
          </div>
          {style !== "solid" ? (
            <div className={s.styleColors}>
              <label className={s.styleColor}>
                <span>{t("roles.style.second")}</span>
                <ColourInput shown value={hex(color2 ?? 0x00b0f4)} disabled={!editable} label={t("roles.style.second")} onChange={(v) => setColor2(parseInt(v.slice(1, 7), 16))} />
              </label>
              {style === "shimmer" ? (
                <label className={s.styleColor}>
                  <span>{t("roles.style.third")}</span>
                  <ColourInput shown value={hex(color3 ?? 0xffd166)} disabled={!editable} label={t("roles.style.third")} onChange={(v) => setColor3(parseInt(v.slice(1, 7), 16))} />
                </label>
              ) : null}
            </div>
          ) : null}
        </Field>
      ) : null}
      {!everyone ? (
        <Field as="div" label={t("roles.icon.label")} hint={t("roles.icon.hint")}>
          <div className={s.styleColors}>
            <Button size="sm" variant="secondary" disabled={!editable || iconBusy} onClick={() => iconRef.current?.click()}>{t("roles.icon.upload")}</Button>
            <Button size="sm" variant="secondary" disabled={!editable} onClick={(e) => setPicker(picker ? null : e.currentTarget.getBoundingClientRect())} data-picker-toggle={picker ? "" : undefined} data-testid="role-icon-choose-emoji">
              {emojiIcon ? <RoleIcon icon={emojiIcon} size={16} /> : <SmilePlus size={16} aria-hidden="true" />} {t("server.channelIcon.chooseEmoji")}
            </Button>
            {role.icon_id || role.icon_emoji ? <Button size="sm" variant="ghost" disabled={!editable || iconBusy} onClick={() => void removeIcon()}>{t("roles.icon.remove")}</Button> : null}
            <input ref={iconRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) setCrop(f); e.target.value = ""; }} />
          </div>
          {picker ? (
            <ExpressionPicker
              anchor={picker}
              serverId={role.server_id}
              onlyServer
              onClose={() => setPicker(null)}
              onEmoji={(pick) => {
                setIconEmoji(pick.custom ? `custom:${pick.custom.id}` : pick.unicode);
                setPicker(null);
              }}
            />
          ) : null}
          {crop ? <ImageCropModal file={crop} aspect={1} maxSize={128} title={t("roles.icon.crop")} serverCrop onClose={() => setCrop(null)} onDone={({ blob, crop: area }) => { setCrop(null); void uploadIcon(blob, area ?? null); }} /> : null}
        </Field>
      ) : null}
      {!everyone ? <Toggle checked={hoist} disabled={!editable} onChange={setHoist} label={t("server.roles.displayRoleMembersSeparatelyFromOnline")} /> : null}
      <Toggle checked={mentionable} disabled={!editable} onChange={setMentionable} label={t("server.roles.allowAnyoneToMentionThisRole")} description={t("server.roles.membersWithMentionEveryoneCanAlways")} />
      {!everyone && editable ? (
        <div>
          <Button variant="danger" size="sm" onClick={() => openModal("confirm", { title: t("server.roles.deleteNamed", { name: role.name }), body: t("server.roles.membersLoseThisRoleAndAny"), confirmLabel: t("server.roles.deleteRole"), danger: true, onConfirm: async () => { await serversApi.deleteRole(role.server_id, role.id); onDeleted(); } })} data-testid="delete-role"><Trash2 size={14} /> {t("server.roles.deleteRole")}</Button>
        </div>
      ) : null}
      <SaveBar dirty={dirty} busy={busy} onReset={() => { setName(role.name); setColor(role.color); setHoist(role.hoist); setMentionable(role.mentionable); setColor2(role.color2 ?? null); setColor3(role.color3 ?? null); setIconEmoji(role.icon_emoji ?? ""); }} onSave={() => void save()} />
    </div>
  );
}

function PermissionsTab({ role, editable, actorPerms, onSaved }: { role: Role; editable: boolean; actorPerms: bigint; onSaved: (r: Role) => void }) {
  const toast = useUI((u) => u.toast);
  const [value, setValue] = useState(() => parsePerm(role.permissions));
  const [filter, setFilter] = useState("");
  const [busy, setBusy] = useState(false);
  const original = parsePerm(role.permissions);
  const dirty = value !== original;
  // an administrator holds everything, and so may give anything
  const grantable = (actorPerms & (1n << 3n)) !== 0n ? undefined : actorPerms;
  const save = async () => {
    setBusy(true);
    try {
      onSaved(await serversApi.patchRole(role.server_id, role.id, { permissions: permString(value) }));
      toast(t("server.channelSettings.permissionsSaved"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.channelSettings.couldNotSavePermissions"), "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div>
      <div className={s.searchRow}>
        <TextInput type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("server.roles.searchPermissions")} aria-label={t("server.roles.searchPermissions")} />
      </div>
      {/* It sat beside the search box as "Clear", the word the quick switcher
          uses for emptying its box, and took every permission off the role.
          It says what it does now, away from the search; the save bar's Reset
          still undoes it. It takes off what the editor could have given, and
          nothing else. */}
      <div className={s.permHintRow}>
        <p className={st.hint} style={{ margin: 0 }}>{(actorPerms & (1n << 3n)) !== 0n ? t("server.roles.grantHintAll") : t("server.roles.grantHint")}</p>
        <Button variant="ghost" size="sm" onClick={() => setValue(grantable === undefined ? 0n : value & ~grantable)} disabled={!editable || (grantable === undefined ? value === 0n : (value & grantable) === 0n)}>{t("server.roles.turnAllOff")}</Button>
      </div>
      <RoleGrid value={value} onChange={setValue} editable={editable} filter={filter} grantable={grantable} />
      <SaveBar dirty={dirty} busy={busy} onReset={() => setValue(original)} onSave={() => void save()} />
    </div>
  );
}

/** How many members Manage Members shows at a time. */
const MEMBERS_PAGE = 50;

function MembersTab({ role, serverId, editable }: { role: Role; serverId: string; editable: boolean }) {
  const server = useServers((x) => x.byId[serverId]);
  const upsertMember = useServers((x) => x.upsertMember);
  const users = useUsers((u) => u.byId);
  const toast = useUI((u) => u.toast);
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [shown, setShown] = useState(MEMBERS_PAGE);
  if (!server) return null;
  const query = q.trim().toLowerCase();
  const all = Object.values(server.members).map((m) => ({ m, name: memberName(m.nickname, users[m.user_id]), username: users[m.user_id]?.username ?? "", has: m.role_ids.includes(role.id) }));
  const holders = all.filter((x) => x.has).length;
  // Cut at a hundred with no count and no way on, whether someone appeared
  // depended on where the alphabet put them. A page at a time, as the
  // members page does, with how many are left.
  const matching = all.filter((x) => !query || x.name.toLowerCase().includes(query) || x.username.includes(query)).sort((a, b) => Number(b.has) - Number(a.has) || a.name.localeCompare(b.name));
  const members = matching.slice(0, shown);
  const toggle = async (userId: string, on: boolean) => {
    setBusy(userId);
    try {
      upsertMember(on ? await serversApi.addRole(serverId, userId, role.id) : await serversApi.removeRole(serverId, userId, role.id));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.memberAction.couldNotChangeRoles"), "error");
    } finally {
      setBusy(null);
    }
  };
  return (
    <div>
      <div className={s.searchRow}>
        <TextInput value={q} onChange={(e) => { setQ(e.target.value); setShown(MEMBERS_PAGE); }} placeholder={t("server.channelSettings.searchMembers")} aria-label={t("server.channelSettings.searchMembers")} />
      </div>
      <p className={st.hint} style={{ margin: "0 0 8px" }}>{t("server.roles.holdersCount", { count: holders })}</p>
      {matching.length === 0 && query ? <NoMatches people query={q} onClear={() => setQ("")} /> : null}
      {members.map(({ m, name, username, has }) => (
        <div key={m.user_id} className={s.memberRow}>
          <Avatar id={m.user_id} avatarId={m.avatar_id ?? users[m.user_id]?.avatar_id ?? null} name={name} size={32} />
          <div className={s.memberText}>
            <PersonName userId={m.user_id} serverId={serverId} className={s.memberName}>{name}</PersonName>
            <span className={s.memberSub}><bdi>@{username}</bdi></span>
          </div>
          <Button size="sm" variant={has ? "secondary" : "primary"} disabled={!editable || busy === m.user_id} onClick={() => void toggle(m.user_id, !has)}>{has ? t("server.packEditor.remove") : t("server.roles.addMember")}</Button>
        </div>
      ))}
      {matching.length > shown ? <div className={s.loadMore}><Button variant="secondary" size="sm" onClick={() => setShown((v) => v + MEMBERS_PAGE)}>{t("server.members.showMoreLeft", { count: matching.length - shown })}</Button></div> : null}
    </div>
  );
}
