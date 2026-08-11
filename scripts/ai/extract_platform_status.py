#!/usr/bin/env python3
r"""Project the gaps ledger into knowledge/platform-status.json.

docs-decoupling-2026-08-11 PLAN.md Phase 1 inverted the ledger from markdown-as-database
(docs/OPEN_GAPS_AND_ROADMAP.md's own "## 1. Priority index" / "## 7. Fixed engine bugs" tables,
regex-scraped out of hand-written prose) to `ledger/gaps.yml`, machine-read structured data.
`docs/OPEN_GAPS_AND_ROADMAP.md` is now RENDERED from that YAML
(scripts/docs/generate_gaps_roadmap.py), not parsed as an input -- editing it by hand no longer
risks silently drifting this projection.

Each YAML row is resynthesized into the same "| cell | cell | ... |" markdown-row shape the old
parser consumed, then run through the SAME `_cells()`/`_id_of()`/`_split_status()`/`_strip_md()`
helpers, byte-for-byte unchanged from the markdown-parsing era -- including their pre-existing
quirk of not respecting a backslash-escaped `\|` inside a cell as non-delimiting (e.g. the ARCH-6
row's "top-level `\|\|`/`&&`" truncates its own title at the first `\|`, both before and after this
change). That quirk is not this migration's to fix; the point of resynthesis-then-reuse is that the
extraction logic did not change at all, only where the row text comes from -- so the output is
proven byte-identical, bugs included (see the Phase 1 commit message for the before/after SHA-256).

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

import yaml

from npdev_ai_common import repo_root

LEDGER = "ledger/gaps.yml"
SOURCE_DOC = "docs/OPEN_GAPS_AND_ROADMAP.md"
OUTPUT = "knowledge/platform-status.json"

_ID = re.compile(r"^\s*(#\d+|[A-Z][A-Za-z]*-[A-Za-z0-9][A-Za-z0-9-]*)")
_STATUS_WORD = re.compile(r"^(OPEN|PARTIAL|NEEDS-VERIFY|DONE|BOUNDARY|LIFTED)", re.IGNORECASE)


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


def _row_to_markdown(cells: list[str]) -> str:
    """Resynthesize a YAML row back into the "| cell | cell | ... |" shape `_cells()` expects --
    the seam that lets this function's caller reuse the markdown-parsing-era extraction logic
    completely unchanged (see module docstring)."""
    return "| " + " | ".join(cells) + " |"


def extract(root: Path) -> list[dict[str, Any]]:
    ledger_path = root / LEDGER
    data = yaml.safe_load(ledger_path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != "gaps-ledger.v1":
        raise ValueError(
            f"{ledger_path}: unsupported schemaVersion {data.get('schemaVersion')!r}, "
            f"expected 'gaps-ledger.v1'"
        )
    seen: set[str] = set()
    out: list[dict[str, Any]] = []

    for row in data.get("priorityIndex", []):
        line = _row_to_markdown([row["idCell"], row["title"], row["category"],
                                  row["statusRaw"], row["priority"], row.get("size", "")])
        cells = _cells(line)
        item_id = _id_of(cells[0])
        if not item_id or item_id in seen:
            continue
        status, notes = _split_status(cells[3])
        seen.add(item_id)
        out.append({
            "id": item_id,
            "title": _strip_md(cells[1]),
            "category": _strip_md(cells[2]),
            "status": status,
            "priority": _strip_md(cells[4]),
            "notes": notes,
        })

    for row in data.get("fixedEngineBugs", []):
        line = _row_to_markdown([row["idCell"], row["fix"]])
        cells = _cells(line)
        item_id = _id_of(cells[0])
        if not item_id or item_id in seen:
            continue
        lifted = "lifted" in cells[0].lower() or "lifted" in cells[1].lower()
        seen.add(item_id)
        out.append({
            "id": item_id,
            "title": _strip_md(cells[1])[:200],
            "category": "lifted-boundary" if lifted else "fixed-bug",
            "status": "LIFTED" if lifted else "DONE",
            "priority": "",
            "notes": _strip_md(cells[1]),
        })

    return out


def render(entries: list[dict[str, Any]], root: Path) -> str:
    doc = {
        "schemaVersion": "platform-status.v1",
        # Kept as the human-facing doc (byte-identical output contract, Phase 1's own acceptance
        # bar) even though the true machine-read input is now LEDGER ("ledger/gaps.yml") --
        # SOURCE_DOC is still where a reader goes to see this data, just rendered FROM the YAML now
        # instead of parsed INTO it.
        "generatedFrom": SOURCE_DOC,
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
