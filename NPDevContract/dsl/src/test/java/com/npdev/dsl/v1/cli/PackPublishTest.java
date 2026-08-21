package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.pack.PackSignature;
import com.npdev.dsl.v1.pack.PackSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-8 Step 6: tests for the {@code --pack-dir} / {@code --output} artifact mode of
 * {@code PackPublishMain}. Verifies that:
 * <ul>
 *   <li>publish produces a valid OCI structure (manifest.json + zip blob)</li>
 *   <li>signing embeds a valid signature in the published pack.json</li>
 *   <li>dry-run prints what would be published without writing</li>
 *   <li>the gate mode (old/new positional args) still works unchanged</li>
 * </ul>
 */
class PackPublishTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    // ---- Artifact mode: basic publish -----------------------------------------------------------

    @Test
    void artifactModeProducesManifestAndBlob() throws Exception {
        Path packDir = createMinimalPackDir("test-pack", "1.0.0");
        Path outputDir = temp.resolve("output");

        String stdout = captureStdout(() -> {
            int exitCode = PackPublishMain.run(new String[]{
                    "--pack-dir", packDir.toString(),
                    "--output", outputDir.toString()
            });
            assertEquals(0, exitCode);
        });

        // Verify manifest exists and is valid JSON
        Path manifestPath = outputDir.resolve("manifest.json");
        assertTrue(Files.exists(manifestPath), "manifest.json must be written");
        JsonNode manifest = MAPPER.readTree(manifestPath.toFile());
        assertEquals(2, manifest.get("schemaVersion").asInt());
        assertTrue(manifest.has("layers"));
        assertTrue(manifest.get("layers").isArray());
        assertEquals(1, manifest.get("layers").size());

        // Verify blob exists
        String blobName = "test-pack-1.0.0.zip";
        Path blobPath = outputDir.resolve(blobName);
        assertTrue(Files.exists(blobPath), "zip blob must be written");
        assertTrue(Files.size(blobPath) > 0, "blob must not be empty");

        // Verify the report
        JsonNode report = MAPPER.readTree(stdout);
        assertEquals("test-pack", report.get("pack").asText());
        assertEquals("1.0.0", report.get("version").asText());
        assertTrue(report.has("digest"));
        assertTrue(report.get("digest").asText().startsWith("sha256:"));
        assertTrue(report.has("blobDigest"));
    }

    // ---- Artifact mode: signing -----------------------------------------------------------------

    @Test
    void artifactModeWithSigningEmbedsSignature() throws Exception {
        Path packDir = createMinimalPackDir("signed-pack", "2.0.0");
        Path outputDir = temp.resolve("output");

        // Generate a keypair
        KeyPair keyPair = PackSigner.generateKeyPair("Ed25519");
        Path keyBase = temp.resolve("signing-key");
        PackSigner.writeKeyPair(keyBase, keyPair);

        String stdout = captureStdout(() -> {
            int exitCode = PackPublishMain.run(new String[]{
                    "--pack-dir", packDir.toString(),
                    "--output", outputDir.toString(),
                    "--sign", keyBase.toString()
            });
            assertEquals(0, exitCode);
        });

        JsonNode report = MAPPER.readTree(stdout);
        assertTrue(report.get("signed").asBoolean());
        assertEquals("Ed25519", report.get("signatureAlgorithm").asText());

        // Extract the blob and verify the signature is in the pack.json
        Path blobPath = outputDir.resolve("signed-pack-2.0.0.zip");
        Path extractDir = temp.resolve("extracted");
        Files.createDirectories(extractDir);
        extractZip(Files.readAllBytes(blobPath), extractDir);

        JsonNode publishedPack = MAPPER.readTree(extractDir.resolve("pack.json").toFile());
        assertTrue(publishedPack.has("signature"), "published pack.json must contain a signature");

        JsonNode sig = publishedPack.get("signature");
        assertEquals("Ed25519", sig.get("algorithm").asText());
        assertTrue(sig.get("digest").asText().startsWith("sha256:"));
        assertNotNull(sig.get("value").asText());
        assertNotNull(sig.get("publicKey").asText());

        // Verify the signature is valid
        PackSignature packSig = new PackSignature(
                sig.get("algorithm").asText(),
                sig.get("digest").asText(),
                sig.get("value").asText(),
                sig.get("publicKey").asText()
        );
        // The signature should verify against its own digest (self-consistent round trip)
        assertTrue(PackSigner.verify(packSig, packSig.digest()),
                "the embedded signature must verify against its own digest");
    }

    // ---- Artifact mode: dry-run -----------------------------------------------------------------

    @Test
    void dryRunDoesNotWriteArtifacts() throws Exception {
        Path packDir = createMinimalPackDir("dryrun-pack", "1.0.0");
        Path outputDir = temp.resolve("output");

        String stdout = captureStdout(() -> {
            int exitCode = PackPublishMain.run(new String[]{
                    "--pack-dir", packDir.toString(),
                    "--output", outputDir.toString(),
                    "--dry-run"
            });
            assertEquals(0, exitCode);
        });

        JsonNode report = MAPPER.readTree(stdout);
        assertTrue(report.get("dryRun").asBoolean());
        assertTrue(report.get("message").asText().contains("Dry run"));

        // No artifacts should have been written
        assertFalse(Files.exists(outputDir.resolve("manifest.json")),
                "dry run must not write manifest.json");
    }

    // ---- Gate mode still works ------------------------------------------------------------------

    @Test
    void gateModeStillWorksForOldNewPackValidation() throws Exception {
        Path oldPack = writeJson("old.json", """
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ] }
                """);
        Path newPack = writeJson("new.json", """
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "nickname", "type": "string", "required": false }
                  ] } ] }
                """);

        String stdout = captureStdout(() -> {
            int exitCode = PackPublishMain.run(new String[]{
                    oldPack.toString(), newPack.toString()
            });
            assertEquals(0, exitCode);
        });

        JsonNode report = MAPPER.readTree(stdout);
        assertTrue(report.get("allowed").asBoolean());
        assertEquals("MINOR", report.get("requiredBump").asText());
    }

    @Test
    void gateModeRefusesInsufficientBump() throws Exception {
        Path oldPack = writeJson("old.json", """
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true }
                  ] } ] }
                """);
        Path newPack = writeJson("new.json", """
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.1",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ] }
                """);

        String stdout = captureStdout(() -> {
            int exitCode = PackPublishMain.run(new String[]{
                    oldPack.toString(), newPack.toString()
            });
            assertEquals(2, exitCode);
        });

        JsonNode report = MAPPER.readTree(stdout);
        assertFalse(report.get("allowed").asBoolean());
    }

    // ---- Digest computation ---------------------------------------------------------------------

    @Test
    void sha256OfTreeIsDeterministicAndStable() throws Exception {
        Path packDir = createMinimalPackDir("stable-pack", "1.0.0");

        String digest1 = PackPublishMain.sha256OfTree(packDir);
        String digest2 = PackPublishMain.sha256OfTree(packDir);

        assertEquals(digest1, digest2, "same tree must produce same digest");
        assertTrue(digest1.startsWith("sha256:"));
        assertEquals(71, digest1.length(), "sha256: prefix + 64 hex chars");
    }

    @Test
    void differentContentProducesDifferentDigest() throws Exception {
        Path packDir1 = createMinimalPackDir("pack-a", "1.0.0");
        Path packDir2 = createMinimalPackDir("pack-b", "1.0.0");
        // Modify packDir2 to have different content
        Files.writeString(packDir2.resolve("extra.txt"), "extra content");

        String digest1 = PackPublishMain.sha256OfTree(packDir1);
        String digest2 = PackPublishMain.sha256OfTree(packDir2);

        assertFalse(digest1.equals(digest2), "different trees must produce different digests");
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private Path createMinimalPackDir(String packId, String version) throws Exception {
        Path packDir = temp.resolve(packId);
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("pack.json"), """
                { "dslVersion": "1.0.0", "pack": "%s", "version": "%s",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ] }
                """.formatted(packId, version));
        return packDir;
    }

    private Path writeJson(String name, String content) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void extractZip(byte[] zipData, Path destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir)) {
                    throw new RuntimeException("zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath);
                }
                zis.closeEntry();
            }
        }
    }
}
