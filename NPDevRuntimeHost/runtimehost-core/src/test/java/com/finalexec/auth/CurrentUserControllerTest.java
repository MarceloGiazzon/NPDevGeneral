package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wave 3 (NPDEV_FEATURE_PLAN_2026-09-24): pins {@link CurrentUserController}'s contract -- returns
 * the CALLER's own {@code identity::User} row (id included) when a valid claims attribute is
 * present, 401 with no claims, 404 when the JWT names a username with no matching row, and 503 when
 * the identity pack is not composed at all. Same H2 schema and {@code ModelHolder}/{@code
 * CompiledModel} construction {@code OAuthGoogleControllerTest} already established.
 */
class CurrentUserControllerTest {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "username VARCHAR(120) UNIQUE, display_name VARCHAR(200), email VARCHAR(200), "
                    + "active BOOLEAN, token_version INT, avatar_url VARCHAR(2048))");
            s.execute("INSERT INTO identity_users (id, tenant_id, username, display_name, email, active) "
                    + "VALUES ('11111111-1111-1111-1111-111111111111', 'dev', 'artist1', 'Artist One', "
                    + "'artist1@example.com', TRUE)");
        }
    }

    private CurrentUserController controller(CompiledModel model) {
        return new CurrentUserController(dataSource, new FakeResolver(), new ModelHolder(model));
    }

    private static MockHttpServletRequest requestWithClaims(Map<String, Object> claims) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (claims != null) {
            request.setAttribute(CLAIMS_ATTRIBUTE, claims);
        }
        return request;
    }

    @Test
    void returnsTheCallersOwnIdentityRow() {
        Map<String, Object> claims = Map.of("sub", "artist1", "tenant_id", "dev");
        ResponseEntity<Map<String, Object>> response = controller(identityModel()).me(requestWithClaims(claims));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("11111111-1111-1111-1111-111111111111", body.get("id"));
        assertEquals("artist1", body.get("username"));
        assertEquals("Artist One", body.get("displayName"));
        assertEquals("artist1@example.com", body.get("email"));
        assertNull(body.get("avatarUrl"));
    }

    @Test
    void unauthenticatedWithNoClaims() {
        ResponseEntity<Map<String, Object>> response = controller(identityModel()).me(requestWithClaims(null));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("unauthenticated", response.getBody().get("error"));
    }

    @Test
    void userNotFoundWhenTheClaimedUsernameHasNoRow() {
        Map<String, Object> claims = Map.of("sub", "no-such-user", "tenant_id", "dev");
        ResponseEntity<Map<String, Object>> response = controller(identityModel()).me(requestWithClaims(claims));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("user_not_found", response.getBody().get("error"));
    }

    @Test
    void identityPackNotComposedWhenNoUserConceptExists() {
        CompiledModel bare = new CompiledModel("test", "1.0.0", new LinkedHashMap<>());
        Map<String, Object> claims = Map.of("sub", "artist1", "tenant_id", "dev");
        ResponseEntity<Map<String, Object>> response = controller(bare).me(requestWithClaims(claims));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("identity_pack_not_composed", response.getBody().get("error"));
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

    /** Resolves claims exactly like {@code JwtAuthenticatedContextResolver}, inlined so this test
     * carries no cross-module test dependency. */
    private static final class FakeResolver implements AuthenticatedContextResolver {
        @Override
        public ExecutionContext resolveFromPrincipal(Map<String, Object> claims, Map<String, String> headers) {
            String tenantId = claims.get("tenant_id") == null ? null : String.valueOf(claims.get("tenant_id"));
            String actorId = claims.get("sub") == null ? null : String.valueOf(claims.get("sub"));
            return ExecutionContext.of(tenantId, actorId);
        }
    }

    /** Same H2 DataSource shape {@code OAuthGoogleControllerTest} uses. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
