package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.kernel.ports.TenantIsolationPolicy;

public final class DefaultTenantIsolationPolicy implements TenantIsolationPolicy {
    @Override
    public boolean sameTenant(String tenantA, String tenantB) {
        String left = normalize(tenantA);
        String right = normalize(tenantB);
        return left.equals(right);
    }

    private static String normalize(String tenantId) {
        if (tenantId == null) {
            return "default";
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? "default" : trimmed;
    }
}
