"""Tests for `npdev migration diff --suggest-renames`'s rendering (boundary lift plan 2026-09-02,
package 2.2, docs/ACCEPTED_BOUNDARIES.md B1).

`run_migration_diff` itself shells out to a Gradle task, so this targets `_print_rename_candidates` --
the pure, stdlib-only rendering of the `renameCandidates` array `ModelChangeClassifierMain` already
emits -- directly. Stdlib-only (unittest), matching this repo's convention. Run with:
    python -m unittest NPDevCli.tests.test_migration_diff_suggest_renames -v
"""

from __future__ import annotations

import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class PrintRenameCandidatesTest(unittest.TestCase):
    def test_no_candidates_prints_none_found(self):
        out = io.StringIO()
        with redirect_stdout(out):
            npdev_cli._print_rename_candidates([])
        self.assertIn("none found", out.getvalue())

    def test_a_resolved_candidate_prints_every_signal_and_an_accept_hint(self):
        candidates = [{
            "table": "widgets",
            "droppedColumn": "email_addres",
            "addedColumn": "email_address",
            "score": 92,
            "maxScore": 100,
            "concept": "Widget",
            "droppedField": "emailAddres",
            "addedField": "emailAddress",
            "signals": [
                {"signal": "name similarity", "points": 22, "maxPoints": 25, "detail": "88% ('email_addres' vs 'email_address')"},
                {"signal": "type", "points": 25, "maxPoints": 25, "detail": "MATCH (VARCHAR(255))"},
            ],
        }]
        out = io.StringIO()
        with redirect_stdout(out):
            npdev_cli._print_rename_candidates(candidates)
        text = out.getvalue()

        self.assertIn("email_addres", text)
        self.assertIn("email_address", text)
        self.assertIn("widgets", text)
        self.assertIn("92/100", text)
        self.assertIn("name similarity", text)
        self.assertIn("22/25", text)
        self.assertIn("npdev migrate rename --model <model.json> Widget.emailAddres emailAddress", text)
        self.assertIn('"droppedField": "emailAddres"', text)

    def test_an_unresolved_candidate_prints_the_sql_evidence_but_no_accept_hint(self):
        candidates = [{
            "table": "widgets",
            "droppedColumn": "legacy_flag",
            "addedColumn": "shipping_address_id",
            "score": 12,
            "maxScore": 100,
            "signals": [],
        }]
        out = io.StringIO()
        with redirect_stdout(out):
            npdev_cli._print_rename_candidates(candidates)
        text = out.getvalue()

        self.assertIn("legacy_flag", text)
        self.assertIn("could not resolve this pair to a DSL field", text)
        self.assertNotIn("npdev migrate rename", text)


def _run(cwd: Path, *cmd: str) -> None:
    subprocess.run(list(cmd), cwd=str(cwd), check=True, capture_output=True, text=True)


def _git_commit(repo_dir: Path, message: str) -> None:
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com", "add", "-A")
    _run(repo_dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
         "commit", "--quiet", "-m", message)


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class GitCommittedSnapshotTest(unittest.TestCase):
    """`_git_committed_snapshot` (REAL_LIFT_PLAN_2026-09-03 package C1, boundary B1, REG-206):
    `migration diff --suggest-renames`'s fallback baseline when none is given by hand. Real git
    repos via subprocess, matching test_pack_lock_tamper_guard.py's precedent -- this is exactly
    the kind of thing a synthetic/mocked git would give false confidence about.
    """

    def test_resolves_the_HEAD_committed_copy_not_the_dirty_working_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            _run(repo, "git", "init", "--quiet", "--initial-branch=main")
            model_path = repo / "model.json"
            model_path.write_text('{"version": "1.0.0"}', encoding="utf-8", newline="\n")
            _git_commit(repo, "initial model")

            # Dirty the working copy after committing -- the snapshot must reflect HEAD, not this.
            model_path.write_text('{"version": "2.0.0"}', encoding="utf-8", newline="\n")

            snapshot = npdev_cli._git_committed_snapshot(model_path)

            self.assertIsNotNone(snapshot)
            self.assertTrue(snapshot.exists())
            self.assertEqual(snapshot.read_text(encoding="utf-8"), '{"version": "1.0.0"}')

    def test_returns_none_outside_a_git_repo(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            model_path.write_text('{"version": "1.0.0"}', encoding="utf-8")

            self.assertIsNone(npdev_cli._git_committed_snapshot(model_path))

    def test_returns_none_for_an_untracked_file_with_no_committed_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            _run(repo, "git", "init", "--quiet", "--initial-branch=main")
            # Something else committed, so the repo is real, but model.json itself never was.
            (repo / "README.md").write_text("placeholder", encoding="utf-8")
            _git_commit(repo, "unrelated initial commit")

            model_path = repo / "model.json"
            model_path.write_text('{"version": "1.0.0"}', encoding="utf-8")

            self.assertIsNone(npdev_cli._git_committed_snapshot(model_path))


if __name__ == "__main__":
    unittest.main()
