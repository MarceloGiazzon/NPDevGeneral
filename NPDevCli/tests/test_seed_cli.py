"""Tests for `npdev seed` (R3.2) -- a thin CLI wrapper around DataSeedAdminController's existing
`GET /api/admin/seeds` and `POST /api/admin/seeds/<id>/run` endpoints.

Filesystem-and-stub level, same shape as `test_npdev_test_verb.py`'s `ThreeLayers` tests and for the
same reason: what this command owns is app discovery (`probe_app`), auth-header selection, and
result/exit-code shaping -- not the HTTP stack or `SeedDataService` itself, which the live run
against a booted app (R3.2's own DoD measurement) covers. Nothing green here is evidence that a real
seed ever loaded a real record; `NPDevRuntimeHost/runtimehost-core/src/test/.../
SeedDataServiceGenerativeSeedTest.java` and the live H2 measurement own that.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli
import npdev_monitor

LIVE_KEY = "live-key-must-never-be-reported"

PLAN = {
    "appId": "demo", "engine": "H2Local", "serverPort": 8099,
    "resolvedDataRoot": "data", "resolvedDatabaseName": "demo",
    "appRoot": "App", "physicalDatabase": True,
}

SEEDS_LIST = [
    {"id": "demo-users", "label": "Demo users", "description": "A handful.", "kind": "smart"},
]

RUN_RESULT_OK = {
    "seedId": "demo-users", "kind": "smart", "createdCounts": {"User": 3}, "ok": True,
    "failedRecordIndex": None, "failedAlias": None, "failedConcept": None,
    "failureMessage": None, "elapsedMs": 42,
}

RUN_RESULT_FAILED = {
    "seedId": "demo-users", "kind": "smart", "createdCounts": {"User": 1}, "ok": False,
    "failedRecordIndex": 1, "failedAlias": None, "failedConcept": "User",
    "failureMessage": "boom", "elapsedMs": 7,
}


def make_app(root: Path) -> Path:
    """Minimal generated-app tree `probe_app` recognizes as a healthy app root -- the same shape
    `test_npdev_test_verb.py`'s own `make_app` builds, trimmed to what the seed verb actually reads
    (no info.json/acceptance/routines: `npdev seed` never touches any of those)."""
    app = root / "demo"
    (app / "_ops").mkdir(parents=True)
    (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
    final = app / "App"
    (final / "npdev-generated" / "src" / "main" / "resources" / "static").mkdir(parents=True)
    (final / "secrets").mkdir()
    (final / "secrets" / "api-key.env").write_text(
        f"NPDEV_AUTH_API_KEYS={LIVE_KEY}=dev:developer:admin", encoding="utf-8")
    return app


def running(app: Path):
    """Same `probe_app` wrapper `test_npdev_test_verb.py` uses: real discovery, health forced to
    running so the verb doesn't need an actual listening socket."""
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "running"
        record["healthDetail"] = None
        return record

    return mock.patch.object(npdev_monitor, "probe_app", _probe)


class _FakeResponse:
    def __init__(self, body: object):
        self._body = json.dumps(body).encode("utf-8")

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def routed_urlopen(list_body=None, run_body=None, seen: list | None = None):
    """One fake covering both `GET /api/admin/seeds` and `POST /api/admin/seeds/<id>/run`, routed by
    method (mirrors `test_npdev_test_verb.py`'s `routed_urlopen`, routed by path there since it only
    ever GETs)."""

    def _open(request, timeout=None):  # noqa: ARG001 -- signature must match urlopen's
        url = request.full_url if hasattr(request, "full_url") else str(request)
        method = getattr(request, "get_method", lambda: "GET")()
        # Record only the seed verb's own requests. `running()` wraps the REAL probe_app, which
        # reaches _http_json -- and therefore this mock -- whenever _tcp_open succeeds, i.e. whenever
        # anything at all happens to be listening on PLAN's port 8099. Recording that health probe
        # made `len(seen) == 1` fail with "2 != 1" and pushed the seed request out of seen[0],
        # producing three false REDs on 2026-08-19 while generated apps were being booted alongside
        # the suite (the identical suite re-ran 449 OK minutes later). The seed verb's requests are
        # the subject here; the probe's are not.
        if seen is not None and "/api/admin/seeds" in url:
            seen.append((method, url, dict(getattr(request, "headers", {}) or {})))
        if method == "POST":
            return _FakeResponse(run_body if run_body is not None else RUN_RESULT_OK)
        return _FakeResponse(list_body if list_body is not None else SEEDS_LIST)

    return _open


def cli_args(app: Path, seed_command: str, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(
        command="seed", seed_command=seed_command, app_dir=str(app),
        id="demo-users", tenant_id=None, timeout=30.0, json=False)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


class SeedList(unittest.TestCase):
    def test_list_returns_the_apps_declared_seeds(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app), mock.patch("urllib.request.urlopen", routed_urlopen()):
                result = npdev_cli.run_seed(cli_args(app, "list"))
            self.assertEqual(result["schemaVersion"], npdev_cli.SEED_SCHEMA_VERSION)
            self.assertTrue(result["ok"])
            self.assertEqual(result["seeds"], SEEDS_LIST)

    def test_list_authenticates_with_the_apps_live_key(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            with running(app), mock.patch("urllib.request.urlopen", routed_urlopen(seen=seen)):
                npdev_cli.run_seed(cli_args(app, "list"))
            self.assertEqual(len(seen), 1)
            _method, url, headers = seen[0]
            self.assertTrue(url.endswith("/api/admin/seeds"))
            self.assertEqual(headers.get("X-api-key"), LIVE_KEY)

    def test_refuses_when_the_app_is_not_running(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            # No `running()` patch -- probe_app sees a real, unreachable app and reports it as such.
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_seed(cli_args(app, "list"))


class SeedRun(unittest.TestCase):
    def test_run_posts_to_the_named_seed_and_echoes_its_report(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            with running(app), mock.patch("urllib.request.urlopen", routed_urlopen(seen=seen)):
                result = npdev_cli.run_seed(cli_args(app, "run", id="demo-users"))
            self.assertTrue(result["ok"])
            self.assertEqual(result["report"], RUN_RESULT_OK)
            self.assertIn("durationMs", result)
            _method, url, _headers = seen[0]
            self.assertTrue(url.endswith("/api/admin/seeds/demo-users/run"))

    def test_a_failed_seed_run_is_reported_not_ok_but_not_raised(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app), mock.patch(
                    "urllib.request.urlopen", routed_urlopen(run_body=RUN_RESULT_FAILED)):
                result = npdev_cli.run_seed(cli_args(app, "run", id="demo-users"))
            self.assertFalse(result["ok"])
            self.assertEqual(result["report"]["failedConcept"], "User")

    def test_tenant_id_is_threaded_onto_the_query_string(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            with running(app), mock.patch("urllib.request.urlopen", routed_urlopen(seen=seen)):
                npdev_cli.run_seed(cli_args(app, "run", id="demo-users", tenant_id="acme"))
            _method, url, _headers = seen[0]
            self.assertIn("tenantId=acme", url)

    def test_usage_without_a_subcommand_is_a_named_error(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_seed(cli_args(app, None))


class SeedCliExitCodes(unittest.TestCase):
    def _run_cli(self, app: Path, argv: list[str], *, run_body=None) -> tuple[int, str]:
        buffer = io.StringIO()
        with running(app), mock.patch(
                "urllib.request.urlopen", routed_urlopen(run_body=run_body)), redirect_stdout(buffer):
            code = npdev_cli.main(argv)
        return code, buffer.getvalue()

    def test_seed_list_exits_zero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            code, out = self._run_cli(app, ["seed", "list", "--app-dir", str(app)])
            self.assertEqual(code, 0)
            self.assertIn("demo-users", out)

    def test_seed_run_ok_exits_zero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            code, out = self._run_cli(app, ["seed", "run", "--app-dir", str(app), "--id", "demo-users"])
            self.assertEqual(code, 0)
            self.assertIn("OK", out)

    def test_seed_run_failure_exits_nonzero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            code, out = self._run_cli(
                app, ["seed", "run", "--app-dir", str(app), "--id", "demo-users"],
                run_body=RUN_RESULT_FAILED)
            self.assertEqual(code, 2)
            self.assertIn("FAILED", out)


class SeedHumanSummary(unittest.TestCase):
    def test_list_summary_names_each_seed(self):
        result = {"command": "seed list", "appName": "demo", "baseUrl": "http://x",
                  "seeds": SEEDS_LIST}
        text = npdev_cli._seed_human_summary(result)
        self.assertIn("demo-users", text)
        self.assertIn("[smart]", text)

    def test_list_summary_of_no_seeds_says_so(self):
        result = {"command": "seed list", "appName": "demo", "baseUrl": "http://x", "seeds": []}
        text = npdev_cli._seed_human_summary(result)
        self.assertIn("no seeds", text)

    def test_run_summary_shows_created_counts(self):
        result = {"command": "seed run", "durationMs": 42, "report": RUN_RESULT_OK}
        text = npdev_cli._seed_human_summary(result)
        self.assertIn("OK", text)
        self.assertIn("User: 3 created", text)

    def test_run_summary_of_a_failure_names_the_failed_record(self):
        result = {"command": "seed run", "durationMs": 7, "report": RUN_RESULT_FAILED}
        text = npdev_cli._seed_human_summary(result)
        self.assertIn("FAILED", text)
        self.assertIn("boom", text)


if __name__ == "__main__":
    unittest.main()
