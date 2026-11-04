import { uploads, type UploadSession } from "@/api/uploads";
import { b64 } from "@/crypto/bytes";
import { decryptFile } from "@/crypto/file";
import { CHUNK, FRAME_LEN, HEADER_LEN, encryptChunks, encryptedSize } from "@/crypto/fileChunks";
import { ApiError } from "@/api/client";
import { fitsUploadLimit, setUploadLimits, uploadStaged, type PieceJob, type PieceSender, type Staged } from "./attachments";
import { lookAtPicture } from "./pictureCopy";

vi.mock("./pictureCopy", async (orig) => ({ ...(await orig<typeof import("./pictureCopy")>()), lookAtPicture: vi.fn(async () => ({})) }));

const MiB = 1024 * 1024;

function pattern(n: number): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = (i * 31 + (i >>> 13) + 7) & 0xff;
  return out;
}

function staged(data: Uint8Array<ArrayBuffer>): Staged {
  const f = new File([data], "big.bin", { type: "application/octet-stream" });
  return { id: "f1", file: f, name: f.name, spoiler: false, preview: null, progress: 0, error: null };
}

async function same(bytes: Uint8Array, key: string, data: Uint8Array): Promise<boolean> {
  const out = new Uint8Array(await (await decryptFile(new Blob([bytes as Uint8Array<ArrayBuffer>]), b64.dec(key))).arrayBuffer());
  return out.length === data.length && out.every((b, i) => b === data[i]);
}

/** What a piece's request would carry. */
async function bodyOf(job: PieceJob): Promise<Uint8Array> {
  if (job.body) return job.body;
  const whole = await encryptChunks(job.file, job.key, job.baseIv, job.first, job.count);
  return whole.subarray(job.skip);
}

/** A stand-in for the server's upload endpoints, holding what has landed. */
function fakeServer() {
  let stored = new Uint8Array(0);
  let size = 0;
  const jobs: PieceJob[] = [];
  const session = (): UploadSession => ({ id: "u1", size, received: stored.length, chunk_size: 32 * MiB });
  const land = (bytes: Uint8Array): void => {
    const next = new Uint8Array(stored.length + bytes.length);
    next.set(stored);
    next.set(bytes, stored.length);
    stored = next;
  };
  const server = {
    jobs,
    cancelled: 0,
    single: null as Uint8Array | null,
    stored: () => stored,
    land,
    /** Replace to misbehave; by default a piece lands and the server says so. */
    onPiece: async (job: PieceJob, body: Uint8Array): Promise<unknown> => {
      if (Number(job.headers?.["Upload-Offset"]) !== stored.length) throw new Error("409 offset_mismatch");
      land(body);
      return session();
    },
  };
  const send: PieceSender = async (job, onProgress) => {
    jobs.push(job);
    const body = await bodyOf(job);
    onProgress(body.length);
    if (job.method === "POST") {
      server.single = body;
      return { blob_id: "b1", size: body.length, channel_id: "c1" };
    }
    return server.onPiece(job, body);
  };
  vi.spyOn(uploads, "create").mockImplementation(async (n) => {
    size = n;
    return session();
  });
  vi.spyOn(uploads, "status").mockImplementation(async () => session());
  vi.spyOn(uploads, "finish").mockImplementation(async () => {
    if (stored.length !== size) throw new Error("400 incomplete");
    return { blob_id: "b1", size };
  });
  vi.spyOn(uploads, "cancel").mockImplementation(async () => {
    server.cancelled += 1;
  });
  return { server, send };
}

afterEach(() => {
  vi.restoreAllMocks();
  setUploadLimits({ max: 100 * MiB, single: 90 * MiB, chunk: 32 * MiB });
});

describe("uploadStaged", () => {
  /** Sends each request's body to a list, answering with a blob id of its own. */
  function recorder() {
    const bodies: Uint8Array[] = [];
    const jobs: PieceJob[] = [];
    const send: PieceSender = async (job) => {
      jobs.push(job);
      const body = await bodyOf(job);
      bodies.push(body);
      return { blob_id: `b${bodies.length}`, size: body.length };
    };
    return { bodies, jobs, send, bodyFor: (id: string) => bodies[Number(id.slice(1)) - 1]! };
  }

  it("measures a picture and sends its small copy beside it, under a key of its own", async () => {
    const copyBytes = pattern(5000);
    vi.mocked(lookAtPicture).mockResolvedValueOnce({ w: 4032, h: 3024, copy: { blob: new Blob([copyBytes], { type: "image/webp" }), type: "image/webp", w: 1280, h: 960 } });
    const data = pattern(300 * 1024);
    const photo = new File([data], "cat.jpg", { type: "image/jpeg" });
    const { send, bodyFor } = recorder();
    const { meta } = await uploadStaged("c1", { ...staged(data), file: photo, name: "cat.jpg" }, () => undefined, send);
    expect(meta).toMatchObject({ w: 4032, h: 3024, type: "image/jpeg" });
    expect(meta.preview).toMatchObject({ type: "image/webp", w: 1280, h: 960, size: 5000 });
    expect(meta.preview!.blob_id).not.toBe(meta.blob_id);
    expect(meta.preview!.key).not.toBe(meta.key);
    expect(await same(bodyFor(meta.blob_id), meta.key, data)).toBe(true);
    expect(await same(bodyFor(meta.preview!.blob_id), meta.preview!.key, copyBytes)).toBe(true);
  });

  it("sends a picture without its copy when the copy will not go up", async () => {
    vi.mocked(lookAtPicture).mockResolvedValueOnce({ w: 10, h: 10, copy: { blob: new Blob([pattern(100)], { type: "image/webp" }), type: "image/webp", w: 5, h: 5 } });
    const data = pattern(4000);
    const { send } = recorder();
    const picky: PieceSender = async (job, onProgress, signal) => {
      if (job.file.type === "image/webp") throw new ApiError(500, "internal", "no");
      return send(job, onProgress, signal);
    };
    const { meta } = await uploadStaged("c1", { ...staged(data), file: new File([data], "a.png", { type: "image/png" }) }, () => undefined, picky);
    expect(meta.preview).toBeUndefined();
    expect(meta).toMatchObject({ w: 10, h: 10 });
  });

  it("sends a file that fits in one request as one request", async () => {
    const data = pattern(CHUNK + 10);
    const { server, send } = fakeServer();
    const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
    expect(server.jobs).toHaveLength(1);
    expect(server.jobs[0]).toMatchObject({ method: "POST", path: "/channels/c1/attachments", first: 0, count: 2, skip: 0 });
    expect(uploads.create).not.toHaveBeenCalled();
    expect(server.single!.length).toBe(encryptedSize(data.length));
    expect(await same(server.single!, meta.key, data)).toBe(true);
    expect(meta.size).toBe(data.length);
  });

  it("tries a single request once more when the connection lets it down", async () => {
    vi.useFakeTimers();
    try {
      const data = pattern(CHUNK + 10);
      const { server, send } = fakeServer();
      let failed = 0;
      const flaky: PieceSender = async (job, onProgress, signal) => {
        if (failed === 0) {
          failed += 1;
          server.jobs.push(job);
          throw new ApiError(0, "network", "offline");
        }
        return send(job, onProgress, signal);
      };
      const done = uploadStaged("c1", staged(data), () => undefined, flaky);
      await vi.advanceTimersByTimeAsync(2000);
      const { meta } = await done;
      expect(server.jobs).toHaveLength(2);
      expect(await same(server.single!, meta.key, data)).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not send a single request again when the server turned it down", async () => {
    const { server } = fakeServer();
    const refusing: PieceSender = async (job) => {
      server.jobs.push(job);
      throw new ApiError(413, "too_large", "too large");
    };
    await expect(uploadStaged("c1", staged(pattern(100)), () => undefined, refusing)).rejects.toThrow("too large");
    expect(server.jobs).toHaveLength(1);
  });

  it("sends a file that fits the single-request limit in one request", { timeout: 20_000 }, async () => {
    setUploadLimits({ max: 100 * MiB, single: 90 * MiB, chunk: 32 * MiB });
    const data = pattern(9 * MiB);
    const { server, send } = fakeServer();
    const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
    expect(uploads.create).not.toHaveBeenCalled();
    expect(server.jobs).toHaveLength(1);
    expect(await same(server.single!, meta.key, data)).toBe(true);
  });

  describe("above the single-request limit", { timeout: 20_000 }, () => {
    // pieces that hold one whole frame each (one more would not fit), so 5 chunks make 5 pieces
    const data = pattern(4 * CHUNK + 77);
    const total = encryptedSize(data.length);

    beforeEach(() => {
      setUploadLimits({ max: 1024 * MiB, single: 1024, chunk: HEADER_LEN + FRAME_LEN + 1 });
    });

    it("goes up in pieces of whole chunks that decrypt back to the file", async () => {
      const { server, send } = fakeServer();
      const progress: number[] = [];
      const { meta, blob_id } = await uploadStaged("c1", staged(data), (p) => progress.push(p), send);
      expect(blob_id).toBe("b1");
      // the channel goes with it, for a boosted server's bigger limit
      expect(uploads.create).toHaveBeenCalledWith(total, "c1");
      expect(server.jobs.map((j) => [j.first, j.count])).toEqual(Array.from({ length: 5 }, (_, i) => [i, 1]));
      expect(server.jobs.map((j) => j.headers?.["Upload-Offset"])).toEqual(Array.from({ length: 5 }, (_, i) => String(i === 0 ? 0 : HEADER_LEN + i * FRAME_LEN)));
      expect(await same(server.stored(), meta.key, data)).toBe(true);
      expect(progress.at(-1)).toBe(1);
      expect(server.cancelled).toBe(0);
    });

    it("fits as many whole chunks in a piece as the limit allows", async () => {
      setUploadLimits({ chunk: HEADER_LEN + 3 * FRAME_LEN });
      const { server, send } = fakeServer();
      const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
      expect(server.jobs.map((j) => [j.first, j.count])).toEqual([[0, 3], [3, 2]]);
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("prepares just the next piece while the current request waits for its reply", async () => {
      const st = staged(data);
      const reads = vi.spyOn(st.file, "slice");
      const { server, send } = fakeServer();
      let reply!: () => void;
      const waiting = new Promise<void>((resolve) => { reply = resolve; });
      const sending: PieceSender = async (job, progress, signal) => {
        if (job.first === 0) await waiting;
        return send(job, progress, signal);
      };
      const run = uploadStaged("c1", st, () => undefined, sending);
      try {
        await vi.waitFor(() => expect(reads.mock.calls.map(([start]) => start)).toEqual([0, CHUNK]));
        expect(server.jobs).toHaveLength(0);
      } finally {
        reply();
      }
      const { meta } = await run;
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("regenerates transferred bytes when retrying a partly landed piece", async () => {
      const { server, send } = fakeServer();
      let failed = false;
      const transferring: PieceSender = async (job, progress, signal) => {
        if (job.first === 1 && !failed && job.body) {
          failed = true;
          const bytes = new Uint8Array(structuredClone(job.body.buffer, { transfer: [job.body.buffer] }));
          server.land(bytes.subarray(0, 1000));
          throw new Error("network");
        }
        return send(job, progress, signal);
      };
      const { meta } = await uploadStaged("c1", staged(data), () => undefined, transferring);
      expect(failed).toBe(true);
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("sends a piece again when its request fails", async () => {
      const { server, send } = fakeServer();
      const onPiece = server.onPiece;
      let n = 0;
      server.onPiece = async (job, body) => {
        n += 1;
        if (n === 3) throw new Error("network");
        return onPiece(job, body);
      };
      const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
      // the piece whose request failed goes out again later
      const failed = server.jobs[2]!.first;
      expect(server.jobs.slice(3).some((j) => j.first === failed)).toBe(true);
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("sends the next piece while the current one's answer is on its way", async () => {
      const { server, send } = fakeServer();
      const onPiece = server.onPiece;
      const events: string[] = [];
      server.onPiece = async (job, body) => {
        events.push(`sent ${job.first}`);
        // the answer takes a while, as it does through the proxy
        await new Promise((r) => setTimeout(r, 30));
        const reply = await onPiece(job, body);
        events.push(`answered ${job.first}`);
        return reply;
      };
      const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
      const second = server.jobs.find((j) => j.first > 0)!.first;
      expect(events.indexOf(`sent ${second}`)).toBeLessThan(events.indexOf("answered 0"));
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("does not send a piece twice when only the reply was lost", async () => {
      const { server, send } = fakeServer();
      let n = 0;
      server.onPiece = async (job, body) => {
        n += 1;
        if (Number(job.headers?.["Upload-Offset"]) !== server.stored().length) throw new Error("409 offset_mismatch");
        server.land(body);
        if (n === 2) throw new Error("network");
        return { received: server.stored().length };
      };
      const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
      expect(server.jobs).toHaveLength(5);
      expect(server.jobs[2]!.first).toBe(2);
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("sends only the rest of a piece that partly landed", async () => {
      const { server, send } = fakeServer();
      let n = 0;
      server.onPiece = async (job, body) => {
        n += 1;
        if (Number(job.headers?.["Upload-Offset"]) !== server.stored().length) throw new Error("409 offset_mismatch");
        if (n === 2) {
          server.land(body.subarray(0, 1000));
          throw new Error("network");
        }
        server.land(body);
        return { received: server.stored().length };
      };
      const { meta } = await uploadStaged("c1", staged(data), () => undefined, send);
      expect(server.jobs[2]).toMatchObject({ first: 1, count: 1, skip: 1000 });
      expect(await same(server.stored(), meta.key, data)).toBe(true);
    });

    it("asks to finish again when the reply to finishing was lost", async () => {
      const { server, send } = fakeServer();
      let n = 0;
      vi.spyOn(uploads, "finish").mockImplementation(async () => {
        n += 1;
        if (n === 1) throw new TypeError("Failed to fetch");
        return { blob_id: "b1", size: server.stored().length };
      });
      const { blob_id } = await uploadStaged("c1", staged(data), () => undefined, send);
      expect(blob_id).toBe("b1");
      expect(n).toBe(2);
      expect(server.cancelled).toBe(0);
    });

    it("waits out a dropped connection instead of throwing the upload away", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      try {
        const { server, send } = fakeServer();
        const onPiece = server.onPiece;
        let down = false;
        let n = 0;
        server.onPiece = async (job, body) => {
          n += 1;
          if (n === 3) down = true;
          if (down) throw new Error("network");
          return onPiece(job, body);
        };
        const status = uploads.status;
        let asked = 0;
        vi.mocked(status).mockImplementation(async () => {
          asked += 1;
          // the connection is back after a while
          if (asked === 6) down = false;
          if (down) throw new TypeError("Failed to fetch");
          return { id: "u1", size: 0, received: server.stored().length, chunk_size: 32 * MiB };
        });
        let result: { meta: { key: string } } | null = null;
        let failed: unknown = null;
        const run = uploadStaged("c1", staged(data), () => undefined, send).then(
          (r) => (result = r),
          (e: unknown) => (failed = e),
        );
        // Fake seconds for the waits, real time for the encryption: WebCrypto
        // finishes on its own thread, so each step also lets the event loop
        // turn. Counting fake steps alone raced the machine's speed, and on a
        // slower run the loop ran out before the pieces were encrypted.
        for (let i = 0; i < 3000 && !result && !failed; i++) {
          await vi.advanceTimersByTimeAsync(1000);
          await new Promise((r) => setImmediate(r));
        }
        await run;
        expect(failed).toBeNull();
        expect(server.cancelled).toBe(0);
        expect(asked).toBeGreaterThanOrEqual(6);
        expect(await same(server.stored(), result!.meta.key, data)).toBe(true);
      } finally {
        vi.useRealTimers();
      }
    });

    it("gives up after a few failures and throws the upload away", async () => {
      const { server, send } = fakeServer();
      server.onPiece = async () => {
        throw new Error("network");
      };
      await expect(uploadStaged("c1", staged(data), () => undefined, send)).rejects.toThrow("network");
      // the first piece is tried four times; the one sent ahead of it once
      expect(server.jobs.filter((j) => j.first === 0)).toHaveLength(4);
      expect(server.jobs.length).toBeLessThanOrEqual(5);
      expect(server.cancelled).toBe(1);
    });
  });

  it("measures the limit on the encrypted size, as the server does", async () => {
    setUploadLimits({ max: encryptedSize(10 * MiB) });
    expect(fitsUploadLimit(10 * MiB)).toBe(true);
    expect(fitsUploadLimit(10 * MiB + 1)).toBe(false);
    const { server, send } = fakeServer();
    await expect(uploadStaged("c1", staged(pattern(10 * MiB + 1)), () => undefined, send)).rejects.toThrow("limit");
    expect(server.jobs).toHaveLength(0);
    expect(uploads.create).not.toHaveBeenCalled();
  });
});
