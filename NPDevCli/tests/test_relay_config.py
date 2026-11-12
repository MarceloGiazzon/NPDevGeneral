"""Configuration proposals (REG-323 T4.5): what a browser AI suggests about settings is shown, never written.

Part 1 is the pure sorter (npdev_relay_config.evaluate). Part 2 pins the allowlist's claims against the schemas: every
knob it says lives in model.json really does, and the properties that must never come from a chat are refused. Part 3
drives whole answers through ingest -> apply with the Java validator and the authoring gate stubbed.

Run with: python -m unittest NPDevCli.tests.test_relay_config -v
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_config as config  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import SEED_MODEL, manifest  # noqa: E402
from test_relay_elements import ElementsAnswerCase  # noqa: E402
from test_relay_files import answer_of, snapshot, with_phone  # noqa: E402

ALLOWLIST = config.load_allowlist()
MODEL_SCHEMA = json.loads((REPO / "NPDevContract" / "schemas" / "model.schema.json").read_text(encoding="utf-8"))
CONFIG_SCHEMA = json.loads((REPO / "NPDevContract" / "schemas" / "config.schema.json").read_text(encoding="utf-8"))


def proposal(*changes) -> dict:
    return {"changes": [{"property": p, "value": v} for p, v in changes]}


class SortTests(unittest.TestCase):
    def test_a_property_nobody_allowlisted_is_a_suggestion_for_settings_and_nothing_else(self):
        out = config.evaluate(proposal(("console.mode", "quiet")))
        (item,) = out["items"]
        self.assertEqual((item["status"], item["reason"]), ("suggestion", "not-a-chat-setting"))
        self.assertIn("You can do that in Settings", item["preview"])
        self.assertIn('"quiet"', item["preview"])
        self.assertEqual(out["errors"], [])

    def test_what_must_never_come_from_a_chat_is_refused_by_name_and_the_value_is_never_echoed(self):
        secret = "hunter2-VERY-SECRET"
        for prop in ("database.password", "Database.Host", "config.database.port", "scenario.name", "runtime.serverPort",
                     "trialDefaults.apiKey", "externalAi.egress", "artifact.root", "someService.authToken", "db/password"):
            (item,) = config.evaluate(proposal((prop, secret)))["items"]
            self.assertEqual((item["status"], item["reason"]), ("refused", "never-from-a-chat"), prop)
            self.assertIsNone(item["valueText"], prop)
            self.assertNotIn(secret, json.dumps(item), prop)
            self.assertIn("never changes that from a chat", item["preview"])

    def test_the_same_property_written_three_ways_is_one_property(self):
        for written in ("console.mode", "config.console.mode", "Console/Mode", "config.json:console.mode", "console[mode]"):
            self.assertEqual(config.normalize_property(written).casefold(), "console.mode", written)

    def test_every_change_is_sorted_in_the_order_sent_and_a_bad_one_is_dropped_with_its_position(self):
        out = config.evaluate({"changes": [{"property": "console.mode", "value": 1}, {"property": "x"}, "oops",
                                           {"property": "database.host", "value": "h"}]})
        self.assertEqual([i["property"] for i in out["items"]], ["console.mode", "database.host"])
        self.assertEqual(len(out["errors"]), 2)
        self.assertIn("changes[1]", out["errors"][0])
        self.assertIn("changes[2]", out["errors"][1])

    def test_malformed_proposals_never_raise(self):
        for payload in (None, [], "x", {}, {"changes": []}, {"changes": "nope"}, {"changes": 5}):
            out = config.evaluate(payload)
            self.assertEqual(out["items"], [], payload)
            self.assertTrue(out["errors"], payload)

    def test_one_change_as_an_object_stands_for_a_list_of_one(self):
        out = config.evaluate({"changes": {"property": "console.mode", "value": "x"}})
        self.assertEqual(len(out["items"]), 1)

    def test_why_and_the_shown_value_are_cut_short(self):
        (item,) = config.evaluate({"changes": [{"property": "console.mode", "value": "v" * 500, "why": "w" * 900}]})["items"]
        self.assertEqual(len(item["why"]), config.WHY_MAX)
        self.assertLessEqual(len(item["valueText"]), config.VALUE_TEXT_MAX)

    def test_an_allowlisted_knob_is_applicable_only_with_a_valid_value_and_a_registered_writer(self):
        allowlist = {"entries": [{"property": "console.mode", "type": "enum", "values": ["quiet", "verbose"],
                                  "preview": "Console: {value}", "writer": "stand-in"}], "neverAllowed": ["database.*"]}
        with mock.patch.dict(config.WRITERS, {"stand-in": lambda *_: None}):
            (ok,) = config.evaluate(proposal(("console.mode", "quiet")), allowlist)["items"]
            (bad,) = config.evaluate(proposal(("console.mode", "loud")), allowlist)["items"]
        (no_writer,) = config.evaluate(proposal(("console.mode", "quiet")), allowlist)["items"]
        self.assertEqual((ok["status"], ok["preview"]), ("applicable", 'Console: "quiet"'))
        self.assertEqual((bad["status"], bad["reason"]), ("suggestion", "value-not-allowed"))
        self.assertEqual((no_writer["status"], no_writer["reason"]), ("suggestion", "no-writer"))

    def test_the_value_types_an_entry_can_declare(self):
        entry = lambda **kw: {"property": "p", "writer": "w", **kw}  # noqa: E731
        cases = [(entry(type="boolean"), True, True), (entry(type="boolean"), "true", False),
                 (entry(type="integer", values={"min": 1, "max": 5}), 3, True), (entry(type="integer", values={"min": 1, "max": 5}), 9, False),
                 (entry(type="integer"), True, False), (entry(type="string"), "x", True), (entry(type="string"), 1, False),
                 (entry(type="mystery"), "x", False)]
        for definition, value, expected in cases:
            self.assertEqual(config._value_ok(definition, value), expected, (definition, value))


def _leaves(node, prefix=""):
    for key, value in (node.get("properties") or {}).items():
        path = f"{prefix}{key}"
        if value.get("properties"):
            yield from _leaves(value, path + ".")
        else:
            yield path


class AllowlistMatchesTheSchemasTests(unittest.TestCase):
    def test_a_knob_has_an_entry_only_with_a_writer_and_only_the_themes_have_one_so_far(self):
        # T4.5b built the apply step, the owner's tick and undo with the first knobs (test_relay_config_apply)
        self.assertEqual([e["property"] for e in ALLOWLIST["entries"]], ["ui.theme.name", "ui.theme.choices"])
        for entry in ALLOWLIST["entries"]:
            self.assertIn(entry["writer"], config.WRITERS, entry["property"])
        self.assertEqual(sorted(config.WRITERS), ["config-default"])

    def test_the_themes_the_allowlist_offers_really_are_config_defaults_the_generator_reads(self):
        java = (REPO / "NPDevContract" / "dsl" / "src" / "main" / "java" / "com" / "npdev" / "dsl" / "v1" / "settings"
                / "NpdevSettings.java").read_text(encoding="utf-8")
        for entry in ALLOWLIST["entries"]:
            self.assertIn(f'"{entry["property"]}"', java, f'{entry["property"]} is no longer a setting id of NpdevSettings')
        self.assertIn("string", CONFIG_SCHEMA["properties"]["defaults"]["additionalProperties"]["type"])

    def test_the_sensitive_properties_of_config_json_are_all_refused(self):
        leaves = set(_leaves(CONFIG_SCHEMA))
        sensitive = {p for p in leaves if p.startswith(("database.", "artifact.", "finalExec."))} \
            | {"scenario.name", "runtime.serverPort", "runtime.javaArgs", "trialDefaults.apiKey"}
        self.assertIn("database.password", sensitive)
        self.assertIn("trialDefaults.apiKey", leaves)
        for prop in sorted(sensitive):
            self.assertTrue(config._never(prop, ALLOWLIST), f"{prop} must never be changeable from a chat")

    def test_every_knob_it_says_lives_in_model_json_really_does(self):
        defs = MODEL_SCHEMA["$defs"]
        claims = {
            "settings.locale": defs["settings"]["properties"].get("locale"),
            "settings.strings": defs["settings"]["properties"].get("strings"),
            "settings.ui.dateFormat": defs["settings"]["properties"]["ui"]["properties"].get("dateFormat"),
            "settings.ui.pageRows": defs["settings"]["properties"]["ui"]["properties"].get("pageRows"),
            "presentationMetadata.currency": defs["presentationMetadata"]["properties"].get("currency"),
            "guidePage.theme": defs["guidePage"]["properties"].get("theme"),
            "properties[]": MODEL_SCHEMA["properties"].get("properties"),
        }
        for claim, found in claims.items():
            self.assertIsNotNone(found, f"model.schema.json no longer has {claim}: fix the allowlist's `located` list")
        text = json.dumps(ALLOWLIST["located"])
        for claim in ("settings.locale", "settings.ui.dateFormat", "settings.ui.pageRows", "presentationMetadata.currency",
                      "guidePage.theme", "properties[]"):
            self.assertIn(claim, text)

    def test_every_candidate_idea_says_where_it_lives(self):
        self.assertGreaterEqual(len(ALLOWLIST["located"]), 7)
        for item in ALLOWLIST["located"]:
            self.assertTrue(item["idea"] and item["where"] and item["how"], item)

    def test_the_proposal_schema_in_schemas_ai_is_the_one_the_sorter_reads(self):
        schema = json.loads((REPO / "schemas" / "ai" / "ai-relay-config-proposal.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(set(schema["properties"]["changes"]["items"]["properties"]), {"property", "value", "why"})
        result = json.loads((REPO / "schemas" / "ai" / "ai-relay-ingest-result.schema.json").read_text(encoding="utf-8"))
        item = result["properties"]["configProposals"]["items"]
        applicable = config.evaluate({"changes": [{"property": "ui.theme.name", "value": "retro"}]},
                                     context={"themes": {"retro"}})["items"]
        self.assertEqual(applicable[0]["status"], "applicable")
        for sample in config.evaluate(proposal(("console.mode", 1), ("database.host", "h")))["items"] + applicable:
            self.assertTrue(set(item["required"]) <= set(sample))
            self.assertTrue(set(sample) <= set(item["properties"]), set(sample) - set(item["properties"]))
            self.assertIn(sample["status"], item["properties"]["status"]["enum"])
            self.assertIn(sample["reason"], item["properties"]["reason"]["enum"])


class ConfigAnswerTests(ElementsAnswerCase):
    def ingest(self, app: Path, named: dict, phase: str = "f4") -> tuple:
        code = self.start(app, phase=phase)
        return code, engine.ingest(code, answer_of(code, 1, named), source="paste")

    def test_a_model_with_a_config_proposal_is_ready_and_the_proposal_is_shown_never_written(self):
        app = self.make_app()
        before = snapshot(app)
        code, result = self.ingest(app, {
            "model.json": with_phone(json.loads(SEED_MODEL)), "npdev-submission.json": manifest(),
            "npdev-config-proposal.json": proposal(("console.mode", "quiet"), ("database.password", "hunter2"))})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertEqual([(i["property"], i["status"]) for i in result["configProposals"]],
                         [("console.mode", "suggestion"), ("database.password", "refused")])
        self.assertNotIn("hunter2", json.dumps(result["configProposals"]))
        self.assertEqual([f["code"] for f in result["findings"] if f["code"] == "config-not-applied"], ["config-not-applied"] * 2)
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        after = snapshot(app)
        self.assertEqual(sorted(set(after) - set(before)), [])  # no config.json, no proposal file: nothing new in the app
        self.assertNotEqual(after["model.json"], before["model.json"])

    def test_an_unreadable_proposal_never_blocks_the_model_it_came_with(self):
        _, result = self.ingest(self.make_app(), {
            "model.json": with_phone(json.loads(SEED_MODEL)), "npdev-submission.json": manifest(),
            "npdev-config-proposal.json": {"changes": "make it blue"}})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("config-proposal-ignored", [f["code"] for f in result["findings"]])
        self.assertNotIn("configProposals", result)

    def test_a_proposal_beside_an_element_upsert_is_sorted_too(self):
        _, result = self.ingest(self.make_app(), {
            "npdev-elements.json": {"upsert": {"concepts": [with_phone(json.loads(SEED_MODEL))["concepts"][0]]}},
            "npdev-submission.json": manifest(), "npdev-config-proposal.json": proposal(("console.mode", "quiet"))})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertEqual(result["configProposals"][0]["status"], "suggestion")

    def test_the_default_phase_never_reads_a_proposal_file(self):
        _, result = self.ingest(self.make_app(), {
            "model.json": with_phone(json.loads(SEED_MODEL)), "npdev-submission.json": manifest(),
            "npdev-config-proposal.json": proposal(("console.mode", "quiet"))}, phase="mvp")
        self.assertNotIn("configProposals", result)
        self.assertIn("path-denied", [f["code"] for f in result["findings"]])

    def ready(self, language: str | None = None) -> dict:
        app = self.make_app()
        kwargs = {"language": language} if language else {}
        code = engine.start(intent="change", ask="Add a phone number to the customer", app_dir=str(app), phase="f4", **kwargs)["code"]
        result = engine.ingest(code, answer_of(code, 1, {
            "model.json": with_phone(json.loads(SEED_MODEL)), "npdev-submission.json": manifest(),
            "npdev-config-proposal.json": proposal(("ui.theme.name", "retro"), ("console.mode", "quiet"), ("database.password", "x"),
                                                    ("ui.theme.choices", "nope"))}), source="paste")
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        return result

    def test_every_proposal_of_a_ready_answer_carries_the_sentence_the_manager_shows_in_english_by_default(self):
        items = self.ready()["configProposals"]
        self.assertEqual([i["status"] for i in items], ["applicable", "suggestion", "refused", "suggestion"])
        for item in items:
            self.assertEqual(item["text"], item["preview"])
        self.assertEqual(items[0]["confirmCode"], "config:ui.theme.name")

    def test_in_portuguese_each_sentence_is_portuguese_and_the_codes_do_not_change(self):
        portuguese = self.ready("Portuguese (Brazil)")["configProposals"]
        self.assertEqual([i["confirmCode"] for i in portuguese if "confirmCode" in i], ["config:ui.theme.name"])
        for item in portuguese:
            self.assertNotEqual(item["text"], item["preview"], item)  # `preview` stays English: the words changed, nothing else
        self.assertIn("retro", portuguese[0]["text"])
        self.assertIn("database.password", portuguese[2]["text"])
        self.assertNotIn("x", [w for w in portuguese[2]["text"].split()])  # the refused value is still never echoed

    def test_a_portuguese_sentence_exists_for_every_knob_and_every_reason_the_sorter_can_give(self):
        import npdev_relay_preview_pt as pt

        for entry in ALLOWLIST["entries"]:
            self.assertIn(entry["property"], pt.CONFIG_PT, "a new allowlisted knob needs its Portuguese sentence")
        for reason in ("not-a-chat-setting", "never-from-a-chat", "value-not-allowed", "no-writer"):
            self.assertIn(reason, pt.CONFIG_STATUS_PT)
        schema = json.loads((REPO / "schemas" / "ai" / "ai-relay-ingest-result.schema.json").read_text(encoding="utf-8"))
        self.assertIn("text", schema["properties"]["configProposals"]["items"]["properties"])


if __name__ == "__main__":
    unittest.main()
