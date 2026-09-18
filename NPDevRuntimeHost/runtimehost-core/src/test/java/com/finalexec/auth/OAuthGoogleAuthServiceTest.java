package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.IdentityProvider;
import com.npdev.runtime.support.IdentityRoleLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11: the account-linking semantics of the OAuth flows (NPDEV_MEGA_ROADMAP.md Session 3b),
 * hermetic on H2 exactly like {@code LoginControllerTest}'s pattern -- a real JDBC schema, a real
 * signing key from the committed test PEM, and a fake {@link IdentityProvider} (no network).
 * Covers the three flows and the load-bearing security properties: the email-collision block, the
 * email-ownership check on linking, the single-owner invariant of a provider subject, and the
 * token_version revocation mapping (a bumped version invalidates the minted session).
 */
class OAuthGoogleAuthServiceTest {

    private static final String TENANT = "dev";
    private static final String PROVIDER = "google";

    private DataSource dataSource;
    private JwtSigner signer;
    private FakeProvider provider;

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
        signer = new JwtSigner(new ObjectMapper(),
                JwtSigner.loadPrivateKey(readTestPrivatePem()),
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
    }

    private OAuthGoogleAuthService service() {
        return new OAuthGoogleAuthService(dataSource, new ModelHolder(identityModel()), provider, signer);
    }

    private void insertUser(String id, String username, String email, boolean active, int tokenVersion) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO identity_users (id, tenant_id, username, display_name, email, active, "
                    + "token_version) VALUES (UUID '" + id + "', '" + TENANT + "', '" + username + "', '"
                    + username + "', " + (email == null ? "NULL" : "'" + email + "'") + ", " + active + ", "
                    + tokenVersion + ")");
        }
    }

    private void link(String userId, String subject) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO identity_external_identity (id, user_id, provider, provider_subject, "
                    + "linked_at) VALUES (UUID '" + UUID.randomUUID() + "', UUID '" + userId + "', '" + PROVIDER
                    + "', '" + subject + "', CURRENT_TIMESTAMP)");
        }
    }

    // ------------------------------------------------------------------ login/signup legs

    @Test
    void linkedUserLogsInWithTokenVersionFromUserRow() throws Exception {
        insertUser("22222222-2222-2222-2222-222222222222", "ada@example.com", "ada@example.com", true, 3);
        link("22222222-2222-2222-2222-222222222222", "sub-ada");
        provider.stub("sub-ada", "ada@example.com", true, "Ada Lovelace", null);

        var ticket = service().resolveAndSignIn("code-1", "http://localhost/api/auth/oauth/google/callback", TENANT);

        assertEquals(OAuthGoogleAuthService.Outcome.LOGIN, ticket.outcome());
        assertNotNull(ticket.token());
        assertEquals("ada@example.com", ticket.username());
        // The minted session must carry the user row's token_version (3) in its tv claim -- the
        // exact value JwtBearerAuthFilter checks per request via IdentityRoleLookup.tokenVersion().
        assertEquals(3, tvClaim(ticket.token()));
        assertEquals(3, IdentityRoleLookup.tokenVersion(dataSource,
                com.npdev.dsl.v1.compiled.IdentityPackTableNames.tryResolve(identityModel()).orElseThrow(),
                TENANT, "ada@example.com"));
    }

    @Test
    void freshEmailSignsUpAndPersistsUserPlusLinkage() throws Exception {
        provider.stub("sub-new", "new@example.com", true, "New Person", "http://avatar/new.png");

        var ticket = service().resolveAndSignIn("code-2", "http://localhost/api/auth/oauth/google/callback", TENANT);

        assertEquals(OAuthGoogleAuthService.Outcome.SIGNUP, ticket.outcome());
        assertNotNull(ticket.token());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT username, active, avatar_url FROM identity_users WHERE username = 'new@example.com'")) {
                assertTrue(rs.next());
                assertEquals("new@example.com", rs.getString("username"));
                assertTrue(rs.getBoolean("active"));
                assertEquals("http://avatar/new.png", rs.getString("avatar_url"));
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT provider, provider_subject, tenant_id FROM identity_external_identity WHERE user_id = "
                            + "(SELECT id FROM identity_users WHERE username = 'new@example.com')")) {
                assertTrue(rs.next());
                assertEquals(PROVIDER, rs.getString("provider"));
                assertEquals("sub-new", rs.getString("provider_subject"));
                // REG-214: the linkage row must live in the ACTOR's tenant (the signup tenant),
                // not fall back to the platform 'default' -- tenant-scoped uniqueness
                // (tenant_id, provider, provider_subject) is what lets the same Google account
                // sign up in two tenants without colliding.
                assertEquals(TENANT, rs.getString("tenant_id"));
            }
        }
    }

    @Test
    void unverifiedEmailIsRefusedAndNothingIsWritten() throws Exception {
        provider.stub("sub-spoof", "spoof@example.com", false, "Spoofer", null);

        var ticket = service().resolveAndSignIn("code-3", "http://localhost/api/auth/oauth/google/callback", TENANT);

        assertEquals(OAuthGoogleAuthService.Outcome.UNVERIFIED_OR_MISSING_EMAIL, ticket.outcome());
        assertNull(ticket.token());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM identity_users")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "an unverified email must never create an account");
            }
        }
    }

    @Test
    void emailCollisionBlocksWithExplicitLinkCode() throws Exception {
        // An existing username/password account owns this email; no google linkage exists.
        insertUser("33333333-3333-3333-3333-333333333333", "Existing User", "collide@example.com", true, 1);
        provider.stub("sub-attacker", "collide@example.com", true, "Attacker", null);

        var ticket = service().resolveAndSignIn("code-4", "http://localhost/api/auth/oauth/google/callback", TENANT);

        assertEquals(OAuthGoogleAuthService.Outcome.EMAIL_EXISTS_REQUIRES_LINK, ticket.outcome());
        assertEquals("email_exists_requires_link", ticket.errorCode());
        assertNull(ticket.token());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM identity_external_identity")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "the block must not create a linkage");
            }
        }
    }

    @Test
    void inactiveLinkedUserIsRefused() throws Exception {
        insertUser("44444444-4444-4444-4444-444444444444", "gone@example.com", "gone@example.com", false, 0);
        link("44444444-4444-4444-4444-444444444444", "sub-gone");
        provider.stub("sub-gone", "gone@example.com", true, "Gone", null);

        var ticket = service().resolveAndSignIn("code-5", "http://localhost/api/auth/oauth/google/callback", TENANT);

        assertEquals(OAuthGoogleAuthService.Outcome.INACTIVE_USER, ticket.outcome());
        assertNull(ticket.token());
    }

    @Test
    void providerFailureSurfacesAsProviderRefused() {
        provider.stubEmpty();

        var ticket = service().resolveAndSignIn("bad-code", "http://localhost/api/auth/oauth/google/callback", TENANT);

        assertEquals(OAuthGoogleAuthService.Outcome.PROVIDER_REFUSED, ticket.outcome());
    }

    // ------------------------------------------------------------------ link leg

    @Test
    void linkRecordsLinkageWhenGoogleEmailMatchesTheAccount() throws Exception {
        insertUser("55555555-5555-5555-5555-555555555555", "owner@example.com", "owner@example.com", true, 2);
        provider.stub("sub-owner", "owner@example.com", true, "Owner", null);

        var ticket = service().linkToAuthenticatedUser("code-6", "http://localhost/api/auth/oauth/google/callback",
                TENANT, "owner@example.com");

        assertEquals(OAuthGoogleAuthService.Outcome.LINKED, ticket.outcome());
        assertNull(ticket.errorCode());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT provider_subject, tenant_id FROM identity_external_identity WHERE "
                    + "user_id = UUID '55555555-5555-5555-5555-555555555555'")) {
                assertTrue(rs.next());
                assertEquals("sub-owner", rs.getString("provider_subject"));
                // REG-214: the link leg also records the linkage in the ACTOR's tenant.
                assertEquals(TENANT, rs.getString("tenant_id"));
            }
        }
    }

    @Test
    void linkRefusesWhenGoogleEmailDoesNotBelongToTheAccount() throws Exception {
        insertUser("66666666-6666-6666-6666-666666666666", "victim@example.com", "victim@example.com", true, 0);
        provider.stub("sub-other", "other@example.com", true, "Other", null);

        var ticket = service().linkToAuthenticatedUser("code-7", "http://localhost/api/auth/oauth/google/callback",
                TENANT, "victim@example.com");

        assertEquals(OAuthGoogleAuthService.Outcome.LINK_EMAIL_MISMATCH, ticket.outcome());
        assertEquals("oauth_link_email_mismatch", ticket.errorCode());
        assertNoLinkForSubject("sub-other");
    }

    @Test
    void linkRefusesSubjectAlreadyClaimedByAnotherAccount() throws Exception {
        insertUser("77777777-7777-7777-7777-777777777777", "first@example.com", "first@example.com", true, 0);
        insertUser("88888888-8888-8888-8888-888888888888", "second@example.com", "second@example.com", true, 0);
        link("77777777-7777-7777-7777-777777777777", "sub-claimed");
        // second@example.com is signed in and tries to link a Google account already owned by first.
        provider.stub("sub-claimed", "second@example.com", true, "Second", null);

        var ticket = service().linkToAuthenticatedUser("code-8", "http://localhost/api/auth/oauth/google/callback",
                TENANT, "second@example.com");

        assertEquals(OAuthGoogleAuthService.Outcome.LINK_ALREADY_USED, ticket.outcome());
        assertNoLinkForUser("88888888-8888-8888-8888-888888888888");
    }

    @Test
    void relinkingTheSameSubjectToTheSameUserIsIdempotent() throws Exception {
        insertUser("99999999-9999-9999-9999-999999999999", "same@example.com", "same@example.com", true, 0);
        link("99999999-9999-9999-9999-999999999999", "sub-same");
        provider.stub("sub-same", "same@example.com", true, "Same", null);

        var ticket = service().linkToAuthenticatedUser("code-9", "http://localhost/api/auth/oauth/google/callback",
                TENANT, "same@example.com");

        assertEquals(OAuthGoogleAuthService.Outcome.LINKED, ticket.outcome());
    }

    // ------------------------------------------------------------------ revocation mapping

    @Test
    void tokenVersionBumpInvalidatesTheMintedGoogleSession() throws Exception {
        insertUser("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "revoked@example.com", "revoked@example.com", true, 0);
        link("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "sub-revoked");
        provider.stub("sub-revoked", "revoked@example.com", true, "Revoked", null);

        var first = service().resolveAndSignIn("code-10", "http://localhost/api/auth/oauth/google/callback", TENANT);
        assertNotNull(first.token());

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE identity_users SET token_version = token_version + 1 WHERE username = 'revoked@example.com'");
        }
        var second = service().resolveAndSignIn("code-11", "http://localhost/api/auth/oauth/google/callback", TENANT);
        assertNotNull(second.token());

        // The JWT carries the user's stored token_version at mint time ("tv" claim). The bump must
        // be visible between the two mintings -- which is exactly the delta
        // JwtBearerAuthFilter/IdentityRoleLookup.tokenVersion() enforce per request (REG-23:
        // tv-less tokens bypass, but every token minted here carries tv).
        assertEquals(0, tvClaim(first.token()));
        assertEquals(1, tvClaim(second.token()), "the tv claim must change when token_version changes");
        assertEquals(1, IdentityRoleLookup.tokenVersion(dataSource,
                com.npdev.dsl.v1.compiled.IdentityPackTableNames.tryResolve(identityModel()).orElseThrow(),
                TENANT, "revoked@example.com"),
                "the live stored version must reflect the bump");
    }

    private static int tvClaim(String token) throws Exception {
        String payload = token.split("\\.")[1];
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(payload);
        return new ObjectMapper().readTree(decoded).path("tv").asInt();
    }

    private void assertNoLinkForSubject(String subject) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM identity_external_identity WHERE "
                    + "provider_subject = '" + subject + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    private void assertNoLinkForUser(String userId) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM identity_external_identity WHERE "
                    + "user_id = UUID '" + userId + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    private static String readTestPrivatePem() throws Exception {
        try (var inputStream = new DefaultResourceLoader().getResource("classpath:npdev/security/test-jwt-private.pem")
                .getInputStream()) {
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

        @Override public java.sql.Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public java.sql.Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** Test double for {@link IdentityProvider}: no network, claims stubbed per test. */
    private static final class FakeProvider implements IdentityProvider {
        private Optional<IdentityProviderClaims> claims = Optional.empty();

        void stub(String subject, String email, boolean emailVerified, String displayName, String avatarUrl) {
            claims = Optional.of(new IdentityProviderClaims(subject, email, emailVerified, displayName, avatarUrl));
        }

        void stubEmpty() {
            claims = Optional.empty();
        }

        @Override
        public String providerId() {
            return PROVIDER;
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