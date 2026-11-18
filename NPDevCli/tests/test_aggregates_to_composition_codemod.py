"""FORM P5-T9e: the aggregates[] -> concepts[].composition codemod."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import aggregates_to_composition_codemod as codemod  # noqa: E402


def loads(text):
    return json.loads(text)


MODEL = '''{
  "namespace": "x",
  "dslVersion": "3.0.0",
  "concepts": [
    { "name": "Order", "fields": [ { "name": "id", "type": "uuid" } ] },
    { "name": "OrderLine", "fields": [] }
  ],
  "aggregates": [
    { "name": "OrderAggregate", "root": "Order",
      "collections": [ { "name": "lines", "concept": "OrderLine", "childField": "orderId" } ],
      "onValidate": "CheckOrder" }
  ],
  "autoPanels": [ { "name": "OrderWorkbench", "aggregate": "OrderAggregate" } ],
  "queries": [ { "name": "Q", "concept": "Order", "aggregates": [ { "name": "total", "fn": "count" } ] } ]
}
'''


class MigrateTextTest(unittest.TestCase):
    def test_the_aggregate_moves_onto_its_root_concept_and_references_are_renamed(self):
        new_text, report = codemod.migrate_text(MODEL)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("aggregates", data)
        order = data["concepts"][0]
        self.assertEqual("lines", order["composition"]["collections"][0]["name"])
        self.assertEqual("CheckOrder", order["composition"]["onValidate"])
        self.assertNotIn("name", order["composition"])
        self.assertNotIn("root", order["composition"])
        self.assertNotIn("composition", data["concepts"][1])
        self.assertEqual("Order", data["autoPanels"][0]["aggregate"])

    def test_a_queries_own_aggregates_list_is_left_alone(self):
        data = loads(codemod.migrate_text(MODEL)[0])
        self.assertEqual("count", data["queries"][0]["aggregates"][0]["fn"])

    def test_the_rewrite_is_idempotent(self):
        once, _ = codemod.migrate_text(MODEL)
        twice, report = codemod.migrate_text(once)
        self.assertEqual(once, twice)
        self.assertEqual(0, report.migrated)

    def test_a_name_equal_to_the_root_renames_nothing(self):
        text = MODEL.replace("OrderAggregate", "Order")
        new_text, report = codemod.migrate_text(text)
        self.assertTrue(report.changed)
        self.assertEqual("Order", loads(new_text)["autoPanels"][0]["aggregate"])

    def test_two_aggregates_on_one_root_are_manual_and_untouched(self):
        text = MODEL.replace('"onValidate": "CheckOrder" }', '"onValidate": "CheckOrder" },\n    { "name": "Other", "root": "Order" }')
        new_text, report = codemod.migrate_text(text)
        self.assertEqual(text, new_text)
        self.assertTrue(report.manual)

    def test_a_root_concept_that_is_not_inline_is_manual(self):
        text = MODEL.replace('"root": "Order"', '"root": "Elsewhere"')
        new_text, report = codemod.migrate_text(text)
        self.assertEqual(text, new_text)
        self.assertTrue(report.manual)

    def test_crlf_files_keep_their_line_endings(self):
        new_text, report = codemod.migrate_text(MODEL.replace("\n", "\r\n"))
        self.assertTrue(report.changed)
        self.assertEqual(new_text.count("\n"), new_text.count("\r\n"))

    def test_a_model_without_aggregates_is_untouched(self):
        text = '{ "namespace": "x", "dslVersion": "3.0.0", "concepts": [] }'
        new_text, report = codemod.migrate_text(text)
        self.assertEqual(text, new_text)
        self.assertEqual(0, report.migrated)


class RefFileTest(unittest.TestCase):
    def test_a_root_concept_in_a_ref_file_receives_the_composition(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "concepts").mkdir()
            concept = root / "concepts" / "Order.json"
            concept.write_text('{\n  "name": "Order",\n  "fields": []\n}\n', encoding="utf-8")
            model = root / "model.json"
            model.write_text('''{
  "namespace": "x", "dslVersion": "3.0.0",
  "concepts": [ { "$ref": "concepts/Order.json" } ],
  "aggregates": [ { "name": "OrderAggregate", "root": "Order", "collections": [] } ]
}
''', encoding="utf-8")
            backup = root / "backup"
            reports = codemod.run([model], apply=True, backup_dir=backup)
            self.assertEqual(1, len(reports))
            self.assertEqual([], reports[0].manual)
            self.assertEqual({"collections": []}, loads(concept.read_text(encoding="utf-8"))["composition"])
            self.assertNotIn("aggregates", loads(model.read_text(encoding="utf-8")))
            self.assertEqual(2, len(list(backup.rglob("*.json"))))

    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp) / "model.json"
            model.write_text(MODEL, encoding="utf-8")
            codemod.run([model], apply=False, backup_dir=None)
            self.assertEqual(MODEL, model.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
