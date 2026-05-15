package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulMigrationPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    void additiveOnlyPlanOnlyWritesDryRunSqlWithoutVersionedFlywayMigration() throws Exception {
        Path migrationDir = tempDir.resolve("db").resolve("migration");

        StatefulMigrationResult result = new StatefulMigrationPlanner().plan(
                previousSnapshot(),
                additiveSnapshot(),
                migrationDir,
                new StatefulMigrationOptions("additive-only", true, MigrationRiskThreshold.SAFE_ADDITIVE, null)
        );

        assertTrue(result.passed());
        assertTrue(result.planOnly());
        assertFalse(result.versionedFlywayMigrationGenerated());
        assertTrue(Files.exists(result.dryRunSqlPath()));
        assertTrue(Files.readString(result.dryRunSqlPath()).contains("ADD COLUMN IF NOT EXISTS display_name"));
        assertTrue(Files.exists(result.decisionReportPath()));
        assertTrue(Files.list(migrationDir).noneMatch(path -> path.getFileName().toString().startsWith("V")));
    }

    @Test
    void additiveOnlyGenerationWritesVersionedFlywayMigration() throws Exception {
        Path migrationDir = tempDir.resolve("db").resolve("migration");
        Files.createDirectories(migrationDir);
        Files.writeString(migrationDir.resolve("V5014__existing.sql"), "-- existing\n");

        StatefulMigrationResult result = new StatefulMigrationPlanner().plan(
                previousSnapshot(),
                additiveSnapshot(),
                migrationDir,
                new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.SAFE_ADDITIVE, null)
        );

        assertTrue(result.versionedFlywayMigrationGenerated());
        assertTrue(result.versionedFlywayMigrationPath().getFileName().toString().startsWith("V6000__"));
        assertTrue(Files.readString(result.versionedFlywayMigrationPath()).contains("ADD COLUMN IF NOT EXISTS display_name"));
    }

    @Test
    void additiveOnlyRejectsDestructiveSnapshotChanges() {
        Path migrationDir = tempDir.resolve("db").resolve("migration");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new StatefulMigrationPlanner().plan(
                        previousSnapshot(),
                        destructiveSnapshot(),
                        migrationDir,
                        new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.MANUAL_REVIEW, null)
                )
        );

        assertTrue(exception.getMessage().contains(StatefulMigrationPlanner.ADDITIVE_ONLY_REJECTED));
        assertTrue(exception.getMessage().contains("remove column"));
    }

    @Test
    void riskThresholdAllowsBackfillOnlyWhenConfigured() throws Exception {
        Path migrationDir = tempDir.resolve("db").resolve("migration");

        assertThrows(IllegalStateException.class, () ->
                new StatefulMigrationPlanner().plan(
                        previousSnapshot(),
                        backfillSnapshot(),
                        migrationDir,
                        new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.SAFE_ADDITIVE, null)
                )
        );

        StatefulMigrationResult result = new StatefulMigrationPlanner().plan(
                previousSnapshot(),
                backfillSnapshot(),
                migrationDir,
                new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.BACKFILL_REQUIRED, null)
        );

        assertTrue(result.passed());
        assertTrue(result.versionedFlywayMigrationGenerated());
    }

    @Test
    void decisionReportSeparatesSafeAndBackfillNotNullChanges() throws Exception {
        Path migrationDir = tempDir.resolve("db").resolve("migration");
        Path newTableDecision = tempDir.resolve("new-table-decision.json");

        new StatefulMigrationPlanner().plan(
                new StorageSchemaSnapshot("none", List.of()),
                additiveSnapshot(),
                migrationDir,
                new StatefulMigrationOptions("additive-only", true, MigrationRiskThreshold.SAFE_ADDITIVE, newTableDecision)
        );

        String newTableJson = Files.readString(newTableDecision);
        assertTrue(newTableJson.contains("\"safeNewTableNotNullChanges\""));
        assertTrue(newTableJson.contains("set required on new table patients.id"));
        assertTrue(newTableJson.contains("\"existingTableNotNullBackfillRequiredChanges\" : [ ]"));

        Path backfillDecision = tempDir.resolve("backfill-decision.json");
        assertThrows(IllegalStateException.class, () ->
                new StatefulMigrationPlanner().plan(
                        previousSnapshot(),
                        backfillSnapshot(),
                        migrationDir,
                        new StatefulMigrationOptions("additive-only", true, MigrationRiskThreshold.SAFE_ADDITIVE, backfillDecision)
                )
        );

        String backfillJson = Files.readString(backfillDecision);
        assertTrue(backfillJson.contains("\"existingTableNotNullBackfillRequiredChanges\""));
        assertTrue(backfillJson.contains("tighten required patients.email"));
    }

    private static StorageSchemaSnapshot previousSnapshot() {
        return new StorageSchemaSnapshot(
                "v1",
                List.of(new StorageTableSchema(
                        "patients",
                        List.of(
                                new StorageColumnSchema("id", "UUID", true, true),
                                new StorageColumnSchema("email", "VARCHAR", false, false),
                                new StorageColumnSchema("retired_code", "VARCHAR", false, false)
                        )
                ))
        );
    }

    private static StorageSchemaSnapshot additiveSnapshot() {
        return new StorageSchemaSnapshot(
                "v2",
                List.of(new StorageTableSchema(
                        "patients",
                        List.of(
                                new StorageColumnSchema("id", "UUID", true, true),
                                new StorageColumnSchema("email", "VARCHAR", false, false),
                                new StorageColumnSchema("retired_code", "VARCHAR", false, false),
                                new StorageColumnSchema("display_name", "VARCHAR", false, false)
                        )
                ))
        );
    }

    private static StorageSchemaSnapshot backfillSnapshot() {
        return new StorageSchemaSnapshot(
                "v2",
                List.of(new StorageTableSchema(
                        "patients",
                        List.of(
                                new StorageColumnSchema("id", "UUID", true, true),
                                new StorageColumnSchema("email", "VARCHAR", true, false),
                                new StorageColumnSchema("retired_code", "VARCHAR", false, false)
                        )
                ))
        );
    }

    private static StorageSchemaSnapshot destructiveSnapshot() {
        return new StorageSchemaSnapshot(
                "v2",
                List.of(new StorageTableSchema(
                        "patients",
                        List.of(
                                new StorageColumnSchema("id", "UUID", true, true),
                                new StorageColumnSchema("email", "VARCHAR", false, false)
                        )
                ))
        );
    }
}
