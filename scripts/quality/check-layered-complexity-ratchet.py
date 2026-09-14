#!/usr/bin/env python3
r"""Backs the Constitution's 'layered-complexity' law (scripts/policy/constitution.json): "Basic
features stay simple by default...". ledger/boundaries/B32.yml originally declined to give this
half of the law a checker, reasoning there is no failing input that proves a feature IS too
complex -- only a design-review judgment, which a checker attempting to render as pass/fail would
be a subjective complexity linter, not a Constitution law enforcer.

This checker does not attempt that judgment. It does not measure "is this feature simple" at all.
It operationalizes the law narrowly and objectively, the same way scripts/quality/
check-coverage-ratchet.py operationalizes "the codebase is adequately tested" as a RATCHET rather
than an absolute judgment: the MANDATORY (JSON Schema `required`) surface of the platform's basic
building blocks -- the model itself, and the `concept`/`field`/`panel`/`procedure` definitions a
first-time author meets first -- must never grow without a deliberate, reviewed bump to
scripts/policy/layered-complexity-baseline.json. A silently growing mandatory-field floor is a
concrete, checkable proxy for "a basic feature got harder to start with by default"; it is not a
claim that a smaller required-count is always simpler, only that a GROWING one, unnoticed, is
exactly the failure mode "stay simple by default" warns against.

This is deliberately narrow: it says nothing about optional-field sprawl, generated-code size, or
any other complexity axis. Widening its scope is real follow-up work, not attempted here -- it
exists only so `layered-complexity` can claim `enforcement: enforced` for something real instead
of staying free-riding aspirational forever, per check_task.py's constitutionNoFreeAspirational
rule and this checker's own `--calibrate` proof that it can actually fail.

    python check-layered-complexity-ratchet.py
    python check-layered-complexity-ratchet.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = REPO_ROOT / "scripts" / "policy" / "layered-complexity-baseline.json"


def load_baseline() -> dict:
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def measure(schema_path: Path, watched: dict) -> dict[str, int]:
    """Read the real schema and return {definition_name: required_count} for every watched key."""
    schema = json.loads(schema_path.read_text(encoding="utf-8-sig"))
    defs = schema.get("$defs", {})
    measured: dict[str, int] = {}
    for name, spec in watched.items():
        if spec["kind"] == "topLevelRequired":
            required = schema.get("required", [])
        else:
            definition = defs.get(name)
            if definition is None:
                raise KeyError(f"watched definition '{name}' not found under $defs in {schema_path}")
            required = definition.get("required", [])
        measured[name] = len(required)
    return measured


def evaluate(measured: dict[str, int], baseline_watched: dict) -> list[str]:
    """Pure comparison, no file I/O -- exercised directly by --calibrate so the RED path is
    proven without needing to mutate a real schema file on disk."""
    violations: list[str] = []
    for name, count in measured.items():
        baseline_count = baseline_watched.get(name, {}).get("requiredCount")
        if baseline_count is None:
            violations.append(f"'{name}': no baseline entry recorded -- add one to layered-complexity-baseline.json")
            continue
        if count > baseline_count:
            violations.append(
                f"'{name}': required-field count grew to {count} (baseline {baseline_count}) -- "
                f"a basic feature just got harder to start with by default. If this is a real, "
                f"reviewed decision, bump requiredCount in scripts/policy/layered-complexity-baseline.json "
                f"in the same commit and say why in its `note`."
            )
    return violations


def calibrate() -> int:
    """Must FAIL when a watched definition's required-count exceeds its baseline, PASS when it
    matches or is smaller."""
    baseline = {
        "concept": {"requiredCount": 2},
        "field": {"requiredCount": 2},
    }
    ok = True

    grown = {"concept": 3, "field": 2}
    grown_violations = evaluate(grown, baseline)
    fired = len(grown_violations) == 1 and "concept" in grown_violations[0]
    ok = ok and fired
    print(f"  [{'PASS' if fired else 'FAIL'}] grown required-count ({len(grown_violations)} violation(s) found, expected 1)")

    unchanged = {"concept": 2, "field": 2}
    unchanged_violations = evaluate(unchanged, baseline)
    silent = len(unchanged_violations) == 0
    ok = ok and silent
    print(f"  [{'PASS' if silent else 'FAIL'}] unchanged required-count ({len(unchanged_violations)} violation(s) found, expected 0)")

    shrunk = {"concept": 1, "field": 2}
    shrunk_violations = evaluate(shrunk, baseline)
    also_silent = len(shrunk_violations) == 0
    ok = ok and also_silent
    print(f"  [{'PASS' if also_silent else 'FAIL'}] shrunk required-count ({len(shrunk_violations)} violation(s) found, expected 0)")

    if not ok:
        print("\nCALIBRATION FAILED: the ratchet did not distinguish growth from unchanged/shrunk.")
        return 1
    print("\nOK: a grown required-field floor fails, an unchanged or shrunk one passes.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true", help="prove this checker can fail")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    baseline = load_baseline()
    schema_path = REPO_ROOT / baseline["schemaPath"]
    measured = measure(schema_path, baseline["watched"])
    violations = evaluate(measured, baseline["watched"])

    for name, count in measured.items():
        baseline_count = baseline["watched"][name]["requiredCount"]
        status = "OK" if count <= baseline_count else "GREW"
        print(f"  [{status}] {name}: required={count} (baseline={baseline_count})")

    if violations:
        for v in violations:
            print(f"FAIL: {v}")
        return 1
    print(f"\nPASS: no watched definition's mandatory surface grew past its recorded baseline ({schema_path.relative_to(REPO_ROOT)}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
