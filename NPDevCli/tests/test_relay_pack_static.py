"""REG-347 / REG-350: what a browser AI's pack answer is refused for BEFORE the Java validator runs, with the way out in the line.

Both cases come from the owner's real round of 2026-11-20 (Gemini, a ratings pack): it declared roles[] naming its own concept and
wrote every ui.label in Portuguese; the Java validator's generic line sent it guessing, and the Portuguese one was published."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_relay_pack as relay_pack  # noqa: E402


def _pack(**changes) -> dict:
    pack = {"dslVersion": "3.0.0", "pack": "ratings", "version": "1.0.0", "namespace": "com.example.ratings",
            "description": "Star ratings with a comment.", "category": "other",
            "concepts": [{"name": "Rating", "ui": {"label": "Rating"},
                          "fields": [{"name": "id", "type": "uuid", "id": True, "required": True},
                                     {"name": "stars", "type": "integer", "required": True, "ui": {"label": "Stars"}}],
                          "access": {"read": "authorAccount == $user.id || $user.roles.contains('Manager')",
                                     "write": "authorAccount == $user.id"}}]}
    pack.update(changes)
    return pack


def _errors(pack: dict) -> list[str]:
    import json

    return relay_pack.static_errors(pack, "ratings", json.dumps(pack))


class RolesInAPackTests(unittest.TestCase):
    def test_a_role_that_names_the_packs_own_concept_is_refused_with_the_way_out(self):
        pack = _pack(roles=[{"name": "Rater", "concepts": {"Rating": ["read", "create"]}}])
        lines = [l for l in _errors(pack) if l.startswith("pack.json roles")]
        self.assertEqual(len(lines), 1)
        self.assertIn('"Rating"', lines[0])
        self.assertIn('remove "roles"', lines[0])
        self.assertIn("$user.roles.contains('Manager')", lines[0])

    def test_a_role_with_only_platform_grants_or_another_packs_concept_is_left_to_the_validator(self):
        for role in ({"name": "Admin", "grants": ["export"]}, {"name": "Rep", "concepts": {"sales::Customer": ["read"]}}):
            self.assertEqual([l for l in _errors(_pack(roles=[role])) if l.startswith("pack.json roles")], [])


class EnglishInAPackTests(unittest.TestCase):
    def test_a_portuguese_label_is_refused_and_named(self):
        pack = _pack()
        pack["concepts"][0]["fields"][1]["ui"]["label"] = "Estrelas"
        pack["concepts"][0]["ui"]["label"] = "Avaliação"
        lines = [l for l in _errors(pack) if "is not English" in l]
        self.assertEqual(len(lines), 1)            # "Estrelas" has no accent: an accent is the sign, not a dictionary
        self.assertIn("/concepts/0/ui/label", lines[0])
        self.assertIn("Avaliação", lines[0])

    def test_an_english_pack_with_ordinary_punctuation_is_clean(self):
        self.assertEqual([l for l in _errors(_pack()) if "English" in l], [])

    def test_a_long_list_is_cut_and_counted(self):
        pack = _pack()
        pack["concepts"][0]["fields"] = [{"name": f"f{i}", "type": "string", "ui": {"label": f"Descrição {i}"}} for i in range(9)]
        lines = [l for l in _errors(pack) if "English" in l or "not English" in l]
        self.assertEqual(len(lines), relay_pack.MAX_LANGUAGE_LINES + 1)
        self.assertIn("5 more texts", lines[-1])


if __name__ == "__main__":
    unittest.main()
