"""Feature detectors that read the MODEL: concepts, fields, flows, procedures, queries.

Bodies moved byte-for-byte out of `check-dsl-coverage.py` (QUAL-1). Retyping a detector is exactly
how a split silently changes what a gate covers.
"""
from __future__ import annotations

import re

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


def _has_field_access(model: dict) -> bool:
    # R5.5: field-level authorization (field.access.read/write) -- the next rung on the ladder
    # below concept.access (row scope): role ceiling -> row scope -> field scope.
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if not isinstance(field, dict):
                continue
            access = field.get("access")
            if isinstance(access, dict) and (access.get("read") or access.get("write")):
                return True
    return False


_LABEL_SITE_KEYS = ("label", "shortLabel", "displayLabel", "actionLabel")


def _is_locale_label_object(value) -> bool:
    """R5.6: a widened label site -- the object form `{"default": "...", "<locale>": "...", ...}`
    the schema's `$defs/localizableLabel` accepts alongside the still-valid plain string. Detected
    structurally (dict with a "default" key), not by checking for any specific locale tag, since
    the whole point is that authors declare whatever locale tags they need."""
    return isinstance(value, dict) and isinstance(value.get("default"), str) and value.get("default").strip() != ""


def _iter_label_site_values(obj):
    if isinstance(obj, dict):
        for key in _LABEL_SITE_KEYS:
            if key in obj:
                yield obj[key]


def _has_locale_label(model: dict) -> bool:
    """R5.6: true when at least one label site anywhere in the model uses the per-locale object
    form rather than a plain string -- proves the widened schema/DSL/canonical-JSON chain actually
    carries a locale map, not just that a plain string still parses (which every OTHER model in
    the corpus already proves and would make this detector vacuous)."""
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        ui = concept.get("ui")
        if isinstance(ui, dict):
            for value in _iter_label_site_values(ui):
                if _is_locale_label_object(value):
                    return True
        for field in (concept.get("fields", None) or []):
            if not isinstance(field, dict):
                continue
            field_ui = field.get("ui")
            if isinstance(field_ui, dict):
                for value in _iter_label_site_values(field_ui):
                    if _is_locale_label_object(value):
                        return True
            for option in (field.get("enumValues", None) or []):
                if isinstance(option, dict):
                    for value in _iter_label_site_values(option):
                        if _is_locale_label_object(value):
                            return True
        lifecycle = concept.get("lifecycle")
        if isinstance(lifecycle, dict):
            for state in (lifecycle.get("states", None) or []):
                if isinstance(state, dict):
                    for value in _iter_label_site_values(state):
                        if _is_locale_label_object(value):
                            return True
            for transition in (lifecycle.get("transitions", None) or []):
                if isinstance(transition, dict):
                    for value in _iter_label_site_values(transition):
                        if _is_locale_label_object(value):
                            return True
    for prop in (model.get("properties", None) or []):
        if isinstance(prop, dict):
            for value in _iter_label_site_values(prop):
                if _is_locale_label_object(value):
                    return True
    for panel in (model.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        for action in (panel.get("actions", None) or []):
            if isinstance(action, dict):
                for value in _iter_label_site_values(action):
                    if _is_locale_label_object(value):
                        return True
    for autopanel in (model.get("autoPanels", None) or []):
        if not isinstance(autopanel, dict):
            continue
        for surface_name in ("selection", "detail", "transaction", "prompt"):
            surface = autopanel.get(surface_name)
            if not isinstance(surface, dict):
                continue
            for action in (surface.get("actions", None) or []):
                if isinstance(action, dict):
                    for value in _iter_label_site_values(action):
                        if _is_locale_label_object(value):
                            return True
            for bucket_key in ("bandPickers", "derivedFields", "uiState"):
                bucket = surface.get(bucket_key)
                if isinstance(bucket, dict):
                    for entry in bucket.values():
                        if isinstance(entry, dict):
                            for value in _iter_label_site_values(entry):
                                if _is_locale_label_object(value):
                                    return True
    return False


def _has_concept_soft_delete(model: dict) -> bool:
    """R5.4 (Roadmap Collection 2026-08-18): a concept declaring softDelete: true -- deletedAt-flip
    delete, deleted-row-excluding reads, and unique-among-live-rows semantics instead of a physical
    DELETE."""
    return any(
        isinstance(c, dict) and c.get("softDelete") is True
        for c in (model.get("concepts", None) or [])
    )


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


def _has_query_where_v2(model: dict) -> bool:
    """R4.3 lockstep fix (Roadmap Wave 1 gap closure): a query.where using the v2 predicate grammar
    (QueryPredicateGrammar#parseGroups) -- an OR-group (||), `in (...)`, `contains`/`startsWith`, or
    `is null`/`is not null` -- distinct from the plain v1 AND-only "queries" feature already tracked
    above. PackValidation#validateQueryWhereCompiles only started accepting this grammar once
    DefaultProcedureExecutor#runQuery was rewired onto ConceptQueryPredicateCompiler
    #compileToConceptQueryFilters in the same change; a where using v2 syntax with zero corpus
    coverage would have let that lockstep silently regress back to v1-only with no gate noticing."""
    for q in (model.get("queries", None) or []):
        where = q.get("where")
        if not isinstance(where, str) or not where.strip():
            continue
        if "||" in where:
            return True
        for keyword in (" in (", " contains ", " startsWith ", " is null", " is not null"):
            if keyword in where:
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


def _schedule_event_delay_seconds(step: dict):
    """The delay a scheduleEvent step resolves to, mirroring JsonModelParser's own precedence
    (delaySeconds, else delayMinutes * 60, else delayMs rounded up). Returns None when the step
    declares no delay at all -- which FlowValidation rejects, so it only happens mid-edit."""
    raw = step.get("delaySeconds")
    if isinstance(raw, (int, float)) and not isinstance(raw, bool):
        return int(raw)
    raw = step.get("delayMinutes")
    if isinstance(raw, (int, float)) and not isinstance(raw, bool):
        return int(raw) * 60
    raw = step.get("delayMs")
    if isinstance(raw, (int, float)) and not isinstance(raw, bool):
        return max(0, (int(raw) + 999) // 1000)
    return None


def _has_schedule_event_with_delay(model: dict, deferred: bool) -> bool:
    """R2.4: the two delivery modes of scheduleEvent are now genuinely different code paths, so
    each needs its own corpus witness -- the plain "step.scheduleEvent" feature is satisfied by
    either one alone. A delay > 0 writes a durable npdev_scheduled_event row and publishes nothing
    until it comes due; a delay of 0 still publishes inline and resumes waiters synchronously.
    Before R2.4 both spellings did the same thing (fire now, label it scheduled), which is exactly
    why the corpus only ever carried the deferred spelling and nothing noticed it was not deferred.
    """
    for step in _all_steps(model):
        if str(step.get("type", "")).lower() != "scheduleevent":
            continue
        delay = _schedule_event_delay_seconds(step)
        if delay is None:
            continue
        if (delay > 0) == deferred:
            return True
    return False


def _has_await_timeout(model: dict) -> bool:
    """R2.5 (durable await timeouts + onTimeout): an awaitEvent/awaitMatch step declaring a
    durable wait deadline plus its escalation branch -- distinct from the plain "step.awaitEvent"
    feature (satisfied by any await, timed or not), the same reasoning "step.onFailure" already
    applies to onFailure. Requires BOTH timeout and a non-empty onTimeout, matching
    FlowValidation.validateAwaitStep's own pairing rule (onTimeout without timeout is rejected;
    timeout without onTimeout is legal but leaves the escalation half of this feature unexercised).
    A regression that silently drops `timeout`/`onTimeout` from the compiled model on their way to
    the generator's canonical JSON (REG-104's exact shape) would go unnoticed without this.
    """
    return any(
        str(s.get("type", "")).lower() == "awaitevent" and s.get("timeout") and s.get("onTimeout")
        for s in _all_steps(model)
    )


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


_QUOTED_LITERAL = re.compile(r"'[^']*'|\"[^\"]*\"")
_ARITHMETIC_OPERATORS = ("+", "-", "*", "/", "%")


def _has_arithmetic_derived_expression(model: dict) -> bool:
    """R4.1 (roadmap): a field's default/derivedExpression exercising the arithmetic grammar
    (+ - * / %) -- e.g. "quantity * unitPrice" -- rather than the identifier/literal/five-
    whitelisted-string-function shape FieldValueValidation.analyzeValueBehaviorExpression used to
    cap author-time validation at. That validator now delegates to the same ComputedExpression
    grammar the runtime (SchemaExpressionSupport.evaluateSchemaExpression, called from
    GeneratedCrudRuntimeSupport.applySchemaValueBehaviors) already evaluated for every default/
    derived expression -- this was a validator-only ceiling, not a runtime gap. Zero corpus
    coverage before this: every existing default/derivedExpression example in the corpus used only
    concat/coalesce/trim/uppercase/lowercase or a bare literal/identifier. Strips quoted string
    literals first so an operator character inside a string argument (e.g. concat(a, '-', b))
    doesn't produce a false positive.
    """
    for concept in (model.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        for field in (concept.get("fields", None) or []):
            if not isinstance(field, dict):
                continue
            for key in ("defaultExpression", "derivedExpression"):
                expr = field.get(key)
                if not isinstance(expr, str) or not expr.strip():
                    continue
                stripped = _QUOTED_LITERAL.sub("", expr)
                if any(op in stripped for op in _ARITHMETIC_OPERATORS):
                    return True
    return False


_ORDERED_COMPARISON_OPERATORS = ("&&", "||", ">=", "<=", ">", "<")


def _has_widened_branch_condition(model: dict) -> bool:
    """R4.2 (roadmap): a flow BRANCH step's {@code condition} exercising the ComputedExpression
    grammar KernelRunner.evaluateCondition widened to (&&, ||, or an ordered comparison) rather
    than the {@code ==}/{@code !=}-only shape every prior corpus branch condition used. Zero corpus
    coverage before this: every existing branch step in the corpus (dsl-conformance-max's own
    "step.branch" example included) used a bare equality/truthy check. Strips quoted string
    literals first so an operator character inside a string operand doesn't produce a false
    positive, then checks for `>=`/`<=` before the bare `>`/`<` they contain so a plain `>=` is not
    also miscounted as a lone `>` -- moot for this any()-over-tuple check today, but keeps the tuple
    order meaningful if a future caller ever needs the specific operator matched.
    """
    for step in _all_steps(model):
        if str(step.get("type", "")).lower() != "branch":
            continue
        condition = step.get("condition")
        if not isinstance(condition, str) or not condition.strip():
            continue
        stripped = _QUOTED_LITERAL.sub("", condition)
        if any(op in stripped for op in _ORDERED_COMPARISON_OPERATORS):
            return True
    return False


def _has_conversion_op(model: dict, op: str) -> bool:
    return any(
        isinstance(c, dict) and c.get("op") == op
        for c in (model.get("conversions", None) or [])
    )
