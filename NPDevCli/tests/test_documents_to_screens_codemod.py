"""Tests for documents_to_screens_codemod (FORM P5-T9b): documents[] -> screens[] kind "document".

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_documents_to_screens_codemod -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import documents_to_screens_codemod as codemod  # noqa: E402

ONE_RECORD = {
    "name": "Invoice", "concept": "Order", "aggregate": "OrderAggregate", "title": "Invoice", "pageSize": "A4",
    "marginMm": 15, "logo": {"field": "companyLogo"},
    "bands": [{"name": "header", "kind": "header", "fields": [{"field": "number"}]}],
    "metadata": {"purpose": "a comment"},
}
REPORT = {
    "name": "PriceReport", "concept": "Entry", "kind": "list", "title": "Prices", "query": "OwnedBy",
    "columns": [{"field": "sku"}, {"field": "price", "total": "sum"}], "groupBy": ["owner"],
    "subtotals": True, "grandTotal": True,
}

WITHOUT_SCREENS = """{
  "namespace": "demo",
  "concepts": [],
  "documents": [
    {
      "name": "P",
      "concept": "C",
      "title": "Print"
    }
  ],
  "flows": []
}
"""

WITH_SCREENS = """{
  "namespace": "demo",
  "documents": [ {"name": "P", "concept": "C"} ],
  "screens": [
    {
      "name": "home",
      "kind": "list",
      "concept": "C"
    }
  ]
}
"""


class DocumentToScreenTest(unittest.TestCase):
    def test_a_one_record_document_keeps_every_key_and_gains_the_kind(self):
        report = codemod.Report("x")
        screen = codemod.document_to_screen(ONE_RECORD, report)
        self.assertEqual(screen["kind"], "document")
        self.assertEqual(list(screen)[:2], ["name", "kind"])
        for key, value in ONE_RECORD.items():
            if key != "name":
                self.assertEqual(screen[key], value, key)
        self.assertNotIn("report", screen)
        self.assertFalse(report.manual)

    def test_kind_list_becomes_report_true_and_columns_become_printColumns(self):
        report = codemod.Report("x")
        screen = codemod.document_to_screen(REPORT, report)
        self.assertEqual(screen, {
            "name": "PriceReport", "kind": "document", "concept": "Entry", "report": True, "title": "Prices",
            "query": "OwnedBy", "printColumns": [{"field": "sku"}, {"field": "price", "total": "sum"}],
            "groupBy": ["owner"], "subtotals": True, "grandTotal": True,
        })
        self.assertNotIn("columns", screen)

    def test_an_unknown_kind_is_manual(self):
        report = codemod.Report("x")
        codemod.document_to_screen({"name": "D", "concept": "C", "kind": "matrix"}, report)
        self.assertIn("not 'list'", report.manual[0])


class MigrateTextTest(unittest.TestCase):
    def test_replaces_the_documents_member_in_place_when_there_is_no_screens_array(self):
        out, report = codemod.migrate_text(WITHOUT_SCREENS)
        self.assertEqual(report.migrated, 1)
        data = json.loads(out)
        self.assertNotIn("documents", data)
        self.assertEqual(data["screens"], [{"name": "P", "kind": "document", "concept": "C", "title": "Print"}])
        self.assertEqual(list(data), ["namespace", "concepts", "screens", "flows"], "member order is kept")
        self.assertEqual(out.count('"flows"'), 1)

    def test_appends_to_an_existing_screens_array_and_keeps_the_rest_byte_identical(self):
        out, report = codemod.migrate_text(WITH_SCREENS)
        data = json.loads(out)
        self.assertEqual([s["name"] for s in data["screens"]], ["home", "P"])
        self.assertEqual(data["screens"][1]["kind"], "document")
        self.assertNotIn("documents", data)
        self.assertIn('"name": "home",\n      "kind": "list",\n      "concept": "C"\n    },', out)

    def test_documents_declared_last_is_removed_without_a_dangling_comma(self):
        text = '{\n  "screens": [],\n  "documents": [ {"name": "P", "concept": "C"} ]\n}\n'
        out, _ = codemod.migrate_text(text)
        self.assertEqual(json.loads(out)["screens"][0]["name"], "P")
        self.assertNotIn("documents", json.loads(out))

    def test_crlf_files_stay_crlf(self):
        out, _ = codemod.migrate_text(WITHOUT_SCREENS.replace("\n", "\r\n"))
        self.assertEqual(out.count("\r\n"), out.count("\n"))
        self.assertEqual(json.loads(out)["screens"][0]["name"], "P")

    def test_idempotent(self):
        once, _ = codemod.migrate_text(WITHOUT_SCREENS)
        twice, report = codemod.migrate_text(once)
        self.assertEqual(once, twice)
        self.assertEqual(report.migrated, 0)

    def test_name_collision_with_an_existing_screen_is_manual_and_leaves_the_text_alone(self):
        text = '{"documents": [{"name": "Home", "concept": "C"}], "screens": [{"name": "home", "kind": "list"}]}'
        out, report = codemod.migrate_text(text)
        self.assertEqual(out, text)
        self.assertTrue(report.manual)
        self.assertFalse(report.changed)

    def test_a_name_that_is_not_a_legal_screen_name_is_manual(self):
        text = '{"documents": [{"name": "my_doc", "concept": "C"}]}'
        out, report = codemod.migrate_text(text)
        self.assertEqual(out, text)
        self.assertIn("not a legal screen name", report.manual[0])

    def test_a_ref_entry_is_manual(self):
        text = '{"documents": [{"$ref": "fragments/docs.json"}]}'
        out, report = codemod.migrate_text(text)
        self.assertEqual(out, text)
        self.assertTrue(report.manual)

    def test_strings_containing_braces_and_the_word_documents_do_not_confuse_the_scan(self):
        text = ('{"note": "} \\"documents\\": [ {", "documents": [{"name": "P", "concept": "C", '
                '"title": "a } b"}], "z": 1}')
        out, report = codemod.migrate_text(text)
        data = json.loads(out)
        self.assertEqual(report.migrated, 1)
        self.assertEqual(data["z"], 1)
        self.assertEqual(data["screens"][0]["title"], "a } b")
        self.assertEqual(data["note"], '} "documents": [ {')

    def test_not_a_model_or_no_documents_is_untouched(self):
        for text in ("[1, 2]", "not json", '{"a": 1}', '{"documents": []}', '{"documents": ["a", "b"]}'):
            out, report = codemod.migrate_text(text)
            self.assertEqual(out, text)
            self.assertEqual(report.migrated, 0)
            self.assertFalse(report.manual)


class RunTest(unittest.TestCase):
    def test_dry_run_writes_nothing_apply_writes_and_backs_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "app" / "Input"
            root.mkdir(parents=True)
            model = root / "model.json"
            model.write_text(WITHOUT_SCREENS, encoding="utf-8", newline="")
            (root / "ai-model.json").write_text(WITHOUT_SCREENS, encoding="utf-8", newline="")
            skipped = Path(tmp) / "app" / "Output"
            skipped.mkdir()
            (skipped / "model.json").write_text(WITHOUT_SCREENS, encoding="utf-8", newline="")

            reports = codemod.run([Path(tmp)], apply=False, backup_dir=None)
            self.assertEqual([Path(r.path).name for r in reports], ["model.json"])
            self.assertEqual(model.read_text(encoding="utf-8"), WITHOUT_SCREENS, "dry run does not write")

            backup = Path(tmp) / "backup"
            codemod.run([Path(tmp)], apply=True, backup_dir=backup)
            self.assertNotIn("documents", json.loads(model.read_text(encoding="utf-8")))
            saved = [p for p in backup.rglob("model.json")]
            self.assertEqual(len(saved), 1)
            self.assertEqual(saved[0].read_text(encoding="utf-8"), WITHOUT_SCREENS, "the backup is the original")
            self.assertIn('"documents"', (root / "ai-model.json").read_text(encoding="utf-8"), "ai-model.json is skipped")
            self.assertIn('"documents"', (skipped / "model.json").read_text(encoding="utf-8"), "Output/ is skipped")

    def test_main_exit_code_is_nonzero_only_for_manual_cases(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok = Path(tmp) / "ok.json"
            ok.write_text(WITHOUT_SCREENS, encoding="utf-8", newline="")
            self.assertEqual(codemod.main(["--root", str(ok)]), 0)
            bad = Path(tmp) / "bad.json"
            bad.write_text('{"documents": [{"name": "bad_name", "concept": "C"}]}', encoding="utf-8")
            self.assertEqual(codemod.main(["--root", str(bad)]), 1)
            self.assertEqual(codemod.main(["--root", str(Path(tmp) / "nothing-here")]), 0)


if __name__ == "__main__":
    unittest.main()
