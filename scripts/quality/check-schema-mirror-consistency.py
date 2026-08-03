#!/usr/bin/env python3
"""Schema mirror consistency: are all copies of model.schema.json / pack.schema.json still the same contract?

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

`pack.schema.json` has the same hazard in miniature -- duplicated in two places
(`NPDevContract/schemas/pack.schema.json`, `NPDevContract/dsl/src/main/resources/schema/pack.schema.json`)
with nothing checking them until S2 (B20 bounded contexts, S2_SPEC.md sec.0.1) added a second real
reason they could diverge (the new `contexts`/`imports` schema additions). Checked here as a second,
independent group -- a drift in one pair never masks or is masked by a drift in the other.

WHAT COUNTS AS "IDENTICAL"
---------------------------
Semantic, not byte-for-byte: parsed JSON structures are deep-compared (so pretty-printing/key-order
differences never fire). For the model.schema.json group, exactly two keys are excused on the
legacy-location copy (`NPDevContract/dsl/resources/Schemas/model.schema.json`): `deprecated` and
`canonicalSchema` -- they mark that file as the deliberately-kept legacy pointer, not a content
difference to reconcile. The pack.schema.json group has no excused keys. Any other difference -- a
new key on the wrong copy, a different enum on one, a value present on some but not all copies in a
group -- is reported as a real drift.

USAGE
-----
    python scripts/quality/check-schema-mirror-consistency.py       # exit 1 on drift, 0 if identical
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

MODEL_SCHEMA_PATHS = (
    "NPDevContract/schemas/model.schema.json",
    "NPDevContract/schemas/authoring/model.schema.json",
    "NPDevContract/dsl/src/main/resources/schema/model.schema.json",
    "NPDevContract/dsl/resources/Schemas/model.schema.json",
)

# Keys excused ONLY on the legacy-location copy (last entry in MODEL_SCHEMA_PATHS) -- see module docstring.
MODEL_SCHEMA_LEGACY_COPY_INDEX = 3
MODEL_SCHEMA_EXCUSED_KEYS_ON_LEGACY_COPY = ("deprecated", "canonicalSchema")

PACK_SCHEMA_PATHS = (
    "NPDevContract/schemas/pack.schema.json",
    "NPDevContract/dsl/src/main/resources/schema/pack.schema.json",
)


def load_normalized(path: Path, excuse_keys: tuple[str, ...]) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in excuse_keys:
        data.pop(key, None)
    return data


def check_group(
    root: Path,
    relative_paths: tuple[str, ...],
    excused_keys_by_index: dict[int, tuple[str, ...]],
    label: str,
) -> tuple[bool, bool]:
    """Returns (ok, hard_error). hard_error means a file was missing or invalid JSON (exit 2 territory)."""
    resolved = [root / p for p in relative_paths]
    missing = [p for p in resolved if not p.is_file()]
    if missing:
        for p in missing:
            print(f"ERROR: schema copy not found: {p}", file=sys.stderr)
        return False, True

    normalized = []
    for i, path in enumerate(resolved):
        excuse = excused_keys_by_index.get(i, ())
        try:
            normalized.append(load_normalized(path, excuse))
        except json.JSONDecodeError as exc:
            print(f"ERROR: {path} is not valid JSON: {exc}", file=sys.stderr)
            return False, True

    print(f"Schema mirror consistency ({len(relative_paths)} copies of {label}, semantic compare):")
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
        print(f"\nFAIL: the {label} copies are not semantically identical. Every edit to "
              f"{label} must be mirrored to all {len(relative_paths)} (CLAUDE.md's own standing rule).",
              file=sys.stderr)
        return False, False

    excused_note = ""
    if any(excused_keys_by_index.values()):
        excused = next(v for v in excused_keys_by_index.values() if v)
        excused_note = f" (excusing {excused} on the legacy-location copy)"
    print(f"\nOK: all {len(relative_paths)} copies semantically identical{excused_note}.")
    return True, False


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".", help="repo root (default: cwd)")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    model_ok, model_hard_error = check_group(
        root, MODEL_SCHEMA_PATHS,
        {MODEL_SCHEMA_LEGACY_COPY_INDEX: MODEL_SCHEMA_EXCUSED_KEYS_ON_LEGACY_COPY},
        "model.schema.json",
    )
    print()
    pack_ok, pack_hard_error = check_group(root, PACK_SCHEMA_PATHS, {}, "pack.schema.json")

    if model_hard_error or pack_hard_error:
        return 2
    if not model_ok or not pack_ok:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
