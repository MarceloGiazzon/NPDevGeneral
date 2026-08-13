package com.finalexec.npdev.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.CrossTenantGovernanceRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CrossTenantGovernanceService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-cross-tenant-governance/cross-tenant-governance-rules.json";

    private static final Path GOVERNANCE_ROOT =
            Path.of("runtime-data", "cross-tenant-governance");
    private static final Path PARTITIONING_ROOT =
            Path.of("runtime-data", "tenant-storage-partitioning");
    private static final Path QUERY_ENFORCEMENT_ROOT =
            Path.of("runtime-data", "tenant-query-enforcement");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final TenantStoragePathResolver tenantStoragePathResolver;

    public CrossTenantGovernanceService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            TenantStoragePathResolver tenantStoragePathResolver
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.tenantStoragePathResolver = tenantStoragePathResolver;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Cross-Tenant Governance Control"));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", GOVERNANCE_ROOT.toString().replace("\\", "/"));
        response.put("partitionRoot", rules.getOrDefault("partitionRoot", "runtime-data/tenant-partitions"));
        response.put("queryEnforcementRoot", rules.getOrDefault("queryEnforcementRoot", "runtime-data/tenant-query-enforcement"));
        response.put("mode", rules.getOrDefault("mode", "cross-tenant-governance-v1"));
        response.put("governanceReality", rules.getOrDefault(
                "governanceReality",
                "explicit governance and audit of cross-tenant inspection, visibility, and override decisions"
        ));
        response.put("defaultCrossTenantDecision", rules.getOrDefault(
                "defaultCrossTenantDecision",
                "REJECT_REQUIRES_EXPLICIT_OVERRIDE"
        ));
        response.put("supportedScopes", rules.getOrDefault("supportedScopes", List.of()));
        response.put("allowedOverrideScopes", rules.getOrDefault("allowedOverrideScopes", List.of()));
        response.put("governedTargets", rules.getOrDefault("governedTargets", List.of()));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(GOVERNANCE_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> evaluate(CrossTenantGovernanceRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String governanceId = UUID.randomUUID().toString();
        String requestingTenantId = request.getRequestingTenantId().trim();
        String targetTenantId = request.getTargetTenantId().trim();
        String scope = request.getScope().trim();
        String evaluatedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "cross-tenant-governance-v1"));
        boolean crossTenantAttemptDetected = !requestingTenantId.equals(targetTenantId);

        List<Map<String, Object>> targets = normalizeTargets(rules.get("governedTargets"));
        Set<String> allowedCategories = categoriesForScope(scope);
        List<Map<String, Object>> evaluatedTargets = new ArrayList<>();
        int targetTenantEvidenceCount = 0;

        for (Map<String, Object> target : targets) {
            String category = stringValue(target.get("category"));
            if (!allowedCategories.contains(category)) {
                continue;
            }

            Map<String, Object> targetEvaluation = evaluateTarget(
                    requestingTenantId,
                    targetTenantId,
                    crossTenantAttemptDetected,
                    target
            );
            evaluatedTargets.add(targetEvaluation);
            targetTenantEvidenceCount += intValue(targetEvaluation.get("targetTenantRecordCount"));
        }

        int requestingPartitioningCount = countRecordsByTenant(PARTITIONING_ROOT, requestingTenantId);
        int targetPartitioningCount = countRecordsByTenant(PARTITIONING_ROOT, targetTenantId);
        int requestingQueryEnforcementCount = countRecordsByTenant(QUERY_ENFORCEMENT_ROOT, requestingTenantId);
        int targetQueryEnforcementCount = countRecordsByTenant(QUERY_ENFORCEMENT_ROOT, targetTenantId);

        Set<String> allowedOverrideScopes = normalizeStringSet(rules.get("allowedOverrideScopes"));
        boolean overrideEligible = isOverrideEligible(scope, crossTenantAttemptDetected, allowedOverrideScopes);

        List<String> decisionReasons = new ArrayList<>();
        decisionReasons.add("Step 110 requires cross-tenant actions to be detected and explicitly governed.");
        decisionReasons.add("Step 108 storage partitioning and Step 109 tenant-scoped query enforcement are treated as prerequisite controls.");
        if (crossTenantAttemptDetected) {
            decisionReasons.add("Cross-tenant request detected from '" + requestingTenantId + "' to '" + targetTenantId + "'.");
        } else {
            decisionReasons.add("Request stays within one tenant scope; cross-tenant override is not required.");
        }
        if (overrideEligible) {
            decisionReasons.add("Requested scope is in the controlled override allow-list and remains auditable.");
        } else if (crossTenantAttemptDetected) {
            decisionReasons.add("Requested scope is outside the controlled override allow-list.");
        }

        String governanceDecisionStatus;
        String governancePosture;
        String overrideDecisionStatus;
        if (!crossTenantAttemptDetected) {
            governanceDecisionStatus = "SAME_TENANT_ALLOWED";
            governancePosture = "NOT_CROSS_TENANT";
            overrideDecisionStatus = "NOT_REQUIRED";
        } else if (overrideEligible) {
            governanceDecisionStatus = "CROSS_TENANT_ALLOWED_WITH_AUDIT";
            governancePosture = "EXPLICIT_OVERRIDE";
            overrideDecisionStatus = "EXPLICIT_OVERRIDE_RECORDED";
        } else {
            governanceDecisionStatus = "CROSS_TENANT_REJECTED";
            governancePosture = "REJECTED";
            overrideDecisionStatus = "OVERRIDE_NOT_ALLOWED";
        }

        Map<String, Object> prerequisiteSignals = new LinkedHashMap<>();
        prerequisiteSignals.put("requestingTenantPartitionPresent", Files.exists(tenantStoragePathResolver.tenantRoot(requestingTenantId)));
        prerequisiteSignals.put("targetTenantPartitionPresent", Files.exists(tenantStoragePathResolver.tenantRoot(targetTenantId)));
        prerequisiteSignals.put("requestingTenantPartitioningCount", requestingPartitioningCount);
        prerequisiteSignals.put("targetTenantPartitioningCount", targetPartitioningCount);
        prerequisiteSignals.put("requestingTenantQueryEnforcementCount", requestingQueryEnforcementCount);
        prerequisiteSignals.put("targetTenantQueryEnforcementCount", targetQueryEnforcementCount);

        Map<String, Object> governanceSummary = new LinkedHashMap<>();
        governanceSummary.put("requestingTenantId", requestingTenantId);
        governanceSummary.put("targetTenantId", targetTenantId);
        governanceSummary.put("scope", scope);
        governanceSummary.put("crossTenantAttemptDetected", crossTenantAttemptDetected);
        governanceSummary.put("governanceDecisionStatus", governanceDecisionStatus);
        governanceSummary.put("governancePosture", governancePosture);
        governanceSummary.put("overrideDecisionStatus", overrideDecisionStatus);
        governanceSummary.put("evaluatedTargetCount", evaluatedTargets.size());
        governanceSummary.put("targetTenantEvidenceCount", targetTenantEvidenceCount);
        governanceSummary.put("auditVisibilityStatus", "AUDIT_VISIBLE");
        governanceSummary.put("decisionReasons", decisionReasons);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("crossTenantGovernanceId", governanceId);
        record.put("governanceReference", request.getGovernanceReference().trim());
        record.put("requestingTenantId", requestingTenantId);
        record.put("targetTenantId", targetTenantId);
        record.put("scope", scope);
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("governanceMode", request.getGovernanceMode().trim());
        record.put("evaluatedTargets", evaluatedTargets);
        record.put("prerequisiteSignals", prerequisiteSignals);
        record.put("governanceSummary", governanceSummary);
        record.put("evaluatedAt", evaluatedAt);
        record.put("mode", mode);
        record.put("status", "GOVERNANCE_RECORDED");

        persistRecord(governanceId, record);
        return record;
    }

    private Map<String, Object> evaluateTarget(
            String requestingTenantId,
            String targetTenantId,
            boolean crossTenantAttemptDetected,
            Map<String, Object> target
    ) {
        Path sourceRoot = Path.of(stringValue(target.get("path")));
        String targetName = stringValue(target.get("name"));
        String category = stringValue(target.get("category"));
        List<String> referenceFields = normalizeStringList(target.get("referenceFields"));

        Path requestingPartitionPath = tenantStoragePathResolver.targetRoot(requestingTenantId, category, targetName);
        Path targetPartitionPath = tenantStoragePathResolver.targetRoot(targetTenantId, category, targetName);
        boolean usePartitionEvidence = hasJsonRecords(targetPartitionPath) || hasJsonRecords(requestingPartitionPath);
        Path evaluationRoot = usePartitionEvidence ? targetPartitionPath : sourceRoot;
        String evaluationRootType = usePartitionEvidence ? "TENANT_PARTITION" : "SOURCE_ROOT";

        if (!Files.exists(evaluationRoot) || !Files.isDirectory(evaluationRoot)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("targetName", targetName);
            result.put("category", category);
            result.put("sourcePath", sourceRoot.toString().replace("\\", "/"));
            result.put("evaluationRoot", evaluationRoot.toString().replace("\\", "/"));
            result.put("evaluationRootType", evaluationRootType);
            result.put("requestingTenantRecordCount", 0);
            result.put("targetTenantRecordCount", 0);
            result.put("otherTenantRecordCount", 0);
            result.put("targetTenantReferenceSample", List.of());
            result.put("requestingPartitionPath", requestingPartitionPath.toString().replace("\\", "/"));
            result.put("requestingPartitionExists", Files.exists(requestingPartitionPath));
            result.put("targetPartitionPath", targetPartitionPath.toString().replace("\\", "/"));
            result.put("targetPartitionExists", Files.exists(targetPartitionPath));
            result.put("crossTenantVisibilityRequested", crossTenantAttemptDetected);
            result.put("status", "SOURCE_MISSING");
            return result;
        }

        int requestingPartitionEvidenceCount = countJsonRecords(requestingPartitionPath);
        int targetPartitionEvidenceCount = countJsonRecords(targetPartitionPath);
        List<Map<String, Object>> records = referenceResolver.readRecords(evaluationRoot);
        List<Map<String, Object>> requestingTenantRecords = new ArrayList<>();
        List<Map<String, Object>> targetTenantRecords = new ArrayList<>();
        int otherTenantRecordCount = 0;

        for (Map<String, Object> record : records) {
            if (usePartitionEvidence) {
                targetTenantRecords.add(record);
            } else {
                String recordTenantId = referenceResolver.extractFirstString(record, "tenantId");
                if (requestingTenantId.equals(recordTenantId)) {
                    requestingTenantRecords.add(record);
                } else if (targetTenantId.equals(recordTenantId)) {
                    targetTenantRecords.add(record);
                } else if (!recordTenantId.isBlank()) {
                    otherTenantRecordCount++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetName", targetName);
        result.put("category", category);
        result.put("sourcePath", sourceRoot.toString().replace("\\", "/"));
        result.put("evaluationRoot", evaluationRoot.toString().replace("\\", "/"));
        result.put("evaluationRootType", evaluationRootType);
        result.put("requestingTenantRecordCount", usePartitionEvidence ? requestingPartitionEvidenceCount : requestingTenantRecords.size());
        result.put("targetTenantRecordCount", targetTenantRecords.size());
        result.put("otherTenantRecordCount", otherTenantRecordCount);
        result.put("targetTenantReferenceSample", collectReferenceSample(targetTenantRecords, referenceFields, 5));
        result.put("requestingPartitionPath", requestingPartitionPath.toString().replace("\\", "/"));
        result.put("requestingPartitionExists", Files.exists(requestingPartitionPath));
        result.put("requestingPartitionEvidenceCount", requestingPartitionEvidenceCount);
        result.put("targetPartitionPath", targetPartitionPath.toString().replace("\\", "/"));
        result.put("targetPartitionExists", Files.exists(targetPartitionPath));
        result.put("targetPartitionEvidenceCount", targetPartitionEvidenceCount);
        result.put("crossTenantVisibilityRequested", crossTenantAttemptDetected);
        result.put("status", targetTenantRecords.isEmpty() ? "NO_TARGET_TENANT_EVIDENCE" : (usePartitionEvidence ? "TARGET_TENANT_PARTITION_EVIDENCE_VISIBLE" : "TARGET_TENANT_VISIBLE_UNDER_GOVERNANCE"));
        return result;
    }

    private int countRecordsByTenant(Path root, String tenantId) {
        int count = 0;
        for (Map<String, Object> item : referenceResolver.readRecords(root)) {
            String recordTenantId = referenceResolver.extractFirstString(item, "tenantId");
            if (tenantId.equals(recordTenantId)) {
                count++;
            }
        }
        return count;
    }

    private int countJsonRecords(Path root) {
        try {
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                return 0;
            }
            try (var stream = Files.list(root)) {
                return (int) stream.filter(path -> path.getFileName().toString().endsWith(".json")).count();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasJsonRecords(Path root) {
        return countJsonRecords(root) > 0;
    }

    private boolean isOverrideEligible(String scope, boolean crossTenantAttemptDetected, Set<String> allowedOverrideScopes) {
        if (!crossTenantAttemptDetected) {
            return false;
        }
        if (allowedOverrideScopes.contains(scope)) {
            return true;
        }
        return scope.toLowerCase().contains("inspection");
    }

    private List<String> collectReferenceSample(
            List<Map<String, Object>> records,
            List<String> referenceFields,
            int limit
    ) {
        Set<String> references = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            String reference = referenceResolver.extractFirstString(record, referenceFields.toArray(new String[0]));
            if (!reference.isBlank()) {
                references.add(reference);
            }
            if (references.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(references);
    }

    private Set<String> categoriesForScope(String scope) {
        Set<String> categories = new LinkedHashSet<>();
        String normalized = scope.toLowerCase();
        if (normalized.contains("publication")) {
            categories.add("publication");
        }
        if (normalized.contains("rollback") || normalized.contains("recovery")) {
            categories.add("rollback-recovery");
        }
        if (normalized.contains("preview")) {
            categories.add("preview");
        }
        if (normalized.contains("explainability")) {
            categories.add("explainability");
        }
        if (normalized.contains("proof") || normalized.contains("scenario")) {
            categories.add("proof");
        }
        if (categories.isEmpty()) {
            categories.add("publication");
            categories.add("rollback-recovery");
            categories.add("preview");
            categories.add("explainability");
            categories.add("proof");
        }
        return categories;
    }

    private List<Map<String, Object>> normalizeTargets(Object raw) {
        List<Map<String, Object>> targets = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    targets.add(normalized);
                }
            }
        }
        return targets;
    }

    private List<String> normalizeStringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                String value = stringValue(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private Set<String> normalizeStringSet(Object raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                String value = stringValue(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private void validate(CrossTenantGovernanceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getGovernanceReference())) {
            throw new IllegalArgumentException("governanceReference is required.");
        }
        if (isBlank(request.getRequestingTenantId())) {
            throw new IllegalArgumentException("requestingTenantId is required.");
        }
        if (isBlank(request.getTargetTenantId())) {
            throw new IllegalArgumentException("targetTenantId is required.");
        }
        if (isBlank(request.getScope())) {
            throw new IllegalArgumentException("scope is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
        if (isBlank(request.getGovernanceMode())) {
            throw new IllegalArgumentException("governanceMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load cross-tenant governance rules.", e);
        }
    }

    private void persistRecord(String governanceId, Map<String, Object> record) {
        try {
            Files.createDirectories(GOVERNANCE_ROOT);
            Path output = GOVERNANCE_ROOT.resolve(governanceId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist cross-tenant governance record.", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
