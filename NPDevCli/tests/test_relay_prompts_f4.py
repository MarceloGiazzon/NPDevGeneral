"""The f4 prompt teaches the files that are not the model (REG-323 F4): themes, pages, menu, sample data, theme selection.

Why: a browser AI can only send what the prompt told it may. The f4 text used to teach fragments, contexts and upserts only, so
the checkers for themes, pages, menu and seeds sat behind a door the AI did not know existed. The risk the other way is a text
that teaches a SHAPE ingest refuses, so every example in relay-prompts-f4.json is run through the real checker here, and every
kind of file the policy lets through must be named in the text (add a new allowed kind and this fails until the prompt says so).

Run with: python -m unittest NPDevCli.tests.test_relay_prompts_f4 -v
"""
from __future__ import annotations

import fnmatch
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_config as config  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_ingest as ingest  # noqa: E402
import npdev_relay_pages as pages  # noqa: E402
import npdev_relay_prompts as rp  # noqa: E402
import npdev_relay_seeds as seeds  # noqa: E402
import npdev_relay_themes as themes  # noqa: E402
from test_relay_elements import ComposerCase  # noqa: E402

P4 = rp.load_f4_prompts()
EX = P4["examples"]
MODEL = {"concepts": [{"name": "Customer", "fields": [{"name": "name", "type": "string"}]}], "roles": [{"name": "admin"}]}
ALREADY_TAUGHT = {"model.json", "npdev-submission.json", "npdev-elements.json", "fragments/*.json", "contexts/*.json"}


def text(value) -> str:
    return json.dumps(value)


class ExamplesAreAcceptedTests(unittest.TestCase):
    def test_the_theme_example_passes_the_theme_check_and_names_its_file_after_its_id(self):
        out = themes.check({f"themes/{EX['theme']['id']}.json": text(EX["theme"])}, MODEL)
        self.assertEqual(out["errors"], [])
        self.assertEqual(out["themes"], [EX["theme"]["id"]])

    def test_the_config_example_is_an_applicable_change_the_owner_can_tick(self):
        selecting = config.evaluate(EX["config"], context={"themes": {EX["theme"]["id"]}})
        self.assertEqual(selecting["errors"], [])
        self.assertEqual([i["status"] for i in selecting["items"]], ["applicable"])
        self.assertEqual(selecting["items"][0]["confirmCode"], "config:ui.theme.name")

    def test_the_theme_the_config_example_selects_is_the_theme_the_theme_example_makes(self):
        self.assertEqual(EX["config"]["changes"][0]["value"], EX["theme"]["id"])

    def test_the_pages_and_menu_examples_pass_the_check_with_the_page_they_point_at(self):
        page = EX["pages"][0]["path"]
        out = pages.check({f"web/{page}": "<p>Orders</p>", "pages.json": text(EX["pages"]), "menu.json": text(EX["menu"])}, MODEL)
        self.assertEqual(out["errors"], [])
        self.assertEqual([f for f in out["findings"] if f["severity"] == "warning"], [])

    def test_the_menu_example_is_a_tree_whose_business_node_names_a_concept_of_the_model_it_is_shown_with(self):
        business = EX["menu"][0]["children"][0]
        self.assertIn(business["target"], [c["name"] for c in MODEL["concepts"]])

    def test_the_seed_example_passes_the_seed_check_with_its_id_equal_to_its_file_name(self):
        out = seeds.check({f"seeds/{EX['seed']['id']}.json": text(EX["seed"])}, MODEL)
        self.assertEqual(out["errors"], [])
        self.assertEqual(out["seeds"], [f"seeds/{EX['seed']['id']}.json"])

    def test_every_generator_the_seed_example_uses_is_one_the_text_lists(self):
        used = {v.split(":")[1] for r in EX["seed"]["records"] for v in r["data"].values() if str(v).startswith("$gen:")}
        self.assertTrue(used and used <= set(seeds.load_policy()["generators"]))


class TextTests(unittest.TestCase):
    def setUp(self):
        self.more = rp.compose_f4_more(P4)

    def test_the_text_names_every_kind_of_file_the_policy_lets_an_f4_answer_carry(self):
        for glob in ingest.load_policy()["paths"]["phases"]["f4"]["allowed"]:
            if glob in ALREADY_TAUGHT:
                continue
            head, _, tail = glob.partition("*")  # themes/*.json -> "themes/" and ".json"; pages.json -> the whole name
            self.assertIn(head, self.more, f"the f4 prompt never mentions {glob}")
            self.assertIn(tail, self.more, f"the f4 prompt never mentions {glob}")

    def test_the_text_says_the_listed_limits_and_generators_from_the_policy_not_from_a_copy(self):
        policy = seeds.load_policy()
        for generator in policy["generators"]:
            self.assertIn(generator, self.more)
        self.assertIn(f"{policy['maxExpandedRecords']:,}", self.more)

    def test_a_literal_dollar_in_the_text_survives_the_template(self):
        for token in ("$ref:<alias>", "$gen:<generator>", "$file:", "$gen:name"):
            self.assertIn(token, self.more)
        self.assertNotIn("$$", self.more)
        self.assertNotIn("$theme", self.more)  # every placeholder was filled

    def test_the_text_never_invites_what_the_policy_refuses(self):
        denied = [d["glob"] for d in ingest.load_policy()["paths"]["denied"]]
        for glob in ("config.json", "*.ps1"):
            self.assertIn(glob, denied)
        self.assertIn("Never send config.json", self.more)
        self.assertIn("no <script>", self.more)

    def test_the_text_lists_every_colour_name_a_theme_may_use_and_the_contrast_floors_the_validator_applies(self):
        import npdev_theme_validate as tv

        for token in tv.TOKENS:
            self.assertIn(token, self.more)
        floors = {f"{floor:g}:1" for _fg, _bg, floor in tv.CONTRAST_RULES}
        for floor in floors:
            self.assertIn(floor, self.more, "the validator has a contrast floor the prompt does not teach")

    def test_the_examples_are_valid_json_a_person_could_copy(self):
        for key, value in EX.items():
            self.assertIn(json.dumps(value, ensure_ascii=False), self.more, key)


class PhaseTests(ComposerCase):
    def test_a_small_f4_change_now_carries_the_text_and_stays_in_whole_mode(self):
        out = self.begin(self.make_app(), phase="f4")
        self.assertEqual(out["answerMode"], "whole")
        self.assertIn("themes/<id>.json", out["prompt"])
        self.assertIn("seeds/<id>.json", out["prompt"])
        self.assertNotIn("npdev-elements.json", out["prompt"])

    def test_a_split_app_and_a_big_app_get_their_own_briefing_and_then_the_text(self):
        split = self.begin(self.make_split_app(), phase="f4")["prompt"]
        self.assertIn("File fragments/customer.json:", split)
        self.assertLess(split.index("File fragments/customer.json:"), split.index("themes/<id>.json"))
        big = self.begin(self.make_big_app(), phase="f4", answer_size=4000)["prompt"]
        self.assertIn("npdev-elements.json", big)
        self.assertLess(big.index("npdev-elements.json"), big.index("themes/<id>.json"))

    def test_no_other_phase_and_no_create_gets_it(self):
        mvp = self.begin(self.make_app(), phase="mvp")
        self.assertNotIn("themes/<id>.json", mvp["prompt"])
        created = engine.start(intent="create", ask="A shop", new_app_name="myshop", phase="f4")
        self.assertNotIn("themes/<id>.json", created["prompt"])

    def test_a_fresh_chat_follow_up_carries_the_text_again(self):
        import npdev_relay_session as sessions

        out = self.begin(self.make_app(), phase="f4")
        fresh = engine._fresh_chat(sessions.load(out["code"]), 1, "something failed")
        self.assertIn("themes/<id>.json", fresh)


class RepairTests(ComposerCase):
    """Found by the bench (f4-run-2): after a theme failed its contrast check, the AI resent ONLY the theme and lost its page, menu and
    seed, because the measured follow-up said 'the COMPLETE corrected files', 'files=2' and, for PARTIAL, 'send model.json and
    npdev-submission.json ... Do not send other files'."""

    def follow_up(self, outcome: str, p4=P4, intent: str = "change") -> str:
        return rp.compose_same_chat(rp.load_prompts(), code="ABCD", rnd=2, outcome=outcome, errors="boom", intent=intent, p4=p4)

    def test_an_f4_follow_up_tells_the_ai_to_resend_every_file_and_never_says_files_equals_two(self):
        for outcome in rp.F4_REPAIR_OUTCOMES:
            text = self.follow_up(outcome)
            self.assertNotIn("files=2", text, outcome)
            self.assertIn("files=<the number of files you send>", text, outcome)
            self.assertTrue("EVERY file" in text or "keeps nothing" in text, outcome)
            self.assertIn("boom" if outcome in ("NEEDS_FIX", "PARTIAL", "BAD_JSON") else "ABCD-2", text, outcome)

    def test_the_f4_partial_follow_up_no_longer_forbids_the_other_files(self):
        measured = self.follow_up("PARTIAL", p4=None)
        self.assertIn("Do not send other files", measured)         # what the measured prompt says, and what dropped the page
        f4 = self.follow_up("PARTIAL")
        self.assertNotIn("Do not send other files", f4)
        for kind in ("theme", "page", "pages.json", "menu.json", "seed"):
            self.assertIn(kind, f4)

    def test_without_p4_or_for_a_pack_the_follow_up_is_the_measured_text_byte_for_byte(self):
        prompts = rp.load_prompts()
        for outcome in ("NEEDS_FIX", "PARTIAL", "BAD_JSON", "ELIDED", "TRUNCATED", "NO_FILES"):
            plain = rp.compose_same_chat(prompts, code="ABCD", rnd=2, outcome=outcome, errors="boom")
            self.assertEqual(self.follow_up(outcome, p4=None), plain)
            self.assertIn("files=2", plain if outcome != "PARTIAL" else plain + "files=2")
        self.assertEqual(self.follow_up("NEEDS_FIX", intent="pack"), rp.compose_same_chat(prompts, code="ABCD", rnd=2, outcome="NEEDS_FIX",
                                                                                           errors="boom", intent="pack"))
        self.assertIsNone(self.follow_up("QUESTIONS"))      # an outcome that needs the person is still None

    def test_the_engine_gives_the_repair_to_a_phase_f4_change_and_to_nothing_else(self):
        from test_relay_files import answer_of

        bad = {"npdev-elements.json": {"upsert": {"widgets": [{"name": "w"}]}}}
        big = self.begin(self.make_big_app(), phase="f4", answer_size=4000)
        result = engine.ingest(big["code"], answer_of(big["code"], 1, bad), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("EVERY file", result["next"]["prompt"])
        self.assertNotIn("files=2", result["next"]["prompt"])
        mvp = self.begin(self.make_app(), phase="mvp")
        fence = "`" * 3
        raw = "\n".join([f"NPDEV-BEGIN {mvp['code']}-1", "NPDEV-FILE model.json", fence + "json", "{not json", fence,
                         f"NPDEV-END {mvp['code']}-1 files=1", ""])
        garbage = engine.ingest(mvp["code"], raw, source="paste")
        self.assertIn(garbage["outcome"], ("BAD_JSON", "TRUNCATED"))   # either way a measured same-chat follow-up
        self.assertNotIn("EVERY file", garbage["next"]["prompt"])
        self.assertIn("files=2", garbage["next"]["prompt"])


class EmptyUpsertTests(ComposerCase):
    """Found by the bench (f4-run-4, f4-all-big): for an app too big to send whole, the prompt says 'do NOT send model.json, send
    npdev-elements.json', so an owner who asks only for a look, a page, a menu or sample data left the AI with nothing valid to put
    there. An elements file that changes nothing is an error, EXCEPT when the answer's other files are the change."""

    THEME = {"schema": "npdev-theme.v1", "id": "calm", "name": "Calm", "colorScheme": "light", "basedOn": "npdev-light",
             "colors": {"accent": "#0f6b4a", "onAccent": "#ffffff"}}

    def ingest(self, files: dict) -> dict:
        from test_relay_apply import manifest
        from test_relay_files import answer_of

        out = self.begin(self.make_big_app(), phase="f4", answer_size=4000)
        return engine.ingest(out["code"], answer_of(out["code"], 1, {**files, "npdev-submission.json": manifest()}), source="paste")

    def test_an_empty_elements_file_beside_a_theme_is_a_look_only_change_and_is_ready(self):
        result = self.ingest({"npdev-elements.json": {}, "themes/calm.json": self.THEME,
                              "npdev-config-proposal.json": {"changes": [{"property": "ui.theme.name", "value": "calm"}]}})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("elements-empty", [f["code"] for f in result["findings"]])
        self.assertEqual([i["status"] for i in result["configProposals"]], ["applicable"])

    def test_an_empty_elements_file_alone_is_still_refused_it_changes_nothing(self):
        result = self.ingest({"npdev-elements.json": {"upsert": {}}})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("it changes nothing", result["errorLines"][0])

    def test_the_manifest_alone_does_not_count_as_a_change(self):
        result = self.ingest({"npdev-elements.json": {}})
        self.assertEqual(result["outcome"], "NEEDS_FIX")


if __name__ == "__main__":
    unittest.main()
