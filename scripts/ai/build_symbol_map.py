#!/usr/bin/env python3
"""Emit a grep-first symbol index of the files an AI session reads most.

    python scripts/ai/build_symbol_map.py

Writes to the Build root (never inside the repo -- BUILD_OUTPUT_LOCATION_POLICY):

    <Build>/npdev-ai/symbol-map.txt    path:line:kind:name   <- grep this
    <Build>/npdev-ai/symbol-map.json   the same, structured

The point is to replace a read with a grep.  Measured 2026-08-30 over 14 days:
2,443 reads were repeats of a file already read in the same session, and 40% of
all reads carried no offset/limit.  `npdev_cli.py` was pulled 371 times.  Cost is
(requests x average context), so a needless whole-file read is billed once when
it lands and again on every later request in that session.

Workflow it enables:

    grep -n "def cmd_verify" <Build>/npdev-ai/symbol-map.txt
    -> NPDevCli/npdev_cli.py:1462:def:cmd_verify
    then Read(file_path=..., offset=1450, limit=80)

Which files are indexed is declared in scripts/policy/symbol-map-policy.json --
this script holds no path knowledge.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

POLICY = Path(__file__).resolve().parents[1] / "policy" / "symbol-map-policy.json"

# Deliberately regex, not a real parser: this must stay fast over ~3,700 files
# and be correct about *line numbers*, which is all a reader needs to jump.
PATTERNS = {
    ".py": [
        (re.compile(r"^\s*class\s+([A-Za-z_]\w*)"), "class"),
        (re.compile(r"^\s*(?:async\s+)?def\s+([A-Za-z_]\w*)"), "def"),
    ],
    ".java": [
        (re.compile(r"^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?"
                    r"(?:abstract\s+)?(?:class|interface|enum|record)\s+([A-Za-z_]\w*)"), "type"),
        # methods: a return type then a name then '(' -- excluding control keywords
        (re.compile(r"^\s{2,}(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?"
                    r"(?:synchronized\s+)?[\w<>\[\],.\s?]+\s+([a-z]\w*)\s*\("), "method"),
    ],
    ".ps1": [
        (re.compile(r"^\s*function\s+([A-Za-z_][\w-]*)", re.I), "function"),
        (re.compile(r"^\s*param\s*\(", re.I), "param"),
    ],
    ".mustache": [
        # template sections are the navigable unit here, not code symbols
        (re.compile(r"^\s*\{\{#\s*([A-Za-z_][\w.]*)\s*\}\}"), "section"),
        (re.compile(r"^\s*<!--\s*(.{4,60}?)\s*-->"), "comment"),
    ],
}


def repo_root() -> Path:
    """Identified by CONTENTS, never by directory name (REG-144)."""
    here = Path(__file__).resolve()
    for cand in [here.parent.parent.parent, *here.parents]:
        if all((cand / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return cand
    raise SystemExit("could not locate the repo root by its module directories")


def out_dir(root: Path) -> Path:
    env = os.environ.get("NPDEV_BUILD_ROOT")
    base = Path(env) if env else root.parent / "Build"
    d = base / "npdev-ai"
    d.mkdir(parents=True, exist_ok=True)
    return d


def scan(path: Path, rel: str) -> list[dict]:
    pats = PATTERNS.get(path.suffix)
    if not pats:
        return []
    found: list[dict] = []
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for n, line in enumerate(fh, 1):
                if len(line) > 400:
                    continue
                for rx, kind in pats:
                    m = rx.match(line)
                    if m:
                        name = m.group(1) if m.groups() else kind
                        if name in ("if", "for", "while", "switch", "catch", "return", "new"):
                            continue
                        found.append({"line": n, "kind": kind, "name": name})
                        break
    except OSError:
        return []
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    root = repo_root()
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    excludes = policy.get("excludeGlobs", [])
    min_bytes = policy.get("minFileBytes", 0)

    def excluded(rel: str) -> bool:
        p = PurePath = rel.replace("\\", "/")
        return any(Path(p).match(g) for g in excludes)

    index: dict[str, list[dict]] = {}
    n_files = n_syms = 0

    for entry in policy["roots"]:
        for path in sorted(root.glob(entry["glob"])):
            if not path.is_file():
                continue
            rel = path.relative_to(root).as_posix()
            if excluded(rel):
                continue
            try:
                if path.stat().st_size < min_bytes:
                    continue
            except OSError:
                continue
            syms = scan(path, rel)
            if syms:
                index[rel] = syms
                n_files += 1
                n_syms += len(syms)

    d = out_dir(root)
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")

    txt_path = d / "symbol-map.txt"
    lines = [f"# npdev symbol map  {stamp}  {n_files} files  {n_syms} symbols",
             "# grep this, then Read(file_path, offset, limit) -- do not read the file whole",
             "# format: path:line:kind:name", ""]
    for rel in sorted(index):
        for s in index[rel]:
            lines.append(f"{rel}:{s['line']}:{s['kind']}:{s['name']}")
    txt_path.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))

    json_path = d / "symbol-map.json"
    json_path.write_bytes(json.dumps(
        {"generatedAt": stamp, "root": str(root), "fileCount": n_files,
         "symbolCount": n_syms, "files": index},
        indent=1).encode("utf-8"))

    if not args.quiet:
        print(f"symbol map: {n_files} files, {n_syms} symbols")
        print(f"  {txt_path}")
        print(f"  {json_path}")
        print(f"  size: {txt_path.stat().st_size // 1024} KB text")
    return 0


if __name__ == "__main__":
    sys.exit(main())
