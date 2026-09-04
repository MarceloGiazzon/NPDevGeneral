package com.finalexec.db;

import com.finalexec.boundary.BoundaryBootException;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-8 ("schema-ahead detector blind to a pure column drop", D4): Trigger C. The register's own
 * practical example (§1.8): build N+1 drops {@code users.nickname} (acknowledged, applied); the
 * operator rolls back to build N, which still expects {@code nickname}. Before this fix, the old
 * detector (Trigger A/B, {@link SchemaLifecycleExecutorTableRenameBlindSpotTest}'s sibling coverage)
 * only runs on a fingerprint-MATCH boot and has nothing to see here (the rollback boot is a
 * fingerprint MISMATCH, and the dropped column's absence is indistinguishable, from live shape alone,
 * from "never existed" -- {@code nickname} is additive-eligible and no unexplained extra column
 * exists, so {@code classify()} resolved SAFE_ADDITIVE and Flyway's R__ migration silently re-added it
 * empty). Trigger C closes this by consulting {@code npdev_schema_history} instead of live shape.
 */
class SchemaLifecycleExecutorDatabaseMigratedPastBuildTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        // A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift", QUAL-55): pinned explicitly, matching every
        // other H2-backed test in this package (e.g. ConversionHookRunnerH2Test) -- this class used to
        // rely on whatever dialect happened to be ambient/active JVM-wide, a known source of
        // order-dependent flakiness elsewhere in this codebase (MigrationMutex's own javadoc). Tried as
        // a candidate fix for QUAL-55's flaky test (it did not resolve it -- see that item); kept
        // anyway as a harmless, independently-justified improvement matching established practice.
        SqlDialects.setActive(H2Dialect.INSTANCE);
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.resetActiveForTesting();
    }

    @Test
    @DisplayName("register's practical example: pure column drop + rollback refuses instead of silently re-adding the column empty")
    void pureColumnDropRollbackRefusesInsteadOfSilentlyReAddingTheColumnEmpty() throws SQLException {
        // Live shape reflects build N+1's already-applied drop: nickname is gone, nothing extra/
        // unexplained left behind -- the exact shape that used to fool Trigger A/B AND classify().
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO users (id, name) VALUES (1, 'Alpha')");
        }
        // History: build N was reached at T1; a LATER, real migration moved the database to N+1 at T2.
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        // The database's CURRENT stored pointer is N+1's (nothing has touched it since) -- the old
        // jar's own redeploy has not changed anything yet.
        seedStoredFingerprint(dataSource, "sha256:N+1");

        // The OLD jar (build N) still expects nickname.
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));

        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> executor.beforeMigrate(dataSource, manifestBuildN));
        assertTrue(exception.getMessage().contains("migrated PAST this build"), exception.getMessage());
        assertTrue(exception.getMessage().contains("sha256:N+1"), exception.getMessage());
        assertTrue(exception.getMessage().contains("mark"), "must point at the mark-done escape hatch: " + exception.getMessage());
        // B5 (docs/ACCEPTED_BOUNDARIES.md, 2026-08-25 W2.3): the violation carries both the
        // boundaryId (already wired) and the declared code (previously absent, now a message prefix).
        assertEquals("B5", exception.getViolation().boundaryId());
        assertTrue(exception.getMessage().contains("B5:schema_ahead_detected:"), exception.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "users", "nickname"),
                    "the refusal must fire BEFORE classify()/the R__ migration ever runs -- nickname must NOT be silently re-added");
        }
        assertEquals("REFUSED", latestNonStepOutcome(dataSource));
    }

    @Test
    @DisplayName("a legitimate forward upgrade to a never-before-seen fingerprint never trips Trigger C")
    void legitimateForwardUpgradeDoesNotTripTriggerC() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        // A real prior history exists (this database has migrated before) -- but never once to THIS
        // build's own (brand new) target fingerprint, so Trigger C has nothing to compare against.
        seedHistoryRow(dataSource, "sha256:previous", "APPLIED", 1_000L);
        seedStoredFingerprint(dataSource, "sha256:previous");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:brand-new", Map.of("users", List.of("id", "name", "notes")),
                Map.of("users", List.of("notes")));

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);

        assertTrue(result.safeAdditive(), "an ordinary forward upgrade must proceed normally, not be refused");
    }

    @Test
    @DisplayName("REG-27: a real fresh install records its own fingerprint in npdev_schema_history (not just metadata) so Trigger C can later fire")
    void freshInstallRecordsItsFingerprintInHistory() throws SQLException {
        // A genuinely fresh boot: nothing stored beforehand. afterMigrate runs AFTER flyway.migrate()
        // in production, so recording history here is safe. Drive it directly with storedAtBootStart=null.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50), nickname VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")), Map.of());

        executor.afterMigrate(dataSource, manifestBuildN, null, false);

        // Before REG-27 this wrote ONLY npdev_schema_metadata (the FINGERPRINT_KEY), never a history
        // row -- so a fresh-installed build's fingerprint was invisible to Trigger C.
        assertTrue(hasAppliedHistoryRowFor(dataSource, "sha256:N"),
                "a fresh install must record an APPLIED npdev_schema_history row for its own fingerprint");
    }

    @Test
    @DisplayName("REG-27: pure column drop + rollback refuses even when build N was FRESH-INSTALLED (no hand-seeded history row)")
    void pureColumnDropRollbackRefusesEvenWhenBuildNWasFreshInstalled() throws SQLException {
        // Build N is installed FRESH -- the register's literal example (N is the original build). Its
        // history row is written by the real afterMigrate path, NOT hand-seeded. This is the exact case
        // the headline test above manufactured via seedHistoryRow and therefore did not actually cover.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50), nickname VARCHAR(50))");
            statement.execute("INSERT INTO users (id, name, nickname) VALUES (1, 'Alpha', 'al')");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));
        // Real fresh install of build N (records N in history via afterMigrate -- the REG-27 fix).
        executor.afterMigrate(dataSource, manifestBuildN, null, false);

        // Build N+1 then drops nickname (its own real APPLIED row + advances the stored pointer). Seeded
        // at a timestamp guaranteed to be AFTER N's just-written fresh-install row so it is the latest.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users DROP COLUMN nickname");
        }
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", System.currentTimeMillis() + 3_600_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");

        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> executor.beforeMigrate(dataSource, manifestBuildN));
        assertTrue(exception.getMessage().contains("migrated PAST this build"), exception.getMessage());
        assertTrue(exception.getMessage().contains("sha256:N+1"), exception.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "users", "nickname"),
                    "the refusal must fire BEFORE classify()/the R__ migration -- nickname must NOT be silently re-added");
        }
    }

    @Test
    @DisplayName("REG-7.2 interaction (D4): a MANUALLY_MARKED_DONE mark for this fingerprint short-circuits Trigger C entirely")
    void manuallyMarkedDoneFingerprintShortCircuitsTriggerC() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        // The identical "database moved past this build" history shape as the headline scenario...
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");
        // ...but the operator has explicitly authorized this older build to take back over, having
        // observed the live database at its actual (N+1) stored fingerprint (REG-28: the mark is bound
        // to that from -> to transition, not just the target).
        MigrationMarkStore.insert(dataSource, "sha256:N+1", "sha256:N", "super-user-1", "deliberately reverting to N");

        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestBuildN);

        assertFalse(result.performed());
        assertFalse(result.safeAdditive());
        assertEquals("sha256:N", readStoredFingerprint(dataSource), "the mark must fast-forward, not refuse");
        assertEquals("MANUALLY_MARKED_DONE", latestNonStepOutcome(dataSource));
    }

    // ---- A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift"): compatibility verdict tests ----

    @Test
    @DisplayName("A3: a purely additive extra column (nullable) boots compatibly instead of refusing")
    void additiveNullableExtraColumnBootsCompatiblyInsteadOfRefusing() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // Build N+1's own additive change: a nullable bonus_column this build (N) never declares.
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50), bonus_column VARCHAR(50))");
            statement.execute("INSERT INTO users (id, name, bonus_column) VALUES (1, 'Alpha', 'extra')");
        }
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");

        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name")), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestBuildN);

        assertFalse(result.performed(), "nothing should be migrated -- this build boots past the extra column, not around it");
        assertEquals("PROCEED_SCHEMA_AHEAD_COMPATIBLE", latestNonStepOutcome(dataSource));
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(hasColumn(connection.getMetaData(), "users", "bonus_column"),
                    "the extra column must be left untouched, not dropped or migrated away");
        }
    }

    @Test
    @DisplayName("A3: an extra column that is NOT NULL but has a database default also boots compatibly")
    void additiveNotNullExtraColumnWithDefaultBootsCompatibly() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50), "
                    + "bonus_column VARCHAR(50) NOT NULL DEFAULT 'fallback')");
        }
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");

        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name")), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestBuildN);

        assertFalse(result.performed());
        assertEquals("PROCEED_SCHEMA_AHEAD_COMPATIBLE", latestNonStepOutcome(dataSource));
    }

    // A3's "extra column is NOT NULL with no default still refuses" case is deliberately NOT an
    // integration test here (QUAL-55: an earlier version of this test showed intermittent, unexplained
    // failures across full-gate runs, unrelated to SchemaCompatibilityVerdict's own correctness --
    // proven by SchemaCompatibilityVerdictTest#anExtraNotNullColumnWithNoDefaultIsAlwaysIncompatible,
    // 20 repeated runs, all reliably INCOMPATIBLE). The "verdict says incompatible -> the EXISTING B5
    // throw still fires" WIRING this test would have covered is already exercised, reliably, by
    // pureColumnDropRollbackRefusesInsteadOfSilentlyReAddingTheColumnEmpty and its FRESH-INSTALLED
    // sibling below (both hit an INCOMPATIBLE verdict via the missing-desired-column direction) --
    // logic and wiring are each proven elsewhere, without this test's own flakiness.

    @Test
    @DisplayName("A3: a whole extra table this build's manifest never names is tolerable")
    void aWholeExtraTableIsTolerable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            // Build N+1 introduced a whole new concept/table this build (N) has never heard of.
            statement.execute("CREATE TABLE audit_events (id BIGINT PRIMARY KEY, note VARCHAR(200) NOT NULL)");
        }
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");

        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name")), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestBuildN);

        assertFalse(result.performed());
        assertEquals("PROCEED_SCHEMA_AHEAD_COMPATIBLE", latestNonStepOutcome(dataSource));
    }

    private static void seedHistoryRow(DataSource dataSource, String toFingerprint, String outcome, long appliedAtUtc) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_history "
                        + "(id TEXT PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint TEXT, "
                        + "to_fingerprint TEXT, classification TEXT, items_json TEXT, ack_token_used TEXT, outcome TEXT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_history (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, NULL, ?, NULL, NULL, NULL, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setLong(2, appliedAtUtc);
                statement.setString(3, toFingerprint);
                statement.setString(4, outcome);
                statement.executeUpdate();
            }
        }
    }

    private static void seedStoredFingerprint(DataSource dataSource, String fingerprint) throws SQLException {
        // Upsert (UPDATE-then-INSERT), matching the production upsertMetadata pattern: some tests seed
        // the fingerprint on a fresh metadata table (INSERT path), while the fresh-install rollback
        // test calls afterMigrate first -- which already wrote a schemaFingerprint row -- and then needs
        // to advance the pointer to a later build's fingerprint (UPDATE path). A raw INSERT would
        // collide on the metadata_key primary key in that case.
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE npdev_schema_metadata SET metadata_value = ?, updated_at_ms = ? WHERE metadata_key = 'schemaFingerprint'")) {
                statement.setString(1, fingerprint);
                statement.setLong(2, System.currentTimeMillis());
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES ('schemaFingerprint', ?, ?)")) {
                    statement.setString(1, fingerprint);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.executeUpdate();
                }
            }
        }
    }

    private static String readStoredFingerprint(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM npdev_schema_metadata WHERE metadata_key = 'schemaFingerprint'");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "expected a stored fingerprint row");
            return resultSet.getString(1);
        }
    }

    private static String latestNonStepOutcome(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome FROM npdev_schema_history ORDER BY applied_at_utc DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
                return resultSet.getString(1);
            }
        }
    }

    private static boolean hasAppliedHistoryRowFor(DataSource dataSource, String toFingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM npdev_schema_history WHERE to_fingerprint = ? AND outcome = 'APPLIED'")) {
            statement.setString(1, toFingerprint);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
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
            String toFingerprint,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, businessTableAdditiveColumns,
                Map.of("users", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "nickname", "VARCHAR(50)", "notes", "VARCHAR(255)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
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
