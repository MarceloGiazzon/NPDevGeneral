package com.npdev.dsl.v1.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-3: {@code npdev pack add|update|list|why} exercised directly against the extracted {@code
 * run(String[])} helper each Main class calls from {@code main()} -- same shape {@link
 * ModelValidatorMainReleaseGateTest} already uses, since {@code main()} itself calls {@code
 * System.exit} and is not test-friendly. Fast: no Gradle subprocess.
 */
class PackCliMainTest {

    @TempDir
    Path temp;

    @Test
    void addWritesALockCoveringTheWholeTransitiveGraph() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());

        String out = captureStdout(() -> {
            int exitCode = PackAddMain.run(new String[] {model.toString()});
            assertEquals(0, exitCode);
        });

        assertTrue(out.contains("\"status\" : \"ok\""), "got: " + out);
        assertTrue(Files.isRegularFile(temp.resolve("npdev.lock")));
        String lock = Files.readString(temp.resolve("npdev.lock"));
        assertTrue(lock.contains("\"user\""));
        assertTrue(lock.contains("\"crm\""));
    }

    @Test
    void updateIsTheSameOperationAsAdd() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());
        PackAddMain.run(new String[] {model.toString()});

        int exitCode = PackUpdateMain.run(new String[] {model.toString()});
        assertEquals(0, exitCode);
        assertTrue(Files.isRegularFile(temp.resolve("npdev.lock")));
    }

    @Test
    void listReadsTheCommittedLockWhenOnePresent() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());
        PackAddMain.run(new String[] {model.toString()});

        String out = captureStdout(() -> {
            int exitCode = PackListMain.run(new String[] {model.toString()});
            assertEquals(0, exitCode);
        });
        assertTrue(out.contains("\"locked\" : true"), "got: " + out);
        assertTrue(out.contains("\"user\""));
    }

    @Test
    void listReportsUnlockedAndStillListsALiveDryRunWhenNoLockExists() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());

        String out = captureStdout(() -> {
            int exitCode = PackListMain.run(new String[] {model.toString()});
            assertEquals(0, exitCode);
        });
        assertTrue(out.contains("\"locked\" : false"), "got: " + out);
        assertTrue(out.contains("not locked"), "got: " + out);
        assertTrue(out.contains("\"user\""));
    }

    @Test
    void whyNamesEveryRequirerAndPath() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());

        String out = captureStdout(() -> {
            int exitCode = PackWhyMain.run(new String[] {model.toString(), "user"});
            assertEquals(0, exitCode);
        });
        assertTrue(out.contains("crm"), "got: " + out);
        assertTrue(out.contains("app -> crm"), "got: " + out);
    }

    @Test
    void whyOnAPackNotInTheGraphFails() throws Exception {
        writeDiamondFixtures();
        Path model = write("model.json", modelJson());

        String out = captureStdout(() -> {
            int exitCode = PackWhyMain.run(new String[] {model.toString(), "doesNotExist"});
            assertEquals(2, exitCode);
        });
        assertTrue(out.contains("\"status\" : \"failed\""), "got: " + out);
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
}
