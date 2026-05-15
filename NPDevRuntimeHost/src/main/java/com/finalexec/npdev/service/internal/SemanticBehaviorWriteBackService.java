package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.SemanticBehaviorCanonicalizationRequest;
import com.finalexec.npdev.dto.SemanticBehaviorWriteBackRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SemanticBehaviorWriteBackService {

    private static final List<String> ALLOWED_REQUEST_TYPES = List.of(
            "addInvariant",
            "addLifecycleState",
            "addLifecycleTransition",
            "addOrchestrationStep",
            "addAwaitEventStep"
    );

    private static final Path REQUEST_ROOT = Paths.get("runtime-data", "semantic-behavior-writeback-requests");
    private static final Path EXECUTION_ROOT = Paths.get("runtime-data", "canonical-mutation-executions", "semantic-behavior");
    private static final Path WORKSPACE_FILE = Paths.get("runtime-data", "canonical-workspace", "semantic-behavior", "semantic-behavior-workspace.json");

    private final ObjectMapper objectMapper;
    private final SemanticBehaviorWriteBackCanonicalizationService semanticBehaviorWriteBackCanonicalizationService;

    public SemanticBehaviorWriteBackService(
            ObjectMapper objectMapper,
            SemanticBehaviorWriteBackCanonicalizationService semanticBehaviorWriteBackCanonicalizationService
    ) {
        this.objectMapper = objectMapper;
        this.semanticBehaviorWriteBackCanonicalizationService = semanticBehaviorWriteBackCanonicalizationService;
    }

    public Map<String, Object> submit(SemanticBehaviorWriteBackRequest request) {
        validate(request);

        String requestId = UUID.randomUUID().toString();
        String submittedAt = utcNow();
        Map<String, Object> payload = request.getPayload();
        String tenantId = resolveTenantId(payload);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("requestId", requestId);
        record.put("requestType", request.getRequestType());
        record.put("tenantId", tenantId);
        record.put("payload", payload);
        record.put("status", "RECEIVED");
        record.put("submittedAt", submittedAt);

        persistRecord(requestId, record);

        Map<String, Object> canonicalizationPlan = semanticBehaviorWriteBackCanonicalizationService.canonicalize(
                toCanonicalizationRequest(requestId, request)
        );
        canonicalizationPlan.put("tenantId", tenantId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("requestType", request.getRequestType());
        response.put("tenantId", tenantId);
        response.put("status", "RECEIVED");
        response.put("submittedAt", submittedAt);
        response.put("canonicalizationPlan", canonicalizationPlan);
        response.put("message", "Semantic behavior write-back request received and canonicalization planned.");
        return response;
    }

    public Map<String, Object> execute(SemanticBehaviorWriteBackRequest request) {
        validate(request);

        String requestId = UUID.randomUUID().toString();
        String submittedAt = utcNow();
        Map<String, Object> payload = request.getPayload();
        String tenantId = resolveTenantId(payload);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("requestId", requestId);
        record.put("requestType", request.getRequestType());
        record.put("tenantId", tenantId);
        record.put("payload", payload);
        record.put("status", "RECEIVED_FOR_EXECUTION");
        record.put("submittedAt", submittedAt);

        persistRecord(requestId, record);

        Map<String, Object> canonicalizationPlan = semanticBehaviorWriteBackCanonicalizationService.canonicalize(
                toCanonicalizationRequest(requestId, request)
        );
        canonicalizationPlan.put("tenantId", tenantId);

        String actionType = String.valueOf(canonicalizationPlan.getOrDefault("actionType", "unsupportedAction"));
        String outcome = String.valueOf(canonicalizationPlan.getOrDefault("outcome", "REVIEW_REQUIRED"));
        boolean directlyExecutable = isDirectlyExecutable(actionType, outcome);

        Map<String, Object> executionRecord = new LinkedHashMap<>();
        String executionId = UUID.randomUUID().toString();
        executionRecord.put("executionId", executionId);
        executionRecord.put("requestId", requestId);
        executionRecord.put("requestType", request.getRequestType());
        executionRecord.put("tenantId", tenantId);
        executionRecord.put("submittedAt", submittedAt);
        executionRecord.put("executedAt", utcNow());
        executionRecord.put("canonicalizationPlan", canonicalizationPlan);
        executionRecord.put("workspacePath", WORKSPACE_FILE.toString().replace("\\", "/"));

        if (!directlyExecutable) {
            executionRecord.put("status", "REVIEW_REQUIRED");
            executionRecord.put("message", "Semantic behavior mutation is not directly executable in v1.");
            persistExecution(executionId, executionRecord);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("requestId", requestId);
            response.put("executionId", executionId);
            response.put("tenantId", tenantId);
            response.put("status", "REVIEW_REQUIRED");
            response.put("canonicalizationPlan", canonicalizationPlan);
            response.put("message", "Semantic behavior mutation requires review before execution.");
            return response;
        }

        Map<String, Object> mutation = buildMutation(requestId, tenantId, request, actionType);
        applyMutationToWorkspace(mutation);

        executionRecord.put("status", "EXECUTED");
        executionRecord.put("mutation", mutation);
        executionRecord.put("message", "Semantic behavior mutation executed into runtime canonical workspace.");
        persistExecution(executionId, executionRecord);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("executionId", executionId);
        response.put("tenantId", tenantId);
        response.put("status", "EXECUTED");
        response.put("canonicalizationPlan", canonicalizationPlan);
        response.put("mutation", mutation);
        response.put("workspacePath", WORKSPACE_FILE.toString().replace("\\", "/"));
        response.put("message", "Semantic behavior mutation executed into runtime canonical workspace.");
        return response;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allowedRequestTypes", ALLOWED_REQUEST_TYPES);
        summary.put("storagePath", REQUEST_ROOT.toString().replace("\\", "/"));
        summary.put("writeBackMode", "tenant-tagged-canonical-mutation-execution-v1");
        summary.put("executionStoragePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        summary.put("workspacePath", WORKSPACE_FILE.toString().replace("\\", "/"));
        summary.put("directExecutionActions", List.of("addNotificationStep"));
        summary.put("tenantPropagation", "tenantId propagated into request, execution, and mutation artifacts");
        summary.put("canonicalization", semanticBehaviorWriteBackCanonicalizationService.summary());
        return summary;
    }

    public Map<String, Object> history() {
        return readJsonHistory(REQUEST_ROOT, "submittedAt");
    }

    public Map<String, Object> executionHistory() {
        return readJsonHistory(EXECUTION_ROOT, "executedAt");
    }

    private SemanticBehaviorCanonicalizationRequest toCanonicalizationRequest(String requestId, SemanticBehaviorWriteBackRequest request) {
        Map<String, Object> payload = request.getPayload();

        SemanticBehaviorCanonicalizationRequest canonicalizationRequest = new SemanticBehaviorCanonicalizationRequest();
        canonicalizationRequest.setRequestId(requestId);
        canonicalizationRequest.setActionType(mapActionType(request.getRequestType(), payload));
        canonicalizationRequest.setFlowName(resolveFlowName(request.getRequestType(), payload));
        canonicalizationRequest.setStepName(asString(payload.get("stepName")));
        canonicalizationRequest.setRequestedBy(asStringOrDefault(payload.get("requestedBy"), "semantic-behavior-writeback"));
        canonicalizationRequest.setNotificationChannel(asString(payload.get("notificationChannel")));
        canonicalizationRequest.setRetryCount(asInteger(payload.get("retryCount")));
        canonicalizationRequest.setTimeoutSeconds(asInteger(payload.get("timeoutSeconds")));
        return canonicalizationRequest;
    }

    private boolean isDirectlyExecutable(String actionType, String outcome) {
        return "CANONICALIZABLE".equals(outcome) && "addNotificationStep".equals(actionType);
    }

    private Map<String, Object> buildMutation(
            String requestId,
            String tenantId,
            SemanticBehaviorWriteBackRequest request,
            String actionType
    ) {
        Map<String, Object> payload = request.getPayload();
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("mutationId", UUID.randomUUID().toString());
        mutation.put("requestId", requestId);
        mutation.put("tenantId", tenantId);
        mutation.put("mutationType", actionType);
        mutation.put("appliedAt", utcNow());

        if ("addNotificationStep".equals(actionType)) {
            mutation.put("target", "flows/" + resolveFlowName(request.getRequestType(), payload) + "/steps/" + asString(payload.get("stepName")));
            mutation.put("flowName", resolveFlowName(request.getRequestType(), payload));
            mutation.put("stepName", asString(payload.get("stepName")));
            mutation.put("notificationChannel", asString(payload.get("notificationChannel")));
        } else {
            throw new IllegalArgumentException("Unsupported direct semantic behavior execution type: " + actionType);
        }

        return mutation;
    }

    private void applyMutationToWorkspace(Map<String, Object> mutation) {
        try {
            Files.createDirectories(WORKSPACE_FILE.getParent());

            Map<String, Object> workspace;
            if (Files.exists(WORKSPACE_FILE)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = objectMapper.readValue(WORKSPACE_FILE.toFile(), LinkedHashMap.class);
                workspace = existing;
            } else {
                workspace = new LinkedHashMap<>();
                workspace.put("workspaceType", "semantic-behavior-runtime-canonical-workspace");
                workspace.put("createdAt", utcNow());
                workspace.put("appliedMutations", new ArrayList<Map<String, Object>>());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> appliedMutations =
                    (List<Map<String, Object>>) workspace.computeIfAbsent("appliedMutations", key -> new ArrayList<Map<String, Object>>());

            appliedMutations.add(mutation);
            workspace.put("lastAppliedAt", utcNow());
            workspace.put("appliedMutationCount", appliedMutations.size());

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(WORKSPACE_FILE.toFile(), workspace);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply semantic behavior mutation to runtime canonical workspace.", e);
        }
    }

    private void validate(SemanticBehaviorWriteBackRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.getRequestType() == null || request.getRequestType().isBlank()) {
            throw new IllegalArgumentException("requestType is required.");
        }
        if (!ALLOWED_REQUEST_TYPES.contains(request.getRequestType())) {
            throw new IllegalArgumentException("Unsupported requestType: " + request.getRequestType());
        }

        Map<String, Object> payload = request.getPayload();
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("payload is required.");
        }

        switch (request.getRequestType()) {
            case "addInvariant" -> requireKeys(payload, "targetName", "ruleName", "expression", "message");
            case "addLifecycleState" -> requireKeys(payload, "targetName", "stateName");
            case "addLifecycleTransition" -> requireKeys(payload, "targetName", "fromState", "toState", "transitionName");
            case "addOrchestrationStep" -> requireKeys(payload, "flowName", "stepName", "stepKind");
            case "addAwaitEventStep" -> requireKeys(payload, "flowName", "stepName", "eventName", "correlationField");
            default -> throw new IllegalArgumentException("Unsupported requestType: " + request.getRequestType());
        }
    }

    private Map<String, Object> readJsonHistory(Path root, String timeKey) {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            if (Files.exists(root)) {
                Files.list(root)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> item = objectMapper.readValue(path.toFile(), LinkedHashMap.class);
                                items.add(item);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception ignored) {
        }

        items.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault(timeKey, "")),
                Comparator.reverseOrder()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    private void requireKeys(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value == null) {
                throw new IllegalArgumentException("payload." + key + " is required.");
            }
            if (value instanceof String s && s.isBlank()) {
                throw new IllegalArgumentException("payload." + key + " is required.");
            }
        }
    }

    private String mapActionType(String requestType, Map<String, Object> payload) {
        return switch (requestType) {
            case "addOrchestrationStep" -> {
                String stepKind = asString(payload.get("stepKind"));
                if ("approval".equalsIgnoreCase(stepKind)) {
                    yield "addApprovalStep";
                }
                if ("notification".equalsIgnoreCase(stepKind)) {
                    yield "addNotificationStep";
                }
                yield "unsupportedAction";
            }
            case "addAwaitEventStep" -> payload.containsKey("timeoutSeconds") ? "setTimeoutPolicy" : "unsupportedAction";
            default -> "unsupportedAction";
        };
    }

    private String resolveFlowName(String requestType, Map<String, Object> payload) {
        if (payload.containsKey("flowName")) {
            return asString(payload.get("flowName"));
        }
        if (payload.containsKey("targetName")) {
            return asString(payload.get("targetName"));
        }
        return requestType;
    }

    private String resolveTenantId(Map<String, Object> payload) {
        return asStringOrDefault(payload.get("tenantId"), "global");
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private String asStringOrDefault(Object value, String fallback) {
        String resolved = asString(value);
        return resolved == null ? fallback : resolved;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private void persistRecord(String requestId, Map<String, Object> record) {
        try {
            Files.createDirectories(REQUEST_ROOT);
            Path output = REQUEST_ROOT.resolve(requestId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist semantic behavior write-back request.", e);
        }
    }

    private void persistExecution(String executionId, Map<String, Object> record) {
        try {
            Files.createDirectories(EXECUTION_ROOT);
            Path output = EXECUTION_ROOT.resolve(executionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist semantic behavior canonical execution.", e);
        }
    }
}