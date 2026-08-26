"""Tests for NPDevMcp/server.py's three AI-authoring-bridge tools (W3.4, 2026-08-25 remediation
plan / QUAL-32's COV-MCP finding): npdev_search_examples, npdev_search_fix, npdev_check_support.

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


if __name__ == "__main__":
    unittest.main()
