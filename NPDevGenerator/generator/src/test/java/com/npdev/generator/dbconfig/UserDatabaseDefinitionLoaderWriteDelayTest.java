package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-57 (docs/NPDEV_OPEN_ITEMS_REGISTER.md): H2's MVStore defaults to a 500ms {@code WRITE_DELAY},
 * buffering committed writes before flushing to disk -- a hard kill inside that window can lose
 * commits the caller was already told succeeded (a durable flow's WAITING_EVENT checkpoint,
 * reproduced live 3/3 at near-zero delay). The real proof is {@code run-durable-resume-demo.ps1}
 * passing with its workaround sleep removed (this fix's actual completion criterion); this test is
 * the cheap, fast regression guard against the URL construction silently losing the parameter.
 */
class UserDatabaseDefinitionLoaderWriteDelayTest {

    @Test
    void h2LocalUrlForcesWriteDelayZero(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """);

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);

        assertTrue(plan.jdbcUrl().contains(";WRITE_DELAY=0"), plan.jdbcUrl());
    }

    @Test
    void h2ServerUrlForcesWriteDelayZero(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Server", "host": "localhost", "port": 9200,
                                 "username": "sa", "password": "",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """);

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);

        assertTrue(plan.jdbcUrl().contains(";WRITE_DELAY=0"), plan.jdbcUrl());
    }

    private static Path writeDefinition(Path tempDir, String json) throws Exception {
        Path path = tempDir.resolve("db-definition.json");
        java.nio.file.Files.writeString(path, json);
        return path;
    }
}
