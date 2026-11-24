package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-359 (pack:signature): {@code pack add|update} must really verify the signature a LOCAL pack carries. Two defects
 * hid each other: the signed digest was circular (see PackSigningDigestTest), and the check opened the pack through
 * the lock entry's model-relative sourcePath from the working directory, so every local pack skipped it silently.
 */
class PackAddSignatureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aSignedLocalPackIsVerifiedAndATamperedOneIsRefused(@TempDir Path root) throws Exception {
        Path model = write(root.resolve("app/model.json"), """
                { "namespace": "sig.test", "dslVersion": "3.0.0", "version": "1.0",
                  "packs": [ { "$ref": "packs/demo/pack.json" } ] }
                """);
        Path packDir = root.resolve("app/packs/demo");
        write(packDir.resolve("pack.json"), """
                {
                  "dslVersion": "3.0.0",
                  "pack": "demo",
                  "version": "1.0.0",
                  "concepts": [ { "name": "Note", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        write(packDir.resolve("NOTES.txt"), "part of the pack tree");
        Path key = root.resolve("keys/signer");
        Files.createDirectories(key.getParent());
        assertEquals(0, PackSignMainTestAccess.run(new String[] {"--pack-dir", packDir.toString(), "--key", key.toString(), "--generate-key"}));
        Path report = root.resolve("report.json");

        assertEquals(0, PackAddMain.run(new String[] {model.toString(), "--out", report.toString()}));
        JsonNode ok = MAPPER.readTree(report.toFile());
        assertEquals("ok", ok.get("status").asText());
        assertTrue(ok.get("signatureWarnings").toString().contains("OK: pack 'demo' signature verified"),
                "a signed local pack must be verified, not skipped: " + ok.get("signatureWarnings"));

        write(packDir.resolve("NOTES.txt"), "tampered after signing");

        assertNotEquals(0, PackAddMain.run(new String[] {model.toString(), "--out", report.toString()}));
        JsonNode refused = MAPPER.readTree(report.toFile());
        assertEquals("failed", refused.get("status").asText());
        assertTrue(refused.get("error").asText().contains("signature verification failed"), refused.get("error").asText());
    }
}
