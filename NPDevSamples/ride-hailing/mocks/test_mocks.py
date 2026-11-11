"""Tests for the three mocks. Run: python -I -m unittest test_mocks -v   (from this folder)

Each test starts the mock on a free port in a thread; the PSP webhook goes to a tiny local sink that
checks the Stripe-style signature exactly the way the NPDev webhook door (scheme stripe-v1) must.
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mock_osrm  # noqa: E402
import mock_psp  # noqa: E402
import mock_push  # noqa: E402

SECRET = "whsec_unit"


def call(method: str, url: str, body: dict | None = None, headers: dict | None = None, timeout: float = 10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), json.loads(e.read() or b"{}")


def start(server):
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{server.server_address[1]}"


class Sink:
    """Receives webhooks; answers 500 for the first `fail_first` deliveries."""

    def __init__(self, fail_first: int = 0):
        self.received: list[tuple[bool, dict]] = []
        self.fail_first = fail_first
        sink = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                ok = mock_psp.verify(SECRET, raw, self.headers.get("Stripe-Signature", ""))
                sink.received.append((ok, json.loads(raw)))
                status = 500 if len(sink.received) <= sink.fail_first else 200
                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = start(self.server) + "/api/hooks/psp"

    def wait(self, n: int, seconds: float = 12) -> None:
        end = time.time() + seconds
        while len(self.received) < n and time.time() < end:
            time.sleep(0.05)


class PspTest(unittest.TestCase):
    def setUp(self):
        self.sink = Sink()
        self.server, self.state = mock_psp.build_server(
            ["--port", "0", "--webhook-url", self.sink.url, "--secret", SECRET, "--async-delay", "0.3", "--slow-seconds", "1"])
        self.base = start(self.server)
        self.auth = {"Authorization": "Bearer sk_test_mock"}

    def tearDown(self):
        self.server.shutdown(); self.server.server_close()
        self.sink.server.shutdown(); self.sink.server.server_close()

    def charge(self, source, key, amount=1500, pid="pay-1"):
        return call("POST", self.base + "/v1/charges", {"amountCents": amount, "currency": "BRL", "paymentId": pid, "source": source},
                    {**self.auth, "Idempotency-Key": key})

    def test_requires_auth_and_key(self):
        self.assertEqual(call("POST", self.base + "/v1/charges", {"amountCents": 1})[0], 401)
        self.assertEqual(call("POST", self.base + "/v1/charges", {"amountCents": 1}, self.auth)[0], 400)

    def test_ok_charge_and_signed_webhook(self):
        status, _, ch = self.charge("tok_ok", "k1")
        self.assertEqual((status, ch["status"]), (200, "succeeded"))
        self.sink.wait(1)
        ok, evt = self.sink.received[0]
        self.assertTrue(ok, "signature must verify")
        self.assertEqual(evt["type"], "payment.succeeded")
        self.assertEqual(evt["data"]["object"]["metadata"]["paymentId"], "pay-1")

    def test_idempotent_replay_and_conflict(self):
        s1, _, a = self.charge("tok_ok", "k2")
        s2, h2, b = self.charge("tok_ok", "k2")
        self.assertEqual((s1, s2, a["id"]), (200, 200, b["id"]))
        self.assertEqual(h2.get("Idempotent-Replayed"), "true")
        self.assertEqual(self.charge("tok_ok", "k2", amount=999)[0], 409)

    def test_decline(self):
        status, _, body = self.charge("tok_decline", "k3")
        self.assertEqual((status, body["error"]["code"]), (402, "card_declined"))

    def test_flaky_then_ok(self):
        self.assertEqual(self.charge("tok_flaky", "k4")[0], 503)
        status, _, ch = self.charge("tok_flaky", "k4")
        self.assertEqual((status, ch["status"]), (200, "succeeded"))

    def test_async_success_and_failure(self):
        _, _, a = self.charge("tok_async", "k5", pid="pay-a")
        _, _, f = self.charge("tok_fail_async", "k6", pid="pay-f")
        self.assertEqual((a["status"], f["status"]), ("processing", "processing"))
        self.sink.wait(2)
        types = sorted(e["type"] for _, e in self.sink.received)
        self.assertEqual(types, ["payment.failed", "payment.succeeded"])

    def test_nowebhook_sends_nothing(self):
        self.charge("tok_nowebhook", "k7")
        time.sleep(0.8)
        self.assertEqual(self.sink.received, [])

    def test_refund_and_redeliver(self):
        _, _, ch = self.charge("tok_ok", "k8", amount=1000)
        self.assertEqual(call("POST", self.base + "/v1/refunds", {"chargeId": ch["id"], "amountCents": 1001},
                              {**self.auth, "Idempotency-Key": "r0"})[0], 400)
        status, _, rf = call("POST", self.base + "/v1/refunds", {"chargeId": ch["id"], "amountCents": 400},
                             {**self.auth, "Idempotency-Key": "r1"})
        self.assertEqual((status, rf["status"]), (200, "succeeded"))
        self.sink.wait(2)
        evt_id = self.sink.received[0][1]["id"]
        self.assertEqual(call("POST", self.base + f"/mock/redeliver/{evt_id}")[0], 202)
        self.sink.wait(3)
        self.assertEqual(sum(1 for _, e in self.sink.received if e["id"] == evt_id), 2, "same event id twice = de-dupe test input")

    def test_webhook_retry(self):
        self.sink.fail_first = 1
        self.charge("tok_ok", "k9")
        self.sink.wait(2, seconds=6)
        self.assertEqual(len(self.sink.received), 2)

    def test_signature_rejects_tampering(self):
        body = b'{"a":1}'
        header = mock_psp.sign(SECRET, body)
        self.assertTrue(mock_psp.verify(SECRET, body, header))
        self.assertFalse(mock_psp.verify(SECRET, b'{"a":2}', header))
        self.assertFalse(mock_psp.verify(SECRET, body, mock_psp.sign(SECRET, body, timestamp=time.time() - 3600)))


class PushTest(unittest.TestCase):
    def setUp(self):
        self.server, _ = mock_push.build_server(["--port", "0"])
        self.base = start(self.server)
        self.auth = {"Authorization": "Bearer push_test_mock"}

    def tearDown(self):
        self.server.shutdown(); self.server.server_close()

    def test_push_paths(self):
        self.assertEqual(call("POST", self.base + "/v1/push", {"to": "a", "title": "t"})[0], 401)
        self.assertEqual(call("POST", self.base + "/v1/push", {"to": "rider1", "title": "Driver on the way"}, self.auth)[0], 200)
        self.assertEqual(call("POST", self.base + "/v1/push", {"to": "fail-x", "title": "t"}, self.auth)[0], 500)
        self.assertEqual(call("POST", self.base + "/v1/push", {"to": "invalid-x", "title": "t"}, self.auth)[0], 410)
        status, _, body = call("GET", self.base + "/mock/sent?to=rider1")
        self.assertEqual((status, body["count"]), (200, 1))


class OsrmTest(unittest.TestCase):
    def setUp(self):
        self.server, _ = mock_osrm.build_server(["--port", "0", "--fail-every", "3"])
        self.base = start(self.server)

    def tearDown(self):
        self.server.shutdown(); self.server.server_close()

    def test_route_shape_noroute_and_outage(self):
        # Sao Paulo: Praca da Se -> Av. Paulista (about 2.9 km straight line)
        status, _, body = call("GET", self.base + "/route/v1/driving/-46.6339,-23.5505;-46.6544,-23.5614?overview=false")
        self.assertEqual((status, body["code"]), (200, "Ok"))
        km = body["routes"][0]["distance"] / 1000
        self.assertTrue(2.5 < km < 4.5, km)
        self.assertEqual(call("GET", self.base + "/route/v1/driving/0,0;-46.6,-23.5")[2]["code"], "NoRoute")
        self.assertEqual(call("GET", self.base + "/route/v1/driving/-46.6,-23.5;-46.7,-23.6")[0], 503)


if __name__ == "__main__":
    unittest.main()
