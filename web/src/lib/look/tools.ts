import { hexToOklch, oklchToHex, parseColour, toThemeHex, type Oklch, type Rgb } from "@/lib/color";
import { fitBackground } from "./derive";
import { TOKENS, type TokenMap, type TokenName } from "./tokens";
import { sourceTokens } from "./customTheme";
import type { CustomTheme, Scheme, SimpleColours } from "./types";

/*
 * The theme builder's tools that work on a whole palette at once: Tune's
 * sliders, the colour-vision previews, colour ideas and Surprise Me. None of
 * them is saved as such: what they make is a palette, kept as the colours
 * set by hand, so a theme made with them reads the same on every device and
 * every version. Android's ui/theme/look/ThemeTools.kt is the same maths.
 */

/** Tune's sliders, each from -100 to 100 except hue, which turns -180 to 180 degrees. Zero leaves a colour as it is. */
export interface Tune {
  hue: number;
  saturation: number;
  brightness: number;
  contrast: number;
}

export const NEUTRAL_TUNE: Tune = { hue: 0, saturation: 0, brightness: 0, contrast: 0 };
export const isNeutral = (t: Tune): boolean => t.hue === 0 && t.saturation === 0 && t.brightness === 0 && t.contrast === 0;

/** The large areas: what Brightness moves, and what Contrast moves text away from. */
export const SURFACES: ReadonlySet<TokenName> = new Set<TokenName>(["bg-base", "bg-raised", "bg-surface", "bg-floating", "bg-input", "bg-composer", "embed-bg", "code-bg", "tooltip-bg", "spoiler-revealed"]);

/** The colours that mean something (online, a warning, danger), left alone unless asked, so green still means online. */
export const STATUS: ReadonlySet<TokenName> = new Set<TokenName>(["positive", "positive-hover", "on-positive", "warning", "text-warning", "on-warning", "danger", "danger-hover", "on-danger", "text-danger", "status-online", "status-idle", "status-dnd", "status-offline"]);

/** What reads on the surfaces, which Contrast pushes away from them or draws towards them. */
const READS: ReadonlySet<TokenName> = new Set<TokenName>(["text-strong", "text", "text-subtle", "text-muted", "text-channel", "text-placeholder", "icon", "link", "text-brand", "mention-fg", "text-warning", "text-danger", "code-keyword", "code-string", "code-number", "code-comment", "code-title", "code-type", "code-variable", "status-online", "status-idle", "status-dnd", "status-offline"]);

const clamp01 = (x: number) => Math.min(1, Math.max(0, x));

/** A token's value through `f` in OKLCH, its opacity kept. */
function through(value: string, f: (o: Oklch) => Oklch): string {
  const p = parseColour(value);
  if (!p) return value;
  const o = hexToOklch(toThemeHex(p.rgb));
  if (!o) return value;
  const out = parseColour(oklchToHex(f(o)));
  return out ? toThemeHex(out.rgb, p.alpha) : value;
}

/**
 * The palette with Tune's sliders applied. Hue turns every colour round the
 * wheel, Saturation makes them richer or greyer, Brightness lifts or lowers
 * the surfaces, and Contrast spreads text and the surfaces it sits on apart
 * (or draws them together). Status colours change only with `status`.
 */
export function tunePalette(tokens: TokenMap, tune: Tune, status: boolean): TokenMap {
  if (isNeutral(tune)) return tokens;
  const page = hexToOklch(toThemeHex(parseColour(tokens["bg-raised"])?.rgb ?? [0, 0, 0]))?.l ?? 0;
  const lift = (tune.brightness / 100) * 0.3;
  const out = { ...tokens };
  for (const tk of TOKENS) {
    if (!status && STATUS.has(tk)) continue;
    out[tk] = through(tokens[tk], (o) => {
      let { l, c, h } = o;
      h = (((h + tune.hue) % 360) + 360) % 360;
      c = Math.max(0, c * (1 + tune.saturation / 100));
      if (SURFACES.has(tk)) l = clamp01(l + lift);
      else if (READS.has(tk)) {
        const moved = page + lift;
        l = clamp01(moved + (l - page) * (1 + tune.contrast / 100));
      }
      return { l, c, h };
    });
  }
  return out;
}

/* Seeing the theme as people with a colour-vision deficiency do. */

export type Vision = "typical" | "protanopia" | "deuteranopia" | "tritanopia" | "greyscale";
export const VISIONS: readonly Vision[] = ["typical", "protanopia", "deuteranopia", "tritanopia", "greyscale"];

/** Machado, Oliveira and Fernandes (2009) at full severity, applied to linear RGB. */
const MATRIX: Record<Exclude<Vision, "typical" | "greyscale">, readonly number[]> = {
  protanopia: [0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998],
  deuteranopia: [0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.01182, 0.04294, 0.968881],
  tritanopia: [1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.3039],
};

const lin = (v: number) => {
  const c = v / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};
const unlin = (v: number) => 255 * (v <= 0.0031308 ? 12.92 * Math.max(0, v) : 1.055 * Math.min(1, v) ** (1 / 2.4) - 0.055);

export function seeAs(rgb: Rgb, vision: Vision): Rgb {
  if (vision === "typical") return rgb;
  const [r, g, b] = rgb.map(lin) as Rgb;
  if (vision === "greyscale") {
    const y = unlin(0.2126 * r + 0.7152 * g + 0.0722 * b);
    return [y, y, y];
  }
  const m = MATRIX[vision];
  return [unlin(m[0]! * r + m[1]! * g + m[2]! * b), unlin(m[3]! * r + m[4]! * g + m[5]! * b), unlin(m[6]! * r + m[7]! * g + m[8]! * b)];
}

/** A palette as someone with `vision` sees it, for the preview only. */
export function visionPalette(tokens: TokenMap, vision: Vision): TokenMap {
  if (vision === "typical") return tokens;
  const out = { ...tokens };
  for (const tk of TOKENS) {
    const p = parseColour(tokens[tk]);
    if (p) out[tk] = toThemeHex(seeAs(p.rgb, vision), p.alpha);
  }
  return out;
}

/* Ideas: colours offered to try with one press. */

const SPECTRUM = [25, 55, 95, 150, 190, 230, 265, 300, 335];

/**
 * Accents to try: the current one turned round the wheel (its neighbours,
 * the three-way split and its opposite), then a spread of hues at a
 * lightness that reads on this kind of background. No repeats.
 */
export function accentIdeas(accent: string, scheme: Scheme): string[] {
  const o = hexToOklch(accent) ?? { l: 0.6, c: 0.2, h: 290 };
  const c = Math.max(0.12, Math.min(0.22, o.c));
  const l = scheme === "dark" ? 0.64 : 0.55;
  const turned = [-30, 30, 120, -120, 180].map((d) => oklchToHex({ l: o.l, c: o.c, h: (o.h + d + 360) % 360 }));
  const spread = SPECTRUM.map((h) => oklchToHex({ l, c, h }));
  return [...new Set([...turned, ...spread])].filter((x) => x !== accent).slice(0, 14);
}

/**
 * Backgrounds to try for this kind of theme: plain, then tinted with the
 * accent's hue at two strengths, its opposite, warm and cool. Each is fitted
 * to the scheme as Simple fits a background picked by hand.
 */
export function backgroundIdeas(accent: string, scheme: Scheme): string[] {
  const h = hexToOklch(accent)?.h ?? 290;
  const l = scheme === "dark" ? 0.2 : 0.975;
  const tints: [number, number][] = [
    [0, 0],
    [h, 0.012],
    [h, 0.035],
    [(h + 180) % 360, 0.025],
    [60, 0.02],
    [235, 0.025],
    [150, 0.02],
  ];
  return [...new Set(tints.map(([hue, c]) => fitBackground(oklchToHex({ l, c, h: hue }), scheme)))];
}

/**
 * A theme to start from at random: dark more often than light, a background
 * barely tinted, an accent that is either the background's own hue or a
 * pleasing turn from it. derive.ts keeps the rest readable.
 */
export function surprise(random: () => number = Math.random): SimpleColours {
  const scheme: Scheme = random() < 0.72 ? "dark" : "light";
  const bgHue = random() * 360;
  const turns = [0, 30, -30, 150, 180, 210, 120];
  const accentHue = (bgHue + turns[Math.floor(random() * turns.length)]! + 360) % 360;
  const background = fitBackground(oklchToHex({ l: scheme === "dark" ? 0.19 : 0.975, c: 0.005 + random() * 0.04, h: bgHue }), scheme);
  const accent = oklchToHex({ l: scheme === "dark" ? 0.6 + random() * 0.12 : 0.5 + random() * 0.1, c: 0.13 + random() * 0.1, h: accentHue });
  return { background, accent, text: null, scheme };
}

/**
 * `theme` changed so that it draws `next` where it drew `current`: each
 * colour that moved becomes a colour set by hand, unless it moved back to
 * what the theme's source gives, which is then not set by hand any more.
 */
export function withPalette(theme: CustomTheme, next: TokenMap, current: TokenMap): CustomTheme {
  const base = sourceTokens(theme.source);
  const overrides = { ...theme.overrides };
  for (const tk of TOKENS) {
    if (next[tk] === current[tk]) continue;
    if (base[tk] === next[tk]) delete overrides[tk];
    else overrides[tk] = next[tk];
  }
  return { ...theme, overrides };
}
