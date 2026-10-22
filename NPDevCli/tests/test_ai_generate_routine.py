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
        base = dict(payload_file=str(self.payload), provider="ai", profile=None, command=[], endpoint=None,
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

    def test_a_failed_call_is_a_cli_error(self):
        with self.assertRaises(npdev_cli.CliError) as ctx:
            self.run_cli({"ok": False, "error": "every target failed: fast: no key for gemini"})
        self.assertIn("no key for gemini", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
