package com.npdev.adapters.runtime.validation;

public record RuntimeSettings(
        String mode,
        boolean schedulerEnabled,
        int schedulerBatchLimit,
        int schedulerTickMillis,
        boolean authEnabled,
        int apiMaxBodyBytes,
        int apiMaxJsonDepth,
        String datasourceUrl,
        String datasourceUser,
        String datasourcePassword,
        int circuitOpenAfterFailures,
        int circuitOpenSeconds,
        int bulkheadMaxConcurrentDefault,
        int idempotencyMaxBytes,
        String capabilityPolicyOverridesJson
) {
    private static final String DEFAULT_MODE = "inproc";

    public RuntimeSettings {
        mode = normalizeMode(mode);
        datasourceUrl = normalize(datasourceUrl);
        datasourceUser = normalize(datasourceUser);
        datasourcePassword = normalize(datasourcePassword);
        capabilityPolicyOverridesJson = normalize(capabilityPolicyOverridesJson);
    }

    public boolean isPostgresMode() {
        return "postgres".equalsIgnoreCase(mode);
    }

    public boolean isInProcMode() {
        return "inproc".equalsIgnoreCase(mode);
    }

    @Override
    public String toString() {
        return "RuntimeSettings["
                + "mode=" + mode
                + ", schedulerEnabled=" + schedulerEnabled
                + ", schedulerBatchLimit=" + schedulerBatchLimit
                + ", schedulerTickMillis=" + schedulerTickMillis
                + ", authEnabled=" + authEnabled
                + ", apiMaxBodyBytes=" + apiMaxBodyBytes
                + ", apiMaxJsonDepth=" + apiMaxJsonDepth
                + ", datasourceUrl=" + mask(datasourceUrl)
                + ", datasourceUser=" + mask(datasourceUser)
                + ", datasourcePassword=***"
                + ", circuitOpenAfterFailures=" + circuitOpenAfterFailures
                + ", circuitOpenSeconds=" + circuitOpenSeconds
                + ", bulkheadMaxConcurrentDefault=" + bulkheadMaxConcurrentDefault
                + ", idempotencyMaxBytes=" + idempotencyMaxBytes
                + ", capabilityPolicyOverridesJson=" + mask(capabilityPolicyOverridesJson)
                + ']';
    }

    private static String normalizeMode(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return DEFAULT_MODE;
        }
        String lower = normalized.toLowerCase();
        if (!"inproc".equals(lower) && !"postgres".equals(lower)) {
            return DEFAULT_MODE;
        }
        return lower;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
