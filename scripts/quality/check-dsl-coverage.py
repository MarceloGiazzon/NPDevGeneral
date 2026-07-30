#!/usr/bin/env python3
r"""F8 (docs/FINAL_OPEN_ITEMS_PLAN.md): the corpus-parse gate answers "does every model parse?".
Nothing answered "is every DSL feature exercised by at least one model?" -- the gap that let 7
schema features (`selectors`, `externalAi`, step `forEach`, step `generatedAction`, `onFailure`
compensation, `flow.schedule`, `flow.hooks`) sit at zero coverage until the 2026-07-29 measurement
that produced NPDevSamples/dsl-conformance-max: a change breaking any of those would have passed the
corpus-parse gate 29/29, because nothing used them. The fixture closes today's gaps; this gate is why
a feature added tomorrow can't be added and never fixtured (CONTRIBUTING.md's standing rule made
mechanical).

Scans every model.json in the corpus (AppGen/apps + NPDevSamples, same universe as
validate-corpus.py) for each tracked feature's presence and reports coverage; fails on any feature
with zero models, unless allowlisted with a reason + REG id (same allowlist discipline as
security-pattern-sweep.py / check-test-task-coverage.py).

Sequenced after F4 (docs/FINAL_OPEN_ITEMS_PLAN.md): generatedAction was unreachable until F4 fixed
FlowValidation, so this gate's target set assumes that fix has already landed.

Why this is not redundant with the platform's existing unit tests (G3, docs/CLOSEOUT_PLAN.md): a
measured 65 test files across NPDevGenerator/NPDevRuntimeHost/NPDevKernel/NPDevContract hand-build
`CompiledModel`/`CompiledFlow` objects directly, bypassing `JsonModelParser`/`SemanticValidator`
entirely. Those tests prove the compiled contract -- given a compiled shape, does the
emitter/runtime do the right thing -- and are correct and fast for exactly that. They cannot,
by construction, prove that a real `model.json` can PRODUCE that shape. That gap is exactly what let
`generatedAction` sit unreachable from authoring for as long as it existed (REG-65): a packaged-app
runtime-proof test passed the whole time by hand-constructing the compiled step directly, while
`FlowValidation` rejected every authored model that tried to express one. This script closes the
authoring-side half of that seam; `check-dsl-conformance-generates.py` (G2) closes the
generation-side half (parsing is not the same as emitting).

    python check-dsl-coverage.py
    python check-dsl-coverage.py --calibrate
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_APPGEN_ROOT = Path(r"D:\WorkSpace\NPDev\AppGen\apps")
DEFAULT_SAMPLES_ROOT = REPO_ROOT / "NPDevSamples"
ALLOWLIST_PATH = REPO_ROOT / "scripts" / "quality" / "dsl-coverage-allowlist.json"

FLOW_STEP_TYPES = (
    "invariantCheck", "capabilityCall", "generatedAction", "emitEvent", "scheduleEvent",
    "return", "branch", "awaitEvent", "createConcept", "updateConcept", "map", "forEach",
    "callProcedure",
)


def _walk_steps(steps):
    for step in steps or []:
        if not isinstance(step, dict):
            continue
        yield step
        for key in ("then", "else", "steps", "onFailure"):
            yield from _walk_steps(step.get(key))


def _all_steps(model: dict):
    for flow in model.get("flows", None) or []:
        if not isinstance(flow, dict):
            continue
        yield from _walk_steps(flow.get("steps"))
        for hook in flow.get("hooks", None) or []:
            if isinstance(hook, dict):
                yield from _walk_steps(hook.get("steps"))


def _all_procedure_steps(model: dict):
    for procedure in model.get("procedures", None) or []:
        if not isinstance(procedure, dict):
            continue
        yield from _walk_steps(procedure.get("steps"))


def _has_step_type(model: dict, step_type: str) -> bool:
    return any(str(s.get("type", "")).lower() == step_type.lower() for s in _all_steps(model))


def _has_procedure_step_type(model: dict, step_type: str) -> bool:
    return any(str(s.get("type", "")).lower() == step_type.lower() for s in _all_procedure_steps(model))


def _has_aggregate_on_commit(model: dict) -> bool:
    return any(
        isinstance(a, dict) and a.get("onCommit")
        for a in (model.get("aggregates", None) or [])
    )


def _has_procedure_create_if_missing(model: dict) -> bool:
    return any(
        str(s.get("type", "")).lower() == "patchconcept" and s.get("createIfMissing")
        for s in _all_procedure_steps(model)
    )


def _has_on_failure(model: dict) -> bool:
    return any("onFailure" in s and s["onFailure"] for s in _all_steps(model))


def _flows(model: dict):
    return [f for f in (model.get("flows", None) or []) if isinstance(f, dict)]


def _nonempty(model: dict, key: str) -> bool:
    value = model.get(key)
    if value is None:
        return False
    if isinstance(value, (list, dict)):
        return len(value) > 0
    return True


FEATURE_DETECTORS = {
    "externalAi": lambda m: "externalAi" in m,
    "domainTypes": lambda m: _nonempty(m, "domainTypes"),
    "selectors": lambda m: _nonempty(m, "selectors"),
    "aggregates": lambda m: _nonempty(m, "aggregates"),
    "autoPanels": lambda m: _nonempty(m, "autoPanels"),
    "documents": lambda m: _nonempty(m, "documents"),
    "guidePages": lambda m: _nonempty(m, "guidePages"),
    "queries": lambda m: _nonempty(m, "queries"),
    "procedures": lambda m: _nonempty(m, "procedures"),
    "panels": lambda m: _nonempty(m, "panels"),
    "ruleProfiles": lambda m: _nonempty(m, "ruleProfiles"),
    "fragments": lambda m: _nonempty(m, "fragments"),
    "packs": lambda m: _nonempty(m, "packs"),
    "flow.schedule": lambda m: any("schedule" in f for f in _flows(m)),
    "flow.specializes": lambda m: any("specializes" in f for f in _flows(m)),
    "flow.hooks": lambda m: any(f.get("hooks") for f in _flows(m)),
    "step.onFailure": _has_on_failure,
    **{f"step.{t}": (lambda m, t=t: _has_step_type(m, t)) for t in FLOW_STEP_TYPES},
    # Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): procedure.patchConcept and aggregate.onCommit
    # are new features, not caught by the flow-only _all_steps() above -- a procedure's steps live
    # under "procedures", not "flows". Tracked separately so a regression to either has the same
    # zero-coverage-fails-the-build guarantee as every other feature this gate already tracks.
    "procedure.patchConcept": lambda m: _has_procedure_step_type(m, "patchConcept"),
    "aggregate.onCommit": _has_aggregate_on_commit,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): patchConcept's create-if-missing opt-in
    # (the create half of REG-77) is a boolean flag on an existing step type, not a new step type
    # itself -- tracked separately so a regression to just this flag still fails the build.
    "procedure.createIfMissing": _has_procedure_create_if_missing,
}


def find_models(appgen_root: Path, samples_root: Path) -> list[tuple[str, Path]]:
    """Mirrors validate-corpus.py's own find_models() label convention exactly -- including its
    Output-dir exclusion (docs/CLOSEOUT_PLAN.md G2 aftermath: a generated model.json copy under
    NPDevSamples/**/Output/ must never enter the tracked corpus; see that function's own docstring)."""
    models: list[tuple[str, Path]] = []
    if appgen_root.exists():
        for p in sorted(appgen_root.rglob("model.json")):
            if "Output" in p.relative_to(appgen_root).parts:
                continue
            rel = p.relative_to(appgen_root).parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"AppGen/apps/{app}", p))
    if samples_root.exists():
        for p in sorted(samples_root.rglob("model.json")):
            if "Output" in p.relative_to(samples_root).parts:
                continue
            rel = p.relative_to(samples_root).parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"NPDevSamples/{app}", p))
    return models


def load_allowlist() -> dict:
    if not ALLOWLIST_PATH.exists():
        return {}
    return json.loads(ALLOWLIST_PATH.read_text(encoding="utf-8")).get("cleared", {})


def coverage(models: list[tuple[str, Path]]) -> dict[str, list[str]]:
    """feature -> list of corpus labels that use it (empty list = zero coverage)."""
    result: dict[str, list[str]] = {f: [] for f in FEATURE_DETECTORS}
    for label, path in models:
        try:
            model = json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            continue
        if not isinstance(model, dict):
            continue
        for feature, detector in FEATURE_DETECTORS.items():
            try:
                if detector(model):
                    result[feature].append(label)
            except Exception:
                continue
    return result


def calibrate() -> int:
    """Must FAIL on a feature no model uses, PASS on one at least one model uses."""
    ok = True
    with tempfile.TemporaryDirectory(prefix="npdev-dsl-coverage-calibrate-") as tmp:
        tmp_path = Path(tmp)
        used = tmp_path / "used.json"
        used.write_text(json.dumps({
            "flows": [{"name": "F", "steps": [{"name": "s", "type": "forEach",
                                                 "collection": "x", "itemKey": "i", "steps": []}]}]
        }), encoding="utf-8")
        models = [("calibrate/used", used)]
        cov = coverage(models)

        def report(label: str, feature: str, expect_fail: bool) -> None:
            nonlocal ok
            fired = len(cov.get(feature, [])) == 0
            passed = fired == expect_fail
            ok = ok and passed
            print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

        print("Calibration -- must catch a zero-coverage feature, pass a covered one:")
        report("step.forEach (used by the fixture above)", "step.forEach", expect_fail=False)
        report("selectors (unused by the fixture above)", "selectors", expect_fail=True)

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--appgen-root", default=str(DEFAULT_APPGEN_ROOT))
    ap.add_argument("--samples-root", default=str(DEFAULT_SAMPLES_ROOT))
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    appgen_root = Path(args.appgen_root)
    samples_root = Path(args.samples_root)
    models = find_models(appgen_root, samples_root)
    allowlist = load_allowlist()
    cov = coverage(models)

    print(f"DSL coverage check: {len(models)} model(s) scanned, {len(FEATURE_DETECTORS)} feature(s) tracked.\n")
    width = max(len(f) for f in FEATURE_DETECTORS)
    zero = []
    for feature in sorted(FEATURE_DETECTORS):
        users = cov[feature]
        count = len(users)
        marker = "OK" if count else ("ALLOWED" if feature in allowlist else "ZERO")
        example = users[0] if users else (allowlist.get(feature, {}).get("why", "") if feature in allowlist else "")
        print(f"  {feature.ljust(width)}  {str(count).rjust(2)} model(s)  [{marker}]  {example}")
        if count == 0 and feature not in allowlist:
            zero.append(feature)

    if zero:
        print(f"\nFAIL: {len(zero)} feature(s) have zero corpus coverage and are not allowlisted:", file=sys.stderr)
        for f in zero:
            print(f"  - {f}", file=sys.stderr)
        print(f"\nAdd a real example to NPDevSamples/dsl-conformance-max (CONTRIBUTING.md's standing "
              f"rule), or record a reviewed exception with a reason + REG id in {ALLOWLIST_PATH}.",
              file=sys.stderr)
        return 1
    print("\nOK: every tracked DSL feature is exercised by at least one corpus model.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
