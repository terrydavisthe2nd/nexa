import { blend, contrast, luminance, parseColour, parseHex, readableOn, rgbToHsl, shade, toHex, tuneForContrast, withAlpha } from "@/lib/color";
import { storage } from "@/lib/storage";
import { isCustomId, resolveCustom, customUnderlines, sanitizeThemes } from "@/lib/look/customTheme";
import { presetById, presetTokens } from "@/lib/look/presets";
import { activeSeason, seasonalTokens } from "@/lib/look/seasonal";
import { declarations, isClassic, type TokenMap } from "@/lib/look/tokens";
import type { ClassicTheme, CustomTheme, Scheme } from "@/lib/look/types";
import type { Settings } from "@/stores/settings";

/** What applyTheme draws the page with. */
export type Appearance = Pick<Settings, "theme" | "accent" | "fontScale" | "reducedMotion" | "underlineLinks"> & {
  animateNames?: boolean;
  /** The app's typeface; the default one when not said. */
  fontFace?: Settings["fontFace"];
  /** The custom themes `theme` may name. */
  customThemes?: CustomTheme[];
  /** Which themes "Sync with system" moves between; Dark and Light when not set. */
  systemThemes?: { dark: string; light: string };
};

/** A theme being edited in the builder, drawn over the whole app until it is saved or dropped. */
export interface ThemeDraft {
  tokens: TokenMap;
  scheme: Scheme;
  /** The custom theme being edited, so `data-look` names it while it is. */
  id?: string;
}

/**
 * The surface in each theme that is hardest to read on, which is where anything
 * derived from the accent has to be measured. A dark theme's derived colours
 * come out light, so their worst case is the palest panel it has; a light
 * theme's come out dark, so theirs is the greyest. Only the accent is known at
 * run time — these are not, because they live in a stylesheet.
 */
export const SURFACE_BG: Record<ClassicTheme, string> = {
  dark: "#2b2636",
  light: "#eae8f3",
  ash: "#27282e",
  onyx: "#16141b",
};

/** How much of the accent a mention pill is tinted with, resting and hovered. */
const MENTION_ALPHA = { dark: [0.26, 0.42], light: [0.16, 0.32] } as const;

/** Where an older version of this file put the accent: on the root element's own style. */
const ACCENT_VARS = ["--brand", "--brand-hover", "--brand-active", "--on-brand", "--focus-ring", "--mention-bg", "--mention-bg-hover", "--mention-fg", "--text-brand"];

/**
 * `brand` moved `delta` along the lightness scale, pulled back towards where it
 * started for as long as the move would cost the fill the text that goes on it.
 * At worst it gives the colour back unchanged, which is always readable.
 */
function shadeKeeping(brand: string, fg: string, delta: number): string {
  for (let i = 10; i > 0; i--) {
    const out = shade(brand, (delta * i) / 10);
    if (contrast(out, fg) >= 4.5) return out;
  }
  return brand;
}

const prefersLight = (): boolean => typeof window !== "undefined" && !!window.matchMedia?.("(prefers-color-scheme: light)").matches;

/**
 * Every custom property a chosen accent implies, worked out from the colour
 * itself rather than assumed. The picker takes any colour at all, so nothing
 * here may hold only for the ten presets: the foreground is the one of black
 * and white that is further from the fill, the focus ring is nudged until it
 * can be seen against the page, and the mention pill's text is nudged until it
 * can be read on the pill. `surface` is the panel that is hardest to read on.
 */
export function accentVarsOn(accent: string, surface: string, scheme: Scheme): Record<string, string> | null {
  const rgb = parseHex(accent);
  if (!rgb) return null;
  const brand = toHex(rgb);
  const fg = readableOn(brand);
  // Hover darkens the fill, as it always has, except where the accent is
  // already so dark that going further would flatten it to black and leave
  // nothing to see; those brighten instead.
  const dir = rgbToHsl(rgb).l > 0.22 ? -1 : 1;
  const hover = shadeKeeping(brand, fg, dir * 0.1);
  const active = shadeKeeping(brand, fg, dir * 0.18);
  // A mention reads as a tint of the accent, lighter on a light page so the
  // dark text that then goes on it still has somewhere to be dark against. Its
  // own colour is measured on the hovered pill, which is the stronger tint.
  const [rest, hovered] = MENTION_ALPHA[scheme];
  const pill = toHex(blend(brand, surface, hovered));
  return {
    "--brand": brand,
    "--brand-hover": hover,
    "--brand-active": active,
    "--on-brand": fg,
    "--focus-ring": tuneForContrast(brand, surface, 3),
    "--mention-bg": withAlpha(brand, rest),
    "--mention-bg-hover": withAlpha(brand, hovered),
    "--mention-fg": tuneForContrast(brand, pill, 4.5),
    "--text-brand": tuneForContrast(brand, surface, 4.5),
  };
}

/** accentVarsOn for one of the classic themes. */
export function accentVars(accent: string, theme: ClassicTheme): Record<string, string> | null {
  return accentVarsOn(accent, SURFACE_BG[theme], theme === "light" ? "light" : "dark");
}

/** What a theme choice comes to on this device, at this moment. */
export interface Look {
  /** The theme drawn: "system" is already one of its pair. */
  id: string;
  scheme: Scheme;
  /**
   * The classic theme set on `data-theme`, under the tokens. tokens.css has a
   * few rules per theme (a call's stage keeps the dark palette in a light
   * theme), and a light preset needs those as much as Light does.
   */
  classic: ClassicTheme;
  /** The palette laid over the stylesheet's; null for a classic theme, which the stylesheet draws. */
  tokens: TokenMap | null;
  underlineLinks: boolean;
}

function lookOf(id: string, customs: readonly CustomTheme[]): Look | null {
  if (isClassic(id)) return { id, scheme: id === "light" ? "light" : "dark", classic: id, tokens: null, underlineLinks: false };
  const preset = presetById(id);
  if (preset) return { id, scheme: preset.scheme, classic: preset.scheme, tokens: presetTokens(id), underlineLinks: preset.underlineLinks };
  // a seasonal theme out of its season is not there to draw, so it comes out as Dark below
  const season = activeSeason(id, new Date());
  if (season) return { id, scheme: season.scheme, classic: season.scheme, tokens: seasonalTokens(id), underlineLinks: false };
  const custom = isCustomId(id) ? customs.find((c) => c.id === id) : undefined;
  if (!custom) return null;
  const r = resolveCustom(custom);
  return { id, scheme: r.scheme, classic: r.scheme, tokens: r.tokens, underlineLinks: customUnderlines(custom) };
}

/**
 * The theme to draw. A theme that is not there any more (a custom one deleted
 * on another device, or a seasonal one whose season is over) draws as Dark
 * rather than as nothing.
 */
export function resolveLook(a: Appearance, draft?: ThemeDraft | null): Look {
  if (draft) return { id: draft.id ?? "draft", scheme: draft.scheme, classic: draft.scheme, tokens: draft.tokens, underlineLinks: false };
  let id = a.theme;
  if (id === "system") id = prefersLight() ? a.systemThemes?.light ?? "light" : a.systemThemes?.dark ?? "dark";
  return lookOf(id, a.customThemes ?? []) ?? lookOf(prefersLight() && a.theme === "system" ? "light" : "dark", [])!;
}

/** Kept for callers that only need to know dark or light. */
export function resolveTheme(theme: Settings["theme"]): ClassicTheme {
  return resolveLook({ theme, accent: null, fontScale: 1, reducedMotion: false, underlineLinks: false }).classic;
}

/** The panel of a theme that anything worked out from the accent has to be read against. */
export function hardestSurface(look: Look): string {
  if (!look.tokens) return SURFACE_BG[look.classic];
  const t = look.tokens;
  const panels = [t["bg-surface"], t["bg-raised"], t["bg-base"], t["bg-floating"]].map((v) => toHex(parseColour(v)?.rgb ?? [0, 0, 0]));
  return panels.reduce((worst, p) => ((look.scheme === "dark" ? luminance(p) > luminance(worst) : luminance(p) < luminance(worst)) ? p : worst));
}

const THEME_STYLE = "nexa-theme";

/**
 * The style element a theme's colours are written into. It goes at the end of
 * the head, after the app's own stylesheet, so a `:root` rule in it outranks
 * tokens.css by order alone; custom CSS goes later still (lib/look/styles.ts),
 * so a person's own `:root { --brand: … }` wins over the theme they picked.
 */
function themeStyle(): HTMLStyleElement {
  let el = document.getElementById(THEME_STYLE) as HTMLStyleElement | null;
  if (!el) {
    el = document.createElement("style");
    el.id = THEME_STYLE;
    document.head.append(el);
  }
  return el;
}

/** What applyTheme last put on the page, to tell a change of theme from the same one again. */
let drawn: string | null = null;

/** Applies theme, accent, font scale and motion preferences to the document. */
export function applyTheme(s: Appearance, draft?: ThemeDraft | null): void {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  const look = resolveLook(s, draft);
  // Another theme (or accent, or size) goes on at once everywhere: controls
  // easing from the old colours while the rest of the page has already
  // changed read as a flicker, so transitions are held while it is applied
  // and let go once every style has its new value (base.css).
  const scale = String(Math.min(1.5, Math.max(0.75, s.fontScale || 1)));
  const drawing = [look.id, look.classic, look.scheme, scale, s.accent ?? "", s.fontFace ?? "", JSON.stringify(look.tokens ?? null)].join("|");
  const switching = drawn !== null && drawn !== drawing;
  drawn = drawing;
  if (switching) root.setAttribute("data-theme-switching", "");
  try {
    paintTheme(root, s, look, draft, scale);
  } finally {
    if (switching) {
      void root.offsetWidth;
      root.removeAttribute("data-theme-switching");
    }
  }
}

function paintTheme(root: HTMLElement, s: Appearance, look: ReturnType<typeof resolveLook>, draft: ThemeDraft | null | undefined, scale: string): void {
  if (look.classic === "dark") delete root.dataset["theme"];
  else root.dataset["theme"] = look.classic;
  // for custom CSS that wants to style one theme and not another
  root.dataset["look"] = look.id;
  // light or dark, for what draws itself outside the theme's tokens (the captcha)
  root.dataset["scheme"] = look.scheme;
  root.style.setProperty("--font-scale", scale);
  for (const v of ACCENT_VARS) root.style.removeProperty(v);
  // A theme being built is shown with its own accent, which is what is being chosen.
  const accent = s.accent && !draft ? accentVarsOn(s.accent, hardestSurface(look), look.scheme) : null;
  const rules = [look.tokens ? declarations(look.tokens) : "", accent ? Object.entries(accent).map(([k, v]) => `${k}:${v}`).join(";") : ""].filter(Boolean).join(";");
  const style = themeStyle();
  const text = rules ? `:root{${rules}}` : "";
  if (style.textContent !== text) style.textContent = text;
  root.dataset["animateNames"] = s.animateNames === false ? "false" : "true";
  root.dataset["reducedMotion"] = s.reducedMotion ? "true" : "false";
  root.dataset["underlineLinks"] = s.underlineLinks || look.underlineLinks ? "true" : "false";
  applyFontFace(s.fontFace ?? "default");
  const meta = document.querySelector('meta[name="theme-color"]');
  const bar = look.tokens ? toHex(parseColour(look.tokens["bg-base"])?.rgb ?? [0, 0, 0]) : look.classic === "light" ? "#f4f2fb" : look.classic === "onyx" ? "#000000" : look.classic === "ash" ? "#18191d" : "#1c1825";
  if (meta) meta.setAttribute("content", bar);
}

let classicFonts: Promise<unknown> | null = null;

/**
 * Sets the app's typeface. The classic fonts are a stylesheet of their own,
 * fetched the first time they are picked: nobody who keeps the default
 * downloads them.
 */
export function applyFontFace(face: Settings["fontFace"]): void {
  const root = document.documentElement;
  if (face !== "classic") {
    delete root.dataset["font"];
    return;
  }
  classicFonts ??= import("@/styles/fonts-classic.css").catch(() => {
    classicFonts = null;
  });
  root.dataset["font"] = "classic";
}

const REMEMBERED = "ec:appearance";

/** Whether `id` names a theme that can be drawn with what is at hand. */
function knownTheme(id: string, customs: readonly CustomTheme[]): boolean {
  return id === "system" || isClassic(id) || !!presetById(id) || !!activeSeason(id, new Date()) || (isCustomId(id) && customs.some((c) => c.id === id));
}

function sanitizePair(v: unknown): { dark: string; light: string } | undefined {
  if (typeof v !== "object" || v === null) return undefined;
  const r = v as Record<string, unknown>;
  const pick = (x: unknown, fallback: string) => (typeof x === "string" && x !== "system" && x.length <= 40 ? x : fallback);
  return { dark: pick(r["dark"], "dark"), light: pick(r["light"], "light") };
}

/**
 * The appearance this device last drew the app in.
 *
 * The account's own settings are encrypted and arrive with the gateway's
 * ready, a second or two after the page has already been drawn — in the
 * default dark theme, at the default size. Someone who uses the light theme
 * saw a dark flash on every reload, and a larger font made the whole layout
 * jump once the settings landed. Remembering the last appearance here lets
 * the first frame already be the right one: a custom theme is kept with it,
 * since without the settings there is nothing else to draw it from. Anything
 * unreadable is ignored rather than trusted: a bad value costs the flash,
 * never a broken page.
 */
export function rememberedAppearance(): Appearance | null {
  const raw = storage.get(REMEMBERED);
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Record<string, unknown> | null;
    if (!v || typeof v !== "object") return null;
    const customThemes = sanitizeThemes(v["customThemes"]);
    const theme = v["theme"];
    if (typeof theme !== "string" || !knownTheme(theme, customThemes)) return null;
    const accent = typeof v["accent"] === "string" && parseHex(v["accent"]) ? v["accent"] : null;
    const scale = Number(v["fontScale"]);
    const out: Appearance = {
      theme,
      accent,
      fontScale: Number.isFinite(scale) && scale > 0 ? scale : 1,
      ...(v["animateNames"] === false ? { animateNames: false } : {}),
      reducedMotion: v["reducedMotion"] === true,
      underlineLinks: v["underlineLinks"] === true,
    };
    if (v["fontFace"] === "classic") out.fontFace = "classic";
    if (customThemes.length) out.customThemes = customThemes;
    const pair = sanitizePair(v["systemThemes"]);
    if (pair) out.systemThemes = pair;
    return out;
  } catch {
    return null;
  }
}

/** Keeps the appearance for the next page load on this device (see rememberedAppearance). */
export function rememberAppearance(a: Appearance): void {
  // only the custom themes that could be drawn: the one chosen, or the pair "Sync with system" moves between
  const wanted = new Set([a.theme, ...(a.theme === "system" && a.systemThemes ? [a.systemThemes.dark, a.systemThemes.light] : [])]);
  const customThemes = (a.customThemes ?? []).filter((c) => wanted.has(c.id));
  storage.set(
    REMEMBERED,
    JSON.stringify({
      theme: a.theme,
      accent: a.accent,
      fontScale: a.fontScale,
      ...(a.animateNames === false ? { animateNames: false } : {}),
      reducedMotion: a.reducedMotion,
      underlineLinks: a.underlineLinks,
      ...(a.fontFace === "classic" ? { fontFace: "classic" } : {}),
      ...(customThemes.length ? { customThemes } : {}),
      ...(a.theme === "system" && a.systemThemes ? { systemThemes: a.systemThemes } : {}),
    }),
  );
}

/** Draws the page in the remembered appearance, if there is one; for before the first render. */
export function applyRememberedAppearance(): void {
  const a = rememberedAppearance();
  if (a) applyTheme(a);
}
