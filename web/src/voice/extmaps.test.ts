import { describe, expect, it } from "vitest";
import { ExtmapHistory } from "./extmaps";

const RID = "urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id";
const MID = "urn:ietf:params:rtp-hdrext:sdes:mid";
const TWCC = "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01";

const sdp = (...sections: string[][]) => ["v=0", "s=-", ...sections.flat(), ""].join("\r\n");
const audio = (mid: string, ext: string[]) => ["m=audio 9 UDP/TLS/RTP/SAVPF 111", `a=mid:${mid}`, ...ext];
const video = (mid: string, ext: string[]) => ["m=video 9 UDP/TLS/RTP/SAVPF 96", `a=mid:${mid}`, ...ext];

describe("ExtmapHistory", () => {
  // what Cloudflare sent on the second watch of a share, which Chromium refused
  it("drops the ids a recycled mid used for something else, and nothing more", () => {
    const h = new ExtmapHistory();
    h.note(sdp(audio("0", [`a=extmap:3 ${TWCC}`]), video("3", [`a=extmap:1 ${RID}`, `a=extmap:4 ${MID}`])));
    const offer = { type: "offer" as const, sdp: sdp(audio("0", [`a=extmap:3 ${TWCC}`]), video("3", [`a=extmap:1 ${TWCC}`, `a=extmap:4 ${MID}`, `a=extmap:3 ${RID}`]), video("5", [`a=extmap:1 ${TWCC}`])) };
    const out = h.fit(offer).sdp;
    expect(out).toBe(sdp(audio("0", [`a=extmap:3 ${TWCC}`]), video("3", [`a=extmap:4 ${MID}`, `a=extmap:3 ${RID}`]), video("5", [`a=extmap:1 ${TWCC}`])));
  });

  it("hands a description with nothing to drop back as it was", () => {
    const h = new ExtmapHistory();
    const d = { type: "answer" as const, sdp: sdp(audio("0", [`a=extmap:3 ${TWCC}`])) };
    h.note(d.sdp);
    expect(h.fit(d)).toBe(d);
  });
});
