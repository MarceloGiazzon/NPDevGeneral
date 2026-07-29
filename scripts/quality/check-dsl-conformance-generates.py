#!/usr/bin/env python3
r"""G2 (docs/CLOSEOUT_PLAN.md): every DSL feature is proven to PARSE (check-dsl-coverage.py, the
corpus-parse gate) but almost none are proven to actually EMIT -- NPDevSamples/dsl-conformance-max
itself was "validated, not run" until this check existed. F4 was exactly this failure one layer over:
generatedAction was runtime-proven by a packaged-app test and rejected by the validator, and the two
halves never met for as long as the feature existed. This check is the parse/validate side's mirror
on the generation side: a change that silently breaks the EMITTER for a rare feature must not be able
to pass while every feature still parses fine.

Generates the fixture for real (NPDevSamples/scripts/generate-sample-app.ps1 -NoAssembleFinalApp --
emission only, no build/boot needed) and asserts specific rare features survive into the compiled
output, not just that the generator command exited 0 -- a generator that silently drops a feature
would otherwise still pass.

Compiled representation differs from the authored model.json in ways that matter here (measured
directly against a real generation run before writing these assertions):
  - aggregates/autoPanels/documents/domainTypes/externalAi/guidePages survive as non-empty top-level
    keys in compiled-model.json, same shape as the source.
  - a `selectors` entry has no top-level key of its own in the compiled output -- it fully expands
    into an ordinary `panels[]` entry; the compiled marker is that panel's
    `metadata.generatedBy == "selector"`.
  - step kinds forEach/generatedAction survive as literal `steps[].type` values.
  - onFailure compensation survives as a non-empty `steps[].onFailureSteps` list (the compiled name;
    the source-side authoring key is `onFailure`).
  - flow.schedule survives as a non-null `flows[].schedule`.
  - flow.hooks and flow.specializes do NOT survive as a distinct marker -- both fully desugar into
    the flattened `steps[]` list during compilation (measured: neither key exists anywhere on a
    compiled flow, even for PlaceWidgetOrderAudited, whose SOURCE model declares both). Asserting
    their presence at this layer would need a naming-convention heuristic (e.g. "a step named
    hook-*"), not a real structural check, so this script does not attempt one --
    check-dsl-coverage.py's source-level check remains the authority for those two features. A real
    regression in either compiler pass would still be caught here indirectly: a broken
    hooks/specializes pass throws during generation, which this script's exit-code check (before any
    content assertion runs) already fails on.

    python check-dsl-conformance-generates.py
    python check-dsl-conformance-generates.py --skip-generate   # assert against an already-generated
                                                                  # compiled-model.json, for local
                                                                  # iteration without re-running Gradle
    python check-dsl-conformance-generates.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_ID = "dsl-conformance-max"
GENERATE_SCRIPT = REPO_ROOT / "NPDevSamples" / "scripts" / "generate-sample-app.ps1"
COMPILED_MODEL_PATH = (
    REPO_ROOT / "NPDevSamples" / SAMPLE_ID / "Output" / "ArtifactNP"
    / "src" / "main" / "resources" / "npdev" / "compiled-model.json"
)


def _nonempty(model: dict, key: str) -> bool:
    value = model.get(key)
    return isinstance(value, (list, dict)) and len(value) > 0


def _has_selector_panel(model: dict) -> bool:
    for panel in model.get("panels", None) or []:
        if isinstance(panel, dict) and panel.get("metadata", {}).get("generatedBy") == "selector":
            return True
    return False


def _walk_steps(steps):
    for step in steps or []:
        if not isinstance(step, dict):
            continue
        yield step
        yield from _walk_steps(step.get("onFailureSteps"))
        yield from _walk_steps(step.get("steps"))
        yield from _walk_steps(step.get("then"))
        yield from _walk_steps(step.get("else"))


def _all_steps(model: dict):
    for flow in model.get("flows", None) or []:
        if isinstance(flow, dict):
            yield from _walk_steps(flow.get("steps"))


def _has_step_type(model: dict, step_type: str) -> bool:
    return any(str(s.get("type", "")).lower() == step_type.lower() for s in _all_steps(model))


def _has_on_failure_steps(model: dict) -> bool:
    return any(s.get("onFailureSteps") for s in _all_steps(model))


def _has_schedule(model: dict) -> bool:
    return any(isinstance(f, dict) and f.get("schedule") for f in (model.get("flows", None) or []))


ASSERTIONS = {
    "aggregates": lambda m: _nonempty(m, "aggregates"),
    "autoPanels": lambda m: _nonempty(m, "autoPanels"),
    "documents": lambda m: _nonempty(m, "documents"),
    "domainTypes": lambda m: _nonempty(m, "domainTypes"),
    "externalAi": lambda m: _nonempty(m, "externalAi"),
    "guidePages": lambda m: _nonempty(m, "guidePages"),
    "selectors (as a panel)": _has_selector_panel,
    "step.forEach": lambda m: _has_step_type(m, "forEach"),
    "step.generatedAction": lambda m: _has_step_type(m, "generatedAction"),
    "step.onFailureSteps": _has_on_failure_steps,
    "flow.schedule": _has_schedule,
}


def check_model(model: dict) -> list[str]:
    return [name for name, fn in ASSERTIONS.items() if not fn(model)]


def generate() -> None:
    result = subprocess.run(
        [
            "pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(GENERATE_SCRIPT),
            "-SampleId", SAMPLE_ID,
            "-NPDevRoot", str(REPO_ROOT),
            "-NoAssembleFinalApp",
        ],
        cwd=REPO_ROOT,
    )
    if result.returncode != 0:
        print(f"FAIL: generation of {SAMPLE_ID} failed (exit {result.returncode}) -- see the "
              f"generator output above.", file=sys.stderr)
        sys.exit(1)


SYNTHETIC_MISSING = {"panels": [], "flows": []}
SYNTHETIC_PRESENT = {
    "aggregates": [{"name": "X"}], "autoPanels": [{"name": "X"}], "documents": [{"name": "X"}],
    "domainTypes": [{"name": "X"}], "externalAi": {"a": 1}, "guidePages": [{"name": "X"}],
    "panels": [{"name": "P", "metadata": {"generatedBy": "selector"}}],
    "flows": [{"name": "F", "schedule": {"cron": "* * * * * *"}, "steps": [
        {"name": "s1", "type": "forEach"},
        {"name": "s2", "type": "generatedAction"},
        {"name": "s3", "type": "invariant", "onFailureSteps": [{"name": "comp"}]},
    ]}],
}


def calibrate() -> int:
    ok = True

    def report(label: str, model: dict, expect_all_present: bool) -> None:
        nonlocal ok
        missing = check_model(model)
        passed = (len(missing) == 0) == expect_all_present
        ok = ok and passed
        state = "all present" if not missing else f"{len(missing)} missing"
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({state})")
        for m in missing:
            print(f"           missing: {m}")

    print("Calibration -- assertion logic must catch a missing feature and stay quiet on a present one:")
    report("synthetic model missing every feature", SYNTHETIC_MISSING, expect_all_present=False)
    report("synthetic model carrying every feature", SYNTHETIC_PRESENT, expect_all_present=True)

    if not ok:
        print("\nFAIL: the assertion logic did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: assertion logic behaves correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--skip-generate", action="store_true",
                     help="assert against an already-generated compiled-model.json, for local iteration")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    if not args.skip_generate:
        print(f"Generating {SAMPLE_ID} for real (emission only, no build/boot needed)...")
        generate()

    if not COMPILED_MODEL_PATH.exists():
        print(f"FAIL: expected compiled model not found: {COMPILED_MODEL_PATH}", file=sys.stderr)
        return 1

    model = json.loads(COMPILED_MODEL_PATH.read_text(encoding="utf-8"))
    missing = check_model(model)

    print(f"dsl-conformance-max generation check: {len(ASSERTIONS)} feature(s) asserted present in "
          f"the compiled output.\n")
    for name in ASSERTIONS:
        marker = "MISSING" if name in missing else "OK"
        print(f"  {name.ljust(28)} [{marker}]")

    if missing:
        print(f"\nFAIL: {len(missing)} feature(s) parsed but did not survive into the compiled "
              f"output:", file=sys.stderr)
        for m in missing:
            print(f"  - {m}", file=sys.stderr)
        return 1
    print("\nOK: every asserted feature survived generation, not just parsing.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
