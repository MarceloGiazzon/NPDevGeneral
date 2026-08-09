#!/usr/bin/env python3
"""A runtime-host template resource must never sit at a path the generator also writes.

WHY THIS EXISTS
---------------
A generated FinalApp is the runtime-host template plus `npdev-generated/`, and its build.gradle
mounts both as resource roots:

    resources {
        srcDir 'src/main/resources'                      <- the TEMPLATE's copy
        srcDir 'npdev-generated/src/main/resources'      <- the APP's real one
    }
    ...
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

EXCLUDE keeps the FIRST file it sees, and the template is listed first. So any path present in both
resolves to the template's copy -- the static, app-agnostic one -- and the generated file is dropped
with no warning from Gradle, no error at boot, and nothing in the app to indicate it happened.

REG-142 was two instances of exactly that, both measured in a real built app:

  npdev/model.json                        every generated app served
                                          `{"namespace":"npdev.template","concepts":[]}` from
                                          GET /api/admin/model/export, GET /api/admin/model/ui and
                                          the capability integration panel -- a DIFFERENT app's
                                          identity, presented as its own.
  npdev/security/dev.ui-metadata-policy   the template's copy does not even share the app copy's
                                          shape (`items` vs `fieldPolicies`/`actionPolicies`), so
                                          the app's UI permission policy was unreadable rather than
                                          merely overridden. Harmless only while both were empty.

The failure mode is what makes it worth a gate: adding a seeded resource to the template is a
reasonable-looking thing to do, it passes every build, and it silently disables whatever the
generator emits at that path. One of these two sat there since 2026-04-23.

HOW IT MEASURES
---------------
The generated side is read from the generator's own source -- every
`writeRelative("src/main/resources/...")` string literal under NPDevGenerator -- rather than from a
hand-kept list, so a new emitted resource is covered the day it is written. The template side is the
actual files under NPDevRuntimeHost/src/main/resources. The finding is the intersection.

Reading literals means a path assembled at runtime from variables is not seen. That is a real limit
and it is stated rather than papered over: it under-reports, never over-reports, and every emitted
resource today is a literal.

USAGE
    python scripts/quality/check-template-resource-shadowing.py
    python scripts/quality/check-template-resource-shadowing.py --json
    python scripts/quality/check-template-resource-shadowing.py --repo <path>

Exit 0 = no template resource shadows a generated one. Exit 1 = at least one does. Exit 2 = usage.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

GENERATOR_SOURCE = "NPDevGenerator/generator/src/main/java"
TEMPLATE_RESOURCES = "NPDevRuntimeHost/src/main/resources"

WRITE_RELATIVE = re.compile(r'writeRelative\(\s*"(src/main/resources/[^"]+)"')


def resolve_repo(explicit: str | None) -> Path | None:
    if explicit:
        return Path(explicit).resolve()
    # REG-144: identify the repo by its CONTENTS, never by its directory NAME. This file is at
    # <repo>/scripts/quality/, so the arithmetic is exact and adds no ancestor walk.
    candidate = Path(__file__).resolve().parents[2]
    modules = ("NPDevContract", "NPDevGenerator", "NPDevKernel")
    return candidate if all((candidate / m).is_dir() for m in modules) else None


def emitted_resource_paths(repo: Path) -> set[str]:
    paths: set[str] = set()
    source_root = repo / GENERATOR_SOURCE
    for java in source_root.rglob("*.java"):
        paths.update(WRITE_RELATIVE.findall(java.read_text(encoding="utf-8", errors="ignore")))
    return paths


def template_resource_paths(repo: Path) -> set[str]:
    root = repo / TEMPLATE_RESOURCES
    if not root.is_dir():
        return set()
    return {"src/main/resources/" + f.relative_to(root).as_posix()
            for f in root.rglob("*") if f.is_file()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    repo = resolve_repo(args.repo)
    if repo is None:
        print("error: could not resolve the repo root (the directory holding NPDevContract + "
              "NPDevGenerator + NPDevKernel) from this script's location; pass --repo",
              file=sys.stderr)
        return 2

    emitted = emitted_resource_paths(repo)
    template = template_resource_paths(repo)
    if not emitted:
        # An empty emitted set would make this check pass vacuously forever. Loud, not silent.
        print(f"error: found no writeRelative(\"src/main/resources/...\") literals under "
              f"{GENERATOR_SOURCE} -- this check cannot measure anything and is not passing, it is "
              f"broken", file=sys.stderr)
        return 2
    shadowed = sorted(emitted & template)

    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-template-resource-shadowing.v1",
            "repo": str(repo),
            "emittedResourceCount": len(emitted),
            "templateResourceCount": len(template),
            "shadowedCount": len(shadowed),
            "shadowed": shadowed,
        }, indent=2))
        return 1 if shadowed else 0

    print("Template resource shadowing gate")
    print("=" * 78)
    print(f"  {len(emitted)} generated resource path(s), {len(template)} template resource(s)")
    if not shadowed:
        print("  PASS -- no template resource sits at a path the generator also writes")
        return 0

    print(f"\n  FAIL -- {len(shadowed)} template resource(s) shadow a generated one:\n")
    for path in shadowed:
        print(f"    {path}")
        print(f"      template: {TEMPLATE_RESOURCES}/{path[len('src/main/resources/'):]}")
    print("\n  DuplicatesStrategy.EXCLUDE keeps the FIRST copy and the template is mounted first, so")
    print("  the app's generated file is dropped silently -- no Gradle warning, no boot error.")
    print("  Delete the template's copy. If a consumer throws when the resource is absent, make")
    print("  absence mean the empty/default value: an app that generates nothing there IS the")
    print("  default, and a boot-time throw is the only reason the shadowing file existed.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
