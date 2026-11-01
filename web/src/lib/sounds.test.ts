// @vitest-environment jsdom
import { FakeAudioContext, setVisibility, trackListeners, useFakeAudio } from "@/test/fakeAudio";

type Sounds = typeof import("./sounds");

/** A fresh copy of the module, with no context or listeners left from the test before. */
async function load(): Promise<Sounds> {
  vi.resetModules();
  return import("./sounds");
}

const tap = (type = "touchend") => window.dispatchEvent(new Event(type));
const made = (i = 0) => FakeAudioContext.made[i]!;
const settle = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};

let untrack: () => void;

beforeEach(() => {
  vi.useFakeTimers();
  untrack = trackListeners();
  setVisibility("visible");
  useFakeAudio(false);
  vi.stubGlobal(
    "fetch",
    vi.fn(() => Promise.resolve({ ok: true, arrayBuffer: () => Promise.resolve(new ArrayBuffer(8)) })),
  );
});

afterEach(() => {
  untrack();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("sounds", () => {
  it("start audio from a tap, and again after the system suspends or interrupts it", async () => {
    const s = await load();
    s.installAudioUnlock();
    // nothing is made until someone touches the page
    expect(FakeAudioContext.made).toHaveLength(0);
    tap();
    expect(FakeAudioContext.made).toHaveLength(1);
    const ac = made();
    expect(ac.state).toBe("running");
    expect(ac.sources).toBe(1);

    // once running, taps are left alone
    tap("pointerdown");
    expect(ac.sources).toBe(1);

    // a phone call interrupts it, and iOS does not always bring it back: the next key press does
    ac.setState("interrupted");
    tap("keydown");
    expect(ac.resumes).toBe(2);
    expect(ac.state).toBe("running");
    expect(FakeAudioContext.made).toHaveLength(1);

    // a click counts on its own: VoiceOver activates things without touches
    ac.setState("suspended");
    tap("click");
    expect(ac.state).toBe("running");
  });

  it("play the recordings once preloaded, and synthesise the rest", async () => {
    const s = await load();
    s.preloadSounds();
    await settle();
    const ac = made();
    expect(ac.decoded).toBe(12);
    expect(await s.playSound("message")).toBe(true);
    expect(await s.playSound("friend")).toBe(true);
    expect(ac.sources).toBe(2);
    expect(await s.playSound("join")).toBe(true);
    expect(ac.tones).toBe(2);
  });

  it("drop a sound that cannot start in time rather than play it late", async () => {
    FakeAudioContext.startsAs = "wait";
    const s = await load();
    s.installAudioUnlock();
    const played = s.playSound("join");
    await vi.advanceTimersByTimeAsync(1500);
    expect(await played).toBe(false);
    const ac = made();
    // a tap later starts audio, and the old sound does not come out then
    ac.onResume = "run";
    tap();
    await settle();
    expect(ac.state).toBe("running");
    expect(ac.tones).toBe(0);
  });

  it("play a sound that starts a moment late", async () => {
    FakeAudioContext.startsAs = "wait";
    const s = await load();
    const played = s.playSound("join");
    await vi.advanceTimersByTimeAsync(500);
    made().setState("running");
    expect(await played).toBe(true);
    expect(made().tones).toBe(2);
  });

  it("ring as soon as audio starts, however long that takes, until stopped", async () => {
    FakeAudioContext.startsAs = "wait";
    const s = await load();
    s.installAudioUnlock();
    s.startRinging();
    await vi.advanceTimersByTimeAsync(10_000);
    const ac = made();
    expect(ac.loops).toBe(0);
    // the tap that answers may be what starts it
    ac.onResume = "run";
    tap();
    await settle();
    expect(ac.loops).toBe(1);
    s.stopRinging();
    expect(ac.stopped).toBe(1);
  });

  it("stay up once started, outside home-screen apps", async () => {
    const s = await load();
    s.installAudioUnlock();
    tap();
    const ac = made();
    expect(await s.playSound("join")).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    setVisibility("hidden");
    expect(ac.suspends).toBe(0);
    expect(ac.state).toBe("running");
  });

  it("replace a context the device refuses at the next tap, at most every 30 s, but not one the system has only interrupted", async () => {
    FakeAudioContext.startsAs = "refuse";
    const s = await load();
    s.installAudioUnlock();
    tap();
    await settle();
    expect(made(0).state).toBe("closed");
    // the new one is refused too: taps try it again rather than making one after another
    tap();
    await settle();
    tap();
    await settle();
    expect(FakeAudioContext.made).toHaveLength(2);
    expect(made(1).resumes).toBe(2);
    await vi.advanceTimersByTimeAsync(30_000);
    FakeAudioContext.startsAs = "run";
    tap();
    await settle();
    expect(made(1).state).toBe("closed");
    tap();
    expect(FakeAudioContext.made).toHaveLength(3);
    expect(made(2).state).toBe("running");

    // Chrome refuses to resume an interrupted context, and brings it back itself
    await vi.advanceTimersByTimeAsync(30_000);
    made(2).onResume = "refuse";
    made(2).setState("interrupted");
    tap();
    await settle();
    expect(FakeAudioContext.made).toHaveLength(3);
    made(2).setState("running");
    expect(await s.playSound("join")).toBe(true);
  });

  it("say why a sound would not start", async () => {
    const s = await load();
    FakeAudioContext.startsAs = "wait";
    const played = s.playSound("message");
    await vi.advanceTimersByTimeAsync(1500);
    expect(await played).toBe(false);
    expect(s.soundTrouble()).toContain("audio stayed suspended");

    (window as unknown as { AudioContext: unknown }).AudioContext = undefined;
    const bare = await load();
    expect(await bare.playSound("message")).toBe(false);
    expect(bare.soundTrouble()).toBe("This browser can't play sounds.");
  });
});

describe("sound packs", () => {
  const pack = (files: Record<string, { blob: string }>, use = "pmine1234") => ({
    pack: use,
    volume: 100,
    off: [] as never[],
    packs: [{ id: "pmine1234", name: "Mine", files: Object.fromEntries(Object.entries(files).map(([k, f]) => [k, { ...f, key: "k".repeat(44), name: "x.wav", ms: 500, size: 10 }])) }],
  });

  it("play a custom recording, opened with the account's key, and Nexa's own for the rest", async () => {
    const s = await load();
    const open = vi.fn(() => Promise.resolve(new ArrayBuffer(16)));
    s.configureSounds(pack({ message: { blob: "77" } }), open);
    expect(await s.playSound("message")).toBe(true);
    const ac = made();
    expect(open).toHaveBeenCalledTimes(1);
    expect(ac.sources).toBe(1);
    expect(ac.decoded).toBe(1);
    expect(fetch).not.toHaveBeenCalled();
    // Nexa's recording plays for a mention, and its chimes for someone joining
    expect(await s.playSound("mention")).toBe(true);
    expect(fetch).toHaveBeenCalledWith("/sounds/mention.m4a");
    expect(ac.sources).toBe(2);
    expect(await s.playSound("join")).toBe(true);
    expect(ac.tones).toBe(2);
    // played again, it is not fetched again
    expect(await s.playSound("message")).toBe(true);
    expect(open).toHaveBeenCalledTimes(1);
  });

  it("fall back to Nexa's own when a recording cannot be had", async () => {
    const s = await load();
    s.configureSounds(pack({ join: { blob: "88" }, message: { blob: "89" } }), () => Promise.reject(new Error("gone")));
    expect(await s.playSound("join")).toBe(true);
    expect(made().tones).toBe(2);
    expect(await s.playSound("message")).toBe(true);
    expect(fetch).toHaveBeenCalledWith("/sounds/message.m4a");
    expect(made().sources).toBe(1);
  });

  it("keep a switched-off sound quiet, but let the settings play it to hear it", async () => {
    const s = await load();
    s.configureSounds({ pack: "nexa", volume: 100, off: ["join", "ring"], packs: [] });
    expect(await s.playSound("join")).toBe(false);
    expect(FakeAudioContext.made).toHaveLength(0);
    expect(await s.previewSound("join")).toBe(true);
    expect(made().tones).toBe(2);
    // and a switched-off ring does not ring
    s.startRinging();
    await settle();
    expect(made().loops).toBe(0);
    expect(made().tones).toBe(2);
  });

  it("preview a pack that is not the one in use", async () => {
    const s = await load();
    const open = vi.fn(() => Promise.resolve(new ArrayBuffer(16)));
    s.configureSounds(pack({ message: { blob: "77" } }, "nexa"), open);
    expect(await s.previewSound("message", "pmine1234")).toBe(true);
    expect(open).toHaveBeenCalledTimes(1);
    expect(fetch).not.toHaveBeenCalled();
    // and Nexa's own, the one in use
    expect(await s.previewSound("message")).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(made().sources).toBe(2);
  });
});

describe("sounds in an iPhone home-screen app", () => {
  beforeEach(() => useFakeAudio(true));

  it("make no audio before a tap, and decode the recordings once one has", async () => {
    const s = await load();
    s.installAudioUnlock();
    s.preloadSounds();
    await settle();
    expect(FakeAudioContext.made).toHaveLength(0);
    expect(fetch).toHaveBeenCalledTimes(12);
    tap();
    await settle();
    expect(made().decoded).toBe(12);
    expect(fetch).toHaveBeenCalledTimes(12);
  });

  it("let audio go after the last sound, and start it again for the next without a tap", async () => {
    const s = await load();
    s.installAudioUnlock();
    tap();
    const ac = made();
    expect(ac.state).toBe("running");
    // nothing played: let go after a moment
    await vi.advanceTimersByTimeAsync(2000);
    expect(ac.state).toBe("suspended");
    // taps leave audio alone once it has been let go
    tap();
    expect(ac.resumes).toBe(1);

    // a message starts it again by itself
    expect(await s.playSound("join")).toBe(true);
    expect(ac.tones).toBe(2);
    expect(ac.state).toBe("running");
    // it lasts 0.22 s, and audio stays 2 s past that for a sound close behind
    await vi.advanceTimersByTimeAsync(2000);
    expect(ac.state).toBe("running");
    expect(await s.playSound("join")).toBe(true);
    expect(ac.tones).toBe(4);
    expect(ac.resumes).toBe(2);
    // and audio is let go 2 s after that one ends
    await vi.advanceTimersByTimeAsync(2100);
    expect(ac.state).toBe("running");
    await vi.advanceTimersByTimeAsync(200);
    expect(ac.state).toBe("suspended");
  });

  it("go quiet in the background and stay quiet there, while a ring carries on when back", async () => {
    const s = await load();
    s.installAudioUnlock();
    tap();
    const ac = made();
    s.startRinging();
    await settle();
    expect(ac.loops).toBe(1);
    // ringing keeps audio up
    await vi.advanceTimersByTimeAsync(10_000);
    expect(ac.state).toBe("running");

    setVisibility("hidden");
    expect(ac.state).toBe("suspended");
    expect(await s.playSound("message")).toBe(false);
    expect(ac.state).toBe("suspended");
    expect(ac.sources).toBe(2);

    setVisibility("visible");
    expect(ac.state).toBe("running");
    // the same ring goes on rather than a second one
    await settle();
    expect(ac.loops).toBe(1);

    s.stopRinging();
    await vi.advanceTimersByTimeAsync(2000);
    expect(ac.state).toBe("suspended");
  });

  it("say what to do when the device will not start", async () => {
    FakeAudioContext.startsAs = "refuse";
    const s = await load();
    expect(await s.playSound("message")).toBe(false);
    expect(s.soundTrouble()).toContain("InvalidStateError: Failed to start the audio device");
    expect(s.soundTrouble()).toContain("until the phone restarts");
  });
});
