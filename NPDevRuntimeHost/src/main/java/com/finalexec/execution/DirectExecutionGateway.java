package com.finalexec.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.CrossTenantGovernanceRequest;
import com.finalexec.npdev.service.CrossTenantGovernanceService;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PublicationChainReferenceResolver;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DirectExecutionGateway {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-direct-execution-gateway/direct-execution-gateway-rules.json";

    private static final Path EXECUTION_ROOT =
            Path.of("runtime-data", "direct-executions");

    private final ObjectMapper objectMapper;
    private final KernelFacade kernelFacade;
    private final CompiledModel compiledModel;
    private final CrossTenantGovernanceService crossTenantGovernanceService;
    private final PublicationChainReferenceResolver referenceResolver;
    private final PanelRuntime panelRuntime;

    public DirectExecutionGateway(
            ObjectMapper objectMapper,
            KernelFacade kernelFacade,
            CompiledModel compiledModel,
            CrossTenantGovernanceService crossTenantGovernanceService,
            PublicationChainReferenceResolver referenceResolver,
            PanelRuntime panelRuntime
    ) {
        this.objectMapper = objectMapper;
        this.kernelFacade = kernelFacade;
        this.compiledModel = compiledModel;
        this.crossTenantGovernanceService = crossTenantGovernanceService;
        this.referenceResolver = referenceResolver;
        this.panelRuntime = panelRuntime;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();
        List<Map<String, Object>> items = referenceResolver.readRecords(EXECUTION_ROOT);

        Map<String, Integer> flows = countBy(items, "flowName");
        Map<String, Integer> tenants = countBy(items, "tenantId");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Direct Execution Governance Bridge"));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        response.put("entrypoint", rules.getOrDefault("entrypoint", "/api/execute/flow"));
        response.put("mode", rules.getOrDefault("mode", "direct-execution-governance-bridge-v1"));
        response.put("directExecutionRule", rules.getOrDefault(
                "directExecutionRule",
                "All direct execution must still pass through Flow -> Capability -> Governance."
        ));
        response.put("crossTenantPolicy", rules.getOrDefault("crossTenantPolicy", "REJECT"));
        response.put("availableFlowNames", kernelFacade.listFlowNames());
        response.put("numberOfDirectExecutions", items.size());
        response.put("successfulExecutionCount", countSuccessfulExecutions(items));
        response.put("rejectedExecutionCount", countStatus(items, "REJECTED"));
        response.put("failedExecutionCount", countFailedExecutions(items));
        response.put("flowsExecuted", toDistribution(flows));
        response.put("tenantDistribution", toDistribution(tenants));
        response.put("latestExecutionReference", latestValue(items, "directExecutionReference"));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(EXECUTION_ROOT);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> execute(DirectExecutionRequest request, ExecutionContext requesterContext) {
        validate(request);

        Map<String, Object> rules = loadRules();
        ExecutionContext effectiveRequesterContext = requesterContext == null ? ExecutionContext.anonymous() : requesterContext;
        String directExecutionId = UUID.randomUUID().toString();
        String requestedAt = now();
        String requestedTenantId = request.getTenantId().trim();
        String requesterTenantId = effectiveRequesterContext.tenantId();
        String requesterActorId = effectiveRequesterContext.actorId();
        String flowName = request.getFlowName().trim();
        Map<String, Object> input = request.getInput() == null ? Map.of() : request.getInput();
        boolean crossTenant = !requestedTenantId.equals(requesterTenantId);

        CompiledFlow flow = compiledModel.findFlow(flowName)
                .orElseThrow(() -> new IllegalArgumentException("flowName is not present in the compiled model."));

        Map<String, Object> capabilityRoute = resolveCapabilityRoute(flow);
        if (isRouteRejected(capabilityRoute)) {
            Map<String, Object> rejected = buildRejectedRecord(
                    directExecutionId,
                    request,
                    effectiveRequesterContext,
                    requestedAt,
                    "ARCHITECTURE_BYPASS_REJECTED",
                    "Direct execution requires a flow with at least one capability call.",
                    null,
                    capabilityRoute,
                    rules
            );
            persistRecord(directExecutionId, rejected);
            throw new SecurityException("Direct execution requires Flow -> Capability routing and cannot bypass the architecture.");
        }

        Map<String, Object> governanceRecord = null;
        if (crossTenant) {
            governanceRecord = crossTenantGovernanceService.evaluate(buildGovernanceRequest(
                    directExecutionId,
                    requesterTenantId,
                    requestedTenantId,
                    requesterActorId,
                    flowName
            ));

            Map<String, Object> rejected = buildRejectedRecord(
                    directExecutionId,
                    request,
                    effectiveRequesterContext,
                    requestedAt,
                    "CROSS_TENANT_REJECTED",
                    "Direct execution remains same-tenant only; cross-tenant execution is rejected even when governance is recorded.",
                    governanceRecord,
                    capabilityRoute,
                    rules
            );
            persistRecord(directExecutionId, rejected);
            throw new SecurityException("Cross-tenant direct execution is not allowed through the direct execution bridge.");
        }

        ExecutionContext effectiveContext = effectiveRequesterContext
                .withTag("directExecution", "true")
                .withTag("directExecutionGateway", "step-111")
                .withTag("directExecutionReference", directExecutionId);

        try {
            ExecutionResult result = kernelFacade.executeFlow(flowName, input, effectiveContext);
            Map<String, Object> record = buildExecutedRecord(
                    directExecutionId,
                    request,
                    effectiveContext,
                    requestedAt,
                    capabilityRoute,
                    governanceRecord,
                    result,
                    rules
            );
            persistRecord(directExecutionId, record);
            return record;
        } catch (RuntimeException exception) {
            Map<String, Object> failed = buildFailedRecord(
                    directExecutionId,
                    request,
                    effectiveContext,
                    requestedAt,
                    capabilityRoute,
                    governanceRecord,
                    exception,
                    rules
            );
            persistRecord(directExecutionId, failed);
            throw exception;
        }
    }

    public Map<String, Object> executePanelAction(DirectExecutionRequest request, ExecutionContext requesterContext) {
        validatePanelAction(request);
        String directExecutionId = UUID.randomUUID().toString();
        String requestedAt = now();
        ExecutionContext context = (requesterContext == null ? ExecutionContext.anonymous() : requesterContext)
                .withTag("directExecution", "true")
                .withTag("directExecutionGateway", "panel-action")
                .withTag("directExecutionReference", directExecutionId);
        if (!request.getTenantId().trim().equals(context.tenantId())) {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("directExecutionId", directExecutionId);
            rejected.put("directExecutionReference", "direct-execution-" + directExecutionId);
            rejected.put("tenantId", request.getTenantId().trim());
            rejected.put("requesterTenantId", context.tenantId());
            rejected.put("requestedAt", requestedAt);
            rejected.put("executionResultStatus", "REJECTED");
            rejected.put("executionLifecycleStatus", "REJECTED");
            rejected.put("rejectionCode", "CROSS_TENANT_REJECTED");
            rejected.put("rejectionReason", "Panel action direct execution remains same-tenant only.");
            persistRecord(directExecutionId, rejected);
            throw new SecurityException("Cross-tenant panel action execution is not allowed through the direct execution bridge.");
        }

        Map<String, Object> result = panelRuntime.executeAction(
                request.getPanelName(),
                request.getActionName(),
                request.getInput() == null ? Map.of() : request.getInput(),
                context
        );
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("directExecutionId", directExecutionId);
        record.put("directExecutionReference", "direct-execution-" + directExecutionId);
        record.put("tenantId", request.getTenantId().trim());
        record.put("requesterTenantId", context.tenantId());
        record.put("requesterActorId", context.actorId());
        record.put("panelName", request.getPanelName().trim());
        record.put("actionName", request.getActionName().trim());
        record.put("input", request.getInput() == null ? Map.of() : request.getInput());
        record.put("requestedAt", requestedAt);
        record.put("mode", "direct-panel-action-governance-bridge-v1");
        record.put("governanceBridgeStatus", "PANEL_PROCEDURE_CONCEPT_GATEWAY_ENFORCED");
        record.put("executionResultStatus", "OK".equals(result.get("status")) ? "OK" : String.valueOf(result.get("status")));
        record.put("executionLifecycleStatus", "OK".equals(result.get("status")) ? "EXECUTED" : "FAILED");
        record.put("result", result);
        persistRecord(directExecutionId, record);
        return record;
    }

    private CrossTenantGovernanceRequest buildGovernanceRequest(
            String directExecutionId,
            String requesterTenantId,
            String requestedTenantId,
            String requesterActorId,
            String flowName
    ) {
        CrossTenantGovernanceRequest request = new CrossTenantGovernanceRequest();
        request.setGovernanceReference("direct-execution-" + directExecutionId);
        request.setRequestingTenantId(requesterTenantId);
        request.setTargetTenantId(requestedTenantId);
        request.setScope("cross-tenant-override-review");
        request.setRequestedBy(requesterActorId);
        request.setRationale("Direct execution attempted for flow '" + flowName + "' across tenants; gateway records governance before rejecting the bypass.");
        request.setGovernanceMode("cross-tenant-governance-v1");
        return request;
    }

    private Map<String, Object> buildExecutedRecord(
            String directExecutionId,
            DirectExecutionRequest request,
            ExecutionContext context,
            String requestedAt,
            Map<String, Object> capabilityRoute,
            Map<String, Object> governanceRecord,
            ExecutionResult result,
            Map<String, Object> rules
    ) {
        Map<String, Object> record = buildBaseRecord(
                directExecutionId,
                request,
                context,
                requestedAt,
                capabilityRoute,
                governanceRecord,
                rules
        );
        record.put("executionResultStatus", result.getStatus() == null ? "" : result.getStatus().name());
        record.put("executionLifecycleStatus", lifecycleStatusFor(result));
        record.put("executionAuditStatus", "RECORDED");
        record.put("result", toResultMap(result));
        return record;
    }

    private Map<String, Object> buildFailedRecord(
            String directExecutionId,
            DirectExecutionRequest request,
            ExecutionContext context,
            String requestedAt,
            Map<String, Object> capabilityRoute,
            Map<String, Object> governanceRecord,
            RuntimeException exception,
            Map<String, Object> rules
    ) {
        Map<String, Object> record = buildBaseRecord(
                directExecutionId,
                request,
                context,
                requestedAt,
                capabilityRoute,
                governanceRecord,
                rules
        );
        record.put("executionResultStatus", "RUNTIME_EXCEPTION");
        record.put("executionLifecycleStatus", "FAILED");
        record.put("executionAuditStatus", "RECORDED");
        record.put("result", Map.of(
                "status", "RUNTIME_EXCEPTION",
                "errorType", exception.getClass().getSimpleName(),
                "errorMessage", exception.getMessage() == null ? "" : exception.getMessage()
        ));
        return record;
    }

    private Map<String, Object> buildRejectedRecord(
            String directExecutionId,
            DirectExecutionRequest request,
            ExecutionContext context,
            String requestedAt,
            String rejectionCode,
            String rejectionReason,
            Map<String, Object> governanceRecord,
            Map<String, Object> capabilityRoute,
            Map<String, Object> rules
    ) {
        Map<String, Object> record = buildBaseRecord(
                directExecutionId,
                request,
                context,
                requestedAt,
                capabilityRoute,
                governanceRecord,
                rules
        );
        record.put("executionResultStatus", "REJECTED");
        record.put("executionLifecycleStatus", "REJECTED");
        record.put("executionAuditStatus", "RECORDED");
        record.put("rejectionCode", rejectionCode);
        record.put("rejectionReason", rejectionReason);
        record.put("result", Map.of(
                "status", "REJECTED",
                "reason", rejectionReason
        ));
        return record;
    }

    private Map<String, Object> buildBaseRecord(
            String directExecutionId,
            DirectExecutionRequest request,
            ExecutionContext context,
            String requestedAt,
            Map<String, Object> capabilityRoute,
            Map<String, Object> governanceRecord,
            Map<String, Object> rules
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("directExecutionId", directExecutionId);
        record.put("directExecutionReference", "direct-execution-" + directExecutionId);
        record.put("tenantId", request.getTenantId().trim());
        record.put("requesterTenantId", context.tenantId());
        record.put("requesterActorId", context.actorId());
        record.put("flowName", request.getFlowName().trim());
        record.put("input", request.getInput() == null ? Map.of() : request.getInput());
        record.put("requestedAt", requestedAt);
        record.put("mode", rules.getOrDefault("mode", "direct-execution-governance-bridge-v1"));
        record.put("governanceBridgeStatus", "FLOW_CAPABILITY_GOVERNANCE_ENFORCED");
        record.put("tenantScopeStatus", request.getTenantId().trim().equals(context.tenantId()) ? "SAME_TENANT" : "CROSS_TENANT");
        record.put("capabilityRoute", capabilityRoute);
        record.put("governanceRecord", governanceRecord == null ? Map.of(
                "governanceStatus", "SAME_TENANT_NO_OVERRIDE_REQUIRED"
        ) : governanceRecord);
        record.put("observability", Map.of(
                "recordedBy", "DirectExecutionGateway",
                "storagePath", EXECUTION_ROOT.toString().replace("\\", "/"),
                "actorRoles", new ArrayList<>(context.roles()),
                "contextTags", context.metadata()
        ));
        return record;
    }

    private Map<String, Object> resolveCapabilityRoute(CompiledFlow flow) {
        List<Map<String, Object>> routedCapabilities = new ArrayList<>();
        collectCapabilityRoute(flow.getSteps(), routedCapabilities, new LinkedHashSet<>());

        Map<String, String> adapterByCapability = new LinkedHashMap<>();
        for (CompiledCapabilityBinding binding : compiledModel.getBindings()) {
            adapterByCapability.put(binding.getCapability(), binding.getAdapter());
        }

        for (Map<String, Object> routedCapability : routedCapabilities) {
            String capabilityName = String.valueOf(routedCapability.getOrDefault("capabilityName", ""));
            routedCapability.put("adapterId", adapterByCapability.getOrDefault(capabilityName, ""));
        }

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("routeStatus", routedCapabilities.isEmpty() ? "BYPASS_REJECTED" : "FLOW_CAPABILITY_ROUTE_RESOLVED");
        route.put("flowConcept", flow.getConcept());
        route.put("flowMode", flow.getMode());
        route.put("capabilityCount", routedCapabilities.size());
        route.put("capabilities", routedCapabilities);
        return route;
    }

    private void collectCapabilityRoute(
            List<CompiledFlowStep> steps,
            List<Map<String, Object>> routedCapabilities,
            Set<String> seen
    ) {
        for (CompiledFlowStep step : steps) {
            CompiledCapabilityCall capabilityCall = step.getCapabilityCall();
            if (capabilityCall != null) {
                String dedupeKey = capabilityCall.getCapabilityName() + "::" + capabilityCall.getOperation() + "::" + step.getName();
                if (seen.add(dedupeKey)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stepName", step.getName());
                    item.put("stepType", step.getType());
                    item.put("capabilityName", capabilityCall.getCapabilityName());
                    item.put("capabilityType", capabilityCall.getCapabilityType());
                    item.put("operation", capabilityCall.getOperation());
                    item.put("retryCount", capabilityCall.getExecutionPolicy() == null
                            ? 0
                            : capabilityCall.getExecutionPolicy().getRetryCount());
                    item.put("timeoutMs", capabilityCall.getExecutionPolicy() == null
                            ? 0L
                            : capabilityCall.getExecutionPolicy().getTimeoutMs());
                    routedCapabilities.add(item);
                }
            }
            collectCapabilityRoute(step.getThenSteps(), routedCapabilities, seen);
            collectCapabilityRoute(step.getElseSteps(), routedCapabilities, seen);
        }
    }

    private Map<String, Object> toResultMap(ExecutionResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", result.getExecutionId());
        map.put("correlationId", result.getCorrelationId());
        map.put("traceId", result.getTraceId());
        map.put("flowName", result.getFlowName());
        map.put("status", result.getStatus() == null ? "" : result.getStatus().name());
        map.put("errorCode", blankIfNull(result.getErrorCode()));
        map.put("error", blankIfNull(result.getError()));
        map.put("awaitedEventName", blankIfNull(result.getAwaitedEventName()));
        map.put("awaitedCorrelationId", blankIfNull(result.getAwaitedCorrelationId()));
        map.put("capabilityName", blankIfNull(result.getCapabilityName()));
        map.put("capabilityOperation", blankIfNull(result.getCapabilityOperation()));
        map.put("capabilityAdapterId", blankIfNull(result.getCapabilityAdapterId()));
        map.put("emittedEventCount", result.getEmittedEvents() == null ? 0 : result.getEmittedEvents().size());
        map.put("invariantViolationCount", result.getInvariantViolations() == null ? 0 : result.getInvariantViolations().size());
        map.put("inputValidationErrorCount", result.getInputValidationErrors() == null ? 0 : result.getInputValidationErrors().size());
        map.put("output", result.getOutput());
        return map;
    }

    private void validate(DirectExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required.");
        }
        if (isBlank(request.getFlowName())) {
            throw new IllegalArgumentException("flowName is required.");
        }
    }

    private void validatePanelAction(DirectExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required.");
        }
        if (isBlank(request.getPanelName())) {
            throw new IllegalArgumentException("panelName is required.");
        }
        if (isBlank(request.getActionName())) {
            throw new IllegalArgumentException("actionName is required.");
        }
    }

    private Map<String, Integer> countBy(List<Map<String, Object>> items, String field) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String key = referenceResolver.extractFirstString(item, field);
            if (key.isBlank()) {
                key = "unknown";
            }
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    private int countStatus(List<Map<String, Object>> items, String status) {
        int count = 0;
        for (Map<String, Object> item : items) {
            String current = referenceResolver.extractFirstString(item, "executionLifecycleStatus");
            if (status.equals(current)) {
                count++;
            }
        }
        return count;
    }

    private int countSuccessfulExecutions(List<Map<String, Object>> items) {
        int count = 0;
        for (Map<String, Object> item : items) {
            String status = executionResultStatus(item);
            if ("OK".equals(status) || "WAITING_EVENT".equals(status)) {
                count++;
            }
        }
        return count;
    }

    private int countFailedExecutions(List<Map<String, Object>> items) {
        int count = 0;
        for (Map<String, Object> item : items) {
            String lifecycle = referenceResolver.extractFirstString(item, "executionLifecycleStatus");
            if ("FAILED".equals(lifecycle) || isResultFailure(executionResultStatus(item))) {
                count++;
            }
        }
        return count;
    }

    private List<Map<String, Object>> toDistribution(Map<String, Integer> counts) {
        List<Map<String, Object>> items = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("count", entry.getValue());
                    items.add(item);
                });
        return items;
    }

    private String latestValue(List<Map<String, Object>> items, String field) {
        if (items.isEmpty()) {
            return "";
        }
        return referenceResolver.extractFirstString(items.get(0), field);
    }

    private boolean isRouteRejected(Map<String, Object> capabilityRoute) {
        return "BYPASS_REJECTED".equals(String.valueOf(capabilityRoute.getOrDefault("routeStatus", "")));
    }

    private String executionResultStatus(Map<String, Object> item) {
        String topLevel = referenceResolver.extractFirstString(item, "executionResultStatus");
        if (!topLevel.isBlank()) {
            return topLevel;
        }
        Object raw = item.get("result");
        if (raw instanceof Map<?, ?> nested) {
            Object status = nested.get("status");
            return status == null ? "" : String.valueOf(status).trim();
        }
        return "";
    }

    private boolean isResultFailure(String status) {
        return !status.isBlank()
                && !"OK".equals(status)
                && !"WAITING_EVENT".equals(status)
                && !"REJECTED".equals(status);
    }

    private String lifecycleStatusFor(ExecutionResult result) {
        if (result == null || result.getStatus() == null) {
            return "FAILED";
        }
        return switch (result.getStatus()) {
            case OK, WAITING_EVENT -> "EXECUTED";
            default -> "FAILED";
        };
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load direct execution gateway rules.", e);
        }
    }

    private void persistRecord(String directExecutionId, Map<String, Object> record) {
        try {
            Files.createDirectories(EXECUTION_ROOT);
            Path output = EXECUTION_ROOT.resolve(directExecutionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist direct execution record.", e);
        }
    }

    private String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
