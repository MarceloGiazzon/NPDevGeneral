package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * QUAL-41/EXT-10: targeted first-run coverage for {@link CreateUserController}, the ADMIN-gated
 * "mint another login in my own tenant" path exercised the moment a real user (not just the
 * anonymous {@link BootstrapAdminController} bootstrap) needs a second account. REG-177's own
 * regression -- an eagerly-{@code resolve()}d {@code IdentityPackTableNames} crashing Spring bean
 * construction for any app that sets {@code npdev.auth.mode=jwt} without composing the identity
 * pack -- landed in exactly this class's constructor shape before being fixed to the graceful,
 * per-request {@code tryResolve()} this test locks in (see {@code identityPackNotComposedFailsClosed}).
 * Follows {@code LoginControllerTest}'s hermetic H2 pattern (no Spring context).
 */
class CreateUserControllerTest {

    private static final String TENANT = "acme";

    private DataSource dataSource;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    private CreateUserController controller(CompiledModel model) {
        return new CreateUserController(
                dataSource, runtimeContextService, new ModelHolder(model),
                "usuarios", "user_id", "senha_hash",
                "entidade_id", "estabelecimento_padrao_id"
        );
    }

    private void authenticateAs(String tenantId, String... roles) {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of(tenantId, "caller").withRoles(Set.of(roles)));
    }

    /** REG-177: a compiled model that never composed the identity pack at all -- identity::User absent. */
    private static CompiledModel modelWithoutIdentityPack() {
        return new CompiledModel("test", "1.0.0", Map.of());
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

    private void createSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "display_name VARCHAR(200), email VARCHAR(200), active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), "
                    + "description VARCHAR(400), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120), entidade_id UUID, estabelecimento_padrao_id UUID)");
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(200), "
                    + "status VARCHAR(50), created_at_ms BIGINT)");
        }
    }

    @Test
    void nonAdminCallerIsForbidden() {
        authenticateAs(TENANT, "USER");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(identityModel()).createUser(
                        new CreateUserController.CreateUserRequest(
                                "bob", "Bob", "s3cret!", null, null, null, null),
                        null));

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void identityPackNotComposedFailsClosedNotEagerCrash() {
        // REG-177's own regression: this must be a clean 503 on the request path, never an
        // ApplicationContext startup failure -- there is no ApplicationContext here, so a bean
        // construction that once resolved eagerly would have made this test impossible to write
        // (the controller wouldn't have survived its own constructor).
        authenticateAs(TENANT, "ADMIN");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(modelWithoutIdentityPack()).createUser(
                        new CreateUserController.CreateUserRequest(
                                "bob", "Bob", "s3cret!", null, null, null, null),
                        null));

        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    void missingRequiredFieldReturns400() {
        authenticateAs(TENANT, "ADMIN");

        var response = controller(identityModel()).createUser(
                new CreateUserController.CreateUserRequest(
                        "  ", "Bob", "s3cret!", null, null, null, null),
                null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("missing_required_field", response.getBody().get("error"));
    }

    @Test
    void usernameTakenReturns409() throws Exception {
        createSchema();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO identity_users (id, username, display_name, active, tenant_id) VALUES "
                        + "(RANDOM_UUID(), 'ada', 'Ada', TRUE, ?)")) {
            ps.setString(1, TENANT);
            ps.executeUpdate();
        }
        authenticateAs(TENANT, "ADMIN");

        var response = controller(identityModel()).createUser(
                new CreateUserController.CreateUserRequest(
                        "ada", "Ada Two", "s3cret!", null, null, null, null),
                null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("username_taken", response.getBody().get("error"));
    }

    @Test
    void successfulCreateInsertsUserRoleCredentialAndAutoRegistersTenant() throws Exception {
        createSchema();
        authenticateAs(TENANT, "ADMIN");

        var response = controller(identityModel()).createUser(
                new CreateUserController.CreateUserRequest(
                        "bob", "Bob", "s3cret!", "bob@example.test", null, null, null),
                null);

        assertEquals(201, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("bob", body.get("username"));
        assertEquals(TENANT, body.get("tenantId"));
        assertEquals("ADMIN", body.get("roleName"));

        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT display_name, email FROM identity_users WHERE username = ? AND tenant_id = ?")) {
                ps.setString(1, "bob");
                ps.setString(2, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "identity user row must exist");
                    assertEquals("Bob", rs.getString(1));
                    assertEquals("bob@example.test", rs.getString(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM identity_user_roles ur JOIN identity_roles r ON ur.role_id = r.id "
                            + "WHERE r.name = 'ADMIN' AND r.tenant_id = ?")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getInt(1), "the new user must be linked to an ADMIN role row");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT senha_hash FROM usuarios WHERE tenant_id = ?")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "credential row must exist");
                    assertTrue(PasswordHasher.verify("s3cret!", rs.getString(1)),
                            "the stored hash must verify against the plaintext password submitted");
                }
            }
            // First-run tenant auto-registration (IdentityProvisioning.ensureTenantRegistered),
            // exercised as a side effect of insertIdentityUser -- proves the belt-and-suspenders
            // real-time registration path fires from THIS controller, not just from
            // TenantAutoRegistrationRunner's separate boot-time reconciliation sweep.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM npdev_tenant WHERE tenant_id = ?")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getInt(1), "the tenant must be auto-registered on first user provisioning");
                }
            }
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
