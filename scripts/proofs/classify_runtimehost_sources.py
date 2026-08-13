#!/usr/bin/env python3
"""BT-1a (PACK-ROADMAP.md card BT-1, step 1 / MASTER-ROADMAP.md Step 2b): classify
NPDevRuntimeHost/src/main/java into app-coupled (jarrable-ineligible) vs app-independent
(jarrable) files, as a standalone, re-runnable artifact instead of only the inline Groovy
heuristic in NPDevRuntimeHost/build.gradle.template (generatedRuntimeDependentMainSources).

This is BT-1's classify step only -- steps 2-6 (the actual source-tree split, jar publish,
FinalAppAssembler change, measurement) are MASTER-ROADMAP.md Step 7 (BT-1 b+c), not this
script's job. This script exists so the 262/49 split is data, not a claim.

    python scripts/proofs/classify_runtimehost_sources.py [--out PATH]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

MARKER = "com.npdev.generated."


def classify(main_root: Path) -> dict:
    app_coupled = []
    app_independent = []
    coupled_loc = 0
    independent_loc = 0

    for path in sorted(main_root.rglob("*.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        loc = text.count("\n") + 1
        rel = path.relative_to(main_root).as_posix()
        if MARKER in text:
            app_coupled.append(rel)
            coupled_loc += loc
        else:
            app_independent.append(rel)
            independent_loc += loc

    total_files = len(app_coupled) + len(app_independent)
    total_loc = coupled_loc + independent_loc
    return {
        "schemaVersion": "npdev-runtimehost-classification.v1",
        "markerText": MARKER,
        "totals": {
            "files": total_files,
            "loc": total_loc,
        },
        "appCoupled": {
            "files": len(app_coupled),
            "loc": coupled_loc,
            "paths": app_coupled,
        },
        "appIndependent": {
            "files": len(app_independent),
            "loc": independent_loc,
            "paths": app_independent,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=None,
                         help="Write the JSON report here (default: print to stdout)")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    main_root = repo_root / "NPDevRuntimeHost" / "src" / "main" / "java"
    if not main_root.is_dir():
        print(f"error: {main_root} not found", flush=True)
        return 2

    report = classify(main_root)
    rendered = json.dumps(report, indent=2) + "\n"

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(rendered, encoding="utf-8")
        print(f"wrote {args.out} "
              f"({report['appIndependent']['files']} app-independent / "
              f"{report['appCoupled']['files']} app-coupled, "
              f"{report['totals']['files']} total)")
    else:
        print(rendered)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
