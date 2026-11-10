"""The pure parts of the change/fix half (npdev_relay_authoring): version bump, manifest translation, preview.

The gate itself (Gradle) is exercised end to end by test_relay_apply.py.

Run with: python -m unittest NPDevCli.tests.test_relay_authoring -v
"""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_relay_authoring as authoring  # noqa: E402
import npdev_relay_preview_pt as preview_pt  # noqa: E402

BASE = {"namespace": "t.shop", "version": "1.0", "concepts": [
    {"name": "Customer", "fields": [{"name": "id", "type": "uuid", "id": True}, {"name": "nm", "type": "string"}]}]}
SESSION = {"intent": "change", "ask": "rename nm to name", "baseModelSha256": "a" * 64}


def candidate(**changes):
    model = copy.deepcopy(BASE)
    model.update(changes)
    return model


class ResolveModelPathTests(unittest.TestCase):
    """Where an app's model.json is, from the folder the Manager hands over. Start, check, apply and undo all ask
    this one function; when they disagreed, a change to a Manager-made app was refused as 'base-moved'."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def put(self, *parts):
        path = self.dir.joinpath(*parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{}", encoding="utf-8")
        return path

    def test_the_model_folder_itself(self):
        model = self.put("shop", "model.json")
        self.assertEqual(authoring.resolve_model_path(str(self.dir / "shop")), model)

    def test_the_generated_folder_finds_the_model_beside_it(self):
        model = self.put("shop", "model.json")
        (self.dir / "shop-app").mkdir()
        self.assertEqual(authoring.resolve_model_path(str(self.dir / "shop-app")), model)
        self.assertEqual(authoring.resolve_model_path(str(self.dir / "shop-app") + "\\"), model)

    def test_a_model_inside_the_generated_folder_wins_over_the_one_beside_it(self):
        self.put("shop", "model.json")
        inside = self.put("shop-app", "model.json")
        self.assertEqual(authoring.resolve_model_path(str(self.dir / "shop-app")), inside)

    def test_the_corpus_layouts_and_the_appgen_input_beside_the_output(self):
        corpus = self.put("one", "Input", "model.json")
        self.assertEqual(authoring.resolve_model_path(str(self.dir / "one")), corpus)
        appgen = self.put("two", "Input", "model.json")
        (self.dir / "two" / "App").mkdir()
        self.assertEqual(authoring.resolve_model_path(str(self.dir / "two" / "App")), appgen)

    def test_nothing_found_is_none(self):
        (self.dir / "empty-app").mkdir()
        self.assertIsNone(authoring.resolve_model_path(str(self.dir / "empty-app")))
        self.assertIsNone(authoring.resolve_model_path(str(self.dir / "-app")))
        self.assertIsNone(authoring.resolve_model_path(None))
        self.assertIsNone(authoring.resolve_model_path(""))


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


class PortuguesePreviewTests(unittest.TestCase):
    """The same preview in Portuguese: written from the CLI's own codes and params, never by guessing at English."""

    def gate(self):
        return {"status": "passed", "diagnostics": []}

    def test_a_new_app_is_described_in_portuguese(self):
        model = copy.deepcopy(BASE)
        model["concepts"].append({"name": "Order", "fields": [{"name": "id", "type": "uuid", "id": True}]})
        model["flows"] = [{"name": "PlaceOrder"}, {"name": "CancelOrder"}]
        english = authoring.build_create_preview(model, {"assumptions": ["Status has two values"]})
        portuguese = preview_pt.localize_preview(english, "Portuguese (Brazil)")
        self.assertEqual(portuguese["sentences"][0], "Um app novo com 2 tipos de registro, 3 campos e 2 processos.")
        self.assertIn("Customer: 2 campos (id e nm).", portuguese["sentences"])
        self.assertIn("Order: 1 campo (id).", portuguese["sentences"])
        self.assertIn("Nada é apagado: este é um app novo.", portuguese["sentences"])
        self.assertIn("A IA presumiu: Status has two values", portuguese["sentences"])
        self.assertEqual(portuguese["lines"][0]["code"], "CREATE_SUMMARY")  # the codes are what Apply keys off
        self.assertEqual(english["sentences"][0], "A new app with 2 kinds of records, 3 fields and 2 processes.")

    def test_a_change_names_what_is_added_in_portuguese_with_the_right_gender(self):
        cand = candidate(version="1.1")
        cand["concepts"][0]["fields"].append({"name": "phone", "type": "string"})
        cand["concepts"].append({"name": "Invoice", "fields": [{"name": "id", "type": "uuid", "id": True}]})
        cand["flows"] = [{"name": "Charge"}]
        cand["queries"] = [{"name": "FindOverdue"}]
        english = authoring.build_preview(self.gate(), BASE, cand, {}, "change")
        pt = preview_pt.localize_preview(english, "pt-BR")["sentences"]
        self.assertIn("Novo tipo de registro: Invoice, com 1 campo.", pt)
        self.assertIn("Customer: novo campo phone.", pt)
        self.assertIn("Novo processo: Charge.", pt)
        self.assertIn("Nova busca: FindOverdue.", pt)
        for sentence in pt:
            self.assertNotRegex(sentence, r"[{}\[\]$]|\.json")

    def test_a_removal_and_a_rename_keep_their_codes_and_say_it_in_portuguese(self):
        cand = candidate(version="1.1")
        cand["concepts"][0]["fields"] = [cand["concepts"][0]["fields"][0]]
        ai = {"deliberateRemovals": [{"kind": "field", "concept": "Customer", "name": "nm"}]}
        english = authoring.build_preview(self.gate(), BASE, cand, ai, "change")
        pt = preview_pt.localize_preview(english, "Portuguese (Brazil)")
        warning = pt["warnings"][0]
        self.assertEqual(warning["code"], "REMOVAL:field:Customer.nm")
        self.assertTrue(warning["needsConfirmation"])
        self.assertEqual(warning["text"], "Isto remove o campo nm de Customer e os dados guardados nele.")
        self.assertTrue(pt["dataLoss"])

    def test_a_changed_field_names_what_changed_in_portuguese(self):
        cand = candidate(version="1.1")
        cand["concepts"][0]["fields"][1] = {"name": "nm", "type": "text", "required": True}
        english = authoring.build_preview(self.gate(), BASE, cand, {}, "change")
        pt = preview_pt.localize_preview(english, "Portuguese (Brazil)")["sentences"]
        self.assertIn("Customer: o campo nm muda (se é obrigatório e o tipo).", pt)

    def test_other_languages_and_unknown_lines_are_left_alone(self):
        english = authoring.build_create_preview(copy.deepcopy(BASE), None)
        self.assertIs(preview_pt.localize_preview(english, "English"), english)
        self.assertIs(preview_pt.localize_preview(english, None), english)
        broken = copy.deepcopy(english)
        broken["lines"][0]["params"] = {}  # a line this module cannot write keeps its English words
        kept = preview_pt.localize_preview(broken, "Portuguese (Brazil)")
        self.assertEqual(kept["sentences"][0], english["sentences"][0])
        self.assertEqual(kept["sentences"][-1], "Nada é apagado: este é um app novo.")

    def test_every_english_template_has_a_portuguese_one_and_the_same_placeholders(self):
        import string
        names = lambda text: {f for _, f, _, _ in string.Formatter().parse(text) if f}  # noqa: E731
        for code, english in authoring.PREVIEW_TEMPLATES.items():
            with self.subTest(code=code):
                self.assertIn(code, preview_pt.TEMPLATES_PT)
                if code in ("ADD_ITEM", "CHANGE_ITEM", "ADD_FIELDS"):
                    continue  # gendered / pluralised words are chosen in code, not by placeholder
                self.assertEqual(names(preview_pt.TEMPLATES_PT[code]), names(english))
        for key in authoring.ITEM_LABELS:
            self.assertIn(key, preview_pt.ITEM_PT)


if __name__ == "__main__":
    unittest.main()
