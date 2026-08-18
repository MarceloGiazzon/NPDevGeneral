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
import re
from pathlib import Path

import yaml

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent

sys.path.insert(0, str(_REPO_ROOT / "scripts" / "ai"))
from npdev_ai_common import build_root  # noqa: E402

# (yaml source, json mirror, rendered markdown)
TARGETS = [
    ("content/npdev-concepts-deep-dive.yml", "content/npdev-concepts-deep-dive.json", "docs/NPDEV_CONCEPTS_DEEP_DIVE.md"),
    ("content/npdev-user-manual.yml", "content/npdev-user-manual.json", "docs/NPDEV_USER_MANUAL.md"),
    ("content/authoring-for-ai.yml", "content/authoring-for-ai.json", "docs/ai/AUTHORING_FOR_AI.md"),
    ("content/ui-generation-prompt.yml", "content/ui-generation-prompt.json", "docs/ai/UI_GENERATION_PROMPT.md"),
    # DOC-1: the audience-facing feature guide. Same source->json->markdown shape as its four
    # siblings, so the ai-knowledge gate's `git diff --exit-code` catches a hand-edit to the .md
    # for free rather than needing a rule of its own.
    ("content/npdev-feature-guide.yml", "content/npdev-feature-guide.json", "docs/NPDEV_FEATURE_GUIDE.md"),
]

# DOC-1: the ONE target that also gets an HTML rendering, written OUTSIDE the repo. A guide is the
# thing people send each other, and a self-contained page is what survives that; the repo is not
# where build output lives (docs/BUILD_OUTPUT_LOCATION_POLICY.md).
HTML_TARGETS = {
    "content/npdev-feature-guide.yml": "npdev-docs/feature-guide.html",
}


def render(doc: dict) -> str:
    """Exact inverse of the split used to build content/*.yml: preamble, then each section's
    heading line + body, all joined by newline -- see that conversion's own round-trip proof."""
    parts = doc["preamble"].split("\n")
    for section in doc["sections"]:
        parts.append("#" * section["level"] + " " + section["title"])
        parts.extend(section["body"].split("\n"))
    return "\n".join(parts)


def render_html(doc: dict, markdown: str) -> str:
    """A self-contained, theme-aware page carrying the SAME markdown the .md file holds.

    Rendered from the already-rendered markdown rather than from the section tree a second time:
    two renderers over one source is how a page and its document start disagreeing about what they
    say. The markdown is escaped and shown in a readable prose layout with the tables and code
    blocks converted -- deliberately a small converter, not a markdown library, because this repo's
    doc tooling is stdlib-only and a build step for one page is not a trade worth making.
    """
    body = _markdown_to_html(markdown)
    title = doc.get("sourceFile", "NPDev").rsplit("/", 1)[-1].removesuffix(".md").replace("_", " ").title()
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>{_escape(title)}</title>
<style>
  :root {{
    --bg: #f6f8fa; --surface: #ffffff; --border: #d8dee4; --text: #1f2328; --muted: #59636e;
    --accent: #0b5cad; --accent-soft: #ddebf7; --mono-bg: #eef2f6; --mono-border: #d3dce3;
  }}
  @media (prefers-color-scheme: dark) {{
    :root:not([data-theme="light"]) {{
      --bg: #0d1117; --surface: #151b23; --border: #2a3038; --text: #e6edf3; --muted: #9198a1;
      --accent: #7cb8f0; --accent-soft: #17293d; --mono-bg: #1b222b; --mono-border: #2c343d;
    }}
  }}
  :root[data-theme="dark"] {{
    --bg: #0d1117; --surface: #151b23; --border: #2a3038; --text: #e6edf3; --muted: #9198a1;
    --accent: #7cb8f0; --accent-soft: #17293d; --mono-bg: #1b222b; --mono-border: #2c343d;
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; background: var(--bg); color: var(--text);
         font-family: -apple-system, "Segoe UI", ui-sans-serif, system-ui, sans-serif; line-height: 1.65; }}
  .page {{ max-width: 62rem; margin: 0 auto; padding: 3rem 1.5rem 6rem; }}
  h1 {{ font-size: clamp(1.8rem, 4vw, 2.6rem); letter-spacing: -0.02em; margin: 0 0 1rem; text-wrap: balance; }}
  h2 {{ font-size: 1.35rem; margin: 3rem 0 0.75rem; padding-top: 1.25rem; border-top: 1px solid var(--border);
       letter-spacing: -0.01em; }}
  h3 {{ font-size: 1.05rem; margin: 2rem 0 0.5rem; color: var(--muted); }}
  p, li {{ max-width: 72ch; }}
  code {{ background: var(--mono-bg); border: 1px solid var(--mono-border); border-radius: 4px;
         padding: 0.1em 0.4em; font-size: 0.87em;
         font-family: ui-monospace, "Cascadia Code", Consolas, monospace; }}
  pre {{ background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
        padding: 1rem 1.1rem; overflow-x: auto; }}
  pre code {{ background: none; border: none; padding: 0; font-size: 0.85rem; line-height: 1.55; }}
  .table-wrap {{ overflow-x: auto; margin: 1.25rem 0; }}
  table {{ border-collapse: collapse; width: 100%; font-size: 0.92rem; }}
  th, td {{ border: 1px solid var(--border); padding: 0.5rem 0.75rem; text-align: left; vertical-align: top; }}
  th {{ background: var(--accent-soft); color: var(--text); font-weight: 600; }}
  blockquote {{ margin: 1.25rem 0; padding: 0.5rem 1rem; border-left: 3px solid var(--accent);
               background: var(--surface); color: var(--muted); }}
</style>
</head>
<body><div class="page">
{body}
</div></body>
</html>
"""


def _escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def _inline(text: str) -> str:
    """Escape, then re-introduce the three inline forms this corpus actually uses."""
    out = _escape(text)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"<a href=\"\2\">\1</a>", out)
    return out


def _markdown_to_html(markdown: str) -> str:
    lines = markdown.split("\n")
    out: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if line.startswith("```"):
            index += 1
            block = []
            while index < len(lines) and not lines[index].startswith("```"):
                block.append(lines[index])
                index += 1
            index += 1
            out.append("<pre><code>" + _escape("\n".join(block)) + "</code></pre>")
            continue
        if line.startswith("|") and index + 1 < len(lines) and set(lines[index + 1].replace("|", "").strip()) <= set("-: "):
            header = [cell.strip() for cell in line.strip("|").split("|")]
            index += 2
            rows = []
            while index < len(lines) and lines[index].startswith("|"):
                rows.append([cell.strip() for cell in lines[index].strip("|").split("|")])
                index += 1
            head = "".join(f"<th>{_inline(cell)}</th>" for cell in header)
            body = "".join("<tr>" + "".join(f"<td>{_inline(cell)}</td>" for cell in row) + "</tr>"
                           for row in rows)
            out.append(f'<div class="table-wrap"><table><thead><tr>{head}</tr></thead>'
                       f"<tbody>{body}</tbody></table></div>")
            continue
        heading = re.match(r"^(#{1,4})\s+(.*)$", line)
        if heading:
            level = len(heading.group(1))
            out.append(f"<h{level}>{_inline(heading.group(2))}</h{level}>")
            index += 1
            continue
        if line.startswith("- "):
            items = []
            while index < len(lines) and lines[index].startswith("- "):
                items.append(f"<li>{_inline(lines[index][2:])}</li>")
                index += 1
            out.append("<ul>" + "".join(items) + "</ul>")
            continue
        if line.startswith("> "):
            out.append(f"<blockquote>{_inline(line[2:])}</blockquote>")
            index += 1
            continue
        if line.strip():
            paragraph = [line]
            index += 1
            while index < len(lines) and lines[index].strip() and not lines[index].startswith(
                    ("#", "|", "- ", "> ", "```")):
                paragraph.append(lines[index])
                index += 1
            out.append("<p>" + _inline(" ".join(paragraph)) + "</p>")
            continue
        index += 1
    return "\n".join(out)


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

        html_rel = HTML_TARGETS.get(yaml_rel)
        if html_rel:
            # build_root() is CALLED, never its answer repeated as a literal -- REG-144's eleven
            # copies of this resolution produced three different roots in one renamed checkout.
            html_path = build_root() / html_rel
            html_path.parent.mkdir(parents=True, exist_ok=True)
            html_path.write_text(render_html(doc, rendered_md), encoding="utf-8")
            print(f"wrote {html_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
