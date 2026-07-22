package com.finalexec.npdev.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantRegistryServiceTest {

    private TenantRegistryService service;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(255), status VARCHAR(32), created_at_ms BIGINT, persistence_mode VARCHAR(32))");
        }
        service = new TenantRegistryService(new FixedProvider(dataSource));
    }

    @Test
    void createThenListReturnsTheTenant() {
        service.create("acme", "Acme Corp");
        List<Map<String, Object>> tenants = service.list();
        assertEquals(1, tenants.size());
        assertEquals("acme", tenants.get(0).get("tenantId"));
        assertEquals("ACTIVE", tenants.get(0).get("status"));
    }

    @Test
    void isActiveFailsOpenForUnknownTenant() {
        assertTrue(service.isActive("never-registered"));
        assertTrue(service.isActive(null));
    }

    @Test
    void disableThenEnableTogglesIsActive() {
        service.create("acme", "Acme");
        assertTrue(service.isActive("acme"));

        service.setStatus("acme", TenantRegistryService.Status.DISABLED);
        assertFalse(service.isActive("acme"));

        service.setStatus("acme", TenantRegistryService.Status.ACTIVE);
        assertTrue(service.isActive("acme"));
    }

    @Test
    void tenantIdIsNormalizedToLowercase() {
        service.create("ACME", "Acme");
        // disable via mixed case resolves to the same normalized id
        service.setStatus("Acme", TenantRegistryService.Status.DISABLED);
        assertFalse(service.isActive("acme"));
    }

    @Test
    void setStatusOnUnknownTenantIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setStatus("ghost", TenantRegistryService.Status.DISABLED));
    }

    @Test
    void creatingTheReservedDefaultTenantIdIsRejected() {
        // "default" is a reserved sentinel in DefaultExecutionAuthorizationPolicy meaning
        // "no tenant registered" -- registering it as a real tenant would make every
        // flow/event/execution silently 403 under it (ARCH-15). Fail fast instead.
        assertThrows(IllegalArgumentException.class, () -> service.create("default", "Default"));
        assertThrows(IllegalArgumentException.class, () -> service.create("DEFAULT", "Default"));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void creatingADuplicateTenantIdThrowsADistinguishableException() {
        // Previously this fell into the generic SQLException catch and surfaced as
        // IllegalStateException -> 503 Service Unavailable, conflating "tenant already exists"
        // with a real database outage. Must be its own exception type so the controller can map
        // it to 409 Conflict instead.
        service.create("acme", "Acme Corp");
        assertThrows(TenantRegistryService.TenantAlreadyExistsException.class,
                () -> service.create("acme", "Acme Corp Again"));
    }

    private record FixedProvider(DataSource dataSource) implements ObjectProvider<DataSource> {
        @Override public DataSource getObject(Object... args) { return dataSource; }
        @Override public DataSource getObject() { return dataSource; }
        @Override public DataSource getIfAvailable() { return dataSource; }
        @Override public DataSource getIfAvailable(Supplier<DataSource> defaultSupplier) { return dataSource; }
        @Override public DataSource getIfUnique() { return dataSource; }
        @Override public DataSource getIfUnique(Supplier<DataSource> defaultSupplier) { return dataSource; }
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
