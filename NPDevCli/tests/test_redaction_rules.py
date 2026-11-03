"""SUP-4: the shared redaction rules, run against the SAME vector file the Rust redactor reads.

``NPDevManager/src/redact.rs`` loads ``NPDevCli/redaction-test-vectors.json`` through ``include_str!``
and this test loads the very same file -- never a copy -- so the two engines must agree on every
vector. Also pins that the Monitor's ``redact()`` is the same definition and only ever gets stricter.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_redaction_rules -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_monitor  # noqa: E402
import npdev_redact  # noqa: E402


class SharedVectorsTest(unittest.TestCase):
    def test_every_vector_redacts_as_expected(self):
        data = json.loads(npdev_redact.VECTORS_PATH.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(data["vectors"]), 26, "the shared vector file shrank")
        bad = []
        for v in data["vectors"]:
            got, _ = npdev_redact.redact_text(v["input"], v["knownSecrets"])
            if got != v["expected"]:
                bad.append(f"{v['name']}: expected {v['expected']!r}, got {got!r}")
        self.assertEqual([], bad)

    def test_every_rule_has_a_vector(self):
        """A rule nobody can see firing is a rule nobody notices breaking."""
        rules = json.loads(npdev_redact.RULES_PATH.read_text(encoding="utf-8"))["rules"]
        vectors = json.loads(npdev_redact.VECTORS_PATH.read_text(encoding="utf-8"))["vectors"]
        fired = set()
        for v in vectors:
            fired |= set(npdev_redact.redact_text(v["input"], v["knownSecrets"])[1])
        missing = [r["id"] for r in rules if r["id"] not in fired]
        self.assertEqual([], missing, "rules with no test vector that exercises them")

    def test_a_non_portable_rule_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "r.json"
            p.write_text(
                json.dumps(
                    {
                        "secretKeyNamePattern": "x",
                        "rules": [{"id": "bad", "class": "secret", "mask": "m", "pattern": "(?P<secret>a)(?=b)"}],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                npdev_redact.load_rules(p)


class MonitorRedactIsTheSameDefinitionTest(unittest.TestCase):
    def test_key_name_pattern_is_the_shared_one(self):
        self.assertIs(npdev_monitor._SECRET_KEYS, npdev_redact.secret_key_name())

    def test_old_behaviour_is_kept(self):
        out = npdev_monitor.redact({"password": "hunter22", "url": "jdbc:x://h/db?user=a&password=zzz", "passCount": 3})
        self.assertEqual("<redacted>", out["password"])
        self.assertIn("password=<redacted>", out["url"])
        self.assertEqual(3, out["passCount"])  # `passCount` is a count, not a credential

    def test_it_is_now_stricter_on_vendor_keys_inside_values(self):
        out = npdev_monitor.redact({"note": "key gsk_abcdefghijklmnopqrstuvwxyz was used"})
        self.assertNotIn("gsk_abcdef", out["note"])

    def test_paths_and_emails_are_left_alone_for_the_ui(self):
        value = {"appDir": r"C:\Users\Marcelo\apps\bakery", "owner": "me@example.com"}
        self.assertEqual(value, npdev_monitor.redact(value))


if __name__ == "__main__":
    unittest.main()
