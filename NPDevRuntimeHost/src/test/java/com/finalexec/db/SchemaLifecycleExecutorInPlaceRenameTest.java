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
 * LNCH-1 Phase 1 integration coverage for {@link SchemaLifecycleExecutor#attemptInPlaceRenames},
 * against a real H2 in-memory database (same style as
 * {@link SchemaLifecycleExecutorAdditiveChangeTest}). Proves: the column is actually renamed via
 * live JDBC introspection (not just classification), every row's data survives unchanged, a
 * second invocation is a clean idempotent no-op, a rename composed with a separate additive column
 * on the same table leaves the table SAFE_ADDITIVE afterward, and a table whose diff is NOT fully
 * explained by declared renames is left completely untouched (no partial rename).
 */
class SchemaLifecycleExecutorInPlaceRenameTest {

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
    void renameIsAppliedInPlaceAndDataSurvives() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (2, 'beta', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "renamed column must be visible under its new name");
            assertFalse(hasColumn(metadata, "widgets", "old_name"), "old column name must no longer exist");

            try (PreparedStatement statement = connection.prepareStatement("SELECT new_name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("alpha", resultSet.getString(1), "row 1's data must survive the rename unchanged");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT new_name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 2L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("beta", resultSet.getString(1), "row 2's data must survive the rename unchanged");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "after a fully-applied rename, live columns exactly match the manifest, so residual classification must be SAFE_ADDITIVE (nothing left to do)");

        // Idempotence: re-invoking against the already-renamed table must be a clean no-op, not an error.
        executor.attemptInPlaceRenames(dataSource, manifest);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "second invocation must leave the renamed column in place");
        }
    }

    @Test
    void renameIsResolvedByIdentityWhenNoRenamedFromIsDeclared() throws SQLException {
        // REG-209 (B1 lift, ALL_HITTABLE_LIFT_PLAN_2026-09-05.md package P7): the plan's own
        // "Done when" scenario -- a hand-edit that changes a field's name but keeps its uid, with NO
        // renamedFrom marker at all. A prior boot's ColumnIdentityStore.record already recorded this
        // uid against the OLD live column name (seeded directly here, standing in for that prior
        // boot) -- attemptInPlaceRenames must resolve the rename by identity alone.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (2, 'beta', 1)");
        }
        seedColumnIdentity(dataSource, "widgets", "old_name", "abcdefgh12345678");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithUids(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), // no renamedFrom declared at all -- identity alone must explain this
                Map.of("widgets", Map.of("new_name", "abcdefgh12345678"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "identity-resolved rename must apply the same as a declared one");
            assertFalse(hasColumn(metadata, "widgets", "old_name"), "old column name must no longer exist");
            try (PreparedStatement statement = connection.prepareStatement("SELECT new_name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("alpha", resultSet.getString(1), "row data must survive an identity-resolved rename unchanged");
                }
            }
        }
    }

    @Test
    void aColumnWithNoMatchingPersistedIdentityIsNotRenamed() throws SQLException {
        // The other half of the "Done when" bar: a model with uids that do NOT match anything
        // persisted (e.g. a genuinely new field, never seen before) must NOT be treated as a rename
        // -- it is left for the ordinary additive-column path, exactly as an unrelated new column
        // always was.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (1, 'alpha', 1)");
        }
        // Identity table has a row, but for a DIFFERENT uid than the model declares below.
        seedColumnIdentity(dataSource, "widgets", "old_name", "zzzzzzzz99999999");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithUids(
                Map.of("widgets", List.of("id", "old_name", "brand_new_col", "version")),
                Map.of("widgets", List.of("brand_new_col")),
                Map.of("widgets", Map.of("id", "BIGINT", "old_name", "VARCHAR(50)", "brand_new_col", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(),
                Map.of("widgets", Map.of("brand_new_col", "abcdefgh12345678"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "old_name"), "old_name must be untouched -- no matching identity means no rename");
            assertFalse(hasColumn(metadata, "widgets", "brand_new_col"), "attemptInPlaceRenames itself never ADDS a column -- that is the additive-column pass' job, not renamed in as a false positive here");
        }
    }

    private static void seedColumnIdentity(DataSource dataSource, String table, String column, String uid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE npdev_column_identity (table_name VARCHAR(255) NOT NULL, "
                    + "column_name VARCHAR(255) NOT NULL, uid VARCHAR(255) NOT NULL, recorded_at_utc BIGINT NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO npdev_column_identity (table_name, column_name, uid, recorded_at_utc) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, table);
                insert.setString(2, column);
                insert.setString(3, uid);
                insert.setLong(4, System.currentTimeMillis());
                insert.executeUpdate();
            }
        }
    }

    @Test
    void renameComposedWithASeparateAdditiveColumnLeavesTableSafeAdditive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (1, 'alpha', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "extra_col", "version")),
                Map.of("widgets", List.of("extra_col")),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "extra_col", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "the rename half of the composition must still be applied");
            assertFalse(hasColumn(metadata, "widgets", "extra_col"), "the additive column is NOT this step's job -- the additive repeatable migration adds it later");
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "once the rename resolves, the only remaining diff (extra_col) is additive-eligible, so residual must be SAFE_ADDITIVE");
    }

    /**
     * LNCH-1 Phase 7 rehearsal fix: this test used to assert the OPPOSITE -- that a table with an
     * unrelated, unexplained extra column left the rename completely unapplied ("no partial
     * rename"). That was found live to be a real data-loss bug (see {@code attemptInPlaceRenames}'
     * javadoc): a declared rename combined with a SEPARATE, legitimately destructive drop on the
     * same table (e.g. the drop is itemized and acknowledged via the surgical path) used to silently
     * orphan the renamed column's data instead of renaming it. The rename and the unrelated extra
     * column are now independent concerns -- the rename applies regardless of what else on the table
     * still needs the destructive path, and classify() still correctly reports DESTRUCTIVE for the
     * remaining unresolved column.
     */
    @Test
    void renameAppliesEvenWhenAnUnrelatedExtraColumnStillNeedsTheDestructivePath() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), other_old VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, other_old, version) VALUES (1, 'alpha', 'gamma', 1)");
        }

        // other_old has no declared rename and is not additive-eligible -- an ordinary drop mixed
        // in with the rename. That drop is a separate concern for the destructive path; it must not
        // block the (independently safe) rename from applying in place.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "old_name"), "the rename must apply even though an unrelated column remains unresolved");
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "the renamed column must be present");
            assertTrue(hasColumn(metadata, "widgets", "other_old"), "the unrelated, unexplained column is untouched by this step -- not this method's job");
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE,
                executor.classify(dataSource, manifest),
                "the still-unresolved other_old column must route the table to the destructive path as the safety net");
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

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                businessTableRenamedColumns,
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

    /** REG-209 (B1 lift, package P7): same shape as {@link #manifest}, plus an explicit
     *  businessTableColumnUids map -- used to prove identity-based rename resolution. */
    private static SchemaLifecycleExecutor.SchemaManifest manifestWithUids(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns,
            Map<String, Map<String, String>> businessTableColumnUids) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                businessTableRenamedColumns,
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                "NpdevManaged",
                Map.of(),
                Map.of(),
                Map.of(),
                businessTableColumnUids
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
