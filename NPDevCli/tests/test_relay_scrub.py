"""A `fix` conversation's error text is scrubbed by the CLI itself: secrets and privacy data redacted, local folders made anonymous.

Run with: python -m unittest NPDevCli.tests.test_relay_scrub -v
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay as relay_cli  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_scrub as scrub  # noqa: E402
from test_relay_engine import EngineCase, SEED_MODEL  # noqa: E402

COLON = chr(58)
SLASH = chr(92)


def win(*parts):
    """A Windows path spelled from parts, so this file never contains the text of one."""
    return "C" + COLON + SLASH + SLASH.join(parts)


class ScrubTests(unittest.TestCase):
    HOME = Path("/people/pat")

    def test_a_credential_and_an_email_address_are_redacted_with_the_one_shared_definition(self):
        text, counts = scrub.scrub_errors("GROQ_API_KEY=gsk_abcdefghijklmnopqrstuvwx was rejected; mail pat@example.com", home=self.HOME)
        self.assertNotIn("gsk_abcdefghijklmnop", text)
        self.assertNotIn("pat@example.com", text)
        self.assertEqual(counts.get("groq-key"), 1)
        self.assertEqual(counts.get("email"), 1)

    def test_the_apps_own_folder_becomes_app_whichever_way_the_slashes_lean(self):
        folder = win("Work", "shop-app")
        text, counts = scrub.scrub_errors(
            f"{folder}{SLASH}src{SLASH}Main.java:12: error: cannot find symbol\nat {folder.replace(SLASH, '/')}/build.gradle line 4",
            app_dir=folder, home=self.HOME)
        self.assertEqual(text.count("<app>"), 2)
        self.assertNotIn("Work", text)
        self.assertIn("<app>" + SLASH + "src" + SLASH + "Main.java:12", text)
        self.assertGreaterEqual(counts["local-path"], 2)

    def test_the_model_folder_next_to_a_generated_app_is_the_app_too(self):
        text, _ = scrub.scrub_errors("reading " + win("Work", "shop", "model.json") + " failed", app_dir=win("Work", "shop-app"), home=self.HOME)
        self.assertEqual(text, "reading <app>" + SLASH + "model.json failed")

    def test_the_home_folder_becomes_home(self):
        text, _ = scrub.scrub_errors("cache at /people/pat/.gradle/caches is locked", home=self.HOME)
        self.assertEqual(text, "cache at <home>/.gradle/caches is locked")

    def test_any_other_absolute_path_keeps_only_its_last_name(self):
        text, counts = scrub.scrub_errors(
            "javac: " + win("Program Files", "Java", "jdk-17", "bin", "javac.exe") + " exited 1; also /var/lib/thing/x.log and /Users/lee/work/a.txt",
            home=self.HOME)
        self.assertNotIn("jdk-17", text)
        self.assertNotIn("/var/lib", text)
        self.assertNotIn("/Users/lee", text)
        self.assertIn("<path>/javac.exe", text)
        self.assertIn("<path>/x.log", text)
        self.assertIn("<path>/a.txt", text)
        self.assertEqual(counts["local-path"], 3)

    def test_a_relative_path_a_url_and_ordinary_words_are_left_alone(self):
        original = "src/main/Main.java:3 see https://example.com/docs/a/b and the word home/ or tmp/ are plain"
        text, counts = scrub.scrub_errors(original, home=self.HOME)
        self.assertEqual(text, original)
        self.assertEqual(counts, {})

    def test_empty_and_none_are_returned_as_empty(self):
        self.assertEqual(scrub.scrub_errors("", app_dir="x"), ("", {}))
        self.assertEqual(scrub.scrub_errors(None), ("", {}))

    def test_scrubbing_twice_changes_nothing_more(self):
        once, _ = scrub.scrub_errors("at " + win("a", "b", "c.java") + " key=gsk_abcdefghijklmnopqrstuvwx", home=self.HOME)
        twice, counts = scrub.scrub_errors(once, home=self.HOME)
        self.assertEqual(once, twice)
        self.assertEqual(counts, {})


class FixConversationTests(EngineCase):
    def make_app(self):
        app = self.dir / "shop"
        app.mkdir()
        (app / "model.json").write_text(SEED_MODEL, encoding="utf-8")
        return app

    def test_a_fix_prompt_never_carries_the_secret_or_the_machines_folders(self):
        app = self.make_app()
        errors = (f"BUILD FAILED in {app}{SLASH}build\nGROQ_API_KEY=gsk_abcdefghijklmnopqrstuvwx\n"
                  f"at {app}{SLASH}src{SLASH}Main.java:9\nuser pat@example.com")
        out = engine.start(intent="fix", ask="", app_dir=str(app), errors=errors)
        self.assertNotIn("gsk_abcdefghijklmnop", out["prompt"])
        self.assertNotIn("pat@example.com", out["prompt"])
        self.assertNotIn(str(self.dir), out["prompt"])
        self.assertIn("<app>", out["prompt"])
        self.assertIn("Main.java:9", out["prompt"], "what failed is kept; only where it lives is not")
        self.assertEqual(out["errorRedactions"]["groq-key"], 1)
        stored = engine.stored_prompt(out["code"])["prompt"]
        self.assertEqual(stored, out["prompt"], "what was saved is what was scrubbed, not the raw text")

    def test_no_errors_means_nothing_to_report(self):
        out = engine.start(intent="create", ask="A shop with orders", new_app_name="shop")
        self.assertEqual(out["errorRedactions"], {})

    def test_the_stateless_prompt_scrubs_too(self):
        app = self.make_app()
        errors_file = self.dir / "errors.txt"
        errors_file.write_text("failed: GROQ_API_KEY=gsk_abcdefghijklmnopqrstuvwx", encoding="utf-8")
        ask = self.dir / "ask.txt"
        ask.write_text("fix it", encoding="utf-8")
        model = self.dir / "m.json"
        model.write_text(SEED_MODEL, encoding="utf-8")
        root = argparse.ArgumentParser()
        ai = root.add_subparsers(dest="c").add_parser("ai")
        relay_cli.add_arguments(ai.add_subparsers(dest="s"))
        args = root.parse_args(["ai", "relay", "prompt", "--intent", "fix", "--ask-file", str(ask), "--model-file", str(model),
                                "--errors-file", str(errors_file), "--json"])
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            self.assertEqual(relay_cli.run(args), 0)
        prompt = json.loads(buffer.getvalue())["prompt"]
        self.assertNotIn("gsk_abcdefghijklmnop", prompt)
        self.assertIn("<redacted:api-key>", prompt)


if __name__ == "__main__":
    unittest.main()
