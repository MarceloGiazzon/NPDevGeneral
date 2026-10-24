#!/usr/bin/env python3
"""ENF-1: every high-risk DSL keyword has a behaviour witness, or an allowlisted reason.

check-dsl-coverage.py proves a DSL feature APPEARS in some model; it cannot prove the runtime obeys it
(nested onFailure and two-clause query filters were accepted and silently ignored for months). The
probes in NPDevSamples/dsl-conformance-max/behavior-probes/*.json do prove it, against a booted app
(scripts/quality/run-behavior-probes.ps1, T1/T2). THIS checker is the static half and needs no app:

  * every property of every tracked `$defs` family listed in scripts/policy/dsl-behavior-witness.json
    is a keyword `<def>.<property>`. A probe WITNESSES a keyword by naming it in its own `keywords`
    array (the probe file is the single source -- the registry never repeats it); a keyword with no
    probe must sit in an `allowlist` group with a reason and a ledger id;
  * a probe keyword that is not a keyword of the tracked families is stale and fails;
  * a probe's `knownFailure` must name an existing ledger item that is not DONE (a marker may never
    outlive the bug it points at -- the runner already turns it red once the probe passes);
  * a keyword that acts only at generation time (no booted-app effect, so no REST probe can see it) is
    witnessed by a `generationWitnesses` entry: a named JUnit `testClass` plus the `methods` that pin it.
    The check is static (the file exists, each method is a declared @Test method) and never runs Java;
    the test itself runs in the module's own gradle test task (ENF-2);
  * the allowlist is a RATCHET: `frozenCount` pins how many keywords it holds. Growing fails; shrinking
    without lowering `frozenCount` in the same commit also fails, so the ceiling only ratchets down.

    python check-dsl-behavior-witness.py
    python check-dsl-behavior-witness.py --emit-uncovered   # keywords that still need a decision
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA = REPO_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
REGISTRY = REPO_ROOT / "scripts" / "policy" / "dsl-behavior-witness.json"
LEDGER_ITEMS = REPO_ROOT / "ledger" / "items"
PROBES_DIR = REPO_ROOT / "NPDevSamples" / "dsl-conformance-max" / "behavior-probes"


def schema_keywords(families: list[str]) -> set[str]:
    defs = json.loads(SCHEMA.read_text(encoding="utf-8"))["$defs"]
    found: set[str] = set()
    for family in families:
        if family not in defs:
            raise SystemExit(f"registry family {family!r} is not a $defs entry in model.schema.json")
        for prop in _properties(defs[family]):
            found.add(f"{family}.{prop}")
    return found


def _properties(node) -> set[str]:
    """Property names of a schema node, including those declared inside oneOf/anyOf/allOf/then/else branches."""
    names: set[str] = set()
    if isinstance(node, dict):
        names |= set((node.get("properties") or {}).keys())
        for key in ("oneOf", "anyOf", "allOf"):
            for branch in node.get(key) or []:
                names |= _properties(branch)
        for key in ("then", "else"):
            names |= _properties(node.get(key))
    return names


def load_probes() -> list[dict]:
    probes: list[dict] = []
    for path in sorted(PROBES_DIR.glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        for spec in (doc["probes"] if "probes" in doc else [doc]):
            spec["_file"] = path.name
            probes.append(spec)
    return probes


def ledger_status(item_id: str) -> str | None:
    path = LEDGER_ITEMS / f"{item_id}.yml"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("status:"):
            return line.split(":", 1)[1].strip()
    return "?"


def declared_test_methods(path: Path) -> set[str]:
    """Names of methods declared in a .java file with an @Test-family annotation within the 3 preceding lines."""
    lines = path.read_text(encoding="utf-8").splitlines()
    found: set[str] = set()
    for idx, line in enumerate(lines):
        match = re.search(r"\bvoid\s+(\w+)\s*\(", line)
        if match and any(re.search(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b", prev)
                         for prev in lines[max(0, idx - 3):idx]):
            found.add(match.group(1))
    return found


def generation_witnessed(entries: list[dict], keywords: set[str], problems: list[str]) -> dict[str, dict]:
    witnessed: dict[str, dict] = {}
    for entry in entries:
        label = f"generationWitnesses {entry.get('keywords', [])[:2]}"
        if not str(entry.get("reason", "")).strip():
            problems.append(f"{label}: missing reason")
        if entry.get("category") != "generationTest":
            problems.append(f"{label}: category must be 'generationTest'")
        if not entry.get("keywords"):
            problems.append(f"{label}: names no keyword")
        for kw in entry.get("keywords", []):
            if kw not in keywords:
                problems.append(f"{label}: keyword {kw!r} is not a keyword of the tracked schema families (stale or mistyped)")
            if kw in witnessed:
                problems.append(f"{kw}: listed in generationWitnesses twice")
            witnessed[kw] = entry
        test_class = REPO_ROOT / str(entry.get("testClass", ""))
        if not entry.get("testClass") or not test_class.is_file() or test_class.suffix != ".java":
            problems.append(f"{label}: testClass {entry.get('testClass')!r} is not an existing repo-relative .java file")
            continue
        if not entry.get("methods"):
            problems.append(f"{label}: names no test method")
        declared = declared_test_methods(test_class)
        for method in entry.get("methods", []):
            if method not in declared:
                problems.append(f"{label}: {entry['testClass']} declares no @Test method {method!r}")
    return witnessed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--emit-uncovered", action="store_true")
    args = parser.parse_args()

    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    keywords = schema_keywords(registry["families"])
    problems: list[str] = []

    allow: dict[str, dict] = {}
    for group in registry.get("allowlist", []):
        for field in ("reason", "ledger"):
            if not str(group.get(field, "")).strip():
                problems.append(f"allowlist group {group.get('keywords', [])[:2]}...: missing {field}")
        if group.get("ledger") and ledger_status(group["ledger"]) in (None, "DONE"):
            problems.append(f"allowlist group {group.get('keywords', [])[:2]}...: ledger item {group['ledger']} is missing or DONE")
        for kw in group.get("keywords", []):
            if kw in allow:
                problems.append(f"{kw}: listed in the allowlist twice")
            allow[kw] = group

    probes = load_probes()
    seen_ids: set[str] = set()
    witnessed: dict[str, list[str]] = {}
    for probe in probes:
        pid = probe["id"]
        if pid in seen_ids:
            problems.append(f"probe id {pid!r} is used twice")
        seen_ids.add(pid)
        if not probe.get("keywords"):
            problems.append(f"probe {pid!r} ({probe['_file']}) witnesses no keyword")
        for kw in probe.get("keywords", []):
            if kw not in keywords:
                problems.append(f"probe {pid!r}: keyword {kw!r} is not a keyword of the tracked schema families (stale or mistyped)")
            witnessed.setdefault(kw, []).append(pid)
        known = probe.get("knownFailure")
        if known and ledger_status(known) in (None, "DONE"):
            problems.append(f"probe {pid!r}: knownFailure {known!r} is not an open ledger item (fix the bug and drop the marker, or file the item)")
    for kw in sorted(set(witnessed) & set(allow)):
        problems.append(f"{kw}: both witnessed and allowlisted -- remove it from the allowlist and lower frozenCount")

    gen = generation_witnessed(registry.get("generationWitnesses", []), keywords, problems)
    for kw in sorted(set(gen) & set(allow)):
        problems.append(f"{kw}: both generation-witnessed and allowlisted -- remove it from the allowlist and lower frozenCount")
    for kw in sorted(set(gen) & set(witnessed)):
        problems.append(f"{kw}: both generation-witnessed and probe-witnessed -- keep only one witness")

    uncovered = sorted(keywords - set(witnessed) - set(allow) - set(gen))
    if args.emit_uncovered:
        print(chr(10).join(uncovered))
        return 0
    for kw in uncovered:
        problems.append(f"{kw}: no witness probe and no allowlist entry")
    for kw in sorted(set(allow) - keywords):
        problems.append(f"{kw}: allowlisted but not a keyword of the schema families (stale)")

    frozen = registry["frozenCount"]
    if len(allow) > frozen:
        problems.append(f"allowlist holds {len(allow)} keywords, frozenCount is {frozen}: the allowlist may only shrink")
    elif len(allow) < frozen:
        problems.append(f"allowlist holds {len(allow)} keywords but frozenCount is {frozen}: lower frozenCount to {len(allow)} in the same commit")

    known_count = sum(1 for probe in probes if probe.get("knownFailure"))
    print(f"{len(keywords)} tracked keywords: {len(set(witnessed))} witnessed by {len(probes)} probe(s) "
          f"({known_count} known failure(s)), {len(gen)} generation-witnessed {sorted(gen)}, "
          f"{len(allow)} allowlisted (frozenCount {frozen})")
    for problem in problems:
        print(f"FAIL  {problem}")
    if problems:
        print(f"{len(problems)} blocking finding(s).")
        return 1
    print("0 blocking finding(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
