"""S16 (NPDEV_MEGA_ROADMAP.md, Track B): blast radius -- `npdev impact --app` joins the migration
plan with the S14 provenance index so each affected table lists the generated classes / schema
migration / frontend routes it would change or break, destructive first.

Stdlib-only (unittest), same convention as test_impact_cli.py -- the join itself (`_artifact_impact`
and `_load_app_provenance_index`) is the unit under test; the classifier legs stay stubbed exactly
as test_impact_cli.py argues. Run with:
    python -m unittest NPDevCli.tests.test_blast_radius -v
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]

MODEL_JSON = {
    "dslVersion": "1.0.0",
    "version": "1.0",
    "namespace": "demo",
    "concepts": [
        {"name": "Order", "fields": [{"name": "id", "type": "string"}, {"name": "status", "type": "string"}]},
    ],
}

CLASSIFY_REPORT_WITH_ITEMS = {
    "classification": "MANUAL_REVIEW",
    "items": [
        {"kind": "DROP_COLUMN", "table": "orders", "column": "status", "destructive": True,
         "description": "Column 'status' will be dropped; existing data in this column will be lost."},
        {"kind": "ADD_COLUMN", "table": "orders", "column": "tracking_code", "destructive": False,
         "description": "New nullable column 'tracking_code' on table 'orders'."},
    ],
}

PROVENANCE_INDEX = {
    "schemaVersion": "npdev-provenance-index.v1",
    "specNodes": {
        "Order": {
            "table": "orders",
            "artifacts": [
                {"type": "generated-class", "path": "src/main/java/com/npdev/generated/entities/Order.java",
                 "digest": "sha256:1111"},
                {"type": "frontend-route", "path": "src/main/resources/static/npdev-business-ui/generated-ui-manifest.json",
                 "digest": "sha256:2222", "note": "/orders"},
            ],
        }
    },
}


def _write_json(path: Path, content: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content), encoding="utf-8")


class BlastRadiusJoinTest(unittest.TestCase):
    def test_destructive_tables_come_first_and_carry_their_artifacts(self):
        impact = npdev_cli._artifact_impact(CLASSIFY_REPORT_WITH_ITEMS, PROVENANCE_INDEX)

        self.assertTrue(impact["joined"])
        self.assertEqual(1, impact["destructiveCount"])
        self.assertEqual(1, len(impact["tables"]))
        table = impact["tables"][0]
        self.assertEqual("orders", table["table"])
        self.assertEqual("Order", table["concept"])
        self.assertTrue(table["destructive"])
        # Two plan items, destructive first (stable order: the dict is built in list order).
        self.assertEqual(["DROP_COLUMN", "ADD_COLUMN"], [it["kind"] for it in table["items"]])
        self.assertTrue(table["items"][0]["destructive"])
        # The concept's emitted artifacts are joined in, verbatim paths preserved.
        self.assertEqual(
            ["src/main/java/com/npdev/generated/entities/Order.java",
             "src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"],
            [a["path"] for a in table["affected"]],
        )

    def test_unknown_table_still_appears_with_empty_artifacts(self):
        plan = {"items": [
            {"kind": "DROP_TABLE", "table": "ghost_table", "destructive": True, "description": "x"},
        ]}
        impact = npdev_cli._artifact_impact(plan, PROVENANCE_INDEX)

        self.assertEqual(1, len(impact["tables"]))
        table = impact["tables"][0]
        self.assertEqual("ghost_table", table["table"])
        self.assertIsNone(table["concept"], "a table the index does not know gets no fabricated concept")
        self.assertEqual([], table["affected"], "no index entry means no artifacts listed")
        self.assertTrue(table["destructive"])

    def test_empty_plan_reports_not_joined(self):
        impact = npdev_cli._artifact_impact({"items": []}, PROVENANCE_INDEX)
        self.assertFalse(impact["joined"])
        self.assertEqual([], impact["tables"])

    def test_missing_items_key_reports_not_joined(self):
        impact = npdev_cli._artifact_impact({"classification": "SAFE_ADDITIVE"}, PROVENANCE_INDEX)
        self.assertFalse(impact["joined"])


class ProvenanceIndexLoadingTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-blast-")
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_loads_index_from_the_generated_app_sibling(self):
        model_dir = self.tmp / "demo"
        model_dir.mkdir()
        index_path = model_dir.parent / "demo-app" / "src" / "main" / "resources" / "npdev"
        index_path.mkdir(parents=True)
        (index_path / "provenance-index.json").write_text(
            json.dumps(PROVENANCE_INDEX), encoding="utf-8")

        loaded = npdev_cli._load_app_provenance_index(str(model_dir))
        self.assertEqual(PROVENANCE_INDEX["specNodes"]["Order"]["table"], loaded["specNodes"]["Order"]["table"])

    def test_missing_index_returns_empty_dict_never_raises(self):
        empty = self.tmp / "no-such-app"
        self.assertEqual({}, npdev_cli._load_app_provenance_index(str(empty)))
        self.assertEqual({}, npdev_cli._load_app_provenance_index(None))


class ImpactWithAppFlagTest(unittest.TestCase):
    """run_impact with --app: the artifactImpact section is emitted, joins when a provenance index
    exists, and reports problemsFound when there are destructive changes (the exit-2 rule)."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-blast-impact-")
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def _model_pair(self) -> tuple[Path, Path]:
        baseline = self.tmp / "baseline.json"
        current = self.tmp / "current.json"
        _write_json(baseline, MODEL_JSON)
        _write_json(current, MODEL_JSON)
        return baseline, current

    def test_app_with_index_joins_and_destructive_changes_set_problems_found(self):
        baseline, current = self._model_pair()
        app_dir = self.tmp / "demo"
        index_path = app_dir.parent / "demo-app" / "src" / "main" / "resources" / "npdev"
        index_path.mkdir(parents=True)
        (index_path / "provenance-index.json").write_text(json.dumps(PROVENANCE_INDEX), encoding="utf-8")

        args = argparse_shim(baseline, current, app=str(app_dir))
        with mock.patch.object(npdev_cli, "_classify_model_change_report",
                               return_value=CLASSIFY_REPORT_WITH_ITEMS), \
             mock.patch.object(npdev_cli, "load_model_xref",
                               return_value={"schemaVersion": "npdev-model-xref.v1",
                                             "summary": {"edges": 0, "resolved": 0,
                                                         "unresolved": 0, "undecidable": 0},
                                             "edges": []}), \
             mock.patch.object(npdev_cli, "_run_authoring_gate",
                               return_value={"status": "passed", "diagnostics": []}):
            with redirect_stdout(io.StringIO()) as buffer:
                code = npdev_cli.run_impact(args)
        result = json.loads(buffer.getvalue())

        self.assertEqual(2, code, "destructive blast radius IS a real problem -> exit 2")
        self.assertTrue(result["problemsFound"])
        section = result["artifactImpact"]
        self.assertEqual("ran", section["status"])
        report = section["report"]
        self.assertTrue(report["joined"])
        self.assertEqual(1, report["destructiveCount"])
        self.assertEqual("Order", report["tables"][0]["concept"])

    def test_app_without_index_reports_joined_false_instead_of_failing(self):
        baseline, current = self._model_pair()
        # An ADDITIVE-only plan: no destructive items, so a missing provenance index must not turn
        # this into a problem -- joined:false is the enrichment miss, not a red flag.
        additive_plan = {
            "classification": "SAFE_ADDITIVE",
            "items": [
                {"kind": "ADD_COLUMN", "table": "orders", "column": "tracking_code",
                 "destructive": False, "description": "New nullable column."},
            ],
        }
        args = argparse_shim(baseline, current, app=str(self.tmp / "missing-app"))
        with mock.patch.object(npdev_cli, "_classify_model_change_report",
                               return_value=additive_plan), \
             mock.patch.object(npdev_cli, "load_model_xref",
                               return_value={"schemaVersion": "npdev-model-xref.v1",
                                             "summary": {"edges": 0, "resolved": 0,
                                                         "unresolved": 0, "undecidable": 0},
                                             "edges": []}), \
             mock.patch.object(npdev_cli, "_run_authoring_gate",
                               return_value={"status": "passed", "diagnostics": []}):
            with redirect_stdout(io.StringIO()) as buffer:
                code = npdev_cli.run_impact(args)
        result = json.loads(buffer.getvalue())

        self.assertEqual(0, code, "joined:false with an additive plan is not a problem")
        self.assertFalse(result["artifactImpact"]["report"]["joined"])


def argparse_shim(baseline: Path, current: Path, **overrides) -> argparse_shim_t:
    import argparse as _argparse
    args = _argparse.Namespace(
        baseline=str(baseline), current=str(current), of=None, manifest=None,
        output=None, timeout=300.0, app=None,
    )
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


argparse_shim_t = object


if __name__ == "__main__":
    unittest.main()