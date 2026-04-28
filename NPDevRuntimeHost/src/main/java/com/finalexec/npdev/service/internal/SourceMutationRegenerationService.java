package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.CanonicalSourceValidationRequest;
import com.finalexec.npdev.dto.SourceMutationRegenerationRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SourceMutationRegenerationService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-source-mutation-regeneration/source-mutation-regeneration-rules.json";

    private static final Path REGENERATION_ROOT =
            Paths.get("runtime-data", "source-mutation-regeneration");
    private static final Path VALIDATION_ROOT =
            Paths.get("runtime-data", "canonical-source-validations");
    private static final Path MUTATION_ROOT =
            Paths.get("runtime-data", "canonical-source-mutations");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final CanonicalSourceValidationService canonicalSourceValidationService;
    private final SourceMutationRegenerationArtifactStore regenerationArtifactStore;

    public SourceMutationRegenerationService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            CanonicalSourceValidationService canonicalSourceValidationService,
            SourceMutationRegenerationArtifactStore regenerationArtifactStore
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.canonicalSourceValidationService = canonicalSourceValidationService;
        this.regenerationArtifactStore = regenerationArtifactStore;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault(
                "displayName",
                rules.getOrDefault("surfaceName", "Source Mutation + Regeneration Link")
        ));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", REGENERATION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "source-mutation-regeneration-v1"));
        response.put("regenerationReality", rules.getOrDefault(
                "regenerationReality",
                "validated source mutation propagates into regeneration-aware recording"
        ));
        response.put("supportedRegenerationModes", rules.getOrDefault("supportedRegenerationModes", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        response.put("regenerationManifest", rules.getOrDefault("regenerationManifest", ""));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(REGENERATION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> link(SourceMutationRegenerationRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String regenerationId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "source-mutation-regeneration-v1"));
        Map<String, Object> transactionTenantAssessment = referenceResolver.assessTenantIsolation(
                Paths.get("runtime-data", "publication-transactions"),
                tenantId,
                List.of(request.getTransactionReference().trim()),
                "publication transaction",
                "transactionReference",
                "publicationTransactionId"
        );
        Map<String, Object> mutationTenantAssessment = referenceResolver.assessTenantIsolation(
                MUTATION_ROOT,
                tenantId,
                List.of(request.getMutationReference().trim()),
                "canonical mutation",
                "mutationReference",
                "canonicalSourceMutationExecutionId"
        );
        Map<String, Object> tenantAssessment = referenceResolver.mergeTenantAssessments(
                transactionTenantAssessment,
                mutationTenantAssessment
        );

        Map<String, Object> validationRecord = resolveValidationRecord(request, tenantId);
        Map<String, Object> mutationRecord = referenceResolver.resolveSingle(
                MUTATION_ROOT,
                tenantId,
                request.getMutationReference().trim(),
                "mutationReference"
        );

        List<String> touchedArtifacts = new ArrayList<>();
        if (validationRecord != null) {
            touchedArtifacts.addAll(referenceResolver.extractStringList(validationRecord, "touchedSourceArtifacts"));
        }

        String safeApplyStatus = validationRecord == null
                ? "REVIEW_REQUIRED"
                : String.valueOf(validationRecord.getOrDefault("safeApplyStatus", "REVIEW_REQUIRED"));
        String regenerationStatus;
        String verificationPosture;
        List<String> reviewRequiredActions = new ArrayList<>();

        if (!"COMPATIBLE".equals(String.valueOf(tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE")))) {
            regenerationStatus = "REVIEW_REQUIRED";
            verificationPosture = "CROSS_TENANT_REJECTED";
            reviewRequiredActions.addAll(referenceResolver.extractStringList(tenantAssessment, "rejectedReferenceReasons"));
        } else if ("SAFE_APPLIED".equals(safeApplyStatus)) {
            regenerationArtifactStore.recordRegeneration(
                    tenantId,
                    request.getTransactionReference().trim(),
                    request.getMutationReference().trim()
            );
            touchedArtifacts.add(regenerationArtifactStore.manifestPath());
            regenerationStatus = "REGENERATED";
            verificationPosture = "SAFE_APPLIED_LINKED_TO_REGENERATION";
        } else {
            regenerationStatus = "REVIEW_REQUIRED";
            verificationPosture = "VALIDATION_NOT_SAFE_APPLIED";
            reviewRequiredActions.add("review canonical source validation result before regeneration");
        }

        List<String> rollbackReferences = mutationRecord == null
                ? List.of()
                : referenceResolver.extractStringList(mutationRecord, "rollbackAnchorReferences");
        List<String> auditLinks = mutationRecord == null
                ? List.of()
                : referenceResolver.extractStringList(mutationRecord, "auditLinks");

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sourceMutationRegenerationId", regenerationId);
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("mutationReference", request.getMutationReference().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("regenerationMode", request.getRegenerationMode().trim());
        record.put("resolvedValidationId", validationRecord == null
                ? ""
                : String.valueOf(validationRecord.getOrDefault("canonicalSourceValidationId", "")));
        record.put("resolvedMutationExecutionId", mutationRecord == null
                ? ""
                : String.valueOf(mutationRecord.getOrDefault("canonicalSourceMutationExecutionId", "")));
        record.put("tenantCompatibilityStatus", tenantAssessment.getOrDefault("tenantCompatibilityStatus", "COMPATIBLE"));
        record.put("crossTenantViolationCount", tenantAssessment.getOrDefault("crossTenantViolationCount", 0));
        record.put("tenantIsolationStatus", tenantAssessment.getOrDefault("tenantIsolationStatus", "TENANT_SCOPED"));
        record.put("rejectedReferenceReasons", tenantAssessment.getOrDefault("rejectedReferenceReasons", List.of()));
        record.put("touchedArtifacts", touchedArtifacts);
        record.put("rollbackReferences", rollbackReferences);
        record.put("auditLinks", auditLinks);
        record.put("regenerationStatus", regenerationStatus);
        record.put("verificationPosture", verificationPosture);
        record.put("reviewRequiredActions", reviewRequiredActions);
        record.put("recordedAt", recordedAt);
        record.put("mode", mode);

        persistRecord(regenerationId, record);
        return record;
    }

    private Map<String, Object> resolveValidationRecord(SourceMutationRegenerationRequest request, String tenantId) {
        List<Map<String, Object>> existing = referenceResolver.resolveRecords(
                VALIDATION_ROOT,
                tenantId,
                List.of(request.getMutationReference().trim()),
                "mutationReference"
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        CanonicalSourceValidationRequest validationRequest = new CanonicalSourceValidationRequest();
        validationRequest.setTransactionReference(request.getTransactionReference());
        validationRequest.setMutationReference(request.getMutationReference());
        validationRequest.setRequestedBy(request.getRequestedBy());
        validationRequest.setRationale(request.getRationale());
        validationRequest.setTenantId(request.getTenantId());
        validationRequest.setValidationMode("canonical-source-safe-apply-v1");
        return canonicalSourceValidationService.validateAndApply(validationRequest);
    }

    private void validate(SourceMutationRegenerationRequest request) {
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
        if (isBlank(request.getRegenerationMode())) {
            throw new IllegalArgumentException("regenerationMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load source mutation regeneration rules.", e);
        }
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void persistRecord(String regenerationId, Map<String, Object> record) {
        try {
            Files.createDirectories(REGENERATION_ROOT);
            Path output = REGENERATION_ROOT.resolve(regenerationId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist source mutation regeneration record.", e);
        }
    }
}
