#!/usr/bin/env python3
"""Mock push gateway for the ride-hailing sample. Stdlib only.

Stands in for a real push provider (FCM, APNs, Web Push, ntfy.sh) so "notify the driver / rider" can be
tested with no account and no phone:

  POST /v1/push     {to, title, body, data?}   header Authorization: Bearer <key>  -> 200 {id, status: "queued"}
  GET  /mock/sent   every push accepted, oldest first; ?to=<account> filters
  POST /mock/reset
  GET  /health

Scenarios chosen by the recipient (`to`):
  to starts with "fail-"     -> 500 {error: {code: provider_unavailable}}  (tests that a failed push never
                                breaks the ride flow)
  to starts with "invalid-"  -> 410 {error: {code: unregistered}}          (device token gone)
  anything else              -> 200

Usage: python -I mock_push.py --port 8702   (key: --api-key or MOCK_PUSH_API_KEY, default push_test_mock)
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def build_server(argv: list[str] | None = None):
    p = argparse.ArgumentParser(description="Mock push gateway for NPDev samples.")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8702)
    p.add_argument("--api-key", default=os.environ.get("MOCK_PUSH_API_KEY", "push_test_mock"))
    cfg = p.parse_args(argv)
    lock = threading.Lock()
    sent: list[dict] = []

    class Handler(BaseHTTPRequestHandler):
        server_version = "npdev-mock-push/1"

        def log_message(self, fmt, *args):
            pass

        def _send(self, status: int, payload: dict) -> None:
            data = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            url = urllib.parse.urlparse(self.path)
            if url.path == "/health":
                return self._send(200, {"status": "UP"})
            if url.path == "/mock/sent":
                to = urllib.parse.parse_qs(url.query).get("to", [None])[0]
                with lock:
                    items = [s for s in sent if to is None or s["to"] == to]
                return self._send(200, {"items": items, "count": len(items)})
            return self._send(404, {"error": {"code": "no_route"}})

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b""
            if self.path == "/mock/reset":
                with lock:
                    sent.clear()
                return self._send(200, {"ok": True})
            if self.path != "/v1/push":
                return self._send(404, {"error": {"code": "no_route"}})
            if self.headers.get("Authorization", "") != f"Bearer {cfg.api_key}":
                return self._send(401, {"error": {"code": "unauthorized"}})
            try:
                msg = json.loads(raw or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": {"code": "invalid_json"}})
            to = str(msg.get("to") or "")
            if not to or not msg.get("title"):
                return self._send(400, {"error": {"code": "to_and_title_required"}})
            if to.startswith("fail-"):
                return self._send(500, {"error": {"code": "provider_unavailable"}})
            if to.startswith("invalid-"):
                return self._send(410, {"error": {"code": "unregistered"}})
            item = {"id": "push_" + uuid.uuid4().hex[:12], "to": to, "title": msg.get("title"),
                    "body": msg.get("body"), "data": msg.get("data"), "at": time.time()}
            with lock:
                sent.append(item)
            return self._send(200, {"id": item["id"], "status": "queued"})

    return ThreadingHTTPServer((cfg.host, cfg.port), Handler), cfg


def main() -> int:
    server, cfg = build_server()
    print(f"mock-push listening on http://{cfg.host}:{server.server_address[1]}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
