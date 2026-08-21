"""Tests for the four agent-context MCP tools (R1.3): npdev_validate_structural,
npdev_get_constrained_schema, npdev_core_context, npdev_app_context.

Two things are locked in here:
  1. The registry invariant -- every entry in NPDevMcp/server.py's TOOLS has a callable handler in
     TOOL_HANDLERS and vice versa, and `tools/list` advertises all four new names. A tool declared
     but not dispatched is invisible to an agent in exactly the way a missing tool is.
  2. The plumbing of each new tool: the exact CLI/script argv it delegates to, its staleness rule,
     and its failure shape. The backends themselves (NPDevCli's --structural-only path,
     scripts/ai/derive_constrained_schemas.py, scripts/ai/build_core_context.py) are already real
     and tested by running them; what is new here is only the wiring, so the subprocess boundary is
     where these tests stub.

NPDevMcp/server.py had NO automated tests before this file (see scripts/policy/coverage-baseline.json's
own NPDevMcp note). It lives under NPDevCli/tests/ because that is the ONE Python test tree the
gates discover -- run-ai-knowledge-gate.ps1 step [18/35] runs
`python -m unittest discover -s NPDevCli/tests`, and a test no gate runs is not a test.

Stdlib-only (unittest + unittest.mock), matching this repo's convention for CLI-adjacent tests.
    python -m unittest NPDevCli.tests.test_mcp_agent_context_tools -v
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

_REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO_ROOT / "NPDevMcp"))

import server  # noqa: E402

NEW_TOOLS = (
    "npdev_validate_structural",
    "npdev_get_constrained_schema",
    "npdev_core_context",
    "npdev_app_context",
)


def _text_of(result: dict) -> str:
    return result["content"][0]["text"]


class ToolRegistryTest(unittest.TestCase):
    """A declared-but-undispatched tool reads to an agent exactly like a missing one."""

    def test_declared_and_dispatched_sets_match(self):
        declared = {tool["name"] for tool in server.TOOLS}
        self.assertEqual(declared, set(server.TOOL_HANDLERS))

    def test_every_handler_is_callable(self):
        for name, handler in server.TOOL_HANDLERS.items():
            self.assertTrue(callable(handler), f"{name} is not callable")

    def test_the_four_agent_context_tools_are_registered(self):
        declared = {tool["name"] for tool in server.TOOLS}
        for name in NEW_TOOLS:
            self.assertIn(name, declared)
            self.assertIn(name, server.TOOL_HANDLERS)

    def test_tools_list_advertises_them_with_input_schemas(self):
        response = server.handle_request({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        advertised = {tool["name"]: tool for tool in response["result"]["tools"]}
        for name in NEW_TOOLS:
            self.assertIn(name, advertised)
            self.assertEqual(advertised[name]["inputSchema"]["type"], "object")
            self.assertTrue(advertised[name]["description"].strip())

    def test_module_docstring_names_the_four_tools(self):
        for name in NEW_TOOLS:
            self.assertIn(name, server.__doc__)


class ValidateStructuralTest(unittest.TestCase):
    def test_delegates_to_the_structural_only_cli_flag(self):
        seen: list[list[str]] = []

        def fake_run_cli(args, timeout=600):
            seen.append(args)
            return {"ok": True, "exitCode": 0,
                    "stdout": "schema validation passed (semantic checks NOT run -- re-run without "
                              "--structural-only)\n",
                    "stderr": ""}

        with patch.object(server, "run_cli", fake_run_cli):
            result = server.tool_validate_structural({"model_path": "some/model.json"})
        self.assertEqual(seen, [["validate", "model", "some/model.json", "--structural-only"]])
        self.assertFalse(result["isError"])
        self.assertIn("semantic checks NOT run", _text_of(result))

    def test_model_path_is_required(self):
        result = server.tool_validate_structural({})
        self.assertTrue(result["isError"])

    def test_schema_failure_surfaces_the_cli_detail(self):
        failed = {"ok": False, "exitCode": 1, "stdout": "",
                  "stderr": "npdev: canonical model schema validation failed: /concepts/0 required\n"}
        with patch.object(server, "run_cli", lambda args, timeout=600: failed):
            result = server.tool_validate_structural({"model_path": "m.json"})
        self.assertTrue(result["isError"])
        self.assertIn("canonical model schema validation failed", _text_of(result))


class ConstrainedSchemaTest(unittest.TestCase):
    """Derive-on-demand + refresh-when-the-source-moved, without running the real deriver."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.build_root = Path(self._tmp.name)
        self.derived = self.build_root / "npdev-ai" / "constrained" / "ai-model.constrained.schema.json"
        self.addCleanup(self._tmp.cleanup)
        env = patch.dict(os.environ, {"NPDEV_BUILD_ROOT": str(self.build_root)})
        env.start()
        self.addCleanup(env.stop)

    def _deriver(self, calls: list[list[str]], payload: dict | None = None):
        def fake(script, args, timeout=300):
            calls.append([script, *args])
            self.derived.parent.mkdir(parents=True, exist_ok=True)
            self.derived.write_text(json.dumps(payload or {"type": "object"}), encoding="utf-8")
            return {"ok": True, "exitCode": 0, "stdout": "{}", "stderr": ""}
        return fake

    def test_derives_on_first_request_then_serves_the_cached_copy(self):
        calls: list[list[str]] = []
        with patch.object(server, "run_ai_script", self._deriver(calls, {"type": "object", "x": 1})):
            first = server.tool_get_constrained_schema({"schema_name": "ai-model"})
            second = server.tool_get_constrained_schema({"schema_name": "ai-model"})
        self.assertFalse(first["isError"])
        self.assertEqual(json.loads(_text_of(second)), {"type": "object", "x": 1})
        self.assertEqual(calls, [["derive_constrained_schemas.py", "ai-model"]])

    def test_a_newer_source_schema_forces_re_derivation(self):
        calls: list[list[str]] = []
        with patch.object(server, "run_ai_script", self._deriver(calls)):
            server.tool_get_constrained_schema({"schema_name": "ai-model"})
            # Age the derived copy so the real (untouched) source schema is newer.
            os.utime(self.derived, (1_000_000, 1_000_000))
            server.tool_get_constrained_schema({"schema_name": "ai-model"})
        self.assertEqual(len(calls), 2)

    def test_refresh_forces_re_derivation_of_a_current_copy(self):
        calls: list[list[str]] = []
        with patch.object(server, "run_ai_script", self._deriver(calls)):
            server.tool_get_constrained_schema({"schema_name": "ai-model"})
            server.tool_get_constrained_schema({"schema_name": "ai-model", "refresh": True})
        self.assertEqual(len(calls), 2)

    def test_unknown_schema_fails_before_running_the_deriver(self):
        calls: list[list[str]] = []
        with patch.object(server, "run_ai_script", self._deriver(calls)):
            result = server.tool_get_constrained_schema({"schema_name": "no-such-schema"})
        self.assertTrue(result["isError"])
        self.assertEqual(calls, [])

    def test_the_full_canonical_model_schema_is_refused_with_a_pointer(self):
        result = server.tool_get_constrained_schema({"schema_name": "model"})
        self.assertTrue(result["isError"])
        self.assertIn("npdev_get_schema", _text_of(result))

    def test_alias_normalization_matches_npdev_get_schema(self):
        # 'config' is a documented alias; the two schema tools must agree on it.
        self.assertEqual(server._ai_schema_stem("config"), "ai-generator-config")
        self.assertEqual(server._ai_schema_stem("ai-model.schema.json"), "ai-model")

    def test_a_failed_deriver_reports_its_stderr(self):
        def failing(script, args, timeout=300):
            return {"ok": False, "exitCode": 1, "stdout": "", "stderr": "boom"}
        with patch.object(server, "run_ai_script", failing):
            result = server.tool_get_constrained_schema({"schema_name": "ai-model"})
        self.assertTrue(result["isError"])
        self.assertIn("boom", _text_of(result))


class CoreContextTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.build_root = Path(self._tmp.name)
        self.out_dir = self.build_root / "npdev-ai" / "core-context"
        self.out_dir.mkdir(parents=True)
        # bundle.json, not bundle.md: build_core_context.py writes the same text to both, and the
        # MCP tool reads the JSON one so that no script reads a .md file (CLAUDE.md's frozen
        # zero-markdown-reads rule). The .md remains the human-facing artifact.
        self.bundle = self.out_dir / "bundle.json"
        self.manifest = self.out_dir / "manifest.json"
        self.addCleanup(self._tmp.cleanup)
        env = patch.dict(os.environ, {"NPDEV_BUILD_ROOT": str(self.build_root)})
        env.start()
        self.addCleanup(env.stop)

    def _write_bundle(self, sources: list[str]) -> None:
        manifest = {
            "generatedFrom": "scripts/ai/build_core_context.py",
            "contentSha256": "deadbeef",
            "bytes": 30,
            "sections": [{"title": s, "source": s, "bytes": 1} for s in sources],
        }
        # Mirrors the real builder's output shape: the manifest fields plus the bundle text under
        # "markdown".
        self.bundle.write_text(
            json.dumps({**manifest, "markdown": "# NPDev core authoring context\n"}),
            encoding="utf-8",
        )
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")

    def test_serves_a_current_bundle_without_rebuilding(self):
        self._write_bundle(["content/authoring-for-ai.json"])
        calls: list[str] = []
        with patch.object(server, "run_ai_script",
                          lambda script, args, timeout=300: calls.append(script)):
            result = server.tool_core_context({})
        self.assertEqual(calls, [])
        self.assertIn("core authoring context", _text_of(result))

    def test_manifest_only_returns_the_hash_and_the_bundle_path(self):
        self._write_bundle(["content/authoring-for-ai.json"])
        with patch.object(server, "run_ai_script", lambda *a, **k: self.fail("should not rebuild")):
            result = server.tool_core_context({"manifest_only": True})
        payload = json.loads(_text_of(result))
        self.assertEqual(payload["contentSha256"], "deadbeef")
        self.assertEqual(Path(payload["bundle"]), self.bundle)

    def test_a_missing_bundle_is_stale(self):
        self.assertTrue(server._core_context_stale(self.bundle, self.manifest))

    def test_a_source_newer_than_the_bundle_is_stale(self):
        # content/authoring-for-ai.json is a real repo file; ageing the bundle past it is enough.
        self._write_bundle(["content/authoring-for-ai.json"])
        self.assertFalse(server._core_context_stale(self.bundle, self.manifest))
        os.utime(self.bundle, (1_000_000, 1_000_000))
        self.assertTrue(server._core_context_stale(self.bundle, self.manifest))

    def test_an_unreadable_manifest_is_stale_rather_than_trusted(self):
        self.bundle.write_text("x", encoding="utf-8")
        self.manifest.write_text("{not json", encoding="utf-8")
        self.assertTrue(server._core_context_stale(self.bundle, self.manifest))

    def test_rebuilds_when_stale_and_reports_a_failed_build(self):
        calls: list[str] = []

        def failing(script, args, timeout=300):
            calls.append(script)
            return {"ok": False, "exitCode": 1, "stdout": "", "stderr": "no content/ dir"}

        with patch.object(server, "run_ai_script", failing):
            result = server.tool_core_context({})
        self.assertEqual(calls, ["build_core_context.py"])
        self.assertTrue(result["isError"])
        self.assertIn("no content/ dir", _text_of(result))


class AppContextTest(unittest.TestCase):
    APP = {"model": "m.json", "namespace": "demo", "counts": {"concepts": 1},
           "concepts": [{"name": "Customer", "fields": []}], "flows": []}

    def _fake_inspect(self, ok: bool = True):
        def fake(args, timeout=600):
            self.assertEqual(args[:2], ["inspect", "app"])
            return {"ok": ok, "exitCode": 0 if ok else 1,
                    "stdout": json.dumps(self.APP) if ok else "",
                    "stderr": "" if ok else "npdev: model not found"}
        return fake

    def test_composes_inspect_app_with_the_constrained_schema(self):
        with patch.object(server, "run_cli", self._fake_inspect()), \
             patch.object(server, "tool_get_constrained_schema",
                          lambda a: server._text(json.dumps({"type": "object"}))):
            result = server.tool_app_context({"model_path": "m.json"})
        payload = json.loads(_text_of(result))
        self.assertEqual(payload["app"]["namespace"], "demo")
        self.assertEqual(payload["constrainedSchema"], {"type": "object"})
        self.assertEqual(payload["constrainedSchemaName"], "ai-model")

    def test_include_schema_false_returns_the_app_summary_alone(self):
        with patch.object(server, "run_cli", self._fake_inspect()), \
             patch.object(server, "tool_get_constrained_schema",
                          lambda a: self.fail("should not derive a schema")):
            result = server.tool_app_context({"model_path": "m.json", "include_schema": False})
        self.assertNotIn("constrainedSchema", json.loads(_text_of(result)))

    def test_a_schema_failure_is_reported_not_swallowed(self):
        """"No shape constraint was applied" must not read as "anything goes"."""
        with patch.object(server, "run_cli", self._fake_inspect()), \
             patch.object(server, "tool_get_constrained_schema",
                          lambda a: server._text_error("deriver unavailable")):
            result = server.tool_app_context({"model_path": "m.json"})
        payload = json.loads(_text_of(result))
        self.assertIsNone(payload["constrainedSchema"])
        self.assertIn("deriver unavailable", payload["constrainedSchemaError"])

    def test_a_failed_inspect_is_an_error_not_an_empty_context(self):
        with patch.object(server, "run_cli", self._fake_inspect(ok=False)):
            result = server.tool_app_context({"model_path": "missing.json"})
        self.assertTrue(result["isError"])
        self.assertIn("model not found", _text_of(result))

    def test_model_path_is_required(self):
        self.assertTrue(server.tool_app_context({})["isError"])


if __name__ == "__main__":
    unittest.main()
