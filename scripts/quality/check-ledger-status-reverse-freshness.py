#!/usr/bin/env python3
"""Reverse status freshness: is an item still marked OPEN after its fix already landed?

WHY THIS EXISTS
---------------
`check-blocker-citation-freshness.py` tests exactly ONE direction: a doc that still calls REG-nnn a
live blocker after REG-nnn's ledger row went DONE. Nothing tested the reverse -- an item whose row
says OPEN while the remedy is already in the tree.

That gap was found by reconciling a green gate pass against the four open items (2026-08-10):
QUAL-2 was `status: OPEN` / `verification: NOT_VERIFIED` while four production files carried its fix
and named it by id, and gate check 37/40 independently scanned 1464 Java files and found zero
unclosed streams. The consequence is not cosmetic: the open-items COUNT is the number quoted in
handover documents and release notes, so an item that is fixed-but-OPEN overstates the debt and
makes the one number a third party reads wrong. "159 DONE / 4 OPEN" was an upper bound being
reported as a measurement.

THE RULE
--------
Two independent rules, R1 and R2, either of which can fail an item.

R1 -- SOURCE-COMMENT ARCHAEOLOGY (the original rule). Fail for an item whose ledger `status:` is
OPEN when, in PRODUCTION source:
  - at least one COMMENT names the id  (resolved-evidence), and
  - NO still-open language appears near ANY mention of that id.

All three conditions carry weight:

  COMMENT, not string literal -- `"ledgerId": "STOR-13"` in NPDevCli/release_candidate.py is the RC
  gate reporting a backlog row as data. It is not a claim that anything was fixed.

  PRODUCTION, not checker -- scripts/quality/ and scripts/hygiene/ describe gaps for a living. A
  checker that enumerates STOR-13's uncalled methods is doing its job, not recording a remedy.

  NO still-open language ANYWHERE for that id -- one honest "not yet" outweighs any number of
  mentions, because prose describing a FUTURE feature also names its id. OperationalRunbookEmitter
  discusses STOR-14 at length in a production Java comment; it also says "NPDev has no EXTERNAL mode
  yet" and "DETECT, do not solve", and that is the sentence that settles it.

HONEST LIMIT (R1) -- the marker lists below are HEURISTIC, not a grammar. They were tuned against
the four real open items on 2026-08-10 and calibrated to fire on exactly one of them (QUAL-2, the
true positive) and stay quiet on the other three (QUAL-4 has no code references at all; STOR-13 and
STOR-14 both carry explicit still-open language). Treat a firing as "reconcile this row", not as
proof. When an item legitimately keeps a resolved-looking mention while staying open, add it to
ACCEPTED below WITH A REASON -- an unexplained allowlist entry is the defect this repo keeps
finding, not a fix for it.

R2 -- LANDED-FIX-BY-HISTORY (2026-08-14, session closeout after R1 was found blind to six live
misses: REG-147/153/160/161/162/169 all carried a real, merged fix and a full `resolution:` block,
and none of the six tripped R1, because R1 only ever reads PRODUCTION SOURCE COMMENTS -- it does not
consult git at all. A fix whose commit lands in a mustache template, a PowerShell script, a JSON
fixture, or a workflow YAML never leaves a "// STOR-13" style comment behind for R1 to find; a
commit message naming the id is not the same signal R1 looks for. R2 closes that gap by asking git
directly instead of asking the source tree to describe itself.

Fail for an item whose `status:` is OPEN when its own `ledger/items/<ID>.yml` was MODIFIED (git
status 'M', not the commit that first added it) by a commit that ALSO touched production source
(anything outside `ledger/`, `docs/`, `scripts/quality/` -- the same "checkers describe gaps for a
living" exemption R1 already uses).

THE DISCRIMINATOR (measured, not assumed, TWICE): the first version of this rule fired whenever the
item's .yml was touched (added OR modified) by a commit that also touched production source. Two
other, cruder candidates were measured first and rejected:
  - "a fix/feat commit names the id in its subject" caught 5 of 6 -- missed REG-169, whose fix
    commit message is "stop record.data() from clobbering tenant_id/id" and never says the id.
  - "the item's .yml was touched by ANY fix/feat commit" caught all 6 but ALSO fired on REG-146,
    REG-148, PACK-9, RUN-5 (19 findings total) -- items a `feat` commit FILED, not fixed.

The "touched (added-or-modified) + production source" version looked right and matched the plan
that specified it -- then measuring it against this repo's REAL history (not just synthetic
controls) found it ALSO fires on REG-146, REG-148 and PACK-9, all three genuinely still open. Root
cause: this repo merges every PR to `main` SQUASHED. A branch's own internal commit boundaries (a
`chore(ledger): file N items` step distinct from the `feat(...)` step that prompted it) collapse
into ONE commit on `main` once merged, so a big feature PR that incidentally files a new gap in the
SAME squashed commit as its own production-code changes is indistinguishable, by "touched", from a
PR that actually fixes a specific item. `git show --name-status` on each of REG-146/148/PACK-9's
one and only ledger-file commit confirms it: status 'A' (added), never modified again. Every one of
the six real misses (REG-147/153/160/161/162/169) shows the opposite: an 'A' commit that files it
(often the SAME squashed feature PR, e.g. REG-147/148/146 were all added by the PK-3 merge commit),
then a LATER, SEPARATE commit -- a dedicated fix -- that MODIFIES ('M') the same file. Requiring 'M'
is what a squash merge cannot erase: it is still true, on `main`, that the fix landed in a commit
distinct from the one that first created the row, however many of that commit's own original
sub-commits got flattened into it.

HONEST LIMIT (R2) -- this is a heuristic over commit TOPOLOGY, not a proof the item is actually
fixed (a commit can modify the ledger file and touch production source for unrelated reasons -- an
item edited only to reword its `detail:`, in the same squashed PR as unrelated production work).
A firing means *reconcile this row* -- read what the commit actually did -- not *this item is
closed*. Same ACCEPTED-with-a-reason escape hatch as R1, in a separate dict below so a reason
written for one rule is never read as covering the other.

CALIBRATE BEFORE TRUSTING IT (same discipline as check-blocker-citation-freshness.py)
--------------------------------------------------------------------------------------
    python scripts/quality/check-ledger-status-reverse-freshness.py --calibrate

USAGE
-----
    python scripts/quality/check-ledger-status-reverse-freshness.py            # exit 1 on any finding
    python scripts/quality/check-ledger-status-reverse-freshness.py --calibrate  # self-test
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LEDGER_DIR = REPO_ROOT / "ledger" / "items"

# Where a REMEDY plausibly lives. Deliberately excludes scripts/quality + scripts/hygiene (checkers
# enumerate gaps by design), ledger/ and docs/ (the record itself, covered by the forward check).
PRODUCTION_ROOTS = (
    "NPDevContract",
    "NPDevGenerator",
    "NPDevKernel",
    "NPDevRuntimeHost",
    "NPDevManager/src",
    "NPDevCli",
    "NPDevMcp",
    "NPDevEditor/ui-react/src",
)

SOURCE_SUFFIXES = {".java", ".py", ".ps1", ".rs", ".ts", ".tsx", ".kt", ".sql"}

# Generated bundles and vendored trees carry no hand-written remedy annotations.
EXCLUDE_PARTS = {
    "node_modules", "build", "target", "dist", ".git", ".gradle",
    "npdev-templates", "static-react", "__pycache__", "out",
}

COMMENT_MARKERS = ("//", "#", "*", "--", "<!--")

# Any ONE of these near a mention means the item is still open, whatever else is said about it.
STILL_OPEN_MARKERS = (
    "not yet", "no caller", "open backlog", "are filed", "is filed", "was filed",
    "deferred", "todo", "fixme", "do not solve", "not implemented", "planned",
    "will be", "would be", "no external mode", "has no ", "have no ",
    "not started", "unbuilt", "backlog", "refuses", "future",
)

# Language that reads as "the remedy is HERE, in this code".
RESOLVED_MARKERS = (
    "try-with-resources", "no longer", "is now", "are now", "now closes", "now uses",
    "fixed", "resolved", "closes ", "corrected", "is simply false", "instead of",
    "guards", "guarded", "prevents", "so that", "which is why",
)

CONTEXT_LINES = 3

# id -> reason. An entry here MUST say why a resolved-looking mention coexists with a live OPEN row.
ACCEPTED: dict[str, str] = {}

# R2's own ACCEPTED list -- separate from R1's so a reason written for one rule is never silently
# read as covering the other (they fire on different evidence and can both be wrong independently).
ACCEPTED_R2: dict[str, str] = {
    "PACK-9": (
        "The commit R2 names (6250f6b3, the PK-3 merge) genuinely MODIFIED PACK-9.yml -- not a "
        "false 'A-commit' positive like REG-146/148 -- adding real substance: 'the DECLARATION and "
        "PRESENCE/BINDING half of this is now done... What's still open, and is this item's real "
        "remaining scope: a pack's own INTERNAL code... still only references concrete role names.' "
        "That is a legitimate partial-progress edit that correctly leaves the item OPEN with a "
        "narrower remaining scope, not a fix. R2 firing here is working as intended (reconcile this "
        "row), not a false positive -- accepted because the reconciliation is already done and the "
        "conclusion is 'still open', not a code or rule change."
    ),
    "REG-170": (
        "The commit R2 names (3e17e37a, the 2026-08-14 closeout PR #90 squash-merge) genuinely "
        "MODIFIED REG-170.yml -- but the edit was 'CONFIRMED, upgraded from PROBABLE, and scoped to "
        "4 files, deferred' (a triage upgrade), not a fix, and the commit's production-source "
        "changes were for REG-171/172/173 in the SAME squashed PR, unrelated to REG-170's own "
        "IdentityProvisioning bug. Same shape as PACK-9 above: a legitimate 'reconcile this row' "
        "firing whose reconciliation is already done and the conclusion is 'still open'."
    ),
}

# R2: same "checkers describe gaps for a living" exemption R1's PRODUCTION_ROOTS encodes, spelled as
# a blocklist instead of an allowlist because R2 must classify EVERY changed path in a commit, not
# just ones under a fixed set of source roots (a commit can touch build.gradle, a .mustache
# template, a workflow YAML, or a JSON fixture -- none of which live under PRODUCTION_ROOTS at all).
R2_NON_PRODUCTION_ROOTS = ("ledger", "docs", "scripts/quality")

ID_IN_LEDGER = re.compile(r"^([A-Z]+-\d+)\.yml$")


def open_items() -> list[str]:
    ids = []
    for path in sorted(LEDGER_DIR.glob("*.yml")):
        m = ID_IN_LEDGER.match(path.name)
        if not m:
            continue
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("status:"):
                if line.split(":", 1)[1].strip() == "OPEN":
                    ids.append(m.group(1))
                break
    return ids


def production_files(root: Path) -> list[Path]:
    files = []
    for rel in PRODUCTION_ROOTS:
        base = root / rel
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.suffix not in SOURCE_SUFFIXES:
                continue
            if EXCLUDE_PARTS & set(path.parts):
                continue
            files.append(path)
    return files


def is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith(COMMENT_MARKERS) or "//" in line or "#" in line


def scan(root: Path, ids: list[str]) -> dict[str, dict]:
    """Return {id: {"resolved": [(path, lineno, text)], "still_open": [(path, lineno, text)]}}."""
    evidence = {i: {"resolved": [], "still_open": []} for i in ids}
    if not ids:
        return evidence
    pattern = re.compile(r"\b(" + "|".join(re.escape(i) for i in ids) + r")\b")

    for path in production_files(root):
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for idx, line in enumerate(lines):
            for match in pattern.finditer(line):
                item = match.group(1)
                if not is_comment(line):
                    continue  # a string literal / data row is not a remedy claim
                lo = max(0, idx - CONTEXT_LINES)
                hi = min(len(lines), idx + CONTEXT_LINES + 1)
                window = " ".join(lines[lo:hi]).lower()
                rel = path.relative_to(root).as_posix()
                if any(m in window for m in STILL_OPEN_MARKERS):
                    evidence[item]["still_open"].append((rel, idx + 1, line.strip()))
                elif any(m in window for m in RESOLVED_MARKERS):
                    evidence[item]["resolved"].append((rel, idx + 1, line.strip()))
    return evidence


def findings(root: Path, ids: list[str]) -> list[tuple[str, list]]:
    evidence = scan(root, ids)
    out = []
    for item in ids:
        if item in ACCEPTED:
            continue
        ev = evidence[item]
        if ev["resolved"] and not ev["still_open"]:
            out.append((item, ev["resolved"]))
    return out


def _git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=root, capture_output=True, text=True, check=True,
    )
    return result.stdout


def _touches_production(root: Path, sha: str) -> bool:
    """True if commit `sha` changed any path outside R2_NON_PRODUCTION_ROOTS."""
    paths = [p for p in _git(root, "show", "--name-only", "--pretty=format:", sha).splitlines() if p.strip()]
    for p in paths:
        if not any(p == r or p.startswith(r + "/") for r in R2_NON_PRODUCTION_ROOTS):
            return True
    return False


def _modifying_commits_touching(root: Path, relpath: str) -> list[str]:
    """Commits that MODIFIED an already-existing `relpath` -- excludes the commit that first added
    it (git status 'A'). This distinction is load-bearing, not cosmetic: every real PR in this repo
    merges to `main` SQUASHED, so a multi-commit branch's internal commit boundaries (a `chore
    (ledger): file N items` step separate from a `feat(...)` step) collapse into ONE commit on
    `main` that touches ledger/ and production together purely because it is a big squashed PR, not
    because anything in it fixed the specific item. Measured directly against this repo's own
    history (2026-08-14): the naive "any commit touching this file + production" version fired on
    REG-146, REG-148 and PACK-9 -- all three genuinely still OPEN, all three FILED (git status 'A'
    on their one and only ledger-file commit) as an incidental byproduct of an unrelated squashed
    feature PR, never modified again. Every one of the six real misses this rule exists to catch
    (REG-147/153/160/161/162/169) shows the opposite, uniform shape: an 'A' commit that files it
    (often the same big squashed PR), then a LATER, SEPARATE 'M' commit -- a dedicated fix -- that
    modifies the same file. Requiring 'M' is what tells those two shapes apart; requiring only
    "touched" cannot, once squash-merge has erased the sub-commit boundaries that would otherwise
    show it.
    """
    log = _git(root, "log", "--follow", "--name-status", "--pretty=format:__COMMIT__%H", "--", relpath)
    modifying = []
    current_sha = None
    for line in log.splitlines():
        if line.startswith("__COMMIT__"):
            current_sha = line[len("__COMMIT__"):]
            continue
        if not line.strip() or current_sha is None:
            continue
        status = line.split("\t", 1)[0]
        if status.startswith("M"):
            modifying.append(current_sha)
    return modifying


def findings_r2(root: Path, ids: list[str]) -> list[tuple[str, list[str]]]:
    """R2: an OPEN item whose own ledger file was MODIFIED (not merely filed) by a commit that also
    touched production source -- see R2's docstring section above for why 'modified, not filed' is
    the discriminator, not just 'touched'."""
    out = []
    for item in ids:
        if item in ACCEPTED_R2:
            continue
        try:
            shas = _modifying_commits_touching(root, f"ledger/items/{item}.yml")
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue  # not a git repo (e.g. calibrate() runs findings() on a bare tempdir too)
        fixing = [sha for sha in shas if _touches_production(root, sha)]
        if fixing:
            out.append((item, fixing))
    return out


def calibrate() -> int:
    """R1: a fixed-but-OPEN item MUST fire; an honestly-open one MUST NOT."""
    ok = True
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "ledger" / "items").mkdir(parents=True)
        src = root / "NPDevRuntimeHost" / "src"
        src.mkdir(parents=True)

        (root / "ledger" / "items" / "ZZZ-1.yml").write_text("status: OPEN\n", encoding="utf-8")
        (root / "ledger" / "items" / "ZZZ-2.yml").write_text("status: OPEN\n", encoding="utf-8")

        # ZZZ-1: remedy in place, no still-open language -> MUST fire.
        (src / "Fixed.java").write_text(
            "class A {\n    // try-with-resources (ZZZ-1): the stream is now closed.\n}\n",
            encoding="utf-8",
        )
        # ZZZ-2: production prose describing a FUTURE feature -> MUST NOT fire.
        (src / "Planned.java").write_text(
            "class B {\n    // That is ZZZ-2, and NPDev has no such mode yet -- detect, do not solve.\n}\n",
            encoding="utf-8",
        )

        got = {i for i, _ in findings(root, ["ZZZ-1", "ZZZ-2"])}

        for label, cond in (
            ("fixed-but-OPEN fires", "ZZZ-1" in got),
            ("honestly-open stays quiet", "ZZZ-2" not in got),
        ):
            print(f"  {'PASS' if cond else 'FAIL'}  {label}")
            ok &= cond
    return 0 if ok else 1


def calibrate_r2() -> int:
    """R2 controls, run against a REAL temp git repo (R2's evidence IS commit topology, so a
    synthetic file tree with no git history cannot exercise it -- unlike R1's calibrate() above).

    Three controls. The first version of this rule (no 'M' requirement) was calibrated against a
    "filed and fixed in one commit -> MUST fire" control the introducing plan named as "the REG-147
    shape" -- MEASURING REG-147's actual git history (not assuming the plan's characterization) found
    that description wrong: REG-147 is two commits (file, then a LATER separate fix), never one. The
    controls below reflect the measured shapes, not the assumed one:
      1. filed AND fixed in the SAME commit (single 'A', no later 'M')     -> MUST stay quiet
      2. filed by a commit that also adds an unrelated feature (same 'A'-only shape as #1, just with
         unrelated rather than fixing production content -- included to show the rule can't and
         doesn't try to tell these apart, which is the honest limit stated above)
                                                                             -> MUST stay quiet
      3. filed in commit A, fixed in a LATER, separate commit B (every real miss's actual shape,
         REG-147 included)                                                 -> MUST fire
    """
    ok = True
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        _git(root, "init", "-q")
        _git(root, "config", "user.email", "calibrate@example.invalid")
        _git(root, "config", "user.name", "calibrate")
        (root / "ledger" / "items").mkdir(parents=True)
        (root / "NPDevKernel" / "src").mkdir(parents=True)

        def commit(msg: str) -> None:
            _git(root, "add", "-A")
            _git(root, "commit", "-q", "-m", msg)

        # Control 1: filed-and-fixed together, one commit, no later revisit -> stays quiet (the
        # rule cannot see past a single 'A' -- see the module docstring's HONEST LIMIT).
        (root / "ledger" / "items" / "ZZZ-1.yml").write_text("status: OPEN\n", encoding="utf-8")
        (root / "NPDevKernel" / "src" / "Fix1.java").write_text("class Fix1 {}\n", encoding="utf-8")
        commit("fix(kernel): file+fix ZZZ-1 in one commit")

        # Control 2: filed only, by a commit that also happens to add a feature elsewhere -- the
        # measured REG-146/148/PACK-9 shape (a squash-merged PR's ledger-filing step and its
        # unrelated feature-code step collapse into one 'A' commit on main).
        (root / "ledger" / "items" / "ZZZ-2.yml").write_text("status: OPEN\n", encoding="utf-8")
        (root / "NPDevKernel" / "src" / "Feature2.java").write_text("class Feature2 {}\n", encoding="utf-8")
        commit("feat(kernel): add Feature2, file ZZZ-2 as a followup gap")

        # Control 3: filed in commit A, fixed in a later, separate commit B -- the measured shape of
        # all six real misses (REG-147/153/160/161/162/169).
        (root / "ledger" / "items" / "ZZZ-3.yml").write_text("status: OPEN\n", encoding="utf-8")
        commit("chore(ledger): file ZZZ-3")
        (root / "ledger" / "items" / "ZZZ-3.yml").write_text(
            "status: OPEN\nresolution: fixed, batch-verification pending\n", encoding="utf-8",
        )
        (root / "NPDevKernel" / "src" / "Fix3.java").write_text("class Fix3 {}\n", encoding="utf-8")
        commit("fix(kernel): resolve ZZZ-3")

        got = {i for i, _ in findings_r2(root, ["ZZZ-1", "ZZZ-2", "ZZZ-3"])}

        for label, cond in (
            ("filed-and-fixed-in-one-commit stays quiet (honest limit)", "ZZZ-1" not in got),
            ("filed-only-alongside-an-unrelated-feature stays quiet", "ZZZ-2" not in got),
            ("filed-then-fixed-later fires (every real miss's own shape)", "ZZZ-3" in got),
        ):
            print(f"  {'PASS' if cond else 'FAIL'}  {label}")
            ok &= cond
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--calibrate", action="store_true", help="run synthetic controls and exit")
    args = ap.parse_args()

    if args.calibrate:
        print("Calibrating check-ledger-status-reverse-freshness (R1 -- source-comment archaeology):")
        r1_ok = calibrate() == 0
        print("Calibrating check-ledger-status-reverse-freshness (R2 -- landed-fix-by-history):")
        r2_ok = calibrate_r2() == 0
        return 0 if (r1_ok and r2_ok) else 1

    ids = open_items()
    found_r1 = findings(REPO_ROOT, ids)
    found_r2 = findings_r2(REPO_ROOT, ids)
    print(f"Reverse status freshness: {len(ids)} OPEN item(s) examined (R1 + R2).")

    if not found_r1 and not found_r2:
        print("OK: no OPEN item has a landed fix without still-open evidence.")
        return 0

    if found_r1:
        print(f"\nFAIL (R1 -- source-comment archaeology): {len(found_r1)} item(s) marked OPEN whose "
              f"remedy appears to be in the tree.\n")
        for item, mentions in found_r1:
            print(f"  {item}: ledger says OPEN, but production source records the fix:")
            for rel, lineno, text in mentions[:4]:
                print(f"    {rel}:{lineno}: {text[:100]}")
            if len(mentions) > 4:
                print(f"    ... and {len(mentions) - 4} more")
            print(f"    -> re-verify and set status/verification in ledger/items/{item}.yml,")
            print(f"       or add {item} to ACCEPTED in this script WITH a reason.")
            print()

    if found_r2:
        print(f"\nFAIL (R2 -- landed-fix-by-history): {len(found_r2)} item(s) marked OPEN whose own "
              f"ledger file was touched by a commit that also touched production source.\n")
        for item, shas in found_r2:
            print(f"  {item}: ledger says OPEN, but a commit touching ledger/items/{item}.yml also "
                  f"touched production source:")
            for sha in shas[:4]:
                print(f"    {sha[:10]}")
            if len(shas) > 4:
                print(f"    ... and {len(shas) - 4} more")
            print(f"    -> reconcile this row (read what the commit(s) actually did) -- a firing means "
                  f"'reconcile', not 'closed' -- then set status/verification in "
                  f"ledger/items/{item}.yml, or add {item} to ACCEPTED_R2 in this script WITH a reason.")
            print()

    return 1


if __name__ == "__main__":
    sys.exit(main())
