package com.npdev.kernel.ports;

import com.npdev.kernel.CorrelationOwnershipViolationException;

import java.util.Optional;

public interface CorrelationOwnershipStore {
    Optional<String> findTenantByCorrelationId(String correlationId);

    void claimCorrelation(String correlationId, String tenantId) throws CorrelationOwnershipViolationException;

    static CorrelationOwnershipStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final CorrelationOwnershipStore INSTANCE = new CorrelationOwnershipStore() {
            @Override
            public Optional<String> findTenantByCorrelationId(String correlationId) {
                return Optional.empty();
            }

            @Override
            public void claimCorrelation(String correlationId, String tenantId) {
            }
        };

        private NoopHolder() {
        }
    }
}
