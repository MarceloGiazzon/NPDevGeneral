"""Applying an accepted browser-AI answer: create the app, change it with a backup, take it back.

Create uses the real `npdev init` scaffold (in a temp folder) with the Java validator stubbed. The change tests
run the repo's real authoring diff gate (Gradle, ~10-30 s each) and are skipped when Java or Gradle is missing.

Run with: python -m unittest NPDevCli.tests.test_relay_apply -v
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_authoring as authoring  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402

SEED_MODEL = (REPO / "NPDevSamples" / "npdev-init-seed" / "model.json").read_text(encoding="utf-8")
FENCE = "`" * 3
PASSED = {"status": "passed", "summary": {"errors": 0, "warnings": 0}, "diagnostics": []}


def answer(code: str, rnd: int, model: dict, manifest: dict) -> str:
    return (f"NPDEV-BEGIN {code}-{rnd}\nDone.\nNPDEV-FILE model.json\n{FENCE}json\n{json.dumps(model, indent=2)}\n{FENCE}\n"
            f"NPDEV-FILE npdev-submission.json\n{FENCE}json\n{json.dumps(manifest)}\n{FENCE}\n"
            f"NPDEV-END {code}-{rnd} files=2\n")


def manifest(**extra) -> dict:
    base = {"request": "change", "renames": [], "deliberateRemovals": [], "securityChanges": [],
            "couldNotExpress": [], "assumptions": []}
    base.update(extra)
    return base


class ApplyCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        env = mock.patch.dict(os.environ, {"NPDEV_AI_CONFIG": str(self.dir / "ai.json")})
        env.start()
        self.addCleanup(env.stop)
        knowledge = mock.patch.object(engine, "build_knowledge", return_value=("# core\n", {"contentSha256": "a" * 64}))
        knowledge.start()
        self.addCleanup(knowledge.stop)
        validation = mock.patch.object(engine, "run_validation", return_value=PASSED)
        validation.start()
        self.addCleanup(validation.stop)

    def make_app(self, name: str = "shop") -> Path:
        folder = self.dir / name
        folder.mkdir()
        (folder / "model.json").write_text(SEED_MODEL, encoding="utf-8")
        return folder


class CreateTests(ApplyCase):
    def test_a_ready_create_answer_becomes_an_app_with_the_ais_model(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="myshop")["code"]
        model = json.loads(SEED_MODEL)
        model["namespace"] = "my.shop"
        result = engine.ingest(code, answer(code, 1, model, manifest()), source="paste")
        self.assertEqual(result["outcome"], "READY")
        session = sessions.load(code)
        done = apply_mod.apply_round(session, None, [], parent_dir=str(self.dir / "apps"))
        self.assertTrue(done["ok"], done)
        self.assertTrue(done["created"])
        written = json.loads((self.dir / "apps" / "myshop" / "model.json").read_text(encoding="utf-8"))
        self.assertEqual(written["namespace"], "my.shop")
        self.assertTrue((self.dir / "apps" / "myshop" / "config.json").is_file())  # the scaffold's own files
        reloaded = sessions.load(code)
        self.assertEqual(reloaded["state"], "done")
        self.assertEqual(reloaded["appDir"], str(self.dir / "apps" / "myshop"))
        self.assertTrue(reloaded["rounds"][0]["appliedAt"])

    def test_applying_twice_is_refused(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="again")["code"]
        engine.ingest(code, answer(code, 1, json.loads(SEED_MODEL), manifest()), source="paste")
        apply_mod.apply_round(sessions.load(code), None, [], parent_dir=str(self.dir / "apps"))
        second = apply_mod.apply_round(sessions.load(code), None, [], parent_dir=str(self.dir / "apps"))
        self.assertFalse(second["ok"])
        self.assertEqual(second["error"], "already-applied")

    def test_a_create_without_a_place_to_go_writes_nothing(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="nowhere")["code"]
        engine.ingest(code, answer(code, 1, json.loads(SEED_MODEL), manifest()), source="paste")
        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"]), (False, "no-target"))

    def test_a_new_apps_name_cannot_walk_out_of_its_parent_folder(self):
        for bad in ["../escaped", "a/b", "a\\b", "C:x", "..", ".hidden", "-flag", "x" * 101]:
            code = engine.start(intent="create", ask="A shop", new_app_name=bad)["code"]
            engine.ingest(code, answer(code, 1, json.loads(SEED_MODEL), manifest()), source="paste")
            refused = apply_mod.apply_round(sessions.load(code), None, [], parent_dir=str(self.dir / "apps"))
            self.assertEqual((refused["ok"], refused["error"]), (False, "bad-name"), bad)
        self.assertFalse((self.dir / "apps").exists(), "a refused name wrote nothing")
        self.assertFalse((self.dir / "escaped").exists())
        for fine in ["Shop", "Bike-shop", "oficina_2", "Mercadão", "My shop"]:
            self.assertTrue(apply_mod._plain_folder_name(fine), fine)

    def test_an_answer_that_is_not_ready_is_never_applied(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="notready")["code"]
        engine.ingest(code, "Sorry, I cannot do that.", source="paste")
        refused = apply_mod.apply_round(sessions.load(code), 1, [], parent_dir=str(self.dir / "apps"))
        self.assertEqual((refused["ok"], refused["error"]), (False, "not-ready"))
        self.assertFalse((self.dir / "apps").exists())

    def test_create_undo_is_not_offered(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="keep")["code"]
        engine.ingest(code, answer(code, 1, json.loads(SEED_MODEL), manifest()), source="paste")
        apply_mod.apply_round(sessions.load(code), None, [], parent_dir=str(self.dir / "apps"))
        refused = apply_mod.undo(sessions.load(code))
        self.assertEqual(refused["error"], "create-undo-unsupported")
        self.assertTrue((self.dir / "apps" / "keep" / "model.json").is_file())


@unittest.skipIf(authoring.gate_unavailable_reason() is not None, "the authoring gate needs Java and the Gradle wrapper")
class ChangeTests(ApplyCase):
    def test_a_change_is_applied_with_a_backup_and_undone_byte_for_byte(self):
        app = self.make_app()
        original = (app / "model.json").read_bytes()
        code = engine.start(intent="change", ask="Add a phone number to the customer", app_dir=str(app))["code"]
        model = json.loads(SEED_MODEL)
        model["concepts"][0]["fields"].append({"name": "phone", "type": "string"})
        result = engine.ingest(code, answer(code, 1, model, manifest()), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        self.assertIn("phone", " ".join(result["preview"]["sentences"]))
        self.assertEqual(result["preview"]["warnings"], [])

        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        changed = json.loads((app / "model.json").read_text(encoding="utf-8"))
        self.assertIn("phone", [f["name"] for f in changed["concepts"][0]["fields"]])
        self.assertNotEqual(changed["version"], json.loads(SEED_MODEL)["version"])
        self.assertEqual(Path(done["backupPath"]).read_bytes(), original)

        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual((app / "model.json").read_bytes(), original)

    def test_a_removal_needs_the_owners_yes_before_anything_is_written(self):
        app = self.make_app()
        original = (app / "model.json").read_bytes()
        code = engine.start(intent="change", ask="Drop the last name", app_dir=str(app))["code"]
        model = json.loads(SEED_MODEL)
        model["concepts"][0]["fields"] = [f for f in model["concepts"][0]["fields"] if f["name"] != "lastName"]
        removal = [{"kind": "field", "concept": "Customer", "name": "lastName", "reason": "not needed"}]
        result = engine.ingest(code, answer(code, 1, model, manifest(deliberateRemovals=removal)), source="paste")
        self.assertEqual(result["outcome"], "READY", result["findings"])
        needs = [w["code"] for w in result["preview"]["warnings"] if w["needsConfirmation"]]
        self.assertEqual(needs, ["REMOVAL:field:Customer.lastName"])
        self.assertTrue(result["preview"]["dataLoss"])

        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"]), (False, "confirmation-required"))
        self.assertEqual(refused["missing"], needs)
        self.assertEqual((app / "model.json").read_bytes(), original)

        done = apply_mod.apply_round(sessions.load(code), None, needs)
        self.assertTrue(done["ok"], done)
        changed = json.loads((app / "model.json").read_text(encoding="utf-8"))
        self.assertNotIn("lastName", [f["name"] for f in changed["concepts"][0]["fields"]])

    def test_the_app_changing_after_the_answer_is_not_overwritten(self):
        app = self.make_app()
        code = engine.start(intent="change", ask="Add a phone number", app_dir=str(app))["code"]
        edited = json.loads(SEED_MODEL)
        edited["description"] = "edited by the owner meanwhile"
        (app / "model.json").write_text(json.dumps(edited), encoding="utf-8")
        model = json.loads(SEED_MODEL)
        model["concepts"][0]["fields"].append({"name": "phone", "type": "string"})
        result = engine.ingest(code, answer(code, 1, model, manifest()), source="paste")
        self.assertNotEqual(result["outcome"], "READY")
        self.assertTrue(result.get("baseMoved"))
        self.assertEqual(json.loads((app / "model.json").read_text(encoding="utf-8"))["description"],
                         "edited by the owner meanwhile")


if __name__ == "__main__":
    unittest.main()
