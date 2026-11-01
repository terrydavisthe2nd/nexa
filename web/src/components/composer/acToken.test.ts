import { describe, expect, it } from "vitest";
import { acToken } from "./Composer";

describe("what asks for suggestions", () => {
  it("asks for the emoji used most on a bare colon starting a word, with smart suggestions on", () => {
    expect(acToken(":", 1, false, true)).toEqual({ kind: "emoji", query: "", start: 0 });
    expect(acToken("so nice :", 9, false, true)).toEqual({ kind: "emoji", query: "", start: 8 });
    // not inside a word or a time, and not with smart suggestions off
    expect(acToken("note:", 5, false, true)).toBeNull();
    expect(acToken("at 12:", 6, false, true)).toBeNull();
    expect(acToken(":", 1, false, false)).toBeNull();
  });

  it("asks nothing for one letter, so :D and :P still send as they are", () => {
    expect(acToken(":D", 2, false, true)).toBeNull();
    expect(acToken(":he", 3, false, true)).toEqual({ kind: "emoji", query: "he", start: 0 });
  });
});
