#!/usr/bin/env python3
r"""Move 13 P6: makes the X0 discipline a permanent gate instead of something that only runs when
someone remembers to re-audit.

WHY THIS EXISTS
---------------
"An input an evaluator cannot handle is an ERROR, never a default answer" (the X0 rule) has eight
confirmed members in scripts/quality/x0-evaluator-registry.json (its own `why` field carries the
full history). Seven were found by a deliberate audit; the eighth (REG-108: roles/propertyScopes/
properties silently dropped during pack composition) was found BY ACCIDENT, while building on top of
it for Move 13. A register that only gets read is not a control -- this script is the control.

md-zero-2026-08-11 PLAN.md Phase 1 (the pilot for the whole plan): this script used to cross-check
the registry against a parallel markdown table, docs/X0_SILENT_EXPRESSION_REGISTER.md. That doc is
deleted -- its narrative moved into the registry's `why`/`note`/`detail` fields (see `supersedes`),
and the checker below no longer reads any `.md` file. The doc<->registry parity check goes with it
(there is nothing left to disagree with); the two checks that were never about the doc in the first
place stay: a FIXED/CLEAN entry's proof still exists, and no evaluator-shaped class ships unaudited.

WHAT IT CHECKS (two things)
----------------------------
1. **A FIXED/CLEAN entry's proof still exists.** Its `test` file must exist and must still contain
   `testMarker` (a method name, not just a substring in the class under test) -- proving the specific
   regression test that pins the "errors instead of silently defaulting" behavior has not been deleted
   or renamed out from under the registry. A FIXED entry with no committed test at all must instead
   carry a `testExemptReason` naming the gap explicitly (see X0-2) -- this is a visible, named
   exception the next reader has to see, not a silent pass.
2. **New evaluator-shaped classes are not silently unaudited.** `evaluatorShapedGlobs` names the
   directories/patterns this project's own evaluators have come from so far. Any file matching one of
   those globs that no registry entry's `class` field already claims is a class this audit has never
   looked at -- the exact "new evaluator/resolver class has no test asserting it errors" case the
   Move 13 spec asks this gate to catch.

WHAT IT DELIBERATELY DOES NOT DO
---------------------------------
It does not require a "must error" test for OPEN/ACCEPTED/NOT_AUDITED/NOT_BUILT entries -- forcing a
test that proves behavior the code does not have yet would just be a second, uglier way to lie. Those
verdicts stay visible in the registry (so they cannot quietly vanish); fixing them is tracked work
(REG-96, LC-P0, the runtime-support audit), not something this gate can or should force.

USAGE
-----
    python check-x0-evaluator-coverage.py
    python check-x0-evaluator-coverage.py --calibrate

Exit codes: 0 = clean, 1 = at least one problem, 2 = the registry itself could not be read.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGISTRY_JSON = REPO_ROOT / "scripts" / "quality" / "x0-evaluator-registry.json"

VERDICTS_REQUIRING_TEST = {"FIXED", "CLEAN"}


def _rel(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def check(registry: dict, repo_root: Path) -> list[str]:
    problems: list[str] = []
    entries = {entry["id"]: entry for entry in registry.get("entries", [])}

    claimed_files: set[Path] = set()
    for entry_id, entry in entries.items():
        verdict = entry.get("verdict")
        class_path = entry.get("class")
        if class_path:
            resolved = (repo_root / class_path).resolve()
            if not resolved.exists():
                problems.append(f"{entry_id}: declared class '{class_path}' does not exist on disk "
                                 f"-- the registry is stale")
            else:
                claimed_files.add(resolved)

        if verdict in VERDICTS_REQUIRING_TEST:
            test_path_str = entry.get("test")
            if not test_path_str:
                exempt_reason = entry.get("testExemptReason")
                if not exempt_reason:
                    problems.append(f"{entry_id}: verdict is {verdict} but no 'test' is declared -- a "
                                     f"FIXED/CLEAN finding must name the test that proves it errors "
                                     f"instead of silently defaulting, or state a 'testExemptReason' "
                                     f"naming the gap explicitly")
                # else: an explicit, named exemption -- visible in the registry, not a silent pass.
                continue
            test_path = (repo_root / test_path_str).resolve()
            if not test_path.is_file():
                problems.append(f"{entry_id}: declared test '{test_path_str}' does not exist -- the "
                                 f"proof this was fixed has been deleted or moved")
                continue
            marker = entry.get("testMarker")
            if not marker:
                problems.append(f"{entry_id}: verdict is {verdict} but no 'testMarker' is declared -- "
                                 f"cannot verify the test still asserts the specific behavior")
                continue
            source = test_path.read_text(encoding="utf-8")
            if marker not in source:
                problems.append(f"{entry_id}: '{marker}' not found in {test_path_str} -- the regression "
                                 f"test that proves this errors (rather than silently defaulting) has "
                                 f"been renamed, weakened, or removed")

    for glob_pattern in registry.get("evaluatorShapedGlobs", []):
        for match in repo_root.glob(glob_pattern):
            if not match.is_file():
                continue
            resolved = match.resolve()
            if resolved in claimed_files:
                continue
            # A file under a directory-shaped `class` entry (e.g. an adapter package cited as a
            # whole) is claimed by that entry even though it is not the literal `class` path.
            claimed = False
            for entry in entries.values():
                class_path = entry.get("class")
                if not class_path:
                    continue
                base = (repo_root / class_path).resolve()
                if base.is_dir() and base in resolved.parents:
                    claimed = True
                    break
            if not claimed:
                problems.append(f"new evaluator-shaped class with no X0 registry entry: {_rel(resolved)} "
                                 f"(matched glob '{glob_pattern}') -- answer this register's one question "
                                 f"for it (what does it do with input it cannot handle?) and add an entry "
                                 f"before this ships")

    return problems


def run() -> int:
    if not REGISTRY_JSON.is_file():
        print(f"FAIL: {_rel(REGISTRY_JSON)} not found.", file=sys.stderr)
        return 2
    try:
        registry = json.loads(REGISTRY_JSON.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        print(f"FAIL: {_rel(REGISTRY_JSON)} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    problems = check(registry, REPO_ROOT)

    entry_count = len(registry.get("entries", []))
    print(f"X0 evaluator coverage ({entry_count} registry entries, {_rel(REGISTRY_JSON)})")
    if problems:
        print("\nFAIL: a proof regressed or a new evaluator shipped unaudited:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("OK: every FIXED/CLEAN proof test still carries its marker, and no unregistered "
          "evaluator-shaped class was found.")
    return 0


def calibrate() -> int:
    """Required-controls discipline: each control must fire for the reason it claims to, and the
    real registry must stay silent."""
    import shutil
    import tempfile

    real_registry = json.loads(REGISTRY_JSON.read_text(encoding="utf-8-sig"))
    ok = True

    def report(label: str, registry: dict, repo_root: Path, expect_fail: bool) -> None:
        nonlocal ok
        problems = check(registry, repo_root)
        fired = bool(problems)
        passed = fired == expect_fail
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for problem in problems[:2]:
            print(f"           {problem}")

    print("Calibration -- must catch a stale class, a weakened proof, and an unaudited class, and "
          "stay silent on the real state:")
    report("the real registry against the real tree", real_registry, REPO_ROOT, expect_fail=False)

    registry_stale_class = json.loads(json.dumps(real_registry))
    for entry in registry_stale_class["entries"]:
        if entry["id"] == "X0-4":
            entry["class"] = "NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/DoesNotExist.java"
    report("a registry entry's class no longer exists on disk (X0-4)", registry_stale_class, REPO_ROOT,
           expect_fail=True)

    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        shutil.copytree(REPO_ROOT / "NPDevKernel", tmp_root / "NPDevKernel")
        shutil.copytree(REPO_ROOT / "NPDevContract", tmp_root / "NPDevContract")
        weakened_test = tmp_root / "NPDevKernel/kernel/src/test/java/com/npdev/kernel/auth/RolePermissionsTest.java"
        weakened_test.write_text(
            weakened_test.read_text(encoding="utf-8").replace("undeclaredRoleIsDeniedNotThrown", "renamedAway"),
            encoding="utf-8",
        )
        report("a FIXED entry's testMarker is renamed away (X0-5)", real_registry, tmp_root,
               expect_fail=True)

        new_evaluator = tmp_root / "NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/BrandNewEvaluator.java"
        new_evaluator.write_text("package com.npdev.kernel.procedures;\nclass BrandNewEvaluator {}\n", encoding="utf-8")
        report("a brand-new evaluator-shaped class with no registry entry", real_registry, tmp_root,
               expect_fail=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--calibrate", action="store_true")
    args = parser.parse_args(argv[1:])
    if args.calibrate:
        return calibrate()
    return run()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
