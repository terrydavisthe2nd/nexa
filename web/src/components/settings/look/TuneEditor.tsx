import { RotateCcw } from "lucide-react";
import { Button } from "@/components/common/Button";
import { Toggle } from "@/components/common/Toggle";
import { t } from "@/i18n";
import { isNeutral, type Tune } from "@/lib/look/tools";
import s from "./Look.module.css";
import b from "./Builder.module.css";

const SLIDERS: { key: keyof Tune; min: number; max: number; unit: string }[] = [
  { key: "hue", min: -180, max: 180, unit: "°" },
  { key: "saturation", min: -100, max: 100, unit: "" },
  { key: "brightness", min: -100, max: 100, unit: "" },
  { key: "contrast", min: -100, max: 100, unit: "" },
];

/** A slider's value as it reads beside it: signed, so which way it moved shows. */
const signed = (v: number, unit: string) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${Math.abs(v)}${unit}`;

/**
 * Tune: the whole theme moved at once. Every move is worked out from the
 * theme as it was when the first slider moved, so a slider back at zero is
 * exactly where it started; what it makes is kept as colours set by hand.
 */
export function TuneEditor({ tune, status, onChange, onReset }: { tune: Tune; status: boolean; onChange: (tune: Tune, status: boolean) => void; onReset: () => void }) {
  return (
    <div>
      <p className={s.panelHint}>{t("settings.themeBuilder.tuneHint")}</p>
      <div className={b.sliders}>
        {SLIDERS.map(({ key, min, max, unit }) => {
          const v = tune[key];
          const name = t(`settings.themeBuilder.tune.${key}`);
          return (
            <label key={key} className={b.slider} data-kind={key} data-moved={v !== 0 || undefined}>
              <span className={b.sliderName}>{name}</span>
              <span className={b.sliderValue} dir="ltr">
                {signed(v, unit)}
              </span>
              <button
                type="button"
                className={[s.iconBtn, b.resetOne].join(" ")}
                onClick={(e) => {
                  e.preventDefault();
                  onChange({ ...tune, [key]: 0 }, status);
                }}
                aria-label={t("settings.themeBuilder.resetNamed", { name })}
                title={t("settings.themeBuilder.resetNamed", { name })}
              >
                <RotateCcw size={16} aria-hidden="true" />
              </button>
              <span className={b.sliderHint}>{t(`settings.themeBuilder.tune.${key}Hint`)}</span>
              <input
                type="range"
                min={min}
                max={max}
                step={1}
                value={v}
                onChange={(e) => onChange({ ...tune, [key]: Number(e.target.value) }, status)}
                aria-valuetext={signed(v, unit)}
                data-testid={`tune-${key}`}
              />
              {key === "hue" ? <span className={b.hueStrip} aria-hidden="true" /> : null}
            </label>
          );
        })}
      </div>
      <div className={s.fieldRow}>
        <Toggle checked={status} onChange={(on) => onChange(tune, on)} label={t("settings.themeBuilder.tuneStatus")} description={t("settings.themeBuilder.tuneStatusHint")} />
      </div>
      <div className={b.tuneFoot}>
        <Button variant="secondary" size="sm" onClick={onReset} disabled={isNeutral(tune)} data-testid="tune-reset">
          <RotateCcw size={15} aria-hidden="true" /> {t("settings.themeBuilder.tuneReset")}
        </Button>
      </div>
    </div>
  );
}
