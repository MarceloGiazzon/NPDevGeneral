#!/usr/bin/env python3
"""Register self-check: a summary row must not contradict its own detail section.

WHY THIS EXISTS
---------------
Across 2026-07-24/25 an audit found ~11 items that *looked* open but were closed:
six rows in `NPDEV_OPEN_ITEMS_REGISTER.md`'s index (REG-1/2/3/4/5/9), REG-6, REG-16
("zero adversarial review" long after Tier A **and** B were done), REG-17 ("PARTIAL"
while its own section said ACHIEVED), the schema-engine plan's Phase-5 row, and AW-P2
in `OPEN_GAPS_AND_ROADMAP.md`.

That drift is expensive in a compounding way: work gets re-planned and nearly re-done,
and because `knowledge/platform-status.json` is DERIVED from these documents, a stale
summary row silently poisons the AI knowledge substrate that the MCP tools serve.

Every one of those would have been caught by the single rule below, in under a second.

THE RULE
--------
For each item that has BOTH a summary-table row and a detail section:

    detail says CLOSED/DONE/WONTFIX/ACHIEVED  =>  the summary row MUST be struck (~~ID~~)
    detail says OPEN/PARTIAL/GAP/IN PROGRESS  =>  the summary row MUST NOT be struck

Strikethrough is the convention this register already uses to mean "closed"; the check
enforces the convention rather than inventing a new one.

DESIGN: FALSE POSITIVES ARE WORSE THAN FALSE NEGATIVES
------------------------------------------------------
A doc gate that cries wolf gets bypassed, and then it protects nothing. So this script
only FAILS on a mismatch it can prove: it must confidently extract a status from BOTH
sides. Anything it cannot parse is reported as SKIPPED (visible, not silent) and does
not fail the run. Tighten it when a real miss proves it too lenient -- never loosen it
after a false alarm.

USAGE
-----
    python scripts/quality/check-register-consistency.py            # check, exit 1 on drift
    python scripts/quality/check-register-consistency.py --verbose  # also list OK/SKIPPED

Exit codes: 0 = consistent, 1 = at least one contradiction, 2 = a document was unreadable.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A closed item, however its section words it.
CLOSED_WORDS = ("CLOSED", "DONE", "WONTFIX", "ACHIEVED", "RETIRED", "LIFTED")
# An item that is still live work.
OPEN_WORDS = ("OPEN", "PARTIAL", "GAP", "IN PROGRESS", "NOT STARTED", "TODO")

# `| ~~**REG-1**~~ |`, `| **REG-7** |`, and the roadmap's unbolded `| AW-P2 |`.
SUMMARY_ROW = re.compile(
    r"^\|\s*(?P<struck>~~)?\s*\*{0,2}(?P<id>[A-Z][A-Za-z0-9]*-[A-Za-z0-9-]+)\*{0,2}\s*~{0,2}\s*\|"
)
# `### 1.6 REG-6 — ...` / `## 3.10 REG-16-resid — ...`
DETAIL_HEADING = re.compile(
    r"^#{2,4}\s+(?:[0-9]+(?:\.[0-9]+)*\s+)?(?P<id>[A-Z][A-Za-z0-9]*-[A-Za-z0-9-]+)\b"
)
# The first bolded token after `**Status:**`, e.g. `**Status:** **CLOSED (2026-07-21, ...)**`
STATUS_LINE = re.compile(r"\*\*Status:\*\*\s*\*{0,2}(?P<status>[^*\n.]{0,80})")


def classify(text: str) -> str | None:
    """First keyword wins, anywhere in the status text; unrecognised phrasing -> None.

    'CLOSED as re-scoped ... purity DEFERRED'        -> closed  (CLOSED comes first)
    'PARTIAL, advanced 2026-07-22'                   -> open
    'GREEN END-TO-END - REG-17 ACHIEVED (2026-...)'  -> closed  (keyword need not start the line)
    'TIER A COMPLETE (2026-07-21).'                  -> None    (deliberate! see below)

    'COMPLETE' is NOT a keyword. REG-16's section reads 'TIER A COMPLETE' while the ITEM is still
    open (its remainder is tracked as REG-16-resid) -- treating that as closed would be a false
    positive, and a doc gate that cries wolf gets bypassed. Staying silent on phrasing we cannot
    prove is the deliberate trade: see the module docstring.
    """
    upper = text.upper()
    best: tuple[int, str] | None = None
    for word, verdict in [(w, "closed") for w in CLOSED_WORDS] + [(w, "open") for w in OPEN_WORDS]:
        at = upper.find(word)
        if at >= 0 and (best is None or at < best[0]):
            best = (at, verdict)
    return best[1] if best else None


def parse(path: Path, mode: str) -> tuple[dict[str, tuple[int, str]], dict[str, tuple[int, str]], set[str]]:
    """Returns (summary rows, detail statuses, ids that HAVE a detail heading).

    The third value separates two very different kinds of skip: an item with no detail section at
    all is single-source and uninteresting, while an item that HAS a section whose status could not
    be parsed is where drift can still hide -- that one is worth surfacing.
    """
    summary: dict[str, tuple[int, str | None]] = {}
    detail: dict[str, tuple[int, str]] = {}
    sectioned: set[str] = set()
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()

    pending_id: str | None = None
    pending_line = 0
    for number, line in enumerate(lines, start=1):
        row = SUMMARY_ROW.match(line)
        if row:
            item = row.group("id")
            # First occurrence wins: the index table precedes any later per-round tables.
            summary.setdefault(item, (number, row_verdict(line, bool(row.group("struck")), mode)))
            continue

        heading = DETAIL_HEADING.match(line)
        if heading:
            pending_id = heading.group("id")
            pending_line = number
            sectioned.add(pending_id)
            continue

        if pending_id:
            found = STATUS_LINE.search(line)
            if found:
                verdict = classify(found.group("status"))
                if verdict:
                    detail.setdefault(pending_id, (pending_line, verdict))
                pending_id = None
            elif line.startswith("#"):
                pending_id = None  # next heading arrived before any Status line
    return summary, detail, sectioned


def row_verdict(line: str, struck: bool, mode: str) -> str | None:
    """How this document declares a summary row's status.

    'strikethrough' (NPDEV_OPEN_ITEMS_REGISTER.md): a struck id means closed, unstruck means open --
        the convention the register already uses.
    'status-cell' (OPEN_GAPS_AND_ROADMAP.md): an explicit status column; the first cell that
        classifies wins. Returns None when no cell is conclusive, so the row is skipped rather than
        guessed at.
    """
    if mode == "strikethrough":
        return "closed" if struck else "open"
    for cell in line.split("|")[2:]:  # skip the leading empty split and the id cell
        verdict = classify(cell.strip())
        if verdict:
            return verdict
    return None


def check(path: Path, mode: str, verbose: bool) -> list[str]:
    summary, detail, sectioned = parse(path, mode)
    problems: list[str] = []
    unparseable: list[str] = []
    single_source = 0
    ok = 0

    for item, (summary_line, declared) in sorted(summary.items()):
        if item not in detail:
            if item in sectioned:
                # Has a detail section, but its Status phrasing is not one this check can prove.
                # Surfaced (not silent) because drift can still hide here.
                unparseable.append(
                    f"  UNPARSEABLE {item}: summary row line {summary_line} has a detail section, "
                    f"but its **Status:** phrasing is not conclusive -- verify this one by hand"
                )
            else:
                single_source += 1  # only appears once (e.g. a findings-table row): nothing to contradict
            continue
        detail_line, verdict = detail[item]
        if declared is None:
            single_source += 1  # this document's row carries no conclusive status
            continue
        how = "not struck through" if mode == "strikethrough" else "its status cell says open"
        fix_closed = (
            f"strike the id (~~**{item}**~~) and put the closure date in the description"
            if mode == "strikethrough"
            else "update the status cell to match"
        )
        if verdict == "closed" and declared == "open":
            problems.append(
                f"{path.name}:{summary_line}: {item} reads as OPEN in the summary table ({how}) "
                f"but its detail section at line {detail_line} says CLOSED. Fix: {fix_closed}."
            )
        elif verdict == "open" and declared == "closed":
            problems.append(
                f"{path.name}:{summary_line}: {item} reads as CLOSED in the summary table but its "
                f"detail section at line {detail_line} still says open. Fix: update whichever is "
                f"stale -- check the dates on both before deciding which one is current."
            )
        else:
            ok += 1

    print(
        f"  {path.name}: {ok} cross-checked OK, {len(problems)} contradiction(s), "
        f"{len(unparseable)} unparseable, {single_source} single-source"
    )
    if verbose:
        for note in unparseable:
            print(note)
    elif unparseable:
        print(f"    ({len(unparseable)} item(s) need a hand check -- re-run with --verbose to list them)")
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    parser.add_argument("--verbose", action="store_true", help="also print consistent/skipped counts")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    # Each document declares status its own way -- made explicit rather than guessed.
    targets = [
        (root / "docs" / "NPDEV_OPEN_ITEMS_REGISTER.md", "strikethrough"),
        (root / "docs" / "OPEN_GAPS_AND_ROADMAP.md", "status-cell"),
    ]

    print("Register consistency check (summary rows vs their own detail sections)")
    all_problems: list[str] = []
    for target, mode in targets:
        if not target.exists():
            print(f"ERROR: missing document: {target}", file=sys.stderr)
            return 2
        all_problems.extend(check(target, mode, args.verbose))

    if all_problems:
        print(f"\nFAIL: {len(all_problems)} row(s) contradict their own detail section:\n", file=sys.stderr)
        for problem in all_problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nWhy this matters: knowledge/platform-status.json is DERIVED from these documents, "
            "so a stale summary row also poisons the AI knowledge substrate.",
            file=sys.stderr,
        )
        return 1

    print("OK: every summary row agrees with its detail section.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
