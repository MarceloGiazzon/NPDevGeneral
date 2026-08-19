"""Tests for R8.6's digest-mutation refusal on `npdev pack add`/`npdev pack update`
(`_guard_against_remote_pack_tamper` in npdev_cli.py).

PREMISE CHECKED BEFORE WRITING ANY OF THIS: PK-5 already computes and records a real content
digest in npdev.lock (`PackLockFile.LockedPack.digest`, `PackCache.sha256OfTree`) and re-verifies
it on every cache read -- the roadmap's "the lock file does not record a content digest" premise
is FALSE. What genuinely was missing: `pack add`/`pack update` unconditionally OVERWRITE
npdev.lock with whatever they freshly re-fetch, with no comparison against what was already
locked -- so a mutated git tag (same version, different content) was silently accepted as the new
truth, never refused. `_guard_against_remote_pack_tamper` closes that gap by comparing the lock's
own before/after digest for any packId held at the SAME `from` coordinate and SAME resolvedVersion,
restoring the pre-call lock and refusing (via CliError) the moment they disagree.

Two tiers:
  - Unit tests (fast, no Gradle, no git): exercise `_read_lock_entries_from_text` and
    `_guard_against_remote_pack_tamper` directly against synthetic before/after lock text.
  - PackTreeDigestMatchesJavaTest / MutatedTagRefusedOnPackUpdateRoundTripTest: real end-to-end
    (~10-20s each), a genuine `git+file://` remote resolved through the actual Gradle-backed PK-5
    machinery -- the first cross-checks this module's own Python digest port
    (`_pack_content_digest`) against a digest the JAVA side actually computed, byte for byte; the
    second reproduces the real attack (a force-moved tag) and asserts the refusal + lock restore.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_lock_tamper_guard -v
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
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")


def _lock_text(packs: dict) -> str:
    return json.dumps({"schemaVersion": "npdev-lock.v1", "packs": packs}, indent=2) + "\n"


class ReadLockEntriesUnitTest(unittest.TestCase):
    def test_missing_text_is_empty(self):
        self.assertEqual({}, npdev_cli._read_lock_entries_from_text(None))
        self.assertEqual({}, npdev_cli._read_lock_entries_from_text(""))

    def test_corrupt_text_is_empty_not_a_crash(self):
        self.assertEqual({}, npdev_cli._read_lock_entries_from_text("{not json"))

    def test_non_object_packs_is_empty(self):
        self.assertEqual({}, npdev_cli._read_lock_entries_from_text(json.dumps({"packs": [1, 2]})))

    def test_real_shape_round_trips(self):
        packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x",
                              "from": "git+https://example.com/w.git//p@v1.0.0"}}
        self.assertEqual(packs, npdev_cli._read_lock_entries_from_text(_lock_text(packs)))


class GuardAgainstRemotePackTamperUnitTest(unittest.TestCase):
    def test_same_coordinate_same_version_different_digest_is_refused_and_lock_restored(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            _write(model_path, {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0"})
            lock_path = Path(tmp) / "npdev.lock"

            before = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aaaa",
                                   "sourcePath": "x", "from": "git+https://example.com/w.git//p@v1.0.0"}}
            after = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:bbbb",
                                  "sourcePath": "y", "from": "git+https://example.com/w.git//p@v1.0.0"}}
            before_text = _lock_text(before)
            lock_path.write_text(_lock_text(after), encoding="utf-8")  # simulate the Gradle task
            # having already overwritten the lock with the freshly (re-)fetched entries

            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli._guard_against_remote_pack_tamper(model_path, before_text, "packUpdate")

            message = str(ctx.exception)
            self.assertIn("widgets", message)
            self.assertIn("sha256:aaaa", message, "must name the OLD (trusted) digest")
            self.assertIn("sha256:bbbb", message, "must name the NEW (mutated) digest")
            self.assertIn("1.0.0", message)
            self.assertEqual(before_text, lock_path.read_text(encoding="utf-8"),
                              "the lock must be restored to its pre-call contents")

    def test_version_bump_with_new_digest_is_not_refused(self):
        """A LEGITIMATE new release: version changed, digest changed -- never refused."""
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            lock_path = Path(tmp) / "npdev.lock"
            before = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aaaa",
                                   "sourcePath": "x", "from": "git+https://example.com/w.git//p@v1.0.0"}}
            after = {"widgets": {"resolvedVersion": "1.1.0", "digest": "sha256:bbbb",
                                  "sourcePath": "y", "from": "git+https://example.com/w.git//p@v1.1.0"}}
            before_text = _lock_text(before)
            after_text = _lock_text(after)
            lock_path.write_text(after_text, encoding="utf-8")

            npdev_cli._guard_against_remote_pack_tamper(model_path, before_text, "packUpdate")
            self.assertEqual(after_text, lock_path.read_text(encoding="utf-8"),
                              "a legitimate version bump must be left alone")

    def test_local_pack_no_from_is_never_compared(self):
        """A LOCAL pack (empty `from`) never went over the network -- never flagged."""
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            lock_path = Path(tmp) / "npdev.lock"
            before = {"local": {"resolvedVersion": "1.0.0", "digest": "sha256:aaaa", "sourcePath": "x"}}
            after = {"local": {"resolvedVersion": "1.0.0", "digest": "sha256:bbbb", "sourcePath": "y"}}
            before_text = _lock_text(before)
            lock_path.write_text(_lock_text(after), encoding="utf-8")

            npdev_cli._guard_against_remote_pack_tamper(model_path, before_text, "packUpdate")  # must not raise

    def test_brand_new_pack_no_prior_entry_is_never_compared(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            lock_path = Path(tmp) / "npdev.lock"
            after = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:bbbb",
                                  "sourcePath": "y", "from": "git+https://example.com/w.git//p@v1.0.0"}}
            lock_path.write_text(_lock_text(after), encoding="utf-8")

            npdev_cli._guard_against_remote_pack_tamper(model_path, None, "packAdd")  # must not raise


class PackContentDigestUnitTest(unittest.TestCase):
    def test_matches_manual_sha256_over_path_nul_bytes_nul(self):
        import hashlib
        digest = hashlib.sha256()
        digest.update(b"pack.json")
        digest.update(b"\x00")
        digest.update(b'{"a":1}')
        digest.update(b"\x00")
        expected = f"sha256:{digest.hexdigest()}"
        self.assertEqual(expected, npdev_cli._pack_content_digest({"pack.json": b'{"a":1}'}))

    def test_multi_file_is_sorted_by_normalized_path(self):
        # Order of insertion into the dict must not matter -- sorted internally.
        d1 = npdev_cli._pack_content_digest({"b.json": b"2", "a.json": b"1"})
        d2 = npdev_cli._pack_content_digest({"a.json": b"1", "b.json": b"2"})
        self.assertEqual(d1, d2)

    def test_of_dir_skips_dot_git(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write(root / "pack.json", '{"a":1}')
            (root / ".git").mkdir()
            _write(root / ".git" / "config", "should be excluded")
            with_git = npdev_cli._pack_content_digest_of_dir(root)
            expected = npdev_cli._pack_content_digest({"pack.json": (root / "pack.json").read_bytes()})
            self.assertEqual(expected, with_git)


# -------------------------------------------------------------------------------------------------
# Real end-to-end: git+file:// remote -> pack add (real Gradle/PK-5 fetch+digest) -> mutate the
# SOURCE repo's tag in place -> pack update -> must refuse and restore the lock. ~15-25s.
# -------------------------------------------------------------------------------------------------

def _run(cwd: Path, *cmd: str) -> None:
    subprocess.run(list(cmd), cwd=str(cwd), check=True, capture_output=True, text=True)


def _git_commit(repo_dir: Path, message: str) -> None:
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com", "add", "-A")
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
         "commit", "--quiet", "-m", message)


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class PackTreeDigestMatchesJavaTest(unittest.TestCase):
    def test_python_digest_of_source_matches_the_locked_digest_java_computed(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            src_repo = tmp_dir / "pack-src"
            src_repo.mkdir()
            _run(src_repo, "git", "init", "--quiet", "--initial-branch=main")
            pack_json = src_repo / "pack.json"
            _write(pack_json, {
                "dslVersion": "1.0.0", "pack": "digestcheck", "version": "1.0.0",
                "concepts": [{"name": "Thing", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]}],
            })
            source_bytes = pack_json.read_bytes()
            _git_commit(src_repo, "v1.0.0")
            _run(src_repo, "git", "tag", "v1.0.0")

            coordinate = f"git+{src_repo.resolve().as_uri()}@v1.0.0"
            app_dir = tmp_dir / "app"
            model_path = app_dir / "model.json"
            _write(model_path, {"namespace": "npdev.throwaway.digestcheck", "dslVersion": "1.0.0",
                                 "version": "1.0", "packs": [{"from": coordinate}]})

            env = {"NPDEV_PACK_CACHE_ROOT": str(tmp_dir / "pack-cache")}
            with mock.patch.dict(os.environ, env):
                # R8.7: this fixture pack is unsigned -- --allow-unsigned is orthogonal to the
                # digest-match this test actually proves.
                add_args = argparse.Namespace(model=str(model_path), from_catalog=None, allow_unsigned=True)
                with redirect_stdout(io.StringIO()):
                    code = npdev_cli.run_pack_add(add_args)
                self.assertEqual(0, code)

            lock = json.loads((app_dir / "npdev.lock").read_text(encoding="utf-8"))
            java_digest = lock["packs"]["digestcheck"]["digest"]
            python_digest = npdev_cli._pack_content_digest({"pack.json": source_bytes})
            self.assertEqual(java_digest, python_digest,
                              "the Python port of PackCache#sha256OfTree must match the real Java "
                              "digest bit for bit -- this is what makes it usable for a mutation check")


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class MutatedTagRefusedOnPackUpdateRoundTripTest(unittest.TestCase):
    def test_force_moved_tag_is_refused_and_lock_restored(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            src_repo = tmp_dir / "pack-src"
            src_repo.mkdir()
            _run(src_repo, "git", "init", "--quiet", "--initial-branch=main")
            _write(src_repo / "pack.json", {
                "dslVersion": "1.0.0", "pack": "honeypot", "version": "2.0.0",
                "concepts": [{"name": "Account", "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                ]}],
            })
            _git_commit(src_repo, "v2.0.0")
            _run(src_repo, "git", "tag", "v2.0.0")

            coordinate = f"git+{src_repo.resolve().as_uri()}@v2.0.0"
            app_dir = tmp_dir / "app"
            model_path = app_dir / "model.json"
            _write(model_path, {"namespace": "npdev.throwaway.tamper", "dslVersion": "1.0.0",
                                 "version": "1.0", "packs": [{"from": coordinate}]})

            env = {"NPDEV_PACK_CACHE_ROOT": str(tmp_dir / "pack-cache")}
            with mock.patch.dict(os.environ, env):
                # R8.7: this fixture pack is unsigned -- --allow-unsigned is orthogonal to the
                # tamper-guard behavior this test actually proves.
                add_args = argparse.Namespace(model=str(model_path), from_catalog=None, allow_unsigned=True)
                with redirect_stdout(io.StringIO()):
                    add_code = npdev_cli.run_pack_add(add_args)
                self.assertEqual(0, add_code)
                lock_before = (app_dir / "npdev.lock").read_text(encoding="utf-8")

                # The attack: force-move the SAME tag name to point at DIFFERENT content, version
                # string unchanged.
                _write(src_repo / "pack.json", {
                    "dslVersion": "1.0.0", "pack": "honeypot", "version": "2.0.0",
                    "concepts": [{"name": "Account", "fields": [
                        {"name": "id", "type": "uuid", "id": True, "required": True},
                        {"name": "backdoor", "type": "string", "required": False},
                    ]}],
                })
                _git_commit(src_repo, "mutated (same version)")
                _run(src_repo, "git", "tag", "-f", "v2.0.0")

                # allow_unsigned=True so this proves ONLY the R8.6 tamper guard (moved-tag digest
                # mismatch), which must refuse BEFORE R8.7's own signature check ever runs.
                update_args = argparse.Namespace(model=str(model_path), allow_unsigned=True)
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    with redirect_stdout(io.StringIO()):
                        npdev_cli.run_pack_update(update_args)
                message = str(ctx.exception)
                self.assertIn("honeypot", message)
                self.assertIn("2.0.0", message)

            lock_after = (app_dir / "npdev.lock").read_text(encoding="utf-8")
            self.assertEqual(lock_before, lock_after,
                              "npdev.lock must be restored to its pre-attack contents, never left "
                              "trusting the mutated fetch")


if __name__ == "__main__":
    unittest.main()
