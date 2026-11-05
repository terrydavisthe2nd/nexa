/**
 * RNNoise probability gating, as used by noise-suppression-for-voice: retain
 * consonant onsets and speech tails. With two channels the gate opens and
 * closes as one, or a voice would move across the image as it did.
 */
export class SpeechGate {
  private hold = 0;
  private gain = 1;
  private readonly frames: Float32Array[][];
  private readonly audible = [true, true, true];
  private index = 0;
  constructor(channels = 1) {
    this.frames = Array.from({ length: 3 }, () => Array.from({ length: channels }, () => new Float32Array(480)));
  }
  process(frame: Float32Array, probability: number): void {
    this.processAll([frame], probability);
  }
  /** One frame of each channel, gated together by the likelier one's probability. */
  processAll(frame: Float32Array[], probability: number): void {
    const speech = probability >= (this.hold ? 0.2 : 0.5);
    if (speech) { this.hold = 20; this.audible.fill(true); }
    else this.hold = Math.max(0, this.hold - 1);
    const held = this.frames[this.index]!;
    for (let c = 0; c < held.length; ++c) held[c]!.set(frame[c]!);
    this.audible[this.index] = this.hold > 0;
    this.index = (this.index + 1) % 3;
    const target = this.audible[this.index] ? 1 : 0.02;
    const old = this.frames[this.index]!;
    for (let i = 0; i < 480; ++i) {
      this.gain += (target - this.gain) * (target > this.gain ? 0.02 : 0.002);
      for (let c = 0; c < old.length; ++c) frame[c]![i] = old[c]![i]! * this.gain;
    }
  }
}
