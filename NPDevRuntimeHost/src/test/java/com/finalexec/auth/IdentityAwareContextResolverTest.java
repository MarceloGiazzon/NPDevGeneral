package com.finalexec.auth;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the supplement-with-fallback contract: persisted identity roles override claim-roles when
 * a matching active user exists for the (tenant, actor); otherwise the delegate's claim-roles stand.
 */
class IdentityAwareContextResolverTest {

    // Delegate that mimics the api-key/JWT path: tenant + actor + a fixed claim-role of USER.
    private static final AuthenticatedContextResolver CLAIMS_DELEGATE = (claims, headers) ->
            ExecutionContext.of(String.valueOf(claims.get("tenant_id")), String.valueOf(claims.get("actor_id")))
                    .withRoles(Set.of("USER"));

    private DataSource dataSource;
    private IdentityAwareContextResolver resolver;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), active BOOLEAN, tenant_id VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('11111111-1111-1111-1111-111111111111','charlie',TRUE,'tenantx',2)");
            s.execute("INSERT INTO identity_users VALUES ('33333333-3333-3333-3333-333333333333','dormant',FALSE,'tenantx',NULL)");
            s.execute("INSERT INTO identity_users VALUES ('44444444-4444-4444-4444-444444444444','eve',TRUE,'tenantx',NULL)");
            s.execute("INSERT INTO identity_roles VALUES ('22222222-2222-2222-2222-222222222222','ADMIN','tenantx')");
            s.execute("INSERT INTO identity_user_roles VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','tenantx')");
            s.execute("INSERT INTO identity_user_roles VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','33333333-3333-3333-3333-333333333333','22222222-2222-2222-2222-222222222222','tenantx')");
        }
        resolver = new IdentityAwareContextResolver(CLAIMS_DELEGATE, new FixedProvider(dataSource));
    }

    private ExecutionContext resolve(String tenant, String actor) {
        return resolver.resolveFromPrincipal(Map.of("tenant_id", tenant, "actor_id", actor), Map.of());
    }

    private ExecutionContext resolveWithTokenVersion(String tenant, String actor, int tv) {
        return resolver.resolveFromPrincipal(
                Map.of("tenant_id", tenant, "actor_id", actor, "tv", tv), Map.of());
    }

    @Test
    void persistedRolesOverrideClaimRolesForMatchingActiveUser() {
        assertEquals(Set.of("ADMIN"), resolve("tenantx", "charlie").roles());
    }

    @Test
    void fallsBackToClaimRolesWhenNoIdentityRecord() {
        assertEquals(Set.of("USER"), resolve("tenantx", "nobody").roles());
    }

    @Test
    void inactiveUserFallsBackToClaimRoles() {
        assertEquals(Set.of("USER"), resolve("tenantx", "dormant").roles());
    }

    @Test
    void identityLookupIsTenantScoped() {
        // Same actor name, different tenant: no identity match -> claim-roles stand.
        assertEquals(Set.of("USER"), resolve("othertenant", "charlie").roles());
    }

    @Test
    void fallsBackWhenIdentityTablesAbsent() {
        IdentityAwareContextResolver noTables =
                new IdentityAwareContextResolver(CLAIMS_DELEGATE, new FixedProvider(emptyDataSource()));
        assertEquals(Set.of("USER"), noTables.resolveFromPrincipal(
                Map.of("tenant_id", "tenantx", "actor_id", "charlie"), Map.of()).roles());
    }

    @Test
    void tokenWithCurrentVersionIsAccepted() {
        assertEquals(Set.of("ADMIN"), resolveWithTokenVersion("tenantx", "charlie", 2).roles());
    }

    @Test
    void tokenWithStaleVersionIsRejected() {
        org.springframework.web.server.ResponseStatusException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolveWithTokenVersion("tenantx", "charlie", 1));
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
    }

    @Test
    void tokenWithNoTvClaimIsNeverRejected() {
        // Pre-LNCH-4 tokens (minted before revocation existed) carry no 'tv' claim at all -- must
        // keep working exactly as before, not be treated as "version 0 vs current 2" and rejected.
        assertEquals(Set.of("ADMIN"), resolve("tenantx", "charlie").roles());
    }

    @Test
    void nullStoredTokenVersionIsTreatedAsZero() {
        // 'eve' has a NULL token_version column (a pre-migration row, or a user who has never had
        // sessions revoked) -- a token minted with tv=0 must still be accepted.
        assertEquals(Set.of("USER"), resolveWithTokenVersion("tenantx", "eve", 0).roles());
    }

    private DataSource emptyDataSource() {
        return new SingleConnectionUrlDataSource(
                "jdbc:h2:mem:empty" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
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
