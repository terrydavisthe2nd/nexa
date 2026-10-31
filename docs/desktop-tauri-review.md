# Desktop Tauri PR review record

Reviewed PR: https://github.com/terrydavisthe2nd/nexa/pull/1
Reviewed head: `49afdd5b33a8fe367f5271d651fb263a20169ea9`.
Follow-up branch: `nexa-desktop-new`. Resume instructions: `../desktop/CLAUDE.md`.
This is an unfinished migration branch, not a release approval. No clear intentional
malware was identified; source review cannot certify absence of malicious behavior.

## What the PR provides

A full Electron-to-Tauri 2 desktop rewrite, native screen/application-audio capture,
ported tray/shortcuts/notifications/Discord Rich Presence, and GitHub Actions installer
and signed-update delivery. It changes the release process and requires a manual
Electron-to-Tauri installation and a fresh sign-in. Existing local sessions/cached keys
are not imported from Electron.

## Findings and current status

1. **P1, download path traversal: fixed in this branch.** `window.rs` decoded the final
   URL segment and passed it directly to `Path::join`. For example,
   `..%2FLibrary%2FLaunchAgents%2Fexample.plist` became a path outside Downloads.
   The review reproduced path construction without writing a payload, not a native
   end-to-end exploit. `downloads.rs` now validates the decoded basename, uses a safe
   fallback, skips occupied names/dangling links and refuses collision exhaustion.
   Native Save As behavior required by root guidance remains follow-up work.
2. **P1, updater trust: contributor default removed, owner setup pending.** The PR's
   default pointed to `hnrie/nexa` and introduced a public signing key of unverified
   ownership. This was not proof of malicious intent. This branch uses only the
   canonical `terrydavisthe2nd/nexa` feed and a build-time, owner-approved
   `NEXA_UPDATER_PUBLIC_KEY`. No runtime override is accepted. Until the owner supplies
   the public key, updates and background polling are disabled. Signed release jobs
   also require the private secret; the pair still needs artifact-signature proof.
   No private key was read, generated, changed or uploaded by these fixes.
3. **P1, Linux receiver: unresolved.** The PR records stock Ubuntu WebKitGTK 2.52.6
   compiled without WebRTC, while the handoff requires `RTCPeerConnection` and the
   bridge advertises capture unconditionally. Preferences/plugins cannot restore
   omitted engine code. Complete a supported receiver architecture and pass the real
   media gate before replacing the released desktop client. See
   `desktop-share-audio-research.md`, section "Verified Linux receiver blocker".
4. **Qualification gaps: unresolved.** Actual Windows/macOS capture/audio-isolation,
   packaged helper exclusion, encrypted remote calls, installer size, CPU, latency
   and sustained background-call measurements were not established. Linux probes
   do not qualify other OSes. The desktop README contains stale capture descriptions
   that must be reconciled when the implementation is completed.
5. **Integration state at review:** GitHub reported conflicts, reproduced with
   `git merge-tree` against main `48d4040b9b41b2d36cb82bf247fbfbd31e237518`, in all
   twelve locale files. No head-commit CI check runs/status results were available.
   These are historical observations; recheck current main and CI when integrating.

## Verification limits

The original review passed web typechecking, 30 targeted tests across five files,
Rust formatting, translation consistency, tracked-file privacy checks and diff
whitespace checking. `cargo check --locked --offline` could not obtain the pinned
libwebrtc dependency. Full native compilation, installers and runtime media were
not verified. The original PR was neither approved nor merged. No deployment ran.

Security-fix validation on `nexa-desktop-new` passed seven dependency-free Rust tests:
four for hostile decoded names, ordinary names/collisions, dangling symlinks and
collision exhaustion; three for missing/blank key rejection, configured-key handling
and canonical endpoint selection. Web typechecking and all 30 targeted tests passed
again, as did Rust formatting, translations, diff whitespace and the staged privacy
check. Full native `cargo check --locked --offline` remains blocked by uncached
libwebrtc. No native runtime or signature/installer qualification is claimed.

The release-workflow YAML and generated public-key config were checked locally with
non-secret fixture values; no Actions workflow or release was dispatched. Do not infer
successful platform qualification from unit tests or formatting checks.
