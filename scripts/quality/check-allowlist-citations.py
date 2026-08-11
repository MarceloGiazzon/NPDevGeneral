#!/usr/bin/env python3
"""Allowlist governance: report every allowlist's size, and require a REG-nn/B-nn citation on new
entries in the allowlists whose own convention already promises one.

WHY THIS EXISTS -- docs/FAIL_OPEN_PLAN.md R3
-----------------------------------------------
Every allowlist that has shipped so far carried an empty `"cleared": {}` and the stated invariant
"never pre-clear speculatively" -- an empty allowlist is self-policing, any entry stands out by
existing at all. REG-69 (2026-07-29) briefly broke that invariant for the first time
(`dsl-coverage-allowlist.json` gained 3 entries), which is what exposed the actual gap: nothing
previously asserted the entries that DO get added carry the citation their own `_comment` header
already promises, and nothing summarized allowlist size anywhere a human would notice it growing.
(REG-69's own entries were re-fixtured for real the same day -- R2 -- so the allowlist in question
is empty again; the governance gap this script closes is real regardless of that outcome.)

SCOPE -- three files enforced, two reported only
--------------------------------------------------
`corpus-parse-allowlist.json`, `test-task-coverage-allowlist.json` and `dsl-coverage-allowlist.json`
each already state in their own `_comment` header: "Add an entry here only for a genuine, reviewed
exception with a REG id." That promise was never machine-checked. This script enforces it: every
entry's `why` must contain a `REG-nn` or `B-nn` citation, or the check fails. All three are empty
today, so this is a zero-grandfathering, zero-risk rule to turn on now, before the next entry lands.

(`query-predicate-allowlist.json` (LC-P0) briefly joined this set, then was deleted in Move 12 P1.4
(REG-101) once its one entry's underlying defect was fixed and the checker it exempted entries for --
`check-query-predicate-compilable.py` -- was retired in favor of the same grammar running at DSL
authoring-time validation.)

`security-pattern-sweep-allowlist.json` (281 entries) uses a DIFFERENT, already-established
convention -- a `docs/SECURITY_PATTERN_SWEEP_2026-07.md` cross-reference, not shaped like
`REG-nn`/`B-nn`. Retrofitting a citation requirement onto it is explicitly NOT attempted here
(docs/FAIL_OPEN_PLAN.md R3's own scope note: "not proposed: auditing the 281 security entries") --
this script only REPORTS its count, so growth is visible without demanding anyone re-read its
existing rows.

(`plan-deferral-citation-allowlist.json` was this section's other REPORT_ONLY member until
md-zero-2026-08-11 PLAN.md Phase 2 deleted it: its one consumer, check-register-consistency.py's
check_plan_deferral_citations rule, was itself deleted along with that script, so the allowlist had
no rule left to exempt anything from.)

CALIBRATE BEFORE TRUSTING IT
------------------------------
    python scripts/quality/check-allowlist-citations.py --calibrate

Two controls: a synthetic entry in an enforced-shape allowlist with no REG/B citation in `why` --
MUST fire; the same entry with a `REG-1` citation -- MUST NOT fire.

USAGE
-----
    python scripts/quality/check-allowlist-citations.py            # check + report, exit 1 on drift
    python scripts/quality/check-allowlist-citations.py --calibrate  # self-test, exit 1 on failure
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent

CITATION_RE = re.compile(r"\b(?:REG|B)-\d+\b")

# Files whose own _comment header already promises a REG id -- enforced.
ENFORCED = (
    "corpus-parse-allowlist.json",
    "scripts/quality/test-task-coverage-allowlist.json",
    "scripts/quality/dsl-coverage-allowlist.json",
)

# Files with an established, different citation convention -- counted, never enforced here.
REPORT_ONLY = (
    "scripts/quality/security-pattern-sweep-allowlist.json",
)


def load_cleared(root: Path, rel_path: str) -> dict:
    path = root / rel_path
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return data.get("cleared", {})


def check_citations(cleared: dict) -> list[str]:
    findings = []
    for key, entry in cleared.items():
        why = entry.get("why", "") if isinstance(entry, dict) else ""
        if not why.strip():
            findings.append(f"entry '{key}': missing a 'why'")
            continue
        if not CITATION_RE.search(why):
            findings.append(f"entry '{key}': 'why' has no REG-nn or B-nn citation: {why[:100]!r}")
    return findings


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

    print("Calibration -- must catch a missing REG/B citation, pass a present one:")
    report("synthetic entry with no citation",
           check_citations({"synthetic": {"why": "Some plausible-sounding reason with no id at all."}}),
           expect_fire=True)
    report("synthetic entry citing REG-1",
           check_citations({"synthetic": {"why": "Reasoned, see REG-1 for the investigation."}}),
           expect_fire=False)
    report("synthetic entry citing B-1",
           check_citations({"synthetic": {"why": "Deliberate, see B-1 for the boundary."}}),
           expect_fire=False)
    report("synthetic entry with an empty why",
           check_citations({"synthetic": {"why": ""}}),
           expect_fire=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(_REPO_ROOT), help="repo root (default: this script's repo)")
    parser.add_argument("--calibrate", action="store_true", help="run the required controls and exit")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    if args.calibrate:
        return calibrate()

    print("Allowlist governance (docs/FAIL_OPEN_PLAN.md R3)")
    counts = []
    total_blocking = 0

    for rel_path in ENFORCED:
        cleared = load_cleared(root, rel_path)
        name = Path(rel_path).name
        counts.append(f"{name} {len(cleared)}")
        findings = check_citations(cleared)
        for f in findings:
            print(f"  {name}: {f}")
        total_blocking += len(findings)

    for rel_path in REPORT_ONLY:
        cleared = load_cleared(root, rel_path)
        name = Path(rel_path).name
        counts.append(f"{name} {len(cleared)} (report-only, established convention)")

    print(f"\nallowlists: {', '.join(counts)}")
    print(f"\n{total_blocking} blocking finding(s).")
    return 1 if total_blocking > 0 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
