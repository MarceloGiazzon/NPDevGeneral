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
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# Only user of PyYAML in this script (load_ledger_items, feeding Rule T1) -- see ledger/README.md
# and scripts/requirements.txt. ai-knowledge-gate.yml installs it before running this script.
import yaml

# A closed item, however its section words it.
# FIXED added 2026-07-29 (docs/REMEDIATION_PLAN.md R-P1): REG-33's own Status line reads "FIXED
# (2026-07-24)" -- a real miss found while building Rule T3 (since retired) tried to cross-check
# every sectioned item's status for the first time, proving the word list too narrow. Per this
# module's own docstring: tighten on a real miss, never loosen after a false alarm.
CLOSED_WORDS = ("CLOSED", "DONE", "WONTFIX", "ACHIEVED", "RETIRED", "LIFTED", "FIXED")
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
    'status-cell' (LAUNCH_READINESS_GAPS.md; OPEN_GAPS_AND_ROADMAP.md before docs-decoupling-2026-08-11
        PLAN.md Phase 1 moved it to LEDGER_EXCLUSIONS): an explicit status column; the first cell that
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
#
# docs/REMEDIATION_PLAN.md R-P2 (2026-07-29): LNCH1_CLOSEOUT_PLAN.md, LNCH1_PLATFORM_COLUMN_PLAN.md,
# and REGISTER_CLOSURE_PLAN.md were archived to docs/archive/programme-history/ (this sweep only
# scans docs/*.md, not subdirectories, so their exclusion entries are now unreachable) -- removed
# rather than left as dead entries nothing can ever match again.
LEDGER_EXCLUSIONS = {
    "FRONTEND_STRATEGY_PLAN.md": "Proposed, not-yet-started roadmap (STATUS: ACTIVE, F1-F6 gated on scheduling). Its F1..F6 table is an effort/priority estimate, not a status ledger of tracked open/closed items -- there is nothing yet to cross-check a detail section against.",
    "OPEN_ITEMS.md": "2.E ledger migration COMPLETE (docs/REMEDIATION_PLAN.md R-P1, 2026-07-29; ledger/README.md). The authoritative source-of-truth projection of ledger/items/*.yml, not hand-editable prose -- a summary-vs-detail contradiction is structurally impossible (both come from the SAME single `status` field in the same YAML file, rendered by the same script). Its own drift check is `python scripts/quality/generate_open_items.py --check` (exact-byte comparison against the source YAML), a stronger guarantee than this script's regex-based cross-check. Excluded here, not added to `checked`, permanently -- not a migration-in-progress artifact.",
    "X0_SILENT_EXPRESSION_REGISTER.md": "MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.2's silent-expression audit. Its table is a per-evaluator VERDICT (OPEN / **FIXED** -- REG-nn / ACCEPTED / CLEAN), never a tracked open/closed status of its OWN -- the same shape as the `_ADVERSARIAL_REVIEW.md` class this script already excludes by pattern. Every row that names a real fix routes through the ledger (REG-95, REG-96, REG-99, REG-100) or the programme doc (RC-B1, Wave 0.3), which ARE cross-checked; this register's own rows are not a second parallel status source to keep in sync.",
    "OPEN_GAPS_AND_ROADMAP.md": "docs-decoupling-2026-08-11 PLAN.md Phase 1: generated from ledger/gaps.yml (scripts/docs/generate_gaps_roadmap.py), the same OPEN_ITEMS.md discipline immediately above -- a summary-vs-detail contradiction is structurally impossible once both the Priority-index row and any prose citing that id's status render from the SAME `statusRaw` field in the same YAML file. Its own drift check is `python scripts/docs/generate_gaps_roadmap.py --check`. Excluded here, not added to `checked`, permanently -- not a migration-in-progress artifact.",
}

# Adversarial-review findings documents are excluded as a CLASS, not one by one: their tables are
# per-finding SEVERITY (F1..Fn -- CRITICAL/HIGH/MEDIUM/INFO), never open/closed status, and the
# tracked REG-nn rows those findings produce live in the register, which IS cross-checked. Excluding
# them by rule rather than by name means the next review round's findings document does not fail this
# gate the day it is written -- a gate that fires on correct new work is a gate people learn to skip.
LEDGER_EXCLUSION_PATTERNS = (
    re.compile(r"_ADVERSARIAL_REVIEW\.md$"),
)


class EmptyScopeError(RuntimeError):
    pass


def ledger_coverage_gaps(root: Path) -> list[str]:
    """Is the checked-document list still complete?

    Blind spot #5 was `LAUNCH_READINESS_GAPS.md` -- a third ledger with 24 rows and full detail
    sections that this script had simply never been pointed at. The rules were right; the SCOPE was
    silently short, and nothing checked it. Exactly the failure this script exists to prevent, one
    level up.

    So the document list is now an assertion rather than a constant: anything ledger-shaped (>=5 id
    rows AND >=1 detail heading) must be either checked or named in LEDGER_EXCLUSIONS with a reason.
    Add a new ledger and this fails until someone decides which it is.

    Rule 1 (docs-decoupling-2026-08-11 PLAN.md Phase 0): the `docs/*.md` scan itself must find SOME
    candidates, or this whole function is silently checking nothing -- a false PASS from an emptied
    docs/ root, not a real all-clear. Measured 2026-08-11: 85 files scanned.
    """
    all_docs = sorted((root / "docs").glob("*.md"))
    if not all_docs:
        raise EmptyScopeError(
            "ledger_coverage_gaps(): docs/*.md matched 0 files -- this would report a false PASS "
            "having scanned nothing. Re-point the scan at the new location of these documents, or "
            "delete this rule outright, before letting it run on an empty set (Rule 1)."
        )
    checked = {"NPDEV_OPEN_ITEMS_REGISTER.md", "LAUNCH_READINESS_GAPS.md"}
    gaps: list[str] = []
    for path in all_docs:
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


def provenance_audit_gaps(root: Path) -> list[str]:
    """ADR-0009 / REG-51 residual (`docs/archive/programme-history/REMAINDER_CLOSURE_PLAN.md` §3.4): defence-in-depth BEHIND
    the build-time refusal `build-review-pack.py`'s `resolve_provenance()` already enforces.

    That refusal stops a NEW pack from ever being built against stale generated-app output -- the
    exact class of false positive REG-49 turned out to be. This instead re-audits EXISTING run
    records (`docs/external-ai-review/runs/*.json`, tracked in the repo) against their backing pack
    file, when that evidence still happens to be on disk: packs are evidence, kept OUTSIDE the repo
    at `<repo>__OutsideRepo/external-ai-review/packs/<missionId>/<packManifestSha256>.json`, not
    guaranteed to survive indefinitely or be present on every checkout.

    A run record whose pack file is NOT found locally is never flagged: an absent file is not proof
    of anything wrong, and treating it as one would manufacture exactly the false-positive class
    this project's own lesson #4 warns against ("a gate that cries wolf gets bypassed"). Only a pack
    that IS found and reads `source.stale: true`, or is `source.kind: "generated-app"` without
    `provenanceVerified: true`, is a real finding: the run record's own verdict may be untrustworthy.
    """
    runs_dir = root / "docs" / "external-ai-review" / "runs"
    if not runs_dir.is_dir():
        return []
    packs_dir = root.parent / f"{root.name}__OutsideRepo" / "external-ai-review" / "packs"
    gaps: list[str] = []
    for run_file in sorted(runs_dir.glob("*.json")):
        try:
            record = json.loads(run_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue  # already reported by mission_run_coverage_gaps
        if record.get("runStatus") != "RUN":
            continue
        # Same discipline as mission_run_coverage_gaps' notRunReason: a limitation is not a gap once
        # it is disclosed in the TRACKED record itself, not only in external (not-guaranteed-present)
        # pack evidence -- that disclosure is the actual fix for the blind spot (info sitting in one
        # place, checked in another). Simple substring match, not a new enum: this mirrors the
        # existing note field's own free-text convention rather than inventing a stricter one.
        if "provenance" in str(record.get("note", "")).lower():
            continue
        mission_id = record.get("missionId", run_file.stem)
        pack_hash = record.get("packManifestSha256")
        if not pack_hash:
            continue
        pack_file = packs_dir / mission_id / f"{pack_hash}.json"
        if not pack_file.is_file():
            continue  # evidence not available on this checkout -- not a finding
        try:
            pack = json.loads(pack_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            gaps.append(f"docs/external-ai-review/runs/{run_file.name}: backing pack "
                        f"{pack_file} is not valid JSON: {exc}")
            continue
        source = pack.get("source", {})
        if source.get("stale"):
            gaps.append(
                f"docs/external-ai-review/runs/{run_file.name}: backing pack ({pack_file.name}) is "
                f"marked source.stale=true -- this run's verdict was produced from generated code "
                f"that predates a relevant template fix (the REG-49 false-positive class). Re-run "
                f"the mission against freshly generated output before trusting this record."
            )
        elif source.get("kind") == "generated-app" and not source.get("provenanceVerified"):
            gaps.append(
                f"docs/external-ai-review/runs/{run_file.name}: backing pack ({pack_file.name}) is "
                f"source.kind=generated-app but provenanceVerified is not true -- provenance was "
                f"never actually checked for this run."
            )
    return gaps


def _plan_documents(root: Path) -> list[Path]:
    """Every `*PLAN*.md`, at docs/ root AND in docs/archive/programme-history/.

    docs-decoupling-2026-08-11 PLAN.md Phase 3b: most closed plans moved to the archive, so scanning
    docs/ root alone would eventually starve this check to zero live-tense declarations even though
    the archived ones are exactly where "does this still read as a live backlog" matters most (Rule 1
    resolution (a), re-point rather than narrow). A handful of still-ACTIVE plans (found via the
    STATUS check itself, not assumed) remain at docs/ root.
    """
    return sorted((root / "docs").glob("*PLAN*.md")) + sorted(
        (root / "docs" / "archive" / "programme-history").glob("*PLAN*.md")
    )


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
    matched = _plan_documents(root)
    if not matched:
        raise EmptyScopeError(
            "check_plan_status_banners(): docs/*PLAN*.md (root + archive/programme-history/) "
            "matched 0 files -- this would report a false PASS having checked no plan documents. "
            "Re-point the scan at the new location of these documents, or delete this rule outright, "
            "before letting it run on an empty set (Rule 1, docs-decoupling-2026-08-11 PLAN.md)."
        )
    problems: list[str] = []
    checked = 0
    for path in matched:
        rel = path.relative_to(root).as_posix()
        head = "\n".join(path.read_text(encoding="utf-8", errors="replace").split("\n")[:8])
        checked += 1
        if "STATUS:" not in head:
            problems.append(
                f"{rel}: no `> **STATUS: …**` line in the first 8 lines. Add one saying "
                f"EXECUTED (work landed) / ACTIVE (live backlog) / HISTORICAL (unverified) / "
                f"SUPERSEDED (point at the replacement), so a reader knows whether to act on it."
            )
    print(f"  planning documents: {checked - len(problems)}/{checked} declare a status")
    return problems


# ---------------------------------------------------------------------------
# docs/INVOCATION_TOPOLOGY_PLAN.md T4: a plan may not close with an unresolved deferral that has no
# tracking id. Finding 2 of that plan's own Part 0 was exactly this shape and had no executable
# artifact to point a gate at -- 17 corpus models stayed broken for ~3 weeks because
# `docs/DSL2_AND_DECOMPOSITION_PLAN.md`'s own Definition of Done recorded "AppGen/apps deferred as a
# non-git external directory -- owner's call" with no id, the plan closed, and the deferral closed
# with it. Deferring is fine and often right; deferring WITHOUT a tracking id is what failed.
#
# Widened from the plan's literal "must cite a REG-nn": a permanent, deliberate scope boundary (the
# real DSL2_AND_DECOMPOSITION_PLAN.md instance) is not a gap or a bug -- filing a REG (whose only
# valid types are GAP/BUG/PROCESS/BOUNDARY) for something ACCEPTED_BOUNDARIES.md already exists to
# record would misuse the ledger. Either a REG-nn or a B-nn (docs/ACCEPTED_BOUNDARIES.md) citation
# counts -- both are "a tracking id", the convention's actual intent.
# ---------------------------------------------------------------------------

DEFERRAL_PHRASES = ("deferred", "out of scope", "not covered", "left for later")
CLOSED_STATUS_WORDS = {"DONE", "EXECUTED", "CLOSED"}
PLAN_STATUS_WORD_RE = re.compile(r"\*\*STATUS:\s*([A-Za-z]+)")
TRACKING_ID_RE = re.compile(r"\bREG-\d+\b|\bB\d{1,3}\b")
# Same historical-narration idea check-narrative-status-drift.py's HISTORICAL_MARKERS already uses
# for the identical reason: a paragraph narrating that something WAS deferred and is now resolved
# is not a live, untracked deferral -- it is the opposite. Without this, a whole closed-out section
# titled e.g. "Resolve the N deferred panels" (a real instance: docs/TREE1_LAUNCH_UNBLOCK_PLAN.md
# T1.4) fires on every paragraph discussing the resolution, not just the one time it actually mattered.
RESOLUTION_MARKERS = (
    "was deferred", "were deferred", "resolve the", "resolved", "resolving",
    "previously", "no longer", "turned out", "is now", "corrected",
)

# Reviewed false positives (same fingerprint-keyed shape as test-task-coverage-allowlist.json /
# security-pattern-sweep-allowlist.json): the phrase set is cheap text-level matching by design, and
# a handful of paragraphs narrate an INVESTIGATION into a pre-existing claim (project memory, an
# older governance file) rather than this plan making its own new, untracked scope cut. Rather than
# chase every future narrative shape with more regex, a human reviews and clears the specific
# paragraph -- moving or editing the text invalidates the fingerprint, so it resurfaces for re-review.
DEFERRAL_ALLOWLIST_PATH = Path(__file__).resolve().parent / "plan-deferral-citation-allowlist.json"


def load_deferral_allowlist() -> dict:
    if not DEFERRAL_ALLOWLIST_PATH.is_file():
        return {}
    data = json.loads(DEFERRAL_ALLOWLIST_PATH.read_text(encoding="utf-8"))
    return data.get("cleared", {})


def deferral_fingerprint(doc_name: str, paragraph: str) -> str:
    normalized = " ".join(paragraph.split())
    return hashlib.sha256(f"{doc_name}|{normalized}".encode()).hexdigest()[:12]


def split_into_deferral_units(text: str) -> list[str]:
    """Blank-line paragraphs, further split so each markdown list bullet is its own unit.

    A long checklist (`- [x] ...` repeated with no blank line between items) otherwise reads as one
    giant paragraph -- found for real: docs/DSL2_AND_DECOMPOSITION_PLAN.md's Part 2 checklist has
    "resolved" three bullets above the actual "AppGen/apps deliberately deferred" bullet, and without
    this split the unrelated earlier "resolved" wrongly suppressed the real deferral two bullets down.
    """
    units: list[str] = []
    for block in re.split(r"\n\s*\n", text):
        bullet_starts = [m.start() for m in re.finditer(r"^\s*[-*]\s", block, re.MULTILINE)]
        if len(bullet_starts) >= 2:
            bounds = bullet_starts + [len(block)]
            units.extend(block[bounds[i]:bounds[i + 1]] for i in range(len(bullet_starts)))
        else:
            units.append(block)
    return units


def plan_deferral_citations_text(doc_name: str, text: str, allowlist: dict, verbose: bool = False) -> list[str]:
    """Checks ONE plan document's already-read text. Returns [] if the plan's STATUS does not read
    as closed (DONE/EXECUTED/CLOSED) -- ACTIVE/HISTORICAL plans are exempt, a deferral in a live
    backlog is not yet a closed decision. See check_plan_deferral_citations for the full rule.
    """
    problems: list[str] = []
    head = "\n".join(text.split("\n")[:8])
    status_match = PLAN_STATUS_WORD_RE.search(head)
    if status_match is None or status_match.group(1).upper() not in CLOSED_STATUS_WORDS:
        return problems
    # Fenced code blocks are data/examples (JSON snippets, shell commands, sequencing diagrams), not
    # prose claims -- strip them before paragraph-splitting so e.g. a `deferred: 32` count in a code
    # fence is not read as an assertion.
    prose_text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    for para in split_into_deferral_units(prose_text):
        stripped = para.strip()
        if not stripped or stripped.startswith("#"):
            continue  # a heading is a title, not a claim
        para_lower = para.lower()
        if not any(phrase in para_lower for phrase in DEFERRAL_PHRASES):
            continue
        if TRACKING_ID_RE.search(para):
            continue
        if any(marker in para_lower for marker in RESOLUTION_MARKERS):
            continue
        fp = deferral_fingerprint(doc_name.rsplit("/", 1)[-1], para)
        if fp in allowlist:
            if verbose:
                print(f"    [allowed] {doc_name} ({fp}): {allowlist[fp].get('why', '(no reason recorded)')}")
            continue
        snippet = " ".join(para.split())[:220]
        problems.append(
            f"{doc_name}: closed plan (STATUS: {status_match.group(1)}) has a deferral with no "
            f"REG-nn or B-nn (docs/ACCEPTED_BOUNDARIES.md) citation in the same paragraph ({fp}): "
            f"\"{snippet}\". Cite an existing id, file one, or record a reviewed exemption in "
            f"scripts/quality/plan-deferral-citation-allowlist.json."
        )
    return problems


def check_plan_deferral_citations(root: Path, verbose: bool) -> list[str]:
    """A plan whose STATUS reads as closed (DONE/EXECUTED/CLOSED) may not contain a deferral
    phrase with no REG-nn or B-nn citation in the same paragraph. Plans still ACTIVE/HISTORICAL are
    exempt -- a deferral in a live backlog is not yet a closed decision. Heading-only lines,
    fenced code blocks, and paragraphs narrating a NOW-RESOLVED historical deferral
    (RESOLUTION_MARKERS) are not claims and are excluded -- this rule targets a plan's own closing
    scope decision, not prose describing work that already addressed an earlier deferral. A reviewed
    residual false positive can be cleared in plan-deferral-citation-allowlist.json.
    """
    matched = _plan_documents(root)
    if not matched:
        raise EmptyScopeError(
            "check_plan_deferral_citations(): docs/*PLAN*.md (root + archive/programme-history/) "
            "matched 0 files -- this would report a false PASS having scanned no plan documents. "
            "Re-point the scan at the new location of these documents, or delete this rule outright, "
            "before letting it run on an empty set (Rule 1, docs-decoupling-2026-08-11 PLAN.md)."
        )
    problems: list[str] = []
    checked = 0
    allowlist = load_deferral_allowlist()
    for path in matched:
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        doc_problems = plan_deferral_citations_text(rel, text, allowlist, verbose)
        head = "\n".join(text.split("\n")[:8])
        status_match = PLAN_STATUS_WORD_RE.search(head)
        if status_match is not None and status_match.group(1).upper() in CLOSED_STATUS_WORDS:
            checked += 1
        problems.extend(doc_problems)
    print(f"  plan deferral citations: {checked} closed plan(s) checked, {len(problems)} untracked deferral(s)")
    return problems


# ---------------------------------------------------------------------------
# Rules T1 + T2 (docs/NEXT_EXECUTION_PLAN.md P2.1): close the tree/ledger drift class, not just the
# instances found by hand on 2026-07-28 (2.F, 3.3, REG-59, and a fourth found WHILE BUILDING this
# rule -- 3.5's REG-4 blocker). Same shape as `check()` above -- a summary-shaped claim contradicting
# its own detail -- pointed at a fourth document (T1) and at a shape `check()` does not cover: a
# struck row whose OWN verdict sentence still argues open (T2).
# ---------------------------------------------------------------------------

# Rule T1: phrases EXECUTION_TREES.md has actually used to write up an item as pending, proven
# against two REAL 2026-07-28 instances, not guessed broad -- add a phrase only when a real instance
# proves it belongs, same discipline as CLOSED_WORDS/OPEN_WORDS above.
#   3.3 / REG-40: "currently fails" + "Not yet scheduled" while REG-40 had been CLOSED since 2026-07-24.
#   3.5 / REG-4:  "still unresolved" while REG-4 had been CLOSED since 2026-07-21 -- found WHILE
#                 BUILDING this rule, a fourth live instance of the exact class this rule exists for.
TREE_PENDING_PHRASES = (
    "still unresolved",
    "not yet scheduled",
    "currently fails",
    "not yet started",
    "not yet implemented",
)
TREE_REG_MENTION = re.compile(r"\bREG-\d+\b")
# A tree bullet line, e.g. "├─ 3.3  ..." or, nested, "│    ├─ 2.B.4 ...".
TREE_BULLET = re.compile(r"^\s*[│\s]*[├└]─")


def tree_ledger_agreement_text(doc_name: str, lines: list[str], register_summary: dict) -> list[str]:
    """Rule T1 core: does any REG-nn mention in `lines` (an EXECUTION_TREES.md-shaped document) read
    as pending work, in a block the register says is CLOSED?

    A "block" is the mention's own line plus up to the next 6 lines, stopped early at a blank line or
    the next tree bullet -- an approximation of "this item's write-up" that needs no real tree parser.
    Only the FIRST mention of each id is used for the block scan (subsequent mentions of an id already
    flagged, or already cleared, are not re-scanned) -- this is a report-once convenience, not a
    correctness requirement.
    """
    gaps: list[str] = []
    reported: set[str] = set()
    for number, line in enumerate(lines, start=1):
        for match in TREE_REG_MENTION.finditer(line):
            reg_id = match.group(0)
            if reg_id in reported:
                continue
            entry = register_summary.get(reg_id)
            if entry is None or entry[1] != "closed":
                continue  # register doesn't track it, or it agrees the item is open: nothing to contradict
            block_lines = [line]
            for later in lines[number:number + 6]:
                if not later.strip() or TREE_BULLET.match(later):
                    break
                block_lines.append(later)
            block_text = " ".join(block_lines).lower()
            hit = next((p for p in TREE_PENDING_PHRASES if p in block_text), None)
            if hit:
                reported.add(reg_id)
                gaps.append(
                    f"{doc_name}:{number}: {reg_id} is CLOSED in NPDEV_OPEN_ITEMS_REGISTER.md but is "
                    f"still written up here as pending work (matched {hit!r}). Fix: update this "
                    f"entry to reflect the register's CLOSED status, or correct the register if it "
                    f"is the one that is wrong."
                )
    return gaps


def ledger_status_summary(root: Path) -> dict[str, tuple[int, str]]:
    """{id: (0, "closed"|"open")} from ledger/items/*.yml -- the same shape `parse()`'s summary dict
    has, so it drops straight into `tree_ledger_agreement_text` unchanged. Line number is always 0
    (meaningless for a YAML source; callers only use it for a human-readable pointer, and the id
    itself is enough to find the file)."""
    return {
        item.get("id", "<missing id>"): (0, "closed" if item.get("status") == "DONE" else "open")
        for item in load_ledger_items(root)
    }


def tree_ledger_agreement_gaps(root: Path) -> list[str]:
    """Rule T1 (docs/REMEDIATION_PLAN.md R-P1: simplified to a YAML field read once the 2.E ledger
    migration completed -- was `parse(NPDEV_OPEN_ITEMS_REGISTER.md, "strikethrough")` until then)."""
    tree_path = root / "docs" / "EXECUTION_TREES.md"
    if not tree_path.exists():
        return []
    summary = ledger_status_summary(root)
    if not summary:
        return []
    lines = tree_path.read_text(encoding="utf-8", errors="replace").splitlines()
    return tree_ledger_agreement_text(tree_path.name, lines, summary)


# Rule T2: a struck (closed) row whose own verdict sentence still argues open. Scoped to the row's
# FIRST bold run AFTER its id -- the verdict sentence, e.g. "**DONE (2026-07-28).**" -- rather than
# the whole row or all its bold runs. Measured directly against this corpus before shipping: a naive
# whole-row (or all-bold-runs) scan for these phrases produces FOUR false positives --
#   REG-16:  "...so no launch surface remains unreviewed" (plain prose, not the verdict)
#   REG-17:  "...run remains an optional nice-to-have" (plain prose, not the verdict)
#   REG-52:  "filed separately per the closure plan" (the FROM column, not Status, and not bolded)
#   REG-59 (post-P1.4-fix): "**Platform-level gap filed as REG-61 ..., not covered by this row's DONE
#            marker:**" -- a LATER bold run, deliberately pointing at the new REG-61 follow-up
# all while the row itself is legitimately CLOSED. The real 2026-07-28 REG-59 bug had the
# contradiction IN the lead bold verdict ("**DONE for WmsOffice ...; the underlying platform gap is
# FILED, not fixed.**"), so scoping to that first run catches the real case without the four false ones.
T2_PHRASES = (
    re.compile(r"\bnot fixed\b", re.IGNORECASE),
    re.compile(r"\bfiled\b", re.IGNORECASE),
    re.compile(r"\bremains\b", re.IGNORECASE),
    re.compile(r"\bstill open\b", re.IGNORECASE),
)
BOLD_RUN = re.compile(r"\*\*(.+?)\*\*")


def strikethrough_contradiction_text(doc_name: str, lines: list[str]) -> list[str]:
    gaps: list[str] = []
    for number, line in enumerate(lines, start=1):
        row = SUMMARY_ROW.match(line)
        if not row or not row.group("struck"):
            continue
        item = row.group("id")
        verdict_runs = [b for b in BOLD_RUN.findall(line) if b.strip() != item]
        if not verdict_runs:
            continue
        verdict_text = verdict_runs[0]
        for pattern in T2_PHRASES:
            found = pattern.search(verdict_text)
            if found:
                gaps.append(
                    f"{doc_name}:{number}: {item} is struck through (closed) but its own verdict "
                    f"sentence ({verdict_text.strip()[:80]!r}) contains {found.group(0)!r}, which "
                    f"reads as still open. Fix: reword the verdict, or split it like REG-59/REG-61 "
                    f"(docs/NEXT_EXECUTION_PLAN.md P1.4) -- a WmsOffice-local DONE stays struck, a "
                    f"platform-level OPEN follow-up gets its own unstruck row."
                )
                break
    return gaps


def strikethrough_contradiction_gaps(root: Path) -> list[str]:
    register_path = root / "docs" / "NPDEV_OPEN_ITEMS_REGISTER.md"
    if not register_path.exists():
        return []
    lines = register_path.read_text(encoding="utf-8", errors="replace").splitlines()
    return strikethrough_contradiction_text(register_path.name, lines)


# ---------------------------------------------------------------------------
# Rule T3 RETIRED (docs/REMEDIATION_PLAN.md R-P1, 2026-07-29): it existed only to cross-check
# ledger/items/*.yml against NPDEV_OPEN_ITEMS_REGISTER.md while the 2.E migration was partial (see
# git history for the removed implementation if it's ever needed again). The migration is now
# complete -- the register is archived-in-place (see its own banner) and the ledger is the single
# source of truth -- so there is nothing left for this rule to cross-check, exactly the condition
# its own docstring named as the retirement trigger. Rule T1 below was simplified to read the ledger
# directly instead of parsing the register's strikethrough state.
# ---------------------------------------------------------------------------


def load_ledger_items(root: Path) -> list[dict]:
    ledger_dir = root / "ledger" / "items"
    if not ledger_dir.is_dir():
        return []
    items = []
    for path in sorted(ledger_dir.glob("*.yml")):
        item = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        item.setdefault("id", path.stem)
        items.append(item)
    return items


# Rule T2b (docs/CLOSEOUT_PLAN.md G4): a scope gap in Rule T2 above, not a new contradiction class --
# T2 checks the register's strikethrough marker against its own verdict SENTENCE, but never reads an
# item's TITLE, and never looks at ledger/items/*.yml at all (that YAML has its own independent
# title/status pair since the 2.E migration). Found live: REG-62.yml's title still read "...still
# blocked on a typed-actions prerequisite" after F9 (docs/FINAL_OPEN_ITEMS_PLAN.md) shipped the
# cross-reference and flipped status to DONE -- the one field a human scans first (the title, e.g. in
# docs/OPEN_ITEMS.md's summary table) was the one field no gate checked.
LEDGER_CLOSED_STATUSES = ("DONE", "WITHDRAWN")
LEDGER_TITLE_STALE_PHRASES = (
    re.compile(r"\bstill blocked\b", re.IGNORECASE),
    re.compile(r"\bnot fixed\b", re.IGNORECASE),
    re.compile(r"\bremains open\b", re.IGNORECASE),
    re.compile(r"\bblocked on\b", re.IGNORECASE),
    re.compile(r"\bunresolved\b", re.IGNORECASE),
)


def ledger_title_status_contradiction_text(items: list[dict]) -> list[str]:
    gaps: list[str] = []
    for item in items:
        status = str(item.get("status") or "").strip().upper()
        if status not in LEDGER_CLOSED_STATUSES:
            continue
        title = str(item.get("title") or "")
        for pattern in LEDGER_TITLE_STALE_PHRASES:
            found = pattern.search(title)
            if found:
                gaps.append(
                    f"ledger/items/{item.get('id')}.yml: status is {status} but its own title "
                    f"({title.strip()[:80]!r}) contains {found.group(0)!r}, which reads as still "
                    f"open. Fix: reword the title to describe the closed state."
                )
                break
    return gaps


def ledger_title_status_contradiction_gaps(root: Path) -> list[str]:
    return ledger_title_status_contradiction_text(load_ledger_items(root))


SYNTHETIC_T1_STALE = """\
├─ 9.1  Fix REG-99 (synthetic fixture, not a real tree item)
│        → User impact is high: this currently fails. Not yet scheduled.
"""
SYNTHETIC_T1_FIXED = """\
├─ 9.1  Fix REG-99 (synthetic fixture, not a real tree item)
│        ✅ DONE 2026-07-01 -- REG-99 is CLOSED in the register.
"""
SYNTHETIC_T1_REGISTER = {"REG-99": (1, "closed")}

SYNTHETIC_T2_STALE = """\
| ~~**REG-98**~~ | synthetic fixture | HIGH | **DONE for X; the underlying gap is FILED, not fixed.** More prose. |
"""
SYNTHETIC_T2_FIXED = """\
| ~~**REG-98**~~ | synthetic fixture | HIGH | **DONE for X.** The platform gap is filed as REG-97 (OPEN). |
"""

SYNTHETIC_T2B_STALE = [{
    "id": "REG-97", "status": "DONE",
    "title": "some fix (synthetic) -- still blocked on a prerequisite",
}]
SYNTHETIC_T2B_FIXED = [{
    "id": "REG-97", "status": "DONE",
    "title": "some fix (synthetic), now unblocked",
}]


def calibrate(root: Path) -> int:
    """Required controls for T1/T2/T2b/T4 before any ships as blocking -- same standard this repo already
    holds `check-narrative-status-drift.py` to. Prefers real git revisions where one exists (T1's
    REG-40/REG-4 instances and T2b's REG-62 instance, all real and all in this repo's own history);
    falls back to a small synthetic fixture only where no single real revision isolates the mechanism
    cleanly.

    REG-67: T1/T2's real-instance controls used to read bare `HEAD`, a moving target -- both target
    docs were edited again after 2026-07-28, so the stale wording the controls looked for no longer
    existed at HEAD and `--calibrate` silently rotted. Pinned to `PRE_FIX_SHA` below instead, same
    fixed-revision discipline Rule T2b already used for REG-62 @ 9c3c423.

    docs/FAIL_OPEN_PLAN.md R1: several controls below are guarded by `if git_show(...) is not None:`
    (`git_show` returns `None` and only prints a stderr warning when a pinned revision can't be read,
    e.g. a shallow clone) -- guarding the *call* is right (a real read failure shouldn't crash the
    whole calibration), but nothing previously noticed when a guard skipped a control entirely: the
    loop would just run fewer `report()` calls and still exit 0, because "control never ran" and
    "control ran and passed" look identical to `ok`. This is exactly how the pre-`fetch-depth` rot
    went unnoticed -- fixed at the workflow level (`ai-knowledge-gate.yml` now sets `fetch-depth: 0`),
    but the *behavior* (a skip reads as a pass) was still there and would recreate the same silent gap
    on the next shallow-clone context (a rebase orphaning a pin, a fork, a future workflow edit).
    `EXPECTED_CONTROLS` asserts the *count* of `report()` calls actually made, not just their
    individual outcomes -- a skipped control now fails loudly instead of silently vanishing.
    """
    ok = True
    reported = 0
    # MEASURED 2026-07-29 (docs/FAIL_OPEN_PLAN.md R1): 15 report() calls when every guard passes.
    # MEASURED 2026-08-11 (docs-decoupling-2026-08-11 PLAN.md Phase 0): +6 report_raises() calls for
    # the three Rule 1 empty-scope floors (empty-tree + populated-repo control each) -- 21 total.
    # Recount by grepping this function for `report(` / `report_raises(` if this function's controls
    # change -- a hand-maintained count is exactly the kind of claim check-record-surfaces.py exists
    # to distrust elsewhere, but there is no cheaper source of truth for "how many controls does THIS
    # run intend" than reading the function that defines them.
    EXPECTED_CONTROLS = 21

    def report(label: str, findings: list[str], expect_fire: bool) -> None:
        nonlocal ok, reported
        reported += 1
        fired = bool(findings)
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for f in findings:
            print(f"           {f}")

    print("Calibration -- Rules T1/T2/T2b/T4 must catch their real historical instances before shipping:")

    register_path = root / "docs" / "NPDEV_OPEN_ITEMS_REGISTER.md"
    tree_path = root / "docs" / "EXECUTION_TREES.md"

    def git_show(path: Path, revision: str = "HEAD") -> str | None:
        try:
            return subprocess.run(
                ["git", "show", f"{revision}:{path.relative_to(root).as_posix()}"],
                cwd=root, capture_output=True, encoding="utf-8", errors="replace", check=True,
            ).stdout
        except subprocess.CalledProcessError as exc:
            print(f"  ERROR: could not read {revision} revision of {path.name}: {exc.stderr}", file=sys.stderr)
            return None

    # Pinned to a fixed SHA, not HEAD (REG-67): both docs changed together in 7ef8af4 (the fix
    # commit); 6a58b09 is its parent and still holds the pre-fix stale wording for both Rule T1
    # (EXECUTION_TREES.md) and Rule T2 (NPDEV_OPEN_ITEMS_REGISTER.md) -- confirmed via `git show`.
    PRE_FIX_SHA = "6a58b09"

    head_register_summary = None
    head_register_text = git_show(register_path, revision=PRE_FIX_SHA)
    if head_register_text is not None:
        # Reuse parse()'s line-based logic against the HEAD text by writing it through the same
        # summary-extraction pass parse() uses (duplicated minimally: parse() takes a Path, not text).
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as tmp:
            tmp.write(head_register_text)
            tmp_path = Path(tmp.name)
        try:
            head_register_summary, _, _ = parse(tmp_path, "strikethrough")
        finally:
            tmp_path.unlink(missing_ok=True)

    head_tree_text = git_show(tree_path, revision=PRE_FIX_SHA)
    if head_tree_text is not None and head_register_summary is not None:
        report(
            f"Rule T1 vs. EXECUTION_TREES.md @ {PRE_FIX_SHA} (pre-fix: 3.3/REG-40 + 3.5/REG-4, real git revision)",
            tree_ledger_agreement_text(f"{tree_path.name}@HEAD", head_tree_text.splitlines(), head_register_summary),
            expect_fire=True,
        )
    report(
        "Rule T1 vs. the working tree (post-fix)",
        tree_ledger_agreement_gaps(root),
        expect_fire=False,
    )

    if head_register_text is not None:
        report(
            f"Rule T2 vs. NPDEV_OPEN_ITEMS_REGISTER.md @ {PRE_FIX_SHA} (pre-fix REG-59, real git revision)",
            strikethrough_contradiction_text(f"{register_path.name}@HEAD", head_register_text.splitlines()),
            expect_fire=True,
        )
    report(
        "Rule T2 vs. the working tree (post-fix REG-59/REG-61 split)",
        strikethrough_contradiction_gaps(root),
        expect_fire=False,
    )

    report("Rule T1 vs. synthetic 'currently fails / not yet scheduled' fixture (mechanism control)",
           tree_ledger_agreement_text("<synthetic>", SYNTHETIC_T1_STALE.splitlines(), SYNTHETIC_T1_REGISTER),
           expect_fire=True)
    report("Rule T1 vs. the corrected synthetic fixture",
           tree_ledger_agreement_text("<synthetic>", SYNTHETIC_T1_FIXED.splitlines(), SYNTHETIC_T1_REGISTER),
           expect_fire=False)
    report("Rule T2 vs. synthetic 'FILED, not fixed' fixture (mechanism control)",
           strikethrough_contradiction_text("<synthetic>", SYNTHETIC_T2_STALE.splitlines()),
           expect_fire=True)
    report("Rule T2 vs. the corrected synthetic fixture",
           strikethrough_contradiction_text("<synthetic>", SYNTHETIC_T2_FIXED.splitlines()),
           expect_fire=False)

    # Rule T3 retired (docs/REMEDIATION_PLAN.md R-P1) -- see its retirement note above Rule T1.

    # Rule T2b (docs/CLOSEOUT_PLAN.md G4): real instance is REG-62.yml @ commit 9c3c423 (the F9 commit
    # that flipped status to DONE without updating the title) -- still readable via git history even
    # though the working tree has since been fixed, same "real revision preferred over synthetic"
    # standard as T1/T2 above.
    head_reg62_text = git_show(root / "ledger" / "items" / "REG-62.yml", revision="9c3c423")
    if head_reg62_text is not None:
        head_reg62_item = yaml.safe_load(head_reg62_text) or {}
        head_reg62_item.setdefault("id", "REG-62")
        report(
            "Rule T2b vs. ledger/items/REG-62.yml @ 9c3c423 (pre-fix, real instance)",
            ledger_title_status_contradiction_text([head_reg62_item]),
            expect_fire=True,
        )
    report(
        "Rule T2b vs. the working tree (post-fix)",
        ledger_title_status_contradiction_gaps(root),
        expect_fire=False,
    )
    report("Rule T2b vs. synthetic 'still blocked' title fixture (mechanism control)",
           ledger_title_status_contradiction_text(SYNTHETIC_T2B_STALE),
           expect_fire=True)
    report("Rule T2b vs. the corrected synthetic fixture",
           ledger_title_status_contradiction_text(SYNTHETIC_T2B_FIXED),
           expect_fire=False)

    # docs/INVOCATION_TOPOLOGY_PLAN.md T4: real instance is DSL2_AND_DECOMPOSITION_PLAN.md's own
    # Part 2 DoD @ commit b7a4f0f (the commit that marked the plan DONE) -- "AppGen/apps deliberately
    # deferred (owner's call...)" with no REG-nn/B-nn citation. Pinned by SHA per T1's own rule, even
    # though (confirmed via `git show`) this pin's content is currently identical to the pre-fix
    # working tree -- the discipline matters going forward, not just today.
    #
    # Two DIFFERENT paths, not one (docs-decoupling-2026-08-11 PLAN.md Phase 3b): b7a4f0f predates
    # the archival move, so the historical git-show lookup must keep using the OLD docs/ path (that
    # commit has no docs/archive/ tree at all) -- but the CURRENT working-tree read must use the NEW
    # archive path, since the file now lives there. Conflating them either 404s the historical lookup
    # or silently stops running the post-citation working-tree control (a control that quietly stops
    # running is exactly the docs/FAIL_OPEN_PLAN.md R1 hazard EXPECTED_CONTROLS exists to catch).
    dsl2_path_historical = root / "docs" / "DSL2_AND_DECOMPOSITION_PLAN.md"
    dsl2_path_current = root / "docs" / "archive" / "programme-history" / "DSL2_AND_DECOMPOSITION_PLAN.md"
    pre_citation_text = git_show(dsl2_path_historical, revision="b7a4f0f")
    empty_allowlist: dict = {}
    if pre_citation_text is not None:
        report(
            "Rule T4 vs. DSL2_AND_DECOMPOSITION_PLAN.md @ b7a4f0f (pre-citation, real instance)",
            plan_deferral_citations_text("docs/DSL2_AND_DECOMPOSITION_PLAN.md", pre_citation_text, empty_allowlist),
            expect_fire=True,
        )
    if dsl2_path_current.exists():
        report(
            "Rule T4 vs. DSL2_AND_DECOMPOSITION_PLAN.md in the working tree (post-citation, B25 added)",
            plan_deferral_citations_text(
                "docs/archive/programme-history/DSL2_AND_DECOMPOSITION_PLAN.md",
                dsl2_path_current.read_text(encoding="utf-8", errors="replace"),
                empty_allowlist,
            ),
            expect_fire=False,
        )
    report(
        "Rule T4 vs. the full working tree (all closed plans, real allowlist)",
        check_plan_deferral_citations(root, verbose=False),
        expect_fire=False,
    )

    # Rule 1 empty-scope floors (docs-decoupling-2026-08-11 PLAN.md Phase 0): ledger_coverage_gaps(),
    # check_plan_status_banners(), and check_plan_deferral_citations() must each raise EmptyScopeError
    # on an emptied docs/ root rather than silently reporting PASS having scanned nothing, and must
    # stay quiet against the real, populated repo.
    def report_raises(label: str, fn, expect_fire: bool) -> None:
        nonlocal ok, reported
        reported += 1
        fired = False
        try:
            fn()
        except EmptyScopeError:
            fired = True
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

    with tempfile.TemporaryDirectory() as tmp:
        empty_root = Path(tmp)
        (empty_root / "docs").mkdir()
        report_raises(
            "ledger_coverage_gaps() vs. synthetic empty docs/ tree (docs/*.md matches 0 files)",
            lambda: ledger_coverage_gaps(empty_root),
            expect_fire=True,
        )
        report_raises(
            "check_plan_status_banners() vs. synthetic empty docs/ tree (docs/*PLAN*.md matches 0 files)",
            lambda: check_plan_status_banners(empty_root, verbose=False),
            expect_fire=True,
        )
        report_raises(
            "check_plan_deferral_citations() vs. synthetic empty docs/ tree (docs/*PLAN*.md matches 0 files)",
            lambda: check_plan_deferral_citations(empty_root, verbose=False),
            expect_fire=True,
        )
    report_raises(
        "ledger_coverage_gaps() vs. the real, populated repo",
        lambda: ledger_coverage_gaps(root),
        expect_fire=False,
    )
    report_raises(
        "check_plan_status_banners() vs. the real, populated repo",
        lambda: check_plan_status_banners(root, verbose=False),
        expect_fire=False,
    )
    report_raises(
        "check_plan_deferral_citations() vs. the real, populated repo",
        lambda: check_plan_deferral_citations(root, verbose=False),
        expect_fire=False,
    )

    if reported != EXPECTED_CONTROLS:
        print(f"\nFAIL: expected {EXPECTED_CONTROLS} control(s) to run, only {reported} did -- "
              f"{EXPECTED_CONTROLS - reported} were silently SKIPPED (an unreachable pinned git "
              f"revision?). A skipped control proves nothing and must be treated as a failure, not a "
              f"pass (docs/FAIL_OPEN_PLAN.md R1).", file=sys.stderr)
        ok = False

    if not ok:
        print("\nFAIL: at least one control did not behave as required -- T1/T2/T2b/T4 do not ship "
              "as blocking until they do (docs/NEXT_EXECUTION_PLAN.md P2.1, docs/CLOSEOUT_PLAN.md G4, "
              "docs/INVOCATION_TOPOLOGY_PLAN.md T4).",
              file=sys.stderr)
        return 1
    print("\nOK: all T1/T2/T2b/T4 controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    parser.add_argument("--verbose", action="store_true", help="also print consistent/skipped counts")
    parser.add_argument("--calibrate", action="store_true", help="run the T1/T2/T2b/T4 required controls and exit")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    if args.calibrate:
        return calibrate(root)
    # Each document declares status its own way -- made explicit rather than guessed.
    # OPEN_GAPS_AND_ROADMAP.md is NOT here (docs-decoupling-2026-08-11 PLAN.md Phase 1): it is now
    # generated from ledger/gaps.yml, same as OPEN_ITEMS.md above it in LEDGER_EXCLUSIONS -- a
    # summary-vs-detail contradiction is structurally impossible once both render from one field.
    targets = [
        (root / "docs" / "NPDEV_OPEN_ITEMS_REGISTER.md", "strikethrough"),
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
    try:
        all_problems.extend(check_plan_status_banners(root, args.verbose))
        all_problems.extend(check_plan_deferral_citations(root, args.verbose))
        # Coverage last so its message is not buried, but it is a HARD gap: a ledger nobody checks is
        # the same failure as a summary row nobody cross-checks.
        all_problems.extend(ledger_coverage_gaps(root))
    except EmptyScopeError as exc:
        print(f"\nFAIL: {exc}", file=sys.stderr)
        return 1
    all_problems.extend(mission_run_coverage_gaps(root))
    all_problems.extend(provenance_audit_gaps(root))
    # Rules T1+T2 (docs/NEXT_EXECUTION_PLAN.md P2.1): tree-vs-ledger and strikethrough-vs-own-verdict
    # cross-checks, calibrated (see `--calibrate`) against the real 2026-07-28 drift instances. T1
    # reads ledger/items/*.yml directly since docs/REMEDIATION_PLAN.md R-P1 completed the 2.E
    # migration; Rule T3 (which bridged the partial-migration period) is retired -- see its note above.
    t1 = tree_ledger_agreement_gaps(root)
    t2 = strikethrough_contradiction_gaps(root)
    t2b = ledger_title_status_contradiction_gaps(root)
    print(f"  EXECUTION_TREES.md vs. ledger (Rule T1): {len(t1)} contradiction(s)")
    print(f"  NPDEV_OPEN_ITEMS_REGISTER.md strikethrough-vs-verdict (Rule T2): {len(t2)} contradiction(s)")
    print(f"  ledger/items/*.yml title-vs-status (Rule T2b): {len(t2b)} contradiction(s)")
    all_problems.extend(t1)
    all_problems.extend(t2)
    all_problems.extend(t2b)

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
