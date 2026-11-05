import { describe, expect, it } from "vitest";
import type { Overwrite } from "@/api/types";
import { ALL, DEFAULT_EVERYONE, P, base, canActOn, canManageRole, compute, has, highestPosition, isPrivateChannel, parsePerm, permString } from "./perms";

const S = "1";
const roles = [
  { id: S, position: 0, permissions: DEFAULT_EVERYONE },
  { id: "10", position: 1, permissions: DEFAULT_EVERYONE | P.MANAGE_MESSAGES },
  { id: "11", position: 2, permissions: P.ADMINISTRATOR },
];
const ow = (target_id: string, target_type: "role" | "member", allow: bigint, deny: bigint): Overwrite => ({ channel_id: "5", target_id, target_type, allow: permString(allow), deny: permString(deny) });

describe("perms", () => {
  it("matches the server's bit values", () => {
    expect(permString(P.VIEW_CHANNEL)).toBe("1024");
    expect(permString(P.MODERATE_MEMBERS)).toBe("1099511627776");
    expect(permString(DEFAULT_EVERYONE)).toBe("3460458279375064641");
    expect(permString(ALL)).toBe("3467223593643405311");
    expect(parsePerm("garbage")).toBe(0n);
  });
  it("computes base permissions", () => {
    expect(base(S, roles, { userId: "u", roleIds: [], timedOut: false }, false)).toBe(DEFAULT_EVERYONE);
    expect(has(base(S, roles, { userId: "u", roleIds: ["10"], timedOut: false }, false), P.MANAGE_MESSAGES)).toBe(true);
    expect(base(S, roles, { userId: "u", roleIds: ["11"], timedOut: false }, false)).toBe(ALL);
    expect(base(S, roles, { userId: "u", roleIds: [], timedOut: false }, true)).toBe(ALL);
  });
  it("takes everything but looking from a member in timeout at the server level too, as the server's Base does", () => {
    // a moderator in timeout cannot kick, ban or manage anything until it ends
    const moderator = { userId: "u", roleIds: ["10"], timedOut: true };
    expect(base(S, roles, moderator, false)).toBe(P.VIEW_CHANNEL | P.READ_MESSAGE_HISTORY);
    expect(has(base(S, roles, moderator, false), P.MANAGE_MESSAGES)).toBe(false);
    // an administrator and the owner are never held back
    expect(base(S, roles, { ...moderator, roleIds: ["11"] }, false)).toBe(ALL);
    expect(base(S, roles, moderator, true)).toBe(ALL);
  });
  it("applies overwrites in Discord order", () => {
    const m = { userId: "u", roleIds: ["10"], timedOut: false };
    expect(compute(S, roles, m, false, [ow(S, "role", 0n, P.VIEW_CHANNEL)])).toBe(0n);
    const p = compute(S, roles, m, false, [ow(S, "role", 0n, P.VIEW_CHANNEL), ow("10", "role", P.VIEW_CHANNEL, P.SEND_MESSAGES)]);
    expect(has(p, P.VIEW_CHANNEL)).toBe(true);
    expect(has(p, P.SEND_MESSAGES)).toBe(false);
    const p2 = compute(S, roles, m, false, [ow("10", "role", 0n, P.SEND_MESSAGES), ow("u", "member", P.SEND_MESSAGES, 0n)]);
    expect(has(p2, P.SEND_MESSAGES)).toBe(true);
    expect(compute(S, roles, { ...m, roleIds: ["11"] }, false, [ow(S, "role", 0n, P.VIEW_CHANNEL)])).toBe(ALL);
    expect(compute(S, roles, { ...m, timedOut: true }, false, [])).toBe(P.VIEW_CHANNEL | P.READ_MESSAGE_HISTORY);
  });
  it("hierarchy helpers", () => {
    expect(highestPosition(roles, { userId: "u", roleIds: ["10", "11"], timedOut: false })).toBe(2);
    expect(canManageRole(2, false, 1)).toBe(true);
    expect(canManageRole(1, false, 1)).toBe(false);
    expect(canManageRole(0, true, 5)).toBe(true);
    expect(canActOn(2, 1, false, false)).toBe(true);
    expect(canActOn(2, 1, false, true)).toBe(false);
    expect(canActOn(1, 1, false, false)).toBe(false);
    expect(isPrivateChannel(S, [ow(S, "role", 0n, P.VIEW_CHANNEL)])).toBe(true);
    expect(isPrivateChannel(S, [ow("10", "role", 0n, P.VIEW_CHANNEL)])).toBe(false);
  });
});
