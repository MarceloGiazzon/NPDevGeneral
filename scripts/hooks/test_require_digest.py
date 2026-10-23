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

PATTERNS = json.loads((HOOK.parents[1] / "policy" / "output-digest-policy.json").read_text(encoding="utf-8"))[
    "enforcement"]["expensiveCommands"]


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


if __name__ == "__main__":
    unittest.main()
