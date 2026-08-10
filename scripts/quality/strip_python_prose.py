#!/usr/bin/env python3
"""Emit the parts of a Python file that a *source* scan should look at: everything EXCEPT comments
and docstrings.

WHY THIS EXISTS
---------------
`run-portable-tooling-check.ps1` scans `NPDevCli/**/*.py` for hardcoded drive letters
(`(?<![A-Za-z])[A-Za-z]:[\\/]`). That scan is worth keeping -- CLAUDE.md's REG-144 rule is
"never hardcode `D:\\WorkSpace\\...` as a default", and this CLI is exactly where such a default
would land. But the scan read the file as raw text, so it also matched the COMMENTS AND DOCSTRINGS
THAT EXPLAIN A DRIVE-LETTER DEFECT. PORT-2/QUAL-3's records say, in prose, that
`npdev init D:\\Apps\\my-app` generates into `D:\\Apps\\my-app-app` and so two apps shared one
`_ops`. Six such occurrences in four lines of `NPDevCli/npdev_cli.py` and
`NPDevCli/tests/test_ops_toolbox_isolation.py` turned NPDev CI Validation red on 2026-08-10
(run 31421541918), the first run in months where the check actually executed.

Prose about a defect is not the defect. The standing repo rule is to excuse the record with a
reason, never to reword it -- so the scan learns to skip prose rather than the prose being deleted.

WHAT IS *NOT* SKIPPED, DELIBERATELY
-----------------------------------
Only comments and docstrings. Every other string literal stays in the output, because that is where
a real defect hides: `default = "D:\\WorkSpace\\Build"` is a hardcoded drive letter and must keep
failing the check. Excluding the whole file (the cheap alternative) would have blinded the scan in
precisely the file most likely to acquire one.

A docstring is identified structurally, via `ast` -- the first statement of a module, class or
function when it is a bare string expression -- not by "looks triple-quoted". A triple-quoted string
that is assigned, returned or passed as an argument is ordinary code and is still scanned.

Output is JSON (ASCII-escaped, so it survives any console encoding) on stdout:
    [{"path": "<as given>", "text": "<scannable source>"}, ...]

FAIL-OPEN, NEVER FAIL-BLIND. If a file cannot be parsed or tokenized, its ORIGINAL text is
returned. That can only produce a false positive (a red the maintainer investigates), never a false
negative (a silent green), which is the safe direction for a check whose whole purpose is to notice
something.
"""
from __future__ import annotations

import ast
import io
import json
import sys
import tokenize


def _docstring_starts(tree: ast.AST) -> set[tuple[int, int]]:
    """(row, col) start of every docstring node. tokenize rows are 1-based and cols 0-based, which
    is exactly what ast reports, so the two can be compared directly."""
    starts: set[tuple[int, int]] = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        body = getattr(node, "body", None)
        if not body:
            continue
        first = body[0]
        if (
            isinstance(first, ast.Expr)
            and isinstance(first.value, ast.Constant)
            and isinstance(first.value.value, str)
        ):
            starts.add((first.value.lineno, first.value.col_offset))
    return starts


def scannable_source(text: str) -> str:
    """`text` with comments and docstrings removed. On any parse/tokenize failure, `text` itself."""
    try:
        tree = ast.parse(text)
    except (SyntaxError, ValueError):
        return text

    skip = _docstring_starts(tree)
    kept: list[str] = []
    try:
        for token in tokenize.generate_tokens(io.StringIO(text).readline):
            if token.type == tokenize.COMMENT:
                continue
            if token.type == tokenize.STRING and token.start in skip:
                continue
            kept.append(token.string)
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return text
    # Newline-joined rather than reassembled at original offsets: the consumer runs a regex over
    # this, so only the presence of content matters, not its layout.
    return "\n".join(kept)


def main(argv: list[str]) -> int:
    paths = argv[1:]
    if not paths:
        print("usage: strip_python_prose.py <file.py> [<file.py> ...]", file=sys.stderr)
        return 2
    out = []
    for path in paths:
        try:
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
        except OSError as exc:
            print(f"cannot read {path}: {exc}", file=sys.stderr)
            return 1
        out.append({"path": path, "text": scannable_source(text)})
    json.dump(out, sys.stdout, ensure_ascii=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
