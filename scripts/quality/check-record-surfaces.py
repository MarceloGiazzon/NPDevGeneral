#!/usr/bin/env python3
"""Record-surface staleness: two mechanical claims that go stale silently and nothing reads.

WHY THIS EXISTS -- docs/RECORD_SURFACES_PLAN.md, item P4
----------------------------------------------------------
The invocation-topology sweep (docs/INVOCATION_TOPOLOGY_PLAN.md) closed one class of bug: "a check
exists, nothing runs it." The 2026-07-29 sweep found a different class with the same repeat-offender
shape: **a surface of record goes stale after successful work, and nothing notices.** Four instances
of that class already have detectors (EXECUTION_TREES.md's Rule T1, the ledger's Rule T2b, plan
deferrals' T4, the narrative-status-drift checker). The two instances that did NOT have one are the
two surfaces a *stranger* meets first:

  1. `origin/main` silently drifted 71 commits behind the working branch (2026-07-29) -- the third
     recurrence (was 150 behind before T1.6). `main` is public and the default branch: it is what a
     clone gets, what GitHub renders, what a first impression is formed from.
  2. CLAUDE.md's own "Large files -- DO NOT full-read" block was wrong in three directions on the
     same day: two files it told every session to avoid had shrunk to ~12 KB (split by the 2.B
     decomposition), a third had grown 25 KB past its stated size, and a fourth 140 KB file was
     missing from the list entirely. CLAUDE.md is loaded into every session's context -- a stale
     claim there actively misdirects, it doesn't just sit unread.

THE TWO CHECKS (deliberately narrow -- see "what this does NOT do" below)
---------------------------------------------------------------------------
1. Branch freshness, both directions (docs/FAIL_OPEN_PLAN.md R4 added the second direction):
   - Ahead of `origin/main` (`git rev-list --count origin/main..HEAD`): WARN above 20 (a working
     branch is *meant* to run ahead), FAIL above 50 (that is a second unmerged release, not "ahead").
   - Behind `origin/main` (`git rev-list --count HEAD..origin/main`): WARN on any amount, never FAIL
     -- being briefly behind right after someone else merges is normal, but a stale local base is how
     an ahead-gap starts (build on it long enough and the next `git push` surprises you with commits
     you never saw). Found live 2026-07-29: `beta1-vision-spine` was 4 commits behind `origin/main`
     immediately after its own PR #7 merged -- the merge commit lands on `main`, never on the source
     branch, so "just merged" and "now behind" are the same moment unless the branch is synced back.
2. CLAUDE.md size claims: every `` `path` (N KB) `` entry in the "Large files" block is resolved
   against the file it names (the path may use a `.../` shorthand, e.g.
   `NPDevKernel/kernel/.../KernelRunner.java`, resolved via glob) and compared to its actual size on
   disk. FAIL if the claim is off by more than 25%, or if the named path resolves to zero or more
   than one file.

WHAT THIS DOES NOT DO
----------------------
It does not verify CLAUDE.md's prose is complete (e.g. that every large file is even *listed*) --
that is unbounded and belongs to a human editor, not a static check. Only the two claims that go
stale MECHANICALLY -- a byte count, a commit count -- are checked. This is the same boundary
check-narrative-status-drift.py drew for prose contradictions vs. the register's own row.

CALIBRATE BEFORE TRUSTING IT
------------------------------
    python scripts/quality/check-record-surfaces.py --calibrate

Six controls, all must behave as stated or the script exits 1:
  - Size-claim check against CLAUDE.md pinned at `27c984d` (the real commit immediately before this
    plan's P2 fix landed -- confirmed via `git show 27c984d:CLAUDE.md` to still carry the stale
    197KB/164KB claims for the now-12KB TrustedSourceEmitter/SemanticValidator) -- MUST fire.
  - Size-claim check against the CLAUDE.md in the working tree (post-P2) -- MUST NOT fire.
  - Branch-freshness against a synthetic 51-ahead/0-behind gap -- MUST fire (FAIL-shaped).
  - Branch-freshness against a synthetic 0-ahead/0-behind gap -- MUST NOT fire.
  - Branch-freshness against a synthetic 0-ahead/4-behind gap -- MUST fire (WARN-shaped, never FAILs).
  - Branch-freshness against a synthetic 0-ahead/0-behind gap (behind control) -- MUST NOT fire.

Same discipline as check-narrative-status-drift.py's ADR-0009 control: pin to a fixed SHA, not HEAD,
so the control keeps proving something after CLAUDE.md is edited again.

USAGE
-----
    python scripts/quality/check-record-surfaces.py            # check the real repo, exit 1 on drift
    python scripts/quality/check-record-surfaces.py --calibrate  # self-test, exit 1 on failure

Unlike check-narrative-status-drift.py, this is BLOCKING by design (per RECORD_SURFACES_PLAN.md P4's
DoD: "both assertions implemented and wired blocking") -- both checks are mechanical byte/commit
counts with a tolerance band, not a prose heuristic, so the false-positive risk that justified
report-only for narrative drift does not apply here.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent

BRANCH_WARN = 20
BRANCH_FAIL = 50
SIZE_TOLERANCE = 0.25

LARGE_FILES_HEADING = re.compile(r"^##\s+Large files")
HEADING = re.compile(r"^#{1,6}\s")
LARGE_FILE_ENTRY = re.compile(r"`([^`]+\.[A-Za-z0-9]+)`\s*\((\d+(?:\.\d+)?)\s*K[Bb]\)")


def resolve_abbrev_path(root: Path, abbrev: str) -> list[Path]:
    """CLAUDE.md abbreviates long paths with a literal `/.../` segment, e.g.
    `NPDevKernel/kernel/.../KernelRunner.java`. Turn that into a glob and resolve it against the
    repo root. Returns every match -- callers decide what zero or more-than-one means."""
    if "/.../" in abbrev:
        prefix, suffix = abbrev.split("/.../", 1)
        pattern = f"{prefix}/**/{suffix}"
    else:
        pattern = abbrev
    try:
        return sorted(root.glob(pattern))
    except ValueError:
        return []


def check_size_claims(root: Path, claude_md_text: str) -> list[str]:
    findings: list[str] = []
    in_block = False
    for line in claude_md_text.splitlines():
        if LARGE_FILES_HEADING.match(line):
            in_block = True
            continue
        if in_block and HEADING.match(line):
            break
        if not in_block:
            continue
        m = LARGE_FILE_ENTRY.search(line)
        if not m:
            continue
        abbrev_path, claimed_kb_str = m.group(1), m.group(2)
        claimed_kb = float(claimed_kb_str)
        matches = resolve_abbrev_path(root, abbrev_path)
        if len(matches) == 0:
            findings.append(f"CLAUDE.md large-files block: `{abbrev_path}` does not resolve to any file on disk")
            continue
        if len(matches) > 1:
            findings.append(
                f"CLAUDE.md large-files block: `{abbrev_path}` is ambiguous -- resolves to "
                f"{len(matches)} files: {', '.join(str(p.relative_to(root)) for p in matches)}"
            )
            continue
        actual_kb = matches[0].stat().st_size / 1024
        if claimed_kb <= 0:
            continue
        drift = abs(actual_kb - claimed_kb) / claimed_kb
        if drift > SIZE_TOLERANCE:
            findings.append(
                f"CLAUDE.md large-files block: `{abbrev_path}` claimed {claimed_kb:.0f} KB, actual "
                f"{actual_kb:.0f} KB ({drift:.0%} drift, tolerance {SIZE_TOLERANCE:.0%})"
            )
    return findings


def branch_gap(root: Path) -> tuple[int, int]:
    subprocess.run(["git", "fetch", "origin", "main"], cwd=root, capture_output=True, text=True)
    ahead = int(subprocess.run(
        ["git", "rev-list", "--count", "origin/main..HEAD"],
        cwd=root, capture_output=True, text=True, check=True,
    ).stdout.strip())
    behind = int(subprocess.run(
        ["git", "rev-list", "--count", "HEAD..origin/main"],
        cwd=root, capture_output=True, text=True, check=True,
    ).stdout.strip())
    return ahead, behind


def check_branch_freshness(ahead: int, behind: int) -> list[str]:
    findings = []
    if ahead > BRANCH_FAIL:
        findings.append(
            f"branch is {ahead} commits ahead of origin/main -- exceeds the FAIL threshold ({BRANCH_FAIL}); "
            f"this is a second unmerged release, not \"ahead\" -- merge forward"
        )
    elif ahead > BRANCH_WARN:
        findings.append(
            f"WARNING: branch is {ahead} commits ahead of origin/main -- exceeds the WARN threshold "
            f"({BRANCH_WARN}) but not the FAIL threshold ({BRANCH_FAIL})"
        )
    if behind > 0:
        findings.append(
            f"WARNING: branch is {behind} commit(s) behind origin/main -- pull before building further "
            f"on top (never FAILs: being briefly behind right after a merge is normal)"
        )
    return findings


def calibrate(root: Path) -> int:
    ok = True

    def report(label: str, findings: list[str], expect_fire: bool) -> None:
        nonlocal ok
        fired = bool(findings)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for f in findings:
            print(f"           {f}")

    print("Calibration -- must catch the real 2026-07-29 CLAUDE.md size drift and a synthetic branch gap:")

    PRE_FIX_SHA = "27c984d"
    try:
        pre_fix_text = subprocess.run(
            ["git", "show", f"{PRE_FIX_SHA}:CLAUDE.md"],
            cwd=root, capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError as exc:
        print(f"  ERROR: could not read {PRE_FIX_SHA}:CLAUDE.md: {exc.stderr}", file=sys.stderr)
        return 1
    report(f"size-claim check vs. CLAUDE.md @ {PRE_FIX_SHA} (real pre-P2 revision)",
           check_size_claims(root, pre_fix_text), expect_fire=True)

    working_text = (root / "CLAUDE.md").read_text(encoding="utf-8")
    report("size-claim check vs. CLAUDE.md in the working tree (post-P2)",
           check_size_claims(root, working_text), expect_fire=False)

    report("branch-freshness vs. synthetic 51-ahead/0-behind gap",
           check_branch_freshness(51, 0), expect_fire=True)
    report("branch-freshness vs. synthetic 0-ahead/0-behind gap",
           check_branch_freshness(0, 0), expect_fire=False)
    report("branch-freshness vs. synthetic 0-ahead/4-behind gap (R4: warn-on-behind)",
           check_branch_freshness(0, 4), expect_fire=True)
    report("branch-freshness vs. synthetic 0-ahead/0-behind gap (behind control)",
           check_branch_freshness(0, 0), expect_fire=False)

    if not ok:
        print("\nFAIL: at least one control did not behave as required -- this detector does not "
              "ship until it does.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(_REPO_ROOT), help="repo root (default: this script's repo)")
    parser.add_argument("--calibrate", action="store_true", help="run the four required controls and exit")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    if args.calibrate:
        return calibrate(root)

    print("Record-surface staleness (branch freshness + CLAUDE.md size claims)")
    total_blocking = 0

    claude_md = root / "CLAUDE.md"
    size_findings = check_size_claims(root, claude_md.read_text(encoding="utf-8"))
    for f in size_findings:
        print(f"  {f}")
    total_blocking += len(size_findings)

    ahead, behind = branch_gap(root)
    branch_findings = check_branch_freshness(ahead, behind)
    for f in branch_findings:
        print(f"  {f}")
    if ahead > BRANCH_FAIL:
        total_blocking += 1

    if total_blocking == 0 and not any(f.startswith("WARNING") for f in branch_findings):
        print("  (none found)")
    print(f"\n{total_blocking} blocking finding(s).")
    return 1 if total_blocking > 0 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
