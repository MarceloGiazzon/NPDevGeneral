"""A config proposal the owner confirms is applied, and undone (REG-323 T4.5b).

The first knobs with an allowlist entry are the app's theme, `ui.theme.name` and `ui.theme.choices`: flat keys of config.json's
`defaults` envelope, written by the CLI's own `npdev config set-default`. Part 1 tests that writer (a pure function, the
command and the verb through a real process). Part 2 tests how a proposal is sorted with a theme catalog behind it. Part 3
drives whole answers through ingest -> apply (with and without the owner's confirmation) -> undo, including a write that
fails half way, a stored answer someone edited, an app with no config.json and a brand-new app.

Run with: python -m unittest NPDevCli.tests.test_relay_config_apply -v
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_config_defaults as defaults  # noqa: E402
import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_config as config  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import SEED_MODEL, ApplyCase, answer, manifest  # noqa: E402
from test_relay_files import FlakyDisk, SplitAnswerCase, answer_of, snapshot  # noqa: E402
from test_relay_themes import SUNNY, seed, theme  # noqa: E402

CONFIG_TEXT = '{\n  "scenario": { "name": "shop" },\n  "defaults": { "ui.theme.mode": "light" }\n}\n'
CONFIRM_NAME = "config:ui.theme.name"
CONFIRM_CHOICES = "config:ui.theme.choices"


def change(prop: str, value, **extra) -> dict:
    return {"property": prop, "value": value, **extra}


def proposal(*changes) -> dict:
    return {"changes": list(changes)}


def defaults_of(folder: Path) -> dict:
    return json.loads((Path(folder) / "config.json").read_text(encoding="utf-8")).get("defaults") or {}


class WriterTests(unittest.TestCase):
    def test_a_missing_defaults_envelope_is_made_and_everything_else_is_kept(self):
        text = '{\n    "scenario": {"name": "x"},\n    "runtime": {"serverPort": 8080}\n}\n'
        out = defaults.set_default_text(text, "ui.theme.name", "sunny")
        parsed = json.loads(out)
        self.assertEqual(parsed["defaults"], {"ui.theme.name": "sunny"})
        self.assertEqual((parsed["scenario"], parsed["runtime"]), ({"name": "x"}, {"serverPort": 8080}))
        self.assertEqual(list(parsed), ["scenario", "runtime", "defaults"])          # key order kept, the new one last
        self.assertTrue(out.startswith('{\n    "scenario"'))                          # the file's 4-space indent kept
        self.assertTrue(out.endswith("}\n"))

    def test_an_existing_key_is_replaced_in_place_and_the_other_defaults_stay(self):
        out = defaults.set_default_text(CONFIG_TEXT, "ui.theme.mode", "dark")
        self.assertEqual(json.loads(out)["defaults"], {"ui.theme.mode": "dark"})
        out = defaults.set_default_text(CONFIG_TEXT, "ui.theme.name", "retro")
        self.assertEqual(json.loads(out)["defaults"], {"ui.theme.mode": "light", "ui.theme.name": "retro"})

    def test_setting_the_value_it_already_has_changes_nothing_at_all(self):
        self.assertEqual(defaults.set_default_text(CONFIG_TEXT, "ui.theme.mode", "light"), CONFIG_TEXT)
        self.assertNotEqual(defaults.set_default_text('{"defaults": {"a.b": 1}}', "a.b", True), '{"defaults": {"a.b": 1}}')

    def test_line_endings_and_a_missing_final_newline_are_kept(self):
        crlf = CONFIG_TEXT.replace("\n", "\r\n")
        out = defaults.set_default_text(crlf, "ui.theme.name", "retro")
        self.assertNotIn("\n", out.replace("\r\n", ""))
        self.assertFalse(defaults.set_default_text(CONFIG_TEXT.rstrip("\n"), "ui.theme.name", "x").endswith("\n"))

    def test_a_wrong_shape_is_refused_in_plain_words(self):
        for key in ("theme", "ui..name", "Ui.theme", "ui.theme name", "../x.y", "", None, 7):
            with self.assertRaises(ValueError, msg=repr(key)) as caught:
                defaults.set_default_text(CONFIG_TEXT, key, "x")
            self.assertIn("not a setting id", str(caught.exception))
        for value in (None, [], {}, ["a"]):
            with self.assertRaises(ValueError):
                defaults.set_default_text(CONFIG_TEXT, "ui.theme.name", value)
        for text, why in (("{nope", "not valid JSON"), ("[1]", "not a JSON object"), ('{"defaults": []}', "is not an object")):
            with self.assertRaises(ValueError) as caught:
                defaults.set_default_text(text, "ui.theme.name", "x")
            self.assertIn(why, str(caught.exception))

    def test_the_command_writes_atomically_and_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(CONFIG_TEXT, encoding="utf-8")
            report = defaults.run(tmp, "ui.theme.name", "retro", dry_run=True)
            self.assertEqual((report["changed"], report["dryRun"]), (True, True))
            self.assertEqual(path.read_text(encoding="utf-8"), CONFIG_TEXT)
            report = defaults.run(tmp, "ui.theme.name", "retro")
            self.assertTrue(report["changed"])
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["defaults"]["ui.theme.name"], "retro")
            self.assertFalse(defaults.run(path, "ui.theme.name", "retro")["changed"])   # a path to the file works too
            self.assertEqual([p.name for p in Path(tmp).iterdir()], ["config.json"])      # no temp file left behind
            with self.assertRaises(ValueError):
                defaults.run(Path(tmp) / "nowhere", "ui.theme.name", "x")

    def test_the_value_types_the_command_line_can_carry(self):
        self.assertEqual(defaults.convert("12", "integer"), 12)
        self.assertEqual(defaults.convert("1.5", "number"), 1.5)
        self.assertIs(defaults.convert("TRUE", "boolean"), True)
        self.assertEqual(defaults.convert("true", "string"), "true")
        for raw, kind in (("x", "integer"), ("x", "number"), ("yes", "boolean")):
            with self.assertRaises(ValueError):
                defaults.convert(raw, kind)

    def test_the_verb_works_through_a_real_process_and_says_no_in_words(self):
        cli = str(REPO / "NPDevCli" / "npdev_cli.py")
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "config.json").write_text(CONFIG_TEXT, encoding="utf-8")
            ok = subprocess.run([sys.executable, cli, "config", "set-default", "--app", tmp, "--key", "ui.theme.name",
                                 "--value", "retro"], capture_output=True, text=True)
            self.assertEqual(ok.returncode, 0, ok.stderr)
            self.assertEqual(json.loads(ok.stdout)["changed"], True)
            self.assertEqual(defaults_of(Path(tmp))["ui.theme.name"], "retro")
            bad = subprocess.run([sys.executable, cli, "config", "set-default", "--app", tmp, "--key", "bad key",
                                  "--value", "x"], capture_output=True, text=True)
            self.assertNotEqual(bad.returncode, 0)
            self.assertIn("not a setting id", bad.stdout + bad.stderr)


class SortWithThemesTests(unittest.TestCase):
    CONTEXT = {"themes": {"retro", "npdev-dark", "sunny"}}

    def sort(self, *changes, context="default"):
        return config.evaluate(proposal(*changes), context=self.CONTEXT if context == "default" else context)["items"]

    def test_the_two_knobs_have_entries_a_writer_and_are_never_on_the_refused_list(self):
        allowlist = config.load_allowlist()
        self.assertEqual([e["property"] for e in allowlist["entries"]], ["ui.theme.name", "ui.theme.choices"])
        for entry in allowlist["entries"]:
            self.assertIn(entry["writer"], config.WRITERS)
            self.assertFalse(config._never(entry["property"], allowlist), entry["property"])

    def test_a_theme_that_exists_is_applicable_and_carries_its_confirmation_code_and_value(self):
        (item,) = self.sort(change("ui.theme.name", "sunny"))
        self.assertEqual((item["status"], item["reason"], item["value"]), ("applicable", "allowlisted", "sunny"))
        self.assertEqual(item["confirmCode"], CONFIRM_NAME)
        self.assertIn('"sunny"', item["preview"])
        self.assertIn("you can undo it", item["preview"])

    def test_the_property_written_the_ways_an_ai_writes_it_is_one_property_and_the_entrys_spelling_is_used(self):
        for written in ("ui.theme.name", "defaults.ui.theme.name", 'defaults["ui.theme.name"]', "config.json:defaults.ui.theme.name",
                        "UI.Theme.Name"):
            (item,) = self.sort(change(written, "retro"))
            self.assertEqual((item["status"], item["property"]), ("applicable", "ui.theme.name"), written)

    def test_an_unknown_theme_is_only_a_suggestion_and_nothing_is_offered(self):
        for value in ("midnight", "", 5, None, ["retro"], "retro,midnight"):
            (item,) = self.sort(change("ui.theme.name", value))
            self.assertEqual((item["status"], item["reason"]), ("suggestion", "value-not-allowed"), value)
            self.assertNotIn("confirmCode", item)
        (item,) = self.sort(change("ui.theme.name", "sunny"), context=None)   # no catalog to check against: never offered
        self.assertEqual(item["status"], "suggestion")

    def test_choices_take_a_list_or_the_star(self):
        for value in ("retro,sunny", " retro , npdev-dark ", "*"):
            (item,) = self.sort(change("ui.theme.choices", value))
            self.assertEqual((item["status"], item["confirmCode"]), ("applicable", CONFIRM_CHOICES), value)
        for value in ("retro,,sunny", ",", "retro,midnight", " "):
            (item,) = self.sort(change("ui.theme.choices", value))
            self.assertEqual(item["status"], "suggestion", value)

    def test_the_other_settings_of_the_same_file_are_still_suggestions_or_refused(self):
        items = self.sort(change("ui.theme.mode", "dark"), change("database.password", "x"), change("ui.density", "compact"))
        self.assertEqual([(i["property"], i["status"]) for i in items],
                         [("ui.theme.mode", "suggestion"), ("database.password", "refused"), ("ui.density", "suggestion")])


class ConfigAnswerCase(SplitAnswerCase):
    """phase f4, the authoring gate stubbed (these tests are about what happens to config.json)."""

    def make_configured_app(self, text: str = CONFIG_TEXT) -> Path:
        app = self.make_app()
        (app / "config.json").write_text(text, encoding="utf-8")
        return app

    def ingest(self, app: Path, named: dict) -> tuple:
        code = self.start(app, ask="Use a warmer theme")
        return code, engine.ingest(code, answer_of(code, 1, named), source="paste")

    def picks(self, value="sunny", *, with_theme=True, model=None, prop="ui.theme.name") -> dict:
        named = {"model.json": model if model is not None else seed(), "npdev-submission.json": manifest(),
                 "npdev-config-proposal.json": proposal(change(prop, value, why="the owner asked for it"))}
        if with_theme:
            named["themes/sunny.json"] = SUNNY
        return named


class ConfigApplyTests(ConfigAnswerCase):
    def test_an_answer_that_adds_a_theme_and_proposes_it_is_ready_offers_the_change_and_does_not_warn_about_an_unused_theme(self):
        app = self.make_configured_app()
        before = snapshot(app)
        _, result = self.ingest(app, self.picks())
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        (item,) = result["configProposals"]
        self.assertEqual((item["status"], item["value"], item["confirmCode"]), ("applicable", "sunny", CONFIRM_NAME))
        codes = [f["code"] for f in result["findings"]]
        self.assertIn("config-change-offered", codes)
        self.assertNotIn("config-not-applied", codes)
        self.assertNotIn("theme-not-selected", codes)   # a proposal on the table counts as the theme being selected
        self.assertEqual(snapshot(app), before)

    def test_without_the_owners_confirmation_the_theme_is_saved_and_config_json_is_not_touched(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, _ = self.ingest(app, self.picks())
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["configSkipped"], [{"property": "ui.theme.name", "reason": "not-confirmed"}])
        self.assertNotIn("configApplied", done)
        self.assertNotIn("config.json", done["files"])
        self.assertEqual(snapshot(app)["config.json"], before["config.json"])
        self.assertIn("themes/sunny.json", snapshot(app))

    def test_with_the_confirmation_the_setting_is_written_with_the_theme_and_undo_puts_everything_back(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, _ = self.ingest(app, self.picks())
        done = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["configApplied"], [{"property": "ui.theme.name", "value": "sunny"}])
        self.assertNotIn("configSkipped", done)
        self.assertEqual(done["files"], ["themes/sunny.json", "config.json", "model.json"])   # model.json last
        self.assertEqual(defaults_of(app), {"ui.theme.mode": "light", "ui.theme.name": "sunny"})
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)   # config.json byte for byte, the theme file gone

    def test_a_proposal_alone_is_a_change_a_builtin_theme_needs_no_file_and_a_model_that_did_not_move_is_fine(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, result = self.ingest(app, self.picks("retro", with_theme=False))
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        done = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        self.assertTrue(done["ok"], done)
        self.assertEqual(defaults_of(app)["ui.theme.name"], "retro")
        self.assertTrue(apply_mod.undo(sessions.load(code))["ok"])
        self.assertEqual(snapshot(app), before)

    def test_ticking_nothing_when_nothing_else_changed_is_refused_as_no_change_not_applied_as_a_no_op(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, _ = self.ingest(app, self.picks("retro", with_theme=False))
        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"]), (False, "not-ready"))
        self.assertEqual(snapshot(app), before)

    def test_a_proposal_that_selects_what_the_app_already_uses_is_not_a_change(self):
        app = self.make_configured_app('{"defaults": {"ui.theme.name": "retro"}}\n')
        code = self.start(app)
        result = engine.ingest(code, answer_of(code, 1, self.picks("retro", with_theme=False)), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("identical to the current model", " ".join(result["errorLines"]))

    def test_several_settings_are_written_in_one_step(self):
        app = self.make_configured_app()
        named = self.picks("sunny")
        named["npdev-config-proposal.json"] = proposal(change("ui.theme.name", "sunny"), change("ui.theme.choices", "retro, sunny"),
                                                       change("database.password", "hunter2"))
        code, result = self.ingest(app, named)
        self.assertEqual([i["status"] for i in result["configProposals"]], ["applicable", "applicable", "refused"])
        done = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME, CONFIRM_CHOICES])
        self.assertTrue(done["ok"], done)
        self.assertEqual(defaults_of(app), {"ui.theme.mode": "light", "ui.theme.name": "sunny", "ui.theme.choices": "retro, sunny"})
        self.assertNotIn("hunter2", (app / "config.json").read_text(encoding="utf-8"))

    def test_a_proposal_for_a_theme_nobody_sent_is_a_suggestion_and_applies_nothing(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, result = self.ingest(app, self.picks("midnight", with_theme=False, model=_with_phone()))
        self.assertEqual(result["configProposals"][0]["reason"], "value-not-allowed")
        done = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        self.assertTrue(done["ok"], done)
        self.assertEqual(snapshot(app)["config.json"], before["config.json"])

    def test_the_stored_answer_is_data_not_authority_an_edited_value_is_checked_again(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, _ = self.ingest(app, self.picks("retro", with_theme=False, model=_with_phone()))
        session = sessions.load(code)
        stored = sessions.round_dir(session, 1) / "result.json"
        data = json.loads(stored.read_text(encoding="utf-8"))
        data["configProposals"][0]["value"] = "evil"
        stored.write_text(json.dumps(data), encoding="utf-8")
        done = apply_mod.apply_round(session, None, [CONFIRM_NAME])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["configSkipped"], [{"property": "ui.theme.name", "reason": "no-longer-allowed"}])
        self.assertEqual(snapshot(app)["config.json"], before["config.json"])

    def test_an_app_without_a_config_file_gets_the_rest_of_the_answer_and_is_told(self):
        app = self.make_app()
        code, _ = self.ingest(app, self.picks())
        done = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["configSkipped"], [{"property": "ui.theme.name", "reason": "no-config-file"}])
        self.assertFalse((app / "config.json").exists())   # a chat never creates the file
        self.assertIn("themes/sunny.json", snapshot(app))

    def test_a_config_file_that_cannot_take_the_change_stops_everything_before_anything_is_written(self):
        app = self.make_configured_app('{"defaults": []}\n')
        before = snapshot(app)
        code, _ = self.ingest(app, self.picks())
        refused = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        self.assertEqual((refused["ok"], refused["error"]), (False, "config-write-refused"))
        self.assertIn("is not an object", refused["detail"])
        self.assertEqual(snapshot(app), before)

    def test_the_last_write_failing_puts_config_json_and_the_theme_back(self):
        app = self.make_configured_app()
        before = snapshot(app)
        code, _ = self.ingest(app, self.picks())
        FlakyDisk(self, app, nth=3)   # theme, config.json, model.json <- fails
        refused = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        self.assertEqual((refused["ok"], refused["error"], refused["path"]), (False, "write-failed", "model.json"))
        self.assertEqual(refused["notRestored"], [])
        self.assertEqual(snapshot(app), before)
        self.assertIsNone(sessions.load(code)["rounds"][0].get("appliedAt"))

    def test_undo_refuses_when_config_json_was_edited_after_the_apply_and_names_it(self):
        app = self.make_configured_app()
        code, _ = self.ingest(app, self.picks())
        apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME])
        (app / "config.json").write_text('{"defaults": {"mine": 1}}\n', encoding="utf-8")
        refused = apply_mod.undo(sessions.load(code))
        self.assertEqual((refused["ok"], refused["error"]), (False, "app-changed-since-apply"))
        self.assertIn("config.json", refused["changed"])
        self.assertTrue(apply_mod.undo(sessions.load(code), force=True)["ok"])


def _with_phone() -> dict:
    from test_relay_files import with_phone
    return with_phone(seed())


class CreateWithConfigTests(ApplyCase):
    def test_a_new_app_gets_its_theme_and_the_confirmed_setting_in_the_scaffolds_config_json(self):
        code = engine.start(intent="create", ask="A shop in warm colours", new_app_name="warmshop", phase="f4")["code"]
        named = {"model.json": seed(), "npdev-submission.json": manifest(), "themes/sunny.json": SUNNY,
                 "npdev-config-proposal.json": proposal(change("ui.theme.name", "sunny"))}
        result = engine.ingest(code, answer_of(code, 1, named), source="paste")
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        done = apply_mod.apply_round(sessions.load(code), None, [CONFIRM_NAME], parent_dir=str(self.dir / "apps"))
        self.assertTrue(done["ok"], done)
        app = Path(done["appDir"])
        self.assertEqual(done["configApplied"], [{"property": "ui.theme.name", "value": "sunny"}])
        self.assertEqual(defaults_of(app)["ui.theme.name"], "sunny")
        self.assertEqual(json.loads((app / "themes" / "sunny.json").read_text(encoding="utf-8"))["id"], "sunny")
        self.assertIn("config.json", done["files"])

    def test_the_same_answer_without_the_confirmation_leaves_the_scaffolds_config_json_alone(self):
        code = engine.start(intent="create", ask="A shop in warm colours", new_app_name="plainshop", phase="f4")["code"]
        named = {"model.json": seed(), "npdev-submission.json": manifest(), "themes/sunny.json": SUNNY,
                 "npdev-config-proposal.json": proposal(change("ui.theme.name", "sunny"))}
        engine.ingest(code, answer_of(code, 1, named), source="paste")
        done = apply_mod.apply_round(sessions.load(code), None, [], parent_dir=str(self.dir / "apps"))
        self.assertTrue(done["ok"], done)
        self.assertNotIn("ui.theme.name", defaults_of(Path(done["appDir"])))
        self.assertEqual(done["configSkipped"], [{"property": "ui.theme.name", "reason": "not-confirmed"}])


if __name__ == "__main__":
    unittest.main()
