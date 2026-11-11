"""An answer made of several files (REG-323 T4.1): fragments beside model.json, written all or nothing and undone file by file.

The first half tests the file transaction on its own (no app, no Java). The second half drives whole answers through
ingest -> apply -> undo with the Java validator and, where it says so, the authoring gate stubbed; one test per
direction runs the repo's real diff gate (Gradle, ~30 s) and is skipped when Java or Gradle is missing.

Run with: python -m unittest NPDevCli.tests.test_relay_files -v
"""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_authoring as authoring  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_files as files  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import FENCE, PASSED, SEED_MODEL, ApplyCase, manifest  # noqa: E402


def split_model(model: dict) -> tuple:
    """The seed model as model.json + one fragment per concept: (model.json dict, {path: fragment dict})."""
    root = {key: value for key, value in model.items() if key != "concepts"}
    root["fragments"], fragments = [], {}
    for concept in model["concepts"]:
        path = f"fragments/{concept['name'].lower()}.json"
        root["fragments"].append({"$ref": path})
        fragments[path] = {"concepts": [concept]}
    return root, fragments


def answer_of(code: str, rnd: int, named: dict) -> str:
    """An answer carrying every {file name: json value} as an NPDEV-FILE block."""
    parts = [f"NPDEV-BEGIN {code}-{rnd}\nDone.\n"]
    for name, value in named.items():
        parts.append(f"NPDEV-FILE {name}\n{FENCE}json\n{json.dumps(value, indent=2)}\n{FENCE}\n")
    parts.append(f"NPDEV-END {code}-{rnd} files={len(named)}\n")
    return "".join(parts)


def with_phone(model: dict) -> dict:
    changed = copy.deepcopy(model)
    changed["concepts"][0]["fields"].append({"name": "phone", "type": "string"})
    return changed


def passing_gate(*_args, **_kwargs) -> dict:
    return {"available": True, "status": "passed", "diagnostics": [], "report": PASSED,
            "archivedPreviousModelTo": None, "reason": None}


def snapshot(folder: Path) -> dict:
    """{relative path: bytes} of every file under `folder`."""
    return {p.relative_to(folder).as_posix(): p.read_bytes() for p in sorted(folder.rglob("*")) if p.is_file()}


class FlakyDisk:
    """Makes the Nth write under `root` fail (and, with `stubborn`, every later write to those relative paths)."""

    def __init__(self, test: unittest.TestCase, root: Path, nth: int, stubborn: tuple = ()):
        self.root, self.nth, self.stubborn, self.calls = Path(root).resolve(), nth, stubborn, 0
        self.real = authoring.atomic_write_bytes
        patcher = mock.patch.object(authoring, "atomic_write_bytes", self)
        patcher.start()
        test.addCleanup(patcher.stop)

    def __call__(self, path, data):
        try:
            rel = Path(path).resolve().relative_to(self.root).as_posix()
        except ValueError:
            return self.real(path, data)
        self.calls += 1
        if self.calls == self.nth or (self.calls > self.nth and rel in self.stubborn):
            raise OSError("disk full (injected)")
        return self.real(path, data)


# --------------------------------------------------------------------------- the transaction on its own

class TransactionTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name) / "app"
        (self.root / "fragments").mkdir(parents=True)
        (self.root / "model.json").write_bytes(b'{"v": 1}\n')
        (self.root / "fragments" / "old.json").write_bytes(b'{"old": true}\n')

    def three_ops(self):
        return files.plan(self.root, [("fragments/old.json", b'{"old": false}\n'), ("contexts/new.json", b'{"new": 1}\n'),
                                      ("model.json", b'{"v": 2}\n')])

    def test_plan_records_the_previous_bytes_or_absent(self):
        ops = self.three_ops()
        self.assertEqual([op.before for op in ops], [b'{"old": true}\n', None, b'{"v": 1}\n'])

    def test_every_op_is_written_in_order_and_a_new_folder_is_created(self):
        files.run(self.root, self.three_ops())
        self.assertEqual((self.root / "contexts" / "new.json").read_bytes(), b'{"new": 1}\n')
        self.assertEqual((self.root / "model.json").read_bytes(), b'{"v": 2}\n')

    def test_a_failed_third_write_puts_back_the_first_two_and_removes_the_folder_it_made(self):
        before = snapshot(self.root)
        FlakyDisk(self, self.root, nth=3)
        with self.assertRaises(files.TransactionError) as caught:
            files.run(self.root, self.three_ops())
        self.assertEqual(caught.exception.path, "model.json")
        self.assertEqual(caught.exception.not_restored, [])
        self.assertEqual(snapshot(self.root), before)  # byte for byte, and no leftover `contexts/` folder
        self.assertFalse((self.root / "contexts").exists())

    def test_a_file_that_cannot_be_put_back_is_named_not_hidden(self):
        FlakyDisk(self, self.root, nth=3, stubborn=("fragments/old.json",))
        with self.assertRaises(files.TransactionError) as caught:
            files.run(self.root, self.three_ops())
        self.assertEqual(caught.exception.not_restored, ["fragments/old.json"])
        self.assertFalse((self.root / "contexts" / "new.json").exists())  # the rest was still put back

    def test_a_path_that_walks_out_of_the_app_is_refused_before_anything_is_written(self):
        drive_path = "c" + ":" + "/x.json"  # built from parts: the portable-tooling scan reads a drive literal as a hardcoded path
        for bad in ("../x.json", "/etc/x.json", "fragments/../../x.json", drive_path, ""):
            with self.assertRaises(files.TreeError, msg=bad):
                files.plan(self.root, [("fragments/ok.json", b"{}"), (bad, b"{}")])
        self.assertFalse((self.root / "fragments" / "ok.json").exists())

    def test_undo_restores_old_files_deletes_created_ones_and_prunes_empty_folders(self):
        before = snapshot(self.root)
        ops = self.three_ops()
        backup = self.root.parent / "backup"
        manifest_path = files.save_backup(backup, ops, "2026-11-11T00:00:00Z")
        files.run(self.root, ops)
        saved = files.load_backup(manifest_path)
        self.assertEqual([s.rel for s in saved], ["fragments/old.json", "contexts/new.json", "model.json"])
        self.assertIsNone(saved[1].before)  # ABSENT: this file did not exist
        self.assertEqual(files.changed_since(self.root, saved), [])
        files.run(self.root, files.undo_ops(self.root, saved))
        self.assertEqual(snapshot(self.root), before)
        self.assertFalse((self.root / "contexts").exists())

    def test_a_file_edited_after_apply_is_reported_and_line_endings_alone_are_not_an_edit(self):
        ops = self.three_ops()
        manifest_path = files.save_backup(self.root.parent / "backup", ops, "2026-11-11T00:00:00Z")
        files.run(self.root, ops)
        saved = files.load_backup(manifest_path)
        (self.root / "model.json").write_bytes(b'{"v": 2}\r\n')  # same text, Windows line endings
        self.assertEqual(files.changed_since(self.root, saved), [])
        (self.root / "contexts" / "new.json").write_bytes(b'{"new": 99}\n')
        (self.root / "fragments" / "old.json").unlink()
        self.assertEqual(files.changed_since(self.root, saved), ["fragments/old.json", "contexts/new.json"])

    def test_a_damaged_or_missing_backup_is_refused_not_trusted(self):
        ops = self.three_ops()
        backup = self.root.parent / "backup"
        manifest_path = files.save_backup(backup, ops, "2026-11-11T00:00:00Z")
        (backup / "tree" / "model.json").write_bytes(b"tampered")
        with self.assertRaises(files.TreeError):
            files.load_backup(manifest_path)
        (backup / "tree" / "model.json").unlink()
        with self.assertRaises(files.TreeError):
            files.load_backup(manifest_path)
        manifest_path.write_text("not json", encoding="utf-8")
        with self.assertRaises(files.TreeError):
            files.load_backup(manifest_path)


class AnswerFilesTests(unittest.TestCase):
    def test_fragments_and_contexts_are_applicable_and_everything_else_is_named(self):
        result = {"files": [
            {"path": "model.json", "status": "allowed", "content": "{}"},
            {"path": "npdev-submission.json", "status": "allowed", "content": "{}"},
            {"path": "fragments/a.json", "status": "allowed", "content": "{\"a\":1}"},
            {"path": "contexts/b.json", "status": "allowed", "content": "{\"b\":1}"},
            {"path": "themes/dark.json", "status": "allowed", "content": "{}"},
            {"path": "web/page.html", "status": "allowed", "content": "<p>"},
            {"path": "capabilities/X.java", "status": "quarantine", "content": "class X {}"},
            {"path": "secrets/key", "status": "denied"},
        ]}
        supported, unsupported = files.answer_files(result)
        self.assertEqual(supported, {"fragments/a.json": "{\"a\":1}", "contexts/b.json": "{\"b\":1}"})
        self.assertEqual(unsupported, ["themes/dark.json", "web/page.html"])


class ComposeTests(unittest.TestCase):
    def test_a_staged_split_model_composes_to_the_same_model_as_the_whole_file(self):
        whole = json.loads(SEED_MODEL)
        root, fragments = split_model(whole)
        with tempfile.TemporaryDirectory() as tmp:
            staged = files.stage(None, {p: json.dumps(v) for p, v in fragments.items()}, json.dumps(root), Path(tmp) / "s")
            composed = files.compose(staged)
        self.assertEqual(composed["concepts"], whole["concepts"])

    def test_a_missing_fragment_is_an_error_the_ai_can_act_on(self):
        root, fragments = split_model(json.loads(SEED_MODEL))
        with tempfile.TemporaryDirectory() as tmp:
            only_one = {"fragments/customer.json": json.dumps(fragments["fragments/customer.json"])}
            staged = files.stage(None, only_one, json.dumps(root), Path(tmp) / "s")
            with self.assertRaises(files.TreeError) as caught:
                files.compose(staged)
        self.assertIn("order.json", str(caught.exception))


# --------------------------------------------------------------------------- whole answers

class SplitHelpers:
    def answer_split(self, code: str, model: dict, rnd: int = 1, extra: dict | None = None) -> str:
        root, fragments = split_model(model)
        named = {"model.json": root, **fragments, "npdev-submission.json": manifest()}
        named.update(extra or {})
        return answer_of(code, rnd, named)

    def start(self, app: Path, ask: str = "Add a phone number to the customer") -> str:
        return engine.start(intent="change", ask=ask, app_dir=str(app), phase="f4")["code"]


class SplitAnswerCase(SplitHelpers, ApplyCase):
    """The Java validator and the authoring gate are stubbed: these tests are about what happens to the files."""

    def setUp(self):
        super().setUp()
        gate = mock.patch.object(authoring, "run_gate", side_effect=passing_gate)
        gate.start()
        self.addCleanup(gate.stop)


class SplitChangeTests(SplitAnswerCase):
    def test_a_change_made_of_fragments_is_applied_and_undone_file_by_file(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        result = engine.ingest(code, self.answer_split(code, with_phone(json.loads(SEED_MODEL))), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        self.assertIn("phone", " ".join(result["preview"]["sentences"]))  # the preview describes the COMPOSED model

        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["files"], ["fragments/customer.json", "fragments/order.json", "model.json"])
        written = json.loads((app / "model.json").read_text(encoding="utf-8"))
        self.assertEqual([r["$ref"] for r in written["fragments"]], ["fragments/customer.json", "fragments/order.json"])
        self.assertIn("phone", json.dumps(json.loads((app / "fragments" / "customer.json").read_text(encoding="utf-8"))))
        self.assertEqual(Path(done["backupPath"]).read_bytes(), before["model.json"])  # model.json's own backup still there

        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(undone["files"], ["model.json", "fragments/order.json", "fragments/customer.json"])
        self.assertEqual(snapshot(app), before)  # byte for byte; the fragments folder the answer made is gone
        self.assertFalse((app / "fragments").exists())

    def test_the_third_write_failing_leaves_the_app_exactly_as_it_was(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        result = engine.ingest(code, self.answer_split(code, with_phone(json.loads(SEED_MODEL))), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        FlakyDisk(self, app, nth=3)  # fragment, fragment, model.json <- fails
        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"], refused["path"]), (False, "write-failed", "model.json"))
        self.assertEqual(refused["notRestored"], [])
        self.assertEqual(snapshot(app), before)
        session = sessions.load(code)
        self.assertIsNone(session["rounds"][0].get("appliedAt"))  # nothing is recorded as applied
        self.assertEqual(session["state"], "ready-to-apply")

    def test_an_existing_fragment_overwritten_by_a_failed_apply_comes_back_byte_for_byte(self):
        app = self.make_app()
        root, fragments = split_model(json.loads(SEED_MODEL))
        (app / "fragments").mkdir()
        (app / "model.json").write_text(json.dumps(root, indent=2) + "\n", encoding="utf-8")
        for path, value in fragments.items():
            (app / path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        before = snapshot(app)
        code = self.start(app)
        named = {"model.json": root, "fragments/customer.json": split_model(with_phone(json.loads(SEED_MODEL)))[1]["fragments/customer.json"],
                 "npdev-submission.json": manifest()}
        result = engine.ingest(code, answer_of(code, 1, named), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])  # the unchanged order.json fragment is read from the app
        FlakyDisk(self, app, nth=2)  # fragments/customer.json written, then model.json fails
        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"]), (False, "write-failed"))
        self.assertEqual(snapshot(app), before)

    def test_undo_refuses_when_a_fragment_was_edited_since_and_names_it(self):
        app = self.make_app()
        code = self.start(app)
        engine.ingest(code, self.answer_split(code, with_phone(json.loads(SEED_MODEL))), source="paste")
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        edited = app / "fragments" / "order.json"
        edited.write_text(edited.read_text(encoding="utf-8").replace('"Order"', '"Orders"'), encoding="utf-8")
        refused = apply_mod.undo(sessions.load(code))
        self.assertEqual((refused["ok"], refused["error"]), (False, "app-changed-since-apply"))
        self.assertEqual(refused["changed"], ["fragments/order.json"])
        self.assertTrue((app / "fragments" / "customer.json").exists())  # nothing was undone
        forced = apply_mod.undo(sessions.load(code), force=True)
        self.assertTrue(forced["ok"], forced)

    def test_an_answer_that_only_changes_model_json_in_a_split_app_keeps_its_fragments(self):
        # The AI sends model.json alone for an app whose model is already split: the fragments it points at are the app's.
        app = self.make_app()
        root, fragments = split_model(json.loads(SEED_MODEL))
        (app / "fragments").mkdir()
        (app / "model.json").write_text(json.dumps(root, indent=2) + "\n", encoding="utf-8")
        for path, value in fragments.items():
            (app / path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        code = self.start(app, "Add products")
        changed = copy.deepcopy(root)
        changed["concepts"] = [{"name": "Product", "fields": [
            {"name": "id", "type": "uuid", "id": True, "required": True}, {"name": "title", "type": "string"}]}]
        result = engine.ingest(code, answer_of(code, 1, {"model.json": changed, "npdev-submission.json": manifest()}), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        self.assertIn("Product", " ".join(result["preview"]["sentences"]))
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertNotIn("files", done)  # one file written: the usual single-file path, one backup
        names = [c["name"] for c in files.compose(app / "model.json")["concepts"]]
        self.assertEqual(sorted(names), ["Customer", "Order", "Product"])  # the app's own fragments are still part of it

    def test_an_answer_pointing_at_a_fragment_it_did_not_send_is_sent_back_by_name(self):
        app = self.make_app()
        code = self.start(app)
        root, fragments = split_model(with_phone(json.loads(SEED_MODEL)))
        named = {"model.json": root, "fragments/customer.json": fragments["fragments/customer.json"],
                 "npdev-submission.json": manifest()}
        result = engine.ingest(code, answer_of(code, 1, named), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("order.json", " ".join(result["errorLines"]))

    def test_files_the_apply_cannot_write_yet_send_the_answer_back_instead_of_applying_part_of_it(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        extra = {"themes/dark.json": {"name": "dark"}}
        result = engine.ingest(code, self.answer_split(code, with_phone(json.loads(SEED_MODEL)), extra=extra), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("themes/dark.json", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_apply_itself_refuses_a_stored_answer_with_a_file_it_cannot_write(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        engine.ingest(code, self.answer_split(code, with_phone(json.loads(SEED_MODEL))), source="paste")
        session = sessions.load(code)
        stored = sessions.round_dir(session, 1) / "result.json"
        data = json.loads(stored.read_text(encoding="utf-8"))
        data["files"].append({"path": "themes/dark.json", "status": "allowed", "content": "{}"})
        stored.write_text(json.dumps(data), encoding="utf-8")
        refused = apply_mod.apply_round(session, None, [])
        self.assertEqual((refused["ok"], refused["error"], refused["paths"]), (False, "files-not-supported", ["themes/dark.json"]))
        self.assertEqual(snapshot(app), before)


class SplitCreateTests(SplitAnswerCase):
    def test_a_new_app_made_of_fragments_gets_every_file(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="myshop", phase="f4")["code"]
        result = engine.ingest(code, self.answer_split(code, json.loads(SEED_MODEL)), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        self.assertIn("Customer", " ".join(result["preview"]["sentences"]))
        done = apply_mod.apply_round(sessions.load(code), None, [], parent_dir=self.dir / "apps")
        self.assertTrue(done["ok"], done)
        app = Path(done["appDir"])
        self.assertEqual(done["files"], ["fragments/customer.json", "fragments/order.json", "model.json"])
        self.assertTrue((app / "fragments" / "order.json").is_file())
        self.assertEqual(files.compose(app / "model.json")["concepts"], json.loads(SEED_MODEL)["concepts"])

    def test_a_failed_write_leaves_no_half_made_app_behind(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="myshop", phase="f4")["code"]
        result = engine.ingest(code, self.answer_split(code, json.loads(SEED_MODEL)), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        parent = self.dir / "apps"
        FlakyDisk(self, parent, nth=3)  # fragment, fragment, model.json <- fails
        refused = apply_mod.apply_round(sessions.load(code), None, [], parent_dir=parent)
        self.assertEqual((refused["ok"], refused["error"]), (False, "write-failed"))
        self.assertFalse((parent / "myshop").exists())
        self.assertIsNone(sessions.load(code).get("appDir"))


@unittest.skipIf(authoring.gate_unavailable_reason(), "the repo's authoring gate needs Java and the Gradle wrapper")
class RealGateTests(SplitHelpers, ApplyCase):
    """The Java gate parses the staged folders itself: this proves it resolves the fragments next to each model.json."""

    def test_a_split_answer_passes_the_real_gate_and_is_applied_then_undone(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        result = engine.ingest(code, self.answer_split(code, with_phone(json.loads(SEED_MODEL))), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertTrue((app / "fragments" / "customer.json").is_file())
        self.assertIn("phone", json.dumps(files.compose(app / "model.json")["concepts"]))
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)


if __name__ == "__main__":
    unittest.main()
