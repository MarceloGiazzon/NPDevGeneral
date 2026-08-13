#!/usr/bin/env python3
"""Coverage ratchet gate: JaCoCo LINE coverage per Java module must never drop below its floor.

WHY THIS EXISTS
---------------
R3 (MASTER-ROADMAP.md Step 9 / ledger QUAL-6). Four Java builds (NPDevContract/dsl,
NPDevGenerator/generator, NPDevKernel/kernel + its 36 adapters, NPDevRuntimeHost) now carry a JaCoCo
plugin -- see each build.gradle's own comment for why RuntimeHost's is gated behind
`-PenableCoverage=true` while the other three are unconditional (only RuntimeHost's
build.gradle.template ships into a generated FinalApp; the platform-internal builds never do). This
script is the other half: it reads scripts/policy/coverage-baseline.json, looks for a
freshly-produced JaCoCo XML report per module, and:

  - if a module's report is missing this run, reports it as NOT MEASURED and does not fail -- the
    ratchet's whole point (see coverage-baseline.json's own _comment) is that the first real
    measurement sets the floor rather than blocking a merge, and this script never triggers a build
    itself;
  - if a module's report exists and its LINE coverage percentage is BELOW the recorded floor, fails
    the gate -- a real regression;
  - if a module's report exists and its LINE coverage percentage is AT the recorded floor, leaves the
    baseline untouched;
  - if a module's report exists and its LINE coverage percentage is ABOVE the recorded floor,
    ratchets scripts/policy/coverage-baseline.json up in place (coveragePercent + measuredOn) --
    committing that file is how the floor rises. Never lowered by this script.

WHERE REPORTS COME FROM
------------------------
This script builds nothing itself -- it is wired into run-ai-knowledge-gate.ps1, which is static by
design (no Gradle, no boot). Reports only exist here because an EARLIER step in the same
`run-all-gates.ps1` invocation (run-generator-gate.ps1 for dsl/generator, run-runtimehost-gate.ps1
for RuntimeHost) already ran `gradlew ... test`, and JaCoCo's `finalizedBy jacocoTestReport` wiring
in each build.gradle left an XML report on disk. In the STANDALONE ai-knowledge-gate.yml CI job
(which never builds anything), every module legitimately reads not-measured every time -- that is
the intended behaviour, not a bug: this check becomes load-bearing when a developer runs the full
`run-all-gates.ps1` locally, or in a future CI job that combines a build with this check. Kernel has
no dedicated CI gate at all yet (kernelQualityGate is a real Gradle task nobody invokes) -- a
pre-existing gap this card does not close; see coverage-baseline.json's kernel note.

Usage:
    python scripts/quality/check-coverage-ratchet.py
    python scripts/quality/check-coverage-ratchet.py --report "NPDevGenerator/generator=<path>"
    python scripts/quality/check-coverage-ratchet.py --calibrate

Exit 0 = no module regressed (coverage-baseline.json may have been rewritten if any module improved).
Exit 1 = at least one module's fresh measurement fell below its recorded floor.
Exit 2 = usage error / baseline file missing or malformed.
"""
from __future__ import annotations

import argparse
import datetime
import glob
import json
import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

BASELINE_PATH = "scripts/policy/coverage-baseline.json"


def _repo_root(explicit: str | None) -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    if explicit:
        return Path(explicit).resolve()
    here = Path(__file__).resolve()
    for candidate in [here, *here.parents]:
        if (
            (candidate / "NPDevContract").is_dir()
            and (candidate / "NPDevGenerator").is_dir()
            and (candidate / "NPDevKernel").is_dir()
        ):
            return candidate
    # scripts/quality/check-coverage-ratchet.py -> scripts/quality -> scripts -> repo root
    return here.parents[2]


def _external_build_root(repo_root: Path) -> Path | None:
    """Mirrors resolveNpdevBuildRoot's Groovy logic (NPDEV_BUILD_ROOT env var, else
    <parent of repo root>/Build) -- Gradle redirects layout.buildDirectory there for dsl/generator/
    kernel, so a `**` glob rooted only at repo_root can never find their JaCoCo reports."""
    env = os.environ.get("NPDEV_BUILD_ROOT")
    if env:
        return Path(env)
    parent = repo_root.parent
    if parent != repo_root:
        return parent / "Build"
    return None


def load_baseline(repo_root: Path) -> dict:
    path = repo_root / BASELINE_PATH
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def save_baseline(repo_root: Path, baseline: dict) -> None:
    path = repo_root / BASELINE_PATH
    with path.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(baseline, f, indent=2)
        f.write("\n")


def _line_counter(report_root: ET.Element) -> tuple[int, int] | None:
    """JaCoCo's <report> root carries <counter type="..."/> elements as DIRECT children summarizing
    the whole report (one per counter type) -- NOT the per-package/per-class ones nested inside
    <package>/<class>/<method>, which is why this only looks at direct children of the root."""
    for counter in report_root.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered", "0")), int(counter.get("missed", "0"))
    return None


def parse_line_coverage_percent(xml_path: Path) -> float | None:
    try:
        tree = ET.parse(xml_path)
    except (ET.ParseError, OSError):
        return None
    counts = _line_counter(tree.getroot())
    if counts is None:
        return None
    covered, missed = counts
    total = covered + missed
    if total == 0:
        return 0.0
    return round((covered / total) * 100.0, 4)


def sum_line_coverage(reports: list[Path]) -> float | None:
    """Aggregate LINE coverage across multiple JaCoCo XML reports -- used for the kernel+adapters
    module family, which has no single Gradle-level aggregate report (see coverage-baseline.json's
    kernel note: 36 adapters each produce their own report, summed here in Python instead of via a
    hand-built Gradle JacocoReport merge task)."""
    total_covered = 0
    total_missed = 0
    found_any = False
    for report in reports:
        try:
            tree = ET.parse(report)
        except (ET.ParseError, OSError):
            continue
        counts = _line_counter(tree.getroot())
        if counts is None:
            continue
        covered, missed = counts
        total_covered += covered
        total_missed += missed
        found_any = True
    if not found_any:
        return None
    total = total_covered + total_missed
    if total == 0:
        return 0.0
    return round((total_covered / total) * 100.0, 4)


def _find_reports(repo_root: Path, globs: list[str]) -> list[Path]:
    candidates: list[Path] = []
    roots = [repo_root]
    external_root = _external_build_root(repo_root)
    if external_root is not None:
        roots.append(external_root)
    for root in roots:
        for pattern in globs:
            for match in glob.glob(str(root / pattern), recursive=True):
                p = Path(match)
                if p.is_file():
                    candidates.append(p)
    return candidates


def measure_module(
    repo_root: Path, module_cfg: dict, override_path: str | None
) -> tuple[float | None, str | None]:
    """Returns (percent, evidence) or (None, None) if not measured this run."""
    if override_path:
        p = Path(override_path)
        if not p.is_file():
            return None, None
        return parse_line_coverage_percent(p), str(p)

    globs = module_cfg.get("reportGlobs", [])
    candidates = _find_reports(repo_root, globs)
    if not candidates:
        return None, None

    if module_cfg.get("aggregate", False):
        pct = sum_line_coverage(candidates)
        evidence = f"{len(candidates)} report(s), e.g. {candidates[0]}"
        return pct, evidence

    newest = max(candidates, key=lambda p: p.stat().st_mtime)
    return parse_line_coverage_percent(newest), str(newest)


def _now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--repo-root", default=None, help="Override repo root (mainly for testing).")
    parser.add_argument(
        "--report",
        action="append",
        default=[],
        metavar="MODULE=PATH",
        help="Explicitly point one module at a fresh JaCoCo XML report, bypassing auto-discovery. May repeat.",
    )
    parser.add_argument(
        "--calibrate",
        action="store_true",
        help="Self-test: verify this checker's own regression/ratchet-up/not-measured branches are reachable.",
    )
    args = parser.parse_args(argv)

    if args.calibrate:
        return run_calibration()

    repo_root = _repo_root(args.repo_root)
    baseline_path = repo_root / BASELINE_PATH
    if not baseline_path.is_file():
        print(f"FAIL: missing {BASELINE_PATH}", file=sys.stderr)
        return 2
    try:
        baseline = load_baseline(repo_root)
    except json.JSONDecodeError as exc:
        print(f"FAIL: {BASELINE_PATH} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    overrides: dict[str, str] = {}
    for entry in args.report:
        if "=" not in entry:
            print(f"FAIL: --report expects MODULE=PATH, got: {entry}", file=sys.stderr)
            return 2
        mod, path = entry.split("=", 1)
        overrides[mod] = path

    modules = baseline.get("modules", {})
    if not modules:
        print(f"FAIL: {BASELINE_PATH} declares no modules", file=sys.stderr)
        return 2

    failures: list[str] = []
    changed = False
    print("Coverage ratchet (LINE %) per module:")
    for name in sorted(modules):
        cfg = modules[name]
        recorded = float(cfg.get("coveragePercent", 0.0))
        pct, evidence = measure_module(repo_root, cfg, overrides.get(name))
        if pct is None:
            print(f"  - {name}: not measured this run (floor stays {recorded}%)")
            continue
        if pct < recorded - 1e-9:
            failures.append(
                f"{name}: coverage DROPPED to {pct}% (recorded floor {recorded}%, evidence {evidence})"
            )
            print(f"  - {name}: {pct}% <-- REGRESSION vs floor {recorded}% ({evidence})")
        elif pct > recorded:
            print(f"  - {name}: {pct}% (ratcheting floor up from {recorded}%, {evidence})")
            cfg["coveragePercent"] = pct
            cfg["measuredOn"] = _now_iso()
            changed = True
        else:
            print(f"  - {name}: {pct}% (matches recorded floor, {evidence})")

    if changed:
        save_baseline(repo_root, baseline)
        print(f"\nUpdated {BASELINE_PATH} -- commit the result to keep the higher floor.")

    if failures:
        print("\nCoverage ratchet FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print("\nCoverage ratchet OK.")
    return 0


def run_calibration() -> int:
    """Self-test: prove the regression branch, the ratchet-up branch, the not-measured branch and
    the aggregation branch are all reachable -- using in-memory fixtures written to a temp
    directory. Never touches the real coverage-baseline.json or any real JaCoCo report."""
    ok = True

    def make_xml(path: Path, covered: int, missed: int) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<report name=\"fixture\">\n"
            "  <counter type=\"INSTRUCTION\" missed=\"1\" covered=\"1\"/>\n"
            f"  <counter type=\"LINE\" missed=\"{missed}\" covered=\"{covered}\"/>\n"
            "  <counter type=\"METHOD\" missed=\"1\" covered=\"1\"/>\n"
            "</report>\n",
            encoding="utf-8",
        )
        return path

    with tempfile.TemporaryDirectory() as tmp_str:
        tmp = Path(tmp_str)

        # Control 1: a report below the recorded floor reads as a regression.
        below = make_xml(tmp / "below.xml", covered=10, missed=90)
        pct_below = parse_line_coverage_percent(below)
        pass1 = pct_below is not None and pct_below < 50.0
        print(f"  [{'PASS' if pass1 else 'FAIL'}] a 10% fixture reads below a 50% floor (measured: {pct_below})")
        ok = ok and pass1

        # Control 2: a report above the recorded floor reads as an improvement, not a regression.
        above = make_xml(tmp / "above.xml", covered=90, missed=10)
        pct_above = parse_line_coverage_percent(above)
        pass2 = pct_above is not None and pct_above > 50.0
        print(f"  [{'PASS' if pass2 else 'FAIL'}] a 90% fixture reads above a 50% floor (measured: {pct_above})")
        ok = ok and pass2

        # Control 3: a missing report is not-measured, never treated as 0% / a failure.
        pct_missing, _ = measure_module(tmp, {"reportGlobs": ["does-not-exist-*.xml"]}, None)
        pass3 = pct_missing is None
        print(f"  [{'PASS' if pass3 else 'FAIL'}] a missing report is not-measured, not zero (measured: {pct_missing})")
        ok = ok and pass3

        # Control 4: aggregation sums covered/missed across multiple reports (the kernel+adapters
        # shape) rather than averaging percentages or picking just one.
        agg_dir = tmp / "agg"
        make_xml(agg_dir / "kernel.xml", covered=80, missed=20)   # 80%, weight 100
        make_xml(agg_dir / "adapter-a.xml", covered=0, missed=100)  # 0%, weight 100
        pct_agg, evidence_agg = measure_module(
            tmp, {"reportGlobs": ["agg/*.xml"], "aggregate": True}, None
        )
        # Expected: (80+0) covered out of (100+100) total = 40%, NOT the naive average of
        # (80% + 0%)/2 = 40% either by coincidence here -- so also check a skewed case.
        pass4a = pct_agg is not None and abs(pct_agg - 40.0) < 1e-6
        print(f"  [{'PASS' if pass4a else 'FAIL'}] two equal-size reports (80%,0%) aggregate to 40% by covered/missed sum (measured: {pct_agg}, {evidence_agg})")
        ok = ok and pass4a

        agg_dir2 = tmp / "agg2"
        make_xml(agg_dir2 / "big.xml", covered=90, missed=10)     # 90%, weight 100
        make_xml(agg_dir2 / "small.xml", covered=0, missed=1)     # 0%, weight 1
        pct_agg2, _ = measure_module(tmp, {"reportGlobs": ["agg2/*.xml"], "aggregate": True}, None)
        # A naive average of the two percentages would be 45%; the correct covered/missed sum is
        # 90/101 =~ 89.1%. This distinguishes "sum counters" (right) from "average percentages" (wrong).
        pass4b = pct_agg2 is not None and abs(pct_agg2 - 89.1089) < 0.01
        print(f"  [{'PASS' if pass4b else 'FAIL'}] a skewed pair (90%/weight100, 0%/weight1) aggregates by weighted sum, not naive average (measured: {pct_agg2}, expected ~89.11)")
        ok = ok and pass4b

        # Control 5: ratchet logic itself -- feed a fixture repo through main()'s measurement path
        # via an explicit --report override, on a throwaway baseline file, and confirm both the
        # PASS (improvement, file rewritten) and FAIL (regression, exit 1) outcomes actually occur.
        fixture_repo = tmp / "fixture-repo"
        (fixture_repo / "scripts" / "policy").mkdir(parents=True, exist_ok=True)
        baseline_file = fixture_repo / BASELINE_PATH
        baseline_file.write_text(
            json.dumps(
                {
                    "modules": {
                        "fixture/module": {"coveragePercent": 50.0, "measuredOn": None, "reportGlobs": []}
                    }
                }
            ),
            encoding="utf-8",
        )
        improved_report = make_xml(tmp / "improved.xml", covered=90, missed=10)
        rc_improve = main(
            [
                "--repo-root", str(fixture_repo),
                "--report", f"fixture/module={improved_report}",
            ]
        )
        rewritten = json.loads(baseline_file.read_text(encoding="utf-8"))
        new_floor = rewritten["modules"]["fixture/module"]["coveragePercent"]
        pass5 = rc_improve == 0 and abs(new_floor - 90.0) < 1e-6
        print(f"  [{'PASS' if pass5 else 'FAIL'}] main() ratchets the floor up to 90% and exits 0 on improvement (exit={rc_improve}, new floor={new_floor})")
        ok = ok and pass5

        regressed_report = make_xml(tmp / "regressed.xml", covered=10, missed=90)
        rc_regress = main(
            [
                "--repo-root", str(fixture_repo),
                "--report", f"fixture/module={regressed_report}",
            ]
        )
        pass6 = rc_regress == 1
        print(f"  [{'PASS' if pass6 else 'FAIL'}] main() exits 1 when a fresh measurement falls below the (now 90%) floor (exit={rc_regress})")
        ok = ok and pass6

    if not ok:
        print("\nFAIL: calibration did not reproduce the expected PASS/FAIL pairs.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
