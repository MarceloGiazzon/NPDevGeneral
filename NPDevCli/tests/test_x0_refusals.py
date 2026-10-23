"""X0 rule ("a checker must REFUSE rather than guess") -- must-error tests for REG-296/297/299.

REG-296: check-pack-diff-gate.py / check-done-item-guards.py took their baseline from
`git merge-base HEAD origin/main`, which IS HEAD on a push to main -- both passed vacuously.
REG-297: `npdev verify --tier T2/T3` treated an unreachable/403 remote CI as green.
REG-299: check-pack-coordinates-pinned / check-pack-shared-not-forked / check-pack-coverage skipped a
model.json/pack.json that would not parse.

Hermetic: temp git repos with explicit -c identity flags, no network (fetch has no origin remote to
reach), no gradle (the refusal happens before any packPublish call).
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

CLI_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = CLI_DIR.parent
QUALITY = REPO_ROOT / "scripts" / "quality"
sys.path.insert(0, str(CLI_DIR))


def _load(script: str):
    path = QUALITY / script
    spec = importlib.util.spec_from_file_location(script.replace("-", "_").removesuffix(".py"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _git(root: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-c", "commit.gpgsign=false", "-c", "user.email=t@example.com", "-c", "user.name=t", *args],
        cwd=str(root), check=True, capture_output=True, text=True).stdout.strip()


def _commit_all(root: Path, message: str) -> str:
    _git(root, "add", "-A")
    _git(root, "commit", "-q", "-m", message)
    return _git(root, "rev-parse", "HEAD")


def _quiet(fn, *args):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        rc = fn(*args)
    return rc, out.getvalue() + err.getvalue()


ENV_NO_BASE = {k: v for k, v in os.environ.items() if k != "NPDEV_BASELINE_REV"}


class PackDiffGateBaselineTest(unittest.TestCase):
    """REG-296, check-pack-diff-gate.py."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        _git(self.root, "init", "-q", "-b", "main")
        self.pack = self.root / "NPDevContract" / "packs" / "identity" / "pack.json"
        self.pack.parent.mkdir(parents=True)
        self.pack.write_text(json.dumps({"pack": "identity", "version": "1.0.0", "concepts": []}), encoding="utf-8")
        self.first = _commit_all(self.root, "v1")
        # A content change with NO version bump, committed and "pushed": origin/main == HEAD, exactly
        # what CI sees on a push to main.
        self.pack.write_text(json.dumps({"pack": "identity", "version": "1.0.0",
                                         "concepts": [{"name": "Extra"}]}), encoding="utf-8")
        _commit_all(self.root, "change without bump")
        _git(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")
        self.mod = _load("check-pack-diff-gate.py")
        self.mod.REPO_ROOT = self.root
        self.mod.CHECKED_PACKS = (self.pack,)

    def tearDown(self):
        self.tmp.cleanup()

    def test_origin_main_equal_to_head_refuses_instead_of_passing(self):
        with mock.patch.dict(os.environ, ENV_NO_BASE, clear=True):
            rc, out = _quiet(self.mod.main, ["check-pack-diff-gate.py"])
        self.assertNotEqual(rc, 0, out)

    def test_explicit_base_compares_the_pushed_range(self):
        refused = {"allowed": False, "message": "content changed with no version bump"}
        with mock.patch.dict(os.environ, ENV_NO_BASE, clear=True), \
                mock.patch.object(self.mod, "run_pack_publish", return_value=refused) as publish:
            rc, out = _quiet(self.mod.main, ["check-pack-diff-gate.py", "--base", self.first])
        self.assertEqual(rc, 1, out)
        publish.assert_called_once()

    def test_all_zeros_base_is_not_a_baseline(self):
        with mock.patch.dict(os.environ, dict(ENV_NO_BASE, NPDEV_BASELINE_REV="0" * 40), clear=True):
            rc, out = _quiet(self.mod.main, ["check-pack-diff-gate.py"])
        self.assertNotEqual(rc, 0, out)

    def test_no_baseline_ok_is_the_explicit_local_opt_out(self):
        with mock.patch.dict(os.environ, ENV_NO_BASE, clear=True):
            rc, out = _quiet(self.mod.main, ["check-pack-diff-gate.py", "--no-baseline-ok"])
        self.assertEqual(rc, 0, out)


class DoneItemGuardsBaselineTest(unittest.TestCase):
    """REG-296, check-done-item-guards.py R4."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        _git(self.root, "init", "-q", "-b", "main")
        items = self.root / "ledger" / "items"
        items.mkdir(parents=True)
        policy = self.root / "scripts" / "policy" / "done-item-guard-policy.json"
        policy.parent.mkdir(parents=True)
        (items / "OLD-1.yml").write_text("id: OLD-1\nstatus: DONE\n", encoding="utf-8")
        policy.write_text(json.dumps({"legacy": {"frozenCount": 1, "ids": ["OLD-1"]}}), encoding="utf-8")
        self.first = _commit_all(self.root, "baseline")
        # The reviewer's attack, COMMITTED (as on a push): a brand-new guard-less DONE item laundered
        # into the freeze list in the same commit that creates it.
        (items / "NEW-1.yml").write_text("id: NEW-1\nstatus: DONE\n", encoding="utf-8")
        policy.write_text(json.dumps({"legacy": {"frozenCount": 2, "ids": ["OLD-1", "NEW-1"]}}), encoding="utf-8")
        _commit_all(self.root, "launder NEW-1")
        _git(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")
        self.mod = _load("check-done-item-guards.py")
        self.mod.REPO_ROOT = self.root
        self.mod.POLICY_PATH = policy
        self.items = [{"id": "OLD-1", "status": "DONE"}, {"id": "NEW-1", "status": "DONE"}]

    def tearDown(self):
        self.tmp.cleanup()

    def _main(self, *argv: str):
        with mock.patch.dict(os.environ, ENV_NO_BASE, clear=True), \
                mock.patch.object(self.mod, "load_items", return_value=self.items):
            return _quiet(self.mod.main, list(argv))

    def test_origin_main_equal_to_head_refuses_instead_of_passing(self):
        rc, out = self._main()
        self.assertNotEqual(rc, 0, out)

    def test_explicit_base_catches_the_laundered_id(self):
        rc, out = self._main("--base", self.first)
        self.assertEqual(rc, 1, out)
        self.assertIn("RATCHET VIOLATION (R4): 'NEW-1'", out)


class RemoteCiUncheckedIsNotGreenTest(unittest.TestCase):
    """REG-297, npdev verify --tier T2."""

    def _verify(self, offline: bool):
        import argparse
        import npdev_cli

        def fake_run(cmd, *a, **kw):
            stdout = json.dumps({"status": "passed"}) if "report" in cmd else ""
            return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

        forbidden = urllib.error.HTTPError("https://api.github.com/x", 403, "rate limited", {}, None)
        args = argparse.Namespace(tier="T2", offline=offline)
        with mock.patch.object(npdev_cli.subprocess, "run", side_effect=fake_run), \
                mock.patch("urllib.request.urlopen", side_effect=forbidden), \
                redirect_stderr(io.StringIO()):
            return npdev_cli.run_verify(args)

    def test_http_403_is_not_ok(self):
        result = self._verify(offline=False)
        self.assertFalse(result["ok"], result)
        self.assertFalse(result["remoteCi"]["checked"])

    def test_offline_is_the_explicit_waiver(self):
        self.assertTrue(self._verify(offline=True)["ok"])


class PackCheckersRefuseUnparseableJsonTest(unittest.TestCase):
    """REG-299: a truncated model.json / pack.json is a finding, not a skip."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        for marker in ("NPDevContract", "NPDevGenerator", "NPDevKernel"):
            (self.root / marker).mkdir()
        app = self.root / "NPDevSamples" / "broken-app"
        app.mkdir(parents=True)
        (app / "model.json").write_text('{"packs": [{"from": "git+https://x/y@latest"', encoding="utf-8")
        (app / "pack.json").write_text('{"pack": "identity", "version": "1.0', encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_coordinates_pinned_fails_on_truncated_model_json(self):
        mod = _load("check-pack-coordinates-pinned.py")
        rc, out = _quiet(mod.main, ["--repo", str(self.root)])
        self.assertNotEqual(rc, 0, out)

    def test_shared_not_forked_fails_on_truncated_pack_json(self):
        mod = _load("check-pack-shared-not-forked.py")
        rc, out = _quiet(mod.main, ["x", "--samples-root", str(self.root / "NPDevSamples"),
                                    "--packs-root", str(self.root / "none")])
        self.assertNotEqual(rc, 0, out)

    def test_pack_coverage_fails_on_truncated_model_json(self):
        mod = _load("check-pack-coverage.py")
        rc, out = _quiet(mod.main, ["x", "--samples-root", str(self.root / "NPDevSamples"),
                                    "--appgen-root", str(self.root / "none")])
        self.assertNotEqual(rc, 0, out)


if __name__ == "__main__":
    unittest.main()
