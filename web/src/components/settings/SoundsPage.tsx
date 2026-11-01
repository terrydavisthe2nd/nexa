import { AtSign, Headphones, HeadphoneOff, MonitorOff, MonitorUp, Check, LogIn, LogOut, MessageSquare, Mic, MicOff, PhoneIncoming, PhoneOutgoing, Play, Plus, RotateCcw, Sparkles, Trash2, Upload, UserPlus, type LucideIcon } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/common/Button";
import { Toggle } from "@/components/common/Toggle";
import { promptText } from "@/components/modals/PromptModal";
import { describeError } from "@/lib/errors";
import { MAX_PACKS, NEXA_PACK, PACK_NAME_MAX, SOUND_NAMES, SoundFileError, newPackId, type SoundPack, type SoundSettings } from "@/lib/soundPacks";
import { previewSound, soundTrouble, type SoundName } from "@/lib/sounds";
import { addSoundFile, dropSoundFile, tidySoundFiles } from "@/lib/soundSync";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import s from "./Sounds.module.css";
import st from "./Settings.module.css";

import { t } from "@/i18n";

const SOUND_ICON: Record<SoundName, LucideIcon> = { message: MessageSquare, mention: AtSign, friend: UserPlus, ring: PhoneIncoming, outgoing: PhoneOutgoing, join: LogIn, leave: LogOut, mute: MicOff, unmute: Mic, deafen: HeadphoneOff, undeafen: Headphones, shareStart: MonitorUp, shareStop: MonitorOff };

function soundLabel(name: SoundName): string {
  return {
    message: t("settings.sounds.soundMessage"),
    mention: t("settings.sounds.soundMention"),
    friend: t("settings.sounds.soundFriend"),
    ring: t("settings.sounds.soundRing"),
    outgoing: t("settings.sounds.soundOutgoing"),
    join: t("settings.sounds.soundJoin"),
    leave: t("settings.sounds.soundLeave"),
    mute: t("settings.sounds.soundMute"),
    unmute: t("settings.sounds.soundUnmute"),
    deafen: t("settings.sounds.soundDeafen"),
    undeafen: t("settings.sounds.soundUndeafen"),
    shareStart: t("settings.sounds.soundShareStart"),
    shareStop: t("settings.sounds.soundShareStop"),
  }[name];
}
function soundWhen(name: SoundName): string {
  return {
    message: t("settings.sounds.whenMessage"),
    mention: t("settings.sounds.whenMention"),
    friend: t("settings.sounds.whenFriend"),
    ring: t("settings.sounds.whenRing"),
    outgoing: t("settings.sounds.whenOutgoing"),
    join: t("settings.sounds.whenJoin"),
    leave: t("settings.sounds.whenLeave"),
    mute: t("settings.sounds.whenMute"),
    unmute: t("settings.sounds.whenUnmute"),
    deafen: t("settings.sounds.whenDeafen"),
    undeafen: t("settings.sounds.whenUndeafen"),
    shareStart: t("settings.sounds.whenShareStart"),
    shareStop: t("settings.sounds.whenShareStop"),
  }[name];
}

const seconds = (ms: number) => (ms / 1000).toFixed(1);

/** What went wrong with a picked file, said for the person who picked it. */
function fileProblem(err: unknown): string {
  if (err instanceof SoundFileError) {
    if (err.reason === "too_big") return t("settings.sounds.errTooBig");
    if (err.reason === "silent") return t("settings.sounds.errSilent");
    return t("settings.sounds.errUnreadable");
  }
  return describeError(err, t("settings.sounds.errUpload"));
}

export default function SoundsPage() {
  const sounds = useSettings((x) => x.settings.sounds);
  const notifySounds = useSettings((x) => x.settings.notifications.sounds);
  const patch = useSettings((x) => x.patch);
  const toast = useUI((x) => x.toast);
  const openModal = useUI((x) => x.openModal);
  const [busy, setBusy] = useState<SoundName | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const picking = useRef<SoundName | null>(null);
  const input = useRef<HTMLInputElement>(null);

  // recordings no pack names any more, from a device that could not say so at the time
  useEffect(() => {
    void tidySoundFiles().catch(() => undefined);
  }, []);

  const set = (next: Partial<SoundSettings>) => patch({ sounds: { ...useSettings.getState().settings.sounds, ...next } });
  const custom = sounds.packs.find((p) => p.id === sounds.pack) ?? null;
  // Nexa's own pack, which plays wherever a pack of your own has no recording
  const nexa = t("settings.sounds.packNexa");

  const play = async (name: SoundName, packId?: string) => {
    setHint((await previewSound(name, packId)) ? null : soundTrouble());
  };
  const playPack = (packId: string) => void play("mention", packId);

  const updatePack = (id: string, change: (p: SoundPack) => SoundPack) =>
    set({ packs: useSettings.getState().settings.sounds.packs.map((p) => (p.id === id ? change(p) : p)) });

  const createPack = async () => {
    if (sounds.packs.length >= MAX_PACKS) {
      toast(t("settings.sounds.tooManyPacks", { max: MAX_PACKS }), "error");
      return;
    }
    const name = await promptText(t("settings.sounds.createPack"), { label: t("settings.sounds.packNameLabel"), placeholder: t("settings.sounds.packNamePlaceholder"), maxLength: PACK_NAME_MAX }, t("settings.sounds.create"));
    if (!name?.trim()) return;
    const pack: SoundPack = { id: newPackId(), name: name.trim().slice(0, PACK_NAME_MAX), files: {} };
    const now = useSettings.getState().settings.sounds;
    set({ packs: [...now.packs, pack], pack: pack.id });
  };

  const renamePack = async (pack: SoundPack) => {
    const name = await promptText(t("settings.sounds.renamePack"), { label: t("settings.sounds.packNameLabel"), initial: pack.name, maxLength: PACK_NAME_MAX });
    if (!name?.trim()) return;
    updatePack(pack.id, (p) => ({ ...p, name: name.trim().slice(0, PACK_NAME_MAX) }));
  };

  const deletePack = (pack: SoundPack) => {
    openModal("confirm", {
      title: t("settings.sounds.deletePack"),
      body: t("settings.sounds.deletePackBody", { name: pack.name }),
      confirmLabel: t("settings.sounds.deletePack"),
      danger: true,
      onConfirm: async () => {
        const now = useSettings.getState().settings.sounds;
        set({ packs: now.packs.filter((p) => p.id !== pack.id), pack: now.pack === pack.id ? NEXA_PACK : now.pack });
        // the files go once the settings without them are saved, so no device is left naming a file that is gone
        await useSettings.getState().flush();
        for (const f of Object.values(pack.files)) if (f) await dropSoundFile(f.blob);
      },
    });
  };

  const pickFile = (name: SoundName) => {
    picking.current = name;
    input.current?.click();
  };

  const onFile = async (file: File | undefined) => {
    const name = picking.current;
    picking.current = null;
    if (input.current) input.current.value = "";
    if (!file || !name || !custom) return;
    setBusy(name);
    try {
      const added = await addSoundFile(file, name);
      const was = useSettings.getState().settings.sounds.packs.find((p) => p.id === custom.id)?.files[name];
      updatePack(custom.id, (p) => ({ ...p, files: { ...p.files, [name]: added } }));
      await useSettings.getState().flush();
      if (was) await dropSoundFile(was.blob);
      void play(name);
    } catch (err) {
      toast(fileProblem(err), "error");
    } finally {
      setBusy(null);
    }
  };

  const resetSound = async (name: SoundName) => {
    if (!custom) return;
    const was = custom.files[name];
    updatePack(custom.id, (p) => {
      const files = { ...p.files };
      delete files[name];
      return { ...p, files };
    });
    if (!was) return;
    await useSettings.getState().flush();
    await dropSoundFile(was.blob);
  };

  const toggleSound = (name: SoundName, on: boolean) => {
    const off = new Set(useSettings.getState().settings.sounds.off);
    if (on) off.delete(name);
    else off.add(name);
    set({ off: SOUND_NAMES.filter((n) => off.has(n)) });
  };

  const packCard = (id: string, name: string, desc: string, Icon: LucideIcon, extra?: string) => {
    const on = sounds.pack === id;
    return (
      <div key={id} className={s.pack} data-on={on} data-testid={`sound-pack-${id}`}>
        <button className={s.packPick} role="radio" aria-checked={on} onClick={() => set({ pack: id })}>
          <span className={s.packIcon} aria-hidden="true"><Icon size={18} /></span>
          <span className={s.packText}>
            <span className={s.packName}>{name}</span>
            <span className={s.packDesc}>{desc}</span>
            {extra ? <span className={s.packDesc}>{extra}</span> : null}
          </span>
          {on ? <span className={s.packCheck} aria-hidden="true"><Check size={14} strokeWidth={3} /></span> : null}
        </button>
        <button className={s.packPlay} onClick={() => playPack(id)} aria-label={t("settings.sounds.playPack", { name })} title={t("settings.sounds.playPack", { name })}>
          <Play size={14} />
        </button>
      </div>
    );
  };

  return (
    <>
      <h2 className={st.title}>{t("settings.sounds.title")}</h2>
      {!notifySounds ? (
        <p className={s.note} role="status">{t("settings.sounds.notificationsOff")}</p>
      ) : null}

      <h3 className={st.h5} data-setting="soundPack">{t("settings.sounds.packHeading")}</h3>
      <p className={st.hint}>{t("settings.sounds.packIntro")}</p>
      <div className={s.packs} role="radiogroup" aria-label={t("settings.sounds.packHeading")}>
        {packCard(NEXA_PACK, nexa, t("settings.sounds.packNexaDesc"), Sparkles)}
        {sounds.packs.map((p) => {
          const replaced = Object.keys(p.files).length;
          return packCard(p.id, p.name || t("settings.sounds.untitledPack"), t("settings.sounds.customPackDesc", { base: nexa }), Upload, t("settings.sounds.replacedCount", { count: replaced }));
        })}
        {sounds.packs.length < MAX_PACKS ? (
          <button className={s.newPack} onClick={() => void createPack()} data-testid="sound-pack-create">
            <Plus size={18} />
            <span>{t("settings.sounds.createPack")}</span>
          </button>
        ) : null}
      </div>

      <h3 className={st.h5} data-setting="soundVolume">{t("settings.sounds.volume")}</h3>
      <div className={s.volume}>
        <input type="range" min={0} max={100} step={5} className={st.slider} value={sounds.volume} onChange={(e) => set({ volume: Number(e.target.value) })} onPointerUp={() => void play("message")} onKeyUp={(e) => (e.key.startsWith("Arrow") ? void play("message") : undefined)} aria-label={t("settings.sounds.volume")} aria-valuetext={`${sounds.volume}%`} data-testid="sound-volume" />
        <span className={s.volumeValue}>{sounds.volume}%</span>
      </div>

      {custom ? (
        <div className={s.customHead}>
          <div className={s.customTitle}>
            <span className={s.customName}>{custom.name || t("settings.sounds.untitledPack")}</span>
            <span className={st.hint}>{t("settings.sounds.customIntro", { base: nexa })}</span>
          </div>
          <div className={s.customBtns}>
            <Button size="sm" variant="secondary" onClick={() => void renamePack(custom)}>{t("settings.sounds.rename")}</Button>
            <Button size="sm" variant="ghost" className={s.dangerBtn} onClick={() => deletePack(custom)}><Trash2 size={14} />{t("settings.sounds.deletePack")}</Button>
          </div>
        </div>
      ) : (
        <h3 className={st.h5}>{t("settings.sounds.soundsHeading")}</h3>
      )}
      <p className={st.hint}>{custom ? t("settings.sounds.uploadHint") : t("settings.sounds.soundsIntro")}</p>
      <ul className={s.list} data-testid="sound-list">
        {SOUND_NAMES.map((name) => {
          const Icon = SOUND_ICON[name];
          const file = custom?.files[name];
          const on = !sounds.off.includes(name);
          return (
            <li key={name} className={s.sound} data-off={!on} data-sound={name}>
              <button className={s.play} onClick={() => void play(name)} aria-label={t("settings.sounds.playSound", { name: soundLabel(name) })} title={t("settings.sounds.playSound", { name: soundLabel(name) })}>
                <Play size={14} />
              </button>
              <span className={s.soundIcon} aria-hidden="true"><Icon size={16} /></span>
              <span className={s.soundText}>
                <span className={s.soundName}>{soundLabel(name)}</span>
                <span className={s.soundSub}>
                  {file ? t("settings.sounds.fileLine", { name: file.name || t("settings.sounds.yourRecording"), seconds: seconds(file.ms) }) : custom ? t("settings.sounds.fromPack", { name: nexa }) : soundWhen(name)}
                </span>
              </span>
              {custom ? (
                <span className={s.soundBtns}>
                  {file ? (
                    <button className={s.iconBtn} onClick={() => void resetSound(name)} aria-label={t("settings.sounds.useDefault", { name: soundLabel(name) })} title={t("settings.sounds.useDefaultShort")}>
                      <RotateCcw size={14} />
                    </button>
                  ) : null}
                  <Button size="sm" variant="secondary" loading={busy === name} disabled={busy !== null && busy !== name} onClick={() => pickFile(name)} data-testid={`sound-upload-${name}`}>
                    <Upload size={14} />
                    {file ? t("settings.sounds.replace") : t("settings.sounds.upload")}
                  </Button>
                </span>
              ) : null}
              <Toggle checked={on} onChange={(v) => toggleSound(name, v)} ariaLabel={t("settings.sounds.playThisSound", { name: soundLabel(name) })} />
            </li>
          );
        })}
      </ul>
      {hint ? <p className={st.hint} role="status" data-testid="sound-trouble">{hint}</p> : null}
      {custom ? <p className={s.footnote}>{t("settings.sounds.limits", { short: seconds(6000), long: seconds(20_000) })}</p> : null}
      <input ref={input} type="file" accept="audio/*,.mp3,.wav,.ogg,.oga,.m4a,.aac,.flac,.opus,.webm" hidden onChange={(e) => void onFile(e.target.files?.[0])} data-testid="sound-file-input" />
    </>
  );
}
