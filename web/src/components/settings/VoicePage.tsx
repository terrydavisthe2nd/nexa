import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Button } from "@/components/common/Button";
import { Select } from "@/components/common/Select";
import { Field } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import { describeError } from "@/lib/errors";
import { NOISE_MODES, useSettings, type VoiceSettings } from "@/stores/settings";
import { useVoice } from "@/stores/voice";
import { LevelMeter, audioContext, defaultDeviceLabel, describeInput, getMic, holdAudio, lastInputChoice, listDevices, realDevices, sentStream, type Devices } from "@/voice/media";
import { makeDenoiser, prepareDenoiser, releaseDenoiser } from "@/voice/denoise";
import { keepsChannelsApart } from "@/voice/channels";
import { noiseModeHint, noiseModeLabel, noiseModePatch } from "@/voice/noiseMode";
import { canChooseOutput } from "@/voice/output";
import { noKeyToHold } from "@/voice/session";
import st from "./Settings.module.css";
import v from "@/components/voice/Voice.module.css";

import { t as tr_ } from "@/i18n";
/** User settings → Voice & Video. */
export default function VoicePage() {
  const settings = useSettings((s) => s.settings.voice);
  const patch = useSettings((s) => s.patch);
  const liveLevel = useVoice((x) => x.level);
  const inCall = useVoice((x) => !!x.mine);
  const apart = keepsChannelsApart(settings.voiceChannels);
  const heldToMono = useVoice((x) => !!x.mine?.mono_only);
  const [devices, setDevices] = useState<Devices | null>(null);
  const [testing, setTesting] = useState(false);
  const [testLevel, setTestLevel] = useState(0);
  const [sentLevel, setSentLevel] = useState(0);
  const [diag, setDiag] = useState("");
  const [oneChannelMic, setOneChannelMic] = useState(false);
  // Kept apart from `diag`: that block is a device dump for someone reading it
  // closely, and a refused permission belongs in a sentence above it.
  const [micError, setMicError] = useState<string | null>(null);
  const [wrongDevice, setWrongDevice] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const captureRef = useRef<HTMLButtonElement>(null);
  const [capturingPriority, setCapturingPriority] = useState(false);
  const priorityRef = useRef<HTMLButtonElement>(null);
  // A touch screen has no key to hold, so calls there fall back to voice
  // activity (voice/session.ts); the page says so instead of promising a key.
  const [starting, setStarting] = useState(false);
  const startingRef = useRef(false);
  const aliveRef = useRef(true);
  const testRef = useRef<{ stream: MediaStream; meter: LevelMeter; timer: ReturnType<typeof setInterval>; sent?: MediaStream; sentMeter?: LevelMeter; gain?: GainNode | null; denoiser?: AudioNode | null; release: () => void } | null>(null);
  const set = (partial: Partial<VoiceSettings>) => patch({ voice: { ...settings, ...partial } });

  useEffect(() => {
    listDevices().then(setDevices).catch(() => setDevices({ inputs: [], outputs: [], cameras: [] }));
    const on = () => listDevices().then(setDevices).catch(() => {});
    navigator.mediaDevices?.addEventListener?.("devicechange", on);
    return () => navigator.mediaDevices?.removeEventListener?.("devicechange", on);
  }, []);

  const stopTest = () => {
    const t = testRef.current;
    if (!t) return;
    clearInterval(t.timer);
    t.meter.close();
    t.sentMeter?.close();
    releaseDenoiser(t.denoiser ?? null);
    t.sent?.getTracks().forEach((x) => x.stop());
    t.stream.getTracks().forEach((x) => x.stop());
    t.release();
    testRef.current = null;
    setTesting(false);
    setTestLevel(0);
    setSentLevel(0);
    setWrongDevice(false);
  };
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      stopTest();
    };
  }, []);
  const startTest = async () => {
    // one test at a time, even while the permission prompt is still up
    if (startingRef.current || testRef.current) return;
    startingRef.current = true;
    setStarting(true);
    setMicError(null);
    let stream: MediaStream | null = null;
    let denoiser: AudioNode | null = null;
    try {
      const ctx = audioContext();
      // opened the way a call opens it, noise suppression and all
      const rnnoise = (settings.noiseMode === "enhanced" || (apart && settings.noiseMode === "standard")) && (await prepareDenoiser(ctx));
      stream = await getMic(settings, { browserNoise: !apart && (settings.noiseMode === "standard" || (settings.noiseMode === "enhanced" && !rnnoise)) });
      if (rnnoise) denoiser = await makeDenoiser(ctx, apart ? 2 : 1);
      if (ctx.state === "suspended") await ctx.resume();
      // Settings closed while the prompt was up: let the microphone go rather than holding it with nothing to show
      if (!aliveRef.current) {
        stream.getTracks().forEach((x) => x.stop());
        releaseDenoiser(denoiser);
        return;
      }
      const meter = new LevelMeter(ctx, stream);
      // The second meter listens to exactly what a call would send: the same
      // stream after noise suppression and the input slider. If this one stays
      // flat while the first moves, the trouble is here rather than with the
      // microphone.
      const sent = sentStream(ctx, stream, settings.inputVolume, denoiser, settings.voiceChannels);
      if (denoiser && !sent.source) {
        releaseDenoiser(denoiser);
        denoiser = null;
      }
      const sentMeter = new LevelMeter(ctx, sent.stream);
      const timer = setInterval(() => {
        setTestLevel(meter.level());
        setSentLevel(sentMeter.level());
      }, 60);
      testRef.current = { stream, meter, timer, sent: sent.stream, sentMeter, gain: sent.gain, denoiser, release: holdAudio() };
      setTesting(true);
      setDiag(describeInput(ctx, stream, sent.stream, settings.inputVolume, !!denoiser));
      setOneChannelMic(apart && stream.getAudioTracks()[0]?.getSettings().channelCount === 1);
      setWrongDevice(lastInputChoice() === "missing");
      // permission granted: device labels are now readable
      listDevices().then(setDevices).catch(() => {});
    } catch (err) {
      if (stream && testRef.current?.stream !== stream) stream.getTracks().forEach((x) => x.stop());
      if (testRef.current?.denoiser !== denoiser) releaseDenoiser(denoiser);
      // The commonest failure here is the reader declining the browser's own
      // prompt, and `NotAllowedError: Permission denied` at 11px in a monospace
      // block is a stack trace, not an answer. `.diag` keeps the device dump
      // for the successful case; this says what happened in words.
      console.error("microphone test failed", err);
      setMicError(describeError(err, tr_("settings.voice.theMicrophoneCouldNotBeOpened2")));
    } finally {
      startingRef.current = false;
      setStarting(false);
    }
  };

  // a test that is running starts again with the noise suppression just chosen, or it would show the old one
  const modeRef = useRef(`${settings.noiseMode} ${settings.voiceChannels}`);
  useEffect(() => {
    const mode = `${settings.noiseMode} ${settings.voiceChannels}`;
    if (modeRef.current === mode) return;
    modeRef.current = mode;
    setOneChannelMic(false);
    if (!testRef.current) return;
    stopTest();
    void startTest();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings.noiseMode, settings.voiceChannels]);

  // The sensitivity bar is set against the voice, so it listens as soon as it
  // is on screen where the microphone was allowed before (a call's own level
  // does that job in a call). Elsewhere it offers to, rather than prompting
  // for the microphone on opening the page. Once a visit: a test stopped by
  // hand stays stopped.
  const sensShown = settings.mode !== "ptt" && !settings.autoSensitivity;
  const listenedRef = useRef(false);
  useEffect(() => {
    if (!sensShown || inCall || listenedRef.current) return;
    let gone = false;
    void micAllowed().then((ok) => {
      if (gone || !ok || listenedRef.current || !aliveRef.current) return;
      listenedRef.current = true;
      void startTest();
    });
    return () => {
      gone = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sensShown, inCall]);

  useEffect(() => {
    if (!capturing) return;
    const onKey = (e: KeyboardEvent) => {
      e.preventDefault();
      e.stopPropagation();
      if (e.key === "Escape") {
        setCapturing(false);
        return;
      }
      set({ pttKey: e.key.length === 1 ? e.key : e.code });
      setCapturing(false);
    };
    // a press anywhere else puts the box down, so it cannot be left waiting for a key forever
    const onDown = (e: PointerEvent) => {
      if (!captureRef.current?.contains(e.target as Node)) setCapturing(false);
    };
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("pointerdown", onDown, true);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("pointerdown", onDown, true);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [capturing]);

  // the Priority Speaker key, recorded the same way; Backspace or Delete leaves it unset
  useEffect(() => {
    if (!capturingPriority) return;
    const onKey = (e: KeyboardEvent) => {
      e.preventDefault();
      e.stopPropagation();
      if (e.key !== "Escape") set({ priorityKey: e.key === "Backspace" || e.key === "Delete" ? "" : e.key.length === 1 ? e.key : e.code });
      setCapturingPriority(false);
    };
    const onDown = (e: PointerEvent) => {
      if (!priorityRef.current?.contains(e.target as Node)) setCapturingPriority(false);
    };
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("pointerdown", onDown, true);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      window.removeEventListener("pointerdown", onDown, true);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [capturingPriority]);

  const level = testing ? testLevel : inCall ? liveLevel : 0;
  // voice activity is judged on what a call sends: after noise suppression and the input volume
  const sendLevel = testing ? sentLevel : inCall ? liveLevel : 0;
  // a browser keeps device names to itself until a device of the kind was allowed once
  const label = (d: MediaDeviceInfo, i: number, kind: "microphoneN" | "speakersN" | "cameraN") => d.label || tr_(`voice.devices.${kind}`, { n: i + 1 });
  // Safari plays calls on the system's output and cannot be told another
  const outputs = canChooseOutput();
  // a touch screen has no key to capture: push to talk there is a button in the call
  const holdButton = noKeyToHold();
  return (
    <>
      <h2 data-setting="devices" className={st.title}>{tr_("settings.voice.voiceVideo")}</h2>
      <div className={st.form}>
        <div className={`${st.twoCol} ${st.twoEqual}`}>
          <Field label={tr_("settings.voice.inputDevice")}>
            <Select
              value={settings.inputDeviceId ?? ""}
              data-testid="input-device"
              onChange={(e) => {
                const id = e.target.value || null;
                const chosen = devices?.inputs.find((d) => d.deviceId === id);
                set({ inputDeviceId: id, inputDeviceLabel: chosen?.label || null });
              }}
            >
              <option value="">{defaultDeviceLabel(devices?.inputs ?? [])}</option>
              {realDevices(devices?.inputs ?? []).map((d, i) => <option key={d.deviceId} value={d.deviceId}>{label(d, i, "microphoneN")}</option>)}
            </Select>
          </Field>
          <Field label={tr_("settings.voice.outputDevice")} hint={outputs ? undefined : tr_("settings.voice.thisBrowserPlaysCallsOnThe")}>
            <Select
              value={settings.outputDeviceId ?? ""}
              disabled={!outputs}
              onChange={(e) => {
                const id = e.target.value || null;
                const chosen = devices?.outputs.find((d) => d.deviceId === id);
                set({ outputDeviceId: id, outputDeviceLabel: chosen?.label || null });
              }}
            >
              <option value="">{defaultDeviceLabel(devices?.outputs ?? [])}</option>
              {realDevices(devices?.outputs ?? []).map((d, i) => <option key={d.deviceId} value={d.deviceId}>{label(d, i, "speakersN")}</option>)}
            </Select>
          </Field>
        </div>
        <div className={`${st.twoCol} ${st.twoEqual}`}>
          <Field label={tr_("settings.voice.inputVolumeValue", { percent: settings.inputVolume })}>
            <input type="range" className={st.slider} min={0} max={200} value={settings.inputVolume} onChange={(e) => set({ inputVolume: Number(e.target.value) })} />
          </Field>
          <Field label={tr_("settings.voice.outputVolumeValue", { percent: settings.outputVolume })}>
            <input type="range" className={st.slider} min={0} max={200} value={settings.outputVolume} onChange={(e) => set({ outputVolume: Number(e.target.value) })} />
          </Field>
        </div>
        <Field label={tr_("settings.voice.micTest")} hint={tr_("settings.voice.speakAndWatchBothMetersThe")}>
          <div>
            <Button size="sm" variant={testing ? "danger" : "secondary"} onClick={() => (testing ? stopTest() : void startTest())} disabled={starting} data-testid="mic-test">{testing ? tr_("settings.voice.stopTest") : tr_("settings.voice.letSCheck")}</Button>
            <div className={v.meter} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(level * 100)} aria-label={tr_("settings.voice.inputLevel")} data-testid="mic-level">
              <div className={v.meterFill} style={{ width: `${Math.round(level * 100)}%` }} />
            </div>
            {testing ? (
              <>
                <div className={v.meter} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(sentLevel * 100)} aria-label={tr_("settings.voice.levelOthersWouldHear")} data-testid="sent-level" style={{ marginTop: 6 }}>
                  <div className={v.meterFill} data-sent="true" style={{ width: `${Math.round(sentLevel * 100)}%` }} />
                </div>
                <p className={st.hint} style={{ marginTop: 6 }}>{tr_("settings.voice.topYourMicrophoneBottomWhatThe")}</p>
              </>
            ) : null}
            {wrongDevice ? (
              <div className={st.dangerBox} style={{ marginTop: 8 }} data-testid="wrong-device">
                {tr_("settings.voice.theMicrophoneYouPickedIsNot")}
              </div>
            ) : null}
            {micError ? <div className={st.dangerBox} style={{ marginTop: 8 }} role="alert" data-testid="mic-error">{micError}</div> : null}
            {diag ? <pre className={v.diag} data-testid="mic-diagnostics">{diag}</pre> : null}
          </div>
        </Field>
      </div>
      {/* Headings and radio cards sat in the form's 20px-gap column, so each
          heading floated 28px above what it named and the two cards stood
          28px apart; everywhere else they are 8px. */}
      <h3 data-setting="inputMode" className={st.h5}>{tr_("settings.voice.inputMode")}</h3>
      <div role="radiogroup" aria-label={tr_("settings.voice.inputMode")}>
        <label className={st.radio} data-on={settings.mode === "vad"}>
          <input type="radio" name="voice-input-mode" checked={settings.mode === "vad"} onChange={() => set({ mode: "vad" })} />
          <span className={st.radioText}><span className={st.radioLabel}>{tr_("settings.voice.voiceActivity")}</span><span className={st.radioDesc}>{tr_("settings.voice.transmitWheneverYouTalk")}</span></span>
        </label>
        <label className={st.radio} data-on={settings.mode === "ptt"}>
          <input type="radio" name="voice-input-mode" checked={settings.mode === "ptt"} onChange={() => set({ mode: "ptt" })} />
          <span className={st.radioText}><span className={st.radioLabel}>{tr_("settings.voice.pushToTalk")}</span><span className={st.radioDesc}>{holdButton ? tr_("settings.voice.transmitOnlyWhileYouHoldTheButton") : tr_("settings.voice.transmitOnlyWhileYouHoldA")}</span></span>
        </label>
      </div>
      <div className={st.form} style={{ marginTop: 12 }}>
        {settings.mode === "ptt" && holdButton ? (
          <p className={st.hint} data-testid="ptt-button-note">{tr_("settings.voice.onThisDevicePushToTalk")}</p>
        ) : settings.mode === "ptt" ? (
          <Field label={tr_("settings.voice.pushToTalkKey")} hint={tr_("settings.voice.clickTheBoxThenPressThe")}>
            <button ref={captureRef} type="button" className={v.keyCapture} data-capturing={capturing} onClick={() => setCapturing((c) => !c)} aria-pressed={capturing}>
              <span>{capturing ? tr_("settings.voice.pressAKey") : settings.pttKey}</span>
              <span style={{ color: "var(--text-muted)", fontSize: 12 }}>{capturing ? tr_("settings.voice.escToCancel") : tr_("settings.security.change")}</span>
            </button>
          </Field>
        ) : (
          <>
            <Toggle checked={settings.autoSensitivity} onChange={(on) => set({ autoSensitivity: on })} label={tr_("settings.voice.automaticallyDetermineInputSensitivity")} description={tr_("settings.voice.learnsYourBackgroundNoiseAndOnly")} />
            {!settings.autoSensitivity ? (
              <Field as="div" label={tr_("settings.voice.inputSensitivityValue", { db: Math.max(SENS_MIN, settings.sensitivity) })} hint={tr_("settings.voice.sensitivityHint")}>
                <SensitivityBar value={settings.sensitivity} onChange={(db) => set({ sensitivity: db })} level={sendLevel} live={testing || inCall} onListen={() => void startTest()} listening={starting} />
              </Field>
            ) : null}
          </>
        )}
        {!holdButton ? (
          <Field label={tr_("settings.voice.priorityKey")} hint={tr_("settings.voice.priorityKeyHint")}>
            <button ref={priorityRef} type="button" className={v.keyCapture} data-capturing={capturingPriority} onClick={() => setCapturingPriority((c) => !c)} aria-pressed={capturingPriority} data-testid="priority-key">
              <span>{capturingPriority ? tr_("settings.voice.pressAKey") : settings.priorityKey || tr_("settings.voice.priorityKeyNone")}</span>
              <span style={{ color: "var(--text-muted)", fontSize: 12 }}>{capturingPriority ? tr_("settings.voice.escToCancel") : tr_("settings.security.change")}</span>
            </button>
          </Field>
        ) : null}
      </div>
      <h3 data-setting="voiceStereo" className={st.h5}>{tr_("voice.channels.title")}</h3>
      <Toggle checked={settings.voiceChannels === "stereo"} onChange={(on) => set({ voiceChannels: on ? "stereo" : "mono" })} label={tr_("voice.channels.stereo")} description={tr_("voice.channels.stereoHint")} />
      <Toggle checked={settings.voiceChannels === "dual"} onChange={(on) => set({ voiceChannels: on ? "dual" : "mono" })} label={tr_("voice.channels.dual")} description={tr_("voice.channels.dualHint")} />
      <Toggle checked={settings.hearMono} onChange={(on) => set({ hearMono: on })} label={tr_("voice.channels.hearMono")} description={tr_("voice.channels.hearMonoHint")} />
      {apart ? <p className={st.hint} data-testid="channels-note">{tr_("voice.channels.stereoNote")}</p> : null}
      {oneChannelMic ? <p className={st.hint} data-testid="channels-one-mic">{tr_("voice.channels.oneChannelMic")}</p> : null}
      {inCall && settings.voiceChannels !== "mono" && heldToMono ? <p className={st.hint} data-testid="channels-held">{tr_("voice.channels.notAllowed")}</p> : null}
      <h3 data-setting="noiseSuppression" className={st.h5}>{tr_("voice.noise.title")}</h3>
      <div role="radiogroup" aria-label={tr_("voice.noise.title")}>
        {NOISE_MODES.map((m) => {
          const off = apart && m === "standard";
          const on = (apart && settings.noiseMode === "standard" ? "enhanced" : settings.noiseMode) === m;
          return (
            <label key={m} className={st.radio} data-on={on} data-disabled={off || undefined}>
              <input type="radio" name="voice-noise-mode" checked={on} disabled={off} onChange={() => set(noiseModePatch(m))} data-testid={`noise-${m}`} />
              <span className={st.radioText}><span className={st.radioLabel}>{noiseModeLabel(m)}</span><span className={st.radioDesc}>{noiseModeHint(m)}</span></span>
            </label>
          );
        })}
      </div>
      <h3 data-setting="voiceProcessing" className={st.h5}>{tr_("settings.voice.voiceProcessing")}</h3>
      <Toggle checked={settings.echoCancellation && !apart} disabled={apart} onChange={(on) => set({ echoCancellation: on })} label={tr_("settings.voice.echoCancellation")} />
      <Toggle checked={settings.autoGain && !apart} disabled={apart} onChange={(on) => set({ autoGain: on })} label={tr_("settings.voice.automaticGainControl")} />
      <div className={st.form} style={{ marginTop: 24 }}>
        <Field label={tr_("settings.voice.camera")}>
          <Select
            value={settings.cameraDeviceId ?? ""}
            data-testid="camera-device"
            disabled={!devices?.cameras.length}
            onChange={(e) => {
              const id = e.target.value || null;
              const chosen = devices?.cameras.find((d) => d.deviceId === id);
              set({ cameraDeviceId: id, cameraDeviceLabel: chosen?.label || null });
            }}
          >
            <option value="">{devices?.cameras.length ? defaultDeviceLabel(devices.cameras) : tr_("settings.voice.noCameraFound")}</option>
            {realDevices(devices?.cameras ?? []).map((d, i) => <option key={d.deviceId} value={d.deviceId}>{label(d, i, "cameraN")}</option>)}
          </Select>
        </Field>
      </div>
    </>
  );
}

/** The sensitivity slider's range: below -60 dB nothing is loud enough to hear (speaking.ts, AUDIBLE_LEVEL). */
const SENS_MIN = -60;

/** Whether the microphone can be opened without a prompt. False where the browser will not say (Firefox). */
async function micAllowed(): Promise<boolean> {
  try {
    const p = await navigator.permissions?.query({ name: "microphone" as PermissionName });
    return p?.state === "granted";
  } catch {
    return false;
  }
}

/** A level from LevelMeter (0–1) as where it falls on the slider, 0–100. */
function levelAt(level: number): number {
  if (level <= 0) return 0;
  // the inverse of sensitivityToThreshold: the meter reads four times the RMS
  const db = 20 * Math.log10(level / 4);
  return Math.max(0, Math.min(100, ((db - SENS_MIN) / -SENS_MIN) * 100));
}

/**
 * Input sensitivity, drawn as the meter it is set against: the microphone's
 * level runs along the slider's own track as it is heard, amber up to the
 * handle and green past it, so where to put the handle can be seen while
 * talking. Sound past the handle is sent. It shows a call's own level, or the
 * microphone test's, or offers to start one.
 */
function SensitivityBar({ value, onChange, level, live, onListen, listening }: { value: number; onChange: (db: number) => void; level: number; live: boolean; onListen: () => void; listening: boolean }) {
  const db = Math.max(SENS_MIN, Math.min(0, value));
  const at = ((db - SENS_MIN) / -SENS_MIN) * 100;
  const style = { "--at": `${at}%`, "--level": `${live ? levelAt(level) : 0}%` } as CSSProperties;
  return (
    <div className={v.sensWrap}>
      <div className={v.sens} style={style} data-live={live || undefined} data-sending={live && levelAt(level) > at ? "" : undefined} data-testid="sensitivity-bar">
        <div className={v.sensTrack} aria-hidden="true">
          <div className={v.sensFill} />
        </div>
        <input
          type="range"
          className={v.sensInput}
          min={SENS_MIN}
          max={0}
          value={db}
          onChange={(e) => onChange(Number(e.target.value))}
          aria-label={tr_("settings.voice.inputSensitivityValue", { db })}
          aria-valuetext={`${db} dB`}
          data-testid="sensitivity"
        />
      </div>
      {live ? null : (
        <Button size="sm" variant="secondary" onClick={onListen} loading={listening} data-testid="sensitivity-listen">
          {tr_("settings.voice.showMyLevel")}
        </Button>
      )}
    </div>
  );
}
