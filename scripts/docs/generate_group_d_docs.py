#!/usr/bin/env python3
"""Renders README.md, docs/GETTING_STARTED.md, docs/YOUR_FIRST_APP.md and docs/AUTHORING_WITH_AI.md
from content/*.yml, and mirrors each content/*.yml as content/*.json alongside it.

WHY THIS EXISTS
---------------
md-zero-2026-08-11 PLAN.md Phase 5 (Group D, "executable docs" -- the plan's own highest-risk item,
since these four docs are what the first-run harness proves a brand-new machine can follow end to
end). Before this, three SEPARATE scripts each parsed markdown out of these docs on their own:
scripts/quality/firstrun-harness/extract_commands.py (README.md's Quickstart section, by regex
heading match), a purpose-built Python heredoc embedded inside run-readme.sh itself
(docs/YOUR_FIRST_APP.md's numbered steps, a second independent parser), and
scripts/quality/check-readme-contract.py's own fenced_sh_commands() (a third, independent
duplicate of the same fence-extraction logic). Three parsers of the same shape of document is how
a change to one doc's structure breaks a check that was never told about it.

Each doc is now split into content/*.yml as a preamble + ordered `##`/`###` sections, each section's
content further split into ordered PROSE and FENCE blocks (never re-merged into one opaque body
string, unlike Group E's docs -- these need per-fence access so a command extractor does not have
to re-parse markdown to find fence boundaries). This script renders the four docs back from that
YAML; nothing else opens any of the four .md files.

WHY A JSON MIRROR TOO
----------------------
The first-run harness runs inside a DELIBERATELY BARE Docker image (scripts/quality/firstrun-harness/
Dockerfile: "Deliberately absent: java, python, pip, pwsh, gradle, node, docker" -- python3 itself
only exists once section 1 installs it from README's own prerequisite list, and even then there is
no pip, so no PyYAML). extract_commands.py runs INSIDE that container. If it read content/*.yml, the
harness would break on every fresh machine -- silently reintroducing exactly the class of defect
this whole plan exists to prevent, just one layer down. content/*.json carries the identical data in
a format Python's stdlib `json` module reads with zero installed packages. content/*.yml stays the
authored, human-edited, git-diffable source; the .json is generated from it, never hand-edited, and
--check verifies both mirrors together.

USAGE
-----
    python scripts/docs/generate_group_d_docs.py            # write all 4 docs + json mirrors
    python scripts/docs/generate_group_d_docs.py --check    # exit 1 if anything is stale
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
    ("content/readme.yml", "content/readme.json", "README.md"),
    ("content/getting-started.yml", "content/getting-started.json", "docs/GETTING_STARTED.md"),
    ("content/your-first-app.yml", "content/your-first-app.json", "docs/YOUR_FIRST_APP.md"),
    ("content/authoring-with-ai.yml", "content/authoring-with-ai.json", "docs/AUTHORING_WITH_AI.md"),
]


def render_blocks(blocks: list[dict]) -> list[str]:
    parts: list[str] = []
    for block in blocks:
        text = block.get("text") or ""
        lines = text.split("\n")
        if block["type"] == "prose":
            parts.extend(lines)
        else:
            parts.append("```" + (block.get("lang") or ""))
            parts.extend(lines)
            parts.append("```")
    return parts


def render(doc: dict) -> str:
    """Exact inverse of the split used to build content/*.yml: preamble blocks, then each
    section's heading line + its own ordered prose/fence blocks -- see that conversion's own
    round-trip proof (in-memory AND disk reload, both diffed byte-for-byte against the source)."""
    parts = render_blocks(doc["preamble"])
    for section in doc["sections"]:
        parts.append("#" * section["level"] + " " + section["title"])
        parts.extend(render_blocks(section["blocks"]))
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
        print("OK: all 4 Group D docs and their JSON mirrors are current.")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
