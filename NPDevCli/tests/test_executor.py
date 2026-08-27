"""Tests for Phase 5 -- `npdev verify --run` (npdev_executor.py) and the producer tightening that
feeds it (npdev_panel._command_for).

These are unit-level: the resolution refusals, the command-splitting, the controlled-result mapping
(which is where the "only completed runs are ledger evidence" rule lives) and the recording-call
shape are all pure or subprocess-mocked, so they run with no gate, no PowerShell and no ledger
write. The live gate run belongs to the Phase 5 acceptance walk.

SYNTAX RULE: these run from the repo; nothing here is a check-*.py script.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_executor  # type: ignore
import npdev_panel  # type: ignore

REPO_ROOT = Path(__file__).resolve().parents[2]


def _fake_controlled(status: str, **overrides) -> dict:
    base = {"status": status, "exitCode": 0, "durationMs": 12300,
            "stdout": "", "stderr": "", "blockedReason": None}
    base.update(overrides)
    return base


class SplitCommandTest(unittest.TestCase):
    def test_ps1_with_args(self):
        exe, args = npdev_executor.split_command(
            "scripts/quality/run-all-gates.ps1 -Only aiKnowledge")
        self.assertEqual(exe, "pwsh")
        self.assertEqual(args, ["-NoProfile", "-File",
                                "scripts/quality/run-all-gates.ps1", "-Only", "aiKnowledge"])

    def test_refuses_non_ps1(self):
        with self.assertRaises(npdev_executor.ExecutorError):
            npdev_executor.split_command("python scripts/quality/check-x.py")


class MapControlledResultTest(unittest.TestCase):
    def test_passed_records(self):
        self.assertEqual(npdev_executor.map_controlled_result(_fake_controlled("passed")),
                         ("passed", 0, True))

    def test_failed_records(self):
        self.assertEqual(npdev_executor.map_controlled_result(_fake_controlled("failed")),
                         ("failed", 1, True))

    def test_timed_out_reports_but_does_not_record(self):
        # The policy cap cut the run short -- not a real verdict, so NOT ledger evidence.
        self.assertEqual(npdev_executor.map_controlled_result(_fake_controlled("timed-out")),
                         ("failed", 1, False))

    def test_blocked_raises_and_records_nothing(self):
        with self.assertRaises(npdev_executor.ExecutorError) as ctx:
            npdev_executor.map_controlled_result(_fake_controlled("blocked", blockedReason="ARGUMENT_BLOCKED"))
        self.assertIn("nothing was run", str(ctx.exception))

    def test_unknown_status_raises(self):
        with self.assertRaises(npdev_executor.ExecutorError):
            npdev_executor.map_controlled_result(_fake_controlled("exploded"))


class ResolveItemTest(unittest.TestCase):
    def _doc(self) -> dict:
        return {
            "items": [
                {"id": "runnableOne", "runnable": True, "command": "scripts/quality/run-x.ps1",
                 "tier": "T1", "name": "One"},
                {"id": "notRunnable", "runnable": False, "command": None, "tier": "T2",
                 "name": "Two"},
            ],
        }

    def test_resolves_runnable_item(self):
        with mock.patch.object(npdev_panel, "build_repo_panel", return_value=self._doc()):
            item = npdev_executor.resolve_item(REPO_ROOT, "runnableOne")
        self.assertEqual(item["id"], "runnableOne")

    def test_missing_id_refused(self):
        with mock.patch.object(npdev_panel, "build_repo_panel", return_value=self._doc()):
            with self.assertRaises(npdev_executor.ExecutorError):
                npdev_executor.resolve_item(REPO_ROOT, "ghost")

    def test_not_runnable_refused(self):
        with mock.patch.object(npdev_panel, "build_repo_panel", return_value=self._doc()):
            with self.assertRaises(npdev_executor.ExecutorError) as ctx:
                npdev_executor.resolve_item(REPO_ROOT, "notRunnable")
        self.assertIn("not runnable", str(ctx.exception))


class RecordRunTest(unittest.TestCase):
    def _item(self) -> dict:
        return {"id": "canary-build-boot-smoke", "name": "Canary", "tier": "T1",
                "command": "scripts/quality/run-fast-gate.ps1", "runnable": True}

    def test_successful_record(self):
        fake = mock.Mock(returncode=0, stdout="Recorded canary-build-boot-smoke (T1): passed\n",
                         stderr="")
        with mock.patch("npdev_executor.subprocess.run", return_value=fake) as run:
            result = npdev_executor.record_run(REPO_ROOT, self._item(), "passed", 42.5)
        self.assertTrue(result["recorded"])
        positional = run.call_args.args[0]
        self.assertIn("--duration-seconds", positional)
        self.assertIn("--id", positional)
        self.assertIn("canary-build-boot-smoke", positional)

    def test_failed_record_is_reported(self):
        fake = mock.Mock(returncode=1, stdout="", stderr="boom")
        with mock.patch("npdev_executor.subprocess.run", return_value=fake):
            result = npdev_executor.record_run(REPO_ROOT, self._item(), "failed", 1.0)
        self.assertFalse(result["recorded"])
        self.assertIn("boom", result["detail"])


class ExecuteItemTest(unittest.TestCase):
    """Full path with subprocess mocked: real resolution (the live repo panel is cheap to build),
    real command split, fake controlled result, mocked runner + recorder."""

    def test_passed_run_records(self):
        with mock.patch.object(npdev_executor, "run_controlled",
                               return_value=_fake_controlled("passed")), \
                mock.patch.object(npdev_executor, "record_run",
                                  return_value={"recorded": True, "detail": "ok"}) as rec:
            rc = npdev_executor.execute_item(REPO_ROOT, "canary-build-boot-smoke", json_out=False)
        self.assertEqual(rc, 0)
        self.assertTrue(rec.called)

    def test_timed_out_run_is_not_recorded(self):
        with mock.patch.object(npdev_executor, "run_controlled",
                               return_value=_fake_controlled("timed-out")), \
                mock.patch.object(npdev_executor, "record_run",
                                  return_value={"recorded": False, "detail": "unused"}) as rec:
            rc = npdev_executor.execute_item(REPO_ROOT, "canary-build-boot-smoke", json_out=False)
        self.assertEqual(rc, 1)
        rec.assert_not_called()

    def test_blocked_run_reports_cleanly_zeroed_run(self):
        with mock.patch.object(npdev_executor, "run_controlled",
                               return_value=_fake_controlled("blocked", blockedReason="NETWORK_BLOCKED")):
            with self.assertRaises(npdev_executor.ExecutorError):
                npdev_executor.execute_item(REPO_ROOT, "canary-build-boot-smoke", json_out=False)


class ProducerRunnabilityTest(unittest.TestCase):
    """The Phase 5 tightening of _command_for: a Run control may only exist for a command the
    controlled runner's policy will honor, with `-Only <gate>` precision for run-all-gates."""

    def test_plain_python_checker_is_not_runnable(self):
        # python is NOT in the controlled runner's allowedExecutables -> honest not-runnable.
        command, runnable = npdev_panel._command_for(
            {"invokedBy": "scripts/quality/check-closeable-streams.py"})
        self.assertIsNone(command)
        self.assertFalse(runnable)

    def test_run_all_gates_gets_only_gate(self):
        command, runnable = npdev_panel._command_for(
            {"invokedBy": "scripts/quality/run-all-gates.ps1 (gate: aiKnowledge)"})
        self.assertTrue(runnable)
        self.assertEqual(command, "scripts/quality/run-all-gates.ps1 -Only aiKnowledge")

    def test_run_all_gates_multi_part_parenthetical(self):
        command, runnable = npdev_panel._command_for(
            {"invokedBy": "scripts/quality/run-all-gates.ps1 (gate: betaRelease, opt-in since item 4) "
                          "/ npdev verify --tier T3"})
        self.assertTrue(runnable)
        self.assertEqual(command, "scripts/quality/run-all-gates.ps1 -Only betaRelease")

    def test_context_dependent_is_not_runnable(self):
        command, runnable = npdev_panel._command_for(
            {"invokedBy": "scripts/quality/run-fast-gate.ps1 -Tier T0 -ModelPath <the model being edited>",
             "contextDependent": True})
        self.assertIsNone(command)
        self.assertFalse(runnable)

    def test_manual_runbook_is_not_runnable(self):
        command, runnable = npdev_panel._command_for(
            {"invokedBy": "manual-runbook: time a full scripts/appgen/Rebuild-And-Restage.ps1 run"})
        self.assertIsNone(command)
        self.assertFalse(runnable)

    def test_non_quality_ps1_is_not_runnable(self):
        command, runnable = npdev_panel._command_for(
            {"invokedBy": "scripts/appgen/Rebuild-And-Restage.ps1"})
        self.assertIsNone(command)
        self.assertFalse(runnable)


if __name__ == "__main__":
    unittest.main()