package com.npdev.kernel.concepts;

/**
 * Thrown by a {@link com.npdev.kernel.ports.ConceptStore} when a delete would violate a declared
 * bond's {@code onDelete: restrict} policy. Storage-engine-agnostic counterpart to the SQL
 * foreign-key-violation exception a physical database throws for the same case — adapters that
 * enforce this in application code (e.g. {@link com.npdev.kernel.inproc.InMemoryConceptStore})
 * throw this so callers can map it to the same {@code reference_integrity_failed} response shape
 * a JDBC-backed store already produces.
 */
public final class ReferentialIntegrityException extends RuntimeException {
    private final String conceptName;
    private final String fieldName;

    public ReferentialIntegrityException(String conceptName, String fieldName, String message) {
        super(message);
        this.conceptName = conceptName;
        this.fieldName = fieldName;
    }

    public String getConceptName() {
        return conceptName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
