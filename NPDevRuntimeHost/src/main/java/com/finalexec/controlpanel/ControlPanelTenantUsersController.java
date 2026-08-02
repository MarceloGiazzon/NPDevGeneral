package com.finalexec.controlpanel;

import com.finalexec.auth.IdentityPackSchemaException;
import com.finalexec.auth.IdentityProvisioning;
import com.finalexec.auth.PasswordHasher;
import com.finalexec.auth.SqlSchemaErrors;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.auth.Permission;
import com.npdev.kernel.auth.RolePermissions;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ControlPanel read/maintenance surface over the people who can log in to each workspace (tenant).
 * SUPERUSER-only, like the rest of the ControlPanel actions.
 *
 * <p>The user table and its column names are configurable via the same {@code npdev.auth.login.*}
 * property family that already parametrizes the credential table, so an app whose user table is
 * named e.g. {@code Usuario} with {@code UsuCod}/{@code UsuNom} columns can still be served by this
 * endpoint without code changes. Role names are read from the identity-pack role tables when
 * present; if the app doesn't have them, users are returned with an empty role list.
 *
 * <p>Passwords are only ever stored hashed ({@link PasswordHasher}); there is deliberately no way
 * to read one back. The maintenance action offered instead is a reset: overwrite the stored hash
 * with a new password's hash.
 */
@RestController
@RequestMapping("/api/admin/tenants/{tenantId}/users")
public class ControlPanelTenantUsersController {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final RuntimeContextService runtimeContextService;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityDispatcher capabilityDispatcher;
    private final CompiledModel compiledModel;
    private final AuditLogStore auditLogStore;
    private final String userTable;
    private final String userIdColumn;
    private final String usernameColumn;
    private final String displayNameColumn;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;

    public ControlPanelTenantUsersController(
            ObjectProvider<DataSource> dataSourceProvider,
            RuntimeContextService runtimeContextService,
            CapabilityRegistry capabilityRegistry,
            CapabilityDispatcher capabilityDispatcher,
            CompiledModel compiledModel,
            AuditLogStore auditLogStore,
            @Value("${npdev.auth.login.user-table:identity_users}") String userTable,
            @Value("${npdev.auth.login.user-id-column:id}") String userIdColumn,
            @Value("${npdev.auth.login.username-column:username}") String usernameColumn,
            @Value("${npdev.auth.login.display-name-column:display_name}") String displayNameColumn,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.runtimeContextService = runtimeContextService;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityDispatcher = capabilityDispatcher;
        this.compiledModel = compiledModel;
        this.auditLogStore = auditLogStore;
        this.userTable = userTable;
        this.userIdColumn = userIdColumn;
        this.usernameColumn = usernameColumn;
        this.displayNameColumn = displayNameColumn;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
    }


    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String tenantId, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        List<Map<String, Object>> users = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            String sql = "SELECT " + userIdColumn + ", " + usernameColumn + ", " + displayNameColumn
                    + " FROM " + userTable + " WHERE tenant_id = ? ORDER BY " + usernameColumn;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        String userId = String.valueOf(rs.getObject(1));
                        row.put("userId", userId);
                        row.put("username", rs.getString(2));
                        row.put("displayName", rs.getString(3));
                        row.put("roles", rolesOf(connection, userId, tenantId));
                        row.put("hasPassword", hasCredential(connection, userId, tenantId));
                        users.add(row);
                    }
                }
            }
            return users;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "list_tenant_users_failed");
        }
    }

    public record ResetPasswordRequest(String password) {
    }

    @PutMapping("/{username}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable String tenantId, @PathVariable String username,
            @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        String password = request == null ? null : request.password();
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_password"));
        }

        try (Connection connection = dataSource.getConnection()) {
            Object userId = findUserId(connection, tenantId, username);
            if (userId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
            }
            String sql = "UPDATE " + credentialTable + " SET " + credentialPasswordColumn + " = ?"
                    + " WHERE " + credentialUserIdColumn + " = ? AND tenant_id = ?";
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, PasswordHasher.hash(password));
                ps.setObject(2, userId);
                ps.setString(3, tenantId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                return ResponseEntity.status(404).body(Map.of("error", "credential_not_found"));
            }
            // LNCH-4: a password reset must also invalidate whatever sessions/tokens were minted
            // under the old password -- otherwise an attacker who already has a live token keeps
            // access indefinitely (until natural expiry) right through the reset that was supposed
            // to lock them out. Best-effort: the reset itself already succeeded, so a revocation
            // failure here is logged, not surfaced as a reset failure.
            bumpTokenVersion(connection, tenantId, username);
            // LNCH-4/LNCH-11: best-effort notification -- only sent if this app bound a "mail"
            // capability and the account has an email on file; a missing capability/email is not
            // an error, since admin-forced reset is explicitly the fallback for apps/accounts
            // without one (see PasswordResetController's self-service flow for the primary path).
            notifyPasswordChanged(connection, tenantId, username);
            return ResponseEntity.ok(Map.of("ok", true, "username", username, "tenantId", tenantId));
        } catch (IdentityPackSchemaException schemaException) {
            // REG-39: the token_version bump found a schema mismatch (stale built-in-pack copy). The
            // credential update already committed, so report the revocation gap distinctly rather than
            // the generic reset_password_failed -- an operator needs to know this is a schema problem,
            // not a transient reset failure.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "identity_pack_schema_error: " + schemaException.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "reset_password_failed");
        }
    }

    /**
     * LNCH-4: invalidates every JWT already minted for this user by bumping
     * {@code identity_users.token_version} -- the counter a token's {@code tv} claim is checked
     * against on every request (see {@code IdentityRoleLookup#tokenVersion}). {@code COALESCE(...,0)}
     * treats a pre-migration NULL column the same as version 0, so the very first revoke on a
     * never-revoked user still moves the counter forward correctly.
     */
    @PostMapping("/{username}/revoke-sessions")
    public ResponseEntity<Map<String, Object>> revokeSessions(
            @PathVariable String tenantId, @PathVariable String username, HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();
        try (Connection connection = dataSource.getConnection()) {
            int updated = bumpTokenVersion(connection, tenantId, username);
            if (updated == 0) {
                return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
            }
            return ResponseEntity.ok(Map.of("ok", true, "username", username, "tenantId", tenantId));
        } catch (IdentityPackSchemaException schemaException) {
            // REG-39: without this, a schema mismatch here previously surfaced as a plain 404
            // user_not_found (bumpTokenVersion swallowed the SQLException and returned 0, which this
            // method could not tell apart from "no such user") -- exactly the misleading-failure shape
            // REG-39 is about.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "identity_pack_schema_error: " + schemaException.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "revoke_sessions_failed");
        }
    }

    public record RoleGrantRequest(String role) {
    }

    /**
     * Wave 3 (RC-B2, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN} Part B.2): grants a role, writing
     * {@code identity_user_roles} -- the table {@code IdentityAwareContextResolver} already treats
     * as authoritative, so no separate "activation" step is needed; the grant is live the moment
     * this commits. "The model owns the vocabulary; the administrator owns the binding" -- only a
     * role name the app model itself declares (RC-B1's {@code roles[]}) may be granted here, checked
     * against {@link CompiledModel#getRoles()} with the same case-insensitive normalization
     * {@link RolePermissions} uses everywhere else. Does NOT bump {@code token_version}: unlike a
     * password reset, a role never gets baked into the token -- {@code IdentityAwareContextResolver}
     * re-derives roles from {@code identity_user_roles} on every request, live-verified (a grant
     * showed up in the very next {@code GET .../users} call with the SAME token). Bumping it here
     * would only force an unrelated, unwanted logout on the user's next request.
     */
    @PostMapping("/{username}/roles")
    public ResponseEntity<Map<String, Object>> grantRole(
            @PathVariable String tenantId, @PathVariable String username,
            @RequestBody RoleGrantRequest request, HttpServletRequest httpRequest
    ) {
        ExecutionContext requester = requireSuperUser(httpRequest);
        String requestedRole = request == null ? null : request.role();
        CompiledRole declaredRole = findDeclaredRole(requestedRole);
        if (declaredRole == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "role_not_declared_by_model",
                    "declaredRoles", compiledModel.getRoles().stream().map(CompiledRole::name).toList()));
        }
        DataSource dataSource = requireDataSource();
        try (Connection connection = dataSource.getConnection()) {
            Object userId = findUserId(connection, tenantId, username);
            if (userId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
            }
            UUID roleId = IdentityProvisioning.findOrCreateRole(connection, tenantId, declaredRole.name(), null);
            IdentityProvisioning.insertUserRole(connection, UUID.fromString(String.valueOf(userId)), roleId, tenantId);
            auditRoleChange(requester, tenantId, username, declaredRole.name(), "role.grant");
            return ResponseEntity.ok(Map.of("ok", true, "username", username, "role", declaredRole.name()));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "grant_role_failed");
        }
    }

    /**
     * Wave 3 (RC-B2): revokes a role -- removes the {@code identity_user_roles} row. Live-verified:
     * takes effect on the user's very next request with the SAME token, because
     * {@code IdentityAwareContextResolver} re-derives roles from {@code identity_user_roles} fresh on
     * every request rather than trusting whatever the JWT was minted with. No {@code token_version}
     * bump here either, for the same reason as {@link #grantRole} -- see its javadoc.
     */
    @DeleteMapping("/{username}/roles/{role}")
    public ResponseEntity<Map<String, Object>> revokeRole(
            @PathVariable String tenantId, @PathVariable String username, @PathVariable String role,
            HttpServletRequest httpRequest
    ) {
        ExecutionContext requester = requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();
        try (Connection connection = dataSource.getConnection()) {
            Object userId = findUserId(connection, tenantId, username);
            if (userId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
            }
            int deleted;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM identity_user_roles WHERE user_id = ? AND tenant_id = ? AND role_id = "
                            + "(SELECT id FROM identity_roles WHERE name = ? AND tenant_id = ?)")) {
                ps.setObject(1, UUID.fromString(String.valueOf(userId)));
                ps.setString(2, tenantId);
                ps.setString(3, role);
                ps.setString(4, tenantId);
                deleted = ps.executeUpdate();
            }
            if (deleted == 0) {
                return ResponseEntity.status(404).body(Map.of("error", "role_not_assigned"));
            }
            auditRoleChange(requester, tenantId, username, role, "role.revoke");
            return ResponseEntity.ok(Map.of("ok", true, "username", username, "role", role));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "revoke_role_failed");
        }
    }

    public record PermissionOverrideRequest(String permission) {
    }

    /**
     * Move 14 Phase C item C2 (RC-B3): the read side of the runtime permission-subset override --
     * what an admin has explicitly bound for this (user, role) pair, if anything. Empty list means
     * "no override configured, the role's full declared ceiling applies" (never ambiguous with "every
     * permission explicitly re-granted" -- that state cannot be produced through this API at all,
     * since {@link #grantPermissionOverride} rejects any permission outside the ceiling).
     */
    @GetMapping("/{username}/roles/{role}/permissions")
    public ResponseEntity<Map<String, Object>> listPermissionOverrides(
            @PathVariable String tenantId, @PathVariable String username, @PathVariable String role,
            HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        CompiledRole declaredRole = findDeclaredRole(role);
        if (declaredRole == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "role_not_declared_by_model"));
        }
        DataSource dataSource = requireDataSource();
        try (Connection connection = dataSource.getConnection()) {
            UUID userRoleId = findUserRoleId(connection, tenantId, username, declaredRole.name());
            if (userRoleId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "role_not_assigned_to_user"));
            }
            List<String> permissions = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT permission FROM identity_user_role_permissions "
                            + "WHERE user_role_id = ? AND tenant_id = ? ORDER BY permission")) {
                ps.setObject(1, userRoleId);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        permissions.add(rs.getString(1));
                    }
                }
            }
            return ResponseEntity.ok(Map.of(
                    "username", username, "role", declaredRole.name(),
                    "declaredCeiling", declaredRole.grants(),
                    "overridePermissions", permissions,
                    "restricted", !permissions.isEmpty()));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "list_permission_overrides_failed");
        }
    }

    /**
     * Move 14 Phase C item C2 (RC-B3): binds ONE permission, within the role's declared ceiling, to a
     * SPECIFIC user's assignment of that role -- the runtime-mutable narrowing the plan calls for.
     * "An admin may grant any subset at runtime; never anything outside" is enforced HERE structurally
     * (400 if the requested permission is not a member of {@code declaredRole.grants()}), and again,
     * independently, at read time by {@link RolePermissions}'s ceiling intersection -- so even a row
     * that reached the table some other way (a hand edit, a downgraded model) can never grant more
     * than the model itself declares that role may hold.
     *
     * <p>Requires the target user to already HOLD the role ({@link #grantRole}) -- a permission
     * override narrows an existing assignment, it does not imply or create one.</p>
     */
    @PostMapping("/{username}/roles/{role}/permissions")
    public ResponseEntity<Map<String, Object>> grantPermissionOverride(
            @PathVariable String tenantId, @PathVariable String username, @PathVariable String role,
            @RequestBody PermissionOverrideRequest request, HttpServletRequest httpRequest
    ) {
        ExecutionContext requester = requireSuperUser(httpRequest);
        CompiledRole declaredRole = findDeclaredRole(role);
        if (declaredRole == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "role_not_declared_by_model"));
        }
        String requestedPermission = request == null ? null : request.permission();
        Permission permission = toRecognizedPermission(requestedPermission);
        if (permission == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "not_a_recognized_permission",
                    "recognizedPermissions", java.util.Arrays.stream(Permission.values()).map(Enum::name).toList()));
        }
        if (!declaredRoleGrants(declaredRole, permission)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "permission_outside_role_ceiling",
                    "role", declaredRole.name(),
                    "declaredCeiling", declaredRole.grants()));
        }
        DataSource dataSource = requireDataSource();
        try (Connection connection = dataSource.getConnection()) {
            UUID userRoleId = findUserRoleId(connection, tenantId, username, declaredRole.name());
            if (userRoleId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "role_not_assigned_to_user"));
            }
            if (!overrideRowExists(connection, tenantId, userRoleId, permission.name())) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO identity_user_role_permissions (id, user_role_id, permission, tenant_id) "
                                + "VALUES (?, ?, ?, ?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, userRoleId);
                    ps.setString(3, permission.name());
                    ps.setString(4, tenantId);
                    ps.executeUpdate();
                }
            }
            auditPermissionOverrideChange(requester, tenantId, username, declaredRole.name(), permission.name(),
                    "permission_override.grant");
            return ResponseEntity.ok(Map.of(
                    "ok", true, "username", username, "role", declaredRole.name(), "permission", permission.name()));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "grant_permission_override_failed");
        }
    }

    /**
     * Move 14 Phase C item C2 (RC-B3): removes one bound permission. Takes effect on the actor's very
     * next request with the SAME token -- {@link com.npdev.adapters.authz.defaultpolicy
     * .DefaultExecutionAuthorizationPolicy} re-derives overrides fresh from this same table on every
     * permission check, never caching, mirroring {@link #revokeRole}'s already-verified freshness.
     * Revoking the LAST bound permission does not restore the full ceiling implicitly in some other
     * state -- it simply leaves zero override rows, which {@code RolePermissions} already treats as
     * "no restriction" (the same as never having configured one).
     */
    @DeleteMapping("/{username}/roles/{role}/permissions/{permission}")
    public ResponseEntity<Map<String, Object>> revokePermissionOverride(
            @PathVariable String tenantId, @PathVariable String username, @PathVariable String role,
            @PathVariable String permission, HttpServletRequest httpRequest
    ) {
        ExecutionContext requester = requireSuperUser(httpRequest);
        CompiledRole declaredRole = findDeclaredRole(role);
        if (declaredRole == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "role_not_declared_by_model"));
        }
        DataSource dataSource = requireDataSource();
        try (Connection connection = dataSource.getConnection()) {
            UUID userRoleId = findUserRoleId(connection, tenantId, username, declaredRole.name());
            if (userRoleId == null) {
                return ResponseEntity.status(404).body(Map.of("error", "role_not_assigned_to_user"));
            }
            int deleted;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM identity_user_role_permissions "
                            + "WHERE user_role_id = ? AND permission = ? AND tenant_id = ?")) {
                ps.setObject(1, userRoleId);
                ps.setString(2, permission.trim().toUpperCase(java.util.Locale.ROOT));
                ps.setString(3, tenantId);
                deleted = ps.executeUpdate();
            }
            if (deleted == 0) {
                return ResponseEntity.status(404).body(Map.of("error", "override_not_found"));
            }
            auditPermissionOverrideChange(requester, tenantId, username, declaredRole.name(),
                    permission.trim().toUpperCase(java.util.Locale.ROOT), "permission_override.revoke");
            return ResponseEntity.ok(Map.of(
                    "ok", true, "username", username, "role", declaredRole.name(), "permission", permission));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "revoke_permission_override_failed");
        }
    }

    private static Permission toRecognizedPermission(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        try {
            return Permission.valueOf(requested.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notRecognized) {
            return null;
        }
    }

    private static boolean declaredRoleGrants(CompiledRole declaredRole, Permission permission) {
        for (String grant : declaredRole.grants()) {
            if (grant != null && permission.name().equalsIgnoreCase(grant.trim())) {
                return true;
            }
        }
        return false;
    }

    private UUID findUserRoleId(Connection connection, String tenantId, String username, String roleName)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ur.id FROM identity_user_roles ur "
                        + "JOIN identity_users u ON u.id = ur.user_id "
                        + "JOIN identity_roles r ON r.id = ur.role_id "
                        + "WHERE u.username = ? AND u.tenant_id = ? AND ur.tenant_id = ? "
                        + "AND r.name = ? AND r.tenant_id = ?")) {
            ps.setString(1, username);
            ps.setString(2, tenantId);
            ps.setString(3, tenantId);
            ps.setString(4, roleName);
            ps.setString(5, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (UUID) rs.getObject(1) : null;
            }
        }
    }

    private boolean overrideRowExists(Connection connection, String tenantId, UUID userRoleId, String permissionName)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM identity_user_role_permissions WHERE user_role_id = ? AND permission = ? AND tenant_id = ?")) {
            ps.setObject(1, userRoleId);
            ps.setString(2, permissionName);
            ps.setString(3, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void auditPermissionOverrideChange(
            ExecutionContext requester, String tenantId, String username, String role, String permission, String action) {
        auditLogStore.append(AuditRecord.create(
                tenantId,
                requester.actorId(),
                requester.roles(),
                action,
                "identity_user_role_permission",
                username + ":" + role + ":" + permission,
                "OK",
                null,
                Map.of("role", role, "permission", permission, "targetUser", username),
                Map.of()
        ));
    }

    private CompiledRole findDeclaredRole(String requestedRole) {
        String normalized = RolePermissions.normalizeRoleName(requestedRole);
        if (normalized == null) {
            return null;
        }
        for (CompiledRole role : compiledModel.getRoles()) {
            if (normalized.equals(RolePermissions.normalizeRoleName(role.name()))) {
                return role;
            }
        }
        return null;
    }

    /** Wave 3 (RC-B2): "audit every grant and revoke" is a DoD line item, not a nice-to-have -- this
     *  is the permission trail an operator reviews after the fact via {@code READ_AUDIT}. */
    private void auditRoleChange(ExecutionContext requester, String tenantId, String username, String role, String action) {
        auditLogStore.append(AuditRecord.create(
                tenantId,
                requester.actorId(),
                requester.roles(),
                action,
                "identity_user_role",
                username + ":" + role,
                "OK",
                null,
                Map.of("role", role, "targetUser", username),
                Map.of()
        ));
    }

    private void notifyPasswordChanged(Connection connection, String tenantId, String username) {
        if (!capabilityRegistry.has("mail")) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT email FROM " + userTable + " WHERE tenant_id = ? AND " + usernameColumn + " = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                String email = rs.getString(1);
                if (email == null || email.isBlank()) {
                    return;
                }
                String adapterId = capabilityRegistry.debugDefaultAdapterId("mail");
                CapabilityCall call = new CapabilityCall(
                        "mail", "EmailCapability", adapterId, "send",
                        List.of(
                                email,
                                "Your password was changed",
                                "An administrator reset the password for account '" + username
                                        + "'. If you did not expect this, contact your administrator."
                        )
                );
                capabilityDispatcher.invoke(call, Map.of());
            }
        } catch (Exception ignored) {
            // Best-effort -- the reset itself already succeeded.
        }
    }

    private int bumpTokenVersion(Connection connection, String tenantId, String username) {
        String sql = "UPDATE " + userTable + " SET token_version = COALESCE(token_version, 0) + 1"
                + " WHERE tenant_id = ? AND " + usernameColumn + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, username);
            return ps.executeUpdate();
        } catch (SQLException exception) {
            // REG-39: a schema mismatch (stale built-in-pack copy) is not the same kind of failure as
            // an ordinary "0 rows updated" -- let it propagate distinctly so callers don't mistake a
            // broken schema for a nonexistent user.
            if (SqlSchemaErrors.isSchemaMismatch(exception)) {
                throw new IdentityPackSchemaException(exception);
            }
            return 0;
        }
    }

    private Object findUserId(Connection connection, String tenantId, String username) throws Exception {
        String sql = "SELECT " + userIdColumn + " FROM " + userTable
                + " WHERE tenant_id = ? AND " + usernameColumn + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    private List<String> rolesOf(Connection connection, String userId, String tenantId) {
        List<String> roles = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.name FROM identity_user_roles ur JOIN identity_roles r ON r.id = ur.role_id"
                        + " WHERE ur.user_id = ? AND ur.tenant_id = ? ORDER BY r.name")) {
            ps.setObject(1, UUID.fromString(userId));
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
        } catch (Exception exception) {
            // identity-pack role tables absent, or user table keys aren't UUIDs -- roles just unknown
        }
        return roles;
    }

    private boolean hasCredential(Connection connection, String userId, String tenantId) {
        String sql = "SELECT COUNT(*) FROM " + credentialTable
                + " WHERE " + credentialUserIdColumn + " = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            Object key;
            try {
                key = UUID.fromString(userId);
            } catch (IllegalArgumentException notUuid) {
                key = userId;
            }
            ps.setObject(1, key);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        } catch (Exception exception) {
            return false;
        }
    }

    private ExecutionContext requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context;
    }

    private DataSource requireDataSource() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ControlPanel unavailable in InMemory mode -- requires a physical database "
                            + "(H2Local/H2Server/Postgres).");
        }
        return dataSource;
    }
}
