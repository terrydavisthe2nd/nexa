import { Lock, MonitorUp } from "lucide-react";
import { useRef, useState, type KeyboardEvent } from "react";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { Toggle } from "@/components/common/Toggle";
import { desktopBridge } from "@/lib/desktopBridge";
import { useSettings, type ShareFps, type ShareKeep, type ShareMode, type ShareResolution } from "@/stores/settings";
import { shareAllowance } from "@/lib/plans";
import { offerPlan } from "@/stores/upsell";
import { MAX_SHARE_BPS, MIN_SHARE_BPS, SHARE_FRAME_RATES, customShareBitrate, shareBitrate, sharePlan, shareResolutions } from "@/voice/shareQuality";
import { startScreen } from "@/voice/session";
import s from "./Voice.module.css";

import { t } from "@/i18n";
const resolutionNotes = (): Record<ShareResolution, string> => ({ "720p": "HD", "1080p": t("voice.shareScreen.fullHd"), "1440p": "QHD", source: t("voice.shareScreen.native") });
const fpsNotes = (): Record<ShareFps, string> => ({ 15: t("voice.shareScreen.textSlides"), 30: t("voice.shareScreen.smooth"), 60: t("voice.shareScreen.gamesVideo") });
const MODES: ShareMode[] = ["performance", "quality", "custom"];
const modeName = (m: ShareMode) => t(m === "performance" ? "voice.shareScreen.modePerformance" : m === "quality" ? "voice.shareScreen.modeQuality" : "voice.shareScreen.modeCustom");
const modeWords = (m: "performance" | "quality") => t(m === "performance" ? "voice.shareScreen.performanceAbout" : "voice.shareScreen.qualityAbout");

/** "6", "9.9": a bitrate in megabits as the dialog writes it. */
const mbpsOf = (bps: number) => {
  const mbps = bps / 1_000_000;
  return Number.isInteger(mbps) ? String(mbps) : mbps.toFixed(1);
};

/** Arrow keys move a radio group's choice along, and focus with it. */
function arrowKeys<T>(e: KeyboardEvent<HTMLElement>, options: T[], value: T, onChange: (v: T) => void, row: HTMLElement | null): void {
  const step = e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 0;
  if (!step) return;
  e.preventDefault();
  // the row reads the other way in a right-to-left language
  const dir = row && getComputedStyle(row).direction === "rtl" && (e.key === "ArrowRight" || e.key === "ArrowLeft") ? -1 : 1;
  const at = options.indexOf(value);
  const next = (at + step * dir + options.length) % options.length;
  onChange(options[next]!);
  row?.querySelectorAll<HTMLElement>("[role=radio]")[next]?.focus();
}

/**
 * A row of choices that behaves as a radio group: one tab stop on the chosen
 * option, and the arrow keys move the choice along the row.
 */
function Choices<T extends string | number>({ label, id, options, value, onChange }: { label: string; id: string; options: { value: T; title: string; note: string }[]; value: T; onChange: (v: T) => void }) {
  const row = useRef<HTMLDivElement>(null);
  return (
    <div className={s.shareGroup}>
      <span className={s.shareLabel} id={id}>{label}</span>
      <div ref={row} className={s.shareOptions} role="radiogroup" aria-labelledby={id} style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}>
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={value === o.value}
            tabIndex={value === o.value ? 0 : -1}
            className={s.shareOption}
            data-on={value === o.value}
            onClick={() => onChange(o.value)}
            onKeyDown={(e) => arrowKeys(e, options.map((x) => x.value), value, onChange, row.current)}
            data-testid={`${id}-${o.value}`}
          >
            <span className={s.shareValue}>{o.title}</span>
            <span className={s.shareNote}>{o.note}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * The three ways a share can go, on one track: a lit thumb sits under the
 * chosen one and slides to the next, the only thing in the dialog that moves.
 */
function ModeSwitch({ value, onChange, locked }: { value: ShareMode; onChange: (m: ShareMode) => void; locked: boolean }) {
  const row = useRef<HTMLDivElement>(null);
  return (
    <div ref={row} className={s.modeTrack} role="radiogroup" aria-label={t("voice.shareScreen.streamMode")} style={{ ["--at" as string]: MODES.indexOf(value) }}>
      <span className={s.modeThumb} aria-hidden="true" />
      {MODES.map((m) => (
        <button
          key={m}
          type="button"
          role="radio"
          aria-checked={value === m}
          tabIndex={value === m ? 0 : -1}
          className={s.modeOption}
          data-on={value === m}
          onClick={() => onChange(m)}
          onKeyDown={(e) => arrowKeys(e, MODES, value, onChange, row.current)}
          data-testid={`share-mode-${m}`}
          data-locked={(locked && m === "quality") || undefined}
        >
          {modeName(m)}
          {locked && m === "quality" ? <Lock size={12} aria-label={t("support.locked")} className={s.modeLock} /> : null}
        </button>
      ))}
    </div>
  );
}

/** Asks how a share should go before the browser asks what to share. */
export function ShareScreenModal({ onClose }: { onClose: () => void }) {
  const voice = useSettings((x) => x.settings.voice);
  const patch = useSettings((x) => x.patch);
  const allow = shareAllowance();
  // Quality is for plans: without one, a saved Quality opens as Performance
  const [mode, setModeRaw] = useState<ShareMode>(voice.shareMode === "quality" && !allow.quality ? "performance" : voice.shareMode);
  const setMode = (m: ShareMode) => {
    if (m === "quality" && !allow.quality) {
      onClose();
      offerPlan({ kind: "share" });
      return;
    }
    setModeRaw(m);
  };
  const [resolution, setResolution] = useState<ShareResolution>(voice.shareResolution);
  const [fps, setFps] = useState<ShareFps>(voice.shareFps);
  const [bitrate, setBitrate] = useState<number | null>(customShareBitrate(voice.shareBitrate));
  const [keep, setKeep] = useState<ShareKeep>(voice.shareKeep);
  // the desktop app knows when this computer cannot share sound without the call echoing in it
  const bridge = desktopBridge();
  const audioSupported = bridge?.shareAudio !== false;
  // an older Windows 10 shares the call's own sound too, which the dialog says rather than hides
  const audioHasCall = bridge?.shareAudioMode === "all";
  // and when a shared window carries just its own program's sound
  const windowAudio = bridge?.windowAudio === true;
  const [audio, setAudio] = useState((voice.shareAudio ?? true) && audioSupported);
  const chosen = { shareMode: mode, shareResolution: resolution, shareFps: fps, shareBitrate: bitrate, shareKeep: keep };
  const plan = sharePlan(chosen, allow);
  const auto = shareBitrate(resolution, fps);

  const goLive = () => {
    const keepAudio = audioSupported ? audio : (voice.shareAudio ?? true);
    const changed = (Object.keys(chosen) as (keyof typeof chosen)[]).some((k) => chosen[k] !== voice[k]) || keepAudio !== (voice.shareAudio ?? true);
    if (changed) patch({ voice: { ...voice, ...chosen, shareAudio: keepAudio } });
    // straight from the click: the browser only opens its picker during a gesture
    void startScreen({ plan, audio });
    onClose();
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={t("voice.shareScreen.shareYourScreen")}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={goLive} data-testid="go-live"><MonitorUp size={16} /> {t("voice.shareScreen.goLive")}</Button>
        </>
      }
    >
      <div className={s.shareForm}>
        <div className={s.modeBlock}>
          <ModeSwitch value={mode} onChange={setMode} locked={!allow.quality} />
          {/* Custom's own rows say what it is */}
          {mode === "custom" ? null : <p className={s.modeAbout} data-testid="share-mode-about">{modeWords(mode)}</p>}
        </div>
        {mode === "custom" ? (
          <div className={s.customPanel} data-testid="share-custom">
            <Choices label={t("voice.shareScreen.resolution")} id="share-res" options={shareResolutions().map((r) => ({ value: r.value, title: r.label, note: resolutionNotes()[r.value] }))} value={resolution} onChange={setResolution} />
            <Choices label={t("voice.shareScreen.frameRate")} id="share-fps" options={SHARE_FRAME_RATES.map((f) => ({ value: f, title: `${f} fps`, note: fpsNotes()[f] }))} value={fps} onChange={setFps} />
            <div className={s.shareGroup}>
              <div className={s.bitrateHead}>
                <label className={s.shareLabel} htmlFor="share-bitrate">{t("voice.shareScreen.bitrateLimit")}</label>
                <span className={s.bitrateValue} data-numeric>{t("voice.shareScreen.mbps", { mbps: mbpsOf(plan.bitrate) })}</span>
              </div>
              <input
                id="share-bitrate"
                type="range"
                className={s.bitrateSlider}
                min={MIN_SHARE_BPS / 1_000_000}
                max={Math.min(MAX_SHARE_BPS, allow.maxBps) / 1_000_000}
                step={0.5}
                value={plan.bitrate / 1_000_000}
                aria-valuetext={t("voice.shareScreen.mbps", { mbps: mbpsOf(plan.bitrate) })}
                onChange={(e) => setBitrate(Number(e.target.value) * 1_000_000)}
                data-testid="share-bitrate"
              />
              <div className={s.bitrateFoot}>
                <span>{bitrate === null ? t("voice.shareScreen.bitrateAuto") : t("voice.shareScreen.bitrateChosen", { mbps: mbpsOf(auto) })}</span>
                {bitrate !== null ? (
                  <button type="button" className={s.linkButton} onClick={() => setBitrate(null)} data-testid="share-bitrate-auto">{t("voice.shareScreen.useAutomatic")}</button>
                ) : null}
              </div>
            </div>
            <Choices
              label={t("voice.shareScreen.whenConnectionShort")}
              id="share-keep"
              options={[
                { value: "sharp" as ShareKeep, title: t("voice.shareScreen.keepSharp"), note: t("voice.shareScreen.keepSharpNote") },
                { value: "smooth" as ShareKeep, title: t("voice.shareScreen.keepSmooth"), note: t("voice.shareScreen.keepSmoothNote") },
              ]}
              value={keep}
              onChange={setKeep}
            />
          </div>
        ) : null}
        <div className={s.shareAudio} data-testid="share-audio">
          <Toggle
            checked={audio}
            disabled={!audioSupported}
            onChange={setAudio}
            label={t("voice.shareScreen.shareAudio")}
            description={
              !audioSupported
                ? t("voice.shareScreen.thisComputerCanTShareIts")
                : bridge?.captureScreen
                  ? t("desktop.displayPicker.applicationAudioHint")
                : audioHasCall
                  ? t("voice.shareScreen.everyoneWatchingHearsWhatYourComputer")
                  : windowAudio
                    ? t("voice.shareScreen.shareAWindowAndEveryoneWatching")
                    : t("voice.shareScreen.everyoneWatchingHearsWhatYouRe")
            }
          />
        </div>
        <p className={s.shareHint} data-testid="share-upload">
          {mode === "custom" && resolution === "source" ? t("voice.shareScreen.sharesAtYourScreenSOwn") : ""}
          {t("voice.shareScreen.usesUpToMbps", { mbps: mbpsOf(plan.bitrate) })}
        </p>
      </div>
    </Modal>
  );
}
