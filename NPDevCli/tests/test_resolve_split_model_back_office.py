"""FORM P5-T9c: the build-free `resolve_split_model` path mirrors ModelSourceResolver for the back office.

A pack declares a pack-level `backOffice.pages`; the resolved model carries them, qualified `pack::Page`, after the
model's own pages in the root `backOffice.pages`. The removed root keys `appShell` / `guidePages` are refused with a pointer.

Run with: python -m unittest NPDevCli.tests.test_resolve_split_model_back_office -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

PACK = {
    "dslVersion": "3.0.0", "pack": "support", "version": "1.0.0",
    "backOffice": {"pages": [{"name": "TicketOverview", "gadgets": []}]},
}


def model(**extra) -> dict:
    base = {"namespace": "demo.bo", "dslVersion": "3.0.0", "version": "1.0",
            "packs": [{"$ref": "packs/support/pack.json"}]}
    base.update(extra)
    return base


class ResolveSplitModelBackOfficeTest(unittest.TestCase):
    def resolve(self, root: dict) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "packs" / "support").mkdir(parents=True)
            (base / "packs" / "support" / "pack.json").write_text(json.dumps(PACK), encoding="utf-8")
            (base / "model.json").write_text(json.dumps(root), encoding="utf-8")
            return npdev_cli.resolve_split_model(base / "model.json")

    def test_a_packs_pages_are_qualified_and_follow_the_models_own_pages(self):
        resolved = self.resolve(model(backOffice={"defaultRoute": "X", "pages": [{"name": "Own"}]}))
        self.assertEqual("X", resolved["backOffice"]["defaultRoute"])
        self.assertEqual(["Own", "support::TicketOverview"], [p["name"] for p in resolved["backOffice"]["pages"]])
        self.assertNotIn(npdev_cli.PACK_PAGES_KEY, resolved)

    def test_a_model_without_backoffice_gets_one_only_from_a_pack(self):
        resolved = self.resolve(model())
        self.assertEqual(["support::TicketOverview"], [p["name"] for p in resolved["backOffice"]["pages"]])

    def test_a_model_without_any_back_office_content_gets_no_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model.json"
            path.write_text(json.dumps({"namespace": "demo.bo", "dslVersion": "3.0.0", "version": "1.0"}), encoding="utf-8")
            self.assertNotIn("backOffice", npdev_cli.resolve_split_model(path))

    def test_the_removed_root_keys_are_refused_with_a_pointer(self):
        for key, value in (("appShell", {"defaultRoute": "X"}), ("guidePages", [])):
            with self.assertRaises(npdev_cli.CliError) as caught:
                self.resolve(model(**{key: value}))
            self.assertIn("backOffice", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
