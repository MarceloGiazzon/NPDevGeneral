#!/usr/bin/env python3
"""Project the gaps ledger's machine-readable tables into knowledge/platform-status.json.

The ledger (docs/OPEN_GAPS_AND_ROADMAP.md) is human markdown that changes fast. Rather than
hand-copy a "what's supported" list into the MCP server (which would rot and lie), we DERIVE a
machine-readable status projection from the ledger's own stable-ID tables:

  - the "## 1. Priority index" table (open/partial/done items, 6 columns), and
  - the "## 7. Fixed engine bugs" table (closed items + lifted boundaries, 2 columns).

Output is committed at knowledge/platform-status.json and CI-checked against a fresh extraction, so
the projection and the ledger can never silently diverge (run-ai-knowledge-gate.ps1).

Usage:
    python scripts/ai/extract_platform_status.py                 # write the committed projection
    python scripts/ai/extract_platform_status.py --check         # exit 1 if committed file is stale
    python scripts/ai/extract_platform_status.py --stdout        # print, don't write
"""

from __future__ import annotations

import argparse
import contextlib
import json
import re
import sys
from pathlib import Path
from typing import Any

from npdev_ai_common import repo_root

LEDGER = "docs/OPEN_GAPS_AND_ROADMAP.md"
OUTPUT = "knowledge/platform-status.json"

_ID = re.compile(r"^\s*(#\d+|[A-Z][A-Za-z]*-[A-Za-z0-9][A-Za-z0-9-]*)")
_STATUS_WORD = re.compile(r"^(OPEN|PARTIAL|NEEDS-VERIFY|DONE|BOUNDARY|LIFTED)", re.IGNORECASE)
_HEADING = re.compile(r"^#{2,3}\s+(.*)$")


def _cells(line: str) -> list[str] | None:
    """Return the trimmed cells of a markdown table row, or None if the line isn't one."""
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    inner = stripped[1:-1]
    if set(inner.replace("|", "").strip()) <= {"-", ":", " "} and "-" in inner:
        return None  # separator row (|---|---|)
    return [cell.strip() for cell in inner.split("|")]


def _id_of(cell: str) -> str | None:
    match = _ID.match(cell)
    return match.group(1) if match else None


def _split_status(raw: str) -> tuple[str, str]:
    """'PARTIAL (needs your call)' -> ('PARTIAL', 'needs your call')."""
    raw = raw.strip()
    word = _STATUS_WORD.match(raw)
    if not word:
        return raw, ""
    head = word.group(1).upper()
    rest = raw[word.end():].strip().strip("()").strip()
    return head, rest


def _strip_md(text: str) -> str:
    text = re.sub(r"\*\(.*?\)\*", "", text)          # *(lifted ...)* annotations
    text = re.sub(r"[`*]", "", text)                 # stray backticks / emphasis
    return text.strip()


def extract(root: Path) -> list[dict[str, Any]]:
    ledger = (root / LEDGER).read_text(encoding="utf-8")
    section = ""
    seen: set[str] = set()
    out: list[dict[str, Any]] = []

    for line in ledger.splitlines():
        heading = _HEADING.match(line)
        if heading:
            section = heading.group(1)
            continue
        cells = _cells(line)
        if not cells:
            continue
        item_id = _id_of(cells[0])
        if not item_id or item_id in ("ID",):
            continue

        if section.startswith("1.") and len(cells) >= 6:
            status, notes = _split_status(cells[3])
            entry = {
                "id": item_id,
                "title": _strip_md(cells[1]),
                "category": _strip_md(cells[2]),
                "status": status,
                "priority": _strip_md(cells[4]),
                "notes": notes,
            }
        elif section.startswith("7.") and len(cells) >= 2:
            lifted = "lifted" in cells[0].lower() or "lifted" in cells[1].lower()
            entry = {
                "id": item_id,
                "title": _strip_md(cells[1])[:200],
                "category": "lifted-boundary" if lifted else "fixed-bug",
                "status": "LIFTED" if lifted else "DONE",
                "priority": "",
                "notes": _strip_md(cells[1]),
            }
        else:
            continue

        if item_id in seen:  # first occurrence wins (§1 outranks §7 for shared ids)
            continue
        seen.add(item_id)
        out.append(entry)

    return out


def render(entries: list[dict[str, Any]], root: Path) -> str:
    doc = {
        "schemaVersion": "platform-status.v1",
        "generatedFrom": LEDGER,
        "note": "DERIVED -- do not hand-edit. Regenerate via scripts/ai/extract_platform_status.py.",
        "count": len(entries),
        "items": entries,
    }
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Fail if the committed projection is stale.")
    parser.add_argument("--stdout", action="store_true", help="Print instead of writing.")
    args = parser.parse_args(argv)

    root = repo_root()
    text = render(extract(root), root)
    target = root / OUTPUT

    if args.stdout:
        with contextlib.suppress(Exception):
            sys.stdout.reconfigure(encoding="utf-8")  # ledger has en-dash/arrow chars
        sys.stdout.write(text)
        return 0
    if args.check:
        current = target.read_text(encoding="utf-8") if target.exists() else ""
        if current != text:
            sys.stderr.write(
                f"platform-status projection is STALE: {OUTPUT} does not match a fresh extraction "
                f"of {LEDGER}. Run: python scripts/ai/extract_platform_status.py\n"
            )
            return 1
        print(f"platform-status projection up to date ({OUTPUT})")
        return 0

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    print(f"wrote {target} ({text.count(chr(10))} lines)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
