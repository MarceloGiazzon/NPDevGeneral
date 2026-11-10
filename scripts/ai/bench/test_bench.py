"""Unit tests for the AI authoring benchmark (BENCH-2). Stdlib only; no model, no Gradle, no app boot.
Run: python -m unittest discover -s scripts/ai/bench -p "test_bench.py"
"""
from __future__ import annotations

import contextlib
import io
import copy
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
import compare  # noqa: E402
import direct  # noqa: E402
import evolve  # noqa: E402
import probes  # noqa: E402
import relay_arm  # noqa: E402
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

    def test_429_and_503_are_retried_with_backoff_then_succeed(self):
        class Flaky(_ChatHandler):
            codes = [429, 503]

            def do_POST(self):
                if Flaky.codes:
                    self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    raw = b"busy"
                    self.send_response(Flaky.codes.pop(0))
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                    return
                super().do_POST()

        srv = HTTPServer(("127.0.0.1", 0), Flaky)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        waits = []
        try:
            info = backends.BackendInfo("t", "local", f"http://127.0.0.1:{srv.server_port}/v1", "m")
            backend = backends.OpenAICompatBackend(info, api_key="k")
            backend._sleep = waits.append
            comp = backend.complete([{"role": "user", "content": "hi"}])
        finally:
            srv.shutdown()
            srv.server_close()
        self.assertEqual(comp.completion_tokens, 7)
        self.assertEqual(waits, list(backends.RETRY_BACKOFF_SECONDS[:2]))

    def test_connection_error_does_not_leak_key(self):
        info = backends.BackendInfo("t", "local", "http://127.0.0.1:1/v1", "m")
        with self.assertRaises(backends.BackendError) as ctx:
            backends.OpenAICompatBackend(info, api_key="sekret-key", timeout=2).complete([{"role": "user", "content": "x"}])
        self.assertNotIn("sekret-key", str(ctx.exception))


class _Stub:
    def __init__(self, info, fail=False):
        self.info, self.fail, self.calls = info, fail, 0

    def complete(self, messages):
        self.calls += 1
        if self.fail:
            raise backends.BackendError("HTTP 429 quota")
        return backends.Completion("ok", 1, 1, 0.0)


class ProviderBackendTests(unittest.TestCase):
    """--provider / --profile against the real catalogue, through npdev ai's one client (fake transport)."""

    KEYS = {"groq": "kg", "nvidia": "kn", "openrouter": "ko", "anthropic": "ka", "gemini": "kgm"}

    def build(self, spec, keys=None, allow_paid=False, opener=None, effort=None):
        keys = self.KEYS if keys is None else keys
        aicfg, _ = backends._ai_modules()
        return backends.build_provider_backends(spec, allow_paid, 5.0, resolve_key=keys.get,
                                                config=aicfg.load(use_mine=False), opener=opener, effort=effort)

    def test_effort_is_recorded_and_an_undeclared_level_is_refused(self):
        backend = self.build("groq", effort="high")
        self.assertEqual(backend.info.describe()["effort"], "high")
        self.assertIsNone(self.build("groq").info.describe()["effort"])
        with self.assertRaises(backends.BackendRefused) as ctx:
            self.build("gemini", effort="high")
        self.assertIn("does not declare effort", str(ctx.exception))

    def test_spec_parses_ids_and_models(self):
        self.assertEqual(backends.parse_provider_spec("a, b=m2"), [("a", None), ("b", "m2")])

    def test_single_provider_is_a_plain_backend_with_catalogue_model(self):
        backend = self.build("groq")
        self.assertIsInstance(backend, backends.ClientBackend)
        self.assertEqual((backend.info.id, backend.info.model), ("groq", "openai/gpt-oss-120b"))

    def test_paid_provider_refused_without_allow_paid(self):
        with self.assertRaises(backends.BackendRefused):
            self.build("anthropic")
        self.assertEqual(self.build("anthropic", allow_paid=True).info.kind, "paid")

    def test_missing_key_is_refused_with_the_fix_in_the_message(self):
        with self.assertRaises(backends.BackendRefused) as ctx:
            self.build("groq", keys={})
        self.assertIn("npdev providers add groq", str(ctx.exception))

    def test_openrouter_style_non_free_model_refused(self):
        with self.assertRaises(backends.BackendRefused):
            self.build("openrouter=expensive-model")
        self.assertEqual(self.build("openrouter=y:free").info.model, "y:free")

    def test_a_call_goes_through_the_one_client_with_the_conversation(self):
        sent = []

        class Reply:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return json.dumps({"choices": [{"message": {"content": "done"}}],
                                   "usage": {"prompt_tokens": 3, "completion_tokens": 1}}).encode()

        def opener(request, timeout=None):
            sent.append(json.loads(request.data))
            return Reply()

        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"NPDEV_AI_USAGE_LOG": tmp + "/u.jsonl"}):
            backend = self.build("groq", opener=opener)
            messages = [{"role": "system", "content": "s"}, {"role": "user", "content": "u1"},
                        {"role": "assistant", "content": "a1"}, {"role": "user", "content": "u2"}]
            completion = backend.complete(messages)
            usage = Path(tmp, "u.jsonl").read_text(encoding="utf-8")
        self.assertEqual((completion.text, completion.prompt_tokens), ("done", 3))
        self.assertEqual([m["role"] for m in sent[0]["messages"]], ["system", "user", "assistant", "user"])
        self.assertIn('"feature": "bench"', usage)

    def test_profile_backend_follows_the_fallback_and_then_sticks_to_it(self):
        import urllib.error

        calls = []

        class Reply:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return json.dumps({"choices": [{"message": {"content": "ok"}}]}).encode()

        def opener(request, timeout=None):
            calls.append(request.full_url)
            if "generativelanguage" in request.full_url:
                raise urllib.error.HTTPError(request.full_url, 401, "no", {}, io.BytesIO(b""))
            return Reply()

        aicfg, _ = backends._ai_modules()
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {"NPDEV_AI_USAGE_LOG": tmp + "/u.jsonl"}):
            backend = backends.ProfileBackend(aicfg.load(use_mine=False), "fast", False, 5.0,
                                              key_resolver=lambda pid, account=None: "k", opener=opener)
            self.assertEqual(backend.info.id, "gemini")
            backend.complete([{"role": "user", "content": "x"}])
            self.assertEqual((backend.info.id, backend.retired), ("groq", ["fast"]))
            backend.complete([{"role": "user", "content": "y"}])
        self.assertEqual(sum("generativelanguage" in url for url in calls), 1)

    def test_failover_retires_an_exhausted_provider_for_the_rest_of_the_run(self):
        first = _Stub(backends.BackendInfo("a", "free", "u", "m"), fail=True)
        second = _Stub(backends.BackendInfo("b", "free", "u", "m"))
        fo = backends.FailoverBackend([first, second])
        self.assertEqual(fo.complete([]).text, "ok")
        self.assertEqual(fo.info.id, "b")
        fo.complete([])
        self.assertEqual((first.calls, second.calls, fo.retired), (1, 2, ["a"]))

    def test_failover_raises_when_the_last_provider_fails_too(self):
        only = _Stub(backends.BackendInfo("a", "free", "u", "m"), fail=True)
        with self.assertRaises(backends.BackendError):
            backends.FailoverBackend([only]).complete([])

    def test_real_catalogue_allowlists_free_and_local_but_not_paid(self):
        entries = backends.load_allowlist()
        self.assertIsNotNone(backends.match_allowlist("https://integrate.api.nvidia.com/v1", entries))
        self.assertIsNone(backends.match_allowlist("https://api.openai.com/v1", entries))


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

    def test_a_descriptive_field_name_matches_when_it_is_the_only_candidate(self):
        concept = probes.CatalogConcept("Recipe", "recipes", [probes.CatalogField("id"), probes.CatalogField("title"),
                                                             probes.CatalogField("minutesToMake")])
        cat = probes.Catalog([concept])
        self.assertEqual(cat.find_field(concept, "minutes").name, "minutesToMake")
        self.assertEqual(cat.find_field(concept, "title").name, "title")
        two = probes.CatalogConcept("Order", "orders", [probes.CatalogField("customerName"),
                                                        probes.CatalogField("productName")])
        self.assertIsNone(probes.Catalog([two]).find_field(two, "name"))     # ambiguous: still a miss

    def test_subset(self):
        self.assertTrue(probes.subset({"a": 3, "b": "x"}, {"a": 3.0}))
        self.assertTrue(probes.subset({"a": "3"}, {"a": 3}))
        self.assertFalse(probes.subset({"a": 3}, {"a": 4}))
        rows = probes.candidate_rows({"content": [{"t": 1}, {"t": 2}], "total": 2})
        self.assertTrue(any(probes.subset(r, {"t": 2}) for r in rows))

    def test_any_key_matches_any_column(self):
        row = {"productName": "Hinge x1", "units": 2}
        self.assertTrue(probes.subset(row, {"{any:1}": "Hinge x1"}))
        self.assertTrue(probes.subset(row, {"{any:1}": "Hinge x1", "{any:2}": 2}))
        self.assertFalse(probes.subset(row, {"{any:1}": "Anvil x1"}))
        self.assertFalse(probes.subset("not a row", {"{any:1}": "Hinge x1"}))

    def test_any_key_survives_template_resolution(self):
        res = self._resolver()
        self.assertEqual(res.value({"{any:1}": "Hinge {unique}"}), {"{any:1}": "Hinge " + res.unique})

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

    def test_an_assert_post_id_is_captured_for_later_probes(self):
        acc = copy.deepcopy(self.ACC)
        acc["probes"][0]["purpose"] = "assert"     # bench-012: p4..p6 read the id of assert probe p3
        r = self._run(acc)
        self.assertTrue(r["ok"], probes.feedback_text(r))

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

    def test_matrix_score_weights_pass_first_attempt_and_token_economy(self):
        card = {"completedPrompts": 4, "passRateCompleted": 1.0, "firstAttemptPassRate": 0.5,
                "medianTokensPerPassedApp": 10000}
        self.assertEqual(run_bench.matrix_score(card), 60 + 12.5 + 7.5)
        self.assertEqual(run_bench.matrix_score({**card, "medianTokensPerPassedApp": 4000}), 87.5)
        self.assertIsNone(run_bench.matrix_score({"completedPrompts": 0}))

    def test_matrix_writes_one_row_and_one_full_log_per_real_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for run_id, dry in (("real", False), ("dry", True)):
                prompt_dir = root / run_id / "bench-001" / "attempt-0"
                prompt_dir.mkdir(parents=True)
                (prompt_dir / "boot.log").write_text("Started App �\n", encoding="utf-8")
                result = {**self._res("bench-001", True, 0),
                          "attempts": [{"attempt": 0, "stages": [{"stage": "boot", "ok": True, "seconds": 1.0}]}]}
                (root / run_id / "bench-001" / "result.json").write_text(json.dumps(result), encoding="utf-8")
                (root / run_id / "summary.json").write_text(json.dumps(
                    {"runId": run_id, "dryRun": dry, "startedAt": "2026-11-02T10:00:00",
                     "backend": {"id": "groq", "kind": "free", "model": "m", "effort": "high"},
                     "knowledge": {"source": "x/bundle-compact.json"}}), encoding="utf-8")
            out = root / "_matrix"
            with contextlib.redirect_stdout(io.StringIO()):
                run_bench.cmd_matrix(mock.Mock(out_root=str(root), out=str(out)))
            runs = json.loads((out / "matrix.json").read_text(encoding="utf-8"))["runs"]
            self.assertEqual([(r["runId"], r["effort"], r["bundle"], r["passed"]) for r in runs],
                             [("real", "high", "compact", 1)])
            log = (out / "logs" / "real.txt").read_text(encoding="utf-8")
            self.assertIn("attempt-0/boot.log", log)
            self.assertIn("Started App ?", log)

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

    def test_slim_knowledge_drops_the_golden_examples(self):
        k = "## Authoring contract\nrules\n\n## Golden example: a\n{}\n## Golden example: b\n{}"
        self.assertEqual(author.slim_knowledge(k), "## Authoring contract\nrules\n")
        self.assertEqual(author.slim_knowledge("no examples"), "no examples")
        self.assertIsNone(author.slim_knowledge(None))

    def test_stateless_repair_sends_two_constant_size_messages(self):
        bad = "Here you go\n```json\n{\"namespace\": \"x\", \"concepts\": []}\n```"
        fb = backends.FakeBackend(CANARY_MODEL, replies=[bad, bad, "no json at all", bad])
        knowledge = "## Authoring contract\nrules\n\n## Golden example: big\n" + "x" * 5000
        outcomes = iter([{"stages": [], "failingStage": "validate", "feedback": "E1 bad mode\nmore", "modelText": "{\"m\": 1}"},
                         {"stages": [], "failingStage": "boot", "feedback": "E2 boot died", "modelText": "{\"m\": 2}"},
                         {"stages": [], "failingStage": "acceptance", "feedback": "E3 probe", "modelText": "{\"m\": 3}"},
                         {"stages": [], "failingStage": None, "feedback": ""}])
        args = mock.Mock(max_loops=4, ablation="none", keep_apps=False, repair_mode="stateless")
        prompt = {"id": "bench-904", "prompt": "make a shop app", "acceptance": {}}
        with tempfile.TemporaryDirectory() as td, mock.patch.object(run_bench, "run_attempt",
                                                                     side_effect=lambda *a, **k: next(outcomes)):
            res = run_bench.run_prompt(prompt, fb, knowledge, Path(td), args)
        self.assertEqual(res["repairMode"], "stateless")
        repairs = fb.calls[1:]
        self.assertTrue(all(len(c) == 2 and [m["role"] for m in c] == ["system", "user"] for c in repairs))
        self.assertTrue(all("Golden example" not in c[0]["content"] for c in repairs))
        first, second, third = (c[1]["content"] for c in repairs[:3])
        self.assertIn("make a shop app", first)
        self.assertIn('{"m": 1}', first)
        self.assertIn("E1 bad mode", first)
        self.assertNotIn("Earlier attempts", first)
        self.assertNotIn("Here you go", second, "earlier prose is not re-sent")
        self.assertIn("- attempt 0: validate: E1 bad mode", second)
        self.assertIn('{"m": 2}', second)
        # attempt 2's reply had no JSON: the next turn keeps the last good model and carries the extract error
        self.assertIn('{"m": 2}', third)
        self.assertIn("'author' stage", third)
        fourth = repairs[3][1]["content"]
        self.assertIn('{"m": 3}', fourth)
        self.assertIn("- attempt 2: author:", fourth)
        self.assertLess(len(fourth) - len(first), 500)
        self.assertTrue(res["passed"])

    def test_desire_path_fixes_are_applied_saved_and_reported_back(self):
        model = json.dumps({"namespace": "t", "concepts": [{"name": "Item"}],
                            "flows": [{"name": "L", "input": {"concept": "Item", "mode": "list"}}]})
        prompt = {"id": "bench-903", "acceptance": {}}
        seen = {}

        def fake_validate(path, log_dir):
            seen["model"] = json.loads(Path(path).read_text(encoding="utf-8"))
            return stages.StageResult("validate", False, 0.1, "some other error")

        with tempfile.TemporaryDirectory() as td, mock.patch.object(stages, "stage_validate", side_effect=fake_validate):
            for autofix in (True, False):
                args = mock.Mock(autofix=autofix, keep_apps=False)
                out = run_bench.run_attempt(0, model, prompt, Path(td) / str(autofix), args, [])
                written = (Path(td) / str(autofix) / "attempt-0" / "fixes-applied.json").exists()
                self.assertEqual(written, autofix)
                self.assertEqual(seen["model"]["flows"][0]["input"]["mode"], "query" if autofix else "list")
                self.assertEqual(out["fixesApplied"], 1 if autofix else 0)
                self.assertEqual("$.flows[0].input.mode: list -> query" in out["feedback"], autofix)
                self.assertIn("some other error", out["feedback"])

    def test_stop_when_stuck_ends_a_prompt_that_keeps_failing_the_same_way(self):
        fb = backends.FakeBackend(CANARY_MODEL)
        same = {"stages": [], "failingStage": "acceptance", "feedback": "- probe p2 failed: 403"}
        changed = {"stages": [], "failingStage": "validate", "feedback": "other error"}
        prompt = {"id": "bench-905", "prompt": "x", "acceptance": {}}
        for limit, outcomes, calls in ((3, [changed, same, same, same], 4), (0, [same] * 6, 6)):
            args = mock.Mock(max_loops=5, ablation="none", keep_apps=False, stop_when_stuck=limit)
            fb.calls.clear()
            with tempfile.TemporaryDirectory() as td, mock.patch.object(run_bench, "run_attempt",
                                                                         side_effect=list(outcomes)):
                res = run_bench.run_prompt(prompt, fb, None, Path(td), args)
            self.assertEqual(len(fb.calls), calls)
            self.assertEqual(res["stoppedStuck"], bool(limit))
            self.assertEqual(res["outcome"], "fail")

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
        self.assertEqual(res["outcome"], "fail")

    def test_backend_errors_are_classified_as_infra_not_authoring(self):
        self.assertEqual(backends.classify_backend_error("HTTP 429 from x: quota"), "quota")
        self.assertEqual(backends.classify_backend_error("HTTP 503 from x: high demand"), "overload")
        self.assertEqual(backends.classify_backend_error("HTTP 404 from x: no model"), "unreachable")
        self.assertEqual(backends.classify_backend_error("model call failed: TimeoutError: timed out"), "unreachable")
        self.assertEqual(backends.classify_backend_error("the JSON in the reply does not parse"), "other")

    def test_quota_after_a_real_attempt_is_infra_and_excluded_from_the_pass_rate(self):
        class QuotaAfterOne(backends.FakeBackend):
            def complete(self, messages):
                if self.calls:
                    raise backends.BackendError("HTTP 429 from host: You exceeded your current quota")
                return super().complete(messages)
        fb = QuotaAfterOne(CANARY_MODEL)
        args = mock.Mock(max_loops=5, ablation="none", keep_apps=False)
        with tempfile.TemporaryDirectory() as td, mock.patch.object(
                run_bench, "run_attempt", return_value={"stages": [], "failingStage": "acceptance", "feedback": "x"}):
            res = run_bench.run_prompt({"id": "bench-904", "prompt": "x", "acceptance": {}}, fb, None, Path(td), args)
        self.assertEqual((res["outcome"], res["infraKind"]), ("infra", "quota"))
        sc = run_bench.scorecard([res, self._res("a", True, 0)])
        self.assertEqual((sc["completedPrompts"], sc["passRateCompleted"]), (1, 1.0))
        self.assertEqual(sc["infraHistogram"], {"quota": 1})

    def test_old_result_files_are_reclassified_from_their_error_text(self):
        old = {"passed": False, "attempts": [{"failingStage": "author", "feedbackExcerpt": "HTTP 503 from h: busy"}]}
        self.assertEqual(run_bench.outcome_of(old), "infra")
        self.assertEqual(run_bench.outcome_of({"passed": False, "attempts": [
            {"failingStage": "validate", "feedbackExcerpt": "- json_schema_enum"}]}), "fail")

    def test_a_truncated_context_is_infra_not_a_model_failure(self):
        class Truncating(backends.FakeBackend):
            def __init__(self, path):
                super().__init__(path)
                self.info = backends.BackendInfo("ollama", "local", "http://localhost:11434/v1", "qwen")

            def complete(self, messages):
                return backends.Completion("```json\n{}\n```", 2048, 10, 1.0)
        args = mock.Mock(max_loops=5, ablation="none", keep_apps=False)
        with tempfile.TemporaryDirectory() as td:
            res = run_bench.run_prompt({"id": "bench-905", "prompt": "x", "acceptance": {}}, Truncating(CANARY_MODEL),
                                       "k" * 64000, Path(td), args)
        self.assertEqual((res["outcome"], res["infraKind"], res["loopsUsed"]), ("infra", "context-truncated", 0))


class ClaudeCliBackendTests(unittest.TestCase):
    def test_isolated_one_shot_call_reads_usage_and_flattens_the_repair_turns(self):
        seen = {}

        def runner(argv, stdin, cwd):
            seen.update(argv=argv, stdin=stdin, cwd=cwd, files=sorted(p.name for p in Path(cwd).iterdir()),
                        system=(Path(cwd) / ".system-prompt.txt").read_text(encoding="utf-8"))
            return json.dumps({"is_error": False, "result": "```json\n{}\n```",
                               "usage": {"input_tokens": 5, "cache_read_input_tokens": 900,
                                         "cache_creation_input_tokens": 100, "output_tokens": 42}})
        be = backends.ClaudeCliBackend("haiku", exe="claude", runner=runner)
        msgs = author.repair_messages(author.initial_messages("make an app", "KNOWLEDGE"), "my reply", "validate", "BAD")
        c = be.complete(msgs)
        self.assertEqual((c.prompt_tokens, c.completion_tokens), (1005, 42))
        for flag in ("-p", "--strict-mcp-config"):
            self.assertIn(flag, seen["argv"])
        self.assertEqual(seen["argv"][seen["argv"].index("--tools") + 1], "")
        self.assertEqual(seen["argv"][seen["argv"].index("--setting-sources") + 1], "")
        self.assertEqual(seen["files"], [".system-prompt.txt"])         # nothing else to read
        self.assertIn("KNOWLEDGE", seen["system"])
        self.assertIn("=== YOUR EARLIER REPLY ===\nmy reply", seen["stdin"])
        self.assertTrue(seen["stdin"].rstrip().endswith("BAD"))

    def test_cli_error_becomes_a_classified_backend_error(self):
        be = backends.ClaudeCliBackend("haiku", exe="claude", runner=lambda *a: json.dumps(
            {"is_error": True, "api_error_status": 429, "result": "usage limit reached"}))
        with self.assertRaises(backends.BackendError) as ctx:
            be.complete([{"role": "user", "content": "x"}])
        self.assertEqual(backends.classify_backend_error(str(ctx.exception)), "quota")


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


class MineResolveTests(unittest.TestCase):
    """mine --resolve: replay recorded attempts through the current fixer + (stubbed) validator."""

    @staticmethod
    def _attempt(root: Path, pid: str, n: int, model: dict, detail: str, author_model: dict | None = None):
        adir = root / pid / f"attempt-{n}"
        adir.mkdir(parents=True)
        (adir / "model.json").write_text(json.dumps(model), encoding="utf-8")
        if author_model is not None:
            (adir / "author-model.json").write_text(json.dumps(author_model), encoding="utf-8")
        return {"attempt": n, "failingStage": "validate", "stages": [{"stage": "validate", "detail": detail}]}

    def _run(self, td):
        root = Path(td) / "run1"
        d1 = "- bad_shape | $.entities[0].x | Bad 'a' shape"
        d2 = "- other_code | $.flows[1].y | Other 'b' problem"
        a1 = self._attempt(root, "bench-001", 0, {"marker": "fixed"}, d1, author_model={"marker": "raw"})
        a2 = self._attempt(root, "bench-002", 0, {"marker": "stuck"}, d1)
        a3 = self._attempt(root, "bench-003", 0, {"marker": "stuck"}, d2)
        for pid, a in (("bench-001", a1), ("bench-002", a2), ("bench-003", a3)):
            (root / pid / "result.json").write_text(json.dumps(
                {"promptId": pid, "attempts": [a]}), encoding="utf-8")
        boot = {"attempt": 0, "failingStage": "boot", "stages": []}
        (root / "bench-004").mkdir()
        (root / "bench-004" / "result.json").write_text(json.dumps({"promptId": "bench-004", "attempts": [boot]}), encoding="utf-8")
        return root

    def test_resolve_counts_and_flags(self):
        seen = []

        def fake_validator(model):
            seen.append(model["marker"])
            # the replayed (raw) model of bench-001 is rewritten by the fixer; 'stuck' models keep failing
            return frozenset({("bad_shape", "$.entities[].x")}) if model["marker"] == "stuck" else frozenset()

        def fake_fix(model):
            seen.append("in:" + model["marker"])
            return ({"marker": "fixed"} if model["marker"] == "raw" else model), []

        with tempfile.TemporaryDirectory() as td:
            root = self._run(td)
            rows = run_bench.mine_failures([root], keep_attempts=True)
            with mock.patch.object(run_bench.npdev_desire_paths, "fix_model", side_effect=fake_fix):
                run_bench.resolve_groups(rows, validator=fake_validator)
        by = {(g["stage"], g["kind"]): g for g in rows}
        shape = by[("validate", "bad_shape")]
        self.assertEqual((shape["stillFailing"], shape["resolved"], shape["now"]), (1, 1, "1/2 still fail"))
        other = by[("validate", "other_code")]
        self.assertEqual((other["stillFailing"], other["resolved"], other["now"]), (0, 1, "FIXED"))
        self.assertEqual(by[("boot", "no-cause-line")]["now"], "not replayed")
        self.assertIn("in:raw", seen)   # author-model.json, not the post-fix model.json, was replayed

    def test_cli_only_open_hides_fixed_and_json_has_fields(self):
        with tempfile.TemporaryDirectory() as td:
            root = self._run(td)
            keys = lambda model: frozenset({("bad_shape", "$.entities[].x")}) if model["marker"] == "stuck" else frozenset()
            with mock.patch.object(run_bench, "replay_validate", side_effect=keys),                     mock.patch.object(run_bench.npdev_desire_paths, "fix_model", side_effect=lambda m: (m, [])):
                out = io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
                    run_bench.main(["mine", str(root), "--resolve", "--only-open"])
                text = out.getvalue()
                out = io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
                    run_bench.main(["mine", str(root), "--resolve", "--json"])
        self.assertIn("still fail", text)
        self.assertIn("not replayed", text)
        data = json.loads(out.getvalue())
        self.assertTrue(all("stillFailing" in g and "now" in g and "attempts" not in g for g in data))
        # other_code's model is 'stuck' but the stub reports only bad_shape -> that group is FIXED and hidden
        self.assertNotIn("other_code", text)
        self.assertEqual({g["kind"]: g["now"] for g in data}["other_code"], "FIXED")


class StageUnitTests(unittest.TestCase):
    def test_validate_warnings_pass_and_errors_fail(self):
        for status, errors, ok in (("warning", 0, True), ("passed", 0, True), ("failed", 1, False)):
            def fake_run(cmd, cwd, log, timeout, status=status, errors=errors):
                report = Path(cmd[cmd.index("--report") + 1])
                report.write_text(json.dumps({"status": status, "summary": {"errors": errors, "warnings": 2},
                                              "diagnostics": []}), encoding="utf-8")
                Path(log).write_text("", encoding="utf-8")
                return (0 if ok else 1), False
            with tempfile.TemporaryDirectory() as td, mock.patch.object(stages, "run_logged", side_effect=fake_run):
                res = stages.stage_validate(Path(td) / "model.json", Path(td))
            self.assertEqual(res.ok, ok, status)

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


class CompactCoreContextTests(unittest.TestCase):
    """F4a: the compact bundle is a strict slimming of the full one, and keeps what the bench relies on."""

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import build_core_context
        cls.full, _ = build_core_context.build_bundle()
        cls.compact, _ = build_core_context.build_bundle(compact=True)

    def test_compact_is_smaller_and_drops_dead_sections(self):
        self.assertLess(len(self.compact), len(self.full) * 0.85)
        self.assertIn("## Ask before you write", self.full)
        for gone in ("## Ask before you write", "## The loop"):
            self.assertNotIn(gone, self.compact)

    def test_compact_keeps_what_authors_and_slim_knowledge_need(self):
        for kept in ("## Non-negotiables", "## Totals, reports and reactions", "## Gotchas",
                     "$.flows[].input.mode:", "$.roles[].concepts[]:", "$.queries[].aggregates[].fn:"):
            self.assertIn(kept, self.compact)
        # slim_knowledge cuts at this literal header; losing it silently stops stateless trimming.
        self.assertIn("\n## Golden example:", self.compact)
        self.assertLess(len(author.slim_knowledge(self.compact)), len(self.compact))


class OpenApiCatalogTests(unittest.TestCase):
    """BENCH-2: an app with no NPDev metadata endpoint is judged by the SAME probes, with the catalog derived
    from its OpenAPI document."""

    API = {"openapi": "3.0.1", "paths": {
        "/api/tasks": {"get": {"responses": {"200": {"content": {"application/json": {"schema": {
            "type": "array", "items": {"$ref": "#/components/schemas/Task"}}}}}}},
                       "post": {"requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/TaskRequest"}}}}}},
        "/api/tasks/{id}": {"get": {}},
        "/api/owners": {"post": {"requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Owner"}}}}}},
        "/api/flows/CreateTask/execute": {"post": {}},
        "/api/queries/OpenHigh": {"get": {}},
        "/v3/api-docs": {}},
        "components": {"schemas": {
            "TaskRequest": {"type": "object", "required": ["title"], "properties": {
                "title": {"type": "string"}, "priority": {"type": "integer"},
                "status": {"type": "string", "enum": ["OPEN", "DONE"]}, "due": {"type": "string", "format": "date"}}},
            "Task": {"type": "object", "properties": {"id": {"type": "string"}, "title": {"type": "string"}}},
            "Owner": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}}}

    def test_concepts_fields_required_and_enums_come_from_the_schemas(self):
        concepts = {c.name: c for c in probes.catalog_from_openapi(self.API)}
        self.assertEqual(set(concepts), {"Task", "Owner"})          # TaskRequest -> Task: the suffix is dropped
        task = concepts["Task"]
        self.assertEqual(task.collection, "tasks")
        by_name = {f.name: f for f in task.fields}
        self.assertNotIn("id", by_name)
        self.assertTrue(by_name["title"].required)
        self.assertEqual(by_name["priority"].type, "integer")
        self.assertEqual(by_name["status"].type, "enum")
        self.assertEqual(by_name["status"].enum_values, ["OPEN", "DONE"])
        self.assertEqual(by_name["due"].type, "date")
        self.assertTrue({f.name for f in concepts["Owner"].fields} == {"name"})

    def test_a_route_with_no_schema_still_yields_a_concept_named_from_the_segment(self):
        concepts = probes.catalog_from_openapi({"paths": {"/api/categories": {}, "/api/queries/X": {}, "/api/admin": {}}})
        self.assertEqual([(c.name, c.collection) for c in concepts], [("Category", "categories")])

    def test_fetch_catalog_falls_back_to_openapi_only_when_metadata_is_absent(self):
        replies = {"/api/admin/runtime/metadata/concepts": (404, None), "/v3/api-docs": (200, self.API)}

        def fake_call(base, method, path, body, key, timeout=30.0):
            return replies.get(path, (404, None))

        with mock.patch.object(probes, "http_call", side_effect=fake_call):
            cat, err = probes.fetch_catalog("http://x", "k", None)
        self.assertEqual(err, "")
        self.assertEqual(cat.source, "openapi")
        self.assertEqual([c.collection for c in cat.concepts], ["tasks", "owners"])
        self.assertEqual(cat.flows, ["CreateTask"])
        self.assertEqual(cat.queries, ["OpenHigh"])
        # and an app with neither metadata nor OpenAPI concepts is still reported as broken
        with mock.patch.object(probes, "http_call", side_effect=lambda *a, **k: (404, None)):
            cat, err = probes.fetch_catalog("http://x", "k", None)
        self.assertIn("404", err)

    def test_an_npdev_app_keeps_using_its_metadata(self):
        concepts = {"items": [{"name": "Thing", "fieldPaths": ["id", "label"]}]}
        replies = {"/api/admin/runtime/metadata/concepts": (200, concepts), "/api/admin/runtime/metadata/fields": (404, None),
                   "/v3/api-docs": (200, {"paths": {"/api/things": {}}})}
        with mock.patch.object(probes, "http_call", side_effect=lambda b, m, p, *a, **k: replies.get(p, (404, None))):
            cat, err = probes.fetch_catalog("http://x", "k", None)
        self.assertEqual((cat.source, err), ("metadata", ""))


class DirectArmTests(unittest.TestCase):
    """BENCH-1: the 'AI writes the app directly' arm."""

    @classmethod
    def setUpClass(cls):
        cls.spec = direct.load_spec()

    def test_the_prompt_tells_the_model_everything_the_probes_check(self):
        st = direct.stack_for(self.spec, "direct-node")
        text = direct.system_prompt(self.spec, st)
        for needle in ("PORT", "/v3/api-docs", "X-Api-Key", "NPDEV_BENCH_ROLE_KEYS", "400 or 422", "/api/flows/<flowName>/execute",
                       "survives a restart", st["label"], "express", "better-sqlite3"):
            self.assertIn(needle, text)
        self.assertNotIn("{probeContract}", text)
        self.assertIn("standard library only", direct.system_prompt(self.spec, direct.stack_for(self.spec, "direct-python")))

    def test_files_are_parsed_and_a_fence_around_a_file_is_stripped(self):
        reply = ("Here you go\n=== FILE: app.py ===\n```python\nprint('hi')\n```\n=== FILE: sub/dir/readme.txt ===\nplain\n")
        files, err = direct.parse_files(reply)
        self.assertEqual(err, "")
        self.assertEqual(files, {"app.py": "print('hi')\n", "sub/dir/readme.txt": "plain\n"})

    def test_unsafe_paths_and_empty_replies_are_refused(self):
        for bad in ("../evil.py", "/etc/passwd", "C:\\Windows\\x.py", "a/../../b.py", "\\\\server\\share\\x"):
            files, err = direct.parse_files(f"=== FILE: {bad} ===\nx\n")
            self.assertEqual(files, {}, bad)
            self.assertIn("unsafe", err, bad)
        files, err = direct.parse_files("I could not do that.")
        self.assertEqual(files, {})
        self.assertIn("no `=== FILE", err)
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError):
                direct.write_files(Path(td) / "app", {"../escape.txt": "x"})

    def test_the_cleared_environment_has_no_secrets(self):
        with mock.patch.dict(os.environ, {"GROQ_API_KEY": "gsk_secret", "NPDEV_AUTH_API_KEYS": "x", "HOME": "/home/me",
                                          "USERPROFILE": "C:\\Users\\me", "PATH": os.environ.get("PATH", "")}):
            env = direct.clean_env({"PORT": "1234"})
        self.assertEqual(env["PORT"], "1234")
        self.assertNotIn("GROQ_API_KEY", env)
        self.assertNotIn("NPDEV_AUTH_API_KEYS", env)
        self.assertNotIn("HOME", env)
        self.assertNotIn("USERPROFILE", env)

    def test_one_key_per_role_plus_admin(self):
        keys = direct.role_keys_for({"roles": [{"name": "Mechanic"}], "probes": [{"as": "Manager"}, {"as": "admin"}, {}]})
        self.assertEqual(set(keys), {"Mechanic", "Manager", "admin"})
        self.assertEqual(len(set(keys.values())), 3)

    def test_dependencies_outside_the_allowlist_are_refused_before_anything_is_installed(self):
        with tempfile.TemporaryDirectory() as td:
            app = Path(td)
            (app / "package.json").write_text(json.dumps({"dependencies": {"express": "4", "left-pad": "1"}}), encoding="utf-8")
            res = direct.stage_install(direct.stack_for(self.spec, "direct-node"), app, app, 5)
            self.assertFalse(res.ok)
            self.assertIn("left-pad", res.detail)
            (app / "requirements.txt").write_text("requests==2\n", encoding="utf-8")
            res = direct.stage_install(direct.stack_for(self.spec, "direct-python"), app, app, 5)
            self.assertFalse(res.ok)
            self.assertIn("standard library", res.detail)

    def test_repair_message_carries_the_stage_and_the_error(self):
        history = direct.initial_messages(self.spec, direct.stack_for(self.spec, "direct-python"), "Make a task list.")
        msgs = direct.repair_messages(self.spec, history, "reply", "boot", "Traceback: boom")
        self.assertEqual([m["role"] for m in msgs], ["system", "user", "assistant", "user"])
        self.assertIn("`boot`", msgs[-1]["content"])
        self.assertIn("Traceback: boom", msgs[-1]["content"])

    def _run(self, td, replies=None, loops=0):
        args = mock.Mock(max_loops=loops, stop_when_stuck=0, keep_apps=True, build_timeout=60, boot_timeout=30)
        prompt = json.loads((run_bench.DRY_RUN_PROMPTS / "bench-900-canary-dry.json").read_text(encoding="utf-8"))
        backend = direct.FakeDirectBackend(replies)
        return direct.run_prompt_direct(prompt, backend, "direct-python", Path(td), args), backend

    def test_a_known_good_python_app_passes_the_real_probes_end_to_end(self):
        with tempfile.TemporaryDirectory() as td:
            result, backend = self._run(td)
            self.assertTrue(result["passed"], json.dumps(result["attempts"], indent=1)[:1500])
            self.assertEqual(result["arm"], "direct-python")
            self.assertEqual(result["tokens"]["prompt"], 1000)
            self.assertEqual(result["tokens"]["completion"], 700)
            self.assertEqual(result["filesWritten"], 1)
            stages_run = [s["stage"] for s in result["attempts"][-1]["stages"]]
            self.assertEqual(stages_run, ["author", "install", "boot", "acceptance"])
            self.assertEqual(len(backend.calls), 1)

    def test_a_broken_app_is_repaired_with_the_boot_error_and_tokens_add_up(self):
        broken = "=== FILE: app.py ===\nthis is not python (\n"
        with tempfile.TemporaryDirectory() as td:
            result, backend = self._run(td, replies=[broken], loops=2)
            self.assertTrue(result["passed"])
            self.assertEqual(result["loopsUsed"], 1)
            self.assertEqual(result["attempts"][0]["failingStage"], "boot")
            self.assertEqual(result["tokens"]["total"], 2 * 1700)
            repair_turn = backend.calls[1][-1]["content"]
            self.assertIn("SyntaxError", repair_turn)

    def test_the_cli_runs_the_direct_arm_in_dry_run_mode(self):
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as td, contextlib.redirect_stdout(out):
            rc = run_bench.main(["run", "--arm", "direct-python", "--dry-run", "--out-root", td, "--run-id", "d1"])
            summary = json.loads((Path(td) / "d1" / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(rc, 0)
        self.assertEqual(summary["arm"], "direct-python")
        self.assertEqual(summary["scorecard"]["passWithin5LoopsRate"], 1.0)

    def test_the_direct_arm_refuses_to_run_elevated(self):
        err = io.StringIO()
        with mock.patch.object(direct, "is_admin", return_value=True), contextlib.redirect_stderr(err):
            rc = run_bench.main(["run", "--arm", "direct-node", "--prompt", "none"])
        self.assertEqual(rc, 3)
        self.assertIn("administrator", err.getvalue())

    def test_the_arm_choices_are_the_two_npdev_arms_and_the_three_stacks(self):
        self.assertEqual(run_bench.ARMS, ["npdev", "npdev-no-knowledge", "direct-python", "direct-node", "direct-spring",
                                          "relay"])


class EvolutionTests(unittest.TestCase):
    """BENCH-4: changes applied to an app that already passes."""

    BASE = {"concepts": [{"name": "Task", "fields": [{"name": "title", "type": "text", "required": True}]}],
            "rules": [{"id": "r1", "description": "A task without a title is refused.", "check": {"kind": "rest-reject", "probes": ["p2"]}}],
            "probes": [{"id": "p2", "purpose": "assert", "method": "POST", "pathHint": "{concept:Task}", "body": {}, "expectStatus": [400]}],
            "minimums": {"concepts": 1, "fields": 1}}

    def test_a_delta_merges_without_touching_the_base_and_replaces_minimums(self):
        delta = {"concepts": [{"name": "Task", "fields": [{"name": "notes", "type": "text"}]}, {"name": "Owner", "fields": [{"name": "name", "type": "text"}]}],
                 "rules": [{"id": "r2", "description": "Notes are kept on a task.", "check": {"kind": "rest-accept", "probes": ["p3"]}}],
                 "probes": [{"id": "p3", "purpose": "assert", "method": "POST", "pathHint": "{concept:Task}", "body": {}, "expectStatus": [201]}],
                 "minimums": {"fields": 3}}
        before = copy.deepcopy(self.BASE)
        merged = evolve.merge_acceptance(self.BASE, delta)
        self.assertEqual(self.BASE, before)
        self.assertEqual([f["name"] for f in merged["concepts"][0]["fields"]], ["title", "notes"])
        self.assertEqual([c["name"] for c in merged["concepts"]], ["Task", "Owner"])
        self.assertEqual([r["id"] for r in merged["rules"]], ["r1", "r2"])
        self.assertEqual(merged["minimums"], {"concepts": 1, "fields": 3})

    def test_a_change_must_use_new_ids(self):
        with self.assertRaises(evolve.EvolutionError):
            evolve.merge_acceptance(self.BASE, {"probes": [dict(self.BASE["probes"][0])]})

    def test_validate_changes_names_the_problem(self):
        prompt = {"id": "bench-x", "acceptance": self.BASE, "changes": [
            {"id": "c1", "request": "short", "acceptanceDelta": {"rules": [
                {"id": "r2", "description": "x" * 12, "check": {"kind": "rest-accept", "probes": ["p9"]}}]}}]}
        problems = " | ".join(evolve.validate_changes(prompt))
        self.assertIn("too short", problems)
        self.assertIn("unknown probe p9", problems)

    def test_every_shipped_prompt_change_merges_cleanly(self):
        count = 0
        for path in sorted((Path(__file__).resolve().parents[3] / "NPDevSamples" / "ai-bench" / "prompts").glob("bench-*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("changes"):
                count += 1
                self.assertEqual(evolve.validate_changes(data), [], path.name)
        self.assertEqual(count, 6, "two prompts per tier carry changes[]")

    def _evolve(self, replies, loops=0):
        args = mock.Mock(max_loops=loops, stop_when_stuck=0, keep_apps=True, build_timeout=60, boot_timeout=30, evolve=True)
        prompt = json.loads((run_bench.DRY_RUN_PROMPTS / "bench-900-canary-dry.json").read_text(encoding="utf-8"))
        backend = direct.FakeDirectBackend(replies)
        with tempfile.TemporaryDirectory() as td:
            result = direct.run_prompt_direct(prompt, backend, "direct-python", Path(td), args)
            self.assertTrue(result["passed"])
            result = run_bench.run_evolution(prompt, result, backend, None, Path(td), args, "direct-python")
            return result, backend

    def test_a_working_app_is_changed_and_the_change_cost_is_recorded(self):
        result, backend = self._evolve([direct.FakeDirectBackend.good_reply(), direct.FakeDirectBackend.evolved_reply()])
        self.assertEqual(result["changePassRate"], 1.0)
        self.assertEqual(result["changeTokens"], 1700)           # one call: 1000 in + 700 out, counted separately below
        self.assertEqual((result["changeTokensInput"], result["changeTokensOutput"]), (1000, 700))
        self.assertTrue(result["changes"][0]["passed"])
        self.assertEqual(result["changes"][0]["regressions"], [])
        # the model was shown its own working app and the owner's request, and told to reply with changed files only
        last = backend.calls[1]
        self.assertEqual([m["role"] for m in last], ["system", "user", "assistant", "user"])
        self.assertIn("=== FILE: app.py ===", last[2]["content"])
        self.assertIn("notes field", last[3]["content"])
        self.assertIn("ONLY the files that change", last[3]["content"])

    def test_a_change_that_is_not_delivered_fails_and_later_changes_are_not_attempted(self):
        result, _ = self._evolve([direct.FakeDirectBackend.good_reply(), direct.FakeDirectBackend.good_reply()])
        self.assertEqual(result["changePassRate"], 0.0)
        self.assertFalse(result["changes"][0]["passed"])
        self.assertEqual(result["changes"][0]["failingStage"], "acceptance")
        self.assertEqual(result["changesAttempted"], 1)

    def test_the_npdev_arm_sends_the_knowledge_the_model_and_the_request(self):
        msgs = author.change_messages("Make a task list for my team.", "KNOWLEDGE-CARDS", '{"concepts": []}', "Add a notes field to tasks.")
        self.assertEqual([m["role"] for m in msgs], ["system", "user", "assistant", "user"])
        self.assertIn("KNOWLEDGE-CARDS", msgs[0]["content"])
        self.assertIn('{"concepts": []}', msgs[2]["content"])
        self.assertIn("notes field", msgs[3]["content"])
        self.assertIn("COMPLETE updated model.json", msgs[3]["content"])

    def test_the_npdev_adapter_applies_the_edit_through_the_normal_attempt(self):
        prompt = json.loads((run_bench.DRY_RUN_PROMPTS / "bench-900-canary-dry.json").read_text(encoding="utf-8"))
        backend = backends.FakeBackend(CANARY_MODEL)
        args = mock.Mock(max_loops=0)
        seen = {}

        def fake_attempt(n, model_text, p, change_dir, a, stage_log):
            seen["acceptance_probes"] = [x["id"] for x in p["acceptance"]["probes"]]
            return {"failingStage": None, "feedback": "", "stages": stage_log, "modelText": model_text}

        with tempfile.TemporaryDirectory() as td, mock.patch.object(run_bench, "run_attempt", side_effect=fake_attempt):
            adapter = run_bench.NpdevEvolution(prompt, "K", '{"x": 1}', args)
            block = evolve.apply_changes(prompt, adapter, backend, args, Path(td))
        self.assertEqual(block["changePassRate"], 1.0)
        self.assertEqual(seen["acceptance_probes"], ["p1", "p2", "p3", "p4", "p5"], "the FULL acceptance is re-run, old probes included")
        self.assertEqual(block["changeTokens"], 1500)


class CompareTests(unittest.TestCase):
    """BENCH-3: one report across arms."""

    @staticmethod
    def _result(pid, tier, passed, prompt_tokens, completion_tokens, probes=4, arm="npdev", outcome=None, wall=10.0, loops=0):
        stage = {"stage": "acceptance", "ok": passed, "probeCounts": {"passed": probes if passed else 1, "failed": 0, "unresolved": 0}}
        return {"promptId": pid, "tier": tier, "arm": arm, "passed": passed, "outcome": outcome or ("pass" if passed else "fail"),
                "loopsUsed": loops, "firstAttemptPassed": passed and loops == 0, "wallSeconds": wall, "costUsd": 0.0,
                "tokens": {"prompt": prompt_tokens, "completion": completion_tokens, "total": prompt_tokens + completion_tokens},
                "attempts": [{"attempt": 0, "failingStage": None if passed else "acceptance", "stages": [stage]}]}

    def _run(self, root, name, arm, results):
        d = Path(root) / name
        for r in results:
            (d / r["promptId"]).mkdir(parents=True, exist_ok=True)
            (d / r["promptId"] / "result.json").write_text(json.dumps(r), encoding="utf-8")
        (d / "summary.json").write_text(json.dumps({"arm": arm}), encoding="utf-8")
        return d

    def test_per_tier_per_arm_numbers_and_the_input_output_split(self):
        with tempfile.TemporaryDirectory() as td:
            a = self._run(td, "a", "npdev", [self._result("bench-001", "simple-crud", True, 30000, 2000),
                                             self._result("bench-002", "simple-crud", True, 32000, 4000, loops=1),
                                             self._result("bench-011", "workflow-roles", False, 60000, 6000)])
            b = self._run(td, "b", "direct-python", [self._result("bench-001", "simple-crud", True, 2000, 5000, arm="direct-python"),
                                                    self._result("bench-002", "simple-crud", True, 2500, 7000, arm="direct-python"),
                                                    self._result("bench-011", "workflow-roles", True, 3000, 9000, arm="direct-python")])
            out = Path(td) / "out"
            with contextlib.redirect_stdout(io.StringIO()):
                rc = run_bench.main(["compare", str(a), str(b), "--out", str(out)])
            self.assertEqual(rc, 0)
            data = json.loads((out / "comparison.json").read_text(encoding="utf-8"))
            html_text = (out / "comparison.html").read_text(encoding="utf-8")
        self.assertEqual(data["arms"], ["npdev", "direct-python"])
        crud = data["table"]["simple-crud"]
        self.assertEqual(crud["npdev"]["passRate"], 1.0)
        self.assertEqual(crud["npdev"]["medianTokensPerPassedApp"], 34000)    # median of 32000 and 36000
        self.assertEqual(crud["npdev"]["inputTokensPerPassedApp"], 31000)
        self.assertEqual(crud["npdev"]["outputTokensPerPassedApp"], 3000)
        self.assertEqual(crud["npdev"]["tokensPerPassedProbe"], 8500.0)       # 68000 tokens / 8 probes
        self.assertEqual(crud["direct-python"]["medianTokensPerPassedApp"], 8250)
        overall = data["table"]["all"]
        self.assertAlmostEqual(overall["npdev"]["passRate"], 2 / 3, places=3)
        self.assertEqual(overall["direct-python"]["passRate"], 1.0)
        for tier in ("simple-crud", "workflow-roles", "all"):
            self.assertIn(tier, html_text)
        self.assertIn("direct-python", html_text)

    def test_the_reading_says_so_when_npdev_loses(self):
        with tempfile.TemporaryDirectory() as td:
            a = self._run(td, "a", "npdev", [self._result("bench-001", "simple-crud", False, 30000, 2000)])
            b = self._run(td, "b", "direct-python", [self._result("bench-001", "simple-crud", True, 2000, 5000, arm="direct-python")])
            data = compare.compare([compare.load_run(a), compare.load_run(b)])
        text = " ".join(data["reading"])
        self.assertIn("0% of prompts vs 100%", text)
        self.assertIn("lower for npdev", text)
        self.assertIn("no passing app", text)

    def test_only_prompts_every_arm_finished_are_counted(self):
        with tempfile.TemporaryDirectory() as td:
            a = self._run(td, "a", "npdev", [self._result("bench-001", "simple-crud", True, 1, 1),
                                             self._result("bench-002", "simple-crud", True, 1, 1),
                                             self._result("bench-003", "simple-crud", True, 1, 1)])
            b = self._run(td, "b", "direct-python", [self._result("bench-001", "simple-crud", True, 1, 1, arm="direct-python"),
                                                    self._result("bench-002", "simple-crud", False, 1, 1, arm="direct-python", outcome="infra")])
            only = compare.compare([compare.load_run(a), compare.load_run(b)])
            every = compare.compare([compare.load_run(a), compare.load_run(b)], common_only=False)
        self.assertEqual(only["promptsCompared"], 1)                  # bench-002 was not finished by direct-python (quota)
        self.assertEqual(only["promptsDroppedForNotBeingFinishedByEveryArm"], 2)
        self.assertEqual(every["table"]["all"]["npdev"]["prompts"], 3)

    def test_an_interval_needs_at_least_three_prompts(self):
        rows = [self._result(f"bench-00{i}", "simple-crud", True, 1000 * i, 100) for i in range(1, 5)]
        self.assertIsNotNone(compare.cell(rows)["medianTokensInterval95"])
        self.assertIsNone(compare.cell(rows[:2])["medianTokensInterval95"])

    def test_the_evolution_numbers_are_reported_per_arm(self):
        def evolved(pid, passes, tokens):
            r = self._result(pid, "simple-crud", True, 30000, 2000)
            r["changesDeclared"] = 1
            r["changes"] = [{"id": "c1", "passed": passes, "tokens": {"total": tokens}, "regressions": [] if passes else ["p1"]}]
            return r
        rows = [evolved("bench-001", True, 4000), evolved("bench-002", True, 6000), evolved("bench-003", False, 9000)]
        cell = compare.cell(rows)
        self.assertEqual(cell["changesDeclared"], 3)
        self.assertAlmostEqual(cell["changePassRate"], 2 / 3, places=3)
        self.assertEqual(cell["medianTokensPerChange"], 5000)
        self.assertEqual(cell["changeRegressedProbes"], 1)
        self.assertIsNone(compare.cell([self._result("bench-004", "simple-crud", True, 1, 1)])["changePassRate"])

    def test_two_dry_runs_produce_both_files(self):
        with tempfile.TemporaryDirectory() as td:
            for run_id in ("d1", "d2"):
                with contextlib.redirect_stdout(io.StringIO()):
                    run_bench.main(["run", "--arm", "direct-python", "--dry-run", "--out-root", td, "--run-id", run_id])
            with contextlib.redirect_stdout(io.StringIO()):
                rc = run_bench.main(["compare", str(Path(td) / "d1"), str(Path(td) / "d2"), "--out", str(Path(td) / "cmp")])
            self.assertEqual(rc, 0)
            self.assertTrue((Path(td) / "cmp" / "comparison.json").exists())
            self.assertTrue((Path(td) / "cmp" / "comparison.html").exists())


class RelayArmTests(unittest.TestCase):
    """T1.7: the relay arm -- ONE user message with no system role, every reply read by `npdev ai relay`'s ingest,
    repairs sent in the same chat. A fake chat AI and a fake attempt runner: no model, no Gradle, no app boot."""

    PROMPT = {"id": "bench-905", "tier": "simple-crud", "title": "t", "prompt": "make a bike shop app", "acceptance": {}}
    KNOWLEDGE = "## Authoring contract\nKNOWLEDGE-MARKER rules\n\n## Golden example: a\n{}\n"
    GOOD = {"failingStage": None, "feedback": ""}

    def _run(self, td, replies=None, outcomes=None, loops=3, mutations=(), stuck=0):
        backend = relay_arm.FakeRelayBackend(CANARY_MODEL, replies=replies)
        queue = iter(outcomes or [self.GOOD])
        seen: list[str] = []

        def runner(n, model_text, prompt, prompt_dir, args, stage_log):
            seen.append(model_text)
            return dict(next(queue), stages=stage_log)

        args = mock.Mock(max_loops=loops, stop_when_stuck=stuck, relay_copy_fidelity=mutations)
        result = relay_arm.run_prompt_relay(self.PROMPT, backend, self.KNOWLEDGE, Path(td) / "run-r", args, runner)
        return result, backend, seen

    @staticmethod
    def _code() -> str:
        return relay_arm.session_code("run-r", "bench-905")

    @staticmethod
    def _good_reply(rnd: int = 1) -> str:
        return relay_arm.good_reply(RelayArmTests._code(), rnd, CANARY_MODEL.read_text(encoding="utf-8"))

    def test_round_one_is_a_single_user_message_with_the_compact_bundle_and_no_system_role(self):
        with tempfile.TemporaryDirectory() as td:
            res, backend, seen = self._run(td)
        first = backend.calls[0]
        self.assertEqual([m["role"] for m in first], ["user"])
        composed = relay_arm.relay_prompts.compose_round1(
            relay_arm.relay_prompts.load_prompts(), code=self._code(), intent="create", ask="make a bike shop app",
            language="English", knowledge=self.KNOWLEDGE)
        self.assertEqual(first[0]["content"], composed)
        self.assertTrue(first[0]["content"].startswith(f"NPDEV-PROMPT {self._code()}-1\n"))
        self.assertIn("KNOWLEDGE-MARKER", first[0]["content"])
        self.assertIn("make a bike shop app", first[0]["content"])
        self.assertTrue(res["passed"])
        self.assertEqual((res["arm"], res["repairMode"], res["loopsUsed"]), ("relay", "same-chat", 0))
        self.assertTrue(res["firstAttemptPassed"])
        self.assertEqual(res["tokens"], {"prompt": 1000, "completion": 500, "total": 1500})
        self.assertEqual(res["relay"]["rounds"], [{"round": 1, "ingestOutcome": "PARSED", "findings": []}])
        self.assertEqual(json.loads(seen[0]), json.loads(CANARY_MODEL.read_text(encoding="utf-8")),
                         "the candidate is files[model.json].content, not the raw reply")

    def test_a_failing_stage_is_repaired_in_the_same_chat_with_the_needs_fix_follow_up(self):
        outcomes = [{"failingStage": "validate", "feedback": "- MISSING_NAMESPACE | $ | namespace is required"}, self.GOOD]
        with tempfile.TemporaryDirectory() as td:
            res, backend, seen = self._run(td, outcomes=outcomes)
        self.assertEqual(len(backend.calls), 2)
        second = backend.calls[1]
        self.assertEqual([m["role"] for m in second], ["user", "assistant", "user"])
        self.assertEqual(second[0], backend.calls[0][0], "the first message is never re-composed")
        self.assertIn("```json", second[1]["content"])
        follow = second[2]["content"]
        self.assertTrue(follow.startswith(f"NPDEV-PROMPT {self._code()}-2\n"))
        self.assertIn("NPDev checked your model and found these problems", follow)
        self.assertIn("The 'validate' stage failed", follow)
        self.assertIn("MISSING_NAMESPACE", follow)
        self.assertNotIn("KNOWLEDGE-MARKER", follow, "a same-chat follow-up never repeats the knowledge")
        self.assertTrue(res["passed"])
        self.assertEqual(res["loopsUsed"], 1)
        self.assertFalse(res["firstAttemptPassed"])
        self.assertEqual(res["firstAttemptFailingStage"], "validate")
        self.assertFalse(res["firstAttemptValid"], "failing at validate is not a valid first attempt")
        self.assertEqual([r["ingestOutcome"] for r in res["relay"]["rounds"]], ["PARSED", "PARSED"])
        self.assertEqual(res["tokens"]["total"], 3000)
        self.assertEqual(len(seen), 2)

    def test_every_unusable_answer_gets_its_own_same_chat_follow_up(self):
        elided = ("NPDEV-BEGIN @CODE@-@ROUND@\nok\nNPDEV-FILE model.json\n```json\n"
                  '{"namespace": "x", "concepts": [\n  ...\n]}\n```\nNPDEV-END @CODE@-@ROUND@ files=1\n')
        bad_json = ('NPDEV-BEGIN @CODE@-@ROUND@\nok\nNPDEV-FILE model.json\n```json\n'
                    '{"namespace": "x" "concepts": []}\n```\nNPDEV-END @CODE@-@ROUND@ files=1\n')
        cut = self._good_reply()[: len(self._good_reply()) // 2]
        cases = [("NO_FILES", "Sure, I can help with that. Tell me about the shop.", "Please answer with the files"),
                 ("TRUNCATED", cut, "cut off before the end"),
                 ("ELIDED", elided, "left parts out"),
                 ("BAD_JSON", bad_json, "could not read the JSON"),
                 ("FILE_LINK_ONLY", "Done, I saved it: [model.json](sandbox:/mnt/data/model.json)",
                  "cannot open files or links")]
        for outcome, reply, phrase in cases:
            with self.subTest(outcome):
                with tempfile.TemporaryDirectory() as td:
                    res, backend, seen = self._run(td, replies=[reply])
                self.assertEqual(res["relay"]["rounds"][0]["ingestOutcome"], outcome)
                self.assertEqual(len(seen), 1, "the stages only ever see the second, usable answer")
                follow = backend.calls[1][-1]["content"]
                self.assertIn(phrase, follow)
                self.assertTrue(follow.startswith(f"NPDEV-PROMPT {self._code()}-2\n"))
                self.assertEqual(backend.calls[1][1]["content"],
                                 reply.replace("@CODE@", self._code()).replace("@ROUND@", "1"),
                                 "history holds what the AI really said")
                self.assertTrue(res["passed"])
                self.assertEqual((res["loopsUsed"], res["firstAttemptFailingStage"]), (1, "author"))
                self.assertIn(f"ingest outcome {outcome}", res["attempts"][0]["feedbackExcerpt"])
                self.assertEqual(res["attempts"][0]["stages"][0]["ingestOutcome"], outcome)

    def test_an_outcome_that_needs_the_person_ends_the_prompt_without_a_follow_up(self):
        for outcome, reply in (("QUESTIONS", "Which currency should the shop use?"),
                               ("WRONG_SESSION", "NPDEV-BEGIN 0000-1\nhello\nNPDEV-END 0000-1 files=0\n")):
            with self.subTest(outcome):
                with tempfile.TemporaryDirectory() as td:
                    res, backend, seen = self._run(td, replies=[reply])
                self.assertEqual(len(backend.calls), 1)
                self.assertEqual(seen, [])
                self.assertFalse(res["passed"])
                self.assertEqual((res["outcome"], res["failingStage"]), ("fail", "author"))
                self.assertEqual(res["relay"]["stoppedNeedsPerson"], outcome)

    def test_the_chat_services_limit_message_is_infrastructure_not_an_authoring_failure(self):
        with tempfile.TemporaryDirectory() as td:
            res, backend, seen = self._run(td, replies=["You've reached your daily message limit. Try again tomorrow."])
        self.assertEqual((res["outcome"], res["infraKind"]), ("infra", "quota"))
        self.assertEqual(len(backend.calls), 1)
        self.assertEqual(run_bench.outcome_of(res), "infra")

    def test_an_unreachable_model_stops_the_prompt_as_infrastructure(self):
        class Down:
            info = backends.BackendInfo("down", "fake", "fake://down", "down")

            def complete(self, messages):
                raise backends.BackendError("HTTP 429 from down: quota")

        args = mock.Mock(max_loops=3, stop_when_stuck=0, relay_copy_fidelity=())
        with tempfile.TemporaryDirectory() as td:
            res = relay_arm.run_prompt_relay(self.PROMPT, Down(), self.KNOWLEDGE, Path(td) / "run-r", args,
                                             lambda *a, **k: self.fail("no model answer, no stage"))
        self.assertEqual((res["outcome"], res["infraKind"]), ("infra", "quota"))

    def test_rounds_are_bounded_by_max_loops_and_stuck_runs_stop_early(self):
        same = {"failingStage": "validate", "feedback": "- E1 | $ | the same error"}
        with tempfile.TemporaryDirectory() as td:
            res, backend, _ = self._run(td, outcomes=[same] * 10, loops=2)
        self.assertEqual((len(backend.calls), res["loopsUsed"], res["passed"]), (3, 2, False))
        self.assertEqual(res["failingStage"], "validate")
        with tempfile.TemporaryDirectory() as td:
            res, backend, _ = self._run(td, outcomes=[same] * 10, loops=5, stuck=2)
        self.assertTrue(res["stoppedStuck"])
        self.assertEqual(len(backend.calls), 2)

    def test_the_copy_fidelity_mutators_degrade_exactly_what_they_say(self):
        reply = self._good_reply()
        self.assertEqual(relay_arm.mutate_copy(reply, ()), reply)
        no_fences = relay_arm.mutate_copy(reply, ("fences",))
        self.assertNotIn("```", no_fences)
        self.assertIn("NPDEV-BEGIN", no_fences)
        no_markers = relay_arm.mutate_copy(reply, ("markers",))
        self.assertNotIn("NPDEV-", no_markers)
        self.assertIn("```json", no_markers)
        smart = relay_arm.mutate_copy(reply, ("smart-quotes",))
        self.assertNotIn('"', smart)
        self.assertIn("“namespace”", smart)
        button = relay_arm.mutate_copy(reply, ("copy-button",))
        self.assertEqual(button.count("\nCopy\n"), 3, "after each of the two code blocks and at the very end")
        self.assertTrue(button.endswith("\nCopy\n"))
        # the order is fixed whatever order is asked for
        self.assertEqual(relay_arm.mutate_copy(reply, ("fences", "copy-button")),
                         relay_arm.mutate_copy(reply, ("copy-button", "fences")))

    def test_ingest_reads_the_same_model_through_every_copy_fidelity_mutation(self):
        want = json.loads(CANARY_MODEL.read_text(encoding="utf-8"))
        policy = relay_arm.ingest.load_policy()
        for spec in ("fences", "markers", "smart-quotes", "copy-button", "fences,markers", "all"):
            with self.subTest(spec):
                seen = relay_arm.mutate_copy(self._good_reply(), relay_arm.parse_copy_fidelity(spec))
                result = relay_arm.ingest.ingest_text(seen, session=self._code(), expected_round=1, policy=policy)
                self.assertEqual(result["outcome"], "PARSED", result["findings"])
                self.assertEqual(json.loads(relay_arm.candidate_model(result)), want)

    def test_a_mutated_reply_reaches_ingest_but_the_history_keeps_the_original(self):
        outcomes = [{"failingStage": "validate", "feedback": "- E1 | $ | x"}, self.GOOD]
        with tempfile.TemporaryDirectory() as td:
            res, backend, seen = self._run(td, outcomes=outcomes, mutations=("fences", "smart-quotes"))
            adir = Path(td) / "run-r" / "bench-905" / "attempt-0"
            copied = (adir / "author-reply-copied.txt").read_text(encoding="utf-8")
            original = (adir / "author-reply.txt").read_text(encoding="utf-8")
            self.assertTrue((adir / "ingest-result.json").exists())
        self.assertNotIn("```", copied)
        self.assertIn("```json", original)
        self.assertEqual(backend.calls[1][1]["content"], original)
        self.assertEqual(res["relay"]["copyFidelity"], ["smart-quotes", "fences"])
        self.assertEqual(set(res["relay"]["rounds"][0]["findings"]), {"fences-missing", "json-repaired"})
        self.assertEqual(json.loads(seen[0]), json.loads(CANARY_MODEL.read_text(encoding="utf-8")))

    def test_the_copy_fidelity_flag_parses_and_rejects_a_typo(self):
        parse = relay_arm.parse_copy_fidelity
        self.assertEqual(parse(None), ())
        self.assertEqual(parse("none"), ())
        self.assertEqual(parse("all"), relay_arm.COPY_MUTATIONS)
        self.assertEqual(parse("fences, markers"), ("markers", "fences"))
        with self.assertRaises(__import__("argparse").ArgumentTypeError):
            parse("fences,smart-quote")

    def test_the_session_code_is_stable_per_run_and_prompt_and_uses_the_protocol_alphabet(self):
        a = relay_arm.session_code("r1", "bench-001")
        self.assertEqual(a, relay_arm.session_code("r1", "bench-001"))
        self.assertNotEqual(a, relay_arm.session_code("r1", "bench-002"))
        self.assertEqual(len(a), 4)
        self.assertTrue(set(a) <= set(relay_arm.ingest.SESSION_ALPHABET))

    def test_the_summary_block_hashes_what_was_measured_and_counts_ingest_outcomes(self):
        results = [{"relay": {"rounds": [{"ingestOutcome": "NO_FILES"}, {"ingestOutcome": "PARSED"}]}},
                   {"relay": {"rounds": [{"ingestOutcome": "PARSED"}]}}, {"promptId": "old-result-without-relay"}]
        block = relay_arm.summarize(mock.Mock(relay_copy_fidelity=("fences",)), results)
        self.assertEqual(block["ingestOutcomeHistogram"], {"NO_FILES": 1, "PARSED": 2})
        self.assertEqual(block["firstRoundParsedRate"], 0.5)
        self.assertEqual(block["meanRoundsPerPrompt"], 1.5)
        self.assertEqual(block["copyFidelity"], ["fences"])
        self.assertEqual(len(block["promptsSha256"]), 16)
        self.assertEqual(len(block["ingestPolicySha256"]), 16)

    def test_the_cli_runs_the_relay_arm_in_dry_run_mode_and_records_the_relay_block(self):
        def runner(n, model_text, prompt, prompt_dir, args, stage_log):
            return {"stages": stage_log, "failingStage": None, "feedback": "", "modelText": model_text}

        out = io.StringIO()
        with tempfile.TemporaryDirectory() as td, contextlib.redirect_stdout(out), \
                mock.patch.object(run_bench, "run_attempt", side_effect=runner):
            rc = run_bench.main(["run", "--arm", "relay", "--dry-run", "--out-root", td, "--run-id", "rr1",
                                 "--relay-copy-fidelity", "all"])
            summary = json.loads((Path(td) / "rr1" / "summary.json").read_text(encoding="utf-8"))
            result = json.loads((Path(td) / "rr1" / "bench-900" / "result.json").read_text(encoding="utf-8"))
        self.assertEqual(rc, 0)
        self.assertEqual((summary["arm"], summary["repairMode"], summary["ablation"]), ("relay", "same-chat", "none"))
        self.assertIn("compact", summary["knowledge"]["source"])
        self.assertGreater(summary["knowledge"]["bytes"], 5000)
        self.assertEqual(summary["relay"]["copyFidelity"], list(relay_arm.COPY_MUTATIONS))
        self.assertEqual(summary["relay"]["firstRoundParsedRate"], 1.0)
        self.assertEqual(result["arm"], "relay")
        self.assertEqual(summary["scorecard"]["passRateCompleted"], 1.0)

    def test_the_cli_refuses_options_that_would_label_another_measurement_relay(self):
        for argv in (["--arm", "relay", "--evolve"], ["--arm", "relay", "--ablation", "no-knowledge"],
                     ["--arm", "relay", "--repair-mode", "stateless"], ["--arm", "npdev", "--relay-copy-fidelity", "all"]):
            with self.subTest(argv):
                err = io.StringIO()
                with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                    rc = run_bench.main(["run", "--dry-run", *argv])
                self.assertEqual(rc, 3)
                self.assertIn("REFUSED", err.getvalue())

    def test_the_other_arms_never_see_the_relay_block(self):
        with tempfile.TemporaryDirectory() as td, contextlib.redirect_stdout(io.StringIO()):
            run_bench.main(["run", "--arm", "direct-python", "--dry-run", "--out-root", td, "--run-id", "d1"])
            summary = json.loads((Path(td) / "d1" / "summary.json").read_text(encoding="utf-8"))
        self.assertNotIn("relay", summary)


if __name__ == "__main__":
    unittest.main()
