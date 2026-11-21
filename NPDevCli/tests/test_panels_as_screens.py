"""D-1 phase 3: a hand-written panel is a `screens[]` entry {name, kind: "custom", panel: {...}}.

Pins the Python readers of the AUTHORING model that moved with it: the root `panels` refusal, `inspect app`, `migrate
assign-uids`, `npdev add panel`, and the bounded-contexts codemod. (The pack-qualification copy is pinned in
test_resolve_split_model_contexts, the rename cascade in test_rename_cascade, the v1->v2 migration in
test_dsl_v2_migration.)

Run with:  python -m unittest NPDevCli.tests.test_panels_as_screens -v
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

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dsl_v2_migration_bounded_contexts as bounded  # noqa: E402
import npdev_cli  # noqa: E402


def _model() -> dict:
    return {
        "dslVersion": "3.0.0", "namespace": "t", "version": "1.0.0",
        "concepts": [{"name": "Order", "fields": [{"name": "id", "type": "uuid", "id": True, "required": True}]}],
        "screens": [
            {"name": "OrderBoard", "kind": "list", "concept": "Order"},
            {"name": "OrderPanel", "kind": "custom", "panel": {
                "route": "order-panel", "dataSources": [{"name": "orders", "concept": "Order"}]}},
        ],
    }


def _write(directory: Path, model: dict) -> Path:
    path = directory / "model.json"
    path.write_text(json.dumps(model, indent=2), encoding="utf-8")
    return path


class PanelsAsScreensTest(unittest.TestCase):
    def test_a_root_panels_key_is_refused_with_the_new_shape_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = _model()
            model["panels"] = [{"name": "Old", "route": "old"}]
            path = _write(Path(tmp), model)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.resolve_split_model(path)
            self.assertIn("screens[]", str(ctx.exception))

    def test_inspect_app_still_reports_the_panels_it_counts_from_custom_screens(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "app.json"
            path = _write(Path(tmp), _model())
            npdev_cli.inspect_app(argparse.Namespace(model=str(path), output=str(out), human=False, json=True))
            report = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(1, report["counts"]["panels"])
            self.assertEqual(["OrderPanel"], report["panels"])

    def test_assign_uids_stamps_the_panel_block_of_a_custom_screen_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), _model())
            args = argparse.Namespace(model=str(path), write=True)
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, npdev_cli.run_migrate_assign_uids(args))
            doc = json.loads(path.read_text(encoding="utf-8"))
            uid = doc["screens"][1]["panel"]["uid"]
            self.assertRegex(uid, r"^[a-z0-9]{8,32}$")
            self.assertNotIn("uid", doc["screens"][0])
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_migrate_assign_uids(args)
            self.assertEqual(uid, json.loads(path.read_text(encoding="utf-8"))["screens"][1]["panel"]["uid"])

    def test_add_panel_from_an_exemplar_copies_only_custom_screens_and_renames_the_title(self):
        exemplar = {"name": "Src", "kind": "custom", "panel": {"route": "src", "title": "Src Panel"}}
        with tempfile.TemporaryDirectory() as tmp:
            sample = Path(tmp) / "NPDevSamples" / "demo" / "Input"
            sample.mkdir(parents=True)
            (sample / "model.json").write_text(json.dumps({"screens": [
                {"name": "Plain", "kind": "list", "concept": "X"}, exemplar]}), encoding="utf-8")
            member, source_name, _ = npdev_cli._add_load_exemplar_member(Path(tmp), "panel", "demo", "Mine")
            self.assertEqual("Src", source_name)
            self.assertEqual("Mine", member["name"])
            self.assertEqual("custom", member["kind"])
            self.assertEqual("Mine", member["panel"]["title"])

    def test_bounded_contexts_codemod_moves_only_the_panel_screens_into_the_context(self):
        with tempfile.TemporaryDirectory() as tmp:
            plan = bounded.plan_migration(_model(), Path(tmp))
            self.assertEqual(["OrderPanel"], [s["name"] for s in plan.context_doc["screens"]])
            self.assertEqual(["OrderBoard"], [s["name"] for s in plan.root_doc["screens"]])


if __name__ == "__main__":
    unittest.main()
