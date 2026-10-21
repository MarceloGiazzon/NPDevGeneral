"""Unit tests for the AI authoring benchmark (BENCH-2). Stdlib only; no model, no Gradle, no app boot.
Run: python -m unittest discover -s scripts/ai/bench -p "test_bench.py"
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import author  # noqa: E402
import backends  # noqa: E402
import probes  # noqa: E402
import run_bench  # noqa: E402
import stages  # noqa: E402

CANARY_MODEL = Path(__file__).resolve().parents[3] / "NPDevSamples" / "npdev-canary" / "Input" / "model.json"


class BackendTests(unittest.TestCase):
    ENTRIES = [{"id": "ollama", "kind": "local", "baseUrl": "http://localhost:11434/v1",
                "pricePer1kTokens": {"input": 0, "output": 0}},
               {"id": "free-hosted", "kind": "free", "baseUrl": "https://free.example.com/openai/v1",
                "pricePer1kTokens": {"input": 0.001, "output": 0.002}}]

    def test_ollama_allowed_with_zero_cost(self):
        info = backends.resolve_backend("http://localhost:11434/v1", "m", False, self.ENTRIES)
        self.assertEqual(info.id, "ollama")
        self.assertEqual(info.cost(100000, 100000), 0)

    def test_unlisted_refused_without_allow_paid(self):
        for url in ("https://api.openai.com/v1", "http://localhost:9999/v1", "http://evil.localhost:11434/v1",
                    "https://free.example.com/other/v1", "http://localhost:11434.evil.com/v1"):
            with self.assertRaises(backends.BackendRefused, msg=url):
                backends.resolve_backend(url, "m", False, self.ENTRIES)

    def test_allow_paid_overrides(self):
        info = backends.resolve_backend("https://api.openai.com/v1", "m", True, self.ENTRIES)
        self.assertEqual(info.kind, "paid-unlisted")

    def test_configured_price_used(self):
        info = backends.resolve_backend("https://free.example.com/openai/v1", "m", False, self.ENTRIES)
        self.assertAlmostEqual(info.cost(2000, 1000), 0.004)

    def test_missing_config_refused(self):
        with self.assertRaises(backends.BackendRefused):
            backends.resolve_backend("", "m", False, self.ENTRIES)
        with self.assertRaises(backends.BackendRefused):
            backends.resolve_backend("http://localhost:11434/v1", "", False, self.ENTRIES)

    def test_real_allowlist_file_parses_and_has_ollama(self):
        entries = backends.load_allowlist()
        self.assertIsNotNone(backends.match_allowlist("http://localhost:11434/v1", entries))

    def test_fake_backend(self):
        fb = backends.FakeBackend(CANARY_MODEL)
        c = fb.complete([{"role": "user", "content": "x"}])
        model, err = author.extract_model(c.text)
        self.assertEqual(err, "")
        self.assertEqual(json.loads(model), json.loads(CANARY_MODEL.read_text(encoding="utf-8")))
        self.assertEqual(fb.info.cost(c.prompt_tokens, c.completion_tokens), 0)


class _ChatHandler(BaseHTTPRequestHandler):
    seen_auth = None

    def do_POST(self):
        _ChatHandler.seen_auth = self.headers.get("Authorization")
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length))
        reply = {"choices": [{"message": {"content": "```json\n{\"a\": 1}\n```"}}],
                 "usage": {"prompt_tokens": 11, "completion_tokens": 7}, "echo_model": body["model"]}
        raw = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *a):
        pass


class OpenAICompatTests(unittest.TestCase):
    def test_chat_completion_roundtrip_sends_bearer_and_reads_usage(self):
        srv = HTTPServer(("127.0.0.1", 0), _ChatHandler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            info = backends.BackendInfo("t", "local", f"http://127.0.0.1:{srv.server_port}/v1", "m")
            comp = backends.OpenAICompatBackend(info, api_key="sekret-key").complete([{"role": "user", "content": "hi"}])
        finally:
            srv.shutdown()
            srv.server_close()
        self.assertEqual((comp.prompt_tokens, comp.completion_tokens), (11, 7))
        self.assertEqual(_ChatHandler.seen_auth, "Bearer sekret-key")

    def test_connection_error_does_not_leak_key(self):
        info = backends.BackendInfo("t", "local", "http://127.0.0.1:1/v1", "m")
        with self.assertRaises(backends.BackendError) as ctx:
            backends.OpenAICompatBackend(info, api_key="sekret-key", timeout=2).complete([{"role": "user", "content": "x"}])
        self.assertNotIn("sekret-key", str(ctx.exception))


class AuthorTests(unittest.TestCase):
    def test_extract_fenced(self):
        m, e = author.extract_model("sure!\n```json\n{\"x\": [1, 2]}\n```\nbye")
        self.assertEqual(json.loads(m), {"x": [1, 2]})

    def test_extract_bare_object_with_braces_in_strings(self):
        m, e = author.extract_model('Here: {"a": "}{", "b": {"c": 1}} thanks')
        self.assertEqual(json.loads(m), {"a": "}{", "b": {"c": 1}})

    def test_extract_prefers_largest_fence(self):
        m, _ = author.extract_model("```json\n{\"a\":1}\n```\n```json\n{\"a\":1,\"b\":2,\"c\":3}\n```")
        self.assertIn("c", json.loads(m))

    def test_extract_failure(self):
        m, e = author.extract_model("no json here")
        self.assertIsNone(m)
        self.assertTrue(e)
        m, e = author.extract_model("```json\n{broken\n```")
        self.assertIsNone(m)

    def test_ablation_prompt_omits_knowledge(self):
        with_k = author.initial_messages("make an app", "KNOWLEDGE-TEXT")
        without = author.initial_messages("make an app", None)
        self.assertIn("KNOWLEDGE-TEXT", with_k[0]["content"])
        self.assertNotIn("KNOWLEDGE-TEXT", without[0]["content"])
        self.assertIn("make an app", without[1]["content"])

    def test_repair_messages_carry_error_text_and_truncate(self):
        hist = author.initial_messages("p", None)
        msgs = author.repair_messages(hist, "reply", "validate", "E" * 20000)
        self.assertEqual(msgs[-2], {"role": "assistant", "content": "reply"})
        self.assertIn("'validate' stage", msgs[-1]["content"])
        self.assertLess(len(msgs[-1]["content"]), 7000)
        self.assertEqual(len(msgs), len(hist) + 2)


class ProbeUnitTests(unittest.TestCase):
    def test_collection_names_match_generator_rule(self):
        self.assertEqual(probes.rest_collection_name("CanaryTask"), "canary_tasks")
        self.assertEqual(probes.rest_collection_name("TicketCategory"), "ticket_categories")
        self.assertEqual(probes.rest_collection_name("Box"), "boxes")
        self.assertEqual(probes.rest_collection_name("Person"), "people")
        self.assertEqual(probes.rest_collection_name("Bikes"), "bikes")
        self.assertEqual(probes.rest_collection_name("Bike"), "bikes")

    def test_name_matching(self):
        self.assertTrue(probes.name_matches("canary_tasks", "CanaryTask"))
        self.assertTrue(probes.name_matches("OwnerName", "name", ["owner name"]))
        self.assertFalse(probes.name_matches("title", "name"))

    def test_subset(self):
        self.assertTrue(probes.subset({"a": 3, "b": "x"}, {"a": 3.0}))
        self.assertTrue(probes.subset({"a": "3"}, {"a": 3}))
        self.assertFalse(probes.subset({"a": 3}, {"a": 4}))
        rows = probes.candidate_rows({"content": [{"t": 1}, {"t": 2}], "total": 2})
        self.assertTrue(any(probes.subset(r, {"t": 2}) for r in rows))

    def _resolver(self):
        cat = probes.Catalog(concepts=[
            probes.CatalogConcept("Bike", "bikes", [probes.CatalogField("id"), probes.CatalogField("frame_size", "int"),
                                                    probes.CatalogField("state", "enum", True, ["IN_REPAIR", "READY"])])],
            flows=["RepairBike"], queries=["BikesByState"])
        acc = {"concepts": [{"name": "Bike", "fields": [{"name": "frameSize", "type": "integer"},
                                                        {"name": "state", "type": "enum",
                                                         "enumValues": [{"key": "InRepair"}, {"key": "Ready"}]}]}],
               "flows": [{"name": "Repair", "aliases": ["RepairBike"]}], "views": [{"name": "ByState", "aliases": ["BikesByState"]}]}
        return probes.Resolver(acc, cat)

    def test_templates(self):
        r = self._resolver()
        self.assertEqual(r.path("/api/{concept:Bike}"), "/api/bikes")
        self.assertEqual(r.path("{concept:Bike}"), "/api/bikes")
        self.assertEqual(r.value({"{field:Bike.frameSize}": 3, "s": "{enum:Bike.state=InRepair}"}),
                         {"frame_size": 3, "s": "IN_REPAIR"})
        self.assertEqual(r.path("/api/{flow:Repair}"), "/api/flows/RepairBike/execute")
        self.assertEqual(r.path("/api/{view:ByState}"), "/api/queries/BikesByState")
        self.assertRegex(r.text("{date:+2}"), r"^\d{4}-\d\d-\d\d$")
        self.assertEqual(r.text("{unique}-{unique}").split("-")[0], r.text("{unique}"))

    def test_unresolved(self):
        r = self._resolver()
        for bad in ("{concept:Nope}", "{id:p9}", "{field:Bike.nope}", "{enum:Bike.state=Lost}", "{flow:Nope}"):
            with self.assertRaises(probes.Unresolved, msg=bad):
                r.text(bad)

    def test_check_concepts_and_minimums(self):
        cat = probes.Catalog(concepts=[probes.CatalogConcept("Bike", "bikes", [probes.CatalogField("id"), probes.CatalogField("size")])])
        acc = {"concepts": [{"name": "Bike", "fields": [{"name": "size", "type": "integer"}, {"name": "color", "type": "text"}]},
                            {"name": "Rider", "fields": [{"name": "name", "type": "text"}]}],
               "minimums": {"concepts": 2, "fields": 3}}
        problems = probes.check_concepts(acc, cat)
        self.assertEqual(len(problems), 4)   # color, Rider, concept minimum, field minimum


class _AppHandler(BaseHTTPRequestHandler):
    rows: dict = {}

    def _send(self, status, obj):
        raw = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.endswith("/metadata/concepts"):
            return self._send(200, {"items": [{"name": "Thing", "fieldPaths": ["id", "label"]}]})
        if self.path.endswith("/metadata/fields"):
            return self._send(200, {"items": [
                {"concept": "Thing", "fieldPath": "id", "parentPath": "", "dslType": "uuid", "required": True},
                {"concept": "Thing", "fieldPath": "label", "parentPath": "", "dslType": "string", "required": True}]})
        if self.path == "/v3/api-docs":
            return self._send(200, {"paths": {"/api/things": {}, "/api/queries/Q": {}}})
        if self.path.startswith("/api/things/"):
            row = _AppHandler.rows.get(self.path.rsplit("/", 1)[1])
            return self._send(200, row) if row else self._send(404, {})
        self._send(404, {})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.headers.get("X-Api-Key") != "k":
            return self._send(401, {})
        if not body.get("label"):
            return self._send(422, {"code": "invariant_failed"})
        body["id"] = "id%d" % (len(_AppHandler.rows) + 1)
        _AppHandler.rows[body["id"]] = body
        self._send(201, body)

    def log_message(self, *a):
        pass


class AcceptanceAgainstStubTests(unittest.TestCase):
    ACC = {"concepts": [{"name": "Thing", "fields": [{"name": "label", "type": "text", "required": True}]}],
           "rules": [{"id": "r1", "description": "A thing without a label is refused.", "check": {"kind": "rest-reject", "probes": ["p2"]}},
                     {"id": "r2", "description": "A thing can be stored and read.", "check": {"kind": "rest-accept", "probes": ["p3"]}}],
           "probes": [{"id": "p1", "purpose": "setup", "method": "POST", "pathHint": "/api/{concept:Thing}",
                       "body": {"{field:Thing.label}": "a"}, "expectStatus": [201]},
                      {"id": "p2", "purpose": "assert", "method": "POST", "pathHint": "/api/{concept:Thing}", "body": {}, "expectStatus": [400, 422]},
                      {"id": "p3", "purpose": "assert", "method": "GET", "pathHint": "/api/{concept:Thing}/{id:p1}",
                       "expectStatus": [200], "expectJsonContains": {"{field:Thing.label}": "a"}}],
           "minimums": {"concepts": 1, "fields": 1}}

    def _run(self, acc):
        _AppHandler.rows = {}
        srv = HTTPServer(("127.0.0.1", 0), _AppHandler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            return probes.run_acceptance(acc, f"http://127.0.0.1:{srv.server_port}", "k", {}, None)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_pass(self):
        r = self._run(self.ACC)
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["probeCounts"], {"passed": 3, "failed": 0, "unresolved": 0})

    def test_failed_and_unresolved_are_distinguished_and_fed_back(self):
        acc = json.loads(json.dumps(self.ACC))
        acc["probes"][1]["expectStatus"] = [200]                        # will fail (422)
        acc["probes"].append({"id": "p4", "purpose": "assert", "method": "GET", "pathHint": "/api/{concept:Ghost}",
                              "expectStatus": [200]})                   # unresolved
        acc["rules"].append({"id": "r3", "description": "Ghosts are listed.", "check": {"kind": "rest-accept", "probes": ["p4"]}})
        r = self._run(acc)
        self.assertFalse(r["ok"])
        self.assertEqual(r["ruleCounts"], {"passed": 1, "failed": 1, "unresolved": 1})
        text = probes.feedback_text(r)
        self.assertIn("rule r1 failed", text)
        self.assertIn("rule r3 unresolved", text)


class ScorecardAndLoopTests(unittest.TestCase):
    @staticmethod
    def _res(pid, passed, loops, fail=None, first_valid=True, first_pass=None, tier="simple-crud", cost=0.0, wall=10.0):
        return {"promptId": pid, "tier": tier, "passed": passed, "loopsUsed": loops, "failingStage": fail,
                "firstAttemptValid": first_valid, "firstAttemptPassed": passed and loops == 0 if first_pass is None else first_pass,
                "firstAttemptFailingStage": None if (passed and loops == 0) else (fail or "validate"),
                "tokens": {"total": 100}, "costUsd": cost, "wallSeconds": wall}

    def test_scorecard_numbers(self):
        rs = [self._res("a", True, 0), self._res("b", True, 2, first_valid=False),
              self._res("c", False, 5, fail="build"), self._res("d", False, 5, fail="build", cost=0.5)]
        sc = run_bench.scorecard(rs)
        self.assertEqual(sc["prompts"], 4)
        self.assertEqual(sc["firstAttemptValidRate"], 0.75)
        self.assertEqual(sc["firstAttemptPassRate"], 0.25)
        self.assertEqual(sc["passWithin5LoopsRate"], 0.5)
        self.assertEqual(sc["meanLoopsAllPrompts"], 3.0)
        self.assertEqual(sc["meanLoopsPassedPrompts"], 1.0)
        self.assertEqual(sc["failingStageHistogram"], {"build": 2})
        self.assertEqual(sc["totalCostUsd"], 0.5)
        self.assertEqual(sc["costUsdPerPassedApp"], 0.25)
        self.assertEqual(sc["totalTokens"], 400)

    def test_repair_loop_feeds_error_text_back_and_stops_on_pass(self):
        bad = "```json\n{\"concepts\": []}\n```"
        fb = backends.FakeBackend(CANARY_MODEL, replies=[bad])      # 1st reply bad, then the good canary model
        outcomes = iter([{"stages": [], "failingStage": "validate", "feedback": "MISSING namespace"},
                         {"stages": [], "failingStage": None, "feedback": ""}])
        args = mock.Mock(max_loops=5, ablation="no-knowledge", keep_apps=False)
        prompt = {"id": "bench-901", "tier": "simple-crud", "title": "t", "prompt": "make an app", "acceptance": {}}
        with tempfile.TemporaryDirectory() as td, mock.patch.object(run_bench, "run_attempt",
                                                                     side_effect=lambda *a, **k: next(outcomes)):
            res = run_bench.run_prompt(prompt, fb, None, Path(td), args)
            self.assertTrue((Path(td) / "bench-901" / "result.json").exists())
        self.assertTrue(res["passed"])
        self.assertEqual(res["loopsUsed"], 1)
        self.assertFalse(res["firstAttemptPassed"])
        self.assertEqual(res["firstAttemptFailingStage"], "validate")
        self.assertEqual(res["tokens"]["total"], 3000)
        self.assertEqual(res["costUsd"], 0)
        second_call = fb.calls[1]
        self.assertIn("MISSING namespace", second_call[-1]["content"])
        self.assertEqual(second_call[-2]["role"], "assistant")

    def test_loop_cap(self):
        fb = backends.FakeBackend(CANARY_MODEL)
        args = mock.Mock(max_loops=2, ablation="none", keep_apps=False)
        prompt = {"id": "bench-902", "prompt": "x", "acceptance": {}}
        with tempfile.TemporaryDirectory() as td, mock.patch.object(
                run_bench, "run_attempt", return_value={"stages": [], "failingStage": "build", "feedback": "boom"}):
            res = run_bench.run_prompt(prompt, fb, None, Path(td), args)
        self.assertFalse(res["passed"])
        self.assertEqual(res["loopsUsed"], 2)
        self.assertEqual(res["failingStage"], "build")
        self.assertEqual(len(fb.calls), 3)

    def test_unparseable_reply_is_author_stage_failure(self):
        fb = backends.FakeBackend(CANARY_MODEL, replies=["I cannot do that"])
        args = mock.Mock(max_loops=0, ablation="none", keep_apps=False)
        with tempfile.TemporaryDirectory() as td:
            res = run_bench.run_prompt({"id": "bench-903", "prompt": "x", "acceptance": {}}, fb, None, Path(td), args)
        self.assertEqual(res["failingStage"], "author")
        self.assertFalse(res["firstAttemptValid"])


class CliTests(unittest.TestCase):
    def test_refuses_unlisted_backend_and_never_prints_key(self):
        env = {"NPDEV_BENCH_BASE_URL": "https://api.paid.example.com/v1", "NPDEV_BENCH_MODEL": "m",
               "NPDEV_BENCH_API_KEY": "TOPSECRETKEY"}
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = run_bench.main(["run", "--prompt", "none"])
        self.assertEqual(rc, 3)
        self.assertIn("REFUSED", err.getvalue())
        self.assertNotIn("TOPSECRETKEY", out.getvalue() + err.getvalue())

    def test_dry_run_prints_backend_and_zero_cost(self):
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as td, contextlib.redirect_stdout(out), \
                mock.patch.object(run_bench, "run_prompt", return_value={
                    "passed": True, "loopsUsed": 0, "failingStage": None, "wallSeconds": 1, "costUsd": 0,
                    "tokens": {"total": 1500}, "promptId": "bench-900", "tier": "simple-crud",
                    "firstAttemptValid": True, "firstAttemptPassed": True, "firstAttemptFailingStage": None}):
            rc = run_bench.main(["run", "--dry-run", "--out-root", td, "--run-id", "t1"])
            self.assertTrue((Path(td) / "t1" / "summary.json").exists())
        self.assertEqual(rc, 0)
        self.assertIn("fake-dry-run", out.getvalue())
        self.assertIn("estimated cost per authoring call: 0.0 USD", out.getvalue())

    def test_report_subcommand_outputs_json(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td) / "run1" / "bench-001"
            d.mkdir(parents=True)
            (d / "result.json").write_text(json.dumps(ScorecardAndLoopTests._res("bench-001", True, 0)), encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(run_bench.main(["--report", str(Path(td) / "run1")]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data["run1"]["scorecard"]["firstAttemptPassRate"], 1.0)


class StageUnitTests(unittest.TestCase):
    def test_free_port_avoids_reserved(self):
        for _ in range(20):
            self.assertNotIn(stages.free_port(), stages.RESERVED_PORTS)

    def test_workspace_files(self):
        with tempfile.TemporaryDirectory() as td:
            paths = stages.write_workspace(Path(td) / "a", "bench-001", 23456, "{}")
            cfg = json.loads(paths["config"].read_text(encoding="utf-8"))
            self.assertEqual(cfg["runtime"]["serverPort"], 23456)
            self.assertEqual(cfg["database"]["provider"], "h2-local")
            self.assertTrue(paths["db"].exists())

    def test_format_diagnostics(self):
        rep = {"diagnostics": [{"severity": "error", "code": "C", "path": "$.x", "message": "bad", "suggestedFix": "do y"},
                               {"severity": "warning", "message": "meh"}]}
        text = stages.format_diagnostics(rep)
        self.assertIn("suggested fix: do y", text)
        self.assertNotIn("meh", text)


if __name__ == "__main__":
    unittest.main()
