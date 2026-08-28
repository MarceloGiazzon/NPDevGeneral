"""C3b (Cold Clone Audit): `npdev_static_pages.py` is a pure-Python port of the six PowerShell
page generators (scripts/appgen/New-*Page.ps1) `npdev generate app`/`npdev dev` used to shell out
to -- meaning every one of those apps needed PowerShell just to get a working info.html on a
Linux/macOS machine. This module removes that dependency for those six pages.

The templates themselves were extracted byte-for-byte from each PS1 script's here-string and
verified, by hand, against real `pwsh` output run against this exact fixture (2026-08-28) -- every
emitted page was content-identical modulo line-ending style (a platform artifact of PowerShell's
own `[Environment]::NewLine`, not something either implementation should chase), with one favorable
divergence: this module's JSON round-trip does not have PowerShell's ConvertFrom-Json/ConvertTo-Json
single-element-array-collapse quirk (see npdev_static_pages.py's module docstring). These tests lock
that verified behaviour going forward, using the same fixture model.

Run with:
    python -m unittest NPDevCli.tests.test_static_pages -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_static_pages as sp  # noqa: E402

_SAMPLE_MODEL_DIR = Path(__file__).resolve().parent.parent.parent / "NPDevSamples" / "npdev-init-seed"


def _copy_sample_app(target: Path) -> Path:
    target.mkdir(parents=True, exist_ok=True)
    for name in ("model.json", "config.json", "db.definition.json"):
        (target / name).write_text((_SAMPLE_MODEL_DIR / name).read_text(encoding="utf-8"),
                                    encoding="utf-8")
    return target


class StaticPagesFixtureTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory(prefix="npdev-static-pages-")
        self.tmp_path = Path(self._tmp.name)
        self.app_folder = _copy_sample_app(self.tmp_path / "app")
        self.static_dir = self.tmp_path / "out" / "static"
        self.ops_dir = self.tmp_path / "out" / "_ops"
        self.ops_dir.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        self._tmp.cleanup()

    def test_control_panel_page_substitutes_all_three_placeholders(self):
        dest = sp.emit_control_panel_page(self.static_dir, "myapp", 8080, self.tmp_path / "out")
        html = dest.read_text(encoding="utf-8")
        self.assertNotIn("__APP__", html)
        self.assertNotIn("__BASE__", html)
        self.assertNotIn("__KEYFILEPATH__", html)
        self.assertIn("myapp", html)
        self.assertIn("http://localhost:8080", html)
        # Also written to out_root directly, per the PS1 original's dual-write.
        self.assertTrue((self.tmp_path / "out" / "control-panel.html").is_file())

    def test_agent_prompter_and_properties_pages_substitute_app_id(self):
        for emit, filename in (
            (lambda: sp.emit_agent_prompter_page(self.static_dir, "myapp"), "agent-prompter.html"),
            (lambda: sp.emit_properties_admin_page(self.static_dir, "myapp"), "properties.html"),
        ):
            with self.subTest(filename=filename):
                dest = emit()
                self.assertEqual(filename, dest.name)
                html = dest.read_text(encoding="utf-8")
                self.assertNotIn("__APP__", html)
                self.assertIn("myapp", html)

    def test_app_tree_page_writes_html_and_json_from_the_real_model(self):
        dest = sp.emit_app_tree_page(self.app_folder, self.static_dir, "myapp")
        self.assertEqual("app-tree.html", dest.name)
        self.assertNotIn("__APP__", dest.read_text(encoding="utf-8"))

        doc = json.loads((self.static_dir / "app-tree.json").read_text(encoding="utf-8"))
        self.assertEqual("npdev-app-tree.v2", doc["schemaVersion"])
        self.assertEqual("myapp", doc["appId"])
        concepts = doc["sections"]["Model"]["concepts"]
        names = [c["name"] for c in concepts]
        self.assertIn("Patient", names)
        patient = next(c for c in concepts if c["name"] == "Patient")
        # The exact case a naive PS-parity port could get wrong (STOR-... aside, this is the one
        # this module's own docstring calls out as a favorable divergence from PowerShell's
        # ConvertFrom-Json/ConvertTo-Json single-element-array-collapse quirk): a length-1
        # `invariants` array must stay an array, and `fields` inside it must stay an array too.
        self.assertIsInstance(patient["invariants"], list)
        self.assertEqual(1, len(patient["invariants"]))
        self.assertIsInstance(patient["invariants"][0]["fields"], list)
        self.assertEqual(["mrn"], patient["invariants"][0]["fields"])

        self.assertTrue((self.static_dir / "app-files.json").is_file())
        files_doc = json.loads((self.static_dir / "app-files.json").read_text(encoding="utf-8"))
        self.assertEqual("npdev-app-files.v1", files_doc["schemaVersion"])
        paths = [f["path"] for f in files_doc["files"]]
        self.assertIn("model.json", paths)

    def test_app_tree_v2_page_categorizes_concepts_under_objects(self):
        sp.emit_app_tree_v2_page(self.app_folder, self.static_dir, "myapp")
        doc = json.loads((self.static_dir / "app-tree-v2.json").read_text(encoding="utf-8"))
        self.assertEqual("npdev-app-tree.v3", doc["schemaVersion"])
        concepts = doc["sections"]["Objects"]["Concepts"]["concepts"]
        self.assertTrue(any(c["name"] == "Patient" for c in concepts))
        # config.json's own content lands under Configs > Project General > App Config -- this is
        # the ONE regrouping app-tree-v2 does that app-tree (v1) does not.
        self.assertIn("App Config", doc["sections"]["Configs"]["Project General"])

    def test_verification_panel_page_is_read_only_and_embeds_no_fetch(self):
        dest = sp.emit_verification_panel_page(self.static_dir, self.ops_dir, "myapp")
        html = dest.read_text(encoding="utf-8")
        self.assertNotIn("__APP__", html)
        self.assertNotIn("__BLOB__", html)
        # S5.3 (the plan's own hard requirement): no fetch() at all -- the inventory is baked in.
        self.assertNotIn("fetch(", html)

        doc = json.loads((self.static_dir / "verification.json").read_text(encoding="utf-8"))
        self.assertEqual("npdev-verification-panel.v1", doc["schemaVersion"])
        self.assertEqual("myapp", doc["subject"]["name"])

    def test_verification_panel_lists_emitted_ops_scripts_as_check_scripts(self):
        (self.ops_dir / "Reset-Environment.ps1").write_text("# stub\n", encoding="utf-8")
        (self.ops_dir / "Start-Environment.ps1").write_text("# stub\n", encoding="utf-8")

        sp.emit_verification_panel_page(self.static_dir, self.ops_dir, "myapp")
        doc = json.loads((self.static_dir / "verification.json").read_text(encoding="utf-8"))

        check_scripts = [i for i in doc["items"] if i["category"] == "check-script"]
        names = {i["name"] for i in check_scripts}
        self.assertIn("Reset Environment", names)
        self.assertIn("Start Environment", names)
        for item in check_scripts:
            self.assertFalse(item["runnable"], "verification.html is READ-ONLY (S5.3)")

    def test_resolve_refs_inlines_a_ref_and_keeps_sibling_keys(self):
        with TemporaryDirectory(prefix="npdev-refs-") as tmp:
            base = Path(tmp)
            (base / "child.json").write_text(json.dumps({"name": "Widgets", "type": "child"}),
                                              encoding="utf-8")
            node = {"$ref": "child.json", "as": "override-me-not"}

            resolved = sp._resolve_refs(node, base)

            self.assertEqual("Widgets", resolved["name"])
            self.assertEqual("child", resolved["type"])
            self.assertEqual("override-me-not", resolved["as"])
            self.assertNotIn("$ref", resolved)

    def test_resolve_refs_drops_schema_key_and_recurses_into_lists(self):
        node = {"$schema": "should-be-dropped", "items": [{"$schema": "also-dropped", "x": 1}]}
        resolved = sp._resolve_refs(node, Path("."))
        self.assertNotIn("$schema", resolved)
        self.assertEqual([{"x": 1}], resolved["items"])


if __name__ == "__main__":
    unittest.main()
