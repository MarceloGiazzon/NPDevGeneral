#!/usr/bin/env python3
"""Coverage ratchet gate: LINE coverage per module must never drop below its recorded floor.

WHY THIS EXISTS
---------------
R3 (MASTER-ROADMAP.md Step 9 / ledger QUAL-6). Four Java builds (NPDevContract/dsl,
NPDevGenerator/generator, NPDevKernel/kernel + its 36 adapters, NPDevRuntimeHost) now carry a JaCoCo
plugin -- see each build.gradle's own comment for why RuntimeHost's is gated behind
`-PenableCoverage=true` while the other three are unconditional (only RuntimeHost's
build.gradle.template ships into a generated FinalApp; the platform-internal builds never do).

Track C card C8 (2026-08-14) extended this same ratchet -- same file, same schema, same gate step --
to the two ecosystems R3 explicitly deferred: the editor (`NPDevEditor/ui-react`) and NPDevCli
(`coverage.py` wrapping the SAME `python -m unittest discover -s NPDevCli/tests` invocation
run-ai-knowledge-gate.ps1 already ran). NPDevEditor/ui-react was later parked out of the repo
(see BREAKING.md), so its `istanbul-json-summary` entry is gone from coverage-baseline.json; the
format dispatch below is retained because it costs nothing and the module may return.
Neither tool emits JaCoCo's XML shape, so each module now
declares a `reportFormat` (`jacoco-xml` / `istanbul-json-summary` / `coverage-py-json`) and this
script dispatches to the matching parser -- the RATCHET SEMANTICS below are identical across all
three formats; only the bytes-on-disk differ. See coverage-baseline.json's own per-module notes for
why NPDevMcp and the rest of scripts/ stay at the 0.0/null placeholder (no dedicated automated test
suite exists for either yet -- the same kind of pre-existing, honestly-labelled gap as kernel's).

It reads scripts/policy/coverage-baseline.json, looks for a freshly-produced coverage report per
module, and:

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
This script is wired into run-ai-knowledge-gate.ps1, which is static by design (no Gradle, no npm
install, no boot). Reports only exist here because an EARLIER step in the same `run-all-gates.ps1`
invocation (run-generator-gate.ps1 for dsl/generator, run-runtimehost-gate.ps1 for RuntimeHost)
already ran the real test command, and each tool's own
report-writing left a file on disk: JaCoCo's `finalizedBy jacocoTestReport` wiring in each
build.gradle, vitest's `coverage.reporter: ["text","json-summary"]` in vitest.config.ts, or (for
NPDevCli) run-ai-knowledge-gate.ps1's OWN step [18/35] running the suite under `coverage run` before
this later step reads its `coverage json` output. In the STANDALONE ai-knowledge-gate.yml CI job
(which never builds the Java/frontend modules), the Java and editor modules legitimately read
not-measured every time -- that is the intended behaviour, not a bug: those checks become
load-bearing when a developer runs the full `run-all-gates.ps1` locally, or in a future CI job that
combines a build with this check. NPDevCli is the one module measured for real even in the
standalone CI job, since step [18/35] already runs its tests there. Kernel has no dedicated CI gate
at all yet (kernelQualityGate is a real Gradle task nobody invokes) -- a pre-existing gap this card
does not close; see coverage-baseline.json's kernel note. NPDevMcp and the rest of scripts/ have no
dedicated automated test suite at all yet -- same shape of gap, see their own baseline notes.

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

# REG-168: directories never worth scanning for "did this module's source change" -- build output,
# dependency caches, VCS metadata. Not exhaustive by design; a false negative here (an excluded dir
# that actually held a real source edit) only makes the staleness check slightly less strict, never
# wrong in the dangerous direction (it would still catch changes anywhere else in the module).
_SOURCE_SCAN_EXCLUDED_DIR_PARTS = {
    ".git", ".gradle", "build", "node_modules", "dist", "__pycache__", ".pytest_cache", "out",
}


def _newest_source_mtime(repo_root: Path, module_name: str) -> float | None:
    """REG-168: newest mtime among a module's own source files, so a coverage report can be checked
    for staleness against the source tree actually on disk -- not just trusted because it exists.
    Returns None (meaning "can't determine, don't block") if the module directory doesn't exist or
    has no files under it; callers must treat that as "skip the staleness check," not "stale."""
    module_dir = repo_root / module_name
    if not module_dir.is_dir():
        return None
    newest: float | None = None
    for p in module_dir.rglob("*"):
        if not p.is_file():
            continue
        if any(part in _SOURCE_SCAN_EXCLUDED_DIR_PARTS for part in p.relative_to(module_dir).parts[:-1]):
            continue
        mtime = p.stat().st_mtime
        if newest is None or mtime > newest:
            newest = mtime
    return newest


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


DEFAULT_REPORT_FORMAT = "jacoco-xml"

# QUAL-27: a PARTIAL test run empties whole classes (each class goes 0% covered), so the fraction of
# classes that are FULLY uncovered is a strong partial-run signal -- a real coverage regression moves
# the percentage without emptying whole classes. Measured live: a partial :generator:test report had
# 116/148 classes fully uncovered (~78%) while the real full run had 7/148 (~5%). A report above this
# fraction is refused as partial rather than treated as a real measurement.
PARTIAL_UNCOVERED_CLASS_FRACTION = 0.5


def _jacoco_line_counts(report_path: Path) -> tuple[int, int] | None:
    """JaCoCo's <report> root carries <counter type="..."/> elements as DIRECT children summarizing
    the whole report (one per counter type) -- NOT the per-package/per-class ones nested inside
    <package>/<class>/<method>, which is why this only looks at direct children of the root."""
    try:
        tree = ET.parse(report_path)
    except (ET.ParseError, OSError):
        return None
    for counter in tree.getroot().findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered", "0")), int(counter.get("missed", "0"))
    return None


def _jacoco_class_fully_uncovered_fraction(report_path: Path) -> float | None:
    """QUAL-27: JaCoCo's root <counter type=\"CLASS\"> reports how many classes are FULLY uncovered
    (missed) vs partially/fully covered (covered). Returns missed/(missed+covered), or None if there
    is no CLASS counter (non-jacoco, or a report truncated before class summaries were written)."""
    try:
        tree = ET.parse(report_path)
    except (ET.ParseError, OSError):
        return None
    for counter in tree.getroot().findall("counter"):
        if counter.get("type") == "CLASS":
            covered = int(counter.get("covered", "0"))
            missed = int(counter.get("missed", "0"))
            total = covered + missed
            return (missed / total) if total else None
    return None


def _report_is_partial(report_path: Path, report_format: str) -> bool:
    """QUAL-27: a single-report jacoco measurement whose fully-uncovered class fraction exceeds
    PARTIAL_UNCOVERED_CLASS_FRACTION is a partial test run (most tests never executed), not a real
    coverage measurement -- it must be skipped, never treated as a regression or a ratchet-up."""
    if report_format != "jacoco-xml":
        return False
    fraction = _jacoco_class_fully_uncovered_fraction(report_path)
    return fraction is not None and fraction > PARTIAL_UNCOVERED_CLASS_FRACTION


def _istanbul_json_summary_line_counts(report_path: Path) -> tuple[int, int] | None:
    """`@vitest/coverage-v8`'s (and plain istanbul's) `json-summary` reporter writes ONE
    coverage-summary.json whose top-level "total" key already aggregates every instrumented file --
    same shape this script wants, just under `total.lines.{covered,total}` instead of an XML
    <counter>. A file with no "total.lines" key (wrong reporter, truncated write) reads as
    not-measured rather than a crash."""
    try:
        with report_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError):
        return None
    lines = data.get("total", {}).get("lines")
    if not isinstance(lines, dict) or "covered" not in lines or "total" not in lines:
        return None
    covered = int(lines["covered"])
    total = int(lines["total"])
    return covered, max(total - covered, 0)


def _coverage_py_json_line_counts(report_path: Path) -> tuple[int, int] | None:
    """`coverage.py`'s `coverage json` writes `totals.{covered_lines,num_statements}` -- its
    "statement coverage" is the same conceptual metric as JaCoCo's LINE counter (each executable
    source line counted once), just named differently by the tool."""
    try:
        with report_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError):
        return None
    totals = data.get("totals")
    if not isinstance(totals, dict) or "covered_lines" not in totals or "num_statements" not in totals:
        return None
    covered = int(totals["covered_lines"])
    num_statements = int(totals["num_statements"])
    return covered, max(num_statements - covered, 0)


_FORMAT_PARSERS = {
    "jacoco-xml": _jacoco_line_counts,
    "istanbul-json-summary": _istanbul_json_summary_line_counts,
    "coverage-py-json": _coverage_py_json_line_counts,
}


def _line_counts(report_path: Path, report_format: str) -> tuple[int, int] | None:
    parser = _FORMAT_PARSERS.get(report_format, _jacoco_line_counts)
    return parser(report_path)


def parse_line_coverage_percent(report_path: Path, report_format: str = DEFAULT_REPORT_FORMAT) -> float | None:
    counts = _line_counts(report_path, report_format)
    if counts is None:
        return None
    covered, missed = counts
    total = covered + missed
    if total == 0:
        return 0.0
    return round((covered / total) * 100.0, 4)


def sum_line_coverage(reports: list[Path], report_format: str = DEFAULT_REPORT_FORMAT) -> float | None:
    """Aggregate LINE coverage across multiple reports of the SAME format -- used for the
    kernel+adapters module family, which has no single Gradle-level aggregate report (see
    coverage-baseline.json's kernel note: 36 adapters each produce their own report, summed here in
    Python instead of via a hand-built Gradle JacocoReport merge task). Generalized beyond JaCoCo so
    any future multi-report module (e.g. a per-package vitest/coverage.py split) can reuse it."""
    total_covered = 0
    total_missed = 0
    found_any = False
    for report in reports:
        counts = _line_counts(report, report_format)
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
    repo_root: Path, module_cfg: dict, override_path: str | None, module_name: str | None = None
) -> tuple[float | None, str | None]:
    """Returns (percent, evidence) or (None, None) if not measured this run."""
    report_format = module_cfg.get("reportFormat", DEFAULT_REPORT_FORMAT)

    if override_path:
        # An explicit --report MODULE=PATH is the caller vouching for freshness directly --
        # the staleness check below exists specifically for auto-discovery's ambiguity, which
        # doesn't apply here.
        p = Path(override_path)
        if not p.is_file():
            return None, None
        if _report_is_partial(p, report_format):
            print(f"    (QUAL-27: refusing partial report for {module_name or p}: "
                  f"{_jacoco_class_fully_uncovered_fraction(p):.1%} of classes fully uncovered)",
                  file=sys.stderr)
            return None, None
        return parse_line_coverage_percent(p, report_format), str(p)

    globs = module_cfg.get("reportGlobs", [])
    candidates = _find_reports(repo_root, globs)
    if not candidates:
        return None, None

    # REG-168: a discovered report may predate the source it's supposed to measure -- shared
    # multi-worktree Build directories, or a stale report surviving a checkout/revert without a
    # rebuild. Drop any candidate older than the module's own newest source file rather than
    # silently trusting it. source_mtime is None (skip the check) when the module directory can't
    # be resolved at all, e.g. a synthetic/calibration module name.
    source_mtime = _newest_source_mtime(repo_root, module_name) if module_name else None
    if source_mtime is not None:
        fresh = [c for c in candidates if c.stat().st_mtime >= source_mtime]
        for stale in candidates:
            if stale not in fresh:
                print(
                    f"    (REG-168: skipping stale report for {module_name}: {stale} predates "
                    "the module's newest source file)",
                    file=sys.stderr,
                )
        candidates = fresh
        if not candidates:
            return None, None

    if module_cfg.get("aggregate", False):
        pct = sum_line_coverage(candidates, report_format)
        evidence = f"{len(candidates)} report(s), e.g. {candidates[0]}"
        return pct, evidence

    newest = max(candidates, key=lambda p: p.stat().st_mtime)
    if _report_is_partial(newest, report_format):
        print(f"    (QUAL-27: refusing partial report for {module_name}: "
              f"{_jacoco_class_fully_uncovered_fraction(newest):.1%} of classes fully uncovered)",
              file=sys.stderr)
        return None, None
    return parse_line_coverage_percent(newest, report_format), str(newest)


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
        help="Explicitly point one module at a fresh coverage report (in whatever format that module's "
        "reportFormat declares), bypassing auto-discovery. May repeat.",
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
        pct, evidence = measure_module(repo_root, cfg, overrides.get(name), name)
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

    def make_istanbul_summary(path: Path, covered: int, total: int) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "total": {
                        "lines": {"total": total, "covered": covered, "skipped": 0, "pct": 0},
                        "statements": {"total": total, "covered": covered, "skipped": 0, "pct": 0},
                    }
                }
            ),
            encoding="utf-8",
        )
        return path

    def make_coverage_py_json(path: Path, covered_lines: int, num_statements: int) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "totals": {
                        "covered_lines": covered_lines,
                        "num_statements": num_statements,
                        "percent_covered": 0,
                    }
                }
            ),
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

        # Control 4c (Track C C8): the editor's `istanbul-json-summary` format (vitest
        # coverage-v8's json-summary reporter) parses covered/total from total.lines, not from a
        # <counter> element -- proves the format dispatch actually routes to a different parser.
        istanbul_report = make_istanbul_summary(tmp / "vitest" / "coverage-summary.json", covered=30, total=100)
        pct_istanbul = parse_line_coverage_percent(istanbul_report, "istanbul-json-summary")
        pass4c = pct_istanbul is not None and abs(pct_istanbul - 30.0) < 1e-6
        print(f"  [{'PASS' if pass4c else 'FAIL'}] istanbul-json-summary format parses total.lines (30/100 -> measured: {pct_istanbul})")
        ok = ok and pass4c

        # Control 4d (Track C C8): NPDevCli's `coverage-py-json` format (coverage.py's `coverage
        # json` output) parses covered_lines/num_statements from totals, not from total.lines --
        # proves the two new formats are genuinely independent parsers, not the same code twice.
        coveragepy_report = make_coverage_py_json(tmp / "python" / "coverage.json", covered_lines=17, num_statements=68)
        pct_coveragepy = parse_line_coverage_percent(coveragepy_report, "coverage-py-json")
        pass4d = pct_coveragepy is not None and abs(pct_coveragepy - 25.0) < 1e-6
        print(f"  [{'PASS' if pass4d else 'FAIL'}] coverage-py-json format parses totals.covered_lines/num_statements (17/68 -> measured: {pct_coveragepy})")
        ok = ok and pass4d

        # Control 4e (REG-168): a report that PREDATES the module's own newest source file is
        # rejected as stale (not-measured), even though it exists and glob-matches -- this is the
        # real regression this item fixed (a leftover report from an unrelated build/worktree/
        # checkout silently ratcheting the floor). A report NEWER than the source is still accepted.
        stale_module_dir = tmp / "stale-module"
        stale_report = make_xml(stale_module_dir / "report.xml", covered=90, missed=10)
        source_file = stale_module_dir / "src" / "Main.java"
        source_file.parent.mkdir(parents=True, exist_ok=True)
        source_file.write_text("class Main {}", encoding="utf-8")
        # Force the report to be OLDER than the source file it's supposed to measure, regardless of
        # filesystem timestamp resolution or how fast this test runs.
        report_time = source_file.stat().st_mtime - 3600
        os.utime(stale_report, (report_time, report_time))
        pct_stale, _ = measure_module(
            tmp, {"reportGlobs": ["stale-module/report.xml"]}, None, "stale-module"
        )
        pass4e_stale = pct_stale is None
        print(f"  [{'PASS' if pass4e_stale else 'FAIL'}] a report older than its module's newest source file is rejected as stale (measured: {pct_stale})")
        ok = ok and pass4e_stale

        # Same fixture, report now touched to be NEWER than the source -- must be accepted.
        fresh_time = source_file.stat().st_mtime + 3600
        os.utime(stale_report, (fresh_time, fresh_time))
        pct_fresh, _ = measure_module(
            tmp, {"reportGlobs": ["stale-module/report.xml"]}, None, "stale-module"
        )
        pass4e_fresh = pct_fresh is not None and abs(pct_fresh - 90.0) < 1e-6
        print(f"  [{'PASS' if pass4e_fresh else 'FAIL'}] the same report, touched newer than the source, is accepted (measured: {pct_fresh})")
        ok = ok and pass4e_fresh

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

        # Control 7 (QUAL-27): a partial report (most classes fully uncovered) must be REFUSED as
        # partial, while a full report (few classes fully uncovered) is accepted.
        partial_xml = tmp / "partial.xml"
        partial_xml.write_text(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<report name=\"fixture\">\n"
            "  <counter type=\"CLASS\" missed=\"116\" covered=\"32\"/>\n"
            "  <counter type=\"LINE\" missed=\"90\" covered=\"10\"/>\n"
            "</report>\n",
            encoding="utf-8",
        )
        full_xml = tmp / "full.xml"
        full_xml.write_text(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<report name=\"fixture\">\n"
            "  <counter type=\"CLASS\" missed=\"7\" covered=\"141\"/>\n"
            "  <counter type=\"LINE\" missed=\"10\" covered=\"90\"/>\n"
            "</report>\n",
            encoding="utf-8",
        )
        pass7 = _report_is_partial(partial_xml, "jacoco-xml") and not _report_is_partial(full_xml, "jacoco-xml")
        print(f"  [{'PASS' if pass7 else 'FAIL'}] a partial report (116/148 classes fully uncovered) is refused; a full one (7/148) is not")
        ok = ok and pass7

    if not ok:
        print("\nFAIL: calibration did not reproduce the expected PASS/FAIL pairs.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
