import { Rnnoise, type DenoiseState } from "@shiguredo/rnnoise-wasm";
import { SpeechGate } from "./speechGate";

declare const sampleRate: number;
declare function registerProcessor(name: string, processor: unknown): void;
declare class AudioWorkletProcessor { port: MessagePort; constructor(options?: unknown); }

const FIFO = 4096;

/** RNNoise over one channel, or over two with a state each and one gate between them (True Stereo). */
class DenoiseProcessor extends AudioWorkletProcessor {
  private states: DenoiseState[] | null = null;
  private destroyed = false;
  private readonly channels: number;
  private readonly frames: Float32Array[];
  private readonly fifos: Float32Array[];
  private readonly gate: SpeechGate;
  private fill = 0;
  private read = 0;
  private write = 0;
  private count = 0;
  constructor(options?: { processorOptions?: { channels?: number } }) {
    super(options);
    this.channels = options?.processorOptions?.channels === 2 ? 2 : 1;
    this.frames = Array.from({ length: this.channels }, () => new Float32Array(480));
    this.fifos = Array.from({ length: this.channels }, () => new Float32Array(FIFO));
    this.gate = new SpeechGate(this.channels);
    this.port.onmessage = ({ data }) => { if (data === "destroy") { this.destroyed = true; this.states?.forEach((s) => s.destroy()); this.states = null; } };
    // The embedded Emscripten build uses a window/worker sentinel but no DOM APIs.
    // AudioWorkletGlobalScope has neither sentinel; provide it only during initialization.
    const scope = globalThis as typeof globalThis & { window?: unknown };
    Object.defineProperty(scope, "window", { value: globalThis, configurable: true });
    void Rnnoise.load().finally(() => { Reflect.deleteProperty(scope, "window"); }).then((module) => {
      if (sampleRate !== 48000) throw new Error("sample rate");
      if (this.destroyed) return;
      this.states = this.frames.map(() => module.createDenoiseState());
      this.port.postMessage("ready");
    }).catch(() => this.port.postMessage("failed"));
  }
  process(inputs: Float32Array[][], outputs: Float32Array[][]): boolean {
    if (this.destroyed) return false;
    const input = inputs[0];
    const output = outputs[0];
    if (!output?.[0]) return true;
    const states = this.states;
    if (!states || !input?.[0]) { output.forEach((o) => o.fill(0)); return true; }
    const n = output[0].length;
    for (let i = 0; i < n; ++i) {
      for (let c = 0; c < this.channels; ++c) this.frames[c]![this.fill] = (input[c]?.[i] ?? input[0]![i] ?? 0) * 32768;
      if (++this.fill === 480) {
        let probability = 0;
        for (let c = 0; c < this.channels; ++c) probability = Math.max(probability, states[c]!.processFrame(this.frames[c]!));
        this.gate.processAll(this.frames, probability);
        for (let c = 0; c < this.channels; ++c) {
          const frame = this.frames[c]!;
          const fifo = this.fifos[c]!;
          for (let j = 0, w = this.write; j < 480; ++j) { fifo[w] = frame[j]! / 32768; w = (w + 1) % FIFO; }
        }
        this.write = (this.write + 480) % FIFO;
        this.count += 480; this.fill = 0;
      }
      const has = this.count > 0;
      for (let c = 0; c < this.channels; ++c) { const o = output[c]; if (o) o[i] = has ? this.fifos[c]![this.read]! : 0; }
      if (has) { this.read = (this.read + 1) % FIFO; --this.count; }
    }
    return true;
  }
}
registerProcessor("nexa-denoise", DenoiseProcessor);
