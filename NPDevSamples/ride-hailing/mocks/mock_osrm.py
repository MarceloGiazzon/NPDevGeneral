#!/usr/bin/env python3
"""Mock OSRM routing server for the ride-hailing sample. Stdlib only.

Answers the one OSRM call the sample uses, with the same response shape as a real OSRM
(https://project-osrm.org/docs/v5.24.0/api/#route-service), so the model's http-connector can be
tested offline and the SAME model later points at a real OSRM (self-hosted, or the public demo server
for light manual tries only -- its usage policy forbids heavy use):

  GET /route/v1/driving/{fromLng},{fromLat};{toLng},{toLat}?overview=false
      -> 200 {"code": "Ok", "routes": [{"distance": <metres>, "duration": <seconds>}], "waypoints": [..]}

The mock's "road" distance is the straight line (haversine) times --detour (default 1.35), and the
duration assumes --speed-kmh (default 28). Scenarios:
  any coordinate exactly 0,0           -> 400 {"code": "NoRoute"}   (tests the fallback to the estimate)
  --fail-every N (N>0)                 -> every Nth call answers 503     (tests the fallback on outage)

  GET /mock/calls   every call seen;  POST /mock/reset;  GET /health

Usage: python -I mock_osrm.py --port 8703
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EARTH_RADIUS_KM = 6371.0088
ROUTE = re.compile(r"^/route/v1/driving/(-?[0-9.]+),(-?[0-9.]+);(-?[0-9.]+),(-?[0-9.]+)(\?.*)?$")


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def build_server(argv: list[str] | None = None):
    p = argparse.ArgumentParser(description="Mock OSRM route service.")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8703)
    p.add_argument("--detour", type=float, default=1.35)
    p.add_argument("--speed-kmh", type=float, default=28.0)
    p.add_argument("--fail-every", type=int, default=0)
    cfg = p.parse_args(argv)
    lock = threading.Lock()
    calls: list[str] = []

    class Handler(BaseHTTPRequestHandler):
        server_version = "npdev-mock-osrm/1"

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
            if self.path == "/health":
                return self._send(200, {"status": "UP"})
            if self.path == "/mock/calls":
                with lock:
                    return self._send(200, {"items": list(calls)})
            m = ROUTE.match(self.path)
            if not m:
                return self._send(400, {"code": "InvalidUrl", "message": self.path})
            with lock:
                calls.append(self.path)
                n = len(calls)
            if cfg.fail_every > 0 and n % cfg.fail_every == 0:
                return self._send(503, {"code": "ServiceUnavailable"})
            lng1, lat1, lng2, lat2 = (float(m.group(i)) for i in range(1, 5))
            if (lat1 == 0 and lng1 == 0) or (lat2 == 0 and lng2 == 0):
                return self._send(400, {"code": "NoRoute", "message": "Impossible route between points"})
            km = haversine_km(lat1, lng1, lat2, lng2) * cfg.detour
            metres = round(km * 1000.0, 1)
            seconds = round(km / cfg.speed_kmh * 3600.0, 1)
            return self._send(200, {
                "code": "Ok",
                "routes": [{"distance": metres, "duration": seconds, "weight": seconds, "legs": []}],
                "waypoints": [{"location": [lng1, lat1]}, {"location": [lng2, lat2]}],
            })

        def do_POST(self):
            if self.path == "/mock/reset":
                with lock:
                    calls.clear()
                return self._send(200, {"ok": True})
            return self._send(404, {"code": "no_route"})

    return ThreadingHTTPServer((cfg.host, cfg.port), Handler), cfg


def main() -> int:
    server, cfg = build_server()
    print(f"mock-osrm listening on http://{cfg.host}:{server.server_address[1]}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
