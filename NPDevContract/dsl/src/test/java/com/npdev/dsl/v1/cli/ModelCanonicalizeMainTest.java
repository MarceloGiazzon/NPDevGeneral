package com.npdev.dsl.v1.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 4 (2026-09-25): {@code npdev} monitor's model-sync feature needs a pack-resolved candidate
 * to POST at {@code ModelSyncStatusController} -- these tests are the proof that
 * {@link ModelCanonicalizeMain#run} actually composes {@code packs[]} rather than echoing the raw
 * authoring file back (which would defeat the whole point: the deployed side is always the
 * pack-resolved form).
 */
class ModelCanonicalizeMainTest {

    @TempDir
    Path temp;

    @Test
    void resolvesPackReferencesIntoTheOutput() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());
        // crm -> user is a transitive pack dependency, which resolve() refuses without a
        // committed npdev.lock (the same refusal a real app's own generation would hit) --
        // PackAddMain.run writes it, same as PackCliMainTest's own fixtures do.
        PackAddMain.run(new String[] {model.toString()});

        String out = captureStdout(() -> {
            int exitCode = ModelCanonicalizeMain.run(new String[] {model.toString()});
            assertEquals(0, exitCode);
        });

        // The raw authoring file only ever says "packs": [ {"$ref": ...} ] -- if the (qualified)
        // concepts packs/crm and packs/user contribute are not in the output, nothing was
        // actually resolved and this tool would be no better than `cat model.json`.
        assertTrue(out.contains("\"crm::Lead\""), "got: " + out);
        assertTrue(out.contains("\"user::Account\""), "got: " + out);
        assertFalse(out.contains("$ref"), "resolved output should not still carry a $ref: " + out);
    }

    @Test
    void writesToOutFileWhenGiven() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());
        PackAddMain.run(new String[] {model.toString()});
        Path out = temp.resolve("canonical.json");

        int exitCode = ModelCanonicalizeMain.run(new String[] {model.toString(), "--out", out.toString()});

        assertEquals(0, exitCode);
        assertTrue(Files.isRegularFile(out));
        String written = Files.readString(out);
        assertTrue(written.contains("\"crm::Lead\""), "got: " + written);
    }

    @Test
    void missingModelFailsWithExitTwoAndNoOutput() throws Exception {
        Path missing = temp.resolve("does-not-exist.json");

        String err = captureStderr(() -> {
            int exitCode = ModelCanonicalizeMain.run(new String[] {missing.toString()});
            assertEquals(2, exitCode);
        });
        assertTrue(err.contains("failed to resolve"), "got: " + err);
    }

    @Test
    void noModelArgIsAUsageError() {
        int exitCode = ModelCanonicalizeMain.run(new String[] {});
        assertEquals(64, exitCode);
    }

    private String modelJson() {
        return """
                {
                  "namespace": "cli.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/crm/pack.json" } ]
                }
                """;
    }

    private void writeDiamondFixtures() throws Exception {
        write("packs/user/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "user",
                  "version": "2.0.0",
                  "concepts": [ { "name": "Account", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ]
                }
                """);
        write("packs/crm/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "crm",
                  "version": "1.0.0",
                  "packs": [ { "pack": "user", "version": "^2.0" } ],
                  "concepts": [ { "name": "Lead", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ]
                }
                """);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
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

    private String captureStderr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
