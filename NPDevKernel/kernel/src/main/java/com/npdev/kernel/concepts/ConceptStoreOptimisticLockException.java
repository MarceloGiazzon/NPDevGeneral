package com.npdev.kernel.concepts;

import java.util.Optional;

/**
 * LNCH-16: thrown by a {@link com.npdev.kernel.ports.ConceptStore#save} call that requested a
 * compare-and-increment (a non-null {@link ConceptRecord#rowVersion()}) when the stored row's
 * current version doesn't match -- either because someone else updated it since it was read, or
 * because it no longer exists. {@code currentRecord} is empty in the latter case.
 */
public final class ConceptStoreOptimisticLockException extends RuntimeException {
    private final String conceptName;
    private final String id;
    private final String tenantId;
    private final Optional<ConceptRecord> currentRecord;

    public ConceptStoreOptimisticLockException(
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
