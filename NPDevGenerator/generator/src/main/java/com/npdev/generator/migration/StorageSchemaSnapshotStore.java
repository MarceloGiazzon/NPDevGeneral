package com.npdev.generator.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class StorageSchemaSnapshotStore {

    private final ObjectMapper objectMapper;

    public StorageSchemaSnapshotStore() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public StorageSchemaSnapshot loadIfExists(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return new StorageSchemaSnapshot("none", java.util.List.of());
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        StorageSchemaSnapshot snapshot = objectMapper.readValue(json, StorageSchemaSnapshot.class);
        return snapshot == null ? new StorageSchemaSnapshot("none", java.util.List.of()) : snapshot.normalized();
    }

    public void save(Path path, StorageSchemaSnapshot snapshot) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("path must be non-null");
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, toCanonicalJson(snapshot), StandardCharsets.UTF_8);
    }

    public String toCanonicalJson(StorageSchemaSnapshot snapshot) throws Exception {
        StorageSchemaSnapshot normalized = snapshot == null
                ? new StorageSchemaSnapshot("none", java.util.List.of())
                : snapshot.normalized();
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
    }

    public String computeCanonicalHash(StorageSchemaSnapshot snapshot) throws Exception {
        String json = toCanonicalJson(snapshot);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) {
            out.append(String.format("%02x", b));
        }
        return out.substring(0, 12);
    }
}
