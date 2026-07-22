package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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

@Service
public class CanonicalSourceArtifactStore {

    private static final Path ARTIFACT_ROOT =
            Paths.get("runtime-data", "canonical-source-artifacts");
    private static final Path REGISTRY_PATH =
            ARTIFACT_ROOT.resolve("canonical-source-registry.json");

    private final ObjectMapper objectMapper;

    public CanonicalSourceArtifactStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> applyMutation(
            String tenantId,
            String transactionReference,
            String mutationReference,
            List<String> structuralMappings,
            List<String> semanticMappings
    ) {
        Map<String, Object> registry = loadRegistry();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) registry.computeIfAbsent("artifacts", key -> new ArrayList<>());

        Map<String, Object> artifact = findOrCreateArtifact(artifacts, tenantId);
        appendUnique(artifact, "appliedTransactions", transactionReference);
        appendUnique(artifact, "appliedMutations", mutationReference);
        appendUniqueAll(artifact, "appliedStructuralMappings", structuralMappings);
        appendUniqueAll(artifact, "appliedSemanticMappings", semanticMappings);
        artifact.put("lastMutationAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        artifact.put("mutationMode", "canonical-source-mutation-v1");

        registry.put("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        persistRegistry(registry);
        return registry;
    }

    public String registryPath() {
        return REGISTRY_PATH.toString().replace("\\", "/");
    }

    public Map<String, Object> rollbackMutation(
            String tenantId,
            String transactionReference,
            String mutationReference,
            List<String> structuralMappings,
            List<String> semanticMappings
    ) {
        Map<String, Object> registry = loadRegistry();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) registry.computeIfAbsent("artifacts", key -> new ArrayList<>());

        Map<String, Object> artifact = findOrCreateArtifact(artifacts, tenantId);
        boolean transactionRemoved = removeValue(artifact, "appliedTransactions", transactionReference);
        boolean mutationRemoved = removeValue(artifact, "appliedMutations", mutationReference);
        List<String> removedStructuralMappings = removeValues(artifact, "appliedStructuralMappings", structuralMappings);
        List<String> removedSemanticMappings = removeValues(artifact, "appliedSemanticMappings", semanticMappings);

        if (transactionRemoved) {
            appendUnique(artifact, "rolledBackTransactions", transactionReference);
        }
        if (mutationRemoved) {
            appendUnique(artifact, "rolledBackMutations", mutationReference);
        }
        appendUniqueAll(artifact, "rolledBackStructuralMappings", removedStructuralMappings);
        appendUniqueAll(artifact, "rolledBackSemanticMappings", removedSemanticMappings);
        artifact.put("lastRollbackAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        artifact.put("rollbackMode", "rollback-execution-v1");

        registry.put("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        persistRegistry(registry);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registryPath", registryPath());
        result.put("transactionRemoved", transactionRemoved);
        result.put("mutationRemoved", mutationRemoved);
        result.put("removedStructuralMappings", removedStructuralMappings);
        result.put("removedSemanticMappings", removedSemanticMappings);
        return result;
    }

    private Map<String, Object> loadRegistry() {
        try {
            Files.createDirectories(ARTIFACT_ROOT);
            if (!Files.exists(REGISTRY_PATH)) {
                Map<String, Object> registry = new LinkedHashMap<>();
                registry.put("artifacts", new ArrayList<>());
                registry.put("updatedAt", "");
                persistRegistry(registry);
                return registry;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> registry = objectMapper.readValue(REGISTRY_PATH.toFile(), LinkedHashMap.class);
            registry.computeIfAbsent("artifacts", key -> new ArrayList<>());
            return registry;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load canonical source registry.", e);
        }
    }

    private void persistRegistry(Map<String, Object> registry) {
        try {
            Files.createDirectories(ARTIFACT_ROOT);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(REGISTRY_PATH.toFile(), registry);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist canonical source registry.", e);
        }
    }

    private Map<String, Object> findOrCreateArtifact(List<Map<String, Object>> artifacts, String tenantId) {
        for (Map<String, Object> artifact : artifacts) {
            if (tenantId.equals(String.valueOf(artifact.getOrDefault("tenantId", "")))) {
                return artifact;
            }
        }

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifactId", "canonical-source-" + tenantId);
        artifact.put("tenantId", tenantId);
        artifact.put("appliedTransactions", new ArrayList<>());
        artifact.put("appliedMutations", new ArrayList<>());
        artifact.put("appliedStructuralMappings", new ArrayList<>());
        artifact.put("appliedSemanticMappings", new ArrayList<>());
        artifact.put("lastMutationAt", "");
        artifacts.add(artifact);
        return artifact;
    }

    @SuppressWarnings("unchecked")
    private void appendUnique(Map<String, Object> artifact, String key, String value) {
        List<String> values = (List<String>) artifact.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private void appendUniqueAll(Map<String, Object> artifact, String key, List<String> values) {
        for (String value : values) {
            appendUnique(artifact, key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean removeValue(Map<String, Object> artifact, String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        List<String> values = (List<String>) artifact.computeIfAbsent(key, ignored -> new ArrayList<>());
        return values.remove(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> removeValues(Map<String, Object> artifact, String key, List<String> removals) {
        List<String> removed = new ArrayList<>();
        if (removals == null || removals.isEmpty()) {
            return removed;
        }
        List<String> values = (List<String>) artifact.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (String removal : removals) {
            if (removal != null && !removal.isBlank() && values.remove(removal)) {
                removed.add(removal);
            }
        }
        return removed;
    }
}
