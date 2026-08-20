"""The tracked-feature table: the name this gate reports, and the predicate that answers it.

The table is what makes a feature TRACKED. A detector missing from here is dead code; a name here
with no detector is an ImportError at startup rather than a feature silently reported as covered.

The imports are EXPLICIT, and that is load-bearing. The first cut of this split used
`from .detectors_model import *`, which silently imports nothing whose name starts with an
underscore -- and every detector's does. The gate crashed on the first table entry, which is the
lucky version; the unlucky version is a star import that resolves enough names to run and reports a
feature as covered because its detector quietly went missing. The before/after output diff is what
caught it.
"""
from __future__ import annotations

from .constants import FLOW_STEP_TYPES  # noqa: F401 - the table expands one entry per type

from .detectors_model import (  # noqa: F401 - every name the table below references
    _all_steps, _flows, _has_aggregate_on_commit, _has_aggregate_on_validate,
    _has_arithmetic_derived_expression,
    _has_capability_policy, _has_composite_index, _has_concept_access, _has_concept_extends,
    _has_concept_soft_delete, _has_concept_temporal, _has_field_access, _has_locale_label,
    _has_conversion_op, _has_date_field, _has_decimal_field, _has_file_field, _has_flow_io_schema,
    _has_flow_start_endpoint, _has_groupby_cross_context_join, _has_groupby_join,
    _has_groupby_multi_hop_join, _has_await_timeout, _has_on_failure, _has_parallel_await_foreach,
    _has_parallel_await_multistep_foreach, _has_post_checkpoint,
    _has_procedure_create_if_missing, _has_procedure_step_type, _has_query_where_v2, _has_renamed_field,
    _has_schedule_event_with_delay, _has_sensitive_field, _has_settings, _has_step_type,
    _has_widened_branch_condition, _nonempty,
)

from .detectors_ui import (  # noqa: F401 - every name the table below references
    _has_autopanel_selection_data_source_procedure, _has_band_picker_filter,
    _has_field_picker_filter, _has_panel_action_concept_query, _has_panel_action_download,
    _has_panel_data_source_on_row_load, _has_region_component_mount, _has_transaction_hook,
    _has_typed_workbench_actions, _has_workbench_after_action, _has_workbench_apply_to,
    _has_workbench_band_pickers, _has_workbench_derived, _has_workbench_ui_state,
    _has_workbench_visible_when,
)

FEATURE_DETECTORS = {
    "externalAi": lambda m: "externalAi" in m,
    "domainTypes": lambda m: _nonempty(m, "domainTypes"),
    # D1 (FIRST_IMPRESSION_PLAN.md I5): field.renamedFrom -- see _has_renamed_field's own docstring.
    "field.renamedFrom": _has_renamed_field,
    "selectors": lambda m: _nonempty(m, "selectors"),
    # Wave 3 (RC-B1, MOVE11_RUNTIME_CONFIGURATION_PLAN Part B.1): app-defined role -> permission-
    # ceiling declarations, a new top-level array sibling of settings/selectors.
    "roles": lambda m: _nonempty(m, "roles"),
    # Wave 6 (RC-A1, MOVE11_RUNTIME_CONFIGURATION_PLAN Part A.1): the scoped-property cascade's
    # declaration layer -- two new top-level arrays, siblings of roles/settings.
    "propertyScopes": lambda m: _nonempty(m, "propertyScopes"),
    "properties": lambda m: _nonempty(m, "properties"),
    "aggregates": lambda m: _nonempty(m, "aggregates"),
    "autoPanels": lambda m: _nonempty(m, "autoPanels"),
    "documents": lambda m: _nonempty(m, "documents"),
    "guidePages": lambda m: _nonempty(m, "guidePages"),
    # R6.2 (Roadmap Collection 2026-08-18): model-declared inbound webhook doors -- a new top-level
    # array sibling of roles/aggregates above, generating POST /api/hooks/{source}.
    "webhooks": lambda m: _nonempty(m, "webhooks"),
    # R5.3 (Roadmap Collection 2026-08-18): model-declared document-numbering counters -- a new
    # top-level array sibling of webhooks above, allocated by field.defaultExpression: nextNumber('name').
    "sequences": lambda m: _nonempty(m, "sequences"),
    "queries": lambda m: _nonempty(m, "queries"),
    # Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): query.groupBy/aggregates/having --
    # distinct from the top-level "aggregates" (Aggregate Workbench) array above, so named
    # "query.*" to avoid the collision.
    "query.groupBy": lambda m: any(q.get("groupBy") for q in (m.get("queries", None) or [])),
    "query.aggregates": lambda m: any(q.get("aggregates") for q in (m.get("queries", None) or [])),
    "query.having": lambda m: any(q.get("having") for q in (m.get("queries", None) or [])),
    # S4 (roadmap B27, ADR-0011 D1): groupBy JOIN paths -- distinct from the plain "query.groupBy"
    # feature above (a bare field name), and split same-context vs cross-context so a regression to
    # just the D3 import-gate/context-qualification half independently fails the build.
    "query.groupBy.join": _has_groupby_join,
    "query.groupBy.join.crossContext": _has_groupby_cross_context_join,
    # S8 W1.1 (roadmap deferred item #1): the join chain widened from exactly one hop to a capped
    # multi-hop chain (GroupByJoinGrammar.MAX_JOIN_HOPS).
    "query.groupBy.join.multiHop": _has_groupby_multi_hop_join,
    # R4.3 lockstep fix (Roadmap Wave 1 gap closure): the query predicate v2 grammar (OR-groups,
    # in, contains/startsWith, is-null, reference-path joins) -- see _has_query_where_v2's own
    # docstring for why this needed its own tracked feature, distinct from plain "queries" above.
    "query.where.v2": _has_query_where_v2,
    "procedures": lambda m: _nonempty(m, "procedures"),
    "panels": lambda m: _nonempty(m, "panels"),
    "ruleProfiles": lambda m: _nonempty(m, "ruleProfiles"),
    "fragments": lambda m: _nonempty(m, "fragments"),
    "packs": lambda m: _nonempty(m, "packs"),
    # B20 (S2, ADR-0011): bounded-context declarations -- a new top-level array sibling of
    # packs/fragments, composed the same way.
    "contexts": lambda m: _nonempty(m, "contexts"),
    # S6 (drift A2): a context fragment's own imports[] -- D3's cross-context import gate
    # (ModelSourceResolver's QualifiedReferenceValidator, ratified in S2's own §0 gate) had NO
    # coverage protection: "contexts" above only proves a context EXISTS, not that any context
    # declares imports[]. _merge_context_fragments already surfaces a fragment's own list-typed
    # keys (imports included) into the merged dict this detector sees, so no new plumbing is
    # needed -- just the missing detector entry. Losing dsl-conformance-max's one
    # contexts/shipping.json `"imports": ["billing"]` line would leave D3 silently unexercised
    # while this gate stayed green.
    "imports": lambda m: _nonempty(m, "imports"),
    # S8 Wave 4 (ADR-0011 D4's v2 opt-in): a context declaring physicallyIsolate:true -- distinct
    # from the plain "contexts" detector above (which a context with NO physicallyIsolate at all
    # already satisfies). Read directly off the top-level contexts[] array (the property lives on
    # the context DECLARATION itself, not inside the fragment file, so no fragment-merge plumbing
    # is needed the way "imports" above required).
    "contexts.physicallyIsolate": lambda m: any(
        isinstance(c, dict) and c.get("physicallyIsolate") is True
        for c in (m.get("contexts", None) or [])
    ),
    "flow.schedule": lambda m: any("schedule" in f for f in _flows(m)),
    "flow.specializes": lambda m: any("specializes" in f for f in _flows(m)),
    "flow.hooks": lambda m: any(f.get("hooks") for f in _flows(m)),
    "step.onFailure": _has_on_failure,
    # R2.5: an awaitEvent step's durable timeout + onTimeout escalation branch -- see
    # _has_await_timeout's own docstring for why this is tracked apart from "step.awaitEvent".
    "step.awaitEvent.timeout": _has_await_timeout,
    "step.forEach.parallelAwait": _has_parallel_await_foreach,
    "step.forEach.parallelAwait.multiStep": _has_parallel_await_multistep_foreach,
    **{f"step.{t}": (lambda m, t=t: _has_step_type(m, t)) for t in FLOW_STEP_TYPES},
    # R2.4: scheduleEvent's two delivery modes are two code paths now, not one path with a label --
    # see _has_schedule_event_with_delay. Tracked separately for the same reason
    # "query.groupBy.join.multiHop" is tracked apart from "query.groupBy.join": the base feature is
    # satisfied by either mode alone, so a corpus carrying only one leaves the other unexercised.
    "step.scheduleEvent.deferred": lambda m: _has_schedule_event_with_delay(m, True),
    "step.scheduleEvent.immediate": lambda m: _has_schedule_event_with_delay(m, False),
    # Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): procedure.patchConcept and aggregate.onCommit
    # are new features, not caught by the flow-only _all_steps() above -- a procedure's steps live
    # under "procedures", not "flows". Tracked separately so a regression to either has the same
    # zero-coverage-fails-the-build guarantee as every other feature this gate already tracks.
    "procedure.patchConcept": lambda m: _has_procedure_step_type(m, "patchConcept"),
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): mapList produces a NEW list
    # (one output object per input item), unlike forEach which only iterates for side effects.
    "procedure.mapList": lambda m: _has_procedure_step_type(m, "mapList"),
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, final item / REG-78): computeValue -- the
    # add/subtract arithmetic primitive procedures previously had none of, blocking
    # SyncOcupacaoProcedure's find-or-increment semantics.
    "procedure.computeValue": lambda m: _has_procedure_step_type(m, "computeValue"),
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
    # R5.5 (Roadmap Wave 1, 2026-08-19): field-level authorization (field.access.read/write) --
    # confirmed genuinely wired end-to-end (DefaultConceptGateway's field-level read-masking in
    # filterVisibleFields and deniedWriteFields-driven write rejection, fail-closed on a malformed
    # expression via the same evaluateAccessRule concept.access already uses), the next rung below
    # concept.access on the role-ceiling -> row-scope -> field-scope ladder.
    "field.access": _has_field_access,
    # R5.6 (Roadmap Wave 1, 2026-08-19): a label site authored as the per-locale object form
    # ({"default": "...", "<locale>": "..."}) rather than a plain string -- proves the widened
    # schema/DSL/canonical-JSON chain actually carries a locale map end to end, distinct from every
    # OTHER corpus model's plain-string labels (which would make a detector that only checked "a
    # label exists" vacuous -- string-form labels predate this feature entirely).
    "label.locale": _has_locale_label,
    # R5.4 (Roadmap Collection 2026-08-18): concept.softDelete -- deletedAt-flip delete, deleted-row-
    # excluding reads (list/page/aggregate/existsUnique/reference finders), a restore action, and
    # unique-among-live-rows semantics instead of a physical DELETE.
    "concept.softDelete": _has_concept_soft_delete,
    # R5.8 (Roadmap Collection 2026-08-18): concept.temporal -- effective-dated rows resolved by an
    # as-of date against author-declared validFrom/validTo fields, instead of one current value per
    # logical entity (price lists, tax rates, assignments).
    "concept.temporal": _has_concept_temporal,
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
    # R5 (MASTER-ROADMAP.md Step 5, M1): a field with type:decimal -- the exact-precision numeric
    # type the DSL previously had no way to express (every existing sample worked around it with a
    # priceCents-style integer field name).
    "type.decimal": _has_decimal_field,
    # R4.1 (roadmap): a default/derivedExpression using arithmetic (+ - * / %), e.g.
    # "quantity * unitPrice" -- distinct from the plain concat/coalesce/trim/uppercase/lowercase/
    # identifier shape every prior corpus example used. See _has_arithmetic_derived_expression's
    # own docstring for why this was a validator-only ceiling, not a runtime gap.
    "field.derivedExpression.arithmetic": _has_arithmetic_derived_expression,
    # R4.2 (roadmap): a flow BRANCH step's condition using the widened ComputedExpression grammar
    # (&&, ||, or an ordered comparison) rather than the ==/!=-only shape KernelRunner.
    # evaluateCondition's legacy matcher was previously capped at. See
    # _has_widened_branch_condition's own docstring for why this was zero-witness before.
    "step.branch.condition.widenedGrammar": _has_widened_branch_condition,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): panelAction.binding=conceptQuery -- zero
    # declarations AND zero test coverage anywhere before this (unlike most other Wave 5 items);
    # PanelRuntimeConceptQueryActionTest (RuntimeHost) now proves it end-to-end.
    "panelAction.conceptQuery": _has_panel_action_concept_query,
    # Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): field.sensitive -- found to be dead wiring
    # (REG-80, ledger/items/REG-80.yml): parsed/compiled/round-tripped but consumed by nothing,
    # including its own documented external-AI-review-pack redaction purpose. Tracked here to protect
    # the round-trip itself from regressing while the real consumption gap (REG-80) stays open.
    "constraint.sensitive": _has_sensitive_field,
    # Move 9 A3 (docs/ACCEPTED_BOUNDARIES.md B16/B19): a field's picker.filter -- folds into the same
    # server-enforced defaultFilterExpression/where mechanism the FK auto-picker's REST endpoint
    # already applies against real rows, not client-side decoration.
    "field.picker.filter": _has_field_picker_filter,
    # Move 9 A3 (docs/ACCEPTED_BOUNDARIES.md B16/B19): a bandPickers entry's filter/multiSelect --
    # the same two properties a plain FK field's picker declares, unifying the two picker shapes.
    "bandPicker.filter": _has_band_picker_filter,
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
    # Move 11 W6 (C1): transaction.uiState + a `$ui.<name>` predicate -- the SAME visibleWhen
    # grammar with a second resolvable root, so transient screen state (a record-type toggle) can
    # gate which surfaces render. Presentation-only; never part of a commit payload.
    "workbench.uiState": _has_workbench_ui_state,
    # Move 6 Move A (docs/MOVE6_TYPED_SURFACE_PLAN.md §2): the typed top-level settings block.
    "settings": _has_settings,
    # Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.2): the typed, closed-enum
    # transaction.hooks positions -- each tracked separately so a regression to just one still
    # fails the build, same discipline as aggregate.onCommit/onValidate above.
    "autoPanel.hooks.onLoad": lambda m: _has_transaction_hook(m, "onLoad"),
    "autoPanel.hooks.onFieldChange": lambda m: _has_transaction_hook(m, "onFieldChange"),
    "autoPanel.hooks.beforeAction": lambda m: _has_transaction_hook(m, "beforeAction"),
    # Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.4): per-action afterAction, which subsumes
    # applyTo -- a procedure receiving {draft, result} and returning a patched draft.
    "workbench.afterAction": _has_workbench_after_action,
    # Move 6 Move C (docs/MOVE6_TYPED_SURFACE_PLAN.md §4).
    "panelDataSource.onRowLoad": _has_panel_data_source_on_row_load,
    # Move 8 D3 (item G6): the produce disposition, reachable from an AutoPanel for the first time.
    "autoPanel.selection.dataSource.procedure": _has_autopanel_selection_data_source_procedure,
    # Move 6 Move D (docs/MOVE6_TYPED_SURFACE_PLAN.md §5).
    "workbench.region.component": _has_region_component_mount,
    # Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): the typed, schema-validated replacements for
    # the untyped transaction.metadata.actions/.bandPickers -- tracked separately from
    # workbench.applyTo/afterAction/visibleWhen above (which recognize either spelling) so a
    # regression to JUST the typed slot still fails the build.
    "autoPanel.actions": _has_typed_workbench_actions,
    "autoPanel.bandPickers": _has_workbench_band_pickers,
    # S7 Phase B (B13 declarative conversion vocabulary): the top-level conversions[] array, plus
    # each of the three closed-enum ops tracked separately -- a regression to only one op still
    # zero-coverage-fails the build, same discipline query.groupBy/query.aggregates/query.having get.
    "conversions": lambda m: _nonempty(m, "conversions"),
    "conversions.op.copy": lambda m: _has_conversion_op(m, "copy"),
    "conversions.op.split": lambda m: _has_conversion_op(m, "split"),
    "conversions.op.lookup": lambda m: _has_conversion_op(m, "lookup"),
    # S8 W1.2 (roadmap deferred item #4): merge/convert, added after the S7 ship -- same
    # per-op-tracked-separately discipline as copy/split/lookup above.
    "conversions.op.merge": lambda m: _has_conversion_op(m, "merge"),
    "conversions.op.convert": lambda m: _has_conversion_op(m, "convert"),
    # PACK-9: an app's root provides.roleBindings map -- the app-visible half of the role('logicalName')
    # compile-time binding token (PackRoleBindingRewriter, NPDevContract/dsl parser package). The
    # token itself lives inside a pack.json a composing app imports, not in model.json, so it is
    # invisible to this scanner (find_models only walks model.json, the same limit that made
    # _merge_context_fragments necessary for contexts[]); roleBindings presence in model.json is the
    # reachable, correct signal that a real corpus model exercises the feature end to end.
    "provides.roleBindings": lambda m: bool((m.get("provides") or {}).get("roleBindings")),
}
