package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 4 (task 4.5). H2 integration coverage for {@link SchemaLifecycleExecutor#beforeMigrate}'s
 * new itemized destructive path (tasks 4.3/4.4): surgical drop-with-matching-ack success, refusal
 * without a token, refusal with a STALE token, the {@code npdev_schema_history} row lifecycle for
 * both outcomes, and the deprecated blanket-flag-only backward-compat path (still whole-schema,
 * not surgical, with a logged deprecation warning). Crash-mid-destruction (item (f) of the plan's
 * task 4.5 list) has its own file, {@link SchemaLifecycleExecutorDestructiveCrashRecoveryTest}, since
 * its fault-injection machinery is a self-contained chunk of test infrastructure.
 */
class SchemaLifecycleExecutorDestructiveItemizationTest {

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
    void dropColumnWithMatchingAckTokenSucceedsSurgicallyLeavingSiblingDataIntact() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (2, 'Beta', FALSE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest("sha256:new", "");
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestWithoutToken);
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifestWithToken = manifest("sha256:new", expectedToken);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestWithToken);

        assertTrue(result.performed());
        assertFalse(result.safeAdditive(), "a destructive recreation is not the safe-additive path");
        assertEquals(List.of("widgets"), result.droppedTables(),
                "the surgical path must scope its 'affected tables' to ONLY the table(s) the itemized report named");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the dropped column must be gone");
            assertTrue(hasColumn(metadata, "widgets", "name"), "a sibling column must be untouched");

            try (PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM widgets ORDER BY id");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(1L, resultSet.getLong("id"));
                assertEquals("Alpha", resultSet.getString("name"));
                assertTrue(resultSet.next());
                assertEquals(2L, resultSet.getLong("id"));
                assertEquals("Beta", resultSet.getString("name"));
                assertFalse(resultSet.next(), "no rows must be lost -- only the dropped column's data, not the rows themselves");
            }
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("APPLIED", row.outcome());
        assertEquals(expectedToken, row.ackTokenUsed());
        assertTrue(row.itemsJson().contains("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"),
                "items_json must record exactly what was itemized: " + row.itemsJson());
        assertEquals("sha256:old", row.fromFingerprint());
        assertEquals("sha256:new", row.toFingerprint());
    }

    @Test
    void missingAckTokenAndNoBlanketFlagRefusesBootAndLeavesDatabaseCompletelyUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestNoBlanket("sha256:new", "");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(exception.getMessage().contains("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"),
                "the refusal message must itemize the destructive report: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("Expected acknowledgment token:"));
        assertTrue(exception.getMessage().contains("docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes"));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"), "the DB must be completely untouched by a refusal");
            try (PreparedStatement statement = connection.prepareStatement("SELECT legacy_flag FROM widgets WHERE id = 1");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getBoolean(1));
            }
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
        assertNull(row.ackTokenUsed(), "no token was provided at all -- ack_token_used must be null, not empty-string");
    }

    @Test
    void staleTokenFromADifferentItemSetRefusesBootAndLeavesDatabaseUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // A token computed for a DIFFERENT (previous) plan -- not the one this boot actually needs.
        String staleToken = DestructiveAckToken.compute("sha256:new", List.of("DROP_COLUMN:widgets:some_other_column:VARCHAR(10)"));
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestNoBlanket("sha256:new", staleToken);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(exception.getMessage().contains("Expected acknowledgment token:"));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"), "a stale token must not authorize anything -- DB untouched");
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
        assertEquals(staleToken, row.ackTokenUsed(), "the (wrong) attempted token is still recorded for audit purposes");
    }

    /**
     * LNCH-1 hardening X1 (finding X-B1). This test previously asserted the OPPOSITE -- it was named
     * {@code blanketFlagAloneStillDoesTheOldWholeSchemaWipeNotSurgicalWithADeprecationWarning} and
     * pinned "even a table with no diff at all is dropped by the whole-schema path" as intended
     * behaviour. That pinned a critical data-loss regression: because the whole-schema path drops the
     * tables the NEW manifest lists, a blanket-authorized upgrade destroyed every still-modelled
     * concept's data (and, for a concept drop, left the actual orphan behind -- see the proof
     * matrix's scenario 24). Authorization no longer selects the execution path; only the presence of
     * an UNKNOWN item does. The deprecation warning for blanket-only authorization is unchanged, and
     * is asserted here as before.
     */
    @Test
    void blanketFlagAloneNowExecutesSurgicallyWithADeprecationWarning() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("CREATE TABLE untouched_table (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
            statement.execute("INSERT INTO untouched_table (id) VALUES (99)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // businessTables intentionally include BOTH tables: the OLD whole-schema path dropped every
        // manifest-listed table regardless of whether it had a diff. Only 'widgets' actually has a
        // diff (legacy_flag is being dropped), so post-X1 'untouched_table' must survive -- that
        // contrast is what this test now pins.
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("widgets", "untouched_table"),
                Map.of("widgets", List.of("id"), "untouched_table", List.of("id")),
                Map.of("widgets", List.of(), "untouched_table", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT"), "untouched_table", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                true, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "", Map.of(), Map.of(), Map.of(), Map.of());

        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        SchemaLifecycleExecutor.DestructiveRecreation result;
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            result = executor.beforeMigrate(dataSource, manifest);
        } finally {
            System.setOut(originalOut);
        }
        String logged = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("DEPRECATION WARNING"), "a blanket-flag-only authorization must log a deprecation warning: " + logged);
        assertTrue(logged.contains("Executing surgically"),
                "the deprecation warning must name what it is about to execute (X1.2): " + logged);
        assertTrue(logged.contains("DROP_COLUMN:widgets:legacy_flag"),
                "the warning must itemize the change so an operator can see the blast radius: " + logged);

        assertTrue(result.performed());
        assertTrue(result.droppedTables().contains("widgets"),
                "the table that actually had a diff is the one the surgical path touches");
        assertFalse(result.droppedTables().contains("untouched_table"),
                "X-B1: a table with no diff must NOT be touched merely because the blanket flag authorized the pass");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(tableExists(metadata, "untouched_table"),
                    "X-B1: a table with no diff at all must survive a blanket-authorized destructive pass");
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM untouched_table");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "the untouched table must keep its rows");
                assertEquals(99L, resultSet.getLong("id"));
            }
            // The acknowledged item itself still executes -- X1 narrowed the blast radius, it did
            // not weaken the change.
            assertTrue(tableExists(metadata, "widgets"), "the diffed table is altered in place, not dropped");
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the dropped column must still be gone");
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM widgets");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "the diffed table's ROWS survive -- only the dropped column's data is lost");
                assertEquals(1L, resultSet.getLong("id"));
            }
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("APPLIED", row.outcome());
        assertNull(row.ackTokenUsed(), "the blanket flag is not an itemized token -- ack_token_used must be null");
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

    private record HistoryRow(String fromFingerprint, String toFingerprint, String classification,
                               String itemsJson, String ackTokenUsed, String outcome) {
    }

    private static HistoryRow latestHistoryRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT from_fingerprint, to_fingerprint, classification, items_json, ack_token_used, outcome "
                             + "FROM npdev_schema_history ORDER BY applied_at_utc DESC");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
            return new HistoryRow(
                    resultSet.getString(1),
                    resultSet.getString(2),
                    resultSet.getString(3),
                    resultSet.getString(4),
                    resultSet.getString(5),
                    resultSet.getString(6));
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

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getTables(null, null, candidate, new String[] {"TABLE"})) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(String toFingerprint, String ackToken) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", ackToken, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestNoBlanket(String toFingerprint, String ackToken) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", ackToken, Map.of(), Map.of(), Map.of(), Map.of());
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
