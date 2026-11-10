"""The browser chat AIs the relay knows (relay-targets.json), choosing one, and sending a long prompt as a file.

Run with: python -m unittest NPDevCli.tests.test_relay_targets -v
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay as relay_cli  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_prompts as rp  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
import npdev_relay_targets as rt  # noqa: E402
from test_relay_engine import EngineCase, answer  # noqa: E402


class TargetsFileTests(unittest.TestCase):
    def test_the_file_and_the_session_agree_on_which_AIs_exist(self):
        self.assertEqual(rt.ids(), sessions.TARGETS)

    def test_every_profile_is_complete_and_every_address_is_https_or_absent(self):
        for p in rt.profiles():
            self.assertIn(p["tier"], rt.TIERS, p["id"])
            self.assertTrue(p["label"], p["id"])
            self.assertIsInstance(p["maxPasteChars"], int, p["id"])
            self.assertIsInstance(p["outputBudgetChars"], int, p["id"])
            self.assertIn(p["delivery"], rt.DELIVERY_KINDS, p["id"])
            self.assertTrue(p["url"] is None or p["url"].startswith("https://"), p["id"])
            self.assertFalse(p["calibrated"], f"{p['id']}: calibrated only with a row in the calibration log behind it")

    def test_defaults_fill_in_and_a_target_can_override_them(self):
        by_id = {p["id"]: p for p in rt.profiles()}
        self.assertEqual(by_id["chatgpt"]["maxPasteChars"], 40000)
        self.assertEqual(by_id["google-ai-mode"]["maxPasteChars"], 8000)
        self.assertEqual(by_id["google-ai-mode"]["labels"]["pt"], "Google Modo IA")
        self.assertIsNone(by_id["generic"]["url"])
        self.assertEqual(by_id["generic"]["label"], "Another AI")

    def test_google_ai_mode_is_its_own_target_not_gemini(self):
        by_id = {p["id"]: p for p in rt.profiles()}
        self.assertNotEqual(by_id["google-ai-mode"]["url"], by_id["gemini"]["url"])

    def test_a_broken_file_raises_instead_of_falling_back(self):
        good = json.loads(rt.DEFAULT_TARGETS_PATH.read_text(encoding="utf-8"))
        cases = {
            "wrong version": {**good, "schemaVersion": "nope"},
            "no targets": {**good, "targets": []},
            "repeated id": {**good, "targets": [good["targets"][0], good["targets"][0]]},
            "plain http": {**good, "targets": [{**good["targets"][0], "url": "http://chatgpt.com/"}]},
            "a script address": {**good, "targets": [{**good["targets"][0], "url": "javascript:alert(1)"}]},
            "unknown tier": {**good, "targets": [{**good["targets"][0], "tier": "huge"}]},
            "no name": {**good, "targets": [{**good["targets"][0], "label": ""}]},
            "bad default": {**good, "defaults": {**good["defaults"], "maxPasteChars": 0}},
        }
        with tempfile.TemporaryDirectory() as tmp:
            for name, data in cases.items():
                path = Path(tmp) / "t.json"
                path.write_text(json.dumps(data), encoding="utf-8")
                with self.assertRaises(rt.RelayTargetsError, msg=name):
                    rt.load_targets(path)
            path.write_text("{ not json", encoding="utf-8")
            with self.assertRaises(rt.RelayTargetsError):
                rt.load_targets(path)

    def test_fits_is_inclusive_at_the_limit(self):
        p = rt.find("google-ai-mode")
        self.assertTrue(rt.fits(p, 8000))
        self.assertFalse(rt.fits(p, 8001))

    def test_an_unknown_id_is_refused_in_words(self):
        with self.assertRaises(rt.RelayTargetsError):
            rt.find("copilot")


class ChoosingTests(EngineCase):
    def test_the_list_is_what_the_file_says(self):
        out = engine.targets_list()
        self.assertTrue(out["ok"])
        self.assertEqual([t["id"] for t in out["targets"]], list(sessions.TARGETS))

    def test_choosing_remembers_the_target_and_says_a_normal_prompt_fits_a_big_chat(self):
        code = self.start()["code"]
        self.assertEqual(sessions.load(code)["target"], "generic")
        out = engine.choose_target(code, "chatgpt")
        self.assertTrue(out["fits"])
        self.assertEqual(out["delivery"], "paste")
        self.assertEqual(out["target"]["url"], "https://chatgpt.com/")
        self.assertEqual(out["chars"], len(engine.stored_prompt(code)["prompt"]))
        self.assertEqual(sessions.load(code)["target"], "chatgpt")

    def test_a_prompt_over_the_limit_is_offered_as_a_file(self):
        code = self.start()["code"]
        chars = len(engine.stored_prompt(code)["prompt"])
        original = rt.profiles
        try:
            def tight(path=None):
                return [{**p, "maxPasteChars": chars - 1} if p["id"] == "google-ai-mode" else p for p in original(path)]
            rt.profiles = tight
            out = engine.choose_target(code, "google-ai-mode")
        finally:
            rt.profiles = original
        self.assertFalse(out["fits"])
        self.assertEqual(out["delivery"], "attachment")
        self.assertEqual(sessions.load(code)["target"], "google-ai-mode")

    def test_the_choice_is_written_to_the_transcript_without_the_prompt(self):
        code = self.start()["code"]
        engine.choose_target(code, "gemini")
        lines = (sessions.session_dir(code) / "transcript.jsonl").read_text(encoding="utf-8").splitlines()
        event = [json.loads(l) for l in lines if json.loads(l)["kind"] == "target-chosen"][0]
        self.assertEqual(event["target"], "gemini")
        self.assertIn("chars", event)
        self.assertNotIn("NPDEV-PROMPT", "\n".join(lines).replace("prompt-composed", ""))

    def test_an_unknown_target_or_a_closed_conversation_is_refused_and_nothing_changes(self):
        code = self.start()["code"]
        with self.assertRaises(engine.RelayStageError):
            engine.choose_target(code, "copilot")
        self.assertEqual(sessions.load(code)["target"], "generic")
        engine.abort(code)
        with self.assertRaises(engine.RelayStageError):
            engine.choose_target(code, "claude")
        with self.assertRaises(sessions.SessionNotFound):
            engine.choose_target("ZZZZ", "claude")

    def test_the_command_line_verbs(self):
        code = self.start()["code"]

        def run(*argv):
            buf = io.StringIO()
            import argparse
            root = argparse.ArgumentParser()
            sub = root.add_subparsers(dest="ai_command")
            ai = sub.add_parser("ai")
            relay_cli.add_arguments(ai.add_subparsers(dest="ai_sub"))
            args = root.parse_args(["ai", "relay", *argv])
            with contextlib.redirect_stdout(buf):
                status = relay_cli.run(args)
            return status, buf.getvalue()

        status, text = run("targets", "--json")
        self.assertEqual(status, 0)
        self.assertEqual(len(json.loads(text)["targets"]), len(sessions.TARGETS))
        status, text = run("target", "--code", code, "--target", "claude", "--json")
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(text)["target"]["id"], "claude")
        status, text = run("target", "--code", code, "--target", "nope", "--json")
        self.assertNotEqual(status, 0)
        self.assertFalse(json.loads(text)["ok"])


class AttachmentTests(EngineCase):
    def test_the_attachment_comes_with_the_one_line_to_write_beside_it_and_records_the_delivery(self):
        out = self.start()
        written = engine.write_attachment(out["code"])
        self.assertEqual(written["chatMessage"], rp.attachment_message(rp.load_prompts()))
        self.assertIn("attached file", written["chatMessage"])
        self.assertLess(len(written["chatMessage"]), 200)
        self.assertEqual(sessions.load(out["code"])["rounds"][0]["delivery"], "attachment")

    def test_the_file_never_lands_in_a_downloads_folder(self):
        out = self.start()
        written = engine.write_attachment(out["code"])
        self.assertNotIn("downloads", str(written["file"]).lower())
        self.assertTrue(str(written["file"]).startswith(str(sessions.root())))


class StatsTests(EngineCase):
    """`relay stats`: the calibration-log rows and the per-AI numbers, read back from the stored conversations."""

    def conversation_that_took_two_rounds(self, target="chatgpt"):
        code = self.start()["code"]
        engine.choose_target(code, target)
        first = engine.ingest(code, "Sure, I will build that for you.", source="paste")
        self.assertNotEqual(first["outcome"], "READY")
        second = engine.ingest(code, answer(code, 2), source="clipboard-watch")
        self.assertEqual(second["outcome"], "READY", second["findings"])
        return code

    def test_a_conversation_that_passed_in_two_rounds_is_two_rows_with_the_same_final_and_rounds(self):
        code = self.conversation_that_took_two_rounds()
        rows = [r for r in engine.stats()["rows"] if r["target"] == "chatgpt"]
        self.assertEqual([r["round"] for r in rows], [1, 2])
        self.assertEqual([r["final"] for r in rows], ["passed", "passed"])
        self.assertEqual([r["rounds_to_pass"] for r in rows], [2, 2])
        self.assertEqual(rows[0]["outcome"], "NO_FILES")
        self.assertEqual(rows[1]["outcome"], "READY")
        self.assertEqual([r["copy_method"] for r in rows], ["manual-paste", "clipboard"])
        self.assertEqual([r["paste_ok"] for r in rows], ["yes", "yes"])
        self.assertEqual(rows[1]["answer_chars"], len(answer(code, 2)))
        self.assertEqual(set(engine.CSV_COLUMNS), set(rows[0]), "the rows carry exactly the calibration log's columns")
        for blank in ("plan", "prompt_id", "notes"):
            self.assertEqual(rows[0][blank], "", f"{blank} is the person's to fill in")

    def test_the_summary_per_target_says_how_it_went(self):
        self.conversation_that_took_two_rounds("chatgpt")
        abandoned = self.start()["code"]
        engine.abort(abandoned)
        summary = {t["target"]: t for t in engine.stats()["byTarget"]}
        chat = summary["chatgpt"]
        self.assertEqual((chat["conversations"], chat["passed"], chat["averageRoundsToPass"], chat["passedInTwoRoundsOrFewer"]), (1, 1, 2.0, 1))
        self.assertEqual(chat["outcomes"], {"NO_FILES": 1, "READY": 1})
        self.assertGreater(chat["largestPromptWithAnAnswer"], 0)
        self.assertEqual(chat["suggestedOutputBudgetChars"], int(chat["largestReadyAnswer"] * 0.8))
        generic = summary["generic"]
        self.assertEqual((generic["conversations"], generic["passed"], generic["averageRoundsToPass"]), (1, 0, None))

    def test_a_prompt_sent_as_a_file_is_marked_attachment_and_an_unanswered_round_is_not_called_pasted(self):
        code = self.start()["code"]
        engine.write_attachment(code)
        row = engine.stats()["rows"][0]
        self.assertEqual((row["paste_ok"], row["outcome"], row["final"]), ("attachment", "", "open"))

    def test_nothing_on_disk_is_an_empty_report_not_an_error(self):
        report = engine.stats()
        self.assertEqual((report["ok"], report["rows"], report["byTarget"]), (True, [], []))

    def test_the_command_line_prints_csv_in_the_logs_column_order(self):
        self.conversation_that_took_two_rounds()
        import argparse
        root = argparse.ArgumentParser()
        ai = root.add_subparsers(dest="c").add_parser("ai")
        relay_cli.add_arguments(ai.add_subparsers(dest="s"))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.assertEqual(relay_cli.run(root.parse_args(["ai", "relay", "stats", "--csv"])), 0)
        lines = buf.getvalue().strip().splitlines()
        self.assertEqual(lines[0], ",".join(engine.CSV_COLUMNS))
        self.assertEqual(len(lines), 3)
        self.assertTrue(lines[1].startswith("20") and ",chatgpt," in lines[1])


if __name__ == "__main__":
    unittest.main()
