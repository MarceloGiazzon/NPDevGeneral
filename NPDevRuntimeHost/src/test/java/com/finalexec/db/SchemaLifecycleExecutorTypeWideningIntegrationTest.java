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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 3 (tasks 3.2-3.4) integration coverage for
 * {@link SchemaLifecycleExecutor#attemptInPlaceTypeWidenings} and its wiring into
 * {@link SchemaLifecycleExecutor#beforeMigrate}, against a real H2 in-memory database (same style
 * as {@link SchemaLifecycleExecutorInPlaceRenameTest}). Covers, per the plan's DoD list:
 * (a) INTEGER -&gt; BIGINT widening with a boundary value (Integer.MAX_VALUE) surviving,
 * (b) a hand-built-manifest VARCHAR(20) -&gt; VARCHAR(50) widening with an existing 20-char string
 *     surviving,
 * (c) a narrowing attempt correctly refuses the widening path and leaves today's existing
 *     destructive-or-refuse behavior unregressed (DB untouched by the widening step itself),
 * (d) mixed widening+narrowing on the SAME table applies NEITHER column (all-or-nothing),
 * (e) idempotence (re-run after a successful widening is a no-op),
 * (f) composition: a column both renamed (via renamedFrom) AND widened in the same model change --
 *     rename applies first, then widening against the new column name, both succeed in one boot
 *     of {@link SchemaLifecycleExecutor#beforeMigrate}.
 */
class SchemaLifecycleExecutorTypeWideningIntegrationTest {

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
    void integerToBigintWideningAppliesInPlaceAndBoundaryValueSurvives() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity INTEGER, version BIGINT)");
            statement.execute("INSERT INTO widgets (id, quantity, version) VALUES (1, " + Integer.MAX_VALUE + ", 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "quantity", "version")),
                Map.of("widgets", Map.of("id", "BIGINT", "quantity", "BIGINT", "version", "BIGINT")));

        executor.attemptInPlaceTypeWidenings(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("BIGINT", columnTypeName(metadata, "widgets", "quantity"),
                    "quantity's live type must now be BIGINT");
            try (PreparedStatement statement = connection.prepareStatement("SELECT quantity FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(Integer.MAX_VALUE, resultSet.getLong(1),
                            "the boundary value (Integer.MAX_VALUE) inserted before widening must survive unchanged");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "after a fully-applied widening, residual classification must be SAFE_ADDITIVE");
    }

    @Test
    void varcharLengthWideningAppliesInPlaceAndExistingStringSurvives() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, code VARCHAR(20))");
            statement.execute("INSERT INTO widgets (id, code) VALUES (1, 'abcdefghijklmnopqrst')"); // exactly 20 chars
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "code")),
                Map.of("widgets", Map.of("id", "BIGINT", "code", "VARCHAR(50)")));

        executor.attemptInPlaceTypeWidenings(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT code FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("abcdefghijklmnopqrst", resultSet.getString(1),
                            "the existing 20-char string must survive the VARCHAR(20)->VARCHAR(50) widening");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "after a fully-applied widening, residual classification must be SAFE_ADDITIVE");
    }

    @Test
    void narrowingAttemptAppliesNothingAndFallsThroughToTodaysExistingDestructiveBehavior() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, code VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, code) VALUES (1, 'unchanged-because-narrowing-is-refused')");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "code")),
                Map.of("widgets", Map.of("id", "BIGINT", "code", "VARCHAR(20)")));

        executor.attemptInPlaceTypeWidenings(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals(50, columnSize(metadata, "widgets", "code"),
                    "a narrowing must NOT be applied by the widening step -- the live column must be untouched");
        }
        // Unregressed today's existing behavior: the residual classification is still
        // TYPE_CHANGE_DETECTED (not SAFE_ADDITIVE), which is exactly what beforeMigrate() already
        // routes to the destructive/refusal path, unchanged since before this phase.
        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "a narrowing must leave the table on TYPE_CHANGE_DETECTED, exactly today's pre-Phase-3 fallback behavior");
    }

    @Test
    void mixedWideningAndNarrowingOnTheSameTableAppliesNeither() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity INTEGER, code VARCHAR(50))");
        }

        // quantity: INTEGER -> BIGINT is a safe widening. code: VARCHAR(50) -> VARCHAR(20) is a
        // narrowing. Per the plan's all-or-nothing rule, NEITHER column may be touched.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "quantity", "code")),
                Map.of("widgets", Map.of("id", "BIGINT", "quantity", "BIGINT", "code", "VARCHAR(20)")));

        executor.attemptInPlaceTypeWidenings(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("INTEGER", columnTypeName(metadata, "widgets", "quantity"),
                    "the safe widening on this table must NOT be applied either -- all-or-nothing");
            assertEquals(50, columnSize(metadata, "widgets", "code"),
                    "the narrowing column must be untouched, as expected");
        }
        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "a mixed table must remain fully unresolved, routing the whole table to destructive/refusal");
    }

    @Test
    void reRunAfterASuccessfulWideningIsAnIdempotentNoOp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity INTEGER)");
            statement.execute("INSERT INTO widgets (id, quantity) VALUES (1, 42)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "quantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "quantity", "BIGINT")));

        executor.attemptInPlaceTypeWidenings(dataSource, manifest);
        // Second invocation against an already-widened column must be a clean no-op, not an error
        // and not a second ALTER attempt that could fail on some engines.
        executor.attemptInPlaceTypeWidenings(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("BIGINT", columnTypeName(metadata, "widgets", "quantity"));
            try (PreparedStatement statement = connection.prepareStatement("SELECT quantity FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(42L, resultSet.getLong(1));
                }
            }
        }
    }

    @Test
    void renameComposedWithWideningOnTheSameColumnBothApplyInOneBoot() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_quantity INTEGER, version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_quantity, version) VALUES (1, " + Integer.MAX_VALUE + ", 1)");
        }

        // The column is BOTH renamed (old_quantity -> new_quantity, via renamedFrom) AND widened
        // (INTEGER -> BIGINT) in the same model change. Overall classify() must report
        // TYPE_CHANGE_DETECTED (rename + type change on the same column escalates severity, per
        // the Phase 1 fix), and the full beforeMigrate() sequence -- table renames, field renames,
        // then type widenings -- must resolve both in a single boot, ending SAFE_ADDITIVE.
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.of("widgets"),
                Map.of("widgets", List.of("id", "new_quantity", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_quantity", "BIGINT", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_quantity", "old_quantity")),
                Map.of(),
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

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "control: before any step runs, a rename+widen combo on the same column classifies as TYPE_CHANGE_DETECTED");

        // Drive the exact sequence beforeMigrate() uses for this classification (table renames are
        // a no-op here -- no concept rename declared -- then field renames, then widenings).
        executor.attemptInPlaceTableRenames(dataSource, manifest);
        executor.attemptInPlaceRenames(dataSource, manifest);
        executor.attemptInPlaceTypeWidenings(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_quantity"), "the rename half of the composition must be applied");
            assertEquals("BIGINT", columnTypeName(metadata, "widgets", "new_quantity"),
                    "the widening half of the composition must be applied, against the NEW column name");

            try (PreparedStatement statement = connection.prepareStatement("SELECT new_quantity FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(Integer.MAX_VALUE, resultSet.getLong(1), "data must survive both operations composed");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "both operations composed must fully resolve the fingerprint diff in one pass");
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

    private static int columnSize(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return resultSet.getInt("COLUMN_SIZE");
                    }
                }
            }
        }
        throw new IllegalStateException("Column not found: " + table + "." + column);
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                businessTableColumnTypes,
                Map.of(),
                Map.of(),
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
