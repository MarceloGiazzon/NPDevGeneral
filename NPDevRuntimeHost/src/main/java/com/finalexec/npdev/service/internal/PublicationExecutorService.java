package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.PublicationExecutionRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PublicationExecutorService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-publication-executor/publication-executor-rules.json";

    private static final Path EXECUTION_ROOT =
            Paths.get("runtime-data", "publication-executions");
    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;

    public PublicationExecutorService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "publication-executor-v1"));
        response.put("executionReality", rules.getOrDefault(
                "executionReality",
                "governed execution may still be partial and review-aware"
        ));
        response.put("supportedExecutionModes", rules.getOrDefault("supportedExecutionModes", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        response.put("supportedOutcomes", rules.getOrDefault("supportedOutcomes", List.of()));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(EXECUTION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> executePublication(PublicationExecutionRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String publicationExecutionId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String executionMode = request.getExecutionMode().trim();
        String executionStatus = firstListValue(rules.get("supportedStatuses"), "RECORDED_EXECUTION_ATTEMPT");
        String executionOutcome = firstListValue(
                rules.get("supportedOutcomes"),
                "PARTIALLY_EXECUTED_REVIEW_REQUIRED"
        );
        String executedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "publication-executor-v1"));
        Map<String, Object> resolvedTransaction = referenceResolver.resolveSingle(
                TRANSACTION_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "publicationTransactionId"
        );
        List<String> structuralLinks = resolvedTransaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        resolvedTransaction,
                        "resolvedStructuralMappingReferences",
                        "structuralMappingReferences"
                );
        List<String> semanticLinks = resolvedTransaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        resolvedTransaction,
                        "resolvedSemanticMappingReferences",
                        "semanticMappingReferences"
                );
        List<String> governanceLinks = resolvedTransaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        resolvedTransaction,
                        "resolvedApprovalReferences",
                        "approvalReferences",
                        "resolvedRollbackAnchorReferences",
                        "rollbackAnchorReferences"
                );
        int resolvedLayers = 0;
        if (resolvedTransaction != null) {
            resolvedLayers++;
        }
        if (!structuralLinks.isEmpty()) {
            resolvedLayers++;
        }
        if (!semanticLinks.isEmpty()) {
            resolvedLayers++;
        }
        if (!governanceLinks.isEmpty()) {
            resolvedLayers++;
        }
        String executionDepth = resolvedTransaction == null ? "REQUEST_ONLY" : "TRANSACTION_PLUS_LINKED_CONTEXT";
        String executionCoverage = resolvedLayers + "/4 linked publication layers resolved";
        List<String> executedActions = new ArrayList<>();
        List<String> reviewRequiredActions = new ArrayList<>();
        List<String> skippedActions = new ArrayList<>();
        if (resolvedTransaction != null) {
            executedActions.add("resolved publication transaction");
        } else {
            reviewRequiredActions.add("resolve publication transaction");
        }
        if (!structuralLinks.isEmpty()) {
            executedActions.add("loaded structural mapping context");
        } else {
            reviewRequiredActions.add("review structural mapping linkage");
        }
        if (!semanticLinks.isEmpty()) {
            executedActions.add("loaded semantic mapping context");
        } else {
            reviewRequiredActions.add("review semantic mapping linkage");
        }
        if (!governanceLinks.isEmpty()) {
            executedActions.add("loaded governance linkage context");
        } else {
            reviewRequiredActions.add("review governance linkage context");
        }
        skippedActions.add("canonical source mutation");
        skippedActions.add("automatic rollback execution");
        String integrityStatus = resolvedTransaction == null ? "UNRESOLVED" : "PARTIALLY_RESOLVED";
        String outcomeNotes =
                "Execution v1 recorded governed publication depth and coverage. Canonical source mutation remains later-stage work.";

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("publicationExecutionId", publicationExecutionId);
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("draftReference", request.getDraftReference().trim());
        record.put("resolvedTransactionId", resolvedTransaction == null
                ? ""
                : referenceResolver.extractFirstString(resolvedTransaction, "publicationTransactionId"));
        record.put("resolvedTransactionIntegrityStatus", resolvedTransaction == null
                ? "UNRESOLVED"
                : referenceResolver.extractFirstString(resolvedTransaction, "integrityStatus"));
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("executionMode", executionMode);
        record.put("executionStatus", executionStatus);
        record.put("executionOutcome", executionOutcome);
        record.put("executionDepth", executionDepth);
        record.put("executionCoverage", executionCoverage);
        record.put("executedActions", executedActions);
        record.put("reviewRequiredActions", reviewRequiredActions);
        record.put("skippedActions", skippedActions);
        record.put("integrityStatus", integrityStatus);
        record.put("reviewRequired", true);
        record.put("outcomeNotes", outcomeNotes);
        record.put("executedAt", executedAt);
        record.put("mode", mode);

        persistRecord(publicationExecutionId, record);
        return record;
    }

    private void validate(PublicationExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTransactionReference())) {
            throw new IllegalArgumentException("transactionReference is required.");
        }
        if (isBlank(request.getDraftReference())) {
            throw new IllegalArgumentException("draftReference is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
        if (isBlank(request.getExecutionMode())) {
            throw new IllegalArgumentException("executionMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load publication executor rules.", e);
        }
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private String firstListValue(Object raw, String defaultValue) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return defaultValue;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void persistRecord(String publicationExecutionId, Map<String, Object> record) {
        try {
            java.nio.file.Files.createDirectories(EXECUTION_ROOT);
            Path output = EXECUTION_ROOT.resolve(publicationExecutionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist publication execution record.", e);
        }
    }
}
