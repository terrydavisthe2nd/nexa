/**
 * Seals outgoing call frames and opens incoming ones, off the main thread.
 * Frames arrive through RTCRtpScriptTransform (onrtctransform) or, in older
 * Chromium, as streams the page hands over. A frame this worker cannot seal
 * (no key yet) or open (another key, or altered) is dropped, never passed on
 * in the clear.
 *
 * Every received video frame, once opened, is also held to the size cap
 * (frameSize.ts) before the browser decodes it. Peer to peer, where nothing is
 * sealed, video receivers come through here for that check alone ("inspect").
 */
import { FIRST_FRAME_COUNTER, MAX_FRAME_COUNTER, clearBytes, frameIv, importFrameKey, newSalt, nextCounter, openFrame, openH264Frame, sealFrame, sealH264Frame, type FrameKind } from "./frameCrypto";
import { H264, SizeGuard } from "./frameSize";
import { isAnnexB } from "./h264";
import { CopyHistory, Mender, copiesThatFit, unwrapCopies, wrapCopies, type Mended } from "./redundancy";

type Op = "encrypt" | "decrypt" | "inspect";

/** How a receiver's transform is set up: `codec` is VP8's MIME type when that is the only codec its transceiver negotiates. */
interface StreamOptions {
  op: Op;
  kind: FrameKind;
  id?: string;
  voice?: boolean;
  codec?: string;
}

export type FrameCryptoMessage =
  | { type: "keys"; keys: { index: number; raw: Uint8Array }[]; send: number }
  | { type: "redundancy"; depth: number }
  | { type: "keyframe"; id: string }
  | ({ type: "stream"; readable: ReadableStream; writable: WritableStream } & StreamOptions);

export interface FrameCryptoStats {
  type: "stats";
  sealed: number;
  opened: number;
  dropped: number;
  /** Voice frames that went missing and were played from the copies a later frame carried. */
  recovered?: number;
  /** Video frames kept from the decoder for a picture over the size cap. */
  oversize?: number;
}

/**
 * The first frame through one receiver's transform, and the first one that
 * opened. A receiver whose packets never show up here is playing them
 * without opening them: sealed frames decoded as they are, which is loud noise.
 */
export interface FrameCryptoSeen {
  type: "seen";
  id: string;
  opened: boolean;
}

/**
 * A receiver's picture went over the size cap (its frames are dropped from
 * then on) or came back within it. Told a moment after it changes, so a
 * stream flipping back and forth cannot flood the page.
 */
export interface FrameCryptoSize {
  type: "size";
  id: string;
  over: boolean;
}

interface FrameMetadata {
  rtpTimestamp?: number;
  synchronizationSource?: number;
  mimeType?: string;
}

interface EncodedFrame {
  data: ArrayBuffer;
  type?: string;
  getMetadata?(): FrameMetadata;
}

interface TransformEvent {
  transformer: { readable: ReadableStream<EncodedFrame>; writable: WritableStream<EncodedFrame>; options: StreamOptions; sendKeyFrameRequest?: () => Promise<void> };
}

/**
 * The receivers that can ask their sender for a keyframe, by id
 * (RTCRtpScriptTransformer.sendKeyFrameRequest), for a picture stuck on a
 * frame that will never arrive (sfu.ts watchStalls). A receiver that has gone
 * is let go when asking fails.
 */
const askers = new Map<string, () => Promise<void>>();

interface WorkerScope {
  onmessage: ((e: MessageEvent<FrameCryptoMessage>) => void) | null;
  onrtctransform: ((e: TransformEvent) => void) | null;
  postMessage(msg: FrameCryptoStats | FrameCryptoSeen | FrameCryptoSize): void;
}

const scope = self as unknown as WorkerScope;
const keys = new Map<number, CryptoKey>();
let sendIndex = -1;
let salt = newSalt();
let counter = FIRST_FRAME_COUNTER;
const stats: FrameCryptoStats = { type: "stats", sealed: 0, opened: 0, dropped: 0, recovered: 0, oversize: 0 };
/** How many earlier frames each outgoing voice frame carries copies of (0 to 2); the page sets it from how lossy the call is. */
let redundancy = 0;
// keys are imported in order, so frames wait behind a key change instead of racing it
let keysReady: Promise<void> = Promise.resolve();
/**
 * How long a voice frame held for lost frames' copies waits for the next frame
 * before going on without: long enough for the frame after two lost ones.
 */
const HOLD_MS = 80;
/** How long a receiver's size verdict has to settle before the page is told. */
const SIZE_REPORT_MS = 300;
/**
 * How many ivs an H.264 frame is sealed with before it is given up: one that
 * comes out holding a start code is sealed again with the next. Sealed bytes
 * hold 00 00 01 about once in 16 million, so a 150 KB keyframe needs a
 * second go about once in a hundred, and a tenth go practically never.
 */
const H264_TRIES = 10;

/** The next iv this sender seals with, a new salt when the counter has run out. */
function nextIv(): Uint8Array {
  if (counter > MAX_FRAME_COUNTER) {
    salt = newSalt();
    counter = FIRST_FRAME_COUNTER;
  }
  const iv = frameIv(salt, counter);
  counter = nextCounter(counter);
  return iv;
}

/** The codec a received frame is in, as the browser reports it; undefined where it does not. */
function mimeOf(frame: EncodedFrame): string | undefined {
  try {
    const m = frame.getMetadata?.().mimeType;
    return typeof m === "string" && m ? m : undefined;
  } catch {
    return undefined;
  }
}

/** Whether a video frame is H.264: as the browser names its codec, or the receiver's own, or from its first bytes. */
function isH264(frame: EncodedFrame, data: Uint8Array, codec: string | undefined): boolean {
  const named = (mimeOf(frame) ?? codec)?.toLowerCase();
  return named === undefined ? isAnnexB(data) : named === H264;
}

function passOn(controller: TransformStreamDefaultController<EncodedFrame>, frames: Mended<EncodedFrame>[]): void {
  for (const { frame, data } of frames) {
    frame.data = (data.byteOffset === 0 && data.byteLength === data.buffer.byteLength ? data.buffer : data.slice().buffer) as ArrayBuffer;
    controller.enqueue(frame);
  }
}

function transformer({ op, kind, id, voice = false, codec }: StreamOptions): TransformStream<EncodedFrame, EncodedFrame> {
  // what this receiver has reported so far: any frame at all, and one that opened
  let reportedFrame = false;
  let reportedOpened = false;
  // A picture just pulled cannot show until a keyframe comes. The browser asks
  // for one only after about 200 ms without; this asks the moment the first
  // frame in turns out not to be one, so a share or camera shows that much sooner.
  let keyAwaited = kind === "video" && op === "decrypt" && id !== undefined;
  // received video is held to the size cap; through the SFU only VP8 and H.264 are sent, peer to peer another codec is let be
  const guard = kind === "video" && op !== "encrypt" ? new SizeGuard(op === "inspect" ? "pass" : "drop") : null;
  let toldOver = false;
  let sizeTimer: ReturnType<typeof setTimeout> | undefined;
  const tellSize = () => {
    if (id === undefined || sizeTimer !== undefined) return;
    sizeTimer = setTimeout(() => {
      sizeTimer = undefined;
      if (!guard || guard.blocked === toldOver) return;
      toldOver = guard.blocked;
      scope.postMessage({ type: "size", id, over: toldOver });
    }, SIZE_REPORT_MS);
  };
  /** Whether an opened video frame may go on to the decoder. */
  const admit = (frame: EncodedFrame, data: Uint8Array): boolean => {
    if (!guard) return true;
    const was = guard.blocked;
    const verdict = guard.check(data, mimeOf(frame) ?? codec);
    if (guard.blocked !== was) tellSize();
    if (verdict === "pass") return true;
    stats.oversize = (stats.oversize ?? 0) + 1;
    return false;
  };
  // a voice sender remembers its last frames even while it sends no copies, so turning them on needs no warm-up
  const history = voice && op === "encrypt" ? new CopyHistory(2) : null;
  const mender = kind === "audio" && op === "decrypt" ? new Mender<EncodedFrame>() : null;
  let holdTimer: ReturnType<typeof setTimeout> | undefined;
  return new TransformStream({
    async transform(frame, controller) {
      // a frame that cannot even be read is dropped: an error here would end the stream, and the track with it
      try {
        if (op === "inspect") {
          if (admit(frame, new Uint8Array(frame.data))) controller.enqueue(frame);
          return;
        }
        if (keyAwaited) {
          keyAwaited = false;
          if (frame.type !== "key") askers.get(id!)?.().catch(() => {});
        }
        await keysReady;
        const data = new Uint8Array(frame.data);
        const clear = clearBytes(kind, kind === "video" && frame.type === "key");
        // An empty frame is DTX's way of sending nothing at all through a
        // pause (voice/opus.ts), and the browser only leaves it unsent while
        // it stays empty. Sealed, it became a packet of its own every 20 ms.
        // It carries nothing to protect, so it goes on as it is.
        if (op === "encrypt" && data.length === 0) {
          controller.enqueue(frame);
          return;
        }
        if (op === "encrypt") {
          const key = keys.get(sendIndex);
          if (!key) {
            stats.dropped++;
            return;
          }
          let plain: Uint8Array = data;
          const ts = history ? frame.getMetadata?.().rtpTimestamp : undefined;
          if (history && ts !== undefined) {
            const copies = history.take(ts, data);
            if (redundancy > 0) plain = wrapCopies(data, copiesThatFit(data.length, copies.slice(0, redundancy))) ?? data;
          }
          if (kind === "video" && isH264(frame, plain, codec)) {
            let sealed: Uint8Array | null = null;
            for (let tries = 0; !sealed && tries < H264_TRIES; tries++) sealed = await sealH264Frame(key, sendIndex, nextIv(), plain);
            if (!sealed) {
              stats.dropped++;
              return;
            }
            frame.data = sealed.buffer as ArrayBuffer;
          } else {
            frame.data = (await sealFrame(key, sendIndex, nextIv(), plain, clear)).buffer as ArrayBuffer;
          }
          stats.sealed++;
        } else {
          const opened = kind === "video" && isH264(frame, data, codec) ? await openH264Frame(keys, data) : await openFrame(keys, data, clear);
          if (id !== undefined && (!reportedFrame || (opened && !reportedOpened))) {
            reportedFrame = true;
            if (opened) reportedOpened = true;
            scope.postMessage({ type: "seen", id, opened: !!opened });
          }
          if (!opened) {
            stats.dropped++;
            return;
          }
          stats.opened++;
          if (mender) {
            const { primary, copies } = unwrapCopies(opened);
            const meta = frame.getMetadata?.();
            const before = mender.recovered;
            clearTimeout(holdTimer);
            passOn(controller, meta?.rtpTimestamp === undefined ? [...mender.flush(), { frame, data: primary }] : mender.push(frame, meta.synchronizationSource ?? 0, meta.rtpTimestamp, primary, copies, performance.now()));
            stats.recovered = (stats.recovered ?? 0) + mender.recovered - before;
            if (mender.holding) {
              holdTimer = setTimeout(() => {
                try {
                  passOn(controller, mender.flush());
                } catch {
                  /* the track ended */
                }
              }, HOLD_MS);
            }
            return;
          }
          if (!admit(frame, opened)) return;
          frame.data = opened.buffer as ArrayBuffer;
        }
        controller.enqueue(frame);
      } catch {
        stats.dropped++;
      }
    },
    flush() {
      clearTimeout(holdTimer);
      clearTimeout(sizeTimer);
    },
  });
}

function pipe(readable: ReadableStream<EncodedFrame>, writable: WritableStream<EncodedFrame>, options: StreamOptions): Promise<void> {
  return readable
    .pipeThrough(transformer(options))
    .pipeTo(writable)
    .catch(() => {
      /* the track ended */
    });
}

scope.onmessage = (e) => {
  const msg = e.data;
  if (msg.type === "keys") {
    keysReady = keysReady.then(async () => {
      const next = new Map<number, CryptoKey>();
      for (const k of msg.keys) next.set(k.index, await importFrameKey(k.raw));
      keys.clear();
      for (const [index, key] of next) keys.set(index, key);
      sendIndex = msg.send;
    });
  } else if (msg.type === "redundancy") {
    redundancy = Math.max(0, Math.min(2, msg.depth | 0));
  } else if (msg.type === "keyframe") {
    const ask = askers.get(msg.id);
    if (ask) ask().catch(() => askers.delete(msg.id));
  } else if (msg.type === "stream") {
    const { readable, writable, op, kind, id, voice, codec } = msg;
    void pipe(readable, writable, { op, kind, id, voice, codec });
  }
};

scope.onrtctransform = (e) => {
  const t = e.transformer;
  const { readable, writable, options } = t;
  const id = options.op !== "encrypt" && options.kind === "video" && typeof t.sendKeyFrameRequest === "function" ? options.id : undefined;
  if (id) askers.set(id, () => t.sendKeyFrameRequest!());
  void pipe(readable, writable, options).finally(() => {
    if (id) askers.delete(id);
  });
};

setInterval(() => scope.postMessage(stats), 2000);
