"""Path A P5.2: `npdev monitor probe --include-info` inlines a built app's own
extension-inventory.json (P0.3, now carrying each entry's provenance -- P5.1's untrusted-extension
declarations plus P5.2's own customization-provenance.json join) the same way it already inlines
info.json -- see `_find_extension_inventory_json` / the `include_info` block in `npdev_monitor.py`.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_monitor


def _minimal_app(root: Path) -> Path:
    """The smallest tree `discovery_rule` accepts: an `_ops` directory plus
    `_ops/resolved-db-plan.json`. No `finalAppPath`/`appRoot` in the plan, so `probe_app` resolves
    `finalAppRoot` to the app root itself (the "in-app" layout)."""
    app = root / "demo-app"
    (app / "_ops").mkdir(parents=True)
    plan = {"appId": "demo", "engine": "H2Local", "serverPort": 8099}
    (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(plan), encoding="utf-8")
    return app


class ExtensionInventoryProbe(unittest.TestCase):
    def test_absent_when_not_built(self):
        with TemporaryDirectory() as tmp:
            app = _minimal_app(Path(tmp))
            record = npdev_monitor.probe_app(app, include_info=True)
            self.assertFalse(record["hasExtensionInventory"])
            self.assertIsNone(record["extensionInventoryPath"])
            self.assertIsNone(record["extensionInventory"])

    def test_inlined_when_present(self):
        with TemporaryDirectory() as tmp:
            app = _minimal_app(Path(tmp))
            inventory_dir = app / "npdev-generated" / "src" / "main" / "resources" / "npdev"
            inventory_dir.mkdir(parents=True)
            inventory = {
                "schemaVersion": "1.0",
                "counts": {"untrustedExtensionAsset": 0, "javaHook": 1, "inProcessController": 0, "pluginPackage": 0},
                "entries": [
                    {
                        "category": "javaHook", "kind": "conversionJavaHook",
                        "origin": "h.java#m", "owner": "conv1",
                        "provenance": {"declared": True, "author": "ana@example.test", "authorType": "human"},
                    }
                ],
            }
            (inventory_dir / "extension-inventory.json").write_text(json.dumps(inventory), encoding="utf-8")

            record = npdev_monitor.probe_app(app, include_info=True)
            self.assertTrue(record["hasExtensionInventory"])
            self.assertEqual(record["extensionInventory"]["entries"][0]["owner"], "conv1")
            self.assertEqual(record["extensionInventory"]["entries"][0]["provenance"]["author"], "ana@example.test")

    def test_absent_from_record_without_include_info(self):
        # Same "opt-in payload" contract info.json already has -- `monitor scan` (include_info=False
        # by default) must not pay to read/serialize a potentially large inventory nobody asked for.
        with TemporaryDirectory() as tmp:
            app = _minimal_app(Path(tmp))
            record = npdev_monitor.probe_app(app, include_info=False)
            self.assertNotIn("extensionInventory", record)
            # The existence facts are cheap and always present, same as hasInfoJson.
            self.assertIn("hasExtensionInventory", record)


if __name__ == "__main__":
    unittest.main()
