#!/usr/bin/env python3
"""Schema mirror consistency: are all four copies of model.schema.json still the same contract?

WHY THIS EXISTS -- 2.A.2 (docs/DSL2_AND_DECOMPOSITION_PLAN.md)
------------------------------------------------------------------
`model.schema.json` is duplicated in four places (CLAUDE.md names this explicitly as a hazard):
  - NPDevContract/schemas/model.schema.json
  - NPDevContract/schemas/authoring/model.schema.json
  - NPDevContract/dsl/src/main/resources/schema/model.schema.json   (the copy actually loaded at
    runtime, per JsonModelSchemaValidator.SCHEMA_RESOURCE_PATH)
  - NPDevContract/dsl/resources/Schemas/model.schema.json           (carries two extra provenance
    keys, "deprecated" and "canonicalSchema", marking it as the legacy authoring location)

Nothing enforced these four staying in sync -- as of 2026-07-27 they already had drifted 3-line
formatting-only differences. A future edit made to only one or two of the four (the DSL 2.0 vocabulary
change this script was built alongside is exactly this kind of edit) would silently leave the others
teaching the old contract to whichever consumer reads them -- the authoring UI reads the `authoring`
copy, the DSL module loads its own `src/main/resources` copy, and so on. This gate makes that
impossible to do unnoticed.

WHAT COUNTS AS "IDENTICAL"
---------------------------
Semantic, not byte-for-byte: parsed JSON structures are deep-compared (so pretty-printing/key-order
differences never fire), with exactly two keys excused on the legacy-location copy
(`NPDevContract/dsl/resources/Schemas/model.schema.json`): `deprecated` and `canonicalSchema`. Those
mark that file as the deliberately-kept legacy pointer, not a content difference to reconcile. Any
other difference -- a new key on the wrong copy, a different enum on one, a value present on three
but not the fourth -- is reported as a real drift.

USAGE
-----
    python scripts/quality/check-schema-mirror-consistency.py       # exit 1 on drift, 0 if identical
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCHEMA_PATHS = (
    "NPDevContract/schemas/model.schema.json",
    "NPDevContract/schemas/authoring/model.schema.json",
    "NPDevContract/dsl/src/main/resources/schema/model.schema.json",
    "NPDevContract/dsl/resources/Schemas/model.schema.json",
)

# Keys excused ONLY on the legacy-location copy (last entry in SCHEMA_PATHS) -- see module docstring.
LEGACY_COPY_INDEX = 3
EXCUSED_KEYS_ON_LEGACY_COPY = ("deprecated", "canonicalSchema")


def load_normalized(path: Path, excuse_keys: tuple[str, ...]) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in excuse_keys:
        data.pop(key, None)
    return data


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    resolved = [root / p for p in SCHEMA_PATHS]
    missing = [p for p in resolved if not p.is_file()]
    if missing:
        for p in missing:
            print(f"ERROR: schema copy not found: {p}", file=sys.stderr)
        return 2

    normalized = []
    for i, path in enumerate(resolved):
        excuse = EXCUSED_KEYS_ON_LEGACY_COPY if i == LEGACY_COPY_INDEX else ()
        try:
            normalized.append(load_normalized(path, excuse))
        except json.JSONDecodeError as exc:
            print(f"ERROR: {path} is not valid JSON: {exc}", file=sys.stderr)
            return 2

    print("Schema mirror consistency (4 copies of model.schema.json, semantic compare):")
    baseline = normalized[0]
    drift_found = False
    for i in range(1, len(normalized)):
        if normalized[i] != baseline:
            drift_found = True
            print(f"  [DRIFT] {resolved[i].relative_to(root).as_posix()} differs from "
                  f"{resolved[0].relative_to(root).as_posix()}", file=sys.stderr)
            # Best-effort pinpoint: walk both dicts' top-level keys to name which one first differs.
            for key in sorted(set(baseline) | set(normalized[i])):
                if baseline.get(key) != normalized[i].get(key):
                    print(f"           first differing top-level key: '{key}'", file=sys.stderr)
                    break
        else:
            print(f"  [OK]    {resolved[i].relative_to(root).as_posix()}")

    if drift_found:
        print("\nFAIL: the four schema copies are not semantically identical. Every edit to "
              "model.schema.json must be mirrored to all four (CLAUDE.md's own standing rule).",
              file=sys.stderr)
        return 1

    print(f"\nOK: all four copies semantically identical (excusing {EXCUSED_KEYS_ON_LEGACY_COPY} on "
          f"the legacy-location copy).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
