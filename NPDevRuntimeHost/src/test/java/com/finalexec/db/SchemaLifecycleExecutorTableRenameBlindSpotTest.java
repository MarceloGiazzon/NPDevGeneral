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
 * LNCH-1 P2 (2.5) VERIFY step: proves, against a real H2 database, that {@code classify()} as it
 * stood before this phase is completely blind to a concept (table) rename -- it only ever loops
 * over {@code manifest.businessTableColumns().keySet()} (tables declared under their CURRENT
 * name), so a table renamed live-DB-side that no longer appears under any current name is simply
 * never visited. The live-but-unvisited OLD table is silently left in place with its data intact
 * but permanently orphaned, while {@code classify()} reports {@code SAFE_ADDITIVE} (or better) as
 * if nothing were wrong -- worse than the pre-Phase-1 field-rename gap, which was at least
 * classified as destructive.
 *
 * <p>Part 1 ({@link #classifyAloneIsBlindToAnOrphanedRenamedTable()}) calls {@code classify()}
 * directly, WITHOUT the new {@link SchemaLifecycleExecutor#attemptInPlaceTableRenames} step, and
 * pins today's actual (buggy, pre-fix) behavior described above. Part 2
 * ({@link #attemptInPlaceTableRenamesClosesTheBlindSpotBeforeClassifyRuns()}) proves the fix: once
 * {@code attemptInPlaceTableRenames} runs first (as {@link SchemaLifecycleExecutor#beforeMigrate}
 * now always does ahead of classification), the table is no longer orphaned and {@code classify()}
 * correctly reports the residual diff.
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
    void classifyAloneIsBlindToAnOrphanedRenamedTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO gadgets (id, name, version) VALUES (1, 'alpha', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", "gadgets"));

        SchemaLifecycleExecutor.SchemaChangeClassification classification =
                executor.classify(dataSource, manifest);

        // THIS is the blind spot: the manifest declares the concept renamed (widgets was gadgets),
        // "widgets" does not exist live yet, so classify()'s per-manifest-table loop calls
        // readActualColumns(metadata, "widgets"), gets nothing back, and -- per its own "table
        // doesn't exist yet, V1's CREATE TABLE IF NOT EXISTS handles it" comment -- treats this as
        // a brand-new table and simply `continue`s. "gadgets" is never enumerated by this loop at
        // all (it only iterates manifest.businessTableColumns().keySet(), which contains "widgets",
        // not "gadgets"), so its existence, and its row of data, are invisible to classify().
        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE, classification,
                "pre-fix blind spot: classify() alone reports SAFE_ADDITIVE even though the "
                        + "renamed-from table still exists live with data and was never examined");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "gadgets"), "the old table must still be sitting there, unexamined");
            assertFalse(hasTable(metadata, "widgets"), "the new table must not exist yet -- nothing renamed it");
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
