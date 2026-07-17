package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LNCH-1 Phase 1 prerequisite fix. {@code classify()}'s "renames fully explain the diff" branch
 * (previously lines 202-205) unconditionally {@code continue}d to {@code RENAME_DETECTED} without
 * ever calling {@link SchemaLifecycleExecutor#classify} 's type-change check for that table -- so a
 * column that is BOTH renamed AND has a type change (or an unrelated shared column on the same
 * table with a type change) was silently mis-classified as a safe rename instead of
 * {@code TYPE_CHANGE_DETECTED}. This test proves the gap and pins the fix.
 */
class SchemaLifecycleExecutorRenameTypeChangeGapTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
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
    void renameCombinedWithTypeChangeOnTheSameColumnIsTypeChangeDetected() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID, old_name VARCHAR(50), version BIGINT)");
        }
        // Field renamed old_name -> new_name AND its declared type widened/changed to BIGINT --
        // the live column (still named old_name) is VARCHAR, so this is not a pure rename.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "UUID", "new_name", "BIGINT", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "a rename combined with a type change on the same column must classify as TYPE_CHANGE_DETECTED, not RENAME_DETECTED");
    }

    @Test
    void renameAloneWithMatchingTypeStaysRenameDetected() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID, old_name VARCHAR(50), version BIGINT)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "UUID", "new_name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.RENAME_DETECTED,
                executor.classify(dataSource, manifest),
                "a pure rename with an unchanged type must still classify as RENAME_DETECTED (control case)");
    }

    @Test
    void renameOnOneColumnDoesNotMaskTypeChangeOnAnUnrelatedSharedColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID, old_name VARCHAR(50), shared_col VARCHAR(50), version BIGINT)");
        }
        // old_name -> new_name is a clean rename (type unchanged), but shared_col (not renamed at
        // all) also has a declared type change on this same table -- must not be masked.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "shared_col", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of(
                        "id", "UUID",
                        "new_name", "VARCHAR(50)",
                        "shared_col", "BIGINT",
                        "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "an unrelated shared column's type change on a table that also has a rename must not be masked");
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
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED"
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
