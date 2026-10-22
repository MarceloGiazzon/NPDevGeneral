"""npdev ai detect / models refresh: found, not typed -- and only written to the user's overlay."""
from __future__ import annotations

import json
import sys
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_ai_config as aicfg  # noqa: E402
import npdev_ai_discovery as discovery  # noqa: E402


def shipped() -> dict:
    return json.loads(aicfg.SHIPPED_PATH.read_text(encoding="utf-8"))


def mine() -> dict:
    return {"schemaVersion": aicfg.SCHEMA_VERSION}


class _Reply:
    def __init__(self, payload):
        self._body = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def read(self):
        return self._body


def routed(routes: dict):
    """An opener answering only the URLs in `routes`; everything else is connection-refused."""
    seen = []

    def opener(request, timeout=None):
        seen.append((request.full_url, timeout))
        if request.full_url in routes:
            return _Reply(routes[request.full_url])
        raise urllib.error.URLError("refused")

    opener.seen = seen
    return opener


class DetectTests(unittest.TestCase):
    def test_finds_ollama_by_its_native_list_and_lm_studio_by_v1_models(self):
        opener = routed({
            "http://127.0.0.1:11434/api/tags": {"models": [{"name": "qwen3:8b"}, {"name": "llama3.2:3b"}]},
            "http://127.0.0.1:1234/v1/models": {"data": [{"id": "qwen3-coder-30b"}]},
        })
        found = discovery.detect(opener=opener)
        self.assertEqual([(s["provider"], s["models"]) for s in found],
                         [("ollama", ["llama3.2:3b", "qwen3:8b"]), ("lmstudio", ["qwen3-coder-30b"])])
        self.assertTrue(all(timeout == 1.0 for _, timeout in opener.seen))
        self.assertTrue(all(url.startswith("http://127.0.0.1:") for url, _ in opener.seen))

    def test_another_host_only_when_named_and_gets_its_own_provider_id(self):
        opener = routed({"http://192.168.1.20:8000/v1/models": {"data": [{"id": "m"}]}})
        found = discovery.detect(["192.168.1.20"], opener=opener)
        self.assertEqual(found[0]["provider"], "vllm-192-168-1-20")
        self.assertEqual(found[0]["baseUrl"], "http://192.168.1.20:8000/v1")

    def test_nothing_running_finds_nothing(self):
        self.assertEqual(discovery.detect(opener=routed({})), [])


class AddDetectedTests(unittest.TestCase):
    def test_add_records_keyless_providers_models_and_retargets_offline(self):
        config = aicfg.merge(shipped(), mine())
        found = [{"server": "ollama", "name": "Ollama", "host": "127.0.0.1", "provider": "ollama",
                  "baseUrl": "http://127.0.0.1:11434/v1", "models": ["qwen3:8b"]},
                 {"server": "lmstudio", "name": "LM Studio", "host": "127.0.0.1", "provider": "lmstudio",
                  "baseUrl": "http://127.0.0.1:1234/v1", "models": ["qwen3-coder-30b"]}]
        document = mine()
        added = discovery.add_detected(document, config, found)
        self.assertEqual(added, ["ollama/qwen3:8b", "lmstudio/qwen3-coder-30b"])
        self.assertEqual([p["id"] for p in document["providers"]], ["lmstudio"])  # ollama is shipped already
        merged = aicfg.merge(shipped(), document)
        self.assertEqual(aicfg.semantic_errors(merged), [])
        self.assertEqual((merged.profile("offline")["provider"], merged.profile("offline")["model"]), ("ollama", "qwen3:8b"))
        self.assertFalse(merged.is_paid("lmstudio", "qwen3-coder-30b"))
        self.assertIsNone(merged.providers["lmstudio"]["keyEnvVar"])

    def test_offline_is_left_alone_when_its_model_is_served(self):
        config = aicfg.merge(shipped(), mine())
        found = [{"server": "ollama", "name": "Ollama", "host": "127.0.0.1", "provider": "ollama",
                  "baseUrl": "http://127.0.0.1:11434/v1", "models": ["qwen2.5-coder:3b", "qwen3:8b"]}]
        document = mine()
        discovery.add_detected(document, config, found)
        self.assertNotIn("profiles", document)

    def test_pick_limits_what_is_added(self):
        config = aicfg.merge(shipped(), mine())
        found = [{"server": "ollama", "name": "Ollama", "host": "127.0.0.1", "provider": "ollama",
                  "baseUrl": "http://127.0.0.1:11434/v1", "models": ["a", "b"]}]
        self.assertEqual(discovery.add_detected(mine(), config, found, {"ollama/b"}), ["ollama/b"])


class RefreshTests(unittest.TestCase):
    def test_new_models_are_recorded_disabled_and_vanished_ones_retired(self):
        config = aicfg.merge(shipped(), mine())
        document = mine()
        report = discovery.refresh(document, config, "groq", ["openai/gpt-oss-120b", "qwen3-32b"])
        self.assertEqual(report["new"], ["openai/gpt-oss-120b", "qwen3-32b"])
        self.assertEqual(report["retired"], ["llama-3.3-70b-versatile"])
        merged = aicfg.merge(shipped(), document)
        self.assertEqual(aicfg.semantic_errors(merged), [])
        self.assertFalse(aicfg.model_active(merged.model("groq", "qwen3-32b")))
        # the retired default model is now visible where it matters: the profile using it
        self.assertIn("retired", merged.profile_problem("fast-alt"))

    def test_a_model_that_comes_back_is_available_again(self):
        document = mine()
        discovery.refresh(document, aicfg.merge(shipped(), mine()), "groq", ["other"])
        report = discovery.refresh(document, aicfg.merge(shipped(), document), "groq", ["other", "llama-3.3-70b-versatile"])
        self.assertEqual(report["back"], ["llama-3.3-70b-versatile"])
        self.assertIsNone(aicfg.merge(shipped(), document).profile_problem("fast-alt"))

    def test_an_empty_list_retires_nothing(self):
        report = discovery.refresh(mine(), aicfg.merge(shipped(), mine()), "groq", [])
        self.assertEqual(report["retired"], [])

    def test_only_filters_new_models(self):
        report = discovery.refresh(mine(), aicfg.merge(shipped(), mine()), "openrouter",
                                   ["deepseek/deepseek-chat-v3-0324:free", "x/a:free", "x/b"], only=":free")
        self.assertEqual(report["new"], ["x/a:free"])


if __name__ == "__main__":
    unittest.main()
