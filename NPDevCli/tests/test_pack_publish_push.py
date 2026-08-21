"""Tests for R8.5's `npdev pack publish --push` (npdev_cli.py: `_push_pack_to_catalog`, the
`--push`/`--catalog-repo`/... args on `pack publish`).

PREMISE CHECKED BEFORE WRITING ANY OF THIS: `npdev pack publish` already existed and already
enforced semver honesty + migration chains (PK-4 Stage B, `PackPublishGate`), but had no way to
actually LAND a publish anywhere -- it only ever wrote a report. `--push` extends that SAME gate
(never replaces it, never a second implementation): the existing Gradle-backed gate runs exactly
as before, and ONLY once it reports `allowed` does this module commit the pack + a regenerated
catalog-index.json (R8.4's own index format, `_scan_pack_catalog_entries`/
`_write_pack_catalog_index` -- reused, not duplicated) into a local git working copy of the
catalog repo, pushing both the branch and the pack's own release tag when `--push` is given.

OCI is explicitly out of scope (its fetch stays a stub, PACK-8) -- this only ever commits into a
GIT catalog repo.

Two tiers:
  - PushImmutabilityUnitTest: fast, no git/Gradle -- exercises the local-only refusal path
    directly against a hand-built "catalog repo" directory (no git repo needed for THIS check,
    since the refusal is decided before any git command ever runs).
  - PackPublishPushRoundTripTest: real end-to-end (~20-40s: two `packPublish` Gradle calls,
    several real `git` operations against a throwaway BARE repo standing in for the catalog
    remote -- never github.com/MarceloGiazzon/NPR, never a real network call). Proves both of
    R8.5's own Done-When clauses: a push lands a pack a FRESH clone of the bare remote can serve
    and a fresh `pack add --from-catalog` resolves by name; and a republish that would MUTATE an
    already-published version is refused locally, with the remote provably untouched.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_publish_push -v
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
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")


class PushImmutabilityUnitTest(unittest.TestCase):
    """The refusal itself needs no git repo at all -- it only ever reads on-disk bytes under
    --catalog-repo, BEFORE any `git add`/`commit`/`push` is attempted. A `.git` marker directory
    is still required (the function's own precondition), but no real git command runs on this path."""

    def _catalog_repo(self, tmp_dir: Path) -> Path:
        repo = tmp_dir / "catalog-repo"
        (repo / ".git").mkdir(parents=True)
        return repo

    def test_mutating_an_already_published_version_is_refused_before_any_git_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            catalog_repo = self._catalog_repo(tmp_dir)
            _write(catalog_repo / "packs" / "widgets" / "pack.json", {
                "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                "concepts": [{"name": "Widget", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]}],
            })
            new_pack_path = tmp_dir / "new-widgets.json"
            _write(new_pack_path, {
                "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                "concepts": [{"name": "Widget", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "evil", "type": "string", "required": False},
                ]}],
            })
            args = argparse.Namespace(catalog_repo=str(catalog_repo), remote="origin",
                                       repository_url="https://example.com/catalog", push=False)

            with mock.patch.object(npdev_cli, "_run_git") as run_git:
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._push_pack_to_catalog(new_pack_path, args)
                run_git.assert_not_called()

            message = str(ctx.exception)
            self.assertIn("widgets", message)
            self.assertIn("1.0.0", message)
            self.assertIn("REFUSED", message)

    def test_republishing_identical_content_at_the_same_version_is_a_no_op_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            catalog_repo = self._catalog_repo(tmp_dir)
            pack_body = {
                "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                "concepts": [{"name": "Widget", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]}],
            }
            _write(catalog_repo / "packs" / "widgets" / "pack.json", pack_body)
            new_pack_path = tmp_dir / "same-widgets.json"
            _write(new_pack_path, pack_body)
            args = argparse.Namespace(catalog_repo=str(catalog_repo), remote="origin",
                                       repository_url="https://example.com/catalog", push=True)

            with mock.patch.object(npdev_cli, "_run_git") as run_git:
                result = npdev_cli._push_pack_to_catalog(new_pack_path, args)
                run_git.assert_not_called()

            self.assertTrue(result["ok"])
            self.assertTrue(result["alreadyPublished"])
            self.assertFalse(result["pushed"])

    def test_missing_catalog_repo_flag_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            new_pack_path = Path(tmp) / "widgets.json"
            _write(new_pack_path, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                                    "concepts": [{"name": "W", "fields": [
                                        {"name": "id", "type": "uuid", "id": True, "required": True}]}]})
            args = argparse.Namespace(catalog_repo=None, push=True)
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli._push_pack_to_catalog(new_pack_path, args)


# -------------------------------------------------------------------------------------------------
# Real end-to-end: bare git repo (the catalog "remote") -> pack publish --push -> fresh clone
# consumes by name; then a mutating republish is refused with the remote provably untouched.
# ~20-40s. Never touches a real network host.
# -------------------------------------------------------------------------------------------------

def _run(cwd: Path, *cmd: str) -> subprocess.CompletedProcess:
    result = subprocess.run(list(cmd), cwd=str(cwd), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{cmd} failed in {cwd}: {result.stdout}\n{result.stderr}")
    return result


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class PackPublishPushRoundTripTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp_dir = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

        self.bare = self.tmp_dir / "catalog-remote.git"
        self.bare.mkdir()
        _run(self.bare, "git", "init", "--quiet", "--bare", "--initial-branch=main")

        self.clone = self.tmp_dir / "catalog-clone"
        _run(self.tmp_dir, "git", "clone", "--quiet", str(self.bare), str(self.clone))
        _write(self.clone / "README.md", "catalog\n")
        _run(self.clone, "git", "-c", "user.name=t", "-c", "user.email=t@example.com", "add", "-A")
        _run(self.clone, "git", "-c", "user.name=t", "-c", "user.email=t@example.com",
             "commit", "--quiet", "-m", "seed")
        _run(self.clone, "git", "push", "--quiet", "origin", "main")

        self.repository_url = self.bare.resolve().as_uri()

    def _publish_args(self, old_pack: Path, new_pack: Path, *, push: bool = True) -> argparse.Namespace:
        return argparse.Namespace(
            old_pack=str(old_pack), new_pack=str(new_pack), out=None, write=False,
            push=push, catalog_repo=str(self.clone), repository_url=self.repository_url,
            tag_template="v{version}", remote="origin", branch=None,
            git_user_name="npdev-test", git_user_email="npdev-test@example.com",
        )

    def test_push_lands_a_pack_a_fresh_machine_consumes_by_name(self):
        old_pack = self.tmp_dir / "widgets-old.json"
        new_pack = self.tmp_dir / "widgets-new.json"
        _write(old_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                           "concepts": [{"name": "Widget", "fields": [
                               {"name": "id", "type": "uuid", "id": True, "required": True}]}]})
        _write(new_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                           "description": "widgets pack", "category": "other", "author": "Test",
                           "concepts": [{"name": "Widget", "fields": [
                               {"name": "id", "type": "uuid", "id": True, "required": True},
                               {"name": "sku", "type": "string", "required": False}]}]})

        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_publish(self._publish_args(old_pack, new_pack))
        self.assertEqual(0, code, out.getvalue())

        remote_tags = _run(self.bare, "git", "tag", "--list").stdout.split()
        self.assertIn("v1.1.0", remote_tags, "the release tag must land on the REMOTE, not just the local clone")

        # Fresh machine: an independent clone of the bare remote, its own catalog-index.json served
        # over a real (local-only) HTTP server, an EMPTY pack cache and catalog cache.
        fresh_clone = self.tmp_dir / "fresh-clone"
        _run(self.tmp_dir, "git", "clone", "--quiet", str(self.bare), str(fresh_clone))
        catalog_body = (fresh_clone / "catalog-index.json").read_bytes()
        catalog = json.loads(catalog_body)
        self.assertEqual(1, len(catalog["packs"]))
        self.assertEqual("widgets", catalog["packs"][0]["pack"])
        self.assertEqual("1.1.0", catalog["packs"][0]["version"])

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
            app_dir = self.tmp_dir / "consumer-app"
            model_path = app_dir / "model.json"
            _write(model_path, {"namespace": "npdev.throwaway.r85push", "dslVersion": "1.0.0", "version": "1.0"})

            env = {"NPDEV_PACK_CATALOG_CACHE": str(self.tmp_dir / "consumer-catalog-cache.json"),
                   "NPDEV_PACK_CACHE_ROOT": str(self.tmp_dir / "consumer-pack-cache")}
            with mock.patch.dict(os.environ, env):
                add_args = argparse.Namespace(model=str(model_path), from_catalog="widgets",
                                               catalog_url=catalog_url, offline=False,
                                               allow_unsigned=True)  # R8.7: this pack was published unsigned
                with redirect_stdout(io.StringIO()) as add_out:
                    add_code = npdev_cli.run_pack_add(add_args)
                self.assertEqual(0, add_code, add_out.getvalue())

            lock = json.loads((app_dir / "npdev.lock").read_text(encoding="utf-8"))
            self.assertEqual("1.1.0", lock["packs"]["widgets"]["resolvedVersion"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_republish_mutating_a_published_version_is_refused_and_remote_is_untouched(self):
        old_pack = self.tmp_dir / "widgets-old.json"
        published_pack = self.tmp_dir / "widgets-published.json"
        _write(old_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                           "concepts": [{"name": "Widget", "fields": [
                               {"name": "id", "type": "uuid", "id": True, "required": True}]}]})
        _write(published_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                                 "concepts": [{"name": "Widget", "fields": [
                                     {"name": "id", "type": "uuid", "id": True, "required": True},
                                     {"name": "sku", "type": "string", "required": False}]}]})
        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_publish(self._publish_args(old_pack, published_pack))
        self.assertEqual(0, code, out.getvalue())

        tags_before = set(_run(self.bare, "git", "tag", "--list").stdout.split())
        head_before = _run(self.bare, "git", "rev-parse", "refs/heads/main").stdout.strip()

        mutated_pack = self.tmp_dir / "widgets-mutated.json"
        _write(mutated_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                               "concepts": [{"name": "Widget", "fields": [
                                   {"name": "id", "type": "uuid", "id": True, "required": True},
                                   {"name": "sku", "type": "string", "required": False},
                                   {"name": "evil", "type": "string", "required": False}]}]})

        captured = io.StringIO()
        with redirect_stdout(captured):
            code = npdev_cli.run_pack_publish(self._publish_args(published_pack, mutated_pack))
        # Refused either by the gate itself (no version bump for an additive change -- also a
        # correct refusal) or by the R8.5 immutability check (raised CliError). Either way the
        # remote must be untouched.
        self.assertNotEqual(0, code, captured.getvalue())

        tags_after = set(_run(self.bare, "git", "tag", "--list").stdout.split())
        head_after = _run(self.bare, "git", "rev-parse", "refs/heads/main").stdout.strip()
        self.assertEqual(tags_before, tags_after, "a refused republish must never create/move a remote tag")
        self.assertEqual(head_before, head_after, "a refused republish must never push a new commit")

    def test_republish_mutating_a_published_version_bypassing_the_semver_gate_is_still_refused(self):
        """Even when old_pack/new_pack (the two files the SEMVER gate diffs) are chosen so the gate
        itself would allow the bump, the CATALOG-level immutability check (comparing against what
        is ALREADY published at that version) still refuses -- it does not merely rely on the
        semver gate having been given honest inputs."""
        old_pack = self.tmp_dir / "widgets-old.json"
        published_pack = self.tmp_dir / "widgets-published.json"
        _write(old_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                           "concepts": [{"name": "Widget", "fields": [
                               {"name": "id", "type": "uuid", "id": True, "required": True}]}]})
        _write(published_pack, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                                 "concepts": [{"name": "Widget", "fields": [
                                     {"name": "id", "type": "uuid", "id": True, "required": True},
                                     {"name": "sku", "type": "string", "required": False}]}]})
        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_publish(self._publish_args(old_pack, published_pack))
        self.assertEqual(0, code, out.getvalue())

        # A DIFFERENT "old_pack" fabricated to make 1.1.0 -> 1.1.0 look like a NONE-bump-required
        # no-op to the semver gate (identical old/new) -- the gate alone would not catch this.
        same_as_published_but_mutated = self.tmp_dir / "widgets-same-version-mutated.json"
        _write(same_as_published_but_mutated, {
            "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
            "concepts": [{"name": "Widget", "fields": [
                {"name": "id", "type": "uuid", "id": True, "required": True},
                {"name": "sku", "type": "string", "required": False}]}],
            "description": "identical structurally, DIFFERENT bytes from what is published",
        })
        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_publish(
                self._publish_args(same_as_published_but_mutated, same_as_published_but_mutated))
        # old==new is a NONE bump with a real diff against the CATALOG's published copy -- refused
        # by the catalog check even if it were somehow allowed by the (here, no-op) semver gate.
        self.assertNotEqual(0, code)


if __name__ == "__main__":
    unittest.main()
