"""require-digest.py (AIP-5, 5.1): a command that RUNS an expensive tool is blocked; one that only
NAMES it in an argument is not. Run: python scripts/hooks/test_require_digest.py"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

HOOK = Path(__file__).resolve().parent / "require-digest.py"
spec = importlib.util.spec_from_file_location("require_digest", HOOK)
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)

POLICY_FILE = HOOK.parents[1] / "policy" / "output-digest-policy.json"
PATTERNS = json.loads(POLICY_FILE.read_text(encoding="utf-8"))["enforcement"]["expensiveCommands"]
WARN_PATTERNS = json.loads(POLICY_FILE.read_text(encoding="utf-8"))["enforcement"]["warnCommands"]


def blocked(command: str) -> str | None:
    return hook.first_expensive(command, PATTERNS)


class RunsTheTool(unittest.TestCase):
    def test_direct_and_launched_runs_are_blocked(self):
        for command, pattern in [
            ("./gradlew build", "gradlew"),
            ("NPDevKernel/gradlew -p NPDevKernel :kernel:test", "gradlew"),
            ("cd x && ./gradlew test", "gradlew"),
            ("JAVA_HOME=/opt/jdk NPDevKernel/gradlew test", "gradlew"),
            ("env FOO=1 sudo ./gradlew test", "gradlew"),
            ("pwsh -NoProfile -File scripts/quality/run-all-gates.ps1", "run-all-gates"),
            ("pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/appgen/Build-NpdevApp.ps1 -App x", "Build-NpdevApp"),
            ('pwsh -NoProfile -Command "& ./scripts/quality/run-fast-gate.ps1"', "run-fast-gate"),
            ("& .\\scripts\\appgen\\Rebuild-And-Restage.ps1", "Rebuild-And-Restage"),
            ('bash -c "cd NPDevKernel && ./gradlew test"', "gradlew"),
            ("cmd /c gradlew.bat test", "gradlew"),
            ("python3 -m pytest NPDevCli", "pytest"),
            ("npm test", "npm test"),
            ("timeout 600 ./gradlew test", "gradlew"),
        ]:
            with self.subTest(command=command):
                self.assertEqual(blocked(command), pattern)


class OnlyNamesTheTool(unittest.TestCase):
    def test_a_mention_in_an_argument_passes(self):
        for command in [
            "grep -n Build-NpdevApp scripts/appgen/README.txt",
            'grep -rn "run-ai-knowledge-gate" scripts/quality',
            "grep -n x scripts/quality/run-ai-knowledge-gate.ps1",
            "ls /tmp/digest-logs/20261023-gradlew--p-NPDevKernel.log",
            "git log --oneline -- scripts/quality/run-all-gates.ps1",
            "cat NPDevKernel/gradlew | head",
            "git commit -q -F - <<'EOF'\nfeat: ./gradlew now runs faster\nEOF",
            'pwsh -NoProfile -File scripts/ai/Close-Session.ps1 -NextStep "run ./gradlew test"',
            "npm install",
            "python3 scripts/quality/check-schema-mirror-consistency.py",
        ]:
            with self.subTest(command=command):
                self.assertIsNone(blocked(command))


class HookProcess(unittest.TestCase):
    """The hook end to end: exit 2 blocks, 0 lets through, and it fails open on garbage."""

    def run_hook(self, stdin: str) -> int:
        return subprocess.run([sys.executable, str(HOOK)], input=stdin, text=True, capture_output=True).returncode

    def test_exit_codes(self):
        call = lambda c: json.dumps({"tool_name": "Bash", "tool_input": {"command": c}})
        self.assertEqual(self.run_hook(call("./gradlew test")), 2)
        self.assertEqual(self.run_hook(call("python scripts/ai/run_digest.py -- ./gradlew test")), 0)
        self.assertEqual(self.run_hook(call("grep gradlew notes.txt")), 0)
        self.assertEqual(self.run_hook("not json"), 0)


class WarnOnlyCommands(unittest.TestCase):
    """warnCommands: long-running but not worth blocking. The command runs (exit 0) and the agent is told."""

    BENCH = "python scripts/ai/bench/run_bench.py run --run-id x"

    def run_hook(self, command: str, **env) -> subprocess.CompletedProcess:
        import os
        full = {k: v for k, v in os.environ.items() if k not in ("NPDEV_DIGEST_WARN", "NPDEV_DIGEST_BYPASS")}
        full.update(env)
        return subprocess.run([sys.executable, str(HOOK)], capture_output=True, text=True, env=full,
                              input=json.dumps({"tool_name": "Bash", "tool_input": {"command": command}}))

    def test_a_long_command_runs_but_the_agent_is_warned(self):
        proc = self.run_hook(self.BENCH)
        self.assertEqual(proc.returncode, 0)
        said = json.loads(proc.stdout)
        self.assertIn("run_bench.py", said["systemMessage"])
        self.assertEqual(said["hookSpecificOutput"]["hookEventName"], "PreToolUse")
        self.assertIn("run_digest.py", said["hookSpecificOutput"]["additionalContext"])
        self.assertNotIn("permissionDecision", said["hookSpecificOutput"], "a warning must not decide permission")

    def test_the_declared_warn_commands_are_the_long_ones(self):
        for command, pattern in [(self.BENCH, "run_bench.py"), ("npm run test:smoke", "npm run test:smoke"),
                                 ("npm run test", "npm run test"), ("cd x && npm run test:smoke > /tmp/o 2>&1", "npm run test:smoke")]:
            with self.subTest(command=command):
                self.assertEqual(hook.first_expensive(command, WARN_PATTERNS), pattern)

    def test_wrapped_or_merely_named_commands_are_not_warned(self):
        self.assertEqual(self.run_hook("python scripts/ai/run_digest.py -- " + self.BENCH).stdout, "")
        self.assertEqual(self.run_hook("grep run_bench.py notes.txt").stdout, "")
        self.assertEqual(self.run_hook("npm run typecheck").stdout, "")

    def test_the_mode_is_parametrizable_per_shell(self):
        self.assertEqual(self.run_hook(self.BENCH, NPDEV_DIGEST_WARN="off").stdout, "")
        self.assertEqual(self.run_hook(self.BENCH, NPDEV_DIGEST_WARN="off").returncode, 0)
        promoted = self.run_hook(self.BENCH, NPDEV_DIGEST_WARN="block")
        self.assertEqual(promoted.returncode, 2)
        self.assertIn("BLOCKED", promoted.stderr)
        self.assertEqual(self.run_hook(self.BENCH, NPDEV_DIGEST_WARN="nonsense").returncode, 0)  # bad value = warn
        self.assertEqual(self.run_hook(self.BENCH, NPDEV_DIGEST_BYPASS="1").stdout, "")

    def test_a_blocked_command_stays_blocked_whatever_the_warn_mode(self):
        self.assertEqual(self.run_hook("./gradlew test", NPDEV_DIGEST_WARN="off").returncode, 2)

    def test_shipped_policy_defaults_to_warn(self):
        self.assertEqual(hook.warn_mode({"warnMode": "warn"}), "warn")
        self.assertEqual(json.loads(POLICY_FILE.read_text(encoding="utf-8"))["enforcement"]["warnMode"], "warn")


class LongRunsOnlyFromTheToolPtrackWatches(unittest.TestCase):
    """enforcement.tracker (2026-11-24): ptrack hooks only the Bash tool, so a long run started with the PowerShell
    tool is invisible to the owner. The rule is pure (env + `installed` injected) so it is tested without ptrack."""

    ENFORCE = json.loads(POLICY_FILE.read_text(encoding="utf-8"))["enforcement"]
    AGENT = {"CLAUDECODE": "1"}
    # The exact launch that went wrong: the one-hour beta gate, digest-wrapped, started from PowerShell.
    THE_MISTAKE = "python scripts/ai/run_digest.py -- pwsh -NoProfile -File scripts/quality/run-beta-release-gate.ps1 -GenerateReports"

    def refusal(self, command, tool="PowerShell", env=None, installed=True, **tool_input):
        return hook.tracker_refusal(tool, {"command": command, **tool_input}, command, self.ENFORCE,
                                    self.AGENT if env is None else env, installed)

    def test_the_mistake_itself_is_blocked_and_the_message_says_what_to_do(self):
        said = self.refusal(self.THE_MISTAKE)
        self.assertIsNotNone(said)
        self.assertIn("Bash tool", said)
        self.assertIn("run_in_background", said)
        self.assertIn("ptrack list", said)

    def test_every_background_powershell_call_is_blocked_whatever_it_runs(self):
        self.assertIsNotNone(self.refusal("Start-Sleep 600", run_in_background=True))

    def test_a_declared_expensive_or_warn_program_is_blocked_in_powershell(self):
        for command in ["pwsh -NoProfile -File scripts/quality/run-fast-gate.ps1 -Tier T1",
                        "./gradlew test", "python scripts/ai/bench/run_bench.py run --run-id x"]:
            with self.subTest(command=command):
                self.assertIsNotNone(self.refusal(command))

    def test_the_same_commands_from_the_bash_tool_are_not_this_rules_business(self):
        self.assertIsNone(self.refusal(self.THE_MISTAKE, tool="Bash"))
        self.assertIsNone(self.refusal("sleep 600", tool="Bash", run_in_background=True))

    def test_short_powershell_commands_still_run(self):
        for command in ["git status --short", "Get-Date", "Get-Content notes.txt -Tail 5",
                        "python scripts/quality/check-twin-pair-consistency.py"]:
            with self.subTest(command=command):
                self.assertIsNone(self.refusal(command))

    def test_quick_forms_of_a_long_command_are_exempt(self):
        for command in ["pwsh -NoProfile -File scripts/quality/run-beta-release-gate.ps1 -DryRun",
                        "pwsh -NoProfile -File scripts/quality/run-all-gates.ps1 -ListOnly",
                        "python scripts/ai/run_digest.py --help"]:
            with self.subTest(command=command):
                self.assertIsNone(self.refusal(command))

    def test_it_only_binds_agent_sessions_that_have_a_tracker_and_no_bypass(self):
        self.assertIsNone(self.refusal(self.THE_MISTAKE, env={}), "a human at a terminal or CI is not an agent session")
        self.assertIsNone(self.refusal(self.THE_MISTAKE, installed=False), "no ptrack on the machine = nothing to hide from")
        self.assertIsNone(self.refusal(self.THE_MISTAKE, env={"CLAUDECODE": "1", "NPDEV_TRACKER_BYPASS": "1"}))

    def test_the_shipped_policy_declares_the_rule(self):
        tracker = self.ENFORCE["tracker"]
        self.assertEqual(tracker["blockedTool"], "PowerShell")
        self.assertEqual(tracker["launchTool"], "Bash")
        self.assertIn("run_digest.py", tracker["longRunCommands"])

    def test_end_to_end_exit_2_with_a_ptrack_on_the_path_and_0_with_the_bypass(self):
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            for fake in ("ptrack", "ptrack.exe"):
                path = Path(td) / fake
                path.write_text("", encoding="utf-8")
                path.chmod(0o755)
            base = {k: v for k, v in os.environ.items() if k not in ("NPDEV_DIGEST_BYPASS", "NPDEV_TRACKER_BYPASS", "PTRACK_JOB_ID")}
            env = dict(base, CLAUDECODE="1", PATH=td + os.pathsep + base.get("PATH", ""))
            stdin = json.dumps({"tool_name": "PowerShell", "tool_input": {"command": self.THE_MISTAKE, "run_in_background": True}})
            blocked_run = subprocess.run([sys.executable, str(HOOK)], input=stdin, text=True, capture_output=True, env=env)
            self.assertEqual(blocked_run.returncode, 2, blocked_run.stderr)
            self.assertIn("Bash tool", blocked_run.stderr)
            bypassed = subprocess.run([sys.executable, str(HOOK)], input=stdin, text=True, capture_output=True,
                                      env=dict(env, NPDEV_TRACKER_BYPASS="1"))
            self.assertEqual(bypassed.returncode, 0)


if __name__ == "__main__":
    unittest.main()
