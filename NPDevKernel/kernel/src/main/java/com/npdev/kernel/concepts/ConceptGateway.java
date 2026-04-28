package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;

import java.util.List;
import java.util.Optional;

public interface ConceptGateway {
    Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context);

    List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context);

    ConceptRecord save(ConceptWriteRequest request, ExecutionContext context);

    void delete(ConceptReadRequest request, ExecutionContext context);

    default List<ConceptGatewayTraceRecord> explain() {
        return List.of();
    }
}
