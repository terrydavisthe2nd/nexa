# Desktop screen sharing with sound

Research dates: 2026-10-29 and 2026-10-30. Status: native implementation under
qualification, with a verified Linux runtime blocker. Not a working release.

## Conclusion

Keep Tauri, but capture both pictures and sound natively. The owner's follow-up
explicitly rejects depending on browser screen capture. The original
audio-only bridge and retained system-video-picker proposal below is historical,
not the current implementation direction.

The recommended first transport experiment is native `libwebrtc` tracks handed
to the system webview through a same-computer WebRTC peer, with signaling over
Tauri commands. This does not bundle Chromium or use a LiveKit service. The
webview can then publish the received tracks through Nexa's existing encrypted
SFU/mesh path. It introduces an extra encode/decode stage, so actual installer
growth, CPU cost, latency and received quality are release gates. It is not
guaranteed to bind only loopback: this wrapper's native ICE defaults use host
interfaces, and its public Rust configuration exposes no loopback override.

Use `libwebrtc` desktop video on Windows/Linux, direct `screencapturekit` for
both Mac outputs, WASAPI process-loopback audio on Windows and
`libpulse-binding` direct playback-producer monitoring on Linux. Wayland's
selected visual source does not identify its audio application, so Linux needs
explicit sound selection rather than guessed window-title matching. Preserve
the existing Discord-compatible IPC/WebSocket server and relay; an outbound
Discord RPC client crate does not implement its role.

This is not yet proven as a complete Nexa feature. The same-host receiver
design cannot run on the stock Ubuntu WebKitGTK package described below.
The first milestone must be a real native sender and a separate receiver, not
another capability flag. Windows, macOS and Linux need separate backends and
separate evidence. Older OS versions, protected audio, audio-server policies
and sandbox restrictions prevent an honest promise of every machine or app.

## Verified Linux receiver blocker

Ubuntu's installed `webkit2gtk 2.52.6-0ubuntu0.24.04.1` was compiled with
`ENABLE_WEB_RTC` disabled. The official amd64 build log explicitly reports
`Disabling USE_GSTREAMER_WEBRTC since ENABLE_WEB_RTC is disabled` for both
GTK3 and GTK4. [Exact package build log](https://launchpad.net/~ubuntu-security-proposed/+archive/ubuntu/ppa/+build/33529899/+files/buildlog_ubuntu-noble-amd64.webkit2gtk_2.52.6-0ubuntu0.24.04.1_BUILDING.txt.gz).

A minimal GTK probe reproduced `typeof RTCPeerConnection === "undefined"`
even with `set_enable_webrtc(true)` applied before constructing the view.
Installing GStreamer bad/nice/libav plugins did not change that. The native
capture smoke fails at the same receiver boundary, before it can measure video
or audio. This is not evidence against the OS capture APIs themselves.

The public setting only changes a preference. The constructor is also guarded
by the compiled `WEB_RTC` feature. [WebKit 2.52.6 constructor gates](https://github.com/WebKit/WebKit/blob/4fb33923db2f945803df49546f75867980365c08/Source/WebCore/Modules/mediastream/RTCPeerConnection.idl#L79-L86).
There is no runtime setting that restores omitted code. A custom WebKit build
with experimental WebRTC enabled is not a proven or upstream-supported
replacement. Retaining stock Ubuntu WebKit requires a different native media
transport architecture, not more permission flags or a browser-capture fallback.

## Follow-up library source review

The implementation plan is
[`2026-10-29-native-desktop-capture.md`](superpowers/plans/2026-10-29-native-desktop-capture.md).
It is work in progress, not evidence of working native capture.

- Native peer/video candidate: `libwebrtc` manifest 0.3.55, inspected Git
  revision `ede8ac53843a8fb91d6f4fb5629f5245f1758e38`. Its SDK downloads are
  107 to 264 MB by target; those are not final application sizes. Linux's
  current C++ build requires clang 21 or newer. [Source](https://github.com/livekit/rust-sdks/tree/ede8ac53843a8fb91d6f4fb5629f5245f1758e38/libwebrtc).
- Mac: `screencapturekit` manifest 11.1.1, revision
  `673ba10e79c53022eaf597bcb1c762f22e6096be`, audio floor macOS 13. Directly
  retain the SCK filter: libwebrtc's Mac-picker source ID is synthetic, not a
  CGWindowID or selected application. [Dual-output example](https://github.com/doom-fish/screencapturekit-rs/blob/673ba10e79c53022eaf597bcb1c762f22e6096be/examples/03_audio_capture.rs).
- Windows: `wasapi` manifest 0.26.0, revision
  `4d58f98729195bdfb458e58368ca2a46eb787a23`. Its inspected read helpers do not
  honor the silent-packet flag before accessing the returned buffer. Do not
  adopt those helpers unchanged; handle silence and packet release explicitly
  through the raw capture client or a tested upstream fix. [Source](https://github.com/HEnquist/wasapi-rs/blob/4d58f98729195bdfb458e58368ca2a46eb787a23/src/api.rs).
- Linux: `libpulse-binding` manifest 2.30.1, revision
  `ee43edac89101bb8880ecbc5e295c0ad3a6941c8`. Use direct producer taps, not
  default-output loopback. [Binding contract](https://github.com/jnqnfe/pulse-binding-rust/blob/ee43edac89101bb8880ecbc5e295c0ad3a6941c8/pulse-binding/src/stream.rs#L1618-L1637).
- Inspected scap, xcap, crabgrab, scrcap and pinray implementations do not
  provide the required three-platform application-audio isolation contract.
  Several Windows/Linux paths record the whole default-output mix. A shared
  configuration flag is not evidence that each backend implements exclusion.

These version labels originally came from inspected source manifests. The
native implementation now pins dependencies in `desktop/src-tauri/Cargo.lock`.
Source review alone is not a successful build or capture test.

## Original repository snapshot, before the native branch

- `desktop/src-tauri/bridge.js` advertises sound on Windows only. Its
  `shareAudioMode` comes from recognition of `restrictOwnAudio`, not an audio
  isolation measurement. `windowAudio` is false everywhere.
- `desktop/src-tauri/src/capture.rs` checks screen-recording access and opens
  settings. `choose_display_source` is a no-op. It does not capture audio.
- `web/src/voice/media.ts:getScreen` requests audio, `restrictOwnAudio: true`
  and `windowAudio: "window"` from `getDisplayMedia`. It does not synthesize a
  missing audio track or bridge a native source. The comment about the desktop
  swapping loopback describes behavior that this Tauri shell does not have.
- `web/src/voice/sfu.ts` already publishes `screen_audio` from the screen
  stream's first audio track, uses the existing frame-encryption path, and
  receives share audio separately from the microphone.
- `web/src/voice/mesh.ts` already adds the screen stream's tracks to peers.
  Do not assume its audio tuning equals the SFU's: the current mesh uses the
  voice-oriented `tunedRemote` path, so stereo/music behavior needs its own test.
- `web/src/voice/opus.ts` defines the SFU share-audio target as stereo Opus,
  128 kbit/s, without voice DTX.
- `desktop/tests/smoke/index.html` checks media API presence and bridge
  metadata. It never obtains a real display stream or measures received audio.
  `.github/workflows/desktop.yml` runs that smoke on four OS/architecture jobs.
- `tools/share_quality.py` measures browser video sharing. Its own header
  explicitly excludes driving the Tauri sender. It is not native audio proof.
- The macOS installer currently accepts macOS 12.0. A newer capture API must
  be runtime-gated, not treated as available on every install.

The screenshot's concern is therefore supported by the current implementation:
media API availability and successful packaging do not establish screen-share
audio correctness.

## Why browser hints do not solve it

The Screen Capture specification permits returning video without audio even
when audio was requested. `windowAudio` and `systemAudio` are preferences that
the user agent may ignore. Recognizing `restrictOwnAudio` is not proof that a
specific call's playback was excluded. Its draft semantics concern the
capturing document, which is also narrower than a verified whole-application
exclusion policy. [1]

The same specification exposes a document-scoped surface identifier, not a
portable HWND, PID or macOS window ID. A native backend cannot reliably infer
the selected program from a track label, title matching or `displaySurface`.
Audio selection can also differ from video selection. [1]

The WebKit source revision already cited by the desktop README builds the
display request without audio constraints and registers ScreenCaptureKit's
screen output, not its audio output. That corroborates the documented WKWebView
gap for that revision; it is not a blanket statement about every future WebKit
release. [2]

## Native operating-system backends

### Windows: WASAPI process loopback

Use `ActivateAudioInterfaceAsync` with
`VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK`. The API includes a target **process
tree**, or captures other system audio while excluding that tree. It is not
bound to one render endpoint. A selected process tree with no audio produces
silence. Microsoft documents build **20348** in the dedicated structures and
official sample. Another API overview says **20438**, a documentation
discrepancy to record rather than conceal. Do not assume ordinary Windows 10
19041/19045 support from an old UI comment. Windows 11 is the sensible initial
client qualification baseline, with runtime activation/error handling. [8]

For application mode, include the selected rendering process tree. For
computer-minus-Nexa mode, exclude Nexa's tree only after proving that the real
WebView2 audio helpers belong to the excluded set, including after recreation.
The main process PID alone is not that proof.

`GetWindowThreadProcessId` identifies the window's creator, not necessarily its
audio renderer. A browser's other windows may be included, and a game renderer
launched outside the chosen tree may be missing. Selecting a common parent
blindly can capture unrelated siblings. Revalidate source identity and handle
PID reuse. Classic endpoint loopback cannot safely replace failed application
capture: it captures a broader mix. [8]

Edge 141 documents the newer browser sound options, but WebView2's documented
`ScreenCaptureStarting` event does not expose the chosen HWND/PID. It cannot
pair the webview's visual picker with this native audio backend. [9]

### macOS: CoreAudio process taps, with a distinct SCK option

For an audio-only bridge retaining WKWebView video, prototype **CoreAudio
process taps on macOS 14.2+** first. Apple's API supports stereo mixdown and
inclusion/exclusion lists. The sequence is: resolve audio process objects,
create a private tap, attach it to a private HAL aggregate device, start IO,
then stop IO and destroy both resources on disposal. Add
`NSAudioCaptureUsageDescription`; system-audio recording consent is separate
from screen-recording and microphone consent. [10]

The tap's process list contains **CoreAudio AudioObjectIDs**, not raw PIDs and
not an automatically recursive process tree. Resolve the actual renderer(s)
and watch process-object changes. `CATapDescription.isExclusive` means
everything except the listed processes, not exclusive hardware mode. Excluding
only Nexa's host PID is unsafe if WKWebView audio is rendered by a helper.
Do not exclude all generic WebKit helpers globally, since that may exclude
other apps. Reliable host/helper attribution and race-free exclusion remain
release gates. Start with selected external apps if broad exclusion cannot be
proved. [10]

**ScreenCaptureKit audio starts at macOS 13.0**, despite base video APIs being
older. Set `capturesAudio`, configure rate/channels, register audio output and
consume its sample buffers. `excludesCurrentProcessAudio` is useful, but still
needs the real WKWebView call-exclusion test. Apple's WWDC session explicitly
says one-window capture takes **all audio from that window's application**,
including its other windows. [11]

SCK is the documented option here for macOS 13.x, or if native SCK eventually
owns picture and sound together. Its native sharing picker starts at 14.0 and
is distinct from WKWebView's picker. Do not implement both Mac backends before
one passes the complete path. macOS 12 remains video-only under this proposed
driver-free policy; this need not raise the desktop shell's minimum OS.

### Linux: capture playback producers before the output mix

The inspected XDG ScreenCast portal interface, version 6, has no standardized
audio selection. Its PipeWire connection exposes the screencast session's
nodes only. **A video-portal PipeWire FD is not audio-graph permission.**
“Monitor” in the portal means a display, not an audio monitor source. [13]

Two driver-free native options exist:

- **Per-playback-stream libpulse monitors.** Enumerate sink inputs and use
  `pa_stream_set_monitor_stream` before connecting each recording stream to
  the corresponding sink monitor. Capture only the explicitly admitted
  application's playback inputs, normalize and mix those inputs. This avoids
  rerouting applications or changing the default output. The reviewed
  pipewire-pulse source also implements direct-input targeting. This is the
  smaller first Linux prototype across real PulseAudio and pipewire-pulse,
  provided visibility, identity and lifecycle tests pass. Use the full async
  API, not blocking libpulse-simple alone. [14]
- **Native PipeWire graph fan-out.** Watch playback producers and link only
  admitted output ports into a private session-owned mixer/capture stream,
  without moving their normal playback links. This provides direct graph
  control if Pulse compatibility access is inadequate. Keep links nonpersistent
  and disable automatic fallback/reconnection to arbitrary sources. This is
  an alternative backend, not a reason to build two before proving one. [15]

One physical-output monitor contains the already mixed call sound. Filtering
PIDs after that mix cannot remove it. Routing Nexa through a virtual sink that
then rejoins the monitored physical output does not fix that topology either.
AEC or signal subtraction is not a privacy-grade exclusion mechanism.

For application audio, admit only positively identified external playback
producers. For computer-minus-Nexa, every new producer must be classified
before it is linked. A startup PID list or the name `WebKitWebProcess` is not
enough: it may miss Nexa helpers or exclude another WebKit app. Launch tagging,
client identity and process lifetime can assist, but propagation across the
actual WebKit/GStreamer stack is unverified. Do not admit an aggregate effects
bus as if it were an original application: it may already mix Nexa audio. [16]

Real PulseAudio kills direct recording taps when the target playback stream
moves devices. Subscribe to changes, validate the selected identity and recreate
the tap on its new monitor. Never fall back to the default microphone or whole
output. Use fresh object identity after audio-server restart. PipeWire policy
may also move/fallback streams unless configured otherwise. [14,15]

Audio capture is independent of Wayland versus X11, but still depends on the
user's audio server and graph permissions. Direct ALSA-exclusive audio that
bypasses the server is not made capturable by selecting its window. deb and
AppImage need the actual user-session libraries/services. Flatpak would need
separate broad audio/socket/graph permissions; video-portal consent alone is
insufficient. Flatpak is a research boundary here, not a new packaging target
for this change. No root, kernel driver or persistent routing change should be
required for the ordinary supported desktop path. [13,15,17]

### Library selection is not integration proof

Inspected sources provide useful starting points, with important caveats:

- `wasapi-rs` implements process loopback, but at the reviewed revision its
  documentation describes `include_tree=false` incorrectly. The code chooses
  **EXCLUDE_TARGET_PROCESS_TREE**, meaning everything else, not target-only.
  Express include/exclude scope explicitly and test both with competing tones.
- `windows-capture` offers native video capture and accepts PCM for encoding;
  the reviewed audio encoder API is not a system-audio sample producer.
- `screencapturekit-rs` really forwards SCK audio buffers and exclusion settings,
  but brings a Swift bridge/toolchain and buffer-lifetime responsibilities.
  It does not turn those samples into a browser track or prove helper exclusion.
- A small Apple-API bridge is an option for taps; no tap-specific Rust wrapper
  was qualified by this research. Pin and audit whichever dependency is chosen.
- `libpulse-binding` exposes the async monitor/introspection APIs and is
  MIT/Apache-2.0; libpulse itself has separate LGPL obligations. `pipewire-rs`
  offers MIT bindings and a capture example. The reviewed OBS PipeWire app
  capture plugin demonstrates graph feasibility but is GPLv2: use it as an
  architectural reference, not code to copy into Nexa's MIT implementation.

These observations are pinned to inspected revisions, not claims that every
future release has the same behavior. [12,18]

## Product contract worth implementing

Offer sound scopes explicitly:

1. **No Sound.** Video capture only, with no native audio capture opened.
2. **Application Sound.** Sound from a selected application's audio streams,
   not necessarily one tab or one window in that application.
3. **Computer Sound, Excluding Nexa.** Only where the backend can maintain
   exclusion of every Nexa playback stream, including webview helper processes.

Keep the browser/OS picture picker and add an explicitly separate sound-source
selection initially. This is a deliberate compromise: one extra selection is
preferable to silently capturing the wrong app or pretending window-to-process
mapping is reliable. A unified picker is a later project requiring native
video capture or supported engine-specific selection integration too.

Never broaden application audio to the whole computer after an error. If the
source disappears, exclusion becomes uncertain, or permission is revoked,
stop sound and report that state. Continuing video without sound must be
visible to the user. Do not conflate legitimate silence with capture failure:
device state, callback liveness, queue progress and audio energy are different
signals.

## Shared capture-to-call path

```text
Native sound source, with explicit scope and exclusion
    -> bounded capture queue and format normalization
    -> Tauri raw-byte channel with application-level flow control
    -> transferable ArrayBuffers to an AudioWorklet
    -> bounded render queue and clock-drift correction
    -> MediaStreamAudioDestinationNode audio track
    -> screen MediaStream
    -> existing SFU frame encryption / peer-to-peer transport
    -> separate receiver's share-audio playback
```

Web Audio explicitly supports generating a MediaStream for transmission over
WebRTC with `MediaStreamAudioDestinationNode`. Nexa already uses this pattern
for processed microphone audio in `media.ts:sentStream`. [3]

Use Tauri channels, not high-frequency JSON events. Tauri documents channels
as the ordered streaming mechanism and supports raw `ArrayBuffer` delivery.
This was also checked against the locally installed, locked Tauri 2.12.5
`src/ipc/channel.rs`, not just newer documentation. [4]

### Transport and timing requirements

- Start with 48 kHz stereo float32 PCM, 10 or 20 ms blocks. This is 384,000
  PCM bytes/second before headers and copies, not a significant network stream
  because it stays on the device. Native layouts and sample rates still need
  conversion, including non-interleaved macOS buffers and 44.1 kHz devices.
- Include a session generation, sequence number, frame count and monotonic
  capture position. Reject stale-session and malformed packets.
- Bound both the native queue and unacknowledged IPC deliveries. A bounded
  native ring alone is insufficient: Tauri's channel implementation retains
  pending data for later JS fetches. `send()` is not playback backpressure.
- Replenish a finite send credit budget only as audio is consumed downstream.
  On a stalled page, discard stale capture blocks before sending, count the
  discontinuity, and recover near the current position. Never accumulate
  seconds of old audio or replay buffered private sound after resuming.
- Begin with a 40-80 ms render target and a hard 200 ms total application queue
  budget. These are proposed tuning values, not measured guarantees. Emit
  silence on underrun; count underruns/overruns and reset cleanly on a gap.
- Use transferable buffers without SharedArrayBuffer or a new cross-origin
  isolation requirement. Do not allocate or block on the real-time callback.
  Batching and acknowledgements must not depend on a foreground-only timer.
- Capture hardware and the AudioContext have different clocks. Use a streaming
  resampler and bounded occupancy correction, not only initial sample-rate
  conversion. Measure drift in a long run.
- Separate native sound and webview video do not automatically share capture
  timestamps. The destination track uses the Web Audio clock. Measure
  flash/click synchronization at the receiver; bounded audio latency is not a
  proof of lip sync. If this path cannot meet the sync budget, revisit paired
  native capture instead of claiming the timestamps solved it.
- Do not connect the generated source to the local speaker destination. That
  would duplicate the shared sound and may create a feedback loop. Do not pass
  music through the microphone's noise suppression, VAD, AGC or push-to-talk.

### Ownership, cleanup and security

Give each share an explicit idempotent disposer alongside its MediaStream.
Call it on Stop Sharing, hangup, channel switch, account switch, navigation,
permission loss, source failure and a share completed after the call ended.
Current `engine.ts` stop/hangup paths stop tracks directly. They must also
dispose native resources. Listening to `ended` alone is insufficient:
programmatic `MediaStreamTrack.stop()` does not dispatch that event. [5]

Scope capture sessions to the requesting webview and its approved origin.
Use backend-issued opaque source tokens, validate them again on start, and
never let a page select an arbitrary unreviewed process by supplying a PID.
OS permission alone is not per-share consent. A native source chooser or
equivalent user-approved source authorization must gate opening capture.

Native sessions must expire after lost ownership, page failure or stalled
consumption. Audio remains in memory, not logs, disk or a second server upload.
Keep commands in the existing Tauri ACL and avoid a public loopback PCM
endpoint, broad CORS or weakening `internal/api/static.go`'s CSP. The current
CSP disallows an arbitrary insecure `ws://127.0.0.1` bridge anyway.

Test the generated audio track through `e2ee.ts` on the actual webview. An
AudioWorklet constructor or `RTCRtpScriptTransform` property does not prove
that encoded audio reaches and passes the transform. Never disable SFU frame
encryption to make a backend appear to work. WebKitGTK's multimedia stack
depends on GStreamer, so WebKit and GStreamer versions both belong in evidence.
[6]

### Smallest useful bridge contract

Keep this an audio-capture boundary, not a second voice engine. The bridge
needs a capability query, an authorized source choice/start operation, bounded
PCM delivery with consumption acknowledgements, terminal/error state and an
idempotent stop. The implementation may combine choice and start in one native
operation. A session description needs its actual scope, exclusion policy,
sample format and source label, not just `shareAudio: true`.

The old static booleans may remain for old pages, but new pages should use the
versioned native operation only when present. New desktop binaries load a
remotely deployed web app, and old binaries continue loading new pages: test
both mismatched-version directions. Do not activate both browser audio and
native audio for the same share. For the native path, obtain video without
browser audio and attach exactly one native-generated audio track.

If native sound fails, keep its error distinct from picture-picker
cancellation and screen-recording denial. State transitions should distinguish
choosing, opening, active-but-silent, interrupted and stopped. A selected
application may be silent indefinitely without being broken. Recover only
within the originally authorized scope. A changed source or broader scope
requires another explicit choice.

## Alternatives and boundaries

- **Only adjust getDisplayMedia constraints:** smallest change, but cannot add
  an absent Mac/Linux audio backend, prove isolation or guarantee scope.
- **Return to Electron:** offers a more uniform Chromium integration surface,
  but is a shell reversal and not a universal audio fix. Electron's own docs
  describe macOS version/permission caveats and Linux picker differences. [7]
  Reconsider it if a native webview fails the actual encrypted audio gate, not
  as a claim that bundling Chromium makes every OS capture sound correctly.
- **CEF:** another large runtime and integration project, with native audio
  and Linux policy problems still to solve. Not the first intervention.
- **Virtual audio device:** a possible opt-in fallback on old systems, not the
  driver-free default. It adds installation, routing, permissions and support
  costs and does not itself guarantee call exclusion.
- **Native WebRTC for the entire call:** removes more webview dependencies but
  duplicates transport, playback, frame encryption and lifecycle ownership.
  Much larger than supplying the missing capture source.

## How to establish that it is real

Build a native media test mode beside the existing smoke harness. Launch the
actual packaged app against a throwaway Nexa server, with generated test
content and disposable accounts. Do not replace native capture with a fake
MediaStream in acceptance runs. Never use the live instance or its database.

### A discriminating signal fixture

Run three independent audio producers, not three sounds in the same process:

- The selected application plays 431 Hz on its left channel and 733 Hz on its
  right, with a timestamped click and visible flash periodically.
- An unrelated application plays 1297 Hz.
- A remote participant sends 1877 Hz through the call, which Nexa plays on the
  sender computer's selected output device.

Record the decoded **share-audio track** at the other participant, separately
from their ordinary voice playback. Detect the expected frequencies, stereo
separation, gaps, and flash/click timing. Also inspect the final receiver
playback path so a decoded-but-inaudible track cannot pass unnoticed.

Application scope must contain 431/733 Hz but not 1297/1877 Hz. Computer scope
must contain the selected and unrelated applications, but never 1877 Hz.
No Sound must carry none of those signals as share audio. Two windows in the
same selected application are a necessary control: an application-scoped
backend may legitimately capture both. It must not be advertised as per-window
or per-tab isolation.

Prove the excluded signals really played on the sender: retain a separate
diagnostic of Nexa's playback stream and the normal incoming call track.
Include a positive-control capture with exclusion deliberately disabled in
the synthetic harness, so each forbidden frequency is demonstrably detectable.
Exercise separated tone epochs as well as simultaneous tones. Trigger Nexa's
notifications and media previews after capture starts, including newly created
WebKit playback streams; excluding only the initial call stream is not enough.

Proposed initial acceptance budgets, to validate and freeze before release:
expected tones at least 25 dB above the measured noise floor; excluded tones
at least 45 dB below the selected reference; left/right separation at least
20 dB; audio/video offset between 80 ms audio-leading and 150 ms audio-lagging;
no capture-induced gap longer than 100 ms during a stable 30-minute run.
Calibrate detection against a known included and excluded source first.
These are proposed criteria, not achieved measurements. Run repeatable
loopback-network trials separately from intentional packet-loss trials.

Use a null output or otherwise physically muted test device so the test makes
no sound in the room while still exercising playback streams. Muting Nexa's
audio stream itself would invalidate the echo-exclusion test. Headphones and
an interactive listening pass are useful additional real-device checks.
Process exclusion does not eliminate acoustic feedback through an open mic;
test that separately with the normal microphone/AEC path.

### Matrix and failure cases

For each supported OS/backend and packaged architecture, exercise:

- Application and computer scopes, No Sound, a silent source that starts
  playing later, cancellation, permission denial and permission revocation.
- Microphone and share active together, microphone mute, server mute/deafen,
  participant join/leave, key rotation, share stop/restart and reconnect.
- Source exit/relaunch, helper-process creation, browser tabs, a game launcher
  spawning a different process tree, and duplicate window titles.
- Speaker/headphone/Bluetooth/USB output changes, output unplug, different
  sample rates and the selected application moving to another output device.
- Foreground, minimized, close-to-tray, another virtual desktop, screen lock,
  sleep/resume and at least 30 minutes of continuous sharing. Support after a
  system lock is a measured policy, not an assumption that capture continues.
- Hangup while the picture picker or sound authorization is pending, account
  switch, page reload and renderer failure. Check that the capture stops and
  native queues, handles and temporary routing resources are released.
- Main-thread stalls and exhausted IPC credits. Queues must remain bounded,
  native callbacks must not block and old samples must not replay afterward.
- A desktop native sender to web, native Android and another desktop receiver.
  Test both encrypted SFU and mesh paths. SFU tests need explicitly approved
  service credentials and traffic; local peer-to-peer tests do not prove SFU.

Preserve a machine-readable report containing commit, packaged artifact,
OS/build/architecture, webview and audio-server versions, device configuration,
selected scope, expected/detected tones, leakage, channel separation, offset,
queue maxima, drop counters and cleanup outcome. Keep synthetic receiver audio
and synchronized video as review artifacts. Do not record personal calls.

CI can cover signal processing, queue bounds, stop races, fake-backend tests,
and native capture against a controlled audio graph where the runner allows
it. A skipped native case is **not verified**, not a green capture result.
macOS consent/interactive sessions, actual Windows audio endpoints and Linux
compositor/audio-policy combinations also need maintained native test machines.
Xvfb plus constructor checks cannot certify them.

## Earlier audio-only implementation order (superseded)

The native-picture-and-sound plan linked above supersedes this original order.
The evidence and release gates remain applicable, but retaining browser video
is no longer an acceptable desktop implementation.

1. **Prove the bridge first.** Feed deterministic PCM from native code through
   bounded Tauri channels, the worklet and the actual encrypted SFU sender to
   a separate receiver on each webview. This is a transport experiment, not
   proof of OS capture. If a webview cannot carry the generated encrypted
   track, settle that before building three capture backends.
2. **Prove one genuine capture backend.** On an available native machine,
   capture the external selected application and pass the discrimination
   fixture, including call exclusion. Retain the video-only behavior elsewhere.
3. **Add the other native backends.** Each needs its own consent, source
   lifetime, helper-process and device-switch handling and evidence. Avoid
   bundling unvalidated fallbacks under one optimistic capability boolean.
4. **Integrate selection and lifecycle.** Extend `desktopBridge.ts`,
   `bridge.js`, the existing Tauri command registration/ACL, `media.ts` and
   `engine.ts`. Keep capture implementation in the desktop crate. Reuse the
   current transport and playback interfaces instead of creating another call
   engine. Introduce only the shared session ownership the native resources
   require. UI changes need the project's design/i18n and phone-parity process;
   Android needs receiver regression coverage, not desktop OS capture APIs.
5. **Gate release on evidence.** Run the real native matrix, preserve reports,
   then enable only verified scopes on supported configurations. Update the
   desktop documentation, CLAUDE.md, dependency licenses and What's New with
   the actual shipped behavior. No release/deployment is part of this research.

This Linux runner was used to inspect code and primary sources. No Windows or
Mac native capture, Linux desktop/audio-server capture, or real Nexa
share-audio call has been executed for this report. The recommended path is
not a declaration that those tests pass.

## Primary sources

1. [W3C Screen Capture, working draft](https://www.w3.org/TR/screen-capture/),
   especially Capturing Displayed Media, Constrainable Properties and
   DisplayMediaStreamOptions. Draft promises are not runtime support evidence.
2. WebKit source pinned by `desktop/README.md`:
   [MediaDevices.cpp](https://github.com/WebKit/WebKit/blob/5883f2415e42b41c3e62a67b35e80063b94cb1ce/Source/WebCore/Modules/mediastream/MediaDevices.cpp#L293-L319)
   and [ScreenCaptureKitCaptureSource.mm](https://github.com/WebKit/WebKit/blob/5883f2415e42b41c3e62a67b35e80063b94cb1ce/Source/WebCore/platform/mediastream/cocoa/ScreenCaptureKitCaptureSource.mm#L425-L431).
3. [W3C Web Audio](https://www.w3.org/TR/webaudio/), integration with WebRTC,
   AudioWorklet and MediaStreamAudioDestinationNode.
4. [Tauri calling the frontend](https://v2.tauri.app/develop/calling-frontend/),
   [JS core channel API](https://v2.tauri.app/reference/javascript/api/namespacecore/)
   and [locked Rust channel implementation](https://docs.rs/crate/tauri/2.12.5/source/src/ipc/channel.rs).
5. [W3C Media Capture and Streams](https://www.w3.org/TR/mediacapture-streams/),
   track life cycle and stop algorithm.
6. [WebKitGTK/WPE multimedia stack](https://docs.webkit.org/Ports/WebKitGTK%20and%20WPE%20WebKit/Multimedia.html).
7. [Electron desktopCapturer](https://www.electronjs.org/docs/latest/api/desktop-capturer).
8. [Microsoft application-loopback sample, pinned source](https://github.com/microsoft/Windows-classic-samples/tree/434f6002bdf9cf9829406c3ff2b33387982d6168/Samples/ApplicationLoopback),
   [process-loopback parameters](https://learn.microsoft.com/en-us/windows/win32/api/audioclientactivationparams/ns-audioclientactivationparams-audioclient_process_loopback_params),
   [activation overview with differing build number](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-activateaudiointerfaceasync),
   [window-to-process identity](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindowthreadprocessid)
   and [endpoint loopback](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording).
9. [Edge 141 web platform notes](https://learn.microsoft.com/en-us/microsoft-edge/web-platform/release-notes/141)
   and [WebView2 ScreenCaptureStarting event arguments](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2screencapturestartingeventargs).
10. Apple: [CoreAudio process-tap sample](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps),
    [AudioHardwareCreateProcessTap](https://developer.apple.com/documentation/coreaudio/audiohardwarecreateprocesstap(_:_:)),
    [process-object listing](https://developer.apple.com/documentation/coreaudio/kaudiohardwarepropertyprocessobjectlist),
    [PID translation](https://developer.apple.com/documentation/coreaudio/kaudiohardwarepropertytranslatepidtoprocessobject),
    [tap exclusion semantics](https://developer.apple.com/documentation/coreaudio/catapdescription/isexclusive)
    and [audio permission description](https://developer.apple.com/documentation/bundleresources/information-property-list/nsaudiocaptureusagedescription).
11. Apple: [SCK capturesAudio](https://developer.apple.com/documentation/screencapturekit/scstreamconfiguration/capturesaudio),
    [current-process exclusion](https://developer.apple.com/documentation/screencapturekit/scstreamconfiguration/excludescurrentprocessaudio),
    [capture sample](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-in-macos),
    [WWDC22 application-wide sound semantics at 4:10](https://developer.apple.com/videos/play/wwdc2022/10155/?time=250)
    and [native sharing picker](https://developer.apple.com/documentation/screencapturekit/sccontentsharingpicker).
12. Reviewed wrapper sources:
    [wasapi-rs include/exclude implementation](https://github.com/HEnquist/wasapi-rs/blob/4d58f98729195bdfb458e58368ca2a46eb787a23/src/api.rs#L759-L825),
    [windows-capture PCM encoder input](https://github.com/NiiightmareXD/windows-capture/blob/c7d106448eb9d9b251345c39047711e1cd408ae2/src/encoder.rs#L646-L655)
    and [screencapturekit-rs sample forwarding](https://github.com/doom-fish/screencapturekit-rs/blob/673ba10e79c53022eaf597bcb1c762f22e6096be/swift-bridge/Sources/ScreenCaptureKitBridge/Stream.swift#L365-L392).
13. [XDG ScreenCast interface, pinned version-6 source](https://github.com/flatpak/xdg-desktop-portal/blob/b3c1f8957e78c2d86f4e6af34ecc2f46aed945a3/data/org.freedesktop.portal.ScreenCast.xml#L108-L350).
14. PulseAudio: [per-stream recording API](https://github.com/pulseaudio/pulseaudio/blob/77d25a1e613095bdf87a1d13b65e7c330565077a/src/pulse/stream.h#L795-L804),
    [sink-input enumeration](https://github.com/pulseaudio/pulseaudio/blob/77d25a1e613095bdf87a1d13b65e7c330565077a/src/pulse/introspect.h#L646-L674),
    [moves kill direct record outputs](https://github.com/pulseaudio/pulseaudio/blob/77d25a1e613095bdf87a1d13b65e7c330565077a/src/pulsecore/sink-input.c#L1859-L1885)
    and [pipewire-pulse direct-input implementation](https://github.com/PipeWire/pipewire/blob/5009ec694a0a6ec6351eac787769a128b6a168e1/src/modules/module-protocol-pulse/pulse-server.c#L2095-L2145).
15. PipeWire: [capture example](https://github.com/PipeWire/pipewire/blob/5009ec694a0a6ec6351eac787769a128b6a168e1/src/examples/audio-capture.c#L130-L180),
    [WirePlumber target/fallback policy](https://github.com/PipeWire/wireplumber/blob/23664690cf86c79ed66d484418bbdaa64b9e6711/docs/rst/policies/linking.rst#L68-L133)
    and [OBS app-capture graph fan-out reference](https://github.com/dimtpap/obs-pipewire-audio-capture/blob/6b96268112390553958a6627870aca0857122365/src/pipewire-audio-capture-app.c#L326-L384).
16. [Pulse launch properties](https://github.com/pulseaudio/pulseaudio/blob/77d25a1e613095bdf87a1d13b65e7c330565077a/src/pulsecore/proplist-util.c#L116-L179)
    and [PipeWire application versus protocol-security identities](https://github.com/PipeWire/pipewire/blob/5009ec694a0a6ec6351eac787769a128b6a168e1/src/pipewire/keys.h#L34-L140).
17. [Flatpak sandbox and audio permissions](https://github.com/flatpak/flatpak-docs/blob/1017976c15fe847c48603de9301d873aeb77b197/docs/sandbox-permissions.rst#L4-L88)
    and [inspected WirePlumber Flatpak policy](https://github.com/PipeWire/wireplumber/blob/23664690cf86c79ed66d484418bbdaa64b9e6711/src/scripts/client/find-flatpak-access.lua#L12-L65).
18. [Rust Pulse binding API](https://github.com/jnqnfe/pulse-binding-rust/blob/ee43edac89101bb8880ecbc5e295c0ad3a6941c8/pulse-binding/src/stream.rs#L1618-L1637),
    [PipeWire Rust binding](https://gitlab.freedesktop.org/pipewire/pipewire-rs/-/blob/71022422eebd1bb2cb5a3259362819639fd29b58/README.md),
    [PulseAudio licensing](https://github.com/pulseaudio/pulseaudio/blob/77d25a1e613095bdf87a1d13b65e7c330565077a/LICENSE)
    and [OBS plugin GPLv2 license](https://github.com/dimtpap/obs-pipewire-audio-capture/blob/6b96268112390553958a6627870aca0857122365/LICENSE).
