package com.npdev.dsl.v1.compiler;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledAutoPanel;
import com.npdev.dsl.v1.compiled.CompiledAutoPanelComputed;
import com.npdev.dsl.v1.compiled.CompiledAutoPanelSurface;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelFieldBinding;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expands a concept-bound {@link CompiledAutoPanel} into the coordinated set of
 * ordinary {@link CompiledPanel} surfaces it stands for — Selection (list),
 * Detail (view), Transaction (form), Prompt (picker) — reading defaults from the
 * concept's fields (ADR-0005). The synthesized panels reuse the entire existing
 * panel pipeline; the AutoPanel record is retained separately as the intent.
 *
 * <p>Aggregate-bound AutoPanels are NOT expanded here — their Transaction becomes
 * the multi-level Aggregate Workbench, delivered in a later phase (P4).
 */
final class AutoPanelExpander {

    private AutoPanelExpander() {
    }

    /** How another concept's form field should open this concept's Prompt picker. */
    record PromptRef(String promptPanel, String returnField, String labelField) {
    }

    /**
     * @param autoPanel        a concept-bound compiled AutoPanel
     * @param fields           declared fields of the bound concept, in order
     * @param promptsByConcept normalized-concept-name -> its Prompt picker (for FK auto-wiring on forms)
     */
    static List<CompiledPanel> expand(
            CompiledAutoPanel autoPanel, List<FieldAst> fields, Map<String, PromptRef> promptsByConcept) {
        List<CompiledPanel> panels = new ArrayList<>();
        String concept = autoPanel.concept();
        if (concept == null || concept.isBlank()) {
            return panels; // aggregate-bound (or unbound) — not handled here
        }
        List<String> fieldNames = new ArrayList<>();
        String idField = null;
        for (FieldAst field : fields) {
            fieldNames.add(field.getName());
            if (idField == null && field.isId()) {
                idField = field.getName();
            }
        }
        String base = hasText(autoPanel.name()) ? autoPanel.name() : concept;
        String baseRoute = hasText(autoPanel.route())
                ? autoPanel.route()
                : "/" + concept.toLowerCase(Locale.ROOT);

        if (surfaceEnabled(autoPanel, "selection")) {
            panels.add(selectionPanel(autoPanel, concept, base, baseRoute, fieldNames));
        }
        if (surfaceEnabled(autoPanel, "detail")) {
            panels.add(detailPanel(autoPanel, concept, base, baseRoute, fieldNames));
        }
        if (surfaceEnabled(autoPanel, "transaction")) {
            panels.add(transactionPanel(autoPanel, concept, base, baseRoute, fields, idField, promptsByConcept));
        }
        if (surfaceEnabled(autoPanel, "prompt")) {
            panels.add(promptPanel(autoPanel, concept, base, baseRoute, fieldNames, idField));
        }
        return panels;
    }

    /**
     * Expand an aggregate-bound AutoPanel into a multi-level Transaction — the Aggregate Workbench
     * (ADR-0005: the Transaction surface of an aggregate-bound AutoPanel). The structure is derived
     * from the aggregate's composition tree: the root concept becomes the header, each first-level
     * owned collection becomes a master grid section, and that collection's own child collections
     * become the section's parallel bands. Carried as a {@code metadata.workbench} descriptor the
     * runtime loads via AggregateRuntime (P0) and the client renders as header + grid + band.
     *
     * <p>Synthesized in the compiler (post-validation), so it is not subject to the one-level
     * panel-nesting cap that governs hand-authored panels.
     */
    static List<CompiledPanel> expandAggregateWorkbench(
            CompiledAutoPanel autoPanel, CompiledAggregate aggregate, Map<String, List<String>> fieldsByConcept,
            Map<String, ConceptAst> conceptsByName) {
        String base = hasText(autoPanel.name()) ? autoPanel.name() : aggregate.name();
        String baseRoute = hasText(autoPanel.route())
                ? autoPanel.route()
                : "/" + aggregate.name().toLowerCase(Locale.ROOT);
        String rootConcept = aggregate.root();
        String selectionPanelName = base + "Selection";
        List<String> filters = autoPanel.selection() == null ? List.of() : autoPanel.selection().filters();

        Map<String, Object> workbench = new LinkedHashMap<>();
        workbench.put("aggregate", aggregate.name());
        workbench.put("root", rootConcept);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("fields", columnsFor(fieldsByConcept, rootConcept));
        workbench.put("header", header);
        // Per-band pickers (C6 "Seleciona …"): declared under transaction.metadata.bandPickers keyed by band
        // collection name; each attaches a source Selection panel the client offers as a modal row picker.
        Map<String, Map<String, Object>> bandPickers = bandPickers(autoPanel.transaction());
        List<Map<String, Object>> sections = new ArrayList<>();
        for (CompiledAggregateCollection collection : aggregate.collections()) {
            sections.add(sectionDescriptor(collection, fieldsByConcept, bandPickers));
        }
        workbench.put("sections", sections);
        // Lifecycle gating (ADR-0005 / P5): the root concept's declared state machine drives the
        // status chip + per-state editability in the client.
        Map<String, Object> lifecycle = lifecycleDescriptor(
                conceptsByName == null ? null : conceptsByName.get(normalize(rootConcept)));
        if (lifecycle != null) {
            workbench.put("lifecycle", lifecycle);
        }
        // Procedure-over-aggregate actions (ADR-0004 / P6): declared under transaction.metadata.actions,
        // each becomes a workbench button that invokes the procedure over the current draft and patches it.
        List<Map<String, Object>> actions = workbenchActions(autoPanel.transaction());
        if (!actions.isEmpty()) {
            workbench.put("actions", actions);
        }
        // Reactive recompute (C7/P3): a procedure named under transaction.metadata.recompute is invoked
        // (debounced) by the client on every cell edit, patching derived fields as the user types.
        String recompute = recomputeProcedure(autoPanel.transaction());
        if (recompute != null) {
            workbench.put("recompute", recompute);
        }
        // Derived display fields (Move 5, docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md Wave 2B / M6's "balanced
        // banner", docs/MOVE3_G2_CHECKLISTS.md): a display-only value the client computes from the
        // CURRENT draft on every render -- never a real field on any concept, never part of the
        // commit payload (the client never writes it into the draft it edits, only into a separate
        // read-only view). No server round trip, unlike recompute above.
        List<Map<String, Object>> derived = derivedFields(autoPanel.transaction());
        if (!derived.isEmpty()) {
            workbench.put("derived", derived);
        }

        Map<String, Object> metadata = surfaceMetadata(base, "transaction", rootConcept);
        metadata.put("dataVia", "aggregate");
        metadata.put("workbench", workbench);
        // Root-selection: the served page lists roots (via this Selection panel) and opens one by id,
        // filtering client-side on the declared filter fields.
        metadata.put("selectionPanel", selectionPanelName);
        metadata.put("filters", new ArrayList<>(filters));

        CompiledPanelLayout layout = new CompiledPanelLayout("stack", List.of(), List.of(), Map.of());
        CompiledPanel workbenchPanel = new CompiledPanel(
                base + "Workbench", baseRoute + "/{id}", rootConcept,
                List.of(conceptDataSource(rootConcept)), layout, List.of(), null, null,
                List.of(), Map.of(), metadata, null);

        // A Selection over the root concept supplies the list rows to the workbench page.
        List<String> rootColumns = override(autoPanel.selection(), CompiledAutoPanelSurface::columns,
                columnsFor(fieldsByConcept, rootConcept));
        CompiledPanelLayout selLayout = new CompiledPanelLayout("table", List.of(), rootColumns, Map.of());
        CompiledPanel selectionPanel = new CompiledPanel(
                selectionPanelName, baseRoute, rootConcept,
                List.of(conceptDataSource(rootConcept)), selLayout, List.of(), null, null,
                List.of(newRecordAction(rootConcept)), Map.of(),
                surfaceMetadata(base, "selection", rootConcept), null);

        return List.of(workbenchPanel, selectionPanel);
    }

    /**
     * Read the workbench's procedure-invoke actions from {@code transaction.metadata.actions}: a list of
     * {@code {label, procedure, inputFields}} objects. Entries lacking a procedure name are skipped; the
     * label defaults to the procedure name. This is the P6 seam before a first-class {@code actions}
     * authoring slot.
     *
     * <p>{@code inputFields} (G3, docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md) mirrors {@code panelAction
     * .inputFields} (Move 2 G3): a list of scalar field names the client collects via an inline
     * "collect input, then invoke" mini-form and merges into the draft body posted to
     * {@code /invoke/{procedure}} -- the mechanism a "propose" step (e.g. parse pasted XML into a
     * draft) needs to seed input a brand-new/empty draft has no field for otherwise.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> workbenchActions(CompiledAutoPanelSurface transaction) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (transaction == null) {
            return actions;
        }
        Object declared = transaction.metadata().get("actions");
        if (!(declared instanceof List<?> list)) {
            return actions;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object procedure = map.get("procedure");
            if (procedure == null || String.valueOf(procedure).isBlank()) {
                continue;
            }
            Object label = map.get("label");
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("label", label == null || String.valueOf(label).isBlank()
                    ? String.valueOf(procedure) : String.valueOf(label));
            action.put("procedure", String.valueOf(procedure).trim());
            List<String> inputFields = new ArrayList<>();
            if (map.get("inputFields") instanceof List<?> fieldList) {
                for (Object field : fieldList) {
                    if (field != null && !String.valueOf(field).isBlank()) {
                        inputFields.add(String.valueOf(field).trim());
                    }
                }
            }
            action.put("inputFields", inputFields);
            Map<String, Object> applyTo = workbenchActionApplyTo(map.get("applyTo"));
            if (applyTo != null) {
                action.put("applyTo", applyTo);
            }
            actions.add(action);
        }
        return actions;
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 2A): declares how this action's
     * {@code invoke()} result folds into the draft, instead of the client's prior unconditional
     * "replace the whole draft" behavior -- the gap that made a pure-computation action like
     * {@code SugerirDestino} silently destroy the rest of the draft. Same untyped-metadata
     * mechanism as the rest of {@code transaction.metadata.actions} (not the schema-typed
     * {@code panelAction} used by regular, non-Workbench panels -- those actions persist directly
     * and have no client-held draft to fold into). Malformed input is dropped rather than thrown,
     * matching this method's own existing defensive style.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> workbenchActionApplyTo(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object collection = map.get("collection");
        Object mode = map.get("mode");
        if (collection == null || String.valueOf(collection).isBlank()
                || mode == null || !"appendRow".equals(String.valueOf(mode).trim())) {
            return null;
        }
        Map<String, String> fieldMap = new LinkedHashMap<>();
        if (map.get("map") instanceof Map<?, ?> rawFieldMap) {
            for (Map.Entry<?, ?> fieldEntry : rawFieldMap.entrySet()) {
                if (fieldEntry.getKey() != null && fieldEntry.getValue() != null) {
                    fieldMap.put(String.valueOf(fieldEntry.getKey()), String.valueOf(fieldEntry.getValue()));
                }
            }
        }
        if (fieldMap.isEmpty()) {
            return null;
        }
        Map<String, Object> applyTo = new LinkedHashMap<>();
        applyTo.put("collection", String.valueOf(collection).trim());
        applyTo.put("mode", "appendRow");
        applyTo.put("map", fieldMap);
        return applyTo;
    }

    private static Map<String, Object> sectionDescriptor(
            CompiledAggregateCollection collection, Map<String, List<String>> fieldsByConcept,
            Map<String, Map<String, Object>> bandPickers) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("collection", collection.name());
        section.put("concept", collection.concept());
        section.put("childField", collection.childField());
        section.put("columns", columnsFor(fieldsByConcept, collection.concept()));
        List<Map<String, Object>> bands = new ArrayList<>();
        for (CompiledAggregateCollection child : collection.collections()) {
            Map<String, Object> band = new LinkedHashMap<>();
            band.put("collection", child.name());
            band.put("concept", child.concept());
            band.put("childField", child.childField());
            band.put("columns", columnsFor(fieldsByConcept, child.concept()));
            Map<String, Object> picker = bandPickers.get(child.name());
            if (picker != null) {
                band.put("picker", picker);
            }
            bands.add(band);
        }
        section.put("bands", bands);
        return section;
    }

    /**
     * Read the reactive recompute procedure name from {@code transaction.metadata.recompute}. Accepts either
     * a bare string or an object with a {@code procedure} key. Null when unset.
     */
    private static String recomputeProcedure(CompiledAutoPanelSurface transaction) {
        if (transaction == null) {
            return null;
        }
        Object declared = transaction.metadata().get("recompute");
        if (declared instanceof Map<?, ?> map) {
            declared = map.get("procedure");
        }
        if (declared == null || String.valueOf(declared).isBlank()) {
            return null;
        }
        return String.valueOf(declared).trim();
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 2B): read declared derived display fields from
     * {@code transaction.metadata.derived} -- a list of {@code {name, expression, label?}} objects.
     * {@code expression} uses a deliberately narrow syntax the client evaluates itself (see
     * workbench-page.html.mustache's {@code evaluateDerived}): {@code sum(<path>)}, where
     * {@code <path>} walks nested collections ({@code itens[].posicoes[].quantidade}) with an
     * optional {@code .filter(field=='literal')} narrowing one array first -- not general CEL
     * (reusing the platform's actual expression-cel engine client-side in a browser is not
     * realistic), but the same expression LANGUAGE FAMILY, not a second one invented from scratch.
     * Entries missing a name or expression are skipped.
     */
    private static List<Map<String, Object>> derivedFields(CompiledAutoPanelSurface transaction) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (transaction == null) {
            return out;
        }
        Object declared = transaction.metadata().get("derived");
        if (!(declared instanceof List<?> list)) {
            return out;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object name = map.get("name");
            Object expression = map.get("expression");
            if (name == null || String.valueOf(name).isBlank()
                    || expression == null || String.valueOf(expression).isBlank()) {
                continue;
            }
            Object label = map.get("label");
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", String.valueOf(name).trim());
            field.put("label", label == null || String.valueOf(label).isBlank()
                    ? String.valueOf(name).trim() : String.valueOf(label).trim());
            field.put("expression", String.valueOf(expression).trim());
            out.add(field);
        }
        return out;
    }

    /**
     * Read per-band row pickers from {@code transaction.metadata.bandPickers}: an object keyed by band
     * collection name, each value {@code {panel, label, columns?}} naming a source Selection panel the client
     * offers as a modal picker (C6 "Seleciona Ruas"). Entries lacking a panel are skipped.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> bandPickers(CompiledAutoPanelSurface transaction) {
        Map<String, Map<String, Object>> pickers = new LinkedHashMap<>();
        if (transaction == null) {
            return pickers;
        }
        Object declared = transaction.metadata().get("bandPickers");
        if (!(declared instanceof Map<?, ?> map)) {
            return pickers;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> spec)) {
                continue;
            }
            Object panel = spec.get("panel");
            if (panel == null || String.valueOf(panel).isBlank()) {
                continue;
            }
            Map<String, Object> picker = new LinkedHashMap<>();
            picker.put("panel", String.valueOf(panel).trim());
            Object label = spec.get("label");
            picker.put("label", label == null || String.valueOf(label).isBlank()
                    ? "Selecionar" : String.valueOf(label));
            Object columns = spec.get("columns");
            if (columns instanceof List<?> cols) {
                picker.put("columns", cols.stream().map(String::valueOf).toList());
            }
            pickers.put(String.valueOf(entry.getKey()), picker);
        }
        return pickers;
    }

    private static List<String> columnsFor(Map<String, List<String>> fieldsByConcept, String concept) {
        return new ArrayList<>(fieldsByConcept.getOrDefault(normalize(concept), List.of()));
    }

    /**
     * Project the root concept's declared lifecycle into a client-facing descriptor: the status field,
     * each state's label + editability (a terminal state, or one flagged {@code metadata.editable=false},
     * is read-only), and the transitions. Null when the concept has no lifecycle (no gating).
     */
    private static Map<String, Object> lifecycleDescriptor(ConceptAst concept) {
        if (concept == null || concept.getLifecycle() == null) {
            return null;
        }
        LifecycleAst lifecycle = concept.getLifecycle();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusField", lifecycle.getStatusField() == null ? "" : lifecycle.getStatusField());
        List<Map<String, Object>> states = new ArrayList<>();
        for (StateMachineStateAst state : lifecycle.getStates()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("value", state.getValue());
            node.put("label", hasText(state.getLabel()) ? state.getLabel() : state.getValue());
            node.put("terminal", state.isTerminal());
            boolean editable = !state.isTerminal()
                    && !"false".equalsIgnoreCase(String.valueOf(state.getMetadata().get("editable")));
            node.put("editable", editable);
            // AW-P5: optional per-state action-rail gating (REG-62, typed array as of
            // docs/CORPUS_INTEGRITY_PLAN.md C8 -- previously a comma-separated string smuggled
            // through the flat string metadata map, with no schema validation at all). Absent/empty
            // = no restriction, every declared action stays enabled.
            if (!state.getAllowedActions().isEmpty()) {
                node.put("allowedActions", state.getAllowedActions());
            }
            states.add(node);
        }
        out.put("states", states);
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (StateTransitionAst transition : lifecycle.getTransitions()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("from", transition.getFrom());
            node.put("to", transition.getTo());
            if (hasText(transition.getActionLabel())) {
                node.put("label", transition.getActionLabel());
            }
            transitions.add(node);
        }
        out.put("transitions", transitions);
        return out;
    }

    /**
     * The Prompt picker this AutoPanel exposes, for FK fields on other concepts' forms to target —
     * or null if this AutoPanel is not concept-bound or does not emit a prompt surface.
     */
    static PromptRef promptRefFor(CompiledAutoPanel autoPanel, List<FieldAst> targetFields) {
        if (autoPanel.concept() == null || autoPanel.concept().isBlank()) {
            return null;
        }
        if (!surfaceEnabled(autoPanel, "prompt")) {
            return null;
        }
        String base = hasText(autoPanel.name()) ? autoPanel.name() : autoPanel.concept();
        List<String> names = new ArrayList<>();
        String idField = null;
        for (FieldAst field : targetFields) {
            names.add(field.getName());
            if (idField == null && field.isId()) {
                idField = field.getName();
            }
        }
        String labelField = promptLabelField(autoPanel, names, idField);
        return new PromptRef(base + "Prompt", idField == null ? "" : idField, labelField == null ? "" : labelField);
    }

    private static boolean surfaceEnabled(CompiledAutoPanel autoPanel, String surface) {
        List<String> surfaces = autoPanel.surfaces();
        if (surfaces.isEmpty()) {
            return true; // default: all surfaces
        }
        return surfaces.stream().anyMatch(s -> s != null && s.trim().equalsIgnoreCase(surface));
    }

    private static CompiledPanel selectionPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute, List<String> fieldNames) {
        List<String> columns = override(autoPanel.selection(), CompiledAutoPanelSurface::columns, fieldNames);
        Map<String, Object> metadata = surfaceMetadata(base, "selection", concept);
        List<String> layoutCols = withComputed(autoPanel.selection(), columns, metadata);
        CompiledPanelLayout layout = new CompiledPanelLayout("table", List.of(), layoutCols, Map.of());
        return new CompiledPanel(
                base + "Selection", baseRoute, concept,
                List.of(conceptDataSource(concept)), layout, List.of(), null, null,
                List.of(newRecordAction(concept)),
                Map.of(), metadata, null);
    }

    private static CompiledPanel detailPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute, List<String> fieldNames) {
        List<String> fields = override(autoPanel.detail(), CompiledAutoPanelSurface::fields, fieldNames);
        List<CompiledPanelFieldBinding> bindings = bindings(fields, false);
        Map<String, Object> metadata = surfaceMetadata(base, "detail", concept);
        List<String> layoutCols = withComputed(autoPanel.detail(), fields, metadata);
        CompiledPanelLayout layout = new CompiledPanelLayout("detail", List.of(), layoutCols, Map.of());
        return new CompiledPanel(
                base + "Detail", baseRoute + "/{id}", concept,
                List.of(conceptDataSource(concept)), layout, bindings, null, null,
                List.of(), Map.of(), metadata, null);
    }

    /**
     * Append the surface's computed columns to the display list (if not already present) and record
     * their expressions under {@code metadata.computed} for the client's Tier-A reactive evaluator.
     * The returned list is used for the layout only — computed columns get no editable binding.
     */
    private static List<String> withComputed(
            CompiledAutoPanelSurface surface, List<String> baseCols, Map<String, Object> metadata) {
        List<String> layoutCols = new ArrayList<>(baseCols);
        if (surface == null || surface.computed().isEmpty()) {
            return layoutCols;
        }
        List<Map<String, Object>> computed = new ArrayList<>();
        for (CompiledAutoPanelComputed c : surface.computed()) {
            if (layoutCols.stream().noneMatch(col -> col.equalsIgnoreCase(c.col()))) {
                layoutCols.add(c.col());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("col", c.col());
            entry.put("expr", c.expr());
            computed.add(entry);
        }
        metadata.put("computed", computed);
        return layoutCols;
    }

    private static CompiledPanel transactionPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute,
            List<FieldAst> fields, String idField, Map<String, PromptRef> promptsByConcept) {
        List<String> defaultEditable = new ArrayList<>();
        for (FieldAst field : fields) {
            if (idField == null || !field.getName().equalsIgnoreCase(idField)) {
                defaultEditable.add(field.getName());
            }
        }
        List<String> formFields = override(autoPanel.transaction(), CompiledAutoPanelSurface::fields, defaultEditable);
        List<CompiledPanelFieldBinding> bindings = bindings(formFields, true);
        List<CompiledPanelAction> actions = List.of(
                mutationAction("save", "Save", concept, "save"),
                mutationAction("delete", "Delete", concept, "delete"));

        Map<String, Object> metadata = surfaceMetadata(base, "transaction", concept);
        // Computed columns are read-only display fields on the form (no editable binding).
        List<String> layoutFields = withComputed(autoPanel.transaction(), formFields, metadata);
        CompiledPanelLayout layout = new CompiledPanelLayout("form", List.of(), layoutFields, Map.of());
        // FK auto-wiring: an editable field that references another concept opens that concept's
        // Prompt picker (if it has one). The runtime/UI reads metadata.fkFields to render the picker.
        List<Map<String, Object>> fkFields = new ArrayList<>();
        for (FieldAst field : fields) {
            String target = field.getReferenceTarget();
            if (target == null || target.isBlank()) {
                continue;
            }
            if (formFields.stream().noneMatch(ff -> ff.equalsIgnoreCase(field.getName()))) {
                continue;
            }
            PromptRef ref = promptsByConcept.get(normalize(target));
            if (ref == null) {
                continue; // target concept has no prompt surface to open
            }
            Map<String, Object> fk = new LinkedHashMap<>();
            fk.put("field", field.getName());
            fk.put("targetConcept", target);
            fk.put("prompt", ref.promptPanel());
            fk.put("returnField", ref.returnField());
            fk.put("labelField", ref.labelField());
            fkFields.add(fk);
        }
        if (!fkFields.isEmpty()) {
            metadata.put("fkFields", fkFields);
        }
        return new CompiledPanel(
                base + "Form", baseRoute + "/edit", concept,
                List.of(conceptDataSource(concept)), layout, bindings, null, null,
                actions, Map.of(), metadata, null);
    }

    /**
     * A modal picker over the concept: choose one row (for foreign-key selection from other forms).
     * Read-only table; metadata declares the display {@code labelField} and the {@code returnField}
     * (the value handed back to the caller). Multi-select pickers over stock locations are an
     * aggregate/band concern (P4), not a single-concept surface.
     */
    private static CompiledPanel promptPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute,
            List<String> fieldNames, String idField) {
        String labelField = promptLabelField(autoPanel, fieldNames, idField);
        List<String> columns = override(autoPanel.prompt(), CompiledAutoPanelSurface::columns, fieldNames);
        CompiledPanelLayout layout = new CompiledPanelLayout("table", List.of(), columns, Map.of());
        Map<String, Object> metadata = surfaceMetadata(base, "prompt", concept);
        metadata.put("labelField", labelField == null ? "" : labelField);
        metadata.put("returnField", idField == null ? "" : idField);
        metadata.put("multiSelect", false);
        return new CompiledPanel(
                base + "Prompt", baseRoute + "/prompt", concept,
                List.of(conceptDataSource(concept)), layout, List.of(), null, null,
                List.of(), Map.of(), metadata, null);
    }

    /** Prompt display field: explicit override, else the first non-id field, else the id field. */
    private static String promptLabelField(CompiledAutoPanel autoPanel, List<String> fieldNames, String idField) {
        if (autoPanel.prompt() != null && hasText(autoPanel.prompt().labelField())) {
            return autoPanel.prompt().labelField();
        }
        for (String field : fieldNames) {
            if (idField == null || !field.equalsIgnoreCase(idField)) {
                return field;
            }
        }
        return idField;
    }

    /**
     * Expand a standalone {@link SelectorAst} into a reusable picker panel (named after the
     * selector, so grids/fields can reference it by name). Metadata carries the pick contract
     * (multiSelect, filters, returnMapping) for the runtime/UI to interpret.
     */
    static CompiledPanel expandSelector(SelectorAst selector, List<String> fieldNames) {
        List<String> columns = selector.columns().isEmpty()
                ? new ArrayList<>(fieldNames)
                : new ArrayList<>(selector.columns());
        CompiledPanelLayout layout = new CompiledPanelLayout("table", List.of(), columns, Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generatedBy", "selector");
        metadata.put("surface", "selector");
        metadata.put("concept", selector.concept());
        metadata.put("multiSelect", selector.multiSelect());
        metadata.put("filters", new ArrayList<>(selector.filters()));
        metadata.put("returnMapping", new LinkedHashMap<>(selector.returnMapping()));
        return new CompiledPanel(
                selector.name(),
                "/select/" + selector.name().toLowerCase(Locale.ROOT),
                selector.concept(),
                List.of(conceptDataSource(selector.concept())), layout, List.of(), null, null,
                List.of(), Map.of(), metadata, null);
    }

    private static CompiledPanelDataSource conceptDataSource(String concept) {
        return new CompiledPanelDataSource("rows", concept, null, null, Map.of(), null, null, null);
    }

    private static List<CompiledPanelFieldBinding> bindings(List<String> fields, boolean editable) {
        List<CompiledPanelFieldBinding> out = new ArrayList<>();
        for (String field : fields) {
            out.add(new CompiledPanelFieldBinding(field, "rows", null, null, null, null, editable));
        }
        return out;
    }

    private static CompiledPanelAction newRecordAction(String concept) {
        return mutationAction("new", "New", concept, "create");
    }

    private static CompiledPanelAction mutationAction(String name, String label, String concept, String operation) {
        return new CompiledPanelAction(
                name, label, "conceptMutation", concept, operation,
                null, null, null, null, List.of(), Map.of(), Map.of(), null, null, List.of());
    }

    private static Map<String, Object> surfaceMetadata(String autoPanel, String surface, String concept) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generatedBy", "autoPanel");
        metadata.put("autoPanel", autoPanel);
        metadata.put("surface", surface);
        metadata.put("concept", concept);
        return metadata;
    }

    private static List<String> override(
            CompiledAutoPanelSurface surface,
            java.util.function.Function<CompiledAutoPanelSurface, List<String>> accessor,
            List<String> fallback) {
        if (surface != null) {
            List<String> declared = accessor.apply(surface);
            if (declared != null && !declared.isEmpty()) {
                return new ArrayList<>(declared);
            }
        }
        return new ArrayList<>(fallback);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
