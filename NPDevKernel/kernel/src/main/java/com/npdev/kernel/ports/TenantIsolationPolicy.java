package com.npdev.kernel.ports;

public interface TenantIsolationPolicy {
    TenantIsolationPolicy STRICT_EQUALS = (tenantA, tenantB) -> {
        String left = normalize(tenantA);
        String right = normalize(tenantB);
        return left.equals(right);
    };

    boolean sameTenant(String tenantA, String tenantB);

    /**
     * REG-52: lowercased to match {@code ExecutionContext.normalizeTenantId()}'s own REG-25
     * canonicalization. A per-request {@code tenantId} (e.g. {@code ConceptReadRequest}/
     * {@code ConceptWriteRequest}) only ever passes through that record's own trim-only
     * normalization -- never {@code ExecutionContext}'s constructor -- so without lowercasing here
     * too, {@code STRICT_EQUALS} could see the SAME logical tenant in two different cases
     * ({@code acme} from the context, {@code ACME} from the request) and wrongly deny it.
     */
    private static String normalize(String tenantId) {
        if (tenantId == null) {
            return "default";
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? "default" : trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
