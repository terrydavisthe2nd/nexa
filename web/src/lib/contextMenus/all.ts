/**
 * Every module that defines a menu. Most load with the app; some only with
 * the screen they belong to (the notes, the pickers, the staff panel). The
 * plugin API loads them all before a plugin that may change menus starts, so
 * it sees every menu and every button from the first moment. A test fails
 * when a module that defines a menu is missing here.
 */
export const MENU_MODULES: (() => Promise<unknown>)[] = [
  () => import("@/components/chat/chatMoreMenu"),
  () => import("@/components/chat/groupMemberMenu"),
  () => import("@/components/chat/messageMenu"),
  () => import("@/components/chat/subchatMenu"),
  () => import("@/components/chat/VideoPlayer"),
  () => import("@/components/composer/plusMenu"),
  () => import("@/components/forum/ForumView"),
  () => import("@/components/friends/friendMenu"),
  () => import("@/components/friends/friendsBulk"),
  () => import("@/components/moderation/BugsTab"),
  () => import("@/components/moderation/modMenu"),
  () => import("@/components/moderation/UserGrid"),
  () => import("@/components/nexa/ThreadChat"),
  () => import("@/components/notes/noteMenu"),
  () => import("@/components/pickers/pickerMenus"),
  () => import("@/components/profile/profileMenus"),
  () => import("@/components/rail/railActions"),
  () => import("@/components/rail/railBulk"),
  () => import("@/components/search/SearchResults"),
  () => import("@/components/server/memberBulk"),
  () => import("@/components/server/memberMenu"),
  () => import("@/components/server/PackEditor"),
  () => import("@/components/server/pages/InvitesPage"),
  () => import("@/components/server/pages/members/ModMembers"),
  () => import("@/components/server/pages/RolesPage"),
  () => import("@/components/settings/ServerProfileDesigner"),
  () => import("@/components/settings/TypingStyleEditor"),
  () => import("@/components/sidebar/dmArrange"),
  () => import("@/components/sidebar/dmBulk"),
  () => import("@/components/sidebar/dmMenu"),
  () => import("@/components/sidebar/serverMenu"),
  () => import("@/components/threads/ThreadBar"),
  () => import("@/components/voice/callMenus"),
  () => import("@/components/voice/deviceMenus"),
  () => import("@/lib/textMenu"),
];

let all: Promise<void> | null = null;

/** Loads every menu module, once. */
export function loadAllMenus(): Promise<void> {
  all ??= Promise.all(MENU_MODULES.map((load) => load())).then(() => undefined);
  return all;
}
