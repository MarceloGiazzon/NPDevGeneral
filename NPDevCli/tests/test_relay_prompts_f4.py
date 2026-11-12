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


if __name__ == "__main__":
    unittest.main()
