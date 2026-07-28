#!/usr/bin/env python3
"""Cross-surface mechanical sweep for the bug CLASSES this codebase has actually produced.

WHY THIS EXISTS
---------------
`docs/ONE_PLAN_CLOSE_EVERYTHING.md` compresses four separate adversarial-review sessions
into two by running one mechanical pass across all four surfaces FIRST. That compression is
only honest if the thing being batched is genuinely batchable. It is: every bug class found
in this repo so far is a *shape*, not a judgement call.

    LNCH13-F1 (CRITICAL)  a security call emitted in ONE arm of a template conditional
    REG-39                a schema/SQL exception swallowed into a security negative
    REG-16 Tier A family  a read with no tenant predicate
    REG-36                a caller-influenced value stored with no length bound
    (not yet found)       SQL assembled by concatenation instead of parameters

Pattern-matching finds those everywhere, cheaply. What it CANNOT do is decide whether a hit
is a bug -- so this script deliberately does not try. It emits a worklist; a human triages
it into docs/SECURITY_PATTERN_SWEEP_2026-07.md.

IT REPORTS, IT DOES NOT FAIL A BUILD
------------------------------------
Exit 0 whether or not there are hits (unless --fail-on-new). A heuristic gate that blocks CI
on regex noise gets bypassed within a week, and then it protects nothing. The same reasoning
as `check-register-consistency.py`: be loud, be skippable, never cry wolf.

THE ALLOWLIST IS THE POINT
--------------------------
Triage verdict (ii) -- "safe, and here is why" -- has to survive to the next sweep or every
run re-litigates the same 200 hits and nobody reads the output. Verdicts live in
`scripts/quality/security-pattern-sweep-allowlist.json`, keyed by a fingerprint of the
matched TEXT (not its line number), so refactors and line drift do not silently drop a
verdict. Anything not in the allowlist is new and needs a human.

USAGE
-----
    python scripts/quality/security-pattern-sweep.py                 # new hits only
    python scripts/quality/security-pattern-sweep.py --all           # include allowlisted
    python scripts/quality/security-pattern-sweep.py --pattern guard-in-one-branch
    python scripts/quality/security-pattern-sweep.py --format md     # paste into the triage doc
    python scripts/quality/security-pattern-sweep.py --fail-on-new   # opt-in CI use

Exit codes: 0 = ran (default), 1 = new un-triaged hits and --fail-on-new was given,
2 = a scan root was missing.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --------------------------------------------------------------------------------------
# Vocabulary -- what "a security call" and "caller-influenced" look like IN THIS CODEBASE.
# These lists are the script's whole accuracy budget: too broad and every line is a hit,
# too narrow and the next LNCH13-F1 walks past. They are derived from the real call sites,
# not invented; keep them that way when extending.
# --------------------------------------------------------------------------------------

SECURITY_CALL = re.compile(
    r"\b("
    r"enforce[A-Z]\w*"
    r"|checkCrudPermission|checkPermission|checkAccess"
    r"|authorize\w*|requireRole\w*|requirePermission\w*"
    r"|assertTenant\w*|isRowReadable|isRowWritable|hasRowReadScope"
    r"|conceptGateway\.(save|delete|update)"
    r")\s*\(",
)

# A value whose content the caller (or a model author, who is only semi-trusted) can steer.
CALLER_INFLUENCED = re.compile(
    r"\b(idempotencyKey|resumeToken|correlationId|externalRef\w*|userKey|clientKey"
    r"|principal|actorId|subject|username|email|tenantId|conceptName|capability|operation"
    r"|fieldName|tableName|columnName|sortBy|orderBy|filter\w*)\b",
)

# Evidence that a length bound EXISTS somewhere in the enclosing method.
LENGTH_BOUND = re.compile(
    r"\b(MAX_[A-Z_]*(LEN|LENGTH|CHARS|SIZE)|"
    r"substring|truncat\w*|sha256|sha1|digest|MessageDigest|hashOf|"
    r"\.length\(\)\s*[<>]|length\(\)\s*>\s*\d|@Size|@Length|@Max)\b",
)

# A catch block that converts a failure into a security-relevant NEGATIVE (or, worse, a
# security-relevant POSITIVE). `return true` is listed because fail-OPEN is the severe form.
SECURITY_NEGATIVE = re.compile(
    r"return\s+(false|true|null|Optional\.empty\(\)|List\.of\(\)|Set\.of\(\)|Map\.of\(\)"
    r"|Collections\.empty\w*\(\)|new\s+\w*\[\s*0\s*\]);",
)

SQL_VERB = re.compile(r"\b(SELECT|INSERT\s+INTO|UPDATE|DELETE\s+FROM|CREATE|ALTER|DROP|MERGE)\b", re.I)

# Paths whose primary job is deciding who may do what. A swallowed exception matters far
# more here than in, say, a metrics adapter.
AUTH_PATH = re.compile(r"(auth|authz|identity|tenant|permission|principal|login|token|jwt|policy|gateway)", re.I)


@dataclass
class Hit:
    pattern: str
    path: Path
    line: int
    reason: str
    snippet: str
    root: Path

    @property
    def rel(self) -> str:
        return self.path.relative_to(self.root).as_posix()

    def fingerprint(self) -> str:
        """Identity = pattern + normalised matched text. Deliberately NOT file- or line-based.

        2026-07-28 (docs/POST_PUBLIC_PLAN.md P3.1): this used to also hash in the relative file
        path, which meant a pure code MOVE (a god-file split, e.g. T2.B.3/T2.B.4) silently
        orphaned every existing verdict on the moved lines -- the sweep then reported
        already-triaged, unchanged code as brand-new untriaged hits (37 of them, for real, on
        PR #5). A triage verdict is a judgment about a SHAPE of code, not about which file
        happens to contain it right now, so the file path was never actually part of the
        thing being judged -- dropping it is a correction, not a loosening. Line number was
        already excluded for the same reason (survives reformatting); whitespace is collapsed
        so indentation changes do not orphan a verdict either. Editing the matched code itself
        still DOES invalidate the verdict -- correct, because the thing that was judged safe
        has changed. `path` stays informational-only in the allowlist (see `report_moves`).
        """
        normalised = re.sub(r"\s+", " ", self.snippet).strip()
        stamp = hashlib.sha1(f"{self.pattern}|{normalised}".encode()).hexdigest()
        return stamp[:12]


@dataclass
class Scan:
    """One source file, pre-chewed into the few views every pattern needs."""

    path: Path
    root: Path
    text: str
    lines: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.lines = self.text.splitlines()

    def line_of(self, offset: int) -> int:
        return self.text.count("\n", 0, offset) + 1

    def context(self, offset: int, before: int = 0, after: int = 400) -> str:
        return self.text[max(0, offset - before) : offset + after]


# --------------------------------------------------------------------------------------
# Pattern 1 -- a security call that lives in only ONE arm of a template conditional.
# This is the shape that produced the only CRITICAL found so far (LNCH13-F1: a concept with
# a custom create flow got ZERO row-level write enforcement, because the enforce call sat in
# the {{^hasCreateFlow}} arm). Cheapest, highest-value pattern -- run it first.
# --------------------------------------------------------------------------------------

SECTION = re.compile(r"\{\{([#^])([\w.]+)\}\}(.*?)\{\{/\2\}\}", re.S)

# Section names that only choose WHICH of several equivalent emissions happens, rather than
# whether a security decision happens at all.
STRUCTURAL_SECTION = re.compile(r"^(kernelControlled|codaAllowed)$")


def word_tokens(name: str) -> set[str]:
    """camelCase / dotted identifier -> lowercase word set, minus predicate noise."""
    parts = re.findall(r"[A-Z]+(?![a-z])|[A-Z][a-z]*|[a-z]+|\d+", name.replace(".", " "))
    return {p.lower() for p in parts} - {"has", "is", "any", "with", "enforce", "the"}


def tautological(condition: str, call: str) -> bool:
    """Is this call's conditionality a tautology rather than a gap?

    `{{#hasCreateFlow}} enforceWithCreateFlow(...) {{/hasCreateFlow}}` is not a missing guard:
    when there is no create flow there is no flow to enforce, so the `{{^hasCreateFlow}}` arm
    CANNOT carry the call. `{{#hasCreateFlow}} enforceWithConceptGateway(...)` is the opposite
    -- the gateway check has nothing to do with flows, so its absence from the other arm means
    one model shape ships unguarded. That was LNCH13-F1.

    The discriminator: does the condition's own subject appear in the call's name?
    """
    subject = word_tokens(condition)
    return bool(subject) and subject <= word_tokens(call)


def sweep_template_branches(scan: Scan) -> list[Hit]:
    hits: list[Hit] = []
    for match in SECTION.finditer(scan.text):
        kind, name, body = match.group(1), match.group(2), match.group(3)
        calls = {
            c.group(1) for c in SECURITY_CALL.finditer(body)
            if not tautological(name, c.group(1))
        }
        if not calls:
            continue

        # Does the SAME variable have an opposite arm, and does that arm carry the call too?
        opposite = "^" if kind == "#" else "#"
        sibling_bodies = [
            m.group(3)
            for m in SECTION.finditer(scan.text)
            if m.group(2) == name and m.group(1) == opposite
        ]
        named = ", ".join(sorted(calls))
        if sibling_bodies:
            covered = {c.group(1) for body2 in sibling_bodies for c in SECURITY_CALL.finditer(body2)}
            missing = sorted(calls - covered)
            if missing:
                hits.append(
                    Hit(
                        "guard-in-one-branch",
                        scan.path,
                        scan.line_of(match.start()),
                        f"{{{{{kind}{name}}}}} emits {', '.join(missing)} but the {{{{{opposite}{name}}}}} "
                        f"arm does not -- LNCH13-F1's exact shape: one model shape gets the guard, the other does not",
                        f"{{{{{kind}{name}}}}} :: {named}",
                        scan.root,
                    )
                )
            continue

        # No opposite arm at all: the guard is emitted only when `name` is truthy, and simply
        # absent otherwise. Weaker signal than an asymmetric pair -- but it is still a security
        # call the generator can decline to emit, so it must be justified once, in writing.
        hits.append(
            Hit(
                "conditional-guard-no-else",
                scan.path,
                scan.line_of(match.start()),
                f"{named} is emitted only under {{{{{kind}{name}}}}}; there is no opposite arm, so a model "
                f"where {name} is falsey gets no such call anywhere -- confirm that is intended",
                f"{{{{{kind}{name}}}}} :: {named}",
                scan.root,
            )
        )
    return hits


# --------------------------------------------------------------------------------------
# Pattern 2 -- a schema/SQL error swallowed into a security answer (REG-39's shape).
# `catch (SQLException e) { return false; }` in an auth path turns an infrastructure fault
# into "not permitted" (annoying) or "permitted" (a bypass). Either way the operator sees a
# behaviour change with no error.
# --------------------------------------------------------------------------------------

CATCH = re.compile(r"catch\s*\(\s*([\w.|\s]*?(?:Exception|Throwable|Error))\s+(\w+)\s*\)\s*\{")


def sweep_swallowed_exceptions(scan: Scan) -> list[Hit]:
    hits: list[Hit] = []
    for match in CATCH.finditer(scan.text):
        caught = " ".join(match.group(1).split())
        body = balanced_block(scan.text, match.end() - 1)
        if body is None:
            continue
        negative = SECURITY_NEGATIVE.search(body)
        if not negative:
            continue

        rethrows = re.search(r"\bthrow\b", body)
        logs = re.search(r"\b(log|logger|LOG|LOGGER)\s*\.\s*(error|warn)", body)
        if rethrows:
            continue  # it re-raises: the failure is not being converted into an answer

        in_auth_path = bool(AUTH_PATH.search(scan.path.as_posix()))
        severity = "auth path" if in_auth_path else "non-auth path"
        silence = "not even logged at warn/error" if not logs else "logged, but still answered"
        hits.append(
            Hit(
                "swallowed-security-exception",
                scan.path,
                scan.line_of(match.start()),
                f"catch ({caught}) returns `{negative.group(1)}` ({severity}, {silence}) -- REG-39's shape: "
                f"an infrastructure fault becomes a security verdict the caller cannot distinguish from a real one",
                f"catch ({caught}) -> {negative.group(0)}",
                scan.root,
            )
        )
    return hits


def balanced_block(text: str, open_brace: int, limit: int = 4000) -> str | None:
    """Body of the `{...}` starting at `open_brace`, brace-counted rather than regexed.

    Braces inside string literals and comments are NOT excluded. That is a deliberate
    simplification: the worst case is a body that ends early or late, which changes which
    hits are reported, and every hit is hand-triaged anyway. Doing it properly needs a Java
    lexer, which is a large amount of machinery for a worklist generator.
    """
    depth = 0
    for index in range(open_brace, min(len(text), open_brace + limit)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1 : index]
    return None


# --------------------------------------------------------------------------------------
# Pattern 3 -- SQL built by concatenation rather than parameters.
# Java text blocks (\"\"\"...\"\"\") are the dominant style in these adapters, so a literal-only
# regex would miss most statements. Both forms are extracted, then each STATEMENT is asked:
# is anything non-constant spliced into you?
# --------------------------------------------------------------------------------------

TEXT_BLOCK = re.compile(r'"""(.*?)"""', re.S)
STRING_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def sql_statements(scan: Scan) -> list[tuple[int, int, str, str]]:
    """(offset, line, sql_text, raw_form) for every string that looks like SQL.

    Adjacent concatenated literals are joined so that a statement split across source lines
    is judged as one statement -- otherwise `"SELECT ... " + "WHERE tenant_id = ?"` reads as
    a tenant-less read.
    """
    found: list[tuple[int, int, str, str]] = []
    consumed: list[tuple[int, int]] = []

    for match in TEXT_BLOCK.finditer(scan.text):
        body = match.group(1)
        if SQL_VERB.search(body):
            found.append((match.start(), scan.line_of(match.start()), body, match.group(0)))
        consumed.append(match.span())

    for match in STRING_LITERAL.finditer(scan.text):
        if any(start <= match.start() < end for start, end in consumed):
            continue
        if not SQL_VERB.search(match.group(1)):
            continue
        # Absorb `"a" + "b" + var + "c"` forward so the whole expression is one statement.
        tail = scan.text[match.end() : match.end() + 1200]
        joined = match.group(1)
        cursor = 0
        while True:
            step = re.match(r"\s*\+\s*", tail[cursor:])
            if not step:
                break
            cursor += step.end()
            piece = STRING_LITERAL.match(tail[cursor:])
            if piece:
                joined += piece.group(1)
                cursor += piece.end()
                continue
            symbol = re.match(r"[\w.]+(\([^()]*\))?", tail[cursor:])
            if symbol:
                joined += f"\x00{symbol.group(0)}\x00"  # marker: a non-constant was spliced in
                cursor += symbol.end()
                continue
            break
        found.append((match.start(), scan.line_of(match.start()), joined, scan.text[match.start() : match.end() + cursor]))
    return found


SAFE_SPLICE = re.compile(r"^(\w+\.)*[A-Z][A-Z0-9_]*$")  # a CONSTANT, or Klass.CONSTANT


def sweep_sql_concatenation(scan: Scan) -> list[Hit]:
    hits: list[Hit] = []
    for offset, line, sql, raw in sql_statements(scan):
        spliced = re.findall(r"\x00(.+?)\x00", sql)
        if not spliced:
            # A text block can still be formatted into: `SQL.formatted(x)` / String.format(SQL, x).
            after = scan.context(offset + len(raw), after=80)
            formatted = re.match(r"\s*\.\s*(formatted|format)\s*\(", after)
            if not formatted and not re.search(r"String\.format\s*\(\s*$", scan.context(offset, before=40, after=0)):
                continue
            spliced = ["<String.format / .formatted>"]

        dynamic = [s for s in spliced if not SAFE_SPLICE.match(s)]
        if not dynamic:
            continue  # only compile-time constants spliced in: not attacker-reachable

        verb = SQL_VERB.search(sql)
        hits.append(
            Hit(
                "sql-string-building",
                scan.path,
                line,
                f"{verb.group(0).upper() if verb else 'SQL'} statement is assembled with {', '.join(dynamic[:4])} "
                f"spliced in rather than bound as a parameter -- trace each to its source; a model-author-"
                f"reachable one is injection",
                re.sub(r"\x00", "", sql)[:200],
                scan.root,
            )
        )
    return hits


# --------------------------------------------------------------------------------------
# Pattern 4 -- a read with no tenant predicate (the REG-16 Tier A family).
# Multi-tenancy in this platform is enforced by a `tenant_id` column, so a SELECT that does
# not mention it either reads across tenants or relies on something else to scope it. The
# second case is common and fine -- it just has to be stated, once, in the allowlist.
# --------------------------------------------------------------------------------------

def sweep_tenantless_reads(scan: Scan) -> list[Hit]:
    hits: list[Hit] = []
    for offset, line, sql, _raw in sql_statements(scan):
        clean = re.sub(r"\x00.+?\x00", " ? ", sql)
        if not re.search(r"\bSELECT\b", clean, re.I):
            continue
        if re.search(r"tenant", clean, re.I):
            continue
        # Schema/catalog introspection is not tenant-scoped data by definition.
        if re.search(r"information_schema|pg_catalog|flyway_schema_history|\bDUAL\b", clean, re.I):
            continue
        target = re.search(r"\bFROM\s+([\w.\"]+)", clean, re.I)
        hits.append(
            Hit(
                "read-without-tenant-predicate",
                scan.path,
                line,
                f"SELECT from {target.group(1) if target else '?'} names no tenant column -- confirm the row set "
                f"is genuinely tenant-independent, or that a caller-side scope makes it so",
                " ".join(clean.split())[:200],
                scan.root,
            )
        )
    return hits


# --------------------------------------------------------------------------------------
# Pattern 5 -- a caller-influenced value stored with no length bound (REG-36's shape).
# The asymmetry is the tell: REG-36 exists because the cached idempotency VALUE is bounded
# by IDEMPOTENCY_RESULT_MAX_CHARS while the KEY that addresses it is not.
# --------------------------------------------------------------------------------------

BIND = re.compile(r"\.(setString|setObject|setNString)\s*\(\s*\w+\s*,\s*([\w.()]+)")
METHOD_START = re.compile(r"^\s*(?:public|protected|private|static|final|\s)*[\w<>,\[\]. ]+\s+(\w+)\s*\([^;{]*\)\s*\{", re.M)


def sweep_unbounded_input(scan: Scan) -> list[Hit]:
    hits: list[Hit] = []
    methods = [(m.start(), m.group(1)) for m in METHOD_START.finditer(scan.text)]
    for match in BIND.finditer(scan.text):
        value = match.group(2)
        if not CALLER_INFLUENCED.search(value):
            continue
        enclosing_start = 0
        enclosing_name = "<file>"
        for start, name in methods:
            if start <= match.start():
                enclosing_start, enclosing_name = start, name
            else:
                break
        body = scan.text[enclosing_start : match.start() + 200]
        if LENGTH_BOUND.search(body):
            continue
        hits.append(
            Hit(
                "unbounded-caller-input",
                scan.path,
                scan.line_of(match.start()),
                f"{enclosing_name}() persists `{value}` with no length bound, digest or truncation anywhere in "
                f"the method -- REG-36's shape; check whether a paired value IS bounded (the asymmetry is the bug)",
                f"{enclosing_name}: {match.group(0)}",
                scan.root,
            )
        )
    return hits


# --------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------

# sweep function -> (file globs it reads, pattern names it can emit)
SWEEPS = [
    (sweep_template_branches, ("*.mustache",), ("guard-in-one-branch", "conditional-guard-no-else")),
    (sweep_swallowed_exceptions, ("*.java",), ("swallowed-security-exception",)),
    (sweep_sql_concatenation, ("*.java",), ("sql-string-building",)),
    (sweep_tenantless_reads, ("*.java",), ("read-without-tenant-predicate",)),
    (sweep_unbounded_input, ("*.java",), ("unbounded-caller-input",)),
]
PATTERN_NAMES = sorted(name for _, _, names in SWEEPS for name in names)

# The four surfaces of ONE_PLAN §3-4, plus the two already reviewed (regressions land there too).
SCAN_ROOTS = [
    "NPDevGenerator/generator/src/main/resources/npdev-templates",
    "NPDevGenerator/generator/src/main/java",
    "NPDevKernel/adapters",
    "NPDevKernel/kernel/src/main/java",
    "NPDevRuntimeHost/src/main/java",
    # Added 2026-07-25. This module was missing, and its absence is exactly the failure mode this
    # sweep exists to prevent: 163 source files reporting "0 new" because nothing looked at them.
    # It holds DestructiveAckToken (the destructive-acknowledgment TOKEN computation), SemanticValidator,
    # and the schema-evolution primitives -- security-relevant by any reading. A scan root list is a
    # claim about coverage; an incomplete one turns a green check into false comfort.
    "NPDevContract/dsl/src/main/java",
]

# Generated bundles and vendored assets: not ours to fix, and huge.
SKIP = re.compile(r"(/build/|/node_modules/|/static-react/|/npdev-generated/|app\.js$|/test/|Test\.java$)")

ALLOWLIST = Path("scripts/quality/security-pattern-sweep-allowlist.json")

# Modules deliberately NOT swept, each with the reason. Anything with main Java that is neither in
# SCAN_ROOTS nor named here fails coverage_gaps() -- see its docstring for why that matters.
SCAN_EXCLUSIONS = {
    # Verified 2026-07-25: a single file, `CoreMarker`, an empty final class used as a package anchor
    # for module-boundary checks. No SQL, no auth, no templates, no caller-influenced input. If this
    # module ever gains real code the exclusion should be removed rather than widened.
    "NPDevKernel/core": "One empty marker class (CoreMarker) used as a package anchor; no code surface.",
}


def coverage_gaps(root: Path) -> list[str]:
    """Does SCAN_ROOTS still cover every module that has main Java?

    Blind spot #4 (2026-07-25) was NPDevContract/dsl missing from SCAN_ROOTS: 163 source files,
    including the destructive-acknowledgment TOKEN computation, reporting "0 new" because nothing
    looked at them. The patterns were fine; the *coverage claim* was wrong, and nothing checked it.

    That is the shape of every blind spot found in this repo: an instrument verifies its CONTENT and
    nobody verifies its SCOPE. A green sweep means "the patterns I ran, over the roots I listed, found
    nothing" -- it silently says nothing about roots that were never listed. So the list is now an
    assertion the tool itself checks: add a module and the sweep fails until you either sweep it or
    state why not.
    """
    covered = [Path(r).as_posix() for r in SCAN_ROOTS]
    gaps: list[str] = []
    for main_java in sorted(root.glob("*/*/src/main/java")) + sorted(root.glob("*/src/main/java")):
        rel = main_java.relative_to(root).as_posix()
        module = rel.rsplit("/src/main/java", 1)[0]
        if any(rel.startswith(c) or c.startswith(rel) for c in covered):
            continue
        if module in SCAN_EXCLUSIONS:
            continue
        count = sum(1 for _ in main_java.rglob("*.java"))
        gaps.append(
            f"{rel} ({count} .java files) is not in SCAN_ROOTS and not in SCAN_EXCLUSIONS. "
            f"Either add it to SCAN_ROOTS, or add '{module}' to SCAN_EXCLUSIONS with the reason it "
            f"carries no SQL/auth/template surface."
        )
    return gaps


def collect(root: Path, only: str | None) -> list[Hit]:
    hits: list[Hit] = []
    for scan_root in SCAN_ROOTS:
        base = root / scan_root
        if not base.exists():
            print(f"ERROR: scan root missing: {base}", file=sys.stderr)
            raise SystemExit(2)
        for function, globs, emits in SWEEPS:
            if only and only not in emits:
                continue
            for pattern_glob in globs:
                for path in base.rglob(pattern_glob):
                    if SKIP.search(path.as_posix()):
                        continue
                    try:
                        text = path.read_text(encoding="utf-8", errors="replace")
                    except OSError:
                        continue
                    hits.extend(function(Scan(path, root, text)))
    if only:
        hits = [h for h in hits if h.pattern == only]
    return hits


# --------------------------------------------------------------------------------------
# Self-test -- the sweep must PROVE it catches the bugs it claims to.
#
# A pattern sweep that finds 350 things but would have walked past LNCH13-F1 is worse than
# useless: it manufactures confidence. So each fixture below is the real historical shape of
# a bug this repo actually shipped, plus the fixed shape, and the sweep must separate them.
# Run by GATE-AI alongside the register self-check.
# --------------------------------------------------------------------------------------

SELF_TEST: list[tuple[str, str, str, str, bool]] = [
    (
        "LNCH13-F1 as it actually shipped: enforceWithConceptGateway emitted only when NO create flow",
        "svc.mustache",
        """
        {{^hasCreateFlow}}
        enforceWithConceptGateway("{{conceptName}}", generatedId, createPayload);
        {{/hasCreateFlow}}
        {{#hasCreateFlow}}
        enforceWithCreateFlow(crudCtx, generatedId, createPayload);
        {{/hasCreateFlow}}
        """,
        "guard-in-one-branch",
        True,
    ),
    (
        "LNCH13-F1 after the fix: the gateway call is unconditional, the flow call is the only branch",
        "svc.mustache",
        """
        enforceWithConceptGateway("{{conceptName}}", generatedId, createPayload);
        {{#hasCreateFlow}}
        enforceWithCreateFlow(crudCtx, generatedId, createPayload);
        {{/hasCreateFlow}}
        {{^hasCreateFlow}}
        noop();
        {{/hasCreateFlow}}
        """,
        "guard-in-one-branch",
        False,
    ),
    (
        "REG-39's shape: a schema fault becomes a security negative, silently",
        "auth/PolicyLoader.java",
        """
        boolean isPermitted(String who) {
            try { return lookup(who); }
            catch (SQLException e) { return false; }
        }
        """,
        "swallowed-security-exception",
        True,
    ),
    (
        "the same catch, but it re-raises instead of answering",
        "auth/PolicyLoader.java",
        """
        boolean isPermitted(String who) {
            try { return lookup(who); }
            catch (SQLException e) { throw new IllegalStateException("policy unreadable", e); }
        }
        """,
        "swallowed-security-exception",
        False,
    ),
    (
        "an identifier concatenated into SQL",
        "Store.java",
        'String sql = "SELECT * FROM " + tableName + " WHERE tenant_id = ?";',
        "sql-string-building",
        True,
    ),
    (
        "the same statement, fully parameterised, split across literals",
        "Store.java",
        'String sql = "SELECT * FROM claims " + "WHERE tenant_id = ? AND id = ?";',
        "sql-string-building",
        False,
    ),
    (
        "a cross-tenant read: the joined statement never names tenant_id",
        "Store.java",
        'String sql = "SELECT id, payload FROM npdev_claim " + "WHERE id = ?";',
        "read-without-tenant-predicate",
        True,
    ),
    (
        "the same read, scoped -- and the scope lives in the SECOND literal, so joining matters",
        "Store.java",
        'String sql = "SELECT id, payload FROM npdev_claim " + "WHERE tenant_id = ? AND id = ?";',
        "read-without-tenant-predicate",
        False,
    ),
    (
        "REG-36's shape: a caller-influenced key bound with no bound anywhere in the method",
        "Store.java",
        """
        void put(Connection c, String idempotencyKey) throws SQLException {
            PreparedStatement ps = c.prepareStatement(INSERT);
            ps.setString(1, idempotencyKey);
        }
        """,
        "unbounded-caller-input",
        True,
    ),
    (
        "the same write, after REG-36's fix digests an oversized key",
        "Store.java",
        """
        void put(Connection c, String idempotencyKey) throws SQLException {
            String bounded = idempotencyKey.length() > MAX_KEY_CHARS ? sha256(idempotencyKey) : idempotencyKey;
            PreparedStatement ps = c.prepareStatement(INSERT);
            ps.setString(1, bounded);
        }
        """,
        "unbounded-caller-input",
        False,
    ),
]


def self_test(root: Path) -> int:
    failures = 0
    print("Sweep self-test (does each pattern separate the real bug from its fix?)")
    for description, filename, source, expected, should_hit in SELF_TEST:
        scan = Scan(root / filename, root, source)
        hits: list[Hit] = []
        for function, globs, emits in SWEEPS:
            if expected not in emits:
                continue
            if filename.endswith(".mustache") != (globs == ("*.mustache",)):
                continue
            hits.extend(function(scan))
        matched = [h for h in hits if h.pattern == expected]
        ok = bool(matched) == should_hit
        verb = "flags" if should_hit else "stays quiet on"
        print(f"  {'PASS' if ok else 'FAIL'}  {expected} {verb}: {description}")
        if not ok:
            failures += 1
            print(f"        got {len(matched)} hit(s): {[h.reason for h in matched]}")
    print(f"{len(SELF_TEST) - failures}/{len(SELF_TEST)} fixtures behaved as documented")
    return 1 if failures else 0


def load_allowlist(root: Path) -> dict[str, dict]:
    path = root / ALLOWLIST
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8")).get("cleared", {})


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    parser.add_argument("--pattern", choices=PATTERN_NAMES, help="run only this pattern")
    parser.add_argument("--self-test", action="store_true", help="prove the patterns catch the known bugs")
    parser.add_argument("--all", action="store_true", help="include hits already cleared in the allowlist")
    parser.add_argument("--format", choices=("text", "md", "json"), default="text")
    parser.add_argument("--fail-on-new", action="store_true", help="exit 1 if any un-triaged hit remains")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()

    # Coverage is asserted BEFORE anything else, including --self-test: a sweep whose scope is wrong
    # produces a confident green over the wrong files, which is worse than a red. See coverage_gaps().
    gaps = coverage_gaps(root)
    if gaps:
        print("COVERAGE GAP: the sweep is not scanning every module that has main Java:", file=sys.stderr)
        for gap in gaps:
            print(f"  - {gap}", file=sys.stderr)
        return 2

    if args.self_test:
        return self_test(root)

    cleared = load_allowlist(root)
    hits = sorted(collect(root, args.pattern), key=lambda h: (h.pattern, h.rel, h.line))

    shown = [h for h in hits if args.all or h.fingerprint() not in cleared]
    counts: dict[str, tuple[int, int]] = {}
    for hit in hits:
        total, new = counts.get(hit.pattern, (0, 0))
        counts[hit.pattern] = (total + 1, new + (0 if hit.fingerprint() in cleared else 1))

    if args.format == "json":
        print(json.dumps(
            [
                {
                    "pattern": h.pattern, "file": h.rel, "line": h.line, "reason": h.reason,
                    "snippet": h.snippet, "fingerprint": h.fingerprint(),
                    "cleared": cleared.get(h.fingerprint(), {}).get("why"),
                }
                for h in shown
            ],
            indent=2,
        ))
    elif args.format == "md":
        print("| Pattern | Where | Why it might be a problem | Fingerprint |")
        print("|---|---|---|---|")
        for hit in shown:
            reason = hit.reason.replace("|", "\\|")
            print(f"| `{hit.pattern}` | `{hit.rel}:{hit.line}` | {reason} | `{hit.fingerprint()}` |")
    else:
        for hit in shown:
            marker = " [cleared]" if hit.fingerprint() in cleared else ""
            print(f"{hit.rel}:{hit.line} · {hit.pattern} · {hit.reason}{marker}")
            print(f"    {hit.snippet}")
            print(f"    fingerprint {hit.fingerprint()}")

    print(f"\n-- {len(hits)} hit(s), {len(hits) - len(shown)} already cleared, {len(shown)} needing triage", file=sys.stderr)
    for name in sorted(counts):
        total, new = counts[name]
        print(f"   {name}: {total} total, {new} new", file=sys.stderr)
    print(
        "\nTriage each new hit into docs/SECURITY_PATTERN_SWEEP_2026-07.md as one of:\n"
        "  (i)  a genuine finding  -> rate it and file a REG-nn row\n"
        "  (ii) safe, with reason  -> add its fingerprint to " + ALLOWLIST.as_posix() + "\n"
        "  (iii) needs deep review -> hand to the session that owns that surface",
        file=sys.stderr,
    )

    moves = report_moves(hits, cleared)
    if moves:
        print(f"\n{len(moves)} cleared hit(s) now live at a path their allowlist entry doesn't "
              "mention -- informational only, NOT a failure (docs/POST_PUBLIC_PLAN.md P3.1: "
              "fingerprints survive file moves by design now; this just flags that `where` is "
              "stale prose worth a follow-up edit, not a re-triage):", file=sys.stderr)
        for hit, entry_where in moves:
            print(f"  {hit.rel}:{hit.line} ({hit.pattern}, fingerprint {hit.fingerprint()}) "
                  f"-- allowlist `where` says: {entry_where}", file=sys.stderr)

    return 1 if (args.fail_on_new and shown) else 0


def report_moves(hits: list[Hit], cleared: dict[str, dict]) -> list[tuple[Hit, str]]:
    """Cleared hits whose current file isn't mentioned in their allowlist entry's `where`.

    Purely informational (never fails the build): since the fingerprint (P3.1) no longer
    includes the file path, a cleared hit surviving a code move is now the EXPECTED, silent
    case. This just surfaces it so `where` -- free prose describing where the reviewer actually
    looked -- can be kept honest without anyone needing to notice a git diff moved the code.
    """
    moved: list[tuple[Hit, str]] = []
    for hit in hits:
        entry = cleared.get(hit.fingerprint())
        if entry is None:
            continue
        where = entry.get("where", "")
        if hit.rel not in where:
            moved.append((hit, where))
    return moved


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
