"""Tests for `npdev bench` (R3.7) -- a per-app latency probe over concept-list and panel/query
endpoints, with saved per-app baselines and a RELATIVE regression check (never an absolute ms
budget -- ledger RUN-16 measured an absolute "<300ms" threshold flake at 365ms under ordinary
multi-agent machine load, a bigger swing than many real regressions).

Two layers, same split `test_npdev_test_verb.py` uses:
  - Pure-function tests (`_bench_stats`, `_compare_to_baseline`, `_bench_plan`, baseline
    load/save) that need no HTTP stub and are fully deterministic.
  - Filesystem-and-HTTP-stub tests for `run_bench`/the CLI, covering plan derivation, baseline
    first-run/promote/compare, refusal on a non-running app, and exit codes. Real elapsed time is
    used for the HTTP round trip (the fake `urlopen` returns instantly, so timings are tiny but
    real) -- correctness of p50<=p95 falls out of sorting, not of controlling the clock. Where a
    test needs a DETERMINISTIC regression verdict, it seeds the baseline file with a value so far
    from any real measurement (near-zero, or absurdly large) that the outcome cannot flake.
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
import npdev_monitor

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

MANIFEST_JSON = {
    "schemaVersion": "npdev-generated-ui-manifest.v1",
    "appName": "demo",
    "panels": [{"name": "UserSummary", "route": "user-summary", "title": "User Summary"}],
    "concepts": [],
}


def make_app(root: Path, *, concepts: bool = True, panels: bool = True) -> Path:
    """Same AppGen-shaped tree `test_npdev_test_verb.py`'s own `make_app` builds
    (`<out>/_ops` beside `<out>/App`), trimmed/extended to what `npdev bench` actually reads:
    info.json for the concept-list plan, generated-ui-manifest.json for the panel plan."""
    app = root / "demo"
    (app / "_ops").mkdir(parents=True)
    (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(PLAN), encoding="utf-8")
    final = app / "App"
    static = final / "npdev-generated" / "src" / "main" / "resources" / "static"
    static.mkdir(parents=True)
    info = dict(INFO_JSON, concepts=INFO_JSON["concepts"] if concepts else [])
    static.joinpath("info.json").write_text(json.dumps(info), encoding="utf-8")
    if panels:
        business_ui = final / "npdev-generated" / "src" / "main" / "resources" / "static" / "npdev-business-ui"
        business_ui.mkdir(parents=True)
        business_ui.joinpath("generated-ui-manifest.json").write_text(
            json.dumps(MANIFEST_JSON), encoding="utf-8")
    (final / "secrets").mkdir()
    (final / "secrets" / "api-key.env").write_text(
        f"NPDEV_AUTH_API_KEYS={LIVE_KEY}=dev:developer:admin", encoding="utf-8")
    return app


def running(app: Path):
    """Real discovery, health forced to running (same pattern as `test_npdev_test_verb.py`/
    `test_seed_cli.py`) so the verb does not need an actual listening socket."""
    real = npdev_monitor.probe_app

    def _probe(app_dir, **kwargs):
        record = real(app_dir, **kwargs)
        record["health"] = "running"
        record["healthDetail"] = None
        return record

    return mock.patch.object(npdev_monitor, "probe_app", _probe)


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


def routed_urlopen(statuses: dict[str, int] | None = None, seen: list | None = None):
    """One fake for every GET this command issues, routed by path -- mirrors
    `test_npdev_test_verb.py`'s helper of the same name and shape."""
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
        return _FakeResponse(status, {"ok": True})

    return _open


def cli_args(app: Path, **overrides) -> argparse.Namespace:
    args = argparse.Namespace(
        command="bench", app_dir=str(app), concept=[], panel=[],
        samples=3, timeout=5.0, regression_threshold=npdev_cli.DEFAULT_BENCH_REGRESSION_THRESHOLD,
        baseline_path=None, update_baseline=False, report_out=None, json=False)
    for key, value in overrides.items():
        setattr(args, key, value)
    return args


def run_bench(app: Path, *, statuses=None, seen=None, **overrides) -> dict:
    with mock.patch("urllib.request.urlopen", routed_urlopen(statuses, seen)):
        return npdev_cli.run_bench(cli_args(app, **overrides))


def run_cli(app: Path, extra: list[str] | None = None, *, statuses=None) -> tuple[int, str]:
    buffer = io.StringIO()
    with mock.patch("urllib.request.urlopen", routed_urlopen(statuses)), redirect_stdout(buffer):
        code = npdev_cli.main(["bench", "--app-dir", str(app), "--samples", "3", *(extra or [])])
    return code, buffer.getvalue()


# ------------------------------------------------------------------------------------------------
# Pure-function tests -- no HTTP, no filesystem beyond what's handed in.
# ------------------------------------------------------------------------------------------------

class BenchStats(unittest.TestCase):
    def test_zero_samples_reports_all_none_not_a_crash(self):
        stats = npdev_cli._bench_stats([])
        self.assertEqual(stats["samples"], 0)
        self.assertIsNone(stats["p50Ms"])
        self.assertIsNone(stats["p95Ms"])

    def test_stats_carry_count_mean_and_stdev_alongside_percentiles(self):
        stats = npdev_cli._bench_stats([10.0, 20.0, 30.0, 40.0, 50.0])
        self.assertEqual(stats["samples"], 5)
        self.assertEqual(stats["minMs"], 10.0)
        self.assertEqual(stats["maxMs"], 50.0)
        self.assertEqual(stats["meanMs"], 30.0)
        # p50 is always between min and max, and p95 >= p50 -- guaranteed by sorting, not by the
        # actual magnitude of the samples (so this holds under real, un-mocked timing too).
        self.assertLessEqual(stats["minMs"], stats["p50Ms"])
        self.assertLessEqual(stats["p50Ms"], stats["p95Ms"])
        self.assertLessEqual(stats["p95Ms"], stats["maxMs"])
        self.assertGreater(stats["stdevMs"], 0.0)

    def test_a_single_sample_has_zero_stdev_and_every_percentile_equal(self):
        stats = npdev_cli._bench_stats([42.0])
        self.assertEqual(stats["samples"], 1)
        self.assertEqual(stats["p50Ms"], 42.0)
        self.assertEqual(stats["p95Ms"], 42.0)
        self.assertEqual(stats["stdevMs"], 0.0)


class BenchBaselineCompare(unittest.TestCase):
    def test_no_baseline_entry_is_reported_as_such_never_as_a_regression(self):
        result = npdev_cli._compare_to_baseline({"samples": 5, "p50Ms": 10.0}, None, 1.5)
        self.assertFalse(result["hasBaseline"])
        self.assertFalse(result["regressed"])

    def test_a_p50_at_the_threshold_ratio_is_flagged(self):
        stats = {"samples": 5, "p50Ms": 15.0}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5)
        self.assertTrue(result["hasBaseline"])
        self.assertEqual(result["ratio"], 1.5)
        self.assertTrue(result["regressed"])

    def test_a_p50_comfortably_under_the_threshold_is_not_flagged(self):
        stats = {"samples": 5, "p50Ms": 11.0}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5)
        self.assertFalse(result["regressed"])

    def test_a_faster_run_is_never_a_regression(self):
        stats = {"samples": 5, "p50Ms": 2.0}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5)
        self.assertFalse(result["regressed"])
        self.assertLess(result["ratio"], 1.0)

    def test_zero_successful_samples_is_never_compared(self):
        stats = {"samples": 0, "p50Ms": None}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5)
        self.assertFalse(result["regressed"])

    def test_uniform_machine_slowdown_is_cancelled_by_the_control(self):
        # MON-21: endpoint and control both ~1.6x slower -> raw ratio flags, normalised does not.
        stats = {"samples": 5, "p50Ms": 16.0}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5, control_ratio=1.6)
        self.assertGreater(result["ratio"], 1.5)
        self.assertFalse(result["regressed"])
        self.assertAlmostEqual(result["normalizedRatio"], 1.0, places=3)

    def test_a_real_regression_survives_normalization(self):
        # MON-21: endpoint 1.6x slower while the control stayed flat -> still regressed.
        stats = {"samples": 5, "p50Ms": 16.0}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5, control_ratio=1.0)
        self.assertTrue(result["regressed"])
        self.assertAlmostEqual(result["normalizedRatio"], 1.6, places=3)

    def test_absent_control_ratio_falls_back_to_the_raw_ratio(self):
        stats = {"samples": 5, "p50Ms": 16.0}
        baseline = {"p50Ms": 10.0, "samples": 5, "measuredAt": "2026-08-19T00:00:00Z"}
        result = npdev_cli._compare_to_baseline(stats, baseline, 1.5, control_ratio=None)
        self.assertTrue(result["regressed"])
        self.assertFalse(result["normalizationApplied"])
        self.assertNotIn("normalizedRatio", result)


class BenchBaselineFile(unittest.TestCase):
    def test_a_missing_baseline_file_is_an_empty_dict_not_an_error(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "nope.json"
            self.assertEqual(npdev_cli._load_bench_baseline(path), {})

    def test_a_corrupt_baseline_file_is_treated_as_absent(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.json"
            path.write_text("{not json", encoding="utf-8")
            self.assertEqual(npdev_cli._load_bench_baseline(path), {})

    def test_default_baseline_path_lives_under_app_ops(self):
        with TemporaryDirectory() as tmp:
            app = Path(tmp) / "demo"
            path = npdev_cli._bench_baseline_path(app, None)
            self.assertEqual(path, app / "_ops" / npdev_cli.BENCH_BASELINE_FILENAME)

    def test_an_explicit_baseline_path_overrides_the_default(self):
        with TemporaryDirectory() as tmp:
            app = Path(tmp) / "demo"
            elsewhere = Path(tmp) / "elsewhere.json"
            path = npdev_cli._bench_baseline_path(app, str(elsewhere))
            self.assertEqual(path, elsewhere)


class BenchPlan(unittest.TestCase):
    def test_plan_covers_both_concept_list_and_panel_endpoints(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            record = npdev_monitor.probe_app(app, include_info=True)
            plan, warnings = npdev_cli._bench_plan(
                record, Path(record["finalAppRoot"]), concepts=None, panels=None)
            self.assertEqual(warnings, [])
            kinds = {(e["kind"], e["name"]) for e in plan}
            self.assertEqual(kinds, {("concept-list", "User"), ("concept-list", "Order"),
                                     ("panel", "UserSummary")})
            paths = {e["id"]: e["path"] for e in plan}
            self.assertEqual(paths["list:User"], "/api/users")
            self.assertEqual(paths["panel:UserSummary"], "/api/runtime/metadata/ui/panels/UserSummary")

    def test_concept_filter_narrows_the_plan_and_leaves_panels_alone(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            record = npdev_monitor.probe_app(app, include_info=True)
            plan, warnings = npdev_cli._bench_plan(
                record, Path(record["finalAppRoot"]), concepts=["User"], panels=None)
            self.assertEqual(warnings, [])
            names = {(e["kind"], e["name"]) for e in plan}
            self.assertEqual(names, {("concept-list", "User"), ("panel", "UserSummary")})

    def test_an_unknown_concept_name_is_a_named_warning_not_a_silent_no_op(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            record = npdev_monitor.probe_app(app, include_info=True)
            plan, warnings = npdev_cli._bench_plan(
                record, Path(record["finalAppRoot"]), concepts=["NoSuchConcept"], panels=None)
            self.assertEqual([e for e in plan if e["kind"] == "concept-list"], [])
            self.assertTrue(any("NoSuchConcept" in w for w in warnings))

    def test_no_manifest_skips_panels_but_still_plans_concepts(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), panels=False)
            record = npdev_monitor.probe_app(app, include_info=True)
            plan, warnings = npdev_cli._bench_plan(
                record, Path(record["finalAppRoot"]), concepts=None, panels=None)
            self.assertEqual([e for e in plan if e["kind"] == "panel"], [])
            self.assertEqual(len([e for e in plan if e["kind"] == "concept-list"]), 2)
            self.assertTrue(any("generated-ui-manifest.json" in w for w in warnings))


# ------------------------------------------------------------------------------------------------
# run_bench / CLI integration -- HTTP stubbed, filesystem real (TemporaryDirectory).
# ------------------------------------------------------------------------------------------------

class RunBenchMeasurement(unittest.TestCase):
    def test_every_planned_endpoint_is_measured_with_the_requested_sample_count(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_bench(app, samples=4)
            self.assertEqual(result["schemaVersion"], npdev_cli.BENCH_SCHEMA_VERSION)
            self.assertEqual(result["counts"]["total"], 3)
            self.assertEqual(result["counts"]["measured"], 3)
            for endpoint in result["endpoints"]:
                self.assertEqual(endpoint["stats"]["samples"], 4)

    def test_the_probe_authenticates_with_the_apps_live_key(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            seen: list = []
            with running(app):
                run_bench(app, seen=seen)
            keys = {tuple(sorted(headers.values())) for _url, headers in seen}
            self.assertEqual(keys, {(LIVE_KEY,)})

    def test_a_failing_endpoint_is_counted_not_hidden(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_bench(app, statuses={"/api/orders": 500}, samples=3)
            orders = next(e for e in result["endpoints"] if e["name"] == "Order")
            self.assertEqual(orders["stats"]["samples"], 0)
            self.assertEqual(orders["failedSamples"], 3)
            self.assertIn("HTTP 500", orders["failures"][0])
            self.assertEqual(result["counts"]["allFailed"], 1)
            self.assertFalse(result["ok"])

    def test_a_scoped_run_measures_only_the_named_endpoints(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_bench(app, concept=["User"], panel=["UserSummary"])
            self.assertEqual({e["name"] for e in result["endpoints"]}, {"User", "UserSummary"})

    def test_the_report_never_carries_the_apps_api_key(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_bench(app)
            self.assertNotIn(LIVE_KEY, json.dumps(result))

    def test_an_app_that_is_not_running_is_refused_not_reported_as_failed(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with self.assertRaises(npdev_cli.CliError) as caught:
                npdev_cli.run_bench(cli_args(app))
            self.assertIn("not answering", str(caught.exception))
            self.assertFalse((app / "_ops" / npdev_cli.BENCH_REPORT_FILENAME).exists())


class RunBenchBaseline(unittest.TestCase):
    def test_the_first_run_establishes_the_baseline_with_no_prior_file(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_bench(app)
            self.assertFalse(result["baselineExistedBefore"])
            self.assertTrue(result["baselineUpdated"])
            baseline_path = Path(result["baselinePath"])
            self.assertTrue(baseline_path.is_file())
            saved = json.loads(baseline_path.read_text(encoding="utf-8"))
            self.assertEqual(saved["schemaVersion"], npdev_cli.BENCH_BASELINE_SCHEMA_VERSION)
            self.assertEqual(set(saved["endpoints"]),
                             {"list:User", "list:Order", "panel:UserSummary",
                              npdev_cli.BENCH_CONTROL_BASELINE_KEY})
            # Not every regression is real on a first run: nothing was there to be worse than.
            self.assertEqual(result["counts"]["regressed"], 0)

    def test_a_second_run_does_not_overwrite_the_baseline_without_the_flag(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                first = run_bench(app)
                baseline_path = Path(first["baselinePath"])
                before = baseline_path.read_text(encoding="utf-8")
                second = run_bench(app)
            self.assertTrue(second["baselineExistedBefore"])
            self.assertFalse(second["baselineUpdated"])
            self.assertEqual(baseline_path.read_text(encoding="utf-8"), before)

    def test_update_baseline_promotes_the_new_measurement(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                run_bench(app)
                second = run_bench(app, update_baseline=True)
            self.assertTrue(second["baselineUpdated"])

    def test_a_measurement_far_slower_than_the_seeded_baseline_is_flagged_regressed(self):
        # Deterministic by construction, not by controlling the clock: a baseline p50 of 0.0001ms
        # is far below anything a real (even stubbed) HTTP round trip can measure, so the ratio
        # test is certain to trip regardless of machine speed.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            baseline_path = app / "_ops" / npdev_cli.BENCH_BASELINE_FILENAME
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_text(json.dumps({
                "schemaVersion": npdev_cli.BENCH_BASELINE_SCHEMA_VERSION, "appName": "demo",
                "endpoints": {
                    "list:User": {"name": "User", "kind": "concept-list", "path": "/api/users",
                                  "samples": 5, "p50Ms": 0.0001, "p95Ms": 0.0002, "meanMs": 0.0001,
                                  "measuredAt": "2026-08-01T00:00:00Z"},
                },
            }), encoding="utf-8")
            with running(app):
                result = run_bench(app, concept=["User"], panel=[])
            user = next(e for e in result["endpoints"] if e["name"] == "User")
            self.assertTrue(user["baseline"]["hasBaseline"])
            self.assertTrue(user["baseline"]["regressed"])
            self.assertGreater(result["counts"]["regressed"], 0)
            self.assertFalse(result["ok"])
            # A regression must not be allowed to silently overwrite the evidence it just caught.
            self.assertFalse(result["baselineUpdated"])
            still = json.loads(baseline_path.read_text(encoding="utf-8"))
            self.assertEqual(still["endpoints"]["list:User"]["p50Ms"], 0.0001)

    def test_a_measurement_far_faster_than_an_absurd_baseline_is_not_regressed(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            baseline_path = app / "_ops" / npdev_cli.BENCH_BASELINE_FILENAME
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_text(json.dumps({
                "schemaVersion": npdev_cli.BENCH_BASELINE_SCHEMA_VERSION, "appName": "demo",
                "endpoints": {
                    "list:User": {"name": "User", "kind": "concept-list", "path": "/api/users",
                                  "samples": 5, "p50Ms": 100000.0, "p95Ms": 100000.0, "meanMs": 100000.0,
                                  "measuredAt": "2026-08-01T00:00:00Z"},
                },
            }), encoding="utf-8")
            with running(app):
                result = run_bench(app, concept=["User"], panel=[])
            user = next(e for e in result["endpoints"] if e["name"] == "User")
            self.assertFalse(user["baseline"]["regressed"])


class BenchReportFile(unittest.TestCase):
    def test_the_report_is_written_next_to_the_apps_other_run_artifacts(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                result = run_bench(app)
            written = app / "_ops" / npdev_cli.BENCH_REPORT_FILENAME
            self.assertEqual(result["reportPath"], str(written))
            payload = json.loads(written.read_text(encoding="utf-8"))
            self.assertEqual(payload["schemaVersion"], npdev_cli.BENCH_SCHEMA_VERSION)

    def test_report_out_overrides_the_default_location(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            elsewhere = Path(tmp) / "reports" / "bench.json"
            with running(app):
                result = run_bench(app, report_out=str(elsewhere))
            self.assertEqual(result["reportPath"], str(elsewhere))
            self.assertTrue(elsewhere.is_file())
            self.assertFalse((app / "_ops" / npdev_cli.BENCH_REPORT_FILENAME).exists())


class BenchCliExitCodes(unittest.TestCase):
    def test_all_measured_exits_zero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                code, out = run_cli(app)
            self.assertEqual(code, 0)
            self.assertIn("OK", out)

    def test_a_failing_endpoint_exits_nonzero(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                code, out = run_cli(app, statuses={"/api/orders": 500})
            self.assertEqual(code, 2)
            self.assertIn("FAILED", out)

    def test_the_json_form_carries_the_same_rollup(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            with running(app):
                code, out = run_cli(app, extra=["--json"])
            payload = json.loads(out[out.index("{"):])
            self.assertEqual(code, 0)
            self.assertTrue(payload["ok"])
            self.assertEqual(payload["schemaVersion"], npdev_cli.BENCH_SCHEMA_VERSION)

    def test_an_app_that_is_not_running_exits_via_a_named_cli_error(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = npdev_cli.main(["bench", "--app-dir", str(app)])
            self.assertEqual(code, 1)


class BenchHumanSummary(unittest.TestCase):
    def test_summary_names_ok_endpoints_with_their_stats(self):
        result = {
            "appName": "demo", "baseUrl": "http://x", "samplesPerEndpoint": 5, "durationMs": 12,
            "warnings": [], "baselinePath": "/x/bench-baseline.json", "baselineUpdated": True,
            "reportPath": "/x/npdev-bench-report.json",
            "counts": {"measured": 1, "total": 1, "allFailed": 0, "regressed": 0},
            "endpoints": [{
                "kind": "concept-list", "name": "User", "failedSamples": 0,
                "stats": {"samples": 5, "p50Ms": 3.0, "p95Ms": 4.0, "meanMs": 3.2, "stdevMs": 0.4},
                "baseline": {"hasBaseline": False, "regressed": False},
            }],
        }
        text = npdev_cli._bench_human_summary(result)
        self.assertIn("OK", text)
        self.assertIn("User", text)
        self.assertIn("p50=3.0ms", text)
        self.assertIn("no-baseline", text)

    def test_summary_flags_a_regressed_endpoint_by_name(self):
        result = {
            "appName": "demo", "baseUrl": "http://x", "samplesPerEndpoint": 5, "durationMs": 12,
            "warnings": [], "baselinePath": "/x/bench-baseline.json", "baselineUpdated": False,
            "reportPath": "/x/npdev-bench-report.json",
            "counts": {"measured": 1, "total": 1, "allFailed": 0, "regressed": 1},
            "endpoints": [{
                "kind": "panel", "name": "UserSummary", "failedSamples": 0,
                "stats": {"samples": 5, "p50Ms": 30.0, "p95Ms": 40.0, "meanMs": 32.0, "stdevMs": 4.0},
                "baseline": {"hasBaseline": True, "regressed": True, "baselineP50Ms": 10.0, "ratio": 3.0},
            }],
        }
        text = npdev_cli._bench_human_summary(result)
        self.assertIn("REGRESSED", text)
        self.assertIn("UserSummary", text)
        self.assertIn("ratio=3.0x", text)


if __name__ == "__main__":
    unittest.main()
