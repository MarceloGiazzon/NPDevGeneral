#!/usr/bin/env python3
"""Select which registered checks a change needs (FORM P1-T3) -- the knob that makes "fewer gates" real.

Every entry of scripts/quality/verification-cadence.json carries `level`, `protects`, `triggers` (path globs) and
`costSeconds`. This script reads the changed paths, the registry and the profile's `checkLevelMap`
(scripts/policy/local-test-profile.json), and prints the ids of the checks to run:

    python scripts/quality/select_checks.py                       # profile checkLevel, changes vs origin/main + working tree
    python scripts/quality/select_checks.py --level low --json
    python scripts/quality/select_checks.py --level core --changed NPDevContract/dsl/src/main/Foo.java
    python scripts/quality/select_checks.py --level extended      # whole levels run regardless of triggers
    python scripts/quality/select_checks.py --list-levels

Rules:
- `--level` is a profile name (very_low .. full) or one registry level (core, standard, extended, release, paperwork).
  A profile name expands through checkLevelMap to a set of registry levels.
- core / standard checks run only when one of their `triggers` matches a changed path (a check with trigger `**` always runs).
- extended / release / paperwork checks run whenever their level is selected, whatever changed: those levels mean "everything".
- With no changed paths at all (clean tree on the base), core/standard select nothing: nothing changed, nothing to prove.
- The per-level budget and the slowest selected checks are printed so an over-budget selection is visible.
This script only SELECTS. Executing a check stays with the runner that already owns it (its `invokedBy`).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CADENCE_PATH = REPO_ROOT / "scripts" / "quality" / "verification-cadence.json"
PROFILE_PATH = REPO_ROOT / "scripts" / "policy" / "local-test-profile.json"
LEVEL_ORDER = ["core", "standard", "extended", "release", "paperwork"]
TRIGGERED_LEVELS = {"core", "standard"}
BUDGET_SECONDS = {"core": 180, "standard": 720, "extended": 2100, "release": 5400, "paperwork": 1800}


def glob_to_regex(glob: str) -> re.Pattern:
    """`**` crosses directories, `*` does not, `?` is one non-slash char. Matches the whole repo-relative path."""
    out, i = [], 0
    while i < len(glob):
        c = glob[i]
        if glob.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif glob.startswith("**", i):
            out.append(".*")
            i += 2
        elif c == "*":
            out.append("[^/]*")
            i += 1
        elif c == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(c))
            i += 1
    return re.compile("^" + "".join(out) + "$")


def triggered(check: dict, changed: list[str]) -> bool:
    return any(glob_to_regex(t).match(path) for t in check["triggers"] for path in changed)


def load_checks() -> list[dict]:
    return json.loads(CADENCE_PATH.read_text(encoding="utf-8"))["checks"]


def load_profile() -> dict:
    return json.loads(PROFILE_PATH.read_text(encoding="utf-8"))


def levels_for(name: str, profile: dict) -> list[str]:
    level_map = profile.get("checkLevelMap", {})
    if name in level_map:
        return list(level_map[name])
    if name in LEVEL_ORDER:
        return [name]
    raise SystemExit(f"unknown level '{name}': use one of {sorted(level_map)} or {LEVEL_ORDER}")


def git_lines(*args: str) -> list[str]:
    try:
        out = subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def changed_paths(base: str) -> list[str]:
    """Committed changes since `base` plus the working tree (staged, unstaged, untracked), repo-relative with '/'."""
    paths = set(git_lines("diff", "--name-only", f"{base}...HEAD"))
    paths.update(git_lines("diff", "--name-only", "HEAD"))
    paths.update(git_lines("ls-files", "--others", "--exclude-standard"))
    return sorted(p.replace("\\", "/") for p in paths)


def select(checks: list[dict], level_names: list[str], changed: list[str]) -> list[dict]:
    selected = []
    for check in checks:
        if check["level"] not in level_names:
            continue
        if check["level"] in TRIGGERED_LEVELS and not triggered(check, changed):
            continue
        selected.append(check)
    selected.sort(key=lambda c: (LEVEL_ORDER.index(c["level"]), -c["costSeconds"], c["id"]))
    return selected


def calibrate() -> int:
    checks = [
        {"id": "a", "level": "core", "triggers": ["**/model.schema.json"], "costSeconds": 3},
        {"id": "b", "level": "standard", "triggers": ["NPDevContract/dsl/**"], "costSeconds": 90},
        {"id": "c", "level": "extended", "triggers": ["x/**"], "costSeconds": 600},
        {"id": "d", "level": "core", "triggers": ["**"], "costSeconds": 1},
    ]
    profile = {"checkLevelMap": {"low": ["core", "standard"], "high": ["core", "standard", "extended"]}}
    problems = []

    def ids(level, changed):
        return [c["id"] for c in select(checks, levels_for(level, profile), changed)]

    if ids("low", []) != []:
        problems.append("a clean tree must select no triggered check -- even a '**' trigger needs a changed path")
    if ids("low", ["docs/readme.txt"]) != ["d"]:
        problems.append("only the '**' check may match an unrelated doc change")
    if ids("low", ["NPDevContract/schemas/model.schema.json"]) != ["a", "d"]:
        problems.append("`**/model.schema.json` must match a nested schema path (core checks sort by cost, then id)")
    if ids("low", ["NPDevContract/dsl/src/Main.java"]) != ["d", "b"]:
        problems.append("`NPDevContract/dsl/**` must match a file below it")
    if "c" in ids("low", ["NPDevContract/dsl/src/Main.java"]):
        problems.append("an extended check must not run at level low")
    if ids("high", []) != ["c"]:
        problems.append("extended must run whatever changed, and core/standard still need a trigger")
    if ids("extended", [])[:1] != ["c"]:
        problems.append("a registry level name must be accepted as --level")
    if glob_to_regex("*.md").match("docs/a.md"):
        problems.append("`*` must not cross a directory")
    if problems:
        print("CALIBRATION FAILED:")
        for p in problems:
            print("  - " + p)
        return 1
    print("CALIBRATION OK: select_checks.py selection rules behave as documented.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--level", default=None, help="profile name or registry level (default: the profile's checkLevel; CI=true: full)")
    ap.add_argument("--base", default="origin/main", help="revision the committed changes are measured from")
    ap.add_argument("--changed", nargs="*", default=None, help="explicit changed paths instead of asking git")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--list-levels", action="store_true")
    ap.add_argument("--calibrate", action="store_true")
    opts = ap.parse_args(argv[1:])
    if opts.calibrate:
        return calibrate()
    profile = load_profile()
    if opts.list_levels:
        for name, levels in profile.get("checkLevelMap", {}).items():
            print(f"{name:11s} -> {', '.join(levels)}")
        return 0
    level = opts.level or ("full" if os.environ.get("CI") == "true" else profile["checkLevel"])
    level_names = levels_for(level, profile)
    changed = [p.replace("\\", "/") for p in opts.changed] if opts.changed is not None else changed_paths(opts.base)
    selected = select(load_checks(), level_names, changed)
    total = sum(c["costSeconds"] for c in selected)
    budget = sum(BUDGET_SECONDS[name] for name in level_names)
    if opts.json:
        print(json.dumps({
            "level": level, "levels": level_names, "changedCount": len(changed),
            "selected": [c["id"] for c in selected], "estimatedSeconds": total, "budgetSeconds": budget,
            "overBudget": total > budget,
        }, indent=2))
        return 0
    for check in selected:
        print(f"{check['id']}  [{check['level']}, ~{check['costSeconds']}s, protects {check['protects']}]  <- {check['invokedBy']}")
    print(f"-- level {level}: {len(selected)} check(s), ~{total}s of a {budget}s budget; {len(changed)} changed path(s)")
    if total > budget:
        slow = ", ".join(f"{c['id']} ({c['costSeconds']}s)" for c in sorted(selected, key=lambda c: -c["costSeconds"])[:3])
        print(f"-- OVER BUDGET: slowest: {slow}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
