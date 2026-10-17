package com.finalexec.auth;

import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.npdev.adapters.mail.inproc.InProcMailCapabilityAdapter;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-14: hermetic proof of opt-in self-signup -- H2-backed identity tables plus a real
 * InProcMailCapabilityAdapter behind the real CapabilityRegistry/RegistryCapabilityDispatcher.
 */
class SignupControllerTest {

    private static final String LINK_BASE = "http://localhost:8084/reset-password.html?token=";
    private final String tenant = DefaultTenant.id();

    private DataSource dataSource;
    private InProcMailCapabilityAdapter mailAdapter;
    private int requestCounter;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), display_name VARCHAR(200), "
                    + "email VARCHAR(200), active BOOLEAN, tenant_id VARCHAR(120), token_version INT, last_login_at TIMESTAMP)");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), description VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_v1_password_reset_tokens (id UUID PRIMARY KEY, user_id UUID, "
                    + "token_hash VARCHAR(64), expires_at TIMESTAMP, used_at TIMESTAMP, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_external_identity (id UUID PRIMARY KEY, user_id UUID, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(200), "
                    + "status VARCHAR(20), created_at_ms BIGINT)");
        }
        mailAdapter = new InProcMailCapabilityAdapter();
    }

    private SignupController controller(boolean enabled, String defaultRoles, String allowedDomains, boolean mailBound) {
        CapabilityRegistry registry = new CapabilityRegistry();
        if (mailBound) {
            registry.register("mail", "EmailCapability", "mail-inproc", mailAdapter);
        }
        return new SignupController(dataSource, registry, new RegistryCapabilityDispatcher(registry),
                new ModelHolder(identityModel()), "usuarios", "user_id", "senha_hash", LINK_BASE,
                enabled, defaultRoles, allowedDomains);
    }

    private SignupController enabledController() {
        return controller(true, "USER", "", true);
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        concepts.put("identity::PasswordResetToken", new CompiledConcept("PasswordResetToken",
                "PasswordResetToken", "identity_v1_password_reset_tokens", List.of()));
        concepts.put("identity::ExternalIdentity", new CompiledConcept("ExternalIdentity",
                "ExternalIdentity", "identity_external_identity", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    /** Each call comes from a fresh source IP so the per-IP ceiling never interferes with a test. */
    private org.springframework.http.ResponseEntity<Map<String, Object>> post(SignupController controller, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0." + (++requestCounter / 250) + "." + (requestCounter % 250));
        return controller.signup(new SignupController.SignupRequest(email, "Grace"), request);
    }

    private int count(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String tokenFrom(Map<String, Object> delivery) {
        String body = (String) delivery.get("body");
        int start = body.indexOf(LINK_BASE) + LINK_BASE.length();
        int end = body.indexOf("&signup=1", start);
        return body.substring(start, end);
    }

    @Test
    void disabledByDefaultAnswers404AndWritesNothing() throws Exception {
        var response = post(controller(false, "USER", "", true), "grace@example.com");

        assertEquals(404, response.getStatusCode().value());
        assertEquals("signup_disabled", response.getBody().get("error"));
        assertEquals(0, mailAdapter.deliveries().size());
        assertEquals(0, count("SELECT COUNT(*) FROM identity_users"));
    }

    @Test
    void enabledCreatesPendingUserWithDefaultRoleCredentialAndTokenAndMailsTheLink() throws Exception {
        var response = post(enabledController(), "Grace@Example.com");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("ok"));
        assertEquals(1, mailAdapter.deliveries().size());
        Map<String, Object> delivery = mailAdapter.deliveries().get(0);
        assertEquals(List.of("grace@example.com"), delivery.get("to"));
        assertTrue(((String) delivery.get("body")).contains("&signup=1"));

        assertEquals(1, count("SELECT COUNT(*) FROM identity_users WHERE username = 'grace@example.com' "
                + "AND email = 'grace@example.com' AND active = TRUE AND tenant_id = '" + tenant + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM identity_user_roles ur JOIN identity_roles r ON r.id = ur.role_id "
                + "WHERE r.name = 'USER'"));
        assertEquals(1, count("SELECT COUNT(*) FROM usuarios"));
        assertEquals(1, count("SELECT COUNT(*) FROM identity_v1_password_reset_tokens"));
    }

    @Test
    void theMailedTokenSetsAPasswordThatThenVerifies() throws Exception {
        SignupController signup = enabledController();
        post(signup, "grace@example.com");
        String token = tokenFrom(mailAdapter.deliveries().get(0));

        // Before the link is used the generated password is random and unknown to anyone.
        assertFalse(storedHashVerifies("anything-at-all-1"));

        var resetController = new PasswordResetController(dataSource, new CapabilityRegistry(),
                new RegistryCapabilityDispatcher(new CapabilityRegistry()), new ModelHolder(identityModel()),
                "usuarios", "user_id", "senha_hash", "");
        var confirm = resetController.confirmReset(
                new PasswordResetController.ConfirmResetRequest(token, "brand-new-password", tenant));

        assertEquals(200, confirm.getStatusCode().value());
        assertTrue(storedHashVerifies("brand-new-password"));
    }

    @Test
    void sameAddressAgainResendsWhilePendingButNeverCreatesASecondUser() throws Exception {
        SignupController signup = enabledController();
        post(signup, "grace@example.com");
        var second = post(signup, "grace@example.com");

        assertEquals(200, second.getStatusCode().value());
        assertEquals(2, mailAdapter.deliveries().size(), "a pending signup is resent a fresh link");
        assertEquals(1, count("SELECT COUNT(*) FROM identity_users"));
        assertEquals(2, count("SELECT COUNT(*) FROM identity_v1_password_reset_tokens"));
    }

    @Test
    void responsesAreIdenticalForNewResentForeignAndInvalidAddresses() throws Exception {
        SignupController signup = controller(true, "USER", "example.com", true);
        var fresh = post(signup, "a@example.com");
        var resent = post(signup, "a@example.com");
        var foreign = post(signup, "a@other.org");
        var invalid = post(signup, "not-an-email");
        var oversized = post(signup, "x".repeat(300) + "@example.com");

        for (var response : List.of(resent, foreign, invalid, oversized)) {
            assertEquals(fresh.getStatusCode(), response.getStatusCode());
            assertEquals(fresh.getBody(), response.getBody());
        }
    }

    @Test
    void foreignDomainWithAllowListGetsGenericOkButNoMailAndNoUser() throws Exception {
        var response = post(controller(true, "USER", "acme.com, @Other.com", true), "a@example.com");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, mailAdapter.deliveries().size());
        assertEquals(0, count("SELECT COUNT(*) FROM identity_users"));

        post(controller(true, "USER", "acme.com, @Other.com", true), "b@OTHER.com");
        assertEquals(1, mailAdapter.deliveries().size());
    }

    @Test
    void noMailCapabilityBoundCreatesNothing() throws Exception {
        var response = post(controller(true, "USER", "", false), "grace@example.com");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, count("SELECT COUNT(*) FROM identity_users"));
        assertEquals(0, count("SELECT COUNT(*) FROM identity_v1_password_reset_tokens"));
    }

    @Test
    void anAccountThatAlreadyExistsAndIsNotPendingIsNeverTouched() throws Exception {
        UUID ordinary = UUID.randomUUID();
        UUID oauthLinked = UUID.randomUUID();
        UUID usedToken = UUID.randomUUID();
        UUID signedIn = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // ordinary password account: no token history at all
            s.execute("INSERT INTO identity_users VALUES ('" + ordinary + "','ord@example.com','Ord','ord@example.com',TRUE,'"
                    + tenant + "',0,NULL)");
            // provider-linked account with a leftover unused token
            s.execute("INSERT INTO identity_users VALUES ('" + oauthLinked + "','oa@example.com','Oa','oa@example.com',TRUE,'"
                    + tenant + "',0,NULL)");
            s.execute("INSERT INTO identity_external_identity VALUES (RANDOM_UUID(), '" + oauthLinked + "', '" + tenant + "')");
            s.execute("INSERT INTO identity_v1_password_reset_tokens VALUES (RANDOM_UUID(), '" + oauthLinked
                    + "', 'h1', CURRENT_TIMESTAMP, NULL, '" + tenant + "')");
            // finished signup/reset: its token was used
            s.execute("INSERT INTO identity_users VALUES ('" + usedToken + "','used@example.com','Used','used@example.com',TRUE,'"
                    + tenant + "',0,NULL)");
            s.execute("INSERT INTO identity_v1_password_reset_tokens VALUES (RANDOM_UUID(), '" + usedToken
                    + "', 'h2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + tenant + "')");
            // has signed in
            s.execute("INSERT INTO identity_users VALUES ('" + signedIn + "','in@example.com','In','in@example.com',TRUE,'"
                    + tenant + "',0,CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO identity_v1_password_reset_tokens VALUES (RANDOM_UUID(), '" + signedIn
                    + "', 'h3', CURRENT_TIMESTAMP, NULL, '" + tenant + "')");
        }
        int tokensBefore = count("SELECT COUNT(*) FROM identity_v1_password_reset_tokens");
        SignupController signup = enabledController();

        for (String email : List.of("ord@example.com", "oa@example.com", "used@example.com", "in@example.com")) {
            assertEquals(200, post(signup, email).getStatusCode().value());
        }

        assertEquals(0, mailAdapter.deliveries().size(), "no mail for an account that is not a pending signup");
        assertEquals(tokensBefore, count("SELECT COUNT(*) FROM identity_v1_password_reset_tokens"));
        assertEquals(4, count("SELECT COUNT(*) FROM identity_users"));
    }

    @Test
    void throttlesRepeatedRequestsForOneAddress() throws Exception {
        SignupController signup = enabledController();
        for (int i = 0; i < 6; i++) {
            assertEquals(200, post(signup, "grace@example.com").getStatusCode().value());
        }
        assertEquals(3, mailAdapter.deliveries().size(), "only the first three requests per address get a mail");
    }

    @Test
    void privilegedDefaultRolesFailStartupWhenEnabledButNotWhenOff() {
        assertThrows(IllegalStateException.class, () -> controller(true, "USER, admin", "", true));
        assertThrows(IllegalStateException.class, () -> controller(true, "SUPERUSER", "", true));
        controller(false, "ADMIN", "", true);
    }

    @Test
    void aCredentialTableWithExtraRequiredColumnsKeepsTheGenericOkAndLeavesNoHalfAccount() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE usuarios ADD COLUMN entidade_id UUID NOT NULL");
        }
        var response = post(enabledController(), "grace@example.com");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, mailAdapter.deliveries().size());
        assertEquals(0, count("SELECT COUNT(*) FROM identity_users"), "the whole provisioning rolled back");
    }

    private boolean storedHashVerifies(String candidate) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT senha_hash FROM usuarios")) {
            assertTrue(rs.next());
            return PasswordHasher.verify(candidate, rs.getString(1));
        }
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
