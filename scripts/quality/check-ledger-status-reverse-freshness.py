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
Fail for an item whose ledger `status:` is OPEN when, in PRODUCTION source:
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

HONEST LIMIT -- the marker lists below are HEURISTIC, not a grammar. They were tuned against the
four real open items on 2026-08-10 and calibrated to fire on exactly one of them (QUAL-2, the true
positive) and stay quiet on the other three (QUAL-4 has no code references at all; STOR-13 and
STOR-14 both carry explicit still-open language). Treat a firing as "reconcile this row", not as
proof. When an item legitimately keeps a resolved-looking mention while staying open, add it to
ACCEPTED below WITH A REASON -- an unexplained allowlist entry is the defect this repo keeps
finding, not a fix for it.

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
ACCEPTED: dict[str, str] = {
    "REG-160": (
        "WorkspaceMenuSeeder.java's own javadoc/comment (added by the 2026-08-14 fix) describes HOW "
        "the fix resolves the physical table name at runtime -- 'resolved from the compiled model' "
        "refers to CompiledModel.findConcept(...).getTableName(), a mechanism word, not a claim that "
        "the ledger item itself is closed. The real code change landed, but REG-160's own guard (a "
        "fresh-DB generate+build+boot proof against superuser-admin-console) has deliberately not "
        "been re-run yet -- it is a real fix that HAS NOT been re-verified live, and ledger/items/"
        "REG-160.yml's own 2026-08-14 addendum says exactly that, so status correctly stays OPEN."
    ),
}

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


def calibrate() -> int:
    """Two synthetic controls: a fixed-but-OPEN item MUST fire; an honestly-open one MUST NOT."""
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


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--calibrate", action="store_true", help="run synthetic controls and exit")
    args = ap.parse_args()

    if args.calibrate:
        print("Calibrating check-ledger-status-reverse-freshness:")
        return calibrate()

    ids = open_items()
    found = findings(REPO_ROOT, ids)
    print(f"Reverse status freshness: {len(ids)} OPEN item(s) examined.")

    if not found:
        print("OK: no OPEN item has a landed fix without still-open evidence.")
        return 0

    print(f"\nFAIL: {len(found)} item(s) marked OPEN whose remedy appears to be in the tree.\n")
    for item, mentions in found:
        print(f"  {item}: ledger says OPEN, but production source records the fix:")
        for rel, lineno, text in mentions[:4]:
            print(f"    {rel}:{lineno}: {text[:100]}")
        if len(mentions) > 4:
            print(f"    ... and {len(mentions) - 4} more")
        print(f"    -> re-verify and set status/verification in ledger/items/{item}.yml,")
        print(f"       or add {item} to ACCEPTED in this script WITH a reason.")
        print()
    return 1


if __name__ == "__main__":
    sys.exit(main())
