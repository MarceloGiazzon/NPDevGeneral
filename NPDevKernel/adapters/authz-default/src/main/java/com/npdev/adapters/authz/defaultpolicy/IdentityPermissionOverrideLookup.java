package com.npdev.adapters.authz.defaultpolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Move 14 Phase C item C2 (RC-B3): resolves any runtime-bound permission-subset override the
 * identity pack holds for an actor's role assignments (identity_user_role_permissions, added by the
 * {@code UserRolePermission} concept), tenant- and actor-scoped, fresh on every call -- the same
 * "never cache, re-derive every request" contract {@code IdentityRoleLookup} (RuntimeHost /
 * expression-cel) already established for roles and {@code token_version}. Deliberately reimplemented
 * here rather than shared with that class: this module ({@code authz-default}) has no dependency on
 * {@code expression-cel} and this query is self-contained, so a new cross-module dependency for one
 * lookup was not worth adding.
 *
 * <p>A role with NO override rows is simply absent from the returned map -- callers must treat
 * absence as "no restriction, the role's full declared ceiling applies" and presence as "restrict to
 * exactly this permission set." This lookup only ever reports what an admin explicitly bound; it does
 * NOT itself enforce the ceiling -- {@code RolePermissions} intersects the returned set against the
 * role's declared {@code grants} at the point of decision, so a row that somehow named a permission
 * outside the ceiling (a bug, a hand-edited row, a downgraded model) can never grant more than the
 * ceiling allows no matter what this lookup returns.</p>
 */
final class IdentityPermissionOverrideLookup {

    private static final String OVERRIDE_QUERY = """
            SELECT r.name, p.permission
            FROM identity_users u
            JOIN identity_user_roles ur ON ur.user_id = u.id
            JOIN identity_roles r ON r.id = ur.role_id
            JOIN identity_user_role_permissions p ON p.user_role_id = ur.id
            WHERE u.username = ? AND u.tenant_id = ? AND u.active = TRUE
              AND ur.tenant_id = ? AND r.tenant_id = ? AND p.tenant_id = ?
            """;

    private IdentityPermissionOverrideLookup() {
    }

    /**
     * Returns role name (as stored, not normalized) to the set of permission-name strings explicitly
     * bound for that role assignment. Empty map -- never null -- when the actor is unknown, inactive,
     * the tenant doesn't match, or the identity/override tables are absent (an app that never adopted
     * this feature, or is mid-migration, behaves exactly as it did before C2 existed: no restriction).
     * Never throws.
     */
    static Map<String, Set<String>> overridesFor(DataSource dataSource, String tenantId, String actorId) {
        if (dataSource == null || actorId == null || actorId.isBlank()) {
            return Map.of();
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(OVERRIDE_QUERY)) {
            statement.setString(1, actorId);
            statement.setString(2, tenant);
            statement.setString(3, tenant);
            statement.setString(4, tenant);
            statement.setString(5, tenant);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, Set<String>> overrides = new LinkedHashMap<>();
                while (resultSet.next()) {
                    String role = resultSet.getString(1);
                    String permission = resultSet.getString(2);
                    if (role == null || role.isBlank() || permission == null || permission.isBlank()) {
                        continue;
                    }
                    overrides.computeIfAbsent(role.trim(), key -> new LinkedHashSet<>()).add(permission.trim());
                }
                return overrides;
            }
        } catch (SQLException exception) {
            // Same fail-open contract as IdentityRoleLookup.rolesFor -- a schema mismatch or absent
            // table must not block flow execution; the caller falls back to "no override" (full
            // ceiling), which is never MORE permissive than today's pre-C2 behavior for any app that
            // hasn't adopted this feature.
            return Map.of();
        }
    }
}
