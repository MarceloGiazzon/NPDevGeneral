package com.npdev.dsl.v1.xref;

import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AggregateFunctionAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ConversionAst;
import com.npdev.dsl.v1.ast.DocumentAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.GroupByFieldAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.ast.GuidePageGadgetAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.PanelActionAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.PanelDataSourceAst;
import com.npdev.dsl.v1.ast.PanelFieldBindingAst;
import com.npdev.dsl.v1.ast.PanelLayoutAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.ast.TransactionHooksAst;
import com.npdev.dsl.v1.ast.WorkbenchActionAst;
import com.npdev.dsl.v1.ast.ProcedureParameterAst;
import com.npdev.dsl.v1.ast.ProcedureVariableAst;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.dsl.v1.query.QueryPredicateGrammar;
import com.npdev.dsl.v1.validation.ExpressionReferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * XREF-1: every reference in a compiled model, in one place.
 *
 * <h2>Why this exists</h2>
 * Measured 2026-08-17 over {@code NPDevSamples/dsl-conformance-max}: a panel's
 * {@code layout.fields}, a panel's {@code fieldBindings[].field}, a {@code panelAction}'s
 * {@code inputFields}, a query's {@code where}/{@code orderBy} and every interaction predicate
 * could all name a field that exists nowhere, and {@code npdev validate model} reported
 * {@code status: passed, errors: 0, warnings: 0}. {@code query.orderBy} reaches generated SQL, so
 * that particular silence is a guaranteed runtime failure the validator called clean. The
 * reference surface is 70 keys across 35 schema definitions; before this class, exactly one of
 * them was cross-referenced anywhere, inside panel provenance, and only for generated panels.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Java, not Python.</b> Pack/context composition and {@code qualifierId::Name}
 *       qualification exist only in {@code ModelSourceResolver}/{@code JsonModelParser}. A second
 *       implementation walking the same graph is REG-108's exact shape.</li>
 *   <li><b>Reads the EFFECTIVE model.</b> Fields arrive by inheritance ({@code extends}), so a
 *       naive "is this name in {@code concept.getFields()}?" check reports inherited fields as
 *       missing. {@link #effectiveFields(ConceptAst)} mirrors
 *       {@code ConceptValidation.resolveEffective}'s own walk, cycle guard included.</li>
 *   <li><b>Three-state resolution.</b> See {@link Resolution}. "Could not evaluate" is recorded as
 *       such and never rounded down to "fine".</li>
 *   <li><b>Deterministic.</b> Insertion-ordered collections throughout, and {@link #edges()} is
 *       sorted by {@link ReferenceEdge#compareTo}. The generator gate SHA-256s emitted files
 *       across two runs; a {@code HashMap} iteration order here would fail it.</li>
 * </ul>
 *
 * <h2>Alias resolution, which is not optional</h2>
 * A naive probe over the 48-model corpus reported 4 orphans, and 3 were false positives of exactly
 * one mistake: a {@code query.orderBy} entry naming a declared {@code aggregates[].name} alias
 * ({@code totalUnits}, {@code taskCount}) rather than a field. {@link #queryEdges} resolves
 * {@code orderBy} against the query's own aggregate aliases and {@code groupBy} entries, and
 * strips a trailing {@code asc}/{@code desc}, BEFORE treating an entry as a field reference.
 */
public final class ReferenceIndex {

    // ---------------------------------------------------------------------------------------
    // Site keys. Stable, dotted, and deliberately spelled out one per constant rather than built
    // by string concatenation at the call site: REG-185 filters on these, and a typo'd site key
    // would silently drop a whole class of reference out of validation with nothing to notice it.
    // ---------------------------------------------------------------------------------------
    public static final String SITE_CONCEPT_REFERENCE_TARGET = "concept.field.reference.target";
    public static final String SITE_CONCEPT_REFERENCE_DISPLAY_FIELD = "concept.field.reference.displayField";
    public static final String SITE_CONCEPT_REFERENCE_SEARCH_FIELDS = "concept.field.reference.searchFields";
    public static final String SITE_CONCEPT_REFERENCE_PREVIEW_FIELDS = "concept.field.reference.previewFields";
    public static final String SITE_CONCEPT_REFERENCE_PICKER_COLUMNS = "concept.field.reference.pickerColumns";
    public static final String SITE_CONCEPT_DOMAIN_TYPE = "concept.field.domainType";
    public static final String SITE_CONCEPT_LIFECYCLE_STATUS_FIELD = "concept.lifecycle.statusField";
    public static final String SITE_CONCEPT_INDEX_FIELDS = "concept.indexes.fields";
    public static final String SITE_CONCEPT_UI_IMAGE_FIELD = "concept.ui.imageField";
    public static final String SITE_CONCEPT_UI_SEARCH_FIELDS = "concept.ui.searchFields";

    public static final String SITE_PANEL_DATASOURCE_CONCEPT = "panel.dataSources.concept";
    public static final String SITE_PANEL_DATASOURCE_QUERY = "panel.dataSources.query";
    public static final String SITE_PANEL_DATASOURCE_PROCEDURE = "panel.dataSources.procedure";
    public static final String SITE_PANEL_DATASOURCE_ON_ROW_LOAD = "panel.dataSources.onRowLoad";
    public static final String SITE_PANEL_DATASOURCE_PARENT = "panel.dataSources.parentDataSource";
    public static final String SITE_PANEL_DATASOURCE_PARENT_FIELD = "panel.dataSources.parentField";
    public static final String SITE_PANEL_DATASOURCE_CHILD_FIELD = "panel.dataSources.childField";
    public static final String SITE_PANEL_DATASOURCE_ADD_FORM_FIELDS = "panel.dataSources.addFormFields";
    public static final String SITE_PANEL_LAYOUT_FIELDS = "panel.layout.fields";
    public static final String SITE_PANEL_FIELD_BINDING_FIELD = "panel.fieldBindings.field";
    public static final String SITE_PANEL_FIELD_BINDING_SOURCE = "panel.fieldBindings.source";
    public static final String SITE_PANEL_FIELD_BINDING_PREDICATE = "panel.fieldBindings.predicate";
    public static final String SITE_PANEL_PREDICATE = "panel.predicate";
    public static final String SITE_PANEL_ACTION_CONCEPT = "panel.actions.concept";
    public static final String SITE_PANEL_ACTION_PROCEDURE = "panel.actions.procedure";
    public static final String SITE_PANEL_ACTION_FLOW = "panel.actions.flow";
    public static final String SITE_PANEL_ACTION_DATASOURCE = "panel.actions.dataSource";
    public static final String SITE_PANEL_ACTION_INPUT_FIELDS = "panel.actions.inputFields";
    public static final String SITE_PANEL_ACTION_PREDICATE = "panel.actions.predicate";
    public static final String SITE_PANEL_GUIDE_PAGE = "panel.guidePage";

    public static final String SITE_QUERY_CONCEPT = "query.concept";
    public static final String SITE_QUERY_ORDER_BY = "query.orderBy";
    public static final String SITE_QUERY_GROUP_BY = "query.groupBy";
    public static final String SITE_QUERY_GROUP_BY_JOIN = "query.groupBy.join";
    public static final String SITE_QUERY_AGGREGATE_FIELD = "query.aggregates.field";
    public static final String SITE_QUERY_WHERE = "query.where";
    public static final String SITE_QUERY_HAVING = "query.having";

    public static final String SITE_PROCEDURE_STEP_CONCEPT = "procedure.steps.concept";
    public static final String SITE_PROCEDURE_STEP_QUERY = "procedure.steps.query";
    public static final String SITE_PROCEDURE_STEP_PROCEDURE = "procedure.steps.procedure";
    public static final String SITE_PROCEDURE_STEP_FLOW = "procedure.steps.flow";
    public static final String SITE_PROCEDURE_STEP_CAPABILITY = "procedure.steps.capability";
    public static final String SITE_PROCEDURE_STEP_EVENT = "procedure.steps.event";
    public static final String SITE_PROCEDURE_STEP_SET_FIELD = "procedure.steps.set";
    public static final String SITE_PROCEDURE_STEP_VAR_FIELD = "procedure.steps.varField";

    public static final String SITE_FLOW_CONCEPT = "flow.concept";
    public static final String SITE_FLOW_STEP_CAPABILITY = "flow.steps.capability";
    public static final String SITE_FLOW_STEP_EVENT = "flow.steps.event";
    public static final String SITE_FLOW_STEP_PROCEDURE = "flow.steps.procedure";
    public static final String SITE_FLOW_STEP_INVARIANT = "flow.steps.invariants";

    public static final String SITE_AGGREGATE_ROOT = "aggregate.root";
    public static final String SITE_AGGREGATE_COLLECTION_CONCEPT = "aggregate.collections.concept";
    public static final String SITE_AGGREGATE_COLLECTION_CHILD_FIELD = "aggregate.collections.childField";
    public static final String SITE_AGGREGATE_COLLECTION_ORDER_BY = "aggregate.collections.orderBy";

    public static final String SITE_AUTOPANEL_CONCEPT = "autoPanel.concept";
    public static final String SITE_AUTOPANEL_AGGREGATE = "autoPanel.aggregate";
    public static final String SITE_AUTOPANEL_SURFACE_FIELDS = "autoPanel.surface.fields";
    public static final String SITE_AUTOPANEL_SURFACE_COLUMNS = "autoPanel.surface.columns";
    public static final String SITE_AUTOPANEL_SURFACE_FILTERS = "autoPanel.surface.filters";
    public static final String SITE_AUTOPANEL_SURFACE_LABEL_FIELD = "autoPanel.surface.labelField";
    public static final String SITE_AUTOPANEL_SURFACE_HOOK = "autoPanel.surface.hooks";
    public static final String SITE_AUTOPANEL_SURFACE_ACTION_PROCEDURE = "autoPanel.surface.actions.procedure";
    public static final String SITE_AUTOPANEL_SURFACE_ACTION_INPUT_FIELDS = "autoPanel.surface.actions.inputFields";
    public static final String SITE_AUTOPANEL_SURFACE_DATASOURCE_PROCEDURE = "autoPanel.surface.dataSource.procedure";

    public static final String SITE_SELECTOR_CONCEPT = "selector.concept";
    public static final String SITE_SELECTOR_COLUMNS = "selector.columns";
    public static final String SITE_SELECTOR_FILTERS = "selector.filters";

    public static final String SITE_GUIDE_PAGE_GADGET_QUERY = "guidePage.gadgets.query";
    public static final String SITE_GUIDE_PAGE_GADGET_AXIS = "guidePage.gadgets.axis";

    public static final String SITE_DOCUMENT_CONCEPT = "document.concept";

    public static final String SITE_CONVERSION_CONCEPT = "conversion.concept";
    public static final String SITE_CONVERSION_FIELD = "conversion.field";
    public static final String SITE_CONVERSION_MATCH_CONCEPT = "conversion.match.concept";

    public static final String KIND_FIELD = "field";
    public static final String KIND_CONCEPT = "concept";
    public static final String KIND_QUERY = "query";
    public static final String KIND_PROCEDURE = "procedure";
    public static final String KIND_FLOW = "flow";
    public static final String KIND_EVENT = "event";
    public static final String KIND_CAPABILITY = "capability";
    public static final String KIND_AGGREGATE = "aggregate";
    public static final String KIND_GUIDE_PAGE = "guidePage";
    public static final String KIND_DATASOURCE = "dataSource";
    public static final String KIND_DOMAIN_TYPE = "domainType";
    public static final String KIND_INVARIANT = "invariant";
    public static final String KIND_EXPRESSION = "expression";
    public static final String KIND_GENERATED_ACTION = "generatedAction";
    public static final String KIND_PARAMETER = "parameter";

    private final List<ReferenceEdge> edges;

    private ReferenceIndex(List<ReferenceEdge> edges) {
        List<ReferenceEdge> copy = new ArrayList<>(edges);
        copy.sort(ReferenceEdge::compareTo);
        this.edges = List.copyOf(copy);
    }

    /** Build the index over an already-resolved (pack/context-composed) model. */
    public static ReferenceIndex build(ModelAst model) {
        return new ReferenceIndex(new Walker(model).walk());
    }

    /** Every edge, in stable emission order. */
    public List<ReferenceEdge> edges() {
        return edges;
    }

    /**
     * Every edge pointing at {@code target}, which is either {@code Concept.field} or a bare
     * object name. Matching is exact on {@code toName}; when {@code target} names a concept, edges
     * pointing at that concept's FIELDS are included too -- "who uses WidgetOrder?" that omitted
     * every panel column reading a WidgetOrder field would be a useless answer.
     */
    public List<ReferenceEdge> usagesOf(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        String wanted = target.trim();
        String fieldPrefix = wanted + ".";
        List<ReferenceEdge> out = new ArrayList<>();
        for (ReferenceEdge edge : edges) {
            if (edge.toName().equals(wanted)
                    || (edge.targetsField() && edge.toName().startsWith(fieldPrefix))
                    || (edge.targetsField() && wanted.equals(edge.ownerConcept()))) {
                out.add(edge);
            }
        }
        return List.copyOf(out);
    }

    /** Edges the walker could not resolve -- both UNRESOLVED and UNDECIDABLE, distinguishable. */
    public List<ReferenceEdge> unresolved() {
        List<ReferenceEdge> out = new ArrayList<>();
        for (ReferenceEdge edge : edges) {
            if (edge.resolution() != Resolution.RESOLVED) {
                out.add(edge);
            }
        }
        return List.copyOf(out);
    }

    // =======================================================================================
    // The walker
    // =======================================================================================

    private static final class Walker {

        private final ModelAst model;
        private final Map<String, ConceptAst> conceptsByLower = new LinkedHashMap<>();
        private final Map<String, Set<String>> effectiveFieldsCache = new LinkedHashMap<>();
        private final Set<String> queryNames = new LinkedHashSet<>();
        private final Set<String> procedureNames = new LinkedHashSet<>();
        private final Set<String> flowNames = new LinkedHashSet<>();
        private final Set<String> eventNames = new LinkedHashSet<>();
        private final Set<String> capabilityNames = new LinkedHashSet<>();
        private final Set<String> aggregateNames = new LinkedHashSet<>();
        private final Set<String> guidePageNames = new LinkedHashSet<>();
        private final Set<String> domainTypeNames = new LinkedHashSet<>();
        private final Map<String, QueryAst> queriesByLower = new LinkedHashMap<>();
        /** Generated actions are DECLARED by the flow step that produces them
         *  ({@code type: generatedAction}, {@code actionName: X}), and REFERENCED elsewhere as the
         *  synthetic capability {@code generated.action.X}. Without this set, every such reference
         *  reads as a capability that does not exist -- 2 of the 6 false positives the first run of
         *  this index produced on dsl-conformance-max. */
        private final Set<String> generatedActionNames = new LinkedHashSet<>();
        /** Per procedure, every name it can accept as an action input: its declared
         *  {@code parameters} and {@code variables}, PLUS every {@code $var} its own steps read.
         *  The last part is not optional -- {@code RenameOrderStatusProcedure} declares no
         *  parameters and reads {@code $newStatus}, so a parameters-only set reports a correct
         *  model as broken. */
        private final Map<String, Set<String>> procedureInputsByLower = new LinkedHashMap<>();
        private final List<ReferenceEdge> edges = new ArrayList<>();

        Walker(ModelAst model) {
            this.model = model;
            for (ConceptAst concept : nullSafe(model.getConcepts())) {
                if (concept != null && hasText(concept.getName())) {
                    conceptsByLower.put(lower(concept.getName()), concept);
                }
            }
            for (QueryAst query : nullSafe(model.getQueries())) {
                if (query != null && hasText(query.name())) {
                    queryNames.add(lower(query.name()));
                    queriesByLower.put(lower(query.name()), query);
                }
            }
            collectNames(procedureNames, nullSafe(model.getProcedures()), ProcedureAst::name);
            for (ProcedureAst procedure : nullSafe(model.getProcedures())) {
                if (procedure == null || !hasText(procedure.name())) {
                    continue;
                }
                Set<String> accepted = new LinkedHashSet<>();
                for (ProcedureParameterAst parameter : nullSafe(procedure.parameters())) {
                    if (parameter != null && hasText(parameter.name())) {
                        accepted.add(lower(parameter.name()));
                    }
                }
                for (ProcedureVariableAst variable : nullSafe(procedure.variables())) {
                    if (variable != null && hasText(variable.name())) {
                        accepted.add(lower(variable.name()));
                    }
                }
                collectDollarReads(procedure.steps(), accepted);
                procedureInputsByLower.put(lower(procedure.name()), accepted);
            }
            for (FlowAst flow : nullSafe(model.getFlows())) {
                if (flow != null && hasText(flow.getName())) {
                    flowNames.add(lower(flow.getName()));
                }
            }
            for (var event : nullSafe(model.getEvents())) {
                if (event != null && hasText(event.getName())) {
                    eventNames.add(lower(event.getName()));
                }
            }
            // A concept may declare its own events; a procedure's `eventPublish` can name either.
            for (ConceptAst concept : conceptsByLower.values()) {
                for (var event : nullSafe(concept.getEvents())) {
                    if (event != null && hasText(event.getName())) {
                        eventNames.add(lower(event.getName()));
                    }
                }
            }
            for (var capability : nullSafe(model.getCapabilities())) {
                if (capability != null && hasText(capability.getName())) {
                    capabilityNames.add(lower(capability.getName()));
                }
            }
            collectNames(aggregateNames, nullSafe(model.getAggregates()), AggregateAst::name);
            collectNames(guidePageNames, nullSafe(model.getGuidePages()), GuidePageAst::name);
            for (var domainType : nullSafe(model.getDomainTypes())) {
                if (domainType != null && hasText(domainType.getName())) {
                    domainTypeNames.add(lower(domainType.getName()));
                }
            }
            for (FlowAst flow : nullSafe(model.getFlows())) {
                if (flow != null) {
                    collectGeneratedActions(flow.getSteps());
                }
            }
        }

        /** Every {@code $name} a procedure's own steps read, at any depth. This is the only
         *  declaration surface a client-collected action input has. */
        private static void collectDollarReads(List<ProcedureStepAst> steps, Set<String> into) {
            for (ProcedureStepAst step : nullSafe(steps)) {
                if (step == null) {
                    continue;
                }
                for (String text : new String[]{step.condition(), step.items(), asText(step.value()),
                        asText(step.left()), asText(step.right())}) {
                    collectDollarNames(text, into);
                }
                if (step.select() != null) {
                    for (Object selected : step.select().values()) {
                        collectDollarNames(asText(selected), into);
                    }
                }
                if (step.set() != null) {
                    for (Object assigned : step.set().values()) {
                        collectDollarNames(asText(assigned), into);
                    }
                }
                collectDollarReads(step.thenSteps(), into);
                collectDollarReads(step.elseSteps(), into);
                collectDollarReads(step.steps(), into);
            }
        }

        private static void collectDollarNames(String text, Set<String> into) {
            if (text == null) {
                return;
            }
            int index = 0;
            while (index < text.length()) {
                int dollar = text.indexOf(DOLLAR, index);
                if (dollar < 0) {
                    return;
                }
                int cursor = dollar + 1;
                while (cursor < text.length() && isIdentifierChar(text.charAt(cursor))) {
                    cursor++;
                }
                if (cursor > dollar + 1) {
                    into.add(lower(text.substring(dollar + 1, cursor)));
                }
                index = Math.max(cursor, dollar + 1);
            }
        }

        private void collectGeneratedActions(List<StepAst> steps) {
            for (StepAst step : nullSafe(steps)) {
                if (step == null) {
                    continue;
                }
                if (hasText(step.getGeneratedActionName())) {
                    generatedActionNames.add(lower(step.getGeneratedActionName()));
                }
                collectGeneratedActions(step.getThenSteps());
                collectGeneratedActions(step.getElseSteps());
                collectGeneratedActions(step.getLoopSteps());
                collectGeneratedActions(step.getOnFailureSteps());
            }
        }

        private static <T> void collectNames(Set<String> into, Collection<T> items,
                                             java.util.function.Function<T, String> nameOf) {
            for (T item : items) {
                if (item == null) {
                    continue;
                }
                String name = nameOf.apply(item);
                if (hasText(name)) {
                    into.add(lower(name));
                }
            }
        }

        List<ReferenceEdge> walk() {
            conceptEdges();
            panelEdges();
            queryEdges();
            procedureEdges();
            flowEdges();
            aggregateEdges();
            autoPanelEdges();
            selectorEdges();
            guidePageEdges();
            documentEdges();
            conversionEdges();
            return edges;
        }

        // -- concepts -----------------------------------------------------------------------

        private void conceptEdges() {
            for (ConceptAst concept : conceptsByLower.values()) {
                String owner = concept.getName();
                String base = "concepts[" + owner + "]";
                for (FieldAst field : nullSafe(concept.getFields())) {
                    if (field == null) {
                        continue;
                    }
                    String fieldBase = base + ".fields[" + safe(field.getName()) + "]";
                    if (hasText(field.getDomainType())) {
                        named(KIND_CONCEPT, owner, SITE_CONCEPT_DOMAIN_TYPE,
                                fieldBase + ".domainType", KIND_DOMAIN_TYPE,
                                field.getDomainType(), domainTypeNames);
                    }
                    ReferenceSemanticsAst semantics = field.getReferenceSemantics();
                    String target = semantics != null && hasText(semantics.getTarget())
                            ? semantics.getTarget() : field.getReferenceTarget();
                    if (!hasText(target)) {
                        continue;
                    }
                    concept(owner, SITE_CONCEPT_REFERENCE_TARGET, fieldBase + ".reference.target", target);
                    // Every *Fields list on a reference names a field of the TARGET, not of the
                    // concept declaring the reference -- getting that backwards would flag every
                    // correct displayField in the corpus.
                    if (semantics != null) {
                        fieldOf(KIND_CONCEPT, owner, SITE_CONCEPT_REFERENCE_DISPLAY_FIELD,
                                fieldBase + ".reference.displayField", target, semantics.getDisplayField());
                        eachField(KIND_CONCEPT, owner, SITE_CONCEPT_REFERENCE_SEARCH_FIELDS,
                                fieldBase + ".reference.searchFields", target, semantics.getSearchFields());
                        eachField(KIND_CONCEPT, owner, SITE_CONCEPT_REFERENCE_PREVIEW_FIELDS,
                                fieldBase + ".reference.previewFields", target, semantics.getPreviewFields());
                        eachField(KIND_CONCEPT, owner, SITE_CONCEPT_REFERENCE_PICKER_COLUMNS,
                                fieldBase + ".reference.pickerColumns", target, semantics.getPickerColumns());
                    }
                }
                LifecycleAst lifecycle = concept.getLifecycle();
                if (lifecycle != null) {
                    fieldOf(KIND_CONCEPT, owner, SITE_CONCEPT_LIFECYCLE_STATUS_FIELD,
                            base + ".lifecycle.statusField", owner, lifecycle.getStatusField());
                }
                int indexPosition = 0;
                for (var index : nullSafe(concept.getIndexes())) {
                    if (index != null) {
                        eachField(KIND_CONCEPT, owner, SITE_CONCEPT_INDEX_FIELDS,
                                base + ".indexes[" + indexPosition + "].fields", owner, index.getFields());
                    }
                    indexPosition++;
                }
                PresentationMetadataAst ui = concept.getUi();
                if (ui != null) {
                    fieldOf(KIND_CONCEPT, owner, SITE_CONCEPT_UI_IMAGE_FIELD,
                            base + ".ui.imageField", owner, ui.getImageField());
                    eachField(KIND_CONCEPT, owner, SITE_CONCEPT_UI_SEARCH_FIELDS,
                            base + ".ui.searchFields", owner, ui.getSearchFields());
                }
            }
        }

        // -- panels -------------------------------------------------------------------------

        private void panelEdges() {
            for (PanelAst panel : nullSafe(model.getPanels())) {
                if (panel == null) {
                    continue;
                }
                String owner = safe(panel.name());
                String base = "panels[" + owner + "]";

                // dataSource name -> concept, so a fieldBinding's `source` can be resolved to the
                // concept whose field it names. Insertion-ordered: the FIRST data source is the
                // panel's primary, which is the fallback both here and in panel provenance.
                Map<String, String> dataSourceConcepts = new LinkedHashMap<>();
                for (PanelDataSourceAst dataSource : nullSafe(panel.dataSources())) {
                    if (dataSource != null && hasText(dataSource.name())) {
                        dataSourceConcepts.put(dataSource.name(), dataSourceConcept(dataSource));
                    }
                }
                String primaryConcept = "";
                for (String candidate : dataSourceConcepts.values()) {
                    if (hasText(candidate)) {
                        primaryConcept = candidate;
                        break;
                    }
                }

                int position = 0;
                for (PanelDataSourceAst dataSource : nullSafe(panel.dataSources())) {
                    if (dataSource == null) {
                        position++;
                        continue;
                    }
                    String dsBase = base + ".dataSources[" + position + "]";
                    concept(owner, SITE_PANEL_DATASOURCE_CONCEPT, dsBase + ".concept", dataSource.concept(),
                            KIND_PANEL);
                    named(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_QUERY, dsBase + ".query",
                            KIND_QUERY, dataSource.query(), queryNames);
                    named(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_PROCEDURE, dsBase + ".procedure",
                            KIND_PROCEDURE, dataSource.procedure(), procedureNames);
                    named(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_ON_ROW_LOAD, dsBase + ".onRowLoad",
                            KIND_PROCEDURE, dataSource.onRowLoad(), procedureNames);
                    if (hasText(dataSource.parentDataSource())) {
                        add(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_PARENT, dsBase + ".parentDataSource",
                                KIND_DATASOURCE, dataSource.parentDataSource(), null,
                                dataSourceConcepts.containsKey(dataSource.parentDataSource())
                                        ? Resolution.RESOLVED : Resolution.UNRESOLVED);
                        // parentField belongs to the PARENT's concept, childField to this one --
                        // the asymmetry is the entire point of a master/detail declaration.
                        fieldOf(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_PARENT_FIELD,
                                dsBase + ".parentField",
                                dataSourceConcepts.getOrDefault(dataSource.parentDataSource(), ""),
                                dataSource.parentField());
                    }
                    fieldOf(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_CHILD_FIELD, dsBase + ".childField",
                            safe(dataSource.concept()), dataSource.childField());
                    eachField(KIND_PANEL, owner, SITE_PANEL_DATASOURCE_ADD_FORM_FIELDS,
                            dsBase + ".addFormFields", safe(dataSource.concept()), dataSource.addFormFields());
                    position++;
                }

                List<String> layoutFields = new ArrayList<>();
                collectLayoutFields(panel.layout(), layoutFields);
                int layoutPosition = 0;
                for (String field : layoutFields) {
                    fieldOf(KIND_PANEL, owner, SITE_PANEL_LAYOUT_FIELDS,
                            base + ".layout.fields[" + layoutPosition + "]", primaryConcept, field);
                    layoutPosition++;
                }

                position = 0;
                for (PanelFieldBindingAst binding : nullSafe(panel.fieldBindings())) {
                    if (binding == null) {
                        position++;
                        continue;
                    }
                    String bindingBase = base + ".fieldBindings[" + position + "]";
                    // A `source` is EITHER a bare data-source name OR the dotted
                    // "<dataSource>.<field>" form. Measured: the dotted form is what most of the
                    // corpus actually writes (medium-expense-approval, npdev-canary, WordLab,
                    // invoice-bonds-demo, the engine probe...), and treating the whole string as a
                    // data-source name produced 160+ of this index's first 173 "orphans" -- all of
                    // them correct models.
                    String sourceDataSource = dataSourceHead(binding.source());
                    String sourceField = dataSourceTail(binding.source());
                    String bindingConcept = hasText(sourceDataSource)
                            ? dataSourceConcepts.getOrDefault(sourceDataSource, primaryConcept)
                            : primaryConcept;
                    if (hasText(sourceDataSource)) {
                        add(KIND_PANEL, owner, SITE_PANEL_FIELD_BINDING_SOURCE, bindingBase + ".source",
                                KIND_DATASOURCE, sourceDataSource, null,
                                dataSourceConcepts.containsKey(sourceDataSource)
                                        ? Resolution.RESOLVED : Resolution.UNRESOLVED);
                        if (hasText(sourceField)) {
                            // Same JSON location as the dataSource edge above -- `source` is ONE
                            // string, "<dataSource>.<field>", and the two edges describe its two
                            // halves. The path must address that string, not an invented
                            // `.source.field` child: --cascade edits at this pointer.
                            fieldOf(KIND_PANEL, owner, SITE_PANEL_FIELD_BINDING_SOURCE,
                                    bindingBase + ".source", bindingConcept, sourceField);
                        }
                    }
                    fieldOf(KIND_PANEL, owner, SITE_PANEL_FIELD_BINDING_FIELD, bindingBase + ".field",
                            bindingConcept, binding.field());
                    predicate(KIND_PANEL, owner, SITE_PANEL_FIELD_BINDING_PREDICATE,
                            bindingBase + ".visibleWhen", bindingConcept, binding.visibleWhen());
                    predicate(KIND_PANEL, owner, SITE_PANEL_FIELD_BINDING_PREDICATE,
                            bindingBase + ".enabledWhen", bindingConcept, binding.enabledWhen());
                    predicate(KIND_PANEL, owner, SITE_PANEL_FIELD_BINDING_PREDICATE,
                            bindingBase + ".readonlyWhen", bindingConcept, binding.readonlyWhen());
                    position++;
                }

                // A panel's `visibility` is a ROLE expression, not a row predicate --
                // `RuntimeApiEmitter.addRoleVisibilityPermission` reads `role:ADMIN` out of it, and
                // `superuser-admin-console` writes a bare `isSuperUser`. Checking it against the
                // concept reported that shipped sample as referencing two fields that do not exist.
                lenientPredicate(KIND_PANEL, owner, SITE_PANEL_PREDICATE, base + ".visibility",
                        primaryConcept, panel.visibility());
                lenientPredicate(KIND_PANEL, owner, SITE_PANEL_PREDICATE, base + ".enabledWhen",
                        primaryConcept, panel.enabledWhen());
                named(KIND_PANEL, owner, SITE_PANEL_GUIDE_PAGE, base + ".guidePage",
                        KIND_GUIDE_PAGE, panel.guidePage(), guidePageNames);

                position = 0;
                for (PanelActionAst action : nullSafe(panel.actions())) {
                    if (action == null) {
                        position++;
                        continue;
                    }
                    String actionBase = base + ".actions[" + position + "]";
                    concept(owner, SITE_PANEL_ACTION_CONCEPT, actionBase + ".concept", action.concept(),
                            KIND_PANEL);
                    named(KIND_PANEL, owner, SITE_PANEL_ACTION_PROCEDURE, actionBase + ".procedure",
                            KIND_PROCEDURE, action.procedure(), procedureNames);
                    named(KIND_PANEL, owner, SITE_PANEL_ACTION_FLOW, actionBase + ".flow",
                            KIND_FLOW, action.flow(), flowNames);
                    if (hasText(dataSourceHead(action.dataSource()))) {
                        add(KIND_PANEL, owner, SITE_PANEL_ACTION_DATASOURCE, actionBase + ".dataSource",
                                KIND_DATASOURCE, dataSourceHead(action.dataSource()), null,
                                dataSourceConcepts.containsKey(dataSourceHead(action.dataSource()))
                                        ? Resolution.RESOLVED : Resolution.UNRESOLVED);
                    }
                    String actionDataSource = dataSourceHead(action.dataSource());
                    String actionConcept = hasText(action.concept()) ? action.concept()
                            : hasText(actionDataSource)
                                ? dataSourceConcepts.getOrDefault(actionDataSource, primaryConcept)
                                : primaryConcept;
                    actionInputFields(KIND_PANEL, owner, SITE_PANEL_ACTION_INPUT_FIELDS,
                            actionBase + ".inputFields", actionConcept, action.procedure(),
                            action.inputFields());
                    lenientPredicate(KIND_PANEL, owner, SITE_PANEL_ACTION_PREDICATE,
                            actionBase + ".visibleWhen", actionConcept, action.visibleWhen());
                    lenientPredicate(KIND_PANEL, owner, SITE_PANEL_ACTION_PREDICATE,
                            actionBase + ".enabledWhen", actionConcept, action.enabledWhen());
                    position++;
                }
            }
        }

        private static void collectLayoutFields(PanelLayoutAst layout, List<String> into) {
            if (layout == null) {
                return;
            }
            for (String field : nullSafe(layout.fields())) {
                if (hasText(field)) {
                    into.add(field);
                }
            }
            for (PanelLayoutAst child : nullSafe(layout.children())) {
                collectLayoutFields(child, into);
            }
        }

        // -- queries ------------------------------------------------------------------------

        private void queryEdges() {
            for (QueryAst query : nullSafe(model.getQueries())) {
                if (query == null) {
                    continue;
                }
                String owner = safe(query.name());
                String base = "queries[" + owner + "]";
                String concept = safe(query.concept());
                concept(owner, SITE_QUERY_CONCEPT, base + ".concept", concept, KIND_QUERY);

                // MEASURED: without this alias set, a naive probe reports 3 false positives across
                // the 48-model corpus -- every one of them an `orderBy` naming a declared aggregate
                // alias (`totalUnits`, `taskCount`), which is legal and reaches SQL as the alias.
                Set<String> aliases = new LinkedHashSet<>();
                for (AggregateFunctionAst aggregate : nullSafe(query.aggregates())) {
                    if (aggregate != null && hasText(aggregate.name())) {
                        aliases.add(lower(aggregate.name()));
                    }
                }
                int position = 0;
                for (GroupByFieldAst groupBy : nullSafe(query.groupBy())) {
                    if (groupBy != null && hasText(groupBy.field())) {
                        aliases.add(lower(groupBy.field()));
                        // The ENTRY, not a `.field` child: a groupBy entry is either the bare
                        // string "warehouseId" or the object {"field": ..., "bucket": ...}, and
                        // only the document knows which. The writer handles both.
                        groupByEdges(owner, base + ".groupBy[" + position + "]", concept, groupBy.field());
                    }
                    position++;
                }
                position = 0;
                for (AggregateFunctionAst aggregate : nullSafe(query.aggregates())) {
                    if (aggregate != null && hasText(aggregate.field())
                            && !"*".equals(aggregate.field().trim())) {
                        fieldOf(KIND_QUERY, owner, SITE_QUERY_AGGREGATE_FIELD,
                                base + ".aggregates[" + position + "].field", concept, aggregate.field());
                    }
                    position++;
                }

                position = 0;
                for (String orderBy : nullSafe(query.orderBy())) {
                    if (hasText(orderBy)) {
                        String bare = stripSortDirection(orderBy);
                        if (!aliases.contains(lower(bare))) {
                            fieldOf(KIND_QUERY, owner, SITE_QUERY_ORDER_BY,
                                    base + ".orderBy[" + position + "]", concept, bare);
                        }
                    }
                    position++;
                }

                queryPredicate(owner, SITE_QUERY_WHERE, base + ".where", concept, query.where(), aliases);
                queryPredicate(owner, SITE_QUERY_HAVING, base + ".having", concept, query.having(), aliases);
            }
        }

        /**
         * A {@code groupBy} entry may be a plain field OR a join path across up to
         * {@link GroupByJoinGrammar#MAX_JOIN_HOPS} reference hops ({@code shipment.invoice.status},
         * {@code billing::invoice.status}). Treating the whole dotted string as one field name is
         * wrong in the worst way -- it reports three CORRECT queries in
         * {@code NPDevSamples/dsl-conformance-max} as orphaned, which is exactly the false-positive
         * rate that gets a checker switched off. {@link GroupByJoinGrammar} is the rule
         * {@code PackValidation.validateQueries} already enforces; this walks the same parse so the
         * two cannot disagree, emitting one edge per hop (each hop IS a real reference to a real
         * field, and {@code --cascade} has to be able to rewrite them).
         */
        private void groupByEdges(String owner, String path, String concept, String rawField) {
            GroupByJoinGrammar.Target target;
            try {
                target = GroupByJoinGrammar.parse(rawField);
            } catch (GroupByJoinGrammar.UnsupportedGroupByPathException unsupported) {
                add(KIND_QUERY, owner, SITE_QUERY_GROUP_BY, path, KIND_FIELD, rawField, null,
                        Resolution.UNDECIDABLE);
                return;
            }
            if (target instanceof GroupByJoinGrammar.Target.Direct direct) {
                fieldOf(KIND_QUERY, owner, SITE_QUERY_GROUP_BY, path, concept, direct.field());
                return;
            }
            GroupByJoinGrammar.Target.Join join = (GroupByJoinGrammar.Target.Join) target;
            String currentConcept = concept;
            int hop = 0;
            for (String referenceFieldName : join.referenceFields()) {
                // A hop is a SEGMENT of the same dotted string the entry holds, so it shares the
                // entry's path -- there is no separate JSON node to point at. The site
                // (query.groupBy.join) is what distinguishes these edges from the final field's.
                fieldOf(KIND_QUERY, owner, SITE_QUERY_GROUP_BY_JOIN, path, currentConcept, referenceFieldName);
                String next = referenceTargetOf(currentConcept, referenceFieldName);
                if (next == null) {
                    // The hop itself is already reported by the edge above (or the field is not a
                    // reference at all, which PackValidation names precisely). Stop rather than
                    // blaming the FINAL field for a break earlier in the chain.
                    return;
                }
                currentConcept = next;
                hop++;
            }
            fieldOf(KIND_QUERY, owner, SITE_QUERY_GROUP_BY, path, currentConcept, join.targetField());
        }

        /** The concept a reference field points at, or null when the field is absent or not a
         *  reference. Walks the effective (inherited) field list, not just the declared one. */
        private String referenceTargetOf(String conceptName, String fieldName) {
            ConceptAst concept = conceptsByLower.get(lower(conceptName));
            while (concept != null) {
                for (FieldAst field : nullSafe(concept.getFields())) {
                    if (field != null && lower(field.getName()).equals(lower(fieldName))) {
                        ReferenceSemanticsAst semantics = field.getReferenceSemantics();
                        String target = semantics != null && hasText(semantics.getTarget())
                                ? semantics.getTarget() : field.getReferenceTarget();
                        return hasText(target) ? target : null;
                    }
                }
                String parent = concept.getExtendsName();
                concept = hasText(parent) ? conceptsByLower.get(lower(parent)) : null;
            }
            return null;
        }

        /** {@code "createdAt desc"} and {@code "createdAt"} are the same field reference. */
        private static String stripSortDirection(String orderBy) {
            String trimmed = orderBy.trim();
            int space = trimmed.lastIndexOf(' ');
            if (space <= 0) {
                return trimmed;
            }
            String tail = trimmed.substring(space + 1).trim().toLowerCase(Locale.ROOT);
            return ("asc".equals(tail) || "desc".equals(tail)) ? trimmed.substring(0, space).trim() : trimmed;
        }

        private void queryPredicate(String owner, String site, String path, String concept,
                                    String expression, Set<String> aliases) {
            if (!hasText(expression)) {
                return;
            }
            List<QueryPredicateGrammar.Clause> clauses;
            try {
                clauses = QueryPredicateGrammar.parse(expression);
            } catch (QueryPredicateGrammar.UnsupportedPredicateException ex) {
                // Outside the compilable grammar. Not an orphan claim -- the query itself will be
                // refused downstream by the code that must actually compile it, and duplicating
                // that refusal here would report the same defect twice under a worse name.
                add(KIND_QUERY, owner, site, path, KIND_EXPRESSION, expression, null,
                        Resolution.UNDECIDABLE);
                return;
            }
            int position = 0;
            for (QueryPredicateGrammar.Clause clause : clauses) {
                if (hasText(clause.field()) && !aliases.contains(lower(clause.field()))) {
                    // `#n`, not `[n]`: `where` is ONE string and n selects a clause within it. The
                    // bracket form named an array element that does not exist.
                    fieldOf(KIND_QUERY, owner, site, path + "#" + position, concept, clause.field());
                }
                position++;
            }
        }

        // -- procedures ---------------------------------------------------------------------

        private void procedureEdges() {
            for (ProcedureAst procedure : nullSafe(model.getProcedures())) {
                if (procedure == null) {
                    continue;
                }
                String owner = safe(procedure.name());
                // A procedure's variables are typed by declaration; a step's `as`/`target` binds
                // more. Both feed $var.field resolution below.
                Map<String, String> varConcepts = new LinkedHashMap<>();
                procedureSteps(owner, "procedures[" + owner + "].steps", procedure.steps(), varConcepts);
            }
        }

        private void procedureSteps(String owner, String base, List<ProcedureStepAst> steps,
                                    Map<String, String> varConcepts) {
            int position = 0;
            for (ProcedureStepAst step : nullSafe(steps)) {
                if (step == null) {
                    position++;
                    continue;
                }
                String stepBase = base + "[" + position + "]";
                concept(owner, SITE_PROCEDURE_STEP_CONCEPT, stepBase + ".concept", step.concept(),
                        KIND_PROCEDURE);
                named(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_QUERY, stepBase + ".query",
                        KIND_QUERY, step.query(), queryNames);
                named(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_PROCEDURE, stepBase + ".procedure",
                        KIND_PROCEDURE, step.procedure(), procedureNames);
                named(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_FLOW, stepBase + ".flow",
                        KIND_FLOW, step.flow(), flowNames);
                capabilityEdge(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_CAPABILITY,
                        stepBase + ".capability", step.capability());
                named(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_EVENT, stepBase + ".event",
                        KIND_EVENT, step.event(), eventNames);

                // Coverage class 4: bind `$var` to a concept where the producing step says so.
                String binding = hasText(step.as()) ? step.as() : step.target();
                if (hasText(binding) && hasText(step.concept())) {
                    varConcepts.put(stripDollar(binding), step.concept());
                } else if (hasText(binding) && hasText(step.query())) {
                    QueryAst source = queriesByLower.get(lower(step.query()));
                    if (source != null && hasText(source.concept())) {
                        varConcepts.put(stripDollar(binding), source.concept());
                    }
                }

                if (step.set() != null && hasText(step.concept())) {
                    for (String key : step.set().keySet()) {
                        fieldOf(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_SET_FIELD,
                                stepBase + ".set." + key, step.concept(), key);
                    }
                }
                varFieldEdges(owner, stepBase, step, varConcepts);

                procedureSteps(owner, stepBase + ".thenSteps", step.thenSteps(), varConcepts);
                procedureSteps(owner, stepBase + ".elseSteps", step.elseSteps(), varConcepts);
                procedureSteps(owner, stepBase + ".steps", step.steps(), varConcepts);
                position++;
            }
        }

        /**
         * Coverage class 4: {@code $var.field} on the READ side. Resolves {@code $var} back to the
         * step that produced it; anything whose producer is a {@code capabilityCall} (or any step
         * whose output shape is declared elsewhere) is UNDECIDABLE, never guessed.
         */
        private void varFieldEdges(String owner, String stepBase, ProcedureStepAst step,
                                   Map<String, String> varConcepts) {
            // Keyed by the STEP KEY the text came from, not just the text: the path has to address
            // the real JSON string so `--cascade` can edit it. An earlier version emitted
            // `steps[0].$p.birthDay`, which names the variable and points at nothing.
            Map<String, String> expressions = new LinkedHashMap<>();
            putIfText(expressions, "condition", step.condition());
            putIfText(expressions, "items", step.items());
            putIfText(expressions, "value", asText(step.value()));
            putIfText(expressions, "left", asText(step.left()));
            putIfText(expressions, "right", asText(step.right()));
            if (step.select() != null) {
                for (Map.Entry<String, Object> entry : step.select().entrySet()) {
                    putIfText(expressions, "select." + entry.getKey(), asText(entry.getValue()));
                }
            }
            for (Map.Entry<String, String> entry : expressions.entrySet()) {
                int position = 0;
                for (String reference : dollarFieldReferences(entry.getValue())) {
                    int dot = reference.indexOf('.');
                    String variable = reference.substring(1, dot);
                    String field = reference.substring(dot + 1);
                    String concept = varConcepts.get(variable);
                    String path = stepBase + "." + entry.getKey() + "#" + position;
                    if (concept == null) {
                        add(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_VAR_FIELD,
                                path, KIND_FIELD, reference, null, Resolution.UNDECIDABLE);
                    } else {
                        fieldOf(KIND_PROCEDURE, owner, SITE_PROCEDURE_STEP_VAR_FIELD,
                                path, concept, field);
                    }
                    position++;
                }
            }
        }

        // -- flows --------------------------------------------------------------------------

        private void flowEdges() {
            for (FlowAst flow : nullSafe(model.getFlows())) {
                if (flow == null) {
                    continue;
                }
                String owner = safe(flow.getName());
                String base = "flows[" + owner + "]";
                concept(owner, SITE_FLOW_CONCEPT, base + ".concept", flow.getConcept(), KIND_FLOW);
                flowSteps(owner, base + ".steps", flow.getSteps(), flow.getConcept());
            }
        }

        private void flowSteps(String owner, String base, List<StepAst> steps, String concept) {
            int position = 0;
            for (StepAst step : nullSafe(steps)) {
                if (step == null) {
                    position++;
                    continue;
                }
                String stepBase = base + "[" + position + "]";
                capabilityEdge(KIND_FLOW, owner, SITE_FLOW_STEP_CAPABILITY, stepBase + ".capability",
                        step.getCapability());
                named(KIND_FLOW, owner, SITE_FLOW_STEP_EVENT, stepBase + ".event",
                        KIND_EVENT, step.getEvent(), eventNames);
                named(KIND_FLOW, owner, SITE_FLOW_STEP_PROCEDURE, stepBase + ".procedure",
                        KIND_PROCEDURE, step.getProcedure(), procedureNames);
                int invariantPosition = 0;
                for (String invariant : nullSafe(step.getInvariants())) {
                    if (hasText(invariant)) {
                        add(KIND_FLOW, owner, SITE_FLOW_STEP_INVARIANT,
                                stepBase + ".invariants[" + invariantPosition + "]", KIND_INVARIANT,
                                invariant, concept,
                                invariantExists(concept, invariant) ? Resolution.RESOLVED : Resolution.UNRESOLVED);
                    }
                    invariantPosition++;
                }
                flowSteps(owner, stepBase + ".thenSteps", step.getThenSteps(), concept);
                flowSteps(owner, stepBase + ".elseSteps", step.getElseSteps(), concept);
                flowSteps(owner, stepBase + ".loopSteps", step.getLoopSteps(), concept);
                flowSteps(owner, stepBase + ".onFailureSteps", step.getOnFailureSteps(), concept);
                position++;
            }
        }

        private boolean invariantExists(String conceptName, String invariantName) {
            ConceptAst concept = conceptsByLower.get(lower(conceptName));
            if (concept == null) {
                return false;
            }
            for (var invariant : nullSafe(concept.getInvariants())) {
                if (invariant != null && lower(invariant.getName()).equals(lower(invariantName))) {
                    return true;
                }
            }
            return false;
        }

        // -- aggregates ---------------------------------------------------------------------

        private void aggregateEdges() {
            for (AggregateAst aggregate : nullSafe(model.getAggregates())) {
                if (aggregate == null) {
                    continue;
                }
                String owner = safe(aggregate.name());
                String base = "aggregates[" + owner + "]";
                concept(owner, SITE_AGGREGATE_ROOT, base + ".root", aggregate.root(), KIND_AGGREGATE);
                aggregateCollections(owner, base + ".collections", aggregate.collections(), aggregate.root());
            }
        }

        private void aggregateCollections(String owner, String base,
                                          List<AggregateCollectionAst> collections, String parentConcept) {
            int position = 0;
            for (AggregateCollectionAst collection : nullSafe(collections)) {
                if (collection == null) {
                    position++;
                    continue;
                }
                String collectionBase = base + "[" + safe(collection.name()) + "]";
                concept(owner, SITE_AGGREGATE_COLLECTION_CONCEPT, collectionBase + ".concept",
                        collection.concept(), KIND_AGGREGATE);
                // childField is the FK on the CHILD pointing back at the parent.
                fieldOf(KIND_AGGREGATE, owner, SITE_AGGREGATE_COLLECTION_CHILD_FIELD,
                        collectionBase + ".childField", safe(collection.concept()), collection.childField());
                if (hasText(collection.orderBy())) {
                    fieldOf(KIND_AGGREGATE, owner, SITE_AGGREGATE_COLLECTION_ORDER_BY,
                            collectionBase + ".orderBy", safe(collection.concept()),
                            stripSortDirection(collection.orderBy()));
                }
                aggregateCollections(owner, collectionBase + ".collections", collection.collections(),
                        collection.concept());
                position++;
            }
        }

        // -- autoPanels / selectors ---------------------------------------------------------

        private void autoPanelEdges() {
            for (AutoPanelAst autoPanel : nullSafe(model.getAutoPanels())) {
                if (autoPanel == null) {
                    continue;
                }
                String owner = safe(autoPanel.name());
                String base = "autoPanels[" + owner + "]";
                concept(owner, SITE_AUTOPANEL_CONCEPT, base + ".concept", autoPanel.concept(),
                        KIND_AUTO_PANEL);
                named(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_AGGREGATE, base + ".aggregate",
                        KIND_AGGREGATE, autoPanel.aggregate(), aggregateNames);
                // An autoPanel over an aggregate takes its fields from the aggregate ROOT.
                String concept = hasText(autoPanel.concept()) ? autoPanel.concept()
                        : aggregateRoot(autoPanel.aggregate());
                autoPanelSurface(owner, base + ".selection", autoPanel.selection(), concept);
                autoPanelSurface(owner, base + ".detail", autoPanel.detail(), concept);
                autoPanelSurface(owner, base + ".transaction", autoPanel.transaction(), concept);
                autoPanelSurface(owner, base + ".prompt", autoPanel.prompt(), concept);
            }
        }

        private String aggregateRoot(String aggregateName) {
            if (!hasText(aggregateName)) {
                return "";
            }
            for (AggregateAst aggregate : nullSafe(model.getAggregates())) {
                if (aggregate != null && lower(aggregate.name()).equals(lower(aggregateName))) {
                    return safe(aggregate.root());
                }
            }
            return "";
        }

        private void autoPanelSurface(String owner, String base, AutoPanelSurfaceAst surface, String concept) {
            if (surface == null) {
                return;
            }
            eachField(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_FIELDS, base + ".fields",
                    concept, surface.fields());
            eachField(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_COLUMNS, base + ".columns",
                    concept, surface.columns());
            eachField(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_FILTERS, base + ".filters",
                    concept, surface.filters());
            fieldOf(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_LABEL_FIELD, base + ".labelField",
                    concept, surface.labelField());
            TransactionHooksAst hooks = surface.hooks();
            if (hooks != null) {
                hook(owner, base + ".hooks.onLoad", hooks.onLoad());
                hook(owner, base + ".hooks.onFieldChange", hooks.onFieldChange());
                hook(owner, base + ".hooks.beforeAction", hooks.beforeAction());
                hook(owner, base + ".hooks.onValidate", hooks.onValidate());
                hook(owner, base + ".hooks.onCommit", hooks.onCommit());
            }
            if (surface.dataSource() != null) {
                named(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_DATASOURCE_PROCEDURE,
                        base + ".dataSource.procedure", KIND_PROCEDURE,
                        surface.dataSource().procedure(), procedureNames);
            }
            int position = 0;
            for (WorkbenchActionAst action : nullSafe(surface.actions())) {
                if (action != null) {
                    named(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_ACTION_PROCEDURE,
                            base + ".actions[" + position + "].procedure", KIND_PROCEDURE,
                            action.procedure(), procedureNames);
                    actionInputFields(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_ACTION_INPUT_FIELDS,
                            base + ".actions[" + position + "].inputFields", concept,
                            action.procedure(), action.inputFields());
                }
                position++;
            }
        }

        private void hook(String owner, String path, String procedure) {
            named(KIND_AUTO_PANEL, owner, SITE_AUTOPANEL_SURFACE_HOOK, path,
                    KIND_PROCEDURE, procedure, procedureNames);
        }

        private void selectorEdges() {
            for (SelectorAst selector : nullSafe(model.getSelectors())) {
                if (selector == null) {
                    continue;
                }
                String owner = safe(selector.name());
                String base = "selectors[" + owner + "]";
                concept(owner, SITE_SELECTOR_CONCEPT, base + ".concept", selector.concept(), KIND_SELECTOR);
                eachField(KIND_SELECTOR, owner, SITE_SELECTOR_COLUMNS, base + ".columns",
                        safe(selector.concept()), selector.columns());
                eachField(KIND_SELECTOR, owner, SITE_SELECTOR_FILTERS, base + ".filters",
                        safe(selector.concept()), selector.filters());
            }
        }

        // -- guide pages / documents / conversions ------------------------------------------

        private void guidePageEdges() {
            for (GuidePageAst page : nullSafe(model.getGuidePages())) {
                if (page == null) {
                    continue;
                }
                String owner = safe(page.name());
                String base = "guidePages[" + owner + "]";
                int position = 0;
                for (GuidePageGadgetAst gadget : nullSafe(page.gadgets())) {
                    if (gadget == null) {
                        position++;
                        continue;
                    }
                    String gadgetBase = base + ".gadgets[" + position + "]";
                    named(KIND_GUIDE_PAGE, owner, SITE_GUIDE_PAGE_GADGET_QUERY, gadgetBase + ".query",
                            KIND_QUERY, gadget.query(), queryNames);
                    // A gadget axis names a COLUMN OF THE QUERY -- which is either one of the
                    // query's own aggregate/groupBy aliases or a field of its concept. Checking it
                    // against the concept alone would report every chart over an aggregate query as
                    // broken; checking it against nothing is what shipped before XREF-1.
                    gadgetAxis(owner, gadgetBase + ".x", gadget.query(), gadget.x());
                    gadgetAxis(owner, gadgetBase + ".y", gadget.query(), gadget.y());
                    gadgetAxis(owner, gadgetBase + ".series", gadget.query(), gadget.series());
                    position++;
                }
            }
        }

        private void gadgetAxis(String owner, String path, String queryName, String axis) {
            if (!hasText(axis)) {
                return;
            }
            QueryAst query = queriesByLower.get(lower(queryName));
            if (query == null) {
                add(KIND_GUIDE_PAGE, owner, SITE_GUIDE_PAGE_GADGET_AXIS, path, KIND_FIELD, axis, null,
                        Resolution.UNDECIDABLE);
                return;
            }
            for (AggregateFunctionAst aggregate : nullSafe(query.aggregates())) {
                if (aggregate != null && lower(aggregate.name()).equals(lower(axis))) {
                    return;
                }
            }
            for (GroupByFieldAst groupBy : nullSafe(query.groupBy())) {
                if (groupBy != null && lower(groupBy.field()).equals(lower(axis))) {
                    return;
                }
            }
            fieldOf(KIND_GUIDE_PAGE, owner, SITE_GUIDE_PAGE_GADGET_AXIS, path, safe(query.concept()), axis);
        }

        private void documentEdges() {
            for (DocumentAst document : nullSafe(model.getDocuments())) {
                if (document == null) {
                    continue;
                }
                concept(safe(document.name()), SITE_DOCUMENT_CONCEPT,
                        "documents[" + safe(document.name()) + "].concept", document.concept(), KIND_DOCUMENT);
            }
        }

        private void conversionEdges() {
            for (ConversionAst conversion : nullSafe(model.getConversions())) {
                if (conversion == null) {
                    continue;
                }
                String owner = safe(conversion.id());
                String base = "conversions[" + owner + "]";
                String concept = safe(conversion.concept());
                concept(owner, SITE_CONVERSION_CONCEPT, base + ".concept", concept, KIND_CONVERSION);
                fieldOf(KIND_CONVERSION, owner, SITE_CONVERSION_FIELD, base + ".from", concept, conversion.from());
                fieldOf(KIND_CONVERSION, owner, SITE_CONVERSION_FIELD, base + ".to", concept, conversion.to());
                fieldOf(KIND_CONVERSION, owner, SITE_CONVERSION_FIELD, base + ".set", concept, conversion.set());
                eachField(KIND_CONVERSION, owner, SITE_CONVERSION_FIELD, base + ".mergeFrom",
                        concept, conversion.mergeFrom());
                int position = 0;
                for (var target : nullSafe(conversion.into())) {
                    if (target != null) {
                        fieldOf(KIND_CONVERSION, owner, SITE_CONVERSION_FIELD,
                                base + ".into[" + position + "].field", concept, target.field());
                    }
                    position++;
                }
                if (conversion.match() != null) {
                    concept(owner, SITE_CONVERSION_MATCH_CONCEPT, base + ".match.concept",
                            conversion.match().concept(), KIND_CONVERSION);
                }
            }
        }

        // =====================================================================================
        // Edge construction primitives
        // =====================================================================================

        private void add(String fromKind, String fromName, String site, String path, String toKind,
                         String toName, String ownerConcept, Resolution resolution) {
            edges.add(new ReferenceEdge(fromKind, fromName, site, path, toKind, toName,
                    ownerConcept, resolution));
        }

        /**
         * A capability reference spelled {@code generated.action.X} does not name a declared
         * capability at all -- it names a generated action, DECLARED by the flow step that produces
         * it ({@code type: generatedAction}, {@code actionName: X}) and synthesized into this
         * namespace by the parser. Resolving it against {@code capabilities[]} reported two correct
         * flows in {@code dsl-conformance-max} as calling a capability that does not exist, which is
         * the kind of confident-and-wrong answer that makes an index worse than none.
         */
        private void capabilityEdge(String fromKind, String fromName, String site, String path,
                                    String capability) {
            if (!hasText(capability)) {
                return;
            }
            if (lower(capability).startsWith(GENERATED_ACTION_PREFIX)) {
                String actionName = capability.substring(GENERATED_ACTION_PREFIX.length());
                add(fromKind, fromName, site, path, KIND_GENERATED_ACTION, actionName, null,
                        generatedActionNames.contains(lower(actionName))
                                ? Resolution.RESOLVED : Resolution.UNRESOLVED);
                return;
            }
            named(fromKind, fromName, site, path, KIND_CAPABILITY, capability, capabilityNames);
        }

        /**
         * An action's {@code inputFields} names the inputs the CLIENT collects and hands to the
         * procedure -- not fields of the concept. Measured on {@code dsl-conformance-max}'s
         * {@code RenameOrderStatusProcedure}: it declares no {@code parameters} at all, and its
         * {@code inputFields: ["newStatus"]} is read inside the procedure as {@code $newStatus}. So
         * the real declaration surfaces are the procedure's declared parameters/variables and the
         * {@code $var} reads in its own steps; checking against the CONCEPT instead (this method's
         * first draft) reported a shipped, deliberate conformance witness as an orphan.
         *
         * <p>But a panel action bound to {@code conceptMutation}/{@code conceptQuery} instead
         * collects CONCEPT fields, so both surfaces are tried -- procedure inputs first (a named
         * procedure is the more specific claim), then the concept's effective fields. Dropping the
         * concept surface, this method's second draft, made three correct
         * {@code WidgetOrderReviewPanel} action inputs UNDECIDABLE that the index can in fact
         * resolve exactly.
         *
         * <p>An entry matching NEITHER is {@link Resolution#UNDECIDABLE}, not UNRESOLVED, and that
         * is an honest limit rather than a hedge: an input the procedure never reads is
         * indistinguishable, from the model alone, from one read through a path this index does not
         * follow. Claiming "orphan" would assert more than is known -- the mirror image of the
         * silence REG-185 exists to fix, and just as wrong.
         */
        private void actionInputFields(String fromKind, String fromName, String site, String path,
                                       String concept, String procedure, List<String> inputFields) {
            List<String> fields = nullSafe(inputFields);
            if (fields.isEmpty()) {
                return;
            }
            Set<String> accepted = hasText(procedure)
                    ? procedureInputsByLower.get(lower(procedure)) : null;
            Set<String> conceptFields = hasText(concept) ? effectiveFields(concept) : null;
            int position = 0;
            for (String field : fields) {
                if (!hasText(field)) {
                    position++;
                    continue;
                }
                String entryPath = path + "[" + position + "]";
                position++;
                if (accepted != null && accepted.contains(lower(field))) {
                    add(fromKind, fromName, site, entryPath, KIND_PARAMETER,
                            procedure + "." + field, null, Resolution.RESOLVED);
                    continue;
                }
                if (conceptFields != null && conceptFields.contains(lower(field))) {
                    add(fromKind, fromName, site, entryPath, KIND_FIELD,
                            concept + "." + field, concept, Resolution.RESOLVED);
                    continue;
                }
                // No procedure named => the concept's fields are the ONLY surface this entry can
                // legally draw from (a `conceptMutation`/`conceptQuery` action collects concept
                // fields), so a miss really is an orphan and is reported as one. With a procedure
                // named, an unmatched entry may be a client-collected input read through a path
                // this index does not follow, and asserting "orphan" would claim more than is known.
                if (!hasText(procedure) && conceptFields != null) {
                    add(fromKind, fromName, site, entryPath, KIND_FIELD,
                            concept + "." + field, concept, Resolution.UNRESOLVED);
                    continue;
                }
                add(fromKind, fromName, site, entryPath, KIND_PARAMETER,
                        (hasText(procedure) ? procedure + "." : "") + field, null,
                        Resolution.UNDECIDABLE);
            }
        }

        private void concept(String fromName, String site, String path, String target) {
            concept(fromName, site, path, target, KIND_CONCEPT);
        }

        private void concept(String fromName, String site, String path, String target, String fromKind) {
            if (!hasText(target)) {
                return;
            }
            add(fromKind, fromName, site, path, KIND_CONCEPT, target, null,
                    conceptsByLower.containsKey(lower(target)) ? Resolution.RESOLVED : Resolution.UNRESOLVED);
        }

        private void named(String fromKind, String fromName, String site, String path, String toKind,
                           String target, Set<String> known) {
            if (!hasText(target)) {
                return;
            }
            add(fromKind, fromName, site, path, toKind, target, null,
                    known.contains(lower(target)) ? Resolution.RESOLVED : Resolution.UNRESOLVED);
        }

        private void eachField(String fromKind, String fromName, String site, String path,
                               String concept, List<String> fields) {
            int position = 0;
            for (String field : nullSafe(fields)) {
                fieldOf(fromKind, fromName, site, path + "[" + position + "]", concept, field);
                position++;
            }
        }

        private void fieldOf(String fromKind, String fromName, String site, String path,
                             String concept, String field) {
            if (!hasText(field)) {
                return;
            }
            if (!hasText(concept)) {
                // No concept in scope to check against -- a panel with no data source, an
                // autoPanel over an aggregate whose root is itself unresolved. Recording this as
                // UNRESOLVED would blame the field for the concept's absence, which is reported
                // separately by its own edge.
                add(fromKind, fromName, site, path, KIND_FIELD, field, null, Resolution.UNDECIDABLE);
                return;
            }
            Set<String> known = effectiveFields(concept);
            if (known == null) {
                add(fromKind, fromName, site, path, KIND_FIELD, concept + "." + field, concept,
                        Resolution.UNDECIDABLE);
                return;
            }
            add(fromKind, fromName, site, path, KIND_FIELD, concept + "." + field, concept,
                    known.contains(lower(field)) ? Resolution.RESOLVED : Resolution.UNRESOLVED);
        }

        /**
         * A predicate whose identifiers may legitimately name something OTHER than a field of the
         * concept -- a role, a session flag, an app-level condition -- none of which has a
         * declaration surface in the model. An identifier that IS a field resolves normally (so
         * {@code inspect usage} still finds a field used in a visibility rule); one that is not is
         * {@link Resolution#UNDECIDABLE} rather than an orphan claim.
         */
        private void lenientPredicate(String fromKind, String fromName, String site, String path,
                                      String concept, String expression) {
            if (!hasText(expression)) {
                return;
            }
            Optional<List<String>> references = ExpressionReferences.references(expression);
            if (references.isEmpty()) {
                add(fromKind, fromName, site, path, KIND_EXPRESSION, expression, null,
                        Resolution.UNDECIDABLE);
                return;
            }
            Set<String> known = hasText(concept) ? effectiveFields(concept) : null;
            int position = 0;
            for (String reference : references.get()) {
                if (known != null && known.contains(lower(reference))) {
                    add(fromKind, fromName, site, path + "#" + position, KIND_FIELD,
                            concept + "." + reference, concept, Resolution.RESOLVED);
                } else {
                    add(fromKind, fromName, site, path + "#" + position, KIND_EXPRESSION,
                            reference, null, Resolution.UNDECIDABLE);
                }
                position++;
            }
        }

        /**
         * A data source names its concept directly OR inherits it from the query it runs -- most of
         * the corpus uses the query form ({@code {"name": "pendingExpenses", "query": "..."}}), and
         * reading only {@code concept} leaves the panel with no concept in scope, which silently
         * downgrades every one of its field references to UNDECIDABLE.
         */
        private String dataSourceConcept(PanelDataSourceAst dataSource) {
            if (hasText(dataSource.concept())) {
                return dataSource.concept();
            }
            if (hasText(dataSource.query())) {
                QueryAst query = queriesByLower.get(lower(dataSource.query()));
                if (query != null && hasText(query.concept())) {
                    return query.concept();
                }
            }
            // A procedure-backed data source has no statically known concept. Blank, so callers
            // report UNDECIDABLE rather than resolving against the wrong concept.
            return "";
        }

        private void predicate(String fromKind, String fromName, String site, String path,
                               String concept, String expression) {
            if (!hasText(expression)) {
                return;
            }
            Optional<List<String>> references = ExpressionReferences.references(expression);
            if (references.isEmpty()) {
                // `$ui.mode == 'edit'` and friends are legal and outside the identifier grammar.
                add(fromKind, fromName, site, path, KIND_EXPRESSION, expression, null,
                        Resolution.UNDECIDABLE);
                return;
            }
            int position = 0;
            for (String reference : references.get()) {
                fieldOf(fromKind, fromName, site, path + "#" + position, concept, reference);
                position++;
            }
        }

        /**
         * Field names of {@code conceptName}, INCLUDING inherited ones, lowercased. Null when the
         * concept is not in the model at all -- the caller turns that into UNDECIDABLE rather than
         * UNRESOLVED, because "field X of a concept that does not exist" is one defect reported by
         * the concept edge, not two.
         *
         * <p>Mirrors {@code ConceptValidation.resolveEffective}: walk {@code extends} upward,
         * parent fields first, with a cycle guard. {@code specializes} is deliberately NOT walked
         * -- specialization is already flattened into the effective model before this runs.
         */
        private Set<String> effectiveFields(String conceptName) {
            String key = lower(conceptName);
            Set<String> cached = effectiveFieldsCache.get(key);
            if (cached != null) {
                return cached;
            }
            ConceptAst concept = conceptsByLower.get(key);
            if (concept == null) {
                return null;
            }
            Set<String> names = new LinkedHashSet<>();
            Set<String> visited = new LinkedHashSet<>();
            ConceptAst current = concept;
            while (current != null && visited.add(lower(current.getName()))) {
                for (FieldAst field : nullSafe(current.getFields())) {
                    if (field != null && hasText(field.getName())) {
                        names.add(lower(field.getName()));
                    }
                }
                String parent = current.getExtendsName();
                current = hasText(parent) ? conceptsByLower.get(lower(parent)) : null;
            }
            Set<String> result = Set.copyOf(names);
            effectiveFieldsCache.put(key, result);
            return result;
        }
    }

    private static final char DOLLAR = '$';
    private static final String GENERATED_ACTION_PREFIX = "generated.action.";
    private static final String KIND_PANEL = "panel";
    private static final String KIND_AUTO_PANEL = "autoPanel";
    private static final String KIND_SELECTOR = "selector";
    private static final String KIND_DOCUMENT = "document";
    private static final String KIND_CONVERSION = "conversion";

    // ------------------------------------------------------------------------------------------
    // Small shared helpers. Deliberately private statics on the outer class rather than a utility
    // class: they are three lines each and exist only to keep the walker readable.
    // ------------------------------------------------------------------------------------------

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** The data-source half of a {@code fieldBindings[].source}, which is either {@code "orders"}
     *  or {@code "orders.status"}. */
    private static String dataSourceHead(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        int dot = source.indexOf('.');
        return dot < 0 ? source.trim() : source.substring(0, dot).trim();
    }

    /** The field half of a dotted {@code fieldBindings[].source}, or blank when there is none. */
    private static String dataSourceTail(String source) {
        if (source == null) {
            return "";
        }
        int dot = source.indexOf('.');
        return dot < 0 ? "" : source.substring(dot + 1).trim();
    }

    private static void addIfText(List<String> into, String value) {
        if (hasText(value)) {
            into.add(value);
        }
    }

    private static void putIfText(Map<String, String> into, String key, String value) {
        if (hasText(value)) {
            into.put(key, value);
        }
    }

    private static String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String stripDollar(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("$") ? trimmed.substring(1) : trimmed;
    }

    /**
     * Pull {@code $var.field} occurrences out of arbitrary step text. Deliberately a narrow scan
     * rather than an expression parser: procedure step values are not one grammar (they are
     * literals, template strings, and predicates), so anything cleverer here would be guessing.
     * Only the FIRST dotted segment is taken -- {@code $order.customer.email} yields
     * {@code $order.customer}, which is the field on the concept; the rest is a traversal this
     * index does not model and the caller records as such.
     */
    private static List<String> dollarFieldReferences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int index = 0;
        while (index < text.length()) {
            int dollar = text.indexOf('$', index);
            if (dollar < 0) {
                break;
            }
            int cursor = dollar + 1;
            while (cursor < text.length() && isIdentifierChar(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor > dollar + 1 && cursor < text.length() && text.charAt(cursor) == '.') {
                int fieldStart = cursor + 1;
                int fieldEnd = fieldStart;
                while (fieldEnd < text.length() && isIdentifierChar(text.charAt(fieldEnd))) {
                    fieldEnd++;
                }
                if (fieldEnd > fieldStart) {
                    out.add(text.substring(dollar, fieldEnd));
                }
                index = fieldEnd;
            } else {
                index = cursor;
            }
        }
        return out;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
