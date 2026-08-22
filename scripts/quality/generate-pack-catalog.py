#!/usr/bin/env python3
"""Generate a static pack catalog JSON from the built-in packs.

WHY THIS EXISTS
---------------
PACK-8 Step 7: a static JSON index of all built-in packs so that `npdev pack search`
can discover packs without network I/O. Scans NPDevContract/packs/, reads each pack.json,
extracts metadata, computes a content digest (sha256 of all files in the pack directory,
sorted by relative path), and outputs a catalog JSON validated against the schema.

USAGE
    python scripts/quality/generate-pack-catalog.py
    python scripts/quality/generate-pack-catalog.py --out path/to/catalog.json

Exit 0 = success. Exit 1 = validation or I/O error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def resolve_repo_root() -> Path:
    """Find the repo root by its content signature (same approach as WorkspaceRootLocator)."""
    candidate = Path(__file__).resolve().parent.parent
    while candidate != candidate.parent:
        if (candidate / "NPDevContract").is_dir() and (candidate / "NPDevGenerator").is_dir():
            return candidate
        candidate = candidate.parent
    raise SystemExit("error: could not locate repo root (NPDevContract/ + NPDevGenerator/)")


def compute_pack_digest(pack_dir: Path) -> str:
    """sha256 of all regular files in pack_dir, sorted by relative path, .git excluded."""
    h = hashlib.sha256()
    files = sorted(
        f for f in pack_dir.rglob("*")
        if f.is_file() and ".git" not in f.relative_to(pack_dir).parts
    )
    for f in files:
        rel = f.relative_to(pack_dir).as_posix()
        h.update(rel.encode("utf-8"))
        h.update(f.read_bytes())
    return f"sha256:{h.hexdigest()}"


def extract_concepts(pack_data: dict) -> list[str]:
    """Extract concept names from a pack.json."""
    concepts = pack_data.get("concepts", [])
    return [c["name"] for c in concepts if isinstance(c, dict) and "name" in c]


def scan_pack(pack_dir: Path) -> dict:
    """Read a single pack directory and produce a catalog entry."""
    pack_json_path = pack_dir / "pack.json"
    if not pack_json_path.is_file():
        return None
    pack_data = json.loads(pack_json_path.read_text(encoding="utf-8"))
    return {
        "packId": pack_data.get("pack", pack_dir.name),
        "version": pack_data.get("version", "0.0.0"),
        "description": pack_data.get("description", ""),
        "author": pack_data.get("author", ""),
        "category": pack_data.get("category", ""),
        "concepts": extract_concepts(pack_data),
        "digest": compute_pack_digest(pack_dir),
    }


def validate_catalog(catalog: dict, schema_path: Path) -> None:
    """Validate catalog against the schema using json-schema-validator if available, else basic checks."""
    try:
        from jsonschema import validate, ValidationError
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        validate(instance=catalog, schema=schema)
    except ImportError:
        # Fallback: basic structural validation
        assert catalog.get("schemaVersion") == "pack-catalog.v1", "schemaVersion mismatch"
        assert isinstance(catalog.get("packs"), list), "packs must be an array"
        for entry in catalog["packs"]:
            assert "packId" in entry, "packId required"
            assert "version" in entry, "version required"
            assert "description" in entry, "description required"
    except Exception as exc:
        raise SystemExit(f"error: catalog validation failed: {exc}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate pack-catalog.json from built-in packs.")
    parser.add_argument(
        "--out",
        default=None,
        help="Output path (default: scripts/reports/out/pack-catalog.json)",
    )
    args = parser.parse_args()

    repo_root = resolve_repo_root()
    packs_dir = repo_root / "NPDevContract" / "packs"

    if not packs_dir.is_dir():
        print(f"error: packs directory not found: {packs_dir}", file=sys.stderr)
        return 1

    entries = []
    for child in sorted(packs_dir.iterdir()):
        if child.is_dir():
            entry = scan_pack(child)
            if entry is not None:
                entries.append(entry)

    catalog = {
        "schemaVersion": "pack-catalog.v1",
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packs": entries,
    }

    # Validate against schema
    schema_path = repo_root / "schemas" / "ai" / "pack-catalog.schema.json"
    if schema_path.is_file():
        validate_catalog(catalog, schema_path)

    # Write output
    if args.out:
        out_path = Path(args.out)
    else:
        out_path = repo_root / "scripts" / "reports" / "out" / "pack-catalog.json"

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(catalog, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"Generated pack catalog with {len(entries)} packs: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
