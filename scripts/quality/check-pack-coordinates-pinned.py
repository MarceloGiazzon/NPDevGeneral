#!/usr/bin/env python3
"""Pack-coordinate pinning gate: refuse mutable tags in `from` fields.

WHY THIS EXISTS
---------------
PACK-8 added `from` to both model.schema.json's packRef (app-level packs[]) and
pack.schema.json's packs[] items (transitive dependencies). The coordinate grammar accepts
both pinned references (oci://...@sha256:<digest>, git+...@<semver-tag>) and mutable ones
(git+...@main, oci://...:latest). A mutable tag is a moving target: the same coordinate
resolves to different content over time, which breaks the content-addressed cache's whole
promise (fetch once, read from cache forever, digest-verified).

This gate scans every model.json and pack.json in the repository and refuses any `from`
field whose reference is a known mutable tag name (latest, main, master, head, develop, dev)
rather than a semver tag or a pinned digest.

USAGE
    python scripts/quality/check-pack-coordinates-pinned.py
    python scripts/quality/check-pack-coordinates-pinned.py --json

Exit 0 = every `from` coordinate is pinned. Exit 1 = at least one mutable tag found. Exit 2 = usage.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Mutable tag names that are NEVER acceptable in a `from` coordinate.
# These are branch names or floating labels that resolve to different content over time.
MUTABLE_TAGS = frozenset({"latest", "main", "master", "head", "develop", "dev", "stable", "nightly"})

# Pattern for a semver tag: starts with optional 'v', then major.minor.patch with optional pre-release.
SEMVER_TAG_RE = re.compile(r"^v?\d+\.\d+\.\d+([.-][A-Za-z0-9.]+)?$")

# Pattern for a pinned digest reference in an OCI coordinate.
OCI_DIGEST_RE = re.compile(r"@sha256:[0-9a-f]{64}$")

# Pattern to extract the reference part from a `from` coordinate.
# OCI: oci://registry/repo:TAG or oci://registry/repo@sha256:DIGEST
# Git: git+transport://url@TAG
OCI_REF_RE = re.compile(r"^oci://[^@:]+(?::([^@]+)|@(sha256:[0-9a-f]{64}))$")
GIT_REF_RE = re.compile(r"^git\+(?:https|http|ssh|file|git)://.+@(.+)$")


def _repo_root(explicit: str | None) -> Path:
    """Identify the repo by its CONTENTS, never by its directory name."""
    if explicit:
        return Path(explicit).resolve()
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if all((candidate / m).is_dir()
               for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit(
        "could not identify the repo root by contents (a directory holding NPDevContract + "
        "NPDevGenerator + NPDevKernel). Pass --repo."
    )


def extract_from_coordinates(root: Path) -> list[dict]:
    """Walk the repo finding every `from` field in model.json and pack.json files.

    Returns a list of dicts with keys: file, path, coordinate.
    """
    findings = []
    # Scan model.json files (app-level packs[].from)
    for model_file in root.rglob("model.json"):
        # Skip anything under build/, .git/, node_modules/
        rel = model_file.relative_to(root)
        if any(part.startswith((".", "build", "node_modules")) for part in rel.parts):
            continue
        try:
            data = json.loads(model_file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if not isinstance(data, dict):
            continue
        packs = data.get("packs")
        if not isinstance(packs, list):
            continue
        for i, entry in enumerate(packs):
            if isinstance(entry, dict) and isinstance(entry.get("from"), str) and entry["from"].strip():
                findings.append({
                    "file": str(rel).replace("\\", "/"),
                    "path": f"/packs/{i}/from",
                    "coordinate": entry["from"].strip(),
                })

    # Scan pack.json files (transitive packs[].from)
    for pack_file in root.rglob("pack.json"):
        rel = pack_file.relative_to(root)
        if any(part.startswith((".", "build", "node_modules")) for part in rel.parts):
            continue
        try:
            data = json.loads(pack_file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if not isinstance(data, dict) or "pack" not in data:
            continue  # not a real pack.json
        packs = data.get("packs")
        if not isinstance(packs, list):
            continue
        for i, entry in enumerate(packs):
            if isinstance(entry, dict) and isinstance(entry.get("from"), str) and entry["from"].strip():
                findings.append({
                    "file": str(rel).replace("\\", "/"),
                    "path": f"/packs/{i}/from",
                    "coordinate": entry["from"].strip(),
                })

    return findings


def classify_coordinate(coord: str) -> str:
    """Classify a `from` coordinate as 'pinned-digest', 'semver-tag', or 'mutable-tag'."""
    # Try OCI
    m = OCI_REF_RE.match(coord)
    if m:
        if m.group(2):  # sha256 digest
            return "pinned-digest"
        tag = m.group(1)
        if tag and tag.lower() in MUTABLE_TAGS:
            return "mutable-tag"
        if tag and SEMVER_TAG_RE.match(tag):
            return "semver-tag"
        # Unknown OCI tag format -- not a known mutable, not a semver, not a digest
        return "unknown-tag"

    # Try Git
    m = GIT_REF_RE.match(coord)
    if m:
        tag = m.group(1)
        if tag.lower() in MUTABLE_TAGS:
            return "mutable-tag"
        if SEMVER_TAG_RE.match(tag):
            return "semver-tag"
        return "unknown-tag"

    return "unparseable"


def check(root: Path) -> list[dict]:
    problems: list[dict] = []
    coordinates = extract_from_coordinates(root)

    for entry in coordinates:
        classification = classify_coordinate(entry["coordinate"])
        if classification == "mutable-tag":
            problems.append({
                "kind": "mutable-tag",
                "file": entry["file"],
                "path": entry["path"],
                "coordinate": entry["coordinate"],
                "message": (
                    f"'{entry['file']}' {entry['path']}: coordinate '{entry['coordinate']}' "
                    f"uses a MUTABLE tag. A `from` coordinate must be pinned to a digest "
                    f"(@sha256:<64-hex>) or at minimum a semver tag (e.g. v2.1.0), never a "
                    f"moving branch name (latest, main, master, etc.). Mutable tags break the "
                    f"content-addressed cache's digest verification."
                ),
            })

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default=None, help="Repo root (default: found by contents).")
    parser.add_argument("--json", action="store_true", help="Emit findings as JSON.")
    args = parser.parse_args(argv)

    root = _repo_root(args.repo)
    problems = check(root)

    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-pack-coordinate-pinning-report.v1",
            "ok": not problems,
            "problems": problems,
        }, indent=2))
        return 1 if problems else 0

    if not problems:
        print("check-pack-coordinates-pinned: OK -- every `from` coordinate in the repo is "
              "pinned (digest or semver tag), no mutable tags found.")
        return 0

    print(f"check-pack-coordinates-pinned: {len(problems)} problem(s)")
    for problem in problems:
        print(f"\n  [{problem['kind']}] {problem['file']} {problem['path']}")
        for line in problem["message"].splitlines():
            print(f"    {line}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
