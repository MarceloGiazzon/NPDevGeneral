package com.finalexec.db;

import com.npdev.kernel.concepts.ValueExpressionFunctions;
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
 * A2 (REAL_LIFT_PLAN_2026-09-03, B2 "real lift"); STOR-26 (B2 lift, 2026-09-05): end-to-end tests
 * for {@link ExpressionBackfillShadowProof#prove} against real H2 -- the full plumbing ({@link
 * ExpressionBackfillPreview#evaluateRows} called twice, results compared), now with a real {@link
 * ValueExpressionFunctions} registry wired in instead of an always-empty one.
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
            ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.prove(
                    connection, "widgets", "auditQuantity", "$quantity", ValueExpressionFunctions.base());

            assertTrue(result.safe(), result.toString());
            assertEquals(10L, result.provenValues().get(1L));
            assertEquals(20L, result.provenValues().get(2L));
            assertTrue(result.unpopulatedRowIds().isEmpty());
            assertTrue(result.nondeterministicRowIds().isEmpty());
        }
    }

    @Test
    void aRegistryKnownFunctionCallExpressionProvesSafeAndCarriesEveryRowsRealValue() throws SQLException {
        // STOR-26 (B2 lift): the whole point of giving this evaluation path a real FunctionRegistry
        // -- a REVIEWABLE candidate (any function call) can now genuinely prove safe, not just a
        // SAFE same-row form. "concat" is one of the five value-behavior functions ValueExpressionFunctions
        // resolves identically to a NEW row's own defaultExpression evaluation.
        exec("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        exec("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
        exec("INSERT INTO widgets (id, name) VALUES (2, 'beta')");

        try (Connection connection = dataSource.getConnection()) {
            ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.prove(
                    connection, "widgets", "auditTag", "concat(name, '-audit')", ValueExpressionFunctions.base());

            assertTrue(result.safe(), result.toString());
            assertEquals("alpha-audit", result.provenValues().get(1L));
            assertEquals("beta-audit", result.provenValues().get(2L));
            assertTrue(result.unpopulatedRowIds().isEmpty());
            assertTrue(result.nondeterministicRowIds().isEmpty());
        }
    }

    @Test
    void anUnresolvableFunctionCallExpressionCanNeverProveSafe() throws SQLException {
        // STOR-26 (B2 lift): a function name the registry genuinely does not implement is still
        // forced unpopulated -- proving the fix only widens what a KNOWN function can do, it does
        // not make every function call look safe. Before the fix this was true for EVERY function
        // call (no registry was ever wired at all); now it is true only for a name the registry
        // truly does not have.
        exec("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity BIGINT)");
        exec("INSERT INTO widgets (id, quantity) VALUES (1, 10)");

        try (Connection connection = dataSource.getConnection()) {
            ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.prove(
                    connection, "widgets", "auditTag", "riskyLookup(quantity)", ValueExpressionFunctions.base());

            assertFalse(result.safe());
            assertEquals(java.util.List.of("1"), result.unpopulatedRowIds());
            assertTrue(result.provenValues().isEmpty());
        }
    }

    @Test
    void noAffectedRowsIsTriviallySafeWithNoProvenValues() throws SQLException {
        exec("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity BIGINT)");

        try (Connection connection = dataSource.getConnection()) {
            ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.prove(
                    connection, "widgets", "auditQuantity", "$quantity", ValueExpressionFunctions.base());

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
