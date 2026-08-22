"""R1.6: the `npdev_impact` MCP tool -- registry wiring + argv construction only.

Mirrors test_mcp_agent_context_tools.py's own pattern (registry invariant: every declared tool has
a dispatched handler and vice versa) and its own note that NPDevMcp/server.py has no tests directory
of its own -- this lives under NPDevCli/tests/ because that is the ONE Python test tree the gates
discover (run-ai-knowledge-gate.ps1's `python -m unittest discover -s NPDevCli/tests`).

The CLI's own composition logic (which legs run, problemsFound, schema conformance) is proven in
test_impact_cli.py; this file only proves the MCP-side plumbing -- the exact argv it delegates to,
and that a CLI failure surfaces as a tool error, not a crash.

Stdlib-only (unittest + unittest.mock). Run with:
    python -m unittest NPDevCli.tests.test_impact_mcp_tool -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

_REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO_ROOT / "NPDevMcp"))

import server  # noqa: E402


def _text_of(result: dict) -> str:
    return result["content"][0]["text"]


class ImpactToolRegistryTest(unittest.TestCase):
    def test_declared_and_dispatched(self):
        declared = {tool["name"] for tool in server.TOOLS}
        self.assertIn("npdev_impact", declared)
        self.assertIn("npdev_impact", server.TOOL_HANDLERS)
        self.assertIs(server.TOOL_HANDLERS["npdev_impact"], server.tool_impact)

    def test_tools_list_advertises_it_with_a_real_input_schema(self):
        response = server.handle_request({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        advertised = {tool["name"]: tool for tool in response["result"]["tools"]}
        self.assertIn("npdev_impact", advertised)
        schema = advertised["npdev_impact"]["inputSchema"]
        self.assertEqual(schema["type"], "object")
        self.assertEqual(set(schema["required"]), {"baseline", "current"})
        self.assertTrue(advertised["npdev_impact"]["description"].strip())

    def test_module_docstring_names_the_tool(self):
        self.assertIn("npdev_impact", server.__doc__)


class ImpactToolArgvTest(unittest.TestCase):
    def test_baseline_and_current_are_required(self):
        result = server.tool_impact({})
        self.assertTrue(result["isError"])

        result = server.tool_impact({"baseline": "b.json"})
        self.assertTrue(result["isError"])

    def test_minimal_call_shape(self):
        seen: list[list[str]] = []

        def fake_run_cli(args, timeout=600):
            seen.append(args)
            return {"ok": True, "exitCode": 0, "stdout": "{}\n", "stderr": ""}

        with patch.object(server, "run_cli", fake_run_cli):
            result = server.tool_impact({"baseline": "b.json", "current": "c.json"})

        self.assertEqual(seen, [["impact", "--baseline", "b.json", "--current", "c.json"]])
        self.assertFalse(result["isError"])

    def test_of_and_manifest_are_threaded_through_when_given(self):
        seen: list[list[str]] = []

        def fake_run_cli(args, timeout=600):
            seen.append(args)
            return {"ok": True, "exitCode": 0, "stdout": "{}\n", "stderr": ""}

        with patch.object(server, "run_cli", fake_run_cli):
            server.tool_impact({
                "baseline": "b.json", "current": "c.json",
                "of": "Order.status", "manifest": "m.json",
            })

        self.assertEqual(seen, [[
            "impact", "--baseline", "b.json", "--current", "c.json",
            "--of", "Order.status", "--manifest", "m.json",
        ]])

    def test_a_problem_found_exit_still_passes_through_the_report_not_an_error(self):
        """`impact` exits 2 for a REAL structured problem (unresolved xref / failed authoring gate)
        -- run_cli's own `ok` already treats exit 2 as a valid, non-error result ("validation
        reported errors (a valid result)", see run_cli's own comment), same convention
        npdev_validate/npdev_migration_diff rely on. The tool must hand the caller the report text,
        not swallow it as a bare error, so an agent can actually read WHAT the problem was."""
        def fake_run_cli(args, timeout=600):
            self.assertEqual(args, ["impact", "--baseline", "b.json", "--current", "c.json"])
            return {"ok": True, "exitCode": 2,
                    "stdout": '{"problemsFound": true}\n', "stderr": ""}

        with patch.object(server, "run_cli", fake_run_cli):
            result = server.tool_impact({"baseline": "b.json", "current": "c.json"})

        self.assertFalse(result["isError"])
        self.assertIn("problemsFound", _text_of(result))


if __name__ == "__main__":
    unittest.main()
