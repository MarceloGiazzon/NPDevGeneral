"""XREF-3: the rewriting and — more importantly — the refusing.

`npdev migrate rename --cascade` edits a user's model file. The tests that matter most here are not
"did it rewrite correctly" but "did it refuse when it should have, and change nothing when it did".
A rename tool that rewrites the references it can follow and quietly leaves the ones it cannot is
worse than one that does nothing: it looks finished.

The Gradle-backed index build is not exercised (see test_inspect_usage.py for why); these test the
pure functions it feeds.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_rename_cascade -v
"""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_rename_cascade as cascade  # noqa: E402


def _edge(path, site, to_name, owner="Patient", resolution="RESOLVED",
          to_kind="field", from_kind="panel", from_name="PatientsPanel"):
    return {"fromKind": from_kind, "fromName": from_name, "site": site, "path": path,
            "toKind": to_kind, "toName": to_name, "ownerConcept": owner, "resolution": resolution}


MODEL = {
    "concepts": [{"name": "Patient", "fields": [
        {"name": "id", "type": "uuid", "id": True},
        {"name": "birthDay", "type": "date", "ui": {"label": "birthDay"}},
    ]}],
    "queries": [{"name": "ByBirthday", "concept": "Patient",
                 "where": "birthDay != '1900-01-01'", "orderBy": ["birthDay desc"],
                 "groupBy": ["birthDay"]}],
    "panels": [{"name": "PatientsPanel",
                "dataSources": [{"name": "patients", "concept": "Patient"}],
                "layout": {"type": "table", "fields": ["birthDay"]},
                "fieldBindings": [{"field": "birthDay", "source": "patients.birthDay",
                                   "visibleWhen": "birthDay != id"}]}],
}


class ValueRewritingTest(unittest.TestCase):
    """The four ways the old name can appear in a string, and the one way it must NOT be touched."""

    def test_a_plain_value_is_replaced(self) -> None:
        rewrite = cascade._rewriter_for("panel.layout.fields", None)
        self.assertEqual("birthDate", rewrite("birthDay", "birthDay", "birthDate"))

    def test_a_sort_direction_suffix_survives(self) -> None:
        rewrite = cascade._rewriter_for("query.orderBy", None)
        self.assertEqual("birthDate desc", rewrite("birthDay desc", "birthDay", "birthDate"))

    def test_a_dotted_source_keeps_its_data_source_half(self) -> None:
        rewrite = cascade._rewriter_for("panel.fieldBindings.source", None)
        self.assertEqual("patients.birthDate",
                         rewrite("patients.birthDay", "birthDay", "birthDate"))

    def test_an_expression_replaces_only_whole_identifiers(self) -> None:
        rewrite = cascade._rewriter_for("panel.fieldBindings.predicate", "0")
        self.assertEqual("birthDate != birthDayOfWeek",
                         rewrite("birthDay != birthDayOfWeek", "birthDay", "birthDate"),
                         "a longer name that merely starts with the old one must not be touched")

    def test_a_quoted_literal_matching_the_old_name_is_left_alone(self) -> None:
        # `status == 'status'` renaming `status`: the left side is the field, the right side is a
        # VALUE that happens to read the same. A word-boundary replace without quote masking would
        # silently change the data the query matches on.
        rewrite = cascade._rewriter_for("query.where", "0")
        self.assertEqual("statusCode == 'status'",
                         rewrite("status == 'status'", "status", "statusCode"))


class PlanCascadeTest(unittest.TestCase):
    def test_resolved_edges_naming_the_field_are_rewritable(self) -> None:
        edges = [
            _edge("panels[PatientsPanel].layout.fields[0]", "panel.layout.fields", "Patient.birthDay"),
            _edge("queries[ByBirthday].orderBy[0]", "query.orderBy", "Patient.birthDay",
                  from_kind="query", from_name="ByBirthday"),
            _edge("panels[Other].layout.fields[0]", "panel.layout.fields", "Patient.fullName"),
        ]

        rewritable, refusals = cascade.plan_cascade(edges, "Patient", "birthDay")

        self.assertEqual([], refusals)
        self.assertEqual(2, len(rewritable))

    def test_an_undecidable_edge_mentioning_the_old_name_refuses(self) -> None:
        """The core safety property. `$ui.birthDay == 'x'` is legal and unreadable to the index;
        rewriting everything around it would leave one stale reference behind, in a file that now
        looks fully migrated."""
        edges = [
            _edge("panels[PatientsPanel].layout.fields[0]", "panel.layout.fields", "Patient.birthDay"),
            _edge("panels[PatientsPanel].fieldBindings[0].enabledWhen",
                  "panel.fieldBindings.predicate", "$ui.birthDay == 'x'",
                  owner=None, resolution="UNDECIDABLE", to_kind="expression"),
        ]

        rewritable, refusals = cascade.plan_cascade(edges, "Patient", "birthDay")

        self.assertEqual(1, len(refusals), refusals)
        self.assertIn("UNDECIDABLE", refusals[0])
        self.assertIn("birthDay", refusals[0])

    def test_a_pack_contributed_owner_refuses(self) -> None:
        edges = [_edge("panels[labeling::LabelPanel].layout.fields[0]", "panel.layout.fields",
                       "Patient.birthDay", from_name="labeling::LabelPanel")]

        _, refusals = cascade.plan_cascade(edges, "Patient", "birthDay")

        self.assertEqual(1, len(refusals))
        self.assertIn("outside this model root", refusals[0])

    def test_a_same_named_procedure_parameter_is_not_a_field_reference(self) -> None:
        """A procedure input called `birthDay` lives in a different namespace from the concept
        field. Renaming the field must not rename the input."""
        edges = [_edge("panels[P].actions[0].inputFields[0]", "panel.actions.inputFields",
                       "AgeCheck.birthDay", owner=None, to_kind="parameter")]

        rewritable, refusals = cascade.plan_cascade(edges, "Patient", "birthDay")

        self.assertEqual([], refusals)
        self.assertEqual([], rewritable)

    def test_an_already_unresolved_edge_refuses(self) -> None:
        edges = [_edge("queries[Q].orderBy[0]", "query.orderBy", "Patient.birthDay",
                       resolution="UNRESOLVED", from_kind="query", from_name="Q")]

        _, refusals = cascade.plan_cascade(edges, "Patient", "birthDay")

        self.assertEqual(1, len(refusals))
        self.assertIn("already inconsistent", refusals[0])


class TrustedSourceTest(unittest.TestCase):
    def test_a_hash_pinned_panel_refuses(self) -> None:
        model = {"panels": [{"name": "PinnedPanel",
                             "metadata": {"trustedSourceEntrypoint": "assets/pinned.html"}}]}
        edges = [_edge("panels[PinnedPanel].layout.fields[0]", "panel.layout.fields",
                       "Patient.birthDay", from_name="PinnedPanel")]

        refusals = cascade.trusted_source_refusals(model, edges)

        self.assertEqual(1, len(refusals))
        self.assertIn("hash-pinned", refusals[0])


class ApplyCascadeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.model = copy.deepcopy(MODEL)

    def test_every_site_shape_is_rewritten_in_place(self) -> None:
        edges = [
            _edge("panels[PatientsPanel].layout.fields[0]", "panel.layout.fields", "Patient.birthDay"),
            _edge("panels[PatientsPanel].fieldBindings[0].field",
                  "panel.fieldBindings.field", "Patient.birthDay"),
            _edge("panels[PatientsPanel].fieldBindings[0].source",
                  "panel.fieldBindings.source", "Patient.birthDay"),
            _edge("panels[PatientsPanel].fieldBindings[0].visibleWhen#0",
                  "panel.fieldBindings.predicate", "Patient.birthDay"),
            _edge("queries[ByBirthday].orderBy[0]", "query.orderBy", "Patient.birthDay",
                  from_kind="query", from_name="ByBirthday"),
            _edge("queries[ByBirthday].where#0", "query.where", "Patient.birthDay",
                  from_kind="query", from_name="ByBirthday"),
            _edge("queries[ByBirthday].groupBy[0]", "query.groupBy", "Patient.birthDay",
                  from_kind="query", from_name="ByBirthday"),
        ]

        edits = cascade.apply_cascade(self.model, edges, "birthDay", "birthDate")

        self.assertEqual(7, len(edits), edits)
        panel = self.model["panels"][0]
        query = self.model["queries"][0]
        self.assertEqual(["birthDate"], panel["layout"]["fields"])
        self.assertEqual("birthDate", panel["fieldBindings"][0]["field"])
        self.assertEqual("patients.birthDate", panel["fieldBindings"][0]["source"])
        self.assertEqual("birthDate != id", panel["fieldBindings"][0]["visibleWhen"])
        self.assertEqual(["birthDate desc"], query["orderBy"])
        self.assertEqual("birthDate != '1900-01-01'", query["where"])
        self.assertEqual(["birthDate"], query["groupBy"])

    def test_the_fields_own_ui_label_is_not_touched(self) -> None:
        """A label reading "birthDay" is prose, not a reference. This is the difference between
        editing at a known pointer and running a string replace over the file."""
        edges = [_edge("panels[PatientsPanel].layout.fields[0]",
                       "panel.layout.fields", "Patient.birthDay")]

        cascade.apply_cascade(self.model, edges, "birthDay", "birthDate")

        self.assertEqual("birthDay", self.model["concepts"][0]["fields"][1]["ui"]["label"])

    def test_a_groupBy_entry_in_object_form_is_rewritten_through_its_field_key(self) -> None:
        self.model["queries"][0]["groupBy"] = [{"field": "birthDay", "bucket": "month"}]
        edges = [_edge("queries[ByBirthday].groupBy[0]", "query.groupBy", "Patient.birthDay",
                       from_kind="query", from_name="ByBirthday")]

        cascade.apply_cascade(self.model, edges, "birthDay", "birthDate")

        self.assertEqual([{"field": "birthDate", "bucket": "month"}], self.model["queries"][0]["groupBy"])

    def test_several_edges_describing_one_string_rewrite_it_once(self) -> None:
        # A groupBy join's hops and its final field share the entry's path, as do the two halves of
        # a dotted `source`. Rewriting twice would find nothing to change the second time and raise.
        edges = [
            _edge("panels[PatientsPanel].fieldBindings[0].source",
                  "panel.fieldBindings.source", "Patient.birthDay"),
            _edge("panels[PatientsPanel].fieldBindings[0].source",
                  "panel.fieldBindings.source", "Patient.birthDay"),
        ]

        edits = cascade.apply_cascade(self.model, edges, "birthDay", "birthDate")

        self.assertEqual(1, len(edits))
        self.assertEqual("patients.birthDate", self.model["panels"][0]["fieldBindings"][0]["source"])

    def test_a_path_that_is_not_in_this_file_refuses(self) -> None:
        edges = [_edge("panels[SomePackPanel].layout.fields[0]",
                       "panel.layout.fields", "Patient.birthDay")]

        with self.assertRaises(cascade.CascadeRefusal) as caught:
            cascade.apply_cascade(self.model, edges, "birthDay", "birthDate")
        self.assertIn("pack", str(caught.exception))

    def test_a_rewrite_that_changes_nothing_refuses_rather_than_reporting_success(self) -> None:
        """If the index and this rewriter disagree about what a site means, the honest outcome is a
        loud stop -- the alternative is a partial rewrite reported as complete."""
        edges = [_edge("panels[PatientsPanel].layout.fields[0]",
                       "panel.layout.fields", "Patient.somethingElse")]

        with self.assertRaises(cascade.CascadeRefusal) as caught:
            cascade.apply_cascade(self.model, edges, "somethingElse", "renamed")
        self.assertIn("changed nothing", str(caught.exception))


class RemainingReferencesTest(unittest.TestCase):
    def test_it_reports_edges_still_pointing_at_the_old_name(self) -> None:
        edges = [_edge("queries[Q].orderBy[0]", "query.orderBy", "Patient.birthDay",
                       from_kind="query", from_name="Q"),
                 _edge("queries[Q].orderBy[1]", "query.orderBy", "Patient.birthDate",
                       from_kind="query", from_name="Q")]

        left = cascade.remaining_references(edges, "Patient", "birthDay")

        self.assertEqual(1, len(left))


if __name__ == "__main__":
    unittest.main()
