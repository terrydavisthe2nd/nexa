import { ApiError } from "@/api/client";
import { users } from "@/api/users";
import { randomBytes } from "@/crypto/bytes";
import { decryptSettings, encryptSettings } from "@/lib/settingsCrypto";
import { toggleFavoriteGif } from "@/lib/gifFavorites";
import { toggleFavoriteSticker } from "@/lib/stickerFavorites";
import { useSettings } from "./settings";

const key = randomBytes(32);

/** A stand-in for the account's settings on the server, with its version check. */
function fakeServer(doc: Record<string, unknown>, version = 1) {
  const server = { doc, version, puts: 0, fail: null as Error | null };
  vi.spyOn(users, "getSettings").mockImplementation(async () => ({ blob: await encryptSettings(key, server.doc), version: server.version }));
  vi.spyOn(users, "putSettings").mockImplementation(async (blob, v) => {
    server.puts += 1;
    if (server.fail) throw server.fail;
    if (v !== server.version) throw new ApiError(409, "version_conflict", "Settings changed elsewhere.");
    server.doc = (await decryptSettings(key, blob)) as Record<string, unknown>;
    server.version += 1;
    return { version: server.version };
  });
  return server;
}

async function loadFrom(server: { doc: Record<string, unknown>; version: number }): Promise<void> {
  await useSettings.getState().load(await encryptSettings(key, server.doc), server.version);
}

beforeEach(() => {
  useSettings.getState().reset();
  useSettings.getState().setKey(key);
});

afterEach(() => {
  useSettings.getState().reset();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("settings sync", () => {
  it("does not save defaults over the account before its settings arrive", async () => {
    const server = fakeServer({ theme: "light", userNotes: { u1: "friend from work" } });
    useSettings.getState().setDraft("c1", "hello");
    await useSettings.getState().flush();
    expect(server.puts).toBe(0);
    await loadFrom(server);
    await useSettings.getState().flush();
    expect(server.doc).toMatchObject({ theme: "light", userNotes: { u1: "friend from work" }, drafts: { c1: "hello" } });
    expect(useSettings.getState().settings.theme).toBe("light");
  });

  it("keeps another device's change when both saved at once", async () => {
    const server = fakeServer({ theme: "dark", fontScale: 1 });
    await loadFrom(server);
    // the other device saves first
    server.doc = { ...server.doc, theme: "light", appLock: null, userNotes: { u2: "note" } };
    server.version += 1;
    useSettings.getState().patch({ fontScale: 1.2 });
    await useSettings.getState().flush();
    expect(server.doc).toMatchObject({ theme: "light", fontScale: 1.2, userNotes: { u2: "note" } });
    expect(useSettings.getState().settings).toMatchObject({ theme: "light", fontScale: 1.2 });
    expect(useSettings.getState().version).toBe(server.version);
  });

  it("keeps a change made during a save when that save's echo reloads", async () => {
    const server = fakeServer({ theme: "dark" });
    await loadFrom(server);
    useSettings.getState().patch({ showMembers: false });
    const first = useSettings.getState().flush();
    useSettings.getState().patch({ sidebarWidth: 300 });
    // the echo of the first save arrives before its reply
    const reload = useSettings.getState().reload();
    // flushing while a save is under way waits for the changes it missed
    const second = useSettings.getState().flush();
    await Promise.all([first, reload, second]);
    expect(useSettings.getState().settings).toMatchObject({ showMembers: false, sidebarWidth: 300 });
    expect(server.doc).toMatchObject({ showMembers: false, sidebarWidth: 300 });
  });

  it("keeps an unmute made here when another device's copy arrives", async () => {
    const n = { desktop: false, app: true, sounds: true, perChannel: { c5: "none", c7: "all" }, muted: ["c1", "c2"] };
    const server = fakeServer({ notifications: n });
    await loadFrom(server);
    // the other device mutes c3 and sets a level on c6, and saves first
    server.doc = { ...server.doc, notifications: { ...n, muted: ["c1", "c2", "c3"], perChannel: { ...n.perChannel, c6: "mentions", c7: "none" } } };
    server.version += 1;
    // this device unmutes c1 and clears c5's level
    const mine = useSettings.getState().settings.notifications;
    useSettings.getState().patch({ notifications: { ...mine, muted: ["c2"], perChannel: { c7: "all" } } });
    await useSettings.getState().flush();
    const saved = server.doc["notifications"] as typeof n;
    expect([...saved.muted].sort()).toEqual(["c2", "c3"]);
    // c5 cleared here, c6 set there, and c7, untouched here, keeps the other device's change
    expect(saved.perChannel).toEqual({ c6: "mentions", c7: "none" });
    expect(useSettings.getState().settings.notifications.muted.sort()).toEqual(["c2", "c3"]);
  });

  it("keeps a GIF favourited on another device when this one favourites another", async () => {
    const server = fakeServer({ favoriteGifs: [{ url: "https://a.example/old.gif" }, { url: "https://a.example/gone.gif" }] });
    await loadFrom(server);
    // the other device favourites one and saves first
    server.doc = { ...server.doc, favoriteGifs: [{ url: "https://a.example/theirs.gif" }, ...(server.doc["favoriteGifs"] as object[])] };
    server.version += 1;
    // this device, on its older copy, favourites one and drops another
    toggleFavoriteGif({ url: "https://a.example/mine.gif" });
    toggleFavoriteGif({ url: "https://a.example/gone.gif" });
    await useSettings.getState().flush();
    const urls = (server.doc["favoriteGifs"] as { url: string }[]).map((f) => f.url);
    expect(urls).toEqual(["https://a.example/mine.gif", "https://a.example/theirs.gif", "https://a.example/old.gif"]);
    expect(useSettings.getState().settings.favoriteGifs.map((f) => f.url)).toEqual(urls);
  });

  it("keeps a sticker favourited on another device when this one favourites another", async () => {
    const server = fakeServer({ favoriteStickers: ["old", "gone"] });
    await loadFrom(server);
    server.doc = { ...server.doc, favoriteStickers: ["theirs", ...(server.doc["favoriteStickers"] as string[])] };
    server.version += 1;
    toggleFavoriteSticker("mine");
    toggleFavoriteSticker("gone");
    await useSettings.getState().flush();
    expect(server.doc["favoriteStickers"]).toEqual(["mine", "theirs", "old"]);
  });

  it("sends a change still waiting when the store is reset straight after", async () => {
    const server = fakeServer({ theme: "dark" });
    await loadFrom(server);
    useSettings.getState().patch({ theme: "ash" });
    // what stopping the session does when the app locks
    const sent = useSettings.getState().flush();
    useSettings.getState().reset();
    await sent;
    expect(server.doc["theme"]).toBe("ash");
  });

  it("tries a save that could not reach the server again later", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const server = fakeServer({ theme: "dark" });
    await loadFrom(server);
    server.fail = new TypeError("Failed to fetch");
    useSettings.getState().patch({ theme: "onyx" });
    await useSettings.getState().flush();
    expect(server.doc["theme"]).toBe("dark");
    server.fail = null;
    await vi.advanceTimersByTimeAsync(2_000);
    await useSettings.getState().flush();
    expect(server.doc["theme"]).toBe("onyx");
  });
});

describe("screen share settings", () => {
  it("carries an older stream quality over to a size and frame rate", async () => {
    await loadFrom({ doc: { voice: { streamQuality: "1080p60" } }, version: 1 });
    const v = useSettings.getState().settings.voice;
    expect(v.shareResolution).toBe("1080p");
    expect(v.shareFps).toBe(60);
    expect("streamQuality" in v).toBe(false);
  });

  it("keeps a size and frame rate already chosen", async () => {
    await loadFrom({ doc: { voice: { streamQuality: "720p30", shareResolution: "1440p", shareFps: 15 } }, version: 1 });
    const v = useSettings.getState().settings.voice;
    expect(v.shareResolution).toBe("1440p");
    expect(v.shareFps).toBe(15);
  });

  it("puts back a size or frame rate it does not offer", async () => {
    await loadFrom({ doc: { voice: { shareResolution: "16384p", shareFps: 100000 } }, version: 1 });
    const v = useSettings.getState().settings.voice;
    expect(v.shareResolution).toBe("1080p");
    expect(v.shareFps).toBe(30);
  });
});

describe("noise suppression", () => {
  const modeAfter = async (voice: Record<string, unknown>) => {
    await loadFrom({ doc: { voice }, version: 1 });
    const v = useSettings.getState().settings.voice;
    // the switch older copies of the app read always says whether it is on
    expect(v.noiseSuppression).toBe(v.noiseMode !== "off");
    return v.noiseMode;
  };

  it("is RNNoise unless it was turned off, for settings from before the modes", async () => {
    expect(await modeAfter({})).toBe("enhanced");
    expect(await modeAfter({ noiseSuppression: true })).toBe("enhanced");
    expect(await modeAfter({ noiseSuppression: false })).toBe("off");
  });

  it("keeps the mode chosen", async () => {
    expect(await modeAfter({ noiseMode: "standard", noiseSuppression: true })).toBe("standard");
    expect(await modeAfter({ noiseMode: "off", noiseSuppression: false })).toBe("off");
  });

  it("follows a copy of the app from before the modes turning it off or on", async () => {
    expect(await modeAfter({ noiseMode: "standard", noiseSuppression: false })).toBe("off");
    expect(await modeAfter({ noiseMode: "off", noiseSuppression: true })).toBe("enhanced");
  });

  it("puts back a mode it does not know", async () => {
    expect(await modeAfter({ noiseMode: "loud", noiseSuppression: true })).toBe("enhanced");
  });
});

describe("how the rail and the DM list are arranged", () => {
  it("keeps the defaults when a copy saved before them has none", async () => {
    await loadFrom({ doc: { theme: "light", serverPositions: ["2", "1"] }, version: 1 });
    const st = useSettings.getState().settings;
    expect(st.rail).toBeNull();
    expect(st.dmList).toEqual({ pins: [], groups: [] });
    expect(st.nexaClosed).toBe(0);
    expect(st.serverPositions).toEqual(["2", "1"]);
  });

  it("reads what another version wrote defensively", async () => {
    await loadFrom({ doc: { rail: { pinned: ["1", { id: "fa", servers: ["2"], color: "blue" }], rest: "3" }, dmList: { pins: ["10", 11], groups: [{ id: "gx", name: "Work", ids: ["10", "12"] }] }, nexaClosed: "soon" }, version: 1 });
    const st = useSettings.getState().settings;
    expect(st.rail).toEqual({ pinned: ["1", { id: "fa", name: "", color: null, servers: ["2"] }], rest: [] });
    expect(st.dmList).toEqual({ pins: ["10"], groups: [{ id: "gx", name: "Work", ids: ["12"] }] });
    expect(st.nexaClosed).toBe(0);
  });

  it("saves a change to the rail whole", async () => {
    const server = fakeServer({ rail: { pinned: [], rest: ["1", "2"] } });
    await loadFrom(server);
    useSettings.getState().patch({ rail: { pinned: ["2"], rest: [{ id: "fa", name: "Games", color: null, servers: ["1"] }] } });
    await useSettings.getState().flush();
    expect(server.doc["rail"]).toEqual({ pinned: ["2"], rest: [{ id: "fa", name: "Games", color: null, servers: ["1"] }] });
  });
});

describe("device settings", () => {
  it("treats a saved Chromium alias as the system's own device", async () => {
    await loadFrom({ doc: { voice: { inputDeviceId: "communications", inputDeviceLabel: "Communications - Headset", outputDeviceId: "default", cameraDeviceId: "cam1", cameraDeviceLabel: "Webcam" } }, version: 1 });
    const v = useSettings.getState().settings.voice;
    expect([v.inputDeviceId, v.inputDeviceLabel, v.outputDeviceId]).toEqual([null, null, null]);
    expect([v.cameraDeviceId, v.cameraDeviceLabel]).toEqual(["cam1", "Webcam"]);
  });
});

describe("typing settings", () => {
  it("starts with no Send button on a computer and no words of its own", async () => {
    await loadFrom({ doc: { theme: "dark" }, version: 1 });
    const st = useSettings.getState().settings;
    expect(st.sendButton).toBe(false);
    expect(st.spellWords).toEqual([]);
  });

  it("reads the Send button and the dictionary's words defensively", async () => {
    await loadFrom({ doc: { sendButton: "yes", spellWords: ["nexa", 7, "", "x".repeat(65), "kito"] }, version: 1 });
    const st = useSettings.getState().settings;
    expect(st.sendButton).toBe(false);
    expect(st.spellWords).toEqual(["nexa", "kito"]);
  });

  it("keeps a Send button switched on", async () => {
    await loadFrom({ doc: { sendButton: true, spellWords: ["wiz"] }, version: 1 });
    expect(useSettings.getState().settings.sendButton).toBe(true);
    expect(useSettings.getState().settings.spellWords).toEqual(["wiz"]);
  });
});
