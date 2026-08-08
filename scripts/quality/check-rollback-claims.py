#!/usr/bin/env python3
"""Rollback-claim gate: no storage message may promise a rollback the engine cannot deliver.

WHY THIS EXISTS
---------------
STOR-2 was a HIGH-severity bug whose entire content was a sentence. Three refusals in
ConversionHookRunner said, verbatim:

    "-- refusing the boot (the hook's changes were rolled back; nothing persisted)."

H2 -- the engine every NPDev dev app runs on -- COMMITS IMPLICITLY ON DDL, so the ALTER had already
landed. MySQL does the same. **The failure mode is not the un-rolled-back DDL; it is the platform
telling an operator the database is untouched when it is not.** A false all-clear turns a recoverable
half-migration into one nobody goes looking for: the operator reads "nothing persisted", fixes the
model, and re-runs against a schema that already moved.

STOR-2 fixed the three call sites. **That left the next one free to make the same mistake**, and
storage/FULL_SUPPORT_PLAN.md W3 promptly found it -- SchemaHistoryStore.recordStepPass let a raw
SQLException propagate out of a multi-item DDL pass, so a half-applied migration on MySQL reported
nothing at all about what had already become permanent.

Fixing instance two by hand would leave instance three free as well. So the sentence now has exactly
one home -- `com.npdev.kernel.storage.sql.PartialApplicationTruth`, which derives it from
StorageCapability.DDL_IN_TRANSACTION rather than from what an author assumed -- and this gate fails
the moment a storage-surface file spells a rollback claim itself instead of asking.

WHAT IT LOOKS FOR
-----------------
A Java string literal in a storage/schema surface that ASSERTS a rollback happened ("nothing
persisted", "was rolled back", "were rolled back", "no changes persisted"). Explaining engine
behaviour in a comment is fine and encouraged -- only literals are scanned, because only a literal
reaches an operator.

USAGE
    python scripts/quality/check-rollback-claims.py
    python scripts/quality/check-rollback-claims.py --json

Exit 0 = no unguarded claim. Exit 1 = at least one. Exit 2 = usage.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Surfaces that speak to an operator about schema state. Deliberately narrow: this gate is about the
# storage/migration path, not about every "rolled back" in the repo (git talk, docs, REG narratives).
SCAN_ROOTS = [
    "NPDevRuntimeHost/src/main/java/com/finalexec/db",
    "NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage",
    "NPDevKernel/adapters",
]

# The one place allowed to spell the claim, because it is the place that checks the capability first.
TRUTH_SOURCE = "NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PartialApplicationTruth.java"

# Phrases that ASSERT an outcome to an operator. Each is a claim about the database's state, not a
# description of a mechanism.
CLAIM_PATTERNS = [
    (r"nothing persisted", "asserts the database is untouched"),
    (r"no changes persisted", "asserts the database is untouched"),
    (r"(?:was|were|are|is)\s+rolled\s+back", "asserts a rollback completed"),
    (r"rollback\s+undid\s+everything", "asserts a rollback completed"),
]

# A literal that RETRACTS the claim in the same breath is the fix, not the defect -- e.g. "were NOT
# rolled back". Checked on the same literal so a corrected message does not fail the gate.
NEGATED = re.compile(r"\bNOT\b|\bnot\b|cannot|never", re.IGNORECASE)


def _repo_root(explicit: str | None) -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    if explicit:
        return Path(explicit).resolve()
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / m).is_dir()
               for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents. Pass --repo.")


# Java string literals, including the pieces of a `"a" + "b"` concatenation. Escaped quotes are
# handled so a literal containing \" does not end early and swallow the rest of the line.
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def _strip_comments(text: str) -> str:
    """Blank out // and /* */ comments, preserving line structure.

    Comments MUST be excluded: this file's own docstring-equivalent -- every javadoc explaining why
    an engine commits implicitly on DDL -- contains the very phrases being hunted. A gate that fired
    on documentation would be uninstalled within a day.
    """
    out = []
    i = 0
    length = len(text)
    while i < length:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = length if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = length if end < 0 else end + 2
            out.append("".join(c if c == "\n" else " " for c in text[i:end]))
            i = end
        elif text[i] == '"':
            match = STRING_LITERAL.match(text, i)
            if match:
                out.append(match.group(0))
                i = match.end()
            else:
                out.append(text[i])
                i += 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def scan(root: Path) -> list[dict]:
    findings: list[dict] = []
    truth_source = (root / TRUTH_SOURCE).resolve()

    for scan_root in SCAN_ROOTS:
        base = root / scan_root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if path.resolve() == truth_source:
                continue
            if any(part in {"build", "test", "generated"} for part in path.parts):
                continue
            text = _strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            for line_number, line in enumerate(text.splitlines(), start=1):
                for literal_match in STRING_LITERAL.finditer(line):
                    literal = literal_match.group(1)
                    for pattern, why in CLAIM_PATTERNS:
                        if re.search(pattern, literal, re.IGNORECASE) and not NEGATED.search(literal):
                            findings.append({
                                "file": str(path.relative_to(root)).replace("\\", "/"),
                                "line": line_number,
                                "literal": literal.strip()[:160],
                                "why": why,
                                "fix": (
                                    "call PartialApplicationTruth.afterRollback() or "
                                    ".afterFailedMultiStep(...) instead -- it asks the dialect whether "
                                    "DDL_IN_TRANSACTION is supported rather than assuming it. On MySQL "
                                    "and H2 this claim is FALSE, and a false all-clear is what STOR-2 "
                                    "was filed for."
                                ),
                            })
                            break
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    root = _repo_root(args.repo)
    findings = scan(root)

    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-rollback-claim-report.v1",
            "ok": not findings,
            "findings": findings,
        }, indent=2))
        return 1 if findings else 0

    if not findings:
        print("check-rollback-claims: OK -- no storage message asserts a rollback without asking the "
              "dialect first.")
        return 0

    print(f"check-rollback-claims: {len(findings)} unguarded rollback claim(s)")
    for finding in findings:
        print(f"\n  {finding['file']}:{finding['line']}  ({finding['why']})")
        print(f"    \"{finding['literal']}\"")
        print(f"    {finding['fix']}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
