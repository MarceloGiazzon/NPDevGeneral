"""Tests for npdev_host.run_checks -- the 12-check catalogue (NPDEV_HOST plan, H5).

One case per check id: a fixture that is wrong must report `fix` (or `warn` for the two checks
that are not machine-fixable), and a fixture that is right must report `ok`. Reuses
npdev_monitor.probe_app for liveness -- a couple of checks (app-running, db-reachable,
schema-current) need a real listening socket to exercise their `ok` branch, so those spin up a
tiny local HTTP/TCP server for the duration of one test rather than mocking probe_app's internals.
"""

from __future__ import annotations

import json
import socket
import sys
import tempfile
import threading
import unittest
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_host  # noqa: E402


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _free_tcp_port() -> int:
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class _HealthServer:
    """A real, minimal /actuator/health responder -- so app-running/schema-current's `ok` branch
    is exercised against genuine liveness, not a mocked probe."""

    def __enter__(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 -- BaseHTTPRequestHandler's own naming
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}')

            def log_message(self, *a):
                pass

        self._server = HTTPServer(("127.0.0.1", 0), Handler)
        outer.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()
        return False


def _app(tmp: str, *, engine="H2Server", server_port=None, physical=True,
         db_host=None, db_port=None) -> Path:
    app_dir = Path(tmp)
    _write_json(app_dir / "_ops" / "resolved-db-plan.json", {
        "appId": "myapp",
        "engine": engine,
        "serverPort": server_port or _free_tcp_port(),
        "resolvedDatabaseName": "npdev_myapp",
        "physicalDatabase": physical,
        "host": db_host,
        "hostPort": db_port,
    })
    return app_dir


def _local_plan(*, target=None, reachable_kind="nobody", provider=None,
                survives_restart=True, engine="H2Server") -> dict:
    return {
        "schemaVersion": "npdev-host-plan.v1", "appId": "myapp", "rung": 0, "target": target,
        "runsOn": {"kind": "this-machine", "port": 8080},
        "dataLivesOn": {"kind": "this-machine", "engine": engine,
                        "survivesRestart": survives_restart, "survivesThisMachineDying": False},
        "reachableBy": {"kind": reachable_kind, "provider": provider},
        "authMode": "apikey",
    }


def _findings_by_id(findings: list[dict]) -> dict:
    return {f["id"]: f for f in findings}


class AppRunningTest(unittest.TestCase):

    def test_fix_when_nothing_is_listening(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)  # port 1: never listening in a test sandbox
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["app-running"]["status"], "fix")

    def test_ok_when_health_endpoint_answers_up(self):
        with _HealthServer() as server, tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=server.port)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["app-running"]["status"], "ok")


class AuthFailClosedTest(unittest.TestCase):

    def test_fix_when_no_key_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["auth-fail-closed"]["status"], "fix")
            self.assertTrue(findings["auth-fail-closed"]["fixable"])

    def test_fix_when_only_the_known_default_key_is_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "secrets" / "api-key.env", "NPDEV_AUTH_APIKEYS=key=t:a:dev-key\n")
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["auth-fail-closed"]["status"], "fix")

    def test_ok_when_a_real_key_is_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "secrets" / "api-key.env",
                        "NPDEV_AUTH_APIKEYS=key=tenant:actor:ADMIN\n")
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["auth-fail-closed"]["status"], "ok")


class DbReachableTest(unittest.TestCase):

    def test_ok_when_engine_has_no_physical_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, engine="InMemory", physical=False, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(engine="InMemory")))
            self.assertEqual(findings["db-reachable"]["status"], "ok")

    def test_fix_when_the_database_port_is_unreachable(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, engine="Postgres", server_port=1, db_host="127.0.0.1", db_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(engine="Postgres")))
            self.assertEqual(findings["db-reachable"]["status"], "fix")

    def test_ok_when_the_database_port_is_reachable(self):
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            db_port = listener.getsockname()[1]
            with tempfile.TemporaryDirectory() as tmp:
                app_dir = _app(tmp, engine="Postgres", server_port=1, db_host="127.0.0.1", db_port=db_port)
                findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(engine="Postgres")))
                self.assertEqual(findings["db-reachable"]["status"], "ok")


class SchemaCurrentTest(unittest.TestCase):

    def test_ok_when_engine_has_no_physical_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, engine="InMemory", physical=False, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(engine="InMemory")))
            self.assertEqual(findings["schema-current"]["status"], "ok")

    def test_warn_when_the_app_has_never_booted(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, engine="Postgres", server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(engine="Postgres")))
            self.assertEqual(findings["schema-current"]["status"], "warn")

    def test_ok_when_the_app_is_running(self):
        with _HealthServer() as server, tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, engine="Postgres", server_port=server.port)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(engine="Postgres")))
            self.assertEqual(findings["schema-current"]["status"], "ok")


class ForwardedHeadersTest(unittest.TestCase):

    def test_ok_when_nobody_can_reach_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(reachable_kind="nobody")))
            self.assertEqual(findings["forwarded-headers"]["status"], "ok")

    def test_fix_when_reachable_and_property_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(reachable_kind="tunnel")))
            self.assertEqual(findings["forwarded-headers"]["status"], "fix")

    def test_ok_when_reachable_and_property_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "src" / "main" / "resources" / "application-prod.properties",
                        "server.forward-headers-strategy=framework\n")
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(reachable_kind="tunnel")))
            self.assertEqual(findings["forwarded-headers"]["status"], "ok")


class HealthDetailsTest(unittest.TestCase):

    def test_fix_when_reachable_and_details_always_shown(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(reachable_kind="tunnel")))
            self.assertEqual(findings["health-details"]["status"], "fix")

    def test_ok_when_reachable_and_when_authorized_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "src" / "main" / "resources" / "application-prod.properties",
                        "management.endpoint.health.show-details=when-authorized\n")
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(reachable_kind="tunnel")))
            self.assertEqual(findings["health-details"]["status"], "ok")


class PortBindingTest(unittest.TestCase):

    def test_fix_when_port_is_hardcoded(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "src" / "main" / "resources" / "application.properties", "server.port=8080\n")
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["port-binding"]["status"], "fix")

    def test_ok_when_port_honours_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "src" / "main" / "resources" / "application.properties",
                        "server.port=${PORT:${SERVER_PORT:8080}}\n")
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan()))
            self.assertEqual(findings["port-binding"]["status"], "ok")


class MemoryPostureTest(unittest.TestCase):

    def test_ok_when_target_has_no_tight_ceiling(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(target=None)))
            self.assertEqual(findings["memory-posture"]["status"], "ok")

    def test_fix_when_target_is_tight_and_dockerfile_lacks_the_setting(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1, engine="Postgres")
            _write_text(app_dir / "Dockerfile", "FROM eclipse-temurin:21-jre-alpine\n")
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(target="render-neon", engine="Postgres")))
            self.assertEqual(findings["memory-posture"]["status"], "fix")

    def test_ok_when_target_is_tight_and_dockerfile_has_the_setting(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1, engine="Postgres")
            _write_text(app_dir / "Dockerfile",
                        'ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65"\n')
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(target="render-neon", engine="Postgres")))
            self.assertEqual(findings["memory-posture"]["status"], "ok")


class DataDurabilityTest(unittest.TestCase):

    def test_ok_when_data_survives_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(survives_restart=True)))
            self.assertEqual(findings["data-durability"]["status"], "ok")

    def test_warn_when_ephemeral_and_unacknowledged(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(survives_restart=False)))
            self.assertEqual(findings["data-durability"]["status"], "warn")

    def test_ok_when_ephemeral_and_acknowledged(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            npdev_host.write_definition(app_dir, {
                "schemaVersion": "npdev-host-definition.v1", "rung": 3,
                "acknowledged": ["data-disappears-on-restart"],
            })
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(survives_restart=False)))
            self.assertEqual(findings["data-durability"]["status"], "ok")


class KeyRetrievableTest(unittest.TestCase):

    def test_ok_when_target_has_persistent_disk(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(target="vps")))
            self.assertEqual(findings["key-retrievable"]["status"], "ok")

    def test_fix_when_no_persistent_disk_and_no_bootstrap_property(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(target="render-h2")))
            self.assertEqual(findings["key-retrievable"]["status"], "fix")

    def test_ok_when_no_persistent_disk_but_bootstrap_hash_set(self):
        import os

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            os.environ["NPDEV_SUPERUSER_BOOTSTRAPKEYHASH"] = "deadbeef"
            try:
                findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(target="render-h2")))
                self.assertEqual(findings["key-retrievable"]["status"], "ok")
            finally:
                del os.environ["NPDEV_SUPERUSER_BOOTSTRAPKEYHASH"]


class EngineMatchesTargetTest(unittest.TestCase):

    def test_ok_when_no_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(target=None)))
            self.assertEqual(findings["engine-matches-target"]["status"], "ok")

    def test_fix_when_engine_does_not_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1, engine="H2Server")
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(target="render-neon", engine="H2Server")))
            self.assertEqual(findings["engine-matches-target"]["status"], "fix")

    def test_ok_when_engine_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1, engine="Postgres")
            findings = _findings_by_id(
                npdev_host.run_checks(app_dir, plan=_local_plan(target="render-neon", engine="Postgres")))
            self.assertEqual(findings["engine-matches-target"]["status"], "ok")


class TunnelBinaryTest(unittest.TestCase):

    def test_ok_when_not_using_a_tunnel(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(app_dir, plan=_local_plan(reachable_kind="nobody")))
            self.assertEqual(findings["tunnel-binary"]["status"], "ok")

    def test_fix_when_tunnel_binary_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            findings = _findings_by_id(npdev_host.run_checks(
                app_dir, plan=_local_plan(reachable_kind="tunnel", provider="a-binary-that-does-not-exist")))
            self.assertEqual(findings["tunnel-binary"]["status"], "fix")

    def test_ok_when_tunnel_binary_is_on_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            # python itself is always on PATH in this test environment -- stands in for a real
            # tunnel binary just to exercise the "found on PATH" branch.
            findings = _findings_by_id(npdev_host.run_checks(
                app_dir, plan=_local_plan(reachable_kind="tunnel", provider="python")))
            self.assertEqual(findings["tunnel-binary"]["status"], "ok")


class ApplyFixesTest(unittest.TestCase):
    """H6: --fix must be idempotent and additive -- running it twice changes nothing the second
    time, and every repaired finding must actually flip to `ok` afterward."""

    def test_fixable_findings_are_repaired_and_become_ok(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            plan = _local_plan(reachable_kind="tunnel")

            before = _findings_by_id(npdev_host.run_checks(app_dir, plan=plan))
            self.assertEqual(before["forwarded-headers"]["status"], "fix")
            self.assertEqual(before["health-details"]["status"], "fix")
            self.assertEqual(before["auth-fail-closed"]["status"], "fix")

            changes, after_findings = npdev_host.apply_fixes(app_dir, plan=plan)
            after = _findings_by_id(after_findings)

            self.assertTrue(changes)
            self.assertEqual(after["forwarded-headers"]["status"], "ok")
            self.assertEqual(after["health-details"]["status"], "ok")
            self.assertEqual(after["auth-fail-closed"]["status"], "ok")

    def test_running_fix_twice_changes_nothing_the_second_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            plan = _local_plan(reachable_kind="tunnel")

            npdev_host.apply_fixes(app_dir, plan=plan)
            second_changes, second_findings = npdev_host.apply_fixes(app_dir, plan=plan)

            self.assertEqual(second_changes, [])
            self.assertTrue(all(f["status"] != "fix" or not f["fixable"] for f in second_findings))

    def test_port_binding_fix_replaces_the_hardcoded_line_not_appends_a_second_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            _write_text(app_dir / "src" / "main" / "resources" / "application.properties",
                        "server.port=8080\n")
            npdev_host.apply_fixes(app_dir, plan=_local_plan())
            text = (app_dir / "src" / "main" / "resources" / "application.properties").read_text(encoding="utf-8")
            self.assertEqual(text.count("server.port="), 1)
            self.assertIn("${PORT:${SERVER_PORT:8080}}", text)


class HostCheckCliTest(unittest.TestCase):

    def test_check_reports_exit_1_when_something_needs_fixing(self):
        import io
        from contextlib import redirect_stdout

        with tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=1)
            npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 1,
                                                    "reachableBy": {"kind": "tunnel", "provider": "cloudflared"}})
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "check", "--app", str(app_dir), "--json"])
            self.assertEqual(code, 1)
            result = json.loads(buffer.getvalue())
            self.assertFalse(result["ok"])
            self.assertGreater(result["counts"]["fix"], 0)

    def test_check_fix_reports_exit_0_once_repaired(self):
        import io
        from contextlib import redirect_stdout

        with _HealthServer() as server, tempfile.TemporaryDirectory() as tmp:
            app_dir = _app(tmp, server_port=server.port)
            npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 0})
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "check", "--app", str(app_dir), "--fix", "--json"])
            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            self.assertTrue(result["ok"])


class _FakeRemoteApp:
    """A tiny real HTTP server standing in for a deployed app -- H15's remote checks are tested
    against genuine sockets/responses, never mocked HTTP internals."""

    def __enter__(self):
        state = {"health": {"status": "UP"}, "login_location": None, "login_body": "sign in"}
        self.state = state

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                if self.path.startswith("/actuator/health"):
                    body = json.dumps(state["health"]).encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(body)
                elif self.path.startswith("/login"):
                    if state["login_location"]:
                        self.send_response(302)
                        self.send_header("Location", state["login_location"])
                        self.end_headers()
                    else:
                        self.send_response(200)
                        self.send_header("Content-Type", "text/html")
                        self.end_headers()
                        self.wfile.write(state["login_body"].encode("utf-8"))
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b"ok")

            def log_message(self, *a):
                pass

        self._server = HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        self.url = f"http://127.0.0.1:{self.port}"
        import threading

        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()
        return False


class RemoteCheckTest(unittest.TestCase):
    """H15: `npdev host check --target <remote>` -- a remote check must never require credentials
    it cannot have; anything unknowable from outside reports `unknown`, never a false `ok`."""

    def test_reachable_ok_when_the_app_answers(self):
        with _FakeRemoteApp() as app:
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-reachable"]["status"], "ok")

    def test_reachable_fix_when_nothing_answers(self):
        findings = _findings_by_id(npdev_host.run_remote_checks("http://127.0.0.1:1/"))
        self.assertEqual(findings["remote-reachable"]["status"], "fix")

    def test_tls_fix_for_a_plain_http_url(self):
        with _FakeRemoteApp() as app:
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-tls"]["status"], "fix")

    def test_forwarded_headers_ok_when_no_internal_origin_leaks(self):
        with _FakeRemoteApp() as app:
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-forwarded-headers"]["status"], "ok")

    def test_forwarded_headers_fix_when_a_localhost_redirect_leaks(self):
        with _FakeRemoteApp() as app:
            app.state["login_location"] = "http://localhost:8080/dashboard"
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-forwarded-headers"]["status"], "fix")

    def test_health_details_ok_when_only_status_is_shown(self):
        with _FakeRemoteApp() as app:
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-health-details"]["status"], "ok")

    def test_health_details_fix_when_components_leak(self):
        with _FakeRemoteApp() as app:
            app.state["health"] = {"status": "UP", "components": {"db": {"status": "UP"}}}
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-health-details"]["status"], "fix")

    def test_auth_fail_closed_is_always_unknown_from_outside(self):
        with _FakeRemoteApp() as app:
            findings = _findings_by_id(npdev_host.run_remote_checks(app.url))
            self.assertEqual(findings["remote-auth-fail-closed"]["status"], "unknown")

    def test_run_checks_dispatches_to_remote_when_remote_is_given(self):
        with _FakeRemoteApp() as app:
            findings = _findings_by_id(npdev_host.run_checks(Path("."), plan={}, remote=app.url))
            self.assertIn("remote-reachable", findings)
            self.assertNotIn("app-running", findings)


if __name__ == "__main__":
    unittest.main()
