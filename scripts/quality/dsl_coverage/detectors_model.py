"""Feature detectors that read the MODEL: concepts, fields, flows, procedures, queries.

Bodies moved byte-for-byte out of `check-dsl-coverage.py` (QUAL-1). Retyping a detector is exactly
how a split silently changes what a gate covers.
"""
from __future__ import annotations

from .constants import FLOW_STEP_TYPES

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


def _has_renamed_field(model: dict) -> bool:
    """D1 (FIRST_IMPRESSION_PLAN.md I5): a field declaring renamedFrom -- the mechanism that keeps
    a rename from being read as drop+add (a destructive change NPDev correctly refuses). Zero
    corpus coverage until this gate started tracking it, discovered while writing the authoring
    contract's own D1 section (docs/ai/AUTHORING_FOR_AI.md), which cites this as its witness."""
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if isinstance(field, dict) and field.get("renamedFrom"):
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


def _has_decimal_field(model: dict) -> bool:
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if isinstance(field, dict) and str(field.get("type", "")).lower() == "decimal":
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


def _groupby_field_text(entry) -> str:
    """A query.groupBy[] entry is either a plain string or {"field": ..., "bucket": ...} --
    S4 (roadmap B27, ADR-0011 D1) join detection needs the field TEXT regardless of shape."""
    if isinstance(entry, str):
        return entry
    if isinstance(entry, dict) and isinstance(entry.get("field"), str):
        return entry["field"]
    return ""


def _has_groupby_join(model: dict) -> bool:
    """S4 (roadmap B27, ADR-0011 D1): a groupBy field naming a one-hop join
    ("referenceField.targetField"), same-context or unqualified -- distinct from the plain
    "query.groupBy" feature (a bare field) already tracked above."""
    return any(
        "." in _groupby_field_text(entry)
        for q in (model.get("queries", None) or [])
        for entry in (q.get("groupBy", None) or [])
    )


def _has_groupby_cross_context_join(model: dict) -> bool:
    """S4 (roadmap B27, ADR-0011 D1, C1): a groupBy join whose reference field crosses an explicit
    context:: boundary ("inventory::lote.produtoId") -- the shape B20 was sequenced first to make
    unambiguous. Distinct from the same-context join above so a regression to just the
    cross-context parsing/import-gate path independently fails the build."""
    return any(
        "::" in (text := _groupby_field_text(entry)) and "." in text
        for q in (model.get("queries", None) or [])
        for entry in (q.get("groupBy", None) or [])
    )


def _has_groupby_multi_hop_join(model: dict) -> bool:
    """S8 W1.1 (roadmap deferred item #1): a groupBy join chaining MORE than one reference-field hop
    ("shipment.invoice.status" -- two hops, not one) -- distinct from the plain one-hop
    "query.groupBy.join" feature above so a regression to only the one-hop grammar path
    independently fails the build."""
    for q in (model.get("queries", None) or []):
        for entry in (q.get("groupBy", None) or []):
            text = _groupby_field_text(entry)
            remainder = text.split("::", 1)[-1] if "::" in text else text
            if remainder.count(".") >= 2:
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


def _has_settings(model: dict) -> bool:
    # Move 6 Move A (docs/MOVE6_TYPED_SURFACE_PLAN.md §2): the typed top-level app settings block
    # (locale/strings/ui) -- retires the untyped mix of platform-default English and hardcoded
    # Portuguese literal fallbacks a generated app's Aggregate Workbench page used to render.
    settings = model.get("settings")
    return isinstance(settings, dict) and len(settings) > 0


def _has_on_failure(model: dict) -> bool:
    return any("onFailure" in s and s["onFailure"] for s in _all_steps(model))


def _has_parallel_await_foreach(model: dict) -> bool:
    """S6 (B15(B), docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): a forEach step opting into N-way
    parallel waiting -- distinct from the plain "step.forEach" feature (any forEach) and from
    B15(A)'s sequential await-in-loop, which has no DSL-level marker of its own."""
    return any(
        str(s.get("type", "")).lower() == "foreach" and s.get("parallelAwait") is True
        for s in _all_steps(model)
    )


def _has_parallel_await_multistep_foreach(model: dict) -> bool:
    """S8 Wave 3 (S8_DEFERRED_FIVE_PLAN.md, 2026-08-04, I5): a parallelAwait forEach body widened
    from EXACTLY one await step to any number of other steps before/after it -- distinct from the
    plain "step.forEach.parallelAwait" feature above (which a single-await body already satisfies),
    same discipline as "query.groupBy.join.multiHop" tracking the widened case separately from the
    base feature it widened."""
    for step in _all_steps(model):
        if str(step.get("type", "")).lower() != "foreach" or step.get("parallelAwait") is not True:
            continue
        loop_steps = step.get("steps", None) or []
        if len(loop_steps) > 1:
            return True
    return False


def _flows(model: dict):
    return [f for f in (model.get("flows", None) or []) if isinstance(f, dict)]


def _nonempty(model: dict, key: str) -> bool:
    value = model.get(key)
    if value is None:
        return False
    if isinstance(value, (list, dict)):
        return len(value) > 0
    return True


def _has_conversion_op(model: dict, op: str) -> bool:
    return any(
        isinstance(c, dict) and c.get("op") == op
        for c in (model.get("conversions", None) or [])
    )
