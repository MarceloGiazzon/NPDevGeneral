"""R1.6: `npdev impact` -- one composed change-preview report over four existing backends.

Every Gradle-backed leg is stubbed here on purpose, same discipline as test_inspect_usage.py's own
docstring: `_classify_model_change_report`/`load_model_xref`/`_run_authoring_gate`/`_pack_diff_report`
are three lines of subprocess plumbing each, already covered by their own commands
(`migration diff`/`inspect usage`/`author diff-gate`/`pack diff`); exercising them for real here would
turn a fast unit test into a multi-minute build with no new coverage. What IS worth pinning is
everything `run_impact` itself does: which legs run for which subjectKind, that `--of`/`--manifest`
are refused for a pack pair, that a mismatched baseline/current kind is refused, that `--of` naming an
unknown concept degrades to a limitation rather than a hard failure, the `problemsFound`/exit-code
rule, and -- the "verified in CI" half of R1.6's own done-when -- that a report this function actually
produces validates against schemas/ai/change-impact-report.schema.json for BOTH document kinds.

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_impact_cli -v
"""

from __future__ import annotations

import argparse
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
import npdev_jsonschema  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / "schemas" / "ai" / "change-impact-report.schema.json"

MODEL_JSON = {
    "dslVersion": "1.0.0",
    "version": "1.0",
    "namespace": "demo",
    "concepts": [
        {"name": "Order", "fields": [{"name": "id", "type": "string"}, {"name": "status", "type": "string"}]},
    ],
}

PACK_JSON = {
    "dslVersion": "1.0.0",
    "pack": "widgets",
    "version": "1.0.0",
    "concepts": [{"name": "Widget", "fields": [{"name": "id", "type": "string"}]}],
}

CLEAN_XREF_REPORT = {
    "schemaVersion": "npdev-model-xref.v1",
    "model": "model.json",
    "summary": {"edges": 1, "resolved": 1, "unresolved": 0, "undecidable": 0},
    "edges": [
        {"fromKind": "query", "fromName": "Recent", "site": "query.orderBy",
         "path": "queries[Recent].orderBy[0]", "toKind": "field", "toName": "Order.status",
         "ownerConcept": "Order", "resolution": "RESOLVED"},
    ],
}

DIRTY_XREF_REPORT = {
    "schemaVersion": "npdev-model-xref.v1",
    "model": "model.json",
    "summary": {"edges": 1, "resolved": 0, "unresolved": 1, "undecidable": 0},
    "edges": [
        {"fromKind": "query", "fromName": "Ghost", "site": "query.orderBy",
         "path": "queries[Ghost].orderBy[0]", "toKind": "field", "toName": "Order.missing",
         "ownerConcept": "Order", "resolution": "UNRESOLVED"},
    ],
}

CLASSIFY_REPORT = {"classification": "SAFE_ADDITIVE", "classificationReasons": ["a field was added"]}

PASSED_AUTHORING_REPORT = {"status": "passed", "diagnostics": []}
REFUSED_AUTHORING_REPORT = {
    "status": "failed",
    "diagnostics": [{"code": "AUTHORING_MANIFEST_MISSING", "severity": "ERROR",
                      "message": "no manifest", "path": None, "suggestedFix": None}],
}

PACK_DIFF_REPORT = {"classification": "ADDITIVE", "changes": []}


def _write_json(path: Path, content: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content), encoding="utf-8")


class _ImpactTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-impact-")
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def _model_pair(self, current_overrides: dict | None = None) -> tuple[Path, Path]:
        baseline = self.tmp / "baseline.json"
        current = self.tmp / "current.json"
        _write_json(baseline, MODEL_JSON)
        current_doc = dict(MODEL_JSON)
        if current_overrides:
            current_doc.update(current_overrides)
        _write_json(current, current_doc)
        return baseline, current

    def _pack_pair(self) -> tuple[Path, Path]:
        baseline = self.tmp / "old-pack.json"
        current = self.tmp / "new-pack.json"
        _write_json(baseline, PACK_JSON)
        new_pack = dict(PACK_JSON)
        new_pack["version"] = "1.1.0"
        _write_json(current, new_pack)
        return baseline, current

    def _run(self, baseline: Path, current: Path, **overrides) -> tuple[int, dict]:
        args = argparse.Namespace(
            baseline=str(baseline), current=str(current), of=None, manifest=None,
            output=None, timeout=300.0,
        )
        for key, value in overrides.items():
            setattr(args, key, value)
        buffer = io.StringIO()
        with mock.patch.object(npdev_cli, "_classify_model_change_report", return_value=CLASSIFY_REPORT), \
             mock.patch.object(npdev_cli, "load_model_xref", return_value=CLEAN_XREF_REPORT), \
             mock.patch.object(npdev_cli, "_run_authoring_gate", return_value=PASSED_AUTHORING_REPORT), \
             mock.patch.object(npdev_cli, "_pack_diff_report", return_value=PACK_DIFF_REPORT):
            with redirect_stdout(buffer):
                code = npdev_cli.run_impact(args)
        return code, json.loads(buffer.getvalue())


class ModelPairCompositionTest(_ImpactTestBase):
    def test_all_three_applicable_legs_run_and_pack_diff_is_not_applicable(self):
        baseline, current = self._model_pair()
        code, result = self._run(baseline, current)

        self.assertEqual(0, code)
        self.assertEqual("model", result["subjectKind"])
        self.assertEqual("ran", result["migrationClassification"]["status"])
        self.assertEqual(CLASSIFY_REPORT, result["migrationClassification"]["report"])
        self.assertEqual("ran", result["xrefUsage"]["status"])
        self.assertEqual("ran", result["authoringGate"]["status"])
        self.assertEqual("notApplicable", result["packDiff"]["status"])
        self.assertIsNone(result["packDiff"]["report"])
        self.assertIsNotNone(result["packDiff"]["reason"])
        self.assertFalse(result["problemsFound"])

    def test_authoring_gate_runs_with_no_manifest_and_reports_its_own_refusal(self):
        """No --manifest must not SKIP the leg -- it must run and surface the same
        AUTHORING_MANIFEST_MISSING refusal `author diff-gate` itself reports without one, with a
        `reason` explaining why (measurement honesty: a caller must see this was attempted)."""
        baseline, current = self._model_pair()
        buffer = io.StringIO()
        args = argparse.Namespace(baseline=str(baseline), current=str(current), of=None,
                                   manifest=None, output=None, timeout=300.0)
        with mock.patch.object(npdev_cli, "_classify_model_change_report", return_value=CLASSIFY_REPORT), \
             mock.patch.object(npdev_cli, "load_model_xref", return_value=CLEAN_XREF_REPORT), \
             mock.patch.object(npdev_cli, "_run_authoring_gate", return_value=REFUSED_AUTHORING_REPORT) as gate:
            with redirect_stdout(buffer):
                code = npdev_cli.run_impact(args)
        result = json.loads(buffer.getvalue())

        gate.assert_called_once()
        called_args = gate.call_args[0][0]
        self.assertIsNone(called_args.manifest)
        self.assertEqual("ran", result["authoringGate"]["status"])
        self.assertIsNotNone(result["authoringGate"]["reason"])
        self.assertEqual(REFUSED_AUTHORING_REPORT, result["authoringGate"]["report"])
        self.assertTrue(result["problemsFound"], "a failed authoring gate IS a real problem")
        self.assertEqual(2, code)

    def test_manifest_is_threaded_through_to_the_authoring_gate(self):
        baseline, current = self._model_pair()
        manifest = self.tmp / "manifest.json"
        _write_json(manifest, {"schemaVersion": "npdev-authoring-submission.v1"})
        with mock.patch.object(npdev_cli, "_classify_model_change_report", return_value=CLASSIFY_REPORT), \
             mock.patch.object(npdev_cli, "load_model_xref", return_value=CLEAN_XREF_REPORT), \
             mock.patch.object(npdev_cli, "_run_authoring_gate", return_value=PASSED_AUTHORING_REPORT) as gate:
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_impact(argparse.Namespace(
                    baseline=str(baseline), current=str(current), of=None,
                    manifest=str(manifest), output=None, timeout=300.0))

        called_args = gate.call_args[0][0]
        self.assertEqual(str(manifest), called_args.manifest)


class XrefUsageCompositionTest(_ImpactTestBase):
    def test_of_narrows_edges_the_same_way_inspect_usage_does(self):
        baseline, current = self._model_pair()
        xref = dict(CLEAN_XREF_REPORT)
        code, result = self._run(baseline, current, of="Order.status")

        self.assertEqual(0, code)
        self.assertEqual("usagesOf:Order.status", result["xrefUsage"]["report"]["mode"])
        self.assertEqual(1, result["xrefUsage"]["report"]["counts"]["matched"])
        self.assertEqual("Order.status", result["of"])

    def test_problems_found_true_on_whole_model_unresolved_even_when_of_narrows_elsewhere(self):
        """impact's problemsFound is a whole-model health signal (like --orphans), not a --of
        answer -- an unrelated unresolved reference elsewhere must still surface as a problem."""
        baseline, current = self._model_pair()
        with mock.patch.object(npdev_cli, "_classify_model_change_report", return_value=CLASSIFY_REPORT), \
             mock.patch.object(npdev_cli, "load_model_xref", return_value=DIRTY_XREF_REPORT), \
             mock.patch.object(npdev_cli, "_run_authoring_gate", return_value=PASSED_AUTHORING_REPORT):
            with redirect_stdout(io.StringIO()) as buffer:
                code = npdev_cli.run_impact(argparse.Namespace(
                    baseline=str(baseline), current=str(current), of="Order.status",
                    manifest=None, output=None, timeout=300.0))
        result = json.loads(buffer.getvalue())

        self.assertEqual(2, code)
        self.assertTrue(result["problemsFound"])
        # --of itself still answers narrowly and does not fail because of the unrelated orphan.
        self.assertEqual(0, result["xrefUsage"]["report"]["counts"]["matched"])

    def test_of_naming_an_unknown_concept_is_a_limitation_not_an_error(self):
        baseline, current = self._model_pair()
        code, result = self._run(baseline, current, of="NoSuchConcept.field")

        self.assertEqual(0, code)
        self.assertTrue(
            any("NoSuchConcept" in note for note in result["limitations"]),
            result["limitations"],
        )

    def test_of_naming_a_kind_prefixed_target_skips_the_concept_precheck(self):
        baseline, current = self._model_pair()
        before_count = len(self._run(baseline, current)[1]["limitations"])
        code, result = self._run(baseline, current, of="procedure:DoesNotExist")

        self.assertEqual(0, code)
        self.assertEqual(before_count, len(result["limitations"]))

    def test_of_naming_a_real_concept_adds_no_extra_limitation(self):
        baseline, current = self._model_pair()
        baseline_count = len(self._run(baseline, current)[1]["limitations"])
        code, result = self._run(baseline, current, of="Order")

        self.assertEqual(baseline_count, len(result["limitations"]))


class PackPairCompositionTest(_ImpactTestBase):
    def test_pack_diff_runs_and_the_other_three_legs_are_not_applicable(self):
        baseline, current = self._pack_pair()
        code, result = self._run(baseline, current)

        self.assertEqual(0, code)
        self.assertEqual("pack", result["subjectKind"])
        self.assertEqual("ran", result["packDiff"]["status"])
        self.assertEqual(PACK_DIFF_REPORT, result["packDiff"]["report"])
        for leg in ("migrationClassification", "xrefUsage", "authoringGate"):
            self.assertEqual("notApplicable", result[leg]["status"], leg)
            self.assertIsNone(result[leg]["report"], leg)
            self.assertIsNotNone(result[leg]["reason"], leg)
        self.assertIsNone(result["of"])

    def test_of_is_refused_for_a_pack_pair(self):
        baseline, current = self._pack_pair()
        with self.assertRaises(npdev_cli.CliError):
            self._run(baseline, current, of="Widget")

    def test_manifest_is_refused_for_a_pack_pair(self):
        baseline, current = self._pack_pair()
        with self.assertRaises(npdev_cli.CliError):
            self._run(baseline, current, manifest=str(self.tmp / "m.json"))


class KindMismatchAndUsageErrorsTest(_ImpactTestBase):
    def test_a_model_baseline_with_a_pack_current_is_refused(self):
        model_baseline, _ = self._model_pair()
        _, pack_current = self._pack_pair()
        with self.assertRaises(npdev_cli.CliError):
            self._run(model_baseline, pack_current)

    def test_a_missing_current_file_is_refused(self):
        baseline, _ = self._model_pair()
        with self.assertRaises(npdev_cli.CliError):
            self._run(baseline, self.tmp / "does-not-exist.json")


class DocumentKindDetectionTest(unittest.TestCase):
    def test_a_bare_pack_string_key_means_pack(self):
        self.assertEqual("pack", npdev_cli._document_kind({"pack": "widgets", "version": "1.0.0"}, Path("x")))

    def test_no_pack_key_means_model(self):
        self.assertEqual("model", npdev_cli._document_kind({"dslVersion": "1.0.0", "version": "1.0"}, Path("x")))

    def test_a_blank_pack_string_still_means_model(self):
        self.assertEqual("model", npdev_cli._document_kind({"pack": "  ", "concepts": []}, Path("x")))


class SchemaConformanceTest(_ImpactTestBase):
    """R1.6's own done-when: 'a single schema-validated report ... verified in CI.' Real reports
    (produced by run_impact itself, backends stubbed) validated against the real schema file with
    the same pure-Python validator test_monitor_and_explore.py's RunRecordShape test already
    established as this repo's CI-hermetic proof mechanism (no Node/ajv dependency)."""

    def setUp(self) -> None:
        super().setUp()
        self.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    def test_model_pair_report_validates_against_its_own_schema(self):
        baseline, current = self._model_pair()
        _, result = self._run(baseline, current, of="Order.status")
        errors = npdev_jsonschema.validate(self.schema, result)
        self.assertEqual([], errors, npdev_jsonschema.describe(errors))

    def test_pack_pair_report_validates_against_its_own_schema(self):
        baseline, current = self._pack_pair()
        _, result = self._run(baseline, current)
        errors = npdev_jsonschema.validate(self.schema, result)
        self.assertEqual([], errors, npdev_jsonschema.describe(errors))

    def test_refused_authoring_gate_report_also_validates(self):
        """The 'ran but refused' shape (no --manifest) is a distinct branch worth its own proof --
        it is the one with a non-null `reason` on a status: 'ran' section."""
        baseline, current = self._model_pair()
        with mock.patch.object(npdev_cli, "_classify_model_change_report", return_value=CLASSIFY_REPORT), \
             mock.patch.object(npdev_cli, "load_model_xref", return_value=CLEAN_XREF_REPORT), \
             mock.patch.object(npdev_cli, "_run_authoring_gate", return_value=REFUSED_AUTHORING_REPORT):
            with redirect_stdout(io.StringIO()) as buffer:
                npdev_cli.run_impact(argparse.Namespace(
                    baseline=str(baseline), current=str(current), of=None,
                    manifest=None, output=None, timeout=300.0))
        result = json.loads(buffer.getvalue())
        errors = npdev_jsonschema.validate(self.schema, result)
        self.assertEqual([], errors, npdev_jsonschema.describe(errors))

    def test_embedded_xref_report_has_the_same_field_names_inspect_usage_produces(self):
        """change-impact-report.schema.json deliberately leaves xrefUsage.report untyped --
        npdev_jsonschema only supports LOCAL $ref, so it cannot reference model-xref.schema.json's
        own file directly (and that schema's own top-level 'version' key is itself outside this
        validator's supported keyword set, proven separately: model-xref.schema.json cannot be fed
        to npdev_jsonschema.validate() at all today). What IS checked here is the load-bearing
        guarantee instead: xrefUsage.report is built by the exact same `_usage_report` helper
        `inspect usage` itself calls (see _select_usage_edges/_usage_report in npdev_cli.py), so the
        two can never drift into disagreeing about the shape."""
        baseline, current = self._model_pair()
        _, result = self._run(baseline, current)
        self.assertEqual(
            {"model", "mode", "of", "counts", "modelTotals", "edges"},
            set(result["xrefUsage"]["report"].keys()),
        )


if __name__ == "__main__":
    unittest.main()
