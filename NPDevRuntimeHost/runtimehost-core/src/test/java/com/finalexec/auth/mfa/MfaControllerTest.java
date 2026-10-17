package com.finalexec.auth.mfa;

import com.finalexec.auth.PasswordHasher;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SEC-11: the enrollment lifecycle over H2, with the claims-attribute harness of CurrentUserControllerTest. */
class MfaControllerTest {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        MfaSecretCodec.configureKey(null);
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "username VARCHAR(120), display_name VARCHAR(200), email VARCHAR(200), "
                    + "active BOOLEAN, token_version INT)");
            s.execute("CREATE TABLE identity_user_mfa (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "user_id UUID, secret VARCHAR(200), enabled_at TIMESTAMP, last_used_step INT, "
                    + "recovery_codes VARCHAR(2000))");
            s.execute("CREATE TABLE usuarios (user_id UUID, tenant_id VARCHAR(120), senha_hash VARCHAR(400))");
            s.execute("INSERT INTO identity_users (id, tenant_id, username, display_name, active) VALUES ('"
                    + USER_ID + "', 'dev', 'ada', 'Ada', TRUE)");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO usuarios VALUES (?, 'dev', ?)")) {
            ps.setObject(1, UUID.fromString(USER_ID));
            ps.setString(2, PasswordHasher.hash("correct horse battery"));
            ps.executeUpdate();
        }
    }

    private MfaController controller(CompiledModel model) {
        return new MfaController(dataSource, new FakeResolver(), new ModelHolder(model),
                "usuarios", "user_id", "senha_hash", "Test App");
    }

    private static MockHttpServletRequest asAda() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CLAIMS_ATTRIBUTE, Map.of("sub", "ada", "tenant_id", "dev"));
        return request;
    }

    @Test
    void fullLifecycle() throws Exception {
        MfaController controller = controller(model(true));

        ResponseEntity<Map<String, Object>> status = controller.status(asAda());
        assertEquals(200, status.getStatusCode().value());
        assertEquals(false, status.getBody().get("enabled"));

        assertEquals(400, controller.enrollStart(new MfaController.PasswordRequest("wrong"), asAda())
                .getStatusCode().value());

        ResponseEntity<Map<String, Object>> start =
                controller.enrollStart(new MfaController.PasswordRequest("correct horse battery"), asAda());
        assertEquals(200, start.getStatusCode().value());
        String secret = (String) start.getBody().get("secret");
        assertNotNull(secret);
        String uri = (String) start.getBody().get("otpauthUri");
        assertTrue(uri.startsWith("otpauth://totp/Test%20App:ada?secret=" + secret));

        // pending only: not enabled yet
        assertEquals(false, controller.status(asAda()).getBody().get("enabled"));
        // a wrong code does not enable
        assertEquals(400, controller.enrollConfirm(new MfaController.CodeRequest("000000"), asAda())
                .getStatusCode().value());

        String code = Totp.codeAt(Totp.base32Decode(secret), Totp.stepAt(Instant.now()));
        ResponseEntity<Map<String, Object>> confirm =
                controller.enrollConfirm(new MfaController.CodeRequest(code), asAda());
        assertEquals(200, confirm.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<String> recovery = (List<String>) confirm.getBody().get("recoveryCodes");
        assertEquals(10, recovery.size());

        Map<String, Object> enabled = controller.status(asAda()).getBody();
        assertEquals(true, enabled.get("enabled"));
        assertEquals(10, enabled.get("recoveryCodesLeft"));
        assertFalse(enabled.containsKey("secret"));
        assertFalse(enabled.containsKey("recoveryCodes"));

        // already enabled: cannot start again
        assertEquals(409, controller.enrollStart(new MfaController.PasswordRequest("correct horse battery"),
                asAda()).getStatusCode().value());

        // the secret is stored encoded and recovery codes only as hashes
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT secret, recovery_codes FROM identity_user_mfa")) {
            assertTrue(rs.next());
            assertEquals("p1:" + secret, rs.getString(1));
            assertFalse(rs.getString(2).contains(recovery.get(0)));
            assertTrue(rs.getString(2).contains(Totp.recoveryCodeHash(recovery.get(0))));
        }

        // recovery codes can be regenerated with a TOTP, but only a NEW step (the enrollment used this one)
        assertEquals(400, controller.regenerateRecoveryCodes(new MfaController.CodeRequest(code), asAda())
                .getStatusCode().value());

        // disable needs the password AND a code; a recovery code is accepted once
        assertEquals(400, controller.disable(new MfaController.DisableRequest("wrong", recovery.get(0)), asAda())
                .getStatusCode().value());
        assertEquals(400, controller.disable(new MfaController.DisableRequest("correct horse battery", "nope"),
                asAda()).getStatusCode().value());
        ResponseEntity<Map<String, Object>> off = controller.disable(
                new MfaController.DisableRequest("correct horse battery", recovery.get(0)), asAda());
        assertEquals(200, off.getStatusCode().value());
        assertEquals(false, off.getBody().get("enabled"));
        assertEquals(0, rowCount());
        assertEquals(false, controller.status(asAda()).getBody().get("enabled"));
    }

    @Test
    void regenerateReplacesRecoveryCodesWithATotp() throws Exception {
        MfaController controller = controller(model(true));
        // seed an enabled row whose last used step is far in the past
        byte[] key = Totp.base32Decode(Totp.newSecretBase32());
        String secret = Totp.base32Encode(key);
        try (Connection c = dataSource.getConnection()) {
            MfaStore.upsertPending(c, "identity_user_mfa", UUID.fromString(USER_ID), "dev", MfaSecretCodec.encode(secret));
            MfaStore.enable(c, "identity_user_mfa", UUID.fromString(USER_ID), "dev",
                    Totp.stepAt(Instant.now()) - 10, List.of(Totp.recoveryCodeHash("aaaa-bbbb")));
        }
        String code = Totp.codeAt(key, Totp.stepAt(Instant.now()));
        ResponseEntity<Map<String, Object>> fresh =
                controller.regenerateRecoveryCodes(new MfaController.CodeRequest(code), asAda());
        assertEquals(200, fresh.getStatusCode().value());
        assertEquals(10, ((List<?>) fresh.getBody().get("recoveryCodes")).size());
        assertEquals(10, controller.status(asAda()).getBody().get("recoveryCodesLeft"));
        // the same TOTP step cannot be used twice
        assertEquals(400, controller.regenerateRecoveryCodes(new MfaController.CodeRequest(code), asAda())
                .getStatusCode().value());
        // recovery codes cannot mint recovery codes
        assertEquals(400, controller.regenerateRecoveryCodes(
                new MfaController.CodeRequest("aaaa-bbbb"), asAda()).getStatusCode().value());
    }

    @Test
    void unauthenticatedIs401() {
        MfaController controller = controller(model(true));
        assertEquals(401, controller.status(new MockHttpServletRequest()).getStatusCode().value());
        assertEquals(401, controller.enrollStart(new MfaController.PasswordRequest("x"),
                new MockHttpServletRequest()).getStatusCode().value());
    }

    @Test
    void modelWithoutUserMfaAnswers503NotAvailable() {
        MfaController controller = controller(model(false));
        ResponseEntity<Map<String, Object>> status = controller.status(asAda());
        assertEquals(503, status.getStatusCode().value());
        assertEquals("mfa_not_available", status.getBody().get("error"));
        assertEquals("mfa_not_available", controller.enrollStart(
                new MfaController.PasswordRequest("correct horse battery"), asAda()).getBody().get("error"));
        assertEquals("mfa_not_available", controller.enrollConfirm(
                new MfaController.CodeRequest("123456"), asAda()).getBody().get("error"));
    }

    @Test
    void repeatedWrongPasswordsLockTheEndpoint() {
        MfaController controller = controller(model(true));
        for (int i = 0; i < 10; i++) {
            assertEquals(400, controller.enrollStart(new MfaController.PasswordRequest("bad" + i), asAda())
                    .getStatusCode().value());
        }
        assertEquals(429, controller.enrollStart(new MfaController.PasswordRequest("correct horse battery"),
                asAda()).getStatusCode().value());
    }

    private int rowCount() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM identity_user_mfa")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static CompiledModel model(boolean withMfa) {
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

    private static final class FakeResolver implements AuthenticatedContextResolver {
        @Override
        public ExecutionContext resolveFromPrincipal(Map<String, Object> claims, Map<String, String> headers) {
            String tenantId = claims.get("tenant_id") == null ? null : String.valueOf(claims.get("tenant_id"));
            String actorId = claims.get("sub") == null ? null : String.valueOf(claims.get("sub"));
            return ExecutionContext.of(tenantId, actorId);
        }
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
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
