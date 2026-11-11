"""`npdev validator serve|doctor|rotate-token` is only a short door to NPDevMcp/validation_service.py.

Run with: python -m unittest NPDevCli.tests.test_validator_command -v
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _forwarded(argv: list[str]) -> list[str]:
    seen: dict = {}

    def fake_call(command, cwd=None):
        seen["command"] = command
        return 0

    with patch.object(npdev_cli.subprocess, "call", fake_call):
        code = npdev_cli.main(argv)
    assert code == 0, code
    command = seen["command"]
    assert command[1].replace("\\", "/").endswith("NPDevMcp/validation_service.py"), command
    return command[2:]


class ValidatorCommandTest(unittest.TestCase):
    def test_serve_forwards_every_flag(self):
        got = _forwarded(["validator", "serve", "--port", "8781", "--mcp", "--quiet", "--max-concurrent", "1",
                          "--token-file", "tok.txt", "--capture"])
        self.assertEqual(["--port", "8781", "--token-file", "tok.txt", "--max-concurrent", "1", "--mcp",
                          "--capture", "--quiet"], got)

    def test_serve_without_flags_forwards_nothing(self):
        self.assertEqual([], _forwarded(["validator", "serve"]))

    def test_doctor_and_rotate_token(self):
        self.assertEqual(["--doctor", "--port", "8790"], _forwarded(["validator", "doctor", "--port", "8790"]))
        self.assertEqual(["--rotate-token"], _forwarded(["validator", "rotate-token"]))

    def test_the_command_is_listed_in_the_ai_help_group(self):
        group = dict(npdev_cli.HELP_GROUPS)["AI"]
        self.assertIn("validator", group)


if __name__ == "__main__":
    unittest.main()
