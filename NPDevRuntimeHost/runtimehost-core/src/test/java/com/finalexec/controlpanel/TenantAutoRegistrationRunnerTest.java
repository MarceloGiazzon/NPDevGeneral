package com.finalexec.controlpanel;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QUAL-41/EXT-10: targeted BOOT-TIME coverage for {@link TenantAutoRegistrationRunner}, the
 * {@code ApplicationRunner} that fires exactly once at every application boot to reconcile any
 * tenant present in the identity pack's own users table but missing from {@code npdev_tenant} (a
 * profile-seeded workspace, a restored dump, or a row written by a path that bypassed
 * {@code IdentityProvisioning.ensureTenantRegistered}). Its own javadoc requires it to be
 * "fail-open, best-effort ... this must never block application startup" -- the single most
 * important property to prove for anything that runs unconditionally on every boot, and exactly
 * the shape of gap this module's defect history (REG-177's own boot-crash regression) warns about.
 * Not itself measured by {@code check-coverage-ratchet.py}'s RuntimeHost JaCoCo report
 * (runtimehost-core has no jacoco plugin applied -- see {@code IdentityProvisioningTest}'s javadoc
 * for the same, already-documented limitation), but real regression protection for a class that
 * runs on every single generated app's boot path.
 */
class TenantAutoRegistrationRunnerTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    private static CompiledModel modelWithoutIdentityPack() {
        return new CompiledModel("test", "1.0.0", Map.of());
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> providerReturning(DataSource dataSource) {
        ObjectProvider<DataSource> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return provider;
    }

    private void createSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(200), "
                    + "status VARCHAR(50), created_at_ms BIGINT)");
        }
    }

    @Test
    void noOpWhenIdentityPackIsNotComposed() {
        // The provider is deliberately wired to throw if ever touched: run() must return before it
        // ever asks for a DataSource when identity::User is absent from the compiled model.
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> provider = Mockito.mock(ObjectProvider.class);
        TenantAutoRegistrationRunner runner =
                new TenantAutoRegistrationRunner(provider, new ModelHolder(modelWithoutIdentityPack()));

        runner.run(new DefaultApplicationArguments());

        verify(provider, never()).getIfAvailable();
    }

    @Test
    void noOpWhenNoPhysicalDataSourceIsAvailable() {
        // InMemory-mode apps have no DataSource bean at all -- ObjectProvider.getIfAvailable()
        // returns null, and this must not NPE.
        TenantAutoRegistrationRunner runner =
                new TenantAutoRegistrationRunner(providerReturning(null), new ModelHolder(identityModel()));

        runner.run(new DefaultApplicationArguments());
    }

    @Test
    void registersATenantFoundInTheUsersTableWithNoExistingRow() throws Exception {
        createSchema();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO identity_users (id, username, tenant_id) VALUES (RANDOM_UUID(), 'ada', 'acme')")) {
            ps.executeUpdate();
        }
        TenantAutoRegistrationRunner runner =
                new TenantAutoRegistrationRunner(providerReturning(dataSource), new ModelHolder(identityModel()));

        runner.run(new DefaultApplicationArguments());

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM npdev_tenant WHERE tenant_id = 'acme'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "a tenant present in identity_users with no npdev_tenant row must be reconciled");
            assertEquals("ACTIVE", rs.getString(1));
        }
    }

    @Test
    void skipsTheReservedDefaultTenantAndAlreadyRegisteredTenants() throws Exception {
        createSchema();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO identity_users (id, username, tenant_id) VALUES (RANDOM_UUID(), 'x', 'default')");
            s.execute("INSERT INTO identity_users (id, username, tenant_id) VALUES (RANDOM_UUID(), 'y', 'acme')");
            s.execute("INSERT INTO npdev_tenant (tenant_id, display_name, status, created_at_ms) "
                    + "VALUES ('acme', 'acme', 'ACTIVE', 0)");
        }
        TenantAutoRegistrationRunner runner =
                new TenantAutoRegistrationRunner(providerReturning(dataSource), new ModelHolder(identityModel()));

        runner.run(new DefaultApplicationArguments());

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM npdev_tenant")) {
            rs.next();
            assertEquals(1, rs.getInt(1),
                    "the reserved 'default' tenant must never be registered and an already-registered "
                            + "tenant must not be duplicated");
        }
    }

    @Test
    void swallowsSqlFailureInsteadOfBlockingStartup() throws Exception {
        // Deliberately create identity_users but NOT npdev_tenant, so the runner's own reconciling
        // INSERT ... SELECT fails with a real "table not found" -- proving the fail-open contract
        // its own javadoc documents ("any SQL failure is logged, never thrown -- this must never
        // block application startup").
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "tenant_id VARCHAR(120))");
        }
        TenantAutoRegistrationRunner runner =
                new TenantAutoRegistrationRunner(providerReturning(dataSource), new ModelHolder(identityModel()));

        runner.run(new DefaultApplicationArguments());
        // Reaching this line without an exception propagating out of run() IS the assertion.
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws java.sql.SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { throw new java.sql.SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
