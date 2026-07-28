package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER-P7.5. The Postgres Testcontainers twin of {@link ConversionHookRunnerH2Test}: proves engine
 * detection ({@code ConversionHookRunner}'s {@code detectEngine}) and real transactional SQL execution
 * work identically against Postgres, not just H2, reusing the SAME static fixture hooks under {@code
 * src/test/resources/db/conversion-hooks/} (their claims name literal, unsuffixed table names, so this
 * class creates tables under those exact names). Excluded from the plain {@code test} task by default
 * (Docker required); run via {@code -PincludePostgresMatrix} exactly like {@code
 * SchemaLifecycleExecutorPostgresProofMatrixTest} (GATE-PG). Only the core rule-6 resolve path and one
 * failure path (rule 4) are twinned here -- the exhaustive per-rule matrix is H2-only, per the plan's
 * own "H2-required, Postgres-nightly" framing.
 */
class ConversionHookRunnerPostgresTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("npdev_conversion_hook_test")
            .withUsername("npdev")
            .withPassword("npdev")
            .withReuse(true);

    private DataSource dataSource;
    private List<String[]> history;
    private ConversionHookRunner.HistoryWriter historyWriter;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void leaveContainerRunning() {
        // withReuse(true): deliberately not calling POSTGRES.stop() -- see
        // SchemaLifecycleExecutorPostgresProofMatrixTest's identical javadoc note.
    }

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionUrlDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        history = new ArrayList<>();
        historyWriter = (label, outcome, details) -> history.add(new String[] {label, outcome, String.valueOf(details)});
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS p75_resolve");
            statement.execute("DROP TABLE IF EXISTS p75_verifyfail");
        }
    }

    @Test
    void hookResolvesNeedsHookItemOnRealPostgres() throws SQLException {
        exec("CREATE TABLE p75_resolve (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor("p75_resolve");

        Set<String> before = unresolvedKeys(manifest);
        assertTrue(before.stream().anyMatch(k -> k.startsWith("ADD_REQUIRED_COLUMN:p75_resolve:status")), before.toString());

        boolean applied = ConversionHookRunner.run(dataSource, manifest, historyWriter);

        assertTrue(applied);
        Set<String> after = unresolvedKeys(manifest);
        assertTrue(after.isEmpty(), "the hook's common convert.sql (no postgres-specific variant needed) "
                + "must resolve it on Postgres too: " + after);
        assertEquals(0L, singleLongQuery("SELECT COUNT(*) FROM p75_resolve WHERE status IS NULL"));

        List<String> outcomes = history.stream().map(row -> row[1]).toList();
        assertTrue(outcomes.contains("HOOK_APPLIED"), outcomes.toString());
        assertTrue(outcomes.contains("RESOLVED"), outcomes.toString());
    }

    @Test
    void verifyMismatchAbortsOnRealPostgresToo() throws SQLException {
        exec("CREATE TABLE p75_verifyfail (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor("p75_verifyfail");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConversionHookRunner.run(dataSource, manifest, historyWriter));
        assertTrue(exception.getMessage().contains("verification failed"), exception.getMessage());

        List<String> outcomes = history.stream().map(row -> row[1]).toList();
        assertTrue(outcomes.contains("HOOK_VERIFY_FAILED"), outcomes.toString());

        // SER-P7 finding-#1 fix: unlike H2, Postgres has transactional DDL -- the verify ran INSIDE the
        // hook transaction, so the failure rolled the WHOLE hook back, INCLUDING the column its
        // convert.sql ADDed. This is the full atomicity the fix delivers on the production engine.
        assertEquals(0L, singleLongQuery("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'p75_verifyfail' AND column_name = 'status'"),
                "Postgres rolls the hook's DDL back on a verify failure -- 'status' must not persist");
    }

    private void exec(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long singleLongQuery(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : -1L;
        }
    }

    private Set<String> unresolvedKeys(SchemaLifecycleExecutor.SchemaManifest manifest) {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Set<String> keys = new LinkedHashSet<>();
        for (SchemaDiffItem item : diff.items()) {
            keys.add(item.itemKey());
        }
        return keys;
    }

    /** {@code table}'s desired schema is {@code id, status} with {@code status} required and NOT
     *  additive-eligible -- a NEEDS_HOOK item matching the fixture hook (under {@code
     *  src/test/resources/db/conversion-hooks/}) that claims {@code
     *  ADD_REQUIRED_COLUMN:<table>:status}. */
    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(String table) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:p75-postgres-test",
                List.of(), List.of(table),
                Map.of(table, List.of("id", "status")),
                Map.of(table, List.of()),
                Map.of(table, Map.of("id", "BIGINT", "status", "VARCHAR(20)")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(table, List.of("status")), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}, mirroring
     *  {@code SchemaLifecycleExecutorPostgresProofMatrixTest}'s identically-named fixture. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        private SingleConnectionUrlDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            return DriverManager.getConnection(url, username, pwd);
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
