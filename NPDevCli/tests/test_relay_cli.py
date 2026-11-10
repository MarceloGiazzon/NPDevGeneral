"""`npdev ai relay prompt|ingest` end to end through the real CLI (subprocess), the way the Manager calls it.

Run with: python -m unittest NPDevCli.tests.test_relay_cli -v
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLI = HERE.parent / "npdev_cli.py"
CASES = {c["name"]: c for c in json.loads(
    (HERE / "fixtures" / "relay" / "relay-ingest-cases.json").read_text(encoding="utf-8"))["cases"]}


_STORE = tempfile.mkdtemp(prefix="npdev-relay-cli-test-")  # the tests never touch the user's own conversations


def run_cli(*argv: str, stdin: str | None = None) -> subprocess.CompletedProcess:
    env = {**os.environ, "NPDEV_AI_CONFIG": str(Path(_STORE) / "ai.json")}
    return subprocess.run([sys.executable, str(CLI), "ai", "relay", *argv], input=stdin,
                          capture_output=True, text=True, encoding="utf-8", timeout=120, env=env)


class PromptVerbTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def write(self, name: str, text: str) -> str:
        path = self.dir / name
        path.write_text(text, encoding="utf-8")
        return str(path)

    def test_create_prompt_is_framed_and_json(self):
        ask = self.write("ask.txt", "A workshop app: customers, vehicles and work orders.")
        done = run_cli("prompt", "--intent", "create", "--ask-file", ask, "--code", "k7q2", "--json")
        self.assertEqual(done.returncode, 0, done.stderr)
        out = json.loads(done.stdout)
        self.assertTrue(out["ok"])
        self.assertEqual((out["code"], out["round"], out["intent"]), ("K7Q2", 1, "create"))
        self.assertEqual(out["bytes"], len(out["prompt"].encode("utf-8")))
        self.assertTrue(out["prompt"].startswith("NPDEV-PROMPT K7Q2-1"))
        self.assertTrue(out["prompt"].rstrip().endswith("NPDEV-PROMPT-END K7Q2-1"))
        self.assertIn("A workshop app", out["prompt"])
        self.assertEqual(len(out["knowledgeSha256"]), 64)

    def test_plain_output_and_a_fresh_code_each_time(self):
        ask = self.write("ask.txt", "A tiny shop")
        first = run_cli("prompt", "--intent", "create", "--ask-file", ask)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertTrue(first.stdout.startswith("NPDEV-PROMPT "))
        self.assertTrue(first.stdout.rstrip().splitlines()[-1].startswith("NPDEV-PROMPT-END "))

    def test_ask_can_come_from_stdin_and_output_goes_to_a_file(self):
        target = self.dir / "prompt.txt"
        done = run_cli("prompt", "--intent", "create", "--ask-file", "-", "--output", str(target), "--json",
                       stdin="from stdin")
        self.assertEqual(done.returncode, 0, done.stderr)
        out = json.loads(done.stdout)
        text = target.read_bytes().decode("utf-8")
        self.assertEqual(out["bytes"], len(text.encode("utf-8")))
        self.assertIn("from stdin", text)
        self.assertNotIn("prompt", out)  # the text went to the file, not into the JSON

    def test_change_needs_a_model_and_says_so_as_json(self):
        ask = self.write("ask.txt", "Add a phone field to Customer")
        done = run_cli("prompt", "--intent", "change", "--ask-file", ask, "--json")
        self.assertEqual(done.returncode, 2)
        out = json.loads(done.stdout)
        self.assertFalse(out["ok"])
        self.assertIn("model", out["error"])

    def test_change_prompt_carries_the_model(self):
        ask = self.write("ask.txt", "Add a phone field to Customer")
        model = self.write("model.json", json.dumps({"namespace": "t.shop", "version": "1.0", "concepts": [
            {"name": "Customer", "fields": [{"name": "id", "type": "uuid", "id": True}]}]}))
        done = run_cli("prompt", "--intent", "change", "--ask-file", ask, "--model-file", model,
                       "--language", "Portuguese (Brazil)", "--json")
        self.assertEqual(done.returncode, 0, done.stderr)
        out = json.loads(done.stdout)
        self.assertIn('"namespace": "t.shop"', out["prompt"])
        self.assertIn("Portuguese (Brazil)", out["prompt"])

    def test_empty_ask_and_bad_code_are_refused(self):
        ask = self.write("ask.txt", "   ")
        self.assertEqual(run_cli("prompt", "--intent", "create", "--ask-file", ask).returncode, 2)
        ask = self.write("ask2.txt", "A shop")
        self.assertEqual(run_cli("prompt", "--intent", "create", "--ask-file", ask, "--code", "toolong").returncode, 2)

    def test_the_prompt_is_recognised_as_our_own_echo_by_ingest(self):
        ask = self.write("ask.txt", "A shop")
        out = json.loads(run_cli("prompt", "--intent", "create", "--ask-file", ask, "--code", "K7Q2", "--json").stdout)
        pasted = self.write("echo.txt", out["prompt"])
        got = json.loads(run_cli("ingest", "--text-file", pasted, "--code", "K7Q2", "--json").stdout)
        self.assertEqual(got["outcome"], "PROMPT_ECHO")


class IngestVerbTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def ingest(self, case_name: str, *extra: str) -> dict:
        case = CASES[case_name]
        path = Path(self.tmp.name) / f"{case_name}.txt"
        path.write_text(case["answer"], encoding="utf-8")
        done = run_cli("ingest", "--text-file", str(path), "--code", case.get("session", "K7Q2"),
                       "--round", str(case.get("round", 1)), "--json", *extra)
        self.assertEqual(done.returncode, 0, done.stderr)
        return json.loads(done.stdout)

    def test_a_complete_answer_is_parsed(self):
        got = self.ingest("ready-full-protocol")
        self.assertEqual(got["outcome"], "PARSED")
        paths = sorted(f["path"] for f in got["files"])
        self.assertIn("model.json", paths)

    def test_a_cut_off_answer_is_truncated_and_still_exit_zero(self):
        name = next(n for n, c in CASES.items() if c["expect"]["outcome"] == "TRUNCATED")
        got = self.ingest(name)
        self.assertEqual(got["outcome"], "TRUNCATED")
        self.assertEqual(got["next"]["kind"], "copy-prompt")  # even with no saved conversation, the message to copy comes back
        self.assertTrue(got["next"]["prompt"].startswith("NPDEV-PROMPT K7Q2-2"))

    def test_the_plain_text_form_is_one_outcome_line_then_files(self):
        case = CASES["ready-full-protocol"]
        path = Path(self.tmp.name) / "a.txt"
        path.write_text(case["answer"], encoding="utf-8")
        done = run_cli("ingest", "--text-file", str(path), "--code", "K7Q2")
        self.assertEqual(done.returncode, 0, done.stderr)
        lines = done.stdout.splitlines()
        self.assertEqual(lines[0], "outcome: PARSED")
        self.assertTrue(any("model.json" in line for line in lines[1:]))

    def test_a_missing_file_is_an_error_not_an_outcome(self):
        done = run_cli("ingest", "--text-file", str(Path(self.tmp.name) / "nope.txt"), "--code", "K7Q2", "--json")
        self.assertEqual(done.returncode, 2)
        self.assertFalse(json.loads(done.stdout)["ok"])


class SessionVerbTests(unittest.TestCase):
    """start / prompt --code / ingest --code / status / list / abort through the real CLI, in a throwaway store."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.env = {**os.environ, "NPDEV_AI_CONFIG": str(self.dir / "ai.json")}

    def call(self, *argv: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(CLI), "ai", "relay", *argv], capture_output=True, text=True,
                              encoding="utf-8", timeout=120, env=self.env)

    def test_a_conversation_from_start_to_abort(self):
        ask = self.dir / "ask.txt"
        ask.write_text("A shop with customers and orders", encoding="utf-8")
        started = self.call("start", "--intent", "create", "--ask-file", str(ask), "--new-app-name", "shop", "--json")
        self.assertEqual(started.returncode, 0, started.stderr)
        code = json.loads(started.stdout)["code"]

        again = self.call("prompt", "--code", code, "--json")
        self.assertEqual(json.loads(again.stdout)["prompt"], json.loads(started.stdout)["prompt"])

        echo = self.dir / "echo.txt"
        echo.write_text(json.loads(started.stdout)["prompt"], encoding="utf-8")
        got = json.loads(self.call("ingest", "--code", code, "--text-file", str(echo), "--source", "clipboard-watch", "--json").stdout)
        self.assertEqual(got["outcome"], "PROMPT_ECHO")

        listed = json.loads(self.call("list", "--json").stdout)
        self.assertEqual([s["code"] for s in listed["sessions"]], [code])
        status = json.loads(self.call("status", "--code", code, "--json").stdout)
        self.assertEqual(status["session"]["intent"], "create")

        self.assertEqual(json.loads(self.call("abort", "--code", code, "--json").stdout)["state"], "abandoned")
        self.assertEqual(json.loads(self.call("list", "--json").stdout)["sessions"], [])

    def test_an_unknown_conversation_is_an_error_with_a_plain_message(self):
        done = self.call("status", "--code", "ZZZZ", "--json")
        self.assertEqual(done.returncode, 2)
        self.assertFalse(json.loads(done.stdout)["ok"])

    def test_apply_before_any_answer_is_a_result_not_a_crash(self):
        ask = self.dir / "ask.txt"
        ask.write_text("A shop", encoding="utf-8")
        code = json.loads(self.call("start", "--intent", "create", "--ask-file", str(ask), "--json").stdout)["code"]
        done = self.call("apply", "--code", code, "--json")
        self.assertEqual(done.returncode, 0, done.stderr)
        out = json.loads(done.stdout)
        self.assertEqual((out["ok"], out["error"]), (False, "not-ready"))


class HelpTests(unittest.TestCase):
    def test_relay_is_a_sub_command_of_ai_not_a_top_level_one(self):
        top = subprocess.run([sys.executable, str(CLI), "--help"], capture_output=True, text=True,
                             encoding="utf-8", timeout=120).stdout
        self.assertNotRegex(top, r"(?m)^\s+relay\b")
        sub = subprocess.run([sys.executable, str(CLI), "ai", "--help"], capture_output=True, text=True,
                             encoding="utf-8", timeout=120).stdout
        self.assertIn("relay", sub)


if __name__ == "__main__":
    unittest.main()
