"""FORM P1-T9: `cadence_state.py audit` -- the report-only earn-your-keep list, and the dates `record` now carries."""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
QUALITY = REPO_ROOT / "scripts" / "quality"
sys.path.insert(0, str(REPO_ROOT / "scripts" / "ai"))
spec = importlib.util.spec_from_file_location("cadence_state_under_test", QUALITY / "cadence_state.py")
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

NOW = datetime.now(timezone.utc)


def _iso(days_ago):
    return (NOW - timedelta(days=days_ago)).isoformat()


def _check(check_id, level="standard", cost=300, protects="correctness"):
    return {"id": check_id, "tier": "T1", "level": level, "costSeconds": cost, "protects": protects}


class AuditTest(unittest.TestCase):
    def _audit(self, checks, state, **kw):
        args = argparse.Namespace(days=kw.get("days", 120), min_cost=kw.get("min_cost", 60), json=True)
        out = io.StringIO()
        with mock.patch.object(cs, "load_cadence", return_value={c["id"]: c for c in checks}), \
                mock.patch.object(cs, "load_state", return_value=state), contextlib.redirect_stdout(out):
            cs.cmd_audit(args)
        import json
        return {r["id"]: r for r in json.loads(out.getvalue())["rows"]}, json.loads(out.getvalue())["noData"]

    def test_expensive_quiet_check_is_a_candidate_one_level_down(self):
        rows, _ = self._audit([_check("slow")], {"slow": {"firstRecordedAt": _iso(200), "lastRun": _iso(1), "history": []}})
        self.assertTrue(rows["slow"]["demotionCandidate"])
        self.assertEqual(rows["slow"]["suggestedLevel"], "extended")

    def test_a_recent_failure_resets_the_clock(self):
        rows, _ = self._audit([_check("slow")], {"slow": {"firstRecordedAt": _iso(300), "lastFailedAt": _iso(10), "history": []}})
        self.assertFalse(rows["slow"]["demotionCandidate"])

    def test_cheap_checks_are_never_candidates(self):
        rows, _ = self._audit([_check("cheap", cost=10)], {"cheap": {"firstRecordedAt": _iso(400), "history": []}})
        self.assertFalse(rows["cheap"]["demotionCandidate"])

    def test_a_security_check_at_standard_is_never_demoted(self):
        rows, _ = self._audit([_check("sec", protects="security")], {"sec": {"firstRecordedAt": _iso(400), "history": []}})
        self.assertFalse(rows["sec"]["demotionCandidate"])

    def test_a_check_with_no_recorded_run_is_reported_as_no_data_not_as_quiet(self):
        rows, no_data = self._audit([_check("never")], {})
        self.assertEqual(no_data, ["never"])
        self.assertNotIn("never", rows)

    def test_record_carries_first_and_last_failure_dates_forward(self):
        checks = {"x": {"id": "x", "tier": "T1"}}
        state = {}
        with mock.patch.object(cs, "load_cadence", return_value=checks), \
                mock.patch.object(cs, "load_state", side_effect=lambda: dict(state)), \
                mock.patch.object(cs, "write_state", side_effect=lambda s: state.update(s)), \
                mock.patch.object(cs, "current_commit", return_value="abc"), contextlib.redirect_stdout(io.StringIO()):
            ns = lambda result: argparse.Namespace(id="x", tier="T1", result=result, commit="", duration_seconds=None)
            cs.cmd_record(ns("failed"))
            first = state["x"]["firstRecordedAt"]
            failed_at = state["x"]["lastFailedAt"]
            cs.cmd_record(ns("passed"))
        self.assertEqual(state["x"]["firstRecordedAt"], first)
        self.assertEqual(state["x"]["lastFailedAt"], failed_at, "a later pass must not erase the last failure")


if __name__ == "__main__":
    unittest.main()
