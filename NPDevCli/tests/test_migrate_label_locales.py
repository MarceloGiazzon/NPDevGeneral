"""Tests for `npdev migrate label-locales` (CLI-1: dsl_v2_migration.migrate_label_locales was
written and round-trip tested for R5.6 but was never wired to a CLI subcommand).

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_migrate_label_locales -v
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


def _write_model(tmp_dir: Path, name: str, concepts: list[dict]) -> Path:
    model = {
        "$schema": "NPDevContract/schemas/model.schema.json",
        "namespace": "test.migrate.label.locales",
        "dslVersion": "1.0.0",
        "version": "1.0",
        "concepts": concepts,
    }
    path = tmp_dir / name
    path.write_text(json.dumps(model, indent=2), encoding="utf-8")
    return path


def _args(inputs: list[str], locale: str, write: bool, report: str | None = None) -> argparse.Namespace:
    return argparse.Namespace(input=inputs, locale=locale, write=write, report=report)


class MigrateLabelLocalesTest(unittest.TestCase):
    def test_dry_run_does_not_touch_the_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, "model.json", [
                {"name": "Order", "ui": {"label": "Order"}, "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]},
            ])
            before = model_path.read_text(encoding="utf-8")

            code = npdev_cli.run_migrate_label_locales(_args([str(model_path)], "en", write=False))

            self.assertEqual(0, code)
            self.assertEqual(before, model_path.read_text(encoding="utf-8"), "dry run must not write")

    def test_write_widens_plain_string_labels_to_per_locale_form(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, "model.json", [
                {"name": "Order", "ui": {"label": "Order", "shortLabel": "Ord"}, "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "status", "type": "string", "ui": {"label": "Status"}},
                ]},
            ])

            code = npdev_cli.run_migrate_label_locales(_args([str(model_path)], "en", write=True))

            self.assertEqual(0, code)
            model = json.loads(model_path.read_text(encoding="utf-8"))
            concept_ui = model["concepts"][0]["ui"]
            self.assertEqual({"default": "Order", "en": "Order"}, concept_ui["label"])
            self.assertEqual({"default": "Ord", "en": "Ord"}, concept_ui["shortLabel"])
            field_ui = model["concepts"][0]["fields"][1]["ui"]
            self.assertEqual({"default": "Status", "en": "Status"}, field_ui["label"])

    def test_idempotent_second_run_reports_no_further_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, "model.json", [
                {"name": "Order", "ui": {"label": "Order"}, "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]},
            ])
            npdev_cli.run_migrate_label_locales(_args([str(model_path)], "en", write=True))
            after_first = model_path.read_text(encoding="utf-8")

            npdev_cli.run_migrate_label_locales(_args([str(model_path)], "en", write=True))

            self.assertEqual(
                after_first, model_path.read_text(encoding="utf-8"),
                "an already-widened site must be left alone on a second run",
            )

    def test_blank_locale_is_a_hard_no_op(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, "model.json", [
                {"name": "Order", "ui": {"label": "Order"}, "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]},
            ])
            before = model_path.read_text(encoding="utf-8")

            code = npdev_cli.run_migrate_label_locales(_args([str(model_path)], "  ", write=True))

            self.assertEqual(0, code)
            self.assertEqual(before, model_path.read_text(encoding="utf-8"))

    def test_directory_input_is_searched_recursively(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            nested = tmp_dir / "nested"
            nested.mkdir()
            _write_model(nested, "model.json", [
                {"name": "Order", "ui": {"label": "Order"}, "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]},
            ])

            code = npdev_cli.run_migrate_label_locales(_args([str(tmp_dir)], "en", write=True))

            self.assertEqual(0, code)
            model = json.loads((nested / "model.json").read_text(encoding="utf-8"))
            self.assertEqual({"default": "Order", "en": "Order"}, model["concepts"][0]["ui"]["label"])

    def test_report_written_when_requested(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _write_model(tmp_dir, "model.json", [
                {"name": "Order", "ui": {"label": "Order"}, "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]},
            ])
            report_path = tmp_dir / "report.json"

            code = npdev_cli.run_migrate_label_locales(
                _args([str(model_path)], "en", write=False, report=str(report_path)))

            self.assertEqual(0, code)
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(1, len(report))
            self.assertEqual(str(model_path), report[0]["file"])
            self.assertTrue(report[0]["changed"])
            self.assertFalse(report[0]["isCompiled"])


if __name__ == "__main__":
    unittest.main()
