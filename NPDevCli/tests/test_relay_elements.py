"""Element upsert (REG-323 T4.2): an npdev-elements.json answer expands into the whole model and is then checked,
previewed, applied and undone exactly like a whole-file answer.

Part 1 is the pure expander (no app, no Java): one test per common AI-answer situation. Part 2 pins the policy's key lists
against model.schema.json, so a schema change cannot leave the lists wrong. Part 3 drives whole answers through
ingest -> apply -> undo with the Java validator and the authoring gate stubbed (as test_relay_files does).

Run with: python -m unittest NPDevCli.tests.test_relay_elements -v
"""
from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_authoring as authoring  # noqa: E402
import npdev_relay_elements as elements  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_ingest as ri  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import SEED_MODEL, ApplyCase, manifest  # noqa: E402
from test_relay_files import answer_of, passing_gate, snapshot, with_phone  # noqa: E402
from unittest import mock  # noqa: E402

POLICY = ri.load_policy()
SCHEMA = json.loads((REPO / "NPDevContract" / "schemas" / "model.schema.json").read_text(encoding="utf-8"))


def base_model() -> dict:
    return json.loads(SEED_MODEL)


def concept(name: str, *fields: str) -> dict:
    return {"name": name, "fields": [{"name": f, "type": "string"} for f in fields] or [{"name": "label", "type": "string"}]}


def codes(out: dict) -> list:
    return [f["code"] for f in out["findings"]]


def expand(base, upsert=None, **rest):
    payload = dict(rest)
    if upsert is not None:
        payload["upsert"] = upsert
    return elements.expand(base, payload, POLICY)


class ExpandTests(unittest.TestCase):
    def test_a_new_element_is_appended_and_nothing_else_moves(self):
        base = base_model()
        out = expand(base, {"concepts": [concept("Supplier", "name")]})
        self.assertTrue(out["ok"], out["errors"])
        self.assertEqual([c["name"] for c in out["model"]["concepts"]], [c["name"] for c in base["concepts"]] + ["Supplier"])
        self.assertEqual(out["changes"]["added"], 1)
        self.assertEqual({k: v for k, v in out["model"].items() if k != "concepts"}, {k: v for k, v in base.items() if k != "concepts"})

    def test_the_equivalent_whole_file_and_upsert_answers_make_the_same_model(self):
        base = base_model()
        whole = with_phone(base)
        out = expand(base, {"concepts": [whole["concepts"][0]]})
        self.assertEqual(out["model"], whole)

    def test_a_replaced_element_keeps_its_position_and_the_base_is_never_touched(self):
        base = base_model()
        frozen = copy.deepcopy(base)
        changed = with_phone(base)["concepts"][0]
        out = expand(base, {"concepts": [changed]})
        self.assertEqual(out["model"]["concepts"][0], changed)
        self.assertEqual(out["model"]["concepts"][1:], base["concepts"][1:])
        self.assertEqual(base, frozen)

    def test_a_list_that_does_not_exist_yet_is_created(self):
        out = expand(base_model(), {"roles": [{"name": "clerk"}]})
        self.assertTrue(out["ok"], out["errors"])
        self.assertEqual(out["model"]["roles"], [{"name": "clerk"}])

    def test_the_same_element_twice_the_last_one_wins(self):
        first, second = concept("Supplier", "name"), concept("Supplier", "name", "phone")
        out = expand(base_model(), {"concepts": [first, second]})
        self.assertEqual(out["model"]["concepts"][-1], second)
        self.assertEqual(sum(1 for c in out["model"]["concepts"] if c["name"] == "Supplier"), 1)
        self.assertIn("element-duplicate-last-wins", codes(out))

    def test_the_key_written_in_the_singular_or_another_case_is_fixed_and_said(self):
        for written in ("concept", "Concepts", " CONCEPTS "):
            out = expand(base_model(), {written: [concept("Supplier")]})
            self.assertTrue(out["ok"], (written, out["errors"]))
            self.assertIn("root-key-fixed", codes(out))
        out = expand(base_model(), {"capability": [{"name": "Ship", "type": "x"}]})
        self.assertTrue(out["ok"], out["errors"])  # capability -> capabilities (y -> ies)
        self.assertIn("capabilities", out["model"])

    def test_one_element_sent_as_an_object_stands_for_a_list_of_one(self):
        out = expand(base_model(), {"concepts": concept("Supplier")})
        self.assertTrue(out["ok"], out["errors"])
        self.assertIn("element-wrapped", codes(out))

    def test_an_unknown_root_key_is_refused_with_the_keys_that_work(self):
        out = expand(base_model(), {"widgets": [{"name": "w"}]})
        self.assertFalse(out["ok"])
        self.assertIsNone(out["model"])
        self.assertIn("concepts", out["errors"][0])
        self.assertIn("upsert.widgets", out["errors"][0])

    def test_a_list_without_names_is_refused_with_its_reason_and_the_way_out(self):
        out = expand(base_model(), {"bindings": [{"capability": "x", "adapter": "y"}]})
        self.assertFalse(out["ok"])
        self.assertIn("no name", out["errors"][0])
        self.assertIn("complete model.json", out["errors"][0])

    def test_an_element_without_a_name_or_that_is_not_an_object_is_refused_by_position(self):
        out = expand(base_model(), {"concepts": [concept("Ok"), {"fields": []}, "Supplier", {"name": "  "}]})
        self.assertFalse(out["ok"])
        self.assertEqual(len(out["errors"]), 3)
        self.assertIn("upsert.concepts[1]", out["errors"][0])
        self.assertIn("upsert.concepts[2]", out["errors"][1])
        self.assertIn("upsert.concepts[3]", out["errors"][2])

    def test_a_malformed_shape_is_one_plain_error_not_a_crash(self):
        for payload in ({"upsert": ["Supplier"]}, {"upsert": {"concepts": "Supplier"}}, {"remove": ["x"]},
                        {"renames": {"from": "a"}}, {"upsert": {"concepts": [concept("A")]}, "renames": [{"from": "a"}]}):
            out = elements.expand(base_model(), payload, POLICY)
            self.assertFalse(out["ok"], payload)
            self.assertTrue(out["errors"])
        self.assertFalse(elements.expand(base_model(), ["x"], POLICY)["ok"])
        self.assertFalse(elements.expand("not a model", {"upsert": {"concepts": [concept("A")]}}, POLICY)["ok"])

    def test_an_answer_that_changes_nothing_says_so(self):
        for payload in ({}, {"upsert": {}}, {"request": "do it"}):
            out = elements.expand(base_model(), payload, POLICY)
            self.assertFalse(out["ok"], payload)
            self.assertIn("changes nothing", out["errors"][0])

    def test_an_unknown_top_level_key_is_ignored_with_a_warning(self):
        out = expand(base_model(), {"concepts": [concept("Supplier")]}, comment="here you go")
        self.assertTrue(out["ok"])
        self.assertIn("elements-key-ignored", codes(out))

    def test_an_element_sent_unchanged_is_counted_not_reapplied(self):
        base = base_model()
        out = expand(base, {"concepts": [copy.deepcopy(base["concepts"][0])]})
        self.assertTrue(out["ok"])
        self.assertEqual((out["changes"]["unchanged"], out["changes"]["replaced"]), (1, 0))
        self.assertIn("element-unchanged", codes(out))

    def test_an_element_that_lost_most_of_its_content_earns_a_warning(self):
        base = base_model()
        base["concepts"].append(concept("Big", *[f"field{i:02d}" for i in range(30)]))
        out = expand(base, {"concepts": [concept("Big", "field00")]})
        self.assertTrue(out["ok"])  # accepted: the authoring gate decides about removed fields
        self.assertIn("element-shrank", codes(out))
        small = expand(base, {"concepts": [with_phone(base_model())["concepts"][0]]})
        self.assertNotIn("element-shrank", codes(small))

    # ------------------------------------------------------------------ removals

    def test_a_removal_removes_and_a_concept_removal_is_a_deliberate_removal_for_the_gate(self):
        base = base_model()
        victim = base["concepts"][-1]["name"]
        out = elements.expand(base, {"remove": {"concepts": [victim]}}, POLICY)
        self.assertTrue(out["ok"], out["errors"])
        self.assertNotIn(victim, [c["name"] for c in out["model"]["concepts"]])
        self.assertEqual(out["manifest"]["deliberateRemovals"], [{"kind": "concept", "name": victim}])

    def test_removing_something_that_is_not_there_is_a_warning_and_a_no_op(self):
        out = elements.expand(base_model(), {"remove": {"concepts": ["Ghost"]}, "upsert": {"concepts": [concept("Supplier")]}}, POLICY)
        self.assertTrue(out["ok"])
        self.assertIn("remove-missing", codes(out))
        self.assertEqual(out["changes"]["removed"], 0)

    def test_a_name_removed_and_upserted_in_one_answer_is_replaced(self):
        base = base_model()
        name = base["concepts"][0]["name"]
        changed = with_phone(base)["concepts"][0]
        out = elements.expand(base, {"remove": {"concepts": [name]}, "upsert": {"concepts": [changed]}}, POLICY)
        self.assertTrue(out["ok"], out["errors"])
        self.assertIn("remove-and-upsert-same-name", codes(out))
        self.assertEqual(out["model"]["concepts"][0], changed)

    def test_removals_accept_a_bare_name_and_objects_with_a_name(self):
        base = base_model()
        a, b = base["concepts"][0]["name"], base["concepts"][1]["name"]
        out = elements.expand(base, {"remove": {"concepts": [{"name": a}], "roles": "clerk"}}, POLICY)
        self.assertTrue(out["ok"], out["errors"])
        self.assertNotIn(a, [c["name"] for c in out["model"]["concepts"]])
        self.assertIn(b, [c["name"] for c in out["model"]["concepts"]])

    # ------------------------------------------------------------------ renames

    def test_a_rename_with_content_replaces_the_old_element_in_its_place_and_says_renamed_from(self):
        base = base_model()
        old = base["concepts"][0]
        renamed = {**copy.deepcopy(old), "name": "Client"}
        out = elements.expand(base, {"upsert": {"concepts": [renamed]}, "renames": [{"from": old["name"], "to": "Client"}]}, POLICY)
        self.assertTrue(out["ok"], out["errors"])
        names = [c["name"] for c in out["model"]["concepts"]]
        self.assertEqual(names[0], "Client")
        self.assertNotIn(old["name"], names)
        self.assertEqual(out["model"]["concepts"][0]["renamedFrom"], old["name"])
        self.assertEqual(out["manifest"]["renames"], [{"kind": "concept", "from": old["name"], "to": "Client"}])
        self.assertEqual(out["changes"]["renamed"], 1)

    def test_a_rename_alone_renames_the_element_and_keeps_its_content(self):
        base = base_model()
        old = base["concepts"][0]
        out = elements.expand(base, {"renames": [{"old": old["name"], "new": "Client"}]}, POLICY)
        self.assertTrue(out["ok"], out["errors"])
        self.assertIn("rename-only", codes(out))
        self.assertEqual(out["model"]["concepts"][0]["fields"], old["fields"])

    def test_a_rename_that_was_already_applied_or_has_no_source_never_fails(self):
        base = base_model()
        done = elements.expand(base, {"renames": [{"from": "Gone", "to": base["concepts"][0]["name"]}]}, POLICY)
        self.assertTrue(done["ok"], done["errors"])
        self.assertIn("rename-already-applied", codes(done))
        nothing = elements.expand(base, {"upsert": {"concepts": [concept("Supplier")]}, "renames": [{"from": "Gone", "to": "Supplier"}]}, POLICY)
        self.assertTrue(nothing["ok"], nothing["errors"])
        self.assertIn("rename-source-missing", codes(nothing))
        self.assertEqual(nothing["changes"]["added"], 1)

    def test_renaming_onto_a_name_that_exists_is_refused(self):
        base = base_model()
        a, b = base["concepts"][0]["name"], base["concepts"][1]["name"]
        out = elements.expand(base, {"renames": [{"from": a, "to": b}]}, POLICY)
        self.assertFalse(out["ok"])
        self.assertIn("already exists", out["errors"][0])

    # ------------------------------------------------------------------ an app made of several files

    def test_an_element_kept_in_a_fragment_is_refused_never_duplicated(self):
        base = base_model()
        composed = copy.deepcopy(base)
        inline = {"concepts": [base["concepts"][0]]}
        in_file = base["concepts"][1]
        root = {**base, **inline}
        composed["concepts"] = [base["concepts"][0], in_file]
        out = elements.expand(root, {"upsert": {"concepts": [with_phone(base)["concepts"][1]]}}, POLICY, composed=composed)
        self.assertFalse(out["ok"])
        self.assertIn("separate file", out["errors"][0])
        gone = elements.expand(root, {"remove": {"concepts": [in_file["name"]]}}, POLICY, composed=composed)
        self.assertFalse(gone["ok"])
        added = elements.expand(root, {"upsert": {"concepts": [concept("Supplier")]}}, POLICY, composed=composed)
        self.assertTrue(added["ok"], added["errors"])  # a NEW element goes inline next to the references

    def test_an_error_anywhere_means_no_model_at_all(self):
        out = expand(base_model(), {"concepts": [concept("Supplier")], "widgets": [{"name": "w"}]})
        self.assertFalse(out["ok"])
        self.assertIsNone(out["model"])  # the good half is not applied either


def _item_schema(key: str):
    node = SCHEMA["properties"][key].get("items", {})
    for _ in range(10):
        if "$ref" in node and node["$ref"].startswith("#/$defs/"):
            node = SCHEMA["$defs"][node["$ref"].split("/")[-1]]
        elif "then" in node and "else" in node:
            node = node["else"]
        else:
            break
    return node


class PolicyMatchesSchemaTests(unittest.TestCase):
    cfg = POLICY["elements"]

    def root_arrays(self) -> list:
        return [k for k, v in SCHEMA["properties"].items() if v.get("type") == "array" or "items" in v]

    def test_every_identity_key_is_a_root_array_whose_elements_require_a_name(self):
        for key in self.cfg["identityKeys"]:
            self.assertIn(key, self.root_arrays(), f"{key} is not a root array of model.schema.json any more")
            self.assertIn("name", _item_schema(key).get("required", []), f"{key}: elements no longer require a name")

    def test_a_whole_file_only_key_is_a_root_array_and_not_an_identity_key(self):
        for key in self.cfg["wholeFileOnly"]:
            self.assertIn(key, self.root_arrays())
            self.assertNotIn(key, self.cfg["identityKeys"])

    def test_every_root_array_is_decided_one_way_or_the_other(self):
        decided = set(self.cfg["identityKeys"]) | set(self.cfg["wholeFileOnly"])
        self.assertEqual(sorted(set(self.root_arrays()) - decided), [], "a new root array of the model: decide whether it can be upserted by name")

    def test_the_schema_in_schemas_ai_is_the_kits_and_names_the_same_keys_the_expander_reads(self):
        schema = json.loads((REPO / "schemas" / "ai" / "ai-relay-elements.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(set(schema["properties"]), {"upsert", "remove", "renames", "request"})


# ---------------------------------------------------------------------------------------------- whole answers

class ElementsAnswerCase(ApplyCase):
    """The Java validator and the authoring gate are stubbed: these tests are about the answer's journey."""

    def setUp(self):
        super().setUp()
        gate = mock.patch.object(authoring, "run_gate", side_effect=passing_gate)
        gate.start()
        self.addCleanup(gate.stop)

    def start(self, app: Path, phase: str = "f4") -> str:
        return engine.start(intent="change", ask="Add a phone number to the customer", app_dir=str(app), phase=phase)["code"]


class ElementsJourneyTests(ElementsAnswerCase):
    def test_an_upsert_answer_is_ready_with_the_same_model_and_preview_as_the_whole_file(self):
        app = self.make_app()
        whole = with_phone(json.loads(SEED_MODEL))
        code = self.start(app)
        result = engine.ingest(code, answer_of(code, 1, {
            "npdev-elements.json": {"upsert": {"concepts": [whole["concepts"][0]]}}, "npdev-submission.json": manifest()}), source="paste")
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("elements-expanded", [f["code"] for f in result["findings"]])
        self.assertIn("phone", " ".join(result["preview"]["sentences"]))
        expanded = json.loads(next(f["content"] for f in result["files"] if f["path"] == "model.json"))
        expanded.pop("version", None)
        expected = copy.deepcopy(whole)
        expected.pop("version", None)
        self.assertEqual(expanded, expected)

    def test_apply_and_undo_work_on_an_upsert_answer_like_on_a_whole_file(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        whole = with_phone(json.loads(SEED_MODEL))
        engine.ingest(code, answer_of(code, 1, {
            "npdev-elements.json": {"upsert": {"concepts": [whole["concepts"][0]]}}, "npdev-submission.json": manifest()}), source="paste")
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertIn("phone", (app / "model.json").read_text(encoding="utf-8"))
        self.assertFalse((app / "npdev-elements.json").exists())  # the upsert list itself is never written into the app
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)

    def test_removing_a_concept_reaches_the_gate_as_a_deliberate_removal_without_the_ai_repeating_it(self):
        app = self.make_app()
        code = self.start(app)
        victim = json.loads(SEED_MODEL)["concepts"][-1]["name"]
        result = engine.ingest(code, answer_of(code, 1, {"npdev-elements.json": {"remove": {"concepts": [victim]}}}), source="paste")
        submission = next((f for f in result["files"] if f["path"] == "npdev-submission.json"), None)
        self.assertIsNotNone(submission, "the removal was not handed to the gate")
        self.assertEqual(json.loads(submission["content"])["deliberateRemovals"][0]["name"], victim)
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        warning = next(w for w in result["preview"]["warnings"] if w["code"] == f"REMOVAL:concept:{victim}")
        self.assertTrue(warning["needsConfirmation"])  # the owner still decides about data loss

    def test_a_stale_base_is_the_apps_problem_not_the_ais_nothing_is_guessed(self):
        app = self.make_app()
        code = self.start(app)
        changed = json.loads(SEED_MODEL)
        changed["concepts"][0]["fields"].append({"name": "nick", "type": "string"})
        (app / "model.json").write_text(json.dumps(changed, indent=2) + "\n", encoding="utf-8")  # the app moved on
        result = engine.ingest(code, answer_of(code, 1, {"npdev-elements.json": {"upsert": {"concepts": [concept("Supplier")]}}}), source="paste")
        self.assertEqual(result["outcome"], "PARSED")
        self.assertTrue(result.get("baseMoved"))
        self.assertEqual(result["next"]["messageId"], "BASE_MOVED")

    def test_a_bad_upsert_is_needs_fix_with_lines_the_ai_can_act_on(self):
        app = self.make_app()
        code = self.start(app)
        result = engine.ingest(code, answer_of(code, 1, {"npdev-elements.json": {"upsert": {"widgets": [{"name": "w"}]}}}), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("upsert.widgets", result["errorLines"][0])
        self.assertEqual(result["next"]["kind"], "copy-prompt")

    def test_a_new_app_has_nothing_to_upsert_into(self):
        code = engine.start(intent="create", ask="A shop", new_app_name="myshop", phase="f4")["code"]
        result = engine.ingest(code, answer_of(code, 1, {"npdev-elements.json": {"upsert": {"concepts": [concept("Shop")]}}}), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("complete model.json", result["errorLines"][0])

    def test_when_both_files_come_the_whole_model_wins_and_it_is_said(self):
        app = self.make_app()
        code = self.start(app)
        whole = with_phone(json.loads(SEED_MODEL))
        result = engine.ingest(code, answer_of(code, 1, {
            "model.json": whole, "npdev-elements.json": {"upsert": {"concepts": [concept("Supplier")]}},
            "npdev-submission.json": manifest()}), source="paste")
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("elements-ignored", [f["code"] for f in result["findings"]])
        model = json.loads(next(f["content"] for f in result["files"] if f["path"] == "model.json"))
        self.assertNotIn("Supplier", [c["name"] for c in model["concepts"]])

    def test_the_default_phase_never_accepts_an_upsert_file(self):
        app = self.make_app()
        code = self.start(app, phase="mvp")
        result = engine.ingest(code, answer_of(code, 1, {"npdev-elements.json": {"upsert": {"concepts": [concept("Supplier")]}}}), source="paste")
        self.assertNotEqual(result["outcome"], "READY")
        self.assertIn("path-denied", [f["code"] for f in result["findings"]])


if __name__ == "__main__":
    unittest.main()
