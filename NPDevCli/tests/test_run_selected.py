"""FORM P1-T4: scripts/quality/run_selected.py executes what select_checks.py selects.

Hermetic: the registry, the profile and the subprocess calls are all stubbed -- nothing runs, nothing is
recorded in the cadence ledger.
"""

from __future__ import annotations

import importlib.util
import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
QUALITY = REPO_ROOT / "scripts" / "quality"


def _load():
    import sys
    sys.path.insert(0, str(QUALITY))
    spec = importlib.util.spec_from_file_location("run_selected_under_test", QUALITY / "run_selected.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rs = _load()


def _check(check_id, run, level="standard", cost=10):
    return {"id": check_id, "tier": "T1", "level": level, "costSeconds": cost, "run": run,
            "invokedBy": f"manual {check_id}", "triggers": ["**"], "protects": "correctness"}


class RunSelectedTest(unittest.TestCase):
    def _run(self, checks, exit_codes, extra_args=()):
        calls = []

        def fake_run(cmd, cwd=None, **kwargs):
            if "cadence_state.py" in " ".join(map(str, cmd)):
                return SimpleNamespace(returncode=0)
            calls.append(cmd)
            return SimpleNamespace(returncode=exit_codes.pop(0))

        out = io.StringIO()
        with mock.patch.object(rs.select_checks, "load_checks", return_value=checks), \
                mock.patch.object(rs.select_checks, "load_profile", return_value={
                    "checkLevel": "low", "checkLevelMap": {"low": ["core", "standard"]}}), \
                mock.patch.object(rs.subprocess, "run", side_effect=fake_run), \
                redirect_stdout(out):
            code = rs.main(["run_selected.py", "--level", "low", "--changed", "x.txt", *extra_args])
        return code, calls, out.getvalue()

    def test_nothing_selected_is_green(self):
        code, calls, out = self._run([], [])
        self.assertEqual(code, 0)
        self.assertEqual(calls, [])
        self.assertIn("Nothing selected", out)

    def test_shared_command_runs_once(self):
        shared = ["pwsh", "-File", "fast.ps1"]
        code, calls, out = self._run([_check("a", shared), _check("b", shared)], [0])
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 1)
        self.assertIn("a + b", out)

    def test_failure_makes_the_run_red_and_later_checks_still_run(self):
        code, calls, out = self._run([_check("a", ["one"], cost=20), _check("b", ["two"], cost=10)], [1, 0])
        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 2)
        self.assertIn("NOT GREEN: 1 failed", out)

    def test_stop_on_failure_reports_the_rest_as_not_run(self):
        code, calls, out = self._run([_check("a", ["one"], cost=20), _check("b", ["two"], cost=10)], [1],
                                     ("--stop-on-failure",))
        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 1)
        self.assertIn("not-run", out)

    def test_manual_entry_is_named_not_counted_green(self):
        code, calls, out = self._run([_check("m", None), _check("a", ["one"])], [0])
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 1)
        self.assertIn("manual          m", out)
        self.assertIn("NOT run", out)

    def test_dry_run_runs_nothing(self):
        code, calls, out = self._run([_check("a", ["one"])], [], ("--dry-run",))
        self.assertEqual(code, 0)
        self.assertEqual(calls, [])

    def test_tokens_expand(self):
        argv = rs.expand(["{python}", "{gradlew}", "plain"])
        self.assertEqual(argv[2], "plain")
        self.assertNotIn("{python}", argv)
        self.assertTrue(argv[1].endswith(("gradlew", "gradlew.bat")))

    def test_every_registry_run_is_a_list_or_null(self):
        for check in rs.select_checks.load_checks():
            self.assertIn("run", check, check["id"])
            run = check["run"]
            self.assertTrue(run is None or (isinstance(run, list) and run and all(isinstance(p, str) for p in run)),
                            check["id"])


if __name__ == "__main__":
    unittest.main()
