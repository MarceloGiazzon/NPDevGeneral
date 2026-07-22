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
 * LNCH-1 Phase 3 prerequisite fix. Before this fix, {@code normalizeSqlType} stripped EVERYTHING
 * from the first {@code '('} onward before comparing types ({@code "VARCHAR(255)"} and
 * {@code "VARCHAR(20)"} both normalized to the bare string {@code "VARCHAR"}), so
 * {@code hasTypeChange()} -- and therefore {@code classify()} -- was blind to any VARCHAR-length
 * or NUMERIC-precision-only change: neither widening nor narrowing was detected, and a table with
 * ONLY that kind of diff was misclassified SAFE_ADDITIVE (i.e. "nothing to do") even though a
 * narrowing (e.g. VARCHAR(255) -> VARCHAR(20)) could silently truncate existing data the moment
 * the additive-eligible check green-lit the (nonexistent, in its view) diff.
 *
 * <p>This test pins the fix: a live H2 table declares {@code name VARCHAR(255)}, the manifest
 * declares the same column as the NARROWER {@code VARCHAR(20)} -- classify() must now report
 * {@code TYPE_CHANGE_DETECTED} for this table, not {@code SAFE_ADDITIVE}. A companion case proves
 * a genuinely unchanged VARCHAR(255) column (the {@code SchemaLifecycleExecutorVarcharTypeNormalizationTest}
 * control case) still classifies SAFE_ADDITIVE after the fix -- the length-aware comparison must
 * not regress the H2 {@code CHARACTER VARYING} alias handling that fix relies on.
 */
class SchemaLifecycleExecutorTypeChangeLengthPrecisionGapTest {

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
    void varcharLengthNarrowingIsNoLongerMisclassifiedAsNoChange() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID, name VARCHAR(255), version BIGINT)");
        }

        // Manifest declares the SAME column name with a NARROWER length -- a length-only diff,
        // no rename, no additive column involved.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "UUID", "name", "VARCHAR(20)", "version", "BIGINT"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "a VARCHAR length-only narrowing must be classified as a type change, not silently treated as no change");
    }

    @Test
    void numericPrecisionNarrowingIsNoLongerMisclassifiedAsNoChange() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE invoices (id UUID, amount NUMERIC(19,2))");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("invoices", List.of("id", "amount")),
                Map.of("invoices", List.of()),
                Map.of("invoices", Map.of("id", "UUID", "amount", "NUMERIC(5,2)"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "a NUMERIC precision-only narrowing must be classified as a type change, not silently treated as no change");
    }

    @Test
    void unchangedVarcharLengthStillClassifiesSafeAdditive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID, name VARCHAR(255), version BIGINT)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "UUID", "name", "VARCHAR(255)", "version", "BIGINT"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "an unchanged VARCHAR(255) column must remain SAFE_ADDITIVE after the length-aware fix (control case, "
                        + "guards against regressing the H2 CHARACTER VARYING alias handling)");
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
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
