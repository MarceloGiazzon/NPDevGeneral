"""Tests for dsl_v2_migration.py (2.A.3, docs/DSL2_AND_DECOMPOSITION_PLAN.md).

Stdlib-only (unittest), matching this repo's convention for quality-gate scripts. Run with:
    python -m unittest NPDevCli.tests.test_dsl_v2_migration -v
"""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dsl_v2_migration import migrate_document  # noqa: E402


def _flow_model(steps: list[dict], extra_flow_keys: dict | None = None) -> dict:
    flow = {"name": "DemoFlow", "input": {"concept": "Widget", "mode": "create"}, "steps": steps}
    if extra_flow_keys:
        flow.update(extra_flow_keys)
    return {"namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [flow]}


class StepTypeMigrationTest(unittest.TestCase):
    def test_validate_becomes_invariant_check(self):
        doc = _flow_model([{"name": "s1", "type": "validate", "scope": "Widget", "invariants": ["X"]}])
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        self.assertEqual("invariantCheck", doc["flows"][0]["steps"][0]["type"])

    def test_capability_call_aliases_all_converge(self):
        for alias in ("capability", "callCapability", "capabilityCall"):
            doc = _flow_model([{"name": "s1", "type": alias, "cap": "persistence", "op": "save"}])
            migrate_document(doc)
            self.assertEqual("capabilityCall", doc["flows"][0]["steps"][0]["type"], msg=f"alias={alias}")

    def test_cap_op_out_field_aliases_renamed(self):
        doc = _flow_model([{
            "name": "s1", "type": "capabilityCall",
            "cap": "persistence", "op": "save", "out": "$saved",
        }])
        result = migrate_document(doc)
        step = doc["flows"][0]["steps"][0]
        self.assertNotIn("cap", step)
        self.assertNotIn("op", step)
        self.assertNotIn("out", step)
        self.assertEqual("persistence", step["capability"])
        self.assertEqual("save", step["operation"])
        self.assertEqual("$saved", step["output"])
        self.assertTrue(result.changed)

    def test_as_renamed_to_await_ref(self):
        doc = _flow_model([{"name": "s1", "type": "waitForEvent", "awaitEvent": "X", "as": "confirmation"}])
        migrate_document(doc)
        step = doc["flows"][0]["steps"][0]
        self.assertNotIn("as", step)
        self.assertEqual("confirmation", step["awaitRef"])
        self.assertEqual("awaitEvent", step["type"])

    def test_nested_branch_steps_are_migrated(self):
        doc = _flow_model([{
            "name": "s1", "type": "if", "condition": "true",
            "then": [{"name": "inner", "type": "assign", "input": "$x", "out": "$y"}],
            "else": [{"name": "inner2", "type": "waitForEvent", "awaitEvent": "Y"}],
        }])
        migrate_document(doc)
        step = doc["flows"][0]["steps"][0]
        self.assertEqual("branch", step["type"])
        self.assertEqual("map", step["then"][0]["type"])
        self.assertEqual("$y", step["then"][0]["output"])
        self.assertEqual("awaitEvent", step["else"][0]["type"])

    def test_unrecognized_type_reported_not_guessed(self):
        doc = _flow_model([{"name": "s1", "type": "somethingMadeUp"}])
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(1, len(result.ambiguities))
        self.assertIn("somethingMadeUp", result.ambiguities[0])
        self.assertEqual("somethingMadeUp", doc["flows"][0]["steps"][0]["type"])

    def test_conflicting_alias_and_canonical_left_untouched(self):
        doc = _flow_model([{
            "name": "s1", "type": "capabilityCall",
            "cap": "persistence", "capability": "notification", "op": "save",
        }])
        result = migrate_document(doc)
        step = doc["flows"][0]["steps"][0]
        self.assertIn("cap", step)
        self.assertIn("capability", step)
        self.assertEqual("persistence", step["cap"])
        self.assertEqual("notification", step["capability"])
        self.assertEqual(1, len(result.ambiguities))

    def test_idempotent_second_run_makes_no_further_changes(self):
        doc = _flow_model([{
            "name": "s1", "type": "validate", "scope": "Widget", "invariants": ["X"],
        }, {
            "name": "s2", "type": "callCapability", "cap": "persistence", "op": "save", "out": "$saved",
        }])
        first = migrate_document(doc)
        self.assertTrue(first.changed)
        before = copy.deepcopy(doc)
        second = migrate_document(doc)
        self.assertFalse(second.changed)
        self.assertEqual([], second.changes)
        self.assertEqual(before, doc)


class FlowHookMigrationTest(unittest.TestCase):
    def test_at_and_target_renamed(self):
        doc = _flow_model(
            [{"name": "base-step", "type": "return", "value": "$x"}],
            extra_flow_keys={"hooks": [{"at": "before", "target": "base-step", "steps": [
                {"name": "extra", "type": "map", "input": "$a", "out": "$b"},
            ]}]},
        )
        result = migrate_document(doc)
        hook = doc["flows"][0]["hooks"][0]
        self.assertEqual("before", hook["position"])
        self.assertEqual("base-step", hook["targetStep"])
        self.assertNotIn("at", hook)
        self.assertNotIn("target", hook)
        self.assertEqual("$b", hook["steps"][0]["output"])
        self.assertTrue(result.changed)


class OrchestrationRuleMigrationTest(unittest.TestCase):
    def test_scalar_action_normalized_to_actions_list(self):
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "orchestrationRules": [{
                "name": "Rule1",
                "trigger": {"type": "event", "event": "X"},
                "action": {"type": "create", "targetConcept": "Y", "map": {"a": "$event.a"}},
            }],
        }
        result = migrate_document(doc)
        rule = doc["orchestrationRules"][0]
        self.assertNotIn("action", rule)
        self.assertEqual(1, len(rule["actions"]))
        self.assertEqual("Y", rule["actions"][0]["concept"])
        self.assertNotIn("targetConcept", rule["actions"][0])
        self.assertTrue(result.changed)

    def test_both_action_and_actions_present_is_ambiguous(self):
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "orchestrationRules": [{
                "name": "Rule1",
                "trigger": {"type": "event", "event": "X"},
                "action": {"type": "create", "concept": "Y", "map": {"a": "1"}},
                "actions": [{"type": "create", "concept": "Z", "map": {"a": "2"}}],
            }],
        }
        result = migrate_document(doc)
        rule = doc["orchestrationRules"][0]
        self.assertIn("action", rule)
        self.assertIn("actions", rule)
        self.assertEqual(1, len(result.ambiguities))

    def test_top_level_orchestrations_key_renamed(self):
        # Found migrating AppGen/apps/invoice-bonds-demo for real (docs/CORPUS_INTEGRITY_PLAN.md
        # C2): this repo's schema has never accepted any spelling but 'orchestrationRules', but the
        # 2.A.6 corpus scan never covered AppGen/apps, so this alias was invisible to it.
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "orchestrations": [{
                "name": "Rule1",
                "trigger": {"type": "event", "event": "X"},
                "action": {"type": "create", "concept": "Y", "map": {"a": "1"}},
            }],
        }
        result = migrate_document(doc)
        self.assertNotIn("orchestrations", doc)
        self.assertIn("orchestrationRules", doc)
        rule = doc["orchestrationRules"][0]
        self.assertNotIn("action", rule)  # scalar-action normalization ran in the same pass
        self.assertEqual(1, len(rule["actions"]))
        self.assertTrue(result.changed)

    def test_both_orchestrations_and_orchestrationRules_present_is_ambiguous(self):
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "orchestrations": [{"name": "Old", "trigger": {"type": "event", "event": "X"},
                                 "action": {"type": "create", "concept": "Y", "map": {}}}],
            "orchestrationRules": [{"name": "New", "trigger": {"type": "event", "event": "Z"},
                                     "actions": [{"type": "create", "concept": "W", "map": {}}]}],
        }
        result = migrate_document(doc)
        self.assertIn("orchestrations", doc)
        self.assertIn("orchestrationRules", doc)
        self.assertEqual(1, len(result.ambiguities))

    def test_orchestrations_key_rename_is_idempotent(self):
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "orchestrations": [{"name": "Rule1", "trigger": {"type": "event", "event": "X"},
                                 "actions": [{"type": "create", "concept": "Y", "map": {}}]}],
        }
        first = migrate_document(doc)
        self.assertTrue(first.changed)
        before = copy.deepcopy(doc)
        second = migrate_document(doc)
        self.assertFalse(second.changed)
        self.assertEqual(before, doc)

    def test_orchestration_action_type_enum_value_never_rewritten(self):
        # orchestrationAction.type's own canonical values (create/callCapability/scheduleEvent) must
        # NEVER be touched by the flowStep.type alias table, even though "callCapability" and
        # "scheduleEvent" are also flowStep aliases in a completely different context.
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "orchestrationRules": [{
                "name": "Rule1",
                "trigger": {"type": "event", "event": "X"},
                "actions": [{"type": "callCapability", "capabilityName": "notification", "op": "send", "map": {}}],
            }],
        }
        migrate_document(doc)
        action = doc["orchestrationRules"][0]["actions"][0]
        self.assertEqual("callCapability", action["type"])
        self.assertEqual("notification", action["capability"])
        self.assertEqual("send", action["operation"])


class CompiledModelDetectionTest(unittest.TestCase):
    def test_compiled_step_shape_is_detected_and_left_untouched(self):
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [],
            "flows": [{
                "name": "AsyncFlow",
                "steps": [{
                    "name": "save-user", "type": "capability",
                    "mapFromRef": "", "mapToRef": "", "returnValueRef": "",
                    "awaitEventName": "", "awaitRef": "", "eventDataRefs": {},
                    "capabilityCall": {"capabilityName": "persistence", "operation": "save"},
                }],
            }],
        }
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertTrue(result.is_compiled)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)

    def test_reference_manifest_shape_without_flows_key_is_a_no_op(self):
        # A generator metadata manifest (e.g. references.manifest.json) has its own unrelated
        # "targetConcept" field and no "flows"/"orchestrationRules" -- must be a complete no-op.
        doc = {
            "metadataManifestVersion": "1.0.0",
            "items": [{"concept": "Appointment", "fieldPath": "patientId", "targetConcept": "Patient"}],
        }
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertFalse(result.is_compiled)
        self.assertEqual(before, doc)


if __name__ == "__main__":
    unittest.main()
