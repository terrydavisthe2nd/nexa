#!/usr/bin/env python3
"""Asserts what the shell reported from desktop/tests/smoke/index.html."""
import json
import pathlib
import sys

report = json.loads((pathlib.Path(__file__).resolve().parent / "report.json").read_text())
problems = []


def want(condition, message):
    if not condition:
        problems.append(message)


want(report.get("stage") == "done", "the page stopped at %r" % report.get("stage"))
want(report.get("bridge") is True, "window.evilionDesktop is missing")
want(not report.get("errors"), "the page reported: %s" % report.get("errors"))
want(str(report.get("version", "")).startswith("2."), "version is %r" % report.get("version"))
settings = report.get("settings") or {}
for key in ("launchAtLogin", "startMinimized", "closeToTray", "hardwareAcceleration"):
    want(key in settings, "settings has no " + key)
want((report.get("settingsAfter") or {}).get("closeToTray") is True, "settings did not save")
expected_partial = {"launchAtLogin": False, "startMinimized": False,
                    "closeToTray": False, "hardwareAcceleration": True}
want(report.get("partialSettings") == expected_partial,
     "the single-setting patch did not return the current settings")
want(report.get("settingsAfterPartial") == expected_partial,
     "the single-setting patch changed unrelated settings or was not saved")
access = report.get("screenAccess") or {}
want(access.get("status") in ("granted", "denied", "not-determined", "restricted", "unsupported"),
     "screen access has an unknown status: %r" % access)
want(isinstance(access.get("usable"), bool), "screen access has no usability decision")
media = report.get("media") or {}
for key in ("captureScreen", "stopScreen", "peerConnection", "getUserMedia", "mediaRecorder", "audioContext", "worklet"):
    want(media.get(key) == "function", "media API %s is missing: %r" % (key, media))
want(media.get("secure") is True, "the app did not load a secure context")
want(media.get("shareAudio") is True and media.get("shareAudioMode") == "application",
     "native sound must require a separately selected application")
if report.get("platform") == "linux":
    capture = report.get("nativeCapture") or {}
    want(capture.get("red", 0) > 500 and capture.get("blue", 0) > 500,
         "native video did not deliver the displayed red/blue pattern")
    want(capture.get("stopped") is True and capture.get("audioOff") is True,
         "native stop/restart or audio-off did not work")
    selected = capture.get("selected") or [{}, {}]
    want(selected[0].get("431", 0) > .04 and selected[1].get("863", 0) > .04,
         "native application sound did not preserve both stereo tones")
    want(selected[0].get("863", 1) < .025 and selected[1].get("431", 1) < .025,
         "native application sound mixed the stereo channels")
    want(all(channel.get("1291", 1) < .01 and channel.get("1709", 1) < .01 for channel in selected),
         "native application sound leaked another app or Nexa playback")
    control = capture.get("unrelatedControl") or [{}, {}]
    want(all(channel.get("1291", 0) > .04 for channel in control),
         "the unrelated-app exclusion had no positive control")
window = report.get("window") or {}
want(window.get("visible") is True, "the window is not visible")
rpc = report.get("rpc") or {}
want(
    "enabled" in rpc and "listening" in rpc,
    "rpc state has the wrong shape: %r" % rpc,
)
# The relay must come up on its own: a page that can only see it after a click
# is useless to Rich Presence. listening is {ipc, ws}; on a healthy start both
# are set, so a None on either is the bug where the relay stayed down.
listening = rpc.get("listening") or {}
want(
    isinstance(listening, dict) and bool(listening.get("ipc")) and bool(listening.get("ws")),
    "rpc must be listening on both the Discord IPC socket and the local WebSocket: %r"
    % listening,
)
ipc_name = str(listening.get("ipc", ""))
if report.get("platform") == "win32":
    want(
        ipc_name.startswith("\\\\?\\pipe\\"),
        "on Windows the IPC socket must be a named pipe, got %r" % ipc_name,
    )
else:
    want(
        ipc_name.startswith("/"),
        "on Unix the IPC socket must be a filesystem path, got %r" % ipc_name,
    )

integration = report.get("rpcIntegration") or {}
expected_activities = {
    "123456789012345678": {"application_id": "123456789012345678", "type": 0,
                           "name": "IPC Smoke Atlas", "details": "Exploring north", "state": "Party of 2"},
    "234567890123456789": {"application_id": "234567890123456789", "type": 0,
                           "name": "WebSocket Smoke Beacon", "details": "Building south", "state": "Room of 7"},
}
for phase in ("published", "beforeDisable", "afterEnable"):
    evidence = integration.get(phase) or {}
    clients = (evidence.get("wire") or {}).get("clients") or []
    want({client.get("transport") for client in clients} == {"ipc", "ws"},
         "RPC %s did not handshake over both external transports" % phase)
    apps = (evidence.get("state") or {}).get("apps") or []
    activities = evidence.get("activities") or []
    want(len(apps) == 2 and len(activities) == 2, "RPC %s did not expose both activities" % phase)
    for client_id, expected in expected_activities.items():
        client = next((client for client in clients if client.get("clientId") == client_id), {})
        ready = client.get("ready") or {}
        want(ready.get("cmd") == "DISPATCH" and ready.get("evt") == "READY",
             "RPC %s %s did not receive READY" % (phase, client_id))
        user = (ready.get("data") or {}).get("user") or {}
        want(all(user.get(key) == value for key, value in {
            "id": "345678901234567890", "username": "smoke-rpc", "global_name": "Smoke RPC",
        }.items()), "RPC %s %s lost the bridge identity" % (phase, client_id))
        ack = client.get("ack") or {}
        want(ack.get("cmd") == "SET_ACTIVITY" and ack.get("evt") is None and bool(ack.get("nonce")),
             "RPC %s %s did not acknowledge SET_ACTIVITY" % (phase, client_id))
        app = next((app for app in apps if app.get("clientId") == client_id), {})
        event = next((a for a in activities if a.get("application_id") == client_id), {})
        for label, actual in (("ack", ack.get("data") or {}),
                              ("state", app.get("activity") or {}), ("event", event)):
            want(all(actual.get(key) == value for key, value in expected.items()),
                 "RPC %s %s %s changed the asymmetric activity: %r" % (phase, client_id, label, actual))

cleared = integration.get("cleared") or {}
clear_ack = (cleared.get("wire") or {}).get("ack") or {}
want(clear_ack.get("cmd") == "SET_ACTIVITY" and clear_ack.get("nonce") == "clear-ipc" and
     clear_ack.get("evt") is None and clear_ack.get("data") is None,
     "RPC clear was not acknowledged")
clear_apps = (cleared.get("state") or {}).get("apps") or []
ipc_app = next((app for app in clear_apps if app.get("clientId") == "123456789012345678"), {})
want(len(clear_apps) == 2 and bool(ipc_app) and ipc_app.get("activity") is None,
     "RPC clear removed the connected IPC app or retained its activity")
want(cleared.get("activities") == [expected_activities["234567890123456789"]],
     "RPC clear did not remove only the IPC activity from onActivities")
for phase in ("disconnected", "finished"):
    evidence = integration.get(phase) or {}
    want((evidence.get("state") or {}).get("apps") == [] and evidence.get("activities") == [],
         "RPC %s retained disconnected clients or their activities" % phase)
disabled = integration.get("disabled") or {}
want(disabled.get("enabled") is False and disabled.get("apps") == [] and
     disabled.get("listening") == {"ipc": None, "ws": None},
     "RPC disable did not stop listeners and remove active clients")
reenabled = integration.get("reenabled") or {}
want(reenabled.get("enabled") is True and bool((reenabled.get("listening") or {}).get("ipc")) and
     bool((reenabled.get("listening") or {}).get("ws")), "RPC re-enable did not restart both listeners")

update = report.get("update") or {}
want(update.get("status") in ("idle", "checking", "ready", "downloading", "error", "manual"),
     "update state is %r" % update.get("status"))
shortcuts = report.get("shortcuts") or []
want(any(item.get("action") == "mute" and item.get("ok") for item in shortcuts),
     "the mute shortcut did not register: %r" % shortcuts)

if "positionBefore" in report:
    want((report.get("closedInCall") or {}).get("visible") is False,
         "closing a call did not hide the window from the user")
    want((report.get("reopenedInCall") or {}).get("visible") is True,
         "the call window did not reopen")
    want((report.get("hiddenInCall") or {}).get("visible") is False,
         "starting a hidden call made the window logically visible")
    for key in ("positionAfterClose", "positionAfterHidden"):
        want(report.get(key) == report["positionBefore"],
             "reopening the call left the window at %r instead of %r" %
             (report.get(key), report["positionBefore"]))

if problems:
    print("\n".join("FAIL: " + problem for problem in problems))
    sys.exit(1)
print("desktop smoke test passed")
