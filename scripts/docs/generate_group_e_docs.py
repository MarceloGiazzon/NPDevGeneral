#!/usr/bin/env python3
"""Renders docs/NPDEV_CONCEPTS_DEEP_DIVE.md, docs/NPDEV_USER_MANUAL.md,
docs/ai/AUTHORING_FOR_AI.md and docs/ai/UI_GENERATION_PROMPT.md from content/*.yml, and mirrors
each content/*.yml as content/*.json alongside it.

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

FOURTH TARGET, ADDED PHASE 7
-----------------------------
docs/ai/UI_GENERATION_PROMPT.md joined this group for the same reason, found by
check-no-markdown-reads.py, not by inspection: `npdev generate screen` (NPDevCli/npdev_cli.py, a
real end-user command) assembled its AI prompt by `Path.read_text()`-ing this doc directly. Same
fix, same shape -- content/ui-generation-prompt.yml is the source, npdev_cli.py reads the JSON
mirror (same PyYAML constraint as build_rag_index.py: this runs on every user's machine).

STALENESS DETECTION MOVED TO GIT, NOT --check (Phase 7, PLAN-11-to-4.md Item 2)
--------------------------------------------------------------------------------
`--check` used to read the CURRENTLY COMMITTED .md/.json back off disk to compare against a fresh
in-memory render -- a script reading markdown content, one level removed from the docs themselves
(found while building check-no-markdown-reads.py). This script now only ever WRITES; staleness
detection moves to the caller, using git to diff bytes without this process opening the .md itself:

    python scripts/docs/generate_group_e_docs.py
    git diff --exit-code -- docs/NPDEV_CONCEPTS_DEEP_DIVE.md docs/NPDEV_USER_MANUAL.md docs/ai/AUTHORING_FOR_AI.md docs/ai/UI_GENERATION_PROMPT.md content/npdev-concepts-deep-dive.json content/npdev-user-manual.json content/authoring-for-ai.json content/ui-generation-prompt.json

USAGE
-----
    python scripts/docs/generate_group_e_docs.py            # write all 4 docs + json mirrors
"""
from __future__ import annotations

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
    ("content/ui-generation-prompt.yml", "content/ui-generation-prompt.json", "docs/ai/UI_GENERATION_PROMPT.md"),
]


def render(doc: dict) -> str:
    """Exact inverse of the split used to build content/*.yml: preamble, then each section's
    heading line + body, all joined by newline -- see that conversion's own round-trip proof."""
    parts = doc["preamble"].split("\n")
    for section in doc["sections"]:
        parts.append("#" * section["level"] + " " + section["title"])
        parts.extend(section["body"].split("\n"))
    return "\n".join(parts)


def main(_argv: list[str]) -> int:
    for yaml_rel, json_rel, md_rel in TARGETS:
        yaml_path = _REPO_ROOT / yaml_rel
        json_path = _REPO_ROOT / json_rel
        md_path = _REPO_ROOT / md_rel
        doc = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
        rendered_md = render(doc)
        rendered_json = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"

        md_path.write_text(rendered_md, encoding="utf-8")
        print(f"wrote {md_rel}")
        json_path.write_text(rendered_json, encoding="utf-8")
        print(f"wrote {json_rel}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
