# CLAUDE.md

Guidance for Claude Code (claude.ai/code) in this repository. Rules first, then commands and testing, then how
each part is built.

Nexa is the end-to-end encrypted chat app at https://nexa.fish (also https://mathtest.lol; the old address
https://chat.evilion.xyz moves browsers on). This repository is all of it: the Go server (`cmd/`,
`internal/`), the React + TypeScript web app (`web/`), the Electron desktop app that runs the web app
(`desktop/`) and the native Android app (`android/`, Kotlin + Jetpack Compose). README.md is the public
overview and `ENCRYPTION.md` the public encryption explanation. The original design spec is
`docs/superpowers/specs/2026-09-11-chat-design.md` (section 6 is the cryptography). First-party code is MIT
licensed (root `LICENSE`, copyright `2026 terrydavisthe2nd / Nexa`); third-party components keep their own
licenses and notices.

## Rules that always apply

### Keep this file current

Every future session learns the codebase from this file, so it must stay true and complete. A change that
makes anything here wrong or incomplete updates it in the same commit: a new, renamed or removed package,
module, route group, gateway event, table, setting, command, tool, test helper, build or release step,
workflow or convention, and every standing rule the owner gives. Fix anything already wrong when you notice
it. Write what you checked in the code, in the style of the rest of the file: plain, compact sentences without
em dashes that say where things live, the conventions, the decisions and what must not break. Leave what the
code already shows (layouts, sizes, animations, step-by-step mechanics) to the code.

### Building is deploying

`web/dist`, `bin/chatd`, `downloads/android` and the desktop releases in `DESKTOP_DIR` are what the live
instance serves, so `npm run build`, `make web`, `make server`, `make build`, `make publish-android`,
`make desktop-release`, `make publish-desktop`, `tools/desktop_release.py` and `tools/android_release.py`
without `--to` publish immediately. Never run them to check a change; use the commands under Commands, which
write nothing live. Never test against port 8090, `data/` or the repo's `chatd.env`. On this server never
start chatd from the repository root either (`make run`, `go run ./cmd/chatd`): it reads the live `chatd.env`
and `./data` and migrates the database before it listens, so an unreleased build would migrate the live
database. Deploy only when the owner says "deploy" (see Deploying).

### Anything touching UI goes through the frontend-design skill, in full. Never cut corners on UI

The owner's rule (2026-11-05): ANYTHING relating to UI, however small, on the web, the desktop app or Android:
a screen, a dialog, a settings section or row, a component, a toast, an empty state, words on screen, an icon,
or any change to layout, spacing, colour, type or motion. Before the first line of such code, invoke the
`frontend-design:frontend-design` skill, then follow all of it. No exceptions, however large the rest of the
task, however small the UI seems, and in autonomous runs too.

Loading the skill is not following it. Every step is required:

1. Write the whole plan: colour, type, layout with wireframes and alignment, and principles. Working inside
   Nexa's design means naming the actual tokens and their values, not saying "existing tokens".
2. Review the plan against the brief, and say what changed and why.
3. Build it.
4. Critique it from real screenshots at both sizes, and fix what they show.

A few lines of plan, a skipped review or no screenshots is cutting corners, and the UI is not done.
`android/DESIGN.md` is this process applied to Android; read it too before Android UI work.

### Web and mobile: both implemented, both tested

Every feature and change is implemented properly on both the web app and the Android app, and the web at phone
width and the Android app look and behave as alike as possible (layout, sizes, colours, words, order of
things). A phone improvement made on one side is brought to the other (`android/DESIGN.md` section 7 keeps the
list). Plugins and the homepage demo are web only. Nothing is done until it has been seen working (how, under
Testing):

1. Web: on a throwaway server, drive it in a headless browser (Playwright) at 1366x768 and 411x914 (the
   emulator's screen in CSS pixels) and look at the screenshots.
2. Android: build the debug app against the same server and use every screen touched in the emulator, in Dark,
   Light and one preset.
3. Put the phone-size web screenshots next to the emulator's and fix whichever side is off.

Passing unit tests do not replace looking at the screens.

### Android updates go over the air first

Ship Android changes over the air whenever possible, and push a new APK only when OTA cannot carry the change.
OTA content packs (`fish.nexa.data.Ota`) carry everything the build copies from the web except Twemoji:
translations, What's New, the Terms, Privacy Policy and their version, sounds and seasonal pictures. Anything
else (Kotlin, Android resources, Gradle, native code, Twemoji) needs an APK; Android cannot load new compiled
code into the installed app. So prefer fixes that live in that content, keep Android reading such content
through `Ota.open`, `Ota.uri` or `Ota.setDataSource` (never `assets` directly), and do not mix content into a
code change without need. `make build` picks the route by itself (see Android, Releases).

### Build what was asked

Build the literal request. "Custom" packs or themes mean letting people make their own, not adding built-in
ones. If an extra seems worth having, suggest it instead of shipping it.

### Tests earn their place

The owner's rule (2026-11-01): keep a test in the suite only when what it guards is easy to break and costly
when it breaks: crypto and keys, sign-in and sessions, permissions and moderation gates, rate limits and
captchas, privacy (what is sent, stored or shown), migrations and data loss, parsers and formats another
client must match (the vectors), and a bug that already happened. Do not commit tests for static rendering,
copy, layout, trivial helpers or what the type checker already holds; check those once while working (a
scratch test, a screenshot) and leave them out of the suite. Keep each kept test small and quick: a few hard
cases, not a grid of every combination. Adding a test is a choice to justify, not a step every change takes.

### Motion answers what was done, and nothing blinks

The owner's rule (2026-11-03): motion belongs to the thing someone acted on (a chosen tab's pill sliding, a
chevron turning, what a category or expander brings back unfolding, a message or reaction arriving, a dialog,
sheet or toast leaving). Never fade or dim a whole page, list or pane when someone switches tabs,
conversations, servers or settings pages: it read as the app blinking. What replaces a view appears at once,
or slides in already partly visible. Everything stops under Reduced motion. Check new motion frame by frame
before calling it done (Testing, Motion).

### When the work is done

1. Run the checks (`tools/check.sh` covers most) and the web and Android testing above, and update this file
   if the change touched anything it describes.
2. Commit on `main`; push (`git push origin main`) only when the owner says to. The owner also works on
   another machine and pulls from GitHub, so say what is committed and not yet pushed. Never commit
   `chatd.env`, tokens, private keys, signing passwords, personal screenshots or handoff transcripts. Android
   signing is private local configuration under `android/signing/` (see its README): keep a private backup and
   never replace or regenerate an existing release key. `nexa` is the canonical source repository; keep
   deployment settings and signing material out of Git rather than in a separate private source branch. Run
   `python3 tools/privacy_check.py` before committing.
3. Before every push, run both history checks as separate commands and push only after both have printed a
   pass ("Repository privacy checks passed (all reachable history)" and Gitleaks' "no leaks found"); never
   chain the push after them with `&&` through a pipe (`| tail` hides a failure) or push when either is
   missing or fails:

   ```
   python3 tools/privacy_check.py --history
   gitleaks git --redact --log-opts="--all --full-history" .
   ```

   On the build server Gitleaks 8.30.1 is `~/.local/bin/gitleaks`; if it is missing, reinstall it with
   `GOBIN=$HOME/.local/bin go install github.com/zricethezav/gitleaks/v8@v8.30.1` rather than skipping the
   scan (`docs/REPOSITORY.md` has the workflow).
4. After a round of app changes, leave a throwaway server running the new build and give the owner its
   address, with accounts to sign in with. WebCrypto needs https or localhost, so from another machine it is
   reached through an SSH tunnel to localhost, not the LAN address, or through the dev instance (Testing, The
   dev instance) when the owner asks for it. Do not deploy until they say "deploy".

## Every major feature gets a What's New entry

A change that adds a major feature, redesigns something or changes behaviour people will notice adds an entry
to the What's New popup in the same change. People see the popup once, on the first open after an update.
Small fixes and polish need none.

- The list is `RELEASES` in `web/src/lib/whatsNew.ts`, newest first. Add a release at the top whose `id` is
  the deploy date (`"2026-09-26"`, then `"2026-09-26.2"` for a second that day), or add items to the newest
  release if it has not been deployed yet.
- An item is a lucide-react icon, a title and one or two plain sentences saying what people can do now and
  where to find it. Title and body are i18n keys named after the release id without dashes and dots
  (`whatsNew.r20260926.<name>Title` and `...Body`), translated into all twelve locales.
- Keep every item in the shape `{ icon: Name, title: "...", body: "..." }` (that order, double quotes):
  Android reads this file with a regex (`fish.nexa.ui.WhatsNew`) and silently skips anything else.
- The popup, `web/src/components/shell/WhatsNewDialog.tsx`, shows the releases an account has not seen
  (tracked in its synced settings), skipping releases older than the account.

## Writing UI text

- The product is "Nexa" in prose and labels.
- Labels are Title Case: buttons, menu items, tabs, navigation, dialog and page titles, headings and column
  headers ("Add Friends", "Create Group", "Mark as Read", "Add to Group"), short articles, conjunctions and
  prepositions lowercase unless first or last. English only; other locales follow their own capitalization.
  Checkbox and toggle labels use sentence case ("Hide their messages completely").
- Descriptions, hints, toasts and errors are short, plain sentences that sound like a person. Avoid em dashes;
  use a comma, a full stop or parentheses.
- Never describe the owner in the third person or sell Nexa as run by one person (app, home page, Terms).
  Contact details read "Contact the owner (nora) here: ...".
- Describe Nexa on its own terms and never compare it to Discord. Name Discord only where a feature really
  talks to it (the desktop Rich Presence relay, Discord activity sync, Discord template import).
- Never hardcode user-visible English. The web uses `t("key")` / `<Tr>` from `@/i18n`; Android uses `t("key")`
  on the same catalogues (its own keys start with `android.`). Add every new key to all twelve files in
  `web/src/i18n/locales/` with real translations (`python3 android/tools/add_strings.py strings.json` writes a
  batch into all twelve, in order); `python3 tools/i18n_check.py` must pass.
- Text about what Nexa stores or sends (Terms, privacy policy, settings) is checked against the code that runs
  (handlers, the SQL in `internal/store`, the client crypto), not comments. Say "not end-to-end encrypted"
  rather than "not encrypted" when the server seals something itself.

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

`make test` runs the Go suite with `-race` plus the web and desktop suites. `internal/db`'s Postgres tests
start an embedded Postgres, downloaded the first time (`SKIP_PG_TESTS=1` skips them). For hot reload,
`cd web && VITE_API_TARGET=http://127.0.0.1:$PORT npm run dev` serves the web app on :5173; without
`VITE_API_TARGET` it proxies to :8090, the live server.

Building without deploying, into a scratch directory `$S`:

```
(cd web && npx vite build --outDir $S/dist --emptyOutDir)
go build -o $S/chatd ./cmd/chatd
```

Android (publishes nothing). On the deployment server the JDK and SDK live in `~/android`, not on the PATH, so
set them in every command:

```
export JAVA_HOME=$HOME/android/jdk ANDROID_HOME=$HOME/android/sdk
(cd android && ./gradlew :app:assembleDebug)   # -PserverUrl=http://127.0.0.1:$PORT points it at a local chatd
(cd android && ./gradlew :app:testDebugUnitTest)
```

On the Mac, Homebrew's `android-commandlinetools` holds the SDK, NDK and emulator; the same Gradle commands
take `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` and
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

Work that goes with certain changes:

- Dependencies added or upgraded in any build: `make licenses` rewrites `web/src/data/licenses.json`
  (Settings, Licenses); `android/tools/android_licenses.py` does Android's (steps in its header).
- The web's markdown, search, themes, formatting editor or webhook messages changed: regenerate the Android
  test vectors (`android/tools/*_vectors.ts`) and make the Kotlin port pass them. URL oracle entries depend on
  Node/ICU: check a changed expectation against current Chromium too before updating it, and never weaken host
  checks just to match a stale vector.
- lucide-react upgraded: `python3 android/tools/lucide_assets.py`. emojibase-data or `@twemoji/svg` upgraded:
  `node android/tools/emoji_assets.mjs`, `tools/twemoji_sprites.py`, `tools/twemoji_font.py`, then
  `tools/twemoji_font_check.py`.

## Testing

### A throwaway server

chatd reads `chatd.env` from its working directory, and the repo's copy is the live configuration (live keys
and bot tokens; `./data` is the live database). So run the scratch build from its own directory with its
settings in the environment (`$S` an absolute path, `$PORT` anything but 8090):

```
(cd web && npx vite build --outDir $S/dist --emptyOutDir) && go build -o $S/chatd ./cmd/chatd
(cd $S && LISTEN=127.0.0.1:$PORT PUBLIC_URL=http://127.0.0.1:$PORT DATA_DIR=$S/data \
  WEB_DIST=$S/dist EGRESS_PROXY=off ./chatd)        # in the background
tools/.venv/bin/python tools/seed.py http://127.0.0.1:$PORT
```

`seed.py` registers alice and bob (password `correct horse battery staple`), makes a server whose #general bob
joins, posts one message of every kind of formatting and prints the ids. Registration is open by default, the
first account becomes the owner (staff rank Developer, the oldest), and captchas, SFU calls, GIF search and
the other keyed services stay off without their keys.

To try attachments in R2, start the scratch chatd with the test bucket's settings in its environment
(`set -a; . ~/.config/nexa/r2-test.env; set +a` first; the file holds `R2_*` for `nexa-media-test`, whose
token reaches no other bucket). Never point a test at the live bucket, `nexa-media`. Uploads stream straight
there; the mover takes held files left on disk a minute old every half minute. Go tests use
`objstore/memstore`, an in-memory bucket that refuses short streams as R2 does. In Playwright, clear
`e2e.Session`'s CF-Connecting-IP (`ctx.set_extra_http_headers({})`) before downloading from the bucket, since
it follows the link there and asks for a preflight no real browser makes, and delete
`window.showSaveFilePicker` in headless Chromium, which never answers it.

### The dev instance (dev.nexa.fish)

When the owner asks for the dev site, run the new build at https://dev.nexa.fish: the tunnel sends it to
127.0.0.1:6969. `make run-dev` builds into `web/dist-dev` and `bin/chatd_dev` (never `web/dist` or
`bin/chatd`, so nothing live changes) and starts it from the repository root with `DATA_DIR=./data-dev` (the
one sanctioned exception to never starting chatd from the root: it reads `chatd.env` but never `./data`). It
runs in the foreground, so start it in the background; stop it with `pkill -f bin/chatd_dev`.

- `chatd.env`'s real captcha keys fail there unless dev.nexa.fish is on the Turnstile widget's hostnames, so
  every captcha says "The captcha check failed". Run it with Cloudflare's test keys in the environment, which
  win over `chatd.env` and never show a challenge. An empty variable does not override the file, so `/instance`
  still names hCaptcha, which is harmless: current apps read `turnstile` first.

  ```
  make web-dev server-dev
  TURNSTILE_SITE_KEY=1x00000000000000000000AA TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA \
    HCAPTCHA_SITE_KEY= HCAPTCHA_SECRET_KEY= \
    LISTEN=127.0.0.1:6969 PUBLIC_URL=https://dev.nexa.fish DATA_DIR=./data-dev R2_BUCKET=off \
    DESKTOP_DIR=./data-dev/desktop WEB_DIST=./web/dist-dev ./bin/chatd_dev -env chatd.env      # in the background
  ```

- Desktop clients for it: `python3 tools/desktop_dev_build.py` builds every platform into `~/.cache/nexa-desktop-dev`
  and puts the installers in `data-dev/desktop` (so `DESKTOP_DIR` above serves them at dev.nexa.fish/desktop/ and on
  its download page), publishing nothing live. Its address is fixed when built (`NEXA_BUILD_URL`, `__NEXA_BUILD_URL__`
  in `config.ts`, empty in a release), since an installed app ignores environment overrides. It installs as "Nexa Dev",
  with its own bundle id and profile, beside the real app and never over it. It is not signed with the update key, so
  it never updates itself: install a newer one by hand. Each build's version says when it was made, and it logs what
  game detection did to `test-build.log` in its profile (a release writes no such file). The tool refuses the live
  folder and the live addresses.

- `R2_BUCKET=off` (`make run-dev` sets it too) keeps it out of the live bucket `chatd.env` names: an empty
  variable does not override the file, and its uploads and deletions would land on live keys.
- `data-dev` keeps its accounts between runs and is not the throwaway server's database: alice and bob exist
  there only once seeded. Before handing it over, check
  (`sqlite3 data-dev/chat.db "select username from users"`) and seed what is missing through localhost,
  `tools/.venv/bin/python tools/seed.py http://127.0.0.1:6969` (it registers alice and bob, so it fails if
  they already exist), then give the owner the address and the logins (password
  `correct horse battery staple`).
- It still has `chatd.env`'s other live keys (Realtime calls, Discord sync, GIFs, Stripe), so what it does
  with them is real; test on a throwaway server instead when that matters.

### Web in a headless browser

Playwright runs from `tools/.venv` (setup in `tools/README.md`). Screenshots at both sizes:

```
tools/.venv/bin/python tools/screenshot.py http://127.0.0.1:$PORT $S/shots \
  --login "alice:correct horse battery staple" \
  --paths /channels/@me /channels/<server id>/<channel id> --viewports 1366x768 411x914
```

Widths under 768 get a mobile, touch context, and the app switches to its phone layout below 768 px. For flows
(clicking, typing, two people at once) script Playwright with `tools/e2e.py`'s `Session` (`register`, `login`,
`send`, `open_dm_with`, `shot`, ...) or run `tools/e2e.py` itself (`--flows`, `--serve`), with `E2E_WEB_DIST`
and `E2E_CHATD` set to the scratch build (otherwise it tests the deployed `web/dist` and `bin/chatd`, not your
changes). A new account in a new browser gets the Terms sheet first; `Session` and `screenshot.py --login`
agree to it. Stable selectors are the `data-testid` attributes and aria labels the scripts already use. QR
sign-in long-polls, so scripts load the login page with `domcontentloaded`. iPhone keyboard and viewport
behaviour needs `tools/ios_sim.py` on a Mac (`tools/README.md`).

### Android in the emulator

The AVD is `nexa_test`: a Pixel 7, 1080x2400 at 420 dpi (411x914 for the web comparison). On the Mac the
equivalent AVD is `nexa36`, which takes `app-arm64-v8a-debug.apk` instead of the x86_64 APK.

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

- `-no-snapshot-save` throws away the whole session, installed app included, so install after every boot.
- Debug builds allow plain http only to 127.0.0.1, localhost and 10.0.2.2, hence `serverUrl` on 127.0.0.1 plus
  `adb reverse`.
- Driving it: `$ADB shell uiautomator dump /sdcard/ui.xml && $ADB exec-out cat /sdcard/ui.xml` lists every
  element with its bounds; then `$ADB shell input tap X Y`, `input text` (`%s` for a space) and
  `input keyevent 4` (Back, which also closes the keyboard).
- Before trusting a screenshot, check that `$ADB shell dumpsys window | grep mCurrentFocus` names
  `fish.nexa.debug`. After a cold boot, Play Services' "Sign in with ease" screen or a launcher "isn't
  responding" dialog can sit on top; dismiss it and start the app again.

### Motion

- Web: sample what is on screen every frame with `requestAnimationFrame` from Playwright (computed opacity,
  transform or background per frame) and slow animations with CDP's `Animation.setPlaybackRate` for
  screenshots. A hand-off is right when no frame shows neither the old state nor the new one; a timer started
  with `setTimeout` ignores the slowed rate.
- Android: `adb shell settings put global animator_duration_scale 10` slows Compose animations for
  `screenrecord` (set it back to 1 after). The emulator's software GPU draws blurred layers (an open drawer)
  at about one frame a second, so motion there cannot be judged from a recording: log the animated values with
  a temporary `Log.d` and remove it.

### Other end-to-end runs

Each starts its own throwaway chatd. Whatever a test starts must make no sound: Playwright's headless Chromium
is muted, the scripts mute Electron and Firefox, and the emulator runs with `-no-audio`.

- `tools/webhook_e2e.py`: every kind of webhook message, at both sizes.
- `tools/bot_e2e.py`: the discord.py, discord.js and discordgo test bots in `tools/bots/` (`BOT_E2E_PYTHON`,
  `BOT_E2E_NODE_MODULES`, `BOT_E2E_GOPATH` say where each library is; `--only dpy` picks one).
- `tools/apps_ui.py`: every app screen at both sizes into `tools/out/apps-ui`; `--no-content` tries an app
  without Message Content, `--serve --port N` leaves the server and its discord.py bot running for the
  emulator.
- `tools/discord_e2e.py`: Discord activity sync against the fake Discord in
  `internal/discordsync/fakediscord/cmd/fakediscord`.
- `tools/sfu_call.py`, `tools/sfu_guest.py`: real calls and a guest through Cloudflare Realtime (keys from
  `chatd.env`). sfu_call turns noise suppression off, since the speech gate rightly silences its steady tone.
- `tools/stereo_call.py`: mono, dual mono and stereo changed mid-call, each channel measured apart; Hear
  everything in mono; Use Stereo Voice taken from an honest app and a cheating one (`window.__cheat`); `--sfu`
  through Cloudflare, `--speech` through RNNoise.
- `tools/call_quality.py`: calls over bad networks made by `tools/netsim`.
- `tools/share_quality.py`: a hard-motion share through Cloudflare, timed frame by frame; the sharer is
  Chromium, Firefox or the desktop app's Electron; `--up-kbps`, `--loss`, `--down-loss` shape the links;
  results in `tools/out/share-quality`.
- `tools/desktop_games.py`: game detection in the desktop app's own build, with a made-up Steam install and small
  stand-in programs as games; `--behaviour` adds the fullscreen check, which needs IGDB's keys and the name index.
- `tools/desktop_idle.py`: the desktop app's own build (muted, no tray icon, no system-wide keys, Rich
  Presence off, its own profile) checked to sleep when hidden or in the tray, stay at full speed in a call and
  still notify; `--long 330` adds Chromium's deeper throttling after five minutes.

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
docs/               design spec and plans, STATUS.md, REPOSITORY.md, legal/ (the Terms and Privacy Policy the
                    apps show word for word), plugins.md (the plugin API), custom-css.md and examples/, bots.md
                    (making apps, pointing bot libraries at Nexa), UI_TEXT_*, design/ reference captures
audit/              code audits per area; security-2026-10-26.md and history-2026-10-26.md record current
                    fixes, historical dispositions and limits
run.sh, backup.sh   what the service starts (bin/chatd -env chatd.env); the backup of data/
```

Not in git: `data/` (database, media, server secret), `logs/`, `bin/` (chatd and chatd.prev), `backup/`,
`chatd.env`, `web/dist`, `web/node_modules`, `tools/.venv`, `tools/out`, `.claude/`.

## Server (Go)

`go.mod` requires Go 1.27.3 (for the standard library's certificate and HTTP security fixes); build with that
or newer. `cmd/chatd/main.go` reads `-env` (default `chatd.env`; the process environment wins), loads
`internal/config`, opens and migrates the database (`DATA_DIR/chat.db` or `DATABASE_URL`), makes the stores
and the server secret (`DATA_DIR/server.secret`), wires the gateway hub (`srv.Bcast`: `Wire*`, last
`WireModeration`), starts the background loops (`Start*`, the WARP keeper, the usage meter, `maintenance`) and
serves until SIGINT or SIGTERM.

### Packages (`internal/`)

| Package | What it is |
|---|---|
| `api` | REST handlers, middleware, rate limits, static pages; one file per area |
| `auth` | Argon2id of the client's auth key, bearer tokens (only SHA-256 hashes stored), token middleware, client IP behind the tunnel |
| `automod` | AutoMod matching; `testdata/vectors.json` binds every implementation |
| `botproto` | the bot protocol of Discord's libraries (ids with a fixed offset, intents, opcodes, JSON and ETF, zlib and zstd); knows nothing of the store |
| `cache` | optional Redis: shared rate limits, presence and events between processes |
| `config` | settings from the environment, with defaults and validation |
| `db` | SQLite or Postgres pools (`W` single writer, `R` readers), `Tx`, dialect helpers, embedded forward-only migrations |
| `discordsync` | Discord activity sync (a bot in a small Discord server reads linked members' presence); `fakediscord` for tests |
| `egress` | the SOCKS5 proxy (WARP) for every request a person can point the server at; fails closed |
| `games`, `music`, `books`, `screen` | IGDB, Deezer, Open Library and TMDB for profile widgets; art kept, previews streamed through. `games` also holds the hand-written known game list and builds the hashed name index (Desktop app, Game detection) |
| `gateway` | the WebSocket hub: connections, presence, typing, voice signalling, fan-out |
| `ids` | snowflakes (41 bits of milliseconds, 10 of node, 12 of sequence), decimal strings in JSON |
| `media` | blob storage, upload validation, sized variants, metadata scrub; bucket attachments (`remote.go`, `relay.go`) |
| `objstore` | a small S3 client for the R2 bucket, standard library only; `objstore/memstore` is an in-memory bucket for tests |
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
| `webauthn` | the server's half of passkeys, standard library only; `webauthn/fake` is a software passkey for tests |

### HTTP API (`internal/api`)

- JSON under `/api/v1` with `Authorization: Bearer <token>`. Errors are `{"error": {"code", "message"}}` from
  `E(status, code, message)` and `WriteError`; `WriteJSON` and `ReadJSON(w, r, dst, maxBytes)` do the rest.
  `Handler()` chains `RequestID`, `Recover`, `SecurityHeaders` (a restrictive default CSP that document
  handlers replace with their page policy), `BodyReadDeadline` and `Logging`. `GET /api/health` answers
  `{ok, version}`.
- One file per area, each registering routes with `mux.Handle("METHOD /path", ...)`; route groups are listed
  in `groups.go` (`routeGroups`), where a new group goes. `ChannelAccess`, `ConvForChannel`,
  `ServerChannelPatch` and `CanRotate` on `Server` are hooks `server_glue.go` installs for server channels; DM
  access is built in.
- Wrappers: `authed` (token, the generic per-user limit, the mute/ban gate), `callAuthed` (accounts and
  guests), `guestAuthed`, `userLimited(limiter, h)`, `ipLimited(limiter, h)`. Every limiter is made in
  `NewServer` and named in `limiters()`; one missing from that map is never swept or shared.
  `moderation_gate.go` lists what a muted or banned account may still do; a new signed-in route is closed to
  them until added there.
- Beyond the generic 600 a minute, what a script would turn on people or paid services has its own limit:
  `limReact`, `limMsgAll` (beside per-channel `limMsg`), `limSearch`, `limLookup` (signed-out lookups, per
  address), `limBuild` (things people create), `limForward`, `limReport`, `limTurn`. `limLookup` and
  `limBuild` step up (`stepup.go`): past the free allowance a captcha (action `lookup` or `build`) buys a
  batch, those captchas capped per address and per account; batches are in memory, per process. A server holds
  at most 500 channels and 250 roles (`store.MaxServerChannels`, `MaxServerRoles`, counted in the creating
  transaction).
- Sign-up: two new accounts a day per IPv4 address or IPv6 /64 (`limSignup`, spent only after every other
  check passed, never for the first account), unless a Developer turned it off (instance setting
  `signup_ip_limit`), on top of `limReg`'s ten attempts an hour. `Store.CreateRegisteredUser` decides the
  first account's Developer flag and the `ADMIN_USERNAME` exception inside the inserting transaction under a
  shared bootstrap lock, so concurrent sign-ups cannot each become the first administrator. KDF, login and
  recovery refuse normalized usernames over 32 bytes before lookup or limiter admission. Visitor limits trust
  only a valid `CF-Connecting-IP` from a loopback proxy, never `X-Forwarded-For`; otherwise the socket peer.
- Captchas (`captcha.go`; Cloudflare Turnstile, off until a pair of keys is set):
  `requireCaptcha(w, r, action)` guards what scripts would abuse, every password check and username change
  included. A refusal names the action in `X-Captcha-Action`, and web `api.captchaSolver` and Android
  `Api.captchaSolver` solve it and resend, so a guarded call needs nothing of its own. One captcha shows at a
  time (a token is good for one request); a control whose request may meet one is disabled until it ends. A
  Turnstile token counts only for the action it was rendered with and on one of Nexa's own hostnames (keep the
  widget's hostnames the same; Cloudflare's test secrets skip both checks).
- hCaptcha stays as a fallback for apps that predate the move back to Turnstile (2026-11-01): with
  `HCAPTCHA_*` set, `/instance`'s `captcha` keeps naming `hcaptcha` (old apps read only that) and `turnstile`
  carries Turnstile's key, which current apps read first. Current apps mark every token with
  `X-Captcha-Provider: turnstile`; an unmarked token is checked with hCaptcha, which binds no action, so only
  its hostname is checked. Remove the fallback once old apps are gone. Desktop 1.2.4 and later frame both
  `challenges.cloudflare.com` and `hcaptcha.com` (`isFrameableUrl`).
- Apps and bots (`bot_*.go`; the guide for bot makers is `docs/bots.md`): the portal, OAuth2, interactions,
  and the libraries' REST under `/api/v6` to `/api/v10`, `/bot-gateway` (events translated and filtered by
  intent in `bot_dispatch`) and `/cdn/...`. A bot route with an app equivalent runs that route's code as the
  bot (`callNexa`), so bots follow a person's rules and everyone sees the same events. A bot is a user with
  flag 64 (128 when its app reads every message); an app's id is its bot's. Interactions and app events go to
  the app's own address through the egress proxy, signed with its Ed25519 key. Keep the protocol's
  `interaction_metadata` unchanged; the submitted options for the clients' tooltip live in
  `webhook.interaction`. Voice channels keep protocol type 2 and carry text chat through the normal message
  routes (fetching it also needs Connect).
- Webhooks take Discord's JSON at `/api/webhooks/{wid}/{token}` and the versioned paths Discord's libraries
  build, with `/slack` and `/github` variants. Links in webhook and app messages get server-side previews
  (`unfurlWebhookMessage`).
- `static.go` serves `web/dist` (hashed assets immutable, `index.html` for any other path), its own page heads
  for link previews, and the CSP (plugins load from `blob:`, Turnstile from `challenges.cloudflare.com`).
- Vanity links (`vanity.go`) are invite rows with `invites.vanity` = 1, at most one per server (a partial
  unique index): 3 to 32 of a-z, 0-9 and hyphens with a letter or number at each end, minus `reservedVanity`.
  One never expires or runs out, is found in any case by `InviteByCodeAny` after an exact match, and stays out
  of the invite list, Revoke All and account purges. Joins through it have no inviter.
- Group chat activity is posted as `system_*` messages (none for a rename to the same name or removing an
  absent icon). Leaving takes `?silent=true` to omit the leave message; removing someone else always posts
  one. Subchats (`subchat_handlers`) can require a password for protected operations, deletion included.

### Gateway (`internal/gateway`, `web/src/gateway`)

- One WebSocket at `GET /gateway`; the client sends `{op, d}`, the server `{t, s, d}`. The first frame is
  `identify` with the token, answered by `ready` (its `now` keeps the device's clock offset: web
  `lib/serverClock.ts`, Android `data/ServerClock`).
- Client ops: `identify`, `heartbeat` (every 25 s), `presence`, `typing`, `voice_state`, `voice_signal`,
  `subscribe_members`, `activities`, `away`, `note_cursor`. A connection may send 20 ops a second, in bursts
  of
  100. Close codes: 4001 session revoked (the client signs out), 4003 banned, 4008 rate limited, 4009 too many
       connections. Both gateways reserve unidentified connections from the hub's admission budget before
       upgrading; the bot gateway cannot bypass the main gateway's limits.
- Events are the `Ev*` constants in `protocol.go`. Handlers emit through the `Broadcaster` interface in
  `api/router.go`; the hub sends an event only to members allowed to see it (`store/permcache.go`) and encodes
  it once. A new event touches the constant, the emit, its type in `web/src/gateway/events.ts`, its handling
  in `web/src/gateway/dispatch.ts`, and the same on Android (`fish.nexa.data.Session`). An app's answer only
  one person sees is a `message_create` with flag 64 to that person alone, removed by a `message_delete`
  marked `ephemeral`. A bot's gateway session is an ordinary connection on the hub (`gateway.External`) whose
  frames go to `bot_dispatch`.
- What follows the app without being part of it (plugins, the search index, notifications) listens through
  `dispatchHooks` in `dispatch.ts`, each hook in a try/catch (`each`) so a throwing listener stops nothing.
  `dispatchHooks.onEvent` takes the `AppEvent`s of `gateway/appEvents.ts`. Servers, relationships and typing
  are watched through their stores (they change without events or arrive twice); `ready` and sign-out fill and
  empty the stores inside `quietly`, and after a reconnect `reportMissed` tells what changed. Listeners cannot
  yet keep a deleted message on screen.

### Database (`internal/db`, `internal/store`)

- Every query lives in `internal/store`; handlers never touch `database/sql`. Queries are written once with
  `?` placeholders that `internal/db` rebinds for Postgres; `db.Dialect` covers the differences. Ids are
  snowflakes, times Unix milliseconds, booleans 0 and 1, JSON kept as text.
- SQLite is live and Postgres must keep working: a new migration goes in both `internal/db/migrations` and
  `internal/db/migrations-postgres` with the same number (Postgres starts at `0013_init.sql`). Migrations are
  embedded, forward only, one transaction each; each also adds its undo to the top of `undoCommunities` in
  `internal/db/undo_test.go`; `tools/gen_pg_schema.sh` prints the Postgres schema the SQLite migrations add up
  to, to diff against. chatd refuses to start on a database a newer build migrated, so a release with a
  migration cannot roll back to `bin/chatd.prev`.
- Tables by area: accounts (`users`, `sessions`, `user_ips`, `user_settings`, `user_mfa`, `mfa_backup_codes`,
  `passkeys`, `user_sounds`, `username_holds`), people (`relationships`), conversations (`channels` for every
  kind, `dm_participants`, `channel_overwrites`, `channel_unlocks`, `thread_members`, `channel_follows`,
  `crossposts`), messages (`messages` with `ciphertext`, and `plain` for webhook and system text;
  `attachments`, `reactions`, `pins`, `read_states`, `polls`, `poll_votes`), keys (`key_epochs`, `key_grants`,
  `key_flags`), servers (`servers`, `server_members`, `roles`, `member_roles`, `invites`, `bans`, `audit_log`,
  `automod_rules`, `automod_events`), plans (`billing`, `server_boosts`, `plan_gifts`, `gift_links`,
  `users.plan`, `servers.boosts`), expressions (`emojis`, `sticker_packs`, `stickers`, `sticker_installs`,
  `recent_stickers`, `blocked_images`), games people share (`game_submissions`, `known_games`), files (`blobs`, `uploads`, `remote_blobs`, `storage_usage`), calls
  (`guest_links`, `guests`, `realtime_usage`, `realtime_measurements`, `realtime_sessions`, `call_usage`,
  `turn_credentials`), integrations (`webhooks`, `discord_links`), notes (`notes`, `note_members`,
  `note_invites`, `note_links`, `note_files`, `note_versions`, `note_version_files`, `note_keys`,
  `note_updates`, `note_uploads`), Nexa and moderation (`mod_cases`, `mod_messages`, `mod_message_files`,
  `mod_restrictions`, `mod_announcements`, `mod_log`, `mod_reads`, `nexa_reads`, `bug_reports`,
  `bug_attachments`, `bug_messages`, `bug_message_files`, `client_errors`), reports (`safety_reports`,
  `safety_report_events`, `safety_report_files`, `safety_report_snapshot_blobs`), apps (`applications`,
  `application_emojis`, `application_commands`, `application_command_permissions`,
  `application_guild_installs`, `application_user_installs`, `oauth_codes`, `oauth_tokens` (gone with its
  refresh token, 90 days unused), `interactions` and `ephemeral_messages` (15 minutes), `ephemeral_files`,
  `interaction_files` (a day), `bot_copies`, `roles.managed_by`), badges (`badge_defs`, `user_badges`,
  `badge_prefs`), the instance (`instance_settings`).
- Channel types: `dm`, `group_dm`, `subchat`, `text`, `voice`, `category`, `announcement`, `forum`, `media`,
  `link`, `thread`, `private_thread`. `store.IsForum` is forum or media (posts only), `store.HoldsNoMessages`
  is category or link (no messages, invites, read states, mentions or own key). A channel's `key_conv_id`
  names the conversation whose keys encrypt it: itself for DMs and private channels, the server otherwise.

### Permissions

`internal/perms` is Discord's algorithm with Discord's bit values, mirrored by `web/src/lib/perms.ts` (BigInt,
decimal strings on the wire) and Android's `fish.nexa.data.Perms`. Server channel access goes through the
hooks `server_glue.go` installs, answered from `store/permcache.go`. Each permission is held as Discord holds
it:

- Categories sync as Discord's do (`api/category_sync.go`): a channel is synced when its overwrites equal its
  category's (`sameOverwrites`; nothing else records it). A category overwrite change is copied to every
  synced channel, and a channel moved into another category takes the new overwrites when it was synced (or
  had none outside a category) and the mover has Manage Roles there. A channel that becomes hidden from
  @everyone this way is flagged for its own key (`markIfPrivate`), and create and sync answer with
  `key_actions` so the actor's app makes it at once.
- Editing a channel's overwrites needs Manage Roles in it (`channelManageCtx`), not Manage Channels.
- View Server as Role is a preview on this device only (web `stores/viewAs.ts`, Android
  `ServersStore.viewAs`); the server still sends every channel. Duplicate Role creates a role from the plain
  fields, then patches the gradient colours and emoji icon (create ignores them).
- Embed Links: previews are made and sealed by the sender's app, so the server sets Suppress Embeds (flag 4)
  on any new or edited message whose author lacks Embed Links there (`embedFlags`) and refuses to clear it
  while they lack it; apps hide flagged previews and composers fetch none there.
- Add Reactions is needed only for a new reaction; every reaction needs Read Message History, without which
  history answers an empty list and only live messages are seen.
- Video (Stream): without it a voice state never says `video` or `streaming`, and the SFU refuses to publish
  or pull a camera or share (`mayStream` in `sfu_handlers.go`, asked at every request); the apps refuse to
  start one and stop one that loses it (web `voice/callPerms.ts`, Android `voice/CallPerms`). The mesh cannot
  be held by the server.
- What the server cannot see, every reader's app holds: Use Voice Activity (push to talk without it), Use
  External Emoji and Stickers, drawn as `:name:` when the author lacks them (web `lib/authorPerms.ts`, Android
  `data/AuthorPerms`; an author whose roles are not loaded counts as allowed), and Use Custom Typing
  Indicators (1 << 60, Nexa's own bit; migration 0045 gave every existing @everyone it; without it the typing
  bar shows that person with the plain dots).
- Use Stereo Voice (1 << 61, Nexa's own; migration 0051 gave every existing @everyone it, and Discord
  templates keep it): call frames are sealed, so the server cannot see or stop what is sent. It works out who
  lacks the permission (`voice.State.MonoOnly`) and says so in their voice state as `mono_only`. Every
  listener's app then decodes them in one channel, and the sender's own app sends mono (`allowedChannels`). A
  patched sender only wastes its own bandwidth; only an older or patched listener can hear it in stereo.
- Use Application Commands (1 << 31) and Use External Apps (1 << 50) are for apps, and @everyone has both. Use
  Soundboard (1 << 42) stays in the maths for the bot API and Discord templates, but Nexa has no soundboard
  and the role editors do not offer it.

### Media and uploads (`internal/media`)

- Blobs are content-addressed under `DATA_DIR/media` (table `blobs`). Public kinds (`avatar`, `banner`,
  `icon`, `emoji`, `sticker`, `tag`, `widget`, `typing`) are served immutable at `/media/{kind}/{id}` (the
  kind must match the stored one; a picture staff blocked is served to nobody), with sized variants made once;
  `?still=1` gives the first frame. JPEG crops and variants apply all eight Exif orientations first
  (`orientation.go`, Android `media/PhotoOrientation`), and sized avatar, banner and icon URLs carry `&v=3` to
  drop stale cached copies. The synced `animate*` settings (on by default, separate from Reduce Motion) make
  the URL helpers ask for `?still=1`.
- Attachments are opaque encrypted blobs served only to the conversation's people; bug report and case files
  are private to their people and staff; webhook files are plain. The server scrubs metadata from pictures and
  videos it keeps readable (`scrub.go`) and the apps do before encrypting (web `lib/scrubMetadata.ts`, Android
  `MetadataScrub`), all checked against `internal/media/testdata/scrub`.
- A channel's uploaded picture lives in `channels.icon_id`; if both a picture and a glyph are set the picture
  wins (`ChannelGlyph` on both clients).
- With an R2 bucket (`R2_*`), end-to-end encrypted attachments (kind `attachment`: message and note files,
  sound pack recordings) live there, not on this disk. Uploads pass through this server and are never written
  here (the owner's decision, 2026-11-04: the server disk is a slow SATA SSD, and apps get no link to write to
  the bucket, since a link cannot be taken back). A single-request upload with a Content-Length streams
  straight to the bucket (`putEncryptedRemote`, checked against R2's MD5 ETag, then a HEAD, then
  `remote_blobs` and the blob). Files that did land on disk are moved by the mover (`StartStorage` in
  `api/storage_budget.go`), which records them in `remote_blobs` and only then deletes the file here. Reads
  prefer the disk. Pictures, private (bug, case, report) and webhook files never move, so the bucket holds
  only ciphertext. Set on the bucket, not in this repository: its CORS allows GET and HEAD with `range` from
  Nexa's origins (`nexa-media`; the test bucket `nexa-media-test` allows any origin), and a lifecycle rule
  aborts multipart uploads left unfinished.
- Downloads of a file in the bucket (`serveFromBucket` in `media/remote.go`) get a presigned link good for two
  minutes, as JSON when asked with `X-Nexa-Link: 1` (the web, which fetches it without its token, since R2
  refuses a request carrying both and WebKit keeps the Authorization header across a redirect), otherwise as a
  302 (Android, older apps). A link can be replayed until it lapses, so `api/storage_links.go` caps what reads
  may cost in a month: past a fifth of the monthly limit (`readCapShare`; `readCapNoLimit`, $10, without one)
  links stop for the rest of the month and the bytes stream through here, paced per account by `limReads`.
  Reads alone therefore never stop uploads, which stop only at the whole limit. An account asking for links
  faster than `limLinks` gets streamed bytes too. The bot CDN and reported-file reads stream from the bucket.
- Relayed uploads (`api/upload_relay.go`, `media/relay.go`): an upload of at least `relayMinBytes` with
  `relay: true` opens a multipart upload at the file's final key, and `PUT /uploads/{id}/parts/{n}` takes a
  part only from the upload's owner, for a number it has, at its exact Content-Length, at most `maxPartSends`
  times, at `limParts`' pace, streaming it on without touching disk. Finish refuses any part of the wrong size
  or number, joins them, checks the whole size with a HEAD and only then writes `remote_blobs` and the blob;
  failing, damaged, cancelled and idle uploads are aborted (`AbortRelay`). Admission is the same as for pieces
  except that relayed uploads need no disk room. Clients: web `sendRelayed` in `lib/attachments.ts`, Android
  `relayed` in `data/Send.kt`.
- R2 has no spending cap, so Settings, Nexa Admin, File Storage sets a monthly limit (instance setting
  `storage_budget` in US cents). `storageCostCents` prices as R2 bills (checked 2026-11-04) what the bucket
  holds plus what waits here and is on its way, plus this month's requests. An upload headed for the bucket
  that would pass it answers 507 `storage_budget`, and the mover leaves files on disk rather than pass it.
  With an analytics token (`R2_ANALYTICS_TOKEN`, else `REALTIME_ANALYTICS_TOKEN`), `measureStorage` uses
  Cloudflare's measured numbers and adds this server's own count since; the size used is the larger of the
  measured one and `remote_blobs`.
- Small files go up in one request (`POST /channels/{id}/attachments`), big ones relayed or, from apps that do
  not ask for that or without a bucket, in pieces through `/api/v1/uploads` (Cloudflare caps a request at 100
  MB) within disk headroom and per-account caps (`roomForUpload`). `reserveUpload` serializes admission and
  counts bodies still arriving; temporary chunks have their own disk reservations, so stalled retries cannot
  pile up copies of an offset. Reservations assume the one chatd process the deployment uses.
- Clients send the next piece as soon as the current piece's bytes have left, before its answer: that round
  trip left the line idle at every 32 MB boundary (2026-11-04). The server holds a piece up to one ahead
  (`aheadWait`) until its predecessor lands, so pieces still land in order at the confirmed offset. Downloads
  read ahead at most one encrypted chunk; authentication and the expected size still decide what is saved.
- Saving asks for a location: Android CreateDocument, the desktop app's Save As, web `lib/saveFile.ts` with
  showSaveFilePicker before fetching, the system share sheet in mobile browsers, else the browser's own
  behaviour. Cancelling saves nothing; failed writes remove the partial file.
- `gif_budget.go` walks a GIF's compressed blocks to check frames and canvas work before any full decode
  (checking after `gif.DecodeAll` is too late for memory).

### Outbound requests

- Everything a person can make the server fetch goes through `internal/egress` (WARP's SOCKS5 proxy,
  `EGRESS_PROXY=warp|off|socks5://...`): link previews (`unfurl`, asked for by the sender's app, which seals
  the preview in the message), the media proxy (only addresses the server signed; `POST /proxy/refresh`
  re-signs old ones), the players, music cards' details, IGDB, Deezer, Discord's Rich Presence apps and
  Discord templates. Discord's gateway, Cloudflare Realtime and the tunnel go out directly.
- Music links (`unfurl/music.go`) carry a `music` card, held by `sanitizeMusic` in web `crypto/message.ts` and
  Android `MessageCrypto`, drawn by web `chat/MusicEmbed.tsx` and Android `ui/media/MusicEmbed.kt`. Each
  service's terms decide how it plays: Deezer's and Apple's thirty-second previews play in the app's own
  preview player (web `profile/previews.ts`, Android `SongPreviews`; one at a time; previews take mute only
  from their own settings); a Spotify track's preview comes from the same recording on Deezer, else Apple
  Music (`matchPreview`, the card names it with `preview_from`); a SoundCloud track plays through SoundCloud's
  widget kept out of sight (web `profile/soundcloudWidget.ts`, Android `ui/profile/SoundCloudWidget.kt`);
  other Spotify and SoundCloud links open their own player, which stops any preview. Bandcamp and Tidal stay
  ordinary link cards.
- Other sites' players are served through this server by `api/frame_proxy.go`: YouTube at `/yt/`, Spotify at
  `/sp/`, SoundCloud at `/sc/`. Tokens are signed per site, so one site's token opens no other. The apps frame
  them sandboxed without `allow-same-origin` (Android in a WebView whose page holds the same sandboxed frame).
- A Tenor page previews as type `gif` of the GIF its `og:image` names (`unfurl/tenor.go`).
- Guarded fetches allow only ports 80 (HTTP) and 443 (HTTPS), checked again when dialing. The public-address
  guard also checks IPv4 embedded in NAT64 and 6to4 addresses and refuses local-use translation, Teredo and
  IPv4-compatible ranges.
- Media links are recognised by response type or a bounded file header, never a filename. SVG previews are
  images: the proxy keeps its restrictive CSP sandbox and serves them as downloads on direct navigation, and
  the clients draw them only as images. Every file a preview loads goes through the signed media proxy
  (`unfurl.PlayableMediaType` keeps its accepted types aligned), so no reader fetches the original.
  Mislabelled files go by their bytes (`unfurl.ImageAlias`, `NeedsSniff`/`SniffMedia`; SVG never by sniffing).
- The GIF picker sends the first Klipy GIF from medium down that fits under `unfurl.MaxMediaBytes`
  (`gifToSend`), else the animated WebP. Picture previews whose proxy fetch fails try again twice, then show
  Retry (web `useRetriedImage`, Android `rememberRetriedImage`).

### Calls

- `internal/voice` tracks who is in each call. Without Realtime keys calls are a peer-to-peer mesh signalled
  over the gateway's `voice_signal`; with `REALTIME_APP_ID`/`REALTIME_APP_SECRET` every call goes through
  Cloudflare's SFU, chatd being the signalling proxy (`/api/v1/voice/sfu/*`) that decides who may pull which
  track. Frames are encrypted in the apps. An SFU call outlives a dropped socket for `voiceGrace`. SFU video
  checks hold a guest to their channel permissions too, ICE restarts included (a guest link is not an
  exception to Video).
- SFU policy (`sfu_handlers.go`, `sfu_policy.go`): mutations serialize per user; a publication is bound to its
  SDP media section and exact mid (no duplicate kinds or mids; an ICE restart reuses its mid); pulling a
  source again reuses its subscription. Mute, deafen, Speak and Video restrictions retire existing provider
  sessions and are rechecked after upstream negotiations and permission changes; voice states carry
  `sfu_generation` so clients rebuild permitted media (older clients may need to rejoin). Camera or share off
  closes the publication.
- Every description the SFU hands a client goes through an `ExtmapHistory` (web `voice/extmaps.ts`, Android
  `voice/ExtmapHistory.kt`, one per connection): Cloudflare reuses a stopped mid with its header extension ids
  renumbered, which WebRTC refuses. Without it, watching a share a second time rebuilt the whole connection.
- Charging (`internal/realtime/meter.go`) follows actual pulled tracks and confirmed provider closures, per
  account and UTC day into `call_usage`. Shares are estimated at the sharer's plan limit (`shareMaxBps`),
  never the declared bitrate. Cloudflare can wait five seconds before answering about a session, so a close
  gets `realtime.CloseTimeout` (20 s), a 410 "disconnected" counts as closed, and `closeSFUSession` retries,
  then stops charging and logs an error. Timed-out closes once stayed charged until a restart: on 2026-10-31
  the meter read 2.3 TB against Cloudflare's real 106 GB and refused calls.
- With `REALTIME_ACCOUNT_ID` and `REALTIME_ANALYTICS_TOKEN`, `StartRealtime` (`api/call_usage.go`) polls
  Cloudflare's GraphQL Analytics (`realtime/analytics.go`; account wide, since SFU and TURN share the 1,000 GB
  allowance) into `realtime_measurements`; the month's usage is that measurement plus the estimate since. The
  meter pauses video near `REALTIME_MONTHLY_GB` and stops calls just short of it. `call_usage` is kept 62
  days, `realtime_sessions` three (Privacy Policy section 3l).
- TURN (`api/turn.go`, `realtime/turn.go`): credentials are recorded by username only (`turn_credentials`),
  and a 15 s sweep revokes those whose account has been out of a call for `turnGrace`. Minting has `limTurn`,
  stops for people not in a call while calls are paused, and cuts an account off for the UTC day past
  `REALTIME_TURN_DAILY_GB` of measured relay.
- A server voice channel has its own chat (web `components/voice/VoiceRoom.tsx`, Android
  `ui/voice/VoiceRoom.kt`). It is the active channel, and marked read, only while shown, and voice channels
  count towards unread state and Mark as Read.
- Call timers count from `call_since` on voice states through the server clock offset, falling back to the
  earliest `joined_at` (web `lib/callTimer.ts`, Android `data/CallTimer`); nothing counts while ringing.
- Voice moderation: `PATCH /servers/{id}/members/{uid}/voice`, checked in the person's current channel (a move
  also needs View Channel on the destination) and against the role hierarchy; guests can be muted or
  disconnected, not moved. Server mute and deafen persist in `server_members.flags` (2 muted, 4 deafened; 1 is
  AutoMod's quarantine) and reapply on every join. `suppress` means the channel denies Speak (`server_mute` is
  set too, for older apps); a moderator's disconnect leaves with `reason: "disconnected"` ("moved" is another
  device taking the call).
- Priority Speaker: holding `priorityKey` sends `priority: true`, kept only where the person has Priority
  Speaker and never for a guest; listeners duck everyone else to `PRIORITY_DUCK`. No key on touch screens, so
  Android only listens.
- Screen shares, as measured with `tools/share_quality.py` (web `voice/media.ts`, `shareQuality.ts`,
  `shareAdapt.ts`; Android `VideoCap`, `ShareAdapt`): the share dialog's mode becomes a `SharePlan` through
  `sharePlan`; older settings map through `shareModeOf` without being rewritten. Sharp shares get the `detail`
  hint and give up frames. Smooth ones get no hint and `ShareAdapter` scales them, because Chromium's `motion`
  hint and `maintain-framerate` shrink a share at the start and never grow it back.
- A watched share stuck behind a lost frame asks for a keyframe (`stallWatch.ts`); Android's WebRTC cannot
  ask. A receiver whose first frame is not a keyframe asks at once from the frame worker (`keyAwaited`).
- Through the SFU a share goes out in H.264 when the sender's hardware encoder makes it and everyone else
  advertises `codecs: ["h264"]`, otherwise VP8. Android sends H.264 whenever its WebRTC can; the web only in a
  Chromium on a Mac whose encoder is power efficient (`sharesInH264`). Windows (driver-dependent H.264),
  Safari and the mesh stay on VP8.
- Through the SFU, cameras and watched shares are pulled only while the call's pictures are on screen (web
  `voice/videoShown.ts`, Android `voice/VideoShown`), and for `VIDEO_HIDDEN_GRACE_MS` after; voices and share
  sound always come in.
- A share's sound is stereo Opus at 128 kbit/s without DTX (`opus.ts`; voices are 48 kbit/s with DTX).
- The call view fits tiles from fractional ResizeObserver sizes rounded down (rounding up clipped the grid);
  `tools/call_layout_audit.py` checks it through the dev-only `web/tests/call-layout.html` fixture.
- Stereo voice (web `voice/channels.ts`): the synced `voiceChannels` is `mono` (the default, and what calls
  always carried, untouched), `dual` or `stereo`; only the person talking chooses it. An encoder takes two
  channels from the applied remote description, so changing it mid-call publishes the voice again through the
  SFU (`setVoiceStereo`) or renegotiates peer to peer. Peer to peer, `stereo=1` stays local and the
  description sent is the one as made, since an older app would take `stereo=1` from it. Chromium's echo
  cancellation, noise suppression and automatic gain each make a microphone one channel in both ears, so
  stereo opens it without them and only RNNoise can run. Stereo always goes through the WebAudio graph
  (`sentStream`): the unprocessed track sent directly left about one join in fifteen with Cloudflare answering
  `not_found_track_error` to every pull (0 of 120 through the graph; why is not known), so do not take that
  stage out. Voice messages always record in mono. A voice packet with its copies stays within
  `MAX_PACKET_WITH_COPIES`. The meter charges a voice at its declared bitrate between `AudioBps` and
  `StreamAudioBps` (`ClampVoiceBps`), and at `AudioBps` for someone `MonoOnly`. No plan limits any of this.
- Mono on the listener's side: a section whose local description says `stereo=0` is decoded in one channel
  whatever is sent (`tunedLocal`'s `mono`, `Sdp.tunedLocal`), which no sender can get round. Hear everything
  in mono (`hearMono`) and `mono_only` use it. Only a new exchange changes a decoder, so `syncNow` drops and
  pulls the track again, and peer to peer renegotiates.
- Android stereo capture: `AudioRecord` opens one way for an audio device module's life, so stereo voice has
  its own factory (`WebRtc.useStereoInput`, kept for the process). The capture post-processing hook only ever
  gets the first channel, so in two-channel capture `Denoise.process` does nothing and `StereoInput` does
  RNNoise, the input volume, the level and the gate on both. This must not break: gating only in the hook left
  the right channel open while muted. A one-channel encoder fed from two-channel capture came out garbled, so
  a call that turns stereo off, or loses the permission, stays in two identical channels until it ends.
  Whether a phone's own microphones or its voice-call audio path keep stereo is the hardware's business and
  untested; the emulator only proves what reaches `AudioRecord`'s buffer and what the speakers are handed
  (debug hooks `TONE` mode 4, `MUTE`, `LISTEN`). A native call on a disposed `PeerConnection` crashes past
  `runCatching`, so after any suspension check `closed` (or `live(of)`) first.
- Noise suppression is Xiph RNNoise commit `70f1d256acd4b34a572f999a05c87bf00b67730d` on both (web
  `@shiguredo/rnnoise-wasm` 2025.1.5; native CMake fetches source and weights under SHA-256), gated by
  `voice/speechGate.ts` (native `SpeechGate`). A failed worklet falls back to browser suppression. Echo
  cancellation runs before it (Android: WebRTC's software AEC3, not the vendor effect); the two stay
  independently switchable.

### Signing in: passkeys, QR codes, more than one account

- Passkeys (`api/passkey_handlers.go`, `internal/webauthn`; web `lib/passkeys.ts`, Android `data/Passkeys`,
  from Android 14 only, since the app carries no Play services) sign in and open the messages in one step: the
  passkey's PRF output (`nexa passkey prf v1`, HKDF `nexa passkey identity v1`) wraps the identity key, which
  the server returns only to a sign-in that passkey signed. A passkey without PRF output is refused when added
  (the owner's choice, 2026-10-25), since it could never read a message. Adding one takes the password;
  signing in takes no username and skips the second factor (user verification is required). A passkey belongs
  to the address it was made on (RP ID from the Origin, else PUBLIC_URL's host); Android's origin is the
  published signing certificate's hash, and `/.well-known/assetlinks.json` carries `get_login_creds` for it.
  Answered challenges are kept in memory per process so each signs in once, and a counter that goes back is
  refused.
- QR sign-in (`api/qr_login.go`, web `lib/qrLogin.ts`, Android `data/QrLogin`): a signed-out device makes a
  one-time X25519 key and shows a code linking to `/login/qr#<id>.<public key>`. A signed-in phone sees the
  asking device's user agent, and on Log In seals the identity key to the key in the code, never one the
  server names. The server hands the session over once; requests live in memory for three minutes, per
  process.
- More than one account (web `lib/accounts.ts`, Android `data/Accounts`): up to five signed-in accounts keep
  their tokens in a list and their keys where they always were. Only one runs; switching ends the call and
  connection and reopens the app where that account last was. Add Another Account signs in without touching
  the open account. Logging out goes on with the next saved account.

### Nexa, moderation and safety

- Nexa is the account the service speaks as: notices, announcements, and case threads where a member can
  appeal (not end-to-end encrypted, and the app says so). The staff panel is `/channels/@admin`
  (`/api/v1/moderation/*`); the member's side is `/api/v1/nexa/*`.
- The panel (the owner's rule, 2026-11-04: everything one or two presses away, nothing behind sub-pages or
  menus that a button could do) has its own sidebar (web `moderation/StaffSidebar.tsx`, Android
  `ui/moderation/StaffSidebar.kt`) from one list of places (`STAFF_GROUPS`), each with its least rank. Every
  badge for the panel is `attentionCount`: unread case and bug threads plus open reports. A person's page (web
  `PersonPage.tsx`, Android `Person.kt`) is one page.
- Staff notifications (web `lib/staffAlerts.ts`, Android `notify/StaffAlerts.kt`) are set by the synced
  `notifications.staff` (missing means on). Urgent reasons play the mention sound. Quiet while the place they
  belong to is on screen.
- Staff ranks (`api/staff.go`, web `lib/staffRank.ts`, Android `data/StaffRanks.kt`) are public user flags,
  each holding everything below it; the owner's rule (2026-11-04) is that each rank's powers fit its job, and
  that no rank gives rights in other people's servers or group chats. Helper (1024): support and triage: the
  report queue (claim, notes, dismiss; never Action Taken), bug reports, and reading people, cases and the
  log; acts on nobody. Mod (512): takes reported content down, mutes and bans for at most 30 days
  (`modMaxDuration`; never permanent, never shortening a longer one), replies in cases and lifts, closes and
  reopens the ones they opened, browses Servers and Stickers & Emoji. Admin (256): restrictions of any length,
  anyone's cases, server and Discover takedowns and pack relisting, alt accounts, call usage, apps,
  announcements, badges, bulk moderation, disabling sign-in and deleting accounts of people who are not staff.
  Developer (1, the first account, or `ADMIN_USERNAME` while nobody has been one): the instance's settings,
  stats and crash reports. Owner, the oldest Developer (`InstanceOwnerID`): the legal report, and the only one
  who gives and takes ranks (`PATCH /admin/users/{id}` with `rank`) or disables and deletes staff. Anyone
  holding a rank cannot be moderated. Routes state their least rank with `s.staff(rank, h)`, as does
  `emitStaff`. The apps ask `can(rank, capability)` (web `STAFF_CAN`, Android `StaffCan`), kept equal to the
  routes.
- Staff are ordinary members of the servers and group chats they join: only a server's owner holds every
  permission there (`serverSnapshot.privileged`), only a group's owner manages it (`managesGroup` on all
  three), and staff delete other people's messages only through a report's Take Action. Until 2026-11-04
  Developers held every permission in every server they were in, private channels and their keys included;
  keys handed out then stay with them until those channels' keys rotate.
- Staff actions (`moderation_*` files) are logged in `mod_log`; an action on an account comes with a case
  (`mod_cases`) whose thread the member sees. Bulk moderation sends the same per-account action for each
  account in turn, so each gets its own case.
- Alt accounts: `user_ips` keeps every address an account signed in or was active from (`Store.RecordUserIP`;
  deleted 180 days after last use by the maintenance loop, and with the account).
  `GET /moderation/users/{id}/alts` lists alts with how many addresses they share, never the addresses; only
  the legal report prints addresses. The Privacy Policy's section 3e describes it.
- Bug reports (`/api/v1/bugs`), client crash reports (`/api/v1/client-errors`), and the instance settings
  (registration mode and key, which override `chatd.env` until cleared).
- Case threads and bug reports are conversations with private blobs of kind `case` and `bug`. A closed case,
  or a bug report set to resolved, won't fix or closed, takes nothing from anyone, staff included, until staff
  reopen it. `mod_reads` keeps what each member of staff has read.
- AutoMod: the server applies a server's rules to what it can read (names, profiles, thread and post titles);
  members' apps apply them to encrypted messages and report what they held
  (`POST /servers/{id}/automod/report`).

### Safety reports

- Routes in `api/reports.go`, queries in `store/reports.go`: `/api/v1/reports`, `/reports/target`,
  `/reports/attachments`, `/media/reports/{id}`, and the staff's `/api/v1/moderation/reports` (`group=item`
  keeps the newest report about each item; detail adds `related`, `reporter_stats`, `subject`; review is
  version-checked). `POST /{id}/remove-message` (Mod and up) removes exactly the reported message without
  joining its conversation or granting keys, opening a case and logging in the same transaction, and closes
  every active report about it as Action Taken. Every staff rank can review reports; rank limits still govern
  other actions, and `staff` keeps the required rank immutable across requests, rejected ones included.
- A per-account request reference makes retries idempotent. `limReport` (ten an hour, captcha action
  `safety_report`) and the upload budgets limit abuse; muted members can report. Receipts (`reportReceipt`)
  show only the reporter's own submissions, statuses, the item's name as they saw it and what staff told them,
  never notes, assignments or who reviewed. Evidence files are private blobs of kind `report`, seen only by
  their uploader until submitted, then by staff too; snapshot pictures take blob references so later edits
  cannot garbage-collect them.
- Reasons (`reportReasons`, the order the forms list them) are harassment, hate, violence, self_harm, sexual,
  child_safety, underage, spam, scam, privacy, ban_evasion, illegal and other; underage and ban_evasion are
  offered only for profiles, messages and something else (`reasonKinds`). Each has an icon and a tone (web
  `lib/reportReasons.ts`, Android `Reports`); urgent reasons show an emergency-services note in the form.
  Sexual content is not offered, and the API refuses it against the target's current settings, for DMs and
  group chats, NSFW channels (inherited designations included) and content tied to NSFW communities.
- Feedback: closing a report sets `outcome_at`, which puts a `report_outcome` item in the reporter's Nexa
  conversation; reopening clears it. `report_update` carries the receipt to the reporter, `mod_report` carries
  changes to every member of staff, with `new` when the first was just sent.
- The form: web `ReportModal.tsx`, Android `openReport` in `ui/reports/ReportForm.kt`. Only Something Else
  takes screenshots (up to five, 100 MB); every other report shares the captured content instead. Your
  Reports: web `settings/YourReportsPage.tsx`, Android `ui/settings/YourReportsPage.kt`.
- Message evidence: the backend resolves the responsible account and keeps only explicit readable fields. A
  message report must carry the complete current message and every attached file (stale edit versions, missing
  content, omitted attachments and invalid file keys are refused), sharing only each reported file's own key,
  never conversation roots or note keys. Private descriptors live under `_message_files` and are stripped from
  all list and detail JSON; staff downloads decrypt chunked AES-GCM with range support (`media/reported.go`),
  and reported files keep their blob references after the source is deleted. Evidence is not end-to-end
  encrypted and is labelled reporter-supplied, not authenticated proof. Reports give staff no conversation
  keys and no unreported content.
- Staff (web `ReportsTab.tsx` with `ReportDecision.tsx`, Android `ui/moderation/Reports.kt` with
  `ReportDecision.kt`): the queue shows each reported thing once. Take Action runs the existing audited calls
  in turn, then closes the report as Action Taken with the reason and case numbers as its note; a failure
  part-way says so and reloads. A helper sees only Dismiss; mods stay within 30 days; a server's own parts
  need admins. Reporting adds no staff privileges.

### NSFW communities

- `servers.nsfw` (migration 0040, both databases) marks a whole community; channel `nsfw` flags stay
  independent, and a channel's settings recommend marking the server only if most of its content is NSFW. The
  Terms permit NSFW communities, require the classification for majority-NSFW servers, and allow a few NSFW
  channels without it when the public-facing details hold no NSFW content.
- Discovery excludes both kinds by default, category counts and popular tags included. `nsfw=1` opts in after
  an 18+ confirmation; `nsfw_only=1` narrows to servers marked NSFW or holding any NSFW channel. The reserved
  `nsfw` tag is derived from that classification, uses no custom-tag slot and cannot be given to a safe
  server; searching never bypasses the opt-in. Cards distinguish `nsfw` (the whole community, badged) from
  `nsfw_content` (either kind). Both clients use the same catalogue strings and Terms version.
- Only whole-community NSFW servers gate invite previews and invite or Discovery joins with `nsfw=1`: an
  unconfirmed preview returns only its code, `nsfw: true` and `age_gate: true`, link unfurls and page metadata
  reveal nothing, and the public bot-protocol invite lookup needs the same confirmation. The clients keep each
  confirmation in memory for the current account. Public media URLs stay public.
- Whole-community NSFW servers cannot create or wear guild tags (turning the toggle on strips the tag from
  wearers); a few NSFW channels change neither tags nor public details.

### Profile badges (`store/badges.go`, `api/badge_handlers.go`)

Badge ids: `staff` (never hideable), `plan`, `og` (account made before `OGCutoff`), `years`, `bug_hunter`,
`app_developer` (`AppDeveloperServers`), `gift_giver` (`GiftTiers`), and the two staff give by hand in
`user_badges`: `contributor` and `event:<badge_defs id>`. Earned badges are worked out when asked for, never
stored; bots, Nexa's accounts and deleted accounts have none. `/users/@me/badges` stays open to muted and
banned accounts. Web `lib/badges.ts`, `components/badges/Badges.tsx`, `moderation/BadgesTab.tsx`; Android
`data/Badges`, `ui/profile/Badges.kt`, `ui/moderation/BadgeTab.kt`. The event icon list must name icons both
lucide-react and Android's `lucide.json` have.

### Profile widgets (`api/widgets.go`, web `lib/widgets.ts`, Android `ui/profile/Widgets.kt`)

- A JSON list on `users.widgets` and `server_members.widgets` (no migration for new kinds; apps skip kinds
  they do not know), checked by `cleanWidgets`. Card links confirm the full address before opening.
- Countdown and Local Time count against the server clock (web `lib/timeWidgets.ts`, Android
  `data/TimeWidgets.kt`). Sticker Showcase: the server checks owner and public on save; a pack later deleted
  or made private drops out for viewers. `store.WidgetImages` counts `books` and `screen` items' own pictures.
- Books use Open Library (`GET /api/v1/books/search`; it can take over ten seconds, so it waits 20), shows and
  films TMDB (`GET /api/v1/screen/search`, off without `TMDB_API_KEY`, a v4 Read Access Token), both through
  the egress proxy. TMDB's terms require its logo (`web/public/brand/tmdb.svg`) and the English notice
  `widgets.screen.tmdbNotice` under the search and in Settings, About.
- Music widgets can be filled from a link: `POST /api/v1/music/from-link` searches Deezer for each piece and
  keeps those whose name and lead artist match (`sameMusic`), naming the rest in `missed`.
- Current apps always send `links` on a card, even empty. A card saved without it comes from an older app, so
  `carryCardExtras` keeps the saved card's extras (`TestOldAppsKeepWhatCardsGained`).

### Game pages (`internal/games/details.go`, `api/widgets.go`)

`GET /api/v1/games/{id}` is a game's page from IGDB, kept a day in memory; `GET /api/v1/games/match?name=`
finds the id for activities that carry none (a detected game comes with `game_id`). Both under `limSearch`. Of
several games with exactly one name, the most rated wins (`mostRated`): IGDB lists "Hollow Knight" twice. `/media/game/{id}` serves pictures at
`t` and `b` only for image ids a fetched page named. Web `components/games/GamePage.tsx`, Android
`ui/profile/GamePage.kt`. Store links confirm first.

### Plans and payments (`api/billing.go`, `internal/stripe`)

- Supporter ($5 a month, $48 a year) and Pro ($10, $96) raise only what costs money (`planLimitsFor`): the
  largest file (`MAX_UPLOAD_BYTES` sizes only files no plan covers, from webhooks, apps and notes), uploads
  per rolling 30 days, the Quality screen share and top share bitrate, the paid name effects (Holo, Neon,
  Flame: refused without a plan, `plan_required`, and sent as the first colour solid, `store.styleForPlan`)
  and Pro's two server boosts (`fileMaxFor`). Chatting, calls and everything else stay free. `users.plan` is
  public, as a badge.
- Stripe Managed Payments is the merchant of record. Prices are found by lookup key (`nexa_*`) and cached.
  `POST /billing/webhook` checks Stripe's signature and always rereads the subscription from Stripe, so
  repeated or out-of-order events end the same way; an older subscription ending never overrides a newer one.
- An account wears the better of its subscription and a running gift (`plan_gifts`, never between people
  either of whom blocked the other). Gift links are claimed by whoever opens `/gift/<code>` first.
  `recomputePlanTx` writes `users.plan` and drops the boosts of an account no longer Pro. Deleting an account
  cancels its subscription (`endSubscription`) and deletes its plan records.
- Pay what you want (`POST /billing/pay-what-you-want`): one Checkout payment, never renewed. `pwywSplit`
  turns it into paid time, applied once by the webhook through `Store.AddPaidTime`; the apps show the same
  split before Stripe's page opens. The Support page always shows a Refunds section (14 days, in full, the
  contact line) and every pay button carries `support.refunds.line`; keep them in step with the Terms' section
  8.
- Without `STRIPE_SECRET_KEY` plans cannot be bought (`billing_off`), but the limits hold. Web `lib/plans.ts`,
  `stores/billing.ts`, `components/support/`; Android `data/Plans`, `ui/support/Support.kt`.

### Mentions

- Web `components/mentions/MentionsModal.tsx`, `lib/mentions.ts`; Android `ui/chat/Mentions.kt`.
- `GET /api/v1/users/@me/mentions` (`store/mentions.go`) reads the caller's open conversations (locked
  subchats only once unlocked in that session) and every server channel and thread, archived ones included,
  where they have View Channel and Read Message History, and leaves out their own messages, blocked authors,
  system messages and @everyone from before they joined. The partial index `messages_mentioning` holds only
  messages that mention anyone; the query repeats its condition so both databases use it. Messages come back
  sealed.
- Android's jump into a channel not yet loaded keeps `ChatUi.jump` set until the list has handled it, and
  `Messages.loadAround` rethrows cancellation: a cold open recomposes the list mid-load, which once swallowed
  the cancellation and toasted "That message could not be found".

### Link and media channels (modelled on Fluxer's)

- A link channel (`link`) opens `channels.url`: a whole http or https address with a host and no userinfo, at
  most 1024 bytes (`cleanLinkURL`; web `lib/linkChannel.ts`, Android `ui/shell/LinkChannels.kt`). A Nexa
  address opens in the app, anything else after a confirm showing the full address. Every channel name keeps
  spaces and capitals (`cleanChannelName` only collapses whitespace). Bots never see link channels.
- A media channel (`media`, protocol type 16) is a forum whose layout is always `gallery`. A post needs a
  picture or video; the server cannot see attachment types, so the apps hold that. `forum.hide_downloads`
  makes the apps leave download, save, copy and open buttons off (web `lib/mediaDownloads.ts`, Android
  `data/MediaDownloads`); a forum never keeps it.

### Messages: links, forwarding and voice messages

- A bare link to a channel or message on this instance that this account can see is drawn as a pill (web
  `jumpTarget` in `lib/appLinks.ts`, Android `AppLinks.jumpTarget`); otherwise it stays a plain link. No
  preview is fetched for these links, and previews older apps sent for them are not drawn.
- Forwarding (`body.forward`; web `lib/forward.ts`, Android `data/Forward`): the server checks the source as a
  reader, lets the new message carry the source's files without owning them (no upload, no allowance spent),
  clears mentions, sets flag 1 << 14 and has its own limit. Android draws the forwarded bar after layout
  without intrinsic measurement, which BoxWithConstraints cannot answer and which used to crash.
- A voice message is an encrypted audio attachment whose meta has `voice: true`, `duration` and `wave`; the
  server knows nothing of it. The text menu's `useTextMenu` must keep the Show Send Message Button switch
  object with its latest state and callback, not wrap it in a function. Recording: web `lib/voiceMessage.ts`,
  `voice/recordingInput.ts`; Android `data/VoiceMessages`, `data/RecordingDsp` (its RNNoise state is never
  shared with a call).
- Transcribe runs Whisper Tiny on the device; audio and transcripts never leave it, and a transcript lives
  only in the open message's memory. Web (`lib/transcribe.ts`, `transcribe.worker.ts`): the model is committed
  in `web/public/models/whisper-tiny/` (no Git LFS; `manifest.json` pins upstream URLs, sizes and SHA-256);
  remote models are off, so a missing file fails instead of reaching Hugging Face, and missing `/models/`
  files are 404s, never the SPA page. Trusted static JavaScript and module responses carry the app's CSP,
  since a worker enforces its own script's policy. Android (`data/Transcripts`, whisper.cpp 1.9.5 pinned by
  SHA-256) downloads `ggml-tiny.bin` from the Nexa server on first use and checks size and SHA-256 before an
  atomic rename. Both show download progress only after Transcribe is chosen.

### Optional host maintenance

`MAINTENANCE_FILE` is empty by default; only nexa.fish points it at the host-written
`/var/lib/nexa-maintenance/schedule.json`. `api/maintenance.go` serves public `GET /api/v1/maintenance`; a
missing or invalid file means no notice, and the app never runs reboot commands. The optional units
`deploy/nexa-maintenance-*` reboot the host 48 hours after boot, replacing any daily reboot timer
(`deploy/README.md`; `tools/test_maintenance.py`). Web `shell/MaintenanceBanner.tsx` and Android
`ui/shell/MaintenanceBanner.kt` show a non-dismissible countdown, timed on local elapsed clocks so a wrong
device clock does not matter. The homepage demo skips it.

### Configuration

Every setting is documented in `chatd.env.example` and loaded by `internal/config`. Groups: addresses
(`LISTEN`, `PUBLIC_URL`, `ALT_URLS`, `MOVED_FROM`), storage (`DATA_DIR`, `DATABASE_URL`, `REDIS_URL`,
`WEB_DIST`, `DESKTOP_DIR`, `ANDROID_DIR`, `NODE_ID`, and the bucket: `R2_ACCOUNT_ID`, `R2_BUCKET` (`off` turns
it off), `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_ENDPOINT` for tests, `R2_ANALYTICS_TOKEN`), limits
(`MAX_UPLOAD_BYTES`, `MAX_AVATAR_BYTES`, `MAX_EMOJI_BYTES`, `MAX_STICKER_BYTES`, `SESSION_TTL_DAYS`), sign-up
(`REGISTRATION` open/invite/closed, `REGISTRATION_KEY`, `ADMIN_USERNAME`, `TURNSTILE_SITE_KEY`,
`TURNSTILE_SECRET_KEY`, and the `HCAPTCHA_SITE_KEY`, `HCAPTCHA_SECRET_KEY` fallback), calls
(`REALTIME_APP_ID`, `REALTIME_APP_SECRET`, `REALTIME_MONTHLY_GB`, `REALTIME_ACCOUNT_ID`,
`REALTIME_ANALYTICS_TOKEN`, `REALTIME_TURN_DAILY_GB`, `GUEST_LINKS_P2P`, `STUN_URLS`, `TURN_URLS`,
`TURN_USERNAME`, `TURN_CREDENTIAL`, `TURN_KEY_ID`, `TURN_API_TOKEN`), outbound (`EGRESS_PROXY`,
`WARP_PROXY_PORT`), integrations (`KLIPY_API_KEY`, `IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET`, `TMDB_API_KEY`,
`TELEGRAM_BOT_TOKEN`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `DISCORD_BOT_TOKEN`,
`DISCORD_ACTIVITY_GUILD_ID`, `DISCORD_REDIRECT_URL` left empty, `DISCORD_API_URL` for the fake), payments
(`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_API_URL` for a fake), logging (`LOG_LEVEL`,
`LOG_FILE`), optional host maintenance (`MAINTENANCE_FILE`). A new setting goes in `config.go`,
`chatd.env.example` and this list.

### Go tests

- `internal/api`: `newTestServer(t)` (`testutil_test.go`) is a real `httptest` server with the gateway wired
  in, on a fresh database (SQLite, or a Postgres schema with `CHAT_TEST_POSTGRES`). Top-level tests that call
  it run in parallel; a test that changes a package-level setting calls `serial(t)` first, which waits for the
  others and runs it alone. `register(t, ts, username)` returns a token and id;
  `do(t, ts, method, path, token, body)` makes a JSON request; `errCode` reads an error; `connectGWReady` /
  `connectGW` open a gateway socket and `gw.next(event, timeout)` waits for an event. Apps (`bot_test.go`):
  `makeApp`, `botDo`, `addBot`. TURN (`turn_test.go`): `newFakeTURN(t, srv)`, `iceUsername`, `idleTURN`,
  `putInCall`. The Realtime SFU stand-in is `newRealtimeStandIn` (`sfu_handlers_test.go`).
- `internal/store`: `newStore(t)`, `mkUser(t, s, name)`.
- Tests against live services run only with their variables set (`UNFURL_LIVE`, `REALTIME_LIVE`,
  `TELEGRAM_LIVE_TOKEN`, `DISCORD_BOT_TOKEN`, and `OBJSTORE_LIVE` with the test bucket's `R2_*`, which refuses
  any bucket not named for tests); the Discord tests otherwise use `fakediscord`.
- `TestPlanCaps` asks for room for a 1.5 GB file, so it fails with `no_space` when the test's temporary
  directory is short of it (a small `/tmp`); point `TMPDIR` at a real disk.
- The TURN tests (`turn_test.go`, `TestGuestTURN...`, `TestCloudflareTURNCredentials`) fail when run together
  (the whole package, or `-run 'TURN'`), at the commit before 2026-11-04's staff rework too; each passes alone
  (`-run '^TestName$'`). A failure there alone is that, not a regression.

## End-to-end encryption

The client does all of it (`web/src/crypto`; Android's port in `fish.nexa.crypto` is checked against
`web-crypto-vectors.json`). The server stores ciphertext plus what it needs to deliver: author, channel,
mentions, reactions, sizes.

- Accounts: Argon2id stretches the password on the device, and HKDF splits it into an auth key (the only thing
  sent, hashed again on the server) and an encryption key wrapping the account's X25519 identity. Clients
  check the server's KDF parameters (`lib/serverKdf.ts`) so it cannot weaken them. A recovery key shown once
  at registration can restore the identity.
- Conversations have a root key per epoch, sealed to each member's identity (`key_grants`); channel keys
  derive from it, so one grant covers a whole server. `keys_needed` asks an online holder to wrap every epoch
  a member lacks (a waiting device asks again with `POST /keys/request`), and `key_rotation_needed` asks one
  for a new epoch when membership shrinks. Invite links can carry the root key in the fragment
  (`#k=<epoch>.<key>`), which never reaches the server.
- Sends and edits use the channel's current conversation and epoch: clients fetch a missing grant rather than
  use an old root, the API rejects stale epochs, and `InsertEncryptedMessage` and `UpdateEncryptedMessage`
  check again inside the writing transaction under the epoch lock. An edit after a channel turns private uses
  its private key. `POST /keys/request` with `bad_epoch` and `bad_wrapped_key` discards exactly the caller's
  unusable wrapper (never another recipient's row or an already replaced one); both clients send it when a
  wrapper fails to open.
- Messages are AES-256-GCM envelopes binding channel and author. The clients hold `MESSAGE_LIMITS`
  (`crypto/message.ts`, Android `MessageCrypto.Limits`): 4000 characters as typed (`typedLength`), 10 files, 5
  link previews and the rest. Neither sends a payload past them, and one arriving past them is shown as
  breaking the limits (`brokenLimit`), not drawn.
- Files are chunked (`crypto/fileChunks.ts`: "ECF1"), each with its own key inside the message; downloads and
  video decrypt as they stream (`download-sw.ts`). Readers check the plaintext size the message authenticates
  as well as each chunk's tag, so dropped whole chunks fail even without a Content-Length. Blob ids in
  decrypted metadata must be 1 to 20 ASCII digits (Android checks again at its media-cache boundary).
- Call frames are sealed again with a key derived from the conversation's (`voice/frameCrypto.ts`; Android
  natively in `cpp/frame` with RustCrypto AES-GCM from `android/frame-crypto`, same wire format), because the
  SFU ends transport encryption; guest links carry the call key in the fragment. Clients keep full epoch
  numbers and try at most eight matching keys when legacy low-byte frame indexes collide.
  `node tools/check_frame_crypto.mjs` checks native and WebCrypto compatibility, tampering and epoch
  collisions.
- Notes have their own key sealed to each member, and a share link carries a second key in its fragment.
  Announcement follows are sealed to the follow's key. The settings blob, sound packs and the on-device search
  index are sealed too.
- Keys on the device live in IndexedDB (`crypto/keystore.ts`), cleared on sign-out; with a passcode lock
  neither the identity nor any root is kept in the clear, and plain data exports omit `appLock` (its salt, KDF
  parameters and verifier would allow offline guessing). Identity keys are pinned on first sight
  (`lib/keyPins.ts`); safety numbers (`crypto/fingerprint.ts`) let two people compare them.
- Apps: a message meant for a bot carries `bot_copies`, sealed by the sender's app to the bot's X25519 key
  (web `lib/botCopies.ts`, Android `data/BotCopies`); the server keeps bots' private keys sealed with the
  server secret and opens the copies for them (`bot_crypto.go`). An app with the Message Content intent gets
  the server's keys like a member and the server decrypts for it; the start of every channel it can see names
  it (web `chat/apps/readingApps.ts`, Android `ui/apps/ReadingApps.kt`), but by the owner's decision the
  channel header shows no mark (whoever added the app knew, and the topic keeps its room). The reading glass
  in the portal and consent page shows which kind an app is.
- Not end-to-end encrypted: webhook and app messages (`messages.plain`, marked in the app), interactions,
  Nexa's case threads, poll and AutoMod notices the server writes, and everything public (profiles, avatars,
  server names, emoji, stickers).

## Web app (`web/src`)

React 18, TypeScript, Vite, zustand and react-router; `@/` is `web/src`. `main.tsx` registers two service
workers: `sw.ts` (no fetch handler; a new copy taking over means a deploy, which `lib/appUpdate.ts` offers)
and `download-sw.ts` (scoped to `/__download/`; streams and decrypts large downloads and videos). Pages load
lazily (`lib/lazyChunk.ts`); a dialog with lazy pages makes them with `readyPage` and switches inside
`startTransition`, so frame and page show together.

- Routes are in `routes.tsx`. `api/` is the typed REST client (`api/client.ts`, `api/types.ts`);
  `gateway/client.ts` keeps the socket, `gateway/session.ts` starts it, `gateway/dispatch.ts` applies events
  to the stores; `lib/decrypt.ts` turns messages into what the stores keep; `lib/keyring.ts` holds roots in
  memory.
- Stores (`stores/`, zustand), among them `auth`, `settings`, `messages`, `channels`, `servers`, `users`,
  `voice`, `ui` (modals), `typing`, `threadLast`, `voiceDrag`, `appCommands`. `settings` (sealed, synced with
  an optimistic version): a save that loses the race lays only this device's own edits over the newer copy,
  and the notification lists and `favoriteGifs` are merged entry by entry through `ListEdits` (Android
  `SettingsStore.gifEdits`, `layerGifs`), since saving those whole dropped other devices' entries.
- Folders: `crypto/`, `markdown/`, `search/` (a sealed full-text index of what this device decrypted, in a
  worker), `voice/`, `lib/` (one concern per file, plus `look/`, `notes/`, `automod/`, `spell/`),
  `components/` by area, `pages/` (one per route), `styles/` (`tokens.css` holds every theme token), `i18n/`
  (`useT()`, `t()` outside components, `<Tr>`; English bundled, the other eleven catalogues, ar, de, es, fr,
  hi, ja, ko, pt-BR, ru, sv, zh-CN, loaded when chosen; flat keys dotted by area).
- Tests sit beside the code (`*.test.ts[x]`); Vitest runs in node with `fake-indexeddb`, component tests start
  with `// @vitest-environment jsdom`.
- An effect that calls `scrollTo`, `scrollBy` or `scrollIntoView` keeps the call in braces: Chromium's scroll
  methods now answer a promise, which an arrow effect returns and React then calls as its cleanup ("c is not a
  function" on unmount).
- Write `:hover` plainly: `hoverOnlyWithAMouse` (a PostCSS step in `vite.config.ts`) moves every `:hover` rule
  into `@media (hover: hover)`, since phones keep the last tap's hover; what only hover reveals needs its own
  `@media (hover: none)` rule.
- Colours are picked with `components/common/ColourInput.tsx` (Android `openColourPicker`), never
  `<input type="color">`.
- `components/chat/MessageList.tsx` measures reading positions from the bottom of a reversed scroller. In
  Chromium, incoming history waits for touch and wheel scrolling to settle and restores the visible row if the
  scroll range rebases; cutting a long list back restores the on-screen row (`trimAnchor`), since the browser
  clamps a shrinking range first. Fast wheel scrolling once sat on one held page indefinitely;
  `MessageList.test.tsx` covers these. Android uses stable row keys in its reversed LazyColumn.
- Subchats stay listed under the open conversation, and unread ones also under their parent until read.
  Android reruns read marking when history finishes loading.

### Shared user text layout

User text must fit its slot whatever its length limit. Markdown list items clip their own ink, so their
markers sit inside with a hanging indent (`markdown.css`). Never blacklist Unicode to fix layout: U+FDFD,
combining sequences, non-breaking spaces and long words must be contained, even from older saves or other
clients. `base.css` gives inherited emergency wrapping and the `PersonName` identity boundary; the chat list
never scrolls sideways. Compact labels get explicit line limits, titles are bounded, markdown blocks wrap and
clip excess ink, code and tables scroll in their own wrappers, and emoji fallbacks stay icon-sized. Android
bounds ink in its common text, name and markdown renderers (keep its bounded name-effect halo). Removable
labels go in a shrinkable text child so their buttons stay visible, and shared action buttons do not shrink in
flex rows (text and inputs yield, form rows wrap). `docs/UI_TEXT_AUDIT.md` is the regression workflow, and
`docs/UI_TEXT_INPUTS.md` with `docs/UI_TEXT_INPUTS.tsv` and `docs/UI_TEXT_INPUTS_ANDROID.tsv` the field-level
ledger; update them when adding shared text fields, dynamic prompts or imported metadata. The dev-only
`web/tests/text-layout.html` fixture is checked with `cd web && npx tsc --noEmit -p tests` and
`tools/text_layout_audit.py` against an explicit Vite scratch-server proxy; Android's debug-only
`TextLayoutAuditActivity` does the same. Neither is a production route.

### Motion

- `lib/motion.ts`: `motionOff()` (Reduced motion, Nexa's or the system's) for script animations, and
  `listOpenedAt`. CSS stops through `base.css`'s Reduced motion rules.
- `:where(button, a, ...)` in `base.css` eases every control at zero specificity, so a component's own
  transition wins. `applyTheme` holds every transition (`data-theme-switching`) while a theme goes on.
- Tab strips: `lib/tabGlide.ts` slides the chosen pill in every `role="tablist"` and every `role="radiogroup"`
  marked `data-glide` (segmented switches only, never swatches, cards or chips); `data-no-glide` opts out. The
  item takes its look back with transitions held (`[data-glide-hold]`; with them running it flashed once as it
  landed).
- Closing: `lib/leaveCopy.ts` leaves an inert, `aria-hidden` copy fading where the thing stood, with typed
  values and scroll positions copied, nothing in it moving (`[data-leaving]`), ids, `data-modal-layer` and
  `role="dialog"` removed (code that asks whether a dialog is open must not see it), and no copy of anything
  holding a canvas, video or iframe, which would come up blank. A dialog opening while another's copy fades
  takes over the dim at once (`data-calm`), so the dim never doubles or dips.
- Arrivals: a message that comes in while its conversation is open rises in (`data-fresh`; never history or
  what came before `listOpenedAt`); a new reaction pops and a changed count ticks (`Reactions.tsx` `moving`,
  let go on animationend so a render cannot cut it short); the member list slides in when opened, not when a
  conversation changes. State that starts an animation is decided while rendering, never in an effect, so
  nothing shows still for a frame first.

### Homepage demo (web only)

`HomeDemo.tsx` embeds `demo.html`, a second Vite entry, in a sandboxed iframe running the real routes and
shell for a fresh, empty visitor account (no fictional people or prefilled servers). `demo/environment.ts`
runs before any app import: storage is fresh maps, IndexedDB an in-memory fake; fetch allows only same-origin
bundled assets without credentials; sockets, XHR, workers, media devices and native navigation are blocked; no
real token is read or issued. `demo/runtime.ts` replaces the API with memory-only operations; unknown routes
fail closed with Create an Account to Continue, as do people, invites, calls, uploads, payments and security
actions. New app endpoints must never reach a real transport in the demo. `lib/demoMode.ts` is true only
there: RequireAuth skips the gateway, AppShell skips live effects and update prompts, voice and plugins ask
for an account first, `desktopBridge()` refuses, LookEffect allows local CSS. Only `/demo.html` allows
same-origin framing (every other page forbids it), with a stricter CSP. Dialogs focus with `preventScroll` so
the gate cannot scroll the homepage.

### Public source

`lib/legal.ts` exports `GITHUB_URL` (Android `Legal.githubUrl`). The homepage names open source in its
introduction and as the first of its five promises and links GitHub; Settings has a GitHub shortcut on every
platform; the About page and Settings, About explain the MIT license and invite contributions. README.md uses
the synthetic `web/public/home/app.png`, which holds no personal conversations.

### Apps (web)

`api/apps.ts`, `lib/apps.ts`, `lib/interactions.ts`, `lib/botCopies.ts`, `lib/pickables.ts`,
`lib/passwordProof.ts`. `components/chat/apps` draws app messages and menu entries; `components/apps` holds
the shared parts; `composer/CommandMode.tsx` fills a command an option at a time. The composer's lists and
option bar are `docked` in the page's flow so messages move up rather than being covered (Android
`CommandPicker`, `ui/apps/CommandMode.kt`). The Developer Portal is `settings/portal`, Authorized Apps
`settings/AuthorizedAppsPage.tsx`, a server's apps `server/pages/AppsSection.tsx`, the consent page
`pages/AuthorizePage.tsx`.

### Menus

- Every menu is registered in `lib/contextMenus/registry.ts`, never built inline:
  `defineMenu<Ctx>(kind, groups)` at module level beside what it acts on, groups of
  `button(id, (ctx) => item | items | null)`, opened with `menu.open(ctx, placement)`. Button ids are
  camelCase and unique across all menus (`deleteMessage`), since plugins name buttons by id alone; buttons two
  menus share come from a factory taking the ids. A menu says what it is about with
  `about(menu, subjects, (ctx) => ({ message, userId, channelId, serverId }))`.
- `registry.test.ts` fails if anything but the registry calls `openContextMenu` or an id repeats;
  `lib/contextMenus/menuDocs.test.ts` generates the menu table in `docs/plugins.md` and fails when it is stale
  (`UPDATE_DOCS=1` rewrites it) or a menu lacks a line in its `WHERE` list; every menu module must be in
  `lib/contextMenus/all.ts`.
- Frequent emoji are snapshotted once per open expression menu on both clients, so hit targets do not move
  while it is open. Copy ID for Bots (`lib/copyId.ts`) sits beside every Copy User ID and copies the id bots
  see.

### Plugins (web and desktop only, never Android)

- `lib/look/plugins/runtime.ts` pins, loads, starts and stops them. No `apiVersion`, or 1, gets the v1 API
  (`api.ts`, kept as it was); 2 gets v2 (`api2.ts` types, `runtimeV2.ts`, `objects.ts`, `permissions.ts`).
- v2 grants are `PluginEntry.permissions` in the settings. Every v2 method checks its permission when called,
  events arrive only with the permission for what they carry, and context objects omit what the plugin may not
  see. Code never runs before someone agreed to it: the Add Plugin review and link-plugin updates read the
  declared `permissions` from the text (`declaration.ts`), and exactly that list is granted (all or nothing;
  updates keep what is still asked, ask again for anything new, drop the rest).
- Commands (`components/composer/commands.ts`): a v1 plugin's cannot take a taken name and its return value is
  sent; a v2 plugin's may take another plugin's name, never the app's, and sends nothing. Plugin keybinds
  (`lib/pluginKeybinds.ts`) run after the app's own. `composer.write` types into the open message box, never
  sends. A plugin's menu button may name a lucide icon; the app's own buttons have none. Version 2 only grows:
  add members and permissions, never change an existing shape.
- `docs/plugins.md` and `docs/custom-css.md` are the guides for authors, written for GitHub's renderer;
  `docs/examples/` holds whole plugins. An API change updates the guide (and its What's New in the API list)
  in the same commit.

### Notes

Mentions write `<@id>` as messages do and notify nobody (`lib/notes/mentions.ts`,
`components/notes/NoteMentions.tsx`; Android `notes/NoteMentions`). Files sit on their own line as
`![name](nexa-file:id)`, width and side as `?w=60&a=left` (`RE_FILE` in `markdown/parse.ts`;
`components/notes/NoteFile.tsx`; Android `ui/notes/NoteFile.kt`).

### Names and profiles

- Name styles (`lib/nameStyle.ts`, `api/name_style.go`, the public column `users.name_style`; Android
  `data/NameStyle`): a font, an effect, colours and an optional `animated` boolean (missing keeps the
  original). A new font goes in `NAME_FONTS`, the CSS imports, `nameFonts` in the Go file, `FONTS` in
  `android/tools/name_fonts.py` (run it) and `data/NameStyle.kt`. Synced `animateNames` (and Reduce Motion)
  stops name animations locally. A server role's colour overrides personal effects but keeps the font.
  Android's debug-only `NameStyleAuditActivity` draws faces and effects for comparison.
- Identity labels go through `components/common/PersonName.tsx` (Android `PersonName`), and names inside
  translated prose through `<Tr>`/`lib/personText.tsx` (Android `PersonText`), so only the name takes the
  effect.
- The profile designer (web `settings/ProfilePage.tsx`, `ServerProfileDesigner.tsx`, `designerParts.tsx`;
  Android `ProfilesPage.kt`, `ServerProfileDesigner.kt`, `DesignerForm.kt`): nothing is edited on the preview,
  and only the page scrolls.
- Activity card links (`components/activity/ActivityCard.tsx`, Android `ui/profile/ActivityCard.kt`) show the
  real canonical host beneath custom labels, need a full-URL review before opening (middle clicks included),
  and act only for HTTP and HTTPS without userinfo.

### Themes

- `lib/theme.ts` applies a theme and `lib/look/` derives presets and custom themes. The gallery is
  `components/settings/look/ThemeGallery.tsx` (Android `Presets.cards`).
- The theme builder (`components/settings/look/ThemeBuilder.tsx`, Android `ui/settings/ThemeBuilder.kt`) and
  its palette tools (`lib/look/tools.ts`, Android `ui/theme/look/ThemeTools.kt`): nothing they make is stored
  as such; results become hand-set overrides, so the saved theme format did not change and older apps read
  every theme.
- Seasonal themes (`lib/look/seasonal.ts` `SEASONS`, kept equal to Android's `ui/theme/look/Seasonal.kt`;
  `look_vectors.ts` checks the palettes): the gallery offers them only in season and never puts one on by
  itself; out of season one draws as Dark and `ThemeEffect` sets the account back to `dark`. Snow
  (`components/chat/SnowFall.tsx`, Android `ui/chat/SnowFall.kt`) clamps its frame time step at zero, since a
  negative step after a hidden tab made every flake NaN.
- `VITE_ALL_SEASONS=1` (web, `npx vite build` into a scratch directory) or `-PallSeasons=true` (Android) runs
  every season every day; never set either for a deploy or a release.

## Desktop app (`desktop/`)

Electron; bump the version in `desktop/package.json` for every release. `src/main/index.ts` opens one window
on the live site (`config.ts`: https://nexa.fish, or `EVILION_URL` in an unpackaged app; `npm start` builds
and runs it), with a tray, system-wide keybinds (`shortcuts.ts`), the screen picker (`displayPicker.ts`),
takeover from an older Mac copy (`succession.ts`) and permissions limited to Nexa's origin. `build.mjs`
bundles with esbuild, `electron-builder.yml` packs. `appAudio.ts` shares window sound (from 1.2.7 voice
changers and mixers that play the person's own voice back, matched by `selfMonitoringApp`, are shared without
sound); `src/main/rpc/` is a Rich Presence server speaking Discord's protocols. `src/preload/index.ts` puts
`window.evilionDesktop` on Nexa's pages; `src/shared/bridge.ts` and `web/src/lib/desktopBridge.ts` describe it
and must stay in step and compatible with installed apps (anything new is optional and checked before use).

- Releases are built on the Linux server, never brought from another machine: the owner wants every changed
  app built and published by `make build` (2026-10-20). Its `desktop-release` step runs
  `tools/desktop_release.py --if-changed`, which builds only when `desktop/package.json` names a version newer
  than the one in `DESKTOP_DIR` (`data/desktop` by default), so app changes bump the version in the same
  commit. Mac targets are signed ad hoc (`sign-mac.cjs` with `rcodesign`), packed by `tools/mac_package.py`
  (`mkfs.hfsplus`, sudo, libdmg-hfsplus's `dmg`) and published by `tools/publish_desktop.py`.
  `python3 tools/desktop_release.py` publishes the current version regardless (`--republish` replaces it).
  `make desktop` is the Mac's own build into `desktop/dist` and publishes nothing.
- Signed updates (from 1.2.8): updaters verify platform-bound Ed25519 signatures over the exact manifest bytes
  before parsing checksums, with no unsigned fallback. `releaseKey.ts` holds only the public anchor, a new key
  the owner authorized after the original was unavailable. The private key is at
  `~/.local/share/nexa-signing/update-key.pem` on the Mac and the build server (0600 in a 0700 directory);
  never commit, replace or regenerate it while building. `NEXA_DESKTOP_SIGNING_KEY` may name another path, but
  its public key must match the pinned anchor. `tools/publish_desktop.py` signs staged manifests before
  exposing any artifact, keeps the legacy `latest*.yml` feeds for the manual migration and copies through
  temporary files with an atomic rename (`python3 -m unittest discover -s tools -p test_publish_desktop.py`).
  The first installer with this anchor is a manual trusted download.
- Updates (`src/main/update/`, `test/updater.test.ts`): from 1.2.6 nothing installs on quit
  (`autoInstallOnAppQuit` off; Windows' silent installer closed a copy reopened meanwhile), so an update
  installs only from Update, once. `will-prevent-unload` keeps a page's `beforeunload` from cancelling a quit.
- Below `REQUIRED_DESKTOP` (`web/src/lib/requiredDesktop.ts`) `components/shell/RequiredUpdate.tsx` shows once
  such a release is published. Below `MANUAL_SIGNATURE_VERSION` it is a manual download with no Later or old
  updater path, even during a call. A future automatic required update waits for a call to end. Only clients
  loading the current web build see it; old code cannot be made to enforce a new anchor.
- Background (from 1.2.5, measured with `tools/desktop_idle.py`): the window is created with background
  throttling on (one created with it off never sleeps), and the gateway heartbeat runs in a worker. A call
  keeps full speed (`applyThrottling`, from the page's `setCallActive`). A launch into the tray shows the
  window for a moment, transparent and unfocused, until the page has been hidden once (`putToSleep`; not on
  Linux). The web helps: `restsWhenIdle`, `voice/videoShown.ts`, `gateOpen`, and only a call listens for
  `devicechange`, which would wake the browser's media services.
- Game detection (from 1.2.11; `src/main/games/`, Settings, Activity, on by default and off with the Rich Presence
  switch): what is running stays on the computer; only the name of a game found, and the store's id that names it
  exactly, leaves it, as an activity with `detected: true`. A look every 3 s, and the window check (on a Mac about 70
  ms of osascript) at every look with a game recognised; a game gone is looked for once more after 2 s. What is
  recognised counts only while it, or something it started, has a normal window (`gameWindows`, `dropWindowless`),
  because Roblox keeps a player process in the menu bar with no window: closing its window replaces the player with a
  hidden one, and the next window opens in that same process. Games added by hand are exempt. A system that has never
  answered the check holds nothing back; once it has answered, a look it fails to answer changes nothing either way.
  Linux has no such check, since `_NET_WM_PID` is not always set and native Wayland windows cannot be seen. Evidence, best first (`match.ts`): a game the person added; exact programs (Steam's non-Steam shortcuts,
  Heroic's added games); Steam's own launch line; Steam's manifests; records of what launchers installed (Epic,
  Legendary and Heroic, Windows' uninstall list for Battle.net, EA, Ubisoft, Riot, Rockstar and Amazon); store folder
  layouts; what the system lists as a game (Linux desktop entries, Lutris, a Mac app's `LSApplicationCategoryType`);
  the known list; then what sits beside the program (`markers.ts`: GOG and itch notes, `MicrosoftGame.config`, engine
  files). Helpers and launchers (`HELPER`) and Steam's tools (`STEAM_TOOLS`) never count.
- Discord's detectable applications list is not used: the owner's condition (2026-10-02) was to use IGDB unless it was
  within Discord's terms, and Discord's terms forbid scraping without written consent. Do not add it without that
  consent. Steam's PICS catalogue is left out too, since nothing from Valve allows it.
- Rich Presence wins (`GameService.reported`): by process, since libraries send `pid` with `SET_ACTIVITY`, family
  included (hidden programs count, so hiding one cannot show the game again); and on the server by name
  (`identifyDetected`, since only it knows an application's name). A detected game also gives way to a linked Discord
  account's copy of it that says more (`mergeActivities`).
- The known list is `internal/games/known_games.json`, written by hand and read by both sides: the server adds what
  staff accepted at public `GET /api/v1/games/known` (ETag), and the app bundles a copy for a first run offline and
  asks at start and every six hours (`known.ts`, `lists.ts`). So it changes with a deploy, without a release. Keep
  file names on it specific; `commonProcesses` in `api/game_lists.go` refuses the shared ones.
- Games by behaviour (`candidates.ts`): a program filling the screen or busy on the graphics card whose name is a
  game's. To check that without sending any program's name anywhere, the server builds a hashed index of IGDB's names
  weekly (`games/nameindex.go`; rated and unrated tiers, `GET /api/v1/games/name-index`, about 2.4 MB) and the app
  looks on the device. Names go through `nameVariants`; `judge` scores behaviour, tier and agreement, and `NEVER_A_GAME`
  and `isWebApp` rule things out. The hashes must match: `internal/games/testdata/fold_vectors.json` holds Go and
  TypeScript to the same `FoldName` and hash. What passes is only asked about (`GameSuggestionBanner`), since a wrong
  guess would go on someone's status; yes makes it an added game, no never asks about that program again, and no cover
  shows before the yes because fetching one would send the name.
- Windows' calls run on a worker thread (`windows.ts`, `windowsApi.ts`, `windowsWorker.ts`, loaded from text so it runs
  inside the archive): under Wine `GetForegroundWindow` never returned, which on the main thread would freeze the app.
  A call that does not answer in 5 s costs the thread; one that hangs twice is not asked again; a thread stuck for good
  makes `will-quit` end the process itself. Electron makes `process.resourcesPath` read only in a worker, so assigning to
  it throws; plain Node does not, so test the packaged app (`Nexa Dev.exe` under Wine, `EVILION_DEBUG=1` logs each look).
  Checked under Wine with a Windows Node: listing, windows, version resources
  and `reg query`; and the packaged `Nexa Dev.exe` under Wine, whose worker listed processes and found a stand-in game.
  Not checked on real Windows: `ProcessCommandLineInformation` (which Wine lacks, and Java games
  need), `GetForegroundWindow` and the GPU counters. On a Mac: all of it.
- Sharing: someone may share a game they added, with or without an IGDB game (`POST /api/v1/games/shared`, `limGameShare`,
  closed to muted accounts). It sends a file name, the OS, a name and any IGDB id, never a path. Each row stays until
  staff review that program or the account goes, and appears in the person's export and the legal report. The panel's
  Games page (web `moderation/GamesTab.tsx`, Android `ui/moderation/Games.kt`, rank Admin, `mod_games` carries the
  count) shows the program, the game, and how many shared it, never who. Staff set the name and IGDB game before adding;
  a turned-down program is not asked about again until Allow Again. Web `games/GameChooser.tsx` is the box art shelf.
- Open Original (from 1.2.10; `originals.ts`) hands decrypted images and videos to the default browser through
  a temporary listener on 127.0.0.1 that accepts only exact random-token paths and Host headers, serves inert
  types with no-store and a sandbox CSP, and expires each original after ten minutes. Files stay in memory,
  are never uploaded or written to disk, and go on Quit. Android shares a read-only FileProvider URI with an
  image viewer.
- `config.testHook` carries development overrides and ignores them in packaged apps; `EVILION_DEBUG` is an
  intentional logging switch.

## Android app (`android/`)

Kotlin and Jetpack Compose (package `fish.nexa`, debug builds `fish.nexa.debug`; minSdk 26, targetSdk 36). It
talks to the server as deployed, so it never depends on unreleased server changes. Native code:
`app/src/main/cpp` through CMake (call frame crypto and H.264, pinned RNNoise, whisper.cpp) and `android/ydoc`
(yrs, the Rust Yjs, built with cargo-ndk) for notes.

- `Nexa.kt` is the object graph; `data/State.kt` keeps each store as an immutable StateFlow snapshot,
  mirroring the web's stores; `data/Session.kt` handles gateway events. Packages: `crypto`, `net`, `data`
  (stores and logic ported from the web), `media`, `notes`, `search`, `voice`, `notify`, `ui/...`.
- A panel page needs its name in `Route.Admin.TABS` (`ui/nav/Nav.kt`), or tapping it goes nowhere. Activity images and
  other addresses of this server come without a host, so they go through `Media.absolute`.
- Compose redraws only for state it collected: `Nexa.settings`' getters and stores' `get(...)` are plain
  reads, so read what a composable shows with `collectAsState()` (or `ui.shell.settingOn(key)`) in that
  composable. Hot rows use `collectEntryAsState` (`ui/StoreState.kt`), so unrelated gateway updates do not
  redraw them.
- Large servers: `RoleLooks.of` visits only the member's roles and must not cache by `ServerState` (its hash
  walks the whole roster); REST and member-page decoding run off the UI thread and keep cancellation, and
  `data/MemberRoster` filters and sorts on a worker. Regression tests cover these.
- Layout (`ui/shell/Shell.kt`, the web's breakpoints): drawers below 768 dp (`Nav.wide` from there), pinned
  member list from 1100 dp (`Nav.roomy`). A slow drag settles to its side past 40%, as on the web.
- `NxSlider` marks itself `noSwipeWhileTouched`, and both settings frames' swipe back check `NoSwipe.active`,
  so dragging a slider towards the back edge moves the slider.
- Shared pieces: `openSheet`, `Modifier.floatAbove(gap)` in `components/Controls.kt`, `TickBox`,
  `RadioCard(tick = true)`. Staff panel in `ui/moderation` at `Route.Admin(path)`, the path being the web's
  below `/channels/@admin`. Apps in `data/Apps`, `ui/apps`, portal in `ui/settings`. `ui/markdown/Webhook.kt`
  parses webhook and app messages as the web's `lib/webhookMessage.ts` does (`web-hook-vectors.json.gz`); both
  pass text through `discordMarkdown`, since Nexa's parser keeps spans on one line.
- GIF favourites (`media/GifFavorites`): a link favourite is re-signed by `MediaLinks.fresh` (saved links run
  out after a day); only a link the server refuses to sign or a file that cannot be opened counts as gone.
- There is no push service: `notify/ConnectionService` keeps the connection open in the background (on unless
  turned off) and `Notifier` raises alerts.
- The exported share target takes only external `content:` URIs, at most ten; `file:` URIs and Nexa's own
  FileProvider authority are refused before staging, so other apps cannot borrow Nexa's file access.
- Session reset cancels pending notification posts, removes personal notifications and their history
  (publishing checks the account generation under the reset lock, so a late avatar load cannot repost an old
  account's alert), clears layers and navigation, and deletes decrypted media; cache filenames include the
  numeric account id.
- `AudioRoute` puts the phone in call mode without taking audio focus, so other apps keep playing and the mic
  keeps sending; only a phone call silences the call both ways until it ends.
- Updates (`ui/AppUpdate`, `/api/v1/android/latest`): downloads resume, are verified by SHA-256 before being
  offered, and install through a `PackageInstaller` session; `voice/Pip.held` keeps a call from floating over
  Android's install buttons. Debug builds never update themselves. To test it, build two releases with
  `tools/android_release.py --server http://10.0.2.2:$PORT --to <scratch dir>` into the same folder (the debug
  network config copied into `src/release/res/xml/` for those builds only) and serve it with `ANDROID_DIR`. On
  Android 12+ an app that installed itself updates with no system prompt, so Install is one tap; the first
  update after a manual install may still ask.
- OTA content packs (`data/Ota.kt`, server `androidOTAPack` in `api/android_handlers.go`):
  `GET /api/v1/android/ota` describes `ota.json` beside `manifest.json` in `ANDROID_DIR`, offered only while
  its `version_code` equals the published APK's. The app checks size and SHA-256, unpacks only
  `locales|sounds|seasonal|whatsnew|legal/<flat name>` into `noBackupFilesDir/ota`, and takes it up at the
  next process start (`Ota.init` in `NexaApp` before anything reads assets); a pack made for another
  versionCode is ignored and dropped after an APK update. To test it, serve a scratch `ANDROID_DIR` whose
  manifest says versionCode 3 (what a debug build carries) with a pack for 3.
- Motion matches the web's: `ui/components/Glide.kt` slides a row's chosen pill, toasts leave through
  `ToastSlot`, settings layers push pages on a phone and their pages have no fade of their own, and the pinned
  member list slides in only when pinned. Values that start an animation are fixed in `remember`, not set from
  an effect.
- `copyWebAssets` copies the web's locales, sounds, emoji sprites, `whatsNew.ts`, seasonal pictures and legal
  documents into the app; `assets/lucide.json`, `assets/emoji/` and `assets/licenses.json` come from
  `android/tools`. Ported logic (markdown, search, themes, the formatting editor, webhook messages, crypto,
  metadata scrubbing, the notes CRDT) is tested against vectors from the web's own code
  (`app/src/test/resources/web-*-vectors.json*`, mostly made by `android/tools/*_vectors.ts`; each file's
  header says how).
- Releases: `make build` runs `tools/android_release.py --if-changed`, which does nothing when nothing the app
  is made from changed since the release in `downloads/android` (its manifest keeps `source` and `code_source`
  hashes). When only `OTA_SOURCES` changed and `code_source` still matches, it publishes an OTA content pack
  for the published APK instead of building (`--ota` forces that and refuses when code changed); otherwise it
  builds an APK, removing any pack, numbered after the published one (passed as `-PnexaVersionCode` and
  `-PnexaVersionName`, so nothing in git changes), signs it with the private key configured in
  `android/signing` and publishes it; chatd offers it at once and phones update. `make publish-android` builds
  one regardless. Builds up to 1.1.0 used an older, lost key and cannot update; those phones reinstall.
  Release builds need the JDK and SDK (as under Commands).

## Tools (`tools/`)

- Testing: `check.sh`, `e2e.py`, `screenshot.py`, `seed.py`, the end-to-end runs above,
  `call_layout_audit.py`, `text_layout_audit.py`, `ios_sim.py` with `ios/`, `check_frame_crypto.mjs` with
  `frame_crypto_check.cc`, `privacy_check.py`, and the unittest suites `test_build_pipeline.py` (`make build`
  in isolation, deploying nothing), `test_maintenance.py` and `test_publish_desktop.py`.
- i18n: `i18n_check.py`, `i18n_keys_used.py`, `i18n_apply.py` (write or merge a language's values),
  `i18n_extract.py` (pull hardcoded English into keys); `android/tools/add_strings.py`.
- Releases: `android_release.py`, `desktop_release.py`, `mac_package.py`, `publish_desktop.py`, `desktop_dev_build.py`
  (test builds, publishing nothing), `licenses.py`
  (license texts in `licenses/`).
- Assets: `twemoji_font.py`, `twemoji_font_check.py`, `twemoji_sprites.py`, `trace_wordmark.py`,
  `home_shots.py`, `home_gifs.py`; `android/tools/name_fonts.py`. `requirements.txt` needs Brotli 1.2.1 or
  newer for the WOFF tooling.
- Video: `trailer.py` films raw footage with a real-size macOS pointer. `advert.py` films a seeded world,
  measures a song with `advert_edit.py` and cuts takes to its beats; `--cut` picks the trailer, `fast` or a
  1080x1920 `short`. Remotion draws the cut (`tools/advert-video`, its own npm project, overriding Remotion's
  pinned Zod with 4.6.7 for its collection-validation fix) in Playwright's Chromium, since Remotion's own
  headless shell does not start on the Mac.
- Schema: `gen_pg_schema.sh`, `pg_schema.py`.

## Deploying (only when the owner says "deploy")

- A web-only change: `make web`. chatd reads `web/dist` from disk on every request, so it is live at once,
  with no restart.
- A server change: `make server` (it keeps the binary it replaces as `bin/chatd.prev`), then
  `sudo systemctl restart xyz.evilion.chat` (the unit runs `run.sh`). Say so when the deploy runs a database
  migration.
- `make build` builds both, then the Android and desktop apps when they changed (see Android, Releases, and
  Desktop app); nothing is built on another machine and copied over.
- Back up first (`bash backup.sh`, which keeps only the newest set) only when the request asks for it.
- To roll back: `cp bin/chatd.prev bin/chatd` and restart (not across a migration; see Database).
