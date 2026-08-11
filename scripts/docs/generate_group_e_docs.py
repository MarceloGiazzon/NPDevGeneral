#!/usr/bin/env python3
"""Renders docs/NPDEV_CONCEPTS_DEEP_DIVE.md, docs/NPDEV_USER_MANUAL.md and
docs/ai/AUTHORING_FOR_AI.md from content/*.yml, and mirrors each content/*.yml as content/*.json
alongside it.

WHY THIS EXISTS
---------------
md-zero-2026-08-11 PLAN.md Phase 4 (Group E): these three docs are the prose scripts/ai/build_rag_index.py
chunks for the npdev_search_examples MCP tool, and AUTHORING_FOR_AI.md is also concatenated whole
into scripts/ai/build_core_context.py's cacheable prompt prefix. Both builders used to
`Path.read_text()` the .md files directly -- and both did `if path.exists() else ""`, so a moved or
renamed doc silently emptied that section of the AI context with every gate green (found while
building this). The prose now lives in content/*.yml (same body text, split into preamble + titled
`##`/`###` sections, identical shape to the chunker's own splitting rule); this script renders it
back into the three tracked, human-readable .md files -- nothing else opens a .md.

WHY A JSON MIRROR TOO
----------------------
Found live, the hard way, running the first-run harness (a bare machine, no packages installed
beyond what README documents) against Phase 5's own work: `./npdev setup` calls `build_rag_index.py`
as part of building the AI knowledge index, on every real end-user machine, not just inside a
Docker test container -- and `scripts/requirements.txt`'s PyYAML entry is explicitly REPO-DEV/CI
only ("NOT a dependency of the shipped CLI itself... the Manager ships a private Python with no
third-party packages" -- npdev_jsonschema.py's own docstring). A bare `import yaml` inside
build_rag_index.py or build_core_context.py breaks `npdev setup` on every fresh install. Same fix
as Group D's content/*.json mirrors: build_rag_index.py and build_core_context.py read the JSON
(Python stdlib `json`, zero installed packages); content/*.yml stays the authored source.

USAGE
-----
    python scripts/docs/generate_group_e_docs.py            # write all 3 docs + json mirrors
    python scripts/docs/generate_group_e_docs.py --check    # exit 1 if anything is stale
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent

# (yaml source, json mirror, rendered markdown)
TARGETS = [
    ("content/npdev-concepts-deep-dive.yml", "content/npdev-concepts-deep-dive.json", "docs/NPDEV_CONCEPTS_DEEP_DIVE.md"),
    ("content/npdev-user-manual.yml", "content/npdev-user-manual.json", "docs/NPDEV_USER_MANUAL.md"),
    ("content/authoring-for-ai.yml", "content/authoring-for-ai.json", "docs/ai/AUTHORING_FOR_AI.md"),
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
    parser.add_argument("--check", action="store_true", help="exit 1 if anything is stale, write nothing")
    args = parser.parse_args(argv)

    stale = []
    for yaml_rel, json_rel, md_rel in TARGETS:
        yaml_path = _REPO_ROOT / yaml_rel
        json_path = _REPO_ROOT / json_rel
        md_path = _REPO_ROOT / md_rel
        doc = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
        rendered_md = render(doc)
        rendered_json = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"

        if args.check:
            current_md = md_path.read_text(encoding="utf-8") if md_path.exists() else None
            if current_md != rendered_md:
                stale.append(md_rel)
            current_json = json_path.read_text(encoding="utf-8") if json_path.exists() else None
            if current_json != rendered_json:
                stale.append(json_rel)
        else:
            md_path.write_text(rendered_md, encoding="utf-8")
            print(f"wrote {md_rel}")
            json_path.write_text(rendered_json, encoding="utf-8")
            print(f"wrote {json_rel}")

    if args.check:
        if stale:
            for rel in stale:
                print(f"STALE: {rel} does not match its content/*.yml source (run without --check to regenerate)",
                      file=sys.stderr)
            return 1
        print("OK: all 3 Group E docs and their JSON mirrors are current.")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
