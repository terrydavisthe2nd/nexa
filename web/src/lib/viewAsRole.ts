import { servers as serversApi } from "@/api/servers";
import type { Role } from "@/api/types";
import { t } from "@/i18n";
import { useServers } from "@/stores/servers";
import { useViewAs } from "@/stores/viewAs";

/** Starts previewing a server as one role (with @everyone, which every member has). */
export function startViewAs(serverId: string, roleId: string): void {
  const prev = useViewAs.getState().current;
  useViewAs.setState({ current: { serverId, roleIds: roleId === serverId ? [] : [roleId] } });
  if (prev && prev.serverId !== serverId) useServers.getState().refreshMine(prev.serverId);
  useServers.getState().refreshMine(serverId);
}

export function stopViewAs(): void {
  const prev = useViewAs.getState().current;
  if (!prev) return;
  useViewAs.setState({ current: null });
  useServers.getState().refreshMine(prev.serverId);
}

/**
 * Makes a new role with everything of this one a role can be made with: its
 * name (marked as a copy), colours, emoji icon, permissions and switches.
 * An uploaded picture icon is not copied, nor are members or channel overrides.
 */
export async function duplicateRole(role: Role): Promise<Role> {
  const made = await serversApi.createRole(role.server_id, {
    name: t("server.roles.copyName", { name: role.name }).slice(0, 100),
    color: role.color,
    hoist: role.hoist,
    mentionable: role.mentionable,
    permissions: role.permissions,
  });
  // creating takes only the plain fields; the rest goes on as an edit
  const extra = { ...(role.color2 != null ? { color2: role.color2 } : {}), ...(role.color3 != null ? { color3: role.color3 } : {}), ...(role.icon_emoji ? { icon_emoji: role.icon_emoji } : {}) };
  if (Object.keys(extra).length) {
    try {
      await serversApi.patchRole(role.server_id, made.id, extra);
    } catch {
      // the copy stands without its gradient or emoji
    }
  }
  useServers.getState().upsertRoles(role.server_id, await serversApi.roles(role.server_id));
  return made;
}
