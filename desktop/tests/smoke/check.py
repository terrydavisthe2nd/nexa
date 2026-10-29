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
for key in ("getDisplayMedia", "getUserMedia", "mediaRecorder", "audioContext", "worklet"):
    want(media.get(key) == "function", "media API %s is missing: %r" % (key, media))
want(media.get("secure") is True, "the app did not load a secure context")
if report.get("platform") == "win32":
    want(media.get("shareAudio") is True, "the Windows bridge disabled shared audio")
    own_audio = (media.get("supportedConstraints") or {}).get("restrictOwnAudio") is True
    want(media.get("shareAudioMode") == ("own" if own_audio else "all"),
         "the Windows bridge misreported whether the call's own sound is excluded")
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
