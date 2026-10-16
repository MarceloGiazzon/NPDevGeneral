#!/usr/bin/env python3
"""Build the RAG example index over NPDev docs + verified sample models.

Chunks by OBJECT / SECTION (not arbitrary token windows), so retrieval returns a whole concept,
flow, or doc section:
  - prose docs     -> one chunk per `##`/`###` heading section, read from docs/content/*.json (never a
    .md -- md-zero-2026-08-11 PLAN.md Phase 4; the three docs this used to read are GENERATED from
    docs/content/*.yml by scripts/docs/generate_group_e_docs.py, which also mirrors it as JSON),
  - sample models  -> one chunk per concept / flow / panel / procedure (with the JSON snippet),
    tagged with keywords pulled from field types, reference targets, and onDelete so queries like
    "cascade delete bond" match.

Output -> <Build>/npdev-ai/rag-index.json, which the `npdev_search_examples` MCP tool ranks.
Retrieval here is keyword/BM25-style and dependency-free; swap in embeddings later by adding a
"vector" per chunk without changing the consumer.

WHY JSON, NOT THE AUTHORED YAML: `./npdev setup` calls this script on every real machine, not just
in CI -- and PyYAML is explicitly a repo-dev/CI-only dependency (scripts/requirements.txt's own
comment: "NOT a dependency of the shipped CLI itself"). Found live running the first-run harness
(a bare machine, nothing installed beyond what README documents): `import yaml` here broke
`npdev setup` on a fresh install. docs/content/*.json carries the identical data, stdlib-readable.

Usage: python scripts/ai/build_rag_index.py
"""

from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path
from typing import Any

from npdev_ai_common import ai_out_dir, cards_dir, repo_root

CONTENT_FILES = [
    "docs/content/npdev-concepts-deep-dive.json",
    "docs/content/npdev-user-manual.json",
    "docs/content/authoring-for-ai.json",
]

SAMPLE_OBJECT_KEYS = ["concepts", "flows", "panels", "procedures", "orchestrations", "events"]


_FENCE = re.compile(r"^[ \t]*```[ \t]*(json|jsonc)[ \t]*\r?\n(.*?)^[ \t]*```[ \t]*$",
                    re.DOTALL | re.MULTILINE | re.IGNORECASE)
_JSON_COMMENTS = re.compile(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', re.DOTALL)
# Keys that make a bare `{"type": ...}` object a flow STEP rather than some other typed object
# (a trigger, a field, an orchestration action all carry `type` too and must not be rewritten).
_STEP_MARKER_KEYS = {"name", "scope", "invariants", "cap", "op", "out", "capability", "operation",
                     "args", "input", "output", "awaitEvent", "condition", "then", "else", "from",
                     "value", "awaitRef", "match"}
_MODEL_KEYS = ("concepts", "flows", "orchestrations", "orchestrationRules", "panels")

_migration_module: Any = None


def _migration() -> Any:
    """NPDevCli/dsl_v2_migration.py -- the codemod that already knows every retired alias."""
    global _migration_module
    if _migration_module is None:
        cli_dir = str(repo_root() / "NPDevCli")
        if cli_dir not in sys.path:
            sys.path.insert(0, cli_dir)
        import dsl_v2_migration  # noqa: PLC0415 -- path set up just above
        _migration_module = dsl_v2_migration
    return _migration_module


def _strip_json_comments(text: str) -> str:
    return _JSON_COMMENTS.sub(lambda m: m.group(0) if m.group(0).startswith('"') else "", text)


def _stale_changes(value: Any) -> list[str]:
    """What the DSL 2.0 codemod would change in one parsed example (empty = already canonical)."""
    migration = _migration()
    if isinstance(value, list):
        out: list[str] = []
        for item in value:
            out.extend(_stale_changes(item))
        return out
    if not isinstance(value, dict):
        return []
    copy_ = copy.deepcopy(value)  # the codemod mutates its argument
    result = migration.MigrationResult()
    if any(key in copy_ for key in _MODEL_KEYS):
        result = migration.migrate_document(copy_)
    elif "trigger" in copy_:  # one orchestration rule
        result = migration.migrate_document({"orchestrationRules": [copy_]})
    elif isinstance(copy_.get("steps"), list) and "name" in copy_:  # one flow
        result = migration.migrate_document({"flows": [copy_]})
    elif isinstance(copy_.get("type"), str) and _STEP_MARKER_KEYS & copy_.keys():  # one flow step
        migration._migrate_step(copy_, "example", result)
    return list(result.changes) if result.changed else []


def _stale_dsl_examples(body: str) -> list[str]:
    """Every retired-DSL change the codemod would make to a ```json / ```jsonc example in `body`.

    The RAG index is what AI authors copy from; an example the schema rejects costs them repair
    iterations. Blocks that do not parse (placeholders, ellipses) are skipped, not guessed at.
    """
    changes: list[str] = []
    for match in _FENCE.finditer(body):
        text = match.group(2)
        if match.group(1).lower() == "jsonc":
            text = _strip_json_comments(text)
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            continue
        changes.extend(_stale_changes(parsed))
    return changes


def chunk_content_doc(content_rel: str, doc: dict[str, Any],
                      refused: list[str] | None = None) -> list[dict[str, Any]]:
    """One chunk per section already split out in docs/content/*.yml (read here via its docs/content/*.json
    mirror) -- pre-chunked by `##`/`###` heading, so this just maps section -> chunk instead of
    re-parsing markdown. A section whose JSON example still uses retired DSL is REFUSED (not
    indexed) and appended to `refused` so the caller can fail the build."""
    chunks: list[dict[str, Any]] = []
    source = doc.get("sourceFile", content_rel)
    for section in doc.get("sections", []):
        title = section["title"]
        body = section["body"].strip()
        if not body:
            continue
        stale = _stale_dsl_examples(body)
        if stale:
            message = f"{source}#{title}: {stale[0]}"
            print(f"STALE DSL example refused: {message}", file=sys.stderr)
            if refused is not None:
                refused.append(message)
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

    Cards live in NPDevMcp/knowledge/cards/*.json; superseded ones are skipped. The card body is already
    capped at 4000 chars (knowledge-card.schema.json) to index whole, matching the doc/sample cap.
    """
    directory = cards_dir(root)
    chunks: list[dict[str, Any]] = []
    if not directory.exists():
        return chunks
    for path in sorted(directory.glob("*.json")):
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
    refused: list[str] = []

    # No `if path.exists() else skip`: a missing content file used to silently empty this section
    # of the AI context with the gate still green. A moved/renamed/deleted content file is a build
    # failure now, not a quiet gap.
    for rel in CONTENT_FILES:
        path = root / rel
        if not path.exists():
            print(f"FAIL: {rel} does not exist -- the AI context would silently lose this "
                  f"section's coverage.", file=sys.stderr)
            return 1
        doc = json.loads(path.read_text(encoding="utf-8"))
        chunks.extend(chunk_content_doc(rel, doc, refused))

    chunks.extend(chunk_cards(root))
    if not any(c.get("objectType") == "knowledge-card" for c in chunks):
        print(f"FAIL: 0 knowledge-card chunks from {cards_dir(root)} -- the AI context would "
              f"silently lose every curated card.", file=sys.stderr)
        return 1

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
        "refusedStaleSections": len(refused),
        "chunks": chunks,
    }
    out_path = ai_out_dir() / "rag-index.json"
    out_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(
        {
            "output": str(out_path),
            "chunkCount": len(chunks),
            "byType": _count_by_type(chunks),
            "refusedStaleSections": len(refused),
        },
        indent=2,
    ))
    return 1 if refused else 0


def _count_by_type(chunks: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for chunk in chunks:
        counts[chunk["objectType"]] = counts.get(chunk["objectType"], 0) + 1
    return counts


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
