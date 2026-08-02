"""Tests for LC-D3's closed authoring loop (run_closed_loop) -- REG-110's named residual, the third
of three ("no automated regression test exists for run_acceptance/run_closed_loop/the JSONPath-lite
evaluator"). Move 14 Phase D item D2's own DoD: "a deliberately wrong model is stopped at the
EARLIEST gate that can catch it, one test per gate, proving the ordering is real and not decorative"
-- these are that proof, as an automated suite rather than the three manual runs REG-110 recorded.
Stdlib-only (unittest), every stage mocked -- no live boot, no real diff-gate/validator invocation.
Run with:
    python -m unittest NPDevCli.tests.test_closed_loop -v
"""

from __future__ import annotations

import argparse
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class ClosedLoopGateOrderingTest(unittest.TestCase):
    @staticmethod
    def _loop_args() -> argparse.Namespace:
        return argparse.Namespace(
            previous="previous-model.json", submitted="submitted-model.json",
            manifest=None, diff_gate_output=None,
            config="unused", output="unused", scenarios=".",
            require_db_definition=False, port=8180, timeout=420, profile="dev",
            api_key="dev-key", keep_running=False,
        )

    def test_stops_at_diff_gate_when_it_refuses(self):
        # DoD case (A): no/invalid manifest -- refused at the EARLIEST gate, nothing downstream runs.
        diff_report = {"status": "refused", "reason": "AUTHORING_MANIFEST_MISSING"}
        with patch("npdev_cli._run_authoring_gate", return_value=diff_report) as mock_diff_gate, \
             patch("npdev_cli.run_validate_semantic") as mock_validate, \
             patch("npdev_cli._classify_model_change") as mock_classify, \
             patch("npdev_cli.run_acceptance") as mock_acceptance:
            report = npdev_cli.run_closed_loop(self._loop_args())

        mock_diff_gate.assert_called_once()
        mock_validate.assert_not_called()
        mock_classify.assert_not_called()
        mock_acceptance.assert_not_called()
        self.assertEqual("diffGate", report["stoppedAt"])
        self.assertFalse(report["ok"])
        self.assertEqual(diff_report, report["diffGate"])

    def test_stops_at_validate_when_diff_gate_passes_but_model_is_semantically_invalid(self):
        # DoD case (B): diffGate correctly authorizes the diff, but the submitted model itself is
        # invalid -- stops at validate, classify/run/acceptance never run.
        diff_report = {"status": "passed"}
        with patch("npdev_cli._run_authoring_gate", return_value=diff_report), \
             patch("npdev_cli.run_validate_semantic", return_value=1) as mock_validate, \
             patch("npdev_cli.read_json", return_value={"errors": ["bogus flow step"]}), \
             patch("pathlib.Path.exists", return_value=True), \
             patch("npdev_cli._classify_model_change") as mock_classify, \
             patch("npdev_cli.run_acceptance") as mock_acceptance:
            report = npdev_cli.run_closed_loop(self._loop_args())

        mock_validate.assert_called_once()
        mock_classify.assert_not_called()
        mock_acceptance.assert_not_called()
        self.assertEqual("validate", report["stoppedAt"])
        self.assertFalse(report["ok"])

    def test_stops_at_run_when_validate_passes_but_boot_fails(self):
        diff_report = {"status": "passed"}
        acceptance_report = {"ok": False, "boot": {"ok": False, "error": "PORT_IN_USE"}}
        with patch("npdev_cli._run_authoring_gate", return_value=diff_report), \
             patch("npdev_cli.run_validate_semantic", return_value=0), \
             patch("npdev_cli._classify_model_change", return_value="SAFE_ADDITIVE"), \
             patch("npdev_cli.run_acceptance", return_value=acceptance_report) as mock_acceptance:
            report = npdev_cli.run_closed_loop(self._loop_args())

        mock_acceptance.assert_called_once()
        self.assertEqual("run", report["stoppedAt"])
        self.assertFalse(report["ok"])

    def test_stops_at_acceptance_when_boot_succeeds_but_a_scenario_fails(self):
        # DoD case (C)'s honest-failure shape: the full pipeline ran for real, boot succeeded, but
        # an approved scenario failed -- ok:false/stoppedAt:acceptance is the CORRECT answer, not a
        # defect in the loop (mirrors REG-110's own live run against fixture 02, deliberately wrong).
        diff_report = {"status": "passed"}
        acceptance_report = {"ok": False, "boot": {"ok": True, "baseUrl": "http://127.0.0.1:8183"},
                              "summary": {"failed": 1}}
        with patch("npdev_cli._run_authoring_gate", return_value=diff_report), \
             patch("npdev_cli.run_validate_semantic", return_value=0), \
             patch("npdev_cli._classify_model_change", return_value="SAFE_ADDITIVE"), \
             patch("npdev_cli.run_acceptance", return_value=acceptance_report):
            report = npdev_cli.run_closed_loop(self._loop_args())

        self.assertEqual("acceptance", report["stoppedAt"])
        self.assertFalse(report["ok"])

    def test_full_pipeline_succeeds_when_every_gate_passes(self):
        diff_report = {"status": "passed"}
        acceptance_report = {"ok": True, "boot": {"ok": True, "baseUrl": "http://127.0.0.1:8183"},
                              "summary": {"failed": 0}}
        with patch("npdev_cli._run_authoring_gate", return_value=diff_report), \
             patch("npdev_cli.run_validate_semantic", return_value=0), \
             patch("npdev_cli._classify_model_change", return_value="SAFE_ADDITIVE") as mock_classify, \
             patch("npdev_cli.run_acceptance", return_value=acceptance_report):
            report = npdev_cli.run_closed_loop(self._loop_args())

        mock_classify.assert_called_once()
        self.assertIsNone(report["stoppedAt"])
        self.assertTrue(report["ok"])
        self.assertEqual("SAFE_ADDITIVE", report["classification"])


if __name__ == "__main__":
    unittest.main()
