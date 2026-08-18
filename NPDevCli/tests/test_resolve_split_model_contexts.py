"""REG-186: `resolve_split_model` and bounded contexts / pack imports.

Two separate defects, both measured 2026-08-17 against `NPDevSamples/dsl-conformance-max`:

  1. HARD FAILURE. `contexts` sits in `MODEL_ARRAY_KEYS`, so `resolve_array` ran `ref_value` on
     each entry -- and `ref_value` demanded the object carry EXACTLY the one key `$ref`, while a
     context entry is `{"name", "$ref", "physicallyIsolate"}`. Every model declaring a bounded
     context was therefore unreadable, with a message that blamed the model:

         npdev: .../model.json/contexts/0: $ref object must be exactly { "$ref": "relative/path.json" }

     Three CLI surfaces failed on it, not the one the plan measured: `inspect app`, `inspect bonds`
     AND `validate model --structural-only`.

  2. SILENT LOSS. Pack-contributed members were never composed at all, so `inspect app` reported
     11 concepts for a model that has 17 and said nothing about the six it could not see.

The fix mirrors the Java resolver (`ModelSourceResolver`) rather than inventing a second rule --
see `npdev_cli.QUALIFIER_SEPARATOR`'s `npdev-qualifier-rule` twin-pair note for what pins the two
copies together. These tests lock in the mirrored semantics, INCLUDING the refusals: a bare
reference that two contributions could satisfy is left unresolved rather than guessed at, and a
remote `packs[].from` coordinate is reported as uncomposed rather than silently contributing zero.

Stdlib-only (unittest), matching this repo's convention for CLI-adjacent tests. Run with:
    python -m unittest NPDevCli.tests.test_resolve_split_model_contexts -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def _concept(name: str, *, reference_target: str | None = None) -> dict:
    fields: list[dict] = [{"name": "id", "type": "uuid", "id": True, "required": True}]
    if reference_target is not None:
        fields.append({
            "name": "link",
            "type": "reference",
            "reference": {"target": reference_target, "via": "id"},
        })
    return {"name": name, "truthLevel": "T2", "fields": fields}


class ResolveSplitModelContextsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-reg186-")
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def _model(self, **extra) -> Path:
        model = {
            "dslVersion": "1.0.0",
            "namespace": "reg186.sample",
            "version": "1.0.0",
            "concepts": [_concept("HomeGrown")],
        }
        model.update(extra)
        path = self.root / "model.json"
        _write(path, model)
        return path

    # -- 1. the hard failure ---------------------------------------------------------------------

    def test_context_entry_with_name_and_physically_isolate_is_accepted(self) -> None:
        """The RED: `{name, $ref, physicallyIsolate}` was rejected as a malformed `$ref` object."""
        _write(self.root / "contexts" / "billing.json", {"concepts": [_concept("Invoice")]})
        model = self._model(contexts=[
            {"name": "billing", "$ref": "contexts/billing.json", "physicallyIsolate": True},
        ])

        resolved = npdev_cli.resolve_split_model(model)

        self.assertIn("billing::Invoice", [c["name"] for c in resolved["concepts"]])

    def test_context_registry_is_preserved_and_physically_isolate_only_when_true(self) -> None:
        """Mirrors ModelSourceResolver: the {name, $ref} registry survives composition, and
        `physicallyIsolate` is emitted ONLY when true so a model that never declares it resolves
        byte-identically to before bounded contexts existed."""
        _write(self.root / "contexts" / "a.json", {"concepts": [_concept("Alpha")]})
        _write(self.root / "contexts" / "b.json", {"concepts": [_concept("Beta")]})
        model = self._model(contexts=[
            {"name": "plain", "$ref": "contexts/a.json"},
            {"name": "isolated", "$ref": "contexts/b.json", "physicallyIsolate": True},
        ])

        resolved = npdev_cli.resolve_split_model(model)

        self.assertEqual(
            [
                {"name": "plain", "$ref": "contexts/a.json"},
                {"name": "isolated", "$ref": "contexts/b.json", "physicallyIsolate": True},
            ],
            resolved["contexts"],
        )

    def test_unknown_sibling_key_on_a_context_entry_is_still_an_error(self) -> None:
        """The shape table is a per-key allowance, not a blanket relaxation -- a typo'd sibling key
        must still be caught, or the fix would trade a false error for a silent one."""
        _write(self.root / "contexts" / "a.json", {"concepts": [_concept("Alpha")]})
        model = self._model(contexts=[
            {"name": "a", "$ref": "contexts/a.json", "physicalyIsolate": True},  # typo, one 'l'
        ])

        with self.assertRaises(npdev_cli.CliError) as caught:
            npdev_cli.resolve_split_model(model)
        self.assertIn("$ref object must be exactly", str(caught.exception))

    # -- 2. the silent loss ----------------------------------------------------------------------

    def test_pack_members_are_composed_under_the_alias(self) -> None:
        _write(self.root / "packs" / "labeling" / "pack.json", {
            "pack": "labeling",
            "version": "1.0.0",
            "concepts": [_concept("Label")],
        })
        model = self._model(packs=[{"$ref": "packs/labeling/pack.json", "as": "tagging"}])

        resolved = npdev_cli.resolve_split_model(model)

        names = [c["name"] for c in resolved["concepts"]]
        self.assertIn("tagging::Label", names, "the `as` alias must win over the pack's own id")
        self.assertNotIn("labeling::Label", names)
        self.assertEqual([{"$ref": "packs/labeling/pack.json", "as": "tagging"}], resolved["packs"])

    def test_pack_without_alias_qualifies_by_its_declared_pack_id(self) -> None:
        _write(self.root / "packs" / "labeling" / "pack.json", {
            "pack": "labeling",
            "version": "1.0.0",
            "concepts": [_concept("Label")],
        })
        model = self._model(packs=[{"$ref": "packs/labeling/pack.json"}])

        resolved = npdev_cli.resolve_split_model(model)

        self.assertIn("labeling::Label", [c["name"] for c in resolved["concepts"]])

    def test_every_member_kind_is_composed_not_just_concepts(self) -> None:
        """The Java rewrite map walks all MODEL_ARRAY_KEYS kinds. A copy that only handled
        `concepts` would look right on the sample corpus and drop a pack's queries and panels."""
        _write(self.root / "packs" / "ops" / "pack.json", {
            "pack": "ops",
            "version": "1.0.0",
            "concepts": [_concept("Job")],
            "queries": [{"name": "OpenJobs", "concept": "Job"}],
            "panels": [{"name": "JobsPanel"}],
        })
        model = self._model(packs=[{"$ref": "packs/ops/pack.json"}])

        resolved = npdev_cli.resolve_split_model(model)

        self.assertIn("ops::Job", [c["name"] for c in resolved["concepts"]])
        self.assertIn("ops::OpenJobs", [q["name"] for q in resolved["queries"]])
        self.assertIn("ops::JobsPanel", [p["name"] for p in resolved["panels"]])

    def test_remote_from_pack_is_reported_uncomposed_never_silently_skipped(self) -> None:
        """A `from` coordinate resolves only out of the lockfile-backed cache at generate time.
        The build-free resolver cannot expand it -- so it says so, and does not fail either."""
        model = self._model(packs=[
            {"from": "git+https://example.invalid/NPR.git//packs/user@v1.0.0", "as": "user"},
        ])

        uncomposed: list[str] = []
        resolved = npdev_cli.resolve_split_model(model, collect_uncomposed=uncomposed)

        self.assertEqual(["git+https://example.invalid/NPR.git//packs/user@v1.0.0"], uncomposed)
        self.assertEqual(
            [{"from": "git+https://example.invalid/NPR.git//packs/user@v1.0.0", "as": "user"}],
            resolved["packs"],
            "the declaration is passed through untouched, not dropped",
        )

    def test_duplicate_qualifier_is_refused(self) -> None:
        _write(self.root / "contexts" / "a.json", {"concepts": [_concept("Alpha")]})
        _write(self.root / "contexts" / "b.json", {"concepts": [_concept("Beta")]})
        model = self._model(contexts=[
            {"name": "same", "$ref": "contexts/a.json"},
            {"name": "same", "$ref": "contexts/b.json"},
        ])

        with self.assertRaises(npdev_cli.CliError) as caught:
            npdev_cli.resolve_split_model(model)
        self.assertIn("duplicate context qualifier", str(caught.exception))


class ResolveMemberReferenceTest(unittest.TestCase):
    """`npdev-qualifier-rule`: how a possibly-bare reference finds its composed member. Mirrors
    ModelSourceResolver.resolveUnqualifiedReferences, refusals included."""

    KNOWN = ["HomeGrown", "shipping::Shipment", "shipping::DeliveryAttempt", "billing::Invoice"]

    def test_exact_match_wins_outright(self) -> None:
        self.assertEqual(
            "billing::Invoice",
            npdev_cli.resolve_member_reference("billing::Invoice", self.KNOWN, "shipping::Shipment"),
        )

    def test_bare_reference_resolves_inside_its_own_contribution_first(self) -> None:
        """The case that made REG-186's first composed `inspect bonds` run report a real bond as
        anchorless: `shipping::DeliveryAttempt.shipment -> Shipment` means `shipping::Shipment`."""
        self.assertEqual(
            "shipping::Shipment",
            npdev_cli.resolve_member_reference("Shipment", self.KNOWN, "shipping::DeliveryAttempt"),
        )

    def test_bare_reference_resolves_when_exactly_one_contribution_offers_it(self) -> None:
        self.assertEqual(
            "billing::Invoice",
            npdev_cli.resolve_member_reference("Invoice", self.KNOWN, "HomeGrown"),
        )

    def test_ambiguous_bare_reference_is_left_unresolved_never_guessed(self) -> None:
        known = [*self.KNOWN, "isolated-a::Ledger", "isolated-b::Ledger"]
        self.assertIsNone(npdev_cli.resolve_member_reference("Ledger", known, "HomeGrown"))

    def test_unknown_qualified_reference_does_not_fall_back_to_a_bare_match(self) -> None:
        self.assertIsNone(
            npdev_cli.resolve_member_reference("nosuch::Invoice", self.KNOWN, "HomeGrown")
        )


if __name__ == "__main__":
    unittest.main()
