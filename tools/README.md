# Tools

Visual checks and end-to-end runs use Playwright from a Python venv:

```
python3 -m venv tools/.venv
tools/.venv/bin/pip install -r tools/requirements.txt
PLAYWRIGHT_HOST_PLATFORM_OVERRIDE=mac14 tools/.venv/bin/python -m playwright install chromium
```

(`PLAYWRIGHT_HOST_PLATFORM_OVERRIDE=mac14` is needed on macOS 13, where Playwright
would otherwise refuse to download its Chromium.)

- `tools/screenshot.py <base-url> <out-dir> --paths /login --viewports 1366x768 390x844`
- `tools/e2e.py` — starts a throwaway server and drives two browsers through register → friend → DM → attachments.
- `tools/webhook_e2e.py` — starts a throwaway server, makes a webhook in Server Settings, Integrations, posts every kind of webhook message through it (embeds, link buttons, layout components, a poll, files, GitHub and Slack formats, an edit) and screenshots them at desktop and phone sizes while a second account votes in the poll.
- `tools/seed.py <base-url>` — fills a running throwaway server with two people, a server and one message of every kind of formatting, to look at how they are drawn.
- `tools/sfu_call.py` — a real three-person call through Cloudflare Realtime SFU (keys from `chatd.env`): audio, TURN relay, camera, screen share pulls, cross-channel refusal.
- `tools/desktop_*.py` are gone: the desktop app is built and published by `.github/workflows/desktop.yml` (Tauri), not on this machine.
- `tools/android_release.py` (`make publish-android`) — builds the Android app's release APKs using private local signing configuration in `android/signing/release.properties`, checks each one's package, version, signature and server, and publishes the builds with `manifest.json` into `downloads/android`. These generated files are ignored by Git; each server builds its own. See `downloads/android/README.md` and `android/signing/README.md`.
- `tools/licenses.py` (`make licenses`) — writes `web/src/data/licenses.json`, which is what Settings → Licenses shows: every dependency that ships in a build, read out of the web's npm packages, Go's module graph and the desktop's Cargo graph, with one copy of each licence text.
- `tools/twemoji_font.py` — builds `web/src/assets/fonts/twemoji-colr.woff2`, the colour font text boxes, names and menus draw emoji with, from the same Twemoji SVGs messages show. Rerun after upgrading `@twemoji/svg` or `emojibase-data`, then run `tools/twemoji_font_check.py` (add `--browser firefox` or `--browser webkit` after `python -m playwright install firefox webkit`): it draws every emoji in the font and as its SVG and fails on any that differ or fall apart into pieces.

## iPhones and iPads

Playwright's phone sizes are Chromium in a small window. They have no iOS keyboard, and none of
what Safari does around one (the visual viewport, the page sliding up to a focused field).
`tools/ios_sim.py` drives Safari in an iOS simulator instead, with real touches and the real
on-screen keyboard, and runs JavaScript in the page.

It needs Xcode with an iOS simulator runtime (`xcodebuild -downloadPlatform iOS`, and
`xcodebuild -downloadPlatform iOS -buildVersion 26.0` for an older one), and:

```
brew install xcodegen ios-webkit-debug-proxy
tools/.venv/bin/pip install -r tools/requirements.txt
```

```
tools/.venv/bin/python tools/ios_sim.py up --device "iPhone 17"       # boots it, builds and starts the driver
tools/.venv/bin/python tools/ios_sim.py open http://127.0.0.1:18431/login
tools/.venv/bin/python tools/ios_sim.py tapsel "input[autocomplete=username]"
tools/.venv/bin/python tools/ios_sim.py keys alice
tools/.venv/bin/python tools/ios_sim.py eval "[visualViewport.height, scrollY]"
tools/.venv/bin/python tools/ios_sim.py shot tools/out/ios/login.png
tools/.venv/bin/python tools/ios_sim.py down
```

`--help` lists the rest: long presses, drags, the keyboard's own Next and Previous, rotation, a
Home Screen app's page, and a screen recording laid out as a strip of frames, which is how to see
what something looks like while it moves. A throwaway server on the Mac is reached at 127.0.0.1.
Several simulators can be up at once (`up --device "iPad (A16)"`, `up --os 26`, then `--sim NAME`).

Safari differs from one iOS to the next in how it treats the keyboard (the notes are at the top
of `web/src/lib/keyboardInset.ts`), so a change there is checked on the oldest and the newest iOS
installed, on an iPad, and in a Home Screen app.
