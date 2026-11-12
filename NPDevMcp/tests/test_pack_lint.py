"""Tests for NPDevMcp/pack_lint.py -- the static lint the validation service appends to every report.

Each case is a defect that really shipped in a browser-AI pack (wave 1, 2026-11-12).
Run with:  python -m unittest NPDevMcp.tests.test_pack_lint -v
Portable-tooling note: no drive-letter-looking literal in this file.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pack_lint  # noqa: E402
import validation_service as vs  # noqa: E402
from test_validation_service import FakeValidator, _Served, _model, _service  # noqa: E402

REP = "$user.roles.contains('ADMIN') || $user.roles.contains(role('salesManager'))"


def _field(name, type_="string", **extra):
    return {"name": name, "type": type_, **extra}


def _codes(model):
    return [d["code"] for d in pack_lint.lint_model(model)]


class OwnerWriteTest(unittest.TestCase):
    def _lead(self, write):
        return {"concepts": [{"name": "Lead", "fields": [_field("ownerId", defaultExpression="$user.id")],
                              "access": {"read": REP, "write": write}}]}

    def test_write_rule_without_blank_owner_is_an_error(self):
        model = self._lead(REP + " || $user.roles.contains(role('salesRep')) && ownerId == $user.id")
        found = pack_lint.lint_model(model)
        self.assertEqual(["LINT_OWNER_WRITE_NO_BLANK"], [d["code"] for d in found])
        self.assertEqual("error", found[0]["severity"])
        self.assertEqual("/concepts/Lead/access/write", found[0]["path"])
        self.assertIn("ownerId == null", found[0]["suggestedFix"])

    def test_write_rule_admitting_a_blank_owner_is_clean(self):
        write = REP + " || role('r') && ownerId == $user.id || role('r') && ownerId == null"
        self.assertEqual([], _codes(self._lead(write)))

    def test_one_diagnostic_per_owner_field(self):
        write = "role('a') && ownerId == $user.id || role('b') && ownerId == $user.id || role('c') && agentId == $user.id"
        self.assertEqual(2, _codes(self._lead(write)).count("LINT_OWNER_WRITE_NO_BLANK"))


class ChildTest(unittest.TestCase):
    def _model(self, child_read):
        return {"concepts": [
            {"name": "Document", "fields": [_field("confidential", "boolean")],
             "access": {"read": REP + " || role('viewer') && confidential == false"}},
            {"name": "Version", "fields": [_field("documentId", "reference", reference={"target": "Document"})],
             "access": {"read": child_read}}]}

    def test_child_without_row_restriction_is_flagged(self):
        found = pack_lint.lint_model(self._model(REP + " || role('viewer')"))
        self.assertEqual(["LINT_CHILD_LESS_RESTRICTED"], [d["code"] for d in found])
        self.assertEqual("warning", found[0]["severity"])
        self.assertEqual("/concepts/Version/access/read", found[0]["path"])

    def test_child_with_its_own_row_restriction_is_clean(self):
        self.assertEqual([], _codes(self._model(REP + " || role('viewer') && confidential == false")))

    def test_unrestricted_parent_does_not_demand_anything_of_children(self):
        model = self._model("x")
        model["concepts"][0]["access"]["read"] = REP
        self.assertEqual([], _codes(model))


class DerivedTest(unittest.TestCase):
    def _line(self, discount):
        return {"concepts": [{"name": "Line", "fields": [
            _field("quantity", "decimal", required=True), _field("unitPrice", "decimal", required=True), discount,
            _field("lineTotal", "decimal", derivedExpression="quantity * unitPrice - discountAmount")]}]}

    def test_optional_number_in_a_derived_expression_is_flagged(self):
        found = pack_lint.lint_model(self._line(_field("discountAmount", "decimal")))
        self.assertEqual(["LINT_DERIVED_OPTIONAL"], [d["code"] for d in found])
        self.assertIn("discountAmount", found[0]["message"])

    def test_required_or_defaulted_inputs_are_clean(self):
        self.assertEqual([], _codes(self._line(_field("discountAmount", "decimal", required=True))))
        self.assertEqual([], _codes(self._line(_field("discountAmount", "decimal", default=0))))


class PromiseAndLanguageTest(unittest.TestCase):
    def test_summary_promising_isolation_is_flagged_once(self):
        model = {"metadata": {"summary": "Several companies. Data kept separate per company. Audit is append-only."}}
        self.assertEqual(["LINT_UNENFORCED_PROMISE"], _codes(model))

    def test_plain_summary_is_clean(self):
        self.assertEqual([], _codes({"metadata": {"summary": "Companies, branches and who belongs to which."}}))

    def test_non_ascii_text_is_flagged_with_its_path(self):
        model = {"metadata": {"reviewNotes": ["fine", "Carta de Correção"]}}
        found = pack_lint.lint_model(model)
        self.assertEqual(["LINT_NON_ENGLISH"], [d["code"] for d in found])
        self.assertEqual("/metadata/reviewNotes/1", found[0]["path"])


class RobustnessAndMergeTest(unittest.TestCase):
    def test_odd_shapes_never_raise(self):
        for odd in (None, [], "x", {"concepts": "no"}, {"concepts": [1, {"name": 3}, {"name": "A", "fields": "x", "access": 5}]},
                    {"metadata": "x"}):
            self.assertEqual([], pack_lint.lint_model(odd))

    def test_merge_recomputes_summary_and_status(self):
        report = {"status": "passed", "summary": {"errors": 0, "warnings": 0}, "diagnostics": []}
        err = pack_lint._diag("error", "E", "/", "m", "f")
        warn = pack_lint._diag("warning", "W", "/", "m", "f")
        self.assertEqual("failed", pack_lint.merge_into_report(report, [warn, err])["status"])
        merged = pack_lint.merge_into_report(report, [warn])
        self.assertEqual(("warning", {"errors": 0, "warnings": 1}), (merged["status"], merged["summary"]))
        self.assertEqual(0, len(report["diagnostics"]))  # input untouched
        self.assertIs(report, pack_lint.merge_into_report(report, []))


class ServiceIntegrationTest(unittest.TestCase):
    def test_service_reports_lint_even_when_the_validator_says_passed(self):
        model = _model()
        model["concepts"] = [{"name": "Lead", "fields": [_field("ownerId")],
                              "access": {"write": "role('r') && ownerId == $user.id"}}]
        with _Served(_service(FakeValidator([]))) as srv:
            status, _, body = srv.post_model(model)
        self.assertEqual(200, status)
        self.assertEqual("failed", body["status"])
        self.assertEqual(1, body["summary"]["errors"])
        self.assertEqual("LINT_OWNER_WRITE_NO_BLANK", body["diagnostics"][0]["code"])

    def test_clean_model_is_untouched(self):
        with _Served(_service(FakeValidator([]))) as srv:
            _, _, body = srv.post_model(_model())
        self.assertEqual("passed", body["status"])
        self.assertEqual([], body["diagnostics"])


if __name__ == "__main__":
    unittest.main()
