// @vitest-environment jsdom
import type { VoiceState } from "@/api/types";
import { useKeys } from "@/stores/keys";
import { useSettings } from "@/stores/settings";
import { useVoice } from "@/stores/voice";
import { applyTransmit, join, leave, onMine, retryMic, setPttHeld } from "./engine";
import { getMic } from "./media";

vi.mock("@/lib/sounds", () => ({ playSound: vi.fn() }));
vi.mock("@/lib/busy", () => ({ holdReload: () => () => {} }));
vi.mock("@/gateway/session", () => ({ gateway: () => null }));
vi.mock("./session", () => ({ asGuest: () => false, me: () => "me", sendState: vi.fn(), iceConfig: vi.fn(async () => null) }));
vi.mock("./guestKeys", () => ({ hasGuestKeys: () => false, openRelayedKeys: vi.fn(), sealKeysForGuest: vi.fn() }));
vi.mock("./e2ee", () => ({ FrameCrypto: { supported: () => true } }));
vi.mock("./sfu", () => ({ SfuTransport: class {} }));
vi.mock("./media", () => ({
  audioContext: () => ({ state: "running", addEventListener: vi.fn(), removeEventListener: vi.fn() }),
  getMic: vi.fn(),
  getCamera: vi.fn(),
  getScreen: vi.fn(),
  stopScreen: vi.fn(),
  holdAudio: () => () => {},
  lastInputChoice: () => "default",
  sensitivityToThreshold: () => 0.1,
  sentStream: (_ctx: unknown, mic: unknown) => ({ stream: mic, gain: null, source: null }),
  LevelMeter: class {
    level() {
      return meter.level;
    }
    close() {}
  },
}));
// what the microphone's meter hears, for voice activity
const meter = vi.hoisted(() => ({ level: 0 }));
// the peer-to-peer transport, as a set of calls to look at
const transport = vi.hoisted(() => ({
  setLocalAudio: vi.fn(),
  setLocalVideo: vi.fn(),
  setLocalScreen: vi.fn(),
  addPeer: vi.fn(),
  removePeer: vi.fn(),
  peerIds: vi.fn(() => [] as string[]),
  handleSignal: vi.fn(),
  setRoster: vi.fn(),
  joined: vi.fn(),
  reconnected: vi.fn(),
  moveTo: vi.fn(),
  keysArrived: vi.fn(),
  setWatching: vi.fn(),
  setVolume: vi.fn(),
  setLocalMute: vi.fn(),
  setStreamVolume: vi.fn(),
  setStreamMuted: vi.fn(),
  setOutputMuted: vi.fn(),
  setMasterVolume: vi.fn(),
  setOutputDevice: vi.fn(async () => {}),
  stats: vi.fn(async () => ({ rtt: 0, loss: 0 })),
  close: vi.fn(),
}));
vi.mock("./mesh", () => ({
  MeshTransport: class {
    constructor() {
      return transport;
    }
  },
}));

function micStream() {
  // the call is handed a copy of the microphone's track, which it silences on its own
  const copies: { enabled: boolean; stop: ReturnType<typeof vi.fn> }[] = [];
  const track = {
    enabled: true,
    readyState: "live",
    onended: null,
    stop: vi.fn(),
    clone: () => {
      const c = { enabled: true, stop: vi.fn() };
      copies.push(c);
      return c;
    },
  };
  return { track, copies, stream: { getAudioTracks: () => [track], getTracks: () => [track] } as unknown as MediaStream };
}

function state(channel: string): VoiceState {
  return { user_id: "me", channel_id: channel, session_id: "s1", self_mute: false, self_deaf: false, video: false, streaming: false, server_mute: false, server_deaf: false, joined_at: 1 };
}

const ice = { servers: [], sfu: false };

describe("the call engine", () => {
  beforeEach(() => {
    leave(false);
    vi.clearAllMocks();
    useVoice.getState().clear();
    useSettings.setState((s) => ({ settings: { ...s.settings, voice: { ...s.settings.voice, mode: "vad" } } }));
  });

  it("follows a moderator's move to the new room, and hands it that room's key once it is here", async () => {
    vi.mocked(getMic).mockResolvedValue(micStream().stream);
    let keyHere!: () => void;
    useKeys.setState({ ensureConvKey: vi.fn(() => new Promise((resolve) => (keyHere = () => resolve({ epoch: 1, root: new Uint8Array(32) })))) as never });
    expect(await join("c1", ice, () => true, vi.fn())).toBe(true);
    onMine(state("c1"));
    onMine(state("c2"));
    expect(transport.moveTo).toHaveBeenCalledWith("c2");
    expect(transport.keysArrived).not.toHaveBeenCalled();
    keyHere();
    await vi.waitFor(() => expect(transport.keysArrived).toHaveBeenCalled());
  });

  it("hands the call a key the moment the key store has a new one", async () => {
    vi.mocked(getMic).mockResolvedValue(micStream().stream);
    await join("c1", ice, () => true, vi.fn());
    transport.keysArrived.mockClear();
    useKeys.setState({ waiting: {} });
    expect(transport.keysArrived).toHaveBeenCalledTimes(1);
    leave(false);
    useKeys.setState({ waiting: {} });
    expect(transport.keysArrived).toHaveBeenCalledTimes(1);
  });

  it("brings a microphone that was refused into the call on Try again", async () => {
    vi.mocked(getMic).mockRejectedValueOnce(new DOMException("denied", "NotAllowedError"));
    await join("c1", ice, () => true, vi.fn());
    expect(useVoice.getState().micBlocked).toBe(true);
    const { stream, copies } = micStream();
    vi.mocked(getMic).mockResolvedValueOnce(stream);
    await retryMic();
    expect(useVoice.getState().micBlocked).toBe(false);
    expect(transport.setLocalAudio).toHaveBeenLastCalledWith(copies[0]);
  });

  it("sends with push to talk only while the button or key is held, on every device", async () => {
    useSettings.setState((s) => ({ settings: { ...s.settings, voice: { ...s.settings.voice, mode: "ptt" } } }));
    const { stream, track, copies } = micStream();
    vi.mocked(getMic).mockResolvedValue(stream);
    await join("c1", ice, () => true, vi.fn());
    applyTransmit();
    expect(track.enabled).toBe(false);
    expect(copies[0]!.enabled).toBe(false);
    setPttHeld(true);
    expect(track.enabled).toBe(true);
    expect(copies[0]!.enabled).toBe(true);
    expect(useVoice.getState().pttHeld).toBe(true);
    setPttHeld(false);
    expect(track.enabled).toBe(false);
    expect(copies[0]!.enabled).toBe(false);
  });

  it("silences its voice between phrases instead of taking it off the call", async () => {
    vi.useFakeTimers();
    try {
      meter.level = 0;
      const { stream, track, copies } = micStream();
      vi.mocked(getMic).mockResolvedValue(stream);
      await join("c1", ice, () => true, vi.fn());
      const sent = copies[0]!;
      expect(transport.setLocalAudio).toHaveBeenCalledWith(sent);
      vi.advanceTimersByTime(100);
      expect(sent.enabled).toBe(false);
      // speech is heard within a reading or two, and let through at once
      meter.level = 0.5;
      vi.advanceTimersByTime(40);
      expect(sent.enabled).toBe(true);
      // it stays through a short pause, and goes quiet after
      meter.level = 0;
      vi.advanceTimersByTime(200);
      expect(sent.enabled).toBe(true);
      vi.advanceTimersByTime(400);
      expect(sent.enabled).toBe(false);
      // the microphone itself stays on for the meter, and the call never lost its track
      expect(track.enabled).toBe(true);
      expect(transport.setLocalAudio).not.toHaveBeenCalledWith(null);
      leave(false);
      expect(sent.stop).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});
