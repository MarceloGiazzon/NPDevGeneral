"""The RAG indexer refuses doc sections whose JSON examples still use retired DSL-1 spellings."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

_REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO / "scripts" / "ai"))

import build_rag_index  # noqa: E402


def _fence(payload: str, lang: str = "json") -> str:
    return f"Intro text.\n\n```{lang}\n{payload}\n```\n\nOutro."


class StaleDslExamplesTest(unittest.TestCase):
    def test_retired_step_type_is_reported(self):
        body = _fence('{"name": "v", "type": "enforceInvariants", "scope": "User", "invariants": ["A"]}')
        changes = build_rag_index._stale_dsl_examples(body)
        self.assertTrue(changes)
        self.assertIn("invariantCheck", changes[0])

    def test_canonical_step_is_clean(self):
        body = _fence('{"name": "v", "type": "invariantCheck", "scope": "User", "invariants": ["A"]}')
        self.assertEqual([], build_rag_index._stale_dsl_examples(body))

    def test_retired_cap_op_out_in_a_flow_are_reported(self):
        flow = ('{"name": "F", "steps": [{"name": "s", "type": "capabilityCall", '
                '"cap": "persistence", "op": "save", "out": "$saved"}]}')
        changes = build_rag_index._stale_dsl_examples(_fence(flow))
        self.assertEqual(3, len(changes))

    def test_scalar_orchestration_action_is_reported(self):
        rule = ('{"name": "R", "trigger": {"type": "event", "event": "E"}, '
                '"action": {"type": "create", "concept": "C", "map": {"a": "b"}}}')
        self.assertTrue(build_rag_index._stale_dsl_examples(_fence(rule)))

    def test_canonical_orchestration_rule_is_clean(self):
        rule = ('{"name": "R", "trigger": {"type": "event", "event": "E"}, '
                '"actions": [{"type": "callCapability", "capability": "c", "operation": "o"}]}')
        self.assertEqual([], build_rag_index._stale_dsl_examples(_fence(rule)))

    def test_trigger_object_alone_is_not_mistaken_for_a_step(self):
        self.assertEqual([], build_rag_index._stale_dsl_examples(
            _fence('{ "type": "event", "event": "InvoiceIssued" }')))

    def test_jsonc_comments_are_stripped_and_unparseable_blocks_skipped(self):
        commented = _fence('{ "name": "F", // a comment with "quotes"\n'
                           '  "steps": [ {"type": "waitForEvent", "awaitEvent": "E"} ] }', "jsonc")
        self.assertTrue(build_rag_index._stale_dsl_examples(commented))
        self.assertEqual([], build_rag_index._stale_dsl_examples(_fence('{ "steps": [ ... ] }')))

    def test_chunk_content_doc_refuses_stale_section_and_keeps_clean_one(self):
        doc = {
            "sourceFile": "docs/X.md",
            "sections": [
                {"title": "Stale", "body": _fence('{"type": "waitForEvent", "awaitEvent": "E"}')},
                {"title": "Clean", "body": _fence('{"type": "awaitEvent", "awaitEvent": "E"}')},
            ],
        }
        refused: list[str] = []
        chunks = build_rag_index.chunk_content_doc("docs/content/x.json", doc, refused)
        self.assertEqual(["Clean"], [c["title"] for c in chunks])
        self.assertEqual(1, len(refused))
        self.assertIn("docs/X.md#Stale", refused[0])


if __name__ == "__main__":
    unittest.main()
