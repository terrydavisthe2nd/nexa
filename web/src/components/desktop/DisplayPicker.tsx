import { ExternalLink, Monitor, MonitorUp, RotateCcw, RotateCw, ShieldAlert } from "lucide-react";
import { useId, useState } from "react";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { Select } from "@/components/common/Select";
import type { DisplaySourceRequest } from "@/lib/desktopBridge";
import { desktopBridge } from "@/lib/desktopBridge";
import { useUI } from "@/stores/ui";
import { shareAudioRequested } from "@/voice/media";
import s from "./Desktop.module.css";

import { t } from "@/i18n";
/**
 * The desktop app's screen and window picker, asked for by getDisplayMedia
 * after the Share Your Screen dialog. Browsers have their own; the app shows
 * this one. Whether the share carries sound was already chosen in that
 * dialog, so the picker only asks what to share.
 */
export function DisplayPicker({ request, onChoose }: { request: DisplaySourceRequest; onChoose: (sourceId: string | null, withAudio: boolean, soundId?: string | null) => void }) {
  if (request.permission) return <ScreenPermission stale={request.permission.stale} onClose={() => onChoose(null, false)} />;
  return <SourceGrid request={request} onChoose={onChoose} />;
}

/**
 * What to do when the computer will not let the app record the screen. macOS
 * keeps that decision itself, and an update can leave one that reads as
 * granted while the recording it allows is an older copy of the app — the
 * settings look right and nothing is captured. The app can have macOS forget
 * that decision, after which switching Nexa on again writes a new one; an
 * older app can only suggest a restart, or taking it out of the list and
 * putting it back.
 */
export function ScreenPermission({ stale, onClose }: { stale: boolean; onClose: () => void }) {
  const bridge = desktopBridge();
  const toast = useUI((u) => u.toast);
  const [step, setStep] = useState<"ask" | "resetting" | "reset">("ask");
  const canReset = !!bridge?.resetScreenAccess;
  const reset = async () => {
    setStep("resetting");
    const done = await bridge?.resetScreenAccess?.().catch(() => false);
    setStep(done ? "reset" : "ask");
    if (!done) toast(t("desktop.displayPicker.couldNotResetThePermission"), "error");
  };
  const openSettings = (primary: boolean) => (bridge?.openScreenSettings ? (
    <Button variant={primary ? "primary" : "secondary"} onClick={() => void bridge.openScreenSettings?.()} data-testid="open-screen-settings"><ExternalLink size={16} /> {t("desktop.displayPicker.openSettings")}</Button>
  ) : null);
  const restart = (primary: boolean) => (bridge?.restartApp ? (
    <Button variant={primary ? "primary" : "secondary"} onClick={() => void bridge.restartApp?.()} data-testid="restart-for-screen"><RotateCw size={16} /> {t("desktop.displayPicker.restartNexa")}</Button>
  ) : null);
  const resetButton = (primary: boolean) => (
    <Button variant={primary ? "primary" : "secondary"} loading={step === "resetting"} onClick={() => void reset()} data-testid="reset-screen-access"><RotateCcw size={16} /> {t("desktop.displayPicker.resetPermission")}</Button>
  );
  const close = <Button variant="ghost" onClick={onClose}>{t("chat.attachments.close")}</Button>;
  let body: React.ReactNode;
  let actions: React.ReactNode;
  if (canReset && step === "reset") {
    body = <p><b>{t("desktop.displayPicker.theOldPermissionIsCleared")}</b> {t("desktop.displayPicker.turnNexaOnInScreenRecording")}</p>;
    actions = <>{close}{openSettings(false)}{restart(true)}</>;
  } else if (canReset && stale) {
    body = (
      <>
        <p><b>{t("desktop.displayPicker.screenRecordingIsSwitchedOnFor")}</b> {t("desktop.displayPicker.thatHappensAfterAnUpdateThe")}</p>
        <p>{t("desktop.displayPicker.chooseResetPermissionThenTurn")}</p>
      </>
    );
    actions = <>{close}{resetButton(true)}</>;
  } else if (canReset) {
    body = (
      <>
        <p><b>{t("desktop.displayPicker.nexaNeedsPermissionToRecordThe")}</b> {t("desktop.displayPicker.openPrivacyAmpSecurityScreenRecording")}</p>
        <p>{t("desktop.displayPicker.ifNexaIsAlreadyOnThere")}</p>
      </>
    );
    actions = <>{close}{resetButton(false)}{openSettings(true)}</>;
  } else {
    body = stale ? (
      <>
        <p><b>{t("desktop.displayPicker.screenRecordingIsSwitchedOnFor")}</b> {t("desktop.displayPicker.thatHappensAfterAnUpdateThe")}</p>
        <p>{t("desktop.displayPicker.restartingUsuallySettlesItIfIt")}</p>
      </>
    ) : (
      <>
        <p><b>{t("desktop.displayPicker.nexaNeedsPermissionToRecordThe")}</b> {t("desktop.displayPicker.openPrivacyAmpSecurityScreenRecording")}</p>
        <p>{t("desktop.displayPicker.macosOnlyHandsThePermissionOver")}</p>
      </>
    );
    actions = <>{close}{openSettings(false)}{restart(true)}</>;
  }
  return (
    <Modal open onClose={onClose} title={t("desktop.displayPicker.thisComputerIsNotSharingThe")} width={520} footer={actions}>
      <div className={s.permission}>
        <span className={s.permissionIcon}><ShieldAlert size={22} /></span>
        <div>{body}</div>
      </div>
    </Modal>
  );
}

function SourceGrid({ request, onChoose }: { request: DisplaySourceRequest; onChoose: (sourceId: string | null, withAudio: boolean, soundId?: string | null) => void }) {
  const screens = request.sources.filter((x) => x.kind === "screen");
  const windows = request.sources.filter((x) => x.kind === "window");
  const [tab, setTab] = useState<"screen" | "window">(screens.length ? "screen" : "window");
  // One pick per tab. A single pick shared by both tabs was still the screen
  // after switching to Windows, where nothing was highlighted — and Share,
  // still enabled, sent the whole screen for a dialog showing a list of windows.
  const [picks, setPicks] = useState<{ screen: string | null; window: string | null }>(() => ({ screen: screens.length === 1 ? screens[0]!.id : null, window: null }));
  const gridId = useId();
  const [soundId, setSoundId] = useState("");
  const choose = (id: string) => onChoose(id, audio && (!request.sounds || !!soundId), soundId || null);
  const onTabKey = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const strip = e.currentTarget.parentElement;
    const buttons = strip ? [...strip.querySelectorAll<HTMLElement>('[role="tab"]')] : [];
    const at = buttons.indexOf(e.currentTarget);
    const rtl = getComputedStyle(e.currentTarget).direction === "rtl";
    const next = buttons[Math.min(buttons.length - 1, Math.max(0, at + (rtl ? -step : step)))];
    next?.click();
    next?.focus();
  };
  // an older app offers sound whether or not the share asked for it
  const audio = request.audio && shareAudioRequested();
  const shown = tab === "screen" ? screens : windows;
  // only ever something on the tab in front of the person choosing
  const picked = shown.some((x) => x.id === picks[tab]) ? picks[tab] : null;
  const pick = (id: string) => setPicks((cur) => ({ ...cur, [tab]: id }));

  // The sources are a radio group, which is moved through with the arrows:
  // with no arrow keys and every source but a picked one out of the tab
  // order, a keyboard could share a screen and never a window.
  const onSourceKey = (e: React.KeyboardEvent<HTMLButtonElement>, at: number) => {
    // Enter on a source shares it: a double click was the only one-gesture path to sharing.
    if (e.key === "Enter") {
      e.preventDefault();
      choose(shown[at]!.id);
      return;
    }
    const rtl = getComputedStyle(e.currentTarget).direction === "rtl";
    const forward = e.key === "ArrowDown" || e.key === (rtl ? "ArrowLeft" : "ArrowRight");
    const back = e.key === "ArrowUp" || e.key === (rtl ? "ArrowRight" : "ArrowLeft");
    const to = forward ? (at + 1) % shown.length : back ? (at - 1 + shown.length) % shown.length : e.key === "Home" ? 0 : e.key === "End" ? shown.length - 1 : -1;
    if (to < 0) return;
    e.preventDefault();
    pick(shown[to]!.id);
    const group = e.currentTarget.parentElement;
    group?.querySelectorAll<HTMLElement>('[role="radio"]')[to]?.focus();
  };

  return (
    <Modal
      open
      onClose={() => onChoose(null, false)}
      title={t("desktop.displayPicker.chooseWhatToShare")}
      width={640}
      footer={
        <>
          <Button variant="ghost" onClick={() => onChoose(null, false)}>{t("chat.editMessage.cancel")}</Button>
          <Button disabled={!picked} onClick={() => picked && choose(picked)} data-testid="display-share"><MonitorUp size={16} /> {t("desktop.displayPicker.share")}</Button>
        </>
      }
    >
      {/* Tabs with no panels and no arrow movement were only ever a look. The
          sibling Choices in ShareScreenModal already does this properly. */}
      <div className={s.tabs} role="tablist" aria-label={t("desktop.displayPicker.share")}>
        <button type="button" role="tab" aria-selected={tab === "screen"} aria-controls={gridId} tabIndex={tab === "screen" ? 0 : -1} onKeyDown={onTabKey} className={s.tab} data-on={tab === "screen"} onClick={() => setTab("screen")}>{t("desktop.displayPicker.screensCount", { count: screens.length })}</button>
        <button type="button" role="tab" aria-selected={tab === "window"} aria-controls={gridId} tabIndex={tab === "window" ? 0 : -1} onKeyDown={onTabKey} className={s.tab} data-on={tab === "window"} onClick={() => setTab("window")}>{t("desktop.displayPicker.windowsCount", { count: windows.length })}</button>
      </div>
      {shown.length === 0 ? (
        <div className={s.empty} id={gridId} role="tabpanel">{t("desktop.displayPicker.nothingToShareHere")}</div>
      ) : (
        <div id={gridId} role="tabpanel" aria-label={tab === "screen" ? t("desktop.displayPicker.screensCount", { count: screens.length }) : t("desktop.displayPicker.windowsCount", { count: windows.length })}>
        <div className={s.grid} role="radiogroup" aria-label={t("desktop.displayPicker.share")}>
          {shown.map((src, i) => (
            <button
              key={src.id}
              type="button"
              className={s.source}
              data-on={picked === src.id}
              role="radio"
              aria-checked={picked === src.id}
              // one stop for the group: the pick, or the first source while nothing on this tab is picked
              tabIndex={picked === src.id || (!picked && i === 0) ? 0 : -1}
              onClick={() => pick(src.id)}
              onDoubleClick={() => choose(src.id)}
              onKeyDown={(e) => onSourceKey(e, i)}
              data-testid="display-source"
            >
              {src.thumbnail ? <img className={s.thumb} src={src.thumbnail} alt="" draggable={false} /> : <span className={s.thumb} />}
              <span className={s.sourceName}>
                {src.icon ? <img src={src.icon} alt="" /> : <Monitor size={14} />}
                <span title={src.name}>{src.name}</span>
              </span>
            </button>
          ))}
        </div>
        </div>
      )}
      {request.sounds && shareAudioRequested() && (
        <div className={s.soundChoice}>
          <label htmlFor={`${gridId}-sound`}>{t("desktop.displayPicker.audioApplication")}</label>
          <Select id={`${gridId}-sound`} value={soundId} onChange={(e) => setSoundId(e.target.value)} aria-describedby={`${gridId}-sound-hint`}>
            <option value="">{t("desktop.displayPicker.noAudio")}</option>
            {request.sounds.map((sound) => <option key={sound.id} value={sound.id}>{sound.name}</option>)}
          </Select>
          <p id={`${gridId}-sound-hint`}>{t("desktop.displayPicker.applicationAudioHint")}</p>
        </div>
      )}
    </Modal>
  );
}
