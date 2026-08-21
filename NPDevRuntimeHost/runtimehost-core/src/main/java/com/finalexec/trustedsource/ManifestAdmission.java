package com.finalexec.trustedsource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Wave 4B / trusted-source pipeline: verifies that source files match their manifest hashes
 * (SHA-256). Every file referenced by the manifest must exist and match byte-for-byte.
 */
public class ManifestAdmission {

    /**
     * Verifies that all files in {@code manifestDir} match the expected SHA-256 hashes.
     *
     * @param manifestDir    the root directory containing the source files
     * @param expectedHashes map of relative-path → expected SHA-256 hex digest
     * @throws SecurityException if any file is missing or its hash does not match
     * @throws IOException       if a file cannot be read
     */
    public void verify(Path manifestDir, Map<String, String> expectedHashes)
            throws IOException {
        for (Map.Entry<String, String> entry : expectedHashes.entrySet()) {
            Path sourceFile = manifestDir.resolve(entry.getKey());
            if (!Files.exists(sourceFile)) {
                throw new SecurityException(
                        "Manifest references missing file: " + entry.getKey());
            }
            String actualHash = sha256(sourceFile);
            if (!actualHash.equals(entry.getValue())) {
                throw new SecurityException(
                        "Hash mismatch for " + entry.getKey()
                                + ": expected " + entry.getValue() + ", got " + actualHash);
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
