"""FORM P1-T7: check-twin-pair-consistency.py can run only the rules whose pinned files changed.

The triggers are derived from each rule's own files, so these tests pin the derivation and the "unknown = run all"
fallback, using the real registry for the shape check.
"""

from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
QUALITY = REPO_ROOT / "scripts" / "quality"

spec = importlib.util.spec_from_file_location("twin_pair_under_test", QUALITY / "check-twin-pair-consistency.py")
tp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tp)


class TwinPairFilterTest(unittest.TestCase):
    def test_a_rule_is_touched_by_a_file_it_pins(self):
        rule = {"id": "r", "files": ["a/x.json", "b/y.json"]}
        self.assertTrue(tp.rule_is_touched(rule, {"b/y.json"}))
        self.assertFalse(tp.rule_is_touched(rule, {"c/z.json"}))

    def test_a_list_file_touches_its_rule(self):
        rule = {"id": "r", "listFile": "g/build.gradle", "groups": [["com/A.java"]]}
        self.assertTrue(tp.rule_is_touched(rule, {"g/build.gradle"}))

    def test_group_members_match_by_path_suffix(self):
        rule = {"id": "r", "listFile": "g/build.gradle", "groups": [["com/finalexec/A.java"]]}
        self.assertTrue(tp.rule_is_touched(rule, {"src/test/java/com/finalexec/A.java"}))
        self.assertFalse(tp.rule_is_touched(rule, {"src/test/java/com/finalexec/AB.java"}))

    def test_no_flag_or_an_empty_change_set_means_run_everything(self):
        self.assertIsNone(tp.changed_set([]))
        self.assertIsNone(tp.changed_set(["--changed"]))

    def test_explicit_changed_paths_are_normalised(self):
        self.assertEqual(tp.changed_set(["--changed", "a\\b.json"]), {"a/b.json"})

    def test_every_real_rule_has_something_to_be_triggered_by(self):
        registry = json.loads((QUALITY / "twin-pair-registry.json").read_text(encoding="utf-8"))
        for rule in registry["rules"]:
            pinned = bool(rule.get("files") or rule.get("listFile"))
            self.assertTrue(pinned, f"{rule['id']} names no file, so a filtered run could never select it")


if __name__ == "__main__":
    unittest.main()
