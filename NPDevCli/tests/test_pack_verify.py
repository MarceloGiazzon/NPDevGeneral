"""Tests for R8.3's `npdev pack verify` -- the pack conformance harness (npdev_cli.py:
`_pack_schema_shallow_check`, `_classify_pack_signature`, `_verify_pack_standalone`,
`_verify_pack_model`, `run_pack_verify`).

PREMISE CHECKED BEFORE WRITING ANY OF THIS (see npdev_cli.py's own R8.3 module comment for the
full detail):
  1. There is no `npdev pack verify` and no pack conformance harness anywhere in this file before
     this change. TRUE -- grepped for "pack_verify"/"PackVerify" before writing any of this: zero
     hits outside this new code.
  2. `validate_json_schema` (the existing ajv-based model.schema.json validator) can be pointed
     directly at `NPDevContract/schemas/pack.schema.json` for a real, deep structural check. FALSE,
     measured directly: `validate_json_schema(pack.schema.json, a real pack.json)` raises a CliError
     wrapping `MissingRefError: can't resolve reference model.schema.json#/$defs/concept` --
     pack.schema.json's concept/panel/flow/... items `$ref` a sibling file
     `validate-json-schema.mjs` never registers with ajv. That script lives under
     `scripts/quality/json-schema-validator/`, outside this task's owned surface
     (NPDevCli/npdev_cli.py + its tests), so `_pack_schema_shallow_check` deliberately validates
     only the pack.json document's own TOP-LEVEL shape (plus one level into a `$ref`-free nested
     object like `forkedFrom`) rather than re-deriving a second, necessarily-approximate deep
     validator -- see that function's own module comment.
  3. R8.3 must not invent a second notion of "is this pack ok" -- `_classify_pack_signature` is the
     SAME function `_verify_pack_signatures` (used by `pack add`/`pack update`) calls, factored out
     rather than duplicated; `VerifyPackSignaturesUnitTest` in test_pack_signing.py still passes
     unmodified against the refactored `_verify_pack_signatures`, proving the refactor preserved its
     exact external behavior (message text, rollback-on-refusal) while `run_pack_verify` reuses the
     same classification read-only.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_verify -v
"""

from __future__ import annotations

import argparse
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")


def _valid_pack_doc(pack_id: str = "widgets", version: str = "1.0.0") -> dict:
    return {
        "dslVersion": "1.0.0",
        "pack": pack_id,
        "version": version,
        "category": "other",
        "author": "tester",
        "concepts": [{"name": "Widget", "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True}]}],
    }


# -------------------------------------------------------------------------------------------------
# `_pack_schema_shallow_check` -- the top-level structural check.
# -------------------------------------------------------------------------------------------------

class PackSchemaShallowCheckUnitTest(unittest.TestCase):
    def setUp(self):
        self.schema = npdev_cli.read_json(
            npdev_cli.repo_root() / "NPDevContract" / "schemas" / "pack.schema.json")

    def test_conforming_pack_has_no_violations(self):
        violations = npdev_cli._pack_schema_shallow_check(_valid_pack_doc(), self.schema)
        self.assertEqual([], violations)

    def test_missing_required_field_is_named(self):
        doc = _valid_pack_doc()
        del doc["version"]
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("/version" in v and "required" in v for v in violations), violations)

    def test_bad_pack_identifier_pattern_is_named(self):
        doc = _valid_pack_doc(pack_id="Not-Valid-ID")
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("/pack" in v and "pattern" in v for v in violations), violations)

    def test_bad_version_pattern_is_named(self):
        doc = _valid_pack_doc(version="not-a-version")
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("/version" in v and "pattern" in v for v in violations), violations)

    def test_wrong_dsl_version_const_is_named(self):
        doc = _valid_pack_doc()
        doc["dslVersion"] = "2.0.0"
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("/dslVersion" in v and "equal" in v for v in violations), violations)

    def test_bad_category_enum_is_named(self):
        doc = _valid_pack_doc()
        doc["category"] = "not-a-real-category"
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("/category" in v and "one of" in v for v in violations), violations)

    def test_unexpected_top_level_property_is_named(self):
        doc = _valid_pack_doc()
        doc["totallyUnknownField"] = "x"
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("totallyUnknownField" in v and "unexpected" in v for v in violations), violations)

    def test_forked_from_missing_version_is_named_one_level_deep(self):
        doc = _valid_pack_doc()
        doc["forkedFrom"] = {"pack": "origin"}  # missing required 'version'
        violations = npdev_cli._pack_schema_shallow_check(doc, self.schema)
        self.assertTrue(any("/forkedFrom/version" in v for v in violations), violations)

    def test_non_object_document_is_refused(self):
        violations = npdev_cli._pack_schema_shallow_check(["not", "an", "object"], self.schema)
        self.assertTrue(violations)


# -------------------------------------------------------------------------------------------------
# `run_pack_verify --pack` -- standalone pack.json conformance.
# -------------------------------------------------------------------------------------------------

class VerifyPackStandaloneUnitTest(unittest.TestCase):
    def _run(self, args_ns):
        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_verify(args_ns)
        report, _rest = json.JSONDecoder().raw_decode(out.getvalue())
        return code, report

    def test_conforming_pack_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            _write(pack_path, _valid_pack_doc())
            args = argparse.Namespace(pack=str(pack_path), model=None, against_digest=None,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(0, code, report)
            self.assertTrue(report["ok"])
            self.assertTrue(report["schemaValid"])
            self.assertEqual([], report["schemaViolations"])
            self.assertTrue(report["digest"].startswith("sha256:"))

    def test_malformed_pack_is_refused_with_schema_invalid_reasons(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            doc = _valid_pack_doc()
            del doc["dslVersion"]
            doc["pack"] = "BadID"
            _write(pack_path, doc)
            args = argparse.Namespace(pack=str(pack_path), model=None, against_digest=None,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertFalse(report["ok"])
            self.assertFalse(report["schemaValid"])
            self.assertTrue(report["schemaViolations"])

    def test_pack_not_found_is_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = argparse.Namespace(pack=str(Path(tmp) / "nope.json"), model=None,
                                       against_digest=None, from_coord=None, trust=None,
                                       allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertFalse(report["ok"])
            self.assertEqual("NOT_FOUND", report["reason"])

    def test_malformed_json_is_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            pack_path.write_text("{not valid json", encoding="utf-8")
            args = argparse.Namespace(pack=str(pack_path), model=None, against_digest=None,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertEqual("MALFORMED_JSON", report["reason"])

    def test_digest_mismatch_is_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            _write(pack_path, _valid_pack_doc())
            args = argparse.Namespace(pack=str(pack_path), model=None,
                                       against_digest="sha256:" + "0" * 64,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertFalse(report["digestOk"])
            self.assertIn("DIGEST_MISMATCH", report["digestReason"])

    def test_digest_match_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            _write(pack_path, _valid_pack_doc())
            computed = npdev_cli._pack_content_digest({"pack.json": pack_path.read_bytes()})
            args = argparse.Namespace(pack=str(pack_path), model=None, against_digest=computed,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(0, code, report)
            self.assertTrue(report["digestOk"])

    def test_directory_form_digest_matches_pack_content_digest_of_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_dir = Path(tmp) / "widgets"
            _write(pack_dir / "pack.json", _valid_pack_doc())
            expected = npdev_cli._pack_content_digest_of_dir(pack_dir)
            args = argparse.Namespace(pack=str(pack_dir), model=None, against_digest=expected,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(0, code, report)
            self.assertEqual(expected, report["digest"])

    def test_from_with_unsigned_pack_is_refused_without_allow_unsigned(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            _write(pack_path, _valid_pack_doc())
            args = argparse.Namespace(
                pack=str(pack_path), model=None, against_digest=None,
                from_coord="git+https://example.com/w.git//p@v1.0.0", trust=None, allow_unsigned=False)
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=None):
                code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertEqual("UNSIGNED_REFUSED", report["signature"]["status"])

    def test_from_with_verified_signature_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack_path = Path(tmp) / "pack.json"
            _write(pack_path, _valid_pack_doc())
            digest = npdev_cli._pack_content_digest({"pack.json": pack_path.read_bytes()})
            seed = os.urandom(32)
            public_key = npdev_cli.ed25519_public_key(seed)
            signature = npdev_cli.ed25519_sign(digest.encode("utf-8"), seed, public_key)
            trust_path = Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME
            _write(trust_path, {"trustedKeys": {"trusted-key": public_key.hex()}})
            sig_record = {"keyId": "trusted-key", "signature": signature.hex()}
            args = argparse.Namespace(
                pack=str(pack_path), model=None, against_digest=None,
                from_coord="git+https://example.com/w.git//p@v1.0.0", trust=None, allow_unsigned=False)
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=sig_record):
                code, report = self._run(args)
            self.assertEqual(0, code, report)
            self.assertEqual("VERIFIED", report["signature"]["status"])

    def test_pack_and_model_both_given_is_refused(self):
        args = argparse.Namespace(pack="x.json", model="m.json", against_digest=None,
                                   from_coord=None, trust=None, allow_unsigned=False)
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli.run_pack_verify(args)

    def test_neither_pack_nor_model_is_refused(self):
        args = argparse.Namespace(pack=None, model=None, against_digest=None,
                                   from_coord=None, trust=None, allow_unsigned=False)
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli.run_pack_verify(args)


# -------------------------------------------------------------------------------------------------
# `run_pack_verify --model` -- read-only re-check of every remote pack in npdev.lock.
# -------------------------------------------------------------------------------------------------

class VerifyPackModelUnitTest(unittest.TestCase):
    def _lock_text(self, packs: dict) -> str:
        return json.dumps({"schemaVersion": "npdev-lock.v1", "packs": packs}, indent=2) + "\n"

    def _run(self, args_ns):
        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_verify(args_ns)
        report, _rest = json.JSONDecoder().raw_decode(out.getvalue())
        return code, report

    def test_no_lock_is_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            args = argparse.Namespace(pack=None, model=str(model_path), against_digest=None,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertEqual("NO_LOCK", report["reason"])

    def test_local_pack_is_reported_as_local_and_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            lock_text = self._lock_text({
                "local": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x"},
            })
            (Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME).write_text(lock_text, encoding="utf-8")
            args = argparse.Namespace(pack=None, model=str(model_path), against_digest=None,
                                       from_coord=None, trust=None, allow_unsigned=False)
            code, report = self._run(args)
            self.assertEqual(0, code, report)
            self.assertEqual("LOCAL", report["packs"][0]["status"])
            # Read-only: the lock file on disk must be untouched.
            self.assertEqual(lock_text, (Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME).read_text(encoding="utf-8"))

    def test_unsigned_remote_pack_without_allow_unsigned_fails_and_is_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            lock_text = self._lock_text(packs)
            (Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME).write_text(lock_text, encoding="utf-8")
            args = argparse.Namespace(pack=None, model=str(model_path), against_digest=None,
                                       from_coord=None, trust=None, allow_unsigned=False)
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=None):
                code, report = self._run(args)
            self.assertEqual(2, code)
            self.assertFalse(report["ok"])
            entry = report["packs"][0]
            self.assertEqual("UNSIGNED_REFUSED", entry["status"])
            self.assertIn("UNSIGNED", entry["reason"])
            # Read-only: no signature field was written back.
            self.assertEqual(lock_text, (Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME).read_text(encoding="utf-8"))

    def test_composition_matches_verify_pack_signatures_classification(self):
        """The load-bearing composition proof: `run_pack_verify`'s classification for a given
        pack entry/trust/allow_unsigned is the SAME classification `_verify_pack_signatures`
        (used by `pack add`/`pack update`) would reach for the identical inputs -- not a second,
        independently-reimplemented notion of "is this pack ok"."""
        with tempfile.TemporaryDirectory() as tmp:
            seed = os.urandom(32)
            public_key = npdev_cli.ed25519_public_key(seed)
            digest = "sha256:aa"
            signature = npdev_cli.ed25519_sign(digest.encode("utf-8"), seed, public_key)
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": digest, "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = Path(tmp) / "model.json"
            lock_text = self._lock_text(packs)
            (Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME).write_text(lock_text, encoding="utf-8")
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME,
                   {"trustedKeys": {"trusted-key": public_key.hex()}})
            sig_record = {"keyId": "trusted-key", "signature": signature.hex()}

            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=sig_record):
                _code, report = self._run(argparse.Namespace(
                    pack=None, model=str(model_path), against_digest=None, from_coord=None,
                    trust=None, allow_unsigned=False))
                # Now actually run the mutating path against a copy of the same lock state.
                npdev_cli._verify_pack_signatures(model_path, lock_text, argparse.Namespace(allow_unsigned=False))

            self.assertEqual("VERIFIED", report["packs"][0]["status"])
            written = json.loads((Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME).read_text(encoding="utf-8"))
            self.assertEqual("verified", written["packs"]["widgets"]["signature"]["status"])


if __name__ == "__main__":
    unittest.main()
