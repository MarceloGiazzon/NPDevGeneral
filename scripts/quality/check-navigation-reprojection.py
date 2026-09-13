#!/usr/bin/env python3
r"""W1.2 (NPDEV_ROADMAP_2026-09-12.md, Wave 1): "Make the menu re-project on regeneration."
WorkspaceMenuSeeder had two modes and NEITHER reconciled -- insert-if-empty ignores a
model/menu.json change once the table has any row; upsert-if-fingerprint-changed truncates the
whole table on ANY change, destroying every manual edit. This checker is the end-to-end proof for
the fix: WorkspaceMenuSeeder's new default `reconcile` mode, run for real across two boots of a
generated app against the SAME database.

What it actually does (a real subprocess call, no mocking): shells out to
scripts/quality/run-navigation-reprojection-probe.ps1, which generates+boots the probe
NPDevSamples/probes/path-a-navigation-reprojection, writes a first menu-seed file (3 rows),
confirms reconcile inserts all 3 into the empty table, stops the app, hand-edits one row directly
in the database (standing in for a generic-CRUD edit), regenerates + writes a SECOND, changed
menu-seed file (1 row changed, 1 dropped, 1 added), reboots against the SAME database file, and
asserts: the changed row shows the fresh model content (D7: model wins), the user's prior edit was
preserved as a new invisible override row (seed_origin=user, overrideOf=<the row's seedKey>), the
dropped row is gone, the new row is present, and the untouched row was left exactly alone.

    python check-navigation-reprojection.py
    python check-navigation-reprojection.py --calibrate   # assertion-logic self-test only
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PROBE_SCRIPT = REPO_ROOT / "scripts" / "quality" / "run-navigation-reprojection-probe.ps1"


def run_probe() -> list[str]:
    result = subprocess.run(
        ["pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(PROBE_SCRIPT)],
        cwd=REPO_ROOT,
    )
    if result.returncode != 0:
        return [f"run-navigation-reprojection-probe.ps1 exited {result.returncode} -- see its "
                f"own FAIL lines above for which specific assertion(s) failed."]
    return []


SUMMARY_PATTERN = re.compile(
    r"WorkspaceMenuSeeder: reconciled .*?--\s*"
    r"(?P<inserted>\d+) inserted,\s*(?P<updated>\d+) updated,\s*(?P<removed>\d+) removed,\s*"
    r"(?P<preserved>\d+) preserved-as-override,\s*(?P<unchanged>\d+) unchanged\."
)


def parse_summary(line: str) -> dict[str, int] | None:
    m = SUMMARY_PATTERN.search(line)
    if not m:
        return None
    return {k: int(v) for k, v in m.groupdict().items()}


SYNTHETIC_EMPTY_TABLE_BOOT = "WorkspaceMenuSeeder: reconciled workspace_v1_menus for tenant 'dev' -- 3 inserted, 0 updated, 0 removed, 0 preserved-as-override, 0 unchanged."
SYNTHETIC_RECONCILE_BOOT = "WorkspaceMenuSeeder: reconciled workspace_v1_menus for tenant 'dev' -- 1 inserted, 1 updated, 1 removed, 1 preserved-as-override, 1 unchanged."
SYNTHETIC_NO_SUMMARY = "some unrelated boot log line"


def calibrate() -> int:
    ok = True

    def report(label: str, passed: bool, expected: bool) -> None:
        nonlocal ok
        good = passed == expected
        ok = ok and good
        print(f"  [{'PASS' if good else 'FAIL'}] {label}")

    print("Calibration -- the summary-line regex must extract the five reconcile counts:")
    first_pass = parse_summary(SYNTHETIC_EMPTY_TABLE_BOOT)
    report("first-boot summary parses", first_pass is not None, expected=True)
    report("first-boot inserted == 3", first_pass is not None and first_pass["inserted"] == 3, expected=True)

    second_pass = parse_summary(SYNTHETIC_RECONCILE_BOOT)
    report("second-boot summary parses", second_pass is not None, expected=True)
    report(
        "second-boot shows the 1/1/1/1/1 split",
        second_pass == {"inserted": 1, "updated": 1, "removed": 1, "preserved": 1, "unchanged": 1},
        expected=True,
    )

    report("a line with no summary does not parse", parse_summary(SYNTHETIC_NO_SUMMARY) is not None, expected=False)

    if not ok:
        print("\nFAIL: the assertion logic did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: assertion logic behaves correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    if not PROBE_SCRIPT.is_file():
        print(f"FAIL: probe driver not found at {PROBE_SCRIPT}", file=sys.stderr)
        return 1

    print(f"Running {PROBE_SCRIPT.name} (real generate/boot/edit/regenerate/reboot cycle)...")
    problems = run_probe()

    print("\nnavigation reprojection checks:")
    if problems:
        for p in problems:
            print(f"  - {p}")
        print(f"\nFAIL: {len(problems)} problem(s).", file=sys.stderr)
        return 1
    print("  OK: reconcile mode inserted, updated-with-preserved-override (D7), removed, and left "
          "an unrelated row alone, across a real two-boot cycle against the same database.")
    print("\nOK: navigation reprojection proof passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
