"""`npdev setup`'s prebuilt-jars tag lookup, after A3 (Cold Clone Audit 2026-08-28).

THE DEFECT THIS PINS. The prebuilt runtimehost-libs Release asset was only ever found when the
checkout sat EXACTLY on a tag (`git describe --tags --exact-match HEAD`). A plain `git clone` of
main -- a few commits past the latest tag -- returned NOTHING, so every ordinary clone paid the
~10-minute local compile instead of a 4.1 MB download. The fallback to the NEAREST reachable tag
(`--abbrev=0`) fixes the ordinary case; but the nearest tag may be older than HEAD's source, or on
a divergent line (a fork, a rebase that orphaned it), so its jars must only be used when the tag is
an ancestor of HEAD -- `_tag_jars_match_head`.

Run with:
    python -m unittest NPDevCli.tests.test_setup_jars_tag -v
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class SetupJarsTagLookupTest(unittest.TestCase):
    def test_abbrev0_lookup_prefers_nearest_reachable_tag(self):
        """The lookup runs `git describe --tags --abbrev=0` -- NOT the old exact-match form that
        returned nothing on a clone a few commits past the tag."""
        calls: list[list[str]] = []
        real_run = npdev_cli.subprocess.run

        def fake_run(cmd, **kwargs):
            if isinstance(cmd, list) and cmd[:2] == ["git", "describe"]:
                calls.append(cmd)
                return subprocess.CompletedProcess(cmd, 0, stdout="beta1.20\n", stderr="")
            return real_run(cmd, **kwargs)

        npdev_cli.subprocess.run = fake_run
        try:
            tag = npdev_cli._current_git_tag(Path("/fake/repo"))
        finally:
            npdev_cli.subprocess.run = real_run

        self.assertEqual(tag, "beta1.20")
        self.assertEqual(len(calls), 1)
        self.assertIn("--abbrev=0", calls[0])
        self.assertNotIn("--exact-match", calls[0],
                         "exact-match was the old lookup; it is what made the asset unreachable "
                         "for every ordinary clone of main")

    def test_no_tags_returns_none(self):
        """A shallow clone / tag-less repo has nothing published to download; the caller falls
        back to a local build. This must be None, never an exception."""
        real_run = npdev_cli.subprocess.run

        def fake_run(cmd, **kwargs):
            return subprocess.CompletedProcess(cmd, 128, stdout="", stderr="")
        npdev_cli.subprocess.run = fake_run
        try:
            tag = npdev_cli._current_git_tag(Path("/fake/repo"))
        finally:
            npdev_cli.subprocess.run = real_run
        self.assertIsNone(tag)

    def test_ancestor_gate_accepts_ancestor_rejects_divergent(self):
        """merge-base --is-ancestor: exit 0 = the tag is on HEAD's own line (safe to use), exit 1 =
        divergent line (a fork or an orphaned tag) -- jars from a divergent line must not be used."""
        real_run = npdev_cli.subprocess.run

        def make_fake(returncode: int):
            def fake_run(cmd, **kwargs):
                if isinstance(cmd, list) and cmd[:3] == ["git", "merge-base", "--is-ancestor"]:
                    return subprocess.CompletedProcess(cmd, returncode, stdout="", stderr="")
                return real_run(cmd, **kwargs)
            return fake_run

        npdev_cli.subprocess.run = make_fake(0)
        try:
            self.assertTrue(npdev_cli._tag_jars_match_head("beta1.20", Path("/fake/repo")))
        finally:
            npdev_cli.subprocess.run = real_run

        npdev_cli.subprocess.run = make_fake(1)
        try:
            self.assertFalse(npdev_cli._tag_jars_match_head("beta1.20", Path("/fake/repo")))
        finally:
            npdev_cli.subprocess.run = real_run


if __name__ == "__main__":
    unittest.main()