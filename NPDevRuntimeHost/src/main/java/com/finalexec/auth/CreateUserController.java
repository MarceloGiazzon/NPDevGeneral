package com.finalexec.auth;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
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
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class CreateUserController {

    private static final String DEFAULT_ROLE_NAME = "ADMIN";

    private final DataSource dataSource;
    private final RuntimeContextService runtimeContextService;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final String credentialPrimaryLinkColumn;
    private final String credentialSecondaryLinkColumn;

    public CreateUserController(
            DataSource dataSource,
            RuntimeContextService runtimeContextService,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.create-user.credential-primary-link-column:entidade_id}") String credentialPrimaryLinkColumn,
            @Value("${npdev.auth.create-user.credential-secondary-link-column:estabelecimento_padrao_id}") String credentialSecondaryLinkColumn
    ) {
        this.dataSource = dataSource;
        this.runtimeContextService = runtimeContextService;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.credentialPrimaryLinkColumn = credentialPrimaryLinkColumn;
        this.credentialSecondaryLinkColumn = credentialSecondaryLinkColumn;
    }

    public record CreateUserRequest(
            String username, String displayName, String password, String roleName,
            String primaryLinkId, String secondaryLinkId
    ) {
    }

    @PostMapping("/api/auth/create-user")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest request, HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        String tenantId = context.tenantId();

        String username = request.username() == null ? null : request.username().trim();
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        String password = request.password();
        String roleName = (request.roleName() == null || request.roleName().isBlank())
                ? DEFAULT_ROLE_NAME : request.roleName().trim().toUpperCase(java.util.Locale.ROOT);

        if (username == null || username.isBlank() || displayName == null || displayName.isBlank()
                || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field"));
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (IdentityProvisioning.usernameTaken(connection, tenantId, username)) {
                    connection.rollback();
                    return ResponseEntity.status(409).body(Map.of("error", "username_taken"));
                }

                UUID userId = UUID.randomUUID();
                IdentityProvisioning.insertIdentityUser(connection, userId, username, displayName, tenantId);

                UUID roleId = IdentityProvisioning.findOrCreateRole(
                        connection, tenantId, roleName, "Created via /api/auth/create-user");
                IdentityProvisioning.insertUserRole(connection, userId, roleId, tenantId);

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
                body.put("roleName", roleName);
                return ResponseEntity.status(201).body(body);
            } catch (Exception exception) {
                connection.rollback();
                return ResponseEntity.status(500).body(Map.of("error", "create_user_failed"));
            }
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "create_user_failed"));
        }
    }
}
