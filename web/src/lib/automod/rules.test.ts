import { describe, expect, it } from "vitest";
import { checkPersonal } from "./checks";
import { readable } from "./rules";

describe("readable", () => {
  // A reported bug: AutoMod's phone number check stopped GIFs from the picker.
  const gif = "/media/proxy/1794038657-39U6OrMqVxXbKQNKfYiEew/aHR0cHM6Ly9zdGF0aWMua2xpcHkuY29tL2lpL2Q";

  it("reads nothing in a GIF's signed path", () => {
    expect(checkPersonal(gif, { phones: true })).not.toBeNull();
    expect(readable({ t: gif }).text).toBe("");
  });

  it("still reads words sent with such a path", () => {
    expect(readable({ t: `${gif} call 555-010-1234` }).text).toContain("555-010-1234");
  });
});
