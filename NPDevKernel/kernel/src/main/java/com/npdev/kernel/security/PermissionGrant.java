package com.npdev.kernel.security;

public record PermissionGrant(
        String permission,
        String tenantId,
        String actorId,
        String role
) {
    public PermissionGrant {
        permission = normalizeRequired(permission, "permission");
        tenantId = normalizeOptional(tenantId);
        actorId = normalizeOptional(actorId);
        role = normalizeOptional(role);
    }

    public boolean matches(PermissionSubject subject, PermissionRequirement requirement) {
        if (!permission.equals(requirement.permission())) {
            return false;
        }
        if (!tenantId.isBlank() && !tenantId.equals(subject.tenantId())) {
            return false;
        }
        if (!actorId.isBlank() && !actorId.equals(subject.actorId())) {
            return false;
        }
        if (!role.isBlank() && !subject.roles().contains(role)) {
            return false;
        }
        return true;
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim().toLowerCase();
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
