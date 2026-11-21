"""D-1 phase 4: the back office is the `admin` block of an experiences[] entry.

Covers the build-free `resolve_split_model` path (it mirrors ModelSourceResolver: a pack declares only `pages` in an
experiences[] `admin` entry, which are lifted, qualified `pack::Page`, after the model's own pages in the root admin
experience; the removed root/pack `backOffice` key is refused) and the `dsl_v2_migration` step that moves the old keys.

Run with: python -m unittest NPDevCli.tests.test_admin_experience -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
from dsl_v2_migration import migrate_document  # noqa: E402

PACK = {
    "dslVersion": "3.0.0", "pack": "support", "version": "1.0.0",
    "experiences": [
        {"name": "agent", "for": "SupportAgent"},
        {"name": "admin", "admin": {"pages": [{"name": "TicketOverview", "gadgets": []}]}},
    ],
}


def model(**extra) -> dict:
    base = {"namespace": "demo.bo", "dslVersion": "3.0.0", "version": "1.0",
            "packs": [{"$ref": "packs/support/pack.json"}]}
    base.update(extra)
    return base


def admin_of(resolved: dict) -> list:
    return [e for e in resolved.get("experiences", []) if "admin" in e]


class ResolveSplitModelAdminExperienceTest(unittest.TestCase):
    def resolve(self, root: dict, pack: dict = PACK) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "packs" / "support").mkdir(parents=True)
            (base / "packs" / "support" / "pack.json").write_text(json.dumps(pack), encoding="utf-8")
            (base / "model.json").write_text(json.dumps(root), encoding="utf-8")
            return npdev_cli.resolve_split_model(base / "model.json")

    def test_a_packs_pages_are_qualified_and_follow_the_models_own_pages(self):
        own = {"name": "admin", "admin": {"defaultRoute": "X", "pages": [{"name": "Own"}]}}
        resolved = self.resolve(model(experiences=[own]))
        (admin,) = admin_of(resolved)
        self.assertEqual("X", admin["admin"]["defaultRoute"])
        self.assertEqual(["Own", "support::TicketOverview"], [p["name"] for p in admin["admin"]["pages"]])
        self.assertNotIn(npdev_cli.PACK_PAGES_KEY, resolved)
        self.assertNotIn("backOffice", resolved)

    def test_the_packs_admin_entry_is_not_composed_as_an_experience_but_its_other_experiences_are(self):
        resolved = self.resolve(model())
        self.assertEqual(["agent", "admin"], [e["name"] for e in resolved["experiences"]])
        self.assertEqual(1, len(admin_of(resolved)))

    def test_a_model_without_an_admin_experience_gets_one_only_from_a_pack(self):
        resolved = self.resolve(model())
        (admin,) = admin_of(resolved)
        self.assertEqual("admin", admin["name"])
        self.assertEqual(["support::TicketOverview"], [p["name"] for p in admin["admin"]["pages"]])

    def test_a_model_without_any_back_office_content_gets_no_admin_experience(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model.json"
            path.write_text(json.dumps({"namespace": "demo.bo", "dslVersion": "3.0.0", "version": "1.0"}), encoding="utf-8")
            self.assertEqual([], admin_of(npdev_cli.resolve_split_model(path)))

    def test_the_removed_root_keys_are_refused_with_a_pointer(self):
        for key, value in (("backOffice", {"pages": []}), ("appShell", {"defaultRoute": "X"}), ("guidePages", [])):
            with self.assertRaises(npdev_cli.CliError) as caught:
                self.resolve(model(**{key: value}))
            self.assertIn("admin", str(caught.exception))

    def test_a_pack_backoffice_key_is_refused(self):
        with self.assertRaises(npdev_cli.CliError) as caught:
            self.resolve(model(), {**PACK, "backOffice": {"pages": []}})
        self.assertIn("admin", str(caught.exception))

    def test_a_pack_admin_block_may_only_declare_pages(self):
        pack = {**PACK, "experiences": [{"name": "admin", "admin": {"locale": "en", "pages": []}}]}
        with self.assertRaises(npdev_cli.CliError) as caught:
            self.resolve(model(), pack)
        self.assertIn("pages", str(caught.exception))

    def test_a_pack_with_two_admin_entries_is_refused(self):
        entry = {"name": "admin", "admin": {"pages": []}}
        pack = {**PACK, "experiences": [entry, {**entry, "name": "admin2"}]}
        with self.assertRaises(npdev_cli.CliError):
            self.resolve(model(), pack)


class BackOfficeMigrationTest(unittest.TestCase):
    def test_root_backoffice_becomes_the_admin_experience(self):
        doc = {"namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
               "backOffice": {"locale": "en", "defaultRoute": "A", "pages": [{"name": "P"}]},
               "experiences": [{"name": "agent", "for": "Agent"}]}
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        self.assertNotIn("backOffice", doc)
        self.assertEqual(["agent", "admin"], [e["name"] for e in doc["experiences"]])
        self.assertEqual({"locale": "en", "defaultRoute": "A", "pages": [{"name": "P"}]}, doc["experiences"][1]["admin"])

    def test_pre_p5_t9c_keys_fold_into_the_same_entry(self):
        doc = {"namespace": "demo", "dslVersion": "3.0.0", "version": "1.0",
               "appShell": {"defaultRoute": "A", "navigation": [{"label": "L"}], "ignored": 1},
               "guidePages": [{"name": "P"}], "settings": {"locale": "pt-BR", "strings": {"k": "v"}}}
        migrate_document(doc)
        self.assertEqual({"experiences", "namespace", "dslVersion", "version"}, set(doc))
        self.assertNotIn("appShell", doc)
        self.assertNotIn("guidePages", doc)
        self.assertNotIn("settings", doc)
        self.assertEqual({"defaultRoute": "A", "navigation": [{"label": "L"}], "pages": [{"name": "P"}],
                          "locale": "pt-BR", "strings": {"k": "v"}}, doc["experiences"][0]["admin"])

    def test_a_pack_keeps_only_its_pages(self):
        doc = {"dslVersion": "3.0.0", "pack": "support", "version": "1.0.0", "guidePages": [{"name": "P"}]}
        migrate_document(doc)
        self.assertEqual([{"name": "admin", "admin": {"pages": [{"name": "P"}]}}], doc["experiences"])
        self.assertNotIn("guidePages", doc)

    def test_an_existing_admin_experience_is_reported_not_overwritten(self):
        doc = {"namespace": "demo", "dslVersion": "3.0.0", "version": "1.0", "backOffice": {"pages": []},
               "experiences": [{"name": "admin", "admin": {"pages": []}}]}
        result = migrate_document(doc)
        self.assertIn("backOffice", doc)
        self.assertTrue(result.ambiguities)

    def test_a_document_without_back_office_keys_is_untouched(self):
        doc = {"namespace": "demo", "dslVersion": "3.0.0", "version": "1.0", "concepts": []}
        self.assertFalse(migrate_document(doc).changed)
        self.assertNotIn("experiences", doc)


if __name__ == "__main__":
    unittest.main()
