import { describe, expect, it } from "vitest";
import { STREAM_AUDIO_BITRATE, tuneOpus, tunedLocal, tunedRemote, VOICE_BITRATE, VOICE_STEREO_BITRATE } from "./opus";

const answer = [
  "v=0",
  "o=- 1 2 IN IP4 127.0.0.1",
  "s=-",
  "t=0 0",
  "m=audio 9 UDP/TLS/RTP/SAVPF 111 63",
  "a=mid:0",
  "a=rtpmap:111 opus/48000/2",
  "a=rtcp-fb:111 transport-cc",
  "a=fmtp:111 minptime=10;useinbandfec=1",
  "a=rtpmap:63 red/48000/2",
  "a=fmtp:63 111/111",
  "m=video 9 UDP/TLS/RTP/SAVPF 96",
  "a=mid:1",
  "a=rtpmap:96 VP8/90000",
  "m=audio 9 UDP/TLS/RTP/SAVPF 109",
  "a=mid:2",
  "a=rtpmap:109 opus/48000/2",
  "",
].join("\r\n");

describe("stereo voices", () => {
  it("sends two channels only when asked, keeping DTX", () => {
    expect(tuneOpus(answer)).not.toContain("stereo=1");
    expect(tuneOpus(answer, new Set(), true, true)).toContain(`usedtx=1;maxaveragebitrate=${VOICE_STEREO_BITRATE};stereo=1;sprop-stereo=1`);
  });
  it("asks to play in stereo only in the sections it is given, and never for a share's tuning", () => {
    const out = tunedLocal({ type: "answer", sdp: answer }, new Set(), new Set(["2"])).sdp!;
    expect(out).toContain("a=rtpmap:109 opus/48000/2\r\na=fmtp:109 stereo=1");
    expect(out).toContain("a=fmtp:111 minptime=10;useinbandfec=1\r\n");
    expect(tunedLocal({ type: "answer", sdp: answer }, new Set(), "all").sdp).toContain("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1");
  });
});

describe("tuneOpus", () => {
  it("asks every Opus section for DTX and the voice bitrate, keeping what was there", () => {
    const out = tuneOpus(answer);
    expect(out).toContain(`a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=${VOICE_BITRATE}`);
    // a section with no fmtp line of its own gets one, straight after its mapping
    expect(out).toContain(`a=rtpmap:109 opus/48000/2\r\na=fmtp:109 usedtx=1;maxaveragebitrate=${VOICE_BITRATE}`);
    // other codecs are left alone
    expect(out).toContain("a=fmtp:63 111/111");
    expect(out).toContain("a=rtpmap:96 VP8/90000\r\nm=audio");
    expect(out.endsWith("\r\n")).toBe(true);
  });

  it("overrides values it sets rather than repeating them", () => {
    const sdp = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=0;maxaveragebitrate=20000;stereo=0\n";
    expect(tuneOpus(sdp)).toBe(`m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 usedtx=1;maxaveragebitrate=${VOICE_BITRATE};stereo=0\n`);
  });

  it("gives the same answer when it runs twice", () => {
    expect(tuneOpus(tuneOpus(answer))).toBe(tuneOpus(answer));
  });

  it("leaves a description without Opus as it is", () => {
    const sdp = "m=video 9 RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n";
    expect(tuneOpus(sdp)).toBe(sdp);
  });

  it("asks a share's sound for stereo at the stream bitrate, without DTX", () => {
    const out = tuneOpus(answer, new Set(["2"]));
    expect(out).toContain(`a=fmtp:109 usedtx=0;maxaveragebitrate=${STREAM_AUDIO_BITRATE};stereo=1;sprop-stereo=1`);
    // the voice next to it keeps the voice's tuning
    expect(out).toContain(`a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=${VOICE_BITRATE}`);
    expect(tuneOpus(out, new Set(["2"]))).toBe(out);
  });

  it("finds a section's mid wherever in the section it is", () => {
    const sdp = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 minptime=10\na=mid:7\n";
    expect(tuneOpus(sdp, new Set(["7"]))).toContain("a=fmtp:111 minptime=10;usedtx=0;");
  });

  it("tunes only a share's sound in this device's own descriptions", () => {
    const local = tunedLocal({ type: "answer", sdp: answer }, new Set(["2"]));
    expect(local.sdp).toContain("a=fmtp:111 minptime=10;useinbandfec=1\r\n");
    expect(local.sdp).toContain("a=fmtp:109 usedtx=0;");
    // with no share's sound there is nothing to change
    expect(tunedLocal({ type: "offer", sdp: answer }, new Set()).sdp).toBe(answer);
  });

  it("passes rollbacks and empty descriptions through", () => {
    expect(tunedRemote({ type: "rollback" })).toEqual({ type: "rollback" });
    const d = tunedRemote({ type: "answer", sdp: answer });
    expect(d.type).toBe("answer");
    expect(d.sdp).toContain("usedtx=1");
  });
});
