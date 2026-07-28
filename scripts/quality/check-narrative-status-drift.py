#!/usr/bin/env python3
"""Narrative status drift: does a PROSE sentence contradict the id row it names?

WHY THIS EXISTS -- blind spot #8
---------------------------------
`check-register-consistency.py` proved its rule (summary row vs. detail section) on eleven real
drifts, but it only ever parses two SHAPES: a table row (`SUMMARY_ROW`) and a heading-led detail
section (`DETAIL_HEADING`/`STATUS_LINE`). A status claim made in a narrative PARAGRAPH is invisible
to it by construction -- and that blind spot fired twice in 24 hours on 2026-07-27:

  1. `NPDEV_OPEN_ITEMS_REGISTER.md`'s own REG-48..52 intro asserted "REG-50 ... remains OPEN,
     pending an owner decision" ten lines above that same id's own `~~REG-50~~` **DONE** row.
  2. `docs/adr/ADR-0009-external-ai-delegation.md`'s header asserted "DRAFT ... D3, D4, D5 remain
     pending" while its own decision table said D3 ANSWERED and its own decision block said
     APPROVED WITH CONDITIONS.

Both were caught by a human re-reading the document, not by any gate. This script is that gate,
for that one specific shape: a sentence that names an id AND asserts a status for it.

THE RULES
---------
Rule P1 (per-id prose claim). Inside a document that owns id rows (a markdown table whose first
    cell is an id: `REG-nn`, `LNCH-nn`, `GATE-nn`, `D-n`/`Dn`, ...), a SENTENCE containing both that
    id and a status keyword must agree with that id's own row (its last table cell, which is where
    every convention this repo uses -- strikethrough register, status-cell roadmap, ADR decision
    table -- puts the authoritative word).
Rule P2 (document self-status), scoped to `docs/adr/ADR-*.md`. The header's declared status
    (DRAFT/PENDING vs. APPROVED/REJECTED/WITHDRAWN) must not contradict its own decision block.

Requiring BOTH an id and a keyword IN THE SAME SENTENCE is what keeps this from becoming a naive
status-keyword grep: prose that merely *discusses* an item never fires, only prose that *asserts*
its state does. "A gate that cries wolf gets bypassed" (this project's own lesson #4) is why this
ships REPORTING ONLY -- see `--calibrate` below -- never blocking, until a clean-tree run proves the
false-positive rate is actually zero.

FALSE-POSITIVE SUPPRESSION
---------------------------
The register deliberately narrates history ("REG-49 turned out to be a false positive"). A sentence
carrying a past-tense/superseded marker (was, were, previously, originally, until, no longer, had
been, turned out, is now, corrected) is legitimate narration, not a live claim, and is suppressed.

CALIBRATE BEFORE TRUSTING IT (non-negotiable -- P4's lesson, repeated at the meta level)
-----------------------------------------------------------------------------------------
    python scripts/quality/check-narrative-status-drift.py --calibrate

Runs two controls and prints PASS/FAIL for each, exiting 1 if either fails:
  - Rule P2 against the REAL git HEAD revision of ADR-0009 (still the pre-`docs:` header-fix text
    as of this writing -- that fix is an uncommitted working-tree edit) -- MUST fire; against the
    current working-tree file -- MUST NOT fire.
  - Rule P1 against a SYNTHETIC fixture reproducing the REG-50 "remains OPEN" shape -- MUST fire;
    against a corrected variant -- MUST NOT fire. Synthetic, not `git show`-derived, and said so
    plainly: the literal stale sentence was caught and corrected before it was ever committed, so
    there is no real git revision containing it to point at (checked: `git log --all -p -S"remains
    OPEN" -- docs/NPDEV_OPEN_ITEMS_REGISTER.md` returns nothing). Pretending otherwise would be
    exactly the kind of undisclosed evidence gap this whole feature exists to refuse.

USAGE
-----
    python scripts/quality/check-narrative-status-drift.py            # report, exit 0 always
    python scripts/quality/check-narrative-status-drift.py --calibrate  # self-test, exit 1 on failure

This script never fails the build on a real corpus finding (Phase 2.4: report-only for one cycle).
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import subprocess
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location(
    "check_register_consistency", _HERE / "check-register-consistency.py"
)
_rc = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_rc)

# Broader than the row-checker's vocabulary on purpose: the ADR decision table and its "Decision:"
# block use ANSWERED/PENDING/APPROVED/REJECTED, not OPEN/CLOSED/DONE. First-keyword-wins, same as
# `_rc.classify`, so a sentence naming several is judged by whichever word comes first in the text.
PROSE_CLOSED_WORDS = _rc.CLOSED_WORDS + ("ANSWERED", "APPROVED", "RESOLVED", "WITHDRAWN", "REJECTED")
PROSE_OPEN_WORDS = _rc.OPEN_WORDS + ("PENDING", "UNRESOLVED", "UNANSWERED")

# Deliberately excludes DRAFT/APPROVED (see PROSE_*_WORDS above) so a sentence merely discussing an
# ADR's drafting process ("drafted per ...") doesn't fire Rule P1 -- DRAFT is reserved for Rule P2's
# document-header check, where it is the header's OWN self-declared banner word, not incidental prose.
HEADER_OPEN_WORDS = ("DRAFT", "PENDING")
HEADER_CLOSED_WORDS = ("APPROVED", "REJECTED", "WITHDRAWN")

HISTORICAL_MARKERS = (
    "was", "were", "previously", "originally", "until", "no longer", "had been",
    "turned out", "is now", "corrected",
)

ID_TOKEN = re.compile(r"\b(?:REG|LNCH|GATE|ADR|SER)-\d+[A-Za-z0-9-]*\b|\bD-?\d\b")
# A table row whose first cell is an id (optionally struck/bolded), e.g.
# `| ~~**REG-48**~~ | ... | HIGH | **DONE ...** |` or `| D3 | Egress authorization ... | **ANSWERED...** |`
ROW_ID_STATUS = re.compile(
    r"^\|\s*(?:~~)?\s*\*{0,2}(?P<id>(?:REG|LNCH|GATE|ADR|SER)-\d+[A-Za-z0-9-]*|D-?\d)\*{0,2}\s*~{0,2}\s*\|(?P<rest>.*)\|\s*$"
)
FENCE = re.compile(r"^\s*```")


def normalize_id(raw: str) -> str:
    """`D-3` and `D3` are the same id; every other family already has no hyphen variant to collapse."""
    return re.sub(r"^D-(\d)$", r"D\1", raw.upper())


def classify_prose(text: str) -> str | None:
    upper = text.upper()
    best: tuple[int, str] | None = None
    for word, verdict in [(w, "closed") for w in PROSE_CLOSED_WORDS] + [(w, "open") for w in PROSE_OPEN_WORDS]:
        at = upper.find(word)
        if at >= 0 and (best is None or at < best[0]):
            best = (at, verdict)
    return best[1] if best else None


def classify_header(text: str) -> str | None:
    upper = text.upper()
    best: tuple[int, str] | None = None
    for word, verdict in [(w, "closed") for w in HEADER_CLOSED_WORDS] + [(w, "open") for w in HEADER_OPEN_WORDS]:
        at = upper.find(word)
        if at >= 0 and (best is None or at < best[0]):
            best = (at, verdict)
    return best[1] if best else None


def has_historical_marker(sentence: str) -> bool:
    lower = sentence.lower()
    return any(marker in lower for marker in HISTORICAL_MARKERS)


def authoritative_ids(lines: list[str]) -> dict[str, str]:
    """id -> its own row's verdict, first occurrence wins (index tables precede per-round tables)."""
    out: dict[str, str] = {}
    for line in lines:
        m = ROW_ID_STATUS.match(line)
        if not m:
            continue
        item = normalize_id(m.group("id"))
        if item in out:
            continue
        cells = [c for c in m.group("rest").split("|")]
        last = cells[-1].strip() if cells else ""
        verdict = classify_prose(last)
        if verdict:
            out[item] = verdict
    return out


def prose_paragraphs(lines: list[str]) -> list[tuple[int, str]]:
    """(start_line, text) for every blank-line-separated paragraph that is not a table row, a
    heading, or inside a fenced code block -- the decision block's own `Decision: [x] ...` line is
    fenced precisely so Rule P1 does not also fire on it (Rule P2 reads it directly, on purpose)."""
    out: list[tuple[int, str]] = []
    buf: list[str] = []
    start = 0
    in_fence = False
    for number, line in enumerate(lines, start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        stripped = line.strip()
        if not stripped or stripped.startswith("|") or stripped.startswith("#"):
            if buf:
                out.append((start, " ".join(buf)))
                buf = []
            continue
        if not buf:
            start = number
        buf.append(stripped)
    if buf:
        out.append((start, " ".join(buf)))
    return out


SENTENCE_SPLIT = re.compile(r"(?<=[.!?;])\s+(?=[A-Z0-9`(*])")


def sentences(paragraph: str) -> list[str]:
    return [s.strip() for s in SENTENCE_SPLIT.split(paragraph) if s.strip()]


def ledger_excluded(path: Path) -> bool:
    """Same exclusion set `check-register-consistency.py` already proved: adversarial-review
    findings docs (per-finding SEVERITY grids, not status ledgers) and named HISTORICAL plans
    (tables restate the register at a point in time; the register itself is the checked source).
    Reused rather than reinvented -- a `REG-nn` cell in one of these is not that id's authoritative
    row, so treating it as one produces exactly the false positives corpus calibration is for."""
    if path.name in _rc.LEDGER_EXCLUSIONS:
        return True
    return any(rule.search(path.name) for rule in _rc.LEDGER_EXCLUSION_PATTERNS)


def rule_p1(path: Path, lines: list[str]) -> list[str]:
    if path.name != "<synthetic>" and ledger_excluded(path):
        return []
    authoritative = authoritative_ids(lines)
    if not authoritative:
        return []
    findings: list[str] = []
    for start_line, paragraph in prose_paragraphs(lines):
        for sentence in sentences(paragraph):
            ids = {normalize_id(m.group(0)) for m in ID_TOKEN.finditer(sentence)}
            ids &= authoritative.keys()
            if not ids:
                continue
            verdict = classify_prose(sentence)
            if verdict is None:
                continue
            if has_historical_marker(sentence):
                continue
            for item in sorted(ids):
                if authoritative[item] != verdict:
                    findings.append(
                        f"{path.name}:~{start_line}: P1 drift -- prose reads {item} as "
                        f"{verdict.upper()}, but {item}'s own row says {authoritative[item].upper()}. "
                        f'Sentence: "{sentence[:160]}"'
                    )
    return findings


def rule_p2_text(path_label: str, text: str) -> list[str]:
    lines = text.splitlines()
    header_lines: list[str] = []
    in_status = False
    for line in lines:
        if line.strip().startswith("## Status"):
            in_status = True
            continue
        if in_status:
            if line.strip().startswith("#"):
                break
            if line.strip():
                header_lines.append(line.strip())
    header_verdict = classify_header(" ".join(header_lines)) if header_lines else None

    decision_verdict = None
    for line in lines:
        if "Decision:" in line:
            decision_verdict = classify_prose(line)
            break

    if header_verdict and decision_verdict and header_verdict != decision_verdict:
        return [
            f"{path_label}: P2 drift -- header reads {header_verdict.upper()} but the decision "
            f"block reads {decision_verdict.upper()}."
        ]
    return []


def rule_p2(path: Path) -> list[str]:
    return rule_p2_text(path.name, path.read_text(encoding="utf-8", errors="replace"))


SYNTHETIC_P1_STALE = """\
| ~~**REG-50**~~ | gemini F1+F2 | HIGH | **DONE (2026-07-27).** Tri-state fail-closed fix, verified live. |

REG-50 remains OPEN, pending an owner decision between tri-state and blanket fail-closed.
"""

SYNTHETIC_P1_FIXED = """\
| ~~**REG-50**~~ | gemini F1+F2 | HIGH | **DONE (2026-07-27).** Tri-state fail-closed fix, verified live. |

REG-50 was OPEN pending an owner decision; the owner chose tri-state fail-closed, now DONE.
"""


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

    print("Calibration -- must catch both real 2026-07-27 instances before this ships:")

    adr_path = root / "docs" / "adr" / "ADR-0009-external-ai-delegation.md"
    try:
        head_text = subprocess.run(
            ["git", "show", f"HEAD:{adr_path.relative_to(root).as_posix()}"],
            cwd=root, capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError as exc:
        print(f"  ERROR: could not read HEAD revision of {adr_path.name}: {exc.stderr}", file=sys.stderr)
        return 1
    report("Rule P2 vs. ADR-0009 @ HEAD (pre-fix header, real git revision)",
           rule_p2_text("ADR-0009@HEAD", head_text), expect_fire=True)
    report("Rule P2 vs. ADR-0009 in the working tree (post-fix)",
           rule_p2(adr_path), expect_fire=False)

    report("Rule P1 vs. synthetic REG-50 'remains OPEN' fixture (reconstructed shape, not a real "
           "git revision -- see module docstring for why none exists)",
           rule_p1(Path("<synthetic>"), SYNTHETIC_P1_STALE.splitlines()), expect_fire=True)
    report("Rule P1 vs. the corrected synthetic fixture",
           rule_p1(Path("<synthetic>"), SYNTHETIC_P1_FIXED.splitlines()), expect_fire=False)

    if not ok:
        print("\nFAIL: at least one control did not behave as required -- this detector does not "
              "ship until it does (per REMAINDER_CLOSURE_PLAN.md §2.3).", file=sys.stderr)
        return 1
    print("\nOK: both controls behave correctly. Safe to run report-only against the corpus.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    parser.add_argument("--calibrate", action="store_true", help="run the two required controls and exit")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    if args.calibrate:
        return calibrate(root)

    print("Narrative status drift (report-only -- see module docstring; never fails the build)")
    total = 0
    for path in sorted((root / "docs").rglob("*.md")):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        findings = rule_p1(path, lines)
        if path.name.startswith("ADR-") and "adr" in path.parts:
            findings += rule_p2(path)
        for f in findings:
            print(f"  {f}")
        total += len(findings)

    if total == 0:
        print("  (none found)")
    print(f"\n{total} narrative-drift candidate(s) found. Report-only: exit 0 regardless.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
