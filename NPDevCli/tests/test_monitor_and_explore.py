"""Tests for `npdev monitor` and `npdev explore` (MONITOR_PLAN A2/A3/A4/D5/D9/D10).

These are deliberately unit-level and filesystem-only: they build fake app trees in temp
directories, so they run in milliseconds, need no engine, no app, and no network. The live
behaviours (an app that really boots, an engine that really runs a routine) are proven by the
Phase B/D acceptance walks, not here.

What each group is protecting is written on the group, because every one of them is a defect that
already happened or a rule that was already broken once.
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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli
import npdev_explore
import npdev_jsonschema
import npdev_monitor

REPO_ROOT = Path(__file__).resolve().parents[2]


def make_app(root: Path, name: str = "demo", *, marker: bool = False, plan: dict | None = None) -> Path:
    app = root / name
    (app / "_ops").mkdir(parents=True)
    if marker:
        (app / ".npdev-root").write_text("marker", encoding="utf-8")
    if plan is not None:
        (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(plan), encoding="utf-8")
    return app


DEFAULT_PLAN = {
    "appId": "demo", "engine": "H2Local", "serverPort": 8099,
    "resolvedDataRoot": "data", "resolvedDatabaseName": "demo",
    "finalAppPath": ".", "physicalDatabase": True, "username": "sa", "password": "s3cret",
    "host": "localhost", "hostPort": 0, "containerName": "",
}


class DiscoveryRules(unittest.TestCase):
    """A2 acceptance + the marker-pair rule. `.npdev-root` alone never identifies an app -- this repo
    has one -- and `_ops` alone is not enough either, so both branches are asserted in both
    directions."""

    def test_marker_pair_is_recognised(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), marker=True)
            self.assertEqual(npdev_monitor.discovery_rule(app), "marker-pair")

    def test_resolved_plan_alone_is_recognised(self):
        # Every app generated before the marker was emitted. A rule that rejected these would make
        # the Monitor blind to every app a tester already has -- measured: 118 apps on this machine,
        # zero of them carrying a marker.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            self.assertEqual(npdev_monitor.discovery_rule(app), "resolved-plan")

    def test_marker_without_ops_is_not_an_app(self):
        with TemporaryDirectory() as tmp:
            directory = Path(tmp) / "repo"
            directory.mkdir()
            (directory / ".npdev-root").write_text("marker", encoding="utf-8")
            self.assertIsNone(npdev_monitor.discovery_rule(directory))
            record = npdev_monitor.probe_app(directory)
            self.assertEqual(record["status"], "not-an-app")

    def test_bare_ops_directory_is_not_an_app(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp))  # _ops, but neither marker nor plan
            self.assertIsNone(npdev_monitor.discovery_rule(app))

    def test_scan_of_an_empty_path_is_ok_and_empty(self):
        with TemporaryDirectory() as tmp:
            result = npdev_monitor.scan_paths([tmp])
            self.assertTrue(result["ok"])
            self.assertEqual(result["apps"], [])

    def test_scan_keeps_descending_past_a_match(self):
        """The pre-QUAL-3 shared `_ops` sits at the OUTPUT ROOT beside the apps. Stopping at the
        first match returns that single legacy entry and hides every real app under it -- measured
        as 1 found instead of 118."""
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            make_app(root, "outer", plan=DEFAULT_PLAN)
            make_app(root / "outer", "inner", plan={**DEFAULT_PLAN, "appId": "inner"})
            result = npdev_monitor.scan_paths([str(root)], max_depth=4)
            names = sorted(app["name"] for app in result["apps"])
            self.assertEqual(names, ["demo", "inner"])


class ProbeFacts(unittest.TestCase):
    def test_probe_never_publishes_the_password(self):
        # This record is rendered in a window, copied into chat windows, and included in the D10
        # export bundle. The plan carries a real DB password; the probe must not carry it onward.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            record = npdev_monitor.probe_app(app)
            self.assertNotIn("s3cret", json.dumps(record))
            self.assertEqual(record["connection"]["username"], "sa")

    def test_probe_uses_127_0_0_1_for_probing_and_localhost_for_display(self):
        # R3. Windows resolves localhost to ::1 first while the app binds IPv4, so a localhost probe
        # of a healthy app reports it down.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            record = npdev_monitor.probe_app(app)
            self.assertEqual(record["probeBaseUrl"], "http://127.0.0.1:8099")
            self.assertEqual(record["baseUrl"], "http://localhost:8099")

    def test_probe_reports_the_apps_real_api_key_not_the_published_default(self):
        """D-b. info.html publishes `X-Api-Key: dev-key` as a literal, correctly -- the page is
        unauthenticated. But the real value comes from `trialDefaults.apiKey` via the plan, so an app
        that configured its own key has a published default that is simply wrong. The probe answers
        it, and answers `None` rather than guessing when the plan predates the field."""
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), name="configured",
                           plan={**DEFAULT_PLAN, "apiKey": "not-the-default-key"})
            record = npdev_monitor.probe_app(app)
            self.assertEqual("X-Api-Key", record["authHeader"])
            self.assertEqual("not-the-default-key", record["apiKey"])

            legacy = make_app(Path(tmp), name="legacy", plan=DEFAULT_PLAN)
            self.assertIsNone(npdev_monitor.probe_app(legacy)["apiKey"],
                              "a plan with no apiKey is unknown, not 'dev-key' -- inventing the "
                              "default here would recreate the very staleness this fixes")

    def test_exported_bundle_redacts_the_api_key_but_keeps_the_header_name(self):
        # The probe record goes into the support bundle. The key is a credential; the header name is
        # not, and is useless on its own -- so redaction must take exactly one of the two.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan={**DEFAULT_PLAN, "apiKey": "not-the-default-key"})
            redacted = npdev_monitor.redact(npdev_monitor.probe_app(app))
            self.assertEqual("<redacted>", redacted["apiKey"])
            self.assertEqual("X-Api-Key", redacted["authHeader"])

    def test_stopped_app_is_stopped_not_error(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan={**DEFAULT_PLAN, "serverPort": 59997})
            record = npdev_monitor.probe_app(app)
            self.assertEqual(record["health"], "stopped")


class Redaction(unittest.TestCase):
    """D10/E3-a. The export bundle is the MOST likely thing to be pasted into a chat window."""

    def test_credential_shaped_keys_are_redacted(self):
        payload = {"password": "hunter2", "apiKey": "abc", "nested": {"dbPassword": "x"}, "user": "sa"}
        redacted = npdev_monitor.redact(payload)
        self.assertEqual(redacted["password"], "<redacted>")
        self.assertEqual(redacted["apiKey"], "<redacted>")
        self.assertEqual(redacted["nested"]["dbPassword"], "<redacted>")
        self.assertEqual(redacted["user"], "sa")

    def test_password_inside_a_jdbc_url_is_redacted(self):
        url = "jdbc:postgresql://h:5432/db?user=sa&password=hunter2&ssl=false"
        self.assertNotIn("hunter2", npdev_monitor.redact(url))

    def test_export_bundle_contains_no_password(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = make_app(root, plan=DEFAULT_PLAN)
            (app / "logs").mkdir()
            (app / "logs" / "app-20260810T000000Z.log").write_text("started\n", encoding="utf-8")
            out = root / "bundle.zip"
            result = npdev_monitor.export_logs(app, out)
            self.assertTrue(out.is_file())
            import zipfile

            with zipfile.ZipFile(out) as archive:
                blob = b"".join(archive.read(name) for name in archive.namelist())
            self.assertNotIn(b"s3cret", blob)
            self.assertIn("resolved-db-plan.redacted.json", result["included"])


class CliPrintRedaction(unittest.TestCase):
    """REG-153. CodeQL's default-setup scan traced `probe_app()`'s live `apiKey` into
    `_print_result` and the `explore` dispatcher's print branches -- neither ever called `redact()`,
    unlike the export bundle and the AI-repair payload. `monitor probe` is the one deliberate
    exception (`test_probe_reports_the_apps_real_api_key_not_the_published_default` above; consumed
    by `NPDevSamples/scripts/sample-common.ps1`'s `Get-NpdevLiveApiKey`), so it keeps opting out."""

    @staticmethod
    def _capture(fn, *args, **kwargs) -> str:
        buf = io.StringIO()
        with redirect_stdout(buf):
            fn(*args, **kwargs)
        return buf.getvalue()

    def test_print_result_redacts_by_default_json(self):
        result = {"health": "running", "apiKey": "THE-REAL-LIVE-KEY-0001"}
        out = self._capture(npdev_cli._print_result, dict(result), argparse.Namespace(json=True))
        self.assertNotIn("THE-REAL-LIVE-KEY-0001", out)
        self.assertIn("<redacted>", out)

    def test_print_result_redacts_by_default_human_summary(self):
        # A result shape that does NOT match one of `_human_summary`'s named branches falls through
        # to its own `json.dumps(result)` fallback -- that path must be scrubbed too.
        result = {"apiKey": "THE-REAL-LIVE-KEY-0001", "somethingElse": True}
        out = self._capture(npdev_cli._print_result, dict(result), argparse.Namespace(json=False))
        self.assertNotIn("THE-REAL-LIVE-KEY-0001", out)

    def test_print_result_opt_out_keeps_the_real_key(self):
        result = {"health": "running", "apiKey": "THE-REAL-LIVE-KEY-0001"}
        out = self._capture(npdev_cli._print_result, dict(result), argparse.Namespace(json=True),
                            redact_output=False)
        self.assertIn("THE-REAL-LIVE-KEY-0001", out)

    def test_monitor_scan_json_redacts_every_apps_api_key(self):
        with TemporaryDirectory() as tmp:
            make_app(Path(tmp), plan={**DEFAULT_PLAN, "apiKey": "THE-REAL-LIVE-KEY-0001"})
            args = argparse.Namespace(monitor_command="scan", paths=str(Path(tmp)), depth=4,
                                      include_info=False, health_timeout=1.0, json=True)
            out = self._capture(npdev_cli.run_monitor, args)
            self.assertNotIn("THE-REAL-LIVE-KEY-0001", out)

    def test_monitor_probe_json_still_returns_the_real_key(self):
        # The contract `Get-NpdevLiveApiKey` (NPDevSamples/scripts/sample-common.ps1) depends on.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan={**DEFAULT_PLAN, "apiKey": "THE-REAL-LIVE-KEY-0001"})
            args = argparse.Namespace(monitor_command="probe", app_dir=str(app),
                                      include_info=False, health_timeout=1.0, json=True)
            out = self._capture(npdev_cli.run_monitor, args)
            self.assertIn("THE-REAL-LIVE-KEY-0001", out)

    def test_explore_preflight_json_redacts_the_embedded_apikey(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan={**DEFAULT_PLAN, "apiKey": "THE-REAL-LIVE-KEY-0001"})
            args = argparse.Namespace(explore_command="preflight", app_dir=str(app),
                                      engine_port=npdev_monitor.DEFAULT_ENGINE_PORT,
                                      engine_root=None, json=True)
            out = self._capture(npdev_cli.run_explore, args)
            self.assertNotIn("THE-REAL-LIVE-KEY-0001", out)
            self.assertIn("<redacted>", out)


class EngineDetection(unittest.TestCase):
    """D9. The point of the whole ordering is that no path literal appears anywhere."""

    def test_not_found_is_reported_not_guessed(self):
        with TemporaryDirectory() as tmp:
            result = npdev_monitor.detect_engine(port=59998, configured_root=None,
                                                 workspace_root=Path(tmp))
            self.assertFalse(result["found"])
            self.assertEqual(result["state"], "not-found")
            self.assertIsNone(result["root"])

    def test_a_declared_root_must_still_pass_the_contents_check(self):
        # A directory that merely EXISTS is not the engine. Contents, never name.
        with TemporaryDirectory() as tmp:
            fake = Path(tmp) / "scrapforai"
            fake.mkdir()
            result = npdev_monitor.detect_engine(port=59998, configured_root=str(fake),
                                                 workspace_root=Path(tmp))
            self.assertFalse(result["found"])

    def test_a_declared_root_with_the_right_contents_is_accepted(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp) / "anything-at-all"
            (root / "src").mkdir(parents=True)
            (root / "src" / "server.ts").write_text("", encoding="utf-8")
            (root / "node_modules" / ".bin").mkdir(parents=True)
            (root / "node_modules" / ".bin" / "tsx.cmd").write_text("", encoding="utf-8")
            result = npdev_monitor.detect_engine(port=59998, configured_root=str(root),
                                                 workspace_root=Path(tmp))
            self.assertTrue(result["found"])
            self.assertEqual(result["state"], "installed-stopped")
            self.assertEqual(result["via"], "manager.json")


class RoutineValidation(unittest.TestCase):
    """A3/D6. `validate` composes the request the ENGINE would receive, so 'valid here' means 'runs
    in the harness' -- validating the routine FILE would report a missing targetUrl on every correct
    routine in the corpus."""

    def setUp(self):
        self.schema = npdev_explore.routine_schema(REPO_ROOT)

    def test_composer_injects_targetUrl_and_drops_targetPath(self):
        request = npdev_explore.compose_engine_request(
            {"targetPath": "/x/", "steps": [{"action": "goto", "url": "http://127.0.0.1:1/"}]},
            "http://127.0.0.1:8080")
        self.assertEqual(request["targetUrl"], "http://127.0.0.1:8080/x/")
        self.assertNotIn("targetPath", request)

    def test_a_corpus_routine_validates(self):
        corpus = REPO_ROOT / "NPDevSamples" / "scripts" / "browser" / "browser-routines"
        files = sorted(corpus.glob("*.json"))
        self.assertTrue(files, "the routine corpus is missing -- check the unification move")
        result = npdev_explore.validate_routine(REPO_ROOT, files[0])
        self.assertTrue(result["valid"], result.get("errors"))

    def test_an_unknown_action_is_rejected(self):
        request = {"targetUrl": "http://127.0.0.1:8080/", "steps": [{"action": "teleport"}]}
        self.assertTrue(npdev_jsonschema.validate(self.schema, request))

    def test_a_relative_targetUrl_is_rejected(self):
        # The engine is z.string().url(); a validator that let this through would tell the user the
        # opposite of what happens. (The reference `jsonschema` package does not assert `format` by
        # default, which is why this assertion is ours to make.)
        request = {"targetUrl": "/npdev-business-ui/", "steps": [{"action": "goto", "url": "http://x/"}]}
        self.assertTrue(npdev_jsonschema.validate(self.schema, request))

    def test_the_engine_vocabulary_is_read_through_refs(self):
        # Zod hoists repeated literals into $defs, so some action consts are behind a $ref. The
        # first extractor missed them and undercounted 32 -> 30, losing `fill` and `selectOption`,
        # which the corpus uses on hundreds of steps.
        actions = npdev_explore.schema_actions(self.schema)
        self.assertIn("fill", actions)
        self.assertIn("selectOption", actions)
        self.assertGreaterEqual(len(actions), 32)

    def test_lint_flags_localhost(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.json"
            path.write_text(json.dumps({
                "targetPath": "/x/",
                "steps": [{"action": "goto", "url": "http://localhost:8080/x", "label": "go"}],
            }), encoding="utf-8")
            result = npdev_explore.validate_routine(REPO_ROOT, path)
            rules = {w["rule"] for w in result["warnings"]}
            self.assertIn("localhost-not-127-0-0-1", rules)


class MiniValidator(unittest.TestCase):
    """The hand-rolled validator exists because the Manager's private Python has no third-party
    packages (R9). The rule that makes it trustworthy is that an unknown keyword is an ERROR."""

    def test_unknown_keyword_raises_rather_than_passing(self):
        with self.assertRaises(npdev_jsonschema.UnsupportedSchema):
            npdev_jsonschema.validate({"type": "object", "dependentRequired": {"a": ["b"]}}, {})

    def test_integer_type_rejects_boolean(self):
        self.assertTrue(npdev_jsonschema.validate({"type": "integer"}, True))

    def test_additional_properties_false(self):
        errors = npdev_jsonschema.validate(
            {"type": "object", "properties": {"a": {"type": "string"}}, "additionalProperties": False},
            {"a": "x", "b": 1})
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0]["keyword"], "additionalProperties")


class Verdict(unittest.TestCase):
    """D5. The allowlist is narrow, CONDITIONAL and audited. Every one of these asserts a way the
    obvious implementation would be wrong."""

    def _result(self, **evidence):
        base = {"consoleErrors": [], "pageErrors": [], "networkFailures": [],
                "unexpectedExternalRequests": []}
        base.update(evidence)
        return {"status": "passed", "evidence": base}

    def test_clean_run_is_green(self):
        verdict = npdev_explore.evaluate_verdict(self._result(), npdev_explore.load_verdict_config(None))
        self.assertTrue(verdict["green"])

    def test_theme_css_404_is_excused_when_no_custom_theme(self):
        result = self._result(consoleErrors=[{"text": "Failed to load resource: theme.css 404"}])
        verdict = npdev_explore.evaluate_verdict(result, npdev_explore.load_verdict_config(None))
        self.assertTrue(verdict["green"])
        self.assertEqual(len(verdict["excused"]), 1)
        self.assertIn("theme-css", verdict["excused"][0]["rule"])

    def test_theme_css_404_is_NOT_excused_for_an_app_with_a_custom_theme(self):
        # The whole reason the excuse is conditional. An app that ships a REAL theme whose path
        # later breaks loads unstyled, logs this exact 404, and must NOT go green.
        config = {**npdev_explore.load_verdict_config(None), "hasCustomTheme": True}
        result = self._result(consoleErrors=[{"text": "Failed to load resource: theme.css 404"}])
        verdict = npdev_explore.evaluate_verdict(result, config)
        self.assertFalse(verdict["green"])

    def test_401_is_excused_only_on_the_first_navigation(self):
        first = self._result(consoleErrors=[{"text": "Failed to load resource: 401"}])
        self.assertTrue(npdev_explore.evaluate_verdict(first, npdev_explore.load_verdict_config(None))["green"])
        # A 401 on the TENTH request is a broken app, and a blanket rule would hide it.
        later = self._result(consoleErrors=[{"text": "ok"}, {"text": "Failed to load resource: 401"}])
        verdict = npdev_explore.evaluate_verdict(later, npdev_explore.load_verdict_config(None))
        self.assertFalse(verdict["green"])

    def test_a_failed_step_is_red_even_with_no_errors(self):
        result = {"status": "failed", "failedStepIndex": 3, "evidence": {
            "consoleErrors": [], "pageErrors": [], "networkFailures": [],
            "unexpectedExternalRequests": []}}
        verdict = npdev_explore.evaluate_verdict(result, npdev_explore.load_verdict_config(None))
        self.assertFalse(verdict["green"])

    def test_every_excuse_is_recorded_on_the_run(self):
        config = {**npdev_explore.load_verdict_config(None),
                  "allowedConsoleErrorSubstrings": ["deliberate-400"]}
        result = self._result(consoleErrors=[{"text": "a deliberate-400 we expect"}])
        verdict = npdev_explore.evaluate_verdict(result, config)
        self.assertTrue(verdict["green"])
        self.assertEqual(verdict["excused"][0]["rule"], "app:deliberate-400")
        self.assertEqual(verdict["allowedConsoleErrorSubstrings"], ["deliberate-400"])


class Retention(unittest.TestCase):
    """A4's retention decision: records are NEVER deleted, only blobs are pruned, pinned wins."""

    def _write_run(self, app: Path, run_id: str, *, green: bool, started: str,
                   blob: str, pinned: bool = False, ledger: str | None = None):
        record = {
            "schemaVersion": npdev_explore.RUN_SCHEMA_VERSION, "runId": run_id, "scope": "app",
            "definition": {"kind": "routine-json", "path": "r.json", "scenarioName": "s",
                           "contentSha256": "0" * 64},
            "target": {"baseUrl": "http://127.0.0.1:1"}, "driver": "cli", "startedAt": started,
            "status": "passed" if green else "failed", "steps": [],
            "evidence": {"consoleErrors": [], "pageErrors": [], "networkFailures": [],
                         "unexpectedExternalRequests": [],
                         "screenshots": [{"name": "s", "blob": f"blobs/{blob}", "sha256": blob}]},
            "verdict": {"green": green, "allowedConsoleErrorSubstrings": [], "excused": []},
            "pinned": pinned, "ledgerId": ledger,
        }
        npdev_explore.append_run(app, record)
        blobs = npdev_explore.blobs_dir(app)
        blobs.mkdir(parents=True, exist_ok=True)
        (blobs / blob).write_bytes(b"x")

    def test_prune_removes_blobs_but_never_records(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            for index in range(14):
                self._write_run(app, f"run-{index:02d}", green=True,
                                started="2020-01-01T00:00:00Z", blob=f"b{index:02d}")
            before = len(npdev_explore.read_index(app))
            result = npdev_explore.prune(app, keep_per_scenario=10)
            self.assertEqual(len(npdev_explore.read_index(app)), before)
            self.assertEqual(result["recordsDeleted"], 0)
            self.assertEqual(result["blobsRemoved"], 4)

    def test_a_pinned_run_keeps_its_blob_forever(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            self._write_run(app, "ancient", green=True, started="2000-01-01T00:00:00Z",
                            blob="keepme", pinned=True)
            for index in range(12):
                self._write_run(app, f"run-{index:02d}", green=True,
                                started="2020-01-01T00:00:00Z", blob=f"b{index:02d}")
            npdev_explore.prune(app, keep_per_scenario=10)
            self.assertTrue((npdev_explore.blobs_dir(app) / "keepme").is_file())

    def test_a_ledger_linked_run_keeps_its_blob(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            self._write_run(app, "reg", green=False, started="2000-01-01T00:00:00Z",
                            blob="regblob", ledger="REG-144")
            for index in range(12):
                self._write_run(app, f"run-{index:02d}", green=True,
                                started="2020-01-01T00:00:00Z", blob=f"b{index:02d}")
            result = npdev_explore.prune(app, keep_per_scenario=10)
            self.assertTrue((npdev_explore.blobs_dir(app) / "regblob").is_file())
            self.assertIn("REG-144", result["keptBecause"]["reg"])

    def test_prune_says_what_it_kept_and_why(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            self._write_run(app, "only", green=True, started="2020-01-01T00:00:00Z", blob="b")
            result = npdev_explore.prune(app)
            self.assertIn("only", result["keptBecause"])


class SingleFlight(unittest.TestCase):
    """R7: one exploration per app. Two browsers driving the same app produce evidence neither can
    be trusted about."""

    def test_a_second_run_is_refused_while_one_is_held(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            with npdev_explore.RunLock(app):
                with self.assertRaises(npdev_explore.ExploreError):
                    with npdev_explore.RunLock(app):
                        pass

    def test_a_stale_lock_does_not_block_forever(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            with npdev_explore.RunLock(app):
                pass
            # A lock nobody can clear is worse than the collision it prevents.
            with npdev_explore.RunLock(app, stale_after_seconds=0):
                pass


class RunRecordShape(unittest.TestCase):
    def test_a_recorded_run_validates_against_its_own_schema(self):
        schema = npdev_explore.run_schema(REPO_ROOT)
        self.assertIsNotNone(schema, "schemas/ai/exploration-run.schema.json is missing")
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            routine = {"targetPath": "/x/", "scenarioName": "s",
                       "steps": [{"action": "goto", "url": "http://127.0.0.1:1/"}]}
            result = {
                "status": "passed", "durationMs": 10, "steps": [
                    {"index": 0, "action": "goto", "label": "go", "status": "passed", "durationMs": 5}],
                "evidence": {"consoleErrors": [], "pageErrors": [], "networkFailures": [],
                             "unexpectedExternalRequests": [], "screenshots": [], "console": [],
                             "network": []},
                "extracted": {},
            }
            record = npdev_explore.build_run_record(
                app_dir=app, repo_root=REPO_ROOT, result=result, routine=routine,
                routine_file=None, driver="cli",
                app_record=npdev_monitor.probe_app(app), started_at="2026-08-10T00:00:00Z",
                duration_ms=10, engine_version=None)
            errors = npdev_jsonschema.validate(schema, record)
            self.assertEqual(errors, [], npdev_jsonschema.describe(errors))

    def test_the_index_line_is_a_summary_not_the_whole_record(self):
        # runs.jsonl is read on every Monitor refresh; a file that grows by 40 KB per run stops
        # being cheap to read.
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            record = {
                "schemaVersion": npdev_explore.RUN_SCHEMA_VERSION, "runId": "r1", "scope": "app",
                "definition": {"kind": "routine-json", "path": "r.json", "contentSha256": "0" * 64},
                "target": {"baseUrl": "http://127.0.0.1:1"}, "driver": "cli",
                "startedAt": "2026-08-10T00:00:00Z", "status": "passed",
                "steps": [{"index": i, "status": "passed"} for i in range(50)],
                "evidence": {"consoleErrors": [], "pageErrors": [], "networkFailures": [],
                             "unexpectedExternalRequests": []},
                "verdict": {"green": True, "allowedConsoleErrorSubstrings": [], "excused": []},
            }
            npdev_explore.append_run(app, record)
            row = npdev_explore.read_index(app)[0]
            self.assertNotIn("steps", row)
            self.assertEqual(row["stepCount"], 50)


if __name__ == "__main__":
    unittest.main()
