package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelFieldBinding;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptQueryFilterSupport;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
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
    private final ProcedureRunner procedureRunner;
    private final KernelFacade kernelFacade;

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
            ObjectProvider<AggregateRuntime> aggregateRuntime,
            ObjectProvider<KernelFacade> kernelFacade
    ) {
        this(
                runtimeMetadataService,
                permissionAwareUiMetadataService,
                compiledModel == null ? null : compiledModel.getIfAvailable(),
                conceptGateway == null ? null : conceptGateway.getIfAvailable(),
                capabilityDispatcher == null ? null : capabilityDispatcher.getIfAvailable(),
                eventBus == null ? null : eventBus.getIfAvailable(),
                aggregateRuntime == null ? null : aggregateRuntime.getIfAvailable(),
                kernelFacade == null ? null : kernelFacade.getIfAvailable()
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
                capabilityDispatcher, eventBus, null, null);
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
        this(runtimeMetadataService, permissionAwareUiMetadataService, compiledModel, conceptGateway,
                capabilityDispatcher, eventBus, aggregateRuntime, null);
    }

    public PanelRuntime(
            RuntimeMetadataService runtimeMetadataService,
            PermissionAwareUiMetadataService permissionAwareUiMetadataService,
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            AggregateRuntime aggregateRuntime,
            KernelFacade kernelFacade
    ) {
        this.runtimeMetadataService = runtimeMetadataService;
        this.permissionAwareUiMetadataService = permissionAwareUiMetadataService;
        this.compiledModel = compiledModel;
        this.conceptGateway = conceptGateway;
        this.capabilityDispatcher = capabilityDispatcher;
        this.eventBus = eventBus;
        this.aggregateRuntime = aggregateRuntime;
        this.procedureRunner = new ProcedureRunner(compiledModel, conceptGateway, capabilityDispatcher, eventBus);
        this.kernelFacade = kernelFacade;
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
        // AW-P2: echo the compiled panel's own metadata (e.g. a selectors[]-expanded panel's
        // multiSelect/returnMapping/filters) so a caller referencing this panel as a bandPicker
        // source can consume the selector's declared pick contract instead of guessing from columns.
        response.put("metadata", panel.metadata() == null ? Map.of() : panel.metadata());
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
        // G2 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): a scope="row" action's input starts from the target
        // row's OWN current data (freshly re-read, not whatever the client happens to have cached),
        // with the caller's body layered on top as overrides -- the same shape crossdocking.html's
        // hand-written Concluir/Cancelar used (`{...xd, situacao: 'Concluido'}`). Panel-scoped actions
        // (the default) are unaffected -- safeInput passes straight through. Covers conceptMutation
        // too (found live authoring G4's Cancelar/Confirmar actions): the real row button only ever
        // sends `{id}`, and conceptmutation's own save path treats a "data"-less body as the WHOLE
        // record to save -- without this merge, that would blank every other required field to null.
        Map<String, Object> effectiveInput =
                "flow".equals(binding) || "procedure".equals(binding) || "conceptmutation".equals(binding)
                        ? resolveRowScopedInput(panel, action, safeInput, effectiveContext)
                        : safeInput;
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
            ProcedureExecutionResult result = executeProcedure(action.procedure(), effectiveInput, effectiveContext);
            response.put("status", result.ok() ? "OK" : "FAILED");
            response.put("result", result);
        } else if ("conceptquery".equals(binding)) {
            String conceptName = firstNonBlank(action.concept(), primaryPanelConcept(panel));
            List<ConceptRecord> records = requireConceptGateway().list(new ConceptListRequest(conceptName, null), effectiveContext);
            response.put("status", "OK");
            response.put("result", records.stream().map(PanelRuntime::toRecordMap).toList());
        } else if ("conceptmutation".equals(binding)) {
            response.put("status", "OK");
            response.put("result", executeConceptMutation(action, panel, effectiveInput, effectiveContext));
        } else if ("flow".equals(binding)) {
            String flowName = action.flow();
            if (kernelFacade == null || !hasText(flowName)) {
                response.put("status", "UNSUPPORTED");
                response.put("result", Map.of(
                        "code", "PANEL_ACTION_BINDING_UNSUPPORTED",
                        "message", "Panel action binding is not executable by the supported runtime: flow"
                ));
            } else {
                // G1 (docs/MOVE1_PANEL_GAPS.md, REG-70): route through the same KernelFacade.executeFlow
                // the generated FlowExecutionController uses for /api/flows/{name}/execute, so row-level
                // authz/tenant isolation and async WAITING_EVENT semantics are identical -- not
                // re-implemented here. Do not synthesize a synchronous "OK" for a parked flow.
                ExecutionResult flowResult = kernelFacade.executeFlow(flowName, effectiveInput, effectiveContext);
                response.put("status", mapFlowStatus(flowResult));
                response.put("executionId", safe(flowResult.getExecutionId()));
                response.put("correlationId", safe(flowResult.getCorrelationId()));
                response.put("result", flowResultMap(flowResult));
            }
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
            List<ConceptRecord> records = requireConceptGateway()
                    .list(new ConceptListRequest(conceptName, null, filterField, filterValue), context);
            Optional<CompiledQuery> query = resolveDataSourceQuery(dataSource);
            // LIFT-QUERY-P1: where/orderBy now come from the shared kernel predicate also used by
            // DefaultProcedureExecutor's runQuery step, instead of a second copy of this logic.
            records = ConceptQueryFilterSupport.applyWhere(records, query.map(CompiledQuery::where).orElse(null));
            records = ConceptQueryFilterSupport.applyOrderBy(records, query.map(CompiledQuery::orderBy).orElse(List.of()));
            return records.stream()
                    .map(PanelRuntime::toRecordMap)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
        summary.put("childField", safe(dataSource.childField()));
        // LIFT-ROWOPS-P2: the client add/delete-row UI is gated entirely off this declared list --
        // no rowOps means no add/delete control is rendered for this dataSource.
        summary.put("rowOps", dataSource.rowOps());
        summary.put("addFormFields", dataSource.addFormFields());
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

    private static String mapFlowStatus(ExecutionResult result) {
        if (result == null || result.getStatus() == null) {
            return "FAILED";
        }
        return switch (result.getStatus()) {
            case OK -> "OK";
            case WAITING_EVENT -> "WAITING";
            default -> "FAILED";
        };
    }

    private static Map<String, Object> flowResultMap(ExecutionResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executionId", safe(result.getExecutionId()));
        out.put("correlationId", safe(result.getCorrelationId()));
        out.put("flowName", safe(result.getFlowName()));
        out.put("status", result.getStatus() == null ? "" : result.getStatus().name());
        out.put("output", result.getOutput());
        if (result.getError() != null) {
            out.put("error", result.getError());
        }
        if (result.getErrorCode() != null) {
            out.put("errorCode", result.getErrorCode());
        }
        return out;
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
            // G2/G4 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): found live authoring InventarioHistoricoPanel's
            // Confirmar action -- ConceptGatewaySemanticPolicy validates every declared concept field,
            // including "id" itself (required:true on every concept), against this data map. Keeping
            // "id" out of it (as this branch used to) meant any flat, "data"-less body -- exactly what
            // resolveRowScopedInput now feeds this method for a scope="row" conceptMutation action --
            // failed with "Required concept field is missing: <Concept>.id" every time.
            data = new LinkedHashMap<>(input);
        }
        ConceptRecord saved = requireConceptGateway().save(new ConceptWriteRequest(conceptName, id, null, data), context);
        return toRecordMap(saved);
    }

    /**
     * LIFT-ROWOPS-P3: creates a row in a declared Panel dataSource that opted into
     * {@code rowOps: [add]}. For a nested (child) dataSource, {@code input.parentId} is required
     * and gets written into the child's FK ({@code childField}) automatically -- the same
     * parent-binding a Workbench child row gets from {@code AggregateRuntime.commitCollections},
     * just for a single row instead of a whole draft tree. Tenant scoping is enforced by
     * {@link ConceptGateway} itself (a null request tenantId falls back to the caller's own
     * context tenant), the same as every other panel/aggregate write in this class.
     */
    public Map<String, Object> createRow(
            String panelName,
            String dataSourceName,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        CompiledPanel panel = requirePanel(panelName);
        CompiledPanelDataSource dataSource = requireDataSource(panel, dataSourceName);
        if (!dataSource.supportsAdd()) {
            throw new IllegalArgumentException(
                    "Panel " + panel.name() + " dataSource " + dataSource.name() + " does not support add");
        }
        String conceptName = resolveDataSourceConcept(dataSource);
        if (!hasText(conceptName)) {
            throw new IllegalStateException(
                    "Panel " + panel.name() + " dataSource " + dataSource.name() + " has no concept to create against");
        }
        ExecutionContext effectiveContext = interactiveContext(context);
        Map<String, Object> safeInput = safeInput(input);

        Map<String, Object> data = castMap(safeInput.get("data"));
        if (data.isEmpty()) {
            data = new LinkedHashMap<>(safeInput);
            data.remove("id");
            data.remove("parentId");
        } else {
            data = new LinkedHashMap<>(data);
        }

        if (hasText(dataSource.parentDataSource())) {
            String parentId = stringValue(safeInput.get("parentId"));
            if (parentId.isBlank()) {
                throw new IllegalArgumentException(
                        "Panel " + panel.name() + " dataSource " + dataSource.name()
                                + " is a child dataSource; parentId is required to create a row");
            }
            data.put(dataSource.childField(), parentId);
        }

        String id = UUID.randomUUID().toString();
        ConceptRecord saved = requireConceptGateway().save(new ConceptWriteRequest(conceptName, id, null, data), effectiveContext);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("surfaceType", "panel-runtime");
        response.put("operation", "createRow");
        response.put("panelName", panel.name());
        response.put("dataSourceName", dataSource.name());
        response.put("status", "OK");
        response.put("result", toRecordMap(saved));
        return response;
    }

    /**
     * LIFT-ROWOPS-P3: deletes a row from a declared Panel dataSource that opted into
     * {@code rowOps: [delete]}.
     */
    public Map<String, Object> deleteRow(
            String panelName,
            String dataSourceName,
            String id,
            ExecutionContext context
    ) {
        CompiledPanel panel = requirePanel(panelName);
        CompiledPanelDataSource dataSource = requireDataSource(panel, dataSourceName);
        if (!dataSource.supportsDelete()) {
            throw new IllegalArgumentException(
                    "Panel " + panel.name() + " dataSource " + dataSource.name() + " does not support delete");
        }
        String conceptName = resolveDataSourceConcept(dataSource);
        if (!hasText(conceptName)) {
            throw new IllegalStateException(
                    "Panel " + panel.name() + " dataSource " + dataSource.name() + " has no concept to delete against");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must be non-blank");
        }
        String rowId = id.trim();
        ExecutionContext effectiveContext = interactiveContext(context);
        ConceptGateway gateway = requireConceptGateway();
        // Confirm the row is visible in the caller's own tenant before deleting. Without this, a
        // delete for a row owned by another tenant scopes to the caller's (empty) tenant, deletes
        // nothing, yet still reports deleted:true -- a silent, misleading no-op. Reading first makes
        // the panel delete tenant-safe and truthful: a row the caller cannot see cannot be deleted,
        // and the caller is told so explicitly instead of receiving a false success.
        if (gateway.read(new ConceptReadRequest(conceptName, rowId, null), effectiveContext).isEmpty()) {
            throw new IllegalArgumentException(
                    "No " + conceptName + " row '" + rowId + "' is visible to this tenant to delete");
        }
        gateway.delete(new ConceptReadRequest(conceptName, rowId, null), effectiveContext);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("surfaceType", "panel-runtime");
        response.put("operation", "deleteRow");
        response.put("panelName", panel.name());
        response.put("dataSourceName", dataSource.name());
        response.put("status", "OK");
        response.put("result", Map.of("deleted", true, "concept", conceptName, "id", rowId));
        return response;
    }

    private static CompiledPanelDataSource requireDataSource(CompiledPanel panel, String dataSourceName) {
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new IllegalArgumentException("dataSourceName must be non-blank");
        }
        for (CompiledPanelDataSource dataSource : panel.dataSources()) {
            if (dataSourceName.trim().equalsIgnoreCase(dataSource.name())) {
                return dataSource;
            }
        }
        throw new IllegalArgumentException("Panel dataSource not found: " + dataSourceName);
    }

    private ProcedureExecutionResult executeProcedure(
            String procedureName,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        return procedureRunner.execute(procedureName, input, context);
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
     * G2 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): resolves the effective flow/procedure input for a
     * panel action. {@code scope: "row"} re-reads the target row fresh from its declared dataSource
     * (by id) and layers the caller's body on top as overrides; {@code scope: "panel"} (the default)
     * passes the caller's body through unchanged, so every action declared before this existed
     * behaves exactly as before.
     */
    private Map<String, Object> resolveRowScopedInput(
            CompiledPanel panel,
            CompiledPanelAction action,
            Map<String, Object> safeInput,
            ExecutionContext context
    ) {
        if (!"row".equalsIgnoreCase(action.scope())) {
            return safeInput;
        }
        String id = stringValue(safeInput.get("id"));
        if (id.isBlank()) {
            throw new IllegalArgumentException(
                    "Panel action '" + action.name() + "' is scope=\"row\" and requires an 'id' in the request body.");
        }
        CompiledPanelDataSource dataSource = findDataSource(panel, action.dataSource());
        if (dataSource == null) {
            throw new IllegalStateException(
                    "Panel action '" + action.name() + "' declares dataSource '" + action.dataSource()
                            + "' which was not found on panel '" + panel.name() + "'.");
        }
        String conceptName = resolveDataSourceConcept(dataSource);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (hasText(conceptName)) {
            requireConceptGateway().read(new ConceptReadRequest(conceptName, id, null), context)
                    .ifPresent(record -> merged.putAll(record.data()));
        }
        merged.putAll(safeInput);
        merged.put("id", id);
        return Collections.unmodifiableMap(merged);
    }

    private static CompiledPanelDataSource findDataSource(CompiledPanel panel, String dataSourceName) {
        if (!hasText(dataSourceName)) {
            return null;
        }
        for (CompiledPanelDataSource dataSource : panel.dataSources()) {
            if (dataSourceName.trim().equalsIgnoreCase(dataSource.name())) {
                return dataSource;
            }
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
            item.put("scope", safe(action.scope()));
            item.put("dataSource", safe(action.dataSource()));
            // G2: row-scoped buttons (rendered from THIS live response's entry.actions, not the
            // boot-time manifest) need visibleWhen/enabledWhen too, to gate per row -- e.g.
            // "situacao == 'Ativo'". Previously absent here because only the boot-time manifest
            // (BusinessUiEmitter) carried them, for the panel-header loop alone.
            item.put("visibleWhen", safe(action.visibleWhen()));
            item.put("enabledWhen", safe(action.enabledWhen()));
            item.put("inputFields", action.inputFields());
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
