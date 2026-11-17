#!/usr/bin/env python3
"""Check that concept-map.json names the fate of EVERY top-level key of model.schema.json, and nothing else.

    python -I scripts/quality/check-concept-map.py [--schema <model.schema.json>] [--map <concept-map.json>] [--after-phase P3]

Without --after-phase: exits 1 when a schema key has no entry, or an entry names a key the schema does not
have (except the map's newKeys, which may or may not exist yet).
With --after-phase Pn: ALSO exits 1 when a key whose fate is MERGE/REMOVE/MOVE/RENAME and whose phase is <= Pn
is still in the schema -- the ratchet that proves a phase really removed what it said it would.
Every key must point at one of the 12 nouns (or "(header)").
Invoked by run-ai-knowledge-gate.ps1. The JSON (scripts/policy/concept-map.json) is the truth: scripts may not read markdown.
ADR: docs/adr/ADR-0013-twelve-nouns.md.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MAP = REPO_ROOT / "scripts" / "policy" / "concept-map.json"
DEFAULT_SCHEMA = REPO_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
GONE = {"MERGE", "REMOVE", "MOVE", "RENAME"}


def phase_number(text: str) -> int:
    try:
        return int(str(text).lstrip("P"))
    except ValueError:
        return 99


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--schema", default=str(DEFAULT_SCHEMA))
    ap.add_argument("--map", default=str(DEFAULT_MAP))
    ap.add_argument("--after-phase", default=None)
    opts = ap.parse_args(argv[1:])
    schema = json.loads(Path(opts.schema).read_text(encoding="utf-8"))
    cmap = json.loads(Path(opts.map).read_text(encoding="utf-8"))
    schema_keys = set((schema.get("properties") or {}).keys())
    entries = {e["key"]: e for e in cmap["keys"]}
    new_keys = {e["key"] for e in cmap.get("newKeys", [])}
    nouns = {n["noun"] for n in cmap["nouns"]} | {"(header)"}
    errors = []
    for key in sorted(schema_keys - set(entries) - new_keys):
        errors.append(f"schema key '{key}' has no fate in concept-map.json -- add it (and say which noun it belongs to)")
    for key in sorted(set(entries) - schema_keys):
        if entries[key]["fate"] not in GONE:
            errors.append(f"concept-map.json names '{key}' but the schema has no such key")
    for key, entry in entries.items():
        if entry.get("noun") not in nouns:
            errors.append(f"'{key}' points at noun '{entry.get('noun')}', which is not one of the 12 nouns")
    if opts.after_phase:
        limit = phase_number(opts.after_phase)
        for key, entry in entries.items():
            if entry["fate"] in GONE and phase_number(entry.get("phase")) <= limit and key in schema_keys:
                errors.append(f"'{key}' should be gone after {entry.get('phase')} ({entry['fate']} -> {entry.get('target', '-')}) but is still in the schema")
    fates = {}
    for entry in entries.values():
        fates[entry["fate"]] = fates.get(entry["fate"], 0) + 1
    print(f"schema keys: {len(schema_keys)}; mapped: {len(entries)}; fates: {fates}; nouns: {len(nouns) - 1}")
    for error in errors:
        print("FAIL " + error)
    print("OK" if not errors else f"{len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
