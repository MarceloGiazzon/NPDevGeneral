"""FORM P6-T3 rung 4A: declarative pack probes -- shape check, variable passing, expectations, and a real HTTP round trip."""

from __future__ import annotations

import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_probe as pp  # noqa: E402


def _doc(**over):
    doc = {"schemaVersion": "npdev-pack-probe.v1", "pack": "leads",
           "users": [{"name": "rep", "role": "REP"}, {"name": "plain"}],
           "steps": [{"name": "rep creates", "as": "rep", "method": "POST", "path": "/api/leads",
                      "body": {"code": "L-${rand}"}, "expect": {"status": 201, "has": ["id"]}, "save": {"leadId": "id"}},
                     {"name": "plain cannot read", "as": "plain", "method": "GET", "path": "/api/leads/${leadId}",
                      "expect": {"status": [403, 404]}}]}
    doc.update(over)
    return doc


class ShapeTest(unittest.TestCase):
    def test_good_probe_has_no_problems(self):
        self.assertEqual(pp.check_shape(_doc()), [])

    def test_unknown_identity_is_named(self):
        doc = _doc()
        doc["steps"][0]["as"] = "ghost"
        self.assertIn("ghost", pp.check_shape(doc)[0])

    def test_duplicate_user_and_schema_errors(self):
        self.assertIn("twice", pp.check_shape(_doc(users=[{"name": "a"}, {"name": "a"}]))[0])
        self.assertTrue(pp.check_shape(_doc(schemaVersion="nope")))

    def test_api_keys_env_format(self):
        users = [{"name": "rep", "role": "REP"}, {"name": "plain"}]
        self.assertEqual(pp.api_keys_env(users, {"rep": "k1", "plain": "k2"}), "k1=dev:rep:REP;k2=dev:plain:USER")


class RunTest(unittest.TestCase):
    def fake(self, responses):
        seen = []

        def call(key, method, path, body):
            seen.append((key, method, path, body))
            return responses.pop(0)
        return call, seen

    def test_saved_variable_flows_into_the_next_path_and_rand_is_substituted(self):
        call, seen = self.fake([(201, {"id": "abc"}), (403, {"error": "denied"})])
        results = pp.run_probe("http://x", _doc(), {"rep": "K1", "plain": "K2"}, call)
        self.assertEqual([r["ok"] for r in results], [True, True])
        self.assertEqual(seen[1][2], "/api/leads/abc")
        self.assertTrue(seen[0][3]["code"].startswith("L-") and "${" not in seen[0][3]["code"])
        self.assertEqual((seen[0][0], seen[1][0]), ("K1", "K2"))

    def test_wrong_status_json_has_and_rows_are_reported_together(self):
        doc = _doc(steps=[{"name": "list", "as": "rep", "method": "GET", "path": "/api/leads",
                           "expect": {"status": 200, "json": {"content.0.name": "x"}, "has": ["page"], "rows": {"min": 5}}}])
        call, _ = self.fake([(500, {"content": [{"name": "y"}], "totalElements": 1})])
        result = pp.run_probe("http://x", doc, {"rep": "K", "plain": "K"}, call)[0]
        self.assertFalse(result["ok"])
        for fragment in ("status 500", "content.0.name is 'y'", "page is missing", "1 row(s), wanted at least 5"):
            self.assertIn(fragment, result["detail"])

    def test_missing_variable_fails_that_step_only(self):
        call, _ = self.fake([(500, {"nope": 1})])
        results = pp.run_probe("http://x", _doc(), {"rep": "K", "plain": "K"}, call)
        self.assertFalse(results[0]["ok"])
        self.assertIn("cannot save 'leadId'", results[0]["detail"])
        self.assertFalse(results[1]["ok"])
        self.assertIn("leadId", results[1]["detail"])

    def test_rows_exact_zero_for_a_hidden_list(self):
        doc = _doc(steps=[{"name": "plain sees none", "as": "plain", "method": "GET", "path": "/api/leads",
                           "expect": {"status": 200, "rows": {"exact": 0}}}])
        call, _ = self.fake([(200, {"content": [], "totalElements": 0})])
        self.assertTrue(pp.run_probe("http://x", doc, {"rep": "K", "plain": "K"}, call)[0]["ok"])


class HttpRoundTripTest(unittest.TestCase):
    def test_real_http_sends_the_api_key_and_json_body(self):
        received = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                received["key"] = self.headers.get("X-Api-Key")
                received["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.send_response(201)
                self.end_headers()
                self.wfile.write(b'{"id": "n1"}')

            def do_GET(self):  # noqa: N802
                self.send_response(403)
                self.end_headers()
                self.wfile.write(b'{"error": "no"}')

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_address[1]}"
        results = pp.run_probe(base, _doc(), {"rep": "KEY-REP", "plain": "KEY-PLAIN"})
        self.assertEqual([r["ok"] for r in results], [True, True], results)
        self.assertEqual(received["key"], "KEY-REP")
        self.assertTrue(received["body"]["code"].startswith("L-"))


if __name__ == "__main__":
    unittest.main()
