"""REG-324: `npdev monitor ops --json` is a JSON-LINES stream, and its last line is the run's verdict.

The Manager reads this command's stdout one line at a time (`run_ops_script_streaming` / `run_ops_script_capture`) and
takes `exitCode` and `logFile` from the last line that parses as a JSON value. The result used to be printed with
`indent=2`: an object that spans many lines parses on none of them, so the Manager saw only the `{"kind": "line"}`
lines before it and defaulted to `exitCode: 0` and `logFile: null` -- every failed build looked like a success, and
"Ask the AI to fix this", the Monitor's run summary and the AI loop's `classify_build` (RUN-35 only worked around it
there) were all blind to it.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli  # noqa: E402


class _FakeProcess:
    """What `subprocess.Popen` hands back for the ops script: its output lines, then an exit code."""

    def __init__(self, lines, code):
        self.stdout = iter(line + "\n" for line in lines)
        self._code = code

    def wait(self):
        return self._code


class MonitorOpsStreamTests(unittest.TestCase):
    def run_ops(self, code, lines, script="build-finalapp", as_json=True):
        with tempfile.TemporaryDirectory() as tmp:
            app = Path(tmp) / "app"
            (app / "_ops").mkdir(parents=True)
            (app / "_ops" / npdev_cli.npdev_monitor.OPS_SCRIPTS[script]).write_text("", encoding="utf-8")
            args = argparse.Namespace(app_dir=str(app), script=script, confirm=None, json=as_json)
            out = io.StringIO()
            with mock.patch.object(npdev_cli.npdev_monitor, "find_powershell", return_value="pwsh"), \
                    mock.patch.object(npdev_cli.subprocess, "Popen", return_value=_FakeProcess(lines, code)), \
                    contextlib.redirect_stdout(out):
                rc = npdev_cli._run_monitor_ops(args)
            logs = sorted(p.name for p in (app / "logs").glob("ops-*.log"))
            written = (app / "logs" / logs[-1]).read_text(encoding="utf-8") if logs else ""
            return rc, out.getvalue(), logs, written

    def test_every_stdout_line_is_one_json_value_and_the_last_is_the_verdict_of_a_failed_build(self):
        lines = ["> Task :compileJava FAILED", "BUILD FAILED in 25s", "Build finished in 25,8 s (exit 1)"]
        rc, stdout, logs, written = self.run_ops(1, lines)
        self.assertEqual(rc, 2)
        parsed = []
        for number, line in enumerate(stdout.splitlines(), 1):
            try:
                parsed.append(json.loads(line))
            except ValueError:
                self.fail(f"stdout line {number} is not a JSON value on its own (a multi-line object?): {line!r}")
        self.assertEqual([p.get("text") for p in parsed[:-1]], lines)
        self.assertTrue(all(p.get("kind") == "line" for p in parsed[:-1]))
        verdict = parsed[-1]
        self.assertEqual(verdict["exitCode"], 1)
        self.assertIs(verdict["ok"], False)
        self.assertEqual(len(logs), 1)
        self.assertTrue(logs[0].startswith("ops-build-finalapp-") and logs[0].endswith(".log"), logs)
        self.assertEqual(Path(verdict["logFile"]).name, logs[0])
        self.assertIn("BUILD FAILED in 25s", written)  # the log the verdict names holds the run's own output
        # The Manager forwards this line as an event and the Monitor's run box appends anything carrying `output`
        # (monitor.js): the output was streamed line by line already, so the verdict must not repeat it.
        self.assertNotIn("output", verdict)

    def test_a_clean_run_ends_on_exit_code_zero_and_the_same_log(self):
        rc, stdout, logs, _written = self.run_ops(0, ["Build finished in 9,1 s (exit 0)"])
        self.assertEqual(rc, 0)
        verdict = json.loads(stdout.splitlines()[-1])
        self.assertEqual((verdict["exitCode"], verdict["ok"]), (0, True))
        self.assertEqual(Path(verdict["logFile"]).name, logs[0])

    def test_without_json_the_verdict_is_still_one_plain_sentence(self):
        rc, stdout, logs, _written = self.run_ops(3, ["boom"], as_json=False)
        self.assertEqual(rc, 2)
        self.assertEqual(stdout.splitlines()[0], "boom")
        self.assertTrue(stdout.splitlines()[-1].startswith("(exit 3; captured to "), stdout)


if __name__ == "__main__":
    unittest.main()
