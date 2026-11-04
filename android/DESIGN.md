# Nexa for Android: design plan and the rules it is built by

Read this before any UI work in `android/`, and again after any context reset. It is the
frontend-design skill's process applied to this app, plus the owner's standing rules. Nothing on
screen ships without passing section 6.

## 1. The brief

- **Subject.** Nexa: private chat with all the fun left in. End-to-end encrypted servers, direct
  and group messages, calls with screen share, emoji and sticker packs anyone makes, notes written
  together, profiles with widgets.
- **Audience.** People who already use Nexa in a browser or the desktop app, now on an Android
  phone: often one-handed, on the move, in a call.
- **Primary job.** Read and answer conversations fast; join and run calls.
- **The owner's words that pin the direction.** "Look and function as closely to the web app as
  possible but be completely native." "As similar to the web version as possible WITHOUT
  EMBEDDING THE WEB VERSION." "The best mobile UI", with gestures and extra improvements for phones.
  Standing direction: a deep, non-flat UI with its own identity (floating panels on a darker canvas,
  lit top edges, theme-tinted shadows, the accent used as light), Wix Madefor, Title Case labels, no
  stock card grids, no gradient lines. "Match the theming of the rest of the site exactly" overrides
  the skill's freedom over palette and type.

## 2. Tokens (the only source of colour, size and motion)

Every value comes from `web/src/styles/tokens.css` (and `lib/theme.ts`, `lib/look/*` for accents,
presets and custom themes) through `ui/theme/Tokens.kt`. No hardcoded colour anywhere else.

**Colour, dark (the default).** Canvas `bg-base` mixed 70% with black (≈ #13101A) with a 13% accent
glow; panels `bg-raised` #221D2B; surface #2B2636; floating #282331; input #15121C; text #F0EDF7,
strong #FFFFFF, subtle #D0CADB, muted #B0A8BF, channel #A39BB3, placeholder #928BA5; icon #BCB4CB;
accent `brand` #8000FF (hover #6A00D6, active #5600AC), focus ring #B77AFF, `text-brand` #C6A4FF;
mention fill rgba(128,0,255,.24) with #E0D0FF text; link #86B8F7; danger #D1343B (as text #FF8A8E);
positive #2D8550; warning #FDB833; presence online #46B56E, idle #F0B232, dnd #FF7075,
offline #A39CB1. Light, Ash and Onyx and every preset come from the same file; a custom accent is
derived as `lib/theme.ts` derives it.

**Depth.** Light comes from above. A 1 px top edge of white at 6% (`edge-light`); shadows tinted
with `shade` (bg-base mixed 30% with black), never neutral grey: `shadow-low`, `-medium`, `-high`,
`-panel` as in tokens.css; `lift` = top edge + medium shadow. The accent as light: `glow-brand`
(1 px ring of the accent at 55% + a 22 px glow at 75%) on pressed and chosen things; `grad-brand`
(accent lightened 16% at the top, the accent at 55%, darkened 8% at the bottom) on primary fills;
`sheen` (white 3.5% fading out by 60%) on raised panels.

**Shape.** Radii xs 6, sm 10, md 14, lg 20, xl 28, pill 999. Server icons are 48 dp squircles that
round further when chosen; avatars are circles (xs 16, sm 24, md 32, lg 40, xl 80) with the status
dot cut out of them (`glyph-cut`).

**Type.** Wix Madefor Text for everything people read and tap; Wix Madefor Display for names,
titles and headings; JetBrains Mono for code. The Classic font option is Onest with Mona Sans at
115% width. Emoji are always Twemoji artwork (the web's sprites), never the system's.

**Motion.** ease-out (0.22, 1, 0.36, 1), ease-in-out (0.65, 0, 0.35, 1), spring
(0.34, 1.4, 0.64, 1); 120 / 180 / 300 ms. Off when the system animator scale is 0 or the app's
Reduce Motion setting is on.

## 3. Layout on a phone (the web's own structure, made native)

```
Chat                                   Drawer (edge swipe right, or the menu button)
┌───────────────────────────────┐      ┌──────┬────────────────────────┐
│ ☰  # general          ⌕   ⋮   │      │ (◆)  │ Alice's Server       ⌄ │  rail on the canvas,
├───────────────────────────────┤      │ ───  ├────────────────────────┤  sidebar floats
│ ◉ Alice  Today at 12:04       │      │ (A)  │ ⌄ Text Channels        │  beside it
│   hello **world**             │      │ (+)  │ ▌# general  (lifted)   │
│   ┌ link card ─────────┐      │      │      │ ⌄ Voice Channels       │
│   └────────────────────┘      │      │      │   🔊 General           │
│   👍 2  ❤ 1  (+)              │      │      │      ◉ Bob  ◉ Alice    │
│                               │      ├──────┴────────────────────────┤
│ ╭───────────────────────────╮ │      │ ◉ Bob · Online                │  user panel, floating
│ │ ⊕  Message #general   ˄ ➤ │ │      │   🎙   🎧   🎮   ⚙           │
│ ╰───────────────────────────╯ │      └───────────────────────────────┘
└───────────────────────────────┘
Members: edge swipe left. Long-press a message: a sheet with a quick-reaction row, then actions.
Swipe a message right to reply. Calls: a full stage with the controls in a floating dock.
```

Alignment: text left-aligned (mirrored in Arabic); titles start-aligned in headers, centred only in
empty states and sign-in, as the web does.

Overlays: a dialog or sheet dims what is behind it and blurs it by 6 dp as its scrim shows
(Modal.module.css), an open drawer blurs the page by 2 dp, and a menu only dims, as on the web.
The blur needs Android 12; older phones only dim.

## 4. Principles

1. **One product, two surfaces.** The same tokens, type, words (the web's own translation
   catalogue) and structure. Someone moving from browser to phone should find no seam.
2. **Depth from light, not lines.** Floating panels on the darker canvas, a lit top edge, a tinted
   shadow. Dividers only where they carry structure.
3. **The accent is light, not paint.** Glows on primary actions, the chosen row's lift, mentions,
   unread pills, speaking rings. One bold thing per screen; everything around it stays quiet.
4. **Native where the hand is.** Edge swipes for the drawers, swipe to reply, long-press sheets,
   pull down to dismiss, predictive back, haptics, the keyboard moving the composer with it,
   notifications you can answer from.
5. **Motion answers the person.** Opening, expanding, confirming. No ambient motion and no
   staggered fade-ins.

## 5. Review against the generic default (what changed and why)

The default "Android chat app" is Material 3 with wallpaper colours, Roboto, a top app bar, a bottom
tab bar (home, messages, notifications, you), a floating button for a new message, ripples, stock
switches and chips, and cards that all share one elevation. That reads as stock, and the bottom-tab
skeleton reads like the big chat apps, which the owner rejects. So:

- **No wallpaper colours.** Only Nexa's themes, presets and custom accent.
- **No bottom tab bar or floating button.** The web's phone structure (header and drawers) stays,
  with native gestures. Unread and mention badges live where the web shows them.
- **No stock Material components on screen.** Buttons, fields, toggles, sheets and menus are drawn
  from the tokens. Pressing is a soft tint and a slight press-in on tiles, not a ripple.
- **Wix Madefor everywhere.** No Roboto.
- **Theme-tinted shadows.** Never default black elevation.
- **None of the skill's generic tells.** No all-caps labels or eyebrows, no middle-dot meta strings
  the web does not use, no monospace data labels, no arrows appended to buttons, no numbered
  markers, no identical rounded cards, no gradient lines.

## 6. Rules that never lapse

From the frontend-design skill:
- Every new screen: plan (tokens, type, an ASCII layout, principles), review the plan against the
  generic default and say what changed, build, then screenshot it and critique it at phone size.
- Spend boldness in one place per screen; cut decoration that does not serve the person.
- Quality floor: 48 dp touch targets, TalkBack labels, visible focus, reduced motion respected,
  contrast as the web's tokens guarantee, right-to-left mirrored for Arabic.
- Words make the app easier to use: name things as people know them, active verbs, the same name
  for an action through its whole flow, errors that say what happened and how to fix it, empty
  screens that invite the next step.

From the owner (CLAUDE.md and standing feedback):
- Labels in Title Case (buttons, menus, tabs, titles, headings); toggles and checkboxes in sentence
  case; descriptions, hints, toasts and errors as short, plain sentences.
- No em dashes. Never describe the owner in the third person. Never compare Nexa to Discord.
- Never hardcode English: `t("key")` with keys from the web catalogue; new keys (`android.*`) go into
  all twelve locales with real translations, and `python3 tools/i18n_check.py` must pass.
- A major feature or noticeable change gets a What's New entry.
- Check every screen on the emulator in Dark, Light and one preset; look before calling it done.
- Compare every screen with the same screen of the web app at phone width (a headless Playwright
  screenshot of a scratch web build, same account and data) before it is done: it must at least
  look similar in layout, sizes, colours and words.
- An improvement made here for phones (a gesture, a better flow, a smarter default) goes to the web
  too, so the two stay consistent: list it in section 7 and port it.
- Motion answers what was done to one thing: a chosen tab's pill slides (`components/Glide.kt`), a
  chevron turns, what a category or expander brings back unfolds, a new message or reaction
  arrives, a sheet or toast leaves. Never fade or dim a whole page, list or pane when someone
  switches tabs, conversations, servers or settings pages: the owner saw it as the app blinking
  (2026-11-03). What replaces something just appears, or slides in already partly in view.

## 7. Phone improvements beyond the web (each one is brought to the web too)

| Improvement | On the web |
|---|---|
| Swipe a message against the reading direction to reply (a tick when armed) | Done: lib/swipeReply.ts, phones and tablets |
| Thread and Nexa headers keep their second line under the title instead of cutting it | Done: Nexa page, case and bug pages |
| A subchat's password dialog keeps the name already typed; a new subchat opens; deleting one only leaves it if you are in it | Done: SubchatPanel, SubchatModal |
| Share pictures and text into Nexa from any app | To do: a share target in the installed web app's manifest |
| Scan QR Code in the status sheet, to let another device in | Partly: a phone's own camera opens the code's link on the web; a scanner in the web app is still to do |
| Picture-in-picture when leaving the app mid-call | To do: the browser's picture-in-picture for the call's video |
| Answer from the notification | Not possible on the web (notifications cannot take text) |
| Pinch-to-zoom pictures that pull down to close | Already there (Lightbox) |
| Haptics on long-press, reactions and the push-to-talk key | Partly: long-press and swipe vibrate where the browser can |
| The composer riding the keyboard's own animation | Not possible on the web (the browser moves the page) |
| Long-press a member card on the Members page (Server Settings) to pick it | Done: ModMembers cards on phones |
| An X on an app's command being filled in, to leave it in one tap | Done: CommandMode (Close Command) |
| Tabs that do not fit on a phone (an app's five) slide sideways in their bar, not the whole page | Done: Designer.module.css `.scopeTabs` |
| While picking accounts in the staff panel, a tap anywhere on a card picks it | Done: UserGrid cards |
| Choose a save location for files and pictures | Done: lib/saveFile.ts uses a picker where supported and mobile file sharing, with the browser download flow elsewhere |
| Full-message reporting, the stepped report form, Your Reports and the one-page staff report with a return path from related staff pages | Done: ReportModal, ReportsTab, YourReportsPage and reportNavigation on the web |
| Dialogs, sheets, Settings and the picture viewer fade or slide away when closed instead of vanishing | Done: lib/leaveCopy.ts (Modal, SettingsLayer, Lightbox, toasts) |
| The picture viewer moves sideways between a message's pictures | Done: Lightbox's `data-step` slides the next one in from that side |

Report forms are steps, as on the web: the reasons as one panel of rows (glyph tinted by urgency, name, what it covers; a press goes on), then the details (the chosen reason with Change, an emergency note for urgent reasons, What Staff Will See, What Happened?, the sharing notice), then the receipt with Block and Your Reports. Message previews and staff evidence use MessageRow, with readonly polls and retained files supplied to the staff row. Only Something Else reports take screenshots.

Reports use 20 dp section spacing in the form and 18 dp on the staff page, 15 sp labels and notices, and 16 sp reading and input text. The staff page is one column in the order the web's narrow panel uses (said, content, account, reporter, decision, other reports, history); the decision's four outcomes sit two by two, as on the web at phone width.
