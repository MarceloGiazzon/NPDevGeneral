package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.StructuralPublicationMappingRequest;
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
public class StructuralPublicationMappingService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-structural-publication-mapping/structural-publication-mapping-rules.json";

    private static final Path MAPPING_ROOT =
            Paths.get("runtime-data", "structural-publication-mappings");

    private static final Path APPROVAL_ROOT =
            Paths.get("runtime-data", "source-mutation-approvals");
    private static final Path AUDIT_ROOT =
            Paths.get("runtime-data", "source-mutation-audit-records");
    private static final Path ROLLBACK_ANCHOR_ROOT =
            Paths.get("runtime-data", "source-mutation-rollback-anchors");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;

    public StructuralPublicationMappingService(
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
        response.put("storagePath", MAPPING_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "structural-publication-mapping-v1"));
        response.put("supportedInputs", rules.getOrDefault("supportedInputs", List.of()));
        response.put("supportedOutputs", rules.getOrDefault("supportedOutputs", List.of()));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(MAPPING_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> mapRequest(StructuralPublicationMappingRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String publicationMappingId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String mappingStatus = firstListValue(rules.get("mappingStatus"), "MAPPED_FOR_REVIEW");
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "structural-publication-mapping-v1"));

        List<String> sourceMutationReferences = normalizeList(request.getSourceMutationReferences());
        List<String> draftReferences = normalizeList(request.getDraftReferences());
        List<String> includedStructuralScopes = normalizeList(request.getIncludedStructuralScopes());
        List<String> includedConcepts = normalizeList(request.getIncludedConcepts());
        List<Map<String, Object>> resolvedApprovals = referenceResolver.resolveRecords(
                APPROVAL_ROOT,
                tenantId,
                sourceMutationReferences,
                "approvalId",
                "mutationReference"
        );
        List<Map<String, Object>> resolvedAudits = referenceResolver.resolveRecords(
                AUDIT_ROOT,
                tenantId,
                sourceMutationReferences,
                "auditId",
                "mutationReference"
        );
        List<Map<String, Object>> resolvedRollbackAnchors = referenceResolver.resolveRecords(
                ROLLBACK_ANCHOR_ROOT,
                tenantId,
                sourceMutationReferences,
                "rollbackAnchorId",
                "mutationReference"
        );
        List<String> resolvedApprovalReferences = referenceResolver.extractCanonicalReferences(resolvedApprovals, "approvalId");
        List<String> resolvedAuditReferences = referenceResolver.extractCanonicalReferences(resolvedAudits, "auditId");
        List<String> resolvedRollbackAnchorReferences = referenceResolver.extractCanonicalReferences(
                resolvedRollbackAnchors,
                "rollbackAnchorId"
        );
        List<String> missingApprovalMutationReferences = referenceResolver.unresolvedReferences(
                sourceMutationReferences,
                resolvedApprovals,
                "mutationReference"
        );
        List<String> missingAuditMutationReferences = referenceResolver.unresolvedReferences(
                sourceMutationReferences,
                resolvedAudits,
                "mutationReference"
        );
        List<String> missingRollbackAnchorMutationReferences = referenceResolver.unresolvedReferences(
                sourceMutationReferences,
                resolvedRollbackAnchors,
                "mutationReference"
        );
        List<String> unresolvedReferences = new ArrayList<>();
        unresolvedReferences.addAll(missingApprovalMutationReferences);
        unresolvedReferences.addAll(missingAuditMutationReferences);
        unresolvedReferences.addAll(missingRollbackAnchorMutationReferences);
        String integrityStatus = referenceResolver.determineIntegrityStatus(sourceMutationReferences, unresolvedReferences);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("publicationMappingId", publicationMappingId);
        record.put("publicationBatchId", request.getPublicationBatchId().trim());
        record.put("chainReference", firstListValue(sourceMutationReferences, request.getPublicationBatchId().trim()));
        record.put("tenantId", tenantId);
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("sourceMutationReferences", sourceMutationReferences);
        record.put("draftReferences", draftReferences);
        record.put("includedStructuralScopes", includedStructuralScopes);
        record.put("includedConcepts", includedConcepts);
        record.put("approvalReferences", resolvedApprovalReferences);
        record.put("auditReferences", resolvedAuditReferences);
        record.put("rollbackAnchorReferences", resolvedRollbackAnchorReferences);
        record.put("resolvedApprovalReferences", resolvedApprovalReferences);
        record.put("resolvedAuditReferences", resolvedAuditReferences);
        record.put("resolvedRollbackAnchorReferences", resolvedRollbackAnchorReferences);
        record.put("missingApprovalMutationReferences", missingApprovalMutationReferences);
        record.put("missingAuditMutationReferences", missingAuditMutationReferences);
        record.put("missingRollbackAnchorMutationReferences", missingRollbackAnchorMutationReferences);
        record.put("integrityStatus", integrityStatus);
        record.put("integrityNotes", integrityStatus.equals("RESOLVED")
                ? "Structural mapping governance references resolved."
                : "Structural mapping governance references are partial or unresolved.");
        record.put("mappingStatus", mappingStatus);
        record.put("recordedAt", recordedAt);
        record.put("mode", mode);

        persistRecord(publicationMappingId, record);
        return record;
    }

    private void validate(StructuralPublicationMappingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getPublicationBatchId())) {
            throw new IllegalArgumentException("publicationBatchId is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
        if (request.getSourceMutationReferences() == null || request.getSourceMutationReferences().isEmpty()) {
            throw new IllegalArgumentException("sourceMutationReferences must contain at least one value.");
        }
        if (request.getIncludedStructuralScopes() == null || request.getIncludedStructuralScopes().isEmpty()) {
            throw new IllegalArgumentException("includedStructuralScopes must contain at least one value.");
        }
        if (request.getIncludedConcepts() == null || request.getIncludedConcepts().isEmpty()) {
            throw new IllegalArgumentException("includedConcepts must contain at least one value.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load structural publication mapping rules.", e);
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

    private void persistRecord(String publicationMappingId, Map<String, Object> record) {
        try {
            java.nio.file.Files.createDirectories(MAPPING_ROOT);
            Path output = MAPPING_ROOT.resolve(publicationMappingId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist structural publication mapping record.", e);
        }
    }
}
