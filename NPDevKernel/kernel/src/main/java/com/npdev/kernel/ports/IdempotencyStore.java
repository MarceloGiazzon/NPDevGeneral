package com.npdev.kernel.ports;

import com.npdev.kernel.capability.IdempotencyRecord;

import java.util.Optional;

public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(String tenantId, String capability, String operation, String idempotencyKey);

    void saveSuccess(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            String resultJsonRedacted,
            long createdAtMs
    );

    void saveFailure(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            String errorCode,
            long createdAtMs
    );

    static IdempotencyStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final IdempotencyStore INSTANCE = new IdempotencyStore() {
            @Override
            public Optional<IdempotencyRecord> find(String tenantId, String capability, String operation, String idempotencyKey) {
                return Optional.empty();
            }

            @Override
            public void saveSuccess(String tenantId, String capability, String operation, String idempotencyKey, String resultJsonRedacted, long createdAtMs) {
            }

            @Override
            public void saveFailure(String tenantId, String capability, String operation, String idempotencyKey, String errorCode, long createdAtMs) {
            }
        };

        private NoopHolder() {
        }
    }
}