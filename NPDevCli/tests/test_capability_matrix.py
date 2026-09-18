"""S12 (NPDEV_MEGA_ROADMAP.md, Track B): pins the capability matrix emitter's pure functions --
build_matrix (app x capability cells + per-feature witness files) and render_html (the human view).

Stdlib-only (unittest), same convention as the CLI tests. The full-corpus run is exercised live by
the script itself (and appears in scripts/reports/out/capability-matrix/); here we pin the shapes
on tiny synthetic corpora so a refactor of the emitter cannot silently change what a cell means.

Run with:
    python -m unittest NPDevCli.tests.test_capability_matrix -v
"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]

# Load the emitter as a module (it lives under scripts/quality, not on this test's import path).
_SPEC = importlib.util.spec_from_file_location(
    "emit_capability_matrix", REPO_ROOT / "scripts" / "quality" / "emit-capability-matrix.py")
emit = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(emit)


def _write_model(base: Path, rel: str, doc: dict) -> None:
    path = base / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc), encoding="utf-8")


class BuildMatrixShapeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-capmatrix-")
        self.tmp = Path(self._tmp.name)
        self.appgen = self.tmp / "AppGen" / "apps"
        self.samples = self.tmp / "Samples"
        self.addCleanup(self._tmp.cleanup)
        # The witness universe indexes the WHOLE repo tree -- real, and slow on Windows. For shape
        # tests a tiny fake universe is enough: what we pin is that a feature's witness list comes
        # from the universe search, not that every real test file is enumerated (the live emitter
        # run exercises the real universe).
        self._universe_patcher = mock.patch.object(
            emit, "_witness_universe", return_value={
                "NPDevKernel/adapters/tracing-inproc/src/test/java/XTest.java": "balances aggregate",
                "NPDevCli/tests/test_impact_cli.py": "panels queries",
            })
        self._universe_patcher.start()
        self.addCleanup(self._universe_patcher.stop)

    def _corpus(self) -> tuple[Path, Path]:
        # One appgen app using panels+queries, one sample app using only autoPanels.
        _write_model(self.appgen, "warehouse/model.json", {
            "dslVersion": "1.0.0", "version": "1.0", "namespace": "wh",
            "concepts": [{"name": "Item", "fields": [{"name": "id", "type": "uuid", "id": True,
                                                       "required": True}]}],
            "panels": [{"name": "ItemList", "route": "/items"}],
            "queries": [{"name": "All", "concept": "Item"}],
        })
        _write_model(self.samples, "store/model.json", {
            "dslVersion": "1.0.0", "version": "1.0", "namespace": "st",
            "concepts": [{"name": "Product", "fields": [{"name": "id", "type": "uuid", "id": True,
                                                          "required": True}]}],
            "autoPanels": [{"name": "Products", "concept": "Product"}],
        })
        return self.appgen, self.samples

    def test_each_app_lists_only_the_features_its_detectors_match(self):
        matrix = emit.build_matrix(*self._corpus())

        labels = [a["label"] for a in matrix["apps"]]
        self.assertIn("AppGen/apps/warehouse", labels)
        self.assertIn("NPDevSamples/store", labels)

        warehouse = next(a for a in matrix["apps"] if a["label"] == "AppGen/apps/warehouse")
        self.assertIn("panels", warehouse["used"])
        self.assertIn("queries", warehouse["used"])
        self.assertNotIn("autoPanels", warehouse["used"])

        store = next(a for a in matrix["apps"] if a["label"] == "NPDevSamples/store")
        self.assertIn("autoPanels", store["used"])
        self.assertNotIn("panels", store["used"])

    def test_feature_entries_carry_corpus_use_counts_and_keywords(self):
        matrix = emit.build_matrix(*self._corpus())
        panels = next(f for f in matrix["features"] if f["name"] == "panels")
        self.assertEqual(1, panels["corpusUsers"])
        self.assertIn("panels", panels["keywords"])
        auto = next(f for f in matrix["features"] if f["name"] == "autoPanels")
        self.assertEqual(1, auto["corpusUsers"])

    def test_counts_are_consistent(self):
        matrix = emit.build_matrix(*self._corpus())
        self.assertEqual(2, matrix["counts"]["apps"])
        self.assertEqual(len(matrix["features"]), matrix["counts"]["capabilities"])
        self.assertEqual(
            matrix["counts"]["capabilities"],
            matrix["counts"]["capabilitiesWithWitness"] + matrix["counts"]["capabilitiesWithoutWitness"],
        )


class RenderHtmlShapeTest(BuildMatrixShapeTest):
    def test_html_renders_one_row_per_app_plus_header_and_witness_row(self):
        matrix = emit.build_matrix(*self._corpus())
        html = emit.render_html(matrix)

        self.assertIn("<title>", html)
        self.assertIn("App \\ Capability", html)
        # One body row per app, plus the witness row.
        for label in ("AppGen/apps/warehouse", "NPDevSamples/store"):
            self.assertIn(label, html)
        self.assertIn("WITNESS", html)
        self.assertTrue(html.rstrip().endswith("</html>"))


class WitnessDetectionTest(unittest.TestCase):
    def test_last_segment_keyword_drives_witness_files(self):
        # The feature "aggregate.balances" must seek its last segment "balances" in the witness
        # universe -- a test class named BalancesTest.java would be a real witness.
        keywords = emit._feature_keywords("aggregate.balances")
        self.assertIn("aggregate.balances", keywords)
        self.assertIn("balances", keywords)


if __name__ == "__main__":
    unittest.main()