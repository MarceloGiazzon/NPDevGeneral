package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * PK-3: {@code npdev.lock} -- the committed record of a transitive pack graph's resolution,
 * {@code {packId: {resolvedVersion, digest, sourcePath}}}. "Generation reads the lock, not the
 * constraints": once this file exists and is current, {@code PackDependencyGraphWalker} loads
 * dependency files by the lock's own {@code sourcePath}, and only re-runs the live
 * discovery/constraint-collection to detect DRIFT (packId set, computed version, file digest),
 * never to pick a version for the actual merge.
 */
public final class PackLockFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_VERSION = "npdev-lock.v1";
    public static final String FILE_NAME = "npdev.lock";

    public record LockedPack(String resolvedVersion, String digest, String sourcePath) {
    }

    private final Map<String, LockedPack> packs;

    private PackLockFile(Map<String, LockedPack> packs) {
        this.packs = packs;
    }

    public static PackLockFile of(Map<String, LockedPack> packs) {
        return new PackLockFile(new LinkedHashMap<>(packs));
    }

    public Map<String, LockedPack> packs() {
        return packs;
    }

    public static boolean exists(Path rootDirectory) {
        return Files.isRegularFile(rootDirectory.resolve(FILE_NAME));
    }

    public static PackLockFile read(Path rootDirectory) throws IOException {
        Path lockFile = rootDirectory.resolve(FILE_NAME);
        JsonNode root = MAPPER.readTree(lockFile.toFile());
        if (root == null || !root.isObject() || !root.has("packs") || !root.get("packs").isObject()) {
            throw new IOException(lockFile + ": malformed npdev.lock -- expected a top-level 'packs' object");
        }
        Map<String, LockedPack> packs = new LinkedHashMap<>();
        root.get("packs").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            packs.put(entry.getKey(), new LockedPack(
                    textOrEmpty(value.get("resolvedVersion")),
                    textOrEmpty(value.get("digest")),
                    textOrEmpty(value.get("sourcePath"))));
        });
        return new PackLockFile(packs);
    }

    public void write(Path rootDirectory) throws IOException {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode packsNode = root.putObject("packs");
        // Deterministic key order regardless of discovery order, for a stable, diffable commit.
        for (Map.Entry<String, LockedPack> entry : new TreeMap<>(packs).entrySet()) {
            ObjectNode entryNode = packsNode.putObject(entry.getKey());
            LockedPack locked = entry.getValue();
            entryNode.put("resolvedVersion", locked.resolvedVersion());
            entryNode.put("digest", locked.digest());
            entryNode.put("sourcePath", locked.sourcePath());
        }
        Files.writeString(rootDirectory.resolve(FILE_NAME),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator());
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) -- never actually thrown.
            throw new UncheckedIOException(new IOException(impossible));
        }
    }

    private static String textOrEmpty(JsonNode node) {
        return node != null && node.isTextual() ? node.asText("") : "";
    }
}
