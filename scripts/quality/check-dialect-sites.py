#!/usr/bin/env python3

"""Dialect-site regression gate: keep dialect-bound SQL out of everything but the dialect package.



WHY THIS EXISTS

---------------

storage/PLAN.md's S1 moved 41 dialect-bound SQL sites -- LIMIT/OFFSET, ON CONFLICT, jsonb,

information_schema, SERIAL, backtick-vs-bracket quoting -- out of 19 files and into

`com.npdev.kernel.storage.sql`. The measured count outside that package went 41 -> 0.



**A zero that nothing defends goes back up.** The next adapter that needs a page of rows will write

`LIMIT ? OFFSET ?` inline, because that is what every neighbouring line looked like until recently

and because it works perfectly on Postgres. It keeps working until MySQL or SQL Server runs it, at

which point the query either fails or -- for pagination specifically -- silently returns the wrong

page, since SQL Server binds (offset, limit) in the opposite order. That is a data defect discovered

in production, which is the failure this whole seam exists to prevent.



So the count is now a gate. Adding a dialect-bound construct outside the dialect package fails here,

with the file, the line, and what to call instead.



This is the repo-resident half of `storage/helpers/dialect-site-inventory.py`, which stays outside

the repo as the richer exploration tool. The patterns are deliberately the same, and each one earned

its exclusions the hard way -- see the notes on each.



USAGE

    python scripts/quality/check-dialect-sites.py

    python scripts/quality/check-dialect-sites.py --json

    python scripts/quality/check-dialect-sites.py --repo <path>



Exit 0 = no dialect-bound site outside the dialect package. Exit 1 = at least one. Exit 2 = usage.

"""

from __future__ import annotations



import argparse

import json

import re

import sys

from pathlib import Path



# Areas that speak SQL. The kernel is included so a site added there is seen -- it was NOT scanned

# before 2026-08-08, which made "0 outside the dialect package" trivially true for the whole kernel.

SCAN_ROOTS = [

    "NPDevKernel/kernel",

    "NPDevKernel/adapters",

    "NPDevRuntimeHost/src/main/java/com/finalexec/db",

    "NPDevRuntimeHost/src/main/java/com/finalexec/npdev",

    "NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig",

]



# Where these constructs are SUPPOSED to live. Everything else must be zero.

DIALECT_PACKAGE = "com/npdev/kernel/storage/sql"



SKIP_DIR_PARTS = {"build", "target", ".gradle", "test", "generated"}



# construct -> (pattern, what to call instead)

CONSTRUCTS = {

    # ---------------------------------------------------------------------------------------

    # STOR-5's three guarded idioms. Added the day they were fixed, which is the point: the

    # emitter wrote `CREATE TABLE IF NOT EXISTS` inline, unconditionally, and NPDev's own first

    # migration could not run on two of its four engines as a result. Each cost a ~12-minute CI

    # round to find, because Flyway stops at the first statement it cannot execute.

    #

    # These patterns make the NEXT one fail here, locally, in seconds. Its sibling

    # check-emitted-sql-portability.py scans the OUTPUT; this scans the SOURCE, and both are

    # needed -- an emitter can assemble a statement in pieces no source pattern would catch.

    # ---------------------------------------------------------------------------------------

    "guarded-create-table": (

        r'"\s*CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS',

        "dialect.guardedCreateTable(table, createStatement) -- T-SQL has no CREATE TABLE IF NOT EXISTS",

    ),

    "guarded-create-index": (

        r'"\s*CREATE\s+(?:UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS|"\s*INDEX\s+IF\s+NOT\s+EXISTS',

        "dialect.guardedCreateIndex(index, table, createStatement) -- MySQL error 1064, T-SQL syntax error",

    ),

    "guarded-add-column": (
        r'ADD\s+COLUMN\s+IF\s+NOT\s+EXISTS',
        "dialect.guardedAddColumn(table, column, alterStatement) -- neither MySQL nor T-SQL has it",

    ),

    # -----------------------------------------------------------------------------------------
    # STOR-7. A text column plays three roles -- payload, key, defaulted -- and MySQL and SQL
    # Server answer each differently. Six inline `TEXT PRIMARY KEY` statements in the runtime
    # host's own bootstrap DDL were unkeyable on both, and `TEXT DEFAULT 'CLOSED'` is MySQL
    # error 1101. Both were found at Flyway time on first boot, in CI run 31284450437 -- the
    # layer no unit test reaches, one ~12-minute round each.
    # -----------------------------------------------------------------------------------------
    "text-key-column": (
        r"\bTEXT\s+PRIMARY\s+KEY\b|\bTEXT\s+UNIQUE\b|\bTEXT\s+NOT\s+NULL\s+PRIMARY\s+KEY\b",
        "dialect.keyableTextColumnType() -- MySQL error 1170 (no key length), and SQL Server "
        "cannot index NVARCHAR(MAX) at all",
    ),
    "text-default-column": (
        r"\bTEXT\s+(?:NOT\s+NULL\s+)?DEFAULT\b",
        "dialect.defaultableTextColumnType() -- MySQL error 1101, TEXT columns cannot carry a "
        "DEFAULT",
    ),

    "pagination": (

        # `:` must be glued to an identifier (LIMIT :pageSize). Without that the class also matched

        # ordinary Java -- `limit > 0 ? limit : defaultCap` was reported as a pagination site.

        r"\bLIMIT\s+(?:[?\d]|:\w)|\bOFFSET\s+(?:[?\d]|:\w)|\bFETCH\s+(?:FIRST|NEXT)\b|\bSELECT\s+TOP\b",

        "dialect.paginated(sql) / dialect.limited(sql) / dialect.limitOffset()",

    ),

    "upsert": (

        r"\bON\s+CONFLICT\b|\bON\s+DUPLICATE\s+KEY\b|\bMERGE\s+INTO\b",

        "dialect.upsert().statementFor(table, keyColumns, valueColumns)",

    ),

    "returning": (

        r"\bRETURNING\b",

        "dialect.returning() -- and check isInline() first; MySQL has no RETURNING",

    ),

    "auto-increment": (

        # (?<![.\w]) so `Function.identity()` is not an auto-increment column. That method reference

        # was the ENTIRE reported count for this construct before the lookbehind was added.

        r"\bSERIAL\b|\bBIGSERIAL\b|\bAUTO_INCREMENT\b|(?<![.\w])IDENTITY\s*\(",

        "dialect.autoIncrementColumn(SqlType.INT | SqlType.BIGINT)",

    ),

    "json-type": (

        r"\bjsonb\b|\bJSONB\b",

        "dialect.jsonColumnType() / dialect.isJsonColumnType(name) / dialect.portableColumnType(t)",

    ),

    "introspection": (

        r"\binformation_schema\b|\bpg_catalog\b|\bpg_indexes\b|\bpg_class\b|\bpg_attribute\b",

        "dialect.listTablesSql/listColumnsSql/listIndexesSql/constraintExistsSql/systemSchemas",

    ),

    "cast": (

        r"::\s*(?:text|int|integer|bigint|uuid|jsonb|timestamptz|boolean|numeric)\b",

        "dialect.cast(expression, SqlType.X)",

    ),

}



# An escaped \"x\" is only an SQL identifier when the line is actually SQL, and `returning` is an

# ordinary English word that appears in error messages. Both need a statement keyword on the line.

#

# TABLE and INDEX are deliberately NOT in this list: they are nouns, so a JSON writer emitting a key

# called "table" satisfied the guard and slipped through as an identifier-quoting site.

SQL_CONTEXT = re.compile(

    r"\b(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|JOIN|VALUES|SET|INTO|ORDER\s+BY|GROUP\s+BY)\b",

    re.IGNORECASE,

)

CONTEXT_REQUIRED = {"returning"}



# A comment that MENTIONS SQL is not emitted SQL. Counting these is how a keyword grep overstates a

# job by 3x -- the original scan reported ~130 sites where there were 41.

COMMENT = re.compile(r"^\s*(//|\*|/\*|#)")





def iter_java(repo: Path):

    for root in SCAN_ROOTS:

        base = repo / root

        if not base.is_dir():

            continue

        for path in base.rglob("*.java"):

            if any(part in SKIP_DIR_PARTS for part in path.parts):

                continue

            if DIALECT_PACKAGE in path.as_posix():

                continue

            yield path





def scan(repo: Path) -> list[dict]:

    hits: list[dict] = []

    for path in iter_java(repo):

        try:

            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()

        except OSError:

            continue

        for number, line in enumerate(lines, 1):

            if COMMENT.match(line):

                continue

            for construct, (pattern, instead) in CONSTRUCTS.items():

                if not re.search(pattern, line, re.IGNORECASE):

                    continue

                if construct in CONTEXT_REQUIRED and not SQL_CONTEXT.search(line):

                    continue

                hits.append({

                    "file": path.relative_to(repo).as_posix(),

                    "line": number,

                    "construct": construct,

                    "useInstead": instead,

                    "text": line.strip()[:120],

                })

    return hits





def resolve_repo(explicit: str | None) -> Path | None:

    if explicit:

        return Path(explicit).resolve()

    # REG-144: identify the repo by its CONTENTS, never by its directory NAME. Eleven resolvers once

    # looked for a directory literally called 'NPDev_General'; a clone named anything else resolved

    # three different roots and Linux CI was red for twelve days. $PSScriptRoot-equivalent arithmetic

    # (this file is at <repo>/scripts/quality/) is exact and adds no twelfth ancestor walk.

    candidate = Path(__file__).resolve().parents[2]

    modules = ("NPDevContract", "NPDevGenerator", "NPDevKernel")

    return candidate if all((candidate / m).is_dir() for m in modules) else None





def main() -> int:

    parser = argparse.ArgumentParser(description=__doc__,

                                     formatter_class=argparse.RawDescriptionHelpFormatter)

    parser.add_argument("--repo", default=None)

    parser.add_argument("--json", action="store_true")

    args = parser.parse_args()



    repo = resolve_repo(args.repo)

    if repo is None:

        print("error: could not resolve the repo root (the directory holding NPDevContract + "

              "NPDevGenerator + NPDevKernel) from this script's location; pass --repo",

              file=sys.stderr)

        return 2



    hits = scan(repo)



    if args.json:

        print(json.dumps({

            "schemaVersion": "npdev-dialect-sites.v1",

            "repo": str(repo),

            "dialectPackage": DIALECT_PACKAGE,

            "siteCount": len(hits),

            "sites": hits,

        }, indent=2))

        return 1 if hits else 0



    print("Dialect-site gate")

    print("=" * 78)

    if not hits:

        print(f"  PASS -- 0 dialect-bound sites outside {DIALECT_PACKAGE}")

        return 0



    print(f"  FAIL -- {len(hits)} dialect-bound site(s) outside {DIALECT_PACKAGE}\n")

    for hit in hits:

        print(f"  {hit['file']}:{hit['line']}  [{hit['construct']}]")

        print(f"      {hit['text']}")

        print(f"      use: {hit['useInstead']}")

    print("\n  Each of these is SQL that one engine spells differently from another. Inline, it works")

    print("  on Postgres and fails -- or, for pagination, silently returns the WRONG PAGE -- on")

    print("  another engine. Route it through SqlDialect; if the dialect has no method for it yet,")

    print("  add one rather than making an exception here.")

    return 1





if __name__ == "__main__":

    sys.exit(main())

