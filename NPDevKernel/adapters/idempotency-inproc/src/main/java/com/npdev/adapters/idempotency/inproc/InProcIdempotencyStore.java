package com.npdev.adapters.idempotency.inproc;

import com.npdev.kernel.capability.IdempotencyKeys;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.ports.IdempotencyStore;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InProcIdempotencyStore implements IdempotencyStore {
    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey
    ) {
        return Optional.ofNullable(records.get(composeKey(tenantId, capability, operation, idempotencyKey)));
    }

    @Override
    public void saveSuccess(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            String resultJsonRedacted,
            long createdAtMs
    ) {
        // The record carries the BOUNDED key, matching what JdbcIdempotencyStore reads back out of the
        // column -- otherwise the two backends disagree on IdempotencyRecord.idempotencyKey().
        records.put(
                composeKey(tenantId, capability, operation, idempotencyKey),
                new IdempotencyRecord(
                        tenantId,
                        IdempotencyKeys.bound(idempotencyKey),
                        capability,
                        operation,
                        createdAtMs,
                        IdempotencyRecord.STATUS_SUCCESS,
                        resultJsonRedacted,
                        null
                )
        );
    }

    @Override
    public void saveFailure(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            String errorCode,
            long createdAtMs
    ) {
        records.put(
                composeKey(tenantId, capability, operation, idempotencyKey),
                new IdempotencyRecord(
                        tenantId,
                        IdempotencyKeys.bound(idempotencyKey),
                        capability,
                        operation,
                        createdAtMs,
                        IdempotencyRecord.STATUS_FAILED,
                        null,
                        errorCode
                )
        );
    }

    public java.util.List<IdempotencyRecord> snapshotRecords() {
        return records.values().stream()
                .sorted(java.util.Comparator
                        .comparing(IdempotencyRecord::tenantId)
                        .thenComparing(IdempotencyRecord::capabilityName)
                        .thenComparing(IdempotencyRecord::operationName)
                        .thenComparing(IdempotencyRecord::idempotencyKey))
                .toList();
    }

    /**
     * REG-36: the key is bounded here so the in-proc store agrees with the JDBC one on what a given
     * raw key resolves to. The in-proc map has no index-size limit of its own, so it would never have
     * failed -- and that is exactly the reason to bound it here too: a dev run that silently accepts a
     * key the production backend rejects hides the bug until deployment.
     */
    private static String composeKey(String tenantId, String capability, String operation, String idempotencyKey) {
        return normalize(tenantId) + "|" + normalize(capability) + "|" + normalize(operation) + "|"
                + IdempotencyKeys.bound(normalize(idempotencyKey));
    }

    private static String normalize(String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("key part must be non-blank");
        }
        return trimmed;
    }
}
