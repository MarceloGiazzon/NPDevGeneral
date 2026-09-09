"""Tests for npdev_host.py's plan model (NPDEV_HOST plan, H3/H4).

Pins the properties the plan calls out as load-bearing for everything downstream: a
host.definition.json round-trip, resolving the full host plan from a fixture
resolved-db-plan.json (never guessing the engine or port when one is missing), required_env's
exact shape for a managed Postgres target, and that every write lands as LF bytes with no CRLF
(pathlib.write_text has corrupted generated artifacts in this repo before -- LANDMINES #3).
"""

from __future__ import annotations

import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_host  # noqa: E402


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def _minimal_resolved_db_plan(**overrides) -> dict:
    base = {
        "appId": "myapp",
        "engine": "H2Server",
        "serverPort": 8100,
        "resolvedDatabaseName": "npdev_myapp",
        "physicalDatabase": True,
        "defaultSpringProfiles": None,
    }
    base.update(overrides)
    return base


class DefinitionRoundTripTest(unittest.TestCase):

    def test_write_then_read_returns_the_same_definition(self):
        with self._temp_app_dir() as app_dir:
            definition = {
                "schemaVersion": "npdev-host-definition.v1",
                "rung": 1,
                "target": None,
                "reachableBy": {"kind": "tunnel", "provider": "cloudflared", "through": "shared-ingress"},
                "authMode": "apikey",
                "acknowledged": ["data-disappears-on-restart"],
            }
            written_path = npdev_host.write_definition(app_dir, definition)

            self.assertTrue(written_path.is_file())
            self.assertEqual(npdev_host.read_definition(app_dir), definition)

    def test_definition_lives_beside_db_definition_in_the_definition_dir(self):
        with self._temp_app_dir() as app_dir:
            definition_dir = app_dir / "definition"
            npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 0})
            self.assertTrue((definition_dir / npdev_host.HOST_DEFINITION_FILENAME).is_file())

    def test_read_definition_is_none_when_absent(self):
        with self._temp_app_dir() as app_dir:
            self.assertIsNone(npdev_host.read_definition(app_dir))

    def test_write_definition_rejects_a_definition_that_fails_its_schema(self):
        with self._temp_app_dir() as app_dir:
            with self.assertRaises(ValueError):
                npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 99})

    def _temp_app_dir(self):
        import tempfile

        class _Ctx:
            def __enter__(self_inner):
                self_inner._tmp = tempfile.TemporaryDirectory()
                app_dir = Path(self_inner._tmp.name)
                # _detect_definition_dir only nests into definition/ when it holds model.json
                # (matching real AppGen apps) -- db.definition.json alone is not the trigger.
                _write_json(app_dir / "definition" / "model.json", {"model": "myapp"})
                _write_json(app_dir / "definition" / "db.definition.json", {"database": {"engine": "H2Server"}})
                return app_dir

            def __exit__(self_inner, *exc):
                self_inner._tmp.cleanup()
                return False

        return _Ctx()


class WriteBytesNoTranslationTest(unittest.TestCase):

    def test_written_files_contain_no_crlf(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            _write_json(app_dir / "definition" / "model.json", {"model": "myapp"})
            _write_json(app_dir / "definition" / "db.definition.json", {"database": {"engine": "H2Local"}})
            npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 0})
            npdev_host.write_state(app_dir, {"url": "https://example.trycloudflare.com", "pid": 123})

            definition_bytes = (app_dir / "definition" / npdev_host.HOST_DEFINITION_FILENAME).read_bytes()
            state_bytes = (app_dir / "data" / npdev_host.HOST_STATE_FILENAME).read_bytes()

            self.assertNotIn(b"\r\n", definition_bytes)
            self.assertNotIn(b"\r\n", state_bytes)


class StateRoundTripTest(unittest.TestCase):

    def test_read_state_is_empty_dict_when_absent(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(npdev_host.read_state(Path(tmp)), {})

    def test_write_then_read_state_round_trips(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            state = {"url": "https://foo.trycloudflare.com", "pid": 4242, "provider": "cloudflared"}
            npdev_host.write_state(app_dir, state)
            self.assertEqual(npdev_host.read_state(app_dir), state)


class LoadTargetsTest(unittest.TestCase):

    def test_render_neon_target_declares_postgres_and_no_persistent_disk(self):
        targets = {t["id"]: t for t in npdev_host.load_targets()["targets"]}
        self.assertIn("render-neon", targets)
        self.assertEqual(targets["render-neon"]["requiresEngine"], "Postgres")
        self.assertFalse(targets["render-neon"]["persistentDisk"])


class ResolvePlanTest(unittest.TestCase):

    def test_raises_when_the_app_has_not_been_generated_yet(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                npdev_host.resolve_plan(Path(tmp), {"schemaVersion": "npdev-host-definition.v1", "rung": 1})

    def test_resolves_a_postgres_app_targeting_render_neon(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            _write_json(app_dir / "_ops" / "resolved-db-plan.json", _minimal_resolved_db_plan(
                engine="Postgres", serverPort=8080, resolvedDatabaseName="npdev_myapp",
            ))
            definition = {
                "schemaVersion": "npdev-host-definition.v1",
                "rung": 3,
                "target": "render-neon",
                "reachableBy": {"kind": "platform-edge"},
                "authMode": "apikey",
                "acknowledged": ["data-disappears-on-restart"],
            }

            plan = npdev_host.resolve_plan(app_dir, definition)

            self.assertEqual(plan["appId"], "myapp")
            self.assertEqual(plan["target"], "render-neon")
            self.assertEqual(plan["runsOn"]["port"], 8080)
            self.assertEqual(plan["runsOn"]["portFromEnv"], "PORT")
            self.assertEqual(plan["dataLivesOn"]["engine"], "Postgres")
            self.assertEqual(plan["dataLivesOn"]["kind"], "managed")
            self.assertTrue(plan["dataLivesOn"]["survivesRestart"])
            self.assertTrue(plan["reachableBy"]["terminatesTlsElsewhere"])
            self.assertIn("SPRING_DATASOURCE_URL", plan["requiredEnv"])

    def test_local_h2_app_with_no_target_is_this_machine_and_survives_restart(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            _write_json(app_dir / "_ops" / "resolved-db-plan.json", _minimal_resolved_db_plan())
            definition = {
                "schemaVersion": "npdev-host-definition.v1",
                "rung": 1,
                "target": None,
                "reachableBy": {"kind": "tunnel", "provider": "cloudflared"},
            }

            plan = npdev_host.resolve_plan(app_dir, definition)

            self.assertEqual(plan["runsOn"]["kind"], "this-machine")
            self.assertEqual(plan["dataLivesOn"]["kind"], "this-machine")
            self.assertTrue(plan["dataLivesOn"]["survivesRestart"])
            self.assertFalse(plan["dataLivesOn"]["survivesThisMachineDying"])


class RequiredEnvTest(unittest.TestCase):

    def _postgres_managed_plan(self) -> dict:
        return {
            "schemaVersion": "npdev-host-plan.v1",
            "appId": "myapp",
            "rung": 3,
            "target": "render-neon",
            "runsOn": {"kind": "platform", "port": 8080, "portFromEnv": "PORT", "memoryMb": 512},
            "dataLivesOn": {
                "kind": "managed", "engine": "Postgres", "databaseName": "npdev_myapp",
                "survivesRestart": True, "survivesThisMachineDying": True,
            },
            "reachableBy": {"kind": "platform-edge", "provider": None, "through": "direct",
                             "domain": None, "terminatesTlsElsewhere": True},
            "authMode": "apikey",
        }

    def test_required_env_for_a_managed_postgres_target(self):
        env = npdev_host.required_env(self._postgres_managed_plan())

        self.assertEqual(env["SPRING_PROFILES_ACTIVE"]["value"], "prod,postgres")
        self.assertEqual(env["SPRING_PROFILES_ACTIVE"]["source"], "npdev")
        self.assertEqual(env["NPDEV_RUNTIME_MODE"]["value"], "postgres")
        self.assertEqual(env["NPDEV_STORAGE_MODE"]["value"], "jdbc")

        for var in ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
            self.assertEqual(env[var]["source"], "you")
            self.assertIsNone(env[var]["value"])

        self.assertEqual(env["NPDEV_AUTH_APIKEYS"]["source"], "you")
        # render-neon has no persistent disk (see hosting-targets.json) -- the super-user key
        # must be supplied, not left to the issued-by-default path (P5).
        self.assertIn("NPDEV_SUPERUSER_BOOTSTRAPKEYHASH", env)

    def test_no_env_var_names_gain_an_underscore_the_property_does_not_have(self):
        # LANDMINES #4: the hyphen is STRIPPED, never turned into an underscore.
        env = npdev_host.required_env(self._postgres_managed_plan())
        self.assertIn("NPDEV_AUTH_APIKEYS", env)
        self.assertNotIn("NPDEV_AUTH_API_KEYS", env)
        self.assertIn("NPDEV_SUPERUSER_BOOTSTRAPKEYHASH", env)
        self.assertNotIn("NPDEV_SUPERUSER_BOOTSTRAP_KEY_HASH", env)

    def test_nobody_reachable_plan_needs_no_api_key(self):
        plan = {
            "schemaVersion": "npdev-host-plan.v1", "appId": "myapp", "rung": 0, "target": None,
            "runsOn": {"kind": "this-machine", "port": 8080},
            "dataLivesOn": {"kind": "this-machine", "engine": "H2Server", "survivesRestart": True,
                             "survivesThisMachineDying": False},
            "reachableBy": {"kind": "nobody"},
            "authMode": "apikey",
        }
        env = npdev_host.required_env(plan)
        self.assertNotIn("NPDEV_AUTH_APIKEYS", env)


class HostPlanCliTest(unittest.TestCase):
    """H4: `npdev host plan` -- the CLI verb itself, not just the model underneath it."""

    def _generated_app(self, tmp: str, **overrides) -> Path:
        app_dir = Path(tmp)
        _write_json(app_dir / "_ops" / "resolved-db-plan.json", _minimal_resolved_db_plan(**overrides))
        _write_json(app_dir / "model.json", {"model": "myapp"})
        return app_dir

    def test_non_interactive_plan_writes_a_schema_valid_definition(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._generated_app(tmp)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "plan", "--app", str(app_dir), "--rung", "1", "--yes", "--json"])

            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            self.assertTrue(result["ok"])
            self.assertEqual(result["definition"]["rung"], 1)

            written = npdev_host.read_definition(app_dir)
            self.assertEqual(written["rung"], 1)

    def test_yes_without_rung_refuses_and_writes_nothing(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._generated_app(tmp)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "plan", "--app", str(app_dir), "--yes"])

            self.assertNotEqual(code, 0)
            self.assertIsNone(npdev_host.read_definition(app_dir))

    def test_refuses_when_the_app_has_not_been_generated_yet(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "plan", "--app", tmp, "--rung", "1", "--yes"])

            self.assertNotEqual(code, 0)

    def test_engine_mismatch_target_refuses_and_writes_nothing(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._generated_app(tmp, engine="H2Server")
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main([
                    "host", "plan", "--app", str(app_dir),
                    "--rung", "3", "--target", "render-neon", "--yes",
                ])

            self.assertNotEqual(code, 0)
            self.assertIsNone(npdev_host.read_definition(app_dir))

    def test_matching_engine_target_succeeds(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._generated_app(tmp, engine="Postgres")
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main([
                    "host", "plan", "--app", str(app_dir),
                    "--rung", "3", "--target", "render-neon", "--yes", "--json",
                ])

            self.assertEqual(code, 0)
            written = npdev_host.read_definition(app_dir)
            self.assertEqual(written["target"], "render-neon")


class HostExplainCliTest(unittest.TestCase):
    """H7: `npdev host explain` -- every 'FROM' column value must come from required_env's own
    `source` field, never a hardcoded string per row."""

    def test_explain_lists_required_env_with_derived_source(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            _write_json(app_dir / "_ops" / "resolved-db-plan.json", _minimal_resolved_db_plan(
                engine="Postgres", serverPort=8080, resolvedDatabaseName="npdev_myapp"))
            _write_json(app_dir / "model.json", {"model": "myapp"})
            npdev_cli.main(["host", "plan", "--app", str(app_dir),
                             "--rung", "3", "--target", "render-neon", "--yes"])

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "explain", "--app", str(app_dir), "--json"])

            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            env = result["requiredEnv"]
            self.assertEqual(env["SPRING_PROFILES_ACTIVE"]["source"], "npdev")
            self.assertEqual(env["SPRING_DATASOURCE_URL"]["source"], "you")


class HostKeysCliTest(unittest.TestCase):
    """H12: `npdev host keys` -- lists (masked), mints, and surfaces the super-user key. Reuses
    secrets/api-key.env, the SAME file/format Ensure-NpdevApiKey already writes -- never a second
    key store."""

    def _flat_app(self, tmp: str) -> Path:
        app_dir = Path(tmp)
        _write_json(app_dir / "_ops" / "resolved-db-plan.json", _minimal_resolved_db_plan())
        return app_dir

    def test_list_is_empty_before_any_key_exists(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._flat_app(tmp)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "keys", "--app", str(app_dir), "--json"])
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(buffer.getvalue())["keys"], [])

    def test_new_mints_a_key_the_env_var_name_is_npdev_auth_apikeys(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._flat_app(tmp)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "keys", "--app", str(app_dir), "--new", "--json"])
            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            # LANDMINES #4: no underscore before APIKEYS -- Spring's relaxed binding strips the
            # hyphen in npdev.auth.api-keys, it does not turn it into an underscore.
            self.assertEqual(result["envVar"], "NPDEV_AUTH_APIKEYS")
            self.assertTrue(result["key"])

            env_text = (app_dir / "secrets" / "api-key.env").read_text(encoding="utf-8")
            self.assertIn("NPDEV_AUTH_APIKEYS=", env_text)
            self.assertIn(result["key"], env_text)

    def test_list_after_new_shows_a_masked_key_never_the_raw_value(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._flat_app(tmp)
            npdev_cli.main(["host", "keys", "--app", str(app_dir), "--new",
                             "--tenant", "acme", "--actor", "alice", "--role", "ADMIN"])

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "keys", "--app", str(app_dir), "--json"])
            self.assertEqual(code, 0)
            keys = json.loads(buffer.getvalue())["keys"]
            self.assertEqual(1, len(keys))
            self.assertEqual(keys[0]["tenant"], "acme")
            self.assertEqual(keys[0]["actor"], "alice")
            self.assertEqual(keys[0]["role"], "ADMIN")
            self.assertIn("…", keys[0]["masked"])

    def test_superuser_reports_a_clear_failure_when_no_key_file_exists(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._flat_app(tmp)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "keys", "--app", str(app_dir), "--superuser", "--json"])
            self.assertEqual(code, 1)
            self.assertFalse(json.loads(buffer.getvalue())["ok"])

    def test_superuser_surfaces_the_key_when_the_file_exists(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = self._flat_app(tmp)
            (app_dir / "_ops" / "SUPER_USER_KEY.txt").write_text("issued-raw-key-123", encoding="utf-8")

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "keys", "--app", str(app_dir), "--superuser", "--json"])
            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            self.assertEqual(result["key"], "issued-raw-key-123")


if __name__ == "__main__":
    unittest.main()
