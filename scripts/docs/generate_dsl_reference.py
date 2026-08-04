#!/usr/bin/env python
"""LNCH-22: generates docs/DSL_REFERENCE.md from the schema + widget catalog, rather than
hand-writing it -- the schemas are the truth, this keeps the reference from drifting away from
them. Run after any change to NPDevContract/schemas/model.schema.json or
FieldWidgetDefaults.java's SUPPORTED_WIDGETS; re-commit the regenerated doc.

Usage: python scripts/docs/generate_dsl_reference.py [--check]

--check exits non-zero if the regenerated content differs from what's committed (a drift gate,
wireable into a quality gate script later; not yet wired into one this session).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = WORKSPACE_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
WIDGET_DEFAULTS_PATH = (
    WORKSPACE_ROOT
    / "NPDevContract" / "dsl" / "src" / "main" / "java" / "com" / "npdev" / "dsl" / "v1"
    / "compiled" / "FieldWidgetDefaults.java"
)
OUTPUT_PATH = WORKSPACE_ROOT / "docs" / "DSL_REFERENCE.md"

# The $defs worth surfacing in the reference -- the ones an author actually writes by hand.
# Not exhaustive (the schema has ~80 $defs); these are the shapes a real model.json touches most.
FEATURED_DEFS = [
    ("concept", "Concept"),
    ("field", "Field"),
    ("conceptAccess", "Concept row-level access (LNCH-13)"),
    ("index", "Concept index"),
    ("flow", "Flow"),
    ("flowStep", "Flow step"),
    ("flowSchedule", "Flow schedule (LNCH-12)"),
    ("capability", "Capability"),
    ("capabilityPolicy", "Capability execution policy"),
    ("lifecycle", "Lifecycle"),
    ("stateTransition", "Lifecycle state transition"),
    ("event", "Event"),
    ("groupByField", "Aggregate query groupBy field (Move 10 B1, S4 joins)"),
    ("panel", "Panel"),
    ("document", "Document (LNCH-10 Slice 3 -- server-rendered PDF)"),
]


def load_schema() -> dict:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def resolve_ref(schema: dict, ref: str) -> dict:
    assert ref.startswith("#/$defs/"), f"only local $defs refs are supported here: {ref}"
    return schema["$defs"][ref[len("#/$defs/"):]]


def describe_type(prop: dict) -> str:
    if "$ref" in prop:
        return prop["$ref"].rsplit("/", 1)[-1]
    if "oneOf" in prop:
        return " | ".join(describe_type(option) for option in prop["oneOf"])
    if "if" in prop and "then" in prop and "else" in prop:
        # F5 (FIRST_IMPRESSION_SPEC.md I2): the model-array-field discriminator ({"if": has "$ref",
        # "then": localModelRef, "else": the real type}) replaced a oneOf of the same two branches --
        # describe it the same "type1 | type2" way a reader of the old oneOf-rendered reference saw,
        # else-branch (the real type) first to match the old oneOf's own ordering.
        return " | ".join([describe_type(prop["else"]), describe_type(prop["then"])])
    ptype = prop.get("type")
    if ptype == "array":
        items = prop.get("items", {})
        return f"array<{describe_type(items)}>"
    if isinstance(ptype, list):
        return " | ".join(ptype)
    if "const" in prop:
        return f'"{prop["const"]}"'
    if "enum" in prop:
        return " | ".join(f'"{v}"' for v in prop["enum"])
    return ptype or "any"


def properties_table(node: dict) -> str:
    properties = node.get("properties", {})
    required = set(node.get("required", []))
    if not properties:
        return "_(no declared properties)_\n"
    lines = ["| Field | Type | Required | Description |", "|---|---|---|---|"]
    for name, prop in properties.items():
        req = "yes" if name in required else ""
        description = (prop.get("description") or "").replace("\n", " ").strip()
        lines.append(f"| `{name}` | `{describe_type(prop)}` | {req} | {description} |")
    return "\n".join(lines) + "\n"


def extract_supported_widgets() -> list[str]:
    """SUPPORTED_WIDGETS lists constant identifiers (TEXT, TEXTAREA, ...), not string
    literals directly -- resolve each identifier back to its own
    `public static final String NAME = "value";` declaration."""
    text = WIDGET_DEFAULTS_PATH.read_text(encoding="utf-8")
    constants = dict(re.findall(r'public static final String (\w+)\s*=\s*"([^"]+)";', text))
    match = re.search(r"SUPPORTED_WIDGETS\s*=\s*Set\.of\(([^)]*)\)", text, re.DOTALL)
    if not match:
        return []
    identifiers = re.findall(r"\b(\w+)\b", match.group(1))
    widgets = [constants[i] for i in identifiers if i in constants]
    return sorted(set(widgets))


def render(schema: dict) -> str:
    out = []
    out.append("# NPDev DSL reference\n")
    out.append(
        "**Generated from `NPDevContract/schemas/model.schema.json` and "
        "`FieldWidgetDefaults.java` — do not hand-edit.** Regenerate with "
        "`python scripts/docs/generate_dsl_reference.py` after any schema change; the schemas "
        "are the source of truth (LNCH-22).\n"
    )
    out.append(f"Schema version: `{schema.get('version', 'unknown')}`. "
               f"DSL version: `{schema.get('properties', {}).get('dslVersion', {}).get('const', 'unknown')}`.\n")

    out.append("## Root model shape\n")
    out.append(properties_table(schema))

    for def_name, label in FEATURED_DEFS:
        if def_name not in schema.get("$defs", {}):
            continue
        node = schema["$defs"][def_name]
        out.append(f"## {label} (`#/$defs/{def_name}`)\n")
        description = (node.get("description") or "").strip()
        if description:
            out.append(description + "\n")
        out.append(properties_table(node))

    widgets = extract_supported_widgets()
    if widgets:
        out.append("## Field widgets\n")
        out.append(
            "Every widget a `field.widget` may declare "
            "(`FieldWidgetDefaults.SUPPORTED_WIDGETS`), compatibility with a field's declared "
            "`type` enforced at compile time by `SemanticValidator` "
            "(`WidgetCompatibilitySupportTest` is the executable spec):\n"
        )
        out.append("\n".join(f"- `{widget}`" for widget in widgets) + "\n")

    out.append("## Where to look next\n")
    out.append(
        "- `docs/TUTORIAL_FIRST_APP.md` — a golden-path walkthrough building a real app "
        "through the AI authoring loop (see `docs/adr/ADR-0006-authoring-path.md`).\n"
        "- `docs/NPDEV_CONCEPTS_DEEP_DIVE.md` — the conceptual model behind concepts/flows/"
        "capabilities/panels.\n"
        "- `knowledge/cards/*.json` — durable platform findings; `npdev_search_examples`/"
        "`npdev_search_fix` (MCP) query this corpus directly.\n"
        "- Validation error codes carry a `suggestedFix`/`helpKey` "
        "(`ValidationDiagnostic`) — every `ModelValidatorMain`/`npdev validate model` JSON "
        "report includes them per-diagnostic.\n"
        "- Truth-level release gating (`ReleaseGateValidator.validatePromotion`, "
        "`--releaseGate --targetTruthLevel=<T0..T6>`): see "
        "`NPDevContract/docs/MODEL-CONTRACT.md`'s `truthLevel` section for the full contract, "
        "including where it now runs automatically (`scripts/quality/run-generator-gate.ps1`'s "
        "`releaseGateT2` step, Move 8 item G3) and its current known-red state (REG-85).\n"
    )
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    schema = load_schema()
    content = render(schema)

    if args.check:
        existing = OUTPUT_PATH.read_text(encoding="utf-8") if OUTPUT_PATH.exists() else ""
        if existing != content:
            print("docs/DSL_REFERENCE.md is stale -- run without --check to regenerate.", file=sys.stderr)
            return 1
        print("docs/DSL_REFERENCE.md is up to date.")
        return 0

    OUTPUT_PATH.write_text(content, encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
