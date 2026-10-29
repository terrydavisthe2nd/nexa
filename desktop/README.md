# Nexa for your computer

The desktop app is a small Tauri 2 shell around https://nexa.fish. It is not a
second implementation of the web app: the page is the app, and this crate adds
the things a browser tab cannot do.

```
desktop/
  src-tauri/          the Rust shell
    src/main.rs       plugins, setup, the window
    src/bridge.rs     the commands the page calls
    bridge.js         window.evilionDesktop, injected before the page loads
    src/window.rs     focus, tray close, badge, attention
    src/tray.rs       the tray menu
    src/shortcuts.rs  system-wide keys
    src/updater.rs    signed updates from GitHub Releases
    src/originals.rs  Open Original from memory over loopback
    src/rpc/          Discord Rich Presence
    src/capture.rs    the screen-recording permission
    permissions/      what the remote page may ask for
    tauri.conf.json   window, bundle and updater settings
  ui/                 a placeholder frontend; the window loads the live site
```

## What it keeps from the Electron app

- The window loads `https://nexa.fish` and gets `window.evilionDesktop` with the
  same shape the Electron preload exposed, so the web app needs no changes.
- Tray menu (Open, Mute, Deafen, Check for Updates, Quit), close to tray,
  start minimized, launch at login.
- System-wide keys (push to talk, mute, deafen) through the global-shortcut
  plugin.
- Discord Rich Presence: a local Discord IPC endpoint and WebSocket server, and
  the connection to the real Discord client.
- Signed updates. Tauri's updater verifies the Ed25519 signature of every
  artifact against the public key in `tauri.conf.json`; there is no unsigned
  fallback.
- Open Original: decrypted media is served from memory on 127.0.0.1, never
  written to disk.

## What the shell cannot do the way Electron did

- **A window's own sound.** This shell does not yet bridge native application
  audio into the call. WKWebView's `getDisplayMedia` path returns screen/window
  video without audio, even though native ScreenCaptureKit supports audio.
  WebKitGTK also returns no display audio. Windows enables system sound
  through WebView2's picker. It reports whether the engine supports excluding
  the app's own sound, so older engines get the warning about call echo.
  The web requests `windowAudio: window` instead of system sound for a chosen
  window, but that depends on Windows and WebView2 versions and is not yet
  runtime-verified. The bridge still reports `windowAudio: false` everywhere,
  and `shareAudio: false` on Mac and Linux. Native window audio remains a
  feature gap. See WebKit's [display request](https://github.com/WebKit/WebKit/blob/5883f2415e42b41c3e62a67b35e80063b94cb1ce/Source/WebCore/Modules/mediastream/MediaDevices.cpp#L293-L319)
  and [screen-only output](https://github.com/WebKit/WebKit/blob/5883f2415e42b41c3e62a67b35e80063b94cb1ce/Source/WebCore/platform/mediastream/cocoa/ScreenCaptureKitCaptureSource.mm#L425-L431).
- **The page's own screen picker.** Electron enumerated sources and drew its own
  picker. Tauri has no such hook, so `getDisplayMedia` opens the system picker
  (WebView2's, macOS's, or the XDG portal's on Linux). Choosing what to share
  happens there.
- **A hidden call at full speed, exactly as before.** The shell now does what
  each engine allows: WebView2 is started with Chromium's background-throttling
  flags off, WKWebView is told not to suspend inactive pages (macOS 14 and
  newer), and the page holds a Web Lock during a call for engines without such
  a switch. On Linux without a Wayland session a call also keeps its window
  mapped off screen while hidden. These are background-call mitigations;
  sustained call and screen-sharing performance still needs native testing.
- **Moving an old copy into Applications.** New installs go to Applications
  through the DMG; there is no takeover of an Electron copy. Existing users
  replace the old app by hand. The two apps use different web engines, so the
  old app's local session and cached keys cannot be imported: signing in once
  in the new app downloads them again from the account.

## Building

Rust 1.82 or newer, and the platform's Tauri prerequisites
(https://tauri.app/start/prerequisites/): on Debian/Ubuntu
`libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libgtk-3-dev
libxdo-dev patchelf`; on Windows, the WebView2 runtime and the MSVC build
tools; on macOS, Xcode's command line tools.

```
cd desktop/src-tauri
cargo fmt --check
cargo check
cargo test
```

A local bundle (no update signatures, no publishing):

```
cd desktop/src-tauri
npx --yes @tauri-apps/cli@2 build --config tauri.nosign.conf.json
```

A local window pointed at a throwaway server:

```
NEXA_DESKTOP_URL=http://127.0.0.1:8081 npx --yes @tauri-apps/cli@2 dev
```

## Testing

```
cd desktop/src-tauri
cargo build --locked
cargo test --locked
python3 ../tests/smoke/run.py "$PWD/target/debug/nexa-desktop"
```

On Windows use `python` and `target/debug/nexa-desktop.exe` in a fresh test
account; the runner refuses an existing Nexa profile. Linux and macOS use
disposable home/config directories. The runner uses an ephemeral loopback port
(`PORT` overrides it). It starts
a local page that exercises the bridge, settings, screen-access reporting,
window state, update state, Rich Presence, strings, shortcuts, badge and call
mode, and checks that the webview exposes the media APIs calls need. It exits
non-zero on a missing step. A permission denial on a fresh Mac is a valid
screen-access report, not evidence that capture works.

The RPC checks connect two real external clients, one over Discord IPC (Unix
socket or Windows named pipe) and one over its local WebSocket. They check
READY, signed-in identity, activity acknowledgements, bridge state and activity
events, clearing one activity, disconnect cleanup and disabling/re-enabling
the listeners. The probes turn off upstream forwarding, so they do not prove
relay behavior with an installed Discord client. They need no Python packages.

Linux needs Xvfb, x11-utils, xdotool and a session bus. The runner sends a real
title-bar close request during call mode, checks that the page sees a hidden
window, and reopens it at its original position. It also starts call mode with
an already hidden window and checks that reopening restores it. The workflow
runs the native smoke and Rust tests on Linux, Windows, Intel Mac and Apple
Silicon Mac before building installers. This does not test a real media call,
screen capture, window audio or sustained background-call performance.

## Releases

`.github/workflows/desktop.yml` builds Windows (NSIS), Linux (AppImage and deb)
and both Macs (`.app` and `.dmg`) on GitHub's runners. A push to
`tauri-desktop-tests` or `desktop-native-capture` runs unsigned test builds
without creating a release. The workflow also typechecks the web app and runs
its media contract tests before building installers. Native-capture contract
tests are test-first work in progress, not evidence of an implemented backend.
Pushing a tag such as
`desktop-v2.0.0` also creates a draft GitHub release, signs the updater
artifacts, and attaches `latest.json`.

The updater key is not in Git. Add these repository secrets:

- `TAURI_SIGNING_PRIVATE_KEY`: the contents of the private key file (or its
  path, as Tauri accepts both).
- `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`: its password.

The matching public key is `plugins.updater.pubkey` in `tauri.conf.json`. A new
key must be generated with `npx @tauri-apps/cli signer generate` and the app
released once more; never replace a key that releases already trust.

Compile-time endpoint: the workflow sets `NEXA_UPDATER_ENDPOINT` to the current
repository's `releases/latest/download/latest.json`, so a fork updates from its
own releases. Without it the checked-in endpoint is used.

Deb users cannot install updates in place (the updater supports AppImage, NSIS
and macOS bundles); they get the release page instead.

## Cutover from Electron

Electron builds up to 1.2.x cannot install the Tauri app themselves. Existing
users download the new installer once. The old `latest*.yml` files and
installers in `DESKTOP_DIR` are left untouched for anyone still on 1.2.x; this
repository no longer builds them.
