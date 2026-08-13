package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.PublicationTransactionRecordRequest;
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
public class PublicationTransactionRecordService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-publication-transaction/publication-transaction-rules.json";

    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");
    private static final Path STRUCTURAL_MAPPING_ROOT =
            Paths.get("runtime-data", "structural-publication-mappings");
    private static final Path SEMANTIC_MAPPING_ROOT =
            Paths.get("runtime-data", "semantic-publication-mappings");
    private static final Path APPROVAL_ROOT =
            Paths.get("runtime-data", "source-mutation-approvals");
    private static final Path ROLLBACK_ANCHOR_ROOT =
            Paths.get("runtime-data", "source-mutation-rollback-anchors");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;

    public PublicationTransactionRecordService(
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
        response.put("storagePath", TRANSACTION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "publication-transaction-record-v1"));
        response.put("supportedInputs", rules.getOrDefault("supportedInputs", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(TRANSACTION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> recordTransaction(PublicationTransactionRecordRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String publicationTransactionId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String transactionStatus = firstListValue(rules.get("supportedStatuses"), "RECORDED_FOR_REVIEW");
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "publication-transaction-record-v1"));
        List<String> claimedStructuralMappingReferences = normalizeList(request.getStructuralMappingReferences());
        List<String> claimedSemanticMappingReferences = normalizeList(request.getSemanticMappingReferences());
        List<String> claimedApprovalReferences = normalizeList(request.getApprovalReferences());
        List<String> claimedRollbackAnchorReferences = normalizeList(request.getRollbackAnchorReferences());
        List<Map<String, Object>> resolvedStructuralMappings = referenceResolver.resolveRecords(
                STRUCTURAL_MAPPING_ROOT,
                tenantId,
                claimedStructuralMappingReferences,
                "publicationMappingId",
                "publicationBatchId",
                "sourceMutationReferences"
        );
        List<Map<String, Object>> resolvedSemanticMappings = referenceResolver.resolveRecords(
                SEMANTIC_MAPPING_ROOT,
                tenantId,
                claimedSemanticMappingReferences,
                "publicationMappingId",
                "mappingReference",
                "semanticMutationReferences"
        );
        List<Map<String, Object>> resolvedApprovals = referenceResolver.resolveRecords(
                APPROVAL_ROOT,
                tenantId,
                claimedApprovalReferences,
                "approvalId",
                "mutationReference"
        );
        List<Map<String, Object>> resolvedRollbackAnchors = referenceResolver.resolveRecords(
                ROLLBACK_ANCHOR_ROOT,
                tenantId,
                claimedRollbackAnchorReferences,
                "rollbackAnchorId",
                "mutationReference"
        );
        List<String> resolvedStructuralMappingReferences = referenceResolver.extractCanonicalReferences(
                resolvedStructuralMappings,
                "publicationMappingId"
        );
        List<String> resolvedSemanticMappingReferences = referenceResolver.extractCanonicalReferences(
                resolvedSemanticMappings,
                "publicationMappingId"
        );
        List<String> resolvedApprovalReferences = referenceResolver.extractCanonicalReferences(resolvedApprovals, "approvalId");
        List<String> resolvedRollbackAnchorReferences = referenceResolver.extractCanonicalReferences(
                resolvedRollbackAnchors,
                "rollbackAnchorId"
        );
        List<String> unresolvedStructuralMappingReferences = referenceResolver.unresolvedReferences(
                claimedStructuralMappingReferences,
                resolvedStructuralMappings,
                "publicationMappingId",
                "publicationBatchId",
                "sourceMutationReferences"
        );
        List<String> unresolvedSemanticMappingReferences = referenceResolver.unresolvedReferences(
                claimedSemanticMappingReferences,
                resolvedSemanticMappings,
                "publicationMappingId",
                "mappingReference",
                "semanticMutationReferences"
        );
        List<String> unresolvedApprovalReferences = referenceResolver.unresolvedReferences(
                claimedApprovalReferences,
                resolvedApprovals,
                "approvalId",
                "mutationReference"
        );
        List<String> unresolvedRollbackAnchorReferences = referenceResolver.unresolvedReferences(
                claimedRollbackAnchorReferences,
                resolvedRollbackAnchors,
                "rollbackAnchorId",
                "mutationReference"
        );
        List<String> unresolvedReferences = new ArrayList<>();
        unresolvedReferences.addAll(unresolvedStructuralMappingReferences);
        unresolvedReferences.addAll(unresolvedSemanticMappingReferences);
        unresolvedReferences.addAll(unresolvedApprovalReferences);
        unresolvedReferences.addAll(unresolvedRollbackAnchorReferences);
        String integrityStatus = unresolvedReferences.isEmpty() ? "RESOLVED" : "PARTIALLY_RESOLVED";

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("publicationTransactionId", publicationTransactionId);
        record.put("chainReference", request.getTransactionReference().trim());
        record.put("transactionScope", request.getTransactionScope().trim());
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("draftReference", request.getDraftReference().trim());
        record.put("structuralMappingReferences", claimedStructuralMappingReferences);
        record.put("semanticMappingReferences", claimedSemanticMappingReferences);
        record.put("approvalReferences", claimedApprovalReferences);
        record.put("rollbackAnchorReferences", claimedRollbackAnchorReferences);
        record.put("resolvedStructuralMappingReferences", resolvedStructuralMappingReferences);
        record.put("resolvedSemanticMappingReferences", resolvedSemanticMappingReferences);
        record.put("resolvedApprovalReferences", resolvedApprovalReferences);
        record.put("resolvedRollbackAnchorReferences", resolvedRollbackAnchorReferences);
        record.put("unresolvedStructuralMappingReferences", unresolvedStructuralMappingReferences);
        record.put("unresolvedSemanticMappingReferences", unresolvedSemanticMappingReferences);
        record.put("unresolvedApprovalReferences", unresolvedApprovalReferences);
        record.put("unresolvedRollbackAnchorReferences", unresolvedRollbackAnchorReferences);
        record.put("integrityStatus", integrityStatus);
        record.put("tenantCompatibilityStatus", "COMPATIBLE");
        record.put("integrityNotes", unresolvedReferences.isEmpty()
                ? "Publication transaction references resolved against real records."
                : "Publication transaction keeps unresolved references visible for review.");
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("transactionStatus", transactionStatus);
        record.put("recordedAt", recordedAt);
        record.put("mode", mode);

        persistRecord(publicationTransactionId, record);
        return record;
    }

    private void validate(PublicationTransactionRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTransactionScope())) {
            throw new IllegalArgumentException("transactionScope is required.");
        }
        if (isBlank(request.getTransactionReference())) {
            throw new IllegalArgumentException("transactionReference is required.");
        }
        if (isBlank(request.getDraftReference())) {
            throw new IllegalArgumentException("draftReference is required.");
        }
        if (request.getStructuralMappingReferences() == null || request.getStructuralMappingReferences().isEmpty()) {
            throw new IllegalArgumentException("structuralMappingReferences must contain at least one value.");
        }
        if (request.getSemanticMappingReferences() == null || request.getSemanticMappingReferences().isEmpty()) {
            throw new IllegalArgumentException("semanticMappingReferences must contain at least one value.");
        }
        if (request.getApprovalReferences() == null || request.getApprovalReferences().isEmpty()) {
            throw new IllegalArgumentException("approvalReferences must contain at least one value.");
        }
        if (request.getRollbackAnchorReferences() == null || request.getRollbackAnchorReferences().isEmpty()) {
            throw new IllegalArgumentException("rollbackAnchorReferences must contain at least one value.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load publication transaction rules.", e);
        }
    }

    private List<String> normalizeList(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized;
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

    private void persistRecord(String publicationTransactionId, Map<String, Object> record) {
        try {
            java.nio.file.Files.createDirectories(TRANSACTION_ROOT);
            Path output = TRANSACTION_ROOT.resolve(publicationTransactionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist publication transaction record.", e);
        }
    }
}
