"""run_digest.py (QUAL-63): a digested command whose output holds characters the console cannot
encode still prints its verdict and passes its exit code through. Run: python scripts/ai/test_run_digest.py"""
from __future__ import annotations

import os
import subprocess
import sys
import time
import unittest
from pathlib import Path

RUNNER = Path(__file__).resolve().parent / "run_digest.py"


def run_under_cp1252(child_code: str, exit_code: int) -> subprocess.CompletedProcess:
    script = f"import sys; sys.stdout.buffer.write({child_code!r}.encode('utf-8')); sys.exit({exit_code})"
    env = dict(os.environ, PYTHONIOENCODING="cp1252", PYTHONUTF8="0")
    return subprocess.run([sys.executable, str(RUNNER), "--label", "qual63-test", "--tail", "5", "--",
                           sys.executable, "-c", script],
                          capture_output=True, env=env, timeout=60)


class NonCp1252Output(unittest.TestCase):
    def test_success_verdict_survives_unencodable_characters(self):
        proc = run_under_cp1252("✔ passed ── → done\n", 0)
        out = proc.stdout.decode("cp1252", errors="replace")
        self.assertNotIn("UnicodeEncodeError", proc.stderr.decode("cp1252", errors="replace"))
        self.assertIn("ok  exit=0", out)
        self.assertEqual(proc.returncode, 0)

    def test_failure_exit_code_is_passed_through(self):
        proc = run_under_cp1252("✘ FAILED → boom\n", 3)
        out = proc.stdout.decode("cp1252", errors="replace")
        self.assertIn("FAILED  exit=3", out)
        self.assertEqual(proc.returncode, 3)


class ForkedDaemonHoldsThePipe(unittest.TestCase):
    def test_runner_returns_when_the_command_exits_not_when_the_pipe_closes(self):
        """A Gradle daemon forked by a gate inherits stdout and outlives it; the runner used to
        wait for EOF and hung after a finished run-all-gates (2026-10-28)."""
        script = ("import subprocess, sys; "
                  "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(45)']); "
                  "print('grandchild', p.pid, flush=True); print('command done', flush=True)")
        started = time.monotonic()
        proc = subprocess.run([sys.executable, str(RUNNER), "--label", "daemon-pipe-test", "--tail", "5", "--",
                               sys.executable, "-c", script], capture_output=True, timeout=40)
        elapsed = time.monotonic() - started
        out = proc.stdout.decode("utf-8", errors="replace")
        for word in out.split("grandchild ")[1:2]:
            try:
                os.kill(int(word.split()[0]), 9)
            except (OSError, ValueError):
                pass
        self.assertLess(elapsed, 25, "the runner waited for the grandchild instead of the command")
        self.assertIn("ok  exit=0", out)
        self.assertIn("command done", out)


if __name__ == "__main__":
    unittest.main()
