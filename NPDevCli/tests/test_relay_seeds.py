"""Sample data in a browser AI's answer (REG-323 F4): `seeds/<id>.json`, checked with the seed schema's and the seed runner's rules
before it is accepted, written all-or-nothing with the model, undone with it, and carried into the app `npdev dev` and `run` build.

Why it matters: staging FAILS GENERATION of the whole app when a seed's id is not its file name; every other mistake (a concept the
model does not have, a `$ref:` to an alias nobody declared, a generator that does not exist, templating in a `raw` seed, a `count`
of a million) shows only when the owner clicks "Load sample data" -- so the AI is told first, in plain words, before anything is
written.

Part 1 the pure check. Part 2 the lists in relay-ingest-policy.json pinned to seed.schema.json and SeedDataService. Part 3 whole
answers through ingest -> apply -> undo with the Java validator and the authoring gate stubbed. Part 4 the generation side: the captured
generate (`dev`, `run`) stages seeds like `generate app` does.

Run with: python -m unittest NPDevCli.tests.test_relay_seeds -v
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_cli as cli  # noqa: E402
import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_files as files_mod  # noqa: E402
import npdev_relay_seeds as seeds  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import manifest  # noqa: E402
from test_relay_files import SplitAnswerCase, answer_of, snapshot  # noqa: E402
from test_relay_themes import seed as seed_model  # noqa: E402

POLICY = seeds.load_policy()
CUSTOMER = {"customerRef": "C-1", "firstName": "Ana", "lastName": "Costa"}


def good(**changes) -> dict:
    out = {"id": "demo", "label": "Demo customers", "records": [{"concept": "Customer", "data": dict(CUSTOMER)}]}
    out.update(changes)
    return out


def check(value, path="seeds/demo.json", model=None, app_dir=None) -> dict:
    text = value if isinstance(value, str) else json.dumps(value)
    return seeds.check({path: text}, seed_model() if model is None else model, app_dir)


def codes(checked: dict) -> list:
    return [f["code"] for f in checked["findings"]]


def lines(checked: dict) -> str:
    return " | ".join(checked["errors"])


class CheckTests(unittest.TestCase):
    def test_a_clean_seed_is_accepted_and_the_owner_is_told_it_runs_on_demand(self):
        checked = check(good())
        self.assertEqual(checked["errors"], [])
        self.assertEqual(codes(checked), ["seed-added", "seed-on-demand"])
        self.assertEqual(checked["findings"][0]["detail"], {"path": "seeds/demo.json", "records": 1})
        self.assertEqual(checked["seeds"], ["seeds/demo.json"])

    def test_a_seed_the_app_already_has_is_replaced_not_added(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "seeds").mkdir()
            (Path(tmp) / "seeds" / "demo.json").write_text("{}", encoding="utf-8")
            self.assertEqual(codes(check(good(), app_dir=tmp))[0], "seed-replaced")

    def test_an_id_that_is_not_the_file_name_is_sent_back_saying_it_fails_the_whole_app(self):
        self.assertIn("must equal the file name (demo)", lines(check(good(id="customers"))))
        self.assertIn("whole app cannot be generated", lines(check(good(id="customers"))))
        missing = good()
        del missing["id"]
        self.assertIn('needs an "id"', lines(check(missing)))

    def test_a_label_is_needed_because_the_owner_chooses_a_seed_by_it(self):
        value = good()
        del value["label"]
        self.assertIn('needs a "label"', lines(check(value)))
        self.assertIn('needs a "label"', lines(check(good(label="  "))))

    def test_where_a_seed_may_live_and_how_it_is_named(self):
        self.assertIn("directly under seeds/", lines(check(good(), path="seeds/a/demo.json")))
        self.assertIn("directly under seeds/", lines(check(good(id="my seed"), path="seeds/my seed.json")))
        self.assertTrue(seeds.is_seed_path("seeds/demo.json"))
        self.assertFalse(seeds.is_seed_path("seeds/.json"))
        self.assertFalse(seeds.is_seed_path("seeds/demo.txt"))
        self.assertFalse(seeds.is_seed_path("web/demo.json"))

    def test_not_json_not_an_object_and_no_records_list_are_refused_in_words(self):
        self.assertIn("not valid JSON", lines(check("{broken")))
        self.assertIn("must be an object", lines(check("[1, 2]")))
        self.assertIn('needs "records"', lines(check(good(records={"concept": "Customer"}))))

    def test_a_key_the_schema_does_not_know_is_named(self):
        self.assertIn('"title" is not allowed in a seed file', lines(check(good(title="x"))))
        value = good(records=[{"concept": "Customer", "values": CUSTOMER}])
        self.assertIn('"values" is not a record key', lines(check(value)))
        self.assertIn('inside "data"', lines(check(value)))

    def test_the_kind_is_smart_or_raw(self):
        self.assertIn('"kind" must be smart or raw', lines(check(good(kind="fancy"))))
        self.assertEqual(check(good(kind="raw"))["errors"], [])

    def test_a_concept_the_model_does_not_have_is_sent_back_naming_the_ones_it_has(self):
        checked = check(good(records=[{"concept": "Client", "data": {}}]))
        self.assertIn('the model has no concept "Client"', lines(checked))
        self.assertIn("Customer", lines(checked))
        self.assertIn('needs a "concept"', lines(check(good(records=[{"data": {}}]))))

    def test_when_a_pack_or_fragment_may_supply_the_concept_it_is_a_warning_said_once(self):
        records = [{"concept": "Client", "data": {}}, {"concept": "Client", "data": {}}]
        checked = check(good(records=records), model={**seed_model(), "packs": [{"from": "x"}]})
        self.assertEqual(checked["errors"], [])
        self.assertEqual(codes(checked).count("seed-concept-unchecked"), 1)

    def test_a_field_the_concept_does_not_have_is_a_warning_and_id_is_always_fine(self):
        checked = check(good(records=[{"concept": "Customer", "data": {**CUSTOMER, "fistName": "x", "id": "k1"}}]))
        self.assertEqual(checked["errors"], [])
        unknown = next(f for f in checked["findings"] if f["code"] == "seed-field-unknown")
        self.assertEqual(unknown["detail"]["fields"], ["fistName"])

    def test_a_ref_needs_an_alias_declared_in_an_earlier_record(self):
        order = {"concept": "Order", "data": {"customerId": "$ref:ana"}}
        ana = {"concept": "Customer", "alias": "ana", "data": CUSTOMER}
        self.assertEqual(check(good(records=[ana, order]))["errors"], [])
        late = lines(check(good(records=[order, ana])))
        self.assertIn('refers to "ana" but no EARLIER record has that alias', late)
        self.assertIn("record 0", late)   # the position is named

    def test_a_generator_has_to_exist_and_a_random_pick_needs_its_concept_saved_first(self):
        gen = lambda value: good(records=[{"concept": "Customer", "data": {**CUSTOMER, "firstName": value}}])  # noqa: E731
        self.assertEqual(check(gen("$gen:name"))["errors"], [])
        self.assertEqual(check(gen("$gen:enum-pick:a,b"))["errors"], [])
        self.assertIn('generator "colour" that does not exist', lines(check(gen("$gen:colour"))))
        pick = {"concept": "Order", "data": {"customerId": "$gen:ref-pick-random:Customer"}}
        customer = {"concept": "Customer", "data": CUSTOMER}
        self.assertIn('no EARLIER record saves that concept', lines(check(good(records=[pick, customer]))))
        self.assertEqual(check(good(records=[customer, pick]))["errors"], [])

    def test_a_raw_seed_cannot_template_and_each_way_to_do_it_is_named(self):
        raw = lambda *records: good(kind="raw", records=list(records))  # noqa: E731
        self.assertIn('cannot use "alias"', lines(check(raw({"concept": "Customer", "alias": "a", "data": CUSTOMER}))))
        self.assertIn('cannot use "count"', lines(check(raw({"concept": "Customer", "count": 2, "data": CUSTOMER}))))
        self.assertIn("uses $gen: but a \"raw\" seed does not template",
                      lines(check(raw({"concept": "Customer", "data": {**CUSTOMER, "firstName": "$gen:name"}}))))
        self.assertIn("uses $ref: but a \"raw\" seed does not template",
                      lines(check(raw({"concept": "Order", "data": {"customerId": "$ref:ana"}}))))

    def test_a_file_value_is_refused_because_a_chat_cannot_send_the_file(self):
        value = good(records=[{"concept": "Customer", "data": {**CUSTOMER, "firstName": "$file:img/ana.png"}}])
        self.assertIn("a chat cannot send the file", lines(check(value)))

    def test_the_ways_to_make_many_rows_are_checked(self):
        many = lambda **keys: good(records=[{"concept": "Customer", **keys}])  # noqa: E731
        self.assertEqual(check(many(count=3, data=CUSTOMER))["errors"], [])
        self.assertEqual(check(many(repeatOver={"vars": {"i": [1, 5]}}, data=CUSTOMER))["errors"], [])
        self.assertIn("use only one", lines(check(many(count=2, repeatOver={"vars": {"i": [1, 2]}}, data=CUSTOMER))))
        self.assertIn('needs "data"', lines(check(many(count=2))))
        self.assertIn("cannot have an \"alias\"", lines(check(many(count=2, alias="a", data=CUSTOMER))))
        self.assertIn("cannot share one \"id\"", lines(check(many(count=2, id="a", data=CUSTOMER))))
        for bad in (0, -1, True, "3", 2.5):
            self.assertIn('"count" must be a whole number', lines(check(many(count=bad, data=CUSTOMER))), bad)
        for bad in ({"vars": {}}, {"vars": {"i": [5, 1]}}, {"vars": {"i": [1]}}, {"vars": {"i": ["a", "b"]}}, {"i": [1, 2]}, [], {"vars": {"i": [1, 2]}, "x": 1}):
            self.assertIn('"repeatOver" must be', lines(check(many(repeatOver=bad, data=CUSTOMER))), bad)

    def test_the_size_of_what_a_seed_makes_is_bounded_and_the_edge_is_allowed(self):
        edge = POLICY["maxExpandedRecords"]
        one = lambda count: good(records=[{"concept": "Customer", "count": count, "data": CUSTOMER}])  # noqa: E731
        self.assertEqual(check(one(edge))["errors"], [])
        self.assertIn(f"would make {edge + 1} rows", lines(check(one(edge + 1))))
        product = good(records=[{"concept": "Customer", "repeatOver": {"vars": {"i": [1, 100], "j": [1, 101]}}, "data": CUSTOMER}])
        self.assertIn("would make 10100 rows", lines(check(product)))
        two_blocks = good(records=[{"concept": "Customer", "count": edge, "data": CUSTOMER}] * 2)
        self.assertIn(f"would make {2 * edge} rows", lines(check(two_blocks)))

    def test_a_file_over_the_byte_limit_is_refused_before_it_is_parsed(self):
        big = good(records=[{"concept": "Customer", "data": {**CUSTOMER, "firstName": "x" * (POLICY["maxFileBytes"] + 10)}}])
        self.assertIn("is larger than", lines(check(big)))

    def test_an_empty_list_of_records_is_a_warning_not_a_refusal(self):
        checked = check(good(records=[]))
        self.assertEqual(checked["errors"], [])
        self.assertIn("seed-empty", codes(checked))

    def test_every_problem_of_one_seed_is_reported_together_and_a_good_seed_beside_it_is_still_accepted(self):
        both = seeds.check({"seeds/demo.json": json.dumps(good()), "seeds/bad.json": json.dumps(good(id="other", label=""))}, seed_model())
        self.assertEqual(len(both["errors"]), 2)
        self.assertTrue(all("seeds/bad.json" in e for e in both["errors"]))
        self.assertEqual([f["detail"]["path"] for f in both["findings"] if f["code"] == "seed-added"], ["seeds/demo.json"])


class ChangedTests(unittest.TestCase):
    def test_a_new_or_different_seed_is_a_change_and_re_indenting_is_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(seeds.changed(tmp, {"seeds/demo.json": json.dumps(good())}), ["seeds/demo.json"])
            (Path(tmp) / "seeds").mkdir()
            (Path(tmp) / "seeds" / "demo.json").write_text(json.dumps(good(), indent=4), encoding="utf-8")
            self.assertEqual(seeds.changed(tmp, {"seeds/demo.json": json.dumps(good())}), [])
            self.assertEqual(seeds.changed(tmp, {"seeds/demo.json": json.dumps(good(label="Other"))}), ["seeds/demo.json"])
            (Path(tmp) / "seeds" / "demo.json").write_text("{broken", encoding="utf-8")
            self.assertEqual(seeds.changed(tmp, {"seeds/demo.json": json.dumps(good())}), ["seeds/demo.json"])
            self.assertEqual(seeds.changed(tmp, {"web/x.html": "<p/>", "model.json": "{}"}), [])


class PolicyPinTests(unittest.TestCase):
    def test_the_lists_match_the_seed_schema(self):
        schema = json.loads((REPO / "NPDevContract" / "schemas" / "seed.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(sorted(POLICY["fileKeys"]), sorted(schema["properties"]))
        self.assertEqual(sorted(POLICY["recordKeys"]), sorted(schema["$defs"]["record"]["properties"]))
        self.assertEqual(POLICY["kinds"], schema["properties"]["kind"]["enum"])
        self.assertEqual(re.compile(schema["properties"]["id"]["pattern"]).pattern, seeds.FILE_ID.pattern)
        self.assertEqual(re.compile(schema["$defs"]["record"]["properties"]["alias"]["pattern"]).pattern, seeds.ALIAS.pattern)

    def test_the_generators_match_the_ones_the_seed_runner_has(self):
        java = (REPO / "NPDevRuntimeHost" / "runtimehost-core" / "src" / "main" / "java" / "com" / "finalexec" / "seed"
                / "SeedDataService.java").read_text(encoding="utf-8")
        self.assertEqual(sorted(POLICY["generators"]), sorted(re.findall(r'case "([a-z-]+)" ->', java)))

    def test_f4_may_carry_seeds_and_the_menu_and_phase_mvp_may_not(self):
        policy = json.loads((REPO / "NPDevCli" / "relay-ingest-policy.json").read_text(encoding="utf-8"))["paths"]["phases"]
        for name in ("seeds/*.json", "menu.json"):
            self.assertIn(name, policy["f4"]["allowed"])
            self.assertNotIn(name, policy["mvp"]["allowed"])
            self.assertIn(name, files_mod.SUPPORTED)
        self.assertTrue(files_mod.is_supported("seeds/demo.json"))


class SeedAnswerTests(SplitAnswerCase):
    """Whole answers with phase f4: ingest -> apply -> undo."""

    def answer(self, code: str, files: dict, model=None) -> str:
        return answer_of(code, 1, {"model.json": model if model is not None else seed_model(),
                                   "npdev-submission.json": manifest(), **files})

    def ingest(self, app: Path, files: dict, model=None) -> tuple:
        code = self.start(app, ask="Add some sample customers")
        return code, engine.ingest(code, self.answer(code, files, model), source="paste")

    def test_a_seed_is_applied_with_the_model_and_undone_with_it(self):
        app = self.make_app()
        before = snapshot(app)
        code, result = self.ingest(app, {"seeds/demo.json": good()})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("seed-added", [f["code"] for f in result["findings"]])
        self.assertEqual(snapshot(app), before)   # nothing is written until the owner applies
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["files"], ["seeds/demo.json", "model.json"])   # model.json last
        self.assertEqual(json.loads((app / "seeds" / "demo.json").read_text(encoding="utf-8")), good())
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)
        self.assertFalse((app / "seeds").exists())   # the folder the answer made is gone

    def test_a_seed_alone_leaves_model_json_identical_and_is_still_a_change(self):
        app = self.make_app()
        code, result = self.ingest(app, {"seeds/demo.json": good()})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])

    def test_answering_with_the_seed_the_app_already_has_is_nothing_changed(self):
        app = self.make_app()
        (app / "seeds").mkdir()
        (app / "seeds" / "demo.json").write_text(json.dumps(good(), indent=2), encoding="utf-8")
        _, result = self.ingest(app, {"seeds/demo.json": good()})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("identical to the current model", " ".join(result["errorLines"]))

    def test_an_id_that_is_not_the_file_name_is_sent_back_and_nothing_is_written(self):
        app = self.make_app()
        before = snapshot(app)
        _, result = self.ingest(app, {"seeds/demo.json": good(id="customers")})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("must equal the file name", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_a_concept_the_model_does_not_have_is_sent_back(self):
        _, result = self.ingest(self.make_app(), {"seeds/demo.json": good(records=[{"concept": "Client", "data": {}}])})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn('no concept "Client"', " ".join(result["errorLines"]))

    def test_the_default_phase_still_denies_a_seed(self):
        app = self.make_app()
        code = engine.start(intent="change", ask="x", app_dir=str(app))["code"]   # phase mvp
        result = engine.ingest(code, self.answer(code, {"seeds/demo.json": good()}), source="paste")
        self.assertEqual({f["path"]: f["status"] for f in result["files"]}["seeds/demo.json"], "denied")


class StagedByTheCapturedGenerateTests(unittest.TestCase):
    """`npdev dev` and `run` use the captured generate; a seed beside the model must reach the app they build."""

    def run_generate(self, tmp: str):
        done = subprocess.CompletedProcess([], 0, stdout="", stderr="")
        model, config = Path(tmp) / "model.json", Path(tmp) / "config.json"
        model.write_text("{}", encoding="utf-8")
        config.write_text("{}", encoding="utf-8")
        args = mock.Mock(model=str(model), config=str(config), require_db_definition=False, web_assets=None)
        with mock.patch.object(cli, "_run_bounded", return_value=done), \
                mock.patch.object(cli, "_emit_static_pages"), mock.patch.object(cli, "claim_artifact_dir"):
            return cli._generate_phase_captured(REPO, args, Path(tmp) / "app", float("inf"))

    def test_a_seed_beside_the_model_is_staged_into_the_app_with_its_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "seeds").mkdir()
            (Path(tmp) / "seeds" / "demo.json").write_text(json.dumps(good()), encoding="utf-8")
            ok, _ = self.run_generate(tmp)
            self.assertTrue(ok)
            staged = Path(tmp) / "app" / "src" / "main" / "resources" / "npdev-seed" / "data-seeds"
            self.assertTrue((staged / "demo.json").is_file())
            self.assertEqual([m["id"] for m in json.loads((staged / "index.json").read_text(encoding="utf-8"))], ["demo"])

    def test_no_seeds_folder_means_nothing_is_staged(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, _ = self.run_generate(tmp)
            self.assertTrue(ok)
            self.assertFalse((Path(tmp) / "app" / "src" / "main" / "resources" / "npdev-seed" / "data-seeds").exists())

    def test_a_seed_that_is_not_json_stops_the_phase_with_the_reason_instead_of_crashing_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "seeds").mkdir()
            (Path(tmp) / "seeds" / "demo.json").write_text("{broken", encoding="utf-8")
            ok, output = self.run_generate(tmp)
            self.assertFalse(ok)
            self.assertIn("Expecting", output)   # the JSON decoder's own words

    def test_an_id_that_is_not_the_file_name_stops_the_phase_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "seeds").mkdir()
            (Path(tmp) / "seeds" / "demo.json").write_text(json.dumps(good(id="other")), encoding="utf-8")
            ok, output = self.run_generate(tmp)
            self.assertFalse(ok)
            self.assertIn("must match the filename stem", output)


if __name__ == "__main__":
    unittest.main()
