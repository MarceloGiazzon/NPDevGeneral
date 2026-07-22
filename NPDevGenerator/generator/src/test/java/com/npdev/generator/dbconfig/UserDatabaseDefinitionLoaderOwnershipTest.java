package com.npdev.generator.dbconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-7.1: {@code schemaLifecycle.ownership} parsing, defaulting, and generation-time validation.
 * Drives the real {@link UserDatabaseDefinitionLoader#load} against a real db-definition JSON file on
 * disk (no mocking) -- the same path {@code GeneratorMain} uses.
 */
class UserDatabaseDefinitionLoaderOwnershipTest {

    @Test
    void ownershipAbsentDefaultsToNpdevManaged(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
                }
                """);

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);

        assertEquals(DatabaseOwnership.NPDEV_MANAGED, plan.schemaLifecycle().ownership());
        assertFalse(plan.schemaLifecycle().externallyManaged());
    }

    @Test
    void externallyManagedWithCompatibleStrategyLoadsCleanly(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly",
                                        "ownership": "ExternallyManaged" }
                }
                """);

        GeneratedDatabasePlan plan = new UserDatabaseDefinitionLoader().load(definitionPath, null);

        assertEquals(DatabaseOwnership.EXTERNALLY_MANAGED, plan.schemaLifecycle().ownership());
        assertTrue(plan.schemaLifecycle().externallyManaged());
    }

    @Test
    void externallyManagedWithRecreateStrategyRefusesAtGenerationTime(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "RecreateOnAppStart", "allowDestructiveRecreate": false,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly",
                                        "ownership": "ExternallyManaged" }
                }
                """);

        UserDatabaseDefinitionLoader loader = new UserDatabaseDefinitionLoader();
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> loader.load(definitionPath, null));
        assertTrue(exception.getMessage().contains("ExternallyManaged"), exception.getMessage());
    }

    @Test
    void externallyManagedWithAllowDestructiveRecreateRefusesAtGenerationTime(@TempDir Path tempDir) throws Exception {
        Path definitionPath = writeDefinition(tempDir, """
                {
                  "database": { "engine": "H2Local", "username": "test", "password": "test",
                                 "createInternalTables": true, "createBusinessTables": true },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": true,
                                        "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly",
                                        "ownership": "ExternallyManaged" }
                }
                """);

        UserDatabaseDefinitionLoader loader = new UserDatabaseDefinitionLoader();
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> loader.load(definitionPath, null));
        assertTrue(exception.getMessage().contains("allowDestructiveRecreate"), exception.getMessage());
    }

    private static Path writeDefinition(Path tempDir, String json) throws Exception {
        Path appDir = Files.createDirectories(tempDir.resolve("app"));
        Path definitionPath = appDir.resolve("db.definition.json");
        Files.writeString(definitionPath, json);
        return definitionPath;
    }
}
