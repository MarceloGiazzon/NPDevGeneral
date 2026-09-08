package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): the end-to-end proof that a
 * lifecycle refusal carries the platform-computed residue -- the exact answer to the row's old
 * workaround ("treat a refused boot as 'inspect before retrying'") -- and that the residue machinery
 * can never change or break a refusal.
 *
 * <p>Scenario: one destructive item a fixture hook ({@code p76-drop-legacy}) genuinely resolves and
 * one it does not claim. The hook runs and COMMITS (the non-idempotent {@code CONVERSION_HOOKS}
 * step), then the token refusal fires on the unclaimed item -- so the thrown message must enumerate
 * the committed steps and verdict {@code INSPECT_FIRST}, proving the boot's own work is legible at
 * the moment it refuses. The negative twin: with {@code npdev_boot_residue_journal} dropped before
 * the boot (and the read-back half proven separately in {@link RefusalResidueVerdictTest}), the token
 * refusal still throws the same exception type with its original message -- never a different
 * exception caused by the journal machinery.
 */
class SchemaLifecycleExecutorRefusalResidueIntegrationTest {

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
    void tokenRefusalAfterAConversionHookCommittedCarriesTheResidueAndInspectFirstVerdict() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE p76_widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("CREATE TABLE p76_untouched (id BIGINT PRIMARY KEY, mystery_column BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // p76-drop-legacy claims exactly "DROP_COLUMN:p76_widgets:legacy_flag:BOOLEAN" and drops the
        // column itself; p76_untouched.mystery_column is claimed by no fixture hook, so the report is
        // non-empty after the hooks run and the token refusal fires -- AFTER CONVERSION_HOOKS committed.
        SchemaLifecycleExecutor.SchemaManifest manifest = twoTableDestructiveManifest();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));

        // The original refusal is intact (its type and primary text never change -- P4 done-when #3)...
        assertTrue(exception.getMessage().contains("Expected acknowledgment token:"), exception.getMessage());
        assertTrue(exception.getMessage().contains("DROP_COLUMN:p76_untouched:mystery_column"),
                exception.getMessage());
        // ...and the residue enumerates what this boot already committed, naming the non-idempotent
        // hook step and its verdict.
        assertTrue(exception.getMessage().contains("B7:refusal_residue:"), exception.getMessage());
        assertTrue(exception.getMessage().contains("IN_PLACE_RENAME_WIDEN"), exception.getMessage());
        assertTrue(exception.getMessage().contains("CONVERSION_HOOKS"), exception.getMessage());
        assertTrue(exception.getMessage().contains("NOT idempotent"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Verdict: INSPECT_FIRST"), exception.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "p76_widgets", "legacy_flag"),
                    "the hook must have genuinely run and dropped the column -- CONVERSION_HOOKS committed before the refusal");
        }
    }

    @Test
    void refusalStillThrowsItsOriginalMessageWhenTheJournalTableIsDroppedBeforeTheBoot() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE p76_untouched (id BIGINT PRIMARY KEY, mystery_column BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        // The negative half of the done-when: destroy the journal before the boot. The boot's own
        // started() writes recreate the table, but the point is that NOTHING in the residue machinery
        // may change the refusal's exception type or primary message -- even when the journal is
        // broken or absent at every contact point.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS npdev_boot_residue_journal");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = singleTableDestructiveManifest();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(exception.getMessage().contains("Expected acknowledgment token:"), exception.getMessage());
        assertTrue(exception.getMessage().contains("DROP_COLUMN:p76_untouched:mystery_column"),
                exception.getMessage());
    }

    private static SchemaLifecycleExecutor.SchemaManifest twoTableDestructiveManifest() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(),
                List.of("p76_widgets", "p76_untouched"),
                Map.of("p76_widgets", List.of("id"), "p76_untouched", List.of("id")),
                Map.of("p76_widgets", List.of("id"), "p76_untouched", List.of("id")),
                Map.of("p76_widgets", Map.of("id", "BIGINT"), "p76_untouched", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly",
                "", "", // no blanket flag, NO acknowledgment token provided
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static SchemaLifecycleExecutor.SchemaManifest singleTableDestructiveManifest() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("p76_untouched"),
                Map.of("p76_untouched", List.of("id")),
                Map.of("p76_untouched", List.of("id")),
                Map.of("p76_untouched", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly",
                "", "",
                Map.of(), Map.of(), Map.of(), Map.of());
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

    private static boolean hasColumn(DatabaseMetaData metadata, String table, String column) throws SQLException {
        try (var resultSet = metadata.getColumns(null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Single, long-lived connection so H2's {@code DB_CLOSE_DELAY=-1} in-memory DB (and its
     *  auto-bootstrapped metadata) survives across the many short-lived connections the executor and
     *  {@link ConversionHookRunner} each open. Mirrors {@code SchemaLifecycleExecutorConversionHookIntegrationTest}'s
     *  identically-named fixture. */
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