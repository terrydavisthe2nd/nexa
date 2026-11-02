import { describe, expect, it } from "vitest";
import { contrast, luminance, toHex } from "@/lib/color";
import { contrastIssues } from "./contrast";
import { BACKGROUND_RANGE, deriveTheme, fitBackground, flipBackground, schemeOf } from "./derive";
import { TOKENS } from "./tokens";
import type { Scheme } from "./types";

/** A small deterministic generator, so a failure names a colour that fails again. */
function colours(seed: number, n: number): string[] {
  let x = seed;
  const next = () => {
    x = (x * 1103515245 + 12345) % 2147483648;
    return x / 2147483648;
  };
  return Array.from({ length: n }, () => toHex([next() * 255, next() * 255, next() * 255]));
}

/** Colours people pick that make a palette hard: near the background, near the text, pure primaries, greys. */
const AWKWARD = ["#ffffff", "#000000", "#fafafa", "#0a0a0f", "#808080", "#7f7f7f", "#ffff00", "#fff5cc", "#101018", "#ff0000", "#00ff00", "#0000ff", "#00ffff", "#ff00ff", "#2c2d32", "#f3f3f4"];

describe("a theme from a few colours", () => {
  const schemes: Scheme[] = ["dark", "light"];

  it("reads everywhere, whatever the background and accent", { timeout: 60_000 }, () => {
    const backgrounds = [...AWKWARD, ...colours(7, 4)];
    const accents = [...AWKWARD.slice(0, 6), ...colours(11, 2)];
    for (const scheme of schemes) {
      for (const background of backgrounds) {
        for (const accent of accents) {
          const issues = contrastIssues(deriveTheme({ background, accent, text: null, scheme }).tokens);
          expect(issues, `${scheme} ${background} ${accent}`).toEqual([]);
        }
      }
    }
  });

  it("reads everywhere with a text colour of the person's own, however close to the background", { timeout: 60_000 }, () => {
    for (const scheme of schemes) {
      for (const background of [...AWKWARD.slice(0, 8), ...colours(3, 2)]) {
        for (const text of [...AWKWARD.slice(0, 8), ...colours(5, 2)]) {
          const d = deriveTheme({ background, accent: "#8000ff", text, scheme });
          expect(contrastIssues(d.tokens), `${scheme} ${background} text ${text}`).toEqual([]);
        }
      }
    }
  });

  it("holds at high contrast too, with text at 7:1", () => {
    for (const scheme of schemes) {
      for (const background of AWKWARD) {
        const d = deriveTheme({ background, accent: "#5a00c8", text: null, scheme }, { contrast: "high" });
        expect(contrastIssues(d.tokens), `${scheme} ${background}`).toEqual([]);
        for (const panel of ["bg-base", "bg-raised", "bg-surface"] as const) {
          expect(contrast(d.tokens.text, d.tokens[panel])).toBeGreaterThanOrEqual(7);
          expect(contrast(d.tokens["text-muted"], d.tokens[panel])).toBeGreaterThanOrEqual(7);
        }
      }
    }
  });

  it("gives a value to every token, as a colour a theme can hold", () => {
    const t = deriveTheme({ background: "#18221d", accent: "#2e8052", text: null, scheme: "dark" }).tokens;
    for (const name of TOKENS) expect(t[name], name).toMatch(/^#[0-9a-f]{6}([0-9a-f]{2})?$/);
  });

  it("keeps the colours it was given when they already work", () => {
    const d = deriveTheme({ background: "#211a30", accent: "#8000ff", text: null, scheme: "dark" });
    expect(d.tokens["bg-raised"]).toBe("#211a30");
    expect(d.tokens.brand).toBe("#8000ff");
    expect(d.adjusted).toEqual({ background: false, accent: false, text: false });
  });

  it("says what it had to move", () => {
    const d = deriveTheme({ background: "#0a0a0f", accent: "#101018", text: "#15151a", scheme: "dark" });
    expect(d.adjusted.accent).toBe(true);
    expect(d.adjusted.text).toBe(true);
    // a very dark accent on a very dark page is lifted until a button can be told from the page
    expect(contrast(d.tokens.brand, d.tokens["bg-raised"])).toBeGreaterThanOrEqual(2);
    // and a pale one on a pale page is darkened
    const pale = deriveTheme({ background: "#fafafa", accent: "#fff5cc", text: null, scheme: "light" });
    expect(contrast(pale.tokens.brand, pale.tokens["bg-raised"])).toBeGreaterThanOrEqual(2);
    expect(contrast(pale.tokens.brand, pale.tokens["on-brand"])).toBeGreaterThanOrEqual(4.5);
  });

  it("orders the panels as the classic themes do", () => {
    const dark = deriveTheme({ background: "#323339", accent: "#8000ff", text: null, scheme: "dark" }).tokens;
    expect(luminance(dark["bg-base"])).toBeLessThan(luminance(dark["bg-raised"]));
    expect(luminance(dark["bg-surface"])).toBeGreaterThan(luminance(dark["bg-raised"]));
    expect(luminance(dark["bg-input"])).toBeLessThan(luminance(dark["bg-base"]));
    const light = deriveTheme({ background: "#fbf9ff", accent: "#7400f0", text: null, scheme: "light" }).tokens;
    expect(luminance(light["bg-base"])).toBeLessThan(luminance(light["bg-raised"]));
    expect(luminance(light["bg-surface"])).toBeLessThan(luminance(light["bg-base"]));
  });

  it("still separates the panels of a black page", () => {
    const t = deriveTheme({ background: "#000000", accent: "#8000ff", text: null, scheme: "dark" }).tokens;
    expect(t["bg-surface"]).not.toBe("#000000");
    expect(t["bg-input"]).not.toBe("#000000");
  });
});

describe("backgrounds and schemes", () => {
  it("moves a background into its scheme's range and no further", () => {
    for (const bg of [...AWKWARD, ...colours(9, 6)]) {
      for (const scheme of ["dark", "light"] as const) {
        const y = luminance(fitBackground(bg, scheme));
        const [lo, hi] = BACKGROUND_RANGE[scheme];
        expect(y).toBeGreaterThanOrEqual(lo - 0.002);
        expect(y).toBeLessThanOrEqual(hi + 0.002);
      }
    }
    expect(fitBackground("#2c2d32", "dark")).toBe("#2c2d32");
  });

  it("carries a background's tint over to the other scheme", () => {
    const light = flipBackground("#211a30", "light");
    expect(schemeOf(light)).toBe("light");
    const back = flipBackground(light, "dark");
    expect(schemeOf(back)).toBe("dark");
  });

  it("tells a dark background from a light one", () => {
    expect(schemeOf("#000000")).toBe("dark");
    expect(schemeOf("#2c2d32")).toBe("dark");
    expect(schemeOf("#f3f3f4")).toBe("light");
    expect(schemeOf("#ffffff")).toBe("light");
  });
});
