package com.npdev.generator.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class IncrementalMigrationHarnessTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("npdev_cp9")
            .withUsername("npdev")
            .withPassword("npdev");

    @TempDir
    Path tempDir;

    @Test
    void fiveUpgradeScenariosPreserveDataOnPostgres() throws Exception {
        List<Map<String, Object>> scenarioProofs = new ArrayList<>();
        Path scenarioMigrationProofDir = proofSiblingDirectory("NPDEV_CP9_INCREMENTAL_PROOF_PATH", "scenario-migrations");
        for (UpgradeScenario scenario : scenarios()) {
            scenarioProofs.add(runScenario(scenario, scenarioMigrationProofDir));
        }

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("schemaVersion", "npdev-cp9-incremental-migration-proof.v1");
        proof.put("postgresTestcontainersUsed", true);
        proof.put("scenarioCount", scenarioProofs.size());
        proof.put("baselineSchemaApplied", true);
        proof.put("preMigrationDataInserted", true);
        proof.put("newMigrationApplied", true);
        proof.put("dataPreservationVerified", true);
        proof.put("flywaySchemaHistoryVerified", true);
        proof.put("scenarios", scenarioProofs);
        writeProof("NPDEV_CP9_INCREMENTAL_PROOF_PATH", proof);
    }

    @Test
    void unsafeExistingTableNotNullTighteningFailsGracefully() throws Exception {
        Path migrationDir = tempDir.resolve("unsafe-backfill").resolve("db").resolve("migration");
        Path decision = tempDir.resolve("unsafe-backfill").resolve("decision.json");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new StatefulMigrationPlanner().plan(
                        patientBaseline(false),
                        patientBaseline(true),
                        migrationDir,
                        new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.SAFE_ADDITIVE, decision)
                )
        );

        String decisionJson = Files.readString(decision);
        assertTrue(exception.getMessage().contains(StatefulMigrationPlanner.ADDITIVE_ONLY_REJECTED));
        assertTrue(exception.getMessage().contains("BACKFILL_REQUIRED"));
        assertTrue(decisionJson.contains("tighten required patients.email"));
        assertTrue(decisionJson.contains("\"allowed\" : false"));
        assertFalse(Files.exists(migrationDir.resolve("V6000__npdev_model_delta.sql")));

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("schemaVersion", "npdev-cp9-unsafe-backfill-proof.v1");
        proof.put("unsafeBackfillFailsGracefully", true);
        proof.put("errorCode", StatefulMigrationPlanner.ADDITIVE_ONLY_REJECTED);
        proof.put("risk", "BACKFILL_REQUIRED");
        proof.put("clearErrorContainsBackfillChange", true);
        proof.put("decisionReportPath", decision.toAbsolutePath().normalize().toString());
        writeProof("NPDEV_CP9_UNSAFE_PROOF_PATH", proof);
    }

    private Map<String, Object> runScenario(UpgradeScenario scenario, Path scenarioMigrationProofDir) throws Exception {
        String schema = "cp9_" + scenario.name().replaceAll("[^a-z0-9_]", "_");
        Path migrationDir = tempDir.resolve(scenario.name()).resolve("db").resolve("migration");

        try (Connection connection = connection()) {
            execute(connection, "drop schema if exists " + schema + " cascade");
            execute(connection, "create schema " + schema);
            execute(connection, "set search_path to " + schema);
            execute(connection, scenario.baselineSql());
            execute(connection, scenario.insertSql());
        }

        StatefulMigrationResult result = new StatefulMigrationPlanner().plan(
                scenario.previous(),
                scenario.current(),
                migrationDir,
                new StatefulMigrationOptions("additive-only", false, MigrationRiskThreshold.SAFE_ADDITIVE, tempDir.resolve(scenario.name() + "-decision.json"))
        );

        assertTrue(result.versionedFlywayMigrationGenerated(), scenario.name());
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("filesystem:" + migrationDir.toAbsolutePath().normalize())
                .baselineOnMigrate(true)
                .cleanDisabled(true)
                .load();
        var migrateResult = flyway.migrate();
        flyway.validate();

        try (Connection connection = connection()) {
            execute(connection, "set search_path to " + schema);
            assertEquals(1, count(connection, scenario.preservationQuery()), scenario.name());
            assertTrue(count(connection, "select count(*) from flyway_schema_history where success = true") >= 1, scenario.name());
            if (!scenario.extraVerificationQuery().isBlank()) {
                assertEquals(1, count(connection, scenario.extraVerificationQuery()), scenario.name());
            }
        }

        Map<String, Object> proof = new LinkedHashMap<>();
        Path migrationArtifact = copyScenarioMigration(scenarioMigrationProofDir, scenario.name(), result.versionedFlywayMigrationPath());
        proof.put("name", scenario.name());
        proof.put("baselineSchemaApplied", true);
        proof.put("preMigrationDataInserted", true);
        proof.put("newMigrationApplied", migrateResult.migrationsExecuted >= 1);
        proof.put("migrationsExecuted", migrateResult.migrationsExecuted);
        proof.put("dataPreserved", true);
        proof.put("versionedMigration", result.versionedFlywayMigrationPath().toAbsolutePath().normalize().toString());
        proof.put("versionedMigrationArtifact", migrationArtifact == null ? "" : migrationArtifact.toAbsolutePath().normalize().toString());
        return proof;
    }

    private static List<UpgradeScenario> scenarios() {
        return List.of(
                new UpgradeScenario(
                        "add_nullable_display_name",
                        patientBaseline(false),
                        patientWithColumns(false, new StorageColumnSchema("display_name", "VARCHAR", false, false)),
                        "create table patients (id uuid primary key, email varchar(255));",
                        "insert into patients (id, email) values ('00000000-0000-0000-0000-000000000001', 'a@example.test');",
                        "select count(*) from patients where id = '00000000-0000-0000-0000-000000000001' and email = 'a@example.test'",
                        "select count(*) from information_schema.columns where table_name = 'patients' and column_name = 'display_name'"
                ),
                new UpgradeScenario(
                        "add_integer_score",
                        patientBaseline(false),
                        patientWithColumns(false, new StorageColumnSchema("risk_score", "INTEGER", false, false)),
                        "create table patients (id uuid primary key, email varchar(255));",
                        "insert into patients (id, email) values ('00000000-0000-0000-0000-000000000002', 'b@example.test');",
                        "select count(*) from patients where id = '00000000-0000-0000-0000-000000000002' and email = 'b@example.test'",
                        "select count(*) from information_schema.columns where table_name = 'patients' and column_name = 'risk_score'"
                ),
                new UpgradeScenario(
                        "add_unique_external_id",
                        patientBaseline(false),
                        patientWithColumns(false, new StorageColumnSchema("external_id", "VARCHAR", false, true)),
                        "create table patients (id uuid primary key, email varchar(255));",
                        "insert into patients (id, email) values ('00000000-0000-0000-0000-000000000003', 'c@example.test');",
                        "select count(*) from patients where id = '00000000-0000-0000-0000-000000000003' and email = 'c@example.test'",
                        "select count(*) from pg_indexes where schemaname = current_schema() and indexname = 'ux_patients_external_id'"
                ),
                new UpgradeScenario(
                        "add_new_required_table",
                        patientBaseline(false),
                        new StorageSchemaSnapshot("v2", List.of(
                                patientTable(false),
                                new StorageTableSchema("appointments", List.of(
                                        new StorageColumnSchema("id", "UUID", true, true),
                                        new StorageColumnSchema("patient_id", "UUID", true, false),
                                        new StorageColumnSchema("scheduled_at", "TIMESTAMP WITH TIME ZONE", true, false),
                                        new StorageColumnSchema("note", "VARCHAR", false, false)
                                ))
                        )),
                        "create table patients (id uuid primary key, email varchar(255));",
                        "insert into patients (id, email) values ('00000000-0000-0000-0000-000000000004', 'd@example.test');",
                        "select count(*) from patients where id = '00000000-0000-0000-0000-000000000004' and email = 'd@example.test'",
                        "select count(*) from information_schema.tables where table_name = 'appointments'"
                ),
                new UpgradeScenario(
                        "add_multiple_optional_columns",
                        patientBaseline(false),
                        patientWithColumns(
                                false,
                                new StorageColumnSchema("preferred_language", "VARCHAR", false, false),
                                new StorageColumnSchema("active", "BOOLEAN", false, false)
                        ),
                        "create table patients (id uuid primary key, email varchar(255));",
                        "insert into patients (id, email) values ('00000000-0000-0000-0000-000000000005', 'e@example.test');",
                        "select count(*) from patients where id = '00000000-0000-0000-0000-000000000005' and email = 'e@example.test'",
                        "select case when count(*) = 2 then 1 else 0 end from information_schema.columns where table_name = 'patients' and column_name in ('preferred_language', 'active')"
                )
        );
    }

    private static StorageSchemaSnapshot patientBaseline(boolean emailRequired) {
        return new StorageSchemaSnapshot("v1", List.of(patientTable(emailRequired)));
    }

    private static StorageSchemaSnapshot patientWithColumns(boolean emailRequired, StorageColumnSchema... columns) {
        List<StorageColumnSchema> allColumns = new ArrayList<>(patientTable(emailRequired).columns());
        allColumns.addAll(List.of(columns));
        return new StorageSchemaSnapshot("v2", List.of(new StorageTableSchema("patients", allColumns)));
    }

    private static StorageTableSchema patientTable(boolean emailRequired) {
        return new StorageTableSchema("patients", List.of(
                new StorageColumnSchema("id", "UUID", true, true),
                new StorageColumnSchema("email", "VARCHAR", emailRequired, false)
        ));
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static void writeProof(String envName, Map<String, Object> proof) throws Exception {
        String proofPath = System.getenv(envName);
        if (proofPath == null || proofPath.isBlank()) {
            return;
        }
        Path path = Path.of(proofPath).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.writeString(path, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(proof) + System.lineSeparator());
        System.out.println("CP9 proof artifact: " + path);
    }

    private static Path proofSiblingDirectory(String envName, String directoryName) throws Exception {
        String proofPath = System.getenv(envName);
        if (proofPath == null || proofPath.isBlank()) {
            return null;
        }
        Path parent = Path.of(proofPath).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return null;
        }
        Path directory = parent.resolve(directoryName);
        Files.createDirectories(directory);
        return directory;
    }

    private static Path copyScenarioMigration(Path proofDir, String scenarioName, Path migrationPath) throws Exception {
        if (proofDir == null || migrationPath == null || !Files.exists(migrationPath)) {
            return null;
        }
        Path targetDir = proofDir.resolve(scenarioName);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(migrationPath.getFileName().toString());
        Files.copy(migrationPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private record UpgradeScenario(
            String name,
            StorageSchemaSnapshot previous,
            StorageSchemaSnapshot current,
            String baselineSql,
            String insertSql,
            String preservationQuery,
            String extraVerificationQuery
    ) {
    }
}
