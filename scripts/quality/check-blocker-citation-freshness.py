#!/usr/bin/env python3
"""Blocker-citation freshness: does a doc still call REG-nnn a blocker after REG-nnn is DONE?

WHY THIS EXISTS -- Move 15 Phase D item D1
-------------------------------------------
Five separate times a console/screen record said "blocked by X" (or "X (open ...)") while X had
already been closed in a LATER move, and docs/SCREEN_TAXONOMY.md itself sat four moves stale before
anyone noticed by re-reading it, not by any gate. `check-narrative-status-drift.py` and
`check-record-surfaces.py` exist but check different shapes (a sentence's own asserted status vs. a
file-size/branch-freshness claim) -- neither one asks "is a cited BLOCKER's ledger status still
accurate?" This is that check, for that one specific shape.

THE RULE
--------
Fail when a doc asserts something is blocked/open BY REG-nnn, and REG-nnn's OWN ledger row
(ledger/items/REG-nnn.yml `status:`) is DONE (or PARTIAL, which is not a live blocker either --
distinguished from OPEN in the finding text).

Two independent, deliberately narrow patterns (a citation must match one of these, not just mention
a REG-id near any blocking-sounding word, to keep the false-positive rate low per lesson #4, "a gate
that cries wolf gets bypassed"):
  1. An explicit inline status annotation this repo's own move-checklist convention already uses:
     "REG-92 (open ..." / "REG-92 (blocked ...".
  2. A "blocked by REG-nn" / "REG-nn ... blocked" / "blocker is REG-nn" phrase, on the SAME LINE as
     the id -- suppressed if a resolved-marker (closed, fixed, resolved, done, no longer, closes)
     ALSO appears on that line, which is legitimate self-correcting narration
     ("REG-75 ... both of which closed in Moves 4-5"), not a live claim.

SCOPE (narrow to start, per this item's own instruction -- widen later if it earns it)
----------------------------------------------------------------------------------------
    docs/SCREEN_TAXONOMY.md
    docs/MOVE*_CHECKLISTS.md
    docs/MOVE*_FINDINGS.md
    docs/MOVE1_PANEL_GAPS.md

CALIBRATE BEFORE TRUSTING IT (non-negotiable, same discipline check-narrative-status-drift.py uses)
------------------------------------------------------------------------------------------------------
    python scripts/quality/check-blocker-citation-freshness.py --calibrate

Runs two synthetic controls and prints PASS/FAIL for each, exiting 1 if either fails:
  - A doc citing a REG id as a live blocker, where that id's ledger row is DONE -- MUST fire.
  - The corrected variant (citation removed, or the id's own row is genuinely still OPEN) --
    MUST NOT fire.

USAGE
-----
    python scripts/quality/check-blocker-citation-freshness.py            # blocking: exit 1 on any finding
    python scripts/quality/check-blocker-citation-freshness.py --calibrate  # self-test
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LEDGER_DIR = REPO_ROOT / "ledger" / "items"

SCOPE_GLOBS = (
    "docs/SCREEN_TAXONOMY.md",
    "docs/MOVE1_PANEL_GAPS.md",
    "docs/MOVE*_CHECKLISTS.md",
    "docs/MOVE*_FINDINGS.md",
)

REG_ID = re.compile(r"\bREG-(\d+)\b")

# Pattern 1: this repo's own "REG-nn (open ..." / "REG-nn (blocked ..." inline-annotation convention
# (e.g. "REG-91 (open -- a claim table with NOT NULL columns ...)", "REG-89 (fixed -- ...)").
INLINE_ANNOTATION = re.compile(r"\bREG-(\d+)\s*\(\s*(open|blocked)\b", re.IGNORECASE)

# Pattern 2: an explicit "blocked by REG-nn" / "REG-nn ... blocked" / "blocker is REG-nn" phrase.
BLOCKED_BY = re.compile(r"blocked\s+by\s+(?:the\s+)?(?:already-named\s+)?REG-(\d+)", re.IGNORECASE)
NAMED_AS_BLOCKED = re.compile(r"REG-(\d+)\s+(?:already\s+)?named\s+as\s+blocked", re.IGNORECASE)
BLOCKER_IS = re.compile(r"(?:the\s+)?blocker\s+is\s+REG-(\d+)", re.IGNORECASE)

RESOLVED_MARKERS = ("closed", "fixed", "resolved", "done", "no longer", "closes ", "not blocked")


def has_resolved_marker(line: str) -> bool:
    lower = line.lower()
    return any(marker in lower for marker in RESOLVED_MARKERS)


def load_ledger_statuses() -> dict[str, str]:
    """REG-nn -> its own ledger row's status: value, upper-cased. Missing files are simply absent
    from the map (a citation to an id with no ledger file at all is not this checker's problem)."""
    statuses: dict[str, str] = {}
    if not LEDGER_DIR.is_dir():
        return statuses
    status_line = re.compile(r"^status:\s*(\S+)", re.MULTILINE)
    for path in LEDGER_DIR.glob("REG-*.yml"):
        item_id = path.stem.upper()
        text = path.read_text(encoding="utf-8", errors="replace")
        m = status_line.search(text)
        if m:
            statuses[item_id] = m.group(1).strip().upper()
    return statuses


def scoped_documents(root: Path) -> list[Path]:
    docs_dir = root / "docs"
    if not docs_dir.is_dir():
        return []
    found: dict[Path, None] = {}
    for pattern in SCOPE_GLOBS:
        for path in root.glob(pattern):
            if path.is_file():
                found[path] = None
    return sorted(found.keys())


def citations_in_line(line: str) -> set[str]:
    """REG ids this line cites as a LIVE blocker, per the two patterns above -- empty set if the
    line doesn't match either pattern, or matches but also carries a resolved-marker suppression."""
    ids: set[str] = set()
    for m in INLINE_ANNOTATION.finditer(line):
        ids.add("REG-" + m.group(1))
    for pattern in (BLOCKED_BY, NAMED_AS_BLOCKED, BLOCKER_IS):
        for m in pattern.finditer(line):
            ids.add("REG-" + m.group(1))
    if not ids:
        return set()
    if has_resolved_marker(line):
        return set()
    return ids


def check_document(path: Path, statuses: dict[str, str], root: Path) -> list[str]:
    findings: list[str] = []
    rel = path.relative_to(root).as_posix()
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for line_number, line in enumerate(lines, start=1):
        for item_id in sorted(citations_in_line(line)):
            status = statuses.get(item_id)
            if status in ("DONE", "PARTIAL"):
                findings.append(
                    f"{rel}:{line_number}: cites {item_id} as a live blocker, but {item_id}'s own "
                    f"ledger row says {status} -- stale citation. Line: \"{line.strip()[:180]}\""
                )
    return findings


def run(root: Path) -> tuple[int, list[str]]:
    statuses = load_ledger_statuses()
    findings: list[str] = []
    for path in scoped_documents(root):
        findings.extend(check_document(path, statuses, root))
    return len(findings), findings


SYNTHETIC_STALE = """\
## Wizard 3 -- assessed, not attempted (blocked by REG-999999)
"""

SYNTHETIC_FIXED = """\
## Wizard 3 -- assessed and closed (REG-999999 was blocked, now closed)
"""


def calibrate() -> int:
    ok = True

    def report(label: str, findings: list[str], expect_fire: bool) -> None:
        nonlocal ok
        fired = bool(findings)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for f in findings:
            print(f"           {f}")

    print("Calibration -- must catch a synthetic stale blocker citation, and stay quiet once it is")
    print("corrected or the cited id is genuinely still open:")

    fake_done_statuses = {"REG-999999": "DONE"}
    fake_open_statuses = {"REG-999999": "OPEN"}

    stale_lines = SYNTHETIC_STALE.splitlines()
    fixed_lines = SYNTHETIC_FIXED.splitlines()

    def findings_for(lines: list[str], statuses: dict[str, str]) -> list[str]:
        out: list[str] = []
        for number, line in enumerate(lines, start=1):
            for item_id in sorted(citations_in_line(line)):
                status = statuses.get(item_id)
                if status in ("DONE", "PARTIAL"):
                    out.append(f"<synthetic>:{number}: cites {item_id} but status is {status}")
        return out

    report(
        "synthetic 'blocked by REG-999999' fixture, REG-999999 marked DONE",
        findings_for(stale_lines, fake_done_statuses),
        expect_fire=True,
    )
    report(
        "same fixture, REG-999999 genuinely still OPEN",
        findings_for(stale_lines, fake_open_statuses),
        expect_fire=False,
    )
    report(
        "corrected fixture ('closed' marker present) even though REG-999999 is DONE",
        findings_for(fixed_lines, fake_done_statuses),
        expect_fire=False,
    )

    if not ok:
        print("\nFAIL: at least one control did not behave as required -- this detector does not "
              "ship until it does.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly. Safe to run against the real corpus.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(REPO_ROOT), help="repo root (default: this repo)")
    parser.add_argument("--calibrate", action="store_true", help="run the required self-test controls and exit")
    args = parser.parse_args(argv)

    if args.calibrate:
        return calibrate()

    root = Path(args.root).resolve()
    print("Blocker-citation freshness (does a doc still call REG-nnn a blocker after it shipped?)")
    count, findings = run(root)
    for f in findings:
        print(f"  {f}")
    if count == 0:
        print("OK: no doc in scope cites a REG id as a live blocker whose own ledger row is DONE/PARTIAL.")
        return 0
    print(f"\nFAIL: {count} stale blocker citation(s) found -- update the doc (name the fix / move that "
          "closed it) or, if the doc is meant to be a frozen historical record, exclude it from this "
          "checker's scope explicitly (never silently).")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
