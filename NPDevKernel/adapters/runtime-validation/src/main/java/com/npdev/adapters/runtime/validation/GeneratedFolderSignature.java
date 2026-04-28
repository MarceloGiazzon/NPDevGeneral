package com.npdev.adapters.runtime.validation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class GeneratedFolderSignature {

    static final String SIGNATURE_RELATIVE_PATH = "src/main/resources/npdev/support/generated-folder.signature.properties";
    private static final String CONTRACT = "npdev-generated-folder-signature-v1";
    private static final String ALGORITHM = "SHA-256";

    private final String treeSha256;
    private final List<Entry> entries;

    private GeneratedFolderSignature(String treeSha256, List<Entry> entries) {
        this.treeSha256 = treeSha256;
        this.entries = List.copyOf(entries);
    }

    static GeneratedFolderSignature capture(Path generatedRoot) throws IOException {
        Path signaturePath = generatedRoot.resolve(SIGNATURE_RELATIVE_PATH).normalize();
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.walk(generatedRoot)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> normalizeRelative(generatedRoot, path)))
                    .forEach(path -> {
                        try {
                            if (path.normalize().equals(signaturePath)) {
                                return;
                            }
                            String relative = normalizeRelative(generatedRoot, path);
                            if (isIgnored(relative)) {
                                return;
                            }
                            byte[] bytes = Files.readAllBytes(path);
                            entries.add(new Entry(relative, sha256(bytes), bytes.length));
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
        return new GeneratedFolderSignature(treeSha256(entries), entries);
    }

    static GeneratedFolderSignature load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        String contract = properties.getProperty("contract");
        if (!CONTRACT.equals(contract)) {
            throw new IOException("Unsupported strict-execution signature contract: " + contract);
        }

        int fileCount = Integer.parseInt(properties.getProperty("fileCount", "0"));
        List<Entry> entries = new ArrayList<>(fileCount);
        for (int index = 0; index < fileCount; index++) {
            String prefix = "file." + String.format(Locale.ROOT, "%04d", index + 1);
            String entryPath = properties.getProperty(prefix + ".path");
            String entrySha256 = properties.getProperty(prefix + ".sha256");
            long entryBytes = Long.parseLong(properties.getProperty(prefix + ".bytes", "0"));
            if (entryPath == null || entrySha256 == null) {
                throw new IOException("Strict-execution signature is missing entry data for " + prefix);
            }
            entries.add(new Entry(entryPath, entrySha256, entryBytes));
        }

        return new GeneratedFolderSignature(properties.getProperty("treeSha256", ""), entries);
    }

    void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(toProperties());
        }
    }

    List<String> diffAgainst(GeneratedFolderSignature actual) {
        List<String> differences = new ArrayList<>();
        if (actual == null) {
            differences.add("actual signature is missing");
            return differences;
        }
        if (!treeSha256.equals(actual.treeSha256)) {
            differences.add("tree hash expected " + treeSha256 + " but was " + actual.treeSha256);
        }
        Map<String, Entry> expectedByPath = byPath(entries);
        Map<String, Entry> actualByPath = byPath(actual.entries);

        for (Map.Entry<String, Entry> expected : expectedByPath.entrySet()) {
            Entry actualEntry = actualByPath.get(expected.getKey());
            if (actualEntry == null) {
                differences.add("missing file " + expected.getKey());
                continue;
            }
            if (!expected.getValue().sha256.equals(actualEntry.sha256)) {
                differences.add("content mismatch for " + expected.getKey());
            } else if (expected.getValue().bytes != actualEntry.bytes) {
                differences.add("size mismatch for " + expected.getKey());
            }
        }
        for (String actualPath : actualByPath.keySet()) {
            if (!expectedByPath.containsKey(actualPath)) {
                differences.add("unexpected file " + actualPath);
            }
        }
        return differences;
    }

    private String toProperties() {
        StringBuilder builder = new StringBuilder();
        append(builder, "contract", CONTRACT);
        append(builder, "algorithm", ALGORITHM);
        append(builder, "root", ".");
        append(builder, "fileCount", Integer.toString(entries.size()));
        append(builder, "treeSha256", treeSha256);
        for (int index = 0; index < entries.size(); index++) {
            String prefix = "file." + String.format(Locale.ROOT, "%04d", index + 1);
            Entry entry = entries.get(index);
            append(builder, prefix + ".path", entry.path());
            append(builder, prefix + ".sha256", entry.sha256());
            append(builder, prefix + ".bytes", Long.toString(entry.bytes()));
        }
        return builder.toString();
    }

    private static Map<String, Entry> byPath(List<Entry> entries) {
        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byPath.put(entry.path(), entry);
        }
        return byPath;
    }

    private static void append(StringBuilder builder, String key, String value) {
        builder.append(key)
                .append('=')
                .append(escape(value))
                .append('\n');
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static boolean isIgnored(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bak")
                || lower.endsWith(".orig")
                || lower.endsWith(".rej")
                || lower.endsWith(".tmp")
                || lower.endsWith(".patch")
                || lower.endsWith(".diff")
                || lower.endsWith(".log");
    }

    private static String normalizeRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String treeSha256(List<Entry> entries) {
        MessageDigest digest = sha256Digest();
        for (Entry entry : entries) {
            digest.update(entry.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.sha256().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(entry.bytes()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return hex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is not available.", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private record Entry(String path, String sha256, long bytes) {
    }
}
