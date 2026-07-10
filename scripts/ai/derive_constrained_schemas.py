#!/usr/bin/env python3
"""Derive structured-output-safe subsets of the NPDev authoring schemas.

The `schemas/ai/*.json` schemas are authoring-strict (draft 2020-12: `pattern`, `minLength`,
`if/then`, `oneOf`, `not`, ...). Claude's structured outputs (`output_config.format`) supports
only a subset -- object/array/scalar types, `enum`, `const`, `anyOf`, `allOf`, `$ref`, and
`additionalProperties: false`; it does NOT support string/numeric/array constraints, `if/then`,
`not`, or recursive schemas. This tool mechanically derives a constrained subset so a schema can
be fed as a generation constraint that makes structurally-illegal JSON impossible to emit.

Shape is constrained here; whole-document + cross-reference semantics stay with `npdev validate
model --semantic`. Outputs go to <Build>/npdev-ai/constrained/ (never committed to source).

Usage:
    python scripts/ai/derive_constrained_schemas.py [name ...]
    # default set: ai-model custom-panel custom-procedure
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from typing import Any

from npdev_ai_common import ai_out_dir, repo_root

# Keywords structured outputs does not support -> stripped from the derived schema.
UNSUPPORTED_KEYWORDS = {
    "minLength", "maxLength", "pattern", "format",
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    "minItems", "maxItems", "uniqueItems", "contains", "minContains", "maxContains",
    "minProperties", "maxProperties", "patternProperties", "propertyNames",
    "if", "then", "else", "not", "dependentSchemas", "dependentRequired",
    "default", "examples", "deprecated", "readOnly", "writeOnly",
    "contentEncoding", "contentMediaType",
}

DEFAULT_SCHEMAS = ["ai-model", "custom-panel", "custom-procedure"]


def transform(node: Any) -> Any:
    """Recursively rewrite a schema node into a structured-outputs-safe form."""
    if isinstance(node, list):
        return [transform(item) for item in node]
    if not isinstance(node, dict):
        return node

    out: dict[str, Any] = {}
    for key, value in node.items():
        if key in UNSUPPORTED_KEYWORDS:
            continue
        if key == "oneOf":
            # oneOf is not in the supported set; anyOf is the safe equivalent for generation.
            out["anyOf"] = [transform(item) for item in value]
            continue
        out[key] = transform(value)

    # Prune combinators that became empty/degenerate after stripping if/then/not.
    for combinator in ("allOf", "anyOf"):
        if combinator in out:
            kept = [entry for entry in out[combinator] if entry not in ({}, None)]
            if kept:
                out[combinator] = kept
            else:
                del out[combinator]

    # Structured outputs requires additionalProperties:false on every object with properties.
    if out.get("type") == "object" and "properties" in out:
        out["additionalProperties"] = False

    return out


def build_ref_graph(defs: dict[str, Any]) -> dict[str, set[str]]:
    graph: dict[str, set[str]] = {}
    for name, schema in defs.items():
        graph[name] = collect_local_refs(schema)
    return graph


def collect_local_refs(node: Any, acc: set[str] | None = None) -> set[str]:
    acc = acc if acc is not None else set()
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/$defs/"):
            acc.add(ref[len("#/$defs/"):])
        for value in node.values():
            collect_local_refs(value, acc)
    elif isinstance(node, list):
        for item in node:
            collect_local_refs(item, acc)
    return acc


def has_cycle(graph: dict[str, set[str]]) -> bool:
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {name: WHITE for name in graph}

    def visit(name: str) -> bool:
        color[name] = GRAY
        for neighbour in graph.get(name, ()):  # neighbour may be absent (inline ref)
            state = color.get(neighbour, BLACK)
            if state == GRAY:
                return True
            if state == WHITE and visit(neighbour):
                return True
        color[name] = BLACK
        return False

    return any(color[name] == WHITE and visit(name) for name in graph)


def find_remaining_unsupported(node: Any, found: set[str] | None = None) -> set[str]:
    found = found if found is not None else set()
    if isinstance(node, dict):
        for key, value in node.items():
            if key in UNSUPPORTED_KEYWORDS or key == "oneOf":
                found.add(key)
            find_remaining_unsupported(value, found)
    elif isinstance(node, list):
        for item in node:
            find_remaining_unsupported(item, found)
    return found


def derive_one(name: str) -> dict[str, Any]:
    source_path = repo_root() / "schemas" / "ai" / f"{name}.schema.json"
    if not source_path.exists():
        return {"name": name, "status": "missing", "detail": f"not found: {source_path}"}

    original = json.loads(source_path.read_text(encoding="utf-8"))
    derived = transform(copy.deepcopy(original))
    derived["$comment"] = (
        "Structured-outputs-safe subset derived from " + source_path.name
        + " by scripts/ai/derive_constrained_schemas.py. Constrains shape only; run "
        + "`npdev validate model --semantic` for cross-reference semantics."
    )

    defs = original.get("$defs") or {}
    recursive = has_cycle(build_ref_graph(defs)) if defs else False
    leftover = find_remaining_unsupported(derived)

    out_dir = ai_out_dir("constrained")
    out_path = out_dir / f"{name}.constrained.schema.json"
    out_path.write_text(json.dumps(derived, indent=2) + "\n", encoding="utf-8")

    status = "ok"
    if recursive:
        status = "recursive"  # safe for leaf/object-level use; not a whole-document constraint
    elif leftover:
        status = "residual-unsupported"
    return {
        "name": name,
        "status": status,
        "recursive": recursive,
        "residualUnsupported": sorted(leftover),
        "output": str(out_path),
    }


def main(argv: list[str]) -> int:
    names = argv[1:] or DEFAULT_SCHEMAS
    results = [derive_one(name) for name in names]
    manifest = {
        "generatedFrom": "scripts/ai/derive_constrained_schemas.py",
        "note": "Constrains JSON shape for output_config.format; semantics via `npdev validate model --semantic`.",
        "schemas": results,
    }
    manifest_path = ai_out_dir("constrained") / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    # Non-zero only when a requested schema was missing -- recursive/residual are informational.
    return 1 if any(r["status"] == "missing" for r in results) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
