"""S5 (NPDEV_MEGA_ROADMAP.md): pins the pack-repo publisher's contract -- one command publishes the
built-in packs into a git repo under semver tags (`<packId>-<version>`), idempotently, so a consumer
can lock to a coordinate like `git+file://<repo>@tracing-1.0.0`.

Stdlib-only (unittest), same convention as the CLI tests. The publish function shells out to the real
`git` binary into a throwaway directory -- no network (file:// transport). Run with:
    python -m unittest NPDevCli.tests.test_publish_pack_repo -v
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

_SPEC = importlib.util.spec_from_file_location(
    "publish_pack_repo", REPO_ROOT / "scripts" / "pack-repo" / "publish-pack-repo.py")
publish_mod = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(publish_mod)


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], check=True,
                          capture_output=True, text=True).stdout


class PublishContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-packrepo-")
        self.tmp = Path(self._tmp.name)
        self.repo = self.tmp / "repo"
        self.addCleanup(self._tmp.cleanup)

    def test_publishes_selected_packs_under_semver_tags(self):
        published = publish_mod.publish(["identity", "tracing"], self.repo)

        self.assertEqual(["identity-1.2.0", "tracing-1.0.0"], sorted(published))
        self.assertTrue((self.repo / "packs" / "identity" / "pack.json").is_file())
        self.assertTrue((self.repo / "packs" / "tracing" / "pack.json").is_file())
        self.assertEqual("1.2.0",
                         json.loads((self.repo / "packs" / "identity" / "pack.json")
                                    .read_text(encoding="utf-8"))["version"])
        # Every published version is tagged -- the pin a consumer's packs[].from locks to.
        tags = _git(self.repo, "tag", "-l")
        self.assertIn("identity-1.2.0", tags)
        self.assertIn("tracing-1.0.0", tags)

    def test_running_again_is_a_no_op(self):
        publish_mod.publish(["identity"], self.repo)
        second = publish_mod.publish(["identity"], self.repo)

        self.assertEqual([], second, "an already-tagged version is not re-published")
        # The commit count stays at exactly one for the published pack.
        log = _git(self.repo, "log", "--oneline")
        self.assertEqual(1, len(log.strip().splitlines()))

    def test_unknown_pack_is_refused(self):
        with self.assertRaises(SystemExit):
            publish_mod.publish(["no-such-pack"], self.repo)


if __name__ == "__main__":
    unittest.main()