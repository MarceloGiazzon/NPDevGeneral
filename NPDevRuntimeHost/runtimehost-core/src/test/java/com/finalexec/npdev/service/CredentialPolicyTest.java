package com.finalexec.npdev.service;

import com.npdev.kernel.ports.ApiKeyCredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INT-8: label / expiry / last-used / tenant-pinned administration of runtime-issued API keys, plus
 * the {@link CredentialAdminPolicy} rules the admin controller delegates to. H2 in-memory.
 */
class CredentialPolicyTest {

    private static final long T0 = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();

    private DataSource dataSource;
    private MutableClock clock;
    private CredentialRegistryService service;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE npdev_api_credential (credential_id VARCHAR(64) PRIMARY KEY, "
                    + "key_hash VARCHAR(64), tenant_id VARCHAR(120), actor_id VARCHAR(120), "
                    + "roles VARCHAR(255), status VARCHAR(32), created_at_ms BIGINT)");
            s.execute("CREATE TABLE npdev_api_credential_policy (credential_id VARCHAR(64) PRIMARY KEY, "
                    + "label VARCHAR(255), expires_at_ms BIGINT, last_used_at_ms BIGINT, created_by VARCHAR(255))");
        }
        clock = new MutableClock(T0);
        service = new CredentialRegistryService(new FixedProvider(dataSource), clock);
    }

    @Test
    void expiredKeyStopsResolving() {
        Map<String, Object> issued = service.issue("acme", "erp", Set.of("USER"), "ERP", T0 + 1_000, "admin1");
        String key = (String) issued.get("apiKey");

        assertTrue(service.resolve(key).isPresent(), "valid before the expiry");
        clock.set(T0 + 999);
        assertTrue(service.resolve(key).isPresent(), "still valid 1 ms before the expiry");
        clock.set(T0 + 1_000);
        assertTrue(service.resolve(key).isEmpty(), "expired exactly at expires_at_ms");
    }

    @Test
    void expiryInThePastIsRejectedAtIssue() {
        assertThrows(IllegalArgumentException.class,
                () -> service.issue("acme", "erp", Set.of("USER"), "ERP", T0 - 1, "admin1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.issue("acme", "erp", Set.of("USER"), "ERP", T0, "admin1"));
    }

    @Test
    void legacyKeyWithoutPolicyRowStillResolvesAndGainsALastUsedStamp() throws SQLException {
        String rawKey = "npk_legacy";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO npdev_api_credential VALUES ('legacy-1', '"
                    + CredentialRegistryService.hash(rawKey) + "', 'acme', 'old', 'ADMIN', 'ACTIVE', 1)");
        }

        Optional<ApiKeyCredentialResolver.Principal> resolved = service.resolve(rawKey);

        assertTrue(resolved.isPresent());
        assertEquals("acme", resolved.get().tenantId());
        assertEquals(Long.valueOf(T0), scalar("SELECT last_used_at_ms FROM npdev_api_credential_policy WHERE credential_id = 'legacy-1'"));
        assertNull(scalarObject("SELECT expires_at_ms FROM npdev_api_credential_policy WHERE credential_id = 'legacy-1'"));
        // and a second call (policy row now exists) keeps working
        assertTrue(service.resolve(rawKey).isPresent());
    }

    @Test
    void lastUsedIsWrittenOnceAndRefreshedOnlyAfterSixtySeconds() throws SQLException {
        Map<String, Object> issued = service.issue("acme", "erp", Set.of("USER"), "ERP", null, null);
        String key = (String) issued.get("apiKey");
        String id = (String) issued.get("credentialId");
        String sql = "SELECT last_used_at_ms FROM npdev_api_credential_policy WHERE credential_id = '" + id + "'";
        assertNull(scalarObject(sql), "never used yet");

        assertTrue(service.resolve(key).isPresent());
        assertEquals(Long.valueOf(T0), scalar(sql));

        clock.set(T0 + 30_000);
        assertTrue(service.resolve(key).isPresent());
        assertEquals(Long.valueOf(T0), scalar(sql), "within 60 s: not rewritten");

        clock.set(T0 + 61_000);
        assertTrue(service.resolve(key).isPresent());
        assertEquals(Long.valueOf(T0 + 61_000), scalar(sql), "after 60 s: refreshed");
    }

    @Test
    void listIsTenantFilteredAndShowsLabelExpiryAndLastUsed() {
        Map<String, Object> a = service.issue("a", "erp", Set.of("USER"), "ERP", T0 + 5_000, "admin-a");
        service.issue("b", "other", Set.of("USER"), "Zapier", null, "admin-b");
        service.resolve((String) a.get("apiKey"));

        List<Map<String, Object>> onlyA = service.list("a");
        assertEquals(1, onlyA.size());
        Map<String, Object> row = onlyA.get(0);
        assertEquals("a", row.get("tenantId"));
        assertEquals("ERP", row.get("label"));
        assertEquals(T0 + 5_000, row.get("expiresAtMs"));
        assertEquals(T0, row.get("lastUsedAtMs"));
        assertEquals("admin-a", row.get("createdBy"));
        assertEquals(false, row.get("expired"));

        assertEquals(2, service.list().size(), "no filter lists every tenant (SUPERUSER view)");
        assertEquals(2, service.list(null).size());

        clock.set(T0 + 5_000);
        assertEquals(true, service.list("a").get(0).get("expired"));
    }

    @Test
    void revokeWithTenantFilterNeverTouchesAnotherTenantsKey() {
        Map<String, Object> b = service.issue("b", "other", Set.of("USER"), null, null, null);
        String idB = (String) b.get("credentialId");
        String keyB = (String) b.get("apiKey");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.revoke(idB, "a"));
        assertTrue(ex.getMessage().startsWith("Unknown credential"));
        assertTrue(service.resolve(keyB).isPresent(), "tenant b's key is untouched");

        service.revoke(idB, "b");
        assertTrue(service.resolve(keyB).isEmpty());
    }

    @Test
    void policyRulesPinATenantAdminToItsOwnTenant() {
        assertEquals("a", CredentialAdminPolicy.effectiveTenant(false, "a", null));
        assertEquals("a", CredentialAdminPolicy.effectiveTenant(false, "a", " a "));
        assertThrows(CredentialAdminPolicy.ForbiddenException.class,
                () -> CredentialAdminPolicy.effectiveTenant(false, "a", "b"));
        assertEquals("b", CredentialAdminPolicy.effectiveTenant(true, "a", "b"));
        assertEquals("a", CredentialAdminPolicy.tenantFilter(false, "a"));
        assertNull(CredentialAdminPolicy.tenantFilter(true, "a"));
    }

    @Test
    void policyRulesValidateRolesAgainstBuiltInsAndTheModel() {
        List<String> model = List.of("ErpReader");
        assertEquals(Set.of("ERPREADER"), CredentialAdminPolicy.validateRoles(List.of("erpreader"), model, false));
        assertEquals(Set.of("ADMIN", "USER"), CredentialAdminPolicy.validateRoles(List.of("Admin", "user"), model, false));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> CredentialAdminPolicy.validateRoles(List.of("ErpRader"), model, false));
        assertEquals("unknown role: ErpRader", unknown.getMessage());
        assertThrows(CredentialAdminPolicy.ForbiddenException.class,
                () -> CredentialAdminPolicy.validateRoles(List.of("SUPERUSER"), model, false));
        assertEquals(Set.of("SUPERUSER"), CredentialAdminPolicy.validateRoles(List.of("SUPERUSER"), model, true));
    }

    @Test
    void policyRulesParseExpiry() {
        assertNull(CredentialAdminPolicy.parseExpiry(null, clock));
        assertNull(CredentialAdminPolicy.parseExpiry("  ", clock));
        assertEquals(Instant.parse("2026-10-17T12:00:00Z").toEpochMilli(),
                CredentialAdminPolicy.parseExpiry("2026-10-17T12:00:00Z", clock));
        assertEquals(Instant.parse("2026-10-18T00:00:00Z").toEpochMilli(),
                CredentialAdminPolicy.parseExpiry("2026-10-17", clock), "a plain date is valid through the end of that day (UTC)");
        assertThrows(IllegalArgumentException.class, () -> CredentialAdminPolicy.parseExpiry("2026-10-15", clock));
        assertThrows(IllegalArgumentException.class, () -> CredentialAdminPolicy.parseExpiry("next tuesday", clock));
    }

    private Long scalar(String sql) throws SQLException {
        Object value = scalarObject(sql);
        assertNotNull(value, sql);
        return ((Number) value).longValue();
    }

    private Object scalarObject(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getObject(1);
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void set(long millis) {
            this.millis = millis;
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
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
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
