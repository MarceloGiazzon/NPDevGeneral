package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.IdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11: the OAuth authorize/callback HTTP contract -- state is single-use and validated, the
 * signup/login callback yields a session cookie and a clean redirect, the link leg demands a real
 * session and applies the service's linkage rules, and the {@code next} parameter cannot be an
 * open redirect. Hermetic (no Spring context): real service + H2 + a fake provider, with
 * invoked controller methods directly, mirroring {@code LoginControllerTest}.
 */
class OAuthGoogleControllerTest {

    private static final String TENANT = "dev";

    private DataSource dataSource;
    private FakeProvider provider;
    private OAuthGoogleController controller;
    private OAuthStateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "username VARCHAR(120) UNIQUE, display_name VARCHAR(200), email VARCHAR(200), "
                    + "active BOOLEAN, token_version INT, avatar_url VARCHAR(2048), "
                    + "last_login_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
            s.execute("CREATE TABLE identity_external_identity (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "user_id UUID, "
                    + "provider VARCHAR(40), provider_subject VARCHAR(255), linked_at TIMESTAMP, "
                    + "CONSTRAINT ux_provider_subject UNIQUE (provider, provider_subject))");
        }
        provider = new FakeProvider();
        JwtSigner signer = new JwtSigner(new ObjectMapper(),
                JwtSigner.loadPrivateKey(readTestPrivatePem()),
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
        OAuthGoogleAuthService service = new OAuthGoogleAuthService(
                dataSource, new ModelHolder(identityModel()), provider, signer);
        stateStore = new OAuthStateStore(60_000L);
        controller = new OAuthGoogleController(stateStore, provider, service, 28800L);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setSecure(false);
        return request;
    }

    @Test
    void authorizeRedirectsToProviderWithStateAndCallbackRoundTripsASession() throws Exception {
        provider.stub("sub-new", "new@example.com", true, "New", null);
        ResponseEntity<Void> authorize = controller.authorize("google", "login", "/", request());

        assertEquals(HttpStatus.FOUND, authorize.getStatusCode());
        String location = authorize.getHeaders().getLocation().toString();
        assertTrue(location.startsWith("https://accounts.google.com/o/oauth2/v2/auth?state="), location);
        assertTrue(location.contains("redirect_uri="), "redirect_uri must travel with the state");
        String state = stateOf(authorize);

        ResponseEntity<Void> callback = controller.callback("google", "code-1", state, request());

        assertEquals(HttpStatus.FOUND, callback.getStatusCode());
        String setCookie = callback.getHeaders().getFirst("Set-Cookie");
        assertTrue(setCookie != null && setCookie.startsWith("npdev_jwt="),
                "a successful Google signup must hand back the session cookie");
        assertEquals("/", callback.getHeaders().getLocation().toString());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT username FROM identity_users WHERE username = 'new@example.com'")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    void signupCallbackSessionCookieIsSameSiteLaxNotStrict() throws Exception {
        // REG-233: this exact callback (redirectWithSession) used to set SameSite=Strict, which
        // browsers silently drop on the top-level GET navigation that lands here FROM
        // accounts.google.com -- a cross-site-initiated redirect even though the destination is
        // same-origin. That broke the account-LINK leg, which depends on JwtBearerAuthFilter
        // reading this same npdev_jwt cookie back on the very next such redirect. Lax still keeps
        // the cookie off cross-site subrequests/form posts (CSRF protection unchanged).
        provider.stub("sub-lax", "lax@example.com", true, "Lax", null);
        ResponseEntity<Void> authorize = controller.authorize("google", "login", "/", request());
        String state = stateOf(authorize);

        ResponseEntity<Void> callback = controller.callback("google", "code-lax", state, request());

        String setCookie = callback.getHeaders().getFirst("Set-Cookie");
        assertTrue(setCookie != null && setCookie.contains("SameSite=Lax"),
                "OAuth callback session cookie must be SameSite=Lax (REG-233): " + setCookie);
        assertTrue(setCookie != null && !setCookie.contains("SameSite=Strict"),
                "must not regress to Strict, which breaks the account-link callback: " + setCookie);
    }

    @Test
    void unknownStateIsRefusedAndNeverMintsASession() {
        ResponseEntity<Void> callback = controller.callback("google", "code-2", "bogus-state", request());

        assertEquals("/?error=oauth_state_invalid", callback.getHeaders().getLocation().toString());
        assertNull(callback.getHeaders().getFirst("Set-Cookie"));
    }

    @Test
    void stateIsSingleUse() throws Exception {
        provider.stub("sub-once", "once@example.com", true, "Once", null);
        ResponseEntity<Void> authorize = controller.authorize("google", "login", "/", request());
        String state = stateOf(authorize);

        ResponseEntity<Void> first = controller.callback("google", "code-3", state, request());
        ResponseEntity<Void> replay = controller.callback("google", "code-4", state, request());

        assertEquals(HttpStatus.FOUND, first.getStatusCode());
        assertTrue(first.getHeaders().getFirst("Set-Cookie").startsWith("npdev_jwt="));
        assertEquals("/?error=oauth_state_invalid", replay.getHeaders().getLocation().toString(),
                "a consumed state must never mint a second session");
        assertNull(replay.getHeaders().getFirst("Set-Cookie"));
    }

    @Test
    void linkLegWithoutASessionCookieIsRefused() throws Exception {
        provider.stub("sub-out", "out@example.com", true, "Out", null);
        ResponseEntity<Void> authorize = controller.authorize("google", "link", "/profile", request());
        String state = stateOf(authorize);

        ResponseEntity<Void> callback = controller.callback("google", "code-5", state, request());

        assertEquals("/profile?error=oauth_link_requires_session",
                callback.getHeaders().getLocation().toString());
    }

    @Test
    void linkLegWithSessionLinksGoogleToTheAccount() throws Exception {
        insertUser("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "owner@example.com", "owner@example.com");
        provider.stub("sub-owner", "owner@example.com", true, "Owner", null);
        ResponseEntity<Void> authorize = controller.authorize("google", "link", "/profile", request());
        String state = stateOf(authorize);

        MockHttpServletRequest callbackRequest = request();
        callbackRequest.setAttribute("npdev.auth.claims",
                Map.of("actor_id", "owner@example.com", "tenant_id", TENANT, "roles", java.util.Set.of("USER")));
        ResponseEntity<Void> callback = controller.callback("google", "code-6", state, callbackRequest);

        assertEquals("/profile", callback.getHeaders().getLocation().toString(),
                "a clean link redirects to the caller with no error parameter");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT provider_subject FROM identity_external_identity WHERE "
                    + "user_id = UUID 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'")) {
                assertTrue(rs.next());
                assertEquals("sub-owner", rs.getString("provider_subject"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void configReportsEnoughForTheLoginScreen() {
        ResponseEntity<Map<String, Object>> config = controller.config();

        assertEquals(HttpStatus.OK, config.getStatusCode());
        List<Map<String, Object>> providers = (List<Map<String, Object>>) config.getBody().get("providers");
        assertEquals(1, providers.size());
        assertEquals("google", providers.get(0).get("id"));
        assertEquals("Google", providers.get(0).get("label"));
        assertEquals("/api/auth/oauth/google/authorize", providers.get(0).get("authorizePath"));
    }

    @Test
    void nextParamIsOpenRedirectSanitized() {
        assertEquals("/", OAuthGoogleController.sanitizeNext("http://evil.example"));
        assertEquals("/", OAuthGoogleController.sanitizeNext("//evil.example"));
        assertEquals("/", OAuthGoogleController.sanitizeNext("\\evil\\path"));
        assertEquals("/", OAuthGoogleController.sanitizeNext("javascript:alert(1)"));
        assertEquals("/profile", OAuthGoogleController.sanitizeNext("/profile"));
        assertEquals("/deep/path?x=1", OAuthGoogleController.sanitizeNext("/deep/path?x=1"));
    }

    // The open-redirect guard must be total: even the redirected Location header can never point
    // off-origin, so exercise it through the authorize path with a hostile next value.
    @Test
    void authorizedNextSurvivesSanitizationIntoTheSavedState() throws Exception {
        provider.stub("sub-safe", "safe@example.com", true, "Safe", null);
        ResponseEntity<Void> authorize = controller.authorize("google", "login", "https://evil.example", request());
        String state = stateOf(authorize);

        ResponseEntity<Void> callback = controller.callback("google", "code-7", state, request());

        assertEquals("/", callback.getHeaders().getLocation().toString(),
                "a hostile next must fall back to the app root, never to the attacker host");
    }

    private static String stateOf(ResponseEntity<Void> authorize) {
        String location = authorize.getHeaders().getLocation().toString();
        int start = location.indexOf("state=") + "state=".length();
        int end = location.indexOf('&', start);
        return end < 0 ? location.substring(start) : location.substring(start, end);
    }

    private void insertUser(String id, String username, String email) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO identity_users (id, tenant_id, username, display_name, email, active, "
                    + "token_version) VALUES (UUID '" + id + "', '" + TENANT + "', '" + username + "', '"
                    + username + "', '" + email + "', TRUE, 0)");
        }
    }

    private static String readTestPrivatePem() throws Exception {
        try (var inputStream = new org.springframework.core.io.DefaultResourceLoader()
                .getResource("classpath:npdev/security/test-jwt-private.pem").getInputStream()) {
            return new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        concepts.put("identity::ExternalIdentity",
                new CompiledConcept("ExternalIdentity", "ExternalIdentity", "identity_external_identity", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    /** Same H2 DataSource shape the app-tree tests use (CredentialRegistryServiceTest et al). */
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
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class FakeProvider implements IdentityProvider {
        private Optional<IdentityProviderClaims> claims = Optional.empty();

        void stub(String subject, String email, boolean emailVerified, String displayName, String avatarUrl) {
            claims = Optional.of(new IdentityProviderClaims(subject, email, emailVerified, displayName, avatarUrl));
        }

        @Override
        public String providerId() {
            return "google";
        }

        @Override
        public String authorizationUrl(String state, String redirectUri) {
            return "https://accounts.google.com/o/oauth2/v2/auth?state=" + state + "&redirect_uri=" + redirectUri;
        }

        @Override
        public Optional<IdentityProviderClaims> resolveIdentity(String code, String redirectUri) {
            return claims;
        }
    }
}