"""`npdev migrate business-packs-2`: sales/purchasing/inventory 2.0.0 single-owner codemod. No Gradle, no network.

Run with: python -m unittest NPDevCli.tests.test_migrate_business_packs_2 -v
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import business_packs_v2_migration as codemod  # noqa: E402
import npdev_cli  # noqa: E402

REPO_PACKS = Path(__file__).resolve().parents[2] / "NPDevContract" / "packs"


def model(packs, roles, extra=None):
    doc = {"namespace": "t", "packs": packs, "roles": roles}
    doc.update(extra or {})
    return doc


class MigrateDocumentTest(unittest.TestCase):

    def test_untouched_when_no_business_pack_is_imported(self):
        doc = model([{"$ref": "packs/contacts/pack.json", "as": "contacts"}], [])
        r = codemod.migrate_document(doc)
        self.assertFalse(r.changed)
        self.assertEqual(r.notes, [])

    def test_sales_grants_are_mirrored_onto_organization_and_product(self):
        doc = model([{"$ref": "packs/sales/pack.json", "as": "sales"}],
                    [{"name": "MGR", "concepts": {"sales::Customer": ["read", "create"], "sales::SalesItem": ["read"]}},
                     {"name": "REP", "concepts": {"sales::Customer": ["read"]}}])
        r = codemod.migrate_document(doc)
        self.assertTrue(r.changed)
        mgr, rep = doc["roles"][0]["concepts"], doc["roles"][1]["concepts"]
        self.assertEqual(mgr["contacts::Organization"], ["read", "create"])
        self.assertEqual(mgr["products::Product"], ["read"])
        self.assertEqual(rep["contacts::Organization"], ["read"])
        self.assertNotIn("products::Product", rep)

    def test_existing_grants_are_unioned_not_replaced(self):
        doc = model([{"$ref": "packs/purchasing/pack.json", "as": "purchasing"}],
                    [{"name": "B", "concepts": {"purchasing::Supplier": ["read", "update"], "contacts::Organization": ["read", "delete"]}}])
        codemod.migrate_document(doc)
        self.assertEqual(doc["roles"][0]["concepts"]["contacts::Organization"], ["read", "delete", "update"])

    def test_inventory_product_is_renamed_in_place_and_mirrored(self):
        doc = model([{"$ref": "packs/inventory/pack.json", "as": "inventory"}],
                    [{"name": "K", "concepts": {"inventory::Warehouse": ["read"], "inventory::Product": ["read"], "inventory::StockMovement": ["read"]}}],
                    {"concepts": [{"name": "Shelf", "fields": [{"name": "p", "type": "reference", "reference": {"target": "inventory::Product"}}]}]})
        r = codemod.migrate_document(doc)
        grants = doc["roles"][0]["concepts"]
        self.assertEqual(list(grants)[:3], ["inventory::Warehouse", "inventory::StockItem", "inventory::StockMovement"],
                         "the renamed key keeps its position")
        self.assertEqual(grants["products::Product"], ["read"])
        self.assertEqual(doc["concepts"][0]["fields"][0]["reference"]["target"], "inventory::StockItem")
        self.assertIn("renamed inventory::Product -> inventory::StockItem (2 place(s))", r.changes)

    def test_aliases_are_honoured(self):
        doc = model([{"$ref": "packs/sales/pack.json", "as": "s"}, {"$ref": "packs/contacts/pack.json", "as": "crm"}],
                    [{"name": "M", "concepts": {"s::Customer": ["read"]}}])
        codemod.migrate_document(doc)
        self.assertEqual(doc["roles"][0]["concepts"]["crm::Organization"], ["read"])

    def test_item_id_use_is_reported_not_rewritten(self):
        doc = model([{"$ref": "packs/sales/pack.json", "as": "sales"}], [],
                    {"queries": [{"name": "Q", "concept": "sales::SalesOrderLine", "groupBy": ["itemId"]}]})
        r = codemod.migrate_document(doc)
        self.assertEqual(doc["queries"][0]["groupBy"], ["itemId"])
        self.assertTrue(any("itemId" in n for n in r.notes))

    def test_second_run_changes_nothing(self):
        doc = model([{"$ref": "packs/inventory/pack.json", "as": "inventory"}, {"$ref": "packs/sales/pack.json", "as": "sales"}],
                    [{"name": "M", "concepts": {"inventory::Product": ["read"], "sales::Customer": ["read"]}}])
        codemod.migrate_document(doc)
        self.assertFalse(codemod.migrate_document(doc).changed)


class PackCopiesTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.source = self.root / "source"
        for pid, version in (("sales", "2.0.0"), ("contacts", "1.1.0"), ("products", "1.0.0")):
            (self.source / pid).mkdir(parents=True)
            (self.source / pid / "pack.json").write_text(json.dumps({"$schema": "x", "pack": pid, "version": version}), encoding="utf-8")
        self.app = self.root / "app"
        (self.app / "packs" / "sales").mkdir(parents=True)
        (self.app / "packs" / "sales" / "pack.json").write_text(
            json.dumps({"$schema": "../../schemas/pack.schema.json", "pack": "sales", "version": "1.0.0"}), encoding="utf-8")
        self.model = self.app / "model.json"
        self.model.write_text(json.dumps(model([{"$ref": "packs/sales/pack.json", "as": "sales"}],
                                               [{"name": "M", "concepts": {"sales::Customer": ["read"]}}])), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, rel):
        return json.loads((self.app / rel).read_text(encoding="utf-8"))

    def test_dry_run_writes_nothing(self):
        lines = []
        codemod.run([str(self.model)], write=False, packs_source=self.source, out=lines.append)
        self.assertEqual(self.read("packs/sales/pack.json")["version"], "1.0.0")
        self.assertFalse((self.app / "packs" / "contacts").exists())
        self.assertNotIn("contacts::Organization", self.read("model.json")["roles"][0]["concepts"])
        self.assertTrue(any("Dry run" in x for x in lines))

    def test_write_refreshes_the_copy_adds_dependencies_and_keeps_schema_path(self):
        lines = []
        codemod.run([str(self.model)], write=True, packs_source=self.source, out=lines.append)
        sales = self.read("packs/sales/pack.json")
        self.assertEqual(sales["version"], "2.0.0")
        self.assertEqual(sales["$schema"], "../../schemas/pack.schema.json")
        self.assertEqual(self.read("packs/contacts/pack.json")["$schema"], "../../schemas/pack.schema.json")
        self.assertEqual(self.read("packs/products/pack.json")["version"], "1.0.0")
        self.assertEqual(self.read("model.json")["roles"][0]["concepts"]["contacts::Organization"], ["read"])
        self.assertTrue(any("npdev pack update" in x for x in lines), lines)
        again = []
        codemod.run([str(self.model)], write=True, packs_source=self.source, out=again.append)
        self.assertFalse(any("CHANGED" in x for x in again), again)

    def test_cli_subcommand_is_wired(self):
        parser = npdev_cli.build_parser() if hasattr(npdev_cli, "build_parser") else None
        if parser is None:
            self.skipTest("npdev_cli exposes no build_parser")
        args = parser.parse_args(["migrate", "business-packs-2", "--input", str(self.model)])
        self.assertEqual(args.migrate_command, "business-packs-2")
        self.assertFalse(args.write)


class BuiltInPacksTest(unittest.TestCase):
    """The codemod's mapping must match the packs it migrates to."""

    def test_satellites_point_where_the_codemod_says(self):
        def concept(pid, name):
            d = json.loads((REPO_PACKS / pid / "pack.json").read_text(encoding="utf-8"))
            return next(c for c in d["concepts"] if c["name"] == name)
        self.assertEqual(concept("sales", "Customer")["satelliteOf"], "contacts::Organization")
        self.assertEqual(concept("sales", "SalesItem")["satelliteOf"], "products::Product")
        self.assertEqual(concept("purchasing", "Supplier")["satelliteOf"], "contacts::Organization")
        self.assertEqual(concept("purchasing", "PurchaseItem")["satelliteOf"], "products::Product")
        self.assertEqual(concept("inventory", "StockItem")["satelliteOf"], "products::Product")
        for pid, deps in codemod.DEPENDENCIES.items():
            d = json.loads((REPO_PACKS / pid / "pack.json").read_text(encoding="utf-8"))
            self.assertEqual(sorted(x["pack"] for x in d["packs"]), sorted(deps), pid)


if __name__ == "__main__":
    unittest.main()
