"""F3 (Cold Clone Audit, P0) regression: `npdev migrate db-lifecycle` must flag a server-engine
db.definition.json that omits `database.externallyProvisioned` entirely, and must never silently
write a guessed value on the operator's behalf -- that silent guess (the field defaulting to
false, "NPDev owns it") is exactly what let `Reset` delete a server the user had provisioned
themselves. See UserDatabaseDefinitionLoader.requireExternallyProvisioned (Java) for the generator
side of this fix; this file locks the migration-tool side.

Run with:
    python -m unittest NPDevCli.tests.test_migrate_db_lifecycle_f3 -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dsl_v2_migration import migrate_db_definition  # noqa: E402


def _definition(engine: str, **database_extra) -> dict:
    database = {"engine": engine, "databaseName": "npdev_probe"}
    database.update(database_extra)
    return {
        "database": database,
        "schemaLifecycle": {"strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly"},
    }


class MigrateDbLifecycleF3Test(unittest.TestCase):
    def test_server_engine_missing_the_key_is_flagged_not_rewritten(self):
        doc = _definition("Postgres")

        result = migrate_db_definition(doc)

        self.assertFalse(result.changed, "must never auto-write a guessed value")
        self.assertNotIn("externallyProvisioned", doc["database"], "the tool must not silently add it")
        self.assertEqual(1, len(result.ambiguities))
        note = result.ambiguities[0]
        self.assertIn("Postgres", note)
        self.assertIn("externallyProvisioned", note)
        self.assertIn("F3", note)

    def test_matching_is_case_insensitive_on_the_engine_name(self):
        for engine in ("postgres", "POSTGRES", "MySQL", "mysql", "SqlServer", "H2Server"):
            with self.subTest(engine=engine):
                doc = _definition(engine)
                result = migrate_db_definition(doc)
                self.assertEqual(1, len(result.ambiguities), engine)

    def test_key_present_as_true_is_not_flagged(self):
        doc = _definition("Postgres", externallyProvisioned=True)

        result = migrate_db_definition(doc)

        self.assertEqual([], result.ambiguities)

    def test_key_present_as_false_is_not_flagged(self):
        doc = _definition("Postgres", externallyProvisioned=False)

        result = migrate_db_definition(doc)

        self.assertEqual([], result.ambiguities)

    def test_embedded_engines_are_never_flagged(self):
        for engine in ("InMemory", "H2Local"):
            with self.subTest(engine=engine):
                doc = _definition(engine)
                result = migrate_db_definition(doc)
                self.assertEqual([], result.ambiguities, engine)

    def test_the_f3_check_runs_independently_of_the_stor16_rewrite(self):
        """A definition can need BOTH: the deprecated strategy rewritten AND the F3 flag raised.
        Neither check may suppress the other."""
        doc = _definition("Postgres")
        doc["schemaLifecycle"]["strategy"] = "RecreateOnAppStart"

        result = migrate_db_definition(doc)

        self.assertTrue(result.changed)
        self.assertEqual("Ephemeral", doc["schemaLifecycle"]["strategy"])
        f3_notes = [n for n in result.ambiguities if "externallyProvisioned" in n]
        self.assertEqual(1, len(f3_notes), result.ambiguities)

    def test_non_db_definition_documents_are_left_alone(self):
        result = migrate_db_definition({"not": "a db definition"})
        self.assertFalse(result.changed)
        self.assertEqual([], result.ambiguities)


if __name__ == "__main__":
    unittest.main()
