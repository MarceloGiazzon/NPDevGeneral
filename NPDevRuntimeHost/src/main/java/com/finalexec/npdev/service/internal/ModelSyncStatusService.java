package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class ModelSyncStatusService {

    private final String deployModelPath;
    private final ObjectMapper canonicalObjectMapper;

    public ModelSyncStatusService(
            @Value("${npdev.deploy.model-path:#{null}}") String deployModelPath,
            ObjectMapper objectMapper
    ) {
        this.deployModelPath = normalizePath(deployModelPath);
        this.canonicalObjectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ModelSyncStatus computeSyncStatus(String authoringModelJson) {
        if (deployModelPath == null || authoringModelJson == null || authoringModelJson.isBlank()) {
            return ModelSyncStatus.unknown();
        }

        try {
            Path deployPath = Paths.get(deployModelPath);
            if (!Files.exists(deployPath)) {
                return ModelSyncStatus.deployNotFound();
            }

            String deployModelJson = Files.readString(deployPath);
            String authoringHash = sha256(canonicalizeJson(authoringModelJson));
            String deployHash = sha256(canonicalizeJson(deployModelJson));
            String lastExportedAt = Files.getLastModifiedTime(deployPath).toInstant().toString();
            boolean inSync = authoringHash.equals(deployHash);

            return new ModelSyncStatus(
                    inSync,
                    authoringHash,
                    deployHash,
                    lastExportedAt,
                    inSync ? "ok" : "diverged"
            );
        } catch (IOException e) {
            return ModelSyncStatus.error();
        } catch (IllegalArgumentException e) {
            return ModelSyncStatus.error();
        }
    }

    private String canonicalizeJson(String sourceJson) {
        try {
            Object parsed = canonicalObjectMapper.readValue(sourceJson, Object.class);
            return canonicalObjectMapper.writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid model JSON.", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private String normalizePath(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        return pathValue.trim();
    }

    public record ModelSyncStatus(
            boolean inSync,
            String authoringHash,
            String deployHash,
            String lastExportedAt,
            String status
    ) {
        public static ModelSyncStatus unknown() {
            return new ModelSyncStatus(false, null, null, null, "unknown");
        }

        public static ModelSyncStatus deployNotFound() {
            return new ModelSyncStatus(false, null, null, null, "deploy_not_found");
        }

        public static ModelSyncStatus error() {
            return new ModelSyncStatus(false, null, null, null, "error");
        }
    }
}
