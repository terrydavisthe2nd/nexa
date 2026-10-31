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
    src/capture.rs    screen-recording permission and native capture entry
    src/capture/      same-host media peer and OS video/application sound
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
  artifact against the owner-approved public key baked into the build; there
  is no unsigned fallback. Updates stay disabled until that key is configured.
- Open Original: decrypted media is served from memory on 127.0.0.1, never
  written to disk.

## Native screen sharing (qualification branch)

`desktop-native-capture` implements OS video and selected-application sound,
handed to the page over a same-host libwebrtc peer. It does not bundle Chromium,
use a LiveKit service, or fall back to browser screen capture. The page keeps
Nexa's existing call encryption and publication. ICE uses no STUN/TURN, but host
candidates can use LAN interfaces, not only loopback.

Windows uses native desktop capture and WASAPI process-tree audio. macOS uses
ScreenCaptureKit and requires macOS 13. Linux uses native X11/portal video and
an explicitly selected PulseAudio or pipewire-pulse playback producer. Picture
and sound are separate choices. Sound is application/producer scoped, not
per-window; it never falls back to the whole output or microphone. Real-media
qualification, helper exclusion and installer-size measurements remain release
gates. This branch is not a released or deployed feature.

The same-host receiver does not run on stock Ubuntu WebKitGTK 2.52.6, which has
WebRTC compiled out. The Linux media smoke currently fails at that boundary.
Runtime preferences and GStreamer plugins cannot restore the missing peer.
See the [verified blocker and package evidence](../docs/desktop-share-audio-research.md#verified-linux-receiver-blocker).

## Differences in the previously released shell

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
libxdo-dev patchelf`, Clang 21 and the native-media development packages listed
in `.github/actions/native-media/action.yml`; on Windows, the WebView2 runtime
and the MSVC build tools; on macOS, Xcode 26 for the pinned Swift/Metal bridge.
The desktop's `.cargo/config.toml` applies libwebrtc's platform linker flags.

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

The owner-approved matching Tauri public key is the repository variable
`NEXA_UPDATER_PUBLIC_KEY`, baked into the binary at compile time. The release
workflow also writes it into a temporary Tauri packaging config. The checked-in
public key is empty; without the build-time variable the app cannot check,
download or install updates and does not poll a feed. Never generate, replace
or rotate signing material without explicit owner authorization. Verify the
private/public pair using a signed artifact before enabling releases.

The feed is fixed to
`https://github.com/terrydavisthe2nd/nexa/releases/latest/download/latest.json`.
Runtime endpoint and key overrides are ignored. Forks must make an explicit
source change to establish their own release trust.

Migration status and the Linux/signing handoff are in [CLAUDE.md](CLAUDE.md)
and the [PR review record](../docs/desktop-tauri-review.md).

Deb users cannot install updates in place (the updater supports AppImage, NSIS
and macOS bundles); they get the release page instead.

## Cutover from Electron

Electron builds up to 1.2.x cannot install the Tauri app themselves. Existing
users download the new installer once. The old `latest*.yml` files and
installers in `DESKTOP_DIR` are left untouched for anyone still on 1.2.x; this
repository no longer builds them.
