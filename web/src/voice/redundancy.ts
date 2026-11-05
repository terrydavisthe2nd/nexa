/**
 * Redundant voice for lossy links, carried where decoders ignore it.
 *
 * On a lossy link Opus leans on redundancy: RED (RFC 2198) repeats the last
 * frame in each packet, and Opus's own FEC hides a lighter copy in the next
 * one. Neither survives Cloudflare's SFU. It answers without RED, and a
 * listener's losses never reach the speaker's encoder through it, so FEC
 * stays off for everything lost on the way down.
 *
 * So each voice frame carries whole copies of the frame or two before it in
 * Opus padding (RFC 6716 section 3.2.5: a code 3 packet of one frame plus
 * padding). Every Opus decoder skips padding, so a client that knows nothing
 * of this plays the frame exactly as before. One that does watches the RTP
 * timestamps, and when frames went missing just before one that arrived, it
 * puts their copies back ahead of it for the decoder to play. The copies sit
 * inside the frame's end-to-end encryption, like the frame itself.
 */

/** "EV", then a version: what marks padding as ours rather than an encoder's own. */
const MAGIC_0 = 0x45;
const MAGIC_1 = 0x56;
const VERSION = 1;
/** Copies older than this many samples (60 ms at 48 kHz) are not worth sending: the listener has played past them. */
export const MAX_COPY_AGE = 2880;
/** A frame of this many bytes or fewer is silence or DTX: nothing to repeat. */
const TINY = 2;

export interface Copy {
  /** How many RTP timestamp units (samples) before the frame carrying it this copy belongs. */
  dts: number;
  data: Uint8Array;
}

/**
 * An Opus packet carrying `copies` in its padding, or null when the packet is
 * not a single plain frame (code 0) that can be wrapped.
 */
export function wrapCopies(packet: Uint8Array, copies: Copy[]): Uint8Array | null {
  if (packet.length <= TINY || (packet[0]! & 3) !== 0) return null;
  const useful = copies.filter((c) => c.dts > 0 && c.dts <= 0xffff && c.data.length > TINY && c.data.length <= 0xffff);
  if (useful.length === 0) return null;
  let blobLen = 4;
  for (const c of useful) blobLen += 4 + c.data.length;
  // the padding length is a run of bytes: 255 stands for 254 and another byte follows
  const lenBytes: number[] = [];
  let rest = blobLen;
  while (rest >= 255) {
    lenBytes.push(255);
    rest -= 254;
  }
  lenBytes.push(rest);
  const out = new Uint8Array(2 + lenBytes.length + (packet.length - 1) + blobLen);
  let at = 0;
  out[at++] = packet[0]! | 3;
  // CBR, padding, one frame
  out[at++] = 0x41;
  for (const b of lenBytes) out[at++] = b;
  out.set(packet.subarray(1), at);
  at += packet.length - 1;
  out[at++] = MAGIC_0;
  out[at++] = MAGIC_1;
  out[at++] = VERSION;
  out[at++] = useful.length;
  for (const c of useful) {
    out[at++] = c.dts >> 8;
    out[at++] = c.dts & 0xff;
    out[at++] = c.data.length >> 8;
    out[at++] = c.data.length & 0xff;
    out.set(c.data, at);
    at += c.data.length;
  }
  return out;
}

/**
 * Splits a packet made by wrapCopies back into the frame as the encoder made
 * it and the copies it carried. Anything else comes back as it is, with none.
 */
export function unwrapCopies(packet: Uint8Array): { primary: Uint8Array; copies: Copy[] } {
  const none = { primary: packet, copies: [] as Copy[] };
  if (packet.length < 3 || (packet[0]! & 3) !== 3 || packet[1] !== 0x41) return none;
  let at = 2;
  let padding = 0;
  for (;;) {
    if (at >= packet.length) return none;
    const b = packet[at++]!;
    padding += b === 255 ? 254 : b;
    if (b !== 255) break;
  }
  const blobAt = packet.length - padding;
  if (padding < 4 || blobAt < at || packet[blobAt] !== MAGIC_0 || packet[blobAt + 1] !== MAGIC_1 || packet[blobAt + 2] !== VERSION) return none;
  const copies: Copy[] = [];
  let p = blobAt + 4;
  for (let i = 0; i < packet[blobAt + 3]!; i++) {
    if (p + 4 > packet.length) return none;
    const dts = (packet[p]! << 8) | packet[p + 1]!;
    const len = (packet[p + 2]! << 8) | packet[p + 3]!;
    p += 4;
    if (p + len > packet.length) return none;
    copies.push({ dts, data: packet.slice(p, p + len) });
    p += len;
  }
  const primary = new Uint8Array(1 + (blobAt - at));
  primary[0] = packet[0]! & ~3;
  primary.set(packet.subarray(at, blobAt), 1);
  return { primary, copies };
}

/** What a sender keeps: its last few frames as the encoder made them, with their RTP timestamps. */
/**
 * The most a voice packet may grow to with its copies: past a network's MTU a
 * packet is split or lost whole, taking the frame with it. A stereo voice's
 * frames are far larger than a mono one's, so two copies can reach it.
 */
export const MAX_PACKET_WITH_COPIES = 1100;

/** As many of `copies`, newest first, as fit a packet with a `primary`-byte frame. */
export function copiesThatFit(primary: number, copies: Copy[]): Copy[] {
  let used = primary;
  const out: Copy[] = [];
  for (const copy of copies) {
    used += copy.data.length;
    if (used > MAX_PACKET_WITH_COPIES) break;
    out.push(copy);
  }
  return out;
}

export class CopyHistory {
  private frames: { ts: number; data: Uint8Array }[] = [];

  constructor(private readonly depth: number) {}

  /** Copies of the frames before one at `ts`, newest first, then remembers this one. */
  take(ts: number, packet: Uint8Array): Copy[] {
    const copies: Copy[] = [];
    for (let i = this.frames.length - 1; i >= 0 && copies.length < this.depth; i--) {
      const f = this.frames[i]!;
      const dts = (ts - f.ts) >>> 0;
      if (dts > 0 && dts <= MAX_COPY_AGE) copies.push({ dts, data: f.data });
    }
    this.frames.push({ ts, data: packet.slice() });
    if (this.frames.length > this.depth) this.frames.shift();
    return copies;
  }
}

/** Samples (at 48 kHz) in one frame of an Opus TOC byte's configuration (RFC 6716 section 3.1). */
function frameSamples(toc: number): number {
  const config = toc >> 3;
  if (config < 12) return [480, 960, 1920, 2880][config & 3]!;
  if (config < 16) return config & 1 ? 960 : 480;
  return [120, 240, 480, 960][config & 3]!;
}

/** Samples in a whole Opus packet, or null when it is too short to say. */
export function packetSamples(packet: Uint8Array): number | null {
  if (packet.length === 0) return null;
  const code = packet[0]! & 3;
  const frames = code === 0 ? 1 : code < 3 ? 2 : packet.length > 1 ? packet[1]! & 0x3f : 0;
  return frames > 0 ? frames * frameSamples(packet[0]!) : null;
}

/** An Opus packet may hold at most 120 ms (RFC 6716 section 3.2.5), and one frame at most 1275 bytes. */
const MAX_PACKET_SAMPLES = 5760;
const MAX_FRAME_BYTES = 1275;
/**
 * A jump in timestamps longer than this is frames that stopped (a pause, a new
 * stream) rather than went missing: copies reach back 60 ms at most.
 */
const MAX_GAP = 5760;
/** A receiver holds frames back only while its speaker's frames carried copies this recently, */
export const COPIES_FRESH_MS = 10_000;
/** and frames went missing on the way this recently: holding costs every frame a little delay. */
export const GAP_FRESH_MS = 30_000;

/** Plain frames (code 0) of one configuration as a single packet (code 3, VBR). */
export function joinFrames(frames: Uint8Array[]): Uint8Array {
  const lengths: number[] = [];
  for (const f of frames.slice(0, -1)) {
    const len = f.length - 1;
    if (len < 252) lengths.push(len);
    else lengths.push(252 + (len & 3), (len - 252 - (len & 3)) >> 2);
  }
  let size = 2 + lengths.length;
  for (const f of frames) size += f.length - 1;
  const out = new Uint8Array(size);
  out[0] = (frames[0]![0]! & ~3) | 3;
  out[1] = 0x80 | frames.length;
  out.set(lengths, 2);
  let at = 2 + lengths.length;
  for (const f of frames) {
    out.set(f.subarray(1), at);
    at += f.length - 1;
  }
  return out;
}

export interface Mended<F> {
  frame: F;
  /** What the frame is to carry on: its own voice, and any lost just after it. */
  data: Uint8Array;
}

interface Held<F> {
  frame: F;
  ts: number;
  /** The timestamp just past everything it carries. */
  end: number;
  frames: Uint8Array[];
  samples: number;
}

/**
 * What a receiver does with the copies: puts lost frames back.
 *
 * A browser only plays frames that came from the network, in the order they
 * came (Chromium drops made-up frames and frames passed on after a later
 * one), and none of their timestamps can change. So a lost frame can only be
 * played inside the frame before it. While copies are coming and frames go
 * missing, each frame is held until the next one arrives. When frames are
 * missing between the two, their copies (carried by the one that arrived) join
 * the held frame as one Opus packet of several frames, which plays at the held
 * frame's time and fills the gap. The cost is one frame of delay (20 ms) while
 * the link is lossy.
 */
export class Mender<F> {
  private held: Held<F> | null = null;
  /** The timestamp just past the last frame passed on, while known. */
  private lastEnd: number | null = null;
  private ssrc: number | null = null;
  private copiesAt = Number.NEGATIVE_INFINITY;
  private gapAt = Number.NEGATIVE_INFINITY;
  /** Timestamps of frames lately played from copies, so the late originals are not played again. */
  private filled: number[] = [];
  /** Frames that went missing and were played from copies. */
  recovered = 0;

  get holding(): boolean {
    return this.held !== null;
  }

  /**
   * Takes a frame that arrived (`data` as its encoder made it, with the copies
   * it carried) and returns what to pass on now, in order.
   */
  push(frame: F, ssrc: number, ts: number, data: Uint8Array, copies: Copy[], now: number): Mended<F>[] {
    const out: Mended<F>[] = [];
    if (copies.length > 0) this.copiesAt = now;
    if (this.ssrc !== ssrc) {
      this.release(out);
      this.lastEnd = null;
      this.filled = [];
      this.ssrc = ssrc;
    }
    const samples = packetSamples(data);
    const after = this.held ? this.held.end : this.lastEnd;
    if (after !== null) {
      const gap = (ts - after) | 0;
      if (gap < 0 && gap > -MAX_GAP) {
        // an older frame, late: it is already played from its copy, or part of the held frame, or it goes after the held one
        if (this.filled.includes(ts) || (this.held && ((ts - this.held.ts) | 0) >= 0)) return out;
        this.release(out);
        out.push({ frame, data });
        return out;
      }
      if (gap > 0 && gap <= MAX_GAP) {
        this.gapAt = now;
        if (this.held) this.fill(this.held, ts, copies);
      }
      this.release(out);
    }
    const plain = samples !== null && (data[0]! & 3) === 0;
    if (plain && now - this.copiesAt < COPIES_FRESH_MS && now - this.gapAt < GAP_FRESH_MS) {
      this.held = { frame, ts, end: (ts + samples) >>> 0, frames: [data], samples };
    } else {
      out.push({ frame, data });
      this.lastEnd = samples !== null ? (ts + samples) >>> 0 : null;
    }
    return out;
  }

  /** The held frame, to pass on without waiting any longer. */
  flush(): Mended<F>[] {
    const out: Mended<F>[] = [];
    this.release(out);
    return out;
  }

  /** Joins the copies of the frames missing between the held frame and one at `ts` to the held frame, while they follow on. */
  private fill(held: Held<F>, ts: number, copies: Copy[]): void {
    const toc = held.frames[0]![0]! & ~3;
    while (((ts - held.end) | 0) > 0) {
      const at = held.end;
      const copy = copies.find((c) => ((ts - c.dts) >>> 0) === at)?.data;
      if (!copy || copy.length < 2 || (copy[0]! & 3) !== 0 || (copy[0]! & ~3) !== toc || copy.length - 1 > MAX_FRAME_BYTES || held.frames[0]!.length - 1 > MAX_FRAME_BYTES) return;
      const samples = frameSamples(copy[0]!);
      if (held.samples + samples > MAX_PACKET_SAMPLES) return;
      held.frames.push(copy);
      held.samples += samples;
      held.end = (at + samples) >>> 0;
      this.recovered++;
      this.filled.push(at);
      if (this.filled.length > 16) this.filled.shift();
    }
  }

  private release(out: Mended<F>[]): void {
    const held = this.held;
    if (!held) return;
    this.held = null;
    out.push({ frame: held.frame, data: held.frames.length > 1 ? joinFrames(held.frames) : held.frames[0]! });
    this.lastEnd = held.end;
  }
}
