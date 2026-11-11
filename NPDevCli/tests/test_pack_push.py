"""P5: `pack push` -- a pack from "My parts" into a clone of the public catalog, public only with an explicit confirmation.

Everything runs against a LOCAL bare repository standing in for the public one: no network, no credentials. The model
validator is a stub (fast); the publish gate (Gradle) is mocked where a test needs a verdict.

Run with: python -m unittest NPDevCli.tests.test_pack_push -v
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import npdev_pack_push as pp  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402

PACK = {"dslVersion": "1.0.0", "pack": "ratings", "version": "1.0.0", "namespace": "com.example.ratings",
        "description": "Star ratings.", "concepts": [{"name": "Rating", "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True}, {"name": "stars", "type": "integer", "min": 1, "max": 5}]}]}
PASSED = {"status": "passed", "diagnostics": []}
FAILED = {"status": "failed", "diagnostics": [{"severity": "error", "path": "/packs/0", "message": "type is not allowed"}]}


def git(cwd, *args):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@localhost", *args], cwd=cwd, capture_output=True, text=True, check=True).stdout


class PushCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.bare = root / "public.git"
        subprocess.run(["git", "init", "--bare", "-q", "-b", "main", str(self.bare)], check=True)
        self.clone = root / "npr"
        subprocess.run(["git", "clone", "-q", str(self.bare), str(self.clone)], check=True, capture_output=True)
        (self.clone / "README.md").write_text("catalog\n", encoding="utf-8")
        git(self.clone, "add", "README.md")
        git(self.clone, "commit", "-q", "-m", "init")
        git(self.clone, "push", "-q", "origin", "HEAD:main")
        self.part = root / "mine" / "ratings"
        self.part.mkdir(parents=True)
        self.write(PACK)
        self.cli = engine._cli_module()

    def write(self, pack):
        (self.part / "pack.json").write_text(json.dumps(pack, indent=2), encoding="utf-8")

    def run_push(self, validate=None, **kw):
        return pp.push(self.cli, pack_dir=self.part, npr_checkout=self.clone, validate=validate or (lambda *_: PASSED), **kw)

    def bare_tags(self):
        return git(self.bare, "tag").split()

    def clone_head(self):
        return git(self.clone, "rev-parse", "HEAD").strip()


class PushTests(PushCase):
    def test_a_dry_run_shows_what_would_become_public_and_touches_nothing(self):
        before = self.clone_head()
        result = self.run_push(dry_run=True)
        self.assertTrue(result["ok"] and result["dryRun"] and not result["committed"] and not result["pushed"])
        self.assertEqual(result["summary"]["files"], ["packs/ratings/pack.json", "catalog-index.json"])
        self.assertIn("Anyone on the internet", result["summary"]["public"])
        self.assertEqual(result["confirmWith"], "ratings@1.0.0")
        self.assertEqual(self.clone_head(), before)
        self.assertFalse((self.clone / "packs").exists())

    def test_without_a_confirmation_it_commits_locally_and_stops_before_the_push(self):
        result = self.run_push()
        self.assertTrue(result["ok"] and result["committed"] and not result["pushed"], result)
        self.assertIn("--confirm-public ratings@1.0.0", result["message"])
        self.assertTrue((self.clone / "packs" / "ratings" / "pack.json").is_file())
        self.assertIn("ratings-1.0.0", git(self.clone, "tag").split())
        self.assertEqual(self.bare_tags(), [])  # nothing reached the public repository

    def test_a_confirmation_for_another_part_or_version_is_refused_before_anything_is_written(self):
        before = self.clone_head()
        for wrong in ("ratings@2.0.0", "other@1.0.0", "ratings", ""):
            result = self.run_push(confirm_public=wrong)
            self.assertFalse(result["ok"])
            self.assertEqual(result["error"], "confirm-mismatch")
        self.assertEqual(self.clone_head(), before)
        self.assertFalse((self.clone / "packs").exists())
        self.assertEqual(self.bare_tags(), [])

    def test_the_right_confirmation_pushes_the_commit_and_the_tag(self):
        result = self.run_push(confirm_public="ratings@1.0.0")
        self.assertTrue(result["ok"] and result["pushed"], result)
        self.assertEqual(self.bare_tags(), ["ratings-1.0.0"])
        self.assertEqual(git(self.bare, "rev-parse", "main").strip(), self.clone_head())
        index = json.loads(git(self.bare, "show", "main:catalog-index.json"))
        self.assertIn("ratings", json.dumps(index))

    def test_a_confirmation_after_the_local_commit_still_pushes_it(self):
        self.run_push()  # first call: local commit only
        again = self.run_push(confirm_public="ratings@1.0.0")
        self.assertTrue(again["ok"] and again["pushed"], again)
        self.assertEqual(self.bare_tags(), ["ratings-1.0.0"])

    def test_a_push_that_fails_says_nothing_was_published(self):
        git(self.clone, "remote", "set-url", "origin", str(self.bare.parent / "gone.git"))
        result = self.run_push(confirm_public="ratings@1.0.0")
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "push-failed")
        self.assertIn("Nothing was published", result["message"])

    def test_a_published_version_cannot_be_changed(self):
        self.run_push(confirm_public="ratings@1.0.0")
        self.write({**PACK, "description": "changed after publishing"})
        result = self.run_push(confirm_public="ratings@1.0.0")
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "immutable-version")

    def test_a_version_bump_smaller_than_the_change_is_refused(self):
        self.run_push(confirm_public="ratings@1.0.0")
        self.write({**PACK, "version": "1.0.1"})
        report = {"allowed": False, "message": "ADDITIVE change needs a minor version bump"}

        def gate(args):
            Path(args.out).write_text(json.dumps(report), encoding="utf-8")
            return 2

        with mock.patch.object(self.cli, "run_pack_publish", side_effect=gate):
            result = self.run_push(confirm_public="ratings@1.0.1")
        self.assertFalse(result["ok"])
        self.assertIn("minor version bump", " ".join(result["problems"]))
        self.assertEqual(self.bare_tags(), ["ratings-1.0.0"])

    def test_a_secret_or_a_binding_or_a_part_that_does_not_compose_is_refused(self):
        self.write({**PACK, "description": "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789"})
        self.assertIn("secret", " ".join(self.run_push()["problems"]))
        self.write({**PACK, "bindings": [{"capability": "x", "adapter": "y"}]})
        self.assertIn("bindings", " ".join(self.run_push()["problems"]))
        self.write(PACK)
        failed = self.run_push(validate=lambda *_: FAILED)
        self.assertFalse(failed["ok"])
        self.assertIn("type is not allowed", " ".join(failed["problems"]))
        self.assertFalse((self.clone / "packs").exists())

    def test_a_folder_that_is_not_a_clone_of_the_catalog_is_refused(self):
        result = pp.push(self.cli, pack_dir=self.part, npr_checkout=self.part.parent, validate=lambda *_: PASSED)
        self.assertEqual(result["error"], "not-a-catalog-clone")

    def test_the_command_line_reaches_it(self):
        out = subprocess.run([sys.executable, str(HERE.parent / "npdev_cli.py"), "pack", "push", "--pack-dir", str(self.part),
                              "--npr-checkout", str(self.clone), "--dry-run", "--json"], capture_output=True, text=True)
        self.assertIn(out.returncode, (0, 2), out.stderr[-400:])
        document = json.loads(out.stdout)
        self.assertEqual(document["packId"] if "packId" in document else "ratings", "ratings")
        self.assertFalse(document["pushed"])


if __name__ == "__main__":
    unittest.main()
