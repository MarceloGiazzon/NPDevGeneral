#!/usr/bin/env python3
"""Mock payment service provider (PSP) for the ride-hailing sample. Stdlib only.

Speaks a small Stripe-like REST dialect and sends Stripe-style signed webhooks, so the generated app
can be tested end to end with NO real provider and NO keys:

  POST /v1/charges            {amountCents, currency, paymentId, source}  + header Idempotency-Key
  GET  /v1/charges/{id}
  POST /v1/refunds            {chargeId, amountCents}                     + header Idempotency-Key
  GET  /mock/log              every request seen (newest last)
  GET  /mock/events           every webhook event sent, with delivery attempts
  POST /mock/redeliver/{evt}  send an already-sent webhook event again (tests webhook de-dupe)
  POST /mock/reset            forget everything
  GET  /health

Auth: every /v1 call needs `Authorization: Bearer <api key>` (default sk_test_mock) -> else 401.

The card `source` picks the scenario (so a test chooses the outcome, nothing is random):
  tok_ok          succeeded at once; webhook payment.succeeded
  tok_async       processing; webhook payment.succeeded after --async-delay seconds
  tok_fail_async  processing; webhook payment.failed after --async-delay seconds
  tok_decline     HTTP 402 {error:{code:card_declined}}; no charge kept as succeeded; webhook payment.failed
  tok_flaky       first call for an Idempotency-Key answers 503, the next one succeeds (tests retry)
  tok_slow        sleeps --slow-seconds before answering (tests the connector timeout)
  tok_nowebhook   processing, and NO webhook is ever sent (tests the awaitEvent timeout)

Idempotency (like Stripe): same key + same body -> the SAME response again, header Idempotent-Replayed: true.
Same key + different body -> 409 idempotency_conflict. A charge without the header -> 400.

Webhook signature (scheme `stripe-v1` of the NPDev webhook door):
  header Stripe-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, f"{t}.{raw body}")>
Event body: {"id": "evt_..", "type": "payment.succeeded"|"payment.failed"|"refund.succeeded",
             "data": {"object": {"id": "ch_..", "status": .., "amountCents": .., "metadata": {"paymentId": ..}}}}
Webhooks are retried 3 times (1 s, 2 s, 4 s) on a non-2xx answer or a connection error.

Usage:
  python -I mock_psp.py --port 8701 --webhook-url http://localhost:8150/api/hooks/psp --secret whsec_test
  (or set MOCK_PSP_WEBHOOK_SECRET; the app side reads the same value from NPDEV_WEBHOOK_PSP_SECRET)
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def sign(secret: str, body: bytes, timestamp: int | None = None) -> str:
    """Stripe-Signature header value for `body` (used by the mock AND by tests that verify it)."""
    t = int(time.time()) if timestamp is None else int(timestamp)
    mac = hmac.new(secret.encode("utf-8"), f"{t}.".encode("utf-8") + body, hashlib.sha256).hexdigest()
    return f"t={t},v1={mac}"


def verify(secret: str, body: bytes, header: str, tolerance_seconds: int = 300) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
    try:
        t = int(parts["t"])
    except (KeyError, ValueError):
        return False
    if abs(time.time() - t) > tolerance_seconds:
        return False
    expected = hmac.new(secret.encode("utf-8"), f"{t}.".encode("utf-8") + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts.get("v1", ""))


class PspState:
    def __init__(self, cfg: argparse.Namespace):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        with getattr(self, "lock", threading.Lock()):
            self.charges: dict[str, dict] = {}
            self.refunds: dict[str, dict] = {}
            self.idem: dict[str, tuple[str, int, dict]] = {}   # key -> (body digest, status, response)
            self.flaky_seen: set[str] = set()
            self.log: list[dict] = []
            self.events: dict[str, dict] = {}

    # ---- webhooks --------------------------------------------------------------------------
    def emit(self, event_type: str, obj: dict, delay: float = 0.0) -> str:
        evt_id = "evt_" + uuid.uuid4().hex[:16]
        event = {"id": evt_id, "type": event_type, "created": int(time.time()), "data": {"object": dict(obj)}}
        with self.lock:
            self.events[evt_id] = {"event": event, "attempts": []}
        if self.cfg.webhook_url:
            threading.Thread(target=self._deliver, args=(evt_id, delay), daemon=True).start()
        return evt_id

    def _deliver(self, evt_id: str, delay: float) -> None:
        if delay > 0:
            time.sleep(delay)
        with self.lock:
            event = self.events[evt_id]["event"]
        body = json.dumps(event, separators=(",", ":")).encode("utf-8")
        for attempt, backoff in enumerate((1, 2, 4, None), start=1):
            status: int | str
            try:
                req = urllib.request.Request(self.cfg.webhook_url, data=body, method="POST", headers={
                    "Content-Type": "application/json",
                    "Stripe-Signature": sign(self.cfg.secret, body),
                    "User-Agent": "npdev-mock-psp/1"})
                with urllib.request.urlopen(req, timeout=10) as resp:
                    status = resp.status
            except urllib.error.HTTPError as e:
                status = e.code
            except (urllib.error.URLError, OSError) as e:
                status = f"error: {e}"
            with self.lock:
                self.events[evt_id]["attempts"].append({"attempt": attempt, "status": status, "at": time.time()})
            if isinstance(status, int) and 200 <= status < 300:
                return
            if backoff is None:
                return
            time.sleep(backoff)


def make_handler(state: PspState):
    cfg = state.cfg

    class Handler(BaseHTTPRequestHandler):
        server_version = "npdev-mock-psp/1"

        def log_message(self, fmt, *args):  # quiet; /mock/log has everything
            if cfg.verbose:
                sys.stderr.write("mock-psp: " + (fmt % args) + "\n")

        # ---- plumbing ----
        def _body(self) -> bytes:
            n = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(n) if n else b""

        def _send(self, status: int, payload: dict, extra: dict | None = None) -> None:
            data = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def _record(self, body: bytes) -> None:
            with state.lock:
                state.log.append({"method": self.command, "path": self.path, "at": time.time(),
                                  "idempotencyKey": self.headers.get("Idempotency-Key"),
                                  "body": body.decode("utf-8", "replace")[:4000]})

        def _authorized(self) -> bool:
            return self.headers.get("Authorization", "") == f"Bearer {cfg.api_key}"

        # ---- routes ----
        def do_GET(self):
            self._record(b"")
            if self.path == "/health":
                return self._send(200, {"status": "UP"})
            if self.path == "/mock/log":
                with state.lock:
                    return self._send(200, {"items": list(state.log)})
            if self.path == "/mock/events":
                with state.lock:
                    return self._send(200, {"items": list(state.events.values())})
            m = re.fullmatch(r"/v1/charges/([A-Za-z0-9_]+)", self.path)
            if m:
                if not self._authorized():
                    return self._send(401, {"error": {"code": "unauthorized"}})
                with state.lock:
                    ch = state.charges.get(m.group(1))
                return self._send(200, ch) if ch else self._send(404, {"error": {"code": "not_found"}})
            return self._send(404, {"error": {"code": "no_route", "path": self.path}})

        def do_POST(self):
            body = self._body()
            self._record(body)
            if self.path == "/mock/reset":
                state.reset()
                return self._send(200, {"ok": True})
            m = re.fullmatch(r"/mock/redeliver/(evt_[A-Za-z0-9]+)", self.path)
            if m:
                with state.lock:
                    known = m.group(1) in state.events
                if not known:
                    return self._send(404, {"error": {"code": "no_such_event"}})
                threading.Thread(target=state._deliver, args=(m.group(1), 0.0), daemon=True).start()
                return self._send(202, {"ok": True})
            if self.path not in ("/v1/charges", "/v1/refunds"):
                return self._send(404, {"error": {"code": "no_route", "path": self.path}})
            if not self._authorized():
                return self._send(401, {"error": {"code": "unauthorized"}})
            key = self.headers.get("Idempotency-Key")
            if not key:
                return self._send(400, {"error": {"code": "idempotency_key_missing"}})
            try:
                req = json.loads(body or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": {"code": "invalid_json"}})
            digest = hashlib.sha256(self.path.encode() + b"|" + body).hexdigest()
            with state.lock:
                prior = state.idem.get(key)
            if prior is not None:
                if prior[0] != digest:
                    return self._send(409, {"error": {"code": "idempotency_conflict"}})
                return self._send(prior[1], prior[2], {"Idempotent-Replayed": "true"})
            status, resp = (self._charge(req, key) if self.path == "/v1/charges" else self._refund(req))
            if status == 503:          # a transient failure is NOT remembered (a retry must be able to succeed)
                return self._send(status, resp)
            with state.lock:
                state.idem[key] = (digest, status, resp)
            return self._send(status, resp)

        def _charge(self, req: dict, key: str) -> tuple[int, dict]:
            try:
                amount = int(round(float(req.get("amountCents"))))  # a decimal fare (1234.5) is rounded, like a real PSP's minor units
            except (TypeError, ValueError):
                return 400, {"error": {"code": "amount_invalid"}}
            if amount <= 0:
                return 400, {"error": {"code": "amount_invalid"}}
            source = str(req.get("source") or "tok_ok")
            if source == "tok_flaky":
                with state.lock:
                    first = key not in state.flaky_seen
                    state.flaky_seen.add(key)
                if first:
                    return 503, {"error": {"code": "temporarily_unavailable"}}
            if source == "tok_slow":
                time.sleep(cfg.slow_seconds)
            ch = {"id": "ch_" + uuid.uuid4().hex[:16], "object": "charge", "amountCents": amount,
                  "currency": str(req.get("currency") or "BRL"), "source": source, "refundedCents": 0,
                  "metadata": {"paymentId": req.get("paymentId")}}
            if source == "tok_decline":
                ch.update(status="failed", failureCode="card_declined")
                with state.lock:
                    state.charges[ch["id"]] = ch
                state.emit("payment.failed", ch)
                return 402, {"error": {"code": "card_declined", "chargeId": ch["id"]}, "id": ch["id"], "status": "failed"}
            if source in ("tok_async", "tok_fail_async", "tok_nowebhook"):
                ch["status"] = "processing"
            else:
                ch["status"] = "succeeded"
            with state.lock:
                state.charges[ch["id"]] = ch
            if source == "tok_async":
                threading.Thread(target=self._settle, args=(ch["id"], "succeeded"), daemon=True).start()
            elif source == "tok_fail_async":
                threading.Thread(target=self._settle, args=(ch["id"], "failed"), daemon=True).start()
            elif ch["status"] == "succeeded":
                state.emit("payment.succeeded", ch)
            return 200, dict(ch)

        @staticmethod
        def _settle(charge_id: str, outcome: str) -> None:
            time.sleep(cfg.async_delay)
            with state.lock:
                ch = state.charges[charge_id]
                ch["status"] = outcome
                snapshot = dict(ch)
            state.emit("payment." + outcome, snapshot)

        def _refund(self, req: dict) -> tuple[int, dict]:
            with state.lock:
                ch = state.charges.get(str(req.get("chargeId")))
            if not ch:
                return 404, {"error": {"code": "charge_not_found"}}
            if ch["status"] != "succeeded":
                return 409, {"error": {"code": "charge_not_refundable", "status": ch["status"]}}
            try:
                amount = int(req.get("amountCents", ch["amountCents"] - ch["refundedCents"]))
            except (TypeError, ValueError):
                return 400, {"error": {"code": "amount_invalid"}}
            if amount <= 0 or amount > ch["amountCents"] - ch["refundedCents"]:
                return 400, {"error": {"code": "amount_exceeds_refundable"}}
            with state.lock:
                ch["refundedCents"] += amount
                rf = {"id": "re_" + uuid.uuid4().hex[:16], "object": "refund", "chargeId": ch["id"],
                      "amountCents": amount, "status": "succeeded", "metadata": dict(ch["metadata"])}
                state.refunds[rf["id"]] = rf
            state.emit("refund.succeeded", rf)
            return 200, dict(rf)

    return Handler


def build_server(argv: list[str] | None = None) -> tuple[ThreadingHTTPServer, PspState]:
    p = argparse.ArgumentParser(description="Mock PSP (payments) for NPDev samples.")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8701)
    p.add_argument("--api-key", default=os.environ.get("MOCK_PSP_API_KEY", "sk_test_mock"))
    p.add_argument("--webhook-url", default=os.environ.get("MOCK_PSP_WEBHOOK_URL", ""))
    p.add_argument("--secret", default=os.environ.get("MOCK_PSP_WEBHOOK_SECRET", "whsec_test"))
    p.add_argument("--async-delay", type=float, default=3.0)
    p.add_argument("--slow-seconds", type=float, default=15.0)
    p.add_argument("--verbose", action="store_true")
    cfg = p.parse_args(argv)
    state = PspState(cfg)
    server = ThreadingHTTPServer((cfg.host, cfg.port), make_handler(state))
    return server, state


def main() -> int:
    server, state = build_server()
    host, port = server.server_address[:2]
    print(f"mock-psp listening on http://{host}:{port}  webhook -> {state.cfg.webhook_url or '(none)'}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
