package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.CanonicalSourceMutationExecutionRequest;
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
public class CanonicalSourceMutationExecutorService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-canonical-source-mutation/canonical-source-mutation-rules.json";

    private static final Path MUTATION_ROOT =
            Paths.get("runtime-data", "canonical-source-mutations");
    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");
    private static final Path STRUCTURAL_MAPPING_ROOT =
            Paths.get("runtime-data", "structural-publication-mappings");
    private static final Path SEMANTIC_MAPPING_ROOT =
            Paths.get("runtime-data", "semantic-publication-mappings");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final CanonicalSourceArtifactStore canonicalSourceArtifactStore;

    public CanonicalSourceMutationExecutorService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            CanonicalSourceArtifactStore canonicalSourceArtifactStore
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.canonicalSourceArtifactStore = canonicalSourceArtifactStore;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", MUTATION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "canonical-source-mutation-v1"));
        response.put("executionReality", rules.getOrDefault(
                "executionReality",
                "authoritative canonical source mutation with explicit review-aware coverage"
        ));
        response.put("supportedExecutionModes", rules.getOrDefault("supportedExecutionModes", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        response.put("canonicalArtifactStore", rules.getOrDefault("canonicalArtifactStore", ""));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(MUTATION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> execute(CanonicalSourceMutationExecutionRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String mutationExecutionId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String executedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "canonical-source-mutation-v1"));
        Map<String, Object> tenantAssessment = referenceResolver.assessTenantIsolation(
                TRANSACTION_ROOT,
                tenantId,
                List.of(request.getTransactionReference().trim()),
                "publication transaction",
                "transactionReference",
                "publicationTransactionId"
        );
        Map<String, Object> transaction = referenceResolver.resolveSingle(
                TRANSACTION_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "publicationTransactionId"
        );
        List<String> structuralMappings = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedStructuralMappingReferences",
                        "structuralMappingReferences"
                );
        List<String> semanticMappings = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedSemanticMappingReferences",
                        "semanticMappingReferences"
                );
        List<Map<String, Object>> resolvedStructuralMappings = referenceResolver.resolveRecords(
                STRUCTURAL_MAPPING_ROOT,
                tenantId,
                structuralMappings,
                "publicationMappingId",
                "publicationBatchId",
                "sourceMutationReferences"
        );
        List<Map<String, Object>> resolvedSemanticMappings = referenceResolver.resolveRecords(
                SEMANTIC_MAPPING_ROOT,
                tenantId,
                semanticMappings,
                "publicationMappingId",
                "mappingReference",
                "semanticMutationReferences"
        );
        List<String> rollbackAnchorReferences = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedRollbackAnchorReferences",
                        "rollbackAnchorReferences"
                );
        List<String> auditLinks = new ArrayList<>();
        for (Map<String, Object> structuralMapping : resolvedStructuralMappings) {
            auditLinks.addAll(referenceResolver.extractStringList(structuralMapping, "resolvedAuditReferences", "auditReferences"));
        }
        for (Map<String, Object> semanticMapping : resolvedSemanticMappings) {
            auditLinks.addAll(referenceResolver.extractStringList(semanticMapping, "resolvedAuditReferences", "auditReferences"));
        }

        boolean eligible = transaction != null
                && "COMPATIBLE".equals(String.valueOf(tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE")));
        List<String> appliedMutations = new ArrayList<>();
        List<String> touchedSourceArtifacts = new ArrayList<>();
        List<String> skippedActions = new ArrayList<>();
        List<String> reviewRequiredActions = new ArrayList<>();
        String mutationResultStatus;

        if (eligible) {
            canonicalSourceArtifactStore.applyMutation(
                    tenantId,
                    request.getTransactionReference().trim(),
                    request.getMutationReference().trim(),
                    structuralMappings,
                    semanticMappings
            );
            appliedMutations.add("canonical-source-registry-updated");
            touchedSourceArtifacts.add(canonicalSourceArtifactStore.registryPath());
            mutationResultStatus = structuralMappings.isEmpty() && semanticMappings.isEmpty()
                    ? "PARTIALLY_APPLIED_REVIEW_REQUIRED"
                    : "APPLIED";
            if (structuralMappings.isEmpty()) {
                reviewRequiredActions.add("review missing structural mapping coverage");
            }
            if (semanticMappings.isEmpty()) {
                reviewRequiredActions.add("review missing semantic mapping coverage");
            }
        } else {
            skippedActions.add("canonical-source-registry-update");
            reviewRequiredActions.add("resolve publication transaction before canonical source mutation");
            reviewRequiredActions.addAll(referenceResolver.extractStringList(tenantAssessment, "rejectedReferenceReasons"));
            mutationResultStatus = "REVIEW_REQUIRED_NO_ELIGIBLE_TRANSACTION";
        }

        skippedActions.add("downstream projection regeneration");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("canonicalSourceMutationExecutionId", mutationExecutionId);
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("mutationReference", request.getMutationReference().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("executionMode", request.getExecutionMode().trim());
        record.put("resolvedTransactionId", transaction == null
                ? ""
                : referenceResolver.extractFirstString(transaction, "publicationTransactionId"));
        record.put("tenantCompatibilityStatus", tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE"));
        record.put("crossTenantViolationCount", tenantAssessment.getOrDefault("crossTenantViolationCount", 0));
        record.put("tenantIsolationStatus", tenantAssessment.getOrDefault("tenantIsolationStatus", "TENANT_SCOPED"));
        record.put("rejectedReferenceReasons", tenantAssessment.getOrDefault("rejectedReferenceReasons", List.of()));
        record.put("mutationEligibilityStatus", eligible ? "ELIGIBLE" : "REVIEW_REQUIRED");
        record.put("touchedSourceArtifacts", touchedSourceArtifacts);
        record.put("appliedMutations", appliedMutations);
        record.put("resolvedStructuralMappingReferences", structuralMappings);
        record.put("resolvedSemanticMappingReferences", semanticMappings);
        record.put("rollbackAnchorReferences", rollbackAnchorReferences);
        record.put("auditLinks", auditLinks);
        record.put("reviewRequiredActions", reviewRequiredActions);
        record.put("skippedActions", skippedActions);
        record.put("mutationResultStatus", mutationResultStatus);
        record.put("executedAt", executedAt);
        record.put("mode", mode);

        persistRecord(mutationExecutionId, record);
        return record;
    }

    private void validate(CanonicalSourceMutationExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTransactionReference())) {
            throw new IllegalArgumentException("transactionReference is required.");
        }
        if (isBlank(request.getMutationReference())) {
            throw new IllegalArgumentException("mutationReference is required.");
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
            throw new IllegalStateException("Failed to load canonical source mutation rules.", e);
        }
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void persistRecord(String mutationExecutionId, Map<String, Object> record) {
        try {
            java.nio.file.Files.createDirectories(MUTATION_ROOT);
            Path output = MUTATION_ROOT.resolve(mutationExecutionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist canonical source mutation record.", e);
        }
    }
}
