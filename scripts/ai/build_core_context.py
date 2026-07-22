#!/usr/bin/env python3
"""Assemble the prompt-cacheable core-context bundle for AI authoring.

Concatenates, in a STABLE order (so it forms a cacheable prompt prefix):
  1. the authoring contract (docs/ai/AUTHORING_FOR_AI.md),
  2. the authoring object schemas (schemas/ai/<curated>.schema.json),
  3. golden, verified example models (a few official samples).

The volatile per-app request is meant to go AFTER this bundle at prompt-assembly time, behind the
cache breakpoint. Output -> <Build>/npdev-ai/core-context/ (bundle.md + manifest.json with a
content hash so callers can tell when the cached prefix changed).

Usage: python scripts/ai/build_core_context.py
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

from npdev_ai_common import ai_out_dir, repo_root

# Curated, deterministic order. Authoring object schemas the AI actually emits.
SCHEMAS = ["ai-model", "custom-panel", "custom-procedure"]

# Verified sample models used as golden examples (official samples; known to build + run).
GOLDEN_SAMPLES = [
    "simple-user-registry",
    "simple-contact-intake",
    "medium-expense-approval",
]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def build_bundle() -> tuple[str, dict]:
    root = repo_root()
    sections: list[str] = []
    manifest_sources: list[dict] = []

    def add(title: str, source: Path, body: str, fence: str = "") -> None:
        rel = str(source.relative_to(root)) if source.is_relative_to(root) else str(source)
        if fence:
            sections.append(f"## {title}\n\nSource: `{rel}`\n\n```{fence}\n{body.rstrip()}\n```\n")
        else:
            sections.append(f"## {title}\n\nSource: `{rel}`\n\n{body.rstrip()}\n")
        manifest_sources.append({"title": title, "source": rel, "bytes": len(body.encode("utf-8"))})

    sections.append(
        "# NPDev core authoring context\n\n"
        "Stable, cacheable prefix for AI authoring of NPDev `model.json`. Keep this first in the "
        "prompt (behind a cache breakpoint); put the specific app request AFTER it.\n"
    )

    authoring = root / "docs" / "ai" / "AUTHORING_FOR_AI.md"
    add("Authoring contract", authoring, read_text(authoring))

    for name in SCHEMAS:
        schema_path = root / "schemas" / "ai" / f"{name}.schema.json"
        if schema_path.exists():
            add(f"Schema: {name}", schema_path, read_text(schema_path), fence="json")

    for sample in GOLDEN_SAMPLES:
        model_path = root / "NPDevSamples" / sample / "Input" / "model.json"
        if model_path.exists():
            add(f"Golden example: {sample}", model_path, read_text(model_path), fence="json")

    bundle = "\n".join(sections)
    digest = hashlib.sha256(bundle.encode("utf-8")).hexdigest()
    manifest = {
        "generatedFrom": "scripts/ai/build_core_context.py",
        "contentSha256": digest,
        "bytes": len(bundle.encode("utf-8")),
        "sections": manifest_sources,
        "note": "Prompt-cacheable prefix. Place before the per-app request, behind a cache_control breakpoint.",
    }
    return bundle, manifest


def main(_argv: list[str]) -> int:
    bundle, manifest = build_bundle()
    out_dir = ai_out_dir("core-context")
    (out_dir / "bundle.md").write_text(bundle, encoding="utf-8")
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(
        {"output": str(out_dir / "bundle.md"), "bytes": manifest["bytes"],
         "contentSha256": manifest["contentSha256"],
         "sections": [s["title"] for s in manifest["sections"]]},
        indent=2,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
