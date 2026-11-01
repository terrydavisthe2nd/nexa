/**
 * What a v2 plugin can ask to do. The person grants them all when the plugin
 * is added (AddPluginDialog) and can switch any of them off, or back on,
 * later on the Plugins page. Every v2
 * method checks its permission as it is called, against the grants as they
 * are at that moment.
 */

import { t } from "@/i18n";

export const PERMISSIONS = [
  "users.read",
  "users.self.read",
  "users.self.write",
  "users.relationships.read",
  "users.relationships.write",
  "directmessages.read",
  "directmessages.write",
  "servers.read",
  "servers.write",
  "servers.members.read",
  "servers.members.write",
  "servers.channels.read",
  "servers.channels.write",
  "servers.messages.read",
  "servers.messages.write",
  "styles.write",
  "events.read",
  "events.write",
  "keybinds.read",
  "keybinds.write",
  "commands.write",
  "storage.read",
  "storage.write",
  "ui.write",
  "composer.write",
  "navigation",
  "stores",
] as const;

export type PermissionName = (typeof PERMISSIONS)[number];

/** Marked as dangerous wherever permissions are shown: the app's own state, past what any other permission gives. */
export const DANGEROUS_PERMISSIONS: readonly PermissionName[] = ["stores"];

const KNOWN = new Set<string>(PERMISSIONS);

export function isPermission(x: unknown): x is PermissionName {
  return typeof x === "string" && KNOWN.has(x);
}

/** A list of permissions as a plugin or the saved settings give it: the known ones, each once, in the list's order. */
export function cleanPermissions(v: unknown): PermissionName[] {
  if (!Array.isArray(v)) return [];
  const seen = new Set<string>();
  const out: PermissionName[] = [];
  for (const p of v) {
    if (!isPermission(p) || seen.has(p)) continue;
    seen.add(p);
    out.push(p);
  }
  return out;
}

/** What a permission is called, and what it lets a plugin do, in the reader's language. */
const WORDS: Record<PermissionName, () => { name: string; hint: string }> = {
  "users.read": () => ({ name: t("look.plugins.perm.usersRead"), hint: t("look.plugins.perm.usersReadHint") }),
  "users.self.read": () => ({ name: t("look.plugins.perm.usersSelfRead"), hint: t("look.plugins.perm.usersSelfReadHint") }),
  "users.self.write": () => ({ name: t("look.plugins.perm.usersSelfWrite"), hint: t("look.plugins.perm.usersSelfWriteHint") }),
  "users.relationships.read": () => ({ name: t("look.plugins.perm.usersRelationshipsRead"), hint: t("look.plugins.perm.usersRelationshipsReadHint") }),
  "users.relationships.write": () => ({ name: t("look.plugins.perm.usersRelationshipsWrite"), hint: t("look.plugins.perm.usersRelationshipsWriteHint") }),
  "directmessages.read": () => ({ name: t("look.plugins.perm.directmessagesRead"), hint: t("look.plugins.perm.directmessagesReadHint") }),
  "directmessages.write": () => ({ name: t("look.plugins.perm.directmessagesWrite"), hint: t("look.plugins.perm.directmessagesWriteHint") }),
  "servers.read": () => ({ name: t("look.plugins.perm.serversRead"), hint: t("look.plugins.perm.serversReadHint") }),
  "servers.write": () => ({ name: t("look.plugins.perm.serversWrite"), hint: t("look.plugins.perm.serversWriteHint") }),
  "servers.members.read": () => ({ name: t("look.plugins.perm.serversMembersRead"), hint: t("look.plugins.perm.serversMembersReadHint") }),
  "servers.members.write": () => ({ name: t("look.plugins.perm.serversMembersWrite"), hint: t("look.plugins.perm.serversMembersWriteHint") }),
  "servers.channels.read": () => ({ name: t("look.plugins.perm.serversChannelsRead"), hint: t("look.plugins.perm.serversChannelsReadHint") }),
  "servers.channels.write": () => ({ name: t("look.plugins.perm.serversChannelsWrite"), hint: t("look.plugins.perm.serversChannelsWriteHint") }),
  "servers.messages.read": () => ({ name: t("look.plugins.perm.serversMessagesRead"), hint: t("look.plugins.perm.serversMessagesReadHint") }),
  "servers.messages.write": () => ({ name: t("look.plugins.perm.serversMessagesWrite"), hint: t("look.plugins.perm.serversMessagesWriteHint") }),
  "styles.write": () => ({ name: t("look.plugins.perm.stylesWrite"), hint: t("look.plugins.perm.stylesWriteHint") }),
  "events.read": () => ({ name: t("look.plugins.perm.eventsRead"), hint: t("look.plugins.perm.eventsReadHint") }),
  "events.write": () => ({ name: t("look.plugins.perm.eventsWrite"), hint: t("look.plugins.perm.eventsWriteHint") }),
  "keybinds.read": () => ({ name: t("look.plugins.perm.keybindsRead"), hint: t("look.plugins.perm.keybindsReadHint") }),
  "keybinds.write": () => ({ name: t("look.plugins.perm.keybindsWrite"), hint: t("look.plugins.perm.keybindsWriteHint") }),
  "commands.write": () => ({ name: t("look.plugins.perm.commandsWrite"), hint: t("look.plugins.perm.commandsWriteHint") }),
  "storage.read": () => ({ name: t("look.plugins.perm.storageRead"), hint: t("look.plugins.perm.storageReadHint") }),
  "storage.write": () => ({ name: t("look.plugins.perm.storageWrite"), hint: t("look.plugins.perm.storageWriteHint") }),
  "ui.write": () => ({ name: t("look.plugins.perm.uiWrite"), hint: t("look.plugins.perm.uiWriteHint") }),
  "composer.write": () => ({ name: t("look.plugins.perm.composerWrite"), hint: t("look.plugins.perm.composerWriteHint") }),
  "navigation": () => ({ name: t("look.plugins.perm.navigation"), hint: t("look.plugins.perm.navigationHint") }),
  "stores": () => ({ name: t("look.plugins.perm.stores"), hint: t("look.plugins.perm.storesHint") }),
};

export function permissionWords(p: PermissionName): { name: string; hint: string } {
  return WORDS[p]();
}

/** A list of permissions in the order they are always shown in, the dangerous one last. */
export function inOrder(list: Iterable<string>): PermissionName[] {
  const want = new Set<string>(list);
  return PERMISSIONS.filter((p) => want.has(p));
}

/**
 * What new code for a plugin means for its grants: what it keeps (granted
 * before and still asked for), what it asks for that it was not granted
 * before (to be agreed to first), and what it no longer asks for (dropped).
 * Installing is all or nothing, so a new plugin's grants are its whole list.
 */
export function grantChange(had: Iterable<string>, asked: Iterable<string>): { keep: PermissionName[]; added: PermissionName[]; dropped: PermissionName[] } {
  const before = new Set<string>(had);
  const now = new Set<string>(cleanPermissions([...asked]));
  return {
    keep: inOrder([...now].filter((p) => before.has(p))),
    added: inOrder([...now].filter((p) => !before.has(p))),
    dropped: inOrder([...before].filter((p) => !now.has(p))),
  };
}
