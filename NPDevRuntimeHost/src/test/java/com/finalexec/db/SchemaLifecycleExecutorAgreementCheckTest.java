package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 6 (task 6.3). H2 integration coverage for {@code SchemaLifecycleExecutor}'s
 * "friendlier" agreement-check enrichment on top of the EXISTING (Phase 4, unchanged) token-
 * mismatch refusal: when the manifest carries a migration plan's own destructive-item stable
 * strings ({@code SchemaManifest#planItemStableStrings()}, populated only when a
 * {@code MigrationPlan} was computed at generation time) and they differ from what
 * {@link SchemaDeltaReport} independently finds live at boot, the refusal message now names BOTH
 * lists side by side. The refuse/proceed DECISION itself is unchanged -- this is diagnostics only,
 * see {@code SchemaLifecycleExecutorDestructiveItemizationTest} for the core refusal-gate coverage
 * this file does not repeat.
 */
class SchemaLifecycleExecutorAgreementCheckTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void refusalMessageNamesBothListsWhenThePlanAndTheLiveBootDiffDiverge() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // Live DB actually needs "legacy_flag" dropped (a fresh field the operator added to the
            // model AFTER generating the plan, say) -- the classic stale-artifact scenario.
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // The plan (computed at generation time, before the extra model edit) expected a DIFFERENT
        // column to be dropped -- "obsolete_note", not "legacy_flag".
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithPlanItems(
                "sha256:new", "", List.of("DROP_COLUMN:widgets:obsolete_note:VARCHAR(255)"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));

        String message = exception.getMessage();
        assertTrue(message.contains("LNCH-1 Phase 6 agreement check"), "expected the enrichment marker: " + message);
        assertTrue(message.contains("DROP_COLUMN:widgets:obsolete_note:VARCHAR(255)"),
                "expected the PLAN's expected item to be named: " + message);
        assertTrue(message.contains("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"),
                "expected the LIVE (actual) item to be named: " + message);
        // The pre-existing Phase 4 refusal content is unchanged, only enriched.
        assertTrue(message.contains("Expected acknowledgment token:"));
        assertTrue(message.contains("docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes"));
    }

    @Test
    void refusalMessageIsUnenrichedWhenThePlanAndTheLiveBootDiffAgree() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // The plan's expected item is EXACTLY what the live boot independently finds -- no drift.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithPlanItems(
                "sha256:new", "", List.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));

        assertFalse(exception.getMessage().contains("LNCH-1 Phase 6 agreement check"),
                "the plan and the live diff AGREE -- no enrichment should fire: " + exception.getMessage());
    }

    @Test
    void refusalMessageIsUnenrichedWhenNoPlanWasEverComputed() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // Pre-Phase-6 manifest shape (the 20-arg backward-compatible constructor) -- every app
        // generated before this phase, or without --schemaMigrationPlanOut, looks like this.
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "", Map.of(), Map.of(), Map.of(), Map.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));

        assertFalse(exception.getMessage().contains("LNCH-1 Phase 6 agreement check"),
                "no migration plan was ever computed for this manifest -- zero behavior change from Phase 4: "
                        + exception.getMessage());
    }

    private static void seedStoredFingerprint(DataSource dataSource, String fingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)")) {
                statement.setString(1, "schemaFingerprint");
                statement.setString(2, fingerprint);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestWithPlanItems(
            String toFingerprint, String ackToken, List<String> planItemStableStrings
    ) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", ackToken, Map.of(), Map.of(), Map.of(), Map.of(),
                planItemStableStrings);
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific compile-time dependency. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
