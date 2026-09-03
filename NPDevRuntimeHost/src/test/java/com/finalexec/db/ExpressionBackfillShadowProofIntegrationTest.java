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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 (REAL_LIFT_PLAN_2026-09-03, B2 "real lift"): end-to-end tests for {@link
 * ExpressionBackfillShadowProof#prove} against real H2 -- the full plumbing ({@link
 * ExpressionBackfillPreview#evaluateRows} called twice, results compared).
 */
class ExpressionBackfillShadowProofIntegrationTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void aFieldCopyExpressionProvesSafeAndCarriesEveryRowsRealValue() throws SQLException {
        exec("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity BIGINT)");
        exec("INSERT INTO widgets (id, quantity) VALUES (1, 10)");
        exec("INSERT INTO widgets (id, quantity) VALUES (2, 20)");

        try (Connection connection = dataSource.getConnection()) {
            ExpressionBackfillShadowProof.ShadowProofResult result =
                    ExpressionBackfillShadowProof.prove(connection, "widgets", "auditQuantity", "$quantity");

            assertTrue(result.safe(), result.toString());
            assertEquals(10L, result.provenValues().get(1L));
            assertEquals(20L, result.provenValues().get(2L));
            assertTrue(result.unpopulatedRowIds().isEmpty());
            assertTrue(result.nondeterministicRowIds().isEmpty());
        }
    }

    @Test
    void aFunctionCallExpressionCanNeverProveSafeInThisEvaluationPath() throws SQLException {
        // A2's own honesty fix (ExpressionBackfillPreview#evaluateRows): no FunctionRegistry is ever
        // wired for this evaluation path (REG-202's own note on why HIGH_RISK is unreachable in
        // practice -- verified here to apply equally to REVIEWABLE, since ANY function call, not only
        // a scope.* one, cannot resolve). Before that fix, this would have silently "succeeded" with
        // the raw expression text as its "value" for every row; now every row is honestly unpopulated.
        exec("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity BIGINT)");
        exec("INSERT INTO widgets (id, quantity) VALUES (1, 10)");

        try (Connection connection = dataSource.getConnection()) {
            ExpressionBackfillShadowProof.ShadowProofResult result =
                    ExpressionBackfillShadowProof.prove(connection, "widgets", "auditTag", "riskyLookup(quantity)");

            assertFalse(result.safe());
            assertEquals(java.util.List.of("1"), result.unpopulatedRowIds());
            assertTrue(result.provenValues().isEmpty());
        }
    }

    @Test
    void noAffectedRowsIsTriviallySafeWithNoProvenValues() throws SQLException {
        exec("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity BIGINT)");

        try (Connection connection = dataSource.getConnection()) {
            ExpressionBackfillShadowProof.ShadowProofResult result =
                    ExpressionBackfillShadowProof.prove(connection, "widgets", "auditQuantity", "$quantity");

            assertTrue(result.safe());
            assertTrue(result.provenValues().isEmpty());
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- no H2-specific compile dependency,
     *  mirroring the pattern every other H2 test in this package uses. */
    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
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
