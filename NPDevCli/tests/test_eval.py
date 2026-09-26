"""Tests for Wave 7.1's `npdev eval run` / `npdev eval compare` (close-all-open plan, 2026-09-26).

Every collaborator that shells out to a PowerShell script (_eval_run_schema_validation,
_eval_run_beta_gate_for_one_scenario) or reads a scenario's manifest off disk (_eval_read_manifest)
is mocked -- these tests prove the STAGE-ROUTING and VERDICT logic (light scenarios never pay for a
real boot, heavy ones always do, a mismatch surfaces the right firstError) without a live boot, a
real gradle invocation, or touching the real <BuildRoot>/evals/ directory.
Run with:
    python -m unittest NPDevCli.tests.test_eval -v
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class EvalStageRoutingTest(unittest.TestCase):
    def _run_args(self, scenario=None, model=None, run_id="test-run", timeout=900):
        return argparse.Namespace(
            scenario=scenario, model=model, scenario_root=None, run_id=run_id, timeout=timeout,
        )

    def test_light_negative_scenario_never_invokes_the_heavy_beta_gate_path(self):
        manifest = {"kind": "negative", "expectedOutcome": "fail", "expectedFailureStage": "ai-model-schema"}
        schema_report = {"scenarios": [{"scenarioId": "panel-arbitrary-javascript", "status": "passed", "failures": []}]}
        with tempfile.TemporaryDirectory() as tmp:
            with patch("npdev_cli._ai_build_root", return_value=Path(tmp)), \
                 patch("npdev_cli._eval_discover_scenario_ids", return_value=["panel-arbitrary-javascript"]), \
                 patch("npdev_cli._eval_read_manifest", return_value=manifest), \
                 patch("npdev_cli._eval_run_schema_validation", return_value=schema_report) as mock_schema, \
                 patch("npdev_cli._eval_run_beta_gate_for_one_scenario") as mock_beta_gate:
                report = npdev_cli.run_eval(self._run_args())

        mock_schema.assert_called_once()
        mock_beta_gate.assert_not_called()
        self.assertTrue(report["ok"])
        self.assertEqual("PASS", report["scenarios"][0]["verdict"])
        self.assertIsNone(report["scenarios"][0]["firstError"])

    def test_positive_scenario_always_invokes_the_heavy_beta_gate_path(self):
        manifest = {"kind": "positive", "expectedOutcome": "pass", "expectedFailureStage": ""}
        beta_gate_entry = {"scenarioId": "base-ai-loop", "expectedOutcome": "pass", "status": "passed",
                            "expectedFailureMatched": False, "failureReasons": []}
        with tempfile.TemporaryDirectory() as tmp:
            with patch("npdev_cli._ai_build_root", return_value=Path(tmp)), \
                 patch("npdev_cli._eval_read_manifest", return_value=manifest), \
                 patch("npdev_cli._eval_run_schema_validation") as mock_schema, \
                 patch("npdev_cli._eval_run_beta_gate_for_one_scenario", return_value=beta_gate_entry) as mock_beta_gate:
                report = npdev_cli.run_eval(self._run_args(scenario="base-ai-loop"))

        mock_beta_gate.assert_called_once()
        mock_schema.assert_not_called()
        self.assertTrue(report["ok"])
        self.assertEqual("PASS", report["scenarios"][0]["verdict"])

    def test_smoke_verification_negative_stage_also_takes_the_heavy_path(self):
        # This stage is only reachable after a real boot -- schema validation alone cannot see it.
        manifest = {"kind": "negative", "expectedOutcome": "fail", "expectedFailureStage": "smoke-verification"}
        beta_gate_entry = {"scenarioId": "behavior-mismatch", "expectedOutcome": "fail",
                            "expectedFailureMatched": True, "status": "passed", "failureReasons": []}
        with tempfile.TemporaryDirectory() as tmp:
            with patch("npdev_cli._ai_build_root", return_value=Path(tmp)), \
                 patch("npdev_cli._eval_read_manifest", return_value=manifest), \
                 patch("npdev_cli._eval_run_schema_validation") as mock_schema, \
                 patch("npdev_cli._eval_run_beta_gate_for_one_scenario", return_value=beta_gate_entry) as mock_beta_gate:
                report = npdev_cli.run_eval(self._run_args(scenario="behavior-mismatch"))

        mock_beta_gate.assert_called_once()
        mock_schema.assert_not_called()
        self.assertEqual("PASS", report["scenarios"][0]["verdict"])

    def test_unexpected_pass_on_a_negative_scenario_surfaces_as_fail_with_first_error(self):
        manifest = {"kind": "negative", "expectedOutcome": "fail", "expectedFailureStage": "ai-model-schema"}
        schema_report = {"scenarios": [{"scenarioId": "panel-arbitrary-javascript", "status": "failed",
                                        "failures": ["expected failure at ai-model-schema but AI schema validation passed."]}]}
        with tempfile.TemporaryDirectory() as tmp:
            with patch("npdev_cli._ai_build_root", return_value=Path(tmp)), \
                 patch("npdev_cli._eval_read_manifest", return_value=manifest), \
                 patch("npdev_cli._eval_run_schema_validation", return_value=schema_report):
                report = npdev_cli.run_eval(self._run_args(scenario="panel-arbitrary-javascript"))

        self.assertFalse(report["ok"])
        self.assertEqual("FAIL", report["scenarios"][0]["verdict"])
        self.assertIn("but AI schema validation passed", report["scenarios"][0]["firstError"])

    def test_model_override_without_scenario_is_refused(self):
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli.run_eval(self._run_args(model="some-model.json"))

    def test_model_override_overlays_a_temp_copy_and_leaves_the_real_corpus_untouched(self):
        manifest = {"kind": "negative", "expectedOutcome": "fail", "expectedFailureStage": "ai-model-schema"}
        schema_report = {"scenarios": [{"scenarioId": "trusted-source-path-traversal", "status": "passed", "failures": []}]}
        captured_roots = []

        def _fake_overlay(scenario_root, scenario_id, model_path, overlay_root):
            captured_roots.append(overlay_root)
            return overlay_root

        with tempfile.TemporaryDirectory() as tmp:
            with patch("npdev_cli._ai_build_root", return_value=Path(tmp)), \
                 patch("npdev_cli._eval_read_manifest", return_value=manifest), \
                 patch("npdev_cli._eval_overlay_scenario", side_effect=_fake_overlay) as mock_overlay, \
                 patch("npdev_cli._eval_run_schema_validation", return_value=schema_report):
                report = npdev_cli.run_eval(self._run_args(scenario="trusted-source-path-traversal", model="candidate.json"))

        mock_overlay.assert_called_once()
        self.assertEqual(1, len(captured_roots))
        self.assertEqual("PASS", report["scenarios"][0]["verdict"])


class EvalCompareTest(unittest.TestCase):
    def _write_results(self, directory: Path, run_id: str, scenarios: list[dict]) -> None:
        import json
        run_dir = directory / "evals" / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "results.json").write_text(
            json.dumps({"runId": run_id, "scenarios": scenarios}), encoding="utf-8")

    def test_regression_and_improvement_and_only_in_one_side_are_all_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            build_root = Path(tmp)
            self._write_results(build_root, "run-a", [
                {"scenario": "sc-regressed", "verdict": "PASS"},
                {"scenario": "sc-improved", "verdict": "FAIL"},
                {"scenario": "sc-unchanged", "verdict": "PASS"},
                {"scenario": "sc-only-a", "verdict": "PASS"},
            ])
            self._write_results(build_root, "run-b", [
                {"scenario": "sc-regressed", "verdict": "FAIL"},
                {"scenario": "sc-improved", "verdict": "PASS"},
                {"scenario": "sc-unchanged", "verdict": "PASS"},
                {"scenario": "sc-only-b", "verdict": "PASS"},
            ])
            with patch("npdev_cli._ai_build_root", return_value=build_root):
                report = npdev_cli.run_eval_compare(argparse.Namespace(run_a="run-a", run_b="run-b"))

        self.assertEqual(["sc-regressed"], report["regressed"])
        self.assertEqual(["sc-improved"], report["improved"])
        self.assertEqual(1, report["unchanged"])
        self.assertEqual(["sc-only-a"], report["onlyInA"])
        self.assertEqual(["sc-only-b"], report["onlyInB"])
        self.assertFalse(report["ok"])

    def test_no_regressions_is_ok(self):
        with tempfile.TemporaryDirectory() as tmp:
            build_root = Path(tmp)
            self._write_results(build_root, "run-a", [{"scenario": "sc", "verdict": "FAIL"}])
            self._write_results(build_root, "run-b", [{"scenario": "sc", "verdict": "PASS"}])
            with patch("npdev_cli._ai_build_root", return_value=build_root):
                report = npdev_cli.run_eval_compare(argparse.Namespace(run_a="run-a", run_b="run-b"))

        self.assertTrue(report["ok"])
        self.assertEqual(["sc"], report["improved"])


if __name__ == "__main__":
    unittest.main()
