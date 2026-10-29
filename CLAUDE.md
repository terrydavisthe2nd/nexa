# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Nexa is the end-to-end encrypted chat app served at https://nexa.fish (also https://mathtest.lol;
https://chat.evilion.xyz is its old address, which moves browsers on). This repository is all of it:
the Go server (`cmd/`, `internal/`), the React + TypeScript web app (`web/`), the Tauri desktop
app that runs the web app (`desktop/`) and the native Android app (`android/`, Kotlin + Jetpack
Compose). README.md is the public project overview, with features, privacy boundaries, an isolated
local quick start and links to development documentation. `ENCRYPTION.md` is the public
encryption explanation, covering key storage and distribution, recovery, bot access and trust
boundaries, with links to the implementation. The original design spec is
`docs/superpowers/specs/2026-09-11-chat-design.md` (section 6 is the cryptography).

Nexa's first-party code is licensed under the root `LICENSE` (MIT), with the copyright
notice `2026 terrydavisthe2nd / Nexa`. Third-party components retain their own licenses and notices.

The rules come first, then commands and testing, then how each part is built.

## Rules that always apply

### Keep this file current

This file is how every future session learns the codebase, so it has to stay true and complete.
Whenever a change makes anything here wrong or incomplete, the same change updates CLAUDE.md: a new,
renamed or removed package, module, route group, gateway event, table, setting, command, tool,
test helper, build or release step, workflow or convention, and every standing rule the owner
gives. When you notice something here that is already wrong, fix it then. Write what you checked in
the code, in the style of the rest of the file (plain sentences, no em dashes), and commit it with
the change it describes.

### Building is deploying

`web/dist`, `bin/chatd` and `downloads/android` are what the live instance serves, so
`npm run build`, `make web`, `make server`, `make build`, `make publish-android` and
`tools/android_release.py` without `--to` publish immediately. Never run them to check a change; use
the commands under Commands, which write nothing live. The desktop app is published by
`.github/workflows/desktop.yml`, not from this machine; `make desktop` only writes
`desktop/src-tauri/target`. Never test against port 8090, `data/` or the
repo's `chatd.env`. On this server, never start chatd from the repository root either (`make run`,
`go run ./cmd/chatd`): it reads the live `chatd.env` and `./data`, and chatd migrates its database
before it listens, so an unreleased build would migrate the live database. Deploy only when the
owner says "deploy" (see Deploying).

### All UI design goes through the frontend-design skill

Before any UI design work on this project, invoke the `frontend-design:frontend-design` skill and
follow it, however small the change: a new screen or component, a redesign, or a change to layout,
spacing, colour, type or motion, on the web, the desktop app or Android. No exceptions.
`android/DESIGN.md` is that process applied to the Android app; read it too before Android UI work.

### Web and mobile: both implemented, both tested

Every feature and change is implemented properly on both the web app and the Android app, and the
web at phone width and the Android app look and behave as alike as possible (same layout, sizes,
colours, words and order of things). A phone improvement made on one side is brought to the other
(`android/DESIGN.md` section 7 keeps the list). Nothing is done until it has been seen working:

1. Web: on a throwaway server, drive it in a headless browser (Playwright) at a desktop size
   (1366x768) and a phone size (411x914, the emulator's screen in CSS pixels). Look at the
   screenshots.
2. Android: build the debug app against the same throwaway server and use it in the Android
   emulator: every screen touched, in Dark, Light and one preset.
3. Put the phone-size web screenshots next to the emulator's and fix whichever side is off.

Passing unit tests do not replace looking at the screens. How to do each step is under Testing.

### Build what was asked

Build the literal request. "Custom" packs or themes mean letting people make their own, not adding
new built-in ones. If an extra seems worth having, suggest it instead of shipping it.

### When the work is done

1. Run the checks (`tools/check.sh` covers most of them) and the web and Android testing above,
   and update this file if the change touched anything it describes.
2. Commit on `main`, and push to GitHub (`git push origin main`) only when the owner says to push.
   The owner also works on another machine and pulls from GitHub, so say what is committed and not
   yet pushed. Never commit `chatd.env`, tokens, private keys, signing passwords, personal
   screenshots or handoff transcripts. Android signing is private local configuration under
   `android/signing/`; see its README. Keep a private backup and never replace or regenerate an
   existing release key. `nexa` is the canonical source repository; keep deployment settings and
   signing material out of Git rather than maintaining a separate private source branch.
   Run `python3 tools/privacy_check.py` before committing and the full history privacy and
   Gitleaks checks before publishing. See `docs/REPOSITORY.md` for the maintenance workflow.
3. After a round of app changes, leave a throwaway server with the new build running and give the
   owner its address so they can try it. WebCrypto needs https or localhost, so from another machine
   it is reached through an SSH tunnel to localhost, not the plain LAN address. Do not deploy until
   they say "deploy".

## Every major feature gets a What's New entry

When a change adds a major feature, redesigns something, or changes behaviour people will notice,
the same change adds an entry to the What's New popup. People see the popup once, the first time
they open the app after an update, and it is how they find out what changed.

- The list is `RELEASES` in `web/src/lib/whatsNew.ts`, newest first. Add a release at the top whose
  `id` is the deploy date (`"2026-09-26"`, then `"2026-09-26.2"` for a second one that day), or add
  items to the newest release if that one has not been deployed yet.
- Each item is a lucide-react icon, a title and one or two plain sentences. Title and body are i18n
  keys named after the release id without its dashes and dots (`whatsNew.r20260926.<name>Title`
  and `...Body` for release `2026-09-26`), translated into all twelve locales.
- Keep every item in the shape `{ icon: Name, title: "...", body: "..." }` (that order, double
  quotes): the Android app reads this same file with a regex (`fish.nexa.ui.WhatsNew`) and silently
  skips anything shaped differently.
- Say what people can do now and where to find it. Small fixes and polish do not need an entry.
- The popup is `web/src/components/shell/WhatsNewDialog.tsx`. It shows the releases an account has
  not seen yet (tracked in its synced settings) and skips releases older than the account.

## Writing UI text

- Labels use Title Case: buttons, menu items, tabs, navigation, dialog and page titles, headings
  and column headers ("Add Friends", "Create Group", "Mark as Read", "Add to Group"). Short
  articles, conjunctions and prepositions stay lowercase unless first or last. English only; the
  other locales follow their own language's capitalization.
- Checkbox and toggle labels use sentence case ("Hide their messages completely").
- Descriptions, hints, toasts and errors are short, plain sentences that sound like a person.
- Avoid em dashes; use a comma, a full stop or parentheses.
- Never describe the owner in the third person or sell Nexa as run by one person, in the app, the
  home page or the Terms. Contact details read "Contact the owner (nora) here: ...".
- Describe Nexa on its own terms. Do not compare it to Discord. Naming Discord is fine only where a
  feature really talks to Discord (the desktop app's Rich Presence relay, Discord activity sync, and
  importing a Discord server template).
- Never hardcode user-visible English. The web uses `t("key")` / `<Tr>` from `@/i18n`; Android uses
  `t("key")` on the same catalogues (its own keys start with `android.`). Add every new key to all
  twelve files in `web/src/i18n/locales/` with real translations
  (`python3 android/tools/add_strings.py strings.json` writes a batch into all twelve, in order).
  `python3 tools/i18n_check.py` must pass.
- Text about what Nexa stores or sends (the Terms, the privacy policy, settings) is checked against
  the code that runs (handlers, the SQL in `internal/store`, the client crypto), not comments. Say
  "not end-to-end encrypted" rather than "not encrypted" when the server seals something itself.

## Commands

Each line on its own, from the repository root. None of these write anything live.

```
tools/check.sh                    # the whole pre-publish check: Go build/vet/tests, web typecheck, tests
                                  # and a scratch build, app size, desktop format/check/tests, i18n keys
go build ./... && go vet ./...
go test ./...                     # `make test-go` adds -race; the api package then takes minutes
go test ./internal/api -run TestEditKeepsTheKeyThatOpensIt -count=1
CHAT_TEST_POSTGRES=postgres://... go test ./internal/store ./internal/api    # the same suites on Postgres
cd web && npm run typecheck
cd web && npx vitest run          # one file: npx vitest run src/lib/perms.test.ts -t "test name"
cd desktop/src-tauri && cargo fmt --check && cargo check && cargo test
python3 tools/i18n_check.py       # every locale has every key, placeholder and plural form
python3 tools/i18n_keys_used.py   # must say "web: ... 0 missing"; Android's keys built at runtime are listed, as expected
python3 tools/privacy_check.py   # private artifacts must not be tracked; --history checks all reachable revisions too
```

`make test` runs the Go suite with `-race`, the web suite and the desktop crate's tests. `internal/db`'s Postgres
tests start an embedded Postgres, downloaded the first time (`SKIP_PG_TESTS=1` skips them). For hot
reload, `cd web && VITE_API_TARGET=http://127.0.0.1:$PORT npm run dev` serves the web app on :5173;
without `VITE_API_TARGET` it proxies to :8090, the live server.

Building without deploying, into a scratch directory `$S`:

```
(cd web && npx vite build --outDir $S/dist --emptyOutDir)
go build -o $S/chatd ./cmd/chatd
```

Android (publishes nothing). On the deployment server the JDK and SDK live in `~/android` and are
not on the PATH. Set their locations in every command:

```
export JAVA_HOME=$HOME/android/jdk ANDROID_HOME=$HOME/android/sdk
(cd android && ./gradlew :app:assembleDebug)   # -PserverUrl=http://127.0.0.1:$PORT points it at a local chatd
(cd android && ./gradlew :app:testDebugUnitTest)
```

On this Mac, Homebrew's `android-commandlinetools` also contains the installed SDK, NDK and emulator.
Use `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` and
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` for the same Gradle commands.

Work that goes with certain changes:

- Dependencies added or upgraded in any build: `make licenses` rewrites `web/src/data/licenses.json`
  (Settings, Licenses) from npm, Go modules and the desktop's Cargo graph;
  `android/tools/android_licenses.py` does the same for the Android app (its header has the steps).
- The web app's first load grew: `tools/app_size.py` (in `check.sh`) fails once the gzipped first
  load passes `LOAD_KB_LIMIT` in `web/src/pages/HomePage.tsx`, the number the front page prints.
  Keep that claim true.
- The web's markdown, search, themes, formatting editor or webhook messages changed: regenerate the
  Android test vectors (`android/tools/*_vectors.ts`) and make the Kotlin port pass them.
  URL oracle entries depend on Node/ICU: verify a changed expectation against current Chromium
  as well before updating it, and never weaken host checks solely to match a stale vector.
- lucide-react upgraded: `python3 android/tools/lucide_assets.py`. emojibase-data or `@twemoji/svg`
  upgraded: `node android/tools/emoji_assets.mjs`, `tools/twemoji_sprites.py`, `tools/twemoji_font.py`
  and then `tools/twemoji_font_check.py`.

## Testing

### A throwaway server

chatd reads `chatd.env` from its working directory, and the repo's copy is the live configuration
(live keys and bot tokens, and `./data` is the live database). So run the scratch build from its own
directory with its settings in the environment (`$S` an absolute path, `$PORT` anything but 8090):

```
(cd web && npx vite build --outDir $S/dist --emptyOutDir) && go build -o $S/chatd ./cmd/chatd
(cd $S && LISTEN=127.0.0.1:$PORT PUBLIC_URL=http://127.0.0.1:$PORT DATA_DIR=$S/data \
  WEB_DIST=$S/dist EGRESS_PROXY=off ./chatd)        # in the background
tools/.venv/bin/python tools/seed.py http://127.0.0.1:$PORT
```

`seed.py` registers alice and bob (password `correct horse battery staple`), makes a server whose
#general bob joins, posts one message of every kind of formatting and prints the ids. Registration
is open by default, the first account becomes the owner (staff rank Developer, the oldest), and captchas, calls through the SFU,
GIF search and the other keyed services stay off without their keys.

### Web in a headless browser

Playwright runs from `tools/.venv` (setup in `tools/README.md`). Screenshots at both sizes:

```
tools/.venv/bin/python tools/screenshot.py http://127.0.0.1:$PORT $S/shots \
  --login "alice:correct horse battery staple" \
  --paths /channels/@me /channels/<server id>/<channel id> --viewports 1366x768 411x914
```

Widths under 768 get a mobile, touch context, and the app switches to its phone layout below 768 px.
For flows (clicking, typing, two people at once) script Playwright with `tools/e2e.py`'s `Session`
(`register`, `login`, `send`, `open_dm_with`, `shot`, ...) or run `tools/e2e.py` itself (`--flows`,
`--serve`). Set `E2E_WEB_DIST` and `E2E_CHATD` to the scratch build; otherwise e2e.py tests the
deployed `web/dist` and `bin/chatd`, not your changes. A new account in a new browser gets the Terms
sheet first; `Session` and `screenshot.py --login` agree to it. Stable selectors are the
`data-testid` attributes and aria labels the scripts already use. iPhone keyboard and viewport
behaviour needs `tools/ios_sim.py` on a Mac (`tools/README.md`).

### Android in the emulator

The AVD is `nexa_test`: a Pixel 7, 1080x2400 at 420 dpi, which is 411x914 for the web comparison.
It boots headless in about 30 seconds. On this Mac the equivalent AVD is `nexa36`;
install `app-arm64-v8a-debug.apk` there instead of the x86_64 APK.

```
ADB=$HOME/android/sdk/platform-tools/adb
(cd android && ./gradlew :app:assembleDebug -PserverUrl=http://127.0.0.1:$PORT)   # JAVA_HOME, ANDROID_HOME as above
$HOME/android/sdk/emulator/emulator -avd nexa_test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swiftshader_indirect       # in the background
$ADB wait-for-device                                # then poll until `$ADB shell getprop sys.boot_completed` is 1
$ADB install -r android/app/build/outputs/apk/debug/app-x86_64-debug.apk
$ADB reverse tcp:$PORT tcp:$PORT
$ADB shell am start -n fish.nexa.debug/fish.nexa.MainActivity
$ADB exec-out screencap -p > $S/android.png
$ADB emu kill                                       # when finished
```

- `-no-snapshot-save` throws away everything from the session, the installed app included, so
  install after every boot.
- Debug builds allow plain http only to 127.0.0.1, localhost and 10.0.2.2, hence the `serverUrl`
  on 127.0.0.1 plus `adb reverse`.
- Driving it: `$ADB shell uiautomator dump /sdcard/ui.xml && $ADB exec-out cat /sdcard/ui.xml` lists
  every element with its bounds; then `$ADB shell input tap X Y`, `input text` (`%s` for a space)
  and `input keyevent 4` (Back, which also closes the keyboard).
- Before trusting a screenshot, check `$ADB shell dumpsys window | grep mCurrentFocus` names
  `fish.nexa.debug`. After a cold boot, Play Services' "Sign in with ease" screen or a launcher
  "isn't responding" dialog can sit on top; dismiss it and start the app again.

### Other end-to-end runs

All start their own throwaway chatd: `tools/webhook_e2e.py` (every kind of webhook message, both
sizes), `tools/bot_e2e.py` (real bot libraries: the discord.py, discord.js and discordgo test bots
in `tools/bots/`, pointed at Nexa as the portal says, used by a person in a headless browser;
`BOT_E2E_PYTHON`, `BOT_E2E_NODE_MODULES` and `BOT_E2E_GOPATH` name where each library is),
`tools/apps_ui.py` (every app screen as a person uses it, at both sizes, into
`tools/out/apps-ui`, including `menu_on_a_picture`, `share-warning`, `app-link`, `staff-bots` and
`apps-used-options`;
`--no-content` tries an app without Message Content, and `--serve --port N` leaves the server and
its discord.py bot running, for the Android emulator), `tools/discord_e2e.py` (Discord activity sync against the fake Discord in
`internal/discordsync/fakediscord/cmd/fakediscord`), `tools/sfu_call.py` and `tools/sfu_guest.py`
(real calls and a guest through Cloudflare Realtime, keys from `chatd.env`), `tools/call_quality.py`
(calls over bad networks made by `tools/netsim`), `tools/share_quality.py` (a screen share of hard
motion through Cloudflare, timed frame by frame as the viewer sees it; the sharer is Chromium or
Firefox, `--up-kbps`, `--loss` and `--down-loss` shape the links through netsim, results in
`tools/out/share-quality`). Whatever a test starts must
make no sound: Playwright's headless Chromium is muted, Firefox is muted by the scripts, and the
emulator runs with `-no-audio`.

## Repository layout

```
cmd/chatd/          the server's main: wiring, the log file, the maintenance loop
cmd/chatmigrate/    copies a SQLite database into Postgres in one transaction, all or nothing
internal/           the server's packages (below)
web/                the web app: src/, public/ (manifest, icons, sounds, front-page media), scripts/deploy-guard.mjs
desktop/            the Tauri app
android/            the Android app; android/ydoc is the notes CRDT in Rust, android/tools its generators
downloads/android/  the published APKs and manifest.json (not in git: each server publishes its own), served at /android/
deploy/             systemd and launchd templates, install.sh, route-dns.sh, warp-proxy.sh
tools/              test drivers, release scripts, i18n and asset generators (below)
docs/               design spec and plans, STATUS.md, legal/ (the Terms and Privacy Policy the apps
                    show word for word), plugins.md (the plugin API), bots.md (making apps and
                    pointing bot libraries at Nexa), design/reference captures
audit/              code audits: findings, triage and progress per area; security-2026-10-26.md and
                    history-2026-10-26.md record current fixes, historical dispositions and limits
run.sh, backup.sh   what the service starts (bin/chatd -env chatd.env); the backup of data/
```

Not in git: `data/` (database, media, server secret), `logs/`, `bin/` (chatd and chatd.prev),
`backup/`, `chatd.env`, `web/dist`, `web/node_modules`, `tools/.venv`, `tools/out`, `.claude/`.

## Server (Go)

The minimum Go version in `go.mod` is 1.27.3, including the standard library's current
certificate and HTTP security fixes. Build checks use that minimum or a newer toolchain.

### Startup

`cmd/chatd/main.go` reads `-env` (default `chatd.env`; the process environment wins over the file),
loads `internal/config`, sets the snowflake node id, opens the database (`DATA_DIR/chat.db`, or
`DATABASE_URL`), runs the migrations, makes the store, the media store (`DATA_DIR/media`), the
server secret (`DATA_DIR/server.secret`) and the optional Redis cache, then builds `api.Server` and
attaches the egress proxy, link previews, Rich Presence apps, games and music. It makes the gateway
hub (`srv.Bcast`), calls `WireServers`, `WireVoice`, `WireActivities`, `WireDiscord` and last
`WireModeration`, mounts `/gateway`, starts the background loops (`StartLimiters`, `StartGuests`,
`StartModeration`, `StartPolls`, `StartThreads`, `StartDiscord`, `StartAccountSweep`, the WARP
keeper, the Realtime usage meter, `maintenance`) and serves until SIGINT or SIGTERM.

### Packages (`internal/`)

| Package | What it is |
|---|---|
| `api` | REST handlers, middleware, rate limits, static pages; one file per area (below) |
| `auth` | Argon2id of the client's auth key, bearer tokens (only SHA-256 hashes stored), the middleware that turns a token into a user or a guest, client IP behind the tunnel |
| `automod` | AutoMod matching (text read past disguises, words with wildcards, patterns, presets); `testdata/vectors.json` binds every implementation |
| `botproto` | what speaking the bot protocol of the libraries made for Discord needs: ids as they see them (Nexa's plus a fixed offset, so the time in them reads right), intents, the gateway's opcodes and close codes, JSON and ETF, zlib and zstd; it knows nothing of the store |
| `cache` | optional Redis: shared rate limits, presence and events between processes |
| `config` | settings from the environment with defaults and validation |
| `db` | SQLite or Postgres pools (`W` single writer, `R` readers), `Tx`, the dialect helpers, embedded forward-only migrations |
| `discordsync` | Discord activity sync: OAuth link, a bot in a small Discord server reads linked members' presence; `fakediscord` stands in for tests |
| `egress` | the SOCKS5 proxy (WARP) for every request a person can point the server at; fails closed |
| `games`, `music` | IGDB games and Deezer music for profile widgets; art kept here, previews streamed through |
| `gateway` | the WebSocket hub: connections, presence, typing, voice signalling, fan-out |
| `ids` | snowflakes: 41 bits of milliseconds, 10 of node, 12 of sequence; decimal strings in JSON |
| `media` | blob storage, upload validation, sized variants, metadata scrub, private and plain files |
| `perms` | Discord's permission model and bit values: roles, @everyone, overwrites, timeouts, hierarchy |
| `ratelimit` | keyed token buckets, optionally shared through Redis |
| `realtime` | Cloudflare Realtime SFU client and the monthly usage meter |
| `rpcapps` | names and art of Discord Rich Presence applications, looked up once and kept |
| `store` | every SQL query, grouped by domain; models; the permission cache |
| `stripe` | the few Stripe API calls plans need (prices by lookup key, Checkout, the billing portal, subscriptions) and webhook signatures; standard library only |
| `telegram` | the Bot API calls a sticker set import needs |
| `textutil` | cleaning user text, shared by REST and gateway |
| `totp` | RFC 6238 codes for two-factor sign-in |
| `unfurl` | link previews with SSRF protection (public addresses only, redirects checked, bodies capped) |
| `voice` | the in-memory registry of who is in which call and what each device publishes |
| `webauthn` | the server's half of passkeys: client data, authenticator data, COSE keys (a small CBOR reader) and ES256, EdDSA and RS256 signatures, standard library only; `webauthn/fake` is a software passkey for tests |

### HTTP API (`internal/api`)

- Everything is JSON under `/api/v1` with `Authorization: Bearer <token>`. Errors are
  `{"error": {"code", "message"}}`, built with `E(status, code, message)` and `WriteError`;
  `WriteJSON` and `ReadJSON(w, r, dst, maxBytes)` do the rest. `Handler()` chains `RequestID`,
  `Recover`, `SecurityHeaders`, `BodyReadDeadline` and `Logging`; `GET /api/health` answers `{ok, version}`.
- `BodyReadDeadline` bounds JSON bodies to 30 seconds and other bodies to fifteen minutes,
  including net/http post-handler draining. `SecurityHeaders` gives JSON/error responses a
  restrictive default CSP; document handlers replace it with their explicit page policy.
- Route groups are listed in `groups.go` (`routeGroups`); each file registers its own with
  `mux.Handle("METHOD /path", ...)`. A new group goes in that list.
- Wrappers: `authed` (token, the generic per-user limit, and the mute/ban gate), `callAuthed`
  (accounts and guests, for call routes), `guestAuthed`, `userLimited(limiter, h)` and
  `ipLimited(limiter, h)`. Every limiter is made in `NewServer` and named in `limiters()`; one
  missing from that map is never swept or shared.
- `moderation_gate.go` lists what a muted or banned account may still do. A new signed-in route is
  closed to them until it is added there.
- Group chat activity uses `system_member_add`, `system_member_remove`, `system_owner_change`,
  `system_name_change` and `system_icon_change` messages, rendered by both clients. Renaming to the
  same name or removing an absent icon posts nothing. Leaving through either DELETE channel or
  DELETE recipient accepts `?silent=true` to omit the caller's leave message; removing someone else
  always posts a message. Single and bulk leave confirmations offer an unchecked "Leave without
  showing a leave message" checkbox. Bulk close asks once only when groups are selected and applies
  the choice to every selected group.
- Areas, by file: `auth_handlers` and `mfa_handlers` (register, KDF parameters, login, recovery,
  password, sessions, TOTP and backup codes), `passkey_handlers` and `qr_login` (see Signing in
  below), `user_handlers` (me, encrypted settings, keys, search,
  profiles, deletion), `relationship_handlers`, `channel_handlers` (DMs, groups, icons, recipients,
  typing, attachments, pins), `group_handlers`, `subchat_handlers` (sub-conversations of a DM, with an
  optional password required for protected operations, including deletion), `message_handlers` (history, send, edit, delete, bulk delete, ack, reactions),
  `poll_handlers`, `key_handlers` (grants, needs, epochs, asking again), `server_handlers` and `discord_templates` (servers, Discord server template preview and import, channels,
  overwrites, roles, members, bans, invites, audit log), `member_admin` (member search, prune, bulk
  ban, server profiles, role icons), `threads` (threads and forum posts), `follows` (announcement
  follows and crossposts), `automod`, `discovery`, `server_tags`, `expression_handlers` (server
  emoji, sticker packs, recents, Telegram import, Klipy GIF search), `voice_glue` and `sfu_handlers`
  (ICE servers, voice states, ringing, the SFU), `guest_handlers` (guest links), `webhook_*`,
  `note_handlers`, `sound_handlers`, `widgets` (profile widgets, game and music search),
  `typing_style`, `name_style`, `profile_links`, `discord_handlers`, `activity_glue`, `nexa` (a member's side of
  Nexa) and `moderation*` (the staff's side), `bugs`, `crash_handlers` (client errors),
  `admin_handlers` (instance stats, users, runtime settings), `billing` (plans, see Plans and
  payments below), `legal_handlers` (the admin's legal
  report), `desktop_handlers` and `android_handlers` (downloads and update manifests),
  `media_handlers`, `upload_handlers` (resumable uploads), `proxy_handlers` (the signed media proxy),
  `youtube_proxy`, `unfurl_handlers`,
  `static` (the SPA).
- Sign-up is limited to two new accounts a day per IPv4 address or IPv6 /64 (`limSignup`, spent only once every
  other check passed, never for the first account; `too_many_signups`), on top of `limReg`'s ten
  attempts an hour. `Store.CreateRegisteredUser` decides the first account's Developer flag,
  and the initial `ADMIN_USERNAME` exception, inside the inserting transaction under a shared
  bootstrap lock, so concurrent sign-ups cannot each become the first administrator.
  KDF, login and recovery refuse normalized usernames longer than 32 bytes before lookup or
  username-keyed limiter admission. Visitor limits trust only valid `CF-Connecting-IP` from a
  loopback proxy, never `X-Forwarded-For`; other requests use their socket peer.
- What a script would turn on other people or on paid services has a limit of its own, on top of
  the generic 600 a minute: reactions (`limReact`), messages and edits across every channel
  (`limMsgAll`, apps excepted, beside `limMsg` per channel), searching people, GIFs and games and
  the activity preview (`limSearch`), the lookups anyone can make without signing in (invite
  previews, profile links and KDF parameters, `limLookup` per address), and making channels,
  roles, invites, sticker packs, subchats, guest links, note links, follows and group DM
  additions (`limBuild`). The last two are step-up limits (`stepup.go`, `stepUpUser`,
  `stepUpIP`): past the free allowance the route asks for a captcha (action `lookup` or `build`),
  the apps solve it as for any guarded action, and a solved one buys a batch (100 lookups for ten
  minutes, 30 more made for an hour), with the captchas themselves limited (ten an hour per
  address, ten a day per account) so there is still a ceiling. With captchas off they are the
  plain free limits. Batches are kept in memory, per process. A server holds at most 500 channels and 250 roles
  (`store.MaxServerChannels`, `MaxServerRoles`, counted inside the creating transaction).
- Captchas (`captcha.go`, hCaptcha in its invisible size, off until both keys are set):
  `requireCaptcha(w, r, action)` guards what a script would abuse (sign-up, sign-in, recovery, friend requests, a first DM
  to a stranger, creating and joining servers, creating apps, joining notes and guest calls, bug reports,
  Discovery and public sticker packs, Telegram imports) and every password check and username change. A
  refusal names the action in `X-Captcha-Action`; the web's `api.captchaSolver` (`lib/captcha.ts`)
  and Android's `Api.captchaSolver` (set by `Auth.solveCaptcha`) solve it and send the request
  again, so a guarded call needs nothing of its own. Only one captcha is on screen at a time (web
`captchaToken`, Android `CaptchaHost.token`): the same action asked for again while its captcha is
up gets none (`captcha_pending`, "finish the one already open"), another action's waits for it (a
token is good for one request), and cancelling one cancels those waiting behind it. A control whose
request may meet a captcha is disabled until that request ends (the Public Pack switches). hCaptcha binds no action to a token, so the
  server checks only that it passed and was solved on one of Nexa's own hostnames (the sitekey's
  allowed domains in the hCaptcha dashboard should list the same ones); with hCaptcha's published
  test secret the hostname is not checked. The web's sheet (`lib/captcha.ts`) dims the page while
  hCaptcha's challenge is open and takes its theme from `data-scheme` on the root, which
  `lib/theme.ts` sets; closing the challenge cancels. Both render only once hCaptcha's script calls
  the function named in its `onload` (rendering when `window.hcaptcha` first appears is refused).
  Android's (`ui/auth/Captcha.kt`) runs it in a web view on the instance's own address and closes
  the keyboard when a challenge opens. Which challenges people get is the sitekey's setting in the
  hCaptcha dashboard; the test sitekey `10000000-ffff-ffff-ffff-000000000001` never shows one, so a
  real challenge can only be seen with the real keys on a throwaway server. From 1.2.4 the desktop
  app frames `hcaptcha.com` and its subdomains (`isFrameableUrl`), and still Turnstile's
  `challenges.cloudflare.com`, so an app updated before its server moved keeps working; 1.2.3 and
  older cannot show hCaptcha at all.
- Apps and bots (`bot_*.go`, guide for bot makers in `docs/bots.md`): the Developer Portal
  (`bot_portal`, `/api/v1/applications`), OAuth2 and installs (`bot_oauth`: the consent page's
  `/api/v1/oauth2/authorize`, Authorized Apps, a server's integrations and who may use their
  commands), people using apps (`bot_interactions`: `POST /api/v1/interactions`, files sent with
  one, a channel's commands, dismissing an answer only one person sees; the first follow-up after a
  type 5 defer fills in the thinking message rather than making a second one, and a message
  command's sealed copy is kept with `Store.KeepBotCopy` so the bot can fetch its files). An answer
  to a slash command keeps the submitted option values in the user-facing `webhook.interaction`
  (`wmInteraction`) for the clients' invocation tooltip; the bot protocol's `interaction_metadata`
  is unchanged. The bot API itself:
  the REST side of the bot libraries' protocol under `/api/v6` to `/api/v10` (`bot_auth` mounts it,
  taking `Authorization: Bot <token>` or an OAuth2 `Bearer` token, with per-route buckets, a global
  limit and the protocol's error shapes; `bot_rest_*`, `bot_message_routes`, `bot_commands`,
  `bot_modals`, `bot_audit`), its gateway at `/bot-gateway` (`bot_gateway`; events translated and
  filtered by intent in `bot_dispatch`), files and pictures at `/cdn/...` (`bot_cdn`), and the
  objects it speaks (`bot_objects`, `bot_messages`). Voice channels keep protocol type 2 and
  support text chat through the normal message routes without joining a call; fetching their
  messages also requires Connect. `tools/bots/dpy_bot.py` and `tools/bot_e2e.py --only dpy`
  exercise voice chat with discord.py, including REST, gateway events and a person's encrypted
  message and the bot's reply. Where the app has a route for the same thing,
  the bot route runs that route's code as the bot (`callNexa`), so a bot is held to a person's
  rules and everyone sees the same events. A bot is a user with flag 64 (128 when its app reads
  every message); an app's id is its bot's. Interactions and app events go to an app's own
  address through the egress proxy, signed with the app's Ed25519 key. Server Settings,
  Integrations lists apps for Manage Server and webhooks for Manage Webhooks.
- Webhooks are used with their token at `/api/webhooks/{wid}/{token}` and at the versioned paths
  Discord's libraries build (`/api/v1`, `/api/v6` to `/api/v10`), with `/slack` and `/github`
  variants, taking Discord's JSON. Links in webhook and app messages get server-side previews
  (`unfurlWebhookMessage`, skipping app install links).
- `static.go` serves `web/dist`: hashed assets immutable, `index.html` for any other path, and its
  own page heads (for link previews elsewhere) at `/invite/{code}`, `/call/{code}`, `/note/{token}`,
  `/@{username}` and `/`. The Content-Security-Policy is there too (plugins load from `blob:`,
  hCaptcha from `hcaptcha.com` and `*.hcaptcha.com`).
- `ChannelAccess`, `ConvForChannel`, `ServerChannelPatch` and `CanRotate` on `Server` are hooks the
  server-channel code (`server_glue.go`) installs; DM access is built in.

### NSFW communities

`servers.nsfw` is the whole-community toggle in Server Settings, Overview (migration 0040
on SQLite and Postgres). Its compact NSFW toggle sits below the ordinary Overview settings,
with its description below the label and switch. Discovery instead uses a compact warning-icon
NSFW switch beside its regular filters, with
no separate NSFW filter button or popular-tag shortcut. Whole-community NSFW listings have an NSFW
badge beside their name; searching `nsfw` still finds classified content after opting in. Channel
`nsfw` flags stay independent. A channel's settings recommend
marking the whole server only if most of its content is NSFW. The Terms permit NSFW communities,
require that classification for majority-NSFW servers, and allow a few NSFW channels without
classifying the whole server when its public-facing details contain no NSFW content.

Discovery excludes both kinds by default, including category counts and popular tags. `nsfw=1`
opts in after an 18+ confirmation; `nsfw_only=1` filters those results to servers marked NSFW or
containing any NSFW channel, including hidden or archived channels. The reserved `nsfw` tag is
derived from that classification, does not use a custom-tag slot, and cannot be assigned to a safe
server by editing its tags. Case-insensitive `nsfw` or `#nsfw` search words and `tag=nsfw` apply
that filter; other search words still match normally. Searching never bypasses the opt-in.
Cards distinguish `nsfw`
(the whole community) from `nsfw_content` (either kind).

Only whole-community NSFW servers gate invite previews and invite/Discovery joins with `nsfw=1`.
An unconfirmed invite preview returns only its code, `nsfw: true` and `age_gate: true`; link
unfurls and page metadata reveal no server details. The public bot-protocol invite lookup
requires the same confirmation before returning any invite metadata. Web and Android keep the per-invite
confirmation in memory, scoped to the current account. Public media URLs remain public.
The moderation panel has a separate Discovery tab at `/channels/@admin/discovery`, for Admin
and higher, using the existing `/moderation/servers?discovery=all|listed|held` endpoint. It searches
and paginates listings and staff holds before returning results, with matching totals, and reuses
the existing audited hide/restore actions and owner cases on web and Android.

Whole-community NSFW servers cannot create or wear guild tags; turning the server toggle on
removes the existing tag from its wearers. A few NSFW channels alone do not hide public details
or block guild tags. Both clients use the same catalogue strings and Terms version.

### Plans and payments (`api/billing.go`, `internal/stripe`)

- Supporter ($5 a month, $48 a year) and Pro ($10, $96) only raise what costs money to keep:
  `planLimitsFor` gives each plan's largest file (1 GB free, 2 GB and 4 GB, whatever `MAX_UPLOAD_BYTES` says: that sizes only the files no plan covers, from webhooks, apps and notes),
  uploads per 30 days (5 GB, 100 GB, 500 GB: a rolling window counted from the account's
  attachment blobs and declared unfinished uploads, `Store.UploadedSince`, refused with
  `upload_month_full`), the Quality screen share and its most bits a second (6, 12, 20 Mbit/s),
  the paid name effects (Holo, Neon, Flame: the server refuses to save one without a plan,
  `plan_required`, and sends an account without a plan its first colour as solid,
  `store.styleForPlan`) and Pro's two server boosts (a boosted server lets every member send
  files up to 2 GB, `fileMaxFor`). Chatting, calls and everything else stay free.
  `users.plan` is public, as a badge.
- Stripe Managed Payments is the merchant of record. Prices are found by lookup key
  (`nexa_supporter_monthly`, `_yearly`, `nexa_pro_monthly`, `_yearly`, and the one-time
  `nexa_supporter_gift_month`, `_year`, `nexa_pro_gift_month`, `_year`) and kept for a while.
  `POST /billing/checkout` and `/billing/gift` answer a Checkout address, `/billing/portal` the
  portal's, and `GET /billing` the account's side (limits, the 30 days' use and when room comes
  back, boosts, gifts, prices). `POST /billing/webhook` checks Stripe's signature and always reads
  the subscription again from Stripe, so repeated or out-of-order events end the same way; an
  older subscription ending never overrides a newer one.
- An account wears the better of its subscription (`billing`) and a running gift (`plan_gifts`,
  a month of 30 days or a year of 365, paid once; another gift of the same plan starts when the
  last ends; never to someone either side blocked). A gift can also be bought as a link
  (`POST /billing/gift` with `link: true`, table `gift_links`, made by the webhook once paid): the
  buyer copies it from Your Gift Links, and whoever opens `/gift/<code>` first claims it
  (`GET /gifts/{code}`, `POST /gifts/{code}/claim`, which adds the `plan_gifts` row; web
  `pages/GiftPage.tsx`, Android `openGiftLink` in `ui/support/Support.kt` through `Nav.openPath`). `recomputePlanTx` writes `users.plan` and drops
  the boosts of an account that is no longer Pro; `StartBilling` ends gifts as they run out.
  Every change emits `user_update`, `server_update` for servers that lost a boost, and
  `billing_update` to the account. Deleting an account cancels its subscription
  (`endSubscription`) and deletes its plan records.
- Without `STRIPE_SECRET_KEY` plans cannot be bought (`billing_off`) and the apps say so; the
  limits still hold. Web: `lib/plans.ts`, `stores/billing.ts`, `stores/upsell.ts` (`offerPlan`,
  the dialog when a limit stops something), `components/support/` (the page at
  `/channels/@me/support` and Settings, Subscription; `Upsell.tsx` with the once-per-release
  Support Nexa prompt, kept as `supportPromptSeen` in the settings; `MonthMeter.tsx` over the
  composer's files). Android: `data/Plans`, `ui/support/Support.kt`.

### Forwarding

A message is forwarded to one conversation or channel at a time (`body.forward` on send, web
`lib/forward.ts` and `modals/ForwardModal.tsx`, Android `data/Forward` and
`ui/chat/ForwardSheet.kt`). The server checks the source as a reader would (same channel, a
person's default or reply message, Read Message History, unlocked), lets the new message carry
the source's files without owning them, so nothing is uploaded again and no allowance is spent,
clears its mentions, sets flag 1 << 14 and limits forwards on their own (`limForward`). The
payload's `fwd.at` is when the original was sent, drawn as Forwarded with a bar down its side.
The forwarding sheet accepts an optional message, kept as `fwd.comment` inside the same encrypted
payload and drawn above the forwarded block. Both clients enforce the ordinary 4000-character
text limit on the comment and include it in search, copying, notifications and AutoMod checks.
Android draws the forwarded bar after normal layout, without intrinsic measurements of the
message body: video players and post previews use BoxWithConstraints, which cannot answer
intrinsic height queries and previously crashed when a forwarded message was viewed.

### Voice messages

A voice message is an ordinary encrypted audio attachment whose meta says `voice: true`, with
`duration` and `wave` (base64, a byte of loudness per bar, 48 bars; both apps accept at most 96
bytes, and only on `audio/` files). The shape travels in the message, so the bubble is drawn whole
before the file comes down; it is drawn with 32 bars (`SHOWN_BARS`). The server knows nothing of
it. While the box is empty (no words, files or command) a mic takes the send button's place, for
anyone with Attach Files. On desktop web, Show Send Message Button keeps a disabled Send beside
the mic until there is something to send. The text menu's useTextMenu keeps the switch object
and its latest checked state and callback, rather than wrapping that object as a function.
The mic opens a bar laid over the box that records at once (red dot, time,
live shape), Stop gives a review to listen back, Send goes from either, the bin, Escape or Back
throws it away. At most ten minutes, under half a second is dropped. The web records with
MediaRecorder from the microphone chosen in Voice & Video, AAC in MP4 where the browser makes it,
else Opus in WebM or Ogg (`lib/voiceMessage.ts`, `composer/VoiceRecordBar.tsx`,
`chat/VoiceMessage.tsx`, `Staged.voice` in `lib/attachments.ts`); Android records AAC in MP4 with
AudioRecord and MediaCodec (`data/VoiceMessages`, `ui/chat/VoiceRecordBar.kt`, `ui/media/VoiceMessage.kt`,
`Staged.voice` in `data/Send.kt`).
Recordings use 48 kHz mono and 128 kbit/s. The web's `voice/recordingInput.ts` applies the call's
neural filter, a high-pass filter, peak compression and headroom, with capture AGC off and no input
volume boost. Android uses AudioRecord with VOICE_RECOGNITION, optional platform echo cancellation,
no AGC, a separate RNNoise state and peak control (`data/RecordingDsp`, `cpp/denoise_jni.cc`), then
MediaCodec AAC and MediaMuxer. It never shares filter state with a call.

Transcribe below a recording runs Whisper Tiny multilingual locally. The web and desktop use
`lib/transcribe.ts` and a disposable `transcribe.worker.ts`, Transformers.js 4.3.1, quantized ONNX
weights and a bundled single-threaded WASM runtime, without needing cross-origin isolation. A
separate first-window language-token pass avoids the library's English default. Native Android
uses `data/Transcripts`, MediaCodec decoding and whisper.cpp 1.9.5 through `cpp/whisper_jni.cc`.
CMake pins its source archive by SHA-256. Its 77.7 MB Tiny weights download on first use into
noBackupFilesDir and are verified by size and SHA-256 before an atomic rename. The model files
are committed directly in `web/public/models/whisper-tiny/` (no Git LFS).
Vite copies them into the web build, served at `/models/whisper-tiny/`; Android downloads
`ggml-tiny.bin` from its configured Nexa server and the browser uses the library's model cache.
The web worker sets `localModelPath` to `/models/` and disables remote models, so a missing
file fails without falling back to Hugging Face. Missing `/models/` files return 404, never
the SPA page. `manifest.json` records pinned upstream URLs, sizes and SHA-256 hashes;
`LICENSE` preserves the Whisper MIT notice. Trusted static JavaScript and module responses carry
the app's
CSP, because a worker enforces its own script response policy and the default deny policy
blocks its model download. Both clients show download percentages only after Transcribe is
chosen, then working, failure and no-speech states; there is no idle transcription hint. They
support cancellation and release inference resources. Audio and transcript stay on the device;
transcripts are held only in the open message component's memory.

### Gateway (`internal/gateway`, `web/src/gateway`)

- One WebSocket at `GET /gateway`. The client sends `{op, d}`, the server `{t, s, d}`. The first
  frame is `identify` with the token, answered by `ready`: `session_id`, `user`, `users`,
  `channels`, `relationships`, `read_states`, `presences`, `key_grants`, `settings`, `servers`,
  `emojis`, `sticker_packs`, `voice_states`, `group_add_notices`, `guest`, `nexa`, and `now` (the
  server's clock as it went out; web `lib/serverClock.ts` and Android `data/ServerClock` keep how
  far the device's clock is off, for running clocks of server times).
- Client ops: `identify`, `heartbeat` (every 25 s, answered by `heartbeat_ack`), `presence`,
  `typing`, `voice_state`, `voice_signal`, `subscribe_members`, `activities` (the desktop app's
  Rich Presence), `away`, `note_cursor`. The server also pings every 12 s at the protocol level.
- Close codes: 4001 session revoked (the client signs out), 4003 banned, 4008 rate limited,
  4009 too many connections. A connection may send 20 ops a second, in bursts of 100.
- Both gateways reserve unidentified connections through the hub's admission budget before
  upgrading. The bot gateway releases its reservation after identify or resume, or on failure;
  it cannot bypass the main gateway's waiting, address and total connection limits.
- Events are the `Ev*` constants in `protocol.go`: messages, reactions, typing, channels and their
  positions, servers, members, roles, presence, users, relationships, read states, voice and calls,
  keys (`key_grant`, `keys_needed`, `key_rotation_needed`), emoji and stickers, pins, bans, invites,
  webhooks, polls, settings, threads, follows, AutoMod, the audit log, notes (`note_*`), Nexa and
  moderation (`nexa_*`, `mod_*`), apps (`application_commands_update`, `integrations_update`, and
  `reaction_remove_all` and `reaction_remove_emoji` for what bots clear), guests and sessions. An
  app's answer only one person sees is a `message_create` with flag 64 to that person alone, and
  goes with a `message_delete` marked `ephemeral`.
- A bot's gateway session is a connection of its account on the hub like any other
  (`gateway.External`, `external.go`), whose frames go to a callback that `bot_dispatch` turns
  into the protocol's events.
- Handlers emit through the `Broadcaster` interface in `api/router.go` (`EmitUsers`, `EmitChannel`,
  ...); the hub sends an event only to members allowed to see it (`store/permcache.go` answers that)
  and encodes it once for all of them.
- A new event touches: the constant in `protocol.go`, the emit in the handler, its type in
  `web/src/gateway/events.ts`, its handling in `web/src/gateway/dispatch.ts` (which updates the
  stores), and the same on Android (`fish.nexa.data.Session`).
- What follows the app without being part of it (plugins, the search index, notifications) listens
  through `dispatchHooks` in `dispatch.ts`, each hook called inside a try/catch (`each`), so a
  listener that throws stops nothing. `dispatchHooks.onEvent` takes the `AppEvent`s of
  `gateway/appEvents.ts`, each fired once the stores have the change, with what was there when
  something goes: reactions, typing started and stopped, members joined, updated and left, servers
  joined and left, channels and threads created, updated and deleted, user and presence updates,
  relationship changes and the language changing (`onLocaleChange` in `i18n`, once the new
  language's words have loaded). Servers, relationships and typing are told by watching their
  stores, since they also change without an event or arrive twice (a REST answer and its event);
  `ready` and sign-out fill and empty the stores inside `quietly`, and after a reconnect's `ready`
  `reportMissed` tells what changed while the connection was down. A deleted message cannot be
  kept on screen by a listener (left for a later update).

### Database (`internal/db`, `internal/store`)

- Every query lives in `internal/store`; handlers never touch `database/sql`. Queries are written
  once with `?` placeholders; `internal/db` rebinds them for Postgres, and `db.Dialect` covers the
  few places the two differ (JSON arrays, greatest/max, case folding with the `ulower()` the store
  registers on SQLite, unique violations).
- Ids are snowflakes, times Unix milliseconds, booleans 0 and 1, JSON kept as text.
- SQLite is live and Postgres must keep working: a new migration goes in both
  `internal/db/migrations` and `internal/db/migrations-postgres` with the same number (Postgres
  starts at `0013_init.sql`). Migrations are embedded, forward only, one transaction each.
  `tools/gen_pg_schema.sh` prints the Postgres schema the SQLite migrations add up to, to diff
  against. chatd refuses to start on a database a newer build has migrated, so a release with a
  migration cannot roll back to `bin/chatd.prev`.
- The tables, by area: accounts (`users`, `sessions`, `user_ips`, `user_settings`, `user_mfa`,
  `mfa_backup_codes`, `passkeys`, `user_sounds`, `username_holds`), people (`relationships`), conversations
  (`channels` for every kind, `dm_participants`, `channel_overwrites`, `channel_unlocks`,
  `thread_members`, `channel_follows`, `crossposts`), messages (`messages` with `ciphertext`, and
  `plain` for webhook and system text; `attachments`, `reactions`, `pins`, `read_states`, `polls`,
  `poll_votes`), keys (`key_epochs`, `key_grants`, `key_flags`), servers (`servers`,
  `server_members`, `roles`, `member_roles`, `invites`, `bans`, `audit_log`, `automod_rules`,
  `automod_events`), plans (`billing`, `server_boosts`, `plan_gifts`, `users.plan`,
  `servers.boosts`, `gift_links`), expressions (`emojis`, `sticker_packs`, `stickers`, `sticker_installs`,
  `recent_stickers`, `blocked_images`), files (`blobs`, `uploads`), calls (`guest_links`, `guests`,
  `realtime_usage`), integrations (`webhooks`, `discord_links`), notes (`notes`, `note_members`,
  `note_invites`, `note_links`, `note_files`, `note_versions`, `note_version_files`, `note_keys`,
  `note_updates`, `note_uploads`), Nexa and moderation (`mod_cases`, `mod_messages`,
  `mod_message_files`, `mod_restrictions`, `mod_announcements`, `mod_log`, `mod_reads`, `nexa_reads`,
  `bug_reports`, `bug_attachments`, `bug_messages`, `bug_message_files`, `client_errors`), apps
  (`applications`, `application_emojis`, `application_commands`, `application_command_permissions`,
  `application_guild_installs`, `application_user_installs`, `oauth_codes`, `oauth_tokens` (a row
  goes when its refresh token does, 90 days unused), `interactions` and `ephemeral_messages` (15
  minutes), `ephemeral_files`, `interaction_files` (a day), `bot_copies`, and `roles.managed_by` for
  the role a bot brings), the instance (`instance_settings`).
- Channel types: `dm`, `group_dm`, `subchat`, `text`, `voice`, `category`, `announcement`, `forum`,
  `thread`, `private_thread`. A channel's `key_conv_id` names the conversation whose keys encrypt
  it: itself for DMs and private channels, the server otherwise.
- `cmd/chatmigrate` copies a SQLite database into Postgres (`DATABASE_URL`).

### Permissions

`internal/perms` is Discord's algorithm with Discord's bit values. `web/src/lib/perms.ts` mirrors it
(BigInt, decimal strings on the wire), and so does Android's `fish.nexa.data.Perms`. Use
Application Commands (1 << 31) and Use External Apps (1 << 50) are for apps, and @everyone has
both. Server channel
access goes through the hooks `server_glue.go` installs, answered from `store/permcache.go`.

How each permission is held, as Discord holds it:

- Categories sync as Discord's do (`api/category_sync.go`): a channel is synced with its category
  when their overwrites are the same (`sameOverwrites`; nothing else records it). A channel made in
  a category starts with its overwrites, an overwrite written or removed on a category is written
  or removed on every channel synced with it, and a channel moved into another category (positions
  `PATCH`, with Discord's optional `lock_permissions`) takes the new category's overwrites when it
  was synced where it came from (or had none outside a category) and the mover has Manage Roles
  there. `POST /channels/{id}/permissions/sync` is Sync Now. A channel that becomes hidden from
  @everyone this way is flagged to get its own key (`markIfPrivate`); create and sync answer with
  `key_actions` so the actor's app makes it at once. The apps show a channel's state on its
  Permissions page (web `ChannelSettingsModal` `.syncBar`, Android `ChannelPermissionsPage`) with
  `sameOverwrites` of their own (web `lib/perms.ts`, Android `Perms`).
- Editing a channel's overwrites needs Manage Roles in it (`channelManageCtx`), not Manage Channels.
- Embed Links: link previews are made by the sender's app and sealed in the message, so the server
  sets Suppress Embeds (flag 4) on any message, new or edited, whose author lacks Embed Links there
  (`embedFlags`), and refuses to clear it while they still lack it. Every app already hides a
  flagged message's previews; the composers do not fetch previews there.
- Add Reactions is needed only for a new reaction; joining one already there, like every reaction,
  needs Read Message History. Without Read Message History a channel's history answers an empty
  list, so only what arrives live is seen.
- Video (Stream): a voice state without it never says `video` or `streaming`, and the SFU refuses to
  publish or pull a camera or a share (`mayStream` in `sfu_handlers.go`, asked at every request).
  The apps refuse to start one and stop one that loses it (web `voice/callPerms.ts`, Android
  `voice/CallPerms`). The peer-to-peer mesh cannot be held by the server.
- What the server cannot see is held by every reader's app: Use Voice Activity (the call switches
  to push to talk in a channel without it, `pushToTalkOn`), and Use External Emoji and Use External
  Stickers in messages, which draw as their `:name:` when the author lacks them in the channel
  (web `lib/authorPerms.ts` with `RenderCtx.plainEmoji`, Android `data/AuthorPerms` with
  `RenderCtx.plainEmoji`; an author whose roles are not loaded counts as allowed).

### Media and uploads (`internal/media`)

- Files are content-addressed on disk under `DATA_DIR/media`, described by the `blobs` table.
- Public kinds (`avatar`, `banner`, `icon`, `emoji`, `sticker`, `tag`, `widget`, `typing`) are
  served at `/media/{kind}/{id}`, immutable, and at a requested size as a variant made once (the next
  power of two). An animation keeps moving at any size: one of 512 px or less is served as it is, a
  larger GIF gets every frame composed and shrunk (`animated.go`), and a WebP or APNG is served as
  uploaded; `?still=1` asks for the first frame. Sized avatar, banner and icon addresses carry
  `&v=3` (web `api/users.ts`, Android `Media.kt`) so caches drop earlier still or incorrectly
  oriented copies. JPEG crops and sized variants (`orientation.go`) apply all eight Exif transforms before cropping
  or shrinking; variant filenames carry an orientation version too. Android applies the same
  transforms (`media/PhotoOrientation`) when making attachment thumbnails and crop previews,
  and measures the displayed dimensions. The kind in the address must match the stored one, and a picture the staff blocked
  is served to nobody.
  Appearance has independent synced `animateAvatars`, `animateIcons` (servers, channels and groups),
  `animateBanners`, `animateEmoji` and `animateStickers` settings, all on by default. Public URL
  helpers request `?still=1` for disabled categories; the renderers subscribe to the preferences.
  Lottie and WebM stickers pause locally too. These settings are independent of Reduce Motion.
- Attachments are opaque encrypted blobs, served only to people in the conversation
  (`/media/attachments/{id}`). Files of bug reports and case threads are private to their people
  and the staff. Webhook files are plain.
- The server strips hidden metadata from the pictures and videos it keeps readable (`scrub.go`);
  the apps do the same before encrypting (web `lib/scrubMetadata.ts`, Android `MetadataScrub`), all
  checked against `internal/media/testdata/scrub`.
- A server channel can wear an uploaded picture in place of its # or speaker: `PUT` and `DELETE
  /channels/{id}/icon` (`iconChannel` in `channel_handlers.go`: a group DM's owner, or Manage
  Channels in a server channel) keep it in `channels.icon_id` as for a group's icon (kind `icon`,
  256 px). Uploading clears the channel's glyph; a glyph saved afterwards (an icon, an emoji or the
  default) puts the picture away, so the picture always wins when both are set (web
  `ChannelGlyph`, Android `ChannelGlyph` in `ui/shell/Sidebars.kt`). The channel icon picker
  uploads at once (Upload Picture, cropped square) and edits the picture as the value `picture`.
- Small files go up in one request (`POST /channels/{id}/attachments`); big ones through
  `/api/v1/uploads` in pieces (Cloudflare caps a request at 100 MB), with disk headroom and
  per-account caps (`roomForUpload`). `reserveUpload` serializes admission and counts bodies still
  arriving against quota until their database records exist. Temporary chunks have their own
  disk reservations, at most sixteen staged together and two per transfer, so a stalled request
  can be retried without allowing unlimited copies of the same offset. These reservations live
  in one chatd process, as the deployment model requires.
- Web uploads (`lib/attachments.ts`) and Android uploads (`data/Send.kt`) prepare one following
  piece while the current request is in flight, so reading and encrypting the next piece does
  not add a full pause after every acknowledgement. Memory holds the active request and at most
  one prepared piece, regardless of file size. Requests still land sequentially at the confirmed
  offset, with the same Cloudflare-safe limits and retry protocol. `encryptChunks` on both clients
  writes frames directly into the request buffer; a partial retry sends a view or offset rather
  than copying its remaining bytes. Web workers receive the view's offset and length with the
  transferred buffer, and regenerate it if the worker fails before starting.
- Streaming downloads read ahead by at most one encrypted chunk during decryption and disk writes:
  web `crypto/streamDecrypt.ts` validates the next frame's length before reading its ciphertext;
  Android `media/Media.kt` and `media/Downloads.kt` use `media/TransferStreams.kt`, a bounded
  pipe that closes its producer and source on failure or cancellation. Authentication and the message's expected file size still govern what
  is saved. Transfer tests cover bounded preparation/read-ahead, transferred-buffer retries,
  partial offsets, file integrity, cancellation and network/disk errors.
- Saving attachments and pictures asks for a location: Android uses CreateDocument on every
  version, the desktop uses its webview's download prompt, and web `lib/saveFile.ts` opens
  showSaveFilePicker before fetching where supported. Large encrypted browser saves stream the
  download worker's transferred ReadableStream into that file. Mobile browsers with file sharing
  use the system sheet (including Save to Files); if loading outlives the tap's activation, a ready-file dialog
  provides the fresh tap. Other browsers retain their own download behavior and settings.
  Cancelling a picker saves nothing; failed writes abort or remove the partial file.
- Before a GIF is decoded in full for cropping or an animated variant, `gif_budget.go` walks its
  compressed blocks and checks frame count and canvas work. Limits checked after `gif.DecodeAll`
  would let a small compressed file exhaust memory before it was refused. Cropping keeps the
  existing 600-frame and 150 Mi-pixel budgets; resizing also caps frames at 600 and falls back to
  the uploaded animation when it exceeds the resizing budget.

### Outbound requests

Everything a person can make the server fetch goes through `internal/egress` (WARP's SOCKS5 proxy,
`EGRESS_PROXY=warp|off|socks5://...`): link previews (`unfurl`, asked for by the sender's app,
which puts the preview inside the encrypted message), the media proxy (only addresses the server
signed; `POST /proxy/refresh` re-signs old ones), the YouTube player (`/yt/...`), IGDB, Deezer,
Discord's Rich Presence apps and Discord server templates (`GET /servers/templates/discord/{code}`
and `POST /servers/import/discord`). Discord's gateway, Cloudflare Realtime and the tunnel go out directly.
Public guarded fetches allow only HTTP port 80 and HTTPS port 443, checked again at dialing.
The public-address guard also checks embedded IPv4 addresses in standard NAT64 and 6to4
addresses, and refuses local-use translation, Teredo and IPv4-compatible ranges.

### Calls

Enhanced noise suppression uses Xiph RNNoise commit
`70f1d256acd4b34a572f999a05c87bf00b67730d` on web and Android, with the same newer model.
The web loads `@shiguredo/rnnoise-wasm` 2025.1.5 in `voice/denoise.worklet.ts`; native CMake fetches
both source and weights under verified SHA-256 hashes. The probability gate uses hysteresis,
20 ms of look-behind, a 200 ms speech tail and smooth gain changes to suppress residual noise
without cutting consonant onsets (`voice/speechGate.ts`, native `SpeechGate`). Failed worklet
initialization falls back to browser suppression. Echo cancellation stays before neural filtering;
Android selects WebRTC software AEC3 rather than the vendor hardware effect and keeps the
existing per-track echo toggle. Noise suppression and echo cancellation remain independently
controllable. Physical acoustic results still depend on the microphone, speakers and room.

SFU requests serialize mutations per user. Publication binds its kind to the SDP media section
and exact mid, refuses duplicate kinds/mids, and ICE restart reuses its recorded mid. Pulling the
same source again reuses its existing subscription. Mute/deafen/Speak/Video restrictions retire
existing provider sessions; `sfu_policy.go` rechecks after upstream negotiations and permission
changes. Voice states carry `sfu_generation`, which both new call clients use to rebuild permitted
media after policy changes. Older clients may need to rejoin. Camera/share off closes publication.
Accounting follows actual tracks and confirmed provider closures; failed closures stay charged.
Session closure retries transient failures, logging exhausted retries as errors. Provider outages
can delay moderation, and the monthly meter is an estimate rather than a hard billing cap.


`internal/voice` tracks who is in each call. Without Realtime keys calls are peer to peer (a mesh,
signalled over the gateway's `voice_signal`); with `REALTIME_APP_ID`/`REALTIME_APP_SECRET` every call
goes through Cloudflare's SFU, with chatd as its signalling proxy (`/api/v1/voice/sfu/*`) deciding
who may pull which track. The usage meter pauses video near `REALTIME_MONTHLY_GB` and stops calls
just short of it. A call through the SFU outlives a dropped socket for `voiceGrace`. Guest links let
someone without an account into one call. Frames are encrypted in the apps.
SFU video checks use a guest's channel permissions as well as a member's, including when an ICE
restart republishes an existing track. A guest link grants entry, not an exception to Video.
Guest TURN credentials are reused until a minute before expiry, at most the normal reuse period.
Concurrent requests from one identity share one credential mint.

A server's voice channel has a chat of its own: the server never refused messages there, and the
apps show it as the channel's messages and box (web `components/voice/VoiceRoom.tsx`, reusing
`ChannelTalk` from `ChatView.tsx`; Android `ui/voice/VoiceRoom.kt`). A chat button in the call's
header opens it, with a dot or a mention count while it holds something unread. From 1100 px (dp)
it stands beside the call, 400 wide, and whether it is open is remembered on the device; narrower
it covers the call (which carries on underneath) until closed, Back closing it on Android. A link
to one of its messages opens it. Only while it shows is it the active channel, so it is marked read
then. Voice channels count towards the sidebar's unread state, a server's unread badge and Mark as
Read (web `lib/unread.ts`, `sidebar/serverActions.ts`; Android `Channel.hasChat`).

Call timers: the registry keeps when each channel went from empty to occupied and stamps it on
every voice state as `call_since` (the same for everyone in the call, kept when the person who
started it leaves, gone once the channel empties). The apps count from it, through the server
clock offset, falling back to the earliest `joined_at` on a server without it (web
`lib/callTimer.ts` and `components/voice/CallTimer.tsx`, Android `data/CallTimer` and
`ui/voice/CallTimer.kt`, both ticking once a second). The time shows after "Voice Connected", in
the call view (beside the quality on a wide call, centred above the controls on a narrow one and
on Android), as a tag on an occupied voice channel in the sidebar (making way on the web for the
row's hover buttons) and on the banner of a call this device is not in. Nothing counts while a
call rings, and the time holds still while this device's call is reconnecting. Android's call
notification's chronometer runs from the same start.

Moderators act on people in a server's voice channels with `PATCH /servers/{id}/members/{uid}/voice`
(`mute`, `deaf`, `channel_id`, null to disconnect), checked against the permissions in the channel
the person is in (Mute Members, Deafen Members, Move Members; a move also needs View Channel on the
destination) and the role hierarchy; a guest can be muted or disconnected but not moved. A server
mute or deafen is kept in the member's `server_members.flags` (2 muted, 4 deafened; 1 is AutoMod's
quarantine) and applied again whenever they join a voice channel of that server. A voice state's
`suppress` says the channel denies Speak (`server_mute` is set too, for older apps), and the leave
of someone a moderator put out carries `reason: "disconnected"` ("moved" is another device of theirs
taking the call). On the web the moderator's buttons are `components/voice/voiceModeration.ts`, in the
call's menus in `components/voice/callMenus.ts`; dragging people between channels is
`stores/voiceDrag.ts`. On Android it is `ui/voice/VoiceModeration.kt`.

Priority Speaker: while someone holds the Priority Speaker key (the account's voice setting
`priorityKey`, recorded in Settings, Voice & Video; not offered on touch screens, where nothing is
held) their mic opens as with push to talk and their voice state op carries `priority: true`. The
server keeps it only in a server's channel where they have Priority Speaker and never for a guest
(`voice.State.Priority`), and every voice state then says `priority_speaker`. Listeners turn
everyone else, voices and share sound, down to `PRIORITY_DUCK` (a quarter) while a priority
speaker is heard (web `voice/playback.ts`, Android `voice/Playback.kt`), and a megaphone shows by
their name in the sidebar and on their call tile. Android only listens: it has no key to hold. Use
Soundboard (1 << 42) stays in the permission maths for the bot API and Discord templates, but
Nexa has no soundboard and the role editors do not offer it.

How a screen share is sent was measured through the SFU with `tools/share_quality.py`, and the
findings decide it (web `voice/media.ts`, `shareQuality.ts`, `shareAdapt.ts`; Android `VideoCap`,
`ShareAdapt`). A share has a mode, picked in the share dialog (web `ShareScreenModal.tsx`, Android
`openShareScreen` in `ui/voice/CallMenus.kt`) and kept in the account's voice settings
(`shareMode`; Custom's `shareResolution`, `shareFps`, `shareBitrate` in bits a second or null for
automatic, and `shareKeep`). `sharePlan` (web `shareQuality.ts`, Android `VideoCap.sharePlan`)
turns it into a `SharePlan`: Performance is 1080p at 60 fps within 6 Mbit/s, smooth; Quality is the
screen's own size at up to 60 fps within the plan's most (12 Mbit/s with Supporter, 20 with Pro; without a plan it
is locked and shares as Performance), sharp; Custom is as chosen. Settings from before
modes read as Performance when they held the usual 1080p at 30 fps, otherwise as Custom (60 fps
smooth, slower sharp), without being rewritten (`shareModeOf`). A smooth share gets no content hint
and is steered by `ShareAdapter`; a sharp one gets `detail` (`text` at 15 fps), keeps its size and
gives up frames. Chromium's `motion` hint shrinks a share while the bandwidth estimate is still low at
the start and never grows it back (1080p60 stayed 480x270, 320x180 in Chromium), and asking for
`maintain-framerate` does the same, so a smooth share is sized by `ShareAdapter` instead: every few
seconds it reads the encoder's `targetBitrate` and scales the picture just far enough for about 0.06
bits a pixel a frame, growing back when there is room. A share's sender has high priority and the bitrate
`shareBitrate` gives (9.9 Mbit/s at 1080p60). A watched share whose picture is stuck behind a lost
frame asks its sender for a keyframe within about half a second (`stallWatch.ts`, read by `sfu.ts`
`watchStalls`, through `FrameCrypto.requestKeyFrame` and the transform's `sendKeyFrameRequest`)
where the browser waits about 3 s; Android's WebRTC has no way to ask. A video receiver whose first
frame is not a keyframe asks for one at once from the frame worker (`frameCrypto.worker.ts`
`keyAwaited`), rather than after Chromium's own wait of about 200 ms, so a share or camera just
pulled shows sooner.

Through the SFU, a share goes out in H.264 rather than VP8 when the sender's hardware encoder makes
it and everyone else in the call said they can watch it (`codecs: ["h264"]` on their voice state);
otherwise VP8. Android does this whenever its WebRTC can send H.264 (`VideoCap.sendsH264`,
`SfuTransport` `wantsH264`). The web does it only in a Chromium on a Mac (the desktop app, Chrome, Edge) whose
H.264 encoder the browser calls power efficient (`videoCap.ts` `sharesInH264`, which reads
`navigator.mediaCapabilities.encodingInfo`): VideoToolbox there, where VP8 is software and a busy
share dropped frames. Windows stays on VP8 (hardware H.264 in WebRTC varies by graphics driver), as
does Safari. Someone joining who cannot watch H.264, or the last of them leaving, makes the sender
publish the share again in the other codec (`republishScreen` in `sfu.ts`, closing its own track
with `sfuClose`), and everyone pulls it afresh. The peer-to-peer mesh always sends VP8.

Through the SFU, cameras and watched shares are pulled only while the call's pictures are on
screen: a call view of that call is open on a visible page, or a call video floats in picture in
picture (web `voice/videoShown.ts`, which `VoiceView` registers with and the engine watches;
Android `voice/VideoShown`, from the call view, whether the app is in front, and `Pip.active`).
When they leave the screen the transport keeps pulling them for `VIDEO_HIDDEN_GRACE_MS` (15 s),
then drops them (`setVideoShown`), and pulls them again the moment they are back. Voices and a
share's sound always come in. The mesh pulls nothing selectively and ignores it.

A share's sound is Opus in stereo at 128 kbit/s without DTX (`opus.ts` `STREAM_AUDIO_BITRATE`),
where voices are 48 kbit/s with DTX. `sfu.ts` passes the mids of every share-sound track, its own
and those it pulls, to `tunedRemote` (which sets what the sender's encoder makes) and `tunedLocal`
(this device's own descriptions, where a decoder learns to play stereo) at every exchange. The SFU
meter charges a pulled share sound at `StreamAudioBps` (128 kbit/s). The mesh keeps the voice tuning.

### Signing in: passkeys, QR codes, more than one account

- Passkeys (`api/passkey_handlers.go`, `internal/webauthn`, table `passkeys`) sign in and open the
  messages in one step. The app asks the passkey for its PRF output for the fixed input
  `nexa passkey prf v1`, makes a key from it with HKDF (`nexa passkey identity v1`) and wraps the
  identity key with that; the server keeps the wrapped copy beside the passkey's public key and
  hands it back only to a sign-in the passkey signed. A passkey that gives no PRF output is refused
  when it is added (the owner's choice, 2026-10-25), since it could sign in but never read a
  message. Adding one takes the password (`requirePassword`, captcha action `passkey`); signing in
  takes no username and skips the second factor, as user verification is required. A passkey
  belongs to the address it was made on (its RP ID, from the request's Origin, else PUBLIC_URL's
  host); the Android app's origin is the published signing certificate's hash, and
  `/.well-known/assetlinks.json` carries `get_login_creds` for it. Answered challenges are kept in
  memory per process so one answer signs in once, and a counter that goes back is refused. Web
  `lib/passkeys.ts` and `components/settings/PasskeysSection.tsx` (Settings, Security); Android
  `data/Passkeys` through AndroidX Credential Manager, offered from Android 14 only, since the app
  carries no Play services.
- QR sign-in (`api/qr_login.go`, web `lib/qrLogin.ts`, Android `data/QrLogin`): a device that is
  not signed in makes a one-time X25519 key and shows a code that is a link to
  `/login/qr#<id>.<public key>`, and long-polls `GET /auth/qr/{id}` (so the login page never goes
  network-idle: the test scripts load it with `domcontentloaded`). A signed-in phone scans it (the
  Android app's Scan QR Code, CameraX with ZXing in `ui/auth/QrLogin.kt`, or any phone camera,
  which opens `/login/qr`: web `pages/QrApprovePage.tsx`, Android `openQrApprove` through
  `Nav.openPath`), is told the asking device's user agent, and on Log In seals the identity key to
  the key in the code, never one the server names. The server makes the session and hands the
  token and box over once. Requests live in memory for three minutes, per process. The login page
  shows the code only from 768 px (web `pages/QrSignIn.tsx`, Android `QrSignInPanel`).
- More than one account (web `lib/accounts.ts`, Android `data/Accounts`): the accounts signed in
  on a device keep their tokens in a list (`ec.accounts`, Android's secure store `accounts`), at
  most five; keys stay where they always were, per account. Only one runs: switching ends the
  call and connection, puts the other token in place and opens the app again where that account
  last was (web `ec.place:<id>` and a page load, Android `Auth.switchTo` then
  `Nav.restore(switched = true)` from its `last:<id>`), clearing Android's open layers first since
  their screen leaves as the app reopens. Add Another Account is a dialog over the app (web
  `modals/AddAccountModal.tsx`, Android `openAddAccountSheet` in `ui/shell/Accounts.kt`): a
  password (`passwordSignIn` in `stores/auth.ts`, `Auth.passwordSignIn`), a passkey or a QR code
  signs the other account in without touching the open one, and `addSignedIn` keeps it and
  switches. Logging out goes on with the next saved account. The status menu (web
  `shell/UserPanel.tsx`, Android `openStatusSheet` in `ui/shell/Dock.kt`) holds the switch for
  notification sounds (the account's `notifications.sounds`), Switch Accounts (a side menu on the
  web, over the menu at phone width; a sheet of its own on Android, `openAccountsSheet`) listing
  every account with the open one ticked and a way to log each other out, then Add Another
  Account, and on Android, Scan QR Code.

### Nexa, moderation and safety

- Nexa is the account the service speaks as: notices, announcements, and case threads where a
  member can appeal (not end-to-end encrypted, and the app says so). Their panel is
  `/channels/@admin` (`/api/v1/moderation/*`); the member's side is `/api/v1/nexa/*`.
- Staff ranks (`api/staff.go`, web `lib/staffRank.ts`, Android `data/StaffRanks.kt`), each holding
  everything below it, kept as public user flags so every app draws them: Mod (flag 512) has the
  panel's people, cases and log, mutes and bans of at most a week (`modMaxDuration`, never
  permanent, never shortening a longer one), and lifts, closes and reopens only the cases they
  opened; Admin (256) has the rest of the panel (other actions, permanent bans, servers,
  expressions, apps, bugs, alts, announcements) but not Disable Sign-In or deleting accounts, and
  no rights in other people's servers; Developer (flag 1, what Nexa admins always were: the first
  account, or `ADMIN_USERNAME` while nobody has been admin yet) adds every right in every server
  they are in, Disable Sign-In, deleting accounts and the instance's settings, stats and crash
  reports; Owner is the oldest Developer (`InstanceOwnerID`) and adds the legal report and ranks.
  Only the owner gives and takes ranks (`PATCH /admin/users/{id}` with `rank`; the old `admin`
  bool means Developer) and disables staff's sign-in; anyone holding a rank cannot be moderated.
  Every route says its least rank with `s.staff(rank, h)`; `emitStaff` takes one too (bug events
  go to admins and up). A change is logged as `rank_change` with `from` and `to`. Badges: the
  owner's crown, a developer's CodeXml, an admin's hammer, a mod's ShieldCheck, all in the accent
  (web `StaffMark`, `StaffBadge`, `RankMark` in `components/common/OwnerBadge.tsx`, the panel's
  `RankPill`, the owner's Set Rank dialog `moderation/RankModal.tsx`; Android `OwnerMarks` in
  `ui/shell/DmList.kt`, `RankBadge`, `openRankSheet` in `ui/moderation/Ranks.kt`).
- Staff can act on accounts (mutes, bans, purges, removing pictures, resetting fields), servers
  (including Discovery holds), sticker packs, emoji and uploads (`moderation_actions.go` and the
  other `moderation_*` files). The Bots tab (`GET /api/v1/moderation/apps`, `moderation_apps.go`,
  web `components/moderation/BotsTab.tsx`, Android `ui/moderation/Bots.kt`) lists every app with its
  owner, reach and whether it reads every message, while the people grid leaves bots out; Moderate
  opens the bot's person page. The grid picks many accounts at once (Select, then a bar to
  Moderate, Disable or Restore Sign-In all of them; web `UserGrid.tsx` with `ModerateModal`'s
  `bulk`, Android `Users.kt` with `openModerate(bulk = ...)`): the apps send the same per-account
  action for each one in turn, so every member gets a case of their own. Actions are recorded in `mod_log`; one on an account comes with a case
  (`mod_cases`) whose thread the member sees.
- Alt accounts: `user_ips` keeps every address an account signed in or was active from
  (`Store.RecordUserIP`, written when a session is made and whenever `auth.Check` sees a session
  used from a new address or touches it, which also keeps `sessions.ip` current; deleted 180 days
  after last use by the maintenance loop, and with the account). The grid and a person's page show
  "Alt Accounts: N" (`alt_count`, `Store.AltCounts`) for accounts that share one, and
  `GET /moderation/users/{id}/alts` lists them with how many addresses they share, never the
  addresses (web `moderation/AltAccounts.tsx`, Android `ui/moderation/Alts.kt`). Only the
  admin's legal report prints the addresses. The Privacy Policy, section 3e, describes it.
- Bug reports (`/api/v1/bugs`), client crash reports (`/api/v1/client-errors`) and the admin's
  instance settings (registration mode and key, which override `chatd.env` until cleared).
- Case threads and bug reports are conversations: either side writes and sends pictures and
  recordings (private blobs of kind `case` and `bug`, `/media/cases/{id}` and `/media/bugs/{id}`).
  A closed case, and a bug report staff set to resolved, won't fix or closed, takes nothing more
  from anyone, staff included, until staff open it again. What each member of staff has read of
  them is kept in `mod_reads` (`moderation_reads.go`): the panel's badges count the threads waiting
  on the staff that hold something new to that person, `mod_unread` keeps their devices in step,
  and `POST /moderation/read` is Mark as Read.
- AutoMod: the server applies a server's rules to what it can read (names, profiles, thread and
  post titles); the members' apps apply the same rules to encrypted messages and report what they
  held (`POST /servers/{id}/automod/report`).

### Configuration

Every setting is documented in `chatd.env.example`; `internal/config` loads them. Groups:
addresses (`LISTEN`, `PUBLIC_URL`, `ALT_URLS`, `MOVED_FROM`), storage (`DATA_DIR`, `DATABASE_URL`,
`REDIS_URL`, `WEB_DIST`, `DESKTOP_DIR`, `ANDROID_DIR`, `NODE_ID`), limits (`MAX_UPLOAD_BYTES`,
`MAX_AVATAR_BYTES`, `MAX_EMOJI_BYTES`, `MAX_STICKER_BYTES`, `SESSION_TTL_DAYS`), sign-up
(`REGISTRATION` open/invite/closed, `REGISTRATION_KEY`, `ADMIN_USERNAME`, `HCAPTCHA_SITE_KEY`,
`HCAPTCHA_SECRET_KEY`), calls (`REALTIME_APP_ID`, `REALTIME_APP_SECRET`, `REALTIME_MONTHLY_GB`,
`GUEST_LINKS_P2P`, `STUN_URLS`, `TURN_URLS`, `TURN_USERNAME`, `TURN_CREDENTIAL`, `TURN_KEY_ID`,
`TURN_API_TOKEN`), outbound (`EGRESS_PROXY`, `WARP_PROXY_PORT`), integrations (`KLIPY_API_KEY`,
`IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET`, `TELEGRAM_BOT_TOKEN`, `DISCORD_CLIENT_ID`,
`DISCORD_CLIENT_SECRET`, `DISCORD_BOT_TOKEN`, `DISCORD_ACTIVITY_GUILD_ID`, `DISCORD_REDIRECT_URL`
left empty, `DISCORD_API_URL` for the fake), payments (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_API_URL` for a fake), logging
(`LOG_LEVEL`, `LOG_FILE`). A new setting goes
in `config.go`, `chatd.env.example` and this list.

### Go tests

- `internal/api`: `newTestServer(t)` (`testutil_test.go`) is a real `httptest` server with the
  gateway wired in, on a fresh database (SQLite, or a Postgres schema with `CHAT_TEST_POSTGRES`).
  `register(t, ts, username)` (`auth_handlers_test.go`) returns a token and id;
  `do(t, ts, method, path, token, body)` makes a JSON request; `errCode` reads an error;
  `connectGWReady` / `connectGW` open a gateway socket and `gw.next(event, timeout)` waits for an event.
  For apps (`bot_test.go`): `makeApp` makes one and returns its bot's token, `botDo` makes a
  request as the bot, `addBot` adds it to a server as the consent page does.
- `internal/store`: `newStore(t)`, `mkUser(t, s, name)`.
- Tests against live services run only with their variables set (`UNFURL_LIVE`, `REALTIME_LIVE`,
  `TELEGRAM_LIVE_TOKEN`, `DISCORD_BOT_TOKEN`); the Discord tests otherwise use `fakediscord`.

## End-to-end encryption

The client does all of it (`web/src/crypto`; Android ports it in `fish.nexa.crypto`, checked
against `web-crypto-vectors.json`). The server stores ciphertext plus the metadata it needs to
deliver: author, channel, mentions, reactions, sizes.

- Accounts: Argon2id stretches the password on the device; HKDF splits it into an auth key (the
  only thing sent, hashed again on the server) and an encryption key that wraps the account's X25519
  identity. The client checks the KDF parameters the server hands out (`lib/serverKdf.ts`) so a
  server cannot weaken them. A recovery key shown once at registration can restore the identity.
- Conversations have a root key per epoch, handed to each member as a sealed box to their identity
  key (`key_grants`). Channel keys derive from the root, so one grant covers every channel of a
  server. When someone needs keys the server sends `keys_needed` and an online holder wraps them,
  for every epoch they lack, so someone who joins reads what was said before they came; a device
  still waiting asks again with `POST /keys/request`. When membership shrinks it sends
  `key_rotation_needed` and an online holder makes a new epoch.
  Invite links can carry the root key in the URL fragment (`#k=<epoch>.<key>`), which never reaches
  the server.
- Messages are AES-256-GCM envelopes with the channel and author bound in. What a message may
  hold is the clients' to keep, since the server sees only ciphertext: `MESSAGE_LIMITS` in
  `crypto/message.ts` (Android `MessageCrypto.Limits`): 4000 characters as typed (a mention or
  custom emoji counts as two, `typedLength`), 10 files with names of 255 characters, 5 link
  previews, a sticker name of 30, and the poll limits. Both apps refuse to send a payload past
  them (`brokenLimit`) and show one that arrives past them as breaking the limits rather than
  drawing it. Attachments are chunked
  (`crypto/fileChunks.ts`: "ECF1", a base IV, then length-prefixed chunks), each file with its own
  key inside the message; pictures also send a small copy of their own. Downloads and video play
  decrypt as they stream, in `download-sw.ts`.
- Attachment blob ids in decrypted message metadata must be 1 to 20 ASCII digits on both clients.
  Android also validates the id at its media-cache file boundary before reading a cached file.
- New messages and edits use the channel's current conversation and current epoch. Both clients
  ask for the current epoch before sealing, fetching a missing grant instead of using an old root.
  The API rejects stale epochs, and `InsertEncryptedMessage`/`UpdateEncryptedMessage` repeat the
  epoch check inside the writing transaction under the epoch lock. Edits after a channel becomes
  private must use its private key, not its old server key.
- `POST /keys/request` can include `bad_epoch` and `bad_wrapped_key` to discard the caller's exact
  unusable wrapper before asking holders for a replacement. Both clients do this after a wrapper
  fails to open; another recipient's row or a wrapper already replaced is never removed.
- File readers check the plaintext size authenticated by the message as well as each chunk's tag.
  Dropping whole valid chunks is refused even if HTTP gives no Content-Length. Plain data exports
  on both clients omit `appLock`, whose salt, KDF parameters and verifier would enable offline guesses.
- Call frames are sealed again with a key derived from the conversation's (`voice/frameCrypto.ts`;
  Android in native code, `cpp/frame`), because the SFU ends the transport encryption. Guest links
  carry the call key in their fragment. Native Android uses RustCrypto AES-GCM through
  `android/frame-crypto`, built by cargo-ndk from CMake. The wire format is unchanged.
  Both clients retain full epoch numbers locally and try at most eight matching keys under
  GCM authentication when legacy low-byte frame indexes collide. `node tools/check_frame_crypto.mjs`
  builds in a throwaway directory and checks native/WebCrypto audio, VP8 and H.264 compatibility,
  tampering, and native epoch-collision recovery. Rust unit tests include NIST vectors.
- Notes have a key of their own, sealed to each member; a share link carries a second key in its
  fragment. Announcement follows are sealed to the follow's key. The settings blob, sound packs and
  the search index on the device are sealed too.
- Keys on the device: IndexedDB (`crypto/keystore.ts`), cleared on sign-out; with a passcode lock
  the device keeps neither the identity nor any roots in the clear. Identity keys are pinned the
  first time they are seen (`lib/keyPins.ts`) and safety numbers (`crypto/fingerprint.ts`) let two
  people compare them.
- Apps: a message meant for a bot (a DM to it, a mention of it, a reply to it, a message command's
  target) carries `bot_copies`, its payload sealed by the sender's app to the bot's X25519 key (web
  `lib/botCopies.ts`, Android `data/BotCopies`); the server keeps each bot's private key sealed
  with the server secret and opens the copy to hand it to the bot (`bot_crypto.go`), files
  included. An app with the Message Content intent gets the server's keys like a member, the
  server decrypts for it, and the start of every channel it can see names it (web
  `chat/apps/readingApps.ts`, Android `ui/apps/ReadingApps.kt`). The owner decided the channel's
  header shows no mark for it: whoever added the app knew, and the topic keeps its room. The
  reading glass in the portal and on the consent page shows people which of the two an app is.
- Not end-to-end encrypted: webhook and app messages (`messages.plain`; the app marks them),
  interactions, Nexa's case threads, poll and AutoMod notices the server writes, and everything
  public (profiles, avatars, server names, emoji and stickers).

Activity links in profile cards show their actual canonical host below custom labels/buttons.
Both clients require a full-URL review before opening them. Only HTTP/HTTPS without userinfo is
actionable. The browser also intercepts middle-clicks. Android uses its existing confirm sheet.

## Web app (`web/src`)

React 18, TypeScript, Vite, zustand and react-router; `@/` is `web/src`. `main.tsx` mounts `App` and
registers the two service workers: `sw.ts` (no fetch handler; a new copy taking over means a
deploy, which `lib/appUpdate.ts` offers, using `version.json`) and `download-sw.ts` (scoped to
`/__download/`, streams and decrypts large downloads and videos). Pages load lazily
(`lib/lazyChunk.ts`). A dialog whose pages load lazily makes them with `readyPage` and calls
`ready()` on the page it opens on, so its frame and that page show together (Server Settings);
switching pages goes through `startTransition`, which keeps the page on screen until the next one's
file is here.

### Shared user text layout

User text must fit its display slot independently of its character limit. Do not blacklist
Unicode to fix layout: U+FDFD, combining sequences, nonbreaking spaces and long plain words
must be contained even when previously saved or received from another client. `base.css`
provides inherited emergency wrapping and the web `PersonName` identity boundary. Compact
labels stay on one line, conversation/invite/note/dialog titles are bounded, and markdown
blocks wrap and clip excess glyph ink. Code and tables scroll inside their own wrappers;
the chat list never scrolls horizontally. Android bounds ink in its common text/name/markdown
renderers and uses matching title limits. Preserve its bounded personal-name effect halo.
Keep removable labels in a shrinkable text child so their buttons stay visible.
`docs/UI_TEXT_AUDIT.md` records the input-to-display inventory and the regression workflow.
The field-level ledger is `docs/UI_TEXT_INPUTS.md`, with source snapshots in
`docs/UI_TEXT_INPUTS.tsv` and `docs/UI_TEXT_INPUTS_ANDROID.tsv`; update these when adding
shared text fields, dynamic prompts or imported/integration metadata. Emoji fallbacks
must stay icon-sized, and compact labels need explicit line limits even with input
length validation.
The dev-only `web/tests/text-layout.html` fixture is checked with
`cd web && npx tsc --noEmit -p tests` and `tools/text_layout_audit.py` against an explicit Vite
scratch-server proxy. Android's debug-only `TextLayoutAuditActivity` accepts theme, payload
and page extras for adversarial text screenshots. Neither fixture is a production route.

### Routes (`routes.tsx`)

`/` (home), `/login`, `/login/qr` (approving a QR sign-in), `/register`, `/recover`, `/invite/:code`, `/note/:token`, `/download`,
`/terms`, `/privacy`, `/about`, `/call/:code` (guest call), `/gift/:code` (claiming a gift link), `/stickers/pack/:packId`,
`/connected/discord`, `/oauth2/authorize` (the consent page for adding an app), and inside the
signed-in shell `/channels/@me` (friends), `@me/nexa` (with
`cases/:caseId`, `bugs/:reportId`), `@me/notes[/:noteId]`, `@me/support` (plans), `@admin/*` (the staff panel),
`@discover`, `@me/:channelId[/:messageId]` (DMs), `:serverId[/:channelId[/:messageId]]`, then
`/:handle` (a profile link, `/@name`).

### Public source and contributions

`lib/legal.ts` exports `GITHUB_URL`, the public repository for every client and the server.
Android reads the same constant from its copied legal asset (`Legal.githubUrl`). The homepage
names open source in its introduction and as the first of its five promises, invites contributions,
and links GitHub in its actions and footer. Settings has a GitHub shortcut on web, desktop and
Android; both the public About page and Settings, About explain the MIT license and invite
contributions. The web shares that content through `OpenSourceBlock`; Android uses the same
`project.*` translations and opens the repository in the browser. README.md reuses the synthetic
`web/public/home/app.png` screenshot, which contains no personal conversations.

### Homepage demo

The homepage's `HomeDemo.tsx` embeds `demo.html`, a second Vite entry, in a sandboxed iframe.
It runs the real app routes and shell from `demo/DemoApp.tsx` with a fresh, empty visitor account,
no fictional people or prefilled servers. `demo/environment.ts` runs before any app imports:
localStorage and sessionStorage are fresh maps, IndexedDB is a fresh in-memory fake-indexeddb
factory, Cache Storage and the desktop bridge are unavailable. Fetch permits only same-origin
bundled assets without credentials; sockets, XHR, workers, media devices and native navigation are
blocked. No real token is read or issued. `demo/runtime.ts` replaces the API with memory-only
profile, settings, server, channel, message and note operations. Unknown routes fail closed and
show Create an Account to Continue, as do people, invites, calls, uploads, payments and security
actions. New app endpoints must never fall through to a real transport in the demo.

`lib/demoMode.ts` is true only in that entry. RequireAuth skips the gateway there, AppShell skips
live effects and update/download prompts, and joining voice asks for an account before opening
any audio device. The desktop bridge is also refused by `desktopBridge()`, and LookEffect
allows local CSS while plugin execution asks for an account. All other visitors keep the normal app behavior. Only `/demo.html` allows
same-origin framing in its response CSP; every other HTML page still forbids framing. The demo
entry also carries a stricter CSP. Reset replaces the iframe; reload drops its entire realm.
Full Screen uses a fixed page overlay on every device, including iOS, with a visible exit button,
Escape, a motion-preference-aware expansion/return animation, and the same iframe kept mounted.
Dialogs take and restore focus with `preventScroll`, so opening the account gate inside the
homepage preview cannot scroll the surrounding page.

This is a public website feature, with no corresponding native Android screen.

### State and data flow

- `api/` is the typed REST client (`api/client.ts` adds the token, a timeout and the error
  envelope); `api/types.ts` holds the wire types.
- `gateway/client.ts` keeps the socket (backoff, heartbeat), `gateway/session.ts` starts it after
  sign-in, and `gateway/dispatch.ts` applies every event to the stores.
- Stores (`stores/`, zustand): `auth` (sign-in status: idle, loading, offline, anonymous, locked,
  pinlocked, ready), `keys`, `settings` (the account's settings, sealed and synced with an optimistic
  version), `messages`, `channels`, `servers`, `users`, `presence`, `relationships`, `readStates`,
  `typing`, `threads`, `voice`, `voiceLocal`, `expressions`, `notes`, `nexa`, `moderation`,
  `discord`, `desktop`, `ui` (modals), `tabs`, `selection`, `pick`, `uploads`, `unsaved`,
  `replyTargets`, `serverNav`, `slowmode`, `profilePopout`, `automodNotices`, `groupAddNotices`,
  `keyAlerts`, `threadLast` (each thread's newest message, for the card under the message it hangs
  from), `voiceDrag` (someone being dragged to another voice channel), `appCommands` (the apps'
  commands each channel offers, read again on `application_commands_update`). `typing` keeps who is typing
  where, for the status dot's typing pill (the dot stretched sideways, never taller) and the dots
  just after a server channel's name, both of which the account's settings can turn off
  (`typingAvatars`, `typingChannels`).
- Hashtag suggestions and send/edit conversion include visible text, announcement and forum
  channels on both clients. Subchats stay listed under the open conversation; unread subchats
  also appear under their parent while another conversation is open, then hide after being read.
  Sidebar rows and the Subchats panel show a small red unread-message count on both clients,
  capped at 99+, counting every unread message from others rather than only mentions. Android
  read marking reruns when history finishes loading, including when live messages were cached
  before the conversation was first opened.
- `lib/decrypt.ts` turns a message into what the stores keep (webhook messages pass through
  `lib/webhookMessage.ts` instead). `lib/keyring.ts` holds the roots in memory and caches channel keys.

### Main folders

- `crypto/`: KDF, identity and sealed boxes, conversation keys, message envelopes, files, notes,
  safety numbers, the IndexedDB keystore, streaming decryption.
- `markdown/`: the message parser (`parse.ts`: the chat syntax people know plus GitHub's blocks,
  coloured text `[words]{red}`, timestamps, mentions) and renderer.
- `search/`: a full-text index of what this device has decrypted, in a worker, sealed on disk;
  `lib/search.ts` reads the query and its filters.
- `voice/`: calls (`session.ts` for the app, `engine.ts`, `transport.ts` with `mesh.ts` and
  `sfu.ts`, `frameCrypto` and its worker, `playback.ts` per-person volume, `denoise.ts` modern RNNoise with speech-probability gating,
  `opus.ts`, `redundancy.ts`, `videoCap.ts`, `shareQuality.ts`, `shareAdapt.ts` (sizing a 60 fps
  share to the link), `stallWatch.ts` (a watched share stuck behind a lost frame), `callRecord.ts`
  for DM call lines).
- `lib/`: everything else, one concern per file. Notable groups: `look/` (themes, presets, the theme
  builder's maths, custom CSS and plugins with their API in `look/plugins`), `notes/` (the Yjs
  document, sessions, sharing, history, mentions), `automod/` (the client side of AutoMod), `spell/` (Hunspell
  in a worker for Nexa's own text menu), and single files for attachments, uploads, notifications,
  sounds, keybinds, the app lock, away detection, moving domains, invites, export, time, links,
  server tags, widgets and the rest.
- Media file links (MP4, WebM, Ogg video, QuickTime, MP3, M4A, Ogg audio, WAV, WebM audio,
  AAC and FLAC) use the existing video and audio players on web and Android. The unfurler
  recognises their response type, or a bounded file header when the host omits it, never a
  filename alone. Audio previews carry type `audio` and `media_kind: audio`. Every file a
  preview loads goes through the signed media proxy, including audio. `unfurl.PlayableMediaType`
  keeps the proxy's accepted types aligned with previews; no reader fetches the original URL.
  SVG previews, including extensionless endpoints such as BenchLM's `/api/og`, pass through the
  signed media proxy as images. Its restrictive CSP sandbox remains, and SVG responses download
  on direct navigation through Content-Disposition; the clients render them only as images,
  with Coil's SVG decoder on Android. HEIC and TIFF remain unsupported by the browser renderer.
  Audio and video keep the original message link visible above the player on both apps;
  pictures and GIFs keep standing alone when the message is only their links.
- `components/` by area: `shell` (app frame, tabs, banners, What's New, update dialog), `sidebar`,
  `rail`, `chat` (message list, message item, embeds, attachments, players, pins, member list),
  `composer` (the box, autocomplete, commands, and `format/`, the formatting editor), `modals`,
  `settings` (every Settings page), `server` (Server Settings pages), `voice`, `profile`, `pickers`
  (emoji, GIFs, stickers), `notes`, `threads`, `forum`, `search`, `moderation` (staff panel), `nexa`
  (the member's Nexa pages, and `ThreadChat`, which draws a case thread or a bug report as a
  conversation with the chat's own rows, attachments and box, for both sides), `bugs`, `legal`
  (Terms gate), `invite`, `discovery`, `follows`, `friends`, `stickers`,
  `servertag`, `activity`, `desktop`, `common` (shared controls: Modal, Popout, ContextMenu, Button,
  Toggle, Select, Avatar, Twemoji and more).
- Apps (web): `api/apps.ts` is their REST client and `lib/apps.ts` their logic (commands, their
  options, names in the reader's language); `lib/interactions.ts` sends what someone does with an
  app and reads its answer, `lib/botCopies.ts` seals the copies for bots, `lib/pickables.ts` lists
  people, roles and channels for an app's select menus, and `lib/passwordProof.ts` turns a password
  into the proof the portal's resets ask for. `components/chat/apps` draws an app's messages (the
  line saying who used which command, with submitted options on hover, buttons, select menus,
  thinking, answers only you see) and
  the Apps entries in the message, person and member menus (`ContextMenuItem.picture` draws each
  app's picture beside its entry); `components/chat/AppLinkCards.tsx` draws cards for app install
  links (`/oauth2/authorize?client_id=...`) in chat;
  `components/apps` holds what they share (`SelectBox`, `AppFormModal` for an app's forms,
  `ReadingGlass`, `PermissionPicker`, `AppIcon`, `BotTag`). `composer/CommandMode.tsx` is the box
  while an app's command is filled in, an option at a time: `:name` in a text option offers emoji
  (the list shared with the message box, `composer/emojiSuggestions.ts`), the emoji picker fills
  the option being written (`insert`), and Ctrl+A where no option's words are selected (or a
  second time) takes the whole command for Backspace to clear. A slash typed in the box opens
  `Autocomplete`'s `CommandPicker`: a rail of the apps that offer commands there and Nexa's own,
  then each app's commands under its picture and name. The composer's lists and the option bar
  are `docked` over the box, in the page's flow, so the messages move up rather than being
  covered (Android: `CommandPicker` and `dockedListMax` in `ui/chat/Composer.kt`, the same in
  `ui/apps/CommandMode.kt`). On a touch screen, a tap on the "used /name" line shows what the
  command was used with, names given for people, roles and channels. The Developer Portal is
  `settings/portal` (`DeveloperPortalPage`, `AppDetail` with its General, Bot, Install, OAuth2 and
  Commands tabs; the Commands tab can delete one command or all of them, through
  `DELETE /api/v1/applications/{id}/commands[/{cid}]`), Settings, Authorized Apps is `settings/AuthorizedAppsPage.tsx`, a server's apps
  and who may use their commands are `server/pages/AppsSection.tsx` in Integrations, and the
  consent page is `pages/AuthorizePage.tsx`.
- Context menus: every menu the app opens is registered in `lib/contextMenus/registry.ts`, and none
  is built inline. A menu is `defineMenu<Ctx>(kind, groups)`, defined once at module level beside
  what it acts on (`chat/messageMenu.ts`, `sidebar/serverMenu.ts`, `sidebar/dmMenu.ts`, ...): groups
  of buttons, each `button(id, (ctx) => item | items | null)`, with a line drawn between two groups
  that both show something. Button ids are camelCase and unique across every menu
  (`deleteMessage`), since the plugin API will name buttons by id alone; a set of buttons that two
  menus share is a factory taking the ids (`imageButtons`, `dmArrangeButtons`, `threadButtons`,
  `relationshipButtons`). Components open a menu with `menu.open(ctx, placement)`, passing what only
  they have (callbacks, the moves a reorder hook offers) in the context. `registry.test.ts` fails
  when any file but the registry calls `openContextMenu`, and loads every menu module together to
  catch a button id used twice. A menu about something says what with `about(menu, subjects, (ctx) => ({
  message, userId, channelId, serverId }))`, which is what plugins' menu callbacks are told
  (`subjects` names which of message, user, channel and server that gives, for the docs). The
  table of menus in `docs/plugins.md` is made from the registry by `lib/contextMenus/menuDocs.test.ts`,
  which fails when it is out of date (`UPDATE_DOCS=1` writes it again) or a menu has no line in its
  `WHERE` list; a new or changed menu updates both. Plugins
  change menus through the registry's layer of changes (`addButton`, `removeButton`, `editButton`,
  each recorded against the plugin and taken back by `dropChanges` when it stops); every menu
  module is listed in `lib/contextMenus/all.ts` so the plugin API can load them all before a plugin
  that may change menus starts, and a test fails when one is missing there.
- A message's menu starts with a row of quick reactions (`ContextMenuState.reactions`, filled by
  `quickReactions()` in `chat/messageMenu.ts`: the account's most used emoji, then the usual few):
  four in a square row beside a mouse, six round ones and a button for the picker in a phone's
  sheet, as Android's `QuickReactions` in `ui/chat/MessageMenu.kt`. Frequent emoji are snapshotted
  once per open expression menu on both clients, including when changing tabs; picks still save
  usage immediately but do not move hit targets until the menu is opened again. A picker that offers emoji
  alone shows a title (the reactions' "Add Reaction") in place of one tab, on both apps.
- People's menus: a right click on someone's picture or name in a message, or the name a reply
  quotes, opens the menu the member list there opens (`chat/personMenu.ts`: `memberMenu` in a
  server, `groupMemberMenu` in a group or a one-to-one conversation, where it leaves out the group's
  choices); webhooks and published copies keep the message's menu. Next to every Copy User ID,
  Copy ID for Bots (`lib/copyId.ts` `copyBotIdItem`, `botId`) copies the id bots see (`botproto`'s
  offset), for pasting into an app's commands. Android does both the same way: a long press on the
  picture or name claims the person (`PressedPerson` in `ui/chat/MessageRow.kt`, as `PressedImage`
  does for pictures, since `rowTouch` sees the press first) and `openPersonMenu` in
  `ui/chat/Members.kt` opens their menu; `copyBotIdItem` lives in `ui/profile/PeopleUi.kt`.
- Plugins (`lib/look/plugins`): `runtime.ts` pins, loads, starts and stops them. A plugin without
  `apiVersion`, or with 1, gets the v1 API (`api.ts`, kept as it was); `apiVersion: 2` gets v2
  (`api2.ts` for its types, `runtimeV2.ts` for the API, `objects.ts` turning the app's objects into
  the API's, `permissions.ts` for what a plugin can ask for). A v2 plugin's grants are
  `PluginEntry.permissions` in the account's settings; every v2 method checks its permission when
  it is called (`PermissionError`; a method that returns a promise rejects with it), events are
  delivered only with the permission for what they carry, and context objects leave out what a
  plugin may not see. Before anyone has agreed to a plugin its code never runs: the Add Plugin
  dialog's review stage and a link plugin's update read its API version, its plainly written
  `permissions` list and who it is from the text (`declaration.ts`), and what is granted is exactly
  that list (installing is all or nothing; new code keeps what it still asks for, asks again for
  anything new, drops the rest). `components/settings/look/PermissionLedger.tsx` draws a list of
  permissions, grouped by what they touch, everywhere one is shown: the add dialog, the update
  dialog and each v2 plugin's Permissions panel on the Plugins page, with a switch for each. v2 events are built on `dispatchHooks` (above); v2 stylesheets go when the
  plugin stops unless added with `persist`. Plugins never run on Android. `docs/plugins.md` is
  the reference for plugin authors (VitePress markdown: v2 first, v1 under its own heading), with
  examples in `docs/examples/` for both versions; a change to the API changes it in the same commit.
- Plugin commands sit beside the app's own in `components/composer/commands.ts`: a v1 plugin's is
  refused a taken name and its return value is sent; a v2 plugin's (`takePluginCommand`) takes the
  name from any plugin, can never take one of the app's, and sends nothing. Plugin keybinds:
  `lib/pluginKeybinds.ts` holds what running plugins registered, `components/shell/Shortcuts.tsx`
  runs them after the app's own keybinds (in the window only, never system-wide), and the keys
  people chose are the account's `pluginKeybinds` setting (by plugin id, then keybind id, checked
  by `sanitizePluginKeybinds`, which also forgets a deleted plugin's). Toasts have a fourth kind,
  `warning`, and `toast(text, kind, duration)` takes a duration from 1 to 15 seconds, which only
  plugins' toasts use. Settings, Keybinds has a Plugins section (`components/settings/PluginKeybinds.tsx`)
  for every running plugin's keybinds, recorded by the same rules as the app's own, pointing out
  keys the app or another plugin already has. A plugin's menu button may name a lucide icon, drawn
  by `components/common/MenuIcon.tsx`, which loads (with lucide's list of every icon) only when a
  menu shows one; the app's own buttons have no icons.
- Mentions in notes: a note writes `<@id>` as a message does. Typing @ in the note editor offers
  the people who have the note (`GET /notes/{id}/sharing`, so viewers too, never yourself),
  ranked by `lib/notes/mentions.ts`, in the message box's `Autocomplete` (its `style` prop places
  it) hung from the caret by `components/notes/NoteMentions.tsx`. The format surface finds the
  "@name" before the caret (`mentionAt`), puts the person in (the `mention` command) and redraws
  mentions with names that arrive later (`setNames`, called from `useFormatBench`), without a save
  or an undo step. Clicking a mention in the editor selects it whole, as a chip in a document
  (Backspace or typing takes it out as one), and opens the profile popout beside it with
  `keepFocus` (`openProfilePopout`'s fourth argument, `Popout`'s prop): the keyboard stays in the
  note and the first key typed there closes the card. Only a surface given `onMention` does this.
  The notes list shows mentions by name. Nobody is notified of a mention. Android:
  `notes/NoteMentions` ranks the same, `ui/notes/NoteMentions.kt` draws the list, and
  `FormatSurface` has `mentionAt`, `Cmd.Mention`, `relabelPeople`, and `onMention` with
  `tappedAt` (a tap on a mention, seen by `mentionTaps` in `FormatPage.kt` without taking it
  from the field, selects it and opens the profile sheet).
- Files in notes: a file sits on a line of its own, `![name](nexa-file:id)`, with how much of
  the line a picture takes and the side a narrower one keeps to as `?w=60`, `?w=60&a=left`,
  `?a=right` (`RE_FILE`, `fileLine` and `fileAlign` in `markdown/parse.ts`; the middle and the
  full width are left unsaid). In the editor `components/notes/NoteFile.tsx` draws the picture
  with grips on its sides for its width and a bar along its top: Move first (dragged up or down
  to the gap between two lines nearest the pointer, across to the left, middle or right third of
  the line, with the landing drawn over the page; the arrow keys do the same), then the sizes and
  Remove. Both go through the surface's `fileWidth` and `placeFile` commands, one undo step each;
  a moved file takes away the empty line that followed it when text is left on both sides.
  Android: `ui/notes/NoteFile.kt` (`FileMove`, TalkBack actions in place of the arrow keys),
  `FormatSurface` `Cmd.PlaceFile`, and `BlockPlaces` in `ui/format/FormatPage.kt`, where the
  page keeps each block's place on the screen and draws the landing.
- Name styles (`lib/nameStyle.ts`, server `api/name_style.go`, column `users.name_style`): a
  display name's font, effect and colours, public like the rest of a profile. The fonts are the
  `NAME_FONTS` list (Comic Neue, serifs, sans, rounded, display, script, pixel and mono faces, all
  picked to stay readable), served by Fontsource from `styles/nameFonts.css`, which loads only once a
  styled name is drawn (`loadNameFonts`). Every font is sized to match the app's own:
  `font-size-adjust` gives it Wix Madefor Display's lowercase height (Bungee, all capitals, its
  capital height), and Android multiplies by the same measured `scale`. `styles/nameStyle.css` draws
  the eleven effects (Solid, Gradient, Glow, Ink, Pop, Candy, Aurora, Chrome, and Holo, Neon and Flame, which come with a plan and show locked without one) as `ns-*` classes. A new
  font goes in `NAME_FONTS`, the CSS imports, `nameFonts` in the Go file, `FONTS` in
  `android/tools/name_fonts.py` (then run it to write `res/font/name_<id>.ttf`) and Android's
  `data/NameStyle.kt`. Names are drawn through `nameStyle`/`nameClass` in `lib/roleLook.ts`, whose
  look carries the person's own style as `own` (`useMemberLook` adds it, `withOwn` for lists): in a
  server a role's colour still colours the name and only the font stays. Settings, Profiles edits it
  with `components/settings/NameStyleEditor.tsx`, saved with the profile; the
  staff can reset it (field `name_style`). The optional public `animated` boolean selects Static or
  Animated for every non-Solid effect, including flowing gradients, moving Candy stripes, Chrome
  sheen and pulsing Glow, Ink and Pop. Missing values preserve the original behavior (only Aurora,
  Holo, Neon and Flame animate). Both editors preview the selected motion and preserve explicit
  choices when changing effects. Appearance has a synced Animate names toggle (`animateNames`, on
  by default) that stops personal name animations and role shimmer locally, preserving colours and
  fonts. Reduce Motion also stops them.
  Identity labels use
  `components/common/PersonName.tsx` on web and `PersonName` in Android's
  `ui/chat/RoleLook.kt`: preserve the caller's nickname/text, observe live user styles and the
  current account's profile, and fetch missing profiles when a list only supplies IDs. A canonical
  null style overrides stale list snapshots. Saved web accounts retain their style too. Use these
  for friends, the account panel, pickers, voice, notes, moderation and other identity labels;
  translated prose takes web `<Tr>`/`lib/personText.tsx` name nodes or Android
  `PersonText`/`PersonInlines`/`PersonWords`, so only
  the identity fragment receives the effect. Mentions and collaborative cursor labels use the same
  renderer. Inline mentions are bounded by the available line width and ellipsize long names.
  The font class outranks local row/heading typography. Server role colours override personal
  effects while retaining the chosen font. Android: `data/NameStyle`, `ui/chat/RoleLook.kt`
  (`RoleName`, `OwnName`), `ui/chat/NameFonts.kt` and `ui/settings/NameStyleSheet.kt`.
  Android Ink uses the web’s 0.04em outline radius, and Pop keeps the web’s dark edge on light themes. The debug-only `NameStyleAuditActivity` draws every face at 14/16/28sp (`page` 0, 1 or 2), or every effect (`effects=true`), in `theme` dark/light, for screenshot comparisons.
- The profile designer (Settings, Profiles, `components/settings/ProfilePage.tsx`): every part of the
  profile is a row in a panel, grouped as Your Name, Pictures & Colours and About You, each row
  showing what the part holds now. With room (`PANEL_BESIDE`, measured on the designer) the panel
  stands beside the profile and Name Style, the avatar, banner, theme and server tag open as pages
  in it; otherwise the list goes above the profile and those pages open as sheets. Beside the profile the
  panel folds to an arrow (remembered in this browser, `nexa.profilePanelFolded`) and opens again for
  any page it has to show. Display name,
  pronouns and About Me are written in place on the profile (the name opening Name Style beside
  it), Status opens its dialog, Widgets the profile's widgets tab, Typing Indicator the In Chat
  view. The panel, its pages and folding are `DesignerLayout` with `usePanelLayout` in
  `settings/designerParts.tsx`, shared by Server Profiles (`ServerProfileDesigner.tsx`), whose rows
  are the parts a server can change (nickname, pronouns, server picture and banner, theme, About Me,
  widgets), each saying what is set there. Android's `ProfilesPage.kt` and `ServerProfileDesigner.kt`
  have the same rows (`PartGroup`, `PartRow`) above the profile, opening sheets.
- Activity cards (`components/activity/ActivityCard.tsx`, Android `ui/profile/ActivityCard.kt`) draw their heading, title and lines in `ScrollingText`, so a long song or game title scrolls as a status does.
- Colours are picked with `components/common/ColourInput.tsx`, Nexa's own picker (a square of
  shades, a hue strip, opacity when asked for, the hex code, the browser's eyedropper where it has
  one), laid over whatever swatch shows the colour; never the browser's `<input type="color">`.
  Android's is `openColourPicker` in `ui/settings/ColourPicker.kt`.
- `pages/`: one per route. `styles/`: `tokens.css` (every theme token: colour, radius, shadow,
  motion), `base.css`, `markdown.css`, `fonts.css` (Wix Madefor) and `fonts-classic.css` (Onest,
  Mona Sans). `lib/theme.ts` applies a theme; `lib/look/` derives presets and custom themes.
  Every `:hover` style only applies with a pointer that hovers: `hoverOnlyWithAMouse` in
  `vite.config.ts`, a PostCSS step, moves each rule with `:hover` into `@media (hover: hover)`
  (unless it already sits in a media query about hovering), since a phone keeps the hover of
  the last thing tapped. Write `:hover` plainly; what only hovering reveals needs its own
  `@media (hover: none)` rule to show on a touch screen.
- The gallery (`components/settings/look/ThemeGallery.tsx`): Seasonal while one is in season, then
  Classic, Colours (the presets' dark and light groups together) and Greyscale and High Contrast.
  A theme with a light version (`forest` and `forest-light`, `presetCards`) is one card with two
  swatches in its corner (`ModeCard`, each that version's own background) that switch between
  them. Such a card shows and puts on the version in the mode being worn now (the resolved look's
  scheme), so going from one theme to another keeps light or dark; Midnight, Sky and the classic themes are cards of their own. Android does the same
  (`Presets.cards`, `ThemeModes` in `ui/settings/AppearancePage.kt`).
- Seasonal themes (`lib/look/seasonal.ts`, `SEASONS`): Halloween, Fall, Winter, Holidays,
  Valentine's Day, Spring and Summer, with Winter, Spring and Summer also in a dark Night version
  (same days, the light one's picture through `art`), each a background and an accent run through `deriveTheme`
  and a span of days in the device's calendar (one may run past the new year). The gallery offers
  only those in season, in a Seasonal group above the rest, never putting one on by itself; a
  season and its Night version are one card (`seasonCards`) with two swatches in its corner,
  light and dark, that switch between them. Out
  of season one draws as Dark (`resolveLook`), and `ThemeEffect` sets the account's theme back to
  `dark` once the settings have loaded. While one is worn, `components/common/SeasonalPerch.tsx`
  sits its picture (`assets/seasonal/<id>.svg`, which the Android app copies and draws too) on
  the top edge of the message box, and the gallery's card shows it on the miniature. A season with
  `snow` (Winter Night) has `components/chat/SnowFall.tsx` falling behind the messages: a canvas at
  z-index -1 in the chat column, drawn over the app shell's main panel (its stacking context), and
  nothing at all when motion is reduced or the account's `seasonalSnow` setting is off (Show
  Falling Snow, in the Seasonal group while such a theme is in season). Nothing in it is a set path:
  the wind and each flake's drift, fall and spin are random walks pulled back towards calm
  (Ornstein-Uhlenbeck), fresh noise every frame. Flakes are dots, the near ones bigger and soft at
  the edge. A frame's time step is never below zero (the frame time can come before the moment
  the loop restarted, after a hidden tab, and a negative step made every flake NaN for good)
  (Android `ui/chat/SnowFall.kt`, same model and numbers in dp). Android
  keeps the same list in `ui/theme/look/Seasonal.kt`; a change to one changes the other, and
  `look_vectors.ts` checks the palettes.
  To try them all on any day, build the web with `VITE_ALL_SEASONS=1` (`npx vite build` into a
  scratch directory) or Android with `-PallSeasons=true`: every season is then running every day.
  Never set either for a deploy or a release.
- `i18n/`: `useT()`, `t()` (usable outside components), `<Tr>`; English is bundled and the other
  eleven catalogues (ar, de, es, fr, hi, ja, ko, pt-BR, ru, sv, zh-CN) load when chosen. Keys are flat and
  dotted by area (`settings.*`, `server.*`, `chat.*`, `whatsNew.*`, `android.*`, ...).
- Tests sit beside the code (`*.test.ts[x]`). Vitest runs in node with `fake-indexeddb`; component
  tests start with `// @vitest-environment jsdom`.

## Desktop app (`desktop/`)

The desktop app is Tauri 2 (version in `desktop/src-tauri/Cargo.toml` and
`tauri.conf.json`, bumped for every release) around the live site. It opens one window on
https://nexa.fish (`NEXA_DESKTOP_URL` points a development build elsewhere), refuses to navigate
anywhere but Nexa's own hostnames and that development origin, and sends other links to the browser.
GitHub Actions (`.github/workflows/desktop.yml`) builds it on a `desktop-v*` tag into a draft
release: NSIS on Windows, AppImage and .deb on Linux, separate Intel and Apple Silicon DMGs, with
signed updater files. The updater private key and its password are repository secrets
(`TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`) and are never committed; the
public half is pinned in `tauri.conf.json`. `src-tauri/src/main.rs`
registers the plugins and creates the window; `src-tauri/bridge.js` is injected before the page and
defines `window.evilionDesktop` with the shape `web/src/lib/desktopBridge.ts` expects, so the web app
needs no changes. `desktop/src-tauri/permissions/desktop.toml` lists every command the remote page
may call: Tauri's ACL otherwise refuses application commands from a remote origin. The window only
serves Nexa's origins, and `on_permission_request` allows the microphone, camera, display capture,
notifications and clipboard reads; the operating system still asks its own questions.
The bridge's `settings.set` accepts a partial settings object and returns all four current public
settings, as the web's desktop settings toggles expect; omitted fields and Rich Presence settings
are preserved.

The shell keeps the tray (Open, Mute, Deafen, Check for Updates, Quit), close to tray, start
minimized and launch at login (the autostart plugin), and system-wide keys through
`tauri-plugin-global-shortcut` (`src-tauri/src/shortcuts.rs`; the accelerators are the same strings
the web app always sent). `src-tauri/src/rpc/` is the Rich Presence server: Discord's IPC endpoint
(named pipe on Windows, Unix socket elsewhere) and a local WebSocket server, passing programs
through to the real Discord when it runs, and handing activities to the page, which sends them to
chatd. `src-tauri/src/originals.rs` is Open Original: decrypted pictures and videos go to the default
browser through a loopback listener that accepts only exact random-token paths and Host headers,
serves inert media types with no-store and a sandbox CSP, and expires each original after ten
minutes. Files stay in memory (at most eight, 256 MiB, dropping the oldest); nothing is written to
disk or uploaded.

Screen sharing uses the webview's own picker. Electron's custom picker fed a chosen window into
`getDisplayMedia` and could share one window's sound; no system webview exposes that hook (WebView2's
screen-capture event can only cancel, not choose), so Nexa's picker is not drawn and the system
picker answers instead. On macOS the `displayCapture` permission alone is not enough for WKWebView:
`window.rs` sets the private `_setScreenCaptureEnabled:` preference on
`WKWebView.configuration.preferences`, which turns on the
ScreenCaptureKit picker WebKit already ships, and `capture.rs` checks and opens Screen Recording in
System Settings. This requires an updated WebKit; Safari 17.6 fixed display capture when the
embedder supplies only the public camera/microphone delegate, as Wry does. Windows enables Share
Audio through WebView2's picker and reports `shareAudioMode: own` only when the engine supports
`restrictOwnAudio`; otherwise the dialog warns that system sound includes the call. The web asks
for `windowAudio: window` when sound is on and `exclude` when off, rather than requesting every
program's sound for a selected window. That hint depends on the installed engine and Windows
version, so the bridge still reports `windowAudio: false` until native testing verifies it.
The Mac and Linux bridges report `shareAudio: false`. WKWebView's display
capture path returns no audio track; native ScreenCaptureKit supports audio, but this shell does
not bridge it into the call. WebKitGTK has no display audio. Native window audio and the custom
picker remain feature gaps. The page's
`DisplayPicker` never receives sources. `desktop/README.md` links the WebKit implementation.

A call keeps its timers. Windows builds pass `--disable-background-timer-throttling`,
`--disable-backgrounding-occluded-windows` and `--disable-renderer-backgrounding` to WebView2; the
page's own idle work (audio rests, no video pulled off screen) still stops. macOS uses Tauri's
`backgroundThrottling` policy, WKWebView's inactive scheduling switch for macOS 14 and newer, and an
`NSProcessInfo` activity that stops App Nap for the call. Linux WebKitGTK has neither switch, so the
page holds a Web Lock (`web/src/lib/backgroundLock.ts`) for the length of a call and, on X11, a call
that starts or continues while the window is hidden keeps it mapped off screen with no taskbar
button. On Wayland an application cannot move its own window, so it is left where it is and the Web
Lock is the only measure. The window goes back to being hidden when the call ends. Background
throttling cannot be toggled at runtime in WebView2, which is why the three flags are always on.
An X11 window parked off screen still reports hidden and unfocused to the page, so notification
and video-visibility decisions follow what the person sees. Opening it from the tray or bridge
restores its original position and taskbar entry before focusing it.

Updates use Tauri's updater plugin (`src-tauri/src/updater.rs`) and the project's GitHub releases.
Every artifact is verified against the Ed25519 (minisign) public key in `plugins.updater.pubkey`
before it installs; there is no unsigned fallback. The private key is never committed: it is
`TAURI_SIGNING_PRIVATE_KEY` and its password `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` in the repository
secrets, and the matching public key is regenerated only with `npx @tauri-apps/cli signer generate`.
The app checks 10 s after start and then every minute, downloads a found update by itself, and
installs only when the page's Update dialog asks (`updates.restart`), so nothing installs on quit.
Linux deb installs cannot self-update; they get the release page (`manual`). The endpoint is the
current repository's `releases/latest/download/latest.json`, baked at build time from
`NEXA_UPDATER_ENDPOINT` so a fork updates from its own releases; the checked-in endpoint is the
public project's. `src-tauri/tauri.nosign.conf.json` turns updater artifacts off for local and pull
request builds.

Releases are built by `.github/workflows/desktop.yml`, never on the server: a `desktop-v*` tag builds
Windows (NSIS), Linux (AppImage and deb) and macOS for x64 and arm64, signs the updater artifacts,
attaches `latest.json` and creates a draft release. Pushes to `tauri-desktop-tests`, pull requests
and manual runs build without signing or creating a release. Native smoke
jobs run on Linux, Windows and both Macs before installer builds. `desktop/tests/smoke/run.py` runs
the debug binary against a loopback page and checks the bridge, partial settings updates, media
API availability and the Windows shared-audio capability report. Linux and macOS use disposable
home/config directories; Windows requires a fresh
test account and refuses an existing Nexa profile. On Linux it uses Xvfb and sends a real close
request during call mode, then checks
logical visibility and restored position, including a call started while hidden. `run.sh` delegates
to it. These tests do not prove screen capture, window audio or real-call performance. `make
desktop` builds a local bundle without signing; `make desktop-release` only points at the workflow.
The old Electron builds (1.2.x) cannot install the Tauri app themselves: existing users download it
once, and the old `latest*.yml` files and installers in `DESKTOP_DIR` are left as they are. New app
data lives in Tauri's app config directory (`xyz.evilion.chat`), not the Electron profile.

`REQUIRED_DESKTOP` (`web/src/lib/requiredDesktop.ts`, 1.2.8) and `components/shell/RequiredUpdate.tsx`
still gate old Electron copies. The Tauri app reports version 2.0.0, so the gate does not apply to it.

## Android app (`android/`)

Native Kotlin and Jetpack Compose (package `fish.nexa`, minSdk 26, targetSdk 36; debug builds are
`fish.nexa.debug`). It talks to the server as deployed, so it never depends on unreleased server
changes. Libraries: OkHttp, kotlinx.serialization, Coil 3, Media3 ExoPlayer, Lottie, BouncyCastle,
argon2kt, ZXing and webrtc-sdk. Native code: `app/src/main/cpp` (call frame crypto and H.264 in
`frame/`, RNNoise) through CMake, and `android/ydoc` (yrs, the Rust Yjs, built with cargo-ndk) for
notes. CMake also fetches pinned Xiph RNNoise source and weights matching rnnoise-wasm
2025.1.5, plus whisper.cpp 1.9.5 for local transcripts.

- `Nexa.kt` is the object graph; `data/State.kt` keeps each store as an immutable snapshot in a
  StateFlow, mirroring the web's zustand stores; `data/Session.kt` handles the gateway's events.
- Packages: `crypto` (the web's crypto), `net` (`Api`, `Gateway`, `Models` as the wire types),
  `data` (stores and logic ported from `web/src/lib` and `stores`), `media` (pictures through Coil,
  encrypted downloads and streaming, Twemoji, sounds), `notes` (CRDT documents, sessions, sharing),
  `search` (the web's index, ported), `voice` (calls: transports, audio routing, the call service,
  picture in picture, notifications), `notify` (alerts over the live connection), `ui/...` (screens
  by area, `ui/theme` with tokens from `tokens.css`, `ui/markdown` with the ported parser).
- Layout by width (`ui/shell/Shell.kt`, the web's breakpoints): below 768 dp the rail and
  sidebar are a drawer and the member list a drawer from the end side, both opened and closed by
  a sideways swipe anywhere over the shell (a swipe that starts on an open drawer closes it);
  from 768 dp (`Nav.wide`) the rail and sidebar stay beside the page and the menu button goes;
  from 1100 dp (`Nav.roomy`) the member list stays beside the chat too, shown or hidden by
  `Nav.toggleMembers` (`Nav.membersPinned`). From 768 dp User Settings keeps its list beside the
  open page (My Account first), as the web's settings do (`SettingsLayer` in `ui/settings/Settings.kt`),
  and so do Server and Channel Settings (`SettingsFrame` in `ui/server/Frame.kt`). A slow drag settles to the side it was dragged
  to once it has gone 40% of the way, from open or closed, on both the web (`AppShell.tsx`) and
  Android.
- Nexa and moderation: `data/NexaInbox` is a member's side (standing, the Nexa conversation, case
  and bug report threads) and `data/Moderation` the staff's (what waits on them, `mod_unread`, the
  panel's requests, and `events` its open views follow). `ui/nexa/ThreadChat` draws a case thread or
  a bug report as a conversation for both sides, as the web's does. The staff panel is native
  (`ui/moderation`: `Panel` with its tabs, `Users`, `Bots`, `Person`, `Moderate` and the other takedown
  sheets, `Cases`, `Bugs`, `Servers`, `Expressions`, `Logs`, `Parts`, `Words`), at
  `Route.Admin(path)`, whose path is the web's below `/channels/@admin`.
- Apps (`data/Apps`, `AppCommands`, `Interactions`, `BotCopies`, `Pickables`, `net/AppModels`) and
  their screens in `ui/apps` (`CommandMode` with `CommandBox` and `CommandList` in the composer,
  `AppMessage`, `AppForm`, `SelectBox`, `Authorize` for the consent sheet that `/oauth2/authorize`
  links open, `ReadingApps` for the apps a channel's start names, `ReadingGlass`, `AppMenus` with
  `MenuItem.Action.picture` in `ui/layers/Layers.kt`), `AppLinkCards` in `ui/chat/LinkCards.kt`, the
  tap-to-show command invocation in `AppMessage.InteractionLine`, and the portal in `ui/settings`
  (`DeveloperPortal`, `AppDetail`, `PortalParts`, `AuthorizedApps`) and `ui/server/AppsSection`.
  `ui/markdown/Webhook.kt` is the one parser of webhook and app messages (the web's
  `lib/webhookMessage.ts`, checked by `web-hook-vectors.json.gz`). Both run the content, embed
  descriptions, field values and text displays through `discordMarkdown`, which puts a fence opened
  mid-line on its own line and splits a `**`, `__`, `~~` or `||` span that crosses lines into one per
  line, since bots write Discord's markdown and Nexa's parser keeps spans on one line.
- Layers: `openSheet` takes `over` (a line over the title) and `heading` (drawn in place of the
  title, `SheetTitle` drawing the words), and a footer with nothing in it takes no room.
  `Modifier.floatAbove(gap)` (`components/Controls.kt`) hangs a list over what it sits on top of
  (the composer's suggestions) without taking room from it. `SQUARE_AVATAR` is the web's square
  avatar, `TickBox` a checkbox's box, and `RadioCard(tick = true)` the option card with one.
- `data/ThreadLast` keeps each thread's newest message for the card under the message it hangs from
  (the web's `threadLast`), and `KeyManager` asks the holders again (`/keys/request`) while it waits.
- There is no push service: `notify/ConnectionService` keeps the connection open in the background
  (on unless turned off) and `Notifier` raises alerts on the device.
- The exported share target accepts only external `content:` URIs, at most ten files. `file:`
  URIs and Nexa's own FileProvider authority are refused before staging, so another application
  cannot ask Nexa to open and share files with its private filesystem permissions.
- Session reset cancels pending notification posts, removes tagged personal notifications and their
  history. Notification publication checks the same account generation under the reset lock, so a
  finishing avatar load cannot repost an earlier account's alert. Reset also clears floating layers
  and navigation, and deletes decrypted media. Cache filenames also
  include the numeric account id, so one account never reads another account's cached attachment.
- Updates (`ui/AppUpdate`) work the way the desktop app's do: a look at `/api/v1/android/latest` ten
  seconds after the app opens and every minute while it is in front (failures back off to fifteen
  minutes), then the build for the phone's processor downloads in the background, resuming a broken
  download with a Range request (If-Range on the SHA-256 the server sends as its ETag), and is
  checked against its SHA-256 before it is offered. Install hands it to a `PackageInstaller` session
  whose result comes back to `UpdateInstallReceiver`; while Android's screens for it are open,
  `voice/Pip.held` keeps a call from floating over their buttons, which Android would then refuse to
  press. Settings, About shows where it stands and the user panel a green button while a build
  waits. Debug builds (`fish.nexa.debug`) never update themselves. To try it end to end, build two
  releases with `tools/android_release.py --server http://10.0.2.2:$PORT --to <scratch dir>` (the
  second numbered after the first by publishing into the same folder, both with the debug network config copied into
  `src/release/res/xml/` for that build only) and serve the newer folder with `ANDROID_DIR`.
- Calls (`voice/`): `ShareAdapt` sizes a 60 fps share as the web's `shareAdapt.ts` does, and
  `ui/voice/VoiceModeration.kt` is the moderators' voice menu (see Calls).
  `AudioRoute` puts the phone in call mode but never takes the audio focus, so music and videos in
  other apps play on beside a call and its microphone keeps sending. Only a phone call (the phone
  ringing or in a call, read from the audio mode once a second) makes the call quiet both ways
  until it ends.
- Compose redraws a composable only for state it collected: `Nexa.settings`' getters and the stores'
  `get(...)` are plain reads, and a row that reads them can keep an old answer when its own
  arguments did not change. Read what a composable shows through `collectAsState()` (or
  `ui.shell.settingOn(key)` for a yes or no setting), in the composable that shows it.
- Hot rows use `ui/StoreState.kt`'s `collectEntryAsState` for one user, presence, setting,
  typing channel, read marker or message channel. Its distinct entry flow avoids redrawing every
  visible row for unrelated gateway updates; changing the entry key starts with that entry's own
  snapshot. Identity labels observe their resolved role look rather than every server snapshot.
  `RoleLooks.of` visits only the member's assigned roles, with no cache keyed by `ServerState`,
  whose structural hash walks the entire member roster. `ServerState.permissionRoles` parses
  permission strings once per immutable server snapshot. Channel trees and sidebar threads group
  children once instead of scanning all channels per parent. Avatars observe only their animation
  switch, remember their grapheme initial and use an offscreen compositing surface only when a
  presence dot needs its transparent cutout.
- Typed REST response decoding (`net/Api`) and member-page decoding run off the UI thread and
  preserve coroutine cancellation. `data/MemberRoster` filters channel permissions and sorts names
  on a worker before `MemberList` draws them. Regression tests cover large-roster name lookups,
  permission snapshot changes, member visibility and sorting, distinct store entries, response
  decoding and channel-tree ordering.
- The build's `copyWebAssets` task copies the web's locale files, sounds, emoji sprites,
  `whatsNew.ts`, the seasonal themes' pictures and the legal documents into the app; `assets/lucide.json`, `assets/emoji/` and
  `assets/licenses.json` come from `android/tools`.
- Logic ported from the web (markdown, search, themes, the formatting editor, webhook messages,
  crypto, metadata scrubbing, the notes CRDT) is tested against vectors produced by the web's own
  code (`app/src/test/resources/web-*-vectors.json*`; most are regenerated by
  `android/tools/*_vectors.ts`, and each file's header says how).
- Releases: `make build` (every deploy) runs `tools/android_release.py --if-changed`, which builds a
  release only when `android/` or the web files its build copies in changed since the one published
  in `downloads/android` (the manifest keeps a `source` hash of them), numbers it after that one
  (versionCode + 1 and the last number of versionName + 1, passed to Gradle as `-PnexaVersionCode`
  and `-PnexaVersionName`, so nothing in git is edited), signs it with the private local key configured in `android/signing`
  and publishes it there; chatd offers it at once and the updater brings every phone up to it.
  `make publish-android` builds one regardless. The APKs and manifest are not in git. Builds up to
  1.1.0 were signed with an older, lost key and cannot update to these; those phones reinstall.
  A release build needs the JDK and SDK (found as under Commands, `~/android` on the server).

## Tools (`tools/`)

- Testing: `check.sh`, `e2e.py`, `screenshot.py`, `text_layout_audit.py`, `seed.py`, `webhook_e2e.py`, `bot_e2e.py` with
  `bots/`, `apps_ui.py`, `discord_e2e.py`,
  `sfu_call.py`, `sfu_guest.py`, `call_quality.py` and `share_quality.py` with `netsim/`, `ios_sim.py`
  with `ios/`, `app_size.py`.
- Fonts: `android/tools/name_fonts.py` (the name style fonts for Android, from the web's Fontsource files).
- `requirements.txt` requires Brotli 1.2.1 or newer for the WOFF font tooling's decoder.
  `advert-video` overrides Remotion's pinned Zod with 4.6.7 for its collection-validation fix.
- i18n: `i18n_check.py`, `i18n_keys_used.py`, `i18n_apply.py` (write or merge a language's
  values), `i18n_extract.py` (pull hardcoded English into keys); `android/tools/add_strings.py`.
- Releases: `android_release.py`; the desktop app is built by
  `.github/workflows/desktop.yml` on a `desktop-v*` tag, and `licenses.py` reads its crates.
- Assets: `twemoji_font.py`, `twemoji_font_check.py`, `twemoji_sprites.py`, `trace_wordmark.py`,
  `home_shots.py` and `home_gifs.py` (the front page's pictures and loops), `trailer.py` (raw footage
  of the app, with a macOS pointer at its real size that moves as a hand does) and `advert.py`, which
  films test1 and test2 with `trailer.py`'s recorder, logs when things happen in each take, measures a
  song (`advert_edit.py`, needs librosa; `--bpm` when it hears the wrong tempo: its beats, drops and
  the piano's notes, which the quiet parts' words land on) and cuts the takes to its beats. The cut is
  drawn by Remotion (`tools/advert-video`, its own npm project: motion blur, the song laid in on one
  clock), using Playwright's Chromium since Remotion's own headless shell does not start on the Mac;
  the takes' frames are served to it over localhost. The thumbnail is still drawn on `advert_stage.html`.
  `--cut` picks the trailer, a faster cut of it (`fast`) or a 1080x1920 short for phones (`short`),
  which uses takes filmed in Nexa's phone layout (`film --phones`, saved as `m-<shot>`), each shown
  as its screen at the frame's full width so the app's words read on a phone. Each shot
  gives up after `SHOT_LIMIT` seconds rather than hanging the run. The seed makes a lived-in world
  (`seed_world`: a server with an icon, categories, channels and coloured roles, more servers in each
  list), people staying test1 and test2. A take's marks can log where on screen they happened
  (`tk.mark(label, focus)`), and the edit's cameras are keyframes (`Cut.cam`) that open on the whole
  app and ease in to those places, close enough to read.
- Schema: `gen_pg_schema.sh`, `pg_schema.py`.

## Deploying (only when the owner says "deploy")

- A web-only change: `make web`. chatd reads `web/dist` from disk on every request, so it is live at
  once, with no restart.
- A server change: `make server` (it keeps the binary it replaces as `bin/chatd.prev`), then
  `sudo systemctl restart xyz.evilion.chat` (the unit runs `run.sh`). `make build` builds both,
  then the Android app when it changed (see Android, Releases). Desktop installers are built
  separately by GitHub Actions on a `desktop-v*` tag. Say so when the deploy runs a
  database migration.
- Back up first (`bash backup.sh`, which keeps only the newest set) only when the request asks for it.
- To roll back: `cp bin/chatd.prev bin/chatd` and restart (not across a migration, see Database).
- Android goes out with `make build` when it changed. Desktop releases are built by GitHub Actions
  and published from their draft release, separately from a server deploy.
