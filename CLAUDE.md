# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Nexa is the end-to-end encrypted chat app at https://nexa.fish (also https://mathtest.lol; the old
address https://chat.evilion.xyz moves browsers on). This repository is all of it: the Go server
(`cmd/`, `internal/`), the React + TypeScript web app (`web/`), the Electron desktop app that runs the
web app (`desktop/`) and the native Android app (`android/`, Kotlin + Jetpack Compose). README.md is the
public overview (features, privacy boundaries, an isolated local quick start, development links) and
`ENCRYPTION.md` the public encryption explanation (key storage and distribution, recovery, bot access,
trust boundaries, links to the code). The original design spec is
`docs/superpowers/specs/2026-09-11-chat-design.md` (section 6 is the cryptography). First-party code is
MIT licensed (root `LICENSE`, copyright `2026 terrydavisthe2nd / Nexa`); third-party components keep
their own licenses and notices.

The rules come first, then commands and testing, then how each part is built.

## Rules that always apply

### Keep this file current

Every future session learns the codebase from this file, so it must stay true and complete. A change
that makes anything here wrong or incomplete updates it in the same commit: a new, renamed or removed
package, module, route group, gateway event, table, setting, command, tool, test helper, build or
release step, workflow or convention, and every standing rule the owner gives. Fix anything already
wrong when you notice it. Write what you checked in the code, in the style of the rest of the file:
plain, compact sentences without em dashes that say where things live, the conventions, the decisions
and what must not break. Leave what the code already shows (layouts, sizes, animations, step-by-step
mechanics) to the code.

### Building is deploying

`web/dist`, `bin/chatd`, `downloads/android` and the desktop releases in `DESKTOP_DIR` are what the live
instance serves, so `npm run build`, `make web`, `make server`, `make build`, `make publish-android`,
`make desktop-release`, `make publish-desktop`, `tools/desktop_release.py` and
`tools/android_release.py` without `--to` publish immediately. Never run them to check a change; use the
commands under Commands, which write nothing live. Never test against port 8090, `data/` or the repo's
`chatd.env`. On this server never start chatd from the repository root either (`make run`,
`go run ./cmd/chatd`): it reads the live `chatd.env` and `./data` and migrates the database before it
listens, so an unreleased build would migrate the live database. Deploy only when the owner says
"deploy" (see Deploying).

### All UI design goes through the frontend-design skill

Before any UI design work, however small (a new screen or component, a redesign, a change to layout,
spacing, colour, type or motion, on the web, the desktop app or Android), invoke the
`frontend-design:frontend-design` skill and follow it. No exceptions. `android/DESIGN.md` is that
process applied to Android; read it too before Android UI work.

### Web and mobile: both implemented, both tested

Every feature and change is implemented properly on both the web app and the Android app, and the web at
phone width and the Android app look and behave as alike as possible (layout, sizes, colours, words,
order of things). A phone improvement made on one side is brought to the other (`android/DESIGN.md`
section 7 keeps the list). Plugins and the homepage demo are web only. Nothing is done until it has been
seen working (how, under Testing):

1. Web: on a throwaway server, drive it in a headless browser (Playwright) at 1366x768 and 411x914 (the
   emulator's screen in CSS pixels) and look at the screenshots.
2. Android: build the debug app against the same server and use every screen touched in the emulator, in
   Dark, Light and one preset.
3. Put the phone-size web screenshots next to the emulator's and fix whichever side is off.

Passing unit tests do not replace looking at the screens.

### Android updates go over the air first

Ship Android changes over the air whenever possible, and push a new APK only when OTA cannot carry
the change. OTA content packs (`fish.nexa.data.Ota`) carry everything the build copies from the web
except Twemoji: translations, What's New, the Terms, Privacy Policy and their version, sounds and
seasonal pictures. Anything else (Kotlin, Android resources, Gradle, native code, Twemoji) needs an
APK; Android cannot load new compiled code into the installed app. So prefer fixes that live in that
content, keep Android reading such content through `Ota.open`, `Ota.uri` or `Ota.setDataSource`
(never `assets` directly), and do not mix content into a code change without need. `make build` picks
the route by itself (see Android, Releases).

### Build what was asked

Build the literal request. "Custom" packs or themes mean letting people make their own, not adding
built-in ones. If an extra seems worth having, suggest it instead of shipping it.

### Tests earn their place

The owner's rule (2026-11-01): keep a test in the suite only when what it guards is easy to break and
costly when it breaks: crypto and keys, sign-in and sessions, permissions and moderation gates, rate
limits and captchas, privacy (what is sent, stored or shown), migrations and data loss, parsers and
formats another client must match (the vectors), and a bug that already happened. Do not commit tests
for static rendering, copy, layout, trivial helpers or what the type checker already holds; check
those once while working (a scratch test, a screenshot) and leave them out of the suite. Keep each
kept test small and quick: a few hard cases, not a grid of every combination. Adding a test is a
choice to justify, not a step every change takes.

### Motion answers what was done, and nothing blinks

The owner's rule (2026-11-03): motion belongs to the thing someone acted on (a chosen tab's pill
sliding, a chevron turning, what a category or expander brings back unfolding, a message or reaction
arriving, a dialog, sheet or toast leaving). Never fade or dim a whole page, list or pane when someone
switches tabs, conversations, servers or settings pages: it read as the app blinking. What replaces a
view appears at once, or slides in already partly visible. Everything stops under Reduced motion.
Check new motion frame by frame before calling it done (Testing, Motion).

### When the work is done

1. Run the checks (`tools/check.sh` covers most) and the web and Android testing above, and update this
   file if the change touched anything it describes.
2. Commit on `main`; push (`git push origin main`) only when the owner says to. The owner also works on
   another machine and pulls from GitHub, so say what is committed and not yet pushed. Never commit
   `chatd.env`, tokens, private keys, signing passwords, personal screenshots or handoff transcripts.
   Android signing is private local configuration under `android/signing/` (see its README): keep a
   private backup and never replace or regenerate an existing release key. `nexa` is the canonical
   source repository; keep deployment settings and signing material out of Git rather than in a separate
   private source branch. Run `python3 tools/privacy_check.py` before committing.
3. Before every push, run both history checks as separate commands and push only after both have
   printed a pass ("Repository privacy checks passed (all reachable history)" and Gitleaks' "no leaks
   found"); never chain the push after them with `&&` through a pipe (`| tail` hides a failure) or
   push when either is missing or fails:

   ```
   python3 tools/privacy_check.py --history
   gitleaks git --redact --log-opts="--all --full-history" .
   ```

   On the build server Gitleaks 8.30.1 is `~/.local/bin/gitleaks`; if it is missing, reinstall it with
   `GOBIN=$HOME/.local/bin go install github.com/zricethezav/gitleaks/v8@v8.30.1` rather than skipping
   the scan (`docs/REPOSITORY.md` has the workflow).
4. After a round of app changes, leave a throwaway server running the new build and give the owner its
   address, with accounts to sign in with. WebCrypto needs https or localhost, so from another machine
   it is reached through an SSH tunnel to localhost, not the LAN address, or through the dev instance
   (Testing, The dev instance) when the owner asks for it. Do not deploy until they say "deploy".

## Every major feature gets a What's New entry

A change that adds a major feature, redesigns something or changes behaviour people will notice adds an
entry to the What's New popup in the same change. People see the popup once, on the first open after an
update; it is how they find out what changed. Small fixes and polish need none.

- The list is `RELEASES` in `web/src/lib/whatsNew.ts`, newest first. Add a release at the top whose `id`
  is the deploy date (`"2026-09-26"`, then `"2026-09-26.2"` for a second that day), or add items to the
  newest release if it has not been deployed yet.
- An item is a lucide-react icon, a title and one or two plain sentences saying what people can do now
  and where to find it. Title and body are i18n keys named after the release id without dashes and dots
  (`whatsNew.r20260926.<name>Title` and `...Body`), translated into all twelve locales.
- Keep every item in the shape `{ icon: Name, title: "...", body: "..." }` (that order, double quotes):
  Android reads this file with a regex (`fish.nexa.ui.WhatsNew`) and silently skips anything else.
- The popup, `web/src/components/shell/WhatsNewDialog.tsx`, shows the releases an account has not seen
  (tracked in its synced settings), skipping releases older than the account.

## Writing UI text

- The product is "Nexa" in prose and labels.
- Labels are Title Case: buttons, menu items, tabs, navigation, dialog and page titles, headings and
  column headers ("Add Friends", "Create Group", "Mark as Read", "Add to Group"), short articles,
  conjunctions and prepositions lowercase unless first or last. English only; other locales follow their
  own capitalization. Checkbox and toggle labels use sentence case ("Hide their messages completely").
- Descriptions, hints, toasts and errors are short, plain sentences that sound like a person. Avoid em
  dashes; use a comma, a full stop or parentheses.
- Never describe the owner in the third person or sell Nexa as run by one person (app, home page,
  Terms). Contact details read "Contact the owner (nora) here: ...".
- Describe Nexa on its own terms and never compare it to Discord. Name Discord only where a feature
  really talks to it (the desktop Rich Presence relay, Discord activity sync, Discord template import).
- Never hardcode user-visible English. The web uses `t("key")` / `<Tr>` from `@/i18n`; Android uses
  `t("key")` on the same catalogues (its own keys start with `android.`). Add every new key to all
  twelve files in `web/src/i18n/locales/` with real translations
  (`python3 android/tools/add_strings.py strings.json` writes a batch into all twelve, in order);
  `python3 tools/i18n_check.py` must pass.
- Text about what Nexa stores or sends (Terms, privacy policy, settings) is checked against the code
  that runs (handlers, the SQL in `internal/store`, the client crypto), not comments. Say "not
  end-to-end encrypted" rather than "not encrypted" when the server seals something itself.

## Commands

From the repository root, each line on its own. None of these write anything live.

```
tools/check.sh                    # the whole pre-publish check: Go build/vet/tests, privacy, web typecheck,
                                  # tests and scratch build, desktop typecheck/tests/bundle, i18n keys
go build ./... && go vet ./...
go test ./...                     # `make test-go` adds -race; the api package then takes minutes
go test ./internal/api -run TestEditKeepsTheKeyThatOpensIt -count=1
CHAT_TEST_POSTGRES=postgres://... go test ./internal/store ./internal/api    # the same suites on Postgres
cd web && npm run typecheck
cd web && npx vitest run          # one file: npx vitest run src/lib/perms.test.ts -t "test name"
cd desktop && npm run typecheck && npm test
python3 tools/i18n_check.py       # every locale has every key, placeholder and plural form
python3 tools/i18n_keys_used.py   # must say "web: ... 0 missing"; Android's runtime-built keys are listed, as expected
python3 tools/privacy_check.py    # private artifacts must not be tracked; --history checks every reachable revision
```

`make test` runs the Go suite with `-race` plus the web and desktop suites. `internal/db`'s Postgres
tests start an embedded Postgres, downloaded the first time (`SKIP_PG_TESTS=1` skips them). For hot
reload, `cd web && VITE_API_TARGET=http://127.0.0.1:$PORT npm run dev` serves the web app on :5173;
without `VITE_API_TARGET` it proxies to :8090, the live server.

Building without deploying, into a scratch directory `$S`:

```
(cd web && npx vite build --outDir $S/dist --emptyOutDir)
go build -o $S/chatd ./cmd/chatd
```

Android (publishes nothing). On the deployment server the JDK and SDK live in `~/android`, not on the
PATH, so set them in every command:

```
export JAVA_HOME=$HOME/android/jdk ANDROID_HOME=$HOME/android/sdk
(cd android && ./gradlew :app:assembleDebug)   # -PserverUrl=http://127.0.0.1:$PORT points it at a local chatd
(cd android && ./gradlew :app:testDebugUnitTest)
```

On the Mac, Homebrew's `android-commandlinetools` holds the SDK, NDK and emulator; the same Gradle
commands take `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` and
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

Work that goes with certain changes:

- Dependencies added or upgraded in any build: `make licenses` rewrites `web/src/data/licenses.json`
  (Settings, Licenses); `android/tools/android_licenses.py` does Android's (steps in its header).
- The web's markdown, search, themes, formatting editor or webhook messages changed: regenerate the
  Android test vectors (`android/tools/*_vectors.ts`) and make the Kotlin port pass them. URL oracle
  entries depend on Node/ICU: check a changed expectation against current Chromium too before updating
  it, and never weaken host checks just to match a stale vector.
- lucide-react upgraded: `python3 android/tools/lucide_assets.py`. emojibase-data or `@twemoji/svg`
  upgraded: `node android/tools/emoji_assets.mjs`, `tools/twemoji_sprites.py`, `tools/twemoji_font.py`,
  then `tools/twemoji_font_check.py`.

## Testing

### A throwaway server

chatd reads `chatd.env` from its working directory, and the repo's copy is the live configuration (live
keys and bot tokens; `./data` is the live database). So run the scratch build from its own directory
with its settings in the environment (`$S` an absolute path, `$PORT` anything but 8090):

```
(cd web && npx vite build --outDir $S/dist --emptyOutDir) && go build -o $S/chatd ./cmd/chatd
(cd $S && LISTEN=127.0.0.1:$PORT PUBLIC_URL=http://127.0.0.1:$PORT DATA_DIR=$S/data \
  WEB_DIST=$S/dist EGRESS_PROXY=off ./chatd)        # in the background
tools/.venv/bin/python tools/seed.py http://127.0.0.1:$PORT
```

`seed.py` registers alice and bob (password `correct horse battery staple`), makes a server whose
#general bob joins, posts one message of every kind of formatting and prints the ids. Registration is
open by default, the first account becomes the owner (staff rank Developer, the oldest), and captchas,
SFU calls, GIF search and the other keyed services stay off without their keys.

### The dev instance (dev.nexa.fish)

When the owner asks for the dev site, run the new build at https://dev.nexa.fish: the tunnel sends it to
127.0.0.1:6969. `make run-dev` builds the web app into `web/dist-dev` and the server into
`bin/chatd_dev` (never `web/dist` or `bin/chatd`, so nothing live changes) and starts it from the
repository root against its own database, `DATA_DIR=./data-dev` (this is the one sanctioned exception to
never starting chatd from the root: it reads `chatd.env` but never `./data`). It runs in the foreground,
so start it in the background; stop it with `pkill -f bin/chatd_dev`.

- `chatd.env`'s real captcha keys fail there unless dev.nexa.fish is on the Turnstile widget's
  hostnames, so every captcha says "The captcha check failed". Run it with Cloudflare's test keys in
  the environment, which win over `chatd.env` and never show a challenge (`HCAPTCHA_SITE_KEY=` and
  `HCAPTCHA_SECRET_KEY=` empty turn the fallback off too):

  ```
  make web-dev server-dev
  TURNSTILE_SITE_KEY=1x00000000000000000000AA TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA \
    HCAPTCHA_SITE_KEY= HCAPTCHA_SECRET_KEY= \
    LISTEN=127.0.0.1:6969 PUBLIC_URL=https://dev.nexa.fish DATA_DIR=./data-dev \
    WEB_DIST=./web/dist-dev ./bin/chatd_dev -env chatd.env      # in the background
  ```

- `data-dev` keeps its accounts between runs and is not the throwaway server's database: alice and bob
  exist there only once seeded. Before handing it over, check (`sqlite3 data-dev/chat.db "select username
  from users"`) and seed what is missing through localhost, `tools/.venv/bin/python tools/seed.py
  http://127.0.0.1:6969` (it registers alice and bob, so it fails if they already exist), then give the
  owner the address and the logins (password `correct horse battery staple`).
- It still has `chatd.env`'s other live keys (Realtime calls, Discord sync, GIFs, Stripe), so what it does
  with them is real; test on a throwaway server instead when that matters.

### Web in a headless browser

Playwright runs from `tools/.venv` (setup in `tools/README.md`). Screenshots at both sizes:

```
tools/.venv/bin/python tools/screenshot.py http://127.0.0.1:$PORT $S/shots \
  --login "alice:correct horse battery staple" \
  --paths /channels/@me /channels/<server id>/<channel id> --viewports 1366x768 411x914
```

Widths under 768 get a mobile, touch context, and the app switches to its phone layout below 768 px. For
flows (clicking, typing, two people at once) script Playwright with `tools/e2e.py`'s `Session`
(`register`, `login`, `send`, `open_dm_with`, `shot`, ...) or run `tools/e2e.py` itself (`--flows`,
`--serve`), with `E2E_WEB_DIST` and `E2E_CHATD` set to the scratch build (otherwise it tests the
deployed `web/dist` and `bin/chatd`, not your changes). A new account in a new browser gets the Terms
sheet first; `Session` and `screenshot.py --login` agree to it. Stable selectors are the `data-testid`
attributes and aria labels the scripts already use. iPhone keyboard and viewport behaviour needs
`tools/ios_sim.py` on a Mac (`tools/README.md`).

### Android in the emulator

The AVD is `nexa_test`: a Pixel 7, 1080x2400 at 420 dpi (411x914 for the web comparison), booting
headless in about 30 seconds. On the Mac the equivalent AVD is `nexa36`, which takes
`app-arm64-v8a-debug.apk` instead of the x86_64 APK.

```
ADB=$HOME/android/sdk/platform-tools/adb
(cd android && ./gradlew :app:assembleDebug -PserverUrl=http://127.0.0.1:$PORT)   # JAVA_HOME, ANDROID_HOME as above
$HOME/android/sdk/emulator/emulator -avd nexa_test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swangle_indirect           # in the background (swiftshader_indirect segfaults on profiles)
$ADB wait-for-device                                # then poll until `$ADB shell getprop sys.boot_completed` is 1
$ADB install -r android/app/build/outputs/apk/debug/app-x86_64-debug.apk
$ADB reverse tcp:$PORT tcp:$PORT
$ADB shell am start -n fish.nexa.debug/fish.nexa.MainActivity
$ADB exec-out screencap -p > $S/android.png
$ADB emu kill                                       # when finished
```

- `-no-snapshot-save` throws away the whole session, installed app included, so install after every
  boot.
- Debug builds allow plain http only to 127.0.0.1, localhost and 10.0.2.2, hence `serverUrl` on
  127.0.0.1 plus `adb reverse`.
- Driving it: `$ADB shell uiautomator dump /sdcard/ui.xml && $ADB exec-out cat /sdcard/ui.xml` lists
  every element with its bounds; then `$ADB shell input tap X Y`, `input text` (`%s` for a space) and
  `input keyevent 4` (Back, which also closes the keyboard).
- Before trusting a screenshot, check that `$ADB shell dumpsys window | grep mCurrentFocus` names
  `fish.nexa.debug`. After a cold boot, Play Services' "Sign in with ease" screen or a launcher "isn't
  responding" dialog can sit on top; dismiss it and start the app again.

### Motion

- Web: sample what is on screen every frame with `requestAnimationFrame` from Playwright (computed
  opacity, transform or background per frame) and slow animations with CDP's
  `Animation.setPlaybackRate` for screenshots. A hand-off is right when no frame shows neither the old
  state nor the new one; a timer started with `setTimeout` ignores the slowed rate.
- Android: `adb shell settings put global animator_duration_scale 10` slows Compose animations for
  `screenrecord` (set it back to 1 after). The emulator's software GPU draws blurred layers (an open
  drawer) at about one frame a second, so motion there cannot be judged from a recording: log the
  animated values with a temporary `Log.d` and remove it.

### Other end-to-end runs

Each starts its own throwaway chatd.

- `tools/webhook_e2e.py`: every kind of webhook message, at both sizes.
- `tools/bot_e2e.py`: the discord.py, discord.js and discordgo test bots in `tools/bots/`, pointed at
  Nexa as the portal says and used by a person in a headless browser (`BOT_E2E_PYTHON`,
  `BOT_E2E_NODE_MODULES` and `BOT_E2E_GOPATH` say where each library is).
- `tools/apps_ui.py`: every app screen at both sizes into `tools/out/apps-ui` (including
  `menu_on_a_picture`, `share-warning`, `app-link`, `staff-bots`, `apps-used-options`); `--no-content`
  tries an app without Message Content, `--serve --port N` leaves the server and its discord.py bot
  running for the emulator.
- `tools/discord_e2e.py`: Discord activity sync against the fake Discord in
  `internal/discordsync/fakediscord/cmd/fakediscord`.
- `tools/sfu_call.py` and `tools/sfu_guest.py`: real calls and a guest through Cloudflare Realtime (keys
  from `chatd.env`). sfu_call turns noise suppression off, since its fake microphone is a steady tone
  the speech gate rightly silences.
- `tools/call_quality.py`: calls over bad networks made by `tools/netsim`.
- `tools/share_quality.py`: a hard-motion screen share through Cloudflare, timed frame by frame as the
  viewer sees it; the sharer is Chromium, Firefox or the desktop app's Electron in a hidden window;
  `--up-kbps`, `--loss` and `--down-loss` shape the links through netsim; results in
  `tools/out/share-quality`.
- `tools/desktop_idle.py`: the desktop app's own build (muted, no tray icon, no system-wide keys, Rich
  Presence off, its own profile) checked to sleep when hidden or launched into the tray, stay at full
  speed in a call (with two headless Chromium pages) and still notify and sound; `--long 330` adds
  Chromium's deeper throttling after five minutes.

Whatever a test starts must make no sound: Playwright's headless Chromium is muted, the scripts mute
Electron and Firefox, and the emulator runs with `-no-audio`.

## Repository layout

```
cmd/chatd/          the server's main: wiring, the log file, the maintenance loop
cmd/chatmigrate/    copies a SQLite database into Postgres (DATABASE_URL) in one transaction, all or nothing
internal/           the server's packages (below)
web/                the web app: src/, public/ (manifest, icons, sounds, front-page media, models/), tests/
                    (dev-only layout fixtures), scripts/deploy-guard.mjs
desktop/            the Electron app
android/            the Android app; android/ydoc is the notes CRDT in Rust, android/tools its generators
downloads/android/  the published APKs and manifest.json, served at /android/ (not in git: each server
                    publishes its own)
deploy/             systemd and launchd templates, install.sh, route-dns.sh, warp-proxy.sh, host maintenance
tools/              test drivers, release scripts, i18n and asset generators (below)
docs/               design spec and plans, STATUS.md, REPOSITORY.md, legal/ (the Terms and Privacy Policy
                    the apps show word for word), plugins.md (the plugin API), custom-css.md and examples/, bots.md
                    (making apps, pointing bot libraries at Nexa), UI_TEXT_*, design/ reference captures
audit/              code audits: findings, triage and progress per area; security-2026-10-26.md and
                    history-2026-10-26.md record current fixes, historical dispositions and limits
run.sh, backup.sh   what the service starts (bin/chatd -env chatd.env); the backup of data/
```

Not in git: `data/` (database, media, server secret), `logs/`, `bin/` (chatd and chatd.prev), `backup/`,
`chatd.env`, `web/dist`, `web/node_modules`, `tools/.venv`, `tools/out`, `.claude/`.

## Server (Go)

`go.mod` requires Go 1.27.3 (for the standard library's certificate and HTTP security fixes); build with
that or newer.

`cmd/chatd/main.go` reads `-env` (default `chatd.env`; the process environment wins), loads
`internal/config`, opens and migrates the database (`DATA_DIR/chat.db` or `DATABASE_URL`), makes the
store, the media store (`DATA_DIR/media`), the server secret (`DATA_DIR/server.secret`) and the optional
Redis cache, builds `api.Server`, wires the gateway hub (`srv.Bcast`: `WireServers`, `WireVoice`,
`WireActivities`, `WireDiscord`, last `WireModeration`), starts the background loops (`StartLimiters`,
`StartGuests`, `StartModeration`, `StartPolls`, `StartThreads`, `StartDiscord`, `StartAccountSweep`,
`StartBilling`, `StartRealtime`, the WARP keeper, the usage meter, `maintenance`) and serves until
SIGINT or SIGTERM.

### Packages (`internal/`)

| Package | What it is |
|---|---|
| `api` | REST handlers, middleware, rate limits, static pages; one file per area |
| `auth` | Argon2id of the client's auth key, bearer tokens (only SHA-256 hashes stored), token middleware for users and guests, client IP behind the tunnel |
| `automod` | AutoMod matching (disguised text, wildcards, patterns, presets); `testdata/vectors.json` binds every implementation |
| `botproto` | the bot protocol of Discord's libraries: ids with a fixed offset (so their time reads right), intents, opcodes, close codes, JSON and ETF, zlib and zstd; knows nothing of the store |
| `cache` | optional Redis: shared rate limits, presence and events between processes |
| `config` | settings from the environment, with defaults and validation |
| `db` | SQLite or Postgres pools (`W` single writer, `R` readers), `Tx`, dialect helpers, embedded forward-only migrations |
| `discordsync` | Discord activity sync (a bot in a small Discord server reads linked members' presence); `fakediscord` for tests |
| `egress` | the SOCKS5 proxy (WARP) for every request a person can point the server at; fails closed |
| `games`, `music`, `books`, `screen` | IGDB, Deezer, Open Library and TMDB for profile widgets; art kept, previews streamed through |
| `gateway` | the WebSocket hub: connections, presence, typing, voice signalling, fan-out |
| `ids` | snowflakes (41 bits of milliseconds, 10 of node, 12 of sequence), decimal strings in JSON |
| `media` | blob storage, upload validation, sized variants, metadata scrub |
| `perms` | Discord's permission model and bit values |
| `ratelimit` | keyed token buckets, optionally shared through Redis |
| `realtime` | Cloudflare Realtime: the SFU client, the usage meter, analytics, TURN keys |
| `rpcapps` | names and art of Discord Rich Presence applications, looked up once and kept |
| `store` | every SQL query, grouped by domain; models; the permission cache |
| `stripe` | the Stripe calls plans need and webhook signatures; standard library only |
| `telegram` | the Bot API calls a sticker set import needs |
| `textutil` | cleaning user text, shared by REST and gateway |
| `totp` | RFC 6238 codes for two-factor sign-in |
| `unfurl` | link previews with SSRF protection (public addresses only, redirects checked, bodies capped) |
| `voice` | the in-memory registry of who is in which call and what each device publishes |
| `webauthn` | the server's half of passkeys (a small CBOR reader; ES256, EdDSA, RS256), standard library only; `webauthn/fake` is a software passkey for tests |

### HTTP API (`internal/api`)

- JSON under `/api/v1` with `Authorization: Bearer <token>`. Errors are `{"error": {"code", "message"}}`
  from `E(status, code, message)` and `WriteError`; `WriteJSON` and `ReadJSON(w, r, dst, maxBytes)` do
  the rest. `Handler()` chains `RequestID`, `Recover`, `SecurityHeaders` (a restrictive default CSP that
  document handlers replace with their page policy), `BodyReadDeadline` (30 s for JSON bodies, fifteen
  minutes for others, net/http's post-handler draining included) and `Logging`. `GET /api/health`
  answers `{ok, version}`.
- One file per area (`auth_handlers`, `channel_handlers`, `message_handlers`, `server_handlers`,
  `moderation_*`, `bot_*`, ...), each registering its routes with `mux.Handle("METHOD /path", ...)`;
  route groups are listed in `groups.go` (`routeGroups`), where a new group goes. `ChannelAccess`,
  `ConvForChannel`, `ServerChannelPatch` and `CanRotate` on `Server` are hooks `server_glue.go` installs
  for server channels; DM access is built in.
- Wrappers: `authed` (token, the generic per-user limit, the mute/ban gate), `callAuthed` (accounts and
  guests, for call routes), `guestAuthed`, `userLimited(limiter, h)`, `ipLimited(limiter, h)`. Every
  limiter is made in `NewServer` and named in `limiters()`; one missing from that map is never swept or
  shared. `moderation_gate.go` lists what a muted or banned account may still do; a new signed-in route
  is closed to them until added there.
- Beyond the generic 600 a minute, what a script would turn on people or paid services has its own
  limit: `limReact`, `limMsgAll` (messages and edits across all channels, apps excepted, beside
  per-channel `limMsg`), `limSearch` (people, GIFs, games, the activity preview), `limLookup`
  (signed-out invite previews, profile links and KDF parameters, per address), `limBuild` (channels,
  roles, invites, sticker packs, subchats, guest links, note links, follows, group DM additions),
  `limForward`, `limReport`, `limTurn`. `limLookup` and `limBuild` step up (`stepup.go`, `stepUpUser`,
  `stepUpIP`): past the free allowance a captcha (action `lookup` or `build`) buys a batch (100 lookups
  for ten minutes, 30 more made for an hour), those captchas capped at ten an hour per address and ten a
  day per account; batches are in memory, per process. A server holds at most 500 channels and 250 roles
  (`store.MaxServerChannels`, `MaxServerRoles`, counted in the creating transaction).
- Sign-up: two new accounts a day per IPv4 address or IPv6 /64 (`limSignup`, spent only after every
  other check passed, never for the first account; `too_many_signups`), unless a Developer turned it
  off in Settings, Nexa Admin (instance setting `signup_ip_limit` = `off`, `signupIPLimitOn`; PATCH
  `/admin/settings` with `signup_ip_limit`; web `AdminPage.tsx`, Android `HelpPages.kt`), on top of `limReg`'s ten attempts
  an hour. `Store.CreateRegisteredUser` decides the first account's Developer flag and the initial
  `ADMIN_USERNAME` exception inside the inserting transaction under a shared bootstrap lock, so
  concurrent sign-ups cannot each become the first administrator. KDF, login and recovery refuse
  normalized usernames over 32 bytes before lookup or limiter admission. Visitor limits trust only a
  valid `CF-Connecting-IP` from a loopback proxy, never `X-Forwarded-For`; otherwise the socket peer.
- Captchas (`captcha.go`; Cloudflare Turnstile in `interaction-only` appearance, off until a pair of
  keys is set): `requireCaptcha(w, r, action)` guards what scripts would abuse (sign-up, sign-in,
  recovery, friend requests, a first DM to a stranger, creating and joining servers, creating apps,
  joining notes and guest calls, bug reports, Discovery and public sticker packs, Telegram imports,
  every password check and username change). A refusal names the action in `X-Captcha-Action`, and web
  `api.captchaSolver` (`lib/captcha.ts`) and Android `Api.captchaSolver` (set by `Auth.solveCaptcha`)
  solve it and resend, so a guarded call needs nothing of its own. One captcha shows at a time (web
  `captchaToken`, Android `CaptchaHost.token`): the same action again gets `captcha_pending`, another
  action waits (a token is good for one request), cancelling cancels those waiting, and a control whose
  request may meet one is disabled until it ends. A Turnstile token counts only for the action it was
  rendered with and on one of Nexa's own hostnames (keep the widget's hostnames the same; Cloudflare's
  test secrets answer `result_with_testing_key` and skip both checks). Both apps render once the
  script calls its `onload` function; Android's (`ui/auth/Captcha.kt`) runs in a web view on the
  instance's own address.
- hCaptcha (moved to on 2026-10-20, back to Turnstile on 2026-11-01) stays as a fallback for apps that
  predate the move back: with `HCAPTCHA_*` set, `/instance`'s `captcha` keeps naming `hcaptcha` (old
  apps read only that and accept only that provider) and `turnstile` carries Turnstile's key, which
  current apps read first. Current apps mark every token with `X-Captcha-Provider: turnstile` (web
  `captchaHeaders` in `api/client.ts`, Android `Api.request`); an unmarked token is checked with
  hCaptcha, which binds no action, so only its hostname is checked (its published test secret skips
  that). Remove the fallback once old apps are gone. Desktop 1.2.4 and later frame both
  `challenges.cloudflare.com` and `hcaptcha.com` (`isFrameableUrl`).
- Apps and bots (`bot_*.go`; the guide for bot makers is `docs/bots.md`): the portal (`bot_portal`,
  `/api/v1/applications`), OAuth2, installs and who may use commands (`bot_oauth`,
  `/api/v1/oauth2/authorize`), interactions (`bot_interactions`, `POST /api/v1/interactions`), and the
  bot API: the libraries' REST under `/api/v6` to `/api/v10` (`bot_auth` takes
  `Authorization: Bot <token>` or an OAuth2 `Bearer` token, with per-route buckets, a global limit and
  the protocol's error shapes; `bot_rest_*`, `bot_message_routes`, `bot_commands`, `bot_modals`,
  `bot_audit`), `/bot-gateway` (`bot_gateway`; events translated and filtered by intent in
  `bot_dispatch`), `/cdn/...` (`bot_cdn`), and the objects in `bot_objects` and `bot_messages`. A bot
  route with an app equivalent runs that route's code as the bot (`callNexa`), so bots follow a person's
  rules and everyone sees the same events. A bot is a user with flag 64 (128 when its app reads every
  message); an app's id is its bot's. Interactions and app events go to the app's own address through
  the egress proxy, signed with its Ed25519 key. The first follow-up after a type 5 defer fills in the
  thinking message; `Store.KeepBotCopy` keeps a message command's sealed copy so the bot can fetch its
  files; a slash command's answer keeps the submitted options in `webhook.interaction` (`wmInteraction`)
  for the clients' tooltip, leaving the protocol's `interaction_metadata` unchanged. Voice channels keep
  protocol type 2 and carry text chat through the normal message routes (fetching it also needs Connect;
  `tools/bots/dpy_bot.py` with `tools/bot_e2e.py --only dpy` covers it). Server Settings, Integrations
  lists apps for Manage Server and webhooks for Manage Webhooks.
- Webhooks take Discord's JSON at `/api/webhooks/{wid}/{token}` and the versioned paths Discord's
  libraries build (`/api/v1`, `/api/v6` to `/api/v10`), with `/slack` and `/github` variants. Links in
  webhook and app messages get server-side previews (`unfurlWebhookMessage`, skipping app install
  links).
- `static.go` serves `web/dist` (hashed assets immutable, `index.html` for any other path), its own page
  heads for link previews at `/invite/{code}`, `/call/{code}`, `/note/{token}`, `/@{username}` and `/`,
  and the CSP (plugins load from `blob:`, Turnstile from `challenges.cloudflare.com`).
- Vanity links (`vanity.go`; `GET` and `PATCH /servers/{id}/vanity-url` with `code`, null clearing it;
  Manage Server, `stepBuild`) are invite rows with `invites.vanity` = 1, at most one per server (a
  partial unique index), whose code the server's managers chose: 3 to 32 of a-z, 0-9 and hyphens with a
  letter or number at each end, minus `reservedVanity`. One never expires or runs out, lands people in
  `landingChannel` (the system channel, else the first text channel, as the tag's invite does), and is
  found in any case by `InviteByCodeAny` after an exact match (sign-up stores the lowercase code). It
  stays out of the invite list, Revoke All and account purges, previews with `vanity: true` and no
  inviter, and joins through it are join method `vanity` with no inviter. Changes audit
  `vanity_url_update` (`from`, `to`). Web `server/pages/VanityLink.tsx`, Android
  `ui/server/VanityLink.kt`, both at the top of the Invites page; the bot API's `vanity-url` answers it.
- Group chat activity is posted as `system_member_add`, `system_member_remove`, `system_owner_change`,
  `system_name_change` and `system_icon_change` messages (none for a rename to the same name or removing
  an absent icon). Leaving through DELETE channel or DELETE recipient takes `?silent=true` to omit the
  leave message, offered as an unchecked "Leave without showing a leave message" checkbox (asked once
  for a bulk close that includes groups); removing someone else always posts one. Subchats
  (`subchat_handlers`) can require a password for protected operations, deletion included.

### Gateway (`internal/gateway`, `web/src/gateway`)

- One WebSocket at `GET /gateway`; the client sends `{op, d}`, the server `{t, s, d}`. The first frame
  is `identify` with the token, answered by `ready` (`session_id`, `user`, `users`, `channels`,
  `relationships`, `read_states`, `presences`, `key_grants`, `settings`, `servers`, `emojis`,
  `sticker_packs`, `voice_states`, `group_add_notices`, `guest`, `nexa`, and `now`, from which web
  `lib/serverClock.ts` and Android `data/ServerClock` keep the device's clock offset).
- Client ops: `identify`, `heartbeat` (every 25 s, answered by `heartbeat_ack`), `presence`, `typing`,
  `voice_state`, `voice_signal`, `subscribe_members`, `activities` (desktop Rich Presence), `away`,
  `note_cursor`. The server pings every 12 s at the protocol level. A connection may send 20 ops a
  second, in bursts of 100. Close codes: 4001 session revoked (the client signs out), 4003 banned, 4008
  rate limited, 4009 too many connections. Both gateways reserve unidentified connections from the hub's
  admission budget before upgrading; the bot gateway releases its reservation after identify, resume or
  failure and cannot bypass the main gateway's limits.
- Events are the `Ev*` constants in `protocol.go`: messages, reactions, typing, channels, servers,
  members, roles, presence, users, relationships, read states, voice and calls, keys (`key_grant`,
  `keys_needed`, `key_rotation_needed`), expressions, pins, bans, invites, webhooks, polls, settings,
  threads, follows, AutoMod, the audit log, notes (`note_*`), Nexa and moderation (`nexa_*`, `mod_*`, `mod_report` and `report_update` for safety reports),
  apps (`application_commands_update`, `integrations_update`, and `reaction_remove_all` and
  `reaction_remove_emoji` for what bots clear), guests and sessions. Handlers emit through the
  `Broadcaster` interface in `api/router.go` (`EmitUsers`, `EmitChannel`, ...); the hub sends an event
  only to members allowed to see it (`store/permcache.go`) and encodes it once. A new event touches the
  constant, the emit, its type in `web/src/gateway/events.ts`, its handling in
  `web/src/gateway/dispatch.ts` (which updates the stores), and the same on Android
  (`fish.nexa.data.Session`). An app's answer only one person sees is a `message_create` with flag 64 to
  that person alone, removed by a `message_delete` marked `ephemeral`. A bot's gateway session is an
  ordinary connection on the hub (`gateway.External`, `external.go`) whose frames go to `bot_dispatch`.
- What follows the app without being part of it (plugins, the search index, notifications) listens
  through `dispatchHooks` in `dispatch.ts`, each hook in a try/catch (`each`) so a throwing listener
  stops nothing. `dispatchHooks.onEvent` takes the `AppEvent`s of `gateway/appEvents.ts`, fired once the
  stores have the change and carrying what was there when something goes. Servers, relationships and
  typing are watched through their stores (they change without events or arrive twice); `ready` and
  sign-out fill and empty the stores inside `quietly`, and after a reconnect `reportMissed` tells what
  changed meanwhile. The language changing is `onLocaleChange` in `i18n`. Listeners cannot yet keep a
  deleted message on screen.

### Database (`internal/db`, `internal/store`)

- Every query lives in `internal/store`; handlers never touch `database/sql`. Queries are written once
  with `?` placeholders that `internal/db` rebinds for Postgres; `db.Dialect` covers the differences
  (JSON arrays, greatest/max, case folding with the store's `ulower()` on SQLite, unique violations).
  Ids are snowflakes, times Unix milliseconds, booleans 0 and 1, JSON kept as text.
- SQLite is live and Postgres must keep working: a new migration goes in both `internal/db/migrations`
  and `internal/db/migrations-postgres` with the same number (Postgres starts at `0013_init.sql`).
  Migrations are embedded, forward only, one transaction each; each also adds its undo to the top of
  `undoCommunities` in `internal/db/undo_test.go`, which tests use to rewind the schema; `tools/gen_pg_schema.sh` prints the
  Postgres schema the SQLite migrations add up to, to diff against. chatd refuses to start on a database
  a newer build migrated, so a release with a migration cannot roll back to `bin/chatd.prev`.
- Tables by area: accounts (`users`, `sessions`, `user_ips`, `user_settings`, `user_mfa`,
  `mfa_backup_codes`, `passkeys`, `user_sounds`, `username_holds`), people (`relationships`),
  conversations (`channels` for every kind, `dm_participants`, `channel_overwrites`, `channel_unlocks`,
  `thread_members`, `channel_follows`, `crossposts`), messages (`messages` with `ciphertext`, and
  `plain` for webhook and system text; `attachments`, `reactions`, `pins`, `read_states`, `polls`,
  `poll_votes`), keys (`key_epochs`, `key_grants`, `key_flags`), servers (`servers`, `server_members`,
  `roles`, `member_roles`, `invites` (`vanity` for vanity links), `bans`, `audit_log`, `automod_rules`, `automod_events`), plans
  (`billing`, `server_boosts`, `plan_gifts`, `gift_links`, `users.plan`, `servers.boosts`), expressions
  (`emojis`, `sticker_packs`, `stickers`, `sticker_installs`, `recent_stickers`, `blocked_images`),
  files (`blobs`, `uploads`), calls (`guest_links`, `guests`, `realtime_usage`, `realtime_measurements`,
  `realtime_sessions`, `call_usage`, `turn_credentials`), integrations (`webhooks`, `discord_links`),
  notes (`notes`, `note_members`, `note_invites`, `note_links`, `note_files`, `note_versions`,
  `note_version_files`, `note_keys`, `note_updates`, `note_uploads`), Nexa and moderation (`mod_cases`,
  `mod_messages`, `mod_message_files`, `mod_restrictions`, `mod_announcements`, `mod_log`, `mod_reads`,
  `nexa_reads`, `bug_reports`, `bug_attachments`, `bug_messages`, `bug_message_files`, `client_errors`),
  reports (`safety_reports` with `outcome`, `safety_report_events`, `safety_report_files`,
  `safety_report_snapshot_blobs`), apps (`applications`, `application_emojis`, `application_commands`,
  `application_command_permissions`, `application_guild_installs`, `application_user_installs`,
  `oauth_codes`, `oauth_tokens` (gone with its refresh token, 90 days unused), `interactions` and
  `ephemeral_messages` (15 minutes), `ephemeral_files`, `interaction_files` (a day), `bot_copies`,
  `roles.managed_by` for a bot's role), badges (`badge_defs`, `user_badges`, `badge_prefs`), the
  instance (`instance_settings`).
- Channel types: `dm`, `group_dm`, `subchat`, `text`, `voice`, `category`, `announcement`, `forum`,
  `media`, `link`, `thread`, `private_thread`. `store.IsForum` is forum or media (posts only),
  `store.HoldsNoMessages` is category or link (no messages, invites, read states, mentions or own key). A channel's `key_conv_id` names the conversation whose keys encrypt it:
  itself for DMs and private channels, the server otherwise.

### Permissions

`internal/perms` is Discord's algorithm with Discord's bit values, mirrored by `web/src/lib/perms.ts`
(BigInt, decimal strings on the wire) and Android's `fish.nexa.data.Perms`. Server channel access goes
through the hooks `server_glue.go` installs, answered from `store/permcache.go`. Each permission is held
as Discord holds it:

- Categories sync as Discord's do (`api/category_sync.go`): a channel is synced when its overwrites
  equal its category's (`sameOverwrites`; nothing else records it). Channels made in a category start
  with its overwrites, a category overwrite change is copied to every synced channel, and a channel
  moved into another category (positions `PATCH`, optional `lock_permissions`) takes the new overwrites
  when it was synced (or had none outside a category) and the mover has Manage Roles there.
  `POST /channels/{id}/permissions/sync` is Sync Now. A channel that becomes hidden from @everyone this
  way is flagged for its own key (`markIfPrivate`), and create and sync answer with `key_actions` so the
  actor's app makes it at once. The apps show sync state on the channel's Permissions page (web
  `ChannelSettingsModal` `.syncBar`, Android `ChannelPermissionsPage`) with their own `sameOverwrites`.
- Editing a channel's overwrites needs Manage Roles in it (`channelManageCtx`), not Manage Channels.
- View Server as Role (Server Settings, Roles: the role's page and its menu, kind `roleOrder` with
  `viewAsRole`, `duplicateRole`, `moveRole`) is a preview on this device only: web `stores/viewAs.ts`
  with `lib/viewAsRole.ts`, Android `ServersStore.viewAs`. While on, this account's own permissions in
  that server come from the role plus @everyone (`serverPerms`, `channelPerms`, `canI`,
  `memberHighest` on both), the sidebar hides channels the role cannot view (the server still sends
  them), and a banner offers Exit Preview (web `shell/ViewAsBanner.tsx`, Android
  `ui/shell/ViewAsBanner.kt`). Duplicate Role creates a role from the plain fields, then patches the
  gradient colours and emoji icon (create ignores them); uploaded icons, members and channel overrides
  are not copied.
- Embed Links: previews are made and sealed by the sender's app, so the server sets Suppress Embeds
  (flag 4) on any new or edited message whose author lacks Embed Links there (`embedFlags`) and refuses
  to clear it while they lack it; apps hide flagged previews and composers fetch none there.
- Add Reactions is needed only for a new reaction; every reaction needs Read Message History, without
  which history answers an empty list and only live messages are seen.
- Video (Stream): without it a voice state never says `video` or `streaming`, and the SFU refuses to
  publish or pull a camera or share (`mayStream` in `sfu_handlers.go`, asked at every request); the apps
  refuse to start one and stop one that loses it (web `voice/callPerms.ts`, Android `voice/CallPerms`).
  The mesh cannot be held by the server.
- What the server cannot see, every reader's app holds: Use Voice Activity (push to talk without it,
  `pushToTalkOn`), and Use External Emoji and Stickers, drawn as `:name:` when the author lacks them
  (web `lib/authorPerms.ts`, Android `data/AuthorPerms`, both with `RenderCtx.plainEmoji`; an author
  whose roles are not loaded counts as allowed), and Use Custom Typing Indicators (1 << 60, Nexa's own
  bit, far above Discord's; migration 0045 gave every existing @everyone it): without it in a channel the
  typing bar shows that person with the plain dots instead of their typing style (web `TypingBar.tsx`,
  Android `ui/chat/TypingBar.kt`).
- Use Application Commands (1 << 31) and Use External Apps (1 << 50) are for apps, and @everyone has
  both. Use Soundboard (1 << 42) stays in the maths for the bot API and Discord templates, but Nexa has
  no soundboard and the role editors do not offer it.

### Media and uploads (`internal/media`)

- Blobs are content-addressed under `DATA_DIR/media` (table `blobs`). Public kinds (`avatar`, `banner`,
  `icon`, `emoji`, `sticker`, `tag`, `widget`, `typing`) are served immutable at `/media/{kind}/{id}`
  (the kind must match the stored one; a picture staff blocked is served to nobody), with sized variants
  made once at the next power of two. Animations keep moving at any size (512 px or less as uploaded,
  larger GIFs recomposed by `animated.go`, WebP and APNG as uploaded); `?still=1` gives the first frame.
  JPEG crops and variants apply all eight Exif orientations first (`orientation.go`, versioned variant
  filenames; Android `media/PhotoOrientation`), and sized avatar, banner and icon URLs carry `&v=3` (web
  `api/users.ts`, Android `Media.kt`) to drop stale cached copies. The synced `animateAvatars`,
  `animateIcons`, `animateBanners`, `animateEmoji` and `animateStickers` settings (on by default,
  separate from Reduce Motion) make the URL helpers ask for `?still=1` and pause Lottie and WebM
  stickers.
- Attachments are opaque encrypted blobs served only to the conversation's people
  (`/media/attachments/{id}`); bug report and case files are private to their people and staff; webhook
  files are plain. The server scrubs metadata from pictures and videos it keeps readable (`scrub.go`)
  and the apps do before encrypting (web `lib/scrubMetadata.ts`, Android `MetadataScrub`), all checked
  against `internal/media/testdata/scrub`.
- A server channel's uploaded picture (`PUT` and `DELETE /channels/{id}/icon`, `iconChannel` in
  `channel_handlers.go`; a group DM's owner or Manage Channels) lives in `channels.icon_id` (kind
  `icon`, 256 px). Uploading clears the glyph and saving a glyph puts the picture away; if both are set
  the picture wins (`ChannelGlyph` on both clients, Android's in `ui/shell/Sidebars.kt`). The picker
  uploads at once and edits it as the value `picture`.
- Small files go up in one request (`POST /channels/{id}/attachments`), big ones in pieces through
  `/api/v1/uploads` (Cloudflare caps a request at 100 MB) within disk headroom and per-account caps
  (`roomForUpload`). `reserveUpload` serializes admission and counts bodies still arriving until their
  records exist; temporary chunks have their own disk reservations (at most sixteen staged, two per
  transfer), so stalled retries cannot pile up copies of an offset. Reservations assume the one chatd
  process the deployment uses.
- Clients (web `lib/attachments.ts`, Android `data/Send.kt`) prepare the next piece while one is in
  flight and hold at most the active request and one prepared piece, landing in order at the confirmed
  offset; `encryptChunks` writes frames straight into the request buffer and partial retries send a view
  or offset (web workers regenerate a transferred buffer if they fail before starting). Downloads read
  ahead at most one encrypted chunk (web `crypto/streamDecrypt.ts`; Android `media/Media.kt` and
  `media/Downloads.kt` through the bounded pipe `media/TransferStreams.kt`); authentication and the
  expected size still decide what is saved.
- Saving asks for a location: Android CreateDocument, the desktop app's Save As, web `lib/saveFile.ts`
  with showSaveFilePicker before fetching (large encrypted saves stream from the download worker), the
  system share sheet in mobile browsers (a ready-file dialog gives a fresh tap when loading outlives the
  tap), else the browser's own behaviour. Cancelling saves nothing; failed writes remove the partial
  file.
- `gif_budget.go` walks a GIF's compressed blocks to check frames and canvas work before any full decode
  (checking after `gif.DecodeAll` is too late for memory): cropping allows 600 frames and 150 Mi-pixels;
  resizing caps frames at 600 and falls back to the uploaded animation past its budget.

### Outbound requests

- Everything a person can make the server fetch goes through `internal/egress` (WARP's SOCKS5 proxy,
  `EGRESS_PROXY=warp|off|socks5://...`): link previews (`unfurl`, asked for by the sender's app, which
  seals the preview in the message), the media proxy (only addresses the server signed;
  `POST /proxy/refresh` re-signs old ones), the YouTube, Spotify and SoundCloud players (`/yt/`, `/sp/`, `/sc/`), music cards' details, IGDB, Deezer, Discord's Rich
  Presence apps and Discord templates (`GET /servers/templates/discord/{code}`,
  `POST /servers/import/discord`). Discord's gateway, Cloudflare Realtime and the tunnel go out
  directly.
- Music links (`unfurl/music.go`) carry a `music` card (platform, kind, id, title, artist, album, art,
  duration, count, year, explicit, preview, up to five `tracks`, `player`), held by `sanitizeMusic` in web
  `crypto/message.ts` and Android `MessageCrypto`, drawn by web `chat/MusicEmbed.tsx` and Android
  `ui/media/MusicEmbed.kt`. Spotify (its embed page's `__NEXT_DATA__`), Apple Music (the iTunes lookup,
  playlists from the page), Deezer (its API) and SoundCloud (oEmbed). Each service's terms decide how it
  plays: Deezer's and Apple's thirty-second previews play in the app's own preview player (web
  `profile/previews.ts` `playPreview`, Android `SongPreviews.play`, one at a time with profile songs;
  Deezer's through `/media/music/preview/{id}` since its links expire, Apple's through the media proxy,
  whose type aliases map `audio/x-m4p` to `audio/mp4`); a card that can play a preview has a strip under its cover (seek bar, times, and the shared song volume, web `profile/SongVolume.tsx`, Android `SongVolume` in `ui/profile/Widgets.kt`), and its backdrop is its cover blurred (Android 12 and up); previews never take mute from videos' settings, only from their own; a Spotify track's thirty seconds come from the same recording on Deezer, else Apple Music (`matchPreview`, matched by title, lead artist and length within three seconds; the card names it with `preview_from`); a SoundCloud track plays in the card through SoundCloud's widget kept out of sight and driven by its message API (web `profile/soundcloudWidget.ts`, Android `ui/profile/SoundCloudWidget.kt`, a web view never shown, with its renderer kept at important priority); other Spotify and SoundCloud links open their own player
  through this server (below), and opening one stops any preview. Bandcamp sits behind a bot challenge
  and Tidal's pages carry nothing, so both stay ordinary link cards.
- Other sites' players are served through this server by `api/frame_proxy.go` (`frameSite`: path root,
  hosts, telemetry answered 204, the shim's host pattern): YouTube at `/yt/` (`youtube_proxy.go`),
  Spotify's embed at `/sp/` and SoundCloud's widget at `/sc/` (`music_players.go`, addresses from
  `GET /api/v1/music/player/{platform}/{kind}/{id}`). Tokens are signed per site, so one site's token
  opens no other. The apps frame them sandboxed without `allow-same-origin` (Android in a WebView whose
  page holds the same sandboxed frame); Spotify's embed says `ready` to its parent and takes
  `{command: "play"}`, which the web sends so one press plays.
- A Tenor page (`tenor.com/view/...`, with or without a language before `view`) previews as type `gif`
  of the GIF its `og:image` names, sized by `twitter:player` (`unfurl/tenor.go`), so it draws, hides
  its link and can be favourited like a picker GIF.
- Guarded fetches allow only ports 80 (HTTP) and 443 (HTTPS), checked again when dialing. The
  public-address guard also checks IPv4 embedded in NAT64 and 6to4 addresses and refuses local-use
  translation, Teredo and IPv4-compatible ranges.
- Media links (MP4, WebM, Ogg, QuickTime, MP3, M4A, WAV, AAC, FLAC) play in the existing players on both
  apps, recognised by response type or a bounded file header, never a filename (audio previews carry
  type `audio` and `media_kind: audio`), with the message's link kept above the player. SVG previews
  (extensionless ones too, such as BenchLM's `/api/og`) are images: the proxy keeps its restrictive CSP
  sandbox and serves them as downloads on direct navigation, and the clients draw them only as images
  (Android with Coil's SVG decoder). Every file a preview loads goes through the signed media proxy
  (`unfurl.PlayableMediaType` keeps its accepted types aligned), so no reader fetches the original. HEIC
  and TIFF stay unsupported in browsers. Mislabelled files go by their bytes: `unfurl.ImageAlias` renames
  `image/jpg` and the like, and `NeedsSniff`/`SniffMedia` judge untyped, `text/plain` or unknown
  `image/*` files by their header (AVIF included, SVG never), for both previews and the proxy.
- The GIF picker sends the first Klipy GIF from medium down that fits under `unfurl.MaxMediaBytes`
  (`gifToSend`, by Klipy's `size`; some medium GIFs pass 50 MB), else the animated WebP. Picture
  previews whose proxy fetch fails try again twice, then show the attachments' "could not be loaded"
  state with Retry (web `useRetriedImage` in `chat/Embeds.tsx`, Android `rememberRetriedImage` in
  `ui/media/Embeds.kt`); a link card's picture that still fails is dropped, leaving the words.

### Calls

- `internal/voice` tracks who is in each call. Without Realtime keys calls are a peer-to-peer mesh
  signalled over the gateway's `voice_signal`; with `REALTIME_APP_ID`/`REALTIME_APP_SECRET` every call
  goes through Cloudflare's SFU, chatd being the signalling proxy (`/api/v1/voice/sfu/*`) that decides
  who may pull which track. Frames are encrypted in the apps. An SFU call outlives a dropped socket for
  `voiceGrace`. Guest links let someone without an account into one call; SFU video checks hold a guest
  to their channel permissions too, ICE restarts included (a guest link is not an exception to Video).
- SFU policy (`sfu_handlers.go`, `sfu_policy.go`): mutations serialize per user; a publication is bound
  to its SDP media section and exact mid (no duplicate kinds or mids; an ICE restart reuses its mid);
  pulling a source again reuses its subscription. Mute, deafen, Speak and Video restrictions retire
  existing provider sessions and are rechecked after upstream negotiations and permission changes; voice
  states carry `sfu_generation` so clients rebuild permitted media (older clients may need to rejoin).
  Camera or share off closes the publication.
- Every description the SFU hands a client goes through an `ExtmapHistory` (web `voice/extmaps.ts`,
  Android `voice/ExtmapHistory.kt`, one per connection): Cloudflare reuses a stopped mid for a new pull
  with its header extension ids renumbered, which WebRTC refuses, so lines giving a mid's id a new
  meaning are dropped. Without it, watching a share a second time rebuilt the whole connection
  (everyone on Reconnecting, voices cut).
- Charging (`internal/realtime/meter.go`) follows actual pulled tracks and confirmed provider closures,
  per account and UTC day into `call_usage` (received by the viewer, sent by the publisher, time from
  `SetParticipants`). Shares are estimated at the sharer's plan limit (`shareMaxBps`), never the
  declared bitrate, and a pulled share sound at `StreamAudioBps` (128 kbit/s). Cloudflare can wait five
  seconds before answering about a session, so a close gets `realtime.CloseTimeout` (20 s), a 410
  "disconnected" counts as closed, and `closeSFUSession` retries on `sfuCloseAfter` (about eight
  minutes), then stops charging and logs an error. Timed-out closes once stayed charged until a restart:
  on 2026-10-31 the meter read 2.3 TB against Cloudflare's real 106 GB and refused calls.
- With `REALTIME_ACCOUNT_ID` and `REALTIME_ANALYTICS_TOKEN` (Account Analytics: Read), `StartRealtime`
  (`api/call_usage.go`) polls Cloudflare's GraphQL Analytics every five minutes
  (`realtime/analytics.go`: `callsUsageAdaptiveGroups`, `callsTurnUsageAdaptiveGroups`, account wide,
  since SFU and TURN share the 1,000 GB allowance) up to `AnalyticsLag` ago into `realtime_measurements`
  (`Meter.Measure`); the month's usage is that measurement plus the estimate since. A day's egress is
  attributed by session (`realtime_sessions`), track (names start with the publisher's id) and TURN
  `customIdentifier`, the rest to account 0. The meter pauses video near `REALTIME_MONTHLY_GB` and stops
  calls just short of it. Admins see it on the Calls tab (`/channels/@admin/calls`,
  `GET /moderation/calls`; web `moderation/CallsTab.tsx`, Android `ui/moderation/Calls.kt`) and per
  person (`GET /moderation/users/{id}/calls`, `CallUsagePanel.tsx`). `call_usage` is kept 62 days,
  `realtime_sessions` three (Privacy Policy section 3l).
- TURN (`api/turn.go`, `realtime/turn.go`): credentials last an hour (`turnTTL`), are reissued with 45
  minutes left, are recorded by username only (`turn_credentials`), and a 15 s sweep revokes those whose
  account has been out of a call for `turnGrace` (2 minutes; 3 after a restart). Minting has `limTurn`,
  stops for people not in a call while calls are paused, and cuts an account off for the UTC day past
  `REALTIME_TURN_DAILY_GB` of measured relay. Guest credentials are reused until a minute before expiry;
  concurrent requests from one identity share one mint.
- A server voice channel has its own chat (web `components/voice/VoiceRoom.tsx` reusing `ChannelTalk`
  from `ChatView.tsx`; Android `ui/voice/VoiceRoom.kt`), beside the call from 1100 px (dp) and over it
  below. It is the active channel, and marked read, only while shown, and voice channels count towards
  unread state and Mark as Read (web `lib/unread.ts`, `sidebar/serverActions.ts`; Android
  `Channel.hasChat`).
- Call timers count from `call_since` on voice states (when the channel became occupied, shared by
  everyone, gone once it empties) through the server clock offset, falling back to the earliest
  `joined_at` (web `lib/callTimer.ts`, `components/voice/CallTimer.tsx`; Android `data/CallTimer`,
  `ui/voice/CallTimer.kt`, also the call notification's chronometer); nothing counts while ringing, and
  the time holds while this device reconnects.
- Voice moderation: `PATCH /servers/{id}/members/{uid}/voice` (`mute`, `deaf`, `channel_id`, null to
  disconnect), checked in the person's current channel (Mute, Deafen or Move Members; a move also needs
  View Channel on the destination) and against the role hierarchy; guests can be muted or disconnected,
  not moved. Server mute and deafen persist in `server_members.flags` (2 muted, 4 deafened; 1 is
  AutoMod's quarantine) and reapply on every join. `suppress` means the channel denies Speak
  (`server_mute` is set too, for older apps); a moderator's disconnect leaves with
  `reason: "disconnected"` ("moved" is another device taking the call). Web
  `components/voice/voiceModeration.ts` in `callMenus.ts`, dragging in `stores/voiceDrag.ts`; Android
  `ui/voice/VoiceModeration.kt`.
- Priority Speaker: holding `priorityKey` (Settings, Voice & Video; no key on touch screens, so Android
  only listens) opens the mic and sends `priority: true`, kept only where the person has Priority
  Speaker and never for a guest (`voice.State.Priority`); voice states then say `priority_speaker`, and
  listeners duck everyone else, share sound included, to `PRIORITY_DUCK` (a quarter) (web
  `voice/playback.ts`, Android `voice/Playback.kt`).
- Screen shares, as measured with `tools/share_quality.py` (web `voice/media.ts`, `shareQuality.ts`,
  `shareAdapt.ts`; Android `VideoCap`, `ShareAdapt`): the share dialog's mode (web
  `ShareScreenModal.tsx`, Android `openShareScreen` in `ui/voice/CallMenus.kt`; settings `shareMode`,
  and Custom's `shareResolution`, `shareFps`, `shareBitrate`, `shareKeep`) becomes a `SharePlan` through
  `sharePlan` (Android `VideoCap.sharePlan`): Performance is 1080p60 within 6 Mbit/s, smooth; Quality
  the screen's size at up to 60 fps within the plan's bitrate (12 Mbit/s Supporter, 20 Pro; without a
  plan it shares as Performance), sharp; Custom as chosen; older settings map through `shareModeOf`
  without being rewritten. Sharp shares get the `detail` hint (`text` at 15 fps) and give up frames.
  Smooth ones get no hint and `ShareAdapter` scales them from the encoder's `targetBitrate` (about 0.06
  bits a pixel a frame), because Chromium's `motion` hint and `maintain-framerate` shrink a share at the
  start and never grow it back. A share's sender has high priority and `shareBitrate`'s bitrate (9.9
  Mbit/s at 1080p60).
- A watched share stuck behind a lost frame asks for a keyframe within about half a second
  (`stallWatch.ts`, `sfu.ts` `watchStalls`, `FrameCrypto.requestKeyFrame`); Android's WebRTC cannot ask.
  A receiver whose first frame is not a keyframe asks at once from the frame worker
  (`frameCrypto.worker.ts` `keyAwaited`).
- Through the SFU a share goes out in H.264 when the sender's hardware encoder makes it and everyone
  else advertises `codecs: ["h264"]`, otherwise VP8, republishing when that changes (`republishScreen`,
  `sfuClose`). Android sends H.264 whenever its WebRTC can (`VideoCap.sendsH264`, `SfuTransport`
  `wantsH264`); the web only in a Chromium on a Mac whose encoder is power efficient (`videoCap.ts`
  `sharesInH264`), where VP8 is software. Windows (driver-dependent H.264), Safari and the mesh stay on
  VP8.
- Through the SFU, cameras and watched shares are pulled only while the call's pictures are on screen
  (web `voice/videoShown.ts`, Android `voice/VideoShown` with `Pip.active`), and for
  `VIDEO_HIDDEN_GRACE_MS` (15 s) after (`setVideoShown`); voices and share sound always come in.
- A share's sound is stereo Opus at 128 kbit/s without DTX (`opus.ts` `STREAM_AUDIO_BITRATE`; voices are
  48 kbit/s with DTX), set by `sfu.ts` passing every share-sound mid to `tunedRemote` and `tunedLocal`
  at each exchange.
- The call view fits tiles from fractional ResizeObserver sizes rounded down (rounding up clipped the
  grid at fractional widths); `tools/call_layout_audit.py` checks it through the dev-only
  `web/tests/call-layout.html` fixture in Chromium and WebKit, with a negative control.
- Noise suppression is Xiph RNNoise commit `70f1d256acd4b34a572f999a05c87bf00b67730d` on both (web
  `@shiguredo/rnnoise-wasm` 2025.1.5 in `voice/denoise.worklet.ts`; native CMake fetches source and
  weights under SHA-256), gated by `voice/speechGate.ts` (native `SpeechGate`: hysteresis, 20 ms of
  look-behind, a 200 ms speech tail and smooth gain, so consonant onsets survive). A failed worklet
  falls back to browser suppression. Echo cancellation runs before it (Android: WebRTC's software AEC3,
  not the vendor effect, with its per-track toggle); the two stay independently switchable.

### Signing in: passkeys, QR codes, more than one account

- Passkeys (`api/passkey_handlers.go`, `internal/webauthn`, table `passkeys`) sign in and open the
  messages in one step: the app takes the passkey's PRF output for `nexa passkey prf v1`, derives a key
  with HKDF (`nexa passkey identity v1`) and wraps the identity key, which the server returns only to a
  sign-in that passkey signed. A passkey without PRF output is refused when added (the owner's choice,
  2026-10-25), since it could never read a message. Adding one takes the password (`requirePassword`,
  captcha action `passkey`); signing in takes no username and skips the second factor (user verification
  is required). A passkey belongs to the address it was made on (RP ID from the Origin, else
  PUBLIC_URL's host); Android's origin is the published signing certificate's hash, and
  `/.well-known/assetlinks.json` carries `get_login_creds` for it. Answered challenges are kept in
  memory per process so each signs in once, and a counter that goes back is refused. Web
  `lib/passkeys.ts`, `components/settings/PasskeysSection.tsx`; Android `data/Passkeys` through AndroidX
  Credential Manager, from Android 14 only (the app carries no Play services).
- QR sign-in (`api/qr_login.go`, web `lib/qrLogin.ts`, Android `data/QrLogin`): a signed-out device
  makes a one-time X25519 key, shows a code linking to `/login/qr#<id>.<public key>` (from 768 px; web
  `pages/QrSignIn.tsx`, Android `QrSignInPanel`) and long-polls `GET /auth/qr/{id}`, so test scripts
  load the login page with `domcontentloaded`. A signed-in phone scans it (Android's Scan QR Code in
  `ui/auth/QrLogin.kt`, or any camera opening `/login/qr`: web `pages/QrApprovePage.tsx`, Android
  `openQrApprove` through `Nav.openPath`), sees the asking device's user agent, and on Log In seals the
  identity key to the key in the code, never one the server names. The server hands the session over
  once; requests live in memory for three minutes, per process.
- More than one account (web `lib/accounts.ts`, Android `data/Accounts`): up to five signed-in accounts
  keep their tokens in a list (`ec.accounts`, Android's secure store `accounts`) and their keys where
  they always were. Only one runs; switching ends the call and connection and reopens the app where that
  account last was (web `ec.place:<id>` and a page load; Android `Auth.switchTo` then
  `Nav.restore(switched = true)` from `last:<id>`, clearing open layers first). Add Another Account (web
  `modals/AddAccountModal.tsx`, Android `openAddAccountSheet` in `ui/shell/Accounts.kt`) signs in by
  password (`passwordSignIn` in `stores/auth.ts`, `Auth.passwordSignIn`), passkey or QR code without
  touching the open account, then `addSignedIn` keeps it and switches. Logging out goes on with the next
  saved account. The status menu (web `shell/UserPanel.tsx`, Android `openStatusSheet` in
  `ui/shell/Dock.kt`) holds the notification sounds switch (`notifications.sounds`), Switch Accounts
  (Android `openAccountsSheet`) and Add Another Account.

### Nexa, moderation and safety

- Nexa is the account the service speaks as: notices, announcements, and case threads where a member can
  appeal (not end-to-end encrypted, and the app says so). The staff panel is `/channels/@admin`
  (`/api/v1/moderation/*`); the member's side is `/api/v1/nexa/*`.
- Staff ranks (`api/staff.go`, web `lib/staffRank.ts`, Android `data/StaffRanks.kt`) are public user
  flags, each holding everything below it. Mod (512): the panel's people, cases and log, mutes and bans
  of at most a week (`modMaxDuration`; never permanent, never shortening a longer one), and lifting,
  closing and reopening only their own cases. Admin (256): the rest of the panel (other actions,
  permanent bans, servers, expressions, apps, bugs, alts, announcements), but not Disable Sign-In,
  deleting accounts or rights in other people's servers. Developer (1, what admins always were: the
  first account, or `ADMIN_USERNAME` while nobody has been admin): every right in every server they are
  in, Disable Sign-In, deleting accounts, and the instance's settings, stats and crash reports. Owner,
  the oldest Developer (`InstanceOwnerID`): the legal report, and the only one who gives and takes ranks
  (`PATCH /admin/users/{id}` with `rank`; the old `admin` bool means Developer) or disables staff's
  sign-in. Anyone holding a rank cannot be moderated. Routes state their least rank with
  `s.staff(rank, h)`, as does `emitStaff` (bug events go to admins and up); changes log `rank_change`
  with `from` and `to`. Badges (the owner's crown, a developer's CodeXml, an admin's hammer, a mod's
  ShieldCheck, all in the accent): web `StaffMark`, `StaffBadge`, `RankMark` in
  `components/common/OwnerBadge.tsx`, `RankPill`, the Set Rank dialog `moderation/RankModal.tsx`;
  Android `OwnerMarks` in `ui/shell/DmList.kt`, `RankBadge`, `openRankSheet` in
  `ui/moderation/Ranks.kt`.
- Staff act on accounts (mutes, bans, purges, removing pictures, resetting fields), servers (Discovery
  holds included), sticker packs, emoji and uploads (`moderation_actions.go` and the other
  `moderation_*` files), logged in `mod_log`; an action on an account comes with a case (`mod_cases`)
  whose thread the member sees. The people grid leaves bots out; the Bots tab
  (`GET /api/v1/moderation/apps`, `moderation_apps.go`, web `components/moderation/BotsTab.tsx`, Android
  `ui/moderation/Bots.kt`) lists every app with its owner, reach and whether it reads every message.
  Bulk moderation (web `UserGrid.tsx` with `ModerateModal`'s `bulk`, Android `Users.kt` with
  `openModerate(bulk = ...)`) sends the same per-account action for each account in turn, so each gets
  its own case.
- Alt accounts: `user_ips` keeps every address an account signed in or was active from
  (`Store.RecordUserIP`, from session creation and `auth.Check` seeing a new address or touching the
  session, which also keeps `sessions.ip` current; deleted 180 days after last use by the maintenance
  loop, and with the account). Staff see "Alt Accounts: N" (`alt_count`, `Store.AltCounts`) and
  `GET /moderation/users/{id}/alts` lists them with how many addresses they share, never the addresses
  (web `moderation/AltAccounts.tsx`, Android `ui/moderation/Alts.kt`); only the legal report prints
  addresses. The Privacy Policy's section 3e describes it.
- Bug reports (`/api/v1/bugs`), client crash reports (`/api/v1/client-errors`), and the instance
  settings (registration mode and key, which override `chatd.env` until cleared).
- Case threads and bug reports are conversations in which either side sends text, pictures and
  recordings (private blobs of kind `case` and `bug`, `/media/cases/{id}`, `/media/bugs/{id}`). A closed
  case, or a bug report set to resolved, won't fix or closed, takes nothing from anyone, staff included,
  until staff reopen it. `mod_reads` (`moderation_reads.go`) keeps what each member of staff has read:
  the panel's badges count threads waiting on staff with something new to them, `mod_unread` syncs their
  devices, and `POST /moderation/read` is Mark as Read.
- AutoMod: the server applies a server's rules to what it can read (names, profiles, thread and post
  titles); members' apps apply them to encrypted messages and report what they held
  (`POST /servers/{id}/automod/report`).

### Safety reports

- Routes (`api/reports.go`): `/api/v1/reports` (submit, own receipts), `GET /reports/target` (a
  permission-checked preview with the allowed reasons and `already_reported`, the caller's own open report
  about the item), `POST /reports/attachments`, `GET /media/reports/{id}`, and the staff's
  `/api/v1/moderation/reports` list (`status` active, closed or one status; `kind`, `reason`,
  `assigned` me or none, the old `mine=1` too; `sort=oldest` with `after`, else `before`; `q` matches
  words, report, item and account ids and usernames; `people` and each report's `duplicates`), `/{id}`
  detail (plus `related` reports about the same item or account, `reporter_stats` and `subject`, the
  account's standing) and version-checked review (`resolve_related` closes the other active reports
  about the same item the same way, `reporter_message` is what the reporter is told). `POST
  /{id}/remove-message` (Admin and up) removes exactly the reported message without joining its
  conversation or granting keys, opening a case and logging in the same transaction, and closes it and
  every other active report about the message as Action Taken. Every staff rank can review reports;
  rank limits still govern other actions, and `staff` keeps the required rank immutable across requests,
  rejected ones included. `/moderation/summary` carries `reports`, the counts by status.
- Migration 0041 adds `safety_reports`, `safety_report_events`, `safety_report_files` and
  `safety_report_snapshot_blobs`; 0046 adds `outcome` and `outcome_at` (both databases; queries in
  `store/reports.go`). A per-account request reference makes retries idempotent. `limReport` (ten an
  hour, captcha action `safety_report`) and the upload budgets limit abuse; muted members can report.
  Receipts (`reportReceipt`) show only the reporter's own submissions, statuses, the item's name as they
  saw it (`target_label`) and what staff told them, never notes, assignments or who reviewed. Evidence
  files are private blobs of kind `report`, seen only by their uploader until submitted, then by staff
  too; snapshot pictures take blob references so later edits cannot garbage-collect them.
- Reasons (`reportReasons`, the order the forms list them) are harassment, hate, violence, self_harm,
  sexual, child_safety, underage, spam, scam, privacy, ban_evasion, illegal and other; underage and
  ban_evasion are offered only for profiles, messages and something else (`reasonKinds`). Each has an
  icon and a tone (urgent, serious, routine) in web `lib/reportReasons.ts` and Android `Reports.icon`,
  `Reports.tone`; urgent reasons show an emergency-services note in the form (self-harm its own words).
  Sexual content is not offered, and the API refuses it against the target's current settings, for DMs
  and group chats, NSFW channels (inherited designations included) and content tied to NSFW communities.
- Feedback: closing a report (or changing a closed one's outcome) sets `outcome_at`, which puts a
  `report_outcome` item in the reporter's Nexa conversation (`handleNexaFeed`, counted unread by
  `NexaUnread` and in `NexaLatest`; a sent report is a `report` item, not unread); reopening clears it.
  `report_update` carries the receipt to the reporter when the status moves (the apps update Your Reports
  and the conversation, and notify on a new outcome), `mod_report` carries changed reports, people and
  counts to every member of staff (the queue moves live; the Reports tab's badge is open plus in review).
  Staff leave the reporter message empty to send the standard words (`reports.outcome.actioned`,
  `.dismissed`).
- The form (web `ReportModal.tsx`, Android `openReport` in `ui/reports/ReportForm.kt`) is steps: from an
  item's menu it opens on the reasons (one press on a reason goes on), then the details (the chosen
  reason with Change, what staff will see, What Happened?, the sharing notice), then a receipt that
  offers Block for a profile or message and links Your Reports. From Settings it first asks what to
  report: a pasted message, channel, profile or pack link of this instance, Something Else, or by ID.
  Only Something Else takes screenshots (up to five, 100 MB); every other report shares the captured
  content instead (the API still takes legacy part metadata for non-message reports, and message
  reports only `whole`). Your Reports is a Settings page (web `settings/YourReportsPage.tsx` with
  `stores/myReports.ts`, Android `ui/settings/YourReportsPage.kt` with `Reports.mine`), and the
  conversation's items are web `reports/ReportFeed.tsx`, Android `ReportFeedEntry` in `NexaPages.kt`,
  both drawing the shared receipt (`ReceiptView.tsx`, `ReceiptCard`).
- Message evidence: the backend resolves the responsible account and keeps only explicit readable
  fields. A message report must carry the complete current message and every attached file (stale edit
  versions, missing content, omitted attachments and invalid file keys are refused), sharing only each
  reported file's own key, never conversation roots or note keys. Private descriptors live under
  `_message_files` and are stripped from all list and detail JSON; staff downloads decrypt chunked
  AES-GCM with range support (`media/reported.go`), and reported files keep their blob references after
  the source is deleted. Evidence is not end-to-end encrypted and is labelled reporter-supplied, not
  authenticated proof. Reports give staff no conversation keys and no unreported content.
- Staff (web `ReportsTab.tsx`, Android `ui/moderation/Reports.kt`): the queue has view chips with
  counts (Needs Review, Open, In Review, Resolved, All), search, and filters folded behind a toggle;
  rows show the reason's glyph, who or what, the words, status, how many open reports share the item and
  the assignee. A report shows, in one page, what the reporter said, the reported content (the chat row
  or a target card, then the captured snapshot), the responsible account's standing, the reporter's
  record, links to the account, server and pack pages, the decision (Remove Message for admins on
  messages; Open, In Review, Action Taken, Dismissed; the reporter message; closing the duplicates; the
  internal note, required to close), the other reports and the history. Claiming puts an open report in
  review and unclaiming opens it again. On a wide web panel the account, reporter and links sit in a
  side column; below 1080 px of panel everything is one column in that order, as on Android. Web queue
  filters live in the URL and Android keeps its queue state. Account, server and pack pages opened from
  a report keep a return link and Back returns there (Android `Nav.returnTo` drops the inspected pages
  from Back history). Reporting adds no staff privileges.

### NSFW communities

- `servers.nsfw` (migration 0040, both databases) marks a whole community in Server Settings, Overview;
  channel `nsfw` flags stay independent, and a channel's settings recommend marking the server only if
  most of its content is NSFW. The Terms permit NSFW communities, require the classification for
  majority-NSFW servers, and allow a few NSFW channels without it when the public-facing details hold no
  NSFW content.
- Discovery excludes both kinds by default, category counts and popular tags included. `nsfw=1` opts in
  after an 18+ confirmation (a compact warning-icon switch beside the filters, with no separate NSFW
  filter button or popular-tag shortcut); `nsfw_only=1` narrows to servers marked NSFW or holding any
  NSFW channel, hidden or archived ones included. The reserved `nsfw` tag is derived from that
  classification, uses no custom-tag slot and cannot be given to a safe server; `nsfw` or `#nsfw` search
  words (any case) and `tag=nsfw` apply the filter, and searching never bypasses the opt-in. Cards
  distinguish `nsfw` (the whole community, badged) from `nsfw_content` (either kind).
- Only whole-community NSFW servers gate invite previews and invite or Discovery joins with `nsfw=1`: an
  unconfirmed preview returns only its code, `nsfw: true` and `age_gate: true`, link unfurls and page
  metadata reveal nothing, and the public bot-protocol invite lookup needs the same confirmation. The
  clients keep each confirmation in memory for the current account. Public media URLs stay public.
- Whole-community NSFW servers cannot create or wear guild tags (turning the toggle on strips the tag
  from wearers); a few NSFW channels change neither tags nor public details.
- The staff Discovery tab (`/channels/@admin/discovery`, Admin and up) uses
  `/moderation/servers?discovery=all|listed|held`, which searches and paginates listings and holds
  before returning them, with matching totals, and reuses the audited hide/restore actions and owner
  cases. Both clients use the same catalogue strings and Terms version.

### Profile badges (`store/badges.go`, `api/badge_handlers.go`)

- Badge ids: `staff` (the rank, never hideable), `plan`, `og` (account made before `OGCutoff`, the start
  of 2026-11-02 in Alaska), `years` (whole years on Nexa as `level`), `bug_hunter` (a bug report staff
  resolved), `app_developer` (an owned app's bot in `AppDeveloperServers`, 5, servers the owner does not
  own), `gift_giver` (paid plan gifts to others, `level` 1 to 3 at `GiftTiers` 1, 5, 20), and the two
  staff give by hand in `user_badges`: `contributor` and `event:<badge_defs id>` (a name, a Lucide icon
  name and a colour). Earned badges are worked out when asked for, never stored; bots, Nexa's accounts
  and deleted accounts have none (deleting an account drops its rows).
- `GET /users/{id}/profile` and `/profiles/{username}` carry `badges`, visible ones in the holder's
  order; `GET`/`PUT /users/@me/badges` (`order`, `hidden`; open to muted and banned accounts) is the
  account's own list with `hidden` marked. Admins and up: `/moderation/badges` (event badges, CRUD) and
  `PUT /moderation/users/{id}/badges` (the whole hand-given set), logged as `badge_change` (names added
  and removed), `badge_create`, `badge_edit`, `badge_delete`.
- Web `lib/badges.ts` (looks, `EVENT_ICONS`, `EVENT_COLOURS`), `components/badges/Badges.tsx`
  (`BadgePill`, `BadgeRow`; staff rank and plan stay tags on the username line beside the pronouns,
  every other badge is an icon tile on its own line under it, in its tone from `--badge-*` in
  `tokens.css`, named only in the tooltip or, with `named`, in lists; the editor groups them the same way), `stores/myBadges.ts` (the
  designer and its preview), `settings/BadgeEditor.tsx` (Settings, Profiles, Badges),
  `moderation/BadgesTab.tsx` (`/channels/@admin/badges`) and `GrantBadgesModal.tsx` (a person's page);
  Android `data/Badges`, `ui/profile/Badges.kt`, `BadgeEditor` in `ui/settings/DesignerForm.kt`,
  `ui/moderation/BadgeTab.kt` (also `openGiveBadgesSheet`), and `badges` in `Route.Admin.TABS`. The
  event icon list must name icons both lucide-react and Android's `lucide.json` have.

### Profile widgets (`api/widgets.go`, web `lib/widgets.ts`, Android `ui/profile/Widgets.kt`)

- A JSON list on `users.widgets` and `server_members.widgets` (no migration for new kinds; apps skip
  kinds they do not know), at most 12 (`maxWidgets`, one of each game and music kind) and 4 custom
  cards, 64 KiB. A card has a title, a picture, markdown text (1000 characters, the chat renderer),
  up to 6 stats (a stat with `bar` 0 to 100 is a progress bar whose `value` is the percentage), up to
  3 `links` (the first repeated as `link` for older apps) and `color`/`color2` (0xRRGGBB tint and
  gradient). Card links confirm the full address before opening.
- Countdown (`countdown`: a title, an emoji, and `date` YYYY-MM-DD counted in each viewer's own
  calendar, or `at` in Unix ms) shows how long is left, Today! on the day, and hides itself from others
  the day after (the owner still sees it in the designer). Local Time (`localtime`: an IANA `zone`
  the server checks with its embedded tzdata) shows that zone's time and its gap from the viewer's.
  Both count against the server clock; web `lib/timeWidgets.ts`, Android `data/TimeWidgets.kt`. The
  Android editor uses the system date and time pickers, as the web uses the browser's.
- Sticker Showcase (`stickers`: `packs`, one or two ids) shows the person's own public sticker or
  emoji packs as the cards pack links make in chat (web `PackLinkCard` with `quiet`, Android
  `PackLinkCard(quiet = true)` in `ui/chat/LinkCards.kt`), with Install Pack. The server checks owner
  and public on save (`cleanWidgets` takes the profile's owner); a pack later deleted or made private
  drops out for viewers.
- Books: Reading Now (`reading`, up to 3, each with optional `progress` {kind page or percent,
  value}) and Favourite Books (`books`, up to 12), items `books` [{id (Open Library work key), title,
  author, cover, image}]. `GET /api/v1/books/search` (`limSearch`; Open Library can take over ten
  seconds, so it waits 20) and `/media/book/{cover}?size=s|l` (kept under `DATA_DIR/books`), both
  through the egress proxy with a `Nexa` User-Agent. `store.WidgetImages` counts `books` and `screen`
  items' own pictures.
- Shows and films: Watching Now (`watching`, up to 3, a show with optional `season`, `episode`) and
  Favourite Shows & Films (`screen`, up to 12), items `screen` [{id, kind tv or movie, title, year,
  poster (TMDB path without its slash), image}]. `GET /api/v1/screen/search` (off without
  `TMDB_API_KEY`, a v4 Read Access Token) and `/media/screen/{poster}?size=s|l` (kept under
  `DATA_DIR/screen`), through the egress proxy. TMDB's terms require its logo
  (`web/public/brand/tmdb.svg`, Android loads it from the server) and the English notice
  `widgets.screen.tmdbNotice` under the search and in Settings, About ("Where Profile Info Comes From").
- Music widgets can be filled from a Spotify, Apple Music, Deezer or SoundCloud link: `POST
  /api/v1/music/from-link` (`kind`, `url`; `limMusic`) reads it with `unfurl.Fetcher.MusicList` (the
  card's reader, keeping up to ten tracks through the `tracksWanted` context value), searches Deezer
  for each piece and keeps those whose name and lead artist match (`sameMusic`), naming the rest in
  `missed`. A playlist or album fills Songs, an album or song link fills Albums, an artist (or any
  link's artist) fills Artists.
- Current apps always send `links` on a card, even empty. A card saved without it comes from an app
  before cards had several links, colours and bars, so `carryCardExtras` keeps the saved card's
  extra links, colours and bars (`TestOldAppsKeepWhatCardsGained`).

### Game pages (`internal/games/details.go`, `api/widgets.go`)

- `GET /api/v1/games/{id}` is a game's page from IGDB (summary, storyline, release, genres, themes,
  modes, platforms, developers, publishers, rating from 3 or more votes, age ratings, screenshots,
  artwork, YouTube trailers, similar games, store and official links by IGDB website type), kept a
  day in memory; trailers carry `thumb` (16:9) and `poster` (4:3 with the frame across its middle)
  signed through the media proxy. `GET /api/v1/games/match?name=` is the IGDB id of the game whose
  name matches exactly (case, trademark signs and punctuation aside), for activities, which carry no
  id; misses are kept too. Both under `limSearch`. `/media/game/{id}` serves covers at `s`/`l` and
  pictures at `t` (strip) and `b` (stage, viewer), the latter only for image ids a fetched page named.
- Web `lib/games.ts` and `components/games/GamePage.tsx` (modal kind `game`, one at a time); Android
  `data/Games.kt` and `ui/profile/GamePage.kt` (`openGamePage`). A game with an IGDB id opens it from
  any game widget (`GameOpen`, Android `opensGame`), and a Playing or Competing activity whose name
  matches gets View Game. Similar games open in place with Back. Trailers play through `/yt/` on the
  web and open the YouTube app or browser on Android, as chat links do; store links confirm first.

### Plans and payments (`api/billing.go`, `internal/stripe`)

- Supporter ($5 a month, $48 a year) and Pro ($10, $96) raise only what costs money (`planLimitsFor`):
  the largest file (1 GB free, 2 GB, 4 GB; `MAX_UPLOAD_BYTES` sizes only files no plan covers, from
  webhooks, apps and notes), uploads per rolling 30 days (5 GB, 100 GB, 500 GB, from attachment blobs
  and declared unfinished uploads, `Store.UploadedSince`; `upload_month_full`), the Quality screen share
  and the top share bitrate (6, 12, 20 Mbit/s), the paid name effects (Holo, Neon, Flame: refused
  without a plan, `plan_required`, and sent as the first colour solid, `store.styleForPlan`) and Pro's
  two server boosts (every member of a boosted server may send 2 GB files, `fileMaxFor`). Chatting,
  calls and everything else stay free. `users.plan` is public, as a badge.
- Stripe Managed Payments is the merchant of record. Prices are found by lookup key
  (`nexa_supporter_monthly`, `_yearly`, `nexa_pro_monthly`, `_yearly`, and one-time
  `nexa_supporter_gift_month`, `_year`, `nexa_pro_gift_month`, `_year`) and cached.
  `POST /billing/checkout` and `/billing/gift` answer a Checkout address, `/billing/portal` the
  portal's, `GET /billing` the account's side (limits, use and when room comes back, boosts, gifts,
  prices). `POST /billing/webhook` checks Stripe's signature and always rereads the subscription from
  Stripe, so repeated or out-of-order events end the same way; an older subscription ending never
  overrides a newer one.
- An account wears the better of its subscription (`billing`) and a running gift (`plan_gifts`, 30 or
  365 days, paid once, queued after a running gift of the same plan, never between people either of whom
  blocked the other). Gift links (`POST /billing/gift` with `link: true`; `gift_links`, made by the
  webhook once paid) are claimed by whoever opens `/gift/<code>` first (`GET /gifts/{code}`,
  `POST /gifts/{code}/claim`; web `pages/GiftPage.tsx`, Android `openGiftLink` in
  `ui/support/Support.kt`). `recomputePlanTx` writes `users.plan` and drops the boosts of an account no
  longer Pro; `StartBilling` ends gifts as they run out; changes emit `user_update`, `server_update` for
  servers that lost a boost, and `billing_update` to the account. Deleting an account cancels its
  subscription (`endSubscription`) and deletes its plan records.
- Pay what you want (`POST /billing/pay-what-you-want` with `amount` in US cents, `pwywMin` 100 to
  `pwywMax` 99,999,999): one Checkout payment, never renewed, its product made inline (`price_data`
  with the plans' tax code, `stripe.CreateAmountCheckout`, accepted by Managed Payments as checked on
  2026-11-02). `pwywSplit` gives a month (30 days) of Pro per Pro month price, then a month of
  Supporter per Supporter month price in what is left (the gift month prices; under one Supporter month
  is a thank-you with no time), written on the session's metadata (`pwyw`, `pro_days`,
  `supporter_days`) and applied once by the webhook through `Store.AddPaidTime`: `plan_gifts` rows from
  the account to itself (sessions `<id>` and `<id>:supporter`), Pro after any Pro already given and
  Supporter after all of it, alongside a subscription rather than after it. The apps show the same
  split before Stripe's page opens (web `payWhatYouWant` in `lib/plans.ts`, Android
  `Plans.payWhatYouWant`), list self-paid rows as "{plan} you paid for", and both offers (`pwywLine`)
  mention it. The Support page always shows a Refunds section (14 days, in full, the contact line) and
  every pay button carries `support.refunds.line`; keep them in step with the Terms' section 8.
- Without `STRIPE_SECRET_KEY` plans cannot be bought (`billing_off`), but the limits hold. Web
  `lib/plans.ts`, `stores/billing.ts`, `stores/upsell.ts` (`offerPlan`, shown when a limit stops
  something), `components/support/` (`/channels/@me/support`, Settings, Subscription, `Upsell.tsx` with
  the once-per-release prompt kept as `supportPromptSeen`, `MonthMeter.tsx`); Android `data/Plans`,
  `ui/support/Support.kt`.

### Mentions

- The dock's @ button (web `shell/UserPanel.tsx`, Android `ui/shell/Dock.kt`; it took the place of the
  activity button, and Share my activity now lives only in Settings, Activity and its keybind) shows
  every unread mention summed from the read states and opens Mentions: web
  `components/mentions/MentionsModal.tsx` (modal kind `mentions`), Android `openMentions` in
  `ui/chat/Mentions.kt`. It filters by kind (All, You, Your Roles, @everyone), place (everywhere,
  direct messages, one server) and sort (newest or oldest first), kept per device (web localStorage
  `nexa.mentionsView`, Android preferences `mentions`), groups under day lines, tags why each one is
  yours (`mentionOf` in web `lib/mentions.ts`, `Mentions.why`), marks those past the read marker New and
  jumps through the route's `jumpTo`.
- `GET /api/v1/users/@me/mentions` (`api/mention_handlers.go`, `store/mentions.go`) takes `kinds`
  (me, roles, everyone), `server_id` (an id or `@me`), `channel_id`, `sort=oldest` with `after`, else
  `before`, and `limit` (at most 50). It reads the caller's open conversations (locked subchats only once
  unlocked in that session) and every server channel and thread, archived ones included, where they
  have View Channel and Read Message History, and leaves out their own messages, blocked authors,
  system messages and @everyone from before they joined. The partial index `messages_mentioning`
  (migration 0043) holds only messages that mention anyone; the query repeats its condition so both
  databases use it. Messages come back sealed, and the apps decrypt them.
- Android's jump into a channel not yet loaded keeps `ChatUi.jump` set until the list has handled it
  (`MessageList.kt`), and `Messages.loadAround` rethrows cancellation: a cold open recomposes the list
  mid-load, which once swallowed the cancellation and toasted "That message could not be found".

### Link and media channels (modelled on Fluxer's)

- A link channel (`link`) is a channel list entry that opens `channels.url` (migration 0044, both
  databases): a whole http or https address with a host and no userinfo, at most 1024 bytes
  (`cleanLinkURL` in `server_handlers.go`; web `lib/linkChannel.ts` `linkDestination`, Android
  `ui/shell/LinkChannels.kt`). Set on create (`url`, required) and changed by PATCH; every channel name keeps spaces and
  capitals (`cleanChannelName` only collapses whitespace); no Private toggle at creation, no invites, no page (a path to one
  goes to the server). Pressing it opens a Nexa address in the app (`followAppLink` in
  `shell/AppLinks.tsx`, Android `AppLinks` and `Nav.openPath`), anything else after a confirm showing
  the full address. Overwrites offer only View Channel, Manage Channels and Manage Roles. Bots never
  see link channels (`channelType` returns false).
- A media channel (`media`, protocol type 16, Discord templates' 16) is a forum whose layout is always
  `gallery` (forced in `parseForum` and the PATCH), drawn as tall tiles (web `data-media` in
  `Forum.module.css`, Android `MediaRow`, two to a row on phones). A post needs a picture or video and
  posting needs Attach Files; the server cannot see attachment types, so the apps hold that.
  `forum.hide_downloads` (Hide download buttons; protocol flag 1 << 15) makes the apps leave the
  download, save, copy and open buttons off its posts' pictures and videos (web `lib/mediaDownloads.ts`
  with the `NoDownloads` context in `Attachments`, `Lightbox`, `VideoPlayer`, and `messageMenu`;
  Android `data/MediaDownloads`); a forum never keeps it.

### Messages: forwarding and voice messages

- A bare link to a channel or message on this instance (`/channels/<server or @me>/<channel>[/<message>]`,
  no query or fragment) to a channel this account can see is drawn as a mention-style pill, as Fluxer
  does: the channel's glyph and name (a DM's person, a group's name), then for a message a caret and a
  speech bubble (web `jumpTarget` in `lib/appLinks.ts` and `JumpLink` in `markdown/render.tsx`; Android
  `AppLinks.jumpTarget` and `jumpLink` in `ui/markdown/Render.kt`). A channel not in the stores (an
  archived thread) is asked of `GET /channels/{id}` once (web `linkedChannels` in `render.tsx`,
  Android `ui/markdown/LinkedChannels`, cleared on session reset); until it answers, or if it refuses,
  the link stays a plain link. It opens as any app link does. No
  preview is fetched for these links, and previews older apps sent for them are not drawn.

- Forwarding goes to one conversation or channel at a time (`body.forward` on send; web
  `lib/forward.ts`, `modals/ForwardModal.tsx`; Android `data/Forward`, `ui/chat/ForwardSheet.kt`). The
  server checks the source as a reader (same channel, a person's default or reply message, Read Message
  History, unlocked), lets the new message carry the source's files without owning them (no upload, no
  allowance spent), clears mentions, sets flag 1 << 14 and has its own limit (`limForward`). `fwd.at` is
  the original's send time; the optional `fwd.comment` sits in the same encrypted payload under the
  4000-character limit and counts in search, copying, notifications and AutoMod. Android draws the
  forwarded bar after layout without intrinsic measurement, which BoxWithConstraints (video players,
  post previews) cannot answer and which used to crash.
- A voice message is an encrypted audio attachment whose meta has `voice: true`, `duration` and `wave`
  (base64, one loudness byte per bar, 48 bars; at most 96 bytes, only on `audio/` files, drawn as 32
  bars by `SHOWN_BARS`); the server knows nothing of it. The mic replaces Send in an empty box for
  anyone with Attach Files (desktop web's Show Send Message Button keeps a disabled Send beside it; the
  text menu's `useTextMenu` must keep that switch object with its latest state and callback, not wrap it
  in a function). Recordings last at most ten minutes (under half a second is dropped) and are 48 kHz
  mono at 128 kbit/s. Web: MediaRecorder from the chosen microphone, AAC in MP4 where possible, else
  Opus in WebM or Ogg, through `voice/recordingInput.ts` (the call's neural filter, high-pass, peak
  compression, headroom, capture AGC off, no boost); `lib/voiceMessage.ts`,
  `composer/VoiceRecordBar.tsx`, `chat/VoiceMessage.tsx`, `Staged.voice` in `lib/attachments.ts`.
  Android: AudioRecord with VOICE_RECOGNITION, optional echo cancellation, no AGC, its own RNNoise state
  and peak control (`data/RecordingDsp`, `cpp/denoise_jni.cc`, never shared with a call), AAC through
  MediaCodec and MediaMuxer; `data/VoiceMessages`, `ui/chat/VoiceRecordBar.kt`,
  `ui/media/VoiceMessage.kt`, `Staged.voice` in `data/Send.kt`.
- Transcribe runs Whisper Tiny multilingual on the device; audio and transcripts never leave it, and a
  transcript lives only in the open message's memory. Web and desktop: `lib/transcribe.ts` and a
  disposable `transcribe.worker.ts` (Transformers.js 4.3.1, quantized ONNX, a bundled single-threaded
  WASM runtime, no cross-origin isolation) with a first-window language-token pass against the library's
  English default. The model is committed in `web/public/models/whisper-tiny/` (no Git LFS;
  `manifest.json` pins upstream URLs, sizes and SHA-256, `LICENSE` keeps Whisper's MIT notice), served
  at `/models/whisper-tiny/`; the worker sets `localModelPath` to `/models/` with remote models off, so
  a missing file fails instead of reaching Hugging Face, and missing `/models/` files are 404s, never
  the SPA page. Trusted static JavaScript and module responses carry the app's CSP, since a worker
  enforces its own script's policy and the default deny policy blocks the model download. Android:
  `data/Transcripts`, MediaCodec decoding and whisper.cpp 1.9.5 (`cpp/whisper_jni.cc`, source pinned by
  SHA-256), with `ggml-tiny.bin` (77.7 MB) downloaded from the Nexa server on first use into
  noBackupFilesDir and checked by size and SHA-256 before an atomic rename. Both show download progress
  only after Transcribe is chosen (no idle hint), then working, failure and no-speech states, can
  cancel, and release inference resources.

### Optional host maintenance

`MAINTENANCE_FILE` is empty by default; only nexa.fish points it at the host-written
`/var/lib/nexa-maintenance/schedule.json` (`reboot_at`, Unix milliseconds). `api/maintenance.go` serves
public `GET /api/v1/maintenance` (server time, a 150-minute notice window, no-store); a missing or
invalid file means no notice, and the app never runs reboot commands. The optional units
`deploy/nexa-maintenance-*` with `deploy/maintenance-schedule.py` reboot the host 48 hours after boot (a
manual reboot starts a new interval), replacing any daily reboot timer;
`deploy/safe-maintenance-reboot.sh` waits up to an hour for package updates, then, if still busy, skips
and schedules the next attempt 48 hours later (`deploy/README.md` installs and disables them;
`tools/test_maintenance.py` checks the arithmetic and dry-run guards). Web `shell/MaintenanceBanner.tsx`
and Android `ui/shell/MaintenanceBanner.kt` poll every 30 seconds in the signed-in shell and show a
non-dismissible countdown from 2 hours 30 minutes before, expecting 3-4 minutes of downtime, kept
through connection loss, showing restart pending at zero until a refreshed schedule replaces or clears
it, and timed on local elapsed clocks so a wrong device clock does not matter. The homepage demo skips
it.

### Configuration

Every setting is documented in `chatd.env.example` and loaded by `internal/config`. Groups: addresses
(`LISTEN`, `PUBLIC_URL`, `ALT_URLS`, `MOVED_FROM`), storage (`DATA_DIR`, `DATABASE_URL`, `REDIS_URL`,
`WEB_DIST`, `DESKTOP_DIR`, `ANDROID_DIR`, `NODE_ID`), limits (`MAX_UPLOAD_BYTES`, `MAX_AVATAR_BYTES`,
`MAX_EMOJI_BYTES`, `MAX_STICKER_BYTES`, `SESSION_TTL_DAYS`), sign-up (`REGISTRATION` open/invite/closed,
`REGISTRATION_KEY`, `ADMIN_USERNAME`, `TURNSTILE_SITE_KEY`, `TURNSTILE_SECRET_KEY`, and the
`HCAPTCHA_SITE_KEY`, `HCAPTCHA_SECRET_KEY` fallback), calls
(`REALTIME_APP_ID`, `REALTIME_APP_SECRET`, `REALTIME_MONTHLY_GB`, `REALTIME_ACCOUNT_ID`,
`REALTIME_ANALYTICS_TOKEN`, `REALTIME_TURN_DAILY_GB`, `GUEST_LINKS_P2P`, `STUN_URLS`, `TURN_URLS`,
`TURN_USERNAME`, `TURN_CREDENTIAL`, `TURN_KEY_ID`, `TURN_API_TOKEN`), outbound (`EGRESS_PROXY`,
`WARP_PROXY_PORT`), integrations (`KLIPY_API_KEY`, `IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET`, `TMDB_API_KEY`,
`TELEGRAM_BOT_TOKEN`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `DISCORD_BOT_TOKEN`,
`DISCORD_ACTIVITY_GUILD_ID`, `DISCORD_REDIRECT_URL` left empty, `DISCORD_API_URL` for the fake),
payments (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_API_URL` for a fake), logging
(`LOG_LEVEL`, `LOG_FILE`), optional host maintenance (`MAINTENANCE_FILE`). A new setting goes in
`config.go`, `chatd.env.example` and this list.

### Go tests

- `internal/api`: `newTestServer(t)` (`testutil_test.go`) is a real `httptest` server with the gateway
  wired in, on a fresh database (SQLite, or a Postgres schema with `CHAT_TEST_POSTGRES`). Top-level tests that
  call it run in parallel; a test that changes a package-level setting (a stand-in's address, a grace
  period) calls `serial(t)` first, which waits for the others and runs it alone.
  `register(t, ts, username)` (`auth_handlers_test.go`) returns a token and id;
  `do(t, ts, method, path, token, body)` makes a JSON request; `errCode` reads an error;
  `connectGWReady` / `connectGW` open a gateway socket and `gw.next(event, timeout)` waits for an event.
  Apps (`bot_test.go`): `makeApp` returns a bot token, `botDo` requests as the bot, `addBot` adds it to
  a server as the consent page does. TURN (`turn_test.go`): `newFakeTURN(t, srv)` stands in for
  Cloudflare's TURN key API and records mints and revocations, `iceUsername` reads the ICE credential
  name, `idleTURN` ages a credential's grace, `putInCall` puts someone in a call without a gateway. The
  Realtime SFU stand-in is `newRealtimeStandIn` (`sfu_handlers_test.go`).
- `internal/store`: `newStore(t)`, `mkUser(t, s, name)`.
- Tests against live services run only with their variables set (`UNFURL_LIVE`, `REALTIME_LIVE`,
  `TELEGRAM_LIVE_TOKEN`, `DISCORD_BOT_TOKEN`); the Discord tests otherwise use `fakediscord`.

## End-to-end encryption

The client does all of it (`web/src/crypto`; Android's port in `fish.nexa.crypto` is checked against
`web-crypto-vectors.json`). The server stores ciphertext plus what it needs to deliver: author, channel,
mentions, reactions, sizes.

- Accounts: Argon2id stretches the password on the device, and HKDF splits it into an auth key (the only
  thing sent, hashed again on the server) and an encryption key wrapping the account's X25519 identity.
  Clients check the server's KDF parameters (`lib/serverKdf.ts`) so it cannot weaken them. A recovery
  key shown once at registration can restore the identity.
- Conversations have a root key per epoch, sealed to each member's identity (`key_grants`); channel keys
  derive from it, so one grant covers a whole server. `keys_needed` asks an online holder to wrap every
  epoch a member lacks, so newcomers read history (a waiting device asks again with
  `POST /keys/request`), and `key_rotation_needed` asks one for a new epoch when membership shrinks.
  Invite links can carry the root key in the fragment (`#k=<epoch>.<key>`), which never reaches the
  server.
- Sends and edits use the channel's current conversation and epoch: clients fetch a missing grant rather
  than use an old root, the API rejects stale epochs, and `InsertEncryptedMessage` and
  `UpdateEncryptedMessage` check again inside the writing transaction under the epoch lock. An edit
  after a channel turns private uses its private key. `POST /keys/request` with `bad_epoch` and
  `bad_wrapped_key` discards exactly the caller's unusable wrapper (never another recipient's row or an
  already replaced one); both clients send it when a wrapper fails to open.
- Messages are AES-256-GCM envelopes binding channel and author. The clients hold `MESSAGE_LIMITS`
  (`crypto/message.ts`, Android `MessageCrypto.Limits`): 4000 characters as typed (a mention or custom
  emoji counts as two, `typedLength`), 10 files with 255-character names, 5 link previews, 30-character
  sticker names, and the poll limits. Neither sends a payload past them, and one arriving past them is
  shown as breaking the limits (`brokenLimit`), not drawn.
- Files are chunked (`crypto/fileChunks.ts`: "ECF1", a base IV, length-prefixed chunks), each with its
  own key inside the message (pictures add a small copy); downloads and video decrypt as they stream
  (`download-sw.ts`). Readers check the plaintext size the message authenticates as well as each chunk's
  tag, so dropped whole chunks fail even without a Content-Length. Blob ids in decrypted metadata must
  be 1 to 20 ASCII digits (Android checks again at its media-cache boundary).
- Call frames are sealed again with a key derived from the conversation's (`voice/frameCrypto.ts`;
  Android natively in `cpp/frame` with RustCrypto AES-GCM from `android/frame-crypto`, built by
  cargo-ndk from CMake, same wire format), because the SFU ends transport encryption; guest links carry
  the call key in the fragment. Clients keep full epoch numbers and try at most eight matching keys when
  legacy low-byte frame indexes collide. `node tools/check_frame_crypto.mjs` (with the host driver
  `tools/frame_crypto_check.cc`) builds in a throwaway directory and checks native and WebCrypto
  compatibility for audio, VP8 and H.264, tampering and epoch collisions; Rust tests include NIST
  vectors.
- Notes have their own key sealed to each member, and a share link carries a second key in its fragment.
  Announcement follows are sealed to the follow's key. The settings blob, sound packs and the on-device
  search index are sealed too.
- Keys on the device live in IndexedDB (`crypto/keystore.ts`), cleared on sign-out; with a passcode lock
  neither the identity nor any root is kept in the clear, and plain data exports omit `appLock` (its
  salt, KDF parameters and verifier would allow offline guessing). Identity keys are pinned on first
  sight (`lib/keyPins.ts`); safety numbers (`crypto/fingerprint.ts`) let two people compare them.
- Apps: a message meant for a bot (a DM, a mention, a reply, a message command's target) carries
  `bot_copies`, sealed by the sender's app to the bot's X25519 key (web `lib/botCopies.ts`, Android
  `data/BotCopies`); the server keeps bots' private keys sealed with the server secret and opens the
  copies for them, files included (`bot_crypto.go`). An app with the Message Content intent gets the
  server's keys like a member and the server decrypts for it; the start of every channel it can see
  names it (web `chat/apps/readingApps.ts`, Android `ui/apps/ReadingApps.kt`), but by the owner's
  decision the channel header shows no mark (whoever added the app knew, and the topic keeps its room).
  The reading glass in the portal and consent page shows which kind an app is.
- Not end-to-end encrypted: webhook and app messages (`messages.plain`, marked in the app),
  interactions, Nexa's case threads, poll and AutoMod notices the server writes, and everything public
  (profiles, avatars, server names, emoji, stickers).

## Web app (`web/src`)

React 18, TypeScript, Vite, zustand and react-router; `@/` is `web/src`. `main.tsx` mounts `App` and
registers two service workers: `sw.ts` (no fetch handler; a new copy taking over means a deploy, which
`lib/appUpdate.ts` offers using `version.json`) and `download-sw.ts` (scoped to `/__download/`; streams
and decrypts large downloads and videos). Pages load lazily (`lib/lazyChunk.ts`); a dialog with lazy
pages makes them with `readyPage` and calls `ready()` on the page it opens on, so frame and page show
together (Server Settings), and switches pages inside `startTransition`, which keeps the page up until
the next one's file is here.

- Routes (`routes.tsx`): `/`, `/login`, `/login/qr`, `/register`, `/recover`, `/invite/:code`,
  `/note/:token`, `/download`, `/terms`, `/privacy`, `/about`, `/call/:code` (guest call),
  `/gift/:code`, `/stickers/pack/:packId`, `/connected/discord`, `/oauth2/authorize` (adding an app); in
  the signed-in shell `/channels/@me` (friends), `@me/nexa` (with `cases/:caseId`, `bugs/:reportId`),
  `@me/notes[/:noteId]`, `@me/support` (plans), `@admin/*` (staff), `@discover`,
  `@me/:channelId[/:messageId]` (DMs), `:serverId[/:channelId[/:messageId]]`; then `/:handle` (a profile
  link, `/@name`).
- Data: `api/` is the typed REST client (`api/client.ts` adds the token, a timeout and the error
  envelope; `api/types.ts` the wire types). `gateway/client.ts` keeps the socket, `gateway/session.ts`
  starts it after sign-in, `gateway/dispatch.ts` applies events to the stores. `lib/decrypt.ts` turns
  messages into what the stores keep (webhook messages through `lib/webhookMessage.ts`);
  `lib/keyring.ts` holds roots in memory and caches channel keys.
- Stores (`stores/`, zustand), among them `auth` (status: idle, loading, offline, anonymous, locked,
  pinlocked, ready), `settings` (the account's sealed settings, synced with an optimistic version; a
  save that loses the race lays only this device's own edits over the newer copy, and the
  notification lists and `favoriteGifs` are merged entry by entry through `ListEdits`, Android
  `SettingsStore.gifEdits` and `layerGifs`, since saving those whole dropped other devices' entries),
  `messages`, `channels`, `servers`, `users`, `voice`, `ui` (modals), `typing` (for the typing pill and
  channel dots, off with `typingAvatars` and `typingChannels`; the synced `sendTyping` setting, Show When I'm Typing, stops this account's own `typing` ops in both composers, web `Composer.tsx` and Android `ui/chat/Composer.kt`), `threadLast` (each thread's newest
  message), `voiceDrag`, and `appCommands` (reloaded on `application_commands_update`).
- Folders: `crypto/`, `markdown/` (`parse.ts`: the chat syntax plus GitHub's blocks, coloured text
  `[words]{red}`, timestamps, mentions; and the renderer), `search/` (a sealed full-text index of what
  this device decrypted, in a worker; queries in `lib/search.ts`), `voice/` (`session.ts`, `engine.ts`,
  `transport.ts` with `mesh.ts` and `sfu.ts`, `frameCrypto`, `playback.ts`, `denoise.ts`, `opus.ts`,
  `redundancy.ts`, `videoCap.ts`, `shareQuality.ts`, `shareAdapt.ts`, `stallWatch.ts`, `callRecord.ts`),
  `lib/` (one concern per file, plus `look/` for themes, custom CSS and `look/plugins`, `notes/` for the
  Yjs document, sessions, sharing, history and mentions, `automod/`, and `spell/`, Hunspell in a worker
  for Nexa's text menu), `components/` by area (`shell`, `sidebar`, `chat`, `composer` with `format/`
  the formatting editor, `settings`, `server` for Server Settings, `voice`, `moderation`, `nexa` with
  `ThreadChat` drawing case threads and bug reports as conversations, `common` for shared controls, and
  so on), `pages/` (one per route), `styles/` (`tokens.css` holds every theme token; `base.css`,
  `markdown.css`, `fonts.css` with Wix Madefor, `fonts-classic.css` with Onest and Mona Sans), `i18n/`
  (`useT()`, `t()` outside components, `<Tr>`; English bundled, the other eleven catalogues, ar, de, es,
  fr, hi, ja, ko, pt-BR, ru, sv, zh-CN, loaded when chosen; flat keys dotted by area: `settings.*`,
  `server.*`, `chat.*`, `whatsNew.*`, `android.*`, ...).
- Tests sit beside the code (`*.test.ts[x]`); Vitest runs in node with `fake-indexeddb`, component tests
  start with `// @vitest-environment jsdom`.
- An effect that calls `scrollTo`, `scrollBy` or `scrollIntoView` keeps the call in braces: Chromium's
  scroll methods now answer a promise, which an arrow effect returns and React then calls as its
  cleanup ("c is not a function" on unmount).
- Write `:hover` plainly: `hoverOnlyWithAMouse` (a PostCSS step in `vite.config.ts`) moves every
  `:hover` rule into `@media (hover: hover)`, since phones keep the last tap's hover; what only hover
  reveals needs its own `@media (hover: none)` rule.
- Colours are picked with `components/common/ColourInput.tsx` (Android `openColourPicker` in
  `ui/settings/ColourPicker.kt`), never `<input type="color">`.
- `components/chat/MessageList.tsx` measures reading positions from the bottom of a reversed scroller.
  In Chromium, incoming history waits for touch and wheel scrolling (trackpad momentum included) to
  settle, restores the visible row in a layout effect if Chromium rebases the scroll range, and arms a
  quiet fallback from the passive wheel listener when the loaded edge stops a scroll event (a wheel
  is settled after `WHEEL_REST_MS`, 200 ms, without wheel or scroll; touch keeps 1.5 s). A wheel still
  turning against the loaded top lets held rows in, or a list at `WINDOW_CAP` be cut back, since
  nothing is moving there. Older pages are asked for two screens from the top, up to `HELD_AHEAD`
  rows past what a gesture holds, and the check runs again after each page lands and on release
  (only real scroll events count as movement for the trim). Cutting a long list back restores the
  on-screen row (`trimAnchor`), since the browser clamps a shrinking range before `holdReading`
  runs. Fast wheel scrolling once sat on one held page indefinitely; `MessageList.test.tsx` covers
  both inputs, late image heights, browsers without scrollend, fetching ahead, the pinned wheel and
  the clamped trim. Android uses stable row keys in its reversed LazyColumn.
- Hashtag suggestions and send and edit conversion include visible text, announcement and forum
  channels. Subchats stay listed under the open conversation, and unread ones also under their parent
  until read. Sidebar rows and the Subchats panel count every unread message from others in red (capped
  at 99+). Android reruns read marking when history finishes loading.

### Shared user text layout

User text must fit its slot whatever its length limit. Markdown list items clip their own ink, so their markers sit inside with a
hanging indent (`markdown.css`); a marker outside an item is cut off. Never blacklist Unicode to fix layout: U+FDFD,
combining sequences, non-breaking spaces and long words must be contained, even from older saves or
other clients. `base.css` gives inherited emergency wrapping and the `PersonName` identity boundary;
compact labels get explicit line limits, titles are bounded, markdown blocks wrap and clip excess ink,
code and tables scroll in their own wrappers, the chat list never scrolls sideways, and emoji fallbacks
stay icon-sized. Android bounds ink in its common text, name and markdown renderers (keep its bounded
name-effect halo). Removable labels go in a shrinkable text child so their buttons stay visible, and
shared action buttons do not shrink in flex rows (text and inputs yield, form rows wrap).
`docs/UI_TEXT_AUDIT.md` is the input-to-display inventory and regression workflow, and
`docs/UI_TEXT_INPUTS.md` with `docs/UI_TEXT_INPUTS.tsv` and `docs/UI_TEXT_INPUTS_ANDROID.tsv` the
field-level ledger; update them when adding shared text fields, dynamic prompts or imported metadata.
The dev-only `web/tests/text-layout.html` fixture (with a button-clipping negative control) is checked
with `cd web && npx tsc --noEmit -p tests` and `tools/text_layout_audit.py` against an explicit Vite
scratch-server proxy; Android's debug-only `TextLayoutAuditActivity` takes theme, payload and page
extras. Neither is a production route.

### Motion

- `lib/motion.ts`: `motionOff()` (Reduced motion, Nexa's or the system's) for script animations, and
  `listOpenedAt`. CSS animations and transitions stop through `base.css`'s Reduced motion rules.
- Every control eases between its states: `:where(button, a, ...)` in `base.css` gives colour,
  background, border, shadow and opacity transitions at zero specificity, so a component's own
  transition wins. `applyTheme` holds every transition (`data-theme-switching`) while a theme, accent
  or size goes on, so controls never trail the page.
- Tab strips: `lib/tabGlide.ts` (installed in `App.tsx`) watches `aria-selected` and `aria-checked`. In
  every `role="tablist"`, and every `role="radiogroup"` marked `data-glide` (segmented switches only,
  never swatches, cards or chips), the chosen item's pill slides to the new one: a copy of the new
  item's look moves behind the words while the item's own background stands aside
  (`[data-gliding]`), then the item takes it back with transitions held (`[data-glide-hold]`; handing
  it back with them running flashed once as it landed). Only between items of the same class; a strip
  whose choice is an underline does nothing; `data-no-glide` opts out (the window's tab bar). The
  leaving item's look is read on pointerdown and keydown, so the pill changes colour on its way.
- Closing: `lib/leaveCopy.ts` leaves an inert, `aria-hidden` copy fading where the thing stood
  (Modal's `leave`, `SettingsLayer`, `Lightbox`, each toast), with typed values and scroll positions
  copied, ids, `data-modal-layer` and `role="dialog"` removed (code that asks whether a dialog is open
  must not see it), nothing in it moving (`[data-leaving]`), and no copy of anything holding a canvas,
  video or iframe, which would come up blank. A copy is removed when its animation finishes. A
  dialog opening while another's copy fades takes over the dim at once (`data-calm`) and the copy
  hurries out above it, so the dim never doubles or dips.
- Arrivals: a message that comes in while its conversation is open rises in (`data-fresh`; never
  history or what came before `listOpenedAt`); a new reaction pops and a changed count ticks
  (`Reactions.tsx` `moving`, let go on animationend so a render cannot cut it short); opening a category
  slides back only the channels it hid, staggered (`ServerSidebar` `opened`); an expander's body
  unfolds; the member list beside the chat slides in when opened (`ChatView` `membersEnter`), not
  when a conversation changes; on a phone Settings pushes a page in and brings the list back from the
  other side (`SettingsLayer` `arrive`); the picture viewer slides between pictures (`data-step`); the
  sign-in pages rise only on the first one landed on (`AuthLayout`). State that starts an animation
  is decided while rendering, never in an effect, so nothing shows still for a frame first.

### Homepage demo (web only)

`HomeDemo.tsx` embeds `demo.html`, a second Vite entry, in a sandboxed iframe running the real routes
and shell (`demo/DemoApp.tsx`) for a fresh, empty visitor account (no fictional people or prefilled
servers). `demo/environment.ts` runs before any app import: storage is fresh maps, IndexedDB a fresh
in-memory fake-indexeddb, Cache Storage and the desktop bridge unavailable; fetch allows only
same-origin bundled assets without credentials; sockets, XHR, workers, media devices and native
navigation are blocked; no real token is read or issued. `demo/runtime.ts` replaces the API with
memory-only profile, settings, server, channel, message and note operations; unknown routes fail closed
with Create an Account to Continue, as do people, invites, calls, uploads, payments and security
actions. New app endpoints must never reach a real transport in the demo. `lib/demoMode.ts` is true only
there: RequireAuth skips the gateway, AppShell skips live effects and update prompts, voice and plugins
ask for an account first, `desktopBridge()` refuses, LookEffect allows local CSS. Only `/demo.html`
allows same-origin framing (every other page forbids it), with a stricter CSP. Full Screen is a fixed
overlay on every device (iOS included) keeping the same iframe; dialogs focus with `preventScroll` so
the gate cannot scroll the homepage.

### Public source

`lib/legal.ts` exports `GITHUB_URL` (Android `Legal.githubUrl` from its copied legal asset), the public
repository for every client and the server. The homepage names open source in its introduction and as
the first of its five promises and links GitHub; Settings has a GitHub shortcut on every platform; the
About page and Settings, About explain the MIT license and invite contributions (web `OpenSourceBlock`,
Android the same `project.*` strings). README.md uses the synthetic `web/public/home/app.png`, which
holds no personal conversations.

### Apps (web)

`api/apps.ts` (REST), `lib/apps.ts` (commands, options, names in the reader's language),
`lib/interactions.ts`, `lib/botCopies.ts`, `lib/pickables.ts` (select-menu choices),
`lib/passwordProof.ts` (the portal's reset proofs). `components/chat/apps` draws app messages (the "used
/name" line shows the submitted options on hover or tap) and the Apps entries in message, person and
member menus (`ContextMenuItem.picture`); `components/chat/AppLinkCards.tsx` draws install-link cards
(`/oauth2/authorize?client_id=...`); `components/apps` holds the shared parts (`SelectBox`,
`AppFormModal`, `ReadingGlass`, `PermissionPicker`, `AppIcon`, `BotTag`). `composer/CommandMode.tsx`
fills a command an option at a time (`:name` emoji from `composer/emojiSuggestions.ts`, the picker's
`insert`, Ctrl+A for the whole command); a slash opens `Autocomplete`'s `CommandPicker`. The composer's
lists and option bar are `docked` in the page's flow so messages move up rather than being covered
(Android `CommandPicker` and `dockedListMax` in `ui/chat/Composer.kt`, and `ui/apps/CommandMode.kt`).
The Developer Portal is `settings/portal` (`DeveloperPortalPage`; `AppDetail` with General, Bot,
Install, OAuth2 and Commands tabs, deleting through
`DELETE /api/v1/applications/{id}/commands[/{cid}]`), Authorized Apps `settings/AuthorizedAppsPage.tsx`,
a server's apps `server/pages/AppsSection.tsx`, the consent page `pages/AuthorizePage.tsx`.

### Menus

- Every menu is registered in `lib/contextMenus/registry.ts`, never built inline:
  `defineMenu<Ctx>(kind, groups)` at module level beside what it acts on (`chat/messageMenu.ts`,
  `sidebar/serverMenu.ts`, `sidebar/dmMenu.ts`, ...), groups of
  `button(id, (ctx) => item | items | null)`, opened with `menu.open(ctx, placement)`. Button ids are
  camelCase and unique across all menus (`deleteMessage`), since plugins name buttons by id alone;
  buttons two menus share come from a factory taking the ids (`imageButtons`, `dmArrangeButtons`,
  `threadButtons`, `relationshipButtons`). A menu says what it is about with
  `about(menu, subjects, (ctx) => ({ message, userId, channelId, serverId }))`, which plugin callbacks
  receive.
- `registry.test.ts` fails if anything but the registry calls `openContextMenu` or an id repeats;
  `lib/contextMenus/menuDocs.test.ts` generates the menu table in `docs/plugins.md` and fails when it is
  stale (`UPDATE_DOCS=1` rewrites it) or a menu lacks a line in its `WHERE` list; every menu module must
  be in `lib/contextMenus/all.ts` (a test checks). Plugins change menus through the registry's layer
  (`addButton`, `removeButton`, `editButton`, undone by `dropChanges` when they stop).
- A message's menu starts with quick reactions (`quickReactions()` into `ContextMenuState.reactions`;
  Android `QuickReactions` in `ui/chat/MessageMenu.kt`). Frequent emoji are snapshotted once per open
  expression menu on both clients, so hit targets do not move while it is open.
- Right-clicking a picture or name in a message, or a reply's quoted name, opens the member list's menu
  for that person (`chat/personMenu.ts`: `memberMenu`, `groupMemberMenu`); Android claims the long press
  with `PressedPerson` in `ui/chat/MessageRow.kt` before `rowTouch` and opens `openPersonMenu` in
  `ui/chat/Members.kt`. Copy ID for Bots (`lib/copyId.ts` `copyBotIdItem`, `botId`; Android in
  `ui/profile/PeopleUi.kt`) sits beside every Copy User ID and copies the id bots see (`botproto`'s
  offset).

### Plugins (web and desktop only, never Android)

- `lib/look/plugins/runtime.ts` pins, loads, starts and stops them. No `apiVersion`, or 1, gets the v1
  API (`api.ts`, kept as it was); 2 gets v2 (`api2.ts` types, `runtimeV2.ts`, `objects.ts`,
  `permissions.ts`).
- v2 grants are `PluginEntry.permissions` in the settings. Every v2 method checks its permission when
  called (`PermissionError`, or a rejected promise), events arrive only with the permission for what
  they carry, and context objects omit what the plugin may not see. Code never runs before someone
  agreed to it: the Add Plugin review and link-plugin updates read the API version, the plainly written
  `permissions` list and the author from the text (`declaration.ts`), and exactly that list is granted
  (all or nothing; updates keep what is still asked, ask again for anything new, drop the rest).
  `components/settings/look/PermissionLedger.tsx` draws permissions wherever they are shown. v2 events
  are built on `dispatchHooks`; v2 stylesheets go when the plugin stops unless added with `persist`.
- Commands (`components/composer/commands.ts`): a v1 plugin's cannot take a taken name and its return
  value is sent; a v2 plugin's (`takePluginCommand`) may take another plugin's name, never the app's,
  and sends nothing. Keybinds: `lib/pluginKeybinds.ts`, run by `components/shell/Shortcuts.tsx` after
  the app's own (in the window only), chosen keys in the `pluginKeybinds` setting (checked by
  `sanitizePluginKeybinds`), set in `components/settings/PluginKeybinds.tsx`. Toasts have a `warning`
  kind and a 1 to 15 second duration (`toast(text, kind, duration)`, used only by plugins). A plugin's
  menu button may name a lucide icon (`components/common/MenuIcon.tsx`, loaded only when shown); the
  app's own buttons have none.
- v2 also has `api.composer` (`composer.write`: types into the open message box through the
  `ec:insert-text` window event the member menu's Mention uses, never sends), `api.navigation`
  (`navigation`: `current`, `go`, `openChannel`, and the `locationChanged` event, which LookEffect
  feeds through `noteLocation` from the router's pathname), `api.utils` (no permission), `ui.prompt`
  (`promptFields`), `ui.openProfile`, `ui.copy`, `users.presence`, `servers.emojis`, and message pins,
  reactors and `markRead` (`messagePinned`/`messageUnpinned` come from `onMessageChange`). Version 2
  only grows: add members and permissions, never change an existing shape.
- `docs/plugins.md` and `docs/custom-css.md` are the guides for authors, written for GitHub's
  renderer (GitHub alerts, Mermaid, `<details>`, banners in `docs/assets/` with dark and light
  versions); `docs/examples/` holds whole plugins. The Plugins and Custom CSS pages link them
  (`GuideLink`, `PLUGIN_GUIDE_URL` and `CSS_GUIDE_URL` in `lib/legal.ts`). An API change updates the
  guide (and its What's New in the API list) in the same commit.

### Notes

- Mentions: notes write `<@id>` as messages do. `@` in the editor offers the people the note is shared
  with (`GET /notes/{id}/sharing`, viewers included, never yourself), ranked by `lib/notes/mentions.ts`,
  in the message box's `Autocomplete` placed by `components/notes/NoteMentions.tsx`; the format
  surface's `mentionAt`, `mention` command and `setNames` (from `useFormatBench`) insert and relabel
  them without a save or an undo step. A clicked mention is selected whole and opens the profile popout
  with `keepFocus` (`openProfilePopout`'s fourth argument; only on a surface given `onMention`).
  Mentions notify nobody. Android `notes/NoteMentions`, `ui/notes/NoteMentions.kt`, `FormatSurface`
  (`mentionAt`, `Cmd.Mention`, `relabelPeople`, `onMention`, `tappedAt`), `mentionTaps` in
  `FormatPage.kt`.
- Files sit on their own line as `![name](nexa-file:id)`, width and side as `?w=60`, `?w=60&a=left`,
  `?a=right` (`RE_FILE`, `fileLine`, `fileAlign` in `markdown/parse.ts`; the middle and full width are
  left unsaid). `components/notes/NoteFile.tsx` resizes and moves them (drag or arrow keys) through the
  surface's `fileWidth` and `placeFile` commands, one undo step each. Android `ui/notes/NoteFile.kt`
  (`FileMove`, with TalkBack actions), `FormatSurface` `Cmd.PlaceFile`, `BlockPlaces` in
  `ui/format/FormatPage.kt`.

### Names and profiles

- Name styles (`lib/nameStyle.ts`, `api/name_style.go`, the public column `users.name_style`; Android
  `data/NameStyle`, `ui/chat/RoleLook.kt`, `ui/chat/NameFonts.kt`, `ui/settings/NameStyleSheet.kt`) are
  a font from `NAME_FONTS` (Fontsource via `styles/nameFonts.css`, loaded only once a styled name is
  drawn, `loadNameFonts`; `font-size-adjust` matches Wix Madefor Display's lowercase height, Bungee its
  capital height, and Android multiplies by the same measured `scale`), an effect from
  `styles/nameStyle.css` (`ns-*`: Solid, Gradient, Glow, Ink, Pop, Candy, Aurora, Chrome, and the
  plan-only Holo, Neon and Flame), colours, and an optional `animated` boolean (missing keeps the
  original, where only Aurora, Holo, Neon and Flame move). A new font goes in `NAME_FONTS`, the CSS
  imports, `nameFonts` in the Go file, `FONTS` in `android/tools/name_fonts.py` (run it to write
  `res/font/name_<id>.ttf`) and `data/NameStyle.kt`. Appearance's synced `animateNames` (and Reduce
  Motion) stops name animations and role shimmer locally. A server role's colour overrides personal
  effects but keeps the font. Settings, Profiles edits it (`components/settings/NameStyleEditor.tsx`);
  staff can reset `name_style`. Android's debug-only `NameStyleAuditActivity` draws every face (`page` 0
  to 2) or effect (`effects=true`) for screenshot comparisons.
- Identity labels go through `components/common/PersonName.tsx` (Android `PersonName` in
  `ui/chat/RoleLook.kt`, with `RoleName` and `OwnName`), and names inside translated prose through
  `<Tr>`/`lib/personText.tsx` (Android `PersonText`, `PersonInlines`, `PersonWords`), so only the name
  takes the effect. They keep the caller's text, follow live styles and the current account's profile,
  fetch missing profiles, and let a canonical null style override stale list snapshots;
  `nameStyle`/`nameClass` in `lib/roleLook.ts` carry the person's own style as `own` (`useMemberLook`,
  `withOwn`). Mentions and collaborative cursors use the same renderer.
- The profile designer (Settings, Profiles: `components/settings/ProfilePage.tsx`; Server Profiles:
  `ServerProfileDesigner.tsx`; shared `DesignerLayout`, `FormSection`, `FormField`, `FormExpander`,
  `WidgetList` and `jumpToField` in `settings/designerParts.tsx`; Android `ProfilesPage.kt` and
  `ServerProfileDesigner.kt` with `ui/settings/DesignerForm.kt`) is one form of every part under three
  headings (Pictures & Colours, Your Name, About You), with name style, server tag and typing style opening
  in place, and the profile preview (Profile Card, Full Profile, In Chat) beside it from 720 px
  (`PREVIEW_BESIDE`) or above it. Pressing a part of the preview scrolls to and lights its field; nothing
  is edited on the preview. Only the page scrolls: the preview is sticky (by its foot when taller than the
  window) and the profile card drops its own scrollers in design mode (`.card[data-design]`). Narrower,
  a bar above the preview folds it (`nexa.profilePreviewFolded`, Android preferences `designer`) and
  stays at the top once the preview scrolls away, scrolling back to it (Android `ProfilesPreviewBar` in
  the page overlay).
- In a full profile (not the popout or the designer) the avatar and banner open the picture viewer on the original upload: web `ProfileCard.tsx` (`viewing`, the `Lightbox` from `chat/Lightbox.tsx`), Android `ProfileCard.kt` (`onAvatar`, `onBanner` into `openLightbox`, `Media.avatarOriginalUrl`, `bannerOriginalUrl`).
- Activity cards (`components/activity/ActivityCard.tsx`, Android `ui/profile/ActivityCard.kt`) scroll
  long text with `ScrollingText`. Their links show the real canonical host beneath custom labels, need a
  full-URL review before opening (middle clicks included; Android's confirm sheet), and act only for
  HTTP and HTTPS without userinfo.

### Themes

- `lib/theme.ts` applies a theme and `lib/look/` derives presets and custom themes. The gallery is
  `components/settings/look/ThemeGallery.tsx` (Android `Presets.cards`, `ThemeModes` in
  `ui/settings/AppearancePage.kt`); a theme with a light version is one card (`presetCards`, `ModeCard`)
  that keeps the light or dark mode being worn.
- The theme builder (`components/settings/look/ThemeBuilder.tsx`, Android `ui/settings/ThemeBuilder.kt`)
  has Simple, Advanced and Tune tabs, undo and redo (changes within 600 ms are one step; web Ctrl+Z
  outside text boxes) and Compare, held to show the saved theme (or the start of a new one). Beside the
  tools from 760 px of builder width, above them below that, `ThemePreview.tsx` (Android `MiniApp`) is a
  small Nexa whose every part names its token (`data-token`), so pressing it opens that colour in
  Advanced (`focus`, scrolled to and lit); Preview As shows that preview through a colour-vision
  simulation only, and `PaletteStrip` is the keyboard and TalkBack route to every colour. The palette
  tools (Tune's `tunePalette`, `visionPalette`, `accentIdeas`, `backgroundIdeas`, `surprise`,
  `withPalette`) are `lib/look/tools.ts` and Android `ui/theme/look/ThemeTools.kt`. Nothing they make
  is stored as such: results become hand-set overrides, so the saved theme format did not change and
  older apps read every theme. While the builder is open the Appearance page is as wide as the profile
  designer (`data-wide-page`, `.content:has(...)` in `Settings.module.css`).
- Seasonal themes (`lib/look/seasonal.ts` `SEASONS`, kept equal to Android's
  `ui/theme/look/Seasonal.kt`; `look_vectors.ts` checks the palettes) run through `deriveTheme` over a
  span of days in the device's calendar. The gallery offers them only in season (`seasonCards`, a season
  and its Night version on one card) and never puts one on by itself; out of season one draws as Dark
  (`resolveLook`) and `ThemeEffect` sets the account back to `dark`.
  `components/common/SeasonalPerch.tsx` perches `assets/seasonal/<id>.svg` (which Android copies) on the
  message box. Winter Night's `snow` draws `components/chat/SnowFall.tsx` (Android
  `ui/chat/SnowFall.kt`, same model in dp) as a canvas behind the messages, off with reduced motion or
  `seasonalSnow` (Show Falling Snow); its frame time step is clamped at zero, since a negative step
  after a hidden tab made every flake NaN.
- `VITE_ALL_SEASONS=1` (web, `npx vite build` into a scratch directory) or `-PallSeasons=true` (Android)
  runs every season every day; never set either for a deploy or a release.

## Desktop app (`desktop/`)

Electron; bump the version in `desktop/package.json` for every release. `src/main/index.ts` opens one
window on the live site (`config.ts`: https://nexa.fish, or `EVILION_URL` in an unpackaged app;
`npm start` builds and runs it) with a tray, system-wide keybinds (`shortcuts.ts`), the screen picker
(`displayPicker.ts`, `src/picker/`), window sound sharing (`appAudio.ts`; from 1.2.7 voice changers and
mixers that play the person's own voice back, such as Voicemod or Voicemeeter, matched by
`selfMonitoringApp`, are shared without sound), permissions limited to Nexa's origin, takeover from an
older Mac copy (`succession.ts`) and updates from Nexa's own server (`update/`). `src/main/rpc/` is a
Rich Presence server speaking Discord's IPC and local WebSocket protocols, passing programs through to
the real Discord when it runs and handing activities to the page for chatd. `src/preload/index.ts` puts
`window.evilionDesktop` on Nexa's pages; `src/shared/bridge.ts` and `web/src/lib/desktopBridge.ts`
describe it and must stay in step and compatible with installed apps (anything new is optional and
checked before use). `build.mjs` bundles with esbuild, `electron-builder.yml` packs, `sign-mac.cjs`
signs Mac builds ad hoc.

- Releases are built on the Linux server, never brought from another machine: the owner wants every
  changed app built and published by `make build` (2026-10-20). Its `desktop-release` step runs
  `tools/desktop_release.py --if-changed`, which builds only when `desktop/package.json` names a version
  newer than the one in `DESKTOP_DIR` (`data/desktop` by default), so app changes bump the version in
  the same commit: Windows and Linux with electron-builder, the Mac x64 and arm64 dir targets signed ad
  hoc (`sign-mac.cjs` with `rcodesign`), packed by `tools/mac_package.py` (`mkfs.hfsplus`, sudo,
  libdmg-hfsplus's `dmg`) and published by `tools/publish_desktop.py`.
  `python3 tools/desktop_release.py` publishes the current version regardless (`--republish` replaces
  it). `make desktop` is the Mac's own build into `desktop/dist` and publishes nothing.
- Signed updates (from 1.2.8): updaters verify platform-bound Ed25519 signatures over the exact manifest
  bytes before parsing checksums (`SignedReleaseProvider` for Windows and the AppImage, `fetchRelease`
  on macOS), with no unsigned fallback. `releaseKey.ts` holds only the public anchor, a new key the
  owner authorized after the original was unavailable. The private key is at
  `~/.local/share/nexa-signing/update-key.pem` on the Mac and the build server (0600 in a 0700
  directory); never commit, replace or regenerate it while building. `NEXA_DESKTOP_SIGNING_KEY` may name
  another path, but its public key must match the pinned anchor. `tools/publish_desktop.py` signs staged
  manifests before exposing any artifact, publishes `signed-latest.yml`, `signed-latest-linux.yml` and
  `signed-latest-mac.yml` with `.sig` sidecars (no-cache), keeps the legacy `latest*.yml` feeds and
  their artifacts for the manual migration, checks every manifest's version, and copies installers and
  blockmaps through temporary files with an atomic rename
  (`python3 -m unittest discover -s tools -p test_publish_desktop.py`). Mac archive reads enforce the
  signed byte count with idle and total deadlines and partial-file cleanup. The first installer with
  this anchor is a manual trusted download.
- Updates (`src/main/update/`, `test/updater.test.ts`; the Mac through `mac.ts`; offered by the page's
  `components/shell/UpdateDialog.tsx`) are checked 10 s after start and every minute. From 1.2.6 nothing
  installs on quit (`autoInstallOnAppQuit` off; Windows' silent installer closed a copy reopened
  meanwhile), so an update installs only from Update, once; a newer release replaces one waiting, and a
  check or download without progress for three minutes is cancelled and retried with backoff.
  `will-prevent-unload` keeps a page's `beforeunload` from cancelling a quit.
- Below `REQUIRED_DESKTOP` (`web/src/lib/requiredDesktop.ts`, now 1.2.8)
  `components/shell/RequiredUpdate.tsx` shows once such a release is published. Below
  `MANUAL_SIGNATURE_VERSION` (1.2.8) it is a manual download with no Later, automatic restart or old
  updater path, even during a call and ignoring a staged old-updater result; app data stays in the
  profile. A future automatic required update waits for a call to end. Only clients loading the current
  web build see it; old code cannot be made to enforce a new anchor.
- Background (from 1.2.5, measured with `tools/desktop_idle.py`): the window is created with background
  throttling on (one created with it off never sleeps), so a hidden page stops drawing and slows its
  timers while the gateway heartbeat runs in a worker. A call keeps full speed (`applyThrottling` in
  `index.ts`, from the page's `setCallActive`); throttling resumes only once the window is shown again,
  and a navigation clears the call flag until the new page reports again. A launch into the tray
  (`--hidden` with Start Minimized) shows the window for a moment, transparent and unfocused, until the
  page has been hidden once (`putToSleep` in `window.ts`; not on Linux). Apps before 1.2.5 keep every
  page at full speed. The web helps: idle audio rests (`restsWhenIdle` in `lib/audioKeeper.ts`), call
  video is not pulled while minimised or in the tray (`voice/videoShown.ts`), the voice gate does not
  open just because the page is hidden (`gateOpen` in `voice/engine.ts`), and only a call listens for
  `devicechange` (`voice/session.ts`), which would wake the browser's media services.
- Open Original (from 1.2.10; `originals.ts`, the optional `openOriginal` bridge call) hands decrypted
  images and videos to the default browser through a temporary listener on 127.0.0.1 that accepts only
  exact random-token paths and Host headers, serves inert types with no-store and a sandbox CSP, and
  expires each original after ten minutes. Files stay in memory (eight originals, 256 MiB, oldest
  dropped first), are never uploaded or written to disk, and go on Quit. Web originals use a normal tab;
  Android shares a read-only FileProvider URI with an image viewer.
- `config.testHook` carries development overrides (profile paths, capture devices, simulated move
  decisions, update tests) and ignores them in packaged apps; `EVILION_DEBUG` is an intentional logging
  switch.

## Android app (`android/`)

Kotlin and Jetpack Compose (package `fish.nexa`, debug builds `fish.nexa.debug`; minSdk 26, targetSdk
36). It talks to the server as deployed, so it never depends on unreleased server changes. Libraries:
OkHttp, kotlinx.serialization, Coil 3, Media3 ExoPlayer, Lottie, BouncyCastle, argon2kt, ZXing,
webrtc-sdk. Native code: `app/src/main/cpp` through CMake (call frame crypto and H.264 in `frame/`, the
pinned RNNoise source and weights, whisper.cpp 1.9.5) and `android/ydoc` (yrs, the Rust Yjs, built with
cargo-ndk) for notes.

- `Nexa.kt` is the object graph; `data/State.kt` keeps each store as an immutable StateFlow snapshot,
  mirroring the web's stores; `data/Session.kt` handles gateway events. Packages: `crypto`, `net`
  (`Api`, `Gateway`, `Models`), `data` (stores and logic ported from the web), `media`, `notes`,
  `search`, `voice`, `notify`, `ui/...` (`ui/theme` from `tokens.css`, `ui/markdown` the ported parser).
- Compose redraws only for state it collected: `Nexa.settings`' getters and stores' `get(...)` are plain
  reads, so read what a composable shows with `collectAsState()` (or `ui.shell.settingOn(key)`) in that
  composable. Hot rows use `collectEntryAsState` (`ui/StoreState.kt`) for one user, presence, setting,
  typing channel, read marker or message channel, so unrelated gateway updates do not redraw them.
- Large servers: `RoleLooks.of` visits only the member's roles and must not cache by `ServerState` (its
  hash walks the whole roster); `ServerState.permissionRoles` parses permissions once per snapshot;
  channel trees group children once; avatars observe only their animation switch and composite offscreen
  only for a presence cutout. REST and member-page decoding (`net/Api`) run off the UI thread and keep
  cancellation, and `data/MemberRoster` filters and sorts on a worker before `MemberList`. Regression
  tests cover these.
- Layout (`ui/shell/Shell.kt`, the web's breakpoints): below 768 dp the rail, sidebar and member list
  are drawers swiped open and closed anywhere; from 768 dp (`Nav.wide`) the rail and sidebar stay and
  settings keep their list beside the page (`SettingsLayer` in `ui/settings/Settings.kt`,
  `SettingsFrame` in `ui/server/Frame.kt`); from 1100 dp (`Nav.roomy`) the member list can stay too
  (`Nav.toggleMembers`, `Nav.membersPinned`). A slow drag settles to its side past 40%, as on the web
  (`AppShell.tsx`).
- `NxSlider` marks itself `noSwipeWhileTouched` (`ui/server/Frame.kt`), and both settings frames'
  swipe back check `NoSwipe.active`, so dragging a slider towards the back edge moves the slider.
- Shared pieces: `openSheet` (`over`, `heading` with `SheetTitle`; an empty footer takes no room),
  `Modifier.floatAbove(gap)` in `components/Controls.kt` (hangs a list over what it sits on),
  `SQUARE_AVATAR`, `TickBox`, `RadioCard(tick = true)`.
- Moderation: `data/NexaInbox` (a member's side), `data/Moderation` (staff: `mod_unread`, `events` for
  open views), `ui/nexa/ThreadChat`, and the native staff panel in `ui/moderation` (`Panel`, `Users`,
  `Bots`, `Person`, `Moderate`, `Cases`, `Bugs`, `Servers`, `Expressions`, `Calls`, `Logs`, `Parts`,
  `Words`) at `Route.Admin(path)`, the path being the web's below `/channels/@admin`.
- Apps: `data/Apps`, `AppCommands`, `Interactions`, `BotCopies`, `Pickables`, `net/AppModels`; screens
  in `ui/apps` (`CommandMode`, `CommandBox`, `CommandList`, `AppMessage` with `InteractionLine`,
  `AppForm`, `SelectBox`, `Authorize` for `/oauth2/authorize` links, `ReadingApps`, `ReadingGlass`,
  `AppMenus`), `AppLinkCards` in `ui/chat/LinkCards.kt`, `MenuItem.Action.picture` in
  `ui/layers/Layers.kt`, the portal in `ui/settings` (`DeveloperPortal`, `AppDetail`, `PortalParts`,
  `AuthorizedApps`) and `ui/server/AppsSection`. `ui/markdown/Webhook.kt` parses webhook and app
  messages as the web's `lib/webhookMessage.ts` does (`web-hook-vectors.json.gz`); both pass text
  through `discordMarkdown`, which puts a mid-line fence on its own line and splits `**`, `__`, `~~` or
  `||` spans that cross lines, since Nexa's parser keeps spans on one line.
- GIF favourites (`media/GifFavorites`, `ui/pickers/Gifs.kt`) show and send a link favourite as
  `MediaLinks.fresh` re-signs it (saved links run out after a day) and an uploaded one from its
  attachment, staged again by `Staging.favorite` as the web's `favoriteFile` does; only a link the
  server refuses to sign or a file that cannot be opened counts as gone.
- `data/ThreadLast` mirrors the web's `threadLast`; `KeyManager` asks holders again (`/keys/request`)
  while it waits.
- There is no push service: `notify/ConnectionService` keeps the connection open in the background (on
  unless turned off) and `Notifier` raises alerts.
- The exported share target takes only external `content:` URIs, at most ten; `file:` URIs and Nexa's
  own FileProvider authority are refused before staging, so other apps cannot borrow Nexa's file access.
- Session reset cancels pending notification posts, removes personal notifications and their history
  (publishing checks the account generation under the reset lock, so a late avatar load cannot repost an
  old account's alert), clears layers and navigation, and deletes decrypted media; cache filenames
  include the numeric account id.
- `AudioRoute` puts the phone in call mode without taking audio focus, so other apps keep playing and
  the mic keeps sending; only a phone call (the audio mode, read once a second) silences the call both
  ways until it ends.
- Updates (`ui/AppUpdate`): `/api/v1/android/latest` is checked ten seconds after opening and every
  minute in front (failures back off to fifteen minutes); the build for the phone's processor downloads
  in the background (resuming with Range and If-Range on the SHA-256 ETag) and is verified by SHA-256
  before it is offered, then installs through a `PackageInstaller` session (`UpdateInstallReceiver`),
  `voice/Pip.held` keeping a call from floating over Android's install buttons. Debug builds never
  update themselves. To test it, build two releases with
  `tools/android_release.py --server http://10.0.2.2:$PORT --to <scratch dir>` into the same folder (the
  debug network config copied into `src/release/res/xml/` for those builds only) and serve it with
  `ANDROID_DIR`.
- OTA content packs (`data/Ota.kt`, server `androidOTAPack` in `api/android_handlers.go`):
  `GET /api/v1/android/ota` describes `ota.json` beside `manifest.json` in `ANDROID_DIR`, offered only
  while its `version_code` equals the published APK's and `ota-<hash>.zip` is there at its size. The
  app checks 15 s after start and every 15 minutes in front, downloads the zip, checks size and
  SHA-256, unpacks only `locales|sounds|seasonal|whatsnew|legal/<flat name>` into `noBackupFilesDir/ota`,
  and takes it up at the next process start (`Ota.init` in `NexaApp` before anything reads assets); a
  pack made for another versionCode is ignored and dropped after an APK update. To test it, serve a
  scratch `ANDROID_DIR` whose manifest says versionCode 3 (what a debug build carries) with a pack for 3.
- Code fixes still need an APK, but on Android 12+ an app that installed itself updates with no system
  prompt (`setRequireUserAction(USER_ACTION_NOT_REQUIRED)`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`;
  Android 14+ also claims update ownership), so Install is one tap; the first update after a manual
  install may still ask.
- Motion matches the web's (Web app, Motion): `ui/components/Glide.kt` (`rememberGlide`, `glideArea`,
  `glideItem`, `GlidePill`) slides a row's chosen pill (the staff panel's tabs, Mentions kinds, an
  app's Allowed and Denied, changing colour, both `Segmented`s, the formatting editor's Text and
  Highlight, a person's sub-tabs) and `TabStrip` has its own; toasts leave through `ToastSlot`;
  reactions pop (`seen` is remembered before the row returns for having none) and counts tick; the
  settings layers push pages on a phone (`arrive` in `Settings.kt` and `Frame.kt`) and their pages have
  no fade of their own; the pinned member list slides in only when pinned; `AuthLayout` rises only on
  the first screen. Values that start an animation are fixed in `remember`, not set from an effect.
- `copyWebAssets` copies the web's locales, sounds, emoji sprites, `whatsNew.ts`, seasonal pictures and
  legal documents into the app; `assets/lucide.json`, `assets/emoji/` and `assets/licenses.json` come
  from `android/tools`. Ported logic (markdown, search, themes, the formatting editor, webhook messages,
  crypto, metadata scrubbing, the notes CRDT) is tested against vectors from the web's own code
  (`app/src/test/resources/web-*-vectors.json*`, mostly made by `android/tools/*_vectors.ts`; each
  file's header says how).
- Releases: `make build` runs `tools/android_release.py --if-changed`, which does nothing when nothing
  the app is made from changed since the release in `downloads/android` (its manifest keeps `source`
  and `code_source` hashes). When only `OTA_SOURCES` changed and `code_source` still matches, it
  publishes an OTA content pack for the published APK instead of building (`--ota` forces that and
  refuses when code changed); otherwise it builds an APK, removing any pack, numbered after the published one (versionCode + 1 and the last number of versionName + 1,
  passed as `-PnexaVersionCode` and `-PnexaVersionName`, so nothing in git changes), signs it with the
  private key configured in `android/signing` and publishes it; chatd offers it at once and phones
  update. `make publish-android` builds one regardless. Builds up to 1.1.0 used an older, lost key and
  cannot update; those phones reinstall. Release builds need the JDK and SDK (as under Commands).

## Tools (`tools/`)

- Testing: `check.sh`, `e2e.py`, `screenshot.py`, `seed.py`, `webhook_e2e.py`, `bot_e2e.py` with
  `bots/`, `apps_ui.py`, `discord_e2e.py`, `sfu_call.py`, `sfu_guest.py`, `call_quality.py` and
  `share_quality.py` with `netsim/`, `call_layout_audit.py`, `text_layout_audit.py`, `ios_sim.py` with
  `ios/`, `desktop_idle.py` with `desktop_idle/`, `check_frame_crypto.mjs` with
  `frame_crypto_check.cc`, `privacy_check.py`, and the unittest suites `test_build_pipeline.py`
  (`make build` in isolation, deploying nothing), `test_maintenance.py` and `test_publish_desktop.py`.
- i18n: `i18n_check.py`, `i18n_keys_used.py`, `i18n_apply.py` (write or merge a language's values),
  `i18n_extract.py` (pull hardcoded English into keys); `android/tools/add_strings.py`.
- Releases: `android_release.py`, `desktop_release.py`, `mac_package.py`, `publish_desktop.py`,
  `licenses.py` (license texts in `licenses/`).
- Assets: `twemoji_font.py`, `twemoji_font_check.py`, `twemoji_sprites.py`, `trace_wordmark.py`,
  `home_shots.py` and `home_gifs.py` (the front page's pictures and loops);
  `android/tools/name_fonts.py` (name style fonts for Android). `requirements.txt` needs Brotli 1.2.1 or
  newer for the WOFF tooling.
- Video: `trailer.py` films raw footage of the app with a real-size macOS pointer that moves as a hand
  does. `advert.py` films test1 and test2 in a lived-in seed world (`seed_world`) with `trailer.py`'s
  recorder, logs each take's moments and their places on screen (`tk.mark(label, focus)`), measures a
  song with `advert_edit.py` (librosa; `--bpm` corrects a misheard tempo) and cuts the takes to its
  beats, drops and piano notes, with cameras keyframed in `Cut.cam`. Remotion draws the cut
  (`tools/advert-video`, its own npm project, overriding Remotion's pinned Zod with 4.6.7 for its
  collection-validation fix) in Playwright's Chromium, since Remotion's own headless shell does not
  start on the Mac; the thumbnail is drawn on `advert_stage.html`. `--cut` picks the trailer, `fast` or
  a 1080x1920 `short` from phone-layout takes (`film --phones`, saved as `m-<shot>`); a shot gives up
  after `SHOT_LIMIT` seconds.
- Schema: `gen_pg_schema.sh`, `pg_schema.py`.

## Deploying (only when the owner says "deploy")

- A web-only change: `make web`. chatd reads `web/dist` from disk on every request, so it is live at
  once, with no restart.
- A server change: `make server` (it keeps the binary it replaces as `bin/chatd.prev`), then
  `sudo systemctl restart xyz.evilion.chat` (the unit runs `run.sh`). Say so when the deploy runs a
  database migration.
- `make build` builds both, then the Android and desktop apps when they changed (see Android, Releases,
  and Desktop app); nothing is built on another machine and copied over.
- Back up first (`bash backup.sh`, which keeps only the newest set) only when the request asks for it.
- To roll back: `cp bin/chatd.prev bin/chatd` and restart (not across a migration; see Database).
