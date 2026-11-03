# Product gaps

What Nexa should do that it does not, and what would make it nicer to use. Three
clients: the React web app (`web/`), the Electron desktop app (`desktop/`) and the
native Android app (`android/`). Bugs, security, performance and visual design are
other people's files; this one is about the shape of the product.

Everything below was read out of the code. Where something is called missing, the
line that proves it is named, or the search that found nothing is described.

**One structural fact first, because it changes how the rest reads.** The desktop
app is not a separate client. It loads the live web app over https
(`desktop/src/main/config.ts:17`, `desktop/src/main/window.ts:129`); there is no
bundled copy of `web/dist` anywhere in `desktop/`. So the desktop column of the
matrix equals the web column for every in-app feature, and differs only where the
Electron shell adds or removes something. The real parity problem in this product
is Android against web, and it is much larger than the READMEs suggest.

---

## If you only do ten things

1. **Let Android set your own presence.** You cannot go idle, DND or invisible from
   the phone at all — the only gateway op the app ever sends is `voice_state`
   (`android/.../voice/VoiceSession.kt:329`). Small, high value, and it is the first
   thing anyone notices. → [G1](#g1)
2. **Let Android block someone.** `RelationshipsApi.block` exists at
   `android/.../net/Endpoints.kt:285` with zero call sites: you can unblock on the
   phone but never block. This is a safety feature with a one-screen fix. → [G2](#g2)
3. **Wire up server mute / deafen / move.** The server implements it
   (`PATCH /api/v1/servers/{id}/members/{uid}/voice`, `internal/api/voice_glue.go`),
   the web client has the typed binding (`web/src/api/voice.ts:49`), and **nothing
   calls it in any client**. Both READMEs claim the feature ships. → [G3](#g3)
4. **Make role mentions and `#channel` mentions authorable.** Both renderers draw
   them (`web/src/markdown/parse.ts:469,477`), roles carry `mentionable`
   (`web/src/components/server/pages/RolesPage.tsx:137`), the wire carries
   `mention_roles` end to end — and no client can produce one. Grep for a producer of
   `<@&` or `<#` across `web/src` and `android/` returns nothing. → [G4](#g4)
5. **Threads in server channels.** "Subchats" exist, but only inside DMs:
   `internal/api/subchat_handlers.go:153` refuses anything that is not a `dm` or
   `group_dm`. This is the biggest single Discord-shaped hole. → [G5](#g5)
6. **Finish the notification settings.** Per-channel level has a data model
   (`web/src/stores/settings.ts:78`) and no UI; `default_notifications` is settable
   per server and read by nobody (`web/src/lib/notify.ts:113`); there is no mute
   duration and no way to keep message text out of the OS notification. → [G6](#g6)
7. **Give the desktop app a global push-to-talk key.** `globalShortcut` is never
   imported in `desktop/src/`. PTT is a page-level `keydown`
   (`web/src/voice/session.ts:982`), so it does nothing while you are in the game you
   are talking about. → [G7](#g7)
8. **Give the desktop app a server field.** The app is hardwired to
   `https://chat.evilion.xyz` unless you set `EVILION_URL` before launch. The Android
   app already has the field (`android/.../ui/auth/AuthScreen.kt:306-314`). For a
   product whose first word is "self-hosted", this is the gap that stops anyone else
   adopting it. → [G8](#g8)
9. **Android search.** It scans only the open channel's already-decrypted messages,
   has no filters, and tapping a hit just closes the sheet without jumping
   (`android/.../ui/sheets/ChannelSheets.kt:198-254`). The web search it should match
   is 200 lines away in `web/src/lib/search.ts`. → [G9](#g9)
10. **Delete the three permissions that gate nothing.** `USE_SOUNDBOARD`,
    `PRIORITY_SPEAKER` and `CREATE_POLLS` are in both role editors
    (`web/src/lib/perms.ts:83,92,94`) and there is no soundboard, no priority
    speaker and no poll. Either hide them or build polls — the `polls` and
    `poll_votes` tables are already in the schema
    (`internal/db/migrations/0001_init.sql:328,335`). → [Q1](#q1) / [G10](#g10)

*And one more, because it is a footgun rather than a gap:* on Android, **Delete
Server** fires on the first tap with no confirmation
(`android/.../ui/server/ServerSettingsScreen.kt:172-184`), where the web client makes
you type the server's name. → [Q22](#q22)

---

## Parity matrix

**full** — the feature is there and complete. **partial** — some of it is there; the
note says which part. **missing** — nothing in that client does it. **n-a** — the
idea does not apply to that platform.

Desktop is the web app in an Electron shell, so unless a row says otherwise its cell
is the web cell. Rows where the shell genuinely changes things say so.

### Servers, categories, channels

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Create a server | full | full | full | `web/.../modals/CreateServerModal.tsx:78`; `android/.../ui/sheets/Misc.kt:245` |
| Join by invite code or link | full | full | full | `android/.../ui/invite/InviteScreen.kt:106` |
| Leave a server | full | full | full | `web/.../sidebar/serverMenu.ts:73-86`; `android/.../ui/server/ServerSettingsScreen.kt:191` |
| Delete a server | full | full | partial | web makes you type the server's name (`DeleteServerPage.tsx:41`); Android deletes on one tap with **no confirmation at all** (`ServerSettingsScreen.kt:172-184`) — see [Q22](#q22) |
| Transfer ownership | missing | missing | missing | `POST /servers/{id}/transfer` exists and audits as `server_transfer` (`AuditLogPage.tsx:20`); `servers.transfer` in `web/src/api/servers.ts` has no caller |
| Reorder the server rail | full | full | missing | web drags (`rail/Rail.tsx:104-114`); `SettingsStore.setServerOrder` (`android/.../data/SettingsStore.kt:194`) is never called — Android only reads the order web wrote |
| Server icon | full | full | missing | `web/.../server/pages/ServerOverviewPage.tsx:64,74`; no icon endpoint in `android/.../net/Endpoints.kt` |
| **Server banner** | missing | missing | missing | `PUT`/`DELETE /api/v1/servers/{id}/banner` are implemented, `banner_id` is on the model (`web/src/api/types.ts:95`) and on the invite preview (`:153`), and `serversApi.uploadBanner`/`deleteBanner` (`api/servers.ts:78,81`) have zero callers. Nothing sets it and nothing draws it — grep for `banner` in `InvitePage.tsx`, `components/server/` and `components/rail/` returns nothing |
| Server name, description, system channel, default notification level | full | full | partial | Android edits name + description only (`ServerSettingsScreen.kt:132-160`) |
| Server folders | missing | missing | missing | spec P2 (`docs/superpowers/specs/2026-09-11-chat-design.md:215`); no code anywhere |
| Server discovery / public directory | missing | missing | missing | no reference in any client |
| Create / rename / delete a category | full | full | missing | Android has no create-channel call at all |
| Collapse a category | full | full | full | shared via `collapsedCategories`; `android/.../ui/main/Sidebar.kt:182` |
| Reorder categories and channels | full | full | missing | web drag-and-drop; `channelPositions` unused on Android |
| Create / delete a channel | full | full | missing | `ChannelsApi.delete` and `createChannel` have no Android call sites |
| Rename a channel, set its topic | full | full | full | `android/.../ui/sheets/ChannelSheets.kt:275-311` |
| Slowmode, age restriction, voice user limit | full | full | missing | `web/.../server/ChannelSettingsModal.tsx:112-121`. Android *honours* slowmode (`Composer.kt:178`) but cannot set it |
| Private channels | full | full | partial | Android resolves and honours overwrites (`data/Perms.kt:122-156`) and draws the lock (`Sidebar.kt:221`) but cannot make one |
| Per-channel permission overwrites (tri-state editor) | full | full | missing | `web/.../server/PermissionGrid.tsx`; `putOverwrite`/`deleteOverwrite` unused on Android |
| Announcement / rules / stage channels | missing | missing | missing | spec P2/P3, never built |

### Threads, DMs, groups

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| **Threads inside a server channel** | **missing** | **missing** | **missing** | `internal/api/subchat_handlers.go:153` — "Subchats belong to a conversation, not a server channel." |
| Subchats inside a DM or group (this product's own idea) | full | full | missing | `web/.../chat/SubchatPanel.tsx`; `ChannelsApi.subchats`/`createSubchat` (`Endpoints.kt:137-140`) have no Android caller |
| Password-locked subchats | full | full | missing | `web/.../modals/SubchatModal.tsx`, `GET/POST /channels/{id}/lock|unlock` |
| Direct messages | full | full | full | |
| Group DMs: create | full | full | full | `android/.../ui/sheets/Misc.kt:199` |
| Group DMs: add / remove people | full | full | missing | `Endpoints.kt:119,121` unused |
| Group name and icon | full | full | partial | Android renames (it treats "no serverId" as permission, `ChannelSheets.kt:265`); no icon |
| Group invite link | full | full | missing | Android gates the invite row on `channel.serverId != null` (`ChannelSheets.kt:329`), so groups never show it |
| Group ownership transfer | full | full | missing | `web/.../chat/groupMemberMenu.ts:64` |
| Close a conversation / delete it for everyone | full | full | missing | `web/.../sidebar/dmMenu.ts:42,53`; no Android equivalent |

### People

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Friends list, requests, accept, decline, remove | full | full | full | `android/.../ui/friends/FriendsScreen.kt:83-177` |
| Add a friend by exact username | full | full | full | |
| **Search for people to add** | missing | missing | missing | `GET /users/search` exists and `users.search` in `web/src/api/users.ts:46` has no caller — you must already know the username |
| Friend nicknames | full | full | partial | Android displays them (`data/AppState.kt:184`); `RelationshipsApi.nickname` (`Endpoints.kt:282`) is never called |
| Private notes on a person | full | full | missing | web keeps them in the encrypted settings (`ProfileCard.tsx:189`); Android never reads `userNotes` |
| Block | full | full | missing | `RelationshipsApi.block` (`Endpoints.kt:285`) has zero call sites |
| Unblock | full | full | full | `android/.../ui/settings/SettingsScreen.kt:466` |
| See other people's presence | full | full | full | |
| **Set your own presence** (online / idle / dnd / invisible) | full | full | **missing** | `web/.../shell/UserPanel.tsx:63`; on Android nothing ever patches `presencePref` and `Gateway.send` is only called for `voice_state` |
| Auto-idle after inactivity | missing | missing | missing | no idle timer in `web/src/stores/presence.ts` or the gateway |
| Custom status | full | full | partial | web sets text, emoji and an expiry (`CustomStatusModal.tsx:39-47`); Android sets text only (`SettingsScreen.kt:300`) |
| Rich presence (games, music) | full (shows) | full (produces) | missing | desktop runs a Discord-RPC **server** (`desktop/src/main/rpc/`) and relays to the real Discord; `Presence.activities` is parsed on Android (`net/Models.kt:421`) and rendered nowhere |
| View someone's profile | full | full | full | `android/.../ui/sheets/ProfileSheet.kt:88-157` is genuinely rich |
| Edit your profile | full | full | partial | Android has no accent colour, no avatar/banner removal |
| Mutual friends / mutual servers | full | full | missing | Android never calls `UsersApi.profile` |
| Per-server nickname ("server profile") | full | full | missing | `web/.../modals/ServerProfileModal.tsx`; `ServersApi.patchMember` unused on Android |
| Per-server avatar | missing | missing | missing | not in the member model (`internal/store/models.go`) |

### Roles, permissions, moderation

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Permission model and enforcement | full | full | full | `android/.../data/Perms.kt:13-178` mirrors `internal/perms` bit for bit |
| Create / rename / delete a role | full | full | partial | Android: name and permission grid only (`ServerSettingsScreen.kt:294-321`) |
| Role colour, hoist, mentionable | full | full | missing | Android shows the colour, cannot set it |
| Reorder roles (hierarchy) | full | full | missing | `rolePositions` unused on Android |
| Assign a role to a member | full | full | missing | `addRole`/`removeRole` (`Endpoints.kt:223,226`) unused; the Android member row offers only Kick |
| Kick | full | full | full | `ServerSettingsScreen.kt:262` |
| Ban (with reason and delete-message-days) | full | full | missing | `ServersApi.ban` (`Endpoints.kt:231`) unused; Android can list and unban but not ban |
| Timeout | full | full | missing | `MODERATE_MEMBERS` is only a checkbox label on Android (`Perms.kt:71`); an incoming timeout is honoured |
| Ban list | full | full | full | |
| Audit log | full | full | partial | web renders verbs and change diffs (`AuditLogPage.tsx:102-126`); Android prints the raw action with underscores swapped for spaces |
| **Server mute / deafen / move a member in voice** | **missing** | **missing** | **missing** | server route in `internal/api/voice_glue.go`; `web/src/api/voice.ts:49` has zero callers. Both READMEs claim it |
| Dead permission toggles | — | — | — | `USE_SOUNDBOARD`, `PRIORITY_SPEAKER`, `CREATE_POLLS` in `web/src/lib/perms.ts:92,97` and `android/.../data/Perms.kt` gate features that do not exist |

### Invites and guests

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Create an invite | full | full | partial | web picks channel, max age and max uses (`InviteModal.tsx:101-108`); Android hardcodes 7 days / unlimited (`ChannelSheets.kt:358`) |
| List and revoke server invites | full | full | full | `web/.../server/pages/InvitesPage.tsx` |
| List and revoke **group DM** invites | missing | missing | missing | `GroupInviteModal.tsx` creates a link with age and uses but never lists one; `channels.groupInvites` (`api/channels.ts:65`) wraps `GET /channels/{id}/invites` and has no caller. Once made, a link into a private group conversation can never be seen again or revoked |
| Invite carries the conversation key in the URL fragment | full | full | full | |
| Vanity / custom invite URL | full | full | full | `api/vanity.go`, Server Settings, Invites (2026-11-02) |
| Guest links for a voice call | full | full | missing | `web/.../voice/GuestLinkModal.tsx`; the whole Android `GuestsApi` (`Endpoints.kt:403-425`) has no callers, **and** `/guest/<code>` is registered in the manifest but routed to a screen that ignores the guest flag (`InviteScreen.kt:49,57`), so the link dead-ends |
| Guest call page | full | full | n-a | `web/src/pages/GuestCallPage.tsx` |

### Messages

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Send, edit, delete | full | full | full | |
| Reply, with a ping toggle | full | full | full | Android adds swipe-to-reply with haptics (`ui/chat/SwipeToReply.kt:49`) |
| Pin / unpin, pins list | full | full | full | |
| Mark unread from a message | full | full | full | |
| Copy text / copy link | full | full | full | |
| Copy message ID (developer mode) | full | full | missing | |
| Bulk select and delete | full | full | missing | `web/.../chat/SelectionBar.tsx`; `POST /messages/bulk-delete` unused on Android |
| Suppress / restore link embeds on your own message | full | full | missing | `web/.../chat/messageMenu.ts:83-86` |
| **Forward a message** | missing | missing | missing | no reference to forwarding in `web/src`, `android/`, or `internal/`; spec P2 (`chat-design.md:173`) |
| Scheduled send | missing | missing | missing | spec P3 |
| Polls | missing | missing | missing | `polls` / `poll_votes` tables exist (`internal/db/migrations/0001_init.sql:328,335`) with no store, API or client code |
| Reactions | full | full | partial | Android has no "who reacted" — `MessagesApi.reactors` (`Endpoints.kt:174`) unused; web shows up to 20 names on hover (`Reactions.tsx:63`) |
| Custom-emoji reactions | full | full | full | |
| `@user` mention + autocomplete | full | full | full | |
| `@everyone` / `@here` | full | full | full | |
| **`@role` mention** | missing | missing | missing | rendered (`parse.ts:469`), never produced; `extractMentions` (`web/.../composer/mentions.ts:13`) only handles usernames. Also not counted server-side: `internal/store/readstates.go:78` checks `mentions` and `mention_everyone` only |
| **`#channel` mention** | missing | missing | missing | rendered (`parse.ts:477`), never produced |
| `:shortcode:` autocomplete | full | full | missing | `EmojiIndex.expandShortcodes` (`android/.../ui/pickers/EmojiIndex.kt:93`) has no callers |
| Slash commands | partial | partial | missing | four kaomoji plus `/me` and `/spoiler` (`web/.../composer/commands.ts`) — cosmetic only |
| Markdown | full | full | full | both parsers cover bold, italic, underline, strike, inline code, fenced blocks, quotes, lists, headings, subtext, masked links, `<url>`, spoilers, timestamps |
| Code syntax highlighting | full | full | missing | web lazy-loads highlight.js (`web/src/markdown/render.tsx:413`) |
| Text spoilers | full | full | full | |
| Message edit history | missing | missing | missing | only an "edited" stamp (`MessageItem.tsx:208`) |

### Files, media, expressions

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Attach files (chunked, resumable, encrypted) | full | full | full | `web/src/lib/attachments.ts:160`; `android/.../media/Uploads.kt:96-204` |
| Drag and drop onto the window | full | full | n-a | `web/.../composer/DropOverlay.tsx` |
| Paste an image from the clipboard | full | full | n-a | `Composer.tsx:490-494` |
| Upload progress per file | full | full | full | |
| Mark an attachment as a spoiler when sending | full | full | missing | `PickedFile.spoiler` (`android/.../media/Uploads.kt:31`) is never set true; incoming spoilers do blur |
| Alt text on an image | missing | missing | missing | no field in `AttachmentMeta` (`android/.../crypto/MessageCrypto.kt:39-50`) or the web equivalent |
| Camera capture | n-a | n-a | missing | no `ACTION_IMAGE_CAPTURE` anywhere despite the CAMERA permission |
| Voice messages | missing | missing | missing | no recorder in any client |
| Image viewer with zoom and pan | full | full | full | `web/.../common/ZoomPan.tsx`; `android/.../ui/sheets/Misc.kt:74-125` |
| Video and audio players | full | full | full | Android uses ExoPlayer (`ui/chat/Players.kt`) |
| GIF autoplay | full | full | full | |
| Link, GIF and YouTube embeds | full | full | full | generated by the sender |
| Unicode emoji picker, recents, skin tone | full | full | full | shared `recentEmoji` and `skinTone` |
| Custom server emoji (use) | full | full | full | |
| Custom server emoji (manage) | full | full | missing | `web/.../server/pages/EmojiPage.tsx` |
| Stickers (send, install a pack) | full | full | full | |
| Sticker packs (create, edit, share) | full | full | missing | `createPack`…`deleteSticker` (`Endpoints.kt:306-323`) all unused |
| Browse public sticker packs | full | full | missing | `expressionsApi.packs`-style listing unused on Android |
| GIF search and trending (Tenor) | full | full | full | |
| GIF favourites | full | full | missing | `favoriteGifs` in the shared settings; Android never reads it |

### Search, unreads, notifications

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Message search | partial | partial | partial | client-side by necessity — the server holds ciphertext. Web searches the whole server (or the DM) with `from: in: has: mentions: before: after:` and `-word` (`web/src/lib/search.ts:6`), pulls older pages into the index, and jumps to the hit. Android does `contains()` over the open channel's already-decrypted messages, no filters, and the tap does not jump (`ChannelSheets.kt:198-254`) |
| Server-side search | n-a | n-a | n-a | impossible under this encryption design, correctly |
| Read states synced across devices | full | full | full | `internal/store/readstates.go` |
| Unread dot and mention badge | full | full | full | |
| Unread count on DMs | full | full | full | server counts DMs only, capped at 100 (`readstates.go:79`) |
| New-messages divider | full | full | missing | `web/.../chat/MessageList.tsx:113-115`; Android has only a day divider |
| Jump to the first unread message | missing | missing | missing | the divider is drawn but nothing navigates to it |
| Mark a channel read | full | full | missing | Esc (`shell/Shortcuts.tsx:77-85`) or the channel menu (`sidebar/serverMenu.ts:99-105`) |
| Mark a server read | full | full | missing | `web/.../sidebar/serverMenu.ts:23` |
| Mark everything read / an inbox | missing | missing | missing | no "inbox" anywhere in the repo |
| System notifications | full | full | full | Android keeps a socket in a foreground service — no push service exists |
| Notification sound | full | full | partial | the shared `notifications.sounds` toggle is written by the Android settings screen (`SettingsScreen.kt:390`) and never read by `notify/Notifications.kt`, which leaves the channel's default sound on |
| Per-server notification level | full | full | partial | web has the modal (`ServerNotificationsModal.tsx`); Android reads the level (`SettingsStore.kt:161`) but has no UI to set it |
| Per-channel notification level | missing | missing | missing | `notifications.perChannel` is keyed by channel id and only ever written with a **server** id |
| Honour the server's `default_notifications` | missing | missing | missing | settable in server settings (`ServerOverviewPage.tsx:108`), read by nobody: `web/src/lib/notify.ts:113` hardcodes the fallback to `"mentions"` |
| Mute a server | full | full | missing | Android mutes channels and DMs only (`ChannelSheets.kt:317`) |
| Mute a channel or DM | full | full | full | |
| Mute for a duration | missing | missing | missing | `notifications.muted` is a flat list of ids (`web/src/stores/settings.ts:78`) |
| Suppress `@everyone` | missing | missing | missing | |
| Hide message text in notifications | missing | missing | missing | the body always carries the decrypted preview (`notify.ts:129`) |
| Reply from the notification | missing | missing | missing | web goes through the service worker, which supports `actions`, and passes none (`notify.ts:47-50,129`); inside Electron the constructor path is taken instead, which cannot have them (`notify.ts:44-55`); Android has no `RemoteInput` |
| Do Not Disturb silences notifications | full | full | missing | `notify.ts:121`; Android cannot set DND at all |
| Notification for a friend request | full | full | missing | `notify.ts:133` |

### Voice and video

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Voice channels, DM calls, ringing, decline | full | full | full | |
| Mute / deafen | full | full | full | |
| Camera | full | full | full | |
| Screen share | full | full | partial | web picks 720p→source at 15/30/60 fps (`stores/settings.ts:31-32`); Android is hardcoded to 1280×720 at 30 fps with no control (`android/.../voice/VoiceSession.kt:316`) |
| Share computer audio | full | full (adds per-window / per-app audio, Win ≥ 19041 and macOS ≥ 13) | missing | `desktop/src/main/appAudio.ts:73-187`; nothing on Linux (`permissions.ts:282-290`); Android's `ScreenCapturerAndroid` takes video only — no `AudioPlaybackCaptureConfiguration` anywhere |
| Screen-source picker with thumbnails | full (browser's) | full (own picker) | n-a | `desktop/src/main/permissions.ts:159-256` |
| Per-user volume to 200 % | full | full | missing | `VoiceSession.setVolume` (`android/.../voice/VoiceSession.kt:254`) has no caller |
| Push to talk | full | partial | missing | web binds it in-page (`web/src/voice/session.ts:982`); the desktop shell registers no global shortcut at all; the Android toggle writes `voiceMode` and nothing reads it |
| Voice activity with auto-sensitivity | full | full | missing | |
| Input / output / camera device selection | full | full | missing | `web/.../settings/VoicePage.tsx:126-228` |
| Noise suppression, echo cancel, AGC | full | full | full | Android exposes the WebRTC flags |
| Link-quality readout | full | full | partial | web shows RTT and loss (`VoiceView.tsx:697`); Android shows frames sealed/opened/dropped |
| Focus / grid layout, picture-in-picture, fullscreen | full | full | partial | `web/.../voice/VoiceView.tsx:313-326` |
| Guests in a call | full | full | missing | |
| Camera flip (front/back) | n-a | n-a | missing | `VoiceSession.switchCamera:287` has no caller |
| Soundboard, stage channels, priority speaker, voice-channel text pane | missing | missing | missing | spec P2/P3; permissions for two of them ship anyway |
| E2E-encrypted call frames | full | full | full | Android does it in C++ because WebRTC only offers the hook to native code |

### Settings, account, data

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Change username / display name | full | full | partial | Android's username is read-only |
| Change password | full | full | full | |
| Recovery key (show, replace) | full | full | full | |
| Two-factor (TOTP) with backup codes | full | full | full | `android/.../ui/settings/SettingsScreen.kt:476-617` |
| Devices / sessions, revoke | full | full | full | |
| Delete account | full | full | missing | `UsersApi.deleteMe` (`Endpoints.kt:79`) unused |
| App lock / passcode | full | full | missing | `web/src/lib/appLock.ts` — and the phone is exactly where this matters. No `BiometricPrompt` on Android |
| DM privacy ("who can message me") | full | full | full | |
| Blocked list | full | full | full | |
| Theme | full | full | full | 5 themes, shared through the encrypted settings |
| Accent colour | full | full | missing | |
| Font size | full | full | missing | `SettingsStore.fontScale` (`android/.../data/SettingsStore.kt:140`) is never read |
| Compact / cozy message display | full | full | full | |
| Reduced motion | full | full | partial | Android exposes the toggle; nothing in the Compose tree consults it |
| Underline links, spellcheck toggle | full | full | missing | |
| Language | full | full | full | 11 languages including RTL Arabic, from one catalogue copied into the APK at build time (`android/app/build.gradle.kts:36-38`) |
| Dedicated accessibility page | missing | missing | missing | reduced motion sits under Appearance; no high contrast, no keybind remapping, no screen-reader options |
| Voice and video settings | full | full | partial | |
| Notification settings | partial | partial | partial | web's page is two toggles and a row of test sounds (`NotificationsPage.tsx`) |
| Data export (zip of everything this device can decrypt) | full | full | missing | `web/src/lib/export.ts`, `PrivacyPage.tsx:69` |
| Instance admin (stats, registration policy, enable/disable, promote) | full | full | partial | Android shows read-only stats (`SettingsScreen.kt:660-681`) |
| Keyboard shortcuts help | full | full | n-a | `web/.../modals/ShortcutsHelp.tsx` |

### Platform and shell

| Feature | Web | Desktop | Android | Evidence / note |
|---|---|---|---|---|
| Point the client at another instance | n-a | **missing** | full | Android has a "Server address" field (`ui/auth/AuthScreen.kt:306-314`); desktop only reads `EVILION_URL` at launch (`desktop/src/main/config.ts:7-18`) with no UI |
| Tray / menu-bar icon with mute and deafen | n-a | full | n-a | `desktop/src/main/tray.ts:14-39` |
| Unread badge | full (title) | partial | full | real count on macOS and Linux, a static dot on Windows (`desktop/src/main/index.ts:270-278`) |
| Open at login, start minimised, close to tray | n-a | full | n-a | `desktop/src/main/index.ts:242-268` |
| Window state restore | n-a | full | n-a | `desktop/src/main/window.ts:114-126` |
| Spellcheck with suggestions | full | full | full (OS) | no language picker in the app |
| Zoom | full (browser) | partial | n-a | zoom items exist only in the macOS menu; Windows and Linux get no menu bar at all (`window.ts:155-157`) |
| Global keybinds (PTT, mute, deafen) | n-a | missing | n-a | `globalShortcut` never imported |
| Deep links / protocol handler | n-a | missing | full | Android handles invite, guest, sticker and channel links (`MainActivity.kt:143-154`); an invite clicked anywhere on the desktop opens the browser |
| Multiple windows, popped-out calls | n-a | missing | n-a | `window.ts:95-98` denies every `window.open` |
| In-game overlay | n-a | missing | n-a | |
| Multi-account | missing | missing | missing | one session, no partitions |
| Auto-update | partial (service worker) | full on Windows NSIS, macOS (hand-rolled, sha512-checked) and Linux AppImage; detect-only on `.deb` | missing | `desktop/src/main/update/`; no in-app update on Android despite `REQUEST_INSTALL_PACKAGES` in the manifest |
| Offline behaviour | full (offline page, service worker) | full (offline page with retry) | partial | Android keeps no local message store by design (`data/AppState.kt:52-58`) and retries every 5 s |
| Drafts kept per channel and synced | full | full | full | through the encrypted settings blob |
| Share target (send a photo to Nexa from another app) | n-a | n-a | missing | no `ACTION_SEND` intent filter |
| Home-screen shortcuts / widgets | n-a | n-a | missing | |
| **iOS app** | n-a | n-a | n-a | there is none. iPhone users get the PWA, which the repo already knows is fragile — the download page offers only Windows, macOS, Linux and Android (`web/src/pages/DownloadPage.tsx:17-29`) |

---

## Feature gaps

Ordered roughly by value ÷ cost. "Depends on" names the thing that must exist first.

### <a id="g1"></a>G1. Android cannot set your own presence
**Value: high. Cost: small.** The app receives and draws everyone else's status
(`android/.../data/Session.kt:533`) but can never send its own: `Gateway.send` has
exactly one caller, `voice_state` (`voice/VoiceSession.kt:329`), and `presencePref`
(`net/Models.kt:54`) is never patched. So a phone user is permanently "online", can
never go invisible, and — because Do Not Disturb is what silences notifications on
web (`web/src/lib/notify.ts:121`) — has no way to quiet the app short of muting every
conversation. Add a status sheet to the user row and one `sendPresence` op.
*Depends on:* nothing; the gateway op already exists.

### <a id="g2"></a>G2. Android cannot block anyone
**Value: high. Cost: small.** `RelationshipsApi.block` (`net/Endpoints.kt:285`) has
zero call sites; three screens call `unblock`. Someone harassed on their phone has to
find a computer. Add "Block" to the profile sheet next to the existing Unblock.
*Depends on:* nothing.

### <a id="g3"></a>G3. Nobody can server-mute, deafen or move a member
**Value: high. Cost: small.** `PATCH /api/v1/servers/{id}/members/{uid}/voice` is
implemented, permission-checked and audited; `web/src/api/voice.ts:49` wraps it; no
component calls it. The web voice participant menu offers volume and (for guests)
"Remove from call" only (`web/.../voice/VoiceParticipants.tsx:59-60`). The display
side is already there — `p.server_mute` is rendered at `VoiceParticipants.tsx:71`.
This is three menu items against permissions the grid already exposes
(`MUTE_MEMBERS`, `DEAFEN_MEMBERS`, `MOVE_MEMBERS`). Until then, the README and
`docs/STATUS.md` both describe a feature that cannot be reached.
*Depends on:* nothing.

### <a id="g4"></a>G4. Role mentions and channel mentions cannot be written
**Value: high. Cost: medium.** Everything except authoring exists: the parsers render
`<@&id>` and `<#id>` (`web/src/markdown/parse.ts:469,477`;
`android/.../ui/markdown/Render.kt:268-296`), roles carry `mentionable`, the message
API carries `mention_roles` and the store persists it
(`internal/store/messages.go:20`). What is missing is (a) autocomplete — the web
`AcItem` union has only `user | emoji | customEmoji | command`
(`web/.../composer/Autocomplete.tsx:10`) — (b) the `@role`/`#channel` → token
conversion in `extractMentions` (`web/.../composer/mentions.ts:13`), and (c)
server-side counting: `internal/store/readstates.go:78` and
`CountMentionsAfter:108` check `mentions` and `mention_everyone` only, so a role ping
would not raise a badge. Without this, "mentionable" in the role editor is a switch
wired to nothing, and there is no way to ping a team.
*Depends on:* a member-roles lookup in the composer (the servers store already has it).

### <a id="g5"></a>G5. Threads in server channels
**Value: high. Cost: large.** `internal/api/subchat_handlers.go:153` rejects subchats
on anything but a DM or group DM. The spec lists threads as P3
(`chat-design.md:175`). This is the largest Discord-shaped hole and the one that most
changes how a busy server feels: without it, one conversation in `#general` buries
every other. The good news is that most of the machinery exists — a subchat is
already a channel with a `parent_id`, its own key conversation and its own read
state, and the web client already has a panel and a router for them
(`web/.../chat/SubchatPanel.tsx`, `ChatView.tsx:175`). The work is permission
resolution through the parent channel, a thread list in the channel header, the
"started a thread" system message, unread roll-up, and auto-archive.
*Depends on:* nothing structural; the key model already supports per-channel keys
derived from the server root.

### <a id="g6"></a>G6. Notification settings stop half way
**Value: high. Cost: small to medium.** Five separate holes, all cheap:
- **Per-channel level.** `notifications.perChannel` is `Record<string,
  NotificationLevel>` (`web/src/stores/settings.ts:78`) and `notify.ts:113` already
  looks up the channel id *before* the server id — but the only writer is
  `ServerNotificationsModal.tsx:24`, which writes a **server** id. The channel
  context menu offers mute or not-mute and nothing between
  (`web/.../sidebar/serverMenu.ts:111`). One reused modal closes this.
- **The server's own default is ignored.** `default_notifications` is stored,
  editable (`ServerOverviewPage.tsx:108`) and read by no client; `notify.ts:113`
  hardcodes `"mentions"`. An admin who sets "All messages" changes nothing.
- **No mute duration.** `muted` is a flat list of ids, so "mute for an hour" is
  impossible. Discord's list is 15 min / 1 h / 8 h / 24 h / until I turn it back on.
- **No "hide message content".** The notification body always carries the decrypted
  text (`notify.ts:129`). For a product whose whole pitch is that the server cannot
  read your messages, not offering to keep them off the lock screen is an odd gap.
- **No suppress-@everyone.**
*Depends on:* nothing; all of it lives in the encrypted settings blob that already
syncs to Android.

### <a id="g7"></a>G7. The desktop app has no global hotkeys
**Value: high. Cost: medium.** `globalShortcut` is never imported in `desktop/src/`.
Push-to-talk is a page `keydown`/`keyup` released on blur
(`web/src/voice/session.ts:982-996`), so it works only while the Nexa window is
focused — which is never, if you are talking while playing something. Mute and deafen
(`Ctrl/Cmd+Shift+M`/`+D`, `web/.../shell/Shortcuts.tsx:39-50`) are page-level too; the
tray menu is the only out-of-window control. For a product that ships a Discord-RPC
server precisely so it can sit beside a game, this is the missing half.
*Depends on:* an IPC path from main to the page for key-down/key-up, plus a capture UI
for the chosen key. Wayland cannot do it and should say so rather than fail silently.

### <a id="g8"></a>G8. The desktop app is hardwired to one instance
**Value: high. Cost: small.** `desktop/src/main/config.ts:17` returns
`https://chat.evilion.xyz` unless `EVILION_URL` is set in the environment before
launch. There is no UI. The Android app already asks
(`android/.../ui/auth/AuthScreen.kt:306-314`), and the whole product is described as
self-hosted. As it stands, anyone who deploys their own `chatd` can use the web app
and the Android app but not the desktop app — the one client that has the tray, the
auto-update and the Rich Presence server. A first-run "which server?" screen, with
the origin then frozen for the session, is the fix; the origin is already a single
constant that the preload bridge, the permission handlers and IPC all read
(`preload/index.ts:24`, `permissions.ts:21,40`, `index.ts:58`), so the plumbing is
one variable, not a rewrite.
*Depends on:* deciding where the chosen origin is stored and how it is re-locked
(see the security reviewer's note on `EVILION_URL` not being gated on
`app.isPackaged`).

### <a id="g9"></a>G9. Android search is not usable
**Value: high. Cost: medium.** `android/.../ui/sheets/ChannelSheets.kt:198-254`:
`contains()` over the messages this device happens to have decrypted **in the channel
you are looking at**, minimum two characters, capped at 60 results, no filters, and
the tap at `:236` just closes the sheet. The web client searches the whole server,
parses `from: in: has: mentions: before: after:` and `-word`
(`web/src/lib/search.ts:6`), pulls older pages into the index as it goes
(`SearchPanel.tsx:56-87`) and jumps to the hit. Search is the thing people use a chat
app's history for; on a phone it is currently decorative.
*Depends on:* a jump-to-message path on Android (which the deep-link handler almost
has, `MainActivity.kt:143-154`), and paging older history into memory.

### <a id="g10"></a>G10. Polls
**Value: medium. Cost: medium.** The schema is already there —
`polls(message_id, options_count, multiselect, expires_at)` and
`poll_votes(message_id, user_id, option_index)`
(`internal/db/migrations/0001_init.sql:328,335`) — and so is the permission
(`CREATE_POLLS`, `web/src/lib/perms.ts:38`). There is no store, no handler and no
client code: `grep -rl poll internal/store internal/api` is empty. Under E2EE the
question and options go in the encrypted payload and only the option index is
plaintext, which the schema already assumes. Half-built features are the cheapest
ones to finish, and a poll is the single most-used "small" Discord feature.
*Depends on:* a message type and a renderer in both clients.

### G11. Message forwarding
**Value: medium. Cost: medium.** Nothing anywhere: no `forward` in `web/src`,
`android/` or `internal/`. Spec P2 (`chat-design.md:173`). The E2EE wrinkle is real —
a forwarded message must be re-encrypted for the destination conversation, so it is a
copy with attribution, not a reference — but that is also what makes it simple: read
the decrypted payload, re-seal it for the target channel, keep a `forwarded_from`
field in the payload for the header. Today the only way to move a message is to copy
its text by hand, which loses attachments entirely.
*Depends on:* a payload field; no server change beyond letting the attachment blob be
referenced twice (or re-uploading it).

### G12. Android moderation
**Value: medium-high. Cost: medium.** A moderator on a phone can kick and unban and
nothing else: no ban (`ServersApi.ban`, `Endpoints.kt:231`, unused), no timeout, no
role assignment (`addRole`/`removeRole`, `:223,226`, unused). Every endpoint and the
whole permission resolver are already in the app; what is missing is three sheets and
a role picker. Moderation is exactly the thing that happens when you are away from
your desk.
*Depends on:* nothing.

### G13. Android cannot manage a server's structure
**Value: medium. Cost: medium-large.** No channel creation or deletion, no category
creation, no reordering, no permission overwrite editor, no server icon or banner, no
invite options. The permission *resolver* is complete (`data/Perms.kt:122-156`), so
the app knows exactly what you are allowed to do — it just offers none of it. This is
a large surface; I would do channels and invite options first and leave the
tri-state overwrite grid last, since that one is genuinely hard on a phone.
*Depends on:* an Android equivalent of `PermissionGrid`.

### <a id="g14"></a>G14. Server ownership cannot be transferred
**Value: medium. Cost: small.** `POST /api/v1/servers/{id}/transfer` exists and the
audit log has a verb for it (`AuditLogPage.tsx:20`), but `servers.transfer` in
`web/src/api/servers.ts` has no caller. A group DM can change hands
(`web/.../chat/groupMemberMenu.ts:64`); a server cannot. If the owner deletes their
account or walks away, the server is frozen — nobody else can ever hold
`MANAGE_SERVER` at the top of the hierarchy. One row in the members page.
*Depends on:* nothing.

### <a id="g15"></a>G15. There is no way to find a person
**Value: medium. Cost: small.** `GET /api/v1/users/search` is implemented and
`users.search` (`web/src/api/users.ts:46`) has no caller: adding a friend means
typing an exact username (`FriendsPage.tsx:225`). On a private instance that is
survivable; on any instance with more than a few dozen people it is not. Wire the
existing endpoint into the Add Friend field as a suggestion list.
*Depends on:* deciding the privacy rule (the `dm_privacy` field suggests the shape).

### G16. Guest links do not work on Android, and the deep link dead-ends
**Value: medium. Cost: small-medium.** The whole `GuestsApi`
(`android/.../net/Endpoints.kt:403-425`) has no callers, so a phone user cannot make
a guest link for a call. Worse, `/guest/<code>` **is** registered in the manifest and
routed (`ui/main/MainScreen.kt:102`) to `InviteScreen(code, guest = true)` — and
`InviteScreen` declares `guest` at `ui/invite/InviteScreen.kt:49` and never reads it,
calling `ServersApi.invitePreview` regardless (`:57`). So a guest link shared to
someone with the app installed opens the app and says the invite is invalid. That is
worse than not handling the link at all.
*Depends on:* nothing.

### G17. Android has no app lock
**Value: medium-high. Cost: medium.** The web client has a full passcode layer —
Argon2 policy synced with the account, auto-lock after inactivity, lock on blur, ten
wrong tries wipe the wrapped key (`web/src/lib/appLock.ts`,
`web/.../settings/SecurityPage.tsx:204-253`). The Android app reads none of it
(`appLock` never appears in `android/`) and there is no `BiometricPrompt`. The phone
is the device most likely to be handed to someone, and the one where a fingerprint
prompt is a single API. The keys are already in a keystore-wrapped file
(`data/SecureStore.kt:17-27`), so the hard part is done.
*Depends on:* honouring the shared `appLock` policy so a lock set on one device is
asked for on all of them, which is what the web policy already assumes.

### G18. Android has no data export and cannot delete the account
**Value: medium. Cost: small.** `UsersApi.deleteMe` (`Endpoints.kt:79`) is unused and
`export` appears nowhere in the Android sources. Both are promises the product makes
on the Privacy page of the other client. Delete-account is a dialog; export is
larger on a phone (a zip of decrypted history) and could reasonably say "do this on a
computer" rather than pretend.
*Depends on:* nothing for delete; a file-writer and the same zip code for export.

### G19. No "jump to unread", no mark-all-read, no inbox
**Value: medium. Cost: small-medium.** The web client draws the new-messages divider
(`web/.../chat/MessageList.tsx:113-115`) but nothing navigates to it, there is no
"X new messages — Jump / Mark read" bar, no way to mark everything read at once (only
per-server, `serverMenu.ts:23`), and no inbox listing unread mentions across servers —
`grep -ri inbox` across the repo returns one emoji. Coming back after a weekend means
clicking every bold channel. Android has none of the three and no divider either.
*Depends on:* the read-state store, which already has everything needed.

### G20. Voice-channel text chat
**Value: medium. Cost: medium.** Spec P2 (`chat-design.md:232`). A voice channel is a
channel with `type = "voice"` and no message surface; the sidebar joins the call on
click (`web/.../sidebar/ServerSidebar.tsx:189`). Letting a voice channel carry
messages is mostly UI — the message pipeline is channel-agnostic — and it is where
links get dropped mid-call today, which currently means finding another channel.
*Depends on:* deciding whether voice channels get their own read state (they should).

### G21. Announcement channels, rules gates, server folders
**Value: medium. Cost: medium each.** All three are in the spec's P2 list
(`chat-design.md:215-217`) and none exists. Announcement (read-only for
`@everyone`) is nearly free — it is a permission default plus a channel flag. Rules
gates matter the moment an instance takes public invites. Folders matter once someone
is in more than about eight servers, and the rail already has drag ordering to build
on (`rail/Rail.tsx:104`).

### G22. Notification actions and richer OS integration
**Value: medium. Cost: medium.** Neither desktop nor Android can reply from a
notification. Desktop takes the `Notification` constructor path inside Electron
(`web/src/lib/notify.ts:44-55`), which has no action buttons; Android has no
`RemoteInput` or `MessagingStyle` (`notify/Notifications.kt:65-101`). Android's
`MessagingStyle` alone would also give conversation-grouped notifications and bubbles
for free. Android is missing a share target too (no `ACTION_SEND` filter), so you
cannot send a photo to Nexa from the gallery.
*Depends on:* on desktop, moving notifications into the main process so they can carry
actions — which also fixes the "no action buttons anywhere" note above.

### G23. Attachment alt text and voice messages
**Value: medium (alt text is an accessibility obligation). Cost: small / medium.**
`AttachmentMeta` has `name`, `w`, `h`, `spoiler` and no description
(`android/.../crypto/MessageCrypto.kt:39-50`; the web mirror is
`web/src/lib/attachments.ts:40`), so an image posted here can never be described to
a screen reader. Adding a field to the encrypted payload costs nothing on the server.
Voice messages need a recorder and a waveform and are a bigger job, but they are the
single most-used mobile chat feature this app lacks.

### G24. An iOS app — hard, and probably the most valuable thing on this list
**Value: high. Cost: very large.** There is no `ios/` directory. iPhone users get the
PWA, and the repo already carries scar tissue from that: an iOS 26 PWA audio bug had
to be worked around, and the download page offers Windows, macOS, Linux and Android
only (`web/src/pages/DownloadPage.tsx:17-29`). A PWA on iOS has no reliable
background socket, so the thing Android solves with a foreground service — messages
arriving while the app is closed — has no answer at all on iPhone. Doing it properly
means a native client with the same crypto (the Android one is 14.6k lines of Kotlin
plus C++ for the call frames) **and** a push story, which this server does not have
in any form. Worth saying out loud rather than leaving implicit.
*Depends on:* push notifications, which depend on deciding what a notification may
contain when the server cannot read the message.

### G25. Bots, webhooks, API tokens — hard, and constrained by the design
**Value: medium. Cost: large.** There is no bot account type, no webhook, no
application and no API token: user flags carry only `FlagAdmin`
(`internal/store/models.go:37`, `:73`), and `grep -ri webhook` across the repo is
empty. This is the feature that turns a chat app into a platform, and the encryption
is what makes it hard: a bot needs a conversation key, which means it is a member
with an identity, not a URL you POST to. The honest version is "an account you can
give a token to, which holds keys like any other member" — which is buildable, unlike
an incoming webhook, which fundamentally cannot be E2EE. Decide which one the product
wants before anyone asks.

### <a id="g26"></a>G26. The rest of the unreachable backend
**Value: medium overall. Cost: small each.** Matching every route in `internal/api/`
against its call sites in `web/src/api/` and `android/.../net/Endpoints.kt` turns up
twelve routes that no client calls. Three have their own entry above because they are
features people ask for — server mute/deafen/move ([G3](#g3)), ownership transfer
([G14](#g14)) and user search ([G15](#g15)). The rest, for completeness:

- **`PUT`/`DELETE /servers/{id}/banner` — server banners can never be set or seen.**
  The routes, the `banner_id` field and the typed bindings (`api/servers.ts:78,81`)
  all exist; no UI writes it and no component renders it. Server icons have a full
  editor two lines away (`ServerOverviewPage.tsx:64,74`).
- **`GET /channels/{id}/invites` — group DM invites cannot be listed or revoked.**
  You can mint a link into a private group conversation with a chosen lifetime
  (`GroupInviteModal.tsx`) and then never see it again. Server invites have the whole
  page (`InvitesPage.tsx`).
- `DELETE /channels/{id}/guest-links/{code}` — revoking *one* guest link; only
  revoke-all is exposed (`GuestLinkModal.tsx:80`).
- `GET /channels/{id}/messages/{mid}`, `GET /servers/{id}/members/{uid}`,
  `GET /keys/epochs`, `GET /stickers/packs`, `GET /channels/{id}/voice` — all
  superseded by a list call, the gateway `ready` payload or the in-memory keyring.
  Harmless, but they are surface to maintain and to keep secure for no one.

Two protocol-level dead ends turned up alongside them: the `subscribe_members`
gateway op is defined (`internal/gateway/protocol.go:33`) and falls through the
switch that should handle it (`internal/api/voice_glue.go:222,339`), and the Redis
presence and pub-sub layer (`internal/cache/redis.go`) is never wired up in
`cmd/chatd/main.go:88-100`. Neither is product-facing; both belong to whoever is
reviewing the server, but they are the same smell as the rest of this section —
capability built, never connected.

### G27. First run has no onboarding
**Value: medium. Cost: small.** After registration and the recovery-key screen
(`web/src/pages/RegisterPage.tsx:70-90`) a new account lands on Friends with "No
conversations yet. Add a friend." (`sidebar/DMSidebar.tsx:67`). There is no nudge to
create a server, no explanation of what the rail is, no sample content, and no second
screen explaining what the recovery key is for beyond one line. For a product whose
first-run experience includes "here is a key, losing it loses your messages", the
empty state is doing a lot of unpaid work.

---

## Quality of life

Small things, cited so they can be found.

### <a id="q1"></a>Q1. Three permissions gate features that do not exist
**Value: medium. Cost: small.** `USE_SOUNDBOARD` (`web/src/lib/perms.ts:37`, shown at
`:92`), `PRIORITY_SPEAKER` (`:17`, shown at `:94`) and `CREATE_POLLS` (`:38`, shown at `:83`) all
appear in the role editor and in the channel overwrite grid, in both clients, with
translated descriptions in eleven languages — and there is no soundboard, no priority
speaker and no poll. Two of them are even in `DEFAULT_EVERYONE` (`:45`). An admin
reads that grid as a promise. Hide them behind a feature flag until the features
land.

### Q2. `Alt+↑`/`Alt+↓` only cycles DMs, wherever you are
**Value: medium. Cost: small.** `web/.../shell/Shortcuts.tsx:61-72` builds its list
from `selectDMs(...)` and matches the path against `^/channels/@me/(\d+)`. Press it
inside a server and it teleports you into your DM list. Discord's binding walks the
channels of whatever you are looking at. The sidebar already computes that list
(`sidebar/ServerSidebar.tsx`), and `useUI.activeChannelId` knows where you are.

### Q3. The shortcuts that are not bound
**Value: medium. Cost: small.** Bound today: `Ctrl+K`, `Alt+↑/↓`, `Esc`, `↑` to edit,
`Ctrl+E`, `Ctrl+Shift+M`, `Ctrl+Shift+D`, `Shift+PageUp/PageDown`, `Ctrl+/`
(`ShortcutsHelp.tsx:7-18`). Not bound anywhere — verified by grepping
`Shortcuts.tsx`, `ChatView.tsx` and `Composer.tsx` for `KeyF`, `KeyU`, `KeyG`,
`KeyP`, `KeyN`:
- `Alt+Shift+↑/↓` — next / previous **unread** channel. The read-state store already
  answers "is this channel unread" in one call (`isUnread`, `stores/readStates.ts:119`).
- `Ctrl+Alt+↑/↓` — previous / next server. `serverPositions` is already the ordered list.
- `Shift+Esc` — mark the whole server read. `markServerRead` already exists
  (`sidebar/serverMenu.ts:23`) and is only reachable from a context menu.
- `Ctrl+F` — focus the channel search box. Today `Ctrl+F` is the browser's find, which
  searches the ~50 rendered messages; the real search is a button in the header
  (`ChatView.tsx:212`).
- `Ctrl+Shift+U` — open the file picker.
- `Ctrl+P` — pinned messages; `Ctrl+Shift+G` / `Ctrl+Shift+S` — GIF and sticker
  pickers. All three panels already exist and are toggled by header buttons.
- `Ctrl+B` / `Ctrl+I` / `Ctrl+U` in the composer to wrap the selection in `**`, `*`
  and `__`. The composer already has `insertAt` (`Composer.tsx`, used by the emoji
  picker) so the plumbing is there.

### Q4. The quick switcher cannot reach a voice channel
**Value: small-medium. Cost: small.** `web/.../modals/QuickSwitcher.tsx:90` skips
anything that is not `type === "text"`. Jumping to a voice channel to see who is in
it is a normal thing to want, and the row would render identically. While there:
results are ranked by string score only (`:31-39`), with no weight for recency or
unread, so the channel you were in thirty seconds ago ranks below an alphabetical
accident.

### Q5. Muting has no duration
**Value: medium. Cost: small.** `notifications.muted` is `string[]`
(`stores/settings.ts:78`), so every mute is forever until you remember to undo it.
"Mute for 15 minutes / 1 hour / 8 hours / 24 hours / until I turn it back on" needs
the list to become `Record<string, number | null>` and one expiry check in
`notify.ts:114`. There is also nowhere to see what you have muted: the only way to
un-mute is to find the thing and right-click it.

### Q6. Nothing tells you what a notification will contain
**Value: medium. Cost: small.** The notification body is the decrypted message text
(`lib/notify.ts:129`). For an end-to-end-encrypted product, a "Show message content
in notifications" toggle on the Notifications page is both an obvious courtesy and a
good place to say, once, that the preview is rendered on your device and never leaves
it.

### Q7. The Notifications settings page is two toggles and nine test buttons
**Value: medium. Cost: small.** `web/.../settings/NotificationsPage.tsx` offers
"Enable desktop notifications", "Sounds", and a row of nine buttons that play each
sound. There are nine named sounds (`lib/sounds.ts:4`) and one switch for all of
them: you cannot keep message pings and silence the call ringtone. The page also has
no list of muted conversations, no per-server summary, and no "quiet hours".

### Q8. No auto-idle
**Value: medium. Cost: small.** Status is manual only — `usePresence` has no idle
timer and the gateway sends presence only when you pick it
(`shell/UserPanel.tsx:63-65`). Everyone on the instance therefore looks online
permanently, which makes the green dot mean nothing. Discord flips to idle after ten
minutes of no input; an `visibilitychange` + last-input timestamp does it in about
fifteen lines, and the `idle` status already exists everywhere
(`stores/presence.ts:4`).

### Q9. The DM sidebar does not show who is typing
**Value: small. Cost: small.** The typing store is per channel
(`stores/typing.ts`) and only `TypingBar` reads it, inside the open conversation. A
dot next to the DM row is the cheapest possible "someone is about to say something".

### Q10. Android: the notification sound toggle does nothing
**Value: medium. Cost: small.** `SettingsScreen.kt:390` writes
`notifications.sounds` into the shared settings — which the web client honours — and
`notify/Notifications.kt` never reads `SettingsStore.soundsOn`. The message channel is
created with `IMPORTANCE_HIGH` and no `setSound` override
(`Notifications.kt:35-38`), so the OS default plays regardless. Either read the flag
or remove the switch; a switch that lies is worse than no switch.

### Q11. Android: the reduce-motion toggle does nothing
**Value: small. Cost: small.** `SettingsStore.reducedMotion` is read at
`data/SettingsStore.kt:146` and consulted by no composable. Same problem as Q10.
`fontScale` (`:140`) is worse — it is never read at all, so the web client's font-size
setting silently does not apply to the phone.

### Q12. Android's audit log prints raw action names
**Value: small. Cost: small.** `ServerSettingsScreen.kt:459-483` shows `member_ban`
as "member ban" with no actor phrasing and no change diff. The web version renders a
sentence and the diff (`AuditLogPage.tsx:102-126`) from a translated verb table that
is already in the shared catalogue — Android can read the same keys.

### Q13. Android invites have no options
**Value: small-medium. Cost: small.** `ChannelSheets.kt:358` hardcodes
`maxUses = 0, expiresIn = 7 days`. The web modal offers both
(`InviteModal.tsx:101-108`). Two dropdowns in a sheet that already exists.

### Q14. Android has no new-messages divider
**Value: medium. Cost: small.** `MessageRow.kt:343` draws a day divider and nothing
else; the read state that the web divider uses is already loaded and already drives
the unread dot (`Sidebar.kt:109`). Without it, opening a busy channel on a phone
means scrolling and guessing.

### Q15. Android cannot mute a server
**Value: small-medium. Cost: small.** `ChannelSheets.kt:317` mutes a channel or DM;
`SettingsStore.isMuted` already checks the server id
(`data/SettingsStore.kt:173-176`), so the read side is done. One row on the server
sheet.

### Q16. Android drops what web put in the shared settings
**Value: small. Cost: small.** Android reads theme, locale, message display, font
scale (unused), previews, reduced motion (unused), skin tone, recent emoji, drafts,
mutes, sounds, per-channel levels, server order and collapsed categories — a
genuinely impressive amount of cross-device state for an encrypted blob. It ignores
`favoriteGifs`, `userNotes` and `appLock`. Favourite GIFs and the private note you
wrote about someone are exactly the sort of small thing whose absence reads as "this
is a different, lesser app".

### Q17. The desktop hardware-acceleration toggle does nothing until you relaunch
**Value: small. Cost: small.** `desktop/src/main/index.ts:21` reads the setting once
at module load; the IPC setter only persists it (`:185-193`). `DesktopPage.tsx:135`
flips it and says nothing. `restartApp()` already exists on the bridge
(`preload/index.ts:63`) and is already used for the screen-permission flow — offer it
here too, or at least say "takes effect next time you open Nexa".

### Q18. `setCallActive` is called on every join and leave and does nothing
**Value: small. Cost: small.** `web/.../desktop/DesktopEffects.tsx:114` sends it;
`desktop/src/main/index.ts:197` handles it with `() => {}`. Either use it (a tray
badge while in a call, suppressing the "keep running" notification, blocking sleep) or
delete the round trip.

### Q19. No menu bar on Windows and Linux
**Value: small. Cost: small.** `desktop/src/main/window.ts:155-157` sets
`Menu.setApplicationMenu(null)` off macOS, which removes zoom, reload and devtools
accelerators along with the menu. `autoHideMenuBar: true` is already set
(`window.ts:61-71`), so a real menu would stay out of the way and still respond to
`Alt`.

### Q20. Windows gets a dot instead of an unread count
**Value: small. Cost: small.** `desktop/src/main/index.ts:270-278` — macOS and Linux
get `app.setBadgeCount(n)`; Windows gets a static overlay icon and the number only in
the tooltip. Windows supports a numbered overlay icon; it just has to be drawn.

### Q21. The composer's reply bar does not show what you are replying to
**Value: small. Cost: small.** The *message* side of replies is complete and good:
`ReplyPreview` (`web/.../chat/MessageItem.tsx:44-62`) draws the avatar, the name and
a plain-text preview, clicking it jumps to the original and clicking the name opens
the profile. The *composer* side is one line of text — "Replying to **Name**" plus an
`@ ON` / `@ OFF` pill (`Composer.tsx:449-460`). So once you have scrolled away, or
picked reply from the context menu, there is nothing on screen saying which message
you are answering. The same `ReplyPreview` component would drop straight in. The pill
is also the only piece of unexplained jargon in the app's chrome: `@ OFF` means "do
not notify them", which the tooltip does not say because there is no tooltip.

### <a id="q22"></a>Q22. Confirmations: right on web, absent on Android
**Value: medium. Cost: small.** On Android, **Delete Server** and **Leave Server** are
plain buttons that fire on the first tap (`ui/server/ServerSettingsScreen.kt:172-184`
and `:185-198`): no dialog, no undo, and the server and every message in it are gone.
The web client makes you type the server's name to delete it
(`web/.../server/pages/DeleteServerPage.tsx:41`) and asks before leaving
(`sidebar/serverMenu.ts:77-85`). This is the clearest case in the product of a
confirmation missing from exactly the place that needs one.

On web, confirmations are mostly well placed: delete message,
delete channel, delete role, leave server, delete server (type the name),
bulk-delete, leave group. Two asymmetries stand out — closing a DM happens instantly
with no undo (`sidebar/dmMenu.ts:132`, the non-group branch), which loses your place
and your draft for that conversation; and shift-clicking the trash icon skips the
prompt (`MessageItem.tsx:238`) with no way to turn that off, which is fine for a
power user and startling for everyone else. The label does say so.

### Q23. There is no undo anywhere
**Value: small. Cost: medium.** `grep -ri undo web/src` finds nothing. Most of it is
genuinely hard under E2EE, but two cases are not: an "Undo" action on the toast after
closing a conversation (the channel is only hidden, `channelsApi.close`), and a
five-second grace before a message actually leaves, which the optimistic-send path
(`composer/send.ts:50`) is already shaped for.

### Q24. Bulk actions exist for messages and nothing else
**Value: small. Cost: medium.** `SelectionBar` gives multi-select delete for messages.
There is no bulk role assignment on the members page, no bulk unban, no
multi-select in the emoji or sticker manager. The members page is where it would earn
its keep: giving fifteen people a role today is fifteen modals.

### Q25. Copy
**Value: small. Cost: small.** Mostly good — the strings in this codebase are written
by someone who cares. Two places where they are not: the Android invite sheet's
hardcoded English (`ChannelSheets.kt`, and the list at the end of the Android i18n
sweep — system messages, "edited", the typing bar, "is calling", "ROLES", member
counts, relative timestamps), which read as English islands in ten other languages;
and the search panel's own hint, which tells you it searches "this device's decrypted
messages" — true, necessary, and phrased as an apology rather than as the privacy
feature it is. The web client has one of these too: `Composer.tsx:451` writes
`Replying to` as a bare string rather than through `t()`, so the reply bar stays
English in all eleven languages.

### Q26. The web app never says what it cannot do on this device
**Value: small. Cost: small.** Search only covers what has been decrypted here; a
fresh browser therefore finds nothing in a channel full of history and says "no
results" rather than "this device has not read this channel yet". `SearchPanel.tsx`
already tracks `indexed` and `exhausted` (`:41,88`) — it has everything needed to say
the honest thing.

---

*Method: read `README.md`, `android/README.md`, `docs/STATUS.md` and
`docs/superpowers/specs/2026-09-11-chat-design.md`; enumerated all 176 routes in
`internal/api/`; read the web client's stores, composer, chat, settings, server and
voice components; read every file under
`android/app/src/main/java/xyz/evilion/chat/`; read `desktop/src/`. "Missing" claims
come either from a named guard in the code or from a repo-wide search that returned
nothing, described in place. Unused client bindings were found by matching every
method in `web/src/api/*.ts` and `android/.../net/Endpoints.kt` against its call
sites. No source file was modified.*
