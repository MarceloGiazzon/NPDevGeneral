package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.adapters.mail.inproc.InProcMailCapabilityAdapter;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SEC-6: hermetic proof of the invitation flow -- an ADMIN invites an e-mail address, the mailed
 * one-time link lets the invitee choose a password through the EXISTING password-reset confirm, and
 * the admin never sees it. H2 + the real in-proc mail adapter through the real capability registry;
 * no Spring context (modelled on CreateUserControllerTest and PasswordResetControllerTest).
 */
class InvitationControllerTest {

    private static final String TENANT = "acme";

    private DataSource dataSource;
    private InProcMailCapabilityAdapter mailAdapter;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_v1_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "display_name VARCHAR(200), email VARCHAR(200), active BOOLEAN, tenant_id VARCHAR(120), "
                    + "token_version INT)");
            s.execute("CREATE TABLE identity_v1_roles (id UUID PRIMARY KEY, name VARCHAR(120), "
                    + "description VARCHAR(400), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_v1_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_v1_user_role_permissions (id UUID PRIMARY KEY)");
            s.execute("CREATE TABLE identity_v1_password_reset_tokens (id UUID PRIMARY KEY, user_id UUID, "
                    + "token_hash VARCHAR(64), expires_at TIMESTAMP, used_at TIMESTAMP, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120), entidade_id UUID, estabelecimento_padrao_id UUID)");
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(200), "
                    + "status VARCHAR(50), created_at_ms BIGINT)");
        }
        mailAdapter = new InProcMailCapabilityAdapter();
        authenticateAs("ADMIN");
    }

    private void authenticateAs(String... roles) {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of(TENANT, "caller").withRoles(Set.of(roles)));
    }

    private InvitationController controller(boolean mailBound) {
        CapabilityRegistry registry = new CapabilityRegistry();
        if (mailBound) {
            registry.register("mail", "EmailCapability", "mail-inproc", mailAdapter);
        }
        return new InvitationController(
                dataSource, runtimeContextService, new ModelHolder(model()), registry,
                new RegistryCapabilityDispatcher(registry),
                "usuarios", "user_id", "senha_hash", "entidade_id", "estabelecimento_padrao_id", "", 168);
    }

    private PasswordResetController resetController() {
        CapabilityRegistry registry = new CapabilityRegistry();
        return new PasswordResetController(
                dataSource, registry, new RegistryCapabilityDispatcher(registry), new ModelHolder(model()),
                "usuarios", "user_id", "senha_hash", "");
    }

    private static CompiledModel model() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_v1_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_v1_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_v1_user_roles", List.of()));
        concepts.put("identity::UserRolePermission", new CompiledConcept(
                "UserRolePermission", "UserRolePermission", "identity_v1_user_role_permissions", List.of()));
        concepts.put("identity::PasswordResetToken", new CompiledConcept(
                "PasswordResetToken", "PasswordResetToken", "identity_v1_password_reset_tokens", List.of()));
        return new CompiledModel(
                "test", "2.0", "1.0.0", concepts,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null,
                List.of(new CompiledRole("Member", List.of("EXECUTE_FLOW"))));
    }

    private static InvitationController.InviteRequest invite(String email, String... roles) {
        return new InvitationController.InviteRequest(
                email, null, null, roles.length == 0 ? null : List.of(roles), null, null);
    }

    @Test
    void adminInviteCreatesTheUserMailsALinkAndStoresAWeekLongToken() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller(true).invite(invite("newbie@example.test"), null);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("emailed"));
        assertEquals("newbie@example.test", response.getBody().get("username"));
        assertFalse(response.getBody().containsKey("acceptPath"), "the raw token must not be returned when mailed");
        assertEquals(1, mailAdapter.deliveries().size());
        assertEquals(List.of("newbie@example.test"), mailAdapter.deliveries().get(0).get("to"));
        assertTrue(((String) mailAdapter.deliveries().get(0).get("body")).contains("invite=1"));

        assertEquals(List.of("USER"), rolesOf("newbie@example.test"));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT expires_at FROM identity_v1_password_reset_tokens")) {
            assertTrue(rs.next());
            Timestamp expiresAt = rs.getTimestamp(1);
            Duration untilExpiry = Duration.between(Instant.now(), expiresAt.toInstant());
            assertTrue(untilExpiry.toHours() >= 167 && untilExpiry.toHours() <= 168, "about 7 days: " + untilExpiry);
        }
    }

    @Test
    void nonAdminCallerIsForbidden() {
        authenticateAs("USER");
        assertThrows(ResponseStatusException.class, () -> controller(true).invite(invite("x@example.test"), null));
        assertTrue(mailAdapter.deliveries().isEmpty());
    }

    @Test
    void duplicateUsernameIs409() {
        controller(true).invite(invite("dup@example.test"), null);
        ResponseEntity<Map<String, Object>> second = controller(true).invite(invite("dup@example.test"), null);
        assertEquals(409, second.getStatusCode().value());
        assertEquals("username_taken", second.getBody().get("error"));
    }

    @Test
    void roleNotDeclaredByTheModelIs400() {
        ResponseEntity<Map<String, Object>> response = controller(true).invite(invite("r@example.test", "Wizard"), null);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("role_not_declared_by_model", response.getBody().get("error"));
        assertEquals(List.of("Wizard"), response.getBody().get("rejected"));
    }

    @Test
    void declaredRoleIsStoredUnderTheModelsSpelling() throws Exception {
        controller(true).invite(invite("m@example.test", "member"), null);
        assertEquals(List.of("Member"), rolesOf("m@example.test"));
    }

    @Test
    void invalidEmailIs400() {
        assertEquals("invalid_email", controller(true).invite(invite("not-an-email"), null).getBody().get("error"));
        assertEquals("invalid_email", controller(true).invite(invite(null), null).getBody().get("error"));
    }

    @Test
    void withNoMailBoundTheResponseCarriesTheAcceptPath() {
        ResponseEntity<Map<String, Object>> response = controller(false).invite(invite("nomail@example.test"), null);
        assertEquals(201, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("emailed"));
        String acceptPath = (String) response.getBody().get("acceptPath");
        assertTrue(acceptPath.startsWith("/reset-password.html?invite=1&token="), acceptPath);
        assertTrue(mailAdapter.deliveries().isEmpty());
    }

    @Test
    void fullRoundTripTheInviteeChoosesAPasswordThroughPasswordResetConfirm() throws Exception {
        controller(true).invite(invite("round@example.test"), null);
        String body = (String) mailAdapter.deliveries().get(0).get("body");
        int start = body.indexOf("token=") + "token=".length();
        String token = body.substring(start).split("\\r?\\n", 2)[0].trim();

        ResponseEntity<Map<String, Object>> weak = resetController().confirmReset(
                new PasswordResetController.ConfirmResetRequest(token, "short", TENANT));
        assertEquals(400, weak.getStatusCode().value(), "the password policy applies to invitees too");

        ResponseEntity<Map<String, Object>> ok = resetController().confirmReset(
                new PasswordResetController.ConfirmResetRequest(token, "a-good-passphrase", TENANT));
        assertEquals(200, ok.getStatusCode().value());
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT senha_hash FROM usuarios u JOIN identity_v1_users i ON i.id = u.user_id WHERE i.username = ?")) {
            ps.setString(1, "round@example.test");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(PasswordHasher.verify("a-good-passphrase", rs.getString(1)));
            }
        }
        // single use
        assertEquals(400, resetController().confirmReset(
                new PasswordResetController.ConfirmResetRequest(token, "another-passphrase", TENANT)).getStatusCode().value());
    }

    @Test
    void resendIssuesAFreshTokenForAnExistingUserWithAnEmail() {
        InvitationController controller = controller(true);
        controller.invite(invite("again@example.test"), null);
        assertEquals(1, mailAdapter.deliveries().size());

        ResponseEntity<Map<String, Object>> resent = controller.resend("again@example.test", null);

        assertEquals(200, resent.getStatusCode().value());
        assertEquals(true, resent.getBody().get("emailed"));
        assertEquals(2, mailAdapter.deliveries().size());
        assertEquals(404, controller.resend("ghost@example.test", null).getStatusCode().value());
    }

    @Test
    void resendWithNoEmailOnFileIs400() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO identity_v1_users VALUES (RANDOM_UUID(), 'noemail', 'No Email', NULL, TRUE, '"
                    + TENANT + "', 0)");
        }
        ResponseEntity<Map<String, Object>> response = controller(true).resend("noemail", null);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("no_email_on_file", response.getBody().get("error"));
        assertNull(response.getBody().get("acceptPath"));
    }

    private List<String> rolesOf(String username) throws Exception {
        List<String> roles = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT r.name FROM identity_v1_users u JOIN identity_v1_user_roles ur ON ur.user_id = u.id "
                        + "JOIN identity_v1_roles r ON r.id = ur.role_id WHERE u.username = ? ORDER BY r.name")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        }
        return roles;
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
