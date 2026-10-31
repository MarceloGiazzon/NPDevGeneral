"""The MCP server teaches ONE model dialect: canonical model.json.

ai-model.v1 (`app`/`entities`, flow `operation: create|read|update|delete|list`) is an older second
dialect; external AIs taught both wrote invalid hybrids (`input.mode: "list"` 14x, `"read"` 6x).
It stays reachable by explicit name only, and every surface that serves it labels it deprecated.

Stdlib-only: python -m unittest discover -s NPDevMcp/tests
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import server  # noqa: E402


def _text_of(result: dict) -> str:
    return result["content"][0]["text"]


class CanonicalDialectTest(unittest.TestCase):
    """Every MCP surface teaches the canonical model.json dialect; ai-model.v1 is reachable only by
    explicit name and always labelled deprecated (AIs taught both wrote `mode: "list"` hybrids)."""

    def test_no_tool_description_offers_ai_model_except_as_deprecated(self):
        for tool in server.TOOLS:
            text = json.dumps(tool)
            if "ai-model" in text.replace("ai-model.v1", "").replace("ai-modelv1", ""):
                self.assertIn("deprecated", text, f"{tool['name']} offers ai-model as current")

    def test_no_tool_default_is_ai_model(self):
        for tool in server.TOOLS:
            for name, prop in tool["inputSchema"].get("properties", {}).items():
                self.assertNotIn("Default 'ai-model'", prop.get("description", ""),
                                 f"{tool['name']}.{name}")

    def test_get_schema_model_is_the_canonical_schema_unmarked(self):
        schema = json.loads(_text_of(server.tool_get_schema({"schema_name": "model"})))
        self.assertIn("concepts", schema["properties"])
        self.assertNotIn("DEPRECATED", schema.get("$comment", ""))

    def test_get_schema_ai_model_carries_the_deprecation_notice(self):
        schema = json.loads(_text_of(server.tool_get_schema({"schema_name": "ai-model"})))
        self.assertTrue(schema["$comment"].startswith("DEPRECATED ai-model.v1"))

    def test_list_schemas_names_model_canonical_and_ai_model_deprecated(self):
        listing = json.loads(_text_of(server.tool_list_schemas({})))
        self.assertIn("ai-model", listing["deprecated"])
        self.assertIn("the one to author", listing["specialCaseNames"]["model"])

    def test_initialize_instructions_teach_the_canonical_dialect(self):
        response = server.handle_request({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                          "params": {}})
        instructions = response["result"]["instructions"]
        self.assertIn("input.mode", instructions)
        self.assertNotIn("ai-model", instructions)


class AppContextDefaultTest(unittest.TestCase):
    def test_default_embeds_no_schema_and_teaches_the_canonical_dialect(self):
        app = {"namespace": "demo", "concepts": [], "flows": []}
        with patch.object(server, "run_cli",
                          lambda args, timeout=600: {"ok": True, "stdout": json.dumps(app)}),              patch.object(server, "tool_get_constrained_schema",
                          lambda a: self.fail("no schema is derived by default")):
            payload = json.loads(_text_of(server.tool_app_context({"model_path": "m.json"})))
        self.assertNotIn("constrainedSchema", payload)
        self.assertIn(server.CANONICAL_HINT, payload["note"])


class MarkDeprecatedTest(unittest.TestCase):
    def test_non_deprecated_or_non_object_text_passes_through_verbatim(self):
        self.assertEqual(server._mark_deprecated("custom-panel", '{"a": 1}'), '{"a": 1}')
        self.assertEqual(server._mark_deprecated("ai-model", "not json"), "not json")
        self.assertEqual(server._mark_deprecated("ai-model", "[1]"), "[1]")

    def test_comment_goes_first_and_keeps_the_original(self):
        marked = json.loads(server._mark_deprecated("ai-model", '{"type": "object", "$comment": "x"}'))
        self.assertEqual(next(iter(marked)), "$comment")
        self.assertTrue(marked["$comment"].endswith("| x"))
        self.assertEqual(marked["type"], "object")


if __name__ == "__main__":
    unittest.main()
