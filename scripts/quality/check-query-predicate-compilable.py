#!/usr/bin/env python3
r"""LC-P0's detector (Wave 0.4, MASTER_AI_PLATFORM_PROGRAMME_v2.md): does every declared `where` in
the corpus compile under the predicate grammar the runtime now ENFORCES?

WHY THIS EXISTS
---------------
LC-P0 changed `ConceptQueryFilterSupport` from "a clause I cannot parse is left unenforced" to "a
clause I cannot compile is a named error". That is a real behavioural break with no codemod, and the
programme's own risk register (R1) says so: *"there is no fix for 'your filter never worked'"* --
a model whose `where` was silently doing nothing will now fail loudly, which is the point, but an
author deserves to find out from a gate rather than from a running app.

So this walks every corpus model and reports any `where` the grammar cannot compile:

    where   := clause ( "&&" clause )*
    clause  := field op literal
    op      := "==" | "!=" | ">=" | "<=" | ">" | "<"
    literal := "'" text "'" | number | true | false

HONEST LIMITATION, NAMED RATHER THAN BURIED
-------------------------------------------
This reimplements the grammar in Python; the authority is
`NPDevKernel/.../concepts/ConceptQueryPredicateCompiler.java`. Two implementations of one grammar is
exactly the drift risk `docs/X0_SILENT_EXPRESSION_REGISTER.md` is about, and it is accepted here only
because the alternative -- booting a JVM per corpus model from a gate -- is worse, and because this
checker fails CLOSED: anything it cannot compile is reported, so drift makes it noisier, not quieter.
`--calibrate` pins both directions against the same fixtures the Java test uses.

The durable fix is to move the compiler to a module both the DSL validator and the kernel can use, so
`queries[].where` is refused at MODEL-VALIDATION time and this checker can be deleted. Filed as the
follow-up in LC-P0's evidence; not done here.

    python check-query-predicate-compilable.py
    python check-query-predicate-compilable.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_ROOTS = [REPO_ROOT / "NPDevSamples", Path(r"D:\WorkSpace\NPDev\AppGen\apps")]
ALLOWLIST_PATH = Path(__file__).resolve().parent / "query-predicate-allowlist.json"

OPERATORS_LONGEST_FIRST = ["==", "!=", ">=", "<=", ">", "<"]
FIELD_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def _index_outside_quotes(text: str, token: str) -> int:
    in_quote = False
    for index in range(len(text) - len(token) + 1):
        if text[index] == "'":
            in_quote = not in_quote
            continue
        if not in_quote and text.startswith(token, index):
            return index
    return -1


def _split_on_and(where: str) -> list[str]:
    parts, in_quote, start, index = [], False, 0, 0
    while index < len(where):
        if where[index] == "'":
            in_quote = not in_quote
        elif not in_quote and where.startswith("&&", index):
            parts.append(where[start:index])
            index += 1
            start = index + 1
        index += 1
    parts.append(where[start:])
    return parts


def compile_where(where: str | None) -> tuple[bool, str]:
    """(compilable, reason). Mirrors ConceptQueryPredicateCompiler.compile."""
    if where is None or not where.strip():
        return True, "no predicate declared"
    text = where.strip()
    if "||" in text:
        return False, "'||' (OR) is not supported -- ConceptQuery filters are AND-combined by contract"
    clauses = _split_on_and(text)
    for raw in clauses:
        clause = raw.strip()
        if not clause:
            return False, "empty clause between '&&' operators"
        for operator in OPERATORS_LONGEST_FIRST:
            at = _index_outside_quotes(clause, operator)
            if at < 0:
                continue
            field = clause[:at].strip()
            literal = clause[at + len(operator):].strip()
            if not field:
                return False, f"no field name before '{operator}' in {clause!r}"
            if not FIELD_RE.match(field):
                return False, (f"{field!r} is not a plain field name (nested paths, functions and "
                               f"expressions are not supported) in {clause!r}")
            if not literal:
                return False, f"no literal after '{operator}' in {clause!r}"
            if len(literal) >= 2 and literal[0] == "'" and literal[-1] == "'":
                if "'" in literal[1:-1]:
                    return False, f"unbalanced quotes in literal {literal} in {clause!r}"
                break
            if literal.lower() in ("true", "false"):
                break
            try:
                float(literal) if "." in literal else int(literal)
            except ValueError:
                hint = (" -- a $-reference is not resolved here; substitute it before compiling"
                        if literal.startswith("$") else "")
                return False, (f"literal {literal!r} is neither a quoted string, a number, nor a "
                               f"boolean in {clause!r}{hint}")
            break
        else:
            return False, f"no supported comparison operator found in {clause!r}"
    return True, "ok"


def declared_wheres(model: dict) -> list[tuple[str, str]]:
    """(location, where) for every declared predicate a model carries."""
    found: list[tuple[str, str]] = []
    for query in model.get("queries") or []:
        if isinstance(query, dict) and query.get("where"):
            found.append((f"queries[{query.get('name', '?')}].where", query["where"]))
    for panel in model.get("panels") or []:
        if not isinstance(panel, dict):
            continue
        for source in panel.get("dataSources") or []:
            if isinstance(source, dict) and source.get("where"):
                found.append((f"panels[{panel.get('name', '?')}].dataSources"
                              f"[{source.get('name', '?')}].where", source["where"]))
    return found


def model_files() -> list[Path]:
    files: list[Path] = []
    for root in CORPUS_ROOTS:
        if root.exists():
            files.extend(sorted(root.rglob("model.json")))
    return [f for f in files if "Output" not in f.parts and "node_modules" not in f.parts]


def corpus_label(path: Path) -> str:
    """'AppGen/apps/pack-sample' or 'NPDevSamples/<id>' -- the same labelling validate-corpus.py uses,
    so an exemption survives a file move inside the app but not a rename of the app itself."""
    parts = path.parts
    for index, part in enumerate(parts):
        if part == "apps" and index >= 1:
            return "AppGen/apps/" + "/".join(parts[index + 1:index + 2])
        if part == "NPDevSamples":
            return "NPDevSamples/" + "/".join(parts[index + 1:index + 2])
    return path.as_posix()


def load_allowlist() -> dict:
    if not ALLOWLIST_PATH.is_file():
        return {}
    return json.loads(ALLOWLIST_PATH.read_text(encoding="utf-8-sig")).get("cleared", {})


def run() -> int:
    files = model_files()
    allowlist = load_allowlist()
    problems: list[str] = []
    exempted: list[str] = []
    checked = 0
    print(f"Query-predicate compilability ({len(files)} corpus model(s))")
    for path in files:
        try:
            model = json.loads(path.read_text(encoding="utf-8-sig"))
        except (json.JSONDecodeError, OSError):
            continue  # validate-corpus.py owns parse failures; this checker owns predicates only
        for location, where in declared_wheres(model):
            checked += 1
            ok, reason = compile_where(where)
            if ok:
                continue
            key = f"{corpus_label(path)}::{location}"
            entry = allowlist.get(key)
            if entry is not None and entry.get("where") == where:
                exempted.append(f"{key} -- {entry.get('why', 'no reason recorded')}")
                continue
            try:
                shown = path.relative_to(REPO_ROOT).as_posix()
            except ValueError:
                shown = path.as_posix()
            problems.append(f"{shown}: {location} = {where!r} -- {reason}")
    print(f"  {checked} declared predicate(s) checked, {len(problems)} uncompilable, "
          f"{len(exempted)} filed exemption(s)")
    for entry in exempted:
        # Printed every run, never silent: an exempted predicate is still broken at runtime.
        print(f"  [filed] {entry}")
    if problems:
        print("\nFAIL: a declared `where` cannot be compiled, so it will be REFUSED at runtime "
              "(LC-P0) rather than silently unenforced:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("OK: every declared predicate compiles under the enforced grammar.")
    return 0


def calibrate() -> int:
    """Both directions, against the same shapes ConceptQueryFilterSupportRedTest pins in Java."""
    cases = [
        ("status == 'ACTIVE'", True),
        ("status != 'ACTIVE'", True),
        ("qty > 5", True),
        ("qty >= 9", True),
        ("status == 'ACTIVE' && warehouseId == 'W1'", True),
        ("status == 'CLOSED' && warehouseId == 'W2' && qty >= 9", True),
        ("text == 'a && b'", True),
        ("active == true", True),
        (None, True),
        ("status in ('ACTIVE','CLOSED')", False),
        ("status == 'ACTIVE' || status == 'CLOSED'", False),
        ("upper(status) == 'ACTIVE'", False),
        ("order.status == 'ACTIVE'", False),
        ("status == ACTIVE", False),
        ("status == $ctx.status", False),
        ("status ==", False),
        ("== 'ACTIVE'", False),
    ]
    ok = True
    print("Calibration -- must accept every supported shape and refuse every unsupported one:")
    for where, expected in cases:
        actual, reason = compile_where(where)
        passed = actual == expected
        ok = ok and passed
        verdict = "accept" if actual else "refuse"
        print(f"  [{'PASS' if passed else 'FAIL'}] {where!r} -> {verdict}"
              f"{'' if actual else ' (' + reason + ')'}")
    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--calibrate", action="store_true")
    args = parser.parse_args(argv[1:])
    return calibrate() if args.calibrate else run()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
