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
    python check-panel-provenance-impact.py --discover        # every built app on this machine
    python check-panel-provenance-impact.py --calibrate

O4 (Move 11 W2): until now NOTHING invoked this script. Its only caller was the per-app
`_ops/Check-Provenance.ps1` that `Build-NpdevApp.ps1` EMITS -- which needs a running, authenticated
app, so no repo gate could ever run it. That is why REG-93 stayed red across three moves while three
move reports said "all gates green".

`--discover` closes that: it pairs each built app's `_ops/app-plan.json` (which already declares
`webSourceDir`, the directory where that app's *.panel.json manifests are authored) with the
compiled metadata sitting next to it in the same build output. No live app, no credentials, no new
hand-maintained list -- it reuses a link the build already writes. 0 built apps found is a PRINTED
pass, not a silent skip, exactly like check-panel-provenance-schema.py on a bare CI checkout.
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
        screen_rel = m.get("screen", "")
        covered.add(screen_rel)

        # REG-93: a manifest whose screen no longer exists is RETIRED, and a retired manifest can
        # never be the thing this gate exists to protect -- "a field rename goes through a rebuild;
        # that is precisely the moment the check has to fire" is about breaking a LIVE hand-written
        # screen. Once the screen is deleted (the Move 3 metric's own success condition: a console
        # reaches behavioural parity and its .html goes away), the model is free to drop the flows
        # and fields that screen used -- indeed deleting the now-callerless flows in the same change
        # is the documented rule, see docs/SCREEN_TAXONOMY.md's crossdocking "two paths, one
        # incomplete" finding. Failing on that is the gate contradicting the metric.
        # The manifest is deliberately KEPT rather than deleted (it is the provenance record of what
        # the frozen *.original.html touched), so this reports it as advisory instead of ignoring it.
        retired = bool(screen_rel) and not (root / screen_rel).exists()
        confirmed = bool(m.get("confirmed", False)) and not retired
        bucket = problems if confirmed else warnings
        if retired:
            tag = " (retired -- screen deleted, nothing left to break)"
        elif confirmed:
            tag = ""
        else:
            tag = " (unconfirmed -- advisory)"

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


DEFAULT_BUILD_ROOT = Path(r"D:\WorkSpace\NPDev\Build")

# Where a built app keeps the model surface, relative to its own `_ops` directory. The regenerated
# compiled metadata is preferred over a captured bundle: the bundle is a SNAPSHOT written by the last
# `_ops/Check-Provenance.ps1` run and can be arbitrarily old, which would make this gate fail on
# history rather than on the current model. The bundle is kept as a fallback only because it is the
# one source that carries a real `modelHash` (compiled-metadata.json has none), so the drift warning
# has teeth when it is available and is simply skipped when it is not.
METADATA_CANDIDATES = (
    Path("..") / "ArtifactNP" / "src" / "main" / "resources" / "npdev" / "compiled-metadata.json",
    Path("..") / "App" / "npdev-generated" / "src" / "main" / "resources" / "npdev" / "compiled-metadata.json",
    Path("ui-contract-bundle.json"),
)


def discover(build_root: Path) -> int:
    """Run the impact check for every app built under `build_root`, pairing each app's own
    `_ops/app-plan.json` (which declares `webSourceDir`) with the compiled metadata beside it.

    Exactly ONE build per appId is checked -- the one whose compiled metadata is newest. A machine
    accumulates build roots (`generated-finalapps`, `-alt`, `-h2q`...`-h2w` here) and an OLD build's
    metadata describes an OLD model, so checking them all would fail this gate on history rather than
    on the current model. Newest-wins is the only reading that answers the question the gate asks:
    "do today's manifests still match what the model emits today?"
    """
    print(f"Panel provenance impact check (discovery under {build_root})")
    if not build_root.exists():
        print(f"  {build_root} not present on this checkout -- 0 built app(s) checked (PASS).")
        return 0

    plans = sorted(build_root.glob("*/*/_ops/app-plan.json")) + sorted(build_root.glob("*/_ops/app-plan.json"))
    # appId -> (metadata mtime, web source root, metadata path)
    newest: dict[str, tuple[float, Path, Path]] = {}
    skip_reasons: dict[str, int] = {}

    def skip(reason: str) -> None:
        skip_reasons[reason] = skip_reasons.get(reason, 0) + 1

    for plan_path in plans:
        try:
            plan = load(plan_path)
        except (json.JSONDecodeError, OSError):
            skip("unreadable app-plan.json")
            continue
        app_id = plan.get("appId") or plan_path.parent.parent.name
        web_source = plan.get("webSourceDir") or ""
        if not web_source or not Path(web_source).exists():
            skip("app-plan.json declares no existing webSourceDir (pre-R-G1 build output)")
            continue
        root = Path(web_source).resolve()
        if not list(root.rglob("*.panel.json")):
            skip("no *.panel.json manifests authored for this app")
            continue
        metadata = next((c for c in ((plan_path.parent / rel).resolve() for rel in METADATA_CANDIDATES)
                         if c.exists()), None)
        if metadata is None:
            skip("no compiled metadata beside the build output")
            continue
        stamp = metadata.stat().st_mtime
        if app_id not in newest or stamp > newest[app_id][0]:
            newest[app_id] = (stamp, root, metadata)

    all_problems: list[str] = []
    all_warnings: list[str] = []
    for app_id, (_, root, metadata) in sorted(newest.items()):
        problems, warnings = check(root, metadata)
        print(f"  [{app_id}] {root}")
        print(f"            newest metadata: {metadata}")
        print(f"            {len(problems)} blocking, {len(warnings)} advisory")
        all_problems += [f"[{app_id}] {p}" for p in problems]
        all_warnings += [f"[{app_id}] {w}" for w in warnings]

    for reason, count in sorted(skip_reasons.items()):
        print(f"  [skip] {count} build(s): {reason}")
    for w in all_warnings:
        print(f"  [warn] {w}")

    if not newest:
        print(f"\n  no checkable built app found under {build_root} -- 0 app(s) checked (PASS).")
        return 0
    if all_problems:
        print(f"\nFAIL: {len(all_problems)} confirmed manifest(s) across {len(newest)} app(s) reference "
              f"model elements that no longer exist:")
        for p in all_problems:
            print(f"  - {p}")
        print("\nEither regenerate the screen against the current bundle, or update the model.")
        return 1
    print(f"\nOK: {len(newest)} app(s) checked, {len(all_warnings)} advisory warning(s), 0 blocking problems.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--metadata", help="compiled-metadata.json, or a captured bundle response")
    ap.add_argument("--discover", nargs="?", const=str(DEFAULT_BUILD_ROOT), default=None,
                    metavar="BUILD_ROOT",
                    help="check every app built under BUILD_ROOT (default %(default)s), pairing each "
                         "app's _ops/app-plan.json with the compiled metadata beside it")
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    if args.discover is not None:
        return discover(Path(args.discover))

    if not args.metadata:
        print("error: --metadata is required (unless --discover or --calibrate)", file=sys.stderr)
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
    """Must FAIL on a stale confirmed manifest for a LIVE screen, and stay silent on a correct one
    or on a retired one (REG-93: screen deleted -- there is no longer a screen to break)."""
    import tempfile
    ok = True
    meta = {"modelHash": "sha256:aaa", "catalogs": {
        "fields": [{"concept": "Inventario", "fieldPath": "quantidade"}],
        "invocations": [{"id": "flow:ConfirmarInventario"}]}}
    stale = {"schemaVersion": "npdev-panel-provenance.v1", "panel": "X", "screen": "web/x.html",
             "confirmed": True, "generatedFrom": {"modelHash": "sha256:aaa"},
             "reads": ["Inventario.quantidadeContada"], "writes": [], "invokes": []}
    good = dict(stale, reads=["Inventario.quantidade"])
    # (label, manifest, screen still on disk?, must the gate fire?)
    cases = (
        ("stale confirmed manifest, screen still present", stale, True, True),
        ("correct manifest, screen still present", good, True, False),
        ("stale confirmed manifest, screen DELETED (retired)", stale, False, False),
    )
    for label, manifest, screen_present, expect_fail in cases:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "web").mkdir()
            if screen_present:
                (root / "web" / "x.html").write_text("<html></html>", encoding="utf-8")
            (root / "x.panel.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
            problems, _ = check(root, root / "meta.json")
            fired = bool(problems)
            good_result = fired == expect_fail
            ok &= good_result
            print(f"  [{'PASS' if good_result else 'FAIL'}] {label} "
                  f"({'fired' if fired else 'silent'})")
    print("\nOK: all controls behave correctly." if ok else "\nCALIBRATION FAILED.")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
