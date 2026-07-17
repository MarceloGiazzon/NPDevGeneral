package com.npdev.kernel.concepts;

import java.util.Optional;

/**
 * LNCH-16: gateway-level view of {@link ConceptStoreOptimisticLockException} -- thrown by
 * {@link ConceptGateway#save} when the caller supplied a non-null {@code expectedRowVersion} (and
 * did not set {@code force}) that no longer matches the stored row. Carries the current record so
 * the caller can implement the "reloaded -- reapply your change" v1 UX without a second round trip.
 */
public final class ConceptGatewayOptimisticLockException extends RuntimeException {
    private final String conceptName;
    private final String id;
    private final String tenantId;
    private final Optional<ConceptRecord> currentRecord;

    public ConceptGatewayOptimisticLockException(
            String conceptName, String id, String tenantId, Optional<ConceptRecord> currentRecord
    ) {
        super("Optimistic lock conflict on " + conceptName + " " + id);
        this.conceptName = conceptName;
        this.id = id;
        this.tenantId = tenantId;
        this.currentRecord = currentRecord == null ? Optional.empty() : currentRecord;
    }

    public String conceptName() {
        return conceptName;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public Optional<ConceptRecord> currentRecord() {
        return currentRecord;
    }
}
