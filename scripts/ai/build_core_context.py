#!/usr/bin/env python3
"""Assemble the prompt-cacheable core-context bundle for AI authoring.

Concatenates, in a STABLE order (so it forms a cacheable prompt prefix):
  1. the authoring contract (content/authoring-for-ai.json, rendered back to its original text --
     never read from docs/ai/AUTHORING_FOR_AI.md, which is GENERATED from content/authoring-for-ai.yml
     by scripts/docs/generate_group_e_docs.py -- md-zero-2026-08-11 PLAN.md Phase 4),
  2. the authoring object schemas (schemas/ai/<curated>.schema.json),
  3. golden, verified example models (a few official samples).

The volatile per-app request is meant to go AFTER this bundle at prompt-assembly time, behind the
cache breakpoint. Output -> <Build>/npdev-ai/core-context/ (bundle.md + manifest.json with a
content hash so callers can tell when the cached prefix changed).

WHY JSON, NOT THE AUTHORED YAML: PyYAML is a repo-dev/CI-only dependency
(scripts/requirements.txt's own comment: "NOT a dependency of the shipped CLI itself"), and this
script can run on a real end-user machine same as scripts/ai/build_rag_index.py (found live running
the first-run harness against Phase 5's own work, when build_rag_index.py's `import yaml` broke
`npdev setup` on a fresh install -- same fix applied here pre-emptively). content/authoring-for-ai.json
carries the identical data, stdlib-readable.

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
    """No `if path.exists() else ""`: a missing file used to silently empty this section of the
    cacheable prompt prefix with the build still succeeding. That is a build failure now."""
    if not path.exists():
        raise FileNotFoundError(f"{path} does not exist -- the core-context bundle would silently "
                                 f"lose this section")
    return path.read_text(encoding="utf-8")


def render_authoring_contract(content_path: Path) -> str:
    """Reconstructs content/authoring-for-ai.json (the JSON mirror of content/authoring-for-ai.yml)
    back into its original markdown text -- the exact inverse used by
    scripts/docs/generate_group_e_docs.py's own render(), duplicated here (5 lines) rather than
    cross-imported from scripts/docs/, since the two directories are siblings with no shared
    package. Keep both in sync if the split-doc shape ever changes."""
    doc = json.loads(content_path.read_text(encoding="utf-8"))
    parts = doc["preamble"].split("\n")
    for section in doc["sections"]:
        parts.append("#" * section["level"] + " " + section["title"])
        parts.extend(section["body"].split("\n"))
    return "\n".join(parts)


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

    authoring_content = root / "content" / "authoring-for-ai.json"
    add("Authoring contract", authoring_content, render_authoring_contract(authoring_content))

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
