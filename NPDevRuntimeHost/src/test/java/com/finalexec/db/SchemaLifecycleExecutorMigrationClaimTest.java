package com.finalexec.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-7.3 ("collision detection", D3): H2 coverage for the migration claim
 * {@link SchemaLifecycleExecutor#migrate(Flyway, SchemaLifecycleExecutor.SchemaManifest)} takes around
 * every upgrade boot. Exercises the full entry point (the claim lives there, not in
 * {@code beforeMigrate}) with a real {@link Flyway} instance (empty locations, following
 * {@code SchemaLifecycleExecutorProofMatrixTest} Row 16's precedent).
 *
 * <p>Per D3, this is detect-and-refuse, not a lock -- a real concurrent-insert race is not (and
 * cannot be) proven by a single-threaded H2 test; only "a held claim is detected and a released claim
 * is gone" is asserted here, honestly matching what this mechanism actually guarantees.
 */
class SchemaLifecycleExecutorMigrationClaimTest {

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
    @DisplayName("a normal upgrade boot claims and releases -- no claim left behind afterward")
    void normalBootClaimsAndReleases() throws SQLException {
        Flyway flyway = freshlyBootstrappedFlyway();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        seedStoredFingerprint(dataSource, "sha256:same");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:same");

        executor.migrate(flyway, manifest);

        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(),
                "a successful boot must release its claim -- none may be left behind");
    }

    @Test
    @DisplayName("an existing claim refuses the boot, naming the holder, and is left untouched")
    void existingClaimRefusesAndIsLeftUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        seedStoredFingerprint(dataSource, "sha256:same");
        MigrationClaimStore.Claim otherInstance = MigrationClaimStore.claim(dataSource);

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:same");
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.migrate(flyway, manifest));
        assertTrue(exception.getMessage().contains(otherInstance.instanceId()), exception.getMessage());
        assertTrue(exception.getMessage().contains("clear-claim"), exception.getMessage());

        Optional<MigrationClaimStore.Claim> stillHeld = MigrationClaimStore.current(dataSource);
        assertTrue(stillHeld.isPresent(), "a refused boot must never release a DIFFERENT instance's claim");
        assertEquals(otherInstance.instanceId(), stillHeld.get().instanceId());
    }

    @Test
    @DisplayName("a genuinely fresh boot (no stored fingerprint) never even attempts a claim")
    void freshBootNeverAttemptsAClaim() throws SQLException {
        // No seedStoredFingerprint call -- this simulates the true first-ever boot against a
        // genuinely virgin schema (nothing pre-created, including no claim table -- see this class's
        // javadoc / the executor's own comment at the claim call site: self-bootstrapping ANY table
        // here, including the claim table, before flyway.migrate() runs on a virgin schema breaks
        // Flyway's own baseline detection, which is exactly what motivates skipping the claim here).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:first-ever");
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        executor.migrate(flyway, manifest);

        // The executor itself must never have touched the claim table on this path.
        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(),
                "a fresh boot must never self-bootstrap the claim table at all");
    }

    @Test
    @DisplayName("clearing a stale claim (the escape hatch) allows the next boot to claim normally")
    void clearingAStaleClaimAllowsTheNextBootToProceed() throws SQLException {
        Flyway flyway = freshlyBootstrappedFlyway();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        seedStoredFingerprint(dataSource, "sha256:same");
        MigrationClaimStore.claim(dataSource); // simulate a crashed holder that never released

        MigrationClaimStore.clear(dataSource);

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:same");

        executor.migrate(flyway, manifest);

        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(), "the boot's own claim must also be released on success");
    }

    @Test
    @DisplayName("ExternallyManaged mode never claims -- NPDev issues no DDL, so there is nothing to serialize")
    void externallyManagedModeNeverClaims() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:old"); // an existing fingerprint, so the claim WOULD normally fire
        MigrationClaimStore.Claim otherInstance = MigrationClaimStore.claim(dataSource); // simulate a genuine other holder

        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "Postgres", "jdbc", true, "sha256:new",
                List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of(), Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false,
                "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), "ExternallyManaged");
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        // Must NOT throw the collision error -- ExternallyManaged never reaches the claim logic at all.
        executor.migrate(flyway, manifest);

        // The other instance's (unrelated) claim is untouched -- ExternallyManaged never touched the claim table.
        assertEquals(otherInstance.instanceId(), MigrationClaimStore.current(dataSource).orElseThrow().instanceId());
    }

    @Test
    @DisplayName("a refusal thrown while this boot holds its claim still releases it -- a recoverable refusal must not wedge the database")
    void refusalWhileHoldingOwnClaimStillReleasesIt() throws SQLException {
        // Live shape matches build N+1's already-applied drop (REG-8 Trigger C's canonical shape,
        // reused from SchemaLifecycleExecutorDatabaseMigratedPastBuildTest): nothing left to see from
        // live shape alone, so only npdev_schema_history can catch it.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        // Non-blank stored fingerprint -- (a) this boot DOES acquire its own claim in migrate().
        seedStoredFingerprint(dataSource, "sha256:N+1");

        // The OLD jar (build N) still expects nickname -- Trigger C fires from beforeMigrate, which
        // migrate() calls from inside its try block, AFTER the claim above was already acquired.
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:N", List.of(), List.of("users"),
                Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")),
                Map.of("users", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "nickname", "VARCHAR(50)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.migrate(flyway, manifestBuildN));
        assertTrue(exception.getMessage().contains("migrated PAST this build"), exception.getMessage());

        assertTrue(MigrationClaimStore.current(dataSource).isEmpty(),
                "a refusal thrown while this boot held its OWN claim must still release it in the finally -- "
                        + "otherwise every future boot is wedged by a recoverable refusal");
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
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setLong(2, appliedAtUtc);
                statement.setString(3, toFingerprint);
                statement.setString(4, outcome);
                statement.executeUpdate();
            }
        }
    }

    /**
     * A {@link Flyway} instance that has already run once against the STILL-EMPTY schema, so its own
     * {@code flyway_schema_history} bookkeeping table already exists -- exactly the invariant a real
     * app always has by the time it reaches a second (upgrade) boot. Without this, a test that creates
     * a business table via raw SQL and only THEN calls the real {@code flyway.migrate()} for the very
     * first time would trip Flyway's own "non-empty schema, no history table" guard itself -- a
     * test-fixture artifact (real apps never reach an upgrade boot without a prior successful first
     * boot already having created this table), not a reproduction of the REG-7.2/REG-7.3 self-bootstrap
     * ordering bug those two features' own fixes address.
     */
    private Flyway freshlyBootstrappedFlyway() {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();
        flyway.migrate();
        return flyway;
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

    private static SchemaLifecycleExecutor.SchemaManifest manifestIdOnly(String toFingerprint) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
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
