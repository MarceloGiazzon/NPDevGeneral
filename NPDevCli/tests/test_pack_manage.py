"""P3: what the Manager's "Ready-made parts" screen asks of the CLI (npdev_pack_manage). No Gradle, no network.

Run with: python -m unittest NPDevCli.tests.test_pack_manage -v
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_pack_manage as manage  # noqa: E402

BEFORE = '{"packs": []}\n'
AFTER = '{"packs": [{"from": "x"}]}\n'


class FakeCli:
    """Just enough of npdev_cli for `change`: a runner that edits the files like the real one, and a validator."""
    CliError = npdev_cli.CliError

    def __init__(self, folder: Path, raises=None, code=0, valid=True, lines=()):
        self.folder, self.raises, self.code, self.valid, self.lines = folder, raises, code, valid, lines

    def _runner(self, args):
        (self.folder / "model.json").write_text(AFTER, encoding="utf-8")
        (self.folder / "npdev.lock").write_text("NEW LOCK", encoding="utf-8")
        print("added pack")
        if self.raises:
            raise self.raises
        return self.code

    run_pack_add = run_pack_update = _runner

    def run_validate_semantic(self, model, report_path, quiet=True):
        if self.valid is None:
            raise RuntimeError("no java")
        diagnostics = [{"severity": "error", "path": "$.x", "message": line} for line in self.lines]
        Path(report_path).write_text(json.dumps({"status": "passed" if self.valid else "failed",
                                                 "diagnostics": diagnostics}), encoding="utf-8")


class ChangeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        (self.dir / "model.json").write_text(BEFORE, encoding="utf-8")
        self.args = argparse.Namespace(model=str(self.dir / "model.json"), from_catalog="contacts")

    def run_change(self, **kwargs):
        return manage.change(FakeCli(self.dir, **kwargs), "add", self.args)

    def assert_untouched(self):
        self.assertEqual((self.dir / "model.json").read_text(encoding="utf-8"), BEFORE)
        self.assertFalse((self.dir / "npdev.lock").exists(), "a refused part leaves no lock behind")

    def test_a_valid_add_is_kept(self):
        doc = self.run_change()
        self.assertTrue(doc["ok"], doc)
        self.assertEqual((doc["code"], doc["validated"]), ("DONE", True))
        self.assertEqual((self.dir / "model.json").read_text(encoding="utf-8"), AFTER)

    def test_an_untrusted_part_is_refused_and_everything_put_back(self):
        doc = self.run_change(raises=npdev_cli.CliError("REFUSED (UNSIGNED): pack 'contacts' has no detached signature"))
        self.assertEqual((doc["ok"], doc["code"]), (False, "UNTRUSTED"))
        self.assert_untouched()

    def test_no_internet_is_its_own_code(self):
        doc = self.run_change(raises=npdev_cli.CliError("git clone failed: Could not resolve host: github.com"))
        self.assertEqual(doc["code"], "OFFLINE")
        self.assert_untouched()

    def test_a_part_missing_from_the_catalog(self):
        doc = self.run_change(raises=npdev_cli.CliError("no pack named 'zzz' in the catalog"))
        self.assertEqual(doc["code"], "NOT_IN_CATALOG")
        self.assert_untouched()

    def test_an_add_that_breaks_the_app_is_undone_with_the_validators_words(self):
        doc = self.run_change(valid=False, lines=["Customer.email is declared twice"])
        self.assertEqual((doc["ok"], doc["code"]), (False, "INVALID"))
        self.assertEqual(doc["details"], ["$.x - Customer.email is declared twice"])
        self.assert_untouched()

    def test_a_file_path_on_this_computer_never_reaches_the_person(self):
        lines = manage._validation_lines({"diagnostics": [{"severity": "error", "path": "/home/x/pack.json",
                                                           "message": "/home/x/.npdev/pack.json /packs: pack 'helpdesk' needs a role"}]})
        self.assertEqual(lines, ["pack 'helpdesk' needs a role"])

    def test_a_validator_that_cannot_run_here_does_not_undo_the_add(self):
        doc = self.run_change(valid=None)
        self.assertTrue(doc["ok"])
        self.assertFalse(doc["validated"])

    def test_a_nonzero_exit_is_a_refusal_too(self):
        doc = self.run_change(code=2)
        self.assertFalse(doc["ok"])
        self.assert_untouched()

    def test_a_missing_model(self):
        (self.dir / "model.json").unlink()
        self.assertEqual(self.run_change()["code"], "NO_MODEL")


class EnrichTests(unittest.TestCase):
    def cli(self, catalog=None, rules=()):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        cache = Path(tmp.name) / "catalog-cache.json"
        if catalog is not None:
            cache.write_text(json.dumps({"catalog": {"packs": catalog}}), encoding="utf-8")
        return SimpleNamespace(_pack_catalog_cache_path=lambda: cache, _load_pack_trust_defaults=lambda: list(rules))

    def test_trust_end_date_and_newer_version_are_added(self):
        packs = {"contacts": {"resolvedVersion": "1.0.0", "signature": {"status": "unsigned", "acceptedBy": "trust-defaults:own-npr"}},
                 "sales": {"resolvedVersion": "1.2.0"}, "tracing": {"resolvedVersion": "1.0.2"}}
        cli = self.cli([{"pack": "contacts", "version": "1.1.0"}, {"pack": "contacts", "version": "1.0.5"},
                        {"pack": "sales", "version": "1.2.0"}, {"pack": "tracing", "version": "1.0.1"}],
                       [{"id": "own-npr", "until": "2027-02-28"}])
        manage.enrich(cli, packs)
        self.assertEqual(packs["contacts"]["trustUntil"], "2027-02-28")
        self.assertEqual(packs["contacts"]["latestVersion"], "1.1.0")
        self.assertNotIn("latestVersion", packs["sales"], "same version is not an update")
        self.assertNotIn("latestVersion", packs["tracing"], "an older catalog version is not an update")
        self.assertNotIn("trustUntil", packs["sales"])

    def test_no_cached_catalog_means_no_update_hint_and_never_an_error(self):
        packs = {"contacts": {"resolvedVersion": "1.0.0"}}
        manage.enrich(self.cli(None), packs)
        self.assertEqual(packs, {"contacts": {"resolvedVersion": "1.0.0"}})


class CliDispatchTests(unittest.TestCase):
    def test_json_flag_prints_one_document_and_does_not_recurse(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp) / "model.json"
            model.write_text(BEFORE, encoding="utf-8")
            args = argparse.Namespace(model=str(model), from_catalog="contacts", json=True, allow_unsigned=False)
            out = io.StringIO()
            with mock.patch.object(npdev_cli, "_add_pack_from_catalog"), \
                    mock.patch.object(npdev_cli, "_run_pack_gradle_task_with_tamper_guard", return_value=0) as gradle, \
                    mock.patch.object(npdev_cli, "run_validate_semantic", side_effect=RuntimeError("no java")), \
                    contextlib.redirect_stdout(out):
                code = npdev_cli.run_pack_add(args)
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(out.getvalue())["schemaVersion"], "npdev-pack-change.v1")
            gradle.assert_called_once()

    def test_the_parser_knows_json_on_add_and_update(self):
        parser_source = (Path(__file__).resolve().parent.parent / "npdev_cli.py").read_text(encoding="utf-8")
        self.assertEqual(parser_source.count('pack_add.add_argument("--json"'), 1)
        self.assertEqual(parser_source.count('pack_update.add_argument("--json"'), 1)


if __name__ == "__main__":
    unittest.main()
