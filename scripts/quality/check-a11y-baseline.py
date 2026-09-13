#!/usr/bin/env python3
"""W1.8 (NPDEV_ROADMAP_2026-09-12.md Wave 1): accessibility violation-count ratchet.

Reads the per-routine `a11y` summaries scripts/quality/run-shell-scenarios.ps1 writes into its own
shell-scenarios-report.json (one axe-core scan per W1.7 golden routine, against whatever real screen
that routine's own steps reached -- see NPDevSamples/scripts/browser/scrapforai-harness.ps1's
New-A11yScanStep) and compares each routine's per-impact violation counts (critical/serious/moderate/
minor) against the floor recorded in scripts/policy/a11y-baseline.json.

Same asymmetric ratchet shape as scripts/quality/check-coverage-ratchet.py, just inverted: there,
higher coverage is better and the floor may only rise; here, FEWER violations is better and the floor
may only fall. A routine with no floor yet gets one from its first real measurement (never blocks).
A routine whose fresh count is lower than the floor auto-ratchets the floor down. A routine whose
fresh count is HIGHER than the floor is a real regression and fails the gate, unless --write-baseline
is passed explicitly (a deliberate, reviewed acceptance of the increase -- never implied).

A routine present in the report with no `a11y` field (its own earlier steps failed first, so the scan
never ran) is reported as not-measured, never as zero -- a missing measurement must never look like a
clean pass, and a routine that IS missing must never silently pass this checker by omission: at least
one routine must have produced a real scan, or the whole check fails.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

IMPACTS = ["critical", "serious", "moderate", "minor"]
SCHEMA_VERSION = "npdev-a11y-baseline.v1"

DEFAULT_COMMENT = (
    "W1.8 (NPDEV_ROADMAP_2026-09-12.md). Ratchet floor per browser routine, read/written by "
    "scripts/quality/check-a11y-baseline.py from scripts/quality/run-shell-scenarios.ps1's own "
    "shell-scenarios-report.json. Counts are axe-core violation NODE counts (not rule counts) per "
    "impact level, scoped to the wcag2a/wcag2aa/wcag21aa/best-practice tag families -- see "
    "NPDevSamples/scripts/browser/vendor/axe-core/VERSION.json for the pinned engine version. A "
    "routine with no entry yet gets one from its first real measurement rather than blocking a "
    "merge. A DROP for a routine that produced a fresh scan this run auto-ratchets the floor down; "
    "an INCREASE is a gate failure unless --write-baseline is passed explicitly. Never hand-edit a "
    "count downward to fake progress -- let the ratchet script do it from a real scan."
)


def find_repo_root(start: Path) -> Path:
    for candidate in [start.resolve(), *start.resolve().parents]:
        if (
            (candidate / "NPDevContract").is_dir()
            and (candidate / "NPDevGenerator").is_dir()
            and (candidate / "NPDevKernel").is_dir()
        ):
            return candidate
    raise SystemExit(
        "Could not locate the NPDev repo root (looked for a directory containing "
        "NPDevContract + NPDevGenerator + NPDevKernel)."
    )


def resolve_path(repo_root: Path, value: str) -> Path:
    p = Path(value)
    return p if p.is_absolute() else (repo_root / p)


def load_baseline(path: Path) -> dict:
    if not path.exists():
        return {"$schemaVersion": SCHEMA_VERSION, "_comment": DEFAULT_COMMENT, "engine": "axe-core", "routines": {}}
    baseline = json.loads(path.read_text(encoding="utf-8"))
    baseline.setdefault("$schemaVersion", SCHEMA_VERSION)
    baseline.setdefault("engine", "axe-core")
    baseline.setdefault("routines", {})
    return baseline


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo-root", default=None, help="Override repo root (mainly for testing).")
    parser.add_argument(
        "--report",
        required=True,
        help="Path to a shell-scenarios-report.json (or any report with the same routines[].a11y shape).",
    )
    parser.add_argument("--baseline", default="scripts/policy/a11y-baseline.json")
    parser.add_argument(
        "--write-baseline",
        action="store_true",
        help="Accept a measured INCREASE and re-pin the floor to it. A downward change (or a "
        "first measurement) is always written regardless of this flag -- it exists only to "
        "authorize a regression, never to make one silent.",
    )
    args = parser.parse_args(argv)

    repo_root = Path(args.repo_root).resolve() if args.repo_root else find_repo_root(Path(__file__).parent)
    report_path = resolve_path(repo_root, args.report)
    baseline_path = resolve_path(repo_root, args.baseline)

    if not report_path.exists():
        print(f"FAIL  report not found: {report_path}")
        return 1

    report = json.loads(report_path.read_text(encoding="utf-8"))
    routines = report.get("routines") or []
    if not routines:
        print("FAIL  report has no routines[] -- nothing to check (a filtered/partial run should never be fed to this checker).")
        return 1

    baseline = load_baseline(baseline_path)
    baseline_routines = baseline["routines"]

    measured_any = False
    regressed: list[str] = []
    changed = False
    lines: list[str] = []

    for entry in routines:
        name = entry.get("routine") or "?"
        scan = entry.get("a11y")
        if not scan:
            lines.append(f"  not-measured  {name:<18} (no a11y scan recorded -- routine's own steps did not reach it)")
            continue

        measured_any = True
        fresh = {impact: int(scan.get(impact, 0)) for impact in IMPACTS}
        rule_count = int(scan.get("ruleCount", 0))
        floor_entry = baseline_routines.get(name)

        if floor_entry is None:
            baseline_routines[name] = {**fresh, "ruleCount": rule_count, "measuredOn": report.get("generatedAt")}
            changed = True
            lines.append(f"  FIRST         {name:<18} {fresh}  (no prior floor -- recorded as the initial baseline)")
            continue

        floor = {impact: int(floor_entry.get(impact, 0)) for impact in IMPACTS}
        worse = {impact: fresh[impact] for impact in IMPACTS if fresh[impact] > floor[impact]}
        improved = any(fresh[impact] < floor[impact] for impact in IMPACTS)

        if worse and not args.write_baseline:
            regressed.append(name)
            lines.append(f"  FAIL          {name:<18} increased {worse}  (floor was {floor})")
            continue

        if worse and args.write_baseline:
            lines.append(f"  ACCEPTED      {name:<18} increase accepted via --write-baseline: {worse}  (was {floor})")
        elif improved:
            lines.append(f"  ok (ratchet)  {name:<18} improved {floor} -> {fresh}")
        else:
            lines.append(f"  ok            {name:<18} unchanged at {fresh}")

        baseline_routines[name] = {**fresh, "ruleCount": rule_count, "measuredOn": report.get("generatedAt")}
        changed = True

    for line in lines:
        print(line)

    if not measured_any:
        print("FAIL  no routine produced an a11y scan -- nothing was actually measured this run.")
        return 1

    if changed:
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(json.dumps(baseline, indent=2) + "\n", encoding="utf-8")
        print(f"  baseline written -> {baseline_path}")

    if regressed:
        print(
            f"FAIL  {len(regressed)} routine(s) regressed: {', '.join(regressed)}. "
            "Re-run with --write-baseline only if the increase is deliberate and reviewed."
        )
        return 1

    print("PASS  accessibility baseline holds (or improved).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
