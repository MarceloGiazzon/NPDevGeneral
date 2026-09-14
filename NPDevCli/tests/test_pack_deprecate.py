"""Tests for W3.1: pack deprecation -- `npdev pack deprecate` (writes a `deprecated` block into a
LOCAL pack.json) and the resolution-time warning/refusal `pack add`/`pack update` now perform
(`run_pack_deprecate` / `_check_deprecated_packs` in npdev_cli.py).

PREMISE CHECKED BEFORE WRITING ANY OF THIS: `npdev pack` already has add/update/list/why/search/
build-catalog/export/diff/publish/sign-keygen/verify -- deprecation was the one lifecycle verb
missing, and the only way to retire a published version was to delete it (breaking every
dependent app with no warning). `deprecated` is authored into pack.json BEFORE that version is
ever published, so it becomes ordinary, immutable content of the version it describes -- never a
retroactive edit to an already-published file, and never a digest-mismatch hazard the way editing
an already-locked pack.json in place would be.

Three tiers:
  - `PackDeprecateCliUnitTest`: `run_pack_deprecate` in isolation (dry-run vs --write, refusals).
  - `CheckDeprecatedPacksUnitTest`: `_check_deprecated_packs` directly against a synthetic
    npdev.lock + a real pack.json on disk at the lock entry's own `sourcePath` -- the exact shape
    `packAdd`/`packUpdate` leave behind, without needing a real Gradle/network resolve.
  - `PackDeprecateRoundTripTest`: real end-to-end (~15-25s) -- a genuine `git+file://` remote
    pack carrying a `deprecated` block, resolved through the actual Gradle-backed PK-5 machinery,
    proving the warning (and, with --strict, the refusal) fire on a REAL resolve, not just a
    synthetic lock.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_deprecate -v
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
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")


def _deprecate_args(pack_path: Path, *, since=None, reason="retired", superseded_pack=None,
                     superseded_version=None, write=False) -> argparse.Namespace:
    return argparse.Namespace(
        pack_path=str(pack_path), since=since, reason=reason,
        superseded_by_pack=superseded_pack, superseded_by_version=superseded_version, write=write,
    )


class PackDeprecateCliUnitTest(unittest.TestCase):
    def _pack(self, tmp_dir: Path, **extra) -> Path:
        pack_path = tmp_dir / "pack.json"
        doc = {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0"}
        doc.update(extra)
        _write(pack_path, doc)
        return pack_path

    def test_dry_run_does_not_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            before = pack_path.read_text(encoding="utf-8")
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, reason="superseded"))
            self.assertEqual(0, code)
            self.assertEqual(before, pack_path.read_text(encoding="utf-8"), "dry run must not touch the file")
            report = json.loads(out.getvalue())
            self.assertFalse(report["written"])
            self.assertEqual("1.0.0", report["deprecated"]["since"], "defaults `since` from the pack's own version")

    def test_write_applies_and_since_defaults_to_pack_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            code = npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, reason="use widgets2 instead", write=True))
            self.assertEqual(0, code)
            doc = json.loads(pack_path.read_text(encoding="utf-8"))
            self.assertEqual({"since": "1.0.0", "reason": "use widgets2 instead"}, doc["deprecated"])

    def test_superseded_by_written_when_both_fields_given(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            npdev_cli.run_pack_deprecate(_deprecate_args(
                pack_path, reason="renamed", superseded_pack="widgets2",
                superseded_version="2.0.0", write=True))
            doc = json.loads(pack_path.read_text(encoding="utf-8"))
            self.assertEqual({"pack": "widgets2", "version": "2.0.0"}, doc["deprecated"]["supersededBy"])

    def test_refuses_when_already_deprecated(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp), deprecated={"since": "1.0.0", "reason": "already"})
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, write=True))
            self.assertIn("already has a `deprecated` block", str(ctx.exception))

    def test_requires_reason_non_blank(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, reason="   "))

    def test_since_must_match_semver_pattern(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, since="not-a-version"))

    def test_superseded_fields_required_together(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, superseded_pack="widgets2"))
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_deprecate(_deprecate_args(pack_path, superseded_version="2.0.0"))

    def test_superseded_by_pack_id_pattern_enforced(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = self._pack(Path(tmp))
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_deprecate(_deprecate_args(
                    pack_path, superseded_pack="Not_Valid", superseded_version="2.0.0"))

    def test_missing_pack_file_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_pack_deprecate(_deprecate_args(Path(tmp) / "nope.json"))


def _lock_text(packs: dict) -> str:
    return json.dumps({"schemaVersion": "npdev-lock.v1", "packs": packs}, indent=2) + "\n"


class CheckDeprecatedPacksUnitTest(unittest.TestCase):
    """Exercises `_check_deprecated_packs` directly against a synthetic npdev.lock plus a real
    pack.json on disk at the lock entry's own `sourcePath` -- the exact shape `packAdd`/
    `packUpdate` leave behind, without a real Gradle/network resolve (same isolation level as
    `GuardAgainstRemotePackTamperUnitTest` in test_pack_lock_tamper_guard.py)."""

    def _locked(self, tmp: Path, *, deprecated=None) -> tuple[Path, dict]:
        source = tmp / "cache" / "widgets" / "pack.json"
        doc = {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0"}
        if deprecated is not None:
            doc["deprecated"] = deprecated
        _write(source, doc)
        entry = {"resolvedVersion": "1.0.0", "digest": "sha256:aaaa", "sourcePath": str(source)}
        return source, entry

    def test_no_deprecated_field_is_silent(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _, entry = self._locked(tmp_dir)
            lock_text = _lock_text({"widgets": entry})
            (tmp_dir / "npdev.lock").write_text(lock_text, encoding="utf-8")
            with redirect_stderr(io.StringIO()) as err:
                npdev_cli._check_deprecated_packs(model_path, lock_text, argparse.Namespace(strict=False))
            self.assertEqual("", err.getvalue())

    def test_deprecated_no_successor_warns_even_under_strict(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _, entry = self._locked(tmp_dir, deprecated={"since": "1.0.0", "reason": "no longer maintained"})
            lock_text = _lock_text({"widgets": entry})
            (tmp_dir / "npdev.lock").write_text(lock_text, encoding="utf-8")
            with redirect_stderr(io.StringIO()) as err:
                npdev_cli._check_deprecated_packs(model_path, lock_text, argparse.Namespace(strict=True))
            message = err.getvalue()
            self.assertIn("WARNING", message)
            self.assertIn("widgets", message)
            self.assertIn("no longer maintained", message)

    def test_deprecated_with_successor_warns_without_strict(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _, entry = self._locked(tmp_dir, deprecated={
                "since": "1.0.0", "reason": "renamed",
                "supersededBy": {"pack": "widgets2", "version": "2.0.0"},
            })
            lock_text = _lock_text({"widgets": entry})
            (tmp_dir / "npdev.lock").write_text(lock_text, encoding="utf-8")
            with redirect_stderr(io.StringIO()) as err:
                npdev_cli._check_deprecated_packs(model_path, lock_text, argparse.Namespace(strict=False))
            message = err.getvalue()
            self.assertIn("widgets2", message)
            self.assertIn("2.0.0", message)

    def test_deprecated_with_successor_refuses_under_strict_and_restores_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _, entry = self._locked(tmp_dir, deprecated={
                "since": "1.0.0", "reason": "renamed",
                "supersededBy": {"pack": "widgets2", "version": "2.0.0"},
            })
            before_text = _lock_text({})  # simulate: nothing was locked before this resolve
            lock_path = tmp_dir / "npdev.lock"
            lock_path.write_text(_lock_text({"widgets": entry}), encoding="utf-8")

            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli._check_deprecated_packs(model_path, before_text, argparse.Namespace(strict=True))
            message = str(ctx.exception)
            self.assertIn("widgets2", message)
            self.assertIn("--strict", message)
            self.assertEqual(before_text, lock_path.read_text(encoding="utf-8"),
                              "the lock must be restored to its pre-call contents on refusal")

    def test_relative_source_path_resolves_against_the_models_own_directory(self):
        """Regression: a LOCAL pack's `sourcePath` in npdev.lock is relative to the MODEL's own
        directory, never to the CLI process's current working directory -- caught live while
        capturing a Manager fixture for W3.2 (`npdev pack list` run from the repo root against an
        app in a different directory silently found nothing for a relative sourcePath before
        `_resolve_pack_source_path` existed). `pack add`/`update` always emit a genuinely relative
        `sourcePath` for a LOCAL pack (the default `packs/<id>/pack.json` convention), so this is
        the realistic case, not merely a synthetic one."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _write(tmp_dir / "packs" / "oldwidgets" / "pack.json", {
                "dslVersion": "1.0.0", "pack": "oldwidgets", "version": "1.0.0",
                "deprecated": {"since": "1.0.0", "reason": "retired",
                               "supersededBy": {"pack": "newwidgets", "version": "2.0.0"}},
            })
            entry = {"resolvedVersion": "1.0.0", "digest": "sha256:aaaa",
                      "sourcePath": "packs/oldwidgets/pack.json"}  # relative, matches real lock shape
            lock_text = _lock_text({"oldwidgets": entry})
            (tmp_dir / "npdev.lock").write_text(lock_text, encoding="utf-8")

            previous_cwd = Path.cwd()
            other_dir = tempfile.mkdtemp()
            try:
                os.chdir(other_dir)  # simulate the CLI running from an unrelated cwd
                with redirect_stderr(io.StringIO()) as err:
                    npdev_cli._check_deprecated_packs(model_path, lock_text, argparse.Namespace(strict=False))
            finally:
                os.chdir(previous_cwd)
            message = err.getvalue()
            self.assertIn("oldwidgets", message)
            self.assertIn("newwidgets", message)

    def test_missing_source_path_is_skipped_not_a_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            entry = {"resolvedVersion": "1.0.0", "digest": "sha256:aaaa", "sourcePath": str(tmp_dir / "gone.json")}
            lock_text = _lock_text({"widgets": entry})
            (tmp_dir / "npdev.lock").write_text(lock_text, encoding="utf-8")
            npdev_cli._check_deprecated_packs(model_path, lock_text, argparse.Namespace(strict=True))  # must not raise


def _run(cwd: Path, *cmd: str) -> None:
    subprocess.run(list(cmd), cwd=str(cwd), check=True, capture_output=True, text=True)


def _git_commit(repo_dir: Path, message: str) -> None:
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com", "add", "-A")
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
         "commit", "--quiet", "-m", message)


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class PackDeprecateRoundTripTest(unittest.TestCase):
    """Real end-to-end: a `git+file://` remote pack whose pack.json was authored with a
    `deprecated` block BEFORE being tagged/published, resolved through the actual Gradle-backed
    PK-5 fetch machinery via `run_pack_add`/`run_pack_update` -- proving the warning path (and the
    --strict refusal) fire on a genuine resolve, not just the synthetic lock the unit tests above
    use. ~15-25s."""

    def test_resolving_a_deprecated_pack_warns_and_strict_refuses(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            src_repo = tmp_dir / "pack-src"
            src_repo.mkdir()
            _run(src_repo, "git", "init", "--quiet", "--initial-branch=main")
            _write(src_repo / "pack.json", {
                "dslVersion": "1.0.0", "pack": "oldwidgets", "version": "1.0.0",
                "deprecated": {
                    "since": "1.0.0", "reason": "replaced by newwidgets",
                    "supersededBy": {"pack": "newwidgets", "version": "2.0.0"},
                },
            })
            _git_commit(src_repo, "v1.0.0")
            _run(src_repo, "git", "tag", "v1.0.0")

            coordinate = f"git+{src_repo.resolve().as_uri()}@v1.0.0"
            app_dir = tmp_dir / "app"
            model_path = app_dir / "model.json"
            _write(model_path, {"namespace": "npdev.throwaway.deprecate", "dslVersion": "1.0.0",
                                 "version": "1.0", "packs": [{"from": coordinate}]})

            env = {"NPDEV_PACK_CACHE_ROOT": str(tmp_dir / "pack-cache")}
            with mock.patch.dict(os.environ, env):
                add_args = argparse.Namespace(model=str(model_path), from_catalog=None,
                                               allow_unsigned=True, strict=False)
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as warn_err:
                    add_code = npdev_cli.run_pack_add(add_args)
                self.assertEqual(0, add_code, "a non-strict deprecated resolve must still succeed")
                warning = warn_err.getvalue()
                self.assertIn("DEPRECATED", warning)
                self.assertIn("oldwidgets", warning)
                self.assertIn("newwidgets", warning)
                self.assertIn("2.0.0", warning)

                lock_before_strict = (app_dir / "npdev.lock").read_text(encoding="utf-8")
                strict_args = argparse.Namespace(model=str(model_path), allow_unsigned=True, strict=True)
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    with redirect_stdout(io.StringIO()):
                        npdev_cli.run_pack_update(strict_args)
                message = str(ctx.exception)
                self.assertIn("newwidgets", message)
                self.assertIn("--strict", message)
                lock_after_strict = (app_dir / "npdev.lock").read_text(encoding="utf-8")
                self.assertEqual(lock_before_strict, lock_after_strict,
                                  "a --strict refusal must restore npdev.lock, never leave it "
                                  "trusting the resolve it just refused")


if __name__ == "__main__":
    unittest.main()
