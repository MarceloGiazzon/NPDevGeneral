package com.npdev.kernel.concepts;

import java.util.Objects;

/**
 * Move 10 B1 (LC-B1): a gateway-level aggregate query request. Mirrors {@link ConceptQueryRequest}
 * but carries a {@link ConceptAggregateQuery} and returns a {@link ConceptAggregateResult} (rows of
 * aggregate output, not concept records).
 */
public record ConceptAggregateRequest(String conceptName, String tenantId, ConceptAggregateQuery query) {
    public ConceptAggregateRequest {
        if (conceptName == null || conceptName.isBlank()) {
            throw new IllegalArgumentException("conceptName must be non-blank");
        }
        conceptName = conceptName.trim();
        tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        Objects.requireNonNull(query, "query");
    }

    public ConceptAggregateRequest(String conceptName, ConceptAggregateQuery query) {
        this(conceptName, null, query);
    }
}
