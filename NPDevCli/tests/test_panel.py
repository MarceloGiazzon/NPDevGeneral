"""Tests for `npdev verify --panel` (VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 Phase 1).

These are unit + filesystem-lite: kanban_column is exercised exhaustively here because that is the
exact decision function the plan insists must be pure and separately tested (S2.2 -- the lesson from
SchemaVerifyMain.exitCodeFor is that a decision buried inside a long producer gets zero coverage and
ships wrong). build_repo_panel reads the repo's own three ledger files, so those tests assert
structural guarantees that must hold regardless of which check happens to be missing a state row.

SYNTAX RULE: these run from the repo, so the surrounding gate checks (some of which parse this file
looking for `check-*.py` naming) are untouched; nothing here is a check script.
"""

from __future__ import annotations

import json
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_panel  # type: ignore

REPO_ROOT = Path(__file__).resolve().parents[2]


def _item(**overrides) -> dict:
    base = {
        "id": "x", "name": "x", "description": "",
        "category": "gate", "tier": None, "command": None, "runnable": False,
        "lastRun": None, "typicalDurationSeconds": None,
    }
    base.update(overrides)
    return base


def _ran(**overrides) -> dict:
    base = {"startedAt": "2026-08-01T00:00:00+00:00", "result": "passed",
            "commit": None, "reportPath": None, "logPath": None}
    base.update(overrides)
    return base


class KanbanColumnTest(unittest.TestCase):
    def test_never_run(self):
        self.assertEqual(npdev_panel.kanban_column(_item(), datetime.now(timezone.utc)), "never-run")

    def test_failing(self):
        item = _item(lastRun=_ran(result="failed"))
        self.assertEqual(npdev_panel.kanban_column(item, datetime.now(timezone.utc)), "failing")

    def test_stale(self):
        now = datetime(2026, 8, 20, 12, 0, 0, tzinfo=timezone.utc)
        # "1-move" is 7 days; startedAt 8 days ago clearly exceeds it.
        started = (now - timedelta(days=8)).isoformat()
        item = _item(maxStaleness="1-move", lastRun=_ran(startedAt=started))
        self.assertEqual(npdev_panel.kanban_column(item, now), "stale")

    def test_healthy_passed(self):
        now = datetime(2026, 8, 20, 12, 0, 0, tzinfo=timezone.utc)
        started = (now - timedelta(days=1)).isoformat()
        item = _item(maxStaleness="1-move", lastRun=_ran(startedAt=started))
        self.assertEqual(npdev_panel.kanban_column(item, now), "healthy")

    def test_healthy_no_staleness_constraint(self):
        item = _item(maxStaleness=None, lastRun=_ran())  # lastRun from 2026-08-01, no threshold
        self.assertEqual(npdev_panel.kanban_column(item, datetime.now(timezone.utc)), "healthy")

    def test_skipped_is_not_pass_but_also_not_failing(self):
        # S1.1: skipped/not-applicable deliberately collapse to HEALTHY (never mapped to passed),
        # exactly as the S1.2 derived-state table says.
        item = _item(lastRun=_ran(result="skipped"))
        self.assertEqual(npdev_panel.kanban_column(item, datetime.now(timezone.utc)), "healthy")

    def test_stale_threshold_exactly_met_is_healthy(self):
        # S1.2 rule is "EXCEEDS it" -- strictly greater. Exactly at the boundary is NOT stale.
        now = datetime(2026, 8, 20, 12, 0, 0, tzinfo=timezone.utc)
        started = (now - timedelta(days=7)).isoformat()  # exactly 1-move / 7 days
        item = _item(maxStaleness="1-move", lastRun=_ran(startedAt=started))
        self.assertEqual(npdev_panel.kanban_column(item, now), "healthy")

    def test_failed_wins_over_stale(self):
        # Any failed run is FAILING regardless of how old it is.
        now = datetime(2026, 8, 20, 12, 0, 0, tzinfo=timezone.utc)
        started = (now - timedelta(days=30)).isoformat()
        item = _item(maxStaleness="1-move", lastRun=_ran(startedAt=started, result="failed"))
        self.assertEqual(npdev_panel.kanban_column(item, now), "failing")


class BuildRepoPanelTest(unittest.TestCase):
    def test_document_has_all_cadence_items(self):
        doc = npdev_panel.build_repo_panel(REPO_ROOT)
        self.assertEqual(doc["schemaVersion"], "npdev-verification-panel.v1")
        item_ids = {i["id"] for i in doc["items"]}
        cadence_ids = set(npdev_panel._load_cadence())
        # Every declared cadence gate appears; nothing drops a check that has no state row.
        self.assertEqual(item_ids, cadence_ids)
        # ids are unique across the whole document.
        ids = [i["id"] for i in doc["items"]]
        self.assertEqual(len(ids), len(set(ids)))

    def test_items_with_no_state_row_have_null_last_run(self):
        doc = npdev_panel.build_repo_panel(REPO_ROOT)
        state = npdev_panel._load_state()
        for item in doc["items"]:
            if item["id"] in state:
                self.assertIsNotNone(item["lastRun"])
            else:
                # The never-run surface is the point: a declared check with no state must not
                # vanish, and must not invent a run.
                self.assertIsNone(item["lastRun"])

    def test_last_run_result_is_propagated_from_state(self):
        doc = npdev_panel.build_repo_panel(REPO_ROOT)
        state = npdev_panel._load_state()
        for item in doc["items"]:
            record = state.get(item["id"])
            if record is not None:
                self.assertEqual(item["lastRun"]["result"], record["result"])
                self.assertEqual(item["lastRun"]["startedAt"], record["lastRun"])

    def test_categories_are_not_degenerate(self):
        # A1: the category column must carry information -- at least the four derived branches
        # appear in the live document (gate gateways, the CI workflow, the canary test-suite, and
        # plain check-scripts).
        doc = npdev_panel.build_repo_panel(REPO_ROOT)
        categories = {i["category"] for i in doc["items"]}
        for expected in ("gate", "check-script", "workflow", "test-suite"):
            self.assertIn(expected, categories)

    def test_subject_describes_the_repo(self):
        doc = npdev_panel.build_repo_panel(REPO_ROOT)
        self.assertEqual(doc["subject"]["kind"], "npdev-repo")
        self.assertEqual(doc["subject"]["name"], REPO_ROOT.name)
        self.assertTrue(doc["subject"]["commit"])

    def test_typical_duration_is_null_without_history(self):
        # Before Phase 2 supplies history, no synthesized durations.
        self.assertIsNone(npdev_panel._typical_duration(None))
        self.assertIsNone(npdev_panel._typical_duration({"history": []}))

    def test_typical_duration_from_history(self):
        entry = {"history": [
            {"lastRun": "x", "result": "passed", "durationSeconds": 700},
            {"lastRun": "x", "result": "passed", "durationSeconds": 800},
            {"lastRun": "x", "result": "passed", "durationSeconds": 900},
            {"lastRun": "x", "result": "passed", "durationSeconds": None},
        ]}
        stat = npdev_panel._typical_duration(entry)
        self.assertIsNotNone(stat)
        self.assertEqual(stat["sampleCount"], 3)
        self.assertEqual(stat["p10"], 700.0)
        self.assertEqual(stat["p50"], 800.0)
        self.assertEqual(stat["p90"], 900.0)

    def test_typical_duration_under_three_samples_is_null(self):
        entry = {"history": [
            {"lastRun": "x", "result": "passed", "durationSeconds": 100},
            {"lastRun": "x", "result": "passed", "durationSeconds": 200},
        ]}
        self.assertIsNone(npdev_panel._typical_duration(entry))


class CategoryDerivationTest(unittest.TestCase):
    """A1: _category_for derives the category mechanically from invokedBy -- one example of each
    of the four branches, including the manual-runbook case."""

    def test_ci_workflow_entry_is_workflow(self):
        category = npdev_panel._category_for(
            {"id": "scale-proof-ladder",
             "invokedBy": ".github/workflows/nightly-scale-ladder.yml (schedule: '0 4 * * *')"})
        self.assertEqual(category, "workflow")

    def test_run_all_gates_entry_is_gate(self):
        category = npdev_panel._category_for(
            {"id": "aiKnowledge", "invokedBy": "scripts/quality/run-all-gates.ps1 (gate: aiKnowledge)"})
        self.assertEqual(category, "gate")

    def test_orchestration_entry_is_gate(self):
        category = npdev_panel._category_for(
            {"id": "beta-release-evidence",
             "invokedBy": "scripts/quality/run-beta-release-evidence-orchestration.ps1 / "
                          "run-beta-release-gate.ps1 -GenerateReports"})
        self.assertEqual(category, "gate")

    def test_canary_boots_an_app_is_test_suite(self):
        category = npdev_panel._category_for(
            {"id": "canary-build-boot-smoke", "invokedBy": "scripts/quality/run-fast-gate.ps1"})
        self.assertEqual(category, "test-suite")

    def test_manual_runbook_entry_is_check_script(self):
        category = npdev_panel._category_for(
            {"id": "rebuild-calibration",
             "invokedBy": "manual-runbook: time a full scripts/appgen/Rebuild-And-Restage.ps1 run"})
        self.assertEqual(category, "check-script")

    def test_direct_check_py_entry_is_check_script(self):
        category = npdev_panel._category_for(
            {"id": "check-schema-mirror-consistency",
             "invokedBy": "scripts/quality/check-schema-mirror-consistency.py"})
        self.assertEqual(category, "check-script")


if __name__ == "__main__":
    unittest.main()