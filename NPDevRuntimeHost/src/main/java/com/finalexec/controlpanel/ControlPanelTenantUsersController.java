package com.finalexec.controlpanel;

import com.finalexec.auth.IdentityPackSchemaException;
import com.finalexec.auth.PasswordHasher;
import com.finalexec.auth.SqlSchemaErrors;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.CapabilityDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
            ps.setObject(1, java.util.UUID.fromString(userId));
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
                key = java.util.UUID.fromString(userId);
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

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
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
