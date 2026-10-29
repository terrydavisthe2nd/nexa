import { isCurrentRelease, type DesktopRelease } from "./desktop";

const release = (version: string) => ({ version } as DesktopRelease);

describe("isCurrentRelease", () => {
  it("stops offering the Electron 1.x builds", () => {
    expect(isCurrentRelease(release("1.2.10"))).toBe(false);
    expect(isCurrentRelease(release("v1.9.0"))).toBe(false);
  });

  it("offers Tauri builds from 2.0.0 on", () => {
    expect(isCurrentRelease(release("2.0.0"))).toBe(true);
    expect(isCurrentRelease(release("v2.1.0"))).toBe(true);
    expect(isCurrentRelease(release("3.0.0"))).toBe(true);
  });

  it("does not offer a version it cannot read", () => {
    expect(isCurrentRelease(release("nightly"))).toBe(false);
  });
});
