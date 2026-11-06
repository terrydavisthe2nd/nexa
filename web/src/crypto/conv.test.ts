import { equal, randomBytes } from "./bytes";
import { KeyRing, channelKey, keyCommitment, newRootKey } from "./conv";

describe("newRootKey", () => {
  it("returns 32 fresh random bytes", () => {
    const a = newRootKey();
    expect(a.length).toBe(32);
    expect(equal(a, newRootKey())).toBe(false);
  });
});

describe("keyCommitment", () => {
  it("matches what the Android app computes", () => {
    // web-crypto-vectors.json holds the same root and result for CryptoVectorsTest
    const root = Uint8Array.from("656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e".match(/../g)!.map((x) => parseInt(x, 16)));
    const hex = Array.from(keyCommitment(root, "99723385805733891"), (x) => x.toString(16).padStart(2, "0")).join("");
    expect(hex).toBe("bbca7e07983fad3d35e036b14333027a60c81880f38bfd2c1989e409c4eaac11");
  });

  it("is not the key that opens messages", () => {
    const root = newRootKey();
    expect(equal(keyCommitment(root, "c"), channelKey(root, "c"))).toBe(false);
  });
});

describe("channelKey", () => {
  const root = new Uint8Array(32).fill(11);

  it("is deterministic and 32 bytes", () => {
    const k = channelKey(root, "general");
    expect(k.length).toBe(32);
    expect(equal(k, channelKey(root, "general"))).toBe(true);
  });

  it("differs per channel", () => {
    expect(equal(channelKey(root, "general"), channelKey(root, "random"))).toBe(false);
    expect(equal(channelKey(root, "a"), channelKey(root, "ab"))).toBe(false);
    expect(equal(channelKey(root, ""), channelKey(root, "general"))).toBe(false);
  });

  it("differs per root key", () => {
    expect(equal(channelKey(root, "general"), channelKey(new Uint8Array(32).fill(12), "general"))).toBe(false);
    expect(equal(channelKey(newRootKey(), "general"), channelKey(newRootKey(), "general"))).toBe(false);
  });
});

describe("KeyRing", () => {
  it("stores and reads a root per conversation and epoch", () => {
    const ring = new KeyRing();
    const r0 = newRootKey();
    const r1 = newRootKey();
    ring.set("conv-a", 0, r0);
    ring.set("conv-a", 1, r1);
    expect(equal(ring.get("conv-a", 0) ?? new Uint8Array(0), r0)).toBe(true);
    expect(equal(ring.get("conv-a", 1) ?? new Uint8Array(0), r1)).toBe(true);
  });

  it("returns undefined for unknown conversations and epochs", () => {
    const ring = new KeyRing();
    ring.set("conv-a", 0, newRootKey());
    expect(ring.get("conv-a", 5)).toBeUndefined();
    expect(ring.get("conv-b", 0)).toBeUndefined();
    expect(ring.latest("conv-b")).toBeUndefined();
  });

  it("overwrites an existing epoch", () => {
    const ring = new KeyRing();
    const first = newRootKey();
    const second = newRootKey();
    ring.set("conv-a", 3, first);
    ring.set("conv-a", 3, second);
    expect(equal(ring.get("conv-a", 3) ?? new Uint8Array(0), second)).toBe(true);
    expect(ring.epochs("conv-a")).toEqual([3]);
  });

  it("reports the highest epoch as the latest, whatever the insertion order", () => {
    const ring = new KeyRing();
    const r7 = newRootKey();
    ring.set("conv-a", 2, newRootKey());
    ring.set("conv-a", 7, r7);
    ring.set("conv-a", 5, newRootKey());
    const latest = ring.latest("conv-a");
    expect(latest?.epoch).toBe(7);
    expect(equal(latest?.root ?? new Uint8Array(0), r7)).toBe(true);
  });

  it("tracks membership", () => {
    const ring = new KeyRing();
    expect(ring.has("conv-a")).toBe(false);
    ring.set("conv-a", 0, newRootKey());
    expect(ring.has("conv-a")).toBe(true);
    expect(ring.has("conv-b")).toBe(false);
  });

  it("lists conversations and ascending epochs", () => {
    const ring = new KeyRing();
    ring.set("conv-b", 4, newRootKey());
    ring.set("conv-a", 9, newRootKey());
    ring.set("conv-a", 1, newRootKey());
    ring.set("conv-a", 3, newRootKey());
    expect(ring.convs().sort()).toEqual(["conv-a", "conv-b"]);
    expect(ring.epochs("conv-a")).toEqual([1, 3, 9]);
    expect(ring.epochs("conv-b")).toEqual([4]);
    expect(ring.epochs("conv-missing")).toEqual([]);
  });

  it("clears everything", () => {
    const ring = new KeyRing();
    ring.set("conv-a", 0, newRootKey());
    ring.set("conv-b", 1, newRootKey());
    ring.clear();
    expect(ring.convs()).toEqual([]);
    expect(ring.has("conv-a")).toBe(false);
    expect(ring.get("conv-a", 0)).toBeUndefined();
    expect(ring.latest("conv-b")).toBeUndefined();
  });

  it("keeps separate instances independent", () => {
    const a = new KeyRing();
    const b = new KeyRing();
    const root = randomBytes(32);
    a.set("conv-a", 0, root);
    expect(b.has("conv-a")).toBe(false);
    expect(b.convs()).toEqual([]);
    expect(b.get("conv-a", 0)).toBeUndefined();
    b.clear();
    expect(equal(a.get("conv-a", 0) ?? new Uint8Array(0), root)).toBe(true);
  });
});
