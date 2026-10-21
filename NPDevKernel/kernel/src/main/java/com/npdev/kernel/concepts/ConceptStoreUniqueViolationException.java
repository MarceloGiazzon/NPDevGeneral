package com.npdev.kernel.concepts;

import java.util.List;

/**
 * Thrown by a {@link com.npdev.kernel.ports.ConceptStore} that enforces a declared {@code unique}
 * concept index in application code (the in-memory store has no database to do it) when a write
 * would store a second live row with the same value(s). Storage-engine-agnostic counterpart to the
 * SQL unique-violation a physical database raises for the same unique index, so callers map it to
 * the same {@code unique_integrity_failed} response a JDBC-backed store already produces. The
 * message deliberately contains "unique constraint" so the generic message-based fallback in the
 * REST layer recognizes it too.
 */
public final class ConceptStoreUniqueViolationException extends RuntimeException {
    private final String conceptName;
    private final String indexName;
    private final List<String> fieldNames;

    public ConceptStoreUniqueViolationException(String conceptName, String indexName, List<String> fieldNames) {
        super("Unique constraint violated on " + conceptName
                + (indexName == null ? "" : " (index " + indexName + ")")
                + ": another row already has the same value for " + fieldNames);
        this.conceptName = conceptName;
        this.indexName = indexName;
        this.fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }

    public String getConceptName() {
        return conceptName;
    }

    public String getIndexName() {
        return indexName;
    }

    public List<String> getFieldNames() {
        return fieldNames;
    }
}
