"""FORM P7-T1: scripts/pack-repo/build-ai-catalog.py -- L0 lines, L1 cards, trust from verdicts, hard token budgets."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "NPDevCli"))
_spec = importlib.util.spec_from_file_location("build_ai_catalog", REPO / "scripts" / "pack-repo" / "build-ai-catalog.py")
cat = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cat)


def _doc(pack="leads", version="1.0.0", **extra):
    doc = {"dslVersion": "3.0.0", "pack": pack, "version": version, "description": "Track leads. More detail here.",
           "concepts": [{"name": "Lead", "fields": [
               {"name": "id", "type": "uuid", "id": True, "required": True},
               {"name": "code", "type": "string", "required": True},
               {"name": "source", "type": "string", "required": True},
               {"name": "notes", "type": "string"},
               {"name": "status", "type": "enum", "required": True}],
               "lifecycle": {"statusField": "status", "states": [{"value": "NEW"}, {"value": "WON"}, {"value": "LOST"}]},
               "access": {"read": "role('salesRep') || role('boss')", "write": "role('salesRep') && owner == $user.id || role('salesRep') && owner == null"}}]}
    doc.update(extra)
    return doc


def _entry(doc=None, **over):
    doc = doc or _doc()
    entry = {"id": doc["pack"], "version": doc["version"], "doc": doc, "trust": "draft", "source": "workshop", "kind": "module",
             "area": "sales", "need": cat.need_of(doc, None), "requires": [], "limit": "does not de-duplicate"}
    entry.update(over)
    return entry


class LinesAndCards(unittest.TestCase):
    def test_need_is_the_first_sentence_and_l0_has_the_documented_shape(self):
        entry = _entry(requires=["contacts", "identity"])
        self.assertEqual(entry["need"], "Track leads.")
        self.assertEqual(cat.l0_line(entry), "leads|module|sales|draft|Track leads.|requires:contacts,identity")

    def test_l0_line_shrinks_its_need_to_the_token_budget_and_never_holds_a_pipe(self):
        entry = _entry(need="A very long | need " * 20, requires=["a-pack", "another-pack", "third-pack"])
        line = cat.l0_line(entry)
        self.assertLessEqual(cat.tokens(line), cat.L0_LINE_TOKENS)
        self.assertEqual(line.count("|"), 5)

    def test_card_names_key_fields_states_roles_and_the_use_line(self):
        card, why = cat.build_card(_entry())
        self.assertIsNone(why)
        self.assertEqual(card["provides"]["concepts"], ["Lead(code, source, status:NEW|WON|LOST)"])
        self.assertIn("salesRep -> writes Lead; reads Lead", card["provides"]["roles"])
        self.assertIn("boss -> reads Lead", card["provides"]["roles"])
        self.assertIn("NPR.git//packs/leads@leads-1.0.0", card["use"])
        self.assertIn('"salesRep": "<YourRole>"', card["use"])
        self.assertEqual(card["limit"], "does not de-duplicate")
        self.assertLessEqual(card["budgetTokens"], cat.CARD_TOKENS)

    def test_use_line_binds_the_roles_of_required_packs_transitively(self):
        # composition refuses an unbound role of ANY pack in the graph, one per validator round (clinic bench 2026-11-27)
        person = _entry(_doc("person"), requires=[])
        person["doc"]["concepts"][0]["access"] = {"read": "role('personStaff')", "write": "role('personManager')"}
        mid = _entry(_doc("mid"), requires=["person"])
        mid["doc"]["concepts"][0]["access"] = {"read": "role('midUser')", "write": "role('midUser')"}
        top = _entry(_doc("top"), requires=["mid", "ghost"])   # `ghost` is not in the catalog: skipped, never an error
        top["doc"]["concepts"][0]["access"] = {"read": "role('topUser')", "write": "role('topUser')"}
        by_id = {e["id"]: e for e in (person, mid, top)}
        person["doc"]["requires"] = {"capabilities": ["payProvider"]}   # a capability of a DEPENDENCY must be declared by the app too
        self.assertEqual(cat.transitive_needs(top, by_id), (["midUser", "personManager", "personStaff", "topUser"], ["payProvider"]))
        card, why = cat.build_card(top, by_id)
        self.assertIsNone(why)
        for token in ("topUser", "midUser", "personManager", "personStaff"):
            self.assertIn(f'"{token}": "<YourRole>"', card["use"])
        self.assertIn('"capabilities": ["payProvider"]', card["use"])
        # the card's own role list stays the pack's own roles (the budget is for what the pack provides)
        self.assertEqual([r.split(" ")[0] for r in card["provides"]["roles"]], ["topUser"])
        # a requirement cycle must terminate
        person["requires"] = ["top"]
        self.assertIn("topUser", cat.transitive_needs(person, by_id)[0])

    def test_the_budget_member_counts_toward_the_budget_so_a_347_token_card_is_trimmed_not_failed(self):
        # sales + its grant list measured 347 raw tokens; the `budgetTokens` member adds 4, so the card was 351 and the build failed with NOTHING trimmed
        card = {"id": "p", "version": "1.0.0", "trust": "draft", "need": "n", "provides": {"events": ["e"], "concepts": ["C"]}, "use": "{}", "limit": "l"}
        pad = 1
        while True:
            card["provides"]["events"] = ["e" * pad]
            if cat._size(card) >= cat.CARD_TOKENS - cat.BUDGET_MEMBER_TOKENS + 1:
                break
            pad += 1
        self.assertLessEqual(cat._size(card), cat.CARD_TOKENS)   # raw size fits ...
        fitted, why = cat.fit_budget(card)
        self.assertIsNone(why)                                    # ... and the card with its own member still fits, by dropping events
        self.assertLessEqual(fitted["budgetTokens"], cat.CARD_TOKENS)
        self.assertNotIn("events", fitted["provides"])

    def test_card_validates_against_its_schema(self):
        import npdev_jsonschema
        schema = json.loads((REPO / "NPDevContract" / "schemas" / "ai-catalog" / "card.schema.json").read_text(encoding="utf-8"))
        card, _ = cat.build_card(_entry(requires=["contacts"]))
        self.assertEqual(npdev_jsonschema.validate(schema, card), [])

    def test_oversized_card_is_trimmed_in_a_fixed_order_and_keeps_concepts(self):
        doc = _doc(events=[{"name": f"Event{i}"} for i in range(400)],
                   autoPanels=[{"name": f"Panel{i}", "route": f"/p{i}"} for i in range(80)])
        card, why = cat.build_card(_entry(doc))
        self.assertIsNone(why)
        self.assertNotIn("events", card["provides"])
        self.assertNotIn("screens", card["provides"])
        self.assertIn("concepts", card["provides"])
        self.assertLessEqual(card["budgetTokens"], cat.CARD_TOKENS)

    def test_trimming_stops_as_soon_as_the_card_fits(self):
        doc = _doc(events=[{"name": f"Event{i}"} for i in range(20)],
                   autoPanels=[{"name": f"Panel{i}", "route": f"/p{i}"} for i in range(300)])
        card, _ = cat.build_card(_entry(doc))
        self.assertNotIn("screens", card["provides"])
        self.assertEqual(len(card["provides"]["events"]), 20)

    def test_a_card_that_cannot_fit_fails_by_name_instead_of_going_over_budget(self):
        doc = _doc()
        doc["concepts"] = [{"name": "C" + "x" * 1600, "fields": []}]
        card, why = cat.build_card(_entry(doc))
        self.assertIsNone(card)
        self.assertIn("limit 350", why)


class Workshop(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def put(self, doc, status=None, handoff=None, area="sales"):
        folder = self.root / "packs" / area / doc["pack"] / doc["version"]
        folder.mkdir(parents=True)
        (folder / "pack.json").write_text(json.dumps(doc), encoding="utf-8")
        if handoff:
            (folder / "handoff.json").write_text(json.dumps(handoff), encoding="utf-8")
        if status:
            run = self.root / "runs" / f"{doc['pack']}@{doc['version']}" / "20261114-100000"
            run.mkdir(parents=True)
            steps = [{"step": "static", "result": "pass"}, {"step": "compose", "result": "pass" if status != "rejected" else "fail"}]
            (run / "verdict.json").write_text(json.dumps({"schemaVersion": "npdev-pack-verdict.v1", "pack": doc["pack"],
                                                          "version": doc["version"], "status": status, "steps": steps}), encoding="utf-8")

    def test_trust_comes_from_the_verdict_and_untested_or_rejected_packs_are_left_out(self):
        self.put(_doc("a-draft"), "secure")
        self.put(_doc("b-verified"), "probed")
        self.put(_doc("c-certified"), "reviewed")
        self.put(_doc("d-rejected"), "rejected")
        self.put(_doc("e-untested"))
        entries, skipped, problems = cat.build(self.root, include_builtin=False)
        self.assertEqual(problems, [])
        self.assertEqual({e["id"]: e["trust"] for e in entries}, {"a-draft": "draft", "b-verified": "verified", "c-certified": "certified"})
        self.assertEqual({s["id"]: s["why"] for s in skipped}, {"d-rejected": "rejected at compose", "e-untested": "never tested (no verdict)"})

    def test_handoff_first_limit_becomes_the_card_limit_and_highest_version_is_used(self):
        self.put(_doc("leads", "1.0.0"), "secure")
        self.put(_doc("leads", "1.0.1"), "secure", handoff={"limits": ["only one pipeline", "second"]})
        entries, _, _ = cat.build(self.root, include_builtin=False)
        self.assertEqual([(e["id"], e["version"]) for e in entries], [("leads", "1.0.1")])
        self.assertEqual(entries[0]["card"]["limit"], "only one pipeline")

    def test_builtins_are_verified_and_a_higher_workshop_version_replaces_one(self):
        entries, _, problems = cat.build(None, include_builtin=True)
        self.assertEqual(problems, [])
        by_id = {e["id"]: e for e in entries}
        self.assertEqual(by_id["products"]["trust"], "verified")
        self.assertNotIn("project-tracker-demo", by_id)
        self.assertTrue(all(e["card"]["budgetTokens"] <= cat.CARD_TOKENS for e in entries))
        higher = _doc("products", "99.0.0")
        self.put(higher, "secure")
        entries, _, _ = cat.build(self.root, include_builtin=True)
        self.assertEqual({e["id"]: e for e in entries}["products"]["version"], "99.0.0")

    def test_output_layout_and_report(self):
        self.put(_doc("leads"), "probed")
        entries, skipped, problems = cat.build(self.root, include_builtin=False)
        out = self.root / "out"
        report = cat.write_output(out, entries, skipped, problems)
        self.assertTrue(report.is_file())
        self.assertEqual((out / "catalog" / "l0.txt").read_text(encoding="utf-8").count("\n"), 1)
        # scripts may not read markdown (check-no-markdown-reads): the file must exist, its TEXT is checked where it is rendered
        self.assertTrue((out / "README.catalog.md").is_file())
        readme = cat.readme_section(entries)
        self.assertIn("raw.githubusercontent.com/MarceloGiazzon/NPR/main/catalog/l0.txt", readme)
        self.assertIn("catalog/cards/leads.json", readme)
        self.assertIn("1 reusable packs", readme)
        self.assertEqual(json.loads((out / "catalog" / "cards" / "leads.json").read_text(encoding="utf-8"))["trust"], "verified")


if __name__ == "__main__":
    unittest.main()
