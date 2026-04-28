package com.npdev.kernel.auth;

import com.npdev.kernel.ExecutionContext;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RolePermissions {
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
