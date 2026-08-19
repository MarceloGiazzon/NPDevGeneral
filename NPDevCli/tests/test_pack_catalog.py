"""Tests for `npdev pack search` + `npdev pack build-catalog` + `npdev pack add --from-catalog`
(R8.4 -- "the pack ecosystem's missing front door": every fetch/cache/lock primitive
(PackDependencyGraphWalker/RemotePackFetcher/PackCache, PK-5) was already live and proven; nothing
let an author DISCOVER a pack by name before this.

PREMISE CHECKED BEFORE WRITING ANY OF THIS (against the real repo, not assumed): the real NPR pack
repo (github.com/MarceloGiazzon/NPR) is live and fetchable over HTTPS, has exactly one published
pack (`packs/user/pack.json`, tagged `v1.0.0` at the repo root) and has NO `catalog-index.json` yet
(a live 404) -- `run_pack_build_catalog` is the tool that produces one; publishing it to NPR itself
is left to the repo owner and is explicitly not attempted by any test here.

Three tiers, mirroring `test_pack_export.py`'s own established split:
  - PackSearchLiveHttpTest / PackBuildCatalogTest / AddFromCatalogUnitTest: fast, real (never
    mocked) network and filesystem I/O against a LOCAL `http.server` -- proves the fetch/cache/
    stale-fallback/refusal contract with a genuine socket round trip, without depending on (or
    being flaky against) a real remote host, matching this repo's own established convention for
    'live-shaped' proofs (`RemotePackFetcherGitLiveTest` uses `git+file://` for the identical
    reason: exercise the real code path with zero real network I/O).
  - PackAddFromCatalogRoundTripTest: slower (~10-20s), real end-to-end -- a genuine `git+file://`
    remote, a catalog built by `build-catalog`, served over a real local HTTP server, resolved by
    `npdev pack add --from-catalog` through the actual Gradle-backed PK-5 machinery, then validated
    OFFLINE (NetworkPolicy.DENIED) with zero errors -- the roadmap's own Done-When, proven by
    measurement exactly as `PackExportRoundTripTest` proves R8.2's.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_catalog -v
"""

from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content)
    path.write_text(content, encoding="utf-8")


SAMPLE_CATALOG = {
    "schemaVersion": "npdev-pack-catalog.v1",
    "repository": "https://github.com/MarceloGiazzon/NPR",
    "generatedAt": "2026-08-19T00:00:00Z",
    "packs": [
        {"pack": "user", "version": "1.0.0", "description": "Portable credential bond",
         "category": "security", "author": "Marcelo Giazzon", "path": "packs/user",
         "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/user@v1.0.0"},
        {"pack": "billing", "version": "2.0.0", "description": "Invoicing helpers",
         "category": "finance", "author": "Someone Else", "path": "packs/billing",
         "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/billing@v2.0.0"},
    ],
}


class _CatalogHandler(BaseHTTPRequestHandler):
    body = json.dumps(SAMPLE_CATALOG).encode("utf-8")

    def do_GET(self):  # noqa: N802 -- stdlib handler method name
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(self.__class__.body)

    def log_message(self, *_args):  # silence the default stderr access log during tests
        pass


class _LocalCatalogServer:
    """A REAL localhost HTTP server serving SAMPLE_CATALOG -- a genuine socket round trip through
    `urllib.request`, not a mocked transport. Never a real remote host, so the automated suite
    stays hermetic and fast."""

    def __enter__(self):
        self.server = HTTPServer(("127.0.0.1", 0), _CatalogHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return f"http://127.0.0.1:{self.server.server_address[1]}/catalog-index.json"

    def __exit__(self, *_exc):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        return False


def _isolated_cache(tmp_dir: Path):
    """Every test gets its OWN catalog cache file -- never the real machine's `~/.npdev/`."""
    return mock.patch.dict(os.environ, {"NPDEV_PACK_CATALOG_CACHE": str(tmp_dir / "catalog-cache.json")})


# -----------------------------------------------------------------------------------------------
# `pack search` -- real HTTP, real cache file, no Gradle.
# -----------------------------------------------------------------------------------------------

class PackSearchLiveHttpTest(unittest.TestCase):
    def test_fresh_fetch_returns_results_and_writes_cache(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            args = argparse.Namespace(query="", catalog_url=url, offline=False, json=True)
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_pack_search(args)
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertEqual("fresh", result["catalogStatus"])
            self.assertEqual(2, result["count"])
            self.assertTrue(Path(os.environ["NPDEV_PACK_CATALOG_CACHE"]).is_file(),
                             "a fresh fetch must populate the cache for the next --offline/stale run")

    def test_query_filters_by_substring_across_id_description_category(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            args = argparse.Namespace(query="finance", catalog_url=url, offline=False, json=True)
            captured = io.StringIO()
            with redirect_stdout(captured):
                npdev_cli.run_pack_search(args)
            result = json.loads(captured.getvalue())
            self.assertEqual(1, result["count"])
            self.assertEqual("billing", result["results"][0]["pack"])

    def test_stale_cache_used_when_fetch_fails_with_clear_warning(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            # First: a real fresh fetch, to populate the cache for real (not hand-seeded).
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_pack_search(argparse.Namespace(query="", catalog_url=url, offline=False, json=True))

            # Second: same cache, but a URL nothing is listening on -- a real connection failure.
            dead_url = "http://127.0.0.1:1/catalog-index.json"
            args = argparse.Namespace(query="", catalog_url=dead_url, offline=False, json=True)
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = npdev_cli.run_pack_search(args)
            self.assertEqual(0, code)
            result = json.loads(out.getvalue())
            self.assertEqual("stale", result["catalogStatus"])
            self.assertEqual(2, result["count"], "a stale cache must still serve its real results")
            self.assertIsNotNone(result["catalogFetchedAt"])
            # Loud and unmissable, never silent -- CLAUDE.md's own "must never silently fail open".
            self.assertIn("WARNING", err.getvalue())
            self.assertIn(dead_url, err.getvalue())

    def test_refuses_rather_than_reporting_zero_when_no_cache_and_fetch_fails(self):
        with tempfile.TemporaryDirectory() as tmp, _isolated_cache(Path(tmp)):
            dead_url = "http://127.0.0.1:1/catalog-index.json"
            args = argparse.Namespace(query="", catalog_url=dead_url, offline=False, json=True)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_pack_search(args)
            self.assertIn("no cached pack catalog", str(ctx.exception))

    def test_offline_never_touches_the_network(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_pack_search(argparse.Namespace(query="", catalog_url=url, offline=False, json=True))
            # Port 1 is a privileged, essentially-never-bound port -- connecting to it fails fast.
            # --offline must never even attempt it.
            unreachable = "http://127.0.0.1:1/catalog-index.json"
            args = argparse.Namespace(query="", catalog_url=unreachable, offline=True, json=True)
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_pack_search(args)
            self.assertEqual(0, code)
            result = json.loads(captured.getvalue())
            self.assertEqual("stale", result["catalogStatus"])
            self.assertIn("--offline", result["catalogStaleReason"])

    def test_malformed_catalog_body_is_treated_as_a_fetch_failure_not_a_crash(self):
        class _BadHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"notPacks": []}')

            def log_message(self, *_args):
                pass

        server = HTTPServer(("127.0.0.1", 0), _BadHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/catalog-index.json"
            with tempfile.TemporaryDirectory() as tmp, _isolated_cache(Path(tmp)):
                with self.assertRaises(npdev_cli.CliError):
                    npdev_cli.run_pack_search(
                        argparse.Namespace(query="", catalog_url=url, offline=False, json=True))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


# -----------------------------------------------------------------------------------------------
# `pack build-catalog` -- pure filesystem, no network, no Gradle.
# -----------------------------------------------------------------------------------------------

class PackBuildCatalogTest(unittest.TestCase):
    def test_scans_local_packs_dir_and_writes_coordinates(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo_dir = Path(tmp) / "repo"
            _write(repo_dir / "packs" / "alpha" / "pack.json", {
                "dslVersion": "1.0.0", "pack": "alpha", "version": "1.2.0",
                "description": "Alpha pack", "category": "other", "author": "A",
                "concepts": [{"name": "Thing", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True}]}],
            })
            args = argparse.Namespace(repo_dir=str(repo_dir),
                                       repository_url="https://github.com/MarceloGiazzon/NPR",
                                       tag_template="v{version}", out="")
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_pack_build_catalog(args)
            self.assertEqual(0, code)
            out_path = repo_dir / "catalog-index.json"
            self.assertTrue(out_path.is_file())
            catalog = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(1, len(catalog["packs"]))
            entry = catalog["packs"][0]
            self.assertEqual("alpha", entry["pack"])
            self.assertEqual("Alpha pack", entry["description"])
            self.assertEqual(
                "git+https://github.com/MarceloGiazzon/NPR.git//packs/alpha@v1.2.0", entry["from"])

    def test_repository_url_already_ending_in_dot_git_is_not_doubled(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo_dir = Path(tmp) / "repo"
            _write(repo_dir / "packs" / "alpha" / "pack.json", {
                "dslVersion": "1.0.0", "pack": "alpha", "version": "1.0.0",
            })
            args = argparse.Namespace(repo_dir=str(repo_dir),
                                       repository_url="https://github.com/MarceloGiazzon/NPR.git",
                                       tag_template="v{version}", out="")
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_pack_build_catalog(args)
            catalog = json.loads((repo_dir / "catalog-index.json").read_text(encoding="utf-8"))
            self.assertEqual(
                "git+https://github.com/MarceloGiazzon/NPR.git//packs/alpha@v1.0.0",
                catalog["packs"][0]["from"])

    def test_skips_malformed_pack_json_and_reports_it_by_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo_dir = Path(tmp) / "repo"
            _write(repo_dir / "packs" / "bad" / "pack.json", {"dslVersion": "1.0.0"})  # no pack/version
            args = argparse.Namespace(repo_dir=str(repo_dir),
                                       repository_url="https://github.com/MarceloGiazzon/NPR",
                                       tag_template="v{version}", out="")
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_pack_build_catalog(args)
            self.assertEqual(2, code)
            report = json.loads(captured.getvalue())
            self.assertEqual(0, report["packCount"])
            self.assertEqual(1, len(report["problems"]))
            self.assertIn("bad", report["problems"][0])

    def test_refuses_when_repo_dir_has_no_packs_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = argparse.Namespace(repo_dir=tmp, repository_url="https://example.com/x",
                                       tag_template="v{version}", out="")
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_build_catalog(args)


# -----------------------------------------------------------------------------------------------
# `pack add --from-catalog` -- the model-editing half, tested directly (fast, no Gradle).
# -----------------------------------------------------------------------------------------------

class AddFromCatalogUnitTest(unittest.TestCase):
    def test_unknown_pack_name_lists_available(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            model_path = Path(tmp) / "model.json"
            _write(model_path, {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0"})
            args = argparse.Namespace(catalog_url=url, offline=False)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli._add_pack_from_catalog(model_path, "does-not-exist", args)
            self.assertIn("user", str(ctx.exception))
            self.assertIn("billing", str(ctx.exception))

    def test_writes_from_coordinate_into_model(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            model_path = Path(tmp) / "model.json"
            _write(model_path, {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0"})
            args = argparse.Namespace(catalog_url=url, offline=False)
            with redirect_stdout(io.StringIO()):
                npdev_cli._add_pack_from_catalog(model_path, "user", args)
            model = json.loads(model_path.read_text(encoding="utf-8"))
            self.assertEqual(
                [{"from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/user@v1.0.0"}],
                model["packs"])

    def test_idempotent_when_already_declared(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            model_path = Path(tmp) / "model.json"
            coordinate = "git+https://github.com/MarceloGiazzon/NPR.git//packs/user@v1.0.0"
            _write(model_path, {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0",
                                 "packs": [{"from": coordinate}]})
            args = argparse.Namespace(catalog_url=url, offline=False)
            with redirect_stdout(io.StringIO()):
                npdev_cli._add_pack_from_catalog(model_path, "user", args)
            model = json.loads(model_path.read_text(encoding="utf-8"))
            self.assertEqual(1, len(model["packs"]), "must not duplicate an already-declared coordinate")

    def test_alias_conflict_is_refused_not_silently_duplicated(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            model_path = Path(tmp) / "model.json"
            _write(model_path, {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0",
                                 "packs": [{"from": "git+https://example.com/other.git//p@v9", "as": "user"}]})
            args = argparse.Namespace(catalog_url=url, offline=False)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli._add_pack_from_catalog(model_path, "user", args)
            self.assertIn("already declares a pack aliased", str(ctx.exception))

    def test_missing_model_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp, _LocalCatalogServer() as url, _isolated_cache(Path(tmp)):
            args = argparse.Namespace(catalog_url=url, offline=False)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli._add_pack_from_catalog(Path(tmp) / "nope.json", "user", args)
            self.assertIn("model not found", str(ctx.exception))

    def test_run_pack_add_without_from_catalog_attribute_is_unaffected(self):
        """Backward compatibility: existing callers (e.g. test_pack_export.py's round trip) build
        a bare `argparse.Namespace(model=...)` with no `from_catalog` attribute at all -- `run_pack_add`
        must not raise an AttributeError for them."""
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            _write(model_path, {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0"})
            args = argparse.Namespace(model=str(model_path))
            # No pack cache/network involved for a model with no packs[] at all -- this just proves
            # the getattr(..., None) guard, not the Gradle round trip (covered elsewhere).
            self.assertIsNone(getattr(args, "from_catalog", None))


# -----------------------------------------------------------------------------------------------
# Real end-to-end: git+file:// remote -> build-catalog -> real HTTP search -> pack add
# --from-catalog -> real PK-5 fetch+lock -> offline validate. ~10-20s.
# -----------------------------------------------------------------------------------------------

def _run(cwd: Path, *cmd: str) -> None:
    subprocess.run(list(cmd), cwd=str(cwd), check=True, capture_output=True, text=True)


def _init_git_repo(repo_dir: Path) -> None:
    repo_dir.mkdir(parents=True, exist_ok=True)
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
         "init", "--quiet", "--initial-branch=main")


def _commit_and_tag(repo_dir: Path, tag: str) -> None:
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com", "add", "-A")
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
         "commit", "--quiet", "-m", "pack-catalog round trip fixture")
    _run(repo_dir, "git", "tag", tag)


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class PackAddFromCatalogRoundTripTest(unittest.TestCase):
    def test_from_catalog_add_resolves_and_then_validates_offline(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            # A directory literally NAMED "<name>.git" -- so passing its path MINUS ".git" as
            # --repository-url and letting build-catalog's own (unmodified) ".git" suffix logic
            # reconstruct it proves the REAL code path a "git+https://github.com/x/y.git" coordinate
            # takes, not a test-only variant of it. Same substrate proof
            # RemotePackFetcherGitLiveTest's own `git+file://` tests already establish for the
            # underlying fetch -- zero real network I/O, identical code path a real remote fetch uses.
            repo_dir = tmp_dir / "npr-fixture.git"
            _init_git_repo(repo_dir)
            _write(repo_dir / "packs" / "rtpack" / "pack.json", {
                "dslVersion": "1.0.0", "pack": "rtpack", "version": "1.0.0",
                "description": "Round trip test pack (R8.4)", "category": "other", "author": "Test",
                "concepts": [{"name": "Thing", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "label", "type": "string", "required": True},
                ]}],
            })
            _commit_and_tag(repo_dir, "v1.0.0")

            repo_uri = repo_dir.resolve().as_uri()
            self.assertTrue(repo_uri.endswith(".git"))
            repository_url = repo_uri[: -len(".git")]

            build_args = argparse.Namespace(repo_dir=str(repo_dir), repository_url=repository_url,
                                             tag_template="v{version}", out="")
            with redirect_stdout(io.StringIO()):
                build_code = npdev_cli.run_pack_build_catalog(build_args)
            self.assertEqual(0, build_code)
            catalog_path = repo_dir / "catalog-index.json"
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            expected_coordinate = f"git+{repository_url}.git//packs/rtpack@v1.0.0"
            self.assertEqual(expected_coordinate, catalog["packs"][0]["from"])

            catalog_body = catalog_path.read_bytes()

            class _Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(catalog_body)

                def log_message(self, *_args):
                    pass

            server = HTTPServer(("127.0.0.1", 0), _Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                catalog_url = f"http://127.0.0.1:{server.server_address[1]}/catalog-index.json"
                cache_path = tmp_dir / "cache" / "catalog-cache.json"
                # Isolate BOTH caches this touches for real: the catalog cache (above) and the
                # PK-5 pack cache RemotePackFetcher actually fetches into -- never the real
                # machine-wide ~/.npdev/packs.
                pack_cache_root = tmp_dir / "pack-cache"
                env = {"NPDEV_PACK_CATALOG_CACHE": str(cache_path),
                       "NPDEV_PACK_CACHE_ROOT": str(pack_cache_root)}
                with mock.patch.dict(os.environ, env):
                    app_dir = tmp_dir / "app"
                    model_path = app_dir / "model.json"
                    _write(model_path, {
                        "namespace": "npdev.throwaway.pack.fromcatalog", "dslVersion": "1.0.0",
                        "version": "1.0",
                    })

                    add_args = argparse.Namespace(model=str(model_path), from_catalog="rtpack",
                                                   catalog_url=catalog_url, offline=False)
                    with redirect_stdout(io.StringIO()):
                        add_code = npdev_cli.run_pack_add(add_args)
                    self.assertEqual(0, add_code, "pack add --from-catalog must resolve and lock")

                    model = json.loads(model_path.read_text(encoding="utf-8"))
                    self.assertEqual([{"from": expected_coordinate}], model["packs"])
                    self.assertTrue((app_dir / "npdev.lock").is_file(),
                                     "pack add must write npdev.lock next to the model")

                    captured = io.StringIO()
                    with redirect_stdout(captured):
                        validate_code = npdev_cli.run_validate_semantic(model_path, None)
                    report = json.loads(captured.getvalue())
                    self.assertEqual(
                        0, report["summary"]["errors"],
                        f"a pack resolved via --from-catalog must validate OFFLINE (lock+cache "
                        f"only, NetworkPolicy.DENIED) with zero errors -- got: {report['diagnostics']}")
                    self.assertNotEqual("failed", report["status"])
                    self.assertEqual(0, validate_code)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
