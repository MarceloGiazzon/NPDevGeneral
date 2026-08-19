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


class AutoPanelTransactionHooksMigrationTest(unittest.TestCase):
    """Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4/§B.5)."""

    def _model_with_autopanel(self, transaction: dict) -> dict:
        return {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [],
            "autoPanels": [{"aggregate": "Movimento", "transaction": transaction}],
        }

    def test_recompute_migrates_to_hooks_on_field_change(self):
        doc = self._model_with_autopanel({"metadata": {"recompute": "RecalcularTotais"}})
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        transaction = doc["autoPanels"][0]["transaction"]
        self.assertEqual("RecalcularTotais", transaction["hooks"]["onFieldChange"])
        self.assertNotIn("recompute", transaction["metadata"])

    def test_recompute_object_shape_with_procedure_key_also_migrates(self):
        doc = self._model_with_autopanel({"metadata": {"recompute": {"procedure": "RecalcularTotais"}}})
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        self.assertEqual("RecalcularTotais", doc["autoPanels"][0]["transaction"]["hooks"]["onFieldChange"])

    def test_recompute_is_idempotent(self):
        doc = self._model_with_autopanel({"metadata": {"recompute": "RecalcularTotais"}})
        migrate_document(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual("RecalcularTotais", doc["autoPanels"][0]["transaction"]["hooks"]["onFieldChange"])

    def test_recompute_and_hooks_with_same_value_drops_redundant_alias(self):
        doc = self._model_with_autopanel({
            "metadata": {"recompute": "RecalcularTotais"},
            "hooks": {"onFieldChange": "RecalcularTotais"},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        self.assertNotIn("recompute", doc["autoPanels"][0]["transaction"]["metadata"])

    def test_recompute_and_hooks_with_different_values_is_left_untouched_and_reported(self):
        doc = self._model_with_autopanel({
            "metadata": {"recompute": "RecalcularTotais"},
            "hooks": {"onFieldChange": "OutraCoisa"},
        })
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)
        self.assertTrue(any("different values" in a for a in result.ambiguities))

    def test_no_recompute_declared_is_a_no_op(self):
        doc = self._model_with_autopanel({"metadata": {}})
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)

    def test_derived_list_migrates_to_derived_fields_object(self):
        doc = self._model_with_autopanel({
            "metadata": {"derived": [
                {"name": "origemTotal", "expression": "sum(itens[].quantidade)"},
                {"name": "destinoTotal", "expression": "sum(itens[].outro)", "label": "Destino Total"},
            ]},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        transaction = doc["autoPanels"][0]["transaction"]
        self.assertNotIn("derived", transaction["metadata"])
        fields = transaction["derivedFields"]
        self.assertEqual({"tier": "client", "expression": "sum(itens[].quantidade)"}, fields["origemTotal"])
        self.assertEqual(
            {"tier": "client", "expression": "sum(itens[].outro)", "label": "Destino Total"},
            fields["destinoTotal"])

    def test_derived_list_with_malformed_entries_drops_them_and_reports(self):
        doc = self._model_with_autopanel({
            "metadata": {"derived": [
                {"name": "origemTotal", "expression": "sum(itens[].quantidade)"},
                {"expression": "sum(itens[].outro)"},  # no name
                {"name": "noExpression"},  # no expression
            ]},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        fields = doc["autoPanels"][0]["transaction"]["derivedFields"]
        self.assertEqual(1, len(fields))
        self.assertIn("origemTotal", fields)
        self.assertTrue(any("dropped 2 malformed" in c for c in result.changes))

    def test_derived_is_idempotent(self):
        doc = self._model_with_autopanel({
            "metadata": {"derived": [{"name": "origemTotal", "expression": "sum(itens[].quantidade)"}]},
        })
        migrate_document(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)

    def test_derived_list_and_existing_derived_fields_is_left_untouched_and_reported(self):
        doc = self._model_with_autopanel({
            "metadata": {"derived": [{"name": "origemTotal", "expression": "sum(itens[].quantidade)"}]},
            "derivedFields": {"saldoFiscal": {"tier": "server", "procedure": "CalcularSaldoFiscal"}},
        })
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)
        self.assertTrue(any("both transaction.metadata.derived and transaction.derivedFields" in a
                             for a in result.ambiguities))

    def test_concept_bound_autopanel_with_no_transaction_is_a_no_op(self):
        doc = {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [],
            "autoPanels": [{"concept": "Widget"}],
        }
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)


class WorkbenchTypedSurfaceMigrationTest(unittest.TestCase):
    """Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): actions/visibleWhen/bandPickers."""

    def _model_with_autopanel(self, transaction: dict) -> dict:
        return {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [],
            "autoPanels": [{"aggregate": "Movimento", "transaction": transaction}],
        }

    # -- actions --------------------------------------------------------------------------------

    def test_actions_list_migrates_to_typed_actions(self):
        doc = self._model_with_autopanel({
            "metadata": {"actions": [
                {"label": "Gerar Demanda", "procedure": "GerarDemanda"},
                {"procedure": "Recalcular"},
            ]},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        transaction = doc["autoPanels"][0]["transaction"]
        self.assertNotIn("actions", transaction["metadata"])
        actions = transaction["actions"]
        self.assertEqual(2, len(actions))
        self.assertEqual({"label": "Gerar Demanda", "procedure": "GerarDemanda"}, actions[0])
        self.assertEqual({"procedure": "Recalcular"}, actions[1])

    def test_actions_with_apply_to_and_after_action_migrate(self):
        doc = self._model_with_autopanel({
            "metadata": {"actions": [
                {"procedure": "Sugerir",
                 "applyTo": {"collection": "itens", "mode": "appendRow", "map": {"a": "$b"}}},
                {"procedure": "Aplicar", "afterAction": {"procedure": "PosProcessar"}},
            ]},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        actions = doc["autoPanels"][0]["transaction"]["actions"]
        self.assertEqual({"collection": "itens", "mode": "appendRow", "map": {"a": "$b"}}, actions[0]["applyTo"])
        self.assertEqual("PosProcessar", actions[1]["afterAction"], "object-shape afterAction unwraps to a string")

    def test_actions_malformed_apply_to_is_dropped_but_action_survives(self):
        doc = self._model_with_autopanel({
            "metadata": {"actions": [
                {"procedure": "Sugerir", "applyTo": {"mode": "appendRow", "map": {"a": "$b"}}},  # no collection
            ]},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        actions = doc["autoPanels"][0]["transaction"]["actions"]
        self.assertEqual(1, len(actions))
        self.assertNotIn("applyTo", actions[0])

    def test_actions_entry_missing_procedure_is_dropped_and_reported(self):
        doc = self._model_with_autopanel({
            "metadata": {"actions": [
                {"procedure": "Real"},
                {"label": "no-op"},  # no procedure
            ]},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        actions = doc["autoPanels"][0]["transaction"]["actions"]
        self.assertEqual(1, len(actions))
        self.assertTrue(any("dropped 1 malformed" in c for c in result.changes))

    def test_actions_is_idempotent(self):
        doc = self._model_with_autopanel({"metadata": {"actions": [{"procedure": "Real"}]}})
        migrate_document(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)

    def test_actions_list_and_existing_actions_is_left_untouched_and_reported(self):
        doc = self._model_with_autopanel({
            "metadata": {"actions": [{"procedure": "Untyped"}]},
            "actions": [{"procedure": "Typed"}],
        })
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)
        self.assertTrue(any("both transaction.metadata.actions and transaction.actions" in a
                             for a in result.ambiguities))

    # -- visibleWhen ------------------------------------------------------------------------------

    def test_visible_when_map_migrates_to_typed_visible_when(self):
        doc = self._model_with_autopanel({
            "metadata": {"visibleWhen": {"itens": "$root.tipo == 'A'", "avulsos": ""}},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        transaction = doc["autoPanels"][0]["transaction"]
        self.assertNotIn("visibleWhen", transaction["metadata"])
        self.assertEqual({"itens": "$root.tipo == 'A'"}, transaction["visibleWhen"])
        self.assertTrue(any("dropped 1 malformed" in c for c in result.changes))

    def test_visible_when_is_idempotent(self):
        doc = self._model_with_autopanel({"metadata": {"visibleWhen": {"itens": "$root.tipo == 'A'"}}})
        migrate_document(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)

    def test_visible_when_and_existing_visible_when_is_left_untouched_and_reported(self):
        doc = self._model_with_autopanel({
            "metadata": {"visibleWhen": {"itens": "untyped"}},
            "visibleWhen": {"itens": "typed"},
        })
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)
        self.assertTrue(any("both transaction.metadata.visibleWhen and transaction.visibleWhen" in a
                             for a in result.ambiguities))

    # -- bandPickers ------------------------------------------------------------------------------

    def test_band_pickers_map_migrates_to_typed_band_pickers(self):
        doc = self._model_with_autopanel({
            "metadata": {"bandPickers": {
                "origens": {"panel": "MovtoOrigemSelection", "label": "Seleciona Ruas", "columns": ["local"]},
                "semvias": {"label": "ignored -- no panel"},
            }},
        })
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        transaction = doc["autoPanels"][0]["transaction"]
        self.assertNotIn("bandPickers", transaction["metadata"])
        pickers = transaction["bandPickers"]
        self.assertEqual(1, len(pickers))
        self.assertEqual(
            {"panel": "MovtoOrigemSelection", "label": "Seleciona Ruas", "columns": ["local"]},
            pickers["origens"])
        self.assertTrue(any("dropped 1 malformed" in c for c in result.changes))

    def test_band_pickers_is_idempotent(self):
        doc = self._model_with_autopanel({
            "metadata": {"bandPickers": {"origens": {"panel": "MovtoOrigemSelection"}}},
        })
        migrate_document(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)

    def test_band_pickers_and_existing_band_pickers_is_left_untouched_and_reported(self):
        doc = self._model_with_autopanel({
            "metadata": {"bandPickers": {"origens": {"panel": "Untyped"}}},
            "bandPickers": {"origens": {"panel": "Typed"}},
        })
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)
        self.assertTrue(any("both transaction.metadata.bandPickers and transaction.bandPickers" in a
                             for a in result.ambiguities))


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


class QueryProcedureAuditPolicyMigrationTest(unittest.TestCase):
    """R5.1: `queries[].auditPolicy`/`procedures[].auditPolicy` were retired (consumed by nothing at
    runtime) -- the codemod strips the dead key so an existing model keeps validating against the
    schema that no longer declares it."""

    def _doc(self, queries=None, procedures=None) -> dict:
        return {
            "namespace": "test", "dslVersion": "1.0.0", "version": "1.0", "concepts": [], "flows": [],
            "queries": queries or [], "procedures": procedures or [],
        }

    def test_audit_policy_dropped_from_query(self):
        doc = self._doc(queries=[
            {"name": "OpenWorkItems", "concept": "WorkItem", "auditPolicy": "read"},
        ])
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        self.assertNotIn("auditPolicy", doc["queries"][0])
        self.assertEqual("OpenWorkItems", doc["queries"][0]["name"])
        self.assertTrue(any("queries[0]" in c and "auditPolicy" in c for c in result.changes))

    def test_audit_policy_dropped_from_procedure(self):
        doc = self._doc(procedures=[
            {"name": "SubmitWorkItem", "steps": [], "auditPolicy": "write"},
        ])
        result = migrate_document(doc)
        self.assertTrue(result.changed)
        self.assertNotIn("auditPolicy", doc["procedures"][0])
        self.assertEqual("SubmitWorkItem", doc["procedures"][0]["name"])

    def test_no_audit_policy_present_is_a_no_op(self):
        doc = self._doc(
            queries=[{"name": "OpenWorkItems", "concept": "WorkItem"}],
            procedures=[{"name": "SubmitWorkItem", "steps": []}],
        )
        before = copy.deepcopy(doc)
        result = migrate_document(doc)
        self.assertFalse(result.changed)
        self.assertEqual(before, doc)

    def test_idempotent_second_run_makes_no_further_changes(self):
        doc = self._doc(
            queries=[{"name": "OpenWorkItems", "concept": "WorkItem", "auditPolicy": "none"}],
            procedures=[{"name": "SubmitWorkItem", "steps": [], "auditPolicy": "write"}],
        )
        first = migrate_document(doc)
        self.assertTrue(first.changed)
        before = copy.deepcopy(doc)
        second = migrate_document(doc)
        self.assertFalse(second.changed)
        self.assertEqual([], second.changes)
        self.assertEqual(before, doc)

    def test_other_fields_on_the_same_entry_are_untouched(self):
        doc = self._doc(queries=[{
            "name": "OpenWorkItems", "concept": "WorkItem", "where": "status == 'open'",
            "tracePolicy": "summary", "auditPolicy": "read",
        }])
        migrate_document(doc)
        query = doc["queries"][0]
        self.assertEqual("status == 'open'", query["where"])
        self.assertEqual("summary", query["tracePolicy"])
        self.assertNotIn("auditPolicy", query)


if __name__ == "__main__":
    unittest.main()
