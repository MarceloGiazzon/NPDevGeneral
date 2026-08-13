package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.CanonicalSourceMutationExecutionRequest;
import com.finalexec.npdev.dto.CanonicalSourceValidationRequest;
import com.finalexec.npdev.dto.RealPublicationExecutionRequest;
import com.finalexec.npdev.dto.SourceMutationRegenerationRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
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
public class RealPublicationExecutorService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-real-publication-executor/real-publication-executor-rules.json";

    private static final Path EXECUTION_ROOT =
            Paths.get("runtime-data", "real-publication-executions");
    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final CanonicalSourceMutationExecutorService canonicalSourceMutationExecutorService;
    private final CanonicalSourceValidationService canonicalSourceValidationService;
    private final SourceMutationRegenerationService sourceMutationRegenerationService;
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public RealPublicationExecutorService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            CanonicalSourceMutationExecutorService canonicalSourceMutationExecutorService,
            CanonicalSourceValidationService canonicalSourceValidationService,
            SourceMutationRegenerationService sourceMutationRegenerationService,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.canonicalSourceMutationExecutorService = canonicalSourceMutationExecutorService;
        this.canonicalSourceValidationService = canonicalSourceValidationService;
        this.sourceMutationRegenerationService = sourceMutationRegenerationService;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Real Publication Executor"));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", EXECUTION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "real-publication-v1"));
        response.put("publicationReality", rules.getOrDefault(
                "publicationReality",
                "authoritative publication composes validated transaction, canonical mutation, safe apply, regeneration, and explicit outcome recording"
        ));
        response.put("supportedPublicationModes", rules.getOrDefault("supportedPublicationModes", List.of()));
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

    public Map<String, Object> execute(RealPublicationExecutionRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String executionId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String executedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "real-publication-v1"));
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

        List<String> structuralMappingReferences = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedStructuralMappingReferences",
                        "structuralMappingReferences"
                );
        List<String> semanticMappingReferences = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedSemanticMappingReferences",
                        "semanticMappingReferences"
                );
        List<String> approvalReferences = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedApprovalReferences",
                        "approvalReferences"
                );
        List<String> rollbackAnchorReferences = transaction == null
                ? List.of()
                : referenceResolver.extractStringList(
                        transaction,
                        "resolvedRollbackAnchorReferences",
                        "rollbackAnchorReferences"
                );
        String transactionIntegrityStatus = transaction == null
                ? "UNRESOLVED"
                : referenceResolver.extractFirstString(transaction, "integrityStatus");
        boolean validatedTransaction = transaction != null
                && "RESOLVED".equals(transactionIntegrityStatus)
                && "COMPATIBLE".equals(String.valueOf(tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE")));
        String mutationReference = buildMutationReference(request.getTransactionReference());

        List<String> executedActions = new ArrayList<>();
        List<String> reviewRequiredActions = new ArrayList<>();
        List<String> skippedActions = new ArrayList<>();
        Set<String> auditLinks = new LinkedHashSet<>();
        List<String> touchedArtifacts = new ArrayList<>();

        Map<String, Object> mutationRecord = null;
        Map<String, Object> validationRecord = null;
        Map<String, Object> regenerationRecord = null;

        if (validatedTransaction) {
            executedActions.add("resolved validated publication transaction");
            executedActions.add("resolved structural publication mappings");
            executedActions.add("resolved semantic publication mappings");
            executedActions.add("resolved approval references");
            executedActions.add("resolved rollback anchor references");

            CanonicalSourceMutationExecutionRequest mutationRequest = new CanonicalSourceMutationExecutionRequest();
            mutationRequest.setTransactionReference(request.getTransactionReference());
            mutationRequest.setMutationReference(mutationReference);
            mutationRequest.setRequestedBy(request.getRequestedBy());
            mutationRequest.setRationale(request.getRationale());
            mutationRequest.setTenantId(tenantId);
            mutationRequest.setExecutionMode("canonical-source-mutation-v1");
            mutationRecord = canonicalSourceMutationExecutorService.execute(mutationRequest);
            executedActions.add("executed canonical source mutation");
            auditLinks.addAll(referenceResolver.extractStringList(mutationRecord, "auditLinks"));
            touchedArtifacts.addAll(referenceResolver.extractStringList(mutationRecord, "touchedSourceArtifacts"));

            CanonicalSourceValidationRequest validationRequest = new CanonicalSourceValidationRequest();
            validationRequest.setTransactionReference(request.getTransactionReference());
            validationRequest.setMutationReference(mutationReference);
            validationRequest.setRequestedBy(request.getRequestedBy());
            validationRequest.setRationale(request.getRationale());
            validationRequest.setTenantId(tenantId);
            validationRequest.setValidationMode("canonical-source-safe-apply-v1");
            validationRecord = canonicalSourceValidationService.validateAndApply(validationRequest);
            executedActions.add("recorded canonical source validation");
            touchedArtifacts.addAll(referenceResolver.extractStringList(validationRecord, "touchedSourceArtifacts"));

            SourceMutationRegenerationRequest regenerationRequest = new SourceMutationRegenerationRequest();
            regenerationRequest.setTransactionReference(request.getTransactionReference());
            regenerationRequest.setMutationReference(mutationReference);
            regenerationRequest.setRequestedBy(request.getRequestedBy());
            regenerationRequest.setRationale(request.getRationale());
            regenerationRequest.setTenantId(tenantId);
            regenerationRequest.setRegenerationMode("source-mutation-regeneration-v1");
            regenerationRecord = sourceMutationRegenerationService.link(regenerationRequest);
            executedActions.add("recorded source mutation regeneration linkage");
            auditLinks.addAll(referenceResolver.extractStringList(regenerationRecord, "auditLinks"));
            touchedArtifacts.addAll(referenceResolver.extractStringList(regenerationRecord, "touchedArtifacts"));
            reviewRequiredActions.addAll(referenceResolver.extractStringList(mutationRecord, "reviewRequiredActions"));
            reviewRequiredActions.addAll(referenceResolver.extractStringList(regenerationRecord, "reviewRequiredActions"));
        } else {
            reviewRequiredActions.add("resolve validated publication transaction before real publication");
            if (transaction != null && !"RESOLVED".equals(transactionIntegrityStatus)) {
                reviewRequiredActions.add("resolve unresolved transaction references before authoritative publication");
            }
            reviewRequiredActions.addAll(referenceResolver.extractStringList(tenantAssessment, "rejectedReferenceReasons"));
            skippedActions.add("canonical source mutation");
            skippedActions.add("canonical source validation");
            skippedActions.add("source mutation regeneration linkage");
        }

        String mutationResultStatus = mutationRecord == null
                ? "NOT_EXECUTED"
                : String.valueOf(mutationRecord.getOrDefault("mutationResultStatus", "NOT_EXECUTED"));
        String safeApplyStatus = validationRecord == null
                ? "NOT_EXECUTED"
                : String.valueOf(validationRecord.getOrDefault("safeApplyStatus", "NOT_EXECUTED"));
        String regenerationStatus = regenerationRecord == null
                ? "NOT_EXECUTED"
                : String.valueOf(regenerationRecord.getOrDefault("regenerationStatus", "NOT_EXECUTED"));

        int resolvedLayers = 0;
        if (validatedTransaction) {
            resolvedLayers++;
        }
        if (!structuralMappingReferences.isEmpty()) {
            resolvedLayers++;
        }
        if (!semanticMappingReferences.isEmpty()) {
            resolvedLayers++;
        }
        if (!approvalReferences.isEmpty()) {
            resolvedLayers++;
        }
        if (!rollbackAnchorReferences.isEmpty()) {
            resolvedLayers++;
        }
        if ("SAFE_APPLIED".equals(safeApplyStatus)) {
            resolvedLayers++;
        }
        if ("REGENERATED".equals(regenerationStatus)) {
            resolvedLayers++;
        }
        String publicationCoverage = resolvedLayers + "/7 authoritative publication layers resolved";

        String publicationStatus;
        String publicationOutcome;
        if (!validatedTransaction) {
            publicationStatus = "BLOCKED";
            publicationOutcome = "BLOCKED_NO_VALIDATED_TRANSACTION";
        } else if ("SAFE_APPLIED".equals(safeApplyStatus) && "REGENERATED".equals(regenerationStatus)) {
            publicationStatus = reviewRequiredActions.isEmpty() ? "PUBLISHED" : "PUBLISHED_REVIEW_AWARE";
            publicationOutcome = "AUTHORITATIVE_PUBLICATION_RECORDED";
        } else {
            publicationStatus = "REVIEW_REQUIRED";
            publicationOutcome = "REVIEW_REQUIRED_BEFORE_PUBLICATION";
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("realPublicationExecutionId", executionId);
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("mutationReference", mutationReference);
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("publicationMode", request.getPublicationMode().trim());
        record.put("resolvedTransactionId", transaction == null
                ? ""
                : referenceResolver.extractFirstString(transaction, "publicationTransactionId"));
        record.put("resolvedTransactionIntegrityStatus", transactionIntegrityStatus);
        record.put("tenantCompatibilityStatus", tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE"));
        record.put("crossTenantViolationCount", tenantAssessment.getOrDefault("crossTenantViolationCount", 0));
        record.put("tenantIsolationStatus", tenantAssessment.getOrDefault("tenantIsolationStatus", "TENANT_SCOPED"));
        record.put("rejectedReferenceReasons", tenantAssessment.getOrDefault("rejectedReferenceReasons", List.of()));
        record.put("validatedTransactionStatus", validatedTransaction ? "VALIDATED" : "REVIEW_REQUIRED");
        record.put("structuralMappingReferences", structuralMappingReferences);
        record.put("semanticMappingReferences", semanticMappingReferences);
        record.put("approvalReferences", approvalReferences);
        record.put("rollbackAnchorReferences", rollbackAnchorReferences);
        record.put("auditLinks", new ArrayList<>(auditLinks));
        record.put("resolvedMutationExecutionId", mutationRecord == null
                ? ""
                : String.valueOf(mutationRecord.getOrDefault("canonicalSourceMutationExecutionId", "")));
        record.put("resolvedValidationId", validationRecord == null
                ? ""
                : String.valueOf(validationRecord.getOrDefault("canonicalSourceValidationId", "")));
        record.put("resolvedRegenerationId", regenerationRecord == null
                ? ""
                : String.valueOf(regenerationRecord.getOrDefault("sourceMutationRegenerationId", "")));
        record.put("mutationResultStatus", mutationResultStatus);
        record.put("safeApplyStatus", safeApplyStatus);
        record.put("regenerationStatus", regenerationStatus);
        record.put("publicationStatus", publicationStatus);
        record.put("publicationOutcome", publicationOutcome);
        record.put("publicationCoverage", publicationCoverage);
        record.put("touchedArtifacts", dedupeList(touchedArtifacts));
        record.put("executedActions", dedupeList(executedActions));
        record.put("reviewRequiredActions", dedupeList(reviewRequiredActions));
        record.put("skippedActions", dedupeList(skippedActions));
        record.put("executedAt", executedAt);
        record.put("mode", mode);

        persistRecord(executionId, record);
        persistPublicationExecution(record);
        return record;
    }

    private void persistPublicationExecution(Map<String, Object> record) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(record);
            String publicationExecutionId = String.valueOf(record.getOrDefault("realPublicationExecutionId", ""));
            String tenantId = String.valueOf(record.getOrDefault("tenantId", ""));
            String publicationReference = String.valueOf(record.getOrDefault("transactionReference", ""));
            String publicationTransactionId = nullIfBlank(String.valueOf(record.getOrDefault("resolvedTransactionId", "")));
            String executionMode = String.valueOf(record.getOrDefault("publicationMode", ""));
            String publicationStatus = String.valueOf(record.getOrDefault("publicationStatus", ""));
            String publicationOutcome = nullIfBlank(String.valueOf(record.getOrDefault("publicationOutcome", "")));
            Timestamp now = currentTimestamp();

            int updated = jdbcTemplate.update(
                    PublicationExecutionSql.update(),
                    tenantId,
                    publicationReference,
                    publicationTransactionId,
                    executionMode,
                    publicationStatus,
                    publicationOutcome,
                    payload,
                    now,
                    now,
                    publicationExecutionId
            );
            if (updated > 0) {
                return;
            }

            try {
                jdbcTemplate.update(
                        PublicationExecutionSql.insert(),
                        publicationExecutionId,
                        tenantId,
                        publicationReference,
                        publicationTransactionId,
                        executionMode,
                        publicationStatus,
                        publicationOutcome,
                        payload,
                        now,
                        now,
                        now,
                        now
                );
            } catch (DuplicateKeyException duplicateKeyException) {
                int retried = jdbcTemplate.update(
                        PublicationExecutionSql.update(),
                        tenantId,
                        publicationReference,
                        publicationTransactionId,
                        executionMode,
                        publicationStatus,
                        publicationOutcome,
                        payload,
                        now,
                        now,
                        publicationExecutionId
                );
                if (retried == 0) {
                    throw duplicateKeyException;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist publication execution database state.", exception);
        }
    }

    static final class PublicationExecutionSql {
        private PublicationExecutionSql() {
        }

        static String update() {
            return """
                    UPDATE npdev_publication_execution
                    SET tenant_id = ?,
                        publication_reference = ?,
                        publication_transaction_id = ?,
                        execution_mode = ?,
                        publication_status = ?,
                        publication_outcome = ?,
                        execution_payload = ?,
                        completed_at = ?,
                        updated_at = ?
                    WHERE publication_execution_id = ?
                    """;
        }

        static String insert() {
            return """
                    INSERT INTO npdev_publication_execution (
                        publication_execution_id,
                        tenant_id,
                        publication_reference,
                        publication_transaction_id,
                        execution_mode,
                        publication_status,
                        publication_outcome,
                        execution_payload,
                        started_at,
                        completed_at,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
        }
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

    private List<String> dedupeList(List<String> values) {
        Set<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                deduped.add(value.trim());
            }
        }
        return new ArrayList<>(deduped);
    }

    private void validate(RealPublicationExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
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
        if (isBlank(request.getPublicationMode())) {
            throw new IllegalArgumentException("publicationMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load real publication executor rules.", e);
        }
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private String nullIfBlank(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private Timestamp currentTimestamp() {
        return Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
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
            throw new IllegalStateException("Failed to persist real publication execution record.", e);
        }
    }
}
