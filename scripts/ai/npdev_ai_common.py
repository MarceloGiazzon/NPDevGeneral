"""Shared paths for the NPDev AI-authoring tooling (Phases 2-4).

Source scripts live in the repo; their generated OUTPUTS go to the external Build root
(NPDEV_BUILD_ROOT, else <repo-parent>/Build) -- never inside the source repo -- matching the
build-output policy the Gradle build already follows.
"""

from __future__ import annotations

import os
from pathlib import Path


def repo_root() -> Path:
    env_root = os.environ.get("NPDEV_ROOT")
    if env_root:
        return Path(env_root).expanduser().resolve()
    # scripts/ai/ -> scripts/ -> repo root
    return Path(__file__).resolve().parents[2]


def build_root() -> Path:
    """External build root, mirroring dsl/build.gradle's resolveNpdevBuildRoot."""
    env_root = os.environ.get("NPDEV_BUILD_ROOT")
    if env_root and env_root.strip():
        return Path(env_root).expanduser().resolve()
    source_root = repo_root()
    # npdev-build-root-resolution: walk up to the repo root -- identified by its CONTENTS, not its
    # name -- then use its sibling Build. See scripts/npdev-common.ps1's Get-NPDevBuildRoot comment
    # for the CI failure the name match caused.
    cursor = source_root
    while cursor is not None and not all(
            (cursor / name).is_dir() for name in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
        cursor = cursor.parent if cursor.parent != cursor else None
    if cursor is not None and cursor.parent is not None:
        return cursor.parent / "Build"
    return source_root.parent / "Build"


def ai_out_dir(*parts: str) -> Path:
    """A subdirectory under <Build>/npdev-ai for generated AI-authoring artifacts."""
    out = build_root() / "npdev-ai"
    for part in parts:
        out = out / part
    out.mkdir(parents=True, exist_ok=True)
    return out
