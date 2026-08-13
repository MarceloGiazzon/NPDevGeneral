"""Smoke test for `npdev pack why` (PK-3, MASTER-ROADMAP.md Step 8).

Real end-to-end: shells out to the actual :NPDevContract:dsl:packWhy Gradle task (slow, ~5-10s,
same discipline as this repo's other real-verification tests) rather than mocking the subprocess,
so this proves the full CLI -> Gradle -> JavaExec -> PackWhyMain -> ModelSourceResolver chain
actually works, not just that the Python wiring calls the right function name.

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_pack_cli -v
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


class PackWhyCliTest(unittest.TestCase):
    def test_why_names_every_requirer_and_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            _write(tmp_dir / "packs" / "user" / "pack.json", json.dumps({
                "dslVersion": "1.0.0", "pack": "user", "version": "2.0.0",
                "concepts": [{"name": "Account", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]}],
            }))
            _write(tmp_dir / "packs" / "crm" / "pack.json", json.dumps({
                "dslVersion": "1.0.0", "pack": "crm", "version": "1.0.0",
                "packs": [{"pack": "user", "version": "^2.0"}],
                "concepts": [{"name": "Lead", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]}],
            }))
            model_path = tmp_dir / "model.json"
            _write(model_path, json.dumps({
                "namespace": "test.pack.why", "dslVersion": "1.0.0", "version": "1.0",
                "packs": [{"$ref": "packs/crm/pack.json"}],
            }))

            args = argparse.Namespace(model=str(model_path), pack_id="user")
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_pack_why(args)

            self.assertEqual(0, code)
            report = json.loads(captured.getvalue())
            self.assertEqual("ok", report["status"])
            self.assertEqual("user", report["packId"])
            self.assertTrue(
                any("crm" in reason and "app -> crm" in reason for reason in report["requiredBy"]),
                f"must name crm and its path, got: {report['requiredBy']}",
            )


if __name__ == "__main__":
    unittest.main()
