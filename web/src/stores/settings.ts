import { create } from "zustand";
import type { FavoriteGif } from "@/lib/gifFavorites";
import { mediaKey } from "@/lib/mediaLinks";
import { users as usersApi } from "@/api/users";
import { ApiError } from "@/api/client";
import { decryptSettings, encryptSettings } from "@/lib/settingsCrypto";
import { isVoiceChannels, type VoiceChannels } from "@/voice/channels";
import { fromStreamQuality, isShareFps, isShareResolution, shareModeOf } from "@/voice/shareQuality";
import { sanitizeRail, type RailLayout } from "@/lib/railLayout";
import { EMPTY_DM_LIST, sanitizeDMList, type DMListLayout } from "@/lib/dmLayout";
import type { CustomCss, CustomTheme, PluginEntry } from "@/lib/look/types";
import { sanitizeUsage, type EmojiUsage } from "@/lib/emojiUsage";
import { DEFAULT_SOUNDS, sanitizeSounds, type SoundSettings } from "@/lib/soundPacks";
import { sanitizeKeybinds, sanitizePluginKeybinds, type Keybinds, type PluginKeybinds } from "@/lib/keybinds";

/** A classic theme, "system", or the id of a gallery preset or a custom theme (lib/look). */
export type Theme = "dark" | "light" | "ash" | "onyx" | "system" | (string & {});
export type NotificationLevel = "all" | "mentions" | "none";
/** What a notification may show: the sender and the text, the sender only, or neither. */
export type NotificationPreview = "full" | "name" | "none";
export type ShareResolution = "720p" | "1080p" | "1440p" | "source";
export type ShareFps = 15 | 30 | 60;
/** How a share is tuned: smooth and light, sharp and full, or the person's own choices. */
export type ShareMode = "performance" | "quality" | "custom";
/** What a Custom share gives up first when the connection is short: frames (it stays sharp) or sharpness (it stays smooth). */
export type ShareKeep = "sharp" | "smooth";
/**
 * How a call takes the noise out of the microphone: "enhanced" runs RNNoise
 * on this device (voice/denoise.ts), "standard" asks the browser for its own,
 * "off" sends the microphone as it is.
 */
export type NoiseMode = "enhanced" | "standard" | "off";
export const NOISE_MODES: readonly NoiseMode[] = ["enhanced", "standard", "off"];
export const isNoiseMode = (v: unknown): v is NoiseMode => v === "enhanced" || v === "standard" || v === "off";

export interface VoiceSettings {
  inputDeviceId: string | null;
  /** The chosen microphone's name, so it can be found again when its id changes. */
  inputDeviceLabel: string | null;
  outputDeviceId: string | null;
  outputDeviceLabel: string | null;
  /** The chosen camera, found again by name when its id changes; null lets the browser pick. */
  cameraDeviceId: string | null;
  cameraDeviceLabel: string | null;
  inputVolume: number;
  outputVolume: number;
  mode: "vad" | "ptt";
  pttKey: string;
  /** The key held to talk as Priority Speaker, in a server's voice channel where that is allowed; empty for none. */
  priorityKey: string;
  sensitivity: number;
  autoSensitivity: boolean;
  noiseMode: NoiseMode;
  /**
   * Whether noise suppression is on at all, kept in step with noiseMode for
   * the copies of the app from before it, which only had this switch.
   */
  noiseSuppression: boolean;
  echoCancellation: boolean;
  autoGain: boolean;
  /** How this device's voice is sent: one channel, one channel in both ears, or the microphone's own left and right (voice/channels.ts). */
  voiceChannels: VoiceChannels;
  /** Plays every voice and shared sound in one channel, on this account's own devices only. */
  hearMono: boolean;
  /** Which way a share is tuned (voice/shareQuality.ts sharePlan); the share dialog starts from the last one used. */
  shareMode: ShareMode;
  /** A Custom share's size and frame rate. */
  shareResolution: ShareResolution;
  shareFps: ShareFps;
  /** A Custom share's bitrate limit in bits a second; null follows its size and frame rate. */
  shareBitrate: number | null;
  /** What a Custom share keeps when the connection is short. */
  shareKeep: ShareKeep;
  /** Whether a screen share carries what the computer plays. */
  shareAudio: boolean;
}

/**
 * The passcode policy, kept with the account so every device asks for it.
 * The wrapped key itself never leaves the device that made it; the verifier
 * is only enough to check a passcode, not to derive anything from it.
 */
export interface AppLockPolicy {
  /** base64 argon2 salt. */
  salt: string;
  params: { m: number; t: number; p: number; v: 1 };
  /** base64 of a value derived from the passcode key, for checking it. */
  verifier: string;
  autoLockMs: number;
  lockOnBlur: boolean;
  blurDelayMs: number;
}

export type FontFace = "default" | "classic";

/**
 * What staff are told about (lib/staffAlerts.ts): new safety reports (every
 * one, only urgent ones, or none), members writing in cases, and bug reports.
 * Missing means the defaults, everything on.
 */
export interface StaffAlertSettings {
  reports?: "all" | "urgent" | "off";
  cases?: boolean;
  bugs?: boolean;
}

export interface Settings {
  theme: Theme;
  accent: string | null;
  fontScale: number;
  /**
   * The app's typeface: Wix Madefor, or the classic pair it had before
   * (Onest, with wide Mona Sans for names and titles), loaded only for
   * whoever picks it.
   */
  fontFace: FontFace;
  messageDisplay: "cozy" | "compact";
  reducedMotion: boolean;
  underlineLinks: boolean;
  /** BCP 47 tag, or null to follow the browser. Travels with the account. */
  locale: string | null;
  /** Underline misspelled words while typing, with suggestions on a right click. */
  spellcheck: boolean;
  /** Words this account added to its dictionary (lib/spell), in lower case. */
  spellWords: string[];
  /** A Send button beside the message box on a computer too; touch screens always have one. */
  sendButton: boolean;
  linkPreviews: boolean;
  imagePreviews: boolean;
  /** Read aloud the messages a webhook sends to be read aloud, in the conversation open in front of you (lib/tts.ts). */
  tts: boolean;
  developerMode: boolean;
  /** Show what the desktop app hears from games and music (Rich Presence) to others. */
  shareActivity: boolean;
  /** Show as Idle after a while away, while the status chosen is Online (see lib/away.ts). */
  autoIdle: boolean;
  /**
   * The keys chosen for muting, deafening and sharing activity (lib/keybinds),
   * only the ones changed from the defaults; "" is a key taken off.
   */
  keybinds: Partial<Keybinds>;
  /** In the desktop app, the keybinds answer even while another window is in front. */
  backgroundKeybinds: boolean;
  /** The keys chosen for plugins' keybinds (lib/look/plugins), by plugin id and then the keybind's own id. */
  pluginKeybinds: PluginKeybinds;
  /** Tell others when this account is typing (Composer sends `typing` only while on). */
  sendTyping: boolean;
  /** Show others' own typing styles (lib/typingStyle); off, everyone is shown with the plain dots. */
  customTyping: boolean;
  /** Someone typing turns their presence dot into a pill of dots, in the DM list and member lists (Avatar). */
  typingAvatars: boolean;
  /** Dots on a server's channels while someone types in them (ServerSidebar). */
  typingChannels: boolean;
  /** Snow behind the messages in a seasonal theme that has it (components/chat/SnowFall.tsx). */
  seasonalSnow: boolean;
  animateAvatars: boolean;
  animateIcons: boolean;
  animateBanners: boolean;
  animateEmoji: boolean;
  animateStickers: boolean;
  animateNames: boolean;
  sidebarWidth: number;
  showMembers: boolean;
  recentEmoji: string[];
  /**
   * Suggest emoji by what this account uses: a bare ":" offers the ones it
   * uses most, and a few letters find the one it usually means, matched
   * inside names too (lib/emojiSuggest.ts). Off, suggestions go by name alone.
   */
  smartEmoji: boolean;
  /** How much, and how lately, each emoji was used (lib/emojiUsage.ts); only counted while smartEmoji is on. */
  emojiUsage: EmojiUsage;
  /** The emoji this account had already sent before smart suggestions came have been counted (lib/emojiSuggest.ts learnEmojiHistory). */
  emojiLearned: boolean;
  favoriteGifs: FavoriteGif[];
  /** Stickers starred in the picker, by id, newest first. One whose pack is not on this account stays out of sight until it is. */
  favoriteStickers: string[];
  /** Sticker packs folded away in the picker. */
  collapsedStickerPacks: string[];
  skinTone: number;
  /**
   * desktop: notifications in a browser, which has to be allowed first.
   * app: notifications in the desktop app, on unless turned off.
   */
  notifications: { desktop: boolean; app: boolean; sounds: boolean; perChannel: Record<string, NotificationLevel>; muted: string[]; preview: NotificationPreview; staff?: StaffAlertSettings };
  /**
   * Which sound pack plays, how loud, which sounds are off, and the account's
   * own packs: their names, and the sealed recordings each plays (lib/soundPacks.ts).
   */
  sounds: SoundSettings;
  drafts: Record<string, string>;
  voice: VoiceSettings;
  serverPositions: string[];
  collapsedCategories: string[];
  userNotes: Record<string, string>;
  appLock: AppLockPolicy | null;

  // How the server list and the DM list are arranged: folders, pins, groups, the Nexa conversation.
  /**
   * The server rail as arranged: folders, and what is pinned to the top. Null
   * until the first time the account is loaded after folders came in, when
   * the order in serverPositions becomes it (see lib/railLayout.ts).
   */
  rail: RailLayout | null;
  /** Pinned conversations and the groups of the DM list (see lib/dmLayout.ts). */
  dmList: DMListLayout;
  /**
   * When the Nexa conversation was closed, as Nexa's latest activity then
   * (its `latest`); 0 while it is open. Anything newer from Nexa brings it back.
   */
  nexaClosed: number;

  // What blocking someone hides.
  /**
   * What becomes of messages from people this account has blocked (see
   * lib/blocked.ts). hideMessages: they leave no trace at all, instead of
   * folding into a "blocked messages" row. hideReplies: messages that reply to
   * theirs go too; it only counts while hideMessages is on.
   */
  blocking: { hideMessages: boolean; hideReplies: boolean };

  // Themes, custom CSS and plugins.
  /** Themes made in the theme builder; `theme` names one by its id. Checked on use (lib/look/customTheme.ts). */
  customThemes: CustomTheme[];
  /** The two themes "Sync with system" moves between. */
  systemThemes: { dark: string; light: string };
  /** The person's own CSS, and stylesheets loaded from links (their text stays on each device). */
  customCss: CustomCss;
  /** Plugins, each pinned to the exact code that was approved (lib/look/plugins). */
  plugins: PluginEntry[];

  // What's New.
  /**
   * The newest What's New release this account has been shown (lib/whatsNew.ts),
   * kept with the account so the pop-up comes once per account, not per device.
   */
  whatsNewSeen: string | null;
  /**
   * The release the "Nexa isn't free to run" prompt was last shown for
   * (components/support/Upsell.tsx): it comes once per update, to accounts
   * without a plan.
   */
  supportPromptSeen: string | null;

  // Search on this device.
  /**
   * Keep a search index on each of this account's devices: every message it
   * can read, decrypted there and stored there encrypted. Off, search walks
   * history only while it searches and keeps nothing once the app closes.
   */
  searchIndex: boolean;
  /** The last few searches, newest first, for the search box to offer again. Inside the encrypted settings like everything here. */
  searchRecent: string[];
}

export const DEFAULT_SETTINGS: Settings = {
  theme: "dark",
  accent: null,
  fontScale: 1,
  fontFace: "default",
  messageDisplay: "cozy",
  reducedMotion: false,
  underlineLinks: false,
  locale: null,
  spellcheck: true,
  spellWords: [],
  sendButton: false,
  linkPreviews: true,
  imagePreviews: true,
  tts: true,
  developerMode: false,
  shareActivity: true,
  autoIdle: true,
  keybinds: {},
  pluginKeybinds: {},
  backgroundKeybinds: true,
  sendTyping: true,
  customTyping: true,
  typingAvatars: true,
  seasonalSnow: true,
  animateAvatars: true,
  animateIcons: true,
  animateBanners: true,
  animateEmoji: true,
  animateStickers: true,
  animateNames: true,

  typingChannels: true,
  sidebarWidth: 240,
  showMembers: true,
  recentEmoji: [],
  smartEmoji: true,
  emojiUsage: {},
  emojiLearned: false,
  favoriteGifs: [],
  favoriteStickers: [],
  collapsedStickerPacks: [],
  skinTone: 0,
  notifications: { desktop: false, app: true, sounds: true, perChannel: {}, muted: [], preview: "full" },
  sounds: DEFAULT_SOUNDS,
  drafts: {},
  voice: {
    inputDeviceId: null,
    inputDeviceLabel: null,
    outputDeviceId: null,
    cameraDeviceId: null,
    cameraDeviceLabel: null,
    outputDeviceLabel: null,
    inputVolume: 100,
    outputVolume: 100,
    mode: "vad",
    pttKey: "`",
    priorityKey: "",
    sensitivity: -50,
    autoSensitivity: true,
    noiseMode: "enhanced",
    noiseSuppression: true,
    echoCancellation: true,
    autoGain: true,
    voiceChannels: "mono",
    hearMono: false,
    shareMode: "performance",
    shareResolution: "1080p",
    shareFps: 30,
    shareBitrate: null,
    shareKeep: "sharp",
    shareAudio: true,
  },
  serverPositions: [],
  collapsedCategories: [],
  userNotes: {},
  appLock: null,

  rail: null,
  dmList: EMPTY_DM_LIST,
  nexaClosed: 0,

  blocking: { hideMessages: false, hideReplies: false },

  customThemes: [],
  systemThemes: { dark: "dark", light: "light" },
  customCss: { snippet: "", snippetOn: true, links: [] },
  plugins: [],

  whatsNewSeen: null,
  supportPromptSeen: null,

  searchIndex: true,
  searchRecent: [],
};

function mergeSettings(base: Settings, incoming: unknown): Settings {
  if (typeof incoming !== "object" || incoming === null) return base;
  const inc = incoming as Partial<Settings>;
  return {
    ...base,
    ...inc,
    notifications: { ...base.notifications, ...(inc.notifications ?? {}) },
    voice: mergeVoice(base.voice, inc.voice),
    sounds: "sounds" in inc ? sanitizeSounds(inc.sounds) : base.sounds,

    // read defensively, as every version of the app may have written them
    rail: "rail" in inc ? sanitizeRail(inc.rail) : base.rail,
    dmList: "dmList" in inc ? sanitizeDMList(inc.dmList) : base.dmList,
    nexaClosed: typeof inc.nexaClosed === "number" && Number.isFinite(inc.nexaClosed) ? inc.nexaClosed : base.nexaClosed,

    blocking: { ...base.blocking, ...(typeof inc.blocking === "object" && inc.blocking !== null ? inc.blocking : {}) },

    customThemes: Array.isArray(inc.customThemes) ? inc.customThemes : base.customThemes,
    systemThemes: { ...base.systemThemes, ...(inc.systemThemes ?? {}) },
    customCss: { ...base.customCss, ...(inc.customCss ?? {}) },
    plugins: Array.isArray(inc.plugins) ? inc.plugins : base.plugins,

    whatsNewSeen: typeof inc.whatsNewSeen === "string" || inc.whatsNewSeen === null ? inc.whatsNewSeen : base.whatsNewSeen,
    supportPromptSeen: typeof inc.supportPromptSeen === "string" || inc.supportPromptSeen === null ? inc.supportPromptSeen : base.supportPromptSeen,

    fontFace: inc.fontFace === "classic" || inc.fontFace === "default" ? inc.fontFace : base.fontFace,

    smartEmoji: typeof inc.smartEmoji === "boolean" ? inc.smartEmoji : base.smartEmoji,
    emojiUsage: "emojiUsage" in inc ? sanitizeUsage(inc.emojiUsage) : base.emojiUsage,
    emojiLearned: typeof inc.emojiLearned === "boolean" ? inc.emojiLearned : base.emojiLearned,

    spellWords: Array.isArray(inc.spellWords) ? inc.spellWords.filter((w): w is string => typeof w === "string" && w.length > 0 && w.length <= 64).slice(-2000) : base.spellWords,
    sendButton: typeof inc.sendButton === "boolean" ? inc.sendButton : base.sendButton,

    keybinds: "keybinds" in inc ? sanitizeKeybinds(inc.keybinds) : base.keybinds,
    backgroundKeybinds: typeof inc.backgroundKeybinds === "boolean" ? inc.backgroundKeybinds : base.backgroundKeybinds,
    // a deleted plugin's keys go with it, whichever device deleted it
    pluginKeybinds:
      "pluginKeybinds" in inc || Array.isArray(inc.plugins)
        ? sanitizePluginKeybinds(inc.pluginKeybinds ?? base.pluginKeybinds, (Array.isArray(inc.plugins) ? inc.plugins : base.plugins).map((p) => (p as { id?: unknown })?.id).filter((id): id is string => typeof id === "string"))
        : base.pluginKeybinds,
    sendTyping: typeof inc.sendTyping === "boolean" ? inc.sendTyping : base.sendTyping,
    customTyping: typeof inc.customTyping === "boolean" ? inc.customTyping : base.customTyping,
    typingAvatars: typeof inc.typingAvatars === "boolean" ? inc.typingAvatars : base.typingAvatars,
    animateAvatars: typeof inc.animateAvatars === "boolean" ? inc.animateAvatars : base.animateAvatars,
    animateIcons: typeof inc.animateIcons === "boolean" ? inc.animateIcons : base.animateIcons,
    animateBanners: typeof inc.animateBanners === "boolean" ? inc.animateBanners : base.animateBanners,
    animateEmoji: typeof inc.animateEmoji === "boolean" ? inc.animateEmoji : base.animateEmoji,
    animateNames: typeof inc.animateNames === "boolean" ? inc.animateNames : base.animateNames,
    animateStickers: typeof inc.animateStickers === "boolean" ? inc.animateStickers : base.animateStickers,
    seasonalSnow: typeof inc.seasonalSnow === "boolean" ? inc.seasonalSnow : base.seasonalSnow,
    typingChannels: typeof inc.typingChannels === "boolean" ? inc.typingChannels : base.typingChannels,

    favoriteStickers: Array.isArray(inc.favoriteStickers) ? inc.favoriteStickers.filter((id): id is string => typeof id === "string").slice(0, MAX_FAVORITE_STICKERS) : base.favoriteStickers,
    collapsedStickerPacks: Array.isArray(inc.collapsedStickerPacks) ? inc.collapsedStickerPacks.filter((id): id is string => typeof id === "string") : base.collapsedStickerPacks,

    searchIndex: typeof inc.searchIndex === "boolean" ? inc.searchIndex : base.searchIndex,
    // no more than search/recent.ts keeps, whatever an older or odd copy holds
    searchRecent: Array.isArray(inc.searchRecent) ? inc.searchRecent.filter((q): q is string => typeof q === "string" && q.length <= 200).slice(0, 8) : base.searchRecent,
  };
}

type NotificationSettings = Settings["notifications"];

/**
 * What this device did to the two collections inside `notifications`, entry
 * by entry: for `muted`, true for muted here and false for unmuted here; for
 * `levels`, true for a level set here and false for one cleared here. The
 * lists alone cannot tell "unmuted here" from "never muted here", so merging
 * the lists themselves brought back every unmute this device made, and an
 * untouched level of this device's overwrote another device's new one.
 */
export interface ListEdits {
  muted: Map<string, boolean>;
  levels: Map<string, boolean>;
  /**
   * Favourite GIFs by what they point at (mediaKey): the favourite starred
   * here, or null for one taken away here. The list used to be saved whole,
   * so a device whose copy was a little old put its own list back over a GIF
   * favourited on another device a moment before, and that GIF was gone.
   */
  gifs: Map<string, FavoriteGif | null>;
  /** Favourite stickers by id, the same way: true for starred here, false for taken away here. */
  stickers: Map<string, boolean>;
}

/** How many favourite GIFs are kept; past this the oldest drop off. */
export const MAX_FAVORITE_GIFS = 500;
/** How many favourite stickers are kept. */
export const MAX_FAVORITE_STICKERS = 200;

/** The entries a change to `notifications` or `favoriteGifs` added, removed or changed. */
export function editsOf(beforeAll: Settings, change: Partial<Settings>): ListEdits | null {
  const before = beforeAll.notifications;
  const after: Partial<NotificationSettings> = change.notifications ?? {};
  const gifs = new Map<string, FavoriteGif | null>();
  if (change.favoriteGifs) {
    const was = new Set(beforeAll.favoriteGifs.map((f) => mediaKey(f.url)));
    const now = new Map(change.favoriteGifs.map((f) => [mediaKey(f.url), f] as const));
    for (const [key, f] of now) if (!was.has(key)) gifs.set(key, f);
    for (const key of was) if (!now.has(key)) gifs.set(key, null);
  }
  const stickers = new Map<string, boolean>();
  if (change.favoriteStickers) {
    const was = new Set(beforeAll.favoriteStickers);
    const now = new Set(change.favoriteStickers);
    for (const id of now) if (!was.has(id)) stickers.set(id, true);
    for (const id of was) if (!now.has(id)) stickers.set(id, false);
  }
  const muted = new Map<string, boolean>();
  if (after.muted) {
    const was = new Set(before.muted);
    const now = new Set(after.muted);
    for (const id of now) if (!was.has(id)) muted.set(id, true);
    for (const id of was) if (!now.has(id)) muted.set(id, false);
  }
  const levels = new Map<string, boolean>();
  if (after.perChannel) {
    for (const [id, level] of Object.entries(after.perChannel)) if (before.perChannel[id] !== level) levels.set(id, true);
    for (const id of Object.keys(before.perChannel)) if (!(id in after.perChannel)) levels.set(id, false);
  }
  return muted.size || levels.size || gifs.size || stickers.size ? { muted, levels, gifs, stickers } : null;
}

/** Two batches of edits as one, the later winning entry by entry. */
function mergeEdits(a: ListEdits | null, b: ListEdits | null): ListEdits | null {
  if (!a || !b) return a ?? b;
  return { muted: new Map([...a.muted, ...b.muted]), levels: new Map([...a.levels, ...b.levels]), gifs: new Map([...a.gifs, ...b.gifs]), stickers: new Map([...a.stickers, ...b.stickers]) };
}

/**
 * This device's unsaved changes, laid over the copy that just arrived.
 *
 * `perChannel` is a map and `muted` a list, and both live inside the one
 * `notifications` object, so a plain merge lets whichever side carries that
 * object throw away everything the other side put in them — and this device
 * then saves the result. They are merged entry by entry instead: the entries
 * changed here win, and every other entry is the arriving copy's.
 */
export function layerUnsaved(base: Settings, unsaved: Partial<Settings>, edits: ListEdits | null = null): Settings {
  const merged = mergeSettings(base, unsaved);
  // Favourite GIFs the same way: the arriving list, with what was starred
  // here put in front and what was taken away here taken out.
  if (unsaved.favoriteGifs) {
    const changed = edits?.gifs ?? new Map<string, FavoriteGif | null>();
    const added = unsaved.favoriteGifs.filter((f) => !!changed.get(mediaKey(f.url)));
    const kept = base.favoriteGifs.filter((f) => !changed.has(mediaKey(f.url)));
    merged.favoriteGifs = [...added, ...kept].slice(0, MAX_FAVORITE_GIFS);
  }
  if (unsaved.favoriteStickers) {
    const changed = edits?.stickers ?? new Map<string, boolean>();
    const added = unsaved.favoriteStickers.filter((id) => changed.get(id));
    const kept = base.favoriteStickers.filter((id) => !changed.has(id));
    merged.favoriteStickers = [...added, ...kept].slice(0, MAX_FAVORITE_STICKERS);
  }
  const mine = unsaved.notifications;
  if (!mine) return merged;
  const perChannel = { ...base.notifications.perChannel };
  const muted = new Set(base.notifications.muted);
  for (const [id, set] of edits?.levels ?? []) {
    const level = mine.perChannel?.[id];
    if (set && level !== undefined) perChannel[id] = level;
    else delete perChannel[id];
  }
  for (const [id, on] of edits?.muted ?? []) {
    if (on) muted.add(id);
    else muted.delete(id);
  }
  merged.notifications = { ...merged.notifications, perChannel, muted: [...muted] };
  return merged;
}

const isAlias = (id: string | null | undefined) => id === "default" || id === "communications";

/** Merges voice settings, turning the one "stream quality" older versions saved into a size and frame rate. */
function mergeVoice(base: VoiceSettings, incoming: Partial<VoiceSettings> | undefined): VoiceSettings {
  const { streamQuality, ...rest } = (incoming ?? {}) as Partial<VoiceSettings> & { streamQuality?: unknown };
  const old = rest.shareResolution ? null : fromStreamQuality(streamQuality);
  const voice = { ...base, ...rest, ...(old ?? {}) };
  delete (voice as { streamQuality?: unknown }).streamQuality;
  // a size or frame rate this version does not offer (another device's, or a damaged copy) goes back to what it was
  if (!isShareResolution(voice.shareResolution)) voice.shareResolution = isShareResolution(base.shareResolution) ? base.shareResolution : "1080p";
  if (!isShareFps(voice.shareFps)) voice.shareFps = isShareFps(base.shareFps) ? base.shareFps : 30;
  Object.assign(voice, shareModeOf(rest, voice));
  // Chromium's "default" and "communications" entries are no longer offered, so one saved earlier means the system's own device
  if (isAlias(voice.inputDeviceId)) Object.assign(voice, { inputDeviceId: null, inputDeviceLabel: null });
  if (isAlias(voice.outputDeviceId)) Object.assign(voice, { outputDeviceId: null, outputDeviceLabel: null });
  if (isAlias(voice.cameraDeviceId)) Object.assign(voice, { cameraDeviceId: null, cameraDeviceLabel: null });
  // Noise suppression was one switch, and a copy of the app from before the
  // modes still only knows that one. Whether it is on goes by the switch, and
  // which kind by the mode: on with no mode yet (or turned on again from an old
  // copy) is the default, RNNoise.
  if (!isNoiseMode(voice.noiseMode)) voice.noiseMode = isNoiseMode(base.noiseMode) ? base.noiseMode : "enhanced";
  if (!isNoiseMode(rest.noiseMode) && typeof rest.noiseSuppression === "boolean") voice.noiseMode = rest.noiseSuppression ? "enhanced" : "off";
  if (voice.noiseSuppression === false) voice.noiseMode = "off";
  else if (voice.noiseSuppression === true && voice.noiseMode === "off") voice.noiseMode = "enhanced";
  voice.noiseSuppression = voice.noiseMode !== "off";
  if (!isVoiceChannels(voice.voiceChannels)) voice.voiceChannels = "mono";
  return voice;
}

interface SettingsState {
  settings: Settings;
  version: number;
  loaded: boolean;
  key: Uint8Array | null;
  saving: boolean;
  setKey: (key: Uint8Array | null) => void;
  load: (blob: string | null, version: number) => Promise<void>;
  reload: () => Promise<void>;
  patch: (partial: Partial<Settings>) => void;
  setDraft: (channelId: string, text: string) => void;
  /** Saves now; resolves false when changes made here have not reached the server. */
  flush: () => Promise<boolean>;
  reset: () => void;
}

/** Two batches of changes as one, the later winning, nested groups merged the way patch merges them. */
function mergeChanges(a: Partial<Settings> | null, b: Partial<Settings> | null): Partial<Settings> | null {
  if (!a || !b) return a ?? b;
  const out: Partial<Settings> = { ...a, ...b };
  if (a.notifications && b.notifications) out.notifications = { ...a.notifications, ...b.notifications };
  if (a.voice && b.voice) out.voice = { ...a.voice, ...b.voice };
  return out;
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;
let retryTimer: ReturnType<typeof setTimeout> | null = null;
let failures = 0;

// What this device changed and the server does not have yet: `dirty` waits
// for the next save, `sending` is in the save under way. Only these are laid
// over the server's copy when it is loaded, so a save that crosses another
// device's (or its own echo) keeps both sides instead of one erasing the other.
let dirty: Partial<Settings> | null = null;
let sending: Partial<Settings> | null = null;
// the entry-by-entry record of the same changes to the notification lists
let dirtyEdits: ListEdits | null = null;
let sendingEdits: ListEdits | null = null;
let inflight: Promise<boolean> | null = null;
let again = false;
/** The page is going away: saves ride a keepalive request, which outlives it. */
let leaving = false;
/** What a keepalive request may carry, a little under the browsers' 64 KB. */
const KEEPALIVE_MAX = 60_000;
/** Bumped on reset, so a save from the account before it cannot land on the next one. */
let generation = 0;

/** Whether a failed save is worth trying again later: the network or the server, not the request. */
function retryable(err: unknown): boolean {
  if (!(err instanceof ApiError)) return true;
  return err.status === 0 || err.status === 429 || err.status >= 500;
}

export const useSettings = create<SettingsState>()((set, get) => {
  const scheduleRetry = (): void => {
    if (retryTimer) return;
    const delay = Math.min(60_000, 2000 * 2 ** failures);
    failures += 1;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      void get().flush();
    }, delay);
  };

  /** One save of everything changed so far; reports whether it should run again straight away. */
  const saveOnce = async (): Promise<boolean> => {
    const { key, settings, version, loaded } = get();
    // Before the account's settings arrive, the local copy is defaults plus
    // these changes; saving that would overwrite everything. load saves them.
    if (!key || !loaded || !dirty) return false;
    const gen = generation;
    sending = dirty;
    sendingEdits = dirtyEdits;
    dirty = null;
    dirtyEdits = null;
    try {
      const blob = await encryptSettings(key, settings);
      const res = await usersApi.putSettings(blob, version, leaving && blob.length < KEEPALIVE_MAX ? { keepalive: true } : undefined);
      if (gen !== generation) return false;
      sending = null;
      sendingEdits = null;
      failures = 0;
      set({ version: res.version });
      return false;
    } catch (err) {
      if (gen !== generation) return false;
      dirty = mergeChanges(sending, dirty);
      dirtyEdits = mergeEdits(sendingEdits, dirtyEdits);
      sending = null;
      sendingEdits = null;
      if (err instanceof ApiError && err.status === 409 && failures < 3) {
        // someone else saved first: take theirs, put ours back on top, try again
        failures += 1;
        try {
          await get().reload();
          return gen === generation;
        } catch {
          scheduleRetry();
          return false;
        }
      }
      if (retryable(err) || (err instanceof ApiError && err.status === 409)) scheduleRetry();
      return false;
    }
  };

  return {
    settings: DEFAULT_SETTINGS,
    version: 0,
    loaded: false,
    key: null,
    saving: false,
    setKey: (key) => set({ key }),
    load: async (blob, version) => {
      const key = get().key;
      const doc = blob && key ? await decryptSettings(key, blob) : null;
      // A copy this device cannot read is not an empty one. Taken as the
      // defaults, it would drop the passcode this device keeps (no app lock
      // is the default) and the next save would write the defaults over
      // every setting on the server. Nothing is decided on it: settings stay
      // unloaded, so nothing saves, and the next ready tries again.
      if (blob && doc === null) {
        console.warn("settings: the saved copy could not be read on this device");
        return;
      }
      // a save of ours may have landed while this copy was on its way
      if (get().loaded && version < get().version) return;
      const base = doc ? mergeSettings(DEFAULT_SETTINGS, doc) : DEFAULT_SETTINGS;
      const unsaved = mergeChanges(sending, dirty);
      set({ settings: unsaved ? layerUnsaved(base, unsaved, mergeEdits(sendingEdits, dirtyEdits)) : base, version, loaded: true });
      if (dirty) void get().flush();
    },
    reload: async () => {
      const res = await usersApi.getSettings();
      await get().load(res?.blob ?? null, res?.version ?? 0);
    },
    patch: (partial) => {
      if (partial.notifications || partial.favoriteGifs || partial.favoriteStickers) dirtyEdits = mergeEdits(dirtyEdits, editsOf(get().settings, partial));
      set((s) => ({ settings: mergeSettings(s.settings, partial) }));
      dirty = mergeChanges(dirty, partial);
      if (saveTimer) clearTimeout(saveTimer);
      saveTimer = setTimeout(() => {
        saveTimer = null;
        void get().flush();
      }, 800);
    },
    setDraft: (channelId, text) => {
      const drafts = { ...get().settings.drafts };
      if (text) drafts[channelId] = text;
      else delete drafts[channelId];
      get().patch({ drafts });
    },
    flush: () => {
      // A save already under way takes these changes along when it finishes,
      // and whoever waits on this waits for that too.
      if (inflight) {
        again = true;
        return inflight;
      }
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      const gen = generation;
      set({ saving: true });
      const run = (async (): Promise<boolean> => {
        try {
          do {
            again = false;
            if (await saveOnce()) again = true;
          } while (again && gen === generation);
          // Callers such as the passcode need to know their change really
          // reached the server: it did when nothing made here is still waiting.
          return gen === generation && !dirty && !sending;
        } catch {
          return false;
        } finally {
          if (gen === generation) {
            inflight = null;
            set({ saving: false });
          }
        }
      })();
      inflight = run;
      return run;
    },
    reset: () => {
      if (saveTimer) clearTimeout(saveTimer);
      saveTimer = null;
      if (retryTimer) clearTimeout(retryTimer);
      retryTimer = null;
      generation += 1;
      dirty = null;
      sending = null;
      dirtyEdits = null;
      sendingEdits = null;
      inflight = null;
      again = false;
      failures = 0;
      set({ settings: DEFAULT_SETTINGS, version: 0, loaded: false, key: null, saving: false });
    },
  };
});

/**
 * A page going away — a reload, a closed tab, a phone putting it to sleep —
 * saves what is still waiting. The save normally waits for a pause in the
 * changes, and a theme picked a moment before a reload was simply lost.
 */
function saveBeforeLeaving(): void {
  if (!dirty && !saveTimer) return;
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = null;
  leaving = true;
  void useSettings.getState().flush();
}

if (typeof window !== "undefined" && typeof document !== "undefined") {
  window.addEventListener("pagehide", saveBeforeLeaving);
  window.addEventListener("pageshow", () => {
    leaving = false;
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") saveBeforeLeaving();
    else leaving = false;
  });
}
