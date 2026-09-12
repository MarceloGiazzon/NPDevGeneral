"""Tests for NPDevMcp/server.py's AI-authoring-bridge tools: originally the three from W3.4,
2026-08-25 remediation plan / QUAL-32's COV-MCP finding (npdev_search_examples, npdev_search_fix,
npdev_check_support), plus P2.4's semantic-graph consumers (npdev_graph_explain, npdev_graph_reuse).

Before this file, `git ls-files` under NPDevMcp/ returned exactly server.py + README.md -- the
surface an external agent actually drives had zero automated regression coverage. Stdlib-only
(unittest), matching NPDevCli/tests' convention. Run with:
    python -m unittest discover -s NPDevMcp/tests -p "test_*.py" -v

Each tool reads its index from build_root()/"npdev-ai"/<file>.json (build_root() honours
NPDEV_BUILD_ROOT). Tests point that env var at a temp directory holding hand-built fixture indexes
in the real shape those tools parse -- not a dependency on scripts/ai/build_knowledge.py actually
having run, so this suite is deterministic and needs no build step.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import server  # noqa: E402


class _WithBuildRoot(unittest.TestCase):
    """Points NPDEV_BUILD_ROOT at a fresh temp dir with an npdev-ai/ subdirectory for each test."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.build_root = Path(self._tmp.name)
        (self.build_root / "npdev-ai").mkdir(parents=True, exist_ok=True)
        self._prior_env = os.environ.get("NPDEV_BUILD_ROOT")
        os.environ["NPDEV_BUILD_ROOT"] = str(self.build_root)

    def tearDown(self) -> None:
        if self._prior_env is None:
            os.environ.pop("NPDEV_BUILD_ROOT", None)
        else:
            os.environ["NPDEV_BUILD_ROOT"] = self._prior_env
        self._tmp.cleanup()

    def write_index(self, name: str, payload: dict) -> None:
        (self.build_root / "npdev-ai" / name).write_text(json.dumps(payload), encoding="utf-8")


class ToolSearchExamplesTest(_WithBuildRoot):
    def test_missing_index_returns_actionable_error_not_a_crash(self):
        result = server.tool_search_examples({"query": "bond onDelete"})

        self.assertTrue(result["isError"])
        self.assertIn("build_rag_index.py", result["content"][0]["text"])

    def test_empty_query_is_rejected(self):
        self.write_index("rag-index.json", {"chunks": []})

        result = server.tool_search_examples({"query": "   "})

        self.assertTrue(result["isError"])
        self.assertIn("query is required", result["content"][0]["text"])

    def test_ranks_matches_by_term_overlap_and_respects_limit(self):
        self.write_index("rag-index.json", {"chunks": [
            {"title": "Bond onDelete", "objectType": "example", "source": "s1",
             "text": "bond anchor port onDelete cascade example"},
            {"title": "Panel orderBy", "objectType": "example", "source": "s2",
             "text": "panel orderBy field ascending"},
            {"title": "Bond via", "objectType": "example", "source": "s3",
             "text": "bond via join table example"},
        ]})

        result = server.tool_search_examples({"query": "bond onDelete", "limit": 1})

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual(1, len(payload["matches"]))
        self.assertEqual("Bond onDelete", payload["matches"][0]["title"])

    def test_knowledge_card_chunks_are_boosted_above_equal_term_overlap(self):
        self.write_index("rag-index.json", {"chunks": [
            {"title": "Example match", "objectType": "example", "source": "s1",
             "text": "propertyScopes cascade"},
            {"title": "Card match", "objectType": "knowledge-card", "source": "s2",
             "text": "propertyScopes cascade"},
        ]})

        result = server.tool_search_examples({"query": "propertyScopes cascade", "limit": 2})

        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("Card match", payload["matches"][0]["title"],
                          "a knowledge-card chunk with equal raw term overlap must rank first "
                          "(KNOWLEDGE_CARD_BOOST) -- otherwise the boost is dead code")


class ToolSearchFixTest(_WithBuildRoot):
    def test_missing_index_returns_actionable_error(self):
        result = server.tool_search_fix({"message": "unknown field 'foo' on concept 'Bar'"})

        self.assertTrue(result["isError"])
        self.assertIn("build_knowledge.py", result["content"][0]["text"])

    def test_empty_message_is_rejected(self):
        self.write_index("failure-index.json", {"signatures": []})

        result = server.tool_search_fix({"message": ""})

        self.assertTrue(result["isError"])

    def test_exact_signature_match_wins_over_keyword_fallback(self):
        message = "unknown field 'department' on concept 'Employee'"
        signature = server.normalize_signature(message, None, None, None)
        self.write_index("failure-index.json", {"signatures": [
            {"signature": signature, "examples": [{"message": message, "fix": "add the field"}]},
            {"signature": "some-other-signature", "examples": [
                {"message": "unrelated diagnostic mentioning department", "fix": "unrelated fix"}
            ]},
        ]})

        result = server.tool_search_fix({"message": message})

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual(1, len(payload["matches"]))
        self.assertEqual("signature", payload["matches"][0]["match"])
        self.assertEqual(signature, payload["matches"][0]["signature"])

    def test_keyword_fallback_when_no_exact_signature_matches(self):
        self.write_index("failure-index.json", {"signatures": [
            {"signature": "sig-a", "examples": [
                {"message": "bond onDelete requires an explicit strategy", "fix": "declare onDelete"}
            ]},
            {"signature": "sig-b", "examples": [
                {"message": "panel orderBy field must exist", "fix": "fix the field name"}
            ]},
        ]})

        result = server.tool_search_fix({"message": "onDelete strategy missing for this bond"})

        payload = json.loads(result["content"][0]["text"])
        self.assertGreaterEqual(len(payload["matches"]), 1)
        self.assertEqual("keyword", payload["matches"][0]["match"])
        self.assertEqual("sig-a", payload["matches"][0]["signature"],
                          "the bond/onDelete signature shares more terms with the query than the "
                          "panel/orderBy one and must rank first")


class ToolCheckSupportTest(_WithBuildRoot):
    def test_missing_index_returns_actionable_error(self):
        result = server.tool_check_support({"feature": "ARCH-10b"})

        self.assertTrue(result["isError"])
        self.assertIn("build_knowledge.py", result["content"][0]["text"])

    def test_empty_feature_is_rejected(self):
        self.write_index("capabilities.json", {"items": [], "cards": []})

        result = server.tool_check_support({"feature": ""})

        self.assertTrue(result["isError"])

    def test_exact_ledger_id_hit_is_case_insensitive(self):
        self.write_index("capabilities.json", {
            "items": [{"id": "ARCH-10b", "title": "Something", "notes": "", "category": "gap"}],
            "cards": [],
        })

        result = server.tool_check_support({"feature": "arch-10b"})

        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("ledger-id", payload["resolvedBy"])
        self.assertEqual("ARCH-10b", payload["result"]["id"])

    def test_keyword_match_over_ledger_items_and_cards(self):
        self.write_index("capabilities.json", {
            "items": [{"id": "REG-1", "title": "panel orderBy is not indexed", "notes": "", "category": "gap"}],
            "cards": [{"id": "card-1", "title": "unrelated", "body": "", "keywords": [], "appliesTo": []}],
        })

        result = server.tool_check_support({"feature": "panel orderBy"})

        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("keyword", payload["resolvedBy"])
        self.assertEqual(1, len(payload["ledgerItems"]))
        self.assertEqual("REG-1", payload["ledgerItems"][0]["id"])
        self.assertEqual(0, len(payload["knowledgeCards"]))

    def test_no_match_reports_unknown_never_fabricates_a_positive(self):
        self.write_index("capabilities.json", {"items": [], "cards": []})

        result = server.tool_check_support({"feature": "some feature nothing tracks"})

        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("none", payload["resolvedBy"])
        self.assertEqual("unknown", payload["result"],
                          "absence of a tracked gap must never be reported as a positive support claim")


class JsonRpcDispatchTest(_WithBuildRoot):
    """One end-to-end test through handle_request's real tools/call dispatch, proving the JSON-RPC
    wiring (not just the bare tool function) actually routes to these three tools."""

    def test_tools_call_routes_to_check_support_and_wraps_the_result(self):
        self.write_index("capabilities.json", {
            "items": [{"id": "REG-42", "title": "x", "notes": "", "category": "gap"}],
            "cards": [],
        })

        response = server.handle_request({
            "jsonrpc": "2.0",
            "id": 7,
            "method": "tools/call",
            "params": {"name": "npdev_check_support", "arguments": {"feature": "REG-42"}},
        })

        self.assertEqual(7, response["id"])
        self.assertFalse(response["result"]["isError"])
        payload = json.loads(response["result"]["content"][0]["text"])
        self.assertEqual("ledger-id", payload["resolvedBy"])

    def test_unknown_tool_name_is_a_protocol_error_not_a_crash(self):
        response = server.handle_request({
            "jsonrpc": "2.0",
            "id": 8,
            "method": "tools/call",
            "params": {"name": "npdev_does_not_exist", "arguments": {}},
        })

        self.assertEqual(-32602, response["error"]["code"])


class _WithSemanticGraph(unittest.TestCase):
    """Writes a hand-built semantic-graph.json (real shape from schemas/ai/semantic-graph.schema.json,
    modeled on the npdev-canary sample) to a fresh temp dir for each test."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.graph_path = Path(self._tmp.name) / "semantic-graph.json"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write_graph(self, nodes: list, edges: list) -> None:
        self.graph_path.write_text(json.dumps({
            "schemaVersion": "npdev-semantic-graph.v1",
            "model": "CanaryModel",
            "summary": {"nodes": len(nodes), "edges": len(edges)},
            "nodes": nodes,
            "edges": edges,
        }), encoding="utf-8")


_CANARY_NODES = [
    {"kind": "concept", "name": "CanaryTask"},
    {"kind": "field", "name": "CanaryTask.priority"},
    {"kind": "flow", "name": "CreateCanaryTask"},
    {"kind": "panel", "name": "CanaryPanel"},
    {"kind": "procedure", "name": "SaveCanaryTaskProcedure"},
]
_CANARY_EDGES = [
    {"from": {"kind": "flow", "name": "CreateCanaryTask"}, "verb": "operatesOn",
     "to": {"kind": "concept", "name": "CanaryTask"}, "site": "flow.concept",
     "path": "flows[CreateCanaryTask].concept"},
    {"from": {"kind": "panel", "name": "CanaryPanel"}, "verb": "calls",
     "to": {"kind": "procedure", "name": "SaveCanaryTaskProcedure"}, "site": "panel.actions.procedure",
     "path": "panels[CanaryPanel].actions[0].procedure"},
]


class ToolGraphExplainTest(_WithSemanticGraph):
    def test_missing_graph_returns_actionable_error_not_a_crash(self):
        result = server.tool_graph_explain({"graph_path": str(self.graph_path), "name": "CanaryTask"})

        self.assertTrue(result["isError"])
        self.assertIn("npdev_generate", result["content"][0]["text"])

    def test_missing_name_is_rejected(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_explain({"graph_path": str(self.graph_path)})

        self.assertTrue(result["isError"])
        self.assertIn("name is required", result["content"][0]["text"])

    def test_unknown_name_reports_similar_names_never_fabricates(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_explain({"graph_path": str(self.graph_path), "name": "CanaryTsk"})

        self.assertTrue(result["isError"])
        self.assertIn("no node named", result["content"][0]["text"])
        self.assertIn("CanaryTask", result["content"][0]["text"])

    def test_element_referenced_by_others_explains_why_it_exists(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_explain({"graph_path": str(self.graph_path), "name": "CanaryTask"})

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual(1, len(payload["incomingEdges"]))
        self.assertIn("CreateCanaryTask", payload["existsBecause"][0])
        self.assertIn("exists because", payload["summary"])

    def test_element_with_no_edges_says_so_explicitly(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_explain({"graph_path": str(self.graph_path), "name": "CanaryTask.priority"})

        payload = json.loads(result["content"][0]["text"])
        self.assertEqual([], payload["incomingEdges"])
        self.assertEqual([], payload["outgoingEdges"])
        self.assertIn("no recorded edges", payload["summary"])

    def test_ambiguous_name_across_kinds_asks_for_disambiguation(self):
        self.write_graph(_CANARY_NODES + [{"kind": "event", "name": "CanaryTask"}], _CANARY_EDGES)

        result = server.tool_graph_explain({"graph_path": str(self.graph_path), "name": "CanaryTask"})

        self.assertTrue(result["isError"])
        self.assertIn("ambiguous", result["content"][0]["text"])


class ToolGraphReuseTest(_WithSemanticGraph):
    def test_missing_intent_is_rejected(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_reuse({"graph_path": str(self.graph_path), "intent": "  "})

        self.assertTrue(result["isError"])
        self.assertIn("intent is required", result["content"][0]["text"])

    def test_matching_intent_ranks_the_matching_node_with_its_wiring(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_reuse({"graph_path": str(self.graph_path), "intent": "canary task priority"})

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        names = [c["node"]["name"] for c in payload["candidates"]]
        self.assertIn("CanaryTask", names)
        top = payload["candidates"][0]
        self.assertGreater(top["score"], 0)

    def test_no_matching_terms_returns_empty_list_not_a_fabricated_guess(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_graph_reuse({"graph_path": str(self.graph_path), "intent": "zzz nonexistent qqq"})

        payload = json.loads(result["content"][0]["text"])
        self.assertEqual([], payload["candidates"])
        self.assertIn("not that nothing relevant exists", payload["disclaimer"])


class JsonRpcDispatchGraphTest(_WithSemanticGraph):
    def test_tools_call_routes_to_graph_explain_and_wraps_the_result(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        response = server.handle_request({
            "jsonrpc": "2.0",
            "id": 9,
            "method": "tools/call",
            "params": {"name": "npdev_graph_explain",
                       "arguments": {"graph_path": str(self.graph_path), "name": "CanaryTask"}},
        })

        self.assertEqual(9, response["id"])
        self.assertFalse(response["result"]["isError"])
        payload = json.loads(response["result"]["content"][0]["text"])
        self.assertEqual("CanaryTask", payload["element"]["name"])


class GraphAcceptanceSliceP24Test(unittest.TestCase):
    """P2.4's golden-scenario fixture: NPDevMcp/tests/fixtures/p2_4_canary_semantic_graph.json is a
    frozen copy of a REAL generated app's npdev/semantic-graph.json (npdev-canary, P2.2's emitter,
    verified byte-for-byte against the live Output/ copy on 2026-09-11), not a hand-simplified toy.

    P2.4's doneWhen: the MCP/authoring path answers 'what would I reuse for this intent' and 'why
    does this element exist' purely from the semantic graph. Each test below is one of those two
    questions, answered only by calling the real tool function against this fixture -- no mocking,
    no shortcut into the DSL/generator internals that produced the graph.
    """

    GRAPH_PATH = str(Path(__file__).resolve().parent / "fixtures" / "p2_4_canary_semantic_graph.json")

    def test_why_does_this_element_exist(self):
        result = server.tool_graph_explain({"graph_path": self.GRAPH_PATH, "name": "CanaryTask"})

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        # CanaryTask exists because a flow creates it, a procedure saves it, and a query reads it --
        # exactly the three real incoming edges the canary generator emitted.
        self.assertEqual(3, len(payload["incomingEdges"]))
        reasons = " ".join(payload["existsBecause"])
        self.assertIn("CreateCanaryTask", reasons)
        self.assertIn("SaveCanaryTaskProcedure", reasons)
        self.assertIn("OpenHighPriorityCanaryTasks", reasons)

    def test_what_would_i_reuse_for_this_intent(self):
        result = server.tool_graph_reuse({
            "graph_path": self.GRAPH_PATH,
            "intent": "show a high priority task on a panel",
        })

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        names = [c["node"]["name"] for c in payload["candidates"]]
        # The existing CanaryPanel + its priority-sorted query are real, reusable precedent for
        # "show a high-priority task" -- reusing them (or seeing how they're wired) beats
        # authoring a new panel/query from scratch.
        self.assertIn("CanaryPanel", names)
        self.assertIn("OpenHighPriorityCanaryTasks", names)
        top = payload["candidates"][0]
        self.assertTrue(top["alreadyWiredTo"], "the top candidate must show how it's already wired, "
                                                "not just that its name matched")


class ToolAuthorTierTest(_WithSemanticGraph):
    """P7.1: reuse > specialization > new concept > untrusted extension, over the canary graph
    for tiers 1/3/4 (specialization needs a real DSL model + the real resolver -- see
    ToolAuthorTierSpecializationTest below)."""

    def test_missing_intent_is_rejected(self):
        result = server.tool_author_tier({"proposal": {"name": "Whatever"}})

        self.assertTrue(result["isError"])
        self.assertIn("intent is required", result["content"][0]["text"])

    def test_missing_proposal_is_rejected(self):
        result = server.tool_author_tier({"intent": "track something"})

        self.assertTrue(result["isError"])
        self.assertIn("proposal is required", result["content"][0]["text"])

    def test_reuse_wins_when_an_existing_element_matches_strongly(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_author_tier({
            "intent": "canary task priority",
            "proposal": {"name": "CanaryTaskV2"},
            "graph_path": str(self.graph_path),
        })

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("reuse", payload["chosenTier"])
        trail = {t["tier"]: t for t in payload["tierTrail"]}
        self.assertTrue(trail["reuse"]["applies"])
        self.assertFalse(trail["specialization"]["applies"])
        self.assertFalse(trail["new-concept"]["applies"])
        self.assertFalse(trail["untrusted-extension"]["applies"])

    def test_new_concept_wins_with_no_inputs_and_no_untrusted_marker(self):
        result = server.tool_author_tier({
            "intent": "track something nobody has modeled yet",
            "proposal": {"name": "BrandNewThing"},
        })

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("new-concept", payload["chosenTier"])
        self.assertEqual(["reuse", "specialization"], payload["tiersSkippedForLackOfInput"])

    def test_untrusted_extension_wins_and_flags_enforcement_risk_when_tiers_were_skipped(self):
        result = server.tool_author_tier({
            "intent": "embed a bespoke calendar widget",
            "proposal": {"name": "BespokeCalendar", "implementation": {"mode": "untrustedExtension"}},
        })

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("untrusted-extension", payload["chosenTier"])
        trail = {t["tier"]: t for t in payload["tierTrail"]}
        self.assertIn("ENFORCEMENT RISK", trail["untrusted-extension"]["reason"])

    def test_untrusted_extension_wins_without_risk_flag_when_reuse_was_actually_checked(self):
        self.write_graph(_CANARY_NODES, _CANARY_EDGES)

        result = server.tool_author_tier({
            "intent": "zzz nonexistent qqq",
            "proposal": {"name": "BespokeCalendar", "implementation": {"mode": "untrustedExtension"}},
            "graph_path": str(self.graph_path),
        })

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        self.assertEqual("untrusted-extension", payload["chosenTier"])
        trail = {t["tier"]: t for t in payload["tierTrail"]}
        # reuse WAS checked (graph_path given) and genuinely found nothing -- only specialization
        # (no app_model_path given) should be named as an unproven skip.
        self.assertIn("specialization", trail["untrusted-extension"]["reason"])
        self.assertNotIn("reuse", trail["untrusted-extension"]["reason"])


class ToolAuthorTierSpecializationTest(unittest.TestCase):
    """P7.1 tier 2 has no standalone 'can X specialize Y' predicate -- these tests drive the REAL
    DSL resolver (via a subprocess to NPDevCli/npdev_cli.py) on a synthetic probe model built next
    to NPDevSamples/specialization-medical-invoice/Input/model.json, confirmed by hand against that
    real corpus fixture on 2026-09-11: a clean specialization reports status=passed with empty
    diagnostics; an unresolvable one reports a diagnostic whose message is prefixed BASE_NOT_FOUND:.
    """

    MODEL_PATH = str(
        Path(__file__).resolve().parents[2]
        / "NPDevSamples" / "specialization-medical-invoice" / "Input" / "model.json"
    )

    def test_probe_reports_resolves_cleanly_against_a_real_qualified_base(self):
        result = server._probe_specialization(
            self.MODEL_PATH,
            {"name": "ProbeInvoiceSpecialization", "fields": [{"name": "extra", "type": "string", "required": False}]},
            "invoicing::Invoice",
        )

        self.assertTrue(result["resolvesCleanly"], result.get("reason"))

    def test_probe_reports_base_not_found_against_an_unqualified_or_unknown_base(self):
        result = server._probe_specialization(
            self.MODEL_PATH,
            {"name": "ProbeInvoiceSpecialization", "fields": [{"name": "extra", "type": "string", "required": False}]},
            "NoSuchConcept",
        )

        self.assertFalse(result["resolvesCleanly"])
        self.assertIn("BASE_NOT_FOUND", result["reason"])

    def test_full_tool_falls_through_to_new_concept_when_the_reuse_candidate_does_not_specialize(self):
        with tempfile.TemporaryDirectory() as tmp:
            graph_path = Path(tmp) / "semantic-graph.json"
            graph_path.write_text(json.dumps({
                "schemaVersion": "npdev-semantic-graph.v1",
                "model": "ProbeModel",
                "summary": {"nodes": 1, "edges": 0},
                "nodes": [{"kind": "concept", "name": "SomeUnrelatedConcept"}],
                "edges": [],
            }), encoding="utf-8")

            # 5 terms, only "someunrelatedconcept" matches (score 1) -- enough to be the top
            # (only) candidate, not enough to win tier 1 outright (required_score == term count).
            result = server.tool_author_tier({
                "intent": "someunrelatedconcept access policy review workflow",
                "proposal": {"name": "ProbeInvoiceSpecialization", "fields": [{"name": "extra", "type": "string", "required": False}]},
                "graph_path": str(graph_path),
                "app_model_path": self.MODEL_PATH,
            })

        self.assertFalse(result["isError"])
        payload = json.loads(result["content"][0]["text"])
        trail = {t["tier"]: t for t in payload["tierTrail"]}
        self.assertFalse(trail["reuse"]["applies"])
        self.assertFalse(trail["specialization"]["applies"])
        self.assertIn("BASE_NOT_FOUND", trail["specialization"]["reason"])
        self.assertEqual("new-concept", payload["chosenTier"])


if __name__ == "__main__":
    unittest.main()
