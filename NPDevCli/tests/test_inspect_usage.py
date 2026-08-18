"""XREF-2: `npdev inspect usage` -- selection, exit codes, and the diagram page.

The Gradle-backed half (`load_model_xref`) is stubbed here on purpose. It is three lines of
subprocess plumbing copied from `run_validate_semantic`, and exercising it would turn a 0.3s unit
test into a two-minute build. What IS worth pinning is everything downstream of it: which edges a
given `--of` selects, that `--orphans` fails on UNRESOLVED but NOT on UNDECIDABLE, and that the
rendered page is genuinely self-contained.

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_inspect_usage -v
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
import npdev_diagram  # noqa: E402


def _edge(from_kind, from_name, site, path, to_kind, to_name, owner=None, resolution="RESOLVED"):
    return {
        "fromKind": from_kind, "fromName": from_name, "site": site, "path": path,
        "toKind": to_kind, "toName": to_name, "ownerConcept": owner, "resolution": resolution,
    }


REPORT = {
    "schemaVersion": "npdev-model-xref.v1",
    "model": "model.json",
    "summary": {"edges": 6, "resolved": 4, "unresolved": 1, "undecidable": 1},
    "edges": [
        _edge("panel", "OrdersPanel", "panel.layout.fields",
              "panels[OrdersPanel].layout.fields[0]", "field", "Order.status", "Order"),
        _edge("panel", "OrdersPanel", "panel.fieldBindings.field",
              "panels[OrdersPanel].fieldBindings[0].field", "field", "Order.status", "Order"),
        _edge("query", "Recent", "query.orderBy",
              "queries[Recent].orderBy[0]", "field", "Order.createdAt", "Order"),
        _edge("panel", "OrdersPanel", "panel.dataSources.onRowLoad",
              "panels[OrdersPanel].dataSources[0].onRowLoad", "procedure", "EnrichRows"),
        _edge("query", "Ghost", "query.orderBy",
              "queries[Ghost].orderBy[0]", "field", "Order.ghostField", "Order",
              resolution="UNRESOLVED"),
        _edge("panel", "OrdersPanel", "panel.predicate",
              "panels[OrdersPanel].visibility", "expression", "isSuperUser",
              resolution="UNDECIDABLE"),
    ],
}


class InspectUsageTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-xref2-")
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.model = self.tmp / "model.json"
        self.model.write_text("{}", encoding="utf-8")

    def _run(self, **overrides):
        args = argparse.Namespace(model=str(self.model), of=None, orphans=False,
                                  diagram=None, output=None)
        for key, value in overrides.items():
            setattr(args, key, value)
        buffer = io.StringIO()
        with mock.patch.object(npdev_cli, "load_model_xref", return_value=REPORT):
            with redirect_stdout(buffer):
                code = npdev_cli.inspect_usage(args)
        return code, json.loads(buffer.getvalue())

    # -- selection -------------------------------------------------------------------------------

    def test_of_a_field_selects_every_site_naming_it(self) -> None:
        code, result = self._run(of="Order.status")

        self.assertEqual(0, code)
        self.assertEqual(2, result["counts"]["matched"])
        self.assertEqual(
            {"panels[OrdersPanel].layout.fields[0]", "panels[OrdersPanel].fieldBindings[0].field"},
            {e["path"] for e in result["edges"]},
        )

    def test_of_a_concept_also_selects_its_fields(self) -> None:
        """"Who uses Order?" that omitted every panel column reading an Order field would be a
        useless answer -- the author is asking what breaks if the concept changes."""
        _, result = self._run(of="Order")

        sites = {e["site"] for e in result["edges"]}
        self.assertIn("panel.layout.fields", sites)
        self.assertIn("query.orderBy", sites)
        self.assertEqual(4, result["counts"]["matched"], result["edges"])

    def test_of_a_kind_prefixed_name_matches_only_that_kind(self) -> None:
        _, result = self._run(of="procedure:EnrichRows")

        self.assertEqual(1, result["counts"]["matched"])
        self.assertEqual("panel.dataSources.onRowLoad", result["edges"][0]["site"])

    def test_of_an_unreferenced_target_is_an_empty_answer_not_an_error(self) -> None:
        code, result = self._run(of="Order.neverUsed")

        self.assertEqual(0, code)
        self.assertEqual(0, result["counts"]["matched"])

    # -- exit codes ------------------------------------------------------------------------------

    def test_orphans_exits_2_when_something_is_unresolved(self) -> None:
        code, result = self._run(orphans=True)

        self.assertEqual(2, code, "2 is this CLI's 'ran fine, found a real problem' code")
        self.assertEqual(1, result["counts"]["unresolved"])
        self.assertEqual(1, result["counts"]["undecidable"])

    def test_orphans_exits_0_when_only_undecidable_edges_remain(self) -> None:
        """UNDECIDABLE must never fail a build. A checker that blocks on what it cannot understand
        teaches authors to work around it -- and the point of this whole index is that 'could not
        check' and 'checked, fine' stop being the same output."""
        clean = dict(REPORT)
        clean["edges"] = [e for e in REPORT["edges"] if e["resolution"] != "UNRESOLVED"]
        args = argparse.Namespace(model=str(self.model), of=None, orphans=True,
                                  diagram=None, output=None)
        buffer = io.StringIO()
        with mock.patch.object(npdev_cli, "load_model_xref", return_value=clean):
            with redirect_stdout(buffer):
                code = npdev_cli.inspect_usage(args)

        result = json.loads(buffer.getvalue())
        self.assertEqual(0, code)
        self.assertEqual(1, result["counts"]["undecidable"])

    def test_of_does_not_fail_because_the_model_has_an_orphan_elsewhere(self) -> None:
        """`--of X` answers a question; it is not a validation run. Returning non-zero here would
        make the command unusable on exactly the models someone is trying to repair."""
        code, result = self._run(of="Order.status")

        self.assertEqual(0, code)
        self.assertEqual(1, result["modelTotals"]["unresolved"],
                         "the whole-model total stays visible even though it did not fail")

    # -- diagram ---------------------------------------------------------------------------------

    def test_diagram_is_written_and_self_contained(self) -> None:
        out = self.tmp / "nested" / "usage.html"
        code, result = self._run(of="Order", diagram=str(out))

        self.assertEqual(0, code)
        self.assertEqual(str(out), result["diagram"])
        html = out.read_text(encoding="utf-8")
        self.assertIn("<!doctype html>", html)
        self.assertIn("panels[OrdersPanel].layout.fields[0]", html)
        for forbidden in ("<script src", "<link rel=\"stylesheet\"", "http://", "https://"):
            self.assertNotIn(forbidden, html,
                             f"the page must not reach the network: found {forbidden!r}")

    def test_diagram_of_an_unreferenced_target_says_so_in_words(self) -> None:
        html = npdev_diagram.render_usage_diagram_html([], model_label="demo", target="Order.unused")

        self.assertIn("Nothing references this", html)
        self.assertIn("not an error", html, "an empty result is an ANSWER, and must read like one")


if __name__ == "__main__":
    unittest.main()
