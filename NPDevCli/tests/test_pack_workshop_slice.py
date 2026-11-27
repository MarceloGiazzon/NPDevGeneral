"""`npdev pack workshop slice | take | trials`: one pack per chat message (owner order 2026-11-26)."""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_slice as sl  # noqa: E402
import npdev_pack_workshop as ws  # noqa: E402

FIX = ["$.dslVersion - must be the constant value '3.0.0' - Update the model.", "(warning) consider an index"]


class SliceTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        guide = self.root / "guide"
        guide.mkdir()
        (guide / "RULES.md").write_text("rules " * 100, encoding="utf-8")
        (guide / "rules.json").write_text(json.dumps({"schemaVersion": "npdev-pack-rules.v1", "guideVersion": "rules test",
                                                      "file": "RULES.md", "bytes": 1}), encoding="utf-8")

    def delivered(self, pack="multi-company", version="1.0.1", area="people", batch="w1-modules", status="rejected"):
        folder = self.root / "packs" / area / pack / version
        folder.mkdir(parents=True)
        (folder / "pack.json").write_text(json.dumps({"pack": pack, "version": version, "dslVersion": "2.0.0", "concepts": []}),
                                          encoding="utf-8")
        run = self.root / "runs" / f"{pack}@{version}" / "20261125-100000"
        run.mkdir(parents=True)
        steps = [{"step": "static", "result": "pass"}, {"step": "compose", "result": "fail" if status == "rejected" else "pass"}]
        (run / "verdict.json").write_text(json.dumps({"pack": pack, "version": version, "status": status, "steps": steps,
                                                      "feedbackForAi": FIX if status == "rejected" else []}), encoding="utf-8")
        (self.root / "batches").mkdir(exist_ok=True)
        (self.root / "batches" / f"{batch}.json").write_text(json.dumps({"batchId": batch, "deliveries": [
            {"packs": [{"pack": pack, "version": version, "area": area, "status": "delivered"}]}]}), encoding="utf-8")

    def answer(self, version="1.0.2", pack="multi-company"):
        body = {"pack": {"pack": pack, "version": version, "dslVersion": "3.0.0", "concepts": []},
                "handoff": {"schemaVersion": "npdev-pack-handoff.v1", "pack": pack, "version": version,
                            "checklist": [{"id": "english-only", "answer": "yes"}], "limits": ["no billing"]},
                "probes": {"read-own": {"steps": []}}}
        path = self.root / "answer.txt"
        path.write_text("Here it is:\n```json\n" + json.dumps(body) + "\n```\nObrigado.", encoding="utf-8")
        return path

    def test_fix_slice_carries_only_this_pack_and_bumps_the_version(self):
        self.delivered()
        meta = sl.slice_pack(None, self.root, "multi-company", stamp="20261126-0800")
        self.assertEqual((meta["version"], meta["replaces"], meta["batchId"]), ("1.0.2", "1.0.1", "w1-modules"))
        doc = json.loads((Path(meta["folder"]) / "slice.json").read_text(encoding="utf-8"))
        self.assertEqual(doc["task"]["kind"], "fix")
        self.assertEqual(doc["task"]["feedback"]["fix"], FIX[:1])
        self.assertEqual(doc["task"]["feedback"]["advice"], ["consider an index"])
        self.assertEqual(doc["task"]["previousPackJson"]["version"], "1.0.1")
        self.assertIn("packJson", doc["example"])
        self.assertEqual([p["pack"] for p in doc["requiredPacks"]], ["identity"])
        prompt = (Path(meta["folder"]) / "prompt.txt").read_text(encoding="utf-8")
        self.assertIn("1.0.2", prompt)
        # 2026-11-27 bench (payments): dependencies and a required pack's domain types were never explained to the model
        self.assertIn('`requires` is NOT for packs', prompt)
        self.assertIn('"domainType": "<pack>::<Name>"', prompt)
        self.assertLess(meta["bytesIn"]["slice"], 40 * 1024)

    def test_a_secure_pack_has_nothing_to_slice(self):
        self.delivered(status="secure")
        with self.assertRaisesRegex(ws.WorkshopError, "nothing to fix"):
            sl.slice_pack(None, self.root, "multi-company")

    def test_new_pack_needs_a_batch_and_a_builtin_is_refused(self):
        with self.assertRaisesRegex(ws.WorkshopError, "--batch"):
            sl.slice_pack(None, self.root, "documents")
        meta = sl.slice_pack(None, self.root, "documents", batch="w3-docs")
        self.assertEqual((meta["version"], meta["replaces"]), ("1.0.0", None))
        with self.assertRaisesRegex(ws.WorkshopError, "built-in"):
            sl.slice_pack(None, self.root, "contacts", batch="w3-x")

    def _ladder(self, status="secure", fixes=None, failed="compose"):
        """A stand-in for `npdev pack workshop test`: writes a verdict where the real one would and returns it."""
        def run(cli, root, pack, version):
            steps = [{"step": "static", "result": "pass", "seconds": 1.0},
                     {"step": "compose", "result": "fail" if (status == "rejected" and failed == "compose") else "pass", "seconds": 2.0}]
            if status != "rejected":
                steps.append({"step": "access-lint", "result": "pass", "seconds": 0.5})
            verdict = {"pack": pack, "version": version, "status": status, "steps": steps,
                       "feedbackForAi": fixes or [], "_run": str(root / "runs" / f"{pack}@{version}" / "x")}
            run_dir = root / "runs" / f"{pack}@{version}" / "20261126-090000"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "verdict.json").write_text(json.dumps(verdict), encoding="utf-8")
            return verdict
        return run

    def test_take_records_a_browser_bench_run_in_the_run_bench_shape(self):
        self.delivered()
        meta = sl.slice_pack(None, self.root, "multi-company", stamp="20261126-0800")
        bench = self.root / "ai-bench"
        out = sl.take_answer(None, self.root, self.answer(), meta["sliceId"], provider="gemini", model="Neon", effort="Alto",
                             minutes=4, bench_root=bench, tester=self._ladder(), stamp="20261126T081500")
        result = out["result"]
        self.assertTrue(result["passed"])
        self.assertEqual((result["outcome"], result["reached"], result["failingStage"]), ("pass", "secure", None))
        self.assertEqual(result["promptId"], "bench-pack-multi-company")          # run_bench.load_results globs bench-*/
        self.assertEqual(result["wallSeconds"], 240)
        self.assertTrue(result["tokens"]["estimated"])
        self.assertEqual([s["stage"] for s in result["attempts"][0]["stages"]], ["author", "ingest", "static", "compose", "access-lint"])
        run_dir = Path(out["runDir"])
        summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual((summary["arm"], summary["backend"]["kind"], summary["dryRun"]), ("pack-slice", "browser", False))
        self.assertTrue((run_dir / "bench-pack-multi-company" / "result.json").is_file())
        self.assertTrue((run_dir / "bench-pack-multi-company" / "attempt-0" / "author-reply.txt").is_file())
        # no binary in an attempt folder: run_bench.matrix_log prints every file there as text
        self.assertEqual([p.suffix for p in (run_dir / "bench-pack-multi-company" / "attempt-0").iterdir()], [".txt"])
        with zipfile.ZipFile(next((run_dir / "bench-pack-multi-company" / "zips").glob("*.zip"))) as zf:
            batch = json.loads(zf.read("batch.json"))
            self.assertIn("packs/multi-company/probes/read-own.json", zf.namelist())
        self.assertEqual(batch["packs"][0], {"pack": "multi-company", "version": "1.0.2", "roadmapId": "multi-company",
                                             "replaces": "1.0.1"})
        self.assertEqual(batch["author"], {"kind": "browser-ai", "model": "Neon"})
        # a browser answer goes into the REAL workshop: the owner's run is the delivery
        self.assertTrue((self.root / "packs" / "people" / "multi-company" / "1.0.2" / "pack.json").is_file())
        rows = sl.bench_rows(bench)
        self.assertEqual((rows[0]["kind"], rows[0]["model"], rows[0]["effort"], rows[0]["outcome"]), ("browser", "Neon", "Alto", "pass"))
        self.assertIn("multi-company@1.0.2", sl.format_runs(rows))

    def test_take_of_a_rejected_pack_keeps_the_checker_lines(self):
        self.delivered()
        meta = sl.slice_pack(None, self.root, "multi-company")
        out = sl.take_answer(None, self.root, self.answer(), meta["sliceId"], bench_root=self.root / "ai-bench",
                             tester=self._ladder("rejected", ["concepts[0] - bad - fix it", "(warning) advice"]))
        result = out["result"]
        self.assertEqual((result["outcome"], result["failingStage"], result["reached"]), ("fail", "compose", "valid"))
        self.assertEqual(out["evaluation"]["fixes"], ["concepts[0] - bad - fix it"])
        self.assertIn("bad", result["attempts"][0]["feedbackExcerpt"])

    def test_take_of_the_wrong_version_fails_at_author_and_ingests_nothing(self):
        self.delivered()
        meta = sl.slice_pack(None, self.root, "multi-company")
        out = sl.take_answer(None, self.root, self.answer(version="1.0.1"), meta["sliceId"], bench_root=self.root / "ai-bench",
                             tester=self._ladder())
        self.assertEqual((out["result"]["outcome"], out["result"]["failingStage"]), ("fail", "author"))
        self.assertIn("asked for multi-company@1.0.2", out["evaluation"]["feedback"])
        self.assertFalse((self.root / "packs" / "people" / "multi-company" / "1.0.2").exists())
        self.assertEqual(len(sl.bench_rows(self.root / "ai-bench")), 1)      # still recorded: a failure is a measurement

    def _rules_with_text(self):
        doc = json.loads((self.root / "guide" / "rules.json").read_text(encoding="utf-8"))
        doc["text"] = "RULES TEXT"
        (self.root / "guide" / "rules.json").write_text(json.dumps(doc), encoding="utf-8")

    def test_api_prompt_inlines_the_same_bytes_the_browser_gets(self):
        self.delivered()
        meta = sl._slice_meta(self.root, sl.slice_pack(None, self.root, "multi-company")["sliceId"])
        with self.assertRaisesRegex(ws.WorkshopError, "no 'text'"):
            sl.api_prompt(self.root, meta)
        self._rules_with_text()
        prompt = sl.api_prompt(self.root, meta)
        folder = Path(meta["folder"])
        self.assertIn((folder / "prompt.txt").read_text(encoding="utf-8"), prompt)
        self.assertIn("RULES TEXT", prompt)
        self.assertTrue(prompt.endswith((folder / "slice.json").read_text(encoding="utf-8")))

    def test_scratch_evaluation_lets_a_repair_redeliver_the_same_version(self):
        self.delivered()
        meta = sl._slice_meta(self.root, sl.slice_pack(None, self.root, "multi-company")["sliceId"])
        scratch = sl.scratch_workshop(self.root, self.root / "scratch")
        first = sl.evaluate(None, scratch, meta, self.answer().read_text(encoding="utf-8"), self.root / "z1", model="m",
                            mode="api", scratch=True, tester=self._ladder("rejected", ["x - y - z"]))
        self.assertEqual(first["failingStage"], "compose")
        changed = self.answer().read_text(encoding="utf-8").replace('"concepts": []', '"concepts": [], "summary": "v2"')
        second = sl.evaluate(None, scratch, meta, changed, self.root / "z2", model="m", mode="api", scratch=True,
                             tester=self._ladder())
        self.assertIsNone(second["failingStage"])
        self.assertFalse((self.root / "packs" / "people" / "multi-company" / "1.0.2").exists())   # real workshop untouched
        message = sl.repair_message(meta, first)
        self.assertIn("`compose`", message)
        self.assertIn("- x - y - z", message)
        self.assertIn("1.0.2", message)

    def test_a_schema_enum_refusal_names_the_allowed_values(self):
        handoff = {"schemaVersion": "npdev-pack-handoff.v1", "pack": "a", "version": "1.0.0", "limits": [],
                   "checklist": [{"id": "validator-status", "answer": "passed"}]}
        with self.assertRaisesRegex(ws.WorkshopError, r'/checklist/0/answer: .*\(allowed: "yes", "no", "n/a"\)'):
            ws._validate("handoff", handoff, "x")

    def test_limits_table_never_guesses_and_joins_api_facts(self):
        doc = sl.read_limits()
        self.assertTrue(doc["rows"] and doc["apiModels"])
        for row in doc["rows"]:
            self.assertTrue(row["source"], row)
            # no provider publishes a per-answer cap for a chat: the cell must say so, never carry a number
            self.assertEqual(row["answerSize"], "not published, measured", row)
        text = sl.format_limits(doc)
        self.assertIn("API MODELS", text)
        self.assertIn("claude-", text)

    def test_extract_accepts_separate_blocks_and_refuses_prose(self):
        text = ("```json\n" + json.dumps({"pack": "a", "version": "1.0.0", "dslVersion": "3.0.0"}) + "\n```\n"
                "```json\n" + json.dumps({"schemaVersion": "npdev-pack-handoff.v1", "pack": "a"}) + "\n```")
        got = sl.extract_answer(text)
        self.assertEqual((got["pack"]["pack"], got["probes"]), ("a", {}))
        with self.assertRaisesRegex(ws.WorkshopError, "no JSON object"):
            sl.extract_answer("Sorry, I cannot do that.")
        # a real 2026-11-26 answer: the position and the text around the error, so a repair round can fix it
        bad = '{"pack": {"pack": "a"}, "handoff": {}, "probes": {"p.json": {"expect": {"status": 200, "json": "status": "SUBMITTED"}}}}'
        with self.assertRaisesRegex(ws.WorkshopError, r'does not parse: line 1 column \d+: .*near `.*"json": "status"'):
            sl.extract_answer(bad)

    def test_an_inline_fence_inside_a_json_string_does_not_end_the_block(self):
        body = {"pack": {"pack": "a", "description": "see ```sql select 1``` for an example"},
                "handoff": {"schemaVersion": "npdev-pack-handoff.v1"}, "probes": {}}
        got = sl.extract_answer("Here:\n```json\n" + json.dumps(body, indent=2) + "\n```\nBye.")
        self.assertEqual(got["pack"]["description"], "see ```sql select 1``` for an example")
        # the closing fence glued to the last brace is no fence at all: the object between the first `{` and the last `}` is read
        self.assertEqual(sl.extract_answer("```json\n" + json.dumps(body) + "```")["pack"]["pack"], "a")

    def test_two_probe_names_that_collapse_to_one_file_are_refused_before_a_zip_exists(self):
        meta = {"batchId": "b", "pack": "a", "version": "1.0.0", "roadmapId": "a", "guideVersion": "g"}
        clash = {"pack": {"pack": "a"}, "handoff": {}, "probes": {"crm/leads.json": {"steps": []}, "leads": {"steps": []}}}
        with self.assertRaisesRegex(ws.WorkshopError, "'crm/leads.json' and 'leads' would both be written as probes/leads.json"):
            sl.build_zip(meta, clash, self.root / "out", model="m", mode="api", stamp="s")
        self.assertFalse(list((self.root / "out").glob("*.zip")))
        # distinct names: the handoff lists the FILE names that are in the zip, not the model's spelling of them
        fine = {"pack": {"pack": "a"}, "handoff": {}, "probes": {"x/read.json": {"steps": []}, "write": {"steps": []}}}
        path = sl.build_zip(meta, fine, self.root / "out", model="m", mode="api", stamp="s")
        with zipfile.ZipFile(path) as zf:
            self.assertEqual(json.loads(zf.read("packs/a/handoff.json"))["probesIncluded"], ["read.json", "write.json"])
            self.assertIn("packs/a/probes/write.json", zf.namelist())

    def test_a_half_written_result_or_meta_costs_one_row_never_the_listing(self):
        bench = self.root / "ai-bench" / "r1"
        (bench / "bench-ok").mkdir(parents=True)
        (bench / "bench-cut").mkdir()
        (bench / "summary.json").write_text(json.dumps({"runId": "r1", "arm": sl.BENCH_ARM, "startedAt": "2026-11-26T10:00:00"}), encoding="utf-8")
        good = {"pack": "a", "version": "1.0.0", "tokens": {"prompt": 1, "completion": 2}, "backend": {"id": "x"}, "outcome": "pass", "reached": "probed"}
        (bench / "bench-ok" / "result.json").write_text(json.dumps(good), encoding="utf-8")
        (bench / "bench-cut" / "result.json").write_text('{"pack": "b", "tok', encoding="utf-8")
        for slice_id, text in (("s-ok", json.dumps({"pack": "a", "sliceId": "s-ok"})), ("s-cut", '{"pack": "b", "sli')):
            (self.root / "slices" / slice_id).mkdir(parents=True)
            (self.root / "slices" / slice_id / "meta.json").write_text(text, encoding="utf-8")
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            rows = sl.bench_rows(self.root / "ai-bench")
            slices = sl.list_slices(self.root)
        self.assertEqual([r["pack"] for r in rows], ["a"])
        self.assertEqual(slices, ["s-ok"])
        self.assertIn("bench-cut", stderr.getvalue())
        self.assertIn("slice s-cut skipped", stderr.getvalue())

    def test_the_mini_validator_follows_if_then_else_so_a_concept_error_names_the_concept_branch(self):
        import npdev_jsonschema as js
        schema = {"if": {"type": "object", "required": ["$ref"]}, "then": {"properties": {"$ref": {"type": "string"}}},
                  "else": {"type": "object", "required": ["name"], "additionalProperties": False, "properties": {"name": {"type": "string"}, "ui": {"type": "object"}}}}
        self.assertEqual(js.validate(schema, {"name": "A", "ui": {}}), [])
        self.assertEqual(js.validate(schema, {"$ref": "x.json"}), [])
        bad = js.validate(schema, {"name": "A", "queries": []})
        self.assertEqual([(e["keyword"], e.get("unexpected")) for e in bad], [("additionalProperties", "queries")])
        self.assertEqual([e["keyword"] for e in js.validate(schema, {"ui": {}})], ["required"])

    def test_a_wrong_enum_value_lists_what_is_allowed(self):
        pack = {"concepts": [{"name": "Slot", "fields": [{"name": "id", "type": "uuid", "id": True}, {"name": "startsAt", "type": "time"}]}]}
        lines = ws._content_shape_errors(pack)
        self.assertEqual(len(lines), 1)
        self.assertIn("concepts[0].fields[1].type - must be one of the allowed values (allowed: string, uuid,", lines[0])
        self.assertIn("datetime", lines[0])

    def test_a_cut_update_refusal_still_yields_its_problems(self):
        # the head of the JSON document is gone (size limit): only the tail, with JSON-escaped newlines, is left
        cut = ("elds[1]: $.concepts[1].fields[1]: property 'onDelete' is not defined in the schema Suggested fix: Adjust it."
               "\\r\\n - $.concepts[1]: $.concepts[1]: must be valid to one and only one schema, but 0 are valid Suggested fix: Update the model."
               "\\r\\n - $.concepts[1]: $.concepts[1]: required property '$ref' not found Suggested fix: Adjust the '$ref' property.\"\n}\n")
        problems = ws._update_problems(cut)
        self.assertEqual(len(problems), 2)
        self.assertTrue(problems[0].startswith("$.concepts[1] - must be valid to one and only one schema"))
        self.assertEqual(ws._update_problems("no json here and nothing to read"), [])

    def test_the_one_of_noise_is_replaced_by_the_real_reason(self):
        pack = {"concepts": [{"name": "Ok", "fields": [{"name": "id", "type": "uuid", "id": True}]},
                             {"name": "Lead", "fields": [{"name": "id", "type": "uuid", "id": True}], "queries": [{"name": "Open"}]}]}
        real = ws._content_shape_errors(pack)
        self.assertEqual(real, ["pack.json concepts[1] - has an unexpected property 'queries'"])
        noisy = ["$.concepts[1] - must be valid to one and only one schema, but 0 are valid - Update the model.",
                 "$.concepts[1] - property 'fields' is not defined in the schema and the schema does not allow additional properties - Adjust it.",
                 "$.concepts[1] - required property '$ref' not found - Adjust it.",
                 "$.concepts[1].invariants[0] - required property 'expr' not found - Adjust it."]
        out = ws._explain_one_of(pack, noisy)
        self.assertEqual(out, real + [noisy[3]])  # a deeper, specific line is kept; only the item-level noise goes
        # no one-of failure in the lines, or nothing the mini validator can name: the validator's lines pass through
        self.assertEqual(ws._explain_one_of(pack, [noisy[3]]), [noisy[3]])
        self.assertEqual(ws._explain_one_of({"concepts": [pack["concepts"][0]]}, noisy), noisy)

    def test_a_packs_line_with_its_hint_survives_the_one_of_noise_filter(self):
        """`packs` is an array the mini validator never reads: its lines are real errors, hints included."""
        pack = {"concepts": [{"name": "Lead", "fields": [{"name": "id", "type": "uuid", "id": True}], "queries": [{"name": "Open"}]}]}
        hinted = ws._with_shape_hint("$.packs[0] - property 'concepts' is not defined in the schema and the schema does not allow additional properties")
        noise = "$.concepts[0] - must be valid to one and only one schema, but 0 are valid - Update the model."
        out = ws._explain_one_of(pack, [noise, hinted])
        self.assertIn("packsEntry", hinted)
        self.assertIn(hinted, out)
        self.assertNotIn(noise, out)
        self.assertEqual(out[0], "pack.json concepts[0] - has an unexpected property 'queries'")

    def test_the_non_ascii_lint_tells_punctuation_from_another_language(self):
        folder = self.root / "lint"
        folder.mkdir()
        path = folder / "pack.json"
        # a non-breaking hyphen (x2) and a curly apostrophe are punctuation; "c-cedilla + a-tilde" is Portuguese
        text = '{"pack": "a", "concepts": [], "description": "tamper\\u2011resistant, write\\u2011once, it\\u2019s fine", "note": "Corre\\u00e7\\u00e3o"}'
        path.write_text(json.dumps(json.loads(text), ensure_ascii=False), encoding="utf-8")
        errors, warnings = ws.lint_pack(path)
        ascii_lines = [w for w in warnings if "non-ASCII" in w]
        self.assertEqual(errors, [])
        self.assertEqual(len(ascii_lines), 4, ascii_lines)   # one per DISTINCT character (U+2011, U+2019, c-cedilla, a-tilde)
        hyphen = next(w for w in ascii_lines if "U+2011" in w)
        self.assertIn("replace all 2 with the plain ASCII '-'", hyphen)
        self.assertNotIn("translate", hyphen)
        self.assertIn("plain ASCII \"'\"", next(w for w in ascii_lines if "U+2019" in w))
        self.assertIn("translate it", next(w for w in ascii_lines if "U+00E7" in w))

    def test_a_cut_refusal_hands_the_ai_clean_quotes_not_json_escapes(self):
        cut = ("elds[1]: $.concepts[1].fields[1]: property 'onDelete' is not defined Suggested fix: Adjust it."
               "\\r\\n - $.concepts[1]: $.concepts[1]: property \\\"unique\\\" is not defined Suggested fix: Remove \\\"unique\\\".\"\n}\n")
        problems = ws._update_problems(cut)
        self.assertEqual(len(problems), 1)
        self.assertIn('property "unique" is not defined', problems[0])
        self.assertNotIn("\\", problems[0])

    def test_the_bench_failures_of_payables_and_quotes_get_a_how_to_fix(self):
        unique = ws._with_shape_hint("$.concepts[1].invariants[2] - property 'unique' is not defined in the schema and the schema does not allow additional properties")
        self.assertIn('"indexes"', unique)
        self.assertIn('"unique": true', unique)
        packs = ws._with_shape_hint("$.packs[0] - property 'concepts' is not defined in the schema and the schema does not allow additional properties")
        self.assertIn("packsEntry", packs)
        # a field of a concept with the same words is not an invariant, and a plain unknown property stays untouched
        other = "$.concepts[1].fields[2] - property 'unique' is not defined in the schema"
        self.assertEqual(ws._with_shape_hint(other), other)

    def test_a_fix_slice_moves_the_old_dependency_constraints_to_the_current_ones(self):
        previous = {"packs": [{"pack": "contacts", "version": "^1.0"}, {"pack": "identity", "version": "^1.0"}, {"pack": "other", "version": "^3.0"}]}
        required = [{"pack": "contacts", "packsEntry": {"pack": "contacts", "version": "^2.0"}},
                    {"pack": "identity", "packsEntry": {"pack": "identity", "version": "^1.0"}}, {"pack": "ghost", "missing": "not built"}]
        self.assertEqual(sl._refresh_dependency_entries(previous, required), ["contacts ^2.0"])
        self.assertEqual([e["version"] for e in previous["packs"]], ["^2.0", "^1.0", "^3.0"])
        self.assertEqual(sl._refresh_dependency_entries({}, required), [])

    def test_a_key_the_concept_does_not_know_is_named_instead_of_the_one_of_noise(self):
        pack = {"concepts": [{"name": "Lead", "fields": [], "events": [], "queries": [{"name": "Open"}], "colour": "red"},
                             {"$ref": "other.json"}, {"name": "Ok", "fields": []}]}
        lines = ws._concept_key_errors(pack)
        self.assertEqual(len(lines), 2)
        self.assertIn("concepts[0] (Lead) queries", lines[0])
        self.assertIn("top-level array of the pack", lines[0])
        self.assertIn('"concept": "Lead"', lines[0])
        self.assertIn("concepts[0] (Lead) colour - not a concept key", lines[1])
        self.assertEqual(ws._concept_key_errors({"concepts": "nope"}), [])
        self.assertEqual(ws._concept_key_errors({"concepts": [{"name": "Ok", "fields": []}]}), [])

    def test_schema_messages_carry_a_how_to_fix_for_the_shapes_the_bench_saw_fail_twice(self):
        enum = ws._with_shape_hint("$.capabilities[0].operations[0].input.properties.channel.type - must be the constant value 'enum'")
        self.assertIn('{"type": "enum", "enumValues"', enum)
        one_of = ws._with_shape_hint("$.capabilities[0].operations[0].input - must be valid to one and only one schema, but 0 are valid")
        self.assertIn("schemaRef", one_of)
        self.assertIn("never a mix", one_of)
        old = ws._with_shape_hint("$.capabilities[0].operations[0].input.properties.channel - property 'enum' is not defined in the schema")
        self.assertIn("enumValues", old)
        # every other message, and a one-of anywhere but an operation's input/output, passes through untouched
        other = "$.concepts[0].fields[1] - must be valid to one and only one schema, but 0 are valid"
        self.assertEqual(ws._with_shape_hint(other), other)
        self.assertEqual(ws._with_shape_hint("$.dslVersion - must be the constant value '3.0.0'"), "$.dslVersion - must be the constant value '3.0.0'")

    def test_a_reference_to_a_domain_type_says_how_to_write_the_field(self):
        # 2026-11-27 bench (payments): `reference` pointed at pix-key::PixKey, a domain type; the bare message sent the model
        # to `requires`, then to dropping the type. The hint names both readings and the right shape.
        line = ws._with_shape_hint("concepts[payments] - Concept payments::PixCharge field pixKey: reference target not found: pix-key::PixKey "
                                   "- Point the reference at an existing concept or fix the target concept name.")
        self.assertIn('{"type": "string", "domainType": "pix-key::PixKey"}', line)
        self.assertIn("remove `reference`", line)
        self.assertIn("never in `requires`", line)
        # consent-lgpd, same bench: the model wrote its OWN pack id without the hyphen -- say the real spelling, not "domain type"
        own = ws._with_shape_hint("concepts[consent-lgpd] - Concept consent-lgpd::ConsentGrant field consentTerm: reference target not found: "
                                  "consentlgpd::ConsentTerm - Point the reference at an existing concept or fix the target concept name.")
        self.assertIn("'consent-lgpd::ConsentTerm'", own)
        self.assertIn("hyphen included", own)
        self.assertNotIn("domain type", own)
        # a reference target with no pack prefix is the plain "unknown concept" case: no domain-type advice
        plain = "Concept leads::Lead field owner: reference target not found: Owner - fix the name."
        self.assertEqual(ws._with_shape_hint(plain), plain)


if __name__ == "__main__":
    unittest.main()
