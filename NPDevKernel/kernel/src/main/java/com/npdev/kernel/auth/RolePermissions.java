package com.npdev.kernel.auth;

import com.npdev.kernel.ExecutionContext;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class RolePermissions {
    private static final Logger LOGGER = Logger.getLogger(RolePermissions.class.getName());
    private static final Map<Role, Set<Permission>> ROLE_TO_PERMISSIONS = Map.of(
            Role.USER,
            EnumSet.of(
                    Permission.EXECUTE_FLOW,
                    Permission.READ_FLOW_DEFINITIONS,
                    Permission.READ_EXECUTIONS,
                    Permission.READ_TRACES,
                    Permission.READ_EVENTS
            ),
            Role.OPERATOR,
            EnumSet.of(
                    Permission.EXECUTE_FLOW,
                    Permission.READ_FLOW_DEFINITIONS,
                    Permission.READ_EXECUTIONS,
                    Permission.RESUME_EXECUTIONS,
                    Permission.PUBLISH_EVENTS,
                    Permission.READ_TRACES,
                    Permission.READ_FAILURES,
                    Permission.READ_STUCK,
                    Permission.READ_EVENTS
            ),
            Role.ADMIN,
            EnumSet.allOf(Permission.class)
    );

    private RolePermissions() {
    }

    public static boolean hasPermission(ExecutionContext context, Permission permission) {
        if (context == null || permission == null) {
            return false;
        }
        Set<String> roles = context.roles();
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        for (String rawRole : roles) {
            Role role = toRole(rawRole);
            if (role == null) {
                continue;
            }
            Set<Permission> permissions = ROLE_TO_PERMISSIONS.get(role);
            if (permissions != null && permissions.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wave 3 (RC-B1, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN} Part B.1): model-aware overload --
     * {@code appDeclaredRoles} is the app model's own {@code roles[]} declarations, keyed by the
     * same normalized (trimmed, upper-cased) form {@link ExecutionContext} already stores its role
     * strings in. A role string that matches neither a built-in {@link Role} NOR an app-declared
     * role is an X0 case (docs/X0_SILENT_EXPRESSION_REGISTER.md): before this overload existed, it
     * was silently skipped by {@link #toRole} returning null and the loop {@code continue}-ing, so
     * an app-declared role granted nothing with no diagnostic anywhere -- now it is logged as a
     * named denial so a misconfigured/renamed role is discoverable instead of a silent no-permission.
     */
    public static boolean hasPermission(
            ExecutionContext context, Permission permission, Map<String, Set<Permission>> appDeclaredRoles) {
        if (context == null || permission == null) {
            return false;
        }
        Set<String> roles = context.roles();
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        Map<String, Set<Permission>> declared = appDeclaredRoles == null ? Map.of() : appDeclaredRoles;
        for (String rawRole : roles) {
            Role role = toRole(rawRole);
            if (role != null) {
                Set<Permission> permissions = ROLE_TO_PERMISSIONS.get(role);
                if (permissions != null && permissions.contains(permission)) {
                    return true;
                }
                continue;
            }
            String normalized = normalizeRoleName(rawRole);
            if (normalized == null) {
                continue;
            }
            Set<Permission> appPermissions = declared.get(normalized);
            if (appPermissions == null) {
                LOGGER.warning(() -> "Denying permission " + permission + " for actor '" + context.actorId()
                        + "': role '" + rawRole + "' is neither a built-in role (USER/OPERATOR/ADMIN) "
                        + "nor declared in the app model's roles[]");
                continue;
            }
            if (appPermissions.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wave 3 (RC-B1): normalizes an app-declared {@code RoleAst.name()}/{@code CompiledRole.name()}
     * to the same key form {@link #hasPermission(ExecutionContext, Permission, Map)} looks roles up
     * by, so callers building the {@code appDeclaredRoles} map need not duplicate this rule.
     */
    public static String normalizeRoleName(String roleName) {
        if (roleName == null) {
            return null;
        }
        String trimmed = roleName.trim();
        return trimmed.isBlank() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static Role toRole(String rawRole) {
        if (rawRole == null) {
            return null;
        }
        String normalized = rawRole.trim();
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
