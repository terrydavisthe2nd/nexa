import { X } from "lucide-react";
import { Toggle } from "@/components/common/Toggle";
import { forget } from "@/lib/spell";
import { stopTTS } from "@/lib/tts";
import { useSettings } from "@/stores/settings";
import s from "./Settings.module.css";

import { t } from "@/i18n";

/**
 * Messages & Media: how conversations are laid out, what links bring with
 * them, and the message box. These used to be spread over Appearance,
 * Privacy & Safety and Notifications, wherever each was first added.
 */
export default function ChatPage() {
  const st = useSettings((x) => x.settings);
  const patch = useSettings((x) => x.patch);
  return (
    <>
      <h2 className={s.title}>{t("settings.chat.title")}</h2>
      <h3 className={s.h5} data-setting="messageDisplay">{t("settings.appearance.messageDisplay")}</h3>
      {(["cozy", "compact"] as const).map((m) => (
        <label key={m} className={s.radio} data-on={st.messageDisplay === m}>
          <input type="radio" name="display" checked={st.messageDisplay === m} onChange={() => patch({ messageDisplay: m })} />
          <span className={s.radioText}>
            <span className={s.radioLabel}>{m === "cozy" ? t("settings.appearance.cozy") : t("settings.appearance.compact")}</span>
            <span className={s.radioDesc}>{m === "cozy" ? t("settings.appearance.cozyDesc") : t("settings.appearance.compactDesc")}</span>
          </span>
        </label>
      ))}
      <h3 className={s.h5}>{t("settings.chat.linksAndMedia")}</h3>
      <Toggle setting="linkPreviews" checked={st.linkPreviews} onChange={(v) => patch({ linkPreviews: v })} label={t("settings.privacy.fetchLinkPreviewsWhenISend")} description={t("settings.privacy.yourDeviceAsksTheServerTo")} />
      <Toggle setting="imagePreviews" checked={st.imagePreviews} onChange={(v) => patch({ imagePreviews: v })} label={t("settings.privacy.showEmbeddedImagesAndGifsFrom")} description={t("settings.privacy.picturesGifsAndVideosFromLinks")} />
      <h3 className={s.h5}>{t("settings.chat.messageBox")}</h3>
      <Toggle setting="spellcheck" checked={st.spellcheck !== false} onChange={(v) => patch({ spellcheck: v })} label={t("settings.appearance.spellCheck")} description={t("settings.appearance.underlinesMisspelledWordsInTheMessage")} />
      {st.spellWords.length ? (
        // the words added from the right-click menu, each one a click away from being checked again
        <div className={s.spellWords} role="group" aria-labelledby="spell-words-head" data-testid="spell-words" data-setting="spellWords">
          <div className={s.spellWordsHead} id="spell-words-head">{t("settings.appearance.yourWords")}</div>
          <div className={s.spellWordList}>
            {st.spellWords.map((w) => (
              <span key={w} className={s.spellWord}>
                <span dir="auto">{w}</span>
                <button type="button" className={s.spellWordRemove} aria-label={t("settings.appearance.removeWord", { word: w })} title={t("settings.appearance.removeWord", { word: w })} onClick={() => forget(w)}>
                  <X size={14} aria-hidden="true" />
                </button>
              </span>
            ))}
          </div>
        </div>
      ) : null}
      <Toggle setting="sendButton" checked={st.sendButton} onChange={(v) => patch({ sendButton: v })} label={t("settings.appearance.sendButton")} description={t("settings.appearance.sendButtonHint")} />
      <Toggle setting="smartEmoji" checked={st.smartEmoji} onChange={(v) => patch({ smartEmoji: v })} label={t("settings.appearance.smartEmoji")} description={t("settings.appearance.smartEmojiHint")} />
      <h3 className={s.h5}>{t("settings.chat.typingHeading")}</h3>
      <Toggle setting="sendTyping" checked={st.sendTyping !== false} onChange={(v) => patch({ sendTyping: v })} label={t("settings.chat.sendTyping")} description={t("settings.chat.sendTypingHint")} />
      <Toggle setting="customTyping" checked={st.customTyping !== false} onChange={(v) => patch({ customTyping: v })} label={t("settings.chat.customTyping")} description={t("settings.chat.customTypingHint")} />
      <Toggle setting="typingAvatars" checked={st.typingAvatars !== false} onChange={(v) => patch({ typingAvatars: v })} label={t("settings.chat.typingAvatars")} description={t("settings.chat.typingAvatarsHint")} />
      <Toggle setting="typingChannels" checked={st.typingChannels !== false} onChange={(v) => patch({ typingChannels: v })} label={t("settings.chat.typingChannels")} description={t("settings.chat.typingChannelsHint")} />
      <h3 className={s.h5}>{t("settings.chat.reading")}</h3>
      <Toggle
        setting="tts"
        checked={st.tts}
        onChange={(v) => {
          patch({ tts: v });
          if (!v) stopTTS();
        }}
        label={t("settings.notifications.readTtsAloud")}
        description={t("settings.notifications.readTtsAloudHint")}
      />
    </>
  );
}
