#!/usr/bin/env python3
"""QUAL-23: is every kernel adapter module's test suite reachable from SOME CI workflow?

WHY THIS EXISTS
----------------
Both `.github/workflows/npdev-ci-validation.yml` and `npdev-pr-gate.yml` enumerate adapter test
targets as a hand-maintained allowlist (`:adapters:<name>:test`, one line each). Nothing reconciled
that list against `NPDevKernel/settings.gradle`, and no blanket `./gradlew test` covers the kernel
root -- the two `./gradlew check` invocations run in `NPDevContract/dsl` and in an assembled sample
app, neither of which reaches `NPDevKernel/adapters`.

Measured 2026-08-19 (ledger/items/QUAL-23.yml): diffing `settings.gradle`'s 41 `include
'adapters:*'` entries against both workflow files found 21 modules in neither -- including
`runtime-support` (holds `CelInvariantEngine`/`GeneratedCrudRuntimeSupport`, core runtime) and the two
adapters carrying RUN-4's hanging-socket deadline/retry proofs (`external-ai-http`, `mail-smtp`). A
whole adapter's test suite could be green on a laptop and never once run in CI, with nothing anywhere
reporting the gap.

This is the same failure shape `AdapterRegistrationConsistencyTest`
(`NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/`) already guards for
PACKAGING: it fails when a `settings.gradle` adapter is missing from the proof-test jar lists, via a
reviewed `KNOWN_NOT_PACKAGED` set with a recorded reason for each deliberate exclusion. This script is
the same shape for CI TEST coverage: `settings.gradle` is the source of truth for "which adapters
exist", both workflow files are scanned for `:adapters:<name>:test` tokens, and anything left over
must be named in the `EXCLUDED` table below with a real reason -- never silently dropped.

WHAT THIS DOES NOT CLAIM
--------------------------
Text-level token scan, not a Gradle build-graph evaluation (same accepted limitation as
`check-test-task-coverage.py`, which this script mirrors closely). It proves a module's `test` task
is NAMED in a workflow; it does not run Gradle itself and cannot prove the step actually executes
successfully. It does not know whether an included adapter has any `@Test` methods at all.

CALIBRATE BEFORE TRUSTING IT
------------------------------
    python scripts/quality/check-ci-adapter-coverage.py --calibrate

Builds a synthetic settings.gradle + workflow pair with three modules (one covered, one excluded,
one covered by neither and not excluded) and proves the gate fires on exactly the uncovered one, plus
a second control proving a stale EXCLUDED entry (naming a module no longer in settings.gradle) is
itself flagged.

USAGE
-----
    python scripts/quality/check-ci-adapter-coverage.py              # the gate: exit 1 on any violation
    python scripts/quality/check-ci-adapter-coverage.py --calibrate  # self-test, exit 1 on failure
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

# REG-144: identify the repo root by its CONTENTS, never a parent-count or a directory name -- a
# checkout named "NPDevGeneral" (GitHub's default) or one nested differently must resolve the same.
def _repo_root() -> Path:
    here = Path(__file__).resolve()
    for candidate in here.parents:
        if all((candidate / module).is_dir()
               for module in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents")


REPO_ROOT = _repo_root()
SETTINGS_GRADLE_PATH = REPO_ROOT / "NPDevKernel" / "settings.gradle"
WORKFLOW_PATHS = (
    REPO_ROOT / ".github" / "workflows" / "npdev-ci-validation.yml",
    REPO_ROOT / ".github" / "workflows" / "npdev-pr-gate.yml",
)

ADAPTER_INCLUDE_RE = re.compile(r"include\s+'adapters:([A-Za-z0-9_-]+)'")
ADAPTER_TEST_TOKEN_RE = re.compile(r":adapters:([A-Za-z0-9_-]+):test\b")

# Reviewed, deliberate exclusions -- same shape/discipline as AdapterRegistrationConsistencyTest's
# KNOWN_NOT_PACKAGED. A module belongs here only when there is a real reason it is not, and should
# not be, named in any workflow's `:adapters:<name>:test` list. Every other module found in
# settings.gradle but in neither workflow is a gate failure, not a candidate for silent addition here.
EXCLUDED: dict[str, str] = {
    "postgres-test-support": (
        "shared Testcontainers test-support module consumed by the *-postgres adapter tests that "
        "ARE in CI (persistence-postgres, idempotency-postgres, bulkhead-postgres, "
        "eventstore-postgres, audit-postgres, circuit-postgres, flowinstance-postgres, "
        "tracestore-postgres) -- not itself an adapter shipped in a generated app. Its own small "
        "unit suite (PostgresTestSupportLinuxCompatibilityTest, Docker-host-string resolution only, "
        "no real Postgres/Docker needed) is exercised transitively every time those adapters build, "
        "since Gradle compiles+tests this project's dependencies first. QUAL-23, 2026-08-19."
    ),
}


class CoverageResult:
    def __init__(self, modules: list[str], covered: set[str], excluded: dict[str, str]):
        self.modules = modules
        self.covered = covered
        self.excluded = excluded
        module_set = set(modules)
        self.uncovered = [
            m for m in modules if m not in covered and m not in excluded
        ]
        self.stale_exclusions = sorted(set(excluded) - module_set)


def parse_settings_gradle(text: str) -> list[str]:
    """Adapter module names, in declaration order (order doesn't matter for evaluation, but a
    stable, readable report benefits from it)."""
    return [m.group(1) for m in ADAPTER_INCLUDE_RE.finditer(text)]


def parse_workflow_coverage(texts: list[str]) -> set[str]:
    covered: set[str] = set()
    for text in texts:
        covered.update(ADAPTER_TEST_TOKEN_RE.findall(text))
    return covered


def evaluate(modules: list[str], covered: set[str], excluded: dict[str, str]) -> CoverageResult:
    return CoverageResult(modules, covered, excluded)


def _run(settings_text: str, workflow_texts: list[str], excluded: dict[str, str]) -> CoverageResult:
    modules = parse_settings_gradle(settings_text)
    covered = parse_workflow_coverage(workflow_texts)
    return evaluate(modules, covered, excluded)


def _print_report(result: CoverageResult) -> None:
    width = max((len(m) for m in result.modules), default=0)
    for m in result.modules:
        if m in result.covered:
            print(f"  [OK]        {m.ljust(width)}  (named as :adapters:{m}:test in a workflow)")
        elif m in result.excluded:
            print(f"  [EXCLUDED]  {m.ljust(width)}  {result.excluded[m]}")
        else:
            print(f"  [VIOLATION] {m.ljust(width)}  not named in any workflow, and not excluded")


def calibrate() -> int:
    ok = True

    def report(label: str, fired: bool, expect_fire: bool) -> None:
        nonlocal ok
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

    print("Calibration -- must catch an uncovered, non-excluded module; stay silent on a covered or")
    print("excluded one; and separately catch a stale EXCLUDED entry:")

    settings_text = (
        "include 'adapters:covered-adapter'\n"
        "include 'adapters:excluded-adapter'\n"
        "include 'adapters:uncovered-adapter'\n"
    )
    workflow_texts = [
        "jobs:\n  x:\n    steps:\n      - run: |\n"
        "          ./gradlew :adapters:covered-adapter:test --no-daemon\n"
    ]
    excluded = {"excluded-adapter": "synthetic control -- deliberately excluded"}

    result = _run(settings_text, workflow_texts, excluded)
    report(
        "covered-adapter judged covered (named in workflow text)",
        fired=("covered-adapter" in result.uncovered), expect_fire=False,
    )
    report(
        "excluded-adapter judged excluded, not a violation",
        fired=("excluded-adapter" in result.uncovered), expect_fire=False,
    )
    report(
        "uncovered-adapter judged a VIOLATION (in settings.gradle, in neither workflow, not excluded)",
        fired=("uncovered-adapter" in result.uncovered), expect_fire=True,
    )

    # Second control: an EXCLUDED entry naming a module that no longer exists in settings.gradle
    # (e.g. the module was renamed/removed and the exclusion was never cleaned up) must itself be
    # flagged -- mirrors AdapterRegistrationConsistencyTest's own KNOWN_NOT_PACKAGED staleness check.
    stale_excluded = {"a-module-that-does-not-exist": "stale reason"}
    stale_result = _run(settings_text, workflow_texts, stale_excluded)
    report(
        "a stale EXCLUDED entry (names a module absent from settings.gradle) is itself caught",
        fired=bool(stale_result.stale_exclusions), expect_fire=True,
    )

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    if not SETTINGS_GRADLE_PATH.is_file():
        print(f"ERROR: {SETTINGS_GRADLE_PATH} not found.", file=sys.stderr)
        return 2
    missing_workflows = [p for p in WORKFLOW_PATHS if not p.is_file()]
    if missing_workflows:
        for p in missing_workflows:
            print(f"ERROR: {p} not found.", file=sys.stderr)
        return 2

    settings_text = SETTINGS_GRADLE_PATH.read_text(encoding="utf-8")
    workflow_texts = [p.read_text(encoding="utf-8") for p in WORKFLOW_PATHS]
    result = _run(settings_text, workflow_texts, EXCLUDED)

    print(
        f"CI adapter coverage: {len(result.modules)} adapter module(s) in "
        f"{SETTINGS_GRADLE_PATH.relative_to(REPO_ROOT).as_posix()}, "
        f"{len(result.covered & set(result.modules))} covered, "
        f"{len(result.excluded)} excluded, {len(result.uncovered)} uncovered.\n"
    )
    _print_report(result)

    failed = False
    if result.uncovered:
        failed = True
        print(f"\nFAIL: {len(result.uncovered)} adapter module(s) run their tests in NO CI workflow "
              f"and are not excluded:", file=sys.stderr)
        for m in result.uncovered:
            print(f"  - {m}", file=sys.stderr)
        print("\nFix by adding \":adapters:<name>:test\" to a step in "
              "npdev-ci-validation.yml or npdev-pr-gate.yml, or record a reviewed exclusion with a "
              "reason in this script's EXCLUDED table.", file=sys.stderr)

    if result.stale_exclusions:
        failed = True
        print(f"\nFAIL: {len(result.stale_exclusions)} EXCLUDED entry/entries name a module no longer "
              f"in {SETTINGS_GRADLE_PATH.relative_to(REPO_ROOT).as_posix()}:", file=sys.stderr)
        for m in result.stale_exclusions:
            print(f"  - {m}", file=sys.stderr)
        print("\nRemove the stale entry from this script's EXCLUDED table.", file=sys.stderr)

    if failed:
        return 1

    print("\nOK: every kernel adapter module either runs its tests in a CI workflow, or is "
          "deliberately excluded with a recorded reason.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
