"""Tests for `npdev migrate pack-lock-paths` (REG-250: a remote pack's npdev.lock sourcePath is
cache-relative, so one committed lock is valid on every machine).

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_migrate_pack_lock_paths -v
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

DIGEST = "9b6a37101172cff4461bbbbb0b494589059dadb607c0b52e08b5e5755188543d"


def _write_lock(directory: Path, remote_source_path: str) -> Path:
    lock = {
        "schemaVersion": "npdev-lock.v1",
        "packs": {
            "identity": {
                "resolvedVersion": "1.2.0",
                "digest": "sha256:" + DIGEST,
                "sourcePath": remote_source_path,
                "from": "git+file:///repo//packs/identity@identity-1.2.0",
            },
            "local": {
                "resolvedVersion": "1.0.0",
                "digest": "sha256:" + "0" * 64,
                "sourcePath": "packs/local/pack.json",
            },
        },
    }
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "npdev.lock"
    path.write_text(json.dumps(lock, indent=2), encoding="utf-8")
    return path


def _run(inputs: list[str], write: bool) -> str:
    out = io.StringIO()
    with redirect_stdout(out):
        code = npdev_cli.run_migrate_pack_lock_paths(argparse.Namespace(input=inputs, write=write))
    assert code == 0
    return out.getvalue()


class MigratePackLockPathsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.absolute = str((self.tmp / "home" / ".npdev" / "packs" / "sha256" / DIGEST / "pack.json").resolve())

    def tearDown(self):
        self._tmp.cleanup()

    def test_dry_run_reports_without_writing(self):
        lock = _write_lock(self.tmp / "app", self.absolute)
        before = lock.read_bytes()
        output = _run([str(self.tmp)], write=False)
        self.assertIn("WOULD CHANGE", output)
        self.assertEqual(before, lock.read_bytes())

    def test_write_rewrites_only_the_remote_entry(self):
        lock = _write_lock(self.tmp / "app", self.absolute)
        _run([str(lock)], write=True)
        packs = json.loads(lock.read_text(encoding="utf-8"))["packs"]
        self.assertEqual(f"sha256/{DIGEST}/pack.json", packs["identity"]["sourcePath"])
        self.assertEqual("packs/local/pack.json", packs["local"]["sourcePath"])

    def test_second_run_is_a_no_op(self):
        lock = _write_lock(self.tmp / "app", self.absolute)
        _run([str(lock)], write=True)
        after_first = lock.read_bytes()
        output = _run([str(lock)], write=True)
        self.assertIn("0 of 1", output)
        self.assertEqual(after_first, lock.read_bytes())

    def test_portable_remote_path_resolves_against_the_cache_root(self):
        resolved = npdev_cli._resolve_pack_source_path(
            self.tmp / "app" / "model.json", f"sha256/{DIGEST}/pack.json", "git+file:///repo//packs/x@x-1")
        self.assertEqual(npdev_cli._pack_cache_root() / "sha256" / DIGEST / "pack.json", resolved)


if __name__ == "__main__":
    unittest.main()
