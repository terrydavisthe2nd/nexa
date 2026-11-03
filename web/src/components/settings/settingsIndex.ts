import { t } from "@/i18n";

/**
 * Every setting the search at the top of User Settings can find: which page
 * it is on, and which part of that page to bring into view (`anchor`, the
 * page's own `data-setting`). The words searched are the ones the settings
 * show, in the language the app is in, so a search finds what it reads.
 */
export interface SettingEntry {
  page: string;
  anchor?: string;
  label: string;
}

/** Lowered, with accents folded and spaces evened out, so "Mise à jour" is found by "mise a jour". */
export function fold(text: string): string {
  return text
    .toLowerCase()
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .replace(/\s+/g, " ")
    .trim();
}

export function settingsIndex(opts: { pageLabels: Record<string, string> }): SettingEntry[] {
  const e = (page: string, label: string, anchor?: string): SettingEntry => ({ page, label, ...(anchor ? { anchor } : {}) });
  const out: SettingEntry[] = [
    e("account", t("settings.account.username"), "account"),
    e("account", t("settings.account.displayName"), "account"),
    e("account", t("settings.account.changePassword"), "password"),
    e("account", t("settings.account.recoveryKey"), "password"),
    e("account", t("settings.account.deleteAccount"), "deleteAccount"),
    e("profiles", t("settings.profile.avatar"), "avatar"),
    e("profiles", t("settings.profile.profileBanner"), "banner"),
    e("profiles", t("settings.profile.profileTheme"), "profileTheme"),
    e("profiles", t("settings.profile.pronouns"), "profile"),
    e("profiles", t("settings.profile.aboutMe"), "profile"),
    e("profiles", t("typing.editor.title"), "typingStyle"),
    e("profiles", t("nameStyle.title"), "nameStyle"),
    e("profiles", t("widgets.title"), "widgets"),
    e("profiles", t("widgets.group.music"), "widgets"),
    e("profiles", t("widgets.type.artists"), "widgets"),
    e("profiles", t("widgets.type.albums"), "widgets"),
    e("profiles", t("widgets.type.songs"), "widgets"),
    e("profiles", t("serverTag.choose.title"), "serverTag"),
    e("security", t("settings.security.twoFactorAuthentication"), "twoFactor"),
    e("security", t("settings.security.backupCodes"), "twoFactor"),
    e("security", t("settings.security.appLock"), "appLock"),
    e("security", t("settings.security.setAPasscode"), "appLock"),
    e("security", t("settings.security.lockAfterInactivity"), "appLock"),
    e("security", t("settings.security.lockWhenTheAppLosesFocus"), "appLock"),
    e("devices", t("settings.devices.logOutAllOtherDevices"), "sessions"),
    e("subscription", t("support.manage")),
    e("subscription", t("support.yourStorage")),
    e("subscription", t("support.boosts.title")),
    e("privacy", t("settings.privacy.directMessages"), "dmPrivacy"),
    e("privacy", t("settings.privacy.letFriendsAddMe"), "groupAdd"),
    e("privacy", t("server.encryption.encryption"), "encryption"),
    e("privacy", t("settings.privacy.search"), "encryption"),
    e("blocked", t("people.block.hideMessages"), "blockedMessages"),
    e("reports", t("reports.report")),
    e("data", t("settings.privacy.exportMyData"), "export"),
    e("chat", t("settings.appearance.messageDisplay"), "messageDisplay"),
    e("chat", t("settings.privacy.fetchLinkPreviewsWhenISend"), "linkPreviews"),
    e("chat", t("settings.privacy.showEmbeddedImagesAndGifsFrom"), "imagePreviews"),
    e("chat", t("settings.appearance.spellCheck"), "spellcheck"),
    e("chat", t("settings.appearance.yourWords"), "spellcheck"),
    e("chat", t("settings.appearance.sendButton"), "sendButton"),
    e("chat", t("settings.appearance.smartEmoji"), "smartEmoji"),
    e("chat", t("settings.notifications.readTtsAloud"), "tts"),
    e("chat", t("settings.chat.sendTyping"), "sendTyping"),
    e("chat", t("settings.chat.customTyping"), "customTyping"),
    e("chat", t("settings.chat.typingAvatars"), "typingAvatars"),
    e("chat", t("settings.chat.typingChannels"), "typingChannels"),
    e("stickers", t("modals.customStatus.emoji"), "packs"),
    e("notifications", t("settings.notifications.enableDesktopNotifications"), "desktopNotifications"),
    e("notifications", t("settings.notifications.sounds"), "sounds"),
    e("notifications", t("settings.notifications.previewHeading"), "notificationPreview"),
    e("sounds", t("settings.sounds.packHeading"), "soundPack"),
    e("sounds", t("settings.sounds.createPack"), "soundPack"),
    e("sounds", t("settings.sounds.volume"), "soundVolume"),
    e("appearance", t("settings.appearance.theme"), "theme"),
    e("appearance", t("settings.appearance.animateAvatars"), "animateAvatars"),
    e("appearance", t("settings.appearance.animateIcons"), "animateIcons"),
    e("appearance", t("settings.appearance.animateBanners"), "animateBanners"),
    e("appearance", t("settings.appearance.animateEmoji"), "animateEmoji"),
    e("appearance", t("settings.appearance.animateStickers"), "animateStickers"),
    e("appearance", t("settings.appearance.animateNames"), "animateNames"),

    e("appearance", t("settings.appearance.accentColour"), "accent"),
    e("appearance", t("settings.appearance.font"), "font"),
    e("appearance", t("settings.appearance.textSize"), "textSize"),
    e("accessibility", t("settings.appearance.reducedMotion"), "reducedMotion"),
    e("accessibility", t("settings.appearance.alwaysUnderlineLinks"), "underlineLinks"),
    e("voice", t("settings.voice.inputDevice"), "devices"),
    e("voice", t("settings.voice.outputDevice"), "devices"),
    e("voice", t("settings.voice.micTest"), "devices"),
    e("voice", t("settings.voice.camera"), "devices"),
    e("voice", t("settings.voice.inputMode"), "inputMode"),
    e("voice", t("settings.voice.pushToTalkKey"), "inputMode"),
    e("voice", t("voice.noise.title"), "noiseSuppression"),
    e("voice", t("voice.noise.enhanced"), "noiseSuppression"),
    e("voice", t("settings.voice.echoCancellation"), "voiceProcessing"),
    e("voice", t("settings.voice.automaticGainControl"), "voiceProcessing"),
    e("keybinds", t("keybinds.actions.mute"), "keybinds"),
    e("keybinds", t("keybinds.actions.deafen"), "keybinds"),
    e("keybinds", t("keybinds.actions.activity"), "keybinds"),
    e("keybinds", t("keybinds.background"), "backgroundKeybinds"),
    e("activity", t("settings.activity.status"), "activityStatus"),
    e("activity", t("settings.activity.discord"), "discord"),
    e("activity", t("settings.desktop.richPresence"), "richPresence"),
    e("desktop", t("settings.desktop.openNexaWhenISignIn"), "startup"),
    e("desktop", t("settings.desktop.hardwareAcceleration"), "startup"),
    e("desktop", t("settings.desktop.updates"), "updates"),
    e("advanced", t("settings.privacy.developerMode"), "developerMode"),
    e("developers", t("apps.portal.newApp")),
    e("developers", t("apps.portal.token")),
    e("developers", t("apps.portal.connect")),
    e("authorizedApps", t("apps.menu.apps")),
    e("about", t("whatsNew.title"), "version"),
    e("about", t("legal.termsTitle"), "documents"),
    e("about", t("legal.privacyTitle"), "documents"),
    e("about", t("settings.about.contact"), "contact"),
    e("about", t("settings.about.version"), "version"),
  ];
  // every page by its own name too
  for (const [page, label] of Object.entries(opts.pageLabels)) out.unshift({ page, label });
  // only pages that are there to open (the desktop app's, an admin's)
  return out.filter((x) => x.page in opts.pageLabels);
}

/** The entries whose words hold every word of the query, best first: a match at the start of a word before one inside it. */
export function searchSettings(index: SettingEntry[], query: string): SettingEntry[] {
  const words = fold(query).split(" ").filter(Boolean);
  if (!words.length) return [];
  const scored: { entry: SettingEntry; score: number }[] = [];
  for (const entry of index) {
    const label = fold(entry.label);
    let score = 0;
    let ok = true;
    for (const w of words) {
      const at = label.indexOf(w);
      if (at < 0) {
        ok = false;
        break;
      }
      score += at === 0 ? 3 : label[at - 1] === " " ? 2 : 1;
    }
    // a page itself ranks above a setting on it with the same words
    if (ok) scored.push({ entry, score: score + (entry.anchor ? 0 : 0.5) });
  }
  const seen = new Set<string>();
  return scored
    .sort((a, b) => b.score - a.score || a.entry.label.length - b.entry.label.length)
    .map((s) => s.entry)
    .filter((x) => {
      const key = `${x.page}|${x.label}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, 12);
}
