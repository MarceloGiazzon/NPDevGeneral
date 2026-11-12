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


if __name__ == "__main__":
    unittest.main()
