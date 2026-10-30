#!/usr/bin/env python3
"""Run the real desktop bridge on Linux, Windows or macOS with disposable settings."""
import base64
import ctypes
import hashlib
import json
import math
import os
import pathlib
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import wave

from server import Handler, HTTPServer, REPORT, ROOT


class NamedPipe:
    """A real Win32 byte pipe, with bounded reads rather than blocking forever."""
    def __init__(self, path):
        from ctypes import wintypes as w
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, w.LPVOID,
                                        w.DWORD, w.DWORD, w.HANDLE]
        self.api.CreateFileW.restype = w.HANDLE
        self.api.PeekNamedPipe.argtypes = [w.HANDLE, w.LPVOID, w.DWORD,
                                          w.LPVOID, ctypes.POINTER(w.DWORD), w.LPVOID]
        self.api.PeekNamedPipe.restype = w.BOOL
        for name in ("ReadFile", "WriteFile"):
            method = getattr(self.api, name)
            method.argtypes = [w.HANDLE, w.LPVOID, w.DWORD,
                               ctypes.POINTER(w.DWORD), w.LPVOID]
            method.restype = w.BOOL
        self.api.CloseHandle.argtypes = [w.HANDLE]
        self.api.CloseHandle.restype = w.BOOL
        path = path.replace("\\\\?\\pipe\\", "\\\\.\\pipe\\", 1)
        deadline = time.monotonic() + 10
        while True:
            self.handle = self.api.CreateFileW(path, 0xC0000000, 0, None, 3, 0, None)
            if self.handle != ctypes.c_void_p(-1).value:
                break
            error = ctypes.get_last_error()
            if error not in (2, 231) or time.monotonic() >= deadline:
                raise ctypes.WinError(error)
            time.sleep(0.02)

    def recv(self, size):
        available = ctypes.c_ulong()
        deadline = time.monotonic() + 10
        while True:
            if not self.api.PeekNamedPipe(self.handle, None, 0, None,
                                         ctypes.byref(available), None):
                raise ctypes.WinError(ctypes.get_last_error())
            if available.value:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError("Discord named pipe did not respond")
            time.sleep(0.01)
        buffer = ctypes.create_string_buffer(min(size, available.value))
        count = ctypes.c_ulong()
        if not self.api.ReadFile(self.handle, buffer, len(buffer), ctypes.byref(count), None):
            raise ctypes.WinError(ctypes.get_last_error())
        return buffer.raw[:count.value]

    def sendall(self, data):
        while data:
            count = ctypes.c_ulong()
            buffer = ctypes.create_string_buffer(data)
            if not self.api.WriteFile(self.handle, buffer, len(data), ctypes.byref(count), None):
                raise ctypes.WinError(ctypes.get_last_error())
            if not count.value:
                raise RuntimeError("zero-byte named pipe write")
            data = data[count.value:]

    def close(self):
        self.api.CloseHandle(self.handle)


class RpcClient:
    """External Discord client. No browser Origin and no product test hooks."""
    def __init__(self, transport, listening, client_id):
        self.transport = transport
        self.client_id = client_id
        if transport == "ipc":
            if os.name == "nt":
                self.stream = NamedPipe(listening["ipc"])
            else:
                self.stream = socket.socket(socket.AF_UNIX)
                self.stream.settimeout(10)
                self.stream.connect(listening["ipc"])
            self.send({"v": 1, "client_id": client_id}, op=0)
        else:
            self.stream = socket.create_connection(("127.0.0.1", listening["ws"]), 10)
            key = base64.b64encode(os.urandom(16)).decode()
            request = ("GET /?v=1&encoding=json&client_id=%s HTTP/1.1\r\n"
                       "Host: 127.0.0.1:%s\r\nUpgrade: websocket\r\n"
                       "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
                       "Sec-WebSocket-Version: 13\r\n\r\n") % (client_id, listening["ws"], key)
            self.stream.sendall(request.encode())
            headers = b""
            while not headers.endswith(b"\r\n\r\n"):
                headers += self.read(1)
                if len(headers) > 8192:
                    raise RuntimeError("oversized WebSocket upgrade response")
            lines = headers.decode().split("\r\n")
            fields = {name.lower(): value.strip() for name, value in
                      (line.split(":", 1) for line in lines[1:] if ":" in line)}
            expected = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            if " 101 " not in lines[0] or fields.get("sec-websocket-accept") != expected:
                raise RuntimeError("invalid WebSocket upgrade: %r" % headers)
        self.ready = self.receive()
        if self.ready.get("cmd") != "DISPATCH" or self.ready.get("evt") != "READY":
            raise RuntimeError("Discord handshake failed: %r" % self.ready)

    def read(self, size):
        chunks = bytearray()
        while len(chunks) < size:
            chunk = self.stream.recv(size - len(chunks))
            if not chunk:
                raise RuntimeError("Discord client connection closed unexpectedly")
            chunks.extend(chunk)
        return bytes(chunks)

    def send(self, value, op=1):
        body = json.dumps(value).encode()
        if self.transport == "ipc":
            self.stream.sendall(struct.pack("<II", op, len(body)) + body)
        else:
            mask = os.urandom(4)
            length = len(body)
            header = bytes([0x81, 0x80 | length]) if length < 126 else \
                bytes([0x81, 0xFE]) + struct.pack("!H", length)
            self.stream.sendall(header + mask + bytes(
                byte ^ mask[i % 4] for i, byte in enumerate(body)))

    def receive(self):
        if self.transport == "ipc":
            op, size = struct.unpack("<II", self.read(8))
            if op != 1 or size > 65536:
                raise RuntimeError("unexpected Discord frame: %s, %s" % (op, size))
        else:
            first, second = self.read(2)
            if first != 0x81 or second & 0x80:
                raise RuntimeError("expected an unmasked WebSocket JSON frame, got %s" % first)
            size = second & 0x7F
            if size == 126:
                size = struct.unpack("!H", self.read(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.read(8))[0]
            if size > 65536:
                raise RuntimeError("oversized WebSocket JSON frame")
        return json.loads(self.read(size))

    def activity(self, activity, nonce):
        self.send({"cmd": "SET_ACTIVITY", "args": {"pid": os.getpid(), "activity": activity},
                   "nonce": nonce})
        answer = self.receive()
        if answer.get("cmd") != "SET_ACTIVITY" or answer.get("nonce") != nonce or answer.get("evt") is not None:
            raise RuntimeError("SET_ACTIVITY was not acknowledged: %r" % answer)
        if activity is None:
            if answer.get("data") is not None:
                raise RuntimeError("clearing activity returned %r" % answer)
        else:
            for key, value in dict(activity, application_id=self.client_id).items():
                if (answer.get("data") or {}).get(key) != value:
                    raise RuntimeError("SET_ACTIVITY ack changed %s: %r" % (key, answer))
        return answer

    def close(self):
        self.stream.close()


def rpc_probe(stage, report, clients):
    result = {"stage": stage}
    if stage in ("rpc-publish", "rpc-republish", "rpc-reconnect"):
        # Disable closes the server ends. Release our stale handles before
        # opening fresh sessions on the newly advertised listener endpoints.
        for client in clients.values():
            client.close()
        clients.clear()
        result["clients"] = []
        for transport, client_id, name, details, state in (
                ("ipc", "123456789012345678", "IPC Smoke Atlas", "Exploring north", "Party of 2"),
                ("ws", "234567890123456789", "WebSocket Smoke Beacon", "Building south", "Room of 7")):
            client = RpcClient(transport, report["rpcEndpoint"]["listening"], client_id)
            clients[transport] = client
            activity = {"name": name, "details": details, "state": state, "type": 0}
            result["clients"].append({"transport": transport, "clientId": client_id,
                                      "ready": client.ready,
                                      "ack": client.activity(activity, stage + "-" + transport)})
    elif stage == "rpc-clear":
        result["ack"] = clients["ipc"].activity(None, "clear-ipc")
    elif stage in ("rpc-disconnect", "rpc-finish"):
        for client in clients.values():
            client.close()
        clients.clear()
    else:
        raise RuntimeError("unknown RPC probe stage %r" % stage)
    return result


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
    rpc_clients = {}
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
                # Disposable null output: real producer streams with no sound
                # sent to the runner's speakers. The native backend receives the
                # same Pulse socket as these independent paplay processes.
                pulse_socket = str(pathlib.Path(scratch, "pulse.sock"))
                env["PULSE_SERVER"] = "unix:" + pulse_socket
                env["PULSE_SINK"] = "nexa_capture"
                processes.append(subprocess.Popen([
                    "pulseaudio", "-n", "--daemonize=no", "--use-pid-file=no", "--exit-idle-time=-1",
                    "--load=module-native-protocol-unix socket=%s auth-anonymous=1" % pulse_socket,
                    "--load=module-null-sink sink_name=nexa_capture rate=48000 channels=2",
                ], env=env, stdout=log, stderr=log))
                for _ in range(100):
                    if pathlib.Path(pulse_socket).exists():
                        break
                    time.sleep(.05)
                else:
                    raise RuntimeError("disposable Pulse server did not start")
                for name, frequencies in (("Selected", (431, 863)), ("Unrelated", (1291, 1291))):
                    path = pathlib.Path(scratch, name + ".wav")
                    # 120 seconds avoids a file restart changing producer IDs.
                    second = b"".join(struct.pack("<hh", *[
                        round(8000 * math.sin(2 * math.pi * hz * i / 48000)) for hz in frequencies
                    ]) for i in range(48000))
                    with wave.open(str(path), "wb") as audio:
                        audio.setnchannels(2); audio.setsampwidth(2); audio.setframerate(48000)
                        audio.writeframes(second * 120)
                    processes.append(subprocess.Popen([
                        "paplay", "--client-name=Nexa Tone " + name, "--stream-name=Nexa Tone " + name, str(path),
                    ], env=env, stdout=log, stderr=log))
                env["NEXA_DESKTOP_URL"] += "&native-capture=1"
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
                if stage in ("rpc-publish", "rpc-clear", "rpc-disconnect", "rpc-republish",
                             "rpc-reconnect", "rpc-finish") and stage not in handled:
                    http.rpc_result = rpc_probe(stage, report, rpc_clients)
                    handled.add(stage)
                if linux and stage in ("native-activate", "close-in-call", "hide-before-call") and stage not in handled:
                    native_pid = subprocess.check_output(
                        ["pgrep", "-P", str(app.pid), "-x", "nexa-desktop"], text=True).strip()
                    window = subprocess.check_output(
                        ["xdotool", "search", "--all", "--pid", native_pid, "--name", "^Nexa$"],
                        env=env, text=True).splitlines()[-1]
                    if stage == "native-activate":
                        subprocess.run(["xdotool", "mousemove", "--window", window, "100", "100",
                                        "click", "1"], env=env, check=True)
                    elif stage == "close-in-call":
                        close_x11_window(int(window), env["DISPLAY"])
                    else:
                        subprocess.run(["xdotool", "windowunmap", window], env=env, check=True)
                    handled.add(stage)
                time.sleep(0.1)
            raise RuntimeError("the smoke page did not finish after 120 seconds")
        finally:
            for client in rpc_clients.values():
                client.close()
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
