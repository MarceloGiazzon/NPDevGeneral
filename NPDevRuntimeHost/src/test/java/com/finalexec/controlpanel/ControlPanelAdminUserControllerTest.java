package com.finalexec.controlpanel;

import com.finalexec.auth.PasswordHasher;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

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
 * QUAL-41/EXT-10: targeted first-run coverage for {@link ControlPanelAdminUserController}, the
 * SUPERUSER-only "create an admin for any tenant" action -- registered UNCONDITIONALLY in every
 * generated app (never gated on the identity pack, unlike {@code BootstrapAdminController}/
 * {@code CreateUserController}'s {@code npdev.auth.mode=jwt} gate). That unconditional registration
 * is exactly why REG-177's original eager {@code IdentityPackTableNames.resolve()} call in this
 * class's constructor was a live packaged-app boot crash (BeanCreationException ->
 * IllegalStateException) for ANY generated app that doesn't compose the identity pack -- see this
 * class's own javadoc and REG-177's ledger "2026-08-15 correction" note. This test locks in the
 * fixed, graceful per-request behavior on both axes that can make ControlPanel unavailable
 * (no physical DataSource / no identity pack), not just the happy path.
 */
class ControlPanelAdminUserControllerTest {

    private static final String TENANT = "acme";

    private DataSource dataSource;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    private ControlPanelAdminUserController controller(DataSource providedDataSource, CompiledModel model) {
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(providedDataSource);
        return new ControlPanelAdminUserController(
                provider, runtimeContextService, new ModelHolder(model),
                "usuarios", "user_id", "senha_hash"
        );
    }

    private void authenticateAs(String... roles) {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("caller-tenant", "operator").withRoles(Set.of(roles)));
    }

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
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(200), "
                    + "status VARCHAR(50), created_at_ms BIGINT)");
        }
    }

    @Test
    void nonSuperUserCallerIsForbidden() {
        authenticateAs("ADMIN");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(dataSource, identityModel()).create(
                        new ControlPanelAdminUserController.CreateTenantAdminRequest(
                                TENANT, "bob", "Bob", "s3cret!", null),
                        null));

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void inMemoryModeWithNoPhysicalDataSourceReturns503() {
        authenticateAs("SUPERUSER");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(null, identityModel()).create(
                        new ControlPanelAdminUserController.CreateTenantAdminRequest(
                                TENANT, "bob", "Bob", "s3cret!", null),
                        null));

        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    void identityPackNotComposedFailsClosedNotEagerCrash() {
        // REG-177's own regression: with the throwing resolve(), this bean's CONSTRUCTOR would
        // never even complete for an app that doesn't compose the identity pack, since this
        // controller is registered unconditionally. There is no ApplicationContext in this test, so
        // reaching this assertion at all is already proof the constructor no longer resolves eagerly.
        authenticateAs("SUPERUSER");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(dataSource, modelWithoutIdentityPack()).create(
                        new ControlPanelAdminUserController.CreateTenantAdminRequest(
                                TENANT, "bob", "Bob", "s3cret!", null),
                        null));

        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    void missingRequiredFieldReturns400() {
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).create(
                new ControlPanelAdminUserController.CreateTenantAdminRequest(
                        TENANT, "  ", "Bob", "s3cret!", null),
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
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).create(
                new ControlPanelAdminUserController.CreateTenantAdminRequest(
                        TENANT, "ada", "Ada Two", "s3cret!", null),
                null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("username_taken", response.getBody().get("error"));
    }

    @Test
    void successfulCreateInsertsUserRoleCredentialAndAutoRegistersTenant() throws Exception {
        createSchema();
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).create(
                new ControlPanelAdminUserController.CreateTenantAdminRequest(
                        TENANT, "bob", "Bob", "s3cret!", "bob@example.test"),
                null);

        assertEquals(201, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("bob", body.get("username"));
        assertEquals(TENANT, body.get("tenantId"));
        assertEquals("ADMIN", body.get("roleName"));

        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM identity_user_roles ur JOIN identity_roles r ON ur.role_id = r.id "
                            + "WHERE r.name = 'ADMIN' AND r.tenant_id = ?")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getInt(1), "the new tenant admin must be linked to an ADMIN role row");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT senha_hash FROM usuarios WHERE tenant_id = ?")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "credential row must exist");
                    assertTrue(PasswordHasher.verify("s3cret!", rs.getString(1)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM npdev_tenant WHERE tenant_id = ?")) {
                ps.setString(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getInt(1),
                            "creating a tenant's first admin from ControlPanel must auto-register that tenant "
                                    + "(this is how a brand-new tenant becomes visible in the ControlPanel workspace "
                                    + "list without waiting for TenantAutoRegistrationRunner's next boot sweep)");
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
