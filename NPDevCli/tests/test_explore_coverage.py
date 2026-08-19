"""Tests for `npdev explore coverage` (R3.5): concept -> referencing routines -> last green run,
plus flow -> referencing acceptance scenarios, each with an explicit UNCOVERED section.

Filesystem-and-fixture level, the same discipline `test_npdev_test_verb.py` documents for the
sibling `npdev test` verb: coverage is a STATIC read over already-durable facts (info.json, routine/
scenario files, run history) -- no engine, no HTTP call against the app -- so a fake tree in a temp
directory is a faithful test of it, not a stand-in for one. The live behaviour (a real app, a real
routine run, a real concept left deliberately uncovered) is proven by hand against
coverage-r35-check, not here.
"""

from __future__ import annotations

import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli
import npdev_explore

PLAN = {
    "appId": "demo", "engine": "H2Local", "serverPort": 8099,
    "resolvedDataRoot": "data", "resolvedDatabaseName": "demo",
    "appRoot": "App", "physicalDatabase": True,
}


def make_app(root: Path, *, concepts: list[dict] | None = None, flows: list[str] | None = None,
            routines: dict[str, list[dict]] | None = None, scenarios: list[dict] | None = None) -> Path:
    """AppGen shape (`<out>/_ops` beside `<out>/App`) -- the shape `resolved-db-plan.json`'s two
    writers actually produce, same as `test_npdev_test_verb.py`'s `make_app`."""
    app = root / "demo"
    (app / "_ops").mkdir(parents=True)
    (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
    final = app / "App"
    static = final / "npdev-generated" / "src" / "main" / "resources" / "static"
    static.mkdir(parents=True)
    info = {
        "schemaVersion": "npdev-app-info.v1",
        "concepts": concepts if concepts is not None else [],
        "flows": flows if flows is not None else [],
        "records": [],
    }
    static.joinpath("info.json").write_text(json.dumps(info), encoding="utf-8")
    for name, steps in (routines or {}).items():
        mirror = npdev_explore.mirror_dir(app)
        mirror.mkdir(parents=True, exist_ok=True)
        mirror.joinpath(f"{name}.json").write_text(
            json.dumps({"scenarioName": name, "steps": steps}), encoding="utf-8")
    for index, scenario in enumerate(scenarios or []):
        directory = final / "acceptance"
        directory.mkdir(exist_ok=True)
        directory.joinpath(f"{index:02d}-case.scenario.json").write_text(
            json.dumps(scenario), encoding="utf-8")
    return app


def record_run(app: Path, routine_path: Path, *, green: bool, started: str, run_id: str) -> None:
    """A minimal, schema-shaped run record, appended through the real `append_run` -- exactly what a
    `run_exploration` call would have written, so `coverage()`'s reading of `runs.jsonl` is under
    test against the real writer, not a hand-rolled substitute."""
    record = {
        "schemaVersion": npdev_explore.RUN_SCHEMA_VERSION, "runId": run_id, "scope": "app",
        "definition": {"kind": "routine-json", "path": str(routine_path),
                       "scenarioName": routine_path.stem, "contentSha256": "0" * 64},
        "target": {"baseUrl": "http://127.0.0.1:1"}, "driver": "cli", "startedAt": started,
        "status": "passed" if green else "failed", "steps": [],
        "evidence": {"consoleErrors": [], "pageErrors": [], "networkFailures": [],
                     "unexpectedExternalRequests": []},
        "verdict": {"green": green, "allowedConsoleErrorSubstrings": [], "excused": [],
                    "reasons": [] if green else ["a step failed"]},
    }
    npdev_explore.append_run(app, record)


class ConceptCoverage(unittest.TestCase):
    def test_a_concept_with_no_referencing_routine_is_uncovered(self):
        with TemporaryDirectory() as tmp:
            app = make_app(
                Path(tmp),
                concepts=[{"name": "CanaryOwner", "route": "canary_owners"},
                         {"name": "CanaryTask", "route": "canary_tasks"}],
                routines={"opens-task": [
                    {"action": "click", "selector": "#concept-CanaryTask .panel-actions button"}]})
            result = npdev_explore.coverage(app)
            by_name = {c["name"]: c for c in result["concepts"]}
            self.assertTrue(by_name["CanaryTask"]["covered"])
            self.assertEqual(by_name["CanaryTask"]["referencingRoutines"], ["opens-task"])
            self.assertFalse(by_name["CanaryOwner"]["covered"])
            self.assertEqual(by_name["CanaryOwner"]["referencingRoutines"], [])
            self.assertEqual(result["uncovered"]["concepts"], ["CanaryOwner"])
            self.assertEqual(result["summary"], {"conceptsTotal": 2, "conceptsCovered": 1,
                                                  "flowsTotal": 0, "flowsCovered": 0})

    def test_naming_a_concept_outside_its_selector_does_not_count_as_referencing_it(self):
        # A routine that merely mentions another concept's name in a value/label must not count --
        # only the deterministic `#concept-<Name>` selector `sectionId()` emits does.
        with TemporaryDirectory() as tmp:
            app = make_app(
                Path(tmp), concepts=[{"name": "Person", "route": "people"}],
                routines={"unrelated": [
                    {"action": "fill", "selector": "#somewhereElse",
                     "value": "Person mentioned here, not as a selector"}]})
            result = npdev_explore.coverage(app)
            self.assertFalse(result["concepts"][0]["covered"])

    def test_last_green_run_is_the_most_recent_green_one_not_the_first(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), concepts=[{"name": "User", "route": "users"}],
                           routines={"r": [{"action": "click", "selector": "#concept-User"}]})
            routine_path = npdev_explore.definition_files(app)[0]
            record_run(app, routine_path, green=True, started="2020-01-01T00:00:00Z", run_id="old-green")
            record_run(app, routine_path, green=False, started="2020-01-02T00:00:00Z", run_id="new-red")
            concept = npdev_explore.coverage(app)["concepts"][0]
            self.assertEqual(concept["lastRun"]["runId"], "new-red")
            self.assertEqual(concept["lastGreenRun"]["runId"], "old-green")

    def test_a_red_only_history_leaves_last_green_run_null_but_last_run_populated(self):
        # A covered-but-never-green concept must not be silently indistinguishable from an
        # uncovered one, and must not be reported as if it had a green run either.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), concepts=[{"name": "User", "route": "users"}],
                           routines={"r": [{"action": "click", "selector": "#concept-User"}]})
            routine_path = npdev_explore.definition_files(app)[0]
            record_run(app, routine_path, green=False, started="2020-01-01T00:00:00Z", run_id="only-red")
            concept = npdev_explore.coverage(app)["concepts"][0]
            self.assertTrue(concept["covered"])
            self.assertIsNone(concept["lastGreenRun"])
            self.assertEqual(concept["lastRun"]["runId"], "only-red")
            self.assertFalse(concept["lastRun"]["green"])

    def test_a_concept_name_needing_selector_sanitization_still_matches(self):
        # sectionId() replaces every non [A-Za-z0-9_-] character with '-'; a concept name with a
        # space has to be matched the same way the generated page's own id is built.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), concepts=[{"name": "Gift Idea", "route": "gift_ideas"}],
                           routines={"r": [{"action": "click", "selector": "#concept-Gift-Idea"}]})
            concept = npdev_explore.coverage(app)["concepts"][0]
            self.assertEqual(concept["selector"], "#concept-Gift-Idea")
            self.assertTrue(concept["covered"])


class FlowCoverage(unittest.TestCase):
    """The business UI never renders a flow trigger (`business-ui-app.mustache` has no
    `api/flows` reference at all), so flow coverage comes from acceptance scenarios' `when.path`
    instead of routine selectors -- verified against `InfoPageEmitter`/`_run_one_scenario`, not
    assumed."""

    def test_a_flow_with_no_referencing_scenario_is_uncovered(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), flows=["CreateCanaryTask"])
            result = npdev_explore.coverage(app)
            self.assertFalse(result["flows"][0]["covered"])
            self.assertEqual(result["uncovered"]["flows"], ["CreateCanaryTask"])

    def test_a_scenario_hitting_the_flows_execute_path_covers_it(self):
        with TemporaryDirectory() as tmp:
            scenario = {"when": {"method": "POST", "path": "/api/flows/CreateCanaryTask/execute",
                                  "body": {"title": "x"}}, "then": []}
            app = make_app(Path(tmp), flows=["CreateCanaryTask"], scenarios=[scenario])
            flow = npdev_explore.coverage(app)["flows"][0]
            self.assertTrue(flow["covered"])
            self.assertEqual(flow["referencingScenarios"], ["00-case.scenario.json"])

    def test_a_scenario_hitting_a_concept_endpoint_does_not_count_as_flow_coverage(self):
        # `_run_one_scenario`'s `given[]` seeds through a concept CRUD path -- that must not be
        # mistaken for exercising the flow.
        with TemporaryDirectory() as tmp:
            scenario = {"when": {"method": "GET", "path": "/api/concepts/canary_tasks"}, "then": []}
            app = make_app(Path(tmp), flows=["CreateCanaryTask"], scenarios=[scenario])
            self.assertFalse(npdev_explore.coverage(app)["flows"][0]["covered"])

    def test_the_execute_path_is_percent_encoded_like_the_real_url(self):
        # `InfoPageEmitter.encodePathSegment()` percent-encodes; a flow name with a space has to be
        # matched against the URL the app actually serves, not `urllib.parse.quote`'s '+' form.
        with TemporaryDirectory() as tmp:
            scenario = {"when": {"method": "POST", "path": "/api/flows/Two%20Words/execute"}, "then": []}
            app = make_app(Path(tmp), flows=["Two Words"], scenarios=[scenario])
            flow = npdev_explore.coverage(app)["flows"][0]
            self.assertEqual(flow["executePath"], "/api/flows/Two%20Words/execute")
            self.assertTrue(flow["covered"])


class UncoveredSection(unittest.TestCase):
    def test_says_so_explicitly_when_nothing_is_uncovered(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), concepts=[{"name": "User", "route": "users"}],
                           routines={"r": [{"action": "click", "selector": "#concept-User"}]})
            result = npdev_explore.coverage(app)
            self.assertEqual(result["uncovered"], {"concepts": [], "flows": []})

    def test_definition_of_done_one_concept_covered_one_deliberately_not(self):
        with TemporaryDirectory() as tmp:
            app = make_app(
                Path(tmp),
                concepts=[{"name": "CanaryTask", "route": "canary_tasks"},
                         {"name": "CanaryOwner", "route": "canary_owners"}],
                routines={"opens-task": [{"action": "click", "selector": "#concept-CanaryTask"}]})
            routine_path = npdev_explore.definition_files(app)[0]
            record_run(app, routine_path, green=True, started="2020-01-01T00:00:00Z", run_id="green-1")
            result = npdev_explore.coverage(app)
            self.assertEqual(result["uncovered"]["concepts"], ["CanaryOwner"])
            covered = next(c for c in result["concepts"] if c["name"] == "CanaryTask")
            self.assertIsNotNone(covered["lastGreenRun"])
            uncovered = next(c for c in result["concepts"] if c["name"] == "CanaryOwner")
            self.assertIsNone(uncovered["lastGreenRun"])
            self.assertIsNone(uncovered["lastRun"])


class Refusals(unittest.TestCase):
    def test_a_non_app_directory_is_refused(self):
        with TemporaryDirectory() as tmp:
            with self.assertRaises(npdev_explore.ExploreError):
                npdev_explore.coverage(Path(tmp))

    def test_an_app_with_no_info_json_is_refused_rather_than_reported_empty(self):
        with TemporaryDirectory() as tmp:
            app = Path(tmp) / "demo"
            (app / "_ops").mkdir(parents=True)
            (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
            with self.assertRaises(npdev_explore.ExploreError) as caught:
                npdev_explore.coverage(app)
            self.assertIn("info.json", str(caught.exception))


class CliWiring(unittest.TestCase):
    def test_the_human_summary_names_the_uncovered_concept(self):
        with TemporaryDirectory() as tmp:
            app = make_app(
                Path(tmp),
                concepts=[{"name": "CanaryTask", "route": "canary_tasks"},
                         {"name": "CanaryOwner", "route": "canary_owners"}],
                routines={"opens-task": [{"action": "click", "selector": "#concept-CanaryTask"}]})
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["explore", "coverage", "--app-dir", str(app)])
            out = buffer.getvalue()
            self.assertEqual(code, 0)
            self.assertIn("UNCOVERED", out)
            self.assertIn("CanaryOwner", out)
            self.assertIn("covered", out)  # the CanaryTask row

    def test_the_json_form_carries_the_schema_version_and_uncovered_section(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), concepts=[{"name": "User", "route": "users"}])
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                npdev_cli.main(["explore", "coverage", "--app-dir", str(app), "--json"])
            payload = json.loads(buffer.getvalue())
            self.assertEqual(payload["schemaVersion"], "npdev-exploration-coverage.v1")
            self.assertEqual(payload["uncovered"]["concepts"], ["User"])

    def test_a_refusal_is_a_diagnosed_cli_error_not_a_crash(self):
        # `coverage()`'s `ExploreError` is re-raised as `CliError` by `run_explore`, and `main()`'s
        # handler for that prints a diagnosis and returns 1 -- no traceback, no exit-2 (that code is
        # reserved for a command that ran and reported red, which a refusal is not, D4/QUAL-4).
        with TemporaryDirectory() as tmp:
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["explore", "coverage", "--app-dir", tmp])
            self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
