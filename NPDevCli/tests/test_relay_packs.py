"""P2: a browser AI's answer may import packs from the NPDev catalog (`packs[]` with a remote `from`).

NPDev resolves them the way `npdev pack update` does, INTO the staged folder, with allow_unsigned False -- so only a
real signature or the temporary trust rule (P1) lets a pack in -- and the lock travels with the model on apply.
Everything but the last class runs without Gradle or a network (the CLI's pack update is replaced by a stub);
the last class resolves a real `git+file://` fixture through the real Gradle-backed machinery (~15-25 s).

Run with: python -m unittest NPDevCli.tests.test_relay_packs -v
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_cli  # noqa: E402
import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_files as files_mod  # noqa: E402
from test_relay_engine import SEED_MODEL, EngineCase, answer  # noqa: E402

COORD = "git+https://github.com/MarceloGiazzon/NPR.git//packs/contacts@contacts-1.0.0"
CATALOG = {
    "schemaVersion": "npdev-pack-catalog.v1", "repository": "https://github.com/MarceloGiazzon/NPR",
    "packs": [
        {"pack": "contacts", "version": "1.0.0", "description": "Organizations, people, addresses",
         "from": COORD},
        {"pack": "sales", "version": "2.0.0", "description": "x " * 80,
         "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/sales@sales-2.0.0"},
        {"pack": "odd", "version": "1.0.0", "description": "", "from": "git+https://example.org/other.git//p@v1"},
    ],
}
LOCK = json.dumps({"schemaVersion": "npdev-lock.v1", "packs": {"contacts": {
    "from": COORD, "resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x"}}}, indent=2) + "\n"


def model_with_packs(*entries: dict) -> str:
    model = json.loads(SEED_MODEL)
    model["packs"] = list(entries)
    return json.dumps(model, indent=2)


def writes_lock(text: str = LOCK):
    """A stand-in for `npdev pack update`: writes the lock beside the model it is given, like the real one."""
    def run(args):
        (Path(args.model).parent / "npdev.lock").write_text(text, encoding="utf-8")
        return 0
    return run


class PackEntriesTests(unittest.TestCase):
    def test_only_remote_from_entries_count(self):
        model = {"packs": [{"from": COORD}, {"$ref": "packs/local/pack.json"}, {"from": "  "}, "junk"]}
        self.assertEqual(files_mod.remote_pack_entries(model), [{"from": COORD}])
        self.assertEqual(files_mod.remote_pack_entries({}), [])

    def test_names_come_from_the_alias_or_the_coordinate(self):
        self.assertEqual(files_mod.pack_name({"from": COORD}), "contacts")
        self.assertEqual(files_mod.pack_name({"from": COORD, "as": "people"}), "people")


class ResolvePacksTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.model_path = Path(self.tmp.name) / "model.json"
        self.model = json.loads(model_with_packs({"from": COORD}))
        self.model_path.write_text(json.dumps(self.model), encoding="utf-8")

    def test_the_lock_is_written_beside_the_staged_model_and_returned(self):
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=writes_lock()) as update:
            text = files_mod.resolve_packs(self.model_path, self.model)
        self.assertEqual(text, LOCK)
        args = update.call_args.args[0]
        self.assertFalse(args.allow_unsigned, "only a signature or the trust rule may accept a pack")

    def test_nothing_to_do_without_remote_packs_or_when_the_lock_already_covers_them(self):
        with mock.patch.object(npdev_cli, "run_pack_update") as update:
            self.assertIsNone(files_mod.resolve_packs(self.model_path, {"packs": [{"$ref": "x.json"}]}))
            (self.model_path.parent / "npdev.lock").write_text(LOCK, encoding="utf-8")
            self.assertIsNone(files_mod.resolve_packs(self.model_path, self.model))
        update.assert_not_called()

    def test_no_internet_gives_the_plain_sentence(self):
        failure = npdev_cli.CliError("pack packUpdate failed (gradle exit 1): git clone failed (exit 128): "
                                     "fatal: unable to access 'https://github.com/x/': Could not resolve host: github.com")
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            with self.assertRaises(files_mod.PackError) as raised:
                files_mod.resolve_packs(self.model_path, self.model)
        self.assertEqual(str(raised.exception), files_mod.OFFLINE_MESSAGE)

    def test_an_unknown_pack_names_the_pack(self):
        failure = npdev_cli.CliError("git clone failed (exit 128): warning: Could not find remote branch ghost-9.9.9")
        model = json.loads(model_with_packs({"from": COORD.replace("contacts", "ghost").replace("1.0.0", "9.9.9")}))
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            with self.assertRaises(files_mod.PackError) as raised:
                files_mod.resolve_packs(self.model_path, model)
        self.assertIn("ghost", str(raised.exception))
        self.assertNotIn("no internet", str(raised.exception))

    def test_an_unsigned_pack_outside_the_trust_rule_is_refused_in_plain_words(self):
        failure = npdev_cli.CliError("REFUSED (UNSIGNED): pack 'contacts' @ 1.0.0 has no detached signature. Pass --allow-unsigned")
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            with self.assertRaises(files_mod.PackError) as raised:
                files_mod.resolve_packs(self.model_path, self.model)
        self.assertIn("contacts", str(raised.exception))
        self.assertNotIn("--allow-unsigned", str(raised.exception), "the AI cannot pass flags")

    def test_a_missing_dependency_names_the_packs_the_answer_must_also_import(self):
        # REG-332: importing only `sales` fails because its dependencies resolve beside the model, never from the catalog.
        # the wording is the OS's (here Portuguese Windows), so the pack ids are found by path, not by the sentence
        failure = npdev_cli.CliError("C" ":\\app\\packs\\contacts\\pack.json (O sistema nao pode encontrar o caminho)\n"
                                     "packs/products/pack.json: path not found")
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            with self.assertRaises(files_mod.PackError) as raised:
                files_mod.resolve_packs(self.model_path, self.model)
        message = str(raised.exception)
        self.assertIn("contacts, products", message)
        self.assertIn("packs[]", message)
        self.assertNotIn("could not download", message)
        self.assertEqual(files_mod.missing_dependencies("nothing relevant", self.model_path.parent), [])

    def test_a_downloaded_pack_that_fails_the_current_schema_says_the_catalog_is_out_of_date(self):
        failure = npdev_cli.CliError("Model schema validation failed for pack.json:\n"
                                     " - $.dslVersion: $.dslVersion: must be the constant '3.0.0'")
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            with self.assertRaises(files_mod.PackError) as raised:
                files_mod.resolve_packs(self.model_path, self.model)
        text = str(raised.exception)
        self.assertIn("contacts", text)
        self.assertIn("out of date", text)
        self.assertNotIn("could not download", text, "the download worked; the old sentence blamed the wrong thing")

    def test_a_failed_update_that_leaves_no_lock_is_refused(self):
        with mock.patch.object(npdev_cli, "run_pack_update", return_value=2):
            with self.assertRaises(files_mod.PackError):
                files_mod.resolve_packs(self.model_path, self.model)


class RelayPackTests(EngineCase):
    def test_an_answer_with_an_npr_pack_is_ready_and_carries_the_lock(self):
        code = self.start()["code"]
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=writes_lock()):
            result = engine.ingest(code, answer(code, 1, model_with_packs({"from": COORD})), source="paste")
        self.assertEqual(result["outcome"], "READY", result.get("errorLines"))
        lock = [f for f in result["files"] if f["path"] == "npdev.lock"]
        self.assertEqual([f["status"] for f in lock], ["generated"])
        self.assertEqual(files_mod.answer_files(result)[0], {"npdev.lock": LOCK})

    def test_apply_writes_the_lock_beside_the_new_model(self):
        code = self.start()["code"]
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=writes_lock()):
            engine.ingest(code, answer(code, 1, model_with_packs({"from": COORD})), source="paste")
        out = apply_mod.apply_round(engine.load_session(code), 1, [], parent_dir=self.dir / "apps")
        self.assertTrue(out["ok"], out)
        app = Path(out["appDir"])
        self.assertEqual((app / "npdev.lock").read_text(encoding="utf-8"), LOCK)
        self.assertIn("npdev.lock", out["files"])

    def test_no_internet_is_a_needs_fix_with_the_plain_sentence(self):
        code = self.start()["code"]
        failure = npdev_cli.CliError("git clone failed (exit 128): Could not resolve host: github.com")
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            result = engine.ingest(code, answer(code, 1, model_with_packs({"from": COORD})), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertEqual(result["errorLines"], [files_mod.OFFLINE_MESSAGE])
        self.validation_mock.assert_not_called()

    def test_an_invented_pack_is_a_needs_fix_naming_it(self):
        code = self.start()["code"]
        failure = npdev_cli.CliError("git clone failed (exit 128): Remote branch ghost-1.0.0 not found")
        with mock.patch.object(npdev_cli, "run_pack_update", side_effect=failure):
            result = engine.ingest(code, answer(code, 1, model_with_packs(
                {"from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/ghost@ghost-1.0.0"})), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("ghost", result["errorLines"][0])

    def test_a_model_without_packs_never_calls_the_pack_machinery(self):
        code = self.start()["code"]
        with mock.patch.object(npdev_cli, "run_pack_update") as update:
            result = engine.ingest(code, answer(code, 1), source="paste")
        self.assertEqual(result["outcome"], "READY")
        update.assert_not_called()


class CatalogBriefingTests(unittest.TestCase):
    def test_the_list_is_generated_from_the_catalog(self):
        text = engine.pack_catalog_text(CATALOG)
        self.assertIn("- contacts 1.0.0 - Organizations, people, addresses\n", text)
        self.assertIn("git+https://github.com/MarceloGiazzon/NPR.git//packs/<id>@<id>-<version>", text)
        self.assertIn("-- from: `git+https://example.org/other.git//p@v1`", text)  # off-pattern: its own coordinate
        sales = next(line for line in text.splitlines() if line.startswith("- sales"))
        self.assertTrue(sales.endswith("..."), sales)  # a long description is cut to one short line

    def test_a_pack_with_dependencies_says_to_import_them_too(self):
        catalog = {"repository": "https://github.com/MarceloGiazzon/NPR.git", "packs": [
            {"pack": "sales", "version": "2.0.0", "needs": ["contacts", "products"],
             "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/sales@sales-2.0.0"},
            {"pack": "contacts", "version": "1.1.0",
             "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/contacts@contacts-1.1.0"}]}
        text = engine.pack_catalog_text(catalog)
        self.assertIn("- sales 2.0.0 [also import: contacts, products]", text)
        self.assertNotIn("- contacts 1.1.0 [also import", text)
        self.assertIn("[also import: ...]", text)  # the rule is stated once, above the list

    def test_the_real_concept_and_role_names_are_listed_with_the_three_rules(self):
        catalog = {"repository": "https://github.com/MarceloGiazzon/NPR.git", "packs": [
            {"pack": "sales", "version": "2.0.0", "needs": ["contacts"], "concepts": ["Customer", "SalesOrder"],
             "roles": ["salesRep", "accountant"],
             "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/sales@sales-2.0.0"},
            {"pack": "bare", "version": "1.0.0",
             "from": "git+https://github.com/MarceloGiazzon/NPR.git//packs/bare@bare-1.0.0"}]}
        text = engine.pack_catalog_text(catalog)
        self.assertIn("- sales 2.0.0 [also import: contacts] [concepts: Customer, SalesOrder] [roles: salesRep, accountant]", text)
        self.assertTrue(any(line == "- bare 1.0.0" for line in text.splitlines()))  # neither concepts nor roles: no marker
        for rule in ("`<pack>::<Name>`", "`provides.roleBindings`", "access rule"):
            self.assertIn(rule, text)

    def test_no_catalog_no_section(self):
        self.assertEqual(engine.pack_catalog_text(None), "")
        self.assertEqual(engine.pack_catalog_text({"packs": []}), "")

    # --- D-8: the directive, and the Manager choosing the packs that fit the request --------------------------

    @staticmethod
    def _big_catalog() -> dict:
        repo = "https://github.com/MarceloGiazzon/NPR.git"

        def pack(name, desc, concepts=(), roles=(), needs=()):
            entry = {"pack": name, "version": "1.0.0", "description": desc,
                     "from": f"git+{repo}//packs/{name}@{name}-1.0.0"}
            if concepts:
                entry["concepts"] = list(concepts)
            if roles:
                entry["roles"] = list(roles)
            if needs:
                entry["needs"] = list(needs)
            return entry

        filler = [pack(f"filler{i}", "Unrelated housekeeping tools") for i in range(10)]
        return {"repository": repo, "packs": filler + [
            pack("appointments", "Book and manage appointments", ["Appointment"], ["scheduler"], ["contacts"]),
            pack("contacts", "Organizations, people, addresses", ["Person"]),
            pack("invoicing", "Issue invoices and track payments", ["Invoice"], ["accountant"])]}

    def test_the_directive_is_in_every_catalog_section(self):
        for text in (engine.pack_catalog_text(CATALOG), engine.pack_catalog_text(CATALOG, "any ask at all")):
            self.assertIn("First match every part of the request", text)
            self.assertIn("do NOT re-declare its concepts", text)

    def test_a_small_catalog_is_listed_whole_even_with_an_ask(self):
        text = engine.pack_catalog_text(CATALOG, "contacts for my clinic")
        self.assertNotIn("Packs chosen for this request", text)
        self.assertIn("- sales 2.0.0", text)

    def test_a_big_catalog_shows_only_the_packs_that_fit_the_request_and_their_dependencies(self):
        text = engine.pack_catalog_text(self._big_catalog(), "A clinic that books appointments and sends invoices")
        self.assertIn("Packs chosen for this request (13 exist", text)
        for name in ("appointments", "invoicing", "contacts"):   # contacts: appointments needs it
            self.assertIn(f"- {name} 1.0.0", text)
        self.assertNotIn("filler", text)
        self.assertIn("Roles you must bind in `provides.roleBindings`", text)
        self.assertIn("appointments: scheduler", text)
        self.assertIn("invoicing: accountant", text)

    def test_a_request_that_matches_no_pack_falls_back_to_the_whole_list(self):
        text = engine.pack_catalog_text(self._big_catalog(), "Quero uma clinica com agenda de consultas")
        self.assertNotIn("Packs chosen for this request", text)
        self.assertIn("- filler3 1.0.0", text)

    def test_no_ask_means_the_whole_list(self):
        text = engine.pack_catalog_text(self._big_catalog())
        self.assertNotIn("Packs chosen for this request", text)
        self.assertIn("- filler9 1.0.0", text)

    def test_the_choice_is_deterministic_best_first_and_capped(self):
        catalog = self._big_catalog()
        crowded = [dict(e, description="Appointments and invoices") for e in catalog["packs"][:10]] + catalog["packs"][10:]
        first = [e["pack"] for e in engine.select_catalog_entries(crowded, "appointments invoices")]
        again = [e["pack"] for e in engine.select_catalog_entries(crowded, "appointments invoices")]
        self.assertEqual(first, again)
        self.assertEqual(first[:2], ["appointments", "invoicing"])   # id words outweigh description words
        self.assertLessEqual(len([n for n in first if n.startswith("filler")]), engine.CATALOG_PICK_MAX - 2)
        self.assertEqual(engine.select_catalog_entries(crowded, "the and for"), [])   # stop words alone pick nothing

    def test_offline_with_nothing_cached_leaves_the_bundle_alone(self):
        with mock.patch.object(npdev_cli, "_load_pack_catalog", side_effect=npdev_cli.CliError("no cache")):
            self.assertEqual(engine.with_pack_catalog("bundle\n", {"bytes": 7}), ("bundle\n", {"bytes": 7}))

    def test_the_section_is_appended_and_the_manifest_follows(self):
        with mock.patch.object(npdev_cli, "_load_pack_catalog", return_value=(CATALOG, {"status": "fresh"})):
            text, manifest = engine.with_pack_catalog("bundle\n", {"bytes": 7, "sections": []})
        self.assertTrue(text.startswith("bundle\n\n## Pack catalog"))
        self.assertEqual(manifest["bytes"], len(text.encode("utf-8")))
        self.assertEqual(manifest["sections"][-1]["title"], "Pack catalog")


def _git(cwd: Path, *cmd: str) -> None:
    subprocess.run(["git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com", *cmd],
                   cwd=str(cwd), check=True, capture_output=True, text=True)


@unittest.skipUnless(shutil.which("git") and npdev_cli.java_launcher() is not None, "git and Java are needed")
class RealResolutionTests(unittest.TestCase):
    """A real `git+file://` pack repo through the real Gradle-backed `pack update` (no network)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        repo = self.dir / "npr"
        (repo / "packs" / "widgets").mkdir(parents=True)
        (repo / "packs" / "widgets" / "pack.json").write_text(json.dumps({
            "dslVersion": "3.0.0", "pack": "widgets", "version": "1.0.0",
            "concepts": [{"name": "Widget", "fields": [{"name": "id", "type": "uuid", "id": True, "required": True}]}],
        }), encoding="utf-8")
        _git(repo, "init", "--quiet", "--initial-branch=main")
        _git(repo, "add", "-A")
        _git(repo, "commit", "--quiet", "-m", "v1")
        _git(repo, "tag", "widgets-1.0.0")
        self.prefix = f"git+{repo.resolve().as_uri()}"
        self.model = json.loads(model_with_packs({"from": f"{self.prefix}//packs/widgets@widgets-1.0.0"}))
        self.staged = self.dir / "stage" / "model.json"
        self.staged.parent.mkdir()
        self.staged.write_text(json.dumps(self.model), encoding="utf-8")
        patcher = mock.patch.dict(os.environ, {"NPDEV_PACK_CACHE_ROOT": str(self.dir / "pack-cache")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_an_unsigned_pack_outside_the_trust_rule_is_refused(self):
        with mock.patch.object(npdev_cli, "_load_pack_trust_defaults", return_value=[]):
            with self.assertRaises(files_mod.PackError) as raised:
                files_mod.resolve_packs(self.staged, self.model)
        self.assertIn("widgets", str(raised.exception))
        self.assertFalse((self.staged.parent / "npdev.lock").exists(), "a refused pack leaves no lock behind")

    def test_the_trust_rule_lets_the_same_pack_in_and_the_lock_records_it(self):
        rule = [{"id": "fixture", "match": self.prefix, "until": "2999-12-31"}]
        with mock.patch.object(npdev_cli, "_load_pack_trust_defaults", return_value=rule):
            text = files_mod.resolve_packs(self.staged, self.model)
        lock = json.loads(text)
        self.assertEqual(lock["packs"]["widgets"]["signature"]["acceptedBy"], "trust-defaults:fixture")




class UnboundRolesHintTests(unittest.TestCase):
    """REG-332 stand-in 2026-11-19: the composer names ONE unbound pack role per run; the relay names them all at once."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        cache = self.tmp / "home" / "packs" / "sha256" / "abc"
        cache.mkdir(parents=True)
        (cache / "pack.json").write_text("{\"a\": \"role('salesManager')\", \"b\": \"role('salesRep')\"}", encoding="utf-8")
        self.model_dir = self.tmp / "stage"
        (self.model_dir / "packs" / "contacts").mkdir(parents=True)
        (self.model_dir / "packs" / "contacts" / "pack.json").write_text("{\"c\": \"role('accountant')\"}", encoding="utf-8")
        lock = {"packs": {"sales": {"sourcePath": "sha256/abc/pack.json"},
                          "contacts": {"sourcePath": "packs/contacts/pack.json"}}}
        (self.model_dir / "npdev.lock").write_text(json.dumps(lock), encoding="utf-8")
        self.model_path = self.model_dir / "model.json"
        self.line = (str(cache / "pack.json") + " /packs: pack 'sales' references role('salesManager'), which the app does not "
                     "declare in provides.roleBindings.'salesManager' -- composition refuses")

    def test_every_role_the_imported_packs_name_is_listed_at_once(self):
        hint = engine._unbound_roles_hint(self.model_path, {}, [self.line])
        self.assertIsNotNone(hint)
        for name in ("accountant", "salesManager", "salesRep"):
            self.assertIn(name, hint)

    def test_roles_the_answer_already_binds_are_left_out(self):
        model = {"provides": {"roleBindings": {"salesManager": "SalesManager"}}}
        hint = engine._unbound_roles_hint(self.model_path, model, [self.line])
        self.assertIn("accountant", hint)
        self.assertNotIn("salesManager,", hint)
        self.assertNotIn("salesManager", hint.split(":")[-1])

    def test_nothing_to_add_for_a_single_missing_role_or_another_failure(self):
        model = {"provides": {"roleBindings": {"salesManager": "A", "salesRep": "B"}}}
        self.assertIsNone(engine._unbound_roles_hint(self.model_path, model, [self.line]))
        self.assertIsNone(engine._unbound_roles_hint(self.model_path, {}, ["$.concepts: something else is wrong"]))

    def test_no_lock_means_no_hint(self):
        (self.model_dir / "npdev.lock").unlink()
        self.assertIsNone(engine._unbound_roles_hint(self.model_path, {}, [self.line]))


if __name__ == "__main__":
    unittest.main()
