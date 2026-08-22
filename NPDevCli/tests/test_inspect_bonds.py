"""Tests for `npdev inspect bonds` and its `--diagram` option.

Covers two things found and fixed in the same session (2026-08-15):
  1. `resolve_split_model`'s top-level key whitelist (MODEL_ARRAY_KEYS/ROOT_SCALAR_KEYS) had
     drifted stale against the real schema -- a real model (WmsOffice) using `aggregates` was
     refused outright. Same failure shape REG-108 already named on the Java side, now caught here
     too. This test locks in that every real top-level schema key is accepted.
  2. `--diagram` renders the same bonds/concepts data as a self-contained ER-diagram HTML page
     (npdev_diagram.py) -- a first-class CLI feature, not an external script.

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_inspect_bonds -v
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_diagram  # noqa: E402


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _sample_model() -> dict:
    return {
        "dslVersion": "1.0.0",
        "namespace": "sample",
        "version": "1.0.0",
        "concepts": [
            {"name": "Author", "fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "name", "type": "string", "required": True},
            ]},
            {"name": "Book", "fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "title", "type": "string", "required": True},
                {"name": "authorId", "type": "reference", "required": True,
                 "reference": {"target": "Author", "onDelete": "restrict"}},
            ]},
        ],
        # Every real top-level schema key this whitelist was missing before the fix -- present
        # (even if mostly empty) so a stale whitelist would reject this model outright, the same
        # way it rejected WmsOffice's `aggregates` key.
        "conversions": [], "documents": [], "guidePages": [], "aggregates": [],
        "autoPanels": [], "selectors": [], "roles": [], "propertyScopes": [], "properties": [],
        "contexts": [],
        "provides": {}, "externalAi": {}, "settings": {},
        # A pack reference with the `as` alias -- must not trip the generic $ref-shape validator.
        "packs": [{"$ref": "packs/identity/pack.json", "as": "identity"}],
    }


def _write_sample_model(tmp_dir: Path) -> Path:
    """Write the sample model AND the pack file it declares.

    REG-186: `packs[]` used to be a raw pass-through, so a model naming a pack file that did not
    exist resolved happily and the pack simply contributed nothing. Now the composer reads it, and a
    missing pack file is a named error -- correct, and the same class of silence this plan removed
    elsewhere. These fixtures declared the pack without writing it.
    """
    _write(tmp_dir / "packs" / "identity" / "pack.json", json.dumps({
        "dslVersion": "1.0.0", "pack": "identity", "version": "1.0.0", "concepts": [],
    }))
    model_path = tmp_dir / "model.json"
    _write(model_path, json.dumps(_sample_model()))
    return model_path


class InspectBondsWhitelistTest(unittest.TestCase):
    def test_every_real_top_level_key_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            _write(tmp_dir / "packs" / "identity" / "pack.json", json.dumps({
                "dslVersion": "1.0.0", "pack": "identity", "version": "1.0.0", "concepts": [],
            }))
            model_path = tmp_dir / "model.json"
            _write(model_path, json.dumps(_sample_model()))

            resolved = npdev_cli.resolve_split_model(model_path)
            self.assertEqual(len(resolved.get("concepts", [])), 2)
            # `packs` survives as a raw pass-through (ROOT_SCALAR_KEYS), not fragment-expanded.
            self.assertEqual(resolved.get("packs"), [{"$ref": "packs/identity/pack.json", "as": "identity"}])

    def test_inspect_bonds_reports_the_one_real_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_sample_model(tmp_dir)
            output_path = tmp_dir / "bonds.json"

            args = argparse.Namespace(model=str(model_path), output=str(output_path), diagram=None)
            npdev_cli.inspect_bonds(args)

            result = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(result["bondCount"], 1)
            self.assertEqual(result["bonds"][0]["sourceConcept"], "Book")
            self.assertEqual(result["bonds"][0]["targetConcept"], "Author")


class InspectBondsDiagramTest(unittest.TestCase):
    def test_diagram_flag_writes_a_self_contained_html_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_sample_model(tmp_dir)
            diagram_path = tmp_dir / "diagram.html"

            args = argparse.Namespace(model=str(model_path), output=None, diagram=str(diagram_path))
            npdev_cli.inspect_bonds(args)

            self.assertTrue(diagram_path.is_file())
            html = diagram_path.read_text(encoding="utf-8")
            self.assertIn("<svg", html)
            self.assertIn('id="er-diagram-data"', html)
            # Tables render client-side (drag-and-drop needs live DOM nodes, not static SVG) from
            # this embedded JSON blob -- both concept names and the FK column must be in it.
            data_start = html.index('id="er-diagram-data"')
            data = json.loads(html[html.index(">", data_start) + 1: html.index("</script>", data_start)])
            table_names = {table["name"] for table in data["tables"]}
            self.assertEqual(table_names, {"Author", "Book"})
            book_columns = {
                column["name"] for table in data["tables"] if table["name"] == "Book" for column in table["columns"]
            }
            self.assertIn("authorId", book_columns)
            # The drag-and-drop rendering script itself must be present.
            self.assertIn("attachDrag", html)
            self.assertIn("setPointerCapture", html)

    def test_render_function_is_driven_by_the_same_bonds_shape_inspect_bonds_computes(self):
        concepts = {
            "A": {"fields": [{"name": "id", "type": "uuid", "id": True, "required": True}]},
            "B": {"fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "aId", "type": "reference", "required": True, "reference": {"target": "A"}},
            ]},
        }
        bonds = [{
            "sourceConcept": "B", "sourceField": "aId", "targetConcept": "A", "via": "id",
            "cardinality": "many-to-one",
        }]
        html = npdev_diagram.render_bonds_diagram_html(concepts, bonds, model_label="unit-test")
        self.assertIn("<svg", html)
        self.assertIn("unit-test", html)


if __name__ == "__main__":
    unittest.main()
