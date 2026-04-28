package com.npdev.adapters.flowinstance.inproc;

import com.npdev.kernel.CorrelationOwnershipViolationException;
import com.npdev.kernel.ports.CorrelationOwnershipStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InProcCorrelationOwnershipStore implements CorrelationOwnershipStore {
    private final Map<String, String> ownersByCorrelation = new ConcurrentHashMap<>();

    @Override
    public Optional<String> findTenantByCorrelationId(String correlationId) {
        String effectiveCorrelationId = normalizeCorrelationId(correlationId);
        if (effectiveCorrelationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ownersByCorrelation.get(effectiveCorrelationId));
    }

    @Override
    public void claimCorrelation(String correlationId, String tenantId) throws CorrelationOwnershipViolationException {
        String effectiveCorrelationId = requireCorrelationId(correlationId);
        String effectiveTenantId = requireTenantId(tenantId);
        ownersByCorrelation.compute(effectiveCorrelationId, (key, currentOwner) -> {
            if (currentOwner == null) {
                return effectiveTenantId;
            }
            if (currentOwner.equals(effectiveTenantId)) {
                return currentOwner;
            }
            throw new CorrelationOwnershipViolationException(
                    effectiveCorrelationId,
                    currentOwner,
                    effectiveTenantId
            );
        });
    }

    public Map<String, String> snapshotOwners() {
        return Map.copyOf(ownersByCorrelation);
    }

    private static String requireCorrelationId(String correlationId) {
        String normalized = normalizeCorrelationId(correlationId);
        if (normalized == null) {
            throw new IllegalArgumentException("correlationId must be non-blank");
        }
        return normalized;
    }

    private static String normalizeCorrelationId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        String trimmed = correlationId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String requireTenantId(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must be non-blank");
        }
        String trimmed = tenantId.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("tenantId must be non-blank");
        }
        return trimmed;
    }
}
