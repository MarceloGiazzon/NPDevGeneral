package com.npdev.kernel.capabilities;

import java.util.List;

/**
 * Runtime overrides for selecting which adapterId should be used for a capability at runtime.
 * This keeps bindings declarative (inventory), while runtime selects the active adapter.
 */
public record RuntimeOverridesManifest(List<CapabilityOverride> capabilityOverrides) {

    public RuntimeOverridesManifest {
        capabilityOverrides = capabilityOverrides == null ? List.of() : List.copyOf(capabilityOverrides);
    }

    public static RuntimeOverridesManifest empty() {
        return new RuntimeOverridesManifest(List.of());
    }

    public record CapabilityOverride(
            String capability,
            String capabilityType,
            String tenantId,
            String environment,
            String useAdapterId
    ) {
        public CapabilityOverride {
            capability = normalizeRequired(capability, "capability");
            capabilityType = normalizeOptional(capabilityType);
            tenantId = normalizeOptional(tenantId);
            environment = normalizeOptional(environment);
            useAdapterId = normalizeRequired(useAdapterId, "useAdapterId");
        }

        public boolean matches(String capability, String capabilityType, String tenantId, String environment) {
            String cap = normalizeRequired(capability, "capability");
            String type = normalizeOptional(capabilityType);
            String tenant = normalizeOptional(tenantId);
            String env = normalizeOptional(environment);

            if (!this.capability.equals(cap)) return false;
            if (!this.capabilityType.isBlank() && !this.capabilityType.equals(type)) return false;
            if (!this.tenantId.isBlank() && !this.tenantId.equals(tenant)) return false;
            if (!this.environment.isBlank() && !this.environment.equals(env)) return false;
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

    static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim().toLowerCase();
    }

    static String normalizeOptional(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
