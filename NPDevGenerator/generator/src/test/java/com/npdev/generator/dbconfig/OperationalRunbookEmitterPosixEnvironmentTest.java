package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 (Cold Clone Audit): the POSIX twins of the eight {@code _ops} environment/build/smoke-test
 * scripts ({@code create-environment.sh}, {@code start-environment.sh}, {@code stop-environment.sh},
 * {@code status-environment.sh}, {@code build-final-app.sh}, {@code smoke-test.sh},
 * {@code print-db-connection-info.sh}, {@code reset-environment.sh}).
 *
 * <p>Two kinds of check. The content assertions run everywhere and lock the behaviour that matters
 * most (STOR-14's externally-provisioned refusal, appearing first and returning, on every script;
 * reset's confirmation-token guard). The {@code sh -n} syntax check runs only when {@code sh} is on
 * PATH ({@link Assumptions#assumeTrue} skips it cleanly otherwise, e.g. a Windows CI agent with no
 * POSIX shell at all) -- it is the one thing in this test class that can catch a real shell syntax
 * error, since nothing here can spin up Docker/Postgres/MySQL/SqlServer to prove the scripts actually
 * work end to end. That real proof is item 30 of the Cold Clone audit: a run on a machine that has
 * never seen NPDev.
 */
class OperationalRunbookEmitterPosixEnvironmentTest {

    private static final List<String> POSIX_SCRIPT_NAMES = List.of(
            "create-environment.sh", "start-environment.sh", "stop-environment.sh",
            "status-environment.sh", "build-final-app.sh", "smoke-test.sh",
            "print-db-connection-info.sh", "reset-environment.sh");

    private static Path writeServerDefinition(Path directory, String engine, int port, boolean externallyProvisioned)
            throws Exception {
        Files.createDirectories(directory);
        Path path = directory.resolve("db.definition.json");
        Files.writeString(path, """
                {
                  "database": { "engine": "%s", "host": "localhost", "port": %d, "username": "npdev",
                                 "password": "secret", "createInternalTables": true, "createBusinessTables": true,
                                 "externallyProvisioned": %s },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """.formatted(engine, port, externallyProvisioned));
        return path;
    }

    private static Path emit(Path tempDir, String engine, int port, boolean externallyProvisioned) throws Exception {
        Path definitionPath = writeServerDefinition(tempDir.resolve("src"), engine, port, externallyProvisioned);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);
        Path appRoot = tempDir.resolve("FinalApp");
        Files.createDirectories(appRoot);
        Path opsRoot = new OperationalRunbookEmitter().emit(null, null, appRoot, plan);
        assertTrue(Files.exists(opsRoot));
        return opsRoot;
    }

    @Test
    void emitsAllEightPosixEnvironmentScripts(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "Postgres", 5432, false);

        for (String name : POSIX_SCRIPT_NAMES) {
            Path script = opsRoot.resolve(name);
            assertTrue(Files.exists(script), name + " must be emitted");
            String content = Files.readString(script);
            assertTrue(content.startsWith("#!/bin/sh"), name + " must start with a POSIX shebang: " + content);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Postgres", "MySQL", "SqlServer", "H2Server"})
    void syntaxCheckEveryScriptOnEveryServerEngine(String engine, @TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(shOnPath(), "sh not on PATH -- skipping syntax check");
        int port = switch (engine) {
            case "MySQL" -> 3306;
            case "SqlServer" -> 1433;
            case "H2Server" -> 9092;
            default -> 5432;
        };
        Path opsRoot = emit(tempDir, engine, port, false);

        for (String name : POSIX_SCRIPT_NAMES) {
            assertShSyntaxOk(opsRoot.resolve(name));
        }
    }

    @Test
    void syntaxCheckEveryScriptWhenExternallyProvisioned(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(shOnPath(), "sh not on PATH -- skipping syntax check");
        Path opsRoot = emit(tempDir, "Postgres", 5432, true);

        for (String name : POSIX_SCRIPT_NAMES) {
            assertShSyntaxOk(opsRoot.resolve(name));
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "STOR-14: every environment script refuses externally-provisioned BEFORE anything destructive")
    void everyEnvironmentScriptChecksExternallyProvisionedFirst(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "Postgres", 5432, true);

        for (String name : List.of("create-environment.sh", "start-environment.sh", "stop-environment.sh",
                "status-environment.sh", "reset-environment.sh")) {
            String content = Files.readString(opsRoot.resolve(name));
            int refusalCheck = content.indexOf("externallyProvisioned");
            assertTrue(refusalCheck >= 0, name + " must check externallyProvisioned: " + content);
            int firstDockerCall = content.indexOf("docker ");
            if (firstDockerCall >= 0) {
                assertTrue(refusalCheck < firstDockerCall,
                        name + " must check externallyProvisioned BEFORE any docker command: " + content);
            }
            int firstRm = content.indexOf("rm -rf");
            if (firstRm >= 0) {
                assertTrue(refusalCheck < firstRm,
                        name + " must check externallyProvisioned BEFORE any recursive delete: " + content);
            }
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("reset-environment.sh refuses without the exact confirmation token")
    void resetRefusesWithoutConfirmationToken(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "Postgres", 5432, false);
        String content = Files.readString(opsRoot.resolve("reset-environment.sh"));

        assertTrue(content.contains("I_UNDERSTAND_DB_DATA_WILL_BE_DELETED"), content);
        assertTrue(content.contains("Reset refused"), content);
        // The confirmation-token IF must come AFTER the externally-provisioned check and its exit,
        // and BEFORE the destructive rm -rf -- the exact ordering STOR-14's own comment insists on.
        // (CONFIRM itself is assigned earlier, from $1, which is not the ordering under test here.)
        int externalCheck = content.indexOf("externallyProvisioned");
        int confirmIf = content.indexOf("\"$CONFIRM\" !=");
        int destructiveDelete = content.indexOf("rm -rf");
        assertTrue(confirmIf >= 0, content);
        assertTrue(externalCheck < confirmIf, content);
        assertTrue(confirmIf < destructiveDelete, content);
    }

    @Test
    void printDbConnectionInfoNamesTheRightScriptForH2Server(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir, "H2Server", 9092, false);
        String content = Files.readString(opsRoot.resolve("print-db-connection-info.sh"));
        assertTrue(content.contains("create-environment.sh starts it with"), content);
    }

    @Test
    void readmeDocumentsThePosixTwinsThatAreNumberedRunbookSteps(@TempDir Path tempDir) throws Exception {
        // start-environment.sh and status-environment.sh are excluded: neither Start-Environment.ps1
        // nor Status-Environment.ps1 was ever its own numbered runbook step either (both are internal
        // helpers -- `npdev db start`/`npdev db status` and Create-Environment/Reset-Environment
        // invoke them directly), so their POSIX twins have nothing to be documented alongside here.
        Path opsRoot = emit(tempDir, "Postgres", 5432, false);
        String readme = Files.readString(opsRoot.resolve("README_RUNBOOK.md"));

        for (String name : POSIX_SCRIPT_NAMES) {
            if (name.equals("start-environment.sh") || name.equals("status-environment.sh")) {
                continue;
            }
            assertTrue(readme.contains("./" + name), "README_RUNBOOK.md must document " + name + ":\n" + readme);
        }
    }

    private static boolean shOnPath() {
        return new java.io.File("/bin/sh").exists() || new java.io.File("/usr/bin/sh").exists()
                || System.getenv("PATH") != null && java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                        .anyMatch(dir -> new java.io.File(dir, "sh").exists() || new java.io.File(dir, "sh.exe").exists());
    }

    private static void assertShSyntaxOk(Path script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-n", script.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        assertEquals(0, exit, "sh -n " + script.getFileName() + " failed:\n" + output);
    }
}
