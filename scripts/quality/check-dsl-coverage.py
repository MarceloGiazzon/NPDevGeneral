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

QUAL-1 (2026-08-09): this file was 913 lines against a 400-line budget. The detectors, the tracked-
feature table, and corpus discovery now live in `dsl_coverage/`; this file keeps the gate itself.
Every moved body is BYTE-FOR-BYTE identical, and the split is proven by a captured before/after diff
of the full corpus output in both modes -- a split that silently drops one detector is worse than a
long file, because a coverage gate that stops covering something is invisible by construction.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dsl_coverage.constants import (  # noqa: E402 - path must be set first
    ALLOWLIST_PATH, DEFAULT_APPGEN_ROOT, DEFAULT_SAMPLES_ROOT, REPO_ROOT,
)
from dsl_coverage.corpus import (  # noqa: E402
    _merge_context_fragments, find_models, load_allowlist,
)
from dsl_coverage.features import FEATURE_DETECTORS  # noqa: E402



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
        model = _merge_context_fragments(model, path.parent)
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


# The number of features this gate tracks, as a FLOOR. Raise it when you add one.
#
# Found while RED-proving the QUAL-1 split: deleting a detector from the FEATURE_DETECTORS table
# does not fail this gate. It reports "84 feature(s) tracked" instead of 85 and exits OK, because
# every remaining feature is still covered -- the check answers "is each TRACKED feature exercised?"
# and a feature that stops being tracked stops being asked about. That hole predates the split (the
# split is simply the first thing that ever tried to remove a detector), and it is the failure mode
# the split was warned about: a coverage gate that stops covering something is invisible by
# construction. So the count is now asserted, in the same spirit as
# check-dsl-reference-output-floor.py.
TRACKED_FEATURE_FLOOR = 86


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--appgen-root", default=str(DEFAULT_APPGEN_ROOT))
    ap.add_argument("--samples-root", default=str(DEFAULT_SAMPLES_ROOT))
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if len(FEATURE_DETECTORS) < TRACKED_FEATURE_FLOOR:
        print(f"FAIL: {len(FEATURE_DETECTORS)} feature(s) tracked, floor is {TRACKED_FEATURE_FLOOR}. "
              f"A detector was removed from the table -- which SILENTLY narrows what this gate "
              f"covers, because it only asks about features it tracks. Restore it, or lower the "
              f"floor deliberately and say why.")
        return 1

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
