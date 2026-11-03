/**
 * What's New: the major features of each update, shown once to every account
 * that was around before it (components/shell/WhatsNewDialog.tsx), and any
 * time from Settings, About.
 *
 * Every change that adds a major feature, redesigns something or changes
 * behaviour people will notice adds an item here (see CLAUDE.md). A release's
 * id is the day it is deployed, "2026-09-26", then "2026-09-26.2" for a second
 * one that day. Its keys are whatsNew.r20260926.<name>Title and ...Body: the
 * id without its dashes and dots.
 */
import { AtSign, Award, AudioLines, BadgeCheck, BellRing, Bot, Bug, CircleX, Clapperboard, ClipboardCopy, CloudUpload, CodeXml, Compass, CornerUpRight, Eye, EyeOff, Feather, Flag, FolderPlus, Gamepad2, Gavel, Gift, Globe, HandCoins, Headphones, Heart, House, IdCard, ImagePlay, ImagePlus, Images, KeyRound, Keyboard, LayoutGrid, Leaf, Link, Link2, MapPinOff, Megaphone, MessageCircle, MessageCircleMore, MessageSquareDashed, MessageSquarePlus, MessageSquareText, MessagesSquare, Mic, MonitorUp, MousePointerClick, Move, Music, Newspaper, NotebookPen, Paintbrush, Palette, PanelBottom, PanelsTopLeft, Pause, Pin, Play, Puzzle, QrCode, RefreshCw, RemoveFormatting, Reply, RotateCcw, ScanEye, ScrollText, Search, SendHorizontal, ServerCog, Shapes, ShieldCheck, SlidersHorizontal, Smartphone, Smile, Snowflake, Sparkles, SpellCheck, Sticker, StickyNote, Tag, Timer, Trash2, TvMinimalPlay, Type, UserCheck, UserCog, UserPen, Users, UsersRound, Volume2, Vote, Webhook, ZoomIn, type LucideIcon } from "lucide-react";

export interface WhatsNewItem {
  icon: LucideIcon;
  /** i18n keys. */
  title: string;
  body: string;
}

export interface Release {
  id: string;
  items: WhatsNewItem[];
}

/** Newest first. */
export const RELEASES: Release[] = [
  {
    id: "2026-11-02",
    items: [
      { icon: Palette, title: "whatsNew.r20261102.themeBuilderTitle", body: "whatsNew.r20261102.themeBuilderBody" },
      { icon: SlidersHorizontal, title: "whatsNew.r20261102.tuneTitle", body: "whatsNew.r20261102.tuneBody" },
      { icon: Eye, title: "whatsNew.r20261102.visionTitle", body: "whatsNew.r20261102.visionBody" },
      { icon: Flag, title: "whatsNew.r20261102.reportsTitle", body: "whatsNew.r20261102.reportsBody" },
      { icon: MessageSquareText, title: "whatsNew.r20261102.reportFeedbackTitle", body: "whatsNew.r20261102.reportFeedbackBody" },
      { icon: Link2, title: "whatsNew.r20261102.vanityTitle", body: "whatsNew.r20261102.vanityBody" },
      { icon: Award, title: "whatsNew.r20261102.badgesTitle", body: "whatsNew.r20261102.badgesBody" },
      { icon: Gamepad2, title: "whatsNew.r20261102.gamePageTitle", body: "whatsNew.r20261102.gamePageBody" },
      { icon: LayoutGrid, title: "whatsNew.r20261102.cardsTitle", body: "whatsNew.r20261102.cardsBody" },
      { icon: Timer, title: "whatsNew.r20261102.timeWidgetsTitle", body: "whatsNew.r20261102.timeWidgetsBody" },
      { icon: HandCoins, title: "whatsNew.r20261102.pwywTitle", body: "whatsNew.r20261102.pwywBody" },
      { icon: RotateCcw, title: "whatsNew.r20261102.refundsTitle", body: "whatsNew.r20261102.refundsBody" },
    ],
  },
  {
    id: "2026-11-01",
    items: [
      { icon: Music, title: "whatsNew.r20261101.musicLinksTitle", body: "whatsNew.r20261101.musicLinksBody" },
      { icon: MessageCircle, title: "whatsNew.r20261101.jumpLinksTitle", body: "whatsNew.r20261101.jumpLinksBody" },
      { icon: UserPen, title: "whatsNew.r20261101.profileFormTitle", body: "whatsNew.r20261101.profileFormBody" },
      { icon: Images, title: "whatsNew.r20261101.mediaChannelsTitle", body: "whatsNew.r20261101.mediaChannelsBody" },
      { icon: Link2, title: "whatsNew.r20261101.linkChannelsTitle", body: "whatsNew.r20261101.linkChannelsBody" },
      { icon: AtSign, title: "whatsNew.r20261101.mentionsTitle", body: "whatsNew.r20261101.mentionsBody" },
      { icon: Gamepad2, title: "whatsNew.r20261101.activityMovedTitle", body: "whatsNew.r20261101.activityMovedBody" },
      { icon: ZoomIn, title: "whatsNew.r20261101.avatarViewerTitle", body: "whatsNew.r20261101.avatarViewerBody" },
      { icon: MessageCircleMore, title: "whatsNew.r20261101.customTypingPermTitle", body: "whatsNew.r20261101.customTypingPermBody" },
      { icon: EyeOff, title: "whatsNew.r20261101.hideTypingTitle", body: "whatsNew.r20261101.hideTypingBody" },
      { icon: Type, title: "whatsNew.r20261101.channelNamesTitle", body: "whatsNew.r20261101.channelNamesBody" },
      { icon: ImagePlay, title: "whatsNew.r20261101.tenorTitle", body: "whatsNew.r20261101.tenorBody" },
      { icon: Images, title: "whatsNew.r20261101.gifFavoritesTitle", body: "whatsNew.r20261101.gifFavoritesBody" },
      { icon: Puzzle, title: "whatsNew.r20261101.pluginsTitle", body: "whatsNew.r20261101.pluginsBody" },
    ],
  },
  {
    id: "2026-10-31",
    items: [
      { icon: Volume2, title: "whatsNew.r20261031.callSoundsTitle", body: "whatsNew.r20261031.callSoundsBody" },
      { icon: Eye, title: "whatsNew.r20261031.viewAsRoleTitle", body: "whatsNew.r20261031.viewAsRoleBody" },
      { icon: ClipboardCopy, title: "whatsNew.r20261031.duplicateRoleTitle", body: "whatsNew.r20261031.duplicateRoleBody" },
    ],
  },
  {
    id: "2026-10-29",
    items: [
      { icon: Timer, title: "whatsNew.r20261029.maintenanceTitle", body: "whatsNew.r20261029.maintenanceBody" },
    ],
  },
  {
    id: "2026-10-28.5",
    items: [
      { icon: Flag, title: "whatsNew.r202610285.reportsTitle", body: "whatsNew.r202610285.reportsBody" },
    ],
  },
  {
    id: "2026-10-28.4",
    items: [
      { icon: Sparkles, title: "whatsNew.r202610284.nameMotionTitle", body: "whatsNew.r202610284.nameMotionBody" },
    ],
  },
  {
    id: "2026-10-28.3",
    items: [
      { icon: Compass, title: "whatsNew.r202610283.discoveryTitle", body: "whatsNew.r202610283.discoveryBody" },
    ],
  },
  {
    id: "2026-10-28.2",
    items: [
      { icon: FolderPlus, title: "whatsNew.r202610282.saveTitle", body: "whatsNew.r202610282.saveBody" },
    ],
  },
  {
    id: "2026-10-28",
    items: [
      { icon: Users, title: "whatsNew.r20261028.groupActivityTitle", body: "whatsNew.r20261028.groupActivityBody" },
      { icon: ShieldCheck, title: "whatsNew.r20261028.nsfwTitle", body: "whatsNew.r20261028.nsfwBody" },
      { icon: Compass, title: "whatsNew.r20261028.discoveryTitle", body: "whatsNew.r20261028.discoveryBody" },
    ],
  },
  {
    id: "2026-10-27",
    items: [
      { icon: ScrollText, title: "whatsNew.r20261027.transcriptsTitle", body: "whatsNew.r20261027.transcriptsBody" },
      { icon: CornerUpRight, title: "whatsNew.r20261027.forwardTitle", body: "whatsNew.r20261027.forwardBody" },
      { icon: Mic, title: "whatsNew.r20261027.audioTitle", body: "whatsNew.r20261027.audioBody" },
      { icon: ImagePlay, title: "whatsNew.r20261027.animationTitle", body: "whatsNew.r20261027.animationBody" },
    ],
  },
  {
    id: "2026-10-26.7",
    items: [
      { icon: ShieldCheck, title: "whatsNew.r202610267.desktopSecurityTitle", body: "whatsNew.r202610267.desktopSecurityBody" },
    ],
  },
  {
    id: "2026-10-26.6",
    items: [
      { icon: CodeXml, title: "whatsNew.r202610266.openSourceTitle", body: "whatsNew.r202610266.openSourceBody" },
    ],
  },
  {
    id: "2026-10-26.5",
    items: [
      { icon: MousePointerClick, title: "whatsNew.r202610265.demoTitle", body: "whatsNew.r202610265.demoBody" },
    ],
  },
  {
    id: "2026-10-26.4",
    items: [
      { icon: Play, title: "whatsNew.r202610264.mediaLinksTitle", body: "whatsNew.r202610264.mediaLinksBody" },
    ],
  },
  {
    id: "2026-10-26.3",
    items: [
      { icon: ShieldCheck, title: "whatsNew.r202610263.ranksTitle", body: "whatsNew.r202610263.ranksBody" },
    ],
  },
  {
    id: "2026-10-26.2",
    items: [
      { icon: Mic, title: "whatsNew.r202610262.voiceMessagesTitle", body: "whatsNew.r202610262.voiceMessagesBody" },
      { icon: MessageSquareText, title: "whatsNew.r202610262.voiceChatTitle", body: "whatsNew.r202610262.voiceChatBody" },
      { icon: ImagePlus, title: "whatsNew.r202610262.channelPicturesTitle", body: "whatsNew.r202610262.channelPicturesBody" },
    ],
  },
  {
    id: "2026-10-26",
    items: [
      { icon: Link2, title: "whatsNew.r20261026.giftLinkTitle", body: "whatsNew.r20261026.giftLinkBody" },
      { icon: CloudUpload, title: "whatsNew.r20261026.filesTitle", body: "whatsNew.r20261026.filesBody" },
    ],
  },
  {
    id: "2026-10-25.2",
    items: [
      { icon: Heart, title: "whatsNew.r202610252.supportTitle", body: "whatsNew.r202610252.supportBody" },
      { icon: Gift, title: "whatsNew.r202610252.giftTitle", body: "whatsNew.r202610252.giftBody" },
      { icon: CornerUpRight, title: "whatsNew.r202610252.forwardTitle", body: "whatsNew.r202610252.forwardBody" },
      { icon: Sparkles, title: "whatsNew.r202610252.effectsTitle", body: "whatsNew.r202610252.effectsBody" },
    ],
  },
  {
    id: "2026-10-25",
    items: [
      { icon: Users, title: "whatsNew.r20261025.accountsTitle", body: "whatsNew.r20261025.accountsBody" },
      { icon: QrCode, title: "whatsNew.r20261025.qrTitle", body: "whatsNew.r20261025.qrBody" },
      { icon: KeyRound, title: "whatsNew.r20261025.passkeysTitle", body: "whatsNew.r20261025.passkeysBody" },
      { icon: BellRing, title: "whatsNew.r20261025.soundsTitle", body: "whatsNew.r20261025.soundsBody" },
    ],
  },
  {
    id: "2026-10-21",
    items: [
      { icon: Type, title: "whatsNew.r20261021.nameStyleTitle", body: "whatsNew.r20261021.nameStyleBody" },
      { icon: Palette, title: "whatsNew.r20261021.colourPickerTitle", body: "whatsNew.r20261021.colourPickerBody" },
    ],
  },
  {
    id: "2026-10-20.3",
    items: [
      { icon: MousePointerClick, title: "whatsNew.r202610203.personMenuTitle", body: "whatsNew.r202610203.personMenuBody" },
      { icon: Bot, title: "whatsNew.r202610203.botIdTitle", body: "whatsNew.r202610203.botIdBody" },
    ],
  },
  {
    id: "2026-10-20.2",
    items: [
      { icon: Megaphone, title: "whatsNew.r202610202.priorityTitle", body: "whatsNew.r202610202.priorityBody" },
      { icon: Link2, title: "whatsNew.r202610202.syncTitle", body: "whatsNew.r202610202.syncBody" },
      { icon: ShieldCheck, title: "whatsNew.r202610202.permsTitle", body: "whatsNew.r202610202.permsBody" },
      { icon: Move, title: "whatsNew.r202610202.dragTitle", body: "whatsNew.r202610202.dragBody" },
    ],
  },
  {
    id: "2026-10-20",
    items: [
      { icon: ShieldCheck, title: "whatsNew.r20261020.privacyTitle", body: "whatsNew.r20261020.privacyBody" },
    ],
  },
  {
    id: "2026-10-19",
    items: [
      { icon: Bot, title: "whatsNew.r20261019.appsTitle", body: "whatsNew.r20261019.appsBody" },
      { icon: CodeXml, title: "whatsNew.r20261019.portalTitle", body: "whatsNew.r20261019.portalBody" },
      { icon: ScanEye, title: "whatsNew.r20261019.readingTitle", body: "whatsNew.r20261019.readingBody" },
    ],
  },
  {
    id: "2026-10-17",
    items: [
      { icon: PanelsTopLeft, title: "whatsNew.r20261017.templatesTitle", body: "whatsNew.r20261017.templatesBody" },
    ],
  },
  {
    id: "2026-10-09",
    items: [
      { icon: SlidersHorizontal, title: "whatsNew.r20261009.shareModesTitle", body: "whatsNew.r20261009.shareModesBody" },
      { icon: MonitorUp, title: "whatsNew.r20261009.macShareTitle", body: "whatsNew.r20261009.macShareBody" },
      { icon: Volume2, title: "whatsNew.r20261009.streamSoundTitle", body: "whatsNew.r20261009.streamSoundBody" },
      { icon: Feather, title: "whatsNew.r20261009.lighterCallsTitle", body: "whatsNew.r20261009.lighterCallsBody" },
    ],
  },
  {
    id: "2026-10-08.2",
    items: [
      { icon: Timer, title: "whatsNew.r202610082.callTimersTitle", body: "whatsNew.r202610082.callTimersBody" },
    ],
  },
  {
    id: "2026-10-08",
    items: [
      { icon: Leaf, title: "whatsNew.r20261008.seasonalTitle", body: "whatsNew.r20261008.seasonalBody" },
      { icon: Snowflake, title: "whatsNew.r20261008.nightTitle", body: "whatsNew.r20261008.nightBody" },
      { icon: Globe, title: "whatsNew.r20261008.swedishTitle", body: "whatsNew.r20261008.swedishBody" },
      { icon: AtSign, title: "whatsNew.r20261008.mentionsTitle", body: "whatsNew.r20261008.mentionsBody" },
      { icon: Move, title: "whatsNew.r20261008.moveFilesTitle", body: "whatsNew.r20261008.moveFilesBody" },
    ],
  },
  {
    id: "2026-10-07",
    items: [
      { icon: Puzzle, title: "whatsNew.r20261007.pluginReviewTitle", body: "whatsNew.r20261007.pluginReviewBody" },
      { icon: SlidersHorizontal, title: "whatsNew.r20261007.pluginPermsTitle", body: "whatsNew.r20261007.pluginPermsBody" },
      { icon: Keyboard, title: "whatsNew.r20261007.pluginKeysTitle", body: "whatsNew.r20261007.pluginKeysBody" },
    ],
  },
  {
    id: "2026-10-06.2",
    items: [
      { icon: Gavel, title: "whatsNew.r202610062.voiceModTitle", body: "whatsNew.r202610062.voiceModBody" },
      { icon: MessageCircleMore, title: "whatsNew.r202610062.typingTitle", body: "whatsNew.r202610062.typingBody" },
      { icon: Palette, title: "whatsNew.r202610062.callsTitle", body: "whatsNew.r202610062.callsBody" },
      { icon: Volume2, title: "whatsNew.r202610062.voiceBadgeTitle", body: "whatsNew.r202610062.voiceBadgeBody" },
      { icon: MonitorUp, title: "whatsNew.r202610062.shareTitle", body: "whatsNew.r202610062.shareBody" },
      { icon: RefreshCw, title: "whatsNew.r202610062.androidTitle", body: "whatsNew.r202610062.androidBody" },
    ],
  },
  {
    id: "2026-10-06",
    items: [
      { icon: MessagesSquare, title: "whatsNew.r20261006.caseChatsTitle", body: "whatsNew.r20261006.caseChatsBody" },
      { icon: MessageSquareText, title: "whatsNew.r20261006.threadCardsTitle", body: "whatsNew.r20261006.threadCardsBody" },
      { icon: KeyRound, title: "whatsNew.r20261006.historyTitle", body: "whatsNew.r20261006.historyBody" },
      { icon: Trash2, title: "whatsNew.r20261006.joinDeleteTitle", body: "whatsNew.r20261006.joinDeleteBody" },
      { icon: Volume2, title: "whatsNew.r20261006.songVolumeTitle", body: "whatsNew.r20261006.songVolumeBody" },
      { icon: ZoomIn, title: "whatsNew.r20261006.zoomTitle", body: "whatsNew.r20261006.zoomBody" },
      { icon: ShieldCheck, title: "whatsNew.r20261006.checksTitle", body: "whatsNew.r20261006.checksBody" },
    ],
  },
  {
    id: "2026-10-05",
    items: [
      { icon: MessageSquareText, title: "whatsNew.r20261005.threadsTitle", body: "whatsNew.r20261005.threadsBody" },
      { icon: MessagesSquare, title: "whatsNew.r20261005.forumsTitle", body: "whatsNew.r20261005.forumsBody" },
      { icon: Megaphone, title: "whatsNew.r20261005.announcementsTitle", body: "whatsNew.r20261005.announcementsBody" },
      { icon: Compass, title: "whatsNew.r20261005.discoveryTitle", body: "whatsNew.r20261005.discoveryBody" },
      { icon: ShieldCheck, title: "whatsNew.r20261005.automodTitle", body: "whatsNew.r20261005.automodBody" },
      { icon: Users, title: "whatsNew.r20261005.membersTitle", body: "whatsNew.r20261005.membersBody" },
      { icon: IdCard, title: "whatsNew.r20261005.profilesTitle", body: "whatsNew.r20261005.profilesBody" },
      { icon: ImagePlay, title: "whatsNew.r20261005.movingTitle", body: "whatsNew.r20261005.movingBody" },
      { icon: Sparkles, title: "whatsNew.r20261005.rolesTitle", body: "whatsNew.r20261005.rolesBody" },
      { icon: Type, title: "whatsNew.r20261005.topicsTitle", body: "whatsNew.r20261005.topicsBody" },
      { icon: Pause, title: "whatsNew.r20261005.invitesTitle", body: "whatsNew.r20261005.invitesBody" },
      { icon: ScrollText, title: "whatsNew.r20261005.auditTitle", body: "whatsNew.r20261005.auditBody" },
    ],
  },
  {
    id: "2026-10-04",
    items: [
      { icon: Smile, title: "whatsNew.r20261004.statusEmojiTitle", body: "whatsNew.r20261004.statusEmojiBody" },
      { icon: MessageSquareText, title: "whatsNew.r20261004.wholeStatusTitle", body: "whatsNew.r20261004.wholeStatusBody" },
      { icon: Sticker, title: "whatsNew.r20261004.packCardsTitle", body: "whatsNew.r20261004.packCardsBody" },
    ],
  },
  {
    id: "2026-10-02.8",
    items: [
      { icon: Link, title: "whatsNew.r202610028.linksTitle", body: "whatsNew.r202610028.linksBody" },
      { icon: Smartphone, title: "whatsNew.r202610028.androidTitle", body: "whatsNew.r202610028.androidBody" },
      { icon: Reply, title: "whatsNew.r202610028.swipeTitle", body: "whatsNew.r202610028.swipeBody" },
    ],
  },
  {
    id: "2026-10-02.7",
    items: [{ icon: Play, title: "whatsNew.r202610027.previewsTitle", body: "whatsNew.r202610027.previewsBody" }],
  },
  {
    id: "2026-10-02.6",
    items: [{ icon: Music, title: "whatsNew.r202610026.musicTitle", body: "whatsNew.r202610026.musicBody" }],
  },
  {
    id: "2026-10-02.5",
    items: [
      { icon: MousePointerClick, title: "whatsNew.r202610025.popoutTitle", body: "whatsNew.r202610025.popoutBody" },
      { icon: UserCog, title: "whatsNew.r202610025.rolesTitle", body: "whatsNew.r202610025.rolesBody" },
    ],
  },
  {
    id: "2026-10-02.4",
    items: [
      { icon: Paintbrush, title: "whatsNew.r202610024.designerTitle", body: "whatsNew.r202610024.designerBody" },
      { icon: IdCard, title: "whatsNew.r202610024.cardTitle", body: "whatsNew.r202610024.cardBody" },
      { icon: ImagePlus, title: "whatsNew.r202610024.typingTitle", body: "whatsNew.r202610024.typingBody" },
      { icon: Tag, title: "whatsNew.r202610024.tagsTitle", body: "whatsNew.r202610024.tagsBody" },
      { icon: RemoveFormatting, title: "whatsNew.r202610024.zalgoTitle", body: "whatsNew.r202610024.zalgoBody" },
    ],
  },
  {
    id: "2026-10-02.3",
    items: [
      { icon: LayoutGrid, title: "whatsNew.r202610023.widgetsTitle", body: "whatsNew.r202610023.widgetsBody" },
      { icon: PanelsTopLeft, title: "whatsNew.r202610023.profileTitle", body: "whatsNew.r202610023.profileBody" },
      { icon: Tag, title: "whatsNew.r202610023.tagsTitle", body: "whatsNew.r202610023.tagsBody" },
      { icon: MessageCircleMore, title: "whatsNew.r202610023.typingTitle", body: "whatsNew.r202610023.typingBody" },
      { icon: Keyboard, title: "whatsNew.r202610023.keybindsTitle", body: "whatsNew.r202610023.keybindsBody" },
      { icon: Gamepad2, title: "whatsNew.r202610023.activityTitle", body: "whatsNew.r202610023.activityBody" },
      { icon: FolderPlus, title: "whatsNew.r202610023.railTitle", body: "whatsNew.r202610023.railBody" },
    ],
  },
  {
    id: "2026-10-02.2",
    items: [
      { icon: Clapperboard, title: "whatsNew.r202610022.streamTitle", body: "whatsNew.r202610022.streamBody" },
      { icon: BellRing, title: "whatsNew.r202610022.friendSoundTitle", body: "whatsNew.r202610022.friendSoundBody" },
    ],
  },
  {
    id: "2026-10-02",
    items: [
      { icon: AudioLines, title: "whatsNew.r20261002.noiseTitle", body: "whatsNew.r20261002.noiseBody" },
      { icon: SlidersHorizontal, title: "whatsNew.r20261002.sensitivityTitle", body: "whatsNew.r20261002.sensitivityBody" },
      { icon: Link, title: "whatsNew.r20261002.profileTitle", body: "whatsNew.r20261002.profileBody" },
      { icon: MessagesSquare, title: "whatsNew.r20261002.orderTitle", body: "whatsNew.r20261002.orderBody" },
      { icon: Sticker, title: "whatsNew.r20261002.expressionsTitle", body: "whatsNew.r20261002.expressionsBody" },
    ],
  },
  {
    id: "2026-10-01.2",
    items: [
      { icon: Globe, title: "whatsNew.r202610012.moveTitle", body: "whatsNew.r202610012.moveBody" },
      { icon: NotebookPen, title: "whatsNew.r202610012.notesTitle", body: "whatsNew.r202610012.notesBody" },
      { icon: AudioLines, title: "whatsNew.r202610012.soundsTitle", body: "whatsNew.r202610012.soundsBody" },
      { icon: PanelBottom, title: "whatsNew.r202610012.dockTitle", body: "whatsNew.r202610012.dockBody" },
    ],
  },
  {
    id: "2026-10-01",
    items: [
      { icon: TvMinimalPlay, title: "whatsNew.r20261001.youtubeTitle", body: "whatsNew.r20261001.youtubeBody" },
      { icon: CircleX, title: "whatsNew.r20261001.removeTitle", body: "whatsNew.r20261001.removeBody" },
      { icon: Smartphone, title: "whatsNew.r20261001.phoneTitle", body: "whatsNew.r20261001.phoneBody" },
      { icon: ImagePlus, title: "whatsNew.r20261001.casesTitle", body: "whatsNew.r20261001.casesBody" },
      { icon: ServerCog, title: "whatsNew.r20261001.serversTitle", body: "whatsNew.r20261001.serversBody" },
    ],
  },
  {
    id: "2026-09-30.3",
    items: [
      { icon: NotebookPen, title: "whatsNew.r202609303.notesTitle", body: "whatsNew.r202609303.notesBody" },
      { icon: UsersRound, title: "whatsNew.r202609303.liveTitle", body: "whatsNew.r202609303.liveBody" },
      { icon: Newspaper, title: "whatsNew.r202609303.postsTitle", body: "whatsNew.r202609303.postsBody" },
      { icon: Type, title: "whatsNew.r202609303.fontTitle", body: "whatsNew.r202609303.fontBody" },
      { icon: Search, title: "whatsNew.r202609303.settingsTitle", body: "whatsNew.r202609303.settingsBody" },
      { icon: MousePointerClick, title: "whatsNew.r202609303.pickTitle", body: "whatsNew.r202609303.pickBody" },
      { icon: Smartphone, title: "whatsNew.r202609303.phoneTitle", body: "whatsNew.r202609303.phoneBody" },
      { icon: BadgeCheck, title: "whatsNew.r202609303.rolesTitle", body: "whatsNew.r202609303.rolesBody" },
    ],
  },
  {
    id: "2026-09-30.2",
    items: [
      { icon: Images, title: "whatsNew.r202609302.picturesTitle", body: "whatsNew.r202609302.picturesBody" },
      { icon: CloudUpload, title: "whatsNew.r202609302.uploadsTitle", body: "whatsNew.r202609302.uploadsBody" },
    ],
  },
  {
    id: "2026-09-30",
    items: [
      { icon: Sparkles, title: "whatsNew.r20260930.lookTitle", body: "whatsNew.r20260930.lookBody" },
      { icon: IdCard, title: "whatsNew.r20260930.profilesTitle", body: "whatsNew.r20260930.profilesBody" },
      { icon: Music, title: "whatsNew.r20260930.spotifyTitle", body: "whatsNew.r20260930.spotifyBody" },
    ],
  },
  {
    id: "2026-09-29.2",
    items: [
      { icon: AudioLines, title: "whatsNew.r202609292.callsTitle", body: "whatsNew.r202609292.callsBody" },
      { icon: Sparkles, title: "whatsNew.r202609292.emojiTitle", body: "whatsNew.r202609292.emojiBody" },
      { icon: Pin, title: "whatsNew.r202609292.pinsTitle", body: "whatsNew.r202609292.pinsBody" },
      { icon: Palette, title: "whatsNew.r202609292.themesTitle", body: "whatsNew.r202609292.themesBody" },
    ],
  },
  {
    id: "2026-09-29",
    items: [
      { icon: Headphones, title: "whatsNew.r20260929.discordTitle", body: "whatsNew.r20260929.discordBody" },
      { icon: SlidersHorizontal, title: "whatsNew.r20260929.activityTitle", body: "whatsNew.r20260929.activityBody" },
      { icon: House, title: "whatsNew.r20260929.homeTitle", body: "whatsNew.r20260929.homeBody" },
      { icon: Smartphone, title: "whatsNew.r20260929.phoneTitle", body: "whatsNew.r20260929.phoneBody" },
    ],
  },
  {
    id: "2026-09-27.2",
    items: [
      { icon: PanelsTopLeft, title: "whatsNew.r202609272.layoutTitle", body: "whatsNew.r202609272.layoutBody" },
      { icon: MousePointerClick, title: "whatsNew.r202609272.textMenuTitle", body: "whatsNew.r202609272.textMenuBody" },
      { icon: SpellCheck, title: "whatsNew.r202609272.spellingTitle", body: "whatsNew.r202609272.spellingBody" },
      { icon: Smile, title: "whatsNew.r202609272.emojiTitle", body: "whatsNew.r202609272.emojiBody" },
      { icon: Keyboard, title: "whatsNew.r202609272.typingTitle", body: "whatsNew.r202609272.typingBody" },
      { icon: Reply, title: "whatsNew.r202609272.repliesTitle", body: "whatsNew.r202609272.repliesBody" },
      { icon: Eye, title: "whatsNew.r202609272.sendPreviewTitle", body: "whatsNew.r202609272.sendPreviewBody" },
      { icon: SendHorizontal, title: "whatsNew.r202609272.sendButtonTitle", body: "whatsNew.r202609272.sendButtonBody" },
      { icon: UserPen, title: "whatsNew.r202609272.aboutMeTitle", body: "whatsNew.r202609272.aboutMeBody" },
      { icon: LayoutGrid, title: "whatsNew.r202609272.gridTitle", body: "whatsNew.r202609272.gridBody" },
      { icon: ClipboardCopy, title: "whatsNew.r202609272.copyTitle", body: "whatsNew.r202609272.copyBody" },
      { icon: Newspaper, title: "whatsNew.r202609272.previewsTitle", body: "whatsNew.r202609272.previewsBody" },
    ],
  },
  {
    id: "2026-09-27",
    items: [
      { icon: Vote, title: "whatsNew.r20260927.pollsTitle", body: "whatsNew.r20260927.pollsBody" },
      { icon: UsersRound, title: "whatsNew.r20260927.groupsTitle", body: "whatsNew.r20260927.groupsBody" },
      { icon: StickyNote, title: "whatsNew.r20260927.notesTitle", body: "whatsNew.r20260927.notesBody" },
      { icon: MapPinOff, title: "whatsNew.r20260927.metadataTitle", body: "whatsNew.r20260927.metadataBody" },
    ],
  },
  {
    id: "2026-09-26.4",
    items: [{ icon: Webhook, title: "whatsNew.r202609264.webhooksTitle", body: "whatsNew.r202609264.webhooksBody" }],
  },
  {
    id: "2026-09-26.3",
    items: [{ icon: Sticker, title: "whatsNew.r202609263.stickersTitle", body: "whatsNew.r202609263.stickersBody" }],
  },
  {
    id: "2026-09-26.2",
    items: [
      { icon: ShieldCheck, title: "whatsNew.r202609262.dmPrivacyTitle", body: "whatsNew.r202609262.dmPrivacyBody" },
      { icon: MessageSquareDashed, title: "whatsNew.r202609262.quietDMTitle", body: "whatsNew.r202609262.quietDMBody" },
    ],
  },
  {
    id: "2026-09-26",
    items: [
      { icon: Search, title: "whatsNew.r20260926.searchTitle", body: "whatsNew.r20260926.searchBody" },
      { icon: Type, title: "whatsNew.r20260926.formattingTitle", body: "whatsNew.r20260926.formattingBody" },
      { icon: Palette, title: "whatsNew.r20260926.themesTitle", body: "whatsNew.r20260926.themesBody" },
      { icon: FolderPlus, title: "whatsNew.r20260926.foldersTitle", body: "whatsNew.r20260926.foldersBody" },
      { icon: Pin, title: "whatsNew.r20260926.pinsTitle", body: "whatsNew.r20260926.pinsBody" },
      { icon: Users, title: "whatsNew.r20260926.dmGroupsTitle", body: "whatsNew.r20260926.dmGroupsBody" },
      { icon: Bug, title: "whatsNew.r20260926.bugsTitle", body: "whatsNew.r20260926.bugsBody" },
      { icon: Puzzle, title: "whatsNew.r20260926.extendTitle", body: "whatsNew.r20260926.extendBody" },
      { icon: Link, title: "whatsNew.r20260926.invitesTitle", body: "whatsNew.r20260926.invitesBody" },
      { icon: Shapes, title: "whatsNew.r20260926.channelIconsTitle", body: "whatsNew.r20260926.channelIconsBody" },
      { icon: EyeOff, title: "whatsNew.r20260926.blockTitle", body: "whatsNew.r20260926.blockBody" },
      { icon: UserCheck, title: "whatsNew.r20260926.friendsTitle", body: "whatsNew.r20260926.friendsBody" },
      { icon: MessageSquarePlus, title: "whatsNew.r20260926.soloGroupTitle", body: "whatsNew.r20260926.soloGroupBody" },
      { icon: BadgeCheck, title: "whatsNew.r20260926.nexaTitle", body: "whatsNew.r20260926.nexaBody" },
      { icon: RefreshCw, title: "whatsNew.r20260926.updatesTitle", body: "whatsNew.r20260926.updatesBody" },
    ],
  },
];

/**
 * The most releases the pop-up lists at once. Someone back after a long time
 * away reads about the newest; Settings, About has every one.
 */
export const MAX_SHOWN = 6;

const RELEASE_ID = /^(\d{4})-(\d{2})-(\d{2})(?:\.(\d+))?$/;

/** A release id as its day (UTC midnight) and its number that day, or null for anything else. */
export function parseRelease(id: string): { day: number; n: number } | null {
  const m = RELEASE_ID.exec(id);
  if (!m) return null;
  return { day: Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])), n: m[4] ? Number(m[4]) : 1 };
}

/**
 * Orders two release ids, older first. An id this build does not know how to
 * read counts as older than every real one, so a stray value can never hide
 * a release.
 */
export function compareReleases(a: string, b: string): number {
  const x = parseRelease(a);
  const y = parseRelease(b);
  if (!x || !y) return (x ? 1 : 0) - (y ? 1 : 0);
  return x.day - y.day || x.n - y.n;
}

/** The later of two seen markers, so a device on an older build never moves it back. */
export function laterRelease(a: string | null, b: string | null): string | null {
  if (!a || !parseRelease(a)) return b && parseRelease(b) ? b : a;
  if (!b || !parseRelease(b)) return a;
  return compareReleases(a, b) >= 0 ? a : b;
}

/** When this build was made, from its id (a base-36 timestamp); Infinity where there is none. */
export function builtAt(): number {
  const id = typeof __BUILD_ID__ === "string" ? __BUILD_ID__ : "";
  const at = parseInt(id, 36);
  return Number.isFinite(at) && at > 0 ? at : Infinity;
}

/**
 * The moment a release reached people: its day, or when this build was made
 * if that is earlier, since nobody can have had a release before it was
 * built. An account made after that never saw what came before and has
 * nothing to catch up on.
 */
export function releaseStart(id: string, built = builtAt()): number {
  const p = parseRelease(id);
  return Math.min(p ? p.day : -Infinity, built);
}

/**
 * The releases an account has not been shown, newest first: those after the
 * last one it saw, and only those that came out after it was made.
 */
export function unseenReleases(seen: string | null, accountCreated: number, releases: Release[] = RELEASES, built = builtAt()): Release[] {
  return releases.filter((r) => (seen === null || compareReleases(r.id, seen) > 0) && accountCreated < releaseStart(r.id, built)).slice(0, MAX_SHOWN);
}

/** A release's day, written the way the reader's language writes a date. */
export function releaseDate(id: string, locale: string): string {
  const p = parseRelease(id);
  if (!p) return id;
  try {
    return new Intl.DateTimeFormat(locale, { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" }).format(p.day);
  } catch {
    return id.slice(0, 10);
  }
}
