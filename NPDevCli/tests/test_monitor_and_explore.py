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
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli
import npdev_explore
import npdev_jsonschema
import npdev_monitor
import npdev_png

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


def _app_record(name: str, *, status: str = "ok", health: str = "running", port: int | None = 8080) -> dict:
    return {"name": name, "status": status, "health": health, "port": port}


class SharedIngress(unittest.TestCase):
    """R9.7. `npdev monitor ingress` generates ONE shared
    Caddy config for the whole box from the Monitor's own live app inventory -- the per-app
    `deploy/Caddyfile` every generated app ships claims the box's 80/443 exclusively, so at most one
    app could ever get TLS through it; this is the box-level answer instead."""

    def test_the_same_app_discovered_at_two_nesting_levels_is_routed_once(self):
        """A generated app satisfies the discovery rule at BOTH `<app>` and its nested `<app>/App`,
        so `scan_paths` returns it twice. Measured against this machine's real Build root: 51 records
        for 8 distinct running apps, and without the guard the config emitted `npdev-canary` AND
        `npdev-canary-2` both reverse-proxying 127.0.0.1:8103 -- a phantom second instance."""
        apps = [
            _app_record("npdev-canary", port=8103),
            _app_record("npdev-canary", port=8103),
        ]
        config = npdev_monitor.shared_ingress_config(apps, mode="path")

        self.assertEqual([r["slug"] for r in config["routed"]], ["npdev-canary"])
        self.assertNotIn("npdev-canary-2", config["caddyfile"])
        self.assertEqual(len(config["skipped"]), 1)
        self.assertIn("another nesting level", config["skipped"][0]["reason"])

    def test_two_different_apps_on_one_port_refuse_the_second_route(self):
        """Only one process can listen on a port, so routing both would proxy the second app's URL
        to the FIRST app's process -- silently serving the wrong application. Observed live: a stale
        port in one app's plan put `thirdparty-probe` on `npdev-canary`'s 8103."""
        apps = [
            _app_record("npdev-canary", port=8103),
            _app_record("thirdparty-probe", port=8103),
        ]
        config = npdev_monitor.shared_ingress_config(apps, mode="path")

        self.assertEqual([r["name"] for r in config["routed"]], ["npdev-canary"])
        self.assertEqual(len(config["skipped"]), 1)
        self.assertEqual(config["skipped"][0]["name"], "thirdparty-probe")
        self.assertIn("conflict", config["skipped"][0]["reason"])
        self.assertIn("npdev-canary", config["skipped"][0]["reason"],
                      "the refusal must name the app that already owns the port")
        self.assertEqual(config["caddyfile"].count("reverse_proxy 127.0.0.1:8103"), 1)

    def test_only_running_apps_with_a_port_are_routed(self):
        apps = [
            _app_record("healthy-one", port=8081),
            _app_record("healthy-two", port=8082),
            _app_record("stopped-app", health="stopped", port=8090),
            _app_record("starting-app", health="starting", port=8091),
            _app_record("error-app", health="error", port=8092),
            _app_record("not-an-app", status="not-an-app", health="unknown", port=None),
            _app_record("no-port-app", port=None),
        ]
        config = npdev_monitor.shared_ingress_config(apps, mode="path")
        routed_names = sorted(a["name"] for a in config["routed"])
        skipped_names = sorted(s["name"] for s in config["skipped"])
        self.assertEqual(routed_names, ["healthy-one", "healthy-two"])
        self.assertEqual(skipped_names, [
            "error-app", "no-port-app", "not-an-app", "starting-app", "stopped-app",
        ])
        # Every skip is NAMED with a reason -- never a silent drop.
        for entry in config["skipped"]:
            self.assertTrue(entry["reason"])

    def test_path_mode_routes_by_slug_prefix_to_each_apps_port(self):
        apps = [_app_record("My App", port=8081), _app_record("Other App", port=8082)]
        config = npdev_monitor.shared_ingress_config(apps, mode="path")
        self.assertIn("handle_path /my-app/*", config["caddyfile"])
        self.assertIn("reverse_proxy 127.0.0.1:8081", config["caddyfile"])
        self.assertIn("handle_path /other-app/*", config["caddyfile"])
        self.assertIn("reverse_proxy 127.0.0.1:8082", config["caddyfile"])
        self.assertIn("tls internal", config["caddyfile"])
        # ONE shared site, not one per app, in path mode.
        self.assertEqual(config["caddyfile"].count(":443 {"), 1)

    def test_hostname_mode_routes_each_app_to_its_own_site_and_cert(self):
        apps = [_app_record("My App", port=8081), _app_record("Other App", port=8082)]
        config = npdev_monitor.shared_ingress_config(apps, mode="hostname", base_domain="localhost")
        self.assertIn("my-app.localhost {", config["caddyfile"])
        self.assertIn("other-app.localhost {", config["caddyfile"])
        self.assertEqual(config["caddyfile"].count("tls internal"), 2)

    def test_name_collision_after_slugging_gets_a_distinct_route_not_a_silent_overwrite(self):
        apps = [_app_record("My App", port=8081), _app_record("my-app", port=8082)]
        config = npdev_monitor.shared_ingress_config(apps, mode="path")
        slugs = [a["slug"] for a in config["routed"]]
        self.assertEqual(len(slugs), len(set(slugs)), "two apps must never collapse onto one route")
        self.assertIn("handle_path /my-app/*", config["caddyfile"])
        self.assertIn("handle_path /my-app-2/*", config["caddyfile"])

    def test_empty_inventory_still_produces_a_valid_fallback_config(self):
        for mode in ("path", "hostname"):
            config = npdev_monitor.shared_ingress_config([], mode=mode)
            self.assertEqual(config["routed"], [])
            self.assertIn("tls internal", config["caddyfile"])
            self.assertIn("404", config["caddyfile"])

    def test_same_inventory_produces_byte_identical_output(self):
        # The platform's generator-determinism requirement, applied to this generator too: same
        # inventory in, same bytes out, every time -- no timestamps, no absolute paths.
        apps = [_app_record("App One", port=8081), _app_record("App Two", port=8082)]
        first = npdev_monitor.shared_ingress_config(apps, mode="path")["caddyfile"]
        second = npdev_monitor.shared_ingress_config(list(reversed(apps)), mode="path")["caddyfile"]
        self.assertEqual(first, second)

    def test_unknown_mode_is_rejected_rather_than_silently_defaulted(self):
        with self.assertRaises(ValueError):
            npdev_monitor.shared_ingress_config([_app_record("x")], mode="bogus")

    def test_write_shared_ingress_writes_lf_not_crlf_and_reports_what_it_wrote(self):
        # feedback_pathlib_write_text_crlf_on_windows: write_bytes only, never write_text, so the
        # LF the Caddyfile text was built with is not silently widened to CRLF on Windows.
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            make_app(root, "demo", plan={**DEFAULT_PLAN, "serverPort": 59998})
            out_path = root / "ingress" / "Caddyfile"
            with mock.patch.object(
                npdev_monitor, "scan_paths",
                return_value={"apps": [_app_record("demo", port=8081)], "searched": []},
            ):
                result = npdev_monitor.write_shared_ingress([str(root)], out_path, mode="path")
            self.assertEqual(result["outPath"], str(out_path.resolve()))
            self.assertEqual(result["scannedApps"], 1)
            raw = out_path.read_bytes()
            self.assertNotIn(b"\r\n", raw)
            self.assertIn(b"reverse_proxy 127.0.0.1:8081", raw)


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

    # A REAL Chromium consoleError never puts the resource name in `text` -- only a fake test fixture
    # would write "theme.css 404" into `text` itself. Measured on a live run (2026-08-18):
    # {"text": "Failed to load resource: the server responded with a status of 404 ()",
    #  "location": {"url": ".../theme.css", ...}}. These two tests used the unrealistic shape until
    # MON-12: it silently hid that the theme.css default excuse never actually fired against a real
    # consoleError entry (only its networkFailure sibling, which does not gate green).
    def _theme_css_console_error(self) -> dict:
        return {
            "type": "error",
            "text": "Failed to load resource: the server responded with a status of 404 ()",
            "location": {"url": "http://127.0.0.1:8199/theme.css", "line": 0, "column": 0},
        }

    def test_theme_css_404_is_excused_when_no_custom_theme(self):
        result = self._result(consoleErrors=[self._theme_css_console_error()])
        verdict = npdev_explore.evaluate_verdict(result, npdev_explore.load_verdict_config(None))
        self.assertTrue(verdict["green"])
        self.assertEqual(len(verdict["excused"]), 1)
        self.assertIn("theme-css", verdict["excused"][0]["rule"])

    def test_theme_css_404_is_NOT_excused_for_an_app_with_a_custom_theme(self):
        # The whole reason the excuse is conditional. An app that ships a REAL theme whose path
        # later breaks loads unstyled, logs this exact 404, and must NOT go green.
        config = {**npdev_explore.load_verdict_config(None), "hasCustomTheme": True}
        result = self._result(consoleErrors=[self._theme_css_console_error()])
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

    # MON-12. A failed-resource console error's TEXT is generic on every engine/app/request --
    # "Failed to load resource: the server responded with a status of 409 ()" -- the request that
    # failed is only named in `location.url`. Without a URL-matching excuse, a routine that
    # deliberately provokes one specific 409 is structurally incapable of going green without a
    # blanket "409" that would also hide a real one anywhere else in the run.

    def test_url_naming_excuse_matches_the_named_request_only(self):
        config = {**npdev_explore.load_verdict_config(None), "allowedConsoleErrorSubstrings": [
            {"urlContains": "/api/concepts/users", "status": 409, "note": "r7-1 provokes EmailUnique"}]}
        provoked = {
            "type": "error",
            "text": "Failed to load resource: the server responded with a status of 409 ()",
            "location": {"url": "http://127.0.0.1:8199/api/concepts/users", "line": 0, "column": 0},
        }
        verdict = npdev_explore.evaluate_verdict(self._result(consoleErrors=[provoked]), config)
        self.assertTrue(verdict["green"])
        self.assertIn("r7-1 provokes EmailUnique", verdict["excused"][0]["rule"])

    def test_url_naming_excuse_does_NOT_excuse_a_409_on_a_different_request(self):
        # The whole point: naming ONE request must not become a blanket "409".
        config = {**npdev_explore.load_verdict_config(None), "allowedConsoleErrorSubstrings": [
            {"urlContains": "/api/concepts/users", "status": 409}]}
        unrelated = {
            "type": "error",
            "text": "Failed to load resource: the server responded with a status of 409 ()",
            "location": {"url": "http://127.0.0.1:8199/api/concepts/orders", "line": 0, "column": 0},
        }
        verdict = npdev_explore.evaluate_verdict(self._result(consoleErrors=[unrelated]), config)
        self.assertFalse(verdict["green"])

    def test_url_naming_excuse_respects_status_even_on_a_matching_url(self):
        # Same request, different status -- e.g. a 500 where only a 409 was ever expected. Naming a
        # status must narrow, not just decorate, the excuse.
        config = {**npdev_explore.load_verdict_config(None), "allowedConsoleErrorSubstrings": [
            {"urlContains": "/api/concepts/users", "status": 409}]}
        wrong_status = {
            "type": "error",
            "text": "Failed to load resource: the server responded with a status of 500 ()",
            "location": {"url": "http://127.0.0.1:8199/api/concepts/users", "line": 0, "column": 0},
        }
        verdict = npdev_explore.evaluate_verdict(self._result(consoleErrors=[wrong_status]), config)
        self.assertFalse(verdict["green"])

    def test_an_object_excuse_with_no_urlContains_is_refused_not_widened(self):
        # A dict with no urlContains is not a narrower rule than a string -- it is a blanket rule
        # wearing an object, so it must match NOTHING rather than fall back to matching everything.
        config = {**npdev_explore.load_verdict_config(None),
                  "allowedConsoleErrorSubstrings": [{"status": 409}]}
        provoked = {
            "type": "error",
            "text": "Failed to load resource: the server responded with a status of 409 ()",
            "location": {"url": "http://127.0.0.1:8199/api/concepts/users", "line": 0, "column": 0},
        }
        verdict = npdev_explore.evaluate_verdict(self._result(consoleErrors=[provoked]), config)
        self.assertFalse(verdict["green"])

    def test_url_naming_excuse_also_works_on_a_networkFailure_entry(self):
        # networkFailure names its request as origin+pathname, not location.url -- the same config
        # entry has to reach both evidence shapes for the same real request.
        config = {**npdev_explore.load_verdict_config(None), "allowedConsoleErrorSubstrings": [
            {"urlContains": "/api/concepts/users", "status": 409}], "strictNetwork": True}
        failure = {"origin": "http://127.0.0.1:8199", "pathname": "/api/concepts/users",
                   "method": "POST", "resourceType": "fetch", "status": 409,
                   "unexpectedExternalOrigin": False}
        verdict = npdev_explore.evaluate_verdict(self._result(networkFailures=[failure]), config)
        self.assertTrue(verdict["green"])

    def test_plain_string_excuses_still_work_unchanged(self):
        # Backward compatibility: every existing app config using bare strings must keep behaving
        # exactly as before MON-12.
        config = {**npdev_explore.load_verdict_config(None),
                  "allowedConsoleErrorSubstrings": ["deliberate-400"]}
        result = self._result(consoleErrors=[{"text": "a deliberate-400 we expect"}])
        self.assertTrue(npdev_explore.evaluate_verdict(result, config)["green"])

    def test_the_default_theme_css_excuse_reaches_the_consoleError_not_just_its_networkFailure_sibling(self):
        # The regression this fix closes: BOTH representations of the same real failed request must
        # be excused by the default rule, not only the networkFailure one (which does not gate
        # green). Before the fix, this exact evidence -- one consoleError, one networkFailure, both
        # for the same theme.css 404 -- was RED, because only the networkFailure entry excused.
        console_entry = {
            "type": "error",
            "text": "Failed to load resource: the server responded with a status of 404 ()",
            "location": {"url": "http://127.0.0.1:8199/theme.css", "line": 0, "column": 0},
        }
        network_entry = {"origin": "http://127.0.0.1:8199", "pathname": "/theme.css",
                         "method": "GET", "resourceType": "stylesheet", "status": 404,
                         "unexpectedExternalOrigin": False}
        result = self._result(consoleErrors=[console_entry], networkFailures=[network_entry])
        verdict = npdev_explore.evaluate_verdict(result, npdev_explore.load_verdict_config(None))
        self.assertTrue(verdict["green"], verdict)
        kinds_excused = {e["kind"] for e in verdict["excused"]}
        self.assertIn("consoleError", kinds_excused)


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

    def test_a_run_record_carrying_a_MON12_url_excuse_still_validates(self):
        # The excused reason travels through build_run_record -> the run record -> the schema,
        # object-shaped allowlist entry and all.
        schema = npdev_explore.run_schema(REPO_ROOT)
        self.assertIsNotNone(schema, "schemas/ai/exploration-run.schema.json is missing")
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            npdev_explore.config_path(app).write_text(json.dumps({
                "allowedConsoleErrorSubstrings": [
                    {"urlContains": "/api/concepts/users", "status": 409, "note": "r7-1 EmailUnique"}],
            }), encoding="utf-8")
            routine = {"targetPath": "/x/", "scenarioName": "s",
                       "steps": [{"action": "goto", "url": "http://127.0.0.1:1/"}]}
            result = {
                "status": "passed", "durationMs": 10, "steps": [
                    {"index": 0, "action": "goto", "label": "go", "status": "passed", "durationMs": 5}],
                "evidence": {
                    "consoleErrors": [{
                        "type": "error",
                        "text": "Failed to load resource: the server responded with a status of 409 ()",
                        "location": {"url": "http://127.0.0.1:1/api/concepts/users", "line": 0, "column": 0},
                    }],
                    "pageErrors": [], "networkFailures": [], "unexpectedExternalRequests": [],
                    "screenshots": [], "console": [], "network": [],
                },
                "extracted": {},
            }
            record = npdev_explore.build_run_record(
                app_dir=app, repo_root=REPO_ROOT, result=result, routine=routine,
                routine_file=None, driver="cli",
                app_record=npdev_monitor.probe_app(app), started_at="2026-08-10T00:00:00Z",
                duration_ms=10, engine_version=None)
            self.assertTrue(record["verdict"]["green"], record["verdict"])
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


def _solid_png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    pixels = bytes(rgb) * (width * height)
    return npdev_png.encode_png(width, height, 3, pixels)


def _png_with_changed_pixels(width: int, height: int, base_rgb: tuple[int, int, int],
                             changed_rgb: tuple[int, int, int], count: int) -> bytes:
    """A `base_rgb` field with the first `count` pixels (row-major) set to `changed_rgb` instead --
    a deterministic way to build "N pixels differ" fixtures with no binary files checked in."""
    pixels = bytearray(bytes(base_rgb) * (width * height))
    for i in range(count):
        pixels[i * 3:i * 3 + 3] = bytes(changed_rgb)
    return npdev_png.encode_png(width, height, 3, bytes(pixels))


class PixelDiffCodec(unittest.TestCase):
    """npdev_png.py in isolation -- the codec R3.6 needed rather than a Pillow/numpy dependency."""

    def test_encode_decode_roundtrips_rgb(self):
        px = bytes([10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120])  # 2x2 RGB
        data = npdev_png.encode_png(2, 2, 3, px)
        w, h, c, decoded = npdev_png.decode_png(data)
        self.assertEqual((w, h, c), (2, 2, 3))
        self.assertEqual(bytes(decoded), px)

    def test_a_non_png_file_is_a_named_error_not_a_crash(self):
        with self.assertRaises(npdev_png.PngError):
            npdev_png.decode_png(b"this is not a png")

    def test_diff_of_identical_images_is_zero(self):
        data = _solid_png(6, 6, (10, 10, 10))
        diff = npdev_png.diff_png_bytes(data, data)
        self.assertFalse(diff["dimensionMismatch"])
        self.assertEqual(diff["diffFraction"], 0.0)

    def test_dimension_mismatch_is_reported_not_raised(self):
        small = _solid_png(4, 4, (0, 0, 0))
        big = _solid_png(4, 5, (0, 0, 0))
        diff = npdev_png.diff_png_bytes(small, big)  # must not raise
        self.assertTrue(diff["dimensionMismatch"])
        self.assertIsNone(diff["diffFraction"])
        self.assertIsNone(diff["diffPng"])


class ScreenshotBaselinePixelDiff(unittest.TestCase):
    """R3.6: the baseline comparison is a pure-Python pixel diff, not sha256 equality -- a 1-pixel
    change passes below threshold, a moved/changed region fails with a written diff-image path, a
    dimension change is a clear failure rather than a crash, and `explore accept` can re-baseline one
    screenshot without touching the others."""

    IMG_W, IMG_H = 50, 50  # 2500 pixels -- big enough that 1 pixel is comfortably under the
                           # DEFAULT_SCREENSHOT_DIFF_THRESHOLD (0.001 = 2.5 pixels)

    def _result(self, scenario: str, png_bytes: bytes, tmp: Path, name: str = "shot") -> dict:
        png_path = Path(tmp) / f"{name}-{scenario}-{id(png_bytes)}.png"
        png_path.write_bytes(png_bytes)
        return {
            "status": "passed", "scenarioName": scenario, "durationMs": 10,
            "steps": [{"index": 0, "action": "goto", "label": "go", "status": "passed", "durationMs": 5}],
            "evidence": {"consoleErrors": [], "pageErrors": [], "networkFailures": [],
                         "unexpectedExternalRequests": [], "console": [], "network": [],
                         "screenshots": [{"name": name, "path": str(png_path)}]},
            "extracted": {},
        }

    def _record(self, app: Path, tmp: Path, scenario: str, png_bytes: bytes, name: str = "shot") -> dict:
        result = self._result(scenario, png_bytes, tmp, name)
        record = npdev_explore.build_run_record(
            app_dir=app, repo_root=REPO_ROOT, result=result, routine={"scenarioName": scenario},
            routine_file=None, driver="cli", app_record=npdev_monitor.probe_app(app),
            started_at="2026-08-19T00:00:00Z", duration_ms=10, engine_version=None,
        )
        npdev_explore.append_run(app, record)
        return record

    def test_a_1_pixel_change_passes_below_threshold(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            base = _solid_png(self.IMG_W, self.IMG_H, (10, 10, 10))
            baseline_record = self._record(app, Path(tmp), "px", base)
            npdev_explore.accept_baseline(app, baseline_record["runId"])

            one_pixel_changed = _png_with_changed_pixels(
                self.IMG_W, self.IMG_H, (10, 10, 10), (250, 250, 250), count=1)
            record = self._record(app, Path(tmp), "px", one_pixel_changed)

            self.assertTrue(record["verdict"]["green"], record["verdict"])
            shot = record["evidence"]["screenshots"][0]
            self.assertFalse(shot["regressed"])
            self.assertGreater(shot["pixelDiffFraction"], 0.0)
            self.assertLess(shot["pixelDiffFraction"], shot["pixelDiffThreshold"])
            self.assertNotIn("diffBlob", shot)

    def test_a_moved_region_fails_with_a_written_diff_image_path(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            base = _solid_png(self.IMG_W, self.IMG_H, (10, 10, 10))
            baseline_record = self._record(app, Path(tmp), "moved", base)
            npdev_explore.accept_baseline(app, baseline_record["runId"])

            # A third of the image changed -- a moved button, not rendering noise.
            moved = _png_with_changed_pixels(
                self.IMG_W, self.IMG_H, (10, 10, 10), (250, 250, 250),
                count=(self.IMG_W * self.IMG_H) // 3)
            record = self._record(app, Path(tmp), "moved", moved)

            self.assertFalse(record["verdict"]["green"])
            self.assertTrue(any("regressed" in reason for reason in record["verdict"]["reasons"]))
            shot = record["evidence"]["screenshots"][0]
            self.assertTrue(shot["regressed"])
            diff_blob = shot.get("diffBlob")
            self.assertTrue(diff_blob, "a regressed screenshot must carry a diff image path")
            self.assertTrue((npdev_explore.runs_root(app) / diff_blob).is_file())

            # `explore show` resolves it to an absolute path a tester can actually open.
            shown = npdev_explore.show_run(app, record["runId"])
            shown_shot = shown["run"]["evidence"]["screenshots"][0]
            self.assertTrue(Path(shown_shot["resolvedDiffPath"]).is_file())

    def test_a_dimension_mismatch_is_a_clear_failure_not_a_crash(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            base = _solid_png(self.IMG_W, self.IMG_H, (10, 10, 10))
            baseline_record = self._record(app, Path(tmp), "resized", base)
            npdev_explore.accept_baseline(app, baseline_record["runId"])

            resized = _solid_png(self.IMG_W + 10, self.IMG_H, (10, 10, 10))
            record = self._record(app, Path(tmp), "resized", resized)  # must not raise

            self.assertFalse(record["verdict"]["green"])
            shot = record["evidence"]["screenshots"][0]
            self.assertTrue(shot["dimensionMismatch"])
            self.assertTrue(shot["regressed"])

    def test_a_screenshot_below_threshold_is_excused_by_a_wider_per_screenshot_override(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            npdev_explore.config_path(app).write_text(
                json.dumps({"screenshotDiffThresholds": {"shot": 0.9}}), encoding="utf-8")
            base = _solid_png(self.IMG_W, self.IMG_H, (10, 10, 10))
            baseline_record = self._record(app, Path(tmp), "cfg", base)
            npdev_explore.accept_baseline(app, baseline_record["runId"])

            moved = _png_with_changed_pixels(
                self.IMG_W, self.IMG_H, (10, 10, 10), (250, 250, 250),
                count=(self.IMG_W * self.IMG_H) // 3)
            record = self._record(app, Path(tmp), "cfg", moved)

            self.assertTrue(record["verdict"]["green"], record["verdict"])
            self.assertFalse(record["evidence"]["screenshots"][0]["regressed"])

    def test_accepting_one_screenshot_leaves_the_others_baseline_alone(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            base_a = _solid_png(10, 10, (1, 1, 1))
            base_b = _solid_png(10, 10, (2, 2, 2))

            def _two_shot_result(scenario, png_a, png_b):
                path_a = Path(tmp) / f"a-{id(png_a)}.png"
                path_b = Path(tmp) / f"b-{id(png_b)}.png"
                path_a.write_bytes(png_a)
                path_b.write_bytes(png_b)
                return {
                    "status": "passed", "scenarioName": scenario, "durationMs": 10,
                    "steps": [], "evidence": {
                        "consoleErrors": [], "pageErrors": [], "networkFailures": [],
                        "unexpectedExternalRequests": [], "console": [], "network": [],
                        "screenshots": [{"name": "a", "path": str(path_a)}, {"name": "b", "path": str(path_b)}],
                    },
                    "extracted": {},
                }

            result1 = _two_shot_result("two", base_a, base_b)
            record1 = npdev_explore.build_run_record(
                app_dir=app, repo_root=REPO_ROOT, result=result1, routine={"scenarioName": "two"},
                routine_file=None, driver="cli", app_record=npdev_monitor.probe_app(app),
                started_at="2026-08-19T00:00:00Z", duration_ms=10, engine_version=None)
            npdev_explore.append_run(app, record1)
            npdev_explore.accept_baseline(app, record1["runId"])

            # Second run: BOTH screenshots changed.
            new_a = _solid_png(10, 10, (200, 1, 1))
            new_b = _solid_png(10, 10, (2, 200, 2))
            result2 = _two_shot_result("two", new_a, new_b)
            record2 = npdev_explore.build_run_record(
                app_dir=app, repo_root=REPO_ROOT, result=result2, routine={"scenarioName": "two"},
                routine_file=None, driver="cli", app_record=npdev_monitor.probe_app(app),
                started_at="2026-08-19T00:01:00Z", duration_ms=10, engine_version=None)
            npdev_explore.append_run(app, record2)

            # Accept ONLY "a" from run2.
            result = npdev_explore.accept_baseline(app, record2["runId"], screenshot="a")
            self.assertEqual(result["screenshot"], "a")

            new_shot_a = next(s for s in record2["evidence"]["screenshots"] if s["name"] == "a")
            stored = npdev_monitor._read_json(npdev_explore.baseline_path(app, "two"))
            by_name = {s["name"]: s for s in stored["screenshots"]}
            self.assertEqual(by_name["a"]["sha256"], new_shot_a["sha256"])
            # "b" was never told to accept, so it must still be run1's original screenshot.
            original_shot_b = next(s for s in record1["evidence"]["screenshots"] if s["name"] == "b")
            self.assertEqual(by_name["b"]["sha256"], original_shot_b["sha256"])

    def test_accepting_an_unknown_screenshot_name_is_refused(self):
        with TemporaryDirectory() as tmp:
            app = make_app(Path(tmp), plan=DEFAULT_PLAN)
            base = _solid_png(self.IMG_W, self.IMG_H, (10, 10, 10))
            record = self._record(app, Path(tmp), "solo", base)
            with self.assertRaises(npdev_explore.ExploreError):
                npdev_explore.accept_baseline(app, record["runId"], screenshot="no-such-name")


GREEN_PREFLIGHT = {
    "ok": False,
    "checks": [
        {"id": "app-is-generated", "name": "the target is a generated NPDev app",
         "status": "pass", "detail": "ok"},
        {"id": "app-healthy", "name": "the app answers /actuator/health",
         "status": "pass", "detail": "ok"},
        # Deliberately FAILING, and deliberately not a blocker: `run_exploration` starts the engine
        # when it is merely absent, so a suite that refused here would be stricter than a single run.
        {"id": "engine-available", "name": "the ScrapForAI engine is available",
         "status": "fail", "detail": "no engine on this machine"},
        {"id": "origin-allowlisted", "name": "the app origin will be allowlisted",
         "status": "pass", "detail": "ok"},
    ],
    "app": {}, "engine": {},
}

APP_DOWN_PREFLIGHT = {
    "ok": False,
    "checks": [
        {"id": "app-is-generated", "name": "the target is a generated NPDev app",
         "status": "pass", "detail": "ok"},
        {"id": "app-healthy", "name": "the app answers /actuator/health",
         "status": "fail", "detail": "connection refused"},
        {"id": "engine-available", "name": "the ScrapForAI engine is available",
         "status": "pass", "detail": "ok"},
        {"id": "origin-allowlisted", "name": "the app origin will be allowlisted",
         "status": "pass", "detail": "ok"},
    ],
    "app": {}, "engine": {},
}


def fake_record(name: str, *, green: bool, status: str = "passed", reasons=None) -> dict:
    return {"runId": f"run-{name}", "status": status, "durationMs": 7,
            "verdict": {"green": green, "reasons": list(reasons or []),
                        "allowedConsoleErrorSubstrings": [], "excused": []}}


class SuiteRuns(unittest.TestCase):
    """R3.1 `explore suite`.

    Stubbed at the `run_exploration` seam ON PURPOSE. This machine has no ScrapForAI engine and no
    booted app, and what these tests protect is the LOOP -- which definitions, in what order, how a
    refusal is classified, what the exit code says -- not the engine round-trip, which the Phase B/D
    acceptance walks cover. Nothing green here is evidence that a browser ran anything.
    """

    def _app(self, tmp: str, names: list[str], *, definition_names: list[str] | None = None) -> Path:
        app = make_app(Path(tmp), plan={**DEFAULT_PLAN, "appDefinitionRoot": "../definition"})
        mirror = npdev_explore.mirror_dir(app)
        mirror.mkdir(parents=True, exist_ok=True)
        for name in names:
            (mirror / f"{name}.json").write_text(
                json.dumps({"scenarioName": name, "mirror": True,
                            "steps": [{"action": "goto", "url": "http://127.0.0.1:1/"}]}),
                encoding="utf-8")
        if definition_names:
            own = Path(tmp) / "definition" / "explorations"
            own.mkdir(parents=True, exist_ok=True)
            for name in definition_names:
                (own / f"{name}.json").write_text(
                    json.dumps({"scenarioName": name, "mirror": False,
                                "steps": [{"action": "goto", "url": "http://127.0.0.1:1/"}]}),
                    encoding="utf-8")
        return app

    def _suite(self, app: Path, run_side_effect, preflight_side_effect=GREEN_PREFLIGHT, **kwargs):
        calls: list[str] = []

        def _run(_root, _app_dir, routine_file, **_kw):
            calls.append(Path(routine_file).stem)
            outcome = run_side_effect(Path(routine_file).stem)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        preflights = (preflight_side_effect if isinstance(preflight_side_effect, list)
                      else None)
        state = {"n": 0}

        def _preflight(*_a, **_k):
            if preflights is None:
                return preflight_side_effect
            index = min(state["n"], len(preflights) - 1)
            state["n"] += 1
            return preflights[index]

        with mock.patch.object(npdev_explore, "run_exploration", _run), \
                mock.patch.object(npdev_explore, "preflight", _preflight):
            result = npdev_explore.run_suite(REPO_ROOT, app, **kwargs)
        return result, calls

    def test_the_suite_runs_what_the_listing_shows_in_the_order_it_shows_it(self):
        # One discovery loop for both, so "which routines does this app have?" cannot get two
        # answers. The mirror wins a filename collision; `z` exists only in the app definition.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["c", "a", "b"], definition_names=["a", "z"])
            listed = [d["name"] for d in npdev_explore.list_explorations(app)["definitions"]]
            self.assertEqual(listed, ["a", "b", "c", "z"])
            self.assertEqual([p.stem for p in npdev_explore.definition_files(app)], listed)
            result, calls = self._suite(app, lambda name: fake_record(name, green=True))
            self.assertEqual(calls, listed)
            self.assertEqual([e["name"] for e in result["runs"]], listed)

    def test_a_filename_in_both_places_resolves_to_the_mirror_copy(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a"], definition_names=["a"])
            files = npdev_explore.definition_files(app)
            self.assertEqual(len(files), 1)
            self.assertEqual(files[0].parent, npdev_explore.mirror_dir(app))

    def test_the_suite_reports_the_verdict_it_was_given_and_never_recomputes_one(self):
        # R10 is one verdict, full stop -- not one verdict function per command. Both rows below are
        # deliberately inconsistent with what a naive second implementation would infer from
        # `status`, so any recomputation inside the suite makes this test fail.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["greenish", "reddish"])

            def outcome(name):
                if name == "greenish":
                    return fake_record(name, green=True, status="failed")
                return fake_record(name, green=False, status="passed",
                                   reasons=["a reason only evaluate_verdict could have produced"])

            result, _ = self._suite(app, outcome)
            by_name = {e["name"]: e for e in result["runs"]}
            self.assertEqual(by_name["greenish"]["outcome"], "green")
            self.assertEqual(by_name["reddish"]["outcome"], "red")
            self.assertEqual(by_name["reddish"]["reasons"],
                             ["a reason only evaluate_verdict could have produced"])

    def test_all_green_is_green(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b"])
            result, _ = self._suite(app, lambda name: fake_record(name, green=True))
            self.assertTrue(result["green"])
            self.assertTrue(result["ok"])
            self.assertEqual(result["counts"], {"total": 2, "green": 2, "red": 0,
                                                "refused": 0, "skipped": 0})

    def test_one_red_routine_makes_the_suite_red(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b", "c"])
            result, calls = self._suite(
                app, lambda name: fake_record(name, green=name != "b"))
            self.assertEqual(calls, ["a", "b", "c"], "a red routine must not stop the suite by itself")
            self.assertFalse(result["green"])
            self.assertEqual(result["counts"]["red"], 1)

    def test_a_per_routine_refusal_is_recorded_and_the_loop_continues(self):
        # THE DECISION: one unrunnable routine must not cost the evidence for the other two.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b", "c"])

            def outcome(name):
                if name == "b":
                    return npdev_explore.ExploreError("the engine refused this routine (HTTP 400)")
                return fake_record(name, green=True)

            result, calls = self._suite(app, outcome)
            self.assertEqual(calls, ["a", "b", "c"])
            by_name = {e["name"]: e for e in result["runs"]}
            self.assertEqual(by_name["b"]["outcome"], "refused")
            self.assertIn("HTTP 400", by_name["b"]["reasons"][0])
            # D4: a tool problem is never counted as a red test result -- but it still costs green,
            # because you did not get the evidence you asked for.
            self.assertEqual(result["counts"]["red"], 0)
            self.assertEqual(result["counts"]["refused"], 1)
            self.assertEqual(result["counts"]["green"], 2)
            self.assertFalse(result["green"])

    def test_an_app_wide_refusal_aborts_and_names_what_it_skipped(self):
        # THE OTHER HALF: when the app dies at routine #2, routines #3.. would refuse identically,
        # and N copies of one diagnosis buries the diagnosis.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b", "c"])

            def outcome(name):
                if name == "b":
                    return npdev_explore.ExploreError("preflight failed: the app answers ...")
                return fake_record(name, green=True)

            # Exactly two preflights happen: once before the loop, then once more to classify `b`'s
            # refusal. The app is healthy at the first and gone at the second, which is the whole
            # point -- the classifier re-asks, so an app that dies at #2 aborts at #2.
            result, calls = self._suite(
                app, outcome, preflight_side_effect=[GREEN_PREFLIGHT, APP_DOWN_PREFLIGHT])
            self.assertEqual(calls, ["a", "b"], "c must not be attempted once the app is down")
            by_name = {e["name"]: e for e in result["runs"]}
            self.assertEqual(by_name["c"]["outcome"], "skipped")
            self.assertIn("connection refused", by_name["c"]["reasons"][0])
            self.assertIn("connection refused", result["aborted"])
            self.assertEqual(result["counts"], {"total": 3, "green": 1, "red": 0,
                                                "refused": 1, "skipped": 1})

    def test_stop_on_red_skips_the_rest_rather_than_shortening_the_list(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b", "c"])
            result, calls = self._suite(
                app, lambda name: fake_record(name, green=name == "a"), stop_on_red=True)
            self.assertEqual(calls, ["a", "b"])
            self.assertEqual([e["outcome"] for e in result["runs"]], ["green", "red", "skipped"])
            self.assertIn("--stop-on-red", result["stoppedEarly"])
            self.assertIsNone(result["aborted"])

    def test_only_selects_by_glob(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["login-admin", "login-user", "orders"])
            result, calls = self._suite(app, lambda name: fake_record(name, green=True),
                                        only=["login-*"])
            self.assertEqual(calls, ["login-admin", "login-user"])
            self.assertEqual(result["counts"]["total"], 2)

    def test_a_pattern_that_matches_nothing_is_a_refusal_not_an_empty_pass(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a"])
            with self.assertRaises(npdev_explore.ExploreError):
                self._suite(app, lambda name: fake_record(name, green=True), only=["nope-*"])

    def test_an_app_with_no_definitions_is_a_refusal_not_an_empty_pass(self):
        # A summary of zero runs reads like a pass, and that is the one thing it must never do.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, [])
            with self.assertRaises(npdev_explore.ExploreError) as caught:
                self._suite(app, lambda name: fake_record(name, green=True))
            self.assertIn("no routines to run", str(caught.exception))

    def test_a_held_lock_refuses_before_anything_runs(self):
        # The suite needs no lock of its own -- `run_exploration` takes the per-app one around each
        # run, so serial-within-an-app is true by construction. What it does need is to notice the
        # lock BEFORE producing N identical refusals.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b"])
            with npdev_explore.RunLock(app):
                with self.assertRaises(npdev_explore.ExploreError) as caught:
                    self._suite(app, lambda name: fake_record(name, green=True))
            self.assertIn("already running", str(caught.exception))

    def test_the_summary_is_not_persisted(self):
        # Deliberate: every run it names is already durable at <runId>/run.json plus its runs.jsonl
        # line. A stored second copy of facts derived from those is a thing that can later disagree
        # with them -- and it is why this feature adds no schema file.
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a"])
            self._suite(app, lambda name: fake_record(name, green=True))
            root = npdev_explore.runs_root(app)
            written = [p.name for p in root.glob("*")] if root.is_dir() else []
            self.assertEqual([p for p in written if "suite" in p.lower()], [])
            self.assertFalse(npdev_explore.runs_index(app).exists())


class SuiteCliContract(unittest.TestCase):
    """R3.1's stated definition of done: the exit code carries the roll-up. Note this DIFFERS from
    `explore run`, which exits 0 on a red run because its caller reads that one verdict -- a suite
    is used as a gate."""

    def _run_cli(self, app: Path, outcomes, **overrides) -> tuple[int, str]:
        args = argparse.Namespace(
            explore_command="suite", app_dir=str(app), only=[], stop_on_red=False,
            engine_port=9999, engine_root=None, api_key=None, driver="cli",
            var=[], credential=[], ledger_id=None, keep_engine=False, json=False)
        for key, value in overrides.items():
            setattr(args, key, value)

        def _run(_root, _app_dir, routine_file, **_kw):
            return outcomes(Path(routine_file).stem)

        buffer = io.StringIO()
        with mock.patch.object(npdev_explore, "run_exploration", _run), \
                mock.patch.object(npdev_explore, "preflight", lambda *a, **k: GREEN_PREFLIGHT), \
                redirect_stdout(buffer):
            code = npdev_cli.run_explore(args)
        return code, buffer.getvalue()

    def _app(self, tmp: str, names: list[str]) -> Path:
        app = make_app(Path(tmp), plan=DEFAULT_PLAN)
        mirror = npdev_explore.mirror_dir(app)
        mirror.mkdir(parents=True, exist_ok=True)
        for name in names:
            (mirror / f"{name}.json").write_text(json.dumps({"scenarioName": name, "steps": []}),
                                                 encoding="utf-8")
        return app

    def test_all_green_exits_zero(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b"])
            code, out = self._run_cli(app, lambda name: fake_record(name, green=True))
            self.assertEqual(code, 0)
            self.assertIn("GREEN", out)

    def test_any_red_exits_nonzero(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a", "b"])
            code, out = self._run_cli(app, lambda name: fake_record(name, green=name == "a"))
            self.assertEqual(code, 2)
            self.assertIn("RED", out)
            self.assertIn("[red    ] b", out)

    def test_the_json_output_carries_the_rollup(self):
        with TemporaryDirectory() as tmp:
            app = self._app(tmp, ["a"])
            code, out = self._run_cli(app, lambda name: fake_record(name, green=False), json=True)
            payload = json.loads(out[out.index("{\n"):])
            self.assertEqual(code, 2)
            self.assertEqual(payload["schemaVersion"], npdev_explore.SUITE_SCHEMA_VERSION)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["counts"]["red"], 1)


if __name__ == "__main__":
    unittest.main()
