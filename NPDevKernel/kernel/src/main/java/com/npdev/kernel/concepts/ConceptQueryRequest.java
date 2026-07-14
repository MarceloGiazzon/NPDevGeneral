package com.npdev.kernel.concepts;

import java.util.Objects;

/**
 * LNCH-5: a gateway-level paged query request. Mirrors {@link ConceptListRequest} but carries a full
 * {@link ConceptQuery} (filter/sort/page) instead of a single exact-match field, and returns a
 * {@link ConceptPage}. Tenant and permission enforcement, plus semantic field-visibility filtering,
 * apply exactly as they do for {@code list}.
 */
public record ConceptQueryRequest(String conceptName, String tenantId, ConceptQuery query) {
    public ConceptQueryRequest {
        if (conceptName == null || conceptName.isBlank()) {
            throw new IllegalArgumentException("conceptName must be non-blank");
        }
        conceptName = conceptName.trim();
        tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        query = query == null ? ConceptQuery.firstPage() : query;
    }

    public ConceptQueryRequest(String conceptName, ConceptQuery query) {
        this(conceptName, null, query);
    }
}
