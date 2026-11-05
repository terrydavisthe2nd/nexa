import { describe, expect, it } from "vitest";
import { COPIES_FRESH_MS, CopyHistory, MAX_COPY_AGE, MAX_PACKET_WITH_COPIES, Mender, copiesThatFit, joinFrames, packetSamples, unwrapCopies, wrapCopies, type Copy } from "./redundancy";

/** A code 0 Opus packet: a TOC byte (SILK wideband 20 ms here) and a frame. */
const opus = (len: number, seed = 1) => Uint8Array.from({ length: len }, (_, i) => (i === 0 ? 0x48 : (i * 31 + seed) & 0xff));

/** How long an Opus decoder takes the frame to be, following RFC 6716 for code 0 and code 3 packets. */
function decodedFrame(packet: Uint8Array): Uint8Array {
  const code = packet[0]! & 3;
  if (code === 0) return packet.subarray(1);
  if (code !== 3) throw new Error("unexpected code");
  const fc = packet[1]!;
  const count = fc & 0x3f;
  let at = 2;
  let padding = 0;
  if (fc & 0x40) {
    for (;;) {
      const b = packet[at++]!;
      padding += b === 255 ? 254 : b;
      if (b !== 255) break;
    }
  }
  const size = (packet.length - at - padding) / count;
  return packet.subarray(at, at + size);
}

describe("copies in Opus padding", () => {
  it("leave the frame an ordinary decoder sees exactly as it was", () => {
    const primary = opus(120);
    const wrapped = wrapCopies(primary, [{ dts: 960, data: opus(110, 7) }, { dts: 1920, data: opus(90, 9) }])!;
    expect(wrapped[0]! & 3).toBe(3);
    expect(wrapped[0]! & ~3).toBe(primary[0]);
    expect(decodedFrame(wrapped)).toEqual(primary.subarray(1));
  });

  it("come back out, with the frame as the encoder made it", () => {
    const primary = opus(150);
    const a = opus(140, 3);
    const b = opus(130, 5);
    const { primary: back, copies } = unwrapCopies(wrapCopies(primary, [{ dts: 960, data: a }, { dts: 1920, data: b }])!);
    expect(back).toEqual(primary);
    expect(copies).toEqual([{ dts: 960, data: a }, { dts: 1920, data: b }]);
  });

  it("use more than one byte for padding past 254 bytes", () => {
    const primary = opus(300);
    const big = opus(600, 11);
    const wrapped = wrapCopies(primary, [{ dts: 960, data: big }])!;
    expect(wrapped[2]).toBe(255);
    expect(decodedFrame(wrapped)).toEqual(primary.subarray(1));
    expect(unwrapCopies(wrapped).copies[0]!.data).toEqual(big);
  });

  it("leave packets alone that are not a plain frame, are silence, or were not wrapped here", () => {
    expect(wrapCopies(Uint8Array.of(0x49, 1, 2, 3, 4), [{ dts: 960, data: opus(50) }])).toBeNull();
    expect(wrapCopies(Uint8Array.of(0x48, 1), [{ dts: 960, data: opus(50) }])).toBeNull();
    expect(wrapCopies(opus(80), [])).toBeNull();
    const plain = opus(90);
    expect(unwrapCopies(plain)).toEqual({ primary: plain, copies: [] });
    // an encoder's own padding, zeros, is not taken for copies
    const padded = Uint8Array.of(0x4b, 0x41, 5, 1, 2, 3, 0, 0, 0, 0, 0);
    expect(unwrapCopies(padded)).toEqual({ primary: padded, copies: [] });
    // a cut-off packet is never read past its end
    const wrapped = wrapCopies(opus(100), [{ dts: 960, data: opus(100, 2) }])!;
    expect(unwrapCopies(wrapped.subarray(0, wrapped.length - 30)).copies).toEqual([]);
  });

  it("are the frames just before, and never ones too old to use", () => {
    const h = new CopyHistory(2);
    expect(h.take(1000, opus(50, 1))).toEqual([]);
    expect(h.take(1960, opus(50, 2)).map((c) => c.dts)).toEqual([960]);
    expect(h.take(2920, opus(50, 3)).map((c) => c.dts)).toEqual([960, 1920]);
    // a pause (the microphone gated) leaves nothing recent enough
    expect(h.take(2920 + MAX_COPY_AGE + 1000, opus(50, 4))).toEqual([]);
    // RTP timestamps wrap around
    const w = new CopyHistory(1);
    w.take(0xffffff00, opus(40));
    expect(w.take(0x2c0, opus(40)).map((c) => c.dts)).toEqual([960]);
  });

});

/** The frames an Opus decoder finds in a packet (RFC 6716 section 3.2), for code 0 and code 3 VBR packets. */
function framesOf(packet: Uint8Array): Uint8Array[] {
  if ((packet[0]! & 3) === 0) return [packet.subarray(1)];
  const count = packet[1]! & 0x3f;
  expect(packet[1]! & 0xc0).toBe(0x80);
  let at = 2;
  const lengths: number[] = [];
  for (let i = 0; i < count - 1; i++) {
    const b = packet[at++]!;
    lengths.push(b < 252 ? b : b + 4 * packet[at++]!);
  }
  const out: Uint8Array[] = [];
  for (const len of lengths) {
    out.push(packet.subarray(at, at + len));
    at += len;
  }
  out.push(packet.subarray(at));
  return out;
}

describe("lost frames played from copies", () => {
  const T = 960;
  /** A received frame: an id to follow it by, its timestamp, its own voice and the copies it carries of the frames before. */
  const frame = (n: number, copies: number[] = []) => ({ id: n, ts: 50_000 + n * T, data: opus(60 + n, n), copies: copies.map((k): Copy => ({ dts: k * T, data: opus(60 + n - k, n - k) })) });
  type F = ReturnType<typeof frame>;
  const push = (m: Mender<F>, f: F, now: number, ssrc = 1) => m.push(f, ssrc, f.ts, f.data, f.copies, now).map((x) => ({ id: x.frame.id, frames: framesOf(x.data) }));
  /** A mender that has seen copies and a missing frame, so it holds frames from here on. */
  function lossy(): { m: Mender<F>; at: number } {
    const m = new Mender<F>();
    expect(push(m, frame(0, []), 0).map((x) => x.id)).toEqual([0]);
    // frame 1 is lost; frame 2 brings the first copies, and is held for the next
    expect(push(m, frame(2, [1, 2]), 40)).toEqual([]);
    return { m, at: 60 };
  }

  it("pass straight through while nothing is lost or no copies come", () => {
    const m = new Mender<F>();
    expect(push(m, frame(0, [1]), 0).map((x) => x.id)).toEqual([0]);
    expect(push(m, frame(1, [1]), 20).map((x) => x.id)).toEqual([1]);
    // a lost frame, but the speaker sends no copies: nothing to wait for
    const plain = new Mender<F>();
    push(plain, frame(0), 0);
    expect(push(plain, frame(2), 40).map((x) => x.id)).toEqual([2]);
    expect(push(plain, frame(3), 60).map((x) => x.id)).toEqual([3]);
    expect(plain.holding).toBe(false);
  });

  it("join a lost frame's copy to the frame before it", () => {
    const { m, at } = lossy();
    expect(m.holding).toBe(true);
    expect(push(m, frame(3, [1, 2]), at)).toEqual([{ id: 2, frames: [frame(2).data.subarray(1)] }]);
    // frame 4 is lost; frame 5 carries it
    const out = push(m, frame(5, [1, 2]), at + 40);
    expect(out.map((x) => x.id)).toEqual([3]);
    expect(out[0]!.frames).toEqual([frame(3).data.subarray(1), frame(4).data.subarray(1)]);
    expect(m.recovered).toBe(1);
  });

  it("join two lost frames, oldest first, and stop at one with no copy", () => {
    const { m, at } = lossy();
    push(m, frame(3, [1, 2]), at);
    const two = push(m, frame(6, [1, 2]), at + 60);
    expect(two[0]!.id).toBe(3);
    expect(two[0]!.frames).toEqual([frame(3).data.subarray(1), frame(4).data.subarray(1), frame(5).data.subarray(1)]);
    // three lost: the copies reach back two, so the oldest is not there and nothing can follow on
    const none = push(m, frame(10, [1, 2]), at + 140);
    expect(none).toEqual([{ id: 6, frames: [frame(6).data.subarray(1)] }]);
    expect(m.recovered).toBe(2);
  });

  it("make packets an Opus decoder reads, long frames included", () => {
    const a = opus(400, 1);
    const b = opus(90, 2);
    const c = opus(300, 3);
    const joined = joinFrames([a, b, c]);
    expect(joined[0]! & 3).toBe(3);
    expect(joined[0]! & ~3).toBe(a[0]);
    expect(framesOf(joined)).toEqual([a.subarray(1), b.subarray(1), c.subarray(1)]);
    expect(packetSamples(joined)).toBe(3 * 960);
    expect(packetSamples(a)).toBe(960);
  });

  it("do not join frames of another configuration", () => {
    const { m, at } = lossy();
    push(m, frame(3, [1, 2]), at);
    const f5 = frame(5, [1, 2]);
    // the lost frame 4 was CELT, 20 ms (config 31) where the rest are SILK
    f5.copies[0]!.data[0] = 31 << 3;
    expect(push(m, f5, at + 40)).toEqual([{ id: 3, frames: [frame(3).data.subarray(1)] }]);
  });

  it("keep late frames in order, and drop the ones already played from copies", () => {
    const { m, at } = lossy();
    push(m, frame(3, [1, 2]), at);
    push(m, frame(5, [1, 2]), at + 40);
    // frame 4 was only late: its copy already went out with frame 3
    expect(push(m, frame(4, [1, 2]), at + 45)).toEqual([]);
    // frame 7 arrives before 6: 6 comes after it, and the held frame goes first
    push(m, frame(7, []), at + 80);
    const late = push(m, frame(6, []), at + 85);
    expect(late.map((x) => x.id)).toEqual([7, 6]);
    expect(m.holding).toBe(false);
    // a repeat of the frame being held is dropped
    push(m, frame(8, [1]), at + 100);
    expect(push(m, frame(8, [1]), at + 101)).toEqual([]);
  });

  it("start over on a new stream or after a pause, without joining across", () => {
    const { m, at } = lossy();
    push(m, frame(3, [1, 2]), at);
    const other = frame(4, [1, 2]);
    expect(push(m, other, at + 20, 2).map((x) => x.id)).toEqual([3]);
    // a pause of a second: not a loss, so nothing is joined
    const after = frame(60, [1, 2]);
    expect(push(m, after, at + 1200, 2)).toEqual([{ id: 4, frames: [other.data.subarray(1)] }]);
  });

  it("stop holding once copies stop coming, and flush what is held", () => {
    const { m, at } = lossy();
    push(m, frame(3, [1]), at);
    expect(m.flush().map((x) => x.frame.id)).toEqual([3]);
    expect(m.flush()).toEqual([]);
    const later = at + COPIES_FRESH_MS + 1;
    expect(push(m, frame(4), later).map((x) => x.id)).toEqual([4]);
    expect(m.holding).toBe(false);
  });
});

describe("copies of a stereo voice", () => {
  it("never grow a packet past what a network carries whole", () => {
    const frame = new Uint8Array(400);
    const fit = copiesThatFit(400, [{ dts: 960, data: frame }, { dts: 1920, data: frame }, { dts: 2880, data: frame }]);
    expect(fit.map((c) => c.dts)).toEqual([960]);
    expect(fit.reduce((n, c) => n + c.data.length, 400)).toBeLessThanOrEqual(MAX_PACKET_WITH_COPIES);
  });
});
