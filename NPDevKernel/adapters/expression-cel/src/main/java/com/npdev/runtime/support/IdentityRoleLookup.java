package com.npdev.runtime.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves the roles assigned to an actor in the built-in identity pack
 * (identity_users / identity_roles / identity_user_roles), tenant-scoped.
 *
 * <p>Shared so that BOTH context-resolution paths apply identity-backed roles uniformly: the
 * RuntimeHost {@code IdentityAwareContextResolver} (admin / business-UI controllers) and
 * {@link GeneratedCrudRuntimeSupport}'s own per-request context (generated business CRUD permission
 * checks). Returns an empty set -- meaning "no identity backing, keep the caller's claim-roles" --
 * when the actor is unknown, the user is inactive, the tenant doesn't match, or the identity tables
 * are absent ({@code internal.tables=false}). Never throws.</p>
 */
public final class IdentityRoleLookup {

    private static final String ROLE_QUERY = """
            SELECT r.name
            FROM identity_users u
            JOIN identity_user_roles ur ON ur.user_id = u.id
            JOIN identity_roles r ON r.id = ur.role_id
            WHERE u.username = ? AND u.tenant_id = ? AND u.active = TRUE
              AND ur.tenant_id = ? AND r.tenant_id = ?
            """;

    private IdentityRoleLookup() {
    }

    public static Set<String> rolesFor(DataSource dataSource, String tenantId, String actorId) {
        if (dataSource == null || actorId == null || actorId.isBlank()) {
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
            return Set.of();
        }
    }
}
