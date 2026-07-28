#!/usr/bin/env python3
"""Test-task coverage: is every custom Gradle `Test` task actually invoked by some CI workflow?

WHY THIS EXISTS -- T1.2/T1.3 (docs/TREE1_LAUNCH_UNBLOCK_PLAN.md)
------------------------------------------------------------------
Found 2026-07-27: `NPDevGenerator/generator/build.gradle` declares a `behaviorTest` source set and
a `tasks.register('behaviorTest', Test)`, correctly wired as `check.dependsOn behaviorTest`. But both
`.github/workflows/npdev-pr-gate.yml` and `npdev-ci-validation.yml` invoked `gradlew :generator:test`
directly, never `check` and never `:generator:behaviorTest` by name -- so
`ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest` (written specifically because REG-49's withdrawal
note conceded "it stops short of an automated JUnit runtime assertion") ran on one laptop and nowhere
in CI. A test that only runs locally is a manual trace with extra steps.

T1.2 fixed that one instance. This script makes the CLASS of bug impossible to ship silently: it
finds every custom `Test`-type Gradle task in the repo (the plugin-default `test` task is exempt --
every module's default `test` is already invoked directly or via `check` in every workflow that
touches that module) and fails if a task is reachable from **neither** a literal `gradlew <task>`
invocation naming it, nor a `gradlew check` invocation scoped to the directory that declares it (when
that task is `check`-wired).

WHAT "REACHABLE" MEANS HERE (and its deliberate limits)
---------------------------------------------------------
This is a text-level check, not a build-graph evaluation -- cheap and static, per the task's own
brief. Two consequences worth naming rather than hiding:

  1. A qualified task name (e.g. `:generator:behaviorTest`) is treated as reachable if that exact
     string appears as a `gradlew` argument ANYWHERE across the workflow files, without requiring the
     invoking step's `working-directory` to match. This is deliberate: Gradle project paths like
     `:generator:behaviorTest` are already qualified and effectively unique repo-wide, so a plain
     string match is sound without needing full YAML-aware working-directory attribution.
  2. `check`-expansion (a task reachable because some workflow step ran bare `check`, and this task
     is `check`-wired) DOES require the step's `working-directory` to match the directory that
     declares the task -- `check` alone is not globally qualified, so this half needs the directory
     context to avoid false negatives/positives.

  Known accepted gap: `NPDevRuntimeHost/build.gradle`'s `integrationTest` is a single-project
  (unqualified) task name, so it is judged reachable if the bare word `integrationTest` appears
  anywhere in the workflows -- today that happens only against a *generated sample app copy*
  (`NPDevSamples/.../Output/App`, itself a copy of this same build.gradle), not the
  `NPDevRuntimeHost/` path directly. That is still a real invocation of the same task definition, so
  it is treated as covered without an allowlist entry. If this ever stops being true (the sample-app
  invocation is removed, or a second same-named task is added to a different module), the allowlist
  mechanism below (`test-task-coverage-allowlist.json`, same fingerprint-keyed shape as
  `security-pattern-sweep-allowlist.json`) is where a deliberately-nightly-only or deliberately-manual
  task gets a documented, reviewable exemption instead of a silent pass.

CALIBRATE BEFORE TRUSTING IT
------------------------------
    python scripts/quality/check-test-task-coverage.py --calibrate

Runs the real historical RED->GREEN pair this gate was built to catch: `:generator:behaviorTest`
judged UNREACHABLE against the pre-T1.2 workflow text (commit f76b95f, the last commit before the fix)
and REACHABLE against the current committed workflow text. Exits 1 if either half doesn't behave.

USAGE
-----
    python scripts/quality/check-test-task-coverage.py            # the gate: exit 1 on any violation
    python scripts/quality/check-test-task-coverage.py --calibrate  # self-test, exit 1 on failure
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

MODULE_ROOTS = ("NPDevContract", "NPDevKernel", "NPDevGenerator", "NPDevRuntimeHost")

EXCLUDED_DIR_PARTS = {".git", ".gradle", "build", "node_modules", "npdev-generated"}

TASK_REGISTER_RE = re.compile(r"tasks\.register\(\s*['\"](\w+)['\"]\s*,\s*Test\s*\)")

# gradlew argument tokens: gradle task refs/`check`, not CLI flags (-x, --no-daemon, -Dfoo=bar).
TASK_TOKEN_RE = re.compile(r"^[:\w][\w:.\-]*$")

ALLOWLIST_PATH = Path(__file__).resolve().parent / "test-task-coverage-allowlist.json"


class CustomTestTask:
    def __init__(self, file: Path, repo_root: Path, name: str, line: int, wired_to_check: bool):
        self.file = file
        self.rel_file = file.relative_to(repo_root)
        self.line = line
        self.name = name
        self.wired_to_check = wired_to_check
        rel = self.rel_file
        self.module_root = rel.parts[0]
        self.task_dir = rel.parent.as_posix()  # directory that declares the task, repo-relative
        rel_from_module = rel.parent.relative_to(self.module_root).as_posix()
        prefix = "" if rel_from_module in ("", ".") else ":" + rel_from_module.replace("/", ":")
        self.qualified_name = f"{prefix}:{name}" if prefix else name

    def fingerprint(self) -> str:
        return hashlib.sha256(f"{self.qualified_name}|{self.task_dir}".encode()).hexdigest()[:12]

    def where(self) -> str:
        return f"{self.rel_file.as_posix()}:{self.line}"


def find_custom_test_tasks(root: Path) -> list[CustomTestTask]:
    tasks: list[CustomTestTask] = []
    for module in MODULE_ROOTS:
        module_root = root / module
        if not module_root.is_dir():
            continue
        for build_file in sorted(module_root.rglob("build.gradle")):
            if any(part in EXCLUDED_DIR_PARTS for part in build_file.relative_to(root).parts):
                continue
            text = build_file.read_text(encoding="utf-8", errors="replace")
            lines = text.splitlines()
            for match in TASK_REGISTER_RE.finditer(text):
                name = match.group(1)
                line_no = text.count("\n", 0, match.start()) + 1
                wired = _is_check_wired(text, name)
                tasks.append(CustomTestTask(build_file, root, name, line_no, wired))
    return tasks


def _is_check_wired(build_gradle_text: str, task_name: str) -> bool:
    """Is `task_name` named in a dependsOn inside a tasks.named('check') { ... } block?"""
    for block_match in re.finditer(
        r"tasks\.named\(\s*['\"]check['\"]\s*\)\s*\{(.*?)\n\}", build_gradle_text, re.DOTALL
    ):
        block = block_match.group(1)
        if re.search(rf"dependsOn\s+tasks\.named\(\s*['\"]{re.escape(task_name)}['\"]\s*\)", block):
            return True
    # Also accept the flatter `check.dependsOn taskName` / `check { dependsOn taskName }` shapes,
    # not seen in this repo today but cheap to accept so the gate doesn't need updating for style drift.
    if re.search(rf"check\s*\.\s*dependsOn\b.*\b{re.escape(task_name)}\b", build_gradle_text):
        return True
    return False


def scan_workflows(workflow_dir: Path) -> tuple[set[str], list[tuple[str, str]]]:
    """Returns (invoked_task_tokens, [(workflow_file, working_dir), ...] for each bare `check` call)."""
    invoked: set[str] = set()
    bare_check: list[tuple[str, str]] = []
    if not workflow_dir.is_dir():
        return invoked, bare_check

    for yml in sorted(workflow_dir.glob("*.yml")):
        text = yml.read_text(encoding="utf-8", errors="replace")
        _scan_one_workflow(yml.name, text, invoked, bare_check)
    return invoked, bare_check


def _scan_one_workflow(
    filename: str, text: str, invoked: set[str], bare_check: list[tuple[str, str]]
) -> None:
    current_dir = "."
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if re.match(r"^\s*-\s+name:", line):
            current_dir = "."  # new step; working-directory (if any) is set below before `run:`
        dir_match = re.match(r"^\s*working-directory:\s*(.+?)\s*$", line)
        if dir_match:
            current_dir = dir_match.group(1).strip("'\"")
        if "gradlew" in line:
            command_lines = [line]
            while command_lines[-1].rstrip().endswith("\\") and i + 1 < len(lines):
                i += 1
                command_lines.append(lines[i])
            command_text = " ".join(command_lines)
            after = command_text.split("gradlew", 1)[1]
            for token in after.split():
                token = token.strip("\\")
                if not token or token.startswith("-"):
                    continue
                if not TASK_TOKEN_RE.match(token):
                    continue
                invoked.add(token)
                if token == "check":
                    bare_check.append((filename, _normalize_dir(current_dir)))
        i += 1


def _normalize_dir(d: str) -> str:
    d = d.replace("\\", "/").strip("/")
    return d if d else "."


def load_allowlist() -> dict:
    if not ALLOWLIST_PATH.is_file():
        return {}
    data = json.loads(ALLOWLIST_PATH.read_text(encoding="utf-8"))
    return data.get("cleared", {})


def evaluate(
    tasks: list[CustomTestTask],
    invoked: set[str],
    bare_check: list[tuple[str, str]],
    allowlist: dict,
) -> tuple[list[str], list[str]]:
    """Returns (report_lines, violation_lines)."""
    report: list[str] = []
    violations: list[str] = []
    for t in tasks:
        if t.qualified_name in invoked:
            report.append(f"  [OK]        {t.qualified_name}  (invoked directly -- {t.where()})")
            continue
        covered_via_check = t.wired_to_check and any(
            d == t.task_dir or d.startswith(t.task_dir + "/") for _, d in bare_check
        )
        if covered_via_check:
            report.append(
                f"  [OK]        {t.qualified_name}  (check-wired, `check` runs in {t.task_dir} -- {t.where()})"
            )
            continue
        fp = t.fingerprint()
        if fp in allowlist:
            why = allowlist[fp].get("why", "(no reason recorded)")
            report.append(f"  [ALLOWED]   {t.qualified_name}  ({fp}: {why})")
            continue
        msg = (
            f"{t.qualified_name} ({t.where()}) is not invoked by name in any workflow, and is "
            f"{'check-wired but no workflow runs `check` in ' + t.task_dir if t.wired_to_check else 'not check-wired'}."
        )
        report.append(f"  [VIOLATION] {msg}")
        violations.append(msg)
    return report, violations


def _run(root: Path) -> tuple[list[str], list[str]]:
    tasks = find_custom_test_tasks(root)
    invoked, bare_check = scan_workflows(root / ".github" / "workflows")
    allowlist = load_allowlist()
    return evaluate(tasks, invoked, bare_check, allowlist)


def calibrate(root: Path) -> int:
    ok = True

    def report(label: str, fired: bool, expect_fire: bool) -> None:
        nonlocal ok
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

    print("Calibration -- the real 2026-07-27 REG-49/T1.2 instance, before and after the fix:")

    pre_fix_commit = "f76b95f"  # last commit before T1.2 wired :generator:behaviorTest into CI
    old_workflow_text = {}
    for name in ("npdev-pr-gate.yml", "npdev-ci-validation.yml"):
        rel = f".github/workflows/{name}"
        try:
            old_workflow_text[name] = subprocess.run(
                ["git", "show", f"{pre_fix_commit}:{rel}"],
                cwd=root, capture_output=True, text=True, check=True,
            ).stdout
        except subprocess.CalledProcessError as exc:
            print(f"  ERROR: could not read {pre_fix_commit}:{rel}: {exc.stderr}", file=sys.stderr)
            return 1

    tasks = find_custom_test_tasks(root)
    behavior_task = next(
        (t for t in tasks if t.qualified_name == ":generator:behaviorTest"), None
    )
    if behavior_task is None:
        print("  ERROR: :generator:behaviorTest not found by find_custom_test_tasks() -- "
              "has generator/build.gradle changed shape?", file=sys.stderr)
        return 1

    old_invoked: set[str] = set()
    old_bare_check: list[tuple[str, str]] = []
    for name, text in old_workflow_text.items():
        _scan_one_workflow(name, text, old_invoked, old_bare_check)
    _, old_violations = evaluate([behavior_task], old_invoked, old_bare_check, {})
    report(
        f"{pre_fix_commit}: :generator:behaviorTest judged UNREACHABLE (pre-T1.2 workflow text, real git revision)",
        fired=bool(old_violations), expect_fire=True,
    )

    new_invoked, new_bare_check = scan_workflows(root / ".github" / "workflows")
    _, new_violations = evaluate([behavior_task], new_invoked, new_bare_check, {})
    report(
        ":generator:behaviorTest judged REACHABLE (current committed workflow text)",
        fired=bool(new_violations), expect_fire=False,
    )

    if not ok:
        print("\nFAIL: calibration did not reproduce the known RED->GREEN pair -- this gate does "
              "not ship until it does.", file=sys.stderr)
        return 1
    print("\nOK: both controls behave correctly. Safe to run as a blocking gate.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    parser.add_argument("--calibrate", action="store_true", help="run the known RED->GREEN control and exit")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    if args.calibrate:
        return calibrate(root)

    if not (root / ".git").exists():
        print(f"ERROR: {root} does not look like the repo root (no .git).", file=sys.stderr)
        return 2

    report, violations = _run(root)
    print("Test-task coverage (every custom Gradle Test task must be reachable from a CI workflow):")
    for line in report:
        print(line)

    if violations:
        print(f"\nFAIL: {len(violations)} custom Test task(s) unreachable from CI.", file=sys.stderr)
        print("Fix by naming the task explicitly in a workflow, wiring it to `check` and running "
              "`check` for its directory, or recording a reviewed exemption in "
              f"{ALLOWLIST_PATH.relative_to(root).as_posix()} (see its _comment for the convention).",
              file=sys.stderr)
        return 1

    print("\nOK: every custom Test task is reachable from at least one workflow.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
