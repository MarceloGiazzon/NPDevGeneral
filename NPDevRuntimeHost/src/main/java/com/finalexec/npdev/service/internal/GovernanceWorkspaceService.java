package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.internal.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.GovernanceWorkspaceDecisionRequest;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GovernanceWorkspaceService {

    private static final Path SOURCE_MUTATION_APPROVAL_ROOT = Path.of("runtime-data", "source-mutation-approvals");
    private static final Path SEMANTIC_GOVERNANCE_ROOT = Path.of("runtime-data", "semantic-governance");
    private static final Path CROSS_TENANT_GOVERNANCE_ROOT = Path.of("runtime-data", "cross-tenant-governance");
    private static final Path TENANT_NATIVE_GOVERNANCE_ROOT = Path.of("runtime-data", "tenant-native-governance-records");
    private static final Path WORKSPACE_DECISION_ROOT = Path.of("runtime-data", "governance-workspace-decisions");

    private static final String SOURCE_MUTATION_RULES = "npdev-source-mutation-approval/source-mutation-approval-rules.json";
    private static final String CROSS_TENANT_RULES = "npdev-cross-tenant-governance/cross-tenant-governance-rules.json";
    private static final String TENANT_NATIVE_RULES = "npdev-tenant-native-governance/tenant-native-governance-rules.json";

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;

    public GovernanceWorkspaceService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
    }

    public Map<String, Object> pending() {
        Map<String, Map<String, Object>> latestDecisions = latestDecisionsByTarget();
        List<Map<String, Object>> items = new ArrayList<>();

        items.addAll(sourceMutationPending(latestDecisions));
        items.addAll(semanticGovernancePending(latestDecisions));
        items.addAll(tenantNativePending(latestDecisions));

        items.sort(Comparator.comparing(this::sortTimestamp, Comparator.reverseOrder()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Governance Workspace");
        response.put("pendingCount", items.size());
        response.put("workspaceDecisionCount", latestDecisions.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(historyFromRoot(WORKSPACE_DECISION_ROOT, "workspace-decision"));
        items.addAll(historyFromRoot(SOURCE_MUTATION_APPROVAL_ROOT, "source-mutation-approval"));
        items.addAll(historyFromRoot(SEMANTIC_GOVERNANCE_ROOT, "semantic-governance"));
        items.addAll(historyFromRoot(CROSS_TENANT_GOVERNANCE_ROOT, "cross-tenant-governance"));
        items.addAll(historyFromRoot(TENANT_NATIVE_GOVERNANCE_ROOT, "tenant-native-governance"));
        items.sort(Comparator.comparing(this::sortTimestamp, Comparator.reverseOrder()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Governance Workspace");
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> policies() {
        List<Map<String, Object>> policies = new ArrayList<>();

        Map<String, Object> sourceRules = loadJsonResource(SOURCE_MUTATION_RULES);
        Map<String, Object> sourcePolicy = new LinkedHashMap<>();
        sourcePolicy.put("policyCode", "source-mutation-approval");
        sourcePolicy.put("title", "Source Mutation Approval Policy");
        sourcePolicy.put("domain", "authoring-governance");
        sourcePolicy.put("rulesPath", SOURCE_MUTATION_RULES);
        sourcePolicy.put("states", normalizeStrings(sourceRules.get("defaultApprovalStates")));
        sourcePolicy.put("details", mapList(sourceRules.get("scopePolicies")));
        policies.add(sourcePolicy);

        Map<String, Object> semanticPolicy = new LinkedHashMap<>();
        semanticPolicy.put("policyCode", "semantic-governance-lifecycle");
        semanticPolicy.put("title", "Semantic Governance Lifecycle");
        semanticPolicy.put("domain", "draft-review-publish");
        semanticPolicy.put("rulesPath", "runtime-data/semantic-governance");
        semanticPolicy.put("states", List.of("DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED"));
        semanticPolicy.put("details", List.of(
                Map.of("transition", "DRAFT -> IN_REVIEW", "meaning", "ready for governed review"),
                Map.of("transition", "IN_REVIEW -> APPROVED", "meaning", "approved change"),
                Map.of("transition", "APPROVED -> PUBLISHED", "meaning", "governed publication")
        ));
        policies.add(semanticPolicy);

        Map<String, Object> crossRules = loadJsonResource(CROSS_TENANT_RULES);
        Map<String, Object> crossTenantPolicy = new LinkedHashMap<>();
        crossTenantPolicy.put("policyCode", "cross-tenant-governance");
        crossTenantPolicy.put("title", "Cross-Tenant Governance Policy");
        crossTenantPolicy.put("domain", "tenant-isolation");
        crossTenantPolicy.put("rulesPath", CROSS_TENANT_RULES);
        crossTenantPolicy.put("states", normalizeStrings(crossRules.get("supportedScopes")));
        crossTenantPolicy.put("details", List.of(
                Map.of("label", "allowedOverrideScopes", "values", normalizeStrings(crossRules.get("allowedOverrideScopes"))),
                Map.of("label", "governedTargets", "values", extractTargetNames(crossRules.get("governedTargets")))
        ));
        policies.add(crossTenantPolicy);

        Map<String, Object> tenantRules = loadJsonResource(TENANT_NATIVE_RULES);
        Map<String, Object> tenantNativePolicy = new LinkedHashMap<>();
        tenantNativePolicy.put("policyCode", "tenant-native-governance");
        tenantNativePolicy.put("title", "Tenant-Native Governance Policy");
        tenantNativePolicy.put("domain", "tenant-administration");
        tenantNativePolicy.put("rulesPath", TENANT_NATIVE_RULES);
        tenantNativePolicy.put("states", extractTenantActions(tenantRules.get("supportedTenantActions")));
        tenantNativePolicy.put("details", mapList(tenantRules.get("supportedTenantActions")));
        policies.add(tenantNativePolicy);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Governance Workspace");
        response.put("policyCount", policies.size());
        response.put("policies", policies);
        return response;
    }

    public Map<String, Object> recordDecision(GovernanceWorkspaceDecisionRequest request) {
        validateDecisionRequest(request);

        String decidedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String workspaceDecisionId = UUID.randomUUID().toString();
        List<String> relatedArtifactReferences = normalizeStrings(request.getRelatedArtifactReferences());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("workspaceDecisionId", workspaceDecisionId);
        record.put("targetType", request.getTargetType().trim());
        record.put("targetReference", request.getTargetReference().trim());
        record.put("decision", request.getDecision().trim());
        record.put("decidedBy", request.getDecidedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("policyCode", stringValue(request.getPolicyCode()));
        record.put("relatedArtifactReferences", relatedArtifactReferences);
        record.put("decidedAt", decidedAt);
        record.put("status", "DECISION_RECORDED");

        persistDecision(workspaceDecisionId, record);

        Map<String, Object> response = new LinkedHashMap<>(record);
        response.put("message", "Governance workspace decision recorded.");
        return response;
    }

    private List<Map<String, Object>> sourceMutationPending(Map<String, Map<String, Object>> latestDecisions) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> record : referenceResolver.readRecords(SOURCE_MUTATION_APPROVAL_ROOT)) {
            String targetReference = firstNonBlank(record.get("approvalId"), record.get("mutationReference"));
            if (targetReference.isBlank()) {
                continue;
            }
            String pendingState = firstNonBlank(record.get("decision"), record.get("defaultApprovalState"));
            if (!"REVIEW_REQUIRED".equalsIgnoreCase(pendingState)) {
                continue;
            }
            if (isResolved(latestDecisions, "source-mutation-approval", targetReference)) {
                continue;
            }

            Map<String, Object> item = basePendingItem("source-mutation-approval", targetReference);
            item.put("title", "Source mutation approval review");
            item.put("decisionStatus", "PENDING_REVIEW");
            item.put("summary", "Mutation scope '" + stringValue(record.get("mutationScope")) + "' requires governance review.");
            item.put("rationale", stringValue(record.get("rationale")));
            item.put("requestedBy", stringValue(record.get("requestedBy")));
            item.put("recordedAt", stringValue(record.get("recordedAt")));
            item.put("policyContext", List.of(
                    "defaultApprovalState=" + stringValue(record.get("defaultApprovalState")),
                    "mutationScope=" + stringValue(record.get("mutationScope"))
            ));
            item.put("relatedArtifacts", List.of(
                    stringValue(record.get("mutationReference")),
                    stringValue(record.get("tenantId"))
            ));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> semanticGovernancePending(Map<String, Map<String, Object>> latestDecisions) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> record : referenceResolver.readRecords(SEMANTIC_GOVERNANCE_ROOT)) {
            String targetReference = stringValue(record.get("governanceId"));
            if (targetReference.isBlank()) {
                continue;
            }
            if (!"IN_REVIEW".equalsIgnoreCase(stringValue(record.get("status")))) {
                continue;
            }
            if (isResolved(latestDecisions, "semantic-governance", targetReference)) {
                continue;
            }

            Map<String, Object> item = basePendingItem("semantic-governance", targetReference);
            item.put("title", firstNonBlank(record.get("title"), "Semantic governance review"));
            item.put("decisionStatus", "PENDING_REVIEW");
            item.put("summary", "Governed semantic change is waiting in review.");
            item.put("rationale", firstNonBlank(record.get("comment"), ""));
            item.put("requestedBy", firstNonBlank(record.get("sourceType"), ""));
            item.put("recordedAt", firstNonBlank(record.get("updatedAt"), record.get("createdAt")));
            item.put("policyContext", List.of(
                    "requestType=" + stringValue(record.get("requestType")),
                    "sourceType=" + stringValue(record.get("sourceType")),
                    "status=" + stringValue(record.get("status"))
            ));
            item.put("relatedArtifacts", List.of(stringValue(record.get("sourceRequestId"))));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> tenantNativePending(Map<String, Map<String, Object>> latestDecisions) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> record : referenceResolver.readRecords(TENANT_NATIVE_GOVERNANCE_ROOT)) {
            String targetReference = stringValue(record.get("tenantGovernanceRecordId"));
            if (targetReference.isBlank()) {
                continue;
            }
            if (!"REVIEW_REQUIRED".equalsIgnoreCase(stringValue(record.get("outcome")))) {
                continue;
            }
            if (isResolved(latestDecisions, "tenant-native-governance", targetReference)) {
                continue;
            }

            Map<String, Object> item = basePendingItem("tenant-native-governance", targetReference);
            item.put("title", "Tenant-native governance review");
            item.put("decisionStatus", "PENDING_REVIEW");
            item.put("summary", "Tenant action '" + stringValue(record.get("tenantAction")) + "' requires governance review.");
            item.put("rationale", stringValue(record.get("rationale")));
            item.put("requestedBy", stringValue(record.get("requestedBy")));
            item.put("recordedAt", stringValue(record.get("recordedAt")));
            item.put("policyContext", List.of(
                    "tenantAction=" + stringValue(record.get("tenantAction")),
                    "targetScope=" + stringValue(record.get("targetScope")),
                    "authorityMeaning=" + stringValue(record.get("authorityMeaning"))
            ));
            item.put("relatedArtifacts", List.of(
                    stringValue(record.get("tenantId")),
                    stringValue(record.get("targetScope"))
            ));
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> basePendingItem(String targetType, String targetReference) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("targetType", targetType);
        item.put("targetReference", targetReference);
        return item;
    }

    private List<Map<String, Object>> historyFromRoot(Path root, String eventType) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> record : referenceResolver.readRecords(root)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventType", eventType);
            item.put("reference", firstNonBlank(
                    record.get("workspaceDecisionId"),
                    record.get("approvalId"),
                    record.get("governanceId"),
                    record.get("crossTenantGovernanceId"),
                    record.get("tenantGovernanceRecordId"),
                    record.get("governanceReference"),
                    record.get("mutationReference")
            ));
            item.put("status", firstNonBlank(
                    record.get("decision"),
                    record.get("status"),
                    nestedValue(record, "governanceSummary", "governanceDecisionStatus"),
                    record.get("outcome")
            ));
            item.put("title", historyTitle(eventType, record));
            item.put("at", historyTimestamp(record));
            item.put("actor", firstNonBlank(record.get("decidedBy"), record.get("requestedBy"), record.get("composedBy")));
            item.put("rationale", firstNonBlank(record.get("rationale"), record.get("comment"), ""));
            item.put("relatedArtifacts", collectRelatedArtifacts(record));
            items.add(item);
        }
        return items;
    }

    private String historyTitle(String eventType, Map<String, Object> record) {
        return switch (eventType) {
            case "workspace-decision" -> "Workspace decision for " + stringValue(record.get("targetType"));
            case "source-mutation-approval" -> "Source mutation approval";
            case "semantic-governance" -> firstNonBlank(record.get("title"), "Semantic governance");
            case "cross-tenant-governance" -> "Cross-tenant governance";
            case "tenant-native-governance" -> "Tenant-native governance";
            default -> eventType;
        };
    }

    private List<String> collectRelatedArtifacts(Map<String, Object> record) {
        Set<String> refs = new LinkedHashSet<>();
        refs.addAll(normalizeStrings(record.get("relatedArtifactReferences")));
        addIfPresent(refs, record.get("mutationReference"));
        addIfPresent(refs, record.get("sourceRequestId"));
        addIfPresent(refs, record.get("governanceReference"));
        addIfPresent(refs, record.get("tenantId"));
        addIfPresent(refs, record.get("targetScope"));
        return new ArrayList<>(refs);
    }

    private String historyTimestamp(Map<String, Object> record) {
        return firstNonBlank(
                record.get("decidedAt"),
                record.get("updatedAt"),
                record.get("recordedAt"),
                record.get("evaluatedAt"),
                record.get("createdAt")
        );
    }

    private Map<String, Map<String, Object>> latestDecisionsByTarget() {
        Map<String, Map<String, Object>> decisions = new LinkedHashMap<>();
        for (Map<String, Object> record : referenceResolver.readRecords(WORKSPACE_DECISION_ROOT)) {
            String targetType = stringValue(record.get("targetType"));
            String targetReference = stringValue(record.get("targetReference"));
            if (targetType.isBlank() || targetReference.isBlank()) {
                continue;
            }
            decisions.putIfAbsent(targetKey(targetType, targetReference), record);
        }
        return decisions;
    }

    private boolean isResolved(Map<String, Map<String, Object>> latestDecisions, String targetType, String targetReference) {
        Map<String, Object> record = latestDecisions.get(targetKey(targetType, targetReference));
        if (record == null) {
            return false;
        }
        String decision = stringValue(record.get("decision"));
        return "APPROVE".equalsIgnoreCase(decision) || "REJECT".equalsIgnoreCase(decision);
    }

    private String targetKey(String targetType, String targetReference) {
        return targetType.trim() + "::" + targetReference.trim();
    }

    private void validateDecisionRequest(GovernanceWorkspaceDecisionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (stringValue(request.getTargetType()).isBlank()) {
            throw new IllegalArgumentException("targetType is required.");
        }
        if (stringValue(request.getTargetReference()).isBlank()) {
            throw new IllegalArgumentException("targetReference is required.");
        }
        if (stringValue(request.getDecision()).isBlank()) {
            throw new IllegalArgumentException("decision is required.");
        }
        if (!List.of("APPROVE", "REJECT").contains(request.getDecision().trim())) {
            throw new IllegalArgumentException("decision must be APPROVE or REJECT.");
        }
        if (stringValue(request.getDecidedBy()).isBlank()) {
            throw new IllegalArgumentException("decidedBy is required.");
        }
        if (stringValue(request.getRationale()).isBlank()) {
            throw new IllegalArgumentException("rationale is required.");
        }
    }

    private void persistDecision(String workspaceDecisionId, Map<String, Object> record) {
        try {
            Files.createDirectories(WORKSPACE_DECISION_ROOT);
            Path output = WORKSPACE_DECISION_ROOT.resolve(workspaceDecisionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist governance workspace decision.", e);
        }
    }

    private Map<String, Object> loadJsonResource(String path) {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load governance resource: " + path, e);
        }
    }

    private List<String> extractTenantActions(Object raw) {
        List<String> actions = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            addIfPresent(actions, item.get("tenantAction"));
        }
        return actions;
    }

    private List<String> extractTargetNames(Object raw) {
        List<String> targets = new ArrayList<>();
        for (Map<String, Object> item : mapList(raw)) {
            addIfPresent(targets, item.get("name"));
        }
        return targets;
    }

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    rawMap.forEach((key, itemValue) -> normalized.put(String.valueOf(key), itemValue));
                    items.add(normalized);
                }
            }
        }
        return items;
    }

    private List<String> normalizeStrings(Object value) {
        Set<String> items = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addIfPresent(items, item);
            }
        } else {
            addIfPresent(items, value);
        }
        return new ArrayList<>(items);
    }

    private void addIfPresent(Collection<String> items, Object value) {
        String normalized = stringValue(value);
        if (!normalized.isBlank()) {
            items.add(normalized);
        }
    }

    private String sortTimestamp(Map<String, Object> item) {
        return firstNonBlank(item.get("recordedAt"), item.get("at"), item.get("updatedAt"), item.get("createdAt"));
    }

    private String nestedValue(Map<String, Object> record, String nestedKey, String field) {
        Object nested = record.get(nestedKey);
        if (nested instanceof Map<?, ?> rawMap) {
            Object value = rawMap.get(field);
            return stringValue(value);
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
