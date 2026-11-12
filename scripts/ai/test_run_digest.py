"""run_digest.py (QUAL-63): a digested command whose output holds characters the console cannot
encode still prints its verdict and passes its exit code through. Run: python scripts/ai/test_run_digest.py"""
from __future__ import annotations

import importlib.util
import json
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


spec = importlib.util.spec_from_file_location("run_digest", RUNNER)
digest_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(digest_mod)

try:
    import psutil  # noqa: F401
    HAVE_PSUTIL = True
except ImportError:
    HAVE_PSUTIL = False

CFG = {"stdoutSeconds": 30.0, "quietAfterSeconds": 60.0, "stalledAfterSeconds": 300.0, "cpuActiveRatio": 0.01,
       "lastLineChars": 60}


class PulseDecidesSlowFromStuck(unittest.TestCase):
    """RUNNING / QUIET / STALLED? from two clocks, with no real time and no process: pure logic."""

    def test_recent_output_is_running(self):
        p = digest_mod.Pulse(CFG, 0.0)
        p.output(50.0)
        self.assertEqual(p.sample(100.0, 5.0)["phase"], "RUNNING")

    def test_silent_but_computing_is_quiet_not_stalled(self):
        p = digest_mod.Pulse(CFG, 0.0)
        p.sample(30.0, 1.0)
        for t in range(60, 1000, 30):  # no output for 16 minutes, CPU keeps advancing
            snap = p.sample(float(t), 1.0 + t * 0.5)
        self.assertEqual(snap["phase"], "QUIET")
        self.assertEqual(snap["quietSeconds"], 990.0)

    def test_silent_and_idle_for_the_stall_window_is_stalled(self):
        p = digest_mod.Pulse(CFG, 0.0)
        p.sample(30.0, 10.0)
        phases = {t: p.sample(float(t), 10.0)["phase"] for t in range(60, 400, 30)}  # CPU flat
        self.assertEqual(phases[60], "QUIET")      # quiet long enough to mention, not long enough to suspect
        self.assertEqual(phases[330], "STALLED?")  # 300 s without output or CPU

    def test_output_or_cpu_resets_the_stall_clock(self):
        p = digest_mod.Pulse(CFG, 0.0)
        p.sample(30.0, 10.0)
        for t in range(60, 300, 30):
            p.sample(float(t), 10.0)
        p.output(299.0)
        self.assertEqual(p.sample(330.0, 10.0)["phase"], "RUNNING")

    def test_without_a_cpu_probe_it_can_only_say_quiet(self):
        p = digest_mod.Pulse(CFG, 0.0)
        snap = p.sample(5000.0, None)
        self.assertEqual(snap["phase"], "QUIET")
        self.assertIsNone(snap["cpuSeconds"])


class HeartbeatSettingsAreParametrizable(unittest.TestCase):
    POLICY = {"heartbeat": {"stdoutSeconds": 20, "quietAfterSeconds": 40, "unknownKey": 1}}

    def test_policy_then_environment_then_command_line(self):
        self.assertEqual(digest_mod.heartbeat_settings(self.POLICY, {}, {})["stdoutSeconds"], 20)
        env = {"NPDEV_HEARTBEAT_SECONDS": "10"}
        self.assertEqual(digest_mod.heartbeat_settings(self.POLICY, {}, env)["stdoutSeconds"], 10)
        cfg = digest_mod.heartbeat_settings(self.POLICY, {"stdoutSeconds": 5, "stalledAfterSeconds": None}, env)
        self.assertEqual(cfg["stdoutSeconds"], 5)
        self.assertEqual(cfg["stalledAfterSeconds"], 300)  # untouched fallback
        self.assertEqual(cfg["quietAfterSeconds"], 40)
        self.assertNotIn("unknownKey", cfg)

    def test_a_garbage_environment_value_is_ignored(self):
        self.assertEqual(digest_mod.heartbeat_settings({}, {}, {"NPDEV_HEARTBEAT_SECONDS": "soon"})["stdoutSeconds"], 30)

    def test_missing_policy_section_falls_back_to_thirty_seconds(self):
        self.assertEqual(digest_mod.heartbeat_settings({}, {}, {})["stdoutSeconds"], 30)

    def test_the_shipped_policy_declares_the_section(self):
        policy = json.loads(digest_mod.POLICY.read_text(encoding="utf-8"))
        self.assertEqual(policy["heartbeat"]["stdoutSeconds"], 30)


class HeartbeatLineFormat(unittest.TestCase):
    def test_line_carries_state_counts_and_a_truncated_last_line(self):
        snap = {"phase": "QUIET", "elapsedSeconds": 3725, "quietSeconds": 95.0, "cpuSeconds": 41.5}
        line = digest_mod.heartbeat_line(snap, 1204, 'x "quoted"   ' + "y" * 100, CFG)
        self.assertTrue(line.startswith("[hb] 1:02:05 QUIET lines=1204 quiet=95s cpu=41.5s last=\"x 'quoted' yyy"), line)
        self.assertLess(len(line), 140)

    def test_no_cpu_and_no_last_line_are_left_out(self):
        snap = {"phase": "RUNNING", "elapsedSeconds": 30, "quietSeconds": 0.0, "cpuSeconds": None}
        self.assertEqual(digest_mod.heartbeat_line(snap, 0, "", CFG), "[hb] 00:30 RUNNING lines=0 quiet=0s")
        self.assertNotIn("last=", digest_mod.heartbeat_line(snap, 3, "text", dict(CFG, lastLineChars=0)))


def run_runner(child_code: str, *flags: str, timeout: int = 60) -> subprocess.CompletedProcess:
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        env = dict(os.environ, NPDEV_BUILD_ROOT=td, PYTHONIOENCODING="utf-8")
        for name in ("NPDEV_HEARTBEAT_SECONDS", "NPDEV_HEARTBEAT_QUIET_AFTER", "NPDEV_HEARTBEAT_STALLED_AFTER"):
            env.pop(name, None)
        return subprocess.run([sys.executable, str(RUNNER), "--label", "hb-test", "--family", "generic", *flags,
                               "--", sys.executable, "-c", child_code],
                              capture_output=True, env=env, timeout=timeout, text=True, encoding="utf-8")


class SilentCommandIsNeverABlackBox(unittest.TestCase):
    """The reason this exists: a background log at 0 bytes for half an hour (2026-11-12, m4s6mf)."""

    def test_opening_line_comes_first_then_heartbeats_then_the_result(self):
        proc = run_runner("import time; time.sleep(4)", "--heartbeat", "1", "--quiet-after", "2")
        out = proc.stdout.splitlines()
        self.assertTrue(out[0].startswith("[run] "), out[:2])
        self.assertIn("pid=", out[0])
        self.assertIn("log=", out[0])
        beats = [ln for ln in out if ln.startswith("[hb] ")]
        self.assertGreaterEqual(len(beats), 2, proc.stdout)
        self.assertLess(out.index(beats[0]), out.index(next(ln for ln in out if "ok  exit=0" in ln)))
        self.assertEqual(proc.returncode, 0)

    def test_heartbeat_zero_restores_the_old_silence(self):
        proc = run_runner("import time; time.sleep(2)", "--heartbeat", "0")
        self.assertNotIn("[run]", proc.stdout)
        self.assertNotIn("[hb]", proc.stdout)
        self.assertIn("ok  exit=0", proc.stdout)

    def test_a_failing_silent_command_still_reports_its_exit_code(self):
        proc = run_runner("import sys, time; time.sleep(2); sys.exit(7)", "--heartbeat", "1")
        self.assertIn("[hb]", proc.stdout)
        self.assertIn("FAILED  exit=7", proc.stdout)
        self.assertEqual(proc.returncode, 7)

    @unittest.skipUnless(HAVE_PSUTIL, "psutil is what tells slow from stuck")
    def test_a_sleeping_command_is_called_stalled_and_a_computing_one_is_not(self):
        flags = ("--heartbeat", "1", "--quiet-after", "1", "--stalled-after", "3")
        sleeper = run_runner("import time; time.sleep(8)", *flags)
        self.assertIn("STALLED?", sleeper.stdout, sleeper.stdout)
        spinner = run_runner("import time; t=time.time()\nwhile time.time()-t < 6: pass", *flags)
        beats = [ln for ln in spinner.stdout.splitlines() if ln.startswith("[hb] ")]
        self.assertTrue(beats, spinner.stdout)
        self.assertNotIn("STALLED?", spinner.stdout, "a command burning CPU is slow, not stuck")
        self.assertIn("QUIET", spinner.stdout)


if __name__ == "__main__":
    unittest.main()
