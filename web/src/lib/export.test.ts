import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/client";
import { allMessages, buildZip, crc32, exportMyData, type Pace } from "./export";
import { useSettings } from "@/stores/settings";

// A channel of 250 messages with ids 1…250, served the way the server does:
// newest page first, each page sorted oldest first.
async function history(_channelId: string, q: { limit: number; before?: string }) {
  const top = q.before ? Number(q.before) - 1 : 250;
  const ids: number[] = [];
  for (let id = Math.max(1, top - q.limit + 1); id <= top; id++) ids.push(id);
  return ids.map((id) => ({ id: String(id), author_id: "9", type: 0, created_at: id * 1000, edited_at: null, reply_to_id: null, reactions: [] }));
}
const server = vi.hoisted(() => ({ list: null as null | ((channelId: string, q: { limit: number; before?: string }) => Promise<unknown[]>) }));
vi.mock("@/api/messages", () => ({
  messages: { list: (channelId: string, q: { limit: number; before?: string }) => (server.list ?? history)(channelId, q) },
}));
vi.mock("@/lib/decrypt", () => ({ decryptForStore: async (m: object) => ({ ...m, payload: { t: "hi" } }) }));
vi.mock("@/api/users", () => ({ users: { me: async () => ({ id: "me", username: "me" }), mySharedGames: async () => ({ games: [] }) } }));
vi.mock("@/api/relationships", () => ({ relationships: { list: async () => [] } }));
vi.mock("@/api/channels", () => ({
  channels: {
    listDMs: async () => [
      { id: "dm1", type: "dm", name: "", server_id: null, recipient_ids: ["me", "ann"] },
      { id: "gone", type: "dm", name: "", server_id: null, recipient_ids: ["me", "bob"] },
    ],
  },
}));

const enc = (s: string) => new TextEncoder().encode(s);

function u32(b: Uint8Array, at: number): number {
  return new DataView(b.buffer, b.byteOffset, b.byteLength).getUint32(at, true);
}

/** A clock that never waits, and remembers what it was asked to wait. */
function fakePace(): Pace & { waits: number[] } {
  let now = 0;
  const waits: number[] = [];
  return {
    last: 0,
    waits,
    now: () => now,
    sleep: async (ms) => {
      waits.push(ms);
      now += ms;
    },
  };
}

/** The names of the files inside a store-only zip. */
function names(zip: Uint8Array): string[] {
  const out: string[] = [];
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  let at = 0;
  while (view.getUint32(at, true) === 0x04034b50) {
    const size = view.getUint32(at + 18, true);
    const nameLen = view.getUint16(at + 26, true);
    out.push(new TextDecoder().decode(zip.subarray(at + 30, at + 30 + nameLen)));
    at += 30 + nameLen + size;
  }
  return out;
}

function fileIn(zip: Uint8Array, name: string): string {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  let at = 0;
  while (view.getUint32(at, true) === 0x04034b50) {
    const size = view.getUint32(at + 18, true);
    const nameLen = view.getUint16(at + 26, true);
    const here = new TextDecoder().decode(zip.subarray(at + 30, at + 30 + nameLen));
    if (here === name) return new TextDecoder().decode(zip.subarray(at + 30 + nameLen, at + 30 + nameLen + size));
    at += 30 + nameLen + size;
  }
  return "";
}

describe("zip writer", () => {
  it("computes the standard CRC-32", () => {
    // the canonical check value for "123456789"
    expect(crc32(enc("123456789"))).toBe(0xcbf43926);
    expect(crc32(new Uint8Array())).toBe(0);
  });
  it("writes a readable store-only archive", () => {
    const zip = buildZip([
      { name: "a.txt", data: enc("hello") },
      { name: "dir/b.json", data: enc("{}") },
    ]);
    expect(u32(zip, 0)).toBe(0x04034b50); // first local header
    // end-of-central-directory sits in the last 22 bytes and counts both entries
    const end = zip.length - 22;
    expect(u32(zip, end)).toBe(0x06054b50);
    const ev = new DataView(zip.buffer, zip.byteOffset + end, 22);
    expect(ev.getUint16(8, true)).toBe(2);
    expect(u32(zip, 14)).toBe(crc32(enc("hello")));
  });
  it("refuses more entries than a plain zip can count, rather than writing a broken one", () => {
    const many = Array.from({ length: 0x10000 }, (_, i) => ({ name: `${i}`, data: new Uint8Array(0) }));
    expect(() => buildZip(many)).toThrow(/too much/i);
  });
});

describe("channel history export", () => {
  afterEach(() => {
    server.list = null;
  });

  it("omits the app-lock passcode verifier from plaintext exports", async () => {
    const previous = useSettings.getState().settings;
    useSettings.setState({ settings: { ...previous, appLock: { salt: "private-lock-salt", params: { m: 65536, t: 6, p: 1, v: 1 }, verifier: "private-lock-verifier", autoLockMs: 300000, lockOnBlur: false, blurDelayMs: 0 } } });
    server.list = async () => [];
    try {
      const zip = await exportMyData(() => undefined);
      const settings = JSON.parse(fileIn(zip, "settings.json"));
      expect(settings).not.toHaveProperty("appLock");
      expect(settings.theme).toBe(previous.theme);
      const contents = new TextDecoder().decode(zip);
      expect(contents).not.toContain("private-lock-verifier");
      expect(contents).not.toContain("private-lock-salt");
      expect(useSettings.getState().settings.appLock?.verifier).toBe("private-lock-verifier");
    } finally {
      useSettings.setState({ settings: previous });
    }
  });

  it("keeps messages oldest first across pages", async () => {
    const progress: number[] = [];
    const rows = (await allMessages("1", (n) => progress.push(n), fakePace())) as { id: string }[];
    expect(rows.map((r) => Number(r.id))).toEqual(Array.from({ length: 250 }, (_, i) => i + 1));
    expect(progress).toEqual([100, 200, 250]);
  });

  it("paces its requests, and waits out the server asking it to slow down", async () => {
    let refusals = 0;
    server.list = async (channelId, q) => {
      if (q.before === "151" && refusals < 2) {
        refusals += 1;
        throw new ApiError(429, "rate_limited", "slow down", 3);
      }
      return history(channelId, q);
    };
    const pace = fakePace();
    const rows = await allMessages("1", () => undefined, pace);
    expect(rows).toHaveLength(250);
    expect(refusals).toBe(2);
    // the server's own three seconds, then the next wait up
    expect(pace.waits.filter((w) => w >= 1000)).toEqual([3000, 3000]);
    // and a gap between every two requests
    expect(pace.waits.filter((w) => w < 1000).length).toBeGreaterThan(0);
  });

  it("leaves out a conversation it can no longer read and says so, and fails on anything else", async () => {
    server.list = async (channelId, q) => {
      if (channelId === "gone") throw new ApiError(403, "no_access", "no");
      return history(channelId, q);
    };
    const zip = await exportMyData(() => undefined);
    expect(names(zip)).toContain("messages/dm/dm1-dm1.json");
    expect(names(zip).some((n) => n.includes("gone"))).toBe(false);
    expect(fileIn(zip, "README.txt")).toMatch(/could not be read any more[\s\S]*\(gone\)/);

    // a server failing for good is not a conversation to skip quietly
    server.list = async (channelId, q) => {
      if (channelId === "gone") throw new ApiError(400, "bad_request", "The server turned that down.");
      return history(channelId, q);
    };
    await expect(exportMyData(() => undefined)).rejects.toThrow("The server turned that down.");
  }, 20_000);
});
