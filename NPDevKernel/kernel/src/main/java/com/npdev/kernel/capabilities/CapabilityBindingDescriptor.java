package com.npdev.kernel.capabilities;

import java.util.Objects;

public record CapabilityBindingDescriptor(
        String capability,
        String capabilityType,
        String adapterId,
        String adapterClass,
        String environment,
        String tenantId
) {
    public CapabilityBindingDescriptor {
        capability = normalizeRequired(capability, "capability");
        capabilityType = normalizeOptional(capabilityType);
        adapterId = normalizeRequired(adapterId, "adapterId");
        adapterClass = normalizeAdapterClass(adapterClass);
        environment = normalizeOptional(environment);
        tenantId = normalizeOptional(tenantId);
    }

    public boolean matches(
            String capability,
            String capabilityType,
            String tenantId,
            String environment
    ) {
        String requestedCapability = normalizeRequired(capability, "capability");
        String requestedType = normalizeOptional(capabilityType);
        String requestedTenant = normalizeOptional(tenantId);
        String requestedEnvironment = normalizeOptional(environment);

        if (!this.capability.equals(requestedCapability)) {
            return false;
        }
        if (!this.capabilityType.isBlank() && !this.capabilityType.equals(requestedType)) {
            return false;
        }
        if (!this.tenantId.isBlank() && !this.tenantId.equals(requestedTenant)) {
            return false;
        }
        if (!this.environment.isBlank() && !this.environment.equals(requestedEnvironment)) {
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

    private static String normalizeAdapterClass(String value) {
        return value == null ? "" : value.trim();
    }
}
