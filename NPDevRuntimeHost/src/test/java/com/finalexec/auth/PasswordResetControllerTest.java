package com.finalexec.auth;

import com.npdev.adapters.mail.inproc.InProcMailCapabilityAdapter;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-4 P1: hermetic proof of the self-service password-reset flow -- H2-backed
 * identity_users/identity_password_reset_tokens/usuarios tables (mirroring
 * IdentityAwareContextResolverTest's pattern) plus a real InProcMailCapabilityAdapter wired through
 * the real CapabilityRegistry/RegistryCapabilityDispatcher production classes, no Spring context.
 */
class PasswordResetControllerTest {

    private static final String TENANT = "tenantx";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DataSource dataSource;
    private InProcMailCapabilityAdapter mailAdapter;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "email VARCHAR(200), active BOOLEAN, tenant_id VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE identity_password_reset_tokens (id UUID PRIMARY KEY, user_id UUID, "
                    + "token_hash VARCHAR(64), expires_at TIMESTAMP, used_at TIMESTAMP, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('" + USER_ID + "','ada','ada@example.com',TRUE,'"
                    + TENANT + "',0)");
            s.execute("INSERT INTO identity_users VALUES ('22222222-2222-2222-2222-222222222222','noemail',"
                    + "NULL,TRUE,'" + TENANT + "',0)");
            s.execute("INSERT INTO usuarios VALUES (RANDOM_UUID(), '" + USER_ID + "', '"
                    + PasswordHasher.hash("old-password") + "', '" + TENANT + "')");
        }
        mailAdapter = new InProcMailCapabilityAdapter();
    }

    private PasswordResetController controllerWithMailBound() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("mail", "EmailCapability", "mail-inproc", mailAdapter);
        return new PasswordResetController(
                dataSource, registry, new RegistryCapabilityDispatcher(registry),
                "usuarios", "user_id", "senha_hash", ""
        );
    }

    private PasswordResetController controllerWithoutMail() {
        CapabilityRegistry registry = new CapabilityRegistry();
        return new PasswordResetController(
                dataSource, registry, new RegistryCapabilityDispatcher(registry),
                "usuarios", "user_id", "senha_hash", ""
        );
    }

    @Test
    void throttlesRepeatedResetRequestsForOneUser() {
        // REG-21: after the per-user ceiling, further reset requests are silently no-op'd (no email,
        // no token) while still returning the same generic 200 -- an account cannot be email-bombed.
        PasswordResetController controller = controllerWithMailBound();

        for (int i = 0; i < 5; i++) {
            var resp = controller.requestReset(new PasswordResetController.RequestResetRequest("ada", TENANT), null);
            assertEquals(200, resp.getStatusCode().value());
        }
        assertEquals(5, mailAdapter.deliveries().size(), "first five requests each send one email");

        var sixth = controller.requestReset(new PasswordResetController.RequestResetRequest("ada", TENANT), null);
        assertEquals(200, sixth.getStatusCode().value(), "the throttled response is still the generic 200");
        assertEquals(5, mailAdapter.deliveries().size(), "the sixth request must NOT send another email");
    }

    @Test
    void requestSendsEmailAndConfirmSetsNewPasswordAndBumpsTokenVersion() throws Exception {
        PasswordResetController controller = controllerWithMailBound();

        var requestResp = controller.requestReset(new PasswordResetController.RequestResetRequest("ada", TENANT), null);
        assertEquals(200, requestResp.getStatusCode().value());
        assertEquals(1, mailAdapter.deliveries().size());
        Map<String, Object> delivery = mailAdapter.deliveries().get(0);
        assertEquals(List.of("ada@example.com"), delivery.get("to"));
        String body = (String) delivery.get("body");
        assertNotNull(body);
        String token = extractToken(body);

        var confirmResp = controller.confirmReset(
                new PasswordResetController.ConfirmResetRequest(token, "brand-new-password", TENANT));
        assertEquals(200, confirmResp.getStatusCode().value());

        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT senha_hash FROM usuarios WHERE user_id = ?")) {
                ps.setObject(1, USER_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(PasswordHasher.verify("brand-new-password", rs.getString(1)));
                    assertFalse(PasswordHasher.verify("old-password", rs.getString(1)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT token_version FROM identity_users WHERE id = ?")) {
                ps.setObject(1, USER_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void confirmRejectsAnAlreadyUsedToken() throws Exception {
        PasswordResetController controller = controllerWithMailBound();
        controller.requestReset(new PasswordResetController.RequestResetRequest("ada", TENANT), null);
        String token = extractToken((String) mailAdapter.deliveries().get(0).get("body"));

        var first = controller.confirmReset(new PasswordResetController.ConfirmResetRequest(token, "first-new-pass", TENANT));
        assertEquals(200, first.getStatusCode().value());

        var second = controller.confirmReset(new PasswordResetController.ConfirmResetRequest(token, "second-new-pass", TENANT));
        assertEquals(400, second.getStatusCode().value());
    }

    @Test
    void confirmRejectsAnExpiredToken() throws Exception {
        PasswordResetController controller = controllerWithMailBound();
        UUID tokenId = UUID.randomUUID();
        String rawToken = "expired-raw-token";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity_password_reset_tokens VALUES (?, ?, ?, ?, NULL, ?)")) {
            ps.setObject(1, tokenId);
            ps.setObject(2, USER_ID);
            ps.setString(3, sha256Hex(rawToken));
            ps.setTimestamp(4, Timestamp.from(Instant.now().minusSeconds(60)));
            ps.setString(5, TENANT);
            ps.executeUpdate();
        }

        var resp = controller.confirmReset(new PasswordResetController.ConfirmResetRequest(rawToken, "new-password-1", TENANT));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void confirmRejectsAnUnknownToken() {
        PasswordResetController controller = controllerWithMailBound();
        var resp = controller.confirmReset(
                new PasswordResetController.ConfirmResetRequest("not-a-real-token", "new-password-1", TENANT));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void requestForUnknownUserStaysGenericAndSendsNothing() {
        PasswordResetController controller = controllerWithMailBound();
        var resp = controller.requestReset(new PasswordResetController.RequestResetRequest("nobody", TENANT), null);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(mailAdapter.deliveries().isEmpty());
    }

    @Test
    void requestForUserWithNoEmailOnFileStaysGenericAndSendsNothing() {
        PasswordResetController controller = controllerWithMailBound();
        var resp = controller.requestReset(new PasswordResetController.RequestResetRequest("noemail", TENANT), null);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(mailAdapter.deliveries().isEmpty());
    }

    @Test
    void requestWithNoMailCapabilityBoundStaysGenericAndSendsNothing() {
        PasswordResetController controller = controllerWithoutMail();
        var resp = controller.requestReset(new PasswordResetController.RequestResetRequest("ada", TENANT), null);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(mailAdapter.deliveries().isEmpty());
    }

    /**
     * REG-39 layer 2: a stale identity-pack copy (no {@code token_version} column) must surface the
     * confirm step as a distinct {@code identity_pack_schema_error}, not the generic
     * {@code password_reset_failed} it used to collapse into via {@code bumpTokenVersion}'s
     * best-effort swallow. The credential update itself still succeeds -- only the revocation bump fails.
     */
    @Test
    void confirmAgainstStaleIdentityPackSurfacesSchemaErrorNotGenericFailure() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + "Stale" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource staleDataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = staleDataSource.getConnection(); Statement s = c.createStatement()) {
            // No token_version column -- the pre-LNCH-4 shape.
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "email VARCHAR(200), active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_password_reset_tokens (id UUID PRIMARY KEY, user_id UUID, "
                    + "token_hash VARCHAR(64), expires_at TIMESTAMP, used_at TIMESTAMP, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('" + USER_ID + "','ada','ada@example.com',TRUE,'"
                    + TENANT + "')");
            s.execute("INSERT INTO usuarios VALUES (RANDOM_UUID(), '" + USER_ID + "', '"
                    + PasswordHasher.hash("old-password") + "', '" + TENANT + "')");
        }
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("mail", "EmailCapability", "mail-inproc", mailAdapter);
        PasswordResetController controller = new PasswordResetController(
                staleDataSource, registry, new RegistryCapabilityDispatcher(registry),
                "usuarios", "user_id", "senha_hash", ""
        );

        var requestResp = controller.requestReset(new PasswordResetController.RequestResetRequest("ada", TENANT), null);
        assertEquals(200, requestResp.getStatusCode().value());
        String token = extractToken((String) mailAdapter.deliveries().get(0).get("body"));

        var confirmResp = controller.confirmReset(
                new PasswordResetController.ConfirmResetRequest(token, "brand-new-password", TENANT));

        assertEquals(500, confirmResp.getStatusCode().value());
        assertEquals("identity_pack_schema_error", confirmResp.getBody().get("error"));

        try (Connection c = staleDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT senha_hash FROM usuarios WHERE user_id = ?")) {
            ps.setObject(1, USER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(PasswordHasher.verify("brand-new-password", rs.getString(1)),
                        "the credential update itself must still have committed before the revocation bump failed");
            }
        }
    }

    private static String extractToken(String body) {
        String marker = "code is: ";
        int idx = body.indexOf(marker);
        assertTrue(idx >= 0, "expected reset body to contain the token: " + body);
        String rest = body.substring(idx + marker.length());
        return rest.split("\\r?\\n", 2)[0].trim();
    }

    private static String sha256Hex(String value) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
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
