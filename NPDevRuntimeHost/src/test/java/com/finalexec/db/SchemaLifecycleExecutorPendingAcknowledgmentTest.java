package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 6 (task 6.2a). H2 integration coverage for {@link SchemaLifecycleExecutor#beforeMigrate}'s
 * NEW read path against {@link PendingSchemaAcknowledgmentStore}: a row written there (simulating a
 * ControlPanel submission on a PREVIOUSLY running app, see {@code SchemaAcknowledgmentController})
 * authorizes the destructive path exactly like the static manifest field does, a stale/foreign row
 * does not, both together still work, and a successfully-applied pass consumes the row it used (see
 * {@link PendingSchemaAcknowledgmentStore}'s class javadoc for the consume-on-use rationale). The
 * core refusal-gate / surgical-execution coverage this file does not repeat lives in
 * {@link SchemaLifecycleExecutorDestructiveItemizationTest}.
 */
class SchemaLifecycleExecutorPendingAcknowledgmentTest {

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
    void pendingAcknowledgmentAloneAuthorizesSurgicalExecutionWhenTheStaticManifestFieldIsEmpty() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest("sha256:new", "");
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestWithoutToken);
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        // The static manifest field stays EMPTY -- only the pending-ack table carries the token,
        // simulating an operator's ControlPanel submission on the previously running app.
        PendingSchemaAcknowledgmentStore.insert(dataSource, "sha256:new", expectedToken, null, "super-user-1");

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestWithoutToken);

        assertTrue(result.performed());
        assertFalse(result.safeAdditive());
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the dropped column must be gone");
            assertTrue(hasColumn(metadata, "widgets", "name"), "a sibling column must be untouched");
        }
    }

    @Test
    void staleFingerprintPendingRowDoesNotAuthorizeAndBootIsRefused() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:new", "");
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        // A pending row for a DIFFERENT (stale) target fingerprint -- e.g. a plan generated before
        // one more model edit. Must not authorize this boot's actual destructive change.
        PendingSchemaAcknowledgmentStore.insert(dataSource, "sha256:some-other-target", expectedToken, null, "super-user-1");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(exception.getMessage().contains("Expected acknowledgment token:"));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"), "a non-matching pending row must not authorize -- DB untouched");
        }

        // The stale row is untouched -- nothing was consumed, since nothing was authorized/applied.
        assertEquals(1, PendingSchemaAcknowledgmentStore.listAll(dataSource).size());
    }

    @Test
    void foreignTokenPendingRowForTheRightFingerprintDoesNotAuthorize() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:new", "");

        // Right fingerprint, WRONG token (e.g. computed against a different item set).
        String foreignToken = DestructiveAckToken.compute("sha256:new", List.of("DROP_COLUMN:widgets:some_other_column:VARCHAR(10)"));
        PendingSchemaAcknowledgmentStore.insert(dataSource, "sha256:new", foreignToken, null, "super-user-1");

        assertThrows(IllegalStateException.class, () -> executor.beforeMigrate(dataSource, manifest));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"), "a foreign token must not authorize -- DB untouched");
        }
    }

    @Test
    void bothStaticManifestFieldAndAMatchingPendingRowStillWorkAndThePendingRowIsConsumed() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifestIdOnly("sha256:new", "");
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestWithoutToken);
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifestWithToken = manifestIdOnly("sha256:new", expectedToken);
        PendingSchemaAcknowledgmentStore.insert(dataSource, "sha256:new", expectedToken, null, "super-user-1");

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestWithToken);

        assertTrue(result.performed());
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"));
        }

        // Consume-on-use: once applied, the matching pending row is gone (whichever source actually
        // authorized it -- here the static field matched first, but the now-redundant pending row for
        // the SAME (fingerprint, token) pair is still cleaned up).
        assertTrue(PendingSchemaAcknowledgmentStore.listAll(dataSource).isEmpty(),
                "the consumed pending-ack row must be deleted after a successful destructive pass");
    }

    @Test
    void pendingAcknowledgmentRowIsConsumedAfterItSuccessfullyAuthorizesASurgicalPass() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifestIdOnly("sha256:new", "");
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestWithoutToken);
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());
        PendingSchemaAcknowledgmentStore.PendingAcknowledgment inserted =
                PendingSchemaAcknowledgmentStore.insert(dataSource, "sha256:new", expectedToken, null, "super-user-1");

        executor.beforeMigrate(dataSource, manifestWithoutToken);

        List<PendingSchemaAcknowledgmentStore.PendingAcknowledgment> remaining = PendingSchemaAcknowledgmentStore.listAll(dataSource);
        assertTrue(remaining.stream().noneMatch(row -> row.id().equals(inserted.id())),
                "the specific row that authorized this pass must be consumed");
    }

    private static void seedStoredFingerprint(DataSource dataSource, String fingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)")) {
                statement.setString(1, "schemaFingerprint");
                statement.setString(2, fingerprint);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private static boolean hasColumn(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (var resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(String toFingerprint, String ackToken) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", ackToken, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** For scenarios whose live table is just {@code (id, legacy_flag)} -- the manifest declares
     * only {@code id}, so the diff is a single, unambiguous DROP_COLUMN of legacy_flag (mirrors
     * {@code SchemaLifecycleExecutorDestructiveItemizationTest#manifestNoBlanket}). */
    private static SchemaLifecycleExecutor.SchemaManifest manifestIdOnly(String toFingerprint, String ackToken) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", ackToken, Map.of(), Map.of(), Map.of(), Map.of());
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
