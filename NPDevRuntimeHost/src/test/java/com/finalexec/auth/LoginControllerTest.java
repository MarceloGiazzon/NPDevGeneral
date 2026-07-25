package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
                dataSource, new ObjectMapper(), new DefaultResourceLoader(),
                "usuarios", "user_id", "senha_hash",
                "classpath:npdev/security/test-jwt-private.pem",
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L
        );
    }

    /** Current (fresh) shape of the identity pack -- includes token_version, as the platform's pack has since LNCH-4. */
    private void createFreshSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "active BOOLEAN, tenant_id VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
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
    void wrongPasswordAgainstFreshSchemaStaysInvalidCredentials() throws Exception {
        createFreshSchema();
        LoginController controller = controller();

        var response = controller.login(
                new LoginController.LoginRequest("ada", "wrong-password", TENANT), null);

        assertEquals(401, response.getStatusCode().value());
        assertEquals("invalid_credentials", response.getBody().get("error"));
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
