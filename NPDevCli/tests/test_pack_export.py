"""Tests for `npdev pack export` (R8.2).

R8.2 retired the only previously-real path from a working app concept to a reusable pack --
NPDevSamples/scripts/packs/export-concept-to-pack.ps1, an external, single-concept PowerShell
script with zero reference handling and zero schema validation -- in favor of a real CLI verb:
multi-member export with references rewritten to intra-pack form.

Two tiers, matching this repo's PackWhyCliTest convention (test_pack_cli.py):
  - PackExportUnitTest: fast, calls run_pack_export directly (no subprocess) to check the
    reference-rewriting rules structurally.
  - PackExportRoundTripTest: slower (~15-40s), real end-to-end -- shells out through
    `npdev pack add` and `npdev validate model` to the actual Gradle-backed DSL composer, proving
    R8.2's own Done-When ("an exported pack validates and composes back into a throwaway model
    cleanly") by measurement, not assertion.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_export -v
"""

from __future__ import annotations

import argparse
import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content)
    path.write_text(content, encoding="utf-8")


def _export_args(**overrides) -> argparse.Namespace:
    base = dict(
        model=None, concepts=None, pack=None, author="Test Author",
        category="other", description="", pack_version="1.0.0", namespace="",
        out_dir="", forked_from_pack="", forked_from_version="", forked_from_author="",
        allow_unresolved_refs=False,
    )
    base.update(overrides)
    return argparse.Namespace(**base)


def _source_model(tmp_dir: Path) -> Path:
    model_path = tmp_dir / "model.json"
    _write(model_path, {
        "namespace": "test.pack.export", "dslVersion": "1.0.0", "version": "1.0",
        "concepts": [
            {
                "name": "Project",
                "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    {"name": "name", "type": "string", "required": True},
                    {"name": "ownerId", "type": "reference", "required": True,
                     "reference": {"target": "identity::User", "onDelete": "restrict"}},
                    {"name": "unownedThing", "type": "reference", "required": False,
                     "reference": {"target": "SomeAppOnlyConcept"}},
                ],
            },
            {
                "name": "ProjectLine",
                "fields": [
                    {"name": "id", "type": "uuid", "id": True, "required": True},
                    # A spurious pre-existing qualifier on a reference to a concept ALSO being
                    # exported -- proves the intra-pack rewrite actually rewrites, rather than just
                    # passing an already-bare name through unchanged.
                    {"name": "projectId", "type": "reference", "required": True,
                     "reference": {"target": "otherqualifier::Project", "onDelete": "cascade"}},
                    {"name": "label", "type": "string", "required": True},
                ],
            },
        ],
    })
    return model_path


class PackExportUnitTest(unittest.TestCase):
    def test_multi_member_export_rewrites_intra_pack_refs_and_declares_cross_pack_dep(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _source_model(tmp_dir)
            out_dir = tmp_dir / "out"
            args = _export_args(
                model=str(model_path), concepts="Project,ProjectLine", pack="projexporttest",
                out_dir=str(out_dir), allow_unresolved_refs=True,
            )
            captured = io.StringIO()
            with redirect_stdout(captured), redirect_stderr(io.StringIO()):
                code = npdev_cli.run_pack_export(args)
            self.assertEqual(0, code)
            report = json.loads(captured.getvalue())
            self.assertEqual(["Project", "ProjectLine"], report["concepts"])

            # The spurious "otherqualifier::Project" -> bare "Project" rewrite actually happened.
            self.assertTrue(
                any(r["from"] == "otherqualifier::Project" and r["to"] == "Project"
                    for r in report["rewrittenReferences"]),
                f"expected an intra-pack rewrite of the ProjectLine->Project reference, "
                f"got: {report['rewrittenReferences']}",
            )
            # The identity::User cross-pack reference became a real packs[] dependency.
            self.assertTrue(
                any(dep["pack"] == "identity" for dep in report["crossPackDependencies"]),
                f"expected a declared 'identity' pack dependency, got: {report['crossPackDependencies']}",
            )
            # The non-portable reference was reported by name, not silently dropped.
            self.assertTrue(
                any(u["target"] == "SomeAppOnlyConcept" for u in report["unresolvedReferences"]),
                f"expected SomeAppOnlyConcept reported as unresolved, got: {report['unresolvedReferences']}",
            )

            pack_doc = json.loads((out_dir / "projexporttest" / "pack.json").read_text(encoding="utf-8"))
            self.assertEqual(["Project", "ProjectLine"], [c["name"] for c in pack_doc["concepts"]])
            line_ref = pack_doc["concepts"][1]["fields"][1]["reference"]
            self.assertEqual("Project", line_ref["target"], "must be bare/intra-pack form, not qualified")
            project_ref = pack_doc["concepts"][0]["fields"][2]["reference"]
            self.assertEqual("identity::User", project_ref["target"], "cross-pack ref stays qualified as-is")
            self.assertIn("identity", [dep["pack"] for dep in pack_doc["packs"]])
            self.assertEqual(
                ["SomeAppOnlyConcept"],
                [u["target"] for u in pack_doc["metadata"]["unresolvedReferences"]],
            )

    def test_refuses_export_by_default_on_non_portable_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _source_model(tmp_dir)
            args = _export_args(
                model=str(model_path), concepts="Project,ProjectLine", pack="projexportrefuse",
                out_dir=str(tmp_dir / "out"),
            )
            with self.assertRaises(npdev_cli.CliError) as ctx:
                with redirect_stdout(io.StringIO()):
                    npdev_cli.run_pack_export(args)
            message = str(ctx.exception)
            self.assertIn("SomeAppOnlyConcept", message,
                           "must name the unportable target, not just refuse silently")
            self.assertIn("Project.unownedThing", message, "must name the offending concept.field")
            self.assertFalse((tmp_dir / "out" / "projexportrefuse").exists(),
                              "must not write a half-exported pack on refusal")

    def test_allow_unresolved_refs_writes_pack_and_warns(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _source_model(tmp_dir)
            args = _export_args(
                model=str(model_path), concepts="Project,ProjectLine", pack="projexportallow",
                out_dir=str(tmp_dir / "out"), allow_unresolved_refs=True,
            )
            stderr = io.StringIO()
            with redirect_stdout(io.StringIO()), redirect_stderr(stderr):
                code = npdev_cli.run_pack_export(args)
            self.assertEqual(0, code)
            self.assertIn("SomeAppOnlyConcept", stderr.getvalue(), "override must still warn loudly")
            self.assertTrue((tmp_dir / "out" / "projexportallow" / "pack.json").exists())

    def test_missing_concept_named_with_available_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _source_model(tmp_dir)
            args = _export_args(
                model=str(model_path), concepts="Project,NoSuchConcept", pack="projexportmissing",
                out_dir=str(tmp_dir / "out"),
            )
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_pack_export(args)
            message = str(ctx.exception)
            self.assertIn("NoSuchConcept", message)
            self.assertIn("Project", message)
            self.assertIn("ProjectLine", message)

    def test_refuses_to_overwrite_existing_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = _source_model(tmp_dir)
            out_dir = tmp_dir / "out"
            _write(out_dir / "existingpack" / "pack.json", {"pack": "existingpack"})
            args = _export_args(
                model=str(model_path), concepts="Project", pack="existingpack",
                out_dir=str(out_dir), allow_unresolved_refs=True,
            )
            with self.assertRaises(npdev_cli.CliError) as ctx:
                with redirect_stdout(io.StringIO()):
                    npdev_cli.run_pack_export(args)
            self.assertIn("already exists", str(ctx.exception))


class PackExportRoundTripTest(unittest.TestCase):
    """R8.2's own Done-When, actually run: export a pack, then compose it back into a throwaway
    model via the REAL DSL composer (`npdev pack add` + `npdev validate model`, both shelling out
    to the real Gradle-backed Java pipeline, same discipline as PackWhyCliTest -- slow, ~15-40s)."""

    def test_exported_pack_composes_and_validates_with_zero_errors(self):
        repo_root = Path(__file__).resolve().parent.parent.parent
        identity_pack = repo_root / "NPDevContract" / "packs" / "identity" / "pack.json"
        self.assertTrue(identity_pack.exists(), f"fixture precondition: {identity_pack} must exist")

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _write(model_path, {
                "namespace": "test.pack.export.roundtrip", "dslVersion": "1.0.0", "version": "1.0",
                "concepts": [
                    {
                        "name": "Project",
                        "fields": [
                            {"name": "id", "type": "uuid", "id": True, "required": True},
                            {"name": "name", "type": "string", "required": True},
                            {"name": "ownerId", "type": "reference", "required": True,
                             "reference": {"target": "identity::User", "onDelete": "restrict"}},
                        ],
                    },
                    {
                        "name": "ProjectLine",
                        "fields": [
                            {"name": "id", "type": "uuid", "id": True, "required": True},
                            {"name": "projectId", "type": "reference", "required": True,
                             "reference": {"target": "Project", "onDelete": "cascade"}},
                            {"name": "label", "type": "string", "required": True},
                        ],
                    },
                ],
            })

            export_args = _export_args(
                model=str(model_path), concepts="Project,ProjectLine", pack="rttestpack",
                out_dir=str(tmp_dir / "out"),
            )
            with redirect_stdout(io.StringIO()):
                export_code = npdev_cli.run_pack_export(export_args)
            self.assertEqual(0, export_code)

            throwaway_model = tmp_dir / "throwaway-app-model.json"
            _write(throwaway_model, {
                "namespace": "npdev.throwaway.pack.export.roundtrip", "dslVersion": "1.0.0",
                "version": "1.0",
                "packs": [{"$ref": "out/rttestpack/pack.json"}],
            })
            # PackDependencyGraphWalker.defaultPackFile: a transitive dependency is discovered as
            # <rootDirectory>/packs/<packId>/pack.json -- "still local files only, no registry yet"
            # (PK-3) -- so the throwaway model needs its own local copy of identity/, exactly as a
            # real app extracting this pack into its own tree would.
            (tmp_dir / "packs" / "identity").mkdir(parents=True)
            shutil.copy(identity_pack, tmp_dir / "packs" / "identity" / "pack.json")

            add_args = argparse.Namespace(model=str(throwaway_model))
            with redirect_stdout(io.StringIO()):
                add_code = npdev_cli.run_pack_add(add_args)
            self.assertEqual(0, add_code,
                              "npdev pack add must resolve the transitive identity dependency "
                              "and write npdev.lock")

            captured = io.StringIO()
            with redirect_stdout(captured):
                validate_code = npdev_cli.run_validate_semantic(throwaway_model, None)
            report = json.loads(captured.getvalue())
            self.assertEqual(
                0, report["summary"]["errors"],
                f"exported pack must compose and validate with ZERO errors, got: {report['diagnostics']}",
            )
            self.assertNotEqual("failed", report["status"])
            self.assertEqual(0, validate_code)


if __name__ == "__main__":
    unittest.main()
