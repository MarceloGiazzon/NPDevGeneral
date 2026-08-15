package com.finalexec.auth;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared JDBC identity-provisioning steps (identity pack's User/Role/UserRole tables + an app's own
 * credential table) used by every controller that mints a login against the identity pack:
 * {@link BootstrapAdminController}, {@link CreateUserController}, and the ControlPanel's
 * cross-tenant admin-user/tenant-users controllers. Callers own the transaction (commit/rollback).
 *
 * <p>Physical table names are resolved from the compiled model ({@link IdentityPackTableNames#resolve})
 * rather than hardcoded, because they are pack-versioned (e.g. {@code identity_v1_users} today) and
 * the generator, not this class, owns that naming scheme -- see REG-160 (the identical fix, applied
 * first to {@code WorkspaceMenuSeeder}) and REG-170 (this class's own instance of the same defect
 * shape). {@code IdentityPackTableNames} itself lives in {@code NPDevContract/dsl}, not here --
 * REG-177 found the identical defect in two {@code NPDevKernel} adapters
 * ({@code IdentityRoleLookup}, {@code IdentityPermissionOverrideLookup}) that cannot depend on
 * {@code NPDevRuntimeHost}, so the shared type moved to the one module every caller can reach.</p>
 */
public final class IdentityProvisioning {

    private IdentityProvisioning() {
    }

    public static int countUsersInTenant(Connection connection, IdentityPackTableNames tables, String tenantId)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + tables.usersTable() + " WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static boolean usernameTaken(Connection connection, IdentityPackTableNames tables, String tenantId,
                                         String username) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + tables.usersTable() + " WHERE tenant_id = ? AND username = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    public static void insertIdentityUser(Connection connection, IdentityPackTableNames tables, UUID userId,
                                           String username, String displayName, String tenantId) throws Exception {
        insertIdentityUser(connection, tables, userId, username, displayName, null, tenantId);
    }

    /**
     * LNCH-4: {@code email} is optional (self-service password reset simply can't reach a user with
     * none on file -- they fall back to an admin-forced reset in ControlPanel) but every caller
     * should pass a real value when the registration flow collected one, since it's the only way
     * {@link PasswordResetController} can find someone to email.
     */
    public static void insertIdentityUser(Connection connection, IdentityPackTableNames tables, UUID userId,
                                           String username, String displayName, String email, String tenantId)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + tables.usersTable() + " (id, username, display_name, email, active, tenant_id) "
                        + "VALUES (?, ?, ?, ?, TRUE, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, username);
            ps.setString(3, displayName);
            ps.setString(4, email == null || email.isBlank() ? null : email.trim());
            ps.setString(5, tenantId);
            ps.executeUpdate();
        }
        ensureTenantRegistered(connection, tenantId);
    }

    /**
     * Belt-and-suspenders companion to {@code TenantAutoRegistrationRunner} (which reconciles at
     * every boot): registers a tenant the moment its first identity user is provisioned, in the
     * SAME transaction, so a tenant created via a path that never calls
     * {@code TenantRegistryService.create()} directly (e.g. an anonymous first-boot
     * {@code BootstrapAdminController} signup) still shows up in the ControlPanel's workspace list
     * immediately, not just after the next restart. Skips the reserved "default" sentinel (see
     * {@code TenantRegistryService.RESERVED_DEFAULT_TENANT_ID}) and silently no-ops if the row
     * already exists (idempotent, race-safe).
     */
    public static void ensureTenantRegistered(Connection connection, String tenantId) throws Exception {
        if (tenantId == null || tenantId.isBlank()
                || "default".equals(tenantId.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        String id = tenantId.trim();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM npdev_tenant WHERE tenant_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO npdev_tenant (tenant_id, display_name, status, created_at_ms) "
                        + "VALUES (?, ?, 'ACTIVE', ?)")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException raceLost) {
            if (raceLost.getSQLState() == null || !raceLost.getSQLState().startsWith("23")) {
                throw raceLost;
            }
        }
    }

    public static UUID findOrCreateRole(Connection connection, IdentityPackTableNames tables, String tenantId,
                                         String roleName, String description) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM " + tables.rolesTable() + " WHERE name = ? AND tenant_id = ?")) {
            ps.setString(1, roleName);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return (UUID) rs.getObject(1);
                }
            }
        }

        UUID roleId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + tables.rolesTable() + " (id, name, description, tenant_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, roleId);
            ps.setString(2, roleName);
            ps.setString(3, description);
            ps.setString(4, tenantId);
            ps.executeUpdate();
        }
        return roleId;
    }

    public static void insertUserRole(Connection connection, IdentityPackTableNames tables, UUID userId, UUID roleId,
                                       String tenantId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + tables.userRolesTable() + " (id, user_id, role_id, tenant_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setString(4, tenantId);
            ps.executeUpdate();
        }
    }

    /**
     * Optional link columns are omitted from the INSERT entirely (not even NULL-bound) when their
     * id is blank, so callers with no link columns at all (e.g. {@link BootstrapAdminController})
     * can just pass two nulls for the link column names.
     */
    public static void insertCredential(
            Connection connection, String credentialTable, String userIdColumn, String passwordColumn,
            UUID credentialId, UUID userId, String password, String tenantId,
            String primaryLinkColumn, String primaryLinkId,
            String secondaryLinkColumn, String secondaryLinkId
    ) throws Exception {
        boolean hasPrimary = primaryLinkColumn != null && primaryLinkId != null && !primaryLinkId.isBlank();
        boolean hasSecondary = secondaryLinkColumn != null && secondaryLinkId != null && !secondaryLinkId.isBlank();

        StringBuilder columns = new StringBuilder("id, " + userIdColumn + ", " + passwordColumn + ", tenant_id");
        StringBuilder placeholders = new StringBuilder("?, ?, ?, ?");
        if (hasPrimary) {
            columns.append(", ").append(primaryLinkColumn);
            placeholders.append(", ?");
        }
        if (hasSecondary) {
            columns.append(", ").append(secondaryLinkColumn);
            placeholders.append(", ?");
        }

        String sql = "INSERT INTO " + credentialTable + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setObject(index++, credentialId);
            ps.setObject(index++, userId);
            ps.setString(index++, PasswordHasher.hash(password));
            ps.setString(index++, tenantId);
            if (hasPrimary) {
                ps.setObject(index++, UUID.fromString(primaryLinkId));
            }
            if (hasSecondary) {
                ps.setObject(index++, UUID.fromString(secondaryLinkId));
            }
            ps.executeUpdate();
        }
    }
}
