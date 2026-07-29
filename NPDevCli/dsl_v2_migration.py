"""DSL 2.0 vocabulary migration (2.A.3, docs/DSL2_AND_DECOMPOSITION_PLAN.md).

Rewrites a raw, author-facing NPDev model document's `flowStep.type` spellings and field
aliases to their DSL 2.0 canonical form (see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.A.1 for the
full naming table and its rationale). This module is pure transformation logic with no I/O of
its own beyond what the caller passes in -- `npdev_cli.py` wires it to the `migrate dsl-2`
subcommand and does the file-walking/reading/writing.

DESIGN PRINCIPLES (all load-bearing, don't relax any of them)
-----------------------------------------------------------------
1. Idempotent. Every mapping below includes the already-canonical spelling mapping to itself,
   so running this twice produces the exact same output as running it once.
2. Reports, does not guess. Two ambiguity classes are recognized and left untouched, surfaced in
   the returned `MigrationResult.ambiguities`:
     - a step's `type` (case-insensitively) matches neither a known alias nor an already-
       canonical name -- an unrecognized type is not silently passed through or dropped.
     - both an alias field and its canonical replacement are present with DIFFERENT values (if
       they hold the same value, the alias is simply redundant and gets dropped without comment).
     - `orchestrationRule` has both `action` and `actions` present -- which one is authoritative
       is not something this tool decides.
3. Structural, not a blind key-value replace. `type`/`cap`/`op`/`out`/etc. are common short names
   that collide across unrelated schema contexts (`orchestrationAction.type`'s own enum values
   `create`/`callCapability`/`scheduleEvent` are NOT flowStep-type aliases; a metadata manifest's
   own `targetConcept` field is unrelated to `orchestrationAction.targetConcept`). This module only
   ever touches fields inside recognized `flowStep`/`flowHook`/`orchestrationAction` shapes reached
   by walking `flows[].steps[]` (recursively into `then`/`else`/`steps`/`onFailure`), `flows[].hooks[]`,
   and `orchestrationRules[].action`/`.actions[]` -- never a global search-and-replace.
4. Refuses compiled-model fixtures. A handful of test fixtures (found: 2026-07-27, e.g.
   `NPDevRuntimeHost/src/test/resources/npdev/async-wait-resume-compiled-model.json`) are
   serialized *compiled* `CompiledFlowStep` records, not raw authored models -- despite having the
   same top-level `flows`/`orchestrationRules` keys, their step objects carry compiled-only field
   names (`capabilityCall` as a nested object, `awaitEventName`, `mapFromRef`/`mapToRef`,
   `returnValueRef`, `eventDataRefs`, `argsRefs`, `awaitPayloadMatch`) that look superficially
   similar to raw field names but mean something entirely different and must never be rewritten.
   Any step carrying one of these markers causes the WHOLE DOCUMENT to be classified `is_compiled`
   and left completely untouched (see `COMPILED_STEP_MARKER_FIELDS`).

5. Also renames the top-level `orchestrations` key to `orchestrationRules` (found: 2026-07-29,
   docs/CORPUS_INTEGRITY_PLAN.md C2, migrating `AppGen/apps/invoice-bonds-demo` for real -- this
   repo's schema history has never accepted any spelling but `orchestrationRules`, so this predates
   even this repo's own baseline commit rather than being a DSL-2.0-era rename; it was invisible to
   the original 2.A.6 corpus scan because that scan never covered `AppGen/apps`, the one tree where
   it turns up). Same ambiguity discipline as every other alias here: if both keys are present, left
   untouched and reported, never silently merged.
"""

from __future__ import annotations

from dataclasses import dataclass, field as dataclass_field

# --- Compiled-model detection -------------------------------------------------------------------

COMPILED_STEP_MARKER_FIELDS = frozenset({
    "capabilityCall", "awaitEventName", "mapFromRef", "mapToRef", "returnValueRef",
    "awaitPayloadMatch", "eventDataRefs", "argsRefs", "awaitMatchCorrelation",
})


def _looks_compiled(doc: dict) -> bool:
    """True if any step object anywhere in the document carries a compiled-only marker field."""
    found = False

    def walk_steps(steps) -> None:
        nonlocal found
        if not isinstance(steps, list):
            return
        for step in steps:
            if not isinstance(step, dict):
                continue
            if COMPILED_STEP_MARKER_FIELDS & step.keys():
                found = True
                return
            walk_steps(step.get("then"))
            walk_steps(step.get("else"))
            walk_steps(step.get("steps"))
            walk_steps(step.get("onFailure"))

    for flow in doc.get("flows", None) or []:
        if not isinstance(flow, dict):
            continue
        walk_steps(flow.get("steps"))
        if found:
            return True
        for hook in flow.get("hooks", None) or []:
            if isinstance(hook, dict):
                walk_steps(hook.get("steps"))
        if found:
            return True
    return False


# --- Canonical name tables (docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.A.1) ---------------------------

# flowStep.type: lowercased input -> DSL 2.0 canonical spelling. Includes every canonical name
# mapping to itself, which is what makes this idempotent.
STEP_TYPE_CANONICAL = {
    "validate": "invariantCheck",
    "invariant": "invariantCheck",
    "enforceinvariants": "invariantCheck",
    "evaluateinvariant": "invariantCheck",
    "invariantcheck": "invariantCheck",
    "capability": "capabilityCall",
    "callcapability": "capabilityCall",
    "capabilitycall": "capabilityCall",
    "generated_action": "generatedAction",
    "generatedaction": "generatedAction",
    "event": "emitEvent",
    "emitevent": "emitEvent",
    "scheduleevent": "scheduleEvent",
    "return": "return",
    "branch": "branch",
    "if": "branch",
    "await": "awaitEvent",
    "awaitevent": "awaitEvent",
    "waitforevent": "awaitEvent",
    "createconcept": "createConcept",
    "createentity": "createConcept",
    "conceptcreate": "createConcept",
    "updateconcept": "updateConcept",
    "updateentity": "updateConcept",
    "conceptupdate": "updateConcept",
    "assign": "map",
    "map": "map",
    "foreach": "forEach",
    "loop": "forEach",
}

# (alias_field, canonical_field) pairs rewritten on every flowStep object.
FLOW_STEP_FIELD_ALIASES = (
    ("cap", "capability"),
    ("op", "operation"),
    ("out", "output"),
    ("as", "awaitRef"),
)

# (alias_field, canonical_field) pairs rewritten on every flowHook object.
FLOW_HOOK_FIELD_ALIASES = (
    ("at", "position"),
    ("target", "targetStep"),
)

# (alias_field, canonical_field) pairs rewritten on every orchestrationAction object.
ORCHESTRATION_ACTION_FIELD_ALIASES = (
    ("targetConcept", "concept"),
    ("capabilityName", "capability"),
    ("eventName", "event"),
    ("op", "operation"),
    ("fieldMap", "map"),
)


@dataclass
class MigrationResult:
    changed: bool = False
    is_compiled: bool = False
    changes: list[str] = dataclass_field(default_factory=list)
    ambiguities: list[str] = dataclass_field(default_factory=list)


def _rewrite_field_aliases(obj: dict, pairs, where: str, result: MigrationResult) -> None:
    for alias, canonical in pairs:
        if alias not in obj:
            continue
        alias_value = obj[alias]
        if canonical in obj and obj[canonical] is not None and obj[canonical] != alias_value:
            result.ambiguities.append(
                f"{where}: both '{alias}'={alias_value!r} and '{canonical}'={obj[canonical]!r} "
                f"present with different values -- left untouched, resolve by hand"
            )
            continue
        if canonical in obj and obj[canonical] == alias_value:
            del obj[alias]
            result.changed = True
            result.changes.append(f"{where}: dropped redundant alias '{alias}' (== '{canonical}')")
            continue
        obj[canonical] = alias_value
        del obj[alias]
        result.changed = True
        result.changes.append(f"{where}: renamed '{alias}' -> '{canonical}'")


def _migrate_step(step: dict, where: str, result: MigrationResult) -> None:
    if not isinstance(step, dict):
        return
    step_type = step.get("type")
    if isinstance(step_type, str):
        canonical = STEP_TYPE_CANONICAL.get(step_type.strip().lower())
        if canonical is None:
            result.ambiguities.append(
                f"{where}: unrecognized step type '{step_type}' -- left untouched"
            )
        elif canonical != step_type:
            step["type"] = canonical
            result.changed = True
            result.changes.append(f"{where}: type '{step_type}' -> '{canonical}'")
    _rewrite_field_aliases(step, FLOW_STEP_FIELD_ALIASES, where, result)
    for nested_key in ("then", "else", "steps", "onFailure"):
        nested = step.get(nested_key)
        if isinstance(nested, list):
            for i, nested_step in enumerate(nested):
                _migrate_step(nested_step, f"{where}.{nested_key}[{i}]", result)


def _migrate_orchestration_action(action: dict, where: str, result: MigrationResult) -> None:
    if not isinstance(action, dict):
        return
    _rewrite_field_aliases(action, ORCHESTRATION_ACTION_FIELD_ALIASES, where, result)


def _migrate_orchestrations_key(doc: dict, result: MigrationResult) -> None:
    """Renames the top-level `orchestrations` key to `orchestrationRules` -- see design principle 5
    in the module docstring. Must run before the `orchestrationRules[]` walk below so the renamed
    entries get the per-rule scalar-action normalization in the same pass."""
    if "orchestrations" not in doc:
        return
    if doc.get("orchestrationRules") is not None:
        result.ambiguities.append(
            "$: both top-level 'orchestrations' and 'orchestrationRules' present -- left untouched, "
            "resolve by hand which one is authoritative"
        )
        return
    doc["orchestrationRules"] = doc.pop("orchestrations")
    result.changed = True
    result.changes.append("$: renamed top-level 'orchestrations' -> 'orchestrationRules'")


def _migrate_orchestration_rule(rule: dict, where: str, result: MigrationResult) -> None:
    if not isinstance(rule, dict):
        return
    has_action = "action" in rule and rule["action"] is not None
    has_actions = "actions" in rule and rule["actions"] is not None
    if has_action and has_actions:
        result.ambiguities.append(
            f"{where}: both scalar 'action' and list 'actions' present -- left untouched, "
            f"resolve by hand which one is authoritative"
        )
    elif has_action:
        rule["actions"] = [rule.pop("action")]
        result.changed = True
        result.changes.append(f"{where}: normalized scalar 'action' to single-element 'actions'")
    for i, action in enumerate(rule.get("actions", None) or []):
        _migrate_orchestration_action(action, f"{where}.actions[{i}]", result)


def migrate_document(doc: dict) -> MigrationResult:
    """Migrates `doc` IN PLACE to DSL 2.0 canonical spellings. Returns what happened."""
    result = MigrationResult()
    if _looks_compiled(doc):
        result.is_compiled = True
        return result

    _migrate_orchestrations_key(doc, result)

    for i, flow in enumerate(doc.get("flows", None) or []):
        if not isinstance(flow, dict):
            continue
        where = f"flows[{i}] ({flow.get('name', '?')})"
        for j, step in enumerate(flow.get("steps", None) or []):
            _migrate_step(step, f"{where}.steps[{j}]", result)
        for j, hook in enumerate(flow.get("hooks", None) or []):
            if not isinstance(hook, dict):
                continue
            hook_where = f"{where}.hooks[{j}]"
            _rewrite_field_aliases(hook, FLOW_HOOK_FIELD_ALIASES, hook_where, result)
            for k, step in enumerate(hook.get("steps", None) or []):
                _migrate_step(step, f"{hook_where}.steps[{k}]", result)

    for i, rule in enumerate(doc.get("orchestrationRules", None) or []):
        _migrate_orchestration_rule(rule, f"orchestrationRules[{i}] ({rule.get('name', '?')})", result)

    return result
