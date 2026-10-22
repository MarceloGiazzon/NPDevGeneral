"""npdev_ai_client: one client, three protocols, failover, retry, spend guard, usage log -- and no key leaks."""
from __future__ import annotations

import io
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_ai_client as client  # noqa: E402
import npdev_ai_config as aicfg  # noqa: E402
import npdev_providers  # noqa: E402

SECRET = "test-key-0123456789abcdef"


def shipped() -> dict:
    return json.loads(aicfg.SHIPPED_PATH.read_text(encoding="utf-8"))


def config(**overlay):
    return aicfg.merge(shipped(), {"schemaVersion": aicfg.SCHEMA_VERSION, **overlay})


class _Reply:
    def __init__(self, payload):
        self._body = json.dumps(payload).encode("utf-8")
        self.status = 200

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def read(self):
        return self._body


class FakeOpener:
    """Records every request; answers from a queue of payloads or exceptions."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.requests = []

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        answer = self.answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return _Reply(answer)

    def body(self, index=-1) -> dict:
        return json.loads(self.requests[index].data.decode("utf-8"))

    def header(self, name, index=-1):
        return {k.lower(): v for k, v in self.requests[index].header_items()}.get(name.lower())


def http_error(code: int, body: str = "") -> urllib.error.HTTPError:
    return urllib.error.HTTPError("https://x.example", code, "err", {}, io.BytesIO(body.encode()))


OPENAI_OK = {"choices": [{"message": {"content": "OK"}}], "usage": {"prompt_tokens": 10, "completion_tokens": 2}}
ANTHROPIC_OK = {"content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": "OK"}],
                "stop_reason": "end_turn", "usage": {"input_tokens": 1000, "output_tokens": 500}}
GEMINI_OK = {"candidates": [{"content": {"parts": [{"text": "hidden", "thought": True}, {"text": "OK"}]}}],
             "usageMetadata": {"promptTokenCount": 7, "candidatesTokenCount": 1}}


class Base(unittest.TestCase):
    def setUp(self):
        self.usage = client.UsageLog(Path(tempfile.mkdtemp()) / "usage.jsonl")

    def run_complete(self, cfg, opener, keys=None, **kwargs):
        keys = {"gemini": SECRET, "groq": SECRET, "nvidia": SECRET, "anthropic": SECRET} if keys is None else keys
        return client.complete(cfg, "hello", key_resolver=lambda pid, account=None: keys.get(pid), usage=self.usage,
                               opener=opener, sleep=lambda s: None, **kwargs)

    def assert_no_secret(self, *things):
        for thing in things:
            self.assertNotIn(SECRET, json.dumps(thing, default=str))


class ProtocolTests(Base):
    def test_openai_chat_request_and_reply(self):
        opener = FakeOpener(OPENAI_OK)
        result = self.run_complete(config(), opener, provider="groq", system="be brief")
        self.assertEqual((result["ok"], result["text"], result["inputTokens"]), (True, "OK", 10))
        request = opener.requests[0]
        self.assertEqual(request.full_url, "https://api.groq.com/openai/v1/chat/completions")
        self.assertEqual(opener.header("Authorization"), "Bearer " + SECRET)
        body = opener.body()
        self.assertEqual(body["messages"][0], {"role": "system", "content": "be brief"})
        self.assertEqual(body["model"], "llama-3.3-70b-versatile")
        self.assert_no_secret(result, self.usage.records())

    def test_anthropic_messages_request_reply_effort_and_no_temperature(self):
        cfg = config(profiles=[{"name": "claude", "temperature": 0.3}], spend={"allowPaid": {"review": True}},
                     bindings={"review": ["claude"]})
        opener = FakeOpener(ANTHROPIC_OK)
        result = self.run_complete(cfg, opener, feature="review")
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["text"], "OK")
        self.assertEqual(opener.requests[0].full_url, "https://api.anthropic.com/v1/messages")
        self.assertEqual(opener.header("x-api-key"), SECRET)
        self.assertEqual(opener.header("anthropic-version"), "2023-06-01")
        self.assertIsNone(opener.header("Authorization"))
        body = opener.body()
        self.assertEqual(body["output_config"], {"effort": "high"})
        self.assertNotIn("temperature", body)
        self.assertTrue(any("temperature" in w for w in result["warnings"]))
        self.assertEqual(result["estUsd"], round(1000 / 1e6 * 4.0 + 500 / 1e6 * 20.0, 6))

    def test_anthropic_refusal_is_an_error_not_an_empty_answer(self):
        cfg = config(spend={"allowPaid": {"review": True}}, bindings={"review": ["claude"]})
        refusal = {"content": [], "stop_reason": "refusal", "stop_details": {"category": "cyber"}}
        result = self.run_complete(cfg, FakeOpener(refusal), feature="review")
        self.assertFalse(result["ok"])
        self.assertIn("refusal, cyber", result["error"])

    def test_gemini_native_request_and_reply_skip_thoughts(self):
        cfg = config(providers=[{"id": "gemini", "protocol": "gemini-native", "authStyle": "x-goog-api-key",
                                 "baseUrl": "https://generativelanguage.googleapis.com/v1beta"}],
                     models=[{"provider": "gemini", "id": "gemini-3.6-flash", "effortLevels": ["low", "high"]}],
                     profiles=[{"name": "fast", "effort": "low"}])
        opener = FakeOpener(GEMINI_OK)
        result = self.run_complete(cfg, opener, profile="fast")
        self.assertEqual(result["text"], "OK")
        self.assertEqual(opener.requests[0].full_url,
                         "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent")
        self.assertEqual(opener.header("x-goog-api-key"), SECRET)
        self.assertEqual(opener.body()["generationConfig"]["thinkingConfig"], {"thinkingLevel": "low"})

    def test_effort_the_model_does_not_declare_is_not_sent_and_warns(self):
        opener = FakeOpener(OPENAI_OK)
        result = self.run_complete(config(), opener, provider="groq", effort="high")
        self.assertNotIn("reasoning_effort", opener.body())
        self.assertTrue(any("does not declare effort" in w for w in result["warnings"]))

    def test_json_schema_becomes_an_instruction_when_the_model_has_no_structured_output(self):
        opener = FakeOpener(OPENAI_OK)
        self.run_complete(config(), opener, provider="groq", json_schema={"type": "object"})
        body = opener.body()
        self.assertNotIn("response_format", body)
        self.assertIn("JSON Schema", body["messages"][0]["content"])


class FailoverTests(Base):
    def test_retry_on_429_then_answer(self):
        opener = FakeOpener(http_error(429), OPENAI_OK)
        result = self.run_complete(config(), opener, provider="groq")
        self.assertTrue(result["ok"])
        self.assertEqual(len(opener.requests), 2)

    def test_profile_falls_back_and_names_what_answered(self):
        opener = FakeOpener(http_error(401, "bad key"), OPENAI_OK)
        result = self.run_complete(config(), opener, profile="fast", backoff=())
        self.assertTrue(result["ok"], result)
        self.assertEqual((result["profile"], result["provider"]), ("fast-alt", "groq"))
        self.assertIn("HTTP 401", result["attempts"][0]["error"])
        self.assertEqual([r["ok"] for r in self.usage.records()], [False, True])

    def test_missing_key_sends_nothing_and_falls_through(self):
        opener = FakeOpener(OPENAI_OK)
        result = self.run_complete(config(), opener, keys={"groq": SECRET}, profile="fast")
        self.assertEqual(result["provider"], "groq")
        self.assertEqual(len(opener.requests), 1)
        self.assertIn("no key for gemini", result["attempts"][0]["error"])

    def test_every_target_failing_reports_each_one(self):
        result = self.run_complete(config(), FakeOpener(http_error(500), http_error(500)), profile="fast", backoff=())
        self.assertFalse(result["ok"])
        self.assertIn("fast:", result["error"])
        self.assertIn("fast-alt:", result["error"])

    def test_a_disabled_provider_is_skipped_with_a_warning(self):
        cfg = config(providers=[{"id": "gemini", "disabled": True}])
        result = self.run_complete(cfg, FakeOpener(OPENAI_OK), profile="fast")
        self.assertEqual(result["provider"], "groq")
        self.assertTrue(any("disabled" in w for w in result["warnings"]))

    def test_errors_never_carry_the_key(self):
        result = self.run_complete(config(), FakeOpener(http_error(403, "denied")), provider="groq", backoff=())
        self.assert_no_secret(result, self.usage.records())


class SpendGuardTests(Base):
    def test_paid_is_refused_without_an_opt_in_and_nothing_is_sent(self):
        opener = FakeOpener()
        result = self.run_complete(config(), opener, profile="claude")
        self.assertFalse(result["ok"])
        self.assertIn("PAID", result["error"])
        self.assertEqual(opener.requests, [])

    def test_direct_call_with_allow_paid_and_feature_opt_in(self):
        self.assertTrue(self.run_complete(config(), FakeOpener(ANTHROPIC_OK), provider="anthropic", allow_paid=True)["ok"])
        cfg = config(spend={"allowPaid": {"prompter": True}}, bindings={"prompter": ["claude"]})
        self.assertTrue(self.run_complete(cfg, FakeOpener(ANTHROPIC_OK), feature="prompter")["ok"])

    def test_non_free_model_on_a_free_suffix_provider_is_paid(self):
        cfg = config(models=[{"provider": "openrouter", "id": "openai/gpt-x"}])
        result = self.run_complete(cfg, FakeOpener(), keys={"openrouter": SECRET}, provider="openrouter", model="openai/gpt-x")
        self.assertIn("PAID", result["error"])

    def test_cap_refuses_an_unknown_price_and_a_spent_month(self):
        cfg = config(spend={"allowPaid": {"prompter": True}, "monthlyCapUsd": 0.01},
                     models=[{"provider": "anthropic", "id": "claude-unpriced"}],
                     profiles=[{"name": "u", "provider": "anthropic", "model": "claude-unpriced"}],
                     bindings={"prompter": ["u"]})
        self.assertIn("no known price", self.run_complete(cfg, FakeOpener(), feature="prompter")["error"])
        cfg = config(spend={"allowPaid": {"prompter": True}, "monthlyCapUsd": 0.01}, bindings={"prompter": ["claude"]})
        self.assertTrue(self.run_complete(cfg, FakeOpener(ANTHROPIC_OK), feature="prompter")["ok"])  # $0.014
        self.assertIn("reached the cap", self.run_complete(cfg, FakeOpener(), feature="prompter")["error"])

    def test_usage_summary_sums_cost_and_holds_no_prompt(self):
        cfg = config(spend={"allowPaid": {"prompter": True}}, bindings={"prompter": ["claude"]})
        self.run_complete(cfg, FakeOpener(ANTHROPIC_OK), feature="prompter")
        summary = client.summarize(self.usage.records(client.month_start()))
        self.assertAlmostEqual(summary["estUsd"], 0.014)
        self.assertNotIn("hello", self.usage.path.read_text(encoding="utf-8"))


class _CompatServer(BaseHTTPRequestHandler):
    seen: list = []

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        _CompatServer.seen.append({"path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()},
                                   "body": json.loads(self.rfile.read(length) or b"{}")})
        payload = json.dumps(OPENAI_OK).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *a):
        pass


class WireFromConfigTests(Base):
    """1.3e: a new provider needs no code -- an Azure-style deployment and a keyless vLLM, from ai.json alone,
    against a real local HTTP server."""

    def setUp(self):
        super().setUp()
        _CompatServer.seen = []
        self.server = HTTPServer(("127.0.0.1", 0), _CompatServer)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def overlay(self):
        return config(
            providers=[
                {"id": "azure-gpt", "name": "Azure deployment", "kind": "free", "protocol": "openai-chat",
                 "baseUrl": self.base + "/openai/deployments/{model}", "modelInPath": True, "authStyle": "api-key",
                 "needsKey": True, "keyEnvVar": "NPDEV_EXTERNALAI_AZURE_GPT_API_KEY",
                 "queryParams": {"api-version": "2024-10-21"}},
                {"id": "vllm", "name": "vLLM", "kind": "local", "protocol": "openai-chat", "baseUrl": self.base + "/v1",
                 "authStyle": "none", "needsKey": False, "keyEnvVar": None}],
            models=[{"provider": "azure-gpt", "id": "my-deploy"}, {"provider": "vllm", "id": "qwen3"}])

    def test_azure_deployment_and_keyless_vllm(self):
        cfg = self.overlay()
        self.assertEqual(aicfg.semantic_errors(cfg), [])
        azure = client.complete(cfg, "hi", provider="azure-gpt", model="my-deploy", usage=self.usage,
                                key_resolver=lambda pid, account=None: SECRET)
        vllm = client.complete(cfg, "hi", provider="vllm", model="qwen3", usage=self.usage,
                               key_resolver=lambda pid, account=None: None)
        self.assertTrue(azure["ok"] and vllm["ok"], (azure, vllm))
        first, second = _CompatServer.seen
        self.assertEqual(first["path"], "/openai/deployments/my-deploy/chat/completions?api-version=2024-10-21")
        self.assertEqual(first["headers"].get("api-key"), SECRET)
        self.assertNotIn("authorization", first["headers"])
        self.assertEqual(second["path"], "/v1/chat/completions")
        self.assertFalse({"authorization", "api-key"} & set(second["headers"]))
        self.assertNotIn(SECRET, first["path"])


class AccountTests(unittest.TestCase):
    """D5: several keys per provider -- <provider>:<account> in the store, its own env var."""

    def test_account_env_var_and_vault_id(self):
        catalogue = [{"id": "gemini", "keyEnvVar": "NPDEV_EXTERNALAI_GEMINI_API_KEY"}]
        env = {"NPDEV_EXTERNALAI_GEMINI_API_KEY": "main", "NPDEV_EXTERNALAI_GEMINI_WORK_API_KEY": "work"}
        self.assertEqual(npdev_providers.resolve_key("gemini", environ=env, catalogue=catalogue), "main")
        self.assertEqual(npdev_providers.resolve_key("gemini", environ=env, catalogue=catalogue, account="work"), "work")
        self.assertEqual(npdev_providers.account_key_id("gemini", "work"), "gemini:work")
        self.assertEqual(npdev_providers.account_key_id("gemini", None), "gemini")


if __name__ == "__main__":
    unittest.main()
