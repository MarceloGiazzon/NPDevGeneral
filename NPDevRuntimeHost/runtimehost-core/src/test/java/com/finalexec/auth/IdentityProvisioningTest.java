package com.finalexec.auth;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QUAL-41/EXT-10: targeted first-run coverage for {@link IdentityProvisioning}, the shared JDBC
 * step library behind every first-account-on-a-fresh-database path in RuntimeHost
 * ({@code BootstrapAdminController}, {@code CreateUserController}, and the ControlPanel's
 * tenant-admin actions -- see this class's own javadoc). Not itself measured by
 * {@code check-coverage-ratchet.py}'s RuntimeHost JaCoCo report (runtimehost-core is a separate
 * Gradle module with no jacoco plugin applied -- confirmed by this exact baseline's own
 * 2026-09-13 note), but real regression protection for the class REG-170/REG-177 both patched:
 * table names now come from {@link IdentityPackTableNames} instead of a pre-versioning literal,
 * and {@code ensureTenantRegistered} is the belt-and-suspenders real-time companion to
 * {@code TenantAutoRegistrationRunner}'s boot-time sweep.
 */
class IdentityProvisioningTest {

    private static final String TENANT = "acme";

    private Connection connection;
    private IdentityPackTableNames tables;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url);
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "display_name VARCHAR(200), email VARCHAR(200), active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), "
                    + "description VARCHAR(400), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, "
                    + "tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE credential_tbl (id UUID PRIMARY KEY, user_id UUID, password_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120), entidade_id UUID, estabelecimento_id UUID)");
            s.execute("CREATE TABLE npdev_tenant (tenant_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(200), "
                    + "status VARCHAR(50), created_at_ms BIGINT)");
        }
        tables = new IdentityPackTableNames(
                "identity_users", "identity_roles", "identity_user_roles", "identity_user_role_permissions");
    }

    @Test
    void countUsersInTenantCountsOnlyMatchingTenant() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        IdentityProvisioning.insertIdentityUser(connection, tables, a, "ada", "Ada", TENANT);
        IdentityProvisioning.insertIdentityUser(connection, tables, b, "bob", "Bob", "other-tenant");

        assertEquals(1, IdentityProvisioning.countUsersInTenant(connection, tables, TENANT));
        assertEquals(0, IdentityProvisioning.countUsersInTenant(connection, tables, "nobody-tenant"));
    }

    @Test
    void usernameTakenTrueWhenPresentFalseOtherwise() throws Exception {
        IdentityProvisioning.insertIdentityUser(connection, tables, UUID.randomUUID(), "ada", "Ada", TENANT);

        assertTrue(IdentityProvisioning.usernameTaken(connection, tables, TENANT, "ada"));
        assertFalse(IdentityProvisioning.usernameTaken(connection, tables, TENANT, "ghost"));
        // Same username, different tenant -- must not collide (row-level tenant isolation).
        assertFalse(IdentityProvisioning.usernameTaken(connection, tables, "other-tenant", "ada"));
    }

    @Test
    void insertIdentityUserWithoutEmailStoresNullEmail() throws Exception {
        UUID id = UUID.randomUUID();
        IdentityProvisioning.insertIdentityUser(connection, tables, id, "ada", "Ada", TENANT);

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT email, active FROM identity_users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getString(1));
                assertTrue(rs.getBoolean(2), "a newly provisioned identity user must be active");
            }
        }
    }

    @Test
    void insertIdentityUserWithBlankEmailIsNormalizedToNull() throws Exception {
        UUID id = UUID.randomUUID();
        IdentityProvisioning.insertIdentityUser(connection, tables, id, "ada", "Ada", "   ", TENANT);

        try (PreparedStatement ps = connection.prepareStatement("SELECT email FROM identity_users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getString(1));
            }
        }
    }

    @Test
    void insertIdentityUserAutoRegistersItsTenant() throws Exception {
        IdentityProvisioning.insertIdentityUser(
                connection, tables, UUID.randomUUID(), "ada", "Ada", "ada@example.test", TENANT);

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT display_name, status FROM npdev_tenant WHERE tenant_id = ?")) {
            ps.setString(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "provisioning a tenant's first user must auto-register the tenant");
                assertEquals(TENANT, rs.getString(1));
                assertEquals("ACTIVE", rs.getString(2));
            }
        }
    }

    @Test
    void ensureTenantRegisteredSkipsTheReservedDefaultSentinel() throws Exception {
        IdentityProvisioning.ensureTenantRegistered(connection, "default");
        IdentityProvisioning.ensureTenantRegistered(connection, "DEFAULT");
        IdentityProvisioning.ensureTenantRegistered(connection, null);
        IdentityProvisioning.ensureTenantRegistered(connection, "  ");

        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM npdev_tenant")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "the reserved 'default' tenant (any casing) and blank/null must never "
                    + "be written to npdev_tenant");
        }
    }

    @Test
    void ensureTenantRegisteredIsIdempotentAndRaceSafe() throws Exception {
        IdentityProvisioning.ensureTenantRegistered(connection, TENANT);
        IdentityProvisioning.ensureTenantRegistered(connection, TENANT);
        IdentityProvisioning.ensureTenantRegistered(connection, TENANT);

        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM npdev_tenant WHERE tenant_id = '" + TENANT + "'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "calling ensureTenantRegistered repeatedly must not duplicate the row");
        }
    }

    @Test
    void findOrCreateRoleCreatesThenReusesTheSameRole() throws Exception {
        UUID first = IdentityProvisioning.findOrCreateRole(connection, tables, TENANT, "ADMIN", "Administrator");
        UUID second = IdentityProvisioning.findOrCreateRole(connection, tables, TENANT, "ADMIN", "Administrator (again)");

        assertEquals(first, second, "a role name already present in this tenant must be reused, not duplicated");

        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM identity_roles WHERE name = 'ADMIN' AND tenant_id = '" + TENANT + "'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void findOrCreateRoleIsScopedPerTenant() throws Exception {
        UUID acmeAdmin = IdentityProvisioning.findOrCreateRole(connection, tables, TENANT, "ADMIN", "d");
        UUID otherAdmin = IdentityProvisioning.findOrCreateRole(connection, tables, "other-tenant", "ADMIN", "d");

        assertFalse(acmeAdmin.equals(otherAdmin), "the same role name in two tenants must be two distinct rows");
    }

    @Test
    void insertUserRoleLinksUserAndRole() throws Exception {
        UUID userId = UUID.randomUUID();
        IdentityProvisioning.insertIdentityUser(connection, tables, userId, "ada", "Ada", TENANT);
        UUID roleId = IdentityProvisioning.findOrCreateRole(connection, tables, TENANT, "ADMIN", "d");

        IdentityProvisioning.insertUserRole(connection, tables, userId, roleId, TENANT);

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM identity_user_roles WHERE user_id = ? AND role_id = ? AND tenant_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, roleId);
            ps.setString(3, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void insertCredentialOmitsBlankOrNullLinkColumnsEntirely() throws Exception {
        UUID userId = UUID.randomUUID();
        IdentityProvisioning.insertCredential(
                connection, "credential_tbl", "user_id", "password_hash",
                UUID.randomUUID(), userId, "s3cret!", TENANT,
                "entidade_id", null,
                "estabelecimento_id", "   ");

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entidade_id, estabelecimento_id FROM credential_tbl WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getObject(1));
                assertEquals(null, rs.getObject(2));
            }
        }
    }

    @Test
    void insertCredentialPopulatesLinkColumnsWhenSupplied() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID entidadeId = UUID.randomUUID();
        IdentityProvisioning.insertCredential(
                connection, "credential_tbl", "user_id", "password_hash",
                UUID.randomUUID(), userId, "s3cret!", TENANT,
                "entidade_id", entidadeId.toString(),
                "estabelecimento_id", null);

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entidade_id, password_hash FROM credential_tbl WHERE user_id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(entidadeId, rs.getObject(1));
                assertNotNull(rs.getString(2));
                assertTrue(PasswordHasher.verify("s3cret!", rs.getString(2)));
            }
        }
    }
}
