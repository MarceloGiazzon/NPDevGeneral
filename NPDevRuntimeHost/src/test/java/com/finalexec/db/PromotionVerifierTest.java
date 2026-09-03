package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.3 (B10 one-command H2-&gt;Postgres promotion, package 3.3): {@link PromotionVerifier}'s four
 * independent signals, each proven to actually catch what it claims to -- a verifier that always
 * says "verified" is worse than none, since it would hide the exact class of promotion bug
 * ("Postgres started successfully" without the data actually matching) this package exists to
 * prevent. Two real H2 in-memory databases stand in for source/target; the mechanism is fully
 * engine-agnostic (plain JDBC + java-side hashing, no dialect-bound SQL), so this proves the real
 * logic, not a stand-in for it.
 */
class PromotionVerifierTest {

    private DataSource source;
    private DataSource target;

    @AfterEach
    void tearDown() throws SQLException {
        dropAll(source);
        dropAll(target);
    }

    private void dropAll(DataSource dataSource) throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private DataSource freshH2() {
        return new UrlDataSource("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    private static SchemaLifecycleExecutor.SchemaManifest widgetsManifest() {
        // VARCHAR(100), matching createWidgets' own DDL exactly -- readActualColumnTypes reports the
        // live type SIZE-QUALIFIED (qualifyTypeWithSize), so a bare "VARCHAR" here would be a genuine
        // type mismatch under ExternalSchemaVerification, unrelated to anything PromotionVerifier
        // itself is trying to prove.
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:test", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "note")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(100)", "note", "VARCHAR(100)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    private void createWidgets(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(100), note VARCHAR(100))");
        }
    }

    private void insertWidget(DataSource dataSource, long id, String name, String note) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("INSERT INTO widgets (id, name, note) VALUES (?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.setObject(3, note);
            statement.executeUpdate();
        }
    }

    @Test
    void identicalDataOnBothSidesVerifiesClean() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        createWidgets(target);
        insertWidget(source, 1L, "alpha", "first");
        insertWidget(source, 2L, "beta", null);
        insertWidget(target, 1L, "alpha", "first");
        insertWidget(target, 2L, "beta", null);

        PromotionVerifier.VerificationResult result = PromotionVerifier.verify(source, target, widgetsManifest());

        assertTrue(result.allVerified(), () -> "expected all verified: " + result.tables());
        PromotionVerifier.TableVerification table = result.tables().get(0);
        assertTrue(table.rowCountMatches());
        assertTrue(table.contentHashMatches());
        assertTrue(table.nullCountsMatch());
        assertEquals(2L, table.sourceRowCount());
        assertEquals(2L, table.targetRowCount());
    }

    @Test
    void rowInsertedOutOfOrderStillVerifies() throws SQLException {
        // The content hash is a SUM of per-row hashes -- proves it is genuinely order-independent,
        // not accidentally order-sensitive because the two SELECTs happened to return rows in the
        // same sequence.
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        createWidgets(target);
        insertWidget(source, 1L, "alpha", "first");
        insertWidget(source, 2L, "beta", "second");
        // Insert in the OPPOSITE order on the target.
        insertWidget(target, 2L, "beta", "second");
        insertWidget(target, 1L, "alpha", "first");

        PromotionVerifier.VerificationResult result = PromotionVerifier.verify(source, target, widgetsManifest());

        assertTrue(result.allVerified(), () -> "order must not affect the verdict: " + result.tables());
    }

    @Test
    void contentMismatchIsCaughtByTheHashEvenWhenRowCountsMatch() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        createWidgets(target);
        insertWidget(source, 1L, "alpha", "first");
        // Same row count, different content.
        insertWidget(target, 1L, "alpha-CORRUPTED", "first");

        PromotionVerifier.VerificationResult result = PromotionVerifier.verify(source, target, widgetsManifest());

        assertFalse(result.allVerified());
        PromotionVerifier.TableVerification table = result.tables().get(0);
        assertTrue(table.rowCountMatches(), "row counts DO match -- only content differs");
        assertFalse(table.contentHashMatches(), "content hash must catch what row count alone cannot");
    }

    @Test
    void nullVsValueMismatchIsCaughtPerColumnEvenIfHashesHappenToCollide() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        createWidgets(target);
        insertWidget(source, 1L, "alpha", "note-present");
        insertWidget(target, 1L, "alpha", null);

        PromotionVerifier.VerificationResult result = PromotionVerifier.verify(source, target, widgetsManifest());

        assertFalse(result.allVerified());
        PromotionVerifier.TableVerification table = result.tables().get(0);
        assertFalse(table.nullCountsMatch());
        PromotionVerifier.ColumnNullCount noteCounts = table.columnNullCounts().stream()
                .filter(c -> c.column().equals("note")).findFirst().orElseThrow();
        assertEquals(0L, noteCounts.sourceNulls());
        assertEquals(1L, noteCounts.targetNulls());
    }

    @Test
    void missingTargetTableReportsZeroRowsRatherThanThrowing() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        insertWidget(source, 1L, "alpha", "first");
        // target has no widgets table at all.

        PromotionVerifier.VerificationResult result = PromotionVerifier.verify(source, target, widgetsManifest());

        assertFalse(result.allVerified());
        PromotionVerifier.TableVerification table = result.tables().get(0);
        assertEquals(1L, table.sourceRowCount());
        assertEquals(0L, table.targetRowCount());
        assertFalse(table.rowCountMatches());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- matches every sibling test's own copy
     *  in this package (a NEW physical connection per call). */
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
