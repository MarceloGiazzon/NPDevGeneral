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


def _has_panel_action_concept_query(model: dict) -> bool:
    for panel in (model.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        for action in (panel.get("actions", None) or []):
            if isinstance(action, dict) and str(action.get("binding", "")).lower() == "conceptquery":
                return True
    return False


def _has_date_field(model: dict) -> bool:
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if isinstance(field, dict) and str(field.get("type", "")).lower() == "date":
                return True
    return False


def _has_flow_io_schema(model: dict) -> bool:
    return any(
        isinstance(f, dict) and (f.get("inputSchema") or f.get("outputSchema"))
        for f in _flows(model)
    )


def _has_concept_extends(model: dict) -> bool:
    return any(
        isinstance(c, dict) and c.get("extends")
        for c in (model.get("concepts", None) or [])
    )


def _has_post_checkpoint(model: dict) -> bool:
    return any(
        str(s.get("type", "")).lower() == "invariantcheck"
        and str(s.get("checkpoint", "") or s.get("phase", "")).lower() == "post"
        for s in _all_steps(model)
    )


def _has_sensitive_field(model: dict) -> bool:
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if isinstance(field, dict) and field.get("sensitive"):
                return True
    return False


def _has_concept_access(model: dict) -> bool:
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        access = concept.get("access")
        if isinstance(access, dict) and (access.get("read") or access.get("write")):
            return True
    return False


def _has_composite_index(model: dict) -> bool:
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for index in (concept.get("indexes", None) or []):
            if isinstance(index, dict) and len(index.get("fields", None) or []) >= 2:
                return True
    return False


def _has_file_field(model: dict) -> bool:
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if isinstance(field, dict) and str(field.get("type", "")).lower() == "file":
                return True
    return False


def _has_flow_start_endpoint(model: dict) -> bool:
    return any(isinstance(f, dict) and f.get("startEndpoint") for f in _flows(model))


def _has_capability_policy(model: dict) -> bool:
    if any(isinstance(s.get("policy"), dict) and s["policy"] for s in _all_steps(model)):
        return True
    for capability in (model.get("capabilities", None) or []):
        if not isinstance(capability, dict):
            continue
        for operation in (capability.get("operations", None) or []):
            if isinstance(operation, dict) and isinstance(operation.get("policy"), dict) and operation["policy"]:
                return True
    return False


def _has_aggregate_on_commit(model: dict) -> bool:
    return any(
        isinstance(a, dict) and a.get("onCommit")
        for a in (model.get("aggregates", None) or [])
    )


def _has_panel_action_download(model: dict) -> bool:
    for panel in (model.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        for action in (panel.get("actions", None) or []):
            if isinstance(action, dict) and action.get("resultAs") == "download":
                return True
    return False


def _has_aggregate_on_validate(model: dict) -> bool:
    return any(
        isinstance(a, dict) and a.get("onValidate")
        for a in (model.get("aggregates", None) or [])
    )


def _has_procedure_create_if_missing(model: dict) -> bool:
    return any(
        str(s.get("type", "")).lower() == "patchconcept" and s.get("createIfMissing")
        for s in _all_procedure_steps(model)
    )


def _workbench_transaction_metadatas(model: dict):
    for auto_panel in (model.get("autoPanels", None) or []):
        if not isinstance(auto_panel, dict):
            continue
        transaction = auto_panel.get("transaction")
        if not isinstance(transaction, dict):
            continue
        metadata = transaction.get("metadata")
        if isinstance(metadata, dict):
            yield metadata


def _has_workbench_apply_to(model: dict) -> bool:
    for metadata in _workbench_transaction_metadatas(model):
        for action in (metadata.get("actions", None) or []):
            if isinstance(action, dict) and isinstance(action.get("applyTo"), dict):
                return True
    return False


def _has_workbench_derived(model: dict) -> bool:
    for metadata in _workbench_transaction_metadatas(model):
        if metadata.get("derived", None):
            return True
    return False


def _has_workbench_visible_when(model: dict) -> bool:
    for metadata in _workbench_transaction_metadatas(model):
        if isinstance(metadata.get("visibleWhen"), dict) and metadata["visibleWhen"]:
            return True
        for action in (metadata.get("actions", None) or []):
            if isinstance(action, dict) and action.get("visibleWhen"):
                return True
    return False


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
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): mapList produces a NEW list
    # (one output object per input item), unlike forEach which only iterates for side effects.
    "procedure.mapList": lambda m: _has_procedure_step_type(m, "mapList"),
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): capabilityPolicy (retry/timeout/circuit/
    # bulkhead/idempotency) -- previously zero declarations anywhere in the corpus; the circuit/
    # bulkhead halves were also found to be silently dropped by the compiler (fixed alongside).
    "capabilityPolicy": _has_capability_policy,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): flow.startEndpoint publishes a real
    # /generated/flows/{name}/start REST route (already proven by a packaged-app runtime test in
    # the generator module) -- zero declarations in the corpus itself before this.
    "flow.startEndpoint": _has_flow_start_endpoint,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): a field with type:file (upload/download/
    # delete-cascade all fully proven by existing packaged-app tests in NPDevGenerator; this just
    # closes the corpus-witness gap -- WmsOffice's DocumentoFiscal.arquivo already qualifies).
    "type.file": _has_file_field,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): a genuinely composite (2+ field) unique
    # index -- REG-58 found H2/Postgres refuse to silently drop a column referenced by a COMPOSITE
    # index, a shape a single-column repro failed to reproduce. Tracked as its own feature
    # (distinct from a plain single-column concept.indexes[] entry) so a regression to just the
    # multi-field case still fails the build.
    "concept.compositeIndex": _has_composite_index,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): declarative row-level authz
    # (concept.access.read/write) -- confirmed genuinely wired end-to-end (DefaultConceptGateway's
    # isRowReadable/isRowWritable, fail-closed on a malformed expression), just never declared by
    # any real/fixture model before this.
    "concept.access": _has_concept_access,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): an explicit "post" checkpoint on an
    # invariantCheck step -- step.invariantCheck itself has broad corpus coverage already, but every
    # existing declaration relies on JsonModelParser's implicit "pre" default (scope declared, no
    # explicit checkpoint), so "post" itself, and explicit declaration of either value, had zero
    # witnesses.
    "invariant.checkpoint.post": _has_post_checkpoint,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): concept.extends (field-merge inheritance)
    # -- already covered at the unit level by DslSpecializationTest (field merge, missing-parent
    # error, cycle detection), just never declared in a real/fixture corpus model before this.
    "concept.extends": _has_concept_extends,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): flow.inputSchema/outputSchema (the
    # external contract for a startEndpoint flow) -- already unit-tested (DslFlowModelTest), just
    # never declared in a real/fixture corpus model before this.
    "flow.ioSchema": _has_flow_io_schema,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): a field with type:date -- WmsOffice
    # already has 8 real usages (CrossDocking/DocumentoFiscal/Expedicao/InventarioArquivo/Lote/
    # Movimento/Recebimento/Romaneio), but they live in $ref'd-out concepts/*.json files this
    # gate's naive model loader can't see; a dsl-conformance-max fixture makes it a real witness.
    "type.date": _has_date_field,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): panelAction.binding=conceptQuery -- zero
    # declarations AND zero test coverage anywhere before this (unlike most other Wave 5 items);
    # PanelRuntimeConceptQueryActionTest (RuntimeHost) now proves it end-to-end.
    "panelAction.conceptQuery": _has_panel_action_concept_query,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): field.sensitive -- found to be dead wiring
    # (REG-80, ledger/items/REG-80.yml): parsed/compiled/round-tripped but consumed by nothing,
    # including its own documented external-AI-review-pack redaction purpose. Tracked here to protect
    # the round-trip itself from regressing while the real consumption gap (REG-80) stays open.
    "constraint.sensitive": _has_sensitive_field,
    "aggregate.onCommit": _has_aggregate_on_commit,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3B / Gap 8): onValidate is a sibling of
    # onCommit, not a flag on it -- tracked separately so a regression to just this field still
    # fails the build.
    "aggregate.onValidate": _has_aggregate_on_validate,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 4 / Gap 7): a panelAction's resultAs
    # ("download") -- inventario.html's Gerar Template had no declared surface for this before.
    "panelAction.resultAs.download": _has_panel_action_download,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): patchConcept's create-if-missing opt-in
    # (the create half of REG-77) is a boolean flag on an existing step type, not a new step type
    # itself -- tracked separately so a regression to just this flag still fails the build.
    "procedure.createIfMissing": _has_procedure_create_if_missing,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 2A): a Workbench transaction action's applyTo
    # mapping lives inside the untyped autoPanel.transaction.metadata.actions[] blob (not a schema
    # property -- regular, non-Workbench panels have no client-held draft to fold a result into),
    # so this walks that structure directly rather than reusing a step/field-type detector.
    "workbench.applyTo": _has_workbench_apply_to,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 2B): same untyped-metadata mechanism as
    # applyTo above -- a declared derived display field (M6's "balanced banner").
    "workbench.derived": _has_workbench_derived,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 2C / Gap 2): conditional surface by toggle --
    # transaction.metadata.visibleWhen (collections/bands) or an action's own visibleWhen key.
    "workbench.visibleWhen": _has_workbench_visible_when,
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
