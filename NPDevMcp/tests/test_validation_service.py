"""Tests for NPDevMcp/validation_service.py -- the local validation service (Path A REST + Path B MCP).

Stdlib unittest, no new dependency. The service runs in a thread on port 0; the validator is an
injected fake (fast, deterministic) except ONE test that uses the real warm jar and is skipped when
the jar or Java is absent. Run with:
    python -m unittest NPDevMcp.tests.test_validation_service -v
or  python -m unittest discover -s NPDevMcp/tests -p "test_validation_service.py" -v

Portable-tooling note: no literal in this file may look like a drive-letter path; every path comes
from tempfile/Path at run time.
"""

from __future__ import annotations

import copy
import http.client
import json
import os
import shutil
import tempfile
import threading
import subprocess
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import server  # noqa: E402
import validation_service as vs  # noqa: E402

_FIXTURE = Path(__file__).resolve().parent / "fixtures" / "validator" / "valid-minimal.json"
_MCP_DIR = Path(__file__).resolve().parent.parent
_RESPONSE_SCHEMA = Path(__file__).resolve().parents[2] / "schemas" / "ai" / "npdev-validation-service-response.schema.json"


def _diag(severity: str, code: str, path: str = "$", message: str = "m", **extra) -> dict:
    return {"layer": "semantic", "severity": severity, "code": code, "message": message, "path": path, **extra}


def _report(diags: list, model: str = "model.json") -> dict:
    errors = sum(1 for d in diags if d["severity"] == "error")
    warnings = sum(1 for d in diags if d["severity"] == "warning")
    status = "failed" if errors else ("warning" if warnings else "passed")
    return {"contractVersion": "npdev-validation-report.v2", "status": status, "model": model,
            "summary": {"errors": errors, "warnings": warnings}, "diagnostics": diags}


class FakeValidator:
    """A validate_fn that records what it was handed and answers from a canned report."""

    def __init__(self, diags=None, exit_code=None, report="auto", raises=None, gate=None, entered=None,
                 message_with_dir=False):
        self.diags = diags or []
        self.exit_code = exit_code
        self.report = report
        self.raises = raises
        self.gate = gate
        self.entered = entered
        self.message_with_dir = message_with_dir
        self.calls: list = []

    def __call__(self, model_path: Path):
        self.calls.append({"path": model_path, "content": json.loads(model_path.read_text(encoding="utf-8"))})
        if self.entered is not None:
            self.entered.set()
        if self.gate is not None:
            self.gate.wait(10)
        if self.raises is not None:
            raise self.raises
        if self.report is None:
            return 1, None, "boom in " + str(model_path.parent)
        diags = list(self.diags)
        if self.message_with_dir:
            diags.append(_diag("error", "leak", message="see " + str(model_path.parent) + " for details"))
        report = _report(diags, model=str(model_path)) if self.report == "auto" else self.report
        code = self.exit_code if self.exit_code is not None else (2 if report.get("status") == "failed" else 0)
        return code, report, ""


class _Served:
    """Context manager: a service on 127.0.0.1:<free port> in a thread."""

    def __init__(self, service: vs.ValidationService):
        self.service = service
        self.httpd = vs.make_server(service, "127.0.0.1", 0)
        self.port = self.httpd.server_address[1]
        self._thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self) -> "_Served":
        self._thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self._thread.join(5)

    def call(self, method: str, path: str, body=None, headers=None):
        data = body.encode("utf-8") if isinstance(body, str) else body
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data, method=method,
                                         headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()

    def post_model(self, model, query="", content_type="application/json"):
        body = model if isinstance(model, (str, bytes)) else json.dumps(model)
        status, headers, raw = self.call("POST", "/api/validate" + query, body, {"Content-Type": content_type})
        return status, headers, json.loads(raw.decode("utf-8"))

    def rpc(self, token: str, message, headers=None):
        body = message if isinstance(message, (str, bytes)) else json.dumps(message)
        merged = {"Content-Type": "application/json"}
        merged.update(headers or {})
        status, hdrs, raw = self.call("POST", f"/mcp/{token}", body, merged)
        return status, hdrs, (json.loads(raw.decode("utf-8")) if raw else None)


def _service(validator=None, **kwargs) -> vs.ValidationService:
    kwargs.setdefault("validator_name", "fake")
    return vs.ValidationService(validate_fn=validator or FakeValidator(), **kwargs)


def _model() -> dict:
    return json.loads(_FIXTURE.read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------

class PureHelpersTest(unittest.TestCase):
    def test_sanitize_replaces_the_scratch_dir_in_both_slash_spellings_and_any_case(self):
        scratch = Path(tempfile.mkdtemp(prefix="npdev-validator-"))
        self.addCleanup(shutil.rmtree, str(scratch), True)
        text = str(scratch)
        variants = [text, text.replace("\\", "/"), text.upper(), text.lower(), text.replace("\\", "\\\\")]
        for variant in variants:
            cleaned = vs.sanitize(f"error in {variant}{os.sep}model.json line 3", scratch)
            self.assertIn("<model-dir>", cleaned, variant)
            self.assertNotIn(scratch.name.lower(), cleaned.lower(), variant)

    def test_sanitize_hides_a_user_home_directory_even_without_a_root(self):
        home_like = "/home/someone/projects/x"
        self.assertEqual("see <user-dir>/projects/x", vs.sanitize("see " + home_like))

    def test_next_step_sentences(self):
        self.assertEqual("The model is valid. You can stop.", vs.next_step("passed"))
        self.assertIn("5 warnings", vs.next_step("warning", {"warnings": 5}))
        self.assertIn("Fix the 2 errors", vs.next_step("failed", {"errors": 2}))
        self.assertIn("1 error below", vs.next_step("failed", {"errors": 1}))
        self.assertIn("line 12, column 5", vs.next_step("rejected", None, "invalid_json", line=12, column=5))
        for code in ("too_large", "busy", "validator_unavailable", "internal_error", "unsupported_media_type"):
            self.assertTrue(vs.next_step("rejected", None, code))

    def test_build_response_orders_errors_first_cuts_and_omits_empty_fields(self):
        diags = [_diag("warning", "w1"), _diag("error", "e1", hint="did you mean X"), _diag("warning", "w2"),
                 _diag("error", "e2", suggestedFix="")]
        payload = vs.build_service_response(_report(diags), max_diagnostics=3)
        self.assertEqual(["e1", "e2", "w1"], [d["code"] for d in payload["diagnostics"]])
        self.assertEqual({"shown": 3, "omitted": 1}, payload["truncated"])
        self.assertNotIn("suggestedFix", payload["diagnostics"][1])
        self.assertEqual("did you mean X", payload["diagnostics"][0]["hint"])
        self.assertEqual({"errors": 2, "warnings": 2}, payload["summary"])

    def test_build_response_severity_error_hides_warnings_without_marking_them_truncated(self):
        payload = vs.build_service_response(_report([_diag("error", "e"), _diag("warning", "w")]), severity="error")
        self.assertEqual(["e"], [d["code"] for d in payload["diagnostics"]])
        self.assertIsNone(payload["truncated"])

    def test_build_response_never_carries_the_model_path(self):
        scratch = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, str(scratch), True)
        report = _report([_diag("error", "e", message=f"bad file {scratch}")], model=str(scratch / "model.json"))
        payload = vs.build_service_response(report, temp_dir=scratch, full=True)
        self.assertEqual("model.json", payload["report"]["model"])
        self.assertNotIn(scratch.name, json.dumps(payload))

    def test_parse_model_text_variants(self):
        self.assertEqual({"a": 1}, vs.parse_model_text(b'\xef\xbb\xbf{"a": 1}'))  # BOM tolerated
        self.assertEqual({"a": 1}, vs.parse_model_text('{"model": {"a": 1}}'))  # envelope
        self.assertEqual({"model": 1, "x": 2}, vs.parse_model_text('{"model": 1, "x": 2}'))  # not an envelope
        for bad, code in ((b"[1]", "not_an_object"), (b'{"model": 3}', "not_an_object"),
                          (b'{"a": NaN}', "invalid_json"), (b"\xff\xfe", "invalid_json"),
                          (b"{" * 100000, "invalid_json")):
            with self.assertRaises(vs.Rejection) as caught:
                vs.parse_model_text(bad)
            self.assertEqual(code, caught.exception.code, bad[:20])
            self.assertEqual(422, caught.exception.http_status)

    def test_media_type_and_origin_rules(self):
        for ok in (None, "", "application/json", "application/json; charset=utf-8", "text/plain", "application/vnd.x+json"):
            self.assertTrue(vs.media_type_ok(ok), ok)
        for bad in ("application/x-www-form-urlencoded", "application/octet-stream", "image/png"):
            self.assertFalse(vs.media_type_ok(bad), bad)
        for ok in (None, "", "https://claude.ai", "https://chatgpt.com", "https://chat.mistral.ai",
                   "http://localhost:3000", "http://127.0.0.1:8780", "https://www.claude.ai"):
            self.assertTrue(vs.origin_allowed(ok), ok)
        for bad in ("https://evil.example", "null", "http://claude.ai", "https://claude.ai.evil.example", "garbage"):
            self.assertFalse(vs.origin_allowed(bad), bad)

    def test_rate_limiter_minute_and_hour_windows_with_an_injected_clock(self):
        now = [1000.0]
        limiter = vs.RateLimiter(per_minute=3, per_hour=5, clock=lambda: now[0])
        self.assertEqual([0, 0, 0], [limiter.check("a") for _ in range(3)])
        self.assertGreater(limiter.check("a"), 0)  # 4th inside the minute
        self.assertEqual(0, limiter.check("b"))  # other keys are independent
        now[0] += 61
        self.assertEqual(0, limiter.check("a"))
        self.assertEqual(0, limiter.check("a"))
        self.assertGreater(limiter.check("a"), 0)  # the hourly cap (5) is hit

    def test_token_store_picks_up_a_rotated_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "token.txt"
            first = vs.load_or_create_token(path)
            store = vs.TokenStore(path=path)
            self.assertTrue(store.matches(first))
            self.assertFalse(store.matches(first + "x"))
            self.assertEqual(first, vs.load_or_create_token(path))  # stable
            second = vs.load_or_create_token(path, rotate=True)
            os.utime(path, (os.path.getmtime(path) + 5, os.path.getmtime(path) + 5))
            self.assertNotEqual(first, second)
            self.assertTrue(store.matches(second))
            self.assertFalse(store.matches(first))

    def test_access_log_rotates_and_never_holds_a_body(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "logs" / "access.log"
            service = _service(log_path=log)
            service.log_access("POST", "/api/validate", 200, 12, 345, "passed", "https://x.example", "10.0.0.1")
            line = log.read_text(encoding="utf-8").strip()
            self.assertIn("POST /api/validate 200 12ms 345b passed", line)
            with patch.object(vs, "ACCESS_LOG_MAX_BYTES", 10):
                service.log_access("GET", "/", 200, 1, 0, "-", "", "")
                service.log_access("GET", "/", 200, 1, 0, "-", "", "")
            self.assertTrue((Path(tmp) / "logs" / "access.log.1").exists())


# ---------------------------------------------------------------------------
# Path A: REST
# ---------------------------------------------------------------------------

class RestApiTest(unittest.TestCase):
    def test_health(self):
        with _Served(_service(max_concurrent=2)) as srv:
            status, headers, raw = srv.call("GET", "/api/health")
        body = json.loads(raw)
        self.assertEqual(200, status)
        self.assertEqual({"status": "ok", "validator": "fake", "version": vs.SERVICE_VERSION, "busy": 0,
                          "maxConcurrent": 2, "mcp": False}, body)
        self.assertEqual("*", headers["Access-Control-Allow-Origin"])

    def test_passed_warning_and_failed(self):
        cases = (([], "passed", 0), ([_diag("warning", "w")], "warning", 0),
                 ([_diag("warning", "w"), _diag("error", "e")], "failed", 2))
        for diags, expected, _ in cases:
            with _Served(_service(FakeValidator(diags))) as srv:
                status, _, body = srv.post_model(_model())
            self.assertEqual(200, status)
            self.assertEqual(expected, body["status"])
            self.assertEqual(vs.CONTRACT, body["contractVersion"])
        self.assertEqual("error", body["diagnostics"][0]["severity"])  # errors before warnings

    def test_truncation_severity_filter_and_clamping_via_query(self):
        diags = [_diag("error", f"e{i}") for i in range(3)] + [_diag("warning", f"w{i}") for i in range(4)]
        with _Served(_service(FakeValidator(diags))) as srv:
            _, _, body = srv.post_model(_model(), "?maxDiagnostics=5")
            self.assertEqual({"shown": 5, "omitted": 2}, body["truncated"])
            _, _, body = srv.post_model(_model(), "?severity=error")
            self.assertEqual(3, len(body["diagnostics"]))
            self.assertIsNone(body["truncated"])
            _, _, body = srv.post_model(_model(), "?maxDiagnostics=0")
            self.assertEqual(1, len(body["diagnostics"]))
            _, _, body = srv.post_model(_model(), "?maxDiagnostics=banana")
            self.assertEqual(7, len(body["diagnostics"]))

    def test_invalid_json_gets_line_and_column_and_an_array_is_not_an_object(self):
        with _Served(_service()) as srv:
            status, _, body = srv.post_model('{\n  "a": 1,\n  oops\n}')
            self.assertEqual((422, "rejected", "invalid_json"), (status, body["status"], body["code"]))
            self.assertEqual((3, 3), (body["line"], body["column"]))
            self.assertIn("line 3, column 3", body["nextStep"])
            status, _, body = srv.post_model("[1, 2]")
            self.assertEqual((422, "not_an_object"), (status, body["code"]))

    def test_envelope_is_accepted_and_text_plain_too(self):
        validator = FakeValidator()
        with _Served(_service(validator)) as srv:
            status, _, body = srv.post_model({"model": _model()}, content_type="text/plain")
        self.assertEqual((200, "passed"), (status, body["status"]))
        self.assertEqual(_model(), validator.calls[0]["content"])

    def test_the_validator_sees_the_reserialised_model_in_a_scratch_dir_that_is_removed(self):
        validator = FakeValidator()
        with _Served(_service(validator)) as srv:
            srv.post_model(_model())
        path = validator.calls[0]["path"]
        self.assertEqual("model.json", path.name)
        self.assertFalse(path.parent.exists())

    def test_too_large_unsupported_media_and_missing_length(self):
        with _Served(_service(max_body_bytes=1000)) as srv:
            status, _, body = srv.post_model("x" * 5000)
            self.assertEqual((413, "too_large"), (status, body["code"]))
            status, _, body = srv.post_model("{}", content_type="application/x-www-form-urlencoded")
            self.assertEqual((415, "unsupported_media_type"), (status, body["code"]))
            conn = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=10)
            conn.putrequest("POST", "/api/validate")
            conn.putheader("Content-Type", "application/json")
            conn.endheaders()
            reply = conn.getresponse()
            self.assertEqual(411, reply.status)
            self.assertEqual("length_required", json.loads(reply.read())["code"])
            conn.close()

    def test_a_three_megabyte_body_is_413_at_the_default_limit(self):
        with _Served(_service()) as srv:
            status, _, body = srv.post_model('{"a": "' + "x" * (3 * 1024 * 1024) + '"}')
        self.assertEqual((413, "too_large"), (status, body["code"]))

    def test_chunked_bodies_are_read_and_bounded(self):
        with _Served(_service(FakeValidator(), max_body_bytes=4000)) as srv:
            conn = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=10)
            conn.request("POST", "/api/validate", body=iter([b'{"a":', b' 1}']), encode_chunked=True,
                         headers={"Content-Type": "application/json"})
            reply = conn.getresponse()
            self.assertEqual(200, reply.status)
            conn.close()
            conn = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=10)
            conn.request("POST", "/api/validate", body=iter([b"x" * 3000, b"x" * 3000]), encode_chunked=True,
                         headers={"Content-Type": "application/json"})
            self.assertEqual(413, conn.getresponse().status)
            conn.close()

    def test_busy_validator_answers_429_with_retry_after_after_the_queue_timeout(self):
        gate, entered = threading.Event(), threading.Event()
        validator = FakeValidator(gate=gate, entered=entered)
        with _Served(_service(validator, max_concurrent=1, queue_timeout=0.3)) as srv:
            first = {}
            worker = threading.Thread(target=lambda: first.update(r=srv.post_model(_model())))
            worker.start()
            self.assertTrue(entered.wait(10))
            self.assertEqual(1, json.loads(srv.call("GET", "/api/health")[2])["busy"])
            status, headers, body = srv.post_model(_model())
            gate.set()
            worker.join(10)
        self.assertEqual((429, "busy"), (status, body["code"]))
        self.assertEqual(str(vs.RETRY_AFTER_SECONDS), headers["Retry-After"])
        self.assertEqual(200, first["r"][0])
        self.assertEqual(1, len(validator.calls))  # the second never reached the validator

    def test_failures_are_503_or_500_and_never_carry_a_stack_trace_or_a_path(self):
        cases = (
            (FakeValidator(report=None), 503, "validator_unavailable"),
            (FakeValidator(exit_code=1), 503, "validator_unavailable"),
            (FakeValidator(raises=vs.ValidatorUnavailable("validation took too long")), 503, "validator_unavailable"),
            (FakeValidator(raises=RuntimeError("secret detail")), 500, "internal_error"),
        )
        for validator, http_status, code in cases:
            with _Served(_service(validator)) as srv:
                status, _, raw = srv.call("POST", "/api/validate", json.dumps(_model()),
                                          {"Content-Type": "application/json"})
            text = raw.decode("utf-8")
            self.assertEqual((http_status, code), (status, json.loads(text)["code"]), code)
            for forbidden in ("Traceback", "RuntimeError", "secret detail", "npdev-validator-"):
                self.assertNotIn(forbidden, text)

    def test_sanitising_the_scratch_dir_in_model_and_messages(self):
        validator = FakeValidator(diags=[_diag("error", "x")], message_with_dir=True)
        with _Served(_service(validator)) as srv:
            _, _, plain = srv.post_model(_model())
            _, _, full = srv.post_model(_model(), "?full=1")
        for body, call in zip((plain, full), validator.calls):
            self.assertNotIn(call["path"].parent.name, json.dumps(body))
        self.assertIn("<model-dir>", json.dumps(plain))
        self.assertEqual("model.json", full["report"]["model"])
        self.assertNotIn("model", {k for k in plain})  # the v2 `model` key is never passed through

    def test_cors_preflight_and_post(self):
        with _Served(_service()) as srv:
            status, headers, _ = srv.call("OPTIONS", "/api/validate", None, {
                "Origin": "https://some.page", "Access-Control-Request-Method": "POST"})
            self.assertEqual(204, status)
            for name, value in (("Access-Control-Allow-Origin", "*"), ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
                                ("Access-Control-Allow-Headers", "Content-Type"), ("Access-Control-Max-Age", "600"),
                                ("Access-Control-Allow-Private-Network", "true")):
                self.assertEqual(value, headers.get(name), name)
            status, headers, _ = srv.call("POST", "/api/validate", "{}", {"Content-Type": "text/plain"})
            self.assertEqual("*", headers["Access-Control-Allow-Origin"])

    def test_page_schema_and_unknown_routes(self):
        with _Served(_service()) as srv:
            status, headers, raw = srv.call("GET", "/")
            self.assertEqual(200, status)
            self.assertIn("text/html", headers["Content-Type"])
            self.assertIn(b"NPDEV_VALIDATION_RESULT", raw)
            status, _, raw = srv.call("GET", "/api/schema/model")
            self.assertEqual(200, status)
            self.assertIn("$schema", json.loads(raw))
            status, _, raw = srv.call("GET", "/nope")
            self.assertEqual((404, "not_found"), (status, json.loads(raw)["code"]))
            status, headers, _ = srv.call("GET", "/api/validate")
            self.assertEqual((405, "POST, OPTIONS"), (status, headers["Allow"]))

    def test_mcp_routes_do_not_exist_without_the_flag(self):
        with _Served(_service(tokens=vs.TokenStore("tok"))) as srv:
            self.assertEqual(404, srv.call("POST", "/mcp/tok", "{}")[0])

    def test_run_cli_without_env_is_unchanged_and_with_env_passes_it(self):
        class _Done:
            returncode = 0
            stdout = ""
            stderr = ""

        with patch.object(server.subprocess, "run", return_value=_Done()) as run:
            server.run_cli(["x"])
            self.assertNotIn("env", run.call_args.kwargs)
            server.run_cli(["x"], env={"A": "1"})
            self.assertEqual({"A": "1"}, run.call_args.kwargs["env"])

    def test_the_cli_validator_switches_the_journal_off_unless_asked(self):
        seen = {}

        def fake_run_cli(args, timeout=600, env=None):
            seen["env"] = env
            seen["args"] = args
            report = Path(args[args.index("--report") + 1])
            report.write_text(json.dumps(_report([])), encoding="utf-8")
            return {"ok": True, "exitCode": 0, "stdout": "", "stderr": ""}

        with tempfile.TemporaryDirectory() as tmp, patch.object(server, "run_cli", fake_run_cli):
            model = Path(tmp) / "model.json"
            model.write_text("{}", encoding="utf-8")
            code, report, _ = vs.make_cli_validator()(model)
            self.assertEqual((0, "passed"), (code, report["status"]))
            self.assertEqual("0", seen["env"]["NPDEV_AI_CAPTURE"])
            vs.make_cli_validator(capture=True)(model)
            self.assertNotEqual("0", seen["env"].get("NPDEV_AI_CAPTURE"))

    def test_a_cli_timeout_is_validator_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(
                server, "run_cli", lambda *a, **k: {"ok": False, "error": "cli timed out after 60s"}):
            model = Path(tmp) / "model.json"
            model.write_text("{}", encoding="utf-8")
            with self.assertRaises(vs.ValidatorUnavailable):
                vs.make_cli_validator()(model)

    def test_responses_match_the_published_json_schema(self):
        from jsonschema import Draft202012Validator

        validator = Draft202012Validator(json.loads(_RESPONSE_SCHEMA.read_text(encoding="utf-8")))
        diags = [_diag("error", "e", hint="h"), _diag("warning", "w")]
        bodies = []
        with _Served(_service(FakeValidator(diags))) as srv:
            bodies.append(srv.post_model(_model())[2])
            bodies.append(srv.post_model(_model(), "?maxDiagnostics=1&full=1")[2])
            bodies.append(srv.post_model("{ nope")[2])
            bodies.append(srv.post_model("[]")[2])
        with _Served(_service(FakeValidator(report=None))) as srv:
            bodies.append(srv.post_model(_model())[2])
        for body in bodies:
            self.assertEqual([], [e.message for e in validator.iter_errors(body)], body)


def _have_toolchain() -> bool:
    try:
        jar, java = vs.find_toolchain()
        return bool(jar and java)
    except Exception:  # noqa: BLE001 -- no CLI import / no jar: the real test is skipped, nothing else
        return False


@unittest.skipUnless(_have_toolchain(), "needs the staged npdev-ai-tools.jar and Java")
class RealValidatorTest(unittest.TestCase):
    """The one end-to-end test: the real warm jar through the real CLI."""

    def test_valid_model_passes_and_a_broken_reference_fails(self):
        service = vs.ValidationService(validate_fn=vs.make_cli_validator(), validator_name="warm-jar")
        with _Served(service) as srv:
            status, _, body = srv.post_model(_model())
            self.assertEqual(200, status)
            self.assertIn(body["status"], ("passed", "warning"), body)
            broken = copy.deepcopy(_model())
            broken["bogusKey"] = 1
            status, _, body = srv.post_model(broken)
            self.assertEqual((200, "failed"), (status, body["status"]))
            self.assertGreaterEqual(body["summary"]["errors"], 1)
            self.assertNotIn(str(Path(tempfile.gettempdir())).lower(), json.dumps(body).lower())


# ---------------------------------------------------------------------------
# Path B: MCP
# ---------------------------------------------------------------------------

TOKEN = "test-token-123"


def _mcp_service(validator=None, **kwargs) -> vs.ValidationService:
    kwargs.setdefault("mcp", True)
    kwargs.setdefault("tokens", vs.TokenStore(TOKEN))
    return _service(validator, **kwargs)


def _call(msg_id, name, arguments=None):
    return {"jsonrpc": "2.0", "id": msg_id, "method": "tools/call", "params": {"name": name, "arguments": arguments or {}}}


class McpTransportTest(unittest.TestCase):
    def test_initialize_negotiates_the_three_versions_and_falls_back_to_the_newest(self):
        with _Served(_mcp_service()) as srv:
            for asked in vs.MCP_SUPPORTED_VERSIONS:
                _, _, reply = srv.rpc(TOKEN, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                              "params": {"protocolVersion": asked}})
                self.assertEqual(asked, reply["result"]["protocolVersion"])
            _, _, reply = srv.rpc(TOKEN, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                          "params": {"protocolVersion": "1999-01-01"}})
            result = reply["result"]
            self.assertEqual("2025-06-18", result["protocolVersion"])
            self.assertEqual({"tools": {"listChanged": False}}, result["capabilities"])
            self.assertEqual("npdev-validator", result["serverInfo"]["name"])
            self.assertIn("npdev_validate", result["instructions"])
            self.assertNotIn("Mcp-Session-Id", srv.call("POST", f"/mcp/{TOKEN}", json.dumps(
                {"jsonrpc": "2.0", "id": 1, "method": "ping"}), {"Content-Type": "application/json"})[1])

    def test_notification_batch_and_garbage(self):
        with _Served(_mcp_service()) as srv:
            status, _, raw = srv.call("POST", f"/mcp/{TOKEN}", json.dumps(
                {"jsonrpc": "2.0", "method": "notifications/initialized"}), {"Content-Type": "application/json"})
            self.assertEqual((202, b""), (status, raw))
            _, _, reply = srv.rpc(TOKEN, [{"jsonrpc": "2.0", "id": 1, "method": "ping"}])
            self.assertEqual(-32600, reply["error"]["code"])
            _, _, reply = srv.rpc(TOKEN, "{ not json")
            self.assertEqual(-32700, reply["error"]["code"])
            _, _, reply = srv.rpc(TOKEN, {"jsonrpc": "2.0", "id": 9, "method": "resources/list"})
            self.assertEqual(-32601, reply["error"]["code"])

    def test_tools_list_is_exactly_the_allowlist_and_no_schema_asks_for_a_file(self):
        with _Served(_mcp_service()) as srv:
            _, _, reply = srv.rpc(TOKEN, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        tools = reply["result"]["tools"]
        self.assertEqual({"npdev_validate", "npdev_core_context", "npdev_search_examples", "npdev_search_fix",
                          "npdev_check_support", "npdev_get_schema"}, {t["name"] for t in tools})
        # The stdio server's writing/building/file-reading tools must never appear remotely.
        self.assertFalse({t["name"] for t in tools} & {"npdev_generate", "npdev_build_and_run", "npdev_close_loop",
                                                       "npdev_author_submit", "npdev_graph_explain"})
        for tool in tools:
            for prop in tool["inputSchema"].get("properties", {}):
                if tool["name"] == "npdev_search_fix" and prop == "path":
                    continue  # the diagnostic's own JSON location, documented as such -- not a file
                self.assertNotRegex(prop, r"(?i)(path|file|dir|config|output|baseline|previous|repo)", tool["name"])
            self.assertEqual(sorted(tool["inputSchema"].get("properties", {})),
                             sorted(vs.REMOTE_TOOL_INDEX[tool["name"]]["args"]))

    def test_every_remote_tool_exists_in_the_stdio_server_so_names_cannot_drift(self):
        for name in vs.REMOTE_TOOL_INDEX:
            self.assertIn(name, server.TOOL_HANDLERS)

    def test_validate_with_an_object_and_with_a_string_gives_the_same_result(self):
        diags = [_diag("error", "e1"), _diag("warning", "w1")]
        with _Served(_mcp_service(FakeValidator(diags))) as srv:
            _, _, as_object = srv.rpc(TOKEN, _call(1, "npdev_validate", {"model": _model()}))
            _, _, as_string = srv.rpc(TOKEN, _call(2, "npdev_validate", {"model": json.dumps(_model())}))
        first, second = as_object["result"], as_string["result"]
        self.assertFalse(first["isError"])  # `failed` is the model's fault, not the tool's
        parsed = [json.loads(r["content"][0]["text"]) for r in (first, second)]
        for payload in parsed:
            payload.pop("service")
        self.assertEqual(parsed[0], parsed[1])
        self.assertEqual("failed", parsed[0]["status"])

    def test_validate_rejections_are_tool_errors(self):
        with _Served(_mcp_service()) as srv:
            for arguments in ({"model": "{ nope"}, {"model": 5}, {}):
                _, _, reply = srv.rpc(TOKEN, _call(1, "npdev_validate", arguments))
                self.assertTrue(reply["result"]["isError"], arguments)
                self.assertEqual("rejected", json.loads(reply["result"]["content"][0]["text"])["status"])

    def test_default_diagnostic_count_is_the_smaller_mcp_default(self):
        diags = [_diag("error", f"e{i}") for i in range(30)]
        with _Served(_mcp_service(FakeValidator(diags))) as srv:
            _, _, reply = srv.rpc(TOKEN, _call(1, "npdev_validate", {"model": _model()}))
            payload = json.loads(reply["result"]["content"][0]["text"])
            self.assertEqual(vs.MCP_DEFAULT_MAX_DIAGNOSTICS, len(payload["diagnostics"]))
            self.assertEqual(10, payload["truncated"]["omitted"])

    def test_a_tool_outside_the_list_is_32602_and_extra_arguments_are_dropped(self):
        received = []
        handlers = {"npdev_core_context": lambda a: (received.append(dict(a)) or server._text("ok"))}
        with _Served(_mcp_service(remote_handlers=handlers)) as srv:
            for name in ("npdev_generate", "npdev_build_and_run", "does_not_exist"):
                _, _, reply = srv.rpc(TOKEN, _call(1, name, {"model_path": "x"}))
                self.assertEqual(-32602, reply["error"]["code"], name)
            _, _, reply = srv.rpc(TOKEN, _call(2, "npdev_core_context", {"variant": "compact", "refresh": True, "model_path": "x"}))
            self.assertFalse(reply["result"]["isError"])
            _, _, reply = srv.rpc(TOKEN, _call(3, "npdev_validate"))  # listed, but this service has no handler for it
            self.assertEqual(-32602, reply["error"]["code"])
        self.assertEqual([{"variant": "compact"}], received)

    def test_a_crashing_tool_reports_without_trace(self):
        def boom(_args):
            raise RuntimeError("secret detail")

        with _Served(_mcp_service(remote_handlers={"npdev_check_support": boom})) as srv:
            _, _, reply = srv.rpc(TOKEN, _call(1, "npdev_check_support", {"feature": "x"}))
        self.assertTrue(reply["result"]["isError"])
        self.assertNotIn("secret", json.dumps(reply))

    def test_tool_output_is_capped(self):
        with patch.object(vs, "TOOL_TEXT_CAP", 50), _Served(_mcp_service(remote_handlers={
                "npdev_check_support": lambda a: server._text("x" * 500)})) as srv:
            _, _, reply = srv.rpc(TOKEN, _call(1, "npdev_check_support", {"feature": "x"}))
        text = reply["result"]["content"][0]["text"]
        self.assertTrue(text.endswith("[truncated]"))
        self.assertLess(len(text), 100)

    def test_wrong_token_is_404_and_get_is_405(self):
        with _Served(_mcp_service()) as srv:
            self.assertEqual(404, srv.call("POST", "/mcp/wrong", "{}", {"Content-Type": "application/json"})[0])
            self.assertEqual(404, srv.call("POST", "/mcp/", "{}")[0])
            self.assertEqual(405, srv.call("GET", f"/mcp/{TOKEN}")[0])
            self.assertEqual(405, srv.call("DELETE", f"/mcp/{TOKEN}")[0])
            self.assertEqual(404, srv.call("GET", "/mcp/wrong")[0])  # nothing confirms the route exists

    def test_origin_guard_and_no_open_cors_on_the_mcp_route(self):
        with _Served(_mcp_service()) as srv:
            ping = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
            self.assertEqual(403, srv.rpc(TOKEN, ping, {"Origin": "https://evil.example"})[0])
            self.assertEqual(200, srv.rpc(TOKEN, ping)[0])
            status, headers, _ = srv.rpc(TOKEN, ping, {"Origin": "https://claude.ai"})
            self.assertEqual(200, status)
            self.assertNotIn("Access-Control-Allow-Origin", headers)

    def test_rate_limit_is_the_31st_call_in_a_minute_with_an_injected_clock(self):
        now = [5000.0]
        limiter = vs.RateLimiter(clock=lambda: now[0])
        handlers = {"npdev_check_support": lambda a: server._text("ok")}
        with _Served(_mcp_service(remote_handlers=handlers, rate_limiter=limiter)) as srv:
            codes = [srv.rpc(TOKEN, _call(i, "npdev_check_support", {"feature": "x"}))[0] for i in range(30)]
            self.assertEqual({200}, set(codes))
            status, headers, reply = srv.rpc(TOKEN, _call(99, "npdev_check_support", {"feature": "x"}))
            self.assertEqual(429, status)
            self.assertIn("Retry-After", headers)
            self.assertEqual(-32000, reply["error"]["code"])
            self.assertEqual(200, srv.rpc(TOKEN, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})[0])  # only tools/call counts
            now[0] += 61
            self.assertEqual(200, srv.rpc(TOKEN, _call(100, "npdev_check_support", {"feature": "x"}))[0])

    def test_the_token_never_reaches_the_log(self):
        lines = []
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "access.log"
            service = _mcp_service(quiet=False, echo=lines.append, log_path=log)
            with _Served(service) as srv:
                srv.rpc(TOKEN, {"jsonrpc": "2.0", "id": 1, "method": "ping"})
                srv.call("POST", "/mcp/" + "wrong-token-value", "{}")
            on_disk = log.read_text(encoding="utf-8")
        for text in ("\n".join(lines), on_disk):
            self.assertNotIn(TOKEN, text)
            self.assertNotIn("wrong-token-value", text)
            self.assertIn("/mcp/***", text)

    def test_core_context_is_served_prebuilt_and_never_triggers_a_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "npdev-ai" / "core-context"
            out.mkdir(parents=True)
            (out / "bundle-compact.json").write_text(json.dumps({"markdown": "COMPACT"}), encoding="utf-8")
            with patch.dict(os.environ, {"NPDEV_BUILD_ROOT": tmp}), patch.object(
                    server, "run_ai_script", side_effect=AssertionError("must not build per call")):
                ok = vs._remote_core_context({})
                missing = vs._remote_core_context({"variant": "full"})
        self.assertEqual("COMPACT", ok["content"][0]["text"])
        self.assertTrue(missing["isError"])

    def test_search_limits_are_clamped(self):
        captured = []
        with patch.object(server, "tool_search_examples", lambda a: captured.append(a) or server._text("{}")):
            vs.default_remote_handlers(_service())["npdev_search_examples"]({"query": "q", "limit": 9999})
            vs.default_remote_handlers(_service())["npdev_search_examples"]({"query": "q", "limit": "abc"})
        self.assertEqual(10, captured[0]["limit"])
        self.assertNotIn("limit", captured[1])

    def test_get_schema_only_serves_the_model_schema(self):
        handlers = vs.default_remote_handlers(_service())
        self.assertTrue(handlers["npdev_get_schema"]({"schema_name": "ai-config"})["isError"])
        self.assertFalse(handlers["npdev_get_schema"]({})["isError"])


class StartupTest(unittest.TestCase):
    def test_refuses_a_public_bind_without_the_flag(self):
        with patch.object(vs, "find_toolchain", return_value=("jar", "java")):
            self.assertEqual(1, vs.main(["--host", "0.0.0.0", "--port", "0"]))

    def test_refuses_to_start_without_the_jar(self):
        with patch.object(vs, "find_toolchain", return_value=(None, "java")):
            self.assertEqual(1, vs.main(["--port", "0"]))

    def test_doctor_reports_the_problems(self):
        with patch.object(vs, "find_toolchain", return_value=(None, None)):
            report = vs.doctor_report("127.0.0.1", 0)
        self.assertFalse(report["ok"])
        self.assertEqual(2, len([p for p in report["problems"] if "jar" in p or "Java" in p]))

    def test_importing_the_module_prints_nothing(self):
        # A fresh interpreter: stdout is the JSON-RPC channel of the stdio server, so a stray print on
        # import would corrupt it. (The path comes in as an argument, never as a literal.)
        done = subprocess.run(
            [sys.executable, "-c", "import sys; sys.path.insert(0, sys.argv[1]); import validation_service",
             str(_MCP_DIR)], capture_output=True, text=True, timeout=60)
        self.assertEqual((0, ""), (done.returncode, done.stdout))


if __name__ == "__main__":
    unittest.main()
