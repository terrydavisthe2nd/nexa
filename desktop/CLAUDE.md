# Desktop Tauri migration handoff

Read the root `../CLAUDE.md` first. This file is the entry point for a future request to
"complete the Tauri migration for desktop using the branch".

## Branch and scope

Resume `nexa-desktop-new`, which starts at PR #1 head
`49afdd5b33a8fe367f5271d651fb263a20169ea9` and includes the review fixes. Preserve unrelated
main work and use a separate worktree. Do not rebuild this migration from scratch or
silently discard either side's changes. The original PR had merge conflicts in all twelve
locale files; reconcile them with current main and rerun translations when integrating.
Do not merge, push, deploy, create a release tag or publish installers unless the owner asks.

`../docs/desktop-tauri-review.md` records only the findings from the PR review, their fix
status and the validation limits. `README.md` describes the shell and release workflow.
`../docs/desktop-share-audio-research.md` contains platform/source evidence.
`../docs/superpowers/plans/2026-10-29-native-desktop-capture.md` is the unfinished plan;
its unchecked tasks are not proof that nothing was implemented. Inspect the current code.

## Security fixes to retain

- `src-tauri/src/downloads.rs` validates already-decoded URL basenames on every OS.
  Separators, absolute/parent paths, control characters and Windows-special names fall back
  to `download`. It skips existing entries, including dangling symlinks, and refuses an
  exhausted collision range. `window.rs` uses it before accepting a download. Keep tests
  for decoded slash/backslash traversal and collision behavior. The review reproduced the
  path calculation, not an end-to-end native exploit. The original Save As requirement
  still needs a native flow and cancellation tests before completing the migration.
- `src-tauri/src/update_trust.rs` fixes the feed to the owner's canonical repository.
  `updater.rs` requires `option_env!("NEXA_UPDATER_PUBLIC_KEY")` before constructing an
  updater. Missing/blank key disables updates and background polling. Runtime environment
  variables cannot change the endpoint or trust anchor. Never restore the contributor's
  fork URL or unverified key, or an unsigned update fallback.

## Linux blocker and completion work

The selected same-host WebRTC handoff requires `RTCPeerConnection` in the system webview.
The reviewed Ubuntu WebKitGTK 2.52.6 package has WebRTC compiled out. Runtime preferences
and extra GStreamer packages cannot restore missing compiled code. The native smoke gate
is expected to fail there; it must not be skipped or marked passing as a workaround.

Complete a working Linux receiver architecture compatible with the supported packaged
runtime. Reevaluate the handoff if stock WebKitGTK is retained; a custom WebKit build has
not been qualified. Preserve native screen/application-audio capture and Nexa's existing
SFU/mesh wire format and frame encryption. Do not silently fall back to microphone or
whole-output audio when selected-application recording fails. Capability flags alone are
not evidence of working media. The current bridge advertises support unconditionally,
which must be reconciled with actual runtime support.

Before calling the migration complete, prove received video, stereo sound, unrelated-app
and Nexa/helper exclusion, stop/restart, audio-off, permission denial and hangup cleanup.
Exercise Linux X11 and Wayland, Windows and both Macs, including packaged apps and a real
encrypted remote call. Measure installer size, CPU, latency and sustained background-call
performance. The Linux probes do not qualify other platforms or packaged helper ownership.
Retain machine-readable smoke results and fix the stale README capture descriptions.
Add the major-feature What's New entry in every locale once behavior is implemented and
qualified, following the root guidance. Keep Android receiving the same encrypted media.

## Signing and cutover

There is intentionally no trusted public key committed in `tauri.conf.json` yet. An
unsigned/local qualification build works with updates disabled. Before a signed release:

1. Have the owner supply/approve the Tauri minisign public key as repository variable
   `NEXA_UPDATER_PUBLIC_KEY`. Local trusted builds pass the same variable at compile time.
2. Use the matching private key in repository secret `TAURI_SIGNING_PRIVATE_KEY` and its
   password in `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`. Never print, commit or upload private
   material to this repository. The release workflow requires the public variable and
   private secret and supplies a temporary packaging config containing the public key.
3. Verify a produced updater artifact's signature against that public key and test that
   a modified artifact or another key is rejected. Confirm the published manifest points
   to the intended canonical release. Key presence alone does not prove a matching pair.
4. Keep the key stable once users trust it. Generation, replacement, rotation, secret
   uploads and publishing require the owner's corresponding authorization. No signing
   material was generated, rotated or accessed during the review fix.

The released Electron trust anchor is in main's `desktop/src/main/update/releaseKey.ts`.
Its existing private PEM path was documented as `~/.local/share/nexa-signing/update-key.pem`
on the owner's Mac and build server, with `NEXA_DESKTOP_SIGNING_KEY` as a path override.
These are historical location hints, not a claim that the key was inspected or is present.
Do not expose it or assume a PEM file is a Tauri minisign secret. Resolve format/key
ownership with the owner before enabling releases; never regenerate an unavailable key.

Electron 1.2.x cannot install this Tauri rewrite automatically. Plan a trusted manual
installer cutover; users sign in again because the web engine/profile changes. Preserve
old Electron feeds/installers for existing copies. Updater artifact signing and native
Windows/macOS installer signing are separate concerns; do not claim OS notarization or
certificate signing merely because Tauri updater signatures work.

## Validation and safe commands

From `src-tauri`, `cargo fmt --check`, `cargo check --locked` and `cargo test --locked`.
For dependency-free regression proof from the repository root:

```
rustc --edition=2021 --test desktop/src-tauri/src/downloads.rs -o /tmp/nexa-download-tests
/tmp/nexa-download-tests
rustc --edition=2021 --test desktop/src-tauri/src/update_trust.rs -o /tmp/nexa-trust-tests
/tmp/nexa-trust-tests
```

The native runner is `python3 desktop/tests/smoke/run.py <scratch debug binary>`; read
its platform/profile requirements first. Use the Actions matrix for each real platform.
Web checks are `npm run typecheck` and the targeted desktop/media/engine Vitest suites.
Run `python3 tools/i18n_check.py` and `python3 tools/privacy_check.py` too.
Use only isolated scratch server builds and data. Do not use port 8090, live `chatd.env`,
`data/`, or a build command that deploys. No live instance or native capture was tested
in the original review, and the offline full Cargo check stopped on an uncached libwebrtc
dependency. Consult the review document for exact completed checks.
