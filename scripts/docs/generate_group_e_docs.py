#!/usr/bin/env python3
"""Renders docs/NPDEV_CONCEPTS_DEEP_DIVE.md, docs/NPDEV_USER_MANUAL.md and
docs/ai/AUTHORING_FOR_AI.md from content/*.yml.

WHY THIS EXISTS
---------------
md-zero-2026-08-11 PLAN.md Phase 4 (Group E): these three docs are the prose scripts/ai/build_rag_index.py
chunks for the npdev_search_examples MCP tool, and AUTHORING_FOR_AI.md is also concatenated whole
into scripts/ai/build_core_context.py's cacheable prompt prefix. Both builders used to
`Path.read_text()` the .md files directly -- and both did `if path.exists() else ""`, so a moved or
renamed doc silently emptied that section of the AI context with every gate green (found while
building this). The prose now lives in content/*.yml (same body text, split into preamble + titled
`##`/`###` sections, identical shape to the chunker's own splitting rule); the builders read that
YAML directly and hard-fail if it is missing. This script is the only thing that turns the YAML back
into the three tracked, human-readable .md files -- nothing else opens a .md.

USAGE
-----
    python scripts/docs/generate_group_e_docs.py            # write all 3 docs
    python scripts/docs/generate_group_e_docs.py --check    # exit 1 if any is stale
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent

TARGETS = [
    ("content/npdev-concepts-deep-dive.yml", "docs/NPDEV_CONCEPTS_DEEP_DIVE.md"),
    ("content/npdev-user-manual.yml", "docs/NPDEV_USER_MANUAL.md"),
    ("content/authoring-for-ai.yml", "docs/ai/AUTHORING_FOR_AI.md"),
]


def render(doc: dict) -> str:
    """Exact inverse of the split used to build content/*.yml: preamble, then each section's
    heading line + body, all joined by newline -- see that conversion's own round-trip proof."""
    parts = doc["preamble"].split("\n")
    for section in doc["sections"]:
        parts.append("#" * section["level"] + " " + section["title"])
        parts.extend(section["body"].split("\n"))
    return "\n".join(parts)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 if any rendered doc is stale, write nothing")
    args = parser.parse_args(argv)

    stale = []
    for yaml_rel, md_rel in TARGETS:
        yaml_path = _REPO_ROOT / yaml_rel
        md_path = _REPO_ROOT / md_rel
        doc = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
        rendered = render(doc)

        if args.check:
            current = md_path.read_text(encoding="utf-8") if md_path.exists() else None
            if current != rendered:
                stale.append(md_rel)
        else:
            md_path.write_text(rendered, encoding="utf-8")
            print(f"wrote {md_rel}")

    if args.check:
        if stale:
            for rel in stale:
                print(f"STALE: {rel} does not match its content/*.yml source (run without --check to regenerate)",
                      file=sys.stderr)
            return 1
        print("OK: all 3 Group E docs are current.")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
