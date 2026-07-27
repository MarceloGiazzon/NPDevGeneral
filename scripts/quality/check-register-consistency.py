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
import json
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
# `### REG-36, REG-37, REG-41 — findings filed by ...` / `## 3.3 REG-18…REG-26 — ...`
# A heading naming SEVERAL items is a group heading, not any one item's detail section: there is no
# single **Status:** it could carry. Previously such a heading was treated as the detail section for
# whichever id came first, which then reported as UNPARSEABLE forever -- three of the nine items on
# ONE_PLAN's "unparseable status" list were this, not documentation drift.
GROUP_HEADING = re.compile(
    r"^#{2,4}\s+(?:[0-9]+(?:\.[0-9]+)*\s+)?"
    r"[A-Z][A-Za-z0-9]*-[A-Za-z0-9-]+\s*(?:,|…|\.\.\.)\s*[A-Z][A-Za-z0-9]*-[A-Za-z0-9-]+"
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
    awaiting_status_text = False
    for number, line in enumerate(lines, start=1):
        row = SUMMARY_ROW.match(line)
        if row:
            item = row.group("id")
            # First occurrence wins: the index table precedes any later per-round tables.
            summary.setdefault(item, (number, row_verdict(line, bool(row.group("struck")), mode)))
            continue

        if GROUP_HEADING.match(line):
            pending_id = None  # a heading covering several items: not any one item's section
            continue

        heading = DETAIL_HEADING.match(line)
        if heading:
            pending_id = heading.group("id")
            pending_line = number
            sectioned.add(pending_id)
            awaiting_status_text = False
            continue

        if pending_id:
            # `**Status:** DONE (...)` on one line, or `**Status:**` with the value wrapped onto the
            # next -- both are used in these documents, and reflowing prose to satisfy a parser is the
            # wrong way round. Four more of ONE_PLAN's nine "unparseable" items were only this.
            if awaiting_status_text:
                verdict = classify(line.strip()[:80])
                if verdict:
                    detail.setdefault(pending_id, (pending_line, verdict))
                    pending_id = None
                awaiting_status_text = False
                continue

            found = STATUS_LINE.search(line)
            if found:
                verdict = classify(found.group("status"))
                if verdict:
                    detail.setdefault(pending_id, (pending_line, verdict))
                    pending_id = None
                else:
                    awaiting_status_text = True  # value continues on the next line
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
    raw_lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
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
                # A single-source row has no detail section to contradict -- but in strikethrough mode
                # it can still contradict ITSELF: unstruck means "open" by convention, while its own
                # description text may say CLOSED/DONE. That is how REG-25/27/28/29/30 sat closed and
                # unstruck while this very script reported 0 contradictions (found 2026-07-25).
                if mode == "strikethrough" and declared == "open":
                    own = classify(raw_lines[summary_line - 1])
                    if own == "closed":
                        problems.append(
                            f"{path.name}:{summary_line}: {item} is not struck through (reads as OPEN) "
                            f"but its own description says CLOSED/DONE. Fix: strike the id "
                            f"(~~**{item}**~~), or correct the description if it is still open."
                        )
                        continue
                single_source += 1  # nothing to contradict
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


# Ledger-SHAPED documents that are deliberately not status-cross-checked, each with the reason.
# Anything else that looks like a ledger fails ledger_coverage_gaps() -- see its docstring.
LEDGER_EXCLUSIONS = {
    "LNCH1_CLOSEOUT_PLAN.md": "Executed plan (marked HISTORICAL). Its tables are task checklists, not a status ledger of record.",
    "LNCH1_PLATFORM_COLUMN_PLAN.md": "Executed plan (marked HISTORICAL). Same: task tables, not tracked items.",
    "REGISTER_CLOSURE_PLAN.md": "Executed plan (marked HISTORICAL). Tables restate register items; the register itself is the checked source.",
}

# Adversarial-review findings documents are excluded as a CLASS, not one by one: their tables are
# per-finding SEVERITY (F1..Fn -- CRITICAL/HIGH/MEDIUM/INFO), never open/closed status, and the
# tracked REG-nn rows those findings produce live in the register, which IS cross-checked. Excluding
# them by rule rather than by name means the next review round's findings document does not fail this
# gate the day it is written -- a gate that fires on correct new work is a gate people learn to skip.
LEDGER_EXCLUSION_PATTERNS = (
    re.compile(r"_ADVERSARIAL_REVIEW\.md$"),
)


def ledger_coverage_gaps(root: Path) -> list[str]:
    """Is the checked-document list still complete?

    Blind spot #5 was `LAUNCH_READINESS_GAPS.md` -- a third ledger with 24 rows and full detail
    sections that this script had simply never been pointed at. The rules were right; the SCOPE was
    silently short, and nothing checked it. Exactly the failure this script exists to prevent, one
    level up.

    So the document list is now an assertion rather than a constant: anything ledger-shaped (>=5 id
    rows AND >=1 detail heading) must be either checked or named in LEDGER_EXCLUSIONS with a reason.
    Add a new ledger and this fails until someone decides which it is.
    """
    checked = {"NPDEV_OPEN_ITEMS_REGISTER.md", "OPEN_GAPS_AND_ROADMAP.md", "LAUNCH_READINESS_GAPS.md"}
    gaps: list[str] = []
    for path in sorted((root / "docs").glob("*.md")):
        if path.name in checked or path.name in LEDGER_EXCLUSIONS:
            continue
        if any(rule.search(path.name) for rule in LEDGER_EXCLUSION_PATTERNS):
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        rows = sum(1 for line in lines if SUMMARY_ROW.match(line))
        heads = sum(1 for line in lines if DETAIL_HEADING.match(line))
        if rows >= 5 and heads >= 1:
            gaps.append(
                f"docs/{path.name} is ledger-shaped ({rows} id rows, {heads} detail headings) but is "
                f"neither cross-checked nor in LEDGER_EXCLUSIONS. Either add it to the target list "
                f"with its status convention, or exclude it with the reason it is not a status ledger."
            )
    return gaps


def mission_run_coverage_gaps(root: Path) -> list[str]:
    """ADR-0009 / P8: does every external-AI mission have a run record -- RUN or an explicit
    NOT_RUN reason?

    Same blind-spot shape every other check in this file exists to catch, one programme over: a
    mission with neither a run record nor a stated reason it hasn't run is indistinguishable from a
    mission nobody remembered, which is exactly how REG-16 sat at zero adversarial review long after
    Tier A and B were done. "Never checked" must never look the same as "checked, and here is why it
    was skipped." Silently absent (no file at all) is a gap here for that reason, not silence.

    A checkout without the external-AI review feature (missions.json absent) has nothing to check --
    this returns no gaps rather than erroring, the same way ledger_coverage_gaps only looks under
    docs/.
    """
    missions_file = root / "scripts" / "external-review" / "missions.json"
    if not missions_file.exists():
        return []
    runs_dir = root / "docs" / "external-ai-review" / "runs"

    missions = json.loads(missions_file.read_text(encoding="utf-8"))["missions"]
    gaps: list[str] = []
    for mission in missions:
        mission_id = mission["missionId"]
        run_file = runs_dir / f"{mission_id}.json"
        if not run_file.exists():
            gaps.append(
                f"mission {mission_id} (scripts/external-review/missions.json) has no run record at "
                f"docs/external-ai-review/runs/{mission_id}.json -- add one with runStatus RUN "
                f"(+ packManifestSha256 + verdictRecordKind/recordKind) or NOT_RUN (+ notRunReason)."
            )
            continue
        try:
            record = json.loads(run_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            gaps.append(f"docs/external-ai-review/runs/{mission_id}.json is not valid JSON: {exc}")
            continue
        status = record.get("runStatus")
        if status == "RUN":
            has_record_kind = record.get("verdictRecordKind") or record.get("recordKind")
            if not record.get("packManifestSha256") or not has_record_kind:
                gaps.append(
                    f"docs/external-ai-review/runs/{mission_id}.json says RUN but is missing "
                    f"packManifestSha256 or verdictRecordKind/recordKind -- see external-ai-run.schema.json."
                )
        elif status == "NOT_RUN":
            if not str(record.get("notRunReason", "")).strip():
                gaps.append(
                    f"docs/external-ai-review/runs/{mission_id}.json says NOT_RUN but notRunReason "
                    f"is blank -- state why, even if the reason is 'blocked on D3'."
                )
        else:
            gaps.append(
                f"docs/external-ai-review/runs/{mission_id}.json has runStatus '{status}', expected "
                f"RUN or NOT_RUN -- there is no third, silent option."
            )
    return gaps


def check_plan_status_banners(root: Path, verbose: bool) -> list[str]:
    """Every planning document must declare its tense in its first few lines.

    A plan whose work is finished but which still reads as a live backlog is the same drift class this
    script was written for, one document type over: someone opens `ONE_PLAN_CLOSE_EVERYTHING.md` and
    starts executing a programme that closed days ago, or re-does REG-17 because `FINAL_FOUR` still
    describes it as open. On 2026-07-25 that was 15 of 22 planning documents.

    The rule is deliberately NOT "detect whether the work is done" -- that is unknowable from the text
    and guessing it would be exactly the false-confidence this repo keeps paying for. It just requires
    the author to SAY, in one line, which of EXECUTED / ACTIVE / HISTORICAL / SUPERSEDED applies.
    Stating the tense costs a sentence; leaving it implicit costs a reader an hour.
    """
    problems: list[str] = []
    checked = 0
    for path in sorted((root / "docs").glob("*PLAN*.md")):
        head = "\n".join(path.read_text(encoding="utf-8", errors="replace").split("\n")[:8])
        checked += 1
        if "STATUS:" not in head:
            problems.append(
                f"docs/{path.name}: no `> **STATUS: …**` line in the first 8 lines. Add one saying "
                f"EXECUTED (work landed) / ACTIVE (live backlog) / HISTORICAL (unverified) / "
                f"SUPERSEDED (point at the replacement), so a reader knows whether to act on it."
            )
    print(f"  planning documents: {checked - len(problems)}/{checked} declare a status")
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
        # Added 2026-07-25 (blind spot #5): a third ledger with 24 rows and full detail sections that
        # nothing had ever cross-checked. Same status-cell convention as the roadmap.
        (root / "docs" / "LAUNCH_READINESS_GAPS.md", "status-cell"),
    ]

    print("Register consistency check (summary rows vs their own detail sections)")
    all_problems: list[str] = []
    for target, mode in targets:
        if not target.exists():
            print(f"ERROR: missing document: {target}", file=sys.stderr)
            return 2
        all_problems.extend(check(target, mode, args.verbose))
    all_problems.extend(check_plan_status_banners(root, args.verbose))
    # Coverage last so its message is not buried, but it is a HARD gap: a ledger nobody checks is the
    # same failure as a summary row nobody cross-checks.
    all_problems.extend(ledger_coverage_gaps(root))
    all_problems.extend(mission_run_coverage_gaps(root))

    if all_problems:
        print(f"\nFAIL: {len(all_problems)} tracking inconsistency(ies) — a summary row contradicting "
              f"its own detail section, or a plan not declaring its tense:\n", file=sys.stderr)
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
