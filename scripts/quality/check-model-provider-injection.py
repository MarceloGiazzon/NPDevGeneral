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
    python scripts/quality/check-model-provider-injection.py --calibrate  # self-test, exit 1 on failure
Exit 0 = no NPDevModelProvider injection outside the allowed seam. Exit 1 = at least one. Exit 2 = usage.
"""

from __future__ import annotations
import argparse
import json
import re
import sys
import tempfile
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


def calibrate() -> int:
    ok = True

    def report(label: str, fired: bool, expect_fire: bool) -> None:
        nonlocal ok
        passed = fired == expect_fire
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} (fired: {fired}, expected: {expect_fire})")

    print("Calibration -- line-level detection must catch a real injection site, ignore a mention:")
    report("field declaration ('private final NPDevModelProvider modelProvider;')",
           bool(INJECTION_SITE.search("    private final NPDevModelProvider modelProvider;")),
           expect_fire=True)
    report("constructor parameter ('NPDevModelProvider modelProvider,')",
           bool(INJECTION_SITE.search("            NPDevModelProvider modelProvider,")),
           expect_fire=True)
    report("import line (no identifier between the type and ';')",
           bool(INJECTION_SITE.search("import com.npdev.generated.runtime.model.NPDevModelProvider;")),
           expect_fire=False)
    report("javadoc mention ('the {@code NPDevModelProvider} field...') is filtered by the comment check",
           bool(COMMENT.match(" * the {@code NPDevModelProvider} field is the one legitimate seam.")),
           expect_fire=True)

    # End-to-end: a real scan() over a synthetic two-file tree, the same code path the real gate
    # runs -- not just the two regexes in isolation.
    print("Calibration -- end-to-end scan() over a synthetic template tree:")
    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        bad_file = repo_root / "NPDevRuntimeHost/src/main/java/com/finalexec/api/SyntheticController.java"
        bad_file.parent.mkdir(parents=True, exist_ok=True)
        bad_file.write_text(
            "package com.finalexec.api;\n"
            "import com.npdev.generated.runtime.model.NPDevModelProvider;\n"
            "public class SyntheticController {\n"
            "    private final NPDevModelProvider modelProvider;\n"
            "}\n",
            encoding="utf-8",
        )
        allowed_file = repo_root / ALLOWED_FILE
        allowed_file.parent.mkdir(parents=True, exist_ok=True)
        allowed_file.write_text(
            "package com.finalexec.config;\n"
            "public class NpdevCapabilityBindingConfig {\n"
            "    public Object modelHolder(NPDevModelProvider modelProvider) { return null; }\n"
            "}\n",
            encoding="utf-8",
        )

        hits = scan(repo_root)
        hit_files = {hit["file"] for hit in hits}
        report("synthetic controller outside the allowed seam is caught",
               "NPDevRuntimeHost/src/main/java/com/finalexec/api/SyntheticController.java" in hit_files,
               expect_fire=True)
        report("the allowlisted seam itself is never flagged",
               ALLOWED_FILE in hit_files,
               expect_fire=False)

        # Now remove the bad file (the "fixed" state) and confirm the gate goes silent -- the two
        # controls above prove it CAN fire; this one proves it does not fire when there is nothing
        # left to catch.
        bad_file.unlink()
        clean_hits = scan(repo_root)
        report("gate is silent once the only bad injection is removed",
               len(clean_hits) > 0,
               expect_fire=False)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=None)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--calibrate", action="store_true", help="run the required controls and exit")
    args = parser.parse_args()
    if args.calibrate:
        return calibrate()
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
