package com.npdev.kernel.ports;

public interface TenantIsolationPolicy {
    TenantIsolationPolicy STRICT_EQUALS = (tenantA, tenantB) -> {
        String left = normalize(tenantA);
        String right = normalize(tenantB);
        return left.equals(right);
    };

    boolean sameTenant(String tenantA, String tenantB);

    private static String normalize(String tenantId) {
        if (tenantId == null) {
            return "default";
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? "default" : trimmed;
    }
}
