# Text input source ledger

This is the source inventory for the October 28, 2026 layout audit. It supplements
[the display audit](UI_TEXT_AUDIT.md). It records discovered input sites, including
exclusions, rather than treating a list of rendering components as an input audit.

[Web source catalog](UI_TEXT_INPUTS.tsv) contains 298 JSX entry points from
non-test `web/src` TSX: native inputs/textareas, `TextInput`, `TextArea`,
`FormattedText`, `InlineText`, `InlineAbout`, `AboutMeEditor`, `CodeEditor` and
editable elements. [Android source catalog](UI_TEXT_INPUTS_ANDROID.tsv) contains
163 `BasicTextField`/`NxTextField` call sites, excluding declarations and imports.
Counts include shared primitives, non-text controls and private fields; they are
not counts of public text fields. Source lines are a snapshot, not stable IDs.

Both catalogs classify each discovered entry. They do not prove that every
consumer has been exercised at runtime. The browser suite exercises common
components and representative layouts; Android fixtures exercise common native
renderers. Actual group and invite flows were also checked. Future fields and
integration schemas must extend this inventory and the relevant regression case.

## Dispositions and display traces

Paths below are relative to `web/src/components` or
`android/app/src/main/java/fish/nexa/ui` unless stated otherwise. Each disposition
in the catalogs refers to one of these traces. Native clients consume the same
stored/protocol fields; their common `Txt`, `PlainName`, `PersonLine`, `PersonText`
and markdown boundaries constrain ink inside Compose constraints.

| Disposition | Editable fields and additional sources | Where their text appears |
| --- | --- | --- |
| `identity` | Registration/account username and display name; profile display name/pronouns; server nickname/pronouns; guest-call name (`web/src/pages/GuestCallPage.tsx`). | `common/PersonName`, profile identity rows, author/member/friend/search rows, mentions, call tiles/participants, account dock and translated person text. Pronouns use existing clipped identity rows. Guest participants use the same identity/voice boundaries. |
| `profile` | About Me/global and server bio; custom status; widget game/music/custom titles, notes, tags, statistic labels/values, link labels/URLs; custom game/music names selected or entered in widget search. | `profile/ProfileCard`, `profile/Widgets`, `activity/StatusLine`, activity cards, status bubbles, `ScrollingText`; profile previews reuse the same renderers. Markdown bodies preserve prose; titles/tags/links/stats have compact bounds. |
| `server` | Server/template names and description; categories/channels/voice names and topic/guidelines; role names; forum tag name/emoji; Discovery tags; server-tag text; the vanity link code (a-z, 0-9 and hyphens, at most 32); Automod rule name, word/regex/allow/domain/extension lists and custom block message. | Server rail/sidebar/header/search/mentions, `invite/InviteView`, Discovery cards/details; `server/RoleChips`, role previews and pickers; forum tags and thread starts; imported template preview; Automod rule list/editor, notices, alerts and audit logs. Rules/configuration remain editable text; shared labels and metadata are bounded. |
| `conversation` | Group names; subchat names; thread names; forum post titles and formatted post body. | DM/sidebar lists, chat header/start, parent/thread links, search, member panels, invite card/dialog/page and forum cards. Titles clamp; post body uses markdown. |
| `expression` | Emoji rename; sticker/pack name, description and tags; imported public/Discord pack metadata; emoji/glyph selection. | Expression picker headers/tiles, emoji detail popout, sticker labels/search, pack cards/dialog/page/editor and moderation sheets. Pack names/headers clamp or ellipsize. Descriptions wrap; initials clip; unsupported Twemoji fallback is icon-sized even for multi-pictograph sequences. |
| `message` | Composer/edit/forward text; formatted links, labels and code language; profile quick-message box; friend-request note. | `chat/MessageItem`, markdown nodes, reply/forward/pin/search previews and transcriptions. `friends/RequestNote` clamps the incoming note (two lines, three on narrow containers); native incoming-request note is similarly bounded. Code/table overflow stays local. |
| `poll` | Poll question and each answer; equivalent bot/webhook poll data. | Webhook poll cards, answer rows and voter dialog; Android poll views. Cards wrap full content; voter-dialog heading is compact. |
| `note` | Shared-note title; body edited through CRDT/format surfaces, not just the title textarea. | Note list/header, reader/link page/card, history and live cursors. Bodies use markdown; titles and remote collaborator labels are bounded. |
| `app` | Application/webhook name and description; tags; bot username; terms/privacy/install/interaction/event/redirect URLs; bot command argument and app-modal response text. | Developer portal, authorized-app list, install/consent completion, `apps/ReadingGlass`, app link cards; command slots/autocomplete; outgoing integration responses can return through message/webhook rendering. URLs retain their protocol validation; visible labels use bounded text children. |
| `reports` | Description (4000), optional evidence for non-message reports (16000), internal review note (2000), and public removal reason (1000). | Web Report Content and Reports staff workspace, Android report sheet and Reports tab. User text wraps within local bounds; report list previews clamp. Message reports automatically share the full message and its files; no part selection. Snapshots and private files are staff-only; receipts omit internal notes. |
| `staff` | Bug subject/description/context; case/report messages and moderation reasons; announcement title/body; member/bulk moderation reason; public-pack approval/lift reason. | Bug feed/card/detail, Nexa/case thread, moderation sheets/lists, server audit logs, announcements and recipient notices. Titles/feed metadata clamp; descriptions/reasons/messages wrap with local ink boundaries. Restricted staff visibility still counts as visibility to another person. |
| `typing` | Typing-style custom words. | Typing bar, editor preview and typing-style popout. The line remains one line; expanded preview/popout allows three. |
| `theme` | Theme name and imported theme JSON/file (shared by export, not a public database field). | Theme gallery/builder/import preview. Gallery names clamp; native gallery names already have line limits. Theme colors use validated color values. |
| `local` | Search/filter/invite lookup and confirmation text; private friend nickname/note; rail folders and personal DM folder names; passkey/device names; sound-pack names; local custom CSS/plugins/plugin settings; administrative key/legal lookup. | These inputs do not publish their text to other users. They remain subject to common local title/name/toast bounds. A local search result can display public text, which is covered by the corresponding public disposition. Plugin-provided labels are additionally covered by shared modal/field/menu/button bounds; executing an installed plugin is outside this text-layout audit. |
| `credential` | Login username, passwords, recovery/registration keys, MFA codes and local lock PIN/passcode. | Authentication only. A login username does not create a public identity; registration/change-username fields are classified as `identity`. Credentials are excluded from the public-text trace. |
| `readonly` | Generated group/server/guest invite links. | Read-only values, not editable public text. The destination name is separately covered by `server`/`conversation`. |
| `nontext` | Checkboxes, radio buttons, ranges, numeric/color values and file selectors. | The selected scalar itself is not arbitrary text. Labels/options can come from integrations and use shared field/choice bounds. File *metadata* is covered below even though the file control is nontext. |
| `primitive` | Shared input/format/prompt wrappers. | These take their disposition from their callers. The dynamic/editor callers below are part of the inventory; the wrapper is not counted as a public field by itself. |

## Inputs without a simple text control

- Web `composer/format/surface.ts` sets `contentEditable` on its root and table
  cells. Composer, `common/FormattedText`, About Me and shared-note CRDT editing
  serialize to their message/profile/server/note field. Android `FormatPage`,
  `FormatEditor` and `FormattedTextField` mirror those fields. `FormattedTextField`
  callers are forum post body, server description and channel topic/guidelines.
  Inline profile wrappers bind display name, nickname, pronouns and About Me.
- Dynamic web prompts: `server/pages/EmojiPage` (emoji name),
  `ServerStickersPage` (pack name), `server/PackEditor` (sticker name/tags),
  `server/memberBulk` (reason), `moderation/PackPage` (approval reason),
  `CaseWorkspace` (lift reason). Android `server/EmojiPage`, `server/StickersPage`,
  `chat/Members`, `moderation/Expressions` and `Cases` have the equivalent prompts.
  `profile/ProfileCard` friend nicknames and sidebar/shell DM folder prompts are
  private. Web sound-pack create/rename prompts are private account settings.
- Uploaded/forwarded/imported filenames and attachment metadata are arbitrary
  strings even though file controls cannot type them. File cards, note files,
  upload rows, download/media views, report attachments and search use bounded
  filename rows. Public/Discord pack imports and server templates additionally
  supply all their nested names/tags/descriptions; their previews are included.
- Bots and remote services supply webhook author/title/description/footer/fields,
  buttons/select options, modal labels/hints/options, command names/descriptions,
  application names, link/post embed metadata, poll content and activity/game/music
  text without a local editor. `chat/webhook`, `apps`, `composer/CommandMode`,
  `chat/Embeds`, `chat/PostEmbed`, `activity` and their native counterparts are
  rendering boundaries for these sources. Payload/schema limits alone are not
  considered sufficient containment.
- Clipboard/drop, voice transcription, mentions/autocomplete and collaborative
  operations feed the same stored fields. Hidden clipboard fallback textareas
  (`lib/clipboard`, `lib/copyText`) and the `useAutosize` measuring textarea do not
  publish text or create a second display path.

## Follow-up regression evidence

The expanded fixture reproduced additional failures before the follow-up fixes:
16 valid pictographs occupied a 320px emoji slot; imported template titles/roles
and typing previews exceeded their line budgets; wide compact metadata exceeded
hundreds of pixels in height; a long command-option name pushed its removal
control out of the container. The browser runner now covers these cases in both
directions at 1366, 411 and 320px widths. Normal labels and all eleven name effects
remain in the same suite. This is evidence for the enumerated boundaries, not a
promise that no future browser/font/content combination can expose another bug.
