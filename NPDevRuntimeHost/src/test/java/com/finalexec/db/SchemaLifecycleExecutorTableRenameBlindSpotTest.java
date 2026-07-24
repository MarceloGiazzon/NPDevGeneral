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
 * LNCH-1 P2 (2.5) VERIFY step, updated at SER-P4.8: this originally pinned {@code classify()}'s
 * blindness to a concept (table) rename -- the old per-manifest-table loop only ever visited
 * {@code manifest.businessTableColumns().keySet()} (tables under their CURRENT name), so a table
 * renamed live-DB-side that no longer appears under any current name was never visited, left
 * orphaned with its data, and reported {@code SAFE_ADDITIVE} as if nothing were wrong.
 *
 * <p>SER-P4.8 switched {@code classify()}'s column-level decision to the desired-vs-current
 * {@code SchemaDiffEngine}, which models a declared table rename as a {@code SAFE_RENAME}. The blind
 * spot is therefore CLOSED even in isolation: Part 1
 * ({@link #classifyAloneNowDetectsAnOrphanedRenamedTable()}) now proves {@code classify()} ALONE
 * reports the pending rename ({@code RENAME_DETECTED}) rather than being blind to the orphaned old
 * table. The live path is unaffected -- {@link SchemaLifecycleExecutor#beforeMigrate} still applies
 * {@link SchemaLifecycleExecutor#attemptInPlaceTableRenames} first, so by the time classify runs
 * live there is no residual, exactly as Part 2
 * ({@link #attemptInPlaceTableRenamesClosesTheBlindSpotBeforeClassifyRuns()}) proves ({@code SAFE_ADDITIVE}).
 */
class SchemaLifecycleExecutorTableRenameBlindSpotTest {

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
    void classifyAloneNowDetectsAnOrphanedRenamedTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO gadgets (id, name, version) VALUES (1, 'alpha', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", "gadgets"));

        SchemaLifecycleExecutor.SchemaChangeClassification classification =
                executor.classify(dataSource, manifest);

        // SER-P4.8: the blind spot is CLOSED. classify() now decides column-level changes via the
        // desired-vs-current SchemaDiffEngine, which sees BOTH sides of the schema: desired "widgets"
        // (renamed-from "gadgets") and the live orphaned "gadgets". The declared rename resolves to a
        // single SAFE_RENAME item, so classify() reports RENAME_DETECTED -- the pending rename -- rather
        // than the old loop's SAFE_ADDITIVE blindness. classify() is read-only, so it does not itself
        // apply the rename; the live path applies it via attemptInPlaceTableRenames first (Part 2).
        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.RENAME_DETECTED, classification,
                "blind spot closed: classify() alone now detects the declared table rename of an "
                        + "orphaned live table instead of reporting SAFE_ADDITIVE");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "gadgets"), "classify is read-only -- the old table is still there, now SEEN not ignored");
            assertFalse(hasTable(metadata, "widgets"), "classify does not apply the rename itself -- the new table does not exist yet");
        }
    }

    @Test
    void attemptInPlaceTableRenamesClosesTheBlindSpotBeforeClassifyRuns() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO gadgets (id, name, version) VALUES (1, 'alpha', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", "gadgets"));

        // The fix: run the new table-rename step BEFORE classify(), exactly as beforeMigrate now does.
        executor.attemptInPlaceTableRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"), "the table must now exist under its new name");
            assertFalse(hasTable(metadata, "gadgets"), "the old table name must no longer exist");
            try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("alpha", resultSet.getString(1), "the row's data must survive the table rename unchanged");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "once the table rename has actually been applied, classify() correctly finds no residual diff");
    }

    private static boolean hasTable(DatabaseMetaData metadata, String table) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getTables(null, null, candidate, new String[] {"TABLE"})) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, String> businessTableRenames) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                Map.of(),
                Map.of(),
                businessTableRenames,
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
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
