package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

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
 *
 * <p>REG-177: table names are caller-supplied (an {@code IdentityPackTableNames}, resolved once by
 * the caller from its own {@code CompiledModel}) instead of hardcoded literals -- the generator's
 * schema-realization SQL creates these under pack-versioned names (e.g. {@code identity_v1_users}),
 * the same defect shape REG-160/REG-170/REG-177's other sites already fixed.</p>
 */
final class IdentityPermissionOverrideLookup {

    private static final Logger LOG = Logger.getLogger(IdentityPermissionOverrideLookup.class.getName());

    private static final String OVERRIDE_QUERY_TEMPLATE = """
            SELECT r.name, p.permission
            FROM %s u
            JOIN %s ur ON ur.user_id = u.id
            JOIN %s r ON r.id = ur.role_id
            JOIN %s p ON p.user_role_id = ur.id
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
    static Map<String, Set<String>> overridesFor(
            DataSource dataSource, IdentityPackTableNames tables, String tenantId, String actorId) {
        if (dataSource == null || tables == null || actorId == null || actorId.isBlank()) {
            return Map.of();
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String query = OVERRIDE_QUERY_TEMPLATE.formatted(
                tables.usersTable(), tables.userRolesTable(), tables.rolesTable(), tables.userRolePermissionsTable());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
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
            // hasn't adopted this feature. But REG-39's rule applies here too: an infrastructure fault
            // silently resolving to "no restriction" must not ALSO be silent to the operator -- a
            // schema mismatch (identity_user_role_permissions absent, e.g. a pre-C2 app that hasn't
            // regenerated) is logged loudly, mirroring IdentityRoleLookup.tokenVersion exactly, so
            // "an admin configured a restriction and it silently never applied" is discoverable
            // instead of indistinguishable from "no restriction was ever configured."
            if (isSchemaMismatch(exception)) {
                LOG.severe("Permission-override lookup failed: identity_user_role_permissions schema "
                        + "mismatch or missing table (app not yet regenerated with the UserRolePermission "
                        + "concept?) -- falling back to 'no override configured' (full role ceiling "
                        + "applies) for tenant='" + tenantId + "', actor='" + actorId + "': "
                        + exception.getMessage());
            }
            return Map.of();
        }
    }

    private static boolean isSchemaMismatch(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("42");
    }
}
