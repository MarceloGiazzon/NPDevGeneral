package com.npdev.generator.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class StatefulFlywayPostgresMigrationProofTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("npdev_cp8")
            .withUsername("npdev")
            .withPassword("npdev");

    @TempDir
    Path tempDir;

    @Test
    void versionedAdditiveMigrationMigratesAndValidatesOnPostgres() throws Exception {
        Path migrationDir = tempDir.resolve("db").resolve("migration");

        StatefulMigrationResult result = new StatefulMigrationPlanner().plan(
                new StorageSchemaSnapshot("none", List.of()),
                currentSnapshot(),
                migrationDir,
                new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.SAFE_ADDITIVE, tempDir.resolve("decision.json"))
        );

        assertTrue(result.versionedFlywayMigrationGenerated());
        assertTrue(Files.exists(result.versionedFlywayMigrationPath()));

        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationDir.toAbsolutePath().normalize())
                .cleanDisabled(true)
                .load();

        MigrateResult migrateResult = flyway.migrate();
        flyway.validate();

        assertEquals(1, migrateResult.migrationsExecuted);
        int schemaHistorySuccessRows;
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
             var statement = connection.createStatement();
             var schemaColumns = statement.executeQuery("""
                     select count(*)
                     from information_schema.columns
                     where table_name = 'patients'
                       and column_name in ('id', 'display_name')
                     """)) {
            assertTrue(schemaColumns.next());
            assertEquals(2, schemaColumns.getInt(1));
        }
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
             var statement = connection.createStatement();
             var historyRows = statement.executeQuery("""
                     select count(*)
                     from flyway_schema_history
                     where success = true
                     """)) {
            assertTrue(historyRows.next());
            schemaHistorySuccessRows = historyRows.getInt(1);
            assertTrue(schemaHistorySuccessRows >= 1);
        }

        writeProofArtifact(result.versionedFlywayMigrationPath(), migrateResult.migrationsExecuted, schemaHistorySuccessRows);
    }

    private static StorageSchemaSnapshot currentSnapshot() {
        return new StorageSchemaSnapshot(
                "v2",
                List.of(new StorageTableSchema(
                        "patients",
                        List.of(
                                new StorageColumnSchema("id", "UUID", true, true),
                                new StorageColumnSchema("display_name", "VARCHAR", false, false)
                        )
                ))
        );
    }

    private static void writeProofArtifact(Path migrationPath, int migrationsExecuted, int schemaHistorySuccessRows) throws Exception {
        String proofPath = System.getenv("NPDEV_CP8_FLYWAY_PROOF_PATH");
        if (proofPath == null || proofPath.isBlank()) {
            return;
        }
        Path path = Path.of(proofPath).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        String json = """
                {
                  "generatedAt": "%s",
                  "flywayPostgresMigrationProofPassed": true,
                  "flywayValidateOrMigrateProofPassed": true,
                  "flywaySchemaHistoryVerified": true,
                  "migrationsExecuted": %d,
                  "schemaHistorySuccessRows": %d,
                  "versionedFlywayMigrationPath": "%s"
                }
                """.formatted(
                Instant.now().toString(),
                migrationsExecuted,
                schemaHistorySuccessRows,
                escapeJson(migrationPath.toAbsolutePath().normalize().toString())
        );
        Files.writeString(path, json);
        System.out.println("CP8 Flyway/Postgres proof artifact: " + path);
        System.out.println("CP8 Flyway schema history success rows: " + schemaHistorySuccessRows);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
