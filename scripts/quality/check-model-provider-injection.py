#!/usr/bin/env python3

"""Model-provider-injection gate: NPDevModelProvider may be injected in exactly one place.
WHY THIS EXISTS
---------------
REG-208 (B28 lift) made a direct `CompiledModel` injection fail to wire at Spring startup instead
of silently caching a stale model -- but that guard is narrower than it looks. `NPDevModelProvider`
is itself a `@Component` that exposes `compiledModel()`, so injecting IT compiles, wires and boots
cleanly, and pins the injecting bean to the boot-time model forever. `DocumentRenderController` did
exactly this for roughly a year (REG-242) before a manual sweep found it -- REG-208's guard never
fired, because nothing about that injection looks wrong to Spring.

The one legitimate use is `NpdevCapabilityBindingConfig#modelHolder`, which reads
`NPDevModelProvider` once to SEED `ModelHolder` at startup. Every other bean that needs the model
must hold a `ModelHolder` and call `.get()` fresh (or register a `ModelReloadListener`), the same
convention `ModelHolder`'s own javadoc states. This gate makes "exactly one seam" a checked fact
instead of a belief.

USAGE
    python scripts/quality/check-model-provider-injection.py
    python scripts/quality/check-model-provider-injection.py --json
    python scripts/quality/check-model-provider-injection.py --repo <path>
Exit 0 = no NPDevModelProvider injection outside the allowed seam. Exit 1 = at least one. Exit 2 = usage.
"""

from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path

# NPDevRuntimeHost is the Spring Boot template copied into every generated FinalApp -- scanning the
# template catches a regression here before it ships into every future app.
SCAN_ROOTS = [
    "NPDevRuntimeHost/src/main/java",
    "NPDevRuntimeHost/runtimehost-core/src/main/java",
]

# The one seam allowed to hold NPDevModelProvider: it reads the boot-time model exactly once, to
# seed ModelHolder, which is the ONLY place a reload is ever observed. Everything else must go
# through ModelHolder instead.
ALLOWED_FILE = "NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevCapabilityBindingConfig.java"

SKIP_DIR_PARTS = {"build", "target", ".gradle", "generated"}

# A field declaration or constructor parameter: "NPDevModelProvider modelProvider". An import line
# ends in a semicolon right after the type name, so it never matches this pattern (no identifier
# between the type and the semicolon).
INJECTION_SITE = re.compile(r"\bNPDevModelProvider\s+\w+\b")

# A javadoc/comment MENTION ("the {@code NPDevModelProvider} field...") is not an injection. Same
# comment-line filter check-dialect-sites.py uses.
COMMENT = re.compile(r"^\s*(//|\*|/\*|#)")


def iter_java(repo: Path):
    for root in SCAN_ROOTS:
        base = repo / root
        if not base.is_dir():
            continue
        for path in base.rglob("*.java"):
            if any(part in SKIP_DIR_PARTS for part in path.parts):
                continue
            rel = path.relative_to(repo).as_posix()
            if rel == ALLOWED_FILE:
                continue
            yield path, rel


def scan(repo: Path) -> list[dict]:
    hits: list[dict] = []
    for path, rel in iter_java(repo):
        try:
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        except OSError:
            continue
        for number, line in enumerate(lines, 1):
            if COMMENT.match(line):
                continue
            if not INJECTION_SITE.search(line):
                continue
            hits.append({
                "file": rel,
                "line": number,
                "text": line.strip()[:160],
            })
    return hits


def resolve_repo(explicit: str | None) -> Path | None:
    if explicit:
        return Path(explicit).resolve()
    # REG-144: identify the repo by its CONTENTS, never by its directory NAME.
    candidate = Path(__file__).resolve().parents[2]
    modules = ("NPDevContract", "NPDevGenerator", "NPDevKernel")
    return candidate if all((candidate / m).is_dir() for m in modules) else None


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
    hits = scan(repo)
    if args.json:
        print(json.dumps({
            "schemaVersion": "npdev-model-provider-injection.v1",
            "repo": str(repo),
            "allowedFile": ALLOWED_FILE,
            "siteCount": len(hits),
            "sites": hits,
        }, indent=2))
        return 1 if hits else 0
    print("Model-provider-injection gate")
    print("=" * 78)
    if not hits:
        print(f"  PASS -- 0 NPDevModelProvider injections outside {ALLOWED_FILE}")
        return 0
    print(f"  FAIL -- {len(hits)} NPDevModelProvider injection(s) outside {ALLOWED_FILE}\n")
    for hit in hits:
        print(f"  {hit['file']}:{hit['line']}")
        print(f"      {hit['text']}")
    print("\n  NPDevModelProvider loads compiled-model.json ONCE at construction and never updates --")
    print("  injecting it pins the receiving bean to the boot-time model forever, invisible to any")
    print("  later /model-reload (REG-242). Inject ModelHolder instead and call .get() fresh on every")
    print("  use, or register a ModelReloadListener if the model is cached into derived state.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
