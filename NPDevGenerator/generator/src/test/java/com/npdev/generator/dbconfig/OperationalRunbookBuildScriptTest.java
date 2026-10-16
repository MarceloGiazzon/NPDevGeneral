package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-5: the toolbox's Build-FinalApp defaults to an incremental warm-daemon {@code bootJar} with no
 * test suite; the old full run is one flag away.
 */
class OperationalRunbookBuildScriptTest {

    private static Path emit(Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Path definition = src.resolve("db.definition.json");
        Files.writeString(definition, """
                {
                  "database": { "engine": "Postgres", "host": "localhost", "port": 5432, "username": "npdev",
                                 "password": "secret", "createInternalTables": true, "createBusinessTables": true,
                                 "externallyProvisioned": false },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """);
        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definition, null);
        Path appRoot = tempDir.resolve("FinalApp");
        Files.createDirectories(appRoot);
        return new OperationalRunbookEmitter().emit(null, null, appRoot, plan);
    }

    @Test
    void powershellBuildIsIncrementalBootJarWithTestsOptIn(@TempDir Path tempDir) throws Exception {
        String ps1 = Files.readString(emit(tempDir).resolve("Build-FinalApp.ps1"));

        assertTrue(ps1.contains("[switch]$RunTests"), ps1);
        assertTrue(ps1.contains("[switch]$Clean"), ps1);
        assertTrue(ps1.contains("bootJar"), ps1);
        assertTrue(ps1.contains("Build finished in"), ps1);
        assertTrue(ps1.contains("'.\\gradlew.bat'"), "backslash must be emitted single: " + ps1);
        assertFalse(ps1.contains("@('--no-daemon')"), "--no-daemon must not be unconditional: " + ps1);
        assertTrue(ps1.contains("if ($NoDaemon -or $env:CI)"), ps1);
        // determinism: no machine paths or timestamps in the emitted text
        assertFalse(ps1.contains(tempDir.toString()), ps1);
    }

    @Test
    void posixBuildMirrorsTheSameSemantics(@TempDir Path tempDir) throws Exception {
        String sh = Files.readString(emit(tempDir).resolve("build-final-app.sh"));

        assertTrue(sh.startsWith("#!/bin/sh"), sh);
        assertTrue(sh.contains("NPDEV_RUN_TESTS"), sh);
        assertTrue(sh.contains("NPDEV_CLEAN"), sh);
        assertTrue(sh.contains("bootJar"), sh);
        assertFalse(sh.contains("exec ./gradlew"), sh);
    }
}
