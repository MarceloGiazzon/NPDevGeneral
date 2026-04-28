package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.PublicationRollbackExecutionRequest;
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
public class PublicationRollbackExecutorService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-publication-rollback/publication-rollback-rules.json";

    private static final Path EXECUTION_ROOT =
            Paths.get("runtime-data", "publication-rollbacks");
    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");
    private static final Path PUBLICATION_ROOT =
            Paths.get("runtime-data", "real-publication-executions");
    private static final Path ANCHOR_ROOT =
            Paths.get("runtime-data", "source-mutation-rollback-anchors");
    private static final Path RECOVERY_ROOT =
            Paths.get("runtime-data", "publication-failure-recovery");
    private static final Path EXPLAINABILITY_ROOT =
            Paths.get("runtime-data", "rich-explainability");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final PublicationStateStore publicationStateStore;
    private final RollbackReferenceNormalizer rollbackReferenceNormalizer;

    public PublicationRollbackExecutorService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            PublicationStateStore publicationStateStore,
            RollbackReferenceNormalizer rollbackReferenceNormalizer
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.publicationStateStore = publicationStateStore;
        this.rollbackReferenceNormalizer = rollbackReferenceNormalizer;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Publication Rollback Executor"));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "publication-rollback-v1"));
        response.put("publicationRollbackReality", rules.getOrDefault(
                "publicationRollbackReality",
                "governed publication-state rollback over supported publication markers with explicit restored and skipped scopes"
        ));
        response.put("supportedRollbackModes", rules.getOrDefault("supportedRollbackModes", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        response.put("supportedRestoreScopes", rules.getOrDefault("supportedRestoreScopes", List.of()));
        response.put("unsupportedRestoreScopes", rules.getOrDefault("unsupportedRestoreScopes", List.of()));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(EXECUTION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> execute(PublicationRollbackExecutionRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String executionId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String executedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "publication-rollback-v1"));
        List<String> normalizedTransactionCandidates = rollbackReferenceNormalizer.normalizePublicationTransactionCandidates(
                request.getTransactionReference(),
                request.getExecutionReference()
        );
        List<String> normalizedExecutionCandidates = rollbackReferenceNormalizer.normalizePublicationExecutionCandidates(
                request.getExecutionReference(),
                request.getTransactionReference()
        );
        Map<String, Object> transactionTenantAssessment = referenceResolver.assessTenantIsolation(
                TRANSACTION_ROOT,
                tenantId,
                normalizedTransactionCandidates,
                "publication transaction",
                "transactionReference",
                "publicationTransactionId"
        );
        Map<String, Object> publicationTenantAssessment = referenceResolver.assessTenantIsolation(
                PUBLICATION_ROOT,
                tenantId,
                normalizedExecutionCandidates,
                "real publication execution",
                "transactionReference",
                "realPublicationExecutionId",
                "mutationReference"
        );
        Map<String, Object> tenantAssessment = referenceResolver.mergeTenantAssessments(
                transactionTenantAssessment,
                publicationTenantAssessment
        );

        List<Map<String, Object>> transactionMatches = referenceResolver.resolveRecords(
                TRANSACTION_ROOT,
                tenantId,
                normalizedTransactionCandidates,
                "transactionReference",
                "publicationTransactionId"
        );
        Map<String, Object> transaction = transactionMatches.isEmpty() ? null : transactionMatches.get(0);
        Map<String, Object> publication = resolvePublication(request, tenantId);
        List<String> rollbackAnchorReferences = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(transaction, "resolvedRollbackAnchorReferences", "rollbackAnchorReferences");
        List<Map<String, Object>> anchors = rollbackAnchorReferences.isEmpty()
                ? List.of()
                : referenceResolver.resolveRecords(
                        ANCHOR_ROOT,
                        tenantId,
                        rollbackAnchorReferences,
                        "rollbackAnchorId",
                        "mutationReference"
                );
        List<Map<String, Object>> recoveries = referenceResolver.findAllByReference(
                RECOVERY_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "publicationFailureRecoveryId"
        );
        List<Map<String, Object>> explainabilityRecords = referenceResolver.findAllByReference(
                EXPLAINABILITY_ROOT,
                tenantId,
                request.getTransactionReference().trim(),
                "transactionReference",
                "richExplainabilityId"
        );

        String publicationStatus = publication == null
                ? ""
                : referenceResolver.extractFirstString(publication, "publicationStatus");
        boolean rollbackEligible = transaction != null
                && publication != null
                && ("PUBLISHED".equals(publicationStatus) || "PUBLISHED_REVIEW_AWARE".equals(publicationStatus))
                && "COMPATIBLE".equals(String.valueOf(tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE")));

        List<String> restoredScopes = new ArrayList<>();
        List<String> skippedScopes = new ArrayList<>();
        List<String> reviewRequiredActions = new ArrayList<>();
        List<String> touchedPublicationArtifacts = new ArrayList<>();

        if (rollbackEligible) {
            Map<String, Object> rollbackResult = publicationStateStore.rollbackPublicationState(
                    referenceResolver.extractFirstString(transaction, "publicationTransactionId"),
                    referenceResolver.extractFirstString(publication, "realPublicationExecutionId"),
                    request.getRollbackReference().trim()
            );
            if (Boolean.TRUE.equals(rollbackResult.get("publicationUpdated"))) {
                restoredScopes.add("publication-execution-state");
                touchedPublicationArtifacts.add(String.valueOf(rollbackResult.getOrDefault("publicationPath", "")));
            } else {
                skippedScopes.add("publication-execution-state");
                reviewRequiredActions.add("publication execution state could not be restored");
            }
            if (Boolean.TRUE.equals(rollbackResult.get("transactionUpdated"))) {
                restoredScopes.add("publication-transaction-state");
                touchedPublicationArtifacts.add(String.valueOf(rollbackResult.getOrDefault("transactionPath", "")));
            } else {
                skippedScopes.add("publication-transaction-state");
                reviewRequiredActions.add("publication transaction state could not be restored");
            }
        } else {
            if (transaction == null) {
                reviewRequiredActions.add("resolve publication transaction before publication rollback");
            }
            if (publication == null) {
                reviewRequiredActions.add("resolve publication execution before publication rollback");
            } else if (!("PUBLISHED".equals(publicationStatus) || "PUBLISHED_REVIEW_AWARE".equals(publicationStatus))) {
                reviewRequiredActions.add("publication execution is not in a rollback-eligible published state");
            }
            reviewRequiredActions.addAll(referenceResolver.extractStringList(tenantAssessment, "rejectedReferenceReasons"));
            skippedScopes.addAll(normalizeObjectList(rules.get("supportedRestoreScopes")));
        }

        skippedScopes.addAll(normalizeObjectList(rules.get("unsupportedRestoreScopes")));

        String publicationRollbackStatus;
        String publicationRollbackOutcome;
        if (!rollbackEligible) {
            publicationRollbackStatus = "BLOCKED";
            publicationRollbackOutcome = "BLOCKED_NO_ELIGIBLE_PUBLICATION";
        } else if (restoredScopes.isEmpty()) {
            publicationRollbackStatus = "REVIEW_REQUIRED";
            publicationRollbackOutcome = "REVIEW_REQUIRED_BEFORE_PUBLICATION_RESTORE";
        } else {
            publicationRollbackStatus = "ROLLED_BACK";
            publicationRollbackOutcome = "PUBLICATION_STATE_RESTORED";
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("publicationRollbackExecutionId", executionId);
        record.put("rollbackReference", request.getRollbackReference().trim());
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("executionReference", request.getExecutionReference().trim());
        record.put("normalizedTransactionCandidates", normalizedTransactionCandidates);
        record.put("normalizedExecutionCandidates", normalizedExecutionCandidates);
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("rollbackMode", request.getRollbackMode().trim());
        record.put("resolvedTransactionId", transaction == null ? "" : referenceResolver.extractFirstString(transaction, "publicationTransactionId"));
        record.put("resolvedPublicationExecutionId", publication == null ? "" : referenceResolver.extractFirstString(publication, "realPublicationExecutionId"));
        record.put("resolvedRollbackAnchorReferences", referenceResolver.extractCanonicalReferences(anchors, "rollbackAnchorId"));
        record.put("tenantCompatibilityStatus", tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE"));
        record.put("crossTenantViolationCount", tenantAssessment.getOrDefault("crossTenantViolationCount", 0));
        record.put("tenantIsolationStatus", tenantAssessment.getOrDefault("tenantIsolationStatus", "TENANT_SCOPED"));
        record.put("rejectedReferenceReasons", tenantAssessment.getOrDefault("rejectedReferenceReasons", List.of()));
        record.put("auditLinks", publication == null ? List.of() : referenceResolver.extractStringList(publication, "auditLinks"));
        record.put("recoveryLinks", referenceResolver.extractCanonicalReferences(recoveries, "publicationFailureRecoveryId"));
        record.put("explainabilityLinks", referenceResolver.extractCanonicalReferences(explainabilityRecords, "richExplainabilityId"));
        record.put("publicationRollbackEligibilityStatus", rollbackEligible ? "ELIGIBLE" : "REVIEW_REQUIRED");
        record.put("restoredScopes", dedupeList(restoredScopes));
        record.put("skippedScopes", dedupeList(skippedScopes));
        record.put("reviewRequiredActions", dedupeList(reviewRequiredActions));
        record.put("touchedPublicationArtifacts", dedupeList(touchedPublicationArtifacts));
        record.put("publicationRollbackStatus", publicationRollbackStatus);
        record.put("publicationRollbackOutcome", publicationRollbackOutcome);
        record.put("executedAt", executedAt);
        record.put("mode", mode);

        persistRecord(executionId, record);
        return record;
    }

    private Map<String, Object> resolvePublication(PublicationRollbackExecutionRequest request, String tenantId) {
        List<String> candidates = rollbackReferenceNormalizer.normalizePublicationExecutionCandidates(
                request.getExecutionReference(),
                request.getTransactionReference()
        );
        List<Map<String, Object>> direct = referenceResolver.resolveRecords(
                PUBLICATION_ROOT,
                tenantId,
                candidates,
                "realPublicationExecutionId",
                "transactionReference",
                "mutationReference"
        );
        if (!direct.isEmpty()) {
            return direct.get(0);
        }

        List<Map<String, Object>> byTransaction = referenceResolver.resolveRecords(
                PUBLICATION_ROOT,
                tenantId,
                rollbackReferenceNormalizer.normalizePublicationTransactionCandidates(
                        request.getTransactionReference(),
                        request.getExecutionReference()
                ),
                "transactionReference",
                "realPublicationExecutionId",
                "mutationReference"
        );
        return byTransaction.isEmpty() ? null : byTransaction.get(0);
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

    private void validate(PublicationRollbackExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getRollbackReference())) {
            throw new IllegalArgumentException("rollbackReference is required.");
        }
        if (isBlank(request.getTransactionReference())) {
            throw new IllegalArgumentException("transactionReference is required.");
        }
        if (isBlank(request.getExecutionReference())) {
            throw new IllegalArgumentException("executionReference is required.");
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
            throw new IllegalStateException("Failed to load publication rollback rules.", e);
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
            throw new IllegalStateException("Failed to persist publication rollback execution record.", e);
        }
    }
}
