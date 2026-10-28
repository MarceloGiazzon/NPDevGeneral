"""Tests for cadence_state.py's Phase 2 duration + bounded-history record behaviour
(VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27 Phase 2).

These exercise cmd_record against a THROWAWAY cadence + state in a temp directory, so they never
touch the repo's real verification-cadence-state.json. They protect the four Phase 2 acceptance
criteria: (1) a --duration-seconds value is stored AND one history entry is appended, (2) omitting
the flag stores null and still appends history, (3) a pre-existing row that has neither key keeps
working untouched, and (4) history never exceeds 20 entries.

Strictly a unit test -- nothing here is a check-*.py script and nothing mutates the repo.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "quality"))

import cadence_state  # type: ignore


def _minimal_cadence() -> dict:
    return {
        "schemaVersion": "npdev-verification-cadence.v1",
        "checks": [
            {"id": "mycheck", "tier": "T0", "maxStaleness": "every-run",
             "invokedBy": "scripts/quality/run-fast-gate.ps1", "description": "test check"},
        ],
    }


def _namespaced(**kwargs) -> argparse.Namespace:
    base = {"id": "mycheck", "tier": "T0", "result": "passed", "commit": "deadbeef",
            "duration_seconds": None}
    base.update(kwargs)
    return argparse.Namespace(**base)


class CadenceRecordDurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.cadence_file = Path(self._tmp.name) / "cadence.json"
        self.state_file = Path(self._tmp.name) / "state.json"
        self.cadence_file.write_text(json.dumps(_minimal_cadence()), encoding="utf-8")
        # Point the module at the temp files, never the repo's real ledger.
        self._orig_cadence = cadence_state.CADENCE_PATH
        self._orig_state = cadence_state.STATE_PATH
        self._orig_legacy = cadence_state.LEGACY_STATE_PATH
        self.legacy_file = Path(self._tmp.name) / "legacy" / "state.json"
        cadence_state.CADENCE_PATH = self.cadence_file
        cadence_state.STATE_PATH = self.state_file
        cadence_state.LEGACY_STATE_PATH = self.legacy_file

    def tearDown(self) -> None:
        cadence_state.CADENCE_PATH = self._orig_cadence
        cadence_state.STATE_PATH = self._orig_state
        cadence_state.LEGACY_STATE_PATH = self._orig_legacy

    def test_legacy_state_is_read_then_moved_by_the_next_record(self):
        """QUAL-62: a checkout whose runs still sit in scripts/reports/out keeps them, and the
        next record moves them to the Build-root file."""
        self.legacy_file.parent.mkdir(parents=True)
        self.legacy_file.write_text(json.dumps({
            "schemaVersion": "npdev-verification-cadence-state.v1", "generatedAt": "x",
            "runs": [{"id": "other", "tier": "T2", "lastRun": "2026-10-27T00:00:00+00:00",
                      "result": "passed", "commit": "old"}],
        }), encoding="utf-8")
        self.assertIn("other", cadence_state.load_state())
        self.assertEqual(cadence_state.cmd_record(_namespaced()), 0)
        self.assertEqual({"mycheck", "other"}, {r["id"] for r in self._read_state()["runs"]})
        self.assertFalse(self.legacy_file.exists())

    def test_absent_state_file_is_named_not_just_reported_as_never_run(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = cadence_state.cmd_report(argparse.Namespace(tier="T0", json=False, report_out=""))
        self.assertEqual(code, 1)
        self.assertIn("STATE FILE ABSENT", out.getvalue())
        self.assertIn("mycheck (T0): never-run", out.getvalue())

    def _read_state(self) -> dict:
        return json.loads(self.state_file.read_text(encoding="utf-8"))

    def test_record_with_duration_stores_it_and_appends_history(self):
        self.assertEqual(cadence_state.cmd_record(_namespaced(duration_seconds=42)), 0)
        record = self._read_state()["runs"][0]
        self.assertEqual(record["durationSeconds"], 42)
        self.assertEqual(len(record["history"]), 1)
        self.assertEqual(record["history"][0]["durationSeconds"], 42)
        self.assertEqual(record["history"][0]["result"], "passed")

    def test_record_without_duration_stores_null_and_appends_history(self):
        self.assertEqual(cadence_state.cmd_record(_namespaced()), 0)
        record = self._read_state()["runs"][0]
        self.assertIsNone(record["durationSeconds"])
        self.assertEqual(len(record["history"]), 1)
        self.assertIsNone(record["history"][0]["durationSeconds"])

    def test_preexisting_row_without_new_keys_stays_working(self):
        # A state file written BEFORE Phase 2 has rows with neither history nor durationSeconds.
        self.state_file.write_text(json.dumps({
            "schemaVersion": "npdev-verification-cadence-state.v1", "generatedAt": "x",
            "runs": [{"id": "mycheck", "tier": "T0", "lastRun": "2026-08-01T00:00:00+00:00",
                      "result": "passed", "commit": "old"}],
        }), encoding="utf-8")
        # Reading a pre-Phase-2 row -- no history, no durationSeconds -- must not crash (S3.4 #3).
        self.assertEqual(cadence_state.cmd_record(_namespaced()), 0)
        record = next(r for r in self._read_state()["runs"] if r["id"] == "mycheck")
        self.assertEqual(len(record["history"]), 1)  # a fresh row, plus the new run
        self.assertEqual(record["history"][0]["result"], "passed")
        self.assertIsNone(record["durationSeconds"])

    def test_history_is_bounded_at_20(self):
        for _ in range(25):
            self.assertEqual(cadence_state.cmd_record(_namespaced()), 0)
        record = self._read_state()["runs"][0]
        self.assertLessEqual(len(record["history"]), 20)
        self.assertEqual(len(record["history"]), 20)
        # Every history entry carries exactly the three recorded fields (never a synthesized commit).
        for entry in record["history"]:
            self.assertEqual(set(entry.keys()), {"lastRun", "result", "durationSeconds"})


if __name__ == "__main__":
    unittest.main()