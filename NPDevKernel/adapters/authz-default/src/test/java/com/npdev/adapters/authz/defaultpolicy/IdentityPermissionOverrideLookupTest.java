package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 14 Phase C item C2 (RC-B3): the JDBC lookup against a real (in-memory) identity schema,
 * mirroring the H2 setup {@code IdentityAwareContextResolverTest} (NPDevRuntimeHost) already
 * established for the sibling roles lookup.
 */
class IdentityPermissionOverrideLookupTest {

    // Matches the literal, unversioned table names this test's own setUp() creates -- this test is
    // a pure JDBC-level unit test with no CompiledModel/generator involved, so the real REG-177
    // resolve-from-compiled-model path is exercised elsewhere (IdentityAwareContextResolverTest /
    // live boot verification), not here.
    private static final IdentityPackTableNames TABLES = new IdentityPackTableNames(
            "identity_users", "identity_roles", "identity_user_roles", "identity_user_role_permissions");

    private DataSource dataSource;
    private CapturingHandler capturingHandler;
    private Logger logger;

    @BeforeEach
    void setUpLogCapture() {
        logger = Logger.getLogger(IdentityPermissionOverrideLookup.class.getName());
        capturingHandler = new CapturingHandler();
        logger.addHandler(capturingHandler);
    }

    @AfterEach
    void tearDownLogCapture() {
        logger.removeHandler(capturingHandler);
    }

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_role_permissions (id UUID PRIMARY KEY, user_role_id UUID, permission VARCHAR(60), tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('11111111-1111-1111-1111-111111111111','charlie',TRUE,'tenantx')");
            s.execute("INSERT INTO identity_users VALUES ('55555555-5555-5555-5555-555555555555','dormant',FALSE,'tenantx')");
            s.execute("INSERT INTO identity_roles VALUES ('22222222-2222-2222-2222-222222222222','WarehouseManager','tenantx')");
            s.execute("INSERT INTO identity_user_roles VALUES "
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','11111111-1111-1111-1111-111111111111',"
                    + "'22222222-2222-2222-2222-222222222222','tenantx')");
            s.execute("INSERT INTO identity_user_roles VALUES "
                    + "('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','55555555-5555-5555-5555-555555555555',"
                    + "'22222222-2222-2222-2222-222222222222','tenantx')");
            s.execute("INSERT INTO identity_user_role_permissions VALUES "
                    + "('cccccccc-cccc-cccc-cccc-cccccccccccc','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',"
                    + "'EXECUTE_FLOW','tenantx')");
            s.execute("INSERT INTO identity_user_role_permissions VALUES "
                    + "('dddddddd-dddd-dddd-dddd-dddddddddddd','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',"
                    + "'READ_EXECUTIONS','tenantx')");
            // dormant's override row exists too, proving inactive users are excluded from the result.
            s.execute("INSERT INTO identity_user_role_permissions VALUES "
                    + "('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',"
                    + "'READ_AUDIT','tenantx')");
        }
    }

    @Test
    void returnsTheOverrideSetForAnActiveUsersRole() {
        Map<String, Set<String>> overrides =
                IdentityPermissionOverrideLookup.overridesFor(dataSource, TABLES, "tenantx", "charlie");
        assertEquals(Set.of("WarehouseManager"), overrides.keySet());
        assertEquals(Set.of("EXECUTE_FLOW", "READ_EXECUTIONS"), overrides.get("WarehouseManager"));
    }

    @Test
    void inactiveUserYieldsNoOverridesEvenThoughRowsExist() {
        assertTrue(IdentityPermissionOverrideLookup.overridesFor(dataSource, TABLES, "tenantx", "dormant").isEmpty());
    }

    @Test
    void unknownActorYieldsEmptyMap() {
        assertTrue(IdentityPermissionOverrideLookup.overridesFor(dataSource, TABLES, "tenantx", "nobody").isEmpty());
    }

    @Test
    void wrongTenantYieldsEmptyMap() {
        assertTrue(IdentityPermissionOverrideLookup.overridesFor(dataSource, TABLES, "othertenant", "charlie").isEmpty());
    }

    @Test
    void absentTablesFailOpenToEmptyMapNotAnException() {
        DataSource noTables = new SingleConnectionUrlDataSource(
                "jdbc:h2:mem:empty" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        assertTrue(IdentityPermissionOverrideLookup.overridesFor(noTables, TABLES, "tenantx", "charlie").isEmpty());
    }

    /**
     * The security-pattern-sweep gate's own rule (REG-39's shape): a swallowed SQLException that
     * silently resolves to "no restriction" must still be OBSERVABLE, not indistinguishable from an
     * app that genuinely never configured an override. Mirrors
     * IdentityRoleLookupSchemaMismatchTest#staleSchemaMissingTokenVersionReturnsZeroButLogsLoudly.
     */
    @Test
    void absentTablesFailOpenButLogSeverely() {
        DataSource noTables = new SingleConnectionUrlDataSource(
                "jdbc:h2:mem:empty" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

        IdentityPermissionOverrideLookup.overridesFor(noTables, TABLES, "tenantx", "charlie");

        assertTrue(capturingHandler.records.stream().anyMatch(r ->
                        r.getLevel() == Level.SEVERE && r.getMessage() != null
                                && r.getMessage().toLowerCase().contains("identity_user_role_permissions")),
                "a schema mismatch must be logged loudly, not silently swallowed: " + capturingHandler.records);
    }

    @Test
    void freshSchemaReadsOverridesWithoutSeverelyLogging() {
        IdentityPermissionOverrideLookup.overridesFor(dataSource, TABLES, "tenantx", "charlie");

        assertFalse(capturingHandler.records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE),
                "the fresh-schema path must not log a schema-mismatch severity");
    }

    @Test
    void nullDataSourceYieldsEmptyMap() {
        assertTrue(IdentityPermissionOverrideLookup.overridesFor(null, TABLES, "tenantx", "charlie").isEmpty());
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
