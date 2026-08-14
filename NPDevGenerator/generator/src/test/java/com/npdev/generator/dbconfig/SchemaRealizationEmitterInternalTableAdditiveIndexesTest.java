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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8c (RUN-2's resume-claim mechanism): proves the additive-migration blocker the R8c card named
 * ("internal-table indexes have no additive-migration path") is CLOSED, not merely that the
 * emitter's SQL TEXT mentions the right statement.
 *
 * <p>{@code newIndexTextAppearsInTheAdditiveMigration} is the same level of proof {@link
 * SchemaRealizationEmitterInternalTableAdditiveColumnsTest} already established for columns.
 * {@code claimColumnsAndIndexLandOnADatabaseThatPredatesR8c} goes one level further, per this
 * session's own "boot-once proves nothing" / "prove the UPGRADE path, not just fresh creation"
 * discipline: it hand-builds {@code npdev_flow_instance} in the EXACT shape it had on a real,
 * already-deployed database before this change (no {@code claimed_by}/{@code claimed_until}
 * columns, no claim index), then runs ONLY the CURRENT {@code R__npdev_schema_additive_columns.sql}
 * -- never the fresh-database-only {@code V1__} -- against that live H2 database via H2's own
 * {@code RunScript}, and asserts via {@code INFORMATION_SCHEMA} that the claim columns and the
 * claim index both now exist. This is exactly what Flyway does on a real upgrade boot: {@code V1__}
 * (versioned) never re-runs once applied; only {@code R__} (repeatable) does.
 */
final class SchemaRealizationEmitterInternalTableAdditiveIndexesTest {

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
    void newIndexTextAppearsInTheAdditiveMigration() throws Exception {
        String additiveSql = generateAdditiveSql();
        assertTrue(
                additiveSql.contains("CREATE INDEX IF NOT EXISTS idx_inst_tenant_status_claimed "
                        + "ON npdev_flow_instance (tenant_id, status, claimed_until)"),
                additiveSql);
        assertTrue(additiveSql.contains("ALTER TABLE npdev_flow_instance ADD COLUMN IF NOT EXISTS claimed_by"),
                additiveSql);
        assertTrue(additiveSql.contains("ALTER TABLE npdev_flow_instance ADD COLUMN IF NOT EXISTS claimed_until"),
                additiveSql);
    }

    @Test
    void claimColumnsAndIndexLandOnADatabaseThatPredatesR8c() throws Exception {
        // Step 1: realize a genuinely fresh database with CURRENT code's V1__ -- this creates every
        // OTHER internal table (npdev_tenant, npdev_correlation_owner, etc.) in their real shape,
        // which the additive script in step 2 assumes already exist (exactly like production: R__
        // only ever ADD COLUMNs/CREATE INDEXes against tables V1__ already created on first boot).
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

        // Step 2: downgrade JUST npdev_flow_instance back to the shape it had on a real,
        // already-deployed database before this change -- no claimed_by/claimed_until columns, no
        // claim index. This is deliberately RAW SQL, not SchemaRealizationEmitter output, because
        // CURRENT code has no way to emit the OLD shape any more (that is the whole point of an
        // additive-migration proof: the "before" state must be independent of the fix being tested).
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE npdev_flow_instance");
            statement.execute("""
                    CREATE TABLE npdev_flow_instance (
                        execution_id VARCHAR(191) NOT NULL,
                        flow_name TEXT NOT NULL,
                        correlation_id TEXT NOT NULL,
                        tenant_id TEXT,
                        actor_id TEXT,
                        status TEXT NOT NULL,
                        current_step_index INT NOT NULL,
                        waiting_for_event_name TEXT,
                        state_json TEXT NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL,
                        resume_attempt_count INT NOT NULL DEFAULT 0,
                        last_resume_at TIMESTAMP,
                        last_resume_error_code TEXT,
                        next_eligible_resume_at TIMESTAMP,
                        last_progress_at TEXT,
                        last_error_kind TEXT,
                        last_error_code TEXT,
                        last_error_message TEXT,
                        failed_at TIMESTAMP,
                        PRIMARY KEY (execution_id)
                    )
                    """);
            statement.execute("CREATE INDEX idx_npdev_flow_instance_status_updated "
                    + "ON npdev_flow_instance (status, updated_at, execution_id)");
        }
        assertFalse(hasColumn("NPDEV_FLOW_INSTANCE", "CLAIMED_BY"), "pre-R8c shape must not have claimed_by yet");
        assertFalse(hasColumn("NPDEV_FLOW_INSTANCE", "CLAIMED_UNTIL"), "pre-R8c shape must not have claimed_until yet");
        assertFalse(hasIndex("NPDEV_FLOW_INSTANCE", "IDX_INST_TENANT_STATUS_CLAIMED"),
                "pre-R8c shape must not have the claim index yet");

        // Step 2: run ONLY the repeatable additive migration -- the R__ script, never V1__ -- the
        // same subset Flyway applies against a database where V1__ already ran historically.
        String additiveSql = generateAdditiveSql();
        RunScript.execute(connection, new StringReader(additiveSql));

        // Step 3: the claim columns and the claim index now exist, on the SAME database, with no
        // data loss and no destructive recreate.
        assertTrue(hasColumn("NPDEV_FLOW_INSTANCE", "CLAIMED_BY"), "claimed_by must land on upgrade");
        assertTrue(hasColumn("NPDEV_FLOW_INSTANCE", "CLAIMED_UNTIL"), "claimed_until must land on upgrade");
        assertTrue(hasIndex("NPDEV_FLOW_INSTANCE", "IDX_INST_TENANT_STATUS_CLAIMED"),
                "the claim index must land on upgrade -- this is the exact gap the R8c card named");

        // Step 4: the mechanism is not just present, it is FUNCTIONAL against the now-upgraded
        // table -- an ordinary write with the new claim columns round-trips.
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO npdev_flow_instance (execution_id, flow_name, correlation_id, tenant_id, actor_id, "
                        + "status, current_step_index, state_json, created_at, updated_at, claimed_by, claimed_until) "
                        + "VALUES (?, 'TestFlow', 'corr-1', 'tenant-1', 'actor-1', 'WAITING_EVENT', 0, '{}', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)")) {
            insert.setString(1, "exec-upgrade-1");
            insert.setString(2, "claimant-x");
            insert.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT claimed_by FROM npdev_flow_instance WHERE execution_id = ?")) {
            select.setString(1, "exec-upgrade-1");
            try (ResultSet resultSet = select.executeQuery()) {
                assertTrue(resultSet.next());
                assertTrue("claimant-x".equals(resultSet.getString(1)));
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

    private boolean hasColumn(String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean hasIndex(String table, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = ? AND INDEX_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "internal-additive-index-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "internal-additive-index-test",
                "internal-additive-index-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:internal-additive-index-test",
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
