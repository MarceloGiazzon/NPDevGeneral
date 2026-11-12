"""Themes in a browser AI's answer (REG-323 T4.3): `themes/<id>.json` is checked with the generator's own rules before it is
accepted, written all-or-nothing with the model and taken back by undo.

Why it matters: the generator's ThemeEmitter FAILS GENERATION of the whole app on one bad theme file, so a theme that slips
through turns a chat answer into an app that no longer builds. The first half tests the check on its own (stdlib, no app,
no Java); the second half drives whole answers through ingest -> apply -> undo with the Java validator real and the
authoring gate stubbed (as test_relay_files does), plus one run against the real gate.

Which theme the app USES is `ui.theme.name` / `ui.theme.choices` in config.json's `defaults` -- never in model.json -- and
a chat cannot write config.json: so a theme the app does not select is saved with a warning, and the tests pin where the
setting really lives.

Run with: python -m unittest NPDevCli.tests.test_relay_themes -v
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
import npdev_relay_session as sessions  # noqa: E402
import npdev_relay_themes as themes  # noqa: E402
import npdev_theme_validate as tv  # noqa: E402
from test_relay_apply import SEED_MODEL, ApplyCase, manifest  # noqa: E402
from test_relay_files import SplitAnswerCase, SplitHelpers, answer_of, snapshot  # noqa: E402

REPO = HERE.parent.parent
SUNNY = {"schema": "npdev-theme.v1", "id": "sunny", "name": "Sunny", "colorScheme": "light", "basedOn": "npdev-light",
         "colors": {"accent": "#8a4b00"}}


def theme(**changes) -> dict:
    out = copy.deepcopy(SUNNY)
    out.update(changes)
    return out


def sent(*items) -> dict:
    """{path: text} the way an answer carries them: each theme under themes/<its id>.json."""
    return {f"themes/{t['id']}.json": json.dumps(t) for t in items}


def selecting(folder, name=None, choices=None) -> str:
    """Write the app's config.json with the theme settings (flat keys under `defaults`, where they really live)."""
    defaults = {}
    if name is not None:
        defaults["ui.theme.name"] = name
    if choices is not None:
        defaults["ui.theme.choices"] = choices
    Path(folder).mkdir(parents=True, exist_ok=True)
    (Path(folder) / "config.json").write_text(json.dumps({"defaults": defaults}), encoding="utf-8")
    return str(folder)


def seed() -> dict:
    return json.loads(SEED_MODEL)


def codes(checked: dict) -> list:
    return [f["code"] for f in checked["findings"]]


def lines(checked: dict) -> str:
    return " | ".join(checked["errors"])


class ValidatorTests(unittest.TestCase):
    def test_one_validator_serves_the_checker_and_the_relay(self):
        self.assertEqual(tv.validate(json.loads((themes.BUILT_IN_DIR / "npdev-light.json").read_text(encoding="utf-8"))), [])
        self.assertEqual(sorted(themes.builtin_catalog()), sorted(tv.BUILT_IN_IDS))

    def test_a_colour_set_that_is_not_an_object_is_an_error_not_a_crash(self):
        errors = tv.validate_structure({**SUNNY, "colors": ["#ffffff"], "typography": "x", "shape": 3})
        self.assertIn("colors must be an object of colour tokens", errors)

    def test_basedOn_is_flattened_ancestors_first_and_the_child_wins(self):
        catalog = {**themes.builtin_catalog(), "sunny": SUNNY}
        flat = tv.resolve(theme(id="sunnier", basedOn="sunny", colors={"accent": "#7a3f00"}), catalog)
        self.assertEqual(flat["colors"]["accent"], "#7a3f00")
        self.assertEqual(flat["colors"]["bg"], "#ffffff")
        self.assertNotIn("basedOn", flat)


class WhereTheThemeIsChosenTests(unittest.TestCase):
    """The claim the whole design rests on: the selection is a config.json default, not a model.json setting."""

    def test_the_selection_is_a_flat_config_default_and_model_json_cannot_hold_it(self):
        config_schema = json.loads((REPO / "NPDevContract" / "schemas" / "config.schema.json").read_text(encoding="utf-8"))
        self.assertIn("string", config_schema["properties"]["defaults"]["additionalProperties"]["type"])
        ui = json.loads((REPO / "NPDevContract" / "schemas" / "model.schema.json").read_text(encoding="utf-8")
                        )["$defs"]["settings"]["properties"]["ui"]
        self.assertFalse(ui["additionalProperties"])
        self.assertNotIn("theme", ui["properties"])
        java = (REPO / "NPDevContract" / "dsl" / "src" / "main" / "java" / "com" / "npdev" / "dsl" / "v1" / "settings"
                / "NpdevSettings.java").read_text(encoding="utf-8")
        self.assertIn(f'"{themes.SETTING_NAME}"', java)
        self.assertIn(f'"{themes.SETTING_CHOICES}"', java)

    def test_selection_reads_name_choices_and_the_star(self):
        self.assertEqual(themes.selection({"defaults": {"ui.theme.name": " sunny ", "ui.theme.choices": "retro, sunny"}}),
                         {"name": "sunny", "choices": ["retro", "sunny"]})
        self.assertEqual(themes.selection({"defaults": {"ui.theme.choices": "*"}}), {"name": None, "choices": "*"})
        self.assertEqual(themes.selection({"defaults": []}), {"name": None, "choices": []})
        self.assertEqual(themes.selection(None), {"name": None, "choices": []})

    def test_a_model_that_tries_to_pick_the_theme_is_told_where_the_choice_is_made(self):
        for settings in ({"ui": {"theme": {"name": "sunny"}}}, {"ui.theme.name": "sunny"}, {"ui": {"themeName": "sunny"}}):
            model = {**seed(), "settings": settings}
            checked = themes.check(sent(SUNNY), model)
            self.assertIn("the theme is not chosen in model.json", lines(checked), settings)
            self.assertIn("say in your note which theme the owner should select", lines(checked))
        self.assertEqual(themes.check(sent(SUNNY), {**seed(), "settings": {"ui": {"pageRows": 25}}})["errors"], [])


class CheckTests(unittest.TestCase):
    def test_a_good_theme_the_app_selects_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            checked = themes.check(sent(SUNNY), seed(), selecting(tmp, name="sunny"))
        self.assertEqual((checked["errors"], codes(checked), checked["themes"]), ([], ["theme-added"], ["sunny"]))

    def test_the_file_sent_under_another_name_than_its_id_is_named_both_ways(self):
        checked = themes.check({"themes/summer-theme.json": json.dumps(theme(id="summer"))}, seed())
        self.assertIn('the file name must be <id>.json (the theme\'s id is "summer")', lines(checked))
        self.assertIn("themes/summer.json", lines(checked))

    def test_a_theme_that_reuses_a_builtin_id_is_refused(self):
        checked = themes.check(sent(theme(id="retro")), seed())
        self.assertIn('"retro" is one of NPDev\'s own themes', lines(checked))

    def test_a_low_contrast_pair_is_named(self):
        low = theme(colors={"accent": "#8a4b00", "textMuted": "#cccccc"})
        checked = themes.check(sent(low), seed())
        self.assertIn("themes/sunny.json: colors: textMuted on bg is", lines(checked))
        self.assertIn("needs 4.5:1", lines(checked))

    def test_contrast_is_checked_on_the_theme_as_used_after_basedOn(self):
        # text on bg is fine in the child's own colours (it sets none); the FLATTENED set is what the user sees
        checked = themes.check(sent(theme(colors={"text": "#fafafa"})), seed())
        self.assertIn("text on bg is", lines(checked))

    def test_hostile_values_never_reach_the_app(self):
        hostile = theme(colors={"accent": "red; background:url(https://evil.example/x)"},
                        typography={"fontUi": "x; } body { display:none"})
        checked = themes.check(sent(hostile), seed())
        self.assertIn("colors.accent must be #rrggbb", lines(checked))
        self.assertIn("typography.fontUi has characters outside", lines(checked))

    def test_basedOn_that_points_nowhere_a_cycle_and_a_chain_too_deep_are_refused(self):
        self.assertIn('basedOn: unknown theme "nope"', lines(themes.check(sent(theme(basedOn="nope")), seed())))
        a = theme(id="aaa", basedOn="bbb")
        b = theme(id="bbb", basedOn="aaa")
        self.assertIn("basedOn cycle", lines(themes.check(sent(a, b), seed())))
        chain = [theme(id="t1", basedOn="npdev-light")] + [theme(id=f"t{i}", basedOn=f"t{i - 1}") for i in range(2, 5)]
        self.assertIn("deeper than 3", lines(themes.check(sent(*chain), seed())))

    def test_a_file_that_is_not_json_an_array_or_too_big_is_refused_in_plain_words(self):
        checked = themes.check({"themes/a.json": "{nope", "themes/b.json": "[1]",
                                "themes/c.json": json.dumps(theme(id="ccc", description="x" * 21000))}, seed())
        text = lines(checked)
        self.assertIn("themes/a.json: not valid JSON", text)
        self.assertIn("themes/b.json: not a JSON object", text)
        self.assertIn("themes/c.json: the theme file is larger than 20000 bytes", text)

    def test_no_themes_and_no_theme_settings_is_untouched(self):
        self.assertEqual(themes.check({}, seed()), {"errors": [], "findings": [], "themes": []})
        self.assertEqual(themes.check({}, None), {"errors": [], "findings": [], "themes": []})

    def test_a_theme_the_app_does_not_select_is_saved_with_a_warning_that_names_the_setting(self):
        checked = themes.check(sent(SUNNY), seed())
        self.assertEqual(checked["errors"], [])
        self.assertEqual(checked["findings"], [
            {"code": "theme-added", "severity": "info", "detail": {"theme": "sunny"}},
            {"code": "theme-not-selected", "severity": "warning",
             "detail": {"theme": "sunny", "setting": "ui.theme.name", "where": "config.json defaults"}}])

    def test_an_ancestor_of_the_selected_theme_and_a_theme_in_choices_count_as_used(self):
        parent, child = theme(id="parent", basedOn="npdev-light"), theme(id="child", basedOn="parent")
        cases = ((sent(parent, child), {"name": "child"}), (sent(SUNNY), {"choices": "sunny"}), (sent(SUNNY), {"choices": "*"}))
        for answer_themes, config in cases:
            with tempfile.TemporaryDirectory() as tmp:
                checked = themes.check(answer_themes, seed(), selecting(tmp, **config))
            self.assertEqual(checked["errors"], [], config)
            self.assertNotIn("theme-not-selected", codes(checked), config)

    def test_a_theme_can_extend_one_the_app_already_has_and_replacing_one_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "themes").mkdir()
            (Path(tmp) / "themes" / "sunny.json").write_text(json.dumps(SUNNY), encoding="utf-8")
            selecting(tmp, name="sunnier")
            checked = themes.check(sent(theme(id="sunnier", basedOn="sunny")), seed(), tmp)
            self.assertEqual((checked["errors"], codes(checked)), ([], ["theme-added"]))
            selecting(tmp, name="sunny")
            checked = themes.check(sent(theme(colors={"accent": "#7a3f00"})), seed(), tmp)
            self.assertEqual((checked["errors"], codes(checked)), ([], ["theme-replaced"]))

    def test_without_the_builtin_files_only_the_structure_is_checked_and_it_says_so(self):
        with mock.patch.object(themes, "builtin_catalog", return_value={}):
            checked = themes.check(sent(SUNNY), seed())
        self.assertEqual(checked["errors"], [])
        self.assertIn("theme-base-unchecked", codes(checked))


class ChangedTests(unittest.TestCase):
    def test_only_a_new_or_different_theme_counts_and_reindenting_does_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "themes").mkdir()
            (Path(tmp) / "themes" / "sunny.json").write_text(json.dumps(SUNNY), encoding="utf-8")
            self.assertEqual(themes.changed(tmp, {"themes/sunny.json": json.dumps(SUNNY, indent=4)}), [])
            self.assertEqual(themes.changed(tmp, sent(theme(colors={"accent": "#6b3a00"}))), ["themes/sunny.json"])
            self.assertEqual(themes.changed(tmp, sent(theme(id="new"))), ["themes/new.json"])
            self.assertEqual(themes.changed(tmp, {"fragments/a.json": "{}"}), [])


class ThemeAnswerTests(SplitAnswerCase):
    """Whole answers with phase f4: ingest -> apply -> undo."""

    def answer(self, code: str, model: dict, *themes_sent: dict, rnd: int = 1) -> str:
        named = {"model.json": model, "npdev-submission.json": manifest()}
        for item in themes_sent:
            named[f"themes/{item['id']}.json"] = item
        return answer_of(code, rnd, named)

    def test_a_new_theme_alone_is_a_change_applied_and_undone_without_touching_the_model(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app, ask="Give the app a warmer look")
        result = engine.ingest(code, self.answer(code, seed(), SUNNY), source="paste")
        self.assertEqual(result["outcome"], "READY", result.get("errorLines"))
        self.assertEqual([f["code"] for f in result["findings"] if f["code"].startswith("theme-")],
                         ["theme-added", "theme-not-selected"])
        self.assertEqual(snapshot(app), before)  # nothing is written until the owner applies
        applied = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(applied["ok"], applied)
        self.assertIn("themes/sunny.json", applied["files"])
        self.assertEqual(json.loads((app / "themes" / "sunny.json").read_text(encoding="utf-8"))["id"], "sunny")
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)  # the theme file the answer created is gone

    def test_a_theme_that_would_break_generation_never_reaches_the_app(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        low = theme(colors={"accent": "#8a4b00", "textMuted": "#cccccc"})
        result = engine.ingest(code, self.answer(code, seed(), low), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("textMuted on bg", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_a_model_that_picks_the_theme_itself_is_sent_back_with_the_way_out(self):
        app = self.make_app()
        code = self.start(app)
        model = {**seed(), "settings": {"ui": {"theme": {"name": "sunny"}}}}
        result = engine.ingest(code, self.answer(code, model, SUNNY), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("the theme is not chosen in model.json", " ".join(result["errorLines"]))

    def test_changing_only_a_theme_the_app_already_selects_is_ready_and_has_no_warning(self):
        app = self.make_app()
        (app / "themes").mkdir()
        (app / "themes" / "sunny.json").write_text(json.dumps(SUNNY), encoding="utf-8")
        selecting(app, name="sunny")
        before = snapshot(app)
        code = self.start(app, ask="Make the accent darker")
        result = engine.ingest(code, self.answer(code, seed(), theme(colors={"accent": "#6b3a00"})), source="paste")
        self.assertEqual(result["outcome"], "READY", result.get("errorLines"))
        self.assertEqual([f["code"] for f in result["findings"] if f["code"].startswith("theme-")], ["theme-replaced"])
        applied = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(applied["ok"], applied)
        self.assertEqual(json.loads((app / "themes" / "sunny.json").read_text(encoding="utf-8"))["colors"]["accent"], "#6b3a00")
        self.assertTrue(apply_mod.undo(sessions.load(code))["ok"])
        self.assertEqual(snapshot(app), before)  # the old theme came back byte for byte

    def test_an_answer_that_changes_nothing_at_all_is_still_refused(self):
        app = self.make_app()
        (app / "themes").mkdir()
        (app / "themes" / "sunny.json").write_text(json.dumps(SUNNY), encoding="utf-8")
        code = self.start(app)
        result = engine.ingest(code, self.answer(code, seed(), SUNNY), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("identical to the current model", " ".join(result["errorLines"]))


@unittest.skipIf(authoring.gate_unavailable_reason(), "the repo's authoring gate needs Java and the Gradle wrapper")
class RealGateThemeTests(SplitHelpers, ApplyCase):
    """The Java gate compares two models; a theme-only answer gives it two IDENTICAL ones. It must still pass and apply."""

    def test_a_theme_only_change_passes_the_real_gate_and_is_applied_then_undone(self):
        app = self.make_app()
        (app / "themes").mkdir()
        (app / "themes" / "sunny.json").write_text(json.dumps(SUNNY), encoding="utf-8")
        selecting(app, name="sunny")
        before = snapshot(app)
        code = engine.start(intent="change", ask="Make the accent darker", app_dir=str(app), phase="f4")["code"]
        named = {"model.json": seed(), "npdev-submission.json": manifest(), "themes/sunny.json": theme(colors={"accent": "#6b3a00"})}
        result = engine.ingest(code, answer_of(code, 1, named), source="paste")
        self.assertEqual(result["outcome"], "READY", result.get("errorLines") or result["findings"])
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(json.loads((app / "themes" / "sunny.json").read_text(encoding="utf-8"))["colors"]["accent"], "#6b3a00")
        self.assertTrue(apply_mod.undo(sessions.load(code))["ok"])
        self.assertEqual(snapshot(app), before)


if __name__ == "__main__":
    unittest.main()
