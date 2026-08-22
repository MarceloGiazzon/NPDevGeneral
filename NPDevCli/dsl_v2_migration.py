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


def _migrate_transaction_recompute(transaction: dict, where: str, result: MigrationResult) -> None:
    """Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4): rewrites the untyped
    `transaction.metadata.recompute` (a bare procedure name, or `{procedure}`) to the typed
    `transaction.hooks.onFieldChange`. Left untouched (reported) if both are present with
    different procedure names -- a same-value pair is simply redundant and the alias is dropped."""
    metadata = transaction.get("metadata")
    if not isinstance(metadata, dict):
        return
    legacy = metadata.get("recompute")
    procedure = legacy.get("procedure") if isinstance(legacy, dict) else legacy
    if not isinstance(procedure, str) or not procedure.strip():
        return
    hooks = transaction.get("hooks")
    existing = hooks.get("onFieldChange") if isinstance(hooks, dict) else None
    if isinstance(existing, str) and existing.strip():
        if existing == procedure:
            del metadata["recompute"]
            result.changed = True
            result.changes.append(
                f"{where}: dropped redundant transaction.metadata.recompute (== hooks.onFieldChange)")
            return
        result.ambiguities.append(
            f"{where}: both transaction.metadata.recompute={procedure!r} and "
            f"transaction.hooks.onFieldChange={existing!r} present with different values -- "
            f"left untouched, resolve by hand"
        )
        return
    if not isinstance(hooks, dict):
        hooks = {}
        transaction["hooks"] = hooks
    hooks["onFieldChange"] = procedure
    del metadata["recompute"]
    result.changed = True
    result.changes.append(f"{where}: migrated transaction.metadata.recompute -> transaction.hooks.onFieldChange")


def _migrate_transaction_derived(transaction: dict, where: str, result: MigrationResult) -> None:
    """Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4): rewrites the untyped
    `transaction.metadata.derived` (a list of `{name, expression, label?}`) to the typed
    `transaction.derivedFields` (an object keyed by name, guaranteeing uniqueness by
    construction). Every migrated entry gets `tier: "client"` -- the only tier the retired list
    ever supported. An entry missing a usable name/expression is dropped, matching the compiler's
    own long-standing tolerance for malformed legacy entries (AutoPanelExpander.derivedFields).
    Left untouched (reported) if `derivedFields` is already non-empty -- which one is authoritative
    is not something this tool decides."""
    metadata = transaction.get("metadata")
    if not isinstance(metadata, dict):
        return
    legacy = metadata.get("derived")
    if not isinstance(legacy, list) or not legacy:
        return
    if isinstance(transaction.get("derivedFields"), dict) and transaction["derivedFields"]:
        result.ambiguities.append(
            f"{where}: both transaction.metadata.derived and transaction.derivedFields are present "
            f"-- left untouched, resolve by hand which one is authoritative"
        )
        return
    derived_fields: dict = {}
    dropped = 0
    for entry in legacy:
        name = entry.get("name") if isinstance(entry, dict) else None
        expression = entry.get("expression") if isinstance(entry, dict) else None
        if not isinstance(name, str) or not name.strip() or not isinstance(expression, str) or not expression.strip():
            dropped += 1
            continue
        migrated_field: dict = {"tier": "client", "expression": expression}
        label = entry.get("label")
        if isinstance(label, str) and label.strip():
            migrated_field["label"] = label
        derived_fields[name] = migrated_field
    if not derived_fields:
        return
    transaction["derivedFields"] = derived_fields
    del metadata["derived"]
    result.changed = True
    plural = "y" if len(derived_fields) == 1 else "ies"
    message = (f"{where}: migrated {len(derived_fields)} entr{plural} from transaction.metadata.derived "
               f"(list) to transaction.derivedFields (object)")
    if dropped:
        message += f", dropped {dropped} malformed entr{'y' if dropped == 1 else 'ies'}"
    result.changes.append(message)


def _migrate_action_entry(entry) -> "dict | None":
    """Normalizes one untyped transaction.metadata.actions[] entry to the typed `workbenchAction`
    shape, applying the exact same tolerance AutoPanelExpander.workbenchActions has always had for
    malformed sub-fields -- an entry with no usable procedure is dropped entirely (None); a
    malformed applyTo (missing/blank collection, a non-"appendRow" mode, an empty map) is dropped
    from the entry alone rather than carried over as a now schema-invalid value, since the typed
    `workbenchActionApplyTo` def requires collection+mode. `afterAction` accepts the same dual
    shape (bare string or `{procedure}`) the untyped form always did."""
    if not isinstance(entry, dict):
        return None
    procedure = entry.get("procedure")
    if not isinstance(procedure, str) or not procedure.strip():
        return None
    migrated: dict = {"procedure": procedure.strip()}
    label = entry.get("label")
    if isinstance(label, str) and label.strip():
        migrated["label"] = label.strip()
    input_fields = entry.get("inputFields")
    if isinstance(input_fields, list):
        cleaned = [str(f).strip() for f in input_fields if isinstance(f, str) and f.strip()]
        if cleaned:
            migrated["inputFields"] = cleaned
    apply_to = entry.get("applyTo")
    if isinstance(apply_to, dict):
        collection = apply_to.get("collection")
        mode = apply_to.get("mode")
        field_map = apply_to.get("map")
        if (isinstance(collection, str) and collection.strip()
                and mode == "appendRow"
                and isinstance(field_map, dict) and field_map):
            migrated["applyTo"] = {
                "collection": collection.strip(),
                "mode": "appendRow",
                "map": {str(k): str(v) for k, v in field_map.items() if k is not None and v is not None},
            }
    after_action = entry.get("afterAction")
    if isinstance(after_action, dict):
        after_action = after_action.get("procedure")
    if isinstance(after_action, str) and after_action.strip():
        migrated["afterAction"] = after_action.strip()
    visible_when = entry.get("visibleWhen")
    if isinstance(visible_when, str) and visible_when.strip():
        migrated["visibleWhen"] = visible_when.strip()
    return migrated


def _migrate_transaction_actions(transaction: dict, where: str, result: MigrationResult) -> None:
    """Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): rewrites the untyped
    `transaction.metadata.actions` (a list) to the typed `transaction.actions` (schema-validated,
    same shape) -- a typo'd key in the untyped bag silently did nothing; the typed slot fails at
    schema time instead. An entry missing `procedure` entirely is dropped, same as the compiler
    always silently skipped it. Left untouched (reported) if `actions` is already non-empty."""
    metadata = transaction.get("metadata")
    if not isinstance(metadata, dict):
        return
    legacy = metadata.get("actions")
    if not isinstance(legacy, list) or not legacy:
        return
    if isinstance(transaction.get("actions"), list) and transaction["actions"]:
        result.ambiguities.append(
            f"{where}: both transaction.metadata.actions and transaction.actions are present "
            f"-- left untouched, resolve by hand which one is authoritative"
        )
        return
    migrated_actions = []
    dropped = 0
    for entry in legacy:
        migrated = _migrate_action_entry(entry)
        if migrated is None:
            dropped += 1
            continue
        migrated_actions.append(migrated)
    if not migrated_actions:
        return
    transaction["actions"] = migrated_actions
    del metadata["actions"]
    result.changed = True
    plural = "" if len(migrated_actions) == 1 else "s"
    message = (f"{where}: migrated {len(migrated_actions)} action{plural} from "
               f"transaction.metadata.actions to transaction.actions")
    if dropped:
        message += f", dropped {dropped} malformed entr{'y' if dropped == 1 else 'ies'}"
    result.changes.append(message)


def _migrate_transaction_visible_when(transaction: dict, where: str, result: MigrationResult) -> None:
    """Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): rewrites the untyped
    `transaction.metadata.visibleWhen` (an object keyed by collection/band name, values a client
    predicate string) to the typed `transaction.visibleWhen` -- same shape, now schema-validated.
    A blank key or non-string value is dropped, matching
    AutoPanelExpander.visibleWhenByCollection's own long-standing tolerance. Left untouched
    (reported) if `visibleWhen` is already non-empty."""
    metadata = transaction.get("metadata")
    if not isinstance(metadata, dict):
        return
    legacy = metadata.get("visibleWhen")
    if not isinstance(legacy, dict) or not legacy:
        return
    if isinstance(transaction.get("visibleWhen"), dict) and transaction["visibleWhen"]:
        result.ambiguities.append(
            f"{where}: both transaction.metadata.visibleWhen and transaction.visibleWhen are present "
            f"-- left untouched, resolve by hand which one is authoritative"
        )
        return
    migrated: dict = {}
    dropped = 0
    for key, value in legacy.items():
        if not isinstance(key, str) or not key.strip() or not isinstance(value, str) or not value.strip():
            dropped += 1
            continue
        migrated[key.strip()] = value.strip()
    if not migrated:
        return
    transaction["visibleWhen"] = migrated
    del metadata["visibleWhen"]
    result.changed = True
    message = (f"{where}: migrated {len(migrated)} entr{'y' if len(migrated) == 1 else 'ies'} from "
               f"transaction.metadata.visibleWhen to transaction.visibleWhen")
    if dropped:
        message += f", dropped {dropped} malformed entr{'y' if dropped == 1 else 'ies'}"
    result.changes.append(message)


def _migrate_transaction_band_pickers(transaction: dict, where: str, result: MigrationResult) -> None:
    """Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): rewrites the untyped
    `transaction.metadata.bandPickers` (an object keyed by band collection name, values
    `{panel, label?, columns?}`) to the typed `transaction.bandPickers` -- same shape, now
    schema-validated. An entry missing `panel` is dropped, matching AutoPanelExpander.bandPickers'
    own long-standing tolerance. Left untouched (reported) if `bandPickers` is already non-empty."""
    metadata = transaction.get("metadata")
    if not isinstance(metadata, dict):
        return
    legacy = metadata.get("bandPickers")
    if not isinstance(legacy, dict) or not legacy:
        return
    if isinstance(transaction.get("bandPickers"), dict) and transaction["bandPickers"]:
        result.ambiguities.append(
            f"{where}: both transaction.metadata.bandPickers and transaction.bandPickers are present "
            f"-- left untouched, resolve by hand which one is authoritative"
        )
        return
    migrated: dict = {}
    dropped = 0
    for key, spec in legacy.items():
        if not isinstance(key, str) or not key.strip() or not isinstance(spec, dict):
            dropped += 1
            continue
        panel = spec.get("panel")
        if not isinstance(panel, str) or not panel.strip():
            dropped += 1
            continue
        migrated_picker: dict = {"panel": panel.strip()}
        label = spec.get("label")
        if isinstance(label, str) and label.strip():
            migrated_picker["label"] = label.strip()
        columns = spec.get("columns")
        if isinstance(columns, list):
            cleaned_columns = [str(c).strip() for c in columns if isinstance(c, str) and c.strip()]
            if cleaned_columns:
                migrated_picker["columns"] = cleaned_columns
        migrated[key.strip()] = migrated_picker
    if not migrated:
        return
    transaction["bandPickers"] = migrated
    del metadata["bandPickers"]
    result.changed = True
    message = (f"{where}: migrated {len(migrated)} picker{'' if len(migrated) == 1 else 's'} from "
               f"transaction.metadata.bandPickers to transaction.bandPickers")
    if dropped:
        message += f", dropped {dropped} malformed entr{'y' if dropped == 1 else 'ies'}"
    result.changes.append(message)


def _migrate_query_or_procedure_audit_policy(entries, kind: str, result: MigrationResult) -> None:
    """R5.1 (roadmap 2026-08-18, "retire the inert auditPolicy knob"): `queries[].auditPolicy` and
    `procedures[].auditPolicy` were schema-declared (none|read|write) but consumed by nothing at
    runtime -- no validator, compiler pass, or kernel code path ever read the accessor beyond
    round-tripping it through the compiled-model JSON. The REAL, always-on audit trail lives at the
    ConceptGateway/AuditLogStore layer (every concept read/write/delete is logged there regardless
    of this flag) and, as of the same change that retires this knob, at the generated CRUD service's
    own field-diff audit trail (create/update/delete/restore). Both are unconditional per-operation,
    not opt-in per query/procedure, so there was no live semantics this flag could have controlled.
    Retired from schema (all 4 model.schema.json mirrors); this strips the dead key from an existing
    document so it keeps validating (`additionalProperties: false` on both object shapes would
    otherwise reject it)."""
    if not isinstance(entries, list):
        return
    for i, entry in enumerate(entries):
        if not isinstance(entry, dict):
            continue
        if "auditPolicy" not in entry:
            continue
        value = entry.pop("auditPolicy")
        result.changed = True
        result.changes.append(
            f"{kind}[{i}] ({entry.get('name', '?')}): dropped inert 'auditPolicy' ({value!r}) -- "
            f"retired, consumed by nothing (R5.1)"
        )


def _migrate_autopanel(autopanel: dict, where: str, result: MigrationResult) -> None:
    if not isinstance(autopanel, dict):
        return
    transaction = autopanel.get("transaction")
    if not isinstance(transaction, dict):
        return
    _migrate_transaction_recompute(transaction, where, result)
    _migrate_transaction_derived(transaction, where, result)
    _migrate_transaction_actions(transaction, where, result)
    _migrate_transaction_visible_when(transaction, where, result)
    _migrate_transaction_band_pickers(transaction, where, result)


# =================================================================================================
# R5.6: per-locale label maps -- widens a label site's plain string to the per-locale object form.
#
# NOT required by the schema change itself: R5.6's non-negotiable is "string stays valid", so
# every existing model keeps validating and behaving exactly as before with zero changes. This
# codemod exists anyway per the standing stability-policy convention ("every breaking change ships
# its `npdev migrate` codemod in the same commit") and to satisfy R5.6's done-when of a lossless,
# structural corpus conversion -- the deadline logic being that this team's own apps are authored
# in Portuguese against English platform defaults, and it is far cheaper to declare that split
# NOW, mechanically, than to retrofit it by hand once more apps exist.
# =================================================================================================

def _widen_label_site(obj: dict, key: str, locale: str, where: str, result: MigrationResult) -> None:
    """Converts obj[key] from a plain string to {"default": <text>, "<locale>": <text>} IN PLACE.
    Lossless: the original string survives byte-for-byte as BOTH the terminal 'default' fallback
    (so a reader that predates R5.6, or simply never authored a locale override, sees exactly the
    same text as before) and as the `locale` entry itself (the app's existing text IS that
    locale's text -- this does not invent a translation). Idempotent and structural, not a blind
    string replace: only a bare string value is touched; a missing key, a non-string value, or a
    value that is already the object form (however it got there) is left completely alone, so
    running this twice -- or against a model where some sites are already widened by hand -- never
    double-wraps or corrupts anything."""
    if key not in obj:
        return
    value = obj[key]
    if not isinstance(value, str) or not value.strip():
        return
    obj[key] = {"default": value, locale: value}
    result.changed = True
    result.changes.append(f"{where}.{key}: widened plain string to per-locale form (tagged {locale!r})")


def migrate_label_locales(doc: dict, locale: str) -> MigrationResult:
    """Widens every label site `migrate_document`'s DSL-2.0 pass does not touch -- a completely
    separate, independently-callable pass (not part of `migrate_document`, and not run by it)
    because it needs one extra piece of information `migrate_document` never requires: which
    locale the app's EXISTING plain-string text is actually written in. `doc.settings.locale` is
    deliberately NOT read for this -- its own schema description says it is "informational...
    does not itself select translations", so trusting it here would silently mislabel text for any
    app that set it loosely. The caller (an author, or a future CLI flag) supplies `locale`
    explicitly instead.

    A falsy/blank `locale` makes this a hard no-op (returns an unchanged, empty `MigrationResult`)
    rather than guessing -- consistent with `migrate_document`'s "reports, does not guess"
    design principle.

    Walks the exact shapes the label-site schema (`$defs/localizableLabel`) and
    `JsonModelParser`'s corresponding parse sites recognize: top-level `properties[]`,
    `domainTypes[].ui`, `concepts[].ui` / `concepts[].fields[].ui` (label + shortLabel),
    `concepts[].fields[].enumValues[]` (label + displayLabel), `concepts[].lifecycle.states[]` /
    `.transitions[]` (actionLabel), `panels[].actions[]`, and every `autoPanels[].<surface>`
    (selection/detail/transaction/prompt) actions/bandPickers/derivedFields/uiState. Does NOT
    reach a nested `actionMetadata` block's own `label` (e.g. a lifecycle transition's `action`
    object, or an orchestration action's `action`) -- narrower than the full 13-site inventory on
    purpose for this first pass; those are still valid to widen by hand and this codemod will not
    touch them either way (schema stays additive either way).
    """
    result = MigrationResult()
    if not isinstance(locale, str) or not locale.strip():
        return result
    locale = locale.strip()
    if not isinstance(doc, dict):
        return result

    for i, prop in enumerate(doc.get("properties", None) or []):
        if isinstance(prop, dict):
            _widen_label_site(prop, "label", locale, f"properties[{i}] ({prop.get('name', '?')})", result)

    for i, domain_type in enumerate(doc.get("domainTypes", None) or []):
        if not isinstance(domain_type, dict):
            continue
        ui = domain_type.get("ui")
        if isinstance(ui, dict):
            _widen_label_site(ui, "label", locale, f"domainTypes[{i}] ({domain_type.get('name', '?')}).ui", result)

    for i, concept in enumerate(doc.get("concepts", None) or []):
        if not isinstance(concept, dict):
            continue
        cwhere = f"concepts[{i}] ({concept.get('name', '?')})"
        ui = concept.get("ui")
        if isinstance(ui, dict):
            _widen_label_site(ui, "label", locale, f"{cwhere}.ui", result)
            _widen_label_site(ui, "shortLabel", locale, f"{cwhere}.ui", result)
        for j, field in enumerate(concept.get("fields", None) or []):
            if not isinstance(field, dict):
                continue
            fwhere = f"{cwhere}.fields[{j}] ({field.get('name', '?')})"
            field_ui = field.get("ui")
            if isinstance(field_ui, dict):
                _widen_label_site(field_ui, "label", locale, fwhere, result)
                _widen_label_site(field_ui, "shortLabel", locale, fwhere, result)
            for k, option in enumerate(field.get("enumValues", None) or []):
                if isinstance(option, dict):
                    owhere = f"{fwhere}.enumValues[{k}]"
                    _widen_label_site(option, "label", locale, owhere, result)
                    _widen_label_site(option, "displayLabel", locale, owhere, result)
        lifecycle = concept.get("lifecycle")
        if isinstance(lifecycle, dict):
            for j, state in enumerate(lifecycle.get("states", None) or []):
                if isinstance(state, dict):
                    _widen_label_site(state, "label", locale, f"{cwhere}.lifecycle.states[{j}]", result)
            for j, transition in enumerate(lifecycle.get("transitions", None) or []):
                if isinstance(transition, dict):
                    _widen_label_site(
                        transition, "actionLabel", locale, f"{cwhere}.lifecycle.transitions[{j}]", result)

    for i, panel in enumerate(doc.get("panels", None) or []):
        if not isinstance(panel, dict):
            continue
        pwhere = f"panels[{i}] ({panel.get('name', '?')})"
        for j, action in enumerate(panel.get("actions", None) or []):
            if isinstance(action, dict):
                _widen_label_site(action, "label", locale, f"{pwhere}.actions[{j}]", result)

    for i, autopanel in enumerate(doc.get("autoPanels", None) or []):
        if not isinstance(autopanel, dict):
            continue
        awhere = f"autoPanels[{i}] ({autopanel.get('name') or autopanel.get('aggregate') or '?'})"
        for surface_name in ("selection", "detail", "transaction", "prompt"):
            surface = autopanel.get(surface_name)
            if not isinstance(surface, dict):
                continue
            swhere = f"{awhere}.{surface_name}"
            for j, action in enumerate(surface.get("actions", None) or []):
                if isinstance(action, dict):
                    _widen_label_site(action, "label", locale, f"{swhere}.actions[{j}]", result)
            band_pickers = surface.get("bandPickers")
            if isinstance(band_pickers, dict):
                for name, picker in band_pickers.items():
                    if isinstance(picker, dict):
                        _widen_label_site(picker, "label", locale, f"{swhere}.bandPickers.{name}", result)
            derived_fields = surface.get("derivedFields")
            if isinstance(derived_fields, dict):
                for name, field in derived_fields.items():
                    if isinstance(field, dict):
                        _widen_label_site(field, "label", locale, f"{swhere}.derivedFields.{name}", result)
            ui_state = surface.get("uiState")
            if isinstance(ui_state, dict):
                for name, control in ui_state.items():
                    if isinstance(control, dict):
                        _widen_label_site(control, "label", locale, f"{swhere}.uiState.{name}", result)

    return result


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

    for i, autopanel in enumerate(doc.get("autoPanels", None) or []):
        _migrate_autopanel(autopanel, f"autoPanels[{i}] ({autopanel.get('name') or autopanel.get('aggregate') or '?'})", result)

    _migrate_query_or_procedure_audit_policy(doc.get("queries", None) or [], "queries", result)
    _migrate_query_or_procedure_audit_policy(doc.get("procedures", None) or [], "procedures", result)

    return result

# =================================================================================================
# STOR-16: db.definition.json's schemaLifecycle.strategy -- RecreateOnAppStart -> Ephemeral.
#
# A DIFFERENT document from everything above. The functions above migrate model.json; this one
# migrates db.definition.json, and `migrate_document`'s `_looks_compiled` guard would have nothing
# sensible to say about it. Kept in this module anyway because it is the same KIND of thing -- a
# spelling the platform retired, rewritten mechanically so an author never has to know it changed --
# and splitting it into a third file would just mean two places to look for "what does npdev
# rewrite for me".
#
# Why the rename is safe rather than merely convenient: `RecreateOnAppStart` never had a code path.
# `SchemaLifecycleExecutor` read `strategy` at exactly one line and compared it to
# `DropAndRecreateOnStructureChange`, so an app declaring the old name recreated nothing, whatever
# the name promised. `Ephemeral` is the behaviour the name always claimed.
# =================================================================================================

DEPRECATED_LIFECYCLE_STRATEGY = "RecreateOnAppStart"
EPHEMERAL_LIFECYCLE_STRATEGY = "Ephemeral"


def looks_like_db_definition(doc: dict) -> bool:
    """A db.definition.json is `{database: {...}, schemaLifecycle: {...}}`. Checked structurally
    rather than by filename so the codemod also reaches a definition someone renamed."""
    return (isinstance(doc, dict)
            and isinstance(doc.get("database"), dict)
            and isinstance(doc.get("schemaLifecycle"), dict))


def migrate_db_definition(doc: dict) -> MigrationResult:
    """Rewrites `doc` IN PLACE. Returns what happened.

    Only the strategy STRING changes. `allowDestructiveRecreate`, the confirmation and the scope are
    deliberately left exactly as they are: every in-corpus user of the old spelling is InMemory and
    already carries the `I_UNDERSTAND_INMEMORY_DATA_IS_EPHEMERAL` +
    `NpdevOwnedLogicalStoresOnly` pair, which `SchemaLifecyclePolicy.ephemeralConfirmedFor` accepts
    for that engine. Rewriting a confirmation string on someone's behalf would be forging their
    signature on a sentence they never read.
    """
    result = MigrationResult()
    if not looks_like_db_definition(doc):
        return result
    lifecycle = doc["schemaLifecycle"]
    strategy = lifecycle.get("strategy")
    if not isinstance(strategy, str) or strategy.strip() != DEPRECATED_LIFECYCLE_STRATEGY:
        return result
    lifecycle["strategy"] = EPHEMERAL_LIFECYCLE_STRATEGY
    result.changed = True
    result.changes.append(
        f"schemaLifecycle.strategy '{DEPRECATED_LIFECYCLE_STRATEGY}' -> "
        f"'{EPHEMERAL_LIFECYCLE_STRATEGY}'")
    engine = (doc.get("database") or {}).get("engine")
    if engine and str(engine) != "InMemory":
        # Not a refusal: the definition is valid either way, and `ephemeralConfirmedFor` will say so
        # precisely at generation time. But a physical engine moving to Ephemeral is a real change in
        # what happens to real rows, and the author should hear it from the tool that made the edit.
        result.ambiguities.append(
            f"engine is {engine}, not InMemory -- on a physical engine Ephemeral means this app "
            f"DROPS its tables on every start. Confirm that is what you want, and set "
            f"destructiveRecreateConfirmation to "
            f"\"I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START\" with scope "
            f"\"NpdevOwnedTablesOnly\".")
    return result
