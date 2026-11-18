"""Tests for appshell_guidepages_to_backoffice_codemod (FORM P5-T9c): appShell + guidePages -> root backOffice.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_appshell_guidepages_to_backoffice_codemod -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import appshell_guidepages_to_backoffice_codemod as codemod  # noqa: E402

BOTH = """{
  "namespace": "demo",
  "dslVersion": "3.0.0",
  "appShell": {
    "defaultRoute": "Order",
    "navigation": [
      { "label": "Orders", "target": "Order", "group": "Ops" }
    ]
  },
  "concepts": [],
  "guidePages": [
    {
      "name": "Guide",
      "default": true,
      "gadgets": [ { "name": "g", "type": "kpi", "query": "Q" } ]
    }
  ],
  "flows": []
}
"""

PAGES_ONLY = """{
  "namespace": "demo",
  "dslVersion": "3.0.0",
  "concepts": [],
  "guidePages": [ { "name": "Guide" } ]
}
"""

PACK = """{
  "pack": "labeling",
  "dslVersion": "3.0.0",
  "guidePages": [
    { "name": "Overview", "gadgets": [] }
  ],
  "queries": []
}
"""


def loads(text: str) -> dict:
    return json.loads(text)


class ModelMigrationTest(unittest.TestCase):
    def test_both_old_keys_become_one_root_backoffice(self):
        new_text, report = codemod.migrate_text(BOTH)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("appShell", data)
        self.assertNotIn("guidePages", data)
        bo = data["backOffice"]
        self.assertEqual(["defaultRoute", "navigation", "pages"], list(bo))
        self.assertEqual("Order", bo["defaultRoute"])
        self.assertEqual([{"label": "Orders", "target": "Order", "group": "Ops"}], bo["navigation"])
        self.assertEqual("Guide", bo["pages"][0]["name"])
        self.assertEqual(True, bo["pages"][0]["default"])

    def test_the_new_member_takes_the_old_appshell_position(self):
        new_text, _ = codemod.migrate_text(BOTH)
        self.assertEqual(["namespace", "dslVersion", "backOffice", "concepts", "flows"], list(loads(new_text)))

    def test_everything_else_in_the_file_is_untouched(self):
        new_text, _ = codemod.migrate_text(BOTH)
        data = loads(new_text)
        self.assertEqual([], data["flows"])
        self.assertEqual([], data["concepts"])
        self.assertEqual("demo", data["namespace"])

    def test_it_is_idempotent(self):
        once, _ = codemod.migrate_text(BOTH)
        twice, report = codemod.migrate_text(once)
        self.assertEqual(once, twice)
        self.assertEqual(0, report.migrated)

    def test_only_pages_gives_a_pages_only_object_in_the_old_position(self):
        new_text, report = codemod.migrate_text(PAGES_ONLY)
        self.assertTrue(report.changed)
        data = loads(new_text)
        self.assertEqual(["pages"], list(data["backOffice"]))
        self.assertEqual(["namespace", "dslVersion", "concepts", "backOffice"], list(data))

    def test_only_a_shell_gives_a_shell_only_object(self):
        text = BOTH.replace('''  "guidePages": [
    {
      "name": "Guide",
      "default": true,
      "gadgets": [ { "name": "g", "type": "kpi", "query": "Q" } ]
    }
  ],
''', "")
        new_text, report = codemod.migrate_text(text)
        self.assertTrue(report.changed)
        self.assertEqual(["defaultRoute", "navigation"], list(loads(new_text)["backOffice"]))

    def test_empty_old_keys_are_just_removed(self):
        text = PAGES_ONLY.replace('[ { "name": "Guide" } ]', "[]")
        new_text, report = codemod.migrate_text(text)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("guidePages", data)
        self.assertNotIn("backOffice", data)

    def test_a_model_that_already_has_backoffice_is_manual_and_untouched(self):
        text = PAGES_ONLY.replace('"concepts": [],', '"concepts": [], "backOffice": {},')
        new_text, report = codemod.migrate_text(text)
        self.assertEqual(text, new_text)
        self.assertTrue(report.manual)

    def test_a_model_without_either_old_key_is_untouched(self):
        text = '{ "namespace": "x", "dslVersion": "3.0.0" }'
        new_text, report = codemod.migrate_text(text)
        self.assertEqual(text, new_text)
        self.assertEqual(0, report.migrated)

    def test_crlf_files_keep_their_line_endings(self):
        crlf = BOTH.replace("\n", "\r\n")
        new_text, report = codemod.migrate_text(crlf)
        self.assertTrue(report.changed)
        self.assertEqual(new_text.count("\n"), new_text.count("\r\n"))
        self.assertEqual("Guide", loads(new_text)["backOffice"]["pages"][0]["name"])


SETTINGS = '''{
  "namespace": "x",
  "dslVersion": "3.0.0",
  "settings": { "locale": "pt-BR", "strings": { "action.save": "Salvar" }, "ui": { "pageRows": 25, "dateFormat": "yyyy-MM-dd" } },
  "concepts": []
}
'''


class SettingsMigrationTest(unittest.TestCase):
    def test_settings_locale_and_strings_become_backoffice_and_ui_is_dropped(self):
        new_text, report = codemod.migrate_text(SETTINGS)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("settings", data)
        self.assertEqual({"locale": "pt-BR", "strings": {"action.save": "Salvar"}}, data["backOffice"])
        self.assertEqual([], data["concepts"])

    def test_settings_with_only_ui_just_disappears(self):
        text = SETTINGS.replace('"locale": "pt-BR", "strings": { "action.save": "Salvar" }, ', "")
        new_text, report = codemod.migrate_text(text)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("settings", data)
        self.assertNotIn("backOffice", data)

    def test_settings_fold_into_an_existing_backoffice(self):
        text = SETTINGS.replace('"concepts": []', '"concepts": [], "backOffice": { "defaultRoute": "Order" }')
        new_text, report = codemod.migrate_text(text)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("settings", data)
        self.assertEqual({"defaultRoute": "Order", "locale": "pt-BR", "strings": {"action.save": "Salvar"}}, data["backOffice"])

    def test_a_clashing_existing_backoffice_is_manual_and_untouched(self):
        text = SETTINGS.replace('"concepts": []', '"concepts": [], "backOffice": { "locale": "en" }')
        new_text, report = codemod.migrate_text(text)
        self.assertEqual(text, new_text)
        self.assertTrue(report.manual)

    def test_the_settings_rewrite_is_idempotent(self):
        once, _ = codemod.migrate_text(SETTINGS)
        twice, report = codemod.migrate_text(once)
        self.assertEqual(once, twice)
        self.assertEqual(0, report.migrated)


class PackMigrationTest(unittest.TestCase):
    def test_a_pack_guidepages_becomes_a_pack_level_backoffice_pages(self):
        new_text, report = codemod.migrate_text(PACK)
        data = loads(new_text)
        self.assertTrue(report.changed)
        self.assertNotIn("guidePages", data)
        self.assertEqual([{"name": "Overview", "gadgets": []}], data["backOffice"]["pages"])
        self.assertEqual([], data["queries"])

    def test_the_pack_rewrite_is_idempotent(self):
        once, _ = codemod.migrate_text(PACK)
        twice, report = codemod.migrate_text(once)
        self.assertEqual(once, twice)
        self.assertEqual(0, report.migrated)


class DriverTest(unittest.TestCase):
    def test_dry_run_writes_nothing_and_apply_writes_with_a_backup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "app"
            root.mkdir()
            model = root / "model.json"
            model.write_text(BOTH, encoding="utf-8")
            codemod.run([root], apply=False, backup_dir=None)
            self.assertEqual(BOTH, model.read_text(encoding="utf-8"))
            backup = Path(tmp) / "backup"
            reports = codemod.run([root], apply=True, backup_dir=backup)
            self.assertEqual(1, len(reports))
            self.assertNotIn("guidePages", model.read_text(encoding="utf-8"))
            backed_up = list(backup.rglob("model.json"))
            self.assertEqual(1, len(backed_up))
            self.assertEqual(BOTH, backed_up[0].read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
