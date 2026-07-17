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
 * LNCH-1 Phase 1 prerequisite fix, discovered incidentally while building the rename+type-change
 * gap tests: H2 2.x's live {@code DatabaseMetaData.getColumns} reports {@code TYPE_NAME} as
 * {@code "CHARACTER VARYING"} for a VARCHAR column -- NOT {@code "VARCHAR"} -- while
 * {@code SchemaRealizationEmitter}'s {@code businessTableColumnTypes} manifest values come from
 * {@code SqlTypeSupport.sqlType(...)}, which always emits the canonical {@code "VARCHAR(255)"}
 * form. {@code normalizeSqlType} never accounted for this H2-specific alias (only "JSONB" -> "JSON"
 * was handled), so ANY unchanged VARCHAR/string column going through {@code hasTypeChange} on H2
 * was silently flagged as a type change -- confirmed empirically against the real H2 2.2.224 jar
 * (`com.h2database:h2:2.2.224`) used by this project: a plain `VARCHAR(255)` column reports
 * `TYPE_NAME=CHARACTER VARYING`, while every other type this project emits (BIGINT, UUID, BOOLEAN,
 * DATE, TIMESTAMP WITH TIME ZONE, NUMERIC, INTEGER, JSON) round-trips exactly. This test pins the
 * fix: an unchanged VARCHAR column, diffed by {@code classify()} with realistic
 * canonical-vs-live-H2 type strings, must NOT be misclassified as a type change.
 */
class SchemaLifecycleExecutorVarcharTypeNormalizationTest {

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
    void unchangedVarcharColumnIsNotMisclassifiedAsATypeChangeOnH2() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // Deliberately no "renamedFrom" involvement -- this exercises the plain no-rename
            // hasTypeChange() branch (classify() lines ~302-312), independent of Phase 1's rename step.
            statement.execute("CREATE TABLE widgets (id UUID, name VARCHAR(255), version BIGINT)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", List.of()),
                // Canonical type strings exactly as SqlTypeSupport.sqlType(...) emits them.
                Map.of("widgets", Map.of("id", "UUID", "name", "VARCHAR(255)", "version", "BIGINT"))
        );

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "an unchanged VARCHAR column must not be misclassified as a type change just because "
                        + "H2's live JDBC metadata reports TYPE_NAME=CHARACTER VARYING for it");
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
