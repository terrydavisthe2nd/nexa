import { describe, expect, it } from "vitest";
import { mentionsPath } from "@/api/mentions";
import { appendPage, mentionOf } from "./mentions";

describe("mentionOf", () => {
  const m = (mentions: string[], roles: string[], everyone = false) => ({ mentions, mention_roles: roles, mention_everyone: everyone });
  it("prefers you, then your role, then everyone", () => {
    expect(mentionOf(m(["1"], ["r"], true), "1", ["r"])).toEqual({ kind: "me" });
    expect(mentionOf(m([], ["x", "r"], true), "1", ["r"])).toEqual({ kind: "role", roleId: "r" });
    expect(mentionOf(m([], ["x"], true), "1", ["r"])).toEqual({ kind: "everyone" });
    expect(mentionOf(m([], ["x"]), "1", [])).toEqual({ kind: "role", roleId: "x" });
  });
});

describe("mentionsPath", () => {
  it("asks for what was chosen", () => {
    expect(mentionsPath({})).toBe("/users/@me/mentions");
    expect(mentionsPath({ kinds: ["me", "roles", "everyone"], sort: "newest", cursor: "9" })).toBe("/users/@me/mentions?before=9");
    expect(mentionsPath({ kinds: ["everyone"], place: "@me", sort: "oldest", cursor: "9", limit: 25 })).toBe("/users/@me/mentions?kinds=everyone&server_id=%40me&sort=oldest&after=9&limit=25");
  });
});

describe("appendPage", () => {
  it("drops repeats", () => {
    expect(appendPage([{ id: "1" }, { id: "2" }], [{ id: "2" }, { id: "3" }]).map((x) => x.id)).toEqual(["1", "2", "3"]);
  });
});
