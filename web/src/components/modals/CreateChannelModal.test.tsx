import { describe, expect, it } from "vitest";
import { slug, typedChannelName } from "./CreateChannelModal";

describe("a channel's name", () => {
  it("keeps spaces and capitals while it is typed", () => {
    expect(typedChannelName("Plans For The Weekend")).toBe("Plans For The Weekend");
    expect(typedChannelName("plans ")).toBe("plans ");
  });

  it("is trimmed with whitespace collapsed when it is saved", () => {
    expect(slug(" Plans  For\tNow ")).toBe("Plans For Now");
    expect(slug("Ünïcode Stays")).toBe("Ünïcode Stays");
  });
});
