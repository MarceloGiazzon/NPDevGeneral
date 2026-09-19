"""WMS-12 follow-up 3: `npdev generate app` must stage definition/seeds/*.json into the final
app's npdev-seed/data-seeds classpath folder (Build-NpdevApp.ps1 step 4d parity), so a CLI-
generated app's /api/admin/seeds works without a manual copy. Locks the always-array index.json
(REG-189) and the id==filename-stem contract SeedDataService relies on.

Run with:
    python -m unittest NPDevCli.tests.test_generate_stages_seeds -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class StageSeedsTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def _write(self, rel: str, data: dict) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data) + "\n", encoding="utf-8")
        return path

    def test_stages_smart_seeds_and_index(self) -> None:
        self._write("definition/seeds/warehouse-a.json", {
            "id": "warehouse-a", "label": "WH A", "description": "demo", "kind": "smart",
            "records": [{"concept": "Entidade", "data": {"nome": "A"}}],
        })
        self._write("definition/seeds/users.json", {
            "id": "users", "label": "Users", "description": "auth",
            "records": [{"concept": "identity::User", "data": {"username": "u"}}],
        })
        app = self.root / "app"

        count = npdev_cli.stage_seeds_into_final_app(self.root / "definition", app)

        self.assertEqual(count, 2)
        dst = app / "src" / "main" / "resources" / "npdev-seed" / "data-seeds"
        self.assertTrue((dst / "warehouse-a.json").exists())
        self.assertTrue((dst / "users.json").exists())
        manifest = json.loads((dst / "index.json").read_text(encoding="utf-8"))
        # index.json must ALWAYS be a JSON array (REG-189), even for a single seed.
        self.assertIsInstance(manifest, list)
        by_id = {entry["id"]: entry for entry in manifest}
        self.assertEqual(set(by_id), {"warehouse-a", "users"})
        self.assertEqual(by_id["warehouse-a"]["kind"], "smart")
        self.assertEqual(by_id["warehouse-a"]["label"], "WH A")

    def test_missing_kind_defaults_to_smart(self) -> None:
        self._write("definition/seeds/only.json", {"id": "only", "records": []})
        app = self.root / "app"

        count = npdev_cli.stage_seeds_into_final_app(self.root / "definition", app)

        self.assertEqual(count, 1)
        dst = app / "src" / "main" / "resources" / "npdev-seed" / "data-seeds"
        manifest = json.loads((dst / "index.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest[0]["kind"], "smart")

    def test_seed_id_must_match_filename_stem(self) -> None:
        self._write("definition/seeds/a.json", {"id": "mismatch", "records": []})
        app = self.root / "app"

        with self.assertRaises(npdev_cli.CliError) as ctx:
            npdev_cli.stage_seeds_into_final_app(self.root / "definition", app)
        self.assertIn("must match the filename stem", str(ctx.exception))

    def test_no_seeds_dir_is_a_noop(self) -> None:
        app = self.root / "app"

        count = npdev_cli.stage_seeds_into_final_app(self.root / "definition", app)

        self.assertEqual(count, 0)
        self.assertFalse((app / "src").exists())


if __name__ == "__main__":
    unittest.main()