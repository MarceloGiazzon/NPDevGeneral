"""How long an answer the relay asks a chat AI for: the budget maths, the prompts that state it, and the engine that
shrinks the request after a repeated cut-off.

The rule that matters most is the first test: with no size asked for and no repeated cut-off, every prompt is
BYTE-IDENTICAL to the one the bench measured (relay-prompts.json is bench-measured: changing a default prompt means
re-running the relay bench arm). The hashes below were taken from the prompts BEFORE this feature existed.

Run with: python -m unittest NPDevCli.tests.test_relay_answer_size -v
"""
from __future__ import annotations

import hashlib
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_prompts as rp  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
import npdev_relay_targets as rt  # noqa: E402
from test_relay_engine import EngineCase, SEED_MODEL, answer  # noqa: E402

P = rp.load_prompts()
KNOWLEDGE = "KNOWLEDGE-TEXT\n## Golden example: x\nEXAMPLE"
MODEL = '{"m":1}'

# sha256 of each prompt as composed before the answer-size feature (same inputs as below).
MEASURED = {
    "round1_create": "d766fae8a375753b75178619075ef172157526aaa7bcd172351cec70f8a1be40",
    "round1_change": "035688c25d201e79bc29e05f23afb429abd51476e4240b81b93ce0d4c85f0530",
    "round1_fix": "627756f48f482aa3312024c02475933022bf861836b8738fdbc0a47562d931a3",
    "same_TRUNCATED": "87c8e23d697f63ce80c0a2bb686132ec29753abf4a73a7b8bb84c37b1c2f1e96",
    "same_ELIDED": "c7a79fa89f92d910b690669fc1b376b37a0bf343aeca54f9a4545deefb951f8b",
    "fresh": "daf3eb0fb308cd6f2335e2f43dc488bf3b0dd861dd0b8f26157d8abd07566cd5",
}


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def cut(code: str, rnd: int) -> str:
    """An answer that stops in the middle: what a chat that hit its length limit leaves behind."""
    text = answer(code, rnd)
    return text[: int(len(text) * 0.6)]


class DefaultPromptsAreUntouchedTests(unittest.TestCase):
    def test_every_default_prompt_is_byte_identical_to_the_measured_one(self):
        def round1(intent):
            return rp.compose_round1(P, code="ABCD", intent=intent, ask="make a thing", language="English",
                                     knowledge=KNOWLEDGE, model=MODEL, errors="E1", interview=False)
        self.assertEqual(sha(round1("create")), MEASURED["round1_create"])
        self.assertEqual(sha(round1("change")), MEASURED["round1_change"])
        self.assertEqual(sha(round1("fix")), MEASURED["round1_fix"])
        self.assertEqual(sha(rp.compose_same_chat(P, code="ABCD", rnd=2, outcome="TRUNCATED")), MEASURED["same_TRUNCATED"])
        self.assertEqual(sha(rp.compose_same_chat(P, code="ABCD", rnd=2, outcome="ELIDED")), MEASURED["same_ELIDED"])
        fresh = rp.compose_fresh_chat(P, code="ABCD", rnd=3, ask="make a thing", language="English", knowledge=KNOWLEDGE,
                                      model=MODEL, errors="E1", trail=["TRUNCATED:aaa"])
        self.assertEqual(sha(fresh), MEASURED["fresh"])


class BudgetMathTests(unittest.TestCase):
    PROFILE = {"outputBudgetChars": 24000}

    def test_automatic_is_the_budget_and_words_are_shares_of_it(self):
        self.assertEqual(rt.answer_budget(self.PROFILE), 24000)
        self.assertEqual(rt.answer_budget(self.PROFILE, size="large"), 24000)
        self.assertEqual(rt.answer_budget(self.PROFILE, size="medium"), 12000)
        self.assertEqual(rt.answer_budget(self.PROFILE, size="small"), 6000)
        self.assertEqual(rt.answer_budget(self.PROFILE, size=9000), 9000)

    def test_each_cut_off_halves_it_and_the_floor_holds(self):
        self.assertEqual([rt.answer_budget(self.PROFILE, n) for n in range(5)], [24000, 12000, 6000, 4000, 4000])

    def test_the_size_is_checked(self):
        for bad in ("huge", "-5", 100, True, "12.5"):
            with self.assertRaises(rt.RelayTargetsError, msg=repr(bad)):
                rt.parse_answer_size(bad)
        self.assertIsNone(rt.parse_answer_size(None))
        self.assertIsNone(rt.parse_answer_size("auto"))
        self.assertIsNone(rt.parse_answer_size(" AUTO "))
        self.assertEqual(rt.parse_answer_size("Small"), "small")
        self.assertEqual(rt.parse_answer_size("8000"), 8000)

    def test_a_model_fits_whole_up_to_sixty_percent_of_the_budget(self):
        self.assertTrue(rt.whole_file_fits(self.PROFILE, 14400))
        self.assertFalse(rt.whole_file_fits(self.PROFILE, 14401))
        self.assertFalse(rt.whole_file_fits(self.PROFILE, 8000, size="small"))   # 60% of 6000 = 3600


class PromptTests(unittest.TestCase):
    def round1(self, intent, budget):
        return rp.compose_round1(P, code="ABCD", intent=intent, ask="make a thing", language="English",
                                 knowledge=KNOWLEDGE, model=MODEL, errors="E1", budget=budget)

    def test_a_new_app_is_told_the_size_and_to_start_with_its_core(self):
        text = self.round1("create", 6000)
        self.assertIn("about 6000 characters", text)
        self.assertIn("CORE", text)

    def test_a_change_or_a_fix_is_never_told_to_write_less_than_the_whole_file(self):
        for intent in ("change", "fix"):
            self.assertNotIn("Size limit", self.round1(intent, 6000))

    def test_the_smaller_follow_up_names_the_size_and_keeps_the_format(self):
        text = rp.compose_same_chat(P, code="ABCD", rnd=3, outcome="TRUNCATED", budget=12000)
        self.assertIn("SMALLER", text)
        self.assertIn("about 12000 characters", text)
        self.assertIn("NPDEV-BEGIN ABCD-3", text)
        self.assertIn("NPDEV-END ABCD-3 files=2", text)

    def test_other_outcomes_ignore_the_budget(self):
        self.assertEqual(rp.compose_same_chat(P, code="ABCD", rnd=2, outcome="BAD_JSON", errors="x", budget=6000),
                         rp.compose_same_chat(P, code="ABCD", rnd=2, outcome="BAD_JSON", errors="x"))


class EngineSizeTests(EngineCase):
    def test_default_start_states_no_size_but_reports_the_budget(self):
        out = self.start()
        self.assertNotIn("Size limit", out["prompt"])
        self.assertEqual(out["answerBudgetChars"], 24000)
        self.assertNotIn("answerSize", sessions.load(out["code"]))

    def test_a_chosen_size_is_stated_and_remembered_for_a_new_app(self):
        out = self.start(answer_size="small")
        self.assertIn("about 6000 characters", out["prompt"])
        self.assertEqual(sessions.load(out["code"])["answerSize"], "small")

    def test_auto_is_the_same_as_nothing(self):
        self.assertNotIn("Size limit", self.start(answer_size="auto")["prompt"])

    def test_a_bad_size_is_refused_before_anything_is_written(self):
        with self.assertRaises(engine.RelayStageError):
            self.start(answer_size="huge")
        self.assertEqual(engine.list_sessions()["sessions"], [])

    def test_a_change_reports_whether_its_model_can_be_written_out_whole(self):
        app = self.dir / "shop"
        app.mkdir()
        (app / "model.json").write_text(SEED_MODEL, encoding="utf-8")
        small = self.start(intent="change", ask="add a phone", app_dir=str(app))
        self.assertTrue(small["wholeFileFits"])
        self.assertEqual(small["modelChars"], len(SEED_MODEL))
        self.assertNotIn("Size limit", self.start(intent="change", ask="x", app_dir=str(app), answer_size="small")["prompt"])
        (app / "model.json").write_text(SEED_MODEL + " " * 15000, encoding="utf-8")
        big = self.start(intent="change", ask="add a phone", app_dir=str(app))
        self.assertFalse(big["wholeFileFits"])

    def test_the_first_cut_off_keeps_the_measured_follow_up(self):
        code = self.start()["code"]
        first = engine.ingest(code, cut(code, 1), source="paste")
        self.assertEqual(first["outcome"], "TRUNCATED")
        self.assertEqual(first["next"]["prompt"], rp.compose_same_chat(P, code=code, rnd=2, outcome="TRUNCATED"))

    def test_a_repeated_cut_off_asks_for_less_each_time_until_it_changes_chat(self):
        code = self.start()["code"]
        engine.ingest(code, cut(code, 1), source="paste")
        second = engine.ingest(code, cut(code, 2), source="paste")
        self.assertEqual(second["next"]["messageId"], "TRUNCATED")
        self.assertIn("SMALLER", second["next"]["prompt"])
        self.assertIn("about 12000 characters", second["next"]["prompt"])
        self.assertNotIn("going-in-circles", [f["code"] for f in second["findings"]])
        third = engine.ingest(code, cut(code, 3), source="paste")
        self.assertIn("about 6000 characters", third["next"]["prompt"])
        self.assertEqual(third["next"]["messageId"], "TRUNCATED")
        # At the floor there is nothing left to take out of the request: change chat, still stating the size.
        fourth = engine.ingest(code, cut(code, 4), source="paste")
        self.assertEqual(fourth["next"]["messageId"], "GOING_IN_CIRCLES")
        self.assertIn("about 4000 characters", fourth["next"]["prompt"])
        self.assertEqual(sessions.load(code)["rounds"][-1]["promptKind"], "fresh-chat")

    def test_a_cut_off_change_is_not_asked_to_shrink_the_file(self):
        app = self.dir / "shop"
        app.mkdir()
        (app / "model.json").write_text(SEED_MODEL, encoding="utf-8")
        code = self.start(intent="change", ask="add a phone", app_dir=str(app))["code"]
        engine.ingest(code, cut(code, 1), source="paste")
        second = engine.ingest(code, cut(code, 2), source="paste")
        self.assertNotIn("SMALLER", second["next"].get("prompt") or "")
        self.assertNotIn("Size limit", second["next"].get("prompt") or "")

    def test_a_good_answer_between_cut_offs_starts_the_count_again(self):
        self.validation_mock.return_value = {"status": "failed", "summary": {"errors": 1, "warnings": 0}, "diagnostics": [
            {"severity": "error", "code": "json_schema_enum", "path": "$.x", "message": "m", "suggestedFix": "f"}]}
        code = self.start()["code"]
        engine.ingest(code, cut(code, 1), source="paste")
        engine.ingest(code, answer(code, 2), source="paste")           # complete, only wrong: breaks the streak
        again = engine.ingest(code, cut(code, 3), source="paste")
        self.assertEqual(again["next"]["prompt"], rp.compose_same_chat(P, code=code, rnd=4, outcome="TRUNCATED"))


if __name__ == "__main__":
    unittest.main()
