"""Tests for `npdev migrate rename` (B1.2, docs/ACCEPTED_BOUNDARIES.md B1).

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_migrate_rename -v
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


def _write_model(tmp_dir: Path, concepts: list[dict]) -> Path:
    model = {
        "$schema": "NPDevContract/schemas/model.schema.json",
        "namespace": "test.migrate.rename",
        "dslVersion": "1.0.0",
        "version": "1.0",
        "concepts": concepts,
    }
    path = tmp_dir / "model.json"
    path.write_text(json.dumps(model, indent=2), encoding="utf-8")
    return path


def _args(model_path: Path, field: str, new_name: str, write: bool) -> argparse.Namespace:
    return argparse.Namespace(model=str(model_path), field=field, new_name=new_name, write=write)


class MigrateRenameTest(unittest.TestCase):
    def test_dry_run_does_not_touch_the_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [
                {"name": "Order", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "customerName", "type": "string"},
                ]},
            ])
            before = model_path.read_text(encoding="utf-8")

            code = npdev_cli.run_migrate_rename(_args(model_path, "Order.customerName", "clientName", write=False))

            self.assertEqual(0, code)
            self.assertEqual(before, model_path.read_text(encoding="utf-8"), "dry run must not write")

    def test_write_stamps_renamed_from_and_renames_the_field(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [
                {"name": "Order", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "customerName", "type": "string"},
                ]},
            ])
            # This test must not require Node/npm for schema validation -- monkeypatch it to a no-op,
            # the same way the dry-run tests avoid it by construction (dry run never calls it at all).
            original_validate = npdev_cli.validate_json_schema
            npdev_cli.validate_json_schema = lambda schema, instance: {"status": "passed"}
            try:
                code = npdev_cli.run_migrate_rename(_args(model_path, "Order.customerName", "clientName", write=True))
            finally:
                npdev_cli.validate_json_schema = original_validate

            self.assertEqual(0, code)
            model = json.loads(model_path.read_text(encoding="utf-8"))
            field = model["concepts"][0]["fields"][1]
            self.assertEqual("clientName", field["name"])
            self.assertEqual("customerName", field["renamedFrom"])

    def test_renaming_back_to_the_original_name_clears_renamed_from(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [
                {"name": "Order", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "clientName", "type": "string", "renamedFrom": "customerName"},
                ]},
            ])
            original_validate = npdev_cli.validate_json_schema
            npdev_cli.validate_json_schema = lambda schema, instance: {"status": "passed"}
            try:
                code = npdev_cli.run_migrate_rename(_args(model_path, "Order.clientName", "customerName", write=True))
            finally:
                npdev_cli.validate_json_schema = original_validate

            self.assertEqual(0, code)
            model = json.loads(model_path.read_text(encoding="utf-8"))
            field = model["concepts"][0]["fields"][1]
            self.assertEqual("customerName", field["name"])
            self.assertNotIn("renamedFrom", field, "a name that nets out unchanged is not a rename")

    def test_preserves_original_name_across_a_chain_of_renames(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [
                {"name": "Order", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "clientName", "type": "string", "renamedFrom": "customerName"},
                ]},
            ])
            original_validate = npdev_cli.validate_json_schema
            npdev_cli.validate_json_schema = lambda schema, instance: {"status": "passed"}
            try:
                npdev_cli.run_migrate_rename(_args(model_path, "Order.clientName", "buyerName", write=True))
            finally:
                npdev_cli.validate_json_schema = original_validate

            model = json.loads(model_path.read_text(encoding="utf-8"))
            field = model["concepts"][0]["fields"][1]
            self.assertEqual("buyerName", field["name"])
            self.assertEqual("customerName", field["renamedFrom"], "must keep the ORIGINAL name, not the intermediate one")

    def test_unknown_concept_raises_cli_error_listing_available_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [{"name": "Order", "fields": [{"name": "id", "type": "uuid"}]}])

            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_migrate_rename(_args(model_path, "Widget.name", "label", write=False))
            self.assertIn("Order", str(ctx.exception))

    def test_unknown_field_raises_cli_error_listing_available_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [
                {"name": "Order", "fields": [{"name": "id", "type": "uuid"}, {"name": "status", "type": "string"}]},
            ])

            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_migrate_rename(_args(model_path, "Order.missingField", "x", write=False))
            self.assertIn("status", str(ctx.exception))

    def test_colliding_new_name_raises_cli_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, [
                {"name": "Order", "fields": [{"name": "id", "type": "uuid"}, {"name": "status", "type": "string"}]},
            ])

            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_migrate_rename(_args(model_path, "Order.id", "status", write=False))


if __name__ == "__main__":
    unittest.main()
