#!/usr/bin/env python
"""LNCH-22: generates the DSL reference from the schema + widget catalog, rather than
hand-writing it -- the schemas are the truth, this keeps the reference from drifting away from
them. Run after any change to NPDevContract/schemas/model.schema.json or
FieldWidgetDefaults.java's SUPPORTED_WIDGETS.

md-zero-2026-08-11 PLAN.md Phase 6 (Group G): no longer committed to the repo -- the doc is build
output (docs/BUILD_OUTPUT_LOCATION_POLICY.md's existing rule for every other build artifact), and
its own drift check (compare against committed content) never caught the one real defect that hit
it (REG-133, a schema-shape change silently rendering "any" -- the SAME commit regenerated and
re-committed the doc, so the drift check matched perfectly while being wrong). That is what
scripts/quality/check-dsl-reference-output-floor.py checks instead: real content floors on a fresh
in-memory render, independent of any committed file -- unaffected by this change, since it never
read the committed doc to begin with.

Usage: python scripts/docs/generate_dsl_reference.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "ai"))
from npdev_ai_common import build_root  # noqa: E402

WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = WORKSPACE_ROOT / "NPDevContract" / "schemas" / "model.schema.json"
WIDGET_DEFAULTS_PATH = (
    WORKSPACE_ROOT
    / "NPDevContract" / "dsl" / "src" / "main" / "java" / "com" / "npdev" / "dsl" / "v1"
    / "compiled" / "FieldWidgetDefaults.java"
)

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
    schema = load_schema()
    content = render(schema)

    out_dir = build_root() / "docs"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "DSL_REFERENCE.md"
    out_path.write_text(content, encoding="utf-8")
    print(f"Wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
