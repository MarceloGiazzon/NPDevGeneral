#!/usr/bin/env python3
"""Every question SqlDialect can answer must have a caller that ASKS it.

WHY THIS CHECK EXISTS
---------------------
STOR-4, STOR-5 and STOR-6 were the same defect three times:

  STOR-4  the dialects declared their JDBC drivers; the generator never asked, so a generated app
          shipped without the driver for the engine it was configured for.
  STOR-5  the dialects knew which engines commit implicitly on DDL; the guarded-DDL path never
          asked, so a "nothing was persisted" claim was false on two of four engines.
  STOR-6  `quoteIdentifier` was implemented, unit-tested and conformance-green on all four
          dialects -- with ZERO calls anywhere in the generator. A field named `order` could not
          be modelled, and every dialect test still passed.

In all three the dialect layer was complete and green. What was missing was any check that the
thing a user actually runs asks it. `check-dialect-sites.py` is the mirror image of this one: it
fails when SQL is hand-written OUTSIDE the dialect package. This one fails when an answer INSIDE the
package is never requested. Together they close the loop in both directions.

WHAT IT DOES
------------
For each method declared on `SqlDialect`, search production sources OUTSIDE the dialect package for
a call. A method with no caller is reported. Nothing here inspects behaviour -- that is what the
conformance vectors are for. This asks the one question the vectors structurally cannot: *is anyone
listening?*

Tests do not count as callers. A method exercised only by its own unit test is exactly the STOR-6
state: proven correct, wired to nothing.

WHAT IT DOES NOT CATCH -- verified, not assumed
-----------------------------------------------
ONE caller is enough to satisfy it. Deleting the `identifier()` call from `SchemaRealizationEmitter`
-- the literal STOR-6 regression -- leaves this check GREEN, because the two runtime seams still
ask. That was confirmed by breaking it on purpose, not reasoned about. The per-seam question is the
twin-pair rule `sql-identifier-quoting-three-seams`, which fails the moment one of the three seams
stops carrying its token. The two are complementary and neither replaces the other: this one catches
"nobody asks at all", that one catches "one of the seams stopped asking".

ALLOWLIST
---------
`internalOnly` records the methods that legitimately have no external caller, each with a reason.
It is a place to state intent, not a way to silence the check -- an entry with no reason fails, and
an entry for a method that HAS a caller fails too, so the list cannot rot into a list of lies.

Exit 0 = every method is asked (or allowlisted with a reason). Exit 1 = at least one is not.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

DIALECT_PACKAGE = "NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql"
DIALECT_FILE = DIALECT_PACKAGE + "/SqlDialect.java"

# Where a caller may live. Deliberately the production trees only.
SEARCH_ROOTS = [
    "NPDevKernel/kernel/src/main/java",
    "NPDevKernel/adapters",
    "NPDevGenerator/generator/src/main/java",
    "NPDevRuntimeHost/src/main/java",
    "NPDevContract/dsl/src/main/java",
]

# Methods with no caller outside the dialect package, and why that is correct.
#
# Two kinds of entry live here, and the difference matters:
#   - genuinely internal: the dialects call it themselves, and no external caller is expected.
#   - STOR-13: declared, implemented on all four dialects, and asked by NOTHING a user runs. These
#     are the same shape as STOR-4/5/6 and are a real open backlog, enumerated so it is visible
#     rather than silent. They are NOT excused -- they are filed.
INTERNAL_ONLY = {
    "require": "the dialects' own X0 guard -- a dialect that cannot honour a question throws rather "
               "than returning another engine's answer. Called by the dialects, by design.",
    "quoteIdentifier": "the unconditional form. Callers ask identifier(), which quotes only when "
                       "isReservedIdentifier() says this engine reserves the name (STOR-6); quoting "
                       "everything would change the emitted SQL of every deployed database.",
    "isReservedIdentifier": "the predicate behind identifier(). Callers ask identifier() and get "
                            "the decision for free; asking this directly would mean re-implementing "
                            "the quoting rule at the call site, which is the STOR-6 defect.",
    "foldsUnquotedIdentifiersToLowerCase": "consulted by identifier() before quoting -- on the "
                                           "engines that fold, a deployed column really is "
                                           "lowercase, so the quoted form has to match it.",
    "identifiers": "the list form of identifier(), used by the upsert strategies as they compose "
                   "statement text inside the dialects.",
    "requireOrderedForPagination": "the guard behind paginated(); callers get it for free by "
                                   "calling paginated() and cannot bypass it.",

    # --- STOR-13: no production caller. Filed, not excused. ---
    "supports": "STOR-13. capabilities() is consulted by StorageCapabilityGate at GENERATION time; "
                "nothing asks at runtime, which is where CLAUDE.md's documented STOR-2 remedy "
                "(ask before assuming a rollback) would actually have to run.",
    "autoIncrementColumn": "STOR-13. No production caller; NPDev's ids are UUIDs, so nothing has "
                           "needed an auto-increment column yet.",
    "timestampColumnType": "STOR-13. No production caller and no test caller either.",
    "requiresOrderByForPagination": "STOR-13. No production caller; paginated() enforces the rule "
                                    "itself via requireOrderedForPagination.",
    "cast": "STOR-13. No production caller and no test caller either.",
    "returning": "STOR-13. No production caller; the INSERT paths read generated keys through JDBC "
                 "rather than a RETURNING clause.",
    "limitOnly": "STOR-13. No production caller; callers reach for limited()/paginated().",
    "rowLimit": "STOR-13. No production caller; callers reach for rowLimited()/paginated().",
    "listTablesSql": "STOR-13. Introspection, exercised only by the conformance vectors.",
    "listColumnsSql": "STOR-13. Introspection, exercised only by the conformance vectors.",
    "listIndexesSql": "STOR-13. Introspection, exercised only by the conformance vectors.",
}


def repo_root() -> Path:
    """Identify the repo by its CONTENTS, never by its directory name (REG-144)."""
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / module).is_dir()
               for module in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents")


def declared_methods(source: str) -> list[str]:
    """The method names SqlDialect declares.

    Block comments are stripped first: this interface's javadoc quotes SQL and Java liberally, and a
    code sample inside a comment reads exactly like a declaration to a regex.
    """
    body = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    pattern = re.compile(
        r"^\s{4}(?:default\s+|static\s+)?(?!private)([A-Za-z_][\w.<>\[\], ?]*)\s+([a-z]\w*)\s*\(", re.M)
    return sorted({match.group(2) for match in pattern.finditer(body)})


def production_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for relative in SEARCH_ROOTS:
        base = root / relative
        if not base.is_dir():
            continue
        for path in base.rglob("*.java"):
            posix = path.relative_to(root).as_posix()
            if DIALECT_PACKAGE in posix:
                continue
            # `src/test` under an adapter tree, and any *Test.java, are not callers: a method
            # exercised only by its own test is precisely the state STOR-6 was in.
            if "/src/test/" in posix or path.name.endswith("Test.java"):
                continue
            files.append(path)
    return files


# A call only counts when the RECEIVER is a dialect. Matching `.method(` alone reported three false
# callers on the first run -- unrelated classes with a `require`/`limited`/`rowLimited` method of
# their own. A checker that over-reports callers is worse than none: it would have declared STOR-6's
# `quoteIdentifier` wired while it had zero real callers.
RECEIVER = re.compile(r"(?i)(dialect|SqlDialects\.active|INSTANCE|forConnection)")
RECEIVER_WINDOW = 90


def callers_of(method: str, blobs: list[tuple[str, str]]) -> list[str]:
    needle = re.compile(r"[.:]" + re.escape(method) + r"\s*\(")
    found = []
    for name, text in blobs:
        for match in needle.finditer(text):
            window = text[max(0, match.start() - RECEIVER_WINDOW):match.start()]
            if RECEIVER.search(window):
                found.append(name)
                break
    return found


def main() -> int:
    root = repo_root()
    dialect_source = (root / DIALECT_FILE).read_text(encoding="utf-8")
    methods = declared_methods(dialect_source)
    if len(methods) < 20:
        print(f"FAILED: only {len(methods)} method(s) parsed out of SqlDialect -- the declaration "
              f"regex has stopped matching, and a check that finds nothing to check passes silently.")
        return 1

    blobs = [(path.relative_to(root).as_posix(), path.read_text(encoding="utf-8", errors="replace"))
             for path in production_files(root)]

    unasked: list[str] = []
    stale_allowlist: list[str] = []
    for method in methods:
        callers = callers_of(method, blobs)
        allowed = method in INTERNAL_ONLY
        if callers and allowed:
            stale_allowlist.append(f"{method} -- allowlisted as internal-only, but called by "
                                   f"{callers[0]}{' and others' if len(callers) > 1 else ''}")
        elif not callers and not allowed:
            unasked.append(method)

    for method, reason in INTERNAL_ONLY.items():
        if method not in methods:
            stale_allowlist.append(f"{method} -- allowlisted, but SqlDialect no longer declares it")
        elif not reason.strip():
            stale_allowlist.append(f"{method} -- allowlisted with no reason given")

    print(f"SqlDialect declares {len(methods)} method(s); {len(blobs)} production source file(s) "
          f"searched for callers.")
    if unasked:
        print(f"FAILED: {len(unasked)} dialect method(s) that nothing outside the dialect package asks:")
        for method in unasked:
            print(f"  - {method}")
        print("\nThis is the STOR-4/5/6 shape: the dialect answers correctly and the thing a user "
              "runs never asks. Either wire a caller, or add the method to INTERNAL_ONLY with the "
              "reason it has none.")
    if stale_allowlist:
        print(f"FAILED: {len(stale_allowlist)} stale allowlist entr(ies):")
        for entry in stale_allowlist:
            print(f"  - {entry}")
    if unasked or stale_allowlist:
        return 1
    print("OK: every SqlDialect method has a production caller, or is allowlisted with a reason.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
