"""Temporary trust for unsigned packs from the owner's NPR (pack-trust-defaults.json).

Run with: python -m unittest NPDevCli.tests.test_pack_trust_defaults -v
"""
from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

NPR = "git+https://github.com/MarceloGiazzon/NPR.git//packs/contacts@contacts-1.0.0"
OTHER = "git+https://github.com/someone/else.git//packs/contacts@contacts-1.0.0"
RULES = [{"id": "own-npr", "match": "git+https://github.com/MarceloGiazzon/NPR", "until": "2027-02-28"}]


def _entry(source: str) -> dict:
    return {"from": source, "digest": "sha256:abc", "resolvedVersion": "1.0.0"}


def _classify(source, mode="warn", allow=False, today="2026-12-01", sig=None):
    trust = {"mode": mode, "trustedKeys": {}}
    out = io.StringIO()
    with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=sig), \
            mock.patch.object(npdev_cli, "_load_pack_trust_defaults", return_value=RULES), \
            contextlib.redirect_stdout(out):
        result = npdev_cli._classify_pack_signature("contacts", _entry(source), trust, allow, today=today)
    return result, out.getvalue()


class TrustDefaultsTest(unittest.TestCase):
    def test_accepted_by_rule_and_recorded(self):
        result, printed = _classify(NPR)
        self.assertEqual(result["status"], "UNSIGNED_ACCEPTED")
        self.assertEqual(result["signatureField"]["acceptedBy"], "trust-defaults:own-npr")
        self.assertIn("temporary trust rule own-npr (until 2027-02-28)", printed)

    def test_expired_rule_refused_and_says_so(self):
        result, _ = _classify(NPR, today="2027-03-01")
        self.assertEqual(result["status"], "UNSIGNED_REFUSED")
        self.assertIn("ended on 2027-02-28", result["message"])

    def test_other_source_refused(self):
        result, _ = _classify(OTHER)
        self.assertEqual(result["status"], "UNSIGNED_REFUSED")
        self.assertNotIn("ended on", result["message"])

    def test_enforce_mode_still_refuses(self):
        result, _ = _classify(NPR, mode="enforce")
        self.assertEqual(result["status"], "UNSIGNED_REFUSED")

    def test_explicit_flag_has_no_acceptedBy(self):
        result, _ = _classify(OTHER, allow=True)
        self.assertEqual(result["status"], "UNSIGNED_ACCEPTED")
        self.assertNotIn("acceptedBy", result["signatureField"])

    def test_signed_pack_unknown_signer_unchanged(self):
        sig = {"keyId": "k1", "signature": "00"}
        result, _ = _classify(NPR, sig=sig)
        self.assertEqual(result["status"], "UNKNOWN_SIGNER")

    def test_shipped_defaults_file_loads(self):
        rules = npdev_cli._load_pack_trust_defaults()
        self.assertTrue(any(r["match"].startswith("git+https://github.com/MarceloGiazzon/NPR") for r in rules))


if __name__ == "__main__":
    unittest.main()
