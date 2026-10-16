"""Tests for Wave 6's Studio Apply routing (`_studio_route_for`, `_studio_classify_and_route`) and
Wave 2.2's `npdev widgets` (`run_widgets`).

Both shell out for real work (the ai-tools.jar classifier, WidgetCatalogueMain), so the
subprocess seams (`_classifier_command`/`_run_bounded`, `subprocess.run`) are mocked -- these tests
prove the ROUTING and FAILURE-SHAPE logic around those calls: which diffs are allowed to go live,
and that every "could not run" branch surfaces as a clear CliError / exit 1 instead of a crash.
Run with:
    python -m unittest NPDevCli.tests.test_studio_route_and_widgets -v
"""

from __future__ import annotations

import argparse
import io
import json
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class StudioRouteForTest(unittest.TestCase):
    def test_metadata_only_always_goes_live(self):
        self.assertEqual("model-reload", npdev_cli._studio_route_for({"classification": "METADATA_ONLY"}))

    def test_additive_diff_of_only_new_tables_goes_live(self):
        report = {"classification": "SAFE_ADDITIVE",
                  "items": [{"kind": "ADD_TABLE"}, {"kind": "ADD_TABLE"}]}
        self.assertEqual("model-reload", npdev_cli._studio_route_for(report))

    def test_mixed_additive_diff_needs_a_rebuild(self):
        # A new concept plus a new column on an EXISTING one: /model-reload would swap the whole
        # model and expose a field whose column was never added.
        report = {"classification": "SAFE_ADDITIVE",
                  "items": [{"kind": "ADD_TABLE"}, {"kind": "ADD_COLUMN"}]}
        self.assertEqual("needs-rebuild", npdev_cli._studio_route_for(report))

    def test_additive_with_no_items_needs_a_rebuild(self):
        self.assertEqual("needs-rebuild",
                         npdev_cli._studio_route_for({"classification": "SAFE_ADDITIVE", "items": []}))

    def test_anything_else_needs_a_rebuild(self):
        self.assertEqual("needs-rebuild", npdev_cli._studio_route_for({"classification": "BREAKING"}))
        self.assertEqual("needs-rebuild", npdev_cli._studio_route_for({}))


def _fake_classifier_command(root, cli_args):
    return (["fake-classifier", *cli_args], root)


def _writes_report(content: str):
    """A `_run_bounded` stand-in that writes `content` to the command's --out path, the way the
    real classifier writes its report before any METADATA_ONLY refusal fires."""
    def _run(command, cwd, deadline, **kwargs):
        out = Path(command[command.index("--out") + 1])
        out.write_text(content, encoding="utf-8")
        return subprocess.CompletedProcess(command, 1)
    return _run


class StudioClassifyAndRouteTest(unittest.TestCase):
    def _classify(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            return npdev_cli._studio_classify_and_route(
                root, root / "baseline.json", root / "candidate.json", time.monotonic() + 60, root / "emit")

    def test_metadata_only_report_is_marked_emitted(self):
        report = {"classification": "METADATA_ONLY", "items": []}
        with patch("npdev_cli._classifier_command", side_effect=_fake_classifier_command), \
             patch("npdev_cli._run_bounded", side_effect=_writes_report(json.dumps(report))) as run:
            result = self._classify()
        self.assertTrue(result["emitted"])
        self.assertEqual("METADATA_ONLY", result["classification"])
        command = run.call_args.args[0]
        self.assertIn("--emitMetadataTo", command)

    def test_schema_shaped_report_is_returned_despite_nonzero_exit(self):
        report = {"classification": "SAFE_ADDITIVE", "items": [{"kind": "ADD_TABLE"}]}
        with patch("npdev_cli._classifier_command", side_effect=_fake_classifier_command), \
             patch("npdev_cli._run_bounded", side_effect=_writes_report(json.dumps(report))):
            result = self._classify()
        self.assertFalse(result["emitted"])
        self.assertEqual([{"kind": "ADD_TABLE"}], result["items"])

    def test_no_classifier_available_is_a_clear_error(self):
        with patch("npdev_cli._classifier_command", return_value=None):
            with self.assertRaisesRegex(npdev_cli.CliError, "no staged npdev-ai-tools.jar"):
                self._classify()

    def test_deadline_exceeded_is_a_clear_error(self):
        with patch("npdev_cli._classifier_command", side_effect=_fake_classifier_command), \
             patch("npdev_cli._run_bounded", side_effect=npdev_cli._DeadlineExceeded()):
            with self.assertRaisesRegex(npdev_cli.CliError, "--timeout budget"):
                self._classify()

    def test_missing_report_is_a_clear_error(self):
        with patch("npdev_cli._classifier_command", side_effect=_fake_classifier_command), \
             patch("npdev_cli._run_bounded", return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaisesRegex(npdev_cli.CliError, "did not produce a report"):
                self._classify()

    def test_unparseable_report_is_a_clear_error(self):
        with patch("npdev_cli._classifier_command", side_effect=_fake_classifier_command), \
             patch("npdev_cli._run_bounded", side_effect=_writes_report("{not json")):
            with self.assertRaisesRegex(npdev_cli.CliError, "not valid JSON"):
                self._classify()


class RunWidgetsTest(unittest.TestCase):
    def _run(self, **kwargs):
        args = argparse.Namespace(json=kwargs.pop("json", False), type=kwargs.pop("type", None))
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = npdev_cli.run_widgets(args)
        return code, out.getvalue(), err.getvalue()

    def test_unstaged_libs_reports_unavailable_as_text(self):
        with patch("npdev_cli._default_runtimehost_libs_dir", return_value=None):
            code, out, _ = self._run()
        self.assertEqual(1, code)
        self.assertIn("runtimehost-libs not staged", out)

    def test_unstaged_libs_reports_unavailable_as_json(self):
        with patch("npdev_cli._default_runtimehost_libs_dir", return_value=None):
            code, out, _ = self._run(json=True)
        self.assertEqual(1, code)
        payload = json.loads(out)
        self.assertEqual("widgets", payload["command"])
        self.assertFalse(payload["ok"])

    def test_no_java_runtime_reports_unavailable(self):
        with tempfile.TemporaryDirectory() as libs, \
             patch("npdev_cli._default_runtimehost_libs_dir", return_value=Path(libs)), \
             patch.dict("os.environ", {"JAVA_HOME": ""}), \
             patch("npdev_cli.shutil.which", return_value=None):
            code, out, _ = self._run()
        self.assertEqual(1, code)
        self.assertIn("no Java runtime found", out)

    def test_passes_type_and_json_through_to_the_catalogue_main(self):
        completed = subprocess.CompletedProcess([], 0, stdout='{"widgets": []}\n', stderr="")
        with tempfile.TemporaryDirectory() as libs, \
             patch("npdev_cli._default_runtimehost_libs_dir", return_value=Path(libs)), \
             patch.dict("os.environ", {"JAVA_HOME": ""}), \
             patch("npdev_cli.shutil.which", return_value="/usr/bin/java"), \
             patch("npdev_cli.subprocess.run", return_value=completed) as run:
            code, out, _ = self._run(json=True, type="date")
        self.assertEqual(0, code)
        self.assertEqual('{"widgets": []}\n', out)
        java_args = run.call_args.args[0]
        self.assertIn("com.npdev.dsl.v1.cli.WidgetCatalogueMain", java_args)
        self.assertEqual(["--type", "date", "--json"], java_args[-3:])

    def test_catalogue_failure_forwards_stderr_and_exits_1(self):
        completed = subprocess.CompletedProcess([], 2, stdout="", stderr="unknown type\n")
        with tempfile.TemporaryDirectory() as libs, \
             patch("npdev_cli._default_runtimehost_libs_dir", return_value=Path(libs)), \
             patch.dict("os.environ", {"JAVA_HOME": ""}), \
             patch("npdev_cli.shutil.which", return_value="/usr/bin/java"), \
             patch("npdev_cli.subprocess.run", return_value=completed):
            code, _, err = self._run(type="nope")
        self.assertEqual(1, code)
        self.assertIn("unknown type", err)


class LiveApplyTest(unittest.TestCase):
    """ENG-5: `_live_apply` is the router both `npdev monitor studio-apply` and `npdev dev` call."""

    def _run(self, report, reload_result=None):
        calls = {}

        def fake_hotswap(*a, **k):
            calls["hotswap"] = a
            return {"ok": True, "code": "APPLIED"}

        with patch.object(npdev_cli, "repo_root", return_value=Path(".")), \
                patch.object(npdev_cli, "_studio_classify_and_route", return_value=report), \
                patch.object(npdev_cli, "_model_reload_apply",
                             return_value=reload_result or {"ok": True, "code": "RELOADED"}) as reload, \
                patch.object(npdev_cli, "_metadata_hotswap_apply", side_effect=fake_hotswap):
            live = npdev_cli._live_apply("http://x", "key", Path("b.json"), Path("m.json"),
                                         Path("app"), time.monotonic() + 5, 5.0)
        return live, reload, calls

    def test_metadata_only_reloads_the_model_and_refreshes_catalogs(self):
        live, reload, calls = self._run({"classification": "METADATA_ONLY", "emitted": True})
        self.assertEqual("APPLIED_LIVE", live["code"])
        self.assertTrue(live["ok"])
        self.assertEqual("model-reload", live["route"])
        reload.assert_called_once()
        self.assertIn("hotswap", calls)
        self.assertEqual("APPLIED", live["metadataHotswap"]["code"])

    def test_new_concept_reloads_without_the_catalog_refresh(self):
        live, reload, calls = self._run(
            {"classification": "SAFE_ADDITIVE", "items": [{"kind": "ADD_TABLE"}]})
        self.assertEqual("APPLIED_LIVE", live["code"])
        reload.assert_called_once()
        self.assertNotIn("hotswap", calls)

    def test_structural_change_needs_a_rebuild_and_never_calls_the_app(self):
        live, reload, _ = self._run({"classification": "SAFE_ADDITIVE",
                                      "items": [{"kind": "ADD_COLUMN"}]})
        self.assertTrue(live["ok"])
        self.assertEqual("NEEDS_REBUILD", live["code"])
        reload.assert_not_called()

    def test_eng3_preflight_refusal_becomes_needs_rebuild(self):
        live, _, _ = self._run(
            {"classification": "METADATA_ONLY"},
            {"ok": False, "code": "NEW_CONCEPT_PROVISIONING_DISABLED", "message": "flag off"})
        self.assertTrue(live["ok"])
        self.assertEqual("NEEDS_REBUILD", live["code"])
        self.assertIn("flag off", live["message"])

    def test_other_reload_failure_is_a_failure(self):
        live, _, _ = self._run({"classification": "METADATA_ONLY"},
                               {"ok": False, "code": "UNREACHABLE", "message": "down"})
        self.assertFalse(live["ok"])
        self.assertEqual("UNREACHABLE", live["code"])

    def test_classifier_failure_is_classify_failed(self):
        with patch.object(npdev_cli, "repo_root", return_value=Path(".")), \
                patch.object(npdev_cli, "_studio_classify_and_route",
                             side_effect=npdev_cli.CliError("no jar")):
            live = npdev_cli._live_apply("http://x", "key", Path("b.json"), Path("m.json"),
                                         Path("app"), time.monotonic() + 5, 5.0)
        self.assertFalse(live["ok"])
        self.assertEqual("CLASSIFY_FAILED", live["code"])


if __name__ == "__main__":
    unittest.main()
