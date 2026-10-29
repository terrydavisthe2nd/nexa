#!/usr/bin/env python3
"""Serves the desktop smoke page and records what the shell reports."""
import json
import pathlib
import sys
from http.server import HTTPServer, SimpleHTTPRequestHandler

ROOT = pathlib.Path(__file__).resolve().parent
REPORT = ROOT / "report.json"


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        if self.path != "/rpc-probe":
            return super().do_GET()
        body = json.dumps(getattr(self.server, "rpc_result", {})).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/report":
            self.send_error(404)
            return
        length = int(self.headers.get("content-length") or 0)
        body = self.rfile.read(length)
        try:
            json.loads(body)
        except ValueError:
            self.send_error(400)
            return
        REPORT.write_bytes(body)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    REPORT.unlink(missing_ok=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
