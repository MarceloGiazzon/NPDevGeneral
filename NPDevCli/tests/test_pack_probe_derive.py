"""Probes derived from a pack's own declarations (npdev_pack_probe_derive)."""
import json
import sys
import unittest
from pathlib import Path

CLI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI))

import npdev_pack_probe  # noqa: E402
import npdev_pack_probe_derive as derive  # noqa: E402

PACKS = CLI.parent / "NPDevContract" / "packs"


def concept(**kw):
    base = {"name": "Lead", "fields": [
        {"name": "id", "type": "uuid", "id": True, "required": True},
        {"name": "name", "type": "string", "required": True, "maxLength": 80}]}
    base.update(kw)
    return base


class ParseAccess(unittest.TestCase):
    def test_true_admits_everyone(self):
        self.assertEqual(derive.parse_access("true"), derive.ALL)

    def test_literal_and_logical_roles(self):
        got = derive.parse_access("$user.roles.contains('ADMIN') || $user.roles.contains(role('employee')) || $user.isSystem")
        self.assertEqual(got, frozenset({"ADMIN", "EMPLOYEE"}))

    def test_anything_else_is_not_guessed(self):
        for expr in ("ownerId == $user.id", "$user.roles.contains('A') && active == true",
                     "($user.roles.contains('A'))", "", None, 5):
            self.assertIsNone(derive.parse_access(expr), expr)


class BuildBody(unittest.TestCase):
    def test_required_fields_without_defaults_only(self):
        body, why = derive.build_body(concept(fields=[
            {"name": "id", "type": "uuid", "id": True, "required": True},
            {"name": "name", "type": "string", "required": True, "maxLength": 80},
            {"name": "note", "type": "string", "required": False},
            {"name": "active", "type": "boolean", "required": True, "default": True},
            {"name": "kind", "type": "enum", "required": True, "enumValues": ["A", "B"]},
            {"name": "qty", "type": "integer", "required": True, "min": 3},
            {"name": "price", "type": "decimal", "required": True, "min": 0},
            {"name": "on", "type": "date", "required": True}]))
        self.assertEqual(why, "")
        self.assertEqual(body, {"name": "p${rand}", "kind": "A", "qty": 3, "price": 1.0, "on": "2026-01-15"})

    def test_refuses_what_it_cannot_fill(self):
        body, why = derive.build_body(concept(fields=[{"name": "boss", "type": "reference", "required": True}]))
        self.assertIsNone(body)
        self.assertIn("boss", why)
        body, why = derive.build_body(concept(fields=[{"name": "code", "type": "string", "required": True, "maxLength": 4}]))
        self.assertIsNone(body)
        self.assertIn("fewer than 9", why)


class DeriveConcept(unittest.TestCase):
    def test_closed_write_set_gets_a_decoy_refusal(self):
        doc, why = derive.derive_concept("leads", concept(access={
            "read": "$user.roles.contains(role('rep')) || $user.roles.contains('ADMIN')",
            "write": "$user.roles.contains(role('rep'))"}))
        self.assertEqual(why, "")
        self.assertEqual(npdev_pack_probe.check_shape(doc), [])
        names = [s["name"] for s in doc["steps"]]
        self.assertEqual(len(names), 4)
        self.assertEqual([u["role"] for u in doc["users"]], ["REP", derive.DECOY_ROLE])
        refused = [s for s in doc["steps"] if s["as"] == derive.DECOY_NAME]
        self.assertEqual([s["expect"]["status"] for s in refused], [derive.OUTSIDER_STATUSES] * 2)

    def test_open_rule_only_smoke_tests(self):
        doc, _ = derive.derive_concept("leads", concept())
        self.assertEqual([u["name"] for u in doc["users"]], [derive.DECOY_NAME])
        self.assertEqual(len(doc["steps"]), 2)

    def test_read_back_is_skipped_when_the_writer_cannot_read(self):
        doc, _ = derive.derive_concept("leads", concept(access={
            "read": "$user.roles.contains('ADMIN')", "write": "$user.roles.contains(role('rep'))"}))
        self.assertFalse(any("reads the leads::Lead back" in s["name"] for s in doc["steps"]))

    def test_unprovable_rules_are_skipped_with_a_reason(self):
        doc, why = derive.derive_concept("leads", concept(access={"write": "owner == $user.id"}))
        self.assertIsNone(doc)
        self.assertIn("access.write", why)
        doc, why = derive.derive_concept("leads", concept(access={"write": "true", "read": "owner == $user.id"}))
        self.assertIsNone(doc)
        self.assertIn("access.read", why)


class ResolvePaths(unittest.TestCase):
    INVOCATIONS = [
        {"kind": "createDirect", "concept": "leads::Lead", "method": "POST", "path": "/api/concepts/leads_leads"},
        {"kind": "read", "concept": "leads::Lead", "method": "GET", "path": "/api/concepts/leads_leads/{id}"},
        {"kind": "list", "concept": "leads::Lead", "method": "GET", "path": "/api/concepts/leads_leads"}]

    def test_placeholders_become_the_consumers_routes(self):
        doc, _ = derive.derive_concept("leads", concept())
        self.assertEqual(derive.resolve_paths(doc, self.INVOCATIONS), "")
        self.assertEqual([s["path"] for s in doc["steps"]], ["/api/concepts/leads_leads", "/api/concepts/leads_leads/${rowId}"])

    def test_unknown_concept_is_reported(self):
        doc, _ = derive.derive_concept("other", concept())
        self.assertIn("no create route", derive.resolve_paths(doc, self.INVOCATIONS))


class ParentChain(unittest.TestCase):
    PACKS = {
        "shop": {"concepts": [
            {"name": "Warehouse", "fields": [{"name": "id", "type": "uuid", "id": True, "required": True},
                                              {"name": "name", "type": "string", "required": True, "maxLength": 80}],
             "access": {"write": "$user.roles.contains('ADMIN')"}},
            {"name": "Bin", "access": {"write": "$user.roles.contains('ADMIN')"}, "fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "warehouseId", "type": "reference", "required": True, "reference": {"target": "Warehouse"}}]},
            {"name": "Crate", "access": {"write": "$user.roles.contains('ADMIN')"}, "fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "item", "type": "reference", "required": True, "reference": {"target": "base::Item"}}]}]},
        "base": {"concepts": [{"name": "Item", "access": {"write": "$user.roles.contains('CLERK')"}, "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True},
            {"name": "name", "type": "string", "required": True, "maxLength": 40}]}]},
    }

    def test_the_parent_is_created_first_and_its_id_is_used(self):
        bin_ = self.PACKS["shop"]["concepts"][1]
        doc, why = derive.derive_concept("shop", bin_, self.PACKS)
        self.assertEqual(why, "")
        self.assertEqual(npdev_pack_probe.check_shape(doc), [])
        creates = [s for s in doc["steps"] if s["method"] == "POST" and s["as"] == "admin"]
        self.assertEqual([s["path"] for s in creates], ["/__derive__/create/shop::Warehouse", "/__derive__/create/shop::Bin"])
        self.assertEqual(creates[1]["body"], {"warehouseId": "${parent1}"})
        self.assertEqual(creates[0]["save"], {"parent1": "id"})
        self.assertEqual(creates[1]["save"], {"rowId": "id"})
        decoy_create = [s for s in doc["steps"] if s["as"] == "decoy" and s["method"] == "POST"][0]
        self.assertEqual(decoy_create["body"], {"warehouseId": "${parent1}"})

    def test_a_parent_the_acting_role_cannot_create_is_a_skip_not_a_guess(self):
        doc, why = derive.derive_concept("shop", self.PACKS["shop"]["concepts"][2], self.PACKS)
        self.assertIsNone(doc)
        self.assertIn("base::Item", why)
        self.assertIn("ADMIN", why)

    def test_unknown_target_is_reported(self):
        lone = {"name": "Bin", "fields": [{"name": "w", "type": "reference", "required": True, "reference": {"target": "Nope"}}]}
        doc, why = derive.derive_concept("shop", lone, {"shop": {"concepts": [lone]}})
        self.assertIsNone(doc)
        self.assertIn("Nope", why)


class BuiltInPacks(unittest.TestCase):
    """Every derived document for every built-in pack must be valid probe JSON; a concept it cannot derive says why."""

    def test_every_derived_document_passes_the_probe_schema(self):
        derived = skipped = 0
        for pack_json in sorted(PACKS.glob("*/pack.json")):
            pack = json.loads(pack_json.read_text(encoding="utf-8"))
            needs = [d["pack"] for d in pack.get("packs") or []]
            known = {n: json.loads((PACKS / n / "pack.json").read_text(encoding="utf-8")) for n in needs}
            docs, notes = derive.derive(pack["pack"], pack, known)
            for name, doc in docs.items():
                self.assertEqual(npdev_pack_probe.check_shape(doc), [], f"{pack['pack']}/{name}")
            self.assertEqual(len(docs) + len(notes), len([c for c in pack.get("concepts", []) if isinstance(c, dict)]))
            derived += len(docs)
            skipped += len(notes)
        self.assertGreater(derived, 0)
        print(f"\nbuilt-in packs: {derived} concept(s) derived, {skipped} skipped with a reason")


if __name__ == "__main__":
    unittest.main()
