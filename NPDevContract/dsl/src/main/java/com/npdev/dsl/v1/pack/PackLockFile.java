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

    /**
     * @param migratedVersion the version this packId was last actually composed into a GENERATED
     *                        app, as of the most recent successful {@code npdev generate} -- distinct
     *                        from {@code resolvedVersion} (what constraint resolution currently
     *                        selects, updated by {@code pack add}/{@code update}). Owned and written
     *                        only by the generator (PK-4 Stage D): it is the one durable, committed
     *                        fact {@code PackMigrationComposer} can use as the migration chain's
     *                        {@code fromVersion} without guessing at a live database's actual state.
     *                        Empty string when a packId has never been generated (first-ever generate
     *                        composes an empty range -- see {@code PackMigrationComposer.compose}'s
     *                        {@code from.equals(to)} no-op case, reached by treating an empty
     *                        migratedVersion as equal to resolvedVersion at the call site).
     * @param from            PK-5: the exact {@code packs[].from} coordinate string this packId was
     *                        fetched from (e.g. {@code git+https://.../identity//pk@v2.1.0}), empty
     *                        for a LOCAL pack (imported via {@code $ref}, the only kind that existed
     *                        before PK-5). When non-empty, {@code sourcePath} is NOT a path relative
     *                        to the app's model root (a remote pack lives in the shared, machine-wide
     *                        {@code PackCache}, which can be on a different filesystem root entirely
     *                        -- {@code Path.relativize} across drive roots throws on Windows) -- it is
     *                        instead the cache entry's own absolute path, informational only. The
     *                        generate path (network-DENIED) looks up a remote pack by matching this
     *                        field against the model's own {@code packs[].from} string, never by
     *                        packId alone, since the coordinate is known before the pack's own
     *                        declared {@code pack} id is (chicken-and-egg: the id lives inside the
     *                        file being located).
     */
    public record LockedPack(String resolvedVersion, String digest, String sourcePath, String migratedVersion, String from) {
        /** Backward-compatible with every call site written before PK-5 added this field. */
        public LockedPack(String resolvedVersion, String digest, String sourcePath, String migratedVersion) {
            this(resolvedVersion, digest, sourcePath, migratedVersion, "");
        }

        /** Backward-compatible with every call site written before PK-4 Stage D added migratedVersion. */
        public LockedPack(String resolvedVersion, String digest, String sourcePath) {
            this(resolvedVersion, digest, sourcePath, "", "");
        }
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
                    textOrEmpty(value.get("sourcePath")),
                    textOrEmpty(value.get("migratedVersion")),
                    textOrEmpty(value.get("from"))));
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
            if (!locked.migratedVersion().isEmpty()) {
                entryNode.put("migratedVersion", locked.migratedVersion());
            }
            if (!locked.from().isEmpty()) {
                entryNode.put("from", locked.from());
            }
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
