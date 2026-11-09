"""The composer's contract: every prompt is recognised as an echo by ingest, follow-ups exist for
exactly the AI-fixable outcomes, and a simulated perfect answer to round 1 comes back PARSED."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import npdev_relay_ingest as ri  # noqa: E402
import npdev_relay_prompts as rp  # noqa: E402

P = rp.load_prompts()
POLICY = ri.load_policy()
KNOWLEDGE = "# NPDev core authoring context\n...contract...\n## Golden example: one\n{...}\n"
MODEL = json.dumps({"namespace": "t.x", "version": "1.0", "concepts": [
    {"name": "Item", "fields": [{"name": "id", "type": "uuid", "id": True, "required": True}]}]}, indent=2)


class ComposeTests(unittest.TestCase):
    def test_every_prompt_is_detected_as_echo(self):
        prompts = [
            rp.compose_round1(P, code="K7Q2", intent="create", ask="A shop", language="Portuguese (Brazil)", knowledge=KNOWLEDGE),
            rp.compose_round1(P, code="K7Q2", intent="change", ask="Add phone", language="English", knowledge=KNOWLEDGE, model=MODEL),
            rp.compose_round1(P, code="K7Q2", intent="fix", ask="", language="English", knowledge=KNOWLEDGE, model=MODEL, errors="boom"),
            rp.compose_same_chat(P, code="K7Q2", rnd=2, outcome="NEEDS_FIX", errors="x"),
            rp.compose_fresh_chat(P, code="K7Q2", rnd=3, ask="A shop", language="English", knowledge=KNOWLEDGE,
                                  model=MODEL, errors="x", trail=["missing id field"]),
        ]
        for text in prompts:
            result = ri.ingest_text(text, session="K7Q2", expected_round=1, policy=POLICY)
            self.assertEqual(result["outcome"], ri.PROMPT_ECHO)

    def test_follow_ups_only_for_ai_fixable_outcomes(self):
        for outcome in ("NEEDS_FIX", "TRUNCATED", "ELIDED", "BAD_JSON", "PARTIAL", "FILE_LINK_ONLY", "NO_FILES"):
            self.assertIsNotNone(rp.compose_same_chat(P, code="K7Q2", rnd=2, outcome=outcome))
        for outcome in ("QUESTIONS", "LIMIT_REACHED", "STALE_ROUND", "WRONG_SESSION", "PROMPT_ECHO", "READY"):
            self.assertIsNone(rp.compose_same_chat(P, code="K7Q2", rnd=2, outcome=outcome))

    def test_fresh_chat_uses_slim_knowledge(self):
        text = rp.compose_fresh_chat(P, code="K7Q2", rnd=3, ask="A", language="English", knowledge=KNOWLEDGE,
                                     model=MODEL, errors="e", trail=[])
        self.assertNotIn("Golden example", text)
        self.assertIn("...contract...", text)

    def test_a_perfect_answer_to_round_one_parses(self):
        manifest = json.dumps({"request": "A shop", "renames": [], "deliberateRemovals": [], "securityChanges": [],
                               "couldNotExpress": [], "assumptions": []})
        fence = "`" * 3
        answer = (f"NPDEV-BEGIN K7Q2-1\nDone.\nNPDEV-FILE model.json\n{fence}json\n{MODEL}\n{fence}\n"
                  f"NPDEV-FILE npdev-submission.json\n{fence}json\n{manifest}\n{fence}\nNPDEV-END K7Q2-1 files=2\n")
        self.assertEqual(ri.ingest_text(answer, session="K7Q2", expected_round=1, policy=POLICY)["outcome"], ri.PARSED)

    def test_round1_overhead_without_knowledge_stays_small(self):
        """Everything except the knowledge bundle and the user's text: keep it under ~3.5 KB so the
        measured ~16.7 KB bundle stays the bulk of the prompt (free-tier budgets, plan 4.5)."""
        text = rp.compose_round1(P, code="K7Q2", intent="create", ask="", language="English", knowledge="")
        self.assertLess(len(text.encode("utf-8")), 3500)


if __name__ == "__main__":
    unittest.main()
