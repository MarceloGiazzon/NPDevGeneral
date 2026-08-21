package com.finalexec.npdev.service;

import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ExecutionMonitorService {

    private static final Path DIRECT_EXECUTION_ROOT = Path.of("runtime-data", "direct-executions");
    private static final String EXECUTION_DETAIL_PATH_TEMPLATE = "/api/executions/{executionId}";

    private final KernelFacade kernelFacade;
    private final PublicationChainReferenceResolver referenceResolver;

    public ExecutionMonitorService(
            KernelFacade kernelFacade,
            PublicationChainReferenceResolver referenceResolver
    ) {
        this.kernelFacade = kernelFacade;
        this.referenceResolver = referenceResolver;
    }

    public Map<String, Object> active(ExecutionContext requesterContext) {
        Map<String, Map<String, Object>> directExecutionByExecutionId = directExecutionIndex();
        List<Map<String, Object>> items = kernelFacade.listExecutions(100, 0, requesterContext).stream()
                .filter(execution -> isActive(execution.status()))
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed())
                .map(execution -> executionCard(execution, directExecutionByExecutionId.get(execution.executionId())))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Execution Monitor");
        response.put("mode", "active");
        response.put("detailRouteTemplate", EXECUTION_DETAIL_PATH_TEMPLATE);
        response.put("activeCount", items.size());
        response.put("needsAttentionCount", countByAttention(items, "NEEDS_ATTENTION"));
        response.put("items", items);
        return response;
    }

    public Map<String, Object> history(ExecutionContext requesterContext) {
        Map<String, Map<String, Object>> directExecutionByExecutionId = directExecutionIndex();
        List<Map<String, Object>> items = kernelFacade.listExecutions(100, 0, requesterContext).stream()
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed())
                .map(execution -> executionCard(execution, directExecutionByExecutionId.get(execution.executionId())))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Execution Monitor");
        response.put("mode", "history");
        response.put("detailRouteTemplate", EXECUTION_DETAIL_PATH_TEMPLATE);
        response.put("historyCount", items.size());
        response.put("succeededCount", countByOutcome(items, "SUCCEEDED"));
        response.put("failedCount", countByOutcome(items, "FAILED"));
        response.put("activeCount", countByOutcome(items, "ACTIVE"));
        response.put("items", items);
        return response;
    }

    /**
     * R2.2: the stuck queue, backed by {@code ExecutionSummaryStore.listStuckSummaries} -- a
     * purpose-built query that both {@code JdbcFlowInstanceStore} and {@code InProcFlowInstanceStore}
     * implemented, and that until now no REST surface called at all. {@link #active} does surface
     * STUCK rows, but only as part of a 100-row recent window that a busy app pushes them out of.
     *
     * <p>Cards are built from {@link ExecutionSummary}, not {@link FlowInstance}, so they carry no
     * flow state -- deliberate: this is a triage list, and the summary query is the whole point of
     * using it instead of re-filtering {@code listExecutions}.
     */
    public Map<String, Object> stuck(ExecutionContext requesterContext) {
        List<Map<String, Object>> items = kernelFacade.listRecentStuckExecutions(100, 0, requesterContext).stream()
                .map(this::stuckCard)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Execution Monitor");
        response.put("mode", "stuck");
        response.put("detailRouteTemplate", EXECUTION_DETAIL_PATH_TEMPLATE);
        response.put("stuckCount", items.size());
        response.put("items", items);
        return response;
    }

    /**
     * R2.2: hand a STUCK execution back to the resume sweep.
     *
     * <p>Every outcome comes back as an explicit body rather than a thrown
     * {@code ResponseStatusException}, for the same measured reason {@code AgentProxyController}
     * documents: Spring Boot defaults {@code server.error.include-message} to {@code never}, so a
     * thrown exception's reason reaches the caller as an empty string -- and "this execution is
     * COMPLETED, not STUCK" is the entire diagnostic value of the 409. The controller maps
     * {@code outcome} to a status code; nothing here knows about HTTP.
     */
    public Map<String, Object> unstick(String executionId, ExecutionContext requesterContext) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Execution Monitor");
        response.put("executionId", blankIfNull(executionId));
        try {
            Optional<FlowInstance> unstuck = kernelFacade.unstickExecution(executionId, requesterContext);
            if (unstuck.isEmpty()) {
                response.put("ok", false);
                response.put("outcome", "NOT_FOUND");
                response.put("message", "No execution exists with that id.");
                return response;
            }
            response.put("ok", true);
            response.put("outcome", "UNSTUCK");
            response.put("message", "Execution returned to WAITING_EVENT with a cleared attempt count; "
                    + "the next resume sweep will retry it, or publish the awaited event to retry now.");
            response.put("execution", executionCard(unstuck.get(), Map.of()));
            return response;
        } catch (IllegalStateException notUnstickable) {
            response.put("ok", false);
            response.put("outcome", "NOT_STUCK");
            response.put("message", blankIfNull(notUnstickable.getMessage()));
            return response;
        }
    }

    private Map<String, Object> stuckCard(ExecutionSummary summary) {
        String status = blankIfNull(summary.status());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("executionId", summary.executionId());
        item.put("flowName", summary.flowName());
        item.put("tenantId", summary.tenantId());
        item.put("correlationId", summary.correlationId());
        item.put("status", status);
        item.put("outcome", outcome(status));
        item.put("attentionLevel", attentionLevel(status));
        item.put("currentStepIndex", summary.currentStepIndex());
        item.put("waitingForEventName", blankIfNull(summary.waitingForEventName()));
        item.put("updatedAtEpochMs", summary.updatedAtMs());
        item.put("resumeAttemptCount", summary.resumeAttemptCount());
        item.put("lastResumeErrorCode", blankIfNull(summary.lastResumeErrorCode()));
        item.put("lastErrorKind", blankIfNull(summary.lastErrorKind()));
        item.put("lastErrorCode", blankIfNull(summary.lastErrorCode()));
        item.put("failedAtEpochMs", summary.failedAtEpochMs() == null ? 0L : summary.failedAtEpochMs());
        item.put("detailPath", "/api/executions/" + summary.executionId());
        item.put("linksPath", "/api/executions/" + summary.executionId() + "/links");
        item.put("unstickPath", "/api/executions/" + summary.executionId() + "/unstick");
        return item;
    }

    public Map<String, Object> links(String executionId, ExecutionContext requesterContext) {
        FlowInstance execution = kernelFacade.findExecution(executionId, requesterContext)
                .orElseThrow(() -> new IllegalArgumentException("executionId was not found."));

        Map<String, Object> directExecution = directExecutionIndex().getOrDefault(executionId, Map.of());
        String directExecutionReference = stringValue(directExecution.get("directExecutionReference"));
        String governanceReference = stringValue(nestedValue(directExecution, "governanceRecord", "governanceReference"));

        List<Map<String, Object>> surfaceLinks = new ArrayList<>();
        surfaceLinks.add(surfaceLink("detail-api", "/api/executions/" + executionId, "Execution detail source"));
        surfaceLinks.add(surfaceLink("explainability-ui", "/explainability-graph", "Explainability view"));
        surfaceLinks.add(surfaceLink("governance-ui", "/governance-workspace", "Governance view"));
        surfaceLinks.add(surfaceLink("topology-ui", "/runtime-topology-explorer", "Runtime topology view"));
        surfaceLinks.add(surfaceLink("rollback-history", "/api/admin/publication-rollback/history", "Rollback history"));
        surfaceLinks.add(surfaceLink("recovery-history", "/api/admin/publication-failure-recovery/history", "Recovery history"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Execution Monitor");
        response.put("executionId", execution.executionId());
        response.put("detailRouteTemplate", EXECUTION_DETAIL_PATH_TEMPLATE);
        response.put("detailPath", "/api/executions/" + execution.executionId());
        response.put("executionStatus", execution.status() == null ? "" : execution.status().name());
        response.put("flowName", execution.flowName());
        response.put("tenantId", execution.tenantId());
        response.put("correlationId", execution.correlationId());
        response.put("directExecutionReference", directExecutionReference);
        response.put("governanceReference", governanceReference);
        response.put("surfaceLinks", surfaceLinks);
        response.put("recommendedActions", recommendedActions(execution, directExecutionReference, governanceReference));
        return response;
    }

    private Map<String, Object> executionCard(FlowInstance execution, Map<String, Object> directExecution) {
        String status = execution.status() == null ? "" : execution.status().name();
        String outcome = outcome(status);
        String attentionLevel = attentionLevel(status);
        long durationMs = Math.max(0L, execution.updatedAtEpochMs() - execution.createdAtEpochMs());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("executionId", execution.executionId());
        item.put("flowName", execution.flowName());
        item.put("tenantId", execution.tenantId());
        item.put("correlationId", execution.correlationId());
        item.put("status", status);
        item.put("outcome", outcome);
        item.put("attentionLevel", attentionLevel);
        item.put("createdAtEpochMs", execution.createdAtEpochMs());
        item.put("updatedAtEpochMs", execution.updatedAtEpochMs());
        item.put("durationMs", durationMs);
        item.put("waitingForEventName", blankIfNull(execution.waitingForEventName()));
        item.put("resumeAttemptCount", execution.resumeAttemptCount());
        item.put("lastErrorCode", blankIfNull(execution.lastErrorCode()));
        item.put("lastErrorKind", blankIfNull(execution.lastErrorKind()));
        item.put("lastErrorMessage", firstNonBlank(
                execution.lastErrorMessage(),
                nestedValue(directExecution, "result", "error")
        ));
        item.put("compensationStatus", compensationStatus(execution));
        item.put("directExecutionReference", stringValue(directExecution.get("directExecutionReference")));
        item.put("governanceReference", stringValue(nestedValue(directExecution, "governanceRecord", "governanceReference")));
        item.put("detailPath", "/api/executions/" + execution.executionId());
        item.put("linksPath", "/api/executions/" + execution.executionId() + "/links");
        item.put("topologyPath", "/runtime-topology-explorer");
        item.put("explainabilityPath", "/explainability-graph");
        item.put("governancePath", "/governance-workspace");
        return item;
    }

    /**
     * LNCH-17: ControlPanel visibility into whether a failed execution ran (or is still running)
     * declared {@code onFailure} compensation steps. {@code "__npdev_compensating__"} mirrors
     * {@code KernelRunner}'s own private reserved flow-state key (not importable -- it's an
     * internal detail of the execution engine, this is just reading the same durable marker back).
     * {@code COMPENSATING} means a crash left compensation mid-run (see
     * docs/architecture/FLOW_TRANSACTION_CONTRACT.md); a normal in-process failure always finishes
     * compensating (or decides there's nothing to compensate) before the execution is persisted as
     * terminal, so callers reading a fully-settled execution will only ever see NONE or COMPENSATED.
     */
    private static String compensationStatus(FlowInstance execution) {
        Object marker = execution.state() == null ? null : execution.state().get("__npdev_compensating__");
        if (Boolean.TRUE.equals(marker)) {
            return "COMPENSATING";
        }
        String status = execution.status() == null ? "" : execution.status().name();
        boolean terminalFailure = "FAILED".equals(status) || "FAILED_PERMANENT".equals(status) || "STUCK".equals(status);
        return terminalFailure ? "COMPENSATED_OR_NONE" : "NONE";
    }

    private List<Map<String, Object>> recommendedActions(
            FlowInstance execution,
            String directExecutionReference,
            String governanceReference
    ) {
        List<Map<String, Object>> actions = new ArrayList<>();
        String status = execution.status() == null ? "" : execution.status().name();

        actions.add(surfaceLink("topology-ui", "/runtime-topology-explorer", "Inspect runtime topology"));
        if (!directExecutionReference.isBlank()) {
            actions.add(surfaceLink("explainability-ui", "/explainability-graph", "Open explainability for related execution evidence"));
        }
        if (!governanceReference.isBlank()) {
            actions.add(surfaceLink("governance-ui", "/governance-workspace", "Review governance context"));
        }
        if ("FAILED".equals(status) || "FAILED_PERMANENT".equals(status) || "STUCK".equals(status)) {
            actions.add(surfaceLink("rollback-history", "/api/admin/publication-rollback/history", "Review rollback candidates"));
            actions.add(surfaceLink("recovery-history", "/api/admin/publication-failure-recovery/history", "Review recovery records"));
        }
        if ("WAITING_EVENT".equals(status)) {
            actions.add(surfaceLink("detail-api", "/api/executions/" + execution.executionId(), "Inspect awaited event details"));
        }
        return actions;
    }

    private Map<String, Map<String, Object>> directExecutionIndex() {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> record : referenceResolver.readRecords(DIRECT_EXECUTION_ROOT)) {
            String executionId = stringValue(nestedValue(record, "result", "executionId"));
            if (!executionId.isBlank()) {
                index.putIfAbsent(executionId, record);
            }
        }
        return index;
    }

    private int countByOutcome(List<Map<String, Object>> items, String expected) {
        int count = 0;
        for (Map<String, Object> item : items) {
            if (expected.equals(item.get("outcome"))) {
                count++;
            }
        }
        return count;
    }

    private int countByAttention(List<Map<String, Object>> items, String expected) {
        int count = 0;
        for (Map<String, Object> item : items) {
            if (expected.equals(item.get("attentionLevel"))) {
                count++;
            }
        }
        return count;
    }

    private boolean isActive(FlowInstanceStatus status) {
        return status == FlowInstanceStatus.RUNNING
                || status == FlowInstanceStatus.WAITING_EVENT
                || status == FlowInstanceStatus.STUCK;
    }

    private String outcome(String status) {
        return switch (status) {
            case "RUNNING", "WAITING_EVENT", "STUCK" -> "ACTIVE";
            case "COMPLETED" -> "SUCCEEDED";
            case "FAILED", "FAILED_PERMANENT" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private String attentionLevel(String status) {
        return switch (status) {
            case "FAILED", "FAILED_PERMANENT", "STUCK" -> "NEEDS_ATTENTION";
            case "WAITING_EVENT" -> "WATCHING";
            case "RUNNING" -> "IN_PROGRESS";
            case "COMPLETED" -> "CLEAR";
            default -> "REVIEW";
        };
    }

    private Map<String, Object> surfaceLink(String kind, String path, String label) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("kind", kind);
        link.put("path", path);
        link.put("label", label);
        return link;
    }

    private Object nestedValue(Map<String, Object> record, String nestedKey, String field) {
        Object nested = record.get(nestedKey);
        if (nested instanceof Map<?, ?> rawMap) {
            return rawMap.get(field);
        }
        return "";
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
