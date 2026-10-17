package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.mfa.MfaChallengeStore;
import com.finalexec.auth.mfa.MfaSecretCodec;
import com.finalexec.auth.mfa.MfaStore;
import com.finalexec.auth.mfa.Totp;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-39 layer 2: hermetic H2-backed proof that a schema mismatch in the identity pack (the
 * historical WmsOffice failure mode -- a stale pack copy missing {@code token_version}) surfaces as a
 * distinct {@code identity_pack_schema_error}, not the generic {@code invalid_credentials} it used to
 * collapse into. Mirrors {@code PasswordResetControllerTest}'s hermetic pattern (no Spring context).
 */
class LoginControllerTest {

    private static final String TENANT = "tenantx";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    private LoginController controller() throws Exception {
        return new LoginController(
                dataSource, new ObjectMapper(), new DefaultResourceLoader(), identityModel(),
                "usuarios", "user_id", "senha_hash",
                "classpath:npdev/security/test-jwt-private.pem",
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L
        );
    }

    // REG-177: LoginController now resolves table names from a CompiledModel rather than a
    // hardcoded literal -- this fabricates one matching this test's own H2 schema (see the
    // NpdevObservabilityConfigSensitiveFieldTest precedent for the same hand-built-CompiledModel
    // pattern). Role/UserRole/UserRolePermission tables never physically exist in this test's H2
    // schema, but that's fine: IdentityRoleLookup.rolesFor fails open (empty roles) on a real
    // "table not found" SQLException, exactly like before this fix existed.
    private static CompiledModel identityModel() {
        return identityModel(true);
    }

    private static CompiledModel identityModel(boolean withMfa) {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        if (withMfa) {
            concepts.put("identity::UserMfa", new CompiledConcept("UserMfa", "UserMfa", "identity_user_mfa", List.of()));
        }
        return new CompiledModel("test", "1.0.0", concepts);
    }

    /** Current (fresh) shape of the identity pack -- includes token_version, as the platform's pack has since LNCH-4. */
    private void createFreshSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "active BOOLEAN, tenant_id VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_mfa (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "user_id UUID, secret VARCHAR(200), enabled_at TIMESTAMP, last_used_step INT, "
                    + "recovery_codes VARCHAR(2000))");
            s.execute("INSERT INTO identity_users VALUES ('" + USER_ID + "','ada',TRUE,'" + TENANT + "',0)");
            s.execute("INSERT INTO usuarios VALUES (RANDOM_UUID(), '" + USER_ID + "', '"
                    + PasswordHasher.hash("correct-horse") + "', '" + TENANT + "')");
        }
    }

    /** REG-39: a STALE pack copy predating LNCH-4 -- identity_users has no token_version column at all. */
    private void createStaleSchemaMissingTokenVersion() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_mfa (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "user_id UUID, secret VARCHAR(200), enabled_at TIMESTAMP, last_used_step INT, "
                    + "recovery_codes VARCHAR(2000))");
            s.execute("INSERT INTO identity_users VALUES ('" + USER_ID + "','ada',TRUE,'" + TENANT + "')");
            s.execute("INSERT INTO usuarios VALUES (RANDOM_UUID(), '" + USER_ID + "', '"
                    + PasswordHasher.hash("correct-horse") + "', '" + TENANT + "')");
        }
    }

    @Test
    void staleIdentityPackCopyProducesSchemaErrorNotInvalidCredentials() throws Exception {
        createStaleSchemaMissingTokenVersion();
        LoginController controller = controller();

        var response = controller.login(
                new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);

        assertEquals(500, response.getStatusCode().value(),
                "a schema mismatch must NOT be reported as a 401 -- that's the REG-39 bug");
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("identity_pack_schema_error", body.get("error"));
        String detail = String.valueOf(body.get("detail"));
        assertTrue(detail.toLowerCase().contains("token_version"),
                "the error must name the missing column, not read as a generic failure: " + detail);
    }

    @Test
    void freshSchemaLogsInSuccessfully() throws Exception {
        createFreshSchema();
        LoginController controller = controller();

        var response = controller.login(
                new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("token"));
    }

    @Test
    void successfulLoginSessionCookieIsSameSiteLaxNotStrict() throws Exception {
        // REG-233: SameSite=Strict silently dropped this cookie on the top-level GET navigation
        // that carries a browser back from Google's OAuth consent screen to our own callback
        // (OAuthGoogleController.handleLinkCallback), which JwtBearerAuthFilter must read (as a
        // Bearer-header fallback) to authenticate the account-LINK leg. Lax still blocks the
        // cookie from riding along on cross-site subrequests/form posts, so CSRF exposure is
        // unchanged; it only additionally permits the cross-site-initiated top-level redirect.
        createFreshSchema();
        LoginController controller = controller();

        var response = controller.login(
                new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);

        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertNotNull(setCookie, "a successful login must set the session cookie");
        assertTrue(setCookie.contains("SameSite=Lax"),
                "session cookie must be SameSite=Lax so it survives the cross-site-initiated "
                        + "top-level OAuth-callback redirect (REG-233): " + setCookie);
        assertTrue(!setCookie.contains("SameSite=Strict"),
                "must not regress to Strict, which breaks the OAuth account-link callback: " + setCookie);
    }

    @Test
    void wrongPasswordAgainstFreshSchemaStaysInvalidCredentials() throws Exception {
        createFreshSchema();
        LoginController controller = controller();

        var response = controller.login(
                new LoginController.LoginRequest("ada", "wrong-password", TENANT), null);

        assertEquals(401, response.getStatusCode().value());
        assertEquals("invalid_credentials", response.getBody().get("error"));
    }

    // ---- SEC-13: second factor at sign-in ------------------------------------------------------

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private void enrollMfa(boolean confirmed, List<String> recoveryCodes) throws Exception {
        MfaSecretCodec.configureKey(null);
        try (Connection c = dataSource.getConnection()) {
            MfaStore.upsertPending(c, "identity_user_mfa", USER_ID, TENANT, MfaSecretCodec.encode(SECRET));
            if (confirmed) {
                MfaStore.enable(c, "identity_user_mfa", USER_ID, TENANT, 1L,
                        recoveryCodes.stream().map(Totp::recoveryCodeHash).toList());
            }
        }
    }

    private static String currentCode() {
        return Totp.codeAt(Totp.base32Decode(SECRET), Totp.stepAt(Instant.now()));
    }

    private String passwordStepChallenge(LoginController controller) {
        var response = controller.login(new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("mfaRequired"));
        assertTrue(response.getBody().get("token") == null, "no token before the second factor");
        assertTrue(response.getHeaders().getFirst("Set-Cookie") == null, "no cookie before the second factor");
        return (String) response.getBody().get("challenge");
    }

    @Test
    void userWithoutMfaStillGetsATokenDirectly() throws Exception {
        createFreshSchema();
        var response = controller().login(new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("token"));
        assertTrue(response.getBody().get("mfaRequired") == null);
    }

    @Test
    void pendingEnrollmentDoesNotPromptForMfa() throws Exception {
        createFreshSchema();
        enrollMfa(false, List.of());
        var response = controller().login(new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);
        assertNotNull(response.getBody().get("token"));
        assertTrue(response.getBody().get("mfaRequired") == null);
    }

    @Test
    void modelWithoutUserMfaBehavesAsBefore() throws Exception {
        createFreshSchema();
        LoginController legacy = new LoginController(
                dataSource, new ObjectMapper(), new DefaultResourceLoader(), identityModel(false),
                "usuarios", "user_id", "senha_hash", "classpath:npdev/security/test-jwt-private.pem",
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
        var response = legacy.login(new LoginController.LoginRequest("ada", "correct-horse", TENANT), null);
        assertNotNull(response.getBody().get("token"));
    }

    @Test
    void enabledMfaReturnsAChallengeThenTheCodeSignsIn() throws Exception {
        createFreshSchema();
        enrollMfa(true, List.of("aaaa-bbbb"));
        LoginController controller = controller();
        String challenge = passwordStepChallenge(controller);

        var wrong = controller.loginMfa(new LoginController.MfaLoginRequest(challenge, "000000", null), null);
        assertEquals(401, wrong.getStatusCode().value());
        assertEquals("invalid_mfa_code", wrong.getBody().get("error"));

        var ok = controller.loginMfa(new LoginController.MfaLoginRequest(challenge, currentCode(), null), null);
        assertEquals(200, ok.getStatusCode().value());
        assertNotNull(ok.getBody().get("token"));
        assertEquals("/", ok.getBody().get("next"));
        assertNotNull(ok.getHeaders().getFirst("Set-Cookie"));

        // the challenge is single use
        var again = controller.loginMfa(new LoginController.MfaLoginRequest(challenge, currentCode(), null), null);
        assertEquals("mfa_challenge_expired", again.getBody().get("error"));
    }

    @Test
    void theSameTotpStepCannotBeReplayedWithANewChallenge() throws Exception {
        createFreshSchema();
        enrollMfa(true, List.of("aaaa-bbbb"));
        LoginController controller = controller();
        String code = currentCode();
        assertEquals(200, controller.loginMfa(
                new LoginController.MfaLoginRequest(passwordStepChallenge(controller), code, null), null)
                .getStatusCode().value());
        var replay = controller.loginMfa(
                new LoginController.MfaLoginRequest(passwordStepChallenge(controller), code, null), null);
        assertEquals(401, replay.getStatusCode().value());
        assertEquals("invalid_mfa_code", replay.getBody().get("error"));
    }

    @Test
    void recoveryCodeWorksExactlyOnce() throws Exception {
        createFreshSchema();
        enrollMfa(true, List.of("k7m2-q9xd", "aaaa-bbbb"));
        LoginController controller = controller();
        var first = controller.loginMfa(new LoginController.MfaLoginRequest(
                passwordStepChallenge(controller), null, "K7M2-Q9XD"), null);
        assertEquals(200, first.getStatusCode().value());
        var second = controller.loginMfa(new LoginController.MfaLoginRequest(
                passwordStepChallenge(controller), null, "k7m2q9xd"), null);
        assertEquals(401, second.getStatusCode().value());
        // the other code is still good
        assertEquals(200, controller.loginMfa(new LoginController.MfaLoginRequest(
                passwordStepChallenge(controller), null, "aaaa-bbbb"), null).getStatusCode().value());
    }

    @Test
    void fiveWrongCodesKillTheChallenge() throws Exception {
        createFreshSchema();
        enrollMfa(true, List.of("aaaa-bbbb"));
        LoginController controller = controller();
        String challenge = passwordStepChallenge(controller);
        for (int i = 0; i < MfaChallengeStore.MAX_ATTEMPTS; i++) {
            assertEquals("invalid_mfa_code", controller.loginMfa(
                    new LoginController.MfaLoginRequest(challenge, "00000" + i, null), null).getBody().get("error"));
        }
        var dead = controller.loginMfa(new LoginController.MfaLoginRequest(challenge, currentCode(), null), null);
        assertEquals("mfa_challenge_expired", dead.getBody().get("error"));
    }

    @Test
    void anAccountDisabledBetweenTheStepsGetsNoSession() throws Exception {
        createFreshSchema();
        enrollMfa(true, List.of("aaaa-bbbb"));
        LoginController controller = controller();
        String challenge = passwordStepChallenge(controller);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE identity_users SET active = FALSE");
        }
        var response = controller.loginMfa(new LoginController.MfaLoginRequest(challenge, currentCode(), null), null);
        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getBody().get("token") == null);
    }

    @Test
    void unknownChallengeIs401() throws Exception {
        createFreshSchema();
        var response = controller().loginMfa(new LoginController.MfaLoginRequest("nope", "123456", null), null);
        assertEquals(401, response.getStatusCode().value());
        assertEquals("mfa_challenge_expired", response.getBody().get("error"));
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
