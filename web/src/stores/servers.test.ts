import { beforeEach, describe, expect, it } from "vitest";
import type { Channel, Member, Role, ServerView } from "@/api/types";
import { DEFAULT_EVERYONE, P, permString } from "@/lib/perms";
import { useChannels } from "./channels";
import { canI, channelPerms, memberColor, rolesByPosition, serverPerms, useServers } from "./servers";

const S = "100";
const ch = (id: string, extra: Partial<Channel> = {}): Channel => ({
  id,
  server_id: S,
  type: "text",
  name: "c" + id,
  topic: "",
  position: 0,
  parent_id: null,
  nsfw: false,
  slowmode_seconds: 0,
  user_limit: 0,
  owner_id: null,
  icon_id: null,
  last_message_id: null,
  created_at: 0,
  recipient_ids: [],
  key_conv_id: S,
  ...extra,
});
const role = (id: string, position: number, permissions: bigint, color = 0): Role => ({ id, server_id: S, name: "r" + id, color, hoist: false, mentionable: false, position, permissions: permString(permissions), icon_id: null, created_at: 0 });
const member = (user_id: string, role_ids: string[] = []): Member => ({ server_id: S, user_id, nickname: "", avatar_id: null, joined_at: 0, timeout_until: null, flags: 0, role_ids });
const view = (): ServerView => ({
  id: S,
  name: "Srv",
  icon_id: null,
  banner_id: null,
  owner_id: "1",
  description: "",
  system_channel_id: null,
  default_notifications: 0,
  flags: 0,
  created_at: 0,
  channels: [ch("200"), ch("201", { overwrites: [{ channel_id: "201", target_id: S, target_type: "role", allow: "0", deny: permString(P.VIEW_CHANNEL) }] })],
  roles: [role(S, 0, DEFAULT_EVERYONE), role("300", 1, DEFAULT_EVERYONE | P.MANAGE_MESSAGES, 0xff0000)],
  members: [member("1"), member("2", ["300"]), member("3")],
  member_count: 3,
  me: member("2", ["300"]),
  my_permissions: permString(DEFAULT_EVERYONE | P.MANAGE_MESSAGES),
});

describe("servers store", () => {
  beforeEach(() => {
    useServers.getState().clear();
    useChannels.getState().clear();
    const v = view();
    useChannels.getState().upsert(v.channels);
    useServers.getState().setFromView(v);
  });
  it("ingests a view", () => {
    const st = useServers.getState().byId[S]!;
    expect(st.memberCount).toBe(3);
    expect(st.membersComplete).toBe(true);
    expect(useServers.getState().order).toEqual([S]);
    expect(rolesByPosition(st).map((r) => r.id)).toEqual(["300", S]);
    expect(memberColor(st, "2")).toBe("#ff0000");
    expect(memberColor(st, "3")).toBeUndefined();
  });
  it("computes permissions locally", () => {
    const st = useServers.getState().byId[S]!;
    expect(serverPerms(st, "1")).toBe((1n << 62n) - 1n & serverPerms(st, "1"));
    expect(canI(st, P.MANAGE_MESSAGES)).toBe(true);
    expect(canI(st, P.KICK_MEMBERS)).toBe(false);
    expect(channelPerms(st, "201", "3")).toBe(0n);
    expect(channelPerms(st, "201", "1") > 0n).toBe(true);
    expect(canI(st, P.VIEW_CHANNEL, "201")).toBe(false);
  });
  it("recomputes my permissions when roles change", () => {
    useServers.getState().upsertRole(role("300", 1, DEFAULT_EVERYONE | P.KICK_MEMBERS));
    expect(canI(useServers.getState().byId[S], P.KICK_MEMBERS)).toBe(true);
    useServers.getState().removeRole(S, "300");
    const st = useServers.getState().byId[S]!;
    expect(canI(st, P.KICK_MEMBERS)).toBe(false);
    expect(st.members["2"]!.role_ids).toEqual([]);
    expect(st.me.role_ids).toEqual([]);
  });
  it("tracks members", () => {
    useServers.getState().upsertMember(member("4"), true);
    expect(useServers.getState().byId[S]!.memberCount).toBe(4);
    useServers.getState().upsertMember(member("4", []));
    expect(useServers.getState().byId[S]!.memberCount).toBe(4);
    useServers.getState().removeMember(S, "4", true);
    expect(useServers.getState().byId[S]!.memberCount).toBe(3);
    useServers.getState().remove(S);
    expect(useServers.getState().byId[S]).toBeUndefined();
    expect(useServers.getState().order).toEqual([]);
  });
  it("counts joins and leaves, not updates to members that aren't loaded", () => {
    // a large server: most members are not loaded, so an update is often about someone unknown
    useServers.getState().upsertMember(member("9", ["300"]));
    expect(useServers.getState().byId[S]!.memberCount).toBe(3);
    // kicked here: the member goes at once and member_remove counts them out, whichever comes first
    useServers.getState().removeMember(S, "3");
    expect(useServers.getState().byId[S]!.memberCount).toBe(3);
    useServers.getState().removeMember(S, "3", true);
    expect(useServers.getState().byId[S]!.memberCount).toBe(2);
    // someone never loaded leaves
    useServers.getState().removeMember(S, "77", true);
    expect(useServers.getState().byId[S]!.memberCount).toBe(1);
  });
  it("recomputes my permissions when ownership moves", () => {
    const st = useServers.getState().byId[S]!;
    expect(canI(st, P.KICK_MEMBERS)).toBe(false);
    useServers.getState().updateServer({ ...st.server, owner_id: "2" });
    expect(canI(useServers.getState().byId[S], P.KICK_MEMBERS)).toBe(true);
    useServers.getState().updateServer({ ...st.server, owner_id: "1" });
    expect(canI(useServers.getState().byId[S], P.KICK_MEMBERS)).toBe(false);
  });
});
