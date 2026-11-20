#!/usr/bin/env python3
"""Manager version-bump guard: NPDevManager behavior changes must carry a version bump.

WHY THIS EXISTS -- 2026-09-24
------------------------------------------------------------------
Two behavior-changing edits landed in NPDevManager (npdev_cli.py's db export/import precondition-JSON
handling, then CsvRowSerializer's NULL/empty-string fix -- both surfaced through the Manager's DB
Import/Export tab) before the Manager's own version was bumped, in the same session, more than once.
The version chip is the only signal a user has that "what's running" matches "what was just fixed";
a change that ships without a bump is invisible to them and indistinguishable from a fix that never
landed. main.rs's own comment above `manager_version_description` already names the three places that
must move together -- this makes that a mechanical check instead of a comment someone has to reread.

WHAT THIS CHECKS
-----------------
Two independent things, both scoped to NPDevManager/:

1. VERSION BUMPED WHEN BEHAVIOR CHANGED. Find the last commit that changed Cargo.toml's `version`
   line (the last bump). If the working tree differs from THAT commit in any of NPDevManager's
   behavior-affecting paths (src/**/*.rs, ui/**/*.{js,html,css}, build.rs, capabilities/**.json),
   Cargo.toml's version must differ from the bump commit's. Cargo.lock, fixtures/, and icons/ are
   excluded -- they never carry behavior a user would notice.

   WHY AGAINST THE LAST BUMP, NOT HEAD (2026-11-20): the first version compared with `git diff HEAD`
   only, so it saw nothing once a change was committed. Six commits changed the Manager after
   0.13.39 (REG-321 relay_apply_removal, REG-323 F4, ...) and the owner installed a new build that
   still said 0.13.39. Measuring from the last bump catches committed drift too. A shallow clone
   without the bump commit falls back to `git diff HEAD`.

2. THE THREE VERSION LOCATIONS AGREE RIGHT NOW. Cargo.toml's `version`, tauri.conf.json's `version`,
   and the leading "X.Y.Z" in main.rs's `manager_version_description` string must all match the
   CURRENT working tree, independent of any diff -- catches a bump that touched two of the three and
   forgot the third.

USAGE
-----
    python scripts/quality/check-manager-version-bump.py       # exit 1 on a violation, 0 if clean
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MANAGER_DIR = "NPDevManager"

BEHAVIOR_PATH_RE = re.compile(
    r"^NPDevManager/(src/.*\.rs|ui/.*\.(js|html|css)|build\.rs|capabilities/.*\.json)$"
)


def git_lines(*args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def last_bump_commit() -> str | None:
    """The newest commit whose diff touches Cargo.toml's top-level `version = "..."` line."""
    commits = git_lines(
        "log", "-n", "1", "--format=%H", "-G", r'^version = "', "--", f"{MANAGER_DIR}/Cargo.toml",
    )
    return commits[0] if commits else None


def git_diff_paths(base: str) -> list[str]:
    return git_lines("diff", "--name-only", base, "--", MANAGER_DIR)


def git_show(ref: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"], cwd=REPO_ROOT, capture_output=True, text=True,
    )
    return result.stdout if result.returncode == 0 else None


def extract_cargo_version(text: str) -> str | None:
    m = re.search(r'^version\s*=\s*"([^"]+)"', text, re.MULTILINE)
    return m.group(1) if m else None


def extract_tauri_version(text: str) -> str | None:
    m = re.search(r'"version"\s*:\s*"([^"]+)"', text)
    return m.group(1) if m else None


def extract_main_rs_version(text: str) -> str | None:
    # manager_build_stamp's own version half reads CARGO_PKG_VERSION at compile time, so it can
    # never itself drift from Cargo.toml -- only the hand-typed leading number in
    # manager_version_description's string is a real, independently-checkable source of truth.
    m = re.search(r'fn manager_version_description.*?"(\d+\.\d+\.\d+):', text, re.DOTALL)
    return m.group(1) if m else None


def main() -> int:
    failures: list[str] = []

    base = last_bump_commit() or "HEAD"
    changed = git_diff_paths(base)
    behavior_changed = [p for p in changed if BEHAVIOR_PATH_RE.match(p)]
    base_text = git_show(base, f"{MANAGER_DIR}/Cargo.toml")
    base_version = extract_cargo_version(base_text) if base_text else None
    now_version = extract_cargo_version(
        (REPO_ROOT / MANAGER_DIR / "Cargo.toml").read_text(encoding="utf-8")
    )

    if behavior_changed and base_version == now_version:
        commits = git_lines(
            "log", "--format=%h %s", f"{base}..HEAD", "--",
            *[f"{MANAGER_DIR}/{d}" for d in ("src", "ui", "build.rs", "capabilities")],
        )
        failures.append(
            f"NPDevManager behavior changed since the last version bump ({base[:8]}, {now_version}) "
            "but the version did not move. Changed: " + ", ".join(behavior_changed)
            + ("" if not commits else ". Commits since the bump: " + " | ".join(commits))
            + ". Bump version in Cargo.toml, tauri.conf.json, AND main.rs's "
            "manager_version_description/CURRENT_VERSION_TITLE together -- see the comment above "
            "manager_version_description in main.rs."
        )

    cargo_text = (REPO_ROOT / MANAGER_DIR / "Cargo.toml").read_text(encoding="utf-8")
    tauri_text = (REPO_ROOT / MANAGER_DIR / "tauri.conf.json").read_text(encoding="utf-8")
    main_rs_text = (REPO_ROOT / MANAGER_DIR / "src" / "main.rs").read_text(encoding="utf-8")

    cargo_version = extract_cargo_version(cargo_text)
    tauri_version = extract_tauri_version(tauri_text)
    main_rs_version = extract_main_rs_version(main_rs_text)

    versions = {
        "Cargo.toml": cargo_version,
        "tauri.conf.json": tauri_version,
        "main.rs (manager_version_description)": main_rs_version,
    }
    distinct = {v for v in versions.values() if v is not None}
    if len(distinct) > 1:
        failures.append(
            "NPDevManager's three version declarations disagree: "
            + ", ".join(f"{name}={value}" for name, value in versions.items())
        )

    if failures:
        for f in failures:
            print(f"FAIL: {f}", file=sys.stderr)
        return 1
    print("OK    NPDevManager version bump check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
