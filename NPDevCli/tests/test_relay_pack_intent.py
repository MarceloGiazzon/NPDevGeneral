"""P4.1: a conversation whose intent is `pack` -- a browser AI writes a reusable pack, not an app.

Covers the pieces P4.1 adds: the `pack` ingest phase (what a pack answer may carry), the session fields
`packId` / `packBase`, the engine's `start` and the CLI flags `--pack-id` / `--from-pack`. Ingest validation of
the pack itself is P4.3.

Run with: python -m unittest NPDevCli.tests.test_relay_pack_intent -v
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay as relay_cli  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_ingest as ri  # noqa: E402
import npdev_relay_session as rs  # noqa: E402
from test_relay_engine import EngineCase  # noqa: E402

PACK = {"pack": "ratings", "version": "1.0.0", "namespace": "ratings"}


class PackPhaseTests(unittest.TestCase):
    def setUp(self):
        self.policy = json.loads((HERE.parent / "relay-ingest-policy.json").read_text(encoding="utf-8"))

    def test_the_pack_phase_allows_exactly_the_pack_files(self):
        allowed = self.policy["paths"]["phases"]["pack"]["allowed"]
        self.assertEqual(sorted(allowed), ["example-model.json", "npdev-submission.json", "pack.json"])
        self.assertEqual(self.policy["paths"]["phases"]["pack"]["quarantine"], [])

    def test_a_pack_answer_may_carry_the_pack_files_and_nothing_else(self):
        for name in ("pack.json", "npdev-submission.json", "example-model.json"):
            self.assertEqual(ri.check_path(name, self.policy, "pack")[1], "allowed", name)
        for name, reason in (("model.json", "not-in-allowlist"), ("web/app.js", "not-in-allowlist"),
                             ("capabilities/x.java", "not-in-allowlist"), ("secrets/k", "protected-directory")):
            self.assertEqual(ri.check_path(name, self.policy, "pack")[1:], ("denied", reason), name)

    def test_pack_json_in_an_app_conversation_is_simply_not_allowed(self):
        self.assertEqual(ri.check_path("pack.json", self.policy, "f4")[1:], ("denied", "not-in-allowlist"))


class SessionTests(EngineCase):
    def test_a_pack_session_remembers_its_id_and_runs_in_the_pack_phase(self):
        session = rs.create("pack", "a ratings part", "generic", "English", None, "mvp", pack_id="ratings")
        self.assertEqual((session["intent"], session["phase"], session["packId"], session["packBase"]),
                         ("pack", "pack", "ratings", None))
        self.assertEqual(rs.load(session["code"])["packId"], "ratings")

    def test_a_new_version_keeps_the_current_pack_and_its_hash(self):
        text = json.dumps(PACK)
        session = rs.create("pack", "add a field", "generic", "English", None, "pack", pack_id="ratings", pack_base_text=text)
        self.assertEqual(session["packBase"], rs._sha256(text.encode("utf-8")))
        self.assertEqual((rs.session_dir(session["code"]) / "base-pack.json").read_text(encoding="utf-8"), text)

    def test_a_bad_pack_id_is_refused(self):
        for bad in (None, "", "Ratings", "../x", "1a", "a b"):
            with self.assertRaises(rs.RelaySessionError, msg=repr(bad)):
                rs.create("pack", "x", "generic", "English", None, "pack", pack_id=bad)

    def test_a_pack_id_on_an_app_conversation_is_refused(self):
        with self.assertRaises(rs.RelaySessionError):
            rs.create("create", "x", "generic", "English", None, "mvp", pack_id="ratings")


class StartTests(EngineCase):
    def test_start_opens_a_pack_conversation_and_names_the_pack_in_the_prompt(self):
        result = engine.start(intent="pack", ask="ratings for trips", pack_id="ratings")
        self.assertTrue(result["ok"])
        self.assertIn("ratings", result["prompt"])
        self.assertEqual(rs.load(result["code"])["packId"], "ratings")

    def test_start_for_a_new_version_shows_the_ai_the_current_pack(self):
        base = self.dir / "pack.json"
        base.write_text(json.dumps(PACK), encoding="utf-8")
        result = engine.start(intent="pack", ask="add a comment field", pack_id="ratings", from_pack=str(base))
        self.assertIn('"namespace": "ratings"', result["prompt"].replace('"namespace":"ratings"', '"namespace": "ratings"'))
        self.assertIsNotNone(rs.load(result["code"])["packBase"])

    def test_a_pack_conversation_needs_an_ask_and_an_id(self):
        with self.assertRaises(engine.RelayStageError):
            engine.start(intent="pack", ask="", pack_id="ratings")
        with self.assertRaises(engine.RelayStageError):
            engine.start(intent="pack", ask="x")


class CliFlagTests(unittest.TestCase):
    def test_start_accepts_the_pack_flags(self):
        import argparse
        parser = argparse.ArgumentParser()
        ai_sub = parser.add_subparsers(dest="command").add_parser("ai").add_subparsers(dest="ai_command")
        relay_cli.add_arguments(ai_sub)
        args = parser.parse_args(["ai", "relay", "start", "--intent", "pack", "--ask-file", "-", "--pack-id", "ratings",
                                  "--from-pack", "p.json"])
        self.assertEqual((args.intent, args.pack_id, args.from_pack), ("pack", "ratings", "p.json"))


class PromptTests(EngineCase):
    """P4.2: the round-1 prompt of a pack conversation."""

    def prompt(self, **kw):
        return engine.start(intent="pack", ask="ratings for trips", pack_id="ratings", **kw)["prompt"]

    def test_the_prompt_asks_for_pack_json_and_never_for_model_json(self):
        text = self.prompt()
        self.assertIn("NPDEV-FILE pack.json", text)
        self.assertIn("NPDEV-FILE npdev-submission.json", text)
        self.assertNotIn("NPDEV-FILE model.json", text)
        self.assertIn("files=2", text)

    def test_the_prompt_carries_the_pack_rules(self):
        text = self.prompt()
        for rule in ("EXACTLY the pack id given above", "NEW VERSION", "CONTRACT only", "No code", "signature",
                     "who may read and write", "packs/<pack id>/pack.json"):
            self.assertIn(rule, text, rule)
        self.assertIn("Pack id: ratings", text)

    def test_the_example_marker_survived_templating_as_a_literal_ref(self):
        self.assertIn('{"$ref": "packs/<pack id>/pack.json"', self.prompt())

    def test_an_app_prompt_is_untouched_by_the_pack_sections(self):
        text = engine.start(intent="create", ask="a todo app")["prompt"]
        self.assertNotIn("PACK", text)
        self.assertIn("NPDEV-FILE model.json", text)


class ExampleAnswerTests(unittest.TestCase):
    def test_the_example_pack_answer_parses_in_the_pack_phase(self):
        policy = ri.load_policy()
        raw = (HERE / "fixtures" / "relay" / "example-pack-answer.txt").read_text(encoding="utf-8")
        result = ri.ingest_text(raw, session="K7QX", expected_round=1, policy=policy, phase="pack")
        self.assertEqual(result["outcome"], ri.PARSED)
        self.assertEqual([(f["path"], f["status"]) for f in result["files"]],
                         [("pack.json", "allowed"), ("npdev-submission.json", "allowed")])

    def test_an_answer_without_pack_json_says_so(self):
        raw = ("NPDEV-BEGIN K7QX-1\nhi\nNPDEV-FILE npdev-submission.json\n```json\n{}\n```\n"
               "NPDEV-END K7QX-1 files=1\n")
        result = ri.ingest_text(raw, session="K7QX", expected_round=1, policy=ri.load_policy(), phase="pack")
        self.assertIn("missing-pack", [f["code"] for f in result["findings"]])


if __name__ == "__main__":
    unittest.main()
