package com.finalexec.controlpanel;

import com.finalexec.config.ModelHolder;
import com.npdev.adapters.audit.inproc.InProcAuditLogStore;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * QUAL-41: this 735-line, 8-endpoint SUPERUSER-only controller (per-tenant identity user/role/
 * permission maintenance) had zero test coverage before this file -- flagged by name, twice, in
 * QUAL-41's own history ("the largest of the five REG-177 touched... NOT attempted this pass") as
 * the next natural target once the smaller REG-177-shaped controllers were covered.
 *
 * <p>Covers the same two axes every sibling ControlPanel test in this package already proves
 * (non-SUPERUSER caller forbidden, no physical DataSource -> 503), plus behaviour specific to this
 * class: {@code list()}'s documented "identity pack absent degrades to an empty role list, not a
 * 503" contract (the one endpoint here that stays available without the pack, unlike every WRITE
 * endpoint, which hard-requires it via {@code requireIdentityTables()}); {@code grantRole}'s
 * cross-check against the MODEL's declared roles ({@link com.npdev.dsl.v1.compiled.CompiledRole} --
 * a different thing from an assigned {@code identity_user_roles} row); and {@code revokeRole}'s
 * real DELETE + audit trail, including the shared {@code requireIdentityTables()} 503 guard every
 * write endpoint in this class reuses (proven once here, not duplicated per endpoint).
 */
class ControlPanelTenantUsersControllerTest {

    private static final String TENANT = "acme";

    private DataSource dataSource;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private InProcAuditLogStore auditLogStore;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        auditLogStore = new InProcAuditLogStore();
    }

    private ControlPanelTenantUsersController controller(DataSource providedDataSource, CompiledModel model) {
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(providedDataSource);
        CapabilityRegistry registry = new CapabilityRegistry();
        return new ControlPanelTenantUsersController(
                provider, runtimeContextService, registry, new RegistryCapabilityDispatcher(registry),
                new ModelHolder(model), auditLogStore,
                "", "id", "username", "display_name", "usuarios", "user_id", "senha_hash"
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

    /** Same four identity-pack concepts as {@link #identityModel()}, plus app-declared roles --
     *  needed by the permission-override endpoints' {@code findDeclaredRole} check, which
     *  {@code identityModel()}'s empty {@code roles[]} deliberately cannot satisfy (see
     *  {@code grantRoleRejectsARoleTheModelDoesNotDeclare}'s own comment on that). */
    private static CompiledModel identityModelWithRoles(CompiledRole... roles) {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        return new CompiledModel(
                "test", "2.0", "1.0.0", concepts,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null,
                List.of(roles)
        );
    }

    private void createSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "display_name VARCHAR(200), tenant_id VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_role_permissions (id UUID PRIMARY KEY, user_role_id UUID, "
                    + "permission VARCHAR(120), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
        }
    }

    private String insertUser(String username) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity_users (id, username, display_name, tenant_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, java.util.UUID.fromString(id));
            ps.setString(2, username);
            ps.setString(3, username);
            ps.setString(4, TENANT);
            ps.executeUpdate();
        }
        return id;
    }

    private String insertRole(String roleName) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity_roles (id, name, tenant_id) VALUES (?, ?, ?)")) {
            ps.setObject(1, java.util.UUID.fromString(id));
            ps.setString(2, roleName);
            ps.setString(3, TENANT);
            ps.executeUpdate();
        }
        return id;
    }

    /** Returns the new row's own id -- the permission-override tests need it to look up/verify
     *  {@code identity_user_role_permissions} rows, which key off {@code user_role_id}, not
     *  {@code (user_id, role_id)} directly. */
    private String assignRole(String userId, String roleId) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity_user_roles (id, user_id, role_id, tenant_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, java.util.UUID.fromString(id));
            ps.setObject(2, java.util.UUID.fromString(userId));
            ps.setObject(3, java.util.UUID.fromString(roleId));
            ps.setString(4, TENANT);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertCredential(String userId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO usuarios (id, user_id, senha_hash, tenant_id) VALUES (RANDOM_UUID(), ?, 'oldhash', ?)")) {
            ps.setObject(1, java.util.UUID.fromString(userId));
            ps.setString(2, TENANT);
            ps.executeUpdate();
        }
    }

    private void insertPermissionOverride(String userRoleId, String permission) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity_user_role_permissions (id, user_role_id, permission, tenant_id) "
                             + "VALUES (RANDOM_UUID(), ?, ?, ?)")) {
            ps.setObject(1, java.util.UUID.fromString(userRoleId));
            ps.setString(2, permission);
            ps.setString(3, TENANT);
            ps.executeUpdate();
        }
    }

    @Test
    void nonSuperUserCallerIsForbidden() {
        authenticateAs("ADMIN");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(dataSource, identityModel()).list(TENANT, null));

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void inMemoryModeWithNoPhysicalDataSourceReturns503() {
        authenticateAs("SUPERUSER");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(null, identityModel()).list(TENANT, null));

        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    void listDegradesToEmptyRolesWhenIdentityPackAbsentRatherThanFailing() throws Exception {
        // Class javadoc's own documented contract: the READ side stays available even when this
        // app never composed the identity pack -- only the WRITE endpoints (grantRole/revokeRole/
        // permission overrides) hard-require it, via requireIdentityTables().
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "display_name VARCHAR(200), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
        }
        insertUser("ada");
        authenticateAs("SUPERUSER");

        var users = controller(dataSource, modelWithoutIdentityPack()).list(TENANT, null);

        assertEquals(1, users.size());
        assertEquals("ada", users.get(0).get("username"));
        assertEquals(List.of(), users.get(0).get("roles"));
        assertEquals(false, users.get(0).get("hasPassword"));
    }

    @Test
    void listReturnsUsersWithRolesAndCredentialFlag() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        assignRole(userId, roleId);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO usuarios (id, user_id, senha_hash, tenant_id) VALUES (RANDOM_UUID(), ?, 'hash', ?)")) {
            ps.setObject(1, java.util.UUID.fromString(userId));
            ps.setString(2, TENANT);
            ps.executeUpdate();
        }
        authenticateAs("SUPERUSER");

        var users = controller(dataSource, identityModel()).list(TENANT, null);

        assertEquals(1, users.size());
        assertEquals("ada", users.get(0).get("username"));
        assertEquals(List.of("MANAGER"), users.get(0).get("roles"));
        assertEquals(true, users.get(0).get("hasPassword"));
    }

    @Test
    void grantRoleRejectsARoleTheModelDoesNotDeclare() {
        // findDeclaredRole runs BEFORE requireDataSource()/requireIdentityTables() -- an
        // undeclared role is rejected even with no schema and no identity pack composed at all,
        // which is itself part of what this test locks in (identityModel() here has an empty
        // roles[] the same as every hand-built CompiledModel in this package's sibling tests, so
        // EVERY role name is "not declared" -- exercising the rejection needs no special setup).
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).grantRole(
                TENANT, "bob", new ControlPanelTenantUsersController.RoleGrantRequest("GhostRole"), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("role_not_declared_by_model", response.getBody().get("error"));
    }

    @Test
    void revokeRoleRequires503WhenIdentityPackAbsent() {
        // The shared requireIdentityTables() guard, proven once here -- grantRole (after its own
        // role-declaration check passes), listPermissionOverrides, grantPermissionOverride, and
        // revokePermissionOverride all call the exact same private method.
        authenticateAs("SUPERUSER");

        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(dataSource, modelWithoutIdentityPack()).revokeRole(TENANT, "bob", "MANAGER", null));

        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    void revokeRoleReturns404WhenUserNotFound() throws Exception {
        createSchema();
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).revokeRole(TENANT, "ghost", "MANAGER", null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("user_not_found", response.getBody().get("error"));
    }

    @Test
    void revokeRoleReturns404WhenRoleNotAssigned() throws Exception {
        createSchema();
        insertUser("ada");
        insertRole("MANAGER");
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).revokeRole(TENANT, "ada", "MANAGER", null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("role_not_assigned", response.getBody().get("error"));
    }

    @Test
    void revokeRoleDeletesTheAssignmentAndAudits() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        assignRole(userId, roleId);
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).revokeRole(TENANT, "ada", "MANAGER", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("ok"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM identity_user_roles WHERE user_id = ? AND tenant_id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(userId));
            ps.setString(2, TENANT);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "the role assignment row must actually be deleted");
            }
        }

        List<AuditRecord> audited = auditLogStore.search(AuditQuery.emptyForTenant(TENANT));
        assertEquals(1, audited.size());
        assertEquals("role.revoke", audited.get(0).action());
        assertEquals("ada:MANAGER", audited.get(0).resourceId());
    }

    // ---------- resetPassword (QUAL-41 next slice) ----------

    @Test
    void resetPasswordRejectsMissingPassword() throws Exception {
        createSchema();
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).resetPassword(
                TENANT, "ada", new ControlPanelTenantUsersController.ResetPasswordRequest(""), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("missing_password", response.getBody().get("error"));
    }

    @Test
    void resetPasswordReturns404WhenUserNotFound() throws Exception {
        createSchema();
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).resetPassword(
                TENANT, "ghost", new ControlPanelTenantUsersController.ResetPasswordRequest("newpass123"), null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("user_not_found", response.getBody().get("error"));
    }

    @Test
    void resetPasswordReturns404WhenCredentialRowMissing() throws Exception {
        createSchema();
        insertUser("ada");
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).resetPassword(
                TENANT, "ada", new ControlPanelTenantUsersController.ResetPasswordRequest("newpass123"), null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("credential_not_found", response.getBody().get("error"));
    }

    @Test
    void resetPasswordUpdatesTheHashAndBumpsTokenVersion() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        insertCredential(userId);
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).resetPassword(
                TENANT, "ada", new ControlPanelTenantUsersController.ResetPasswordRequest("newpass123"), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("ok"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT senha_hash FROM usuarios WHERE user_id = ? AND tenant_id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(userId));
            ps.setString(2, TENANT);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertTrue(com.finalexec.auth.PasswordHasher.verify("newpass123", rs.getString(1)),
                        "the stored hash must verify against the new password, not still the old one");
            }
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT token_version FROM identity_users WHERE id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(userId));
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1),
                        "LNCH-4: a reset must invalidate sessions minted under the old password");
            }
        }
    }

    // ---------- revokeSessions ----------

    @Test
    void revokeSessionsReturns404WhenUserNotFound() throws Exception {
        createSchema();
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).revokeSessions(TENANT, "ghost", null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("user_not_found", response.getBody().get("error"));
    }

    @Test
    void revokeSessionsBumpsTokenVersion() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).revokeSessions(TENANT, "ada", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("ok"));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT token_version FROM identity_users WHERE id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(userId));
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    // ---------- listPermissionOverrides ----------

    @Test
    void listPermissionOverridesRejectsUndeclaredRole() {
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).listPermissionOverrides(
                TENANT, "ada", "GhostRole", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("role_not_declared_by_model", response.getBody().get("error"));
    }

    @Test
    void listPermissionOverridesReturns404WhenRoleNotAssignedToUser() throws Exception {
        createSchema();
        insertUser("ada");
        insertRole("MANAGER");
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).listPermissionOverrides(TENANT, "ada", "MANAGER", null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("role_not_assigned_to_user", response.getBody().get("error"));
    }

    @Test
    void listPermissionOverridesReturnsEmptyWhenNoneConfigured() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        assignRole(userId, roleId);
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT", "EXECUTE_FLOW")));

        var response = controller(dataSource, model).listPermissionOverrides(TENANT, "ada", "MANAGER", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(), response.getBody().get("overridePermissions"));
        assertEquals(false, response.getBody().get("restricted"));
        assertEquals(List.of("READ_AUDIT", "EXECUTE_FLOW"), response.getBody().get("declaredCeiling"));
    }

    @Test
    void listPermissionOverridesReturnsConfiguredOverrides() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        String userRoleId = assignRole(userId, roleId);
        insertPermissionOverride(userRoleId, "READ_AUDIT");
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT", "EXECUTE_FLOW")));

        var response = controller(dataSource, model).listPermissionOverrides(TENANT, "ada", "MANAGER", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("READ_AUDIT"), response.getBody().get("overridePermissions"));
        assertEquals(true, response.getBody().get("restricted"));
    }

    // ---------- grantPermissionOverride ----------

    @Test
    void grantPermissionOverrideRejectsUndeclaredRole() {
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).grantPermissionOverride(
                TENANT, "ada", "GhostRole",
                new ControlPanelTenantUsersController.PermissionOverrideRequest("READ_AUDIT"), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("role_not_declared_by_model", response.getBody().get("error"));
    }

    @Test
    void grantPermissionOverrideRejectsUnrecognizedPermission() {
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).grantPermissionOverride(
                TENANT, "ada", "MANAGER",
                new ControlPanelTenantUsersController.PermissionOverrideRequest("NOT_A_REAL_PERMISSION"), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("not_a_recognized_permission", response.getBody().get("error"));
    }

    @Test
    void grantPermissionOverrideRejectsPermissionOutsideRoleCeiling() {
        authenticateAs("SUPERUSER");
        // MANAGER's declared ceiling is READ_AUDIT only -- EXECUTE_FLOW is a real permission, just
        // not one this role is allowed to hold, the structural check this endpoint's own javadoc
        // describes as enforced "HERE... and again, independently, at read time."
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).grantPermissionOverride(
                TENANT, "ada", "MANAGER",
                new ControlPanelTenantUsersController.PermissionOverrideRequest("EXECUTE_FLOW"), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("permission_outside_role_ceiling", response.getBody().get("error"));
    }

    @Test
    void grantPermissionOverrideReturns404WhenRoleNotAssignedToUser() throws Exception {
        createSchema();
        insertUser("ada");
        insertRole("MANAGER");
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).grantPermissionOverride(
                TENANT, "ada", "MANAGER",
                new ControlPanelTenantUsersController.PermissionOverrideRequest("READ_AUDIT"), null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("role_not_assigned_to_user", response.getBody().get("error"));
    }

    @Test
    void grantPermissionOverrideInsertsTheRowAuditsAndIsIdempotent() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        String userRoleId = assignRole(userId, roleId);
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));
        var request = new ControlPanelTenantUsersController.PermissionOverrideRequest("READ_AUDIT");

        var first = controller(dataSource, model).grantPermissionOverride(TENANT, "ada", "MANAGER", request, null);
        var second = controller(dataSource, model).grantPermissionOverride(TENANT, "ada", "MANAGER", request, null);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM identity_user_role_permissions WHERE user_role_id = ? AND tenant_id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(userRoleId));
            ps.setString(2, TENANT);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1), "re-granting an already-bound permission must not duplicate the row");
            }
        }

        List<AuditRecord> audited = auditLogStore.search(AuditQuery.emptyForTenant(TENANT));
        assertEquals(2, audited.size(), "both grant calls are audited even though only one row ever exists");
        assertEquals("permission_override.grant", audited.get(0).action());
        assertEquals("ada:MANAGER:READ_AUDIT", audited.get(0).resourceId());
    }

    // ---------- revokePermissionOverride ----------

    @Test
    void revokePermissionOverrideRejectsUndeclaredRole() {
        authenticateAs("SUPERUSER");

        var response = controller(dataSource, identityModel()).revokePermissionOverride(
                TENANT, "ada", "GhostRole", "READ_AUDIT", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("role_not_declared_by_model", response.getBody().get("error"));
    }

    @Test
    void revokePermissionOverrideReturns404WhenRoleNotAssignedToUser() throws Exception {
        createSchema();
        insertUser("ada");
        insertRole("MANAGER");
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).revokePermissionOverride(
                TENANT, "ada", "MANAGER", "READ_AUDIT", null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("role_not_assigned_to_user", response.getBody().get("error"));
    }

    @Test
    void revokePermissionOverrideReturns404WhenOverrideNotFound() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        assignRole(userId, roleId);
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).revokePermissionOverride(
                TENANT, "ada", "MANAGER", "READ_AUDIT", null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("override_not_found", response.getBody().get("error"));
    }

    @Test
    void revokePermissionOverrideDeletesTheRowAndAudits() throws Exception {
        createSchema();
        String userId = insertUser("ada");
        String roleId = insertRole("MANAGER");
        String userRoleId = assignRole(userId, roleId);
        insertPermissionOverride(userRoleId, "READ_AUDIT");
        authenticateAs("SUPERUSER");
        CompiledModel model = identityModelWithRoles(new CompiledRole("MANAGER", List.of("READ_AUDIT")));

        var response = controller(dataSource, model).revokePermissionOverride(
                TENANT, "ada", "MANAGER", "READ_AUDIT", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("ok"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM identity_user_role_permissions WHERE user_role_id = ? AND tenant_id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(userRoleId));
            ps.setString(2, TENANT);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "the override row must actually be deleted");
            }
        }

        List<AuditRecord> audited = auditLogStore.search(AuditQuery.emptyForTenant(TENANT));
        assertEquals(1, audited.size());
        assertEquals("permission_override.revoke", audited.get(0).action());
        assertEquals("ada:MANAGER:READ_AUDIT", audited.get(0).resourceId());
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
