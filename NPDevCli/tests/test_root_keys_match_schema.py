"""The CLI's top-level key whitelist must be exactly the canonical schema's top-level keys.

Hand-kept lists drifted three times (REG-108, the 2026-08-15 `aggregates` refusal, and 2026-10-31:
`agentAccess`, `appShell`, `webhooks`, `sequences` were schema-valid but refused by
`validate model --structural-only`, failing AI-authored models the full validator accepts). This
test derives the expectation from model.schema.json, so the next new key fails here, not in a user's
model.

Run with: python -m unittest NPDevCli.tests.test_root_keys_match_schema -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

SCHEMA = Path(__file__).resolve().parents[2] / "NPDevContract" / "schemas" / "model.schema.json"


class RootKeysMatchSchemaTest(unittest.TestCase):
    def test_whitelist_equals_schema_top_level_properties(self):
        schema_keys = set(json.loads(SCHEMA.read_text(encoding="utf-8"))["properties"])
        cli_keys = npdev_cli.ROOT_SCALAR_KEYS | npdev_cli.MODEL_ARRAY_KEYS | {"metadata", "fragments"}
        self.assertEqual(set(), schema_keys - cli_keys, "schema keys the CLI refuses")
        self.assertEqual(set(), cli_keys - schema_keys, "CLI keys the schema does not define")


if __name__ == "__main__":
    unittest.main()
