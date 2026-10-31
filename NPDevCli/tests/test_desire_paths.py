"""Desire-path rewrites (npdev_desire_paths): each rule turns a shape AI authors were measured
guessing into canonical model.json, and reports it. The fixtures are the real wrong shapes from the
2026-10-31 Haiku/Gemini/Nemotron bench runs.

Run with: python -m unittest NPDevCli.tests.test_desire_paths -v
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_desire_paths as dp  # noqa: E402

CLI = Path(__file__).resolve().parent.parent / "npdev_cli.py"


def _model(**extra) -> dict:
    return {"namespace": "t.shop", "concepts": [{"name": "WorkOrder"}, {"name": "Customer"}], **extra}


def _rules(fixes: list[dict]) -> list[str]:
    return [f["rule"] for f in fixes]


class FlowModeTests(unittest.TestCase):
    def test_list_and_read_become_query_and_canonical_modes_stay(self):
        model = _model(flows=[{"name": "A", "input": {"concept": "WorkOrder", "mode": "list"}},
                              {"name": "B", "input": {"concept": "WorkOrder", "mode": "read"}},
                              {"name": "C", "input": {"concept": "WorkOrder", "mode": "create"}}])
        fixed, fixes = dp.fix_model(model)
        self.assertEqual([f["input"]["mode"] for f in fixed["flows"]], ["query", "query", "create"])
        self.assertEqual(fixes[0], {"rule": "flow-input-mode", "path": "$.flows[0].input.mode",
                                    "wrote": "list", "now": "query"})
        self.assertEqual(len(fixes), 2)
        self.assertEqual(model["flows"][0]["input"]["mode"], "list", "the input model is not mutated")


class RoleTests(unittest.TestCase):
    def test_role_id_and_concept_colon_verb_permissions(self):
        fixed, fixes = dp.fix_model(_model(roles=[
            {"roleId": "mechanic", "permissions": ["workorder:create", "workorder:read", "workorder:resolve"]}]))
        self.assertEqual(fixed["roles"], [{"name": "mechanic", "concepts": {"WorkOrder": ["read", "create", "update"]}}])
        self.assertEqual(_rules(fixes), ["role-name", "role-permissions"])

    def test_concept_ops_written_as_grants_move_and_platform_grants_stay(self):
        fixed, _ = dp.fix_model(_model(roles=[
            {"name": "boss", "grants": ["EXECUTE_FLOW", "workorder:delete"]}]))
        self.assertEqual(fixed["roles"], [{"name": "boss", "grants": ["EXECUTE_FLOW"],
                                           "concepts": {"WorkOrder": ["delete"]}}])

    def test_permission_prefix_matches_the_one_concept_it_starts(self):
        model = {"namespace": "t", "concepts": [{"name": "LeaveRequest"}, {"name": "Employee"}],
                 "roles": [{"roleId": "employee", "permissions": ["leave:submit", "leave:read-own"]}]}
        fixed, _ = dp.fix_model(model)
        self.assertEqual(fixed["roles"][0]["concepts"], {"LeaveRequest": ["read", "create"]})

    def test_concepts_as_a_bare_op_list_applies_to_every_concept(self):
        fixed, fixes = dp.fix_model(_model(roles=[{"name": "staff", "concepts": ["read", "create"]}]))
        self.assertEqual(fixed["roles"][0]["concepts"],
                         {"WorkOrder": ["read", "create"], "Customer": ["read", "create"]})
        self.assertEqual(_rules(fixes), ["role-concepts-list"])

    def test_concepts_as_a_list_of_entries(self):
        fixed, _ = dp.fix_model(_model(roles=[{"name": "owner", "concepts": [
            {"concept": "WorkOrder", "permissions": ["read", "update"]}]}]))
        self.assertEqual(fixed["roles"][0]["concepts"], {"WorkOrder": ["read", "update"]})

    def test_canonical_role_is_untouched(self):
        role = {"name": "viewer", "concepts": {"WorkOrder": ["read"]}, "grants": ["READ_EXECUTIONS"]}
        fixed, fixes = dp.fix_model(_model(roles=[role]))
        self.assertEqual(fixes, [])
        self.assertEqual(fixed["roles"], [role])

    def test_wrong_case_concept_key_and_list_verb_are_normalized(self):
        fixed, fixes = dp.fix_model(_model(roles=[{"name": "v", "concepts": {"workorder": ["list"]}}]))
        self.assertEqual(fixed["roles"][0]["concepts"], {"WorkOrder": ["read"]})
        self.assertEqual(_rules(fixes), ["role-concept-name", "role-concept-ops"])


class LifecycleTests(unittest.TestCase):
    def test_flat_lifecycle_becomes_states_and_split_transitions(self):
        model = {"namespace": "t", "concepts": [{"name": "Booking", "lifecycle": {
            "field": "status", "initial": "enquiry", "final": ["paid", "cancelled"],
            "transitions": [{"name": "confirm", "from": "enquiry", "to": "confirmed"},
                            {"name": "pay", "from": "confirmed", "to": "paid"},
                            {"name": "cancel", "from": ["enquiry", "confirmed"], "to": "cancelled"}]}}]}
        fixed, fixes = dp.fix_model(model)
        lc = fixed["concepts"][0]["lifecycle"]
        self.assertEqual(lc["statusField"], "status")
        self.assertEqual(lc["states"], [{"value": "enquiry", "initial": True}, {"value": "confirmed"},
                                        {"value": "paid", "terminal": True}, {"value": "cancelled", "terminal": True}])
        self.assertEqual(lc["transitions"], [
            {"from": "enquiry", "to": "confirmed", "event": "confirm"},
            {"from": "confirmed", "to": "paid", "event": "pay"},
            {"from": "enquiry", "to": "cancelled", "event": "cancel"},
            {"from": "confirmed", "to": "cancelled", "event": "cancel"}])
        self.assertNotIn("field", lc)
        self.assertIn("lifecycle-transition-from-list", _rules(fixes))

    def test_existing_states_get_the_flags_and_strings_become_objects(self):
        model = {"namespace": "t", "concepts": [{"name": "T", "lifecycle": {
            "statusField": "status", "states": ["open", "closed"], "initial": "open", "terminal": "closed",
            "transitions": [{"from": "open", "to": "closed"}]}}]}
        fixed, _ = dp.fix_model(model)
        self.assertEqual(fixed["concepts"][0]["lifecycle"]["states"],
                         [{"value": "open", "initial": True}, {"value": "closed", "terminal": True}])


class DataSourceTests(unittest.TestCase):
    def test_kind_entity_becomes_concept(self):
        fixed, _ = dp.fix_model(_model(panels=[{"name": "p", "dataSources": [{"kind": "entity", "name": "Customer"}]}]))
        self.assertEqual(fixed["panels"][0]["dataSources"], [{"name": "Customer", "concept": "Customer"}])


class CliTests(unittest.TestCase):
    def test_normalize_desire_paths_writes_model_and_report(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "model.json"
            src.write_text(json.dumps(_model(flows=[{"name": "A", "input": {"concept": "WorkOrder", "mode": "list"}}])),
                           encoding="utf-8")
            out, report = Path(td) / "fixed.json", Path(td) / "fixes.json"
            proc = subprocess.run([sys.executable, str(CLI), "normalize", "desire-paths", str(src),
                                   "--output", str(out), "--report", str(report)],
                                  capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("1 desire-path fix(es)", proc.stderr)
            self.assertEqual(json.loads(out.read_text(encoding="utf-8"))["flows"][0]["input"]["mode"], "query")
            self.assertEqual(json.loads(report.read_text(encoding="utf-8"))["fixes"][0]["rule"], "flow-input-mode")


if __name__ == "__main__":
    unittest.main()
