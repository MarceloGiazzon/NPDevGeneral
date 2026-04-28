package com.npdev.generator.emitters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GeneratedFolderSignatureEmitter {

    public static final String SIGNATURE_RELATIVE_PATH = "src/main/resources/npdev/support/generated-folder.signature.properties";
    private static final String CONTRACT = "npdev-generated-folder-signature-v1";
    private static final String ALGORITHM = "SHA-256";

    public void emit(Path generatedRoot) throws IOException {
        if (generatedRoot == null || !Files.isDirectory(generatedRoot)) {
            return;
        }

        GeneratedFolderSignature signature = GeneratedFolderSignature.capture(generatedRoot);
        Path signaturePath = generatedRoot.resolve(SIGNATURE_RELATIVE_PATH);
        Files.createDirectories(signaturePath.getParent());
        Files.writeString(
                signaturePath,
                signature.toProperties(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private record GeneratedFolderSignature(String treeSha256, List<Entry> entries) {

        private static GeneratedFolderSignature capture(Path generatedRoot) throws IOException {
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
            return new GeneratedFolderSignature(treeSha256(entries), List.copyOf(entries));
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
    }

    private record Entry(String path, String sha256, long bytes) {
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
}
