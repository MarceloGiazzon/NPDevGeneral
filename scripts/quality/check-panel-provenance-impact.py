#!/usr/bin/env python3
"""F4 (docs/NEXT_EXECUTION_PLAN.md P4.4): fail the build when a CONFIRMED panel-provenance
manifest references a model element that no longer exists. Unconfirmed manifests are reported,
never failed -- same refuse-vs-warn discipline as check-register-consistency.py and
check-narrative-status-drift.py.

Accepts either a full compiled-metadata.json (fields/invocations under "catalogs") or a captured
UI-contract bundle response (fields/invocations at the top level, plus a real "modelHash" --
compiled-metadata.json has no modelHash field, so the drift-WARN check only has teeth against a
real bundle capture, not the bare compiled model).

    python check-panel-provenance-impact.py --root <app-or-appgen-dir> --metadata bundle.json
    python check-panel-provenance-impact.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load(p: Path) -> dict:
    return json.loads(p.read_text(encoding="utf-8-sig"))


def model_surface(metadata: dict) -> tuple[set[str], set[str]]:
    cat = metadata.get("catalogs", metadata)
    fields = {f"{f['concept']}.{f['fieldPath']}" for f in cat.get("fields", [])}
    invocations = {i["id"] for i in cat.get("invocations", [])}
    return fields, invocations


def check(root: Path, metadata_path: Path) -> tuple[list[str], list[str]]:
    metadata = load(metadata_path)
    fields, invocations = model_surface(metadata)
    current_hash = metadata.get("modelHash", "")
    problems: list[str] = []
    warnings: list[str] = []

    manifests = sorted(root.rglob("*.panel.json"))
    covered = set()

    for mf in manifests:
        m = load(mf)
        rel = mf.relative_to(root).as_posix()
        covered.add(m.get("screen", ""))
        confirmed = bool(m.get("confirmed", False))
        bucket = problems if confirmed else warnings
        tag = "" if confirmed else " (unconfirmed -- advisory)"

        for ref in m.get("reads", []) + m.get("writes", []):
            if ref not in fields:
                bucket.append(f"{rel}: references field '{ref}', which the model no longer has{tag}")
        for inv in m.get("invokes", []):
            if inv not in invocations:
                bucket.append(f"{rel}: references invocation '{inv}', which no longer exists{tag}")

        built = m.get("generatedFrom", {}).get("modelHash", "")
        if confirmed and current_hash and built and built != current_hash:
            warnings.append(f"{rel}: built against {built[:19]}..., model is now {current_hash[:19]}... "
                            f"-- regenerate to pick up model changes")

    for screen in sorted(root.rglob("web/*.html")):
        if screen.as_posix() not in covered and not screen.with_suffix(".panel.json").exists():
            warnings.append(f"{screen.relative_to(root).as_posix()}: no provenance manifest "
                            f"-- run scripts/quality/bootstrap-panel-provenance.py")

    return problems, warnings


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--metadata", help="compiled-metadata.json, or a captured bundle response")
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    if not args.metadata:
        print("error: --metadata is required (unless --calibrate)", file=sys.stderr)
        return 2

    problems, warnings = check(Path(args.root).resolve(), Path(args.metadata).resolve())
    print("Panel provenance impact check")
    for w in warnings:
        print(f"  [warn] {w}")
    if problems:
        print(f"\nFAIL: {len(problems)} confirmed manifest(s) reference model elements "
              f"that no longer exist:")
        for p in problems:
            print(f"  - {p}")
        print("\nEither regenerate the screen against the current bundle, or update the model.")
        return 1
    print(f"\nOK: {len(warnings)} advisory warning(s), 0 blocking problems.")
    return 0


def calibrate() -> int:
    """Must FAIL on a stale confirmed manifest and PASS on a correct one."""
    import tempfile
    ok = True
    meta = {"modelHash": "sha256:aaa", "catalogs": {
        "fields": [{"concept": "Inventario", "fieldPath": "quantidade"}],
        "invocations": [{"id": "flow:ConfirmarInventario"}]}}
    stale = {"schemaVersion": "npdev-panel-provenance.v1", "panel": "X", "screen": "web/x.html",
             "confirmed": True, "generatedFrom": {"modelHash": "sha256:aaa"},
             "reads": ["Inventario.quantidadeContada"], "writes": [], "invokes": []}
    good = dict(stale, reads=["Inventario.quantidade"])
    for label, manifest, expect_fail in (("stale confirmed manifest", stale, True),
                                         ("correct manifest", good, False)):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "web").mkdir()
            (root / "x.panel.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
            problems, _ = check(root, root / "meta.json")
            fired = bool(problems)
            good_result = fired == expect_fail
            ok &= good_result
            print(f"  [{'PASS' if good_result else 'FAIL'}] {label} "
                  f"({'fired' if fired else 'silent'})")
    print("\nOK: both controls behave correctly." if ok else "\nCALIBRATION FAILED.")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
