package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelFieldBinding;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.procedures.DefaultProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import com.npdev.kernel.procedures.ProcedureStep;
import com.npdev.kernel.procedures.ProcedureStepType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PanelRuntime {
    private static final String ENDPOINT_VERSION = "1.0.0";

    private final RuntimeMetadataService runtimeMetadataService;
    private final PermissionAwareUiMetadataService permissionAwareUiMetadataService;
    private final CompiledModel compiledModel;
    private final ConceptGateway conceptGateway;
    private final CapabilityDispatcher capabilityDispatcher;
    private final EventBus eventBus;
    private final AggregateRuntime aggregateRuntime;

    public PanelRuntime(
            RuntimeMetadataService runtimeMetadataService,
            PermissionAwareUiMetadataService permissionAwareUiMetadataService
    ) {
        this(
                runtimeMetadataService,
                permissionAwareUiMetadataService,
                (CompiledModel) null,
                null,
                null,
                null,
                null
        );
    }

    @Autowired
    public PanelRuntime(
            RuntimeMetadataService runtimeMetadataService,
            PermissionAwareUiMetadataService permissionAwareUiMetadataService,
            ObjectProvider<CompiledModel> compiledModel,
            ObjectProvider<ConceptGateway> conceptGateway,
            ObjectProvider<CapabilityDispatcher> capabilityDispatcher,
            ObjectProvider<EventBus> eventBus,
            ObjectProvider<AggregateRuntime> aggregateRuntime
    ) {
        this(
                runtimeMetadataService,
                permissionAwareUiMetadataService,
                compiledModel == null ? null : compiledModel.getIfAvailable(),
                conceptGateway == null ? null : conceptGateway.getIfAvailable(),
                capabilityDispatcher == null ? null : capabilityDispatcher.getIfAvailable(),
                eventBus == null ? null : eventBus.getIfAvailable(),
                aggregateRuntime == null ? null : aggregateRuntime.getIfAvailable()
        );
    }

    public PanelRuntime(
            RuntimeMetadataService runtimeMetadataService,
            PermissionAwareUiMetadataService permissionAwareUiMetadataService,
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus
    ) {
        this(runtimeMetadataService, permissionAwareUiMetadataService, compiledModel, conceptGateway,
                capabilityDispatcher, eventBus, null);
    }

    public PanelRuntime(
            RuntimeMetadataService runtimeMetadataService,
            PermissionAwareUiMetadataService permissionAwareUiMetadataService,
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            AggregateRuntime aggregateRuntime
    ) {
        this.runtimeMetadataService = runtimeMetadataService;
        this.permissionAwareUiMetadataService = permissionAwareUiMetadataService;
        this.compiledModel = compiledModel;
        this.conceptGateway = conceptGateway;
        this.capabilityDispatcher = capabilityDispatcher;
        this.eventBus = eventBus;
        this.aggregateRuntime = aggregateRuntime;
    }

    public Map<String, Object> renderConceptPanel(String conceptName, ExecutionContext context) {
        String resolvedConceptName = requireConceptName(conceptName);
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        Map<String, Object> preview = permissionAwareUiMetadataService == null
                ? runtimeMetadataService.previewSupport(resolvedConceptName)
                : permissionAwareUiMetadataService.previewSupport(resolvedConceptName, effectiveContext);

        Map<String, Object> concept = castMap(preview.get("concept"));
        Map<String, Object> previewSupport = castMap(preview.get("previewSupport"));
        List<Map<String, Object>> fields = castItems(preview.get("fields"));
        List<Map<String, Object>> actions = castItems(preview.get("actions"));
        List<Map<String, Object>> validationHints = castItems(preview.get("validationHints"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("surfaceType", "panel-runtime");
        response.put("panelName", resolvedConceptName + "Panel");
        response.put("concept", concept);
        response.put("tenantId", effectiveContext.tenantId());
        response.put("actorId", effectiveContext.actorId());
        response.put("permissionAware", Boolean.TRUE.equals(preview.get("permissionAware")));
        response.put("governedDataAccess", "ConceptGateway");
        response.put("fields", fields);
        response.put("actions", actions);
        response.put("validationHints", validationHints);
        response.put("layout", buildLayout(previewSupport));
        response.put("counts", Map.of(
                "fields", fields.size(),
                "actions", actions.size(),
                "validationHints", validationHints.size()
        ));
        return response;
    }

    public Map<String, Object> loadPanel(String panelName, Map<String, Object> input, ExecutionContext context) {
        CompiledPanel panel = requirePanel(panelName);
        ExecutionContext effectiveContext = interactiveContext(context);
        Map<String, Object> safeInput = safeInput(input);

        // Aggregate Workbench (ADR-0005): the Transaction surface of an aggregate-bound AutoPanel.
        // Its data is the aggregate tree loaded by root id, not flat dataSources.
        if (panel.metadata() != null && "aggregate".equals(panel.metadata().get("dataVia"))) {
            return loadWorkbench(panel, safeInput, effectiveContext);
        }

        int traceStart = traceStartIndex();
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> dataSourceSummaries = new ArrayList<>();

        // Pass 1: every dataSource with no declared parent, exactly as before this method grew nesting support.
        for (CompiledPanelDataSource dataSource : panel.dataSources()) {
            if (hasText(dataSource.parentDataSource())) {
                continue;
            }
            Object value = loadDataSource(dataSource, safeInput, effectiveContext, null, null);
            data.put(dataSource.name(), value);
            dataSourceSummaries.add(dataSourceSummary(dataSource, value));
        }

        // Pass 2: each declared child dataSource is loaded once per already-loaded parent row, filtered by
        // childField == that row's parentField value, and nested under the parent record's "__children" map.
        // The flattened child list is still kept under data[childName] so the existing flat-array contract is
        // unchanged for any caller that only reads data[name] (backward compatibility for non-nested consumers).
        for (CompiledPanelDataSource dataSource : panel.dataSources()) {
            if (!hasText(dataSource.parentDataSource())) {
                continue;
            }
            Object parentValue = data.get(dataSource.parentDataSource());
            List<Map<String, Object>> flatChildren = new ArrayList<>();
            if (parentValue instanceof List<?> parentList) {
                String parentField = firstNonBlank(dataSource.parentField(), "id");
                for (Object parentItemObj : parentList) {
                    if (!(parentItemObj instanceof Map<?, ?> rawParentItem)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parentItem = (Map<String, Object>) rawParentItem;
                    String parentKeyValue = resolveRecordFieldValue(parentItem, parentField);
                    Object childValue = loadDataSource(dataSource, safeInput, effectiveContext,
                            dataSource.childField(), parentKeyValue);
                    List<Map<String, Object>> childList = List.of();
                    if (childValue instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> castList = (List<Map<String, Object>>) (List<?>) list;
                        childList = castList;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> children = (Map<String, Object>) parentItem.computeIfAbsent(
                            "__children", key -> new LinkedHashMap<String, Object>());
                    children.put(dataSource.name(), childList);
                    flatChildren.addAll(childList);
                }
            }
            data.put(dataSource.name(), flatChildren);
            dataSourceSummaries.add(dataSourceSummary(dataSource, flatChildren));
        }

        // Tier-A computed columns: evaluate each declared expression per row and fold the value into
        // the row's data so it renders as a (derived) column. See ADR-0004 §L3 / AutoPanel expansion.
        applyComputedColumns(panel, data);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("surfaceType", "panel-runtime");
        response.put("operation", "loadPanel");
        response.put("panelName", panel.name());
        response.put("route", safe(panel.route()));
        response.put("title", safe(panel.title()));
        response.put("tenantId", effectiveContext.tenantId());
        response.put("actorId", effectiveContext.actorId());
        response.put("ruleProfile", "interactive");
        response.put("governedDataAccess", "ConceptGateway");
        response.put("dataSources", dataSourceSummaries);
        response.put("data", data);
        response.put("fields", panelFields(panel));
        response.put("fieldBindings", panelFieldBindings(panel));
        response.put("actions", panelActions(panel));
        response.put("fallbackUi", dataSourceSummaries.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("fallback"))));
        response.put("layout", panel.layout() == null ? Map.of() : Map.of(
                "type", safe(panel.layout().type()),
                "fields", panel.layout().fields()
        ));
        response.put("gatewayTrace", traceSince(traceStart));
        return response;
    }

    public Map<String, Object> executeAction(
            String panelName,
            String actionName,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        CompiledPanel panel = requirePanel(panelName);
        CompiledPanelAction action = requireAction(panel, actionName);
        ExecutionContext effectiveContext = interactiveContext(context);
        Map<String, Object> safeInput = safeInput(input);
        String binding = normalize(firstNonBlank(action.binding(), action.procedure() == null ? null : "procedure"));
        int traceStart = traceStartIndex();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("surfaceType", "panel-runtime");
        response.put("operation", "executeAction");
        response.put("panelName", panel.name());
        response.put("actionName", action.name());
        response.put("binding", safe(action.binding()));
        response.put("tenantId", effectiveContext.tenantId());
        response.put("actorId", effectiveContext.actorId());
        response.put("ruleProfile", "interactive");
        response.put("governedDataAccess", "ConceptGateway");

        if ("procedure".equals(binding)) {
            ProcedureExecutionResult result = executeProcedure(action.procedure(), safeInput, effectiveContext);
            response.put("status", result.ok() ? "OK" : "FAILED");
            response.put("result", result);
        } else if ("conceptquery".equals(binding)) {
            String conceptName = firstNonBlank(action.concept(), primaryPanelConcept(panel));
            List<ConceptRecord> records = requireConceptGateway().list(new ConceptListRequest(conceptName, null), effectiveContext);
            response.put("status", "OK");
            response.put("result", records.stream().map(PanelRuntime::toRecordMap).toList());
        } else if ("conceptmutation".equals(binding)) {
            response.put("status", "OK");
            response.put("result", executeConceptMutation(action, panel, safeInput, effectiveContext));
        } else {
            response.put("status", "UNSUPPORTED");
            response.put("result", Map.of(
                    "code", "PANEL_ACTION_BINDING_UNSUPPORTED",
                    "message", "Panel action binding is not executable by the supported runtime: " + action.binding()
            ));
        }
        response.put("gatewayTrace", traceSince(traceStart));
        return response;
    }

    private Map<String, Object> buildLayout(Map<String, Object> previewSupport) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("tabs", previewSupport.getOrDefault("tabs", List.of()));
        layout.put("summaryFields", previewSupport.getOrDefault("summaryFields", List.of()));
        layout.put("listColumns", previewSupport.getOrDefault("listColumns", List.of()));
        layout.put("referencePickers", previewSupport.getOrDefault("referencePickers", List.of()));
        layout.put("defaultSort", stringValue(previewSupport.get("defaultSort")));
        layout.put("defaultGroup", stringValue(previewSupport.get("defaultGroup")));
        layout.put("displayMode", stringValue(previewSupport.get("displayMode")));
        return layout;
    }

    private Object loadDataSource(
            CompiledPanelDataSource dataSource,
            Map<String, Object> input,
            ExecutionContext context,
            String filterField,
            String filterValue
    ) {
        if (hasText(dataSource.procedure())) {
            try {
                ProcedureExecutionResult result = executeProcedure(dataSource.procedure(), input, context);
                return result.state().containsKey("return") ? result.state().get("return") : result.state();
            } catch (IllegalArgumentException | IllegalStateException ex) {
                return fallbackDataSource(
                        "PANEL_PROCEDURE_UNAVAILABLE",
                        "Panel procedure data source is unavailable; rendering fallback metadata instead.",
                        ex.getMessage()
                );
            }
        }
        String conceptName = resolveDataSourceConcept(dataSource);
        if (hasText(conceptName)) {
            if (conceptGateway == null) {
                return fallbackDataSource(
                        "CONCEPT_GATEWAY_UNAVAILABLE",
                        "Panel data source is unavailable; rendering fallback metadata instead.",
                        "ConceptGateway is required for executable panel data."
                );
            }
            List<Map<String, Object>> rows = requireConceptGateway()
                    .list(new ConceptListRequest(conceptName, null, filterField, filterValue), context).stream()
                    .map(PanelRuntime::toRecordMap)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return applyQueryWhereFilter(rows, resolveDataSourceQuery(dataSource));
        }
        return fallbackDataSource(
                "PANEL_DATASOURCE_UNBOUND",
                "Panel data source has no supported concept, query, or procedure binding.",
                ""
        );
    }

    private Map<String, Object> dataSourceSummary(CompiledPanelDataSource dataSource, Object value) {
        boolean fallback = isFallbackDataSource(value);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", safe(dataSource.name()));
        summary.put("concept", safe(resolveDataSourceConcept(dataSource)));
        summary.put("query", safe(dataSource.query()));
        summary.put("procedure", safe(dataSource.procedure()));
        summary.put("parentDataSource", safe(dataSource.parentDataSource()));
        summary.put("recordCount", value instanceof Collection<?> collection ? collection.size() : 0);
        summary.put("fallback", fallback);
        if (fallback && value instanceof Map<?, ?> fallbackMap) {
            summary.put("fallbackCode", safe(String.valueOf(fallbackMap.get("code"))));
        }
        return summary;
    }

    // Parent records have the toRecordMap shape {tenantId, concept, id, data}; "id" lives at the top level,
    // every other field lives one level down under "data".
    private static String resolveRecordFieldValue(Map<String, Object> record, String field) {
        Object value = "id".equals(field) ? record.get("id") : dataMap(record).get(field);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataMap(Map<String, Object> record) {
        Object data = record.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    // Evaluate the panel's declared computed columns (metadata.computed = [{col, expr}]) against each
    // loaded row and fold the derived values into that row's data. Expressions are parsed once; a per-row
    // evaluation failure leaves the column absent rather than failing the whole page load.
    @SuppressWarnings("unchecked")
    private static void applyComputedColumns(CompiledPanel panel, Map<String, Object> data) {
        Object computedMeta = panel.metadata() == null ? null : panel.metadata().get("computed");
        if (!(computedMeta instanceof List<?> computedList) || computedList.isEmpty()) {
            return;
        }
        List<Map.Entry<String, ComputedExpression.Node>> compiled = new ArrayList<>();
        for (Object item : computedList) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object col = entry.get("col");
            Object expr = entry.get("expr");
            if (col == null || expr == null) {
                continue;
            }
            try {
                compiled.add(Map.entry(col.toString(), ComputedExpression.parse(expr.toString())));
            } catch (ComputedExpression.ExpressionException ignored) {
                // invalid expression already surfaced by SemanticValidator; skip at runtime
            }
        }
        if (compiled.isEmpty()) {
            return;
        }
        for (Object value : data.values()) {
            if (!(value instanceof List<?> rows)) {
                continue;
            }
            for (Object rowObj : rows) {
                if (!(rowObj instanceof Map<?, ?> rawRow)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) rawRow;
                Map<String, Object> fields = dataMap(row);
                Map<String, Object> vars = new LinkedHashMap<>(fields);
                vars.put("id", row.get("id"));
                Map<String, Object> augmented = new LinkedHashMap<>(fields);
                for (Map.Entry<String, ComputedExpression.Node> c : compiled) {
                    try {
                        augmented.put(c.getKey(), c.getValue().eval(vars));
                    } catch (RuntimeException ignored) {
                        // leave the computed column absent on evaluation failure
                    }
                }
                row.put("data", augmented);
            }
        }
    }

    private Object executeConceptMutation(
            CompiledPanelAction action,
            CompiledPanel panel,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        String conceptName = firstNonBlank(action.concept(), primaryPanelConcept(panel));
        String operation = normalize(firstNonBlank(action.operation(), "save"));
        String id = stringValue(input.get("id"));
        if (id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if ("delete".equals(operation)) {
            requireConceptGateway().delete(new ConceptReadRequest(conceptName, id, null), context);
            return Map.of("deleted", true, "concept", conceptName, "id", id);
        }
        Map<String, Object> data = castMap(input.get("data"));
        if (data.isEmpty()) {
            data = new LinkedHashMap<>(input);
            data.remove("id");
        }
        ConceptRecord saved = requireConceptGateway().save(new ConceptWriteRequest(conceptName, id, null, data), context);
        return toRecordMap(saved);
    }

    private ProcedureExecutionResult executeProcedure(
            String procedureName,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        if (!hasText(procedureName)) {
            throw new IllegalArgumentException("Panel action requires a procedure name.");
        }
        Map<String, ProcedureDefinition> procedures = buildProcedureDefinitions();
        ProcedureDefinition definition = procedures.get(procedureName);
        if (definition == null) {
            throw new IllegalArgumentException("Procedure not found for panel action: " + procedureName);
        }
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                requireConceptGateway(),
                capabilityDispatcher == null ? PanelRuntime::capabilityUnavailable : capabilityDispatcher,
                eventBus == null ? event -> { } : eventBus,
                procedures
        );
        return executor.execute(definition, input, context);
    }

    private Map<String, ProcedureDefinition> buildProcedureDefinitions() {
        if (compiledModel == null) {
            return Map.of();
        }
        Map<String, ProcedureDefinition> definitions = new LinkedHashMap<>();
        for (CompiledProcedure procedure : compiledModel.getProcedures()) {
            definitions.put(procedure.name(), toProcedureDefinition(procedure));
        }
        return Map.copyOf(definitions);
    }

    private static ProcedureDefinition toProcedureDefinition(CompiledProcedure procedure) {
        return new ProcedureDefinition(
                procedure.name(),
                procedure.steps().stream().map(PanelRuntime::toProcedureStep).toList()
        );
    }

    private static ProcedureStep toProcedureStep(CompiledProcedureStep step) {
        ProcedureStepType type = ProcedureStep.parseType(step.type());
        String target = normalized(step.target());
        String concept = normalized(step.concept());
        return switch (type) {
            case READ_CONCEPT -> ProcedureStep.readConcept(stepName(step), concept, refOf(step.id(), "id"), target);
            case LIST_CONCEPTS -> ProcedureStep.listConcepts(stepName(step), concept, target);
            case RUN_QUERY -> ProcedureStep.runQuery(stepName(step), normalized(step.query()), concept, target);
            case SAVE_CONCEPT -> ProcedureStep.saveConcept(stepName(step), concept, refOf(step.id(), "id"), dataRef(step), target);
            case DELETE_CONCEPT -> ProcedureStep.deleteConcept(stepName(step), concept, refOf(step.id(), "id"));
            case CALL_CAPABILITY -> ProcedureStep.callCapability(
                    stepName(step),
                    normalized(step.capability()),
                    "",
                    "",
                    normalized(step.operation()),
                    step.args().values().stream().map(value -> refOf(value, String.valueOf(value))).toList(),
                    target
            );
            case PUBLISH_EVENT -> ProcedureStep.publishEvent(stepName(step), normalized(step.event()), dataRef(step));
            case CALL_PROCEDURE -> ProcedureStep.callProcedure(stepName(step), normalized(step.procedure()), dataRef(step), target);
            case IF -> ProcedureStep.ifThenElse(
                    stepName(step),
                    refOf(step.condition(), "condition"),
                    step.thenSteps().stream().map(PanelRuntime::toProcedureStep).toList(),
                    step.elseSteps().stream().map(PanelRuntime::toProcedureStep).toList()
            );
            case FOR_EACH -> ProcedureStep.forEach(
                    stepName(step),
                    refOf(step.items(), "items"),
                    normalized(step.as()) == null ? "item" : normalized(step.as()),
                    step.steps().stream().map(PanelRuntime::toProcedureStep).toList()
            );
            case MAP_VALUE -> ProcedureStep.mapValue(stepName(step), refOf(step.value(), "input"), target);
            case RETURN -> ProcedureStep.returnValue(stepName(step), refOf(step.value(), target == null ? "input" : target));
        };
    }

    // Serve an aggregate Workbench: the metadata.workbench descriptor (header/sections/bands) plus,
    // when a root id is supplied, the nested aggregate tree loaded via AggregateRuntime (P0). With no
    // id (e.g. the "new" route) only the descriptor is returned so the client can render an empty shell.
    private Map<String, Object> loadWorkbench(CompiledPanel panel, Map<String, Object> input, ExecutionContext context) {
        Map<String, Object> workbench = castMap(panel.metadata().get("workbench"));
        String aggregate = stringValue(workbench.get("aggregate"));
        String rootId = stringValue(input.get("id"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("surfaceType", "panel-runtime");
        response.put("operation", "loadWorkbench");
        response.put("panelName", panel.name());
        response.put("route", safe(panel.route()));
        response.put("title", safe(panel.title()));
        response.put("tenantId", context.tenantId());
        response.put("actorId", context.actorId());
        response.put("aggregate", aggregate);
        response.put("workbench", workbench);

        if (rootId.isBlank()) {
            response.put("data", Map.of());
        } else if (aggregateRuntime == null) {
            response.put("data", Map.of());
            response.put("dataError", "Aggregate runtime is not configured.");
        } else {
            try {
                response.put("data", aggregateRuntime.load(aggregate, rootId, context));
            } catch (IllegalArgumentException ex) {
                response.put("data", Map.of());
                response.put("dataError", ex.getMessage());
            }
        }
        return response;
    }

    private CompiledPanel requirePanel(String panelName) {
        if (compiledModel == null) {
            throw new IllegalStateException("Compiled model is required for executable panels.");
        }
        String requested = requirePanelName(panelName);
        for (CompiledPanel panel : compiledModel.getPanels()) {
            if (requested.equalsIgnoreCase(panel.name()) || requested.equalsIgnoreCase(panel.route())) {
                return panel;
            }
        }
        throw new IllegalArgumentException("Panel not found: " + panelName);
    }

    private static CompiledPanelAction requireAction(CompiledPanel panel, String actionName) {
        if (actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("actionName must be non-blank");
        }
        for (CompiledPanelAction action : panel.actions()) {
            if (actionName.trim().equalsIgnoreCase(action.name())) {
                return action;
            }
        }
        throw new IllegalArgumentException("Panel action not found: " + actionName);
    }

    private String resolveDataSourceConcept(CompiledPanelDataSource dataSource) {
        if (hasText(dataSource.concept())) {
            return dataSource.concept();
        }
        return resolveDataSourceQuery(dataSource).map(CompiledQuery::concept).orElse("");
    }

    private Optional<CompiledQuery> resolveDataSourceQuery(CompiledPanelDataSource dataSource) {
        if (compiledModel == null || !hasText(dataSource.query())) {
            return Optional.empty();
        }
        return compiledModel.getQueries().stream()
                .filter(item -> dataSource.query().equalsIgnoreCase(item.name()))
                .findFirst();
    }

    /**
     * Applies a query's declared {@code where} clause as an in-process post-filter on records
     * already fetched from the concept gateway. Deliberately scoped to the same single-field
     * {@code field == literal} / {@code field != literal} shape every query in practice declares
     * (mirrors CelInvariantEngine's own documented DNF-only scope) -- not a general expression
     * evaluator. A clause outside this shape is left unenforced (rows pass through unfiltered)
     * rather than failing the whole panel load.
     */
    private static List<Map<String, Object>> applyQueryWhereFilter(List<Map<String, Object>> rows, Optional<CompiledQuery> query) {
        String where = query.map(CompiledQuery::where).orElse(null);
        if (!hasText(where)) {
            return rows;
        }
        String trimmed = where.trim();
        boolean negate;
        int opIndex = trimmed.indexOf("!=");
        if (opIndex >= 0) {
            negate = true;
        } else {
            opIndex = trimmed.indexOf("==");
            negate = false;
            if (opIndex < 0) {
                return rows;
            }
        }
        String field = trimmed.substring(0, opIndex).trim();
        String literalText = trimmed.substring(opIndex + 2).trim();
        if (field.isEmpty()) {
            return rows;
        }
        Object literal = parseWhereLiteral(literalText);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object dataObj = row.get("data");
            Object fieldValue = dataObj instanceof Map<?, ?> data ? data.get(field) : null;
            boolean equal = Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(literal));
            if (equal != negate) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private static Object parseWhereLiteral(String text) {
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static Object normalizeForCompare(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            return String.valueOf(value);
        }
        return null;
    }

    private String primaryPanelConcept(CompiledPanel panel) {
        for (CompiledPanelDataSource dataSource : panel.dataSources()) {
            String concept = resolveDataSourceConcept(dataSource);
            if (hasText(concept)) {
                return concept;
            }
        }
        return "";
    }

    private ConceptGateway requireConceptGateway() {
        if (conceptGateway == null) {
            throw new IllegalStateException("ConceptGateway is required for executable panels.");
        }
        return conceptGateway;
    }

    private int traceStartIndex() {
        return conceptGateway == null ? 0 : conceptGateway.explain().size();
    }

    private List<ConceptGatewayTraceRecord> traceSince(int startIndex) {
        if (conceptGateway == null) {
            return List.of();
        }
        List<ConceptGatewayTraceRecord> traces = conceptGateway.explain();
        if (startIndex <= 0) {
            return List.copyOf(traces);
        }
        if (startIndex >= traces.size()) {
            return List.of();
        }
        return List.copyOf(traces.subList(startIndex, traces.size()));
    }

    private static List<Map<String, Object>> panelFields(CompiledPanel panel) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (panel.layout() != null) {
            for (String field : panel.layout().fields()) {
                fields.add(Map.of("field", field));
            }
        }
        return List.copyOf(fields);
    }

    private static List<Map<String, Object>> panelFieldBindings(CompiledPanel panel) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (CompiledPanelFieldBinding binding : panel.fieldBindings()) {
            String source = safe(binding.source());
            int dot = source.indexOf('.');
            String dataSource = dot > 0 ? source.substring(0, dot) : "";
            String sourceField = dot > 0 ? source.substring(dot + 1) : (source.isBlank() ? safe(binding.field()) : source);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", safe(binding.field()));
            item.put("dataSource", dataSource);
            item.put("sourceField", sourceField);
            item.put("editable", binding.editable());
            item.put("label", binding.ui() == null ? "" : safe(binding.ui().getLabel()));
            item.put("order", binding.ui() == null ? null : binding.ui().getOrder());
            bindings.add(item);
        }
        return List.copyOf(bindings);
    }

    private static List<Map<String, Object>> panelActions(CompiledPanel panel) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (CompiledPanelAction action : panel.actions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", safe(action.name()));
            item.put("label", safe(action.label()));
            item.put("binding", safe(action.binding()));
            item.put("procedure", safe(action.procedure()));
            item.put("concept", safe(action.concept()));
            item.put("operation", safe(action.operation()));
            actions.add(item);
        }
        return List.copyOf(actions);
    }

    private static String requireConceptName(String conceptName) {
        if (conceptName == null || conceptName.isBlank()) {
            throw new IllegalArgumentException("conceptName must be non-blank");
        }
        return conceptName.trim();
    }

    private static String requirePanelName(String panelName) {
        if (panelName == null || panelName.isBlank()) {
            throw new IllegalArgumentException("panelName must be non-blank");
        }
        return panelName.trim();
    }

    private static List<Map<String, Object>> castItems(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                out.add(castMap(item));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> safeInput(Map<String, Object> input) {
        return input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static Map<String, Object> fallbackDataSource(String code, String message, String detail) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("fallback", true);
        fallback.put("status", "UNAVAILABLE");
        fallback.put("code", safe(code));
        fallback.put("message", safe(message));
        fallback.put("detail", safe(detail));
        return Map.copyOf(fallback);
    }

    private static boolean isFallbackDataSource(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return false;
        }
        return Boolean.TRUE.equals(map.get("fallback"));
    }

    private static ExecutionContext interactiveContext(ExecutionContext context) {
        ExecutionContext effective = context == null ? ExecutionContext.anonymous() : context;
        return effective.withTag("executionMode", "interactive");
    }

    private static Map<String, Object> toRecordMap(ConceptRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", record.tenantId());
        out.put("concept", record.conceptName());
        out.put("id", record.id());
        out.put("data", record.data());
        return out;
    }

    private static CapabilityResult capabilityUnavailable(com.npdev.kernel.CapabilityCall call, Map<String, Object> state) {
        return CapabilityResult.failure(
                "CAPABILITY_UNAVAILABLE",
                "Panel procedure execution has no capability dispatcher for " + (call == null ? "" : call.capability()),
                CapabilityErrorKind.PERMANENT,
                Map.of()
        );
    }

    private static String dataRef(CompiledProcedureStep step) {
        if (step.data() != null && !step.data().isEmpty()) {
            Object input = step.data().get("input");
            if (input != null) {
                return refOf(input, "input");
            }
            Object payload = step.data().get("payload");
            if (payload != null) {
                return refOf(payload, "input");
            }
        }
        return "input";
    }

    private static String refOf(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        return text.startsWith("$") ? text.substring(1) : text;
    }

    private static String stepName(CompiledProcedureStep step) {
        String name = normalized(step.name());
        return name == null ? "panel-procedure-step" : name;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
