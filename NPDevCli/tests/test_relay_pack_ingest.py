"""P4.3: what NPDev checks in a browser AI's pack answer (intent "pack"): id, shape, no secrets or bindings, the
version bump, and that an application importing the pack still validates.

The model validator is a stub in most tests (fast, no Java); the last class runs the real one (~15-30 s) and skips
itself when this machine cannot.

Run with: python -m unittest NPDevCli.tests.test_relay_pack_ingest -v
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_engine as engine  # noqa: E402
from test_relay_engine import FAILED, PASSED, EngineCase  # noqa: E402

FENCE = "`" * 3
FIXTURE = (HERE / "fixtures" / "relay" / "example-pack-answer.txt").read_text(encoding="utf-8")
GOOD = json.loads(FIXTURE.split("NPDEV-FILE pack.json\n" + FENCE + "json\n", 1)[1].split("\n" + FENCE, 1)[0])


def pack_answer(code: str, pack: dict, example: dict | None = None) -> str:
    blocks = [f"NPDEV-FILE pack.json\n{FENCE}json\n{json.dumps(pack, indent=2)}\n{FENCE}\n",
              f"NPDEV-FILE npdev-submission.json\n{FENCE}json\n"
              + json.dumps({"request": "x", "packId": pack.get("pack"), "packVersion": pack.get("version")}) + f"\n{FENCE}\n"]
    if example is not None:
        blocks.append(f"NPDEV-FILE example-model.json\n{FENCE}json\n{json.dumps(example)}\n{FENCE}\n")
    return f"NPDEV-BEGIN {code}-1\nDone.\n" + "".join(blocks) + f"NPDEV-END {code}-1 files={len(blocks)}\n"


class IngestPackTests(EngineCase):
    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(engine, "run_validation", return_value=PASSED)
        self.validator = patcher.start()
        self.addCleanup(patcher.stop)

    def ingest(self, pack, *, example=None, base=None):
        base_file = None
        if base is not None:
            base_file = self.dir / "base.json"
            base_file.write_text(json.dumps(base), encoding="utf-8")
        started = engine.start(intent="pack", ask="ratings", pack_id="ratings",
                               from_pack=str(base_file) if base_file else None)
        return engine.ingest(started["code"], pack_answer(started["code"], pack, example))

    @staticmethod
    def with_(**changes):
        pack = dict(GOOD)
        for key, value in changes.items():
            if value is None:
                pack.pop(key, None)
            else:
                pack[key] = value
        return pack

    def assert_needs_fix(self, result, *words):
        self.assertEqual(result["outcome"], "NEEDS_FIX", result.get("errorLines"))
        text = "\n".join(result["errorLines"])
        for word in words:
            self.assertIn(word, text)

    def test_a_good_pack_is_ready_and_the_probe_imports_it_by_local_ref(self):
        result = self.ingest(GOOD)
        self.assertEqual(result["outcome"], "READY")
        self.assertEqual(result["next"]["kind"], "apply")
        self.assertEqual((result["pack"]["id"], result["pack"]["version"]), ("ratings", "1.0.0"))
        probe = Path(self.validator.call_args_list[0].args[0])
        self.assertEqual(json.loads(probe.read_text(encoding="utf-8"))["packs"][0]["$ref"], "packs/ratings/pack.json")
        self.assertTrue((probe.parent / "packs" / "ratings" / "pack.json").is_file())

    def test_a_pack_for_another_id_is_refused(self):
        self.assert_needs_fix(self.ingest(self.with_(pack="other")), '"ratings"', "other")

    def test_a_missing_required_field_and_a_bad_version_are_refused(self):
        self.assert_needs_fix(self.ingest(self.with_(dslVersion=None)), "dslVersion")
        self.assert_needs_fix(self.ingest(self.with_(version="1.0")), "version")

    def test_bindings_and_npdev_owned_fields_are_refused(self):
        self.assert_needs_fix(self.ingest(self.with_(bindings=[{"capability": "x", "adapter": "y"}])), "bindings")
        self.assert_needs_fix(self.ingest(self.with_(signature={"x": 1})), "signature")

    def test_a_secret_is_refused(self):
        secret = "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789"
        self.assert_needs_fix(self.ingest(self.with_(description=secret)), "secret")

    def test_a_pack_that_does_not_compose_is_needs_fix_with_the_validators_lines(self):
        self.validator.return_value = FAILED
        self.assert_needs_fix(self.ingest(GOOD), "type is not allowed")

    def test_the_followup_for_a_pack_asks_for_a_pack_not_a_model(self):
        result = self.ingest(self.with_(pack="other"))
        self.assertIn("pack.json", result["next"]["prompt"])
        self.assertNotIn("your model", result["next"]["prompt"])
        self.assertIn("pack.json", result["next"]["freshChatPrompt"])

    def test_an_unreachable_validator_keeps_parsed(self):
        self.validator.return_value = None
        self.assertEqual(self.ingest(GOOD)["outcome"], "PARSED")

    def test_the_ais_example_model_is_validated_too(self):
        example = {"namespace": "x", "dslVersion": "1.0.0", "version": "1.0",
                   "packs": [{"$ref": "packs/ratings/pack.json", "as": "r"}]}
        self.assertEqual(self.ingest(GOOD, example=example)["outcome"], "READY")
        self.assertEqual(self.validator.call_count, 2)

    def test_a_new_version_must_be_higher(self):
        self.assert_needs_fix(self.ingest(GOOD, base=GOOD), "must be higher")

    def test_a_new_version_the_publish_gate_refuses_is_needs_fix_with_its_reason(self):
        newer = self.with_(version="1.0.1")
        report = {"allowed": False, "message": "BREAKING change needs a major version bump"}

        def gate(args):
            Path(args.out).write_text(json.dumps(report), encoding="utf-8")
            return 2

        with mock.patch.object(engine._cli_module(), "run_pack_publish", side_effect=gate):
            self.assert_needs_fix(self.ingest(newer, base=GOOD), "major version bump")
            report.update(allowed=True, message="ok")
            self.assertEqual(self.ingest(newer, base=GOOD)["outcome"], "READY")

    # P4.4: a READY pack carries a plain-words preview (the same shape an app preview has), in the person's language.
    def test_a_ready_pack_has_a_plain_preview_with_what_it_holds_and_who_may_see_it(self):
        preview = self.ingest(GOOD)["preview"]
        text = " ".join(preview["sentences"])
        self.assertIn('"ratings" (version 1.0.0)', text)
        self.assertIn("Nothing is replaced", text)
        self.assertIn("Rating: 5 fields", text)
        self.assertIn("seen by only the people a rule allows", text)
        self.assertIn("Search RatingsOfSubject", text)
        self.assertEqual(len(preview["lines"]), len(preview["sentences"]))
        self.assertFalse(preview["dataLoss"])
        self.assertNotIn("$user", text)  # no expression syntax reaches a person

    def test_the_preview_is_written_in_portuguese_when_the_session_asks_for_it(self):
        started = engine.start(intent="pack", ask="ratings", pack_id="ratings", language="Portuguese (Brazil)")
        result = engine.ingest(started["code"], pack_answer(started["code"], GOOD))
        text = " ".join(result["preview"]["sentences"])
        self.assertIn('Um pacote reutilizável "ratings" (versão 1.0.0)', text)
        self.assertIn("Nada é substituído", text)
        self.assertIn("Rating: 5 campos", text)
        self.assertIn("visto por só as pessoas que uma regra permite", text)
        self.assertIn("Busca RatingsOfSubject", text)
        self.assertEqual([l["code"] for l in result["preview"]["lines"]][:2], ["PACK_SUMMARY", "PACK_NEW"])

    def test_a_new_version_shows_what_changed_and_warns_when_it_is_breaking(self):
        newer = self.with_(version="2.0.0")
        report = {"allowed": True, "message": "ok", "overallClassification": "BREAKING",
                  "findings": [{"section": "concepts", "path": "Rating.stars", "classification": "BREAKING", "message": "field stars removed"},
                               {"section": "queries", "path": "Q", "classification": "ADDITIVE", "message": "query Q added"}]}

        def gate(args):
            Path(args.out).write_text(json.dumps(report), encoding="utf-8")
            return 0

        with mock.patch.object(engine._cli_module(), "run_pack_publish", side_effect=gate):
            result = self.ingest(newer, base=GOOD)
        self.assertEqual(result["outcome"], "READY")
        self.assertEqual((result["pack"]["baseVersion"], result["pack"]["gate"]), ("1.0.0", "BREAKING"))
        text = " ".join(result["preview"]["sentences"])
        self.assertIn("Version 1.0.0 becomes 2.0.0", text)
        self.assertIn("field stars removed", text)
        self.assertEqual([w["code"] for w in result["preview"]["warnings"]], ["PACK_BREAKING"])

    def test_the_rules_sheet_core_the_script_now_imports_still_renders_a_page(self):
        import npdev_pack_sheet

        page = npdev_pack_sheet.render(GOOD)
        self.assertIn("<h1>ratings pack", page)
        self.assertIn("Rating", page)


class RealValidatorTests(EngineCase):
    def setUp(self):
        super().setUp()  # EngineCase stubs the validator; put the real one back for this class
        real = mock.patch.object(engine, "run_validation", self.validation.temp_original)
        real.start()
        self.addCleanup(real.stop)

    def test_the_example_pack_answer_is_ready_with_the_real_validator(self):
        started = engine.start(intent="pack", ask="ratings", pack_id="ratings")
        result = engine.ingest(started["code"], FIXTURE.replace("K7QX", started["code"]))
        if result["outcome"] == "PARSED":
            self.skipTest("the model validator cannot run on this machine")
        self.assertEqual((result["outcome"], result.get("errorLines")), ("READY", None))


if __name__ == "__main__":
    unittest.main()


class PackApplyTests(EngineCase):
    """P4.5: apply writes pack.json into the pack workspace (all or nothing, with a backup); undo takes it back."""

    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(engine, "run_validation", return_value=PASSED)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.workspace = self.dir / "packs"

    def ingest(self, pack, *, base=None):
        base_file = None
        if base is not None:
            base_file = self.dir / "base.json"
            base_file.write_text(json.dumps(base), encoding="utf-8")
        started = engine.start(intent="pack", ask="ratings", pack_id="ratings",
                               from_pack=str(base_file) if base_file else None)
        return engine.ingest(started["code"], pack_answer(started["code"], pack))

    @staticmethod
    def with_(**changes):
        return {**GOOD, **changes}

    def ready(self, pack=None, *, base=None):
        result = self.ingest(pack or GOOD, base=base)
        self.assertEqual(result["outcome"], "READY")
        return engine.load_session(result["session"]["expected"])

    def test_apply_writes_the_pack_into_its_folder_and_undo_removes_it(self):
        import npdev_relay_apply as apply_mod

        session = self.ready()
        done = apply_mod.apply_round(session, None, [], parent_dir=str(self.workspace))
        self.assertTrue(done["ok"], done)
        written = self.workspace / "ratings" / "pack.json"
        self.assertEqual(json.loads(written.read_text(encoding="utf-8"))["pack"], "ratings")
        self.assertEqual(done["packPath"], str(written))
        undone = apply_mod.undo(engine.load_session(session["code"]))
        self.assertTrue(undone["ok"], undone)
        self.assertFalse(written.exists())
        self.assertFalse((self.workspace / "ratings").exists())

    def test_apply_needs_the_packs_folder_and_never_overwrites_an_existing_pack(self):
        import npdev_relay_apply as apply_mod

        session = self.ready()
        self.assertEqual(apply_mod.apply_round(session, None, [])["error"], "no-target")
        (self.workspace / "ratings").mkdir(parents=True)
        (self.workspace / "ratings" / "pack.json").write_text("{}", encoding="utf-8")
        self.assertEqual(apply_mod.apply_round(session, None, [], parent_dir=str(self.workspace))["error"], "pack-exists")
        self.assertEqual((self.workspace / "ratings" / "pack.json").read_text(encoding="utf-8"), "{}")

    def test_a_new_version_replaces_the_old_one_and_undo_puts_it_back(self):
        import npdev_relay_apply as apply_mod

        (self.workspace / "ratings").mkdir(parents=True)
        target = self.workspace / "ratings" / "pack.json"
        target.write_text(json.dumps(GOOD), encoding="utf-8")
        newer = self.with_(version="1.1.0")
        report = {"allowed": True, "message": "ok", "overallClassification": "ADDITIVE", "findings": []}

        def gate(args):
            Path(args.out).write_text(json.dumps(report), encoding="utf-8")
            return 0

        with mock.patch.object(engine._cli_module(), "run_pack_publish", side_effect=gate):
            session = self.ready(newer, base=GOOD)
        self.assertTrue(apply_mod.apply_round(session, None, [], parent_dir=str(self.workspace))["ok"])
        self.assertEqual(json.loads(target.read_text(encoding="utf-8"))["version"], "1.1.0")
        self.assertTrue(apply_mod.undo(engine.load_session(session["code"]))["ok"])
        self.assertEqual(json.loads(target.read_text(encoding="utf-8"))["version"], "1.0.0")

    def test_a_pack_edited_after_the_conversation_began_is_not_overwritten(self):
        import npdev_relay_apply as apply_mod

        (self.workspace / "ratings").mkdir(parents=True)
        target = self.workspace / "ratings" / "pack.json"
        target.write_text(json.dumps(GOOD), encoding="utf-8")
        newer = self.with_(version="1.1.0")
        report = {"allowed": True, "message": "ok", "findings": []}

        def gate(args):
            Path(args.out).write_text(json.dumps(report), encoding="utf-8")
            return 0

        with mock.patch.object(engine._cli_module(), "run_pack_publish", side_effect=gate):
            session = self.ready(newer, base=GOOD)
        target.write_text(json.dumps(self.with_(description="edited by hand")), encoding="utf-8")
        refused = apply_mod.apply_round(session, None, [], parent_dir=str(self.workspace))
        self.assertEqual((refused["ok"], refused["error"], refused["baseMoved"]), (False, "not-ready", True))

    def test_undo_refuses_when_the_pack_was_edited_since_apply(self):
        import npdev_relay_apply as apply_mod

        session = self.ready()
        apply_mod.apply_round(session, None, [], parent_dir=str(self.workspace))
        (self.workspace / "ratings" / "pack.json").write_text("{}", encoding="utf-8")
        refused = apply_mod.undo(engine.load_session(session["code"]))
        self.assertEqual(refused["error"], "app-changed-since-apply")
        self.assertTrue(apply_mod.undo(engine.load_session(session["code"]), force=True)["ok"])
