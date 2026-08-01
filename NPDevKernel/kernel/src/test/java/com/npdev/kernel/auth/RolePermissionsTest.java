package com.npdev.kernel.auth;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
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

    /**
     * Wave 3 (RC-B1): before the model-aware overload existed, an app-declared role like
     * "WAREHOUSE_MANAGER" was not one of the three built-in {@link Role} constants, so
     * {@code RolePermissions.hasPermission(context, permission)} silently granted nothing for it --
     * this is the GREEN proof the fix works. Also proves the app-declared role name lookup is
     * case-insensitive (declared "WAREHOUSE_MANAGER", context carries lower-case "warehouse_manager"
     * -- {@link ExecutionContext} itself upper-cases every role string it stores).
     */
    @Test
    void appDeclaredRoleGrantsItsDeclaredPermissions() {
        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("warehouse_manager"));
        Map<String, Set<Permission>> appDeclaredRoles = Map.of(
                "WAREHOUSE_MANAGER", Set.of(Permission.EXECUTE_FLOW, Permission.READ_EXECUTIONS));

        assertTrue(RolePermissions.hasPermission(warehouseManager, Permission.EXECUTE_FLOW, appDeclaredRoles));
        assertTrue(RolePermissions.hasPermission(warehouseManager, Permission.READ_EXECUTIONS, appDeclaredRoles));
        assertFalse(RolePermissions.hasPermission(warehouseManager, Permission.READ_AUDIT, appDeclaredRoles));
    }

    /**
     * Wave 3 (RC-B1): a role string that matches neither a built-in {@link Role} nor an
     * app-declared role is denied, not thrown -- the X0 rule here is "logged", not "loud failure",
     * because an unrecognized role arriving on a real request (a stale JWT, a renamed role) must not
     * crash the request; {@link RolePermissions} logs the denial (see its own javadoc) instead.
     */
    @Test
    void undeclaredRoleIsDeniedNotThrown() {
        ExecutionContext ghost = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("GHOST_ROLE"));
        assertFalse(RolePermissions.hasPermission(ghost, Permission.EXECUTE_FLOW, Map.of(
                "WAREHOUSE_MANAGER", Set.of(Permission.EXECUTE_FLOW))));
    }

    /**
     * Wave 3 (RC-B1) regression proof: an app declaring no roles at all (empty map, exactly what
     * every pre-existing app has) behaves identically to the original two-arg
     * {@link RolePermissions#hasPermission(ExecutionContext, Permission)} the three tests above this
     * one exercise -- the built-in USER/OPERATOR/ADMIN trio is completely unaffected by this feature.
     */
    @Test
    void emptyAppDeclaredRolesBehavesLikeBuiltInRolesOnly() {
        ExecutionContext user = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("USER"));
        assertTrue(RolePermissions.hasPermission(user, Permission.EXECUTE_FLOW, Map.of()));
        assertFalse(RolePermissions.hasPermission(user, Permission.READ_AUDIT, Map.of()));

        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));
        for (Permission permission : Permission.values()) {
            assertTrue(RolePermissions.hasPermission(admin, permission, Map.of()));
        }
    }
}
