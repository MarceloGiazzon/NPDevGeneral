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
     * Move 14 Phase C item C2 (RC-B3): overload that additionally applies a runtime-bound permission
     * SUBSET, if one is configured for the actor's role -- never a superset. {@code
     * actorPermissionOverrides} is keyed by role name (any casing; normalized internally the same way
     * {@code appDeclaredRoles} is) to the exact set of permission-name strings an administrator bound
     * at runtime for that (actor, role) pair, e.g. via {@code IdentityPermissionOverrideLookup}.
     *
     * <p><b>The ceiling is enforced structurally, not by convention:</b> a role's effective permission
     * set is always {@code declaredCeiling ∩ override} when an override is present for that role --
     * never the override alone, never a union. A row that names a permission outside the role's
     * declared {@code grants} (a bug, a hand-edited row, a downgraded model since the override was
     * written) is silently dropped by the intersection; it can never grant anything the model itself
     * doesn't already allow that role to hold. A role with NO entry in {@code actorPermissionOverrides}
     * is completely unaffected -- its full declared ceiling applies, exactly as before this overload
     * existed. This is what makes "an admin may grant any subset at runtime; never anything outside"
     * (the plan's own framing for RC-B3) a property of the code, not a promise about how the write side
     * behaves.</p>
     */
    public static boolean hasPermission(
            ExecutionContext context, Permission permission,
            Map<String, Set<Permission>> appDeclaredRoles,
            Map<String, Set<String>> actorPermissionOverrides) {
        if (context == null || permission == null) {
            return false;
        }
        Set<String> roles = context.roles();
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        Map<String, Set<Permission>> declared = appDeclaredRoles == null ? Map.of() : appDeclaredRoles;
        Map<String, Set<String>> normalizedOverrides = normalizeOverrideKeys(actorPermissionOverrides);
        for (String rawRole : roles) {
            Set<Permission> ceiling;
            Role builtIn = toRole(rawRole);
            if (builtIn != null) {
                ceiling = ROLE_TO_PERMISSIONS.get(builtIn);
            } else {
                String normalized = normalizeRoleName(rawRole);
                if (normalized == null) {
                    continue;
                }
                ceiling = declared.get(normalized);
                if (ceiling == null) {
                    LOGGER.warning(() -> "Denying permission " + permission + " for actor '" + context.actorId()
                            + "': role '" + rawRole + "' is neither a built-in role (USER/OPERATOR/ADMIN) "
                            + "nor declared in the app model's roles[]");
                    continue;
                }
            }
            if (ceiling == null || ceiling.isEmpty()) {
                continue;
            }
            if (effectivePermissions(rawRole, ceiling, normalizedOverrides).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Set<String>> normalizeOverrideKeys(Map<String, Set<String>> actorPermissionOverrides) {
        if (actorPermissionOverrides == null || actorPermissionOverrides.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> normalized = new java.util.HashMap<>();
        for (Map.Entry<String, Set<String>> entry : actorPermissionOverrides.entrySet()) {
            String key = normalizeRoleName(entry.getKey());
            if (key != null) {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }

    /**
     * {@code ceiling ∩ override} when an override exists for this role; {@code ceiling} unchanged
     * otherwise. An unrecognized permission NAME in an override row (never a recognized {@link
     * Permission} constant) is dropped rather than failing the whole lookup -- it can only ever narrow
     * the effective set further, never widen it, so silently ignoring it is safe.
     */
    private static Set<Permission> effectivePermissions(
            String rawRole, Set<Permission> ceiling, Map<String, Set<String>> normalizedOverrides) {
        String normalizedRole = normalizeRoleName(rawRole);
        Set<String> override = normalizedRole == null ? null : normalizedOverrides.get(normalizedRole);
        if (override == null) {
            return ceiling;
        }
        EnumSet<Permission> intersected = EnumSet.noneOf(Permission.class);
        for (String permissionName : override) {
            if (permissionName == null) {
                continue;
            }
            try {
                Permission requested = Permission.valueOf(permissionName.trim().toUpperCase(Locale.ROOT));
                if (ceiling.contains(requested)) {
                    intersected.add(requested);
                }
            } catch (IllegalArgumentException ignored) {
                // Not a recognized platform permission -- never grants anything; safe to ignore.
            }
        }
        return intersected;
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
