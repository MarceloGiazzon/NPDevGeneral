package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;

import java.util.List;
import java.util.Optional;

public interface ConceptGateway {
    Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context);

    List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context);

    /**
     * LNCH-5: tenant- and permission-enforced paged query (filter/sort/page) returning a
     * {@link ConceptPage}. The default evaluates the query in memory over {@link #list} results, so
     * any gateway gets correct behaviour for free; {@code DefaultConceptGateway} overrides it to push
     * the window down to the {@code ConceptStore} (and thus to SQL) instead of materializing every
     * row. Same isolation and field-visibility guarantees as {@code list}.
     */
    default ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
        List<ConceptRecord> all = list(
                new ConceptListRequest(request.conceptName(), request.tenantId(), null, null), context);
        return ConceptQueryEngine.apply(all, request.query());
    }

    ConceptRecord save(ConceptWriteRequest request, ExecutionContext context);

    void delete(ConceptReadRequest request, ExecutionContext context);

    default List<ConceptGatewayTraceRecord> explain() {
        return List.of();
    }
}
