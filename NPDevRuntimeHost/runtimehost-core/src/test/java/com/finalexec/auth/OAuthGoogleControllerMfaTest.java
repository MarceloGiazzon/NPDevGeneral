package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.mfa.MfaChallengeStore;
import com.finalexec.auth.mfa.MfaSecretCodec;
import com.finalexec.auth.mfa.MfaStore;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-13: a provider (Google/GitHub/OIDC) sign-in of a user with two-step sign-in on must NOT mint a
 * session: it redirects to /login.html?mfa=&lt;challenge&gt; with no Set-Cookie. A user without it, or with
 * only a PENDING enrollment, signs in as before.
 */
class OAuthGoogleControllerMfaTest {

    private static final String TENANT = "dev";
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DataSource dataSource;
    private OAuthGoogleController controller;
    private OAuthStateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new UrlDataSource("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "username VARCHAR(120) UNIQUE, display_name VARCHAR(200), email VARCHAR(200), "
                    + "active BOOLEAN, token_version INT, avatar_url VARCHAR(2048), "
                    + "last_login_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
            s.execute("CREATE TABLE identity_external_identity (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "user_id UUID, provider VARCHAR(40), provider_subject VARCHAR(255), linked_at TIMESTAMP, "
                    + "CONSTRAINT ux_provider_subject UNIQUE (provider, provider_subject))");
            s.execute("CREATE TABLE identity_user_mfa (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "user_id UUID, secret VARCHAR(200), enabled_at TIMESTAMP, last_used_step INT, "
                    + "recovery_codes VARCHAR(2000))");
            s.execute("INSERT INTO identity_users (id, tenant_id, username, display_name, email, active, token_version) "
                    + "VALUES (UUID '" + USER_ID + "', '" + TENANT + "', 'ada@example.com', 'Ada', 'ada@example.com', TRUE, 0)");
            s.execute("INSERT INTO identity_external_identity (id, tenant_id, user_id, provider, provider_subject) "
                    + "VALUES (RANDOM_UUID(), '" + TENANT + "', UUID '" + USER_ID + "', 'google', 'sub-ada')");
        }
        MfaSecretCodec.configureKey(null);
        FakeProvider provider = new FakeProvider();
        JwtSigner signer = new JwtSigner(new ObjectMapper(), JwtSigner.loadPrivateKey(readTestPrivatePem()),
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
        ModelHolder holder = new ModelHolder(identityModel());
        OAuthGoogleAuthService service = new OAuthGoogleAuthService(dataSource, holder, provider, signer);
        stateStore = new OAuthStateStore(60_000L);
        controller = new OAuthGoogleController(stateStore, provider, service, 28800L, dataSource, holder);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        return request;
    }

    private ResponseEntity<Void> providerLogin(String next) {
        ResponseEntity<Void> authorize = controller.authorize("google", "login", next, request());
        String location = authorize.getHeaders().getLocation().toString();
        int start = location.indexOf("state=") + "state=".length();
        int end = location.indexOf('&', start);
        String state = end < 0 ? location.substring(start) : location.substring(start, end);
        return controller.callback("google", "code-1", state, request());
    }

    private void enrollMfa(boolean confirmed) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            MfaStore.upsertPending(c, "identity_user_mfa", USER_ID, TENANT, MfaSecretCodec.encode("GEZDGNBV"));
            if (confirmed) {
                MfaStore.enable(c, "identity_user_mfa", USER_ID, TENANT, 1L, List.of("hash"));
            }
        }
    }

    @Test
    void userWithoutMfaSignsInWithACookieAsBefore() {
        ResponseEntity<Void> callback = providerLogin("/orders");
        assertEquals(HttpStatus.FOUND, callback.getStatusCode());
        assertNotNull(callback.getHeaders().getFirst("Set-Cookie"));
        assertEquals("/orders", callback.getHeaders().getLocation().toString());
    }

    @Test
    void pendingEnrollmentDoesNotGateTheProviderSignIn() throws Exception {
        enrollMfa(false);
        assertNotNull(providerLogin("/").getHeaders().getFirst("Set-Cookie"));
    }

    @Test
    void enabledMfaRedirectsToTheSecondStepWithoutAnyCookie() throws Exception {
        enrollMfa(true);
        ResponseEntity<Void> callback = providerLogin("/orders");

        assertEquals(HttpStatus.FOUND, callback.getStatusCode());
        assertNull(callback.getHeaders().getFirst("Set-Cookie"), "no session before the second factor");
        String location = callback.getHeaders().getLocation().toString();
        assertTrue(location.startsWith("/login.html?mfa="), location);
        assertTrue(location.endsWith("&next=%2Forders"), location);
        String challengeId = location.substring("/login.html?mfa=".length(), location.indexOf('&'));
        MfaChallengeStore.Challenge challenge = MfaChallengeStore.shared().peek(challengeId).orElseThrow();
        assertEquals(TENANT, challenge.tenantId());
        assertEquals("ada@example.com", challenge.username());
        assertEquals("/orders", challenge.next());
    }

    @Test
    void unknownDatabaseStateFailsClosed() throws Exception {
        enrollMfa(true);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE identity_user_mfa");
        }
        ResponseEntity<Void> callback = providerLogin("/");
        assertNull(callback.getHeaders().getFirst("Set-Cookie"));
        assertEquals("/?error=oauth_login_failed", callback.getHeaders().getLocation().toString());
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
        concepts.put("identity::UserMfa", new CompiledConcept("UserMfa", "UserMfa", "identity_user_mfa", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
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
            return Optional.of(new IdentityProviderClaims("sub-ada", "ada@example.com", true, "Ada", null));
        }
    }
}
