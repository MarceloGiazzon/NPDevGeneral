package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-193: proves the CREATE-TABLE self-heal REG-40 gave business tables now also reaches
 * INTERNAL tables. Before this fix, {@code CREATE TABLE} for an internal table was emitted ONLY
 * into {@code V1__npdev_schema_realization.sql}; Flyway runs a versioned migration exactly once,
 * so on a database that already migrated, a platform-added internal table (e.g.
 * {@code npdev_cron_fire_claim}, added by R2.7/RUN-15) never reached it. The repeatable
 * {@code R__npdev_schema_additive_columns.sql} touched internal tables via
 * {@code appendInternalTableAdditiveColumns}/{@code appendInternalTableAdditiveIndexes}, but
 * neither of those emits CREATE TABLE -- so an ALTER TABLE against a table that does not exist
 * failed at boot (measured live as an HTTP 503 by an unrelated workstream, 2026-08-19).
 *
 * <p>Same two-level proof {@link SchemaRealizationEmitterInternalTableAdditiveIndexesTest}
 * established for R8c's index gap: {@code newInternalTableCreateTextAppearsInTheAdditiveMigration}
 * checks the emitted SQL text; {@code internalTableSelfHealsOnADatabaseThatPredatesItsAddition}
 * goes further and proves the UPGRADE path against a real H2 database -- it realizes a fresh
 * database with current V1__ (so every OTHER internal table exists in its real shape), then DROPS
 * {@code npdev_cron_fire_claim} to reproduce the exact "database migrated before this table was
 * added" precondition, then runs ONLY the current R__ script (never V1__ again, exactly like a
 * real Flyway upgrade boot) and asserts the table exists and is functional.
 */
final class SchemaRealizationEmitterInternalTableCreateTest {

    @TempDir
    Path tempDir;

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        connection.close();
    }

    @Test
    void newInternalTableCreateTextAppearsInTheAdditiveMigration() throws Exception {
        String additiveSql = generateAdditiveSql();
        assertTrue(
                additiveSql.contains("CREATE TABLE IF NOT EXISTS npdev_cron_fire_claim"),
                additiveSql);
        // Ordering rule (REG-40/REG-193): the CREATE TABLE block for internal tables must appear
        // BEFORE their ADD COLUMN block, which in turn must appear before their index block --
        // otherwise an additive column/index could run against a table that does not exist yet.
        int createIndex = additiveSql.indexOf("CREATE TABLE IF NOT EXISTS npdev_cron_fire_claim");
        int alterIndex = additiveSql.indexOf("ALTER TABLE npdev_cron_fire_claim");
        assertTrue(createIndex >= 0 && alterIndex > createIndex,
                "CREATE TABLE must precede any ADD COLUMN for the same internal table: " + additiveSql);
    }

    @Test
    void internalTableSelfHealsOnADatabaseThatPredatesItsAddition() throws Exception {
        // Step 1: realize a genuinely fresh database with CURRENT code's V1__ -- creates every
        // internal table (including npdev_cron_fire_claim) in its real, current shape.
        Path outRoot = tempDir.resolve("fresh-" + System.nanoTime());
        CompiledConcept lonelyConcept = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(lonelyConcept.getName(), lonelyConcept));
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model-fresh.json"));
        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        RunScript.execute(connection, new StringReader(
                Files.readString(schemaDir.resolve("V1__npdev_schema_realization.sql"))));

        // Step 2: drop JUST npdev_cron_fire_claim -- reproducing the exact precondition of a real
        // database that ran V1__ BEFORE this table was added to NpdevInternalTables.all() (its
        // real history: R2.7/RUN-15 added it after many apps had already migrated). This is
        // deliberately a raw DROP, not something SchemaRealizationEmitter can express, because the
        // whole point of an additive-migration proof is that the "before" state is independent of
        // the fix being tested.
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE npdev_cron_fire_claim");
        }
        assertFalse(hasTable("NPDEV_CRON_FIRE_CLAIM"), "precondition: table must be absent before the R__ run");

        // Step 3: run ONLY the repeatable additive migration -- never V1__ again, exactly what
        // Flyway does on a real upgrade boot against an already-migrated database.
        String additiveSql = generateAdditiveSql();
        RunScript.execute(connection, new StringReader(additiveSql));

        // Step 4: the table now exists on the SAME database, with no destructive recreation of
        // anything else, and is genuinely usable -- an ordinary write round-trips.
        assertTrue(hasTable("NPDEV_CRON_FIRE_CLAIM"),
                "npdev_cron_fire_claim must self-heal onto an already-migrated database -- REG-193");
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO npdev_cron_fire_claim "
                        + "(flow_name, tenant_id, scheduled_fire_time, created_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, "TestFlow");
            insert.setString(2, "tenant-1");
            insert.setTimestamp(3, Timestamp.from(Instant.parse("2026-08-19T00:00:00Z")));
            insert.setTimestamp(4, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT tenant_id FROM npdev_cron_fire_claim WHERE flow_name = ?")) {
            select.setString(1, "TestFlow");
            try (ResultSet resultSet = select.executeQuery()) {
                assertTrue(resultSet.next());
                assertTrue("tenant-1".equals(resultSet.getString(1)));
            }
        }
    }

    private boolean hasTable(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String generateAdditiveSql() throws Exception {
        CompiledConcept lonelyConcept = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(lonelyConcept.getName(), lonelyConcept));
        Path outRoot = tempDir.resolve("app-" + System.nanoTime());
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));
        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        return Files.readString(schemaDir.resolve("R__npdev_schema_additive_columns.sql"));
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "internal-create-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "internal-create-test",
                "internal-create-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:internal-create-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                false,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "test-fingerprint",
                tempDir.resolve("database.json"),
                List.of("test")
        );
    }
}
