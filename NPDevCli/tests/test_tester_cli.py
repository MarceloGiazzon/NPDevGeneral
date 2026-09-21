"""REG-234 (EXT-9) -- tests for `npdev tester` and its in-container driver.

Two things are exercised without spending money or touching Docker/the network:
  1. npdev_cli.run_tester()'s --dry-run path (argument assembly only).
  2. independent_tester.tester_agent's tool implementations and brief assembly.

Neither test requires NPDEV_TESTER_ANTHROPIC_API_KEY or Docker. The `anthropic` package is a
dev/CI-only dependency for this module (see scripts/requirements.txt) -- if it isn't installed,
the tests that need it are skipped rather than failing the whole suite.
"""
from __future__ import annotations

import io
import json
import sys
import unittest
from argparse import Namespace
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "independent_tester"))

import npdev_cli  # noqa: E402
from independent_tester import tester_agent  # noqa: E402

try:
    import anthropic  # noqa: F401
    _HAS_ANTHROPIC = True
except ImportError:
    _HAS_ANTHROPIC = False


class CollectGradleDistributionUrlsTest(unittest.TestCase):
    def test_dedupes_and_unescapes(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a").mkdir()
            (root / "b").mkdir()
            (root / "a" / "gradle-wrapper.properties").write_text(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip\n",
                encoding="utf-8",
            )
            (root / "b" / "gradle-wrapper.properties").write_text(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip\n",
                encoding="utf-8",
            )
            urls = npdev_cli._collect_gradle_distribution_urls(root)
        self.assertEqual(urls, ["https://services.gradle.org/distributions/gradle-9.5.1-bin.zip"])

    def test_empty_when_no_wrapper_files(self):
        with TemporaryDirectory() as tmp:
            self.assertEqual(npdev_cli._collect_gradle_distribution_urls(Path(tmp)), [])


class RunTesterDryRunTest(unittest.TestCase):
    def _namespace(self, **overrides):
        base = dict(task="all", ref="main", timeout_minutes=60, dry_run=True, json=True)
        base.update(overrides)
        return Namespace(**base)

    def test_dry_run_never_touches_docker_and_reports_missing_key(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            exit_code = npdev_cli.run_tester(self._namespace())
        self.assertEqual(exit_code, 0)
        result = json.loads(buf.getvalue())
        self.assertTrue(result["dryRun"])
        self.assertEqual(result["buildCommand"][0:2], ["docker", "build"])
        self.assertEqual(result["runCommand"][0:2], ["docker", "run"])
        # The key is forwarded by NAME only ("-e VAR", no "=value") -- never present as a value.
        self.assertIn("NPDEV_TESTER_ANTHROPIC_API_KEY", result["runCommand"])
        self.assertNotIn("NPDEV_TESTER_ANTHROPIC_API_KEY=", " ".join(result["runCommand"]))

    def test_task_and_ref_flow_into_run_command(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            npdev_cli.run_tester(self._namespace(task="B", ref="my-branch"))
        result = json.loads(buf.getvalue())
        self.assertIn("NPDEV_TESTER_TASK=B", result["runCommand"])
        self.assertIn("NPDEV_TESTER_REF=my-branch", result["runCommand"])


class ResolveScopedPathTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self._work_dir = Path(self._tmp.name) / "work"
        self._work_dir.mkdir()
        self._orig_work_dir = tester_agent.WORK_DIR
        tester_agent.WORK_DIR = self._work_dir

    def tearDown(self):
        tester_agent.WORK_DIR = self._orig_work_dir
        self._tmp.cleanup()

    def test_relative_path_resolves_under_work_dir(self):
        resolved = tester_agent._resolve_scoped_path("output/notes.txt")
        self.assertEqual(resolved, (self._work_dir / "output" / "notes.txt").resolve())

    def test_escaping_path_is_refused(self):
        with self.assertRaises(ValueError):
            tester_agent._resolve_scoped_path("../../etc/passwd")


class ToolImplTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self._work_dir = Path(self._tmp.name) / "work"
        self._work_dir.mkdir()
        self._orig_work_dir = tester_agent.WORK_DIR
        tester_agent.WORK_DIR = self._work_dir

    def tearDown(self):
        tester_agent.WORK_DIR = self._orig_work_dir
        self._tmp.cleanup()

    def test_write_then_read_round_trips(self):
        write_result = tester_agent._write_file_impl("notes.md", "hello world")
        self.assertIn("wrote", write_result)
        self.assertEqual(tester_agent._read_file_impl("notes.md"), "hello world")

    def test_read_missing_file_reports_error_not_exception(self):
        result = tester_agent._read_file_impl("nope.txt")
        self.assertIn("error", result)

    def test_run_shell_reports_exit_code_and_output(self):
        payload = json.loads(tester_agent._run_shell_impl("echo hi", cwd="."))
        self.assertEqual(payload["exit_code"], 0)
        self.assertIn("hi", payload["stdout"])

    def test_run_shell_refuses_cwd_outside_work(self):
        payload = json.loads(tester_agent._run_shell_impl("echo hi", cwd="../../outside"))
        self.assertIn("error", payload)


class BuildUserMessageTest(unittest.TestCase):
    def setUp(self):
        self.brief = tester_agent._load_brief()

    def test_all_tasks_present_by_default(self):
        message = tester_agent.build_user_message(self.brief, "all")
        for task_id in ("REG-13", "REG-14", "REG-17"):
            self.assertIn(task_id, message)

    def test_single_task_scopes_to_one(self):
        message = tester_agent.build_user_message(self.brief, "B")
        self.assertIn("REG-14", message)
        self.assertNotIn("REG-13", message)
        self.assertNotIn("REG-17", message)

    def test_brief_never_mentions_claude_code_or_this_repo_root(self):
        message = tester_agent.build_user_message(self.brief, "all")
        for forbidden in ("Claude Code", "CLAUDE.md", str(Path(__file__).resolve().parents[2])):
            self.assertNotIn(forbidden, message)

    def test_ref_override_reaches_the_clone_instruction(self):
        default_message = tester_agent.build_user_message(self.brief, "A")
        self.assertIn("--branch main", default_message)
        overridden = tester_agent.build_user_message(self.brief, "A", repo_ref="my-feature-branch")
        self.assertIn("--branch my-feature-branch", overridden)
        self.assertNotIn("--branch main", overridden)


@unittest.skipUnless(_HAS_ANTHROPIC, "anthropic package not installed (dev/CI-only dependency)")
class MakeToolsShapeTest(unittest.TestCase):
    def test_returns_three_named_tools(self):
        tools = tester_agent.make_tools()
        self.assertEqual(len(tools), 3)


if __name__ == "__main__":
    unittest.main()
