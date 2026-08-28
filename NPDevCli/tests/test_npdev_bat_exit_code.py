"""A4 (Cold Clone Audit) regression: `npdev.bat`'s `where /q py && py -3 ... && exit /b` chain only
reaches `exit /b` when the Python command itself EXITS ZERO. cmd.exe's `&&` short-circuits on any
non-zero exit too, not just on `where` failing -- so `npdev doctor` (which exits non-zero by
design on a fresh clone; it is the one entry in the firstrun harness's accepted-failures.json),
every validation failure, every refusal, every boundary message all fell through past `exit /b`
into the NEXT `where /q python && ...` line, which re-ran the ENTIRE command a second time, then
fell through THAT too and printed "python3 or python is required" -- on a machine that plainly has
Python -- exiting 127 instead of the real code. This was worse than the bug it replaced: it turned
every non-zero exit on Windows into a double-run ending on a false error.

The fix branches on DISCOVERY (`where /q py`/`where /q python`), not on the launched command's own
exit code, and forwards `%ERRORLEVEL%` explicitly. Windows-only: `cmd.exe` and `.bat` semantics
have no meaningful POSIX equivalent to test here.

Run with:
    python -m unittest NPDevCli.tests.test_npdev_bat_exit_code -v
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
_NPDEV_BAT = _REPO_ROOT / "npdev.bat"


def _run_npdev_bat(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["cmd", "/c", str(_NPDEV_BAT), *args],
        cwd=_REPO_ROOT, capture_output=True, text=True, check=False,
    )


@unittest.skipUnless(sys.platform == "win32", "npdev.bat is a Windows cmd.exe launcher")
class NpdevBatExitCodeTest(unittest.TestCase):
    def test_an_invalid_command_exits_with_argparses_own_code_not_127(self):
        result = _run_npdev_bat("nosuchcommand")

        self.assertEqual(
            2, result.returncode,
            f"must forward argparse's own exit code (2), not fall through to 127: "
            f"stdout={result.stdout!r} stderr={result.stderr!r}")

    def test_an_invalid_command_prints_the_usage_error_exactly_once(self):
        result = _run_npdev_bat("nosuchcommand")

        occurrences = result.stderr.count("invalid choice: 'nosuchcommand'")
        self.assertEqual(
            1, occurrences,
            f"the && chain used to fall through on a non-zero exit and run the WHOLE command a "
            f"second time -- must appear exactly once: {result.stderr!r}")

    def test_never_falsely_claims_python_is_missing_on_a_failing_command(self):
        result = _run_npdev_bat("nosuchcommand")

        self.assertNotIn(
            "python3 or python is required", result.stderr,
            "this machine has Python -- a failing SUBCOMMAND must never be reported as "
            "Python being absent")

    def test_success_path_still_exits_zero(self):
        result = _run_npdev_bat("--version")

        self.assertEqual(0, result.returncode, f"stdout={result.stdout!r} stderr={result.stderr!r}")
        self.assertIn("npdev", result.stdout)


if __name__ == "__main__":
    unittest.main()
