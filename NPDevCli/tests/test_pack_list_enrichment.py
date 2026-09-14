"""Tests for W3.2's `_enrich_pack_list_report` (npdev_cli.py): `npdev pack list`'s report comes
straight from PackListMain (Java), which only ever re-serializes `resolvedVersion`/`sourcePath`/
`digest` per entry -- never `signature` (written into npdev.lock by `_verify_pack_signatures` on a
prior `pack add`/`update`) or `deprecated` (authored content in the pack's own `sourcePath`
pack.json). The Manager's new Packs screen (W3.2) needed both to show "signature status" and
"deprecation state" per pack, so this enriches the SAME report in place with data that already
exists on disk -- no re-verification, no re-resolution, just reading two fields the report was
silently missing.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_list_enrichment -v
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")


class EnrichPackListReportTest(unittest.TestCase):
    def test_merges_signature_from_lock_and_deprecated_from_source_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            source = tmp_dir / "cache" / "widgets" / "pack.json"
            _write(source, {
                "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                "deprecated": {"since": "1.0.0", "reason": "renamed",
                               "supersededBy": {"pack": "widgets2", "version": "2.0.0"}},
            })
            _write(tmp_dir / "npdev.lock", {
                "schemaVersion": "npdev-lock.v1",
                "packs": {"widgets": {
                    "resolvedVersion": "1.0.0", "digest": "sha256:aaaa", "sourcePath": str(source),
                    "signature": {"status": "verified", "keyId": "k1", "algorithm": "ed25519"},
                }},
            })

            report = {"status": "ok", "locked": True, "packs": {
                "widgets": {"resolvedVersion": "1.0.0", "sourcePath": str(source), "digest": "sha256:aaaa"},
            }}
            npdev_cli._enrich_pack_list_report(model_path, report)

            entry = report["packs"]["widgets"]
            self.assertEqual({"status": "verified", "keyId": "k1", "algorithm": "ed25519"}, entry["signature"])
            self.assertEqual({"since": "1.0.0", "reason": "renamed",
                               "supersededBy": {"pack": "widgets2", "version": "2.0.0"}},
                              entry["deprecated"])
            # untouched fields survive
            self.assertEqual("1.0.0", entry["resolvedVersion"])
            self.assertEqual("sha256:aaaa", entry["digest"])

    def test_no_lock_file_is_a_silent_noop_for_signature(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            source = tmp_dir / "cache" / "widgets" / "pack.json"
            _write(source, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0"})
            report = {"packs": {"widgets": {"resolvedVersion": "1.0.0", "sourcePath": str(source)}}}
            npdev_cli._enrich_pack_list_report(model_path, report)  # must not raise
            self.assertNotIn("signature", report["packs"]["widgets"])
            self.assertNotIn("deprecated", report["packs"]["widgets"])

    def test_missing_source_path_is_skipped_not_a_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            report = {"packs": {"widgets": {"resolvedVersion": "1.0.0",
                                             "sourcePath": str(tmp_dir / "gone.json")}}}
            npdev_cli._enrich_pack_list_report(model_path, report)  # must not raise
            self.assertNotIn("deprecated", report["packs"]["widgets"])

    def test_non_dict_packs_is_a_noop(self):
        report = {"packs": "not-a-dict"}
        npdev_cli._enrich_pack_list_report(Path("/does/not/matter"), report)  # must not raise
        self.assertEqual("not-a-dict", report["packs"])

    def test_relative_source_path_resolves_against_the_models_own_directory(self):
        """Regression: a LOCAL pack's `sourcePath` in npdev.lock is relative to the MODEL's own
        directory (PackDependencyGraphWalker's `rootDirectory.relativize`), never to the CLI
        process's current working directory -- caught live while capturing a Manager fixture:
        `pack list` run from a different cwd than the app's own directory silently found nothing
        for a relative sourcePath before `_resolve_pack_source_path` existed."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            _write(tmp_dir / "packs" / "oldwidgets" / "pack.json", {
                "dslVersion": "1.0.0", "pack": "oldwidgets", "version": "1.0.0",
                "deprecated": {"since": "1.0.0", "reason": "retired"},
            })
            report = {"packs": {"oldwidgets": {
                "resolvedVersion": "1.0.0", "sourcePath": "packs/oldwidgets/pack.json",  # relative
            }}}
            previous_cwd = Path.cwd()
            other_dir = tempfile.mkdtemp()
            try:
                os.chdir(other_dir)  # simulate the CLI running from an unrelated cwd
                npdev_cli._enrich_pack_list_report(model_path, report)
            finally:
                os.chdir(previous_cwd)
            self.assertEqual({"since": "1.0.0", "reason": "retired"}, report["packs"]["oldwidgets"]["deprecated"])

    def test_pack_with_no_deprecated_block_is_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            model_path = tmp_dir / "model.json"
            source = tmp_dir / "cache" / "widgets" / "pack.json"
            _write(source, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0"})
            report = {"packs": {"widgets": {"resolvedVersion": "1.0.0", "sourcePath": str(source)}}}
            npdev_cli._enrich_pack_list_report(model_path, report)
            self.assertNotIn("deprecated", report["packs"]["widgets"])


if __name__ == "__main__":
    unittest.main()
