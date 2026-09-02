"""Tests for `npdev migration diff --suggest-renames`'s rendering (boundary lift plan 2026-09-02,
package 2.2, docs/ACCEPTED_BOUNDARIES.md B1).

`run_migration_diff` itself shells out to a Gradle task, so this targets `_print_rename_candidates` --
the pure, stdlib-only rendering of the `renameCandidates` array `ModelChangeClassifierMain` already
emits -- directly. Stdlib-only (unittest), matching this repo's convention. Run with:
    python -m unittest NPDevCli.tests.test_migration_diff_suggest_renames -v
"""

from __future__ import annotations

import io
import sys
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


if __name__ == "__main__":
    unittest.main()
