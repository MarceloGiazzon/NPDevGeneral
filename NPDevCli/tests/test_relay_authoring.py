"""The pure parts of the change/fix half (npdev_relay_authoring): version bump, manifest translation, preview.

The gate itself (Gradle) is exercised end to end by test_relay_apply.py.

Run with: python -m unittest NPDevCli.tests.test_relay_authoring -v
"""
from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_relay_authoring as authoring  # noqa: E402

BASE = {"namespace": "t.shop", "version": "1.0", "concepts": [
    {"name": "Customer", "fields": [{"name": "id", "type": "uuid", "id": True}, {"name": "nm", "type": "string"}]}]}
SESSION = {"intent": "change", "ask": "rename nm to name", "baseModelSha256": "a" * 64}


def candidate(**changes):
    model = copy.deepcopy(BASE)
    model.update(changes)
    return model


class VersionTests(unittest.TestCase):
    def test_bump_keeps_the_shape_of_the_version(self):
        for before, after in (("1.0", "1.1"), ("3", "4"), ("2.9", "2.10"), ("0.1.0", "0.1.1"), ("1.0.9", "1.0.10"), (None, "1")):
            with self.subTest(before=before):
                self.assertEqual(authoring.bump_version(before), after)

    def test_compare_orders_numerically_not_alphabetically(self):
        self.assertEqual(authoring.compare_versions("1.10", "1.9"), 1)
        self.assertEqual(authoring.compare_versions("1.0", "1.0"), 0)
        self.assertEqual(authoring.compare_versions("0.9", "1.0"), -1)


class TranslateTests(unittest.TestCase):
    def test_a_version_that_did_not_move_is_bumped_and_said_so(self):
        cand = candidate()
        cand["concepts"][0]["fields"][1] = {"name": "phone", "type": "string"}
        submission, fixed, findings = authoring.translate_manifest(SESSION, BASE, cand, {"request": "x"})
        self.assertEqual(submission["recordKind"], "npdev-authoring-submission.v1")
        self.assertEqual(submission["previousModelSha256"], "a" * 64)
        self.assertEqual(fixed["version"], "1.1")
        self.assertEqual(submission["submittedModelVersion"], "1.1")
        self.assertIn("version-bumped", [f["code"] for f in findings])

    def test_a_removal_without_a_reason_gets_the_requests_own_words(self):
        cand = candidate()
        cand["concepts"][0]["fields"] = [cand["concepts"][0]["fields"][0]]
        ai = {"request": "drop it", "deliberateRemovals": [{"kind": "field", "concept": "Customer", "name": "nm"}]}
        submission, _, _ = authoring.translate_manifest(SESSION, BASE, cand, ai)
        self.assertEqual(len(submission["deliberateRemovals"]), 1)
        self.assertIn("requested", submission["deliberateRemovals"][0]["reason"])

    def test_the_ais_security_sentences_never_become_manifest_entries(self):
        ai = {"request": "x", "securityChanges": [{"kind": "access", "concept": "Customer", "rationale": "trust me"}]}
        submission, _, _ = authoring.translate_manifest(SESSION, BASE, candidate(version="2.0"), ai)
        self.assertEqual(submission["securityChanges"], [])

    def test_a_string_could_not_express_becomes_a_requirement(self):
        ai = {"request": "x", "couldNotExpress": ["a Kanban board"]}
        submission, _, _ = authoring.translate_manifest(SESSION, BASE, candidate(version="2.0"), ai)
        self.assertEqual(submission["couldNotExpress"][0]["requirement"], "a Kanban board")

    def test_without_a_base_hash_there_is_nothing_to_check_against(self):
        with self.assertRaises(ValueError):
            authoring.translate_manifest({"intent": "change", "ask": "x"}, BASE, candidate(version="2.0"), None)


class PreviewTests(unittest.TestCase):
    def test_a_new_field_is_one_plain_sentence_and_needs_no_confirmation(self):
        cand = candidate(version="1.1")
        cand["concepts"][0]["fields"].append({"name": "phone", "type": "string"})
        preview = authoring.build_preview({"status": "passed", "diagnostics": []}, BASE, cand, {}, "change")
        self.assertEqual(preview["warnings"], [])
        self.assertFalse(preview["dataLoss"])
        self.assertTrue(any("phone" in sentence for sentence in preview["sentences"]))
        for sentence in preview["sentences"]:  # the audience is not technical: no JSON punctuation, no paths
            self.assertNotRegex(sentence, r"[{}\[\]$]|\.json")

    def test_a_removal_is_a_warning_that_needs_the_owners_yes(self):
        cand = candidate(version="1.1")
        cand["concepts"][0]["fields"] = [cand["concepts"][0]["fields"][0]]
        ai = {"deliberateRemovals": [{"kind": "field", "concept": "Customer", "name": "nm"}]}
        preview = authoring.build_preview({"status": "passed", "diagnostics": []}, BASE, cand, ai, "change")
        self.assertTrue(preview["dataLoss"])
        self.assertEqual([w["code"] for w in preview["warnings"] if w["needsConfirmation"]], ["REMOVAL:field:Customer.nm"])

    def test_a_create_preview_says_nothing_is_deleted(self):
        preview = authoring.build_create_preview(copy.deepcopy(BASE), None)
        self.assertIn("Nothing is deleted", " ".join(preview["sentences"]))
        self.assertEqual(preview["warnings"], [])
        json.dumps(preview)  # serialisable as is


if __name__ == "__main__":
    unittest.main()
