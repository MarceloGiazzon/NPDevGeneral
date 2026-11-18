"""Tests for selectors_to_picker_codemod (FORM P5-T9a): selectors[] -> screens[] kind "picker".

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_selectors_to_picker_codemod -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import selectors_to_picker_codemod as codemod  # noqa: E402

SELECTOR = {
    "name": "WidgetCatalogPicker", "concept": "WidgetCatalogEntry", "multiSelect": True,
    "filters": ["sku", "displayName"], "columns": ["sku", "displayName"], "orderBy": ["sku"],
    "filter": "discontinued == false", "returnMapping": {"sku": "sku"},
    "metadata": {"purpose": "a comment"},
}

WITHOUT_SCREENS = """{
  "namespace": "demo",
  "concepts": [],
  "selectors": [
    {
      "name": "P",
      "concept": "C",
      "filter": "a == 'x'"
    }
  ],
  "flows": []
}
"""

WITH_SCREENS = """{
  "namespace": "demo",
  "selectors": [ {"name": "P", "concept": "C"} ],
  "screens": [
    {
      "name": "home",
      "kind": "list",
      "concept": "C"
    }
  ]
}
"""


class SelectorToScreenTest(unittest.TestCase):
    def test_every_key_maps_and_filter_becomes_where(self):
        notes: list[str] = []
        screen = codemod.selector_to_screen(SELECTOR, notes)
        self.assertEqual(screen, {
            "name": "WidgetCatalogPicker", "kind": "picker", "concept": "WidgetCatalogEntry", "multiSelect": True,
            "filters": ["sku", "displayName"], "columns": ["sku", "displayName"], "returnMapping": {"sku": "sku"},
            "orderBy": ["sku"], "where": "discontinued == false",
        })
        self.assertEqual(len(notes), 1)
        self.assertIn("metadata dropped", notes[0])

    def test_minimal_selector_and_unknown_key(self):
        notes: list[str] = []
        screen = codemod.selector_to_screen({"name": "P", "concept": "C", "mystery": 1}, notes)
        self.assertEqual(screen, {"name": "P", "kind": "picker", "concept": "C"})
        self.assertIn("unknown key 'mystery' dropped", notes[0])


class MigrateTextTest(unittest.TestCase):
    def test_replaces_the_selectors_member_in_place_when_there_is_no_screens_array(self):
        out, report = codemod.migrate_text(WITHOUT_SCREENS)
        self.assertEqual(report.migrated, 1)
        data = json.loads(out)
        self.assertNotIn("selectors", data)
        self.assertEqual(data["screens"], [{"name": "P", "kind": "picker", "concept": "C", "where": "a == 'x'"}])
        self.assertEqual(list(data), ["namespace", "concepts", "screens", "flows"], "member order is kept")
        self.assertEqual(out.count('"flows"'), 1)

    def test_appends_to_an_existing_screens_array_and_keeps_the_rest_byte_identical(self):
        out, report = codemod.migrate_text(WITH_SCREENS)
        data = json.loads(out)
        self.assertEqual([s["name"] for s in data["screens"]], ["home", "P"])
        self.assertEqual(data["screens"][1]["kind"], "picker")
        self.assertNotIn("selectors", data)
        self.assertIn('"name": "home",\n      "kind": "list",\n      "concept": "C"\n    },', out)

    def test_selectors_declared_last_is_removed_without_a_dangling_comma(self):
        text = '{\n  "screens": [],\n  "selectors": [ {"name": "P", "concept": "C"} ]\n}\n'
        out, _ = codemod.migrate_text(text)
        self.assertEqual(json.loads(out)["screens"][0]["name"], "P")
        self.assertNotIn("selectors", json.loads(out))

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
        text = '{"selectors": [{"name": "Home", "concept": "C"}], "screens": [{"name": "home", "kind": "list"}]}'
        out, report = codemod.migrate_text(text)
        self.assertEqual(out, text)
        self.assertTrue(report.manual)
        self.assertFalse(report.changed)

    def test_strings_containing_braces_and_the_word_selectors_do_not_confuse_the_scan(self):
        text = ('{"note": "} \\"selectors\\": [ {", "selectors": [{"name": "P", "concept": "C", '
                '"filter": "a == \'}\'"}], "z": 1}')
        out, report = codemod.migrate_text(text)
        data = json.loads(out)
        self.assertEqual(report.migrated, 1)
        self.assertEqual(data["z"], 1)
        self.assertEqual(data["screens"][0]["where"], "a == '}'")
        self.assertEqual(data["note"], '} "selectors": [ {')

    def test_not_a_model_or_no_selectors_is_untouched(self):
        for text in ("[1, 2]", "not json", '{"a": 1}'):
            out, report = codemod.migrate_text(text)
            self.assertEqual(out, text)
            self.assertEqual(report.migrated, 0)

    def test_malformed_selectors_member_is_manual(self):
        out, report = codemod.migrate_text('{"selectors": {"name": "P"}}')
        self.assertEqual(out, '{"selectors": {"name": "P"}}')
        self.assertTrue(report.manual)


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
            self.assertNotIn("selectors", json.loads(model.read_text(encoding="utf-8")))
            saved = [p for p in backup.rglob("model.json")]
            self.assertEqual(len(saved), 1)
            self.assertEqual(saved[0].read_text(encoding="utf-8"), WITHOUT_SCREENS, "the backup is the original")
            self.assertIn('"selectors"', (root / "ai-model.json").read_text(encoding="utf-8"), "ai-model.json is skipped")
            self.assertIn('"selectors"', (skipped / "model.json").read_text(encoding="utf-8"), "Output/ is skipped")

    def test_main_exit_code_is_nonzero_only_for_manual_cases(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok = Path(tmp) / "ok.json"
            ok.write_text(WITHOUT_SCREENS, encoding="utf-8", newline="")
            self.assertEqual(codemod.main(["--root", str(ok)]), 0)
            bad = Path(tmp) / "bad.json"
            bad.write_text('{"selectors": {"name": "P"}}', encoding="utf-8")
            self.assertEqual(codemod.main(["--root", str(bad)]), 1)
            self.assertEqual(codemod.main(["--root", str(Path(tmp) / "nothing-here")]), 0)


if __name__ == "__main__":
    unittest.main()
