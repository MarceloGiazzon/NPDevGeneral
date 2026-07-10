package com.finalexec.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

/**
 * Creates the first admin user on a fresh database, replacing manual SQL-shell seeding.
 * Self-disabling: only succeeds while {@code identity_users} is empty for the target tenant, so it
 * cannot be used to escalate privileges once a tenant has any user -- that check is the entire
 * security model, mirroring the one-time nature of the manual seed it replaces.
 *
 * <p>Like {@link LoginController}, the credential table/column names are configurable so this works
 * for any app bonding its own credential concept to {@code identity::User} the same way. Any
 * additional required columns on that app's credential table (beyond user id + password hash) are
 * out of scope here -- this only covers the identity pack + minimal credential row.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class BootstrapAdminController {

    private static final String ADMIN_ROLE_NAME = "ADMIN";

    private final DataSource dataSource;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;

    public BootstrapAdminController(
            DataSource dataSource,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn
    ) {
        this.dataSource = dataSource;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
    }

    public record BootstrapRequest(String username, String displayName, String password, String tenantId) {
    }

    @PostMapping("/api/auth/bootstrap-admin")
    public ResponseEntity<Map<String, Object>> bootstrapAdmin(@RequestBody BootstrapRequest request) {
        String tenantId = (request.tenantId() == null || request.tenantId().isBlank()) ? "dev" : request.tenantId().trim();
        String username = request.username() == null ? null : request.username().trim();
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        String password = request.password();

        if (username == null || username.isBlank() || displayName == null || displayName.isBlank()
                || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field"));
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (IdentityProvisioning.countUsersInTenant(connection, tenantId) > 0) {
                    connection.rollback();
                    return ResponseEntity.status(409).body(Map.of("error", "tenant_already_bootstrapped"));
                }

                UUID userId = UUID.randomUUID();
                IdentityProvisioning.insertIdentityUser(connection, userId, username, displayName, tenantId);

                UUID roleId = IdentityProvisioning.findOrCreateRole(
                        connection, tenantId, ADMIN_ROLE_NAME, "Bootstrapped administrator role");
                IdentityProvisioning.insertUserRole(connection, userId, roleId, tenantId);

                IdentityProvisioning.insertCredential(
                        connection, credentialTable, credentialUserIdColumn, credentialPasswordColumn,
                        UUID.randomUUID(), userId, password, tenantId,
                        null, null, null, null);

                connection.commit();
                return ResponseEntity.status(201).body(Map.of("username", username, "tenantId", tenantId));
            } catch (Exception exception) {
                connection.rollback();
                return ResponseEntity.status(500).body(Map.of("error", "bootstrap_failed"));
            }
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "bootstrap_failed"));
        }
    }
}
