import { afterEach, describe, expect, it, vi } from "vitest";

const app = vi.hoisted(() => ({ isPackaged: true }));
vi.mock("electron", () => ({ app }));
import { testHook } from "../src/main/config";

afterEach(() => vi.unstubAllEnvs());

describe("development overrides", () => {
  it("ignores every test override in a packaged app", () => {
    app.isPackaged = true;
    for (const name of ["EVILION_URL", "EVILION_USER_DATA", "EVILION_WINDOW_AUDIO", "EVILION_TEST_PROGRAM_AUDIO_DEVICE", "EVILION_SHARE_AUDIO_MODE", "EVILION_MOVE_ANSWER", "EVILION_NO_LOGIN_ITEM", "EVILION_UPDATE_TEST", "EVILION_UPDATE_NO_RELAUNCH", "EVILION_TEST_GAME_BEHAVIOUR"]) {
      vi.stubEnv(name, "test-value");
      expect(testHook(name), name).toBeUndefined();
    }
  });

  it("keeps overrides available for isolated development tests", () => {
    app.isPackaged = false;
    vi.stubEnv("EVILION_USER_DATA", "/temporary/test-profile");
    expect(testHook("EVILION_USER_DATA")).toBe("/temporary/test-profile");
  });
});
