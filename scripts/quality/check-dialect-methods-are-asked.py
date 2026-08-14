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
    # BT-1: most SqlDialect callers in RuntimeHost (SchemaLifecycleExecutor, JdbcBusinessConceptStore)
    # are app-independent (no com.npdev.generated. reference) and now live in runtimehost-core, a
    # separate Gradle module (scripts/proofs/classify_runtimehost_sources.py) -- without this, their
    # real callers vanish from view and every method they call reads as dead.
    "NPDevRuntimeHost/runtimehost-core/src/main/java",
    "NPDevContract/dsl/src/main/java",
]

# Methods with no caller outside the dialect package, and why that is correct.
#
# Three kinds of entry live here, and the difference matters:
#   - genuinely internal: the dialects call it themselves, and no external caller is expected.
#   - prepared early: implemented on all four dialects before any consumer exists. STOR-13 measured
#     these and CLOSED them as a deliberate decision rather than a backlog -- an answer written
#     before its question is early, not wrong. What was missing was any record of which it was;
#     these entries are that record. Decide again when a consumer appears.
#   - covered by a non-production consumer: asked by the conformance vectors against real engines.
#     That is a real consumer even though this checker's search roots (production only, by design)
#     structurally cannot see it, so it must be stated here or it reads as dead weight.
#
# STOR-13 opened with nine names on this list. Six were false alarms the one-hop rule below removed,
# one (`requiresOrderByForPagination`) was DELETED from the interface, and the eight-minus-one that
# remain are the seven entries below. None of them is an open chore.
INTERNAL_ONLY = {
    # `require` graduated OFF this list, R8c (RUN-2, 2026-08-14) -- recorded here rather than
    # silently deleted, per this file's own "an allowlist that asserts something untrue is worse
    # than none" discipline.
    #
    # The entry used to read "the dialects' own X0 guard ... Called by the dialects, by design",
    # which was ALREADY false the moment it was written: grepping every dialect-package source for
    # `require(` before this change found zero callers anywhere, not even from within the package
    # itself. `require(StorageCapability)` is a generic precondition helper -- it calls only
    # `supports()` and `name()`, both engine-agnostic, and contains no dialect-specific SQL text of
    # its own -- so there was never an architectural reason for it to be internal-only; the entry
    # just predated any real caller.
    #
    # `JdbcFlowInstanceStore.claimWaitingEligibleToResume` (R8c's resume-claim) is the first one:
    # `dialect.require(StorageCapability.SKIP_LOCKED_READS)` before building a SKIP LOCKED
    # statement, the exact X0-rule usage `StorageCapability`'s own class javadoc prescribes
    # ("SqlDialect#require(StorageCapability) is how a site asks"). That call lives in
    # NPDevKernel/adapters/flowinstance-postgres, an ordinary production search root -- so `require`
    # now has a real external caller and must be REMOVED from this allowlist, not just left with a
    # stale reason, or this checker's own "callers + allowlisted = stale" rule (below) fails on it.
    "isReservedIdentifier": "the predicate behind identifier(). Callers ask identifier() and get "
                            "the decision for free; asking this directly would mean re-implementing "
                            "the quoting rule at the call site, which is the STOR-6 defect.",
    "identifiers": "the list form of identifier(), used by the upsert strategies as they compose "
                   "statement text inside the dialects.",

    # --- STOR-13, AFTER the one-hop rule and after its closure. ---
    #
    # SIX entries left this list when reachability was measured instead of assumed, and one of them
    # matters more than the rest: `supports` was filed as "nothing asks at runtime, which is where
    # CLAUDE.md's documented STOR-2 remedy would actually have to run". That was FALSE.
    # `PartialApplicationTruth.afterRollback()` asks it, and ConversionHookRunner and
    # SchemaHistoryStore call that on the real failure path -- so a user on MySQL or H2 IS told their
    # schema changes were already committed. The capability model is enforced at runtime; it was the
    # checker's package exclusion that made it look otherwise, and this allowlist repeated the
    # mistake as fact. An allowlist that asserts something untrue is worse than none, because the
    # next reader believes it and "fixes" a non-problem.
    #
    # Also left: limitOnly (<- limited()), rowLimit (<- rowLimited()), quoteIdentifier and
    # foldsUnquotedIdentifiersToLowerCase (<- identifier()), requireOrderedForPagination
    # (<- paginated()).
    #
    # The REDUNDANT group had exactly one member and it is gone: `requiresOrderByForPagination` was
    # deleted from SqlDialect and its four implementations, because requireOrderedForPagination()
    # demands ORDER BY of EVERY engine regardless of the answer, so no caller could ever act on it.
    # Shipping the unconditional rule AND a flag that reads like it gates the rule was the defect.
    # Nothing replaces the entry here -- this checker fails on an allowlist entry for a method the
    # interface no longer declares, which is what keeps the deletion honest.
    #
    # What remains is genuinely unasked, in two groups, and both are decisions rather than chores:
    #
    #   PREPARED EARLY, no consumer yet. Decide again when one appears; the reason each answer exists
    #   before its question is the point of the entry.
    "autoIncrementColumn": "STOR-13 (prepared early, deliberate). No caller; NPDev's ids are UUIDs, "
                           "so nothing has needed an auto-increment column yet. Kept because the "
                           "engines genuinely differ here and re-deriving it later is the expensive "
                           "half.",
    "timestampColumnType": "STOR-13 (prepared early, deliberate). No caller in production or test; "
                           "column types are emitted through portableColumnType() today.",
    "cast": "STOR-13 (prepared early, deliberate). No caller in production or test. Every engine "
            "spells CAST portably enough that no site has needed the dialect to arbitrate yet.",
    "returning": "STOR-13 (prepared early, deliberate). The INSERT paths read generated keys through "
                 "JDBC's getGeneratedKeys rather than a RETURNING clause, so the strategy is "
                 "answered and unused.",

    #   COVERED BY THE CONFORMANCE VECTORS -- a real consumer that this checker's production-only
    #   search roots structurally cannot see. Not "no caller": DialectConformanceTierATest asserts
    #   their shape on all four dialects and TierB executes listColumnsSql against real engines.
    "listTablesSql": "STOR-13 (covered). Asked by DialectConformanceTierATest against all four "
                     "dialects -- a real consumer, outside this checker's production-only roots.",
    "listColumnsSql": "STOR-13 (covered). Asked by DialectConformanceTierATest and EXECUTED by "
                      "DialectConformanceTierBTest against real engines.",
    "listIndexesSql": "STOR-13 (covered). Asked by DialectConformanceTierATest against all four "
                      "dialects -- a real consumer, outside this checker's production-only roots.",
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


# One hop, and it removes a whole class of FALSE alarm.
#
# A method can be reached from production without any production file naming it: `limited()` and
# `rowLimited()` are `default` methods ON SqlDialect that call `limitOnly()` / `rowLimit()`, and
# `PartialApplicationTruth` (in the dialect package, but PUBLIC and called by ConversionHookRunner
# and SchemaHistoryStore) calls `supports()`. All three were filed under STOR-13 as "no production
# caller", which read as dead weight -- and for `supports` the allowlist went further and asserted
# that "nothing asks at runtime", which is simply false: that is exactly where STOR-2's documented
# remedy runs. An allowlist that states something untrue is worse than no allowlist, because the
# next reader believes it.
#
# So: a method also counts as ASKED when a same-package helper calls it AND that helper is itself
# used from outside. Deliberately one hop only -- an arbitrary-depth reachability walk would
# eventually declare everything asked, which is the failure mode in the other direction.
DIALECT_PACKAGE_HELPERS_TO_SCAN = ("SqlDialect.java", "PartialApplicationTruth.java", "SqlDialects.java")


def concrete_method_bodies(code: str) -> list[tuple[str, str]]:
    """(name, body) for each method in a helper that HAS a body, by brace matching.

    Needed because attribution must be per-METHOD, not per-file. Checking whether the file contains
    the call and then blaming whichever entry point happens to have callers reported every method as
    reached "via SqlDialect.bindableValue()" -- an over-report, and this file's own header says a
    checker that over-reports callers is worse than none.
    """
    bodies = []
    signature = re.compile(r"(?:public|default|static|final|\s)+[\w<>,\[\]. ]+?\s+(\w+)\s*\([^;{]*\)\s*\{")
    for match in signature.finditer(code):
        name, depth, index = match.group(1), 1, match.end()
        while index < len(code) and depth:
            depth += (code[index] == "{") - (code[index] == "}")
            index += 1
        bodies.append((name, code[match.end():index]))
    return bodies


def reached_via_dialect_package_helper(method: str, root: Path, blobs: list[tuple[str, str]]) -> str | None:
    """The helper method that calls `method` AND is itself used from outside, or None."""
    package = root / DIALECT_PACKAGE
    needle = re.compile(r"[.:\s]" + re.escape(method) + r"\s*\(")
    for helper_name in DIALECT_PACKAGE_HELPERS_TO_SCAN:
        helper = package / helper_name
        if not helper.is_file():
            continue
        # Strip comments first -- a javadoc `{@link #supports}` is not a call.
        code = "\n".join(line for line in helper.read_text(encoding="utf-8", errors="replace").splitlines()
                         if not line.strip().startswith(("*", "//", "/*")))
        stem = helper_name[:-5]
        for entry, body in concrete_method_bodies(code):
            if entry == method or not needle.search(body):
                continue
            used_outside = bool(callers_of(entry, blobs)) or any(f"{stem}.{entry}(" in blob for _, blob in blobs)
            if used_outside:
                return f"{stem}.{entry}()"
    return None


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
    reached_indirectly: list[str] = []
    for method in methods:
        callers = callers_of(method, blobs)
        if not callers:
            via = reached_via_dialect_package_helper(method, root, blobs)
            if via is not None:
                callers = [f"(via {via})"]
                reached_indirectly.append(f"{method} <- {via}")
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
