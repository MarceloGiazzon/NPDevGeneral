#!/usr/bin/env python3
"""Local machine resource policy: scripts/policy/local-test-profile.json (sibling of
verification-cadence.json/cadence_state.py, same read pattern) declares a checkLevel and which
DB engines are enabled on THIS machine. It exists so routine local/agent work stops spinning up
Docker/Postgres/MySQL by default -- CI is unaffected (see docker_allowed()/is_engine_enabled()
callers, which all treat CI=true as "ignore this file, run everything").

Usage:
    python scripts/quality/test_profile.py engines          # comma list, e.g. "h2,sqlserver"
    python scripts/quality/test_profile.py check-level       # e.g. "normal"
    python scripts/quality/test_profile.py docker-allowed    # exit 0/1, prints true/false
"""
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PROFILE_PATH = REPO_ROOT / "scripts" / "policy" / "local-test-profile.json"

DOCKER_ENGINES = {"postgres", "mysql"}


def load_profile():
    if not PROFILE_PATH.exists():
        raise SystemExit(f"local-test-profile.json not found: {PROFILE_PATH}")
    with PROFILE_PATH.open("r", encoding="utf-8") as f:
        doc = json.load(f)

    level = doc.get("checkLevel")
    levels = doc.get("checkLevels", [])
    if level not in levels:
        raise SystemExit(
            f"local-test-profile.json: checkLevel '{level}' is not one of {levels}"
        )

    engines = doc.get("enabledEngines", [])
    all_engines = doc.get("allEngines", [])
    unknown = [e for e in engines if e not in all_engines]
    if unknown:
        raise SystemExit(
            f"local-test-profile.json: enabledEngines contains unknown engine(s) {unknown}, "
            f"not in allEngines {all_engines}"
        )

    return doc


def enabled_engines():
    return list(load_profile().get("enabledEngines", []))


def is_engine_enabled(name):
    return name in enabled_engines()


def check_level():
    return load_profile().get("checkLevel")


def docker_allowed():
    return any(engine in DOCKER_ENGINES for engine in enabled_engines())


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    command = argv[0]
    if command == "engines":
        print(",".join(enabled_engines()))
        return 0
    if command == "check-level":
        print(check_level())
        return 0
    if command == "docker-allowed":
        allowed = docker_allowed()
        print("true" if allowed else "false")
        return 0 if allowed else 1
    print(f"Unknown command: {command}\n\n{__doc__}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
