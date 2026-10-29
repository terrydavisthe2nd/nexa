# Native Desktop Capture Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan. Steps use checkbox syntax for tracking.

**Goal:** Capture desktop pictures and application sound natively on Windows, macOS and Linux, retain Tauri and working Rich Presence, and qualify the result with actual received media rather than capability flags.

**Architecture:** Native OS capture feeds native WebRTC video and audio sources. A private local peer connection hands tracks to the system webview, which keeps Nexa's existing SFU/mesh publication and frame encryption. This adds a media library, not a bundled browser, but adds a local encode/decode stage whose size, latency and CPU cost must be measured before release.

**Tech Stack:** Rust, LiveKit's standalone libwebrtc bindings (no LiveKit service), ScreenCaptureKit, WASAPI process loopback, PulseAudio direct playback-stream monitoring, Tauri 2, TypeScript.

**Spec:** The owner's native-capture request in this thread supersedes the browser-video-first proposal in `docs/desktop-share-audio-research.md`.

## Global Constraints

- No Electron, bundled Chromium, or desktop `getDisplayMedia` fallback.
- Never broaden a failed application-audio capture into whole-output recording.
- Audio scopes are applications/process trees, not individual windows' sound.
- No production server, release tag, deployment, signing-key change or live build.
- Build and test using GitHub Actions as requested. Publishing the new code to a test branch needs scoped push authorization; dispatching a workflow against unchanged remote code cannot validate local changes.
- Preserve the existing Discord-compatible IPC/WebSocket server and relay. An outbound RPC client crate is not a replacement.
- Native desktop-only capture has no corresponding Android capture implementation; both clients must continue receiving the existing wire format unchanged.

## Review Focus

- Consent cancellation, a closed source and a hung-up call must stop native recording and local peers.
- An audio initialization failure must fail the requested share, not claim success with silent sound or a broader mix.
- Incoming call sound must not feed back into a share; helper-process playback requires real platform qualification.
- Backpressure must bound queued frames and audio, and stopping must not deadlock a media callback.
- Existing SFU and mesh receivers must receive actual pictures and stereo sound through the existing encryption path.

## Task 1: Real RPC qualification

- [ ] Extend the native smoke driver to act as a real IPC and WebSocket game client.
- [ ] Check READY, SET_ACTIVITY acknowledgements, bridge activity events, activity clearing and disconnect cleanup.
- [ ] Preserve origin rejection and existing window/settings smoke checks.
- [ ] Run the cross-platform smoke matrix in Actions, not just socket-presence assertions.

## Task 2: Native media handoff and lifecycle

Files: `desktop/src-tauri/src/capture/`, `desktop/src-tauri/bridge.js`, `desktop/src-tauri/src/{main,state,window}.rs`, `desktop/src-tauri/permissions/desktop.toml`, `web/src/lib/desktopBridge.ts`, `web/src/voice/{media,engine}.ts`.

- [ ] Add tests for native track delivery, no browser fallback on failure, audio-off behavior, concurrent/cancelled starts and explicit native disposal on hangup.
- [ ] Add a session-scoped native source selector, bounded capture worker and authenticated command-only peer signaling. Do not expose a listening media HTTP endpoint.
- [ ] Feed the webview's received tracks into the existing `getScreen` contract and retain browser capture only outside the native desktop bridge.
- [ ] Stop recording on explicit stop, page unload/navigation, peer failure and timed-out startup.
- [ ] Qualify actual frame/audio delivery with a native sender and a separate receiving peer in Actions.

## Task 3: Platform capture

- [ ] Source-check and pin capture dependencies, including platform version floors.
- [ ] Windows: native display/window video and WASAPI process-tree audio, with no endpoint-loopback fallback.
- [ ] macOS: ScreenCaptureKit native video/audio, macOS 13+ audio gate, matching native display/window identifiers and explicit capture consent.
- [ ] Linux: native X11/portal video and admitted external playback-stream monitoring; stop/revalidate moved or replaced streams and refuse unknown ownership.
- [ ] For each platform exercise chosen-app stereo tones, unrelated-app tone and incoming-call tone, plus inclusion positive controls and stop/restart cleanup. Record unsupported/permission-restricted environments as unverified, never passing captures.

## Task 4: Delivery gates

- [ ] Add Actions jobs and retain machine-readable results, logs and representative native captures.
- [ ] Run Rust tests/checks, web tests/typecheck and unsigned platform builds; fix failures from actual logs.
- [ ] Measure installer growth and local-bridge latency/CPU; reject an architecture that defeats the lightweight requirement.
- [ ] Regenerate dependency notices, update desktop documentation and `CLAUDE.md`, and add localized release notes only once the feature is implemented.
- [ ] Report precisely what passed and what still needs real OS/hardware qualification. Do not ship or deploy unqualified media behavior.
