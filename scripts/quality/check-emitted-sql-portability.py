#!/usr/bin/env python3
"""Emitted-SQL portability linter: report EVERY unsupported construct per engine, in one pass.

WHY THIS EXISTS -- it buys back CI rounds
-----------------------------------------
`V1__npdev_schema_realization.sql` is written in PostgreSQL/H2 guarded DDL (`CREATE TABLE IF NOT
EXISTS`, `CREATE INDEX IF NOT EXISTS`, `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`). MySQL supports
one of the three; T-SQL supports none (ledger STOR-5).

**Flyway stops at the first statement it cannot run.** So each unsupported construct costs its own
~12-minute CI round to discover: fix one, push, wait, learn the next. That is how the previous
iteration found the missing JDBC driver, then the unindexable TEXT key, then this -- three rounds for
what is really one body of work.

The emitted script is a static artifact. Nothing about finding these needs a database, a container,
or a boot. Scanning it locally reports **all** of them at once, so a CI round confirms rather than
discovers.

WHAT IT IS NOT
--------------
Not a SQL parser, and it must not become one. It matches the specific idioms NPDev's own emitters
produce, each with the `SqlDialect` method to use instead. An idiom it does not know about is a gap
to add here, not a reason to widen it into a grammar.

Its sibling `check-dialect-sites.py` guards the SOURCE (an idiom written inline in an emitter);
this guards the OUTPUT (an idiom that reached a script). Both are needed: an emitter can build a
statement in pieces that no source-level pattern catches, and only the artifact shows what a database
will actually be handed.

USAGE
    python scripts/quality/check-emitted-sql-portability.py                 # find scripts, scan all
    python scripts/quality/check-emitted-sql-portability.py --script <p>
    python scripts/quality/check-emitted-sql-portability.py --dialect mysql,sqlserver
    python scripts/quality/check-emitted-sql-portability.py --json

Exit 0 = every scanned script is portable to every requested engine. Exit 1 = at least one is not.
Exit 2 = usage, or nothing to scan (a linter with no input is not a passing linter).
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from pathlib import Path

# construct id -> (regex, human name, per-dialect support, the SqlDialect method to use instead,
#                  emittedUnconditionally)
#
# Support is stated per engine rather than as "portable/not", because the interesting cases are the
# ones where engines DISAGREE -- MySQL accepts CREATE TABLE IF NOT EXISTS and rejects the other two,
# so a single portable/non-portable flag would either over- or under-report on every run.
#
# `emittedUnconditionally` is the flag that keeps this checker honest, and it was added after the
# first run reported 6,609 UUID "problems" that were nothing of the kind.
#
#   TRUE  -- the emitter writes this idiom the same way whatever engine the app is for. That IS the
#            STOR-5 bug, and it is a finding on ANY script, because the same text would be handed to
#            MySQL or SQL Server verbatim.
#   FALSE -- the emitter already asks the dialect (portableColumnType, autoIncrementColumn, ...), so
#            the text is engine-SPECIFIC. A script generated for H2 legitimately contains `UUID`;
#            flagging it for MySQL would report a bug that does not exist. These are only checked
#            with --generated-for, which states which engine the script was actually produced for.
CONSTRUCTS = {
    "create-table-if-not-exists": (
        re.compile(r"\bCREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\b", re.IGNORECASE),
        "CREATE TABLE IF NOT EXISTS",
        {"postgres": True, "h2": True, "mysql": True, "sqlserver": False},
        "dialect.guardedCreateTable(table, createStatement)",
        True,
    ),
    "create-index-if-not-exists": (
        re.compile(r"\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS\b", re.IGNORECASE),
        "CREATE [UNIQUE] INDEX IF NOT EXISTS",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": False},
        "dialect.guardedCreateIndex(index, table, createStatement)",
        True,
    ),
    "add-column-if-not-exists": (
        re.compile(r"\bADD\s+COLUMN\s+IF\s+NOT\s+EXISTS\b", re.IGNORECASE),
        "ALTER TABLE ... ADD COLUMN IF NOT EXISTS",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": False},
        "dialect.guardedAddColumn(table, column, alterStatement)",
        True,
    ),
    "drop-column-if-exists": (
        re.compile(r"\bDROP\s+COLUMN\s+IF\s+EXISTS\b", re.IGNORECASE),
        "ALTER TABLE ... DROP COLUMN IF EXISTS",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": False},
        "a dialect-built guard -- MySQL and SQL Server have no IF EXISTS on DROP COLUMN",
        True,
    ),
    "serial": (
        re.compile(r"\b(?:BIG)?SERIAL\b", re.IGNORECASE),
        "SERIAL / BIGSERIAL",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": False},
        "dialect.autoIncrementColumn(SqlType)",
        False,
    ),
    "native-uuid": (
        # Word-bounded so `UNIQUEIDENTIFIER` and column names containing "uuid" do not match; only a
        # bare type token does.
        re.compile(r"(?<![\w])UUID(?![\w(])", re.IGNORECASE),
        "UUID column type",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": False},
        'dialect.portableColumnType("UUID") -- MySQL has no native UUID type, and SQL Server '
        "spells it UNIQUEIDENTIFIER",
        False,
    ),
    "sqlserver-timestamp": (
        # THE DANGEROUS ONE, and the reason this entry exists at all. SQL Server's TIMESTAMP is a
        # ROWVERSION binary counter with no relationship to time -- SqlServerDialect's own javadoc
        # calls it "a genuinely dangerous false friend". A column declared TIMESTAMP there is not a
        # broken date column, it is a different thing entirely, and nothing errors.
        #
        # Found by READING the emitted SQL Server script, not by this linter -- which is exactly why
        # it is here now. The linter reported that script clean.
        re.compile(r"(?<![\w(])TIMESTAMP(?![\w)(])", re.IGNORECASE),
        "TIMESTAMP column type",
        {"postgres": True, "h2": True, "mysql": True, "sqlserver": False},
        "dialect.timestampColumnType() -- DATETIME2(6) on SQL Server; its TIMESTAMP is a rowversion",
        False,
    ),
    "sqlserver-text": (
        re.compile(r"(?<![\w(])TEXT(?![\w)(])", re.IGNORECASE),
        "TEXT column type",
        {"postgres": True, "h2": True, "mysql": True, "sqlserver": False},
        'dialect.portableColumnType("TEXT") -- NVARCHAR(MAX) on SQL Server, where TEXT is deprecated',
        False,
    ),
    # --------------------------------------------------------------------------------------------
    # STOR-7. The two that got past every check above, because both are about what a text column is
    # FOR rather than how it is spelled. Found at Flyway time on first boot, CI run 31284450437.
    #
    #   MySQL 8.4     BLOB, TEXT, GEOMETRY or JSON column 'state' can't have a default value  (1101)
    #   SQL Server    Column 'metadata_key' ... is of a type that is invalid for use as a key
    #                 column in an index.
    #
    # Both are `emittedUnconditionally=False` because after the fix the emitter asks the dialect, so
    # the type in a script IS engine-specific -- `text PRIMARY KEY` in a Postgres script is correct
    # and flagging it would be noise. They fire under --generated-for, which is the run that knows.
    # --------------------------------------------------------------------------------------------
    "text-key-column": (
        re.compile(r"(?:(?<![\w(])TEXT|N?VARCHAR\s*\(\s*MAX\s*\))\s+(?:NOT\s+NULL\s+)?"
                   r"(?:PRIMARY\s+KEY|UNIQUE)\b", re.IGNORECASE),
        "unbounded text in a PRIMARY KEY / UNIQUE",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": False},
        "dialect.keyableTextColumnType() -- MySQL error 1170 wants a key length; SQL Server cannot "
        "index NVARCHAR(MAX) at all",
        False,
    ),
    "text-default-column": (
        re.compile(r"(?<![\w(])TEXT(?![\w)(])\s+(?:NOT\s+NULL\s+)?DEFAULT\b", re.IGNORECASE),
        "unbounded text carrying a DEFAULT",
        {"postgres": True, "h2": True, "mysql": False, "sqlserver": True},
        "dialect.defaultableTextColumnType() -- MySQL error 1101; a TEXT column cannot have one",
        False,
    ),
    "jsonb": (
        re.compile(r"\bJSONB\b", re.IGNORECASE),
        "jsonb",
        {"postgres": True, "h2": False, "mysql": False, "sqlserver": False},
        "dialect.jsonColumnType()",
        False,
    ),
}

ALL_DIALECTS = ["postgres", "h2", "mysql", "sqlserver"]

# Where an emitted schema script lands. Deliberately only the build root and sample Output dirs --
# BUILD_OUTPUT_LOCATION_POLICY keeps generated artifacts out of the repo, so a script found INSIDE
# the source tree is itself a finding, not an input.
DEFAULT_GLOBS = [
    "**/db/schema-realization/*.sql",
    "**/db/conversion-hooks/**/*.sql",
]


# The emitted script names its own engine in its header:
#
#     -- NPDev schema realization
#     -- Engine: SqlServer
#
# Reading it is what makes an unattended scan CORRECT rather than noisy. Since STOR-5 was fixed every
# script is engine-specific, so judging one against any other engine reports problems that cannot
# happen -- a build directory full of older h2local output would fail a gate forever, which is how a
# gate gets switched off.
_ENGINE_HEADER = re.compile(r"^--\s*Engine:\s*(\S+)", re.IGNORECASE | re.MULTILINE)

_EXTERNAL_NAME_TO_DIALECT = {
    "postgres": "postgres",
    "h2local": "h2",
    "h2server": "h2",
    "mysql": "mysql",
    "sqlserver": "sqlserver",
    "inmemory": None,   # stores nothing in SQL; nothing to check
}


def engine_of(text: str) -> str | None:
    """The dialect a script was generated for, or None when it does not say."""
    match = _ENGINE_HEADER.search(text)
    if not match:
        return None
    return _EXTERNAL_NAME_TO_DIALECT.get(match.group(1).strip().lower(), None)


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


def _strip_sql_comments(line: str) -> str:
    """Blank out a `--` comment tail.

    The emitter writes explanatory comments that NAME these idioms ("CREATE TABLE IF NOT EXISTS is a
    no-op the instant the table already exists"). Matching those would report constructs no database
    is ever handed -- the same false-positive class the security sweep hits on refusal messages.
    """
    index = line.find("--")
    return line if index < 0 else line[:index]


def scan_script(path: Path, dialects: list[str], generated_for: str | None) -> list[dict]:
    findings: list[dict] = []
    text = path.read_text(encoding="utf-8", errors="replace")
    # An explicit --generated-for wins; otherwise believe the script's own header. A script that
    # names no engine (a conversion hook, say) falls back to the unconditional-only check.
    declared = generated_for or engine_of(text)
    if declared is None and _ENGINE_HEADER.search(text):
        return findings   # InMemory: no SQL engine to be portable to
    generated_for = declared
    for number, raw in enumerate(text.splitlines(), start=1):
        line = _strip_sql_comments(raw)
        if not line.strip():
            continue
        for construct_id, (pattern, name, support, fix, unconditional) in CONSTRUCTS.items():
            # An engine-SPECIFIC construct is only a problem on the engine the script was actually
            # generated for. Without --generated-for there is nothing to compare it against, so it
            # is not reported at all -- silence beats a confident wrong finding.
            if not unconditional and generated_for is None:
                continue
            if not pattern.search(line):
                continue
            # SINCE STOR-5 WAS FIXED, all three guarded idioms go through the dialect, so a script
            # is generated FOR exactly one engine and only that engine will ever be handed it.
            # `--generated-for mysql` therefore means "check this against MySQL", and reporting
            # `CREATE TABLE IF NOT EXISTS` as a SQL Server problem in a MySQL script would be the
            # confident-wrong-diagnosis shape all over again -- MySQL supports it, and SQL Server is
            # never going to see this file.
            targets = [generated_for] if generated_for is not None else dialects
            for dialect in targets:
                if dialect is None or support.get(dialect, True):
                    continue
                findings.append({
                    "script": str(path),
                    "line": number,
                    "dialect": dialect,
                    "construct": construct_id,
                    "constructName": name,
                    "fix": fix,
                    "text": line.strip()[:120],
                })
    return findings


def find_scripts(root: Path, explicit: list[str], extra_roots: list[Path]) -> list[Path]:
    if explicit:
        return [Path(p).resolve() for p in explicit]
    search_roots = [r if r.is_absolute() else (root / r) for r in extra_roots]
    if extra_roots:
        # An explicit scope means EXACTLY that scope. A gate scopes itself to what this run just
        # produced; silently adding the whole build root back would drag in months of older apps,
        # including ones emitted before STOR-5 was fixed -- stale artifacts, not findings, and a
        # permanent red is a gate people switch off.
        return _collect(search_roots)
    build_root = os.environ.get("NPDEV_BUILD_ROOT")
    search_roots.append(Path(build_root) if build_root else root.parent / "Build")
    search_roots.append(root / "NPDevSamples")

    return _collect(search_roots)


def _collect(search_roots: list[Path]) -> list[Path]:
    found: list[Path] = []
    seen: set[str] = set()
    for search_root in search_roots:
        if not search_root.is_dir():
            continue
        for pattern in DEFAULT_GLOBS:
            for match in glob.glob(str(search_root / pattern), recursive=True):
                resolved = str(Path(match).resolve())
                # One generated app contains the same script twice (npdev-generated/ and the
                # compiled build/resources/ copy). Scanning both doubles every finding for no
                # information, and a doubled count reads as a worse problem than there is.
                normalized = resolved.replace("\\", "/")
                # One generated app contains the same script twice (npdev-generated/ and the compiled
                # build/resources/ copy). Scanning both doubles every finding for no information, and
                # a doubled count reads as a worse problem than there is.
                if "build/resources" in normalized:
                    continue
                # `src/test/resources` holds CHECKED-IN fixtures for the RuntimeHost's own H2-based
                # tests -- hand-written SQL that is copied into every generated app and never run
                # against a user's engine. Emitted output is what this linter judges; a fixture is
                # not output. check-dialect-sites.py skips `test` for the same reason.
                if "/src/test/" in normalized:
                    continue
                if resolved not in seen:
                    seen.add(resolved)
                    found.append(Path(resolved))
    return sorted(found)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=None)
    parser.add_argument("--script", action="append", default=[],
                        help="Scan this script (repeatable). Default: find emitted scripts.")
    parser.add_argument("--search-root", action="append", default=[],
                        help="Extra directory to search for emitted scripts (repeatable).")
    parser.add_argument("--dialect", default="mysql,sqlserver",
                        help="Comma-separated engines to check portability TO "
                             "(default: mysql,sqlserver -- postgres and h2 are what it is written in).")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--generated-for", default=None, choices=ALL_DIALECTS,
                        help="Which engine these scripts were generated FOR. Enables the "
                             "engine-specific checks (column types), which are meaningless without "
                             "it -- a script generated for H2 legitimately contains UUID.")
    parser.add_argument("--allow-empty", action="store_true",
                        help="Exit 0 when no script is found. Only for a context that legitimately "
                             "has not generated one; a gate should never pass this.")
    args = parser.parse_args(argv)

    root = _repo_root(args.repo)
    dialects = [d.strip().lower() for d in args.dialect.split(",") if d.strip()]
    unknown = [d for d in dialects if d not in ALL_DIALECTS]
    if unknown:
        print(f"unknown dialect(s): {unknown}. Known: {ALL_DIALECTS}", file=sys.stderr)
        return 2

    scripts = find_scripts(root, args.script, [Path(p) for p in args.search_root])
    if not scripts:
        message = ("no emitted schema script found to scan. Generate an app first (any engine -- "
                   "generation needs no database), or pass --script.")
        if args.json:
            print(json.dumps({"schemaVersion": "npdev-sql-portability-report.v1",
                              "ok": bool(args.allow_empty), "scripts": [], "findings": [],
                              "note": message}, indent=2))
        else:
            print(f"check-emitted-sql-portability: {message}")
        # A linter with no input has not passed. Exiting 0 here by default would make this checker
        # green on a machine that never generated anything -- the "check that never ran" shape.
        return 0 if args.allow_empty else 2

    findings: list[dict] = []
    for script in scripts:
        findings.extend(scan_script(script, dialects, args.generated_for))

    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-sql-portability-report.v1",
            "ok": not findings,
            "dialects": dialects,
            "scripts": [str(s) for s in scripts],
            "findings": findings,
        }, indent=2))
        return 1 if findings else 0

    print(f"check-emitted-sql-portability: {len(scripts)} script(s), engines {', '.join(dialects)}")
    for script in scripts:
        print(f"  scanned {script}")
    if not findings:
        print("\nOK: every scanned script is portable to every requested engine.")
        return 0

    # GROUPED, with a couple of examples each. A generated app repeats the same idiom hundreds of
    # times and ONE emitter change fixes every repetition -- so a flat list of 52,000 occurrences
    # reports a far worse problem than there is and buries the three facts that matter.
    print()
    groups: dict[tuple[str, str], list[dict]] = {}
    for finding in findings:
        groups.setdefault((finding["dialect"], finding["constructName"]), []).append(finding)
    for (dialect, name), items in sorted(groups.items()):
        print(f"  {dialect:10} {name:<42} {len(items):>7} occurrence(s)")
        print(f"  {'':10} -> {items[0]['fix']}")
        for example in items[:2]:
            print(f"  {'':10}    {Path(example['script']).name}:{example['line']}  "
                  f"{example['text'][:88]}")
        print()
    engines = sorted({f["dialect"] for f in findings})
    constructs = sorted({f["constructName"] for f in findings})
    print(f"{len(constructs)} construct(s) unsupported on {len(engines)} engine(s); "
          f"{len(findings)} occurrence(s) across {len(scripts)} script(s).")
    print("Each is a statement Flyway would stop at on that engine -- and it stops at the FIRST one, "
          "so fixing them one CI round at a time is what this scan exists to avoid.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
