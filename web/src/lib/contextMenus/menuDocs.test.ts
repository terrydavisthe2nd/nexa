/**
 * The table of menus in docs/plugins.md, made from the registry so it cannot
 * fall out of date: every menu a plugin can change, where it opens, what its
 * context gives a plugin and its buttons' ids. The test fails when the docs
 * differ; `UPDATE_DOCS=1 npx vitest run src/lib/contextMenus/menuDocs.test.ts`
 * writes the table again. A new menu needs a line in WHERE below.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { expect, it } from "vitest";
import { loadAllMenus } from "./all";
import { menuButtons, menuKinds, menuSubjects } from "./registry";

/** Where each menu opens, in a few plain words. */
const WHERE: Record<string, string> = {
  activity: "A right click on the activity button: sharing activity and where it comes from",
  bugStatus: "A bug report's status, in the staff panel",
  callParticipant: "A person in a call's list of who is in it",
  callTile: "A person's tile in a call",
  camera: "The arrow beside the camera button in a call",
  category: "A category in a server's channel list",
  channel: "A channel in a server's channel list",
  chatMore: "The More button at the top of a conversation",
  composerPlus: "The plus button in the message box, when it offers a choice",
  composerTools: "The tools button of the message box on a phone (formatting, preview, emoji)",
  dm: "A conversation in the direct messages list",
  dmBulk: "Several conversations picked in the direct messages list",
  dmGroup: "A group of conversations in the direct messages list",
  dmList: "The empty space of the direct messages list",
  favoriteGif: "A favourite GIF in the GIF picker",
  folder: "A folder of servers in the server rail",
  forumView: "A forum's view options",
  friend: "A person on the Friends page",
  friendsBulk: "Several people picked on the Friends page",
  gifResult: "A search result in the GIF picker",
  groupMember: "A person in a group conversation's member list",
  groupMemberBulk: "Several people picked in a group conversation's member list",
  image: "A picture opened full size",
  invitePause: "Pausing invites, in Server Settings",
  member: "A person in a server's member list, or their name in a server",
  memberAgeFilter: "The account age filter of Server Settings, Members",
  memberBulk: "Several people picked in a server's member list",
  memberJoinedFilter: "The joined filter of Server Settings, Members",
  memberJoinFilter: "The join method filter of Server Settings, Members",
  memberRoleFilter: "The roles filter of Server Settings, Members",
  memberSort: "The sort order of Server Settings, Members",
  message: "A message",
  microphone: "The arrow beside the microphone button in a call",
  moderation: "The staff panel's entry in the server rail or the direct messages list",
  nexa: "Nexa's own conversation in the direct messages list",
  noiseMode: "The noise suppression button in a call",
  note: "A note in the notes list",
  noteLinkRole: "What a note's share link lets people do",
  noteMemberRole: "What a person may do in a shared note",
  page: "Selected text or a link anywhere else in the app",
  profileAddRole: "The add role button on a person's profile in a server",
  profileMore: "The More button on a person's profile",
  roleOrder: "A role, in Server Settings, Roles",
  searchSort: "The sort order of search results",
  server: "A server in the server rail",
  serverBulk: "Several servers picked in the server rail",
  serverProfilePick: "Picking a server, in Settings, Profiles",
  stickerOrder: "Moving a sticker, in a sticker pack's editor",
  stickerPack: "A sticker pack in the sticker picker",
  subchat: "A sub-conversation of a direct message",
  systemMessage: "A message the app writes itself (a join, a pin, a call)",
  textBox: "The message box and other text you can type in",
  thread: "A thread in a server's channel list",
  threadLine: "A line of a case thread or bug report",
  threadTools: "The tools button of a case thread's or bug report's box on a phone",
  typingSlot: "One of the pictures of your typing style, in Settings",
  userGrid: "A person in the staff panel's list of accounts",
  videoMore: "The More button of a video player",
};

const SUBJECT_WORDS = { message: "`message`", user: "`user`", channel: "`channel`", server: "`server`" } as const;

function table(): string {
  const kinds = menuKinds().filter((k) => !k.startsWith("test") && !k.startsWith("layer")).sort();
  const lines = ["| Menu | Where it opens | Context | Buttons |", "| --- | --- | --- | --- |"];
  for (const k of kinds) {
    const subjects = menuSubjects(k).map((s) => SUBJECT_WORDS[s]).join(", ") || "none";
    const buttons = menuButtons(k).map((b) => `\`${b.id}\``).join(", ");
    lines.push(`| \`${k}\` | ${WHERE[k] ?? ""} | ${subjects} | ${buttons} |`);
  }
  return lines.join("\n");
}

const START = "<!-- menus:start (made by web/src/lib/contextMenus/menuDocs.test.ts) -->";
const END = "<!-- menus:end -->";

it("docs/plugins.md lists every menu as the registry has it", async () => {
  await loadAllMenus();
  const missing = menuKinds().filter((k) => !k.startsWith("test") && !k.startsWith("layer") && !WHERE[k]);
  expect(missing, "menus without a line in WHERE").toEqual([]);
  const path = join(__dirname, "../../../../docs/plugins.md");
  const doc = readFileSync(path, "utf8");
  const a = doc.indexOf(START);
  const b = doc.indexOf(END);
  expect(a >= 0 && b > a, "docs/plugins.md has the menus markers").toBe(true);
  const want = `${doc.slice(0, a + START.length)}\n\n${table()}\n\n${doc.slice(b)}`;
  if (process.env["UPDATE_DOCS"]) writeFileSync(path, want);
  else expect(doc, "the menus table is out of date: run with UPDATE_DOCS=1").toBe(want);
}, 60_000);
