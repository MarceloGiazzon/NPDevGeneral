#!/usr/bin/env python3
"""R-G1 static half (docs/REMEDIATION_PLAN.md): does every *.panel.json manifest structurally
validate against schemas/panel-provenance.schema.json, and is every invokes[] entry well-formed?

This is the half of the impact gate (check-panel-provenance-impact.py, F4) that needs no live app
/ authenticated bundle -- unlike F4's field/invocation EXISTENCE check (which needs a real compiled
model to compare against), this only checks a manifest's own SHAPE: required fields present, no
unexpected fields, enums/consts respected, reads/writes/invokes entries pattern-shaped. Runs in CI
(run-ai-knowledge-gate.ps1) with zero external dependency; the live half runs per-app against a
real bundle via `_ops/Check-Provenance.ps1` (F4 proper, wired 2026-07-28).

Hand-rolled against the schema rather than a `jsonschema` dependency: schemas/panel-provenance
.schema.json is shallow (no $ref chains, no oneOf/anyOf) and this keeps the script in this repo's
established "no new pip dependency for a shallow shape check" norm (contrast
scripts/requirements.txt's PyYAML, needed for a genuinely nested/enum-driven ledger schema). If the
schema grows real structural complexity, switch to a real validator instead of growing this by hand.

`invokes[]` gets an EXTRA check beyond the schema itself: the schema only requires each entry be a
string (it cannot know the invocations catalog's real id shapes without importing the DSL module),
so a malformed id (typo'd kind, wrong separator -- exactly the dot-vs-colon bug
bootstrap-panel-provenance.py's own history records) would pass schema validation and then FAIL
silently downstream, since F4's live check can only ever report "not found in the catalog", not
"malformed". Catching the shape here, statically, surfaces that class of bug immediately instead of
waiting for a live bundle -- see INVOCATION_ID_KINDS, kept in sync BY HAND with
CompiledMetadataCanonicalJson#toInvocationCatalog's id-emitting methods (same kind of hand
correspondence this repo already accepts for scripts/quality/check-panel-provenance-impact.py's own
sibling scripts; add a kind here when the emitter grows one).

Manifests live in AppGen/apps (a non-git external workspace, CLAUDE.md's Layer 2) -- a GitHub
Actions checkout has neither that directory nor (normally) any committed *.panel.json under this
repo, so 0 found is a PASS ("nothing to check on this checkout"), always printed rather than
silently skipped, so it reads as checked-and-empty, not skipped-and-forgotten.

    python check-panel-provenance-schema.py
    python check-panel-provenance-schema.py --root D:\\WorkSpace\\NPDev\\AppGen\\apps
    python check-panel-provenance-schema.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


def _default_appgen_root() -> Path:
    """Layer 2 (app definitions) lives OUTSIDE the repo and is not a git repo, so CI never has it.
    Resolve it from $NPDEV_APPGEN_APPS, else as a sibling of the repo root identified by CONTENTS --
    never by assuming a drive letter (REG-144)."""
    from_env = os.environ.get("NPDEV_APPGEN_APPS")
    if from_env:
        return Path(from_env).expanduser().resolve()
    here = Path(__file__).resolve()
    for ancestor in here.parents:
        if (ancestor / "NPDevContract").is_dir() and (ancestor / "NPDevKernel").is_dir():
            return ancestor.parent / "AppGen" / "apps"
    return Path("AppGen") / "apps"

# Optional `<namespace>::` prefix before the concept name -- pack-provided concepts (identity::User,
# workspace::Menu) are real and namespaced this way (schemas/panel-provenance.schema.json, same
# pattern, fixed alongside this one: the original pattern had never been exercised by a pack-field
# reference until R-G2's usuarios-roles.panel.json).
FIELD_PATTERN = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*::)?[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z0-9_.]+$")

# Kept in sync BY HAND with CompiledMetadataCanonicalJson#toInvocationCatalog's id-emitting helpers
# (NPDevContract/dsl/.../compiled/CompiledMetadataCanonicalJson.java) -- add a kind here the same
# commit a new invocation kind is added there.
INVOCATION_ID_KINDS = (
    "list", "pagedQuery", "exportCsv", "read",
    "createDirect", "updateDirect", "deleteDirect", "fileUpload",
    "flow", "panelAction", "panelRowAdd", "panelRowDelete",
    "aggregateRead", "aggregateCommit", "aggregateInvoke",
)
INVOCATION_ID_PATTERN = re.compile(
    r"^(" + "|".join(INVOCATION_ID_KINDS) + r"):[A-Za-z0-9_]+(:[A-Za-z0-9_]+)?$"
)

TOP_LEVEL_REQUIRED = (
    "schemaVersion", "panel", "producer", "generatedFrom", "reads", "writes",
    "invokes", "calls", "slotOf", "confirmed", "unresolved",
)
TOP_LEVEL_ALLOWED = TOP_LEVEL_REQUIRED + ("screen", "screenClass", "handWritten", "reviewedBy", "notes")
GENERATED_FROM_ALLOWED = ("modelHash", "generatedAt", "generator", "bundleScope")
BUNDLE_SCOPE_ALLOWED = ("concept", "panel")
PRODUCER_ENUM = ("generator", "agent", "human")


def _err(path: Path, msg: str) -> str:
    return f"{path}: {msg}"


def validate_manifest(path: Path, m: dict) -> list[str]:
    errors: list[str] = []
    if not isinstance(m, dict):
        return [_err(path, "top level is not a JSON object")]

    for field in TOP_LEVEL_REQUIRED:
        if field not in m:
            errors.append(_err(path, f"missing required field '{field}'"))
    extra = sorted(set(m.keys()) - set(TOP_LEVEL_ALLOWED))
    if extra:
        errors.append(_err(path, f"unexpected top-level field(s) {extra} (schema is additionalProperties:false)"))

    if m.get("schemaVersion") not in (None, "npdev-panel-provenance.v1"):
        errors.append(_err(path, f"schemaVersion must be 'npdev-panel-provenance.v1', got {m.get('schemaVersion')!r}"))
    if "producer" in m and m["producer"] not in PRODUCER_ENUM:
        errors.append(_err(path, f"producer must be one of {PRODUCER_ENUM}, got {m.get('producer')!r}"))
    if "confirmed" in m and not isinstance(m["confirmed"], bool):
        errors.append(_err(path, f"confirmed must be a boolean, got {m.get('confirmed')!r}"))
    if "screenClass" in m and m["screenClass"] is not None and not isinstance(m["screenClass"], str):
        errors.append(_err(path, "screenClass must be a string or null"))
    if "slotOf" in m and m["slotOf"] is not None and not isinstance(m["slotOf"], str):
        errors.append(_err(path, "slotOf must be a string or null"))

    generated_from = m.get("generatedFrom")
    if isinstance(generated_from, dict):
        if "generator" not in generated_from:
            errors.append(_err(path, "generatedFrom missing required field 'generator'"))
        gf_extra = sorted(set(generated_from.keys()) - set(GENERATED_FROM_ALLOWED))
        if gf_extra:
            errors.append(_err(path, f"generatedFrom has unexpected field(s) {gf_extra}"))
        bundle_scope = generated_from.get("bundleScope")
        if isinstance(bundle_scope, dict):
            bs_extra = sorted(set(bundle_scope.keys()) - set(BUNDLE_SCOPE_ALLOWED))
            if bs_extra:
                errors.append(_err(path, f"generatedFrom.bundleScope has unexpected field(s) {bs_extra}"))
    elif "generatedFrom" in m:
        errors.append(_err(path, "generatedFrom must be an object"))

    for field in ("reads", "writes"):
        for entry in m.get(field, []) or []:
            if not isinstance(entry, str) or not FIELD_PATTERN.match(entry):
                errors.append(_err(path, f"{field}[] entry {entry!r} is not 'Concept.fieldPath'-shaped"))

    for entry in m.get("invokes", []) or []:
        if not isinstance(entry, str) or not INVOCATION_ID_PATTERN.match(entry):
            errors.append(_err(
                path,
                f"invokes[] entry {entry!r} does not match a known invocation id shape "
                f"(<kind>:<name> or <kind>:<name>:<name>, kind one of {INVOCATION_ID_KINDS})"
            ))

    return errors


def find_manifests(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(root.rglob("*.panel.json"))


def check(root: Path) -> tuple[list[str], int]:
    manifests = find_manifests(root)
    all_errors: list[str] = []
    for path in manifests:
        try:
            manifest = json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError as exc:
            all_errors.append(_err(path, f"not valid JSON: {exc}"))
            continue
        all_errors.extend(validate_manifest(path, manifest))
    return all_errors, len(manifests)


def calibrate() -> int:
    """Must FAIL on a manifest with a malformed invokes[] id or a missing field, PASS on a
    well-formed one -- same required-controls discipline as check-register-consistency.py's
    --calibrate."""
    good = {
        "schemaVersion": "npdev-panel-provenance.v1", "panel": "X", "producer": "human",
        "generatedFrom": {"generator": "bootstrap-panel-provenance.py"},
        "reads": ["Inventario.quantidade"], "writes": [], "invokes": ["panelAction:X:Confirm"],
        "calls": [], "slotOf": None, "confirmed": True, "unresolved": [],
    }
    bad_invoke_shape = dict(good, invokes=["panelAction:X.Confirm"])  # dot, not colon -- the real historical bug
    missing_field = {k: v for k, v in good.items() if k != "producer"}
    extra_field = dict(good, notAllowedHere=True)

    ok = True

    def report(label: str, manifest: dict, expect_fail: bool) -> None:
        nonlocal ok
        errors = validate_manifest(Path("<synthetic>"), manifest)
        fired = bool(errors)
        passed = fired == expect_fail
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for e in errors:
            print(f"           {e}")

    print("Calibration -- must catch a malformed invokes[] id and a missing required field:")
    report("well-formed manifest", good, expect_fail=False)
    report("malformed invokes[] id (dot instead of colon -- the real bootstrap-panel-provenance.py bug)",
           bad_invoke_shape, expect_fail=True)
    report("missing required field 'producer'", missing_field, expect_fail=True)
    report("unexpected top-level field (additionalProperties:false)", extra_field, expect_fail=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=str(_default_appgen_root()),
                     help="where to look for *.panel.json (default: the AppGen apps workspace, "
                          "resolved from $NPDEV_APPGEN_APPS or as a sibling of the repo root)")
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    root = Path(args.root)
    errors, count = check(root)

    if not root.exists():
        print(f"Panel provenance schema check: {root} not present on this checkout -- 0 manifest(s) checked (PASS).")
        return 0

    print(f"Panel provenance schema check: {count} manifest(s) found under {root}, {len(errors)} error(s).")
    if errors:
        print("\nFAIL: the following manifest(s) do not match schemas/panel-provenance.schema.json:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    print("OK: every manifest is structurally valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
