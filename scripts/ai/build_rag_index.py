#!/usr/bin/env python3
"""Build the RAG example index over NPDev docs + verified sample models.

Chunks by OBJECT / SECTION (not arbitrary token windows), so retrieval returns a whole concept,
flow, or doc section:
  - prose docs     -> one chunk per `##`/`###` heading section, read from content/*.yml (never a
    .md -- md-zero-2026-08-11 PLAN.md Phase 4; the three docs this used to read are GENERATED from
    the same YAML by scripts/docs/generate_group_e_docs.py),
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

import yaml

from npdev_ai_common import ai_out_dir, repo_root

CONTENT_FILES = [
    "content/npdev-concepts-deep-dive.yml",
    "content/npdev-user-manual.yml",
    "content/authoring-for-ai.yml",
]

SAMPLE_OBJECT_KEYS = ["concepts", "flows", "panels", "procedures", "orchestrations", "events"]


def chunk_content_yaml(content_rel: str, doc: dict[str, Any]) -> list[dict[str, Any]]:
    """One chunk per section already split out in content/*.yml -- the YAML IS pre-chunked by
    `##`/`###` heading, so this just maps section -> chunk instead of re-parsing markdown."""
    chunks: list[dict[str, Any]] = []
    source = doc.get("sourceFile", content_rel)
    for section in doc.get("sections", []):
        title = section["title"]
        body = section["body"].strip()
        if not body:
            continue
        chunks.append({
            "id": f"{source}#{title}",
            "title": title,
            "objectType": "doc",
            "source": source,
            "keywords": _keywords(title),
            "text": body[:4000],
        })
    return chunks


def chunk_cards(root: Path) -> list[dict[str, Any]]:
    """One chunk per active knowledge card (idea 1: merge maintainer findings into retrieval).

    Cards live in knowledge/cards/*.json; superseded ones are skipped. The card body is already
    capped at 4000 chars (knowledge-card.schema.json) to index whole, matching the doc/sample cap.
    """
    cards_dir = root / "knowledge" / "cards"
    chunks: list[dict[str, Any]] = []
    if not cards_dir.exists():
        return chunks
    for path in sorted(cards_dir.glob("*.json")):
        try:
            card = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if not isinstance(card, dict) or card.get("status", "active") != "active":
            continue
        rel = path.relative_to(root).as_posix()
        keywords = list(card.get("keywords") or []) + list(card.get("appliesTo") or [])
        chunks.append({
            "id": f"{rel}#card:{card.get('id')}",
            "title": card.get("title") or card.get("id"),
            "objectType": "knowledge-card",
            "source": rel,
            "keywords": _keywords(card.get("title") or "") + [str(k).lower() for k in keywords],
            "text": str(card.get("body") or "")[:4000],
        })
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

    # No `if path.exists() else skip`: a missing content file used to silently empty this section
    # of the AI context with the gate still green. A moved/renamed/deleted content file is a build
    # failure now, not a quiet gap.
    for rel in CONTENT_FILES:
        path = root / rel
        if not path.exists():
            print(f"FAIL: {rel} does not exist -- the AI context would silently lose this "
                  f"section's coverage.", file=sys.stderr)
            return 1
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        chunks.extend(chunk_content_yaml(rel, doc))

    chunks.extend(chunk_cards(root))

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
