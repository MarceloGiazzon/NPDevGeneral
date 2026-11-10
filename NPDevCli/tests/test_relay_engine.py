"""The session-aware relay pipeline (npdev_relay_engine): start, ingest, follow-ups, duplicates, circles, files.

The Java validator is replaced by a stub in almost every test (a real run is ~5 s); one test runs the real
validator and is skipped when no Java is installed.

Run with: python -m unittest NPDevCli.tests.test_relay_engine -v
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))

import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402

CASES = {c["name"]: c for c in json.loads(
    (HERE / "fixtures" / "relay" / "relay-ingest-cases.json").read_text(encoding="utf-8"))["cases"]}
SEED_MODEL = (REPO / "NPDevSamples" / "npdev-init-seed" / "model.json").read_text(encoding="utf-8")
FENCE = "`" * 3

PASSED = {"status": "passed", "summary": {"errors": 0, "warnings": 0}, "diagnostics": []}
FAILED = {"status": "failed", "summary": {"errors": 1, "warnings": 0}, "diagnostics": [
    {"severity": "error", "code": "json_schema_enum", "path": "$.concepts[0].fields[1].type",
     "message": "type is not allowed", "suggestedFix": "use one of the listed types"}]}


def answer(code: str, rnd: int, model: str = SEED_MODEL, manifest: dict | None = None, prose: str = "Done.") -> str:
    manifest = manifest if manifest is not None else {
        "request": "a shop", "renames": [], "deliberateRemovals": [], "securityChanges": [],
        "couldNotExpress": [], "assumptions": []}
    return (f"NPDEV-BEGIN {code}-{rnd}\n{prose}\nNPDEV-FILE model.json\n{FENCE}json\n{model.strip()}\n{FENCE}\n"
            f"NPDEV-FILE npdev-submission.json\n{FENCE}json\n{json.dumps(manifest)}\n{FENCE}\n"
            f"NPDEV-END {code}-{rnd} files=2\n")


class EngineCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        patcher = mock.patch.dict(os.environ, {"NPDEV_AI_CONFIG": str(self.dir / "ai.json")})
        patcher.start()
        self.addCleanup(patcher.stop)
        # Building the knowledge bundle reads ~20 files; one stub keeps every test fast and deterministic.
        knowledge = mock.patch.object(engine, "build_knowledge", return_value=("# core\n...\n", {"contentSha256": "a" * 64}))
        knowledge.start()
        self.addCleanup(knowledge.stop)
        self.validation = mock.patch.object(engine, "run_validation", return_value=PASSED)
        self.validation_mock = self.validation.start()
        self.addCleanup(self.validation.stop)

    def start(self, **extra) -> dict:
        args = dict(intent="create", ask="A shop with customers and orders", new_app_name="shop")
        args.update(extra)
        return engine.start(**args)


class StartTests(EngineCase):
    def test_create_opens_a_session_and_keeps_round_one(self):
        out = self.start()
        self.assertTrue(out["prompt"].startswith(f"NPDEV-PROMPT {out['code']}-1"))
        self.assertEqual(engine.stored_prompt(out["code"])["prompt"], out["prompt"])
        listed = engine.list_sessions()["sessions"]
        self.assertEqual([s["code"] for s in listed], [out["code"]])

    def test_change_needs_the_app_and_remembers_its_model(self):
        with self.assertRaises(engine.RelayStageError):
            self.start(intent="change", ask="add a phone")
        app = self.dir / "shop"
        app.mkdir()
        (app / "model.json").write_text(SEED_MODEL, encoding="utf-8")
        out = self.start(intent="change", ask="add a phone", app_dir=str(app))
        session = sessions.load(out["code"])
        self.assertEqual(len(session["baseModelSha256"]), 64)
        self.assertIn("Customer", out["prompt"])

    def test_the_model_is_found_from_the_generated_folder_too(self):
        model_dir = self.dir / "shop"
        model_dir.mkdir()
        (model_dir / "model.json").write_text(SEED_MODEL, encoding="utf-8")
        found = engine.find_model_file(str(self.dir / "shop-app"))
        self.assertEqual(found, model_dir / "model.json")

    def test_attachment_goes_into_the_session_folder(self):
        out = self.start()
        written = engine.write_attachment(out["code"])
        self.assertTrue(Path(written["file"]).is_file())
        self.assertIn(out["code"], Path(written["file"]).parts[-3:])
        self.assertEqual(Path(written["file"]).read_text(encoding="utf-8"), out["prompt"])


class IngestTests(EngineCase):
    def test_a_portuguese_conversation_gets_a_portuguese_preview_with_the_same_codes(self):
        code = self.start(language="Portuguese (Brazil)")["code"]
        result = engine.ingest(code, answer(code, 1), source="paste")
        self.assertEqual(result["outcome"], "READY")
        self.assertTrue(result["preview"]["sentences"][0].startswith("Um app novo com "), result["preview"]["sentences"])
        self.assertIn("Nada é apagado", " ".join(result["preview"]["sentences"]))
        self.assertEqual(result["preview"]["lines"][0]["code"], "CREATE_SUMMARY")
        english = self.start()["code"]
        self.assertTrue(engine.ingest(english, answer(english, 1), source="paste")["preview"]["sentences"][0].startswith("A new app with "))

    def test_a_valid_create_answer_is_ready_with_a_plain_preview(self):
        code = self.start()["code"]
        result = engine.ingest(code, answer(code, 1), source="paste")
        self.assertEqual(result["outcome"], "READY")
        self.assertEqual(result["next"], {"kind": "apply", "messageId": "READY"})
        self.assertIn("Nothing is deleted", " ".join(result["preview"]["sentences"]))
        self.assertEqual(sessions.load(code)["state"], "ready-to-apply")

    def test_a_validation_failure_is_needs_fix_and_prepares_the_follow_up_round(self):
        self.validation_mock.return_value = FAILED
        code = self.start()["code"]
        result = engine.ingest(code, answer(code, 1), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        nxt = result["next"]
        self.assertEqual((nxt["kind"], nxt["messageId"]), ("copy-prompt", "NEEDS_FIX"))
        self.assertTrue(nxt["prompt"].startswith(f"NPDEV-PROMPT {code}-2"))
        self.assertIn("$.concepts[0].fields[1].type", nxt["prompt"])
        self.assertIn("A shop with customers and orders", nxt["freshChatPrompt"])
        session = sessions.load(code)
        self.assertEqual([r["promptKind"] for r in session["rounds"]], ["round1", "same-chat"])

    def test_the_same_answer_twice_gives_the_same_result_not_a_stale_one(self):
        self.validation_mock.return_value = FAILED
        code = self.start()["code"]
        text = answer(code, 1)
        first = engine.ingest(code, text, source="clipboard-watch")
        second = engine.ingest(code, text, source="paste")
        self.assertEqual(second["outcome"], first["outcome"])
        self.assertIn("duplicate-answer", [f["code"] for f in second["findings"]])
        self.assertEqual(len(sessions.load(code)["rounds"]), 2)

    def test_the_previous_answer_is_stale_once_a_follow_up_exists(self):
        self.validation_mock.return_value = FAILED
        code = self.start()["code"]
        engine.ingest(code, answer(code, 1), source="paste")
        older = engine.ingest(code, answer(code, 1, prose="A different sentence."), source="paste")
        self.assertEqual(older["outcome"], "STALE_ROUND")
        self.assertEqual(older["next"], {"kind": "user-action", "messageId": "STALE_ROUND"})

    def test_the_same_mistake_twice_is_going_in_circles(self):
        self.validation_mock.return_value = FAILED
        code = self.start()["code"]
        engine.ingest(code, answer(code, 1), source="paste")
        again = engine.ingest(code, answer(code, 2, prose="Fixed it, really."), source="paste")
        self.assertEqual(again["outcome"], "NEEDS_FIX")
        self.assertIn("going-in-circles", [f["code"] for f in again["findings"]])
        self.assertEqual(again["next"]["messageId"], "GOING_IN_CIRCLES")
        self.assertTrue(again["next"]["prompt"].startswith(f"NPDEV-PROMPT {code}-3"))
        self.assertEqual(sessions.load(code)["rounds"][-1]["promptKind"], "fresh-chat")

    def test_a_missing_validator_is_never_the_ais_fault(self):
        self.validation_mock.return_value = None
        code = self.start()["code"]
        result = engine.ingest(code, answer(code, 1), source="paste")
        self.assertEqual(result["outcome"], "PARSED")
        self.assertIn("validator-unavailable", [f["code"] for f in result["findings"]])
        self.assertEqual(result["next"], {"kind": "user-action", "messageId": "JAVA_MISSING"})

    def test_a_quota_message_offers_the_conversation_for_another_ai(self):
        name = next(n for n, c in CASES.items() if c["expect"]["outcome"] == "LIMIT_REACHED")
        code = self.start()["code"]
        result = engine.ingest(code, CASES[name]["answer"], source="paste")
        self.assertEqual(result["outcome"], "LIMIT_REACHED")
        self.assertEqual(result["next"]["messageId"], "LIMIT_REACHED")
        self.assertIn("A shop with customers and orders", result["next"]["prompt"])

    def test_our_own_prompt_pasted_back_is_ignored_and_changes_nothing(self):
        out = self.start()
        result = engine.ingest(out["code"], out["prompt"], source="clipboard-watch")
        self.assertEqual(result["outcome"], "PROMPT_ECHO")
        session = sessions.load(out["code"])
        self.assertEqual(len(session["rounds"]), 1)
        self.assertIsNone(session["rounds"][0].get("outcome"))

    def test_the_desire_path_rewrites_are_reported_and_kept(self):
        broken = json.loads(SEED_MODEL)
        broken["concepts"][0]["fields"][0]["type"] = "integer number"  # not a type; the normaliser may or may not know it
        code = self.start()["code"]
        result = engine.ingest(code, answer(code, 1, model=json.dumps(broken)), source="paste")
        self.assertIn("desirePathFixes", result)

    def test_a_closed_conversation_refuses_answers(self):
        code = self.start()["code"]
        engine.abort(code)
        with self.assertRaises(engine.RelayStageError):
            engine.ingest(code, answer(code, 1), source="paste")

    def test_an_unknown_conversation_is_session_not_found(self):
        with self.assertRaises(sessions.SessionNotFound):
            engine.ingest("ZZZZ", "hello", source="paste")


class FileTests(EngineCase):
    def test_a_downloaded_model_json_is_wrapped_and_ready(self):
        code = self.start()["code"]
        path = self.dir / "model (1).json"
        path.write_text(SEED_MODEL, encoding="utf-8")
        raw, pre = engine.raw_from_files([str(path)], code, 1)
        self.assertEqual(pre, [])
        result = engine.ingest(code, raw, source="downloads-watch", pre_findings=pre)
        self.assertEqual([f["path"] for f in result["files"]], ["model.json"])
        self.assertEqual(result["outcome"], "READY")

    def test_a_zip_of_files_is_read_in_memory(self):
        code = self.start()["code"]
        path = self.dir / "answer.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("model.json", SEED_MODEL)
            archive.writestr("npdev-submission.json", json.dumps({"request": "a shop"}))
        raw, pre = engine.raw_from_files([str(path)], code, 1)
        result = engine.ingest(code, raw, source="drop", pre_findings=pre)
        self.assertEqual(sorted(f["path"] for f in result["files"]), ["model.json", "npdev-submission.json"])
        self.assertEqual(result["outcome"], "READY")

    def test_a_hostile_zip_entry_is_reported_and_never_used(self):
        code = self.start()["code"]
        path = self.dir / "evil.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("model.json", SEED_MODEL)
            archive.writestr("../escape.json", "{}")
        raw, pre = engine.raw_from_files([str(path)], code, 1)
        codes = [f["code"] for f in pre]
        self.assertIn("zip-path-traversal", codes)
        result = engine.ingest(code, raw, source="drop", pre_findings=pre)
        self.assertNotIn("escape.json", [f["path"] for f in result["files"]])
        self.assertFalse((self.dir / "escape.json").exists())

    def test_text_with_backticks_inside_a_zip_member_does_not_break_the_block(self):
        code = self.start()["code"]
        path = self.dir / "ticks.zip"
        model = json.loads(SEED_MODEL)
        model["description"] = "uses ``` three ticks"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("model.json", json.dumps(model, indent=2))
        raw, _ = engine.raw_from_files([str(path)], code, 1)
        result = engine.ingest(code, raw, source="drop")
        self.assertEqual([f["path"] for f in result["files"]], ["model.json"])
        self.assertIn("```", result["files"][0]["content"])


class RealValidatorTests(unittest.TestCase):
    """One run through the real Java validator, so the stub above cannot hide a broken integration."""

    def test_a_real_validation_of_the_seed_model_passes_and_a_broken_one_fails(self):
        import npdev_cli

        if npdev_cli.java_launcher() is None:
            self.skipTest("no Java on this machine")
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "good.json"
            good.write_text(SEED_MODEL, encoding="utf-8")
            report = engine.run_validation(good, Path(tmp) / "good-report.json")
            self.assertIsNotNone(report)
            self.assertEqual(report["status"], "passed")
            bad = Path(tmp) / "bad.json"
            model = json.loads(SEED_MODEL)
            model["concepts"][0]["fields"][1]["type"] = "money"
            bad.write_text(json.dumps(model), encoding="utf-8")
            report = engine.run_validation(bad, Path(tmp) / "bad-report.json")
            self.assertEqual(report["status"], "failed")
            self.assertTrue(engine.validation_lines(report))


if __name__ == "__main__":
    unittest.main()
