"""npdev ai context --detail lean|standard|full|auto: the sizes only shrink, auto picks from the ask, and unsure means standard."""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_ai  # noqa: E402
import npdev_cli  # noqa: E402


def _context(*argv: str) -> dict:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = npdev_cli.main(["ai", "context", "--json", *argv])
    assert code == 0, out.getvalue()
    return json.loads(out.getvalue())


class SuggestDetailTests(unittest.TestCase):
    def test_plain_records_get_lean(self):
        self.assertEqual(npdev_ai.suggest_detail("A small app to keep track of my customers and the bikes they bring")[0], "lean")

    def test_roles_states_and_reports_get_standard_in_both_languages(self):
        for ask in ("Purchase requests that a manager must approve", "Pedidos de compra que o gerente aprova",
                    "Show the total sales per salesperson", "A leave request has a status"):
            self.assertEqual(npdev_ai.suggest_detail(ask)[0], "standard", ask)

    def test_packs_and_integrations_get_full(self):
        for ask in ("Reuse a pack for invoices", "Sync with an external API", "Integração com o banco"):
            self.assertEqual(npdev_ai.suggest_detail(ask)[0], "full", ask)

    def test_unsure_means_standard(self):
        self.assertEqual(npdev_ai.suggest_detail("")[0], "standard")
        self.assertEqual(npdev_ai.suggest_detail("x " * 400)[0], "standard")

    def test_a_word_inside_another_word_does_not_count(self):
        self.assertEqual(npdev_ai.suggest_detail("A rapid list of capital cities")[0], "lean")


class ContextDetailCommandTests(unittest.TestCase):
    def test_sizes_only_shrink_and_full_flag_is_the_same_as_detail_full(self):
        lean, standard, full = (_context("--detail", d)["bytes"] for d in ("lean", "standard", "full"))
        self.assertLess(lean, standard)
        self.assertLess(standard, full)
        self.assertEqual(_context("--full")["bytes"], full)
        self.assertEqual(_context()["bytes"], standard)

    def test_auto_reports_what_it_chose_and_why(self):
        with tempfile.TemporaryDirectory() as tmp:
            ask = Path(tmp) / "ask.txt"
            ask.write_text("Purchase requests that a manager must approve", encoding="utf-8")
            answer = _context("--detail", "auto", "--ask-file", str(ask))
        self.assertEqual(answer["detailChosen"], "standard")
        self.assertIn("approv", answer["detailBecause"])


if __name__ == "__main__":
    unittest.main()
