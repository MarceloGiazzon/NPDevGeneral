package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.auth.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates an additional login within the caller's own tenant, for use cases like WmsOffice's
 * "Novo Estabelecimento" page where a fresh business record (not a fresh tenant) wants its own
 * operator login. Unlike {@link BootstrapAdminController}, this is repeatable -- there is no
 * empty-tenant guard -- so it requires the caller to already be authenticated as ADMIN
 * ({@link RuntimeContextService}), matching the pattern used by
 * {@code com.npdev.generated.runtime.api.AdminController#requireAdminContext}.
 *
 * <p>The credential table/column names are configurable like {@link LoginController}/
 * {@link BootstrapAdminController}. Two additional nullable link columns (defaulting to
 * WmsOffice's own {@code Usuario} field names) may be populated when the caller supplies them, so
 * the new login can be tied back to the business record that prompted its creation (e.g. an
 * {@code Entidade}) without a separate follow-up write.</p>
 *
 * <p>Roles and users: {@code roleNames} gives the new login several roles at once, and
 * {@code POST/DELETE /api/auth/users/{username}/roles} lets the same tenant ADMIN add or remove
 * roles later -- the tenant-scoped counterpart of the SUPERUSER-only ControlPanel role endpoints.
 * A requested name matching a model-declared role is stored under the model's own spelling.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class CreateUserController {

    private static final String DEFAULT_ROLE_NAME = "ADMIN";

    private final DataSource dataSource;
    private final RuntimeContextService runtimeContextService;
    // REG-177 fix: graceful tryResolve, not the throwing resolve() -- this bean is only gated on
    // npdev.auth.mode=jwt, NOT on the identity pack being composed, so an app that sets jwt auth
    // mode without ever composing the identity pack must still boot (guarded per-request instead).
    // REG-208 (B28 lift): resolved fresh from modelHolder.get() on every request rather than cached
    // at construction, so a hot model reload is observed without needing a rebuild listener.
    private final ModelHolder modelHolder;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final String credentialPrimaryLinkColumn;
    private final String credentialSecondaryLinkColumn;

    public CreateUserController(
            DataSource dataSource,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.create-user.credential-primary-link-column:entidade_id}") String credentialPrimaryLinkColumn,
            @Value("${npdev.auth.create-user.credential-secondary-link-column:estabelecimento_padrao_id}") String credentialSecondaryLinkColumn
    ) {
        this.dataSource = dataSource;
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.credentialPrimaryLinkColumn = credentialPrimaryLinkColumn;
        this.credentialSecondaryLinkColumn = credentialSecondaryLinkColumn;
    }

    public record CreateUserRequest(
            String username, String displayName, String password, String email, String roleName,
            String primaryLinkId, String secondaryLinkId, List<String> roleNames
    ) {
        public CreateUserRequest(
                String username, String displayName, String password, String email, String roleName,
                String primaryLinkId, String secondaryLinkId
        ) {
            this(username, displayName, password, email, roleName, primaryLinkId, secondaryLinkId, null);
        }
    }

    public record AssignRolesRequest(List<String> roleNames) {
    }

    @PostMapping("/api/auth/create-user")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest request, HttpServletRequest httpRequest) {
        String tenantId = requireAdmin(httpRequest).tenantId();

        String username = request.username() == null ? null : request.username().trim();
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        String password = request.password();
        List<String> roleNames = requestedRoles(request);

        if (username == null || username.isBlank() || displayName == null || displayName.isBlank()
                || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field"));
        }
        Optional<IdentityPackTableNames> resolvedIdentityTables = IdentityPackTableNames.tryResolve(modelHolder.get());
        if (resolvedIdentityTables.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "identity_pack_not_composed");
        }
        IdentityPackTableNames identityTables = resolvedIdentityTables.get();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (IdentityProvisioning.usernameTaken(connection, identityTables, tenantId, username)) {
                    connection.rollback();
                    return ResponseEntity.status(409).body(Map.of("error", "username_taken"));
                }

                UUID userId = UUID.randomUUID();
                IdentityProvisioning.insertIdentityUser(
                        connection, identityTables, userId, username, displayName, request.email(), tenantId);

                for (String roleName : roleNames) {
                    UUID roleId = IdentityProvisioning.findOrCreateRole(
                            connection, identityTables, tenantId, roleName, "Created via /api/auth/create-user");
                    IdentityProvisioning.insertUserRole(connection, identityTables, userId, roleId, tenantId);
                }

                IdentityProvisioning.insertCredential(
                        connection, credentialTable, credentialUserIdColumn, credentialPasswordColumn,
                        UUID.randomUUID(), userId, password, tenantId,
                        credentialPrimaryLinkColumn, request.primaryLinkId(),
                        credentialSecondaryLinkColumn, request.secondaryLinkId());

                connection.commit();
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("userId", userId.toString());
                body.put("username", username);
                body.put("tenantId", tenantId);
                body.put("roleName", roleNames.get(0));
                body.put("roleNames", roleNames);
                return ResponseEntity.status(201).body(body);
            } catch (Exception exception) {
                connection.rollback();
                return ResponseEntity.status(500).body(Map.of("error", "create_user_failed"));
            }
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "create_user_failed"));
        }
    }

    /** Adds roles to an existing login in the caller's own tenant; already-held roles are skipped. */
    @PostMapping("/api/auth/users/{username}/roles")
    public ResponseEntity<Map<String, Object>> assignRoles(
            @PathVariable String username, @RequestBody AssignRolesRequest request, HttpServletRequest httpRequest
    ) {
        String tenantId = requireAdmin(httpRequest).tenantId();
        List<String> requested = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();
        for (String raw : request == null || request.roleNames() == null ? List.<String>of() : request.roleNames()) {
            String canonical = assignableRole(raw);
            if (canonical == null) {
                undeclared.add(String.valueOf(raw));
            } else if (!requested.contains(canonical)) {
                requested.add(canonical);
            }
        }
        if (requested.isEmpty() && undeclared.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field"));
        }
        if (!undeclared.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "role_not_declared_by_model",
                    "rejected", undeclared,
                    "assignableRoles", assignableRoles()));
        }
        IdentityPackTableNames identityTables = requireIdentityTables();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID userId = findUserId(connection, identityTables, tenantId, username);
                if (userId == null) {
                    connection.rollback();
                    return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
                }
                List<String> held = rolesOf(connection, identityTables, tenantId, userId);
                for (String roleName : requested) {
                    if (held.stream().anyMatch(roleName::equalsIgnoreCase)) {
                        continue;
                    }
                    UUID roleId = IdentityProvisioning.findOrCreateRole(
                            connection, identityTables, tenantId, roleName, "Assigned via /api/auth/users/{username}/roles");
                    IdentityProvisioning.insertUserRole(connection, identityTables, userId, roleId, tenantId);
                }
                List<String> roles = rolesOf(connection, identityTables, tenantId, userId);
                connection.commit();
                return ResponseEntity.ok(Map.of("username", username, "roleNames", roles));
            } catch (Exception exception) {
                connection.rollback();
                return ResponseEntity.status(500).body(Map.of("error", "assign_roles_failed"));
            }
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "assign_roles_failed"));
        }
    }

    /** Removes one role from a login in the caller's own tenant. */
    @DeleteMapping("/api/auth/users/{username}/roles/{role}")
    public ResponseEntity<Map<String, Object>> revokeRole(
            @PathVariable String username, @PathVariable String role, HttpServletRequest httpRequest
    ) {
        String tenantId = requireAdmin(httpRequest).tenantId();
        IdentityPackTableNames identityTables = requireIdentityTables();
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = findUserId(connection, identityTables, tenantId, username);
            if (userId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
            }
            String held = rolesOf(connection, identityTables, tenantId, userId).stream()
                    .filter(name -> name.equalsIgnoreCase(role.trim())).findFirst().orElse(null);
            if (held == null) {
                return ResponseEntity.status(404).body(Map.of("error", "role_not_assigned"));
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + identityTables.userRolesTable() + " WHERE user_id = ? AND tenant_id = ? AND role_id = "
                            + "(SELECT id FROM " + identityTables.rolesTable() + " WHERE name = ? AND tenant_id = ?)")) {
                ps.setObject(1, userId);
                ps.setString(2, tenantId);
                ps.setString(3, held);
                ps.setString(4, tenantId);
                ps.executeUpdate();
            }
            return ResponseEntity.ok(Map.of("username", username,
                    "roleNames", rolesOf(connection, identityTables, tenantId, userId)));
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "revoke_role_failed"));
        }
    }

    private ExecutionContext requireAdmin(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context;
    }

    private IdentityPackTableNames requireIdentityTables() {
        return IdentityPackTableNames.tryResolve(modelHolder.get()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "identity_pack_not_composed"));
    }

    /** {@code roleNames} wins over the legacy single {@code roleName}; neither given means ADMIN. */
    private List<String> requestedRoles(CreateUserRequest request) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (request.roleNames() != null) {
            for (String raw : request.roleNames()) {
                if (raw != null && !raw.isBlank()) {
                    roles.add(canonicalRoleName(raw));
                }
            }
        }
        if (roles.isEmpty() && request.roleName() != null && !request.roleName().isBlank()) {
            roles.add(canonicalRoleName(request.roleName()));
        }
        if (roles.isEmpty()) {
            roles.add(DEFAULT_ROLE_NAME);
        }
        return List.copyOf(roles);
    }

    /** The model-declared spelling when the name matches a declared role, else upper case (legacy). */
    private String canonicalRoleName(String raw) {
        String declared = declaredRole(raw);
        return declared != null ? declared : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** A role the tenant ADMIN may assign: a model-declared role, or the built-in USER/ADMIN. */
    private String assignableRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String declared = declaredRole(raw);
        if (declared != null) {
            return declared;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return "USER".equals(upper) || "ADMIN".equals(upper) ? upper : null;
    }

    private String declaredRole(String raw) {
        String normalized = RolePermissions.normalizeRoleName(raw);
        if (normalized == null) {
            return null;
        }
        for (CompiledRole role : modelHolder.get().getRoles()) {
            if (normalized.equals(RolePermissions.normalizeRoleName(role.name()))) {
                return role.name();
            }
        }
        return null;
    }

    private List<String> assignableRoles() {
        List<String> roles = new ArrayList<>(modelHolder.get().getRoles().stream().map(CompiledRole::name).toList());
        roles.add("USER");
        roles.add("ADMIN");
        return roles;
    }

    private static UUID findUserId(Connection connection, IdentityPackTableNames tables, String tenantId,
                                   String username) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM " + tables.usersTable() + " WHERE username = ? AND tenant_id = ?")) {
            ps.setString(1, username);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(String.valueOf(rs.getObject(1))) : null;
            }
        }
    }

    private static List<String> rolesOf(Connection connection, IdentityPackTableNames identityTables, String tenantId,
                                        UUID userId) throws Exception {
        List<String> roles = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.name FROM " + identityTables.userRolesTable() + " ur JOIN "
                        + identityTables.rolesTable() + " r ON r.id = ur.role_id"
                        + " WHERE ur.user_id = ? AND ur.tenant_id = ? ORDER BY r.name")) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        }
        return roles;
    }
}
