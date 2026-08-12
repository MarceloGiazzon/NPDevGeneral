#!/usr/bin/env python
"""docs-decoupling-2026-08-11 PLAN.md Phase 1: generates OPEN_GAPS_AND_ROADMAP.md from
ledger/gaps.yml, the same "markdown is generated, YAML is truth" discipline
scripts/quality/generate_open_items.py already applies to OPEN_ITEMS.md.

WHY THIS EXISTS
----------------
`docs/OPEN_GAPS_AND_ROADMAP.md` carried two machine-parsed tables (Sec 1 "Priority index", Sec 7
"Fixed engine bugs") that `scripts/ai/extract_platform_status.py` regex-scraped out of hand-written
markdown into `knowledge/platform-status.json` -- "a database disguised as documentation." Editing
either table risked silently drifting the projection every MCP tool reads. `ledger/gaps.yml` is now
the single source of truth for those two tables; `extract_platform_status.py` reads it directly.

Everything else in the document -- the "how to read this" preamble, the per-item detail sections in
Sec 2-6, the changelog in Sec 8 -- is narrative that no script parses as data. It is preserved
verbatim in `scripts/docs/gaps-roadmap-narrative.md.tmpl`, a template with two substitution markers
(`<!-- GENERATED: priority-index-table -->`, `<!-- GENERATED: fixed-engine-bugs-table -->`) where
this script splices in the two freshly-rendered tables.

md-zero-2026-08-11 PLAN.md Phase 6 (Group G): the rendered doc is no longer committed to the repo
at all -- "nothing to drift-check because nothing is stored" (the plan's own words). This always
writes fresh, to the external Build root (docs/BUILD_OUTPUT_LOCATION_POLICY.md's existing rule for
every other build artifact), never inside this repo. `--check` is gone with it.

Usage:
    python scripts/docs/generate_gaps_roadmap.py            # writes <Build>/docs/OPEN_GAPS_AND_ROADMAP.md
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "ai"))
from npdev_ai_common import build_root  # noqa: E402

WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
LEDGER_PATH = WORKSPACE_ROOT / "ledger" / "gaps.yml"
NARRATIVE_PATH = WORKSPACE_ROOT / "scripts" / "docs" / "gaps-roadmap-narrative.md.tmpl"

TABLE1_MARKER = "<!-- GENERATED: priority-index-table -->"
TABLE7_MARKER = "<!-- GENERATED: fixed-engine-bugs-table -->"

SCHEMA_VERSION = "gaps-ledger.v1"


def load_ledger() -> dict:
    data = yaml.safe_load(LEDGER_PATH.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(
            f"{LEDGER_PATH}: unsupported schemaVersion {data.get('schemaVersion')!r}, "
            f"expected {SCHEMA_VERSION!r}"
        )
    return data


def render_priority_index(rows: list[dict]) -> str:
    lines = [
        "| ID | Title | Category | Status | Priority | Est. size |",
        "|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row['idCell']} | {row['title']} | {row['category']} | {row['statusRaw']} | "
            f"{row['priority']} | {row.get('size', '')} |"
        )
    return "\n".join(lines)


def render_fixed_engine_bugs(rows: list[dict]) -> str:
    lines = ["| ID | Fix |", "|---|---|"]
    for row in rows:
        lines.append(f"| {row['idCell']} | {row['fix']} |")
    return "\n".join(lines)


def render(data: dict) -> str:
    template = NARRATIVE_PATH.read_text(encoding="utf-8")
    if TABLE1_MARKER not in template:
        raise ValueError(f"{NARRATIVE_PATH} is missing {TABLE1_MARKER!r}")
    if TABLE7_MARKER not in template:
        raise ValueError(f"{NARRATIVE_PATH} is missing {TABLE7_MARKER!r}")
    text = template.replace(TABLE1_MARKER, render_priority_index(data["priorityIndex"]))
    text = text.replace(TABLE7_MARKER, render_fixed_engine_bugs(data["fixedEngineBugs"]))
    return text


def main() -> int:
    data = load_ledger()
    rendered = render(data)

    out_dir = build_root() / "docs"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "OPEN_GAPS_AND_ROADMAP.md"
    out_path.write_text(rendered, encoding="utf-8")
    print(f"wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
