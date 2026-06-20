package com.finalexec.auth;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Makes the built-in identity pack (identity_users / identity_roles / identity_user_roles)
 * load-bearing for authorization, instead of trusting the roles encoded in the api-key / JWT
 * principal blindly.
 *
 * <p>Resolution is <b>supplement-with-fallback</b>, deliberately non-breaking: the wrapped delegate
 * resolves the base {@link ExecutionContext} (tenant + actor + claim-roles) exactly as before; then,
 * IF a matching active identity user with role assignments exists for that (tenant, actor), the
 * persisted roles become authoritative and replace the claim-roles. When the identity tables are
 * absent ({@code internal.tables=false}) or hold no matching user, the claim-roles stand — so apps
 * that don't use the identity pack are unaffected, and the very first ADMIN (who has no identity row
 * yet) can still bootstrap the identity data via the claim-role fallback.</p>
 */
public final class IdentityAwareContextResolver implements AuthenticatedContextResolver {

    private static final String ROLE_QUERY = """
            SELECT r.name
            FROM identity_users u
            JOIN identity_user_roles ur ON ur.user_id = u.id
            JOIN identity_roles r ON r.id = ur.role_id
            WHERE u.username = ? AND u.tenant_id = ? AND u.active = TRUE
              AND ur.tenant_id = ? AND r.tenant_id = ?
            """;

    private final AuthenticatedContextResolver delegate;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public IdentityAwareContextResolver(
            AuthenticatedContextResolver delegate,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dataSourceProvider = Objects.requireNonNull(dataSourceProvider, "dataSourceProvider");
    }

    @Override
    public ExecutionContext resolveFromPrincipal(Map<String, Object> claims, Map<String, String> headers) {
        ExecutionContext base = delegate.resolveFromPrincipal(claims, headers);
        Set<String> identityRoles = identityRoles(base.tenantId(), base.actorId());
        return identityRoles.isEmpty() ? base : base.withRoles(identityRoles);
    }

    private Set<String> identityRoles(String tenantId, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return Set.of();
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return Set.of();
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ROLE_QUERY)) {
            statement.setString(1, actorId);
            statement.setString(2, tenant);
            statement.setString(3, tenant);
            statement.setString(4, tenant);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> roles = new LinkedHashSet<>();
                while (resultSet.next()) {
                    String role = resultSet.getString(1);
                    if (role != null && !role.isBlank()) {
                        roles.add(role.trim());
                    }
                }
                return roles;
            }
        } catch (SQLException exception) {
            // Identity tables absent (internal.tables=false) or unreadable: fall back to claim-roles.
            return Set.of();
        }
    }
}
