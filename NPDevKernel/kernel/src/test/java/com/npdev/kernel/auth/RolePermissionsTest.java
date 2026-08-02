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

    /**
     * Move 14 Phase C item C2 (RC-B3): the core safety property. An override is intersected against
     * the role's declared ceiling -- it is NOT trusted verbatim. Even though the override row here
     * names READ_AUDIT (a permission WAREHOUSE_MANAGER never declares), the actor never gains it: the
     * ceiling wins. This is what "an admin may grant any subset at runtime; never anything outside"
     * means as code, not as a promise about the write side's own validation.
     */
    @Test
    void runtimeOverrideCanNeverExceedTheDeclaredCeilingEvenIfTheRowNamesAPermissionOutsideIt() {
        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("warehouse_manager"));
        Map<String, Set<Permission>> appDeclaredRoles = Map.of(
                "WAREHOUSE_MANAGER",
                Set.of(Permission.EXECUTE_FLOW, Permission.READ_EXECUTIONS, Permission.READ_FLOW_DEFINITIONS));
        Map<String, Set<String>> overrides = Map.of(
                "WAREHOUSE_MANAGER", Set.of("EXECUTE_FLOW", "READ_AUDIT"));

        assertTrue(RolePermissions.hasPermission(
                warehouseManager, Permission.EXECUTE_FLOW, appDeclaredRoles, overrides));
        assertFalse(RolePermissions.hasPermission(
                warehouseManager, Permission.READ_AUDIT, appDeclaredRoles, overrides),
                "READ_AUDIT is outside WAREHOUSE_MANAGER's declared ceiling -- the override must not grant it");
        assertFalse(RolePermissions.hasPermission(
                warehouseManager, Permission.READ_EXECUTIONS, appDeclaredRoles, overrides),
                "the override narrows to {EXECUTE_FLOW, READ_AUDIT} -- READ_EXECUTIONS was not re-granted");
    }

    /** A role with no override entry at all is unaffected: its full declared ceiling still applies. */
    @Test
    void roleWithNoConfiguredOverrideKeepsItsFullCeiling() {
        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("WAREHOUSE_MANAGER"));
        Map<String, Set<Permission>> appDeclaredRoles = Map.of(
                "WAREHOUSE_MANAGER",
                Set.of(Permission.EXECUTE_FLOW, Permission.READ_EXECUTIONS, Permission.READ_FLOW_DEFINITIONS));

        assertTrue(RolePermissions.hasPermission(
                warehouseManager, Permission.READ_EXECUTIONS, appDeclaredRoles, Map.of()));
        assertTrue(RolePermissions.hasPermission(
                warehouseManager, Permission.READ_FLOW_DEFINITIONS, appDeclaredRoles, null));
    }

    /** An override row with an unrecognized permission name is dropped, not thrown -- it can only
     *  narrow further, never widen, so it is safe to ignore rather than fail the whole check. */
    @Test
    void unrecognizedPermissionNameInOverrideRowIsIgnoredNotThrown() {
        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("WAREHOUSE_MANAGER"));
        Map<String, Set<Permission>> appDeclaredRoles = Map.of(
                "WAREHOUSE_MANAGER", Set.of(Permission.EXECUTE_FLOW));
        Map<String, Set<String>> overrides = Map.of(
                "WAREHOUSE_MANAGER", Set.of("EXECUTE_FLOW", "NOT_A_REAL_PERMISSION"));

        assertTrue(RolePermissions.hasPermission(
                warehouseManager, Permission.EXECUTE_FLOW, appDeclaredRoles, overrides));
    }

    /** Built-in roles (USER/OPERATOR/ADMIN) can also be narrowed by a runtime override -- the ceiling
     *  intersection applies uniformly, not only to app-declared roles. */
    @Test
    void builtInRoleCanAlsoBeNarrowedByARuntimeOverride() {
        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));
        Map<String, Set<String>> overrides = Map.of("ADMIN", Set.of("READ_TRACES"));

        assertTrue(RolePermissions.hasPermission(admin, Permission.READ_TRACES, Map.of(), overrides));
        assertFalse(RolePermissions.hasPermission(admin, Permission.READ_AUDIT, Map.of(), overrides),
                "ADMIN's full ceiling includes READ_AUDIT, but the runtime override narrowed to READ_TRACES only");
    }
}
