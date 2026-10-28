"""`npdev ai generate-routine --provider ai`: the Scrap assistant through the one client."""
from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from argparse import Namespace
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_ai_client  # noqa: E402
import npdev_cli  # noqa: E402


class GenerateRoutineThroughTheClientTest(unittest.TestCase):
    def setUp(self):
        self.payload = Path(tempfile.mkdtemp()) / "payload.json"
        self.payload.write_text(json.dumps({"prompt": "repair this routine"}), encoding="utf-8")

    def args(self, **extra):
        base = dict(payload_file=str(self.payload), provider="ai", profile=None, assistant_command=[], endpoint=None,
                    api_key=None, model=None, timeout=30.0, json=True)
        base.update(extra)
        return Namespace(**base)

    def run_cli(self, answer, **extra):
        out = io.StringIO()
        with mock.patch.object(npdev_ai_client, "complete", return_value=answer) as complete, \
                mock.patch("npdev_explore.validate_routine", return_value={"valid": True}), redirect_stdout(out):
            code = npdev_cli.run_ai_generate_routine(self.args(**extra))
        return code, json.loads(out.getvalue()), complete

    def test_uses_the_scrap_assistant_binding_and_the_payload_prompt(self):
        code, result, complete = self.run_cli({"ok": True, "text": 'Here: {"steps": []}'})
        self.assertEqual((code, result["ok"], result["routine"]), (0, True, {"steps": []}))
        _, prompt = complete.call_args.args
        self.assertEqual(prompt, "repair this routine")
        self.assertEqual(complete.call_args.kwargs["feature"], "scrap-assistant")
        self.assertIsNone(complete.call_args.kwargs["profile"])

    def test_a_named_profile_is_passed_through(self):
        _, _, complete = self.run_cli({"ok": True, "text": "{}"}, profile="fast")
        self.assertEqual(complete.call_args.kwargs["profile"], "fast")

    def test_a_run_effort_is_passed_through_and_none_leaves_the_profiles(self):
        _, _, complete = self.run_cli({"ok": True, "text": "{}"}, profile="deep", effort="low")
        self.assertEqual(complete.call_args.kwargs["effort"], "low")
        _, _, complete = self.run_cli({"ok": True, "text": "{}"})
        self.assertIsNone(complete.call_args.kwargs["effort"])

    def test_a_failed_call_is_a_cli_error(self):
        with self.assertRaises(npdev_cli.CliError) as ctx:
            self.run_cli({"ok": False, "error": "every target failed: fast: no key for gemini"})
        self.assertIn("no key for gemini", str(ctx.exception))


class ParserDispatch(unittest.TestCase):
    """The tests above build a Namespace by hand, so they could not see that `--command` shared the
    top-level subcommand's dest and overwrote args.command with [] -- the real call printed the
    top-level help and never ran."""

    def test_generate_routine_parses_as_the_ai_command(self):
        args = npdev_cli.build_parser().parse_args(
            ["ai", "generate-routine", "--json", "--payload-file", "p.json", "--provider", "command",
             "--command", "my-assistant", "--command", "{payload_file}"])
        self.assertEqual("ai", args.command)
        self.assertEqual("generate-routine", args.ai_command)
        self.assertEqual(["my-assistant", "{payload_file}"], args.assistant_command)

    def test_no_subcommand_option_reuses_the_top_level_dest(self):
        import argparse

        def walk(parser, path):
            for action in parser._actions:
                if isinstance(action, argparse._SubParsersAction):
                    for name, sub in action.choices.items():
                        yield from walk(sub, path + [name])
                elif path and action.dest == "command":
                    yield " ".join(path) + " " + "/".join(action.option_strings)

        self.assertEqual([], list(walk(npdev_cli.build_parser(), [])))


if __name__ == "__main__":
    unittest.main()
