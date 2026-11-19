#!/usr/bin/env python3
"""Every DSL keyword has a meaning, and docs/DSL_KEYWORD_REFERENCE.md is not stale.

    python -I scripts/quality/check-dsl-keyword-reference.py [--calibrate]

WHAT IT CHECKS (scripts/docs/generate_dsl_keyword_reference.py is the renderer; no markdown is read)
  1. COVERAGE.  Every keyword slot counted by check-dsl-keyword-budget.py (and every model root key) has a meaning,
                either the schema node's own `description` or an entry in scripts/policy/dsl-keyword-meanings/*.json,
                and every key written inside an inline object has one too. A new keyword without a meaning fails here:
                the reference is where a keyword is "taught".
  2. NO ORPHANS. A meanings entry that names a keyword the schema no longer has fails (the data may not rot).
  3. LAYOUT.    Every `$defs` entry is placed under exactly one noun in layout.json (the renderer refuses otherwise).
  4. EXAMPLES.  The top-level keys of every example in the meanings data are keywords of that kind.
  5. FRESHNESS. The renderer's output hashes to the SHA-256 recorded in scripts/policy/dsl-keyword-reference-stamp.json.
                Scripts may not read markdown, so the page is re-rendered in memory and compared to the stamp.
                A schema, meanings-data or renderer change without `python scripts/docs/generate_dsl_keyword_reference.py`
                fails here.

Invoked by run-ai-knowledge-gate.ps1. Level core; triggers are the schemas, the meanings data and the renderer.
"""
from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "docs"))
import generate_dsl_keyword_reference as renderer  # noqa: E402


def check(model: dict, pack: dict, meanings: dict, stamp: dict | None) -> list[str]:
    problems: list[str] = []
    cov = renderer.coverage(model, pack, meanings)
    for slot in cov["missing"]:
        problems.append(
            f"keyword '{slot}' has no meaning -- add a `description` to its schema node, or an entry under `slots` in "
            "scripts/policy/dsl-keyword-meanings/meanings-*.json")
    for key in sorted(set(cov["inlineMissing"])):
        problems.append(f"inline key '{key}' has no meaning -- add it under `inline` in scripts/policy/dsl-keyword-meanings/meanings-*.json")
    for slot in cov["orphans"]:
        problems.append(f"meanings data names '{slot}' but the schema has no such keyword -- remove the entry")

    defs = model.get("$defs", {})
    for def_name, info in meanings["defs"].items():
        if def_name not in defs:
            problems.append(f"meanings data has a `defs` entry for '{def_name}', which is not a $defs entry of the schema")
            continue
        example = info.get("example")
        known = set((defs[def_name].get("properties") or {}))
        if isinstance(example, dict) and known:
            for key in example:
                if key not in known:
                    problems.append(f"example of '{def_name}' uses '{key}', which is not a keyword of that kind")

    if problems:
        return problems
    try:
        recorded = (stamp or {}).get("renderSha256")
        if recorded is None:
            problems.append(f"{renderer.STAMP_PATH.relative_to(REPO_ROOT)} is missing: run python scripts/docs/generate_dsl_keyword_reference.py and commit the result")
        elif recorded != renderer.render_sha256(model, pack, meanings):
            problems.append(
                "docs/DSL_KEYWORD_REFERENCE.md is stale against the schema, the meanings data or the renderer: "
                "run python scripts/docs/generate_dsl_keyword_reference.py and commit the result")
    except ValueError as exc:
        problems.append(f"layout: {exc}")
    return problems


def calibrate(model: dict, pack: dict, meanings: dict) -> int:
    """A checker that cannot see a planted defect will not see a real one."""
    failures = 0
    clean = check(model, pack, meanings, {"renderSha256": renderer.render_sha256(model, pack, meanings)})
    if clean:
        print("calibration FAILED: the clean tree is reported as defective:")
        for line in clean:
            print(f"  - {line}")
        failures += 1
    slot = next(iter(meanings["slots"]))
    broken = copy.deepcopy(meanings)
    del broken["slots"][slot]
    node_has_description = False
    def_name, _, prop = slot.partition(".")
    if "." in slot and def_name in model.get("$defs", {}):
        node_has_description = bool(model["$defs"][def_name]["properties"].get(prop, {}).get("description"))
    if not node_has_description and not check(model, pack, broken, None):
        print(f"calibration FAILED: deleting the meaning of '{slot}' was not reported")
        failures += 1
    orphan = copy.deepcopy(meanings)
    orphan["slots"]["noSuchDef.noSuchKeyword"] = "x"
    if not any("noSuchDef.noSuchKeyword" in p for p in check(model, pack, orphan, None)):
        print("calibration FAILED: a meaning for a keyword the schema lacks was not reported")
        failures += 1
    if not any("stale" in p for p in check(model, pack, meanings, {"renderSha256": "0" * 64})):
        print("calibration FAILED: a stale stamp was not reported")
        failures += 1
    return failures


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true", help="also plant defects in memory and fail unless each is reported")
    opts = ap.parse_args(argv[1:])

    model, pack = renderer.load_schemas()
    meanings = renderer.load_meanings()
    stamp = json.loads(renderer.STAMP_PATH.read_text(encoding="utf-8")) if renderer.STAMP_PATH.is_file() else None
    problems = check(model, pack, meanings, stamp)
    if opts.calibrate and calibrate(model, pack, meanings):
        return 1
    if problems:
        print("DSL keyword reference: FAILED")
        for line in problems:
            print(f"  - {line}")
        return 1
    cov = renderer.coverage(model, pack, meanings)
    from_data = sum(1 for src in cov["slots"].values() if src == "data")
    from_schema = sum(1 for src in cov["slots"].values() if src == "schema")
    print(f"DSL keyword reference: OK ({len(cov['slots'])} keywords: {from_schema} meanings from the schema, {from_data} from the meanings data; "
          f"{len(cov['root'])} root keys; page matches its stamp)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
