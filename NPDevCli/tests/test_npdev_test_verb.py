"""Tests for `npdev test` (R3.4) -- one verb, one verdict per app.

Filesystem-and-stub level, like the `explore suite` tests next door and for the same reason: what
this command owns is the COMPOSITION -- which three layers ran, how an absent layer is reported, and
what the exit code says -- not the engine round-trip or the HTTP stack, which the live run against a
booted app covers. Nothing green here is evidence that a browser or an app ran anything.

The HTTP seam is `urllib.request.urlopen`, patched module-wide: layer 1 and `_run_one_scenario` both
import urllib inside the function, so one patch routes both by URL.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli
import npdev_explore
import npdev_monitor

REPO_ROOT = Path(__file__).resolve().parents[2]

LIVE_KEY = "live-key-must-never-be-reported"

PLAN = {
    "appId": "demo", "engine": "H2Local", "serverPort": 8099,
    "resolvedDataRoot": "data", "resolvedDatabaseName": "demo",
    "appRoot": "App", "physicalDatabase": True,
}

INFO_JSON = {
    "schemaVersion": "npdev-app-info.v1",
    "namespace": "demo",
    "concepts": [{"name": "User", "route": "users"}, {"name": "Order", "route": "orders"}],
    "records": [],
}


def make_app(root: Path, *, concepts: bool = True, scenarios: list[dict] | None = None,
             routines: list[str] | None = None) -> Path:
    """A generated app tree in the AppGen shape (`<out>/_ops` beside `<out>/App`), which is the
    shape the two-writers plan produces and therefore the one discovery has to cope with."""
    app = root / "demo"
    (app / "_ops").mkdir(parents=True)
    (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
    final = app / "App"
    static = final / "npdev-generated" / "src" / "main" / "resources" / "static"
    static.mkdir(parents=True)
    info = dict(INFO_JSON, concepts=INFO_JSON["concepts"] if concepts else [])
    static.joinpath("info.json").write_text(json.dumps(info), encoding="utf-8")
    (final / "secrets").mkdir()
    (final / "secrets" / "api-key.env").write_text(
        f"NPDEV_AUTH_API_KEYS={LIVE_KEY}=dev:developer:admin", encoding="utf-8")
    for index, scenario in enumerate(scenarios or []):
        directory = final / "acceptance"
        directory.mkdir(exist_ok=True)
        directory.joinpath(f"{index:02d}-case.scenario.json").write_text(
            json.dumps(scenario), encoding="utf-8")
    for name in routines or []:
        mirror = npdev_explore.mirror_dir(app)
        mirror.mkdir(parents=True, exist_ok=True)
        mirror.joinpath(f"{name}.json").write_text(
            json.dumps({"scenarioName": name,
                        "steps": [{"action": "goto", "url": "http://127.0.0.1:1/"}]}),
            encoding="utf-8")
    return app


class _FakeResponse:
    def __init__(self, status: int, body: object):
        self.status = status
        self._body = json.dumps(body).encode("utf-8")

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


LIST_ENVELOPE = {"content": [], "page": 0, "size": 20, "totalElements": 0}


def routed_urlopen(statuses: dict[str, int] | None = None, seen: list | None = None):
    """One fake for both layers, routed by path. `seen` collects (url, headers) so a test can assert
    what was actually requested -- which endpoints were derived, and with which credential."""
    statuses = statuses or {}

    def _open(request, timeout=None):  # noqa: ARG001 -- signature must match urlopen's
        url = request.full_url if hasattr(request, "full_url") else str(request)
        if seen is not None:
            seen.append((url, dict(getattr(request, "headers", {}) or {})))
        path = url.split("//", 1)[-1].split("/", 1)[-1]
        status = statuses.get("/" + path, 200)
        if status >= 400:
            import urllib.error
            raise urllib.error.HTTPError(url, status, "boom", {}, io.BytesIO(b"{}"))
        return _FakeResponse(status, LIST_ENVELOPE)

    return _open


def cli_args(app: Path, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(
        command="test", app_dir=str(app), report_out=None,
        engine_port=9999, engine_root=None, engine_api_key=None, json=False)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


GREEN_PREFLIGHT = {
    "ok": True,
    "checks": [{"id": "app-is-generated", "name": "generated", "status": "pass", "detail": "ok"},
               {"id": "app-healthy", "name": "healthy", "status": "pass", "detail": "ok"},
               {"id": "engine-available", "name": "engine", "status": "pass", "detail": "ok"},
               {"id": "origin-allowlisted", "name": "origin", "status": "pass", "detail": "ok"}],
    "app": {}, "engine": {},
}


def fake_run_record(name: str, *, green: bool) -> dict:
    return {"runId": f"run-{name}", "status": "passed" if green else "failed", "durationMs": 3,
            "verdict": {"green": green, "reasons": [] if green else ["a step failed"],
                        "allowedConsoleErrorSubstrings": [], "excused": []}}


def run_test(app: Path, *, statuses=None, seen=None, routine_green=True, **overrides) -> dict:
    with mock.patch("urllib.request.urlopen", routed_urlopen(statuses, seen)), \
            mock.patch.object(npdev_explore, "preflight", lambda *a, **k: GREEN_PREFLIGHT), \
            mock.patch.object(npdev_explore, "run_exploration",
                              lambda _r, _a, f, **_k: fake_run_record(Path(f).stem, green=routine_green)):
        return npdev_cli.run_test(cli_args(app, **overrides))


def run_cli(app: Path, **kwargs) -> tuple[int, str]:
    buffer = io.StringIO()
    with mock.patch("urllib.request.urlopen", routed_urlopen(kwargs.pop("statuses", None))), \
            mock.patch.object(npdev_explore, "preflight", lambda *a, **k: GREEN_PREFLIGHT), \
            mock.patch.object(npdev_explore, "run_exploration",
                              lambda _r, _a, f, **_k: fake_run_record(
                                  Path(f).stem, green=kwargs.pop("routine_green", True))), \
            redirect_stdout(buffer):
        code = npdev_cli.main(["test", "--app-dir", str(app), *kwargs.pop("extra", [])])
    return code, buffer.getvalue()


PASSING_SCENARIO = {
    "schemaVersion": "npdev-acceptance-scenario.v1", "name": "size is 20", "approved": True,
    "when": {"method": "GET", "path": "/api/concepts/users"},
    "then": [{"path": "$.size", "equals": 20}],
}
FAILING_SCENARIO = {
    "schemaVersion": "npdev-acceptance-scenario.v1", "name": "size is 999", "approved": True,
    "when": {"method": "GET", "path": "/api/concepts/users"},
    "then": [{"path": "$.size", "equals": 999}],
}


def running(app: Path):
    """The probe, with the app reported healthy. Everything else in the record -- the resolved final
    app root, the live API key, the concept list -- comes from the real `probe_app` reading the real
    tree, so discovery is under test rather than stubbed out."""
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "running"
        record["healthDetail"] = None
        return record

    return mock.patch.object(npdev_monitor, "probe_app", _probe)


class ThreeLayers(unittest.TestCase):
    def test_all_three_layers_run_and_roll_up_to_one_verdict(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO], routines=["login"])
            with running(app):
                result = run_test(app)
            self.assertEqual(result["schemaVersion"], npdev_cli.TEST_SCHEMA_VERSION)
            self.assertEqual([layer["layer"] for layer in result["layers"]],
                             ["rest-smoke", "acceptance", "browser"])
            self.assertEqual([layer["status"] for layer in result["layers"]],
                             ["green", "green", "green"])
            self.assertTrue(result["ok"])
            self.assertEqual(result["counts"], {"layers": 3, "green": 3, "red": 0, "empty": 0})

    def test_the_rest_plan_is_derived_from_the_model_not_from_a_file(self):
        # Every concept the app publishes, and nothing else: no plan file exists anywhere in the
        # tree, which is the whole point of the item.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            with running(app):
                result = run_test(app, seen=seen)
            smoke = result["layers"][0]
            # In the app's OWN published order, not re-sorted here: the generator already sorts its
            # concept list, and a second ordering rule would be a place the two answers can differ.
            self.assertEqual([check["path"] for check in smoke["checks"]],
                             ["/api/users", "/api/orders"])
            self.assertEqual(smoke["counts"], {"total": 2, "passed": 2, "failed": 0})

    def test_the_rest_layer_authenticates_with_the_apps_live_key(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            with running(app):
                run_test(app, seen=seen)
            keys = {tuple(sorted(headers.values())) for _url, headers in seen}
            self.assertEqual(keys, {(LIVE_KEY,)})

    def test_a_concept_endpoint_that_does_not_answer_200_is_red(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_test(app, statuses={"/api/orders": 500})
            smoke = result["layers"][0]
            self.assertEqual(smoke["status"], "red")
            failed = [check for check in smoke["checks"] if check["status"] == "failed"]
            self.assertEqual(len(failed), 1)
            self.assertEqual(failed[0]["actualStatus"], 500)
            self.assertIn("expected HTTP 200, got 500", failed[0]["failures"][0])
            self.assertFalse(result["ok"])

    def test_scenarios_are_discovered_without_being_pointed_at(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO, FAILING_SCENARIO])
            with running(app):
                result = run_test(app)
            acceptance = result["layers"][1]
            self.assertEqual(acceptance["scenariosDir"], str(app / "App" / "acceptance"))
            self.assertEqual(acceptance["report"]["schemaVersion"], "npdev-acceptance-report.v1")
            self.assertEqual(acceptance["report"]["summary"]["approvedTotal"], 2)
            self.assertEqual(acceptance["status"], "red")


class AbsentLayers(unittest.TestCase):
    """R3.3 (`explore generate`) has not landed, so most apps have no routines at all. An absent
    layer must read as absent -- not as coverage, and not as a failure."""

    def test_no_routines_is_reported_empty_with_the_directories_that_were_looked_at(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO])
            with running(app):
                result = run_test(app)
            browser = result["layers"][2]
            self.assertEqual(browser["status"], "empty")
            self.assertIsNone(browser["green"])
            self.assertIsNone(browser["report"])
            self.assertIn("declares no browser routines", browser["detail"])
            self.assertIn(str(npdev_explore.mirror_dir(app)), browser["detail"])
            # Empty is neither: it does not make the run red, and it is not counted as green either.
            self.assertTrue(result["ok"])
            self.assertEqual(result["counts"], {"layers": 3, "green": 2, "red": 0, "empty": 1})

    def test_an_app_wide_browser_refusal_is_not_the_same_as_having_no_routines(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), routines=["login"])
            with running(app), \
                    mock.patch.object(npdev_explore, "run_suite",
                                      mock.Mock(side_effect=npdev_explore.ExploreError("no engine"))):
                result = run_test(app)
            browser = result["layers"][2]
            self.assertEqual(browser["status"], "refused")
            self.assertFalse(browser["green"])
            self.assertEqual(browser["detail"], "no engine")
            self.assertFalse(result["ok"])

    def test_no_scenarios_anywhere_is_empty_and_names_where_it_looked(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_test(app)
            acceptance = result["layers"][1]
            self.assertEqual(acceptance["status"], "empty")
            self.assertIsNone(acceptance["scenariosDir"])
            self.assertEqual(acceptance["searched"],
                             [str(app / "App" / "acceptance"), str(app / "acceptance")])

    def test_three_empty_layers_is_not_a_pass(self):
        # `run_suite`'s rule one level up: a summary of zero runs reads like a pass, so it must not
        # be allowed to look like one.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), concepts=False)
            with running(app):
                result = run_test(app)
            self.assertEqual([layer["status"] for layer in result["layers"]],
                             ["empty", "empty", "empty"])
            self.assertTrue(result["nothingMeasured"])
            self.assertFalse(result["ok"])
            self.assertFalse(result["green"])


class ReportAndExitCode(unittest.TestCase):
    def test_one_report_is_written_next_to_the_apps_other_run_artifacts(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO])
            with running(app):
                result = run_test(app)
            written = app / "_ops" / npdev_cli.TEST_REPORT_FILENAME
            self.assertEqual(result["reportPath"], str(written))
            payload = json.loads(written.read_text(encoding="utf-8"))
            self.assertEqual(payload["schemaVersion"], npdev_cli.TEST_SCHEMA_VERSION)
            self.assertEqual(len(payload["layers"]), 3)

    def test_the_report_never_carries_the_apps_api_key(self):
        # The guarantee that replaces redaction: `npdev_monitor.redact()` is key-name driven and its
        # `pass(word)?` pattern matches the ordinary word `passed`, so redacting a pass/fail report
        # would destroy the evidence. Not putting the credential in is the stronger property.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO], routines=["login"])
            with running(app):
                result = run_test(app)
            written = (app / "_ops" / npdev_cli.TEST_REPORT_FILENAME).read_text(encoding="utf-8")
            self.assertNotIn(LIVE_KEY, written)
            self.assertNotIn(LIVE_KEY, json.dumps(result))

    def test_report_out_overrides_the_default_location(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            elsewhere = Path(tmp) / "reports" / "one.json"
            with running(app):
                result = run_test(app, report_out=str(elsewhere))
            self.assertEqual(result["reportPath"], str(elsewhere))
            self.assertTrue(elsewhere.is_file())
            self.assertFalse((app / "_ops" / npdev_cli.TEST_REPORT_FILENAME).exists())

    def test_all_green_exits_zero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO], routines=["login"])
            with running(app):
                code, out = run_cli(app)
            self.assertEqual(code, 0)
            self.assertIn("GREEN", out)

    def test_a_red_layer_exits_nonzero_and_names_the_failure(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[FAILING_SCENARIO], routines=["login"])
            with running(app):
                code, out = run_cli(app)
            self.assertEqual(code, 2)
            self.assertIn("RED", out)
            self.assertIn("$.size equals 999, got 20", out)

    def test_a_red_browser_routine_exits_nonzero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[PASSING_SCENARIO], routines=["login"])
            with running(app):
                code, out = run_cli(app, routine_green=False)
            self.assertEqual(code, 2)
            self.assertIn("[red] login", out)

    def test_the_json_form_carries_the_same_rollup(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), scenarios=[FAILING_SCENARIO])
            with running(app):
                code, out = run_cli(app, extra=["--json"])
            payload = json.loads(out[out.index("{"):])
            self.assertEqual(code, 2)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["counts"]["red"], 1)

    def test_an_app_that_is_not_running_is_refused_not_reported_as_red(self):
        # D4/QUAL-4: a tool problem dressed as a test result teaches people to distrust the tests.
        # Exit 1 (a refusal), no report file, and the probe's own sentence.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with self.assertRaises(npdev_cli.CliError) as caught:
                npdev_cli.run_test(cli_args(app))
            self.assertIn("not answering", str(caught.exception))
            self.assertFalse((app / "_ops" / npdev_cli.TEST_REPORT_FILENAME).exists())


class DefinitionRootDiscovery(unittest.TestCase):
    """The plan has two writers spelling the same facts differently, and `npdev test` needs both --
    an AppGen-built app resolved its FinalApp root, its live key and its definition folder wrongly
    before this, each of which LOOKED like an ordinary absence."""

    def test_the_appgen_spelling_of_the_final_app_root_is_honoured(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            record = npdev_monitor.probe_app(app, include_info=True)
            self.assertEqual(record["finalAppRoot"], str(app / "App"))
            self.assertTrue(record["hasInfoJson"])
            self.assertEqual(record["apiKey"], LIVE_KEY)

    def test_the_definition_root_is_read_from_app_plan_when_the_db_plan_omits_it(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            definition = Path(tmp) / "app-definition"
            (definition / "definition").mkdir(parents=True)
            (definition / "definition" / "model.json").write_text("{}", encoding="utf-8")
            (app / "_ops" / "app-plan.json").write_text(
                json.dumps({"webSourceDir": str(definition / "web")}), encoding="utf-8")
            self.assertEqual(npdev_monitor.app_definition_root(app), str(definition))
            # One rule, two callers: `explore` must see the same folder `probe_app` publishes.
            self.assertIn(definition / "explorations", npdev_explore.definition_dirs(app))

    def test_a_web_source_whose_parent_holds_no_model_is_not_a_definition_root(self):
        # Identified by CONTENTS, never by shape (REG-144). A stale path must return None rather
        # than a directory that merely sits in the right place.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            (app / "_ops" / "app-plan.json").write_text(
                json.dumps({"webSourceDir": str(Path(tmp) / "gone" / "web")}), encoding="utf-8")
            self.assertIsNone(npdev_monitor.app_definition_root(app))


if __name__ == "__main__":
    unittest.main()
