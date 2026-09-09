"""Tests for `npdev host deploy` (NPDEV_HOST plan, H14).

The refusal matters more than the success path (H14's own note): on an engine/target mismatch,
`deploy` must write NOTHING and exit non-zero, naming the three fix steps. On success, it writes
the manifest and prints exactly the variables `required_env()` returns -- never a hardcoded list.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_host  # noqa: E402


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def _generated_app(tmp: str, *, engine: str) -> Path:
    app_dir = Path(tmp)
    _write_json(app_dir / "_ops" / "resolved-db-plan.json", {
        "appId": "myapp", "engine": engine, "serverPort": 8080,
        "resolvedDatabaseName": "npdev_myapp", "physicalDatabase": True,
    })
    return app_dir


class HostDeployRefusalTest(unittest.TestCase):

    def test_refuses_and_writes_nothing_on_engine_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _generated_app(tmp, engine="H2Server")
            npdev_host.write_definition(app_dir, {
                "schemaVersion": "npdev-host-definition.v1", "rung": 3, "target": "render-neon",
                "reachableBy": {"kind": "platform-edge"},
            })

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "deploy", "--app", str(app_dir), "--json"])

            self.assertEqual(code, 1)
            result = json.loads(buffer.getvalue())
            self.assertFalse(result["ok"])
            self.assertEqual(result["code"], "ENGINE_MISMATCH")
            self.assertIn("database.engine", result["detail"])
            self.assertIn("databaseName", result["detail"])
            self.assertFalse((app_dir / "render.yaml").exists())

    def test_refuses_when_no_target_is_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _generated_app(tmp, engine="Postgres")
            npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 1})

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "deploy", "--app", str(app_dir)])

            self.assertNotEqual(code, 0)
            self.assertFalse((app_dir / "render.yaml").exists())

    def test_refuses_when_plan_was_never_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _generated_app(tmp, engine="Postgres")
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "deploy", "--app", str(app_dir)])
            self.assertNotEqual(code, 0)


class HostDeploySuccessTest(unittest.TestCase):

    def test_writes_render_yaml_and_lists_exactly_required_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _generated_app(tmp, engine="Postgres")
            npdev_host.write_definition(app_dir, {
                "schemaVersion": "npdev-host-definition.v1", "rung": 3, "target": "render-neon",
                "reachableBy": {"kind": "platform-edge"},
            })

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "deploy", "--app", str(app_dir), "--json"])

            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            self.assertTrue(result["ok"])
            self.assertTrue(any("render.yaml" in w for w in result["written"]))

            plan = npdev_host.resolve_plan(app_dir, npdev_host.read_definition(app_dir))
            self.assertEqual(set(result["requiredEnv"].keys()), set(plan["requiredEnv"].keys()))

            self.assertTrue((app_dir / "render.yaml").is_file())
            self.assertTrue((app_dir / ".env.render-neon.example").is_file())

    def test_writes_nothing_extra_for_vps(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _generated_app(tmp, engine="Postgres")
            npdev_host.write_definition(app_dir, {
                "schemaVersion": "npdev-host-definition.v1", "rung": 4, "target": "vps",
                "reachableBy": {"kind": "domain", "provider": "caddy"},
            })

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "deploy", "--app", str(app_dir), "--json"])

            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            self.assertEqual(result["written"], [])


if __name__ == "__main__":
    unittest.main()
