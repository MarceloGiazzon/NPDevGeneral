"""Tests for `npdev add concept|panel|flow|procedure` (R1.5).

Before R1.5, `npdev init` scaffolded a whole app and nothing scaffolded a single member --
growing an existing model.json meant hand-editing it against the 4x-mirrored schema with no help
until `npdev validate model` failed. `npdev add <kind> <NAME>` writes one schema-valid member into
the correct top-level array, either a minimal self-contained default stub or (`--from`) a real
member copied out of NPDevSamples/ and renamed, refusing (naming the offender) rather than
silently overwriting or writing something with a dangling reference.

Two tiers, matching this repo's PackExportUnitTest/PackExportRoundTripTest convention
(test_pack_export.py):
  - AddMemberUnitTest: fast, calls run_add_member directly (no subprocess) -- refusal behaviour,
    stub shape, --from wiring.
  - AddMemberRoundTripTest: slower (a couple seconds), real end-to-end through
    `npdev validate model` (the R1.1 warm ai-tools-jar path when staged, else Gradle), proving
    R1.5's own Done-When ("passes validation with zero errors on first try") by measurement for
    all four supported kinds in one throwaway model, not by assertion.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_add_member -v
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write_model(path: Path, content: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content), encoding="utf-8")


def _add_args(name: str, model: Path, concept: str | None = None,
              from_exemplar: str | None = None) -> argparse.Namespace:
    return argparse.Namespace(name=name, model=str(model), concept=concept, from_exemplar=from_exemplar)


def _bare_model(tmp_dir: Path) -> Path:
    """The exact shape NPDevSamples/npdev-init-seed/model.json (`npdev init`'s own default seed)
    has -- no capabilities[] declared, so this also proves the default flow stub's createConcept
    step needs none (FlowValidation.BUILTIN_CAPABILITY_OPERATIONS seeds "persistence"/"save" for
    every model whether or not capabilities[] is declared)."""
    model_path = tmp_dir / "model.json"
    _write_model(model_path, {
        "namespace": "test.add.member", "dslVersion": "1.0.0", "version": "1.0",
        "concepts": [
            {
                "name": "Widget",
                "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "name", "type": "string", "required": True},
                ],
            },
        ],
    })
    return model_path


class AddMemberUnitTest(unittest.TestCase):
    def test_default_concept_stub_has_exactly_one_id_field(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_add_member(_add_args("Order", model_path), "concept")
            self.assertEqual(0, code)
            report = json.loads(captured.getvalue())
            self.assertEqual("concepts", report["arrayKey"])

            model = json.loads(model_path.read_text(encoding="utf-8"))
            names = [c["name"] for c in model["concepts"]]
            self.assertIn("Order", names)
            order = next(c for c in model["concepts"] if c["name"] == "Order")
            id_fields = [f for f in order["fields"] if f.get("id") is True]
            self.assertEqual(1, len(id_fields), "ConceptValidation requires exactly one id field")

    def test_default_stubs_for_panel_flow_procedure_bind_to_required_concept(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            for kind, name in (("panel", "WidgetPanel"), ("flow", "CreateWidget"), ("procedure", "GetWidget")):
                captured = io.StringIO()
                with redirect_stdout(captured):
                    code = npdev_cli.run_add_member(_add_args(name, model_path, concept="Widget"), kind)
                self.assertEqual(0, code)
            model = json.loads(model_path.read_text(encoding="utf-8"))
            self.assertEqual(["WidgetPanel"], [p["name"] for p in model["panels"]])
            self.assertEqual("Widget", model["panels"][0]["dataSources"][0]["concept"])
            self.assertEqual("Widget", model["flows"][0]["input"]["concept"])
            self.assertEqual(
                "Widget",
                next(s for s in model["procedures"][0]["steps"] if "concept" in s)["concept"],
            )

    def test_refuses_without_concept_and_without_from(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            for kind in ("panel", "flow", "procedure"):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli.run_add_member(_add_args(f"X{kind}", model_path), kind)
                self.assertIn("--concept is required", str(ctx.exception))

    def test_refuses_unknown_concept_naming_available_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_add_member(_add_args("P", model_path, concept="NoSuchConcept"), "panel")
            message = str(ctx.exception)
            self.assertIn("NoSuchConcept", message)
            self.assertIn("Widget", message)

    def test_refuses_duplicate_name_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            before = model_path.read_text(encoding="utf-8")
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_add_member(_add_args("Widget", model_path), "concept")
            self.assertIn("already exists", str(ctx.exception))
            self.assertIn("Widget", str(ctx.exception))
            self.assertEqual(before, model_path.read_text(encoding="utf-8"),
                              "a refused add must not touch the file")

    def test_from_exemplar_copies_and_renames_a_self_contained_concept(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            captured = io.StringIO()
            with redirect_stdout(captured):
                code = npdev_cli.run_add_member(
                    _add_args("Owner", model_path, from_exemplar="npdev-canary::CanaryOwner"), "concept")
            self.assertEqual(0, code)
            report = json.loads(captured.getvalue())
            self.assertIn("npdev-canary::CanaryOwner", report["from"])
            model = json.loads(model_path.read_text(encoding="utf-8"))
            owner = next(c for c in model["concepts"] if c["name"] == "Owner")
            # The top-level identity (name + its own ui.label) is renamed; everything else (field
            # names, the invariant's own name) is copied verbatim -- see
            # _add_load_exemplar_member's docstring for why that split is deliberate.
            self.assertEqual("Owner", owner["ui"]["label"])
            self.assertTrue(any(f["name"] == "name" for f in owner["fields"]))
            self.assertEqual(["CanaryOwnerNameRequired"], [i["name"] for i in owner["invariants"]])

    def test_from_exemplar_refuses_on_dangling_reference_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            before = model_path.read_text(encoding="utf-8")
            # npdev-canary's own CreateCanaryTask flow binds to concept "CanaryTask", which this
            # bare model (only "Widget") does not declare -- must refuse, naming it, not write a
            # flow that `npdev validate model` would immediately reject.
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_add_member(
                    _add_args("MakeThing", model_path, from_exemplar="npdev-canary::CreateCanaryTask"),
                    "flow",
                )
            message = str(ctx.exception)
            self.assertIn("CanaryTask", message)
            self.assertIn("concept not found", message)
            self.assertEqual(before, model_path.read_text(encoding="utf-8"),
                              "a refused --from add must not touch the file")

    def test_from_unknown_sample_names_the_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_add_member(
                    _add_args("X", model_path, from_exemplar="no-such-sample-xyz"), "concept")
            self.assertIn("no-such-sample-xyz", str(ctx.exception))

    def test_from_unknown_member_lists_available(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_add_member(
                    _add_args("X", model_path, from_exemplar="npdev-canary::NoSuchConcept"), "concept")
            message = str(ctx.exception)
            self.assertIn("NoSuchConcept", message)
            self.assertIn("CanaryTask", message)
            self.assertIn("CanaryOwner", message)


class AddMemberRoundTripTest(unittest.TestCase):
    """R1.5's own Done-When, actually run: `npdev add concept Order --from <exemplar>` (plus panel/
    flow/procedure, default-stub and --from mixed) then the REAL validation path,
    `npdev validate model` (run_validate_semantic -- the R1.1 warm ai-tools-jar path when staged,
    else Gradle's :NPDevContract:dsl:validateModel), asserting zero errors by measurement."""

    def test_all_four_kinds_validate_with_zero_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _bare_model(tmp_dir)

            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, npdev_cli.run_add_member(
                    _add_args("Order", model_path), "concept"))
                self.assertEqual(0, npdev_cli.run_add_member(
                    _add_args("OrderPanel", model_path, concept="Order"), "panel"))
                self.assertEqual(0, npdev_cli.run_add_member(
                    _add_args("CreateOrder", model_path, concept="Order"), "flow"))
                self.assertEqual(0, npdev_cli.run_add_member(
                    _add_args("GetOrder", model_path, concept="Order"), "procedure"))
                # Mix in a --from copy too, proving the reference-scanned path also validates for
                # real, not just the hand-derived default stubs.
                self.assertEqual(0, npdev_cli.run_add_member(
                    _add_args("Owner", model_path, from_exemplar="npdev-canary::CanaryOwner"),
                    "concept",
                ))

            captured = io.StringIO()
            with redirect_stdout(captured):
                validate_code = npdev_cli.run_validate_semantic(model_path, None)
            report = json.loads(captured.getvalue())
            self.assertEqual(
                0, report["summary"]["errors"],
                f"scaffolded concept+panel+flow+procedure must validate with ZERO errors, "
                f"got: {report['diagnostics']}",
            )
            self.assertNotEqual("failed", report["status"])
            self.assertEqual(0, validate_code)


if __name__ == "__main__":
    unittest.main()
