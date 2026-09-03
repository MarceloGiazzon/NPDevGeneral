package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;
import org.flywaydb.core.Flyway;
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

/**
 * 3.3 (B10 one-command H2-&gt;Postgres promotion, package 3.3): the correctness property
 * {@link PromoteMain}'s whole cross-engine design depends on -- {@link
 * SchemaLifecycleExecutor.SchemaManifest#withEngine} actually changes which dialect
 * {@code SchemaLifecycleExecutor.migrate}'s {@code pinDialectFromManifest} pins, not just which
 * string a getter returns. Proven against a REAL {@code migrate(Flyway, SchemaManifest)} call (an
 * empty {@code Flyway} locations array, matching {@code SchemaLifecycleExecutorMigrationClaimTest}'s
 * own precedent for exercising this method with no real migration content) rather than asserting on
 * the record's fields alone, which would not catch a future change to
 * {@code pinDialectFromManifest}'s own trust of {@code manifest.engine()} silently stop mattering.
 */
class SchemaManifestWithEngineTest {

    private DataSource h2;
    private com.npdev.kernel.storage.sql.SqlDialect previousActive;

    @AfterEach
    void tearDown() throws SQLException {
        // pinDialectFromManifest mutates a GLOBAL static -- restore it so this test cannot leak its
        // pin into a sibling test running later in the same JVM.
        if (previousActive != null) {
            SqlDialects.setActive(previousActive);
        }
        if (h2 != null) {
            try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP ALL OBJECTS");
            }
        }
    }

    @Test
    void withEngineChangesOnlyTheEngineField() {
        SchemaLifecycleExecutor.SchemaManifest original = widgetsManifest("H2Local");

        SchemaLifecycleExecutor.SchemaManifest overridden = original.withEngine("postgres");

        assertEquals("postgres", overridden.engine());
        assertEquals("H2Local", original.engine(), "the original must be unchanged -- records are immutable");
        assertEquals(original.storageMode(), overridden.storageMode());
        assertEquals(original.schemaFingerprint(), overridden.schemaFingerprint());
        assertEquals(original.businessTables(), overridden.businessTables());
        assertEquals(original.businessTableColumns(), overridden.businessTableColumns());
        assertEquals(original.businessTableColumnTypes(), overridden.businessTableColumnTypes());
        assertEquals(original.strategy(), overridden.strategy());
        assertEquals(original.scope(), overridden.scope());
        assertEquals(original.ownership(), overridden.ownership());
    }

    @Test
    void migrateActuallyPinsTheOverriddenEngineNotTheOriginalOrWhateverWasActive() throws SQLException {
        previousActive = SqlDialects.active();
        // THREE distinct engine values in play, deliberately: what was active before this call
        // (mysql -- irrelevant noise), what the ORIGINAL manifest claims (postgres -- simulating "this
        // manifest describes a postgres-sourced app"), and what withEngine overrides it to (h2 --
        // simulating "but we are realizing schema against an h2 target this time"). Only the LAST one
        // may end up active; either of the other two leaking through would silently misconfigure DDL
        // guard syntax against the real target -- see PromoteMain's own class javadoc.
        SqlDialects.setActive(SqlDialects.forName("mysql"));
        SchemaLifecycleExecutor.SchemaManifest originalManifest = widgetsManifest("postgres");
        SchemaLifecycleExecutor.SchemaManifest overriddenManifest = originalManifest.withEngine("h2");

        h2 = new UrlDataSource("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        Flyway flyway = Flyway.configure().dataSource(h2).locations(new String[0]).load();

        new SchemaLifecycleExecutor().migrate(flyway, overriddenManifest);

        assertEquals("h2", SqlDialects.active().name(),
                "pinDialectFromManifest must have pinned the OVERRIDDEN engine ('h2'), not the "
                        + "manifest's original claim ('postgres') or whatever was active before this call "
                        + "('mysql')");
    }

    private static SchemaLifecycleExecutor.SchemaManifest widgetsManifest(String engine) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                engine, "jdbc", true, "sha256:with-engine-test", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- matches every sibling test's own copy. */
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
