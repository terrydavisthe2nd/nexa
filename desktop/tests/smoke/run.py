#!/usr/bin/env python3
"""Run the real desktop bridge on Linux, Windows or macOS with disposable settings."""
import ctypes
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import threading
import time

from server import Handler, HTTPServer, REPORT, ROOT


def close_x11_window(window, display_name):
    """Send the same WM_DELETE_WINDOW request as a title-bar close button."""
    class Message(ctypes.Structure):
        _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong),
                    ("send_event", ctypes.c_int), ("display", ctypes.c_void_p),
                    ("window", ctypes.c_ulong), ("message_type", ctypes.c_ulong),
                    ("format", ctypes.c_int), ("data", ctypes.c_long * 5)]

    class Event(ctypes.Union):
        _fields_ = [("message", Message), ("pad", ctypes.c_long * 24)]

    x11 = ctypes.CDLL("libX11.so.6")
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    x11.XInternAtom.restype = ctypes.c_ulong
    x11.XSendEvent.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int,
                              ctypes.c_long, ctypes.POINTER(Event)]
    x11.XFlush.argtypes = [ctypes.c_void_p]
    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    display = x11.XOpenDisplay(display_name.encode())
    if not display:
        raise RuntimeError("could not connect to X11")
    try:
        event = Event()
        event.message.type = 33  # ClientMessage
        event.message.display = display
        event.message.window = window
        event.message.message_type = x11.XInternAtom(display, b"WM_PROTOCOLS", 0)
        event.message.format = 32
        event.message.data[0] = x11.XInternAtom(display, b"WM_DELETE_WINDOW", 0)
        x11.XSendEvent(display, window, 0, 0, ctypes.byref(event))
        x11.XFlush(display)
    finally:
        x11.XCloseDisplay(display)


def main():
    binary = pathlib.Path(sys.argv[1]).resolve()
    env = os.environ.copy()
    linux = sys.platform.startswith("linux")
    if os.name == "nt" and pathlib.Path(env["APPDATA"], "xyz.evilion.chat").exists():
        raise RuntimeError("use a fresh Windows test account, not an existing Nexa profile")
    processes = []
    REPORT.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="nexa-desktop-smoke-") as scratch, \
            (ROOT / "app.log").open("w") as log:
        env["HOME"] = scratch
        for key in ("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "APPDATA", "LOCALAPPDATA"):
            env[key] = str(pathlib.Path(scratch, key))
            pathlib.Path(env[key]).mkdir()
        http = HTTPServer(("127.0.0.1", int(env.get("PORT", "0"))), Handler)
        threading.Thread(target=http.serve_forever, daemon=True).start()
        port = http.server_address[1]
        env["NEXA_DESKTOP_URL"] = "http://127.0.0.1:%s/" % port
        if linux:
            env["DISPLAY"] = env.get("DISPLAY", env.get("DISPLAY_NUMBER", ":99"))
            env.pop("WAYLAND_DISPLAY", None)
            env["WEBKIT_DISABLE_COMPOSITING_MODE"] = "1"
        try:
            if linux:
                if subprocess.run(["xdpyinfo"], env=env, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL).returncode:
                    processes.append(subprocess.Popen(
                        ["Xvfb", env["DISPLAY"], "-screen", "0", "1440x900x24"],
                        stdout=log, stderr=log))
                    for _ in range(50):
                        if subprocess.run(["xdpyinfo"], env=env, stdout=subprocess.DEVNULL,
                                          stderr=subprocess.DEVNULL).returncode == 0:
                            break
                        time.sleep(0.1)
                    else:
                        raise RuntimeError("Xvfb did not start")
                env["NEXA_DESKTOP_URL"] += "?window-test=1"
            command = [str(binary)]
            if linux:
                command = ["dbus-run-session", "--"] + command
            app = subprocess.Popen(command, env=env, stdout=log, stderr=log,
                                   start_new_session=os.name != "nt")
            processes.append(app)
            handled = set()
            for _ in range(1200):
                if app.poll() is not None:
                    raise RuntimeError("the app exited before finishing (code %s)" % app.returncode)
                try:
                    report = json.loads(REPORT.read_text())
                except (OSError, ValueError):
                    report = {}
                stage = report.get("stage")
                if stage == "done":
                    return subprocess.call([sys.executable, str(ROOT / "check.py")])
                if linux and stage in ("close-in-call", "hide-before-call") and stage not in handled:
                    native_pid = subprocess.check_output(
                        ["pgrep", "-P", str(app.pid), "-x", "nexa-desktop"], text=True).strip()
                    window = subprocess.check_output(
                        ["xdotool", "search", "--all", "--pid", native_pid, "--name", "^Nexa$"],
                        env=env, text=True).splitlines()[-1]
                    if stage == "close-in-call":
                        close_x11_window(int(window), env["DISPLAY"])
                    else:
                        subprocess.run(["xdotool", "windowunmap", window], env=env, check=True)
                    handled.add(stage)
                time.sleep(0.1)
            raise RuntimeError("the smoke page did not finish after 120 seconds")
        finally:
            http.shutdown()
            http.server_close()
            for process in reversed(processes):
                if process.poll() is not None:
                    continue
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                elif process is processes[-1] and "app" in locals() and process is app:
                    os.killpg(process.pid, signal.SIGTERM)
                else:
                    process.terminate()
                process.wait(timeout=10)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("desktop smoke failed:", error, file=sys.stderr)
        if (ROOT / "app.log").exists():
            print((ROOT / "app.log").read_text(errors="replace")[-6000:], file=sys.stderr)
        sys.exit(1)
