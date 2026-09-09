"""Tests for npdev_tunnel.py -- the provider abstraction (NPDEV_HOST plan, H9).

Every test injects a FAKE provider via `register_provider` -- none require a real cloudflared/
ngrok binary (H9 warning). A fake provider is a real subprocess (a tiny Python script) so
is_alive/stop are exercised against a genuine PID, not a mocked one.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_host  # noqa: E402
import npdev_tunnel  # noqa: E402

_SLEEPER_SCRIPT = "import time\ntime.sleep(60)\n"


def _fake_provider_factory(scratch: Path, spawned: list):
    """Returns a starter that spawns a real, short-lived-but-controllable child process (a Python
    sleep loop) so is_alive/stop exercise genuine PID semantics rather than a mock. `spawned`
    collects every Popen handle so the test can reap it in tearDown even if `stop()` did the OS-level
    kill by PID -- avoids both a leaked process and a ResourceWarning from an un-waited Popen."""

    script_path = scratch / "sleeper.py"
    script_path.write_text(_SLEEPER_SCRIPT, encoding="utf-8")

    def _starter(port: int, *, timeout: float = 30.0, **kwargs) -> dict:
        proc = subprocess.Popen([sys.executable, str(script_path)])
        spawned.append(proc)
        return {"url": f"https://fake-{port}.example.test", "pid": proc.pid,
                "provider": "fake", "startedAt": "2026-01-01T00:00:00+00:00"}

    return _starter


class ProviderAbstractionTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-tunnel-")
        self.scratch = Path(self._tmp.name)
        self._spawned: list = []
        npdev_tunnel.register_provider("fake", _fake_provider_factory(self.scratch, self._spawned))

    def tearDown(self):
        for proc in self._spawned:
            if proc.poll() is None:
                proc.kill()
            proc.wait(timeout=5)
        self._tmp.cleanup()

    def test_start_returns_the_documented_shape(self):
        state = npdev_tunnel.start("fake", 8080)
        try:
            self.assertIn("url", state)
            self.assertIn("pid", state)
            self.assertEqual(state["provider"], "fake")
            self.assertIn("startedAt", state)
        finally:
            npdev_tunnel.stop(state)

    def test_is_alive_true_while_running_false_after_stop(self):
        state = npdev_tunnel.start("fake", 8081)
        self.assertTrue(npdev_tunnel.is_alive(state))

        stopped = npdev_tunnel.stop(state)
        self.assertTrue(stopped)
        time.sleep(0.2)
        self.assertFalse(npdev_tunnel.is_alive(state))

    def test_stop_is_a_safe_no_op_when_nothing_is_up(self):
        self.assertTrue(npdev_tunnel.stop({}))
        self.assertTrue(npdev_tunnel.stop({"pid": None}))
        self.assertTrue(npdev_tunnel.stop({"pid": 999999999}))

    def test_is_alive_false_for_a_pid_that_does_not_exist(self):
        self.assertFalse(npdev_tunnel.is_alive({"pid": 999999999}))

    def test_unknown_provider_raises(self):
        with self.assertRaises(ValueError):
            npdev_tunnel.start("not-a-real-provider", 8080)

    def test_available_providers_returns_only_binaries_actually_on_path(self):
        # Does not assert emptiness or membership (cloudflared/ngrok may or may not be installed
        # on the machine running this suite) -- only that it never raises and always returns a
        # subset of the two real provider names.
        result = npdev_tunnel.available_providers()
        self.assertTrue(set(result) <= {"cloudflared", "ngrok"})


class _HealthServer:
    """A real, minimal /actuator/health responder -- self-contained copy of test_host_check.py's
    own helper (unittest discovery does not guarantee tests/ itself is on sys.path, so a
    cross-test-file import is one more thing to keep working as either file's path setup churns)."""

    def __enter__(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}')

            def log_message(self, *a):
                pass

        self._server = HTTPServer(("127.0.0.1", 0), Handler)
        outer.port = self._server.server_address[1]
        import threading as _threading
        self._thread = _threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()
        return False


def _app_and_plan(app_dir: Path, *, reachable_kind="tunnel", ephemeral=False, acknowledged=None,
                   server_port=1):
    import json as _json

    (app_dir / "_ops").mkdir(parents=True, exist_ok=True)
    (app_dir / "_ops" / "resolved-db-plan.json").write_text(_json.dumps({
        "appId": "myapp", "engine": "H2Server", "serverPort": server_port,
        "resolvedDatabaseName": "npdev_myapp", "physicalDatabase": True,
    }), encoding="utf-8")
    if acknowledged is not None:
        npdev_host.write_definition(app_dir, {
            "schemaVersion": "npdev-host-definition.v1", "rung": 1, "acknowledged": acknowledged,
        })
    plan = {
        "schemaVersion": "npdev-host-plan.v1", "appId": "myapp", "rung": 1, "target": None,
        "runsOn": {"kind": "this-machine", "port": server_port},
        "dataLivesOn": {"kind": "this-machine", "engine": "H2Server",
                        "survivesRestart": not ephemeral, "survivesThisMachineDying": False},
        "reachableBy": {"kind": reachable_kind, "provider": "cloudflared"},
        "authMode": "apikey",
    }
    return plan


class PreflightTest(unittest.TestCase):
    """H10: the publish gate -- fix-status findings block; an unacknowledged ephemeral-data
    condition blocks too, even though run_checks itself reports it as `warn`."""

    def test_blocks_and_names_the_fact_and_fix_when_forwarded_headers_is_unset(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            plan = _app_and_plan(app_dir, reachable_kind="tunnel")

            blocking = npdev_host.preflight(app_dir, plan)

            ids = {f["id"] for f in blocking}
            self.assertIn("forwarded-headers", ids)
            forwarded = next(f for f in blocking if f["id"] == "forwarded-headers")
            self.assertTrue(forwarded["detail"])
            self.assertTrue(forwarded["fix"])

    def test_proceeds_once_check_fix_has_repaired_everything(self):
        with _HealthServer() as server, tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            plan = _app_and_plan(app_dir, reachable_kind="nobody", server_port=server.port)
            npdev_host.apply_fixes(app_dir, plan=plan)

            blocking = npdev_host.preflight(app_dir, plan)

            self.assertEqual(blocking, [])

    def test_unacknowledged_ephemeral_data_blocks_even_though_it_is_not_machine_fixable(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            plan = _app_and_plan(app_dir, reachable_kind="nobody", ephemeral=True)
            npdev_host.apply_fixes(app_dir, plan=plan)  # repair everything ELSE that is fixable

            blocking = npdev_host.preflight(app_dir, plan)

            ids = {f["id"] for f in blocking}
            self.assertIn("data-durability", ids)

    def test_acknowledged_ephemeral_data_does_not_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_dir = Path(tmp)
            plan = _app_and_plan(app_dir, reachable_kind="nobody", ephemeral=True,
                                  acknowledged=["data-disappears-on-restart"])
            npdev_host.apply_fixes(app_dir, plan=plan)

            blocking = npdev_host.preflight(app_dir, plan)

            ids = {f["id"] for f in blocking}
            self.assertNotIn("data-durability", ids)


class HostShareCliTest(unittest.TestCase):
    """H11: `npdev host share`/`down`/`status`, end to end against a fake provider (registered
    under a name the CLI is told to use via --provider) -- no real tunnel binary required."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-host-share-cli-")
        self.scratch = Path(self._tmp.name)
        self._spawned: list = []
        npdev_tunnel.register_provider("fake", _fake_provider_factory(self.scratch, self._spawned))

    def tearDown(self):
        for proc in self._spawned:
            if proc.poll() is None:
                proc.kill()
            proc.wait(timeout=5)
        self._tmp.cleanup()

    def test_share_then_status_then_down_round_trips(self):
        import io
        from contextlib import redirect_stdout

        # preflight re-probes liveness at SHARE time (not just once during setup), so the health
        # server must stay up through the whole share call, not just while apply_fixes runs.
        with _HealthServer() as server:
            app_dir = self.scratch / "app"
            plan = _app_and_plan(app_dir, reachable_kind="nobody", server_port=server.port)
            npdev_host.write_definition(app_dir, {"schemaVersion": "npdev-host-definition.v1", "rung": 1})
            npdev_host.apply_fixes(app_dir, plan=plan)

            empty_scan_root = self.scratch / "empty-scan-root"
            empty_scan_root.mkdir()

            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["host", "share", "--app", str(app_dir), "--provider", "fake",
                                        "--paths", str(empty_scan_root), "--json"])
            self.assertEqual(code, 0)
            share_result = json.loads(buffer.getvalue())
            self.assertTrue(share_result["ok"])
            self.assertIn("url", share_result["state"])

        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = npdev_cli.main(["host", "status", "--app", str(app_dir), "--json"])
        self.assertEqual(code, 0)
        status_result = json.loads(buffer.getvalue())
        self.assertTrue(status_result["up"])

        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = npdev_cli.main(["host", "down", "--app", str(app_dir), "--json"])
        self.assertEqual(code, 0)

        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = npdev_cli.main(["host", "status", "--app", str(app_dir), "--json"])
        self.assertEqual(code, 0)
        status_after_down = json.loads(buffer.getvalue())
        self.assertFalse(status_after_down["up"])

    def test_down_is_safe_when_nothing_is_up(self):
        import io
        from contextlib import redirect_stdout

        app_dir = self.scratch / "never-shared"
        app_dir.mkdir()
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = npdev_cli.main(["host", "down", "--app", str(app_dir), "--json"])
        self.assertEqual(code, 0)
        result = json.loads(buffer.getvalue())
        self.assertTrue(result["ok"])

    def test_share_blocks_and_writes_no_state_when_preflight_fails(self):
        import io
        import json as _json
        from contextlib import redirect_stdout

        app_dir = self.scratch / "unfixed-app"
        (app_dir / "_ops").mkdir(parents=True)
        (app_dir / "_ops" / "resolved-db-plan.json").write_text(_json.dumps({
            "appId": "myapp", "engine": "H2Server", "serverPort": 1,
            "resolvedDatabaseName": "npdev_myapp", "physicalDatabase": True,
        }), encoding="utf-8")
        npdev_host.write_definition(app_dir, {
            "schemaVersion": "npdev-host-definition.v1", "rung": 1,
            "reachableBy": {"kind": "tunnel", "provider": "cloudflared"},
        })

        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = npdev_cli.main(["host", "share", "--app", str(app_dir), "--provider", "fake", "--json"])

        self.assertEqual(code, 1)
        self.assertEqual(npdev_host.read_state(app_dir), {})


if __name__ == "__main__":
    unittest.main()
