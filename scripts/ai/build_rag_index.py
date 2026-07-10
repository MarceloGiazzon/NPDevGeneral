#!/usr/bin/env python3
"""Build the RAG example index over NPDev docs + verified sample models.

Chunks by OBJECT / SECTION (not arbitrary token windows), so retrieval returns a whole concept,
flow, or doc section:
  - markdown docs  -> one chunk per `##`/`###` heading section,
  - sample models  -> one chunk per concept / flow / panel / procedure (with the JSON snippet),
    tagged with keywords pulled from field types, reference targets, and onDelete so queries like
    "cascade delete bond" match.

Output -> <Build>/npdev-ai/rag-index.json, which the `npdev_search_examples` MCP tool ranks.
Retrieval here is keyword/BM25-style and dependency-free; swap in embeddings later by adding a
"vector" per chunk without changing the consumer.

Usage: python scripts/ai/build_rag_index.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

from npdev_ai_common import ai_out_dir, repo_root

DOC_FILES = [
    "docs/NPDEV_CONCEPTS_DEEP_DIVE.md",
    "docs/NPDEV_USER_MANUAL.md",
    "docs/ai/AUTHORING_FOR_AI.md",
]

SAMPLE_OBJECT_KEYS = ["concepts", "flows", "panels", "procedures", "orchestrations", "events"]

HEADING_RE = re.compile(r"^(#{2,3})\s+(.*)$")


def chunk_markdown(rel_path: str, text: str) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = []
    current_title: str | None = None
    current_lines: list[str] = []

    def flush() -> None:
        if current_title is None:
            return
        body = "\n".join(current_lines).strip()
        if not body:
            return
        chunks.append({
            "id": f"{rel_path}#{current_title}",
            "title": current_title,
            "objectType": "doc",
            "source": rel_path,
            "keywords": _keywords(current_title),
            "text": body[:4000],
        })

    for line in text.splitlines():
        match = HEADING_RE.match(line)
        if match:
            flush()
            current_title = match.group(2).strip()
            current_lines = []
        elif current_title is not None:
            current_lines.append(line)
    flush()
    return chunks


def chunk_sample(rel_path: str, model: dict[str, Any]) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = []
    for key in SAMPLE_OBJECT_KEYS:
        items = model.get(key)
        if not isinstance(items, list):
            continue
        object_type = key[:-1] if key.endswith("s") else key
        for item in items:
            if not isinstance(item, dict) or not item.get("name"):
                continue
            name = item["name"]
            chunks.append({
                "id": f"{rel_path}#{object_type}:{name}",
                "title": f"{object_type} {name}",
                "objectType": object_type,
                "source": rel_path,
                "keywords": _keywords(name, object_type) + _object_keywords(item),
                "text": json.dumps(item, indent=2)[:4000],
            })
    return chunks


def _object_keywords(item: dict[str, Any]) -> list[str]:
    """Surface field types, reference targets, and onDelete so semantic queries match."""
    words: list[str] = []
    for field in item.get("fields") or []:
        if not isinstance(field, dict):
            continue
        for token in (field.get("type"), field.get("name")):
            if token:
                words.append(str(token).lower())
        reference = field.get("reference")
        if isinstance(reference, dict):
            words.append("reference")
            words.append("bond")
            if reference.get("onDelete"):
                words.append(str(reference["onDelete"]).lower())
            if reference.get("target"):
                words.append(str(reference["target"]).lower())
    return words


def _keywords(*parts: str) -> list[str]:
    words: list[str] = []
    for part in parts:
        words.extend(
            token for token in re.split(r"[^a-z0-9]+", part.lower()) if len(token) > 1
        )
    return words


def main(_argv: list[str]) -> int:
    root = repo_root()
    chunks: list[dict[str, Any]] = []

    for rel in DOC_FILES:
        path = root / rel
        if path.exists():
            chunks.extend(chunk_markdown(rel, path.read_text(encoding="utf-8")))

    samples_dir = root / "NPDevSamples"
    if samples_dir.exists():
        for model_path in sorted(samples_dir.glob("*/Input/model.json")):
            try:
                model = json.loads(model_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            if not isinstance(model, dict):
                continue
            rel = str(model_path.relative_to(root)).replace("\\", "/")
            chunks.extend(chunk_sample(rel, model))

    index = {
        "generatedFrom": "scripts/ai/build_rag_index.py",
        "chunkCount": len(chunks),
        "chunks": chunks,
    }
    out_path = ai_out_dir() / "rag-index.json"
    out_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(
        {
            "output": str(out_path),
            "chunkCount": len(chunks),
            "byType": _count_by_type(chunks),
        },
        indent=2,
    ))
    return 0


def _count_by_type(chunks: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for chunk in chunks:
        counts[chunk["objectType"]] = counts.get(chunk["objectType"], 0) + 1
    return counts


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
