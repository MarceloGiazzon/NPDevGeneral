package com.npdev.kernel.security;

public record PermissionRequirement(
        String permission,
        String resourceType,
        String resourceName
) {
    public PermissionRequirement {
        permission = normalizeRequired(permission, "permission");
        resourceType = normalizeOptional(resourceType);
        resourceName = normalizeOptional(resourceName);
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
