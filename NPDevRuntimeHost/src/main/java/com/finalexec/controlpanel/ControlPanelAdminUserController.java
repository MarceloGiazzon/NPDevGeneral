package com.finalexec.controlpanel;

import com.finalexec.auth.IdentityProvisioning;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ControlPanel action: create an Admin User for ANY tenant (new or existing), at any time -- not
 * just at first-setup. Unlike {@code CreateUserController} (ADMIN-gated, scoped to the caller's own
 * tenant, repeatable) and {@code BootstrapAdminController} (anonymous, one-time per tenant), this is
 * SUPERUSER-ONLY and deliberately does not accept a caller-supplied role name (always creates
 * {@code ADMIN}), so this endpoint can never be used to mint a peer {@code SUPERUSER} credential --
 * that only ever happens via {@link SuperUserBootstrapper} / {@code CredentialRegistryService}.
 */
@RestController
@RequestMapping("/api/admin/tenant-admins")
public class ControlPanelAdminUserController {

    private static final String ADMIN_ROLE_NAME = "ADMIN";

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final RuntimeContextService runtimeContextService;
    // REG-177/REG-179 fix: resolved with the GRACEFUL tryResolve (not resolve), and only unwrapped
    // per-request (see requireIdentityTables()) -- this bean is registered unconditionally in every
    // generated app regardless of whether it composes the identity pack, so the eager, throwing
    // IdentityPackTableNames.resolve(...) this used to call here crashed Spring context startup for
    // any app without one (confirmed live: BeanCreationException -> IllegalStateException on a
    // generated sample with no identity::User concept at all).
    private final Optional<IdentityPackTableNames> identityTables;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;

    public ControlPanelAdminUserController(
            ObjectProvider<DataSource> dataSourceProvider,
            RuntimeContextService runtimeContextService,
            CompiledModel compiledModel,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.runtimeContextService = runtimeContextService;
        this.identityTables = IdentityPackTableNames.tryResolve(compiledModel);
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
    }

    public record CreateTenantAdminRequest(String tenantId, String username, String displayName, String password, String email) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody CreateTenantAdminRequest request, HttpServletRequest httpRequest
    ) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }

        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ControlPanel unavailable in InMemory mode -- requires a physical database "
                            + "(H2Local/H2Server/Postgres).");
        }
        if (identityTables.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ControlPanel unavailable -- this app does not compose the identity pack.");
        }
        // Shadows the Optional field with the unwrapped value for the rest of this method.
        IdentityPackTableNames identityTables = this.identityTables.get();

        String tenantId = request.tenantId() == null ? null : request.tenantId().trim();
        String username = request.username() == null ? null : request.username().trim();
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        String password = request.password();

        if (tenantId == null || tenantId.isBlank() || username == null || username.isBlank()
                || displayName == null || displayName.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field"));
        }

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

                UUID roleId = IdentityProvisioning.findOrCreateRole(
                        connection, identityTables, tenantId, ADMIN_ROLE_NAME,
                        "Created via ControlPanel /api/admin/tenant-admins");
                IdentityProvisioning.insertUserRole(connection, identityTables, userId, roleId, tenantId);

                IdentityProvisioning.insertCredential(
                        connection, credentialTable, credentialUserIdColumn, credentialPasswordColumn,
                        UUID.randomUUID(), userId, password, tenantId,
                        null, null, null, null);

                connection.commit();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("userId", userId.toString());
                body.put("username", username);
                body.put("tenantId", tenantId);
                body.put("roleName", ADMIN_ROLE_NAME);
                return ResponseEntity.status(201).body(body);
            } catch (Exception exception) {
                connection.rollback();
                return ResponseEntity.status(500).body(Map.of("error", "create_tenant_admin_failed"));
            }
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "create_tenant_admin_failed"));
        }
    }
}
