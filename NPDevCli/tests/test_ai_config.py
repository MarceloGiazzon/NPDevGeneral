"""npdev_ai_config + `npdev ai`: one schema for both layers, mine wins, cross-references on the merged view."""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_ai  # noqa: E402
import npdev_ai_config as aicfg  # noqa: E402


def shipped() -> dict:
    return json.loads(aicfg.SHIPPED_PATH.read_text(encoding="utf-8"))


def mine(**sections) -> dict:
    return {"schemaVersion": aicfg.SCHEMA_VERSION, **sections}


class ShippedCatalogueTests(unittest.TestCase):
    def test_shipped_catalogue_validates_against_the_schema_and_itself(self):
        self.assertTrue(aicfg.SCHEMA_PATH.is_file())
        self.assertEqual(aicfg.schema_errors(shipped(), "shipped"), [])
        config = aicfg.load(use_mine=False)
        self.assertEqual(aicfg.semantic_errors(config), [])

    def test_the_review_pack_keeps_no_private_vendor_table(self):
        """AIP-1 1.4: the external review reads vendors from the one catalogue, not a table of its own."""
        source = (Path(__file__).resolve().parents[2] / "scripts" / "external-review" / "build-review-pack.py")
        text = source.read_text(encoding="utf-8")
        self.assertNotIn("VENDOR_PROFILES", text)
        self.assertIn("import npdev_ai_config", text)

    def test_every_binding_resolves_to_a_free_or_local_profile(self):
        # owner decision 2026-10-19: no paid spend by default -- a shipped binding never lands on a paid model
        config = aicfg.load(use_mine=False)
        for feature, names in config.bindings.items():
            for name in names:
                profile = config.profile(name)
                self.assertFalse(config.is_paid(profile["provider"], profile["model"]), (feature, name))

    def test_no_key_shaped_value_anywhere_in_the_catalogue(self):
        self.assertNotRegex(aicfg.SHIPPED_PATH.read_text(encoding="utf-8"), r"(sk-|nvapi-|AIza|ghp_)[A-Za-z0-9]{8,}")

    def test_key_env_var_convention(self):
        self.assertEqual(aicfg.key_env_var("lmstudio-desk"), "NPDEV_EXTERNALAI_LMSTUDIO_DESK_API_KEY")


class LayeringTests(unittest.TestCase):
    def test_mine_overrides_only_the_fields_it_sets_and_says_so(self):
        overlay = mine(providers=[{"id": "gemini", "baseUrl": "https://proxy.example/v1"}])
        config = aicfg.merge(shipped(), overlay)
        self.assertEqual(config.providers["gemini"]["baseUrl"], "https://proxy.example/v1")
        self.assertEqual(config.providers["gemini"]["kind"], "free")
        origin = config.origin[("providers", "gemini")]
        self.assertEqual(origin["source"], "overridden")
        self.assertEqual(list(origin["overrides"]), ["baseUrl"])
        self.assertEqual(origin["overrides"]["baseUrl"]["shipped"],
                         "https://generativelanguage.googleapis.com/v1beta/openai")

    def test_setting_a_field_to_its_shipped_value_is_not_an_override(self):
        config = aicfg.merge(shipped(), mine(providers=[{"id": "groq", "kind": "free"}]))
        self.assertEqual(config.origin[("providers", "groq")]["source"], "shipped")

    def test_monthly_cap_defaults_to_ten_dollars_and_null_turns_it_off(self):
        self.assertEqual(aicfg.merge(shipped()).spend["monthlyCapUsd"], 10.0)
        self.assertEqual(aicfg.merge(shipped(), mine(spend={"monthlyCapUsd": 3})).spend["monthlyCapUsd"], 3)
        self.assertIsNone(aicfg.merge(shipped(), mine(spend={"monthlyCapUsd": None})).spend["monthlyCapUsd"])

    def test_a_new_local_provider_with_its_own_model_and_profile(self):
        overlay = mine(
            providers=[{"id": "lmstudio-desk", "name": "LM Studio on the desk", "kind": "local",
                        "protocol": "openai-chat", "baseUrl": "http://192.168.1.20:1234/v1",
                        "authStyle": "none", "needsKey": False, "keyEnvVar": None}],
            models=[{"provider": "lmstudio-desk", "id": "qwen3-coder-30b", "effortLevels": []}],
            profiles=[{"name": "desk", "provider": "lmstudio-desk", "model": "qwen3-coder-30b"}],
            bindings={"prompter": ["desk", "deep"]})
        config = aicfg.merge(shipped(), overlay)
        self.assertEqual(aicfg.semantic_errors(config), [])
        self.assertEqual(config.origin[("providers", "lmstudio-desk")]["source"], "mine")
        self.assertEqual(config.binding("prompter"), ["desk", "deep"])
        self.assertEqual(config.origin[("bindings", "prompter")]["source"], "overridden")

    def test_disabling_a_provider_makes_its_profiles_unavailable_not_invalid(self):
        config = aicfg.merge(shipped(), mine(providers=[{"id": "gemini", "disabled": True}]))
        self.assertEqual(aicfg.semantic_errors(config), [])
        self.assertIn("disabled", config.profile_problem("fast"))
        self.assertNotIn("gemini", [p["id"] for p in config.active_providers()])

    def test_mine_cannot_declare_protocols(self):
        with self.assertRaises(aicfg.AiConfigError):
            aicfg.merge(shipped(), mine(protocols=[{"id": "x", "description": "y"}]))

    def test_duplicate_entry_in_one_file_is_refused(self):
        with self.assertRaises(aicfg.AiConfigError):
            aicfg.merge(shipped(), mine(profiles=[{"name": "a"}, {"name": "a"}]))


class CrossReferenceTests(unittest.TestCase):
    def errors(self, overlay: dict) -> str:
        return "\n".join(aicfg.semantic_errors(aicfg.merge(shipped(), overlay)))

    def test_profile_naming_an_unknown_model_fails(self):
        self.assertIn("unknown model gemini/nope",
                      self.errors(mine(profiles=[{"name": "x", "provider": "gemini", "model": "nope"}])))

    def test_unimplemented_protocol_fails_with_the_supported_list(self):
        text = self.errors(mine(providers=[{"id": "groq", "protocol": "bedrock"}]))
        self.assertIn("'bedrock' is not implemented", text)
        self.assertIn("anthropic-messages, gemini-native, openai-chat", text)

    def test_key_rules(self):
        self.assertIn("needsKey must be true", self.errors(mine(providers=[{"id": "groq", "authStyle": "none"}])))
        self.assertIn("keyEnvVar must be", self.errors(mine(providers=[{"id": "groq", "keyEnvVar": "NPDEV_EXTERNALAI_X_API_KEY"}])))

    def test_default_model_must_be_listed(self):
        self.assertIn("defaultModel 'ghost'", self.errors(mine(providers=[{"id": "groq", "defaultModel": "ghost"}])))

    def test_fallback_loops_and_unknown_fallbacks(self):
        self.assertIn("loops", self.errors(mine(profiles=[{"name": "fast-alt", "fallback": ["fast"]}])))
        self.assertIn("unknown fallback profile 'ghost'", self.errors(mine(profiles=[{"name": "fast", "fallback": ["ghost"]}])))

    def test_binding_to_an_unknown_profile(self):
        self.assertIn("unknown profile 'ghost'", self.errors(mine(bindings={"bench": ["ghost"]})))

    def test_model_in_path_needs_the_placeholder(self):
        self.assertIn("modelInPath", self.errors(mine(providers=[{"id": "groq", "modelInPath": True}])))


class SchemaTests(unittest.TestCase):
    def test_no_secret_in_headers_or_query_parameters(self):
        for overlay in (mine(providers=[{"id": "x", "extraHeaders": {"authorization": "Bearer k"}}]),
                        mine(providers=[{"id": "x", "extraHeaders": {"X-Api-Key": "k"}}]),
                        mine(providers=[{"id": "x", "queryParams": {"api_key": "k"}}])):
            self.assertTrue(aicfg.schema_errors(overlay, "t"), overlay)
        self.assertEqual(aicfg.schema_errors(mine(providers=[{"id": "azure", "queryParams": {"api-version": "2024-10-21"},
                                                              "extraHeaders": {"anthropic-version": "2023-06-01"}}]), "t"), [])

    def test_unknown_effort_and_feature(self):
        self.assertTrue(aicfg.schema_errors(mine(profiles=[{"name": "p", "effort": "turbo"}]), "t"))
        self.assertTrue(aicfg.schema_errors(mine(bindings={"everything": ["fast"]}), "t"))
        self.assertEqual(aicfg.schema_errors(mine(bindings={"app:helpdesk": ["fast"]}), "t"), [])


class EffortTests(unittest.TestCase):
    MODEL = {"id": "m", "effortLevels": ["low", "high"]}

    def test_each_protocol_maps_the_one_word(self):
        self.assertEqual(aicfg.effort_request("anthropic-messages", self.MODEL, "high"), ({"output_config": {"effort": "high"}}, None))
        self.assertEqual(aicfg.effort_request("openai-chat", self.MODEL, "low"), ({"reasoning_effort": "low"}, None))
        self.assertEqual(aicfg.effort_request("gemini-native", self.MODEL, "high"),
                         ({"generationConfig": {"thinkingConfig": {"thinkingLevel": "high"}}}, None))

    def test_a_level_the_model_does_not_declare_is_not_sent_and_warns(self):
        body, warning = aicfg.effort_request("openai-chat", self.MODEL, "max")
        self.assertEqual(body, {})
        self.assertIn("does not declare effort 'max'", warning)
        body, warning = aicfg.effort_request("openai-chat", {"id": "plain", "effortLevels": []}, "low")
        self.assertEqual(body, {})
        self.assertIn("no effort parameter", warning)

    def test_no_effort_asked_sends_nothing(self):
        self.assertEqual(aicfg.effort_request("openai-chat", self.MODEL, None), ({}, None))

    def test_unknown_level_is_an_error(self):
        with self.assertRaises(aicfg.AiConfigError):
            aicfg.effort_request("openai-chat", self.MODEL, "turbo")


class OverlayFileTests(unittest.TestCase):
    def setUp(self):
        self.path = Path(tempfile.mkdtemp()) / "ai.json"

    def test_write_refuses_an_inconsistent_overlay_and_leaves_the_file(self):
        aicfg.write_mine(mine(profiles=[{"name": "fast", "effort": "low"}]), self.path)
        before = self.path.read_text(encoding="utf-8")
        with self.assertRaises(aicfg.AiConfigError):
            aicfg.write_mine(mine(profiles=[{"name": "x", "provider": "gemini", "model": "nope"}]), self.path)
        self.assertEqual(self.path.read_text(encoding="utf-8"), before)

    def test_reset_one_field_then_the_whole_entry(self):
        document = mine(providers=[{"id": "gemini", "baseUrl": "https://p.example/v1", "notes": "mine"}])
        self.assertTrue(aicfg.reset_mine(document, "providers", "gemini", ["baseUrl"]))
        self.assertEqual(document["providers"], [{"id": "gemini", "notes": "mine"}])
        self.assertTrue(aicfg.reset_mine(document, "providers", "gemini"))
        self.assertEqual(document["providers"], [])
        self.assertFalse(aicfg.reset_mine(document, "providers", "gemini"))

    def test_mine_path_follows_the_environment(self):
        self.assertEqual(aicfg.mine_path({"NPDEV_AI_CONFIG": "/x/ai.json"}), Path("/x/ai.json"))
        self.assertEqual(aicfg.mine_path({"XDG_CONFIG_HOME": "/cfg"}), Path("/cfg/NPDev/ai.json"))


class CliTests(unittest.TestCase):
    """`npdev ai ...` against a throwaway overlay: what the Manager will drive."""

    def setUp(self):
        self.path = Path(tempfile.mkdtemp()) / "ai.json"
        patcher = mock.patch.dict("os.environ", {"NPDEV_AI_CONFIG": str(self.path)})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.parser = argparse.ArgumentParser()
        sub = self.parser.add_subparsers(dest="command")
        npdev_ai.add_arguments(sub.add_parser("ai").add_subparsers(dest="ai_command"))

    def ai(self, *argv) -> tuple[int, dict]:
        args = self.parser.parse_args(["ai", *argv, "--json"])
        out = io.StringIO()
        with mock.patch.object(npdev_ai, "_key_sources", return_value={}), contextlib.redirect_stdout(out):
            code = npdev_ai.run(args)
        return code, json.loads(out.getvalue())

    def test_one_provider_two_profiles_with_different_model_and_effort(self):
        self.assertEqual(self.ai("models", "add", "anthropic", "claude-haiku-4-5", "--effort-levels", "",
                                 "--price-in", "1", "--price-out", "5")[0], 0)
        self.assertEqual(self.ai("profiles", "add", "cheap", "--provider", "anthropic", "--model", "claude-haiku-4-5")[0], 0)
        self.assertEqual(self.ai("profiles", "add", "thorough", "--provider", "anthropic", "--model", "claude-opus-5-5",
                                 "--effort", "max", "--fallback", "cheap")[0], 0)
        code, answer = self.ai("bind", "review", "thorough")
        self.assertEqual(code, 0, answer)
        _, listing = self.ai("profiles")
        by_name = {p["name"]: p for p in listing["profiles"]}
        self.assertEqual((by_name["cheap"]["model"], by_name["thorough"]["effort"]), ("claude-haiku-4-5", "max"))
        self.assertEqual(by_name["thorough"]["source"], "mine")
        _, bindings = self.ai("bindings")
        self.assertEqual({b["feature"]: b["profiles"] for b in bindings["bindings"]}["review"], ["thorough"])

    def test_providers_listing_shows_the_overridden_field(self):
        self.ai("providers", "set", "gemini", "--base-url", "https://proxy.example/v1")
        _, listing = self.ai("providers")
        gemini = next(p for p in listing["providers"] if p["id"] == "gemini")
        self.assertEqual(gemini["source"], "overridden")
        self.assertEqual(gemini["overrides"]["baseUrl"]["mine"], "https://proxy.example/v1")
        self.assertEqual(self.ai("reset", "provider", "gemini")[0], 0)
        _, listing = self.ai("providers")
        self.assertEqual(next(p for p in listing["providers"] if p["id"] == "gemini")["source"], "shipped")

    def test_add_a_keyed_custom_provider_gets_the_convention_env_var(self):
        code, answer = self.ai("providers", "add", "azure-gpt", "--name", "Azure", "--kind", "paid",
                               "--protocol", "openai-chat", "--auth-style", "api-key",
                               "--base-url", "https://x.openai.azure.com/openai/deployments/{model}",
                               "--model-in-path", "--query", "api-version=2024-10-21")
        self.assertEqual(code, 0, answer)
        _, listing = self.ai("providers")
        azure = next(p for p in listing["providers"] if p["id"] == "azure-gpt")
        self.assertEqual(azure["keyEnvVar"], "NPDEV_EXTERNALAI_AZURE_GPT_API_KEY")

    def test_refusals_answer_json_and_leave_no_file(self):
        code, answer = self.ai("profiles", "add", "x", "--provider", "gemini", "--model", "nope")
        self.assertEqual((code, answer["ok"]), (2, False))
        self.assertFalse(self.path.exists())
        code, answer = self.ai("profiles", "remove", "fast")
        self.assertIn("shipped profile", answer["error"])
        code, answer = self.ai("bind", "everything", "fast")
        self.assertIn("unknown feature", answer["error"])


if __name__ == "__main__":
    unittest.main()
