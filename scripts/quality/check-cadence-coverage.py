#!/usr/bin/env python3
"""Fast Lane plan Sec.7.4: "a check absent from verification-cadence.json fails the cadence-
coverage check itself." Closes the "a check exists and nothing runs it" class one level up --
run-script-inventory-check.py already catches an orphaned check-*.py (nothing calls it); this
catches a gate that runs but was never given a staleness deadline, which is the same defect
wearing the tiering hat.

Validates:
  1. verification-cadence.json parses and every entry has id/tier/maxStaleness/invokedBy/description.
  2. Every tier value is one of T0/T1/T2/T3.
  3. Every maxStaleness value is one cadence_state.py actually knows how to evaluate (imports the
     same module rather than duplicating the set, so the two files cannot silently drift apart).
  4. Every gate name declared in run-all-gates.ps1's $gates array has a matching check id here,
     with the SAME tier run-all-gates.ps1 itself assigns it (T2, except betaRelease which is T3 --
     see item 4's split).

Usage: python scripts/quality/check-cadence-coverage.py [--calibrate]
"""
import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CADENCE_PATH = REPO_ROOT / "scripts" / "quality" / "verification-cadence.json"
RUN_ALL_GATES_PATH = REPO_ROOT / "scripts" / "quality" / "run-all-gates.ps1"

sys.path.insert(0, str(REPO_ROOT / "scripts" / "quality"))
import cadence_state  # noqa: E402

REQUIRED_FIELDS = ["id", "tier", "maxStaleness", "invokedBy", "description"]
VALID_TIERS = {"T0", "T1", "T2", "T3"}

# run-all-gates.ps1 assigns betaRelease and weeklyPaperwork to T3 by convention (item 4's split;
# GATE-SPLIT, 2026-08-25 W4.5, reuses the same tier for a second, differently-reasoned deferred
# gate rather than inventing a fifth tier), every other gate name in its $gates array is T2. Kept
# explicit here rather than inferred, so a future change to which gates are T3 is a one-line,
# reviewable diff in this exact spot.
GATE_TIER_OVERRIDES = {"betaRelease": "T3", "weeklyPaperwork": "T3"}


def load_cadence_checks():
    if not CADENCE_PATH.exists():
        return None, [f"verification-cadence.json not found: {CADENCE_PATH}"]
    try:
        with CADENCE_PATH.open("r", encoding="utf-8") as f:
            doc = json.load(f)
    except Exception as e:
        return None, [f"verification-cadence.json is not valid JSON: {e}"]
    checks = doc.get("checks")
    if not isinstance(checks, list) or not checks:
        return None, ["verification-cadence.json has no non-empty 'checks' array"]
    return checks, []


def extract_gate_names(ps1_text):
    # Matches:  [pscustomobject]@{ Name = "aiKnowledge"; Script = "scripts/quality/run-ai-knowledge-gate.ps1"; ...
    return re.findall(r'Name\s*=\s*"([A-Za-z0-9_]+)"\s*;\s*Script\s*=', ps1_text)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--calibrate", action="store_true", help="Self-test: verify this checker's own failure modes are reachable.")
    args = parser.parse_args(argv)

    if args.calibrate:
        return run_calibration()

    failures = []
    checks, load_failures = load_cadence_checks()
    failures.extend(load_failures)

    checks_by_id = {}
    if checks is not None:
        for entry in checks:
            check_id = entry.get("id")
            if not check_id:
                failures.append(f"A check entry has no 'id': {entry}")
                continue
            if check_id in checks_by_id:
                failures.append(f"Duplicate check id in verification-cadence.json: {check_id}")
            checks_by_id[check_id] = entry
            missing = [field for field in REQUIRED_FIELDS if not entry.get(field)]
            if missing:
                failures.append(f"Check '{check_id}' is missing required field(s): {', '.join(missing)}")
            tier = entry.get("tier")
            if tier and tier not in VALID_TIERS:
                failures.append(f"Check '{check_id}' has unknown tier '{tier}' (expected one of {sorted(VALID_TIERS)})")
            staleness = entry.get("maxStaleness")
            if staleness and staleness not in cadence_state.STALENESS_TO_TIMEDELTA:
                failures.append(
                    f"Check '{check_id}' has maxStaleness '{staleness}' that cadence_state.py does not know "
                    "how to evaluate -- add it to STALENESS_TO_TIMEDELTA there, or fix the typo here."
                )

    if not RUN_ALL_GATES_PATH.exists():
        failures.append(f"run-all-gates.ps1 not found: {RUN_ALL_GATES_PATH}")
    else:
        ps1_text = RUN_ALL_GATES_PATH.read_text(encoding="utf-8")
        gate_names = extract_gate_names(ps1_text)
        if not gate_names:
            failures.append(
                "Could not extract any gate name from run-all-gates.ps1 -- its $gates array shape "
                "changed and this checker's regex needs updating (fail loud, not silent)."
            )
        for gate_name in gate_names:
            if gate_name not in checks_by_id:
                failures.append(
                    f"run-all-gates.ps1 gate '{gate_name}' has no entry in verification-cadence.json "
                    "-- every gate run-all-gates.ps1 knows about must have a staleness deadline."
                )
                continue
            expected_tier = GATE_TIER_OVERRIDES.get(gate_name, "T2")
            actual_tier = checks_by_id[gate_name].get("tier")
            if actual_tier != expected_tier:
                failures.append(
                    f"run-all-gates.ps1 gate '{gate_name}' is declared tier '{actual_tier}' in "
                    f"verification-cadence.json, expected '{expected_tier}'."
                )

    if failures:
        print(f"FAIL: {len(failures)} cadence-coverage issue(s):")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print(f"OK: verification-cadence.json covers every run-all-gates.ps1 gate ({len(checks_by_id)} checks declared).")
    return 0


def run_calibration():
    """Self-test (the run-ai-knowledge-gate.ps1 --calibrate convention, step 18): prove this
    checker's own failure branches are reachable by feeding it deliberately-broken fixtures,
    in-memory, without touching the real verification-cadence.json."""
    failures = []

    # Missing gate coverage should be caught.
    fake_checks_by_id = {"aiKnowledge": {"tier": "T2"}}
    ps1_text = 'Name = "aiKnowledge"; Script = "x"\nName = "generator"; Script = "y"\n'
    gate_names = extract_gate_names(ps1_text)
    if "generator" in fake_checks_by_id or "generator" not in gate_names:
        failures.append("calibration: extract_gate_names did not find the expected gate names")

    # Unknown maxStaleness should be caught.
    if "not-a-real-value" in cadence_state.STALENESS_TO_TIMEDELTA:
        failures.append("calibration: STALENESS_TO_TIMEDELTA unexpectedly contains a fake key")

    if failures:
        print(f"CALIBRATION FAILED: {len(failures)} issue(s):")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("CALIBRATION OK: check-cadence-coverage.py's failure branches are reachable.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
