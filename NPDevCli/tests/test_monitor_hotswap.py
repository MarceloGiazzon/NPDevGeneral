"""Tests for `npdev monitor hotswap` (R1.7 dev-loop closer) -- the CLI trigger for
`MetadataHotSwapController#apply`: push an already-classified METADATA_ONLY change's descriptive
metadata catalogs into a RUNNING app's JVM without a restart.

Filesystem-and-stub level, same shape as `test_seed_cli.py`: what this command owns is app discovery
(`probe_app`), reading the app's own Super User key off disk, building the `X-Super-User-Key`
request, and result/exit-code shaping -- not `RuntimeMetadataService` itself, which
`MetadataHotSwapControllerStandaloneTest.java` (NPDevRuntimeHost) and a live boot-edit-swap-observe
proof own. Nothing green here proves a real running JVM's catalogs actually changed.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import unittest
import urllib.error
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
}

APPLY_OK = {
    "ok": True, "metadataGeneration": 7, "appliedAt": "2026-08-19T00:00:00Z",
    "catalogsUpdated": ["compiled-metadata.json", "metadata/concepts.manifest.json"],
    "classificationReasons": ["no concept/field/index change: the compiled schema fingerprint is unchanged"],
}


def make_app(root: Path, *, with_super_key: bool = True) -> Path:
    """Minimal generated-app tree `probe_app` recognizes as a healthy app root, plus the
    `_ops/SUPER_USER_KEY.txt` file `SuperUserBootstrapper` writes (relocated into `_ops` by
    `Start-App.ps1`, per its own class javadoc)."""
    app = root / "demo"
    ops = app / "_ops"
    ops.mkdir(parents=True)
    (ops / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
    if with_super_key:
        (ops / "SUPER_USER_KEY.txt").write_text(SUPER_KEY + "\n", encoding="utf-8")
    final = app / "App"
    (final / "npdev-generated" / "src" / "main" / "resources" / "static").mkdir(parents=True)
    return app


def make_metadata_source_root(root: Path) -> Path:
    """The directory shape `--emitMetadataTo` writes -- only its existence matters here; the
    controller reads its contents server-side, not this CLI verb."""
    scratch = root / "scratch"
    npdev_dir = scratch / "src" / "main" / "resources" / "npdev"
    npdev_dir.mkdir(parents=True)
    (npdev_dir / "compiled-metadata.json").write_text("{}", encoding="utf-8")
    return scratch


def running(app: Path):
    """Same `probe_app` wrapper `test_seed_cli.py` uses: real discovery, health forced to running so
    the verb doesn't need an actual listening socket."""
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "running"
        record["healthDetail"] = None
        return record

    return mock.patch.object(npdev_monitor, "probe_app", _probe)


def not_running(app: Path):
    """Force health to `stopped` WITHOUT touching the real network. `probe_app`'s own health probe
    would otherwise dial 127.0.0.1:<PLAN port> for real, and CLAUDE.md already documents this exact
    class of flake for `npdev seed`'s tests (port 8099): if anything on the machine happens to be
    listening on this PLAN's port, the health check silently finds a REAL server there instead of
    reporting the intended `stopped`."""
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "stopped"
        record["healthDetail"] = "nothing is listening (forced by test)"
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


def ok_urlopen(seen: list | None = None, body: object = None):
    def _open(request, timeout=None):  # noqa: ARG001
        url = request.full_url if hasattr(request, "full_url") else str(request)
        # `running()` wraps the REAL probe_app, which reaches this same mocked urlopen for its own
        # health check whenever anything happens to be listening on PLAN's port -- record only the
        # hotswap verb's own apply request (same fix `test_seed_cli.py` applies for the identical
        # reason).
        if seen is not None and "/metadata-hotswap/apply" in url:
            seen.append((url, dict(getattr(request, "headers", {}) or {}), request.data))
        return _FakeResponse(body if body is not None else APPLY_OK)

    return _open


def failing_urlopen(status: int, body: dict):
    def _open(request, timeout=None):  # noqa: ARG001
        raise urllib.error.HTTPError(
            request.full_url, status, "error", {}, io.BytesIO(json.dumps(body).encode("utf-8")))

    return _open


def cli_args(app: Path, metadata_source_root: Path, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(
        command="monitor", monitor_command="hotswap", app_dir=str(app),
        classification="METADATA_ONLY", reason=["reason one"],
        metadata_source_root=str(metadata_source_root), timeout=15.0, json=False)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


class HotswapLocalRefusals(unittest.TestCase):
    """Refusals that need no HTTP call at all -- checked BEFORE any request is built."""

    def test_refuses_locally_when_not_metadata_only(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            with mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")):
                code = npdev_cli._run_monitor_hotswap(
                    cli_args(app, source_root, classification="SAFE_ADDITIVE"))
            self.assertEqual(code, 2)

    def test_refuses_when_metadata_source_root_is_missing(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            missing = Path(tmp) / "does-not-exist"
            with mock.patch("urllib.request.urlopen", side_effect=AssertionError("must not be called")):
                code = npdev_cli._run_monitor_hotswap(cli_args(app, missing))
            self.assertEqual(code, 2)

    def test_refuses_when_the_app_is_not_running(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            with not_running(app), mock.patch(
                    "urllib.request.urlopen", side_effect=AssertionError("must not be called")):
                code = npdev_cli._run_monitor_hotswap(cli_args(app, source_root))
            self.assertEqual(code, 2)

    def test_refuses_when_no_super_user_key_file_exists(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), with_super_key=False)
            source_root = make_metadata_source_root(Path(tmp))
            with running(app), mock.patch(
                    "urllib.request.urlopen", side_effect=AssertionError("must not be called")):
                code = npdev_cli._run_monitor_hotswap(cli_args(app, source_root))
            self.assertEqual(code, 2)


class HotswapApply(unittest.TestCase):
    def test_applies_and_authenticates_with_the_super_user_key_header(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            seen: list = []
            with running(app), mock.patch("urllib.request.urlopen", ok_urlopen(seen=seen)):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    code = npdev_cli._run_monitor_hotswap(cli_args(app, source_root))
            self.assertEqual(code, 0)
            self.assertEqual(len(seen), 1)
            url, headers, data = seen[0]
            self.assertTrue(url.endswith("/api/admin/runtime/metadata-hotswap/apply"))
            self.assertEqual(headers.get("X-super-user-key"), SUPER_KEY)
            self.assertNotIn(SUPER_KEY, buffer.getvalue())
            posted = json.loads(data.decode("utf-8"))
            self.assertEqual(posted["classification"], "METADATA_ONLY")
            self.assertEqual(posted["classificationReasons"], ["reason one"])
            self.assertEqual(posted["metadataSourceRoot"], str(source_root.resolve()))

    def test_the_raw_super_user_key_never_appears_in_the_json_result(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            with running(app), mock.patch("urllib.request.urlopen", ok_urlopen()):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    npdev_cli._run_monitor_hotswap(cli_args(app, source_root, json=True))
            self.assertNotIn(SUPER_KEY, buffer.getvalue())

    def test_endpoint_refusal_is_reported_not_raised(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            refusal = {"ok": False, "code": "NOT_METADATA_ONLY", "message": "got SAFE_ADDITIVE"}
            with running(app), mock.patch("urllib.request.urlopen", failing_urlopen(409, refusal)):
                code = npdev_cli._run_monitor_hotswap(cli_args(app, source_root))
            self.assertEqual(code, 2)

    def test_endpoint_not_found_names_the_pre_r17_app_case(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            with running(app), mock.patch("urllib.request.urlopen", failing_urlopen(404, {})):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    code = npdev_cli._run_monitor_hotswap(cli_args(app, source_root, json=True))
            self.assertEqual(code, 2)
            result = json.loads(buffer.getvalue())
            self.assertEqual(result["code"], "ENDPOINT_NOT_FOUND")


class HotswapCliExitCodes(unittest.TestCase):
    def test_main_exits_zero_on_success(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            argv = [
                "monitor", "hotswap", "--app-dir", str(app),
                "--classification", "METADATA_ONLY",
                "--metadata-source-root", str(source_root), "--json",
            ]
            with running(app), mock.patch("urllib.request.urlopen", ok_urlopen()):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    code = npdev_cli.main(argv)
            self.assertEqual(code, 0)
            result = json.loads(buffer.getvalue())
            self.assertTrue(result["ok"])
            self.assertEqual(result["metadataGeneration"], 7)

    def test_main_exits_two_when_app_is_not_running(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            source_root = make_metadata_source_root(Path(tmp))
            argv = [
                "monitor", "hotswap", "--app-dir", str(app),
                "--classification", "METADATA_ONLY",
                "--metadata-source-root", str(source_root), "--json",
            ]
            with not_running(app), mock.patch(
                    "urllib.request.urlopen", side_effect=AssertionError("must not be called")):
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    code = npdev_cli.main(argv)
            self.assertEqual(code, 2)
            result = json.loads(buffer.getvalue())
            self.assertFalse(result["ok"])
            self.assertEqual(result["code"], "APP_NOT_RUNNING")


if __name__ == "__main__":
    unittest.main()
