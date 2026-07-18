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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 7 (task 7.1) -- a pre-existing bug found while writing the row-6 proof-matrix
 * scenario ("rename + widen same column"), fixed in its own commit per guardrail 10.
 *
 * <p>{@link SchemaLifecycleExecutorTypeWideningIntegrationTest#renameComposedWithWideningOnTheSameColumnBothApplyInOneBoot}
 * proves the underlying primitives ({@code attemptInPlaceTableRenames}/{@code attemptInPlaceRenames}/
 * {@code attemptInPlaceTypeWidenings}) compose correctly WHEN CALLED DIRECTLY, in the right order --
 * but it never drove the composition through the real entry point,
 * {@link SchemaLifecycleExecutor#beforeMigrate}, which has its own branching logic that decides
 * which steps to attempt. That branching turned out to be gappy: {@code classify()} escalates a
 * rename+type-change combination on the SAME column straight to {@code TYPE_CHANGE_DETECTED} (the
 * Phase 1 fix -- an explained rename pair's OLD column type is compared against the NEW column's
 * expected type). Pre-fix, {@code beforeMigrate}'s two classification branches were mutually
 * exclusive ({@code if (classification == RENAME_DETECTED) {...} else if (classification ==
 * TYPE_CHANGE_DETECTED) {...}}) -- only the {@code RENAME_DETECTED} branch ever attempted renames.
 * Since {@code classify()} reports {@code TYPE_CHANGE_DETECTED} directly for this exact composition,
 * the rename was NEVER attempted, {@code attemptInPlaceTypeWidenings} looked for the column under
 * its new name (not there yet), found nothing to widen, and the whole pass fell through to
 * destructive recreation -- silently contradicting the plan's own DoD row 6 ("Both applied, data
 * intact") the moment a real boot (not a hand-sequenced unit test) hit this scenario.
 *
 * <p>Confirmed red before the fix (this exact test, run against the pre-fix code, failed with
 * {@code result.safeAdditive()} false and the table having been dropped/recreated by the
 * destructive fallback instead). Fixed by having the {@code TYPE_CHANGE_DETECTED} branch also
 * attempt renames first, mirroring the {@code RENAME_DETECTED} branch's own nested widening
 * attempt -- {@code attemptInPlaceRenames} is self-guarding per table, so this is exactly as safe
 * as the pre-existing unconditional {@code attemptInPlaceTableRenames} call.
 */
class SchemaLifecycleExecutorRenameWidenBeforeMigrateGapTest {

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
    void beforeMigrateResolvesARenamePlusWidenOnTheSameColumnWithoutFallingThroughToDestructive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_quantity INTEGER, version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_quantity, version) VALUES (1, " + Integer.MAX_VALUE + ", 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // Deliberately allowDestructiveRecreate=true, so the pre-fix bug's actual failure mode
        // (falling through to the blanket-flag whole-schema wipe) is directly observable: the table
        // would have been DROPPED, not merely refused. Post-fix, this must never be reached.
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "new_quantity", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_quantity", "BIGINT", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_quantity", "old_quantity")),
                Map.of(),
                true, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "",
                Map.of(), Map.of(), Map.of(), Map.of());

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "control: classify()'s TOP-LEVEL verdict for this composition is TYPE_CHANGE_DETECTED, not RENAME_DETECTED");

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);

        assertTrue(result.safeAdditive(),
                "the composed rename+widen must resolve via the safe path, exactly like the plan's row 6 DoD ('Both applied, data intact')");
        assertFalse(result.performed(), "destructive recreation must NOT have run -- this is the bug's actual failure mode pre-fix");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_quantity"), "the rename half must be applied");
            assertEquals("BIGINT", columnTypeName(metadata, "widgets", "new_quantity"), "the widen half must be applied");
            try (PreparedStatement statement = connection.prepareStatement("SELECT new_quantity FROM widgets WHERE id = 1");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "the row must still exist -- pre-fix, the destructive fallback would have dropped and recreated the table");
                assertEquals(Integer.MAX_VALUE, resultSet.getLong(1), "the original data must survive");
            }
        }
    }

    private static boolean hasColumn(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String columnTypeName(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return resultSet.getString("TYPE_NAME");
                    }
                }
            }
        }
        throw new IllegalStateException("Column not found: " + table + "." + column);
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
