package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finalexec.npdev.dto.SemanticBehaviorCanonicalizationRequest;
import com.finalexec.npdev.dto.SemanticBehaviorWriteBackRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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
import java.util.Optional;
import java.util.Properties;
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
    private static final String BUILD_INFO_RESOURCE = "npdev-build-info.properties";
    private static final String MODEL_SOURCE_PATH_KEY = "npdev.model.sourcePath";

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
        ModelSourceMutationResult modelSourceResult = applyMutationToModelSource(mutation);

        if (!modelSourceResult.applied()) {
            executionRecord.put("status", "REVIEW_REQUIRED");
            executionRecord.put("mutation", mutation);
            executionRecord.put("message", modelSourceResult.reason());
            persistExecution(executionId, executionRecord);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("requestId", requestId);
            response.put("executionId", executionId);
            response.put("tenantId", tenantId);
            response.put("status", "REVIEW_REQUIRED");
            response.put("canonicalizationPlan", canonicalizationPlan);
            response.put("mutation", mutation);
            response.put("message", modelSourceResult.reason());
            return response;
        }

        mutation.put("modelSourcePath", modelSourceResult.modelSourcePath());
        String appliedMessage = "Semantic behavior mutation written into the app's own model source at "
                + modelSourceResult.modelSourcePath() + ". Regenerate and rebuild the app for it to take effect "
                + "-- nothing in a running JVM re-reads flow definitions.";

        executionRecord.put("status", "EXECUTED");
        executionRecord.put("mutation", mutation);
        executionRecord.put("requiresRebuild", true);
        executionRecord.put("message", appliedMessage);
        persistExecution(executionId, executionRecord);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("executionId", executionId);
        response.put("tenantId", tenantId);
        response.put("status", "EXECUTED");
        response.put("canonicalizationPlan", canonicalizationPlan);
        response.put("mutation", mutation);
        response.put("modelSourcePath", modelSourceResult.modelSourcePath());
        response.put("requiresRebuild", true);
        response.put("message", appliedMessage);
        return response;
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allowedRequestTypes", ALLOWED_REQUEST_TYPES);
        summary.put("storagePath", REQUEST_ROOT.toString().replace("\\", "/"));
        summary.put("writeBackMode", "tenant-tagged-canonical-mutation-execution-v1");
        summary.put("executionStoragePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        summary.put("directExecutionTarget", "the app's own model source ("
                + MODEL_SOURCE_PATH_KEY + " from " + BUILD_INFO_RESOURCE + "), applied on next regenerate+rebuild");
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

    /**
     * REG-138: the ONLY thing in this whole platform that mutates flow definitions is a
     * regenerate+rebuild -- CompiledModelFlowDefinitionProvider builds an immutable flow map once
     * at JVM boot from the compiled model baked into the jar, and nothing ever touches it again.
     * So "execute" cannot mean "took effect in this JVM"; the honest, achievable version is
     * "wrote a real, valid change into the app's own model.json, which the NEXT regenerate+rebuild
     * will pick up" -- a structural mutation, same category as `npdev init`'s scaffolding, not a
     * live capability. Every failure path below returns REVIEW_REQUIRED with a reason rather than
     * throwing, on purpose: a malformed automatic edit that corrupts the model source is worse than
     * declining and asking a human to apply it.
     */
    private ModelSourceMutationResult applyMutationToModelSource(Map<String, Object> mutation) {
        Optional<Path> modelSourcePath = resolveModelSourcePath();
        if (modelSourcePath.isEmpty()) {
            return ModelSourceMutationResult.reviewRequired(
                    "Model source path is unknown for this app (" + MODEL_SOURCE_PATH_KEY + " is UNKNOWN in "
                            + BUILD_INFO_RESOURCE + ", e.g. because it predates REG-138's build-info fields). "
                            + "Apply this change to the model manually and regenerate."
            );
        }
        return applyMutationToModelSourceAt(mutation, modelSourcePath.get());
    }

    /** Split from {@link #applyMutationToModelSource} so the actual JSON surgery is testable
     *  against a known temp-directory path, without needing a classpath-resource fixture. */
    ModelSourceMutationResult applyMutationToModelSourceAt(Map<String, Object> mutation, Path path) {
        if (!Files.exists(path)) {
            return ModelSourceMutationResult.reviewRequired("Model source file not found at " + path + ".");
        }

        String actionType = String.valueOf(mutation.get("mutationType"));
        if (!"addNotificationStep".equals(actionType)) {
            return ModelSourceMutationResult.reviewRequired("Unsupported direct model-source mutation type: " + actionType);
        }

        ObjectNode modelRoot;
        boolean originalUsesCrlf;
        boolean originalHadTrailingNewline;
        try {
            String originalText = Files.readString(path);
            // Jackson's tree writer always emits LF and never a trailing newline -- on a
            // CRLF-checked-in file (this repo mixes both) that ends with one (most do), writing
            // back with no adjustment turns a one-step insertion into a whole-file rewrite in
            // `git diff`, the kind of noisy edit that hides the real change and makes the mutation
            // look far riskier than it is.
            originalUsesCrlf = originalText.contains("\r\n");
            originalHadTrailingNewline = originalText.endsWith("\n");
            JsonNode parsed = objectMapper.readTree(originalText);
            if (!(parsed instanceof ObjectNode)) {
                return ModelSourceMutationResult.reviewRequired("Model source at " + path + " is not a JSON object.");
            }
            modelRoot = (ObjectNode) parsed;
        } catch (Exception e) {
            return ModelSourceMutationResult.reviewRequired("Failed to read model source at " + path + ": " + e.getMessage());
        }

        if (!declaresCapability(modelRoot, "notification")) {
            return ModelSourceMutationResult.reviewRequired(
                    "Model does not declare a 'notification' capability; a capabilityCall step targeting it would fail generation."
            );
        }

        String flowName = String.valueOf(mutation.get("flowName"));
        ObjectNode targetFlow = findFlowByName(modelRoot, flowName);
        if (targetFlow == null) {
            return ModelSourceMutationResult.reviewRequired("Flow '" + flowName + "' not found in model source.");
        }

        String stepName = String.valueOf(mutation.get("stepName"));
        ArrayNode steps = targetFlow.withArray("steps");
        for (JsonNode existingStep : steps) {
            if (existingStep.isObject() && stepName.equalsIgnoreCase(existingStep.path("name").asText(""))) {
                return ModelSourceMutationResult.reviewRequired(
                        "Flow '" + flowName + "' already has a step named '" + stepName + "'."
                );
            }
        }

        ObjectNode newStep = objectMapper.createObjectNode();
        newStep.put("name", stepName);
        newStep.put("type", "capabilityCall");
        newStep.put("capability", "notification");
        newStep.put("operation", "send");
        steps.add(newStep);

        try {
            String rewritten = objectMapper.writer(modelSourcePrettyPrinter()).writeValueAsString(modelRoot);
            if (originalHadTrailingNewline) {
                rewritten = rewritten + "\n";
            }
            if (originalUsesCrlf) {
                rewritten = rewritten.replace("\r\n", "\n").replace("\n", "\r\n");
            }
            Files.writeString(path, rewritten);
        } catch (Exception e) {
            return ModelSourceMutationResult.reviewRequired("Failed to write model source at " + path + ": " + e.getMessage());
        }

        return ModelSourceMutationResult.applied(path.toString().replace("\\", "/"));
    }

    private boolean declaresCapability(ObjectNode modelRoot, String capabilityName) {
        for (JsonNode capability : modelRoot.withArray("capabilities")) {
            if (capability.isObject() && capabilityName.equalsIgnoreCase(capability.path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode findFlowByName(ObjectNode modelRoot, String flowName) {
        for (JsonNode flow : modelRoot.withArray("flows")) {
            if (flow.isObject() && flowName.equalsIgnoreCase(flow.path("name").asText(""))) {
                return (ObjectNode) flow;
            }
        }
        return null;
    }

    /**
     * Every model.json this platform ships (npdev init's scaffold, every sample under
     * NPDevContract/dsl/resources/Models/) uses 2-space indent, "key": value (no space before the
     * colon), and one array element per line -- Jackson's own writerWithDefaultPrettyPrinter()
     * instead writes "key" : value and puts array-of-object elements on the SAME line as `[`/`,`
     * (a well-known Jackson default quirk), which turns a one-step insertion into a whole-file
     * rewrite in `git diff` and makes an automatic edit look far riskier than it is.
     */
    private static DefaultPrettyPrinter modelSourcePrettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    private Optional<Path> resolveModelSourcePath() {
        ClassPathResource resource = new ClassPathResource(BUILD_INFO_RESOURCE);
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            Properties properties = new Properties();
            properties.load(inputStream);
            String value = properties.getProperty(MODEL_SOURCE_PATH_KEY, "UNKNOWN").trim();
            if (value.isEmpty() || "UNKNOWN".equals(value)) {
                return Optional.empty();
            }
            return Optional.of(Path.of(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    record ModelSourceMutationResult(boolean applied, String modelSourcePath, String reason) {
        static ModelSourceMutationResult applied(String modelSourcePath) {
            return new ModelSourceMutationResult(true, modelSourcePath, null);
        }

        static ModelSourceMutationResult reviewRequired(String reason) {
            return new ModelSourceMutationResult(false, null, reason);
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
            // try-with-resources (QUAL-2) -- see TemplateLibraryManagementService for the full note.
            if (Files.exists(root)) {
                try (var paths = Files.list(root)) {
                    paths
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