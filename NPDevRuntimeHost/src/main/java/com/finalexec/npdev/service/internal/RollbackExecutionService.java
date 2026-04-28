package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.RollbackExecutionRequest;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RollbackExecutionService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-rollback-execution/rollback-execution-rules.json";

    private static final Path EXECUTION_ROOT =
            Paths.get("runtime-data", "rollback-executions");
    private static final Path ANCHOR_ROOT =
            Paths.get("runtime-data", "source-mutation-rollback-anchors");
    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");
    private static final Path REAL_PUBLICATION_ROOT =
            Paths.get("runtime-data", "real-publication-executions");
    private static final Path RECOVERY_ROOT =
            Paths.get("runtime-data", "publication-failure-recovery");
    private static final Path EXPLAINABILITY_ROOT =
            Paths.get("runtime-data", "publication-explainability");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final CanonicalSourceArtifactStore canonicalSourceArtifactStore;
    private final SourceMutationRegenerationArtifactStore regenerationArtifactStore;

    public RollbackExecutionService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            CanonicalSourceArtifactStore canonicalSourceArtifactStore,
            SourceMutationRegenerationArtifactStore regenerationArtifactStore
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.canonicalSourceArtifactStore = canonicalSourceArtifactStore;
        this.regenerationArtifactStore = regenerationArtifactStore;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Rollback Execution v1"));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "rollback-execution-v1"));
        response.put("rollbackReality", rules.getOrDefault(
                "rollbackReality",
                "first-pass governed rollback over supported scopes with explicit restored and skipped state"
        ));
        response.put("supportedRollbackModes", rules.getOrDefault("supportedRollbackModes", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        response.put("supportedOutcomes", rules.getOrDefault("supportedOutcomes", List.of()));
        response.put("supportedScopes", rules.getOrDefault("supportedScopes", List.of()));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(EXECUTION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> execute(RollbackExecutionRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String executionId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String executedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "rollback-execution-v1"));
        Map<String, Object> transactionTenantAssessment = referenceResolver.assessTenantIsolation(
                TRANSACTION_ROOT,
                tenantId,
                List.of(request.getTransactionReference().trim()),
                "publication transaction",
                "transactionReference",
                "publicationTransactionId"
        );
        Map<String, Object> anchorTenantAssessment = referenceResolver.assessTenantIsolation(
                ANCHOR_ROOT,
                tenantId,
                List.of(request.getAnchorReference().trim()),
                "rollback anchor",
                "rollbackAnchorId",
                "beforeStateReference",
                "mutationReference"
        );
        Map<String, Object> tenantAssessment = referenceResolver.mergeTenantAssessments(
                transactionTenantAssessment,
                anchorTenantAssessment
        );

        Map<String, Object> anchor = resolveAnchor(request, tenantId);
        Map<String, Object> transaction = referenceResolver.resolveSingle(
                TRANSACTION_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "publicationTransactionId"
        );
        Map<String, Object> publication = referenceResolver.resolveSingle(
                REAL_PUBLICATION_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "realPublicationExecutionId"
        );
        List<Map<String, Object>> recoveries = referenceResolver.findAllByReference(
                RECOVERY_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "publicationFailureRecoveryId"
        );
        List<Map<String, Object>> explainabilityBundles = referenceResolver.findAllByReference(
                EXPLAINABILITY_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "publicationExplainabilityBundleId"
        );

        String mutationReference = publication == null
                ? buildMutationReference(request.getTransactionReference())
                : referenceResolver.extractFirstString(publication, "mutationReference");
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
        List<String> auditLinks = publication == null
                ? List.of()
                : referenceResolver.extractStringList(publication, "auditLinks");
        List<String> explainabilityLinks = referenceResolver.extractCanonicalReferences(
                explainabilityBundles,
                "publicationExplainabilityBundleId"
        );
        List<String> linkedRecoveryReferences = referenceResolver.extractCanonicalReferences(
                recoveries,
                "publicationFailureRecoveryId"
        );

        List<String> restoredScopes = new ArrayList<>();
        List<String> skippedScopes = new ArrayList<>();
        List<String> reviewRequiredActions = new ArrayList<>();
        List<String> touchedArtifacts = new ArrayList<>();

        boolean anchorResolved = anchor != null;
        boolean publicationEligible = publication != null
                && "PUBLISHED".equals(referenceResolver.extractFirstString(publication, "publicationStatus"))
                        || publication != null
                        && "PUBLISHED_REVIEW_AWARE".equals(referenceResolver.extractFirstString(publication, "publicationStatus"));
        boolean rollbackEligible = anchorResolved
                && transaction != null
                && publicationEligible
                && "COMPATIBLE".equals(String.valueOf(tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE")))
                && "SAFE_APPLIED".equals(referenceResolver.extractFirstString(publication, "safeApplyStatus"));

        if (rollbackEligible) {
            Map<String, Object> canonicalResult = canonicalSourceArtifactStore.rollbackMutation(
                    tenantId,
                    request.getTransactionReference().trim(),
                    mutationReference,
                    structuralMappings,
                    semanticMappings
            );
            if (Boolean.TRUE.equals(canonicalResult.get("transactionRemoved"))
                    || Boolean.TRUE.equals(canonicalResult.get("mutationRemoved"))) {
                restoredScopes.add("canonical-source-registry");
                touchedArtifacts.add(String.valueOf(canonicalResult.getOrDefault("registryPath", "")));
            } else {
                skippedScopes.add("canonical-source-registry");
                reviewRequiredActions.add("canonical source registry did not contain restorable linkage");
            }

            Map<String, Object> regenerationResult = regenerationArtifactStore.rollbackRegeneration(
                    tenantId,
                    request.getTransactionReference().trim(),
                    mutationReference
            );
            if (Boolean.TRUE.equals(regenerationResult.get("transactionRemoved"))
                    || Boolean.TRUE.equals(regenerationResult.get("mutationRemoved"))) {
                restoredScopes.add("regeneration-manifest");
                touchedArtifacts.add(String.valueOf(regenerationResult.getOrDefault("manifestPath", "")));
            } else {
                skippedScopes.add("regeneration-manifest");
                reviewRequiredActions.add("regeneration manifest did not contain restorable linkage");
            }
        } else {
            if (!anchorResolved) {
                reviewRequiredActions.add("resolve rollback anchor before rollback execution");
            }
            if (transaction == null) {
                reviewRequiredActions.add("resolve publication transaction before rollback execution");
            }
            if (publication == null) {
                reviewRequiredActions.add("resolve real publication execution before rollback execution");
            } else if (!publicationEligible) {
                reviewRequiredActions.add("publication outcome is not eligible for rollback execution");
            }
            reviewRequiredActions.addAll(referenceResolver.extractStringList(tenantAssessment, "rejectedReferenceReasons"));
            skippedScopes.addAll(normalizeObjectList(rules.get("supportedScopes")));
        }

        skippedScopes.addAll(normalizeObjectList(rules.get("unsupportedScopes")));

        String rollbackStatus;
        String rollbackOutcome;
        if (!rollbackEligible) {
            rollbackStatus = "BLOCKED";
            rollbackOutcome = "BLOCKED_NO_ELIGIBLE_ANCHOR";
        } else if (restoredScopes.isEmpty()) {
            rollbackStatus = "REVIEW_REQUIRED";
            rollbackOutcome = "REVIEW_REQUIRED_BEFORE_ROLLBACK";
        } else {
            rollbackStatus = "ROLLED_BACK_PARTIALLY";
            rollbackOutcome = "PARTIAL_RESTORATION_RECORDED";
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("rollbackExecutionId", executionId);
        record.put("rollbackReference", request.getRollbackReference().trim());
        record.put("anchorReference", request.getAnchorReference().trim());
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("rollbackMode", request.getRollbackMode().trim());
        record.put("resolvedAnchorId", anchor == null
                ? ""
                : referenceResolver.extractFirstString(anchor, "rollbackAnchorId"));
        record.put("resolvedTransactionId", transaction == null
                ? ""
                : referenceResolver.extractFirstString(transaction, "publicationTransactionId"));
        record.put("resolvedPublicationExecutionId", publication == null
                ? ""
                : referenceResolver.extractFirstString(publication, "realPublicationExecutionId"));
        record.put("tenantCompatibilityStatus", tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE"));
        record.put("crossTenantViolationCount", tenantAssessment.getOrDefault("crossTenantViolationCount", 0));
        record.put("tenantIsolationStatus", tenantAssessment.getOrDefault("tenantIsolationStatus", "TENANT_SCOPED"));
        record.put("rejectedReferenceReasons", tenantAssessment.getOrDefault("rejectedReferenceReasons", List.of()));
        record.put("resolvedRecoveryReferences", linkedRecoveryReferences);
        record.put("explainabilityLinks", explainabilityLinks);
        record.put("auditLinks", auditLinks);
        record.put("mutationReference", mutationReference);
        record.put("rollbackEligibilityStatus", rollbackEligible ? "ELIGIBLE" : "REVIEW_REQUIRED");
        record.put("restoredScopes", dedupeList(restoredScopes));
        record.put("skippedScopes", dedupeList(skippedScopes));
        record.put("reviewRequiredActions", dedupeList(reviewRequiredActions));
        record.put("touchedArtifacts", dedupeList(touchedArtifacts));
        record.put("rollbackStatus", rollbackStatus);
        record.put("rollbackOutcome", rollbackOutcome);
        record.put("executedAt", executedAt);
        record.put("mode", mode);

        persistRecord(executionId, record);
        return record;
    }

    private Map<String, Object> resolveAnchor(RollbackExecutionRequest request, String tenantId) {
        String anchorReference = request.getAnchorReference().trim();
        List<Map<String, Object>> exactMatches = referenceResolver.resolveRecords(
                ANCHOR_ROOT,
                tenantId,
                List.of(anchorReference),
                "rollbackAnchorId",
                "beforeStateReference",
                "mutationReference"
        );
        if (!exactMatches.isEmpty()) {
            return exactMatches.get(0);
        }

        if (anchorReference.equals(request.getTransactionReference().trim() + "-anchor")) {
            Map<String, Object> byTransaction = referenceResolver.resolveSingle(
                    ANCHOR_ROOT,
                    tenantId,
                    request.getTransactionReference().trim(),
                    "mutationReference"
            );
            if (byTransaction != null) {
                return byTransaction;
            }
        }

        return referenceResolver.resolveSingle(
                ANCHOR_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "mutationReference"
        );
    }

    private String buildMutationReference(String transactionReference) {
        String normalized = transactionReference == null ? "" : transactionReference.trim().toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.isBlank()) {
            normalized = "unnamed-transaction";
        }
        return "real-publication-" + normalized;
    }

    private List<String> normalizeObjectList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String value = String.valueOf(item).trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }

    private List<String> dedupeList(List<String> values) {
        Set<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                deduped.add(value.trim());
            }
        }
        return new ArrayList<>(deduped);
    }

    private void validate(RollbackExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getRollbackReference())) {
            throw new IllegalArgumentException("rollbackReference is required.");
        }
        if (isBlank(request.getAnchorReference())) {
            throw new IllegalArgumentException("anchorReference is required.");
        }
        if (isBlank(request.getTransactionReference())) {
            throw new IllegalArgumentException("transactionReference is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
        if (isBlank(request.getRollbackMode())) {
            throw new IllegalArgumentException("rollbackMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load rollback execution rules.", e);
        }
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void persistRecord(String executionId, Map<String, Object> record) {
        try {
            Files.createDirectories(EXECUTION_ROOT);
            Path output = EXECUTION_ROOT.resolve(executionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist rollback execution record.", e);
        }
    }
}
