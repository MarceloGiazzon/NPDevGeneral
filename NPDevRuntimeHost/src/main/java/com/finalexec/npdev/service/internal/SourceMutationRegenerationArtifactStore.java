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
public class SourceMutationRegenerationArtifactStore {

    private static final Path ARTIFACT_ROOT =
            Paths.get("runtime-data", "regenerated-artifacts");
    private static final Path MANIFEST_PATH =
            ARTIFACT_ROOT.resolve("regeneration-manifest.json");

    private final ObjectMapper objectMapper;

    public SourceMutationRegenerationArtifactStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> recordRegeneration(
            String tenantId,
            String transactionReference,
            String mutationReference
    ) {
        Map<String, Object> manifest = loadManifest();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) manifest.computeIfAbsent("artifacts", key -> new ArrayList<>());

        Map<String, Object> artifact = findOrCreateArtifact(artifacts, tenantId);
        appendUnique(artifact, "regeneratedTransactions", transactionReference);
        appendUnique(artifact, "regeneratedMutations", mutationReference);
        appendUnique(artifact, "generatedArtifacts", "artifactnp-regeneration-request");
        appendUnique(artifact, "generatedArtifacts", "runtimehost-refresh-request");
        artifact.put("lastRegenerationAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        artifact.put("regenerationMode", "source-mutation-regeneration-v1");

        manifest.put("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        persistManifest(manifest);
        return manifest;
    }

    public String manifestPath() {
        return MANIFEST_PATH.toString().replace("\\", "/");
    }

    public Map<String, Object> rollbackRegeneration(
            String tenantId,
            String transactionReference,
            String mutationReference
    ) {
        Map<String, Object> manifest = loadManifest();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) manifest.computeIfAbsent("artifacts", key -> new ArrayList<>());

        Map<String, Object> artifact = findOrCreateArtifact(artifacts, tenantId);
        boolean transactionRemoved = removeValue(artifact, "regeneratedTransactions", transactionReference);
        boolean mutationRemoved = removeValue(artifact, "regeneratedMutations", mutationReference);
        if (transactionRemoved) {
            appendUnique(artifact, "rolledBackTransactions", transactionReference);
        }
        if (mutationRemoved) {
            appendUnique(artifact, "rolledBackMutations", mutationReference);
        }
        appendUnique(artifact, "generatedArtifacts", "artifactnp-regeneration-rollback-request");
        appendUnique(artifact, "generatedArtifacts", "runtimehost-refresh-rollback-request");
        artifact.put("lastRollbackAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        artifact.put("rollbackMode", "rollback-execution-v1");

        manifest.put("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        persistManifest(manifest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manifestPath", manifestPath());
        result.put("transactionRemoved", transactionRemoved);
        result.put("mutationRemoved", mutationRemoved);
        return result;
    }

    private Map<String, Object> loadManifest() {
        try {
            Files.createDirectories(ARTIFACT_ROOT);
            if (!Files.exists(MANIFEST_PATH)) {
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("artifacts", new ArrayList<>());
                manifest.put("updatedAt", "");
                persistManifest(manifest);
                return manifest;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> manifest = objectMapper.readValue(MANIFEST_PATH.toFile(), LinkedHashMap.class);
            manifest.computeIfAbsent("artifacts", key -> new ArrayList<>());
            return manifest;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load regeneration manifest.", e);
        }
    }

    private void persistManifest(Map<String, Object> manifest) {
        try {
            Files.createDirectories(ARTIFACT_ROOT);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(MANIFEST_PATH.toFile(), manifest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist regeneration manifest.", e);
        }
    }

    private Map<String, Object> findOrCreateArtifact(List<Map<String, Object>> artifacts, String tenantId) {
        for (Map<String, Object> artifact : artifacts) {
            if (tenantId.equals(String.valueOf(artifact.getOrDefault("tenantId", "")))) {
                return artifact;
            }
        }

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifactId", "regenerated-artifacts-" + tenantId);
        artifact.put("tenantId", tenantId);
        artifact.put("regeneratedTransactions", new ArrayList<>());
        artifact.put("regeneratedMutations", new ArrayList<>());
        artifact.put("generatedArtifacts", new ArrayList<>());
        artifact.put("lastRegenerationAt", "");
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

    @SuppressWarnings("unchecked")
    private boolean removeValue(Map<String, Object> artifact, String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        List<String> values = (List<String>) artifact.computeIfAbsent(key, ignored -> new ArrayList<>());
        return values.remove(value);
    }
}
