#!/usr/bin/env python3
r"""O5 (Move 11 W4): does every step type declared in model.schema.json have a MODEL-LEVEL
validation test -- one that goes through SemanticValidator, not one that hand-builds a step object?

WHY THIS EXISTS
---------------
Three bugs shipped green because the test started downstream of the layer holding the bug:

    REG-71   used a noop semantic policy
    REG-83   the test gateway lacked the governed policy
    REG-89   kernel tests build a ProcedureStep DIRECTLY, never through SemanticValidator

REG-89 is the one this check is shaped by. `patchConcept`'s `createIfMissing` shipped in Move 5,
was "fixed" by REG-83, was re-specced in Move 9 -- and for two moves it could not be declared in ANY
model, because PackValidation still demanded an `id`. Its kernel tests passed the entire time: they
construct a ProcedureStep and hand it to the executor, so the validator that forbade the declaration
was never in the picture. The runtime worked. The front door was locked. Nobody tried the door.

WHAT IT CHECKS (three things, deliberately)
-------------------------------------------
For each step kind (`procedureStep`, `flowStep`) declared in model.schema.json:

  1. its model-level conformance test EXISTS -- deleting the test is the cheapest way to make this
     kind of coverage evaporate, so absence is a failure, not a skip;
  2. that test goes through the real validator (`new SemanticValidator()`) and the real parser
     (`JsonModelParser`) -- a test that stops short of the validator is REG-89's exact defect and
     must not count as coverage;
  3. every enum value has a `"type": "<value>"` example in that test's own fixture.

It does NOT re-run the tests -- Gradle does that. This is the structural half: it answers "is a
model-level test even there to run?", which is what nobody was asking.

    python check-step-type-test-coverage.py
    python check-step-type-test-coverage.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
TEST_ROOT = REPO_ROOT / "NPDevContract" / "dsl" / "src" / "test" / "java" / "com" / "npdev" / "dsl" / "v1" / "validation"

# stepKind in model.schema.json -> the model-level conformance test that must cover it.
CONFORMANCE_TESTS = {
    "procedureStep": TEST_ROOT / "ProcedureStepTypeConformanceTest.java",
    "flowStep": TEST_ROOT / "FlowStepTypeConformanceTest.java",
}

# The markers that make a test MODEL-level rather than object-level. Both are required: a test can
# parse a model and never validate it, or validate a hand-built AST and never parse one.
MODEL_LEVEL_MARKERS = ("new SemanticValidator()", "JsonModelParser")


def _display(path: Path) -> str:
    """Repo-relative when it is inside the repo, absolute otherwise (calibration uses a temp dir)."""
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def schema_enum(schema: dict, step_kind: str) -> list[str]:
    defs = schema.get("$defs") or schema.get("definitions") or {}
    node = defs.get(step_kind, {}).get("properties", {}).get("type", {})
    return list(node.get("enum") or [])


def declared_types(test_source: str) -> set[str]:
    """Every `"type": "x"` naming a step type in the test's own fixture."""
    return set(re.findall(r'"type"\s*:\s*"([A-Za-z_][A-Za-z0-9_]*)"', test_source))


def check_kind(step_kind: str, enum_values: list[str], test_path: Path) -> list[str]:
    problems: list[str] = []
    rel = _display(test_path)
    if not test_path.is_file():
        return [f"{step_kind}: no model-level conformance test at {rel} "
                f"-- every {step_kind}.type is untested at the model layer (REG-89's shape)"]

    source = test_path.read_text(encoding="utf-8")
    for marker in MODEL_LEVEL_MARKERS:
        if marker not in source:
            problems.append(f"{step_kind}: {rel} does not use {marker} -- it is not a MODEL-level test, so it "
                            f"cannot see a validator rule that forbids a legal declaration (REG-89)")

    covered = declared_types(source)
    missing = [value for value in enum_values if value not in covered]
    for value in missing:
        problems.append(f"{step_kind}.type '{value}' has no example in {rel} -- add one, or it ships with no "
                        f"model-level validation test at all")
    return problems


def run(schema_path: Path) -> int:
    schema = json.loads(schema_path.read_text(encoding="utf-8-sig"))
    problems: list[str] = []
    print(f"Step-type model-level test coverage ({_display(schema_path)})")
    for step_kind, test_path in CONFORMANCE_TESTS.items():
        enum_values = schema_enum(schema, step_kind)
        if not enum_values:
            problems.append(f"{step_kind}: no type enum found in the schema -- this check has gone stale")
            continue
        kind_problems = check_kind(step_kind, enum_values, test_path)
        status = "OK" if not kind_problems else f"{len(kind_problems)} problem(s)"
        print(f"  {step_kind:<15} {len(enum_values):>3} declared type(s)  -> {status}")
        problems.extend(kind_problems)

    if problems:
        print("\nFAIL: a step type has no model-level validation test:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("\nOK: every declared step type has a model-level validation test that goes through the real validator.")
    return 0


def calibrate() -> int:
    """Three controls. Two must fire, one must stay silent -- the same required-controls discipline
    the other checkers in this directory use."""
    import tempfile

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8-sig"))
    real_enum = schema_enum(schema, "procedureStep")
    real_source = CONFORMANCE_TESTS["procedureStep"].read_text(encoding="utf-8")
    ok = True

    def report(label: str, source: str | None, expect_fail: bool) -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "ProcedureStepTypeConformanceTest.java"
            if source is not None:
                path.write_text(source, encoding="utf-8")
            problems = check_kind("procedureStep", real_enum, path)
        fired = bool(problems)
        passed = fired == expect_fail
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for problem in problems[:3]:
            print(f"           {problem}")

    print("Calibration -- must catch a missing example and a test that never reaches the validator:")
    report("the real conformance test vs. the real schema enum", real_source, expect_fail=False)
    # Drop one enum value's example. patchConcept is the right one to drop: it IS REG-89.
    report("an enum value with no example in the fixture (patchConcept removed)",
           real_source.replace('"type": "patchConcept"', '"type": "conceptUpdate"'), expect_fail=True)
    report("a fixture-complete test that never calls SemanticValidator (REG-89's own defect)",
           real_source.replace("new SemanticValidator()", "new NoopValidatorStandIn()"), expect_fail=True)
    report("no conformance test on disk at all", None, expect_fail=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--schema", default=str(SCHEMA_PATH))
    parser.add_argument("--calibrate", action="store_true")
    args = parser.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()
    return run(Path(args.schema))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
