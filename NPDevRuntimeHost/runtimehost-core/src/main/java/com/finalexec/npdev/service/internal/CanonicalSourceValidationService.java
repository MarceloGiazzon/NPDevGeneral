package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.CanonicalSourceMutationExecutionRequest;
import com.finalexec.npdev.dto.CanonicalSourceValidationRequest;
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
public class CanonicalSourceValidationService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-canonical-source-validation/canonical-source-validation-rules.json";

    private static final Path VALIDATION_ROOT =
            Paths.get("runtime-data", "canonical-source-validations");
    private static final Path MUTATION_ROOT =
            Paths.get("runtime-data", "canonical-source-mutations");
    private static final Path CANONICAL_REGISTRY_PATH =
            Paths.get("runtime-data", "canonical-source-artifacts", "canonical-source-registry.json");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final CanonicalSourceMutationExecutorService canonicalSourceMutationExecutorService;

    public CanonicalSourceValidationService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            CanonicalSourceMutationExecutorService canonicalSourceMutationExecutorService
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.canonicalSourceMutationExecutorService = canonicalSourceMutationExecutorService;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", VALIDATION_ROOT.toString().replace("\\", "/"));
        response.put("mode", rules.getOrDefault("mode", "canonical-source-safe-apply-v1"));
        response.put("validationReality", rules.getOrDefault(
                "validationReality",
                "safe apply only succeeds when canonical source validation passes"
        ));
        response.put("supportedValidationModes", rules.getOrDefault("supportedValidationModes", List.of()));
        response.put("supportedStatuses", rules.getOrDefault("supportedStatuses", List.of()));
        response.put("canonicalArtifactStore", rules.getOrDefault("canonicalArtifactStore", ""));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(VALIDATION_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> validateAndApply(CanonicalSourceValidationRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String validationId = UUID.randomUUID().toString();
        String tenantId = normalizeOrDefault(request.getTenantId(), "global");
        String validatedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "canonical-source-safe-apply-v1"));

        Map<String, Object> mutationRecord = resolveMutationRecord(request, tenantId);
        List<String> validationChecks = new ArrayList<>();
        List<String> failureReasons = new ArrayList<>();
        List<String> touchedSourceArtifacts = new ArrayList<>();

        boolean artifactExists = Files.exists(CANONICAL_REGISTRY_PATH);
        validationChecks.add("source artifact existence checked");
        if (!artifactExists) {
            failureReasons.add("canonical source registry does not exist");
        }

        boolean artifactReadable = false;
        Map<String, Object> registry = null;
        if (artifactExists) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> loaded = objectMapper.readValue(CANONICAL_REGISTRY_PATH.toFile(), LinkedHashMap.class);
                registry = loaded;
                artifactReadable = true;
                touchedSourceArtifacts.add(CANONICAL_REGISTRY_PATH.toString().replace("\\", "/"));
            } catch (Exception ignored) {
            }
        }
        validationChecks.add("source artifact readability checked");
        if (artifactExists && !artifactReadable) {
            failureReasons.add("canonical source registry is not readable");
        }

        String mutationEligibilityStatus = mutationRecord == null
                ? "REVIEW_REQUIRED"
                : String.valueOf(mutationRecord.getOrDefault("mutationEligibilityStatus", "REVIEW_REQUIRED"));
        validationChecks.add("mutation eligibility checked");
        if (!"ELIGIBLE".equals(mutationEligibilityStatus)) {
            failureReasons.add("mutation is not eligible for safe apply");
        }

        boolean mutationStructureValid = mutationRecord != null
                && mutationRecord.containsKey("mutationResultStatus")
                && mutationRecord.containsKey("touchedSourceArtifacts");
        validationChecks.add("source mutation result structure checked");
        if (!mutationStructureValid) {
            failureReasons.add("canonical mutation result structure is incomplete");
        }

        boolean deterministicWriteValid = false;
        boolean postMutationValid = false;
        if (registry != null) {
            deterministicWriteValid = registry.containsKey("artifacts");
            postMutationValid = registryHasMutation(registry, tenantId, request.getTransactionReference().trim(), request.getMutationReference().trim());
        }
        validationChecks.add("deterministic write result checked");
        if (!deterministicWriteValid) {
            failureReasons.add("canonical registry does not expose deterministic artifact structure");
        }
        validationChecks.add("post-mutation source validity checked");
        if (!postMutationValid) {
            failureReasons.add("canonical registry does not contain the requested transaction/mutation linkage");
        }

        String safeApplyStatus;
        if (failureReasons.isEmpty()) {
            safeApplyStatus = "SAFE_APPLIED";
        } else if (artifactExists || mutationRecord != null) {
            safeApplyStatus = "REVIEW_REQUIRED";
        } else {
            safeApplyStatus = "REJECTED";
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("canonicalSourceValidationId", validationId);
        record.put("transactionReference", request.getTransactionReference().trim());
        record.put("mutationReference", request.getMutationReference().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("validationMode", request.getValidationMode().trim());
        record.put("resolvedMutationExecutionId", mutationRecord == null
                ? ""
                : String.valueOf(mutationRecord.getOrDefault("canonicalSourceMutationExecutionId", "")));
        record.put("validationChecks", validationChecks);
        record.put("failureReasons", failureReasons);
        record.put("touchedSourceArtifacts", touchedSourceArtifacts);
        record.put("safeApplyStatus", safeApplyStatus);
        record.put("validatedAt", validatedAt);
        record.put("mode", mode);

        persistRecord(validationId, record);
        return record;
    }

    private Map<String, Object> resolveMutationRecord(CanonicalSourceValidationRequest request, String tenantId) {
        List<Map<String, Object>> existing = referenceResolver.resolveRecords(
                MUTATION_ROOT,
                tenantId,
                List.of(request.getMutationReference().trim()),
                "mutationReference"
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        CanonicalSourceMutationExecutionRequest mutationRequest = new CanonicalSourceMutationExecutionRequest();
        mutationRequest.setTransactionReference(request.getTransactionReference());
        mutationRequest.setMutationReference(request.getMutationReference());
        mutationRequest.setRequestedBy(request.getRequestedBy());
        mutationRequest.setRationale(request.getRationale());
        mutationRequest.setTenantId(request.getTenantId());
        mutationRequest.setExecutionMode("canonical-source-mutation-v1");
        return canonicalSourceMutationExecutorService.execute(mutationRequest);
    }

    @SuppressWarnings("unchecked")
    private boolean registryHasMutation(
            Map<String, Object> registry,
            String tenantId,
            String transactionReference,
            String mutationReference
    ) {
        Object rawArtifacts = registry.get("artifacts");
        if (!(rawArtifacts instanceof List<?> artifacts)) {
            return false;
        }

        for (Object artifactRaw : artifacts) {
            if (!(artifactRaw instanceof Map<?, ?> artifactMapRaw)) {
                continue;
            }
            Map<String, Object> artifact = (Map<String, Object>) artifactMapRaw;
            if (!tenantId.equals(String.valueOf(artifact.getOrDefault("tenantId", "")))) {
                continue;
            }
            List<String> transactions = normalizeObjectList(artifact.get("appliedTransactions"));
            List<String> mutations = normalizeObjectList(artifact.get("appliedMutations"));
            if (transactions.contains(transactionReference) && mutations.contains(mutationReference)) {
                return true;
            }
        }

        return false;
    }

    private List<String> normalizeObjectList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String normalized = String.valueOf(item).trim();
                    if (!normalized.isBlank()) {
                        values.add(normalized);
                    }
                }
            }
        }
        return values;
    }

    private void validate(CanonicalSourceValidationRequest request) {
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
        if (isBlank(request.getValidationMode())) {
            throw new IllegalArgumentException("validationMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load canonical source validation rules.", e);
        }
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void persistRecord(String validationId, Map<String, Object> record) {
        try {
            Files.createDirectories(VALIDATION_ROOT);
            Path output = VALIDATION_ROOT.resolve(validationId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist canonical source validation record.", e);
        }
    }
}
