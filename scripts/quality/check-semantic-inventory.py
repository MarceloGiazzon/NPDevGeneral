#!/usr/bin/env python3
r"""Path A P1.1 (NPDEV_PATH_A_REALIGNMENT_PLAN.md): "does NPDev already have this?" must be
answerable by a query, not a re-read of the tree. ledger/semantic/inventory.yml maps every
model.schema.json top-level property to the mechanism that implements it. This checker enforces
the mapping in BOTH directions:

  - a schema key with no ledger/semantic/inventory.yml entry -> FAIL (an undocumented mechanism)
  - a ledger/semantic/inventory.yml entry naming a key the schema no longer has -> FAIL (stale
    entry, the kind of drift the plan calls out as the reason a prose "Semantic Gap Matrix" rots)

The canonical NPDevContract/schemas/model.schema.json copy is read as the source of truth for the
key set; check-schema-mirror-consistency.py already guarantees the four mirrors agree, so this
checker does not re-read all four.

Also asserts every keys[] entry carries a non-empty mechanism and status, and that status is one
of the small closed vocabulary the ledger file documents in its header comment -- an entry with a
missing mechanism/status is exactly as useless as no entry at all.

    python check-semantic-inventory.py
    python check-semantic-inventory.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
INVENTORY_PATH = REPO_ROOT / "ledger" / "semantic" / "inventory.yml"

VALID_STATUSES = {"shipped", "partial", "duplicated"}


def schema_keys(schema_path: Path) -> set[str]:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    return set(schema.get("properties", {}).keys())


def check_inventory(inventory: dict, schema_key_set: set[str]) -> list[str]:
    problems: list[str] = []

    keys_list = inventory.get("keys")
    if not isinstance(keys_list, list) or not keys_list:
        return ["inventory.yml has no (or an empty) top-level 'keys' list"]

    seen: dict[str, int] = {}
    for index, entry in enumerate(keys_list):
        if not isinstance(entry, dict):
            problems.append(f"keys[{index}] is not a mapping")
            continue
        key = entry.get("key")
        if not isinstance(key, str) or not key:
            problems.append(f"keys[{index}] has no string 'key'")
            continue
        seen[key] = seen.get(key, 0) + 1
        mechanism = entry.get("mechanism")
        if not isinstance(mechanism, str) or not mechanism.strip():
            problems.append(f"keys[{index}] ({key!r}) has no non-empty 'mechanism'")
        status = entry.get("status")
        if status not in VALID_STATUSES:
            problems.append(
                f"keys[{index}] ({key!r}) has status {status!r}, expected one of {sorted(VALID_STATUSES)}"
            )

    duplicates = sorted(k for k, count in seen.items() if count > 1)
    if duplicates:
        problems.append(f"keys[] has duplicate entries for: {duplicates}")

    entry_keys = set(seen.keys())
    missing_from_ledger = sorted(schema_key_set - entry_keys)
    if missing_from_ledger:
        problems.append(
            f"model.schema.json has keys with no ledger/semantic/inventory.yml entry: {missing_from_ledger}"
        )

    stale_in_ledger = sorted(entry_keys - schema_key_set)
    if stale_in_ledger:
        problems.append(
            f"ledger/semantic/inventory.yml names keys model.schema.json no longer has: {stale_in_ledger}"
        )

    return problems


SYNTHETIC_SCHEMA_KEYS = {"alpha", "beta", "gamma"}

SYNTHETIC_VALID = {
    "keys": [
        {"key": "alpha", "mechanism": "AlphaAst", "status": "shipped"},
        {"key": "beta", "mechanism": "BetaAst", "status": "partial"},
        {"key": "gamma", "mechanism": "GammaAst + GammaValidation (two lanes)", "status": "duplicated"},
    ]
}

SYNTHETIC_MISSING_KEY = {
    "keys": [
        {"key": "alpha", "mechanism": "AlphaAst", "status": "shipped"},
        {"key": "beta", "mechanism": "BetaAst", "status": "partial"},
    ]
}

SYNTHETIC_STALE_KEY = {
    "keys": [
        {"key": "alpha", "mechanism": "AlphaAst", "status": "shipped"},
        {"key": "beta", "mechanism": "BetaAst", "status": "partial"},
        {"key": "gamma", "mechanism": "GammaAst", "status": "shipped"},
        {"key": "delta-removed-from-schema", "mechanism": "DeltaAst", "status": "shipped"},
    ]
}

SYNTHETIC_BAD_ENTRY = {
    "keys": [
        {"key": "alpha", "mechanism": "", "status": "shipped"},
        {"key": "beta", "mechanism": "BetaAst", "status": "not-a-real-status"},
        {"key": "gamma", "mechanism": "GammaAst", "status": "shipped"},
    ]
}


def calibrate() -> int:
    ok = True

    def report(label: str, inventory: dict, expect_clean: bool) -> None:
        nonlocal ok
        problems = check_inventory(inventory, SYNTHETIC_SCHEMA_KEYS)
        passed = (len(problems) == 0) == expect_clean
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({len(problems)} problem(s))")
        for p in problems:
            print(f"           {p}")

    print("Calibration -- assertion logic must catch missing/stale/malformed entries and stay "
          "quiet on a complete, well-formed inventory:")
    report("complete inventory matching schema keys", SYNTHETIC_VALID, expect_clean=True)
    report("schema key with no ledger entry", SYNTHETIC_MISSING_KEY, expect_clean=False)
    report("ledger entry naming a key the schema doesn't have", SYNTHETIC_STALE_KEY, expect_clean=False)
    report("empty mechanism / invalid status", SYNTHETIC_BAD_ENTRY, expect_clean=False)

    if not ok:
        print("\nFAIL: the assertion logic did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: assertion logic behaves correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    if not SCHEMA_PATH.exists():
        print(f"FAIL: {SCHEMA_PATH} not found.", file=sys.stderr)
        return 1
    if not INVENTORY_PATH.exists():
        print(f"FAIL: {INVENTORY_PATH} not found.", file=sys.stderr)
        return 1

    schema_key_set = schema_keys(SCHEMA_PATH)
    inventory = yaml.safe_load(INVENTORY_PATH.read_text(encoding="utf-8")) or {}
    problems = check_inventory(inventory, schema_key_set)

    print(f"model.schema.json has {len(schema_key_set)} top-level keys; "
          f"ledger/semantic/inventory.yml has {len(inventory.get('keys', []))} entries.")
    if problems:
        print("\nProblems:")
        for p in problems:
            print(f"  - {p}")
        print(f"\nFAIL: {len(problems)} problem(s). Add/fix entries in {INVENTORY_PATH.relative_to(REPO_ROOT)}.",
              file=sys.stderr)
        return 1

    print("\nOK: ledger/semantic/inventory.yml maps every model.schema.json top-level key, "
          "in both directions, with a non-empty mechanism and a valid status.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
