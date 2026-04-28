package com.npdev.kernel.auth;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePermissionsTest {

    @Test
    void userRoleHasOnlyBasePermissions() {
        ExecutionContext user = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("USER"));
        assertTrue(RolePermissions.hasPermission(user, Permission.EXECUTE_FLOW));
        assertTrue(RolePermissions.hasPermission(user, Permission.READ_EXECUTIONS));
        assertTrue(RolePermissions.hasPermission(user, Permission.READ_TRACES));
        assertFalse(RolePermissions.hasPermission(user, Permission.RESUME_EXECUTIONS));
        assertFalse(RolePermissions.hasPermission(user, Permission.READ_AUDIT));
        assertFalse(RolePermissions.hasPermission(user, Permission.READ_ADMIN_HEALTH));
    }

    @Test
    void operatorRoleHasOperationalPermissionsButNoAdminPermissions() {
        ExecutionContext operator = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        assertTrue(RolePermissions.hasPermission(operator, Permission.EXECUTE_FLOW));
        assertTrue(RolePermissions.hasPermission(operator, Permission.RESUME_EXECUTIONS));
        assertTrue(RolePermissions.hasPermission(operator, Permission.PUBLISH_EVENTS));
        assertTrue(RolePermissions.hasPermission(operator, Permission.READ_FAILURES));
        assertFalse(RolePermissions.hasPermission(operator, Permission.READ_AUDIT));
        assertFalse(RolePermissions.hasPermission(operator, Permission.READ_ADMIN_HEALTH));
    }

    @Test
    void adminRoleHasAllPermissions() {
        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));
        for (Permission permission : Permission.values()) {
            assertTrue(RolePermissions.hasPermission(admin, permission));
        }
    }
}
