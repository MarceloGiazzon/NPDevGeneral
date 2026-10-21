"""TRUTH-1: `npdev truth` joins the box manifest, provenance index and the probe / smoke / journey / browser
reports into a per-concept declared-vs-supported truth rollup. Display only: every missing input degrades
to unknown, nothing here may raise or block."""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "quality" / "behavior_probes"))

import npdev_jsonschema  # noqa: E402
import npdev_truth  # noqa: E402
import run_probes  # noqa: E402

BUILT_AT = "2026-10-20T05:00:00Z"
FRESH = "2026-10-20T06:00:00Z"
OLD = "2026-10-19T06:00:00Z"


def _write(path: Path, doc) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc) if not isinstance(doc, str) else doc, encoding="utf-8")
    return path


def _set_mtime(path: Path, iso: str) -> None:
    stamp = datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()
    os.utime(path, (stamp, stamp))


def _box(name, table, declared, truth="T2_GENERATED"):
    return {"conceptName": name, "displayName": name, "table": table, "module": "Ungrouped",
            "truthLevel": truth, "declaredTruthLevel": declared}


def make_app(root: Path, boxes, *, built_at=BUILT_AT, sample="demo-sample", artifacts=3) -> Path:
    """A minimal generated-app tree: box manifest + provenance index + build info."""
    resources = root / "npdev-generated" / "src" / "main" / "resources" / "npdev"
    _write(resources / "box" / "box-manifest.json",
           {"schemaVersion": "npdev-box-manifest.v2", "application": {"name": "ns.demo", "truthLevel": "T2_GENERATED"},
            "modules": [], "boxes": boxes, "capabilities": []})
    _write(resources / "provenance-index.json", {
        "schemaVersion": "npdev-provenance-index.v1",
        "specNodes": {b["conceptName"]: {"artifacts": [{"path": f"a{i}.java"} for i in range(artifacts)]} for b in boxes}})
    info = root / "src" / "main" / "resources" / "npdev-build-info.properties"
    info.parent.mkdir(parents=True, exist_ok=True)
    info.write_text(f"npdev.builtAt={built_at}\nnpdev.model.sourcePath=D:/x/NPDevSamples/{sample}/Input/model.json\n",
                    encoding="utf-8")
    return root


def probe_report(path: Path, results, *, sample="demo-sample", finished=FRESH):
    return _write(path, {"schemaVersion": "npdev-behavior-probes-result.v1", "generatedAt": OLD, "sampleId": sample,
                         "status": "passed",
                         "probes": {"schemaVersion": "npdev-behavior-probes-run.v1", "generatedAt": finished,
                                    "results": results}})


def probe(pid, status, *paths):
    return {"id": pid, "source": "10.json", "keywords": [], "status": status, "failures": [],
            "steps": [{"name": f"s{i}", "method": "GET", "path": p, "status": 200} for i, p in enumerate(paths)]}


class LevelTests(unittest.TestCase):
    def test_both_data_formats_normalise_to_the_code(self):
        # the box manifest says T2_GENERATED, the model's declaredTruthLevel says T2: same fact
        self.assertEqual(npdev_truth.normalise_level("T2_GENERATED"), "T2")
        self.assertEqual(npdev_truth.normalise_level("T2"), "T2")
        self.assertEqual(npdev_truth.normalise_level("runslocally"), "T3")
        self.assertEqual(npdev_truth.normalise_level(" t6_release_approved "), "T6")
        self.assertIsNone(npdev_truth.normalise_level("T9"))
        self.assertIsNone(npdev_truth.normalise_level(None))
        self.assertIsNone(npdev_truth.normalise_level(""))

    def test_route_maps_table_and_concept_name_but_never_guesses(self):
        index = npdev_truth.route_index([_box("WidgetOrder", "widget_orders", "T4")])
        for path in ("/api/widget_orders", "/api/widget_orders/${id}", "/api/widget_orders?limit=5",
                     "/api/concepts/widget_orders", "/api/concepts/WidgetOrder/page?x=1"):
            self.assertEqual(npdev_truth.concept_for_route(path, index), "WidgetOrder", path)
        for path in ("/api/flows/PlaceWidgetOrder/execute", "/api/widget_order", "/actuator/health", "/api",
                     "/api/queries/WidgetOrder", None, 7):
            self.assertIsNone(npdev_truth.concept_for_route(path, index), path)


class RollupTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.app = make_app(self.tmp / "app", [
            _box("Alpha", "alphas", "T2"),
            _box("Beta", "betas", "T4"),         # declared T4, ends up with fresh probe evidence -> ok
            _box("Gamma", "gammas", "T4"),       # declared T4, no evidence -> overclaim
            _box("Delta", "deltas", "T1"),       # declared T1 -> underclaim once live
            _box("Eps", "epss", "T5"),           # probe + smoke -> T5
        ])
        self.reports = self.tmp / "reports"
        self.reports.mkdir()

    def rollup(self, **kw):
        kw.setdefault("reports_dir", self.reports)
        return npdev_truth.build_rollup(self.app, **kw)

    def by_name(self, rollup):
        return {c["conceptName"]: c for c in rollup["concepts"]}

    def test_without_any_report_every_concept_is_generated_and_declared_levels_are_kept(self):
        rollup = self.rollup()
        concepts = self.by_name(rollup)
        self.assertEqual({n: c["declaredLevel"] for n, c in concepts.items()},
                         {"Alpha": "T2", "Beta": "T4", "Gamma": "T4", "Delta": "T1", "Eps": "T5"})
        self.assertTrue(all(c["supportedLevel"] == "T2" for c in concepts.values()))
        self.assertEqual(rollup["summary"]["lowestSupported"], "T2")
        self.assertEqual(rollup["summary"]["lowestDeclared"], "T1")
        self.assertEqual(rollup["summary"]["overclaims"], 3)        # Beta, Gamma, Eps declare above T2
        self.assertEqual(concepts["Delta"]["claim"], "underclaim")
        gen = concepts["Alpha"]["evidence"][0]
        self.assertEqual((gen["kind"], gen["level"], gen["status"]), ("generated", "T2", "observed"))
        self.assertIn("3 artifact", gen["summary"])
        self.assertEqual(rollup["app"]["sampleId"], "demo-sample")
        self.assertEqual(rollup["app"]["builtAt"], BUILT_AT)

    def test_live_lifts_everything_to_t3(self):
        rollup = self.rollup(live=True)
        self.assertTrue(all(c["supportedLevel"] == "T3" for c in rollup["concepts"]))
        self.assertEqual(rollup["summary"]["lowestSupported"], "T3")
        self.assertTrue(all(any(e["kind"] == "runtime" for e in c["evidence"]) for c in rollup["concepts"]))

    def test_fresh_passing_probe_lifts_only_the_concepts_its_steps_hit(self):
        probe_report(self.reports / "behavior-probes-report.json",
                     [probe("p-beta", "passed", "/api/betas", "/api/betas/${id}", "/api/flows/X/execute")])
        rollup = self.rollup(live=True)
        concepts = self.by_name(rollup)
        self.assertEqual(concepts["Beta"]["supportedLevel"], "T4")
        self.assertEqual(concepts["Beta"]["claim"], "ok")
        self.assertEqual(concepts["Alpha"]["supportedLevel"], "T3")
        self.assertEqual(concepts["Gamma"]["claim"], "overclaim")
        evidence = [e for e in concepts["Beta"]["evidence"] if e["kind"] == "probe"]
        self.assertEqual(len(evidence), 1)
        self.assertEqual(evidence[0]["id"], "probe:p-beta")
        self.assertEqual(evidence[0]["status"], "passed")
        self.assertEqual(evidence[0]["source"]["sampleId"], "demo-sample")
        self.assertEqual(evidence[0]["detail"]["routes"][0]["concept"], "Beta")
        self.assertEqual(rollup["summary"]["testedConcepts"], 1)
        self.assertEqual(rollup["summary"]["lowestSupported"], "T3")

    def test_two_kinds_of_evidence_make_t5_and_one_kind_does_not(self):
        probe_report(self.reports / "behavior-probes-report.json",
                     [probe("p", "passed", "/api/epss", "/api/betas")])
        _write(self.reports / "rest-smoke-result.json", {
            "schemaVersion": "npdev-ai-rest-smoke-result.v1", "scenarioId": "demo-sample", "status": "passed",
            "checks": [{"id": "eps-list", "status": "passed", "method": "GET", "path": "/api/epss",
                        "expectedStatus": 200, "actualStatus": 200},
                       {"id": "health", "status": "passed", "method": "GET", "path": "/actuator/health"}]})
        _set_mtime(self.reports / "rest-smoke-result.json", FRESH)
        concepts = self.by_name(self.rollup())
        self.assertEqual(concepts["Eps"]["supportedLevel"], "T5")
        self.assertEqual(concepts["Beta"]["supportedLevel"], "T4")   # one kind only
        self.assertEqual(concepts["Eps"]["claim"], "ok")

    def test_stale_failed_and_undated_evidence_is_shown_but_never_counted(self):
        # a run observed BEFORE this build was made says nothing about it
        probe_report(self.reports / "behavior-probes-report.json",
                     [probe("old", "passed", "/api/betas")], finished=OLD)
        rollup = self.rollup(live=True)
        beta = self.by_name(rollup)["Beta"]
        self.assertEqual(beta["supportedLevel"], "T3")
        stale = [e for e in beta["evidence"] if e["kind"] == "probe"][0]
        self.assertEqual((stale["status"], stale["observedStatus"]), ("stale", "passed"))
        self.assertIn("before this build was made", stale["staleReason"])
        self.assertEqual(rollup["summary"]["staleEvidence"], 1)
        # a fresh FAILING run: shown red, never lifts the level
        probe_report(self.reports / "behavior-probes-report.json",
                     [probe("bad", "failed", "/api/betas"), probe("known", "known-failure", "/api/gammas")])
        rollup = self.rollup(live=True)
        concepts = self.by_name(rollup)
        self.assertEqual(concepts["Beta"]["supportedLevel"], "T3")
        self.assertEqual([e["status"] for e in concepts["Beta"]["evidence"] if e["kind"] == "probe"], ["failed"])
        self.assertEqual(concepts["Gamma"]["supportedLevel"], "T3")   # a known failure is not evidence
        self.assertEqual([e["status"] for e in concepts["Gamma"]["evidence"] if e["kind"] == "probe"], ["known-failure"])
        self.assertEqual(rollup["summary"]["failedEvidence"], 1)
        self.assertEqual(rollup["summary"]["knownFailureEvidence"], 1)

    def test_a_fresh_failing_test_caps_a_tested_concept_but_a_known_failure_does_not(self):
        probe_report(self.reports / "behavior-probes-report.json", [
            probe("good", "passed", "/api/betas"), probe("bad", "failed", "/api/betas"),
            probe("fine", "passed", "/api/gammas"), probe("pinned", "known-failure", "/api/gammas")])
        concepts = self.by_name(self.rollup(live=True))
        self.assertEqual(concepts["Beta"]["supportedLevel"], "T3")    # red run contradicts "tested"
        self.assertEqual(concepts["Beta"]["claim"], "overclaim")
        self.assertEqual(concepts["Gamma"]["supportedLevel"], "T4")   # a known defect is not a regression
        # not running + only a red run: still just Generated -- a failure never lifts a level (not even to T3)
        probe_report(self.reports / "behavior-probes-report.json", [probe("bad", "failed", "/api/betas")])
        down = self.by_name(self.rollup(live=False))
        self.assertEqual(down["Beta"]["supportedLevel"], "T2")

    def test_evidence_with_no_usable_timestamp_is_undated_and_not_counted(self):
        built = npdev_truth.parse_time(BUILT_AT)
        record = {"id": "e", "kind": "probe", "level": "T4", "status": "passed", "observedAt": None}
        judged = npdev_truth.judge(record, built)
        self.assertEqual((judged["status"], judged["observedStatus"]), ("undated", "passed"))
        self.assertIsNone(npdev_truth.supported_level([judged], live=False))
        # observed-now evidence (the live request itself) is exempt from freshness
        live = npdev_truth.judge({"id": "r", "kind": "runtime", "level": "T3", "status": "observed",
                                  "observedAt": None}, built)
        self.assertEqual(live["status"], "observed")
        # unparseable / 7-digit-fraction timestamps (PowerShell writes those)
        self.assertIsNone(npdev_truth.parse_time("not-a-date"))
        self.assertEqual(npdev_truth.parse_time("2026-10-20T02:31:35.9310341Z").second, 35)

    def test_evidence_for_another_sample_is_ignored(self):
        probe_report(self.reports / "behavior-probes-report.json",
                     [probe("p", "passed", "/api/betas")], sample="some-other-sample")
        self.assertEqual(self.by_name(self.rollup())["Beta"]["supportedLevel"], "T2")

    def test_report_without_recorded_routes_is_flagged_not_misattributed(self):
        old_shape = {"id": "legacy", "source": "x.json", "keywords": [], "status": "passed", "failures": []}
        probe_report(self.reports / "behavior-probes-report.json", [old_shape])
        rollup = self.rollup(live=True)
        self.assertEqual(rollup["summary"]["testedConcepts"], 0)
        app_wide = [e for e in rollup["appEvidence"] if e["id"] == "probe:legacy"]
        self.assertEqual(len(app_wide), 1)
        self.assertIn("predates route recording", app_wide[0]["summary"])

    def test_journeys_and_browser_suite_are_app_wide_evidence(self):
        _write(self.reports / "journeys-report.json", {
            "schemaVersion": "npdev-journeys-result.v1", "sampleId": "demo-sample", "status": "passed",
            "rest": {"total": 4, "failed": 0}})
        _write(self.reports / "shell-scenarios-report.json", {
            "schemaVersion": "npdev-shell-scenarios-result.v1", "sampleId": "demo-sample", "status": "failed",
            "routines": [{"routine": "list", "status": "passed"}, {"routine": "detail", "status": "failed"}]})
        for name in ("journeys-report.json", "shell-scenarios-report.json"):
            _set_mtime(self.reports / name, FRESH)
        rollup = self.rollup()
        kinds = {e["kind"]: e for e in rollup["appEvidence"]}
        self.assertEqual(kinds["journey"]["status"], "passed")
        self.assertEqual(kinds["browser-suite"]["status"], "failed")
        self.assertEqual(rollup["summary"]["testedConcepts"], 0)   # app-wide never lifts a single concept

    def test_exploration_run_attributes_to_the_concepts_it_visited(self):
        ops = self.tmp / "ops"
        runs = ops / "exploration-runs"
        _write(runs / "r1" / "run.json", {"steps": [{"url": "http://x/npdev-business-ui/#concept-Beta"}]})
        (runs / "runs.jsonl").write_text(json.dumps({
            "runId": "r1", "definition": {"path": "x/beta-routine.json"}, "startedAt": FRESH,
            "verdict": {"green": True}, "stepCount": 1}) + "\n", encoding="utf-8")
        concepts = self.by_name(self.rollup(ops_dir=ops, live=True))
        self.assertEqual(concepts["Beta"]["supportedLevel"], "T4")
        self.assertEqual([e["kind"] for e in concepts["Beta"]["evidence"] if e["kind"] == "browser"], ["browser"])
        self.assertEqual(concepts["Alpha"]["supportedLevel"], "T3")

    def test_missing_and_corrupt_inputs_degrade_instead_of_raising(self):
        empty = npdev_truth.build_rollup(self.tmp / "nothing-here")
        self.assertEqual(empty["concepts"], [])
        self.assertIsNone(empty["summary"]["lowestSupported"])
        self.assertIsNone(empty["app"]["namespace"])
        (self.reports / "behavior-probes-report.json").write_text("{not json", encoding="utf-8")
        _write(self.reports / "journeys-report.json", [1, 2, 3])
        self.assertEqual(len(self.rollup()["concepts"]), 5)
        (self.app / "src" / "main" / "resources" / "npdev-build-info.properties").unlink()
        rollup = self.rollup()      # no build info: evidence cannot be judged stale, builtAt is unknown
        self.assertIsNone(rollup["app"]["builtAt"])
        self.assertIsNone(rollup["app"]["sampleId"])

    def test_rollup_is_deterministic_and_matches_the_schema(self):
        probe_report(self.reports / "behavior-probes-report.json", [probe("p", "passed", "/api/betas")])
        first = json.dumps(self.rollup(live=True), sort_keys=True)
        second = json.dumps(self.rollup(live=True), sort_keys=True)
        self.assertEqual(first, second)
        schema = json.loads((REPO_ROOT / "schemas" / "ai" / "truth-rollup.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(npdev_truth.SCHEMA_VERSION, schema["properties"]["schemaVersion"]["const"])
        self.assertEqual(npdev_jsonschema.validate(schema, self.rollup(live=True)), [])
        broken = self.rollup(live=True)
        broken["concepts"][0]["claim"] = "bogus"
        self.assertNotEqual(npdev_jsonschema.validate(schema, broken), [])

    def test_summary_is_the_compact_card_form(self):
        probe_report(self.reports / "behavior-probes-report.json", [probe("p", "passed", "/api/betas")])
        summary = npdev_truth.summarize(self.rollup(live=True), "X:/rollup.json")
        self.assertEqual(summary["lowestSupported"], "T3")
        self.assertEqual(summary["testedConcepts"], 1)
        self.assertTrue(summary["live"])
        self.assertEqual(summary["rollupPath"], "X:/rollup.json")
        self.assertIsNone(npdev_truth.unknown_summary("why")["lowestSupported"])


class CliTests(unittest.TestCase):
    def test_truth_command_writes_the_rollup_into_the_app_ops_dir_and_blocks_nothing(self):
        import npdev_cli
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            app = make_app(tmp / "app", [_box("Beta", "betas", "T4")])
            ops = app / "_ops"
            ops.mkdir()
            (ops / ".npdev-root").write_text("", encoding="utf-8")
            (ops / "resolved-db-plan.json").write_text(json.dumps({"appId": "demo", "serverPort": 0}), encoding="utf-8")
            (app / ".npdev-root").write_text("", encoding="utf-8")
            reports = tmp / "reports"
            reports.mkdir()
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = npdev_cli.main(["truth", "--app", str(app), "--reports-dir", str(reports), "--json", "--write"])
            self.assertEqual(code, 0, out.getvalue())
            printed = json.loads(out.getvalue())
            written = json.loads((ops / npdev_truth.ROLLUP_FILENAME).read_text(encoding="utf-8"))
            self.assertEqual(printed, written)
            self.assertEqual(written["concepts"][0]["declaredLevel"], "T4")
            self.assertEqual(written["concepts"][0]["supportedLevel"], "T2")    # not running: no T3 claim
            self.assertFalse(written["app"]["live"])

    def test_not_an_app_is_a_clean_refusal(self):
        import npdev_cli
        with tempfile.TemporaryDirectory() as tmp:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = npdev_cli.main(["truth", "--app", tmp, "--json"])
            self.assertEqual(code, 2)
            self.assertFalse(json.loads(out.getvalue())["ok"])


class ProbeRunnerTests(unittest.TestCase):
    """The runner records the routes each step hit -- that is what lets `npdev truth` attribute a
    passing probe to the concepts it actually exercised."""

    class Fake(run_probes.Probe):
        def call(self, step):
            return 200, json.dumps({"id": "abc"})

    def test_steps_record_the_authored_route_and_status(self):
        spec = {"id": "x", "steps": [
            {"name": "create", "method": "post", "path": "/api/betas", "body": {}, "save": {"i": "$.id"}},
            {"name": "get", "path": "/api/betas/${i}"}]}
        result = self.Fake("http://x", "k", spec, "f.json").run()
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["steps"], [
            {"name": "create", "method": "POST", "path": "/api/betas", "status": 200},
            {"name": "get", "method": "GET", "path": "/api/betas/${i}", "status": 200}])

    def test_jsonpath_filter_selects_an_array_element_by_field(self):
        doc = {"concepts": [{"conceptName": "A", "x": 1}, {"conceptName": "approvals::B", "x": 2}]}
        self.assertEqual(run_probes.resolve_path(doc, "$.concepts[?conceptName=approvals::B].x"), (True, 2))
        self.assertEqual(run_probes.resolve_path(doc, "$.concepts[?conceptName=Z].x"), (False, None))
        self.assertEqual(run_probes.resolve_path(doc, "$.concepts[0].x"), (True, 1))


if __name__ == "__main__":
    unittest.main()
