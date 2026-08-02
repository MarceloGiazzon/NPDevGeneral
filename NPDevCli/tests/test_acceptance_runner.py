"""Tests for LC-D2's acceptance-scenario runner (REG-110's named residual: "no automated
regression test exists for run_acceptance/run_closed_loop/the JSONPath-lite evaluator ... a
future session should add one"). Stdlib-only (unittest), matching this repo's convention for
quality-gate scripts -- mirrors test_dsl_v2_migration.py's own style. Run with:
    python -m unittest NPDevCli.tests.test_acceptance_runner -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import ANY, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
from npdev_cli import _eval_assertion, _resolve_json_path, _run_one_scenario  # noqa: E402


class ResolveJsonPathTest(unittest.TestCase):
    def test_status_literal_returns_scalar_root(self):
        self.assertEqual([200], _resolve_json_path(200, "$status"))

    def test_status_literal_ignores_a_dict_or_list_root(self):
        # $status is only meaningful against the raw HTTP status int; a dict/list root means the
        # caller passed the wrong root for this path (see _run_one_scenario's own root selection).
        self.assertEqual([], _resolve_json_path({"status": 200}, "$status"))

    def test_dotted_field_path(self):
        self.assertEqual([2], _resolve_json_path({"totalElements": 2}, "$.totalElements"))

    def test_missing_field_yields_empty_list_not_an_error(self):
        self.assertEqual([], _resolve_json_path({"totalElements": 2}, "$.nope"))

    def test_wildcard_over_array_then_field(self):
        root = {"content": [{"name": "A"}, {"name": "B"}]}
        self.assertEqual(["A", "B"], _resolve_json_path(root, "$.content[*].name"))

    def test_numeric_index_into_array(self):
        root = {"content": [{"name": "A"}, {"name": "B"}]}
        self.assertEqual(["B"], _resolve_json_path(root, "$.content[1].name"))

    def test_index_out_of_range_yields_empty_list(self):
        root = {"content": [{"name": "A"}]}
        self.assertEqual([], _resolve_json_path(root, "$.content[5].name"))

    def test_path_not_starting_with_dollar_is_rejected(self):
        with self.assertRaises(ValueError):
            _resolve_json_path({}, "totalElements")


class EvalAssertionTest(unittest.TestCase):
    def test_equals_scalar_passes(self):
        result = _eval_assertion({"totalElements": 2}, {"path": "$.totalElements", "equals": 2})
        self.assertTrue(result["passed"])
        self.assertEqual(2, result["actual"])

    def test_equals_scalar_fails_names_the_actual_value(self):
        # REG-110's own live-verification called out that scenario 02 (deliberately wrong) must
        # report the ACTUAL value, not just "failed" -- this is that guarantee, pinned by a test.
        result = _eval_assertion({"totalElements": 1}, {"path": "$.totalElements", "equals": 999})
        self.assertFalse(result["passed"])
        self.assertEqual(1, result["actual"])
        self.assertEqual(999, result["expected"])

    def test_all_equal_passes_when_every_match_agrees(self):
        root = {"content": [{"name": "X"}, {"name": "X"}]}
        result = _eval_assertion(root, {"path": "$.content[*].name", "allEqual": "X"})
        self.assertTrue(result["passed"])

    def test_all_equal_fails_when_any_match_disagrees(self):
        root = {"content": [{"name": "X"}, {"name": "Y"}]}
        result = _eval_assertion(root, {"path": "$.content[*].name", "allEqual": "X"})
        self.assertFalse(result["passed"])

    def test_all_equal_fails_on_zero_matches_rather_than_vacuously_passing(self):
        # An empty result set trivially satisfies "every element equals X" under Python's all() --
        # _eval_assertion must refuse that vacuous pass, or a broken filter that returns nothing
        # would silently look like a passing assertion.
        result = _eval_assertion({"content": []}, {"path": "$.content[*].name", "allEqual": "X"})
        self.assertFalse(result["passed"])

    def test_count_operator(self):
        root = {"content": [{"name": "A"}, {"name": "B"}, {"name": "C"}]}
        self.assertTrue(_eval_assertion(root, {"path": "$.content[*].name", "count": 3})["passed"])
        self.assertFalse(_eval_assertion(root, {"path": "$.content[*].name", "count": 2})["passed"])

    def test_less_than_and_greater_than(self):
        root = {"totalElements": 2}
        self.assertTrue(_eval_assertion(root, {"path": "$.totalElements", "lessThan": 3})["passed"])
        self.assertFalse(_eval_assertion(root, {"path": "$.totalElements", "lessThan": 2})["passed"])
        self.assertTrue(_eval_assertion(root, {"path": "$.totalElements", "greaterThan": 1})["passed"])
        self.assertFalse(_eval_assertion(root, {"path": "$.totalElements", "greaterThan": 2})["passed"])

    def test_unrecognized_operator_fails_with_an_explanation_not_a_crash(self):
        result = _eval_assertion({"x": 1}, {"path": "$.x", "notARealOperator": 1})
        self.assertFalse(result["passed"])
        self.assertIsNotNone(result["error"])


class _FakeHttpResponse:
    def __init__(self, status: int, body: bytes):
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


def _urlopen_sequence(*responses):
    """Returns a side_effect callable yielding each response/exception in order, one per call --
    models the "seed rows one by one, then run the `when` request" sequence _run_one_scenario
    issues, without a real HTTP server."""
    iterator = iter(responses)

    def _side_effect(request, timeout=None):  # noqa: ARG001 - signature must match urlopen's
        outcome = next(iterator)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    return _side_effect


class RunOneScenarioTest(unittest.TestCase):
    def _scenario(self, **overrides):
        base = {
            "schemaVersion": "npdev-acceptance-scenario.v1",
            "name": "test scenario",
            "approved": True,
            "given": [{"path": "/api/concepts/users", "rows": [{"name": "Alice", "email": "a@x.test"}]}],
            "when": {"method": "GET", "path": "/api/concepts/users?where=name:eq:Alice"},
            "then": [{"path": "$.totalElements", "equals": 1}],
        }
        base.update(overrides)
        return base

    @patch("urllib.request.urlopen")
    def test_passing_scenario_seeds_then_asserts(self, mock_urlopen):
        seed_response = _FakeHttpResponse(201, b'{"id": "1"}')
        when_response = _FakeHttpResponse(
            200, b'{"totalElements": 1, "content": [{"name": "Alice"}]}')
        mock_urlopen.side_effect = _urlopen_sequence(seed_response, when_response)

        result = _run_one_scenario("http://127.0.0.1:8180", self._scenario(), "01-fixture.scenario.json")

        self.assertEqual("PASS", result["outcome"])
        self.assertIsNone(result["error"])
        self.assertEqual(2, mock_urlopen.call_count, "one seed POST + one when call")

    @patch("urllib.request.urlopen")
    def test_failing_assertion_reports_outcome_fail_with_actual_value(self, mock_urlopen):
        seed_response = _FakeHttpResponse(201, b'{"id": "1"}')
        when_response = _FakeHttpResponse(200, b'{"totalElements": 1}')
        mock_urlopen.side_effect = _urlopen_sequence(seed_response, when_response)

        scenario = self._scenario(then=[{"path": "$.totalElements", "equals": 999}])
        result = _run_one_scenario("http://127.0.0.1:8180", scenario, "02-fixture.scenario.json")

        self.assertEqual("FAIL", result["outcome"])
        self.assertEqual(1, result["assertions"][0]["actual"])
        self.assertEqual(999, result["assertions"][0]["expected"])

    @patch("urllib.request.urlopen")
    def test_unapproved_scenario_still_runs_and_is_reported(self, mock_urlopen):
        # D2's DoD: an Author-proposed scenario is visible, just excluded from the pass TOTAL --
        # that exclusion is run_acceptance's job (summary math), not _run_one_scenario's: this
        # scenario must still actually execute and report a real outcome.
        seed_response = _FakeHttpResponse(201, b'{"id": "1"}')
        when_response = _FakeHttpResponse(200, b'{"totalElements": 1}')
        mock_urlopen.side_effect = _urlopen_sequence(seed_response, when_response)

        scenario = self._scenario(approved=False)
        result = _run_one_scenario("http://127.0.0.1:8180", scenario, "04-fixture.scenario.json")

        self.assertFalse(result["approved"])
        self.assertEqual("PASS", result["outcome"])

    @patch("urllib.request.urlopen")
    def test_seed_failure_short_circuits_before_the_when_call(self, mock_urlopen):
        import urllib.error

        mock_urlopen.side_effect = _urlopen_sequence(
            urllib.error.HTTPError("url", 500, "boom", {}, None))

        result = _run_one_scenario("http://127.0.0.1:8180", self._scenario(), "01-fixture.scenario.json")

        self.assertEqual("ERROR", result["outcome"])
        self.assertIn("seed POST", result["error"])
        self.assertEqual(1, mock_urlopen.call_count, "the when call must not run after a seed failure")


class RunAcceptanceSummaryTest(unittest.TestCase):
    """run_acceptance's own arithmetic (approved-only pass total, excludedUnapproved count) --
    D2's DoD line item, enforced in the runner rather than by convention. Boots via a patched
    run_app so this stays a pure unit test of the summary math, not a live boot."""

    @staticmethod
    def _acceptance_args(scenarios: str, base_url: str | None = None) -> "argparse.Namespace":
        import argparse

        return argparse.Namespace(
            model="unused", config="unused", output="unused",
            scenarios=scenarios, api_key="dev-key",
            port=8180, timeout=420, profile="dev",
            require_db_definition=False, baseline_model=None, keep_running=False,
            base_url=base_url,
        )

    def _scenario_file(self, tmp_path: Path, filename: str, **overrides) -> None:
        import json as _json

        base = {
            "schemaVersion": "npdev-acceptance-scenario.v1",
            "name": filename,
            "approved": True,
            "given": [],
            "when": {"method": "GET", "path": "/x"},
            "then": [{"path": "$status", "equals": 200}],
        }
        base.update(overrides)
        (tmp_path / filename).write_text(_json.dumps(base), encoding="utf-8")

    def test_unapproved_scenarios_are_excluded_from_the_pass_total(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            self._scenario_file(tmp_path, "01-approved-pass.scenario.json", approved=True)
            self._scenario_file(tmp_path, "02-unapproved.scenario.json", approved=False)

            fake_boot = {"ok": True, "baseUrl": "http://127.0.0.1:8180"}
            fake_results = [
                {"name": "01", "file": "01-approved-pass.scenario.json", "approved": True,
                 "outcome": "PASS", "assertions": [], "error": None},
                {"name": "02", "file": "02-unapproved.scenario.json", "approved": False,
                 "outcome": "PASS", "assertions": [], "error": None},
            ]
            args = self._acceptance_args(str(tmp_path))
            with patch("npdev_cli.run_app", return_value=fake_boot), \
                 patch("npdev_cli._run_one_scenario", side_effect=fake_results):
                report = npdev_cli.run_acceptance(args)

        self.assertEqual(2, report["summary"]["total"])
        self.assertEqual(1, report["summary"]["approvedTotal"])
        self.assertEqual(1, report["summary"]["passed"])
        self.assertEqual(0, report["summary"]["failed"])
        self.assertEqual(1, report["summary"]["excludedUnapproved"])
        self.assertTrue(report["ok"], "one approved+passing scenario, one excluded unapproved -> overall ok")

    def test_a_failing_approved_scenario_makes_the_run_not_ok(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            self._scenario_file(tmp_path, "01-approved-fail.scenario.json", approved=True)

            fake_boot = {"ok": True, "baseUrl": "http://127.0.0.1:8180"}
            fake_results = [
                {"name": "01", "file": "01-approved-fail.scenario.json", "approved": True,
                 "outcome": "FAIL", "assertions": [], "error": None},
            ]
            args = self._acceptance_args(str(tmp_path))
            with patch("npdev_cli.run_app", return_value=fake_boot), \
                 patch("npdev_cli._run_one_scenario", side_effect=fake_results):
                report = npdev_cli.run_acceptance(args)

        self.assertEqual(1, report["summary"]["failed"])
        self.assertFalse(report["ok"])

    def test_boot_failure_short_circuits_before_any_scenario_runs(self):
        fake_boot = {"ok": False, "error": "PORT_IN_USE"}
        args = self._acceptance_args(".")
        with patch("npdev_cli.run_app", return_value=fake_boot), \
             patch("npdev_cli._run_one_scenario") as mock_run_one:
            report = npdev_cli.run_acceptance(args)

        mock_run_one.assert_not_called()
        self.assertFalse(report["ok"])
        self.assertEqual([], report["scenarios"])

    def test_base_url_skips_the_boot_entirely(self):
        # Move 14 Phase D item D1: a caller that already has an app running (the T1 fast gate,
        # reusing its own canary boot) must not pay for a second generate+build+boot cycle.
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            self._scenario_file(tmp_path, "01-approved-pass.scenario.json", approved=True)

            fake_result = {"name": "01", "file": "01-approved-pass.scenario.json", "approved": True,
                            "outcome": "PASS", "assertions": [], "error": None}
            args = self._acceptance_args(str(tmp_path), base_url="http://127.0.0.1:8103")
            with patch("npdev_cli.run_app") as mock_run_app, \
                 patch("npdev_cli._run_one_scenario", return_value=fake_result) as mock_run_one:
                report = npdev_cli.run_acceptance(args)

        mock_run_app.assert_not_called()
        mock_run_one.assert_called_once_with("http://127.0.0.1:8103", ANY,
                                              "01-approved-pass.scenario.json", "dev-key")
        self.assertEqual("http://127.0.0.1:8103", report["baseUrl"])
        self.assertTrue(report["ok"])

    def test_missing_model_config_output_without_base_url_is_a_clean_error(self):
        args = self._acceptance_args(".")
        args.model = None
        args.config = None
        args.output = None
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli.run_acceptance(args)


if __name__ == "__main__":
    unittest.main()
