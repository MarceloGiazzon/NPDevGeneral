package com.finalexec.db;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 9 A4 (docs/ACCEPTED_BOUNDARIES.md B10): the real Testcontainers Postgres proof for
 * {@link CrossEngineDataPromotion}, following {@link SchemaLifecycleExecutorPostgresProofMatrixTest}'s
 * exact lightweight bare-JDBC pattern (a single reused {@link PostgreSQLContainer}). Docker-dependent,
 * so excluded from the plain {@code test} task (see build.gradle) -- run via
 * {@code gradlew test --tests com.finalexec.db.CrossEngineDataPromotionTest -PincludePostgresMatrix}.
 *
 * <p>H2 (source, real data including a JSONB column and a UUID id) -&gt; Postgres (target, schema
 * already realized by hand here exactly as a real boot would realize it) -- proves the typed copy
 * preserves every value (including the JSON round-trip through {@link
 * SchemaDropSnapshotWriter#decodeJsonColumnValue} and back out as a real Postgres {@code jsonb}, not
 * a double-quoted string), the dry-run preview writes nothing, and a target table that doesn't exist
 * yet is reported as a clear per-table failure rather than silently skipped or a thrown exception
 * that hides every other table's result.
 */
@Tag("integration")
class CrossEngineDataPromotionTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("npdev_cross_engine_promotion")
            .withUsername("npdev")
            .withPassword("npdev")
            .withReuse(true);

    private DataSource sourceH2;
    private DataSource targetPostgres;
    private String table;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainerUnlessReused() {
        // withReuse(true): deliberately not stopped here, same reasoning as the proof matrix twin.
    }

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        sourceH2 = new SingleConnectionUrlDataSource(url);
        targetPostgres = new SingleConnectionUrlDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        table = "widgets_" + Math.abs(System.nanoTime() % 1_000_000_000L);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = sourceH2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        try (Connection connection = targetPostgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS \"" + table + "\" CASCADE");
        }
        // No manual claim-table cleanup needed: CrossEngineDataPromotion.apply always releases its
        // claim in a finally block (asserted directly in the tests below), and the claim table itself
        // is a single canonical row keyed by a fixed claim_key, not per-table -- nothing here leaks it.
    }

    @Test
    @DisplayName("preview reports accurate counts and JSONB type-mapping notes, and writes nothing")
    void previewReportsCountsAndNotesWithoutWriting() throws SQLException {
        createSourceTableWithRows(2);
        createTargetTableEmpty();

        CrossEngineDataPromotion.Preview preview =
                CrossEngineDataPromotion.preview(sourceH2, targetPostgres, manifest());

        CrossEngineDataPromotion.TableCounts counts = preview.tableCounts().stream()
                .filter(c -> c.table().equals(table)).findFirst().orElseThrow();
        assertEquals(2, counts.sourceRowCount());
        assertEquals(0, counts.targetRowCountBefore());
        assertTrue(preview.notes().stream().anyMatch(n -> n.table().equals(table) && n.column().equals("payload")),
                "JSONB column must be surfaced as a type-mapping note");
        assertEquals(0, countTargetRows(), "preview must write nothing to the target");
    }

    @Test
    @DisplayName("apply copies every row, preserving real JSON content, and reports allMatched")
    void applyCopiesRowsAndPreservesJson() throws SQLException {
        UUID id1 = createSourceTableWithRows(2);
        createTargetTableEmpty();

        CrossEngineDataPromotion.PromotionResult result =
                CrossEngineDataPromotion.apply(sourceH2, targetPostgres, manifest());

        assertTrue(result.allMatched(), result.tables().toString());
        CrossEngineDataPromotion.TableCopyResult tableResult = result.tables().get(0);
        assertEquals(2, tableResult.sourceRowCount());
        assertEquals(2, tableResult.rowsCopied());
        assertEquals(2, tableResult.targetRowCountAfter());
        assertEquals(2, countTargetRows());

        try (Connection connection = targetPostgres.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT name, payload FROM \"" + table + "\" WHERE id = ?")) {
            statement.setObject(1, id1);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("alpha", resultSet.getString(1));
                String payload = resultSet.getString(2);
                assertTrue(payload.contains("\"carrier\""), "JSON content must survive the copy: " + payload);
                assertFalse(payload.startsWith("\""), "JSON must be real jsonb, not a double-quoted string: " + payload);
            }
        }

        assertTrue(MigrationClaimStore.current(targetPostgres).isEmpty(),
                "apply must release its migration claim on the target when done");
    }

    @Test
    @DisplayName("apply reports a clear per-table failure (not a thrown exception) when the target table does not exist")
    void applyReportsFailureWhenTargetTableMissing() throws SQLException {
        createSourceTableWithRows(1);
        // Target table deliberately NOT created -- simulates promoting before the target's schema
        // was ever realized.

        CrossEngineDataPromotion.PromotionResult result =
                CrossEngineDataPromotion.apply(sourceH2, targetPostgres, manifest());

        assertFalse(result.allMatched());
        CrossEngineDataPromotion.TableCopyResult tableResult = result.tables().get(0);
        assertFalse(tableResult.matched());
        assertTrue(tableResult.error().contains("does not exist"), tableResult.error());
        assertTrue(MigrationClaimStore.current(targetPostgres).isEmpty(),
                "the claim must still be released even when a table's copy fails");
    }

    private UUID createSourceTableWithRows(int rowCount) throws SQLException {
        try (Connection connection = sourceH2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table
                    + " (id UUID PRIMARY KEY, name VARCHAR(50), payload JSON, version BIGINT, row_version BIGINT, tenant_id VARCHAR(120))");
        }
        UUID firstId = null;
        String[] names = {"alpha", "beta", "gamma"};
        for (int i = 0; i < rowCount; i++) {
            UUID id = UUID.randomUUID();
            if (firstId == null) {
                firstId = id;
            }
            try (Connection connection = sourceH2.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO " + table + " (id, name, payload, version, row_version, tenant_id) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setObject(1, id);
                statement.setString(2, names[i % names.length]);
                statement.setString(3, "{\"carrier\":\"acme\",\"weight\":" + (i + 1) + "}");
                statement.setLong(4, 0L);
                statement.setLong(5, 0L);
                statement.setString(6, "default");
                statement.executeUpdate();
            }
        }
        return firstId;
    }

    private void createTargetTableEmpty() throws SQLException {
        try (Connection connection = targetPostgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"" + table
                    + "\" (id UUID PRIMARY KEY, name VARCHAR(50), payload JSONB, version BIGINT, row_version BIGINT, tenant_id VARCHAR(120))");
        }
    }

    private long countTargetRows() throws SQLException {
        try (Connection connection = targetPostgres.getConnection(); Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private SchemaLifecycleExecutor.SchemaManifest manifest() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:cross-engine-promotion-test",
                List.of(), List.of(table),
                Map.of(table, List.of("id", "name", "payload", "version", "row_version", "tenant_id")),
                Map.of(table, List.of()),
                Map.of(table, Map.of(
                        "id", "UUID",
                        "name", "VARCHAR(50)",
                        "payload", "JSONB",
                        "version", "BIGINT",
                        "row_version", "BIGINT",
                        "tenant_id", "VARCHAR(120)")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; same pattern every H2/Postgres test
     * in this package already duplicates rather than sharing. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;
        private final String username;
        private final String password;

        private SingleConnectionUrlDataSource(String url) {
            this(url, null, null);
        }

        private SingleConnectionUrlDataSource(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return username == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, username, password);
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
