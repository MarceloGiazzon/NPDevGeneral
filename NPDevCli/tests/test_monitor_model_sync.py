"""Tests for the model-sync field `npdev monitor probe --include-info` adds (Wave 4, 2026-09-25):
is this RUNNING app's deployed model still in sync with its authoring source?

Filesystem-and-stub level, same shape as `test_monitor_hotswap.py`: what this feature owns is
gating (only on --include-info, only when running, only when both modelPath and
superUserKeyFile are known), invoking the canonicalizer, reading the app's own Super User key off
disk, and POSTing with the right header -- not `ModelSyncStatusService` itself (NPDevRuntimeHost
owns that) and not `ModelCanonicalizeMain` (NPDevContract/dsl owns that; `_canonicalize_model_for_sync`
is mocked here rather than actually forking Gradle).
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

SUPER_KEY = "super-user-key-must-never-be-reported"

PLAN = {
    "appId": "demo", "engine": "H2Local", "serverPort": 8098,
    "resolvedDataRoot": "data", "resolvedDatabaseName": "demo",
    "appRoot": "App", "physicalDatabase": True,
    "modelPath": "definition/model.json",
}

SYNCED = {
    "inSync": True, "status": "ok",
    "authoringHash": "aaaa", "deployHash": "aaaa", "lastExportedAt": "2026-09-25T00:00:00Z",
}
DIVERGED = {
    "inSync": False, "status": "diverged",
    "authoringHash": "aaaa", "deployHash": "bbbb", "lastExportedAt": "2026-09-25T00:00:00Z",
}


def make_app(root: Path, *, with_super_key: bool = True, with_model: bool = True) -> Path:
    app = root / "demo"
    ops = app / "_ops"
    ops.mkdir(parents=True)
    (ops / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
    if with_super_key:
        (ops / "SUPER_USER_KEY.txt").write_text(SUPER_KEY + "\n", encoding="utf-8")
    if with_model:
        model_path = app / "definition" / "model.json"
        model_path.parent.mkdir(parents=True, exist_ok=True)
        model_path.write_text('{"namespace":"demo","concepts":[]}', encoding="utf-8")
    (app / "App" / "npdev-generated" / "src" / "main" / "resources" / "static").mkdir(parents=True)
    return app


def running(app: Path):
    """Same wrapper `test_monitor_hotswap.py` uses: real discovery, health forced to running."""
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "running"
        record["healthDetail"] = None
        return record

    return mock.patch.object(npdev_monitor, "probe_app", _probe)


def not_running(app: Path):
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "stopped"
        record["healthDetail"] = "nothing is listening (forced by test)"
        return record

    return mock.patch.object(npdev_monitor, "probe_app", _probe)


class _FakeResponse:
    def __init__(self, body: object, status: int = 200):
        self._body = json.dumps(body).encode("utf-8")
        self.status = status

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def ok_urlopen(seen: list | None = None, body: object = None):
    def _open(request, timeout=None):  # noqa: ARG001
        url = request.full_url if hasattr(request, "full_url") else str(request)
        if seen is not None and "/api/admin/model/sync-status" in url:
            seen.append((url, dict(getattr(request, "headers", {}) or {}), request.data))
        return _FakeResponse(body if body is not None else SYNCED)

    return _open


def cli_args(app: Path, *, include_info: bool = True, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(
        command="monitor", monitor_command="probe", app_dir=str(app),
        include_info=include_info, health_timeout=1.0, json=True)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


class ModelSyncGating(unittest.TestCase):
    """Cases where the feature must not even attempt canonicalization or an HTTP call."""

    def test_no_model_sync_field_without_include_info(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       side_effect=AssertionError("must not be called")), \
                    mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")):
                result = npdev_cli.run_monitor(cli_args(app, include_info=False))
        self.assertEqual(result, 0)

    def test_no_model_sync_field_when_app_not_running(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            buffer = io.StringIO()
            with not_running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       side_effect=AssertionError("must not be called")), \
                    mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")), \
                    redirect_stdout(buffer):
                npdev_cli.run_monitor(cli_args(app))
        result = json.loads(buffer.getvalue())
        self.assertNotIn("modelSync", result)

    def test_no_model_sync_field_when_no_super_user_key(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), with_super_key=False)
            buffer = io.StringIO()
            with running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       side_effect=AssertionError("must not be called")), \
                    mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")), \
                    redirect_stdout(buffer):
                npdev_cli.run_monitor(cli_args(app))
        result = json.loads(buffer.getvalue())
        self.assertNotIn("modelSync", result)


class ModelSyncCanonicalizeFailure(unittest.TestCase):
    def test_canonicalize_failure_is_reported_not_raised(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            buffer = io.StringIO()
            with running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       return_value=(False, None, "this model's pack graph has no npdev.lock")), \
                    mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")), \
                    redirect_stdout(buffer):
                npdev_cli.run_monitor(cli_args(app))
        result = json.loads(buffer.getvalue())
        self.assertFalse(result["modelSync"]["ok"])
        self.assertEqual(result["modelSync"]["code"], "CANONICALIZE_FAILED")
        self.assertIn("npdev.lock", result["modelSync"]["message"])

    def test_empty_super_user_key_file_is_reported_not_raised(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            (app / "_ops" / "SUPER_USER_KEY.txt").write_text("", encoding="utf-8")
            buffer = io.StringIO()
            with running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       return_value=(True, "{}", None)), \
                    mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")), \
                    redirect_stdout(buffer):
                npdev_cli.run_monitor(cli_args(app))
        result = json.loads(buffer.getvalue())
        self.assertFalse(result["modelSync"]["ok"])
        self.assertEqual(result["modelSync"]["code"], "SUPERUSER_KEY_EMPTY")


class ModelSyncPost(unittest.TestCase):
    def test_posts_the_canonical_json_with_the_super_user_key_header(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            buffer = io.StringIO()
            with running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       return_value=(True, '{"namespace":"demo","concepts":[]}', None)), \
                    mock.patch("urllib.request.urlopen", ok_urlopen(seen=seen)), \
                    redirect_stdout(buffer):
                npdev_cli.run_monitor(cli_args(app))

            self.assertEqual(len(seen), 1)
            url, headers, data = seen[0]
            self.assertTrue(url.endswith("/api/admin/model/sync-status"))
            self.assertEqual(headers.get("X-super-user-key"), SUPER_KEY)
            self.assertEqual(data, b'{"namespace":"demo","concepts":[]}')

            result = json.loads(buffer.getvalue())
            self.assertTrue(result["modelSync"]["ok"])
            self.assertTrue(result["modelSync"]["inSync"])
            self.assertNotIn(SUPER_KEY, buffer.getvalue())

    def test_reports_diverged_without_treating_it_as_a_failure(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            buffer = io.StringIO()
            with running(app), \
                    mock.patch.object(npdev_cli, "_canonicalize_model_for_sync",
                                       return_value=(True, "{}", None)), \
                    mock.patch("urllib.request.urlopen", ok_urlopen(body=DIVERGED)), \
                    redirect_stdout(buffer):
                npdev_cli.run_monitor(cli_args(app))
        result = json.loads(buffer.getvalue())
        # "ok" is about whether the CHECK succeeded, not whether the models happen to match --
        # a diverged answer is a successful, actionable check, not an error.
        self.assertTrue(result["modelSync"]["ok"])
        self.assertFalse(result["modelSync"]["inSync"])
        self.assertEqual(result["modelSync"]["code"], "diverged")


if __name__ == "__main__":
    unittest.main()
