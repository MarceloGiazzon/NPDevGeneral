#!/usr/bin/env python3
"""Per-dialect conformance table for a CI job summary.

WHY THIS EXISTS
---------------
Run 31264977219 -- the first execution of storage-dialect-conformance.yml -- reported three failed
jobs and "52 tests completed, 14 failed". Underneath that headline, MySQL had passed all 13 of its
vectors against a real MySQL 8.4 container, for the first time ever. Discovering that required
downloading the artifact and parsing the XML by hand.

A run whose headline hides the result you were looking for is a run people learn to ignore. This
prints what the XML knows, where the reader already is.

The SECONDS column is not decoration: it is what separates a harness failure from a behavioural one.
In that run h2 failed 13 vectors in 0.1s total (an immediate throw, no database involved) while mysql
spent 23.7s (container time). Same red, entirely different meaning.

USAGE
    python scripts/quality/summarize-dialect-conformance.py <results-dir> [--out <file>]

Writes markdown to --out (default: $GITHUB_STEP_SUMMARY, else stdout). Exit 0 always: this
summarises a result, it does not judge one -- the test task's own exit code decides the job.
"""
from __future__ import annotations

import argparse
import collections
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DIALECT = re.compile(r"SqlDialect\[(\w+)\]")


def collect(results_dir: Path) -> tuple[dict[str, dict], list[str]]:
    per: dict[str, dict] = collections.defaultdict(
        lambda: {"passed": 0, "failed": 0, "skipped": 0, "seconds": 0.0, "failures": []})
    containers: list[str] = []
    for xml in sorted(results_dir.rglob("TEST-*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        # DialectTestSupport prints one line per container start. Gradle captures a forked test JVM's
        # stdout into system-out rather than forwarding it to the console, so this is where the
        # evidence reliably lives -- and reading it here puts "a real container was used" beside the
        # results rather than in an artifact nobody opens.
        out = root.find("system-out")
        for line in ((out.text or "") if out is not None else "").splitlines():
            if "[dialect-support] started" in line:
                containers.append(line.split("[dialect-support] started", 1)[1].strip())
        for case in root.iter("testcase"):
            match = DIALECT.search(case.get("name", "") or "")
            name = match.group(1) if match else "(unparameterised)"
            row = per[name]
            row["seconds"] += float(case.get("time") or 0)
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if case.find("skipped") is not None:
                row["skipped"] += 1
            elif problem is not None:
                row["failed"] += 1
                kind = (problem.get("type") or "").split(".")[-1]
                row["failures"].append(f"{case.get('classname', '').split('.')[-1]} -- {kind}")
            else:
                row["passed"] += 1
    return per, containers


def render(per: dict[str, dict], containers: list[str], title: str) -> str:
    if not per:
        return f"### {title}\n\nNo JUnit XML found -- the test step produced no results at all.\n"
    lines = [f"### {title}", "", "| dialect | passed | failed | skipped | seconds |",
             "|---|---:|---:|---:|---:|"]
    for name in sorted(per):
        row = per[name]
        mark = "" if row["failed"] == 0 else " ⚠"
        lines.append(f"| `{name}`{mark} | {row['passed']} | {row['failed']} | {row['skipped']} "
                     f"| {row['seconds']:.1f} |")
    failing = {n: r for n, r in per.items() if r["failed"]}
    if failing:
        lines += ["", "**Failures by kind** -- an `IllegalArgumentException` in ~0s is a HARNESS "
                      "problem (no database was involved); an `AssertionFailedError` after seconds "
                      "of container time is a real behavioural finding.", ""]
        for name, row in sorted(failing.items()):
            for kind, count in sorted(collections.Counter(row["failures"]).items()):
                lines.append(f"- `{name}`: {kind} × {count}")
    lines += ["", "**Containers started**"]
    if containers:
        lines += [f"- `{c}`" for c in sorted(set(containers))]
    else:
        lines.append("- _none reported_ — no real engine was started. If the job name says "
                     "\"real engine\", THAT is the failure, not the tests.")
    lines += ["", "_Seconds near zero means the engine was never reached._"]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results_dir")
    parser.add_argument("--out", default=os.environ.get("GITHUB_STEP_SUMMARY"))
    parser.add_argument("--title", default="Storage dialect conformance")
    args = parser.parse_args()

    results = Path(args.results_dir)
    per, containers = collect(results) if results.is_dir() else ({}, [])
    markdown = render(per, containers, args.title)
    if args.out:
        with open(args.out, "a", encoding="utf-8") as handle:
            handle.write(markdown)
    print(markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
