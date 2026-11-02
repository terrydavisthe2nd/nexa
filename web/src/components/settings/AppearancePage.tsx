import { Toggle } from "@/components/common/Toggle";
import { Check, Pipette } from "lucide-react";
import { ColourInput } from "@/components/common/ColourInput";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Avatar } from "@/components/common/Avatar";
import { useAuth } from "@/stores/auth";
import { useSettings } from "@/stores/settings";
import { readableOn } from "@/lib/color";
import { MAX_THEME_NAME, newThemeId, themeTokens } from "@/lib/look/customTheme";
import type { CustomTheme } from "@/lib/look/types";
import { resolveLook } from "@/lib/theme";
import { ImportThemeDialog } from "./look/ImportThemeDialog";
import { startingColours } from "./look/SimpleEditor";
import { ThemeBuilder } from "./look/ThemeBuilder";
import { ThemeGallery, useCustomThemes } from "./look/ThemeGallery";
import look from "./look/Look.module.css";
import s from "./Settings.module.css";

import { t as tr_ } from "@/i18n";
/** The accent the app uses until one is picked; the Default swatch stands for it. */
const DEFAULT_ACCENT = "#8000ff";
// Default is drawn in the first swatch, so it is not in this list as well:
// listed twice, it was two identical purple squares side by side.
const ACCENTS = ["#5865f2", "#3d9e60", "#fdb833", "#da3e44", "#eb459f", "#1abc9c", "#e67e22", "#3498db", "#e91e63"];

/** The scrolling column a settings page sits in, to bring a sub-page in at its top. */
function scroller(el: HTMLElement | null): HTMLElement | null {
  for (let p = el?.parentElement ?? null; p; p = p.parentElement) {
    const y = getComputedStyle(p).overflowY;
    if ((y === "auto" || y === "scroll") && p.scrollHeight > p.clientHeight) return p;
  }
  return null;
}

export default function AppearancePage() {
  const me = useAuth((a) => a.me);
  const st = useSettings((x) => x.settings);
  const patch = useSettings((x) => x.patch);
  const customs = useCustomThemes();
  const [editing, setEditing] = useState<{ theme: CustomTheme; isNew: boolean } | null>(null);
  const [importing, setImporting] = useState(false);
  const top = useRef<HTMLDivElement>(null);
  const px = Math.round(st.fontScale * 16);
  // the default picked by hand is still the default
  const accent = st.accent === DEFAULT_ACCENT ? null : st.accent;
  const shown = resolveLook({ ...st, customThemes: customs });
  // Default means the theme's own accent, so the swatch shows that
  const themeAccent = themeTokens(shown.id, customs)?.brand ?? DEFAULT_ACCENT;

  useLayoutEffect(() => {
    const col = scroller(top.current);
    col?.scrollTo?.(0, 0);
  }, [editing]);
  // the classic fonts, for the choice between them to show them as they are
  useEffect(() => {
    void import("@/styles/fonts-classic.css").catch(() => undefined);
  }, []);

  const startNew = () => {
    const from = customs.find((c) => c.id === shown.id);
    const theme: CustomTheme = from
      ? { ...from, id: newThemeId(), name: tr_("settings.themeBuilder.copyOf", { name: from.name || tr_("settings.themeBuilder.untitled") }) }
      : { id: newThemeId(), name: tr_("settings.themeBuilder.defaultName"), source: { kind: "simple", ...(startingColours(shown.id) ?? startingColours("dark")!) }, overrides: {} };
    setEditing({ theme, isNew: true });
  };

  if (editing) {
    return (
      <div ref={top}>
        <ThemeBuilder
          key={editing.theme.id}
          initial={editing.theme}
          isNew={editing.isNew}
          onClose={() => setEditing(null)}
          onDuplicate={(from) => setEditing({ theme: { ...from, id: newThemeId(), name: tr_("settings.themeBuilder.copyOf", { name: from.name.trim() || tr_("settings.themeBuilder.untitled") }).slice(0, MAX_THEME_NAME) }, isNew: true })}
        />
      </div>
    );
  }

  return (
    <div ref={top}>
      <h2 className={s.title}>{tr_("settings.appearance.appearance")}</h2>
      <p className={look.lead}>{tr_("settings.appearance.lead")}</p>
      <h3 className={s.h5} data-setting="theme">{tr_("settings.appearance.theme")}</h3>
      <ThemeGallery
        onEdit={(id) => {
          const theme = customs.find((c) => c.id === id);
          if (theme) setEditing({ theme, isNew: false });
        }}
        onNew={startNew}
        onImport={() => setImporting(true)}
      />
      {importing ? (
        <ImportThemeDialog
          onClose={() => setImporting(false)}
          onOpen={(theme) => {
            setImporting(false);
            setEditing({ theme, isNew: true });
          }}
        />
      ) : null}
      <h3 className={s.h5} data-setting="accent">{tr_("settings.appearance.accentColour")}</h3>
      <p className={s.hint} style={{ margin: "0 0 10px" }}>
        {tr_("settings.appearance.accentHint")}
      </p>
      <div className={s.swatches}>
        {/* Named and marked as pressed: a title of "#5865f2" was all a screen reader had, and which one was chosen was only a border. */}
        <button className={s.swatch} data-on={accent === null} aria-pressed={accent === null} style={{ background: themeAccent }} title={tr_("settings.appearance.accentDefault")} aria-label={tr_("settings.appearance.accentDefault")} onClick={() => patch({ accent: null })} data-testid="accent-default">
          {accent === null ? (
            <span className={look.swatchCheck}>
              <Check size={18} strokeWidth={3} color={readableOn(themeAccent)} aria-hidden="true" />
            </span>
          ) : null}
        </button>
        {ACCENTS.map((c, i) => <button key={c} className={s.swatch} data-on={accent === c} aria-pressed={accent === c} style={{ background: c }} title={c} aria-label={tr_("server.roles.colourIndexOfCount", { index: i + 1, count: ACCENTS.length })} onClick={() => patch({ accent: c })} />)}
        <label className={[s.swatch, look.swatchPick].join(" ")} data-on={!!accent && !ACCENTS.includes(accent)} title={tr_("settings.appearance.custom")} style={accent && !ACCENTS.includes(accent) ? { background: accent } : undefined}>
          {accent && !ACCENTS.includes(accent) ? null : <Pipette size={18} aria-hidden="true" />}
          <ColourInput value={accent ?? themeAccent} label={tr_("settings.appearance.custom")} onChange={(v) => patch({ accent: v })} />
        </label>
      </div>
      <h3 className={s.h5}>{tr_("settings.appearance.animatedImages")}</h3>
      {(["animateAvatars", "animateIcons", "animateBanners", "animateEmoji", "animateStickers", "animateNames"] as const).map((key) => <Toggle key={key} setting={key} checked={st[key]} onChange={(v) => patch({ [key]: v })} label={tr_(`settings.appearance.${key}`)} />)}
      <h3 className={s.h5} data-setting="font">{tr_("settings.appearance.font")}</h3>
      <div className={look.fonts} role="radiogroup" aria-label={tr_("settings.appearance.font")} data-testid="font-choice">
        {(["default", "classic"] as const).map((face) => (
          <button key={face} type="button" role="radio" aria-checked={st.fontFace === face} className={look.fontChoice} onClick={() => patch({ fontFace: face })} data-face={face}>
            <span className={look.fontSample} data-face={face} aria-hidden="true">
              Aa
            </span>
            <span className={look.fontText}>
              <span className={look.fontName}>{face === "default" ? tr_("settings.appearance.fontDefault") : tr_("settings.appearance.fontClassic")}</span>
              <span className={look.fontNote}>{face === "default" ? "Wix Madefor" : "Onest, Mona Sans"}</span>
            </span>
            {st.fontFace === face ? <Check size={16} className={look.fontCheck} aria-hidden="true" /> : null}
          </button>
        ))}
      </div>
      <h3 className={[s.h5, look.sizeHead].join(" ")} data-setting="textSize">
        <span>{tr_("settings.appearance.textSize")}</span>
        <span className={look.sizeValue}>{tr_("settings.appearance.textSizeValue", { px })}</span>
      </h3>
      <input className={s.slider} type="range" min={12} max={20} step={1} value={px} onChange={(e) => patch({ fontScale: Number(e.target.value) / 16 })} aria-label={tr_("settings.appearance.fontSize")} aria-valuetext={tr_("settings.appearance.textSizeValue", { px })} />
      <div className={s.previewMsg} style={{ fontSize: `${px}px`, marginTop: 12 }}>
        <Avatar id={me?.id ?? "0"} avatarId={me?.avatar_id ?? null} name={me?.display_name ?? "You"} size={40} />
        <div>
          <span className={s.previewName}>{me?.display_name ?? "You"}</span>
          <span className={s.previewTime}>{tr_("settings.appearance.todayAt420Pm")}</span>
          <div className={s.previewText}>{tr_("settings.appearance.lookAtMeIMA")}</div>
          <div className={s.previewText}>{tr_("settings.appearance.waitingForTheDayWhen")}</div>
        </div>
      </div>
    </div>
  );
}
