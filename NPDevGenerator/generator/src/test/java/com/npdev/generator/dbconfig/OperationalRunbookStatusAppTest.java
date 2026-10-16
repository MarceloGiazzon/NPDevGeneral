package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-2: the Java toolbox (App/_ops) is the only real toolbox, so the app-level health probe that
 * ControlPanelHealthController runs by name ({@code Status-App.ps1}) has to be emitted here.
 */
class OperationalRunbookStatusAppTest {

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
    void emitsStatusAppPairThatProbesHealthAndFailsWhenDown(@TempDir Path tempDir) throws Exception {
        Path opsRoot = emit(tempDir);

        Path ps1 = opsRoot.resolve("Status-App.ps1");
        Path sh = opsRoot.resolve("status-app.sh");
        assertTrue(Files.exists(ps1), "Status-App.ps1 must be emitted");
        assertTrue(Files.exists(sh), "status-app.sh must be emitted");

        String ps1Text = Files.readString(ps1);
        assertTrue(ps1Text.contains("/actuator/health"), ps1Text);
        assertTrue(ps1Text.contains("exit 1"), "DOWN must exit non-zero so the Control Panel sees a failure");
        assertTrue(ps1Text.contains("UP   -") && ps1Text.contains("DOWN -"), ps1Text);

        String shText = Files.readString(sh);
        assertTrue(shText.startsWith("#!/bin/sh"), shText);
        assertTrue(shText.contains("/actuator/health") && shText.contains("exit 1"), shText);
    }
}
