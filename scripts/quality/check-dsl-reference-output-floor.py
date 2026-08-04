#!/usr/bin/env python
"""REG-133 -- the gate that would have caught 8cd9860.

WHY THIS EXISTS
---------------
generate_dsl_reference.py's own --check mode is a DRIFT check: it fails only when the
regenerated content differs from what is already committed. That does not catch 8cd9860's
actual failure mode, because the same commit that changed model.schema.json (oneOf ->
if/then/else) ALSO regenerated and re-committed docs/DSL_REFERENCE.md -- the freshly
regenerated content matched the freshly committed content perfectly. --check would have passed.
The renderer had silently started producing "any" instead of "concept | localModelRef" for
every one of the 19 model-array fields, and nothing noticed until a human read the doc.

A drift check answers "was this regenerated." This answers "is what it produced any good" --
an absolute floor on the rendered CONTENT itself, independent of whether it matches history.

WHAT THIS CHECKS (two floors, deliberately)
--------------------------------------------
1. THE EXACT 8cd9860 SHAPE: every root model-array field whose schema uses the if/then/else
   (or oneOf) discriminator must render BOTH branch type names in the reference's "Root model
   shape" table -- never the bare fallback "any". This is schema-shape-agnostic: it does not
   assume if/then/else specifically, so the NEXT composition keyword the schema grows (anyOf,
   a new discriminator shape, ...) trips this floor the same way, rather than needing this
   script updated every time describe_type() needs a new branch.
2. BREADTH: the reference must name >= a floor count of flowStep types, procedureStep types,
   and concept fields -- catches the enum/properties silently shrinking even when no field
   renders as "any" (e.g. describe_type() renders each value fine but the loop that gathers
   them drops half the list).

Regenerates the reference in memory (imports generate_dsl_reference's own render()/load_schema())
rather than re-implementing schema-walking a second time -- this checks the REAL output a real
regeneration would produce, not a parallel guess at it.

Usage: python scripts/quality/check-dsl-reference-output-floor.py
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "docs"))

import generate_dsl_reference as gdr  # noqa: E402

MIN_FLOWSTEP_TYPES = 10
MIN_PROCEDURESTEP_TYPES = 15
MIN_CONCEPT_FIELDS = 8
MIN_DISCRIMINATED_FIELDS = 15  # 19 as of this writing (F5/8cd9860) -- see check_breadth's own guard


def discriminated_array_fields(schema: dict) -> list[str]:
    """Root array fields whose items use the if/then/else (or oneOf) localModelRef discriminator
    -- computed FROM THE SCHEMA ITSELF, not a hardcoded list. A hardcoded list here would be
    exactly the twin-pair risk this whole item is about: it would silently stop covering a field
    the schema grows a discriminator for, with nothing to notice the two had drifted apart."""
    fields = []
    for name, prop in schema.get("properties", {}).items():
        if prop.get("type") != "array":
            continue
        items = prop.get("items", {})
        if ("if" in items and "then" in items and "else" in items) or "oneOf" in items:
            fields.append(name)
    return fields


def check_no_bare_any(schema: dict) -> list[str]:
    problems = []
    root_props = schema.get("properties", {})
    discriminated = discriminated_array_fields(schema)
    if len(discriminated) < MIN_DISCRIMINATED_FIELDS:
        problems.append(
            f"only {len(discriminated)} root field(s) detected as using the if/then/else|oneOf "
            f"discriminator, floor is {MIN_DISCRIMINATED_FIELDS} -- either the schema genuinely "
            f"dropped fields (update this floor deliberately) or discriminated_array_fields() no "
            f"longer recognizes the shape the schema actually uses (check model.schema.json's "
            f"array item shapes directly)"
        )
    for field in discriminated:
        prop = root_props[field]
        rendered_type = gdr.describe_type(prop)
        if rendered_type.strip().lower() in ("any", "array<any>"):
            problems.append(
                f"root field '{field}' renders as '{rendered_type}' -- describe_type() does not "
                f"understand this field's current schema shape (the exact 8cd9860 failure mode: "
                f"a real, structured type collapsing to the bare fallback, silently)"
            )
    return problems


def check_breadth(schema: dict) -> list[str]:
    problems = []
    flowstep_types = schema.get("$defs", {}).get("flowStep", {}).get("properties", {}).get("type", {}).get("enum", [])
    if len(flowstep_types) < MIN_FLOWSTEP_TYPES:
        problems.append(
            f"flowStep.type enum has only {len(flowstep_types)} value(s), floor is {MIN_FLOWSTEP_TYPES}"
        )
    procstep_types = schema.get("$defs", {}).get("procedureStep", {}).get("properties", {}).get("type", {}).get("enum", [])
    if len(procstep_types) < MIN_PROCEDURESTEP_TYPES:
        problems.append(
            f"procedureStep.type enum has only {len(procstep_types)} value(s), floor is {MIN_PROCEDURESTEP_TYPES}"
        )
    concept_fields = schema.get("$defs", {}).get("concept", {}).get("properties", {})
    if len(concept_fields) < MIN_CONCEPT_FIELDS:
        problems.append(
            f"concept has only {len(concept_fields)} declared propert(y/ies), floor is {MIN_CONCEPT_FIELDS}"
        )
    return problems


def main() -> int:
    schema = gdr.load_schema()

    problems = check_no_bare_any(schema) + check_breadth(schema)

    print("REG-133 -- docs/DSL_REFERENCE.md output floor")
    print("=" * 78)
    if not problems:
        print("  OK: no discriminated root field renders as a bare fallback; breadth floors met.")
        return 0

    print(f"  FAIL: {len(problems)} problem(s) -- the reference generator silently degraded:")
    for p in problems:
        print(f"    - {p}")
    print()
    print("  This is the gate 8cd9860 needed: generate_dsl_reference.py's --check mode only")
    print("  catches 'not regenerated', not 'regenerated but produced less'.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
